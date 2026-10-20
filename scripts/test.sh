#!/bin/sh
# Repository checks that need no app build: the provider API catalogs, the
# privacy manifest and AuthManager (all offline). The store release tooling, its
# tests and the store asset checks live in apoorvdarshan/store-release, and the
# Store checks workflow runs them (see store/README.md).
#
#   scripts/test.sh                 pull requests, main, local runs
#   scripts/test.sh --release-gate  what an Android release gate runs (the same
#                                   tests; the flag is kept for ci.yml's callers)
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
case "${1:-}" in
  ""|--release-gate) ;;
  *) echo "usage: $0 [--release-gate]" >&2; exit 2 ;;
esac

python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py' -v
