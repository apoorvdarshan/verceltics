import type { MetadataRoute } from "next";
import paths from "../../shared/site-paths.json";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  return paths.map(({ path, updated }) => ({
    url: `https://verceltics.com${path}`,
    lastModified: new Date(`${updated}T00:00:00Z`),
  }));
}
