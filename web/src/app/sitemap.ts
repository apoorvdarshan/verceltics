import type { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://verceltics.com",
      lastModified: new Date(),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: "https://verceltics.com/privacy",
      lastModified: new Date("2026-05-05"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: "https://verceltics.com/terms",
      lastModified: new Date("2026-05-05"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
  ];
}
