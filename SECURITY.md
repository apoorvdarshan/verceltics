# Security Policy

Verceltics handles hosting-platform and domain-registrar credentials, so we take security reports seriously. Thanks for helping keep the app and its users safe.

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
- Credential transmission outside the selected provider's official API host (other than explicitly documented credential-free image services)
- StoreKit verification bypass that grants entitlements without a real receipt
- Memory disclosure or crashes triggered by malformed provider API responses
- ATS / TLS misconfiguration
- Deep link or URL scheme injection
- Vulnerabilities in the landing page (`web/`) that could affect users (XSS, CSRF on any form, etc.)

## Out of Scope

- Anything requiring a jailbroken device
- Vulnerabilities in third-party provider or image services that Verceltics talks to — please report those upstream
- Behavior that is documented and intended (for example, a pasted credential being sent directly to its provider API, or an explicitly confirmed Cloudflare write request)
- Self-inflicted issues (sharing your own token publicly, pasting the wrong token)
- Theoretical issues without a concrete attack scenario
- Outdated or unsupported iOS versions
- Social engineering

## Token Safety

Verceltics stores all connected credentials in the iOS Keychain using device-only, when-unlocked accessibility. Credentials are sent **only** to the corresponding provider host:

- `api.vercel.com` (user profile, project listing, project detail, domain list)
- `vercel.com/api` (analytics endpoints)
- `api.cloudflare.com` (Cloudflare profile, accounts, zones, DNS, Pages, Workers, analytics, and user-initiated API operations)
- `api.netlify.com`, `backboard.railway.com`, `api.render.com`, `api.digitalocean.com`, `api.heroku.com`, `api.machines.dev`, `firebasehosting.googleapis.com`, or the selected regional `amplify.*.amazonaws.com` host
- `api.name.com`, `api.namecheap.com`, `api.porkbun.com`, `spaceship.dev`, `api.dynadot.com`, `www.namesilo.com`, `api.gandi.net`, or `api.godaddy.com`

Favicon fetches, SVG rasterisation, and Vercel avatar image loads do **not** include credentials — they are plain image/GET requests.

Provider credentials inherit their configured permissions and can make destructive changes or purchases. The app blocks cross-host redirects and requires confirmation before detected write or purchase requests. If a credential may be exposed, revoke or rotate it immediately in that provider's dashboard.

## Disclosure Policy

We follow a coordinated disclosure model. Once a fix ships in the App Store and the source repo, we'll:

- Credit the reporter (with permission) in the release notes
- Publish a brief writeup if the issue was severe
- Add a CHANGELOG entry

Thanks again for helping keep Verceltics safe.
