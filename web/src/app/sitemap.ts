import type { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://verceltics.site",
      lastModified: new Date(),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: "https://verceltics.site/privacy",
      lastModified: new Date("2026-04-03"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: "https://verceltics.site/terms",
      lastModified: new Date("2026-04-03"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
  ];
}
