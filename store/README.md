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
             ─► resolve switches (tag must equal MARKETING_VERSION); a run that builds also
                checks the app's export compliance declaration
  └─ IOS_RELEASE_AUTOMATION off ─► GitHub Release only
  └─ on:
       listing / What's New / screenshot / review switch on
                        ─► check only what this run uploads: the listing, screenshots and
                           What's New, each only with its switch on
       IOS_CREATE_VERSION ─► create App Store version X.Y when it is missing (an existing one
                             is left alone)
       IOS_CREATE_PRODUCTS ─► read-only plan of every catalog product (fields, review
                              screenshot, price point, territories); then create the
                              missing ones, Ready to Submit (see Creating products)
       listing / What's New / screenshot / creative asset / review switch on
                        ─► read-only App Store Connect preflight: version X.Y exists in a state
                           that allows the enabled steps, with every locale they touch; the
                           creative images exist, and screenshot sets that will be replaced
                           have their upload copies made (RGB copies of PNGs with alpha)
       IOS_UPLOAD_BUILD ─► next build number from App Store Connect
                        ─► archive Release ─► verify app id/version/build in every bundle,
                           and the app's export compliance declaration
                        ─► cloud-signed export + upload
                        ─► wait until THIS build is VALID (poll 30 s, up to 120 min from upload start)
       IOS_UPLOAD_LISTING / IOS_UPLOAD_WHATS_NEW / IOS_UPLOAD_SCREENSHOTS ─► version metadata
       IOS_UPLOAD_CREATIVE_ASSETS ─► header / search results images: upload and review new
                                     ones on their own, place approved ones
       IOS_SUBMIT_REVIEW ─► select this run's build, read it back, confirm export compliance,
                            set release type, add Ready-to-Submit catalog subscriptions +
                            in-app purchases and the version to one draft submission
                         ─► recheck build + selection, and that the draft holds nothing else
                         ─► submit the draft
  └─ GitHub Release (only if every enabled step succeeded)
```

Every App Store Connect call goes through the pinned App Store Connect CLI
([`asc`](#app-store-connect-cli-asc)); the binary itself is still exported and
uploaded by `xcodebuild`.

**The App Store version must exist first.** App Store Connect only accepts
listing, What's New, screenshots, creative assets and review submission on an
App Store version that already exists. With `IOS_CREATE_VERSION` on, a tag run
creates version X.Y (it then shows "Prepare for Submission") when it is missing,
after the offline checks and before the preflight; an existing X.Y is left alone.
It refuses, before anything is built and without changing anything, when another
iOS version is still on its way to the store (any state but Ready for
Distribution, replaced or removed), when X.Y is not newer than every existing
version, or when the pinned App Store Connect app (`6761645656`) does not carry
the Verceltics bundle id. A version created by a run whose later steps fail stays
in Prepare for Submission, and the next run for the same tag reuses it. With the
switch off and any of those switches on, create version X.Y in App Store Connect
before pushing `ios-vX.Y`; otherwise the read-only preflight in the prepare job
stops the run before anything is built or uploaded. A build-only release
(`IOS_UPLOAD_BUILD` alone) needs no App Store version.

**A store asset the run does not upload never blocks it**, on either platform.
The listing text is read and checked only with `IOS_UPLOAD_LISTING` /
`ANDROID_UPLOAD_LISTING` on (otherwise `prepare --no-listing` hands the store step
What's New alone), screenshots only with their screenshot switch, and the tag's
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
because they cannot change in that state, and creative assets are never changed
there. If the version was already submitted (`WAITING_FOR_REVIEW`), the rerun
changes nothing: it passes only when App Store Connect already holds this run's
build and the repository's text and screenshots, and then the GitHub Release is
created.

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

### App Store Connect CLI (`asc`)

Every App Store Connect call goes through the
[App Store Connect CLI](https://github.com/rorkai/App-Store-Connect-CLI) (`asc`,
MIT) instead of hand-written API code (no more JWT signing or HTTP client in this
repository). [`scripts/store/asc_cli.py`](../scripts/store/asc_cli.py) is the only
place that runs it:

- It is pinned to one release (`ASC_VERSION`, now 5.15.0) and installed by
  `asc_cli.py install`, which refuses a binary whose SHA-256 differs from the
  pinned value for that platform (`linux_amd64` and `macOS_arm64`). Every job
  that uses it runs `asc_cli.py check`, and every run re-checks the checksum of
  the `asc` on `PATH` before handing it credentials; no environment variable can
  point the scripts at another executable. The **Store automation (dry-run)**
  workflow installs and checks it on every change to the store scripts, so a bad
  pin fails on the pull request.
- Credentials come only from the `APP_STORE_CONNECT_*` secrets, through asc's
  own environment variables; the keychain and asc config files are bypassed,
  and `ASC_STRICT_AUTH` rejects credentials mixed from several sources.
- Telemetry (`ASC_TELEMETRY_DISABLED`, `DO_NOT_TRACK`) and the skills update
  check are off.
- Checks that must never write run asc read-only (`ASC_READ_ONLY`): the version
  preflight, the product plan, build numbering, the build wait, and the whole
  `prepare` job on validation runs. asc refuses a write in that mode before
  sending it (exit 6).
- On failure the error keeps asc's exit code and drops the key and issuer IDs.
  asc retries a read itself after a rate limit, a server error or a dropped
  connection (`ASC_MAX_RETRIES=5`). The build wait also retries asc's
  server-side exit codes (HTTP 5xx, 408, 429 and timeouts) and network drops,
  which asc reports as a generic exit 1 with a connection error; auth, usage
  and not-found errors stop at once.
- Every run checks that the pinned App Store Connect app (`ASC_APP_ID`,
  `6761645656`) carries exactly the bundle id `com.apoorvdarshan.verceltics`
  before it reads or changes anything.

To move to a newer asc, change `ASC_VERSION` and both `ASC_SHA256` values from
that release's `asc_<version>_checksums.txt`, then run the store tests.

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
| `IOS_CREATE_PRODUCTS` | Create every iOS product in `store/catalog/products.json` that App Store Connect does not have yet (matched by product ID) and bring it to Ready to Submit, in `prepare`, before anything is built, so `IOS_SUBMIT_REVIEW` sends it to review in the same run. A read-only plan of every product runs first and must pass completely (fields, review screenshot, price point, territories), so a problem stops the run before any write. Existing products are never changed: their price, period, type and family sharing are compared and differences printed as warnings. One left in Missing Metadata by an interrupted run is finished, never created twice. Tag runs only; validation runs print the plan read-only (see Creating products) |
| `IOS_UPLOAD_BUILD` | Build, sign and upload the binary to App Store Connect, then wait for processing |
| `IOS_UPLOAD_LISTING` | App name, subtitle, description, keywords, promotional text, privacy/marketing/support URLs from [`metadata/ios/`](metadata/ios/) |
| `IOS_UPLOAD_WHATS_NEW` | What's New: the tag's `## ios-vX.Y` section in [RELEASE_NOTES.md](../RELEASE_NOTES.md) as plain text (4000 characters at most; fails without one) |
| `IOS_UPLOAD_SCREENSHOTS` | Replace the iPhone 6.7" and iPad 13" screenshot sets that differ from App Store Connect (see below) |
| `IOS_UPLOAD_CREATIVE_ASSETS` | Product page header and search results images from `store/creative/ios/header/` and `store/creative/ios/search-results/` (see below). A new image is uploaded to the Asset Library and sent to App Review in a submission of its own; once Apple approves it, a later run places it on the version (or swaps it in for the current image). Never changes anything while the version waits for review |
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
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API key: build numbers, cloud-managed distribution signing, upload, build readiness, listing, review (used by `asc` and `xcodebuild`) |
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
| [`scripts/store/ios_release.py`](../scripts/store/ios_release.py) | iOS switches, export compliance declaration (runs that build), build number, wait for this run's build (both read-only) |
| [`scripts/store/asc_cli.py`](../scripts/store/asc_cli.py) | Installs the pinned, checksum-verified `asc` and runs it (credentials, read-only mode, telemetry off, redacted errors) |
| [`scripts/store/asc_release.py`](../scripts/store/asc_release.py) | ASC version creation (`--create-version`), listing, App Info, What's New, screenshots, header and search results images, build selection, export compliance, IAPs, submit for review (`--dry-run` = local checks only, `--preflight` = read-only App Store Connect checks); every call through `asc` |
| [`scripts/store/ios_metadata.py`](../scripts/store/ios_metadata.py) | Check the App Store listing and/or screenshots; prepare the listing (unless `--no-listing`) with the tag's What's New as plain text |
| [`scripts/store/export_ios_listing.py`](../scripts/store/export_ios_listing.py) | Read-only (asc in read-only mode): export the live App Store listing into `store/metadata/ios/` |
| [`scripts/store/android_release.py`](../scripts/store/android_release.py) | Android switches, versionCode check against Play, AAB/APK identity + pinned signature checks, pre-upload track state, Play track read-back |
| [`scripts/store/play_listing.py`](../scripts/store/play_listing.py) | Check, prepare and upload the Play listing, phone screenshots and What's New |
| [`scripts/store/asc_products.py`](../scripts/store/asc_products.py) | `plan`: read-only check of the catalog products App Store Connect does not have (or has in Missing Metadata) and drift warnings for the rest; `create` (`CREATE_IOS_PRODUCTS`): the plan, then create or finish them, Ready to Submit. Identical to Fud AI's copy. Its offline field checks also run in `validate_catalog.py` |
| [`scripts/store/validate_catalog.py`](../scripts/store/validate_catalog.py) | `catalog/products.json` against StoreKit (products listing `ios`) and the Android billing IDs (products listing `android`), plus the App Store creation fields |
| [`scripts/store/print_gates.py`](../scripts/store/print_gates.py) | Print the configured switches |
| [`scripts/store/check_store_tooling.py`](../scripts/store/check_store_tooling.py) | Offline check of the installed store tooling: the versions `requirements-store.in` pins and the Google Play API client (store-automation.yml) |
| [`scripts/store/test_ios_release.py`](../scripts/store/test_ios_release.py) | Offline tests against a fake App Store Connect behind `asc`: disabled/invalid switches, tag mismatch, validation mode, build wait timeout/failure/ambiguity/mismatch/old build/unknown upload time, selection read-back, products only in the rechecked draft, unchosen draft items, no empty drafts on reruns, version states and reruns, version creation, product creation (each type, finishing an interrupted product, drift warnings, failed checks before any write, plan-only validation runs, new products reaching review, only iOS products submitted), export compliance, preflight, listing (never read with its switch off), screenshots (sizes, formats, and RGB copies of PNGs with alpha) and creative images (sizes, formats and alpha), locked-version approvals, catalog, network-drop retries, and the asc runner (credentials, read-only mode, redaction, pinned checksum) |
| [`scripts/store/test_android_release.py`](../scripts/store/test_android_release.py) | Offline tests: switches, track selection, wrong app/version/key, pinned upload certificate, unsigned bundle entries, versionCode and pre-upload checks, Play listing and read-back |
| [`scripts/store/test_store_assets.py`](../scripts/store/test_store_assets.py) | The repository's App Store and Play listings and screenshots (pull requests and main; a release checks only what it uploads) |
| [`scripts/ci/redact_ids.py`](../scripts/ci/redact_ids.py) | Removes App Store Connect key/issuer IDs from failure logs before they are kept |
| [`scripts/release_notes.py`](../scripts/release_notes.py) | The tag's reviewed notes: GitHub Release body and (as plain text) What's New |

The release workflows install [`requirements-store.txt`](../requirements-store.txt)
(Google API client and Pillow; hash-pinned) into the pinned Python 3.13 from
`actions/setup-python`, never the runner's system Python, and only for steps
that need it: Android for the steps that call Google Play, iOS only with
`IOS_UPLOAD_SCREENSHOTS` on, in the preflight and the store step, for the
screenshots' RGB copies (Pillow). Otherwise the iOS store steps install only the
pinned `asc`. Store steps run unbuffered (`python3 -u`), so the build wait and
uploads log as they go. Nothing else installs the requirements, so whenever the
**Store automation (dry-run)** workflow runs, including for every change to
`requirements-store.in`/`.txt` (all that a Dependabot pip update touches), its
`store-tooling` job installs them the same way and uses them offline with
[`check_store_tooling.py`](../scripts/store/check_store_tooling.py) and the
screenshot RGB copy tests (`STORE_TESTS_NEED_PILLOW=1`, so they cannot skip).
A bad update fails there, before it is merged, instead of at the next release tag.
`asc_release.py` reads `CREATE_IOS_VERSION`, `UPLOAD_LISTING`, `UPLOAD_WHATS_NEW`,
`UPLOAD_SCREENSHOTS`, `UPLOAD_CREATIVE_ASSETS`, `SUBMIT_IOS_REVIEW` and
`PUBLIC_RELEASE`, set by `ios-release.yml` from the resolved iOS switches.
`asc_products.py create` needs `CREATE_IOS_PRODUCTS`.
`play_listing.py upload` reads `UPLOAD_LISTING` and `UPLOAD_SCREENSHOTS`, set by
`android-release.yml` from the resolved Android switches.

## Metadata layout

```
store/metadata/
  ios/en-US/        name, subtitle, description, keywords, promotional_text, *_url (.txt)
  play/en-US/       title, short_description, full_description (.txt)
store/app-store/screenshots/iphone/   App Store iPhone 6.7" screenshots (1290×2796), upload order = file name
store/app-store/screenshots/ipad/     App Store iPad 13" screenshots (2048×2732)
store/creative/ios/header/            one App Store product page header image, 3840×1646 PNG
                                      (IOS_UPLOAD_CREATIVE_ASSETS; empty today)
store/creative/ios/search-results/    one App Store search results image, 3:2, 1920×1280 to
                                      3840×2560 PNG or JPEG (IOS_UPLOAD_CREATIVE_ASSETS; empty today);
                                      a 5244×2950 PNG is a universal image that fits either slot
store/google-play/screenshots/phone/   Play phone screenshots (1080×1920)
RELEASE_NOTES.md            ## ios-vX.Y / ## android-vX.Y → GitHub Release + What's New
```

`store/metadata/ios/` was exported from the live 2.1 listing (en-US is the only
locale) with `export_ios_listing.py`; after editing the listing in App Store
Connect, export again so the repository stays the source of truth. The upload
covers every locale folder; App Info fields (name, subtitle, privacy URL) are
only editable while a new version is being prepared, and the run fails rather
than silently skipping a change it cannot make.

Each set takes exactly the sizes asc accepts for it (`asc screenshots sizes
--display-type <type>`): 1260×2736, 1290×2796 or 1320×2868 for the 6.7" iPhone
(not 1284×2778, the 6.5" size) and 2048×2732 or 2064×2752 for the 13" iPad, in
either orientation. asc refuses to upload a PNG that stores an alpha channel or
transparency (colour types 4 and 6, or a `tRNS` chunk), even when every pixel is
opaque, so the release uploads an RGB copy of each such PNG instead (checked the
same way, `asc_cli.image_has_alpha`). Pillow makes the copies in a temporary
folder, laying transparent pixels over black and keeping the colour profile; the
repository's files are never changed, and PNGs without alpha are uploaded byte
for byte. The screenshots in the repository today are fully opaque RGBA, so
their copies have exactly the same pixels. A copy that would still have alpha is
never handed to asc. A screenshot set that App Store Connect already holds
exactly (the same screenshots in the same order, compared by checksum, as the
committed files or as their copies) is left alone. Any other set is replaced as
a whole (`asc screenshots upload --replace`) and read back against the copies
that were uploaded. The preflight makes the copies too, so a missing Pillow
stops a release whose screenshots with alpha have to be uploaded before anything
is built. Copies are the same for the same Pillow version; after a Pillow update
a set uploaded as copies may be replaced once more. If an upload fails part-way,
the set can be left incomplete: fix the error and rerun, and the set is replaced
again.

A creative image is matched in the Asset Library by file name and size, so give
a changed image a new file name. A reservation left by an interrupted upload
(`AWAITING_UPLOAD`, which asc never deletes) is ignored and the image is
uploaded again. Images never take the draft the version and its products chose.
An unfinished draft holding only these images is finished first, else an empty
one, instead of starting another, so a rerun leaves no extra drafts. Apple
reviews each new image on its own (the app already has an approved version), and
a later run places it once it is approved; a rejected image stops the run with
Apple's reason. Apple's rules for these images: no prices, website addresses,
other platforms or Apple badges, and no transparency (`asc asset-library specs`
lists the current sizes). Sizes, formats and alpha are checked with the other
local inputs, before anything is built and before any asc call, so a wrong file
stops the run early. An image approved while the version waits for review is not
a mismatch: the rerun passes and the image is placed once the version can be
edited. Both folders are empty today, so keep `IOS_UPLOAD_CREATIVE_ASSETS` off
until an image is added: with the switch on and no image, the run fails before
anything is built.

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

`IOS_SUBMIT_REVIEW` submits the App Store version and every `READY_TO_SUBMIT`
(or `DEVELOPER_REJECTED`) subscription and `READY_TO_SUBMIT` in-app purchase
(the lifetime purchase and tips) in
[`catalog/products.json`](catalog/products.json) whose `platforms` list `ios`, in
one review submission, including products `IOS_CREATE_PRODUCTS` created earlier
in the same run. Each
product attaches through its current version (`subscriptionVersions` /
`inAppPurchaseVersions` review items, via `asc review items-add`), so a
first-time subscription ships with the version. Nothing reaches App Review until
the draft is submitted, after the final build and selection recheck; when the
recheck fails, nothing is sent. (The lifetime purchase and tips used to go
through Apple's deprecated `inAppPurchaseSubmissions` endpoint, which sent each
one to App Review at once; asc submits them only as review items, so they now
wait in the draft with everything else. Every catalog purchase is approved
today.) Products outside the catalog are never submitted, and the version is not
submitted if a required product cannot be added. A reused draft is submitted
only when it holds this version and nothing but versions of catalog products;
anything else stops the run. Drafts holding creative images belong to those
images' own review and are never used for the version. The draft that already
holds this version is reused; otherwise only a draft holding no app version is,
so another version's draft is never touched. A `READY_FOR_REVIEW` version that
no draft holds stops the run before anything is written: the draft is checked
before the listing text, screenshots, images or build are touched, and the
read-only preflight checks it too, before anything is built. If the version is
already `WAITING_FOR_REVIEW`, late products go out in a draft of their own,
which is created only when a product has to be added, so a rerun with nothing to
send leaves no empty draft; a retry never duplicates items or ships an unrelated
app version.

### Product catalog

[`catalog/products.json`](catalog/products.json) lists every product the apps
sell, by group: `subscriptions` (auto-renewable), `consumables` and `tips`
(`CONSUMABLE`), and `non_consumables` (`NON_CONSUMABLE`). Each product lists
the platforms that sell it in `platforms` (`ios`, `android`); only products
listing `ios` are App Store products, and only they are created, compared or
submitted for review. `validate_catalog.py` checks the `ios` products against
`Products.storekit` and the `android` ones against `BillingModels.kt`.
Subscriptions carry their `period` (`P1M`, `P1Y`, …), and `suggested_price_usd`
is the price in the United States. Every product the app sells today exists in
App Store Connect (Approved), with these periods and prices, in the subscription
group `Verceltics Pro` (`ios_subscription_groups`, declared by name only), so
none of them has an `ios` block: nothing is invented for products that already
exist, and the release only compares them.

### Creating products (`IOS_CREATE_PRODUCTS`)

[`asc_products.py`](../scripts/store/asc_products.py) (the same file as Fud AI's,
so a fix there applies here unchanged) matches every iOS product to App Store
Connect by product ID:

| In App Store Connect | What a tag run does |
|---|---|
| Missing | Creates it and brings it to Ready to Submit |
| Missing Metadata, with the catalog's reference name, type (or period) and family sharing | Finishes it: an interrupted run left it there. Only missing parts are added; a part that is already set and differs from the catalog (localization, price, availability, review note, or a failed or different review screenshot) stops the run in the plan, before any write. A screenshot that matches the file is kept even while Apple is still processing it |
| Anything else | Never changed. Price (United States), period, type and family sharing (when the catalog sets it) are compared, and differences are printed as warnings |

What a created product gets, all from the catalog:

| Part | Source |
|---|---|
| Reference name | `ios.reference_name` (up to 64 characters) |
| Type | the catalog group (above) |
| Subscription group and period | `ios.subscription_group` (a `reference_name` from the top-level `ios_subscription_groups`) and `period` (`P1W`, `P1M`, `P2M`, `P3M`, `P6M`, `P1Y`) |
| Base price | `suggested_price_usd` in the United States (`USA`); App Store Connect derives the other territories' prices from it (Apple's equalized prices). The plan checks it is a price point by asking a live product of the same kind; an app without one is checked against its own price grid, except a first subscription priced above that grid's top ($400; only subscriptions go higher), which App Store Connect checks while creating it (a warning says so) |
| Availability | `ios.territories`: `"all"` (the default) is every current territory plus new ones automatically; a list of alpha-3 codes (`["USA", "CAN"]`) is exactly those |
| en-US display name and description | `ios.display_name` (2–30 characters), `ios.description` (up to 45) |
| App Review screenshot | `ios.review_screenshot`: a path under `store/` (keep them in `store/catalog/review/`), a PNG or JPEG named for its format, RGB without alpha, at an iPhone or iPad screenshot size (e.g. 1290×2796 or 2064×2752), and complete (a PNG is fully decoded; a JPEG must reach its end marker) |
| Review note | `ios.review_note` (subscriptions only, up to 4,000 characters) |
| Family sharing | `ios.family_sharing`, default `false`. It can never be turned off again, so it is only sent when the catalog says `true` (subscriptions and non-consumables only) |

A new subscription group also needs `display_name` in its `ios_subscription_groups`
entry (its en-US name); a group App Store Connect already names is never renamed.
Comparing an existing product's price is only a warning, so an App Store Connect
error while reading it is reported and the release goes on. `validate_catalog.py`
checks every `ios` block offline (including the screenshot file), and the plan
checks, read-only, that each product to create has every field, that its price
is a price point in the United States, and that its territories exist. Example:

```json
"tips": [
  {
    "id": "com.apoorvdarshan.verceltics.tip.dinner",
    "suggested_price_usd": "14.99",
    "platforms": ["ios", "android"],
    "ios": {
      "reference_name": "Dinner Tip",
      "display_name": "Dinner Tip",
      "description": "Support Verceltics development",
      "review_screenshot": "store/catalog/review/tips.png"
    }
  }
]
```

(A product listed for `android` must also be in `BillingModels.kt`, and one
listed for `ios` in `Products.storekit`, or `validate_catalog.py` fails.)

How it runs (`prepare` job, before anything is built): the plan (read-only)
checks every product first; any problem stops the run with nothing created.
Subscriptions go through asc's reconciling `asc subscriptions setup`: once
without the screenshot (group, localization, price, availability), then the
review note, then again with the screenshot, which completes it and verifies the
result. In-app purchases are added one part at a time (product, en-US
localization, price, availability, screenshot last). Because the screenshot is
required for Ready to Submit and always goes last, a product an interrupted run
left behind stays in Missing Metadata, and the next run finishes it. Each product
is read back until it is Ready to Submit (up to 2 minutes); the `prepare` job
allows 30 minutes for this. A manual validation run with `IOS_RELEASE_AUTOMATION`
and `IOS_CREATE_PRODUCTS` on prints the plan and creates nothing.

What stays manual:

- **RevenueCat**: add each new product to the RevenueCat app, the
  `Verceltics Pro` entitlement and the offerings by hand (the script's closing
  note says the same); the app only sells what RevenueCat offers.
- An in-app purchase's review note: the pinned asc 5.15.0 has no command for it,
  so `ios.review_note` is refused on in-app purchases. Add one in App Store
  Connect when App Review needs it.
- Other locales, promotional images, introductory and promotional offers, offer
  codes, a subscription's level within its group, and Google Play products.
- Changing an existing product (price, name, availability); the release only
  warns about differences.
- A new subscription group's own review item, if App Store Connect asks for one:
  the review step attaches subscription and in-app purchase versions only.

## Release procedure

1. Bump the version (`MARKETING_VERSION` in Xcode / `versionName` and `versionCode`
   in `android/app/build.gradle.kts`). iOS build numbers come from App Store Connect;
   the Android versionCode must be higher than every versionCode on Google Play
   (the release checks this before building).
2. Add reviewed `## ios-vX.Y` / `## android-vX.Y` notes to `RELEASE_NOTES.md`
   (required for What's New; otherwise the GitHub Release uses generated notes).
3. iOS, with any listing, What's New, screenshot or review switch on: the X.Y App
   Store version must exist. With `IOS_CREATE_VERSION` on, the tag run creates
   it; otherwise **create it in App Store Connect first** (Prepare for
   Submission). The preflight stops the run before building when it is missing.
4. New in-app purchase or subscription: add it to `catalog/products.json`
   (and `Products.storekit`) with its `ios` fields and review screenshot, turn
   on `IOS_CREATE_PRODUCTS` (and `IOS_SUBMIT_REVIEW` to send it with the
   version), and add it to RevenueCat by hand. A validation run (step 5) prints
   what the tag would create.
5. Run the validation run (**Run workflow**) to prove signing still works.
6. Turn on only the switches you want; try Android on `ANDROID_INTERNAL_TESTING`
   first, and leave `IOS_SUBMIT_REVIEW`, `IOS_PUBLIC_RELEASE` and
   `ANDROID_PRODUCTION_ROLLOUT` off until you intend that release.
7. Push the `ios-vX.Y` / `android-vX.Y` tag. A tag can be used once.

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
python3 scripts/store/asc_products.py create   # CREATE_IOS_PRODUCTS unset: skipped, no calls
```

These never build, sign, or contact a store. The product plan reads App Store
Connect (needs the `APP_STORE_CONNECT_*` variables) and never writes:

```bash
ASC_READ_ONLY=1 python3 scripts/store/asc_products.py plan
```

The **Store automation (dry-run)** workflow runs
the offline ones for every change to store files, scripts, release workflows or
`requirements-store.in`/`.txt`, and `ci.yml` runs `./scripts/test.sh` on every
push to main and on pull requests that touch `tests/`, `scripts/` or the iOS app.

## Android closed beta

Android **1.0 (44)** is in Google Play closed testing (Alpha track), so the next
`android-v*` release needs a higher versionCode (the release checks this first).
The testers, countries, review status, and the `VERCELTICSBETA` promo code are
recorded in [google-play/TESTERS.md](google-play/TESTERS.md) and
[google-play/PROMO.md](google-play/PROMO.md).
