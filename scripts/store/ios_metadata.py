#!/usr/bin/env python3
"""The App Store listing kept in this repository, checked offline.

store/metadata/ios/<locale>/ holds one text file per field, exported from the
live App Store listing with export_ios_listing.py. What's New comes from the
tag's RELEASE_NOTES.md section, converted to plain text (App Store Connect shows
Markdown literally). Screenshots come from store/app-store/screenshots/: iphone/
and ipad/ (required), iphone-duo/ and watch/ (optional; an Apple Watch frame's
size picks its set). asc_release.py uploads RGB copies of them, never these files.

    ios_metadata.py check [--listing] [--screenshots]
                                          validate listing text and/or screenshots
                                          (both when neither flag is given)
    ios_metadata.py prepare --out DIR [--tag ios-vX.Y] [--no-listing]
                                          copy the listing (and the tag's What's New)
                                          into DIR/ios/<locale>/ for asc_release.py;
                                          --no-listing writes only What's New and
                                          never reads the listing
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
from release_notes import plain_text, section_for_tag  # noqa: E402

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
        ROOT / "store" / "app-store" / "screenshots" / "iphone",
        frozenset({(1290, 2796), (2796, 1290), (1284, 2778), (2778, 1284)}),
    ),
    ScreenshotSet(
        "APP_IPAD_PRO_3GEN_129",
        'iPad 13"',
        ROOT / "store" / "app-store" / "screenshots" / "ipad",
        frozenset({(2048, 2732), (2732, 2048), (2064, 2752), (2752, 2064)}),
    ),
)
# Uploaded with the sets above when their folder holds PNGs; without any, the
# live set is left alone. One iPhone Duo set takes both screens: outer 1398x2034
# and inner 2007x2853. APP_IPHONE_DUO is not in Apple's OpenAPI spec yet, but App
# Store Connect accepts it.
OPTIONAL_SCREENSHOT_SETS = (
    ScreenshotSet(
        "APP_IPHONE_DUO",
        "iPhone Duo",
        ROOT / "store" / "app-store" / "screenshots" / "iphone-duo",
        frozenset({(1398, 2034), (2007, 2853)}),
    ),
)
# Apple Watch screenshots, also optional: without PNGs in this folder the live
# Watch sets are left alone. Each frame's size picks its display type (Apple's
# screenshot specifications), so one folder can fill more than one Watch set.
WATCH_FOLDER = "watch"
WATCH_SCREENSHOTS = ROOT / "store" / "app-store" / "screenshots" / WATCH_FOLDER
WATCH_DISPLAY_TYPES = {
    (416, 496): "APP_WATCH_SERIES_10",  # Series 10 and 11
    (422, 514): "APP_WATCH_ULTRA",  # Ultra 3
    (410, 502): "APP_WATCH_ULTRA",  # Ultra and Ultra 2
    (396, 484): "APP_WATCH_SERIES_7",  # Series 7 to 9
    (368, 448): "APP_WATCH_SERIES_4",  # Series 4 to 6 and SE
    (312, 390): "APP_WATCH_SERIES_3",
}
WATCH_LABELS = {
    "APP_WATCH_SERIES_10": "Apple Watch Series 10",
    "APP_WATCH_ULTRA": "Apple Watch Ultra",
    "APP_WATCH_SERIES_7": "Apple Watch Series 7",
    "APP_WATCH_SERIES_4": "Apple Watch Series 4",
    "APP_WATCH_SERIES_3": "Apple Watch Series 3",
}
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


def optional_screenshot_files(spec: ScreenshotSet) -> list[Path]:
    """An optional set's PNGs in display order; none (an empty or missing folder) leaves the live set alone."""
    if not (spec.directory.is_dir() and any(spec.directory.glob("*.png"))):
        return []
    return screenshot_files(spec)


def watch_screenshot_sets(directory: Path | None = None) -> list[tuple[ScreenshotSet, list[Path]]]:
    """The Apple Watch frames grouped by the display type their size picks, in file name order."""
    directory = WATCH_SCREENSHOTS if directory is None else directory
    groups: dict[str, list[Path]] = {}
    for path in sorted(directory.glob("*.png")) if directory.is_dir() else []:
        size = png_size(path)
        display_type = WATCH_DISPLAY_TYPES.get(size)
        if display_type is None:
            accepted = ", ".join(f"{w}x{h}" for w, h in WATCH_DISPLAY_TYPES)
            raise MetadataError(
                f"the Apple Watch screenshot {path.name} is {size[0]}x{size[1]}, which is not an "
                f"Apple Watch screenshot size ({accepted})"
            )
        groups.setdefault(display_type, []).append(path)
    sets = []
    for display_type, files in groups.items():
        label = WATCH_LABELS[display_type]
        if len(files) > SCREENSHOTS_PER_SET:
            raise MetadataError(f"{len(files)} {label} screenshots; App Store Connect accepts {SCREENSHOTS_PER_SET}")
        sizes = frozenset(size for size, kind in WATCH_DISPLAY_TYPES.items() if kind == display_type)
        sets.append((ScreenshotSet(display_type, label, directory, sizes), files))
    return sets


def screenshot_sets() -> list[tuple[ScreenshotSet, list[Path]]]:
    """Every set a screenshot upload sends, with its PNGs: iPhone and iPad (both
    required), then iPhone Duo and the Apple Watch sets when they have frames."""
    sets = [(spec, screenshot_files(spec)) for spec in SCREENSHOT_SETS]
    sets += [(spec, files) for spec in OPTIONAL_SCREENSHOT_SETS if (files := optional_screenshot_files(spec))]
    return sets + watch_screenshot_sets()


def app_store_whats_new(notes: str, tag: str) -> str:
    """Reviewed notes as plain App Store text, within the What's New limit."""
    text = plain_text(notes)
    if not text:
        raise MetadataError(f"the ## {tag} section in RELEASE_NOTES.md has no text for What's New")
    validate_value(WHATS_NEW, text, f"{tag} release notes")
    return text


def whats_new(tag: str) -> str:
    """The tag's reviewed notes as App Store What's New; fails without usable notes."""
    notes = section_for_tag(tag)
    if notes is None:
        raise MetadataError(f"What's New needs a ## {tag} section in RELEASE_NOTES.md")
    return app_store_whats_new(notes, tag)


def prepare(
    out: Path, tag: str | None = None, listing_dir: Path = LISTING_DIR, *, include_listing: bool = True
) -> list[Path]:
    """Copy the validated listing to out/ios/<locale>/ and add the tag's What's New.

    Without include_listing (IOS_UPLOAD_LISTING off) the listing is neither read
    nor copied, so an unused listing never blocks a release: only What's New is
    written.
    """
    written = []
    if include_listing:
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
            path = out / "ios" / PRIMARY_LOCALE / WHATS_NEW.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(app_store_whats_new(notes, tag) + "\n", encoding="utf-8")
            written.append(path)
    return written


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--listing", action="store_true", help="check the listing text")
    check.add_argument("--screenshots", action="store_true", help="check the screenshot sets")
    prep = sub.add_parser("prepare")
    prep.add_argument("--out", required=True, type=Path)
    prep.add_argument("--tag", help="add this tag's RELEASE_NOTES.md section as What's New")
    prep.add_argument("--no-listing", action="store_true",
                      help="skip the listing (only What's New), when the listing is not uploaded")
    notes = sub.add_parser("whats-new")
    notes.add_argument("--tag", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "whats-new":
            print(whats_new(args.tag))
        elif args.command == "check":
            # Release runs check only what they upload; with no flag, check everything.
            everything = not (args.listing or args.screenshots)
            if args.listing or everything:
                for locale in locales(LISTING_DIR):
                    values = load_locale(LISTING_DIR / locale)
                    print(f"listing {locale}: {len(values)} fields OK")
            if args.screenshots or everything:
                sets = screenshot_sets()
                for spec, files in sets:
                    print(f"screenshots {spec.label}: {len(files)} PNGs OK")
                shown = {spec.display_type for spec, _ in sets}
                for spec in OPTIONAL_SCREENSHOT_SETS:
                    if spec.display_type not in shown:
                        print(f"screenshots {spec.label}: none; the live set is left alone")
                if not shown & set(WATCH_LABELS):
                    print("screenshots Apple Watch: none; the live Watch sets are left alone")
        else:
            written = prepare(args.out, args.tag, LISTING_DIR, include_listing=not args.no_listing)
            has_whats_new = any(path.name == WHATS_NEW.filename for path in written)
            remarks = (["listing not included"] if args.no_listing else []) + (
                [] if has_whats_new else ["no What's New for this run"])
            print(f"prepared {len(written)} App Store metadata files under {args.out / 'ios'}"
                  + (f" ({'; '.join(remarks)})" if remarks else ""))
    except (MetadataError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
