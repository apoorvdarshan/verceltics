import type { Metadata } from "next";
import { DM_Sans, Instrument_Serif } from "next/font/google";
import { Analytics } from "@vercel/analytics/next";
import "./globals.css";

const dmSans = DM_Sans({
  variable: "--font-dm-sans",
  subsets: ["latin"],
  weight: ["300", "400", "500", "600", "700", "800"],
});

const instrumentSerif = Instrument_Serif({
  variable: "--font-instrument-serif",
  subsets: ["latin"],
  weight: "400",
  style: ["normal", "italic"],
});

export const metadata: Metadata = {
  metadataBase: new URL("https://verceltics.site"),
  title: {
    default: "Verceltics — Vercel Web Analytics on Your iPhone, Open Source",
    template: "%s — Verceltics",
  },
  description:
    "Track your Vercel web analytics on the go. Visitors, page views, bounce rate, referrers, countries, devices — all from your iPhone. Open source and private by default.",
  keywords: [
    "Vercel",
    "analytics",
    "iOS",
    "iPhone",
    "web analytics",
    "mobile analytics",
    "SwiftUI",
    "open source",
    "Vercel dashboard",
    "page views",
    "referrers",
    "bounce rate",
  ],
  authors: [{ name: "Apoorv Darshan", url: "https://x.com/apoorvdarshan" }],
  creator: "Apoorv Darshan",
  publisher: "Apoorv Darshan",
  openGraph: {
    type: "website",
    locale: "en_US",
    siteName: "Verceltics",
    title: "Verceltics — Vercel Analytics on Your iPhone",
    description:
      "Track visitors, page views, referrers, countries, and devices from your iPhone. Open source. Private by default.",
    url: "https://verceltics.site",
    images: [
      {
        url: "/og.jpg",
        width: 1200,
        height: 630,
        alt: "Verceltics — Vercel Analytics on Your iPhone",
        type: "image/jpeg",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Verceltics — Vercel Analytics on Your iPhone",
    description:
      "Track visitors, page views, referrers, and devices from your iPhone. Open source.",
    creator: "@apoorvdarshan",
    images: [
      {
        url: "/og.jpg",
        width: 1200,
        height: 630,
        alt: "Verceltics — Vercel Analytics on Your iPhone",
      },
    ],
  },
  alternates: {
    canonical: "https://verceltics.site",
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-video-preview": -1,
      "max-image-preview": "large",
      "max-snippet": -1,
    },
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${dmSans.variable} ${instrumentSerif.variable} antialiased`}>
      <body className="min-h-screen bg-black font-[family-name:var(--font-dm-sans)] text-white">
        {children}
        <Analytics />
      </body>
    </html>
  );
}
