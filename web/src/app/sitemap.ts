import type { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://www.verceltics.com",
      lastModified: new Date("2026-05-12"),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: "https://www.verceltics.com/privacy",
      lastModified: new Date("2026-05-08"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: "https://www.verceltics.com/terms",
      lastModified: new Date("2026-05-08"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
  ];
}
