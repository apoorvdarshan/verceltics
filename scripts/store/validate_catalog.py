#!/usr/bin/env python3
"""Check store/catalog/products.json against the products each app actually sells.

iOS: the product IDs in ios/verceltics/Paywall/Products.storekit.
Android: the product ID constants in the Android BillingModels.kt.
Each product lists the platforms that sell it (`platforms`). For iOS, the App
Store creation fields (`ios` blocks, `ios_subscription_groups`) are checked
too, including each review screenshot file; see asc_products.py.
Never contacts a store.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asc_products  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "store" / "catalog" / "products.json"
STOREKIT = ROOT / "ios" / "verceltics" / "Paywall" / "Products.storekit"
BILLING_MODELS = (
    ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "apoorvdarshan" / "verceltics"
    / "billing" / "BillingModels.kt"
)
PACKAGE = "com.apoorvdarshan.verceltics"
GROUPS = tuple(asc_products.PRODUCT_GROUPS)
PLATFORMS = ("ios", "android")


class CatalogError(Exception):
    """The catalog does not match the app's products."""


def load_catalog(path: Path = CATALOG) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def catalog_product_ids(catalog: dict, platform: str | None = None) -> list[str]:
    """Every catalog product ID, or only those whose `platforms` list platform."""
    ids = []
    for group in GROUPS:
        items = catalog.get(group) or []
        if not isinstance(items, list):
            raise CatalogError(f"{group} must be a list")
        for item in items:
            pid = item.get("id")
            if not isinstance(pid, str) or not pid:
                raise CatalogError(f"{group} has an entry without an id: {item!r}")
            platforms = item.get("platforms")
            if not (isinstance(platforms, list) and platforms and set(platforms) <= set(PLATFORMS)
                    and len(set(platforms)) == len(platforms)):
                raise CatalogError(f"{pid}: platforms must list ios and/or android, got {platforms!r}")
            if platform is None or platform in platforms:
                ids.append(pid)
    return ids


def storekit_product_ids(text: str) -> set[str]:
    storekit = json.loads(text)
    ids = {product["productID"] for product in storekit.get("products") or []}
    for group in storekit.get("subscriptionGroups") or []:
        ids.update(sub["productID"] for sub in group.get("subscriptions") or [])
    return ids


def billing_product_ids(kotlin: str) -> set[str]:
    return set(re.findall(r'const val \w+_ID = "(com\.apoorvdarshan\.[^"]+)"', kotlin))


def check(catalog: dict, *, platform: str, app_ids: set[str]) -> int:
    """Validate one platform's view of the catalog; returns the product count."""
    ids = catalog_product_ids(catalog)
    duplicates = sorted({pid for pid in ids if ids.count(pid) > 1})
    if duplicates:
        raise CatalogError(f"duplicate product ids: {', '.join(duplicates)}")
    package = (catalog.get("package") or {}).get(platform)
    if package != PACKAGE:
        raise CatalogError(f"catalog {platform} package is {package!r}, expected {PACKAGE}")
    foreign = sorted(pid for pid in ids if not pid.startswith(PACKAGE + "."))
    if foreign:
        raise CatalogError(f"products from another app: {', '.join(foreign)}")
    sold = catalog_product_ids(catalog, platform)
    missing = sorted(app_ids - set(sold))
    extra = sorted(set(sold) - app_ids)
    if missing or extra:
        source = "Products.storekit" if platform == "ios" else "BillingModels.kt"
        details = []
        if missing:
            details.append(f"missing from the catalog (or without {platform} in platforms): {', '.join(missing)}")
        if extra:
            details.append(f"not in {source}: {', '.join(extra)}")
        raise CatalogError(f"{platform} catalog does not match {source} ({'; '.join(details)})")
    if platform == "ios" and not isinstance(catalog.get("uses_non_exempt_encryption"), bool):
        raise CatalogError("catalog must declare uses_non_exempt_encryption as true or false")
    if platform == "ios":
        problems = asc_products.catalog_problems(catalog, ROOT)
        if problems:
            raise CatalogError("App Store product fields: " + "; ".join(problems))
    return len(sold)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("ios", "android", "all"), default="all")
    args = parser.parse_args(argv)
    catalog = load_catalog()
    platforms = ("ios", "android") if args.platform == "all" else (args.platform,)
    try:
        for platform in platforms:
            source = STOREKIT if platform == "ios" else BILLING_MODELS
            text = source.read_text(encoding="utf-8")
            app_ids = storekit_product_ids(text) if platform == "ios" else billing_product_ids(text)
            count = check(catalog, platform=platform, app_ids=app_ids)
            print(f"{platform}: {count} catalog products match {source.name}")
    except CatalogError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
