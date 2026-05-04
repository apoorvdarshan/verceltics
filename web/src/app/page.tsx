import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { ScrollReveal } from "@/components/scroll-reveal";

export const metadata: Metadata = {
  alternates: { canonical: "https://verceltics.com" },
};

const APPSTORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";
const PRODUCTHUNT = "https://www.producthunt.com/products/verceltics";

const ticker = [
  "v1.1.2 — multi-account",
  "SwiftUI",
  "Swift Charts",
  "Private by design",
  "Open source",
  "iOS Keychain",
  "No app tracking",
  "No servers",
  "iPad ready",
] as const;

const features = [
  {
    label: "Dashboard",
    title: "Your numbers, one glance.",
    body: "Visitors, page views, bounce rate, and trends in a native layout that stays legible on the smallest screen. Drag the chart to inspect any day — peak indicator and dashed average line travel with the data.",
    bullets: ["Period comparisons", "Swift Charts native", "Drag-to-inspect with haptics"],
    image: "/analytics.png",
    alt: "Verceltics analytics dashboard with chart",
  },
  {
    label: "Breakdowns",
    title: "Where the traffic moved.",
    body: "Pages, routes, hostnames, referrers, UTM, countries, devices, browsers, OS, events, flags, query params — twelve dimensions, all ranked by visitors with blue gradient bars.",
    bullets: ["Twelve breakdowns surfaced", "Country flag emoji", "Ranked by visitors"],
    image: "/referrers.png",
    alt: "Pages, routes, hostnames, and referrers ranked",
  },
  {
    label: "Projects",
    title: "Every project, one tap.",
    body: "All your Vercel projects with favicons, framework, last commit, and a pulsing green dot when something deployed in the last thirty minutes. Switch Vercel accounts from the toolbar, then search by name, domain, or framework.",
    bullets: ["Multi-account switcher", "Live deploy indicator", "Search & filter"],
    image: "/projects.png",
    alt: "Verceltics project list with favicons and live deploy dots",
  },
  {
    label: "Audience",
    title: "Who's visiting, from where.",
    body: "Country, device, browser, OS — every audience axis from the same data Vercel renders on the web. Read it all without rotating your phone.",
    bullets: ["Country with flag emoji", "Device & browser splits", "OS distribution"],
    image: "/devices.png",
    alt: "Country, device, and browser breakdowns",
  },
  {
    label: "Depth",
    title: "Down to the query parameter.",
    body: "Operating systems. Custom events. Feature flags. UTM source. Query strings. Everything Vercel tracks in the web dashboard, surfaced in the same legible cards on iPhone.",
    bullets: ["Events & flags", "UTM source ranking", "Query params"],
    image: "/breakdowns.png",
    alt: "OS, events, flags, and query parameter breakdowns",
  },
] as const;

const tiers = [
  {
    name: "Monthly",
    price: "$4.99",
    cadence: "/mo",
    pitch: "Cancel anytime. Full access.",
    badge: null as string | null,
    featured: false,
    features: ["Unlimited projects", "All twelve breakdowns", "Native Swift Charts", "Open source"],
  },
  {
    name: "Yearly",
    price: "$34.99",
    cadence: "/yr",
    pitch: "$2.92 / month equivalent.",
    badge: "Best value",
    featured: true,
    features: ["7-day free trial", "Unlimited projects", "All twelve breakdowns", "Save 42% vs monthly"],
  },
  {
    name: "Lifetime",
    price: "$59.99",
    cadence: "once",
    pitch: "Pay once. No recurring charges. Ever.",
    badge: "Forever",
    featured: false,
    features: ["Unlimited projects", "All twelve breakdowns", "No subscription", "Yours forever"],
  },
] as const;

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: "Verceltics",
  operatingSystem: "iOS",
  applicationCategory: "DeveloperApplication",
  description:
    "Vercel web analytics viewer for iPhone and iPad. Track visitors, page views, bounce rate, referrers, countries, devices, browsers, and operating systems. Built with SwiftUI and Swift Charts. Open source, private by default, no tracking, no servers.",
  url: "https://verceltics.com",
  image: "https://verceltics.com/og.jpg",
  screenshot: [
    "https://verceltics.com/analytics.png",
    "https://verceltics.com/referrers.png",
    "https://verceltics.com/projects.png",
    "https://verceltics.com/devices.png",
    "https://verceltics.com/breakdowns.png",
  ],
  author: {
    "@type": "Person",
    name: "Apoorv Darshan",
    url: "https://x.com/apoorvdarshan",
  },
  offers: [
    { "@type": "Offer", price: "4.99", priceCurrency: "USD", description: "Monthly subscription" },
    { "@type": "Offer", price: "34.99", priceCurrency: "USD", description: "Yearly subscription with 7-day free trial" },
    { "@type": "Offer", price: "59.99", priceCurrency: "USD", description: "Lifetime, one-time purchase" },
  ],
  aggregateRating: {
    "@type": "AggregateRating",
    ratingValue: "5",
    ratingCount: "1",
  },
};

export default function Home() {
  return (
    <div className="grain relative bg-[#050a12] text-[#e8e8ed]">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />

      {/* Hidden SEO content — semantic keywords for crawlers */}
      <div className="sr-only" aria-hidden="true">
        <h2>Verceltics — Vercel Analytics iOS App</h2>
        <p>
          Verceltics is a native iOS and iPadOS app for viewing Vercel web analytics on your iPhone or iPad.
          Monitor your Vercel dashboard, check website traffic, track visitors, page views,
          unique visitors, bounce rate, session duration, referral sources, UTM campaigns,
          country traffic, device types, browser stats, operating system breakdown, top pages,
          route analytics, hostname analytics, event tracking, feature flags, and query parameters.
          Built with SwiftUI, Swift Charts, async/await, and StoreKit 2. Live deploy indicator,
          multi-account switching, framework-tinted dots, soft paywall flow, and App Store update checker.
          Vercel tokens stored in iOS Keychain. No app data collection. No app telemetry. No servers.
          Open source on GitHub. Works with Vercel Hobby and Pro plans.
          Alternative to Vercel dashboard for mobile. Best Vercel analytics app for iPhone.
          Vercel mobile app. Vercel stats on phone. Web analytics iOS. Developer tools iOS.
          Indie developer tools. Vercel project monitoring. Website traffic monitor iPhone.
          Real-time analytics mobile. Privacy-first analytics viewer.
          Subscription: $4.99/month, $34.99/year with 7-day free trial, or $59.99 lifetime
          one-time purchase. Compatible with iOS 18 and later.
        </p>
      </div>

      {/* ── Ambient ── */}
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute -top-[30vh] left-1/2 h-[80vh] w-[120vw] -translate-x-1/2 bg-[radial-gradient(ellipse,rgba(80,140,255,0.09),transparent_60%)]" />
      </div>

      {/* ── Nav ── */}
      <nav className="fixed top-0 z-50 w-full bg-[#050a12]/75 backdrop-blur-xl">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-5 py-3.5 sm:px-8">
          <Link href="/" className="flex items-center gap-2.5">
            <Image src="/icon.png" alt="" width={30} height={30} className="rounded-[9px]" />
            <span className="text-[13px] font-semibold tracking-[0.2em]">VERCELTICS</span>
          </Link>

          <div className="hidden items-center gap-7 md:flex">
            {[
              { text: "Features", href: "#features" },
              { text: "How it works", href: "#how-it-works" },
              { text: "Pricing", href: "#pricing" },
              { text: "GitHub", href: GITHUB, ext: true },
            ].map((l) => (
              <a
                key={l.text}
                href={l.href}
                target={l.ext ? "_blank" : undefined}
                rel={l.ext ? "noreferrer" : undefined}
                className="nav-link text-[13px] text-white/40 transition-colors hover:text-white"
              >
                {l.text}
              </a>
            ))}
          </div>

          <a
            href={APPSTORE}
            target="_blank"
            rel="noreferrer"
            className="rounded-full bg-white px-4 py-1.5 text-[13px] font-semibold text-[#050a12] transition-colors hover:bg-white/85"
          >
            App Store
          </a>
        </div>
      </nav>

      <main>
        {/* ══ HERO ══ */}
        <section className="relative flex min-h-svh items-center overflow-hidden">
          <div className="mx-auto grid w-full max-w-[1320px] items-center gap-8 px-5 pb-6 pt-20 sm:gap-12 sm:px-8 sm:pt-24 lg:grid-cols-[0.85fr_1.15fr] lg:gap-10 lg:pb-0 lg:pt-16">
            {/* Copy */}
            <div className="max-w-xl text-center lg:text-left">
              <p
                className="animate-fade-up text-[11px] font-medium uppercase tracking-[0.35em] text-white/30"
                style={{ animationDelay: "0.05s" }}
              >
                Open source &middot; iOS 18+ &middot; v1.1.2
              </p>

              <h1
                className="animate-fade-up mt-5 font-serif text-[clamp(3rem,7vw,7rem)] italic leading-[0.88] tracking-[-0.04em]"
                style={{ animationDelay: "0.15s" }}
              >
                Vercel analytics,
                <br />
                <span className="bg-gradient-to-r from-white via-[#a4cfff] to-[#5a96ff] bg-clip-text text-transparent">
                  in your pocket.
                </span>
              </h1>

              <p
                className="animate-fade-up mt-7 max-w-md text-[15px] leading-7 text-white/45 lg:text-base"
                style={{ animationDelay: "0.28s" }}
              >
                Visitors, referrers, devices, page views, twelve breakdowns —
                from your iPhone or iPad. Tokens in your Keychain. Nothing in between.
              </p>

              <div
                className="animate-fade-up mt-8 flex flex-col items-stretch gap-3 sm:flex-row sm:items-center lg:justify-start"
                style={{ animationDelay: "0.4s" }}
              >
                <a
                  href={APPSTORE}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center justify-center gap-2 rounded-full bg-white px-6 py-3 text-[14px] font-semibold text-[#050a12] transition-colors hover:bg-white/85 sm:text-[15px]"
                >
                  <AppleIcon />
                  Download on App Store
                </a>
                <a
                  href={PRODUCTHUNT}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center justify-center gap-2.5 rounded-full border border-[#FF6154]/20 bg-[#FF6154]/[0.06] px-5 py-2.5 transition-colors hover:bg-[#FF6154]/[0.12]"
                >
                  <svg width="24" height="24" viewBox="0 0 40 40" fill="none" aria-hidden className="flex-none">
                    <circle cx="20" cy="20" r="20" fill="#FF6154" />
                    <path d="M22.667 20h-6v-6.667h6a3.333 3.333 0 1 1 0 6.667Z" fill="#fff" />
                    <path d="M16.667 26.667V20h6a6.667 6.667 0 0 0 0-13.333h-9.334V26.667h3.334Z" fill="#fff" />
                  </svg>
                  <div className="flex flex-col">
                    <div className="flex items-center gap-0.5">
                      {[1, 2, 3, 4, 5].map((s) => (
                        <svg key={s} width="10" height="10" viewBox="0 0 24 24" fill="#FF6154" aria-hidden>
                          <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z" />
                        </svg>
                      ))}
                    </div>
                    <span className="text-[12px] font-medium text-[#FF6154]/80">Vote on Product Hunt</span>
                  </div>
                </a>
              </div>
            </div>

            {/* Hero phones — single transparent product shot */}
            <div className="animate-fade-up relative w-full" style={{ animationDelay: "0.35s" }}>
              <div className="absolute inset-x-[5%] top-[8%] h-[70%] rounded-[40%] bg-[radial-gradient(ellipse,rgba(80,140,255,0.22),transparent_70%)] blur-3xl" aria-hidden />
              <Image
                src="/hero-phones.png"
                alt="Verceltics on three iPhones — projects list, welcome chart, analytics dashboard"
                width={1920}
                height={1440}
                priority
                className="relative h-auto w-full drop-shadow-[0_50px_90px_rgba(0,0,0,0.6)] lg:w-[118%] lg:max-w-none lg:-translate-x-[6%]"
              />
            </div>
          </div>
        </section>

        {/* ── Ticker ── */}
        <div className="overflow-hidden border-y border-white/[0.04] py-4">
          <div className="animate-marquee flex w-max gap-10">
            {[...ticker, ...ticker].map((t, i) => (
              <span key={`${t}-${i}`} className="flex items-center gap-3 text-[13px] text-white/25">
                <span className="text-white/15">/</span>
                {t}
              </span>
            ))}
          </div>
        </div>

        {/* ══ FEATURES ══ */}
        <section id="features" className="scroll-mt-24 px-5 py-20 sm:px-8 sm:py-28">
          <div className="mx-auto max-w-6xl">
            <ScrollReveal>
              <p className="text-center text-[11px] font-medium uppercase tracking-[0.35em] text-white/30 lg:text-left">Features</p>
              <h2 className="mt-4 text-center font-serif text-[clamp(1.8rem,4.5vw,3.8rem)] italic leading-[0.95] tracking-[-0.03em] lg:max-w-lg lg:text-left">
                Everything you need. Nothing you don&apos;t.
              </h2>
            </ScrollReveal>

            <div className="mt-14 space-y-20 sm:mt-20 sm:space-y-28">
              {features.map((f, i) => {
                const flip = i % 2 !== 0;
                return (
                  <ScrollReveal key={f.title} delay={80}>
                    <div className={`grid items-center gap-8 sm:gap-12 lg:grid-cols-[1fr_1fr] lg:gap-20 ${flip ? "[direction:rtl]" : ""}`}>
                      {/* Screenshot */}
                      <div className="mx-auto w-full max-w-[220px] sm:max-w-[280px] [direction:ltr]">
                        <div className="overflow-hidden rounded-2xl border border-white/[0.05] shadow-[0_20px_60px_rgba(0,0,0,0.4)] sm:rounded-[1.5rem]">
                          <Image src={f.image} alt={f.alt} width={460} height={996} className="h-auto w-full" />
                        </div>
                      </div>

                      {/* Copy */}
                      <div className="text-center [direction:ltr] lg:text-left">
                        <p className="text-[11px] font-medium uppercase tracking-[0.35em] text-white/30">{f.label}</p>
                        <h3 className="mt-4 font-serif text-3xl italic leading-[1] tracking-[-0.02em] sm:text-4xl">
                          {f.title}
                        </h3>
                        <p className="mt-5 max-w-md text-[15px] leading-7 text-white/40">{f.body}</p>
                        <ul className="mt-7 flex flex-col gap-3">
                          {f.bullets.map((b) => (
                            <li key={b} className="flex items-center gap-2.5 text-[14px] text-white/35">
                              <span className="h-px w-3 bg-white/20" />
                              {b}
                            </li>
                          ))}
                        </ul>
                      </div>
                    </div>
                  </ScrollReveal>
                );
              })}
            </div>
          </div>
        </section>

        <div className="divider mx-auto max-w-4xl" />

        {/* ══ HOW IT WORKS ══ */}
        <section id="how-it-works" className="scroll-mt-24 px-5 py-20 sm:px-8 sm:py-28">
          <div className="mx-auto max-w-6xl">
            <ScrollReveal>
              <p className="text-[11px] font-medium uppercase tracking-[0.35em] text-white/30">Setup</p>
              <h2 className="mt-4 font-serif text-[clamp(2rem,4.5vw,3.8rem)] italic leading-[0.95] tracking-[-0.03em]">
                Three steps. That&apos;s it.
              </h2>
            </ScrollReveal>

            <div className="mt-16 grid gap-px overflow-hidden rounded-2xl border border-white/[0.04] md:grid-cols-3">
              {[
                { n: "01", t: "Create a token", d: "Generate a read-only token in your Vercel dashboard." },
                { n: "02", t: "Paste it in", d: "Enter it once. Add more accounts later if needed." },
                { n: "03", t: "Check anytime", d: "Open the app. See your stats. That’s the whole flow." },
              ].map((s, i) => (
                <ScrollReveal key={s.n} delay={i * 80}>
                  <div className="h-full bg-white/[0.02] p-8 transition-colors hover:bg-white/[0.04]">
                    <span className="font-serif text-4xl italic text-white/[0.06]">{s.n}</span>
                    <h3 className="mt-4 text-lg font-semibold tracking-[-0.01em]">{s.t}</h3>
                    <p className="mt-3 text-[14px] leading-6 text-white/35">{s.d}</p>
                  </div>
                </ScrollReveal>
              ))}
            </div>
          </div>
        </section>

        <div className="divider mx-auto max-w-4xl" />

        {/* ══ PRICING ══ */}
        <section id="pricing" className="scroll-mt-24 px-5 py-20 sm:px-8 sm:py-24">
          <div className="mx-auto max-w-6xl">
            <ScrollReveal>
              <div className="text-center">
                <p className="text-[11px] font-medium uppercase tracking-[0.35em] text-white/30">Pricing</p>
                <h2 className="mt-4 font-serif text-[clamp(2rem,4.5vw,3.8rem)] italic leading-[0.95] tracking-[-0.03em]">
                  Three plans. No tricks.
                </h2>
                <p className="mt-4 max-w-md mx-auto text-[14px] leading-6 text-white/35">
                  Yearly comes with a real 7-day free trial. Lifetime is one payment, no recurring charges ever. Or build from source for free with your own Vercel tokens.
                </p>
              </div>
            </ScrollReveal>

            <div className="mx-auto mt-12 grid max-w-5xl gap-px overflow-hidden rounded-2xl border border-white/[0.04] lg:grid-cols-3">
              {tiers.map((t, i) => (
                <ScrollReveal key={t.name} delay={i * 70}>
                  <div
                    className={`relative h-full p-7 sm:p-8 ${
                      t.featured
                        ? "bg-gradient-to-b from-sky-500/[0.08] via-sky-500/[0.02] to-white/[0.02]"
                        : "bg-white/[0.02]"
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <p
                        className={`text-[11px] font-medium uppercase tracking-[0.3em] ${
                          t.featured ? "text-sky-300/70" : "text-white/30"
                        }`}
                      >
                        {t.name}
                      </p>
                      {t.badge && (
                        <span
                          className={`rounded-full px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.2em] ${
                            t.featured
                              ? "bg-sky-400/[0.10] text-sky-300/80"
                              : "border border-white/10 text-white/45"
                          }`}
                        >
                          {t.badge}
                        </span>
                      )}
                    </div>

                    <div className="mt-5 flex items-baseline gap-1">
                      <span className="text-4xl font-semibold tracking-tight sm:text-5xl">{t.price}</span>
                      <span className="text-sm text-white/30">{t.cadence}</span>
                    </div>

                    <p className="mt-4 text-[14px] leading-6 text-white/35">{t.pitch}</p>

                    <ul className="mt-7 space-y-2.5">
                      {t.features.map((f) => (
                        <li
                          key={f}
                          className={`flex items-center gap-2.5 text-[13px] ${
                            t.featured ? "text-white/55" : "text-white/45"
                          }`}
                        >
                          <Tick className={t.featured ? "text-sky-400/55" : "text-white/25"} />
                          {f}
                        </li>
                      ))}
                    </ul>

                    <a
                      href={APPSTORE}
                      target="_blank"
                      rel="noreferrer"
                      className={`mt-8 inline-flex w-full items-center justify-center gap-2 rounded-full px-5 py-2.5 text-[13px] font-semibold transition-colors ${
                        t.featured
                          ? "bg-white text-[#050a12] hover:bg-white/90"
                          : "border border-white/10 text-white hover:border-white/30 hover:bg-white/[0.03]"
                      }`}
                    >
                      <AppleIcon />
                      {t.featured ? "Start free trial" : t.name === "Lifetime" ? "Buy lifetime" : "Start monthly"}
                    </a>
                  </div>
                </ScrollReveal>
              ))}
            </div>

            <p className="mt-6 text-center text-[11px] uppercase tracking-[0.18em] text-white/20">
              All payments via Apple. Subscriptions auto-renew until cancelled in Settings.
            </p>
          </div>
        </section>

        <div className="divider mx-auto max-w-4xl" />

        {/* ══ CTA ══ */}
        <section className="px-5 py-20 sm:px-8 sm:py-28">
          <ScrollReveal>
            <div className="mx-auto max-w-2xl text-center">
              <h2 className="font-serif text-[clamp(2.4rem,5vw,4.2rem)] italic leading-[0.92] tracking-[-0.03em]">
                Try it free for seven days.
              </h2>
              <p className="mt-5 text-[15px] text-white/35">Your Vercel analytics, always in your pocket.</p>
              <a
                href={APPSTORE}
                target="_blank"
                rel="noreferrer"
                className="mt-9 inline-flex items-center gap-2 rounded-full bg-white px-7 py-3.5 text-[15px] font-semibold text-[#050a12] transition-colors hover:bg-white/85"
              >
                <AppleIcon />
                Download on App Store
              </a>
            </div>
          </ScrollReveal>
        </section>
      </main>

      {/* ── Footer ── */}
      <footer className="border-t border-white/[0.04] px-5 py-10 sm:px-8 sm:py-12">
        <div className="mx-auto max-w-6xl">
          {/* Brand */}
          <div className="flex flex-col items-center text-center md:flex-row md:items-start md:justify-between md:text-left">
            <div className="max-w-xs">
              <div className="flex items-center justify-center gap-2 md:justify-start">
                <Image src="/icon.png" alt="" width={24} height={24} className="rounded-md" />
                <span className="text-[12px] font-semibold tracking-[0.2em]">VERCELTICS</span>
              </div>
              <p className="mt-3 text-[12px] leading-5 text-white/25">
                An open source companion app for Vercel Web Analytics.
              </p>
            </div>

            {/* Links — wrapping row on mobile, 3-col on desktop */}
            <div className="mt-8 flex flex-wrap justify-center gap-x-8 gap-y-2 text-[12px] text-white/30 md:mt-0 md:gap-x-12">
              <a href="#features" className="transition-colors hover:text-white/70">Features</a>
              <a href="#how-it-works" className="transition-colors hover:text-white/70">How it works</a>
              <a href="#pricing" className="transition-colors hover:text-white/70">Pricing</a>
              <Link href="/privacy" className="transition-colors hover:text-white/70">Privacy</Link>
              <Link href="/terms" className="transition-colors hover:text-white/70">Terms</Link>
              <a href={GITHUB} target="_blank" rel="noreferrer" className="transition-colors hover:text-white/70">GitHub</a>
              <a href="https://github.com/apoorvdarshan/verceltics/issues" target="_blank" rel="noreferrer" className="transition-colors hover:text-white/70">Report issue</a>
              <a href="https://x.com/apoorvdarshan" target="_blank" rel="noreferrer" className="transition-colors hover:text-white/70">X</a>
              <a href="mailto:ad13dtu@gmail.com" className="transition-colors hover:text-white/70">Contact</a>
              <a href="https://paypal.me/apoorvdarshan" target="_blank" rel="noreferrer" className="transition-colors hover:text-white/70">Support · PayPal</a>
            </div>
          </div>

          <p className="mt-8 text-center text-[11px] text-white/15 md:text-left">
            Built with <span className="text-white/30">♥</span> by Apoorv Darshan. Not affiliated with Vercel Inc. &copy; 2026.
          </p>
        </div>
      </footer>
    </div>
  );
}

/* ── Icons ── */

function AppleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
    </svg>
  );
}


function Tick({ className = "text-white/25" }: { className?: string }) {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className={`flex-none ${className}`} aria-hidden>
      <path d="M3.5 8.5L6.5 11.5L12.5 4.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
