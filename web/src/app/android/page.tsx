import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { ArrowUpRight } from "@/components/arrow-up-right";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { integrationCount, integrationGroups } from "@/data/integrations";
import { ANDROID_BETA_CODE, ANDROID_BETA_GROUP, ANDROID_BETA_OPT_IN, APP_STORE_URL } from "@/lib/product";

const SITE_URL = "https://verceltics.com";
const PAGE_URL = `${SITE_URL}/android`;
const TITLE = "Vercel, Cloudflare and Domain App for Android (Beta)";
const DESCRIPTION =
  "Vercel, Cloudflare, Netlify, domain registrars and Search Console in one native Android app. Join the Google Play beta and get a free month of Pro.";

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: PAGE_URL },
  openGraph: {
    type: "website",
    siteName: "Verceltics",
    title: `${TITLE} | Verceltics`,
    description: DESCRIPTION,
    url: PAGE_URL,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics for Android: hosting, domains and site analytics" }],
  },
  twitter: {
    card: "summary_large_image",
    title: `${TITLE} | Verceltics`,
    description: DESCRIPTION,
    images: ["/og-verceltics.png"],
  },
};

const plans = [
  { code: "M", name: "Monthly", price: "$4.99", detail: "per month" },
  { code: "Y", name: "Yearly", price: "$34.99", detail: "per year · 7-day free trial for new subscribers" },
  { code: "∞", name: "Lifetime", price: "$59.99", detail: "one-time purchase" },
] as const;

const providerNames = (id: string) =>
  integrationGroups.find((group) => group.id === id)?.providers.map((provider) => provider.name).join(", ") ?? "";

const questions = [
  {
    question: "Is there a Vercel app for Android?",
    answer:
      "Verceltics is an independent native Android app for Vercel and 26 other services, currently in beta on Google Play. It shows Vercel projects, deployments and Web Analytics next to your other hosting platforms, domains and site services. It is not made by Vercel.",
  },
  {
    question: "How do I install Verceltics on Android?",
    answer:
      "Join the Verceltics Testers Google Group, then open the Google Play test with the same Google account, tap Become a tester and install Verceltics from Google Play. Keep it installed for 14 days so the app can launch publicly.",
  },
  {
    question: "Is Verceltics for Android free?",
    answer: `Connecting accounts, lists, search and refresh are free. Verceltics Pro unlocks details and provider tools for $4.99 a month, $34.99 a year or $59.99 once. Beta testers can redeem ${ANDROID_BETA_CODE} in Google Play for 30 days of Pro on the monthly plan, valid until December 31, 2026. It then renews at the regular price unless you cancel in Google Play.`,
  },
  {
    question: "Can I manage Cloudflare from my Android phone?",
    answer:
      "Yes. With a scoped Cloudflare API token, Verceltics shows zones, DNS records, Pages, Workers and analytics, and runs supported operations you confirm. Available data follows your token permissions and Cloudflare plan.",
  },
  {
    question: "Where are my API tokens stored on Android?",
    answer:
      "Tokens are encrypted with Android Keystore in app-private storage. Requests go straight from your phone to each provider's HTTPS API. Verceltics has no backend and never receives your tokens or provider data.",
  },
  {
    question: "Which Android versions are supported?",
    answer: "Verceltics for Android runs on Android 9 and later, on phones and tablets.",
  },
  {
    question: "Is it the same app as on iPhone?",
    answer:
      "It has the same 27 integrations and the same Pro plans, built natively in Kotlin and Jetpack Compose. Purchases are made separately through the App Store or Google Play.",
  },
] as const;

const pageJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Verceltics", item: `${SITE_URL}/` },
        { "@type": "ListItem", position: 2, name: "Android", item: PAGE_URL },
      ],
    },
    {
      "@type": "WebPage",
      "@id": `${PAGE_URL}#webpage`,
      url: PAGE_URL,
      name: TITLE,
      description: DESCRIPTION,
      isPartOf: { "@id": `${SITE_URL}/#website` },
      about: { "@id": `${PAGE_URL}#app` },
      dateModified: "2026-10-10",
    },
    {
      "@type": "MobileApplication",
      "@id": `${PAGE_URL}#app`,
      name: "Verceltics for Android",
      operatingSystem: "Android 9 or later",
      applicationCategory: "DeveloperApplication",
      applicationSubCategory: "Infrastructure monitoring and management",
      softwareVersion: "1.0 (beta)",
      description:
        "A private, open-source native Android app for hosting platforms, domain registrars, DNS, website analytics, search performance, speed and uptime. 27 integrations, beta on Google Play.",
      url: PAGE_URL,
      downloadUrl: ANDROID_BETA_OPT_IN,
      image: `${SITE_URL}/og-verceltics.png`,
      screenshot: [
        `${SITE_URL}/screens/android/analytics.webp`,
        `${SITE_URL}/screens/android/hosting.webp`,
        `${SITE_URL}/screens/android/registrars.webp`,
      ],
      publisher: { "@id": `${SITE_URL}/#organization` },
      offers: plans.map((plan) => ({ "@type": "Offer", price: plan.price.replace("$", ""), priceCurrency: "USD", description: `${plan.name} access to Verceltics Pro` })),
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

export default function AndroidPage() {
  return (
    <div className="site-shell">
      <script dangerouslySetInnerHTML={{ __html: JSON.stringify(pageJsonLd) }} type="application/ld+json" />
      <SiteHeader />

      <main className="product-main" id="main-content">
        <nav aria-label="Breadcrumb" className="product-breadcrumbs">
          <Link href="/">Verceltics</Link><span aria-hidden="true">/</span>
          <span aria-current="page">Android</span>
        </nav>
        <header className="product-hero">
          <div className="product-hero-copy">
            <p className="instrument-label"><span>AND</span> Android beta</p>
            <h1>Vercel, Cloudflare and domains on Android.</h1>
            <p>
              Verceltics brings {integrationCount} hosting, domain and site services into one native Android app: Vercel projects and analytics, Cloudflare DNS and Workers, your registrars, Search Console, PageSpeed and uptime. It is in beta on Google Play, and every tester helps it launch.
            </p>
            <div className="discovery-actions">
              <a className="primary-control" href={ANDROID_BETA_GROUP} rel="noreferrer" target="_blank">Join the Android beta <ArrowUpRight /></a>
              <a className="text-control" href="#join">How to join <span aria-hidden="true">→</span></a>
            </div>
            <p className="independence-note">Independent and open source. Not affiliated with or endorsed by any supported provider.</p>
          </div>
          <figure className="product-screen-console">
            <div className="product-screen-head"><span><i /> Direct API</span><strong>Project analytics</strong><span>Android 9+</span></div>
            <div className="product-screen product-screen--android"><Image alt="Vercel project analytics in the Verceltics Android app with sample data" fill priority sizes="(max-width: 780px) 82vw, 420px" src="/screens/android/analytics.webp" /></div>
            <figcaption>Vercel project analytics · sample data</figcaption>
          </figure>
        </header>

        <section aria-labelledby="android-join" className="product-guide" id="join">
          <header>
            <p className="instrument-label"><span>01</span> Join the beta</p>
            <h2 id="android-join">Three steps to install Verceltics on Android.</h2>
          </header>
          <div className="beta-console">
            <div className="price-console-head"><span>Android beta</span><span>Google Play closed test</span><span>Testers wanted</span></div>
            <ol className="beta-steps">
              <li>
                <span>1</span>
                <strong>Join the tester group</strong>
                <p>Anyone can join the Verceltics Testers Google Group.</p>
                <a href={ANDROID_BETA_GROUP} rel="noreferrer" target="_blank">groups.google.com/g/verceltics-testers <ArrowUpRight /></a>
              </li>
              <li>
                <span>2</span>
                <strong>Become a tester</strong>
                <p>Open the Google Play test with the same Google account, tap Become a tester, install Verceltics, and keep it for 14 days.</p>
                <a href={ANDROID_BETA_OPT_IN} rel="noreferrer" target="_blank">Open the Google Play test <ArrowUpRight /></a>
              </li>
              <li>
                <span>3</span>
                <strong>Get a month of Pro</strong>
                <p>Redeem <code>{ANDROID_BETA_CODE}</code> in Google Play for 30 days of Pro on the monthly plan. It then renews at $4.99 a month unless you cancel. Valid until December 31, 2026.</p>
              </li>
            </ol>
            <div className="beta-actions">
              <a className="price-cta" href={ANDROID_BETA_GROUP} rel="noreferrer" target="_blank">Join the tester group <ArrowUpRight /></a>
              <a className="price-cta price-cta--light" href={ANDROID_BETA_OPT_IN} rel="noreferrer" target="_blank">Open the Google Play test <ArrowUpRight /></a>
            </div>
          </div>
          <div className="product-guide-copy">
            <p>Google asks new apps to run a closed test with 12 testers for 14 days before a public launch on Google Play. Staying opted in for those two weeks is the most useful thing a tester can do. Uninstalling is fine; leaving the test is what removes you.</p>
          </div>
        </section>

        <section className="product-capabilities">
          <header>
            <p className="instrument-label"><span>02</span> What you can check</p>
            <h2>Your whole stack on one Android screen.</h2>
          </header>
          <div className="capability-grid">
            <article><span>01</span><h3>Hosting</h3><p>{providerNames("hosting")}: projects, deployments, logs, DNS and releases.</p></article>
            <article><span>02</span><h3>Domains</h3><p>{providerNames("registrars")}: expiry, auto renew, locks, nameservers and DNS.</p></article>
            <article><span>03</span><h3>Site services</h3><p>{providerNames("sites")}: search, traffic, speed and uptime.</p></article>
            <article><span>04</span><h3>Sample data</h3><p>Tap Explore sample data on first launch to try every screen, including Pro, with fictional accounts before connecting your own.</p></article>
          </div>
        </section>

        <section aria-labelledby="android-pricing" className="product-guide" id="pricing">
          <header>
            <p className="instrument-label"><span>03</span> Pricing</p>
            <h2 id="android-pricing">Free to connect. Pro unlocks the details.</h2>
          </header>
          <div className="price-console">
            <div className="price-console-head"><span>Verceltics Pro</span><span>Google Play Billing</span><span>Details + tools</span></div>
            <div className="price-options">
              {plans.map((plan) => (
                <div className="price-row" key={plan.name}>
                  <span>{plan.code}</span><strong>{plan.name}</strong><p>{plan.detail}</p><b>{plan.price}</b>
                </div>
              ))}
            </div>
          </div>
          <div className="product-guide-copy">
            <p>US reference prices. Google Play shows the current local price and trial eligibility at checkout, and that price applies. Connections, lists, search, refresh and account switching stay free.</p>
          </div>
        </section>

        <section className="direct-architecture">
          <div>
            <p className="instrument-label instrument-label--light"><span>04</span> Private connection</p>
            <h2>Your tokens stay on your phone.</h2>
            <p>Verceltics encrypts every token with Android Keystore in app-private storage. Requests go straight to each provider&apos;s official HTTPS API. There is no Verceltics server in between.</p>
            <Link href="/privacy">Read the full privacy architecture <span aria-hidden="true">→</span></Link>
          </div>
          <dl>
            <div><dt>Credential storage</dt><dd>Android Keystore</dd></div>
            <div><dt>Request path</dt><dd>Phone → provider API</dd></div>
            <div><dt>Verceltics proxy</dt><dd>None</dd></div>
            <div><dt>Source</dt><dd>Open under MIT</dd></div>
          </dl>
        </section>

        <section className="product-faq">
          <header>
            <p className="instrument-label"><span>05</span> Android notes</p>
            <h2>Verceltics for Android questions.</h2>
            <div className="product-guide-copy">
              <p>On iPhone or iPad? <a href={APP_STORE_URL} rel="noreferrer" target="_blank">Download Verceltics on the App Store</a>. Using Vercel? See <Link href="/vercel-analytics-ios">Vercel Analytics on iPhone and iPad</Link> and all <Link href="/integrations">{integrationCount} integrations</Link>.</p>
            </div>
          </header>
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
