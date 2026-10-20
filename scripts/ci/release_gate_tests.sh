#!/bin/sh
# ios.prepare.checks: the repository tests every iOS release prepare ran (the provider API
# catalogs, the privacy manifest, AuthManager), as the former ios-release.yml prepare step
# ran them (`python3 -m unittest discover -s tests -p 'test_*.py'`). The store tooling's own
# tests now run in store-release, and the catalog check is store-release's `catalog check`.
set -eu
python3 -m unittest discover -s tests -p 'test_*.py'
