import type { Metadata } from "next";
import Image from "next/image";

import { ArrowUpRight } from "@/components/arrow-up-right";
import { InstrumentHero } from "@/components/instrument-hero";
import { ProviderPatchbay } from "@/components/provider-directory";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

const SITE_URL = "https://verceltics.com";
const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";
const PUBLIC_PROFILES = [
  GITHUB,
  "https://www.producthunt.com/products/verceltics",
  "https://trustmrr.com/startup/vercel-analytics-verceltics",
  "https://www.linkedin.com/company/verceltics",
  "https://www.instagram.com/verceltics/",
  "https://ko-fi.com/apoorvdarshan",
  "https://x.com/apoorvdarshan",
] as const;

export const metadata: Metadata = { alternates: { canonical: SITE_URL } };

const checks = [
  { number: "01", name: "Deploy", detail: "Status, environments, releases and logs" },
  { number: "02", name: "DNS", detail: "Zones, records, nameservers and changes" },
  { number: "03", name: "Renew", detail: "Expiry, privacy, forwarding and transfers" },
  { number: "04", name: "Traffic", detail: "Visitors, cache, threats and HTTPS" },
  { number: "05", name: "Index", detail: "Clicks, sitemaps, coverage and URLs" },
  { number: "06", name: "Uptime", detail: "Monitor state, latency and availability" },
] as const;

const plans = [
  { code: "M", name: "Monthly", price: "$4.99", detail: "per month" },
  { code: "Y", name: "Yearly", price: "$34.99", detail: "per year · 7-day trial" },
  { code: "∞", name: "Lifetime", price: "$59.99", detail: "one-time purchase" },
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

const ipadScreens = [
  {
    src: "/screens/ipad/analytics.png",
    label: "Traffic analytics",
    detail: "Requests, visitors, cache, threats and HTTPS",
    alt: "Traffic analytics workspace in Verceltics on iPad",
  },
  {
    src: "/screens/ipad/hosting.png",
    label: "Hosting",
    detail: "10 hosting platforms in an adaptive provider sheet",
    alt: "Hosting platform connections in Verceltics on iPad",
  },
  {
    src: "/screens/ipad/registrars.png",
    label: "Registrars",
    detail: "Domains, DNS, renewals, privacy and transfers",
    alt: "Domain registrar connections in Verceltics on iPad",
  },
  {
    src: "/screens/ipad/sites.png",
    label: "Site services",
    detail: "Search, analytics, speed and uptime connections",
    alt: "Site intelligence service connections in Verceltics on iPad",
  },
] as const;

const applicationJsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: "Verceltics",
  operatingSystem: "iOS 18 or later",
  applicationCategory: "DeveloperApplication",
  softwareVersion: "2.0",
  description: "Verceltics is a private native iPhone and iPad app for hosting, domains, analytics, search performance, speed, and uptime.",
  url: SITE_URL,
  downloadUrl: APP_STORE,
  image: `${SITE_URL}/og-verceltics.png`,
  screenshot: [
    `${SITE_URL}/screens/ios/analytics.png`,
    `${SITE_URL}/screens/ios/hosting.png`,
    `${SITE_URL}/screens/ios/registrars.png`,
    `${SITE_URL}/screens/ios/sites.png`,
    `${SITE_URL}/screens/ipad/analytics.png`,
    `${SITE_URL}/screens/ipad/hosting.png`,
    `${SITE_URL}/screens/ipad/registrars.png`,
    `${SITE_URL}/screens/ipad/sites.png`,
  ],
  sameAs: [APP_STORE, ...PUBLIC_PROFILES],
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
        <InstrumentHero />

        <section className="patchbay-section" id="patchbay">
          <header className="section-intro patchbay-intro">
            <div>
              <p className="instrument-label"><span>01</span> Connection patchbay</p>
              <h2>Plug in what you already run.</h2>
            </div>
            <p>Every account keeps its own context, controls and official API. Verceltics supplies the native interface—not a data soup.</p>
          </header>
          <ProviderPatchbay />
        </section>

        <section className="inspection-section" id="workflows">
          <div className="inspection-copy">
            <p className="instrument-label instrument-label--light"><span>02</span> Field checklist</p>
            <h2>One sweep before you move.</h2>
            <p>Verceltics is tuned for fast, specific checks: the production questions that arrive while you are away from a desk.</p>
            <p className="confirmation-note"><i /> Writes, purchases and destructive requests ask for confirmation.</p>
          </div>
          <ol className="check-tape">
            {checks.map((check) => (
              <li key={check.name}>
                <span>{check.number}</span>
                <strong>{check.name}</strong>
                <p>{check.detail}</p>
                <i aria-hidden="true" />
              </li>
            ))}
          </ol>
        </section>

        <section className="ipad-section">
          <header className="section-intro ipad-intro">
            <div>
              <p className="instrument-label"><span>03</span> Adaptive instrument</p>
              <h2>iPad is re-composed. Not stretched.</h2>
            </div>
            <p>Adaptive navigation, two-column provider grids and full-width charts turn the portrait canvas into a real operations workspace.</p>
          </header>
          <div className="ipad-console">
            <div className="console-toolbar">
              <span><i /> Live workspace</span>
              <strong>iPad / Portrait workspace</strong>
              <span>1640 × 2360</span>
            </div>
            <div className="ipad-gallery">
              {ipadScreens.map((screen, index) => (
                <figure className="ipad-gallery-item" key={screen.src}>
                  <div className="ipad-screen">
                    <Image
                      alt={screen.alt}
                      fill
                      sizes="(max-width: 780px) 96vw, 46vw"
                      src={screen.src}
                    />
                  </div>
                  <figcaption>
                    <span>{String(index + 1).padStart(2, "0")}</span>
                    <strong>{screen.label}</strong>
                    <p>{screen.detail}</p>
                  </figcaption>
                </figure>
              ))}
            </div>
            <div className="console-features"><span>Adaptive navigation</span><span>Two-column grids</span><span>Full-width charts</span></div>
          </div>
        </section>

        <section className="signals-section">
          <header className="signals-copy">
            <p className="instrument-label"><span>04</span> Connected signals</p>
            <h2>Connect, then inspect.</h2>
            <p>The Sites workspace keeps every service separate. Connect the signal you need, then open its native dashboard without mixing provider data.</p>
          </header>
          <div className="signal-rack">
            <figure className="signal-module signal-module--sites">
              <figcaption><span>Connection bank</span><strong>Site services</strong><p>Search · analytics · speed · uptime</p></figcaption>
              <div className="signal-screen"><Image alt="Site service connections in Verceltics" fill sizes="(max-width: 640px) 86vw, (max-width: 1080px) 44vw, 31vw" src="/screens/ios/sites.png" /></div>
            </figure>
            <div aria-hidden="true" className="signal-separator"><span>→</span><i /><i /></div>
            <figure className="signal-module signal-module--analytics">
              <figcaption><span>Live surface</span><strong>Traffic analytics</strong><p>Requests · cache · threats · HTTPS</p></figcaption>
              <div className="signal-screen"><Image alt="Traffic analytics in Verceltics" fill sizes="(max-width: 640px) 86vw, (max-width: 1080px) 44vw, 31vw" src="/screens/ios/analytics.png" /></div>
            </figure>
          </div>
        </section>

        <section className="circuit-section" id="privacy">
          <header className="circuit-heading">
            <p className="instrument-label instrument-label--light"><span>05</span> Sealed circuit</p>
            <h2>Your credentials never route through us.</h2>
            <p>Tokens stay in the device-only iOS Keychain. Provider data moves between your device and the provider’s official HTTPS API.</p>
          </header>
          <div aria-label="Your device connects directly to an official provider API" className="circuit-path" role="group">
            <div className="circuit-node circuit-node--device">
              <span className="node-icon"><Image alt="" height={46} src="/icon.png" width={46} /></span>
              <span><small>Origin</small><strong>Your device</strong><p>Keychain + protected cache</p></span>
            </div>
            <div className="cable"><i /><span>Encrypted HTTPS</span><i /></div>
            <div className="circuit-node circuit-node--provider">
              <span className="node-icon">API</span>
              <span><small>Destination</small><strong>Official provider</strong><p>Direct request and response</p></span>
            </div>
          </div>
          <div className="missing-port">
            <span><i /> Port not fitted</span>
            <strong>Verceltics credential server</strong>
            <b>NOT PRESENT</b>
          </div>
          <div className="circuit-links">
            <a href="/privacy">Read the privacy policy <ArrowUpRight /></a>
            <a href={GITHUB} rel="noreferrer" target="_blank">Audit the source <ArrowUpRight /></a>
          </div>
        </section>

        <section className="pricing-section" id="pricing">
          <header className="section-intro pricing-intro">
            <div>
              <p className="instrument-label"><span>06</span> Ownership plate</p>
              <h2>One unlock. Every connection.</h2>
            </div>
            <p>Every paid option unlocks all 27 connections on iPhone and iPad. The MIT-licensed source stays available for personal builds.</p>
          </header>
          <div className="price-console">
            <div className="price-console-head"><span>Verceltics Pro</span><span>Choose access term</span><span>All ports enabled</span></div>
            <div className="price-options">
              {plans.map((plan) => (
                <div className="price-row" key={plan.name}>
                  <span>{plan.code}</span><strong>{plan.name}</strong><p>{plan.detail}</p><b>{plan.price}</b>
                </div>
              ))}
            </div>
            <a className="price-cta" href={APP_STORE} rel="noreferrer" target="_blank">View in the App Store <ArrowUpRight /></a>
          </div>
        </section>

        <section className="faq-section">
          <header>
            <p className="instrument-label"><span>07</span> Operator notes</p>
            <h2>Before connecting.</h2>
          </header>
          <div className="faq-manual">
            {faqs.map((faq, index) => (
              <details key={faq.question}>
                <summary><span>{String(index + 1).padStart(2, "0")}</span><strong>{faq.question}</strong><i aria-hidden="true">+</i></summary>
                <p>{faq.answer}</p>
              </details>
            ))}
          </div>
        </section>

        <section className="closing-section">
          <div className="closing-copy">
            <p>Verceltics 2.0 / iPhone + iPad / 27 direct connections</p>
            <h2>Production called.<br />You can answer from here.</h2>
          </div>
          <a className="closing-control" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
          <span aria-hidden="true" className="closing-lamp"><i /></span>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
