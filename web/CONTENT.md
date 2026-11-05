# Website content

The website exports provider references, setup and task guides, workflow guides,
and blog articles as static HTML. No external content service or AI request runs
when a reader opens a page.

- `src/data/integrations.ts` owns provider names, categories and connection summaries.
- `src/content/providers.json` owns each provider's capabilities, required fields,
  setup, limitations, implementation reference and task guides.
- `src/content/workflows.json` owns cross-provider guides.
- `src/content/blog.json` owns dated editorial posts and release articles.
- `src/content/library.ts` composes the setup/task articles for the reading templates.

Create a page only for a distinct reader task. Verify product instructions against
the native clients and connection forms, link supporting references, and preserve
platform limitations. Do not create device or keyword-order copies of the same
article. Do not publish private Search Console exports or account data here.

Each content record has explicit publication and modification dates. Change the
modification date when its content changes; do not replace it with the build date.
For the original pages and library hubs, maintain their dates in
`scripts/generate-site-paths.mjs` when the corresponding page changes.

Run `npm run build`. It regenerates `shared/site-paths.json`, builds the static
pages, and verifies the output. The generated registry is shared by the XML sitemap
and the Worker's canonical redirects, so new content must enter both together.
Commit the generated registry with the content. `feed.xml` automatically includes
every blog post; it is an RSS resource rather than an HTML sitemap entry.

The export checks cover unique titles/descriptions, canonical URLs, one H1,
JSON-LD, internal links and anchors, orphan pages, distinct article bodies, source
paths, sitemap coverage and RSS membership. The article-length guard only catches
obvious omissions; passing it does not establish editorial quality or search value.
Review the actual content and rendered desktop/mobile pages before publishing.

The build checks do not prove external provider links are reachable, that provider
accounts are configured, or that Google has crawled or indexed new content. Verify
the public deployment and inspect Search Console separately. An already submitted
sitemap does not need repeated submission whenever the content changes.
