#!/usr/bin/env python3
"""App Store Connect listing, What's New, screenshots, build selection and review.

Run by ios-release.yml with the resolved iOS switches as environment flags:
UPLOAD_LISTING, UPLOAD_WHATS_NEW, UPLOAD_SCREENSHOTS, SUBMIT_IOS_REVIEW and
PUBLIC_RELEASE. Every flag that is off skips its App Store Connect work.

Submitting for review covers both the App Store version and any Ready-to-Submit
subscriptions and in-app purchases from store/catalog/products.json. As of the
2026 workflow, subscriptions attach to the review submission through the
`subscriptionVersion` relationship on Review Submission Items; a first-time
subscription must go out with the version or App Review rejects the build.
Review submission only ever selects the build uploaded by this run.
"""
from __future__ import annotations

import argparse
import json
import os
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
EDITABLE_VERSION_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
}
# READY_FOR_REVIEW means the version already sits in a draft review submission
# (for example after a failed submit); it can still be submitted.
SUBMITTABLE_VERSION_STATES = EDITABLE_VERSION_STATES | {"READY_FOR_REVIEW"}
# App Info (name, subtitle, privacy URL) becomes editable with a new version.
EDITABLE_APP_INFO_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "READY_FOR_REVIEW",
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
    query = urllib.parse.urlencode({"filter[bundleId]": bundle_id})
    payload = client.get(f"/apps?{query}")
    data = [row for row in payload.get("data") or []
            if (row.get("attributes") or {}).get("bundleId", bundle_id) == bundle_id]
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


def get_ios_app_store_version(
    client: AscClient, app_id: str, version_string: str
) -> dict[str, Any]:
    # Prefer the app relationship listing — some ASC API keys cannot
    # GET_COLLECTION on /appStoreVersions with filter[app]=… (403).
    rows = iter_collection(
        client,
        f"/apps/{app_id}/appStoreVersions?"
        + urllib.parse.urlencode({"limit": "50", "filter[platform]": "IOS"}),
    )
    matches = [row for row in rows if (row.get("attributes") or {}).get("versionString") == version_string]
    if not matches:
        fail(
            f"no IOS App Store version {version_string!r} for app {app_id} "
            "(create the version in App Store Connect first)"
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


def upload_listing(
    client: AscClient,
    *,
    version_id: str,
    metadata_dir: Path,
    include_listing: bool = True,
    include_whats_new: bool = False,
) -> None:
    """Update each locale's version localization. Listing text and What's New are separate gates."""
    fields = (VERSION_FIELDS if include_listing else ()) + ((WHATS_NEW,) if include_whats_new else ())
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
        changed = changed_attributes(version_loc.get("attributes") or {}, wanted)
        if not changed:
            print(f"  version localization ({locale}) already up to date")
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


def upload_app_info(client: AscClient, *, app_id: str, metadata_dir: Path) -> None:
    """Name, subtitle and privacy URL. App Store Connect only allows edits while a new
    version is prepared, so unchanged values need no editable App Info."""
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
    infos = iter_collection(client, f"/apps/{app_id}/appInfos?limit=50")
    editable = [info for info in infos if app_info_state(info) in EDITABLE_APP_INFO_STATES]
    if len(editable) > 1:
        fail(f"ambiguous: {len(editable)} editable App Info records; resolve this in App Store Connect")
    if not editable:
        live = [info for info in infos if app_info_state(info) not in EDITABLE_APP_INFO_STATES]
        if not live:
            fail("the app has no App Info in App Store Connect")
        for locale, wanted in wanted_by_locale.items():
            loc = localization_for_locale(client, f"/appInfos/{live[0]['id']}/appInfoLocalizations", locale)
            changed = changed_attributes(loc.get("attributes") or {}, wanted)
            if changed:
                fail(
                    f"App Info ({locale}) {', '.join(changed)} differ from App Store Connect, but no "
                    "App Info is editable; create the new App Store version first"
                )
        print("  app name, subtitle and privacy URL already match App Store Connect")
        return
    info_id = editable[0]["id"]
    for locale, wanted in wanted_by_locale.items():
        loc = localization_for_locale(client, f"/appInfos/{info_id}/appInfoLocalizations", locale)
        changed = changed_attributes(loc.get("attributes") or {}, wanted)
        if not changed:
            print(f"  app info ({locale}) already up to date")
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
    client.patch(
        f"/appScreenshots/{shot_id}",
        {
            "data": {
                "type": "appScreenshots",
                "id": shot_id,
                "attributes": {"uploaded": True},
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


def upload_screenshots(client: AscClient, *, version_id: str, locale: str) -> None:
    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    for spec in ios_metadata.SCREENSHOT_SETS:
        print(f"  {spec.label} screenshots from {spec.directory.relative_to(ROOT)}")
        try:
            pngs = ios_metadata.screenshot_files(spec)
        except ios_metadata.MetadataError as exc:
            fail(str(exc))
        replace_screenshot_set(client, version_loc["id"], spec.display_type, pngs)


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


def submit_in_app_purchases(
    client: AscClient,
    app_id: str,
    *,
    intended_ids: set[str] | None = None,
    submission_id: str | None = None,
) -> ProductSubmitResult:
    """Attach Ready-to-Submit subscriptions / IAPs to App Review.

    As of the 2026 App Store Connect workflow, subscriptions are added to the
    app's review submission through the `subscriptionVersion` relationship on
    Review Submission Items — not the `subscription` relationship, which the API
    rejects. Attaching the current in-flight version also clears a
    `DEVELOPER_REJECTED` state left by a prior submission (for example when a
    plan was removed from an earlier review). The lifetime purchase and tips use
    the separate `inAppPurchaseSubmissions` endpoint.

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

    # Idempotency: a retry reuses the same draft, so skip anything already
    # attached instead of posting a duplicate that App Store Connect rejects.
    already_attached = draft_item_version_ids(client, submission_id)

    submitted = 0
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
                submitted += 1
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
            submitted += 1
            added_items = True
            has_products = True
            print(f"  subscription {name}: attached to review submission")

    for iap in iter_collection(client, f"/apps/{app_id}/inAppPurchasesV2?limit=200"):
        attrs = iap.get("attributes") or {}
        pid = attrs.get("productId") or ""
        if pid not in intended_ids:
            continue
        if attrs.get("state") != "READY_TO_SUBMIT":
            continue
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

    print(f"  in-app purchases / subscriptions submitted: {submitted}")
    if failures:
        raise ProductSubmitError(
            "these required products were not added to App Review: "
            + "; ".join(failures)
            + ". Resolve them in App Store Connect and rerun the release."
        )
    return ProductSubmitResult(submission_id, submitted, added_items, has_products)


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
    if upload_listing or upload_whats_new:
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
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate flags and local files only — no ASC HTTP calls",
    )
    args = parser.parse_args()

    if args.bundle_id != DEFAULT_BUNDLE_ID:
        fail("this publisher only supports the Verceltics iOS bundle")

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
    if do_submit and not (args.build_id and args.build_number):
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

    app_id = find_app(client, args.bundle_id)
    version = get_ios_app_store_version(client, app_id, args.version)
    version_id = version["id"]
    state = (version.get("attributes") or {}).get("appStoreState") or ""
    print(
        f"ASC app={app_id} version={args.version} id={version_id} state={state}"
    )
    edits_metadata = do_listing or do_whats_new or do_screenshots

    if state == "WAITING_FOR_REVIEW":
        if do_submit and not edits_metadata:
            # A rerun after a successful submit: only accept it if review holds
            # exactly this run's build.
            confirm_selected_build(client, version_id, args.build_id)
            print(
                "ASC submit skipped — version already WAITING_FOR_REVIEW (idempotent)"
            )
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
        if edits_metadata:
            fail(
                f"cannot update listing/What's New/screenshots while version {args.version!r} "
                "is WAITING_FOR_REVIEW"
            )
        do_submit = False

    if edits_metadata and state not in EDITABLE_VERSION_STATES:
        fail(
            f"version {args.version!r} is not editable for metadata (state={state}); "
            f"expected one of: {', '.join(sorted(EDITABLE_VERSION_STATES))}"
        )

    if do_submit and state not in SUBMITTABLE_VERSION_STATES:
        fail(
            f"version {args.version!r} cannot be submitted for review (state={state})"
        )

    if do_listing or do_whats_new:
        parts = [name for name, on in (("listing", do_listing), ("What's New", do_whats_new)) if on]
        print(f"uploading ASC {' + '.join(parts)}…")
        upload_listing(
            client,
            version_id=version_id,
            metadata_dir=args.metadata_dir,
            include_listing=do_listing,
            include_whats_new=do_whats_new,
        )
        if do_listing:
            upload_app_info(client, app_id=app_id, metadata_dir=args.metadata_dir)

    if do_screenshots:
        print("uploading ASC screenshots…")
        upload_screenshots(client, version_id=version_id, locale=ios_metadata.PRIMARY_LOCALE)

    if do_submit:
        print("selecting this run's build…")
        select_build(client, version_id, args.build_id, args.version, args.build_number)
        ensure_export_compliance(
            client, args.build_id, args.version, args.build_number,
            catalog.get("uses_non_exempt_encryption"),
        )
        set_release_type(client, version_id, public_release=do_public)
        print("submitting in-app purchases / subscriptions for review…")
        submission_id = get_or_create_draft_submission(client, app_id)
        try:
            result = submit_in_app_purchases(
                client, app_id, intended_ids=intended_product_ids(catalog), submission_id=submission_id
            )
        except ProductSubmitError as exc:
            # A first-time subscription must ship with the version, so a failed
            # required product blocks the whole submission rather than letting
            # the version enter review without it.
            fail(f"{exc} Not submitting the app version.")
        # Recheck immediately before submitting: the selection must still be
        # this run's processed build.
        verify_build(client, args.build_id, args.version, args.build_number)
        confirm_selected_build(client, version_id, args.build_id)
        print("submitting for App Store review…")
        submit_for_review(
            client, app_id, version_id, state, submission_id=submission_id
        )
        print(f"  {result.submitted} product(s) went out with the version")

    print("ASC release step finished")


if __name__ == "__main__":
    main()
