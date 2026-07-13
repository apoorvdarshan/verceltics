#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py' -v
