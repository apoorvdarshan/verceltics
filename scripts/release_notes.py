#!/usr/bin/env python3
"""Print the reviewed release-note section for an exact iOS or Android tag.

RELEASE_NOTES.md sections are headed `## ios-vX.Y` or `## android-vX.Y`. The
same text becomes the GitHub Release body (Markdown) and, when its switch is on,
the App Store or Google Play What's New (as plain text, see plain_text()).

    release_notes.py ios-v2.1               notes, or fail when there are none
    release_notes.py --if-present ios-v2.1  notes, or nothing when there are none

A missing RELEASE_NOTES.md counts as "no notes": --if-present prints nothing and
the GitHub Release gets generated notes. Duplicate or empty sections always fail.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
TAG_PATTERN = re.compile(r"(?:ios|android)-v\d+(?:\.\d+){0,2}")


def section_for_tag(tag: str, text: str | None = None) -> str | None:
    """The tag's notes, or None without a section (or file). Duplicate or empty sections fail."""
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError("expected a tag such as ios-v3.0, ios-v3.0.1, or android-v1.0")
    if text is None:
        path = ROOT / "RELEASE_NOTES.md"
        if not path.is_file():
            return None
        text = path.read_text(encoding="utf-8")
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


def plain_text(notes: str) -> str:
    """Reviewed Markdown notes as plain store text.

    App Store Connect and Google Play show What's New literally, so the Markdown
    that suits the GitHub Release body is converted: images and links keep their
    text, list markers become bullets, and heading, quote, bold and code markers
    are dropped.
    """
    lines = [line for line in notes.splitlines() if not re.fullmatch(r"\s*(```|~~~).*", line)]
    text = "\n".join(lines)
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"^#{1,6}[ \t]+", "", text, flags=re.MULTILINE)
    text = re.sub(r"^>[ \t]?", "", text, flags=re.MULTILINE)
    text = re.sub(r"^([ \t]*)[-*+][ \t]+", r"\1• ", text, flags=re.MULTILINE)
    text = text.replace("**", "").replace("`", "")
    return text.strip()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag")
    parser.add_argument("--if-present", action="store_true", help="print nothing instead of failing without notes")
    args = parser.parse_args(argv)
    try:
        notes = section_for_tag(args.tag) if args.if_present else notes_for_tag(args.tag)
    except ValueError as exc:
        parser.error(str(exc))
    if notes is None:
        print(f"no ## {args.tag} section in RELEASE_NOTES.md", file=sys.stderr)
    else:
        print(notes)


if __name__ == "__main__":
    main()
