#!/bin/sh
# Repository checks that need no app build: provider catalogs, the privacy
# manifest, and the store release scripts (all offline).
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py' -v
python3 -m unittest discover -s "$ROOT/scripts/store" -p 'test_*.py'
