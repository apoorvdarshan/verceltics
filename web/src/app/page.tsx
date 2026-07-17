import type { Metadata } from "next";
import Image from "next/image";

import { ArrowUpRight } from "@/components/arrow-up-right";
import { HeroSwitcher } from "@/components/hero-switcher";
import { ProviderDirectory } from "@/components/provider-directory";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

const SITE_URL = "https://verceltics.com";
const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export const metadata: Metadata = { alternates: { canonical: SITE_URL } };

const workflows = [
  ["Deploy", "Review status, environment, releases & logs."],
  ["DNS", "Inspect zones, records, nameservers & changes."],
  ["Renew", "Check expiry, privacy, forwarding & transfers."],
  ["Traffic", "Read visitors, cache, threats & HTTPS."],
  ["Index", "Check clicks, sitemaps, coverage & URLs."],
  ["Uptime", "Confirm monitor state, latency & availability."],
] as const;

const plans = [
  { name: "Monthly", price: "$4.99", detail: "per month" },
  { name: "Yearly", price: "$34.99", detail: "per year · 7-day trial" },
  { name: "Lifetime", price: "$59.99", detail: "one-time purchase" },
] as const;

const faqs = [
  {
    question: "What is Verceltics?",
    answer: "Verceltics is an independent native workspace for hosting platforms, domain registrars, and site-intelligence services on iPhone and iPad.",
  },
  {
    question: "Does Verceltics merge provider data?",
    answer: "No. Providers remain separate. Google Search Console and Google Analytics, for example, have separate connections and separate dashboards inside Sites.",
  },
  {
    question: "Where are credentials stored?",
    answer: "Credentials and OAuth tokens use device-only, when-unlocked iOS Keychain protection. Requests go directly to each provider’s official HTTPS API; Verceltics does not run a credential proxy.",
  },
  {
    question: "Does it work on iPad?",
    answer: "Yes. Verceltics is a universal app with a sidebar, adaptive metric grids, wider tables, and full-width charts on iPad.",
  },
  {
    question: "Can I build it myself?",
    answer: "Yes. The complete SwiftUI app and website are open source under the MIT license. A source build uses your own provider credentials and OAuth configuration.",
  },
] as const;

const applicationJsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: "Verceltics",
  operatingSystem: "iOS 18 or later",
  applicationCategory: "DeveloperApplication",
  softwareVersion: "2.0",
  description: "A native iPhone and iPad workspace for hosting, domains, analytics, search performance, speed, and uptime.",
  url: SITE_URL,
  downloadUrl: APP_STORE,
  image: `${SITE_URL}/screens/ipad/cloudflare.png`,
  screenshot: [
    `${SITE_URL}/screens/ipad/cloudflare.png`,
    `${SITE_URL}/screens/ios/hosting.png`,
    `${SITE_URL}/screens/ios/search.png`,
  ],
  sameAs: [APP_STORE, GITHUB],
  offers: plans.map((plan) => ({ "@type": "Offer", price: plan.price.replace("$", ""), priceCurrency: "USD", description: `${plan.name} access` })),
};

const faqJsonLd = {
  "@context": "https://schema.org",
  "@type": "FAQPage",
  mainEntity: faqs.map((faq) => ({ "@type": "Question", name: faq.question, acceptedAnswer: { "@type": "Answer", text: faq.answer } })),
};

export default function Home() {
  return (
    <div className="site-shell">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(applicationJsonLd) }} type="application/ld+json" />
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(faqJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main id="main-content">
        <HeroSwitcher />

        <section className="connections-section" id="connections">
          <header className="section-heading section-heading--connections">
            <p className="section-kicker">Every account stays independent</p>
            <h2>27 connections.<br />No detours.</h2>
            <p>Connect the providers you already use. Each keeps its own account context, controls, and official API.</p>
          </header>
          <ProviderDirectory />
        </section>

        <section className="workflow-section" id="workflows">
          <header className="section-heading section-heading--workflow">
            <p className="section-kicker">Worth opening your phone for</p>
            <h2>From deploy<br />to uptime.</h2>
          </header>
          <div className="workflow-list">
            {workflows.map(([name, detail]) => (
              <article key={name}>
                <strong>{name}</strong>
                <p>{detail}</p>
              </article>
            ))}
          </div>
          <p className="workflow-note">Changes and destructive requests always ask first.</p>
        </section>

        <section className="signal-section">
          <div className="signal-heading">
            <p className="section-kicker">Sites keeps signals separate</p>
            <h2>Search <span>≠</span> traffic.</h2>
            <p>Connect each independently. Search performance stays in Search Console. Traffic and engagement stay in Analytics.</p>
          </div>
          <div className="signal-comparison">
            <figure>
              <div className="signal-phone signal-phone--search"><Image alt="Google Search Console dashboard in Verceltics" fill sizes="(max-width: 720px) 78vw, 330px" src="/screens/ios/search.webp" /></div>
              <figcaption><span>Search Console</span><strong>Clicks, indexing & URLs</strong></figcaption>
            </figure>
            <span aria-hidden="true" className="not-equal">≠</span>
            <figure>
              <div className="signal-phone signal-phone--analytics"><Image alt="Google Analytics dashboard in Verceltics" fill sizes="(max-width: 720px) 78vw, 330px" src="/screens/ios/google-analytics.webp" /></div>
              <figcaption><span>Google Analytics</span><strong>Visitors, sessions & events</strong></figcaption>
            </figure>
          </div>
        </section>

        <section className="ipad-section">
          <header className="section-heading section-heading--ipad">
            <p className="section-kicker">Built wide on iPad</p>
            <h2>Not stretched.<br />Re-composed.</h2>
            <p>A persistent sidebar, adaptive metric grid, wider detail surfaces, and full-width charts make iPad a real operations workspace.</p>
          </header>
          <figure className="ipad-screen"><Image alt="Cloudflare analytics dashboard in Verceltics on iPad" fill sizes="(max-width: 900px) 96vw, 1500px" src="/screens/ipad/cloudflare.webp" /></figure>
          <div className="ipad-facts"><span>Persistent sidebar</span><span>Adaptive metrics</span><span>Full-width charts</span></div>
        </section>

        <section className="privacy-section" id="privacy">
          <div className="privacy-copy">
            <p className="section-kicker">Private by architecture</p>
            <h2>Your credentials<br />never visit us.</h2>
            <p>Tokens stay in the device-only iOS Keychain. Provider data moves between your device and the provider’s official HTTPS API.</p>
          </div>
          <div aria-label="Your device connects directly to official provider APIs" className="direct-diagram">
            <div><Image alt="" height={56} src="/icon.png" width={56} /><span><strong>Your device</strong><small>Keychain + protected cache</small></span></div>
            <span aria-hidden="true" className="direct-line"><i /><i /><i /></span>
            <div><b>API</b><span><strong>Official provider</strong><small>Encrypted HTTPS request</small></span></div>
          </div>
          <div className="proxy-proof"><span>Verceltics credential proxy</span><strong>None.</strong><div><a href="/privacy">Privacy policy <ArrowUpRight /></a><a href={GITHUB} rel="noreferrer" target="_blank">Audit the source <ArrowUpRight /></a></div></div>
        </section>

        <section className="pricing-section" id="pricing">
          <header className="section-heading section-heading--pricing">
            <p className="section-kicker">Verceltics Pro</p>
            <h2>One app.<br />Every connection.</h2>
          </header>
          <div className="price-strip">
            {plans.map((plan) => <div key={plan.name}><span>{plan.name}</span><strong>{plan.price}</strong><small>{plan.detail}</small></div>)}
            <a className="button button--store" href={APP_STORE} rel="noreferrer" target="_blank">View in the App Store <ArrowUpRight /></a>
          </div>
          <p className="source-note">Every paid option unlocks all 27 connections on iPhone and iPad. The MIT-licensed source remains available for personal builds.</p>
        </section>

        <section className="faq-section">
          <header><p className="section-kicker">Before connecting</p><h2>Questions.</h2></header>
          <div className="faq-list">
            {faqs.map((faq) => <details key={faq.question}><summary>{faq.question}<span aria-hidden="true">+</span></summary><p>{faq.answer}</p></details>)}
          </div>
        </section>

        <section className="closing-billboard">
          <div><p>Verceltics 2.0 / iPhone + iPad</p><h2>Your stack<br />is already<br />waiting.</h2></div>
          <a className="button button--closing" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
