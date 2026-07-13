#!/usr/bin/env python3
"""Build the compact hosting/registrar operation catalog used by the iOS app."""

from __future__ import annotations

import argparse
import html
import json
import re
import urllib.parse
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options")
SENSITIVE_FIELD = re.compile(r"(?i)(?:authorization|credential|password|secret|token|api[_ -]?key)")
JSON_SECRET_VALUE = re.compile(
    r'(?i)("[^"\\]*(?:authorization|credential|password|secret|token|api[_ -]?key)[^"\\]*"\s*:\s*")[^"]*(")'
)
BEARER_VALUE = re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{16,}")
LONG_HEX_VALUE = re.compile(r"(?i)\b[0-9a-f]{40,}\b")


def clean(value: Any, limit: int = 700) -> str:
    if not isinstance(value, str):
        return ""
    value = re.sub(r"\s+", " ", value).strip()
    return value if len(value) <= limit else value[: limit - 1].rstrip() + "…"


def sanitize_examples(value: Any) -> Any:
    """Remove credential-shaped examples shipped by otherwise official schemas."""
    if isinstance(value, dict):
        result = {key: sanitize_examples(item) for key, item in value.items()}
        parameter_name = result.get("name")
        if isinstance(parameter_name, str) and SENSITIVE_FIELD.search(parameter_name) and "example" in result:
            result["example"] = ""
        return result
    if isinstance(value, list):
        return [sanitize_examples(item) for item in value]
    if isinstance(value, str):
        value = JSON_SECRET_VALUE.sub(r"\1<value>\2", value)
        value = BEARER_VALUE.sub(r"\1<token>", value)
        return LONG_HEX_VALUE.sub("<value>", value)
    return value


def placeholder(schema: dict[str, Any]) -> Any:
    if "example" in schema:
        return schema["example"]
    if "default" in schema:
        return schema["default"]
    values = schema.get("enum")
    if isinstance(values, list) and values:
        return values[0]
    kind = schema.get("type")
    if kind == "boolean":
        return False
    if kind in ("integer", "number"):
        return 0
    if kind == "array":
        return []
    if kind == "object" or "properties" in schema:
        return {}
    if schema.get("format") == "date-time":
        return "2026-01-01T00:00:00Z"
    if schema.get("format") == "date":
        return "2026-01-01"
    return ""


class OpenAPI:
    def __init__(self, spec: dict[str, Any]) -> None:
        self.spec = spec

    def resolve(self, value: Any) -> dict[str, Any]:
        if not isinstance(value, dict):
            return {}
        ref = value.get("$ref")
        if not isinstance(ref, str) or not ref.startswith("#/"):
            return value
        current: Any = self.spec
        for part in ref[2:].split("/"):
            current = current.get(part.replace("~1", "/").replace("~0", "~"), {})
            if not isinstance(current, dict):
                return {}
        return current

    def example(self, raw: Any, depth: int = 0) -> Any:
        if depth > 4:
            return None
        schema = self.resolve(raw)
        if "example" in schema or "default" in schema or schema.get("enum"):
            return placeholder(schema)
        for choice in ("oneOf", "anyOf"):
            if isinstance(schema.get(choice), list) and schema[choice]:
                return self.example(schema[choice][0], depth + 1)
        if isinstance(schema.get("allOf"), list):
            result: dict[str, Any] = {}
            for item in schema["allOf"]:
                value = self.example(item, depth + 1)
                if isinstance(value, dict):
                    result.update(value)
            return result
        if schema.get("type") == "object" or "properties" in schema:
            required = set(schema.get("required", []))
            result = {}
            for name, child in schema.get("properties", {}).items():
                value = self.example(child, depth + 1)
                if name in required or value not in (None, "", [], {}) or depth < 1:
                    result[name] = placeholder(self.resolve(child)) if value is None else value
            return result
        if schema.get("type") == "array":
            value = self.example(schema.get("items", {}), depth + 1)
            return [] if value is None else [value]
        return placeholder(schema)

    def parameter(self, raw: Any) -> dict[str, Any] | None:
        value = self.resolve(raw)
        name, location = value.get("name"), value.get("in")
        if not isinstance(name, str) or location not in ("path", "query", "header"):
            return None
        schema = self.resolve(value.get("schema", value))
        enums = schema.get("enum", [])
        example = schema.get("example", schema.get("default", value.get("x-example", "")))
        return {
            "name": name,
            "location": location,
            "required": bool(value.get("required", False) or location == "path"),
            "description": clean(value.get("description") or schema.get("description"), 400),
            "type": str(schema.get("type", "string")),
            "example": "" if example is None else str(example).lower() if isinstance(example, bool) else str(example),
            "enumValues": [str(item) for item in enums] if isinstance(enums, list) else [],
        }

    def operations(self, path_prefix_to_strip: str = "") -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for raw_path, path_item in self.spec.get("paths", {}).items():
            if not isinstance(path_item, dict):
                continue
            path = raw_path
            if path_prefix_to_strip and path.startswith(path_prefix_to_strip):
                path = path[len(path_prefix_to_strip):] or "/"
            for method in HTTP_METHODS:
                item = path_item.get(method)
                if not isinstance(item, dict):
                    continue
                parameters: list[dict[str, Any]] = []
                body_schema: Any = None
                for raw_parameter in [*path_item.get("parameters", []), *item.get("parameters", [])]:
                    resolved = self.resolve(raw_parameter)
                    if resolved.get("in") == "body":
                        body_schema = resolved.get("schema", {})
                        continue
                    parameter = self.parameter(raw_parameter)
                    if parameter and not any(
                        old["name"] == parameter["name"] and old["location"] == parameter["location"]
                        for old in parameters
                    ):
                        parameters.append(parameter)

                content_types: list[str] = []
                body_required = False
                request_body = self.resolve(item.get("requestBody", {}))
                content = request_body.get("content", {})
                if isinstance(content, dict) and content:
                    content_types = list(content)
                    preferred = next(
                        (kind for kind in ("application/json", "application/merge-patch+json") if kind in content),
                        content_types[0],
                    )
                    body_schema = content[preferred].get("schema", {})
                    body_required = bool(request_body.get("required", False))
                elif body_schema is not None:
                    content_types = item.get("consumes", self.spec.get("consumes", ["application/json"]))
                    body_required = True

                body_template = ""
                if body_schema is not None:
                    body_template = json.dumps(self.example(body_schema), indent=2, ensure_ascii=False)
                tags = item.get("tags") or [raw_path.strip("/").split("/")[0] or "General"]
                result.append({
                    "id": item.get("operationId") or f"{method}-{raw_path}",
                    "method": method.upper(),
                    "path": path,
                    "summary": clean(item.get("summary")) or f"{method.upper()} {path}",
                    "description": clean(item.get("description")),
                    "tags": [str(tag) for tag in tags],
                    "deprecated": bool(item.get("deprecated", False)),
                    "parameters": parameters,
                    "contentTypes": content_types,
                    "requestBodyRequired": body_required,
                    "bodyTemplate": body_template,
                })
        return result


def manual_operation(
    provider: str,
    method: str,
    path: str,
    summary: str,
    tag: str,
    parameters: list[str] | None = None,
    body: str = "",
) -> dict[str, Any]:
    values = []
    for name in parameters or []:
        location = "path" if "{" + name + "}" in path else "query"
        values.append({
            "name": name,
            "location": location,
            "required": location == "path",
            "description": "",
            "type": "string",
            "example": "",
            "enumValues": [],
        })
    return {
        "id": f"{provider}:{method.lower()}:{path}:{summary}",
        "method": method,
        "path": path,
        "summary": summary,
        "description": "",
        "tags": [tag],
        "deprecated": False,
        "parameters": values,
        "contentTypes": ["application/json"] if body else [],
        "requestBodyRequired": bool(body),
        "bodyTemplate": body,
    }


def command_operations(provider: str, base_path: str, prefix: str, groups: dict[str, list[str]]) -> list[dict[str, Any]]:
    operations = []
    for tag, commands in groups.items():
        for command in commands:
            full = f"{prefix}.{command}" if prefix else command
            separator = "&" if "?" in base_path else "?"
            path = f"{base_path}{separator}Command={full}" if provider == "namecheap" else f"{base_path}{separator}command={full}"
            operations.append(manual_operation(provider, "GET", path, full, tag))
    return operations


def namecheap_operations() -> list[dict[str, Any]]:
    groups = {
        "Domains": ["domains.getList", "domains.getContacts", "domains.create", "domains.getTldList", "domains.setContacts", "domains.check", "domains.reactivate", "domains.renew", "domains.getRegistrarLock", "domains.setRegistrarLock", "domains.getInfo"],
        "DNS": ["domains.dns.setDefault", "domains.dns.setCustom", "domains.dns.getList", "domains.dns.getHosts", "domains.dns.getEmailForwarding", "domains.dns.setEmailForwarding", "domains.dns.setHosts"],
        "Nameservers": ["domains.ns.create", "domains.ns.delete", "domains.ns.getInfo", "domains.ns.update"],
        "Transfers": ["domains.transfer.create", "domains.transfer.getStatus", "domains.transfer.updateStatus", "domains.transfer.getList"],
        "SSL": ["ssl.create", "ssl.getList", "ssl.parseCSR", "ssl.getApproverEmailList", "ssl.activate", "ssl.resendApproverEmail", "ssl.getInfo", "ssl.renew", "ssl.reissue", "ssl.resendfulfillmentemail", "ssl.purchasemoresans", "ssl.revokecertificate", "ssl.editDCVMethod"],
        "Users": ["users.getPricing", "users.getBalances", "users.changePassword", "users.update", "users.createaddfundsrequest", "users.getAddFundsStatus", "users.create", "users.login", "users.resetPassword"],
        "Addresses": ["users.address.create", "users.address.delete", "users.address.getInfo", "users.address.getList", "users.address.setDefault", "users.address.update"],
        "Privacy": ["domainprivacy.changeemailaddress", "domainprivacy.enable", "domainprivacy.disable", "domainprivacy.getList", "domainprivacy.renew"],
    }
    operations = []
    for tag, commands in groups.items():
        for command in commands:
            operations.append(manual_operation("namecheap", "GET", f"/xml.response?Command=namecheap.{command}", f"namecheap.{command}", tag))
    return operations


def simple_manual_catalogs() -> dict[str, list[dict[str, Any]]]:
    porkbun = [
        ("POST", "/ping", "Test authentication", "Account"),
        ("POST", "/pricing/get", "Get domain pricing", "Pricing"),
        ("POST", "/domain/listAll", "List all domains", "Domains"),
        ("POST", "/domain/checkDomain/{domain}", "Check domain availability", "Domains"),
        ("POST", "/domain/create/{domain}", "Register a domain", "Domains"),
        ("POST", "/domain/updateNs/{domain}", "Update nameservers", "Nameservers"),
        ("POST", "/domain/getNs/{domain}", "Get nameservers", "Nameservers"),
        ("POST", "/domain/addUrlForward/{domain}", "Add URL forwarding", "Forwarding"),
        ("POST", "/domain/getUrlForwarding/{domain}", "List URL forwarding", "Forwarding"),
        ("POST", "/domain/deleteUrlForward/{domain}/{id}", "Delete URL forwarding", "Forwarding"),
        ("POST", "/domain/updateAutoRenew/{domain}", "Update auto renew", "Domains"),
        ("POST", "/domain/renew/{domain}", "Renew domain", "Domains"),
        ("POST", "/dns/create/{domain}", "Create DNS record", "DNS"),
        ("POST", "/dns/edit/{domain}/{id}", "Edit DNS record", "DNS"),
        ("POST", "/dns/delete/{domain}/{id}", "Delete DNS record", "DNS"),
        ("POST", "/dns/retrieve/{domain}", "Retrieve DNS records", "DNS"),
        ("POST", "/dns/retrieveByNameType/{domain}/{type}/{name}", "Retrieve DNS records by name and type", "DNS"),
        ("POST", "/ssl/retrieve/{domain}", "Retrieve SSL certificate bundle", "SSL"),
    ]
    namesilo_names = [
        "registerDomain", "renewDomain", "transferDomain", "transferUpdate", "listDomains", "getDomainInfo", "checkRegisterAvailability", "checkTransferAvailability", "retrieveAuthCode", "changeNameServers", "getContacts", "contactList", "contactAdd", "contactUpdate", "domainUpdateRegistrant", "domainUpdateAdmin", "domainUpdateTech", "domainUpdateBilling", "addPrivacy", "removePrivacy", "addAutoRenewal", "removeAutoRenewal", "addRegistryLock", "removeRegistryLock", "dnsListRecords", "dnsAddRecord", "dnsUpdateRecord", "dnsDeleteRecord", "domainForward", "domainForwardSubDomain", "domainForwardEmail", "listRegisteredNameServers", "addRegisteredNameServer", "modifyRegisteredNameServer", "deleteRegisteredNameServer", "portfolioList", "portfolioAdd", "portfolioDelete", "accountBalance", "viewOrder", "listOrders", "listTransactions"
    ]
    dynadot_names = [
        "list_domain", "domain_info", "search", "register", "delete", "restore", "renew", "transfer", "transfer_status", "get_transfer_auth_code", "set_renew_option", "set_privacy", "set_lock", "set_note", "get_contact", "create_contact", "edit_contact", "set_domain_contacts", "get_dns", "set_dns2", "set_name_server", "set_email_forwarding", "set_domain_forwarding", "set_stealth_forwarding", "set_parking", "set_dyndns", "clear_dns", "get_nameserver", "register_nameserver", "modify_nameserver", "delete_nameserver", "get_account_info", "get_account_balance", "get_order_status", "get_price", "get_tld_price", "get_coupon", "list_expired_domain", "list_auction", "list_backorder", "place_backorder_request", "delete_backorder_request", "get_backorder_status", "list_marketplace", "buy_it_now", "make_offer", "get_sale_status", "push_domain", "folder_list", "folder_create", "folder_delete", "folder_set_domains"
    ]
    return {
        "registrar.porkbun": [manual_operation("porkbun", *item, parameters=re.findall(r"\{([^}]+)\}", item[1]), body="{}") for item in porkbun],
        "registrar.namecheap": namecheap_operations(),
        "registrar.nameSilo": [manual_operation("namesilo", "GET", f"/api/{name}", name, "NameSilo") for name in namesilo_names],
        "registrar.dynadot": [manual_operation("dynadot", "GET", f"/api3.json?command={name}", name, "Dynadot") for name in dynadot_names],
    }


def firebase_operations(spec: dict[str, Any]) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []

    def visit(resource: dict[str, Any], tags: list[str]) -> None:
        for name, method in resource.get("methods", {}).items():
            path = "/" + str(method.get("path", "")).removeprefix("v1beta1/")
            parameters = []
            for parameter_name, value in method.get("parameters", {}).items():
                location = "path" if value.get("location") == "path" else "query"
                parameters.append({
                    "name": parameter_name,
                    "location": location,
                    "required": bool(value.get("required", False) or location == "path"),
                    "description": clean(value.get("description"), 400),
                    "type": str(value.get("type", "string")),
                    "example": "",
                    "enumValues": [str(item) for item in value.get("enum", [])],
                })
            request = method.get("request", {})
            operations.append({
                "id": method.get("id", name),
                "method": method.get("httpMethod", "GET"),
                "path": path,
                "summary": clean(method.get("description"), 120) or name,
                "description": clean(method.get("description")),
                "tags": tags or ["Firebase Hosting"],
                "deprecated": False,
                "parameters": parameters,
                "contentTypes": ["application/json"] if request else [],
                "requestBodyRequired": bool(request),
                "bodyTemplate": "{}" if request else "",
            })
        for name, child in resource.get("resources", {}).items():
            visit(child, [*tags, name])

    visit(spec, [])
    return operations


def heroku_operations(spec: dict[str, Any]) -> list[dict[str, Any]]:
    operations = []
    for name, definition in spec.get("definitions", {}).items():
        for link in definition.get("links", []):
            raw_path = link.get("href", "")
            if not isinstance(raw_path, str) or not raw_path.startswith("/"):
                continue
            def replace_identity(match: re.Match[str]) -> str:
                decoded = urllib.parse.unquote(match.group(1))
                names = re.findall(r"/definitions/([^/()]+)", decoded)
                name = next((item for item in names if item != "identity"), "resource")
                return "{" + name.replace("-", "_") + "_id_or_name}"
            path = re.sub(r"\{\((.*?)\)\}", replace_identity, raw_path)
            parameters = []
            for path_name in re.findall(r"\{([^}]+)\}", path):
                parameters.append({
                    "name": path_name,
                    "location": "path",
                    "required": True,
                    "description": "Heroku resource identity (ID or name)",
                    "type": "string",
                    "example": "",
                    "enumValues": [],
                })
            for parameter_name, value in link.get("schema", {}).get("properties", {}).items():
                parameters.append({
                    "name": parameter_name,
                    "location": "query",
                    "required": parameter_name in link.get("schema", {}).get("required", []),
                    "description": clean(value.get("description"), 400),
                    "type": str(value.get("type", "string")),
                    "example": str(value.get("example", "")),
                    "enumValues": [str(item) for item in value.get("enum", [])],
                })
            method = link.get("method", "GET")
            body = ""
            if method not in ("GET", "HEAD", "DELETE") and link.get("schema"):
                body = json.dumps(OpenAPI(spec).example(link["schema"]), indent=2)
            operations.append({
                "id": f"heroku:{name}:{link.get('rel', '')}:{method}:{path}",
                "method": method,
                "path": path,
                "summary": clean(link.get("title")) or f"{method} {path}",
                "description": clean(link.get("description")),
                "tags": [definition.get("title", name)],
                "deprecated": "deprecated" in clean(link.get("description")).lower(),
                "parameters": parameters,
                "contentTypes": ["application/json"] if body else [],
                "requestBodyRequired": bool(body),
                "bodyTemplate": body,
            })
    return operations


def aws_operations(spec: dict[str, Any]) -> list[dict[str, Any]]:
    result = []
    shapes = spec.get("shapes", {})
    for name, item in spec.get("operations", {}).items():
        http = item.get("http", {})
        path = http.get("requestUri", "/")
        input_shape = shapes.get(item.get("input", {}).get("shape", ""), {})
        parameters = []
        body_properties: dict[str, Any] = {}
        required = set(input_shape.get("required", []))
        for member_name, member in input_shape.get("members", {}).items():
            location = member.get("location")
            if location in ("uri", "querystring", "header"):
                parameters.append({
                    "name": member.get("locationName", member_name),
                    "location": {"uri": "path", "querystring": "query", "header": "header"}[location],
                    "required": member_name in required or location == "uri",
                    "description": clean(member.get("documentation"), 400),
                    "type": "string",
                    "example": "",
                    "enumValues": [],
                })
            else:
                body_properties[member_name] = ""
        body = json.dumps(body_properties, indent=2) if body_properties else ""
        result.append({
            "id": name,
            "method": http.get("method", "POST"),
            "path": path,
            "summary": re.sub(r"(?<!^)(?=[A-Z])", " ", name),
            "description": clean(item.get("documentation")),
            "tags": [name.split("App", 1)[0] or "Amplify"],
            "deprecated": False,
            "parameters": parameters,
            "contentTypes": ["application/json"] if body else [],
            "requestBodyRequired": bool(body),
            "bodyTemplate": body,
        })
    return result


def gandi_operations(directory: Path) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    for page in sorted(directory.glob("gandi-*.html")):
        tag = page.stem.removeprefix("gandi-").replace("-", " ").title()
        text = page.read_text(errors="ignore")
        for block in re.findall(r'<div class="raml-resource">(.*?)(?=<div class="raml-resource">|<div class="api-meta-footer">)', text, re.S):
            path_match = re.search(r'<p class="subtitle">\s*https://api\.gandi\.net<strong>(/v5/.*?)</strong>', block, re.S)
            if not path_match:
                continue
            path = html.unescape(re.sub(r"<[^>]+>", "", path_match.group(1))).strip()
            for method, summary in re.findall(
                r'<span class="raml-method-verb[^>]*">(get|post|put|patch|delete|head)</span>\s*<span>(.*?)</span>',
                block,
                re.S | re.I,
            ):
                summary = clean(html.unescape(re.sub(r"<[^>]+>", " ", summary)))
                body = "{}" if method.lower() in ("post", "put", "patch") else ""
                operations.append(manual_operation(
                    "gandi",
                    method.upper(),
                    path,
                    summary or f"{method.upper()} {path}",
                    tag,
                    parameters=re.findall(r"\{([^}]+)\}", path),
                    body=body,
                ))
    return operations


def catalog(provider_id: str, title: str, version: str, source: str, description: str, operations: list[dict[str, Any]]) -> dict[str, Any]:
    operations.sort(key=lambda value: (value["tags"][0].lower(), value["summary"].lower(), value["method"], value["path"]))
    return {
        "id": provider_id,
        "title": title,
        "apiVersion": version,
        "sourceURL": source,
        "sourceDescription": description,
        "operations": operations,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    load = lambda name: json.loads((args.input / name).read_text())

    providers = [
        catalog("hosting.netlify", "Netlify", "2.57", "https://open-api.netlify.com/swagger.json", "Official Netlify OpenAPI definition", OpenAPI(load("netlify.json")).operations()),
        catalog("hosting.render", "Render", "1", "https://api-docs.render.com/openapi/render-public-api-1.json", "Official Render Public API definition", OpenAPI(load("render.json")).operations()),
        catalog("hosting.digitalOcean", "DigitalOcean", "2", "https://github.com/digitalocean/openapi", "Official DigitalOcean OpenAPI definition", OpenAPI(load("digitalocean.json")).operations()),
        catalog("hosting.fly", "Fly.io", "1", "https://machines-api-spec.fly.dev/openapi.json", "Official Fly Machines API definition", OpenAPI(load("fly.json")).operations(path_prefix_to_strip="/v1")),
        catalog("hosting.firebase", "Firebase Hosting", str(load("firebase.json").get("version", "v1beta1")), "https://firebasehosting.googleapis.com/$discovery/rest?version=v1beta1", "Official Google Discovery document", firebase_operations(load("firebase.json"))),
        catalog("hosting.heroku", "Heroku", "3", "https://api.heroku.com/schema", "Official Heroku Platform API schema", heroku_operations(load("heroku.json"))),
        catalog("hosting.awsAmplify", "AWS Amplify", "2017-07-25", "https://github.com/boto/botocore/tree/develop/botocore/data/amplify", "Official AWS service model", aws_operations(load("amplify.json"))),
        catalog("hosting.railway", "Railway", "GraphQL v2", "https://docs.railway.com/reference/public-api", "The live GraphQL schema is discovered with introspection after authentication", [manual_operation("railway", "POST", "/graphql/v2", "Complete live GraphQL schema", "GraphQL", body='{"query":"query IntrospectionQuery { __schema { queryType { name } mutationType { name } types { kind name fields(includeDeprecated: true) { name description isDeprecated deprecationReason args { name description type { kind name ofType { kind name ofType { kind name } } } defaultValue } type { kind name ofType { kind name ofType { kind name } } } } } } }"}')]),
        catalog("registrar.nameDotCom", "Name.com", "CORE v1", "https://namedotcom-cdn.name.tools/api-info/namecom.api.yaml", "Official Name.com CORE OpenAPI definition", OpenAPI(load("name.json")).operations()),
        catalog("registrar.spaceship", "Spaceship", "1.0.0", "https://docs.spaceship.dev/", "Official embedded Spaceship OpenAPI definition", OpenAPI(load("spaceship.json")).operations()),
        catalog("registrar.goDaddy", "GoDaddy", "1", "https://developer.godaddy.com/swagger/swagger_domains.json", "Official GoDaddy Domains API definition", OpenAPI(load("godaddy.json")).operations()),
    ]

    manual = simple_manual_catalogs()
    providers += [
        catalog(provider_id, title, "Current", source, "Official published operation directory", operations)
        for provider_id, title, operations, source in [
            ("registrar.namecheap", "Namecheap", manual["registrar.namecheap"], "https://www.namecheap.com/support/api/methods/"),
            ("registrar.porkbun", "Porkbun", manual["registrar.porkbun"], "https://docs.porkbun.com/api-reference"),
            ("registrar.dynadot", "Dynadot", manual["registrar.dynadot"], "https://www.dynadot.com/domain/api-commands"),
            ("registrar.nameSilo", "NameSilo", manual["registrar.nameSilo"], "https://www.namesilo.com/api-reference"),
        ]
    ]
    providers += [catalog(
        "registrar.gandi",
        "Gandi",
        "v5",
        "https://api.gandi.net/docs/",
        "Complete operation index parsed from Gandi's official v5 reference",
        gandi_operations(args.input),
    )]

    output = sanitize_examples({
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "providers": providers,
    })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"Generated {sum(len(item['operations']) for item in providers)} operations across {len(providers)} providers")


if __name__ == "__main__":
    main()
