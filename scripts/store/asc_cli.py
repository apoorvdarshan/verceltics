#!/usr/bin/env python3
"""The one place the store scripts run the App Store Connect CLI (`asc`).

`asc` (https://github.com/rorkai/App-Store-Connect-CLI, MIT) is pinned to an exact
release whose binaries are checked against the SHA-256 values below before use.
Every call:

- authenticates from APP_STORE_CONNECT_API_KEY_ID / _ISSUER_ID / _KEY_P8 through
  asc's own environment variables, never the keychain or a config file;
- turns telemetry and the skills update check off;
- refuses every write when ASC_READ_ONLY is set (validation runs and read-only
  checks), which asc enforces before a request is sent;
- returns asc's JSON output, and on failure raises AscError with the key and
  issuer IDs removed from the message.

Install the pinned binary into a directory on PATH:

  python3 scripts/store/asc_cli.py install --dest "$RUNNER_TEMP/asc-bin"

To move to a newer asc, update ASC_VERSION and ASC_SHA256 from that release's
asc_<version>_checksums.txt, then re-run the store tests.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Sequence

ASC_VERSION = "5.15.0"
ASC_RELEASES = "https://github.com/rorkai/App-Store-Connect-CLI/releases/download"
# From asc_5.15.0_checksums.txt; the downloaded binary must match exactly.
ASC_SHA256 = {
    "linux_amd64": "614ae3190938f4826c8cfbbb836a1926f765963614c17fdeb86750883f71aa70",
    "macOS_arm64": "29b3f747acd94d1dc49884f4c0a7404181ec058c635d0ce20fc4202c930edf22",
}
# How long one asc call may run (uploads include Apple's processing wait).
DEFAULT_TIMEOUT_SECONDS = 1800

TRUE_VALUES = {"1", "true", "yes", "on"}


class AscError(Exception):
    """An asc command failed. `exit_code` is asc's exit status."""

    def __init__(self, args: Sequence[str], exit_code: int, detail: str) -> None:
        self.command = " ".join(args[:3])
        self.exit_code = exit_code
        super().__init__(f"asc {self.command} failed (exit {exit_code}): {detail}")


def redact(text: str, env: Mapping[str, str] | None = None) -> str:
    """Remove the App Store Connect key and issuer IDs from text that may be logged."""
    env = os.environ if env is None else env
    for name in ("APP_STORE_CONNECT_API_KEY_ID", "APP_STORE_CONNECT_ISSUER_ID", "ASC_KEY_ID", "ASC_ISSUER_ID"):
        value = (env.get(name) or "").strip()
        if len(value) >= 6:
            text = text.replace(value, "[redacted]")
    return text


def asc_environment(base: Mapping[str, str] | None = None, *, read_only: bool = False) -> dict[str, str]:
    """The environment one asc call runs with."""
    env = dict(os.environ if base is None else base)
    key_id = (env.get("APP_STORE_CONNECT_API_KEY_ID") or "").strip()
    issuer = (env.get("APP_STORE_CONNECT_ISSUER_ID") or "").strip()
    key_p8 = (env.get("APP_STORE_CONNECT_API_KEY_P8") or "").strip()
    if key_id and issuer and key_p8:
        env["ASC_KEY_ID"] = key_id
        env["ASC_ISSUER_ID"] = issuer
        env["ASC_PRIVATE_KEY"] = key_p8.replace("\\n", "\n")
    for name in ("ASC_PROFILE", "ASC_CONFIG_PATH", "ASC_PRIVATE_KEY_PATH", "ASC_PRIVATE_KEY_B64"):
        env.pop(name, None)
    env.update(
        {
            "ASC_BYPASS_KEYCHAIN": "1",
            "ASC_STRICT_AUTH": "1",
            "ASC_TELEMETRY_DISABLED": "1",
            "DO_NOT_TRACK": "1",
            "ASC_SKILLS_AUTO_CHECK": "0",
            "ASC_SPINNER_DISABLED": "1",
            "ASC_DEFAULT_OUTPUT": "json",
            "NO_COLOR": "1",
        }
    )
    if read_only or (env.get("ASC_READ_ONLY") or "").strip().lower() in TRUE_VALUES:
        env["ASC_READ_ONLY"] = "1"
    else:
        env.pop("ASC_READ_ONLY", None)
    return env


def verified_binary(name: str = "asc") -> str:
    """The absolute path of `name` on PATH, after checking it is the pinned build.

    Credentials are only ever handed to an executable whose SHA-256 matches the
    pinned release for this machine, whatever its version string claims.
    """
    path = shutil.which(name)
    if not path:
        raise AscError((name,), 127, f"{name} not found; install it with asc_cli.py install")
    digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    expected = ASC_SHA256[platform_key()]
    if digest != expected:
        raise AscError((name,), 126, f"{path} is not the pinned asc {ASC_VERSION} (sha256 {digest[:12]}…)")
    return path


class Asc:
    """Runs `asc` and returns its JSON output.

    Real runs use the asc on PATH only after verified_binary() has checked its
    checksum. Tests inject `runner` (and may name a fake `binary`); nothing else
    can redirect the executable.
    """

    def __init__(self, *, binary: str = "asc", read_only: bool = False, runner: Any = None) -> None:
        self.binary = binary
        self.read_only = read_only
        self._runner = runner or subprocess.run
        self._verify = runner is None
        self._path: str | None = None

    def executable(self) -> str:
        if not self._verify:
            return self.binary
        if self._path is None:
            self._path = verified_binary(self.binary)
        return self._path

    def run(self, *args: str, read_only: bool | None = None, timeout: int = DEFAULT_TIMEOUT_SECONDS) -> Any:
        argv = [self.executable(), *args]
        if "--output" not in args:
            argv += ["--output", "json"]
        env = asc_environment(read_only=self.read_only if read_only is None else read_only)
        try:
            done = self._runner(argv, env=env, capture_output=True, text=True, timeout=timeout, check=False)
        except subprocess.TimeoutExpired:
            raise AscError(args, 124, f"timed out after {timeout}s") from None
        except FileNotFoundError:
            raise AscError(args, 127, f"{self.binary} not found; install it with asc_cli.py install") from None
        stdout = done.stdout or ""
        if done.returncode != 0:
            detail = redact((done.stderr or stdout or "").strip())[-2000:]
            raise AscError(args, done.returncode, detail or "no output")
        if not stdout.strip():
            return {}
        try:
            return json.loads(stdout)
        except json.JSONDecodeError:
            raise AscError(args, 0, "returned output that is not JSON") from None


def check_version(asc: Asc) -> str:
    """Fail unless the asc on PATH has the pinned checksum and reports the pinned version."""
    try:
        path = asc.executable()
        done = subprocess.run([path, "version"], capture_output=True, text=True, check=True, timeout=60,
                              env=asc_environment())
    except AscError as exc:
        raise SystemExit(f"error: {exc}") from None
    except (OSError, subprocess.SubprocessError) as exc:
        raise SystemExit(f"error: cannot run {asc.binary}: {exc}") from None
    reported = (done.stdout or "").strip().split()[0] if done.stdout.strip() else ""
    if reported != ASC_VERSION:
        raise SystemExit(f"error: {asc.binary} reports {reported or 'no version'}, expected the pinned {ASC_VERSION}")
    return reported


def platform_key(system: str | None = None, machine: str | None = None) -> str:
    system = (system or platform.system()).lower()
    machine = (machine or platform.machine()).lower()
    if system == "darwin" and machine in {"arm64", "aarch64"}:
        return "macOS_arm64"
    if system == "linux" and machine in {"x86_64", "amd64"}:
        return "linux_amd64"
    raise SystemExit(f"error: no pinned asc binary for {system}/{machine}")


def install(dest: Path, *, key: str | None = None, opener: Any = None) -> Path:
    """Download the pinned asc for this machine, verify its SHA-256 and install it as dest/asc."""
    key = key or platform_key()
    expected = ASC_SHA256[key]
    url = f"{ASC_RELEASES}/{ASC_VERSION}/asc_{ASC_VERSION}_{key}"
    opener = opener or urllib.request.urlopen
    with opener(url, timeout=300) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise SystemExit(f"error: asc {ASC_VERSION} ({key}) checksum {actual} does not match the pinned {expected}")
    dest.mkdir(parents=True, exist_ok=True)
    target = dest / "asc"
    with tempfile.NamedTemporaryFile(dir=dest, delete=False) as handle:
        handle.write(data)
        tmp = Path(handle.name)
    tmp.chmod(tmp.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    shutil.move(str(tmp), target)
    print(f"installed asc {ASC_VERSION} ({key}, sha256 {actual[:12]}…) at {target}")
    return target


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    inst = sub.add_parser("install", help="download and verify the pinned asc")
    inst.add_argument("--dest", required=True, type=Path)
    sub.add_parser("check", help="fail unless the asc on PATH is the pinned release")
    args = parser.parse_args(argv)
    if args.command == "install":
        target = install(args.dest)
        github_path = os.environ.get("GITHUB_PATH")
        if github_path:
            with open(github_path, "a", encoding="utf-8") as handle:
                handle.write(f"{target.parent}\n")
    else:
        print(f"asc {check_version(Asc())} (pinned)")


if __name__ == "__main__":
    main()
