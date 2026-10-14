#!/usr/bin/env python3
"""Google Play listing text, phone screenshots and What's New.

store/metadata/play/<locale>/ holds the Play listing (title, short and full
description). Phone screenshots come from store/google-play/screenshots/phone/.
What's New comes from the tag's RELEASE_NOTES.md section, as plain text.

    play_listing.py check [--listing] [--screenshots]
                                                validate listing text and/or screenshots
                                                (both when neither flag is given)
    play_listing.py prepare --out DIR [--tag android-vX.Y] [--no-listing]
                                                copy the listing to DIR/play/<locale>/ and
                                                write DIR/play/whatsnew-en-US for the tag
                                                (exactly the text Play receives: no
                                                trailing newline)
    play_listing.py upload [--metadata-dir DIR] [--dry-run]
                                                upload what UPLOAD_LISTING / UPLOAD_SCREENSHOTS
                                                switch on, in one Play edit (gated in CI)
    play_listing.py whats-new --tag android-vX.Y
                                                print the tag's Play What's New, or fail when
                                                the notes are missing or too long
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from release_notes import plain_text, section_for_tag  # noqa: E402

PACKAGE_NAME = "com.apoorvdarshan.verceltics"
LISTING_DIR = ROOT / "store" / "metadata" / "play"
SCREENSHOTS_DIR = ROOT / "store" / "google-play" / "screenshots" / "phone"
PRIMARY_LOCALE = "en-US"
# Google Play limits, per language.
FIELDS = {
    "title.txt": ("title", 30),
    "short_description.txt": ("shortDescription", 80),
    "full_description.txt": ("fullDescription", 4000),
}
WHATS_NEW_LIMIT = 500
# Play phone screenshots: 2–8 images, sides 320–3840 px, long side at most twice the short side.
SCREENSHOT_COUNT = (2, 8)
SCREENSHOT_SIDE = (320, 3840)


class ListingError(Exception):
    """The Play listing cannot be uploaded as it is."""


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def truthy(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def read_text(path: Path) -> str | None:
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8").strip()
    return text or None


def locales(listing_dir: Path = LISTING_DIR) -> list[str]:
    found = sorted(p.name for p in listing_dir.iterdir() if p.is_dir()) if listing_dir.is_dir() else []
    if PRIMARY_LOCALE not in found:
        raise ListingError(f"{listing_dir} has no {PRIMARY_LOCALE} Play listing")
    return found


def load_locale(locale_dir: Path) -> dict[str, str]:
    """Every listing field for one locale, validated against Play's limits."""
    unknown = sorted(p.name for p in locale_dir.iterdir()
                     if p.is_file() and p.name not in FIELDS and not p.name.startswith("."))
    if unknown:
        raise ListingError(f"{locale_dir}: unexpected file(s) {', '.join(unknown)}")
    values = {}
    for filename, (_, limit) in FIELDS.items():
        value = read_text(locale_dir / filename)
        if value is None:
            raise ListingError(f"{locale_dir.name}/{filename} is missing or empty")
        if len(value) > limit:
            raise ListingError(f"{locale_dir.name}/{filename}: {len(value)} characters, the limit is {limit}")
        values[filename] = value
    return values


def play_whats_new(notes: str) -> str:
    """Reviewed release notes as plain Play Store text, at most WHATS_NEW_LIMIT characters."""
    text = plain_text(notes)
    if not text:
        raise ListingError("the release notes have no text for Play What's New")
    if len(text) > WHATS_NEW_LIMIT:
        raise ListingError(f"Play What's New is limited to {WHATS_NEW_LIMIT} characters (the notes have {len(text)})")
    return text


def whats_new(tag: str) -> str:
    """The tag's reviewed notes as Play What's New; fails without usable notes."""
    notes = section_for_tag(tag)
    if notes is None:
        raise ListingError(f"Play What's New needs a ## {tag} section in RELEASE_NOTES.md")
    return play_whats_new(notes)


def image_size(path: Path) -> tuple[int, int]:
    data = path.read_bytes()[:32]
    if data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        return struct.unpack(">II", data[16:24])
    raise ListingError(f"{path.name} is not a PNG image")


def screenshot_files(directory: Path = SCREENSHOTS_DIR) -> list[Path]:
    files = sorted(directory.glob("*.png")) if directory.is_dir() else []
    low, high = SCREENSHOT_COUNT
    if not low <= len(files) <= high:
        raise ListingError(f"Play needs {low}–{high} phone screenshots in {directory}, found {len(files)}")
    for path in files:
        width, height = image_size(path)
        smallest, largest = SCREENSHOT_SIDE
        if not (smallest <= min(width, height) and max(width, height) <= largest):
            raise ListingError(f"{path.name} is {width}x{height}; sides must be {smallest}–{largest} px")
        if max(width, height) > 2 * min(width, height):
            raise ListingError(f"{path.name} is {width}x{height}; the long side may be at most twice the short side")
    return files


def prepare(
    out: Path, tag: str | None = None, listing_dir: Path = LISTING_DIR, *, include_listing: bool = True
) -> list[Path]:
    """Copy the validated listing to out/play/<locale>/ and the tag's notes to out/play/whatsnew-en-US.

    The What's New file holds exactly the text Play receives (the upload action sends
    the file as it is), so it has no trailing newline. Without include_listing the
    listing is neither read nor copied, so an unused listing never blocks a release.
    """
    written = []
    if include_listing:
        for locale in locales(listing_dir):
            load_locale(listing_dir / locale)
            target = out / "play" / locale
            target.mkdir(parents=True, exist_ok=True)
            for filename in FIELDS:
                shutil.copyfile(listing_dir / locale / filename, target / filename)
                written.append(target / filename)
    if tag:
        notes = section_for_tag(tag)
        if notes is not None:
            path = out / "play" / f"whatsnew-{PRIMARY_LOCALE}"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(play_whats_new(notes), encoding="utf-8")
            written.append(path)
    return written


def load_service_account_info() -> dict:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        fail("PLAY_SERVICE_ACCOUNT_JSON is not set")
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        fail(f"PLAY_SERVICE_ACCOUNT_JSON is not valid JSON: {exc}")


def build_android_publisher(credentials_info: dict):
    from google.oauth2 import service_account
    from googleapiclient.discovery import build

    creds = service_account.Credentials.from_service_account_info(
        credentials_info,
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False)


def upload_play_store_listing(
    service,
    *,
    package_name: str,
    metadata_dir: Path,
    screenshots: list[Path],
    upload_listing: bool,
    upload_screenshots: bool,
) -> None:
    """One Play edit: listing text for every locale and/or the primary locale's phone screenshots."""
    from googleapiclient.http import MediaFileUpload

    edits = service.edits()
    edit_id = edits.insert(packageName=package_name, body={}).execute()["id"]
    print(f"  opened Play edit {edit_id}")
    try:
        if upload_listing:
            for locale in locales(metadata_dir / "play"):
                values = load_locale(metadata_dir / "play" / locale)
                body = {FIELDS[name][0]: value for name, value in values.items()}
                edits.listings().update(
                    packageName=package_name, editId=edit_id, language=locale, body=body
                ).execute()
                print(f"  updated Play listing ({locale})")

        if upload_screenshots:
            edits.images().deleteall(
                packageName=package_name, editId=edit_id, language=PRIMARY_LOCALE, imageType="phoneScreenshots"
            ).execute()
            print(f"  cleared existing Play phone screenshots ({PRIMARY_LOCALE})")
            for path in screenshots:
                media = MediaFileUpload(str(path), mimetype="image/png", resumable=False)
                edits.images().upload(
                    packageName=package_name, editId=edit_id, language=PRIMARY_LOCALE,
                    imageType="phoneScreenshots", media_body=media,
                ).execute()
                print(f"  uploaded Play phone screenshot {path.name}")

        edits.commit(packageName=package_name, editId=edit_id).execute()
        print("  committed Play edit")
    except Exception:
        try:
            edits.delete(packageName=package_name, editId=edit_id).execute()
        except Exception:  # noqa: BLE001 - the original error matters more
            pass
        raise


def cmd_upload(args: argparse.Namespace) -> None:
    do_listing = truthy("UPLOAD_LISTING")
    do_screenshots = truthy("UPLOAD_SCREENSHOTS")
    if not (do_listing or do_screenshots):
        print("Play listing skipped — UPLOAD_LISTING / UPLOAD_SCREENSHOTS are off")
        return
    try:
        if do_listing:
            for locale in locales(args.metadata_dir / "play"):
                load_locale(args.metadata_dir / "play" / locale)
        screenshots = screenshot_files(args.screenshots_dir) if do_screenshots else []
    except ListingError as exc:
        fail(str(exc))
    if args.dry_run:
        print("Play listing dry-run — local inputs OK; no API calls")
        print(f"  package={args.package_name} listing={do_listing} screenshots={len(screenshots)}")
        return
    service = build_android_publisher(load_service_account_info())
    print("uploading Play listing / screenshots…")
    upload_play_store_listing(
        service,
        package_name=args.package_name,
        metadata_dir=args.metadata_dir,
        screenshots=screenshots,
        upload_listing=do_listing,
        upload_screenshots=do_screenshots,
    )
    print("Play listing step finished")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--listing", action="store_true", help="check the listing text")
    check.add_argument("--screenshots", action="store_true", help="check the phone screenshots")
    prep = sub.add_parser("prepare")
    prep.add_argument("--out", required=True, type=Path)
    prep.add_argument("--tag", help="add this tag's RELEASE_NOTES.md section as Play What's New")
    prep.add_argument("--no-listing", action="store_true",
                      help="skip the listing (only What's New), when the listing is not uploaded")
    upload = sub.add_parser("upload")
    upload.add_argument("--metadata-dir", type=Path, default=ROOT / "store" / "metadata",
                        help="directory holding play/<locale>/ listing files")
    upload.add_argument("--screenshots-dir", type=Path, default=SCREENSHOTS_DIR)
    upload.add_argument("--package-name", default=PACKAGE_NAME)
    upload.add_argument("--dry-run", action="store_true", help="validate local files only — no Play API calls")
    notes = sub.add_parser("whats-new")
    notes.add_argument("--tag", required=True)
    args = parser.parse_args(argv)
    if getattr(args, "package_name", PACKAGE_NAME) != PACKAGE_NAME:
        fail("this publisher only supports the Verceltics Android app")
    try:
        if args.command == "whats-new":
            print(whats_new(args.tag))
        elif args.command == "check":
            # Release runs check only what they upload; with no flag, check everything.
            everything = not (args.listing or args.screenshots)
            if args.listing or everything:
                for locale in locales(LISTING_DIR):
                    load_locale(LISTING_DIR / locale)
                    print(f"Play listing {locale}: OK")
            if args.screenshots or everything:
                print(f"Play phone screenshots: {len(screenshot_files(SCREENSHOTS_DIR))} OK")
        elif args.command == "prepare":
            written = prepare(args.out, args.tag, include_listing=not args.no_listing)
            has_whats_new = any(path.name.startswith("whatsnew-") for path in written)
            print(f"prepared {len(written)} Play listing files under {args.out / 'play'}"
                  + ("" if has_whats_new else " (no What's New for this run)"))
        else:
            cmd_upload(args)
    except (ListingError, ValueError) as exc:
        fail(str(exc))


if __name__ == "__main__":
    main()
