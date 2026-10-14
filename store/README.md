# Store release automation

GitHub Actions plumbing for App Store and Google Play releases: binaries,
listing text, What's New, screenshots, and the in-app purchase catalog.

**Nothing reaches a store unless its switch is on.** An `ios-v*` tag runs
[`ios-release.yml`](../.github/workflows/ios-release.yml): GitHub Actions builds,
signs and uploads the iOS binary. An `android-v*` tag runs
[`android-release.yml`](../.github/workflows/android-release.yml): GitHub Actions
builds, verifies and signs the Android app, and uploads it to the Play tracks you
switch on. The two tag prefixes never start each other's pipeline.

**Xcode Cloud was removed for Verceltics on 2026-10-14**, after this pipeline's
validation run passed: its tag-triggered workflow
(`2245A813-CA9D-45A4-8ACA-CF30494D2575`) and its product were deleted in App Store
Connect. Xcode Cloud triggers live in App Store Connect, not in this repository,
so a GitHub switch cannot stop them: never re-create an Xcode Cloud workflow for
`ios-v*` tags, or both pipelines would upload a build for the same tag.

## iOS release flow (`ios-v*` tag)

```
ios-vX.Y tag ─► iOS unit + UI tests
             ─► offline checks: release-script tests, API catalogs, privacy manifest, IAP catalog
             ─► resolve switches (tag must equal MARKETING_VERSION)
  └─ IOS_RELEASE_AUTOMATION off ─► GitHub Release only
  └─ on:
       listing / What's New / screenshot / review switch on
                        ─► check the listing (and screenshots, if uploaded) and What's New
                        ─► read-only App Store Connect preflight: version X.Y exists in a state
                           that allows the enabled steps, with every locale they touch
       IOS_UPLOAD_BUILD ─► next build number from App Store Connect
                        ─► archive Release ─► verify app id/version/build in every bundle
                        ─► cloud-signed export + upload
                        ─► wait until THIS build is VALID (poll 30 s, up to 120 min from upload start)
       IOS_UPLOAD_LISTING / IOS_UPLOAD_WHATS_NEW / IOS_UPLOAD_SCREENSHOTS ─► version metadata
       IOS_SUBMIT_REVIEW ─► select this run's build, read it back, answer export compliance,
                            set release type, attach catalog subscriptions to the draft
                         ─► recheck build + selection
                         ─► submit catalog in-app purchases, then the version (+ subscriptions)
  └─ GitHub Release (only if every enabled step succeeded)
```

**Create the App Store version first.** App Store Connect only accepts listing,
What's New, screenshots and review submission on an App Store version that
already exists, and nothing here creates one. With any of those switches on,
create version X.Y in App Store Connect (it then shows "Prepare for Submission")
before pushing `ios-vX.Y`; otherwise the read-only preflight in the prepare job
stops the run before anything is built or uploaded. A build-only release
(`IOS_UPLOAD_BUILD` alone) needs no App Store version.

A manual **Run workflow** (`workflow_dispatch`) is a validation run: it archives,
signs, exports and validates the IPA with App Store Connect, and never uploads,
edits the listing, submits or creates a GitHub Release. Apple refuses versions it
has already approved, so before bumping the project version, fill in the optional
**marketing_version** input with the next version (e.g. `2.1.1`); the build is
validated as that version without changing the project:

```bash
gh workflow run ios-release.yml --ref main -f marketing_version=2.1.1
```

Submission fails instead of continuing when the build times out, fails
processing, is expired, is ambiguous, belongs to another app/version, was
uploaded before this run, or is not the build selected on the version. An older
build is never substituted. The wait covers build readiness only, not Apple's
review or the public release.

Waiting for the build is its own `wait-for-build` job, which hands the processed
build's id to the `app-store-connect` job. When a later store step fails (for
example App Store Connect changed in between), **Re-run failed jobs** reuses that
processed build instead of waiting again against the original upload deadline;
the store job re-reads the build (version, build number, VALID, unexpired)
before selecting it. Tag runs share one concurrency group and queue
behind each other, so no release run is ever cancelled or dropped.

A rerun also works after a submission went partly through. If the version is
already in a draft review submission (`READY_FOR_REVIEW`, for example after App
Store Connect rejected the submit), the listing and What's New text are updated
where they differ and the version is submitted; screenshots must already match,
because they cannot change in that state. If the version was already submitted
(`WAITING_FOR_REVIEW`), the rerun changes nothing: it passes only when App Store
Connect already holds this run's build and the repository's text and
screenshots, and then the GitHub Release is created.

What's New is the tag's `RELEASE_NOTES.md` section converted to plain text, as
App Store Connect and Google Play show text literally: links and images keep
their text, list markers become `•`, and heading, quote, bold and code markers
are dropped. The App Store limit (4000) and the Play limit (500) apply to that
converted text, and Play receives exactly those characters.

When the build job fails, its logs are kept for 7 days as the
`ios-release-logs-<run>-<attempt>` artifact. This repository is public, so
[`scripts/ci/redact_ids.py`](../scripts/ci/redact_ids.py) first replaces the App
Store Connect key and issuer IDs in text logs, leaves out binary or UTF-16 files
that hold one, and renames files named after one; if any ID were left, nothing
would be uploaded.

## Android release flow (`android-v*` tag)

```
android-vX.Y tag ─► resolve switches (tag must equal versionName)
  └─ ANDROID_RELEASE_AUTOMATION off ─► nothing is built or released
  └─ on:
       reviewed notes (and What's New if on) ─► read-only Play check: versionCode is higher
                                                than every versionCode Google Play has
       ci.yml as the release gate: catalog and store-release tests (Python), Android
                      unit tests, lint, Debug + unsigned Release builds, both emulator test
                      shards (not the website/Worker job, which cannot affect the app)
       ─► check the IAP catalog, and the Play listing/screenshots this run uploads
       ─► build signed AAB + APK
       ─► verify package, versionName/versionCode, and the pinned upload-key signature
       ─► Sigstore signatures + build provenance
       ANDROID_UPLOAD_LISTING / ANDROID_UPLOAD_SCREENSHOTS ─► Play listing
       ANDROID_INTERNAL_TESTING / ANDROID_CLOSED_TESTING / ANDROID_PRODUCTION_ROLLOUT
                      ─► one Play upload to every selected track (+ What's New if on)
                      ─► read each track back: this versionCode, status completed
       GitHub Release (AAB + APK; a prerelease unless production is on), only after
                      every enabled Play step succeeded
```

**Re-run failed jobs** is safe on a release run. The versionCode check runs once,
in `gates`, so it does not object to this run's own upload. Before uploading, the
release job reads the selected tracks: if an earlier attempt already put this
versionCode on every one of them, the upload is skipped and the tracks are read
back again; if only some of them carry it, the run stops (Play rejects a second
upload of a versionCode). An existing GitHub Release for the tag is updated
(title, notes, assets) instead of failing. A rerun rebuilds the AAB and APK from
the same commit and attaches those, with fresh Sigstore signatures and
provenance. **Re-run all jobs** repeats the versionCode check, which fails once
Play has the build.

A manual **Run workflow** (`workflow_dispatch`) is a validation run: it builds
the signed AAB and APK and verifies them (bundletool pinned by SHA-256,
`apksigner`, `jarsigner` plus a check that the signature covers every bundle
entry, all against the pinned upload certificate), and never contacts Google
Play, uploads, signs with Sigstore or creates a GitHub Release. It fails first,
with "upload keystore secrets are not configured", until the two keystore
secrets below exist.

Each track switch is independent: testing tracks never imply production, and
production never falls back to a testing track. The Play upload never runs
without a track (the upload action would default to production). **Google has
not granted Verceltics production access yet**: it needs at least 12 testers
opted in to closed testing for 14 consecutive days (see
[google-play/TESTERS.md](google-play/TESTERS.md)). Until then Play rejects a
production upload, so keep `ANDROID_PRODUCTION_ROLLOUT` off. Uploaded means the
track carries this versionCode as a completed release; Google may still review
it before testers or users get it.

## Switches

Repository **Variables** (Settings → Secrets and variables → Actions → Variables).
Missing means off. Any value other than `true/false/yes/no/on/off/1/0` fails the
run, even when the platform's master switch is off. Copy names from
[`gates.env.example`](gates.env.example).

### iOS

| Variable | When `true` |
|----------|-------------|
| `IOS_RELEASE_AUTOMATION` | Master switch. When off, every iOS store operation below is skipped (the tag still creates a GitHub Release) |
| `IOS_UPLOAD_BUILD` | Build, sign and upload the binary to App Store Connect, then wait for processing |
| `IOS_UPLOAD_LISTING` | App name, subtitle, description, keywords, promotional text, privacy/marketing/support URLs from [`metadata/ios/`](metadata/ios/) |
| `IOS_UPLOAD_WHATS_NEW` | What's New: the tag's `## ios-vX.Y` section in [RELEASE_NOTES.md](../RELEASE_NOTES.md) as plain text (4000 characters at most; fails without one) |
| `IOS_UPLOAD_SCREENSHOTS` | Replace the iPhone 6.7" and iPad 13" screenshots (see below) |
| `IOS_SUBMIT_REVIEW` | Select this run's build and submit the version **and** Ready-to-Submit catalog IAPs / subscriptions for review. Requires `IOS_UPLOAD_BUILD` |
| `IOS_PUBLIC_RELEASE` | Release automatically after approval. Off = manual release. Requires `IOS_SUBMIT_REVIEW` |
| `IOS_BUILD_POLL_SECONDS` | Poll interval, default 30 (10–600) |
| `IOS_BUILD_WAIT_MINUTES` | Total wait from upload start, default 120 (10–300) |

### Android

| Variable | When `true` |
|----------|-------------|
| `ANDROID_RELEASE_AUTOMATION` | Master switch. On: the tag builds and verifies the signed AAB/APK and creates the GitHub Release. Off: the tag builds and releases nothing, and every switch below is ignored |
| `ANDROID_UPLOAD_LISTING` | Play title, short and full description from [`metadata/play/`](metadata/play/) |
| `ANDROID_UPLOAD_WHATS_NEW` | English What's New on this run's Play release: the tag's `## android-vX.Y` section as plain text (500 characters at most). Needs a track below; skipped with a note otherwise |
| `ANDROID_UPLOAD_SCREENSHOTS` | Replace the Play phone screenshots with [`google-play/screenshots/phone/`](google-play/screenshots/phone/) |
| `ANDROID_INTERNAL_TESTING` | Upload this build to Play **internal testing** |
| `ANDROID_CLOSED_TESTING` | Upload this build to Play **closed testing** (the `alpha` track) |
| `ANDROID_PRODUCTION_ROLLOUT` | Upload this build to Play **production**, live. Play rejects it until Google grants production access |

### Migrating from the earlier `STORE_*` variables

The Xcode Cloud-era variables are no longer read; delete them after setting the new ones.

| Earlier variable | Replacement |
|------------------|-------------|
| `STORE_IOS_RELEASE_ENABLED` | `IOS_RELEASE_AUTOMATION` |
| `STORE_UPLOAD_LISTING` (listing and What's New) | `IOS_UPLOAD_LISTING` and `IOS_UPLOAD_WHATS_NEW` |
| `STORE_UPLOAD_SCREENSHOTS` | `IOS_UPLOAD_SCREENSHOTS` |
| `STORE_SUBMIT_IOS_REVIEW` (waited for the Xcode Cloud build) | `IOS_SUBMIT_REVIEW`, plus `IOS_UPLOAD_BUILD` because GitHub now builds the binary |
| — (release timing was set in App Store Connect) | `IOS_PUBLIC_RELEASE` |
| `STORE_ANDROID_RELEASE_ENABLED` | `ANDROID_RELEASE_AUTOMATION` |
| `STORE_PLAY_TRACK=internal` / `closed` / `none` | `ANDROID_INTERNAL_TESTING` / `ANDROID_CLOSED_TESTING` / neither |
| `STORE_UPLOAD_WHATS_NEW` (Android) | `ANDROID_UPLOAD_WHATS_NEW` |

## Secrets

iOS:

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API key: build numbers, cloud-managed distribution signing, upload, build readiness, listing, review |
| `APP_STORE_CONNECT_ISSUER_ID` | ASC issuer |
| `APP_STORE_CONNECT_API_KEY_P8` | ASC private key (`.p8` body) |
| `IOS_DEV_SIGNING_P12_BASE64` | CI Apple Development identity (base64 `.p12`; certificate "Apple Development: Created via API", ID `NVNGDQGCKK`, expires 2027-10-14) that signs the archive. App Store signing happens at export with Apple's cloud-managed distribution certificate |
| `IOS_DEV_SIGNING_P12_PASSWORD` | Password for that `.p12` |

The same CI identity signs the archives of Fud AI, Abysm, iitjee, Quit All,
Scowld and Sesslet: Apple allows one API-created development certificate per API
key. Its private key exists only in those repositories' `IOS_DEV_SIGNING_*`
secrets. **Rotation:** before it expires (2027-10-14) or after it is revoked,
revoke it (App Store Connect API, `DELETE /v1/certificates/<id>`), create a new
one (`certificateType: DEVELOPMENT`), and replace both `IOS_DEV_SIGNING_*`
secrets in every one of those repositories, then rerun a validation run.

Android:

| Secret | Used for |
|--------|----------|
| `ANDROID_KEYSTORE_BASE64` | `base64` of the Play upload keystore (`verceltics-upload.jks`, alias `upload`) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password, which is also the key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | a Google Play publisher service-account key with release access to `com.apoorvdarshan.verceltics`. Every release run uses it read-only to confirm the versionCode is new; uploads and listing changes use it only with their switches on |

Signing and Play credentials only ever reach the disk under `$RUNNER_TEMP`, readable
by the job's user alone (`umask 077`), never in the checkout, and are removed right
after use: the upload keystore and its password file after verification, and the
Play key file the upload action reads right after the upload.

The upload certificate is pinned: `EXPECTED_UPLOAD_CERT_SHA256` in
[`scripts/store/android_release.py`](../scripts/store/android_release.py)
(`fe735c7c…61f1ef`) must match the keystore in `ANDROID_KEYSTORE_BASE64`, so a
replaced keystore fails verification before anything is signed or published.
After a Play upload-key reset, update that constant together with the secret.

Keys are encrypted in GitHub Secrets and never belong in this repository.
Offline checks need none of them.

## Scripts

| Script | Role |
|--------|------|
| [`scripts/store/ios_release.py`](../scripts/store/ios_release.py) | iOS switches, build number, wait for this run's build |
| [`scripts/store/asc_release.py`](../scripts/store/asc_release.py) | ASC listing, App Info, What's New, screenshots, build selection, export compliance, IAPs, submit for review (`--dry-run` = local checks only, `--preflight` = read-only App Store Connect checks) |
| [`scripts/store/ios_metadata.py`](../scripts/store/ios_metadata.py) | Check the App Store listing and/or screenshots; prepare them with the tag's What's New as plain text |
| [`scripts/store/export_ios_listing.py`](../scripts/store/export_ios_listing.py) | Read-only: export the live App Store listing into `store/metadata/ios/` |
| [`scripts/store/android_release.py`](../scripts/store/android_release.py) | Android switches, versionCode check against Play, AAB/APK identity + pinned signature checks, pre-upload track state, Play track read-back |
| [`scripts/store/play_listing.py`](../scripts/store/play_listing.py) | Check, prepare and upload the Play listing, phone screenshots and What's New |
| [`scripts/store/validate_catalog.py`](../scripts/store/validate_catalog.py) | `catalog/products.json` against StoreKit and the Android billing IDs |
| [`scripts/store/print_gates.py`](../scripts/store/print_gates.py) | Print the configured switches |
| [`scripts/store/test_ios_release.py`](../scripts/store/test_ios_release.py) | Offline tests: disabled/invalid switches, tag mismatch, validation mode, build wait timeout/failure/ambiguity/mismatch/old build/unknown upload time, selection read-back, purchases only after the recheck, version states and reruns, preflight, token refresh, listing, screenshots, catalog |
| [`scripts/store/test_android_release.py`](../scripts/store/test_android_release.py) | Offline tests: switches, track selection, wrong app/version/key, pinned upload certificate, unsigned bundle entries, versionCode and pre-upload checks, Play listing and read-back |
| [`scripts/store/test_store_assets.py`](../scripts/store/test_store_assets.py) | The repository's App Store and Play listings and screenshots (pull requests and main; a release checks only what it uploads) |
| [`scripts/ci/redact_ids.py`](../scripts/ci/redact_ids.py) | Removes App Store Connect key/issuer IDs from failure logs before they are kept |
| [`scripts/release_notes.py`](../scripts/release_notes.py) | The tag's reviewed notes: GitHub Release body and (as plain text) What's New |

Release workflows install [`requirements-store.txt`](../requirements-store.txt)
(PyJWT, Google API client; hash-pinned) into the pinned Python 3.13 from
`actions/setup-python`, never the runner's system Python, and only for steps
that call a store. Store steps run unbuffered (`python3 -u`), so the build wait
and uploads log as they go.
`asc_release.py` reads `UPLOAD_LISTING`, `UPLOAD_WHATS_NEW`, `UPLOAD_SCREENSHOTS`,
`SUBMIT_IOS_REVIEW` and `PUBLIC_RELEASE`, set by `ios-release.yml` from the
resolved iOS switches. `play_listing.py upload` reads `UPLOAD_LISTING` and
`UPLOAD_SCREENSHOTS`, set by `android-release.yml` from the resolved Android switches.

## Metadata layout

```
store/metadata/
  ios/en-US/        name, subtitle, description, keywords, promotional_text, *_url (.txt)
  play/en-US/       title, short_description, full_description (.txt)
marketing/mockups ios/      App Store iPhone 6.7" screenshots (1290×2796), upload order = file name
marketing/mockups ipad/     App Store iPad 13" screenshots (2048×2732)
store/google-play/screenshots/phone/   Play phone screenshots (1080×1920)
RELEASE_NOTES.md            ## ios-vX.Y / ## android-vX.Y → GitHub Release + What's New
```

`store/metadata/ios/` was exported from the live 2.1 listing (en-US is the only
locale) with `export_ios_listing.py`; after editing the listing in App Store
Connect, export again so the repository stays the source of truth. The upload
covers every locale folder; App Info fields (name, subtitle, privacy URL) are
only editable while a new version is being prepared, and the run fails rather
than silently skipping a change it cannot make. Screenshot replacement deletes
and re-uploads one image at a time, so a failed run can leave a mixed set:
inspect App Store Connect before rerunning.

The catalog explicitly declares `uses_non_exempt_encryption: false`: the app
uses only Apple's HTTPS, Keychain and CryptoKit implementations. Because the
Info.plist does not answer the export-compliance question, review submission
answers it on this run's build from the catalog and refuses a build that
declares otherwise. Reassess before adding other cryptography; see
[Apple's encryption guidance](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).

### iOS subscription submission caveat

`IOS_SUBMIT_REVIEW` submits the App Store version and every
`READY_TO_SUBMIT` (or `DEVELOPER_REJECTED`) subscription in
[`catalog/products.json`](catalog/products.json) in one step. As of the 2026 App
Store Connect workflow, subscriptions attach to the review submission through
the `subscriptionVersion` relationship on Review Submission Items, so a
first-time subscription must ship with the version. The lifetime purchase and
tips go through `inAppPurchaseSubmissions`, which submits each one immediately,
so they are sent only after the final build and selection recheck, right before
the version. (Apple marks that endpoint deprecated in favour of review
submission items; it still works, and every catalog purchase is approved
today.) Products outside the catalog are never submitted, and the version is
not submitted if a required product cannot be added. If the version is already
`WAITING_FOR_REVIEW`, a late product is submitted on its own; a retry never
duplicates items or ships an unrelated app version.

## Release procedure

1. Bump the version (`MARKETING_VERSION` in Xcode / `versionName` and `versionCode`
   in `android/app/build.gradle.kts`). iOS build numbers come from App Store Connect;
   the Android versionCode must be higher than every versionCode on Google Play
   (the release checks this before building).
2. Add reviewed `## ios-vX.Y` / `## android-vX.Y` notes to `RELEASE_NOTES.md`
   (required for What's New; otherwise the GitHub Release uses generated notes).
3. iOS, with any listing, What's New, screenshot or review switch on: **create the
   X.Y App Store version in App Store Connect** (Prepare for Submission). The
   preflight stops the run before building when it is missing.
4. Run the validation run (**Run workflow**) to prove signing still works.
5. Turn on only the switches you want; try Android on `ANDROID_INTERNAL_TESTING`
   first, and leave `IOS_SUBMIT_REVIEW`, `IOS_PUBLIC_RELEASE` and
   `ANDROID_PRODUCTION_ROLLOUT` off until you intend that release.
6. Push the `ios-vX.Y` / `android-vX.Y` tag. A tag can be used once.

## Offline checks

```bash
./scripts/test.sh                                   # both release suites and the store assets
./scripts/test.sh --release-gate                    # what an Android release gate runs
python3 scripts/store/print_gates.py
python3 scripts/store/validate_catalog.py
python3 scripts/store/ios_metadata.py check
python3 scripts/store/play_listing.py check
python3 scripts/store/ios_metadata.py prepare --out /tmp/store-meta --tag ios-v2.1
UPLOAD_LISTING=true UPLOAD_WHATS_NEW=true SUBMIT_IOS_REVIEW=true \
  python3 scripts/store/asc_release.py --dry-run --metadata-dir /tmp/store-meta --build-id DRY --build-number 1
```

These never build, sign, or contact a store. The **Store automation (dry-run)**
workflow runs them for every change to store files, scripts or release workflows,
and `ci.yml` runs `./scripts/test.sh` on every push to main and on pull requests
that touch `tests/`, `scripts/` or the iOS app.

## Android closed beta

Android **1.0 (44)** is in Google Play closed testing (Alpha track), so the next
`android-v*` release needs a higher versionCode (the release checks this first).
The testers, countries, review status, and the `VERCELTICSBETA` promo code are
recorded in [google-play/TESTERS.md](google-play/TESTERS.md) and
[google-play/PROMO.md](google-play/PROMO.md).
