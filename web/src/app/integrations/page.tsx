import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import type { CSSProperties } from "react";

import { ArrowUpRight } from "@/components/arrow-up-right";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { integrationCount, integrationGroups } from "@/data/integrations";

const SITE_URL = "https://verceltics.com";
const PAGE_URL = `${SITE_URL}/integrations`;
const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";

export const metadata: Metadata = {
  title: "27 Hosting, Domain & Analytics Integrations",
  description:
    "Explore 27 Verceltics integrations for hosting, domains, DNS, analytics, search, speed and uptime on iPhone and iPad.",
  alternates: { canonical: PAGE_URL },
  openGraph: {
    type: "website",
    siteName: "Verceltics",
    title: "27 Hosting, Domain & Analytics Integrations — Verceltics",
    description: "Connect 10 hosting platforms, 8 domain registrars and 9 site services in one private native iPhone and iPad workspace.",
    url: PAGE_URL,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics integrations on iPhone and iPad" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "27 Hosting, Domain & Analytics Integrations — Verceltics",
    description: "Hosting, domains, DNS, analytics, search, speed and uptime on iPhone and iPad.",
    images: ["/og-verceltics.png"],
  },
};

const breadcrumbJsonLd = {
  "@context": "https://schema.org",
  "@type": "BreadcrumbList",
  itemListElement: [
    { "@type": "ListItem", position: 1, name: "Verceltics", item: SITE_URL },
    { "@type": "ListItem", position: 2, name: "Integrations", item: PAGE_URL },
  ],
};

const itemListJsonLd = {
  "@context": "https://schema.org",
  "@type": "ItemList",
  name: "Verceltics integrations",
  numberOfItems: integrationCount,
  itemListElement: integrationGroups.flatMap((group) =>
    group.providers.map((provider) => ({
      "@type": "ListItem",
      position: integrationGroups
        .flatMap((entry) => entry.providers)
        .findIndex((entry) => entry.slug === provider.slug) + 1,
      name: provider.name,
      url: `${PAGE_URL}#${provider.slug}`,
    })),
  ),
};

export default function IntegrationsPage() {
  return (
    <div className="site-shell">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(breadcrumbJsonLd) }} type="application/ld+json" />
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(itemListJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main className="discovery-main" id="main-content">
        <header className="discovery-hero">
          <div className="discovery-hero-copy">
            <p className="instrument-label"><span>INT</span> Connection directory</p>
            <h1>Connect the web services you already use.</h1>
            <p>
              Verceltics supports 10 hosting platforms, 8 domain registrars and 9 site services. Each connection keeps its own credentials, API scope, dashboard and supported operations.
            </p>
            <div className="discovery-actions">
              <a className="primary-control" href={APP_STORE} rel="noreferrer" target="_blank">Get Verceltics <ArrowUpRight /></a>
              <Link className="text-control" href="/vercel-analytics-ios">Explore Vercel Analytics for iOS <span aria-hidden="true">→</span></Link>
            </div>
          </div>
          <dl className="connection-totals">
            {integrationGroups.map((group) => (
              <div key={group.id} style={{ "--group-accent": group.accent } as CSSProperties}>
                <dt>{group.label}</dt>
                <dd className="connection-count">{String(group.count).padStart(2, "0")}</dd>
                <dd className="connection-detail">{group.detail}</dd>
              </div>
            ))}
          </dl>
        </header>

        <nav aria-label="Integration categories" className="directory-index">
          <span>{integrationCount} direct connections</span>
          {integrationGroups.map((group) => <a href={`#${group.id}`} key={group.id}>{group.label} · {group.count}</a>)}
        </nav>

        <div className="integration-groups">
          {integrationGroups.map((group, groupIndex) => (
            <section
              className="integration-group"
              id={group.id}
              key={group.id}
              style={{ "--group-accent": group.accent } as CSSProperties}
            >
              <header className="integration-group-header">
                <div>
                  <p className="instrument-label"><span>0{groupIndex + 1}</span> {group.label}</p>
                  <h2>{group.heading}</h2>
                </div>
                <p>{group.description}</p>
              </header>
              <div className="integration-card-grid">
                {group.providers.map((provider, providerIndex) => (
                  <article className="integration-card" id={provider.slug} key={provider.slug}>
                    <header>
                      <span className="integration-card-index">{String(providerIndex + 1).padStart(2, "0")}</span>
                      <span className="integration-card-icon"><Image alt="" fill sizes="36px" src={`/providers/${provider.icon}`} /></span>
                    </header>
                    <h3 translate="no">{provider.name}</h3>
                    <p>{provider.summary}</p>
                    <dl>
                      <dt>Connect with</dt>
                      <dd>{provider.connection}</dd>
                    </dl>
                  </article>
                ))}
              </div>
            </section>
          ))}
        </div>

        <aside className="capability-note">
          <span>API reality check</span>
          <h2>Capabilities vary by provider.</h2>
          <p>Available data, write operations, rate limits and account requirements are controlled by each provider API and plan. Verceltics asks for confirmation before detected writes, purchases or destructive requests.</p>
          <div><Link href="/privacy">Read the privacy architecture <span aria-hidden="true">→</span></Link><a href={APP_STORE} rel="noreferrer" target="_blank">View on the App Store <ArrowUpRight /></a></div>
        </aside>
      </main>

      <SiteFooter />
    </div>
  );
}
