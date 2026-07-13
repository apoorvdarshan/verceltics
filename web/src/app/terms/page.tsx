import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Terms of Service",
  description:
    "Verceltics terms of service. Subscription plans: $4.99/month, $34.99/year with 7-day free trial, and $59.99 lifetime one-time purchase. Refunds are handled by Apple.",
  alternates: { canonical: "https://www.verceltics.com/terms" },
  openGraph: {
    type: "article",
    siteName: "Verceltics",
    title: "Terms of Service — Verceltics",
    description:
      "Verceltics terms of service: subscription plans, lifetime purchase, and Apple-handled refunds.",
    url: "https://www.verceltics.com/terms",
    images: [{ url: "/og.jpg", width: 1200, height: 630, alt: "Verceltics" }],
  },
};

export default function Terms() {
  return (
    <div className="mx-auto max-w-2xl px-6 py-28 sm:px-8">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify({
            "@context": "https://schema.org",
            "@type": "BreadcrumbList",
            itemListElement: [
              { "@type": "ListItem", position: 1, name: "Home", item: "https://www.verceltics.com" },
              { "@type": "ListItem", position: 2, name: "Terms of Service", item: "https://www.verceltics.com/terms" },
            ],
          }),
        }}
      />
      <Link href="/" className="text-[13px] text-white/30 transition-colors hover:text-white/60">&larr; Back to Verceltics</Link>
      <h1 className="mt-10 font-serif text-4xl italic tracking-[-0.03em]">Terms of Service</h1>
      <p className="mt-2 text-[13px] text-white/25">Last updated: July 14, 2026 — applies to v2.0</p>

      <div className="mt-12 space-y-10 text-[15px] leading-7 text-white/40">
        <section>
          <h2 className="text-lg font-semibold text-white/80">Acceptance</h2>
          <p className="mt-3">By using Verceltics, you agree to these terms. If you don&apos;t agree, don&apos;t use the app.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">The Service</h2>
          <p className="mt-3">Verceltics is a mobile dashboard for supported hosting platforms and domain registrars. It uses credentials you provide to communicate directly with the selected provider, display account data, and perform actions you initiate.</p>
          <p className="mt-3">Advanced API explorers can send requests to provider-relative official API paths. You are responsible for reviewing the method, path, parameters, and body before confirming a write or purchase.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Your Account</h2>
          <p className="mt-3">You are responsible for every hosting and registrar credential you connect and all activity initiated through it. Keep credentials secure and revoke or rotate them if you stop using the app or suspect exposure. Provider credentials inherit their configured permissions and may allow purchases, configuration changes, or destructive operations.</p>
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
          <p className="mt-4">Purchases are processed by Apple. Verceltics uses RevenueCat to manage entitlement status, purchase history, refund request handling, and restore purchases for the Verceltics Pro entitlement.</p>
          <p className="mt-4">Verceltics also offers optional tips — one-time consumable in-app purchases (Coffee, Lunch, Big, and Huge). Tips are voluntary, processed by Apple through the RevenueCat SDK, unlock no content or features, and are not subscriptions.</p>
          <p className="mt-4">Refunds for App Store purchases are requested through Apple and decided by Apple under its App Store refund policy. When Apple requests developer input, Verceltics may use RevenueCat to send purchase and entitlement context and to prefer that Apple declines the refund request. This preference does not guarantee Apple&apos;s final decision.</p>
          <p className="mt-4">By using Verceltics and making in-app purchases, you consent to Verceltics and RevenueCat sharing purchase and entitlement context with Apple for refund request review.</p>
          <p className="mt-4">Verceltics does not issue refunds directly. If you have a purchase issue, contact us before requesting a refund so we can help troubleshoot access, restore purchases, or billing confusion.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Building From Source</h2>
          <p className="mt-3">Verceltics is open source under the MIT license. You&apos;re free to clone the repository at <a href="https://github.com/apoorvdarshan/verceltics" className="text-white/60 underline underline-offset-2 transition-colors hover:text-white">github.com/apoorvdarshan/verceltics</a> and build the app yourself for personal use. The App Store version exists for convenience and to fund continued development.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">External Links and Voluntary Support</h2>
          <p className="mt-3">Verceltics may link to external services such as GitHub, Product Hunt, LinkedIn, TrustMRR, Ko-fi, PayPal, X, Apple, Vercel, and Cloudflare. We are not responsible for the content, policies, or availability of those third-party services.</p>
          <p className="mt-3">Ko-fi and PayPal support links are voluntary support options for the developer. They are not a subscription, do not unlock Verceltics Pro, and do not replace App Store purchases.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Updates</h2>
          <p className="mt-3">Verceltics may check Apple&apos;s public App Store lookup endpoint to show when a newer version is available. Installing updates is optional, but older versions may stop receiving fixes over time.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Disclaimer</h2>
          <p className="mt-3">Verceltics is provided &quot;as is&quot; without warranty of any kind. We are not responsible for provider API availability, the accuracy of returned data, or changes resulting from actions you confirm. Verceltics is independent and is not affiliated with, endorsed by, or sponsored by any supported hosting platform or registrar.</p>
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
