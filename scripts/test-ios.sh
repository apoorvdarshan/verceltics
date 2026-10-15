#!/bin/bash
# Unsigned iOS Simulator unit and UI tests. This script never archives, signs, or
# publishes an app, and it never touches a physical device.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

derived_data="${IOS_TEST_DERIVED_DATA:-$HOME/Library/Developer/Xcode/DerivedData/verceltics-simulator-tests}"
packages="${IOS_TEST_PACKAGES:-$derived_data/SourcePackages}"
results="${IOS_TEST_RESULTS:-$derived_data/TestResults/$(date -u +%Y%m%dT%H%M%SZ)-$$}"
mkdir -p "$results"
if [[ -e "$results/Tests.xcresult" ]]; then
  echo 'Choose an unused IOS_TEST_RESULTS directory; existing results are preserved.' >&2
  exit 1
fi

xcodebuild -version

# A task-owned simulator: an existing developer simulator is never erased or reset.
simulator_id="$(python3 - <<'PY'
import json, subprocess
data = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '--json']))
def version(runtime):
    return tuple(int(part) for part in runtime['version'].split('.'))
runtimes = [r for r in data['runtimes'] if r.get('isAvailable') and
            '.iOS-' in r['identifier'] and version(r) >= (18, 0)]
if not runtimes:
    raise SystemExit('Install an iOS 18 or newer Simulator runtime in Xcode.')
runtime = max(runtimes, key=version)
iphones = [d for d in runtime.get('supportedDeviceTypes', []) if d.get('productFamily') == 'iPhone']
if not iphones:
    raise SystemExit('The newest iOS runtime has no supported iPhone device type.')
device = next((d for d in iphones if d['name'] == 'iPhone 17 Pro'), iphones[-1])
print(subprocess.check_output(['xcrun', 'simctl', 'create', 'Verceltics CI',
      device['identifier'], runtime['identifier']], text=True).strip())
PY
)"

cleanup() {
  # Only the simulator created by this invocation is removed.
  xcrun simctl shutdown "$simulator_id" >/dev/null 2>&1 || true
  xcrun simctl delete "$simulator_id" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Tests run one at a time on one simulator: UI tests relaunch the app and must
# not compete with a second clone for the runner's CPU.
# -collect-test-diagnostics never: otherwise xcodebuild can spend 10 minutes after
# the tests finish timing out while collecting simulator diagnostics. Failure
# screenshots and logs are still kept in the result bundle.
status=0
xcodebuild test \
  -project ios/verceltics.xcodeproj \
  -scheme verceltics \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -destination-timeout 120 \
  -derivedDataPath "$derived_data" \
  -clonedSourcePackagesDirPath "$packages" \
  -onlyUsePackageVersionsFromResolvedFile \
  -resultBundlePath "$results/Tests.xcresult" \
  -enableCodeCoverage YES \
  -parallel-testing-enabled NO \
  -maximum-concurrent-test-simulator-destinations 1 \
  -collect-test-diagnostics never \
  CODE_SIGNING_ALLOWED=NO \
  2>&1 | tee "$results/xcodebuild.log" || status=$?

# Both bundles must have run tests, even when xcodebuild reports success.
if [[ -d "$results/Tests.xcresult" ]]; then
  python3 scripts/ci/check_ios_test_counts.py "$results/Tests.xcresult" vercelticsTests vercelticsUITests || status=1
else
  echo 'error: xcodebuild produced no result bundle' >&2
  status=1
fi
echo "Results: $results/Tests.xcresult"
exit "$status"
