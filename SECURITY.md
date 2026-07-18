# Security Policy

Verceltics handles hosting-platform, domain-registrar, and site-intelligence credentials, so we take security reports seriously. Thanks for helping keep the app and its users safe.

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
- Credential transmission outside the selected provider's allowed API host, including an explicitly selected HTTPS host where supported (credential-free same-origin favicons, provider-hosted avatars, and the public IPv4 setup helper never receive credentials)
- RevenueCat or StoreKit entitlement bypass that grants Pro access without a valid purchase
- Memory disclosure or crashes triggered by malformed provider API responses
- ATS / TLS misconfiguration
- Deep link or URL scheme injection
- Vulnerabilities in the static website (`web/`) that could affect users, such as XSS, malicious link injection, or compromised static assets

## Out of Scope

- Anything requiring a jailbroken device
- Vulnerabilities in provider APIs or project sites that Verceltics talks to — please report those upstream
- Behavior that is documented and intended (for example, a pasted credential being sent directly to its provider API, or an explicitly confirmed Cloudflare write request)
- Self-inflicted issues (sharing your own token publicly, pasting the wrong token)
- Theoretical issues without a concrete attack scenario
- Outdated or unsupported iOS versions
- Social engineering

## Local Data Protection

Connected credentials and Google OAuth tokens use device-only, when-unlocked iOS Keychain protection. Site-service snapshots may be cached in the app's Application Support directory using iOS file protection; that cache is excluded from device backups and contains provider responses, not credentials.

RevenueCat receives purchase and entitlement context for App Store transactions but does not receive provider credentials or provider account data from Verceltics. Apple processes payments; Verceltics does not receive payment-card details.

## Credential and Endpoint Safety

Credentials are sent **only** to the corresponding allowed provider endpoint:

- `api.vercel.com` (user profile, project listing, project detail, domain list)
- `vercel.com/api` (analytics endpoints)
- `api.cloudflare.com` (Cloudflare profile, accounts, zones, DNS, Pages, Workers, analytics, and user-initiated API operations)
- `api.netlify.com`, `backboard.railway.com`, `api.render.com`, `api.digitalocean.com`, `api.heroku.com`, `api.machines.dev`, `firebasehosting.googleapis.com`, or the selected regional `amplify.*.amazonaws.com` host
- `api.name.com`, `api.namecheap.com`, `api.porkbun.com`, `spaceship.dev`, `api.dynadot.com`, `www.namesilo.com`, `api.gandi.net`, or `api.godaddy.com`
- Google OAuth, identity, and Sites APIs under `accounts.google.com`, `oauth2.googleapis.com`, `openidconnect.googleapis.com`, `www.googleapis.com`, `searchconsole.googleapis.com`, `analyticsadmin.googleapis.com`, `analyticsdata.googleapis.com`, and `chromeuxreport.googleapis.com`; the OAuth token endpoint handles exchange and refresh for Firebase Hosting, Search Console, and Analytics
- `ssl.bing.com`, `www.clarity.ms`, `plausible.io`, `api.umami.is` (or the user-selected HTTPS Umami host), `api.uptimerobot.com`, and `uptime.betterstack.com`

Favicon fetches are limited to the project site's own HTTPS origin, and Vercel avatar image loads do **not** include credentials. No project domain is sent to a third-party favicon service.

Registrar setup may make a bounded, credential-free request to `api.ipify.org` to display the current public IPv4 required by Namecheap or optionally allowlisted in Name.com. This request never includes a provider credential or provider account data.

Provider credentials inherit their configured permissions and can make destructive changes or purchases. The app blocks cross-host redirects and requires confirmation before detected write or purchase requests. If a credential may be exposed, revoke or rotate it immediately in that provider's dashboard.

## Disclosure Policy

We follow a coordinated disclosure model. Once a fix ships in the App Store and the source repo, we'll:

- Credit the reporter (with permission) in the release notes
- Publish a brief writeup if the issue was severe
- Document the fix in the release notes or public project history

Thanks again for helping keep Verceltics safe.
