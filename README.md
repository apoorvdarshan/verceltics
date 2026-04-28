<p align="center">
  <img src="docs/icon.png" width="100" style="border-radius: 20px" />
</p>

<h1 align="center">Verceltics</h1>

<p align="center">
  Vercel Web Analytics on your iPhone.<br>
  <a href="https://apps.apple.com/us/app/verceltics/id6761645656">App Store</a> · <a href="https://verceltics.com">Website</a>
</p>

<p align="center">
  <a href="https://github.com/apoorvdarshan/verceltics/releases/latest"><img src="https://img.shields.io/github/v/release/apoorvdarshan/verceltics?label=release&color=d6ff5c" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" /></a>
  <a href="https://swift.org"><img src="https://img.shields.io/badge/Swift-6-orange.svg" /></a>
  <a href="https://developer.apple.com/ios/"><img src="https://img.shields.io/badge/Platform-iOS%2018%2B-black.svg" /></a>
  <a href="https://github.com/apoorvdarshan/verceltics/stargazers"><img src="https://img.shields.io/github/stars/apoorvdarshan/verceltics?style=flat&color=yellow" /></a>
</p>

## Screenshots

| Projects | Analytics |
|:---:|:---:|
| ![Projects](docs/screenshots/projects.png) | ![Analytics](docs/screenshots/analytics.png) |
| All your Vercel projects — favicons, framework dot, last commit, live deploy indicator | Visitors, page views, bounce rate, and the interactive chart with peak + average markers |

| Pages & Routes | Countries & Devices | Deep Breakdowns |
|:---:|:---:|:---:|
| ![Pages and Routes](docs/screenshots/breakdowns.png) | ![Countries and Devices](docs/screenshots/referrers.png) | ![Devices and OS](docs/screenshots/devices.png) |
| Pages, routes, hostnames, and referrers — ranked by visitors | Countries with flags, devices, and browsers | Operating systems, events, flags, and query parameters |

## Features

- **Projects Dashboard** — All your Vercel projects with favicons, git repo, last commit, framework
- **Live Deploy Indicator** — Pulsing green dot when a deployment is < 30 minutes old
- **Framework-tinted dots** — Astro orange, Vite purple, Remix cyan, Angular red, Eleventy yellow, etc.
- **Analytics** — Visitors, page views, bounce rate with % change badges and staggered entrance
- **Interactive Chart** — Peak indicator, average reference line, drag-to-inspect with haptic feedback
- **Full Breakdowns** — Pages, routes, hostnames, referrers, UTM, countries, devices, browsers, OS, events, flags, query params
- **Soft Paywall** — Browse projects free; analytics gated per project tap
- **Robust Favicons** — Multi-source race (apple-touch-icon, scrape, weserv.nl SVG rasterise, DuckDuckGo, Google s2, icon.horse)
- **Search** — Filter projects by name, domain, or framework
- **Pull to Refresh** — Live data from Vercel API
- **iPad** — Adaptive grid + sidebar tab style on regular size class
- **Dark Mode** — Pure black (#000000) Vercel-style design
- **Secure** — Token stored in iOS Keychain, open source code

## Pricing

| Plan | Price | Trial |
|---|---|---|
| Monthly | $4.99 | — |
| Yearly | $34.99 | 7-day free trial |
| Lifetime | $59.99 | — (one-time purchase) |

Build from source for free with your own Vercel token. App Store distribution exists for convenience and to fund development.

## Tech Stack

**iOS**
- **SwiftUI** — Entire UI, layered gradient cards, scoped animations
- **Swift Charts** — Interactive line + area chart with peak / average / drag-select
- **Swift 6** — Strict concurrency (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`)
- **StoreKit 2** — Auto-renewable subscriptions + non-consumable lifetime
- **Keychain** — Secure token storage
- **async/await** + **actors** — All API calls
- **Zero dependencies** — No third-party libraries

**Web**
- **Next.js** — Landing page, privacy, terms
- **Tailwind CSS** — Styling
- Deployed on **Vercel**

## Repository Structure

This is a monorepo containing both the iOS app and the landing page:

```
verceltics/
├── ios/          # SwiftUI iOS app
└── web/          # Next.js landing page (verceltics.com)
```

## Setup (iOS)

1. Clone the repo
   ```bash
   git clone https://github.com/apoorvdarshan/verceltics.git
   ```
2. Open `ios/verceltics.xcodeproj` in Xcode
3. Select your team in Signing & Capabilities
4. Build and run (iOS 18.0+)

## Setup (Web)

```bash
cd web
npm install
npm run dev
```

### Vercel Token

The app uses a [Vercel personal access token](https://vercel.com/account/tokens) for authentication:

1. Go to [vercel.com/account/tokens](https://vercel.com/account/tokens)
2. Create a token with your account scope
3. Paste it in the app

### StoreKit Testing

To test the paywall in Xcode:

1. Edit Scheme → Run → Options → StoreKit Configuration → select `ios/verceltics/Paywall/Products.storekit`
2. Build and run
3. Use Debug → StoreKit → Manage Transactions to reset purchases

The local config has all three products: monthly, yearly (with 7-day intro offer), and lifetime non-consumable. Production prices and trial duration are set independently in App Store Connect.

## API

The app uses two Vercel API hosts:

| Host | Endpoints | Auth |
|------|-----------|------|
| `api.vercel.com` | `/v9/projects` | Bearer token |
| `vercel.com/api` | `/web-analytics/*` | Bearer token |

Analytics endpoints use `groupBy` parameter: `path`, `route`, `hostname`, `referrer`, `utm`, `country`, `device_type`, `client_name`, `os_name`, `event_name`, `flags`, `query_params`

## iOS Project Structure

```
ios/verceltics/
├── App/VercelticsApp.swift          # Entry point, soft paywall routing
├── Auth/
│   ├── AuthManager.swift            # Token validation, login/logout
│   └── KeychainHelper.swift         # Secure token storage
├── Network/VercelAPI.swift          # All API calls (actor-based)
├── Models/
│   ├── Project.swift                # Project, deployment, alias, /domains
│   └── Analytics.swift              # Analytics data models, time ranges
├── Views/
│   ├── LoginView.swift              # Token login with animated demo chart
│   ├── MainTabView.swift            # Tab bar (Projects, About, Search)
│   ├── ProjectsView.swift           # Project list, favicons, paywall sheet
│   ├── AnalyticsView.swift          # Full analytics dashboard
│   └── AboutView.swift              # Support, links, legal, sign out
├── Components/
│   ├── StatCard.swift               # Metric card with change badge
│   ├── AnalyticsChart.swift         # Interactive Swift Charts line graph
│   └── Shimmer.swift                # Loading skeleton shimmer modifier
└── Paywall/
    ├── PaywallManager.swift         # StoreKit 2 purchase logic
    ├── PaywallView.swift            # Subscription + lifetime paywall UI
    └── Products.storekit            # StoreKit testing config
```

## Disclaimer

Verceltics is **not** affiliated with, endorsed by, or sponsored by Vercel Inc. Vercel and the Vercel logo are trademarks of Vercel Inc. This is an independent, open-source project that uses Vercel's API with user-provided authentication tokens.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

[MIT](LICENSE)

## Contact

- **Email**: ad13dtu@gmail.com
- **X**: [@apoorvdarshan](https://x.com/apoorvdarshan)
- **Issues**: [github.com/apoorvdarshan/verceltics/issues](https://github.com/apoorvdarshan/verceltics/issues)
- **Security**: see [SECURITY.md](SECURITY.md) for private vulnerability reporting

---

<p align="center">Built with ❤︎ by <a href="https://x.com/apoorvdarshan">Apoorv Darshan</a></p>
