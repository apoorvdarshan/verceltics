#!/usr/bin/env python3
"""Print the reviewed release-note section for an exact iOS or Android tag.

RELEASE_NOTES.md sections are headed `## ios-vX.Y` or `## android-vX.Y`. The
same text becomes the GitHub Release body and, when its switch is on, the App
Store or Google Play What's New.

    release_notes.py ios-v2.1               notes, or fail when there are none
    release_notes.py --if-present ios-v2.1  notes, or nothing when there are none
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
TAG_PATTERN = re.compile(r"(?:ios|android)-v\d+\.\d+(?:\.\d+)?")


def section_for_tag(tag: str, text: str | None = None) -> str | None:
    """The tag's notes, or None without a section. Duplicate or empty sections fail."""
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError("expected a tag such as ios-v3.0, ios-v3.0.1, or android-v1.0")
    if text is None:
        text = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    matches = []
    for section in re.split(r"^## ", text, flags=re.MULTILINE)[1:]:
        heading, _, body = section.partition("\n")
        if heading.strip() == tag:
            matches.append(body.strip())
    if len(matches) > 1:
        raise ValueError(f"RELEASE_NOTES.md has {len(matches)} ## {tag} sections; keep exactly one")
    if matches and not matches[0]:
        raise ValueError(f"the ## {tag} section in RELEASE_NOTES.md is empty")
    return matches[0] if matches else None


def notes_for_tag(tag: str, text: str | None = None) -> str:
    notes = section_for_tag(tag, text)
    if notes is None:
        raise ValueError(f"add exactly one nonempty, reviewed ## {tag} section to RELEASE_NOTES.md")
    return notes


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag")
    parser.add_argument("--if-present", action="store_true", help="print nothing instead of failing without notes")
    args = parser.parse_args()
    try:
        notes = section_for_tag(args.tag) if args.if_present else notes_for_tag(args.tag)
    except ValueError as exc:
        parser.error(str(exc))
    if notes is None:
        print(f"no ## {args.tag} section in RELEASE_NOTES.md", file=sys.stderr)
    else:
        print(notes)
