import type { MetadataRoute } from "next";

export const dynamic = "force-static";

const UPDATED = new Date("2026-10-10");

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    { url: "https://verceltics.com/", lastModified: UPDATED, changeFrequency: "weekly", priority: 1 },
    { url: "https://verceltics.com/android", lastModified: UPDATED, changeFrequency: "weekly", priority: 0.9 },
    { url: "https://verceltics.com/vercel-analytics-ios", lastModified: UPDATED, changeFrequency: "monthly", priority: 0.9 },
    { url: "https://verceltics.com/integrations", lastModified: UPDATED, changeFrequency: "monthly", priority: 0.8 },
    { url: "https://verceltics.com/privacy", lastModified: UPDATED, changeFrequency: "yearly", priority: 0.4 },
    { url: "https://verceltics.com/terms", lastModified: UPDATED, changeFrequency: "yearly", priority: 0.3 },
  ];
}
