import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { decodeHtml as decode } from "../shared/html-entities.mjs";
import paths from "../shared/site-paths.json" with { type: "json" };
import providers from "../src/content/providers.json" with { type: "json" };
import workflows from "../src/content/workflows.json" with { type: "json" };
import posts from "../src/content/blog.json" with { type: "json" };

const origin = "https://verceltics.com";
const root = new URL("../out/", import.meta.url);
const read = path => readFile(new URL(path === "/" ? "index.html" : `${path.slice(1)}.html`, root), "utf8");
const pages = new Map(await Promise.all(paths.map(async ({ path }) => [path, await read(path)])));
const titles = new Set();
const descriptions = new Set();
const contentHashes = new Set();
let links = 0;
let minimumArticleWords = Infinity;
const internal = new Map(paths.map(({ path }) => [path, new Set()]));
// CI uses a sparse website checkout. Verify source references against Git's tree
// without fetching native app blobs that the website does not need to build.
const trackedFiles = new Set(execFileSync("git", ["ls-tree", "-r", "--name-only", "HEAD"], {
  cwd: new URL("../../", import.meta.url), encoding: "utf8",
}).trim().split("\n"));

for (const [path, html] of pages) {
  const title = /<title>([\s\S]*?)<\/title>/.exec(html)?.[1];
  const description = /<meta name="description" content="([^"]+)"/.exec(html)?.[1];
  const canonical = /<link rel="canonical" href="([^"]+)"/.exec(html)?.[1];
  assert(title && !titles.has(title), `Missing or duplicate title: ${path}`);
  assert(description && !descriptions.has(description), `Missing or duplicate description: ${path}`);
  assert(canonical, `Missing canonical: ${path}`);
  assert.equal(new URL(canonical).href, `${origin}${path}`, `Canonical mismatch: ${path}`);
  assert.equal((html.match(/<h1(?:\s|>)/g) || []).length, 1, `Expected one H1: ${path}`);
  assert(!/<meta[^>]+content="[^"]*noindex/i.test(html), `Canonical page excluded from search: ${path}`);
  titles.add(title); descriptions.add(description);
  for (const match of html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)) {
    const value = JSON.parse(match[1]);
    assert.equal(value["@context"], "https://schema.org", `Invalid structured data: ${path}`);
    if (["BlogPosting", "TechArticle"].includes(value["@type"])) {
      assert.equal(value.mainEntityOfPage, `${origin}${path}`);
      assert(value.datePublished <= value.dateModified, `Invalid article dates: ${path}`);
    }
  }
  for (const match of html.matchAll(/<a\s[^>]*href="([^"]+)"/g)) {
    const href = decode(match[1]);
    const url = new URL(href, `${origin}${path}`);
    if (url.origin !== origin) continue;
    const target = decodeURIComponent(url.pathname);
    if (["/feed.xml"].includes(target)) continue;
    assert(pages.has(target), `Broken internal link: ${path} -> ${href}`);
    internal.get(path).add(target);
    if (url.hash) {
      const id = decodeURIComponent(url.hash.slice(1));
      assert(pages.get(target).includes(`id="${id}"`), `Missing anchor: ${path} -> ${href}`);
    }
    links++;
  }
  const body = /<article[^>]*data-article-body="true"[^>]*>([\s\S]*?)<\/article>/.exec(html)?.[1];
  if (body) {
    const text = decode(body.replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
    const words = text.split(/\s+/).length;
    assert(words >= 180, `Article needs more substance (${words} words): ${path}`);
    minimumArticleWords = Math.min(minimumArticleWords, words);
    const hash = createHash("sha256").update(text).digest("hex");
    assert(!contentHashes.has(hash), `Duplicate article body: ${path}`);
    contentHashes.add(hash);
  }
}
const reached = new Set(["/"]);
const queue = ["/"];
while (queue.length) for (const target of internal.get(queue.shift())) if (!reached.has(target)) { reached.add(target); queue.push(target); }
assert.equal(reached.size, pages.size, `Orphaned pages: ${[...pages.keys()].filter(path => !reached.has(path)).join(", ")}`);
const sitemap = await readFile(new URL("sitemap.xml", root), "utf8");
const locations = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map(match => decode(match[1]));
assert.deepEqual(new Set(locations), new Set(paths.map(({ path }) => `${origin}${path}`)), "Sitemap and page registry differ");
assert.equal(locations.length, paths.length, "Duplicate sitemap URLs");
const robots = await readFile(new URL("robots.txt", root), "utf8");
assert(robots.includes(`Sitemap: ${origin}/sitemap.xml`), "robots.txt must reference canonical sitemap");
const feed = await readFile(new URL("feed.xml", root), "utf8");
assert.equal((feed.match(/<item>/g) || []).length, posts.length, "RSS must include every blog post");
for (const post of posts) assert(feed.includes(`${origin}/blog/${post.slug}`), `RSS missing ${post.slug}`);
for (const provider of providers) {
  assert(trackedFiles.has(provider.source), `Unverified provider source: ${provider.source}`);
  assert(provider.overview.length >= 2 && provider.setup.length >= 4, `Incomplete provider: ${provider.slug}`);
  for (const task of provider.tasks) assert(task.steps.length >= 4 && task.verify && task.caution, `Incomplete task: ${task.slug}`);
}
for (const article of [...workflows, ...posts]) {
  assert(article.sections.length >= 3 && article.sources.length, `Incomplete editorial article: ${article.slug}`);
}
const exportedHtml = (await readdir(root, { recursive: true })).filter(path => path.endsWith(".html") && !path.startsWith("_not-found") && path !== "404.html");
assert.equal(exportedHtml.length, pages.size, "Exported HTML and sitemap page counts differ");
console.log(`Verified ${pages.size} pages, ${contentHashes.size} unique article bodies, ${links} internal links, canonical metadata, structured data, sitemap and ${posts.length} RSS items. Minimum article body: ${minimumArticleWords} words.`);
