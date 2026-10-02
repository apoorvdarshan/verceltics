# Security Policy

Verceltics handles hosting-platform, domain-registrar, and site-intelligence credentials, so we take security reports seriously. Thanks for helping keep the app and its users safe.

## Components and boundaries

This policy covers the native iOS app (`ios/`), the current native Android
implementation (`android/`), the website and public Cloudflare Worker (`web/`),
and Vercie's Discord command and release-announcement integration
(`web/worker/discord-*.mjs` and `services/discord-bot/`).

Mobile apps communicate with provider APIs using the credentials supplied on the
device. The Discord bot processes explicitly submitted commands using its own
server credentials; it has no access to the mobile apps' stored credentials,
connected accounts, or private provider data.

## Supported Versions

Security fixes target the latest iOS App Store release, current Android source on
`main`, and the current website/Worker deployment. Older builds are not patched.
Android support here refers to the implemented source in this repository and
does not imply Play Store availability or full iOS feature parity.

| Component | Supported |
|---|---|
| iOS app — latest App Store release | ✅ |
| iOS app — older App Store releases | ❌ (please update) |
| Android implementation — current `main` source | ✅ |
| Website, Worker, and Vercie — current production deployment | ✅ |
| `main` branch (source) | ✅ |
| Forks / unofficial builds | ❌ |

## Reporting a Vulnerability

**Do not open a public GitHub issue or use Discord's `/bug`, `/feature`, or `/ask`
commands for security vulnerabilities.** Those commands are not a private
reporting channel.

Instead, email **ad13dtu@gmail.com** with:

1. A description of the vulnerability and its potential impact
2. Steps to reproduce (proof-of-concept appreciated)
3. The affected component, app/build or commit version, and device/OS or browser where relevant
4. Any suggested mitigation, if you have one

You can expect:

- An acknowledgement within **72 hours**
- A first assessment within **7 days**
- A coordinated disclosure timeline once the fix is scoped — typically a patch for the affected component within 14–30 days for critical issues, with public credit at your discretion

## In Scope

- Token leakage (Keychain or Android Keystore storage bypass, plaintext storage, accidental logging, or Worker secret exposure)
- Credential transmission outside the selected provider's allowed API host, including an explicitly selected HTTPS host where supported (credential-free same-origin favicons, provider-hosted avatars, and the public IPv4 setup helper never receive credentials)
- RevenueCat or StoreKit entitlement bypass that grants Pro access without a valid purchase
- Memory disclosure or crashes triggered by malformed provider API responses
- ATS / TLS or Android provider-origin validation failures
- Deep link, Android intent, OAuth callback, or URL scheme injection
- Vulnerabilities in the website or public Worker (`web/`) that could affect users, such as XSS, malicious link injection, compromised static assets, or unsafe upstream requests
- Discord signature, timestamp, application/server authorization, or command validation bypass that enables unauthorized processing or GitHub actions
- Exposure of Discord interaction tokens, bot tokens, Gemini keys, GitHub credentials, or optional release-announcement credentials
- Untrusted command text or model output crossing its intended data boundary to perform unintended operations or disclose secrets

## Out of Scope

- Anything requiring a jailbroken device
- Vulnerabilities in provider APIs or project sites that Verceltics talks to — please report those upstream
- Behavior that is documented and intended (for example, a pasted credential being sent directly to its provider API, or an explicitly confirmed Cloudflare write request)
- Self-inflicted issues (sharing your own token publicly, pasting the wrong token)
- Theoretical issues without a concrete attack scenario
- Outdated or unsupported iOS versions
- Social engineering

## Local Data Protection

### iOS

Connected credentials and Google OAuth tokens use device-only, when-unlocked iOS Keychain protection. Site-service snapshots may be cached in the app's Application Support directory using iOS file protection; that cache is excluded from device backups and contains provider responses, not credentials.

RevenueCat receives purchase and entitlement context for App Store transactions but does not receive provider credentials or provider account data from Verceltics. Apple processes payments; Verceltics does not receive payment-card details.

### Android

The implemented Android account repositories store credentials in AES-256-GCM
encrypted files using `AndroidKeystoreAccountCipher`. Keys are held in Android
Keystore, and `NoBackupAtomicFileStore` writes account files under the app's
`noBackupFilesDir`. The manifest disables app backup and the extraction rules
exclude app data from cloud backup and device transfer.

The key configuration does not require StrongBox or biometric authentication.
Secret values redact their printable representation, but credentials still exist
in process memory while used; JVM strings cannot be reliably erased. These
implementation details should not be described as a guarantee of hardware-backed
keys or zero secrets in memory.

## Credential and Endpoint Safety

Mobile provider credentials are sent **only** to the corresponding allowed provider endpoint. This catalog includes iOS integrations; Android coverage is incremental:

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

Android's implemented provider transports require HTTPS and validate the
configured scheme, host, and effective port with `ProviderEndpointPolicy` before
sending credentials or following a redirect. Preserve those checks when extending
integration coverage. Security reports should identify the affected platform and
provider so the relevant implementation can be examined.

## Discord and Worker data handling

- Discord's HTTP interaction endpoint is public. Vercie verifies Ed25519
  signatures, a five-minute timestamp window, and the configured application and
  server before processing commands. Preserve these checks before external work.
- `/ask` sends the submitted question to Google Gemini. `/bug` and `/feature`
  may send submitted reports to Gemini to organize a draft, then publish the
  original report and any generated summary as a public GitHub issue in this
  repository. Treat both user text and model output as untrusted data.
- Bot credentials belong in Cloudflare Worker secrets. Do not commit them or
  include them in mobile binaries, public site assets, issue bodies, or logs.
  Use the dedicated `DISCORD_GEMINI_API_KEY` for Discord processing and scope
  GitHub credentials to the repository operations the Worker needs.
- Upstream bot requests use fixed service origins and reject redirects. Request
  and response bodies have size limits, and command fulfillment has a bounded
  time budget. Preserve these boundaries when changing the handlers.
- `DISCORD_STATE` KV stores release markers and hashed identifiers for cooldowns
  and duplicate detection. Duplicate markers expire after 15 minutes; `/ask` has
  a 60-second cooldown and `/bug` plus `/feature` share a 300-second cooldown.
  Submitted text and interaction tokens are not stored in KV. KV is eventually
  consistent, so these controls do not guarantee exactly-once processing across
  locations.
- Command failure logs record bounded diagnostic categories and status codes;
  they omit prompts, report text, raw upstream error bodies, and credential-bearing
  URLs. Discord messages, GitHub issues, and external AI processing have their own
  retention and visibility rules. Do not promise that Gemini retains no data.
- Release announcements use server-side bot credentials and, when configured,
  store-release metadata. Announcement setup must not expose secrets or give
  Discord commands access to publishing a mobile release.

See [Discord setup and data handling](services/discord-bot/README.md) and the
published [privacy policy](https://verceltics.com/privacy#discord) for operational
details. Conduct concerns follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Solo-maintainer workflow

Verceltics is maintained by Apoorv Darshan as its sole developer. The maintainer
may push directly to `main`; another person's approval, code-owner approval,
and a pull request are not required for maintainer changes. `CODEOWNERS` records
ownership and does not create a review requirement.

The `Protect main` ruleset blocks branch deletion and force pushes. CI runs
tests, dependency auditing, fuzz tests, and CodeQL on pushes to `main`; Scorecard
continues to run all of its checks.

Scorecard's `Code-Review` and `Branch-Protection` recommendations for independent
reviews and mandatory pre-merge gates are accepted governance exceptions for
this solo workflow. Their alerts are dismissed with this rationale rather than
treated as vulnerabilities repaired in application code. Revisit these
exceptions if the project gains additional maintainers. They do not exempt
dependency vulnerabilities or findings in application code from remediation.

## Build dependency integrity

The checked-in `android/gradle/wrapper/gradle-wrapper.jar` is the standard Gradle
bootstrap required by both `gradlew` and `gradlew.bat`. It is an intentional
exception to Scorecard's blanket recommendation against binary artifacts, as
recommended by the [Gradle Wrapper documentation](https://docs.gradle.org/current/userguide/gradle_wrapper.html).
It is not an application binary.

- The Gradle 9.8.0 wrapper JAR SHA-256 is
  `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5`, matching
  [Gradle's published checksum](https://services.gradle.org/distributions/gradle-9.8.0-wrapper.jar.sha256).
- CI explicitly enables `gradle/actions/setup-gradle` wrapper validation before
  executing Gradle, rejecting JARs that do not match known Gradle releases.
- `android/gradle/wrapper/gradle-wrapper.properties` pins the distribution ZIP's
  SHA-256 via `distributionSha256Sum`. Keep that checksum aligned with Gradle's
  published checksum when updating the distribution.

## Disclosure Policy

We follow a coordinated disclosure model. Once a fix is available in the affected
app release, current Android source, or deployed website/Worker and the source
repository, we'll:

- Credit the reporter (with permission) in the release notes
- Publish a brief writeup if the issue was severe
- Document the fix in the release notes or public project history

Thanks again for helping keep Verceltics safe.
