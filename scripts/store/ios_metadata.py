#!/usr/bin/env python3
"""The App Store listing kept in this repository, checked offline.

store/metadata/ios/<locale>/ holds one text file per field, exported from the
live App Store listing with export_ios_listing.py. What's New comes from the
tag's RELEASE_NOTES.md section. Screenshots come from the marketing mockups.

    ios_metadata.py check                 validate listing text and screenshots
    ios_metadata.py prepare --out DIR [--tag ios-vX.Y]
                                          copy the listing (and the tag's What's New)
                                          into DIR/ios/<locale>/ for asc_release.py
    ios_metadata.py whats-new --tag ios-vX.Y
                                          print the tag's What's New, or fail when the
                                          notes are missing or too long
Never contacts App Store Connect.
"""
from __future__ import annotations

import argparse
import shutil
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from release_notes import section_for_tag  # noqa: E402

LISTING_DIR = ROOT / "store" / "metadata" / "ios"
PRIMARY_LOCALE = "en-US"


@dataclass(frozen=True)
class Field:
    filename: str
    attribute: str
    limit: int | None = None
    url: bool = False
    required: bool = False


# Fields of the App Store version localization (editable with each new version).
VERSION_FIELDS = (
    Field("description.txt", "description", 4000, required=True),
    Field("keywords.txt", "keywords", 100),
    Field("promotional_text.txt", "promotionalText", 170),
    Field("marketing_url.txt", "marketingUrl", url=True),
    Field("support_url.txt", "supportUrl", url=True, required=True),
)
# Fields of the app's App Info localization (editable while a new version is prepared).
APP_INFO_FIELDS = (
    Field("name.txt", "name", 30, required=True),
    Field("subtitle.txt", "subtitle", 30),
    Field("privacy_url.txt", "privacyPolicyUrl", url=True),
)
WHATS_NEW = Field("whats_new.txt", "whatsNew", 4000)
LISTING_FILES = {field.filename for field in VERSION_FIELDS + APP_INFO_FIELDS}


@dataclass(frozen=True)
class ScreenshotSet:
    display_type: str
    label: str
    directory: Path
    sizes: frozenset[tuple[int, int]]


SCREENSHOT_SETS = (
    ScreenshotSet(
        "APP_IPHONE_67",
        'iPhone 6.7"',
        ROOT / "marketing" / "mockups ios",
        frozenset({(1290, 2796), (2796, 1290), (1284, 2778), (2778, 1284)}),
    ),
    ScreenshotSet(
        "APP_IPAD_PRO_3GEN_129",
        'iPad 13"',
        ROOT / "marketing" / "mockups ipad",
        frozenset({(2048, 2732), (2732, 2048), (2064, 2752), (2752, 2064)}),
    ),
)
SCREENSHOTS_PER_SET = 10


class MetadataError(Exception):
    """The repository listing cannot be uploaded as it is."""


def read_field(path: Path) -> str | None:
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8").strip()
    return text or None


def locales(listing_dir: Path = LISTING_DIR) -> list[str]:
    found = sorted(p.name for p in listing_dir.iterdir() if p.is_dir()) if listing_dir.is_dir() else []
    if not found:
        raise MetadataError(f"no App Store listing locales under {listing_dir}")
    if PRIMARY_LOCALE not in found:
        raise MetadataError(f"{listing_dir} has no {PRIMARY_LOCALE} listing (the app's primary locale)")
    return found


def validate_value(field: Field, value: str, where: str) -> None:
    if field.limit is not None and len(value) > field.limit:
        raise MetadataError(f"{where}: {len(value)} characters, the limit is {field.limit}")
    if field.url and not value.startswith("https://"):
        raise MetadataError(f"{where}: must be an https:// URL")


def load_locale(locale_dir: Path, *, prepared: bool = False) -> dict[str, str]:
    """Validated listing text for one locale, keyed by file name.

    The repository listing never holds What's New (it comes from RELEASE_NOTES.md);
    a prepared directory may.
    """
    allowed = LISTING_FILES | ({WHATS_NEW.filename} if prepared else set())
    unknown = sorted(
        p.name for p in locale_dir.iterdir()
        if p.is_file() and p.name not in allowed and not p.name.startswith(".")
    )
    if unknown:
        raise MetadataError(f"{locale_dir}: unexpected file(s) {', '.join(unknown)}")
    values = {}
    for field in VERSION_FIELDS + APP_INFO_FIELDS:
        where = f"{locale_dir.name}/{field.filename}"
        value = read_field(locale_dir / field.filename)
        if value is None:
            if field.required:
                raise MetadataError(f"{where} is missing or empty")
            continue
        validate_value(field, value, where)
        values[field.filename] = value
    return values


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise MetadataError(f"{path.name} is not a PNG image")
    return struct.unpack(">II", header[16:24])


def screenshot_files(spec: ScreenshotSet) -> list[Path]:
    """The set's PNGs in display order, or a clear error when there are none."""
    files = sorted(spec.directory.glob("*.png")) if spec.directory.is_dir() else []
    if not files:
        raise MetadataError(
            f"no {spec.label} screenshots: add App Store-ready PNGs to {spec.directory.relative_to(ROOT)} "
            "or leave IOS_UPLOAD_SCREENSHOTS off"
        )
    if len(files) > SCREENSHOTS_PER_SET:
        raise MetadataError(f"{len(files)} {spec.label} screenshots; App Store Connect accepts {SCREENSHOTS_PER_SET}")
    for path in files:
        size = png_size(path)
        if size not in spec.sizes:
            accepted = ", ".join(f"{w}x{h}" for w, h in sorted(spec.sizes))
            raise MetadataError(f"{path.name} is {size[0]}x{size[1]}; {spec.label} accepts {accepted}")
    return files


def whats_new(tag: str) -> str:
    """The tag's reviewed notes as App Store What's New; fails without usable notes."""
    notes = section_for_tag(tag)
    if notes is None:
        raise MetadataError(f"What's New needs a ## {tag} section in RELEASE_NOTES.md")
    validate_value(WHATS_NEW, notes, f"{tag} release notes")
    return notes


def prepare(out: Path, tag: str | None = None, listing_dir: Path = LISTING_DIR) -> list[Path]:
    """Copy the validated listing to out/ios/<locale>/ and add the tag's What's New."""
    written = []
    for locale in locales(listing_dir):
        load_locale(listing_dir / locale)
        target = out / "ios" / locale
        target.mkdir(parents=True, exist_ok=True)
        for path in sorted((listing_dir / locale).iterdir()):
            if path.name in LISTING_FILES:
                shutil.copyfile(path, target / path.name)
                written.append(target / path.name)
    if tag:
        notes = section_for_tag(tag)
        if notes is not None:
            validate_value(WHATS_NEW, notes, f"{tag} release notes")
            path = out / "ios" / PRIMARY_LOCALE / WHATS_NEW.filename
            path.write_text(notes + "\n", encoding="utf-8")
            written.append(path)
    return written


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    prep = sub.add_parser("prepare")
    prep.add_argument("--out", required=True, type=Path)
    prep.add_argument("--tag", help="add this tag's RELEASE_NOTES.md section as What's New")
    notes = sub.add_parser("whats-new")
    notes.add_argument("--tag", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "whats-new":
            print(whats_new(args.tag))
        elif args.command == "check":
            for locale in locales():
                values = load_locale(LISTING_DIR / locale)
                print(f"listing {locale}: {len(values)} fields OK")
            for spec in SCREENSHOT_SETS:
                print(f"screenshots {spec.label}: {len(screenshot_files(spec))} PNGs OK")
        else:
            written = prepare(args.out, args.tag)
            has_whats_new = any(path.name == WHATS_NEW.filename for path in written)
            print(f"prepared {len(written)} listing files under {args.out / 'ios'}"
                  + ("" if has_whats_new else " (no What's New for this run)"))
    except (MetadataError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
