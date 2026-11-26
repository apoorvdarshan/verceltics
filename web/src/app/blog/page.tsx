import Link from "next/link";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { ContentDirectory } from "@/components/content/content-directory";
import { blogPosts } from "@/content/library";
import { breadcrumbs, pageMetadata, structuredData } from "@/lib/seo";

export const metadata = { ...pageMetadata("Verceltics Blog: Releases and Web Operations", "Read Verceltics releases, mobile infrastructure workflows, analytics explanations and technical notes on provider credentials, domains and deployments.", "/blog"), alternates: { canonical: "https://verceltics.com/blog", types: { "application/rss+xml": "https://verceltics.com/feed.xml" } } };
export default function BlogPage() {
  return <div className="site-shell"><SiteHeader /><main className="library-main" id="main-content">
    <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(breadcrumbs([{ name: "Blog", path: "/blog" }])) }} />
    <header className="library-header"><p className="instrument-label">Verceltics blog</p><h1>Releases, architecture and web operations.</h1><p>Practical explanations for checking your web stack from a phone, understanding provider reports and keeping account boundaries clear.</p><nav className="library-quicklinks" aria-label="Blog resources"><a href="/feed.xml">Subscribe with RSS</a><Link href="/guides">Setup and task guides</Link><Link href="/blog/verceltics-android-public-release">Android launch</Link></nav></header>
    <ContentDirectory entries={blogPosts.map(article => ({ title: article.title, description: article.description, category: article.category, path: `/blog/${article.slug}` }))} />
  </main><SiteFooter /></div>;
}
