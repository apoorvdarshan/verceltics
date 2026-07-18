import type { Metadata } from "next";

import { LegalShell } from "@/components/legal-shell";

const SITE_URL = "https://verceltics.com";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "How Verceltics handles provider credentials, Google OAuth data, local caches, purchases, and website delivery.",
  alternates: { canonical: `${SITE_URL}/privacy` },
  openGraph: {
    type: "article",
    siteName: "Verceltics",
    title: "Privacy Policy — Verceltics",
    description: "Device-only Keychain storage, direct provider requests, no app tracking, and no Verceltics credential proxy.",
    url: `${SITE_URL}/privacy`,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics mobile operations instrument" }],
  },
};

const sections = [
  { id: "overview", label: "Overview" },
  { id: "app-data", label: "Data not collected" },
  { id: "credentials", label: "Credentials and OAuth" },
  { id: "network-address", label: "Network address helper" },
  { id: "google-data", label: "Google API data" },
  { id: "provider-data", label: "Provider data and cache" },
  { id: "images", label: "Images and update checks" },
  { id: "website", label: "Website delivery" },
  { id: "purchases", label: "Purchases" },
  { id: "controls", label: "Your controls" },
  { id: "changes", label: "Policy changes" },
  { id: "contact", label: "Contact" },
] as const;

export default function Privacy() {
  return (
    <LegalShell
      asideDescription="Plain-language privacy details for a direct-to-provider iOS app."
      eyebrow="Direct-to-provider architecture"
      sections={sections}
      summary="Verceltics is designed so provider credentials and account data do not pass through a Verceltics server. This policy explains the limited local and third-party processing needed to operate the app and website."
      title="Privacy Policy"
      updated="July 18, 2026"
    >
      <section id="overview">
        <h2>Overview</h2>
        <p>Verceltics is an independent iPhone and iPad workspace for supported hosting platforms, domain registrars, and site-intelligence services. The app connects to services you choose using credentials or OAuth authorization you provide.</p>
        <p><strong>Verceltics does not operate a credential or provider-data proxy.</strong> Requests for provider data go from your device directly to the selected provider&apos;s official HTTPS API.</p>
      </section>

      <section id="app-data">
        <h2>Data the iOS app does not collect</h2>
        <p>The iOS app does not send provider credentials, account data, projects, domains, DNS records, deployments, logs, analytics, search data, or uptime data to Verceltics infrastructure.</p>
        <ul>
          <li>No advertising or cross-app tracking</li>
          <li>No product-analytics or provider-data telemetry in the app</li>
          <li>No sale of credentials, provider data, or personal information</li>
          <li>No use of provider or Google user data for advertising, credit decisions, or training generalized AI models</li>
        </ul>
      </section>

      <section id="credentials">
        <h2>Credentials and OAuth tokens</h2>
        <p>Hosting, registrar, and site-service credentials are stored with device-only, when-unlocked iOS Keychain protection. Credentials are attached only to HTTPS requests for the selected provider&apos;s allowed API hosts. Cross-host redirects are blocked.</p>
        <p>Google Search Console, Google Analytics, and Firebase Hosting connections use Google&apos;s official OAuth authorization and token endpoints. Authorization opens in the system authentication session. Access and refresh tokens returned by Google are stored in the iOS Keychain and are used only to provide the Google feature you connected.</p>
        <p>Provider credentials inherit the permissions granted by that provider. Supported writes and purchases are initiated by you; detected write, purchase, and destructive requests require confirmation in the app.</p>
      </section>

      <section id="network-address">
        <h2>Public network address helper</h2>
        <p>When you open Namecheap or Name.com connection setup, Verceltics may make a credential-free HTTPS request to <a href="https://www.ipify.org/" rel="noreferrer" target="_blank">ipify</a> to display the current network&apos;s public IPv4 address. You choose whether to place it in Namecheap&apos;s required ClientIp field or copy it for Name.com&apos;s optional IP allowlist.</p>
        <p>The lookup does not include provider credentials, provider account data, or device identifiers added by Verceltics. Because ipify must reply to your network, it necessarily receives the source public IP as part of the request; any server-side processing or retention is governed by ipify&apos;s own policy. For Namecheap, you may place the detected address in the editable ClientIp field; the accepted value is stored with that connection because Namecheap requires it on API requests. For Name.com, Verceltics copies the address to the system pasteboard only after your explicit action and never saves it in the Name.com connection or sends it to the Name.com API.</p>
      </section>

      <section id="google-data">
        <h2>Google API data</h2>
        <p>When you connect a Google service, Verceltics requests your Google account identifier and email address through Google OpenID Connect. The app uses them only to identify the connected account, label it in account controls, and match later OAuth refreshes to the same saved connection. The identifier and email are stored in the iOS Keychain with the connection&apos;s OAuth tokens.</p>
        <p>Verceltics uses Google API data only to provide the user-facing feature you select:</p>
        <ul>
          <li><strong>Google Search Console:</strong> verified properties, search performance, indexing, sitemaps, and URL inspection</li>
          <li><strong>Google Analytics:</strong> GA4 properties and read-only traffic, engagement, acquisition, geography, device, page, event, and realtime reports</li>
          <li><strong>Firebase Hosting:</strong> hosting sites, channels, releases, versions, and user-initiated hosting operations supported by the app</li>
        </ul>
        <p>Google data is displayed on your device and may be included in the protected local snapshot cache described below. It is not transferred to Verceltics servers, sold, shared for advertising, or used for unrelated purposes.</p>
        <p>Verceltics&apos; use and transfer of information received from Google APIs adheres to the <a href="https://developers.google.com/terms/api-services-user-data-policy" rel="noreferrer" target="_blank">Google API Services User Data Policy</a>, including its Limited Use requirements.</p>
      </section>

      <section id="provider-data">
        <h2>Provider data and local cache</h2>
        <p>Account, project, domain, deployment, configuration, DNS, Worker, search, analytics, performance, uptime, and API explorer responses are fetched directly from the selected provider to your device.</p>
        <p>To avoid a blank dashboard on every launch, the Sites workspace can save recently viewed provider snapshots in the app&apos;s local Application Support directory. These files use iOS file protection and are excluded from device backups. In-memory caches also keep recently loaded screens responsive. Verceltics does not receive these caches.</p>
      </section>

      <section id="images">
        <h2>Favicons, avatars, and update checks</h2>
        <p>To display a project favicon, the app may make bounded, credential-free GET requests to that project site&apos;s own HTTPS origin. If no safe icon is available, it draws a local letter tile. Project domains are not sent to a third-party favicon service. Vercel profile avatars may be loaded from Vercel without provider credentials.</p>
        <p>The app may call Apple&apos;s public App Store lookup endpoint with the Verceltics app identifier and country to check whether a newer version is available. This request does not include provider credentials or provider account data.</p>
      </section>

      <section id="website">
        <h2>Website delivery</h2>
        <p>The Verceltics website is deployed through Cloudflare Workers Static Assets. The site does not include client-side analytics, advertising pixels, account sign-in, or forms that collect provider credentials. Cloudflare may process standard connection, security, and delivery logs under its own policies to serve and protect the website.</p>
      </section>

      <section id="purchases">
        <h2>Purchases and RevenueCat</h2>
        <p>Subscriptions, lifetime access, and optional tips are processed by Apple through the App Store. Verceltics uses RevenueCat to manage the Verceltics Pro entitlement, restore purchases, and provide purchase status to the app. RevenueCat may receive an anonymous app-user identifier, Apple receipt information, product identifiers, purchase history, and subscription or entitlement status.</p>
        <p>RevenueCat does not receive provider credentials or provider account data from Verceltics. Verceltics does not receive or store payment-card details.</p>
        <p>Refund decisions are made by Apple. If Apple asks for developer input on a refund request, RevenueCat may send Apple purchase and entitlement context. Verceltics configures RevenueCat to prefer declining refund requests; Apple retains the final decision.</p>
      </section>

      <section id="controls">
        <h2>Your controls and retention</h2>
        <p>You can remove a connected account or service inside Verceltics to delete its saved credential and associated local snapshot. You can also revoke OAuth access or rotate API credentials from the provider&apos;s own account settings. Provider-side retention is governed by that provider&apos;s policy.</p>
        <p>External links—including Apple, GitHub, supported providers, Product Hunt, LinkedIn, TrustMRR, Ko-fi, PayPal, and X—open third-party services with their own privacy practices.</p>
      </section>

      <section id="changes">
        <h2>Changes to this policy</h2>
        <p>We may update this policy when the app, its providers, or legal requirements change. The current version and revision date will remain available at this URL.</p>
      </section>

      <section id="contact">
        <h2>Contact</h2>
        <p>For privacy questions or requests, email <a href="mailto:ad13dtu@gmail.com">ad13dtu@gmail.com</a>. Security vulnerabilities should follow the private reporting process in the project&apos;s <a href="https://github.com/apoorvdarshan/verceltics/blob/main/SECURITY.md" rel="noreferrer" target="_blank">Security Policy</a>.</p>
      </section>
    </LegalShell>
  );
}
