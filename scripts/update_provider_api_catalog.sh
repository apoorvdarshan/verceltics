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
curl -fsSL https://porkbun.com/api/json/v3/spec -o "$WORK/porkbun.json"
curl -fsSL https://raw.githubusercontent.com/boto/botocore/develop/botocore/data/amplify/2017-07-25/service-2.json -o "$WORK/amplify.json"
git clone --depth 1 --quiet https://github.com/digitalocean/openapi.git "$WORK/digitalocean-openapi"
curl -fsSL https://namedotcom-cdn.name.tools/api-info/namecom.api.yaml -o "$WORK/name.yaml"
curl -fsSL https://docs.spaceship.dev/ -o "$WORK/spaceship.html"
for page in certificate domains linkedzones livedns mailbox email billing comment organization simplehosting gandicloud template; do
  curl -fsSL "https://api.gandi.net/docs/$page/" -o "$WORK/gandi-$page.html"
done

ruby -rjson -ryaml -rdate - "$WORK/digitalocean-openapi/specification/DigitalOcean-public.v2.yaml" > "$WORK/digitalocean.json" <<'RUBY'
def fragment(document, value)
  return document if value.nil? || value.empty?
  value.delete_prefix('/').split('/').reduce(document) do |current, part|
    current.fetch(part.gsub('~1', '/').gsub('~0', '~'))
  end
end

def bundle(value, base_directory, root_document, seen = [])
  case value
  when Array
    value.map { |item| bundle(item, base_directory, root_document, seen) }
  when Hash
    reference = value['$ref']
    if reference
      reference_key = "#{base_directory}|#{reference}"
      return {} if seen.include?(reference_key)
      next_seen = seen + [reference_key]
      if reference.start_with?('#/')
        return bundle(fragment(root_document, reference.delete_prefix('#')), base_directory, root_document, next_seen)
      end
      file_name, pointer = reference.split('#', 2)
      file_path = File.expand_path(file_name, base_directory)
      document = YAML.safe_load(File.read(file_path), permitted_classes: [Date, Time], aliases: true)
      return bundle(fragment(document, pointer), File.dirname(file_path), document, next_seen)
    end
    value.to_h { |key, item| [key, bundle(item, base_directory, root_document, seen)] }
  else
    value
  end
end

path = File.expand_path(ARGV.fetch(0))
document = YAML.safe_load(File.read(path), permitted_classes: [Date, Time], aliases: true)
puts JSON.generate(bundle(document, File.dirname(path), document))
RUBY
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
