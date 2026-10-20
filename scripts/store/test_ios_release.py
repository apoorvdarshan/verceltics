#!/usr/bin/env python3
"""Offline tests for the iOS release: switches, build numbering, waiting for and
selecting this run's build, listing/What's New/screenshots, header and search
results images, catalog and review.

No network: every App Store Connect call goes to an in-memory fake.
Run: python3 -m unittest scripts/store/test_ios_release.py

The RGB screenshot tests need Pillow (requirements-store.txt) and are skipped
without it; store-automation.yml runs this file again with the store tooling
installed and STORE_TESTS_NEED_PILLOW set, so there they cannot skip.
"""
from __future__ import annotations

import contextlib
import functools
import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import urllib.parse
import zlib
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import asc_release  # noqa: E402
import ios_metadata  # noqa: E402
import ios_release  # noqa: E402
import print_gates  # noqa: E402
import release_notes  # noqa: E402
import validate_catalog  # noqa: E402
from ios_release import ReleaseError, resolve_gates  # noqa: E402

PILLOW = asc_release.try_import_pillow()

TAG = "ios-v2.2"
VERSION = "2.2"
ALL_ON = {
    "IOS_RELEASE_AUTOMATION": "true",
    "IOS_CREATE_VERSION": "true",
    "IOS_UPLOAD_BUILD": "true",
    "IOS_UPLOAD_LISTING": "true",
    "IOS_UPLOAD_WHATS_NEW": "true",
    "IOS_UPLOAD_SCREENSHOTS": "true",
    "IOS_SUBMIT_REVIEW": "true",
    "IOS_PUBLIC_RELEASE": "true",
}
OPS = ("create_version", "upload_build", "listing", "whats_new", "screenshots", "submit", "public_release")


def make_png(width: int, height: int, colour_type: int = 2, *, chunks: tuple[tuple[bytes, bytes], ...] = (),
             fill: int = 0) -> bytes:
    """A valid PNG (8 bits per sample, every sample fill: black by default), built without Pillow.

    chunks are extra (type, data) chunks written before the image data, e.g. tRNS.
    """
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colour_type]

    def chunk(kind: bytes, data: bytes) -> bytes:
        return len(data).to_bytes(4, "big") + kind + data + zlib.crc32(kind + data).to_bytes(4, "big")

    header = width.to_bytes(4, "big") + height.to_bytes(4, "big") + bytes([8, colour_type, 0, 0, 0])
    extra = b"".join(chunk(kind, data) for kind, data in chunks)
    palette = chunk(b"PLTE", b"\x00\x00\x00") if colour_type == 3 else b""
    pixels = zlib.compress((b"\x00" + bytes([fill]) * (width * channels)) * height)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + palette + extra
            + chunk(b"IDAT", pixels) + chunk(b"IEND", b""))


def gates(env: dict[str, str], *, event: str = "push", ref: str = TAG) -> dict:
    return resolve_gates(env, event=event, ref_name=ref, marketing_version=VERSION)


class GateTests(unittest.TestCase):
    def test_missing_settings_mean_everything_off(self) -> None:
        result = gates({})
        self.assertEqual(result["mode"], "release")
        self.assertFalse(result["master"])
        self.assertFalse(result["build"])
        self.assertFalse(result["asc"])
        for key in OPS:
            self.assertFalse(result[key], key)
        self.assertEqual(result["poll_seconds"], 30)
        self.assertEqual(result["wait_minutes"], 120)

    def test_master_off_ignores_operation_switches(self) -> None:
        result = gates({**ALL_ON, "IOS_RELEASE_AUTOMATION": "false"})
        for key in OPS:
            self.assertFalse(result[key], key)
        self.assertFalse(result["build"])
        self.assertTrue(any("ignored" in note for note in result["notes"]))

    def test_each_operation_is_independent(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "yes", "IOS_UPLOAD_LISTING": "on"})
        self.assertTrue(result["listing"])
        self.assertTrue(result["asc"])
        self.assertFalse(result["whats_new"])
        self.assertFalse(result["upload_build"])
        self.assertFalse(result["build"])

    def test_all_on(self) -> None:
        result = gates(ALL_ON)
        for key in OPS:
            self.assertTrue(result[key], key)
        self.assertTrue(result["build"])

    def test_invalid_values_are_rejected_even_when_master_is_off(self) -> None:
        for name in ("IOS_RELEASE_AUTOMATION", "IOS_SUBMIT_REVIEW", "IOS_UPLOAD_WHATS_NEW"):
            with self.subTest(name=name), self.assertRaisesRegex(ReleaseError, name):
                gates({name: "maybe"})

    def test_bad_timing_values_are_rejected(self) -> None:
        for env in (
            {"IOS_BUILD_POLL_SECONDS": "5"},
            {"IOS_BUILD_POLL_SECONDS": "thirty"},
            {"IOS_BUILD_POLL_SECONDS": "٣٠"},
            {"IOS_BUILD_WAIT_MINUTES": "0"},
            {"IOS_BUILD_WAIT_MINUTES": "999"},
        ):
            with self.subTest(env=env), self.assertRaises(ReleaseError):
                gates(env)
        result = gates({"IOS_BUILD_POLL_SECONDS": "45", "IOS_BUILD_WAIT_MINUTES": "90"})
        self.assertEqual((result["poll_seconds"], result["wait_minutes"]), (45, 90))

    def test_submit_requires_this_runs_build(self) -> None:
        env = {"IOS_RELEASE_AUTOMATION": "true", "IOS_SUBMIT_REVIEW": "true"}
        with self.assertRaisesRegex(ReleaseError, "IOS_UPLOAD_BUILD"):
            gates(env)

    def test_public_release_requires_submit(self) -> None:
        env = {"IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_BUILD": "true", "IOS_PUBLIC_RELEASE": "true"}
        with self.assertRaisesRegex(ReleaseError, "IOS_SUBMIT_REVIEW"):
            gates(env)

    def test_tag_must_match_marketing_version(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "does not match"):
            gates({}, ref="ios-v2.3")
        with self.assertRaisesRegex(ReleaseError, "ios-v"):
            gates({}, ref="v2.2")

    def test_manual_run_validates_only(self) -> None:
        result = gates(ALL_ON, event="workflow_dispatch", ref="main")
        self.assertEqual(result["mode"], "validate")
        self.assertTrue(result["build"])
        self.assertFalse(result["master"])
        self.assertFalse(result["asc"])
        for key in OPS:
            self.assertFalse(result[key], key)

    def test_manual_run_can_validate_a_future_version(self) -> None:
        result = resolve_gates({}, event="workflow_dispatch", ref_name="main",
                               marketing_version=VERSION, validation_version="2.3")
        self.assertEqual(result["mode"], "validate")
        self.assertEqual(result["marketing_version"], "2.3")
        self.assertFalse(result["asc"])
        default = resolve_gates({}, event="workflow_dispatch", ref_name="main", marketing_version=VERSION)
        self.assertEqual(default["marketing_version"], VERSION)

    def test_version_override_is_rejected_for_releases_and_bad_values(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "manual validation"):
            resolve_gates({}, event="push", ref_name=TAG, marketing_version=VERSION, validation_version="2.3")
        for bad in ("2.x", "v2.3", "2.3.1.2", "2.3 beta", "٢.٣"):
            with self.subTest(bad=bad), self.assertRaisesRegex(ReleaseError, "must look like"):
                resolve_gates({}, event="workflow_dispatch", ref_name="main",
                              marketing_version=VERSION, validation_version=bad)

    def test_project_versions_are_consistent(self) -> None:
        self.assertRegex(ios_release.single_project_value("MARKETING_VERSION"), r"^\d+(\.\d+)*$")
        self.assertRegex(ios_release.single_project_value("CURRENT_PROJECT_VERSION"), r"^\d+$")
        with tempfile.TemporaryDirectory() as tmp:
            pbx = Path(tmp) / "project.pbxproj"
            pbx.write_text("MARKETING_VERSION = 1.0;\nMARKETING_VERSION = 1.1;\n")
            with self.assertRaisesRegex(ReleaseError, "identical"):
                ios_release.single_project_value("MARKETING_VERSION", pbx)

    def test_create_version_needs_only_the_master_switch(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "true", "IOS_CREATE_VERSION": "true"})
        self.assertTrue(result["create_version"])
        self.assertFalse(result["build"])
        self.assertFalse(result["asc"])
        self.assertFalse(gates({"IOS_CREATE_VERSION": "true"})["create_version"])
        self.assertFalse(gates({**ALL_ON}, event="workflow_dispatch", ref="main")["create_version"])


class ExportComplianceTests(unittest.TestCase):
    """ITSAppUsesNonExemptEncryption must be declared in every configuration of the app
    target, or App Store Connect asks about every build."""

    NO = "\t\t\t\tINFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO;\n"
    YES = "\t\t\t\tINFOPLIST_KEY_ITSAppUsesNonExemptEncryption = YES;\n"
    CODE = "\t\t\t\tINFOPLIST_KEY_ITSEncryptionExportComplianceCode = ABC123;\n"

    @staticmethod
    def configuration(name: str, bundle_id: str, extra: str = "") -> str:
        return (
            f"\t\tAAA{name}{len(bundle_id)} /* {name} */ = {{\n"
            "\t\t\tisa = XCBuildConfiguration;\n"
            "\t\t\tbuildSettings = {\n"
            f"{extra}"
            "\t\t\t\tLD_RUNPATH_SEARCH_PATHS = (\n"
            "\t\t\t\t\t\"$(inherited)\",\n"
            "\t\t\t\t);\n"
            f"\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = {bundle_id};\n"
            "\t\t\t};\n"
            f"\t\t\tname = {name};\n"
            "\t\t};\n"
        )

    def project(self, text: str, *, catalog_says: object = False) -> str:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        pbx = Path(tmp.name) / "project.pbxproj"
        pbx.write_text("/* project */\n" + text)
        catalog = Path(tmp.name) / "products.json"
        declared = {} if catalog_says is None else {"uses_non_exempt_encryption": catalog_says}
        catalog.write_text(json.dumps({**declared, "products": []}))
        return ios_release.check_export_compliance(pbx, catalog)

    def check(self, debug: str, release: str, others: str = "", *, catalog_says: object = False) -> str:
        app = ios_release.BUNDLE_ID
        return self.project(self.configuration("Debug", app, debug) + self.configuration("Release", app, release)
                            + others, catalog_says=catalog_says)

    def test_the_app_declares_no_non_exempt_encryption(self) -> None:
        self.assertEqual(ios_release.check_export_compliance(), "NO")
        configurations = ios_release.app_build_configurations()
        self.assertEqual(sorted(configurations), ["Debug", "Release"])
        for name, settings in configurations.items():
            self.assertEqual(settings.get(ios_release.ENCRYPTION_KEY), "NO", name)

    def test_no_in_every_configuration_passes(self) -> None:
        self.assertEqual(self.check(self.NO, self.NO), "NO")

    def test_a_configuration_without_the_key_fails_before_the_build(self) -> None:
        # Debug only: the Release build that ships would lack the key.
        with self.assertRaisesRegex(ReleaseError, "Release configuration does not declare export compliance"):
            self.check(self.NO, "")
        with self.assertRaisesRegex(ReleaseError, "Debug configuration does not declare export compliance"):
            self.check("", self.NO)
        with self.assertRaisesRegex(ReleaseError, "does not declare export compliance"):
            self.check("", "")

    def test_a_value_on_another_target_does_not_count_for_the_app(self) -> None:
        for bundle_id in (ios_release.BUNDLE_ID + "Tests", ios_release.BUNDLE_ID + "UITests"):
            with self.subTest(bundle_id=bundle_id):
                others = (self.configuration("Debug", bundle_id, self.NO)
                          + self.configuration("Release", bundle_id, self.NO))
                with self.assertRaisesRegex(ReleaseError, "does not declare export compliance"):
                    self.check("", "", others)
                # ...and another target's YES never overrides the app's NO.
                others = (self.configuration("Debug", bundle_id, self.YES)
                          + self.configuration("Release", bundle_id, self.YES))
                self.assertEqual(self.check(self.NO, self.NO, others), "NO")

    def test_yes_needs_the_compliance_code_in_each_configuration(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "YES in the Release configuration needs "
                                                  "INFOPLIST_KEY_ITSEncryptionExportComplianceCode"):
            self.check(self.YES + self.CODE, self.YES, catalog_says=True)
        self.assertEqual(self.check(self.YES + self.CODE, self.YES + self.CODE, catalog_says=True), "YES")

    def test_mixed_or_unknown_values_fail(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "in every app configuration alike"):
            self.check(self.NO, self.YES + self.CODE)
        with self.assertRaisesRegex(ReleaseError, "must be NO or YES"):
            self.check(self.NO, "\t\t\t\tINFOPLIST_KEY_ITSAppUsesNonExemptEncryption = maybe;\n")

    def test_missing_app_configurations_fail(self) -> None:
        for text in ("", self.configuration("Release", "com.example.other", self.NO),
                     self.configuration("Release", ios_release.BUNDLE_ID, self.NO)):
            with self.subTest(text=text), \
                    self.assertRaisesRegex(ReleaseError, "could not find the Debug and Release configurations"):
                self.project(text)

    def test_the_project_and_the_catalog_must_agree(self) -> None:
        # Review submission refuses a build whose answer differs from the catalog's.
        for answer, catalog_says in ((self.NO, True), (self.YES + self.CODE, False), (self.NO, None),
                                     (self.NO, "false")):
            with self.subTest(answer=answer, catalog=catalog_says), \
                    self.assertRaisesRegex(ReleaseError, "they must agree"):
                self.check(answer, answer, catalog_says=catalog_says)

    def test_gates_check_it_only_when_this_run_builds(self) -> None:
        missing = mock.patch.object(ios_release, "check_export_compliance",
                                    side_effect=ReleaseError("the app does not declare export compliance"))
        for env, fails in (({"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF_NAME": "main"}, True),
                           ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG,
                             "IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_BUILD": "true"}, True),
                           ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG,
                             "IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_LISTING": "true"}, False),
                           ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG}, False)):
            with self.subTest(env=env), missing, mock.patch.object(ios_release, "single_project_value",
                                                                   return_value=VERSION), \
                    mock.patch.dict(os.environ, env, clear=True), redirect_stdout(io.StringIO()), \
                    redirect_stderr(io.StringIO()) as err:
                if fails:
                    with self.assertRaises(SystemExit):
                        ios_release.main(["gates"])
                    self.assertIn("export compliance", err.getvalue())
                else:
                    ios_release.main(["gates"])


class BuildNumberTests(unittest.TestCase):
    def test_next_build_number(self) -> None:
        self.assertEqual(ios_release.next_build_number(39, ["13", "35", "12"]), 39)
        self.assertEqual(ios_release.next_build_number(39, ["13", "39"]), 40)
        self.assertEqual(ios_release.next_build_number(1, []), 1)
        self.assertEqual(ios_release.next_build_number(5, ["2.1", "abc", "9"]), 10)
        self.assertEqual(ios_release.next_build_number(5, ["٩٩"]), 5)  # only ASCII digits count


APP = "APP1"
STARTED = 1_800_000_000.0  # this run's upload start (epoch seconds)


def iso(epoch: float) -> str:
    import datetime as dt

    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).isoformat()


def build_row(
    build_id: str = "B1",
    *,
    number: str = "40",
    state: str = "VALID",
    expired: bool = False,
    uploaded: float | str | None = STARTED + 60,
    app: str = APP,
    pre: str = "P1",
) -> dict:
    """A /builds row; uploaded is epoch seconds, a raw uploadedDate string, or None (absent)."""
    attributes = {"version": number, "processingState": state, "expired": expired}
    if uploaded is not None:
        attributes["uploadedDate"] = uploaded if isinstance(uploaded, str) else iso(uploaded)
    return {
        "type": "builds",
        "id": build_id,
        "attributes": attributes,
        "relationships": {
            "preReleaseVersion": {"data": {"type": "preReleaseVersions", "id": pre}},
            "app": {"data": {"type": "apps", "id": app}},
        },
    }


def pre_row(pre_id: str = "P1", version: str = VERSION, platform: str = "IOS") -> dict:
    return {"type": "preReleaseVersions", "id": pre_id, "attributes": {"version": version, "platform": platform}}


class FakeClock:
    def __init__(self, start: float) -> None:
        self.time = start
        self.sleeps: list[float] = []

    def now(self) -> float:
        return self.time

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.time += seconds


class SequenceClient:
    """Returns queued /builds payloads (or raises queued exceptions) per GET."""

    def __init__(self, responses: list) -> None:
        self.responses = list(responses)
        self.paths: list[str] = []

    def get(self, path: str) -> dict:
        self.paths.append(path)
        item = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(item, BaseException):
            raise item
        return item


def wait(client: SequenceClient, clock: FakeClock, *, minutes: int = 120, transient=None) -> dict:
    with redirect_stdout(io.StringIO()):
        return ios_release.wait_for_build(
            client,
            app_id=APP,
            marketing_version=VERSION,
            build_number="40",
            uploaded_after=STARTED,
            deadline=STARTED + minutes * 60,
            poll_seconds=30,
            now=clock.now,
            sleep=clock.sleep,
            is_transient=transient,
        )


class WaitForBuildTests(unittest.TestCase):
    def test_waits_through_missing_and_processing_then_returns_build(self) -> None:
        client = SequenceClient(
            [
                {"data": []},
                {"data": [build_row(state="PROCESSING")], "included": [pre_row()]},
                {"data": [build_row()], "included": [pre_row()]},
            ]
        )
        clock = FakeClock(STARTED + 120)
        build = wait(client, clock)
        self.assertEqual(build["id"], "B1")
        self.assertEqual(clock.sleeps, [30, 30])
        self.assertIn("filter%5Bversion%5D=40", client.paths[0])
        self.assertIn("filter%5Bapp%5D=APP1", client.paths[0])

    def test_times_out_without_using_another_build(self) -> None:
        client = SequenceClient([{"data": [build_row(state="PROCESSING")], "included": [pre_row()]}])
        clock = FakeClock(STARTED)
        with self.assertRaisesRegex(ReleaseError, "timed out"):
            wait(client, clock, minutes=10)
        self.assertLessEqual(clock.time, STARTED + 10 * 60 + 30)

    def test_ready_build_after_the_deadline_is_still_a_timeout(self) -> None:
        client = SequenceClient([{"data": [build_row()], "included": [pre_row()]}])
        clock = FakeClock(STARTED + 121 * 60)  # job started after the 120-minute budget
        with self.assertRaisesRegex(ReleaseError, "timed out"):
            wait(client, clock)
        self.assertEqual(client.paths, [], "must not poll once the budget is spent")

    def test_processing_failure_stops(self) -> None:
        for state in ("FAILED", "INVALID"):
            client = SequenceClient([{"data": [build_row(state=state)], "included": [pre_row()]}])
            with self.subTest(state=state), self.assertRaisesRegex(ReleaseError, state):
                wait(client, FakeClock(STARTED))

    def test_expired_build_is_rejected(self) -> None:
        client = SequenceClient([{"data": [build_row(expired=True)], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "expired"):
            wait(client, FakeClock(STARTED))

    def test_ambiguous_match_stops(self) -> None:
        client = SequenceClient(
            [{"data": [build_row("B1"), build_row("B2")], "included": [pre_row()]}]
        )
        with self.assertRaisesRegex(ReleaseError, "ambiguous"):
            wait(client, FakeClock(STARTED))

    def test_older_upload_is_never_substituted(self) -> None:
        old = build_row(uploaded=STARTED - 3600)
        client = SequenceClient([{"data": [old], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "older build"):
            wait(client, FakeClock(STARTED))

    def test_build_without_an_upload_time_is_never_used(self) -> None:
        # While processing, App Store Connect may not report the time yet: keep waiting.
        client = SequenceClient([
            {"data": [build_row(state="PROCESSING", uploaded=None)], "included": [pre_row()]},
            {"data": [build_row(uploaded=None)], "included": [pre_row()]},
        ])
        clock = FakeClock(STARTED)
        with self.assertRaisesRegex(ReleaseError, "no uploadedDate"):
            wait(client, clock)
        self.assertEqual(clock.sleeps, [30], "the processing poll waits; the VALID one fails closed")
        for raw in ("", "yesterday", "2026-13-45T99:00:00Z"):
            client = SequenceClient([{"data": [build_row(uploaded=raw)], "included": [pre_row()]}])
            with self.subTest(raw=raw), self.assertRaisesRegex(ReleaseError, "uploadedDate"):
                wait(client, FakeClock(STARTED))

    def test_other_versions_and_platforms_are_ignored(self) -> None:
        other_version = build_row("BX", pre="P2")
        other_platform = build_row("BY", pre="P3")
        client = SequenceClient(
            [
                {
                    "data": [other_version, other_platform],
                    "included": [pre_row("P2", "2.1"), pre_row("P3", VERSION, "MAC_OS")],
                },
                {"data": [other_version, build_row("B1")], "included": [pre_row("P2", "2.1"), pre_row()]},
            ]
        )
        self.assertEqual(wait(client, FakeClock(STARTED))["id"], "B1")

    def test_build_from_another_app_is_a_mismatch(self) -> None:
        client = SequenceClient([{"data": [build_row(app="OTHER")], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "another app"):
            wait(client, FakeClock(STARTED))

    def test_transient_errors_are_retried_and_others_raise(self) -> None:
        transient = asc_release.AscHttpError(503, "unavailable")
        client = SequenceClient([transient, {"data": [build_row()], "included": [pre_row()]}])
        with redirect_stderr(io.StringIO()):
            build = wait(client, FakeClock(STARTED), transient=lambda exc: getattr(exc, "status", 0) == 503)
        self.assertEqual(build["id"], "B1")
        fatal = SequenceClient([asc_release.AscHttpError(401, "unauthorized")])
        with self.assertRaises(SystemExit):
            wait(fatal, FakeClock(STARTED), transient=lambda exc: getattr(exc, "status", 0) == 503)


class SelectionClient:
    """Fake ASC client for build verification and selection."""

    def __init__(self, *, build: dict, included: list, attached: str | None, sticky: bool = True) -> None:
        self.build = build
        self.included = included
        self.attached = attached
        self.sticky = sticky
        self.calls: list[tuple[str, str, dict | None]] = []

    def get(self, path: str) -> dict:
        self.calls.append(("GET", path, None))
        if path.startswith(f"/builds/{self.build['id']}"):
            return {"data": self.build, "included": self.included}
        if path.endswith("/build"):
            return {"data": {"type": "builds", "id": self.attached} if self.attached else None}
        raise AssertionError(f"unexpected GET {path}")

    def patch(self, path: str, body: dict) -> dict:
        self.calls.append(("PATCH", path, body))
        return {"data": body["data"]}

    def request(self, method: str, path: str, body: dict | None = None):
        self.calls.append((method, path, body))
        if method == "PATCH" and path.endswith("/relationships/build") and self.sticky:
            self.attached = body["data"]["id"]
        return None


def quiet(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*args, **kwargs)


class BuildSelectionTests(unittest.TestCase):
    def test_selects_and_reads_back_this_runs_build(self) -> None:
        client = SelectionClient(build=build_row("B9"), included=[pre_row()], attached="OLD")
        quiet(asc_release.select_build, client, "V1", "B9", VERSION, "40")
        self.assertEqual(client.attached, "B9")
        methods = [(m, p) for m, p, _ in client.calls]
        self.assertIn(("PATCH", "/appStoreVersions/V1/relationships/build"), methods)
        self.assertEqual(methods[-1], ("GET", "/appStoreVersions/V1/build"))

    def test_selection_that_does_not_stick_fails(self) -> None:
        client = SelectionClient(build=build_row("B9"), included=[pre_row()], attached="OLD", sticky=False)
        with self.assertRaises(SystemExit):
            quiet(asc_release.select_build, client, "V1", "B9", VERSION, "40")

    def test_unprocessed_expired_or_wrong_build_is_refused(self) -> None:
        cases = {
            "processing": (build_row("B9", state="PROCESSING"), [pre_row()]),
            "expired": (build_row("B9", expired=True), [pre_row()]),
            "number": (build_row("B9", number="41"), [pre_row()]),
            "version": (build_row("B9"), [pre_row(version="2.1")]),
        }
        for label, (build, included) in cases.items():
            client = SelectionClient(build=build, included=included, attached=None)
            with self.subTest(label), self.assertRaises(SystemExit):
                quiet(asc_release.select_build, client, "V1", "B9", VERSION, "40")
            self.assertFalse([c for c in client.calls if c[0] == "PATCH"], label)

    def test_release_type(self) -> None:
        for public, expected in ((True, "AFTER_APPROVAL"), (False, "MANUAL")):
            client = SelectionClient(build=build_row(), included=[], attached=None)
            quiet(asc_release.set_release_type, client, "V1", public_release=public)
            body = client.calls[-1][2]
            self.assertEqual(body["data"]["attributes"]["releaseType"], expected)


class FindAppTests(unittest.TestCase):
    def find(self, rows: list[dict]) -> tuple[int, str | None]:
        client = mock.Mock()
        client.get.return_value = {"data": rows}
        try:
            return 0, quiet(asc_release.find_app, client, asc_release.DEFAULT_BUNDLE_ID)
        except SystemExit as exc:
            return exc.code if isinstance(exc.code, int) else 1, None

    def test_exactly_one_app_with_the_bundle_id(self) -> None:
        bundle = asc_release.DEFAULT_BUNDLE_ID
        self.assertEqual(self.find([{"id": "A", "attributes": {"bundleId": bundle}},
                                    {"id": "B", "attributes": {"bundleId": bundle + ".widget"}}]), (0, "A"))
        self.assertEqual(self.find([]), (1, None))
        self.assertEqual(self.find([{"id": "A", "attributes": {}}]), (1, None), "no bundle id is no match")
        self.assertEqual(self.find([{"id": "A", "attributes": {"bundleId": bundle}},
                                    {"id": "B", "attributes": {"bundleId": bundle}}]), (1, None))


class ScreenshotChecksumTests(unittest.TestCase):
    def test_upload_commits_the_files_md5(self) -> None:
        client = mock.Mock()
        client.post.return_value = {"data": {"id": "SHOT", "attributes": {"uploadOperations": [
            {"url": "https://upload.example/1", "length": 4, "offset": 0, "method": "PUT"}]}}}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "1.png"
            path.write_bytes(b"\x89PNG")
            quiet(asc_release.upload_screenshot_file, client, "SET", path)
        attrs = client.patch.call_args[0][1]["data"]["attributes"]
        self.assertEqual(attrs, {"uploaded": True, "sourceFileChecksum": md5(b"\x89PNG")})


class AppInfoTargetTests(unittest.TestCase):
    def test_the_app_info_in_review_is_compared_when_none_is_editable(self) -> None:
        live, next_ = app_info("LIVE", "READY_FOR_DISTRIBUTION"), app_info("NEXT", "WAITING_FOR_REVIEW")
        old = app_info("OLD", "REPLACED_WITH_NEW_INFO")
        self.assertEqual(asc_release.app_info_target([old, live, next_]), (next_, False))
        self.assertEqual(asc_release.app_info_target([old, live]), (live, False))
        editable = app_info("EDIT", "PREPARE_FOR_SUBMISSION")
        self.assertEqual(asc_release.app_info_target([live, editable]), (editable, True))
        with self.assertRaises(SystemExit):
            quiet(asc_release.app_info_target, [app_info("A", "IN_REVIEW"), app_info("B", "WAITING_FOR_REVIEW")])


class TokenRefreshTests(unittest.TestCase):
    def test_token_is_reminted_during_long_waits(self) -> None:
        minted: list[str] = []

        def factory() -> str:
            minted.append(f"t{len(minted)}")
            return minted[-1]

        with mock.patch.object(asc_release.time, "monotonic", side_effect=[0, 100, 700, 700, 800]):
            client = asc_release.AscClient(token_factory=factory)
            self.assertEqual(client._authorization(), "Bearer t0")
            self.assertEqual(client._authorization(), "Bearer t1")
            self.assertEqual(client._authorization(), "Bearer t1")


LISTING = {
    "name.txt": "Verceltics — Hosting & Domains",
    "subtitle.txt": "Deployments, Analytics, Uptime",
    "privacy_url.txt": "https://verceltics.com/privacy",
    "description.txt": "Hosting, domains and site services in one workspace.",
    "keywords.txt": "dns,registrar,cloudflare,vercel",
    "promotional_text.txt": "Check your whole stack.",
    "marketing_url.txt": "https://verceltics.com",
    "support_url.txt": "https://verceltics.com",
}


def write_listing(root: Path, locale: str = "en-US", **overrides: str | None) -> Path:
    """A valid listing under root/ios/<locale>/; overrides replace or (None) remove files."""
    loc = root / "ios" / locale
    loc.mkdir(parents=True, exist_ok=True)
    for name, text in {**LISTING, **overrides}.items():
        if text is not None:
            (loc / name).write_text(text + "\n", encoding="utf-8")
    return loc


class AscCliTests(unittest.TestCase):
    def run_cli(self, env: dict[str, str], *args: str) -> tuple[int, str]:
        out = io.StringIO()
        full_env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "UPLOAD_CREATIVE_ASSETS", "SUBMIT_IOS_REVIEW",
            "PUBLIC_RELEASE", "CREATE_IOS_VERSION"}}
        full_env.update(env)
        with mock.patch.dict(os.environ, full_env, clear=True), mock.patch.object(
            sys, "argv", ["asc_release.py", "--dry-run", *args]
        ), redirect_stdout(out), redirect_stderr(out):
            try:
                asc_release.main()
            except SystemExit as exc:
                return (exc.code if isinstance(exc.code, int) else 1), out.getvalue()
        return 0, out.getvalue()

    def test_everything_off_is_a_no_op(self) -> None:
        code, output = self.run_cli({})
        self.assertEqual(code, 0)
        self.assertIn("skipped", output)

    def test_submit_without_this_runs_build_fails(self) -> None:
        code, output = self.run_cli({"SUBMIT_IOS_REVIEW": "true"})
        self.assertEqual(code, 1)
        self.assertIn("--build-id", output)

    def test_public_release_without_submit_fails(self) -> None:
        code, _ = self.run_cli({"UPLOAD_LISTING": "true", "PUBLIC_RELEASE": "true"})
        self.assertEqual(code, 1)

    def test_only_the_verceltics_bundle_is_supported(self) -> None:
        code, output = self.run_cli({"UPLOAD_LISTING": "true"}, "--bundle-id", "com.example.other")
        self.assertEqual(code, 1)
        self.assertIn("Verceltics", output)

    def test_whats_new_requires_text(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("whats_new.txt", output)
            (loc / "whats_new.txt").write_text("x" * 4001)
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("4000", output)
            (loc / "whats_new.txt").write_text("Fixes\n")
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 0, output)

    def test_listing_upload_rejects_invalid_text_before_any_call(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp), **{"keywords.txt": "k" * 101})
            code, output = self.run_cli({"UPLOAD_LISTING": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("keywords.txt", output)

    def test_whats_new_alone_never_reads_the_listing(self) -> None:
        # P-14: prepare --no-listing hands over What's New only, and an unused
        # listing (missing or invalid) never blocks the run.
        with tempfile.TemporaryDirectory() as tmp:
            loc = Path(tmp) / "ios" / "en-US"
            loc.mkdir(parents=True)
            (loc / "whats_new.txt").write_text("Fixes\n")
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 0, output)
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true", "UPLOAD_LISTING": "true"},
                                        "--metadata-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("description.txt", output)
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp), **{"keywords.txt": "k" * 150})
            (loc / "whats_new.txt").write_text("Fixes\n")
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 0, output)

    def test_dry_run_submit_exercises_product_selection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp))
            code, output = self.run_cli(
                {"SUBMIT_IOS_REVIEW": "true", "UPLOAD_LISTING": "true"},
                "--build-id", "DRY-RUN", "--build-number", "1", "--metadata-dir", tmp,
            )
        self.assertEqual(code, 0, output)
        self.assertIn("product submission selection OK", output)

    def test_dry_run_and_preflight_are_exclusive(self) -> None:
        code, output = self.run_cli({"UPLOAD_LISTING": "true"}, "--preflight")
        self.assertEqual(code, 2)
        self.assertIn("not allowed with argument", output)

    def test_listing_excludes_whats_new_unless_enabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text("New")
            for whats_new in (False, True):
                client = mock.Mock()
                client.get.return_value = {"data": [{"id": "LOC", "attributes": {"locale": "en-US"}}]}
                quiet(
                    asc_release.upload_listing,
                    client,
                    version_id="V1",
                    metadata_dir=Path(tmp),
                    include_listing=True,
                    include_whats_new=whats_new,
                )
                attrs = client.patch.call_args[0][1]["data"]["attributes"]
                self.assertEqual("whatsNew" in attrs, whats_new)
                self.assertEqual(attrs["description"], LISTING["description.txt"])
                self.assertEqual(attrs["supportUrl"], LISTING["support_url.txt"])
                self.assertNotIn("name", attrs, "app name belongs to App Info, not the version")

    def test_whats_new_alone_leaves_the_listing_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text("New")
            client = mock.Mock()
            client.get.return_value = {"data": [{"id": "LOC", "attributes": {"locale": "en-US"}}]}
            quiet(asc_release.upload_listing, client, version_id="V1", metadata_dir=Path(tmp),
                  include_listing=False, include_whats_new=True)
            self.assertEqual(client.patch.call_args[0][1]["data"]["attributes"], {"whatsNew": "New"})

    def test_unchanged_listing_is_not_patched(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp))
            live = {field.attribute: LISTING[field.filename] for field in ios_metadata.VERSION_FIELDS}
            client = mock.Mock()
            client.get.return_value = {"data": [{"id": "LOC", "attributes": {"locale": "en-US", **live}}]}
            quiet(asc_release.upload_listing, client, version_id="V1", metadata_dir=Path(tmp))
            client.patch.assert_not_called()


class TransientErrorTests(unittest.TestCase):
    def test_real_retry_rule(self) -> None:
        import http.client
        import urllib.error

        transient = [
            http.client.RemoteDisconnected("closed"),
            ConnectionResetError("reset"),
            http.client.IncompleteRead(b""),
            urllib.error.URLError("dns"),
            TimeoutError("slow"),
            asc_release.AscHttpError(503, "busy"),
            asc_release.AscHttpError(429, "rate limited"),
            urllib.error.HTTPError("https://x", 502, "bad gateway", {}, None),
        ]
        fatal = [
            asc_release.AscHttpError(401, "unauthorized"),
            asc_release.AscHttpError(409, "conflict"),
            urllib.error.HTTPError("https://x", 404, "missing", {}, None),
            ValueError("bad"),
            ReleaseError("mismatch"),
            SystemExit(1),
        ]
        for exc in transient:
            with self.subTest(exc=repr(exc)):
                self.assertTrue(ios_release.is_transient_error(exc))
        for exc in fatal:
            with self.subTest(exc=repr(exc)):
                self.assertFalse(ios_release.is_transient_error(exc))


class CmdWaitBuildTests(unittest.TestCase):
    def test_deadline_counts_from_upload_start_and_app_lookup_is_retried(self) -> None:
        import http.client
        import argparse

        lookups = []

        def find_app(_client, bundle_id):
            lookups.append(bundle_id)
            if len(lookups) == 1:
                raise http.client.RemoteDisconnected("closed")
            return APP

        fake_module = mock.Mock(find_app=find_app)
        captured = {}

        def fake_wait(client, **kwargs):
            captured.update(kwargs)
            return {"id": "B9"}

        args = argparse.Namespace(
            version=VERSION, build=40, started_at=STARTED, wait_minutes=120, poll_seconds=30
        )
        with tempfile.NamedTemporaryFile("r", suffix=".out") as out, mock.patch.object(
            ios_release, "asc_client", return_value=(fake_module, object())
        ), mock.patch.object(ios_release, "wait_for_build", side_effect=fake_wait), mock.patch.object(
            ios_release.time, "sleep"
        ), mock.patch.object(
            ios_release.time, "time", return_value=STARTED + 30
        ), mock.patch.dict(os.environ, {"GITHUB_OUTPUT": out.name}), redirect_stdout(io.StringIO()):
            ios_release.cmd_wait_build(args)
            written = Path(out.name).read_text()
        self.assertEqual(lookups, [ios_release.BUNDLE_ID] * 2)
        self.assertEqual(captured["deadline"], STARTED + 120 * 60)
        self.assertEqual(captured["uploaded_after"], STARTED)
        self.assertEqual(captured["build_number"], "40")
        self.assertIs(captured["is_transient"], ios_release.is_transient_error)
        self.assertIn("build_id=B9", written)

    def test_app_lookup_gives_up_at_the_deadline(self) -> None:
        import http.client

        def boom():
            raise http.client.RemoteDisconnected("closed")

        clock = FakeClock(STARTED)
        with redirect_stdout(io.StringIO()), self.assertRaises(http.client.RemoteDisconnected):
            ios_release.call_with_retries(
                boom, deadline=STARTED + 90, poll_seconds=30, now=clock.now, sleep=clock.sleep
            )
        self.assertEqual(clock.sleeps, [30, 30, 30])


class FakeAsc:
    """App Store Connect stand-in for asc_release.main() (live path, no network)."""

    LIVE_TEXT = {field.attribute: LISTING[field.filename] for field in ios_metadata.VERSION_FIELDS}
    LIVE_APP_INFO = {"name": LISTING["name.txt"], "subtitle": LISTING["subtitle.txt"],
                     "privacyPolicyUrl": LISTING["privacy_url.txt"]}

    def __init__(self, *, state: str = "PREPARE_FOR_SUBMISSION", attached: str | None = "OLD",
                 revert_after_select: bool = False, encryption: bool | None = None,
                 version_exists: bool = True, other_versions: list[tuple[str, str]] | None = None,
                 app_id: str = APP, localization: dict | None = None,
                 app_infos: list | None = None, iaps: list | None = None,
                 screenshots: dict | None = None) -> None:
        self.state = state
        self.attached = attached
        self.revert_after_select = revert_after_select
        self.encryption = encryption
        self.version_exists = version_exists
        # Other IOS versions of the app as (versionString, appStoreState).
        self.other_versions = [("2.1", "READY_FOR_SALE")] if other_versions is None else other_versions
        self.app_id = app_id
        self.localization = dict(self.LIVE_TEXT if localization is None else localization)
        self.app_infos = app_infos if app_infos is not None else [app_info("LIVE", "READY_FOR_DISTRIBUTION")]
        self.iaps = iaps or []
        # display type -> checksums of the live screenshots, in order
        self.screenshots = screenshots or {}
        self.build_reads = 0
        self.calls: list[tuple[str, str]] = []
        self.bodies: list[tuple[str, str, dict]] = []
        # Display types of the screenshot sets this run created.
        self.created_sets: list[str] = []

    def writes(self) -> list[tuple[str, str]]:
        return [c for c in self.calls if c[0] in {"POST", "PATCH", "DELETE"}]

    def get(self, path: str) -> dict:
        self.calls.append(("GET", path))
        if path.startswith("/apps?"):
            return {"data": [{"id": self.app_id, "attributes": {"bundleId": asc_release.DEFAULT_BUNDLE_ID}}]}
        if path.startswith(f"/apps/{self.app_id}/appStoreVersions"):
            rows = [{"id": f"V0-{i}", "attributes": {"versionString": number, "appStoreState": state}}
                    for i, (number, state) in enumerate(self.other_versions)]
            if self.version_exists:
                rows.append({"id": "V1", "attributes": {"versionString": VERSION, "appStoreState": self.state}})
            return {"data": rows}
        if path.startswith("/builds/B9"):
            build = build_row("B9")
            build["attributes"]["usesNonExemptEncryption"] = self.encryption
            return {"data": build, "included": [pre_row()]}
        if path == "/appStoreVersions/V1/build":
            self.build_reads += 1
            if self.revert_after_select and self.build_reads >= 2:
                return {"data": {"type": "builds", "id": "OLD"}}
            return {"data": {"type": "builds", "id": self.attached} if self.attached else None}
        if path.startswith("/appStoreVersions/V1/appStoreVersionLocalizations"):
            if "filter%5Blocale%5D=en-US" not in path:
                return {"data": []}
            return {"data": [{"id": "LOC", "attributes": {"locale": "en-US", **self.localization}}]}
        if path.startswith(f"/apps/{self.app_id}/appInfos"):
            return {"data": self.app_infos}
        if "/appInfoLocalizations" in path:
            return {"data": [{"id": "INFOLOC", "attributes": {"locale": "en-US", **self.LIVE_APP_INFO}}]}
        if path == "/appStoreVersionLocalizations/LOC/appScreenshotSets":
            return {"data": [{"id": f"SET-{kind}", "attributes": {"screenshotDisplayType": kind}}
                             for kind in self.screenshots]}
        if path.startswith("/appScreenshotSets/SET-") and path.endswith("/appScreenshots"):
            kind = path[len("/appScreenshotSets/SET-"):-len("/appScreenshots")]
            return {"data": [{"id": f"{kind}-{i}", "attributes": {"sourceFileChecksum": checksum}}
                             for i, checksum in enumerate(self.screenshots[kind])]}
        if path.startswith("/appScreenshotSets/NEW-") and path.endswith("/appScreenshots"):
            return {"data": []}  # sets created by this run start empty
        if path.startswith(f"/apps/{self.app_id}/reviewSubmissions"):
            return {"data": [{"id": "SUB1"}]}
        if path.startswith("/reviewSubmissions/SUB1/items"):
            # A READY_FOR_REVIEW version sits in its draft, as in App Store Connect.
            held = [{"id": "ITEM-V1", "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": "V1"}}}}]
            return {"data": held if self.state == "READY_FOR_REVIEW" else []}
        if path.startswith(f"/apps/{self.app_id}/subscriptionGroups"):
            return {"data": []}
        if path.startswith(f"/apps/{self.app_id}/inAppPurchasesV2"):
            return {"data": self.iaps}
        raise AssertionError(f"unexpected GET {path}")

    def post(self, path: str, body: dict) -> dict:
        self.calls.append(("POST", path))
        self.bodies.append(("POST", path, body))
        if path == "/appStoreVersions":
            self.version_exists = True
            self.state = "PREPARE_FOR_SUBMISSION"
            return {"data": {"id": "V1"}}
        if path == "/appScreenshotSets":
            display = body["data"]["attributes"]["screenshotDisplayType"]
            self.created_sets.append(display)
            return {"data": {"id": f"NEW-{display}"}}
        return {"data": {"id": "ITEM"}}

    def patch(self, path: str, body: dict) -> dict:
        self.calls.append(("PATCH", path))
        if path == "/builds/B9":
            self.encryption = body["data"]["attributes"]["usesNonExemptEncryption"]
        return {"data": body["data"]}

    def request(self, method: str, path: str, body: dict | None = None):
        self.calls.append((method, path))
        if method == "PATCH" and path == "/appStoreVersions/V1/relationships/build":
            self.attached = body["data"]["id"]
        return None


LIFETIME = {"id": "IAP1", "attributes": {"name": "Lifetime", "state": "READY_TO_SUBMIT",
                                         "productId": "com.apoorvdarshan.verceltics.lifetime"}}


def run_asc(fake: FakeAsc, env: dict[str, str], *args: str, metadata: Path | None = None,
            products: bool = True, version: str = VERSION) -> tuple[int, dict, str]:
    """Run asc_release.main() against fake; products=False keeps the real product steps."""
    argv = ["asc_release.py", "--version", version, *args]
    if metadata is not None:
        argv += ["--metadata-dir", str(metadata)]
    clean = {k: v for k, v in os.environ.items() if k not in {
        "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "UPLOAD_CREATIVE_ASSETS", "SUBMIT_IOS_REVIEW",
        "PUBLIC_RELEASE", "CREATE_IOS_VERSION"}}
    mocks = {
        "attach_subscriptions": mock.Mock(return_value=asc_release.ProductSubmitResult("SUB1", 0, False)),
        "in_app_purchases_to_submit": mock.Mock(return_value=[]),
        "submit_in_app_purchases": mock.Mock(return_value=asc_release.ProductSubmitResult("SUB1", 0, False)),
    }
    out = io.StringIO()
    code = 0
    with contextlib.ExitStack() as stack:
        stack.enter_context(mock.patch.dict(os.environ, {**clean, **env}, clear=True))
        stack.enter_context(mock.patch.object(sys, "argv", argv))
        stack.enter_context(mock.patch.object(asc_release, "client_from_environment", return_value=fake))
        if products:
            for name, value in mocks.items():
                stack.enter_context(mock.patch.object(asc_release, name, value))
        stack.enter_context(redirect_stdout(out))
        stack.enter_context(redirect_stderr(out))
        try:
            asc_release.main()
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 1
    return code, mocks, out.getvalue()


class LiveSubmitFlowTests(unittest.TestCase):
    BUILD = ("--build-id", "B9", "--build-number", "40")

    def run_main(self, fake: FakeAsc, *, public: bool = False) -> tuple[int, dict]:
        env = {"SUBMIT_IOS_REVIEW": "true", **({"PUBLIC_RELEASE": "true"} if public else {})}
        code, mocks, _ = run_asc(fake, env, *self.BUILD)
        return code, mocks

    def test_selects_answers_compliance_rechecks_then_submits(self) -> None:
        fake = FakeAsc()
        code, products = self.run_main(fake)
        self.assertEqual(code, 0)
        calls = fake.calls
        select = calls.index(("PATCH", "/appStoreVersions/V1/relationships/build"))
        compliance = calls.index(("PATCH", "/builds/B9"))
        submit = calls.index(("PATCH", "/reviewSubmissions/SUB1"))
        reads = [i for i, c in enumerate(calls) if c == ("GET", "/appStoreVersions/V1/build")]
        build_checks = [i for i, c in enumerate(calls) if c[0] == "GET" and c[1].startswith("/builds/B9")]
        self.assertEqual(len(reads), 2, "selection must be read back and rechecked")
        self.assertTrue(select < reads[0] < compliance < reads[1] < submit)
        self.assertTrue(build_checks[-1] < reads[1] < submit, "build must be re-verified just before submit")
        self.assertFalse(fake.encryption, "the catalog declares no non-exempt encryption")
        self.assertIn(("PATCH", "/appStoreVersions/V1"), calls)  # release type
        products["attach_subscriptions"].assert_called_once()
        products["in_app_purchases_to_submit"].assert_called_once()
        products["submit_in_app_purchases"].assert_not_called()

    def test_answered_compliance_is_not_changed(self) -> None:
        fake = FakeAsc(encryption=False)
        code, _ = self.run_main(fake)
        self.assertEqual(code, 0)
        self.assertNotIn(("PATCH", "/builds/B9"), fake.calls)

    def test_compliance_that_contradicts_the_catalog_aborts(self) -> None:
        fake = FakeAsc(encryption=True)
        code, products = self.run_main(fake)
        self.assertEqual(code, 1)
        products["attach_subscriptions"].assert_not_called()
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_selection_changed_before_submit_aborts(self) -> None:
        fake = FakeAsc(revert_after_select=True)
        code, _ = self.run_main(fake)
        self.assertEqual(code, 1)
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_waiting_for_review_with_another_build_aborts(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="OTHER")
        code, products = self.run_main(fake)
        self.assertEqual(code, 1)
        products["submit_in_app_purchases"].assert_not_called()
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_waiting_for_review_with_this_build_is_idempotent(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9")
        code, products = self.run_main(fake)
        self.assertEqual(code, 0)
        products["submit_in_app_purchases"].assert_called_once()
        self.assertNotIn(("PATCH", "/appStoreVersions/V1/relationships/build"), fake.calls)

    def test_released_version_is_never_submitted(self) -> None:
        fake = FakeAsc(state="READY_FOR_SALE")
        code, products = self.run_main(fake)
        self.assertEqual(code, 1)
        products["attach_subscriptions"].assert_not_called()
        self.assertFalse(fake.writes())


class InAppPurchaseOrderTests(unittest.TestCase):
    """Purchases go to App Review only after the final build and selection recheck."""

    def run_real(self, fake: FakeAsc) -> int:
        code, _, _ = run_asc(fake, {"SUBMIT_IOS_REVIEW": "true"}, *LiveSubmitFlowTests.BUILD, products=False)
        return code

    def test_purchases_are_submitted_after_the_recheck(self) -> None:
        fake = FakeAsc(iaps=[LIFETIME])
        self.assertEqual(self.run_real(fake), 0)
        calls = fake.calls
        recheck = max(i for i, c in enumerate(calls) if c == ("GET", "/appStoreVersions/V1/build"))
        purchase = calls.index(("POST", "/inAppPurchaseSubmissions"))
        submit = calls.index(("PATCH", "/reviewSubmissions/SUB1"))
        self.assertTrue(recheck < purchase < submit)

    def test_a_failed_recheck_sends_nothing_to_review(self) -> None:
        fake = FakeAsc(iaps=[LIFETIME], revert_after_select=True)
        self.assertEqual(self.run_real(fake), 1)
        self.assertNotIn(("POST", "/inAppPurchaseSubmissions"), fake.calls)
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)


def md5(data: bytes) -> str:
    import hashlib

    return hashlib.md5(data).hexdigest()


SMALL = (4, 8)  # the size temp_screenshot_sets' iPhone and iPad sets take, to keep tests fast


@contextlib.contextmanager
def temp_screenshot_sets(*, frames: bool = True):
    """The screenshot folders in a temporary root, in place of the repository's.

    With frames, iphone/ and ipad/ hold two small RGB PNGs each (both sets then
    take SMALL only); without, they are empty and keep the App Store sizes. The
    optional iphone-duo/ and watch/ folders (App Store sizes) are not created.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        sets = []
        for spec in ios_metadata.SCREENSHOT_SETS:
            folder = root / spec.directory.name
            folder.mkdir()
            sizes = spec.sizes
            if frames:
                sizes = frozenset({SMALL})
                for i in range(2):
                    (folder / f"{i}.png").write_bytes(make_png(*SMALL, fill=i))
            sets.append(ios_metadata.ScreenshotSet(spec.display_type, spec.label, folder, sizes))
        optional = tuple(
            ios_metadata.ScreenshotSet(spec.display_type, spec.label, root / spec.directory.name, spec.sizes)
            for spec in ios_metadata.OPTIONAL_SCREENSHOT_SETS
        )
        with mock.patch.object(ios_metadata, "SCREENSHOT_SETS", tuple(sets)), \
                mock.patch.object(ios_metadata, "OPTIONAL_SCREENSHOT_SETS", optional), \
                mock.patch.object(ios_metadata, "WATCH_SCREENSHOTS", root / ios_metadata.WATCH_FOLDER), \
                mock.patch.object(ios_metadata, "ROOT", root):
            yield tuple(sets)


def staged_checksums() -> dict[str, list[str]]:
    """display type -> MD5 of each copy a run stages (and so uploads) from the current folders, in order."""
    with tempfile.TemporaryDirectory() as tmp:
        sets = quiet(asc_release.staged_screenshot_sets, asc_release.stage_screenshots(Path(tmp)))
        return {display: [md5(path.read_bytes()) for path in pngs] for display, _, pngs in sets}


class VersionStateTests(unittest.TestCase):
    """Reruns after a partly completed submission (IOS-REL-3), with metadata switches on."""

    LIVE_SWITCHES = {"UPLOAD_LISTING": "true", "UPLOAD_WHATS_NEW": "true", "SUBMIT_IOS_REVIEW": "true"}

    def run_live(self, fake: FakeAsc, env: dict[str, str], *, whats_new: str = "Faster dashboards.") -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text(whats_new + "\n")
            code, _, output = run_asc(fake, env, *LiveSubmitFlowTests.BUILD, metadata=Path(tmp))
        return code, output

    def test_ready_for_review_updates_text_and_submits(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW", app_infos=[app_info("NEXT", "READY_FOR_REVIEW")])
        code, output = self.run_live(fake, self.LIVE_SWITCHES)
        self.assertEqual(code, 0, output)
        self.assertIn(("PATCH", "/appStoreVersionLocalizations/LOC"), fake.calls)  # What's New
        self.assertIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_ready_for_review_accepts_screenshots_only_when_unchanged(self) -> None:
        env = {"UPLOAD_SCREENSHOTS": "true", "SUBMIT_IOS_REVIEW": "true"}
        with temp_screenshot_sets():
            # A run compares what it would upload: the copies it stages.
            wanted = staged_checksums()
            same = FakeAsc(state="READY_FOR_REVIEW", screenshots=wanted)
            code, output = self.run_live(same, env)
            self.assertEqual(code, 0, output)
            self.assertFalse([c for c in same.writes() if "Screenshot" in c[1]])
            self.assertIn(("PATCH", "/reviewSubmissions/SUB1"), same.calls)
            for live in ({k: v[:-1] for k, v in wanted.items()}, {k: [None, None] for k in wanted}):
                changed = FakeAsc(state="READY_FOR_REVIEW", screenshots=live)
                code, output = self.run_live(changed, env)
                self.assertEqual(code, 1)
                self.assertIn("cannot change", output)
                self.assertFalse(changed.writes())

    def test_waiting_for_review_rerun_with_matching_metadata_changes_nothing(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9",
                       localization={**FakeAsc.LIVE_TEXT, "whatsNew": "Faster dashboards."},
                       app_infos=[app_info("LIVE", "READY_FOR_DISTRIBUTION"), app_info("NEXT", "WAITING_FOR_REVIEW")])
        code, output = self.run_live(fake, self.LIVE_SWITCHES)
        self.assertEqual(code, 0, output)
        self.assertFalse(fake.writes(), "a rerun of a submitted version must not write anything")
        self.assertIn("idempotent", output)

    def test_waiting_for_review_rerun_that_needs_a_change_fails(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9",
                       localization={**FakeAsc.LIVE_TEXT, "whatsNew": "Older notes."})
        code, output = self.run_live(fake, self.LIVE_SWITCHES)
        self.assertEqual(code, 1)
        self.assertIn("whatsNew", output)
        self.assertFalse(fake.writes())


class PreflightTests(unittest.TestCase):
    """--preflight: read-only checks before the build is uploaded (IOS-REL-2)."""

    def run_preflight(self, fake: FakeAsc, env: dict[str, str], **listing: str | None) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp), **listing)
            (loc / "whats_new.txt").write_text("Faster dashboards.\n")
            code, _, output = run_asc(fake, env, "--preflight", metadata=Path(tmp))
        self.assertFalse(fake.writes(), "the preflight must never write")
        return code, output

    def test_an_editable_version_passes_without_a_build(self) -> None:
        code, output = self.run_preflight(FakeAsc(), VersionStateTests.LIVE_SWITCHES)
        self.assertEqual(code, 0, output)
        self.assertIn("preflight passed", output)
        self.assertIn("will be updated: whatsNew", output)

    def test_a_missing_version_fails_with_the_fix(self) -> None:
        code, output = self.run_preflight(FakeAsc(version_exists=False), VersionStateTests.LIVE_SWITCHES)
        self.assertEqual(code, 1)
        self.assertIn("create the version in App Store Connect first", output)

    def test_states_that_rule_out_the_enabled_steps_fail(self) -> None:
        for state, env in (
            ("READY_FOR_SALE", {"UPLOAD_LISTING": "true"}),
            ("READY_FOR_SALE", {"SUBMIT_IOS_REVIEW": "true"}),
            ("IN_REVIEW", {"UPLOAD_WHATS_NEW": "true"}),
            ("WAITING_FOR_REVIEW", {"SUBMIT_IOS_REVIEW": "true"}),
        ):
            with self.subTest(state=state, env=env):
                code, _ = self.run_preflight(FakeAsc(state=state), env)
                self.assertEqual(code, 1)

    def test_a_missing_locale_fails(self) -> None:
        fake = FakeAsc()
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp))
            write_listing(Path(tmp), locale="fr-FR")
            code, _, output = run_asc(fake, {"UPLOAD_LISTING": "true"}, "--preflight", metadata=Path(tmp))
        self.assertEqual(code, 1)
        self.assertIn("fr-FR", output)

    def test_an_app_info_change_without_an_editable_app_info_fails(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW")  # only the live App Info exists
        code, output = self.run_preflight(fake, {"UPLOAD_LISTING": "true"}, **{"name.txt": "Verceltics"})
        self.assertEqual(code, 1)
        self.assertIn("no App Info is editable", output)


class CreateVersionTests(unittest.TestCase):
    """--create-version (IOS_CREATE_VERSION): add the tag's App Store version only when it is missing."""

    ON = {"CREATE_IOS_VERSION": "true"}

    @staticmethod
    def fake(**kwargs) -> FakeAsc:
        return FakeAsc(**{"app_id": asc_release.DEFAULT_APP_ID, **kwargs})

    def create(self, fake: FakeAsc, flags: dict[str, str] | None = None, *extra: str,
               version: str = VERSION) -> tuple[int, str]:
        code, _, output = run_asc(fake, self.ON if flags is None else flags, "--create-version", *extra,
                                  version=version)
        return code, output

    def test_switch_off_makes_no_calls(self) -> None:
        fake = self.fake(version_exists=False)
        code, output = self.create(fake, {})
        self.assertEqual(code, 0, output)
        self.assertIn("skipped", output)
        self.assertEqual(fake.calls, [])

    def test_existing_version_is_left_alone(self) -> None:
        fake = self.fake(state="READY_FOR_REVIEW")
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertIn("already exists (READY_FOR_REVIEW)", output)
        self.assertEqual(fake.writes(), [])

    def test_missing_version_is_created_for_this_app_and_read_back(self) -> None:
        fake = self.fake(version_exists=False)
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.writes(), [("POST", "/appStoreVersions")])
        body = fake.bodies[0][2]["data"]
        self.assertEqual(body["attributes"], {"platform": "IOS", "versionString": VERSION})
        self.assertEqual(body["relationships"]["app"]["data"], {"type": "apps", "id": asc_release.DEFAULT_APP_ID})
        self.assertEqual(fake.calls[-1][0], "GET", "the created version must be read back")
        self.assertIn(f"created App Store version {VERSION} (PREPARE_FOR_SUBMISSION)", output)

    def test_another_version_on_its_way_blocks_creation(self) -> None:
        for state in ("PREPARE_FOR_SUBMISSION", "WAITING_FOR_REVIEW", "PENDING_DEVELOPER_RELEASE"):
            with self.subTest(state=state):
                fake = self.fake(version_exists=False, other_versions=[("2.1", "READY_FOR_SALE"), ("2.0", state)])
                code, output = self.create(fake)
                self.assertEqual(code, 1)
                self.assertIn(f"is still {state}", output)
                self.assertEqual(fake.writes(), [])

    def test_version_not_newer_than_an_existing_one_is_refused(self) -> None:
        for existing in ("2.3", "2.2.1", "3"):
            with self.subTest(existing=existing):
                fake = self.fake(version_exists=False, other_versions=[(existing, "READY_FOR_SALE")])
                code, output = self.create(fake)
                self.assertEqual(code, 1)
                self.assertIn(f"not newer than the existing version {existing}", output)
                self.assertEqual(fake.writes(), [])

    def test_older_finished_versions_allow_creation(self) -> None:
        fake = self.fake(version_exists=False,
                         other_versions=[("2.0", "REPLACED_WITH_NEW_VERSION"), ("2.1.10", "READY_FOR_SALE")])
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.writes(), [("POST", "/appStoreVersions")])

    def test_other_app_or_bad_version_fails_without_writing(self) -> None:
        fake = self.fake(app_id="1234567890", version_exists=False)
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertIn(f"expected {asc_release.DEFAULT_APP_ID}", output)
        self.assertEqual(fake.writes(), [])
        for bad in ("2.2-beta", "v2.2", "2.2.1.1", "٢.٢"):
            with self.subTest(bad=bad):
                fake = self.fake(version_exists=False)
                code, output = self.create(fake, version=bad)
                self.assertEqual(code, 1)
                self.assertIn("must look like", output)
                self.assertEqual(fake.calls, [])

    def test_dry_run_makes_no_calls(self) -> None:
        fake = self.fake(version_exists=False)
        code, output = self.create(fake, None, "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertIn("would create", output)
        self.assertEqual(fake.calls, [])

    def test_dry_run_rejects_a_bad_version_like_a_live_run(self) -> None:
        for bad in ("2.2-beta", "v2.2", "2.2.1.1", "٢.٢"):
            with self.subTest(bad=bad):
                fake = self.fake(version_exists=False)
                code, output = self.create(fake, None, "--dry-run", version=bad)
                self.assertEqual(code, 1)
                self.assertIn("must look like", output)
                self.assertEqual(fake.calls, [])

    def test_create_version_never_runs_together_with_the_preflight(self) -> None:
        fake = self.fake(version_exists=False)
        code, output = self.create(fake, {**self.ON, "SUBMIT_IOS_REVIEW": "true"}, "--preflight")
        self.assertEqual(code, 1)
        self.assertIn("separate steps", output)
        self.assertEqual(fake.calls, [])
        # Refused even with the switch off, so the combination never silently skips the preflight.
        code, output = self.create(fake, {"SUBMIT_IOS_REVIEW": "true"}, "--preflight")
        self.assertEqual(code, 1)
        self.assertIn("separate steps", output)
        self.assertEqual(fake.calls, [])

    def test_a_created_version_that_does_not_read_back_fails_naming_it(self) -> None:
        class Vanishing(FakeAsc):
            def post(self, path: str, body: dict) -> dict:
                result = super().post(path, body)
                self.version_exists = False  # App Store Connect does not list it yet
                return result

        fake = Vanishing(app_id=asc_release.DEFAULT_APP_ID, version_exists=False)
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertEqual(fake.writes(), [("POST", "/appStoreVersions")])
        self.assertIn(f"created App Store version {VERSION}, but reading it back found 0 records", output)
        self.assertIn("check it in App Store Connect before rerunning", output)
        self.assertNotIn("turn on IOS_CREATE_VERSION", output)


class UnusedListingTests(unittest.TestCase):
    """P-14: with IOS_UPLOAD_LISTING off, the release prepares no listing
    (ios_metadata.py prepare --no-listing) and the store steps never need one."""

    def test_whats_new_alone_preflights_and_uploads_only_whats_new(self) -> None:
        env = {"UPLOAD_WHATS_NEW": "true"}
        with tempfile.TemporaryDirectory() as tmp:
            loc = Path(tmp) / "ios" / "en-US"
            loc.mkdir(parents=True)
            (loc / "whats_new.txt").write_text("Faster dashboards.\n")
            preflight = FakeAsc()
            code, _, output = run_asc(preflight, env, "--preflight", metadata=Path(tmp))
            self.assertEqual(code, 0, output)
            self.assertIn("will be updated: whatsNew", output)
            self.assertFalse(preflight.writes(), "the preflight must never write")
            fake = FakeAsc()
            code, _, output = run_asc(fake, env, metadata=Path(tmp))
        self.assertEqual(code, 0, output)
        self.assertIn("updated version localization (en-US): whatsNew", output)
        self.assertEqual(fake.writes(), [("PATCH", "/appStoreVersionLocalizations/LOC")])
        for calls in (preflight.calls, fake.calls):
            self.assertFalse([c for c in calls if "appInfo" in c[1]], "App Info belongs to the listing")

    def test_review_alone_needs_no_prepared_metadata(self) -> None:
        # Without the listing and What's New, prepare --no-listing writes nothing at all.
        with tempfile.TemporaryDirectory() as tmp:
            fake = FakeAsc()
            code, _, output = run_asc(fake, {"SUBMIT_IOS_REVIEW": "true"}, *LiveSubmitFlowTests.BUILD,
                                      metadata=Path(tmp) / "store-meta")
        self.assertEqual(code, 0, output)
        self.assertIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)
        self.assertFalse([c for c in fake.calls if "Localizations" in c[1] or "appInfo" in c[1]])


class AppInfoClient:
    """Fake App Info endpoints for name/subtitle/privacy URL uploads."""

    def __init__(self, infos: list[dict], live: dict) -> None:
        self.infos = infos
        self.live = live
        self.patches: list[tuple[str, dict]] = []

    def get(self, path: str) -> dict:
        if path.startswith(f"/apps/{APP}/appInfos"):
            return {"data": self.infos}
        if "/appInfoLocalizations" in path:
            return {"data": [{"id": "INFOLOC", "attributes": {"locale": "en-US", **self.live}}]}
        raise AssertionError(f"unexpected GET {path}")

    def patch(self, path: str, body: dict) -> dict:
        self.patches.append((path, body["data"]["attributes"]))
        return {"data": body["data"]}


def app_info(info_id: str, state: str) -> dict:
    return {"id": info_id, "attributes": {"state": state}}


class AppInfoUploadTests(unittest.TestCase):
    LIVE = {"name": LISTING["name.txt"], "subtitle": LISTING["subtitle.txt"],
            "privacyPolicyUrl": LISTING["privacy_url.txt"]}

    def upload(self, client: AppInfoClient, **overrides: str | None) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp), **overrides)
            try:
                quiet(asc_release.upload_app_info, client, app_id=APP, metadata_dir=Path(tmp))
            except SystemExit as exc:
                return exc.code if isinstance(exc.code, int) else 1
        return 0

    def test_changed_fields_go_to_the_editable_app_info(self) -> None:
        client = AppInfoClient([app_info("LIVE", "READY_FOR_DISTRIBUTION"),
                                app_info("NEXT", "PREPARE_FOR_SUBMISSION")], self.LIVE)
        self.assertEqual(self.upload(client, **{"subtitle.txt": "Hosting, Domains, Uptime"}), 0)
        self.assertEqual(client.patches, [("/appInfoLocalizations/INFOLOC", {"subtitle": "Hosting, Domains, Uptime"})])

    def test_unchanged_values_need_no_editable_app_info(self) -> None:
        client = AppInfoClient([app_info("LIVE", "READY_FOR_DISTRIBUTION")], self.LIVE)
        self.assertEqual(self.upload(client), 0)
        self.assertEqual(client.patches, [])

    def test_a_change_without_an_editable_app_info_fails(self) -> None:
        client = AppInfoClient([app_info("LIVE", "READY_FOR_DISTRIBUTION")], self.LIVE)
        self.assertEqual(self.upload(client, **{"name.txt": "Verceltics"}), 1)
        self.assertEqual(client.patches, [])

    def test_two_editable_app_infos_are_ambiguous(self) -> None:
        client = AppInfoClient([app_info("A", "PREPARE_FOR_SUBMISSION"),
                                app_info("B", "DEVELOPER_REJECTED")], self.LIVE)
        self.assertEqual(self.upload(client, **{"name.txt": "Verceltics"}), 1)
        self.assertEqual(client.patches, [])


def png(width: int, height: int) -> bytes:
    """A minimal valid PNG header with the given size (enough for dimension checks)."""
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    chunk = b"IHDR" + ihdr
    return (b"\x89PNG\r\n\x1a\n" + struct.pack(">I", len(ihdr)) + chunk
            + struct.pack(">I", zlib.crc32(chunk)))


class ListingMetadataTests(unittest.TestCase):
    # The repository's own listing and screenshots are checked in test_store_assets.py.

    def test_limits_urls_and_required_fields(self) -> None:
        cases = {
            "name.txt": ("n" * 31, "limit is 30"),
            "subtitle.txt": ("s" * 31, "limit is 30"),
            "promotional_text.txt": ("p" * 171, "limit is 170"),
            "keywords.txt": ("k" * 101, "limit is 100"),
            "description.txt": ("d" * 4001, "limit is 4000"),
            "support_url.txt": ("http://verceltics.com", "https"),
            "privacy_url.txt": ("verceltics.com/privacy", "https"),
        }
        for name, (text, message) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                loc = write_listing(Path(tmp), **{name: text})
                with self.assertRaisesRegex(ios_metadata.MetadataError, message):
                    ios_metadata.load_locale(loc)
        for name in ("description.txt", "support_url.txt", "name.txt"):
            with self.subTest(missing=name), tempfile.TemporaryDirectory() as tmp:
                loc = write_listing(Path(tmp), **{name: None})
                with self.assertRaisesRegex(ios_metadata.MetadataError, "missing"):
                    ios_metadata.load_locale(loc)
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp), **{"keywords.txt": None, "marketing_url.txt": None})
            self.assertNotIn("keywords.txt", ios_metadata.load_locale(loc))

    def test_unknown_files_and_missing_primary_locale_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text("stale notes")
            with self.assertRaisesRegex(ios_metadata.MetadataError, "unexpected"):
                ios_metadata.load_locale(loc)
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp), locale="de-DE")
            with self.assertRaisesRegex(ios_metadata.MetadataError, "en-US"):
                ios_metadata.locales(Path(tmp) / "ios")

    def test_prepare_copies_every_locale_and_adds_the_tags_whats_new(self) -> None:
        notes = "## ios-v2.2\n\nFaster dashboards.\n"
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            write_listing(Path(src))
            write_listing(Path(src), locale="fr-FR")
            with mock.patch.object(release_notes, "ROOT", Path(src)):
                (Path(src) / "RELEASE_NOTES.md").write_text(notes)
                written = ios_metadata.prepare(Path(out), "ios-v2.2", Path(src) / "ios")
                self.assertEqual((Path(out) / "ios" / "en-US" / "whats_new.txt").read_text(), "Faster dashboards.\n")
                self.assertFalse((Path(out) / "ios" / "fr-FR" / "whats_new.txt").exists())
                self.assertEqual(len(written), 2 * len(LISTING) + 1)
                # A tag without a section prepares the listing and no What's New.
                written = ios_metadata.prepare(Path(out) / "next", "ios-v2.3", Path(src) / "ios")
                self.assertFalse(any(p.name == "whats_new.txt" for p in written))

    def test_whats_new_needs_notes_within_the_app_store_limit(self) -> None:
        with tempfile.TemporaryDirectory() as src, mock.patch.object(release_notes, "ROOT", Path(src)):
            notes = Path(src) / "RELEASE_NOTES.md"
            notes.write_text("## ios-v2.2\n\nFaster dashboards.\n")
            self.assertEqual(ios_metadata.whats_new("ios-v2.2"), "Faster dashboards.")
            with self.assertRaisesRegex(ios_metadata.MetadataError, "## ios-v2.3"):
                ios_metadata.whats_new("ios-v2.3")
            notes.write_text("## ios-v2.2\n\n" + "x" * 4001 + "\n")
            with self.assertRaisesRegex(ios_metadata.MetadataError, "limit is 4000"):
                ios_metadata.whats_new("ios-v2.2")

    def test_whats_new_is_plain_text_for_the_app_store(self) -> None:
        notes = ("## ios-v2.2\n\n### Highlights\n\n- **Faster** [deploys](https://example.com)\n"
                 "  * nested `logs`\n> Quoted\n![chart](https://example.com/a.png)\n```\ncode\n```\n")
        with tempfile.TemporaryDirectory() as src, mock.patch.object(release_notes, "ROOT", Path(src)):
            (Path(src) / "RELEASE_NOTES.md").write_text(notes)
            self.assertEqual(
                ios_metadata.whats_new("ios-v2.2"),
                "Highlights\n\n• Faster deploys\n  • nested logs\nQuoted\nchart\ncode",
            )
            # The limit applies to the text App Store Connect receives.
            (Path(src) / "RELEASE_NOTES.md").write_text("## ios-v2.2\n\n**" + "x" * 3998 + "**\n")
            self.assertEqual(len(ios_metadata.whats_new("ios-v2.2")), 3998)
            (Path(src) / "RELEASE_NOTES.md").write_text("## ios-v2.2\n\n```\n```\n")
            with self.assertRaisesRegex(ios_metadata.MetadataError, "no text"):
                ios_metadata.whats_new("ios-v2.2")

    def test_check_covers_only_the_assets_asked_for(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            empty = tuple(ios_metadata.ScreenshotSet(spec.display_type, spec.label, Path(tmp) / "none", spec.sizes)
                          for spec in ios_metadata.SCREENSHOT_SETS)
            listing = Path(tmp) / "listing"
            write_listing(listing)

            def check(*flags: str) -> int:
                with mock.patch.object(ios_metadata, "SCREENSHOT_SETS", empty), \
                        mock.patch.object(ios_metadata, "LISTING_DIR", listing / "ios"), \
                        mock.patch.object(ios_metadata, "ROOT", Path(tmp)):
                    try:
                        quiet(ios_metadata.main, ["check", *flags])
                    except SystemExit as exc:
                        return exc.code if isinstance(exc.code, int) else 1
                return 0

            self.assertEqual(check("--listing"), 0, "unused screenshots never block a listing check")
            self.assertEqual(check("--screenshots"), 1)
            self.assertEqual(check(), 1, "no flag checks everything")

    def test_prepare_without_a_tag_never_adds_whats_new(self) -> None:
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            write_listing(Path(src))
            (Path(src) / "RELEASE_NOTES.md").write_text("## ios-v2.2\n\n" + "x" * 5000 + "\n")
            with mock.patch.object(release_notes, "ROOT", Path(src)):
                written = ios_metadata.prepare(Path(out), None, Path(src) / "ios")
            self.assertFalse(any(p.name == "whats_new.txt" for p in written))

    def test_without_the_listing_switch_the_listing_is_not_read(self) -> None:
        # P-14: with IOS_UPLOAD_LISTING off, an invalid listing never blocks What's New,
        # screenshots or review: prepare --no-listing does not read it.
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            write_listing(Path(src), **{"keywords.txt": "k" * 150})  # invalid, but unused
            (Path(src) / "RELEASE_NOTES.md").write_text("## ios-v2.2\n\nFaster dashboards.\n")
            with mock.patch.object(release_notes, "ROOT", Path(src)):
                self.assertEqual(
                    ios_metadata.prepare(Path(out) / "none", None, Path(src) / "ios", include_listing=False), [])
                written = ios_metadata.prepare(Path(out), "ios-v2.2", Path(src) / "ios", include_listing=False)
                self.assertEqual(written, [Path(out) / "ios" / "en-US" / "whats_new.txt"])
                self.assertEqual(written[0].read_text(), "Faster dashboards.\n")
                with self.assertRaisesRegex(ios_metadata.MetadataError, "keywords.txt"):
                    ios_metadata.prepare(Path(out) / "full", "ios-v2.2", Path(src) / "ios")

            def cli(*flags: str) -> int:
                argv = ["prepare", "--out", str(Path(out) / "cli"), "--tag", "ios-v2.2", *flags]
                with mock.patch.object(ios_metadata, "LISTING_DIR", Path(src) / "ios"), \
                        mock.patch.object(release_notes, "ROOT", Path(src)):
                    try:
                        quiet(ios_metadata.main, argv)
                    except SystemExit as exc:
                        return exc.code if isinstance(exc.code, int) else 1
                return 0

            self.assertEqual(cli("--no-listing"), 0)
            self.assertEqual(sorted(p.name for p in (Path(out) / "cli" / "ios" / "en-US").iterdir()),
                             ["whats_new.txt"])
            self.assertEqual(cli(), 1, "a run that uploads the listing still fails on it")

    def test_screenshot_sets_accept_only_app_store_sizes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            spec = ios_metadata.ScreenshotSet("APP_IPHONE_67", "iPhone", Path(tmp), frozenset({(1290, 2796)}))
            with mock.patch.object(ios_metadata, "ROOT", Path(tmp).parent):
                with self.assertRaisesRegex(ios_metadata.MetadataError, "no iPhone screenshots"):
                    ios_metadata.screenshot_files(spec)
                (Path(tmp) / "b.png").write_bytes(png(1290, 2796))
                (Path(tmp) / "a.png").write_bytes(png(1290, 2796))
                self.assertEqual([p.name for p in ios_metadata.screenshot_files(spec)], ["a.png", "b.png"])
                (Path(tmp) / "c.png").write_bytes(png(1179, 2556))
                with self.assertRaisesRegex(ios_metadata.MetadataError, "1179x2556"):
                    ios_metadata.screenshot_files(spec)
                (Path(tmp) / "c.png").write_bytes(b"not a png")
                with self.assertRaisesRegex(ios_metadata.MetadataError, "not a PNG"):
                    ios_metadata.screenshot_files(spec)
                (Path(tmp) / "c.png").unlink()
                for i in range(9):
                    (Path(tmp) / f"z{i}.png").write_bytes(png(1290, 2796))
                with self.assertRaisesRegex(ios_metadata.MetadataError, "accepts 10"):
                    ios_metadata.screenshot_files(spec)



class ScreenshotUploadTests(unittest.TestCase):
    def test_replaces_old_screenshots_and_respects_capacity(self) -> None:
        state = {"ids": [f"OLD{i}" for i in range(10)], "uploaded": []}
        client = mock.Mock()

        def get(path: str) -> dict:
            if path.endswith("/appScreenshotSets"):
                return {"data": [{"id": "SET", "attributes": {"screenshotDisplayType": "APP_IPHONE_67"}}]}
            if path.endswith("/appScreenshots"):
                return {"data": [{"id": i} for i in state["ids"]]}
            raise AssertionError(path)

        def request(method: str, path: str, **_: object) -> None:
            assert method == "DELETE"
            state["ids"].remove(path.rsplit("/", 1)[1])

        def upload(_client, _set_id, path: Path) -> None:
            state["ids"].append(f"NEW-{path.name}")
            state["uploaded"].append(path.name)

        client.get.side_effect = get
        client.request.side_effect = request
        with tempfile.TemporaryDirectory() as tmp:
            files = []
            for i in range(3):
                path = Path(tmp) / f"{i}.png"
                path.write_bytes(png(1290, 2796))
                files.append(path)
            with mock.patch.object(asc_release, "upload_screenshot_file", side_effect=upload):
                quiet(asc_release.replace_screenshot_set, client, "LOC", "APP_IPHONE_67", files)
        self.assertEqual(state["uploaded"], ["0.png", "1.png", "2.png"])
        self.assertEqual(state["ids"], ["NEW-0.png", "NEW-1.png", "NEW-2.png"])


def writes(fake: FakeAsc) -> list[tuple[str, str]]:
    """Every call that is not a GET (uploads included)."""
    return [call for call in fake.calls if call[0] != "GET"]


def chunk_types(raw: bytes) -> list[bytes]:
    return [kind for kind, _ in asc_release.png_chunks(raw)]


class CreativeGateTests(unittest.TestCase):
    """IOS_UPLOAD_CREATIVE_ASSETS follows every switch convention."""

    def test_creative_assets_are_their_own_switch(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_CREATIVE_ASSETS": "true"})
        self.assertTrue(result["creative"])
        self.assertTrue(result["asc"], "the store job runs for creative assets alone")
        self.assertFalse(result["build"])
        for key in OPS:
            self.assertFalse(result[key], key)
        self.assertFalse(gates({**ALL_ON, "IOS_UPLOAD_CREATIVE_ASSETS": "false"})["creative"])

    def test_it_needs_the_master_switch_and_validation_runs_never_use_it(self) -> None:
        result = gates({"IOS_UPLOAD_CREATIVE_ASSETS": "true"})
        self.assertFalse(result["creative"])
        self.assertFalse(result["asc"])
        self.assertTrue(any("IOS_UPLOAD_CREATIVE_ASSETS" in note for note in result["notes"]))
        manual = gates({**ALL_ON, "IOS_UPLOAD_CREATIVE_ASSETS": "true"}, event="workflow_dispatch", ref="main")
        self.assertFalse(manual["creative"])

    def test_invalid_values_fail_even_when_the_master_is_off(self) -> None:
        for env in ({"IOS_UPLOAD_CREATIVE_ASSETS": "maybe"},
                    {"IOS_RELEASE_AUTOMATION": "false", "IOS_UPLOAD_CREATIVE_ASSETS": "2"}):
            with self.subTest(env=env), self.assertRaisesRegex(ReleaseError, "IOS_UPLOAD_CREATIVE_ASSETS"):
                gates(env)

    def test_every_ios_switch_is_printed_and_documented(self) -> None:
        example = (Path(ios_release.ROOT) / "store" / "gates.env.example").read_text()
        self.assertEqual(dict(ios_release.OPERATION_SWITCHES).get("IOS_UPLOAD_CREATIVE_ASSETS"), "creative")
        for name, _ in ios_release.OPERATION_SWITCHES:
            with self.subTest(name=name):
                self.assertIn(name, print_gates.GATES["ios"])
                self.assertIn(f"\n{name}=false\n", example)

    def test_preflight_reads_the_switch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            # Images approved and placed: the preflight passes without writing.
            library, placements = [], {}
            for image_id, folder, placement, raw in (
                    ("HDR", "header", "PRODUCT_PAGE_HEADER_ASSET", creative_png(3840, 1646)),
                    ("SRCH", "search-results", "APP_STORE_SEARCH_RESULTS_ASSET", creative_png(1920, 1280))):
                path = Path(tmp) / folder / f"verceltics-{folder}.png"
                path.parent.mkdir()
                path.write_bytes(raw)
                library.append({"id": image_id, "attributes": {"fileName": path.name, "fileSize": len(raw),
                                                               "state": "APPROVED"}})
                placements[f"PL-{image_id}"] = {"type": placement, "image": image_id, "loc": "LOC"}
            fake = CreativeFake(library=library, placements=placements)
            code, _, output = run_asc(fake, {"UPLOAD_CREATIVE_ASSETS": "true"}, "--preflight", "--creative-dir", tmp)
            self.assertEqual(code, 0, output)
            # The images go on the version's localization, so the preflight checks it, and their library state.
            self.assertTrue([c for c in fake.calls if "appStoreVersionLocalizations" in c[1]])
            self.assertIn(("GET", f"/apps/{APP}/assetLibrary"), fake.calls)
            self.assertEqual(writes(fake), [])
            # Off, the Asset Library is never read.
            off = CreativeFake(library=library, placements=placements)
            code, _, output = run_asc(off, {"SUBMIT_IOS_REVIEW": "true"}, "--preflight", "--creative-dir", tmp)
            self.assertEqual(code, 0, output)
            self.assertFalse([c for c in off.calls if "assetlibrary" in c[1].lower()])

    def test_print_gates_lists_it(self) -> None:
        clean = {k: v for k, v in os.environ.items() if not k.startswith(("IOS_", "ANDROID_", "STORE_"))}
        for value, shown in (("true", "IOS_UPLOAD_CREATIVE_ASSETS=true\n"),
                             ("maybe", "IOS_UPLOAD_CREATIVE_ASSETS=INVALID 'maybe'")):
            out = io.StringIO()
            with mock.patch.dict(os.environ, {**clean, "IOS_UPLOAD_CREATIVE_ASSETS": value}, clear=True), \
                    mock.patch.object(sys, "argv", ["print_gates.py", "--platform", "ios"]), redirect_stdout(out):
                print_gates.main()
            self.assertIn(shown, out.getvalue())


class ScreenshotAlphaGuardTests(unittest.TestCase):
    """The last guard: a staged file that still stores alpha never reaches App Store Connect."""

    def test_alpha_is_detected_the_way_app_store_connect_refuses_it(self) -> None:
        cases = {
            "rgb": (make_png(2, 2, 2), False),
            "grey": (make_png(2, 2, 0), False),
            "palette": (make_png(2, 2, 3), False),
            "grey + alpha": (make_png(2, 2, 4), True),
            "rgba": (make_png(2, 2, 6), True),
            "rgb + tRNS": (make_png(2, 2, 2, chunks=((b"tRNS", b"\x00\x00\x00\x00\x00\x00"),)), True),
            "palette + tRNS": (make_png(2, 2, 3, chunks=((b"tRNS", b"\x00"),)), True),
        }
        for label, (raw, alpha) in cases.items():
            with self.subTest(label):
                self.assertEqual(asc_release.png_stores_alpha(asc_release.png_chunks(raw)), alpha)

    def test_truncated_or_corrupt_pngs_are_refused(self) -> None:
        good = make_png(8, 8)
        ihdr_end = 8 + 12 + 13
        idat = good.index(b"IDAT") - 4
        corrupt_crc = bytearray(good)
        corrupt_crc[idat + 8] ^= 0xFF  # first byte of the IDAT data; its CRC no longer matches
        cases = {
            "signature and IHDR only": good[:ihdr_end],
            "cut in the image data": good[: idat + 10],
            "no IEND": good[:-12],
            "IEND without its CRC": good[:-4],
            "bad CRC": bytes(corrupt_crc),
            "no IDAT": good[:ihdr_end] + good[-12:],
            "image data is not zlib": make_png(8, 8)[:idat] + self.chunk(b"IDAT", b"not zlib") + good[-12:],
            "image data for another size": make_png(8, 8)[:idat] + make_png(4, 4)[make_png(4, 4).index(b"IDAT") - 4:],
        }
        for label, raw in cases.items():
            with self.subTest(label), self.assertRaises(ValueError):
                asc_release.png_chunks(raw)
        self.assertEqual([kind for kind, _ in asc_release.png_chunks(good)], [b"IHDR", b"IDAT", b"IEND"])
        # Lenient reading (staging without Pillow) only needs the header.
        self.assertEqual(asc_release.png_chunks(good[:ihdr_end], strict=False)[0][0], b"IHDR")

    @staticmethod
    def chunk(kind: bytes, data: bytes) -> bytes:
        return len(data).to_bytes(4, "big") + kind + data + zlib.crc32(kind + data).to_bytes(4, "big")

    def test_staged_files_with_alpha_or_not_png_are_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "01-home.png"
            for raw in (make_png(4, 4, 6), make_png(4, 4, 4), make_png(4, 4, 2, chunks=((b"tRNS", b"\x00" * 6),)),
                        b"\x89PNG\r\n\x1a\n" + b"screenshot" * 4, b"GIF89a", make_png(4, 4)[:-12]):
                path.write_bytes(raw)
                with self.subTest(raw=raw[:30]), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    asc_release.staged_png(path, 'iPhone 6.7"')
            path.write_bytes(make_png(4, 4, 2))
            self.assertEqual(asc_release.staged_png(path, 'iPhone 6.7"'), (4, 4))

    def test_a_staged_set_that_stores_alpha_stops_the_upload_before_any_call(self) -> None:
        # Whatever put it there, a staged copy that stores alpha is never uploaded.
        with temp_screenshot_sets(), tempfile.TemporaryDirectory() as tmp:
            staged = quiet(asc_release.stage_screenshots, Path(tmp))
            (staged / "ipad" / "1.png").write_bytes(make_png(*SMALL, 6))
            fake = FakeAsc()
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                asc_release.upload_screenshots(fake, version_id="V1", locale="en-US", screenshots_dir=staged)
            self.assertEqual(fake.calls, [])


class WatchScreenshotTests(unittest.TestCase):
    """Apple Watch frames are uploaded like the other sets, by the display type their size belongs to."""

    def setUp(self) -> None:
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(temp_screenshot_sets())
        self.watch = ios_metadata.WATCH_SCREENSHOTS
        self.watch.mkdir()
        self.watch_png = make_png(416, 496)
        (self.watch / "01-home-glance.png").write_bytes(self.watch_png)

    def run_main(self, fake: FakeAsc, env: dict[str, str]) -> int:
        build = LiveSubmitFlowTests.BUILD if "SUBMIT_IOS_REVIEW" in env else ()
        code, _, _ = run_asc(fake, env, *build)
        return code

    def staged(self) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        return quiet(asc_release.stage_screenshots, Path(tmp.name))

    def test_each_watch_size_maps_to_its_display_type(self) -> None:
        (self.watch / "02-ultra-3.png").write_bytes(make_png(422, 514))
        (self.watch / "03-ultra.png").write_bytes(make_png(410, 502))
        (self.watch / "04-series-7.png").write_bytes(make_png(396, 484))
        staged = self.staged()
        sets = asc_release.staged_watch_sets(staged / asc_release.WATCH_FOLDER)
        self.assertEqual(
            [(display, [p.name for p in pngs]) for display, _, pngs in sets],
            [("APP_WATCH_SERIES_10", ["01-home-glance.png"]),
             ("APP_WATCH_ULTRA", ["02-ultra-3.png", "03-ultra.png"]),
             ("APP_WATCH_SERIES_7", ["04-series-7.png"])],
        )
        # The committed frames are checked the same way (ios_metadata.py check --screenshots).
        self.assertEqual([(spec.display_type, [p.name for p in files])
                          for spec, files in ios_metadata.watch_screenshot_sets()],
                         [(display, [p.name for p in pngs]) for display, _, pngs in sets])
        expected = {(416, 496): "APP_WATCH_SERIES_10", (422, 514): "APP_WATCH_ULTRA", (410, 502): "APP_WATCH_ULTRA",
                    (396, 484): "APP_WATCH_SERIES_7", (368, 448): "APP_WATCH_SERIES_4", (312, 390): "APP_WATCH_SERIES_3"}
        self.assertEqual(asc_release.WATCH_DISPLAY_TYPES, expected)
        # The Watch sets come after the iPhone and iPad sets.
        displays = [display for display, _, _ in asc_release.staged_screenshot_sets(staged)]
        self.assertEqual(displays, ["APP_IPHONE_67", "APP_IPAD_PRO_3GEN_129",
                                    "APP_WATCH_SERIES_10", "APP_WATCH_ULTRA", "APP_WATCH_SERIES_7"])

    def test_a_frame_that_is_no_watch_size_or_stores_alpha_fails_before_any_call(self) -> None:
        # Without Pillow a frame is staged as it is, so one that stores alpha is refused too.
        for name, raw in (("02-phone.png", make_png(1290, 2796)), ("02-alpha.png", make_png(416, 496, 6))):
            (self.watch / name).write_bytes(raw)
            fake = FakeAsc()
            with self.subTest(name=name), mock.patch.object(asc_release, "try_import_pillow", return_value=None):
                self.assertEqual(self.run_main(fake, {"UPLOAD_SCREENSHOTS": "true"}), 1)
                self.assertEqual(fake.calls, [])
            (self.watch / name).unlink()
        # The last guard: a staged Watch frame that still stores alpha is refused.
        staged = self.staged()
        (staged / asc_release.WATCH_FOLDER / "02-alpha.png").write_bytes(make_png(416, 496, 6))
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            asc_release.staged_watch_sets(staged / asc_release.WATCH_FOLDER)

    def test_the_watch_set_is_uploaded_with_its_display_type(self) -> None:
        fake = FakeAsc()
        uploads = []
        with mock.patch.object(asc_release, "upload_screenshot_file",
                               lambda client, set_id, path: uploads.append((set_id, path.name))):
            self.assertEqual(self.run_main(fake, {"UPLOAD_SCREENSHOTS": "true"}), 0)
        self.assertEqual(fake.created_sets, ["APP_IPHONE_67", "APP_IPAD_PRO_3GEN_129", "APP_WATCH_SERIES_10"])
        self.assertEqual(uploads[-1], ("NEW-APP_WATCH_SERIES_10", "01-home-glance.png"))

    def test_a_watch_set_is_replaced_and_other_watch_sets_are_left_alone(self) -> None:
        # Like every set with staged frames, the live Series 10 set is replaced; the
        # Ultra set, which the repository has no frames for, is left alone.
        live = {"APP_IPHONE_67": ["x", "y"], "APP_IPAD_PRO_3GEN_129": ["x", "y"],
                "APP_WATCH_SERIES_10": ["old"], "APP_WATCH_ULTRA": ["by hand"]}
        fake = FakeAsc(screenshots=live)
        uploads = []
        with mock.patch.object(asc_release, "upload_screenshot_file",
                               lambda client, set_id, path: uploads.append((set_id, path.name))):
            self.assertEqual(self.run_main(fake, {"UPLOAD_SCREENSHOTS": "true"}), 0)
        self.assertEqual(uploads[-1], ("SET-APP_WATCH_SERIES_10", "01-home-glance.png"))
        self.assertIn(("DELETE", "/appScreenshots/APP_WATCH_SERIES_10-0"), fake.calls)
        self.assertFalse([c for c in writes(fake) if "APP_WATCH_ULTRA" in c[1]])
        self.assertFalse([u for u in uploads if "APP_WATCH_ULTRA" in u[0]])
        self.assertEqual(fake.created_sets, [])

    def test_a_locked_version_only_compares_the_watch_set(self) -> None:
        env = {"UPLOAD_SCREENSHOTS": "true", "SUBMIT_IOS_REVIEW": "true"}
        same = staged_checksums()
        self.assertEqual(sorted(same), ["APP_IPAD_PRO_3GEN_129", "APP_IPHONE_67", "APP_WATCH_SERIES_10"])
        fake = FakeAsc(state="READY_FOR_REVIEW", screenshots=same)
        self.assertEqual(self.run_main(fake, env), 0)
        self.assertFalse([c for c in writes(fake) if "creenshot" in c[1]])
        # A Watch set that differs while the version is in a review submission stops the run before any write.
        fake = FakeAsc(state="READY_FOR_REVIEW", screenshots={**same, "APP_WATCH_SERIES_10": ["old"]})
        self.assertEqual(self.run_main(fake, env), 1)
        self.assertEqual(writes(fake), [])

    def test_without_staged_watch_frames_the_live_watch_sets_are_left_alone(self) -> None:
        (self.watch / "01-home-glance.png").unlink()
        live = {"APP_IPHONE_67": ["x"], "APP_IPAD_PRO_3GEN_129": ["y"], "APP_WATCH_ULTRA": ["by hand"]}
        fake = FakeAsc(screenshots=live)
        uploads = []
        with mock.patch.object(asc_release, "upload_screenshot_file",
                               lambda client, set_id, path: uploads.append((set_id, path.name))):
            code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 0, output)
        self.assertIn("the Watch sets in App Store Connect are left alone", output)
        self.assertFalse([c for c in writes(fake) if "APP_WATCH" in c[1]])
        self.assertEqual({set_id for set_id, _ in uploads}, {"SET-APP_IPHONE_67", "SET-APP_IPAD_PRO_3GEN_129"})
        self.assertEqual(asc_release.staged_watch_sets(self.watch), [])
        # Locked, a Watch set with nothing staged is not compared either.
        locked = FakeAsc(state="READY_FOR_REVIEW", screenshots={**staged_checksums(), "APP_WATCH_ULTRA": ["by hand"]})
        self.assertEqual(self.run_main(locked, {"UPLOAD_SCREENSHOTS": "true", "SUBMIT_IOS_REVIEW": "true"}), 0)


class DuoScreenshotTests(unittest.TestCase):
    """The iPhone Duo set (outer and inner screen in one set) goes with the others when it has frames."""

    def setUp(self) -> None:
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(temp_screenshot_sets())
        duo, = ios_metadata.OPTIONAL_SCREENSHOT_SETS
        self.assertEqual((duo.display_type, duo.sizes), ("APP_IPHONE_DUO", frozenset({(1398, 2034), (2007, 2853)})))
        self.duo = duo.directory
        self.duo.mkdir()

    def test_the_duo_set_is_uploaded_after_the_ipad_set(self) -> None:
        (self.duo / "01-outer.png").write_bytes(make_png(1398, 2034))
        (self.duo / "02-inner.png").write_bytes(make_png(2007, 2853))
        fake = FakeAsc()
        uploads = []
        with mock.patch.object(asc_release, "upload_screenshot_file",
                               lambda client, set_id, path: uploads.append((set_id, path.name))):
            code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.created_sets, ["APP_IPHONE_67", "APP_IPAD_PRO_3GEN_129", "APP_IPHONE_DUO"])
        self.assertEqual(uploads[-2:], [("NEW-APP_IPHONE_DUO", "01-outer.png"), ("NEW-APP_IPHONE_DUO", "02-inner.png")])

    def test_without_duo_frames_the_live_duo_set_is_left_alone(self) -> None:
        # The folder exists but holds no PNG: nothing to upload for that set.
        live = {"APP_IPHONE_67": ["x"], "APP_IPAD_PRO_3GEN_129": ["y"], "APP_IPHONE_DUO": ["by hand"]}
        fake = FakeAsc(screenshots=live)
        with mock.patch.object(asc_release, "upload_screenshot_file", lambda client, set_id, path: None):
            code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 0, output)
        self.assertIn("the iPhone Duo set in App Store Connect is left alone", output)
        self.assertFalse([c for c in writes(fake) if "APP_IPHONE_DUO" in c[1]])

    def test_a_duo_frame_of_another_size_fails_before_any_call(self) -> None:
        (self.duo / "01-phone.png").write_bytes(make_png(1290, 2796))
        fake = FakeAsc()
        code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 1)
        self.assertIn("iPhone Duo accepts", output)
        self.assertEqual(fake.calls, [])


class StagingWithoutPillowTests(unittest.TestCase):
    """Without Pillow a frame is staged as it is, so one that stores alpha stops the run before any call."""

    def test_frames_that_store_alpha_are_refused_and_rgb_frames_copied_as_they_are(self) -> None:
        cases = {
            "rgba": make_png(*SMALL, 6),
            "grey + alpha": make_png(*SMALL, 4),
            "rgb + tRNS": make_png(*SMALL, 2, chunks=((b"tRNS", b"\x00" * 6),)),
        }
        with temp_screenshot_sets() as sets, mock.patch.object(asc_release, "try_import_pillow", return_value=None):
            iphone = sets[0].directory
            for label, raw in cases.items():
                (iphone / "2-alpha.png").write_bytes(raw)
                fake = FakeAsc()
                with self.subTest(label):
                    code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
                    self.assertEqual(code, 1)
                    self.assertIn("2-alpha.png", output)
                    self.assertIn("install Pillow", output)
                    self.assertEqual(fake.calls, [])
                    self.assertEqual((iphone / "2-alpha.png").read_bytes(), raw, "the committed file is untouched")
            # An RGB file is staged byte for byte.
            (iphone / "2-alpha.png").write_bytes(make_png(*SMALL, fill=7))
            with tempfile.TemporaryDirectory() as tmp:
                staged = asc_release.stage_screenshots(Path(tmp))
                for spec in sets:
                    for path in spec.directory.glob("*.png"):
                        with self.subTest(set=spec.label, name=path.name):
                            self.assertEqual((staged / spec.directory.name / path.name).read_bytes(),
                                             path.read_bytes())

    def test_the_upload_sends_staged_copies_never_the_committed_files(self) -> None:
        with temp_screenshot_sets() as sets, mock.patch.object(asc_release, "try_import_pillow", return_value=None):
            fake = FakeAsc()
            sent = []
            with mock.patch.object(asc_release, "upload_screenshot_file",
                                   lambda client, set_id, path: sent.append((set_id, path))):
                code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
            self.assertEqual(code, 0, output)
            committed = {spec.directory for spec in sets}
            self.assertEqual([(set_id, path.name) for set_id, path in sent],
                             [("NEW-APP_IPHONE_67", "0.png"), ("NEW-APP_IPHONE_67", "1.png"),
                              ("NEW-APP_IPAD_PRO_3GEN_129", "0.png"), ("NEW-APP_IPAD_PRO_3GEN_129", "1.png")])
            for _, path in sent:
                self.assertNotIn(path.parent, committed)
                self.assertFalse(path.exists(), "the staging folder is removed when the run ends")


@unittest.skipIf(PILLOW is None and not os.environ.get("STORE_TESTS_NEED_PILLOW"), "Pillow not installed")
class RgbScreenshotTests(unittest.TestCase):
    """With Pillow, every App Store frame is staged as an RGB PNG without alpha; committed files never change."""

    OPAQUE = (10, 20, 30, 255)
    HIDDEN_RED = (255, 0, 0, 0)  # fully transparent, with a colour that must never show
    HALF = (200, 100, 50, 128)

    def setUp(self) -> None:
        if PILLOW is None:
            self.fail("Pillow is required here (STORE_TESTS_NEED_PILLOW is set)")
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        sets = stack.enter_context(temp_screenshot_sets(frames=False))
        self.ios_src, self.ipad_src = (spec.directory for spec in sets)
        self.duo_src = ios_metadata.OPTIONAL_SCREENSHOT_SETS[0].directory
        self.watch_src = ios_metadata.WATCH_SCREENSHOTS
        self.frame(self.ipad_src, "01-i.png", (2048, 2732))
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.staging = Path(tmp.name)

    def frame(self, directory: Path, name: str, size: tuple[int, int], *, icc: bytes | None = None,
              chunks: tuple[tuple[bytes, bytes], ...] = ()) -> Path:
        from PIL import PngImagePlugin

        img = PILLOW.new("RGBA", size, self.OPAQUE)
        width, height = size
        # The left quarter is transparent; one translucent pixel top right.
        img.paste(self.HIDDEN_RED, (0, 0, width // 4, height))
        img.putpixel((width - 1, 0), self.HALF)
        info = PngImagePlugin.PngInfo()
        for kind, data in chunks:
            info.add(kind, data)
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / name
        img.save(path, pnginfo=info, **({"icc_profile": icc} if icc else {}))
        return path

    def stage(self) -> Path:
        return quiet(asc_release.stage_screenshots, self.staging)

    def rgb(self, path: Path):
        raw = path.read_bytes()
        self.assertEqual(raw[25], 2, f"{path.name} is not an RGB PNG (colour type {raw[25]})")
        self.assertNotIn(b"tRNS", chunk_types(raw))
        img = PILLOW.open(path)
        img.load()
        self.assertEqual(img.mode, "RGB")
        return img

    def test_frames_at_an_accepted_size_keep_their_pixels_over_black(self) -> None:
        source = self.frame(self.ios_src, "01-a.png", (1290, 2796))
        before = hashlib.sha256(source.read_bytes()).hexdigest()

        staged = self.stage()

        img = self.rgb(staged / "iphone" / "01-a.png")
        self.assertEqual(img.size, (1290, 2796))
        self.assertEqual(img.getpixel((1289, 2795)), self.OPAQUE[:3], "opaque pixels are kept as they are")
        self.assertEqual(img.getpixel((0, 0)), (0, 0, 0), "transparent pixels are laid over black")
        # Translucent pixels are blended over black exactly once.
        for got, value in zip(img.getpixel((1289, 0)), self.HALF[:3]):
            self.assertAlmostEqual(got, value * 128 / 255, delta=1)
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), before, "the committed file is untouched")

    def test_every_app_store_set_is_staged_without_alpha(self) -> None:
        self.frame(self.ios_src, "01-a.png", (1290, 2796))
        self.frame(self.duo_src, "01-d.png", (1398, 2034))
        self.frame(self.duo_src, "02-d.png", (2007, 2853))
        self.frame(self.watch_src, "01-w.png", (416, 496))

        staged = self.stage()

        sets = quiet(asc_release.staged_screenshot_sets, staged)  # the last guard passes
        self.assertEqual([display for display, _, _ in sets],
                         ["APP_IPHONE_67", "APP_IPAD_PRO_3GEN_129", "APP_IPHONE_DUO", "APP_WATCH_SERIES_10"])
        for display, _, pngs in sets:
            for path in pngs:
                with self.subTest(set=display, name=path.name):
                    self.rgb(path)

    def test_the_colour_profile_is_kept(self) -> None:
        from PIL import ImageCms

        icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
        gamma = (b"gAMA", (45455).to_bytes(4, "big"))
        chroma = (b"cHRM", bytes(range(32)))
        self.frame(self.ios_src, "01-a.png", (1290, 2796), icc=icc, chunks=(gamma, chroma))

        staged = self.stage() / "iphone" / "01-a.png"

        self.assertEqual(self.rgb(staged).info.get("icc_profile"), icc)
        kept = {kind: data for kind, data in asc_release.png_chunks(staged.read_bytes())}
        self.assertEqual(kept.get(b"gAMA"), gamma[1])
        self.assertEqual(kept.get(b"cHRM"), chroma[1])

    def test_the_release_uploads_the_rgb_copies(self) -> None:
        source = self.frame(self.ios_src, "01-a.png", (1290, 2796))
        before = source.read_bytes()
        fake = FakeAsc()
        sent = []
        with mock.patch.object(asc_release, "upload_screenshot_file",
                               lambda client, set_id, path: sent.append((set_id, path, path.read_bytes()))):
            code, _, output = run_asc(fake, {"UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 0, output)
        self.assertEqual([(set_id, path.name) for set_id, path, _ in sent],
                         [("NEW-APP_IPHONE_67", "01-a.png"), ("NEW-APP_IPAD_PRO_3GEN_129", "01-i.png")])
        for _, path, raw in sent:
            self.assertNotIn(path.parent, (self.ios_src, self.ipad_src))
            self.assertFalse(asc_release.png_stores_alpha(asc_release.png_chunks(raw)))
        self.assertEqual(source.read_bytes(), before, "the committed file is untouched")

    def test_a_locked_version_compares_the_rgb_copies(self) -> None:
        self.frame(self.ios_src, "01-a.png", (1290, 2796))
        env = {"UPLOAD_SCREENSHOTS": "true", "SUBMIT_IOS_REVIEW": "true"}
        # App Store Connect holding the committed RGBA files is not what this run uploads.
        committed = {spec.display_type: [md5(p.read_bytes()) for p in ios_metadata.screenshot_files(spec)]
                     for spec in ios_metadata.SCREENSHOT_SETS}
        fake = FakeAsc(state="READY_FOR_REVIEW", screenshots=committed)
        code, _, output = run_asc(fake, env, *LiveSubmitFlowTests.BUILD)
        self.assertEqual(code, 1)
        self.assertIn("cannot change", output)
        self.assertEqual(writes(fake), [])
        fake = FakeAsc(state="READY_FOR_REVIEW", screenshots=staged_checksums())
        code, _, output = run_asc(fake, env, *LiveSubmitFlowTests.BUILD)
        self.assertEqual(code, 0, output)
        self.assertFalse([c for c in writes(fake) if "creenshot" in c[1]])


@functools.lru_cache(maxsize=None)
def creative_png(width: int, height: int, colour_type: int = 2) -> bytes:
    return make_png(width, height, colour_type)


def jpeg(width: int, height: int) -> bytes:
    """A JPEG header with a baseline start-of-frame segment (enough to read its size)."""
    app0 = b"\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    sof0 = b"\xff\xc0\x00\x11\x08" + height.to_bytes(2, "big") + width.to_bytes(2, "big") + b"\x03" + b"\x01\x11\x00" * 3
    return b"\xff\xd8" + app0 + sof0 + b"\xff\xd9"


class CreativeFake(FakeAsc):
    """FakeAsc plus the Asset Library, image placements and any number of review drafts."""

    def __init__(self, *, library: list[dict] | None = None, placements: dict[str, dict] | None = None,
                 drafts: list[str] | None = None, draft_items: dict[str, list[dict]] | None = None,
                 processing: dict[str, list[str]] | None = None, **kwargs) -> None:
        super().__init__(**kwargs)
        self.library = [json_copy(image) for image in library or []]
        # placement id -> {"type", "image", "loc", "group", "state"}
        self.placements = {pid: dict(p) for pid, p in (placements or {}).items()}
        self.drafts = list(drafts or [])
        self.items = {draft: list(rows) for draft, rows in (draft_items or {}).items()}
        # image id -> the states successive GETs of the image report while Apple processes it
        self.processing = {image_id: list(states) for image_id, states in (processing or {}).items()}
        self.submitted: list[str] = []
        self.placed: list[dict] = []
        self.counter = 0

    def next_id(self, prefix: str) -> str:
        self.counter += 1
        return f"{prefix}{self.counter}"

    def image(self, image_id: str) -> dict:
        return next(image for image in self.library if image["id"] == image_id)

    def get(self, path: str) -> dict:
        parsed = urllib.parse.urlparse(path)
        query = urllib.parse.parse_qs(parsed.query)
        if path == f"/apps/{self.app_id}/assetLibrary":
            self.calls.append(("GET", path))
            return {"data": {"type": "appAssetLibraries", "id": "LIB"}}
        if parsed.path == "/appAssetLibraries/LIB/images":
            self.calls.append(("GET", path))
            assert query.get("filter[category]") == ["CREATIVE_ASSETS"], path
            return {"data": [json_copy(image) for image in self.library]}
        if parsed.path.startswith("/appAssetLibraryImages/") and parsed.path.endswith("/relationships/placements"):
            self.calls.append(("GET", path))
            image_id = parsed.path.split("/")[2]
            return {"data": [{"type": "appAssetLibraryPlacements", "id": pid}
                             for pid, p in self.placements.items() if p["image"] == image_id]}
        if parsed.path.startswith("/appAssetLibraryImages/"):
            self.calls.append(("GET", path))
            image = self.image(parsed.path.split("/")[2])
            states = self.processing.get(image["id"])
            if states:
                image["attributes"]["state"] = states.pop(0)
                if image["attributes"]["state"] == "PREPARE_FOR_SUBMISSION":
                    image["attributes"]["imageAsset"] = {"width": 1, "height": 1}
            return {"data": json_copy(image)}
        if parsed.path == "/appStoreVersionLocalizations/LOC/placements":
            self.calls.append(("GET", path))
            wanted = (query.get("filter[placementType]") or [""])[0]
            return {"data": [{"type": "appAssetLibraryPlacements", "id": pid,
                              "attributes": {"placementType": p["type"], "placementGroup": p.get("group", "DEFAULT_PROFILE"),
                                             "state": p.get("state", "ACTIVE")}}
                             for pid, p in self.placements.items() if p["loc"] == "LOC" and p["type"] == wanted]}
        if path.startswith(f"/apps/{self.app_id}/reviewSubmissions"):
            self.calls.append(("GET", path))
            assert query.get("filter[state]") == ["READY_FOR_REVIEW"], path
            return {"data": [{"type": "reviewSubmissions", "id": draft} for draft in self.drafts]}
        if parsed.path.startswith("/reviewSubmissions/") and parsed.path.endswith("/items"):
            self.calls.append(("GET", path))
            assert "appAssetLibraryImage" in (query.get("include") or [""])[0], path
            return {"data": [json_copy(item) for item in self.items.get(parsed.path.split("/")[2], [])]}
        return super().get(path)

    def post(self, path: str, body: dict) -> dict:
        data = body["data"]
        if path == "/appAssetLibraryImages":
            self.calls.append(("POST", path))
            image_id = self.next_id("IMG")
            attributes = data["attributes"]
            self.library.append({"id": image_id, "attributes": {**attributes, "state": "AWAITING_UPLOAD",
                                                                 "createdDate": "2026-10-21"}})
            ops = [{"url": f"https://upload.example/{image_id}", "offset": 0, "length": attributes["fileSize"],
                    "method": "PUT", "requestHeaders": [{"name": "Content-Type", "value": "image/png"}]}]
            return {"data": {"type": "appAssetLibraryImages", "id": image_id,
                             "attributes": {"state": "AWAITING_UPLOAD", "uploadOperations": ops}}}
        if path == "/reviewSubmissions":
            self.calls.append(("POST", path))
            draft = self.next_id("DRAFT")
            self.drafts.append(draft)
            self.items[draft] = []
            return {"data": {"type": "reviewSubmissions", "id": draft}}
        if path == "/reviewSubmissionItems":
            self.calls.append(("POST", path))
            rels = data["relationships"]
            draft = rels["reviewSubmission"]["data"]["id"]
            (name, related_data), = [(k, v) for k, v in rels.items() if k != "reviewSubmission"]
            self.items.setdefault(draft, []).append(
                {"id": self.next_id("ITEM"), "relationships": {name: {"data": dict(related_data["data"])}}})
            if name == "appAssetLibraryImage":
                self.image(related_data["data"]["id"])["attributes"]["state"] = "READY_FOR_REVIEW"
            return {"data": {"type": "reviewSubmissionItems", "id": "ITEM"}}
        if path == "/appAssetLibraryPlacements":
            self.calls.append(("POST", path))
            self.placed.append(body)
            rels = data["relationships"]
            swap = (rels.get("placementToSwapOut") or {}).get("data")
            if swap:
                del self.placements[swap["id"]]
            self.placements[self.next_id("PL")] = {
                "type": data["attributes"]["placementType"], "image": rels["image"]["data"]["id"],
                "loc": rels["appStoreVersionLocalization"]["data"]["id"],
                "group": data["attributes"]["placementGroup"]}
            return {"data": {"type": "appAssetLibraryPlacements", "id": "PL"}}
        return super().post(path, body)

    def patch(self, path: str, body: dict) -> dict:
        if path.startswith("/appAssetLibraryImages/"):
            self.calls.append(("PATCH", path))
            image = self.image(path.split("/")[2])
            assert body["data"]["attributes"] == {"uploaded": True}
            image["attributes"]["state"] = "UPLOAD_COMPLETE"
            self.processing.setdefault(image["id"], ["UPLOAD_COMPLETE", "PREPARE_FOR_SUBMISSION"])
            return {"data": body["data"]}
        return super().patch(path, body)

    def request(self, method: str, path: str, body: dict | None = None):
        if method == "PATCH" and path.startswith("/reviewSubmissions/"):
            self.calls.append((method, path))
            draft = path.split("/")[2]
            assert body is not None and body["data"]["attributes"] == {"submitted": True}
            self.submitted.append(draft)
            self.drafts.remove(draft)
            for item in self.items.get(draft, []):
                image_id = related(item, "appAssetLibraryImage")
                if image_id:
                    self.image(image_id)["attributes"]["state"] = "WAITING_FOR_REVIEW"
            return None
        return super().request(method, path, body)

    def upload_bytes(self, url: str, data: bytes, headers: dict, *, method: str = "PUT") -> None:
        self.calls.append(("UPLOAD", url))
        image = self.image(url.rsplit("/", 1)[1])
        assert len(data) == image["attributes"]["fileSize"] and headers == {"Content-Type": "image/png"}


def json_copy(value):
    return json.loads(json.dumps(value))


def related(item: dict, name: str):
    return ((item.get("relationships") or {}).get(name) or {}).get("data", {}).get("id")


def image_item(image_id: str) -> dict:
    return {"id": f"I-{image_id}", "relationships": {"appAssetLibraryImage": {"data": {"type": "appAssetLibraryImages", "id": image_id}}}}


def version_item(version_id: str = "V1") -> dict:
    return {"id": f"I-{version_id}", "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}}}


class CreativeAssetTests(unittest.TestCase):
    """Header and search results images: Asset Library upload, their own review, placement."""

    ENV = {"UPLOAD_CREATIVE_ASSETS": "true"}

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name) / "creative"
        self.header = self.dir / "header" / "header.png"
        self.search = self.dir / "search-results" / "search.png"
        for path, raw in ((self.header, creative_png(3840, 1646)), (self.search, creative_png(1920, 1280))):
            path.parent.mkdir(parents=True)
            path.write_bytes(raw)
        sleep = mock.patch.object(asc_release.time, "sleep")
        sleep.start()
        self.addCleanup(sleep.stop)

    def image(self, image_id: str, path: Path, state: str, created: str = "2026-10-20") -> dict:
        return {"id": image_id, "attributes": {"fileName": path.name, "fileSize": path.stat().st_size,
                                               "state": state, "createdDate": created,
                                               "imageAsset": {"width": 1, "height": 1}}}

    def run_main(self, fake: FakeAsc, env: dict[str, str] | None = None, *extra: str,
                 creative_dir: Path | None = None) -> int:
        code, _, _ = run_asc(fake, self.ENV if env is None else env,
                             "--creative-dir", str(creative_dir or self.dir), *extra)
        return code

    def submit(self, fake: FakeAsc) -> int:
        return self.run_main(fake, {**self.ENV, "SUBMIT_IOS_REVIEW": "true"}, *LiveSubmitFlowTests.BUILD)

    def image_review_drafts(self, fake: CreativeFake) -> set[str]:
        return {draft for draft, items in fake.items.items() if any(related(i, "appAssetLibraryImage") for i in items)}

    def test_new_images_are_uploaded_once_and_reviewed_on_their_own(self) -> None:
        fake = CreativeFake()
        self.assertEqual(self.run_main(fake), 0)
        posts = [c for c in writes(fake) if c == ("POST", "/appAssetLibraryImages")]
        self.assertEqual(len(posts), 2)
        self.assertEqual([c[0] for c in writes(fake) if c[1].startswith(("https://upload", "/appAssetLibraryImages/"))],
                         ["UPLOAD", "PATCH", "UPLOAD", "PATCH"])
        uploaded = {image["attributes"]["fileName"]: image["attributes"] for image in fake.library}
        self.assertEqual(set(uploaded), {"header.png", "search.png"})
        self.assertEqual({a["category"] for a in uploaded.values()}, {"CREATIVE_ASSETS"})
        self.assertEqual(uploaded["header.png"]["fileSize"], self.header.stat().st_size)
        # One submission of their own, holding exactly the two images, submitted.
        self.assertEqual(len(fake.submitted), 1)
        held = [related(item, "appAssetLibraryImage") for item in fake.items[fake.submitted[0]]]
        self.assertEqual(sorted(held), sorted(image["id"] for image in fake.library))
        self.assertEqual(fake.placed, [], "images are placed only once Apple approves them")
        # The next run finds them in review: nothing is uploaded, reviewed or placed again.
        before = len(writes(fake))
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(len(writes(fake)), before)

    def test_slots_holding_only_a_readme_fail_before_any_call(self) -> None:
        # Laid out as store/creative/ios is committed while it has no image: each slot
        # folder holds only its README. With the switch on there is nothing to upload,
        # so a run with the default --creative-dir fails before any call. The folders
        # themselves are a repository asset, checked by test_store_assets.py, so adding
        # an image never fails these release tests.
        for path in (self.header, self.search):
            path.unlink()
            (path.parent / "README.md").write_text("At most one image here.\n")
        fake = CreativeFake()
        with mock.patch.object(asc_release, "DEFAULT_CREATIVE_DIR", self.dir):
            code, _, output = run_asc(fake, self.ENV)
        self.assertEqual(code, 1)
        self.assertIn("has no header or search results image", output)
        self.assertEqual(fake.calls, [])

    def test_approved_images_are_placed_on_the_version(self) -> None:
        fake = CreativeFake(library=[self.image("H", self.header, "APPROVED"), self.image("S", self.search, "APPROVED")],
                            # Placed on another version's localization only.
                            placements={"PL-OLD": {"type": "PRODUCT_PAGE_HEADER_ASSET", "image": "H", "loc": "LOC-21"}})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.placed, [
            {"data": {"type": "appAssetLibraryPlacements",
                      "attributes": {"placementType": placement, "placementGroup": "DEFAULT_PROFILE"},
                      "relationships": {"image": {"data": {"type": "appAssetLibraryImages", "id": image_id}},
                                        "appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations",
                                                                                 "id": "LOC"}}}}}
            for placement, image_id in (("PRODUCT_PAGE_HEADER_ASSET", "H"), ("APP_STORE_SEARCH_RESULTS_ASSET", "S"))
        ])
        self.assertNotIn(("POST", "/appAssetLibraryImages"), fake.calls)
        self.assertEqual(fake.submitted, [])
        # A second run finds both placed and writes nothing.
        before = len(writes(fake))
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(len(writes(fake)), before)
        self.assertIn(("GET", "/appAssetLibraryImages/H/relationships/placements?limit=50"), fake.calls)

    def test_another_image_in_the_slot_is_swapped_out_in_one_request(self) -> None:
        fake = CreativeFake(library=[self.image("H", self.header, "APPROVED"), self.image("S", self.search, "IN_REVIEW")],
                            placements={"PL-OLD": {"type": "PRODUCT_PAGE_HEADER_ASSET", "image": "OLDIMG", "loc": "LOC"}})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(len(fake.placed), 1)
        rels = fake.placed[0]["data"]["relationships"]
        self.assertEqual(rels["placementToSwapOut"], {"data": {"type": "appAssetLibraryPlacements", "id": "PL-OLD"}})
        self.assertEqual(rels["image"]["data"]["id"], "H")
        self.assertNotIn("PL-OLD", fake.placements)

    def test_an_interrupted_upload_is_uploaded_again(self) -> None:
        stale = self.image("STALE", self.header, "AWAITING_UPLOAD", created="2026-10-21")
        fake = CreativeFake(library=[stale, self.image("S", self.search, "APPROVED")])
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual([c for c in writes(fake) if c == ("POST", "/appAssetLibraryImages")],
                         [("POST", "/appAssetLibraryImages")])
        reviewed = {related(item, "appAssetLibraryImage") for item in fake.items[fake.submitted[0]]}
        self.assertNotIn("STALE", reviewed)
        self.assertEqual(len(reviewed), 1)
        # Failed and archived images do not count either.
        for state in ("FAILED", "ARCHIVED"):
            fake = CreativeFake(library=[self.image("OLD", self.header, state), self.image("S", self.search, "APPROVED")])
            with self.subTest(state=state):
                self.assertEqual(self.run_main(fake), 0)
                self.assertIn(("POST", "/appAssetLibraryImages"), fake.calls)

    def test_an_uploaded_image_still_processing_is_reviewed_once_ready(self) -> None:
        fake = CreativeFake(library=[self.image("H", self.header, "UPLOAD_COMPLETE"), self.image("S", self.search, "APPROVED")],
                            processing={"H": ["UPLOAD_COMPLETE", "COMPLETE", "PREPARE_FOR_SUBMISSION"]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertNotIn(("POST", "/appAssetLibraryImages"), fake.calls)
        self.assertEqual(fake.processing["H"], [], "the run waits until Apple has processed the image")
        self.assertEqual([related(i, "appAssetLibraryImage") for i in fake.items[fake.submitted[0]]], ["H"])

    def test_processing_failure_or_timeout_stops_before_review(self) -> None:
        fake = CreativeFake(library=[self.image("H", self.header, "UPLOAD_COMPLETE")], processing={"H": ["FAILED"]})
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])
        self.assertNotIn(("POST", "/reviewSubmissionItems"), fake.calls)
        fake = CreativeFake(library=[self.image("H", self.header, "UPLOAD_COMPLETE")],
                            processing={"H": ["UPLOAD_COMPLETE"] * 5})
        with mock.patch.object(asc_release, "CREATIVE_PROCESSING_TIMEOUT_SECONDS", 0):
            self.assertEqual(self.run_main(fake), 1)
        self.assertNotIn(("POST", "/reviewSubmissionItems"), fake.calls)

    def test_images_in_review_are_neither_uploaded_nor_placed(self) -> None:
        for state in ("WAITING_FOR_REVIEW", "IN_REVIEW", "ACCEPTED"):
            fake = CreativeFake(library=[self.image("H", self.header, state), self.image("S", self.search, state)])
            with self.subTest(state=state):
                self.assertEqual(self.run_main(fake), 0)
                self.assertEqual(writes(fake), [])

    def test_a_rejected_image_stops_the_run_before_any_write(self) -> None:
        rejected = self.image("H", self.header, "REJECTED")
        rejected["attributes"]["stateDetails"] = [{"code": "IMAGE_QUALITY"}]
        fake = CreativeFake(library=[rejected, self.image("S", self.search, "PREPARE_FOR_SUBMISSION")])
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(writes(fake), [])

    def test_a_locked_version_takes_no_new_images(self) -> None:
        for state, attached in (("WAITING_FOR_REVIEW", "B9"), ("READY_FOR_REVIEW", "B9")):
            for library in ([], [self.image("H", self.header, "PREPARE_FOR_SUBMISSION")]):
                fake = CreativeFake(state=state, attached=attached, library=library)
                with self.subTest(state=state, library=bool(library)):
                    self.assertEqual(self.run_main(fake), 1)
                    self.assertEqual(writes(fake), [])

    def test_an_approved_image_on_a_locked_version_waits_without_failing(self) -> None:
        # Approval can land between a submit and a re-run of the store job.
        fake = CreativeFake(state="WAITING_FOR_REVIEW", attached="B9",
                            library=[self.image("H", self.header, "APPROVED"), self.image("S", self.search, "IN_REVIEW")])
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(writes(fake), [])

    def test_images_never_go_into_the_versions_draft(self) -> None:
        # The version's draft is picked (and reserved) before any write; the
        # empty draft goes to the version and the images get their own submission.
        fake = CreativeFake(drafts=["EMPTY"], draft_items={"EMPTY": []})
        self.assertEqual(self.submit(fake), 0)
        self.assertEqual(len(fake.submitted), 2)
        self.assertEqual(fake.submitted[-1], "EMPTY", "the version went out in the reserved draft")
        self.assertEqual([related(i, "appStoreVersion") for i in fake.items["EMPTY"]], ["V1"])
        self.assertEqual(self.image_review_drafts(fake), {fake.submitted[0]})
        self.assertNotIn("EMPTY", self.image_review_drafts(fake))

    def test_a_creative_draft_is_never_used_for_the_version(self) -> None:
        library = [self.image("H", self.header, "WAITING_FOR_REVIEW"), self.image("S", self.search, "APPROVED")]
        placements = {"P": {"type": "APP_STORE_SEARCH_RESULTS_ASSET", "image": "S", "loc": "LOC"}}
        # A draft holding an image (left by hand or another tool) is not the version's.
        fake = CreativeFake(library=library, placements=placements, drafts=["IMAGES"],
                            draft_items={"IMAGES": [image_item("OTHER")]})
        self.assertEqual(self.submit(fake), 0)
        self.assertEqual(fake.items["IMAGES"], [image_item("OTHER")])
        self.assertEqual(len(fake.submitted), 1)
        self.assertNotEqual(fake.submitted[0], "IMAGES")
        self.assertIn(("POST", "/reviewSubmissions"), fake.calls)

    def test_a_ready_for_review_version_its_draft_does_not_hold_stops_before_any_write(self) -> None:
        # Images in review wait without failing, so only the draft check can stop the run.
        library = [self.image("H", self.header, "IN_REVIEW"), self.image("S", self.search, "IN_REVIEW")]
        # The only draft holding the version also holds an image, so it is not the version's draft.
        for drafts, items in (([], {}), (["MIXED"], {"MIXED": [version_item(), image_item("X")]})):
            fake = CreativeFake(state="READY_FOR_REVIEW", library=library, drafts=drafts, draft_items=items)
            with self.subTest(drafts=drafts):
                code, _, output = run_asc(fake, {**self.ENV, "SUBMIT_IOS_REVIEW": "true"},
                                          "--creative-dir", str(self.dir), *LiveSubmitFlowTests.BUILD)
                self.assertEqual(code, 1)
                self.assertIn("no draft review submission", output)
                self.assertEqual(writes(fake), [])

    def test_the_draft_holding_this_version_is_reserved_over_others(self) -> None:
        fake = CreativeFake(state="READY_FOR_REVIEW", drafts=["EMPTY", "MINE"],
                            draft_items={"EMPTY": [], "MINE": [version_item()]},
                            library=[self.image("H", self.header, "APPROVED"), self.image("S", self.search, "IN_REVIEW")],
                            placements={"P": {"type": "PRODUCT_PAGE_HEADER_ASSET", "image": "H", "loc": "LOC"}})
        self.assertEqual(self.submit(fake), 0)
        self.assertEqual(fake.submitted, ["MINE"])
        self.assertEqual(fake.items["EMPTY"], [])

    def test_a_rerun_finishes_the_draft_an_interrupted_review_left(self) -> None:
        library = [self.image("H", self.header, "READY_FOR_REVIEW"), self.image("S", self.search, "PREPARE_FOR_SUBMISSION")]
        # A draft already holding some of the images is finished before an empty one.
        fake = CreativeFake(library=library, drafts=["EMPTY", "PART"],
                            draft_items={"EMPTY": [], "PART": [image_item("H")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertNotIn(("POST", "/reviewSubmissions"), fake.calls)
        self.assertEqual(fake.submitted, ["PART"])
        self.assertEqual(sorted(related(i, "appAssetLibraryImage") for i in fake.items["PART"]), ["H", "S"])
        self.assertEqual(fake.items["EMPTY"], [])
        # Else an empty draft (a run stopped before it added the images) is used.
        fake = CreativeFake(library=[self.image("S", self.search, "PREPARE_FOR_SUBMISSION")],
                            drafts=["EMPTY"], draft_items={"EMPTY": []})
        self.assertEqual(self.run_main(fake), 0)
        self.assertNotIn(("POST", "/reviewSubmissions"), fake.calls)
        self.assertEqual(fake.submitted, ["EMPTY"])
        # A draft holding anything else is left alone and a new one is created.
        fake = CreativeFake(library=[self.image("S", self.search, "PREPARE_FOR_SUBMISSION")],
                            drafts=["OTHER"], draft_items={"OTHER": [version_item("V0")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertIn(("POST", "/reviewSubmissions"), fake.calls)
        self.assertEqual(fake.items["OTHER"], [version_item("V0")])
        self.assertNotIn("OTHER", fake.submitted)

    def test_a_creative_submission_holding_other_items_is_not_submitted(self) -> None:
        class Crowded(CreativeFake):
            def post(self, path: str, body: dict) -> dict:
                result = super().post(path, body)
                if path == "/reviewSubmissions":
                    # Something else lands in the new draft before it is submitted.
                    self.items[result["data"]["id"]].append(version_item("V0"))
                return result

        fake = Crowded(library=[self.image("S", self.search, "PREPARE_FOR_SUBMISSION")])
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])

    def test_images_apple_would_refuse_fail_before_any_call(self) -> None:
        cases = [
            (self.header, creative_png(3840, 2160), "header.png"),
            (self.header, creative_png(3840, 1646, 6), "header.png"),
            (self.header, jpeg(3840, 1646), "header.jpg"),
            (self.header, jpeg(3840, 1646), "header.png"),
            (self.search, creative_png(3000, 2001), "search.png"),
            (self.search, creative_png(1800, 1200), "search.png"),
            (self.search, creative_png(3900, 2600), "search.png"),
            (self.search, creative_png(1920, 1280, 4), "search.png"),
            (self.search, make_png(1920, 1280, 2, chunks=((b"tRNS", b"\x00" * 6),)), "search.png"),
            (self.search, jpeg(4000, 2666), "search.jpg"),
            (self.search, b"GIF89a", "search.png"),
            # Truncated or corrupt files with the right header fail too.
            (self.header, creative_png(3840, 1646)[:-12], "header.png"),
            (self.header, creative_png(3840, 1646)[:8 + 25], "header.png"),
            (self.search, creative_png(1920, 1280)[: len(creative_png(1920, 1280)) // 2], "search.png"),
        ]
        for path, raw, name in cases:
            original = path.read_bytes()
            path.unlink()
            (path.parent / name).write_bytes(raw)
            fake = CreativeFake()
            with self.subTest(slot=path.parent.name, name=name, raw=raw[:26]):
                self.assertEqual(self.run_main(fake), 1)
                self.assertEqual(fake.calls, [])
            (path.parent / name).unlink()
            path.write_bytes(original)

    def test_sizes_and_formats_apple_takes_pass(self) -> None:
        for name, raw in (("search.jpg", jpeg(1920, 1280)), ("search.jpeg", jpeg(3840, 2560)),
                          ("search.png", creative_png(3840, 2560)), ("search.png", creative_png(3000, 2000))):
            self.search.unlink()
            (self.search.parent / name).write_bytes(raw)
            with self.subTest(name=name, raw=raw[:26]):
                sources = quiet(asc_release.creative_sources, self.dir)
                self.assertEqual([(p, path.name) for p, _, path in sources],
                                 [("PRODUCT_PAGE_HEADER_ASSET", "header.png"), ("APP_STORE_SEARCH_RESULTS_ASSET", name)])
            (self.search.parent / name).unlink()
            self.search.write_bytes(creative_png(1920, 1280))

    def test_a_slot_takes_one_image_and_at_least_one_slot_needs_one(self) -> None:
        (self.header.parent / "header-2.png").write_bytes(creative_png(3840, 1646))
        fake = CreativeFake()
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.calls, [])
        (self.header.parent / "header-2.png").unlink()
        # Other files (notes, .DS_Store) do not count; an empty slot is left alone.
        (self.header.parent / "README.md").write_text("notes")
        self.header.unlink()
        self.assertEqual([p for p, _, _ in quiet(asc_release.creative_sources, self.dir)],
                         ["APP_STORE_SEARCH_RESULTS_ASSET"])
        self.search.unlink()
        fake = CreativeFake()
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.calls, [])

    def preflight(self, fake: FakeAsc, *, submit: bool = False) -> tuple[int, str]:
        env = {**self.ENV, **({"SUBMIT_IOS_REVIEW": "true"} if submit else {})}
        code, _, output = run_asc(fake, env, "--preflight", "--creative-dir", str(self.dir))
        return code, output

    def test_the_preflight_stops_before_the_build_when_the_version_cannot_take_the_images(self) -> None:
        # In a review submission the version takes no new image: found before the build, not after.
        for library in ([], [self.image("H", self.header, "PREPARE_FOR_SUBMISSION")]):
            fake = CreativeFake(state="READY_FOR_REVIEW", library=library,
                                drafts=["MINE"], draft_items={"MINE": [version_item()]})
            with self.subTest(library=bool(library)):
                self.assertEqual(self.preflight(fake)[0], 1)
            self.assertEqual(writes(fake), [])
        # A rejected image stops it in any state.
        rejected = [self.image("H", self.header, "REJECTED"), self.image("S", self.search, "APPROVED")]
        self.assertEqual(self.preflight(CreativeFake(library=rejected))[0], 1)

    def test_the_preflight_passes_what_the_store_step_can_do(self) -> None:
        cases = [
            # An editable version takes new images (uploaded by the store step, not here).
            ("PREPARE_FOR_SUBMISSION", []),
            # Images in review or approved wait for a later run without failing.
            ("READY_FOR_REVIEW", [self.image("H", self.header, "IN_REVIEW"), self.image("S", self.search, "APPROVED")]),
        ]
        for state, library in cases:
            fake = CreativeFake(state=state, library=library, drafts=["MINE"], draft_items={"MINE": [version_item()]})
            with self.subTest(state=state):
                code, output = self.preflight(fake, submit=True)
                self.assertEqual(code, 0, output)
                self.assertIn(f"version {VERSION} ({state}) is ready", output)
                self.assertEqual(writes(fake), [])

    def test_the_preflight_checks_a_ready_for_review_versions_draft(self) -> None:
        library = [self.image("H", self.header, "APPROVED"), self.image("S", self.search, "IN_REVIEW")]
        placements = {"P": {"type": "PRODUCT_PAGE_HEADER_ASSET", "image": "H", "loc": "LOC"}}
        for drafts, items in (([], {}), (["MIXED"], {"MIXED": [version_item(), image_item("X")]})):
            fake = CreativeFake(state="READY_FOR_REVIEW", library=library, placements=placements,
                                drafts=drafts, draft_items=items)
            with self.subTest(drafts=drafts):
                code, output = self.preflight(fake, submit=True)
                self.assertEqual(code, 1)
                self.assertIn("no draft review submission", output)
                self.assertEqual(writes(fake), [])
                # Without review submission the draft does not matter.
                code, output = self.preflight(fake, submit=False)
                self.assertEqual(code, 0, output)

    def test_dry_run_checks_the_images_without_calls(self) -> None:
        env = {k: v for k, v in os.environ.items() if not k.startswith(("UPLOAD_", "SUBMIT_", "PUBLIC_"))}
        argv = ["asc_release.py", "--dry-run", "--creative-dir", str(self.dir)]
        out = io.StringIO()
        with mock.patch.dict(os.environ, {**env, **self.ENV}, clear=True), mock.patch.object(sys, "argv", argv), \
                mock.patch.object(asc_release, "client_from_environment") as client, \
                redirect_stdout(out), redirect_stderr(out):
            asc_release.main()
        client.assert_not_called()
        self.assertIn("creative=True", out.getvalue())
        self.header.write_bytes(creative_png(3840, 1646, 6))
        with mock.patch.dict(os.environ, {**env, **self.ENV}, clear=True), mock.patch.object(sys, "argv", argv), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            asc_release.main()


class CatalogTests(unittest.TestCase):
    def test_repository_catalog_matches_storekit_and_android_billing(self) -> None:
        with redirect_stdout(io.StringIO()):
            validate_catalog.main(["--platform", "all"])

    def test_mismatches_fail(self) -> None:
        catalog = validate_catalog.load_catalog()
        ids = set(validate_catalog.catalog_product_ids(catalog))
        self.assertEqual(validate_catalog.check(catalog, platform="ios", app_ids=ids), len(ids))
        with self.assertRaisesRegex(validate_catalog.CatalogError, "missing from the catalog"):
            validate_catalog.check(catalog, platform="ios", app_ids=ids | {"com.apoorvdarshan.verceltics.new"})
        with self.assertRaisesRegex(validate_catalog.CatalogError, "not in Products.storekit"):
            validate_catalog.check(catalog, platform="ios", app_ids=ids - {"com.apoorvdarshan.verceltics.lifetime"})
        duplicate = json.loads(json.dumps(catalog))
        duplicate["tips"].append({"id": "com.apoorvdarshan.verceltics.tip.coffee"})
        with self.assertRaisesRegex(validate_catalog.CatalogError, "duplicate"):
            validate_catalog.check(duplicate, platform="ios", app_ids=ids)
        foreign = json.loads(json.dumps(catalog))
        foreign["tips"].append({"id": "com.apoorvdarshan.calorietracker.tip.snack"})
        with self.assertRaisesRegex(validate_catalog.CatalogError, "another app"):
            validate_catalog.check(foreign, platform="ios", app_ids=ids | {"com.apoorvdarshan.calorietracker.tip.snack"})
        undeclared = json.loads(json.dumps(catalog))
        del undeclared["uses_non_exempt_encryption"]
        with self.assertRaisesRegex(validate_catalog.CatalogError, "uses_non_exempt_encryption"):
            validate_catalog.check(undeclared, platform="ios", app_ids=ids)

    def test_release_submits_only_catalog_products(self) -> None:
        self.assertEqual(asc_release.intended_product_ids(),
                         set(validate_catalog.catalog_product_ids(validate_catalog.load_catalog())))


class ReleaseNotesTests(unittest.TestCase):
    TEXT = "# Notes\n\n## ios-v2.1\n\nShipped.\n\n## android-v1.0\n\nBeta.\n"

    def test_sections_are_found_by_exact_tag(self) -> None:
        self.assertEqual(release_notes.section_for_tag("ios-v2.1", self.TEXT), "Shipped.")
        self.assertEqual(release_notes.notes_for_tag("android-v1.0", self.TEXT), "Beta.")
        self.assertIsNone(release_notes.section_for_tag("ios-v2.2", self.TEXT))
        with self.assertRaisesRegex(ValueError, "add exactly one"):
            release_notes.notes_for_tag("ios-v2.2", self.TEXT)

    def test_bad_tags_and_sections_fail(self) -> None:
        for tag in ("v2.1", "ios-2.1", "ios-v2.1.2.3", "ios-v2.1-beta", "macos-v2.1"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_notes.section_for_tag(tag, self.TEXT)
        with self.assertRaisesRegex(ValueError, "2 ## ios-v2.1"):
            release_notes.section_for_tag("ios-v2.1", self.TEXT + "## ios-v2.1\n\nAgain.\n")
        with self.assertRaisesRegex(ValueError, "empty"):
            release_notes.section_for_tag("ios-v2.3", self.TEXT + "## ios-v2.3\n\n")

    def run_cli(self, *args: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            try:
                release_notes.main(list(args))
            except SystemExit as exc:
                return (exc.code if isinstance(exc.code, int) else 1), out.getvalue(), err.getvalue()
        return 0, out.getvalue(), err.getvalue()

    def test_a_missing_notes_file_is_missing_notes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(release_notes, "ROOT", Path(tmp)):
            self.assertIsNone(release_notes.section_for_tag("ios-v2.2"))
            # --if-present (what the workflows run) prints nothing and succeeds.
            code, out, err = self.run_cli("--if-present", "ios-v2.2")
            self.assertEqual((code, out), (0, ""))
            self.assertIn("no ## ios-v2.2 section", err)
            code, out, err = self.run_cli("ios-v2.2")
            self.assertEqual((code, out), (2, ""))
            self.assertIn("add exactly one", err)

    def test_cli_prints_the_section_and_rejects_duplicates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(release_notes, "ROOT", Path(tmp)):
            (Path(tmp) / "RELEASE_NOTES.md").write_text(self.TEXT)
            self.assertEqual(self.run_cli("--if-present", "ios-v2.1")[:2], (0, "Shipped.\n"))
            (Path(tmp) / "RELEASE_NOTES.md").write_text(self.TEXT + "## ios-v2.1\n\nAgain.\n")
            code, _, err = self.run_cli("--if-present", "ios-v2.1")
            self.assertEqual(code, 2)
            self.assertIn("keep exactly one", err)


if __name__ == "__main__":
    unittest.main()
