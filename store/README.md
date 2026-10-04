# iOS release setup

This is the iOS portion of Fud AI's release approach, adapted to Verceltics.
The authorized 2.1 release uses the `ios-v2.1` tag. The GitHub master,
listing upload, and review submission switches, and the independent Xcode Cloud
workflow are enabled. Screenshot replacement is off to retain the existing store gallery.

App Store Connect version 2.1 is configured for automatic release after approval.
The existing four iPhone and four iPad screenshots were inherited by this version.

Track the current tagged submission in the
[iOS Release workflow](https://github.com/apoorvdarshan/verceltics/actions/workflows/ios-release.yml).

The prepared iOS source version is **2.1 (build 43)**; the public App Store
release remains **2.0** until Apple approves and publishes 2.1. Reviewed notes
for `ios-v2.1` are in `RELEASE_NOTES.md`. App Store Connect controls automatic
publication after approval separately from the GitHub switches.

## Independent switches

| Setting | Initial value | Effect when enabled |
| --- | --- | --- |
| `STORE_IOS_RELEASE_ENABLED` | `false` | Allows the tag workflow to prepare metadata and create a GitHub release. Required for any ASC operation. |
| `STORE_UPLOAD_LISTING` | `false` | Updates editable App Store version description, keywords, promotional text, What's New, marketing URL, and support URL. |
| `STORE_UPLOAD_SCREENSHOTS` | `false` | Replaces the editable version's 6.7-inch iPhone screenshots with reviewed PNGs. |
| `STORE_SUBMIT_IOS_REVIEW` | `false` | Waits for the exact tagged Cloud run, selects its processed iOS build, then submits it and eligible catalog products for review. |
| Xcode Cloud workflow enabled | `false` | Independently allows Xcode Cloud to archive and upload tagged iOS builds. GitHub variables cannot turn it on or off. |

Unset GitHub variables also mean off. The master variable must be exactly `true`.
No Android release pipeline, Play upload, or RevenueCat catalog sync is included.

## Local validation without a build

```bash
python3 scripts/store/validate_ios_release.py
python3 scripts/store/prepare_ios_metadata.py --out /tmp/verceltics-store-meta
UPLOAD_LISTING=true python3 scripts/store/asc_release.py --dry-run --metadata-dir /tmp/verceltics-store-meta
python3 scripts/store/print_gates.py
```

These commands do not build, call ASC, grant entitlements, or publish a release.
The optional `Validate iOS store setup` Actions workflow performs the same local
checks without credentials. It is manual only and is not started by this setup.

## Future release procedure

1. Choose the iOS version/build and commit the source to release. Bump the build
   number for each uploaded binary; use a marketing version matching the tag.
2. Add reviewed `## ios-vX.Y` notes to `RELEASE_NOTES.md`.
   Tag validation rejects absent or empty notes.
3. Update `APPSTORE.md` and run the local checks. Tagged releases use the exact
   reviewed release notes for What's New, rather than a stale listing section.
4. When a build is actually wanted, enable the iOS workflow in Xcode Cloud. Its
   only automatic start condition should be tags beginning `ios-v`.
5. Enable `STORE_IOS_RELEASE_ENABLED` only when a GitHub release is wanted.
   Enable listing/screenshots/review switches independently, only when wanted.
6. Create and push the chosen `ios-vX.Y` tag. Old `v*` tags and Android tags do
   not match this release workflow or the configured Xcode Cloud start condition.

Xcode Cloud owns archive/signing and delivery to App Store Connect. The GitHub
workflow does not build an IPA or publish to TestFlight. A tag does not mean
Apple has approved the app or that it is live in the store.

For later metadata/review automation, the matching App Store version must
already exist and be editable. When review submission is enabled, the job:

1. Finds the Xcode Cloud run in workflow `2245A813-CA9D-45A4-8ACA-CF30494D2575`
   with the exact tag name and checked-out source commit SHA.
2. Waits for that run to finish successfully, then waits for its own linked iOS
   artifact to finish App Store processing. It verifies the app and marketing
   version; it never falls back to the newest unrelated uploaded build.
   Cloud collection entries may be incomplete, so each linked build is resolved
   through its canonical ASC resource before ownership and processing checks.
3. Selects the verified build on the matching App Store version, then submits
   the version and eligible catalog products for review.

The wait polls every 30 seconds for up to two hours. Failed/cancelled builds,
failed processing, expired artifacts, ambiguous runs/artifacts, and timeouts
stop submission. A retry of an already submitted version must still match its
selected build. ASC credentials refresh during long waits. The GitHub job has
a 150-minute overall limit. If the Cloud workflow is replaced, update the
publisher's default workflow ID (or supply `--ci-workflow-id`).

Metadata-only runs do not wait for Cloud. The script never starts or enables a
Cloud build, and disabled switches still skip all live ASC work. Public store
release timing remains controlled in App Store Connect; review submission does
not wait for Apple's review decision or change automatic/manual release settings.

## Credentials and product boundary

ASC calls require repository Secrets `APP_STORE_CONNECT_API_KEY_ID`,
`APP_STORE_CONNECT_ISSUER_ID`, and `APP_STORE_CONNECT_API_KEY_P8`.
Keys are encrypted in GitHub Secrets and never belong in this repository.
They are used only by the gated ASC step; offline validation needs none.

`scripts/store/asc_release.py` adapts Fud AI's publisher, including retry handling
for draft review submissions and eligible IAPs/subscriptions. It is limited to
`com.apoorvdarshan.verceltics` and the product IDs in `catalog/products.json`.
Local validation checks that catalog against the checked-in StoreKit reference.
The script creates no products and performs no RevenueCat synchronization.

The catalog explicitly declares `uses_non_exempt_encryption: false`: the app
uses Apple's HTTPS, Keychain, and CryptoKit implementations. The gated publisher
fills an unanswered build declaration from this setting and rejects a mismatch.
Reassess this declaration before adding other cryptographic implementations;
see [Apple's encryption guidance](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations).

Screenshot replacement can leave a mixed set if an upload fails; inspect ASC
before rerunning. App name, subtitle, privacy details, reviewer notes, pricing,
and other submission requirements remain managed in App Store Connect.
