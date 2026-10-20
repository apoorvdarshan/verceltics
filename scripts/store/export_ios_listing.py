#!/usr/bin/env python3
"""Export the live App Store listing into store/metadata/ios/<locale>/.

Read-only: it only sends GET requests to App Store Connect. Run it to bring the
repository back in sync after the listing was edited in App Store Connect:

    APP_STORE_CONNECT_API_KEY_ID=… APP_STORE_CONNECT_ISSUER_ID=… \\
    APP_STORE_CONNECT_API_KEY_P8="$(cat AuthKey_….p8)" \\
    python3 scripts/store/export_ios_listing.py

Needs PyJWT (pip install --require-hashes -r requirements-store.txt).
What's New is not exported: it comes from the tag's RELEASE_NOTES.md section.
"""
from __future__ import annotations

import argparse
import datetime as dt
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asc_release  # noqa: E402
import ios_metadata  # noqa: E402

LIVE_VERSION_STATES = {"READY_FOR_SALE"}
LIVE_APP_INFO_STATES = {"READY_FOR_DISTRIBUTION", "READY_FOR_SALE"}


def only(rows: list[dict[str, Any]], what: str) -> dict[str, Any]:
    if len(rows) != 1:
        asc_release.fail(f"expected exactly one {what}, found {len(rows)}")
    return rows[0]


def write_locale(out: Path, locale: str, fields, attributes: dict[str, Any]) -> list[str]:
    target = out / locale
    target.mkdir(parents=True, exist_ok=True)
    written = []
    for field in fields:
        value = (attributes.get(field.attribute) or "").strip()
        path = target / field.filename
        if value:
            path.write_text(value + "\n", encoding="utf-8")
            written.append(field.filename)
        elif path.exists():
            path.unlink()
    return written


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=ios_metadata.LISTING_DIR)
    parser.add_argument("--bundle-id", default=asc_release.DEFAULT_BUNDLE_ID)
    args = parser.parse_args()

    client = asc_release.client_from_environment()
    app_id = asc_release.find_app(client, args.bundle_id)

    versions = asc_release.iter_collection(client, f"/apps/{app_id}/appStoreVersions?filter[platform]=IOS&limit=50")
    # Every released version keeps READY_FOR_SALE; the newest one is on the store.
    released = [v for v in versions if (v.get("attributes") or {}).get("appStoreState") in LIVE_VERSION_STATES]
    if not released:
        asc_release.fail("the app has no released iOS version")
    live = max(released, key=lambda v: dt.datetime.fromisoformat(v["attributes"]["createdDate"]))
    version_string = live["attributes"]["versionString"]
    infos = asc_release.iter_collection(client, f"/apps/{app_id}/appInfos?limit=50")
    info = only([i for i in infos if asc_release.app_info_state(i) in LIVE_APP_INFO_STATES], "live App Info")

    version_locs = asc_release.iter_collection(
        client, f"/appStoreVersions/{live['id']}/appStoreVersionLocalizations?limit=50"
    )
    info_locs = asc_release.iter_collection(client, f"/appInfos/{info['id']}/appInfoLocalizations?limit=50")
    by_locale: dict[str, list[str]] = {}
    for loc in version_locs:
        attrs = loc.get("attributes") or {}
        by_locale.setdefault(attrs["locale"], []).extend(
            write_locale(args.out, attrs["locale"], ios_metadata.VERSION_FIELDS, attrs)
        )
    for loc in info_locs:
        attrs = loc.get("attributes") or {}
        by_locale.setdefault(attrs["locale"], []).extend(
            write_locale(args.out, attrs["locale"], ios_metadata.APP_INFO_FIELDS, attrs)
        )
    for locale, files in sorted(by_locale.items()):
        print(f"{locale}: {', '.join(sorted(files))}")
    print(f"exported the live {version_string} listing for {len(by_locale)} locale(s) to {args.out}")


if __name__ == "__main__":
    main()
