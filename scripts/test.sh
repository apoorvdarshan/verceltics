#!/bin/sh
# Repository checks that need no app build: provider catalogs, the privacy
# manifest, the CI helper scripts, the store release scripts, and the store
# listing text and screenshots (all offline).
#
#   scripts/test.sh                 everything (pull requests, main, local runs)
#   scripts/test.sh --release-gate  everything except the repository's store
#                                   listing and screenshots: a release run checks
#                                   only the assets its switches upload
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
case "${1:-}" in
  "") store_tests='test_*.py' ;;
  --release-gate) store_tests='test_*_release.py' ;;
  *) echo "usage: $0 [--release-gate]" >&2; exit 2 ;;
esac

python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py' -v
python3 -m unittest discover -s "$ROOT/scripts/ci" -p 'test_*.py'
python3 -m unittest discover -s "$ROOT/scripts/store" -p "$store_tests"
