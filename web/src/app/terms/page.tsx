import type { Metadata } from "next";

import { LegalShell } from "@/components/legal-shell";

const SITE_URL = "https://verceltics.com";

export const metadata: Metadata = {
  title: "Terms of Service",
  description:
    "Terms for Verceltics provider connections, user-initiated operations, subscriptions, lifetime access, and Apple-handled purchases.",
  alternates: { canonical: `${SITE_URL}/terms` },
  openGraph: {
    type: "article",
    siteName: "Verceltics",
    title: "Terms of Service — Verceltics",
    description: "Terms for using the Verceltics iPhone and iPad app.",
    url: `${SITE_URL}/terms`,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics mobile operations instrument" }],
  },
};

const sections = [
  { id: "acceptance", label: "Acceptance" },
  { id: "service", label: "The service" },
  { id: "accounts", label: "Accounts and actions" },
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
      summary="These terms cover your use of Verceltics, the provider credentials and operations you control, and purchases processed by Apple."
      title="Terms of Service"
      updated="July 17, 2026"
    >
      <section id="acceptance">
        <h2>Acceptance</h2>
        <p>By downloading, building, or using Verceltics, you agree to these terms and the <a href="/privacy">Privacy Policy</a>. If you do not agree, do not use the app.</p>
      </section>

      <section id="service">
        <h2>The service</h2>
        <p>Verceltics is an independent iPhone and iPad workspace for supported hosting platforms, domain registrars, and site-intelligence services. It uses credentials or OAuth authorization you provide to communicate directly with the provider, display provider data, and perform supported actions you initiate.</p>
        <p>Verceltics is not affiliated with, endorsed by, or sponsored by any supported provider. Provider names, marks, APIs, plans, data, limits, and availability remain controlled by their respective owners.</p>
      </section>

      <section id="accounts">
        <h2>Your accounts, credentials, and actions</h2>
        <p>You are responsible for every credential or Google authorization you connect and for all activity initiated through it. Use the narrowest provider permissions that meet your needs. Revoke or rotate access if you stop using the app or suspect exposure.</p>
        <p>Provider credentials inherit the permissions granted by that provider and may allow configuration changes, deployment actions, purchases, or destructive operations. Verceltics requires confirmation for detected writes, purchases, and destructive operations, but you remain responsible for reviewing the provider, HTTP method, path, parameters, body, and effect before confirming.</p>
        <p>You must use Verceltics only with accounts and data you are authorized to access and in accordance with provider terms, applicable law, rate limits, and acceptable-use policies. Do not use the app to evade provider security controls or access another person&apos;s account without permission.</p>
      </section>

      <section id="purchases">
        <h2>Subscriptions, lifetime access, and tips</h2>
        <p>Verceltics offers these App Store purchase options:</p>
        <ul>
          <li><strong>Monthly:</strong> $4.99 per month, auto-renewable, with no trial</li>
          <li><strong>Yearly:</strong> $34.99 per year, auto-renewable, with a 7-day introductory trial for eligible first-time subscribers</li>
          <li><strong>Lifetime:</strong> $59.99 one-time, non-consumable purchase with no recurring charge</li>
        </ul>
        <p>Prices may vary by country, currency, tax, or future App Store pricing changes. The price shown by Apple at confirmation controls.</p>
        <p>Payment is charged to your Apple ID at confirmation. Auto-renewable subscriptions continue unless cancelled at least 24 hours before the end of the current period. Manage or cancel them in <code>Settings → Apple ID → Subscriptions</code>. Any unused trial portion may be forfeited when a subscription is purchased.</p>
        <p>Optional Coffee, Lunch, Big, and Huge tips are one-time consumable purchases. They support development, unlock no feature or content, and are not subscriptions.</p>
        <p>Apple processes purchases and decides refund requests under its policies. Verceltics uses RevenueCat for entitlement status, restoration, purchase context, and refund-request handling. Verceltics configures RevenueCat to prefer declining refunds when Apple asks for developer input; Apple makes the final decision. Verceltics does not issue App Store refunds directly.</p>
      </section>

      <section id="source">
        <h2>Building from source</h2>
        <p>The source code is available under the MIT license at <a href="https://github.com/apoorvdarshan/verceltics" rel="noreferrer" target="_blank">github.com/apoorvdarshan/verceltics</a>. You may build it for personal use subject to that license, Apple&apos;s developer terms, provider terms, and your own credentials. The App Store version is offered for convenience and to fund ongoing development.</p>
        <p>Unofficial builds and forks are controlled by their maintainers. Verceltics does not provide warranties or support for modified builds.</p>
      </section>

      <section id="availability">
        <h2>Availability, updates, and external services</h2>
        <p>Provider APIs, endpoints, authentication rules, response formats, features, plans, and limits may change without notice. Verceltics may add, change, or remove provider integrations when required to keep the app safe and maintainable.</p>
        <p>The app may check Apple&apos;s public App Store endpoint for updates. Installing an update is optional, but older builds may stop receiving fixes or working with changed provider APIs.</p>
        <p>Links to Apple, GitHub, supported providers, Product Hunt, LinkedIn, Instagram, TrustMRR, Ko-fi, PayPal, X, and other third parties are provided for convenience. Their content, availability, purchases, and policies are outside Verceltics&apos; control.</p>
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
