#!/usr/bin/env python3
"""Generate the compact iOS Cloudflare API catalog from Cloudflare's OpenAPI JSON."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


HTTP_METHODS = ("get", "post", "put", "patch", "delete")
SENSITIVE_FIELD = re.compile(r"(?i)(?:authorization|credential|password|secret|token|api[_ -]?key|private[_ -]?key)")
JSON_SECRET_VALUE = re.compile(
    r'(?i)("[^"\\]*(?:authorization|credential|password|secret|token|api[_ -]?key|private[_ -]?key)[^"\\]*"\s*:\s*")[^"]*(")'
)
BEARER_VALUE = re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{16,}")
LONG_HEX_VALUE = re.compile(r"(?i)\b[0-9a-f]{40,}\b")
WEBHOOK_URL = re.compile(
    r"(?i)https://(?:hooks\.slack\.com/services|discord(?:app)?\.com/api/webhooks|api\.telegram\.org/bot)/[^\s\"']+"
)
PRIVATE_KEY = re.compile(
    r"-----BEGIN (?:RSA )?PRIVATE KEY-----.*?-----END (?:RSA )?PRIVATE KEY-----",
    re.DOTALL,
)
PRIVATE_KEY_BEGIN = re.compile(r"-----BEGIN (?:RSA )?PRIVATE KEY-----")


def sanitize_examples(value: Any) -> Any:
    """Remove credential-shaped examples shipped by the upstream schema."""
    if isinstance(value, dict):
        result = {key: sanitize_examples(item) for key, item in value.items()}
        name = result.get("name")
        if isinstance(name, str) and SENSITIVE_FIELD.search(name):
            for key in ("example", "default"):
                if key in result:
                    result[key] = ""
        return result
    if isinstance(value, list):
        return [sanitize_examples(item) for item in value]
    if isinstance(value, str):
        value = PRIVATE_KEY.sub("<private-key>", value)
        value = PRIVATE_KEY_BEGIN.sub("<private-key>", value)
        value = JSON_SECRET_VALUE.sub(r"\1<value>\2", value)
        value = BEARER_VALUE.sub(r"\1<token>", value)
        value = LONG_HEX_VALUE.sub("<value>", value)
        return WEBHOOK_URL.sub("https://example.invalid/webhook", value)
    return value


def clean_text(value: Any, limit: int = 900) -> str:
    if not isinstance(value, str):
        return ""
    text = re.sub(r"\s+", " ", value).strip()
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


class CatalogGenerator:
    def __init__(self, specification: dict[str, Any]) -> None:
        self.specification = specification

    def resolve(self, schema: Any) -> dict[str, Any]:
        if not isinstance(schema, dict):
            return {}
        reference = schema.get("$ref")
        if not isinstance(reference, str) or not reference.startswith("#/"):
            return schema
        current: Any = self.specification
        for segment in reference[2:].split("/"):
            current = current.get(segment.replace("~1", "/").replace("~0", "~"), {})
            if not isinstance(current, dict):
                return {}
        return current

    def schema_info(self, raw_schema: Any) -> dict[str, Any]:
        schema = self.resolve(raw_schema)
        if "allOf" in schema:
            merged: dict[str, Any] = {}
            for item in schema["allOf"]:
                merged.update(self.resolve(item))
            schema = {**merged, **{key: value for key, value in schema.items() if key != "allOf"}}

        result: dict[str, Any] = {}
        schema_type = schema.get("type")
        if not schema_type:
            if "properties" in schema:
                schema_type = "object"
            elif "items" in schema:
                schema_type = "array"
        if schema_type:
            result["type"] = schema_type
        for key in ("format", "default", "example", "minimum", "maximum", "minLength", "maxLength", "pattern"):
            if key in schema and schema[key] is not None:
                result[key] = schema[key]
        if isinstance(schema.get("enum"), list):
            result["enumValues"] = schema["enum"]
        description = clean_text(schema.get("description"), 500)
        if description:
            result["description"] = description
        return result

    def example_value(self, raw_schema: Any, depth: int = 0) -> Any:
        if depth > 5:
            return None
        schema = self.resolve(raw_schema)
        if "example" in schema:
            return schema["example"]
        if "default" in schema:
            return schema["default"]
        if isinstance(schema.get("enum"), list) and schema["enum"]:
            return schema["enum"][0]
        if "allOf" in schema:
            merged: dict[str, Any] = {}
            for item in schema["allOf"]:
                resolved = self.resolve(item)
                merged.update(resolved.get("properties", {}))
            if merged:
                return {name: self.example_value(value, depth + 1) for name, value in merged.items()}
        for choice_key in ("oneOf", "anyOf"):
            choices = schema.get(choice_key)
            if isinstance(choices, list) and choices:
                return self.example_value(choices[0], depth + 1)

        schema_type = schema.get("type")
        if schema_type == "object" or "properties" in schema:
            properties = schema.get("properties", {})
            required = set(schema.get("required", []))
            result: dict[str, Any] = {}
            for name, value in properties.items():
                field_schema = self.resolve(value)
                field_value = self.example_value(value, depth + 1)
                if field_value is not None or name in required or depth < 2:
                    result[name] = field_value if field_value is not None else self.placeholder(field_schema)
            return result
        if schema_type == "array":
            item = self.example_value(schema.get("items", {}), depth + 1)
            return [] if item is None else [item]
        return self.placeholder(schema)

    @staticmethod
    def placeholder(schema: dict[str, Any]) -> Any:
        schema_type = schema.get("type")
        schema_format = schema.get("format")
        if schema_type == "boolean":
            return False
        if schema_type in ("integer", "number"):
            return 0
        if schema_type == "array":
            return []
        if schema_type == "object":
            return {}
        if schema_format in ("date-time", "time"):
            return "2026-01-01T00:00:00Z"
        if schema_format == "date":
            return "2026-01-01"
        if schema_format == "binary":
            return "<FILE>"
        return ""

    def multipart_fields(self, raw_schema: Any) -> list[dict[str, Any]]:
        schema = self.resolve(raw_schema)
        fields: list[dict[str, Any]] = []
        for name, field_schema_raw in schema.get("properties", {}).items():
            field_schema = self.resolve(field_schema_raw)
            info = self.schema_info(field_schema)
            fields.append(
                {
                    "name": name,
                    "required": name in set(schema.get("required", [])),
                    "isFile": info.get("format") == "binary",
                    **info,
                }
            )
        return fields

    def parameter(self, parameter_raw: Any) -> dict[str, Any] | None:
        parameter = self.resolve(parameter_raw)
        name = parameter.get("name")
        location = parameter.get("in")
        if not isinstance(name, str) or location not in ("path", "query", "header"):
            return None
        info = self.schema_info(parameter.get("schema", {}))
        description = clean_text(parameter.get("description"), 500) or info.pop("description", "")
        return {
            "name": name,
            "location": location,
            "required": bool(parameter.get("required", False) or location == "path"),
            "description": description,
            **info,
        }

    def operation(
        self,
        path: str,
        method: str,
        path_item: dict[str, Any],
        operation: dict[str, Any],
    ) -> dict[str, Any]:
        parameters: list[dict[str, Any]] = []
        for item in [*path_item.get("parameters", []), *operation.get("parameters", [])]:
            converted = self.parameter(item)
            if converted and not any(
                value["name"] == converted["name"] and value["location"] == converted["location"]
                for value in parameters
            ):
                parameters.append(converted)

        content_types: list[str] = []
        body_template = ""
        multipart_fields: list[dict[str, Any]] = []
        request_body = self.resolve(operation.get("requestBody", {}))
        content = request_body.get("content", {})
        if isinstance(content, dict):
            content_types = list(content.keys())
            preferred_type = next(
                (value for value in ("application/json", "multipart/form-data", "application/x-www-form-urlencoded") if value in content),
                content_types[0] if content_types else "",
            )
            if preferred_type:
                content_types = [preferred_type, *[value for value in content_types if value != preferred_type]]
                schema = content.get(preferred_type, {}).get("schema", {})
                if preferred_type == "application/json" or preferred_type.endswith("+json"):
                    body_template = json.dumps(self.example_value(schema), indent=2, ensure_ascii=False)
                elif preferred_type in ("multipart/form-data", "application/x-www-form-urlencoded"):
                    multipart_fields = self.multipart_fields(schema)

        tags = operation.get("tags") or ["Other"]
        permissions = operation.get("x-api-token-group") or []
        security = operation.get("security", self.specification.get("security", []))
        security_keys = {
            key
            for requirement in security
            if isinstance(requirement, dict)
            for key in requirement.keys()
        }
        unrestricted = not security_keys
        return {
            "id": operation.get("operationId") or f"{method}-{path}",
            "method": method.upper(),
            "path": path,
            "summary": clean_text(operation.get("summary")) or f"{method.upper()} {path}",
            "description": clean_text(operation.get("description")),
            "tags": tags,
            "deprecated": bool(operation.get("deprecated", False)),
            "permissions": permissions,
            "supportsGlobalKey": unrestricted or ("api_email" in security_keys and "api_key" in security_keys),
            "supportsAPIToken": unrestricted or "api_token" in security_keys,
            "supportsUserServiceKey": "user_service_key" in security_keys,
            "parameters": parameters,
            "contentTypes": content_types,
            "requestBodyRequired": bool(request_body.get("required", False)),
            "bodyTemplate": body_template,
            "multipartFields": multipart_fields,
        }

    def generate(self, source_commit: str) -> dict[str, Any]:
        operations: list[dict[str, Any]] = []
        for path, path_item in self.specification.get("paths", {}).items():
            if not isinstance(path_item, dict):
                continue
            for method in HTTP_METHODS:
                operation = path_item.get(method)
                if isinstance(operation, dict):
                    operations.append(self.operation(path, method, path_item, operation))
        operations.sort(key=lambda value: (value["tags"][0].lower(), value["summary"].lower(), value["method"]))
        return {
            "schemaVersion": 1,
            "openAPIVersion": self.specification.get("openapi", ""),
            "apiVersion": self.specification.get("info", {}).get("version", ""),
            "sourceCommit": source_commit,
            "sourceURL": "https://github.com/cloudflare/api-schemas",
            "operationCount": len(operations),
            "operations": operations,
        }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("specification", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--source-commit", default="unknown")
    arguments = parser.parse_args()

    specification = sanitize_examples(json.loads(arguments.specification.read_text()))
    catalog = CatalogGenerator(specification).generate(arguments.source_commit)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"Generated {catalog['operationCount']} operations at {arguments.output}")


if __name__ == "__main__":
    main()
