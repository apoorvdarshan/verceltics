import type { MetadataRoute } from "next";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://verceltics.com",
      lastModified: new Date("2026-07-17"),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: "https://verceltics.com/privacy",
      lastModified: new Date("2026-07-17"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: "https://verceltics.com/terms",
      lastModified: new Date("2026-07-17"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
  ];
}
