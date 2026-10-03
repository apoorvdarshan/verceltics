import type { Metadata } from "next";

import { LegalShell } from "@/components/legal-shell";

const SITE_URL = "https://verceltics.com";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "How Verceltics handles app data, provider credentials, purchases, website delivery, and Vercie Discord commands.",
  alternates: { canonical: `${SITE_URL}/privacy` },
  openGraph: {
    type: "article",
    siteName: "Verceltics",
    title: "Privacy Policy — Verceltics",
    description: "App privacy, direct provider connections, and how Vercie processes Discord questions and public issue reports.",
    url: `${SITE_URL}/privacy`,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics mobile operations instrument" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Privacy Policy — Verceltics",
    description: "App privacy, direct provider connections, and how Vercie processes Discord questions and public issue reports.",
    images: ["/og-verceltics.png"],
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
  { id: "feedback", label: "Support and feedback" },
  { id: "discord", label: "Vercie on Discord" },
  { id: "purchases", label: "Purchases" },
  { id: "controls", label: "Your controls" },
  { id: "changes", label: "Policy changes" },
  { id: "contact", label: "Contact" },
] as const;

export default function Privacy() {
  return (
    <LegalShell
      asideDescription="Privacy details for the Verceltics app, website, and Vercie Discord bot."
      eyebrow="Direct-to-provider architecture"
      sections={sections}
      summary="The Verceltics app connects directly to providers. Vercie, our optional Discord bot, processes the questions and reports you submit through Cloudflare, Google Gemini, and GitHub as explained below."
      title="Privacy Policy"
      updated="October 3, 2026"
    >
      <section id="overview">
        <h2>Overview</h2>
        <p>Verceltics is an independent iPhone and iPad workspace for supported hosting platforms, domain registrars, and site-intelligence services. The app connects to services you choose using credentials or OAuth authorization you provide.</p>
        <p>Verceltics is developed and operated by Apoorv Darshan. This policy covers the app, this website, and the optional Vercie Discord service, including the features prepared for iOS 2.1.</p>
        <p><strong>Verceltics does not operate a credential or provider-data proxy.</strong> Requests for provider data go from your device directly to the selected provider&apos;s HTTPS API or an explicitly selected HTTPS host for a supported self-hosted service.</p>
        <p>The app-data sections below describe information accessed through the app. Vercie is a separate, optional Discord service with its own processing described in <a href="#discord">Vercie on Discord</a>. Vercie cannot access your app&apos;s saved credentials or connected accounts.</p>
      </section>

      <section id="app-data">
        <h2>Data the iOS app does not collect</h2>
        <p>The iOS app does not send provider credentials, account data, projects, domains, DNS records, deployments, logs, analytics, search data, or uptime data to Verceltics infrastructure.</p>
        <ul>
          <li>No advertising or cross-app tracking</li>
          <li>No Verceltics-operated product analytics and no provider-data telemetry; RevenueCat processes limited purchase history and technical context for App Store purchase functionality and purchase analytics as described below</li>
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
        <p>The Verceltics app uses Google API data only to provide the user-facing feature you select:</p>
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

      <section id="feedback">
        <h2>Support, bug reports, and feature requests</h2>
        <p>The app&apos;s About screen lets you join Discord, report an issue, or request a feature. GitHub options open a public issue template in your browser and require your own GitHub account. Discord options let you submit <code>/bug</code> or <code>/feature</code> through Vercie without a GitHub account; the bot&apos;s processing is described below. Opening these links does not automatically attach saved app credentials, account data, or logs.</p>
        <p>Issues you submit directly on GitHub are associated with your GitHub profile and are public. Include only information you want to share publicly, and redact screenshots and logs before submitting them. Sending a support email shares your email address and the content you choose to send with the developer; we use it to respond and handle your request. For requests about that correspondence, contact <a href="mailto:ad13dtu@gmail.com">ad13dtu@gmail.com</a>.</p>
      </section>

      <section id="discord">
        <h2>Vercie on Discord</h2>
        <p>Vercie responds when you submit a slash command in the configured Verceltics Discord server. Discord sends the command text and interaction metadata, including your Discord user, server, channel, and interaction identifiers, to our Cloudflare Worker. The Worker verifies the request, routes the command, applies abuse controls, and sends the response to Discord. Vercie does not passively read channel conversations, retrieve message history, or connect to your Verceltics app accounts.</p>
        <ul>
          <li><strong><code>/ask</code>:</strong> your submitted question is sent to Google&apos;s Gemini API with Verceltics help instructions to generate an answer. The answer is visible to people with access to the Discord channel.</li>
          <li><strong><code>/bug</code> and <code>/feature</code>:</strong> your report may be sent to Gemini to organize it, then the original report and any generated summary are published as a <strong>public GitHub issue</strong> in <a href="https://github.com/apoorvdarshan/verceltics/issues" rel="noreferrer" target="_blank">apoorvdarshan/verceltics</a>. Vercie does not add your Discord username or user ID to the public issue. Any personal information you include in the report itself can become public. The resulting issue link is posted in the channel.</li>
          <li><strong>Release announcements:</strong> Vercie may post new public app releases in the designated announcements channel. This uses release information rather than members&apos; message history.</li>
        </ul>
        <p><strong>Do not submit passwords, API keys, private account data, personal information, or confidential reports through Vercie.</strong> Security vulnerabilities should use our <a href="https://github.com/apoorvdarshan/verceltics/blob/main/SECURITY.md" rel="noreferrer" target="_blank">private security reporting process</a>. Bot replies and reports are not a private support conversation.</p>
        <p>Google&apos;s processing depends on the Gemini service and billing configuration. Under its unpaid-service terms, submitted content and generated responses may be used to improve Google products and reviewed by humans; paid-service terms describe different handling. We do not promise that Google immediately deletes submitted content or excludes it from model improvement. See the <a href="https://ai.google.dev/gemini-api/terms" rel="noreferrer" target="_blank">Gemini API terms</a> and <a href="https://policies.google.com/privacy" rel="noreferrer" target="_blank">Google Privacy Policy</a>.</p>
        <p>The bot does not maintain a conversation archive. Cloudflare KV stores release-version markers and short-lived hashes derived from interaction or user identifiers for duplicate-request and cooldown handling; these request and cooldown entries expire within 15 minutes. Bot operational logs omit submitted question and report text. Cloudflare may separately process service, delivery, and security records under its <a href="https://www.cloudflare.com/privacypolicy/" rel="noreferrer" target="_blank">Privacy Policy</a>.</p>
        <p>Replies remain in Discord and issues remain on GitHub until removed under the respective service&apos;s controls and policies. Deleting a Discord message does not remove a GitHub issue. See the <a href="https://discord.com/privacy" rel="noreferrer" target="_blank">Discord Privacy Policy</a> and <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement" rel="noreferrer" target="_blank">GitHub Privacy Statement</a>. To request correction or deletion of bot-generated content, email <a href="mailto:ad13dtu@gmail.com">ad13dtu@gmail.com</a> with the message or issue link. We can address content we control, but cannot guarantee removal of public copies or records independently retained by third parties.</p>
      </section>

      <section id="purchases">
        <h2>Purchases and RevenueCat</h2>
        <p>Subscriptions, lifetime access, and optional tips are processed by Apple through the App Store. Verceltics uses RevenueCat to manage the Verceltics Pro entitlement, restore purchases, and provide purchase status to the app. RevenueCat may receive an anonymous app-user identifier; device type, operating-system, platform, app-version, and locale context; Apple receipt information; product identifiers; purchase history; subscription or entitlement status; and purchase-service timestamps such as first-seen or last-seen app use. RevenueCat uses purchase history for app functionality and purchase analytics.</p>
        <p>RevenueCat does not receive provider credentials or provider account data from Verceltics. Verceltics does not receive or store payment-card details.</p>
        <p>Refund decisions are made by Apple. If refund-request handling is enabled, RevenueCat may send Apple limited purchase delivery and consumption context in response to a refund request, subject to the consent described in the Terms of Service. Apple retains the final decision.</p>
      </section>

      <section id="controls">
        <h2>Your controls and retention</h2>
        <p>You can remove a connected account or service inside Verceltics to delete its saved credential and associated local snapshot. You can also revoke OAuth access or rotate API credentials from the provider&apos;s own account settings. Provider-side retention is governed by that provider&apos;s policy.</p>
        <p>External links—including Apple, GitHub, Discord, supported providers, Product Hunt, LinkedIn, Instagram, Ko-fi, PayPal, and X—open third-party services with their own privacy practices.</p>
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
