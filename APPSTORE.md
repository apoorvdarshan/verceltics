# App Store Listing

App Store Connect submission details for Verceltics v1.1. Each field is in a code block for easy copy-paste.

## App Name
```
Verceltics - Vercel Analytics
```

## Subtitle (30 chars max)
```
Web Analytics in your pocket
```

## Promotional Text (170 chars max)
```
Vercel Web Analytics on your iPhone and iPad. Visitors, page views, bounce rate, twelve breakdowns — token in your Keychain, no servers in between. Now with Lifetime.
```

## Keywords (100 chars max)
```
vercel,analytics,web traffic,visitors,referrers,bounce rate,developer,deploy,website,open source
```

## Category
```
Primary: Developer Tools
Secondary: Productivity
```

## What's New (v1.1)
```
Verceltics 1.1 — full iPad layout, soft paywall, lifetime tier, and a redesigned analytics view.

NEW
• iPad ready — adaptive grid + sidebar tab style on regular size class. Cards no longer stretch full-width on big screens.
• Soft paywall — browse all your projects free. The paywall only appears when you tap into a project's analytics. After purchase the sheet auto-dismisses and continues into the project you tapped.
• Lifetime tier — one-time $59.99 non-consumable alongside Monthly and Yearly. Yearly now includes a 7-day free trial for first-time subscribers (was 3 days).
• Native rate prompt — fires once per install, 3 seconds after your first project loads, for both free and paid users. No App Store round-trip.
• Redesigned analytics chart — peak indicator pill, dashed average reference line, gradient line stroke, 3-stop area gradient, drag-to-inspect with date capsule + haptic feedback.
• Stat cards — heavy rounded numerals, change badges with stroked outlines, staggered entrance across visitors / page views / bounce rate.
• Framework-tinted live deploy dots — Astro orange, Vite purple, Remix cyan, Angular red, Eleventy yellow, Next/Nuxt/SvelteKit/Gatsby and friends each get their own accent. Pulses green when a deployment is < 30 minutes old.
• Robust favicons — multi-source race across apple-touch-icon, favicon paths, HTML scrape, and SVG → PNG rasterisation via images.weserv.nl. Falls back through DuckDuckGo, Google s2, and icon.horse.
• Long *.vercel.app URLs collapse — projects whose bulk listing only returned the long alias now enrich via /v9/projects/{id}/domains and display the short canonical hostname.
• Share Verceltics — system share sheet with a plain message including App Store + website links.
• About page — restructured into Support, Links, Help, Account, Legal sections matching the Projects card chrome. Every row is fully tappable end-to-end.

Polish
• Live deploy dot pulse no longer drags sibling views.
• Login screen connect button auto-scrolls above the keyboard while you paste a token.
• Pure black (#000000) background throughout.
• Numbers use system rounded heavy with monospaced digits so columns align cleanly.

Bug fixes
• User-prefixed Vercel account IDs (user_…) no longer get rejected by the API.
• www.<sub>.vercel.app probes removed — Vercel's TLS cert is single-level wildcard, so those requests were stalling on first paint.
• Scoped animations replaced every withAnimation { } block inside onAppear, fixing transaction leaks.
```

## Description
```
Verceltics puts your Vercel web analytics in your pocket. Check visitors, page views, bounce rate, referrers, countries, devices, browsers, and more — all from a fast native iPhone and iPad app.

WHAT YOU GET
• Real-time analytics dashboard with interactive Swift Charts
• Period comparisons: 24h, 7d, 30d, 3 months, 12 months
• Referrers, UTMs, countries with flags
• Device, browser, and OS breakdowns
• Pages, routes, hostnames, events, feature flags, and query params
• Multi-project switching with search and favicon detection
• Pull to refresh for live data
• iPad layout — adaptive grid + sidebar tab style on regular size class

PRIVATE BY DESIGN
• Your Vercel token stays in the iOS Keychain — never leaves your device
• No tracking, no telemetry, no servers in between
• Fully open source on GitHub

HOW IT WORKS
1. Create a token at vercel.com/account/tokens
2. Paste it in the app once
3. View your analytics anytime

PRICING
• Monthly: $4.99/month
• Yearly: $34.99/year with a 7-day free trial (save 41%)
• Lifetime: $59.99 one-time purchase
• Cancel subscriptions anytime from Apple ID settings

Built with SwiftUI and Swift Charts. Zero third-party dependencies.

Website: https://verceltics.com
GitHub: https://github.com/apoorvdarshan/verceltics
Privacy Policy: https://verceltics.com/privacy
Terms of Service: https://verceltics.com/terms
Contact: ad13dtu@gmail.com

Not affiliated with Vercel Inc.
```

## Privacy URL
```
https://verceltics.com/privacy
```

## Terms URL
```
https://verceltics.com/terms
```

## Support URL
```
https://verceltics.com
```

## Marketing URL
```
https://verceltics.com
```

## Reviewer Notes
```
1) Verceltics is a third-party client for Vercel Web Analytics. It is not affiliated with Vercel Inc. — disclaimer is shown in the app's About tab and on the website.

2) The app requires a Vercel personal access token to fetch any data. To test:
   • Visit https://vercel.com/account/tokens
   • Create a token with full account scope (the same scope a developer would use for the Vercel CLI)
   • Paste the token on the Sign In screen

3) Tokens are stored in iOS Keychain. Outbound destinations:
   • api.vercel.com and vercel.com/api — authenticated with the user's token
   • images.weserv.nl, icons.duckduckgo.com, www.google.com/s2/favicons, icon.horse — favicon CDNs that never receive the token, only public hostnames

4) Soft paywall: the project list is fully accessible without a subscription. The paywall sheet only appears when a user taps into a project's analytics. Three products:
   • com.apoorvdarshan.verceltics.monthly — $4.99/mo, no trial
   • com.apoorvdarshan.verceltics.yearly — $34.99/yr, 7-day intro offer (P1W in StoreKit config; production duration is set in App Store Connect)
   • com.apoorvdarshan.verceltics.lifetime — $59.99 non-consumable
   Restore Purchases is available in the paywall and in About → Account → Manage Subscription.

5) Universal app — supports iPhone and iPad with an adaptive grid + sidebar tab style on regular size class.

6) The app is open source under MIT (github.com/apoorvdarshan/verceltics). The App Store build is identical to what's in the repo at the matching tag.
```
