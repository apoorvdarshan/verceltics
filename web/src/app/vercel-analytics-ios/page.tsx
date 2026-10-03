import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { ArrowUpRight } from "@/components/arrow-up-right";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

const SITE_URL = "https://verceltics.com";
const PAGE_URL = `${SITE_URL}/vercel-analytics-ios`;
const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";

export const metadata: Metadata = {
  title: "Vercel Analytics iOS App for iPhone & iPad",
  description:
    "View Vercel Web Analytics, visitors, page views and deployments on iPhone or iPad with Verceltics. Learn setup, token requirements and plan limits.",
  alternates: { canonical: PAGE_URL },
  openGraph: {
    type: "website",
    siteName: "Verceltics",
    title: "Vercel Analytics iOS App for iPhone & iPad — Verceltics",
    description: "Visitors, page views, traffic, projects and deployments in a native iOS workspace with direct Vercel API requests.",
    url: PAGE_URL,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics Vercel Analytics dashboard on iPhone and iPad" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Vercel Analytics iOS App for iPhone & iPad — Verceltics",
    description: "A private, open-source native iOS workspace for Vercel Web Analytics, projects and deployments.",
    images: ["/og-verceltics.png"],
  },
};

const questions = [
  {
    question: "Can I view Vercel Web Analytics on iPhone and iPad?",
    answer: "Yes. Verceltics displays supported Vercel Web Analytics reports alongside projects and deployments in its native iPhone and iPad interface.",
  },
  {
    question: "Does Verceltics send my Vercel token to its own server?",
    answer: "No. The token is stored in the device-only iOS Keychain, and Vercel requests go directly from your device to Vercel's official HTTPS API.",
  },
  {
    question: "Is Verceltics an official Vercel app?",
    answer: "No. Verceltics is an independent, open-source app and is not affiliated with, endorsed by or sponsored by Vercel.",
  },
  {
    question: "What is Vercel Web Analytics?",
    answer: "Vercel Web Analytics reports visitors, page views and supported custom events for a website deployed on Vercel. Enable it for the project and install its tracking integration on your website before expecting traffic reports. Connecting Verceltics does not add tracking code to your site.",
  },
  {
    question: "Why is my Vercel Analytics dashboard empty?",
    answer: "Check that Web Analytics is enabled, its tracking integration has been deployed, the project has received traffic, and your token can access the selected project or team. Also check the date range and environment. An empty report can reflect missing data or permissions; Verceltics cannot create historical traffic that Vercel did not collect.",
  },
  {
    question: "Does Verceltics require a subscription?",
    answer: "Connecting accounts and browsing project lists is available without a purchase. Opening project details and analytics requires Verceltics Pro through a monthly, yearly or lifetime purchase. Vercel’s own plan controls reporting availability, retention and advanced dimensions separately; check both services before choosing a plan.",
  },
  {
    question: "Can I use the Vercel dashboard in Safari on iPhone?",
    answer: "You can open Vercel’s web dashboard in Safari. Verceltics provides a separate native SwiftUI workspace with saved account switching, project context and supported analytics reports. It is an independent client, and some provider settings remain available only through Vercel’s own dashboard.",
  },
] as const;

const pageJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Verceltics", item: `${SITE_URL}/` },
        { "@type": "ListItem", position: 2, name: "Integrations", item: `${SITE_URL}/integrations` },
        { "@type": "ListItem", position: 3, name: "Vercel Analytics for iOS", item: PAGE_URL },
      ],
    },
    {
      "@type": "WebPage",
      "@id": `${PAGE_URL}#webpage`,
      url: PAGE_URL,
      name: "Vercel Analytics iOS App for iPhone & iPad",
      description: "Setup, supported reports, API resources and connection limits for viewing Vercel Analytics with Verceltics.",
      isPartOf: { "@id": `${SITE_URL}/#website` },
      about: { "@id": `${SITE_URL}/#app` },
      dateModified: "2026-10-03",
    },
  ],
};

export default function VercelAnalyticsIOSPage() {
  return (
    <div className="site-shell">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(pageJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main className="product-main" id="main-content">
        <nav aria-label="Breadcrumb" className="product-breadcrumbs">
          <Link href="/">Verceltics</Link><span aria-hidden="true">/</span>
          <Link href="/integrations">Integrations</Link><span aria-hidden="true">/</span>
          <span aria-current="page">Vercel Analytics for iOS</span>
        </nav>
        <header className="product-hero">
          <div className="product-hero-copy">
            <p className="instrument-label"><span>VCL</span> Native iOS workspace</p>
            <h1>Vercel Analytics, in a native iOS app.</h1>
            <p>View visitors, page views, traffic breakdowns and deployments on iPhone and iPad with Verceltics. Connect your Vercel account and keep project context close while checking your website.</p>
            <div className="discovery-actions">
              <a className="primary-control" href={APP_STORE} rel="noreferrer" target="_blank">Download on the App Store <ArrowUpRight /></a>
              <Link className="text-control" href="/integrations#vercel">See the Vercel integration <span aria-hidden="true">→</span></Link>
            </div>
            <p className="independence-note">Independent and open source. Not affiliated with or endorsed by Vercel.</p>
          </div>
          <figure className="product-screen-console">
            <div className="product-screen-head"><span><i /> Direct API</span><strong>Hosting connections</strong><span>iOS 18+</span></div>
            <div className="product-screen"><Image alt="Connect Vercel for projects, deployments and Web Analytics in the Verceltics iPhone app" fill priority sizes="(max-width: 780px) 82vw, 420px" src="/screens/ios/hosting.webp" /></div>
            <figcaption>Hosting selector · choose Connect Vercel</figcaption>
          </figure>
        </header>

        <section className="product-capabilities">
          <header>
            <p className="instrument-label"><span>01</span> What you can check</p>
            <h2>Analytics and deployment context, together on your device.</h2>
          </header>
          <div className="capability-grid">
            <article><span>01</span><h3>Web Analytics</h3><p>Inspect visitors, page views and supported breakdowns by page, referrer, country, device and browser. History and advanced dimensions depend on your Vercel plan.</p></article>
            <article><span>02</span><h3>Projects</h3><p>Browse connected projects and open project-specific details without losing the active account context.</p></article>
            <article><span>03</span><h3>Deployments</h3><p>Check deployment status, environment, timing and related project information from iPhone or iPad.</p></article>
            <article><span>04</span><h3>Multiple accounts</h3><p>Keep saved Vercel connections separate and switch between them inside the native workspace.</p></article>
          </div>
        </section>

        <section aria-labelledby="vercel-setup" className="product-guide" id="setup">
          <header><p className="instrument-label"><span>02</span> Setup guide</p><h2 id="vercel-setup">How to view Vercel Analytics on iPhone.</h2></header>
          <ol className="product-steps">
            <li><h3>Prepare your Vercel project</h3><p>Enable Web Analytics and deploy its tracking integration on your website. Follow <a href="https://vercel.com/docs/analytics/quickstart" rel="noreferrer" target="_blank">Vercel&apos;s Web Analytics setup guide</a>. Reports need real traffic; installing this iOS app does not enable website tracking.</p></li>
            <li><h3>Connect your account</h3><p>Create a token in <a href="https://vercel.com/account/tokens" rel="noreferrer" target="_blank">Vercel account settings</a> with access to the project or team you want to check. In Verceltics, choose Hosting, then Connect Vercel, and provide that token. Credentials stay in the device-only iOS Keychain.</p></li>
            <li><h3>Open the project and select a range</h3><p>Browse your connected projects and open the one you want to inspect. Project details and analytics require Verceltics Pro. Choose a date range and environment; available history, events and advanced breakdowns also depend on your Vercel plan and project configuration.</p></li>
          </ol>
        </section>

        <section aria-labelledby="vercel-api" className="product-guide" id="api">
          <header><p className="instrument-label"><span>03</span> Developer reference</p><h2 id="vercel-api">Does Vercel have a Web Analytics API?</h2></header>
          <div className="product-guide-copy">
            <p>Vercel provides a public Web Analytics API for querying visitor, page-view and custom-event data. Its count and aggregate endpoints support custom reporting integrations, with project and team authorization. Read <a href="https://vercel.com/docs/analytics/web-analytics-api" rel="noreferrer" target="_blank">Vercel&apos;s official Web Analytics API documentation</a> for authentication, filters and response formats.</p>
            <p>Verceltics provides a ready-to-use native client for supported reports. It does not host an analytics API or proxy your Vercel token. The <a href="https://github.com/apoorvdarshan/verceltics/blob/main/ios/verceltics/Network/VercelAPI.swift" rel="noreferrer" target="_blank">open-source Vercel client</a> documents the requests used by the current app. For the full provider catalog, explore <Link href="/integrations">hosting, domain and site-service integrations</Link>.</p>
          </div>
        </section>

        <section className="direct-architecture">
          <div>
            <p className="instrument-label instrument-label--light"><span>04</span> Private connection</p>
            <h2>Your Vercel token stays on your device.</h2>
            <p>Verceltics stores the credential with device-only, when-unlocked iOS Keychain protection. API requests go directly to Vercel&apos;s official HTTPS API; Verceltics does not run a credential or provider-data proxy.</p>
            <Link href="/privacy">Read the full privacy architecture <span aria-hidden="true">→</span></Link>
          </div>
          <dl>
            <div><dt>Credential storage</dt><dd>iOS Keychain</dd></div>
            <div><dt>Request path</dt><dd>Device → Vercel API</dd></div>
            <div><dt>Verceltics proxy</dt><dd>None</dd></div>
            <div><dt>Source</dt><dd>Open under MIT</dd></div>
          </dl>
        </section>

        <section className="product-faq">
          <header><p className="instrument-label"><span>05</span> Vercel connection notes</p><h2>Vercel Analytics questions.</h2></header>
          <div className="faq-manual">
            {questions.map((entry, index) => (
              <details key={entry.question}>
                <summary><span>{String(index + 1).padStart(2, "0")}</span><strong>{entry.question}</strong><i aria-hidden="true">+</i></summary>
                <p>{entry.answer}</p>
              </details>
            ))}
          </div>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
