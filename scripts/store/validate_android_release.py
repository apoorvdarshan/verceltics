#!/usr/bin/env python3
"""Validate the Android version, catalog, and tag against source, without building an app."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from release_notes import notes_for_tag

PACKAGE = "com.apoorvdarshan.verceltics"
# Google Play rejects What's New text longer than this, per language.
PLAY_WHATS_NEW_LIMIT = 500


def android_version(gradle: str) -> tuple[str, int]:
    names = re.findall(r'^\s*versionName = "([0-9.]+)"', gradle, flags=re.MULTILINE)
    codes = re.findall(r"^\s*versionCode = ([0-9]+)", gradle, flags=re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("android/app/build.gradle.kts must declare exactly one versionName and versionCode")
    return names[0], int(codes[0])


def play_whats_new(notes: str) -> str:
    """Reviewed release notes as plain Play Store text."""
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", notes)
    text = re.sub(r"^[-*] ", "• ", text, flags=re.MULTILINE).replace("**", "").replace("`", "")
    if len(text) > PLAY_WHATS_NEW_LIMIT:
        raise ValueError(f"Play What's New is limited to {PLAY_WHATS_NEW_LIMIT} characters (notes have {len(text)})")
    return text


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--whats-new-dir", type=Path, help="write the tag's notes as Play whatsnew-en-US here")
    args = parser.parse_args()
    if args.whats_new_dir and not args.tag:
        parser.error("--whats-new-dir needs --tag")
    try:
        version, code = android_version((ROOT / "android/app/build.gradle.kts").read_text())
    except ValueError as exc:
        parser.error(str(exc))
    catalog = json.loads((ROOT / "store/catalog/products.json").read_text())
    ids = [p["id"] for group in ("subscriptions", "non_consumables", "tips") for p in catalog[group]]
    billing = (ROOT / "android/app/src/main/java/com/apoorvdarshan/verceltics/billing/BillingModels.kt").read_text()
    android_ids = set(re.findall(r'const val \w+_ID = "(com\.apoorvdarshan\.[^"]+)"', billing))
    if len(ids) != len(set(ids)) or set(ids) != android_ids:
        parser.error("catalog must exactly match the Android billing product ids, with no duplicates")
    if catalog["package"]["android"] != PACKAGE or any(not p.startswith(PACKAGE + ".") for p in ids):
        parser.error("catalog contains a different app's package or product")
    if args.tag:
        try:
            notes = notes_for_tag(args.tag)
            whats_new = play_whats_new(notes)
        except ValueError as exc:
            parser.error(str(exc))
        if not args.tag.startswith("android-v") or args.tag.removeprefix("android-v") != version:
            parser.error("tag must be android-v<versionName> from android/app/build.gradle.kts")
        if args.whats_new_dir:
            args.whats_new_dir.mkdir(parents=True, exist_ok=True)
            (args.whats_new_dir / "whatsnew-en-US").write_text(whats_new + "\n", encoding="utf-8")
    print(f"Android {version}, versionCode {code}: {len(ids)} catalog products match the billing ids")


if __name__ == "__main__":
    main()
