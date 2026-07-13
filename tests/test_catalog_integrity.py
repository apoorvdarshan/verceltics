import json
import plistlib
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROVIDER_CATALOG = ROOT / "ios/verceltics/Resources/ProviderAPICatalog.json"
CLOUDFLARE_CATALOG = ROOT / "ios/verceltics/Resources/CloudflareAPICatalog.json"
PRIVACY_MANIFEST = ROOT / "ios/verceltics/PrivacyInfo.xcprivacy"


class ProviderCatalogIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle = json.loads(PROVIDER_CATALOG.read_text())
        cls.cloudflare = json.loads(CLOUDFLARE_CATALOG.read_text())

    def test_all_supported_providers_have_catalogs(self):
        expected = {
            "hosting.netlify", "hosting.railway", "hosting.render", "hosting.digitalOcean",
            "hosting.heroku", "hosting.fly", "hosting.firebase", "hosting.awsAmplify",
            "registrar.nameDotCom", "registrar.namecheap", "registrar.porkbun",
            "registrar.spaceship", "registrar.dynadot", "registrar.nameSilo",
            "registrar.gandi", "registrar.goDaddy",
        }
        self.assertEqual(expected, {provider["id"] for provider in self.bundle["providers"]})

    def test_operation_and_parameter_ids_are_unique(self):
        for provider in self.bundle["providers"]:
            with self.subTest(provider=provider["id"]):
                operation_ids = [operation["id"] for operation in provider["operations"]]
                self.assertEqual(len(operation_ids), len(set(operation_ids)))
                for operation in provider["operations"]:
                    parameter_ids = [
                        (parameter["location"], parameter["name"])
                        for parameter in operation["parameters"]
                    ]
                    self.assertEqual(len(parameter_ids), len(set(parameter_ids)), operation["id"])

    def test_every_path_placeholder_has_an_editor(self):
        for provider in self.bundle["providers"]:
            for operation in provider["operations"]:
                placeholders = set(re.findall(r"\{\+?([^}]+)\}", operation["path"]))
                path_parameters = {
                    parameter["name"]
                    for parameter in operation["parameters"]
                    if parameter["location"] == "path"
                }
                self.assertLessEqual(
                    placeholders,
                    path_parameters,
                    f"{provider['id']} {operation['method']} {operation['path']}",
                )

    def test_paths_and_methods_are_safe_for_relative_explorer(self):
        allowed_methods = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}
        for provider in self.bundle["providers"]:
            for operation in provider["operations"]:
                self.assertTrue(operation["path"].startswith("/"), operation["id"])
                self.assertFalse(operation["path"].startswith("//"), operation["id"])
                self.assertNotRegex(operation["path"], r"[<>]", operation["id"])
                self.assertIn(operation["method"], allowed_methods, operation["id"])

    def test_catalog_does_not_ship_credential_shaped_examples(self):
        serialized = json.dumps([self.bundle, self.cloudflare]).lower()
        for marker in (
            "hooks.slack.com/services/",
            "discord.com/api/webhooks/",
            "discordapp.com/api/webhooks/",
            "api.telegram.org/bot/",
            "bearer eyj",
            "-----begin private key-----",
            "-----begin rsa private key-----",
        ):
            self.assertNotIn(marker, serialized)

    def test_cloudflare_catalog_metadata_matches_operations(self):
        self.assertRegex(self.cloudflare["sourceCommit"], r"^[0-9a-f]{40}$")
        self.assertEqual(self.cloudflare["operationCount"], len(self.cloudflare["operations"]))

    def test_official_porkbun_schema_is_present(self):
        porkbun = next(provider for provider in self.bundle["providers"] if provider["id"] == "registrar.porkbun")
        self.assertEqual("https://porkbun.com/api/json/v3/spec", porkbun["sourceURL"])
        self.assertGreaterEqual(len(porkbun["operations"]), 60)

    def test_every_provider_operation_has_multipart_metadata(self):
        for provider in self.bundle["providers"]:
            for operation in provider["operations"]:
                self.assertIsInstance(operation.get("multipartFields"), list, operation["id"])


class AppComplianceTests(unittest.TestCase):
    def test_app_privacy_manifest_declares_user_defaults(self):
        manifest = plistlib.loads(PRIVACY_MANIFEST.read_bytes())
        entries = manifest["NSPrivacyAccessedAPITypes"]
        user_defaults = next(
            entry for entry in entries
            if entry["NSPrivacyAccessedAPIType"] == "NSPrivacyAccessedAPICategoryUserDefaults"
        )
        self.assertIn("CA92.1", user_defaults["NSPrivacyAccessedAPITypeReasons"])

    def test_hosting_logout_does_not_clear_registrars(self):
        helper = (ROOT / "ios/verceltics/Auth/KeychainHelper.swift").read_text()
        hosting_delete = helper.split("static func deleteHostingAccounts()", 1)[1].split("private static", 1)[0]
        self.assertNotIn("registrarAccountsKey", hosting_delete)
        self.assertNotIn("activeRegistrarAccountIdKey", hosting_delete)


if __name__ == "__main__":
    unittest.main()
