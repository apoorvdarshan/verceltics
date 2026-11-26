import type { Metadata } from "next";

export const SITE_URL = "https://verceltics.com";

export function pageMetadata(title: string, description: string, path: string, article = false): Metadata {
  const url = `${SITE_URL}${path}`;
  return {
    title, description,
    alternates: { canonical: url },
    openGraph: {
      title: `${title} | Verceltics`, description, url,
      type: article ? "article" : "website", siteName: "Verceltics",
      images: [{ url: "/og-verceltics.png", width: 1200, height: 630, alt: "Verceltics for iPhone, iPad and Android" }],
    },
    twitter: { card: "summary_large_image", title: `${title} | Verceltics`, description, images: ["/og-verceltics.png"] },
  };
}

export function structuredData(value: unknown) {
  return JSON.stringify(value).replace(/</g, "\\u003c");
}

export function breadcrumbs(items: { name: string; path: string }[]) {
  return {
    "@context": "https://schema.org", "@type": "BreadcrumbList",
    itemListElement: [{ name: "Home", path: "/" }, ...items].map((item, index) => ({
      "@type": "ListItem", position: index + 1, name: item.name, item: `${SITE_URL}${item.path}`,
    })),
  };
}
