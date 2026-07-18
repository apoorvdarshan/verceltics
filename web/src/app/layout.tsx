import type { Metadata, Viewport } from "next";
import { IBM_Plex_Mono, Space_Grotesk } from "next/font/google";

import "./globals.css";

const spaceGrotesk = Space_Grotesk({
  display: "swap",
  subsets: ["latin"],
  variable: "--font-space-grotesk",
  weight: ["400", "500", "600", "700"],
});

const ibmPlexMono = IBM_Plex_Mono({
  display: "swap",
  subsets: ["latin"],
  variable: "--font-ibm-plex-mono",
  weight: ["400", "500", "600"],
});

const SITE_URL = "https://verceltics.com";

export const viewport: Viewport = {
  colorScheme: "light",
  themeColor: "#dce3e0",
};

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: "Verceltics — Hosting, Domains & Web Analytics for iPhone",
    template: "%s — Verceltics",
  },
  description:
    "Open-source iPhone and iPad app for Vercel Analytics, hosting, domains, DNS, deployments, Search Console, site speed and uptime across 27 integrations.",
  applicationName: "Vercel Analytics — Verceltics",
  category: "Developer Tools",
  keywords: [
    "hosting dashboard iOS",
    "domain registrar app",
    "DNS management iPhone",
    "Cloudflare iOS dashboard",
    "Vercel Analytics iPhone",
    "Google Search Console iOS",
    "Google Analytics iOS dashboard",
    "Firebase Hosting iOS",
    "developer tools iPhone",
    "infrastructure dashboard iPad",
    "SwiftUI",
    "open source",
  ],
  authors: [{ name: "Apoorv Darshan", url: "https://x.com/apoorvdarshan" }],
  creator: "Apoorv Darshan",
  publisher: "Verceltics",
  itunes: { appId: "6761645656", appArgument: SITE_URL },
  openGraph: {
    type: "website",
    locale: "en_US",
    siteName: "Verceltics",
    title: "Verceltics — Hosting, Domains & Web Analytics for iPhone",
    description: "Monitor hosting, domains, DNS, deployments, analytics, search, speed and uptime across 27 integrations on iPhone and iPad.",
    url: SITE_URL,
    images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics mobile operations instrument" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "Verceltics — Hosting, Domains & Web Analytics for iPhone",
    description: "Monitor hosting, domains, DNS, deployments, analytics, search, speed and uptime across 27 integrations on iPhone and iPad.",
    creator: "@apoorvdarshan",
    images: ["/og-verceltics.png"],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: { index: true, follow: true, "max-image-preview": "large", "max-snippet": -1, "max-video-preview": -1 },
  },
};

const siteJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      "@id": `${SITE_URL}/#organization`,
      name: "Verceltics",
      url: SITE_URL,
      logo: `${SITE_URL}/icon.png`,
      founder: { "@type": "Person", name: "Apoorv Darshan", url: "https://x.com/apoorvdarshan" },
      sameAs: [
        "https://github.com/apoorvdarshan/verceltics",
        "https://www.linkedin.com/company/verceltics",
        "https://www.producthunt.com/products/verceltics",
        "https://trustmrr.com/startup/verceltics-hosting-domains",
        "https://ko-fi.com/apoorvdarshan",
        "https://x.com/apoorvdarshan",
      ],
    },
    {
      "@type": "WebSite",
      "@id": `${SITE_URL}/#website`,
      name: "Verceltics",
      url: SITE_URL,
      description: "A private, open-source iPhone and iPad app for hosting, domains, DNS, analytics, search, speed and uptime.",
      inLanguage: "en-US",
      publisher: { "@id": `${SITE_URL}/#organization` },
    },
  ],
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html className={`${spaceGrotesk.variable} ${ibmPlexMono.variable}`} lang="en">
      <body>
        <script dangerouslySetInnerHTML={{ __html: JSON.stringify(siteJsonLd) }} type="application/ld+json" />
        {children}
      </body>
    </html>
  );
}
