# Store release automation

GitHub Actions plumbing for App Store and Google Play releases: binaries,
listing text, What's New, screenshots, App Store header and search results
images, and the in-app purchase catalog.

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
             ─► resolve switches (tag must equal MARKETING_VERSION); a run that builds also
                checks the app's export compliance declaration
  └─ IOS_RELEASE_AUTOMATION off ─► GitHub Release only
  └─ on:
       listing / What's New / screenshot / review switch on
                        ─► check only what this run uploads: the listing, screenshots and
                           What's New, each only with its switch on
       IOS_UPLOAD_CREATIVE_ASSETS ─► check the header / search results images
                                     (size, format, no alpha, well-formed file)
       IOS_CREATE_VERSION ─► create App Store version X.Y when it is missing (an existing one
                             is left alone)
       listing / What's New / screenshot / creative asset / review switch on
                        ─► read-only App Store Connect preflight: version X.Y exists in a state
                           that allows the enabled steps, with every locale they touch; a
                           READY_FOR_REVIEW version's draft (with IOS_SUBMIT_REVIEW) and the
                           images' Asset Library state (with IOS_UPLOAD_CREATIVE_ASSETS)
       IOS_UPLOAD_BUILD ─► next build number from App Store Connect
                        ─► archive Release ─► verify app id/version/build in every bundle,
                           and the app's export compliance declaration
                        ─► cloud-signed export + upload
                        ─► wait until THIS build is VALID (poll 30 s, up to 120 min from upload start)
       IOS_SUBMIT_REVIEW ─► pick and check the version's draft review submission
                            (read-only, before any write) and reserve it
       IOS_UPLOAD_LISTING / IOS_UPLOAD_WHATS_NEW / IOS_UPLOAD_SCREENSHOTS ─► version metadata
                            (screenshots as RGB copies)
       IOS_UPLOAD_CREATIVE_ASSETS ─► new images: Asset Library upload, then App Review
                                     in a submission of their own
                                  ─► approved images: placed on the version (en-US)
       IOS_SUBMIT_REVIEW ─► select this run's build, read it back, confirm export compliance,
                            set release type, attach catalog subscriptions to the draft
                         ─► recheck build + selection
                         ─► submit catalog in-app purchases, then the version (+ subscriptions)
  └─ GitHub Release (only if every enabled step succeeded)
```

**The App Store version must exist first.** App Store Connect only accepts
listing, What's New, screenshots, creative assets and review submission on an
App Store version that already exists. With `IOS_CREATE_VERSION` on, a tag run
creates version X.Y (it then shows "Prepare for Submission") when it is missing,
after the offline checks and before the preflight; an existing X.Y is left
alone. It refuses,
before anything is built and without changing anything, when another iOS version
is still on its way to the store (any state but Ready for Distribution, replaced
or removed), when X.Y is not newer than every existing version, or when the
bundle id resolves to another app. A version created by a run whose later steps
fail stays in Prepare for Submission, and the next run for the same tag reuses
it. With the switch off and any of those switches on, create version X.Y in App
Store Connect before pushing `ios-vX.Y`; otherwise the read-only preflight in the
prepare job stops the run before anything is built or uploaded. A build-only
release (`IOS_UPLOAD_BUILD` alone) needs no App Store version.

**A store asset the run does not upload never blocks it**, on either platform.
The listing text is read and checked only with `IOS_UPLOAD_LISTING` /
`ANDROID_UPLOAD_LISTING` on (otherwise `prepare --no-listing` hands the store step
What's New alone), screenshots only with their screenshot switch, the tag's
What's New only with its What's New switch, and the header and search results
images only with `IOS_UPLOAD_CREATIVE_ASSETS`. Pull requests that change store
files, and every push to main, still check every asset in the repository.

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
screenshots, and then the GitHub Release is created. Header and search results
images already in the Asset Library are never uploaded again, an image already
placed is left alone, and an interrupted image review is finished in the draft
it left (see [below](#header-and-search-results-images-ios)).

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
| `IOS_CREATE_VERSION` | Create the tag's App Store version (Prepare for Submission) when App Store Connect does not have it, before the preflight. An existing version is left alone. Refuses, before anything is built, when another version is still on its way to the store (anything but Ready for Distribution, replaced or removed) or the tag is not newer than every existing version. Tag runs only |
| `IOS_UPLOAD_BUILD` | Build, sign and upload the binary to App Store Connect, then wait for processing |
| `IOS_UPLOAD_LISTING` | App name, subtitle, description, keywords, promotional text, privacy/marketing/support URLs from [`metadata/ios/`](metadata/ios/) |
| `IOS_UPLOAD_WHATS_NEW` | What's New: the tag's `## ios-vX.Y` section in [RELEASE_NOTES.md](../RELEASE_NOTES.md) as plain text (4000 characters at most; fails without one) |
| `IOS_UPLOAD_SCREENSHOTS` | Replace the iPhone 6.7", iPad 13", iPhone Duo and Apple Watch screenshots, staged as RGB (see below) |
| `IOS_UPLOAD_CREATIVE_ASSETS` | Product page header and search results images from `store/creative/ios/`, checked (size, format, no alpha) before the build. A new image is uploaded to the Asset Library once and sent to App Review in a submission of its own; an approved image is placed on the version's en-US page while the version can be edited (see [below](#header-and-search-results-images-ios)). Off: never read |
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
| [`scripts/store/ios_release.py`](../scripts/store/ios_release.py) | iOS switches, export compliance declaration (runs that build), build number, wait for this run's build |
| [`scripts/store/asc_release.py`](../scripts/store/asc_release.py) | ASC version creation (`--create-version`), listing, App Info, What's New, 6.7" iPhone / 13" iPad / iPhone Duo / Apple Watch screenshots (uploaded as RGB copies), header and search results images, build selection, export compliance, IAPs, submit for review (`--dry-run` = local checks only, `--preflight` = read-only App Store Connect checks) |
| [`scripts/store/ios_metadata.py`](../scripts/store/ios_metadata.py) | Check the App Store listing and/or screenshots (iPhone Duo and Apple Watch too, when they have frames); prepare the listing (unless `--no-listing`) with the tag's What's New as plain text |
| [`scripts/store/export_ios_listing.py`](../scripts/store/export_ios_listing.py) | Read-only: export the live App Store listing into `store/metadata/ios/` |
| [`scripts/store/android_release.py`](../scripts/store/android_release.py) | Android switches, versionCode check against Play, AAB/APK identity + pinned signature checks, pre-upload track state, Play track read-back |
| [`scripts/store/play_listing.py`](../scripts/store/play_listing.py) | Check, prepare and upload the Play listing, phone screenshots and What's New |
| [`scripts/store/validate_catalog.py`](../scripts/store/validate_catalog.py) | `catalog/products.json` against StoreKit and the Android billing IDs |
| [`scripts/store/print_gates.py`](../scripts/store/print_gates.py) | Print the configured switches |
| [`scripts/store/check_store_tooling.py`](../scripts/store/check_store_tooling.py) | Offline check of the installed store tooling: the versions `requirements-store.in` pins (Pillow included), an ES256 App Store Connect token, the Google Play API client (store-automation.yml) |
| [`scripts/store/test_ios_release.py`](../scripts/store/test_ios_release.py) | Offline tests: disabled/invalid switches, tag mismatch, validation mode, build wait timeout/failure/ambiguity/mismatch/old build/unknown upload time, selection read-back, purchases only after the recheck, version states and reruns, version creation, export compliance, preflight, token refresh, listing (never read with its switch off), screenshots, the RGB copies and the screenshot alpha guard, iPhone Duo and Apple Watch sets, header and search results images (upload, review, placement, drafts), catalog. The RGB tests need Pillow: they skip without it, except in the store-tooling job |
| [`scripts/store/test_android_release.py`](../scripts/store/test_android_release.py) | Offline tests: switches, track selection, wrong app/version/key, pinned upload certificate, unsigned bundle entries, versionCode and pre-upload checks, Play listing and read-back |
| [`scripts/store/test_store_assets.py`](../scripts/store/test_store_assets.py) | The repository's App Store and Play listings and screenshots, and the header and search results images (pull requests and main; a release checks only what it uploads) |
| [`scripts/ci/redact_ids.py`](../scripts/ci/redact_ids.py) | Removes App Store Connect key/issuer IDs from failure logs before they are kept |
| [`scripts/release_notes.py`](../scripts/release_notes.py) | The tag's reviewed notes: GitHub Release body and (as plain text) What's New |

Release workflows install [`requirements-store.txt`](../requirements-store.txt)
(PyJWT, Google API client, Pillow; hash-pinned) into the pinned Python 3.13 from
`actions/setup-python`, never the runner's system Python, and only for steps
that call a store; the iOS preflight and store step use Pillow to stage the RGB
screenshot copies. Store steps run unbuffered (`python3 -u`), so the build wait
and uploads log as they go. Nothing else installs it, so whenever the **Store
automation (dry-run)** workflow runs, including for every change to
`requirements-store.in`/`.txt` (all that a Dependabot pip update touches), its
`store-tooling` job installs it the same way and uses it offline with
[`check_store_tooling.py`](../scripts/store/check_store_tooling.py), runs the iOS
release tests again with `STORE_TESTS_NEED_PILLOW` set (so the Pillow tests
cannot skip), and stages the repository's screenshots as a dry run. A bad update
fails there, before it is merged, instead of at the next release tag.
`asc_release.py` reads `CREATE_IOS_VERSION`, `UPLOAD_LISTING`, `UPLOAD_WHATS_NEW`,
`UPLOAD_SCREENSHOTS`, `UPLOAD_CREATIVE_ASSETS`, `SUBMIT_IOS_REVIEW` and
`PUBLIC_RELEASE`, set by `ios-release.yml` from the resolved iOS switches.
`play_listing.py upload` reads `UPLOAD_LISTING` and `UPLOAD_SCREENSHOTS`, set by
`android-release.yml` from the resolved Android switches.

## Metadata layout

```
store/metadata/
  ios/en-US/        name, subtitle, description, keywords, promotional_text, *_url (.txt)
  play/en-US/       title, short_description, full_description (.txt)
store/app-store/screenshots/iphone/   App Store iPhone 6.7" screenshots (1290×2796), upload order = file name
store/app-store/screenshots/ipad/     App Store iPad 13" screenshots (2048×2732)
store/app-store/screenshots/iphone-duo/  optional: iPhone Duo screenshots, outer screen 1398×2034
                                         (the same set also takes inner-screen 2007×2853 frames)
store/app-store/screenshots/watch/    optional: Apple Watch screenshots, by frame size
                                      (416×496 APP_WATCH_SERIES_10, and the other Watch sizes)
store/creative/ios/header/            one product page header PNG, exactly 3840×1646 (empty for now)
store/creative/ios/search-results/    one search results PNG or JPEG, 3:2, 1920 to 3840 wide (empty for now)
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

App Store screenshots go up as RGB copies, staged in a temporary folder. App
Store Connect refuses a PNG that stores an alpha channel, even when every pixel
is opaque, and the committed screenshots are RGBA, so staging lays transparent
pixels over black once and keeps the colour profile (ICC profile, sRGB, gamma and
chromaticity chunks). The files in `store/app-store/screenshots/` are only read,
never rewritten, and never resized: `ios_metadata.py` accepts only App Store
sizes. Without Pillow, staging copies the files as they are and refuses one that
stores alpha. Right before upload, `asc_release.py` refuses any staged file that
still stores alpha (PNG colour type 4 or 6, or a `tRNS` chunk) or is not a
well-formed PNG (complete chunks with matching CRCs, image data of the size its
header gives, a final `IEND`), before anything is sent. While the version is in a
review submission, the live screenshots are compared with these copies, so a set
uploaded before copies were staged (such as the committed RGBA files) counts as
different.

iPhone Duo and Apple Watch screenshots are optional. With PNGs in
`store/app-store/screenshots/iphone-duo/`, they go up as the `APP_IPHONE_DUO`
set after the iPad set. Apple Watch screenshots go with the other sets when
`store/app-store/screenshots/watch/` holds PNGs. Each frame's size picks its set:
416×496 `APP_WATCH_SERIES_10`, 422×514 or 410×502 `APP_WATCH_ULTRA`, 396×484
`APP_WATCH_SERIES_7`, 368×448 `APP_WATCH_SERIES_4`, 312×390 `APP_WATCH_SERIES_3`;
any other size fails before anything is uploaded. Like every set, a Watch set is
replaced in an editable version and only compared while the version is in a
review submission. Sets the repository has no frames for (an iPhone Duo set, or
an `APP_WATCH_ULTRA` set uploaded by hand) are left alone; remove them in App
Store Connect if they should go. Without frames in either folder, no iPhone Duo
or Watch set is touched. `APP_IPHONE_DUO` is not in Apple's OpenAPI spec yet,
but App Store Connect accepts it.

### Export compliance

The app declares its export compliance answer in its build settings
(`INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO` on the app target, so every
build's `Info.plist` carries `ITSAppUsesNonExemptEncryption = false`), and the
catalog declares the same answer (`uses_non_exempt_encryption: false`). Without
the key, App Store Connect asks about each build by hand, and review submission
fails with `usesNonExemptEncryption` missing until someone answers.

`NO` is right while the app uses only exempt encryption: HTTPS (`URLSession` and
the RevenueCat SDK), the Keychain for credentials, iOS Data Protection for the
site cache, and CryptoKit for hashing and request signing (SHA-256 for the
Google sign-in PKCE challenge and cache keys, HMAC-SHA256 for AWS Signature
Version 4). If the app ever adds its own encryption, set both to `YES` / `true`,
add `INFOPLIST_KEY_ITSEncryptionExportComplianceCode` with the code App Store
Connect issues for the app's encryption documentation, and see
[Apple's encryption guidance](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).

Every release and validation run checks it twice: `ios_release.py gates` reads
each configuration (Debug and Release) of the app target, and the catalog,
before anything is built, and the build job reads the archived app's
`Info.plist`. Other targets' settings never count for the app. A key missing
from either configuration, unknown or different values, `YES` without the code
in that configuration, or a project and catalog that disagree fail the run.
Review submission then confirms this run's build carries the catalog's answer
(answering it from the catalog only if the build arrived without one) and
refuses a build that declares otherwise.

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

## Header and search results images (iOS)

With `IOS_UPLOAD_CREATIVE_ASSETS` on, the images in `store/creative/ios/` go to
the App Store product page: `header/` takes one PNG of exactly 3840×1646, and
`search-results/` one PNG or JPEG in 3:2, from 1920×1280 to 3840×2560. Neither
may store alpha. `prepare` checks sizes, formats, alpha and that each file is
a complete, well-formed image before anything is built, then (read-only, in the
preflight) each image's Asset Library state the way the store step plans it, so
an image the version cannot take now fails before the build. A slot without an
image is left alone; with neither image the run fails. Both folders are empty
for now (each holds only a README), so keep the switch off until an image is
committed. Pull requests check any committed image the same way
(`test_store_assets.py`).

They live in the app's Asset Library and are reviewed on their own, apart from
the version:

1. An image is uploaded once, matched by file name and size. A reservation an
   interrupted upload left in `AWAITING_UPLOAD` (like a failed or archived image)
   does not count, and the image is uploaded again. To change an image, commit
   it under a new file name. An image App Review rejected stops the run until it
   is replaced.
2. A new image goes to App Review in a review submission of its own, never the
   version's draft. A draft an earlier attempt left holding only some of the
   images is finished first, else an empty draft, else a new one is created, so
   re-runs never pile up drafts. The run submits it only when it holds exactly
   these images.
3. Once Apple approves an image, the run places it on the version's en-US
   localization (`POST /v1/appAssetLibraryPlacements`, placement group
   `DEFAULT_PROFILE`), only while the version can be edited (Prepare for
   Submission or rejected). An image already placed there is left alone; another
   image in the same slot is replaced in the same request. Approval takes a while,
   so a new image is usually placed by the next release's run.

While the version is in a review submission (`READY_FOR_REVIEW` or
`WAITING_FOR_REVIEW`), nothing is uploaded, reviewed or placed: an image that
still needs uploading or review fails the run, and an approved image waits for
a later run.

With `IOS_SUBMIT_REVIEW` on, the run picks the version's draft review submission
before anything is written: the draft that already holds the version, else a
draft holding no app version, never one holding header or search results images.
That draft is reserved for the version and its products, so image reviews never
take it. A `READY_FOR_REVIEW` version that no such draft holds stops the run in
`prepare`, before the build, and the store step checks it again before its first
write.

## Release procedure

1. Bump the version (`MARKETING_VERSION` in Xcode / `versionName` and `versionCode`
   in `android/app/build.gradle.kts`). iOS build numbers come from App Store Connect;
   the Android versionCode must be higher than every versionCode on Google Play
   (the release checks this before building).
2. Add reviewed `## ios-vX.Y` / `## android-vX.Y` notes to `RELEASE_NOTES.md`
   (required for What's New; otherwise the GitHub Release uses generated notes).
3. iOS, with any listing, What's New, screenshot, creative asset or review switch
   on: the X.Y App Store version must exist. With `IOS_CREATE_VERSION` on, the
   tag run creates it; otherwise **create it in App Store Connect first**
   (Prepare for Submission). The preflight stops the run before building when it
   is missing.
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
# What a run with IOS_UPLOAD_LISTING off prepares and checks: What's New only
python3 scripts/store/ios_metadata.py prepare --out /tmp/store-meta-whats-new --tag ios-v2.1 --no-listing
UPLOAD_WHATS_NEW=true python3 scripts/store/asc_release.py --dry-run --metadata-dir /tmp/store-meta-whats-new
# The RGB screenshot copies a release uploads (needs Pillow from requirements-store.txt)
UPLOAD_SCREENSHOTS=true python3 scripts/store/asc_release.py --dry-run
# Header / search results images (fails while store/creative/ios holds none)
UPLOAD_CREATIVE_ASSETS=true python3 scripts/store/asc_release.py --dry-run
```

These never build, sign, or contact a store. The **Store automation (dry-run)**
workflow runs them for every change to store files, scripts, release workflows or
`requirements-store.in`/`.txt`, and `ci.yml` runs `./scripts/test.sh` on every
push to main and on pull requests that touch `tests/`, `scripts/` or the iOS app.

## Android closed beta

Android **1.0 (44)** is in Google Play closed testing (Alpha track), so the next
`android-v*` release needs a higher versionCode (the release checks this first).
The testers, countries, review status, and the `VERCELTICSBETA` promo code are
recorded in [google-play/TESTERS.md](google-play/TESTERS.md) and
[google-play/PROMO.md](google-play/PROMO.md).
