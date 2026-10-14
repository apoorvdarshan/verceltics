#!/usr/bin/env python3
"""Android release switches, artifact checks and Play track read-back.

Subcommands (`gates` also writes key=value lines to $GITHUB_OUTPUT when it is set):

  gates         Validate the ANDROID_* repository variables and resolve what this
                run may do. Missing values mean off; invalid values fail. A tag
                run needs android-v<versionName>; a manual run only validates.
  verify        Before anything leaves the runner, check the built AAB and APK:
                package name, versionName/versionCode from build.gradle.kts, and
                that both are signed only by the upload key.
  check-tracks  After a Play upload, read every selected track back and confirm
                it carries this versionCode as a completed release. Never edits.

Google Play has not granted Verceltics production access yet: it needs 12
testers opted in to closed testing for 14 consecutive days first. Until then
Play rejects production uploads, so ANDROID_PRODUCTION_ROLLOUT stays off.

See store/README.md for what each switch controls.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Any, Mapping

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
PACKAGE_NAME = "com.apoorvdarshan.verceltics"
TAG_PREFIX = "android-v"

TRUE_VALUES = {"1", "true", "yes", "on"}
FALSE_VALUES = {"", "0", "false", "no", "off"}

MASTER = "ANDROID_RELEASE_AUTOMATION"
OPERATION_SWITCHES = (
    ("ANDROID_UPLOAD_LISTING", "listing"),
    ("ANDROID_UPLOAD_WHATS_NEW", "whats_new"),
    ("ANDROID_UPLOAD_SCREENSHOTS", "screenshots"),
    ("ANDROID_INTERNAL_TESTING", "internal"),
    ("ANDROID_CLOSED_TESTING", "closed"),
    ("ANDROID_PRODUCTION_ROLLOUT", "production"),
)
# Switch key -> Google Play track id. Closed testing is Play's default "alpha" track.
PLAY_TRACKS = (("internal", "internal"), ("closed", "alpha"), ("production", "production"))
TRACK_LABELS = {"internal": "internal testing", "alpha": "closed testing", "production": "production"}

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
PEM_CERT = re.compile(
    r"-----BEGIN CERTIFICATE-----\s*(.+?)\s*-----END CERTIFICATE-----", re.DOTALL
)


class ReleaseError(Exception):
    """A release precondition failed; the run must stop without publishing."""


def parse_switch(name: str, raw: str | None) -> bool:
    value = (raw or "").strip().lower()
    if value in TRUE_VALUES:
        return True
    if value in FALSE_VALUES:
        return False
    raise ReleaseError(
        f"{name}={raw!r} is not a valid switch value; use true/false (or leave it unset)"
    )


def gradle_version(text: str) -> tuple[str, int]:
    names = re.findall(r'^\s*versionName = "([^"]+)"', text, flags=re.MULTILINE)
    codes = re.findall(r"^\s*versionCode = ([0-9]+)\s*$", text, flags=re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ReleaseError(
            "android/app/build.gradle.kts must declare exactly one versionName and one versionCode"
        )
    return names[0], int(codes[0])


def resolve_gates(
    env: Mapping[str, str], *, event: str, ref_name: str, version_name: str
) -> dict[str, Any]:
    """Return what this run is allowed to do. Raises ReleaseError on bad config."""
    if event not in {"push", "workflow_dispatch"}:
        raise ReleaseError(f"unsupported event {event!r}")
    # Parse every switch first so a typo fails even when the master is off.
    master = parse_switch(MASTER, env.get(MASTER))
    requested = {key: parse_switch(name, env.get(name)) for name, key in OPERATION_SWITCHES}
    notes: list[str] = []

    if event == "workflow_dispatch":
        # Manual runs only prove the signed build is the right app, version and key.
        mode = "validate"
        resolved = {key: False for _, key in OPERATION_SWITCHES}
        notes.append(
            "validation run: the signed AAB and APK are built and verified only; nothing is "
            "uploaded, signed with Sigstore or released"
        )
    else:
        if not ref_name.startswith(TAG_PREFIX):
            raise ReleaseError(f"release runs need an {TAG_PREFIX}* tag, got {ref_name!r}")
        if ref_name[len(TAG_PREFIX):] != version_name:
            raise ReleaseError(
                f"tag {ref_name} does not match versionName {version_name}; "
                "fix the tag or bump the version before releasing"
            )
        mode = "release"
        if master:
            resolved = dict(requested)
        else:
            notes.append(f"{MASTER} is off: no signed build, GitHub Release or Play operation runs")
            ignored = [name for name, key in OPERATION_SWITCHES if requested[key]]
            if ignored:
                notes.append(f"ignored because {MASTER} is off: {', '.join(ignored)}")
            resolved = {key: False for _, key in OPERATION_SWITCHES}

    tracks = [track for key, track in PLAY_TRACKS if resolved[key]]
    if resolved["whats_new"] and not tracks:
        notes.append(
            "ANDROID_UPLOAD_WHATS_NEW is on but no Play track is selected, so there is no "
            "release to attach What's New to; it is skipped"
        )
        resolved["whats_new"] = False

    published = mode == "release" and master
    resolved.update(
        {
            "mode": mode,
            "build": mode == "validate" or published,
            "github_release": published,
            "play_upload": bool(tracks),
            "play_tracks": ",".join(tracks),
        }
    )
    resolved["notes"] = notes
    return resolved


def write_outputs(values: Mapping[str, Any]) -> None:
    lines = []
    for key, value in values.items():
        if isinstance(value, bool):
            value = "true" if value else "false"
        lines.append(f"{key}={value}")
    target = os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
    for line in lines:
        print(f"  {line}")


# --- artifact checks ---------------------------------------------------------


def pem_fingerprints(text: str) -> set[str]:
    """SHA-256 fingerprints (lowercase hex) of every PEM certificate in text."""
    prints = set()
    for body in PEM_CERT.findall(text):
        der = base64.b64decode("".join(body.split()))
        prints.add(hashlib.sha256(der).hexdigest())
    return prints


def apksigner_fingerprints(output: str) -> set[str]:
    """Signer certificate digests from `apksigner verify --print-certs`.

    Older build-tools print "Signer #1 certificate SHA-256 digest", newer ones
    "V2 Signer: certificate SHA-256 digest". A source stamp is not an app signer.
    """
    prints = set()
    for line in output.splitlines():
        if "Source Stamp" in line:
            continue
        match = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", line)
        if match:
            prints.add(match.group(1).lower())
    return prints


def badging_identity(output: str) -> tuple[str, int, str]:
    match = re.search(
        r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'",
        output,
        flags=re.MULTILINE,
    )
    if not match:
        raise ReleaseError("could not read package/versionCode/versionName from the APK")
    return match.group(1), int(match.group(2)), match.group(3)


def manifest_identity(xml_text: str) -> tuple[str, int, str]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ReleaseError(f"could not parse the AAB manifest: {exc}") from None
    package = root.get("package") or ""
    code = root.get(f"{ANDROID_NS}versionCode") or ""
    name = root.get(f"{ANDROID_NS}versionName") or ""
    if not package or not re.fullmatch(r"[0-9]+", code):
        raise ReleaseError("could not read package/versionCode from the AAB manifest")
    return package, int(code), name


SIGNATURE_FILE = re.compile(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC)|SIG-[^/]+)")


def manifest_entry_names(manifest: str) -> set[str]:
    """Entry names listed in a JAR manifest (unwrapping 72-byte continuation lines)."""
    unwrapped = re.sub(r"\r?\n ", "", manifest)
    return {
        line[len("Name: "):].strip()
        for line in unwrapped.splitlines()
        if line.startswith("Name: ")
    }


def unsigned_entries(archive: Path) -> list[str]:
    """Files in a JAR-signed archive (an AAB) that its signature does not cover.

    `jarsigner -verify` checks every entry the manifest lists, but still reports
    "jar verified." when other entries were added without a signature.
    """
    with zipfile.ZipFile(archive) as bundle:
        names = [n for n in bundle.namelist() if not n.endswith("/")]
        if "META-INF/MANIFEST.MF" not in names:
            return [n for n in names if not SIGNATURE_FILE.fullmatch(n)]
        listed = manifest_entry_names(bundle.read("META-INF/MANIFEST.MF").decode("utf-8"))
    return sorted(n for n in names if not SIGNATURE_FILE.fullmatch(n) and n not in listed)


def check_identity(
    kind: str, found: tuple[str, int, str], *, package: str, version_code: int, version_name: str
) -> None:
    expected = (package, version_code, version_name)
    if found != expected:
        raise ReleaseError(
            f"{kind} is {found[0]} {found[2]} ({found[1]}), expected "
            f"{package} {version_name} ({version_code})"
        )


def check_signers(kind: str, found: set[str], *, upload_cert: str) -> None:
    if not found:
        raise ReleaseError(f"{kind} is not signed")
    if found != {upload_cert}:
        raise ReleaseError(
            f"{kind} must be signed only by the upload key ({upload_cert}); found {sorted(found)}"
        )


def run(cmd: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise ReleaseError(
            f"{Path(cmd[0]).name} failed ({result.returncode}): {(result.stderr or result.stdout).strip()}"
        )
    return result


def build_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ReleaseError("ANDROID_HOME is not set; cannot find build-tools")

    def version_key(path: Path) -> tuple[int, ...]:
        return tuple(int(part) for part in re.findall(r"[0-9]+", path.parent.name))

    candidates = sorted(Path(sdk, "build-tools").glob(f"*/{name}"), key=version_key)
    if not candidates:
        raise ReleaseError(f"{name} not found under {sdk}/build-tools")
    return str(candidates[-1])


def cmd_verify(args: argparse.Namespace) -> None:
    version_name, version_code = gradle_version(GRADLE.read_text(encoding="utf-8"))
    identity = {"package": args.package, "version_code": version_code, "version_name": version_name}

    exported = run(
        [
            "keytool", "-exportcert", "-rfc", "-alias", args.alias, "-keystore", str(args.keystore),
            "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
        ]
    )
    upload_certs = pem_fingerprints(exported.stdout)
    if len(upload_certs) != 1:
        raise ReleaseError(f"expected one upload certificate in the keystore, found {len(upload_certs)}")
    upload_cert = upload_certs.pop()
    print(f"upload certificate SHA-256: {upload_cert}")

    badging = run([build_tool("aapt2"), "dump", "badging", str(args.apk)])
    check_identity("APK", badging_identity(badging.stdout), **identity)
    signers = run([build_tool("apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)])
    check_signers("APK", apksigner_fingerprints(signers.stdout), upload_cert=upload_cert)

    manifest = run(["java", "-jar", str(args.bundletool), "dump", "manifest", "--bundle", str(args.aab)])
    check_identity("AAB", manifest_identity(manifest.stdout), **identity)
    jar = run(["jarsigner", "-verify", str(args.aab)], check=False)
    if jar.returncode != 0 or "jar verified." not in jar.stdout:
        raise ReleaseError(f"AAB signature does not verify: {(jar.stdout + jar.stderr).strip()[-2000:]}")
    unsigned = unsigned_entries(args.aab)
    if unsigned:
        raise ReleaseError(
            f"AAB has {len(unsigned)} entr{'y' if len(unsigned) == 1 else 'ies'} its signature "
            f"does not cover, e.g. {unsigned[0]}"
        )
    printed = run(["keytool", "-printcert", "-rfc", "-jarfile", str(args.aab)])
    check_signers("AAB", pem_fingerprints(printed.stdout), upload_cert=upload_cert)

    print(f"verified AAB and APK: {args.package} {version_name} ({version_code}), signed by the upload key")


# --- Play read-back ----------------------------------------------------------


def track_release_state(track: dict[str, Any], version_code: int) -> str:
    """Status of the release on this track that carries version_code ('' if none)."""
    for release in track.get("releases") or []:
        if str(version_code) in (release.get("versionCodes") or []):
            return release.get("status") or ""
    return ""


def check_tracks(fetch_track, tracks: list[str], version_code: int) -> list[str]:
    """Confirm each track carries version_code as a completed release."""
    messages = []
    for track in tracks:
        state = track_release_state(fetch_track(track), version_code)
        label = TRACK_LABELS.get(track, track)
        if state != "completed":
            found = state or "no release"
            raise ReleaseError(
                f"Play {label} ({track}) does not carry versionCode {version_code} as a completed "
                f"release (found: {found})"
            )
        messages.append(
            f"Play {label}: versionCode {version_code} uploaded and assigned (status completed). "
            "Google Play may still review it before it reaches "
            + ("users" if track == "production" else "testers")
        )
    return messages


def cmd_check_tracks(args: argparse.Namespace) -> None:
    tracks = [t for t in args.tracks.split(",") if t]
    if not tracks:
        raise ReleaseError("no Play tracks to check")
    unknown = [t for t in tracks if t not in TRACK_LABELS]
    if unknown:
        raise ReleaseError(f"unexpected Play track(s): {', '.join(unknown)}")
    _, version_code = gradle_version(GRADLE.read_text(encoding="utf-8"))

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import play_listing  # noqa: PLC0415 - keeps `gates` free of Google API deps

    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        raise ReleaseError("PLAY_SERVICE_ACCOUNT_JSON is not set")
    service = play_listing.build_android_publisher(json.loads(raw))
    edits = service.edits()
    # A throwaway edit only reads the committed state; it is deleted, never committed.
    edit_id = edits.insert(packageName=PACKAGE_NAME, body={}).execute()["id"]
    try:
        messages = check_tracks(
            lambda track: edits.tracks().get(packageName=PACKAGE_NAME, editId=edit_id, track=track).execute(),
            tracks,
            version_code,
        )
    finally:
        edits.delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
    for message in messages:
        print(message)


def cmd_gates(_: argparse.Namespace) -> None:
    version_name, version_code = gradle_version(GRADLE.read_text(encoding="utf-8"))
    resolved = resolve_gates(
        os.environ,
        event=os.environ.get("GITHUB_EVENT_NAME", ""),
        ref_name=os.environ.get("GITHUB_REF_NAME", ""),
        version_name=version_name,
    )
    notes = resolved.pop("notes")
    print(f"Android release switches ({version_name}, versionCode {version_code}):")
    for note in notes:
        print(f"  note: {note}")
    if resolved["production"]:
        print("  WARNING: ANDROID_PRODUCTION_ROLLOUT is on — this build goes live on Play production")
        print(
            "  note: Google Play has not granted Verceltics production access yet (12 testers in closed "
            "testing for 14 days); Play rejects the production upload until it does"
        )
    write_outputs(resolved)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("gates").set_defaults(func=cmd_gates)
    verify = sub.add_parser("verify")
    verify.add_argument("--aab", required=True, type=Path)
    verify.add_argument("--apk", required=True, type=Path)
    verify.add_argument("--keystore", required=True, type=Path)
    verify.add_argument("--alias", default="upload", help="upload key alias (android/app/build.gradle.kts signs with upload)")
    verify.add_argument("--bundletool", required=True, type=Path)
    verify.add_argument("--package", default=PACKAGE_NAME)
    verify.set_defaults(func=cmd_verify)
    tracks = sub.add_parser("check-tracks")
    tracks.add_argument("--tracks", required=True, help="comma-separated Play track ids")
    tracks.set_defaults(func=cmd_check_tracks)
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except ReleaseError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
