# Contributing to Verceltics

Thanks for your interest in contributing! Here's how to get started.

## Getting Started

1. Fork the repo
2. Clone your fork
   ```bash
   git clone https://github.com/YOUR_USERNAME/verceltics.git
   ```
3. Open `ios/verceltics.xcodeproj` in Xcode (iOS app) or `cd web && npm install` (landing page)
4. Create a branch
   ```bash
   git checkout -b feature/your-feature
   ```
5. Make your changes
6. Build and test on iOS 18.0+
7. Commit and push
8. Open a Pull Request

## Guidelines

- **SwiftUI only** — No UIKit wrappers unless absolutely necessary
- **No new third-party dependencies without discussion** — RevenueCat is already used for App Store entitlements; keep everything else lean
- **All appearances** — Every screen must work in System, Light, and Dark appearance. Use `AppTheme`; do not hardcode a one-mode palette.
- **iOS 18.0+** — Minimum deployment target
- **Swift concurrency** — The project uses Swift 5 language mode with `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`. Mark pure data models and helpers `nonisolated`; use `async`/`await`, actors, and `@Observable`.
- **iPad-aware** — Avoid hardcoded widths beyond 40-180pt; respect `@Environment(\.horizontalSizeClass)`
- **Keep it minimal** — Don't add features nobody asked for

## Visual Style

`ios/verceltics/Components/ProviderVisuals.swift` is the canonical design system. Build with the shared primitives rather than recreating card chrome locally:

- **Canvas and surfaces**: `AppTheme.canvas`, `surface`, and `surfaceRaised` adapt across Light and Dark appearance.
- **Text and state**: use `textPrimary`, `textSecondary`, `textTertiary`, `signal`, `success`, `warning`, and `danger`.
- **Cards**: prefer `.appSurface()` for neutral surfaces and `.providerSurface(accent:)` when provider identity matters.
- **Radii**: `AppTheme.panelRadius` (16), `controlRadius` (13), and `iconRadius` (10), always with continuous corners.
- **Provider color**: reserve it for identity, a thin accent rail, an icon tile, or status—not decorative page gradients.
- **Press feedback**: wrap interactive surfaces in `Button` and apply `.buttonStyle(PressScaleButtonStyle())`.
- **Hit testing**: HStacks with `Spacer()` need `.contentShape(Rectangle())` so the gap is tappable.

## Code Style

- Use SF Symbols for icons (heavy / bold weights for accent dots)
- Use `.system(size:weight:)` fonts with explicit sizes; rounded design + monospaced digits for numbers
- Use `RoundedRectangle(cornerRadius:style: .continuous)` for shapes — never `.circular`
- Use semantic `AppTheme` colors instead of white-opacity ladders so contrast remains correct in every appearance.
- Animations should be **scoped** with `.animation(.spring(...), value: someState)`. Avoid `withAnimation { state = ... }` inside `onAppear` — that leaks the transaction onto sibling state changes (causes "bouncing" siblings)
- Haptics: use `.sensoryFeedback(.selection, trigger: value)` on selectable controls and `.impact(weight: .light)` on tap-to-refresh actions

## Architecture Notes

- **Soft paywall**: `VercelticsApp` sends every authenticated user to `MainTabView`. The paywall is presented as a sheet from `ProjectsView` only when the user taps a project without an active entitlement. Don't add launch-time paywall gates.
- **Rate prompt**: lives in `ProjectsView` and fires once via `@AppStorage("hasShownOnboardingRatePrompt")` after a successful project load — works for both free and paid users.
- **Favicon chain**: `ProjectIcon` performs bounded, credential-free discovery against the project site's own HTTPS origin and otherwise draws a local fallback. Do not add third-party favicon services or probes for `www.<sub>.vercel.app`; Vercel's wildcard certificate does not cover sub-subdomains and ATS will fail.
- **Domain resolution**: when a project's bulk listing only shows `*.vercel.app`, `enrichProjectsNeedingDomainRefresh` calls `/v9/projects/{id}/domains` and merges the verified entries into the project's alias array so `primaryDomain` picks the best one.

## What to Contribute

- Bug fixes
- Performance improvements
- Better error handling
- Accessibility improvements
- UI polish
- New analytics breakdowns (if Vercel API supports them)
- Localizations
- New framework dot colors in `ProjectCard.frameworkColors`

## What NOT to Contribute

- Third-party dependencies
- Major architecture changes without discussion
- Features that don't align with the app's purpose
- One-off colors or cards that bypass the adaptive `AppTheme` system
- Launch-time paywalls — the soft paywall flow is deliberate
- Weekly subscription tiers — at sub-$5 monthly the abuse cost is too low to deter, and the dev community treats weekly subs in tooling apps as a red flag. Trial is yearly-only on purpose.

## Reporting Issues

Open an issue at [github.com/apoorvdarshan/verceltics/issues](https://github.com/apoorvdarshan/verceltics/issues) with:

1. What happened
2. What you expected
3. Steps to reproduce
4. iOS version and device

For security vulnerabilities, please follow [SECURITY.md](SECURITY.md) instead — do not open a public issue.

## Contact

- **Email**: ad13dtu@gmail.com
- **X**: [@apoorvdarshan](https://x.com/apoorvdarshan)
- **Support**: [ko-fi.com/apoorvdarshan](https://ko-fi.com/apoorvdarshan)

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
