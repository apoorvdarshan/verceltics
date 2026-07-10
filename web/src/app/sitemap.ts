import type { MetadataRoute } from "next";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://www.verceltics.com",
      lastModified: new Date("2026-07-10"),
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: "https://www.verceltics.com/privacy",
      lastModified: new Date("2026-07-10"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
    {
      url: "https://www.verceltics.com/terms",
      lastModified: new Date("2026-07-09"),
      changeFrequency: "yearly",
      priority: 0.3,
    },
  ];
}
