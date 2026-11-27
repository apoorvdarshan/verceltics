import Link from "next/link";
import { notFound } from "next/navigation";
import { providerCatalog, providers } from "@/content/library";
import chapters from "@/content/reference.json";
import { breadcrumbs, pageMetadata, structuredData } from "@/lib/seo";

export const dynamicParams = false;
type Props = { params: Promise<{ segments: string[] }> };

export function generateStaticParams() {
  return chapters.flatMap(chapter => [
    { segments: [chapter.provider] },
    ...chapter.operations.map(operation => ({ segments: [chapter.provider, operation.slug] })),
  ]);
}

function lookup(segments: string[]) {
  const [slug, operationSlug] = segments;
  const chapter = chapters.find(item => item.provider === slug);
  const detail = providers.find(item => item.slug === slug);
  if (!chapter || !detail || segments.length > 2) notFound();
  const operation = chapter.operations.find(item => item.slug === operationSlug);
  if (operationSlug && !operation) notFound();
  const { provider } = providerCatalog(slug);
  const title = operation ? `${provider.name}: ${operation.title}` : `${provider.name} API reference`;
  return { chapter, detail, operation, provider, title, path: `/docs/${segments.join("/")}` };
}

export async function generateMetadata({ params }: Props) {
  const { title, path, chapter, operation } = lookup((await params).segments);
  return pageMetadata(title, operation?.note ?? chapter.intro, path);
}

export default async function ReferencePage({ params }: Props) {
  const { title, path, chapter, operation, detail, provider } = lookup((await params).segments);
  const chapterPath = `/docs/${chapter.provider}`;
  const trail = [{ name: "Documentation", path: "/docs" }, { name: provider.name, path: chapterPath }];
  if (operation) trail.push({ name: operation.title, path });
  return <main className="reading-main" id="main-content">
    <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(breadcrumbs(trail)) }} />
    <nav className="reading-breadcrumb" aria-label="Breadcrumb"><Link href="/docs">Documentation</Link><span>/</span><Link href={chapterPath}>{provider.name}</Link></nav>
    <header className="reading-header"><p className="instrument-label">Provider API reference</p><h1>{title}</h1><p className="reading-deck">{operation?.note ?? chapter.intro}</p></header>
    <div className="reading-body">
      {operation && <>
        <section><h2>Request</h2><p><code className="reference-route">GET {operation.path}</code></p>
          <p>Catalog operation: <code className="reference-route">{operation.id}</code></p>
          <p>{chapter.intro}</p>
        </section>
        <section><h2>Request parameters</h2>
          {operation.parameters.length === 0 ? <p>No parameters are listed for this operation in the bundled catalog. The provider connection still supplies authentication.</p> :
            <dl className="reference-inputs">{operation.parameters.map(param => <div key={`${param.location}:${param.name}`}>
              <dt><code>{param.name}</code> · {param.location} · {param.required ? "required" : "optional"}</dt>
              <dd>{param.type}{param.description ? ` — ${param.description}` : ""}{"enumValues" in param && param.enumValues?.length ? ` Allowed values: ${param.enumValues.join(", ")}.` : ""}</dd>
            </div>)}</dl>}
          {operation.permissions.length > 0 && <p>Provider permission options: {operation.permissions.join(", ")}</p>}
        </section>
      </>}
      <section><h2>{operation ? "Using this reference" : "Before your first request"}</h2>
        <p>{detail.limit}</p><p>Use the <Link href={`/guides/connect-${chapter.provider}`}>{provider.name} connection guide</Link> to set up credentials. In supported Pro API tools, look for the operation in the connected provider’s catalog. If your app version does not expose it, consult the <a href={detail.reference}>provider documentation</a>.</p>
      </section>
      <section><h2>{operation ? "Other lookups" : "Choose a lookup"}</h2><ul>{chapter.operations.filter(item => item.slug !== operation?.slug).map(item => <li key={item.slug}><Link href={`${chapterPath}/${item.slug}`}>{item.title}</Link>{!operation && <p>{item.note}</p>}</li>)}</ul></section>
      <section><h2>Related guides</h2><ul>{detail.tasks.map(task => <li key={task.slug}><Link href={`/guides/${task.slug}`}>{task.title}</Link></li>)}<li><Link href="/guides/safe-provider-api-explorer">Review a provider request before running it</Link></li></ul></section>
    </div>
  </main>;
}
