# Security Policy

Verceltics handles Vercel personal access tokens, so we take security reports seriously. Thanks for helping keep the app and its users safe.

## Supported Versions

Only the latest released version of the iOS app and the current `main` branch receive security fixes. Older builds are not patched.

| Component | Supported |
|---|---|
| iOS app — latest App Store release | ✅ |
| iOS app — older App Store releases | ❌ (please update) |
| `main` branch (source) | ✅ |
| Forks / unofficial builds | ❌ |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Instead, email **ad13dtu@gmail.com** with:

1. A description of the vulnerability and its potential impact
2. Steps to reproduce (proof-of-concept appreciated)
3. The iOS version, device, and app build number where you observed it
4. Any suggested mitigation, if you have one

You can expect:

- An acknowledgement within **72 hours**
- A first assessment within **7 days**
- A coordinated disclosure timeline once the fix is scoped — typically a patched App Store build within 14–30 days for critical issues, with public credit at your discretion

## In Scope

- Token leakage (Keychain bypass, plaintext storage, accidental logging)
- Token transmission outside `api.vercel.com` / `vercel.com/api` (other than the explicitly documented favicon services in `ProjectIcon`)
- StoreKit verification bypass that grants entitlements without a real receipt
- Memory disclosure or crashes triggered by malformed Vercel API responses
- ATS / TLS misconfiguration
- Deep link or URL scheme injection
- Vulnerabilities in the landing page (`web/`) that could affect users (XSS, CSRF on any form, etc.)

## Out of Scope

- Anything requiring a jailbroken device
- Vulnerabilities in third-party services Verceltics talks to (Vercel API, `images.weserv.nl`, `icon.horse`, `icons.duckduckgo.com`, `www.google.com/s2/favicons`) — please report those upstream
- Behavior that is documented and intended (e.g. the user's own pasted token being transmitted to `api.vercel.com`)
- Self-inflicted issues (sharing your own token publicly, pasting the wrong token)
- Theoretical issues without a concrete attack scenario
- Outdated or unsupported iOS versions
- Social engineering

## Token Safety

Verceltics stores your Vercel personal access tokens in the iOS Keychain, scoped to the app. Tokens are sent **only** to:

- `api.vercel.com` (user profile, project listing, project detail, domain list)
- `vercel.com/api` (analytics endpoints)

Favicon fetches, SVG rasterisation, and Vercel avatar image loads do **not** include your tokens — they're plain image/GET requests.

If you suspect a token has been exposed, revoke it immediately at [vercel.com/account/tokens](https://vercel.com/account/tokens) and generate a new one.

## Disclosure Policy

We follow a coordinated disclosure model. Once a fix ships in the App Store and the source repo, we'll:

- Credit the reporter (with permission) in the release notes
- Publish a brief writeup if the issue was severe
- Add a CHANGELOG entry

Thanks again for helping keep Verceltics safe.
