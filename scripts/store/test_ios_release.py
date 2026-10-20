#!/usr/bin/env python3
"""Offline tests for the iOS release: switches, build numbering, waiting for and
selecting this run's build, listing/What's New/screenshots/creative assets,
catalog and review, and the asc runner.

No network: every asc call goes to an in-memory fake of App Store Connect.
Run: python3 -m unittest scripts/store/test_ios_release.py
"""
from __future__ import annotations

import argparse
import contextlib
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
    "IOS_UPLOAD_BUILD": "true",
    "IOS_UPLOAD_LISTING": "true",
    "IOS_UPLOAD_WHATS_NEW": "true",
    "IOS_UPLOAD_SCREENSHOTS": "true",
    "IOS_UPLOAD_CREATIVE_ASSETS": "true",
    "IOS_SUBMIT_REVIEW": "true",
    "IOS_PUBLIC_RELEASE": "true",
}
OPS = ("create_version", "upload_build", "listing", "whats_new", "screenshots", "creative", "submit", "public_release")


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


def png_header(color_type: int, *, trns: bool = False) -> bytes:
    """A PNG signature and IHDR (plus an optional tRNS chunk) with the given colour type."""

    def chunk(kind: bytes, body: bytes) -> bytes:
        return len(body).to_bytes(4, "big") + kind + body + zlib.crc32(kind + body).to_bytes(4, "big")

    ihdr = (16).to_bytes(4, "big") + (16).to_bytes(4, "big") + bytes([8, color_type, 0, 0, 0])
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + (chunk(b"tRNS", b"\x00\x00") if trns else b"") + chunk(b"IDAT", b"x")


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
                       "net/http: TLS handshake timeout", "unexpected EOF"):
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
}
ITEM_RELATIONS = {
    "appStoreVersions": "appStoreVersion",
    "subscriptionVersions": "subscriptionVersion",
    "inAppPurchaseVersions": "inAppPurchaseVersion",
    "appAssetLibraryImages": "appAssetLibraryImage",
}


def is_write(call: tuple[str, ...]) -> bool:
    return call[:2] in MUTATIONS or call[:3] in MUTATIONS


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
            (Path(tmp) / "header" / "a.png").write_bytes(b"x")
            (Path(tmp) / "header" / "b.png").write_bytes(b"y")
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
        # A draft that also holds an app version is never submitted from here.
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", iaps=[LIFETIME], drafts=["D1"],
                       draft_items={"D1": [item("appStoreVersion", "V0")]})
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])

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

    def test_a_draft_without_this_version_is_never_submitted(self) -> None:
        # READY_FOR_REVIEW: the version should already sit in the draft. A draft
        # that does not hold it is not submitted in its place.
        fake = FakeAsc(state="READY_FOR_REVIEW", drafts=["D1"], draft_items={"D1": []})
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])
        # The same with a catalog product in the reused draft.
        fake = FakeAsc(state="READY_FOR_REVIEW", subscriptions=[subscription("S1", MONTHLY, "READY_TO_SUBMIT")],
                       drafts=["D1"], draft_items={"D1": [item("subscriptionVersion", "S1v2")]})
        self.assertEqual(self.run_main(fake), 1)
        self.assertEqual(fake.submitted, [])
        fake = FakeAsc(state="READY_FOR_REVIEW", drafts=["D1"], draft_items={"D1": [item("appStoreVersion", "V1")]})
        self.assertEqual(self.run_main(fake), 0)
        self.assertEqual(fake.submitted, ["D1"])
        self.assertEqual([c for c in fake.writes() if c[:2] == ("review", "items-add")], [])


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
                           app_infos=[app_info("NEXT", "READY_FOR_REVIEW")])
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

    def test_screenshot_sets_to_replace_must_be_pngs_asc_can_upload(self) -> None:
        env = {"UPLOAD_SCREENSHOTS": "true"}
        with temp_screenshot_sets(alpha=True) as sets:
            code, output = self.run_preflight(FakeAsc(), env)
            self.assertEqual(code, 1)
            self.assertIn("alpha channel", output)
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

    def test_pngs_with_alpha_stop_the_run_before_anything_is_written(self) -> None:
        with temp_screenshot_sets(alpha=True), tempfile.TemporaryDirectory() as tmp:
            write_listing(Path(tmp))
            fake = FakeAsc(localization={"description": "Old"})
            code, output = run_asc(fake, {**self.ENV, "UPLOAD_LISTING": "true"}, metadata=Path(tmp))
        self.assertEqual(code, 1)
        self.assertIn("alpha channel", output)
        self.assertEqual(fake.writes(), [], "neither the text nor any screenshot was changed")

    def test_a_set_without_screenshots_fails_before_any_call(self) -> None:
        with temp_screenshot_sets() as sets:
            for path in sets[1].directory.glob("*.png"):
                path.unlink()
            fake = FakeAsc()
            code, output = run_asc(fake, self.ENV)
        self.assertEqual(code, 1)
        self.assertIn("screenshots", output)
        self.assertEqual(fake.calls, [])


class CreativeAssetTests(unittest.TestCase):
    ENV = {"UPLOAD_CREATIVE_ASSETS": "true"}

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        for folder, payload in (("header", b"header-image"), ("search-results", b"search-image")):
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
        (self.dir / "header" / "header.png").write_bytes(png_header(2))
        self.assertEqual(self.run_creative(FakeAsc()), 0)

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
