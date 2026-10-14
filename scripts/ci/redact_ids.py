#!/usr/bin/env python3
"""Remove App Store Connect key and issuer IDs from a log folder before it is uploaded.

Usage: redact_ids.py DIR   (reads ASC_KEY_ID and ASC_ISSUER_ID from the environment)

Artifacts are not masked like the console, and this repository is public, so
nothing that holds an ID may be uploaded:

  - text files: every occurrence is replaced with <ASC_KEY_ID> / <ASC_ISSUER_ID>
  - binary or UTF-16 files that hold an ID (in UTF-8, UTF-16LE or UTF-16BE, any
    letter case) are deleted, because rewriting them could corrupt them
  - file and folder names that hold an ID are renamed
  - symbolic links are deleted, so nothing outside DIR is uploaded

Afterwards every remaining file is searched again; the script fails (exit 1) if an
ID is still found, and the workflow then uploads nothing.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

PLACEHOLDERS = (("ASC_KEY_ID", "<ASC_KEY_ID>"), ("ASC_ISSUER_ID", "<ASC_ISSUER_ID>"))
ENCODINGS = ("utf-8", "utf-16-le", "utf-16-be")


def secrets_from(env: dict[str, str]) -> list[tuple[str, str]]:
    """(value, placeholder) for every ID that is set."""
    return [(env[name].strip(), placeholder) for name, placeholder in PLACEHOLDERS if env.get(name, "").strip()]


def holds(data: bytes, value: str) -> bool:
    lowered = data.lower()
    return any(value.lower().encode(encoding) in lowered for encoding in ENCODINGS)


def is_text(data: bytes) -> bool:
    if b"\0" in data:
        return False
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def redact_name(name: str, secrets: list[tuple[str, str]]) -> str:
    for value, placeholder in secrets:
        name = re.sub(re.escape(value), placeholder, name, flags=re.IGNORECASE)
    return name


def redact(root: Path, secrets: list[tuple[str, str]]) -> list[str]:
    """Clean root in place; returns what was done, one line per change."""
    report: list[str] = []
    if not secrets or not root.is_dir():
        return report
    # Deepest paths first, so renaming a folder never invalidates a path still to visit.
    for directory, folders, files in os.walk(root, topdown=False):
        base = Path(directory)
        for name in files + folders:
            path = base / name
            shown = path.relative_to(root)
            if path.is_symlink():
                path.unlink()
                report.append(f"removed link {shown}")
                continue
            if path.is_file():
                data = path.read_bytes()
                if any(holds(data, value) for value, _ in secrets):
                    if is_text(data):
                        text = data.decode("utf-8")
                        for value, placeholder in secrets:
                            text = re.sub(re.escape(value), placeholder, text, flags=re.IGNORECASE)
                        path.write_text(text, encoding="utf-8")
                        report.append(f"redacted {shown}")
                    else:
                        path.unlink()
                        report.append(f"removed {shown} (binary file holding an ID)")
                        continue
            renamed = redact_name(name, secrets)
            if renamed != name:
                path.rename(base / renamed)
                report.append(f"renamed {shown}")
    return report


def leftovers(root: Path, secrets: list[tuple[str, str]]) -> list[str]:
    found = []
    for path in sorted(root.rglob("*")):
        shown = str(path.relative_to(root))
        if any(value.lower() in shown.lower() for value, _ in secrets):
            found.append(shown)
        elif path.is_file() and not path.is_symlink():
            if any(holds(path.read_bytes(), value) for value, _ in secrets):
                found.append(shown)
    return found


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = Path(argv[1])
    secrets = secrets_from(dict(os.environ))
    if not secrets:
        print("no App Store Connect IDs to redact")
        return 0
    for line in redact(root, secrets):
        print(line)
    remaining = leftovers(root, secrets) if root.is_dir() else []
    if remaining:
        print(f"error: an App Store Connect ID is still in {', '.join(remaining)}; not uploading", file=sys.stderr)
        return 1
    print(f"no App Store Connect ID left in {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
