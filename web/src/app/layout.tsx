import type { Metadata } from "next";
import { Geist, Geist_Mono, Instrument_Serif } from "next/font/google";
import { Analytics } from "@vercel/analytics/next";
import "./globals.css";

const geist = Geist({
  variable: "--font-geist",
  subsets: ["latin"],
  weight: ["300", "400", "500", "600", "700"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

const instrumentSerif = Instrument_Serif({
  variable: "--font-instrument-serif",
  subsets: ["latin"],
  weight: "400",
  style: ["normal", "italic"],
});

export const metadata: Metadata = {
  metadataBase: new URL("https://verceltics.com"),
  title: {
    default: "Verceltics — Vercel Web Analytics on Your iPhone, Open Source",
    template: "%s — Verceltics",
  },
  description:
    "Vercel web analytics from your iPhone. Visitors, page views, bounce rate, referrers, countries, devices — every breakdown Vercel tracks. Open source, native, private.",
  keywords: [
    "Vercel",
    "Vercel analytics",
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
    "developer tools",
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
      "Visitors, page views, referrers, countries, devices — every breakdown Vercel tracks, on the iPhone you already carry.",
    url: "https://verceltics.com",
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
      "Visitors, page views, referrers, devices — every breakdown Vercel tracks, on iPhone. Open source.",
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
    canonical: "https://verceltics.com",
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
    <html
      lang="en"
      className={`${geist.variable} ${geistMono.variable} ${instrumentSerif.variable} antialiased`}
    >
      <body className="min-h-screen bg-black text-[#f0e9d6]" style={{ fontFamily: "var(--font-geist)" }}>
        {children}
        <Analytics />
      </body>
    </html>
  );
}
