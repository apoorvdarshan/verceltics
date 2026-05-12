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
  metadataBase: new URL("https://www.verceltics.com"),
  title: {
    default: "Verceltics — Vercel Analytics iOS App for iPhone and iPad",
    template: "%s — Verceltics",
  },
  description:
    "Open-source iOS app for Vercel Web Analytics: visitors, page views, referrers, devices, deployments, domains, and projects on iPhone.",
  keywords: [
    "Vercel",
    "analytics",
    "Vercel Analytics",
    "Vercel Web Analytics",
    "Vercel analytics iOS app",
    "Vercel mobile app",
    "Vercel iPhone app",
    "Vercel iPad app",
    "Vercel dashboard mobile",
    "Vercel project monitoring",
    "Vercel deployments",
    "Vercel domains",
    "iOS",
    "iPhone",
    "iPad",
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
    title: "Verceltics — Vercel Analytics iOS App",
    description:
      "Track Vercel Web Analytics, projects, deployments, domains, and traffic breakdowns from your iPhone or iPad.",
    url: "https://www.verceltics.com",
    images: [
      {
        url: "/og.jpg",
        width: 1200,
        height: 630,
        alt: "Verceltics — Vercel Analytics iOS App",
        type: "image/jpeg",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Verceltics — Vercel Analytics iOS App",
    description:
      "Track Vercel Web Analytics, projects, deployments, domains, and traffic breakdowns from your iPhone.",
    creator: "@apoorvdarshan",
    images: [
      {
        url: "/og.jpg",
        width: 1200,
        height: 630,
        alt: "Verceltics — Vercel Analytics iOS App",
      },
    ],
  },
  alternates: {
    canonical: "https://www.verceltics.com",
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
