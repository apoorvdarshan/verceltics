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
const X_HANDLE = "https://x.com/apoorvdarshan";

const ticker = [
  "ISSUE Nº 01",
  "VOLUME I",
  "v1.1.0 — BUILD 2",
  "GIT main",
  "iOS 18+",
  "OPEN SOURCE",
  "TOKEN IN KEYCHAIN",
  "NO TRACKING",
  "NO SERVERS",
  "NATIVE SWIFTUI",
  "SWIFT CHARTS",
  "BUILT BY APOORV DARSHAN",
] as const;

type Department = {
  num: string;
  label: string;
  title: string;
  body: string;
  bullets: readonly string[];
  image: string;
  alt: string;
};

const departments: readonly Department[] = [
  {
    num: "01",
    label: "Dashboard",
    title: "Your numbers,\nat one glance.",
    body:
      "Visitors, page views, bounce rate — with the kind of period comparison you'd actually read on a phone. Drag the chart to inspect any day; the peak indicator and average reference line travel with the data.",
    bullets: ["Period comparisons", "Swift Charts native", "Drag-to-inspect with haptics"],
    image: "/analytics.png",
    alt: "Verceltics analytics dashboard",
  },
  {
    num: "02",
    label: "Breakdowns",
    title: "Where the\ntraffic moved.",
    body:
      "Every breakdown the Vercel API returns, ranked and rendered. Pages, routes, hostnames, referrers, UTM, countries, devices, browsers, operating systems, events, flags, query params — twelve dimensions, no tab maze.",
    bullets: ["Twelve breakdowns surfaced", "Ranked by visitors", "Country flags & framework tints"],
    image: "/referrers.png",
    alt: "Breakdowns: pages, routes, hostnames, referrers",
  },
  {
    num: "03",
    label: "Projects",
    title: "Every project,\none tap.",
    body:
      "All your Vercel projects with their favicons, framework, last commit, and a pulsing green dot when something deployed in the last thirty minutes. Switch instantly. Search by name, domain, or framework.",
    bullets: ["Live deploy indicator", "Framework-tinted dots", "Search & filter"],
    image: "/projects.png",
    alt: "Verceltics project list",
  },
  {
    num: "04",
    label: "Audience",
    title: "Who's visiting,\nfrom where.",
    body:
      "Country, device, browser, OS — every audience axis from the same data Vercel renders on the web. The percentages are real, the bars are gradient-filled, and you can read it all without rotating your phone.",
    bullets: ["Country with flag emoji", "Device & browser splits", "OS distribution"],
    image: "/devices.png",
    alt: "Country, device, and browser breakdowns",
  },
  {
    num: "05",
    label: "Depth",
    title: "Down to the\nquery parameter.",
    body:
      "Operating systems. Custom events. Feature flags. UTM source. Query strings. Everything Vercel tracks in the web dashboard, surfaced in the same legible cards. No more pulling out a laptop to debug a campaign.",
    bullets: ["Events & flags", "UTM source ranking", "Query params"],
    image: "/breakdowns.png",
    alt: "OS, events, flags, query params",
  },
] as const;

const tiers = [
  {
    name: "Monthly",
    price: "$4.99",
    cadence: "/month",
    pitch: "Cancel anytime.",
    badge: null as string | null,
    features: ["Unlimited projects", "Native Swift Charts", "All breakdowns"],
    cta: "Start monthly",
  },
  {
    name: "Yearly",
    price: "$34.99",
    cadence: "/year",
    pitch: "$2.92 / month equivalent.",
    badge: "Best value",
    features: ["Unlimited projects", "All breakdowns", "7-day free trial"],
    cta: "Start free trial",
  },
  {
    name: "Lifetime",
    price: "$59.99",
    cadence: "one-time",
    pitch: "Pay once. Yours forever.",
    badge: "Forever",
    features: ["Unlimited projects", "All breakdowns", "No recurring charges"],
    cta: "Buy lifetime",
  },
] as const;

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: "Verceltics",
  operatingSystem: "iOS",
  applicationCategory: "DeveloperApplication",
  description:
    "Vercel web analytics viewer for iPhone. Visitors, page views, bounce rate, referrers, countries, devices, browsers, and operating systems. Built with SwiftUI and Swift Charts. Open source, private by default.",
  url: "https://verceltics.com",
  image: "https://verceltics.com/og.jpg",
  screenshot: [
    "https://verceltics.com/analytics.png",
    "https://verceltics.com/referrers.png",
    "https://verceltics.com/projects.png",
    "https://verceltics.com/devices.png",
    "https://verceltics.com/breakdowns.png",
  ],
  author: { "@type": "Person", name: "Apoorv Darshan", url: X_HANDLE },
  offers: [
    { "@type": "Offer", price: "4.99", priceCurrency: "USD", description: "Monthly subscription" },
    { "@type": "Offer", price: "34.99", priceCurrency: "USD", description: "Yearly subscription with 7-day free trial" },
    { "@type": "Offer", price: "59.99", priceCurrency: "USD", description: "Lifetime, one-time purchase" },
  ],
  aggregateRating: { "@type": "AggregateRating", ratingValue: "5", ratingCount: "1" },
};

export default function Home() {
  return (
    <div className="grain relative bg-black text-[var(--paper)]">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />

      {/* Hidden SEO content */}
      <div className="sr-only" aria-hidden="true">
        <h2>Verceltics — Vercel Analytics iOS App</h2>
        <p>
          Verceltics is a native iOS app for viewing Vercel web analytics on your iPhone.
          Monitor your Vercel dashboard, check website traffic, track visitors, page views,
          unique visitors, bounce rate, referral sources, UTM campaigns, country traffic,
          device types, browser stats, operating system breakdown, top pages, route analytics,
          hostname analytics, event tracking, and query parameters. Built with SwiftUI, Swift
          Charts, async/await, and StoreKit 2. Token stored in iOS Keychain. No data
          collection. No telemetry. No servers. Open source on GitHub. Works with Vercel
          Hobby and Pro plans. Subscription: $4.99/month, $34.99/year with 7-day free trial,
          or $59.99 lifetime. Compatible with iOS 18 and later.
        </p>
      </div>

      {/* ══ AMBIENT ══ */}
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute -top-[40vh] left-1/2 h-[80vh] w-[120vw] -translate-x-1/2 bg-[radial-gradient(ellipse_at_center,rgba(214,255,92,0.045),transparent_60%)]" />
        <div className="absolute bottom-[-20vh] right-[-10vw] h-[60vh] w-[60vw] bg-[radial-gradient(circle,rgba(90,150,255,0.045),transparent_60%)]" />
      </div>

      {/* ══ NAV ══ */}
      <nav className="fixed top-0 z-50 w-full border-b border-[var(--rule)] bg-black/80 backdrop-blur-xl">
        <div className="mx-auto flex max-w-[1280px] items-center justify-between px-5 py-3.5 sm:px-8">
          <Link href="/" className="flex items-center gap-3">
            <Image src="/icon.png" alt="" width={26} height={26} className="rounded-[7px]" />
            <span className="serif text-[18px] leading-none">Verceltics</span>
            <span className="hidden border-l border-[var(--rule)] pl-3 text-[10px] tracking-[0.18em] text-[var(--paper-faint)] sm:inline mono">
              ISSUE Nº 01 · VOL I
            </span>
          </Link>

          <div className="hidden items-center gap-7 lg:flex">
            {[
              { text: "Departments", href: "#departments" },
              { text: "Setup", href: "#setup" },
              { text: "Subscription", href: "#subscription" },
              { text: "GitHub", href: GITHUB, ext: true },
            ].map((l) => (
              <a
                key={l.text}
                href={l.href}
                target={l.ext ? "_blank" : undefined}
                rel={l.ext ? "noreferrer" : undefined}
                className="mag-link text-[12px] tracking-wide text-[var(--paper-soft)]"
              >
                {l.text}
              </a>
            ))}
          </div>

          <a
            href={APPSTORE}
            target="_blank"
            rel="noreferrer"
            className="rounded-full bg-[var(--paper)] px-4 py-1.5 text-[12px] font-semibold text-black transition-colors hover:bg-white"
          >
            App Store
          </a>
        </div>
      </nav>

      <main className="relative">
        {/* ══════════════════════════════════════════════════════════════
            MASTHEAD / HERO
            ══════════════════════════════════════════════════════════════ */}
        <section className="relative overflow-hidden pt-[88px] sm:pt-[104px]">
          {/* top-of-page issue meta */}
          <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
            <div className="flex items-center justify-between border-b border-[var(--rule)] pb-3 text-[10px] tracking-[0.18em] text-[var(--paper-faint)] mono uppercase">
              <span>Verceltics — A Field Guide</span>
              <span className="hidden sm:inline">Edited from your iPhone</span>
              <span className="flex items-center gap-2">
                <span className="live-dot" aria-hidden /> live
              </span>
            </div>
          </div>

          <div className="mx-auto grid max-w-[1280px] gap-10 px-5 pb-12 pt-12 sm:px-8 sm:pb-20 sm:pt-16 lg:grid-cols-12 lg:gap-8 lg:pb-28 lg:pt-20">
            {/* Left column — masthead copy */}
            <div className="lg:col-span-7">
              <p className="animate-fade-soft eyebrow" style={{ animationDelay: "0.05s" }}>
                Open source · iOS 18+ · Native SwiftUI
              </p>

              <h1
                className="animate-fade-up headline mt-6 text-[clamp(3rem,9vw,8.5rem)]"
                style={{ animationDelay: "0.15s" }}
              >
                Vercel
                <br />
                analytics,
                <br />
                <span className="text-[var(--accent)]">in your pocket.</span>
                <span className="ml-1 align-top text-[0.45em] text-[var(--paper-faint)] not-italic mono">¹</span>
              </h1>

              <div className="mt-10 grid gap-7 lg:grid-cols-[2fr_1fr]">
                <p
                  className="animate-fade-up max-w-[44ch] text-[16px] leading-[1.7] text-[var(--paper-soft)] lg:text-[17px]"
                  style={{ animationDelay: "0.28s" }}
                >
                  Visitors, page views, bounce rate, referrers, countries, devices —
                  every breakdown Vercel tracks, on the iPhone you already carry.
                  No dashboards open. No tabs to find.
                </p>

                <div
                  className="animate-fade-soft border-l border-[var(--rule-strong)] pl-5"
                  style={{ animationDelay: "0.4s" }}
                >
                  <p className="eyebrow">Method</p>
                  <p className="mt-2 text-[12px] leading-[1.6] text-[var(--paper-muted)] mono">
                    Token stored in iOS Keychain. Requests go to{" "}
                    <span className="text-[var(--paper)]">api.vercel.com</span>{" "}
                    only. No middleware, no telemetry, nothing in between.
                  </p>
                </div>
              </div>

              <div
                className="animate-fade-up mt-10 flex flex-col items-stretch gap-3 sm:flex-row sm:items-center"
                style={{ animationDelay: "0.5s" }}
              >
                <a href={APPSTORE} target="_blank" rel="noreferrer" className="btn-accent">
                  <AppleIcon /> Download on App Store
                </a>
                <a
                  href={PRODUCTHUNT}
                  target="_blank"
                  rel="noreferrer"
                  className="btn-ghost"
                >
                  <PHIcon /> Vote on Product Hunt
                </a>
              </div>

              <div
                className="animate-fade-soft mt-8 grid grid-cols-3 gap-px overflow-hidden rounded border border-[var(--rule)] sm:max-w-md"
                style={{ animationDelay: "0.65s" }}
              >
                {[
                  { k: "Open source", v: "MIT" },
                  { k: "Pricing from", v: "$4.99/mo" },
                  { k: "Trial", v: "7 days" },
                ].map((s) => (
                  <div key={s.k} className="bg-black/40 p-4">
                    <p className="text-[9px] tracking-[0.18em] text-[var(--paper-faint)] mono uppercase">
                      {s.k}
                    </p>
                    <p className="mt-1 serif text-[20px] text-[var(--paper)]">{s.v}</p>
                  </div>
                ))}
              </div>

              <p className="mt-6 max-w-md text-[11px] leading-[1.5] text-[var(--paper-faint)] mono">
                <span className="text-[var(--paper-muted)]">¹</span> Or your iPad. The
                app ships universal, with a native sidebar and an adaptive grid that
                respects every breakpoint.
              </p>
            </div>

            {/* Right column — phones */}
            <div className="lg:col-span-5">
              <div className="relative mx-auto h-[480px] w-full max-w-[440px] sm:h-[560px] lg:h-[640px]">
                {/* Background phone — projects */}
                <div
                  className="animate-fade-up absolute left-0 top-12 w-[58%] -rotate-[8deg] opacity-70"
                  style={{ animationDelay: "0.7s" }}
                >
                  <div className="float overflow-hidden rounded-[28px] border border-[var(--rule)] shadow-[0_30px_80px_-20px_rgba(0,0,0,0.8)]">
                    <Image
                      src="/projects.png"
                      alt="Verceltics project list"
                      width={300}
                      height={650}
                      className="h-auto w-full"
                    />
                  </div>
                </div>

                {/* Foreground phone — analytics */}
                <div
                  className="animate-fade-up absolute right-0 top-0 z-10 w-[68%] rotate-[3deg]"
                  style={{ animationDelay: "0.5s" }}
                >
                  <div className="overflow-hidden rounded-[32px] border border-[var(--rule-strong)] shadow-[0_40px_100px_-20px_rgba(0,0,0,0.9)]">
                    <Image
                      src="/analytics.png"
                      alt="Verceltics analytics dashboard"
                      width={400}
                      height={866}
                      className="h-auto w-full"
                      priority
                    />
                  </div>
                </div>

                {/* Floating annotation pill */}
                <div
                  className="animate-fade-soft absolute -bottom-2 left-0 hidden items-center gap-2 rounded-full border border-[var(--rule-strong)] bg-black/85 px-3 py-1.5 backdrop-blur sm:flex"
                  style={{ animationDelay: "1s" }}
                >
                  <span className="h-1.5 w-1.5 rounded-full bg-[var(--accent)]" />
                  <span className="text-[10px] tracking-[0.18em] text-[var(--paper-soft)] mono uppercase">
                    Drag chart · Peak 56
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* ══ TICKER ══ */}
        <div className="overflow-hidden border-y border-[var(--rule)] bg-black/40 py-3.5">
          <div className="animate-marquee flex w-max gap-12">
            {[...ticker, ...ticker].map((t, i) => (
              <span key={`${t}-${i}`} className="flex items-center gap-4 text-[11px] tracking-[0.16em] text-[var(--paper-faint)] mono uppercase">
                <span className="text-[var(--accent)]">§</span>
                {t}
              </span>
            ))}
          </div>
        </div>

        {/* ══════════════════════════════════════════════════════════════
            DEPARTMENTS — TABLE OF CONTENTS
            ══════════════════════════════════════════════════════════════ */}
        <section className="px-5 pt-24 sm:px-8 sm:pt-32">
          <div className="mx-auto max-w-[1280px]">
            <ScrollReveal>
              <div className="rule-text">
                Table of Contents
              </div>

              <div className="mt-10 grid gap-12 lg:grid-cols-12 lg:gap-8">
                <div className="lg:col-span-5">
                  <h2 className="headline text-[clamp(2.4rem,5.5vw,5rem)]">
                    Five
                    <br />
                    departments.
                  </h2>
                </div>
                <div className="lg:col-span-6 lg:col-start-7">
                  <p className="text-[16px] leading-[1.65] text-[var(--paper-soft)]">
                    A field guide reads better when it&apos;s organised. So instead
                    of a feature list, here&apos;s an index — five departments,
                    each one a real pane of the app, each one a reason this works
                    on a phone.
                  </p>
                  <ol className="mt-7 divide-y divide-[var(--rule)] border-y border-[var(--rule)]">
                    {departments.map((d) => (
                      <li key={d.num}>
                        <a
                          href={`#dept-${d.num}`}
                          className="group flex items-center justify-between py-3.5 text-[14px] transition-colors hover:bg-[var(--paper-ghost)]"
                        >
                          <span className="flex items-center gap-5">
                            <span className="dept-num">№ {d.num}</span>
                            <span className="serif text-[20px] text-[var(--paper)]">
                              {d.label}
                            </span>
                          </span>
                          <span className="text-[var(--paper-faint)] transition-colors group-hover:text-[var(--paper)]">
                            →
                          </span>
                        </a>
                      </li>
                    ))}
                  </ol>
                </div>
              </div>
            </ScrollReveal>
          </div>
        </section>

        {/* ══════════════════════════════════════════════════════════════
            DEPARTMENTS — INDIVIDUAL ENTRIES
            ══════════════════════════════════════════════════════════════ */}
        <section id="departments" className="scroll-mt-24 px-5 pt-24 sm:px-8 sm:pt-32">
          <div className="mx-auto max-w-[1280px]">
            <div className="space-y-32 sm:space-y-44">
              {departments.map((d, i) => {
                const flip = i % 2 !== 0;
                return (
                  <ScrollReveal key={d.num} delay={60}>
                    <article id={`dept-${d.num}`} className="scroll-mt-24">
                      <div className="rule-text">
                        Department № {d.num} — {d.label}
                      </div>

                      <div className="mt-10 grid items-center gap-10 lg:grid-cols-12 lg:gap-16">
                        {/* Phone */}
                        <div
                          className={`mx-auto w-full max-w-[260px] lg:col-span-5 lg:max-w-none ${
                            flip ? "lg:order-2 lg:col-start-8" : "lg:order-1 lg:col-start-1"
                          }`}
                        >
                          <div className="overflow-hidden rounded-[28px] border border-[var(--rule)] shadow-[0_30px_80px_-20px_rgba(0,0,0,0.7)]">
                            <Image
                              src={d.image}
                              alt={d.alt}
                              width={460}
                              height={996}
                              className="h-auto w-full"
                            />
                          </div>
                        </div>

                        {/* Copy */}
                        <div
                          className={`flex flex-col justify-center lg:col-span-6 ${
                            flip ? "lg:order-1 lg:col-start-1" : "lg:order-2 lg:col-start-7"
                          }`}
                        >
                          <p className="dept-marker">№ {d.num} / {d.label}</p>

                          <h3 className="headline mt-5 whitespace-pre-line text-[clamp(2.2rem,4.5vw,4rem)]">
                            {d.title}
                          </h3>

                          <p className="mt-7 max-w-[52ch] text-[16px] leading-[1.7] text-[var(--paper-soft)]">
                            {d.body}
                          </p>

                          <ul className="mt-8 grid gap-2.5">
                            {d.bullets.map((b) => (
                              <li
                                key={b}
                                className="flex items-baseline gap-3 text-[13px] text-[var(--paper-muted)] mono"
                              >
                                <span className="text-[var(--accent)]">→</span>
                                {b}
                              </li>
                            ))}
                          </ul>
                        </div>
                      </div>
                    </article>
                  </ScrollReveal>
                );
              })}
            </div>
          </div>
        </section>

        {/* ══════════════════════════════════════════════════════════════
            EDITORIAL PULL QUOTE
            ══════════════════════════════════════════════════════════════ */}
        <section className="px-5 pt-32 sm:px-8 sm:pt-44">
          <div className="mx-auto max-w-[1100px]">
            <ScrollReveal>
              <div className="border-y border-[var(--rule-strong)] py-16 text-center sm:py-24">
                <p className="eyebrow">A Field Note</p>
                <p className="serif mt-7 text-[clamp(1.8rem,4.5vw,3.4rem)] leading-[1.15] tracking-[-0.02em] text-[var(--paper)]">
                  &ldquo;You shouldn&apos;t need a laptop and three browser tabs
                  to know how your site is doing. The phone in your hand
                  is enough.&rdquo;
                </p>
                <p className="mt-7 mono text-[10px] tracking-[0.18em] text-[var(--paper-faint)] uppercase">
                  — From the editor
                </p>
              </div>
            </ScrollReveal>
          </div>
        </section>

        {/* ══════════════════════════════════════════════════════════════
            SETUP — THREE STEPS
            ══════════════════════════════════════════════════════════════ */}
        <section id="setup" className="scroll-mt-24 px-5 pt-32 sm:px-8 sm:pt-44">
          <div className="mx-auto max-w-[1280px]">
            <ScrollReveal>
              <div className="rule-text">Setup</div>

              <div className="mt-10 grid gap-10 lg:grid-cols-12 lg:gap-12">
                <div className="lg:col-span-5">
                  <h2 className="headline text-[clamp(2.4rem,5.5vw,5rem)]">
                    Three
                    <br />
                    minutes,
                    <br />
                    once.
                  </h2>
                </div>
                <div className="lg:col-span-6 lg:col-start-7">
                  <p className="text-[16px] leading-[1.65] text-[var(--paper-soft)]">
                    No account to create. No magic link to hunt for in your
                    inbox. The only credential is a read-only Vercel token that
                    never leaves your device.
                  </p>
                </div>
              </div>
            </ScrollReveal>

            <div className="mt-16 grid gap-px overflow-hidden border border-[var(--rule)] md:grid-cols-3">
              {[
                {
                  n: "01",
                  t: "Create a token",
                  d: "Generate a read-only personal token in your Vercel account settings. Takes ten seconds.",
                  ref: "vercel.com/account/tokens",
                },
                {
                  n: "02",
                  t: "Paste it once",
                  d: "Open Verceltics, paste the token. We store it in the iOS Keychain — encrypted, app-scoped, sandboxed.",
                  ref: "iOS Keychain",
                },
                {
                  n: "03",
                  t: "Check anytime",
                  d: "Open the app from anywhere. Pull to refresh. The data is current. The token never crosses our servers — because there are none.",
                  ref: "No backend",
                },
              ].map((s, i) => (
                <ScrollReveal key={s.n} delay={i * 80}>
                  <div className="editorial-card flex h-full flex-col bg-black/40 p-7">
                    <div className="flex items-baseline justify-between">
                      <span className="dept-num">№ {s.n}</span>
                      <span className="text-[10px] tracking-[0.14em] text-[var(--paper-faint)] mono uppercase">
                        Step {s.n}
                      </span>
                    </div>

                    <h3 className="serif mt-6 text-[28px] leading-[1.05] text-[var(--paper)]">
                      {s.t}
                    </h3>
                    <p className="mt-4 flex-1 text-[14px] leading-[1.6] text-[var(--paper-muted)]">
                      {s.d}
                    </p>
                    <p className="mt-6 border-t border-[var(--rule)] pt-4 text-[10px] tracking-[0.16em] text-[var(--paper-faint)] mono uppercase">
                      → {s.ref}
                    </p>
                  </div>
                </ScrollReveal>
              ))}
            </div>
          </div>
        </section>

        {/* ══════════════════════════════════════════════════════════════
            SUBSCRIPTION
            ══════════════════════════════════════════════════════════════ */}
        <section id="subscription" className="scroll-mt-24 px-5 pt-32 sm:px-8 sm:pt-44">
          <div className="mx-auto max-w-[1280px]">
            <ScrollReveal>
              <div className="rule-text">Subscription</div>

              <div className="mt-10 grid gap-10 lg:grid-cols-12 lg:gap-12">
                <div className="lg:col-span-5">
                  <h2 className="headline text-[clamp(2.4rem,5.5vw,5rem)]">
                    Three plans.
                    <br />
                    <span className="text-[var(--accent)]">No tricks.</span>
                  </h2>
                </div>
                <div className="lg:col-span-6 lg:col-start-7">
                  <p className="text-[16px] leading-[1.65] text-[var(--paper-soft)]">
                    Yearly comes with a real seven-day free trial. Lifetime is
                    a single payment, no recurring charge ever. You can also
                    clone the repo, build it yourself with your own token, and
                    skip the App Store entirely — no hard feelings.
                  </p>
                </div>
              </div>
            </ScrollReveal>

            <div className="mt-14 grid gap-px overflow-hidden border border-[var(--rule)] lg:grid-cols-3">
              {tiers.map((t, i) => {
                const isFeatured = t.name === "Yearly";
                return (
                  <ScrollReveal key={t.name} delay={i * 80}>
                    <div
                      className={`editorial-card flex h-full flex-col p-8 ${
                        isFeatured
                          ? "bg-[radial-gradient(ellipse_at_top,rgba(214,255,92,0.08),transparent_70%)]"
                          : "bg-black/40"
                      }`}
                    >
                      <div className="flex items-baseline justify-between">
                        <p
                          className={`text-[10px] tracking-[0.18em] mono uppercase ${
                            isFeatured ? "text-[var(--accent)]" : "text-[var(--paper-faint)]"
                          }`}
                        >
                          {t.name}
                        </p>
                        {t.badge && (
                          <span
                            className={`rounded-full border px-2.5 py-0.5 text-[9px] tracking-[0.16em] mono uppercase ${
                              isFeatured
                                ? "border-[var(--accent)]/40 bg-[var(--accent-soft)] text-[var(--accent)]"
                                : "border-[var(--rule-strong)] text-[var(--paper-soft)]"
                            }`}
                          >
                            {t.badge}
                          </span>
                        )}
                      </div>

                      <div className="mt-6 flex items-baseline gap-2">
                        <span className="serif text-[clamp(3rem,5.5vw,4.5rem)] text-[var(--paper)]">
                          {t.price}
                        </span>
                        <span className="text-[12px] text-[var(--paper-faint)] mono">
                          {t.cadence}
                        </span>
                      </div>

                      <p className="mt-4 text-[14px] leading-[1.55] text-[var(--paper-muted)]">
                        {t.pitch}
                      </p>

                      <ul className="mt-8 flex flex-1 flex-col gap-3 border-t border-[var(--rule)] pt-7">
                        {t.features.map((f) => (
                          <li
                            key={f}
                            className="flex items-baseline gap-3 text-[13px] text-[var(--paper-soft)]"
                          >
                            <Tick className={isFeatured ? "text-[var(--accent)]" : "text-[var(--paper-faint)]"} />
                            <span>{f}</span>
                          </li>
                        ))}
                      </ul>

                      <a
                        href={APPSTORE}
                        target="_blank"
                        rel="noreferrer"
                        className={`mt-9 inline-flex items-center justify-center gap-2 rounded-full px-5 py-3 text-[13px] font-semibold transition-colors ${
                          isFeatured
                            ? "bg-[var(--accent)] text-black hover:brightness-105"
                            : "border border-[var(--rule-strong)] text-[var(--paper)] hover:border-[var(--paper-muted)]"
                        }`}
                      >
                        <AppleIcon />
                        {t.cta}
                      </a>
                    </div>
                  </ScrollReveal>
                );
              })}
            </div>

            <p className="mt-6 mono text-[10px] tracking-[0.16em] text-[var(--paper-faint)] uppercase">
              All payments via Apple. Subscriptions auto-renew until cancelled.
              Lifetime is a non-consumable in-app purchase.
            </p>
          </div>
        </section>

        {/* ══════════════════════════════════════════════════════════════
            CTA
            ══════════════════════════════════════════════════════════════ */}
        <section className="px-5 pt-32 sm:px-8 sm:pt-44">
          <ScrollReveal>
            <div className="mx-auto max-w-[1100px] border-y border-[var(--rule-strong)] py-20 text-center sm:py-28">
              <p className="eyebrow">Last word</p>
              <h2 className="headline mt-7 text-[clamp(2.6rem,7vw,6rem)]">
                Try it for
                <br />
                <span className="text-[var(--accent)]">seven days.</span>
              </h2>
              <p className="mt-7 max-w-md mx-auto text-[15px] leading-[1.6] text-[var(--paper-soft)]">
                Free for a week, full access. Cancel any time inside the App Store
                — Apple handles the whole thing, we never see your card.
              </p>
              <div className="mt-10 flex flex-col items-center justify-center gap-3 sm:flex-row">
                <a href={APPSTORE} target="_blank" rel="noreferrer" className="btn-accent">
                  <AppleIcon /> Download on App Store
                </a>
                <a href={GITHUB} target="_blank" rel="noreferrer" className="btn-ghost">
                  <GitHubIcon /> Read the source
                </a>
              </div>
            </div>
          </ScrollReveal>
        </section>

        {/* ══════════════════════════════════════════════════════════════
            COLOPHON
            ══════════════════════════════════════════════════════════════ */}
        <footer className="px-5 pb-12 pt-24 sm:px-8 sm:pt-32">
          <div className="mx-auto max-w-[1280px]">
            <div className="rule-text">Colophon</div>

            <div className="mt-10 grid gap-10 lg:grid-cols-12 lg:gap-12">
              <div className="lg:col-span-6">
                <p className="serif text-[clamp(1.8rem,3.5vw,2.6rem)] leading-[1.2] text-[var(--paper)]">
                  Set in <span className="not-italic">Geist</span> &{" "}
                  Instrument Serif. Built with Next.js, Tailwind, and Swift Charts. Composed
                  by <a href={X_HANDLE} className="text-[var(--accent)] underline-offset-4 hover:underline">Apoorv Darshan</a>.
                </p>
              </div>

              <div className="lg:col-span-6 lg:col-start-7">
                <div className="grid grid-cols-2 gap-8 sm:grid-cols-3">
                  {[
                    {
                      head: "Sections",
                      items: [
                        ["Departments", "#departments"],
                        ["Setup", "#setup"],
                        ["Subscription", "#subscription"],
                      ],
                    },
                    {
                      head: "Legal",
                      items: [
                        ["Privacy", "/privacy"],
                        ["Terms", "/terms"],
                      ],
                    },
                    {
                      head: "Elsewhere",
                      items: [
                        ["GitHub", GITHUB],
                        ["X", X_HANDLE],
                        ["Email", "mailto:ad13dtu@gmail.com"],
                      ],
                    },
                  ].map((col) => (
                    <div key={col.head}>
                      <p className="dept-num">{col.head}</p>
                      <ul className="mt-4 space-y-2.5">
                        {col.items.map(([label, href]) => (
                          <li key={label}>
                            {(href as string).startsWith("#") || (href as string).startsWith("/") ? (
                              <Link
                                href={href as string}
                                className="text-[13px] text-[var(--paper-soft)] mag-link"
                              >
                                {label}
                              </Link>
                            ) : (
                              <a
                                href={href as string}
                                target="_blank"
                                rel="noreferrer"
                                className="text-[13px] text-[var(--paper-soft)] mag-link"
                              >
                                {label}
                              </a>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            <div className="mt-16 flex flex-col items-start justify-between gap-4 border-t border-[var(--rule)] pt-7 text-[10px] tracking-[0.16em] text-[var(--paper-faint)] mono uppercase sm:flex-row sm:items-center">
              <div className="flex items-center gap-3">
                <Image src="/icon.png" alt="" width={20} height={20} className="rounded-[5px]" />
                <span>Verceltics — Issue Nº 01</span>
              </div>
              <p className="max-w-md text-left sm:text-right">
                Not affiliated with Vercel Inc. © {new Date().getFullYear()} Apoorv Darshan. All
                rights reserved on what little I claim.
              </p>
            </div>
          </div>
        </footer>
      </main>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════
   ICONS
   ══════════════════════════════════════════════════════════════ */

function AppleIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
    </svg>
  );
}

function PHIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 40 40" fill="none" aria-hidden>
      <circle cx="20" cy="20" r="20" fill="#FF6154" />
      <path d="M22.667 20h-6v-6.667h6a3.333 3.333 0 1 1 0 6.667Z" fill="#fff" />
      <path d="M16.667 26.667V20h6a6.667 6.667 0 0 0 0-13.333h-9.334V26.667h3.334Z" fill="#fff" />
    </svg>
  );
}

function GitHubIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0 1 12 6.844a9.59 9.59 0 0 1 2.504.337c1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.203 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.02 10.02 0 0 0 22 12.017C22 6.484 17.522 2 12 2Z"
      />
    </svg>
  );
}

function Tick({ className = "text-[var(--paper-faint)]" }: { className?: string }) {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" className={`flex-none translate-y-[3px] ${className}`} aria-hidden>
      <path d="M3.5 8.5L6.5 11.5L12.5 4.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
