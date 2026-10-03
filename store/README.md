# iOS release setup

This is the iOS portion of Fud AI's release approach, adapted to Verceltics.
All GitHub store switches are **false**. Xcode Cloud's tag workflow is configured
for the `ios-v` prefix and **disabled** during setup. No release tag is created.

## Independent switches

| Setting | Initial value | Effect when enabled |
| --- | --- | --- |
| `STORE_IOS_RELEASE_ENABLED` | `false` | Allows the tag workflow to prepare metadata and create a GitHub release. Required for any ASC operation. |
| `STORE_UPLOAD_LISTING` | `false` | Updates editable App Store version description, keywords, promotional text, What's New, marketing URL, and support URL. |
| `STORE_UPLOAD_SCREENSHOTS` | `false` | Replaces the editable version's 6.7-inch iPhone screenshots with reviewed PNGs. |
| `STORE_SUBMIT_IOS_REVIEW` | `false` | Submits the selected App Store build and eligible products in this app's catalog for review. |
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
2. Add reviewed `## ios-vX.Y` notes to `RELEASE_NOTES.md`. There is deliberately
   no release-ready section yet. Tag validation rejects absent or empty notes.
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
already exist and be editable, with the intended processed build selected before
review submission. The GitHub job does not wait for Xcode Cloud; leave submission
off for the initial tag, and rerun after selecting the build when ready.
Public store release timing remains controlled in App Store Connect.

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

Screenshot replacement can leave a mixed set if an upload fails; inspect ASC
before rerunning. App name, subtitle, privacy details, reviewer notes, pricing,
and other submission requirements remain managed in App Store Connect.
