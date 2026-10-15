#!/usr/bin/env python3
"""App Store Connect listing, What's New, screenshots, build selection and review.

Run by ios-release.yml with the resolved iOS switches as environment flags:
UPLOAD_LISTING, UPLOAD_WHATS_NEW, UPLOAD_SCREENSHOTS, SUBMIT_IOS_REVIEW and
PUBLIC_RELEASE. Every flag that is off skips its App Store Connect work, and its
local files are not checked: listing text only with UPLOAD_LISTING, What's New
only with UPLOAD_WHATS_NEW, screenshots only with UPLOAD_SCREENSHOTS.

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
                                         screenshots only when already identical
  WAITING_FOR_REVIEW (submitted)         nothing changes: a rerun passes only when
                                         App Store Connect already matches the
                                         repository and holds this run's build
  anything else                          the run fails

`--create-version` (IOS_CREATE_VERSION, CREATE_IOS_VERSION here) runs first on a
release run: it adds the App Store version for the tag when App Store Connect does
not have it yet, and leaves an existing one alone. It refuses when another
version is still on its way to the store or the tag's version is not newer than
every existing one.

`--dry-run` checks the switches and local files only (no network).
`--preflight` runs the same version, localization and state checks with GET
requests only, before anything is built or uploaded (ios-release.yml prepare).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ios_metadata  # noqa: E402
from ios_metadata import APP_INFO_FIELDS, VERSION_FIELDS, WHATS_NEW  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ASC_BASE = "https://api.appstoreconnect.apple.com/v1"
DEFAULT_BUNDLE_ID = "com.apoorvdarshan.verceltics"
# Verceltics' App Store Connect app (apps.apple.com/app/id6761645656). A version is
# only ever created for this app, never for whatever else the bundle id resolves to.
DEFAULT_APP_ID = "6761645656"
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
    upload_ops = (shot.get("attributes") or {}).get("uploadOperations") or []
    if not upload_ops:
        fail(f"ASC returned no uploadOperations for {image_path.name}")
    for op in upload_ops:
        offset = int(op.get("offset", 0))
        length = int(op["length"])
        chunk = raw[offset : offset + length]
        headers = {h["name"]: h["value"] for h in op.get("requestHeaders") or []}
        method = (op.get("method") or "PUT").upper()
        client.upload_bytes(op["url"], chunk, headers, method=method)
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


def screenshot_pngs(spec: ios_metadata.ScreenshotSet) -> list[Path]:
    try:
        return ios_metadata.screenshot_files(spec)
    except ios_metadata.MetadataError as exc:
        fail(str(exc))


def live_screenshot_checksums(
    client: AscClient, version_loc_id: str, display_type: str
) -> list[str | None] | None:
    """Checksums of the set's screenshots in display order, or None without the set. Read-only."""
    for item in screenshot_sets_for_localization(client, version_loc_id):
        if (item.get("attributes") or {}).get("screenshotDisplayType") == display_type:
            payload = client.get(f"/appScreenshotSets/{item['id']}/appScreenshots")
            return [(shot.get("attributes") or {}).get("sourceFileChecksum") for shot in payload.get("data") or []]
    return None


def require_screenshots_unchanged(client: AscClient, *, version_id: str, locale: str, state: str) -> None:
    """Screenshots cannot change in this version state: pass only when App Store
    Connect already shows the repository's screenshots, in order. Read-only."""
    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    for spec in ios_metadata.SCREENSHOT_SETS:
        wanted = [md5_hex(path.read_bytes()) for path in screenshot_pngs(spec)]
        if live_screenshot_checksums(client, version_loc["id"], spec.display_type) != wanted:
            fail(
                f"{spec.label} screenshots in App Store Connect differ from "
                f"{shown_path(spec.directory)} (or cannot be compared), but screenshots cannot "
                f"change while the version is {state}; remove the version from review in App Store "
                "Connect (or update the screenshots there), then rerun"
            )
        print(f"  {spec.label} screenshots already match App Store Connect")


def upload_screenshots(client: AscClient, *, version_id: str, locale: str) -> None:
    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    for spec in ios_metadata.SCREENSHOT_SETS:
        print(f"  {spec.label} screenshots from {shown_path(spec.directory)}")
        replace_screenshot_set(client, version_loc["id"], spec.display_type, screenshot_pngs(spec))


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


def get_or_create_draft_submission(client: AscClient, app_id: str) -> str:
    """Return the app's READY_FOR_REVIEW draft review submission, creating one."""
    existing = client.get(
        f"/apps/{app_id}/reviewSubmissions?"
        + urllib.parse.urlencode({"filter[state]": "READY_FOR_REVIEW", "limit": "5"})
    )
    for row in existing.get("data") or []:
        return row["id"]
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


def draft_item_relationships(client: AscClient, submission_id: str) -> list[dict[str, Any]]:
    """Return the draft's items with their version relationships populated.

    App Store Connect omits `relationships` from Review Submission Items unless
    they are requested with `include`, so both relationship readers must ask for
    the version relationships explicitly.
    """
    return iter_collection(
        client,
        f"/reviewSubmissions/{submission_id}/items"
        "?limit=50&include=subscriptionVersion,appStoreVersion",
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


def submit_product_draft(client: AscClient, submission_id: str) -> None:
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
    print(f"  submitted product review submission {submission_id}")


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

    The app's Info.plist does not declare ITSAppUsesNonExemptEncryption, so each
    uploaded build starts unanswered. Verceltics uses only Apple's HTTPS, Keychain
    and CryptoKit (store/catalog/products.json: uses_non_exempt_encryption).
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
    items_path = "/reviewSubmissions/SUB1/items?limit=50&include=subscriptionVersion,appStoreVersion"
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
    created = get_ios_app_store_version(client, app_id, version_string)
    state = (created.get("attributes") or {}).get("appStoreState") or ""
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
) -> None:
    """Read-only checks before anything is built or uploaded (GET requests only).

    The version must exist (IOS_CREATE_VERSION's step, which runs before this,
    creates a missing one) in a state that allows the enabled steps, and every
    locale they touch must have its localization. The live store step repeats all
    of this, because App Store Connect can change in between.
    """
    app_id = find_app(client, bundle_id)
    version = get_ios_app_store_version(client, app_id, version_string)
    version_id = version["id"]
    state = (version.get("attributes") or {}).get("appStoreState") or ""
    print(f"ASC preflight: app={app_id} version={version_string} id={version_id} state={state}")
    problem = version_state_problem(
        version_string, state, edits_metadata=do_listing or do_whats_new or do_screenshots, submit=do_submit
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
                client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, state=state
            )
        else:
            localization_for_locale(
                client,
                f"/appStoreVersions/{version_id}/appStoreVersionLocalizations",
                ios_metadata.PRIMARY_LOCALE,
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
        if not truthy("CREATE_IOS_VERSION"):
            print("App Store version creation skipped — CREATE_IOS_VERSION is off")
            return
        if not args.version:
            fail("--create-version needs --version (the marketing version from the tag)")
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

    do_listing = truthy("UPLOAD_LISTING")
    do_whats_new = truthy("UPLOAD_WHATS_NEW")
    do_screenshots = truthy("UPLOAD_SCREENSHOTS")
    do_submit = truthy("SUBMIT_IOS_REVIEW")
    do_public = truthy("PUBLIC_RELEASE")

    if not (do_listing or do_whats_new or do_screenshots or do_submit):
        print(
            "ASC release skipped — UPLOAD_LISTING / UPLOAD_WHATS_NEW / "
            "UPLOAD_SCREENSHOTS / SUBMIT_IOS_REVIEW are off"
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
    )
    catalog = load_catalog()

    if args.dry_run:
        print("ASC dry-run — local inputs OK; no API calls")
        print(
            f"  bundle={args.bundle_id} listing={do_listing} whats_new={do_whats_new} "
            f"screenshots={do_screenshots} submit={do_submit} public_release={do_public}"
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
        )
        return

    app_id = find_app(client, args.bundle_id)
    version = get_ios_app_store_version(client, app_id, args.version)
    version_id = version["id"]
    state = (version.get("attributes") or {}).get("appStoreState") or ""
    print(
        f"ASC app={app_id} version={args.version} id={version_id} state={state}"
    )
    edits_metadata = do_listing or do_whats_new or do_screenshots
    problem = version_state_problem(args.version, state, edits_metadata=edits_metadata, submit=do_submit)
    if problem:
        fail(problem)
    # A rerun after a successful submit changes nothing: it only confirms that App
    # Store Connect already holds this run's build and the repository's metadata.
    frozen = state if state == WAITING_FOR_REVIEW else None
    if frozen and do_submit:
        confirm_selected_build(client, version_id, args.build_id)

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
        if state in {READY_FOR_REVIEW, WAITING_FOR_REVIEW}:
            print("checking ASC screenshots (they cannot change in this state)…")
            require_screenshots_unchanged(
                client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE, state=state
            )
        else:
            print("uploading ASC screenshots…")
            upload_screenshots(client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE)

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
        submission_id = get_or_create_draft_submission(client, app_id)
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
