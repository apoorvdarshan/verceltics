# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

Monorepo with two independent apps that ship together but build separately:

```
verceltics/
├── ios/        # SwiftUI iOS/iPad app — App Store product
└── web/        # Next.js 16 landing page (verceltics.com) — privacy/terms/marketing
```

The two halves never import from each other. They share only the brand and the docs at the repo root.

## Commands

**iOS** — User builds and runs through Xcode manually. Do **not** invoke `xcodebuild`, `xcrun simctl`, or any iOS build CLI. Open `ios/verceltics.xcodeproj`, build with Cmd+B, run with Cmd+R, archive with Product → Archive. Xcode project uses **synchronized file groups** (`PBXFileSystemSynchronizedRootGroup`), so any new `.swift` file added on disk is automatically picked up by the build — no `pbxproj` edits needed.

**Web**
```bash
cd web
npm install
npm run dev      # localhost:3000
npm run build    # production build
npm start        # serve production build
```

**StoreKit testing** (in Xcode): Edit Scheme → Run → Options → StoreKit Configuration → select `ios/verceltics/Paywall/Products.storekit`. Then Debug → StoreKit → Manage Transactions to reset purchases.

There are **no automated tests, no lint config, no CI scripts**. Don't fabricate `npm test` or `swift test` — they don't exist.

## Working With This Codebase

**Auto-commit + push after code changes, no co-author.** This is a hard rule from the repo owner — every functional change should end with `git add -A && git commit -m "..." && git push`. Use a HEREDOC for multi-line messages.

**Don't `xcodebuild` or `simctl`.** The owner builds manually in Xcode.

**SourceKit "Cannot find type" / "Cannot find 'X' in scope" errors are usually noise.** When you edit a file in `Views/` or `Components/`, the SourceKit linter may flag types defined in `Models/`, `Auth/`, or `Network/` as missing. They aren't — they're in the same target, just in different files. The errors clear on the next Xcode rebuild. Don't restructure code to "fix" them.

## iOS Architecture (the parts that span multiple files)

**Concurrency**: `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` is set in the project, so every type is `@MainActor` by default. Pure data models (`Project`, `Analytics*`, `BreakdownItem`, etc.) and pure helpers (`fetchImageData`, `removeWhiteBackground`, `looksLikeSVG`, `rasterizeRemoteSVG`) must be marked `nonisolated` so they can be decoded off-main and used from `actor VercelAPI` without compiler errors. The Decodable conformances in particular **require** `nonisolated`.

**Soft paywall flow**: `VercelticsApp` sends every authenticated user straight to `MainTabView` regardless of subscription state. The paywall is a sheet presented from `ProjectsView` only when a user taps a project without `paywallManager.hasActiveSubscription`. After a successful purchase, `PaywallView` auto-dismisses (via `onChange(of: paywall.hasActiveSubscription)`) and `ProjectsView.handlePaywallDismiss()` continues into the originally-tapped project's analytics. **Never re-introduce a launch-time paywall gate.**

**Rate prompt**: lives in `ProjectsView.maybeRequestReview()`, gated by `@AppStorage("hasShownOnboardingRatePrompt")`. Fires for both free and paid users 3 seconds after the first successful project load. Don't move it back to `MainTabView`.

**Pricing tiers** (StoreKit 2):
- `com.apoorvdarshan.verceltics.monthly` — $4.99, no trial
- `com.apoorvdarshan.verceltics.yearly` — $34.99, 7-day trial (configured `P1W` in `Products.storekit`; production duration is set in App Store Connect)
- `com.apoorvdarshan.verceltics.lifetime` — $59.99, **non-consumable** (not a subscription)

`PaywallManager.hasActiveSubscription` returns true for any of the three because `Transaction.currentEntitlements` emits the non-consumable alongside subscriptions. The trial duration shown in the paywall badge is read live from `paywall.yearlyProduct?.subscription?.introductoryOffer.period` — don't hardcode "7-day" / "3-day" anywhere.

**Domain resolution** (`Models/Project.swift` + `Network/VercelAPI.swift`):
1. `primaryDomain` walks `lastDeployment.alias`, `targets.production.alias`, project-level `alias`, and `customEnvironments.preferredDomains`, deduplicates, and returns the first non-`vercel.app` host (or shortest `*.vercel.app` if no custom domain).
2. The bulk `/v9/projects` listing sometimes omits short aliases. `enrichProjectsNeedingDomainRefresh` calls `/v9/projects/{id}/domains` for any project flagged by `needsPrimaryDomainRefresh`, then **merges every verified non-redirect domain** into the project's `alias` array (`alias` is therefore `var`, not `let`). The `primaryDomain` selection then picks correctly.
3. `teamId` is computed: only forwarded to the API if `accountId` starts with `team_`. User-prefixed account IDs (`user_…`) must NOT be sent as `teamId` — Vercel rejects them.

**Favicon chain** (`Views/ProjectsView.swift` `ProjectIcon`):
1. Direct paths race in parallel: `apple-touch-icon.png`, `favicon-192x192.png`, `favicon-96x96.png`, `favicon.png`, `favicon.ico`, `icon.png`, `icon.svg`.
2. Hosts probed: bare host always; `www.<host>` only if the host has fewer than 2 dots (apex domains). **Never probe `www.<sub>.vercel.app`** — Vercel's TLS cert is `*.vercel.app` (single-level wildcard), so `www.foo.vercel.app` fails ATS and stalls the connection pool for ~5s per request.
3. SVG responses are routed through `images.weserv.nl/?url=…&output=png` for server-side rasterisation. UIImage cannot decode SVG natively. The proxy URL must be **manually fully percent-encoded** (alphanumerics + `-._~` only) — `URLComponents` leaves `?`, `:`, `/` un-encoded which iOS 17+ `URL(string:)` may reject when the inner URL has its own query string.
4. WKWebView is **not** used for SVG rendering anymore. The offscreen WebContent process lacks emoji/font entitlements and produces blank snapshots that fool size checks.
5. Inline `data:image/svg+xml` hrefs from HTML scrape are skipped (no remote URL to feed the proxy) — the third-party fallback chain (DuckDuckGo, Google s2, icon.horse) handles those.

**Animation rule**: never use `withAnimation { state = ... }` inside `.onAppear`. The transaction leaks onto sibling state changes during the animation lifetime and causes "bouncing" of unrelated views (the live-deploy dot pulse used to drag the repo capsule with it). Always use scoped `.animation(.spring(...), value: someState)` instead.

**Hit-testing**: any `Button` whose label is an `HStack` with a `Spacer()` between content and a chevron needs `.contentShape(Rectangle())` on the label, otherwise the gap isn't tappable. See `AboutRowContent`.

**iPad adaptation**: views inject `@Environment(\.horizontalSizeClass)` and switch to `LazyVGrid(columns: [.adaptive(minimum: 340, maximum: 520)])` for regular size class, and constrain `frame(maxWidth: 1100-1200)` so cards don't stretch full-width on big screens. `MainTabView` uses `.tabViewStyle(.sidebarAdaptable)` for the iPad sidebar.

## Visual Style

The card chrome across `ProjectCard`, `StatCard`, breakdown cards, About sections, paywall plans, and the chart container is intentionally consistent. New cards must match it:

- Background: two stacked `LinearGradient`s — diagonal `0.07 → 0.02` white plus top-highlight `0.04 → clear`
- `.strokeBorder(LinearGradient(colors: [0.12, 0.04] white))` at `lineWidth: 0.5`
- Corner radius `18` for cards, `14` for tiles, `10` for icon backgrounds, all `style: .continuous`
- Press feedback via `.buttonStyle(PressScaleButtonStyle())` (defined in `LoginView.swift`, accessible target-wide)
- Numbers use `.system(size:weight:design:)` rounded heavy + `.monospacedDigit()`
- Blue accent: system `.blue` paired with `Color(red: 0.45, green: 0.65, blue: 1.0)` for gradients
- Green positive / soft red negative: `Color(red: 0.30, green: 0.85, blue: 0.55)` and `Color(red: 1.0, green: 0.42, blue: 0.42)`

`CONTRIBUTING.md` has the full style guide.

## Web App

Next.js 16 / React 19 / Tailwind 4. Deployed on Vercel with the project's **Root Directory set to `web/`** and a custom Ignored Build Step:

```
git diff HEAD^ HEAD --quiet -- ./
```

This command runs from inside the Root Directory, so `./` resolves to `web/`. iOS-only commits don't trigger a web rebuild. **Don't change this command to `./web` — it would break.**

The web app talks to no backend. It's purely marketing pages plus `@vercel/analytics` for traffic.

## External Services

| Service | Used for | Sends user's Vercel token? |
|---|---|---|
| `api.vercel.com` | Project listing, project detail, `/domains` | ✅ |
| `vercel.com/api` | Analytics overview, timeseries, breakdowns | ✅ |
| `images.weserv.nl` | SVG → PNG rasterisation | ❌ (favicon URL only) |
| `icons.duckduckgo.com` | Favicon fallback | ❌ |
| `www.google.com/s2/favicons` | Favicon fallback | ❌ |
| `icon.horse` | Favicon fallback | ❌ |

Token is stored in iOS Keychain via `Auth/KeychainHelper.swift`. It never crosses the boundary into web.
