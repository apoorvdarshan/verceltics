import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy",
  description:
    "Verceltics privacy policy. Hosting and registrar credentials stay in the device-only iOS Keychain. No app tracking, telemetry, or data proxy.",
  alternates: { canonical: "https://www.verceltics.com/privacy" },
  openGraph: {
    type: "article",
    siteName: "Verceltics",
    title: "Privacy Policy — Verceltics",
    description:
      "How Verceltics handles provider credentials and data: device-only Keychain storage, no tracking, no telemetry, and no data proxy.",
    url: "https://www.verceltics.com/privacy",
    images: [{ url: "/og.jpg", width: 1200, height: 630, alt: "Verceltics" }],
  },
};

export default function Privacy() {
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
              { "@type": "ListItem", position: 2, name: "Privacy Policy", item: "https://www.verceltics.com/privacy" },
            ],
          }),
        }}
      />
      <Link href="/" className="text-[13px] text-white/30 transition-colors hover:text-white/60">&larr; Back to Verceltics</Link>
      <h1 className="mt-10 font-serif text-4xl italic tracking-[-0.03em]">Privacy Policy</h1>
      <p className="mt-2 text-[13px] text-white/25">Last updated: July 14, 2026 — applies to v2.0</p>

      <div className="mt-12 space-y-10 text-[15px] leading-7 text-white/40">
        <section>
          <h2 className="text-lg font-semibold text-white/80">Overview</h2>
          <p className="mt-3">Verceltics is a direct-to-provider mobile dashboard for hosting platforms and domain registrars. This policy explains how credentials and provider data are handled.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Data We Collect</h2>
          <p className="mt-3"><strong className="text-white/60">The iOS app does not collect your hosting or registrar account data.</strong> Specifically:</p>
          <ul className="mt-3 list-disc space-y-1 pl-5">
            <li>We do not track you</li>
            <li>We do not use product analytics, advertising tracking, or provider-data telemetry inside the app</li>
            <li>We do not proxy or store your provider account data, configuration, or analytics on our servers</li>
            <li>We do not sell or share your credentials or provider data with third parties</li>
          </ul>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Provider Credentials</h2>
          <p className="mt-3">Credentials for Vercel, Cloudflare, Netlify, Railway, Render, DigitalOcean, Heroku, Fly.io, Firebase, AWS Amplify, Name.com, Namecheap, Porkbun, Spaceship, Dynadot, NameSilo, Gandi, and GoDaddy are stored locally using device-only, when-unlocked iOS Keychain protection.</p>
          <p className="mt-3">A credential is attached only to HTTPS requests for its selected provider&apos;s official API host. For Firebase refresh-token connections, the refresh token is first sent directly to Google&apos;s official <code className="text-white/60">oauth2.googleapis.com</code> token endpoint and the returned access token is sent to Firebase Hosting. Redirects to a different host are blocked. Credentials never cross Verceltics infrastructure. Provider keys inherit the permissions configured by the provider; detected writes and purchases require confirmation in the app.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Analytics Data</h2>
          <p className="mt-3">All account, project, domain, configuration, deployment, DNS, Worker, and analytics data is fetched directly from the selected provider to your device. API explorer responses are displayed locally. We do not proxy or store this data on our servers.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Images, Favicons, and Avatars</h2>
          <p className="mt-3">To display project favicons, the app may issue plain GET requests to <code className="text-white/60">images.weserv.nl</code>, <code className="text-white/60">icons.duckduckgo.com</code>, <code className="text-white/60">www.google.com/s2/favicons</code>, and <code className="text-white/60">icon.horse</code>. Vercel profile avatars may be loaded from Vercel. These image requests never include provider credentials or account data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">App Store Update Checks</h2>
          <p className="mt-3">To check whether a new version is available, the app may call Apple&apos;s public App Store lookup endpoint with the Verceltics app ID and country. This request never includes provider credentials or provider data.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Website Hosting</h2>
          <p className="mt-3">The marketing website is hosted on Cloudflare Pages. The website does not include client-side analytics. Cloudflare may process standard connection and security data needed to deliver and protect the site under its own privacy policy. This does not include credentials or account data saved in the iOS app.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Subscriptions</h2>
          <p className="mt-3">Subscriptions and the lifetime in-app purchase are processed by Apple through the App Store. Verceltics uses RevenueCat to manage entitlement status and restore purchases. RevenueCat may receive Apple purchase receipt data, an anonymous RevenueCat app user identifier, and subscription status needed to unlock the app.</p>
          <p className="mt-3">Optional tips are one-time consumable in-app purchases processed by Apple and requested through the RevenueCat SDK. They unlock no content or features and involve no payment information being shared with Verceltics.</p>
          <p className="mt-3">RevenueCat does not receive provider credentials, account data, project data, configuration, or analytics from Verceltics. We do not process or store any payment card information. We can&apos;t see your card.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">Refund Requests</h2>
          <p className="mt-3">Refund requests for App Store purchases are handled by Apple. If you request a refund through Apple, RevenueCat may send Apple purchase and entitlement context needed to respond to Apple&apos;s refund review, such as receipt data, product identifiers, purchase history, subscription status, and whether the purchase was delivered or restored.</p>
          <p className="mt-3">Verceltics configures RevenueCat to prefer that Apple declines refund requests. Apple makes the final refund decision. This refund handling data does not include provider credentials or provider account data.</p>
          <p className="mt-3">By using Verceltics and making in-app purchases, you consent to Verceltics and RevenueCat sharing this purchase and entitlement context with Apple for refund request review.</p>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-white/80">External Links and Support</h2>
          <p className="mt-3">The app and website may link to GitHub, Product Hunt, LinkedIn, TrustMRR, Ko-fi, PayPal, X, Apple, and supported hosting or registrar providers. Opening those links sends you to third-party services with their own privacy practices.</p>
          <p className="mt-3">Optional support payments through Ko-fi or PayPal are handled outside Verceltics. They do not unlock app features and do not give those services access to provider credentials or provider data.</p>
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
