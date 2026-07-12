# Security Policy

Verceltics handles Vercel personal access tokens and Cloudflare Global API Keys, so we take security reports seriously. Thanks for helping keep the app and its users safe.

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
- Credential transmission outside `api.vercel.com`, `vercel.com/api`, or `api.cloudflare.com` (other than the explicitly documented credential-free image services in `ProjectIcon`)
- StoreKit verification bypass that grants entitlements without a real receipt
- Memory disclosure or crashes triggered by malformed Vercel or Cloudflare API responses
- ATS / TLS misconfiguration
- Deep link or URL scheme injection
- Vulnerabilities in the landing page (`web/`) that could affect users (XSS, CSRF on any form, etc.)

## Out of Scope

- Anything requiring a jailbroken device
- Vulnerabilities in third-party services Verceltics talks to (Vercel API, Cloudflare API, `images.weserv.nl`, `icon.horse`, `icons.duckduckgo.com`, `www.google.com/s2/favicons`) — please report those upstream
- Behavior that is documented and intended (for example, a pasted credential being sent directly to its provider API, or an explicitly confirmed Cloudflare write request)
- Self-inflicted issues (sharing your own token publicly, pasting the wrong token)
- Theoretical issues without a concrete attack scenario
- Outdated or unsupported iOS versions
- Social engineering

## Token Safety

Verceltics stores Vercel personal access tokens and Cloudflare Global API Keys in the iOS Keychain using device-only, when-unlocked accessibility. Credentials are sent **only** to:

- `api.vercel.com` (user profile, project listing, project detail, domain list)
- `vercel.com/api` (analytics endpoints)
- `api.cloudflare.com` (Cloudflare profile, accounts, zones, DNS, Pages, Workers, analytics, and user-initiated API operations)

Favicon fetches, SVG rasterisation, and Vercel avatar image loads do **not** include credentials — they are plain image/GET requests.

Cloudflare Global API Keys inherit the Cloudflare user's permissions and can make destructive changes. The app requires an explicit confirmation before destructive typed actions and all non-GET requests in the advanced API explorer. If a credential may be exposed, revoke or rotate it immediately from [Vercel Tokens](https://vercel.com/account/tokens) or [Cloudflare API Tokens](https://dash.cloudflare.com/profile/api-tokens).

## Disclosure Policy

We follow a coordinated disclosure model. Once a fix ships in the App Store and the source repo, we'll:

- Credit the reporter (with permission) in the release notes
- Publish a brief writeup if the issue was severe
- Add a CHANGELOG entry

Thanks again for helping keep Verceltics safe.
