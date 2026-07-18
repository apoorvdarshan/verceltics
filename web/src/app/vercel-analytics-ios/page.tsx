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
  title: "Vercel Analytics for iPhone & iPad",
  description:
    "Check Vercel Web Analytics, projects and deployments from a private, open-source native iPhone and iPad app. Tokens stay in the iOS Keychain.",
  alternates: { canonical: PAGE_URL },
  openGraph: {
    type: "website",
    siteName: "Verceltics",
    title: "Vercel Analytics for iPhone & iPad — Verceltics",
    description: "Visitors, page views, traffic, projects and deployments in a native iOS workspace with direct Vercel API requests.",
    url: PAGE_URL,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics Vercel Analytics dashboard on iPhone and iPad" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Vercel Analytics for iPhone & iPad — Verceltics",
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
] as const;

const pageJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Verceltics", item: SITE_URL },
        { "@type": "ListItem", position: 2, name: "Integrations", item: `${SITE_URL}/integrations` },
        { "@type": "ListItem", position: 3, name: "Vercel Analytics for iOS", item: PAGE_URL },
      ],
    },
    {
      "@type": "FAQPage",
      mainEntity: questions.map((entry) => ({
        "@type": "Question",
        name: entry.question,
        acceptedAnswer: { "@type": "Answer", text: entry.answer },
      })),
    },
  ],
};

export default function VercelAnalyticsIOSPage() {
  return (
    <div className="site-shell">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(pageJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main className="product-main" id="main-content">
        <header className="product-hero">
          <div className="product-hero-copy">
            <p className="instrument-label"><span>VCL</span> Native iOS workspace</p>
            <h1>Vercel Analytics on iPhone and iPad.</h1>
            <p>Check supported Vercel Web Analytics reports, projects and deployments from a private native workspace—without opening the desktop dashboard.</p>
            <div className="discovery-actions">
              <a className="primary-control" href={APP_STORE} rel="noreferrer" target="_blank">Download on the App Store <ArrowUpRight /></a>
              <Link className="text-control" href="/integrations#vercel">See the Vercel integration <span aria-hidden="true">→</span></Link>
            </div>
            <p className="independence-note">Independent and open source. Not affiliated with or endorsed by Vercel.</p>
          </div>
          <figure className="product-screen-console">
            <div className="product-screen-head"><span><i /> Direct API</span><strong>Vercel workspace</strong><span>iOS 18+</span></div>
            <div className="product-screen"><Image alt="Verceltics traffic analytics dashboard on iPhone" fill priority sizes="(max-width: 780px) 82vw, 420px" src="/screens/ios/analytics.webp" /></div>
            <figcaption>Traffic analytics · provider-bound context</figcaption>
          </figure>
        </header>

        <section className="product-capabilities">
          <header>
            <p className="instrument-label"><span>01</span> What you can check</p>
            <h2>Analytics and deployment context, together on your device.</h2>
          </header>
          <div className="capability-grid">
            <article><span>01</span><h3>Web Analytics</h3><p>Inspect supported visitor, page-view and traffic breakdowns from your connected Vercel account.</p></article>
            <article><span>02</span><h3>Projects</h3><p>Browse connected projects and open project-specific details without losing the active account context.</p></article>
            <article><span>03</span><h3>Deployments</h3><p>Check deployment status, environment, timing and related project information from iPhone or iPad.</p></article>
            <article><span>04</span><h3>Multiple accounts</h3><p>Keep saved Vercel connections separate and switch between them inside the native workspace.</p></article>
          </div>
        </section>

        <section className="direct-architecture">
          <div>
            <p className="instrument-label instrument-label--light"><span>02</span> Private connection</p>
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
          <header><p className="instrument-label"><span>03</span> Vercel connection notes</p><h2>Before you connect.</h2></header>
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
