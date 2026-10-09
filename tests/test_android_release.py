import importlib.util
import sys
import unittest
from unittest import mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/store" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gates = load("print_android_gates")
validate = load("validate_android_release")


class AndroidGateTests(unittest.TestCase):
    def test_everything_is_off_by_default(self):
        self.assertEqual(
            gates.effective_gates({}),
            {"android_release_enabled": "false", "play_upload": "false", "play_track": "", "upload_whats_new": "false"},
        )

    def test_track_needs_the_master_switch(self):
        result = gates.effective_gates({"STORE_PLAY_TRACK": "internal", "STORE_UPLOAD_WHATS_NEW": "true"})
        self.assertEqual(result["play_upload"], "false")
        self.assertEqual(result["upload_whats_new"], "false")

    def test_master_switch_must_be_exactly_true(self):
        result = gates.effective_gates({"STORE_ANDROID_RELEASE_ENABLED": "yes", "STORE_PLAY_TRACK": "internal"})
        self.assertEqual(result["android_release_enabled"], "false")
        self.assertEqual(result["play_upload"], "false")

    def test_master_switch_without_track_builds_but_skips_play(self):
        result = gates.effective_gates({"STORE_ANDROID_RELEASE_ENABLED": "true"})
        self.assertEqual(result["android_release_enabled"], "true")
        self.assertEqual(result["play_upload"], "false")

    def test_testing_tracks_map_to_play_track_ids(self):
        for value, track in (("internal", "internal"), ("Closed", "alpha")):
            result = gates.effective_gates({"STORE_ANDROID_RELEASE_ENABLED": "true", "STORE_PLAY_TRACK": value})
            self.assertEqual((result["play_upload"], result["play_track"]), ("true", track))

    def test_production_and_unknown_tracks_fail_closed(self):
        for value in ("production", "beta", "alpha"):
            with self.assertRaises(ValueError):
                gates.effective_gates({"STORE_ANDROID_RELEASE_ENABLED": "true", "STORE_PLAY_TRACK": value})


class AndroidValidationTests(unittest.TestCase):
    def test_reads_the_single_version(self):
        self.assertEqual(validate.android_version('    versionCode = 44\n    versionName = "1.0"\n'), ("1.0", 44))

    def test_rejects_ambiguous_versions(self):
        with self.assertRaises(ValueError):
            validate.android_version('versionCode = 1\nversionName = "1.0"\nversionName = "2.0"\n')

    def test_whats_new_is_plain_text(self):
        text = validate.play_whats_new("- **Faster** [deploys](https://example.com)\n- `Logs`")
        self.assertEqual(text, "• Faster deploys\n• Logs")

    def test_whats_new_respects_the_play_limit(self):
        with self.assertRaises(ValueError):
            validate.play_whats_new("x" * 501)

    def test_source_catalog_matches_the_android_billing_ids(self):
        with mock.patch.object(sys, "argv", ["validate_android_release.py"]):
            validate.main()


if __name__ == "__main__":
    unittest.main()
