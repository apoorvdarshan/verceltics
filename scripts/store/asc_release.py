#!/usr/bin/env python3
"""App Store Connect listing, What's New, screenshots, creative assets, build selection and review.

Every App Store Connect call goes through the pinned App Store Connect CLI
(`asc`, see asc_cli.py); this script decides what may change and checks the
results.

Run by ios-release.yml with the resolved iOS switches as environment flags:
UPLOAD_LISTING, UPLOAD_WHATS_NEW, UPLOAD_SCREENSHOTS, UPLOAD_CREATIVE_ASSETS,
SUBMIT_IOS_REVIEW and PUBLIC_RELEASE. Every flag that is off skips its App Store
Connect work, and its local files are not checked: listing text only with
UPLOAD_LISTING, What's New only with UPLOAD_WHATS_NEW, screenshots only with
UPLOAD_SCREENSHOTS, header and search results images only with
UPLOAD_CREATIVE_ASSETS.

Submitting for review covers the App Store version and the Ready-to-Submit
subscriptions and in-app purchases from store/catalog/products.json whose
`platforms` list ios (asc_products.py creates missing ones first). Each
product joins the version's draft review submission through its current version
(`subscriptionVersions` / `inAppPurchaseVersions` review items), so a first-time
subscription goes out with the version or not at all. Nothing reaches App Review
until that draft is submitted, which happens only after this run's build and its
selection have been rechecked. Review submission only ever selects the build
uploaded by this run.

What each App Store version state allows:
  editable states (PREPARE_FOR_SUBMISSION, rejected)   every enabled step
  READY_FOR_REVIEW (in a draft review submission, e.g. after a failed submit)
                                         listing/What's New text and submission;
                                         screenshots only when already identical,
                                         creative assets never change
  WAITING_FOR_REVIEW (submitted)         nothing changes: a rerun passes only when
                                         App Store Connect already matches the
                                         repository and holds this run's build
  anything else                          the run fails

`--create-version` (IOS_CREATE_VERSION, CREATE_IOS_VERSION here) runs first on a
release run: it adds the App Store version for the tag when App Store Connect does
not have it yet, and leaves an existing one alone. It refuses when another
version is still on its way to the store or the tag's version is not newer than
every existing one.

`--dry-run` checks the switches and local files only (no asc calls).
`--preflight` runs the same version, localization and state checks with asc in
read-only mode, before anything is built or uploaded (ios-release.yml prepare).
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asc_cli  # noqa: E402
import ios_metadata  # noqa: E402
from ios_metadata import APP_INFO_FIELDS, VERSION_FIELDS, WHATS_NEW  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BUNDLE_ID = "com.apoorvdarshan.verceltics"
# Verceltics' App Store Connect app (apps.apple.com/app/id6761645656). Every run
# checks that this record carries exactly DEFAULT_BUNDLE_ID before touching
# anything, so a version is only ever created or changed for this app.
ASC_APP_ID = "6761645656"
PLATFORM = "IOS"
# asc's --device-type for each screenshot set (ios_metadata.SCREENSHOT_SETS).
SCREENSHOT_DEVICE_TYPES = {
    "APP_IPHONE_67": "IPHONE_67",
    "APP_IPAD_PRO_3GEN_129": "IPAD_PRO_3GEN_129",
}
# Product page creative assets: the folder under --creative-dir, the placement
# type, and a label for logs. Each folder holds at most one image.
CREATIVE_SLOTS = (
    ("header", "PRODUCT_PAGE_HEADER_ASSET", "product page header"),
    ("search-results", "APP_STORE_SEARCH_RESULTS_ASSET", "search results"),
)
# Apple's image specs per slot (asc asset-library specs): the header is a 21:9
# 3840x1646 PNG; search results are 3:2 PNG or JPEG from 1920x1280 to 3840x2560.
# A 5244x2950 16:9 PNG is a universal image that fits either.
UNIVERSAL_CREATIVE_SIZE = (5244, 2950)
CREATIVE_EXTENSIONS = {".png", ".jpg", ".jpeg"}
# Library image states: still being reviewed, not sent yet, or finished without approval.
CREATIVE_PENDING_STATES = {"WAITING_FOR_REVIEW", "IN_REVIEW", "ACCEPTED"}
CREATIVE_UNSUBMITTED_STATES = {"PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW", "COMPLETE", "UPLOAD_COMPLETE"}
# AWAITING_UPLOAD is a reservation an interrupted upload left behind (asc never
# deletes it); it is ignored like a failed one, so the next run uploads the image again.
CREATIVE_DEAD_STATES = {"REJECTED", "FAILED", "ARCHIVED", "AWAITING_UPLOAD"}
EDITABLE_VERSION_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
}
# READY_FOR_REVIEW means the version already sits in a draft review submission
# (for example after a failed submit); it can still be submitted, and its text
# (listing, What's New) stays editable. Screenshots and creative assets cannot
# change there.
READY_FOR_REVIEW = "READY_FOR_REVIEW"
SUBMITTABLE_VERSION_STATES = EDITABLE_VERSION_STATES | {READY_FOR_REVIEW}
# Submitted: nothing may change. A rerun only confirms App Store Connect already matches.
WAITING_FOR_REVIEW = "WAITING_FOR_REVIEW"
LOCKED_MEDIA_STATES = {READY_FOR_REVIEW, WAITING_FOR_REVIEW}
# App Info (name, subtitle, privacy URL) becomes editable with a new version.
EDITABLE_APP_INFO_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "READY_FOR_REVIEW",
}
# App Info that is on the store now, or was superseded by a newer one.
LIVE_APP_INFO_STATES = {"READY_FOR_DISTRIBUTION", "READY_FOR_SALE"}
SUPERSEDED_APP_INFO_STATES = {"REPLACED_WITH_NEW_INFO"}
# Versions in these states are finished. Any other state means the version is
# still on its way to the store, and App Store Connect allows only one such
# version per platform, so no new version is created next to it.
FINISHED_VERSION_STATES = {
    "READY_FOR_SALE",
    "REPLACED_WITH_NEW_VERSION",
    "REMOVED_FROM_SALE",
    "DEVELOPER_REMOVED_FROM_SALE",
}
# asc `localizations update` flag for each version and App Info attribute.
LOCALIZATION_FLAGS = {
    "description": "--description",
    "keywords": "--keywords",
    "promotionalText": "--promotional-text",
    "marketingUrl": "--marketing-url",
    "supportUrl": "--support-url",
    "whatsNew": "--whats-new",
    "name": "--name",
    "subtitle": "--subtitle",
    "privacyPolicyUrl": "--privacy-policy-url",
}


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


def data(payload: Any) -> Any:
    return (payload or {}).get("data") if isinstance(payload, dict) else None


def rows(payload: Any) -> list[dict[str, Any]]:
    value = data(payload)
    return value if isinstance(value, list) else []


def attrs(row: dict[str, Any] | None) -> dict[str, Any]:
    return (row or {}).get("attributes") or {}


def related_id(row: dict[str, Any], name: str) -> str | None:
    return (((row.get("relationships") or {}).get(name) or {}).get("data") or {}).get("id")


def asc_value(text: str) -> str:
    """A flag value for asc, which reads a leading @ as @file:/@env: (escaped as @@)."""
    return "@" + text if text.startswith("@") else text


def load_credentials() -> tuple[str, str, str]:
    key_id = os.environ.get("APP_STORE_CONNECT_API_KEY_ID", "").strip()
    issuer_id = os.environ.get("APP_STORE_CONNECT_ISSUER_ID", "").strip()
    key_p8 = os.environ.get("APP_STORE_CONNECT_API_KEY_P8", "").strip()
    if not key_id or not issuer_id or not key_p8:
        fail(
            "missing ASC credentials — set APP_STORE_CONNECT_API_KEY_ID, "
            "APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_API_KEY_P8"
        )
    return key_id, issuer_id, key_p8


def asc_from_environment(*, read_only: bool = False) -> asc_cli.Asc:
    """asc with the release credentials; read_only makes asc refuse every write."""
    load_credentials()
    return asc_cli.Asc(read_only=read_only)


def call(asc: asc_cli.Asc, *args: str) -> Any:
    """Run asc and stop the release with a readable error when it fails."""
    try:
        return asc.run(*args)
    except asc_cli.AscError as exc:
        fail(str(exc))


def find_app(asc: asc_cli.Asc, bundle_id: str, app_id: str = ASC_APP_ID) -> str:
    """The pinned App Store Connect app, after checking it carries exactly bundle_id."""
    record = data(call(asc, "apps", "view", "--id", app_id)) or {}
    if record.get("id") != app_id:
        fail(f"App Store Connect returned app {record.get('id')!r}, expected {app_id}")
    found = attrs(record).get("bundleId")
    if found != bundle_id:
        fail(f"App Store Connect app {app_id} has bundle id {found!r}, expected {bundle_id}")
    return app_id


def list_ios_app_store_versions(asc: asc_cli.Asc, app_id: str) -> list[dict[str, Any]]:
    return rows(call(asc, "versions", "list", "--app", app_id, "--platform", PLATFORM, "--paginate"))


def get_ios_app_store_version(asc: asc_cli.Asc, app_id: str, version_string: str) -> dict[str, Any]:
    matches = [
        row for row in list_ios_app_store_versions(asc, app_id) if attrs(row).get("versionString") == version_string
    ]
    if not matches:
        fail(
            f"no IOS App Store version {version_string!r} for app {app_id} "
            "(turn on IOS_CREATE_VERSION, or create the version in App Store Connect first)"
        )
    if len(matches) > 1:
        fail(f"ambiguous App Store versions for {version_string!r} on IOS — got {len(matches)} records")
    return matches[0]


def version_localization(asc: asc_cli.Asc, version_id: str, locale: str) -> dict[str, Any]:
    """The version's localization for locale; fails when App Store Connect has none."""
    found = [
        row
        for row in rows(call(asc, "localizations", "list", "--version", version_id, "--locale", locale))
        if attrs(row).get("locale") == locale
    ]
    if not found:
        fail(f"App Store version {version_id} has no {locale} localization; add it in App Store Connect first")
    return found[0]


def app_info_localization(asc: asc_cli.Asc, app_id: str, app_info_id: str, locale: str) -> dict[str, Any]:
    """The App Info's localization for locale; fails when App Store Connect has none."""
    found = [
        row
        for row in rows(
            call(asc, "localizations", "list", "--app", app_id, "--type", "app-info", "--app-info", app_info_id,
                 "--locale", locale)
        )
        if attrs(row).get("locale") == locale
    ]
    if not found:
        fail(f"App Info {app_info_id} has no {locale} localization; add it in App Store Connect first")
    return found[0]


def update_flags(changed: dict[str, str]) -> list[str]:
    """`asc localizations update` flags that set exactly the changed attributes."""
    flags: list[str] = []
    for attribute, value in changed.items():
        flags += [LOCALIZATION_FLAGS[attribute], asc_value(value)]
    return flags


def metadata_locales(metadata_dir: Path) -> list[str]:
    try:
        return ios_metadata.locales(metadata_dir / "ios")
    except ios_metadata.MetadataError as exc:
        fail(str(exc))


def changed_attributes(current: dict[str, Any], wanted: dict[str, str]) -> dict[str, str]:
    return {key: value for key, value in wanted.items() if (current.get(key) or "") != value}


def listing_changes(
    asc: asc_cli.Asc,
    *,
    version_id: str,
    metadata_dir: Path,
    include_listing: bool = True,
    include_whats_new: bool = False,
) -> list[tuple[str, dict[str, Any], dict[str, str]]]:
    """(locale, version localization, attributes that differ) for every locale with
    text to send. Read-only; fails when a locale has no version localization."""
    fields = (VERSION_FIELDS if include_listing else ()) + ((WHATS_NEW,) if include_whats_new else ())
    plan = []
    for locale in metadata_locales(metadata_dir):
        loc_dir = metadata_dir / "ios" / locale
        wanted = {
            field.attribute: value
            for field in fields
            if (value := read_text(loc_dir / field.filename))
        }
        if not wanted:
            continue
        version_loc = version_localization(asc, version_id, locale)
        plan.append((locale, version_loc, changed_attributes(attrs(version_loc), wanted)))
    return plan


def upload_listing(
    asc: asc_cli.Asc,
    *,
    version_id: str,
    metadata_dir: Path,
    include_listing: bool = True,
    include_whats_new: bool = False,
    frozen_state: str | None = None,
    write: bool = True,
) -> None:
    """Update each locale's version localization. Listing text and What's New are separate gates.

    Only the fields that differ are sent. With frozen_state (a submitted version),
    nothing is written: the run passes only when App Store Connect already holds
    the repository's text. write=False (the preflight) reads and reports only.
    """
    for locale, version_loc, changed in listing_changes(
        asc,
        version_id=version_id,
        metadata_dir=metadata_dir,
        include_listing=include_listing,
        include_whats_new=include_whats_new,
    ):
        if not changed:
            print(f"  version localization ({locale}) already up to date")
            continue
        if frozen_state:
            fail(
                f"version localization ({locale}) {', '.join(changed)} differ from the repository, "
                f"but the version is {frozen_state} and cannot change"
            )
        if not write:
            print(f"  version localization ({locale}) will be updated: {', '.join(changed)}")
            continue
        call(asc, "localizations", "update", "--id", version_loc["id"], *update_flags(changed))
        print(f"  updated version localization ({locale}): {', '.join(changed)}")


def app_info_state(info: dict[str, Any]) -> str:
    attributes = info.get("attributes") or {}
    return attributes.get("state") or attributes.get("appStoreState") or ""


def app_info_target(infos: list[dict[str, Any]]) -> tuple[dict[str, Any], bool]:
    """The App Info that name/subtitle/privacy URL compare with, and whether it is editable.

    An editable App Info (a new version is being prepared) takes the change. Without
    one the repository must already match: the App Info going through review if there
    is one (it replaces the live one, and an earlier attempt may have edited it),
    otherwise the live App Info.
    """
    editable = [info for info in infos if app_info_state(info) in EDITABLE_APP_INFO_STATES]
    if len(editable) > 1:
        fail(f"ambiguous: {len(editable)} editable App Info records; resolve this in App Store Connect")
    if editable:
        return editable[0], True
    settled = EDITABLE_APP_INFO_STATES | LIVE_APP_INFO_STATES | SUPERSEDED_APP_INFO_STATES
    in_review = [info for info in infos if app_info_state(info) not in settled]
    if len(in_review) > 1:
        fail(f"ambiguous: {len(in_review)} App Info records in review; resolve this in App Store Connect")
    if in_review:
        return in_review[0], False
    live = [info for info in infos if app_info_state(info) in LIVE_APP_INFO_STATES]
    if not live:
        fail("the app has no App Info in App Store Connect")
    return live[0], False


def upload_app_info(
    asc: asc_cli.Asc,
    *,
    app_id: str,
    metadata_dir: Path,
    frozen_state: str | None = None,
    write: bool = True,
) -> None:
    """Name, subtitle and privacy URL. App Store Connect only allows edits while a new
    version is prepared, so unchanged values need no editable App Info. With
    frozen_state nothing is written; write=False (the preflight) reads only."""
    wanted_by_locale = {}
    for locale in metadata_locales(metadata_dir):
        loc_dir = metadata_dir / "ios" / locale
        wanted = {
            field.attribute: value
            for field in APP_INFO_FIELDS
            if (value := read_text(loc_dir / field.filename))
        }
        if wanted:
            wanted_by_locale[locale] = wanted
    if not wanted_by_locale:
        return
    info, editable = app_info_target(rows(call(asc, "apps", "info", "list", "--app", app_id)))
    for locale, wanted in wanted_by_locale.items():
        loc = app_info_localization(asc, app_id, info["id"], locale)
        changed = changed_attributes(attrs(loc), wanted)
        if not changed:
            print(f"  app info ({locale}) already up to date")
            continue
        if frozen_state:
            fail(
                f"App Info ({locale}) {', '.join(changed)} differ from the repository, but the "
                f"version is {frozen_state} and cannot change"
            )
        if not editable:
            fail(
                f"App Info ({locale}) {', '.join(changed)} differ from App Store Connect, but no "
                "App Info is editable; create the new App Store version first"
            )
        if not write:
            print(f"  app info ({locale}) will be updated: {', '.join(changed)}")
            continue
        call(asc, "localizations", "update", "--type", "app-info", "--id", loc["id"], *update_flags(changed))
        print(f"  updated app info ({locale}): {', '.join(changed)}")


def shown_path(path: Path) -> str:
    """A path for messages: relative to the repository when it is inside it."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def file_checksum(raw: bytes) -> str:
    """The MD5 App Store Connect records as a screenshot's sourceFileChecksum (an integrity check)."""
    return hashlib.md5(raw, usedforsecurity=False).hexdigest()


def screenshot_pngs(spec: ios_metadata.ScreenshotSet) -> list[Path]:
    try:
        return ios_metadata.screenshot_files(spec)
    except ios_metadata.MetadataError as exc:
        fail(str(exc))


def checksums(pngs: list[Path]) -> list[str]:
    return [file_checksum(path.read_bytes()) for path in pngs]


def live_screenshot_checksums(
    asc: asc_cli.Asc, *, app_id: str, version_id: str, locale: str
) -> dict[str, list[str | None]]:
    """Display type -> checksums of the set's screenshots in display order. Read-only.

    asc fails when the version has no localization for locale.
    """
    payload = call(
        asc, "screenshots", "list", "--app", app_id, "--version-id", version_id, "--locale", locale,
        "--platform", PLATFORM,
    )
    live: dict[str, list[str | None]] = {}
    for entry in (payload or {}).get("sets") or []:
        display_type = attrs(entry.get("set")).get("screenshotDisplayType")
        live[display_type] = [attrs(shot).get("sourceFileChecksum") for shot in entry.get("screenshots") or []]
    return live


def try_import_pillow():
    """PIL.Image, or None when Pillow (requirements-store.txt) is not installed."""
    try:
        from PIL import Image  # type: ignore[import-untyped]
    except ImportError:
        return None
    return Image


def rgb_copy(path: Path) -> bytes:
    """The PNG without its alpha channel: transparent pixels laid over black, the
    colour profile kept. Read-only: the file itself never changes.

    asc refuses screenshots that store alpha, even fully opaque ones
    (asc_cli.image_has_alpha), so these copies are what gets uploaded.
    """
    Image = try_import_pillow()
    if Image is None:
        fail(
            f"{shown_path(path)} has an alpha channel, which asc does not upload; install Pillow "
            "(requirements-store.txt) so the run can upload an RGB copy of it"
        )
    with Image.open(path) as img:
        icc_profile = img.info.get("icc_profile")
        rgba = img.convert("RGBA")
    rgb = Image.new("RGB", rgba.size, (0, 0, 0))
    rgb.paste(rgba, mask=rgba.getchannel("A"))
    out = io.BytesIO()
    rgb.save(out, format="PNG", icc_profile=icc_profile)
    return out.getvalue()


def upload_copies(pngs: list[Path]) -> list[bytes]:
    """What is uploaded for each committed screenshot: the file itself, or an RGB
    copy (rgb_copy) when it stores alpha."""
    return [rgb_copy(path) if asc_cli.image_has_alpha(path) else path.read_bytes() for path in pngs]


def copies_to_upload(live: list[str | None] | None, pngs: list[Path]) -> list[bytes] | None:
    """None when App Store Connect already shows exactly these screenshots, in
    order; otherwise the upload copies that would replace the set. Read-only.

    The set matches as the committed files (as uploaded before the RGB copies
    existed) or as their upload copies, compared by checksum.
    """
    if live == checksums(pngs):
        return None
    copies = upload_copies(pngs)
    return None if live == [file_checksum(raw) for raw in copies] else copies


def require_screenshots_unchanged(asc: asc_cli.Asc, *, app_id: str, version_id: str, locale: str, state: str) -> None:
    """Screenshots cannot change in this version state: pass only when App Store
    Connect already shows the repository's screenshots, in order. Read-only."""
    live = live_screenshot_checksums(asc, app_id=app_id, version_id=version_id, locale=locale)
    for spec in ios_metadata.SCREENSHOT_SETS:
        if copies_to_upload(live.get(spec.display_type), screenshot_pngs(spec)) is not None:
            fail(
                f"{spec.label} screenshots in App Store Connect differ from "
                f"{shown_path(spec.directory)} (or cannot be compared), but screenshots cannot "
                f"change while the version is {state}; remove the version from review in App Store "
                "Connect (or update the screenshots there), then rerun"
            )
        print(f"  {spec.label} screenshots already match App Store Connect")


# A set to replace: its spec, the committed PNGs and the bytes uploaded for each.
PendingScreenshots = list[tuple[ios_metadata.ScreenshotSet, list[Path], list[bytes]]]


def screenshot_uploads(asc: asc_cli.Asc, *, app_id: str, version_id: str, locale: str) -> PendingScreenshots:
    """The sets App Store Connect does not already hold exactly (same files, same order). Read-only.

    asc refuses to upload a PNG that stores an alpha channel or transparency,
    even a fully opaque one (asc_cli.image_has_alpha), so such a PNG is uploaded
    as an RGB copy (rgb_copy). The copies are made here, before anything is
    written; without Pillow a set with alpha that has to be uploaded fails here.
    A set that already matches is never uploaded again.
    """
    live = live_screenshot_checksums(asc, app_id=app_id, version_id=version_id, locale=locale)
    pending: PendingScreenshots = []
    for spec in ios_metadata.SCREENSHOT_SETS:
        pngs = screenshot_pngs(spec)
        copies = copies_to_upload(live.get(spec.display_type), pngs)
        if copies is None:
            print(f"  {spec.label} screenshots already match App Store Connect")
            continue
        flattened = [path.name for path in pngs if asc_cli.image_has_alpha(path)]
        note = f" (RGB copies of {', '.join(flattened)}, which have an alpha channel)" if flattened else ""
        print(f"  {spec.label} screenshots will be replaced with {shown_path(spec.directory)}{note}")
        pending.append((spec, pngs, copies))
    return pending


def upload_screenshots(
    asc: asc_cli.Asc,
    *,
    app_id: str,
    version_id: str,
    locale: str,
    pending: PendingScreenshots | None = None,
) -> None:
    """Replace each set App Store Connect does not hold yet with the repository's
    screenshots (RGB copies of those with alpha), in file-name order."""
    if pending is None:
        pending = screenshot_uploads(asc, app_id=app_id, version_id=version_id, locale=locale)
    for spec, pngs, copies in pending:
        print(f"  {spec.label} screenshots from {shown_path(spec.directory)}")
        # Exactly the checked screenshots, under their own names: asc uploads every
        # file in --path, sorted by name. The repository's files are never changed.
        with tempfile.TemporaryDirectory() as staging:
            staged = []
            for path, raw in zip(pngs, copies, strict=True):
                target = Path(staging) / path.name
                target.write_bytes(raw)
                staged.append(target)
            # The last guard: never hand asc a file it refuses.
            with_alpha = [target.name for target in staged if asc_cli.image_has_alpha(target)]
            if with_alpha:
                fail(f"the {spec.label} upload copies of {', '.join(with_alpha)} still have an alpha channel")
            # --replace deletes the set's screenshots before uploading. If an upload
            # fails part-way, fix the error and rerun: the set is replaced again.
            call(
                asc, "screenshots", "upload", "--app", app_id, "--version-id", version_id, "--locale", locale,
                "--path", staging, "--device-type", SCREENSHOT_DEVICE_TYPES[spec.display_type],
                "--replace", "--confirm",
            )
        after = live_screenshot_checksums(asc, app_id=app_id, version_id=version_id, locale=locale)
        if after.get(spec.display_type) != [file_checksum(raw) for raw in copies]:
            fail(f"after uploading, App Store Connect's {spec.label} screenshots differ from {shown_path(spec.directory)}")
        print(f"  uploaded {len(pngs)} {spec.label} screenshot(s)")


def image_dimensions(path: Path) -> tuple[str | None, int, int]:
    """(format, width, height) of a PNG or JPEG, read from its headers."""
    raw = Path(path).read_bytes()
    if raw.startswith(asc_cli.PNG_SIGNATURE) and len(raw) >= 24:
        return "png", int.from_bytes(raw[16:20], "big"), int.from_bytes(raw[20:24], "big")
    if raw[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(raw):
            if raw[i] != 0xFF:
                i += 1
                continue
            marker = raw[i + 1]
            if marker == 0xFF or marker in (0x01, 0xD8) or 0xD0 <= marker <= 0xD7:
                i += 1 if marker == 0xFF else 2
                continue
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                return "jpeg", int.from_bytes(raw[i + 7:i + 9], "big"), int.from_bytes(raw[i + 5:i + 7], "big")
            i += 2 + int.from_bytes(raw[i + 2:i + 4], "big")
    return None, 0, 0


def creative_size_problem(placement_type: str, path: Path) -> str | None:
    """Why Apple would refuse this image for the slot, or None when it fits."""
    kind, width, height = image_dimensions(path)
    if kind is None:
        return "is not a PNG or JPEG"
    if kind == "png" and (width, height) == UNIVERSAL_CREATIVE_SIZE:
        return None
    if placement_type == "PRODUCT_PAGE_HEADER_ASSET":
        if kind != "png" or (width, height) != (3840, 1646):
            return f"is a {width}x{height} {kind.upper()}; the header must be a 3840x1646 PNG"
        return None
    if not (1920 <= width <= 3840 and width * 2 == height * 3):
        return f"is {width}x{height}; search results images are 3:2, from 1920x1280 to 3840x2560"
    return None


def creative_sources(creative_dir: Path) -> list[tuple[str, str, Path]]:
    """(placement type, label, image) for every creative slot that has an image."""
    found = []
    for folder, placement_type, label in CREATIVE_SLOTS:
        slot_dir = creative_dir / folder
        images = sorted(
            p for p in slot_dir.glob("*") if p.is_file() and p.suffix.lower() in CREATIVE_EXTENSIONS
        ) if slot_dir.is_dir() else []
        if len(images) > 1:
            fail(f"the {label} slot takes one image; {slot_dir} has {len(images)}")
        if images and asc_cli.image_has_alpha(images[0]):
            fail(f"the {label} image {images[0].name} has an alpha channel, which App Store Connect refuses; re-export it as RGB")
        problem = creative_size_problem(placement_type, images[0]) if images else None
        if problem:
            fail(f"the {label} image {images[0].name} {problem}")
        if images:
            found.append((placement_type, label, images[0]))
    if not found:
        fail(f"creative asset upload is on, but {creative_dir} has no header or search results image")
    return found


def library_images(asc: asc_cli.Asc, library_id: str) -> list[dict[str, Any]]:
    return rows(
        call(asc, "asset-library", "images", "list", "--library-id", library_id, "--category", "CREATIVE_ASSETS", "--paginate")
    )


def library_image_for(images: list[dict[str, Any]], path: Path) -> dict[str, Any] | None:
    """The newest usable library image uploaded from this exact file (name and size)."""
    size = path.stat().st_size
    matches = [
        image
        for image in images
        if attrs(image).get("fileName") == path.name
        and attrs(image).get("fileSize") == size
        and attrs(image).get("state") not in CREATIVE_DEAD_STATES
    ]
    rejected = [
        image
        for image in images
        if attrs(image).get("fileName") == path.name
        and attrs(image).get("fileSize") == size
        and attrs(image).get("state") == "REJECTED"
    ]
    if not matches and rejected:
        fail(
            f"App Review rejected {path.name}; replace the image (a new file name) and rerun. "
            f"Details: {json.dumps(attrs(rejected[0]).get('stateDetails'))}"
        )
    return max(matches, key=lambda image: attrs(image).get("createdDate") or "") if matches else None


def sync_creative_assets(
    asc: asc_cli.Asc,
    *,
    app_id: str,
    version_id: str,
    locale: str,
    creative_dir: Path,
    editable: bool,
    reserved_draft: str | None = None,
) -> dict[str, str]:
    """Put the repository's header and search results images on the version.

    An image is uploaded to the Asset Library once (matched by file name and size)
    and reviewed on its own. Only approved images are placed, because a version
    that is already approved only takes approved assets; one that is still in
    review or not yet submitted is placed on a later run. Nothing changes while
    the version is locked (editable=False): an image that would have to be
    uploaded or sent to App Review stops the run, and an approved image that is
    not placed yet waits for a run where the version can be edited. Returns
    placement type -> outcome.
    """
    sources = creative_sources(creative_dir)
    library_id = (data(call(asc, "asset-library", "view", "--app", app_id)) or {}).get("id")
    if not library_id:
        fail(f"App Store Connect returned no Asset Library for app {app_id}")
    images = library_images(asc, library_id)
    version_loc = version_localization(asc, version_id, locale)
    outcomes: dict[str, str] = {}
    to_review: list[tuple[str, dict[str, Any]]] = []
    for placement_type, label, path in sources:
        image = library_image_for(images, path)
        if image is None:
            if not editable:
                fail(f"cannot upload the {label} image while the version waits for review")
            # asc waits for processing and prints a receipt (imageId, ready, state).
            receipt = call(asc, "asset-library", "images", "upload", "--library-id", library_id, "--file", str(path))
            if not receipt.get("imageId") or not receipt.get("ready"):
                fail(f"Apple did not finish processing the {label} image {path.name}: {json.dumps(receipt)}")
            image = {"id": receipt["imageId"], "attributes": {"fileName": path.name, "state": receipt.get("state")}}
            print(f"  uploaded {path.name} to the Asset Library ({image['id']})")
        state = attrs(image).get("state") or ""
        if state in CREATIVE_UNSUBMITTED_STATES or not state:
            if not editable:
                fail(
                    f"cannot send the {label} image {path.name} to App Review while the version waits for "
                    "review; rerun once the version can be edited, or submit it in App Store Connect"
                )
            to_review.append((label, image))
            outcomes[placement_type] = "submitted for review"
            continue
        if state in CREATIVE_PENDING_STATES:
            outcomes[placement_type] = f"in App Review ({state}); placed on a later run"
            print(f"  {label}: {path.name} is {state}; it is placed once App Review approves it")
            continue
        if state != "APPROVED":
            fail(f"the {label} image {path.name} is {state}; resolve it in App Store Connect")
        current = rows(
            call(
                asc, "localizations", "placements", "list", "--localization-id", version_loc["id"],
                "--placement-type", placement_type, "--include", "image",
            )
        )
        if any(related_id(row, "image") == image["id"] for row in current):
            outcomes[placement_type] = "already placed"
            print(f"  {label}: {path.name} is already on the product page")
            continue
        if not editable:
            # Approval can land between a submit and a rerun; that is not a
            # mismatch, so the rerun passes and placement waits for an editable version.
            outcomes[placement_type] = "approved; placed once the version can be edited"
            print(f"  {label}: {path.name} is approved; it is placed on a later run, once the version can be edited")
            continue
        if current:
            call(
                asc, "localizations", "placements", "swap", "--localization-id", version_loc["id"],
                "--image-id", image["id"], "--placement-type", placement_type, "--placement-id", current[0]["id"],
                "--confirm",
            )
        else:
            call(
                asc, "localizations", "placements", "create", "--localization-id", version_loc["id"],
                "--image-id", image["id"], "--placement-type", placement_type, "--placement-group", "DEFAULT_PROFILE",
            )
        outcomes[placement_type] = "placed"
        print(f"  {label}: placed {path.name} on the product page")
    if to_review:
        submit_creative_review(asc, app_id, to_review, reserved_draft=reserved_draft)
    return outcomes


def submit_creative_review(
    asc: asc_cli.Asc, app_id: str, images: list[tuple[str, dict[str, Any]]], *, reserved_draft: str | None = None
) -> str:
    """Send not-yet-reviewed creative images to App Review in a submission of their own.

    A draft an earlier attempt left behind holding only some of these images is
    finished first, else an empty one, instead of starting another, so reruns
    leave no extra drafts. The draft this run chose for the version and its
    products (reserved_draft) is never taken.
    """
    wanted = {image["id"] for _, image in images}
    partial = empty = None
    for draft in draft_submission_ids(asc, app_id):
        if draft == reserved_draft:
            continue
        # A version or product item adds None, which is never wanted.
        held = {related_id(item, "appAssetLibraryImage") for item in submission_items(asc, draft)}
        if not held:
            empty = empty or draft
        elif held <= wanted:
            partial = draft
            break
    submission_id = partial or empty
    if submission_id:
        print(f"  finishing creative review submission {submission_id} from an earlier attempt")
    else:
        submission = data(call(asc, "review", "submissions-create", "--app", app_id, "--platform", PLATFORM)) or {}
        submission_id = submission.get("id")
    if not submission_id:
        fail("asc did not report the new review submission's id")
    for label, image in images:
        call(
            asc, "review", "items-add", "--submission", submission_id, "--item-type", "appAssetLibraryImages",
            "--item-id", image["id"], "--if-exists", "skip",
        )
        print(f"  {label}: {attrs(image).get('fileName')} added to creative review submission {submission_id}")
    items = submission_items(asc, submission_id)
    unexpected = [item["id"] for item in items if related_id(item, "appAssetLibraryImage") not in wanted]
    if unexpected or len(items) != len(images):
        fail(f"review submission {submission_id} holds unexpected items {unexpected}; not submitting it")
    call(asc, "review", "submissions-submit", "--id", submission_id, "--confirm")
    print(f"  submitted {len(images)} creative image(s) for App Review ({submission_id})")
    return submission_id


def load_catalog() -> dict[str, Any]:
    return json.loads((ROOT / "store" / "catalog" / "products.json").read_text(encoding="utf-8"))


# The catalog's product groups (asc_products.PRODUCT_GROUPS maps each to its App Store type).
CATALOG_PRODUCT_GROUPS = ("subscriptions", "consumables", "non_consumables", "tips")


def intended_product_ids(catalog: dict[str, Any] | None = None) -> set[str]:
    """Product IDs the release is allowed to submit: the canonical catalog's iOS products."""
    catalog = catalog if catalog is not None else load_catalog()
    ids: set[str] = set()
    for group in CATALOG_PRODUCT_GROUPS:
        for item in catalog.get(group) or []:
            pid = item.get("id")
            if isinstance(pid, str) and pid and "ios" in (item.get("platforms") or []):
                ids.add(pid)
    return ids


class ProductSubmitError(Exception):
    """A required product could not be added to App Review."""


class ProductSubmitResult:
    """Outcome of attaching products to a review submission."""

    def __init__(
        self,
        submission_id: str,
        attached: int,
        added_items: bool,
        has_products: bool = False,
        allowed_versions: set[str] | None = None,
    ) -> None:
        self.submission_id = submission_id
        # Intended products the draft now holds (attached now or by an earlier run).
        self.attached = attached
        self.added_items = added_items
        # True when the draft holds intended products, so a catch-up retry can
        # still submit the draft even when nothing new was attached.
        self.has_products = has_products
        # Every version of every catalog product: the only product items a
        # draft may hold when it is submitted.
        self.allowed_versions = allowed_versions or set()


# States that mean a subscription can still be (re)attached to a review.
SUBMITTABLE_PRODUCT_STATES = {
    "READY_TO_SUBMIT",
    "DEVELOPER_REJECTED",
    "READY_FOR_REVIEW",
}
ITEM_RELATIONSHIPS = ("appStoreVersion", "subscriptionVersion", "inAppPurchaseVersion", "appAssetLibraryImage")


def draft_submission_ids(asc: asc_cli.Asc, app_id: str) -> list[str]:
    payload = call(
        asc, "review", "submissions-list", "--app", app_id, "--platform", PLATFORM, "--state", "READY_FOR_REVIEW",
        "--paginate",
    )
    return [row["id"] for row in rows(payload)]


def submission_items(asc: asc_cli.Asc, submission_id: str) -> list[dict[str, Any]]:
    """The submission's items with their related ids.

    App Store Connect omits the relationships of Review Submission Items unless
    they are requested, so every reader asks for them explicitly.
    """
    return rows(
        call(
            asc, "review", "items-list", "--submission", submission_id, "--include", ",".join(ITEM_RELATIONSHIPS),
            "--paginate",
        )
    )


def find_draft_submission(asc: asc_cli.Asc, app_id: str, version_id: str | None = None) -> str | None:
    """The app's READY_FOR_REVIEW draft to use for the version and products, if there is one.

    An earlier attempt can leave a draft that was never submitted; it is reused.
    The draft already holding version_id wins. Otherwise only a draft holding no
    app version is reused, so another version's draft is never touched. A draft
    holding creative images belongs to their own review and is skipped.
    """
    drafts = []
    for submission_id in draft_submission_ids(asc, app_id):
        items = submission_items(asc, submission_id)
        if not any(related_id(item, "appAssetLibraryImage") for item in items):
            drafts.append((submission_id, {related_id(item, "appStoreVersion") for item in items} - {None, ""}))
    if version_id:
        for submission_id, versions in drafts:
            if version_id in versions:
                return submission_id
    for submission_id, versions in drafts:
        if not versions:
            return submission_id
    return None


def create_draft_submission(asc: asc_cli.Asc, app_id: str) -> str:
    created = data(call(asc, "review", "submissions-create", "--app", app_id, "--platform", PLATFORM)) or {}
    if not created.get("id"):
        fail("asc did not report the new review submission's id")
    return created["id"]


def get_or_create_draft_submission(asc: asc_cli.Asc, app_id: str, version_id: str | None = None) -> str:
    return find_draft_submission(asc, app_id, version_id) or create_draft_submission(asc, app_id)


def draft_item_version_ids(asc: asc_cli.Asc, submission_id: str) -> set[str]:
    """The app, subscription and in-app purchase version ids already in a draft."""
    ids: set[str] = set()
    for item in submission_items(asc, submission_id):
        for name in ITEM_RELATIONSHIPS[:3]:
            found = related_id(item, name)
            if found:
                ids.add(found)
    return ids


def draft_holds_app_version(asc: asc_cli.Asc, submission_id: str) -> bool:
    """Return True if any item in the draft references an App Store version."""
    return any(related_id(item, "appStoreVersion") for item in submission_items(asc, submission_id))


def current_version_id(versions: list[dict[str, Any]]) -> str | None:
    """The highest-numbered version (App Store Connect allows one in flight per product)."""
    numbered = [v for v in versions if attrs(v).get("version") is not None]
    if not numbered:
        return versions[0]["id"] if versions else None
    return max(numbered, key=lambda v: int(attrs(v)["version"]))["id"]


def attach_products(
    asc: asc_cli.Asc,
    app_id: str,
    *,
    submission_id: str | None,
    intended_ids: set[str] | None = None,
) -> ProductSubmitResult:
    """Add the catalog's Ready-to-Submit subscriptions and in-app purchases to a draft.

    Each product attaches through its current (highest-numbered) version: a
    subscription version through the `subscriptionVersions` review item, which
    also clears a DEVELOPER_REJECTED state left by an earlier submission (for
    example when a plan was removed from a review), and the lifetime purchase and
    tips through `inAppPurchaseVersions`. Only products in
    store/catalog/products.json are considered, so an unrelated Ready-to-Submit
    plan or tip is never dragged into a release. Attaching submits nothing: the
    items go to App Review only when the caller submits the draft. With
    submission_id None, an existing draft is reused, and a new one is created
    only when a product has to be added, so a rerun with nothing to send leaves
    no empty draft behind.
    """
    if intended_ids is None:
        intended_ids = intended_product_ids()
    if not intended_ids:
        print("  catalog has no products to submit")
        return ProductSubmitResult(submission_id or "", 0, False)
    if submission_id is None:
        submission_id = find_draft_submission(asc, app_id)
    # Idempotency: a retry reuses the same draft, so skip anything already
    # attached instead of adding a duplicate that App Store Connect rejects.
    already = draft_item_version_ids(asc, submission_id) if submission_id else set()
    attached = 0
    added = False
    failures: list[str] = []

    candidates: list[tuple[str, str, str, str, list[dict[str, Any]]]] = []
    allowed: set[str] = set()
    for sub in rows(call(asc, "subscriptions", "list", "--app", app_id, "--paginate")):
        a = attrs(sub)
        if a.get("productId") not in intended_ids:
            continue
        versions = rows(call(asc, "subscriptions", "versions", "list", "--subscription-id", sub["id"], "--paginate"))
        allowed |= {v["id"] for v in versions}
        if a.get("state") in SUBMITTABLE_PRODUCT_STATES:
            candidates.append(("subscription", "subscriptionVersions", a.get("name") or sub["id"], a["productId"], versions))
    for iap in rows(call(asc, "iap", "list", "--app", app_id, "--paginate")):
        a = attrs(iap)
        if a.get("productId") not in intended_ids:
            continue
        versions = rows(call(asc, "iap", "versions", "list", "--iap-id", iap["id"], "--paginate"))
        allowed |= {v["id"] for v in versions}
        if a.get("state") == "READY_TO_SUBMIT":
            candidates.append(("in-app purchase", "inAppPurchaseVersions", a.get("name") or iap["id"], a["productId"], versions))

    for kind, item_type, name, pid, versions in candidates:
        version_id = current_version_id(versions)
        if not version_id:
            failures.append(f"{kind} {name} ({pid}): no version to submit")
            continue
        if version_id in already:
            attached += 1
            print(f"  {kind} {name}: already attached (idempotent)")
            continue
        if submission_id is None:
            submission_id = create_draft_submission(asc, app_id)
        try:
            asc.run(
                "review", "items-add", "--submission", submission_id, "--item-type", item_type, "--item-id", version_id,
                "--if-exists", "skip",
            )
        except asc_cli.AscError as exc:
            failures.append(f"{kind} {name} ({pid}): {exc}")
            continue
        attached += 1
        added = True
        print(f"  {kind} {name}: attached to review submission")
    print(f"  in-app purchases / subscriptions in the draft: {attached}")
    if failures:
        raise ProductSubmitError(
            "these required products were not added to App Review: "
            + "; ".join(failures)
            + ". Resolve them in App Store Connect and rerun the release."
        )
    return ProductSubmitResult(submission_id or "", attached, added, attached > 0, allowed)


def check_draft_items(
    asc: asc_cli.Asc, submission_id: str, *, version_id: str | None, allowed_products: set[str]
) -> None:
    """Refuse to submit a draft holding anything but this version and catalog products.

    A reused draft can hold items added by hand or by another tool; they must
    not ride along into App Review. With version_id, the draft must also hold
    that version, so a draft without it is never submitted in its place.
    """
    unexpected = []
    items = submission_items(asc, submission_id)
    if version_id and not any(related_id(item, "appStoreVersion") == version_id for item in items):
        fail(f"review submission {submission_id} does not hold App Store version {version_id}; not submitting it")
    for item in items:
        app_version = related_id(item, "appStoreVersion")
        product = related_id(item, "subscriptionVersion") or related_id(item, "inAppPurchaseVersion")
        if app_version and app_version == version_id:
            continue
        if product and not app_version and product in allowed_products:
            continue
        unexpected.append(item.get("id"))
    if unexpected:
        fail(
            f"review submission {submission_id} holds items this release did not choose "
            f"({', '.join(map(str, unexpected))}); remove them in App Store Connect and rerun"
        )


def submit_draft(asc: asc_cli.Asc, submission_id: str) -> None:
    call(asc, "review", "submissions-submit", "--id", submission_id, "--confirm")
    print(f"  submitted review submission {submission_id}")


def add_version_to_draft(asc: asc_cli.Asc, version_id: str, version_state: str, *, submission_id: str) -> None:
    """Add the App Store version to the draft unless it is already there."""
    # READY_FOR_REVIEW means the version is already held by a draft submission.
    if version_state == READY_FOR_REVIEW or version_id in draft_item_version_ids(asc, submission_id):
        return
    call(
        asc, "review", "items-add", "--submission", submission_id, "--item-type", "appStoreVersions",
        "--item-id", version_id, "--if-exists", "skip",
    )


RELEASE_TYPE_PUBLIC = "AFTER_APPROVAL"
RELEASE_TYPE_MANUAL = "MANUAL"


def verify_build(asc: asc_cli.Asc, build_id: str, version_string: str, build_number: str) -> dict[str, Any]:
    """Re-read the build and refuse anything but this run's processed build. Returns its attributes."""
    payload = call(
        asc, "builds", "list", "--app", ASC_APP_ID, "--build-number", build_number, "--version", version_string,
        "--platform", PLATFORM, "--processing-state", "all", "--include", "preReleaseVersion",
    )
    included = {(row.get("type"), row.get("id")): row for row in (payload or {}).get("included") or []}
    build = next((row for row in rows(payload) if row.get("id") == build_id), None)
    problems = []
    if build is None:
        problems.append("not listed for this app, version and build number")
        a: dict[str, Any] = {}
        pre: dict[str, Any] = {}
    else:
        a = attrs(build)
        pre = attrs(included.get(("preReleaseVersions", related_id(build, "preReleaseVersion"))))
    if build is not None and a.get("version") != build_number:
        problems.append(f"build number {a.get('version')!r}")
    if build is not None and a.get("processingState") != "VALID":
        problems.append(f"processing state {a.get('processingState')!r}")
    if a.get("expired"):
        problems.append("expired")
    if build is not None and (pre.get("version") != version_string or pre.get("platform") != PLATFORM):
        problems.append(f"version {pre.get('platform')} {pre.get('version')!r}")
    if problems:
        fail(
            f"build {build_id} is not {version_string} ({build_number}), VALID and unexpired: " + ", ".join(problems)
        )
    return a


def attached_build_id(asc: asc_cli.Asc, version_id: str) -> str | None:
    payload = call(asc, "versions", "view", "--version-id", version_id, "--include", "build")
    return related_id(data(payload) or {}, "build")


def confirm_selected_build(asc: asc_cli.Asc, version_id: str, build_id: str) -> None:
    current = attached_build_id(asc, version_id)
    if current != build_id:
        fail(
            f"App Store version {version_id} has build {current or 'none'} selected, "
            f"expected {build_id}; not submitting"
        )


def select_build(asc: asc_cli.Asc, version_id: str, build_id: str, version_string: str, build_number: str) -> None:
    """Explicitly select this run's build for the version, then read it back."""
    verify_build(asc, build_id, version_string, build_number)
    call(asc, "versions", "attach-build", "--version-id", version_id, "--build-id", build_id)
    confirm_selected_build(asc, version_id, build_id)
    print(f"  selected build {build_number} ({build_id}) for version {version_string}")


def ensure_export_compliance(
    asc: asc_cli.Asc, build_id: str, version_string: str, build_number: str, uses_encryption: Any
) -> None:
    """Answer the build's export-compliance question from the catalog, never against it.

    The app declares ITSAppUsesNonExemptEncryption = NO in its build settings, so
    an uploaded build normally arrives answered and this only confirms the answer
    matches store/catalog/products.json (uses_non_exempt_encryption); a build that
    arrives unanswered is answered from the catalog. Verceltics uses only Apple's
    HTTPS, Keychain and CryptoKit (hashing and HMAC request signing).
    """
    if not isinstance(uses_encryption, bool):
        fail("store/catalog/products.json must declare uses_non_exempt_encryption as true or false")
    current = verify_build(asc, build_id, version_string, build_number).get("usesNonExemptEncryption")
    if current is None:
        answer = "true" if uses_encryption else "false"
        call(asc, "builds", "update", "--build-id", build_id, "--uses-non-exempt-encryption", answer)
        current = verify_build(asc, build_id, version_string, build_number).get("usesNonExemptEncryption")
        print(f"  answered export compliance: usesNonExemptEncryption={answer}")
    if current != uses_encryption:
        fail(
            f"build {build_number} declares usesNonExemptEncryption={current}, but the catalog says "
            f"{uses_encryption}; not submitting"
        )


def set_release_type(asc: asc_cli.Asc, version_id: str, *, public_release: bool) -> None:
    release_type = RELEASE_TYPE_PUBLIC if public_release else RELEASE_TYPE_MANUAL
    call(asc, "versions", "update", "--version-id", version_id, "--release-type", release_type)
    print(
        "  release type: "
        + ("release automatically after approval" if public_release else "manual release after approval")
    )


class _ScriptedAsc:
    """Offline asc stand-in for the dry-run self-check: answers by command prefix."""

    def __init__(self, answers: dict[tuple[str, ...], Any]) -> None:
        self.answers = answers
        self.calls: list[tuple[str, ...]] = []

    def run(self, *args: str, **_: Any) -> Any:
        self.calls.append(args)
        for prefix, answer in sorted(self.answers.items(), key=lambda kv: -len(kv[0])):
            if args[: len(prefix)] == prefix:
                return answer(args) if callable(answer) else answer
        if args[:2] in {("review", "items-add"), ("review", "submissions-submit")}:
            return {"data": {"id": "ITEM"}}
        raise AssertionError(f"unexpected asc call {args}")


def dry_run_self_check() -> None:
    """Exercise product selection and the submit order offline so dry-run covers them."""
    pid = DEFAULT_BUNDLE_ID
    subscriptions = [
        # One plan is still DEVELOPER_REJECTED from a prior submit.
        {"id": "S0", "attributes": {"name": "Monthly", "productId": f"{pid}.monthly", "state": "DEVELOPER_REJECTED"}},
        {"id": "S1", "attributes": {"name": "Yearly", "productId": f"{pid}.yearly", "state": "READY_TO_SUBMIT"}},
        {"id": "SX", "attributes": {"name": "Legacy", "productId": f"{pid}.weekly", "state": "READY_TO_SUBMIT"}},
    ]
    purchases = [
        {"id": "IAP1", "attributes": {"name": "Lifetime", "productId": f"{pid}.lifetime", "state": "READY_TO_SUBMIT"}},
        # Already approved: must not be submitted again.
        {"id": "IAP2", "attributes": {"name": "Coffee", "productId": f"{pid}.tip.coffee", "state": "APPROVED"}},
    ]

    def flag(args: tuple[str, ...], name: str) -> str:
        return args[args.index(name) + 1]

    def answers(held: list[dict[str, Any]]) -> dict[tuple[str, ...], Any]:
        return {
            ("review", "items-list"): {"data": held},
            ("subscriptions", "list"): {"data": subscriptions},
            # Two versions, oldest first: the higher-numbered current version
            # must win regardless of response ordering.
            ("subscriptions", "versions", "list"): lambda args: {"data": [
                {"id": f"{flag(args, '--subscription-id')}v1", "attributes": {"version": 1}},
                {"id": f"{flag(args, '--subscription-id')}v2", "attributes": {"version": 2}},
            ]},
            ("iap", "list"): {"data": purchases},
            ("iap", "versions", "list"): lambda args: {"data": [
                {"id": f"{flag(args, '--iap-id')}v1", "attributes": {"version": 1}},
            ]},
        }

    def added(fake: _ScriptedAsc) -> list[str]:
        return [flag(c, "--item-id") for c in fake.calls if c[:2] == ("review", "items-add")]

    intended = intended_product_ids()
    fake = _ScriptedAsc(answers([]))
    result = attach_products(fake, "APP", submission_id="SUB1", intended_ids=intended)  # type: ignore[arg-type]
    assert added(fake) == ["S0v2", "S1v2", "IAP1v1"], f"wrong items added: {added(fake)}"
    assert result.attached == 3 and result.added_items and result.has_products, "expected 3 products attached"
    assert not [c for c in fake.calls if "SX" in c], "a product outside the catalog was considered"
    assert not [c for c in fake.calls if c[:2] == ("review", "submissions-submit")], "attaching must not submit"
    assert {"S0v1", "S0v2", "IAP1v1", "IAP2v1"} <= result.allowed_versions, "catalog product versions are allowed"

    # Retry: the same draft already holds one subscription version, so the rerun
    # must not add it again (App Store Connect rejects duplicates).
    held = [{"id": "ITEM0", "relationships": {"subscriptionVersion": {"data": {"id": "S0v2"}}}}]
    retry = _ScriptedAsc(answers(held))
    result = attach_products(retry, "APP", submission_id="SUB1", intended_ids=intended)  # type: ignore[arg-type]
    assert result.has_products and result.attached == 3, "retry must report the draft holds products"
    assert added(retry) == ["S1v2", "IAP1v1"], f"retry attached the wrong versions: {added(retry)}"
    assert draft_holds_app_version(retry, "SUB1") is False, "false app-version detection"  # type: ignore[arg-type]

    # The version is never added twice, and a draft holding it is detected.
    review = _ScriptedAsc({("review", "items-list"): {"data": [
        {"id": "ITEMV", "relationships": {"appStoreVersion": {"data": {"id": "AV1"}}}},
    ]}})
    assert draft_holds_app_version(review, "SUB1") is True, "app-version items must be detected"  # type: ignore[arg-type]
    add_version_to_draft(review, "AV1", "PREPARE_FOR_SUBMISSION", submission_id="SUB1")  # type: ignore[arg-type]
    assert not added(review), "app version was added again on retry"
    check_draft_items(review, "SUB1", version_id="AV1", allowed_products=set())  # type: ignore[arg-type]
    print("  dry-run self-check: product submission selection OK")


def validate_local_inputs(
    *,
    metadata_dir: Path,
    upload_listing: bool,
    upload_whats_new: bool,
    upload_screenshots: bool,
    upload_creative: bool = False,
    creative_dir: Path | None = None,
) -> None:
    """Check the local files of the enabled steps only, so an unused listing never
    blocks What's New, screenshots or review (the release prepares it only with
    IOS_UPLOAD_LISTING on)."""
    if upload_listing:
        for locale in metadata_locales(metadata_dir):
            try:
                ios_metadata.load_locale(metadata_dir / "ios" / locale, prepared=True)
            except ios_metadata.MetadataError as exc:
                fail(str(exc))
    if upload_whats_new:
        notes = metadata_dir / "ios" / ios_metadata.PRIMARY_LOCALE / WHATS_NEW.filename
        text = read_text(notes)
        if not text:
            fail(f"What's New upload is on, but {notes} is missing or empty "
                 "(add the tag's section to RELEASE_NOTES.md)")
        if len(text) > (WHATS_NEW.limit or 0):
            fail(f"What's New is {len(text)} characters; the limit is {WHATS_NEW.limit}")
    if upload_screenshots:
        for spec in ios_metadata.SCREENSHOT_SETS:
            try:
                ios_metadata.screenshot_files(spec)
            except ios_metadata.MetadataError as exc:
                fail(str(exc))
    if upload_creative:
        creative_sources(creative_dir or ROOT / "store" / "creative" / "ios")


def version_key(version_string: str) -> tuple[int, ...] | None:
    """2.2.1 -> (2, 2, 1), padded to three parts; None when it is not numeric."""
    if not re.fullmatch(r"[0-9]+(\.[0-9]+){0,2}", version_string or ""):
        return None
    parts = [int(part) for part in version_string.split(".")]
    return tuple(parts + [0] * (3 - len(parts)))


def ensure_ios_app_store_version(
    asc: asc_cli.Asc, *, bundle_id: str, expected_app_id: str, version_string: str
) -> None:
    """Create the App Store version for this release when it does not exist yet.

    An existing version with this number is left as it is (the preflight then
    checks its state). Nothing is created when another version is still on its
    way to the store, or when this number is not newer than every existing
    version; the created version is read back before the run continues.
    """
    wanted = version_key(version_string)
    if wanted is None:
        fail(f"version {version_string!r} must look like 2.2 or 2.2.1")
    app_id = find_app(asc, bundle_id, expected_app_id)
    versions = list_ios_app_store_versions(asc, app_id)
    existing = [row for row in versions if attrs(row).get("versionString") == version_string]
    if len(existing) > 1:
        fail(f"ambiguous App Store versions for {version_string!r} on IOS — got {len(existing)} records")
    if existing:
        state = attrs(existing[0]).get("appStoreState") or ""
        print(f"App Store version {version_string} already exists ({state}); nothing to create")
        return
    problems: list[str] = []
    for row in versions:
        other = attrs(row).get("versionString") or ""
        state = attrs(row).get("appStoreState") or ""
        if state not in FINISHED_VERSION_STATES:
            problems.append(
                f"version {other!r} is still {state or 'in an unknown state'}; release or remove it "
                f"in App Store Connect before {version_string} can be created"
            )
        other_key = version_key(other)
        if other_key is not None and other_key >= wanted:
            problems.append(f"version {version_string} is not newer than the existing version {other}")
    if problems:
        fail(
            f"not creating App Store version {version_string} (nothing was changed):\n  "
            + "\n  ".join(problems)
        )
    call(asc, "versions", "create", "--app", app_id, "--version", version_string, "--platform", PLATFORM)
    created = [row for row in list_ios_app_store_versions(asc, app_id) if attrs(row).get("versionString") == version_string]
    if len(created) != 1:
        fail(
            f"created App Store version {version_string}, but reading it back found "
            f"{len(created)} records; check it in App Store Connect before rerunning"
        )
    state = attrs(created[0]).get("appStoreState") or ""
    print(f"created App Store version {version_string} ({state or 'state not reported yet'})")


def version_state_problem(
    version_string: str, state: str, *, edits_metadata: bool, submit: bool
) -> str | None:
    """Why this App Store version state rules out the enabled steps, or None.

    READY_FOR_REVIEW and WAITING_FOR_REVIEW pass here; what they still allow
    (see the module docstring) is checked against App Store Connect afterwards.
    """
    if state in SUBMITTABLE_VERSION_STATES or state == WAITING_FOR_REVIEW:
        return None
    if edits_metadata:
        return (
            f"version {version_string!r} is not editable for metadata (state={state}); "
            f"expected one of: {', '.join(sorted(EDITABLE_VERSION_STATES))}"
        )
    if submit:
        return f"version {version_string!r} cannot be submitted for review (state={state})"
    return None


def preflight(
    asc: asc_cli.Asc,
    *,
    bundle_id: str,
    version_string: str,
    metadata_dir: Path,
    do_listing: bool,
    do_whats_new: bool,
    do_screenshots: bool,
    do_submit: bool,
    do_creative: bool = False,
) -> None:
    """Read-only checks before anything is built or uploaded (asc runs read-only).

    The version must exist (IOS_CREATE_VERSION's step, which runs before this,
    creates a missing one) in a state that allows the enabled steps, and every
    locale they touch must have its localization. Screenshot sets that will be
    replaced get their upload copies (RGB for PNGs with alpha) made and checked.
    The live store step repeats all of this, because App Store Connect can change
    in between.
    """
    app_id = find_app(asc, bundle_id)
    version = get_ios_app_store_version(asc, app_id, version_string)
    version_id = version["id"]
    state = attrs(version).get("appStoreState") or ""
    print(f"ASC preflight: app={app_id} version={version_string} id={version_id} state={state}")
    problem = version_state_problem(
        version_string, state, edits_metadata=do_listing or do_whats_new or do_screenshots or do_creative,
        submit=do_submit,
    )
    if problem:
        fail(problem)
    frozen = state if state == WAITING_FOR_REVIEW else None
    if frozen and do_submit:
        fail(
            f"version {version_string!r} is already {state} with an earlier build, so the build this "
            "run uploads could not be selected; remove it from review in App Store Connect first"
        )
    if do_listing or do_whats_new:
        upload_listing(
            asc,
            version_id=version_id,
            metadata_dir=metadata_dir,
            include_listing=do_listing,
            include_whats_new=do_whats_new,
            frozen_state=frozen,
            write=False,
        )
    if do_listing:
        upload_app_info(asc, app_id=app_id, metadata_dir=metadata_dir, frozen_state=frozen, write=False)
    if do_screenshots:
        if state in LOCKED_MEDIA_STATES:
            require_screenshots_unchanged(
                asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, state=state
            )
        else:
            screenshot_uploads(asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE)
    if do_creative:
        # The header and search results images are placed on this localization.
        version_localization(asc, version_id, ios_metadata.PRIMARY_LOCALE)
    if do_submit and state == READY_FOR_REVIEW:
        # The store step needs the draft holding this version; finding out after
        # the build and upload would be too late.
        draft = find_draft_submission(asc, app_id, version_id)
        if draft is None or not any(
            related_id(item, "appStoreVersion") == version_id for item in submission_items(asc, draft)
        ):
            fail(
                f"App Store version {version_string} is READY_FOR_REVIEW, but no draft review submission holds it; "
                "remove the version from its submission in App Store Connect and rerun"
            )
    print(f"ASC preflight passed: version {version_string} ({state}) is ready for the enabled steps")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--metadata-dir",
        type=Path,
        default=ROOT / "store" / "metadata",
        help="directory holding ios/<locale>/ listing files (ios_metadata.py prepare output)",
    )
    parser.add_argument(
        "--creative-dir",
        type=Path,
        default=ROOT / "store" / "creative" / "ios",
        help="Product page header and search results images, one per folder: "
        + ", ".join(slot[0] for slot in CREATIVE_SLOTS),
    )
    parser.add_argument("--bundle-id", default=DEFAULT_BUNDLE_ID)
    parser.add_argument(
        "--version",
        help="Marketing version from the release tag (e.g. 2.2 for ios-v2.2)",
    )
    parser.add_argument(
        "--build-id",
        help="App Store Connect id of the build uploaded by this run (required to submit)",
    )
    parser.add_argument(
        "--build-number",
        help="CFBundleVersion of the build uploaded by this run (required to submit)",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate flags and local files only — no asc calls",
    )
    mode.add_argument(
        "--preflight",
        action="store_true",
        help="Read-only App Store Connect checks before anything is built or uploaded (no changes)",
    )
    parser.add_argument(
        "--create-version",
        action="store_true",
        help="Create the App Store version for --version when it is missing (CREATE_IOS_VERSION)",
    )
    args = parser.parse_args()

    # find_app() checks the pinned app's bundle id, so only this app is accepted.
    if args.bundle_id != DEFAULT_BUNDLE_ID:
        fail("this publisher only supports the Verceltics iOS bundle")

    if args.create_version:
        if args.preflight:
            fail("--create-version and --preflight are separate steps; run them one at a time")
        if not truthy("CREATE_IOS_VERSION"):
            print("App Store version creation skipped — CREATE_IOS_VERSION is off")
            return
        if not args.version:
            fail("--create-version needs --version (the marketing version from the tag)")
        if version_key(args.version) is None:
            fail(f"version {args.version!r} must look like 2.2 or 2.2.1")
        if args.dry_run:
            print(f"ASC dry-run — would create App Store version {args.version} if it is missing")
            return
        ensure_ios_app_store_version(
            asc_from_environment(),
            bundle_id=args.bundle_id,
            expected_app_id=ASC_APP_ID,
            version_string=args.version,
        )
        return

    do_listing = truthy("UPLOAD_LISTING")
    do_whats_new = truthy("UPLOAD_WHATS_NEW")
    do_screenshots = truthy("UPLOAD_SCREENSHOTS")
    do_creative = truthy("UPLOAD_CREATIVE_ASSETS")
    do_submit = truthy("SUBMIT_IOS_REVIEW")
    do_public = truthy("PUBLIC_RELEASE")

    if not (do_listing or do_whats_new or do_screenshots or do_creative or do_submit):
        print(
            "ASC release skipped — UPLOAD_LISTING / UPLOAD_WHATS_NEW / UPLOAD_SCREENSHOTS / "
            "UPLOAD_CREATIVE_ASSETS / SUBMIT_IOS_REVIEW are off"
        )
        return
    if do_public and not do_submit:
        fail("PUBLIC_RELEASE only applies when SUBMIT_IOS_REVIEW is on")
    # The preflight runs before this run has a build; every other mode needs it to submit.
    if do_submit and not args.preflight and not (args.build_id and args.build_number):
        fail(
            "submitting needs --build-id and --build-number for the build uploaded by "
            "this run; an older build is never substituted"
        )

    validate_local_inputs(
        metadata_dir=args.metadata_dir,
        upload_listing=do_listing,
        upload_whats_new=do_whats_new,
        upload_screenshots=do_screenshots,
        upload_creative=do_creative,
        creative_dir=args.creative_dir,
    )
    catalog = load_catalog()

    if args.dry_run:
        print("ASC dry-run — local inputs OK; no asc calls")
        print(
            f"  bundle={args.bundle_id} listing={do_listing} whats_new={do_whats_new} "
            f"screenshots={do_screenshots} creative={do_creative} submit={do_submit} public_release={do_public}"
        )
        if do_submit:
            dry_run_self_check()
        return

    if not args.version:
        fail("--version is required for live ASC calls (pass marketing version from tag)")

    if args.preflight:
        preflight(
            asc_from_environment(read_only=True),
            bundle_id=args.bundle_id,
            version_string=args.version,
            metadata_dir=args.metadata_dir,
            do_listing=do_listing,
            do_whats_new=do_whats_new,
            do_screenshots=do_screenshots,
            do_submit=do_submit,
            do_creative=do_creative,
        )
        return

    asc = asc_from_environment()
    app_id = find_app(asc, args.bundle_id)
    version = get_ios_app_store_version(asc, app_id, args.version)
    version_id = version["id"]
    state = attrs(version).get("appStoreState") or ""
    print(f"ASC app={app_id} version={args.version} id={version_id} state={state}")
    edits_metadata = do_listing or do_whats_new or do_screenshots or do_creative
    problem = version_state_problem(args.version, state, edits_metadata=edits_metadata, submit=do_submit)
    if problem:
        fail(problem)
    # A rerun after a successful submit changes nothing: it only confirms that App
    # Store Connect already holds this run's build and the repository's metadata.
    frozen = state if state == WAITING_FOR_REVIEW else None
    media_locked = state in LOCKED_MEDIA_STATES
    if frozen and do_submit:
        confirm_selected_build(asc, version_id, args.build_id)
    # Pick and check the version's draft before any write (text, screenshots,
    # images or the build), so a run that has to stop here changes nothing.
    submission_id: str | None = None
    if do_submit and not frozen:
        submission_id = find_draft_submission(asc, app_id, version_id)
        if state == READY_FOR_REVIEW and (
            submission_id is None
            or not any(related_id(item, "appStoreVersion") == version_id for item in submission_items(asc, submission_id))
        ):
            fail(
                f"version {args.version} is READY_FOR_REVIEW, but no draft review submission holds it; "
                "remove the version from its submission in App Store Connect and rerun"
            )

    # Read-only comparisons first, so a mismatch stops the run before any write.
    pending_screenshots: PendingScreenshots = []
    if do_screenshots:
        print(f"checking ASC screenshots{' (they cannot change in this state)' if media_locked else ''}…")
        if media_locked:
            require_screenshots_unchanged(
                asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, state=state
            )
        else:
            pending_screenshots = screenshot_uploads(
                asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE
            )
    if do_creative and media_locked:
        print("checking ASC product page header and search results images (they cannot change in this state)…")
        sync_creative_assets(
            asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE,
            creative_dir=args.creative_dir, editable=False,
        )

    if do_listing or do_whats_new:
        parts = [name for name, on in (("listing", do_listing), ("What's New", do_whats_new)) if on]
        print(f"{'checking' if frozen else 'uploading'} ASC {' + '.join(parts)}…")
        upload_listing(
            asc,
            version_id=version_id,
            metadata_dir=args.metadata_dir,
            include_listing=do_listing,
            include_whats_new=do_whats_new,
            frozen_state=frozen,
        )
        if do_listing:
            upload_app_info(asc, app_id=app_id, metadata_dir=args.metadata_dir, frozen_state=frozen)

    if pending_screenshots:
        print("uploading ASC screenshots…")
        upload_screenshots(
            asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, pending=pending_screenshots
        )
    if do_creative and not media_locked:
        print("syncing ASC product page header and search results images…")
        sync_creative_assets(
            asc, app_id=app_id, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE,
            creative_dir=args.creative_dir, editable=True, reserved_draft=submission_id,
        )

    if frozen:
        if do_submit:
            print("ASC submit skipped — version already WAITING_FOR_REVIEW with this run's build (idempotent)")
            # Products that missed the version's submission go out in a draft of
            # their own. `has_products` also covers a retry where an earlier run
            # attached them but failed to submit their draft. Only a draft that
            # holds nothing but catalog products is submitted, so an unrelated
            # app version never ships from here, and no draft is created when
            # there is nothing to send.
            try:
                result = attach_products(asc, app_id, submission_id=None, intended_ids=intended_product_ids(catalog))
            except ProductSubmitError as exc:
                fail(str(exc))
            draft = result.submission_id
            if result.has_products:
                if draft_holds_app_version(asc, draft):
                    fail(
                        "refusing to submit a draft that contains an app "
                        "version; remove it in App Store Connect and rerun"
                    )
                check_draft_items(asc, draft, version_id=None, allowed_products=result.allowed_versions)
                submit_draft(asc, draft)
        print("ASC release step finished")
        return

    if do_submit:
        print("selecting this run's build…")
        select_build(asc, version_id, args.build_id, args.version, args.build_number)
        ensure_export_compliance(
            asc, args.build_id, args.version, args.build_number,
            catalog.get("uses_non_exempt_encryption"),
        )
        set_release_type(asc, version_id, public_release=do_public)
        print("adding in-app purchases / subscriptions and the version to the review submission…")
        submission_id = submission_id or create_draft_submission(asc, app_id)
        try:
            # A first-time subscription must ship with the version, so a failed
            # required product blocks the whole submission rather than letting
            # the version enter review without it.
            result = attach_products(
                asc, app_id, submission_id=submission_id, intended_ids=intended_product_ids(catalog)
            )
        except ProductSubmitError as exc:
            fail(f"{exc} Not submitting the app version.")
        add_version_to_draft(asc, version_id, state, submission_id=submission_id)
        # Recheck immediately before anything goes to App Review: the selection
        # must still be this run's processed build, and the draft must hold only
        # what this release chose.
        verify_build(asc, args.build_id, args.version, args.build_number)
        confirm_selected_build(asc, version_id, args.build_id)
        check_draft_items(asc, submission_id, version_id=version_id, allowed_products=result.allowed_versions)
        print("submitting for App Store review…")
        submit_draft(asc, submission_id)
        print(f"  {result.attached} product(s) went out with the version")

    print("ASC release step finished")


if __name__ == "__main__":
    main()
