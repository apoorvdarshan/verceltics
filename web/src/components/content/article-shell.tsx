import Link from "next/link";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { StoreButtons } from "@/components/store-buttons";
import { SITE_URL, breadcrumbs, structuredData } from "@/lib/seo";
import type { Article } from "@/content/types";
import { blogPosts, guides, providerCatalog } from "@/content/library";

function relatedLabel(path: string) {
  if (path.startsWith("/guides/")) return guides.find(item => `/guides/${item.slug}` === path)?.title;
  if (path.startsWith("/blog/")) return blogPosts.find(item => `/blog/${item.slug}` === path)?.title;
  if (path.startsWith("/integrations/")) return `${providerCatalog(path.split("/")[2]).provider.name} integration`;
  if (path.startsWith("/privacy")) return "Privacy and data handling";
  return "Explore all integrations";
}

export function ArticleShell({ article, collection }: { article: Article; collection: "guides" | "blog" }) {
  const path = `/${collection}/${article.slug}`;
  const label = collection === "blog" ? "Blog" : "Guides";
  const schema = {
    "@context": "https://schema.org", "@type": collection === "blog" ? "BlogPosting" : "TechArticle",
    "@id": `${SITE_URL}${path}#article`, headline: article.title, description: article.description,
    datePublished: article.published, dateModified: article.updated, inLanguage: "en",
    mainEntityOfPage: `${SITE_URL}${path}`,
    author: { "@type": "Person", name: "Apoorv Darshan", url: "https://apoorvdarshan.com" },
    publisher: { "@id": `${SITE_URL}/#organization` },
  };
  return <div className="site-shell">
    <SiteHeader />
    <main className="reading-main" id="main-content">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(schema) }} />
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(breadcrumbs([{ name: label, path: `/${collection}` }, { name: article.title, path }])) }} />
      <nav className="reading-breadcrumb" aria-label="Breadcrumb"><Link href="/">Home</Link><span>/</span><Link href={`/${collection}`}>{label}</Link><span>/</span><span>{article.category}</span></nav>
      <header className="reading-header">
        <p className="instrument-label">{article.category}</p>
        <h1>{article.title}</h1><p className="reading-deck">{article.description}</p>
        <p className="reading-meta">By Apoorv Darshan · Updated <time dateTime={article.updated}>{new Date(`${article.updated}T12:00:00Z`).toLocaleDateString("en-US", { month: "long", day: "numeric", year: "numeric", timeZone: "UTC" })}</time></p>
      </header>
      <div className="reading-layout">
        <nav className="reading-toc" aria-label="On this page"><strong>On this page</strong>{article.sections.map((section, index) => <a key={section.heading} href={`#section-${index + 1}`}>{section.heading}</a>)}</nav>
        <article className="reading-body" data-article-body="true">
          {article.sections.map((section, index) => <section id={`section-${index + 1}`} key={section.heading}>
            <h2>{section.heading}</h2>
            {section.paragraphs?.map(text => <p key={text}>{text}</p>)}
            {section.items ? <ol>{section.items.map(text => <li key={text}>{text}</li>)}</ol> : null}
          </section>)}
          <section className="reading-references"><h2>References</h2><p>Product instructions reflect the supported app flows. Provider access and available operations depend on your permissions, account plan and app version.</p><ul>{article.sources.map(source => <li key={source.url}><a href={source.url} target="_blank" rel="noreferrer">{source.label} ↗</a></li>)}</ul></section>
        </article>
      </div>
      <aside className="reading-next"><h2>Continue exploring</h2><ul>{article.related.map(path => <li key={path}><Link href={path}>{relatedLabel(path)}</Link></li>)}</ul><Link href={`/${collection}`}>Browse all {label.toLowerCase()} →</Link></aside>
      <section className="reading-download"><div><h2>Your web stack, on your phone.</h2><p>Verceltics is an independent native app for iPhone, iPad and Android. Account connections and browsing are free; Pro unlocks details and provider tools.</p></div><StoreButtons /></section>
    </main><SiteFooter />
  </div>;
}
