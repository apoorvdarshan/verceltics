# Contributing to Verceltics

Verceltics includes a SwiftUI iOS app, a Kotlin and Jetpack Compose Android app,
a Next.js website, and a Cloudflare Worker that also runs Vercie on Discord.
Community participation follows our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting Started

1. Fork the repo
2. Clone your fork
   ```bash
   git clone https://github.com/YOUR_USERNAME/verceltics.git
   ```
3. Set up the component you want to change using the prerequisites below
4. Create a branch
   ```bash
   git checkout -b feature/your-feature
   ```
5. Make your changes
6. Run the relevant checks below and describe the results in your pull request
7. Commit and push
8. Open a Pull Request

## Prerequisites and checks

| Component | Setup |
| --- | --- |
| iOS | Open `ios/verceltics.xcodeproj` in Xcode. The deployment target is iOS/iPadOS 18.0 or later. Select your own development team for device builds. |
| Android | Open `android/` in Android Studio. Use JDK 17 and Android SDK Platform 37.0. The minimum supported OS is Android 9 (API 28). |
| Website and Worker | Use Node.js 22 and run `npm ci` in `web/`. Use `npm run dev` for the Next.js website or `npm run preview` to build the static site and run it with the Worker locally. |
| Discord bot | Vercie runs in the website Worker. Follow [the bot setup guide](services/discord-bot/README.md) for Discord registration, Gemini, GitHub, and Cloudflare configuration. |

Android provider support is being migrated incrementally. Check the
[native mobile architecture and migration matrix](docs/native-mobile-architecture.md)
before assuming a provider workflow is available on both platforms. For personal
Google OAuth configuration, see [Run the Android app](README.md#run-the-android-app).

Run the repository's Python checks from the root; CI uses Python 3.13:

```bash
./scripts/test.sh
```

For iOS changes, build and run the `verceltics` scheme and its tests in Xcode on
an available iOS Simulator. For Android changes, the CI checks are:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

For website and Worker changes, run these checks from `web/`:

```bash
npm ci
npm audit --audit-level=moderate
npm run test:worker
npm run test:fuzz
npm run build
```

The audit includes development dependencies because Wrangler's local server and
deployment tools execute on developer machines and CI. The `undici` override
pins the patched 7.29.1 release while the current Wrangler/Miniflare dependency
chain requests 7.29.0. Remove the override once the upstream dependency chain
resolves a patched release, and rerun the full audit.

The fuzz tests use `fast-check` as a development-only dependency to exercise
untrusted query parameters and upstream responses. Failures include a seed and
replay path for reproducing and shrinking the failing input.

## Guidelines

- **Native UI** — Use SwiftUI for iOS and Kotlin with Jetpack Compose for Android. On iOS, avoid UIKit wrappers unless necessary.
- **No new third-party dependencies without discussion** — RevenueCat is already used for App Store entitlements; keep everything else lean
- **All appearances** — Every screen must work in System, Light, and Dark appearance. Use iOS `AppTheme` or Android `VercelticsTheme` and `MaterialTheme`; do not hardcode a one-mode palette.
- **Deployment targets** — iOS/iPadOS 18.0+ and Android API 28+.
- **Swift concurrency** — The iOS project uses Swift 5 language mode with `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`. Mark pure data models and helpers `nonisolated`; use `async`/`await`, actors, and `@Observable`.
- **iPad-aware** — On iOS, avoid arbitrary fixed content widths; respect `@Environment(\.horizontalSizeClass)`, shared `AppLayout` limits, and adaptive grids
- **Provider boundaries** — Use the device-only Keychain on iOS and the existing Android Keystore-backed encrypted account storage on Android. Credentials travel only to the selected provider's allowed HTTPS hosts. Preserve redirect restrictions and the app's confirmation boundaries for supported writes, purchases, and destructive requests.
- **Catalog integrity** — Update generated provider operation catalogs through the scripts in `scripts/`; do not hand-edit generated JSON without updating its source and tests.
- **Keep it minimal** — Don't add features nobody asked for

## Visual Style

`ios/verceltics/Components/ProviderVisuals.swift` is the reference design system.
Use the shared iOS primitives:

- **Canvas and surfaces**: `AppTheme.canvas`, `surface`, and `surfaceRaised` adapt across Light and Dark appearance.
- **Text and state**: use `textPrimary`, `textSecondary`, `textTertiary`, `signal`, `success`, `warning`, and `danger`.
- **Cards**: prefer `.appSurface()` for neutral surfaces and `.providerSurface(accent:)` when provider identity matters.
- **Radii**: `AppTheme.panelRadius` (16), `controlRadius` (13), and `iconRadius` (10), always with continuous corners.
- **Provider color**: reserve it for identity, a thin accent rail, an icon tile, or status—not decorative page gradients.
- **Press feedback**: wrap interactive surfaces in `Button` and apply `.buttonStyle(PressScaleButtonStyle())`.
- **Hit testing**: HStacks with `Spacer()` need `.contentShape(Rectangle())` so the gap is tappable.

On Android, reuse `VercelticsTheme` in
`android/app/src/main/java/com/apoorvdarshan/verceltics/ui/theme/` and the shared
Compose controls in `ui/components/`. Colors, typography, and shapes come from
`MaterialTheme`. Keep spacing, navigation, and information hierarchy consistent
with the iOS reference while preserving native Android interactions.

## iOS Code Style

- Use SF Symbols for icons (heavy / bold weights for accent dots)
- Use `.system(size:weight:)` fonts with explicit sizes; rounded design + monospaced digits for numbers
- Use `RoundedRectangle(cornerRadius:style: .continuous)` for shapes — never `.circular`
- Use semantic `AppTheme` colors instead of white-opacity ladders so contrast remains correct in every appearance.
- Animations should be **scoped** with `.animation(.spring(...), value: someState)`. Avoid `withAnimation { state = ... }` inside `onAppear` — that leaks the transaction onto sibling state changes (causes "bouncing" siblings)
- Haptics: use `.sensoryFeedback(.selection, trigger: value)` on selectable controls and `.impact(weight: .light)` on tap-to-refresh actions

## iOS Architecture Notes

- **Connection routing**: `VercelticsApp` opens `MainTabView` when at least one hosting, registrar, or site-service account exists. With no connections it opens `FirstConnectionFlow`: a one-time welcome followed by the `LoginView` provider catalog. Existing connected users and active subscribers are migrated past the welcome.
- **Soft paywall**: connection, account switching, search, refresh, scrolling, and workspace-list browsing stay available without Pro. Opening item details, provider dashboards, API catalogs, or guarded actions presents the reusable paywall sheet when there is no active entitlement. Don't add launch-time paywall gates.
- **Rate prompt**: lives in `ProjectsView` and fires once via `@AppStorage("hasShownOnboardingRatePrompt")` after a successful project load — works for both free and paid users.
- **Favicon chain**: `ProjectIcon` performs bounded, credential-free discovery against the project site's own HTTPS origin and otherwise draws a local fallback. Do not add third-party favicon services or probes for `www.<sub>.vercel.app`; Vercel's wildcard certificate does not cover sub-subdomains and ATS will fail.
- **Public IPv4 helper**: registrar setup uses `PublicIPv4Lookup` for a bounded, credential-free lookup. Namecheap stores the explicitly accepted, validated public IPv4 as required `ClientIp` metadata and sends it with Namecheap API requests. Name.com only writes the address to the system pasteboard after an explicit Copy action; it must never save or send it as an API credential.
- **Domain resolution**: when a project's bulk listing only shows `*.vercel.app`, `enrichProjectsNeedingDomainRefresh` calls `/v9/projects/{id}/domains` and merges the verified entries into the project's alias array so `primaryDomain` picks the best one.
- **Sites cache**: site-service snapshots may be written to the file-protected, backup-excluded Application Support cache. Credentials and OAuth tokens must never be moved out of the Keychain.

## Android and Worker Architecture Notes

- **Android account storage**: `AndroidKeystoreAccountCipher` encrypts account payloads with AES-256-GCM using a non-exportable Android Keystore key. `NoBackupAtomicFileStore` writes the encrypted files under `noBackupFilesDir`. Reuse these boundaries rather than writing credentials to ordinary preferences or logs.
- **Android networking**: `SecureProviderHttpClient` and `ProviderEndpointPolicy` enforce provider HTTPS destinations, redirect rules, response-size limits, and cancellation. Use the provider gateways and repositories when adding screens.
- **Android previews**: sample gateways are separate from live account gateways. Keep sample data and credentials separate; the migration matrix records the supported workflows.
- **Website and Worker**: Next.js builds static assets; `web/worker/index.mjs` handles server endpoints. Worker secrets belong in Cloudflare secret storage, not browser code or committed configuration.
- **Discord**: Vercie handles signed HTTP interactions and scheduled release checks in the Worker. Preserve application/server validation, cooldowns, and the public-report disclosures. Only text explicitly submitted through commands is sent to Gemini; the bot does not ingest channel history or mobile-app credentials. See [the bot guide](services/discord-bot/README.md) for its boundaries and configuration.

## What to Contribute

- Bug fixes
- Performance improvements
- Better error handling
- Accessibility improvements
- UI polish
- Provider dashboards, integrations, and data breakdowns supported by the relevant official API
- Safer operation coverage and provider-catalog updates with tests
- Localizations
- New framework dot colors in `ProjectCard.frameworkColors`

## What NOT to Contribute

- Third-party dependencies
- Major architecture changes without discussion
- Features that don't align with the app's purpose
- One-off colors or cards that bypass the shared adaptive theme on either platform
- Credential proxies, provider-data telemetry, or third-party favicon services
- Launch-time paywalls — the soft paywall flow is deliberate
- Weekly subscription tiers — at sub-$5 monthly the abuse cost is too low to deter, and the dev community treats weekly subs in tooling apps as a red flag. Trial is yearly-only on purpose.

## Reporting Issues and Requesting Features

- [Report a bug](https://github.com/apoorvdarshan/verceltics/issues/new?template=bug_report.yml)
- [Request a feature](https://github.com/apoorvdarshan/verceltics/issues/new?template=feature_request.yml)
- [Join the Verceltics Discord community](https://discord.gg/qv5nsTmkxA) for help, or use Vercie's `/bug` and `/feature` commands to create an issue.

Bug reports should include:

1. What happened
2. What you expected
3. Steps to reproduce
4. Platform, app version/build, OS version, and device

GitHub reports and issues created by Vercie's `/bug` and `/feature` commands are
**public**. Successful Discord replies and failures after processing starts are
visible in their channel; immediate validation errors are private to the caller.
Submitted text may be processed by Google Gemini. Remove tokens, credentials, and private
account details from reports and screenshots. For security vulnerabilities,
follow the private reporting process in [SECURITY.md](SECURITY.md) instead.

## Contact

- **Email**: ad13dtu@gmail.com
- **Discord**: [Verceltics community](https://discord.gg/qv5nsTmkxA)
- **X**: [@apoorvdarshan](https://x.com/apoorvdarshan)
- **LinkedIn**: [Verceltics](https://www.linkedin.com/showcase/verceltics)
- **Support**: [ko-fi.com/apoorvdarshan](https://ko-fi.com/apoorvdarshan)

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
