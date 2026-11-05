import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { StoreButtons } from "@/components/store-buttons";
import { providerCatalog, providerSources, providers } from "@/content/library";
import { breadcrumbs, pageMetadata, structuredData } from "@/lib/seo";

export const dynamicParams = false;
export function generateStaticParams() { return providers.map(detail => ({ slug: detail.slug })); }
export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  if (!providers.some(item => item.slug === slug)) notFound();
  const { provider } = providerCatalog(slug);
  return pageMetadata(`${provider.name} on iPhone, iPad & Android`, `${provider.summary} with the ${provider.name} integration in Verceltics. Read supported capabilities, connection requirements and practical mobile guides.`, `/integrations/${slug}`);
}
export default async function ProviderPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const detail = providers.find(item => item.slug === slug);
  if (!detail) notFound();
  const { provider, group } = providerCatalog(slug);
  const sources = providerSources(detail);
  const screenshot = group.id === "hosting" ? "hosting" : group.id === "registrars" ? "registrars" : "sites";
  return <div className="site-shell"><SiteHeader /><main className="reading-main" id="main-content">
    <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: structuredData(breadcrumbs([{ name: "Integrations", path: "/integrations" }, { name: provider.name, path: `/integrations/${slug}` }])) }} />
    <nav className="reading-breadcrumb" aria-label="Breadcrumb"><Link href="/">Home</Link><span>/</span><Link href="/integrations">Integrations</Link><span>/</span><span>{provider.name}</span></nav>
    <header className="reading-header provider-reading-header"><div><p className="instrument-label">{group.label} integration</p><h1>{provider.name} on iPhone, iPad and Android.</h1><p className="reading-deck">{provider.summary}. Independent native access through Verceltics, with your provider account and permissions.</p><Link className="primary-control" href={`/guides/connect-${slug}`}>Set up {provider.name} <span aria-hidden="true">→</span></Link></div><Image alt="" src={`/providers/${provider.icon}`} width={96} height={96} /></header>
    <div className="provider-reading-layout"><article className="reading-body" data-article-body="true">
      <section><h2>What this integration is for</h2>{detail.overview.map(text => <p key={text}>{text}</p>)}</section>
      <section><h2>Supported resource checks</h2><ul>{detail.capabilities.map(text => <li key={text}>{text}</li>)}</ul><p>{detail.limit}</p></section>
      <section><h2>Connection requirements</h2><p><strong>Connection method:</strong> {provider.connection}. Prepare access for the intended provider account. Start with only the permissions your workflow needs and verify a known resource after connecting.</p><ul>{detail.fields.map(field => <li key={field}>{field}</li>)}</ul><p><Link href={`/guides/connect-${slug}`}>Read the {provider.name} setup and troubleshooting guide →</Link></p></section>
      <section><h2>Practical {provider.name} guides</h2>{detail.tasks.map(task => <div key={task.slug}><h3><Link href={`/guides/${task.slug}`}>{task.title}</Link></h3><p>{task.summary}</p></div>)}</section>
      <section><h2>Native app and provider boundaries</h2><p>Provider requests go directly from the device to the service. Credentials are protected by the iOS Keychain or Android Keystore-backed encryption. Verceltics does not operate a provider-data proxy or combine accounts into one analytics total.</p><p>Connections, list browsing, search and refresh stay free. Pro unlocks supported details and provider tools. Operations can differ by platform and app version; use the provider dashboard for a task the installed app does not expose.</p></section>
      <section className="reading-references"><h2>References</h2><ul>{sources.map(source => <li key={source.url}><a href={source.url} rel="noreferrer" target="_blank">{source.label} ↗</a></li>)}</ul></section>
    </article><aside className="provider-reading-aside"><Image alt={`The ${group.label} connection workspace in Verceltics on iPhone`} src={`/screens/ios/${screenshot}.webp`} width={300} height={650} /><p>The {group.label} workspace on iPhone. Layouts differ on iPad and Android.</p><Link href={`/integrations#${group.id}`}>All {group.label.toLowerCase()} integrations →</Link></aside></div>
    <section className="reading-download"><div><h2>Use your existing provider account.</h2><p>Verceltics is independent and is not affiliated with or endorsed by {provider.name}.</p></div><StoreButtons /></section>
  </main><SiteFooter /></div>;
}
