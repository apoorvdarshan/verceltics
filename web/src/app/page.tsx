import type { Metadata } from "next";
import Image from "next/image";
import type { ReactNode } from "react";

import { ProviderDirectory } from "@/components/provider-directory";
import { ArrowUpRight } from "@/components/arrow-up-right";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

const SITE_URL = "https://verceltics.com";
const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export const metadata: Metadata = { alternates: { canonical: SITE_URL } };

const checks = [
  ["Review a deploy", "Status, environment, release, and logs."],
  ["Inspect DNS", "Zones, records, nameservers, and changes."],
  ["Check a domain", "Expiry, renewal, privacy, and forwarding."],
  ["Read traffic", "Visitors, requests, cache, threats, and HTTPS."],
  ["Check discovery", "Clicks, indexing, sitemaps, and inspection."],
  ["Confirm uptime", "Monitor state, response time, and availability."],
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
    `${SITE_URL}/screens/ios/cloudflare.png`,
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

function SectionLabel({ route, children }: { route?: "hosting" | "registrars" | "sites"; children: ReactNode }) {
  return <p className={route ? `section-label section-label--${route}` : "section-label"}><i />{children}</p>;
}

export default function Home() {
  return (
    <div className="site-canvas">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(applicationJsonLd) }} type="application/ld+json" />
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(faqJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main id="main-content">
        <section className="field-hero" id="overview">
          <div className="field-width hero-intro">
            <div className="hero-heading">
              <SectionLabel>Verceltics / native infrastructure</SectionLabel>
              <h1>Leave the<br />laptop closed.</h1>
            </div>
            <div className="hero-summary">
              <p>Review a deploy, check DNS, read traffic, or confirm uptime from a native iPhone and iPad workspace. Providers stay separate. Credentials stay on your device.</p>
              <div className="hero-actions">
                <a className="action action--dark" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
                <a className="text-action" href="#workspaces">See the workspaces <span aria-hidden="true">↓</span></a>
              </div>
              <p className="hero-proof">27 direct connections <i /> iOS 18+ <i /> Open source</p>
            </div>
          </div>

          <div className="field-width product-plate">
            <div className="plate-heading">
              <div><SectionLabel route="hosting">Hosting / Cloudflare</SectionLabel><p>Traffic, cache, threats, DNS, Pages, Workers, and more.</p></div>
              <span>Real app screens</span>
            </div>
            <div className="plate-gallery">
              <figure className="plate-ipad">
                <picture>
                  <source media="(min-width: 701px)" srcSet="/screens/ipad/cloudflare.png" />
                  <img alt="Cloudflare analytics workspace in Verceltics on iPad" fetchPriority="high" src="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=" />
                </picture>
              </figure>
              <figure className="plate-phone">
                <Image alt="Cloudflare dashboard in Verceltics on iPhone" fill loading="eager" sizes="(max-width: 900px) 78vw, 280px" src="/screens/ios/cloudflare.png" />
              </figure>
            </div>
          </div>

          <nav aria-label="Verceltics workspaces" className="field-width route-manifest">
            <a className="manifest-route manifest-route--hosting" href="#hosting"><i /><span>Hosting</span><strong>10</strong></a>
            <a className="manifest-route manifest-route--registrars" href="#registrars"><i /><span>Registrars</span><strong>8</strong></a>
            <a className="manifest-route manifest-route--sites" href="#sites"><i /><span>Sites</span><strong>9</strong></a>
          </nav>
        </section>

        <section className="workspace-field" id="workspaces">
          <header className="field-width field-heading">
            <SectionLabel>One app / providers stay providers</SectionLabel>
            <h2>Three workspaces.<br />No blended dashboard.</h2>
            <p>Move between the jobs you actually perform. Each connection keeps its own capabilities, controls, and account context.</p>
          </header>

          <article className="workspace-chapter workspace-chapter--hosting" id="hosting">
            <div className="chapter-route" aria-hidden="true"><i /></div>
            <div className="field-width chapter-grid">
              <div className="chapter-copy">
                <SectionLabel route="hosting">Hosting / 10 connections</SectionLabel>
                <h3>Ship, inspect, recover.</h3>
                <p>Projects, deployments, domains, logs, jobs, bandwidth, channels, releases, DNS, and provider-specific operations remain in one focused workspace.</p>
                <p className="provider-line">Vercel · Cloudflare · Netlify · Railway · Render · DigitalOcean · Heroku · Fly.io · Firebase Hosting · AWS Amplify</p>
              </div>
              <div className="chapter-gallery chapter-gallery--hosting">
                <figure className="chapter-ipad"><Image alt="Hosting providers in Verceltics on iPad" fill sizes="(max-width: 900px) 94vw, 760px" src="/screens/ipad/hosting.png" /></figure>
                <figure className="chapter-phone"><Image alt="Hosting provider connections in Verceltics on iPhone" fill sizes="(max-width: 900px) 74vw, 270px" src="/screens/ios/hosting.png" /></figure>
              </div>
            </div>
          </article>

          <article className="workspace-chapter workspace-chapter--registrars" id="registrars">
            <div className="chapter-route" aria-hidden="true"><i /></div>
            <div className="field-width chapter-grid chapter-grid--reverse">
              <div className="chapter-copy">
                <SectionLabel route="registrars">Registrars / 8 connections</SectionLabel>
                <h3>Domains stay with domains.</h3>
                <p>Check expiry and renewal state, inspect nameservers and DNS, manage contacts, privacy, forwarding, transfers, and certificates without mixing them into hosting.</p>
                <p className="provider-line">Name.com · Namecheap · Porkbun · Spaceship · Dynadot · NameSilo · Gandi · GoDaddy</p>
              </div>
              <div className="chapter-gallery chapter-gallery--registrars">
                <figure className="chapter-ipad"><Image alt="Registrar providers in Verceltics on iPad" fill sizes="(max-width: 900px) 94vw, 760px" src="/screens/ipad/registrars.png" /></figure>
                <figure className="chapter-phone"><Image alt="Registrar connections in Verceltics on iPhone" fill sizes="(max-width: 900px) 74vw, 270px" src="/screens/ios/registrars.png" /></figure>
              </div>
            </div>
          </article>

          <article className="workspace-chapter workspace-chapter--sites" id="sites">
            <div className="chapter-route" aria-hidden="true"><i /></div>
            <div className="field-width sites-intro">
              <div>
                <SectionLabel route="sites">Sites / 9 services</SectionLabel>
                <h3>Signals stay separate.</h3>
              </div>
              <p>Search Console is not Analytics. Speed is not uptime. Connect each service independently, then open the report built for that signal.</p>
            </div>
            <div className="field-width sites-gallery">
              <figure><span className="site-phone-frame"><Image alt="Site service connections in Verceltics on iPhone" fill sizes="(max-width: 700px) 78vw, 280px" src="/screens/ios/services.png" /></span><figcaption>Connect a service</figcaption></figure>
              <figure><span className="site-phone-frame"><Image alt="Google Search Console dashboard in Verceltics on iPhone" fill sizes="(max-width: 700px) 78vw, 280px" src="/screens/ios/search.png" /></span><figcaption>Read search performance</figcaption></figure>
              <figure><span className="site-phone-frame"><Image alt="Google Analytics dashboard in Verceltics on iPhone" fill sizes="(max-width: 700px) 78vw, 280px" src="/screens/ios/analytics.png" /></span><figcaption>Read traffic independently</figcaption></figure>
              <figure className="sites-ipad"><Image alt="Site services in Verceltics on iPad" fill sizes="(max-width: 900px) 94vw, 980px" src="/screens/ipad/sites.png" /></figure>
            </div>
          </article>
        </section>

        <section className="field-section quick-check">
          <div className="field-width quick-grid">
            <div className="quick-heading">
              <SectionLabel>The five-minute production check</SectionLabel>
              <h2>A quick check.<br />Not another cockpit.</h2>
              <p>Verceltics is shaped around the work developers do between meetings, on a commute, or whenever opening a laptop is too much ceremony.</p>
            </div>
            <ol className="quick-list">
              {checks.map(([title, detail], index) => (
                <li key={title}><span>{String(index + 1).padStart(2, "0")}</span><strong>{title}</strong><p>{detail}</p></li>
              ))}
            </ol>
          </div>
        </section>

        <section className="ipad-proof">
          <div className="field-width ipad-heading">
            <div><SectionLabel>Native on every screen</SectionLabel><h2>Made for iPad.<br />Not merely enlarged.</h2></div>
            <p>A persistent sidebar, adaptive metric grids, wider detail surfaces, and full-width charts turn the same connections into a real regular-width workspace.</p>
          </div>
          <figure className="field-width ipad-figure">
            <Image alt="Cloudflare analytics dashboard in Verceltics on iPad" fill sizes="(max-width: 900px) 96vw, 1320px" src="/screens/ipad/cloudflare.png" />
          </figure>
          <div className="field-width ipad-notes">
            <span><strong>Cached first</strong>Recent dashboards return immediately.</span>
            <span><strong>Fresh quietly</strong>Background refresh leaves the screen in place.</span>
            <span><strong>Writes guarded</strong>Changes and destructive requests ask first.</span>
          </div>
        </section>

        <section className="field-section provider-index" id="providers">
          <header className="field-width field-heading field-heading--split">
            <div><SectionLabel>27 direct connections</SectionLabel><h2>The stack you already chose.</h2></div>
            <p>Connect every account independently. Provider credentials stay scoped to that provider, and every dashboard keeps its own capabilities.</p>
          </header>
          <div className="field-width"><ProviderDirectory /></div>
        </section>

        <section className="field-section privacy-proof" id="privacy">
          <div className="field-width privacy-heading">
            <SectionLabel>Private by architecture</SectionLabel>
            <h2>Your data takes<br />the shortest route.</h2>
            <p>Credentials and OAuth tokens stay in the device-only iOS Keychain. Provider data travels between your device and the selected provider’s official HTTPS API.</p>
          </div>
          <div aria-label="Your device connects directly to official provider APIs without a Verceltics proxy" className="field-width direct-route">
            <div className="direct-endpoint"><Image alt="" height={58} src="/icon.png" width={58} /><span><strong>Your device</strong><small>Keychain + protected cache</small></span></div>
            <div className="direct-lines" aria-hidden="true"><i /><i /><i /></div>
            <div className="direct-endpoint direct-endpoint--api"><b>API</b><span><strong>Official provider</strong><small>Encrypted HTTPS request</small></span></div>
          </div>
          <div className="field-width no-proxy"><span>Verceltics server in the middle</span><strong>None.</strong><div><a href="/privacy">Read the privacy policy <ArrowUpRight /></a><a href={GITHUB} rel="noreferrer" target="_blank">Audit the source <ArrowUpRight /></a></div></div>
        </section>

        <section className="field-section purchase-section" id="pricing">
          <header className="field-width field-heading field-heading--split">
            <div><SectionLabel>Verceltics Pro</SectionLabel><h2>Full access.<br />Three ways.</h2></div>
            <p>Every paid option unlocks all provider workspaces on iPhone and iPad. Or build the MIT-licensed source for personal use.</p>
          </header>
          <div className="field-width purchase-table">
            <div className="purchase-table-head"><Image alt="" height={64} src="/icon.png" width={64} /><div><strong>Verceltics Pro</strong><span>All 27 integrations · iPhone + iPad</span></div></div>
            <div className="purchase-rows">
              {plans.map((plan) => <div className="purchase-row" key={plan.name}><strong>{plan.name}</strong><span>{plan.detail}</span><b>{plan.price}</b></div>)}
            </div>
            <div className="purchase-footer"><p>Prices shown in USD. Local App Store pricing and tax may vary.</p><a className="action action--light" href={APP_STORE} rel="noreferrer" target="_blank">View in the App Store <ArrowUpRight /></a></div>
          </div>
        </section>

        <section className="field-section faq-section">
          <header className="field-width faq-heading"><SectionLabel>Before connecting</SectionLabel><h2>Questions, answered.</h2></header>
          <div className="field-width faq-list">
            {faqs.map((faq) => <details key={faq.question}><summary>{faq.question}<span aria-hidden="true">+</span></summary><p>{faq.answer}</p></details>)}
          </div>
        </section>

        <section className="field-width closing-cta">
          <Image alt="" height={72} src="/icon.png" width={72} />
          <div><SectionLabel>Verceltics 2.0</SectionLabel><h2>Production, close at hand.</h2><p>Private, native, open source, and ready for iPhone and iPad.</p></div>
          <a className="action action--dark" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
