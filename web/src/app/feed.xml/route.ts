import { blogPosts } from "@/content/library";
import { SITE_URL } from "@/lib/seo";

export const dynamic = "force-static";
function xml(value: string) { return value.replace(/[<>&"']/g, character => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" })[character]!); }
export function GET() {
  const latest = blogPosts.map(post => post.updated).sort().at(-1)!;
  const items = blogPosts.map(post => {
    const url = `${SITE_URL}/blog/${post.slug}`;
    return `<item><title>${xml(post.title)}</title><link>${xml(url)}</link><guid isPermaLink="true">${xml(url)}</guid><description>${xml(post.description)}</description><pubDate>${new Date(`${post.published}T12:00:00Z`).toUTCString()}</pubDate><category>${xml(post.category)}</category></item>`;
  }).join("");
  const feed = `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Verceltics Blog</title><link>${SITE_URL}/blog</link><description>Releases, architecture and mobile web operations.</description><language>en</language><lastBuildDate>${new Date(`${latest}T12:00:00Z`).toUTCString()}</lastBuildDate><atom:link href="${SITE_URL}/feed.xml" rel="self" type="application/rss+xml"/>${items}</channel></rss>`;
  return new Response(feed, { headers: { "Content-Type": "application/rss+xml; charset=utf-8" } });
}
