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
- **No third-party dependencies** — Keep it dependency-free
- **Dark mode only** — All UI must work on pure black (#000000) background
- **iOS 18.0+** — Minimum deployment target
- **Swift 6 strict concurrency** — Project sets `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`. Mark pure data models / pure helpers `nonisolated`. Use `async/await`, actors, `@Observable`
- **iPad-aware** — Avoid hardcoded widths beyond 40-180pt; respect `@Environment(\.horizontalSizeClass)`
- **Keep it minimal** — Don't add features nobody asked for

## Visual Style

The card chrome is layered, not flat. New cards should match what's already in `ProjectCard`, `StatCard`, and the breakdown cards:

- **Background**: two stacked gradients — a top-left → bottom-right diagonal at `0.07 → 0.02` white, and a top → center highlight at `0.04 → clear`
- **Stroke**: gradient `strokeBorder` at `0.12 → 0.04` white, `lineWidth: 0.5`
- **Corner radius**: `18` for cards, `14` for tiles, `10` for icon backgrounds, all `style: .continuous`
- **Press feedback**: wrap interactive surfaces in `Button` and apply `.buttonStyle(PressScaleButtonStyle())`
- **Hit-testing**: HStacks with `Spacer()` need `.contentShape(Rectangle())` so the gap is tappable

## Code Style

- Use SF Symbols for icons (heavy / bold weights for accent dots)
- Use `.system(size:weight:)` fonts with explicit sizes; rounded design + monospaced digits for numbers
- Use `RoundedRectangle(cornerRadius:style: .continuous)` for shapes — never `.circular`
- Color palette: backgrounds use white at `0.02` / `0.04` / `0.06` / `0.07` / `0.08`; text uses white at `0.18` / `0.30` / `0.40` / `0.55` / `1.0`; blue accent is the system blue with a `(0.45, 0.65, 1.0)` light variant for gradients
- Animations should be **scoped** with `.animation(.spring(...), value: someState)`. Avoid `withAnimation { state = ... }` inside `onAppear` — that leaks the transaction onto sibling state changes (causes "bouncing" siblings)
- Haptics: use `.sensoryFeedback(.selection, trigger: value)` on selectable controls and `.impact(weight: .light)` on tap-to-refresh actions

## Architecture Notes

- **Soft paywall**: `VercelticsApp` sends every authenticated user to `MainTabView`. The paywall is presented as a sheet from `ProjectsView` only when the user taps a project without an active entitlement. Don't add launch-time paywall gates.
- **Rate prompt**: lives in `ProjectsView` and fires once via `@AppStorage("hasShownOnboardingRatePrompt")` after a successful project load — works for both free and paid users.
- **Favicon chain**: parallel race in `ProjectIcon`. Direct paths first, then HTML scrape, then third-party services. SVG paths are routed through the `images.weserv.nl` proxy. Don't add probes for `www.<sub>.vercel.app` — Vercel's wildcard cert doesn't cover sub-subdomains and ATS will fail.
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
- Light mode (this is a dark mode app)
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

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
