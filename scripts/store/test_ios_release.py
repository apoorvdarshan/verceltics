#!/usr/bin/env python3
"""Offline tests for the iOS release: switches, build numbering, waiting for and
selecting this run's build, listing/What's New/screenshots/creative assets,
catalog, product creation and review, and the asc runner.

No network: every asc call goes to an in-memory fake of App Store Connect.
Run: python3 -m unittest scripts/store/test_ios_release.py
"""
from __future__ import annotations

import argparse
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
import zlib
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import asc_cli  # noqa: E402
import asc_products  # noqa: E402
import asc_release  # noqa: E402
import export_ios_listing  # noqa: E402
import ios_metadata  # noqa: E402
import ios_release  # noqa: E402
import print_gates  # noqa: E402
import release_notes  # noqa: E402
import validate_catalog  # noqa: E402
from ios_release import ReleaseError, resolve_gates  # noqa: E402

TAG = "ios-v2.2"
VERSION = "2.2"
ALL_ON = {
    "IOS_RELEASE_AUTOMATION": "true",
    "IOS_CREATE_VERSION": "true",
    "IOS_CREATE_PRODUCTS": "true",
    "IOS_UPLOAD_BUILD": "true",
    "IOS_UPLOAD_LISTING": "true",
    "IOS_UPLOAD_WHATS_NEW": "true",
    "IOS_UPLOAD_SCREENSHOTS": "true",
    "IOS_UPLOAD_CREATIVE_ASSETS": "true",
    "IOS_SUBMIT_REVIEW": "true",
    "IOS_PUBLIC_RELEASE": "true",
}
OPS = ("create_version", "create_products", "upload_build", "listing", "whats_new", "screenshots", "creative", "submit",
       "public_release")


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

    def test_creative_assets_are_their_own_switch(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_CREATIVE_ASSETS": "true"})
        self.assertTrue(result["creative"])
        self.assertTrue(result["asc"])
        self.assertFalse(result["screenshots"])
        self.assertFalse(result["build"])
        self.assertFalse(gates({"IOS_UPLOAD_CREATIVE_ASSETS": "true"})["creative"], "the master switch is off")
        with self.assertRaisesRegex(ReleaseError, "IOS_UPLOAD_CREATIVE_ASSETS"):
            gates({"IOS_UPLOAD_CREATIVE_ASSETS": "sometimes"})

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


APP = asc_release.ASC_APP_ID
BUNDLE = asc_release.DEFAULT_BUNDLE_ID
STARTED = 1_800_000_000.0  # this run's upload start (epoch seconds)
SWITCH_ENV = {
    "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "UPLOAD_CREATIVE_ASSETS", "SUBMIT_IOS_REVIEW",
    "PUBLIC_RELEASE", "CREATE_IOS_VERSION",
}


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
    """An `asc builds list` row; uploaded is epoch seconds, a raw uploadedDate string, or None (absent)."""
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


def png_header(color_type: int = 2, *, trns: bool = False, width: int = 16, height: int = 16) -> bytes:
    """A PNG signature and IHDR (plus an optional tRNS chunk) with the given colour type and size."""

    def chunk(kind: bytes, body: bytes) -> bytes:
        return len(body).to_bytes(4, "big") + kind + body + zlib.crc32(kind + body).to_bytes(4, "big")

    ihdr = width.to_bytes(4, "big") + height.to_bytes(4, "big") + bytes([8, color_type, 0, 0, 0])
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + (chunk(b"tRNS", b"\x00\x00") if trns else b"") + chunk(b"IDAT", b"x")


def jpeg_header(width: int, height: int) -> bytes:
    """A JPEG with an APP0 segment and a baseline SOF0 frame header of the given size."""
    app0 = b"\xff\xe0" + (16).to_bytes(2, "big") + b"JFIF\x00" + bytes(9)
    sof0 = b"\xff\xc0" + (17).to_bytes(2, "big") + b"\x08" + height.to_bytes(2, "big") + width.to_bytes(2, "big") + bytes(12)
    return b"\xff\xd8" + app0 + sof0 + b"\xff\xd9"


def asc_error(code: int) -> asc_cli.AscError:
    return asc_cli.AscError(("builds", "list"), code, "failed")


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def flag(call: tuple[str, ...], name: str) -> str:
    return call[call.index(name) + 1]


def options(call: tuple[str, ...]) -> dict[str, str]:
    """The --flag value pairs of an asc call (a flag without a value maps to "")."""
    found: dict[str, str] = {}
    args = list(call)
    i = 0
    while i < len(args):
        if args[i].startswith("--"):
            if i + 1 < len(args) and not args[i + 1].startswith("--"):
                found[args[i]] = args[i + 1]
                i += 2
                continue
            found[args[i]] = ""
        i += 1
    return found


def quiet(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*args, **kwargs)


class FakeClock:
    def __init__(self, start: float) -> None:
        self.time = start
        self.sleeps: list[float] = []

    def now(self) -> float:
        return self.time

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.time += seconds


class SequenceAsc:
    """Returns queued `asc builds list` payloads (or raises queued exceptions) per call."""

    def __init__(self, responses: list) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[str, ...]] = []

    def run(self, *args: str, **_: object) -> dict:
        self.calls.append(args)
        item = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(item, BaseException):
            raise item
        return item


def wait(client: SequenceAsc, clock: FakeClock, *, minutes: int = 120, transient=None) -> dict:
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
        client = SequenceAsc(
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
        query = client.calls[0]
        self.assertEqual(query[:2], ("builds", "list"))
        self.assertEqual(flag(query, "--app"), APP)
        self.assertEqual(flag(query, "--build-number"), "40")
        self.assertEqual(flag(query, "--include"), "preReleaseVersion,app")
        self.assertEqual(flag(query, "--processing-state"), "all")

    def test_times_out_without_using_another_build(self) -> None:
        client = SequenceAsc([{"data": [build_row(state="PROCESSING")], "included": [pre_row()]}])
        clock = FakeClock(STARTED)
        with self.assertRaisesRegex(ReleaseError, "timed out"):
            wait(client, clock, minutes=10)
        self.assertLessEqual(clock.time, STARTED + 10 * 60 + 30)

    def test_ready_build_after_the_deadline_is_still_a_timeout(self) -> None:
        client = SequenceAsc([{"data": [build_row()], "included": [pre_row()]}])
        clock = FakeClock(STARTED + 121 * 60)  # job started after the 120-minute budget
        with self.assertRaisesRegex(ReleaseError, "timed out"):
            wait(client, clock)
        self.assertEqual(client.calls, [], "must not poll once the budget is spent")

    def test_processing_failure_stops(self) -> None:
        for state in ("FAILED", "INVALID"):
            client = SequenceAsc([{"data": [build_row(state=state)], "included": [pre_row()]}])
            with self.subTest(state=state), self.assertRaisesRegex(ReleaseError, state):
                wait(client, FakeClock(STARTED))

    def test_expired_build_is_rejected(self) -> None:
        client = SequenceAsc([{"data": [build_row(expired=True)], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "expired"):
            wait(client, FakeClock(STARTED))

    def test_ambiguous_match_stops(self) -> None:
        client = SequenceAsc([{"data": [build_row("B1"), build_row("B2")], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "ambiguous"):
            wait(client, FakeClock(STARTED))

    def test_older_upload_is_never_substituted(self) -> None:
        old = build_row(uploaded=STARTED - 3600)
        client = SequenceAsc([{"data": [old], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "older build"):
            wait(client, FakeClock(STARTED))

    def test_build_without_an_upload_time_is_never_used(self) -> None:
        # While processing, App Store Connect may not report the time yet: keep waiting.
        client = SequenceAsc([
            {"data": [build_row(state="PROCESSING", uploaded=None)], "included": [pre_row()]},
            {"data": [build_row(uploaded=None)], "included": [pre_row()]},
        ])
        clock = FakeClock(STARTED)
        with self.assertRaisesRegex(ReleaseError, "no uploadedDate"):
            wait(client, clock)
        self.assertEqual(clock.sleeps, [30], "the processing poll waits; the VALID one fails closed")
        for raw in ("", "yesterday", "2026-13-45T99:00:00Z"):
            client = SequenceAsc([{"data": [build_row(uploaded=raw)], "included": [pre_row()]}])
            with self.subTest(raw=raw), self.assertRaisesRegex(ReleaseError, "uploadedDate"):
                wait(client, FakeClock(STARTED))

    def test_other_versions_and_platforms_are_ignored(self) -> None:
        other_version = build_row("BX", pre="P2")
        other_platform = build_row("BY", pre="P3")
        client = SequenceAsc(
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
        client = SequenceAsc([{"data": [build_row(app="OTHER")], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "another app"):
            wait(client, FakeClock(STARTED))

    def test_transient_errors_are_retried_and_others_raise(self) -> None:
        client = SequenceAsc([asc_error(63), {"data": [build_row()], "included": [pre_row()]}])
        with redirect_stderr(io.StringIO()):
            build = wait(client, FakeClock(STARTED), transient=ios_release.is_transient_error)
        self.assertEqual(build["id"], "B1")
        fatal = SequenceAsc([asc_error(3)])
        with self.assertRaises(asc_cli.AscError):
            wait(fatal, FakeClock(STARTED), transient=ios_release.is_transient_error)


class TransientErrorTests(unittest.TestCase):
    def test_retry_rule_follows_asc_exit_codes(self) -> None:
        # 408, 429, any 5xx and a call that ran out of time are retried.
        for code in (18, 39, 60, 62, 63, 99, 124):
            with self.subTest(code=code):
                self.assertTrue(ios_release.is_transient_error(asc_error(code)))
        # Generic, usage, auth, not found, conflict, read-only refusal, other 4xx,
        # an unverified binary and a missing one.
        for code in (1, 2, 3, 4, 5, 6, 10, 32, 126, 127):
            with self.subTest(code=code):
                self.assertFalse(ios_release.is_transient_error(asc_error(code)))
        # asc retries network errors itself; anything that is not an asc failure stops.
        for exc in (ValueError("bad"), ReleaseError("mismatch"), SystemExit(1), ConnectionResetError("reset")):
            with self.subTest(exc=repr(exc)):
                self.assertFalse(ios_release.is_transient_error(exc))

    def test_network_drops_reported_as_exit_1_are_retried(self) -> None:
        for detail in ('Get "https://api.appstoreconnect.apple.com/v1/builds": read tcp: connection reset by peer',
                       "dial tcp: lookup api.appstoreconnect.apple.com: no such host",
                       "net/http: TLS handshake timeout", "unexpected EOF",
                       'Get "https://api.appstoreconnect.apple.com/v1/builds": EOF'):
            with self.subTest(detail=detail):
                self.assertTrue(ios_release.is_transient_error(asc_cli.AscError(("builds", "list"), 1, detail)))
        for detail in ("invalid character '<' looking for beginning of value", "build not found"):
            with self.subTest(detail=detail):
                self.assertFalse(ios_release.is_transient_error(asc_cli.AscError(("builds", "list"), 1, detail)))
        # Only exit 1 carries a network drop; other codes keep their own rule.
        self.assertFalse(ios_release.is_transient_error(asc_cli.AscError(("builds", "list"), 3, "connection reset")))


class AscRunnerTests(unittest.TestCase):
    ENV = {
        "APP_STORE_CONNECT_API_KEY_ID": "TESTKEY0001",
        "APP_STORE_CONNECT_ISSUER_ID": "00000000-test-4000-8000-issuer000001",
        "APP_STORE_CONNECT_API_KEY_P8": "-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----",
        "ASC_PROFILE": "personal",
        "ASC_CONFIG_PATH": "/home/me/.asc/config.json",
    }

    def run_once(self, *, returncode: int = 0, stdout: str = '{"data": []}', stderr: str = "", read_only=False, env=None):
        seen: dict = {}

        def runner(argv, **kwargs):
            seen["argv"], seen["env"] = argv, kwargs["env"]
            return mock.Mock(returncode=returncode, stdout=stdout, stderr=stderr)

        with mock.patch.dict(os.environ, env or self.ENV, clear=True):
            result = asc_cli.Asc(binary="asc", read_only=read_only, runner=runner).run("apps", "view", "--id", APP)
        return result, seen

    def test_credentials_come_from_the_release_secrets_and_nothing_else(self) -> None:
        result, seen = self.run_once()
        self.assertEqual(result, {"data": []})
        self.assertEqual(seen["argv"], ["asc", "apps", "view", "--id", APP, "--output", "json"])
        env = seen["env"]
        self.assertEqual(env["ASC_KEY_ID"], "TESTKEY0001")
        self.assertEqual(env["ASC_ISSUER_ID"], self.ENV["APP_STORE_CONNECT_ISSUER_ID"])
        self.assertIn("\nabc\n", env["ASC_PRIVATE_KEY"])
        for name in ("ASC_PROFILE", "ASC_CONFIG_PATH"):
            self.assertNotIn(name, env)
        for name, value in (("ASC_BYPASS_KEYCHAIN", "1"), ("ASC_TELEMETRY_DISABLED", "1"), ("DO_NOT_TRACK", "1"),
                            ("ASC_SKILLS_AUTO_CHECK", "0"), ("ASC_STRICT_AUTH", "1"), ("ASC_MAX_RETRIES", "5")):
            self.assertEqual(env[name], value, name)
        self.assertNotIn("ASC_READ_ONLY", env)

    def test_read_only_from_the_caller_or_the_job(self) -> None:
        _, seen = self.run_once(read_only=True)
        self.assertEqual(seen["env"]["ASC_READ_ONLY"], "1")
        _, seen = self.run_once(env={**self.ENV, "ASC_READ_ONLY": "1"})
        self.assertEqual(seen["env"]["ASC_READ_ONLY"], "1")

    def test_failures_keep_the_exit_code_and_hide_the_ids(self) -> None:
        stderr = f"401 for key {self.ENV['APP_STORE_CONNECT_API_KEY_ID']} issuer {self.ENV['APP_STORE_CONNECT_ISSUER_ID']}"
        with self.assertRaises(asc_cli.AscError) as caught:
            self.run_once(returncode=3, stderr=stderr)
        self.assertEqual(caught.exception.exit_code, 3)
        self.assertNotIn("TESTKEY0001", str(caught.exception))
        self.assertNotIn("issuer000001", str(caught.exception))
        with self.assertRaises(asc_cli.AscError):
            self.run_once(stdout="not json")

    def test_install_accepts_only_the_pinned_binary(self) -> None:
        good = b"pinned-binary"
        with mock.patch.dict(asc_cli.ASC_SHA256, {"linux_amd64": hashlib.sha256(good).hexdigest()}):
            with tempfile.TemporaryDirectory() as tmp:
                opener = lambda url, timeout: io.BytesIO(good)  # noqa: E731
                target = quiet(asc_cli.install, Path(tmp), key="linux_amd64", opener=opener)
                self.assertEqual(target.read_bytes(), good)
                self.assertTrue(os.access(target, os.X_OK))
                bad = lambda url, timeout: io.BytesIO(b"tampered")  # noqa: E731
                with self.assertRaises(SystemExit):
                    quiet(asc_cli.install, Path(tmp) / "other", key="linux_amd64", opener=bad)
                self.assertFalse((Path(tmp) / "other" / "asc").exists())

    def test_the_pin_is_one_exact_release_with_both_ci_platforms(self) -> None:
        self.assertRegex(asc_cli.ASC_VERSION, r"^[0-9]+\.[0-9]+\.[0-9]+$")
        self.assertEqual(set(asc_cli.ASC_SHA256), {"linux_amd64", "macOS_arm64"})
        for digest in asc_cli.ASC_SHA256.values():
            self.assertRegex(digest, r"^[0-9a-f]{64}$")
        self.assertEqual(asc_cli.platform_key("Linux", "x86_64"), "linux_amd64")
        self.assertEqual(asc_cli.platform_key("Darwin", "arm64"), "macOS_arm64")
        with self.assertRaises(SystemExit):
            asc_cli.platform_key("Windows", "x86_64")

    def test_only_the_pinned_binary_receives_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            binary = Path(tmp) / "asc"
            binary.write_bytes(b"pinned-binary")
            pinned = {asc_cli.platform_key(): hashlib.sha256(b"pinned-binary").hexdigest()}
            with mock.patch.dict(asc_cli.ASC_SHA256, pinned), mock.patch.object(asc_cli.shutil, "which", return_value=str(binary)):
                self.assertEqual(asc_cli.verified_binary("asc"), str(binary))
                binary.write_bytes(b"something else")
                with self.assertRaises(asc_cli.AscError) as caught:
                    asc_cli.verified_binary("asc")
                self.assertEqual(caught.exception.exit_code, 126)
                # A real run checks the binary before anything is executed.
                with mock.patch.object(asc_cli.subprocess, "run") as run, self.assertRaises(asc_cli.AscError):
                    asc_cli.Asc().run("apps", "view", "--id", APP)
                run.assert_not_called()
            with mock.patch.object(asc_cli.shutil, "which", return_value=None), self.assertRaises(asc_cli.AscError):
                asc_cli.verified_binary("asc")
        # No environment variable can point the release at another executable.
        with mock.patch.dict(os.environ, {"ASC_BIN": "/tmp/evil"}):
            self.assertEqual(asc_cli.Asc().binary, "asc")

    def test_alpha_is_detected_the_way_asc_refuses_it(self) -> None:
        cases = {"rgb": (png_header(2), False), "rgba": (png_header(6), True), "gray-alpha": (png_header(4), True),
                 "rgb-trns": (png_header(2, trns=True), True), "jpeg": (b"\xff\xd8\xff\xe0rest", False)}
        with tempfile.TemporaryDirectory() as tmp:
            for name, (content, expected) in cases.items():
                path = Path(tmp) / name
                path.write_bytes(content)
                with self.subTest(name=name):
                    self.assertEqual(asc_cli.image_has_alpha(path), expected)

    def test_a_leading_at_sign_is_escaped(self) -> None:
        self.assertEqual(asc_release.asc_value("@verceltics on X"), "@@verceltics on X")
        self.assertEqual(asc_release.asc_value("Hosting, domains"), "Hosting, domains")

    def test_the_release_needs_all_three_credentials(self) -> None:
        for missing in ("APP_STORE_CONNECT_API_KEY_ID", "APP_STORE_CONNECT_ISSUER_ID", "APP_STORE_CONNECT_API_KEY_P8"):
            env = {k: v for k, v in self.ENV.items() if k != missing}
            with self.subTest(missing=missing), mock.patch.dict(os.environ, env, clear=True), \
                    self.assertRaises(SystemExit):
                quiet(asc_release.asc_from_environment)
        with mock.patch.dict(os.environ, self.ENV, clear=True):
            self.assertTrue(asc_release.asc_from_environment(read_only=True).read_only)
            self.assertFalse(asc_release.asc_from_environment().read_only)


MUTATIONS = {
    ("versions", "create"), ("versions", "update"), ("versions", "attach-build"), ("builds", "update"),
    ("localizations", "update"), ("screenshots", "upload"), ("asset-library", "images", "upload"),
    ("localizations", "placements", "create"), ("localizations", "placements", "swap"),
    ("review", "submissions-create"), ("review", "items-add"), ("review", "submissions-submit"),
    # Product creation.
    ("subscriptions", "setup"), ("subscriptions", "update"), ("iap", "create"), ("iap", "versions", "create"),
    ("iap", "versions", "localizations", "create"), ("iap", "pricing", "schedules", "create"),
    ("iap", "pricing", "availability", "set"), ("iap", "review-screenshots", "create"),
    ("iap", "review-screenshots", "update"),
}
ITEM_RELATIONS = {
    "appStoreVersions": "appStoreVersion",
    "subscriptionVersions": "subscriptionVersion",
    "inAppPurchaseVersions": "inAppPurchaseVersion",
    "appAssetLibraryImages": "appAssetLibraryImage",
}


def is_write(call: tuple[str, ...]) -> bool:
    return any(call[:n] in MUTATIONS for n in (2, 3, 4, 5))


def app_info(info_id: str, state: str) -> dict:
    return {"id": info_id, "attributes": {"state": state}}


def item(relation: str, related: str, item_id: str | None = None) -> dict:
    """A review submission item pointing at one related record."""
    return {"id": item_id or f"ITEM-{related}", "relationships": {relation: {"data": {"id": related}}}}


def subscription(sub_id: str, product_id: str, state: str, name: str = "Plan") -> dict:
    return {"id": sub_id, "attributes": {"name": name, "productId": product_id, "state": state}}


def purchase(iap_id: str, product_id: str, state: str, name: str = "Purchase") -> dict:
    return {"id": iap_id, "attributes": {"name": name, "productId": product_id, "state": state}}


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


MONTHLY = "com.apoorvdarshan.verceltics.monthly"
WEEKLY = "com.apoorvdarshan.verceltics.weekly"  # not in the catalog
LIFETIME_ID = "com.apoorvdarshan.verceltics.lifetime"
COFFEE = "com.apoorvdarshan.verceltics.tip.coffee"
LIFETIME = purchase("IAP1", LIFETIME_ID, "READY_TO_SUBMIT", "Lifetime")


class FakeAsc:
    """A stateful App Store Connect behind `asc`, for asc_release (no network).

    Like the real asc, it refuses every write (exit 6) once it was handed out
    read-only (asc_from_environment(read_only=True)).
    """

    LIVE_TEXT = {field.attribute: LISTING[field.filename] for field in ios_metadata.VERSION_FIELDS}
    LIVE_APP_INFO = {"name": LISTING["name.txt"], "subtitle": LISTING["subtitle.txt"],
                     "privacyPolicyUrl": LISTING["privacy_url.txt"]}

    def __init__(
        self,
        *,
        state: str = "PREPARE_FOR_SUBMISSION",
        attached: str | None = "OLD",
        revert_after_select: bool = False,
        encryption: bool | None = None,
        version_exists: bool = True,
        other_versions: list[tuple[str, str]] | None = None,
        bundle: str | None = BUNDLE,
        localization: dict | None = None,
        app_info_text: dict | None = None,
        locales: tuple[str, ...] = ("en-US",),
        app_infos: list | None = None,
        screenshots: dict | None = None,
        upload_lands: bool = True,
        builds: list | None = None,
        build: dict | None = None,
        build_included: list | None = None,
        subscriptions: list | None = None,
        iaps: list | None = None,
        drafts: list[str] | None = None,
        draft_items: dict[str, list[dict]] | None = None,
        refuse_items: set[str] | None = None,
        library: list | None = None,
        placements: list | None = None,
        listed_after_create: bool = True,
    ) -> None:
        self.state = state
        self.attached = attached
        self.revert_after_select = revert_after_select
        self.encryption = encryption
        self.version_exists = version_exists
        # Other IOS versions of the app as (versionString, appStoreState).
        self.other_versions = [("2.1", "READY_FOR_SALE")] if other_versions is None else other_versions
        self.bundle = bundle
        self.text = dict(self.LIVE_TEXT if localization is None else localization)
        self.app_info_text = dict(self.LIVE_APP_INFO if app_info_text is None else app_info_text)
        self.locales = tuple(locales)
        self.app_infos = app_infos if app_infos is not None else [app_info("LIVE", "READY_FOR_DISTRIBUTION")]
        # display type -> checksums of the live screenshots, in order
        self.screenshots = {k: list(v) for k, v in (screenshots or {}).items()}
        self.upload_lands = upload_lands
        self.builds = builds if builds is not None else [build_row("B9")]
        self.build = build
        self.build_included = build_included
        self.subscriptions = subscriptions or []
        self.iaps = iaps or []
        self.drafts = list(drafts or [])
        self.items = {k: list(v) for k, v in (draft_items or {}).items()}
        self.refuse_items = refuse_items or set()
        self.library = list(library or [])
        self.placements = list(placements or [])
        self.listed_after_create = listed_after_create
        self.read_only = False
        self.modes: list[bool] = []  # read_only of every asc_from_environment() call
        self.calls: list[tuple[str, ...]] = []
        self.version_views = 0
        self.created_versions: list[str] = []
        self.uploaded: list[tuple[str, list[str]]] = []  # (device type, file names)
        self.uploaded_bytes: dict[str, list[bytes]] = {}  # device type -> the files' contents, in upload order
        self.release_types: list[str] = []
        self.submitted: list[str] = []

    def connect(self, *, read_only: bool = False) -> "FakeAsc":
        """Stands in for asc_release.asc_from_environment."""
        self.modes.append(read_only)
        self.read_only = read_only
        return self

    def writes(self) -> list[tuple[str, ...]]:
        return [c for c in self.calls if is_write(c)]

    def version_rows(self) -> list[dict]:
        rows = [{"id": f"V0-{i}", "attributes": {"versionString": number, "appStoreState": state,
                                                "createdDate": f"2026-{i + 1:02d}-01T00:00:00-07:00"}}
                for i, (number, state) in enumerate(self.other_versions)]
        if self.version_exists:
            rows.append({"id": "V1", "attributes": {"versionString": VERSION, "appStoreState": self.state,
                                                    "createdDate": "2026-12-01T00:00:00-08:00"}})
        return rows

    def run(self, *args: str, **_: object):
        self.calls.append(args)
        if self.read_only and is_write(args):
            raise asc_cli.AscError(args, 6, "read-only mode refused a mutating request")
        two, three = args[:2], args[:3]
        if two == ("apps", "view"):
            return {"data": {"type": "apps", "id": flag(args, "--id"), "attributes": {"bundleId": self.bundle}}}
        if three == ("apps", "info", "list"):
            return {"data": self.app_infos}
        if two == ("versions", "list"):
            return {"data": self.version_rows()}
        if two == ("versions", "create"):
            self.created_versions.append(flag(args, "--version"))
            if self.listed_after_create:
                self.version_exists = True
                self.state = "PREPARE_FOR_SUBMISSION"
            return {"data": {"type": "appStoreVersions", "id": "V1"}}
        if two == ("versions", "view"):
            self.version_views += 1
            build = "OLD" if self.revert_after_select and self.version_views >= 2 else self.attached
            relation = {"build": {"data": {"type": "builds", "id": build} if build else None}}
            return {"data": {"id": flag(args, "--version-id"), "relationships": relation}}
        if two == ("versions", "attach-build"):
            self.attached = flag(args, "--build-id")
            return {"data": {}}
        if two == ("versions", "update"):
            self.release_types.append(flag(args, "--release-type"))
            return {"data": {}}
        if two == ("builds", "list"):
            if "--build-number" not in args:
                return {"data": self.builds}
            build = json.loads(json.dumps(self.build or build_row("B9")))
            if self.encryption is not None:
                build["attributes"]["usesNonExemptEncryption"] = self.encryption
            included = self.build_included if self.build_included is not None else [pre_row()]
            return {"data": [build], "included": included}
        if two == ("builds", "update"):
            self.encryption = flag(args, "--uses-non-exempt-encryption") == "true"
            return {"data": {}}
        if three == ("localizations", "placements", "list"):
            wanted = flag(args, "--placement-type")
            return {"data": [p for p in self.placements if p["attributes"]["placementType"] == wanted]}
        if three in {("localizations", "placements", "create"), ("localizations", "placements", "swap")}:
            kind = flag(args, "--placement-type")
            self.placements = [p for p in self.placements if p["attributes"]["placementType"] != kind]
            self.placements.append({"id": "PL-new", "attributes": {"placementType": kind},
                                    "relationships": {"image": {"data": {"id": flag(args, "--image-id")}}}})
            return {"data": {}}
        if two == ("localizations", "list"):
            locales = [flag(args, "--locale")] if "--locale" in args else list(self.locales)
            found = [locale for locale in locales if locale in self.locales]
            if "--type" in args and flag(args, "--type") == "app-info":
                return {"data": [{"id": "INFOLOC" if locale == "en-US" else f"INFOLOC-{locale}",
                                  "attributes": {"locale": locale, **self.app_info_text}} for locale in found]}
            return {"data": [{"id": "LOC" if locale == "en-US" else f"LOC-{locale}",
                              "attributes": {"locale": locale, **self.text}} for locale in found]}
        if two == ("localizations", "update"):
            target = self.app_info_text if "--type" in args and flag(args, "--type") == "app-info" else self.text
            attributes = {value: key for key, value in asc_release.LOCALIZATION_FLAGS.items()}
            for name, value in options(args).items():
                if name in attributes:
                    target[attributes[name]] = value[1:] if value.startswith("@@") else value
            return {"data": {}}
        if two == ("screenshots", "list"):
            locale = flag(args, "--locale")
            if locale not in self.locales:
                raise asc_cli.AscError(args, 1, f'no App Store version localization found for locale "{locale}"')
            return {"versionLocalizationId": "LOC", "locale": locale, "sets": [
                {"set": {"attributes": {"screenshotDisplayType": display}},
                 "screenshots": [{"attributes": {"sourceFileChecksum": checksum}} for checksum in checksums]}
                for display, checksums in self.screenshots.items()
            ]}
        if two == ("screenshots", "upload"):
            # Like asc: every file in --path, sorted by name; PNGs with alpha are refused.
            files = sorted(p for p in Path(flag(args, "--path")).iterdir() if p.is_file())
            if any(asc_cli.image_has_alpha(p) for p in files):
                raise asc_cli.AscError(args, 1, "image has an alpha channel or transparency")
            self.uploaded.append((flag(args, "--device-type"), [p.name for p in files]))
            self.uploaded_bytes[flag(args, "--device-type")] = [p.read_bytes() for p in files]
            if self.upload_lands:
                self.screenshots["APP_" + flag(args, "--device-type")] = [md5(p.read_bytes()) for p in files]
            return {}
        if two == ("asset-library", "view"):
            return {"data": {"id": "LIB1"}}
        if three == ("asset-library", "images", "list"):
            return {"data": self.library}
        if three == ("asset-library", "images", "upload"):
            path = Path(flag(args, "--file"))
            image_id = f"IMG{len(self.library) + 1}"
            self.library.append({"id": image_id, "attributes": {"fileName": path.name, "fileSize": path.stat().st_size,
                                                                "state": "PREPARE_FOR_SUBMISSION", "createdDate": "z"}})
            return {"imageId": image_id, "ready": True, "state": "PREPARE_FOR_SUBMISSION"}
        if two == ("review", "submissions-list"):
            return {"data": [{"id": draft} for draft in self.drafts]}
        if two == ("review", "submissions-create"):
            new = f"SUB{len(self.drafts) + 1}"
            self.drafts.append(new)
            return {"data": {"id": new}}
        if two == ("review", "items-list"):
            return {"data": self.items.get(flag(args, "--submission"), [])}
        if two == ("review", "items-add"):
            item_type, item_id = flag(args, "--item-type"), flag(args, "--item-id")
            if item_id in self.refuse_items:
                raise asc_cli.AscError(args, 5, "conflict")
            relation = ITEM_RELATIONS[item_type]
            held = self.items.setdefault(flag(args, "--submission"), [])
            if any(asc_release.related_id(existing, relation) == item_id for existing in held):
                if "--if-exists" not in args or flag(args, "--if-exists") != "skip":
                    raise asc_cli.AscError(args, 5, "already on the submission")
                return {"data": {}}
            held.append(item(relation, item_id))
            return {"data": {}}
        if two == ("review", "submissions-submit"):
            self.submitted.append(flag(args, "--id"))
            return {"data": {}}
        if two == ("subscriptions", "list"):
            return {"data": self.subscriptions}
        if three == ("subscriptions", "versions", "list"):
            sub_id = flag(args, "--subscription-id")
            return {"data": [{"id": f"{sub_id}v2", "attributes": {"version": 2}},
                             {"id": f"{sub_id}v1", "attributes": {"version": 1}}]}
        if two == ("iap", "list"):
            return {"data": self.iaps}
        if three == ("iap", "versions", "list"):
            return {"data": [{"id": f"{flag(args, '--iap-id')}v1", "attributes": {"version": 1}}]}
        raise AssertionError(f"unexpected asc call {args}")


def run_asc(fake: FakeAsc, env: dict[str, str], *args: str, metadata: Path | None = None,
            creative: Path | None = None, version: str = VERSION) -> tuple[int, str]:
    """Run asc_release.main() against fake (the live path, no network)."""
    argv = ["asc_release.py", "--version", version, *args]
    if metadata is not None:
        argv += ["--metadata-dir", str(metadata)]
    if creative is not None:
        argv += ["--creative-dir", str(creative)]
    clean = {k: v for k, v in os.environ.items() if k not in SWITCH_ENV}
    out = io.StringIO()
    code = 0
    with mock.patch.dict(os.environ, {**clean, **env}, clear=True), mock.patch.object(sys, "argv", argv), \
            mock.patch.object(asc_release, "asc_from_environment", side_effect=fake.connect), \
            redirect_stdout(out), redirect_stderr(out):
        try:
            asc_release.main()
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 1
    return code, out.getvalue()


SUBMIT = {"SUBMIT_IOS_REVIEW": "true"}
BUILD_ARGS = ("--build-id", "B9", "--build-number", "40")


def heads(calls: list[tuple[str, ...]], head: tuple[str, ...]) -> list[int]:
    """Indexes of the calls that start with head."""
    return [i for i, call in enumerate(calls) if call[: len(head)] == head]


class BuildSelectionTests(unittest.TestCase):
    def test_selects_and_reads_back_this_runs_build(self) -> None:
        fake = FakeAsc()
        quiet(asc_release.select_build, fake, "V1", "B9", VERSION, "40")
        self.assertEqual(fake.attached, "B9")
        self.assertEqual([c[:2] for c in fake.calls],
                         [("builds", "list"), ("versions", "attach-build"), ("versions", "view")])
        verify = fake.calls[0]
        self.assertEqual((flag(verify, "--app"), flag(verify, "--build-number"), flag(verify, "--version")),
                         (APP, "40", VERSION))
        self.assertEqual((flag(fake.calls[1], "--version-id"), flag(fake.calls[1], "--build-id")), ("V1", "B9"))

    def test_selection_that_does_not_stick_fails(self) -> None:
        fake = FakeAsc(revert_after_select=True)
        quiet(asc_release.confirm_selected_build, fake, "V1", "OLD")
        with self.assertRaises(SystemExit):
            quiet(asc_release.select_build, fake, "V1", "B9", VERSION, "40")

    def test_unprocessed_expired_or_wrong_build_is_refused(self) -> None:
        cases = {
            "processing": (build_row("B9", state="PROCESSING"), [pre_row()]),
            "expired": (build_row("B9", expired=True), [pre_row()]),
            "number": (build_row("B9", number="41"), [pre_row()]),
            "version": (build_row("B9"), [pre_row(version="2.1")]),
            "missing": (build_row("B8"), [pre_row()]),
        }
        for label, (build, included) in cases.items():
            fake = FakeAsc(build=build, build_included=included, attached=None)
            with self.subTest(label), self.assertRaises(SystemExit):
                quiet(asc_release.select_build, fake, "V1", "B9", VERSION, "40")
            self.assertEqual(fake.writes(), [], label)

    def test_release_type(self) -> None:
        for public, expected in ((True, "AFTER_APPROVAL"), (False, "MANUAL")):
            fake = FakeAsc()
            quiet(asc_release.set_release_type, fake, "V1", public_release=public)
            self.assertEqual(fake.release_types, [expected])
            self.assertEqual(flag(fake.calls[-1], "--version-id"), "V1")


class FindAppTests(unittest.TestCase):
    def test_the_pinned_app_must_carry_exactly_the_bundle_id(self) -> None:
        self.assertEqual(asc_release.ASC_APP_ID, "6761645656")
        fake = FakeAsc()
        self.assertEqual(quiet(asc_release.find_app, fake, BUNDLE), APP)
        self.assertEqual(fake.calls, [("apps", "view", "--id", APP)])
        for bundle in ("com.example.other", BUNDLE + ".widget", None):
            with self.subTest(bundle=bundle), self.assertRaises(SystemExit):
                quiet(asc_release.find_app, FakeAsc(bundle=bundle), BUNDLE)

    def test_another_record_is_refused(self) -> None:
        class Other:
            def run(self, *args: str, **_: object):
                return {"data": {"id": "1234567890", "attributes": {"bundleId": BUNDLE}}}

        with self.assertRaises(SystemExit):
            quiet(asc_release.find_app, Other(), BUNDLE)

    def test_asc_failures_stop_with_a_readable_error(self) -> None:
        class Broken:
            def run(self, *args: str, **_: object):
                raise asc_cli.AscError(args, 3, "unauthorized")

        err = io.StringIO()
        with redirect_stderr(err), self.assertRaises(SystemExit):
            asc_release.find_app(Broken(), BUNDLE)
        self.assertIn("exit 3", err.getvalue())


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


class DryRunTests(unittest.TestCase):
    def run_cli(self, env: dict[str, str], *args: str) -> tuple[int, str]:
        out = io.StringIO()
        full_env = {k: v for k, v in os.environ.items() if k not in SWITCH_ENV}
        full_env.update(env)
        with mock.patch.dict(os.environ, full_env, clear=True), mock.patch.object(
            sys, "argv", ["asc_release.py", "--dry-run", *args]
        ), mock.patch.object(asc_release, "asc_from_environment",
                             side_effect=AssertionError("a dry run never starts asc")), \
                redirect_stdout(out), redirect_stderr(out):
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

    def test_creative_assets_need_exactly_one_image_per_slot(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            code, output = self.run_cli({"UPLOAD_CREATIVE_ASSETS": "true"}, "--creative-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("no header or search results image", output)
            (Path(tmp) / "header").mkdir()
            (Path(tmp) / "header" / "a.png").write_bytes(png_header(width=3840, height=1646))
            (Path(tmp) / "header" / "b.png").write_bytes(png_header(width=3840, height=1646))
            code, output = self.run_cli({"UPLOAD_CREATIVE_ASSETS": "true"}, "--creative-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("takes one image", output)
            (Path(tmp) / "header" / "b.png").unlink()
            (Path(tmp) / "header" / "notes.txt").write_text("not an image")
            code, output = self.run_cli({"UPLOAD_CREATIVE_ASSETS": "true"}, "--creative-dir", tmp)
            self.assertEqual(code, 0, output)
            self.assertIn("creative=True", output)

    def test_creative_images_are_never_read_with_the_switch_off(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            code, output = self.run_cli({"UPLOAD_LISTING": "true"}, "--creative-dir", tmp,
                                        "--metadata-dir", str(write_listing(Path(tmp)).parents[1]))
        self.assertEqual(code, 0, output)


class ListingUploadTests(unittest.TestCase):
    def upload(self, fake: FakeAsc, tmp: str, **kwargs) -> tuple[str, ...] | None:
        quiet(asc_release.upload_listing, fake, version_id="V1", metadata_dir=Path(tmp), **kwargs)
        updates = [c for c in fake.calls if c[:2] == ("localizations", "update")]
        self.assertLessEqual(len(updates), 1)
        return updates[0] if updates else None

    def test_listing_excludes_whats_new_unless_enabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text("New")
            for whats_new in (False, True):
                update = self.upload(FakeAsc(localization={}), tmp, include_listing=True, include_whats_new=whats_new)
                self.assertEqual(flag(update, "--id"), "LOC")
                self.assertEqual("--whats-new" in update, whats_new)
                self.assertEqual(flag(update, "--description"), LISTING["description.txt"])
                self.assertEqual(flag(update, "--support-url"), LISTING["support_url.txt"])
                self.assertNotIn("--name", update, "app name belongs to App Info, not the version")

    def test_whats_new_alone_leaves_the_listing_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text("New")
            update = self.upload(FakeAsc(localization={}), tmp, include_listing=False, include_whats_new=True)
            self.assertEqual(options(update), {"--id": "LOC", "--whats-new": "New"})

    def test_unchanged_listing_is_not_sent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp))
            fake = FakeAsc()
            self.assertIsNone(self.upload(fake, tmp))
            self.assertEqual(fake.writes(), [])

    def test_only_changed_fields_are_sent_and_a_leading_at_sign_is_escaped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp), **{"promotional_text.txt": "@verceltics on X"})
            fake = FakeAsc()
            update = self.upload(fake, tmp)
            self.assertEqual(options(update), {"--id": "LOC", "--promotional-text": "@@verceltics on X"})
            self.assertEqual(fake.text["promotionalText"], "@verceltics on X")

    def test_every_listing_field_has_an_asc_flag(self) -> None:
        fields = ios_metadata.VERSION_FIELDS + ios_metadata.APP_INFO_FIELDS + (ios_metadata.WHATS_NEW,)
        for field in fields:
            with self.subTest(field=field.attribute):
                self.assertIn(field.attribute, asc_release.LOCALIZATION_FLAGS)


class CmdWaitBuildTests(unittest.TestCase):
    def test_deadline_counts_from_upload_start_and_app_lookup_is_retried(self) -> None:
        lookups = []

        class Lookup:
            def run(self, *args: str, **_: object):
                lookups.append(args)
                if len(lookups) == 1:
                    raise asc_error(63)
                return {"data": {"id": APP, "attributes": {"bundleId": ios_release.BUNDLE_ID}}}

        captured = {}

        def fake_wait(client, **kwargs):
            captured.update(kwargs)
            return {"id": "B9"}

        args = argparse.Namespace(version=VERSION, build=40, started_at=STARTED, wait_minutes=120, poll_seconds=30)
        with tempfile.NamedTemporaryFile("r", suffix=".out") as out, mock.patch.object(
            ios_release, "asc_client", return_value=(asc_release, Lookup())
        ), mock.patch.object(ios_release, "wait_for_build", side_effect=fake_wait), mock.patch.object(
            ios_release.time, "sleep"
        ), mock.patch.object(ios_release.time, "time", return_value=STARTED + 30), mock.patch.dict(
            os.environ, {"GITHUB_OUTPUT": out.name}
        ), redirect_stdout(io.StringIO()):
            ios_release.cmd_wait_build(args)
            written = Path(out.name).read_text()
        self.assertEqual(lookups, [("apps", "view", "--id", APP)] * 2)
        self.assertEqual(captured["deadline"], STARTED + 120 * 60)
        self.assertEqual(captured["uploaded_after"], STARTED)
        self.assertEqual(captured["build_number"], "40")
        self.assertEqual(captured["app_id"], APP)
        self.assertIs(captured["is_transient"], ios_release.is_transient_error)
        self.assertIn("build_id=B9", written)

    def test_runs_alone_without_the_other_store_scripts(self) -> None:
        # Only build-number and wait-build import asc_cli; gates and --help need this file alone.
        import shutil
        import subprocess

        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "ios_release.py"
            shutil.copy(Path(ios_release.__file__), copy)
            for command in ("gates", "build-number", "wait-build"):
                done = subprocess.run([sys.executable, str(copy), command, "--help"],
                                      capture_output=True, text=True, timeout=60)
                with self.subTest(command=command):
                    self.assertEqual(done.returncode, 0, done.stderr)

    def test_other_errors_still_raise(self) -> None:
        with mock.patch.object(ios_release, "asc_client", side_effect=ValueError("bug")), \
                mock.patch.dict(os.environ, {}, clear=True), redirect_stdout(io.StringIO()), \
                self.assertRaises(ValueError):
            ios_release.main(["wait-build", "--version", VERSION, "--build", "40", "--started-at", str(STARTED),
                              "--wait-minutes", "120", "--poll-seconds", "30"])

    def test_asc_errors_end_with_an_error_line_not_a_traceback(self) -> None:
        err = io.StringIO()
        with mock.patch.object(ios_release, "asc_client", side_effect=asc_cli.AscError(("apps", "view"), 3, "unauthorized")), \
                mock.patch.dict(os.environ, {}, clear=True), redirect_stderr(err), redirect_stdout(io.StringIO()), \
                self.assertRaises(SystemExit) as caught:
            ios_release.main(["wait-build", "--version", VERSION, "--build", "40", "--started-at", str(STARTED),
                              "--wait-minutes", "120", "--poll-seconds", "30"])
        self.assertEqual(caught.exception.code, 1)
        self.assertIn("error: asc apps view failed (exit 3)", err.getvalue())

    def test_another_app_record_stops_the_wait(self) -> None:
        class Other:
            def run(self, *args: str, **_: object):
                return {"data": {"id": APP, "attributes": {"bundleId": "com.example.other"}}}

        args = argparse.Namespace(version=VERSION, build=40, started_at=STARTED, wait_minutes=120, poll_seconds=30)
        with mock.patch.object(ios_release, "asc_client", return_value=(asc_release, Other())), \
                mock.patch.object(ios_release.time, "time", return_value=STARTED), \
                redirect_stdout(io.StringIO()), self.assertRaises(ReleaseError):
            ios_release.cmd_wait_build(args)

    def test_app_lookup_gives_up_at_the_deadline(self) -> None:
        def boom():
            raise asc_error(63)

        clock = FakeClock(STARTED)
        with redirect_stdout(io.StringIO()), self.assertRaises(asc_cli.AscError):
            ios_release.call_with_retries(boom, deadline=STARTED + 90, poll_seconds=30, now=clock.now, sleep=clock.sleep)
        self.assertEqual(clock.sleeps, [30, 30, 30])

    def test_checks_run_asc_read_only(self) -> None:
        env = {"APP_STORE_CONNECT_API_KEY_ID": "K", "APP_STORE_CONNECT_ISSUER_ID": "I", "APP_STORE_CONNECT_API_KEY_P8": "P"}
        with mock.patch.dict(os.environ, env, clear=True):
            _, client = ios_release.asc_client()
        self.assertTrue(client.read_only)


class BuildNumberCommandTests(unittest.TestCase):
    def test_next_build_number_counts_every_uploaded_build_without_writing(self) -> None:
        fake = FakeAsc(builds=[build_row(f"B{n}", number=n) for n in ("5", "12", "4", "12")])
        with tempfile.NamedTemporaryFile("r", suffix=".out") as out, \
                mock.patch.object(ios_release, "asc_client", return_value=(asc_release, fake)), \
                mock.patch.object(ios_release, "single_project_value", return_value="1"), \
                mock.patch.dict(os.environ, {"GITHUB_OUTPUT": out.name}), redirect_stdout(io.StringIO()):
            ios_release.cmd_build_number(argparse.Namespace())
            written = Path(out.name).read_text()
        self.assertIn("build_number=13", written)
        self.assertEqual(fake.writes(), [])
        listing = fake.calls[heads(fake.calls, ("builds", "list"))[0]]
        self.assertEqual(flag(listing, "--app"), APP)
        self.assertEqual(flag(listing, "--processing-state"), "all")
        self.assertIn("--paginate", listing)


class LiveSubmitFlowTests(unittest.TestCase):
    def run_main(self, fake: FakeAsc, *, public: bool = False) -> int:
        env = {**SUBMIT, **({"PUBLIC_RELEASE": "true"} if public else {})}
        code, _ = run_asc(fake, env, *BUILD_ARGS)
        return code

    def test_selects_answers_compliance_rechecks_then_submits(self) -> None:
        fake = FakeAsc(subscriptions=[subscription("S1", MONTHLY, "READY_TO_SUBMIT", "Monthly")])
        self.assertEqual(self.run_main(fake), 0)
        calls = fake.calls
        select = heads(calls, ("versions", "attach-build"))[0]
        compliance = heads(calls, ("builds", "update"))[0]
        submit = heads(calls, ("review", "submissions-submit"))[0]
        reads = heads(calls, ("versions", "view"))
        build_checks = heads(calls, ("builds", "list"))
        self.assertEqual(len(reads), 2, "selection must be read back and rechecked")
        self.assertTrue(select < reads[0] < compliance < reads[1] < submit)
        self.assertTrue(build_checks[-1] < reads[1] < submit, "build must be re-verified just before submit")
        self.assertIs(fake.encryption, False, "the catalog declares no non-exempt encryption")
        self.assertEqual(fake.release_types, ["MANUAL"])
        added = [(flag(calls[i], "--item-type"), flag(calls[i], "--item-id")) for i in heads(calls, ("review", "items-add"))]
        self.assertEqual(added, [("subscriptionVersions", "S1v2"), ("appStoreVersions", "V1")])
        self.assertTrue(max(heads(calls, ("review", "items-add"))) < build_checks[-1],
                        "the recheck comes after everything is in the draft")
        self.assertEqual(fake.submitted, ["SUB1"])
        self.assertEqual(fake.modes, [False])

    def test_answered_compliance_is_not_changed(self) -> None:
        fake = FakeAsc(encryption=False)
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(heads(fake.calls, ("builds", "update")), [])

    def test_compliance_that_contradicts_the_catalog_aborts(self) -> None:
        fake = FakeAsc(encryption=True)
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual([c for c in fake.writes() if c[:1] == ("review",)], [], "nothing went to a review draft")
        self.assertEqual(fake.submitted, [])

    def test_selection_changed_before_submit_aborts(self) -> None:
        fake = FakeAsc(revert_after_select=True)
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])

    def test_waiting_for_review_with_another_build_aborts(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="OTHER")
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.writes(), [])

    def test_waiting_for_review_with_this_build_is_idempotent(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", drafts=["SUB1"])
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.writes(), [])
        # The catch-up for late products still looked for them.
        self.assertTrue(heads(fake.calls, ("subscriptions", "list")))
        self.assertTrue(heads(fake.calls, ("iap", "list")))

    def test_released_version_is_never_submitted(self) -> None:
        fake = FakeAsc(state="READY_FOR_SALE")
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.writes(), [])

    def test_public_release_sets_after_approval(self) -> None:
        fake = FakeAsc()
        self.assertEqual(self.run_main(fake, public=True), 0)
        self.assertEqual(fake.release_types, ["AFTER_APPROVAL"])

    def test_late_products_go_out_in_a_product_only_draft(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", iaps=[LIFETIME])
        self.assertEqual(self.run_main(fake), 0)
        added = [c for c in fake.writes() if c[:2] == ("review", "items-add")]
        self.assertEqual([(flag(c, "--item-type"), flag(c, "--item-id")) for c in added],
                         [("inAppPurchaseVersions", "IAP1v1")])
        self.assertEqual(fake.writes()[-1][:2], ("review", "submissions-submit"))
        # A draft that holds an app version is never touched from here: the
        # products go out in a new product-only draft instead.
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", iaps=[LIFETIME], drafts=["D1"],
                       draft_items={"D1": [item("appStoreVersion", "V0")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertNotIn("D1", [flag(c, "--submission") for c in fake.writes() if c[:2] == ("review", "items-add")])
        self.assertEqual(fake.submitted, ["SUB2"])

    def test_a_rerun_with_nothing_to_send_leaves_no_empty_draft(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9")
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.writes(), [])
        self.assertEqual(fake.drafts, [])
        # Approved products have nothing to send either.
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", iaps=[purchase("IAP2", COFFEE, "APPROVED", "Coffee")],
                       subscriptions=[subscription("S1", MONTHLY, "APPROVED")])
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.writes(), [])

    def test_a_rerun_finishes_the_product_draft_it_left_behind(self) -> None:
        # An earlier attempt attached the lifetime purchase but did not submit its draft.
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", iaps=[LIFETIME], drafts=["D1"],
                       draft_items={"D1": [item("inAppPurchaseVersion", "IAP1v1")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual([c[:2] for c in fake.writes()], [("review", "submissions-submit")])
        self.assertEqual(fake.submitted, ["D1"])

    def test_a_product_that_cannot_be_added_keeps_the_version_out_of_review(self) -> None:
        fake = FakeAsc(subscriptions=[subscription("S1", MONTHLY, "READY_TO_SUBMIT")], refuse_items={"S1v2"})
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])
        added = [flag(c, "--item-type") for c in fake.writes() if c[:2] == ("review", "items-add")]
        self.assertNotIn("appStoreVersions", added)

    def test_reused_draft_skips_what_it_already_holds(self) -> None:
        subs = [subscription("S1", MONTHLY, "DEVELOPER_REJECTED")]
        held = [item("subscriptionVersion", "S1v2"), item("appStoreVersion", "V1")]
        fake = FakeAsc(subscriptions=subs, drafts=["D1"], draft_items={"D1": held})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual([c[:2] for c in fake.writes() if c[:1] == ("review",)], [("review", "submissions-submit")])
        self.assertEqual(fake.submitted, ["D1"])

    def test_products_outside_the_catalog_or_already_approved_are_left_alone(self) -> None:
        subs = [subscription("S9", WEEKLY, "READY_TO_SUBMIT", "Legacy"), subscription("S1", MONTHLY, "APPROVED")]
        iaps = [purchase("IAP2", COFFEE, "APPROVED", "Coffee")]
        fake = FakeAsc(subscriptions=subs, iaps=iaps)
        self.assertEqual(self.run_main(fake), 0)
        added = [flag(c, "--item-type") for c in fake.writes() if c[:2] == ("review", "items-add")]
        self.assertEqual(added, ["appStoreVersions"])
        self.assertFalse([c for c in fake.calls if "S9" in c], "a product outside the catalog was read")

    def test_a_reused_draft_with_items_this_release_did_not_choose_is_not_submitted(self) -> None:
        stray = [item("inAppPurchaseVersion", "OTHER-IAPv1", "STRAY")]
        fake = FakeAsc(drafts=["D1"], draft_items={"D1": stray})
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])
        # A catalog product's version (in any state) and this version are fine.
        held = [item("inAppPurchaseVersion", "IAP2v1", "OK")]
        fake = FakeAsc(iaps=[purchase("IAP2", COFFEE, "APPROVED", "Coffee")], drafts=["D1"], draft_items={"D1": held})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.submitted, ["D1"])

    def test_a_creative_draft_is_never_used_for_the_version(self) -> None:
        fake = FakeAsc(drafts=["CREATIVE"], draft_items={"CREATIVE": [item("appAssetLibraryImage", "H", "IMG")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertIn(("review", "submissions-create"), [c[:2] for c in fake.writes()])
        self.assertEqual(fake.submitted, ["SUB2"])

    def test_a_ready_for_review_version_with_no_draft_holding_it_stops_before_writing_to_review(self) -> None:
        # READY_FOR_REVIEW says a draft holds the version; the only draft here does not.
        for held in ([], [item("subscriptionVersion", "S1v2")]):
            fake = FakeAsc(state="READY_FOR_REVIEW", subscriptions=[subscription("S1", MONTHLY, "READY_TO_SUBMIT")],
                           drafts=["D1"], draft_items={"D1": held})
            with self.subTest(held=held):
                self.assertEqual(self.run_main(fake), 1)
                # Checked before the build is selected, so nothing at all is written.
                self.assertEqual(fake.writes(), [])
        fake = FakeAsc(state="READY_FOR_REVIEW", drafts=["D1"], draft_items={"D1": [item("appStoreVersion", "V1")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.submitted, ["D1"])
        self.assertEqual([c for c in fake.writes() if c[:2] == ("review", "items-add")], [])

    def test_a_stopped_submit_leaves_the_listing_text_alone_too(self) -> None:
        # Text stays editable in READY_FOR_REVIEW, so the draft check must come
        # before the listing update, not just before the build selection.
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp), **{"description.txt": "New description"})
            (loc / "whats_new.txt").write_text("Fixes.\n")
            fake = FakeAsc(state="READY_FOR_REVIEW", localization={"description": "Old", "whatsNew": "Older"},
                           app_infos=[app_info("NEXT", "READY_FOR_REVIEW")])
            env = {**SUBMIT, "UPLOAD_LISTING": "true", "UPLOAD_WHATS_NEW": "true"}
            code, output = run_asc(fake, env, *BUILD_ARGS, metadata=Path(tmp))
        self.assertEqual(code, 1, output)
        self.assertIn("no draft review submission holds it", output)
        self.assertEqual(fake.writes(), [])

    def test_a_draft_without_this_version_is_never_submitted(self) -> None:
        # The last check before submitting refuses a draft that does not hold the version.
        empty = FakeAsc(drafts=["D1"], draft_items={"D1": []})
        with self.assertRaises(SystemExit):
            quiet(asc_release.check_draft_items, empty, "D1", version_id="V1", allowed_products=set())
        holding = FakeAsc(drafts=["D1"], draft_items={"D1": [item("appStoreVersion", "V1")]})
        quiet(asc_release.check_draft_items, holding, "D1", version_id="V1", allowed_products=set())

    def test_the_draft_holding_this_version_is_chosen_over_others(self) -> None:
        other = [item("subscriptionVersion", "S1v2", "P")]
        mine = [item("appStoreVersion", "V1", "V")]
        fake = FakeAsc(state="READY_FOR_REVIEW", subscriptions=[subscription("S1", MONTHLY, "READY_TO_SUBMIT")],
                       drafts=["OTHER", "MINE"], draft_items={"OTHER": other, "MINE": mine})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual({flag(c, "--submission") for c in fake.writes() if c[:2] == ("review", "items-add")}, {"MINE"})
        self.assertEqual(fake.submitted, ["MINE"])

    def test_another_versions_draft_is_never_reused(self) -> None:
        fake = FakeAsc(drafts=["THEIRS"], draft_items={"THEIRS": [item("appStoreVersion", "V0", "V")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertIn(("review", "submissions-create"), [c[:2] for c in fake.writes()])
        self.assertEqual(fake.submitted, ["SUB2"])
        self.assertNotIn("THEIRS", [flag(c, "--submission") for c in fake.writes() if c[:2] == ("review", "items-add")])


class ProductReviewOrderTests(unittest.TestCase):
    """Products join the version's draft; nothing reaches App Review before the final recheck."""

    def test_the_lifetime_purchase_waits_in_the_draft_until_after_the_recheck(self) -> None:
        fake = FakeAsc(iaps=[LIFETIME, purchase("IAP2", COFFEE, "APPROVED", "Coffee")])
        code, _ = run_asc(fake, SUBMIT, *BUILD_ARGS)
        self.assertEqual(code, 0)
        calls = fake.calls
        recheck = max(heads(calls, ("versions", "view")))
        added = heads(calls, ("review", "items-add"))
        self.assertEqual([(flag(calls[i], "--item-type"), flag(calls[i], "--item-id")) for i in added],
                         [("inAppPurchaseVersions", "IAP1v1"), ("appStoreVersions", "V1")])
        submits = heads(calls, ("review", "submissions-submit"))
        self.assertEqual(len(submits), 1, "one submission carries the version and its products")
        self.assertTrue(max(added) < recheck < submits[0])

    def test_a_failed_recheck_sends_nothing_to_review(self) -> None:
        fake = FakeAsc(iaps=[LIFETIME], revert_after_select=True)
        code, _ = run_asc(fake, SUBMIT, *BUILD_ARGS)
        self.assertEqual(code, 1)
        self.assertEqual(fake.submitted, [])


@contextlib.contextmanager
def temp_screenshot_sets(*, alpha: bool = False):
    """Small screenshot sets in a temporary folder, in place of the repository's."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        sets = []
        for spec in ios_metadata.SCREENSHOT_SETS:
            folder = root / spec.directory.name
            folder.mkdir()
            width, height = min(spec.sizes)
            for i in range(2):
                (folder / f"{i}.png").write_bytes(png(width, height, alpha=alpha) + bytes([i]))
            sets.append(ios_metadata.ScreenshotSet(spec.display_type, spec.label, folder, spec.sizes))
        with mock.patch.object(ios_metadata, "SCREENSHOT_SETS", tuple(sets)), \
                mock.patch.object(ios_metadata, "ROOT", root):
            yield tuple(sets)


def wanted_screenshots(sets) -> dict[str, list[str]]:
    return {spec.display_type: [md5(p.read_bytes()) for p in ios_metadata.screenshot_files(spec)] for spec in sets}


class VersionStateTests(unittest.TestCase):
    """Reruns after a partly completed submission (IOS-REL-3), with metadata switches on."""

    LIVE_SWITCHES = {"UPLOAD_LISTING": "true", "UPLOAD_WHATS_NEW": "true", "SUBMIT_IOS_REVIEW": "true"}

    def run_live(self, fake: FakeAsc, env: dict[str, str], *, whats_new: str = "Faster dashboards.") -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp))
            (loc / "whats_new.txt").write_text(whats_new + "\n")
            return run_asc(fake, env, *BUILD_ARGS, metadata=Path(tmp))

    def test_ready_for_review_updates_text_and_submits(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW", app_infos=[app_info("NEXT", "READY_FOR_REVIEW")],
                       drafts=["SUB1"], draft_items={"SUB1": [item("appStoreVersion", "V1")]})
        code, output = self.run_live(fake, self.LIVE_SWITCHES)
        self.assertEqual(code, 0, output)
        update = fake.calls[heads(fake.calls, ("localizations", "update"))[0]]
        self.assertEqual(options(update), {"--id": "LOC", "--whats-new": "Faster dashboards."})
        self.assertEqual(fake.submitted, ["SUB1"])

    def test_ready_for_review_accepts_screenshots_only_when_unchanged(self) -> None:
        env = {"UPLOAD_SCREENSHOTS": "true", "SUBMIT_IOS_REVIEW": "true"}
        held = {"SUB1": [item("appStoreVersion", "V1")]}
        with temp_screenshot_sets() as sets:
            wanted = wanted_screenshots(sets)
            same = FakeAsc(state="READY_FOR_REVIEW", screenshots=wanted, drafts=["SUB1"], draft_items=held)
            code, output = self.run_live(same, env)
            self.assertEqual(code, 0, output)
            self.assertEqual(same.uploaded, [])
            self.assertEqual(same.submitted, ["SUB1"])
            for live in ({k: v[:-1] for k, v in wanted.items()}, {k: [None, None] for k in wanted}, {}):
                changed = FakeAsc(state="READY_FOR_REVIEW", screenshots=live, drafts=["SUB1"], draft_items=held)
                code, output = self.run_live(changed, env)
                self.assertEqual(code, 1)
                self.assertIn("cannot change", output)
                self.assertEqual(changed.writes(), [])

    def test_locked_screenshots_are_compared_before_any_text_is_written(self) -> None:
        env = {**self.LIVE_SWITCHES, "UPLOAD_SCREENSHOTS": "true"}
        with temp_screenshot_sets() as sets:
            live = {k: v[::-1] for k, v in wanted_screenshots(sets).items()}  # same files, other order
            fake = FakeAsc(state="READY_FOR_REVIEW", screenshots=live, localization={"description": "Old"},
                           app_infos=[app_info("NEXT", "READY_FOR_REVIEW")],
                           drafts=["SUB1"], draft_items={"SUB1": [item("appStoreVersion", "V1")]})
            code, output = self.run_live(fake, env)
        self.assertEqual(code, 1)
        self.assertIn("cannot change", output)
        self.assertEqual(fake.writes(), [])

    def test_waiting_for_review_rerun_with_matching_metadata_changes_nothing(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9",
                       localization={**FakeAsc.LIVE_TEXT, "whatsNew": "Faster dashboards."},
                       app_infos=[app_info("LIVE", "READY_FOR_DISTRIBUTION"), app_info("NEXT", "WAITING_FOR_REVIEW")],
                       drafts=["SUB1"])
        code, output = self.run_live(fake, self.LIVE_SWITCHES)
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.writes(), [], "a rerun of a submitted version must not write anything")
        self.assertIn("idempotent", output)

    def test_waiting_for_review_rerun_that_needs_a_change_fails(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9",
                       localization={**FakeAsc.LIVE_TEXT, "whatsNew": "Older notes."})
        code, output = self.run_live(fake, self.LIVE_SWITCHES)
        self.assertEqual(code, 1)
        self.assertIn("whatsNew", output)
        self.assertEqual(fake.writes(), [])


class PreflightTests(unittest.TestCase):
    """--preflight: read-only checks before the build is uploaded (IOS-REL-2)."""

    def run_preflight(self, fake: FakeAsc, env: dict[str, str], *, creative: Path | None = None,
                      **listing: str | None) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            loc = write_listing(Path(tmp), **listing)
            (loc / "whats_new.txt").write_text("Faster dashboards.\n")
            code, output = run_asc(fake, env, "--preflight", metadata=Path(tmp), creative=creative)
        self.assertEqual(fake.writes(), [], "the preflight must never write")
        self.assertEqual(fake.modes, [True] if fake.calls else [], "the preflight runs asc read-only")
        return code, output

    def test_an_editable_version_passes_without_a_build(self) -> None:
        code, output = self.run_preflight(FakeAsc(), VersionStateTests.LIVE_SWITCHES)
        self.assertEqual(code, 0, output)
        self.assertIn("preflight passed", output)
        self.assertIn("will be updated: whatsNew", output)

    def test_versions_the_store_step_can_use_pass(self) -> None:
        held = [item("appStoreVersion", "V1")]
        for state in sorted(asc_release.SUBMITTABLE_VERSION_STATES):
            with self.subTest(state=state):
                fake = FakeAsc(state=state, drafts=["D1"], draft_items={"D1": held})
                code, output = self.run_preflight(fake, VersionStateTests.LIVE_SWITCHES)
                self.assertEqual(code, 0, output)
                self.assertIn("preflight passed", output)

    def test_a_ready_for_review_version_needs_its_draft_before_the_build(self) -> None:
        other = [item("subscriptionVersion", "S1v2", "P")]
        for drafts, items in (([], {}), (["D1"], {"D1": other})):
            fake = FakeAsc(state="READY_FOR_REVIEW", drafts=drafts, draft_items=items)
            with self.subTest(drafts=drafts):
                code, output = self.run_preflight(fake, SUBMIT)
                self.assertEqual(code, 1)
                self.assertIn("no draft review submission holds it", output)
        # Without review submission the draft does not matter.
        code, output = self.run_preflight(FakeAsc(state="READY_FOR_REVIEW"), {"UPLOAD_LISTING": "true"})
        self.assertEqual(code, 0, output)

    def test_a_missing_version_fails_with_the_fix(self) -> None:
        code, output = self.run_preflight(FakeAsc(version_exists=False), VersionStateTests.LIVE_SWITCHES)
        self.assertEqual(code, 1)
        self.assertIn("create the version in App Store Connect first", output)

    def test_states_that_rule_out_the_enabled_steps_fail(self) -> None:
        for state, env in (
            ("READY_FOR_SALE", {"UPLOAD_LISTING": "true"}),
            ("READY_FOR_SALE", {"SUBMIT_IOS_REVIEW": "true"}),
            ("READY_FOR_SALE", {"UPLOAD_CREATIVE_ASSETS": "true"}),
            ("IN_REVIEW", {"UPLOAD_WHATS_NEW": "true"}),
            ("WAITING_FOR_REVIEW", {"SUBMIT_IOS_REVIEW": "true"}),
        ):
            with self.subTest(state=state, env=env), tempfile.TemporaryDirectory() as creative:
                (Path(creative) / "header").mkdir()
                (Path(creative) / "header" / "header.png").write_bytes(png(3840, 1646))
                code, _ = self.run_preflight(FakeAsc(state=state), env, creative=Path(creative))
                self.assertEqual(code, 1)

    def test_a_missing_locale_fails(self) -> None:
        fake = FakeAsc()
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp))
            write_listing(Path(tmp), locale="fr-FR")
            code, output = run_asc(fake, {"UPLOAD_LISTING": "true"}, "--preflight", metadata=Path(tmp))
        self.assertEqual(code, 1)
        self.assertIn("fr-FR", output)
        self.assertEqual(fake.writes(), [])

    def test_an_app_info_change_without_an_editable_app_info_fails(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW")  # only the live App Info exists
        code, output = self.run_preflight(fake, {"UPLOAD_LISTING": "true"}, **{"name.txt": "Verceltics"})
        self.assertEqual(code, 1)
        self.assertIn("no App Info is editable", output)

    def test_without_pillow_screenshot_sets_with_alpha_to_replace_fail(self) -> None:
        env = {"UPLOAD_SCREENSHOTS": "true"}
        with temp_screenshot_sets(alpha=True) as sets, \
                mock.patch.object(asc_release, "try_import_pillow", return_value=None):
            code, output = self.run_preflight(FakeAsc(), env)
            self.assertEqual(code, 1)
            self.assertIn("alpha channel", output)
            self.assertIn("install Pillow", output)
            # Nothing has to be uploaded when App Store Connect already holds them.
            code, output = self.run_preflight(FakeAsc(screenshots=wanted_screenshots(sets)), env)
            self.assertEqual(code, 0, output)
            self.assertIn("already match", output)
        with temp_screenshot_sets():
            code, output = self.run_preflight(FakeAsc(), env)
            self.assertEqual(code, 0, output)
            self.assertIn("will be replaced", output)

    def test_creative_assets_need_the_version_localization(self) -> None:
        with tempfile.TemporaryDirectory() as creative:
            (Path(creative) / "search-results").mkdir()
            (Path(creative) / "search-results" / "search.png").write_bytes(png(1920, 1280))
            env = {"UPLOAD_CREATIVE_ASSETS": "true"}
            code, output = self.run_preflight(FakeAsc(), env, creative=Path(creative))
            self.assertEqual(code, 0, output)
            code, output = self.run_preflight(FakeAsc(locales=()), env, creative=Path(creative))
            self.assertEqual(code, 1)
            self.assertIn("has no en-US localization", output)


class CreateVersionTests(unittest.TestCase):
    """--create-version (IOS_CREATE_VERSION): add the tag's App Store version only when it is missing."""

    ON = {"CREATE_IOS_VERSION": "true"}

    def create(self, fake: FakeAsc, flags: dict[str, str] | None = None, *extra: str,
               version: str = VERSION) -> tuple[int, str]:
        return run_asc(fake, self.ON if flags is None else flags, "--create-version", *extra, version=version)

    def test_switch_off_makes_no_calls(self) -> None:
        fake = FakeAsc(version_exists=False)
        code, output = self.create(fake, {})
        self.assertEqual(code, 0, output)
        self.assertIn("skipped", output)
        self.assertEqual(fake.calls, [])

    def test_existing_version_is_left_alone(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW")
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertIn("already exists (READY_FOR_REVIEW)", output)
        self.assertEqual(fake.writes(), [])

    def test_missing_version_is_created_for_this_app_and_read_back(self) -> None:
        fake = FakeAsc(version_exists=False)
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertEqual([c[:2] for c in fake.writes()], [("versions", "create")])
        create = fake.writes()[0]
        self.assertEqual(options(create), {"--app": APP, "--version": VERSION, "--platform": "IOS"})
        self.assertEqual(fake.calls[-1][:2], ("versions", "list"), "the created version must be read back")
        self.assertIn(f"created App Store version {VERSION} (PREPARE_FOR_SUBMISSION)", output)
        self.assertEqual(fake.modes, [False])

    def test_another_version_on_its_way_blocks_creation(self) -> None:
        for state in ("PREPARE_FOR_SUBMISSION", "WAITING_FOR_REVIEW", "PENDING_DEVELOPER_RELEASE"):
            with self.subTest(state=state):
                fake = FakeAsc(version_exists=False, other_versions=[("2.1", "READY_FOR_SALE"), ("2.0", state)])
                code, output = self.create(fake)
                self.assertEqual(code, 1)
                self.assertIn(f"is still {state}", output)
                self.assertEqual(fake.writes(), [])

    def test_version_not_newer_than_an_existing_one_is_refused(self) -> None:
        for existing in ("2.3", "2.2.1", "3"):
            with self.subTest(existing=existing):
                fake = FakeAsc(version_exists=False, other_versions=[(existing, "READY_FOR_SALE")])
                code, output = self.create(fake)
                self.assertEqual(code, 1)
                self.assertIn(f"not newer than the existing version {existing}", output)
                self.assertEqual(fake.writes(), [])

    def test_older_finished_versions_allow_creation(self) -> None:
        fake = FakeAsc(version_exists=False,
                       other_versions=[("2.0", "REPLACED_WITH_NEW_VERSION"), ("2.1.10", "READY_FOR_SALE")])
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.created_versions, [VERSION])

    def test_other_app_or_bad_version_fails_without_writing(self) -> None:
        fake = FakeAsc(bundle="com.example.other", version_exists=False)
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertIn(f"expected {BUNDLE}", output)
        self.assertEqual(fake.writes(), [])
        for bad in ("2.2-beta", "v2.2", "2.2.1.1", "٢.٢"):
            with self.subTest(bad=bad):
                fake = FakeAsc(version_exists=False)
                code, output = self.create(fake, version=bad)
                self.assertEqual(code, 1)
                self.assertIn("must look like", output)
                self.assertEqual(fake.calls, [])

    def test_ambiguous_version_fails_without_writing(self) -> None:
        fake = FakeAsc(other_versions=[(VERSION, "PREPARE_FOR_SUBMISSION")])
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertIn("ambiguous", output)
        self.assertEqual(fake.writes(), [])

    def test_dry_run_makes_no_calls(self) -> None:
        fake = FakeAsc(version_exists=False)
        code, output = self.create(fake, None, "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertIn("would create", output)
        self.assertEqual(fake.calls, [])

    def test_dry_run_rejects_a_bad_version_like_a_live_run(self) -> None:
        for bad in ("2.2-beta", "v2.2", "2.2.1.1", "٢.٢"):
            with self.subTest(bad=bad):
                fake = FakeAsc(version_exists=False)
                code, output = self.create(fake, None, "--dry-run", version=bad)
                self.assertEqual(code, 1)
                self.assertIn("must look like", output)
                self.assertEqual(fake.calls, [])

    def test_create_version_never_runs_together_with_the_preflight(self) -> None:
        fake = FakeAsc(version_exists=False)
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
        fake = FakeAsc(version_exists=False, listed_after_create=False)
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertEqual([c[:2] for c in fake.writes()], [("versions", "create")])
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
            code, output = run_asc(preflight, env, "--preflight", metadata=Path(tmp))
            self.assertEqual(code, 0, output)
            self.assertIn("will be updated: whatsNew", output)
            self.assertEqual(preflight.writes(), [], "the preflight must never write")
            fake = FakeAsc()
            code, output = run_asc(fake, env, metadata=Path(tmp))
        self.assertEqual(code, 0, output)
        self.assertIn("updated version localization (en-US): whatsNew", output)
        self.assertEqual([options(c) for c in fake.writes()], [{"--id": "LOC", "--whats-new": "Faster dashboards."}])
        for calls in (preflight.calls, fake.calls):
            self.assertFalse([c for c in calls if c[:2] == ("apps", "info") or "app-info" in c],
                             "App Info belongs to the listing")

    def test_review_alone_needs_no_prepared_metadata(self) -> None:
        # Without the listing and What's New, prepare --no-listing writes nothing at all.
        with tempfile.TemporaryDirectory() as tmp:
            fake = FakeAsc()
            code, output = run_asc(fake, SUBMIT, *BUILD_ARGS, metadata=Path(tmp) / "store-meta")
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.submitted, ["SUB1"])
        self.assertFalse([c for c in fake.calls if c[0] == "localizations" or c[:2] == ("apps", "info")])


class AppInfoUploadTests(unittest.TestCase):
    def upload(self, fake: FakeAsc, **overrides: str | None) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp), **overrides)
            try:
                quiet(asc_release.upload_app_info, fake, app_id=APP, metadata_dir=Path(tmp))
            except SystemExit as exc:
                return exc.code if isinstance(exc.code, int) else 1
        return 0

    def test_changed_fields_go_to_the_editable_app_info(self) -> None:
        fake = FakeAsc(app_infos=[app_info("LIVE", "READY_FOR_DISTRIBUTION"), app_info("NEXT", "PREPARE_FOR_SUBMISSION")])
        self.assertEqual(self.upload(fake, **{"subtitle.txt": "Hosting, Domains, Uptime"}), 0)
        self.assertEqual([options(c) for c in fake.writes()],
                         [{"--type": "app-info", "--id": "INFOLOC", "--subtitle": "Hosting, Domains, Uptime"}])
        read = fake.calls[heads(fake.calls, ("localizations", "list"))[0]]
        self.assertEqual((flag(read, "--type"), flag(read, "--app-info"), flag(read, "--locale")),
                         ("app-info", "NEXT", "en-US"))

    def test_unchanged_values_need_no_editable_app_info(self) -> None:
        fake = FakeAsc()
        self.assertEqual(self.upload(fake), 0)
        self.assertEqual(fake.writes(), [])

    def test_a_change_without_an_editable_app_info_fails(self) -> None:
        fake = FakeAsc()
        self.assertEqual(self.upload(fake, **{"name.txt": "Verceltics"}), 1)
        self.assertEqual(fake.writes(), [])

    def test_two_editable_app_infos_are_ambiguous(self) -> None:
        fake = FakeAsc(app_infos=[app_info("A", "PREPARE_FOR_SUBMISSION"), app_info("B", "DEVELOPER_REJECTED")])
        self.assertEqual(self.upload(fake, **{"name.txt": "Verceltics"}), 1)
        self.assertEqual(fake.writes(), [])


def chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def png(width: int, height: int, *, alpha: bool = False) -> bytes:
    """A minimal PNG header with the given size (enough for size and alpha checks); RGB unless alpha."""
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)


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

    def test_screenshot_sizes_are_the_ones_asc_accepts(self) -> None:
        # `asc screenshots sizes --display-type <type>` (asc 5.15.0); 1284x2778 is the 6.5" size.
        accepted = {
            "APP_IPHONE_67": {(1260, 2736), (1290, 2796), (1320, 2868), (2736, 1260), (2796, 1290), (2868, 1320)},
            "APP_IPAD_PRO_3GEN_129": {(2048, 2732), (2064, 2752), (2732, 2048), (2752, 2064)},
        }
        self.assertEqual({spec.display_type: set(spec.sizes) for spec in ios_metadata.SCREENSHOT_SETS}, accepted)
        iphone = ios_metadata.SCREENSHOT_SETS[0]
        with tempfile.TemporaryDirectory() as tmp:
            spec = ios_metadata.ScreenshotSet(iphone.display_type, iphone.label, Path(tmp), iphone.sizes)
            with mock.patch.object(ios_metadata, "ROOT", Path(tmp).parent):
                (Path(tmp) / "a.png").write_bytes(png(1284, 2778))
                with self.assertRaisesRegex(ios_metadata.MetadataError, "1284x2778"):
                    ios_metadata.screenshot_files(spec)
                (Path(tmp) / "a.png").write_bytes(png(1320, 2868))
                self.assertEqual([p.name for p in ios_metadata.screenshot_files(spec)], ["a.png"])

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
    ENV = {"UPLOAD_SCREENSHOTS": "true"}

    def test_every_screenshot_set_has_an_asc_device_type(self) -> None:
        for spec in ios_metadata.SCREENSHOT_SETS:
            with self.subTest(spec.label):
                self.assertEqual("APP_" + asc_release.SCREENSHOT_DEVICE_TYPES[spec.display_type], spec.display_type)

    def test_sets_that_differ_are_replaced_with_exactly_the_checked_pngs(self) -> None:
        with temp_screenshot_sets() as sets:
            (sets[0].directory / "notes.txt").write_text("not a screenshot")  # never uploaded
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
            self.assertEqual(code, 0, output)
            self.assertEqual(fake.uploaded, [("IPHONE_67", ["0.png", "1.png"]), ("IPAD_PRO_3GEN_129", ["0.png", "1.png"])])
            for upload in [c for c in fake.writes() if c[:2] == ("screenshots", "upload")]:
                found = options(upload)
                self.assertEqual((found["--app"], found["--version-id"], found["--locale"]), (APP, "V1", "en-US"))
                self.assertIn("--replace", found)
                self.assertIn("--confirm", found)
                self.assertFalse(Path(found["--path"]).exists(), "the staged copy is removed after the upload")
            self.assertEqual(fake.screenshots, wanted_screenshots(sets))
            # Read back after every upload.
            uploads = heads(fake.calls, ("screenshots", "upload"))
            for i in uploads:
                self.assertEqual(fake.calls[i + 1][:2], ("screenshots", "list"))

    def test_a_set_that_already_matches_is_not_uploaded_again(self) -> None:
        with temp_screenshot_sets() as sets:
            wanted = wanted_screenshots(sets)
            fake = FakeAsc(screenshots={"APP_IPHONE_67": wanted["APP_IPHONE_67"]})
            code, output = run_asc(fake, self.ENV)
            self.assertEqual(code, 0, output)
            self.assertEqual([device for device, _ in fake.uploaded], ["IPAD_PRO_3GEN_129"])
            fake = FakeAsc(screenshots=wanted)
            code, output = run_asc(fake, self.ENV)
            self.assertEqual(code, 0, output)
            self.assertEqual(fake.writes(), [])

    def test_an_upload_that_does_not_land_fails(self) -> None:
        with temp_screenshot_sets():
            fake = FakeAsc(upload_lands=False)
            code, output = run_asc(fake, self.ENV)
        self.assertEqual(code, 1)
        self.assertIn("after uploading", output)
        self.assertEqual(len(fake.uploaded), 1, "the run stops at the first set that does not match")

    def test_without_pillow_pngs_with_alpha_stop_the_run_before_anything_is_written(self) -> None:
        with temp_screenshot_sets(alpha=True), tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(asc_release, "try_import_pillow", return_value=None):
            write_listing(Path(tmp))
            fake = FakeAsc(localization={"description": "Old"})
            code, output = run_asc(fake, {**self.ENV, "UPLOAD_LISTING": "true"}, metadata=Path(tmp))
        self.assertEqual(code, 1)
        self.assertIn("alpha channel", output)
        self.assertIn("install Pillow", output)
        self.assertEqual(fake.writes(), [], "neither the text nor any screenshot was changed")

    def test_an_upload_copy_with_alpha_is_never_handed_to_asc(self) -> None:
        with temp_screenshot_sets(alpha=True), \
                mock.patch.object(asc_release, "rgb_copy", side_effect=lambda path: path.read_bytes()):
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
        self.assertEqual(code, 1)
        self.assertIn("still have an alpha channel", output)
        self.assertEqual(fake.uploaded, [])

    def test_a_set_without_screenshots_fails_before_any_call(self) -> None:
        with temp_screenshot_sets() as sets:
            for path in sets[1].directory.glob("*.png"):
                path.unlink()
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
        self.assertEqual(code, 1)
        self.assertIn("screenshots", output)
        self.assertEqual(fake.calls, [])


# Real PNGs need Pillow, which only the store tooling installs (requirements-store.txt).
# store-automation.yml's store-tooling job sets STORE_TESTS_NEED_PILLOW, so these
# tests cannot skip there.
try:
    from PIL import Image as PILLOW  # type: ignore[import-untyped]
except ImportError:
    PILLOW = None


@unittest.skipIf(PILLOW is None and not os.environ.get("STORE_TESTS_NEED_PILLOW"), "Pillow not installed")
class ScreenshotRgbCopyTests(unittest.TestCase):
    """Screenshots that store alpha are uploaded as RGB copies; the repository's files never change."""

    ENV = {"UPLOAD_SCREENSHOTS": "true"}

    def setUp(self) -> None:
        if PILLOW is None:
            self.fail("Pillow is required here (STORE_TESTS_NEED_PILLOW is set)")

    def write_rgba(self, path: Path, size: tuple[int, int], index: int, *, icc_profile: bytes | None = None) -> None:
        """An opaque RGBA screenshot with distinct pixels, a fully transparent corner and a translucent one."""
        img = PILLOW.new("RGBA", size, (30 + index, 60, 90, 255))
        for x in range(0, size[0], 97):
            img.putpixel((x, size[1] // 2), (x % 256, (x * 7) % 256, 200, 255))
        img.putpixel((0, 0), (250, 250, 250, 0))
        img.putpixel((1, 0), (200, 100, 50, 128))
        img.save(path, format="PNG", icc_profile=icc_profile)

    @contextlib.contextmanager
    def rgba_sets(self, *, icc_profile: bytes | None = None):
        with temp_screenshot_sets() as sets:
            for spec in sets:
                for i, path in enumerate(sorted(spec.directory.glob("*.png"))):
                    self.write_rgba(path, min(spec.sizes), i, icc_profile=icc_profile)
            yield sets

    def committed(self, sets) -> dict[Path, bytes]:
        return {path: path.read_bytes() for spec in sets for path in sorted(spec.directory.glob("*.png"))}

    def test_an_rgba_png_is_uploaded_as_an_rgb_copy_with_the_same_pixels(self) -> None:
        with self.rgba_sets() as sets:
            before = self.committed(sets)
            self.assertTrue(all(asc_cli.image_has_alpha(path) for path in before))
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
            self.assertEqual(code, 0, output)
            self.assertIn("RGB copies of 0.png, 1.png", output)
            self.assertEqual(self.committed(sets), before, "the repository's screenshots are never changed")
            self.assertEqual([device for device, _ in fake.uploaded], ["IPHONE_67", "IPAD_PRO_3GEN_129"])
            for spec in sets:
                device = asc_release.SCREENSHOT_DEVICE_TYPES[spec.display_type]
                sources = sorted(spec.directory.glob("*.png"))
                uploaded = fake.uploaded_bytes[device]
                self.assertEqual(len(uploaded), len(sources))
                self.assertEqual(fake.screenshots[spec.display_type], [md5(raw) for raw in uploaded],
                                 "the read-back compares the copies that were uploaded")
                for source, raw in zip(sources, uploaded):
                    with self.subTest(set=spec.label, file=source.name), \
                            PILLOW.open(source) as original, PILLOW.open(io.BytesIO(raw)) as copy:
                        self.assertEqual(original.mode, "RGBA")
                        self.assertEqual(copy.mode, "RGB")
                        self.assertEqual(copy.size, original.size)
                        expected = original.convert("RGB")
                        # Transparent pixels are laid over black, translucent ones blended onto it.
                        expected.putpixel((0, 0), (0, 0, 0))
                        expected.putpixel((1, 0), (100, 50, 25))
                        self.assertEqual(copy.tobytes(), expected.tobytes())

    def test_other_pngs_with_transparency_become_rgb_copies(self) -> None:
        with temp_screenshot_sets() as sets:
            size = min(sets[0].sizes)
            first, second = sorted(sets[0].directory.glob("*.png"))
            gray = PILLOW.new("LA", size, (120, 255))
            gray.putpixel((0, 0), (200, 0))
            gray.save(first, format="PNG")
            palette = PILLOW.new("P", size, 1)
            palette.putpalette([0, 0, 0, 10, 200, 30] + [0] * 762)
            palette.save(second, format="PNG", transparency=0)
            for spec in sets[1:]:
                for path in spec.directory.glob("*.png"):
                    PILLOW.new("RGB", min(spec.sizes), (1, 2, 3)).save(path, format="PNG")
            self.assertTrue(asc_cli.image_has_alpha(first) and asc_cli.image_has_alpha(second))
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
            self.assertEqual(code, 0, output)
            copies = fake.uploaded_bytes["IPHONE_67"]
            with PILLOW.open(io.BytesIO(copies[0])) as copy:
                self.assertEqual((copy.mode, copy.getpixel((0, 0)), copy.getpixel((1, 1))), ("RGB", (0, 0, 0), (120, 120, 120)))
            with PILLOW.open(io.BytesIO(copies[1])) as copy:
                self.assertEqual((copy.mode, copy.getpixel((0, 0))), ("RGB", (10, 200, 30)))
            # A set without alpha is uploaded byte for byte.
            ipad = sorted(sets[1].directory.glob("*.png"))
            self.assertEqual(fake.uploaded_bytes["IPAD_PRO_3GEN_129"], [path.read_bytes() for path in ipad])

    def test_the_colour_profile_is_kept(self) -> None:
        profile = b"not a real profile, kept byte for byte"
        with self.rgba_sets(icc_profile=profile):
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
            self.assertEqual(code, 0, output)
        for copies in fake.uploaded_bytes.values():
            for raw in copies:
                with PILLOW.open(io.BytesIO(raw)) as copy:
                    self.assertEqual(copy.info.get("icc_profile"), profile)

    def test_a_set_showing_the_committed_files_or_their_rgb_copies_is_left_alone(self) -> None:
        with self.rgba_sets() as sets:
            committed = wanted_screenshots(sets)
            copies = {spec.display_type: [md5(raw) for raw in asc_release.upload_copies(asc_release.screenshot_pngs(spec))]
                      for spec in sets}
            self.assertNotEqual(committed, copies)
            for live in (committed, copies):
                fake = FakeAsc(screenshots=live)
                code, output = run_asc(fake, self.ENV)
                self.assertEqual(code, 0, output)
                self.assertEqual(fake.writes(), [])
                # Locked in review, both count as unchanged, and the preflight agrees.
                held = {"SUB1": [item("appStoreVersion", "V1")]}
                for state in sorted(asc_release.LOCKED_MEDIA_STATES):
                    locked = FakeAsc(state=state, screenshots=live, drafts=["SUB1"], draft_items=held)
                    code, output = run_asc(locked, self.ENV)
                    self.assertEqual(code, 0, output)
                    self.assertEqual(locked.writes(), [])
                code, output = run_asc(FakeAsc(screenshots=live), self.ENV, "--preflight")
                self.assertEqual(code, 0, output)
                self.assertIn("already match", output)

    def test_the_preflight_makes_the_rgb_copies_without_writing(self) -> None:
        with self.rgba_sets() as sets:
            before = self.committed(sets)
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV, "--preflight")
            self.assertEqual(self.committed(sets), before)
        self.assertEqual(code, 0, output)
        self.assertIn("will be replaced", output)
        self.assertIn("RGB copies of", output)
        self.assertEqual(fake.writes(), [])


class CreativeAssetTests(unittest.TestCase):
    ENV = {"UPLOAD_CREATIVE_ASSETS": "true"}

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        for folder, payload in (("header", png_header(width=3840, height=1646)),
                                ("search-results", png_header(width=3840, height=2560) + b"search")):
            (self.dir / folder).mkdir()
            (self.dir / folder / f"{folder}.png").write_bytes(payload)

    def image(self, image_id: str, folder: str, state: str) -> dict:
        path = self.dir / folder / f"{folder}.png"
        return {"id": image_id, "attributes": {"fileName": path.name, "fileSize": path.stat().st_size, "state": state,
                                               "createdDate": "2026-10-20"}}

    def run_creative(self, fake: FakeAsc) -> int:
        code, _ = run_asc(fake, self.ENV, creative=self.dir)
        return code

    def test_new_images_are_uploaded_and_reviewed_on_their_own(self) -> None:
        fake = FakeAsc()
        self.assertEqual(self.run_creative(fake), 0)
        uploads = [Path(flag(c, "--file")).name for c in fake.writes() if c[:3] == ("asset-library", "images", "upload")]
        self.assertEqual(uploads, ["header.png", "search-results.png"])
        review = [c for c in fake.writes() if c[:1] == ("review",)]
        self.assertEqual([c[:2] for c in review], [("review", "submissions-create"), ("review", "items-add"),
                                                   ("review", "items-add"), ("review", "submissions-submit")])
        self.assertEqual({flag(c, "--item-type") for c in review if c[:2] == ("review", "items-add")},
                         {"appAssetLibraryImages"})
        self.assertFalse([c for c in fake.writes() if c[:2] == ("localizations", "placements")])

    def test_images_never_take_the_empty_draft_the_version_chose(self) -> None:
        # The version's draft is picked first; an empty leftover draft goes to the
        # version, and the images get a submission of their own.
        fake = FakeAsc(drafts=["EMPTY"], draft_items={"EMPTY": []})
        code, output = run_asc(fake, {**self.ENV, **SUBMIT}, *BUILD_ARGS, creative=self.dir)
        self.assertEqual(code, 0, output)
        submitted = [flag(c, "--id") for c in fake.writes() if c[:2] == ("review", "submissions-submit")]
        self.assertEqual(len(submitted), 2)
        self.assertEqual(submitted[-1], "EMPTY")
        image_items = {flag(c, "--submission") for c in fake.writes()
                       if c[:2] == ("review", "items-add") and flag(c, "--item-type") == "appAssetLibraryImages"}
        self.assertEqual(image_items, {submitted[0]})
        self.assertNotIn("EMPTY", image_items)

    def test_a_rerun_finishes_the_empty_draft_an_interrupted_review_left(self) -> None:
        # A run stopped between creating its draft and adding the images; the
        # rerun uses that draft instead of leaving it behind and starting another.
        fake = FakeAsc(drafts=["EMPTY"], draft_items={"EMPTY": []})
        self.assertEqual(self.run_creative(fake), 0)
        self.assertNotIn(("review", "submissions-create"), [c[:2] for c in fake.writes()])
        self.assertEqual(flag(fake.writes()[-1], "--id"), "EMPTY")
        # A draft already holding some of the images is finished before an empty one.
        library = [self.image("H", "header", "PREPARE_FOR_SUBMISSION"),
                   self.image("S", "search-results", "PREPARE_FOR_SUBMISSION")]
        held = [item("appAssetLibraryImage", "H", "I-H")]
        fake = FakeAsc(library=library, drafts=["EMPTY", "PART"], draft_items={"EMPTY": [], "PART": held})
        self.assertEqual(self.run_creative(fake), 0)
        self.assertEqual({flag(c, "--submission") for c in fake.writes() if c[:2] == ("review", "items-add")}, {"PART"})
        self.assertEqual(flag(fake.writes()[-1], "--id"), "PART")

    def test_an_interrupted_upload_is_uploaded_again(self) -> None:
        # asc never deletes the reservation an interrupted upload leaves behind.
        fake = FakeAsc(library=[self.image("STALE", "header", "AWAITING_UPLOAD")])
        self.assertEqual(self.run_creative(fake), 0)
        uploads = [Path(flag(c, "--file")).name for c in fake.writes() if c[:3] == ("asset-library", "images", "upload")]
        self.assertEqual(uploads, ["header.png", "search-results.png"])
        added = [flag(c, "--item-id") for c in fake.writes() if c[:2] == ("review", "items-add")]
        self.assertNotIn("STALE", added)

    def test_images_in_review_are_neither_reuploaded_nor_placed(self) -> None:
        fake = FakeAsc(library=[self.image("H", "header", "WAITING_FOR_REVIEW"),
                                self.image("S", "search-results", "IN_REVIEW")])
        self.assertEqual(self.run_creative(fake), 0)
        self.assertEqual(fake.writes(), [])

    def test_approved_images_are_placed_and_existing_ones_swapped(self) -> None:
        old = {"id": "PL-old", "attributes": {"placementType": "PRODUCT_PAGE_HEADER_ASSET"},
               "relationships": {"image": {"data": {"id": "OLDIMG"}}}}
        fake = FakeAsc(library=[self.image("H", "header", "APPROVED"), self.image("S", "search-results", "APPROVED")],
                       placements=[old])
        self.assertEqual(self.run_creative(fake), 0)
        swap = [c for c in fake.writes() if c[:3] == ("localizations", "placements", "swap")][0]
        self.assertEqual((flag(swap, "--image-id"), flag(swap, "--placement-id"), flag(swap, "--localization-id")),
                         ("H", "PL-old", "LOC"))
        self.assertIn("--confirm", swap)
        create = [c for c in fake.writes() if c[:3] == ("localizations", "placements", "create")][0]
        self.assertEqual((flag(create, "--image-id"), flag(create, "--placement-type"), flag(create, "--placement-group")),
                         ("S", "APP_STORE_SEARCH_RESULTS_ASSET", "DEFAULT_PROFILE"))
        # A second run finds both placed and writes nothing.
        writes = len(fake.writes())
        self.assertEqual(self.run_creative(fake), 0)
        self.assertEqual(len(fake.writes()), writes)

    def test_an_image_with_alpha_fails_before_any_call(self) -> None:
        for content in (png_header(6), png_header(2, trns=True)):
            (self.dir / "header" / "header.png").write_bytes(content)
            fake = FakeAsc()
            with self.subTest(content=content[25]):
                self.assertEqual(self.run_creative(fake), 1)
                self.assertEqual(fake.calls, [])
        (self.dir / "header" / "header.png").write_bytes(png_header(width=3840, height=1646))
        self.assertEqual(self.run_creative(FakeAsc()), 0)

    def test_images_apple_would_refuse_fail_before_any_call(self) -> None:
        cases = {
            "header": [png_header(width=3840, height=2160), jpeg_header(3840, 1646), b"not an image"],
            "search-results": [png_header(width=3000, height=2000 + 1), png_header(width=1800, height=1200),
                               jpeg_header(4000, 2666)],
        }
        for folder, contents in cases.items():
            original = (self.dir / folder / f"{folder}.png").read_bytes()
            for content in contents:
                (self.dir / folder / f"{folder}.png").write_bytes(content)
                fake = FakeAsc()
                with self.subTest(folder=folder, content=content[:24]):
                    self.assertEqual(self.run_creative(fake), 1)
                    self.assertEqual(fake.calls, [])
            (self.dir / folder / f"{folder}.png").write_bytes(original)

    def test_sizes_apple_accepts_pass(self) -> None:
        good = [("PRODUCT_PAGE_HEADER_ASSET", png_header(width=3840, height=1646)),
                ("PRODUCT_PAGE_HEADER_ASSET", png_header(width=5244, height=2950)),
                ("APP_STORE_SEARCH_RESULTS_ASSET", png_header(width=3840, height=2560)),
                ("APP_STORE_SEARCH_RESULTS_ASSET", jpeg_header(1920, 1280)),
                ("APP_STORE_SEARCH_RESULTS_ASSET", png_header(width=5244, height=2950))]
        for placement, content in good:
            path = self.dir / "probe"
            path.write_bytes(content)
            with self.subTest(placement=placement, content=content[:24]):
                self.assertIsNone(asc_release.creative_size_problem(placement, path))

    def test_an_approved_image_on_a_locked_version_waits_without_failing(self) -> None:
        # Approval can land between the submit and a rerun of the store job.
        library = [self.image("H", "header", "APPROVED"), self.image("S", "search-results", "IN_REVIEW")]
        for state in ("WAITING_FOR_REVIEW", "READY_FOR_REVIEW"):
            fake = FakeAsc(state=state, attached="B9", library=library)
            with self.subTest(state=state):
                code, output = run_asc(fake, self.ENV, creative=self.dir)
                self.assertEqual(code, 0, output)
                self.assertEqual(fake.writes(), [])
                self.assertIn("placed on a later run, once the version can be edited", output)

    def test_a_rejected_image_stops_the_run(self) -> None:
        fake = FakeAsc(library=[self.image("H", "header", "REJECTED")])
        self.assertEqual(self.run_creative(fake), 1)
        self.assertEqual(fake.writes(), [])

    def test_a_locked_version_takes_no_new_images(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9")
        self.assertEqual(self.run_creative(fake), 1)
        self.assertEqual(fake.writes(), [])

    def test_a_locked_version_sends_nothing_to_review(self) -> None:
        for state in ("WAITING_FOR_REVIEW", "READY_FOR_REVIEW"):
            for image_state in ("UPLOAD_COMPLETE", "PREPARE_FOR_SUBMISSION"):
                fake = FakeAsc(state=state, attached="B9", library=[self.image("H", "header", image_state)])
                with self.subTest(state=state, image_state=image_state):
                    self.assertEqual(self.run_creative(fake), 1)
                    self.assertEqual(fake.writes(), [])

    def test_a_locked_version_keeps_approved_images_that_are_already_placed(self) -> None:
        placed = [{"id": f"PL-{kind}", "attributes": {"placementType": kind},
                   "relationships": {"image": {"data": {"id": image_id}}}}
                  for kind, image_id in (("PRODUCT_PAGE_HEADER_ASSET", "H"), ("APP_STORE_SEARCH_RESULTS_ASSET", "S"))]
        fake = FakeAsc(state="READY_FOR_REVIEW", library=[self.image("H", "header", "APPROVED"),
                                                         self.image("S", "search-results", "APPROVED")],
                       placements=placed)
        self.assertEqual(self.run_creative(fake), 0)
        self.assertEqual(fake.writes(), [])

    def test_a_retry_finishes_the_creative_draft_it_left_behind(self) -> None:
        library = [self.image("H", "header", "PREPARE_FOR_SUBMISSION"),
                   self.image("S", "search-results", "PREPARE_FOR_SUBMISSION")]
        fake = FakeAsc(library=library, drafts=["OLD"], draft_items={"OLD": [item("appAssetLibraryImage", "H", "I-H")]})
        self.assertEqual(self.run_creative(fake), 0)
        self.assertNotIn(("review", "submissions-create"), [c[:2] for c in fake.writes()])
        self.assertEqual(fake.submitted, ["OLD"])
        # A draft holding anything else is left alone and a new one is used.
        fake = FakeAsc(library=library, drafts=["VERSION"], draft_items={"VERSION": [item("appStoreVersion", "V1")]})
        self.assertEqual(self.run_creative(fake), 0)
        self.assertNotEqual(fake.submitted, ["VERSION"])
        self.assertEqual(len(fake.submitted), 1)

    def test_a_creative_submission_with_other_items_is_not_submitted(self) -> None:
        fake = FakeAsc()
        fake_run = fake.run

        def run(*args, **kwargs):
            result = fake_run(*args, **kwargs)
            if args[:2] == ("review", "submissions-create"):
                fake.items[result["data"]["id"]] = [item("appStoreVersion", "V1", "X")]
            return result

        fake.run = run  # type: ignore[method-assign]
        self.assertEqual(self.run_creative(fake), 1)
        self.assertEqual(fake.submitted, [])


@functools.lru_cache(maxsize=None)
def rgb_png(width: int, height: int) -> bytes:
    """A complete, decodable RGB PNG (unlike png_header, whose image data is a stub)."""
    import zlib

    def chunk(kind: bytes, body: bytes) -> bytes:
        return len(body).to_bytes(4, "big") + kind + body + zlib.crc32(kind + body).to_bytes(4, "big")

    ihdr = width.to_bytes(4, "big") + height.to_bytes(4, "big") + bytes([8, 2, 0, 0, 0])
    pixels = zlib.compress((b"\x00" + b"\x80" * (width * 3)) * height, 6)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", pixels) + chunk(b"IEND", b"")


def full_jpeg(width: int, height: int) -> bytes:
    """A well-formed JPEG layout: APP0, a one-component baseline frame, a scan and the end marker."""
    app0 = b"\xff\xe0" + (16).to_bytes(2, "big") + b"JFIF\x00" + bytes(9)
    sof0 = b"\xff\xc0" + (11).to_bytes(2, "big") + b"\x08" + height.to_bytes(2, "big") + width.to_bytes(2, "big") + b"\x01\x01\x11\x00"
    sos = b"\xff\xda" + (8).to_bytes(2, "big") + b"\x01\x01\x00\x00\x3f\x00"
    return b"\xff\xd8" + app0 + sof0 + sos + b"\x12\x34" * 64 + b"\xff\xd9"


TERRITORIES = ["USA", "CAN", "GBR"]
PRICE_GRID = {"0.99", "1.99", "2.99", "4.99", "8.99", "9.99", "69.99"}
PID = "com.apoorvdarshan.verceltics"
SHOT = "store/catalog/review/shot.png"


class ProductAsc(FakeAsc):
    """FakeAsc plus App Store products: groups, subscriptions, in-app purchases and their parts.

    Products created here land in FakeAsc's subscription and IAP lists, so the
    review submission in asc_release sees them in the same run.
    """

    def __init__(self, *, groups: dict[str, dict[str, str]] | None = None, prices: dict[str, str] | None = None,
                 fail_on: set[tuple[str, ...]] | None = None, stuck: bool = False, **kwargs: object) -> None:
        super().__init__(**kwargs)  # type: ignore[arg-type]
        # reference name -> {locale: display name}
        self.groups = {name: dict(locales) for name, locales in (groups or {"Verceltics Pro": {"en-US": "Verceltics Pro"}}).items()}
        self.prices = dict(prices or {})  # live id -> current USA price
        self.parts: dict[str, dict[str, object]] = {}
        self.fail_on = set(fail_on or set())
        self.stuck = stuck  # App Store Connect never reports Ready to Submit

    # -- helpers ------------------------------------------------------------
    def product(self, live_id: str) -> dict:
        return next(p for p in [*self.subscriptions, *self.iaps] if p["id"] == live_id)

    def by_product_id(self, product_id: str) -> list[dict]:
        return [p for p in [*self.subscriptions, *self.iaps] if p["attributes"]["productId"] == product_id]

    def part(self, live_id: str) -> dict[str, object]:
        return self.parts.setdefault(live_id, {})

    def restate(self, live_id: str) -> None:
        a = self.product(live_id)["attributes"]
        if a.get("state") not in (None, "MISSING_METADATA", "READY_TO_SUBMIT"):
            return
        parts = self.part(live_id)
        complete = all(parts.get(k) for k in ("localization", "availability", "screenshot")) and (
            live_id in self.prices)
        a["state"] = "READY_TO_SUBMIT" if complete and not self.stuck else "MISSING_METADATA"

    @staticmethod
    def conflict(args: tuple[str, ...], what: str) -> asc_cli.AscError:
        return asc_cli.AscError(args, 5, f"existing {what} differs")

    # -- asc ----------------------------------------------------------------
    def run(self, *args: str, **kwargs: object):
        for prefix in self.fail_on:
            if args[:len(prefix)] == prefix:
                self.calls.append(args)
                raise asc_cli.AscError(args, 63, "service unavailable")
        try:
            handled = self.products_run(args)
        except asc_cli.AscError:
            self.calls.append(args)  # an attempted call that App Store Connect refused
            raise
        if handled is not None:
            self.calls.append(args)
            return handled
        return super().run(*args, **kwargs)

    def products_run(self, args: tuple[str, ...]):  # noqa: C901 - one fake endpoint per branch
        if args[:3] == ("pricing", "territories", "list"):
            return {"data": [{"type": "territories", "id": t} for t in TERRITORIES]}
        if args[:2] == ("pricing", "price-points"):
            return {"data": [{"id": f"APP-{p}", "attributes": {"customerPrice": p}} for p in sorted(PRICE_GRID | {"0.0"})]}
        if args[:4] in {("subscriptions", "pricing", "price-points", "list"), ("iap", "pricing", "price-points", "list")}:
            price = flag(args, "--price")
            return {"data": [{"id": f"PP-{price}", "attributes": {"customerPrice": price}}] if price in PRICE_GRID else []}
        if args[:3] == ("subscriptions", "groups", "list"):
            data, included = [], []
            for n, (name, locales) in enumerate(self.groups.items()):
                refs = [{"type": "subscriptionGroupLocalizations", "id": f"GL{n}-{loc}"} for loc in locales]
                data.append({"id": f"G{n}", "attributes": {"referenceName": name},
                             "relationships": {"subscriptionGroupLocalizations": {"data": refs}}})
                included += [{"type": "subscriptionGroupLocalizations", "id": f"GL{n}-{loc}",
                              "attributes": {"locale": loc, "name": label}} for loc, label in locales.items()]
            return {"data": data, "included": included}
        if args[:3] == ("subscriptions", "pricing", "summary"):
            live = flag(args, "--subscription-id")
            row = {"id": live}
            if live in self.prices:
                row["currentPrice"] = {"amount": self.prices[live], "currency": "USD"}
            return {"subscriptions": [row]}
        if args[:3] == ("iap", "pricing", "summary"):
            live = flag(args, "--iap-id")
            if live not in self.prices:
                raise asc_cli.AscError(args, 4, "no price schedule")
            return {"iaps": [{"id": live, "baseTerritory": "USA", "currentPrice": {"amount": self.prices[live]}}]}
        if args[:2] == ("subscriptions", "setup"):
            return self.setup(args)
        if args[:4] == ("subscriptions", "versions", "localizations", "list"):
            loc = self.part(flag(args, "--version-id")[:-2]).get("localization")
            return {"data": [{"id": "L", "attributes": loc}] if loc else []}
        if args[:4] == ("subscriptions", "pricing", "availability", "view"):
            live = flag(args, "--subscription-id")
            availability = self.part(live).get("availability")
            if not availability:
                raise asc_cli.AscError(args, 4, "no availability")
            return {"data": {"id": f"AV-{live}", "attributes": {"availableInNewTerritories": availability[1]}}}
        if args[:4] == ("subscriptions", "pricing", "availability", "available-territories"):
            return {"data": [{"id": t} for t in self.part(flag(args, "--availability-id")[3:])["availability"][0]]}  # type: ignore[index]
        if args[:4] == ("subscriptions", "review", "app-store-screenshot", "view"):
            shot = self.part(flag(args, "--subscription-id")).get("screenshot")
            if not shot:
                raise asc_cli.AscError(args, 4, "no screenshot")
            return {"data": shot}
        if args[:2] in {("subscriptions", "view"), ("iap", "view")}:
            # App Store Connect works the state out from the parts a product has.
            self.restate(flag(args, "--id"))
            return {"data": self.product(flag(args, "--id"))}
        if args[:2] == ("subscriptions", "update"):
            self.product(flag(args, "--id"))["attributes"]["reviewNote"] = flag(args, "--review-note")
            return {"data": {}}
        if args[:2] == ("iap", "create"):
            product_id = flag(args, "--product-id")
            if self.by_product_id(product_id):
                raise asc_cli.AscError(args, 5, "product id already exists")
            attributes = {"name": flag(args, "--ref-name"), "productId": product_id,
                          "inAppPurchaseType": flag(args, "--type"), "state": "MISSING_METADATA"}
            if "--family-sharable" in args:
                attributes["familySharable"] = True
            new = {"id": f"IAP-{product_id.rsplit('.', 1)[-1]}", "attributes": attributes}
            self.iaps.append(new)
            self.parts[new["id"]] = {}
            return {"data": new}
        if args[:3] == ("iap", "versions", "list") and flag(args, "--iap-id") in self.parts:
            live = flag(args, "--iap-id")
            loc = self.part(live).get("localization")
            version = {"id": f"{live}v1", "attributes": {"version": 1},
                       "relationships": {"localizations": {"data": [{"id": f"{live}-loc"}] if loc else []}}}
            included = [{"type": "inAppPurchaseLocalizations", "id": f"{live}-loc", "attributes": loc}] if loc else []
            return {"data": [version], "included": included}
        if args[:4] == ("iap", "versions", "localizations", "create"):
            live = flag(args, "--version-id")[:-2]
            self.part(live)["localization"] = {"locale": flag(args, "--locale"), "name": flag(args, "--name"),
                                               "description": flag(args, "--description")}
            return {"data": {"id": f"{live}-loc"}}
        if args[:4] == ("iap", "pricing", "schedules", "create"):
            live = flag(args, "--iap-id")
            assert flag(args, "--base-territory") == "USA"
            self.prices[live] = flag(args, "--price")
            return {"data": {"id": live}}
        if args[:4] == ("iap", "pricing", "availability", "view"):
            availability = self.part(flag(args, "--iap-id")).get("availability")
            if not availability:
                raise asc_cli.AscError(args, 4, "no availability")
            return {"data": {"id": f"AV-{flag(args, '--iap-id')}",
                             "attributes": {"availableInNewTerritories": availability[1]}}}
        if args[:4] == ("iap", "pricing", "availabilities", "available-territories"):
            live = flag(args, "--id")[3:]
            return {"data": [{"id": t} for t in self.part(live)["availability"][0]]}  # type: ignore[index]
        if args[:4] == ("iap", "pricing", "availability", "set"):
            live = flag(args, "--iap-id")
            self.part(live)["availability"] = (flag(args, "--territories").split(","), "--available-in-new-territories" in args)
            return {"data": {}}
        if args[:3] == ("iap", "review-screenshots", "view"):
            shot = self.part(flag(args, "--iap-id")).get("screenshot")
            if not shot:
                raise asc_cli.AscError(args, 4, "no screenshot")
            return {"data": shot}
        if args[:3] == ("iap", "review-screenshots", "create"):
            live = flag(args, "--iap-id")
            raw = Path(flag(args, "--file")).read_bytes()
            self.part(live)["screenshot"] = {"id": f"SHOT-{live}", "attributes": {
                "fileSize": len(raw), "sourceFileChecksum": hashlib.md5(raw).hexdigest(),
                "assetDeliveryState": {"state": "COMPLETE"}}}
            self.restate(live)
            return {"data": self.part(live)["screenshot"]}
        if args[:3] == ("iap", "review-screenshots", "update"):
            # Resumes the upload reservation an interrupted run left.
            shot_id = flag(args, "--screenshot-id")
            live = next(k for k, v in self.parts.items() if (v.get("screenshot") or {}).get("id") == shot_id)
            raw = Path(flag(args, "--file")).read_bytes()
            self.part(live)["screenshot"] = {"id": shot_id, "attributes": {
                "fileSize": len(raw), "sourceFileChecksum": hashlib.md5(raw).hexdigest(),
                "assetDeliveryState": {"state": "COMPLETE"}}}
            self.restate(live)
            return {"data": self.part(live)["screenshot"]}
        if args[:2] == ("iap", "versions"):
            raise AssertionError(f"unexpected product call {args}")
        return None

    def setup(self, args: tuple[str, ...]):
        """asc subscriptions setup: find or create the group and subscription, fill missing parts, stop on conflicts."""
        group = flag(args, "--group-reference-name")
        locales = self.groups.setdefault(group, {})
        if "--group-display-name" in args:
            wanted = flag(args, "--group-display-name")
            if locales.get("en-US", wanted) != wanted:
                raise self.conflict(args, "group localization")
            locales["en-US"] = wanted
        product_id = flag(args, "--product-id")
        found = [s for s in self.subscriptions if s["attributes"]["productId"] == product_id]
        if found:
            sub = found[0]
            a = sub["attributes"]
            if (a["name"] != flag(args, "--reference-name") or a.get("subscriptionPeriod") != flag(args, "--subscription-period")
                    or bool(a.get("familySharable")) != ("--family-sharable" in args)):
                raise self.conflict(args, "subscription")
        elif self.by_product_id(product_id):
            raise asc_cli.AscError(args, 5, "product id already exists")
        else:
            attributes = {"name": flag(args, "--reference-name"), "productId": product_id, "state": "MISSING_METADATA",
                          "subscriptionPeriod": flag(args, "--subscription-period")}
            if "--family-sharable" in args:
                attributes["familySharable"] = True
            sub = {"id": f"SUB-{product_id.rsplit('.', 1)[-1]}", "attributes": attributes}
            self.subscriptions.append(sub)
        live = sub["id"]
        parts = self.part(live)
        wanted_parts = {
            "localization": {"locale": flag(args, "--locale"), "name": flag(args, "--display-name"),
                             "description": flag(args, "--description")},
            "availability": (flag(args, "--territories").split(","), "--available-in-new-territories" in args),
        }
        for name, value in wanted_parts.items():
            if parts.get(name) not in (None, value):
                raise self.conflict(args, name)
            parts[name] = value
        if self.prices.get(live, flag(args, "--price")) != flag(args, "--price"):
            raise self.conflict(args, "price")
        self.prices[live] = flag(args, "--price")
        if "--review-screenshot" in args:
            raw = Path(flag(args, "--review-screenshot")).read_bytes()
            existing = parts.get("screenshot")
            if existing and existing["attributes"]["sourceFileChecksum"] != hashlib.md5(raw).hexdigest():  # type: ignore[index]
                raise self.conflict(args, "review screenshot")
            parts["screenshot"] = {"id": f"SHOT-{live}", "attributes": {
                "sourceFileChecksum": hashlib.md5(raw).hexdigest(), "assetDeliveryState": {"state": "COMPLETE"}}}
        self.restate(live)
        if "--no-verify" not in args and sub["attributes"]["state"] != "READY_TO_SUBMIT":
            raise asc_cli.AscError(args, 1, "subscriptions setup: verify_state: MISSING_METADATA")
        return {"status": "ok", "subscriptionId": live}

    def live_iap(self, product_id: str, *, state: str = "MISSING_METADATA", type_: str = "CONSUMABLE",
                 name: str = "Probe", parts: dict | None = None, price: str | None = None) -> str:
        """Put an existing in-app purchase (e.g. one an interrupted run left) into the fake."""
        live = f"IAP-{product_id.rsplit('.', 1)[-1]}"
        self.iaps.append({"id": live, "attributes": {"name": name, "productId": product_id,
                                                      "inAppPurchaseType": type_, "state": state}})
        self.parts[live] = dict(parts or {})
        if price:
            self.prices[live] = price
        return live


class ReadOnlyView:
    """What the plan sees: the same fake, but any write fails the test (asc refuses writes there)."""

    def __init__(self, fake: ProductAsc) -> None:
        self.fake = fake

    def run(self, *args: str, **kwargs: object):
        if is_write(args):
            raise AssertionError(f"the read-only plan wrote: {args}")
        return self.fake.run(*args, **kwargs)


def ios_item(name: str, **ios: object) -> dict:
    base = {"reference_name": name.title(), "display_name": f"Verceltics {name.title()}",
            "description": "Pro dashboards and alerts", "review_screenshot": SHOT}
    base.update(ios)
    return base


def product_catalog(*, subscriptions=(), consumables=(), non_consumables=(), tips=(), groups=None) -> dict:
    catalog = {
        "uses_non_exempt_encryption": False,
        "ios_subscription_groups": groups if groups is not None else [{"reference_name": "Verceltics Pro"}],
        "subscriptions": list(subscriptions),
        "consumables": list(consumables),
        "tips": list(tips),
    }
    if non_consumables:
        catalog["non_consumables"] = list(non_consumables)
    return catalog


def new_subscription(suffix: str = "plus.weekly", *, period: str = "P1W", price: str = "2.99", **ios: object) -> dict:
    fields = {"review_note": "Optional Pro plan.", "subscription_group": "Verceltics Pro", **ios}
    return {"id": f"{PID}.{suffix}", "period": period, "suggested_price_usd": price, "platforms": ["ios"],
            "ios": ios_item(suffix.split(".")[0], **fields)}


def new_iap(suffix: str, *, price: str = "1.99", **ios: object) -> dict:
    return {"id": f"{PID}.{suffix}", "suggested_price_usd": price, "platforms": ["ios"],
            "ios": ios_item(suffix.split(".")[0], **ios)}


class ProductTestCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "store" / "catalog" / "review").mkdir(parents=True)
        self.shot(SHOT)
        self.sleeps: list[float] = []
        self.modes: list[bool] = []  # read_only of every asc the script asked for

    def shot(self, relative: str, content: bytes | None = None) -> Path:
        path = self.root / relative
        path.write_bytes(content if content is not None else rgb_png(1290, 2796))
        return path

    def run_products(self, fake: ProductAsc, catalog: dict, command: str = "create",
                     env: dict[str, str] | None = None) -> tuple[int, str, str]:
        catalog_path = self.root / "products.json"
        catalog_path.write_text(json.dumps(catalog))
        environ = {k: v for k, v in os.environ.items() if k != "CREATE_IOS_PRODUCTS"}
        environ.update({"CREATE_IOS_PRODUCTS": "true"} if env is None else env)
        out, err = io.StringIO(), io.StringIO()
        code = 0
        with mock.patch.dict(os.environ, environ, clear=True), \
                mock.patch.object(asc_release, "asc_from_environment", side_effect=lambda read_only=False: (
                    self.modes.append(read_only), ReadOnlyView(fake) if read_only else fake)[1]), \
                mock.patch.object(asc_products, "ROOT", self.root), redirect_stdout(out), redirect_stderr(err):
            try:
                asc_products.main([command, "--catalog", str(catalog_path)], sleep=self.sleeps.append)
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
        return code, out.getvalue(), err.getvalue()

    def submit_for_review(self, fake: FakeAsc) -> tuple[int, str]:
        """asc_release's review submission (SUBMIT_IOS_REVIEW) against fake, reading the catalog under self.root."""
        with mock.patch.object(asc_release, "ROOT", self.root):
            return run_asc(fake, SUBMIT, *BUILD_ARGS)

    def writes(self, fake: ProductAsc) -> list[tuple[str, ...]]:
        return [c[:4] if c[:2] in {("iap", "pricing"), ("iap", "versions")} else c[:3] if c[0] == "iap" else c[:2]
                for c in fake.writes()]


class ProductGateTests(unittest.TestCase):
    def test_create_products_needs_the_master_switch_and_a_tag_run(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "true", "IOS_CREATE_PRODUCTS": "true"})
        self.assertTrue(result["create_products"])
        self.assertTrue(result["product_plan"])
        self.assertFalse(result["build"])
        self.assertFalse(result["asc"])
        off = gates({"IOS_CREATE_PRODUCTS": "true"})
        self.assertFalse(off["create_products"])
        self.assertFalse(off["product_plan"])
        self.assertTrue(any("IOS_CREATE_PRODUCTS" in note for note in off["notes"]))
        self.assertFalse(gates({})["product_plan"])

    def test_invalid_values_are_rejected_even_when_the_master_is_off(self) -> None:
        for env in ({"IOS_CREATE_PRODUCTS": "maybe"}, {"IOS_RELEASE_AUTOMATION": "true", "IOS_CREATE_PRODUCTS": "2"}):
            with self.subTest(env=env), self.assertRaisesRegex(ReleaseError, "IOS_CREATE_PRODUCTS"):
                gates(env)

    def test_validation_runs_only_plan(self) -> None:
        env = {"IOS_RELEASE_AUTOMATION": "true", "IOS_CREATE_PRODUCTS": "true"}
        result = gates(env, event="workflow_dispatch", ref="main")
        self.assertFalse(result["create_products"])
        self.assertTrue(result["product_plan"])
        self.assertTrue(any("read-only" in note for note in result["notes"]))
        self.assertFalse(gates({"IOS_CREATE_PRODUCTS": "true"}, event="workflow_dispatch", ref="main")["product_plan"])
        self.assertFalse(gates({"IOS_RELEASE_AUTOMATION": "true"}, event="workflow_dispatch", ref="main")["product_plan"])

    def test_print_gates_lists_the_switch(self) -> None:
        clean = {k: v for k, v in os.environ.items() if not k.startswith(("IOS_", "ANDROID_", "STORE_"))}
        for value, shown in (("true", "IOS_CREATE_PRODUCTS=true\n"), ("maybe", "IOS_CREATE_PRODUCTS=INVALID"),
                             (None, "IOS_CREATE_PRODUCTS=false (unset)")):
            env = dict(clean) if value is None else {**clean, "IOS_CREATE_PRODUCTS": value}
            out = io.StringIO()
            with mock.patch.dict(os.environ, env, clear=True), \
                    mock.patch.object(sys, "argv", ["print_gates.py", "--platform", "ios"]), redirect_stdout(out):
                print_gates.main()
            with self.subTest(value=value):
                self.assertIn(shown, out.getvalue())


class CatalogFieldTests(ProductTestCase):
    def problems(self, catalog: dict) -> list[str]:
        return asc_products.catalog_problems(catalog, self.root)

    def test_the_repository_catalog_is_valid(self) -> None:
        with redirect_stdout(io.StringIO()) as out:
            validate_catalog.main(["--platform", "ios"])
        self.assertIn("catalog products match Products.storekit", out.getvalue())
        catalog = validate_catalog.load_catalog()
        self.assertEqual(asc_products.catalog_problems(catalog), [])
        self.assertEqual(asc_release.CATALOG_PRODUCT_GROUPS, tuple(asc_products.PRODUCT_GROUPS))
        self.assertEqual(validate_catalog.GROUPS, tuple(asc_products.PRODUCT_GROUPS))
        # Every product the app sells on iOS is one the release can create, compare and submit.
        self.assertEqual({p.product_id for p in asc_products.ios_products(catalog)}, asc_release.intended_product_ids())
        self.assertEqual(asc_release.intended_product_ids(), set(validate_catalog.catalog_product_ids(catalog, "ios")))

    def test_validate_catalog_reports_app_store_field_problems(self) -> None:
        catalog = validate_catalog.load_catalog()
        ids = set(validate_catalog.catalog_product_ids(catalog, "ios"))
        broken = json.loads(json.dumps(catalog))
        broken["tips"][0]["ios"] = {"colour": "red"}
        with self.assertRaisesRegex(validate_catalog.CatalogError, "App Store product fields: .*unknown ios keys colour"):
            validate_catalog.check(broken, platform="ios", app_ids=ids)
        del broken["subscriptions"][0]["period"]
        broken["tips"][0].pop("ios")
        with self.assertRaisesRegex(validate_catalog.CatalogError, "period must be one of"):
            validate_catalog.check(broken, platform="ios", app_ids=ids)

    def test_complete_fields_pass_and_existing_products_need_none(self) -> None:
        catalog = product_catalog(subscriptions=[new_subscription()], consumables=[new_iap("credits.75")],
                                  non_consumables=[new_iap("lifetime", family_sharing=True)],
                                  tips=[{"id": f"{PID}.tip.snack", "suggested_price_usd": "0.99", "platforms": ["ios"]}])
        self.assertEqual(self.problems(catalog), [])

    def test_malformed_fields_are_reported(self) -> None:
        cases = {
            "unknown ios keys": new_iap("credits.75", colour="red"),
            "only applies to subscriptions": new_iap("credits.75", review_note="Tips unlock nothing."),
            "family sharing is only": new_iap("credits.75", family_sharing=True),
            "ios.territories": new_iap("credits.75", territories=["US"]),
            "ios.display_name must be 2-30": new_iap("credits.75", display_name="x" * 31),
            "ios.description must be 1-45": new_iap("credits.75", description="x" * 46),
            "leading or trailing whitespace": new_iap("credits.75", reference_name=" Credits"),
            "under store/": new_iap("credits.75", review_screenshot="../shot.png"),
            "does not exist": new_iap("credits.75", review_screenshot="store/catalog/review/missing.png"),
            "suggested_price_usd": new_iap("credits.75", price="1.9"),
        }
        for expected, item in cases.items():
            with self.subTest(expected):
                self.assertTrue(any(expected in p for p in self.problems(product_catalog(consumables=[item]))),
                                self.problems(product_catalog(consumables=[item])))
        sub = new_subscription(subscription_group="Elsewhere")
        self.assertTrue(any("not in ios_subscription_groups" in p for p in self.problems(product_catalog(subscriptions=[sub]))))
        bad_period = {**new_subscription(), "period": "P5D"}
        self.assertTrue(any("period must be" in p for p in self.problems(product_catalog(subscriptions=[bad_period]))))
        android_only = {**new_iap("credits.75"), "platforms": ["android"]}
        self.assertTrue(any("does not list ios" in p for p in self.problems(product_catalog(consumables=[android_only]))))

    def test_review_screenshots_must_be_rgb_and_a_size_apple_accepts(self) -> None:
        cases = {
            "alpha channel": png_header(6, width=1290, height=2796),
            "transparency": png_header(2, trns=True, width=1290, height=2796),
            "not an iPhone or iPad screenshot size": png_header(width=1000, height=2000),
        }
        good = rgb_png(1290, 2796)
        damaged = bytearray(good)
        damaged[60] ^= 0xFF
        cases.update({
            "is damaged: it ends inside its IDAT chunk": good[: len(good) // 2],
            "is damaged: it has a damaged IDAT chunk": bytes(damaged),
        })
        for expected, content in cases.items():
            self.shot("store/catalog/review/bad.png", content)
            item = new_iap("credits.75", review_screenshot="store/catalog/review/bad.png")
            with self.subTest(expected):
                problems = self.problems(product_catalog(consumables=[item]))
                self.assertTrue(any("alpha channel" in p or expected in p for p in problems), problems)
        self.shot("store/catalog/review/cut.jpg", full_jpeg(1179, 2556)[:-40])
        problems = self.problems(product_catalog(consumables=[new_iap("credits.75", review_screenshot="store/catalog/review/cut.jpg")]))
        self.assertTrue(any("ends before its end marker" in p for p in problems), problems)
        self.shot("store/catalog/review/jpeg.png", jpeg_header(1179, 2556))
        problems = self.problems(product_catalog(consumables=[new_iap("credits.75", review_screenshot="store/catalog/review/jpeg.png")]))
        self.assertTrue(any("is a JPEG image named .png" in p for p in problems), problems)
        for good in (rgb_png(2796, 1290), rgb_png(2064, 2752), full_jpeg(1179, 2556)):
            name = "store/catalog/review/good.jpg" if good.startswith(b"\xff\xd8") else "store/catalog/review/good.png"
            self.shot(name, good)
            self.assertEqual(self.problems(product_catalog(consumables=[new_iap("credits.75", review_screenshot=name)])), [])


class ProductCreationTests(ProductTestCase):
    def assert_ready(self, fake: ProductAsc, product_id: str) -> dict:
        found = fake.by_product_id(product_id)
        self.assertEqual(len(found), 1, f"{product_id} must exist exactly once")
        self.assertEqual(found[0]["attributes"]["state"], "READY_TO_SUBMIT")
        return found[0]

    def test_the_plan_always_runs_asc_read_only(self) -> None:
        catalog = product_catalog(consumables=[new_iap("credits.75")])
        code, _, err = self.run_products(ProductAsc(), catalog, command="plan")
        self.assertEqual((code, self.modes), (0, [True]), err)
        self.modes.clear()
        code, _, err = self.run_products(ProductAsc(), catalog)
        self.assertEqual(code, 0, err)
        self.assertEqual(self.modes, [True, False], "the plan reads read-only; only creation gets a writing asc")

    def test_products_not_sold_on_ios_are_never_planned_or_created(self) -> None:
        android_only = {"id": f"{PID}.android.only", "suggested_price_usd": "1.99", "platforms": ["android"]}
        catalog = product_catalog(consumables=[new_iap("credits.75")], non_consumables=[android_only])
        fake = ProductAsc()
        code, out, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        self.assertNotIn("android.only", out + err)
        self.assertEqual(fake.by_product_id(f"{PID}.android.only"), [])
        self.assert_ready(fake, f"{PID}.credits.75")

    def test_switch_off_makes_no_calls(self) -> None:
        fake = ProductAsc()
        code, out, _ = self.run_products(fake, product_catalog(subscriptions=[new_subscription()]), env={})
        self.assertEqual(code, 0)
        self.assertIn("CREATE_IOS_PRODUCTS is off", out)
        self.assertEqual(fake.calls, [])

    def test_a_validation_run_lists_new_products_and_writes_nothing(self) -> None:
        fake = ProductAsc()
        catalog = product_catalog(subscriptions=[new_subscription()], consumables=[new_iap("credits.75")])
        for env in ({}, {"CREATE_IOS_PRODUCTS": "true"}):
            code, out, err = self.run_products(fake, catalog, command="plan", env=env)
            with self.subTest(env=env):
                self.assertEqual(code, 0, err)
                self.assertIn(f"would create subscription {PID}.plus.weekly", out)
                self.assertIn(f"would create consumable {PID}.credits.75", out)
                self.assertIn("nothing was created", out)
                self.assertEqual(fake.writes(), [])

    def test_creates_a_subscription_in_an_existing_group(self) -> None:
        fake = ProductAsc()
        code, out, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription()]))
        self.assertEqual(code, 0, err)
        sub = self.assert_ready(fake, f"{PID}.plus.weekly")
        self.assertEqual(sub["attributes"]["reviewNote"], "Optional Pro plan.")
        self.assertNotIn("familySharable", sub["attributes"])
        writes = fake.writes()
        self.assertEqual([w[:2] for w in writes], [("subscriptions", "setup"), ("subscriptions", "update"),
                                                   ("subscriptions", "setup")])
        first, last = writes[0], writes[-1]
        # The note is set while the subscription still lacks its screenshot, so it
        # cannot reach Ready to Submit without it.
        self.assertIn("--no-verify", first)
        self.assertNotIn("--review-screenshot", first)
        self.assertNotIn("--no-verify", last)
        self.assertEqual(Path(flag(last, "--review-screenshot")), self.root / SHOT)
        for call in (first, last):
            self.assertEqual((flag(call, "--app"), flag(call, "--group-reference-name"), flag(call, "--subscription-period"),
                              flag(call, "--price"), flag(call, "--price-territory"), flag(call, "--territories"),
                              flag(call, "--locale"), flag(call, "--reference-name")),
                             (APP, "Verceltics Pro", "ONE_WEEK", "2.99", "USA", "USA,CAN,GBR", "en-US", "Plus"))
            self.assertIn("--available-in-new-territories", call)
            self.assertNotIn("--family-sharable", call)
            # The group already has an en-US name, which is never changed.
            self.assertNotIn("--group-display-name", call)
        self.assertIn("Ready to Submit", out)
        self.assertIn("RevenueCat", out)

    def test_creates_a_subscription_group_that_does_not_exist_yet(self) -> None:
        groups = [{"reference_name": "Verceltics Pro"}, {"reference_name": "Coaching", "display_name": "Verceltics Coaching"}]
        sub = new_subscription("coach.monthly", period="P1M", price="8.99", subscription_group="Coaching",
                               family_sharing=True, territories=["USA", "CAN"])
        fake = ProductAsc()
        code, _, err = self.run_products(fake, product_catalog(subscriptions=[sub], groups=groups))
        self.assertEqual(code, 0, err)
        self.assertEqual(fake.groups["Coaching"], {"en-US": "Verceltics Coaching"})
        created = self.assert_ready(fake, f"{PID}.coach.monthly")
        self.assertTrue(created["attributes"]["familySharable"])
        setup = fake.writes()[0]
        self.assertEqual((flag(setup, "--group-display-name"), flag(setup, "--group-locale")), ("Verceltics Coaching", "en-US"))
        self.assertEqual(flag(setup, "--territories"), "USA,CAN")
        self.assertNotIn("--available-in-new-territories", setup)
        self.assertIn("--family-sharable", setup)

    def test_creates_each_in_app_purchase_type(self) -> None:
        catalog = product_catalog(consumables=[new_iap("credits.75", price="2.99")], tips=[new_iap("tip.coffee", price="0.99")],
                                  non_consumables=[new_iap("lifetime", price="69.99", family_sharing=True)])
        fake = ProductAsc()
        code, out, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        expected = {"credits.75": ("CONSUMABLE", "2.99", False), "tip.coffee": ("CONSUMABLE", "0.99", False),
                    "lifetime": ("NON_CONSUMABLE", "69.99", True)}
        for suffix, (iap_type, price, shared) in expected.items():
            with self.subTest(suffix):
                live = self.assert_ready(fake, f"{PID}.{suffix}")
                self.assertEqual(live["attributes"]["inAppPurchaseType"], iap_type)
                self.assertEqual(fake.prices[live["id"]], price)
                self.assertEqual(bool(live["attributes"].get("familySharable")), shared)
                calls = [c for c in fake.writes() if live["id"] in c or f"{live['id']}v1" in c or f"{PID}.{suffix}" in c]
                kinds = [c[:4] if c[1] in {"pricing", "versions"} else c[:3] for c in calls]
                # The screenshot, which completes the product, always goes last.
                self.assertEqual(kinds, [("iap", "create", "--app"), ("iap", "versions", "localizations", "create"),
                                         ("iap", "pricing", "schedules", "create"), ("iap", "pricing", "availability", "set"),
                                         ("iap", "review-screenshots", "create")])
                create = calls[0]
                self.assertEqual(flag(create, "--type"), iap_type)
                self.assertEqual("--family-sharable" in create, shared)
                availability = calls[3]
                self.assertEqual(flag(availability, "--territories"), "USA,CAN,GBR")
                self.assertIn("--available-in-new-territories", availability)
                loc = fake.parts[live["id"]]["localization"]
                self.assertEqual(loc, {"locale": "en-US", "name": f"Verceltics {suffix.split('.')[0].title()}",
                                       "description": "Pro dashboards and alerts"})
        self.assertIn("would create non-consumable", out)

    def test_existing_products_are_compared_and_never_written(self) -> None:
        subs = [{"id": "S1", "attributes": {"name": "Plus Monthly", "productId": f"{PID}.plus.monthly",
                                            "state": "APPROVED", "subscriptionPeriod": "ONE_YEAR"}}]
        iaps = [{"id": "I1", "attributes": {"name": "Credits 50", "productId": f"{PID}.credits.50",
                                            "inAppPurchaseType": "NON_CONSUMABLE", "state": "APPROVED"}},
                {"id": "I2", "attributes": {"name": "Tip", "productId": f"{PID}.tip.snack",
                                            "inAppPurchaseType": "CONSUMABLE", "state": "MISSING_METADATA"}},
                {"id": "I3", "attributes": {"name": "Pro", "productId": f"{PID}.pro.monthly",
                                            "inAppPurchaseType": "NON_CONSUMABLE", "state": "APPROVED"}}]
        fake = ProductAsc(subscriptions=subs, iaps=iaps, prices={"S1": "9.99", "I1": "1.99", "I3": "19.99"})
        catalog = product_catalog(
            subscriptions=[{"id": f"{PID}.plus.monthly", "period": "P1M", "suggested_price_usd": "8.99", "platforms": ["ios"]},
                           {"id": f"{PID}.pro.monthly", "period": "P1M", "suggested_price_usd": "19.99", "platforms": ["ios"]}],
            consumables=[{"id": f"{PID}.credits.50", "suggested_price_usd": "1.99", "platforms": ["ios"],
                          "ios": {"family_sharing": False}}],
            tips=[{"id": f"{PID}.tip.snack", "suggested_price_usd": "0.99", "platforms": ["ios"]}])
        code, out, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        self.assertEqual(fake.writes(), [])
        for text in ("period is ONE_YEAR in App Store Connect, ONE_MONTH in the catalog",
                     "price is 9.99 in USA in App Store Connect, 8.99 in the catalog",
                     "type is NON_CONSUMABLE in App Store Connect, CONSUMABLE in the catalog",
                     f"{PID}.pro.monthly is an in-app purchase in App Store Connect",
                     f"{PID}.tip.snack is Missing Metadata; give it complete ios fields",
                     f"{PID}.tip.snack: no current price in USA"):
            self.assertIn(text, out)
        self.assertIn("nothing to create", out)

    def test_an_interrupted_in_app_purchase_is_finished_without_a_duplicate(self) -> None:
        catalog = product_catalog(consumables=[new_iap("credits.75")])
        fake = ProductAsc(fail_on={("iap", "pricing", "availability", "set")})
        code, _, err = self.run_products(fake, catalog)
        self.assertEqual(code, 1)
        self.assertIn("next run finishes it", err)
        live = fake.by_product_id(f"{PID}.credits.75")[0]
        self.assertEqual(live["attributes"]["state"], "MISSING_METADATA")
        fake.fail_on.clear()
        before = len(fake.calls)
        code, out, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        self.assertIn(f"would finish consumable {PID}.credits.75", out)
        self.assertIn("adds: availability, screenshot", out)
        rerun = [c for c in fake.calls[before:] if is_write(c)]
        self.assertEqual([c[:4] if c[1] == "pricing" else c[:3] for c in rerun],
                         [("iap", "pricing", "availability", "set"), ("iap", "review-screenshots", "create")])
        self.assert_ready(fake, f"{PID}.credits.75")

    def test_an_interrupted_subscription_is_finished_without_a_duplicate(self) -> None:
        catalog = product_catalog(subscriptions=[new_subscription()])
        fake = ProductAsc(fail_on={("subscriptions", "update")})
        code, _, _ = self.run_products(fake, catalog)
        self.assertEqual(code, 1)
        self.assertEqual(fake.by_product_id(f"{PID}.plus.weekly")[0]["attributes"]["state"], "MISSING_METADATA")
        fake.fail_on.clear()
        code, out, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        self.assertIn(f"would finish subscription {PID}.plus.weekly", out)
        sub = self.assert_ready(fake, f"{PID}.plus.weekly")
        self.assertEqual(sub["attributes"]["reviewNote"], "Optional Pro plan.")
        # A third run finds a Ready to Submit product and leaves it alone.
        before = len(fake.writes())
        code, out, _ = self.run_products(fake, catalog)
        self.assertEqual((code, len(fake.writes())), (0, before))
        self.assertIn(f"exists: subscription {PID}.plus.weekly", out)

    def test_a_missing_metadata_product_unlike_the_catalog_is_left_alone(self) -> None:
        fake = ProductAsc()
        fake.live_iap(f"{PID}.credits.75", name="Made by hand")
        code, out, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
        self.assertEqual(code, 0, err)
        self.assertIn("reference name 'Made by hand'", out)
        self.assertIn("left as it is", out)
        self.assertEqual(fake.writes(), [])

    def test_an_unfinished_part_that_differs_from_the_catalog_stops_before_writing(self) -> None:
        loc = {"locale": "en-US", "name": "Something else", "description": "Pro dashboards and alerts"}
        for parts, price, expected in (({"localization": loc}, None, "display name or description differs"),
                                       ({}, "4.99", "its price is 4.99"),
                                       ({"availability": (["USA"], False)}, None, "availability differs")):
            fake = ProductAsc()
            fake.live_iap(f"{PID}.credits.75", name="Credits", parts=parts, price=price)
            code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
            with self.subTest(expected):
                self.assertEqual(code, 1)
                self.assertIn(expected, err)
                self.assertEqual(fake.writes(), [])

    def test_a_subscription_part_that_differs_is_refused_and_left_unchanged(self) -> None:
        sub = {"id": "SUB-weekly", "attributes": {"name": "Plus", "productId": f"{PID}.plus.weekly",
                                                  "state": "MISSING_METADATA", "subscriptionPeriod": "ONE_WEEK"}}
        # Its localization is missing (setup would add it) and its price differs:
        # the plan stops the run, so the missing part is not written either.
        fake = ProductAsc(subscriptions=[sub], prices={"SUB-weekly": "4.99"})
        code, out, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription()]))
        self.assertEqual(code, 1)
        self.assertIn(f"would finish subscription {PID}.plus.weekly", out)
        self.assertIn("its price is 4.99", err)
        self.assertEqual(fake.writes(), [])
        self.assertEqual(fake.prices["SUB-weekly"], "4.99")
        self.assertNotIn("localization", fake.part("SUB-weekly"))
        loc = {"locale": "en-US", "name": "Something else", "description": "Pro dashboards and alerts"}
        for parts, expected in (({"localization": loc}, "display name or description differs"),
                                ({"availability": (["USA"], True)}, "availability differs"),
                                ({"screenshot": {"id": "S", "attributes": {"assetDeliveryState": {"state": "FAILED"}}}},
                                 "screenshot is FAILED"),
                                ({"screenshot": {"id": "S", "attributes": {"sourceFileChecksum": "0" * 32,
                                                                           "assetDeliveryState": {"state": "COMPLETE"}}}},
                                 "screenshot (COMPLETE) differs")):
            fake = ProductAsc(subscriptions=[json.loads(json.dumps(sub))])
            fake.parts["SUB-weekly"] = dict(parts)
            code, _, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription()]))
            with self.subTest(expected):
                self.assertEqual(code, 1)
                self.assertIn(expected, err)
                self.assertEqual(fake.writes(), [])
        # A review note that differs is caught by the plan, before any write.
        sub["attributes"]["reviewNote"] = "Written by hand."
        fake = ProductAsc(subscriptions=[sub])
        code, _, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription()]))
        self.assertEqual(code, 1)
        self.assertIn("its review note differs from the catalog", err)
        self.assertEqual(fake.writes(), [])

    def test_an_interrupted_screenshot_upload_is_resumed(self) -> None:
        raw = (self.root / SHOT).read_bytes()
        loc = {"locale": "en-US", "name": "Verceltics Credits", "description": "Pro dashboards and alerts"}
        reservation = {"id": "SHOT-1", "attributes": {"fileSize": len(raw), "uploadOperations": [{"method": "PUT"}],
                                                      "assetDeliveryState": {"state": "AWAITING_UPLOAD"}}}
        fake = ProductAsc()
        live = fake.live_iap(f"{PID}.credits.75", name="Credits", price="1.99", parts={
            "localization": loc, "availability": (TERRITORIES, True), "screenshot": reservation})
        code, out, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
        self.assertEqual(code, 0, err)
        self.assertIn("adds: screenshot", out)
        self.assertEqual([c[:3] for c in fake.writes()], [("iap", "review-screenshots", "update")])
        self.assertEqual(flag(fake.writes()[0], "--screenshot-id"), "SHOT-1")
        self.assert_ready(fake, f"{PID}.credits.75")
        self.assertEqual(fake.product(live)["attributes"]["state"], "READY_TO_SUBMIT")

    def test_a_failed_or_different_screenshot_stops_before_writing(self) -> None:
        raw = (self.root / SHOT).read_bytes()
        for attributes, expected in (({"assetDeliveryState": {"state": "FAILED"}}, "screenshot is FAILED"),
                                     ({"assetDeliveryState": {"state": "COMPLETE"}, "fileSize": len(raw),
                                       "sourceFileChecksum": "0" * 32}, "screenshot (COMPLETE) differs")):
            fake = ProductAsc()
            fake.live_iap(f"{PID}.credits.75", name="Credits", parts={"screenshot": {"id": "S", "attributes": attributes}})
            code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
            with self.subTest(expected):
                self.assertEqual(code, 1)
                self.assertIn(expected, err)
                self.assertEqual(fake.writes(), [])
        # A finish whose screenshot file is gone is an error line, not a traceback.
        (self.root / SHOT).unlink()
        fake = ProductAsc()
        fake.live_iap(f"{PID}.credits.75", name="Credits", parts={"screenshot": {"id": "S", "attributes": {}}})
        code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
        self.assertEqual(code, 1)
        self.assertIn("does not exist", err)
        self.assertEqual(fake.writes(), [])

    def test_a_screenshot_still_being_processed_is_kept(self) -> None:
        # An earlier run uploaded the screenshot and stopped while Apple was still processing it.
        raw = (self.root / SHOT).read_bytes()
        pending = {"id": "SHOT-1", "attributes": {"fileSize": len(raw), "sourceFileChecksum": hashlib.md5(raw).hexdigest(),
                                                  "assetDeliveryState": {"state": "UPLOAD_COMPLETE"}}}
        fake = ProductAsc()
        fake.live_iap(f"{PID}.credits.75", name="Credits", parts={"screenshot": pending})
        code, out, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
        self.assertEqual(code, 0, err)
        self.assertIn("adds: localization, price, availability", out)
        self.assertNotIn(("iap", "review-screenshots", "create"), [c[:3] for c in fake.writes()])
        self.assert_ready(fake, f"{PID}.credits.75")

    def test_a_failed_price_lookup_on_an_existing_product_is_only_a_warning(self) -> None:
        iaps = [{"id": "I1", "attributes": {"name": "Credits 50", "productId": f"{PID}.credits.50",
                                            "inAppPurchaseType": "CONSUMABLE", "state": "APPROVED"}}]
        fake = ProductAsc(iaps=iaps, prices={"I1": "1.99"}, fail_on={("iap", "pricing", "summary")})
        catalog = product_catalog(consumables=[{"id": f"{PID}.credits.50", "suggested_price_usd": "1.99", "platforms": ["ios"]}])
        code, out, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        self.assertIn("could not read its price, so it was not compared", out)
        # The same error while planning a product to finish stops the run.
        fake = ProductAsc(fail_on={("iap", "pricing", "summary")})
        fake.live_iap(f"{PID}.credits.75", name="Credits")
        code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
        self.assertEqual(code, 1)
        self.assertIn("exit 63", err)
        self.assertEqual(fake.writes(), [])

    def test_a_first_subscription_above_the_app_price_grid_is_created(self) -> None:
        fake = ProductAsc()
        code, out, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription(price="404.99")]))
        self.assertEqual(code, 0, err)
        self.assertIn("404.99 is above the app's price grid", out)
        self.assert_ready(fake, f"{PID}.plus.weekly")
        # Below the grid's top the app grid is authoritative for subscriptions too.
        fake = ProductAsc()
        code, _, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription(price="3.33")]))
        self.assertEqual(code, 1)
        self.assertIn("3.33 is not a price point in USA (checked against the app's own price grid", err)
        self.assertEqual(fake.writes(), [])

    def test_only_ios_products_are_sent_to_review(self) -> None:
        catalog = product_catalog(consumables=[new_iap("credits.75")],
                                  non_consumables=[{"id": f"{PID}.lifetime", "platforms": ["android"],
                                                    "play": {"product_id": "lifetime"}}])
        (self.root / "store" / "catalog" / "products.json").write_text(json.dumps(catalog))
        with mock.patch.object(asc_release, "ROOT", self.root):
            self.assertEqual(asc_release.intended_product_ids(), {f"{PID}.credits.75"})
        # Not even a Ready to Submit App Store product with that id rides along.
        iaps = [{"id": "LIFE", "attributes": {"name": "Lifetime", "productId": f"{PID}.lifetime", "state": "READY_TO_SUBMIT"}}]
        fake = FakeAsc(iaps=iaps)
        code, output = self.submit_for_review(fake)
        self.assertEqual(code, 0, output)
        added = [flag(c, "--item-id") for c in fake.writes() if c[:2] == ("review", "items-add")]
        self.assertEqual(added, ["V1"])

    def test_missing_fields_stop_the_run_before_any_write(self) -> None:
        incomplete = new_iap("credits.75")
        del incomplete["ios"]["description"]
        sub = new_subscription()
        del sub["ios"]["review_note"]
        fake = ProductAsc()
        catalog = product_catalog(subscriptions=[sub], consumables=[incomplete, new_iap("credits.500", price="9.99")])
        code, _, err = self.run_products(fake, catalog)
        self.assertEqual(code, 1)
        self.assertIn(f"{PID}.credits.75 would be created but the catalog lacks ios.description", err)
        self.assertIn(f"{PID}.plus.weekly would be created but the catalog lacks ios.review_note", err)
        self.assertIn("nothing was created", err)
        # Not even the complete product is created.
        self.assertEqual(fake.writes(), [])
        self.assertEqual(fake.iaps, [])

    def test_a_bad_review_screenshot_stops_the_run_before_any_write(self) -> None:
        for content in (png_header(6, width=1290, height=2796), png_header(width=1200, height=2000)):
            self.shot(SHOT, content)
            fake = ProductAsc()
            code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
            with self.subTest(content=content[:30]):
                self.assertEqual(code, 1)
                self.assertIn("review screenshot", err)
                self.assertEqual(fake.writes(), [])
        (self.root / SHOT).unlink()
        fake = ProductAsc()
        code, _, err = self.run_products(fake, product_catalog(subscriptions=[new_subscription()]))
        self.assertEqual(code, 1)
        self.assertIn("does not exist", err)
        self.assertEqual(fake.writes(), [])

    def test_a_price_without_a_price_point_stops_the_run(self) -> None:
        live_sub = [{"id": "S1", "attributes": {"name": "Plus", "productId": f"{PID}.plus.monthly", "state": "APPROVED",
                                                "subscriptionPeriod": "ONE_MONTH"}}]
        fake = ProductAsc(subscriptions=live_sub, prices={"S1": "8.99"})
        catalog = product_catalog(subscriptions=[new_subscription(price="2.98")], consumables=[new_iap("credits.75", price="1.23")])
        code, _, err = self.run_products(fake, catalog)
        self.assertEqual(code, 1)
        self.assertIn("2.98 is not a price point in USA", err)
        self.assertIn("1.23 is not a price point in USA (checked against the app's own price grid", err)
        self.assertEqual(fake.writes(), [])
        # The live subscription is asked for its price points; the app has no IAP yet, so its grid is used.
        asked = [c for c in fake.calls if "price-points" in c]
        self.assertTrue(any(c[:1] == ("subscriptions",) and flag(c, "--subscription-id") == "S1" for c in asked))
        self.assertTrue(any(c[:2] == ("pricing", "price-points") for c in asked))

    def test_a_new_group_needs_a_display_name_and_listed_territories_must_exist(self) -> None:
        groups = [{"reference_name": "Verceltics Pro"}, {"reference_name": "Coaching"}]
        sub = new_subscription(subscription_group="Coaching")
        fake = ProductAsc()
        code, _, err = self.run_products(fake, product_catalog(subscriptions=[sub], groups=groups))
        self.assertEqual(code, 1)
        self.assertIn("subscription group 'Coaching' needs a display_name", err)
        code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75", territories=["USA", "XYZ"])]))
        self.assertEqual(code, 1)
        self.assertIn("App Store Connect has no territories XYZ", err)
        self.assertEqual(fake.writes(), [])

    def test_a_product_that_never_becomes_ready_fails_after_a_bounded_wait(self) -> None:
        fake = ProductAsc(stuck=True)
        code, _, err = self.run_products(fake, product_catalog(consumables=[new_iap("credits.75")]))
        self.assertEqual(code, 1)
        self.assertIn("state is MISSING_METADATA, expected READY_TO_SUBMIT", err)
        self.assertEqual(self.sleeps, [asc_products.STATE_POLL_SECONDS] * (asc_products.STATE_POLLS - 1))

    def test_new_products_reach_review_in_the_same_run(self) -> None:
        catalog = product_catalog(subscriptions=[new_subscription()], non_consumables=[new_iap("lifetime", price="69.99")])
        fake = ProductAsc()
        code, _, err = self.run_products(fake, catalog)
        self.assertEqual(code, 0, err)
        sub = self.assert_ready(fake, f"{PID}.plus.weekly")
        iap = self.assert_ready(fake, f"{PID}.lifetime")
        self.assertTrue(is_write(("subscriptions", "setup")))
        before = len(fake.writes())
        catalog_file = self.root / "store" / "catalog" / "products.json"
        catalog_file.write_text(json.dumps(catalog))
        with mock.patch.object(asc_release, "ROOT", self.root):
            ids = asc_release.intended_product_ids()
        self.assertEqual(ids, {f"{PID}.plus.weekly", f"{PID}.lifetime"})
        code, output = self.submit_for_review(fake)
        self.assertEqual(code, 0, output)
        review = fake.writes()[before:]
        added = {flag(c, "--item-id") for c in review if c[:2] == ("review", "items-add")}
        self.assertEqual(added, {f"{sub['id']}v2", f"{iap['id']}v1", "V1"})
        self.assertEqual(review[-1][:2], ("review", "submissions-submit"))

    def test_the_workflow_plans_and_creates_in_prepare_before_the_build(self) -> None:
        workflow = (asc_release.ROOT / ".github" / "workflows" / "ios-release.yml").read_text()
        prepare = workflow[workflow.index("\n  prepare:\n"):workflow.index("\n  build:\n")]
        step = prepare[prepare.index("- name: Create missing App Store products (IOS_CREATE_PRODUCTS)"):]
        self.assertIn("if: steps.gates.outputs.product_plan == 'true'", step)
        self.assertIn('[ "$CREATE_IOS_PRODUCTS" = true ] && command=create', step)
        self.assertLess(prepare.index("Create missing App Store products"), prepare.index("Choose the build number"))
        self.assertIn("IOS_CREATE_PRODUCTS: ${{ vars.IOS_CREATE_PRODUCTS }}", prepare)
        self.assertIn("ASC_READ_ONLY: ${{ github.event_name == 'workflow_dispatch' && '1' || '' }}", prepare)
        for path in ("ios-release.yml", "store-automation.yml"):
            text = (asc_release.ROOT / ".github" / "workflows" / path).read_text()
            blocks = text.split("run: python3 scripts/store/print_gates.py")[:-1]
            self.assertTrue(blocks, path)
            for block in blocks:
                env = block[block.rindex("env:"):]
                with self.subTest(path=path):
                    self.assertIn("IOS_CREATE_PRODUCTS: ${{ vars.IOS_CREATE_PRODUCTS }}", env)
                    self.assertIn("IOS_UPLOAD_CREATIVE_ASSETS: ${{ vars.IOS_UPLOAD_CREATIVE_ASSETS }}", env)


class ExportListingTests(unittest.TestCase):
    def test_exports_the_newest_released_listing_read_only(self) -> None:
        fake = FakeAsc(version_exists=False, other_versions=[("2.0", "READY_FOR_SALE"), ("2.1", "READY_FOR_SALE"),
                                                             ("2.2", "PREPARE_FOR_SUBMISSION")])
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(asc_release, "asc_from_environment", side_effect=fake.connect), \
                mock.patch.object(sys, "argv", ["export_ios_listing.py", "--out", tmp]):
            out = io.StringIO()
            with redirect_stdout(out):
                export_ios_listing.main()
            written = {p.name: p.read_text(encoding="utf-8") for p in (Path(tmp) / "en-US").iterdir()}
        self.assertEqual(written, {name: text + "\n" for name, text in LISTING.items()})
        self.assertIn("exported the live 2.1 listing for 1 locale(s)", out.getvalue())
        self.assertEqual(fake.modes, [True])
        self.assertEqual(fake.writes(), [])
        reads = [fake.calls[i] for i in heads(fake.calls, ("localizations", "list"))]
        self.assertEqual(flag(reads[0], "--version"), "V0-1")
        self.assertEqual((flag(reads[1], "--type"), flag(reads[1], "--app-info")), ("app-info", "LIVE"))


class PrintGatesTests(unittest.TestCase):
    def run_print(self, env: dict[str, str]) -> str:
        clean = {k: v for k, v in os.environ.items() if not k.startswith(("IOS_", "ANDROID_", "STORE_"))}
        clean.update(env)
        out = io.StringIO()
        with mock.patch.dict(os.environ, clean, clear=True), \
                mock.patch.object(sys, "argv", ["print_gates.py", "--platform", "ios"]), redirect_stdout(out):
            print_gates.main()
        return out.getvalue()

    def test_creative_assets_switch_is_listed(self) -> None:
        self.assertIn("IOS_UPLOAD_CREATIVE_ASSETS=false (unset)", self.run_print({}))
        self.assertIn("IOS_UPLOAD_CREATIVE_ASSETS=true", self.run_print({"IOS_UPLOAD_CREATIVE_ASSETS": "true"}))
        self.assertIn("IOS_UPLOAD_CREATIVE_ASSETS=INVALID", self.run_print({"IOS_UPLOAD_CREATIVE_ASSETS": "maybe"}))
        self.assertEqual([name for name, _ in ios_release.OPERATION_SWITCHES if name not in print_gates.GATES["ios"]], [])


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
        duplicate["tips"].append({"id": "com.apoorvdarshan.verceltics.tip.coffee", "platforms": ["ios"]})
        with self.assertRaisesRegex(validate_catalog.CatalogError, "duplicate"):
            validate_catalog.check(duplicate, platform="ios", app_ids=ids)
        foreign = json.loads(json.dumps(catalog))
        foreign["tips"].append({"id": "com.apoorvdarshan.calorietracker.tip.snack", "platforms": ["ios"]})
        with self.assertRaisesRegex(validate_catalog.CatalogError, "another app"):
            validate_catalog.check(foreign, platform="ios", app_ids=ids | {"com.apoorvdarshan.calorietracker.tip.snack"})
        undeclared = json.loads(json.dumps(catalog))
        del undeclared["uses_non_exempt_encryption"]
        with self.assertRaisesRegex(validate_catalog.CatalogError, "uses_non_exempt_encryption"):
            validate_catalog.check(undeclared, platform="ios", app_ids=ids)

    def test_each_platform_is_checked_against_the_products_it_lists(self) -> None:
        catalog = validate_catalog.load_catalog()
        ids = set(validate_catalog.catalog_product_ids(catalog))
        android_only = json.loads(json.dumps(catalog))
        android_only["tips"].append({"id": "com.apoorvdarshan.verceltics.tip.android", "platforms": ["android"]})
        # Not sold on iOS, so Products.storekit does not need it, but BillingModels.kt does.
        self.assertEqual(validate_catalog.check(android_only, platform="ios", app_ids=ids), len(ids))
        with self.assertRaisesRegex(validate_catalog.CatalogError, "missing from the catalog"):
            validate_catalog.check(catalog, platform="android", app_ids=ids | {"com.apoorvdarshan.verceltics.tip.android"})
        self.assertEqual(validate_catalog.check(android_only, platform="android",
                                                app_ids=ids | {"com.apoorvdarshan.verceltics.tip.android"}), len(ids) + 1)
        with self.assertRaisesRegex(validate_catalog.CatalogError, "missing from the catalog"):
            validate_catalog.check(android_only, platform="ios", app_ids=ids | {"com.apoorvdarshan.verceltics.tip.android"})
        for platforms in (None, [], ["ios", "ios"], ["web"], "ios"):
            unlisted = json.loads(json.dumps(catalog))
            unlisted["tips"][0]["platforms"] = platforms
            with self.subTest(platforms=platforms), self.assertRaisesRegex(validate_catalog.CatalogError, "platforms must list"):
                validate_catalog.check(unlisted, platform="ios", app_ids=ids)

    def test_release_submits_only_catalog_products(self) -> None:
        self.assertEqual(asc_release.intended_product_ids(),
                         set(validate_catalog.catalog_product_ids(validate_catalog.load_catalog(), "ios")))


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
