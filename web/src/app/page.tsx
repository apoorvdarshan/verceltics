import type { Metadata } from "next";
import Image from "next/image";

import { ProviderDirectory } from "@/components/provider-directory";
import { ArrowUpRight, SiteFooter, SiteHeader } from "@/components/site-chrome";
import { WorkspaceSwitcher } from "@/components/workspace-switcher";

const SITE_URL = "https://verceltics.com";
const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export const metadata: Metadata = { alternates: { canonical: SITE_URL } };

const providerRibbon = [
  { name: "Vercel", icon: "VercelMark.svg" },
  { name: "Cloudflare", icon: "CloudflareMark.svg" },
  { name: "Netlify", icon: "NetlifyMark.svg" },
  { name: "Namecheap", icon: "NamecheapMark.svg" },
  { name: "Firebase", icon: "FirebaseMark.svg" },
  { name: "Search Console", icon: "GoogleSearchConsoleMark.svg" },
  { name: "Google Analytics", icon: "GoogleAnalyticsMark.svg" },
  { name: "PageSpeed", icon: "PageSpeedMark.svg" },
] as const;

const workspaces = [
  {
    label: "Hosting",
    count: "10",
    accent: "blue",
    title: "Deployments stay with deployments.",
    body: "Projects, deploys, domains, logs, jobs, bandwidth, channels, and releases across the hosting platforms you use.",
    image: "/screens/ios/hosting.png",
    alt: "Verceltics hosting provider connection screen on iPhone",
  },
  {
    label: "Registrars",
    count: "8",
    accent: "white",
    title: "Domains stay with domains.",
    body: "Expiry, renewals, nameservers, DNS, contacts, privacy, forwarding, transfers, and certificates in a dedicated workspace.",
    image: "/screens/ios/registrars.png",
    alt: "Verceltics registrar connection screen on iPhone",
  },
  {
    label: "Sites",
    count: "9",
    accent: "violet",
    title: "Signals stay separate.",
    body: "Search Console is not Analytics. Speed is not uptime. Each service opens as its own provider-specific dashboard.",
    image: "/screens/ios/services.png",
    alt: "Verceltics site service connection screen on iPhone",
  },
] as const;

const checks = [
  ["01", "Review a deploy", "See status, environment, logs, and the latest release."],
  ["02", "Inspect DNS", "Find the zone, record, or nameserver that changed."],
  ["03", "Check a domain", "Confirm expiry, renewal state, privacy, and forwarding."],
  ["04", "Read traffic", "Spot changes in requests, visitors, cache, threats, and HTTPS."],
  ["05", "Check discovery", "Review clicks, indexing, sitemaps, and URL inspection."],
  ["06", "Confirm uptime", "See monitor state, response time, and availability."],
] as const;

const plans = [
  { name: "Monthly", price: "$4.99", detail: "per month", featured: false },
  { name: "Yearly", price: "$34.99", detail: "per year · 7-day trial", featured: true },
  { name: "Lifetime", price: "$59.99", detail: "one-time purchase", featured: false },
] as const;

const faqs = [
  {
    question: "What is Verceltics?",
    answer: "Verceltics is an independent native workspace for hosting platforms, domain registrars, and site-intelligence services on iPhone and iPad.",
  },
  {
    question: "Does Verceltics merge provider data?",
    answer: "No. Providers stay separate. Google Search Console and Google Analytics, for example, have separate connections and separate dashboards inside the Sites workspace.",
  },
  {
    question: "Where are credentials stored?",
    answer: "Credentials and OAuth tokens use device-only, when-unlocked iOS Keychain protection. Requests go directly to each provider’s official HTTPS API; Verceltics does not run a credential proxy.",
  },
  {
    question: "Does it work on iPad?",
    answer: "Yes. The universal app uses a sidebar, adaptive grids, wider tables, and full-width charts on regular-width iPad layouts.",
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
  screenshot: workspaces.map((workspace) => `${SITE_URL}${workspace.image}`),
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
    <div className="site-canvas">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(applicationJsonLd) }} type="application/ld+json" />
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(faqJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main id="main-content">
        <section className="hero page-width">
          <div className="hero-copy">
            <p className="eyebrow"><span /> Native ops for iPhone and iPad</p>
            <h1>Your stack,<br /><em>off the desk.</em></h1>
            <p className="hero-description">Check deploys, domains, DNS, traffic, search, speed, and uptime from one native app. Credentials stay in your Keychain; requests go straight to each provider.</p>
            <div className="hero-actions">
              <a className="primary-action" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
              <a className="secondary-action" href={GITHUB} rel="noreferrer" target="_blank">View source <ArrowUpRight /></a>
            </div>
            <p className="hero-trust">27 direct integrations <i /> Device-only Keychain <i /> No tracking</p>
          </div>
          <WorkspaceSwitcher />
        </section>

        <section aria-label="Selected supported providers" className="provider-ribbon">
          <div className="page-width provider-ribbon-inner">
            <p>Works with the stack you already have.</p>
            <ul>
              {providerRibbon.map((provider) => (
                <li key={provider.name} translate="no"><Image alt="" height={20} src={`/providers/${provider.icon}`} width={20} /><span>{provider.name}</span></li>
              ))}
            </ul>
          </div>
        </section>

        <section className="page-section page-width" id="workspaces">
          <header className="section-heading section-heading--wide">
            <p className="eyebrow"><span /> One app. Three clear workspaces.</p>
            <h2>Providers stay themselves.</h2>
            <p>Cloudflare is not Vercel. Search Console is not Analytics. Verceltics keeps every provider’s shape, then makes switching between them immediate.</p>
          </header>

          <div className="workspace-showcase">
            {workspaces.map((workspace) => (
              <article className={`workspace-story workspace-story--${workspace.accent}`} key={workspace.label}>
                <header><span>{workspace.count}</span><strong>{workspace.label}</strong></header>
                <h3>{workspace.title}</h3>
                <p>{workspace.body}</p>
                <div className="workspace-phone"><Image alt={workspace.alt} fill sizes="(max-width: 760px) 82vw, 310px" src={workspace.image} /></div>
              </article>
            ))}
          </div>
        </section>

        <section className="page-section page-width production-section">
          <div className="production-copy">
            <p className="eyebrow"><span /> The five-minute production check</p>
            <h2>Know what needs attention before you open your laptop.</h2>
            <p>Verceltics is built around the quick checks developers actually make between meetings, on a commute, or away from a desk.</p>
            <ol className="check-list">
              {checks.map(([number, title, description]) => (
                <li key={number}><span>{number}</span><div><strong>{title}</strong><p>{description}</p></div></li>
              ))}
            </ol>
          </div>

          <div className="production-visual">
            <div className="production-phone production-phone--analytics"><Image alt="Cloudflare analytics in Verceltics on iPhone" fill sizes="300px" src="/screens/ios/analytics.png" /></div>
            <div className="production-phone production-phone--search"><Image alt="Google Search Console in Verceltics on iPhone" fill sizes="250px" src="/screens/ios/search.png" /></div>
            <span className="production-note production-note--top">Traffic, cache, threats</span>
            <span className="production-note production-note--bottom">Search &amp; indexing</span>
          </div>
        </section>

        <section className="page-section native-section">
          <div className="page-width native-copy">
            <p className="eyebrow"><span /> Native on every screen</p>
            <h2>Not a web dashboard in a wrapper.</h2>
            <p>On iPad, Verceltics opens into a real operator workspace: persistent navigation, adaptive metric grids, full-width charts, and provider controls sized for regular width.</p>
            <ul>
              <li><strong>Cached first</strong><span>Recent dashboards return immediately.</span></li>
              <li><strong>Fresh quietly</strong><span>Background refresh updates the data without resetting the screen.</span></li>
              <li><strong>Writes guarded</strong><span>Detected changes, purchases, and destructive requests ask for confirmation.</span></li>
            </ul>
          </div>
          <div className="native-ipad-wrap">
            <div className="native-ipad">
              <div className="tablet-camera" />
              <Image alt="Cloudflare analytics workspace in Verceltics on iPad" fill sizes="(max-width: 900px) 94vw, 1180px" src="/screens/ipad/cloudflare.png" />
            </div>
          </div>
        </section>

        <section className="page-section page-width" id="providers">
          <header className="section-heading section-heading--split">
            <div><p className="eyebrow"><span /> 27 direct integrations</p><h2>Use the stack you already chose.</h2></div>
            <p>Connect each account independently. Provider credentials stay scoped to that provider and every dashboard keeps its own capabilities.</p>
          </header>
          <ProviderDirectory />
        </section>

        <section className="page-section page-width privacy-section" id="privacy">
          <div className="privacy-copy">
            <p className="eyebrow"><span /> Private by architecture</p>
            <h2>Your credentials don’t visit us.</h2>
            <p>Connected credentials and OAuth tokens stay in the device-only iOS Keychain. Provider data travels directly between your device and the selected provider’s official HTTPS API.</p>
            <div className="privacy-links"><a href="/privacy">Read the privacy policy <ArrowUpRight /></a><a href={GITHUB} rel="noreferrer" target="_blank">Audit the source <ArrowUpRight /></a></div>
          </div>
          <div className="privacy-route" aria-label="Data travels from your iPhone or iPad directly to the selected provider API">
            <div className="privacy-node"><Image alt="" height={52} src="/icon.png" width={52} /><span><strong>Your device</strong><small>Keychain + protected cache</small></span></div>
            <div className="privacy-route-line"><i /><i /><i /><span>Encrypted HTTPS</span></div>
            <div className="privacy-node privacy-node--api"><span aria-hidden="true">↗</span><span><strong>Official provider API</strong><small>Only the selected provider host</small></span></div>
            <div className="privacy-no-proxy"><b>×</b><span><strong>No Verceltics proxy</strong><small>No credential or provider-data server in between.</small></span></div>
          </div>
        </section>

        <section className="page-section page-width pricing-section" id="pricing">
          <header className="section-heading">
            <p className="eyebrow"><span /> Verceltics Pro</p>
            <h2>One app. Every integration.</h2>
            <p>Every paid option unlocks the same provider workspaces on iPhone and iPad. Or build the MIT-licensed source for personal use.</p>
          </header>
          <div className="purchase-sheet">
            <div className="purchase-sheet-head"><Image alt="" height={58} src="/icon.png" width={58} /><div><strong>Verceltics Pro</strong><span>All 27 integrations · iPhone + iPad</span></div></div>
            <div className="purchase-options">
              {plans.map((plan) => (
                <div className={plan.featured ? "purchase-option is-featured" : "purchase-option"} key={plan.name}>
                  <div><strong>{plan.name}</strong><span>{plan.detail}</span></div>
                  <b>{plan.price}</b>
                  {plan.featured ? <small>Best value</small> : null}
                </div>
              ))}
            </div>
            <a className="primary-action purchase-action" href={APP_STORE} rel="noreferrer" target="_blank">View in the App Store <ArrowUpRight /></a>
            <p>Prices shown in USD. Local App Store pricing and tax may vary.</p>
          </div>
        </section>

        <section className="page-section page-width faq-section">
          <header className="section-heading"><p className="eyebrow"><span /> Before connecting</p><h2>Questions, answered.</h2></header>
          <div className="faq-list">
            {faqs.map((faq) => <details key={faq.question}><summary>{faq.question}<span>+</span></summary><p>{faq.answer}</p></details>)}
          </div>
        </section>

        <section className="final-cta page-width">
          <Image alt="" height={70} src="/icon.png" width={70} />
          <div><p className="eyebrow"><span /> Verceltics 2.0</p><h2>Take your stack with you.</h2><p>Private, native, open source, and ready for iPhone and iPad.</p></div>
          <a className="primary-action" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
