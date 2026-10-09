#!/usr/bin/env python3
"""Print a reviewed release-note section for an exact iOS or Android tag."""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def notes_for_tag(tag: str) -> str:
    if not re.fullmatch(r"(?:ios|android)-v\d+\.\d+(?:\.\d+)?", tag):
        raise ValueError("expected a tag such as ios-v3.0, ios-v3.0.1, or android-v1.0")
    text = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    matches = []
    for section in re.split(r"^## ", text, flags=re.MULTILINE)[1:]:
        heading, _, body = section.partition("\n")
        if heading.strip() == tag:
            matches.append(body.strip())
    if len(matches) != 1 or not matches[0]:
        raise ValueError(f"add exactly one nonempty, reviewed ## {tag} section to RELEASE_NOTES.md before tagging")
    return matches[0]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        print(notes_for_tag(args.tag))
    except ValueError as exc:
        parser.error(str(exc))
