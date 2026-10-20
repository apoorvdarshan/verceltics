#!/usr/bin/env python3
"""Create the catalog's missing App Store products (IOS_CREATE_PRODUCTS).

Every iOS product in store/catalog/products.json (subscriptions, consumables,
non-consumables, tips) is matched to App Store Connect by product ID:

- missing: created and brought to Ready to Submit (reference name, type or
  subscription group and period, base price in the United States, availability,
  en-US display name and description, App Review screenshot and, for
  subscriptions, the review note; family sharing exactly as the catalog says);
- in Missing Metadata with the catalog's reference name and type (or period) and
  family sharing: finished, which an interrupted run leaves behind. Only missing
  parts are added; anything already set must match the catalog or the run stops;
- anything else: never changed. Price, period, type and family sharing are
  compared read-only and differences are printed as warnings.

Subcommands:

  plan    Read-only. Check the catalog fields, review screenshots, prices and
          territories of every product that would be created or finished, and
          print what would happen. Validation runs use this.
  create  The plan above (read-only, it must pass completely), then create and
          finish. Needs CREATE_IOS_PRODUCTS=true. Re-running is safe: products
          that already exist are found by product ID, never created twice.

Every App Store Connect call goes through the pinned asc (asc_cli.py). New
products still have to be added to RevenueCat's offerings by hand.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
import zlib
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asc_cli  # noqa: E402
import asc_release  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "store" / "catalog" / "products.json"

# Catalog group -> (kind, in-app purchase type). Tips are consumables on the App Store.
PRODUCT_GROUPS = {
    "subscriptions": ("subscription", None),
    "consumables": ("iap", "CONSUMABLE"),
    "non_consumables": ("iap", "NON_CONSUMABLE"),
    "tips": ("iap", "CONSUMABLE"),
}
REQUIRED_GROUPS = ("subscriptions", "consumables", "tips")
# ISO 8601 period in the catalog -> App Store Connect subscription period.
PERIODS = {
    "P1W": "ONE_WEEK",
    "P1M": "ONE_MONTH",
    "P2M": "TWO_MONTHS",
    "P3M": "THREE_MONTHS",
    "P6M": "SIX_MONTHS",
    "P1Y": "ONE_YEAR",
}
# `suggested_price_usd` is the base price, set in the United States; App Store
# Connect derives every other territory's price from it.
PRICE_TERRITORY = "USA"
PRICE_PATTERN = re.compile(r"[0-9]+\.[0-9]{2}")
LOCALE = "en-US"

IOS_KEYS = {
    "reference_name", "display_name", "description", "review_note", "review_screenshot",
    "family_sharing", "territories", "subscription_group",
}
GROUP_KEYS = {"reference_name", "display_name"}
# Apple's limits (App Store Connect help, "In-app purchase information").
LIMITS = {"reference_name": (1, 64), "display_name": (2, 30), "description": (1, 45), "review_note": (1, 4000)}
PRODUCT_ID_MAX = 100
# Every field creation needs; review_note only applies to subscriptions (asc
# 5.15.0 has no command for an in-app purchase's review note).
CREATE_FIELDS = ("reference_name", "display_name", "description", "review_screenshot")
SUBSCRIPTION_CREATE_FIELDS = CREATE_FIELDS + ("review_note", "subscription_group")
SCREENSHOT_EXTENSIONS = {".png", ".jpg", ".jpeg"}


def _sizes(*pairs: tuple[int, int]) -> set[tuple[int, int]]:
    return {size for width, height in pairs for size in ((width, height), (height, width))}


# App Review screenshot sizes App Store Connect accepts for an iPhone and iPad
# app: the iPhone and iPad screenshot specifications, portrait or landscape (the
# same list asc 5.15.0 checks review screenshots against).
REVIEW_SCREENSHOT_SIZES = _sizes(
    (1260, 2736), (1290, 2796), (1320, 2868),  # 6.9" / 6.7"
    (1206, 2622), (1179, 2556),  # 6.3" / 6.1"
    (1242, 2688), (1284, 2778),  # 6.5"
    (1170, 2532), (1125, 2436), (1080, 2340),  # 5.8" / 5.4"
    (1242, 2208), (750, 1334), (640, 1136), (640, 960),  # 5.5", 4.7", 4", 3.5"
    (1398, 2034), (2007, 2853),  # iPhone Duo
    (2048, 2732), (2064, 2752),  # 13" / 12.9" iPad
    (1488, 2266), (1668, 2388), (1668, 2420), (1640, 2360),  # 11" iPad
    (1668, 2224), (1536, 2048),  # 10.5" / 9.7" iPad
)

READY = "READY_TO_SUBMIT"
MISSING_METADATA = "MISSING_METADATA"
# Readback after the last part is added: App Store Connect may take a moment to
# move a product to Ready to Submit.
STATE_POLLS = 12
STATE_POLL_SECONDS = 10


def fail(msg: str) -> None:
    asc_release.fail(msg)


@dataclass
class CatalogProduct:
    product_id: str
    group: str
    kind: str  # "subscription" or "iap"
    iap_type: str | None
    period: str | None  # App Store Connect period, subscriptions only
    price: str | None
    ios: dict[str, Any]

    @property
    def label(self) -> str:
        if self.kind == "subscription":
            return f"subscription {self.product_id}"
        return f"{self.iap_type.lower().replace('_', '-')} {self.product_id}"  # type: ignore[union-attr]

    @property
    def family_sharing(self) -> bool:
        return bool(self.ios.get("family_sharing", False))

    def missing_fields(self) -> list[str]:
        """Catalog fields this product still needs before it can be created."""
        needed = SUBSCRIPTION_CREATE_FIELDS if self.kind == "subscription" else CREATE_FIELDS
        missing = [f"ios.{name}" for name in needed if not self.ios.get(name)]
        if not self.price:
            missing.append("suggested_price_usd")
        if self.kind == "subscription" and not self.period:
            missing.append("period")
        return missing


def ios_products(catalog: dict[str, Any]) -> list[CatalogProduct]:
    """The catalog's iOS products, in catalog order."""
    found = []
    for group, (kind, iap_type) in PRODUCT_GROUPS.items():
        for item in catalog.get(group) or []:
            if "ios" not in (item.get("platforms") or []):
                continue
            found.append(
                CatalogProduct(
                    product_id=item.get("id", ""),
                    group=group,
                    kind=kind,
                    iap_type=iap_type,
                    period=PERIODS.get(item.get("period", "")) if kind == "subscription" else None,
                    price=item.get("suggested_price_usd"),
                    ios=dict(item.get("ios") or {}),
                )
            )
    return found


PNG_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def png_data_problem(raw: bytes) -> str | None:
    """Why a PNG's data is damaged (bad chunk, truncated or short image data), or None."""
    pos, idat, header = 8, [], b""
    while True:
        if pos + 12 > len(raw):
            return "ends before its IEND chunk (truncated)"
        length = int.from_bytes(raw[pos:pos + 4], "big")
        kind, body = raw[pos + 4:pos + 8], raw[pos + 8:pos + 8 + length]
        crc = raw[pos + 8 + length:pos + 12 + length]
        if len(body) != length or len(crc) != 4:
            return f"ends inside its {kind.decode('latin-1')} chunk (truncated)"
        if zlib.crc32(kind + body).to_bytes(4, "big") != crc:
            return f"has a damaged {kind.decode('latin-1')} chunk (checksum mismatch)"
        if kind == b"IHDR":
            header = body
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
        pos += 12 + length
    if len(header) != 13 or not idat:
        return "has no image header or image data"
    width, height = int.from_bytes(header[:4], "big"), int.from_bytes(header[4:8], "big")
    depth, colour, interlace = header[8], header[9], header[12]
    inflater = zlib.decompressobj()
    try:
        pixels = inflater.decompress(b"".join(idat)) + inflater.flush()
    except zlib.error as exc:
        return f"has damaged image data ({exc})"
    if not inflater.eof:
        return "has truncated image data"
    if interlace == 0 and colour in PNG_CHANNELS:
        stride = 1 + math.ceil(width * depth * PNG_CHANNELS[colour] / 8)
        if len(pixels) != stride * height:
            return "has truncated image data"
        if any(pixels[row * stride] > 4 for row in range(height)):
            return "has damaged image data (unknown row filter)"
    return None


def jpeg_data_problem(raw: bytes) -> str | None:
    """Why a JPEG is damaged (no frame or scan, or cut off before its end marker), or None."""
    pos, frame = 2, False
    while pos + 4 <= len(raw):
        if raw[pos] != 0xFF:
            return "has a damaged segment"
        marker = raw[pos + 1]
        if marker == 0xFF:
            pos += 1
            continue
        if marker == 0xDA:  # start of scan: the entropy-coded data runs to the end marker
            if not frame:
                return "has no frame header"
            return None if raw.rstrip(b"\x00").endswith(b"\xff\xd9") else "ends before its end marker (truncated)"
        frame = frame or (0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC))
        pos += 2 + int.from_bytes(raw[pos + 2:pos + 4], "big")
    return "ends before its image data (truncated)"


def screenshot_problem(path: Path) -> str | None:
    """Why App Store Connect would refuse this App Review screenshot, or None."""
    if path.suffix.lower() not in SCREENSHOT_EXTENSIONS:
        return "must be a .png, .jpg or .jpeg file"
    if not path.is_file():
        return "does not exist"
    if asc_cli.image_has_alpha(path):
        return "has an alpha channel, which App Store Connect refuses; re-export it as RGB"
    kind, width, height = asc_release.image_dimensions(path)
    if kind is None:
        return "is not a PNG or JPEG image"
    if (kind == "png") != (path.suffix.lower() == ".png"):
        return f"is a {kind.upper()} image named {path.suffix.lower()}; App Store Connect refuses a name that does not match the format"
    if (width, height) not in REVIEW_SCREENSHOT_SIZES:
        return f"is {width}x{height}, which is not an iPhone or iPad screenshot size (e.g. 1290x2796 or 2064x2752)"
    # The header alone can look right on a damaged file, which App Store
    # Connect would only refuse after the product was created.
    raw = path.read_bytes()
    damage = png_data_problem(raw) if kind == "png" else jpeg_data_problem(raw)
    return f"is damaged: it {damage}" if damage else None


def _text_problem(name: str, value: Any) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return f"ios.{name} must be non-empty text"
    if value != value.strip():
        return f"ios.{name} has leading or trailing whitespace"
    low, high = LIMITS[name]
    if not low <= len(value) <= high:
        return f"ios.{name} must be {low}-{high} characters (it is {len(value)})"
    return None


def catalog_problems(catalog: dict[str, Any], root: Path = ROOT) -> list[str]:
    """Offline checks of the App Store creation fields (validate_catalog.py runs these).

    Existing live products need none of the `ios` fields; whatever is given must
    be well formed, and a review screenshot must be a file App Store Connect takes.
    """
    problems: list[str] = []
    groups = catalog.get("ios_subscription_groups", [])
    declared: dict[str, dict[str, Any]] = {}
    if not isinstance(groups, list):
        problems.append("ios_subscription_groups must be a list")
        groups = []
    for group in groups:
        if not isinstance(group, dict):
            problems.append(f"ios_subscription_groups has an entry that is not an object: {group!r}")
            continue
        unknown = sorted(set(group) - GROUP_KEYS)
        name = group.get("reference_name")
        if unknown:
            problems.append(f"subscription group {name!r}: unknown keys {', '.join(unknown)}")
        if not isinstance(name, str) or not name.strip() or len(name) > 64:
            problems.append(f"subscription group reference_name must be 1-64 characters, got {name!r}")
            continue
        if name in declared:
            problems.append(f"subscription group {name!r} is declared twice")
        display = group.get("display_name")
        if display is not None and (not isinstance(display, str) or len(display.strip()) < 2):
            problems.append(f"subscription group {name!r}: display_name must be at least 2 characters")
        declared[name] = group

    for group_name, (kind, _) in PRODUCT_GROUPS.items():
        for item in catalog.get(group_name) or []:
            pid = item.get("id")
            on_ios = "ios" in (item.get("platforms") or [])
            ios = item.get("ios")
            if ios is None:
                ios = {}
            elif not on_ios:
                problems.append(f"{pid} has an ios block but does not list ios")
                continue
            if not isinstance(ios, dict):
                problems.append(f"{pid}: ios must be an object")
                continue
            if not on_ios:
                continue
            if isinstance(pid, str) and len(pid) > PRODUCT_ID_MAX:
                problems.append(f"{pid}: App Store product ids are at most {PRODUCT_ID_MAX} characters")
            price = item.get("suggested_price_usd")
            if price is not None and not (isinstance(price, str) and PRICE_PATTERN.fullmatch(price) and Decimal(price) > 0):
                problems.append(f"{pid}: suggested_price_usd must look like \"4.99\", got {price!r}")
            if kind == "subscription" and item.get("period") not in PERIODS:
                problems.append(f"{pid}: period must be one of {', '.join(PERIODS)}, got {item.get('period')!r}")
            unknown = sorted(set(ios) - IOS_KEYS)
            if unknown:
                problems.append(f"{pid}: unknown ios keys {', '.join(unknown)}")
            for name in LIMITS:
                if name in ios:
                    problem = _text_problem(name, ios[name])
                    if problem:
                        problems.append(f"{pid}: {problem}")
            if "review_note" in ios and kind != "subscription":
                problems.append(
                    f"{pid}: ios.review_note only applies to subscriptions; the pinned asc has no command for an "
                    "in-app purchase's review note, so add one in App Store Connect if App Review needs it"
                )
            if "subscription_group" in ios:
                if kind != "subscription":
                    problems.append(f"{pid}: ios.subscription_group only applies to subscriptions")
                elif ios["subscription_group"] not in declared:
                    problems.append(
                        f"{pid}: ios.subscription_group {ios['subscription_group']!r} is not in ios_subscription_groups"
                    )
            if "family_sharing" in ios:
                if not isinstance(ios["family_sharing"], bool):
                    problems.append(f"{pid}: ios.family_sharing must be true or false")
                elif ios["family_sharing"] and group_name not in ("subscriptions", "non_consumables"):
                    problems.append(f"{pid}: family sharing is only for subscriptions and non-consumables")
            if "territories" in ios:
                territories = ios["territories"]
                if territories != "all" and not (
                    isinstance(territories, list) and territories
                    and all(isinstance(code, str) and re.fullmatch(r"[A-Z]{3}", code) for code in territories)
                    and len(set(territories)) == len(territories)
                ):
                    problems.append(f"{pid}: ios.territories must be \"all\" or a list of distinct alpha-3 codes like \"USA\"")
            if "review_screenshot" in ios:
                shot = ios["review_screenshot"]
                if not isinstance(shot, str) or not shot:
                    problems.append(f"{pid}: ios.review_screenshot must be a path")
                    continue
                path = (root / shot).resolve()
                if Path(shot).is_absolute() or not path.is_relative_to((root / "store").resolve()):
                    problems.append(f"{pid}: ios.review_screenshot must be a path under store/, got {shot!r}")
                    continue
                problem = screenshot_problem(path)
                if problem:
                    problems.append(f"{pid}: review screenshot {shot} {problem}")
    return problems


# --- App Store Connect (read-only planning) ---------------------------------


@dataclass
class Planned:
    product: CatalogProduct
    action: str  # "create", "finish" or "existing"
    live_id: str | None = None
    territories: list[str] = field(default_factory=list)
    new_territories: bool = False
    group_display_name: str | None = None  # subscriptions: name the group gets in en-US, when it has none
    steps: list[str] = field(default_factory=list)  # in-app purchases: parts still to add
    version_id: str | None = None
    screenshot_id: str | None = None  # an interrupted screenshot upload to resume


@dataclass
class Plan:
    app_id: str
    items: list[Planned]
    warnings: list[str]
    errors: list[str]
    root: Path = ROOT

    def to_write(self) -> list[Planned]:
        return [item for item in self.items if item.action in ("create", "finish")]


def run_optional(asc: Any, *args: str) -> Any | None:
    """asc output, or None when App Store Connect has no such resource (asc exit 4).

    Any other asc failure raises AscError, for the caller to stop on or report.
    """
    try:
        payload = asc.run(*args)
    except asc_cli.AscError as exc:
        if exc.exit_code == 4:
            return None
        raise
    if isinstance(payload, dict) and "data" in payload and not payload.get("data"):
        return None
    return payload


def required(fn: Callable[..., Any], *args: Any) -> Any:
    """fn(*args), stopping the run with asc's error when it fails."""
    try:
        return fn(*args)
    except asc_cli.AscError as exc:
        fail(str(exc))


def same_price(live: Any, wanted: str | None) -> bool:
    try:
        return wanted is not None and Decimal(str(live)) == Decimal(wanted)
    except (InvalidOperation, ValueError):
        return False


def live_price(asc: Any, product: CatalogProduct, live_id: str) -> tuple[str | None, str | None]:
    """(current price in PRICE_TERRITORY, base territory) of a live product; (None, None) without a price."""
    if product.kind == "subscription":
        payload = run_optional(asc, "subscriptions", "pricing", "summary", "--subscription-id", live_id,
                               "--territory", PRICE_TERRITORY)
        rows_ = (payload or {}).get("subscriptions") or []
    else:
        payload = run_optional(asc, "iap", "pricing", "summary", "--iap-id", live_id, "--territory", PRICE_TERRITORY)
        rows_ = (payload or {}).get("iaps") or []
    row = rows_[0] if rows_ else {}
    amount = (row.get("currentPrice") or {}).get("amount")
    return amount, row.get("baseTerritory")


def price_grid_has(asc: Any, app_id: str, kind: str, price: str, reference: str | None,
                   cache: dict[tuple[str, str], bool | None]) -> bool | None:
    """Read-only: is `price` a price point in PRICE_TERRITORY? None when it cannot be told yet.

    Price points belong to each product, so a live product of the same kind is
    asked. An app without one is checked against its own price grid, which has
    the same customer prices as in-app purchases, and the subscription prices up
    to its highest one (subscription-only prices all lie above it; checked on
    App Store Connect, October 2026). A first subscription priced above that
    cannot be checked before it exists.
    """
    key = (kind, price)
    if key not in cache:
        if reference and kind == "subscription":
            payload = asc_release.call(asc, "subscriptions", "pricing", "price-points", "list", "--subscription-id",
                                       reference, "--territory", PRICE_TERRITORY, "--price", price, "--paginate")
        elif reference:
            payload = asc_release.call(asc, "iap", "pricing", "price-points", "list", "--iap-id", reference,
                                       "--territory", PRICE_TERRITORY, "--price", price, "--paginate")
        else:
            payload = asc_release.call(asc, "pricing", "price-points", "--app", app_id, "--territory",
                                       PRICE_TERRITORY, "--paginate")
        prices = [asc_release.attrs(row).get("customerPrice") for row in asc_release.rows(payload)]
        found: bool | None = any(same_price(live, price) for live in prices)
        if not found and not reference and kind == "subscription":
            top = max((Decimal(str(live)) for live in prices if live is not None), default=Decimal(0))
            if Decimal(price) > top:
                found = None
        cache[key] = found
    return cache[key]


def drift_warnings(asc: Any, product: CatalogProduct, live: dict[str, Any], live_kind: str) -> list[str]:
    """How an existing product differs from the catalog (price, period, type, family sharing)."""
    a = asc_release.attrs(live)
    warnings = []
    if live_kind != product.kind:
        what = "a subscription" if live_kind == "subscription" else "an in-app purchase"
        warnings.append(f"{product.product_id} is {what} in App Store Connect; the catalog lists it under {product.group}")
        return warnings
    if product.kind == "subscription" and a.get("subscriptionPeriod") != product.period:
        warnings.append(
            f"{product.product_id}: period is {a.get('subscriptionPeriod')} in App Store Connect, {product.period} in the catalog"
        )
    if product.kind == "iap" and a.get("inAppPurchaseType") != product.iap_type:
        warnings.append(
            f"{product.product_id}: type is {a.get('inAppPurchaseType')} in App Store Connect, {product.iap_type} in the catalog"
        )
    if "family_sharing" in product.ios and bool(a.get("familySharable")) != product.family_sharing:
        warnings.append(
            f"{product.product_id}: family sharing is {'on' if a.get('familySharable') else 'off'} in App Store "
            f"Connect, {'on' if product.family_sharing else 'off'} in the catalog"
        )
    if product.price:
        try:
            amount, _ = live_price(asc, product, live["id"])
        except asc_cli.AscError as exc:
            # Only a warning is at stake, so an App Store Connect error does not stop the release.
            warnings.append(f"{product.product_id}: could not read its price, so it was not compared ({exc})")
            return warnings
        if amount is None:
            warnings.append(f"{product.product_id}: no current price in {PRICE_TERRITORY} in App Store Connect")
        elif not same_price(amount, product.price):
            warnings.append(
                f"{product.product_id}: price is {amount} in {PRICE_TERRITORY} in App Store Connect, "
                f"{product.price} in the catalog"
            )
    return warnings


def identity_mismatch(product: CatalogProduct, live: dict[str, Any]) -> list[str]:
    """What makes a Missing Metadata product differ from what this automation would have created."""
    a = asc_release.attrs(live)
    found = []
    if a.get("name") != product.ios.get("reference_name"):
        found.append(f"reference name {a.get('name')!r}")
    if product.kind == "subscription" and a.get("subscriptionPeriod") != product.period:
        found.append(f"period {a.get('subscriptionPeriod')}")
    if product.kind == "iap" and a.get("inAppPurchaseType") != product.iap_type:
        found.append(f"type {a.get('inAppPurchaseType')}")
    if bool(a.get("familySharable")) != product.family_sharing:
        found.append(f"family sharing {'on' if a.get('familySharable') else 'off'}")
    return found


def iap_version(asc: Any, iap_id: str) -> tuple[str | None, dict[str, dict[str, Any]]]:
    """(current version id, locale -> localization attributes) of an in-app purchase."""
    payload = asc_release.call(asc, "iap", "versions", "list", "--iap-id", iap_id, "--include", "localizations",
                               "--paginate")
    versions = asc_release.rows(payload)
    if not versions:
        return None, {}
    version_id = asc_release.current_version_id(versions)
    version = next(v for v in versions if v["id"] == version_id)
    wanted = {ref.get("id") for ref in ((version.get("relationships") or {}).get("localizations") or {}).get("data") or []}
    locales = {
        asc_release.attrs(row).get("locale"): asc_release.attrs(row)
        for row in payload.get("included") or []
        if row.get("type") == "inAppPurchaseLocalizations" and row.get("id") in wanted
    }
    return version_id, locales


def screenshot_conflict(pid: str, record: dict[str, Any], raw: bytes | None, shot: str) -> str | None:
    """Why an existing App Review screenshot cannot be kept for this product, or None.

    A failed delivery, or a different file, has to be deleted by hand. A file
    that matches is kept even while App Store Connect is still processing it;
    the readback after the run waits for that.
    """
    a = asc_release.attrs(record)
    state = ((a.get("assetDeliveryState") or {}).get("state") or "").upper()
    if state == "FAILED":
        return f"{pid}: its App Review screenshot is FAILED; delete it in App Store Connect and re-run"
    if raw is not None and (a.get("sourceFileChecksum") or "").lower() != asc_release.file_checksum(raw):
        return f"{pid}: its App Review screenshot ({state or 'unknown state'}) differs from {shot}"
    return None


def plan_subscription_parts(asc: Any, item: Planned, live: dict[str, Any], errors: list[str], root: Path) -> None:
    """For a subscription being finished: does every part it already has match the catalog?

    asc's `subscriptions setup` refuses a differing part too, but only after it
    may have added others; checking here keeps a conflict from writing anything.
    """
    product, sub_id = item.product, item.live_id
    pid = product.product_id
    note = asc_release.attrs(live).get("reviewNote") or ""
    if note not in ("", product.ios["review_note"]):
        errors.append(f"{pid}: its review note differs from the catalog")
    versions = asc_release.rows(asc_release.call(asc, "subscriptions", "versions", "list", "--subscription-id", sub_id,
                                                 "--paginate"))
    if versions:
        locales = {
            asc_release.attrs(row).get("locale"): asc_release.attrs(row)
            for row in asc_release.rows(asc_release.call(
                asc, "subscriptions", "versions", "localizations", "list", "--version-id",
                asc_release.current_version_id(versions), "--paginate"))
        }
        loc = locales.get(LOCALE)
        if loc and (loc.get("name") != product.ios["display_name"]
                    or (loc.get("description") or "") != product.ios["description"]):
            errors.append(f"{pid}: its {LOCALE} display name or description differs from the catalog")
    amount, _ = required(live_price, asc, product, sub_id)
    if amount is not None and not same_price(amount, product.price):
        errors.append(f"{pid}: its price is {amount}, the catalog has {product.price} in {PRICE_TERRITORY}")
    availability = required(run_optional, asc, "subscriptions", "pricing", "availability", "view", "--subscription-id", sub_id)
    if availability is not None:
        record = asc_release.data(availability) or {}
        live_territories = {row["id"] for row in asc_release.rows(asc_release.call(
            asc, "subscriptions", "pricing", "availability", "available-territories", "--availability-id",
            record.get("id", ""), "--paginate"))}
        if (set(item.territories) != live_territories
                or bool(asc_release.attrs(record).get("availableInNewTerritories")) != item.new_territories):
            errors.append(f"{pid}: its availability differs from the catalog's territories")
    shot = required(run_optional, asc, "subscriptions", "review", "app-store-screenshot", "view", "--subscription-id", sub_id)
    if shot is not None:
        try:
            raw: bytes | None = (root / product.ios["review_screenshot"]).read_bytes()
        except OSError:
            raw = None  # catalog_problems already reports the missing file
        conflict = screenshot_conflict(pid, asc_release.data(shot) or {}, raw, product.ios["review_screenshot"])
        if conflict:
            errors.append(conflict)


def plan_iap_steps(asc: Any, item: Planned, errors: list[str], root: Path) -> None:
    """For an in-app purchase being finished: which parts are missing, and whether the rest matches."""
    product, iap_id = item.product, item.live_id
    pid = product.product_id
    item.version_id, locales = iap_version(asc, iap_id)  # type: ignore[arg-type]
    loc = locales.get(LOCALE)
    if loc is None:
        item.steps.append("localization")
    elif loc.get("name") != product.ios["display_name"] or (loc.get("description") or "") != product.ios["description"]:
        errors.append(f"{pid}: its {LOCALE} display name or description differs from the catalog")

    amount, base = required(live_price, asc, product, iap_id)
    if amount is None:
        item.steps.append("price")
    elif base != PRICE_TERRITORY or not same_price(amount, product.price):
        errors.append(f"{pid}: its price is {amount} (base territory {base}), the catalog has {product.price} in {PRICE_TERRITORY}")

    availability = required(run_optional, asc, "iap", "pricing", "availability", "view", "--iap-id", iap_id)
    if availability is None:
        item.steps.append("availability")
    else:
        record = asc_release.data(availability) or {}
        live_territories = {
            row["id"] for row in asc_release.rows(asc_release.call(
                asc, "iap", "pricing", "availabilities", "available-territories", "--id", record.get("id", ""), "--paginate"))
        }
        if (set(item.territories) != live_territories
                or bool(asc_release.attrs(record).get("availableInNewTerritories")) != item.new_territories):
            errors.append(f"{pid}: its availability differs from the catalog's territories")

    shot = required(run_optional, asc, "iap", "review-screenshots", "view", "--iap-id", iap_id)
    if shot is None:
        item.steps.append("screenshot")
    else:
        record = asc_release.data(shot) or {}
        a = asc_release.attrs(record)
        try:
            raw = (root / product.ios["review_screenshot"]).read_bytes()
        except OSError:
            return  # catalog_problems already reports the missing file
        if a.get("uploadOperations") and a.get("fileSize") == len(raw):
            # A reservation an interrupted run left: resume the upload.
            item.steps.append("screenshot")
            item.screenshot_id = record.get("id")
        else:
            conflict = screenshot_conflict(pid, record, raw, product.ios["review_screenshot"])
            if conflict:
                errors.append(conflict)


def plan_products(asc: Any, catalog: dict[str, Any], *, root: Path = ROOT) -> Plan:
    """Read-only: what creating the catalog's iOS products would do. Never writes."""
    problems = catalog_problems(catalog, root)
    app_id = asc_release.find_app(asc, asc_release.DEFAULT_BUNDLE_ID)
    subs = {asc_release.attrs(r).get("productId"): r for r in asc_release.rows(
        asc_release.call(asc, "subscriptions", "list", "--app", app_id, "--paginate"))}
    iaps = {asc_release.attrs(r).get("productId"): r for r in asc_release.rows(
        asc_release.call(asc, "iap", "list", "--app", app_id, "--paginate"))}
    items: list[Planned] = []
    warnings: list[str] = []
    errors: list[str] = list(problems)

    for product in ios_products(catalog):
        live, live_kind = (subs[product.product_id], "subscription") if product.product_id in subs else (
            (iaps[product.product_id], "iap") if product.product_id in iaps else (None, None))
        if live is None:
            items.append(Planned(product, "create"))
            continue
        state = asc_release.attrs(live).get("state")
        if state == MISSING_METADATA and live_kind == product.kind:
            mismatch = [] if product.missing_fields() else identity_mismatch(product, live)
            if product.missing_fields():
                warnings.append(
                    f"{product.product_id} is Missing Metadata; give it complete ios fields in the catalog to let a "
                    "release finish it, or finish it in App Store Connect"
                )
            elif not mismatch:
                items.append(Planned(product, "finish", live_id=live["id"]))
                continue
            else:
                warnings.append(
                    f"{product.product_id} is Missing Metadata with {', '.join(mismatch)}, unlike the catalog, so this "
                    "automation did not create it; it is left as it is (finish it in App Store Connect)"
                )
        items.append(Planned(product, "existing", live_id=live["id"]))
        warnings += drift_warnings(asc, product, live, live_kind)  # type: ignore[arg-type]

    writing = [item for item in items if item.action != "existing"]
    if writing:
        territories = [row["id"] for row in asc_release.rows(
            asc_release.call(asc, "pricing", "territories", "list", "--paginate"))]
        groups_payload = asc_release.call(asc, "subscriptions", "groups", "list", "--app", app_id, "--include",
                                          "subscriptionGroupLocalizations", "--paginate")
        localized = {
            row["id"]: asc_release.attrs(row) for row in groups_payload.get("included") or []
            if row.get("type") == "subscriptionGroupLocalizations"
        }
        live_groups: dict[str, list[dict[str, Any]]] = {}
        for row in asc_release.rows(groups_payload):
            refs = ((row.get("relationships") or {}).get("subscriptionGroupLocalizations") or {}).get("data") or []
            live_groups[asc_release.attrs(row).get("referenceName")] = [localized[r["id"]] for r in refs if r.get("id") in localized]
        declared = {g.get("reference_name"): g for g in catalog.get("ios_subscription_groups") or [] if isinstance(g, dict)}
        reference = {
            "subscription": next(iter(subs.values()), {}).get("id"),
            "iap": next(iter(iaps.values()), {}).get("id"),
        }
        price_cache: dict[tuple[str, str], bool | None] = {}
        for item in writing:
            product = item.product
            pid = product.product_id
            missing = product.missing_fields()
            if missing:
                errors.append(f"{pid} would be created but the catalog lacks {', '.join(missing)}")
                continue
            wanted = product.ios.get("territories", "all")
            if wanted == "all":
                item.territories, item.new_territories = list(territories), True
            else:
                unknown = sorted(set(wanted) - set(territories))
                if unknown:
                    errors.append(f"{pid}: App Store Connect has no territories {', '.join(unknown)}")
                item.territories, item.new_territories = list(wanted), False
            priced = price_grid_has(asc, app_id, product.kind, product.price, reference[product.kind], price_cache)  # type: ignore[arg-type]
            if priced is None:
                warnings.append(
                    f"{pid}: {product.price} is above the app's price grid, so it cannot be checked before the app's "
                    "first subscription exists; App Store Connect checks it while creating the subscription"
                )
            elif not priced:
                where = "" if reference[product.kind] else (
                    " (checked against the app's own price grid: the app has no live product of this kind to ask yet)")
                errors.append(f"{pid}: {product.price} is not a price point in {PRICE_TERRITORY}{where}")
            if product.kind == "subscription":
                group = declared.get(product.ios["subscription_group"]) or {}
                locales = {loc.get("locale"): loc for loc in live_groups.get(group.get("reference_name"), [])}
                if group.get("reference_name") not in live_groups or LOCALE not in locales:
                    if not group.get("display_name"):
                        errors.append(
                            f"{pid}: subscription group {group.get('reference_name')!r} needs a display_name in the "
                            f"catalog (App Store Connect has no {LOCALE} name for it yet)"
                        )
                    item.group_display_name = group.get("display_name")
                elif group.get("display_name") and locales[LOCALE].get("name") != group["display_name"]:
                    warnings.append(
                        f"subscription group {group['reference_name']!r} is named {locales[LOCALE].get('name')!r} in "
                        "App Store Connect, unlike the catalog; the existing group is left as it is"
                    )
                if item.action == "finish":
                    plan_subscription_parts(asc, item, subs[pid], errors, root)
            elif item.action == "finish":
                plan_iap_steps(asc, item, errors, root)
            else:
                item.steps = ["create", "localization", "price", "availability", "screenshot"]
    return Plan(app_id, items, warnings, errors, root)


def describe(plan: Plan) -> None:
    print("App Store products (catalog vs App Store Connect):")
    for item in plan.items:
        product = item.product
        detail = f"{product.price} USD in {PRICE_TERRITORY}"
        if product.kind == "subscription":
            detail = f"{product.period}, {detail}, group {product.ios.get('subscription_group')!r}"
        if item.action == "create":
            print(f"  would create {product.label} ({detail})")
        elif item.action == "finish":
            parts = ", ".join(item.steps) if product.kind == "iap" else "missing metadata"
            print(f"  would finish {product.label} (Missing Metadata from an earlier run; adds: {parts or 'nothing'})")
        else:
            print(f"  exists: {product.label} (left as it is)")
    for warning in plan.warnings:
        print(f"  warning: {warning}")
    for error in plan.errors:
        print(f"  error: {error}", file=sys.stderr)


# --- App Store Connect (writes) ----------------------------------------------


def subscription_setup_args(plan: Plan, item: Planned, *, screenshot: bool) -> list[str]:
    product = item.product
    group = product.ios["subscription_group"]
    args = [
        "subscriptions", "setup", "--app", plan.app_id, "--group-reference-name", asc_release.asc_value(group),
        "--reference-name", asc_release.asc_value(product.ios["reference_name"]), "--product-id", product.product_id,
        "--subscription-period", product.period, "--locale", LOCALE,
        "--display-name", asc_release.asc_value(product.ios["display_name"]),
        "--description", asc_release.asc_value(product.ios["description"]),
        "--price", product.price, "--price-territory", PRICE_TERRITORY, "--territories", ",".join(item.territories),
    ]
    if item.group_display_name:
        # Only when App Store Connect has no en-US name for the group (a new group,
        # or one an interrupted run created); an existing name is never changed.
        args += ["--group-locale", LOCALE, "--group-display-name", asc_release.asc_value(item.group_display_name)]
    if item.new_territories:
        args.append("--available-in-new-territories")
    if product.family_sharing:
        # Family sharing cannot be turned off again, so it is only ever sent when the catalog asks for it.
        args.append("--family-sharable")
    if screenshot:
        args += ["--review-screenshot", str(plan.root / product.ios["review_screenshot"])]
    else:
        args.append("--no-verify")
    return args


def find_live(asc: Any, plan: Plan, product: CatalogProduct) -> dict[str, Any]:
    command = ("subscriptions", "list") if product.kind == "subscription" else ("iap", "list")
    found = [r for r in asc_release.rows(asc_release.call(asc, *command, "--app", plan.app_id, "--paginate"))
             if asc_release.attrs(r).get("productId") == product.product_id]
    if len(found) != 1:
        fail(f"expected one {product.label} in App Store Connect after creating it, found {len(found)}")
    return found[0]


def wait_until_ready(asc: Any, product: CatalogProduct, live_id: str, *,
                     sleep: Callable[[float], None] = time.sleep) -> dict[str, Any]:
    """Read the product back until it is Ready to Submit (bounded), then check what it holds."""
    view = ("subscriptions", "view", "--id", live_id) if product.kind == "subscription" else ("iap", "view", "--id", live_id)
    record: dict[str, Any] = {}
    for attempt in range(STATE_POLLS):
        record = asc_release.data(asc_release.call(asc, *view)) or {}
        if asc_release.attrs(record).get("state") == READY:
            break
        if attempt + 1 < STATE_POLLS:
            sleep(STATE_POLL_SECONDS)
    a = asc_release.attrs(record)
    problems = []
    if a.get("state") != READY:
        problems.append(f"state is {a.get('state') or 'unknown'}, expected {READY}")
    if a.get("productId") != product.product_id:
        problems.append(f"product id {a.get('productId')!r}")
    problems += identity_mismatch(product, record)
    if product.kind == "subscription" and (a.get("reviewNote") or "") != product.ios["review_note"]:
        problems.append("review note differs from the catalog")
    if problems:
        fail(f"{product.label} was not completed: {'; '.join(problems)}. Re-run the release to finish it.")
    return record


def create_subscription(asc: Any, plan: Plan, item: Planned, *, sleep: Callable[[float], None]) -> None:
    """Create or finish a subscription with asc's reconciling `subscriptions setup`.

    The first call adds everything but the App Review screenshot (missing parts
    only; it stops on anything that differs), so the subscription stays Missing
    Metadata until its review note is set. The second call adds the screenshot,
    which completes it, and verifies the result.
    """
    product = item.product
    result = asc_release.call(asc, *subscription_setup_args(plan, item, screenshot=False))
    live_id = (result or {}).get("subscriptionId") or find_live(asc, plan, product)["id"]
    a = asc_release.attrs(asc_release.data(asc_release.call(asc, "subscriptions", "view", "--id", live_id)))
    if (a.get("reviewNote") or "") != product.ios["review_note"]:
        asc_release.call(asc, "subscriptions", "update", "--id", live_id, "--review-note",
                         asc_release.asc_value(product.ios["review_note"]))
    asc_release.call(asc, *subscription_setup_args(plan, item, screenshot=True))
    wait_until_ready(asc, product, live_id, sleep=sleep)


def create_iap(asc: Any, plan: Plan, item: Planned, *, sleep: Callable[[float], None]) -> None:
    """Create or finish an in-app purchase one part at a time; the screenshot goes last."""
    product = item.product
    iap_id = item.live_id
    if "create" in item.steps:
        args = ["iap", "create", "--app", plan.app_id, "--type", product.iap_type, "--ref-name",
                asc_release.asc_value(product.ios["reference_name"]), "--product-id", product.product_id]
        if product.family_sharing:
            args.append("--family-sharable")
        created = asc_release.data(asc_release.call(asc, *args)) or {}
        iap_id = created.get("id") or find_live(asc, plan, product)["id"]
        print(f"  created {product.label} ({iap_id})")
    if "localization" in item.steps:
        version_id = item.version_id or iap_version(asc, iap_id)[0]  # type: ignore[arg-type]
        if not version_id:
            version_id = (asc_release.data(asc_release.call(asc, "iap", "versions", "create", "--iap-id", iap_id)) or {}).get("id")
        if not version_id:
            fail(f"{product.label}: App Store Connect returned no in-app purchase version")
        asc_release.call(asc, "iap", "versions", "localizations", "create", "--version-id", version_id, "--locale", LOCALE,
                         "--name", asc_release.asc_value(product.ios["display_name"]),
                         "--description", asc_release.asc_value(product.ios["description"]))
    if "price" in item.steps:
        asc_release.call(asc, "iap", "pricing", "schedules", "create", "--iap-id", iap_id, "--base-territory",
                         PRICE_TERRITORY, "--price", product.price)
    if "availability" in item.steps:
        args = ["iap", "pricing", "availability", "set", "--iap-id", iap_id, "--territories", ",".join(item.territories)]
        if item.new_territories:
            args.append("--available-in-new-territories")
        asc_release.call(asc, *args)
    if "screenshot" in item.steps:
        path = str(plan.root / product.ios["review_screenshot"])
        if item.screenshot_id:
            asc_release.call(asc, "iap", "review-screenshots", "update", "--screenshot-id", item.screenshot_id, "--file", path)
        else:
            asc_release.call(asc, "iap", "review-screenshots", "create", "--iap-id", iap_id, "--file", path)
    wait_until_ready(asc, product, iap_id, sleep=sleep)  # type: ignore[arg-type]


def create_products(asc: Any, plan: Plan, *, sleep: Callable[[float], None] = time.sleep) -> list[str]:
    """Create or finish every planned product. Returns their product IDs."""
    done = []
    for item in plan.to_write():
        verb = "creating" if item.action == "create" else "finishing"
        print(f"{verb} {item.product.label}…")
        try:
            if item.product.kind == "subscription":
                create_subscription(asc, plan, item, sleep=sleep)
            else:
                create_iap(asc, plan, item, sleep=sleep)
        except SystemExit:
            print(
                f"note: {item.product.label} may be left in Missing Metadata; fix the cause and re-run, and the "
                "next run finishes it instead of creating it again",
                file=sys.stderr,
            )
            raise
        print(f"  {item.product.label} is Ready to Submit")
        done.append(item.product.product_id)
    return done


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        fail(f"cannot read {path}: {exc}")
    return {}


def main(argv: list[str] | None = None, *, sleep: Callable[[float], None] = time.sleep) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("plan", "create"))
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    args = parser.parse_args(argv)
    if args.command == "create" and not asc_release.truthy("CREATE_IOS_PRODUCTS"):
        print("App Store product creation skipped — CREATE_IOS_PRODUCTS is off")
        return
    catalog = load_catalog(args.catalog)
    # The plan always runs read-only; asc refuses any write it might attempt.
    plan = plan_products(asc_release.asc_from_environment(read_only=True), catalog, root=ROOT)
    describe(plan)
    if plan.errors:
        fail(f"{len(plan.errors)} catalog product problem(s); nothing was created")
    if args.command == "plan" or not plan.to_write():
        print("nothing to create" if not plan.to_write() else "plan only — nothing was created")
        return
    done = create_products(asc_release.asc_from_environment(), plan, sleep=sleep)
    print(f"{len(done)} product(s) Ready to Submit: {', '.join(done)}")
    print("Add new products to RevenueCat's offerings by hand (store/catalog/revenuecat.json is not synced).")


if __name__ == "__main__":
    main()
