import Link from "next/link";
import { ContentDirectory } from "@/components/content/content-directory";
import { providerCatalog } from "@/content/library";
import chapters from "@/content/reference.json";
import { breadcrumbs, pageMetadata, structuredData } from "@/lib/seo";

export const metadata = pageMetadata("Verceltics documentation: accounts, dashboards and API reference", "Learn to connect your first provider, read mobile dashboards and look up request paths and parameters in the native provider API catalogs.", "/docs");

export default function DocumentationPage() {
  const entries = chapters.flatMap(chapter => {
    const { provider } = providerCatalog(chapter.provider);
    const path = `/docs/${chapter.provider}`;
    return [
      { title: `${provider.name} API reference`, description: chapter.intro, category: provider.name, path },
      ...chapter.operations.map(operation => ({ title: `${provider.name}: ${operation.title}`, description: operation.note, category: provider.name, path: `${path}/${operation.slug}` })),
    ];
  });
  return <main className="library-main" id="main-content">
    <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(breadcrumbs([{ name: "Documentation", path: "/docs" }])) }} />
    <header className="library-header">
      <p className="instrument-label">Documentation</p><h1>Use Verceltics with your web stack.</h1>
      <p>New here? Start with sample data or choose one of the 27 integrations. If you already have a connection, look up a resource below to find its request path, inputs and interpretation notes.</p>
      <nav className="library-quicklinks" aria-label="App documentation">
        <Link href="/guides/try-verceltics-with-sample-data">Try the app</Link>
        <Link href="/integrations">Connect a provider</Link>
        <Link href="/guides/vercel-web-analytics-on-mobile">Read Vercel Analytics</Link>
        <Link href="/guides/provider-authentication-permission-errors">Troubleshoot access</Link>
        <Link href="/guides/cached-data-refresh-and-offline">Understand cached data</Link>
        <Link href="/guides">All task guides</Link>
      </nav>
    </header>
    <h2>Provider API reference</h2><ContentDirectory entries={entries} />
  </main>;
}
