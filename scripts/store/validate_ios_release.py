#!/usr/bin/env python3
"""Validate the iOS catalog and tag against source, without building an app."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from release_notes import notes_for_tag


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    args = parser.parse_args()
    project = (ROOT / "ios/verceltics.xcodeproj/project.pbxproj").read_text()
    versions = set(re.findall(r"MARKETING_VERSION = ([0-9.]+);", project))
    builds = set(re.findall(r"CURRENT_PROJECT_VERSION = ([0-9]+);", project))
    if len(versions) != 1 or len(builds) != 1:
        parser.error("all iOS configurations must agree on marketing version and build")
    catalog = json.loads((ROOT / "store/catalog/products.json").read_text())
    ids = [p["id"] for group in ("subscriptions", "non_consumables", "tips") for p in catalog[group]]
    storekit = json.loads((ROOT / "ios/verceltics/Paywall/Products.storekit").read_text())
    reference_ids = {p["productID"] for p in storekit["products"]}
    reference_ids.update(p["productID"] for g in storekit["subscriptionGroups"] for p in g["subscriptions"])
    if len(ids) != len(set(ids)) or set(ids) != reference_ids:
        parser.error("catalog must exactly match StoreKit products, with no duplicates")
    if catalog["package"]["ios"] != "com.apoorvdarshan.verceltics" or any(not p.startswith("com.apoorvdarshan.verceltics.") for p in ids):
        parser.error("catalog contains a different app's bundle or product")
    if args.tag:
        try:
            notes_for_tag(args.tag)
        except ValueError as exc:
            parser.error(str(exc))
        if args.tag.removeprefix("ios-v") != next(iter(versions)):
            parser.error("tag version must match the iOS MARKETING_VERSION")
    print(f"iOS {next(iter(versions))}, build {next(iter(builds))}: {len(ids)} catalog products match StoreKit")


if __name__ == "__main__":
    main()
