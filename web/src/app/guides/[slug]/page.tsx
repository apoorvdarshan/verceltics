import { notFound } from "next/navigation";
import { ArticleShell } from "@/components/content/article-shell";
import { guides } from "@/content/library";
import { pageMetadata } from "@/lib/seo";

export const dynamicParams = false;
export function generateStaticParams() { return guides.map(article => ({ slug: article.slug })); }
export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const article = guides.find(item => item.slug === slug);
  if (!article) notFound();
  return pageMetadata(article.title, article.description, `/guides/${slug}`, true);
}
export default async function GuidePage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const article = guides.find(item => item.slug === slug);
  if (!article) notFound();
  return <ArticleShell article={article} collection="guides" />;
}
