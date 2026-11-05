import { notFound } from "next/navigation";
import { ArticleShell } from "@/components/content/article-shell";
import { blogPosts } from "@/content/library";
import { pageMetadata } from "@/lib/seo";

export const dynamicParams = false;
export function generateStaticParams() { return blogPosts.map(article => ({ slug: article.slug })); }
export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const article = blogPosts.find(item => item.slug === slug);
  if (!article) notFound();
  return pageMetadata(article.title, article.description, `/blog/${slug}`, true);
}
export default async function BlogPost({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const article = blogPosts.find(item => item.slug === slug);
  if (!article) notFound();
  return <ArticleShell article={article} collection="blog" />;
}
