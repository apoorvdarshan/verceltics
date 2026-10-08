# Native mobile architecture

Verceltics has two independent native mobile applications:

- `ios/` is SwiftUI-only. Its existing provider clients, state, Keychain records, local snapshots, navigation, and Liquid Glass integrations remain native iOS code.
- `android/` is Kotlin and Jetpack Compose-only. Android-native lifecycle, input, haptics, accessibility, storage, and Material behavior live in this project.

There is no Flutter or Dart runtime, generated Flutter bridge, or shared cross-platform UI module in either application. Provider identifiers and API behavior are ported deliberately, but platform UI, lifecycle, and secure storage are not shared binaries.

## Data and backend boundaries

- Restoring the SwiftUI application does not migrate, rewrite, or delete existing iOS Keychain records, preferences, or protected snapshots.
- Android has a separate application sandbox. Credentials are encrypted with keys held by Android Keystore and stored only in app-private, backup-excluded storage; ordinary preferences must never contain credentials.
- Credentials and provider data are not copied between iOS and Android. Installing or updating one platform cannot alter the other platform's data.
- The existing iOS provider clients and request policies remain unchanged during the Android migration.
- Provider requests continue to go directly from the device to the selected provider's HTTPS API. The Android port must preserve the same host validation, redirect, timeout, response-size, cancellation, and destructive-action safeguards before a provider is marked complete.

## Screen-by-screen migration rule

Each Android provider moves through the same gates:

1. Preserve the canonical provider identifier and authentication contract.
2. Build the native Compose connection, loading, empty, error, search, refresh, and detail states.
3. Add Android Keystore-backed account persistence and the provider HTTPS client.
4. Cover domain and request behavior with unit tests.
5. Exercise the user-visible flow on a dedicated Android emulator and inspect screenshots, the accessibility tree, and runtime logs.

A catalog entry is not considered provider parity. A provider is complete only after all relevant gates pass.

## Screen-by-screen status

| Platform / slice | Status | Scope |
|---|---|---|
| iOS SwiftUI | Preserved | Existing UI, Liquid Glass integration, local data, and all 27 provider integrations remain native and operational. |
| Android app shell and catalog | Implemented | Native Compose navigation and discoverability for 10 hosting providers, 8 registrars, and 9 site services. |
| Android Vercel | Implemented | Token connection and validation, protected account persistence, projects, analytics, loading/error/empty states, search, refresh, and details. |
| Android PageSpeed & CrUX | Implemented | Protected API-key connection, Lighthouse and field-data audits, cached restore, history, loading/error/empty states, refresh, and details. |
| Android Netlify | Implemented read-only flow | Protected token connection, cached restore, sites, domains, build controls, published deployments, deploy history, build history, cancellation reconciliation, refresh, and details. Mutations remain intentionally excluded. |
| Android Cloudflare | Implemented | Protected scoped-token connection, cached restore, account switching, searchable inventory, zone/DNS/security operations, Pages and Workers operations, D1/KV/R2 storage, and Advanced tools behind Pro: API Explorer, complete OpenAPI catalog (3,324 operations), GraphQL dataset directory, guided product operations, account detail and operations. Every mutation is confirmed. |
| Android Google Search Console | Implemented read-only flow | Native PKCE Google OAuth, encrypted credential restore/refresh, cached searchable properties, performance query controls and pagination, sitemaps, URL inspection, adaptive Compose states, cancellation reconciliation, and tests. A source build must supply its own Google Android OAuth client configuration. |
| Android Verceltics Pro | Implemented | RevenueCat paywall with the iOS plans, copy, and soft gating: lists stay free, while project analytics, Netlify sites, Cloudflare resources, Search Console properties, and the full PageSpeed breakdown require Pro. Includes restore, Google Play subscription management, and the in-app tip jar. Needs the RevenueCat Google Play key and Play Console products before testers can buy. |
| Android hosting platforms | Implemented | Railway, Render, DigitalOcean, Heroku, Fly.io, Firebase Hosting (Google sign-in) and AWS Amplify (SigV4): protected connections, cached restore, resources, deployments, confirmed primary actions, refresh and details. |
| Android registrars | Implemented | Name.com, Namecheap, Porkbun, Spaceship, Dynadot, NameSilo, Gandi and GoDaddy: protected connections, domain portfolio, expiry health, search, refresh and domain details. |
| Android site services | Implemented | Google Analytics 4 (Google sign-in), Bing Webmaster, Microsoft Clarity, Plausible, Umami (Cloud and self-hosted), UptimeRobot and Better Stack: protected connections, overviews, search, refresh and full detail reports. |
| Android API explorers | Implemented | Complete API catalogs from the bundled ProviderAPICatalog.json and CloudflareAPICatalog.json (Railway discovers its live GraphQL schema), operation detail, raw explorers with likely-write confirmation, protected headers, host-locked paths, SigV4 for Amplify, binary/multipart uploads and the response viewer, all behind Pro. |

The matrix describes source parity, not store availability. It should be updated whenever a provider passes or falls back from the completion gates above.

## Continuous integration

CI keeps the native projects independent:

- iOS tests run with `xcodebuild` against an iOS simulator.
- Android runs JVM tests, Android Lint, and a debug assembly through the Gradle wrapper.
- No Flutter bootstrap, analysis, test, or framework-generation step is required.
