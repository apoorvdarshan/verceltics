<p align="center">
  <img src="docs/icon.png" width="100" style="border-radius: 20px" />
</p>

<h1 align="center">Verceltics</h1>

<p align="center">
  Hosting, domains, analytics, and site health on your iPhone.<br>
  <a href="https://apps.apple.com/us/app/verceltics/id6761645656">App Store</a> · <a href="https://verceltics.com">Website</a>
</p>

<p align="center">
  <a href="https://github.com/apoorvdarshan/verceltics/releases/latest"><img src="https://img.shields.io/github/v/release/apoorvdarshan/verceltics?label=release&color=d6ff5c" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" /></a>
  <a href="https://swift.org"><img src="https://img.shields.io/badge/Swift-5-orange.svg" /></a>
  <a href="https://developer.apple.com/ios/"><img src="https://img.shields.io/badge/Platform-iOS%2018%2B-black.svg" /></a>
  <a href="https://github.com/apoorvdarshan/verceltics/stargazers"><img src="https://img.shields.io/github/stars/apoorvdarshan/verceltics?style=flat&color=yellow" /></a>
</p>

## Screenshots

| Projects | Analytics |
|:---:|:---:|
| ![Projects](docs/screenshots/projects.png) | ![Analytics](docs/screenshots/analytics.png) |
| All your Vercel projects — favicons, framework dot, last commit, live deploy indicator | Visitors, page views, and the interactive chart with peak + average markers |

| Pages & Routes | Countries & Devices | Deep Breakdowns |
|:---:|:---:|:---:|
| ![Pages and Routes](docs/screenshots/breakdowns.png) | ![Countries and Devices](docs/screenshots/referrers.png) | ![Devices and OS](docs/screenshots/devices.png) |
| Pages, routes, hostnames, and referrers — ranked by visitors | Countries with flags, devices, and browsers | Operating systems, events, flags, and query parameters |

## Features

- **Projects Dashboard** — Personal and team Vercel projects with favicons, git repo, last commit, framework
- **Multi-provider accounts** — Connect and switch between 10 hosting platforms, 8 domain registrars, and 9 site-intelligence services
- **Cloudflare Control** — Accounts, zones, DNS CRUD, analytics, Pages folder uploads/deployments/logs/actions, Workers deployments/actions, cache purge, and a guarded advanced API explorer
- **Hosting dashboards** — Netlify, Railway, Render, DigitalOcean, Heroku, Fly.io, Firebase, and AWS Amplify resources, deployments, logs, actions, and provider API operation catalogs
- **Registrar dashboards** — Name.com, Namecheap, Porkbun, Spaceship, Dynadot, NameSilo, Gandi, and GoDaddy domains with provider API catalogs
- **Sites dashboard** — Combine Google Search Console, Google Analytics, PageSpeed & CrUX, Bing Webmaster, Microsoft Clarity, Plausible, Umami, UptimeRobot, and Better Stack signals by site
- **Live Deploy Indicator** — Pulsing green dot when a deployment is < 30 minutes old
- **Project Details** — Scope, framework, connected repository, verified domains, and recent deployments
- **Deployment Details** — Open deployments to inspect target, branch, commit, creator, live URL, and build events
- **Framework-tinted dots** — Astro orange, Vite purple, Remix cyan, Angular red, Eleventy yellow, etc.
- **Analytics** — Visitors, page views, and trends with % change badges and staggered entrance
- **Interactive Chart** — Peak indicator, average reference line, drag-to-inspect with haptic feedback
- **Full Breakdowns** — Pages, routes, hostnames, referrers, UTM, countries, devices, browsers, OS, events, flags, query params
- **Soft Paywall** — Browse projects free; analytics gated per project tap
- **Private Favicons** — Bounded direct and same-origin icon discovery with a local letter fallback; project domains are never sent to third-party favicon services
- **Search** — Filter projects by name, domain, or framework
- **Responsive Refreshing** — Cached dashboards open immediately, refresh quietly in the background, and support manual pull to refresh
- **Update Checks** — About tab shows when a newer App Store version is available
- **About Tab** — Optional tips, rate/share links, contact, legal, update checks, and subscription management in one place
- **iPad** — Adaptive grid + sidebar tab style on regular size class
- **Appearance** — System-default, light, and pure-black dark themes
- **Secure** — Every credential uses device-only iOS Keychain storage; cross-host redirects are blocked and detected writes require confirmation

## Pricing

| Plan | Price | Trial |
|---|---|---|
| Monthly | $4.99 | — |
| Yearly | $34.99 | 7-day free trial |
| Lifetime | $59.99 | — (one-time purchase) |

Build from source for free with your own provider credentials. App Store distribution exists for convenience and to fund development.

## Tech Stack

**iOS**
- **SwiftUI** — Entire UI, layered gradient cards, scoped animations
- **Swift Charts** — Interactive line + area chart with peak / average / drag-select
- **Swift 5 language mode** — Main-actor isolation with strict concurrency checks
- **RevenueCat + StoreKit** — Entitlements, purchase restore, auto-renewable subscriptions, and lifetime unlock
- **Keychain** — Secure token storage
- **async/await** + **actors** — All API calls

**Web**
- **Next.js** — Landing page, privacy, terms
- **Tailwind CSS** — Styling
- Static export deployed with **Cloudflare Workers Static Assets** and Wrangler

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

Deploy the static export to Cloudflare Workers Static Assets:

```bash
npm run deploy
```

Wrangler manages the `verceltics.com` and `www.verceltics.com` custom-domain
routes declared in `web/wrangler.jsonc`, including their Cloudflare DNS records.

### Vercel Tokens

The app uses [Vercel personal access tokens](https://vercel.com/account/tokens) for authentication:

1. Go to [vercel.com/account/tokens](https://vercel.com/account/tokens)
2. Create a token with your account scope
3. Paste it in the app
4. Add more accounts from the account switcher

### Cloudflare credentials

Cloudflare accounts can use either a scoped API token or the login email plus legacy Global API Key from [Cloudflare User Profile → API Tokens](https://dash.cloudflare.com/profile/api-tokens). A Global API Key inherits the Cloudflare user's permissions, including write access. Credentials are sent directly to `api.cloudflare.com`; typed destructive actions and all non-GET advanced API requests require confirmation.

### Purchase Testing

To test the paywall in Xcode:

1. Open `ios/verceltics.xcodeproj` in Xcode and let Swift Package Manager resolve RevenueCat
2. Run the app with an Apple sandbox tester or TestFlight build
3. Use RevenueCat customer history and Apple sandbox tools to inspect purchase state

The checked-in StoreKit config still mirrors all three products for local reference: monthly, yearly with a 7-day intro offer, and lifetime non-consumable. Production prices, trial duration, and entitlement state are managed through App Store Connect and RevenueCat.

### RevenueCat Refund Handling

RevenueCat Dashboard is configured to ask Apple to decline refund requests when Apple asks for developer input.

Keep this setting enabled in RevenueCat Dashboard: Project -> Apps & providers -> iOS App Store -> Handling of refund requests -> Always prefer declining refunds. Apple still makes the final refund decision.

## API

The app communicates directly with these provider API hosts:

| Host | Endpoints | Auth |
|------|-----------|------|
| `api.vercel.com` | `/v2/user`, `/v9/projects`, `/v9/projects/{id}`, `/v9/projects/{id}/domains` | Bearer token |
| `vercel.com/api` | `/web-analytics/v2/*` | Bearer token |
| `api.cloudflare.com` | `/client/v4/*`, including GraphQL analytics | Global key or scoped API token |
| Hosting provider APIs | Netlify, Railway, Render, DigitalOcean, Heroku, Fly.io, Firebase, and AWS Amplify | Provider token/key or Google OAuth for Firebase |
| Registrar provider APIs | Name.com, Namecheap, Porkbun, Spaceship, Dynadot, NameSilo, Gandi, and GoDaddy | Provider key/token |
| Site-intelligence APIs | Google Search Console, Google Analytics, PageSpeed & CrUX, Bing Webmaster, Microsoft Clarity, Plausible, Umami, UptimeRobot, and Better Stack | Google OAuth or provider API key/token |

Analytics endpoints use `groupBy` parameter: `path`, `route`, `hostname`, `referrer`, `utm`, `country`, `device_type`, `client_name`, `os_name`, `event_name`, `flags`, `query_params`

## iOS Project Structure

```
ios/verceltics/
├── App/VercelticsApp.swift          # Entry point, soft paywall routing
├── Auth/
│   ├── AuthManager.swift            # Multi-account auth, token validation, profile refresh
│   ├── KeychainHelper.swift         # Secure token storage
│   └── SiteStore.swift              # Sites accounts, protected snapshots, OAuth refresh
├── Network/VercelAPI.swift          # All API calls (actor-based)
├── Models/
│   ├── VercelAccount.swift          # Saved Vercel account metadata
│   ├── Project.swift                # Project, deployment, alias, /domains
│   └── Analytics.swift              # Analytics data models, time ranges
├── Views/
│   ├── LoginView.swift              # Token login with animated demo chart
│   ├── MainTabView.swift            # Tab bar (Projects, Registrars, Sites, About, Search)
│   ├── ProjectsView.swift           # Project list, search, account switcher, favicons, paywall sheet
│   ├── AnalyticsView.swift          # Full analytics dashboard
│   ├── SitesView.swift              # Cross-provider site intelligence dashboard
│   └── AboutView.swift              # Support, links, legal, update checks, sign out
├── Components/
│   ├── StatCard.swift               # Metric card with change badge
│   ├── AnalyticsChart.swift         # Interactive Swift Charts line graph
│   └── Shimmer.swift                # Loading skeleton shimmer modifier
└── Paywall/
    ├── PaywallManager.swift         # RevenueCat entitlement + purchase logic
    ├── PaywallView.swift            # Subscription + lifetime paywall UI
    └── Products.storekit            # StoreKit testing config
```

## Disclaimer

Verceltics is **not** affiliated with, endorsed by, or sponsored by any supported hosting platform, registrar, or site-intelligence service. Their names and marks belong to their respective owners. This independent, open-source project communicates directly with provider APIs using user-provided credentials.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

[MIT](LICENSE)

## Contact

- **Email**: ad13dtu@gmail.com
- **X**: [@apoorvdarshan](https://x.com/apoorvdarshan)
- **LinkedIn**: [Verceltics](https://www.linkedin.com/company/verceltics)
- **Support**: [ko-fi.com/apoorvdarshan](https://ko-fi.com/apoorvdarshan)
- **Issues**: [github.com/apoorvdarshan/verceltics/issues](https://github.com/apoorvdarshan/verceltics/issues)
- **Security**: see [SECURITY.md](SECURITY.md) for private vulnerability reporting

## Contributors

<a href="https://github.com/apoorvdarshan/verceltics/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=apoorvdarshan/verceltics" alt="Verceltics contributors" />
</a>

## Star History

<a href="https://www.star-history.com/?repos=apoorvdarshan%2Fverceltics&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=apoorvdarshan/verceltics&type=date&theme=dark&legend=top-left&sealed_token=DwXYLjTue4rdo7cn1p8qwQo08vbXrdJjAzQvLP7dCR8FmYlWU0xbnYxZcceEqenzxHmWVnYukXgzUdhFozwZFiF01DWMu9Kbr8HOGhfTHo7dXTsB-WAI8b2tdKMQf5U-1nMjuCkavC3ySKBymks0EbOxCt2chNurnGGIgvHMuOG4-2tLTy5Duq7rqc_y" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=apoorvdarshan/verceltics&type=date&legend=top-left&sealed_token=DwXYLjTue4rdo7cn1p8qwQo08vbXrdJjAzQvLP7dCR8FmYlWU0xbnYxZcceEqenzxHmWVnYukXgzUdhFozwZFiF01DWMu9Kbr8HOGhfTHo7dXTsB-WAI8b2tdKMQf5U-1nMjuCkavC3ySKBymks0EbOxCt2chNurnGGIgvHMuOG4-2tLTy5Duq7rqc_y" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=apoorvdarshan/verceltics&type=date&legend=top-left&sealed_token=DwXYLjTue4rdo7cn1p8qwQo08vbXrdJjAzQvLP7dCR8FmYlWU0xbnYxZcceEqenzxHmWVnYukXgzUdhFozwZFiF01DWMu9Kbr8HOGhfTHo7dXTsB-WAI8b2tdKMQf5U-1nMjuCkavC3ySKBymks0EbOxCt2chNurnGGIgvHMuOG4-2tLTy5Duq7rqc_y" />
 </picture>
</a>

---

<p align="center">Built with ❤︎ by <a href="https://x.com/apoorvdarshan">Apoorv Darshan</a></p>
