#!/usr/bin/env python3
"""Prepare and validate local iOS listing text. Never contacts a store."""
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from release_notes import notes_for_tag

FIELDS = {
    "App Name": ("name.txt", 30),
    "Subtitle (30 chars max)": ("subtitle.txt", 30),
    "Promotional Text (170 chars max)": ("promotional_text.txt", 170),
    "Keywords (100 chars max)": ("keywords.txt", 100),
    "Description": ("description.txt", 4000),
    "Privacy URL": ("privacy_url.txt", None),
    "Support URL": ("support_url.txt", None),
    "Marketing URL": ("marketing_url.txt", None),
}


def field(text: str, heading: str) -> str:
    sections = re.split(r"^## ", text, flags=re.MULTILINE)[1:]
    matches = [s.partition("\n")[2] for s in sections if s.partition("\n")[0].strip() == heading]
    if len(matches) != 1:
        raise ValueError(f"expected exactly one APPSTORE.md heading: {heading}")
    fenced = re.search(r"^```[^\n]*\n(.*?)^```\s*$", matches[0], re.MULTILINE | re.DOTALL)
    if not fenced or not fenced.group(1).strip():
        raise ValueError(f"missing nonempty fenced text under {heading}")
    return fenced.group(1).strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--tag", help="use reviewed notes for this exact tag as What's New")
    args = parser.parse_args()
    text = (ROOT / "APPSTORE.md").read_text(encoding="utf-8")
    values = {}
    try:
        for heading, (filename, cap) in FIELDS.items():
            value = field(text, heading)
            if cap and len(value) > cap:
                raise ValueError(f"{heading}: {len(value)} characters exceeds {cap}")
            if filename.endswith("_url.txt") and not value.startswith("https://"):
                raise ValueError(f"{heading} must use HTTPS")
            values[filename] = value
        if args.tag:
            notes = notes_for_tag(args.tag)
        else:
            headings = re.findall(r"^## (What's New[^\n]*)$", text, re.MULTILINE)
            if len(headings) != 1:
                raise ValueError("expected exactly one What's New section in APPSTORE.md")
            notes = field(text, headings[0])
        if len(notes) > 4000:
            raise ValueError("What's New exceeds 4000 characters")
        values["whats_new.txt"] = notes
    except ValueError as exc:
        parser.error(str(exc))
    output = args.out / "ios" / "en-US"
    output.mkdir(parents=True, exist_ok=True)
    for filename, value in values.items():
        (output / filename).write_text(value + "\n", encoding="utf-8")
    print(f"Prepared {len(values)} iOS metadata fields under {output}")


if __name__ == "__main__":
    main()
