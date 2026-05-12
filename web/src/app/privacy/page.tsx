import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "Verceltics privacy policy. Your Vercel tokens are stored locally in the iOS Keychain. No app tracking, no app telemetry, no Vercel data proxy.",
  alternates: { canonical: "https://www.verceltics.com/privacy" },
};

export default function Privacy() {
  return (
    <div className="mx-auto max-w-2xl px-6 py-28 sm:px-8">
      <Link href="/" className="text-[13px] text-white/30 transition-colors hover:text-white/60">&larr; Back to Verceltics</Link>
      <h1 className="mt-10 font-serif text-4xl italic tracking-[-0.03em]">Privacy Policy</h1>
      <p className="mt-2 text-[13px] text-white/25">Last updated: May 11, 2026 — applies to v1.1.5</p>

      <div className="mt-12 space-y-10 text-[15px] leading-7 text-white/40">
        <section>
          <h2 className="text-lg font-semibold text-white/80">Overview</h2>
          <p className="mt-3">Verceltics is a mobile analytics viewer for Vercel. We take your privacy seriously. This policy explains what data we collect and how we use it.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Data We Collect</h2>
          <p className="mt-3"><strong className="text-white/60">The iOS app does not collect your Vercel account data or analytics data.</strong> Specifically:</p>
          <ul className="mt-3 list-disc space-y-1 pl-5">
            <li>We do not track you</li>
            <li>We do not use product analytics, advertising tracking, or Vercel data telemetry inside the app</li>
            <li>We do not proxy or store your Vercel account data or analytics data on our servers</li>
            <li>We do not sell or share your Vercel tokens, account data, or analytics data with third parties</li>
          </ul>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Vercel API Tokens</h2>
          <p className="mt-3">Your Vercel personal access tokens are stored locally on your device in the iOS Keychain — Apple&apos;s encrypted, hardware-backed secure storage. Tokens are sent only to <code className="text-white/60">api.vercel.com</code> and <code className="text-white/60">vercel.com/api</code> to fetch your Vercel profile, projects, domains, and analytics. They never cross our infrastructure.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Analytics Data</h2>
          <p className="mt-3">All analytics data displayed in the app is fetched directly from Vercel&apos;s API to your device. We do not proxy, cache, or store this data anywhere.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Images, Favicons, and Avatars</h2>
          <p className="mt-3">To display project favicons in the project list, the app may issue plain GET requests for favicon URLs to <code className="text-white/60">images.weserv.nl</code> (SVG rasterisation), <code className="text-white/60">icons.duckduckgo.com</code>, <code className="text-white/60">www.google.com/s2/favicons</code>, and <code className="text-white/60">icon.horse</code>. Vercel profile avatars may be loaded from Vercel&apos;s avatar endpoint. These image requests never include your Vercel tokens or analytics data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">App Store Update Checks</h2>
          <p className="mt-3">To check whether a new version is available, the app may call Apple&apos;s public App Store lookup endpoint with the Verceltics app ID and country. This request never includes your Vercel tokens, account data, or analytics data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Website Analytics</h2>
          <p className="mt-3">The marketing website may use Vercel Web Analytics to understand aggregate page visits. This does not include your Vercel tokens, app account data, or analytics data from your Vercel projects.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Subscriptions</h2>
          <p className="mt-3">Subscriptions and the lifetime in-app purchase are processed by Apple through the App Store. Verceltics uses RevenueCat to manage entitlement status and restore purchases. RevenueCat may receive Apple purchase receipt data, an anonymous RevenueCat app user identifier, and subscription status needed to unlock the app.</p>
          <p className="mt-3">RevenueCat does not receive your Vercel tokens, Vercel account data, project data, or analytics data from Verceltics. We do not process or store any payment card information. We can&apos;t see your card.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">External Links and Support</h2>
          <p className="mt-3">The app and website may link to GitHub, Product Hunt, LinkedIn, Instagram, TrustMRR, Ko-fi, PayPal, X, Apple, and Vercel. Opening those links sends you to third-party services with their own privacy practices.</p>
          <p className="mt-3">Optional support payments through Ko-fi or PayPal are handled outside Verceltics. They do not unlock app features and do not give those services access to your Vercel tokens, Vercel account data, project data, or analytics data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Open Source</h2>
          <p className="mt-3">Verceltics is open source. You can verify everything above by reviewing the source code at <a href="https://github.com/apoorvdarshan/verceltics" className="text-white/60 underline underline-offset-2 transition-colors hover:text-white">github.com/apoorvdarshan/verceltics</a>.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Contact</h2>
          <p className="mt-3">If you have questions, contact us at <a href="mailto:ad13dtu@gmail.com" className="text-white/60 underline underline-offset-2 transition-colors hover:text-white">ad13dtu@gmail.com</a>.</p>
        </section>
      </div>
    </div>
  );
}
