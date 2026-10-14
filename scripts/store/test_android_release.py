"""Offline tests for the Android release: switches, artifact checks, Play read-back,
Play listing/What's New/screenshots, and the product catalog.

No network: Play is never contacted. Run: python3 -m unittest scripts/store/test_android_release.py
"""
from __future__ import annotations

import base64
import hashlib
import io
import os
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_release as ar  # noqa: E402
import play_listing  # noqa: E402
import release_notes  # noqa: E402
import validate_catalog  # noqa: E402

ALL_ON = {name: "true" for name, _ in ar.OPERATION_SWITCHES}


def gates(env=None, *, event="push", ref="android-v1.1", version="1.1"):
    return ar.resolve_gates(env or {}, event=event, ref_name=ref, version_name=version)


def pem(der: bytes) -> str:
    body = base64.b64encode(der).decode()
    return f"-----BEGIN CERTIFICATE-----\n{body}\n-----END CERTIFICATE-----\n"


UPLOAD_DER = b"upload-certificate"
UPLOAD = hashlib.sha256(UPLOAD_DER).hexdigest()
DEBUG = hashlib.sha256(b"debug-certificate").hexdigest()
IDENTITY = {"package": ar.PACKAGE_NAME, "version_code": 42, "version_name": "1.1"}


class SwitchTests(unittest.TestCase):
    def test_missing_settings_mean_nothing_runs(self):
        result = gates()
        self.assertFalse(result["build"])
        self.assertFalse(result["github_release"])
        self.assertFalse(result["play_upload"])
        self.assertEqual(result["play_tracks"], "")
        self.assertTrue(any("is off" in note for note in result["notes"]))

    def test_master_off_ignores_every_operation(self):
        result = gates(ALL_ON)
        for _, key in ar.OPERATION_SWITCHES:
            self.assertFalse(result[key], key)
        self.assertFalse(result["play_upload"])
        self.assertTrue(any("ANDROID_PRODUCTION_ROLLOUT" in note for note in result["notes"]))

    def test_master_alone_builds_and_releases_on_github_only(self):
        result = gates({ar.MASTER: "true"})
        self.assertTrue(result["build"])
        self.assertTrue(result["github_release"])
        self.assertFalse(result["play_upload"])
        self.assertFalse(result["listing"])

    def test_invalid_values_fail_even_when_master_is_off(self):
        for name, _ in ar.OPERATION_SWITCHES:
            with self.subTest(name=name), self.assertRaises(ar.ReleaseError):
                gates({name: "maybe"})
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "enabled"})

    def test_switch_values_are_case_and_space_insensitive(self):
        result = gates({ar.MASTER: " YES ", "ANDROID_INTERNAL_TESTING": "On"})
        self.assertEqual(result["play_tracks"], "internal")
        result = gates({ar.MASTER: "1", "ANDROID_INTERNAL_TESTING": "0"})
        self.assertFalse(result["play_upload"])

    def test_each_track_switch_maps_to_its_play_track(self):
        for name, track in (
            ("ANDROID_INTERNAL_TESTING", "internal"),
            ("ANDROID_CLOSED_TESTING", "alpha"),
            ("ANDROID_PRODUCTION_ROLLOUT", "production"),
        ):
            with self.subTest(name=name):
                result = gates({ar.MASTER: "true", name: "true"})
                self.assertTrue(result["play_upload"])
                self.assertEqual(result["play_tracks"], track)

    def test_testing_tracks_never_imply_production(self):
        result = gates(
            {ar.MASTER: "true", "ANDROID_INTERNAL_TESTING": "true", "ANDROID_CLOSED_TESTING": "true"}
        )
        self.assertEqual(result["play_tracks"], "internal,alpha")
        self.assertFalse(result["production"])

    def test_all_tracks_go_in_one_upload(self):
        result = gates({ar.MASTER: "true", **ALL_ON})
        self.assertEqual(result["play_tracks"], "internal,alpha,production")
        self.assertTrue(result["whats_new"])

    def test_listing_and_screenshots_need_no_track(self):
        result = gates(
            {ar.MASTER: "true", "ANDROID_UPLOAD_LISTING": "true", "ANDROID_UPLOAD_SCREENSHOTS": "true"}
        )
        self.assertTrue(result["listing"])
        self.assertTrue(result["screenshots"])
        self.assertFalse(result["play_upload"])

    def test_whats_new_without_a_track_is_skipped_with_a_note(self):
        result = gates({ar.MASTER: "true", "ANDROID_UPLOAD_WHATS_NEW": "true"})
        self.assertFalse(result["whats_new"])
        self.assertTrue(any("What's New" in note for note in result["notes"]))

    def test_tag_must_match_version_name(self):
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, ref="android-v1.0")
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, ref="ios-v1.1")
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, ref="android-v1.1.0")

    def test_manual_run_only_validates(self):
        result = gates({ar.MASTER: "true", **ALL_ON}, event="workflow_dispatch", ref="main")
        self.assertEqual(result["mode"], "validate")
        self.assertTrue(result["build"])
        self.assertFalse(result["github_release"])
        self.assertFalse(result["play_upload"])
        for _, key in ar.OPERATION_SWITCHES:
            self.assertFalse(result[key], key)

    def test_manual_run_still_rejects_invalid_values(self):
        with self.assertRaises(ar.ReleaseError):
            gates({"ANDROID_CLOSED_TESTING": "sure"}, event="workflow_dispatch", ref="main")

    def test_unsupported_event_fails(self):
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, event="pull_request")


class GradleVersionTests(unittest.TestCase):
    def test_reads_default_config_and_ignores_suffixes(self):
        text = """
            versionCode = 42
            versionName = "1.1"
            debug { versionNameSuffix = "-debug" }
        """
        self.assertEqual(ar.gradle_version(text), ("1.1", 42))

    def test_ambiguous_or_missing_version_fails(self):
        with self.assertRaises(ar.ReleaseError):
            ar.gradle_version('versionCode = 1\nversionName = "1.0"\nversionName = "1.1"\n')
        with self.assertRaises(ar.ReleaseError):
            ar.gradle_version('versionName = "1.0"\n')

    def test_repository_gradle_file_parses(self):
        name, code = ar.gradle_version(ar.GRADLE.read_text(encoding="utf-8"))
        self.assertRegex(name, r"^[0-9]+(\.[0-9]+){1,2}$")
        self.assertGreater(code, 0)


class ArtifactTests(unittest.TestCase):
    def test_pem_fingerprints_hash_the_der_bytes(self):
        text = "Signer #1:\n" + pem(UPLOAD_DER) + "noise\n" + pem(b"debug-certificate")
        self.assertEqual(ar.pem_fingerprints(text), {UPLOAD, DEBUG})

    def test_apksigner_fingerprints(self):
        output = (
            "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
            f"Signer #1 certificate DN: CN=Apoorv Darshan, O=Verceltics\nSigner #1 certificate SHA-256 digest: {UPLOAD.upper()}\n"
            "Signer #1 certificate SHA-1 digest: 0123\n"
        )
        self.assertEqual(ar.apksigner_fingerprints(output), {UPLOAD})

    def test_apksigner_fingerprints_newer_format_ignores_keys_and_stamps(self):
        output = (
            "Verifies\nNumber of signers: 1\n"
            f"V2 Signer: certificate SHA-256 digest: {UPLOAD}\n"
            f"V2 Signer: public key SHA-256 digest: {DEBUG}\n"
            f"Source Stamp Signer certificate SHA-256 digest: {DEBUG}\n"
        )
        self.assertEqual(ar.apksigner_fingerprints(output), {UPLOAD})

    def test_badging_identity(self):
        output = (
            "package: name='com.apoorvdarshan.verceltics' versionCode='42' versionName='1.1' "
            "platformBuildVersionName='16'\nsdkVersion:'26'\n"
        )
        found = ar.badging_identity(output)
        self.assertEqual(found, (ar.PACKAGE_NAME, 42, "1.1"))
        ar.check_identity("APK", found, **IDENTITY)

    def test_badging_without_package_line_fails(self):
        with self.assertRaises(ar.ReleaseError):
            ar.badging_identity("sdkVersion:'26'\n")

    def test_manifest_identity(self):
        xml = (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.apoorvdarshan.verceltics" android:versionCode="42" '
            'android:versionName="1.1"><application/></manifest>'
        )
        found = ar.manifest_identity(xml)
        self.assertEqual(found, (ar.PACKAGE_NAME, 42, "1.1"))

    def test_bad_manifest_fails(self):
        with self.assertRaises(ar.ReleaseError):
            ar.manifest_identity("not xml")
        with self.assertRaises(ar.ReleaseError):
            ar.manifest_identity('<manifest package="x"/>')

    def test_wrong_app_or_version_is_rejected(self):
        for found in (
            ("com.apoorvdarshan.verceltics.debug", 42, "1.1"),
            (ar.PACKAGE_NAME, 41, "1.1"),
            (ar.PACKAGE_NAME, 42, "1.1-debug"),
        ):
            with self.subTest(found=found), self.assertRaises(ar.ReleaseError):
                ar.check_identity("AAB", found, **IDENTITY)

    def test_only_the_upload_key_may_sign(self):
        ar.check_signers("APK", {UPLOAD}, upload_cert=UPLOAD)
        for found in (set(), {DEBUG}, {UPLOAD, DEBUG}):
            with self.subTest(found=found), self.assertRaises(ar.ReleaseError):
                ar.check_signers("APK", found, upload_cert=UPLOAD)


class SignatureCoverageTests(unittest.TestCase):
    LONG = "base/root/" + "x" * 80 + ".properties"

    def bundle(self, entries, manifest_names):
        manifest = "Manifest-Version: 1.0\r\n\r\n"
        for name in manifest_names:
            line = f"Name: {name}"
            # JAR manifests wrap at 72 bytes with a leading-space continuation.
            wrapped = "\r\n ".join([line[:72]] + [line[i:i + 71] for i in range(72, len(line), 71)])
            manifest += f"{wrapped}\r\nSHA-256-Digest: AAAA\r\n\r\n"
        handle = tempfile.NamedTemporaryFile(suffix=".aab", delete=False)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        with zipfile.ZipFile(handle.name, "w") as bundle:
            bundle.writestr("META-INF/MANIFEST.MF", manifest)
            bundle.writestr("META-INF/FUDAI.SF", "signature")
            bundle.writestr("META-INF/FUDAI.RSA", "block")
            bundle.writestr("base/", "")
            for name in entries:
                bundle.writestr(name, "data")
        return Path(handle.name)

    def test_fully_signed_bundle_passes(self):
        names = ["BundleConfig.pb", "base/manifest/AndroidManifest.xml", self.LONG]
        self.assertEqual(ar.unsigned_entries(self.bundle(names, names)), [])

    def test_added_unsigned_entry_is_reported(self):
        names = ["BundleConfig.pb", "base/dex/classes.dex"]
        found = ar.unsigned_entries(self.bundle(names + ["base/dex/classes2.dex"], names))
        self.assertEqual(found, ["base/dex/classes2.dex"])

    def test_bundle_without_manifest_is_entirely_unsigned(self):
        handle = tempfile.NamedTemporaryFile(suffix=".aab", delete=False)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        with zipfile.ZipFile(handle.name, "w") as bundle:
            bundle.writestr("BundleConfig.pb", "data")
        self.assertEqual(ar.unsigned_entries(Path(handle.name)), ["BundleConfig.pb"])


class TrackReadBackTests(unittest.TestCase):
    def fetcher(self, tracks):
        return lambda name: tracks[name]

    def test_completed_release_on_every_track_passes(self):
        tracks = {
            "internal": {"releases": [{"versionCodes": ["42"], "status": "completed"}]},
            "alpha": {
                "releases": [
                    {"versionCodes": ["41"], "status": "completed"},
                    {"versionCodes": ["42"], "status": "completed"},
                ]
            },
        }
        messages = ar.check_tracks(self.fetcher(tracks), ["internal", "alpha"], 42)
        self.assertEqual(len(messages), 2)
        self.assertIn("internal testing", messages[0])
        self.assertIn("closed testing", messages[1])
        self.assertIn("review", messages[1])

    def test_missing_or_unfinished_release_fails(self):
        for release in (
            {},
            {"releases": [{"versionCodes": ["41"], "status": "completed"}]},
            {"releases": [{"versionCodes": ["42"], "status": "draft"}]},
            {"releases": [{"versionCodes": ["42"], "status": "halted"}]},
        ):
            with self.subTest(release=release), self.assertRaises(ar.ReleaseError):
                ar.check_tracks(self.fetcher({"production": release}), ["production"], 42)


class ProductionAccessTests(unittest.TestCase):
    def test_production_switch_exists_and_warns_about_missing_access(self):
        result = gates({ar.MASTER: "true", "ANDROID_PRODUCTION_ROLLOUT": "true"})
        self.assertEqual(result["play_tracks"], "production")
        out = io.StringIO()
        env = {ar.MASTER: "true", "ANDROID_PRODUCTION_ROLLOUT": "true",
               "GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": f"android-v{ar.gradle_version(ar.GRADLE.read_text())[0]}"}
        with mock.patch.dict(os.environ, env, clear=True), redirect_stdout(out):
            ar.cmd_gates(None)
        self.assertIn("production access", out.getvalue())

    def test_closed_testing_is_the_alpha_track_and_never_production(self):
        result = gates({ar.MASTER: "true", "ANDROID_CLOSED_TESTING": "true"})
        self.assertEqual(result["play_tracks"], "alpha")
        self.assertFalse(result["production"])


LISTING = {
    "title.txt": "Verceltics: Hosting & Domains",
    "short_description.txt": "Hosting, domains, analytics & uptime in one native app.",
    "full_description.txt": "Verceltics brings your web stack to your phone.",
}


def write_play_listing(root: Path, locale: str = "en-US", **overrides: str | None) -> Path:
    loc = root / "play" / locale
    loc.mkdir(parents=True, exist_ok=True)
    for name, text in {**LISTING, **overrides}.items():
        if text is not None:
            (loc / name).write_text(text + "\n", encoding="utf-8")
    return loc


def png(width: int, height: int) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    chunk = b"IHDR" + ihdr
    return b"\x89PNG\r\n\x1a\n" + struct.pack(">I", len(ihdr)) + chunk + struct.pack(">I", zlib.crc32(chunk))


class PlayListingTests(unittest.TestCase):
    def test_repository_listing_and_screenshots_are_valid(self):
        for locale in play_listing.locales():
            values = play_listing.load_locale(play_listing.LISTING_DIR / locale)
            self.assertEqual(set(values), set(play_listing.FIELDS))
        self.assertGreaterEqual(len(play_listing.screenshot_files()), 2)

    def test_limits_and_missing_fields_fail(self):
        for name, limit in (("title.txt", 30), ("short_description.txt", 80), ("full_description.txt", 4000)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                loc = write_play_listing(Path(tmp), **{name: "x" * (limit + 1)})
                with self.assertRaisesRegex(play_listing.ListingError, f"limit is {limit}"):
                    play_listing.load_locale(loc)
            with self.subTest(missing=name), tempfile.TemporaryDirectory() as tmp:
                loc = write_play_listing(Path(tmp), **{name: None})
                with self.assertRaisesRegex(play_listing.ListingError, "missing"):
                    play_listing.load_locale(loc)
        with tempfile.TemporaryDirectory() as tmp:
            write_play_listing(Path(tmp), locale="de-DE")
            with self.assertRaisesRegex(play_listing.ListingError, "en-US"):
                play_listing.locales(Path(tmp) / "play")

    def test_whats_new_is_plain_text_within_the_play_limit(self):
        text = play_listing.play_whats_new("- **Faster** [deploys](https://example.com)\n- `Logs`")
        self.assertEqual(text, "• Faster deploys\n• Logs")
        with self.assertRaisesRegex(play_listing.ListingError, "500"):
            play_listing.play_whats_new("x" * 501)

    def test_prepare_writes_listing_and_the_tags_whats_new(self):
        notes = "## android-v1.1\n\n- **Faster** dashboards\n"
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            write_play_listing(Path(src))
            (Path(src) / "RELEASE_NOTES.md").write_text(notes)
            with mock.patch.object(release_notes, "ROOT", Path(src)):
                written = play_listing.prepare(Path(out), "android-v1.1", Path(src) / "play")
                self.assertEqual((Path(out) / "play" / "whatsnew-en-US").read_text(), "• Faster dashboards\n")
                self.assertEqual(len(written), 4)
                written = play_listing.prepare(Path(out) / "next", "android-v1.2", Path(src) / "play")
                self.assertFalse(any(p.name.startswith("whatsnew-") for p in written))

    def test_whats_new_needs_notes_that_fit_google_play(self):
        with tempfile.TemporaryDirectory() as src, mock.patch.object(release_notes, "ROOT", Path(src)):
            notes = Path(src) / "RELEASE_NOTES.md"
            notes.write_text("## android-v1.1\n\n- **Faster** sync\n")
            self.assertEqual(play_listing.whats_new("android-v1.1"), "• Faster sync")
            with self.assertRaisesRegex(play_listing.ListingError, "## android-v1.2"):
                play_listing.whats_new("android-v1.2")
            notes.write_text("## android-v1.1\n\n" + "x" * 501 + "\n")
            with self.assertRaisesRegex(play_listing.ListingError, "500"):
                play_listing.whats_new("android-v1.1")

    def test_long_notes_do_not_block_a_release_without_whats_new(self):
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            write_play_listing(Path(src))
            (Path(src) / "RELEASE_NOTES.md").write_text("## android-v1.1\n\n" + "x" * 900 + "\n")
            with mock.patch.object(release_notes, "ROOT", Path(src)):
                written = play_listing.prepare(Path(out), None, Path(src) / "play")
            self.assertFalse(any(p.name.startswith("whatsnew-") for p in written))

    def test_screenshots_need_two_to_eight_play_sized_pngs(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "1.png").write_bytes(png(1080, 1920))
            with self.assertRaisesRegex(play_listing.ListingError, "found 1"):
                play_listing.screenshot_files(folder)
            (folder / "2.png").write_bytes(png(1080, 1920))
            self.assertEqual(len(play_listing.screenshot_files(folder)), 2)
            (folder / "3.png").write_bytes(png(1080, 2400))
            with self.assertRaisesRegex(play_listing.ListingError, "twice"):
                play_listing.screenshot_files(folder)
            (folder / "3.png").write_bytes(png(200, 300))
            with self.assertRaisesRegex(play_listing.ListingError, "320"):
                play_listing.screenshot_files(folder)
            (folder / "3.png").unlink()
            for i in range(4, 11):
                (folder / f"{i}.png").write_bytes(png(1080, 1920))
            with self.assertRaisesRegex(play_listing.ListingError, "found 9"):
                play_listing.screenshot_files(folder)

    def run_upload(self, env, *args):
        out = io.StringIO()
        clean = {k: v for k, v in os.environ.items() if k not in {"UPLOAD_LISTING", "UPLOAD_SCREENSHOTS"}}
        with mock.patch.dict(os.environ, {**clean, **env}, clear=True), redirect_stdout(out), redirect_stderr(out):
            try:
                play_listing.main(["upload", "--dry-run", *args])
            except SystemExit as exc:
                return (exc.code if isinstance(exc.code, int) else 1), out.getvalue()
        return 0, out.getvalue()

    def test_upload_is_gated_and_validates_before_any_call(self):
        code, output = self.run_upload({})
        self.assertEqual(code, 0)
        self.assertIn("skipped", output)
        code, output = self.run_upload({"UPLOAD_LISTING": "true", "UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 0, output)
        self.assertIn("screenshots=6", output)
        with tempfile.TemporaryDirectory() as tmp:
            write_play_listing(Path(tmp), **{"title.txt": "t" * 31})
            code, output = self.run_upload({"UPLOAD_LISTING": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("title.txt", output)

    def test_one_edit_updates_listing_and_screenshots_then_commits(self):
        service = mock.MagicMock()
        edits = service.edits.return_value
        edits.insert.return_value.execute.return_value = {"id": "EDIT"}
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(sys.modules, {
            "googleapiclient": mock.MagicMock(), "googleapiclient.http": mock.MagicMock()}):
            write_play_listing(Path(tmp))
            shots = []
            for i in range(2):
                path = Path(tmp) / f"{i}.png"
                path.write_bytes(png(1080, 1920))
                shots.append(path)
            with redirect_stdout(io.StringIO()):
                play_listing.upload_play_store_listing(
                    service, package_name=play_listing.PACKAGE_NAME, metadata_dir=Path(tmp),
                    screenshots=shots, upload_listing=True, upload_screenshots=True,
                )
        body = edits.listings.return_value.update.call_args.kwargs["body"]
        self.assertEqual(body["title"], LISTING["title.txt"])
        self.assertEqual(edits.images.return_value.upload.call_count, 2)
        edits.commit.assert_called_once_with(packageName=play_listing.PACKAGE_NAME, editId="EDIT")
        edits.delete.assert_not_called()

    def test_a_failed_upload_discards_the_edit(self):
        service = mock.MagicMock()
        edits = service.edits.return_value
        edits.insert.return_value.execute.return_value = {"id": "EDIT"}
        edits.listings.return_value.update.return_value.execute.side_effect = RuntimeError("rejected")
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(sys.modules, {
            "googleapiclient": mock.MagicMock(), "googleapiclient.http": mock.MagicMock()}):
            write_play_listing(Path(tmp))
            with redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
                play_listing.upload_play_store_listing(
                    service, package_name=play_listing.PACKAGE_NAME, metadata_dir=Path(tmp),
                    screenshots=[], upload_listing=True, upload_screenshots=False,
                )
        edits.commit.assert_not_called()
        edits.delete.assert_called_once()


class AndroidCatalogTests(unittest.TestCase):
    def test_catalog_matches_the_android_billing_ids(self):
        catalog = validate_catalog.load_catalog()
        ids = validate_catalog.billing_product_ids(validate_catalog.BILLING_MODELS.read_text())
        self.assertEqual(validate_catalog.check(catalog, platform="android", app_ids=ids), len(ids))
        with self.assertRaisesRegex(validate_catalog.CatalogError, "BillingModels.kt"):
            validate_catalog.check(catalog, platform="android", app_ids=ids - {"com.apoorvdarshan.verceltics.tip.huge"})

    def test_billing_ids_are_read_from_kotlin_constants(self):
        kotlin = 'const val MONTHLY_ID = "com.apoorvdarshan.verceltics.monthly"\nconst val ENTITLEMENT_ID = "Verceltics Pro"\n'
        self.assertEqual(validate_catalog.billing_product_ids(kotlin), {"com.apoorvdarshan.verceltics.monthly"})


if __name__ == "__main__":
    unittest.main()
