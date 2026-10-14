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
ID is still found, and the workflow then uploads nothing. Messages only ever show
redacted names.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

PLACEHOLDERS = (("ASC_KEY_ID", "<ASC_KEY_ID>"), ("ASC_ISSUER_ID", "<ASC_ISSUER_ID>"))
ENCODINGS = ("utf-8", "utf-16-le", "utf-16-be")
# Lowercases ASCII letters only, so a string keeps its length and indices line up.
ASCII_LOWER = str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")


def ids_from(env: dict[str, str]) -> list[tuple[str, str]]:
    """(lowercased ID, placeholder) for every ID that is set."""
    return [
        (env[name].strip().translate(ASCII_LOWER), placeholder)
        for name, placeholder in PLACEHOLDERS
        if env.get(name, "").strip()
    ]


def holds(data: bytes, needle: str) -> bool:
    lowered = data.lower()
    return any(needle.encode(encoding) in lowered for encoding in ENCODINGS)


def is_text(data: bytes) -> bool:
    if b"\0" in data:
        return False
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def scrub(text: str, ids: list[tuple[str, str]]) -> str:
    """text with every ID (any ASCII letter case) replaced by its placeholder.

    Only slices of text and the placeholders are kept, never the IDs themselves.
    """
    for needle, placeholder in ids:
        lowered = text.translate(ASCII_LOWER)
        parts: list[str] = []
        start = 0
        found = lowered.find(needle)
        while found != -1:
            parts.append(text[start:found])
            parts.append(placeholder)
            start = found + len(needle)
            found = lowered.find(needle, start)
        parts.append(text[start:])
        text = "".join(parts)
    return text


def redact(root: Path, ids: list[tuple[str, str]]) -> list[str]:
    """Clean root in place; returns what was done, one line per change."""
    report: list[str] = []
    if not ids or not root.is_dir():
        return report
    # Deepest paths first, so renaming a folder never invalidates a path still to visit.
    for directory, folders, files in os.walk(root, topdown=False):
        base = Path(directory)
        for name in files + folders:
            path = base / name
            shown = scrub(str(path.relative_to(root)), ids)
            if path.is_symlink():
                path.unlink()
                report.append(f"removed link {shown}")
                continue
            if path.is_file():
                data = path.read_bytes()
                if any(holds(data, needle) for needle, _ in ids):
                    if is_text(data):
                        path.write_text(scrub(data.decode("utf-8"), ids), encoding="utf-8")
                        report.append(f"redacted {shown}")
                    else:
                        path.unlink()
                        report.append(f"removed {shown} (binary file holding an ID)")
                        continue
            renamed = scrub(name, ids)
            if renamed != name:
                path.rename(base / renamed)
                report.append(f"renamed {shown}")
    return report


def leftovers(root: Path, ids: list[tuple[str, str]]) -> list[str]:
    """Redacted names of everything that still holds an ID, in its name or content."""
    found = []
    for path in sorted(root.rglob("*")):
        name = str(path.relative_to(root))
        in_name = any(needle in name.translate(ASCII_LOWER) for needle, _ in ids)
        in_data = path.is_file() and not path.is_symlink() and any(
            holds(path.read_bytes(), needle) for needle, _ in ids
        )
        if in_name or in_data:
            found.append(scrub(name, ids))
    return found


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = Path(argv[1])
    ids = ids_from(dict(os.environ))
    if not ids:
        print("no App Store Connect IDs to redact")
        return 0
    for line in redact(root, ids):
        print(line)
    remaining = leftovers(root, ids) if root.is_dir() else []
    if remaining:
        print(f"error: an App Store Connect ID is still in {', '.join(remaining)}; not uploading", file=sys.stderr)
        return 1
    print(f"no App Store Connect ID left in {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
