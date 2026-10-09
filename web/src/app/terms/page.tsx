import type { Metadata } from "next";

import { LegalShell } from "@/components/legal-shell";

const SITE_URL = "https://verceltics.com";

export const metadata: Metadata = {
  title: "Terms of Service",
  description:
    "Terms for Verceltics provider connections, purchases, and Vercie Discord questions and public issue reports.",
  alternates: { canonical: `${SITE_URL}/terms` },
  openGraph: {
    type: "article",
    siteName: "Verceltics",
    title: "Terms of Service | Verceltics",
    description: "Terms for using the Verceltics app, website, and Vercie Discord bot.",
    url: `${SITE_URL}/terms`,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics mobile operations instrument" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Terms of Service | Verceltics",
    description: "Terms for using the Verceltics app, website, and Vercie Discord bot.",
    images: ["/og-verceltics.png"],
  },
};

const sections = [
  { id: "acceptance", label: "Acceptance" },
  { id: "service", label: "The service" },
  { id: "accounts", label: "Accounts and actions" },
  { id: "feedback", label: "Support and feedback" },
  { id: "discord", label: "Vercie on Discord" },
  { id: "purchases", label: "Purchases" },
  { id: "source", label: "Building from source" },
  { id: "availability", label: "Availability" },
  { id: "disclaimers", label: "Disclaimers" },
  { id: "changes", label: "Changes" },
  { id: "contact", label: "Contact" },
] as const;

export default function Terms() {
  return (
    <LegalShell
      asideDescription="Plain-language terms for using an independent provider workspace."
      eyebrow="Independent developer tool"
      sections={sections}
      summary="These terms cover the Verceltics app and website, provider operations you control, purchases processed by Apple and Google Play, and the optional Vercie Discord bot."
      title="Terms of Service"
      updated="October 10, 2026"
    >
      <section id="acceptance">
        <h2>Acceptance</h2>
        <p>By downloading, building, or using Verceltics, including submitting commands to Vercie on Discord, you agree to these terms and the <a href="/privacy">Privacy Policy</a>. If you do not agree, do not use these services.</p>
      </section>

      <section id="service">
        <h2>The service</h2>
        <p>Verceltics is an independent iPhone, iPad, and Android workspace for supported hosting platforms, domain registrars, and site-intelligence services. It uses credentials or OAuth authorization you provide to communicate directly with the provider, display provider data, and perform supported actions you initiate.</p>
        <p>Verceltics is developed and operated by Apoorv Darshan. Features described on this website may reflect source builds before they reach the App Store or Google Play. Check the store listing for the version available to download. The Android app may be offered as a beta or pre-release build through Google Play testing before it is generally available; beta builds can change, contain bugs, or stop being offered. Provider capabilities depend on permissions, configuration, account plans, and API availability.</p>
        <p>Verceltics is not affiliated with, endorsed by, or sponsored by any supported provider. Provider names, marks, APIs, plans, data, limits, and availability remain controlled by their respective owners.</p>
      </section>

      <section id="accounts">
        <h2>Your accounts, credentials, and actions</h2>
        <p>You are responsible for every credential or Google authorization you connect and for all activity initiated through it. Use the narrowest provider permissions that meet your needs. Revoke or rotate access if you stop using the app or suspect exposure.</p>
        <p>Provider credentials inherit the permissions granted by that provider and may allow configuration changes, deployment actions, purchases, or destructive operations. Verceltics requires confirmation for detected writes, purchases, and destructive operations, but you remain responsible for reviewing the provider, HTTP method, path, parameters, body, and effect before confirming.</p>
        <p>You must use Verceltics only with accounts and data you are authorized to access and in accordance with provider terms, applicable law, rate limits, and acceptable-use policies. Do not use the app to evade provider security controls or access another person&apos;s account without permission.</p>
      </section>

      <section id="feedback">
        <h2>Support and feedback</h2>
        <p>The app&apos;s About screen offers GitHub and Discord options for reporting an issue or requesting a feature. GitHub options open the corresponding issue template and require a GitHub account. Discord options use Vercie and do not require a GitHub account. Both routes create public GitHub issues; submitting an issue or idea does not guarantee a response, fix, implementation, or release date.</p>
        <p>Share only material you are authorized to make public. Do not include credentials, private provider data, or confidential security reports. Community participation is covered by our <a href="https://github.com/apoorvdarshan/verceltics/blob/main/CODE_OF_CONDUCT.md" rel="noreferrer" target="_blank">Code of Conduct</a>; vulnerabilities should use the <a href="https://github.com/apoorvdarshan/verceltics/blob/main/SECURITY.md" rel="noreferrer" target="_blank">private security reporting process</a>.</p>
      </section>

      <section id="discord">
        <h2>Vercie on Discord</h2>
        <p>Vercie provides optional Verceltics help through <code>/ask</code>, accepts <code>/bug</code> and <code>/feature</code> reports, and may publish app-release announcements. It processes explicitly submitted commands through Cloudflare and may use Google Gemini to generate answers and organize reports. Use is subject to the applicable <a href="https://discord.com/terms" rel="noreferrer" target="_blank">Discord terms</a>, <a href="https://ai.google.dev/gemini-api/terms" rel="noreferrer" target="_blank">Gemini API terms</a>, and <a href="https://docs.github.com/en/site-policy/github-terms/github-terms-of-service" rel="noreferrer" target="_blank">GitHub terms</a>.</p>
        <p>By submitting <code>/bug</code> or <code>/feature</code>, you ask us to publish your report and any generated summary as a <strong>public GitHub issue</strong> in the Verceltics repository. Submit only information you are authorized to share publicly. Successful command replies are visible in the Discord channel. Do not include credentials, personal information, confidential material, or security vulnerabilities; use the <a href="https://github.com/apoorvdarshan/verceltics/blob/main/SECURITY.md" rel="noreferrer" target="_blank">private security reporting process</a> for vulnerabilities.</p>
        <p>AI-generated answers and issue summaries can be inaccurate or incomplete. Review them before relying on them, especially instructions that could change or delete provider resources. Vercie cannot access your app connections, perform provider operations, or guarantee a fix, response, release date, or continuous availability. We may limit or suspend bot access to address spam, abuse, service limits, or maintenance.</p>
        <p>Processing, publication, retention, and requests to correct or delete bot-generated content are described in <a href="/privacy#discord">Vercie&apos;s privacy section</a>.</p>
      </section>

      <section id="purchases">
        <h2>Subscriptions, lifetime access, and tips</h2>
        <p>On the App Store, Verceltics offers these purchase options:</p>
        <ul>
          <li><strong>Monthly:</strong> $4.99 per month, auto-renewable, with no trial</li>
          <li><strong>Yearly:</strong> $34.99 per year, auto-renewable, with a 7-day introductory trial for eligible first-time subscribers</li>
          <li><strong>Lifetime:</strong> $59.99 one-time, non-consumable purchase with no recurring charge</li>
        </ul>
        <p>Prices may vary by country, currency, tax, or future App Store pricing changes. The price shown by Apple at confirmation controls.</p>
        <p>Payment is charged to your Apple Account at confirmation. Auto-renewable subscriptions continue unless cancelled at least 24 hours before the end of the current period. Manage or cancel them in <code>Settings → [your name] → Subscriptions</code>. Any unused trial portion may be forfeited when a subscription is purchased.</p>
        <p>On Android, the same monthly, yearly, and lifetime options are sold through Google Play Billing, and the price, billing period, and any trial shown by Google Play at confirmation control. Payment is charged to your Google account. Subscriptions renew automatically until cancelled, and you can manage or cancel them in Google Play under <code>Payments &amp; subscriptions → Subscriptions</code> or from <code>About → Manage subscription</code> in the app.</p>
        <p>We may offer promotional codes, such as App Store offer codes or Google Play promo codes, that give a limited free period of Verceltics Pro. Each code has its own eligibility rules, redemption limit, and expiry date, and can end once its limit is reached. An App Store offer code follows the renewal terms Apple shows when you redeem it. On Google Play, a subscription started with a promo code renews at the regular price after the free period unless you cancel in Google Play before the free period ends.</p>
        <p>Optional Coffee, Lunch, Big, and Huge tips are one-time consumable purchases. They support development, unlock no feature or content, and are not subscriptions.</p>
        <p>Apple processes App Store purchases and decides refund requests under its policies. Verceltics uses RevenueCat for entitlement status, restoration, purchase context, and optional refund-request handling. Where that handling is enabled, RevenueCat may send Apple limited purchase delivery and consumption context in response to a refund request. By making an in-app purchase after accepting these terms, you consent to that limited sharing solely for Apple&apos;s refund evaluation. Apple makes the final decision, and Verceltics does not issue App Store refunds directly. Google Play purchases are processed by Google, and refunds follow Google Play&apos;s refund policies.</p>
      </section>

      <section id="source">
        <h2>Building from source</h2>
        <p>The source code is available under the MIT license at <a href="https://github.com/apoorvdarshan/verceltics" rel="noreferrer" target="_blank">github.com/apoorvdarshan/verceltics</a>. You may build it for personal use subject to that license, Apple&apos;s and Google&apos;s developer terms, provider terms, and your own credentials. The App Store and Google Play versions are offered for convenience and to fund ongoing development.</p>
        <p>Unofficial builds and forks are controlled by their maintainers. Verceltics does not provide warranties or support for modified builds.</p>
      </section>

      <section id="availability">
        <h2>Availability, updates, and external services</h2>
        <p>Provider APIs, endpoints, authentication rules, response formats, features, plans, and limits may change without notice. Verceltics may add, change, or remove provider integrations when required to keep the app safe and maintainable.</p>
        <p>The iOS app may check Apple&apos;s public App Store endpoint for updates, and Google Play delivers Android updates. Installing an update is optional, but older builds may stop receiving fixes or working with changed provider APIs.</p>
        <p>Links to Apple, Google Play, GitHub, Discord, supported providers, Product Hunt, LinkedIn, Instagram, Ko-fi, PayPal, X, and other third parties are provided for convenience. Their content, availability, purchases, and policies are outside Verceltics&apos; control.</p>
      </section>

      <section id="disclaimers">
        <h2>Disclaimers and limitation of liability</h2>
        <p>Verceltics is provided “as is” and “as available,” without warranties of merchantability, fitness for a particular purpose, non-infringement, uninterrupted availability, or accuracy. Provider data and operation results are returned by third parties and may be delayed, incomplete, or incorrect.</p>
        <p>To the maximum extent permitted by law, Verceltics and its developer are not liable for indirect, incidental, special, exemplary, punitive, or consequential damages; lost revenue, profits, data, or business; provider charges; downtime; or changes resulting from credentials or operations you authorize.</p>
        <p>If applicable law does not permit a limitation above, liability is limited to the maximum extent allowed by that law.</p>
      </section>

      <section id="changes">
        <h2>Changes to these terms</h2>
        <p>We may update these terms when the app, purchases, providers, or legal requirements change. The current version and revision date will remain available at this URL. Continued use after an update means you accept the revised terms.</p>
      </section>

      <section id="contact">
        <h2>Contact</h2>
        <p>Questions about these terms can be sent to <a href="mailto:ad13dtu@gmail.com">ad13dtu@gmail.com</a>.</p>
      </section>
    </LegalShell>
  );
}
