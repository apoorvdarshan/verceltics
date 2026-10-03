#!/usr/bin/env python3
"""Report iOS switches; live operations require the master switch."""
import argparse
import os
from pathlib import Path

GATES = {
    "STORE_IOS_RELEASE_ENABLED": "ios_release_enabled",
    "STORE_UPLOAD_LISTING": "upload_listing",
    "STORE_UPLOAD_SCREENSHOTS": "upload_screenshots",
    "STORE_SUBMIT_IOS_REVIEW": "submit_ios_review",
}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    # Match the workflow's exact string comparison. Unset or other values are off.
    master = os.environ.get("STORE_IOS_RELEASE_ENABLED") == "true"
    outputs = []
    for name, output in GATES.items():
        configured = os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}
        effective = master and (name == "STORE_IOS_RELEASE_ENABLED" or configured)
        print(f"{name}: configured={str(configured).lower()}, effective={str(effective).lower()}")
        outputs.append(f"{output}={str(effective).lower()}\n")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            handle.writelines(outputs)
