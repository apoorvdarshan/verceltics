#!/usr/bin/env python3
"""Export the live App Store listing into store/metadata/ios/<locale>/.

Read-only: it runs the pinned App Store Connect CLI (asc_cli.py) in read-only
mode, which refuses every write. Run it to bring the repository back in sync
after the listing was edited in App Store Connect:

    python3 scripts/store/asc_cli.py install --dest /tmp/asc-bin
    PATH="/tmp/asc-bin:$PATH" \\
    APP_STORE_CONNECT_API_KEY_ID=… APP_STORE_CONNECT_ISSUER_ID=… \\
    APP_STORE_CONNECT_API_KEY_P8="$(cat AuthKey_….p8)" \\
    python3 scripts/store/export_ios_listing.py

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

    asc = asc_release.asc_from_environment(read_only=True)
    app_id = asc_release.find_app(asc, args.bundle_id)

    versions = asc_release.list_ios_app_store_versions(asc, app_id)
    # Every released version keeps READY_FOR_SALE; the newest one is on the store.
    released = [v for v in versions if asc_release.attrs(v).get("appStoreState") in LIVE_VERSION_STATES]
    if not released:
        asc_release.fail("the app has no released iOS version")
    live = max(released, key=lambda v: dt.datetime.fromisoformat(v["attributes"]["createdDate"]))
    version_string = live["attributes"]["versionString"]
    infos = asc_release.rows(asc_release.call(asc, "apps", "info", "list", "--app", app_id))
    info = only([i for i in infos if asc_release.app_info_state(i) in LIVE_APP_INFO_STATES], "live App Info")

    version_locs = asc_release.rows(
        asc_release.call(asc, "localizations", "list", "--version", live["id"], "--paginate")
    )
    info_locs = asc_release.rows(
        asc_release.call(
            asc, "localizations", "list", "--app", app_id, "--type", "app-info", "--app-info", info["id"], "--paginate"
        )
    )
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
