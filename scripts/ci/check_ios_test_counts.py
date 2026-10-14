#!/usr/bin/env python3
"""Fail unless every named test bundle in an .xcresult ran at least one test.

xcodebuild can succeed while a test bundle runs nothing (for example after a
scheme or target change), so CI checks the result bundle as well.

Usage: check_ios_test_counts.py RESULT.xcresult BUNDLE [BUNDLE ...]
"""
from __future__ import annotations

import json
import subprocess
import sys
from collections import Counter


def count_test_cases(node: dict, bundle: str | None, counts: Counter, results: Counter) -> None:
    node_type = node.get("nodeType", "")
    if node_type.endswith("test bundle"):
        bundle = node.get("name")
        counts.setdefault(bundle, 0)
    if node_type == "Test Case" and bundle:
        counts[bundle] += 1
        results[(bundle, node.get("result", "unknown"))] += 1
        return
    for child in node.get("children") or []:
        count_test_cases(child, bundle, counts, results)


def bundle_counts(tests: dict) -> tuple[Counter, Counter]:
    counts: Counter = Counter()
    results: Counter = Counter()
    for node in tests.get("testNodes") or []:
        count_test_cases(node, None, counts, results)
    return counts, results


def problems(counts: Counter, required: list[str]) -> list[str]:
    found = []
    for bundle in required:
        if bundle not in counts:
            found.append(f"{bundle} did not run")
        elif counts[bundle] == 0:
            found.append(f"{bundle} ran zero tests")
    return found


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    result_bundle, required = argv[1], argv[2:]
    output = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", result_bundle],
        check=True, capture_output=True, text=True,
    ).stdout
    counts, results = bundle_counts(json.loads(output))
    for bundle in sorted(counts):
        outcomes = ", ".join(
            f"{outcome.lower()} {number}"
            for (name, outcome), number in sorted(results.items())
            if name == bundle
        )
        print(f"{bundle}: {counts[bundle]} test(s) ({outcomes or 'none'})")
    found = problems(counts, required)
    for problem in found:
        print(f"error: {problem}", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
