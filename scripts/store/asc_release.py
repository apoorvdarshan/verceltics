#!/usr/bin/env python3
"""App Store Connect listing, What's New, screenshots, creative assets, build selection and review.

Run by ios-release.yml with the resolved iOS switches as environment flags:
UPLOAD_LISTING, UPLOAD_WHATS_NEW, UPLOAD_SCREENSHOTS, UPLOAD_CREATIVE_ASSETS,
SUBMIT_IOS_REVIEW and PUBLIC_RELEASE. Every flag that is off skips its App Store
Connect work, and its local files are not checked: listing text only with
UPLOAD_LISTING, What's New only with UPLOAD_WHATS_NEW, screenshots only with
UPLOAD_SCREENSHOTS, header and search results images only with
UPLOAD_CREATIVE_ASSETS.

Screenshots go up as RGB copies staged in a temporary folder: App Store Connect
refuses files that store an alpha channel, even when every pixel is opaque, so
transparent pixels are laid over black and the colour profile is kept. The
files in store/app-store/screenshots/ are only read, never rewritten.

Product page header and search results images (UPLOAD_CREATIVE_ASSETS) live in
the app's Asset Library. A new image is uploaded once, sent to App Review in a
review submission of its own (never the version's draft), and placed on the
version's localization once Apple approves it.

Submitting for review covers both the App Store version and any Ready-to-Submit
subscriptions and in-app purchases from store/catalog/products.json. As of the
2026 workflow, subscriptions attach to the review submission through the
`subscriptionVersion` relationship on Review Submission Items; a first-time
subscription must go out with the version or App Review rejects the build.
Review submission only ever selects the build uploaded by this run, and nothing
goes to App Review until that selection has been rechecked.

What each App Store version state allows:
  editable states (PREPARE_FOR_SUBMISSION, rejected)   every enabled step
  READY_FOR_REVIEW (in a draft review submission, e.g. after a failed submit)
                                         listing/What's New text and submission;
                                         screenshots only when already identical;
                                         no header or search results image is
                                         uploaded, reviewed or placed
  WAITING_FOR_REVIEW (submitted)         nothing changes: a rerun passes only when
                                         App Store Connect already matches the
                                         repository and holds this run's build
  anything else                          the run fails

With review submission on, the version's draft review submission is picked and
checked before anything is written, and reserved for the version and its
products: header and search results images never go out in it.

`--create-version` (IOS_CREATE_VERSION, CREATE_IOS_VERSION here) runs first on a
release run: it adds the App Store version for the tag when App Store Connect does
not have it yet, and leaves an existing one alone. It refuses when another
version is still on its way to the store or the tag's version is not newer than
every existing one.

`--dry-run` checks the switches and local files only (no network), staging the
screenshots as a real run does.
`--preflight` runs the same version, localization and state checks with GET
requests only, before anything is built or uploaded (ios-release.yml prepare):
with review submission also a READY_FOR_REVIEW version's draft, with creative
assets also the images' Asset Library state.
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
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ios_metadata  # noqa: E402
from ios_metadata import APP_INFO_FIELDS, VERSION_FIELDS, WHATS_NEW  # noqa: E402
# The Apple Watch sets: each staged frame's size picks its display type.
from ios_metadata import WATCH_DISPLAY_TYPES, WATCH_FOLDER, WATCH_LABELS  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASC_BASE = "https://api.appstoreconnect.apple.com/v1"
DEFAULT_BUNDLE_ID = "com.apoorvdarshan.verceltics"
# Verceltics' App Store Connect app (apps.apple.com/app/id6761645656). A version is
# only ever created for this app, never for whatever else the bundle id resolves to.
DEFAULT_APP_ID = "6761645656"
# Product page creative assets: the folder under --creative-dir, the placement
# type, a label for logs, and the formats Apple takes. Each folder holds at most
# one image; a slot without an image is left alone.
HEADER_PLACEMENT = "PRODUCT_PAGE_HEADER_ASSET"
SEARCH_PLACEMENT = "APP_STORE_SEARCH_RESULTS_ASSET"
CREATIVE_SLOTS = (
    ("header", HEADER_PLACEMENT, "product page header", ("png",)),
    ("search-results", SEARCH_PLACEMENT, "search results", ("png", "jpeg")),
)
CREATIVE_EXTENSIONS = {".png": "png", ".jpg": "jpeg", ".jpeg": "jpeg"}
# Apple's image specs: the header is a 3840x1646 PNG; search results images are
# 3:2 PNG or JPEG from 1920x1280 to 3840x2560.
HEADER_SIZE = (3840, 1646)
SEARCH_RESULTS_WIDTHS = (1920, 3840)
CREATIVE_PLACEMENT_GROUP = "DEFAULT_PROFILE"
DEFAULT_CREATIVE_DIR = ROOT / "store" / "creative" / "ios"
# Asset Library image states (AppAssetLibraryAssetState). AWAITING_UPLOAD is the
# reservation an interrupted upload leaves behind: like a failed or archived
# image it is ignored, and the image is uploaded again.
CREATIVE_DEAD_STATES = {"AWAITING_UPLOAD", "FAILED", "ARCHIVED", "REJECTED"}
# Uploaded and still being processed; App Review takes it once it is ready.
CREATIVE_PROCESSING_STATES = {"UPLOAD_COMPLETE", "COMPLETE"}
# Processed, not yet submitted (READY_FOR_REVIEW: in a draft review submission).
CREATIVE_READY_STATES = {"PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW"}
CREATIVE_PENDING_STATES = {"WAITING_FOR_REVIEW", "IN_REVIEW", "ACCEPTED"}
# Processing a freshly uploaded image usually takes seconds.
CREATIVE_PROCESSING_TIMEOUT_SECONDS = 600
CREATIVE_POLL_SECONDS = 5
# A review submission item's relationships, requested explicitly (App Store
# Connect leaves them out otherwise).
DRAFT_ITEM_INCLUDE = "subscriptionVersion,appStoreVersion,inAppPurchaseVersion,appAssetLibraryImage"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# PNG chunks that describe colour, copied from the source to the staged file
# (Pillow writes an ICC profile, iCCP, itself).
PNG_COLOUR_CHUNKS = (b"sRGB", b"gAMA", b"cHRM", b"cICP")
EDITABLE_VERSION_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
}
# READY_FOR_REVIEW means the version already sits in a draft review submission
# (for example after a failed submit); it can still be submitted, and its text
# (listing, What's New) stays editable. Screenshots cannot change there.
READY_FOR_REVIEW = "READY_FOR_REVIEW"
SUBMITTABLE_VERSION_STATES = EDITABLE_VERSION_STATES | {READY_FOR_REVIEW}
# Submitted: nothing may change. A rerun only confirms App Store Connect already matches.
WAITING_FOR_REVIEW = "WAITING_FOR_REVIEW"
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


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


class AscHttpError(SystemExit):
    """An App Store Connect HTTP error; exits like fail() unless a caller retries."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(f"error: {message}")
        self.status = status


def truthy(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def read_text(path: Path) -> str | None:
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8").strip()
    return text or None


def attrs(row: dict[str, Any] | None) -> dict[str, Any]:
    return (row or {}).get("attributes") or {}


def related_id(row: dict[str, Any], name: str) -> str | None:
    """The id a JSON:API row's `name` relationship points at, if it was included."""
    related = (((row.get("relationships") or {}).get(name) or {}).get("data")) or {}
    return related.get("id") if isinstance(related, dict) else None


def png_chunks(raw: bytes, *, strict: bool = True) -> list[tuple[bytes, bytes]]:
    """(type, data) of every chunk of a PNG, up to IEND.

    Raises ValueError when the bytes are not a PNG with an IHDR header. Strict
    (the default) also requires a well-formed file: every chunk complete with a
    matching CRC, image data that decompresses to the rows the header describes,
    and a final IEND, so a truncated or corrupt file fails before anything is
    uploaded. Lenient reading (staging without Pillow, which only needs the
    header) stops at the first chunk that runs past the end of the file.
    """
    if not raw.startswith(PNG_SIGNATURE):
        raise ValueError("not a PNG")
    chunks: list[tuple[bytes, bytes]] = []
    offset = len(PNG_SIGNATURE)
    while offset + 8 <= len(raw):
        length = int.from_bytes(raw[offset : offset + 4], "big")
        kind = raw[offset + 4 : offset + 8]
        start = offset + 8
        end = start + length
        if end + (4 if strict else 0) > len(raw):
            if strict or not chunks:
                raise ValueError(f"truncated {kind.decode('latin-1')!r} chunk")
            break
        data = raw[start:end]
        if strict and zlib.crc32(kind + data) != int.from_bytes(raw[end : end + 4], "big"):
            raise ValueError(f"the {kind.decode('latin-1')!r} chunk fails its CRC check")
        chunks.append((kind, data))
        offset = end + 4  # data, then the CRC
        if kind == b"IEND":
            break
    if not chunks or chunks[0][0] != b"IHDR" or len(chunks[0][1]) < 13:
        raise ValueError("no IHDR header")
    if strict:
        if chunks[-1][0] != b"IEND":
            raise ValueError("no IEND chunk (the file is truncated)")
        check_png_image_data(chunks)
    return chunks


# Samples per pixel for each PNG colour type.
PNG_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def check_png_image_data(chunks: list[tuple[bytes, bytes]]) -> None:
    """Raises ValueError unless the IDAT data is one complete zlib stream of the header's size.

    Decompresses in bounded steps; the size is checked for non-interlaced images
    (Pillow writes those), an interlaced image only needs a complete stream.
    """
    header = chunks[0][1]
    width, height = png_size(chunks)
    bit_depth, colour_type, interlace = header[8], header[9], header[12]
    if colour_type not in PNG_CHANNELS:
        raise ValueError(f"unknown PNG colour type {colour_type}")
    idat = [data for kind, data in chunks if kind == b"IDAT"]
    if not idat:
        raise ValueError("no image data (IDAT)")
    inflater = zlib.decompressobj()
    size = 0
    try:
        for data in idat:
            pending = data
            while pending:
                size += len(inflater.decompress(pending, 1 << 20))
                pending = inflater.unconsumed_tail
        size += len(inflater.flush())
    except zlib.error as exc:
        raise ValueError(f"the image data does not decompress ({exc})") from None
    if not inflater.eof:
        raise ValueError("the image data ends early (the file is truncated)")
    if interlace == 0:
        expected = height * (1 + (width * PNG_CHANNELS[colour_type] * bit_depth + 7) // 8)
        if size != expected:
            raise ValueError(
                f"the image data holds {size} bytes, not the {expected} a {width}x{height} image needs"
            )


def png_size(chunks: list[tuple[bytes, bytes]]) -> tuple[int, int]:
    header = chunks[0][1]
    return int.from_bytes(header[0:4], "big"), int.from_bytes(header[4:8], "big")


def png_stores_alpha(chunks: list[tuple[bytes, bytes]]) -> bool:
    """True when a PNG stores alpha: grey or RGB with alpha (colour type 4 or 6), or a tRNS chunk.

    App Store Connect refuses such files even when every pixel is opaque.
    """
    colour_type = chunks[0][1][9]
    return colour_type in (4, 6) or any(kind == b"tRNS" for kind, _ in chunks)


def jpeg_size(raw: bytes) -> tuple[int, int] | None:
    """(width, height) from a JPEG's start-of-frame segment, or None when there is none."""
    if raw[:3] != b"\xff\xd8\xff":
        return None
    offset = 2
    while offset + 9 < len(raw):
        if raw[offset] != 0xFF:
            offset += 1
            continue
        marker = raw[offset + 1]
        if marker == 0xFF:
            offset += 1
            continue
        if marker in (0x01, 0xD8) or 0xD0 <= marker <= 0xD7:
            offset += 2
            continue
        # SOF0-SOF15, except DHT (C4), JPG (C8) and DAC (CC).
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height = int.from_bytes(raw[offset + 5 : offset + 7], "big")
            width = int.from_bytes(raw[offset + 7 : offset + 9], "big")
            return width, height
        offset += 2 + int.from_bytes(raw[offset + 2 : offset + 4], "big")
    return None


def staged_png(path: Path, label: str) -> tuple[int, int]:
    """The size of a staged screenshot; fails unless it is a PNG without alpha.

    The last guard before upload: staging (stage_screenshots) writes RGB, so a
    file that still stores alpha was not staged by it.
    """
    try:
        chunks = png_chunks(path.read_bytes())
    except ValueError as exc:
        fail(f"the {label} screenshot {path.name} is not a readable PNG ({exc})")
    if png_stores_alpha(chunks):
        fail(
            f"the {label} screenshot {path.name} stores an alpha channel (PNG colour type 4 or 6, "
            "or a tRNS chunk), which App Store Connect refuses; stage it with stage_screenshots, "
            "which saves App Store screenshots as RGB"
        )
    return png_size(chunks)


class AscClient:
    # JWTs are minted for 20 minutes; refresh well before that during long waits.
    TOKEN_MAX_AGE_SECONDS = 600

    def __init__(
        self, token: str | None = None, *, token_factory: Any | None = None
    ) -> None:
        if token is None and token_factory is None:
            raise ValueError("AscClient needs a token or a token_factory")
        self._token_factory = token_factory
        self._token = token if token is not None else token_factory()
        self._token_minted = time.monotonic()

    def _authorization(self) -> str:
        if (
            self._token_factory is not None
            and time.monotonic() - self._token_minted > self.TOKEN_MAX_AGE_SECONDS
        ):
            self._token = self._token_factory()
            self._token_minted = time.monotonic()
        return f"Bearer {self._token}"

    def request(
        self,
        method: str,
        path: str,
        *,
        body: dict[str, Any] | None = None,
        raw_body: bytes | None = None,
        content_type: str = "application/json",
        accept: str = "application/json",
    ) -> dict[str, Any] | None:
        url = path if path.startswith("http") else f"{ASC_BASE}{path}"
        headers = {
            "Authorization": self._authorization(),
            "Accept": accept,
        }
        data: bytes | None = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = content_type
        elif raw_body is not None:
            data = raw_body
            headers["Content-Type"] = content_type

        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = resp.read()
                if not payload:
                    return None
                if accept != "application/json":
                    return {"raw": payload}
                return json.loads(payload.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            print(f"error: ASC {method} {path} → HTTP {exc.code}: {detail[:2000]}", file=sys.stderr)
            raise AscHttpError(exc.code, f"ASC {method} {path} → HTTP {exc.code}") from None

    def get(self, path: str) -> dict[str, Any]:
        result = self.request("GET", path)
        assert result is not None
        return result

    def patch(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        result = self.request("PATCH", path, body=body)
        assert result is not None
        return result

    def post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        result = self.request("POST", path, body=body)
        assert result is not None
        return result

    def upload_bytes(
        self, url: str, data: bytes, headers: dict[str, str], *, method: str = "PUT"
    ) -> None:
        req = urllib.request.Request(
            url, data=data, method=method.upper(), headers=headers
        )
        try:
            with urllib.request.urlopen(req, timeout=300):
                return
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            fail(f"ASC asset upload → HTTP {exc.code}: {detail[:1000]}")


def make_asc_token(key_id: str, issuer_id: str, private_key: str) -> str:
    import jwt

    now = int(time.time())
    payload = {
        "iss": issuer_id,
        "iat": now,
        "exp": now + 1200,
        "aud": "appstoreconnect-v1",
    }
    headers = {"kid": key_id, "typ": "JWT"}
    return jwt.encode(payload, private_key, algorithm="ES256", headers=headers)


def load_credentials() -> tuple[str, str, str]:
    key_id = os.environ.get("APP_STORE_CONNECT_API_KEY_ID", "").strip()
    issuer_id = os.environ.get("APP_STORE_CONNECT_ISSUER_ID", "").strip()
    key_p8 = os.environ.get("APP_STORE_CONNECT_API_KEY_P8", "").strip()
    if not key_id or not issuer_id or not key_p8:
        fail(
            "missing ASC credentials — set APP_STORE_CONNECT_API_KEY_ID, "
            "APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_API_KEY_P8"
        )
    key_p8 = key_p8.replace("\\n", "\n")
    return key_id, issuer_id, key_p8


def client_from_environment() -> AscClient:
    """Client whose JWT is re-minted automatically during long-running steps."""
    key_id, issuer_id, key_p8 = load_credentials()
    return AscClient(token_factory=lambda: make_asc_token(key_id, issuer_id, key_p8))


def find_app(client: AscClient, bundle_id: str) -> str:
    """The id of exactly one App Store Connect app whose bundle id is bundle_id."""
    query = urllib.parse.urlencode({"filter[bundleId]": bundle_id})
    payload = client.get(f"/apps?{query}")
    # Only an exact, reported bundle id counts; a row without one is never assumed to match.
    data = [row for row in payload.get("data") or []
            if (row.get("attributes") or {}).get("bundleId") == bundle_id]
    if not data:
        fail(f"no App Store Connect app for bundle id {bundle_id}")
    if len(data) > 1:
        fail(f"ambiguous: {len(data)} App Store Connect apps for bundle id {bundle_id}")
    return data[0]["id"]


def iter_collection(client: AscClient, path: str) -> list[dict[str, Any]]:
    """Return every record in an ASC collection, following links.next."""
    rows: list[dict[str, Any]] = []
    while path:
        payload = client.get(path)
        rows.extend(payload.get("data") or [])
        next_url = (payload.get("links") or {}).get("next")
        if not next_url:
            break
        path = next_url
    return rows


def list_ios_app_store_versions(client: AscClient, app_id: str) -> list[dict[str, Any]]:
    # Prefer the app relationship listing — some ASC API keys cannot
    # GET_COLLECTION on /appStoreVersions with filter[app]=… (403).
    return iter_collection(
        client,
        f"/apps/{app_id}/appStoreVersions?"
        + urllib.parse.urlencode({"limit": "50", "filter[platform]": "IOS"}),
    )


def get_ios_app_store_version(
    client: AscClient, app_id: str, version_string: str
) -> dict[str, Any]:
    matches = [
        row
        for row in list_ios_app_store_versions(client, app_id)
        if (row.get("attributes") or {}).get("versionString") == version_string
    ]
    if not matches:
        fail(
            f"no IOS App Store version {version_string!r} for app {app_id} "
            "(turn on IOS_CREATE_VERSION, or create the version in App Store Connect first)"
        )
    if len(matches) > 1:
        fail(
            f"ambiguous App Store versions for {version_string!r} on IOS — "
            f"got {len(matches)} records"
        )
    return matches[0]


def localization_for_locale(
    client: AscClient, collection_path: str, locale: str
) -> dict[str, Any]:
    query = urllib.parse.urlencode({"filter[locale]": locale, "limit": "1"})
    payload = client.get(f"{collection_path}?{query}")
    data = payload.get("data") or []
    if not data:
        fail(f"no {locale} localization at {collection_path}; add it in App Store Connect first")
    return data[0]


def metadata_locales(metadata_dir: Path) -> list[str]:
    try:
        return ios_metadata.locales(metadata_dir / "ios")
    except ios_metadata.MetadataError as exc:
        fail(str(exc))


def changed_attributes(current: dict[str, Any], wanted: dict[str, str]) -> dict[str, str]:
    return {key: value for key, value in wanted.items() if (current.get(key) or "") != value}


def listing_changes(
    client: AscClient,
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
        version_loc = localization_for_locale(
            client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
        )
        plan.append((locale, version_loc, changed_attributes(version_loc.get("attributes") or {}, wanted)))
    return plan


def upload_listing(
    client: AscClient,
    *,
    version_id: str,
    metadata_dir: Path,
    include_listing: bool = True,
    include_whats_new: bool = False,
    frozen_state: str | None = None,
    write: bool = True,
) -> None:
    """Update each locale's version localization. Listing text and What's New are separate gates.

    With frozen_state (a submitted version), nothing is written: the run passes only
    when App Store Connect already holds the repository's text. write=False (the
    preflight) reads and reports only.
    """
    for locale, version_loc, changed in listing_changes(
        client,
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
        client.patch(
            f"/appStoreVersionLocalizations/{version_loc['id']}",
            {
                "data": {
                    "type": "appStoreVersionLocalizations",
                    "id": version_loc["id"],
                    "attributes": changed,
                }
            },
        )
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
    client: AscClient,
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
    info, editable = app_info_target(iter_collection(client, f"/apps/{app_id}/appInfos?limit=50"))
    for locale, wanted in wanted_by_locale.items():
        loc = localization_for_locale(client, f"/appInfos/{info['id']}/appInfoLocalizations", locale)
        changed = changed_attributes(loc.get("attributes") or {}, wanted)
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
        client.patch(
            f"/appInfoLocalizations/{loc['id']}",
            {"data": {"type": "appInfoLocalizations", "id": loc["id"], "attributes": changed}},
        )
        print(f"  updated app info ({locale}): {', '.join(changed)}")


def screenshot_sets_for_localization(
    client: AscClient, version_loc_id: str
) -> list[dict[str, Any]]:
    payload = client.get(
        f"/appStoreVersionLocalizations/{version_loc_id}/appScreenshotSets"
    )
    return payload.get("data") or []


def ensure_screenshot_set(
    client: AscClient, version_loc_id: str, display_type: str
) -> dict[str, Any]:
    for item in screenshot_sets_for_localization(client, version_loc_id):
        if (item.get("attributes") or {}).get("screenshotDisplayType") == display_type:
            return item
    created = client.post(
        "/appScreenshotSets",
        {
            "data": {
                "type": "appScreenshotSets",
                "attributes": {"screenshotDisplayType": display_type},
                "relationships": {
                    "appStoreVersionLocalization": {
                        "data": {
                            "type": "appStoreVersionLocalizations",
                            "id": version_loc_id,
                        }
                    }
                },
            }
        },
    )
    return created["data"]


def list_screenshot_ids(client: AscClient, screenshot_set_id: str) -> list[str]:
    payload = client.get(f"/appScreenshotSets/{screenshot_set_id}/appScreenshots")
    return [shot["id"] for shot in payload.get("data") or []]


def delete_screenshots(client: AscClient, screenshot_ids: list[str]) -> None:
    for shot_id in screenshot_ids:
        client.request("DELETE", f"/appScreenshots/{shot_id}")


def send_upload_operations(
    client: AscClient, reserved: dict[str, Any], raw: bytes, name: str
) -> None:
    """Send a file's bytes the way the reservation's uploadOperations ask for."""
    upload_ops = attrs(reserved).get("uploadOperations") or []
    if not upload_ops:
        fail(f"ASC returned no uploadOperations for {name}")
    for op in upload_ops:
        offset = int(op.get("offset", 0))
        length = int(op["length"])
        chunk = raw[offset : offset + length]
        headers = {h["name"]: h["value"] for h in op.get("requestHeaders") or []}
        method = (op.get("method") or "PUT").upper()
        client.upload_bytes(op["url"], chunk, headers, method=method)


def upload_screenshot_file(
    client: AscClient, screenshot_set_id: str, image_path: Path
) -> None:
    raw = image_path.read_bytes()
    reserved = client.post(
        "/appScreenshots",
        {
            "data": {
                "type": "appScreenshots",
                "attributes": {
                    "fileName": image_path.name,
                    "fileSize": len(raw),
                },
                "relationships": {
                    "appScreenshotSet": {
                        "data": {"type": "appScreenshotSets", "id": screenshot_set_id}
                    }
                },
            }
        },
    )
    shot = reserved["data"]
    shot_id = shot["id"]
    send_upload_operations(client, shot, raw, image_path.name)
    # Committing with the file's MD5 lets App Store Connect verify the upload, and lets
    # a later rerun recognise screenshots that are already in place.
    client.patch(
        f"/appScreenshots/{shot_id}",
        {
            "data": {
                "type": "appScreenshots",
                "id": shot_id,
                "attributes": {"uploaded": True, "sourceFileChecksum": md5_hex(raw)},
            }
        },
    )
    print(f"  uploaded screenshot {image_path.name}")


def replace_screenshot_set(
    client: AscClient, version_loc_id: str, display_type: str, pngs: list[Path]
) -> None:
    """Replace one display type's screenshots with pngs, in order."""
    if not pngs or len(pngs) > ios_metadata.SCREENSHOTS_PER_SET:
        fail(f"{display_type} needs 1–{ios_metadata.SCREENSHOTS_PER_SET} screenshots, got {len(pngs)}")
    set_id = ensure_screenshot_set(client, version_loc_id, display_type)["id"]
    # Old ASC ids still on the set; pop front when deleting for capacity or final cleanup.
    pending_old_ids = list_screenshot_ids(client, set_id)

    # Upload replacements one at a time. ASC rejects new reservations when the set is
    # at capacity, so delete one pending old screenshot before each upload when full.
    # If a mid-run upload fails, the live set may be mixed (some olds removed, some news
    # uploaded); fix the error and re-run, or restore screenshots in App Store Connect.
    for path in pngs:
        current_ids = list_screenshot_ids(client, set_id)
        if len(current_ids) >= ios_metadata.SCREENSHOTS_PER_SET:
            if not pending_old_ids:
                fail(
                    f"{display_type} screenshot set at capacity "
                    f"({ios_metadata.SCREENSHOTS_PER_SET}) with no old screenshots left to replace"
                )
            delete_screenshots(client, [pending_old_ids.pop(0)])
        upload_screenshot_file(client, set_id, path)

    if pending_old_ids:
        delete_screenshots(client, pending_old_ids)
        print(f"  removed {len(pending_old_ids)} previous {display_type} screenshot(s)")


def shown_path(path: Path) -> str:
    """A path for messages: relative to the repository when it is inside it."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def md5_hex(data: bytes) -> str:
    """App Store Connect's sourceFileChecksum: the MD5 of the file (an integrity check)."""
    return hashlib.md5(data, usedforsecurity=False).hexdigest()


def try_import_pillow():
    try:
        from PIL import Image  # type: ignore[import-untyped]

        return Image
    except ImportError:
        return None


def opaque_rgb(Image, img):
    """The image as RGB, with transparent pixels laid over black."""
    rgba = img.convert("RGBA")
    canvas = Image.new("RGB", rgba.size, (0, 0, 0))
    canvas.paste(rgba, mask=rgba.getchannel("A"))
    return canvas


def colour_chunks(raw: bytes) -> list[tuple[bytes, bytes]]:
    """The source PNG's colour chunks other than its ICC profile (Pillow keeps that one)."""
    try:
        chunks = png_chunks(raw, strict=False)
    except ValueError:
        return []
    return [(kind, data) for kind, data in chunks if kind in PNG_COLOUR_CHUNKS]


def prepare_asc_png(Image, path: Path) -> bytes:
    """An RGB PNG of the frame, without alpha, colour profile kept.

    Transparent pixels are laid over black once. Frames keep their size:
    ios_metadata.py accepts only App Store screenshot sizes, so nothing is resized.
    """
    from PIL import PngImagePlugin  # type: ignore[import-untyped]

    raw = path.read_bytes()
    try:
        with Image.open(io.BytesIO(raw)) as img:
            icc_profile = img.info.get("icc_profile")
            out_img = opaque_rgb(Image, img)
    except (OSError, SyntaxError, ValueError) as exc:
        fail(f"{shown_path(path)} is not a readable PNG ({exc})")
    info = PngImagePlugin.PngInfo()
    for kind, data in colour_chunks(raw):
        info.add(kind, data)
    out = io.BytesIO()
    options = {"icc_profile": icc_profile} if icc_profile else {}
    out_img.save(out, format="PNG", pnginfo=info, **options)
    return out.getvalue()


def stores_alpha(path: Path) -> bool:
    """True when a PNG stores alpha (colour type 4 or 6, or a tRNS chunk)."""
    try:
        chunks = png_chunks(path.read_bytes(), strict=False)
    except ValueError:
        fail(f"not a PNG: {shown_path(path)}")
    return png_stores_alpha(chunks)


def stage_screenshots(out_dir: Path) -> Path:
    """Write the repository's App Store screenshots into out_dir as RGB PNGs; returns out_dir.

    One folder per set, named like its store/app-store/screenshots/ folder:
    iphone and ipad, plus iphone-duo and watch when they have frames. With Pillow
    every frame is saved as RGB: transparent pixels laid over black, colour
    profile kept. Without Pillow a frame is copied as it is, so one that stores
    alpha fails here, before any App Store Connect call. The committed files are
    only read, never rewritten.
    """
    try:
        sets = ios_metadata.screenshot_sets()
    except ios_metadata.MetadataError as exc:
        fail(str(exc))
    Image = try_import_pillow()
    for spec, files in sets:
        dest = out_dir / spec.directory.name
        dest.mkdir(parents=True, exist_ok=True)
        for path in files:
            if Image is not None:
                staged = prepare_asc_png(Image, path)
            elif stores_alpha(path):
                # Without Pillow the file is copied as it is, so it must already be RGB.
                fail(
                    f"{spec.directory.name}/{path.name}: App Store screenshots cannot store an alpha "
                    "channel; install Pillow (requirements-store.txt) so the release can upload an RGB "
                    "copy, or re-export it as RGB"
                )
            else:
                staged = path.read_bytes()
            (dest / path.name).write_bytes(staged)
    return out_dir


def staged_screenshot_sets(screenshots_dir: Path) -> list[tuple[str, str, list[Path]]]:
    """(display type, label, PNGs) for every staged set, Watch sets last.

    Fails when an iPhone or iPad set has nothing to upload, when a set is over
    App Store Connect's limit, when a Watch frame is not a Watch screenshot size,
    and when any staged file is not a PNG without alpha. iPhone Duo is optional:
    without staged frames it is left out.
    """
    sets = []
    for spec in ios_metadata.SCREENSHOT_SETS + ios_metadata.OPTIONAL_SCREENSHOT_SETS:
        set_dir = screenshots_dir / spec.directory.name
        pngs = sorted(set_dir.glob("*.png")) if set_dir.is_dir() else []
        if not pngs:
            if spec in ios_metadata.OPTIONAL_SCREENSHOT_SETS:
                continue
            fail(f"no PNG screenshots for the {spec.label} set in {set_dir}")
        for path in pngs:
            staged_png(path, spec.label)
        sets.append((spec.display_type, spec.label, pngs))
    # staged_watch_sets checks each Watch frame while it reads its size.
    sets.extend(staged_watch_sets(screenshots_dir / WATCH_FOLDER))
    for _, label, pngs in sets:
        if len(pngs) > ios_metadata.SCREENSHOTS_PER_SET:
            fail(
                f"too many PNGs ({len(pngs)}) for the ASC {label} set "
                f"(max {ios_metadata.SCREENSHOTS_PER_SET})"
            )
    return sets


def staged_watch_sets(watch_dir: Path) -> list[tuple[str, str, list[Path]]]:
    """The staged Watch frames grouped by the display type their size belongs to, in file name order."""
    groups: dict[str, list[Path]] = {}
    for path in sorted(watch_dir.glob("*.png")) if watch_dir.is_dir() else []:
        size = staged_png(path, "Apple Watch")
        display_type = WATCH_DISPLAY_TYPES.get(size)
        if display_type is None:
            accepted = ", ".join(f"{w}x{h}" for w, h in WATCH_DISPLAY_TYPES)
            fail(
                f"the Apple Watch screenshot {path.name} is {size[0]}x{size[1]}, which is not an "
                f"Apple Watch screenshot size ({accepted})"
            )
        groups.setdefault(display_type, []).append(path)
    return [(display_type, WATCH_LABELS[display_type], pngs) for display_type, pngs in groups.items()]


def live_screenshot_checksums(
    client: AscClient, version_loc_id: str, display_type: str
) -> list[str | None] | None:
    """Checksums of the set's screenshots in display order, or None without the set. Read-only."""
    for item in screenshot_sets_for_localization(client, version_loc_id):
        if (item.get("attributes") or {}).get("screenshotDisplayType") == display_type:
            payload = client.get(f"/appScreenshotSets/{item['id']}/appScreenshots")
            return [(shot.get("attributes") or {}).get("sourceFileChecksum") for shot in payload.get("data") or []]
    return None


def note_sets_left_alone(sets: list[tuple[str, str, list[Path]]]) -> None:
    """Say which optional sets this run leaves alone in App Store Connect (nothing staged for them)."""
    staged = {display_type for display_type, _, _ in sets}
    for spec in ios_metadata.OPTIONAL_SCREENSHOT_SETS:
        if spec.display_type not in staged:
            print(f"  no {spec.label} screenshots staged; the {spec.label} set in App Store Connect is left alone")
    if not staged & set(WATCH_LABELS):
        print("  no Apple Watch screenshots staged; the Watch sets in App Store Connect are left alone")


def require_screenshots_unchanged(
    client: AscClient, *, version_id: str, locale: str, state: str, screenshots_dir: Path
) -> None:
    """Screenshots cannot change in this version state: pass only when App Store
    Connect already shows the screenshots this run would upload (the staged RGB
    copies in screenshots_dir), in order. Read-only. A set nothing was staged
    for (no iPhone Duo or Apple Watch frames) is not compared."""
    sets = staged_screenshot_sets(screenshots_dir)
    note_sets_left_alone(sets)
    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    for display_type, label, pngs in sets:
        wanted = [md5_hex(path.read_bytes()) for path in pngs]
        if live_screenshot_checksums(client, version_loc["id"], display_type) != wanted:
            fail(
                f"{label} screenshots in App Store Connect differ from the repository's (staged as "
                f"RGB) or cannot be compared, but screenshots cannot change while the version is "
                f"{state}; remove the version from review in App Store Connect (or update the "
                "screenshots there), then rerun"
            )
        print(f"  {label} screenshots already match App Store Connect")


def upload_screenshots(client: AscClient, *, version_id: str, locale: str, screenshots_dir: Path) -> None:
    """Replace every staged set (the RGB copies in screenshots_dir), Watch sets last.

    Sets nothing was staged for (no iPhone Duo or Apple Watch frames) are left alone.
    """
    sets = staged_screenshot_sets(screenshots_dir)
    note_sets_left_alone(sets)
    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    for display_type, label, pngs in sets:
        print(f"  {label} screenshots: {len(pngs)} staged as RGB")
        replace_screenshot_set(client, version_loc["id"], display_type, pngs)


def creative_problem(placement_type: str, path: Path, formats: tuple[str, ...]) -> str | None:
    """Why App Store Connect would refuse this image for the slot, or None when it fits."""
    raw = path.read_bytes()
    kind = "png" if raw.startswith(PNG_SIGNATURE) else "jpeg" if raw[:3] == b"\xff\xd8\xff" else None
    if kind is None:
        return "is not a PNG or JPEG file"
    if CREATIVE_EXTENSIONS[path.suffix.lower()] != kind:
        return f"holds {kind.upper()} data but is named {path.suffix}"
    if kind not in formats:
        return f"is a {kind.upper()}; this slot takes {' or '.join(f.upper() for f in formats)}"
    if kind == "png":
        try:
            chunks = png_chunks(raw)
        except ValueError as exc:
            return f"is not a readable PNG ({exc})"
        if png_stores_alpha(chunks):
            return (
                "stores an alpha channel (PNG colour type 4 or 6, or a tRNS chunk), which App Store "
                "Connect refuses; re-export it as RGB"
            )
        width, height = png_size(chunks)
    else:
        size = jpeg_size(raw)
        if size is None:
            return "is not a readable JPEG"
        width, height = size
    if placement_type == HEADER_PLACEMENT:
        if (width, height) != HEADER_SIZE:
            return f"is {width}x{height}; the header must be exactly {HEADER_SIZE[0]}x{HEADER_SIZE[1]}"
        return None
    low, high = SEARCH_RESULTS_WIDTHS
    if not (low <= width <= high and width * 2 == height * 3):
        return (
            f"is {width}x{height}; search results images are 3:2, from {low}x{low * 2 // 3} "
            f"to {high}x{high * 2 // 3}"
        )
    return None


def creative_sources(creative_dir: Path) -> list[tuple[str, str, Path]]:
    """(placement type, label, image) for every creative slot that has an image.

    Checks each image's format, size and alpha first, so a file App Store
    Connect would refuse stops the run before any call. Fails when no slot has
    an image.
    """
    found = []
    for folder, placement_type, label, formats in CREATIVE_SLOTS:
        slot_dir = creative_dir / folder
        images = sorted(
            p
            for p in (slot_dir.iterdir() if slot_dir.is_dir() else [])
            if p.is_file() and p.suffix.lower() in CREATIVE_EXTENSIONS
        )
        if len(images) > 1:
            fail(f"the {label} slot takes one image; {slot_dir} has {len(images)}")
        if not images:
            print(f"  no {label} image in {slot_dir}; that slot is left alone")
            continue
        problem = creative_problem(placement_type, images[0], formats)
        if problem:
            fail(f"the {label} image {images[0].name} {problem}")
        found.append((placement_type, label, images[0]))
    if not found:
        fail(f"creative asset upload is on, but {creative_dir} has no header or search results image")
    return found


def asset_library_id(client: AscClient, app_id: str) -> str:
    library_id = (client.get(f"/apps/{app_id}/assetLibrary").get("data") or {}).get("id")
    if not library_id:
        fail(f"App Store Connect returned no Asset Library for app {app_id}")
    return library_id


def library_images(client: AscClient, library_id: str) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode({"filter[category]": "CREATIVE_ASSETS", "limit": "200"})
    return iter_collection(client, f"/appAssetLibraries/{library_id}/images?{query}")


def library_image_for(images: list[dict[str, Any]], path: Path) -> dict[str, Any] | None:
    """The newest usable library image uploaded from this file (same name and size).

    Reservations an interrupted upload left (AWAITING_UPLOAD), failed and
    archived images do not count, so the file is uploaded again. A file whose
    only upload App Review rejected stops the run.
    """
    size = path.stat().st_size
    same = [
        image
        for image in images
        if attrs(image).get("fileName") == path.name and attrs(image).get("fileSize") == size
    ]
    usable = [image for image in same if attrs(image).get("state") not in CREATIVE_DEAD_STATES]
    rejected = [image for image in same if attrs(image).get("state") == "REJECTED"]
    if not usable and rejected:
        fail(
            f"App Review rejected {path.name}; replace the image (with a new file name) and re-run. "
            f"Details: {json.dumps(attrs(rejected[0]).get('stateDetails'))}"
        )
    return max(usable, key=lambda image: attrs(image).get("createdDate") or "") if usable else None


def wait_for_library_image(client: AscClient, image_id: str, name: str) -> dict[str, Any]:
    """Poll an uploaded image until Apple has processed it; fails on FAILED or timeout."""
    deadline = time.monotonic() + CREATIVE_PROCESSING_TIMEOUT_SECONDS
    while True:
        image = client.get(f"/appAssetLibraryImages/{image_id}").get("data") or {}
        state = attrs(image).get("state") or ""
        asset = attrs(image).get("imageAsset") or {}
        if state == "FAILED":
            fail(
                f"Apple could not process {name} ({image_id}): "
                f"{json.dumps(attrs(image).get('stateDetails'))}"
            )
        if state in CREATIVE_READY_STATES and asset.get("width") and asset.get("height"):
            return image
        if state in CREATIVE_PENDING_STATES or state == "APPROVED":
            return image
        if time.monotonic() >= deadline:
            fail(f"Apple did not finish processing {name} ({image_id}) in time; it is {state or 'in an unknown state'}")
        print(f"  waiting for Apple to process {name} ({state or 'unknown state'})")
        time.sleep(CREATIVE_POLL_SECONDS)


def upload_library_image(client: AscClient, library_id: str, path: Path) -> dict[str, Any]:
    """Reserve, upload and commit one creative image, then wait until Apple has processed it."""
    raw = path.read_bytes()
    reserved = client.post(
        "/appAssetLibraryImages",
        {
            "data": {
                "type": "appAssetLibraryImages",
                "attributes": {
                    "fileName": path.name,
                    "fileSize": len(raw),
                    "category": "CREATIVE_ASSETS",
                },
                "relationships": {
                    "assetLibrary": {"data": {"type": "appAssetLibraries", "id": library_id}}
                },
            }
        },
    )["data"]
    image_id = reserved["id"]
    send_upload_operations(client, reserved, raw, path.name)
    client.patch(
        f"/appAssetLibraryImages/{image_id}",
        {
            "data": {
                "type": "appAssetLibraryImages",
                "id": image_id,
                "attributes": {"uploaded": True},
            }
        },
    )
    print(f"  uploaded {path.name} to the Asset Library ({image_id})")
    return wait_for_library_image(client, image_id, path.name)


class CreativeStep:
    """What one creative slot needs, decided before anything is written.

    action: upload (then review), review, pending (in App Review), place,
    placed, or locked (approved, but the version cannot be edited).
    """

    def __init__(
        self,
        placement_type: str,
        label: str,
        path: Path,
        image: dict[str, Any] | None,
        action: str,
        swap_out: dict[str, Any] | None = None,
    ) -> None:
        self.placement_type = placement_type
        self.label = label
        self.path = path
        self.image = image
        self.action = action
        # The localization's placement in this slot that holds another image.
        self.swap_out = swap_out


class CreativePlan:
    def __init__(self, library_id: str, localization_id: str, steps: list[CreativeStep]) -> None:
        self.library_id = library_id
        self.localization_id = localization_id
        self.steps = steps


def image_placement_ids(client: AscClient, image_id: str) -> set[str]:
    return {
        row["id"]
        for row in iter_collection(client, f"/appAssetLibraryImages/{image_id}/relationships/placements?limit=50")
    }


def slot_placements(client: AscClient, localization_id: str, placement_type: str) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode({"filter[placementType]": placement_type, "limit": "50"})
    rows = iter_collection(client, f"/appStoreVersionLocalizations/{localization_id}/placements?{query}")
    return [row for row in rows if attrs(row).get("placementType", placement_type) == placement_type]


def plan_creative_assets(
    client: AscClient,
    *,
    app_id: str,
    version_id: str,
    locale: str,
    creative_dir: Path,
    editable: bool,
) -> CreativePlan:
    """Read-only: decide what each header / search results image needs.

    Fails before anything is written when an image App Review rejected has not
    been replaced, when a locked version (in a review submission) would need an
    upload or a review submission, or when an image is in a state the release
    cannot resolve.
    """
    sources = creative_sources(creative_dir)
    library_id = asset_library_id(client, app_id)
    images = library_images(client, library_id)
    localization_id = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )["id"]
    steps: list[CreativeStep] = []
    for placement_type, label, path in sources:
        image = library_image_for(images, path)
        state = attrs(image).get("state") or ""
        if image is None or state in CREATIVE_PROCESSING_STATES | CREATIVE_READY_STATES:
            if not editable:
                fail(
                    f"cannot send the {label} image {path.name} to App Review while the version is in "
                    "a review submission; re-run once the version can be edited"
                )
            steps.append(CreativeStep(placement_type, label, path, image, "review" if image else "upload"))
            continue
        if state in CREATIVE_PENDING_STATES:
            print(f"  {label}: {path.name} is {state}; it is placed once App Review approves it")
            steps.append(CreativeStep(placement_type, label, path, image, "pending"))
            continue
        if state != "APPROVED":
            fail(f"the {label} image {path.name} is {state or 'in an unknown state'}; resolve it in App Store Connect")
        # Placed when one of the image's placements is this slot on this localization.
        placed = image_placement_ids(client, image["id"])
        slot = slot_placements(client, localization_id, placement_type)
        if any(row["id"] in placed and attrs(row).get("state") != "FAILED" for row in slot):
            print(f"  {label}: {path.name} is already on the {locale} product page")
            steps.append(CreativeStep(placement_type, label, path, image, "placed"))
            continue
        if not editable:
            # Approval can land between a submit and a re-run; the image is
            # placed by a later run, once the version can be edited.
            print(f"  {label}: {path.name} is approved; it is placed once the version can be edited")
            steps.append(CreativeStep(placement_type, label, path, image, "locked"))
            continue
        if len(slot) > 1:
            fail(
                f"the {locale} {label} slot holds {len(slot)} placements "
                f"({', '.join(row['id'] for row in slot)}); leave one in App Store Connect and re-run"
            )
        steps.append(CreativeStep(placement_type, label, path, image, "place", slot[0] if slot else None))
    return CreativePlan(library_id, localization_id, steps)


def place_creative_image(client: AscClient, localization_id: str, step: CreativeStep) -> None:
    """Put an approved image in its slot; replaces the slot's other image in the same request."""
    assert step.image is not None
    group = attrs(step.swap_out).get("placementGroup") or CREATIVE_PLACEMENT_GROUP
    relationships: dict[str, Any] = {
        "image": {"data": {"type": "appAssetLibraryImages", "id": step.image["id"]}},
        "appStoreVersionLocalization": {
            "data": {"type": "appStoreVersionLocalizations", "id": localization_id}
        },
    }
    if step.swap_out is not None:
        relationships["placementToSwapOut"] = {
            "data": {"type": "appAssetLibraryPlacements", "id": step.swap_out["id"]}
        }
    client.post(
        "/appAssetLibraryPlacements",
        {
            "data": {
                "type": "appAssetLibraryPlacements",
                "attributes": {"placementType": step.placement_type, "placementGroup": group},
                "relationships": relationships,
            }
        },
    )
    replaced = f", replacing placement {step.swap_out['id']}" if step.swap_out else ""
    print(f"  {step.label}: placed {step.path.name} on the product page{replaced}")


def apply_creative_plan(
    client: AscClient, plan: CreativePlan, *, app_id: str, reserved_draft: str | None = None
) -> dict[str, str]:
    """Upload, review and place what plan_creative_assets decided. Returns placement type -> outcome."""
    outcomes: dict[str, str] = {}
    to_review: list[tuple[str, dict[str, Any]]] = []
    for step in plan.steps:
        if step.action == "upload":
            step.image = upload_library_image(client, plan.library_id, step.path)
        elif step.action == "review" and step.image is not None and (
            attrs(step.image).get("state") in CREATIVE_PROCESSING_STATES
        ):
            # An earlier run uploaded it; Apple may still be processing it.
            step.image = wait_for_library_image(client, step.image["id"], step.path.name)
        if step.action in {"upload", "review"}:
            assert step.image is not None
            to_review.append((step.label, step.image))
            outcomes[step.placement_type] = "submitted for review"
    if to_review:
        submit_creative_review(client, app_id, to_review, reserved_draft=reserved_draft)
    for step in plan.steps:
        if step.action == "place":
            place_creative_image(client, plan.localization_id, step)
            outcomes[step.placement_type] = "placed"
        elif step.action in {"pending", "placed", "locked"}:
            outcomes[step.placement_type] = step.action
    return outcomes


def submit_creative_review(
    client: AscClient,
    app_id: str,
    images: list[tuple[str, dict[str, Any]]],
    *,
    reserved_draft: str | None = None,
) -> str:
    """Send not-yet-reviewed creative images to App Review in a submission of their own.

    A draft an earlier attempt left holding only some of these images is
    finished first, else an empty draft, and a new one is created only when
    neither exists, so re-runs never pile up drafts. The draft this run keeps
    for the version and its products (reserved_draft) is never taken. Nothing is
    submitted unless the draft then holds exactly these images.
    """
    wanted = {image["id"] for _, image in images}
    partial = empty = None
    for draft in draft_submission_ids(client, app_id):
        if draft == reserved_draft:
            continue
        items = draft_item_relationships(client, draft)
        # An item that is not an image adds None, which is never wanted.
        held = {related_id(item, "appAssetLibraryImage") for item in items}
        if not items:
            empty = empty or draft
        elif held <= wanted:
            partial = draft
            break
    submission_id = partial or empty
    if submission_id:
        print(f"  finishing review submission {submission_id} from an earlier attempt")
    else:
        submission_id = create_draft_submission(client, app_id)
    held = {related_id(item, "appAssetLibraryImage") for item in draft_item_relationships(client, submission_id)}
    for label, image in images:
        if image["id"] in held:
            print(f"  {label}: {attrs(image).get('fileName')} is already in review submission {submission_id}")
            continue
        client.post(
            "/reviewSubmissionItems",
            {
                "data": {
                    "type": "reviewSubmissionItems",
                    "relationships": {
                        "reviewSubmission": {
                            "data": {"type": "reviewSubmissions", "id": submission_id}
                        },
                        "appAssetLibraryImage": {
                            "data": {"type": "appAssetLibraryImages", "id": image["id"]}
                        },
                    },
                }
            },
        )
        print(f"  {label}: {attrs(image).get('fileName')} added to review submission {submission_id}")
    items = draft_item_relationships(client, submission_id)
    held_ids = [related_id(item, "appAssetLibraryImage") for item in items]
    if len(items) != len(wanted) or set(held_ids) != wanted:
        unexpected = [item.get("id") for item in items if related_id(item, "appAssetLibraryImage") not in wanted]
        fail(
            f"review submission {submission_id} does not hold exactly the header and search results "
            f"images (unexpected items: {unexpected or 'none'}); not submitting it"
        )
    submit_product_draft(client, submission_id, what="creative image")
    return submission_id


def load_catalog() -> dict[str, Any]:
    return json.loads((ROOT / "store" / "catalog" / "products.json").read_text(encoding="utf-8"))


def intended_product_ids(catalog: dict[str, Any] | None = None) -> set[str]:
    """Product IDs the release is allowed to submit, from the canonical catalog."""
    catalog = catalog if catalog is not None else load_catalog()
    ids: set[str] = set()
    for group in ("subscriptions", "non_consumables", "tips"):
        for item in catalog.get(group) or []:
            pid = item.get("id")
            if isinstance(pid, str) and pid:
                ids.add(pid)
    return ids


class ProductSubmitError(Exception):
    """A required product could not be added to App Review."""


class ProductSubmitResult:
    """Outcome of attaching products to a review submission."""

    def __init__(
        self,
        submission_id: str,
        submitted: int,
        added_items: bool,
        has_products: bool = False,
    ) -> None:
        self.submission_id = submission_id
        self.submitted = submitted
        self.added_items = added_items
        # True when the draft holds intended products (attached now or in an
        # earlier run), so a catch-up retry can still submit the draft.
        self.has_products = has_products


# States that mean the product can still be (re)attached to a review.
SUBMITTABLE_PRODUCT_STATES = {
    "READY_TO_SUBMIT",
    "DEVELOPER_REJECTED",
    "READY_FOR_REVIEW",
}


def draft_submission_ids(client: AscClient, app_id: str) -> list[str]:
    """The app's READY_FOR_REVIEW (draft, not yet submitted) review submissions."""
    return [
        row["id"]
        for row in iter_collection(
            client,
            f"/apps/{app_id}/reviewSubmissions?"
            + urllib.parse.urlencode({"filter[state]": "READY_FOR_REVIEW", "limit": "5"}),
        )
    ]


def find_draft_submission(
    client: AscClient, app_id: str, version_id: str | None = None
) -> str | None:
    """The draft to use for the App Store version and its products, if there is one.

    The draft already holding version_id wins; otherwise the first draft holding
    no app version, so another version's draft is never touched. A draft holding
    header or search results images belongs to their own review and is skipped,
    so those images never go out with the version.
    """
    drafts = []
    for submission_id in draft_submission_ids(client, app_id):
        items = draft_item_relationships(client, submission_id)
        if any(related_id(item, "appAssetLibraryImage") for item in items):
            continue
        versions = {related_id(item, "appStoreVersion") for item in items} - {None, ""}
        drafts.append((submission_id, versions))
    if version_id:
        for submission_id, versions in drafts:
            if version_id in versions:
                return submission_id
    for submission_id, versions in drafts:
        if not versions:
            return submission_id
    return None


def create_draft_submission(client: AscClient, app_id: str) -> str:
    submission = client.post(
        "/reviewSubmissions",
        {
            "data": {
                "type": "reviewSubmissions",
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
            }
        },
    )
    return submission["data"]["id"]


def get_or_create_draft_submission(client: AscClient, app_id: str) -> str:
    """The app's draft review submission for versions and products (see find_draft_submission), creating one."""
    return find_draft_submission(client, app_id) or create_draft_submission(client, app_id)


def version_draft(
    client: AscClient, app_id: str, version_id: str, version_state: str
) -> str | None:
    """Read-only: pick and check the draft the version and its products go out in.

    None means there is none yet (one is created at submit). A READY_FOR_REVIEW
    version already sits in a draft; when no usable draft holds it, this stops
    the run instead of submitting another draft. The preflight runs it before
    the build, and the store step again before its first write.
    """
    draft = find_draft_submission(client, app_id, version_id)
    if version_state == READY_FOR_REVIEW and (
        draft is None or version_id not in draft_item_version_ids(client, draft)
    ):
        fail(
            f"App Store version {version_id} is READY_FOR_REVIEW, but no draft review submission "
            "without header or search results images holds it; remove the version from its "
            "submission in App Store Connect and re-run"
        )
    return draft


def reserve_version_draft(
    client: AscClient, app_id: str, version_id: str, version_state: str
) -> str | None:
    """Pick and check the version's draft before anything is written, and reserve it.

    The draft is kept for the version and its products: creative images never take it.
    """
    draft = version_draft(client, app_id, version_id, version_state)
    if draft:
        print(f"  review submission {draft} is kept for the version and its products")
    return draft


def draft_item_relationships(client: AscClient, submission_id: str) -> list[dict[str, Any]]:
    """Return the draft's items with their relationships populated.

    App Store Connect omits `relationships` from Review Submission Items unless
    they are requested with `include`, so every relationship the readers look
    at (versions, products and Asset Library images) is asked for explicitly.
    """
    return iter_collection(
        client,
        f"/reviewSubmissions/{submission_id}/items?limit=50&include={DRAFT_ITEM_INCLUDE}",
    )


def draft_item_version_ids(client: AscClient, submission_id: str) -> set[str]:
    """Return the subscription/app version ids already attached to a draft."""
    ids: set[str] = set()
    for item in draft_item_relationships(client, submission_id):
        relationships = item.get("relationships") or {}
        for name in ("subscriptionVersion", "appStoreVersion", "inAppPurchaseVersion"):
            related = (relationships.get(name) or {}).get("data") or {}
            if related.get("id"):
                ids.add(related["id"])
    return ids


def draft_holds_app_version(client: AscClient, submission_id: str) -> bool:
    """Return True if any item in the draft references an app store version."""
    for item in draft_item_relationships(client, submission_id):
        related = (
            ((item.get("relationships") or {}).get("appStoreVersion") or {}).get("data")
            or {}
        )
        if related.get("id"):
            return True
    return False


def submit_product_draft(client: AscClient, submission_id: str, *, what: str = "product") -> None:
    """Submit a draft review submission.

    Used on the catch-up path when the app version is already under review and a
    late Ready-to-Submit product was attached to a new draft: the draft is
    submitted on its own so the product actually reaches review. Callers must
    ensure the draft holds only products intended for this release.
    """
    client.request(
        "PATCH",
        f"/reviewSubmissions/{submission_id}",
        body={
            "data": {
                "type": "reviewSubmissions",
                "id": submission_id,
                "attributes": {"submitted": True},
            }
        },
    )
    print(f"  submitted {what} review submission {submission_id}")


def current_subscription_version(
    client: AscClient, subscription_id: str
) -> str | None:
    """Return the subscription's current in-flight version id, if any.

    App Store Connect allows only one in-flight version per subscription, so the
    highest-numbered version in a submittable state is the one to attach.
    """
    versions = iter_collection(client, f"/subscriptions/{subscription_id}/versions")
    candidates = [v for v in versions if (v.get("attributes") or {}).get("version")]
    if not candidates:
        return versions[0]["id"] if versions else None
    return max(candidates, key=lambda v: v["attributes"]["version"])["id"]


def product_failure(failures: list[str]) -> ProductSubmitError:
    return ProductSubmitError(
        "these required products were not added to App Review: "
        + "; ".join(failures)
        + ". Resolve them in App Store Connect and rerun the release."
    )


def attach_subscriptions(
    client: AscClient, app_id: str, *, intended_ids: set[str], submission_id: str
) -> ProductSubmitResult:
    """Attach Ready-to-Submit catalog subscriptions to the draft review submission.

    As of the 2026 App Store Connect workflow, subscriptions are added to the
    app's review submission through the `subscriptionVersion` relationship on
    Review Submission Items — not the `subscription` relationship, which the API
    rejects. Attaching the current in-flight version also clears a
    `DEVELOPER_REJECTED` state left by a prior submission (for example when a
    plan was removed from an earlier review). Attaching submits nothing: the items
    go to review only when the draft is submitted.
    """
    # Idempotency: a retry reuses the same draft, so skip anything already
    # attached instead of posting a duplicate that App Store Connect rejects.
    already_attached = draft_item_version_ids(client, submission_id)

    attached = 0
    added_items = False
    has_products = False
    failures: list[str] = []

    for group in iter_collection(
        client, f"/apps/{app_id}/subscriptionGroups?limit=200"
    ):
        for sub in iter_collection(
            client, f"/subscriptionGroups/{group['id']}/subscriptions?limit=200"
        ):
            attrs = sub.get("attributes") or {}
            pid = attrs.get("productId") or ""
            if pid not in intended_ids:
                continue
            if attrs.get("state") not in SUBMITTABLE_PRODUCT_STATES:
                continue
            name = attrs.get("name") or sub["id"]
            version_id = current_subscription_version(client, sub["id"])
            if not version_id:
                failures.append(f"subscription {name} ({pid}): no version to submit")
                continue
            if version_id in already_attached:
                attached += 1
                has_products = True
                print(f"  subscription {name}: already attached (idempotent)")
                continue
            try:
                client.post(
                    "/reviewSubmissionItems",
                    {
                        "data": {
                            "type": "reviewSubmissionItems",
                            "relationships": {
                                "reviewSubmission": {
                                    "data": {
                                        "type": "reviewSubmissions",
                                        "id": submission_id,
                                    }
                                },
                                "subscriptionVersion": {
                                    "data": {
                                        "type": "subscriptionVersions",
                                        "id": version_id,
                                    }
                                },
                            },
                        }
                    },
                )
            except SystemExit:
                failures.append(f"subscription {name} ({pid})")
                continue
            attached += 1
            added_items = True
            has_products = True
            print(f"  subscription {name}: attached to review submission")

    if failures:
        raise product_failure(failures)
    return ProductSubmitResult(submission_id, attached, added_items, has_products)


def in_app_purchases_to_submit(
    client: AscClient, app_id: str, *, intended_ids: set[str]
) -> list[dict[str, Any]]:
    """Ready-to-Submit catalog in-app purchases (lifetime, tips). Read-only."""
    rows = []
    for iap in iter_collection(client, f"/apps/{app_id}/inAppPurchasesV2?limit=200"):
        attrs = iap.get("attributes") or {}
        if (attrs.get("productId") or "") in intended_ids and attrs.get("state") == "READY_TO_SUBMIT":
            rows.append(iap)
    return rows


def submit_listed_in_app_purchases(client: AscClient, iaps: list[dict[str, Any]]) -> int:
    """Send in-app purchases to App Review.

    `inAppPurchaseSubmissions` submits each purchase immediately, on its own, so
    callers do this only after the final build and selection recheck. Apple marks the
    endpoint deprecated in favour of review submission items; it still works, and
    today every catalog purchase is approved, so nothing is posted.
    """
    submitted = 0
    failures: list[str] = []
    for iap in iaps:
        attrs = iap.get("attributes") or {}
        pid = attrs.get("productId") or ""
        name = attrs.get("name") or iap["id"]
        try:
            client.post(
                "/inAppPurchaseSubmissions",
                {
                    "data": {
                        "type": "inAppPurchaseSubmissions",
                        "relationships": {
                            "inAppPurchaseV2": {
                                "data": {"type": "inAppPurchases", "id": iap["id"]}
                            }
                        },
                    }
                },
            )
        except SystemExit:
            failures.append(f"in-app purchase {name} ({pid})")
            continue
        submitted += 1
        print(f"  in-app purchase {name}: submitted for review")
    if failures:
        raise product_failure(failures)
    return submitted


def submit_in_app_purchases(
    client: AscClient,
    app_id: str,
    *,
    intended_ids: set[str] | None = None,
    submission_id: str | None = None,
) -> ProductSubmitResult:
    """Attach Ready-to-Submit catalog subscriptions, then submit catalog in-app purchases.

    For callers that have already confirmed this run's build is the one under review
    (the WAITING_FOR_REVIEW catch-up). A new submission instead attaches
    subscriptions, rechecks the build, and only then submits purchases (main()).

    Only products listed in `store/catalog/products.json` are considered, so an
    unrelated Ready-to-Submit plan or tip is not dragged into this release.
    """
    if intended_ids is None:
        intended_ids = intended_product_ids()

    if not intended_ids:
        print("  catalog has no products to submit")
        return ProductSubmitResult(submission_id or "", 0, False)

    if submission_id is None:
        submission_id = get_or_create_draft_submission(client, app_id)

    result = attach_subscriptions(
        client, app_id, intended_ids=intended_ids, submission_id=submission_id
    )
    result.submitted += submit_listed_in_app_purchases(
        client, in_app_purchases_to_submit(client, app_id, intended_ids=intended_ids)
    )
    print(f"  in-app purchases / subscriptions submitted: {result.submitted}")
    return result


def submit_for_review(
    client: AscClient,
    app_id: str,
    version_id: str,
    version_state: str = "",
    *,
    submission_id: str | None = None,
) -> None:
    # Reuse a draft submission when one already exists (an earlier attempt can
    # leave a READY_FOR_REVIEW submission that was never submitted).
    if submission_id is None:
        submission_id = get_or_create_draft_submission(client, app_id)

    items_versions = draft_item_version_ids(client, submission_id)
    # READY_FOR_REVIEW means the version is already held by this draft submission.
    already_added = version_state == "READY_FOR_REVIEW" or version_id in items_versions
    if not already_added:
        client.post(
            "/reviewSubmissionItems",
            {
                "data": {
                    "type": "reviewSubmissionItems",
                    "relationships": {
                        "reviewSubmission": {
                            "data": {"type": "reviewSubmissions", "id": submission_id}
                        },
                        "appStoreVersion": {
                            "data": {"type": "appStoreVersions", "id": version_id}
                        },
                    },
                }
            },
        )

    # Submitting is a PATCH with submitted=true; there is no /submit action.
    client.request(
        "PATCH",
        f"/reviewSubmissions/{submission_id}",
        body={
            "data": {
                "type": "reviewSubmissions",
                "id": submission_id,
                "attributes": {"submitted": True},
            }
        },
    )
    print(
        f"  submitted App Store version {version_id} for review "
        f"(submission {submission_id})"
    )


RELEASE_TYPE_PUBLIC = "AFTER_APPROVAL"
RELEASE_TYPE_MANUAL = "MANUAL"


def verify_build(
    client: AscClient, build_id: str, version_string: str, build_number: str
) -> dict[str, Any]:
    """Re-read the build and refuse anything but this run's processed build."""
    payload = client.get(
        f"/builds/{build_id}?"
        + urllib.parse.urlencode(
            {
                "include": "preReleaseVersion",
                "fields[builds]": "version,processingState,expired,usesNonExemptEncryption,preReleaseVersion",
                "fields[preReleaseVersions]": "version,platform",
            }
        )
    )
    data = payload.get("data") or {}
    attrs = data.get("attributes") or {}
    pre = next(
        (row for row in payload.get("included") or [] if row.get("type") == "preReleaseVersions"),
        {},
    )
    pre_attrs = pre.get("attributes") or {}
    problems = []
    if data.get("id") != build_id:
        problems.append(f"id {data.get('id')!r}")
    if attrs.get("version") != build_number:
        problems.append(f"build number {attrs.get('version')!r}")
    if attrs.get("processingState") != "VALID":
        problems.append(f"processing state {attrs.get('processingState')!r}")
    if attrs.get("expired"):
        problems.append("expired")
    if pre_attrs.get("version") != version_string or pre_attrs.get("platform") != "IOS":
        problems.append(f"version {pre_attrs.get('platform')} {pre_attrs.get('version')!r}")
    if problems:
        fail(
            f"build {build_id} is not {version_string} ({build_number}), VALID and unexpired: "
            + ", ".join(problems)
        )
    return attrs


def attached_build_id(client: AscClient, version_id: str) -> str | None:
    payload = client.get(f"/appStoreVersions/{version_id}/build")
    return (payload.get("data") or {}).get("id")


def confirm_selected_build(client: AscClient, version_id: str, build_id: str) -> None:
    current = attached_build_id(client, version_id)
    if current != build_id:
        fail(
            f"App Store version {version_id} has build {current or 'none'} selected, "
            f"expected {build_id}; not submitting"
        )


def select_build(
    client: AscClient,
    version_id: str,
    build_id: str,
    version_string: str,
    build_number: str,
) -> None:
    """Explicitly select this run's build for the version, then read it back."""
    verify_build(client, build_id, version_string, build_number)
    client.request(
        "PATCH",
        f"/appStoreVersions/{version_id}/relationships/build",
        body={"data": {"type": "builds", "id": build_id}},
    )
    confirm_selected_build(client, version_id, build_id)
    print(f"  selected build {build_number} ({build_id}) for version {version_string}")


def ensure_export_compliance(
    client: AscClient, build_id: str, version_string: str, build_number: str, uses_encryption: Any
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
    current = verify_build(client, build_id, version_string, build_number).get("usesNonExemptEncryption")
    if current is None:
        client.patch(
            f"/builds/{build_id}",
            {"data": {"type": "builds", "id": build_id,
                      "attributes": {"usesNonExemptEncryption": uses_encryption}}},
        )
        current = verify_build(client, build_id, version_string, build_number).get("usesNonExemptEncryption")
        print(f"  answered export compliance: usesNonExemptEncryption={str(uses_encryption).lower()}")
    if current != uses_encryption:
        fail(
            f"build {build_number} declares usesNonExemptEncryption={current}, but the catalog says "
            f"{uses_encryption}; not submitting"
        )


def set_release_type(client: AscClient, version_id: str, *, public_release: bool) -> None:
    release_type = RELEASE_TYPE_PUBLIC if public_release else RELEASE_TYPE_MANUAL
    client.patch(
        f"/appStoreVersions/{version_id}",
        {
            "data": {
                "type": "appStoreVersions",
                "id": version_id,
                "attributes": {"releaseType": release_type},
            }
        },
    )
    print(
        "  release type: "
        + ("release automatically after approval" if public_release else "manual release after approval")
    )


class _RecordingClient:
    """Offline AscClient stand-in for the dry-run self-check."""

    def __init__(self, collections: dict[str, list[dict[str, Any]]]) -> None:
        self._collections = collections
        self.posts: list[tuple[str, dict[str, Any]]] = []
        self.requests: list[tuple[str, str, dict[str, Any] | None]] = []

    def get(self, path: str) -> dict[str, Any]:
        return {"data": self._collections.get(path, [])}

    def post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        self.posts.append((path, body))
        return {"data": {"id": "fake"}}

    def patch(self, path: str, body: dict[str, Any]) -> dict[str, Any]:  # pragma: no cover
        raise AssertionError(f"unexpected PATCH {path}")

    def request(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        self.requests.append((method, path, body))
        return {"data": {"id": "fake"}}


def dry_run_self_check() -> None:
    """Exercise the product-submission path offline so dry-run covers it."""
    app_id = "APP"
    groups_path = f"/apps/{app_id}/subscriptionGroups?limit=200"
    subs_path = "/subscriptionGroups/G1/subscriptions?limit=200"
    submission_path = (
        f"/apps/{app_id}/reviewSubmissions?"
        + urllib.parse.urlencode({"filter[state]": "READY_FOR_REVIEW", "limit": "5"})
    )
    subscriptions = {
        "monthly": "com.apoorvdarshan.verceltics.monthly",
        "yearly": "com.apoorvdarshan.verceltics.yearly",
    }
    collections: dict[str, list[dict[str, Any]]] = {
        groups_path: [{"id": "G1"}],
        subs_path: [
            {
                "id": f"S{i}",
                "attributes": {
                    "name": name,
                    "productId": pid,
                    # One plan is still DEVELOPER_REJECTED from a prior submit.
                    "state": "DEVELOPER_REJECTED" if i == 0 else "READY_TO_SUBMIT",
                },
            }
            for i, (name, pid) in enumerate(subscriptions.items())
        ]
        + [
            {
                "id": "SX",
                "attributes": {
                    "name": "Legacy",
                    "productId": "com.apoorvdarshan.verceltics.weekly",
                    "state": "READY_TO_SUBMIT",
                },
            }
        ],
        submission_path: [{"id": "SUB1"}],
        f"/apps/{app_id}/inAppPurchasesV2?limit=200": [
            {
                "id": "IAP1",
                "attributes": {
                    "name": "Lifetime",
                    "productId": "com.apoorvdarshan.verceltics.lifetime",
                    "state": "READY_TO_SUBMIT",
                },
            },
            {
                "id": "IAP2",
                "attributes": {
                    "name": "Coffee",
                    "productId": "com.apoorvdarshan.verceltics.tip.coffee",
                    # Already approved: must not be resubmitted.
                    "state": "APPROVED",
                },
            },
        ],
    }
    for i in range(len(subscriptions)):
        # Two versions returned out of order: the higher-numbered current
        # version must win regardless of response ordering.
        collections[f"/subscriptions/S{i}/versions"] = [
            {"id": f"V{i}b", "attributes": {"version": 2, "state": "READY_FOR_REVIEW"}},
            {"id": f"V{i}", "attributes": {"version": 1, "state": "DEVELOPER_REJECTED"}},
        ]
    intended = intended_product_ids()
    fake = _RecordingClient(collections)
    result = submit_in_app_purchases(fake, app_id, intended_ids=intended)
    assert result.submitted == 3, f"expected 3 products, got {result.submitted}"
    assert result.added_items, "expected added_items=True"
    assert result.submission_id == "SUB1", f"wrong submission: {result.submission_id}"
    item_posts = [body for path, body in fake.posts if path == "/reviewSubmissionItems"]
    attached_versions = {
        body["data"]["relationships"]["subscriptionVersion"]["data"]["id"] for body in item_posts
    }
    assert attached_versions == {"V0b", "V1b"}, (
        f"expected the current (v2) version of each subscription, got {attached_versions}"
    )
    iap_posts = [body for path, body in fake.posts if path == "/inAppPurchaseSubmissions"]
    assert [body["data"]["relationships"]["inAppPurchaseV2"]["data"]["id"] for body in iap_posts] == ["IAP1"], (
        "only the Ready-to-Submit catalog purchase may be submitted"
    )

    # Retry: the same draft already holds one subscription version, so the rerun
    # must not post it again (App Store Connect rejects duplicates).
    items_path = f"/reviewSubmissions/SUB1/items?limit=50&include={DRAFT_ITEM_INCLUDE}"
    collections[items_path] = [
        {
            "id": "ITEM0",
            "relationships": {
                "subscriptionVersion": {"data": {"type": "subscriptionVersions", "id": "V0b"}},
                "appStoreVersion": {"data": None},
            },
        }
    ]
    retry = _RecordingClient(collections)
    result2 = submit_in_app_purchases(retry, app_id, intended_ids=intended)
    assert result2.has_products, "retry must report the draft holds products"
    retry_versions = {
        body["data"]["relationships"]["subscriptionVersion"]["data"]["id"]
        for path, body in retry.posts
        if path == "/reviewSubmissionItems"
    }
    assert retry_versions == {"V1b"}, f"retry attached the wrong versions: {retry_versions}"
    assert draft_holds_app_version(retry, "SUB1") is False, "false app-version detection"

    # submit_for_review must not re-add an app version already in the draft.
    review = _RecordingClient(
        {
            **collections,
            items_path: [
                {
                    "id": "ITEMV",
                    "relationships": {
                        "appStoreVersion": {"data": {"type": "appStoreVersions", "id": "AV1"}},
                        "subscriptionVersion": {"data": None},
                    },
                }
            ],
        }
    )
    assert draft_holds_app_version(review, "SUB1") is True, "app-version items must be detected"
    submit_for_review(review, app_id, "AV1", "PREPARE_FOR_SUBMISSION", submission_id="SUB1")
    assert not [path for path, _ in review.posts if path == "/reviewSubmissionItems"], (
        "app version was posted again on retry"
    )
    assert any(
        method == "PATCH" and path == "/reviewSubmissions/SUB1" and body is not None
        and body["data"]["attributes"]["submitted"] is True
        for method, path, body in review.requests
    ), "submission was not PATCHed to submitted=true"
    print("  dry-run self-check: product submission selection OK")


def validate_local_inputs(
    *,
    metadata_dir: Path,
    upload_listing: bool,
    upload_whats_new: bool,
    upload_screenshots: bool,
    screenshots_dir: Path,
    upload_creative: bool = False,
    creative_dir: Path | None = None,
) -> None:
    """Check the local files of the enabled steps only, so an unused listing never
    blocks What's New, screenshots or review (the release prepares it only with
    IOS_UPLOAD_LISTING on).

    With screenshots on, the RGB copies this run uploads are staged into
    screenshots_dir and checked as they will be sent; with creative assets on,
    the header and search results images' formats, sizes and alpha."""
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
        staged_screenshot_sets(stage_screenshots(screenshots_dir))
    if upload_creative:
        creative_sources(creative_dir or DEFAULT_CREATIVE_DIR)


def version_key(version_string: str) -> tuple[int, ...] | None:
    """2.2.1 -> (2, 2, 1), padded to three parts; None when it is not numeric."""
    if not re.fullmatch(r"[0-9]+(\.[0-9]+){0,2}", version_string or ""):
        return None
    parts = [int(part) for part in version_string.split(".")]
    return tuple(parts + [0] * (3 - len(parts)))


def ensure_ios_app_store_version(
    client: AscClient, *, bundle_id: str, expected_app_id: str, version_string: str
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
    app_id = find_app(client, bundle_id)
    if app_id != expected_app_id:
        fail(f"{bundle_id} resolved to App Store Connect app {app_id}, expected {expected_app_id}")
    versions = list_ios_app_store_versions(client, app_id)
    existing = [
        row for row in versions if (row.get("attributes") or {}).get("versionString") == version_string
    ]
    if len(existing) > 1:
        fail(f"ambiguous App Store versions for {version_string!r} on IOS — got {len(existing)} records")
    if existing:
        state = (existing[0].get("attributes") or {}).get("appStoreState") or ""
        print(f"App Store version {version_string} already exists ({state}); nothing to create")
        return
    problems: list[str] = []
    for row in versions:
        attrs = row.get("attributes") or {}
        other = attrs.get("versionString") or ""
        state = attrs.get("appStoreState") or ""
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
    client.post(
        "/appStoreVersions",
        {
            "data": {
                "type": "appStoreVersions",
                "attributes": {"platform": "IOS", "versionString": version_string},
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
            }
        },
    )
    created = [
        row
        for row in list_ios_app_store_versions(client, app_id)
        if (row.get("attributes") or {}).get("versionString") == version_string
    ]
    if len(created) != 1:
        fail(
            f"created App Store version {version_string}, but reading it back found "
            f"{len(created)} records; check it in App Store Connect before rerunning"
        )
    state = (created[0].get("attributes") or {}).get("appStoreState") or ""
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
    client: AscClient,
    *,
    bundle_id: str,
    version_string: str,
    metadata_dir: Path,
    do_listing: bool,
    do_whats_new: bool,
    do_screenshots: bool,
    do_submit: bool,
    screenshots_dir: Path,
    do_creative: bool = False,
    creative_dir: Path | None = None,
) -> None:
    """Read-only checks before anything is built or uploaded (GET requests only).

    The version must exist (IOS_CREATE_VERSION's step, which runs before this,
    creates a missing one) in a state that allows the enabled steps, and every
    locale they touch must have its localization. With review submission on, a
    READY_FOR_REVIEW version's draft is checked too. With creative assets on,
    their Asset Library state is checked the way the store step plans it, so an
    image the version cannot take now (rejected, or needing upload or review
    while the version sits in a review submission) stops the run before the
    build instead of after it. The live store step repeats all of this, because
    App Store Connect can change in between.
    """
    app_id = find_app(client, bundle_id)
    version = get_ios_app_store_version(client, app_id, version_string)
    version_id = version["id"]
    state = (version.get("attributes") or {}).get("appStoreState") or ""
    print(f"ASC preflight: app={app_id} version={version_string} id={version_id} state={state}")
    problem = version_state_problem(
        version_string,
        state,
        edits_metadata=do_listing or do_whats_new or do_screenshots or do_creative,
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
    if do_submit and state == READY_FOR_REVIEW:
        # The store step submits the draft holding this version; finding out
        # after the build and upload that there is none would be too late.
        version_draft(client, app_id, version_id, state)
    if do_listing or do_whats_new:
        upload_listing(
            client,
            version_id=version_id,
            metadata_dir=metadata_dir,
            include_listing=do_listing,
            include_whats_new=do_whats_new,
            frozen_state=frozen,
            write=False,
        )
    if do_listing:
        upload_app_info(client, app_id=app_id, metadata_dir=metadata_dir, frozen_state=frozen, write=False)
    if do_screenshots:
        if state in {READY_FOR_REVIEW, WAITING_FOR_REVIEW}:
            require_screenshots_unchanged(
                client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, state=state,
                screenshots_dir=screenshots_dir,
            )
        else:
            localization_for_locale(
                client,
                f"/appStoreVersions/{version_id}/appStoreVersionLocalizations",
                ios_metadata.PRIMARY_LOCALE,
            )
    if do_creative:
        plan_creative_assets(
            client,
            app_id=app_id,
            version_id=version_id,
            locale=ios_metadata.PRIMARY_LOCALE,
            creative_dir=creative_dir or DEFAULT_CREATIVE_DIR,
            editable=state in EDITABLE_VERSION_STATES,
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
        default=DEFAULT_CREATIVE_DIR,
        help="Product page header and search results images, one folder per slot: "
        + ", ".join(folder for folder, _, _, _ in CREATIVE_SLOTS),
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
        help="Validate flags and local files only — no ASC HTTP calls",
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
            client_from_environment(),
            bundle_id=args.bundle_id,
            expected_app_id=DEFAULT_APP_ID,
            version_string=args.version,
        )
        return

    # Screenshots go up as RGB copies staged here; the repository's files are only
    # read. The folder is removed when the run ends, whatever its outcome.
    with tempfile.TemporaryDirectory(prefix="app-store-screenshots-") as staging:
        run_release(args, screenshots_dir=Path(staging))


def run_release(args: argparse.Namespace, *, screenshots_dir: Path) -> None:
    """Everything but --create-version: dry run, preflight, or the store step."""
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
        screenshots_dir=screenshots_dir,
        upload_creative=do_creative,
        creative_dir=args.creative_dir,
    )
    catalog = load_catalog()

    if args.dry_run:
        print("ASC dry-run — local inputs OK; no API calls")
        print(
            f"  bundle={args.bundle_id} listing={do_listing} whats_new={do_whats_new} "
            f"screenshots={do_screenshots} creative={do_creative} submit={do_submit} "
            f"public_release={do_public}"
        )
        if do_submit:
            dry_run_self_check()
        return

    if not args.version:
        fail("--version is required for live ASC calls (pass marketing version from tag)")
    client = client_from_environment()

    if args.preflight:
        preflight(
            client,
            bundle_id=args.bundle_id,
            version_string=args.version,
            metadata_dir=args.metadata_dir,
            do_listing=do_listing,
            do_whats_new=do_whats_new,
            do_screenshots=do_screenshots,
            do_submit=do_submit,
            screenshots_dir=screenshots_dir,
            do_creative=do_creative,
            creative_dir=args.creative_dir,
        )
        return

    app_id = find_app(client, args.bundle_id)
    version = get_ios_app_store_version(client, app_id, args.version)
    version_id = version["id"]
    state = (version.get("attributes") or {}).get("appStoreState") or ""
    print(
        f"ASC app={app_id} version={args.version} id={version_id} state={state}"
    )
    edits_metadata = do_listing or do_whats_new or do_screenshots or do_creative
    problem = version_state_problem(args.version, state, edits_metadata=edits_metadata, submit=do_submit)
    if problem:
        fail(problem)
    # A rerun after a successful submit changes nothing: it only confirms that App
    # Store Connect already holds this run's build and the repository's metadata.
    frozen = state if state == WAITING_FOR_REVIEW else None
    if frozen and do_submit:
        confirm_selected_build(client, version_id, args.build_id)
    # In a review submission (READY_FOR_REVIEW, WAITING_FOR_REVIEW) screenshots and
    # creative placements are locked.
    locked = state in {READY_FOR_REVIEW, WAITING_FOR_REVIEW}

    # Pick and check the version's draft before any write. It is reserved for
    # the version and its products, so header and search results images never
    # go out in it.
    reserved_draft: str | None = None
    if do_submit and not frozen:
        reserved_draft = reserve_version_draft(client, app_id, version_id, state)

    # Read-only, so an image the version cannot take stops the run before any write.
    creative_plan: CreativePlan | None = None
    if do_creative:
        print("checking ASC product page header and search results images…")
        creative_plan = plan_creative_assets(
            client,
            app_id=app_id,
            version_id=version_id,
            locale=ios_metadata.PRIMARY_LOCALE,
            creative_dir=args.creative_dir,
            editable=not locked,
        )

    if do_listing or do_whats_new:
        parts = [name for name, on in (("listing", do_listing), ("What's New", do_whats_new)) if on]
        print(f"{'checking' if frozen else 'uploading'} ASC {' + '.join(parts)}…")
        upload_listing(
            client,
            version_id=version_id,
            metadata_dir=args.metadata_dir,
            include_listing=do_listing,
            include_whats_new=do_whats_new,
            frozen_state=frozen,
        )
        if do_listing:
            upload_app_info(client, app_id=app_id, metadata_dir=args.metadata_dir, frozen_state=frozen)

    if do_screenshots:
        if locked:
            print("checking ASC screenshots (they cannot change in this state)…")
            require_screenshots_unchanged(
                client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, state=state,
                screenshots_dir=screenshots_dir,
            )
        else:
            print("uploading ASC screenshots…")
            upload_screenshots(
                client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE,
                screenshots_dir=screenshots_dir,
            )

    if creative_plan is not None:
        print("syncing ASC product page header and search results images…")
        apply_creative_plan(client, creative_plan, app_id=app_id, reserved_draft=reserved_draft)

    if frozen:
        if do_submit:
            print("ASC submit skipped — version already WAITING_FOR_REVIEW with this run's build (idempotent)")
            try:
                result = submit_in_app_purchases(client, app_id, intended_ids=intended_product_ids(catalog))
            except ProductSubmitError as exc:
                fail(str(exc))
            # Attaching a product to a fresh draft does not submit it. If the
            # version is already under review, the draft holding the late
            # products must be submitted on its own or they never reach review.
            # `has_products` also covers a retry where an earlier run attached
            # the products but failed to submit their draft. Only submit a
            # product-only draft: refuse if it holds an app version (e.g.
            # another release's) so unrelated items never ship.
            if result.has_products and result.submission_id:
                if draft_holds_app_version(client, result.submission_id):
                    fail(
                        "refusing to submit a draft that contains an app "
                        "version; remove it in App Store Connect and rerun"
                    )
                submit_product_draft(client, result.submission_id)
        print("ASC release step finished")
        return

    if do_submit:
        print("selecting this run's build…")
        select_build(client, version_id, args.build_id, args.version, args.build_number)
        ensure_export_compliance(
            client, args.build_id, args.version, args.build_number,
            catalog.get("uses_non_exempt_encryption"),
        )
        set_release_type(client, version_id, public_release=do_public)
        intended = intended_product_ids(catalog)
        print("attaching subscriptions to the review submission…")
        submission_id = reserved_draft or create_draft_submission(client, app_id)
        try:
            # Attaching submits nothing; purchases are only listed here.
            subscriptions = attach_subscriptions(
                client, app_id, intended_ids=intended, submission_id=submission_id
            )
            purchases = in_app_purchases_to_submit(client, app_id, intended_ids=intended)
        except ProductSubmitError as exc:
            # A first-time subscription must ship with the version, so a failed
            # required product blocks the whole submission rather than letting
            # the version enter review without it.
            fail(f"{exc} Not submitting the app version.")
        # Recheck immediately before anything goes to App Review: the selection
        # must still be this run's processed build.
        verify_build(client, args.build_id, args.version, args.build_number)
        confirm_selected_build(client, version_id, args.build_id)
        if purchases:
            print("submitting in-app purchases for review…")
        try:
            submitted_purchases = submit_listed_in_app_purchases(client, purchases)
        except ProductSubmitError as exc:
            fail(f"{exc} Not submitting the app version.")
        print("submitting for App Store review…")
        submit_for_review(
            client, app_id, version_id, state, submission_id=submission_id
        )
        print(f"  {subscriptions.submitted + submitted_purchases} product(s) went out with the version")

    print("ASC release step finished")


if __name__ == "__main__":
    main()
