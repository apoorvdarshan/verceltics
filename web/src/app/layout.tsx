import type { Metadata, Viewport } from "next";
import { Antonio, IBM_Plex_Mono, IBM_Plex_Sans } from "next/font/google";

import "./globals.css";

const antonio = Antonio({
  display: "swap",
  subsets: ["latin"],
  variable: "--font-antonio",
  weight: ["600", "700"],
});

const ibmPlexSans = IBM_Plex_Sans({
  display: "swap",
  subsets: ["latin"],
  variable: "--font-ibm-plex-sans",
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
  colorScheme: "dark",
  themeColor: "#07090d",
};

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: "Verceltics — Your Stack Under One Thumb",
    template: "%s — Verceltics",
  },
  description:
    "A private, open-source iPhone and iPad workspace for hosting platforms, domain registrars, deployments, analytics, search performance, speed, and uptime.",
  applicationName: "Verceltics",
  category: "Developer Tools",
  keywords: [
    "hosting dashboard iOS",
    "domain registrar app",
    "DNS management iPhone",
    "Cloudflare iOS dashboard",
    "Vercel mobile app",
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
  alternates: { canonical: SITE_URL },
  openGraph: {
    type: "website",
    locale: "en_US",
    siteName: "Verceltics",
    title: "Verceltics — Your stack under one thumb",
    description:
      "Review deploys, domains, DNS, traffic, search, speed, and uptime from a native iPhone and iPad workspace—without a credential proxy.",
    url: SITE_URL,
    images: [
      {
        url: "/screens/ipad/cloudflare.png",
        width: 2360,
        height: 1640,
        alt: "Verceltics Cloudflare analytics workspace on iPad",
        type: "image/png",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Verceltics — Your stack under one thumb",
    description:
      "Review deploys, domains, DNS, traffic, search, speed, and uptime from a private native workspace.",
    creator: "@apoorvdarshan",
    images: ["/screens/ipad/cloudflare.png"],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-image-preview": "large",
      "max-snippet": -1,
      "max-video-preview": -1,
    },
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
        "https://x.com/apoorvdarshan",
      ],
    },
    {
      "@type": "WebSite",
      "@id": `${SITE_URL}/#website`,
      name: "Verceltics",
      url: SITE_URL,
      description: "Hosting, domains, and site intelligence on iPhone and iPad.",
      inLanguage: "en-US",
      publisher: { "@id": `${SITE_URL}/#organization` },
    },
  ],
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html className={`${antonio.variable} ${ibmPlexSans.variable} ${ibmPlexMono.variable}`} lang="en">
      <body>
        <script dangerouslySetInnerHTML={{ __html: JSON.stringify(siteJsonLd) }} type="application/ld+json" />
        {children}
      </body>
    </html>
  );
}
