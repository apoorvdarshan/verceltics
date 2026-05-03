import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Terms of Service",
  description:
    "Verceltics terms of service. Subscription plans: $4.99/month, $34.99/year with 7-day free trial, and $59.99 lifetime one-time purchase. Manage subscriptions through Apple ID settings.",
  alternates: { canonical: "https://verceltics.com/terms" },
};

export default function Terms() {
  return (
    <div className="mx-auto max-w-2xl px-6 py-28 sm:px-8">
      <Link href="/" className="text-[13px] text-white/30 transition-colors hover:text-white/60">&larr; Back to Verceltics</Link>
      <h1 className="mt-10 font-serif text-4xl italic tracking-[-0.03em]">Terms of Service</h1>
      <p className="mt-2 text-[13px] text-white/25">Last updated: May 3, 2026 — applies to v1.1.1</p>

      <div className="mt-12 space-y-10 text-[15px] leading-7 text-white/40">
        <section>
          <h2 className="text-lg font-semibold text-white/80">Acceptance</h2>
          <p className="mt-3">By using Verceltics, you agree to these terms. If you don&apos;t agree, don&apos;t use the app.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">The Service</h2>
          <p className="mt-3">Verceltics is a mobile app that displays your Vercel web analytics using your own Vercel personal access token. The app fetches data directly from Vercel&apos;s API on your behalf.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Your Account</h2>
          <p className="mt-3">You are responsible for your Vercel access token and any activity that occurs through it. Keep your token secure. We recommend creating a token specifically for Verceltics and revoking it if you stop using the app.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Subscriptions &amp; Lifetime</h2>
          <p className="mt-3">Verceltics offers three purchase options:</p>
          <ul className="mt-3 list-disc space-y-1 pl-5">
            <li><strong className="text-white/60">Monthly</strong> — auto-renewable subscription at $4.99/month. No trial.</li>
            <li><strong className="text-white/60">Yearly</strong> — auto-renewable subscription at $34.99/year, with a 7-day free trial for first-time subscribers.</li>
            <li><strong className="text-white/60">Lifetime</strong> — one-time non-consumable purchase at $59.99. No recurring charges. Yours forever, restorable across your Apple ID&apos;s devices.</li>
          </ul>
          <p className="mt-4">For subscriptions:</p>
          <ul className="mt-3 list-disc space-y-1 pl-5">
            <li>Payment is charged to your Apple ID at confirmation of purchase</li>
            <li>Subscriptions auto-renew unless cancelled at least 24 hours before the end of the current period</li>
            <li>You can manage and cancel subscriptions in your Apple ID settings (<code className="text-white/60">Settings → Apple ID → Subscriptions</code>)</li>
            <li>Any unused portion of a free trial is forfeited when you purchase a subscription</li>
          </ul>
          <p className="mt-4">The Lifetime purchase is non-refundable through Verceltics; refunds for any purchase are processed by Apple per their App Store refund policy.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Building From Source</h2>
          <p className="mt-3">Verceltics is open source under the MIT license. You&apos;re free to clone the repository at <a href="https://github.com/apoorvdarshan/verceltics" className="text-white/60 underline underline-offset-2 transition-colors hover:text-white">github.com/apoorvdarshan/verceltics</a> and build the app yourself for personal use. The App Store version exists for convenience and to fund continued development.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Updates</h2>
          <p className="mt-3">Verceltics may check Apple&apos;s public App Store lookup endpoint to show when a newer version is available. Installing updates is optional, but older versions may stop receiving fixes over time.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Disclaimer</h2>
          <p className="mt-3">Verceltics is provided &quot;as is&quot; without warranty of any kind. We are not responsible for the accuracy or availability of data from Vercel&apos;s API. Verceltics is not affiliated with, endorsed by, or sponsored by Vercel Inc.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Limitation of Liability</h2>
          <p className="mt-3">To the maximum extent permitted by law, Verceltics and its developer shall not be liable for any indirect, incidental, special, or consequential damages arising from your use of the app.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Changes</h2>
          <p className="mt-3">We may update these terms from time to time. Continued use of the app after changes constitutes acceptance of the new terms.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Contact</h2>
          <p className="mt-3">Questions? Email us at <a href="mailto:ad13dtu@gmail.com" className="text-white/60 underline underline-offset-2 transition-colors hover:text-white">ad13dtu@gmail.com</a>.</p>
        </section>
      </div>
    </div>
  );
}
