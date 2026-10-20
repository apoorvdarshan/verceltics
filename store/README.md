# Store releases

Verceltics' App Store and Google Play releases run on
[apoorvdarshan/store-release](https://github.com/apoorvdarshan/store-release), a
shared set of reusable workflows and Python tooling. Its Verceltics flow is this
repository's former `scripts/store` tooling, ported unchanged. Three thin caller
workflows pin it by commit SHA, with the version in a `# vX.Y.Z` comment (v0.3.0
today):

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| [`ios-release.yml`](../.github/workflows/ios-release.yml) | `ios-vX.Y` tag | release run; the tag must equal `MARKETING_VERSION` |
| | **Run workflow** | validation only: archive, cloud-signed export and App Store Connect validation of the IPA, plus a read-only store plan. Never uploads |
| [`android-release.yml`](../.github/workflows/android-release.yml) | `android-vX.Y` tag | release run; the tag must equal `versionName` |
| | **Run workflow** | validation only: the signed AAB and APK, verified against the pinned upload certificate, plus a read-only store plan. Never uploads, signs with Sigstore or releases |
| [`store-checks.yml`](../.github/workflows/store-checks.yml) | pull requests and `main` pushes that touch store files, **Run workflow** | checks the store data (listings, screenshots, catalog, release notes), [`store-release.toml`](store-release.toml) and the callers against the pinned version. Never uploads |

**Nothing reaches a store unless its switch is on**, and merging never releases:
only a tag push does. The two tag prefixes never start each other's pipeline. The
callers are rendered by store-release (`run.py callers render`); Store checks fails
when one is edited by hand or pins anything but a store-release release. Dependabot
moves all three pins to a new store-release release in one PR of its own, seven days
after it is published.

How the pipeline behaves is documented in store-release (v0.3.0):
[switches](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/switches.md),
[secrets](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/secrets.md),
[jobs and checks](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/workflows.md),
[the Verceltics flow](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/flows.md#verceltics-iosflow-and-androidflow--verceltics),
[store plan](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/plan.md),
[re-runs](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/rerun.md),
[verifying downloads](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/verification.md),
[the config file](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/config.md)
and [how a new release reaches this repository](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/release-process.md#how-apps-receive-a-release).
This file keeps only Verceltics' own facts.

**Xcode Cloud was removed for Verceltics on 2026-10-14**, after this pipeline's
validation run passed: its tag-triggered workflow
(`2245A813-CA9D-45A4-8ACA-CF30494D2575`) and its product were deleted in App Store
Connect. Xcode Cloud triggers live in App Store Connect, not in this repository, so
a GitHub switch cannot stop them: never re-create an Xcode Cloud workflow for
`ios-v*` tags, or both pipelines would upload a build for the same tag.

## What this repository keeps

- [`store-release.toml`](store-release.toml): where the store files are and
  Verceltics' fixed identities: bundle id and package `com.apoorvdarshan.verceltics`,
  App Store Connect app `6761645656`, team `23RV7FYH36`, the Xcode to build with, and
  the pinned Android upload certificate.
- The switches (repository variables) and credentials (repository secrets) below.
- The store files under `store/` (layout below) and
  [`RELEASE_NOTES.md`](../RELEASE_NOTES.md): the tag's section is both the GitHub
  Release body and, as plain text, What's New on both stores.
- The gates a tag run must pass first: [`ios-tests.yml`](../.github/workflows/ios-tests.yml)
  (iOS unit and UI tests; a test bundle that runs no tests fails) for iOS tags, and
  [`ci.yml`](../.github/workflows/ci.yml) with `android-release: true` (catalog tests,
  Android unit tests, lint, builds and both emulator test shards; not the
  website/Worker job) for Android tags.
- [`scripts/ci/release_gate_tests.sh`](../scripts/ci/release_gate_tests.sh): the
  offline repository tests (`tests/`: the provider API catalogs, the privacy manifest,
  AuthManager) that every iOS prepare runs (`ios.prepare.checks`).

## Current switches

Repository **Variables** (Settings → Secrets and variables → Actions → Variables),
as of 2026-10-21. Missing means off; any value other than
`true/false/yes/no/on/off/1/0` fails the run, even when the platform's master switch
is off.

| Variable | Value |
|----------|-------|
| `IOS_RELEASE_AUTOMATION` | `true` |
| `IOS_CREATE_VERSION` | `true` |
| `IOS_UPLOAD_BUILD` | `true` |
| `IOS_UPLOAD_LISTING` | `true` |
| `IOS_UPLOAD_WHATS_NEW` | `true` |
| `IOS_UPLOAD_SCREENSHOTS` | `false` |
| `IOS_UPLOAD_CREATIVE_ASSETS` | not set (off) |
| `IOS_SUBMIT_REVIEW` | `true` |
| `IOS_PUBLIC_RELEASE` | `true` |
| `IOS_BUILD_POLL_SECONDS`, `IOS_BUILD_WAIT_MINUTES` | not set (30 s, 120 min) |
| `ANDROID_RELEASE_AUTOMATION` | `false` |
| `ANDROID_UPLOAD_LISTING`, `ANDROID_UPLOAD_WHATS_NEW`, `ANDROID_UPLOAD_SCREENSHOTS` | `false` |
| `ANDROID_INTERNAL_TESTING`, `ANDROID_CLOSED_TESTING`, `ANDROID_PRODUCTION_ROLLOUT` | `false` |
| `STORE_SYNC_REVENUECAT` | not set (off; on fails the run, since RevenueCat sync is not implemented) |

With these values:

- An `ios-vX.Y` tag creates App Store version X.Y when App Store Connect does not have
  it yet, builds, signs and uploads the binary, waits until App Store Connect has
  processed it, updates the listing and App Info from `store/metadata/ios/` and What's
  New from the tag's `RELEASE_NOTES.md` section, selects this run's build, submits the
  version (with the catalog's ready subscriptions and in-app purchases) for review and
  releases it automatically once Apple approves it. Screenshots and the header and
  search results images are left alone. The GitHub Release `iOS X.Y (build N)` is
  created, marked Latest.
- An `android-vX.Y` tag builds and releases nothing.

Each **Run workflow** prints this plan for the switches as they are at that moment
(the `store-plan` job's summary). Try `ANDROID_INTERNAL_TESTING` first, and keep
`ANDROID_PRODUCTION_ROLLOUT` off: **Google has not granted Verceltics production
access yet** (it needs at least 12 testers opted in to closed testing for 14
consecutive days, see [google-play/TESTERS.md](google-play/TESTERS.md)), so Play
rejects a production upload until then.

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

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API key: build numbers, cloud-managed distribution signing, upload, build readiness, listing, review |
| `APP_STORE_CONNECT_ISSUER_ID` | Its issuer |
| `APP_STORE_CONNECT_API_KEY_P8` | Its private key (`.p8` body) |
| `IOS_DEV_SIGNING_P12_BASE64` | The CI Apple Development identity (base64 `.p12`) that signs the archive; App Store signing happens at export with Apple's cloud-managed certificate |
| `IOS_DEV_SIGNING_P12_PASSWORD` | Its password |
| `ANDROID_KEYSTORE_BASE64` | base64 of the Play upload keystore (`verceltics-upload.jks`, alias `upload`) |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password, which is also the key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | A Google Play publisher service-account key with release access to `com.apoorvdarshan.verceltics`. Every Android release run uses it read-only to confirm the versionCode is new; uploads and listing changes use it only with their switches on |

The callers pass each reusable workflow exactly the secrets it declares, by name
(never `secrets: inherit`). Keys are encrypted in GitHub Secrets and never belong in
this repository; offline checks need none of them.

The CI Apple Development identity is certificate "Apple Development: Created via
API", ID `NVNGDQGCKK`, **expiring 2027-10-14**. The same identity signs the archives
of Fud AI, Abysm, iitjee, Quit All, Scowld and Sesslet, because Apple allows one
API-created development certificate per API key. Renewing it:
[the shared CI signing identity](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/provenance.md#facts-that-only-concern-the-six-apps).
When it is replaced, update both `IOS_DEV_SIGNING_*` secrets here and in every
repository listed above, then run a validation run.

The Android upload certificate is pinned as `android.upload_cert_sha256` in
[`store-release.toml`](store-release.toml) (`fe735c7c…61f1ef`). A run fails before
anything is signed or published when `ANDROID_KEYSTORE_BASE64` holds any other key.
After a Play upload-key reset, change the pin in the same change as the new keystore
secret.

## Release procedure

1. Bump the version (`MARKETING_VERSION` in Xcode / `versionName` and `versionCode`
   in `android/app/build.gradle.kts`). iOS build numbers come from App Store Connect;
   the Android versionCode must be higher than every versionCode on Google Play (the
   release checks this before building).
2. Add reviewed `## ios-vX.Y` / `## android-vX.Y` notes to `RELEASE_NOTES.md`
   (required for What's New; otherwise the GitHub Release uses generated notes). The
   App Store limit (4000) and the Play limit (500) apply to the plain-text form.
3. iOS, with any listing, What's New, screenshot, creative asset or review switch on:
   the X.Y App Store version must exist. With `IOS_CREATE_VERSION` on, the tag run
   creates it; otherwise create it in App Store Connect first (Prepare for
   Submission). The preflight stops the run before building when it is missing.
4. Run **iOS Release → Run workflow** and **Android Release → Run workflow**. Both
   only validate. Apple refuses versions it has already approved, so while the
   project still has the released version, set the iOS run's `marketing_version` to
   the next one (`gh workflow run ios-release.yml --ref main -f marketing_version=2.1.1`);
   the build is validated as that version without changing the project. Read each
   `store-plan` job's summary: it lists every write the tag run would make with the
   current switches, and anything that would stop it.
5. Turn on only the switches you want, and leave `IOS_SUBMIT_REVIEW`,
   `IOS_PUBLIC_RELEASE` and `ANDROID_PRODUCTION_ROLLOUT` off until you intend that
   release.
6. Push the `ios-vX.Y` / `android-vX.Y` tag, one at a time. A tag can be used once.
7. If a later step fails, fix the cause and use **Re-run failed jobs**
   ([re-runs](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/rerun.md)).

## Export compliance (iOS)

The app target declares `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO` in both
configurations, so every build's `Info.plist` carries
`ITSAppUsesNonExemptEncryption = false`, and the catalog declares the same answer
(`uses_non_exempt_encryption: false`). `NO` is right while the app uses only exempt
encryption: HTTPS (`URLSession` and the RevenueCat SDK), the Keychain for
credentials, iOS Data Protection for the site cache, and CryptoKit for hashing and
request signing (SHA-256 for the Google sign-in PKCE challenge and cache keys,
HMAC-SHA256 for AWS Signature Version 4). If the app ever adds its own encryption,
set both to `YES` / `true`, add `INFOPLIST_KEY_ITSEncryptionExportComplianceCode`
with the code App Store Connect issues, and see
[Apple's encryption guidance](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).
Every iOS run that builds checks the project and catalog before building and the
archived app right after archiving; review submission refuses a build that declares
otherwise.

## Catalog

[`catalog/products.json`](catalog/products.json) lists the App Store and Play product
ids (subscriptions, the lifetime purchase and tips; every id starts with
`com.apoorvdarshan.verceltics.`). store-release checks it against
[`Products.storekit`](../ios/verceltics/Paywall/Products.storekit) and the Android
billing ids in `BillingModels.kt` on every pull request that touches them and in every
release run. With `IOS_SUBMIT_REVIEW` on, a release attaches the catalog's ready
subscriptions to the version's review submission and sends ready in-app purchases to
review only after the final build and selection recheck; products outside the catalog
are never submitted.

## Store files

```
store/metadata/
  ios/en-US/        name, subtitle, description, keywords, promotional_text, *_url (.txt)
  play/en-US/       title, short_description, full_description (.txt)
store/app-store/screenshots/iphone/      App Store iPhone 6.7" screenshots (1290×2796), upload order = file name
store/app-store/screenshots/ipad/        App Store iPad 13" screenshots (2048×2732)
store/app-store/screenshots/iphone-duo/  optional: iPhone Duo screenshots, outer screen 1398×2034
store/app-store/screenshots/watch/       optional: Apple Watch screenshots, by frame size
store/creative/ios/header/               one product page header PNG, exactly 3840×1646 (empty for now)
store/creative/ios/search-results/       one search results PNG or JPEG, 3:2, 1920 to 3840 wide (empty for now)
store/google-play/screenshots/phone/     Play phone screenshots (1080×1920), uploaded as they are
RELEASE_NOTES.md            ## ios-vX.Y / ## android-vX.Y → GitHub Release + What's New
```

`store/metadata/ios/` was exported from the live 2.1 listing (en-US is the only
locale); after editing the listing in App Store Connect, export it again
(`ios export-listing`, below) so the repository stays the source of truth. App Info
fields (name, subtitle, privacy URL) are only editable while a new version is being
prepared, and the run fails rather than silently skipping a change it cannot make.

App Store screenshots go up as RGB copies (App Store Connect refuses a PNG that
stores an alpha channel, and the committed screenshots are RGBA); the files here are
only read, never rewritten or resized. A screenshot upload replaces that store's set
one image at a time, so a failed run can leave a mixed set: inspect App Store Connect
before rerunning.

Both header and search results image folders are empty (each holds only a README),
so keep `IOS_UPLOAD_CREATIVE_ASSETS` off until an image is committed. To change an
image later, commit it under a new file name: App Store Connect matches Asset Library
images by file name and size.

## Verifying Android downloads

Android GitHub Releases from store-release are built and signed by its
`android-publish.yml`; pin this repository as well, because every app that uses
store-release shares that signer:

```bash
cosign verify-blob verceltics-android-X.Y-N.apk --bundle verceltics-android-X.Y-N.apk.sigstore.json \
  --certificate-identity-regexp '^https://github\.com/apoorvdarshan/store-release/\.github/workflows/android-publish\.yml@[0-9a-f]{40}$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-github-workflow-repository apoorvdarshan/verceltics
gh attestation verify verceltics-android-X.Y-N.apk --repo apoorvdarshan/verceltics \
  --signer-workflow apoorvdarshan/store-release/.github/workflows/android-publish.yml
```

No Android GitHub Release was published by this repository's own former
`android-release.yml`.

## Checking locally

With Python 3.11 or later (CI uses 3.13), from the repository root:

```bash
./scripts/test.sh                                   # the offline repository tests (ci.yml's catalog job)
git clone --branch v0.3.0 https://github.com/apoorvdarshan/store-release ../store-release
python3 -I -u ../store-release/run.py --app-root . config check --files
python3 -I -u ../store-release/run.py --app-root . catalog check
python3 -I -u ../store-release/run.py --app-root . release-notes --check-headings
python3 -I -u ../store-release/run.py --app-root . ios pr-checks       # App Store listing, What's New, screenshots (dry runs)
python3 -I -u ../store-release/run.py --app-root . android pr-checks   # Play listing and phone screenshots (dry run)
IOS_SUBMIT_REVIEW=maybe python3 -I -u ../store-release/run.py --app-root . print-switches --platform all   # shows INVALID
```

These read only this checkout and call no store API. Re-exporting the live App Store
listing (GET-only, needs the App Store Connect key in the environment) is
`run.py --app-root . ios export-listing`.

## Android closed beta

Android **1.0 (44)** is in Google Play closed testing (Alpha track), so the next
`android-v*` release needs a higher versionCode (the release checks this first).
The testers, countries, review status, and the `VERCELTICSBETA` promo code are
recorded in [google-play/TESTERS.md](google-play/TESTERS.md) and
[google-play/PROMO.md](google-play/PROMO.md).
