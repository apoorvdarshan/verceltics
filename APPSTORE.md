# App Store Listing

The App Store listing text lives in [`store/metadata/ios/en-US/`](store/metadata/ios/en-US/),
one file per field, exported from the live App Store listing (iOS 2.1):

| File | App Store Connect field | Limit |
| --- | --- | --- |
| `name.txt` | App Name | 30 characters |
| `subtitle.txt` | Subtitle | 30 characters |
| `promotional_text.txt` | Promotional Text | 170 characters |
| `keywords.txt` | Keywords | 100 characters |
| `description.txt` | Description | 4000 characters |
| `privacy_url.txt` | Privacy Policy URL | HTTPS |
| `support_url.txt` | Support URL | HTTPS |
| `marketing_url.txt` | Marketing URL | HTTPS |

What's New is the tag's `## ios-vX.Y` section in [RELEASE_NOTES.md](RELEASE_NOTES.md).
Screenshots are the App Store-ready mockups in [`store/app-store/screenshots/iphone/`](store/app-store/screenshots/iphone/)
(iPhone 6.7", 1290×2796) and [`store/app-store/screenshots/ipad/`](store/app-store/screenshots/ipad/) (iPad 13", 2048×2732).
`ios-release.yml` uploads each of these only when its switch is on; see
[store/README.md](store/README.md). Check them with `python3 scripts/store/ios_metadata.py check`,
and re-export after editing the listing in App Store Connect with
`scripts/store/export_ios_listing.py`.

The fields below are managed in App Store Connect by hand.

## Category
```
Primary: Developer Tools
Secondary: Utilities
```

## Terms URL
```
https://verceltics.com/terms
```

## Reviewer Notes
```
This app uses provider API credentials or Google OAuth and does not ask for provider account passwords. Credentials and OAuth tokens are stored locally with device-only Keychain protection and are not sent to Verceltics infrastructure.

To test:
1. On the first 2.1 launch, tap "Close" on the optional developer/community introduction to enter the app. The introduction stays pending until explicitly closed. On a clean install, then tap "Choose what to connect" on the connection welcome screen and choose Hosting, Registrars, or Sites. Existing connected users skip the connection welcome.
2. Select Vercel and paste a personal access token from https://vercel.com/account/tokens, or choose another provider and follow its on-screen credential instructions.
3. Google Search Console, Google Analytics, and Firebase Hosting use Google's OAuth authorization flow instead of a pasted password.
4. Hosting accounts appear in Hosting, registrar accounts in Registrars, and site-service accounts in Sites. After connecting an account, use that workspace's top-left account menu to add or switch accounts.

Connection, account switching, search, refresh, scrolling, and workspace-list browsing are available without a purchase. Opening a project, hosting resource, domain, site-service detail, provider dashboard, API catalog, or guarded provider action presents Verceltics Pro unless a sandbox entitlement is active. Use an Apple sandbox/TestFlight Monthly, Yearly, or Lifetime purchase to review those details and actions. To review Vercel Web Analytics, connect a free Vercel account with a deployed project, enable Web Analytics, and generate traffic; availability and history depend on the Vercel plan and configuration.

Some Vercel Web Analytics fields, add-ons, and historical ranges depend on the connected account's plan and Web Analytics configuration. When analytics are unavailable, Verceltics shows an explanatory state and continues to display project, domain, and deployment details.

The app offers Monthly ($4.99 with no trial), Yearly ($34.99 with a 7-day introductory trial for eligible first-time subscribers), and Lifetime ($59.99 one-time) purchase options in the US storefront. Apple localizes prices for other storefronts. Purchases are processed by Apple and entitlement state is managed through RevenueCat.

The app also offers four optional consumable tip purchases in the About tab: Coffee ($2.99), Lunch ($5.99), Big Tip ($9.99), and Huge Supporter ($19.99) in the US storefront. Tips are voluntary, processed by Apple through the RevenueCat SDK, and unlock no content, features, or functionality.

Refund decisions are made by Apple under its policies; Verceltics does not issue App Store refunds directly.

Verceltics supports iPhone and iPad and requires iOS 18 or iPadOS 18 or later. iPad uses sidebar-adaptable navigation, adaptive grids, and wider detail layouts.
```
