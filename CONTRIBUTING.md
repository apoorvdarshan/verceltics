# Contributing to Verceltics

Thanks for your interest in contributing! Here's how to get started.

## Getting Started

1. Fork the repo
2. Clone your fork
   ```bash
   git clone https://github.com/YOUR_USERNAME/verceltics.git
   ```
3. Open `verceltics.xcodeproj` in Xcode
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
- **Dark mode** — All UI must work on pure black (#000000) background
- **iOS 18.0+** — Minimum deployment target
- **Swift concurrency** — Use async/await, actors, @Observable
- **Keep it minimal** — Don't add features nobody asked for

## Code Style

- Use SF Symbols for icons
- Use `.system()` fonts with explicit sizes and weights
- Use `RoundedRectangle(cornerRadius:style: .continuous)` for shapes
- Use `.ultraThinMaterial` for glass effects
- Keep opacity values consistent (0.04, 0.06, 0.08 for backgrounds; 0.35, 0.45 for text)

## What to Contribute

- Bug fixes
- Performance improvements
- Better error handling
- Accessibility improvements
- UI polish
- New analytics breakdowns (if Vercel API supports them)

## What NOT to Contribute

- Third-party dependencies
- Major architecture changes without discussion
- Features that don't align with the app's purpose
- Light mode (this is a dark mode app)

## Reporting Issues

Open an issue at [github.com/apoorvdarshan/verceltics/issues](https://github.com/apoorvdarshan/verceltics/issues) with:

1. What happened
2. What you expected
3. Steps to reproduce
4. iOS version and device

## Contact

- **Email**: ad13dtu@gmail.com
- **X**: [@apoorvdarshan](https://x.com/apoorvdarshan)

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
