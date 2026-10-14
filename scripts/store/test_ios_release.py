#!/usr/bin/env python3
"""Offline tests for the iOS release: switches, build numbering, waiting for and
selecting this run's build, listing/What's New/screenshots, catalog and review.

No network: every App Store Connect call goes to an in-memory fake.
Run: python3 -m unittest scripts/store/test_ios_release.py
"""
from __future__ import annotations

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

import asc_release  # noqa: E402
import ios_metadata  # noqa: E402
import ios_release  # noqa: E402
import release_notes  # noqa: E402
import validate_catalog  # noqa: E402
from ios_release import ReleaseError, resolve_gates  # noqa: E402

TAG = "ios-v2.2"
VERSION = "2.2"
ALL_ON = {
    "IOS_RELEASE_AUTOMATION": "true",
    "IOS_UPLOAD_BUILD": "true",
    "IOS_UPLOAD_LISTING": "true",
    "IOS_UPLOAD_WHATS_NEW": "true",
    "IOS_UPLOAD_SCREENSHOTS": "true",
    "IOS_SUBMIT_REVIEW": "true",
    "IOS_PUBLIC_RELEASE": "true",
}
OPS = ("upload_build", "listing", "whats_new", "screenshots", "submit", "public_release")


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
    uploaded: float = STARTED + 60,
    app: str = APP,
    pre: str = "P1",
) -> dict:
    return {
        "type": "builds",
        "id": build_id,
        "attributes": {
            "version": number,
            "processingState": state,
            "expired": expired,
            "uploadedDate": iso(uploaded),
        },
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
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "SUBMIT_IOS_REVIEW", "PUBLIC_RELEASE"}}
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

    def test_dry_run_submit_exercises_product_selection(self) -> None:
        code, output = self.run_cli(
            {"SUBMIT_IOS_REVIEW": "true", "UPLOAD_LISTING": "true"},
            "--build-id", "DRY-RUN", "--build-number", "1",
        )
        self.assertEqual(code, 0, output)
        self.assertIn("product submission selection OK", output)

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

    def __init__(self, *, state: str = "PREPARE_FOR_SUBMISSION", attached: str | None = "OLD",
                 revert_after_select: bool = False, encryption: bool | None = None) -> None:
        self.state = state
        self.attached = attached
        self.revert_after_select = revert_after_select
        self.encryption = encryption
        self.build_reads = 0
        self.calls: list[tuple[str, str]] = []

    def get(self, path: str) -> dict:
        self.calls.append(("GET", path))
        if path.startswith("/apps?"):
            return {"data": [{"id": APP, "attributes": {"bundleId": asc_release.DEFAULT_BUNDLE_ID}}]}
        if path.startswith(f"/apps/{APP}/appStoreVersions"):
            return {"data": [{"id": "V1", "attributes": {"versionString": VERSION, "appStoreState": self.state}}]}
        if path.startswith("/builds/B9"):
            build = build_row("B9")
            build["attributes"]["usesNonExemptEncryption"] = self.encryption
            return {"data": build, "included": [pre_row()]}
        if path == "/appStoreVersions/V1/build":
            self.build_reads += 1
            if self.revert_after_select and self.build_reads >= 2:
                return {"data": {"type": "builds", "id": "OLD"}}
            return {"data": {"type": "builds", "id": self.attached} if self.attached else None}
        if path.startswith(f"/apps/{APP}/reviewSubmissions"):
            return {"data": [{"id": "SUB1"}]}
        if path.startswith("/reviewSubmissions/SUB1/items"):
            return {"data": []}
        raise AssertionError(f"unexpected GET {path}")

    def post(self, path: str, body: dict) -> dict:
        self.calls.append(("POST", path))
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


class LiveSubmitFlowTests(unittest.TestCase):
    def run_main(self, fake: FakeAsc, *, public: bool = False) -> tuple[int, mock.Mock]:
        products = mock.Mock(return_value=asc_release.ProductSubmitResult("SUB1", 0, False))
        argv = ["asc_release.py", "--version", VERSION, "--build-id", "B9", "--build-number", "40"]
        env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "PUBLIC_RELEASE"}}
        env["SUBMIT_IOS_REVIEW"] = "true"
        if public:
            env["PUBLIC_RELEASE"] = "true"
        code = 0
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(sys, "argv", argv), \
                mock.patch.object(asc_release, "client_from_environment", return_value=fake), \
                mock.patch.object(asc_release, "submit_in_app_purchases", products), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                asc_release.main()
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
        return code, products

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
        products.assert_called_once()

    def test_answered_compliance_is_not_changed(self) -> None:
        fake = FakeAsc(encryption=False)
        code, _ = self.run_main(fake)
        self.assertEqual(code, 0)
        self.assertNotIn(("PATCH", "/builds/B9"), fake.calls)

    def test_compliance_that_contradicts_the_catalog_aborts(self) -> None:
        fake = FakeAsc(encryption=True)
        code, products = self.run_main(fake)
        self.assertEqual(code, 1)
        products.assert_not_called()
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
        products.assert_not_called()
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_waiting_for_review_with_this_build_is_idempotent(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9")
        code, products = self.run_main(fake)
        self.assertEqual(code, 0)
        products.assert_called_once()
        self.assertNotIn(("PATCH", "/appStoreVersions/V1/relationships/build"), fake.calls)

    def test_released_version_is_never_submitted(self) -> None:
        fake = FakeAsc(state="READY_FOR_SALE")
        code, products = self.run_main(fake)
        self.assertEqual(code, 1)
        products.assert_not_called()
        self.assertFalse([c for c in fake.calls if c[0] in {"PATCH", "POST"}])


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
    def test_repository_listing_is_valid(self) -> None:
        for locale in ios_metadata.locales():
            values = ios_metadata.load_locale(ios_metadata.LISTING_DIR / locale)
            self.assertIn("description.txt", values)
            self.assertIn("name.txt", values)

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

    def test_prepare_without_a_tag_never_adds_whats_new(self) -> None:
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            write_listing(Path(src))
            (Path(src) / "RELEASE_NOTES.md").write_text("## ios-v2.2\n\n" + "x" * 5000 + "\n")
            with mock.patch.object(release_notes, "ROOT", Path(src)):
                written = ios_metadata.prepare(Path(out), None, Path(src) / "ios")
            self.assertFalse(any(p.name == "whats_new.txt" for p in written))

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

    def test_repository_screenshots_are_app_store_ready(self) -> None:
        for spec in ios_metadata.SCREENSHOT_SETS:
            with self.subTest(spec.label):
                self.assertGreaterEqual(len(ios_metadata.screenshot_files(spec)), 1)


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


if __name__ == "__main__":
    unittest.main()
