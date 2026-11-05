import Link from "next/link";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { ContentDirectory } from "@/components/content/content-directory";
import { guides } from "@/content/library";
import { breadcrumbs, pageMetadata, structuredData } from "@/lib/seo";

export const metadata = pageMetadata("Setup and Task Guides for Your Web Stack", "Connect hosting, registrar and analytics accounts. Find practical Verceltics guides for deployments, DNS, search, performance and uptime on iOS and Android.", "/guides");
export default function GuidesPage() {
  return <div className="site-shell"><SiteHeader /><main className="library-main" id="main-content">
    <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(breadcrumbs([{ name: "Guides", path: "/guides" }])) }} />
    <header className="library-header"><p className="instrument-label">Setup and operations</p><h1>Guides for hosting, domains and site services.</h1><p>Start with a provider connection, investigate a specific resource or follow a workflow across your web stack. Each guide explains what to check and what the result can tell you.</p><nav className="library-quicklinks" aria-label="Start here"><Link href="/guides/try-verceltics-with-sample-data">Try sample data</Link><Link href="/guides/mobile-website-incident-triage">Investigate an incident</Link><Link href="/integrations">Find your provider</Link><Link href="/blog">Read the blog</Link></nav></header>
    <ContentDirectory entries={guides.map(article => ({ title: article.title, description: article.description, category: article.category, path: `/guides/${article.slug}` }))} />
  </main><SiteFooter /></div>;
}
