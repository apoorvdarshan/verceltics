import type { MetadataRoute } from "next";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://verceltics.com/",
      lastModified: new Date("2026-10-03"),
    },
    {
      url: "https://verceltics.com/privacy",
      lastModified: new Date("2026-10-03"),
    },
    {
      url: "https://verceltics.com/integrations",
      lastModified: new Date("2026-10-03"),
    },
    {
      url: "https://verceltics.com/vercel-analytics-ios",
      lastModified: new Date("2026-10-03"),
    },
    {
      url: "https://verceltics.com/terms",
      lastModified: new Date("2026-10-03"),
    },
  ];
}
