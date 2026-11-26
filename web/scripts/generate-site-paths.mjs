import { readFile, writeFile } from "node:fs/promises";

const read = async name => JSON.parse(await readFile(new URL(`../src/content/${name}.json`, import.meta.url), "utf8"));
const [providers, workflows, posts] = await Promise.all([read("providers"), read("workflows"), read("blog")]);
// Update a page's date when its content changes, independently of build time.
const basePages = [
  { path: "/", updated: "2026-11-05" },
  { path: "/android", updated: "2026-11-05" },
  { path: "/vercel-analytics-ios", updated: "2026-11-05" },
  { path: "/integrations", updated: "2026-11-05" },
  { path: "/privacy", updated: "2026-11-05" },
  { path: "/terms", updated: "2026-11-05" },
  { path: "/guides", updated: "2026-11-05" },
  { path: "/blog", updated: "2026-11-05" },
];
const entries = [
  ...basePages,
  ...providers.flatMap(provider => [
    { path: `/integrations/${provider.slug}`, updated: provider.updated },
    { path: `/guides/connect-${provider.slug}`, updated: provider.updated },
    ...provider.tasks.map(task => ({ path: `/guides/${task.slug}`, updated: provider.updated })),
  ]),
  ...workflows.map(article => ({ path: `/guides/${article.slug}`, updated: article.updated })),
  ...posts.map(article => ({ path: `/blog/${article.slug}`, updated: article.updated })),
];
const paths = new Set();
for (const entry of entries) {
  if (paths.has(entry.path)) throw new Error(`Duplicate canonical route: ${entry.path}`);
  if (!/^\/(?:[a-z0-9-]+(?:\/[a-z0-9-]+)*)?$/.test(entry.path)) throw new Error(`Invalid route: ${entry.path}`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(entry.updated)) throw new Error(`Invalid modification date: ${entry.path}`);
  paths.add(entry.path);
}
await writeFile(new URL("../shared/site-paths.json", import.meta.url), `${JSON.stringify(entries, null, 2)}\n`);
console.log(`Generated ${entries.length} canonical page routes.`);
