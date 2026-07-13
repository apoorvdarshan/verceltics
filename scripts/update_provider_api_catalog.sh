#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

curl -fsSL https://open-api.netlify.com/swagger.json -o "$WORK/netlify.json"
curl -fsSL https://api-docs.render.com/openapi/render-public-api-1.json -o "$WORK/render.json"
curl -fsSL https://machines-api-spec.fly.dev/openapi.json -o "$WORK/fly.json"
curl -fsSL 'https://firebasehosting.googleapis.com/$discovery/rest?version=v1beta1' -o "$WORK/firebase.json"
curl -fsSL -H 'Accept: application/vnd.heroku+json; version=3' https://api.heroku.com/schema -o "$WORK/heroku.json"
curl -fsSL https://developer.godaddy.com/swagger/swagger_domains.json -o "$WORK/godaddy.json"
curl -fsSL https://raw.githubusercontent.com/boto/botocore/develop/botocore/data/amplify/2017-07-25/service-2.json -o "$WORK/amplify.json"
curl -fsSL https://raw.githubusercontent.com/digitalocean/openapi/main/specification/DigitalOcean-public.v2.yaml -o "$WORK/digitalocean.yaml"
curl -fsSL https://namedotcom-cdn.name.tools/api-info/namecom.api.yaml -o "$WORK/name.yaml"
curl -fsSL https://docs.spaceship.dev/ -o "$WORK/spaceship.html"
for page in certificate domains linkedzones livedns mailbox email billing comment organization simplehosting gandicloud template; do
  curl -fsSL "https://api.gandi.net/docs/$page/" -o "$WORK/gandi-$page.html"
done

ruby -rjson -ryaml -e 'puts JSON.generate(YAML.safe_load(File.read(ARGV[0]), aliases: true))' "$WORK/digitalocean.yaml" > "$WORK/digitalocean.json"
ruby -rjson -ryaml -e 'puts JSON.generate(YAML.safe_load(File.read(ARGV[0]), aliases: true))' "$WORK/name.yaml" > "$WORK/name.json"

python3 - "$WORK/spaceship.html" "$WORK/spaceship.json" <<'PY'
import json
import pathlib
import sys

text = pathlib.Path(sys.argv[1]).read_text()
start = text.index('{"openapi":"3.0.0"')
spec, _ = json.JSONDecoder().raw_decode(text[start:])
pathlib.Path(sys.argv[2]).write_text(json.dumps(spec))
PY

python3 "$ROOT/scripts/generate_provider_api_catalog.py" \
  "$WORK" \
  "$ROOT/ios/verceltics/Resources/ProviderAPICatalog.json"
