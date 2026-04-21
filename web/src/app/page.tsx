import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { ScrollReveal } from "@/components/scroll-reveal";

export const metadata: Metadata = {
  alternates: { canonical: "https://verceltics.site" },
};

const heroShots = [
  {
    src: "/projects.png",
    alt: "Verceltics project browser",
    className: "absolute left-0 top-6 z-10 w-[38%] -rotate-6 opacity-70",
    delay: "0.55s",
  },
  {
    src: "/analytics.png",
    alt: "Verceltics analytics dashboard",
    className: "relative z-30 mx-auto w-[48%]",
    delay: "0.3s",
  },
  {
    src: "/referrers.png",
    alt: "Verceltics referrers",
    className: "absolute right-0 top-6 z-10 w-[38%] rotate-6 opacity-70",
    delay: "0.8s",
  },
] as const;

const ticker = [
  "SwiftUI",
  "Swift Charts",
  "Private by design",
  "Open source",
  "iOS Keychain",
  "No tracking",
  "No servers",
  "Offline-ready",
] as const;

const features = [
  {
    label: "Dashboard",
    title: "Your numbers, one glance.",
    body: "Visitors, views, bounce rate, and trends in a native layout that stays legible on the smallest screen.",
    bullets: ["Period comparisons", "Swift Charts", "Mobile-first design"],
    image: "/analytics.png",
    alt: "Dashboard",
  },
  {
    label: "Breakdowns",
    title: "Where the traffic moved.",
    body: "Referrers, countries, UTMs, devices, browsers — sliced and surfaced without the tab maze.",
    bullets: ["Referrer ranking", "Country and device mix", "Launch day ready"],
    image: "/referrers.png",
    alt: "Breakdowns",
  },
  {
    label: "Projects",
    title: "Every project, one tap.",
    body: "Switch between sites instantly. Favicons, domains, and deployment context travel with you.",
    bullets: ["Fast switching with search", "Favicon detection", "Multi-site support"],
    image: "/projects.png",
    alt: "Projects",
  },
  {
    label: "Devices",
    title: "Know your audience.",
    body: "See which devices, browsers, and operating systems your visitors use — at a glance.",
    bullets: ["Device type split", "Browser and OS stats", "Spot mobile vs desktop trends"],
    image: "/devices.png",
    alt: "Devices",
  },
  {
    label: "Countries",
    title: "Traffic by region.",
    body: "Country-level breakdowns show exactly where your visitors are coming from.",
    bullets: ["Top countries ranked", "Geographic distribution", "Spot new markets fast"],
    image: "/breakdowns.png",
    alt: "Country breakdowns",
  },
] as const;

const pricingFeatures = [
  "3-day free trial",
  "Unlimited projects",
  "Native charts",
  "Open source",
] as const;

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: "Verceltics",
  operatingSystem: "iOS",
  applicationCategory: "DeveloperApplication",
  description:
    "Vercel web analytics viewer for iPhone. Track visitors, page views, bounce rate, referrers, countries, devices, browsers, and operating systems. Built with SwiftUI and Swift Charts. Open source, private by default, no tracking, no servers.",
  url: "https://verceltics.site",
  image: "https://verceltics.site/og.jpg",
  screenshot: [
    "https://verceltics.site/analytics.png",
    "https://verceltics.site/referrers.png",
    "https://verceltics.site/projects.png",
    "https://verceltics.site/devices.png",
    "https://verceltics.site/breakdowns.png",
  ],
  author: {
    "@type": "Person",
    name: "Apoorv Darshan",
    url: "https://x.com/apoorvdarshan",
  },
  offers: [
    {
      "@type": "Offer",
      price: "3.99",
      priceCurrency: "USD",
      description: "Monthly subscription with 3-day free trial",
    },
    {
      "@type": "Offer",
      price: "29.99",
      priceCurrency: "USD",
      description: "Yearly subscription with 3-day free trial",
    },
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
          Verceltics is a native iOS app for viewing Vercel web analytics on your iPhone.
          Monitor your Vercel dashboard, check website traffic, track visitors, page views,
          unique visitors, bounce rate, session duration, referral sources, UTM campaigns,
          country traffic, device types, browser stats, operating system breakdown, top pages,
          route analytics, hostname analytics, event tracking, and query parameters.
          Built with SwiftUI, Swift Charts, async/await, and StoreKit 2.
          Token stored in iOS Keychain. No data collection. No telemetry. No servers.
          Open source on GitHub. Works with Vercel Hobby and Pro plans.
          Alternative to Vercel dashboard for mobile. Best Vercel analytics app for iPhone.
          Vercel mobile app. Vercel stats on phone. Web analytics iOS. Developer tools iOS.
          Indie developer tools. Vercel project monitoring. Website traffic monitor iPhone.
          Real-time analytics mobile. Privacy-first analytics viewer. Subscription: $3.99/month
          or $29.99/year with 3-day free trial. Compatible with iOS 18 and later.
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
              { text: "GitHub", href: "https://github.com/apoorvdarshan/verceltics", ext: true },
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
            href="https://apps.apple.com/us/app/verceltics/id6761645656"
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
          <div className="mx-auto grid w-full max-w-6xl items-center gap-8 px-5 pb-6 pt-20 sm:gap-12 sm:px-8 sm:pt-24 lg:grid-cols-[1fr_0.9fr] lg:gap-12 lg:pb-0 lg:pt-16">
            {/* Copy */}
            <div className="max-w-xl text-center lg:text-left">
              <p
                className="animate-fade-up text-[11px] font-medium uppercase tracking-[0.35em] text-white/30"
                style={{ animationDelay: "0.05s" }}
              >
                Open source &middot; iOS
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
                Visitors, referrers, devices, and page views — from your iPhone. Private by design. Fully open source.
              </p>

              <div
                className="animate-fade-up mt-8 flex flex-col items-stretch gap-3 sm:flex-row sm:items-center lg:justify-start"
                style={{ animationDelay: "0.4s" }}
              >
                <a
                  href="https://apps.apple.com/us/app/verceltics/id6761645656"
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center justify-center gap-2 rounded-full bg-white px-6 py-3 text-[14px] font-semibold text-[#050a12] transition-colors hover:bg-white/85 sm:text-[15px]"
                >
                  <AppleIcon />
                  Download on App Store
                </a>
                <a
                  href="https://www.producthunt.com/products/verceltics"
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

            {/* Phones */}
            <div className="animate-fade-up w-full" style={{ animationDelay: "0.35s" }}>
              {/* Mobile */}
              <div className="lg:hidden">
                <div className="relative mx-auto flex max-w-[360px] items-center justify-center">
                  {heroShots.map((s) => (
                    <div key={s.src} className={`animate-fade-up ${s.className.replace('z-30', 'z-30').replace('w-[48%]', 'w-[50%]').replace('w-[38%]', 'w-[40%]')}`} style={{ animationDelay: s.delay }}>
                      <div className="overflow-hidden rounded-xl border border-white/[0.06] shadow-[0_12px_40px_rgba(0,0,0,0.5)]">
                        <Image src={s.src} alt={s.alt} width={200} height={433} className="h-auto w-full" priority={s.src === "/analytics.png"} />
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Desktop */}
              <div className="relative mx-auto hidden w-full max-w-[560px] lg:block xl:max-w-[600px]">
                <div className="relative flex h-[min(55vh,480px)] items-center justify-center">
                  {heroShots.map((s) => (
                    <div key={s.src} className={`animate-fade-up ${s.className}`} style={{ animationDelay: s.delay }}>
                      <div className="overflow-hidden rounded-2xl border border-white/[0.06] shadow-[0_20px_70px_rgba(0,0,0,0.6)]">
                        <Image src={s.src} alt={s.alt} width={300} height={650} className="h-auto w-full" priority={s.src === "/analytics.png"} />
                      </div>
                    </div>
                  ))}
                </div>
              </div>
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
                { n: "02", t: "Paste it in", d: "Enter it once. Stored in the iOS Keychain." },
                { n: "03", t: "Check anytime", d: "Open the app. See your stats. That\u2019s the whole flow." },
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
                  Simple pricing.
                </h2>
                <p className="mt-4 text-[14px] text-white/35">Both plans include a 3-day free trial.</p>
              </div>
            </ScrollReveal>

            <div className="mx-auto mt-12 grid max-w-3xl gap-px overflow-hidden rounded-2xl border border-white/[0.04] lg:grid-cols-2">
              {/* Monthly */}
              <ScrollReveal>
                <div className="h-full bg-white/[0.02] p-7 sm:p-8">
                  <p className="text-[11px] font-medium uppercase tracking-[0.3em] text-white/30">Monthly</p>
                  <div className="mt-5 flex items-baseline gap-1">
                    <span className="text-4xl font-semibold tracking-tight sm:text-5xl">$3.99</span>
                    <span className="text-sm text-white/30">/mo</span>
                  </div>
                  <p className="mt-4 text-[14px] leading-6 text-white/35">Full access. Cancel anytime.</p>
                  <ul className="mt-7 space-y-2.5">
                    {pricingFeatures.map((f) => (
                      <li key={f} className="flex items-center gap-2.5 text-[13px] text-white/40">
                        <Tick />
                        {f}
                      </li>
                    ))}
                  </ul>
                </div>
              </ScrollReveal>

              {/* Yearly */}
              <ScrollReveal delay={80}>
                <div className="relative h-full bg-gradient-to-b from-sky-500/[0.06] to-white/[0.02] p-7 sm:p-8">
                  <div className="flex items-center justify-between">
                    <p className="text-[11px] font-medium uppercase tracking-[0.3em] text-sky-300/50">Yearly</p>
                    <span className="rounded-full bg-sky-400/[0.08] px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.2em] text-sky-300/70">
                      Best value
                    </span>
                  </div>
                  <div className="mt-5 flex items-baseline gap-1">
                    <span className="text-4xl font-semibold tracking-tight sm:text-5xl">$29.99</span>
                    <span className="text-sm text-white/30">/yr</span>
                  </div>
                  <div className="mt-2 inline-flex rounded-full bg-emerald-500/[0.08] px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-[0.15em] text-emerald-400/70">
                    Save 37%
                  </div>
                  <p className="mt-4 text-[14px] leading-6 text-white/35">Best for regular use.</p>
                  <ul className="mt-7 space-y-2.5">
                    {pricingFeatures.map((f) => (
                      <li key={f} className="flex items-center gap-2.5 text-[13px] text-white/45">
                        <Tick className="text-sky-400/50" />
                        {f}
                      </li>
                    ))}
                  </ul>
                </div>
              </ScrollReveal>
            </div>
          </div>
        </section>

        <div className="divider mx-auto max-w-4xl" />

        {/* ══ CTA ══ */}
        <section className="px-5 py-20 sm:px-8 sm:py-28">
          <ScrollReveal>
            <div className="mx-auto max-w-2xl text-center">
              <h2 className="font-serif text-[clamp(2.4rem,5vw,4.2rem)] italic leading-[0.92] tracking-[-0.03em]">
                Try it free for three days.
              </h2>
              <p className="mt-5 text-[15px] text-white/35">Your Vercel analytics, always in your pocket.</p>
              <a
                href="https://apps.apple.com/us/app/verceltics/id6761645656"
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
              <a href="https://github.com/apoorvdarshan/verceltics" target="_blank" rel="noreferrer" className="transition-colors hover:text-white/70">GitHub</a>
              <a href="https://x.com/apoorvdarshan" target="_blank" rel="noreferrer" className="transition-colors hover:text-white/70">X</a>
              <a href="mailto:ad13dtu@gmail.com" className="transition-colors hover:text-white/70">Contact</a>
            </div>
          </div>

          <p className="mt-8 text-center text-[11px] text-white/15 md:text-left">
            Not affiliated with Vercel Inc. &copy; 2026 Apoorv Darshan.
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
