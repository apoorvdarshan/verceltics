import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "Verceltics privacy policy. We collect no personal data. Your Vercel token is stored locally in the iOS Keychain. No tracking, no telemetry, no servers.",
  alternates: { canonical: "https://verceltics.com/privacy" },
};

export default function Privacy() {
  return (
    <div className="mx-auto max-w-2xl px-6 py-28 sm:px-8">
      <Link href="/" className="text-[13px] text-white/30 transition-colors hover:text-white/60">&larr; Back to Verceltics</Link>
      <h1 className="mt-10 font-serif text-4xl italic tracking-[-0.03em]">Privacy Policy</h1>
      <p className="mt-2 text-[13px] text-white/25">Last updated: May 3, 2026 — applies to v1.1.1</p>

      <div className="mt-12 space-y-10 text-[15px] leading-7 text-white/40">
        <section>
          <h2 className="text-lg font-semibold text-white/80">Overview</h2>
          <p className="mt-3">Verceltics is a mobile analytics viewer for Vercel. We take your privacy seriously. This policy explains what data we collect and how we use it.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Data We Collect</h2>
          <p className="mt-3"><strong className="text-white/60">We do not collect any personal data.</strong> Specifically:</p>
          <ul className="mt-3 list-disc space-y-1 pl-5">
            <li>We do not track you</li>
            <li>We do not use analytics or telemetry inside the app</li>
            <li>We do not store your data on our servers — there are no servers</li>
            <li>We do not sell or share your Vercel token, account data, or analytics data with third parties</li>
          </ul>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Vercel API Token</h2>
          <p className="mt-3">Your Vercel personal access token is stored locally on your device in the iOS Keychain — Apple&apos;s encrypted, hardware-backed secure storage. The token is sent only to <code className="text-white/60">api.vercel.com</code> and <code className="text-white/60">vercel.com/api</code> to fetch your projects and analytics. It never crosses our infrastructure.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Analytics Data</h2>
          <p className="mt-3">All analytics data displayed in the app is fetched directly from Vercel&apos;s API to your device. We do not proxy, cache, or store this data anywhere.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Favicon Services</h2>
          <p className="mt-3">To display project favicons in the project list, the app may issue plain GET requests for favicon URLs to <code className="text-white/60">images.weserv.nl</code> (SVG rasterisation), <code className="text-white/60">icons.duckduckgo.com</code>, <code className="text-white/60">www.google.com/s2/favicons</code>, and <code className="text-white/60">icon.horse</code>. These requests carry only the favicon URL — they never include your Vercel token or any account data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">App Store Update Checks</h2>
          <p className="mt-3">To check whether a new version is available, the app may call Apple&apos;s public App Store lookup endpoint with the Verceltics app ID and country. This request never includes your Vercel token, account data, or analytics data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Subscriptions</h2>
          <p className="mt-3">Subscriptions and the lifetime in-app purchase are managed entirely by Apple through the App Store. We do not process or store any payment information. We can&apos;t see your card.</p>
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
