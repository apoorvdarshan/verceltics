#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

git clone --depth 1 https://github.com/cloudflare/api-schemas.git "$TEMP_DIR/api-schemas"
COMMIT=$(git -C "$TEMP_DIR/api-schemas" rev-parse HEAD)
python3 "$ROOT/scripts/generate_cloudflare_api_catalog.py" \
  "$TEMP_DIR/api-schemas/openapi.json" \
  "$ROOT/ios/verceltics/Resources/CloudflareAPICatalog.json" \
  --source-commit "$COMMIT"
