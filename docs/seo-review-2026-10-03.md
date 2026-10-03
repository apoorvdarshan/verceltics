# Search Console review — October 3, 2026

## Inputs and baseline

Reviewed the five exports supplied from Downloads. Raw exports are not copied
into the repository. The data ends before the export date:

| Export | Findings |
| --- | --- |
| Search performance | June 30–September 29: 31 clicks, 819 impressions, 3.79% CTR; impression-weighted daily average position approximately 16.31. |
| Page coverage | Latest snapshot September 21: 4 indexed, 13 not indexed. Reasons: 5 canonical alternates, 6 crawled but not indexed, 2 discovered but not indexed. |
| HTTPS | Latest snapshot September 28: 4 HTTPS URLs, 0 non-HTTPS URLs; issue table empty. |
| More sample links | 356 distinct linking URLs across 33 hostnames. |
| Latest links | The same 356 URLs, with crawl dates; this is not a second set of backlinks. |

Backlink samples include 226 App Store URLs and 61 OpenSSF Best Practices URLs.
These are linking-page counts, not 287 independent websites or proof of link
quality. There is no evidence here that removing or disavowing links would help.

### Pages with the clearest opportunity

| Page | Clicks | Impressions | CTR | Average position |
| --- | ---: | ---: | ---: | ---: |
| `https://verceltics.com/` | 24 | 212 | 11.32% | 7.32 |
| `/vercel-analytics-ios` | 4 | 406 | 0.99% | 22.67 |
| `https://www.verceltics.com/` | 2 | 154 | 1.30% | 10.55 |
| `/integrations` | 0 | 20 | 0% | 6.40 |

The brand query `verceltics` has 10 clicks from 44 impressions, at position 1.98.
Relevant non-brand queries include `vercel analytics` (10 impressions),
`vercel analytics api` (7), and `vercel ios` (4). These small samples guide
content choices; they do not support ranking or traffic forecasts. Query and
page aggregates can differ from daily chart totals, so they are not summed
to reconstruct the overall traffic baseline.

## Live observations before the changes

- HTTPS apex, HTTPS `www`, and HTTP apex homepages returned HTTP 200 with the
  same content. Canonical tags pointed to the apex, but redirects did not
  consolidate the duplicate entry points.
- `/privacy/` and `/privacy.html` returned temporary 307 redirects to `/privacy`.
- The sitemap contained five canonical pages, all with July 19 modification
  dates despite subsequent content and policy changes.
- The Vercel landing page displayed a Cloudflare traffic screenshot. Its
  wording had limited setup, permission, plan-limit, and API information.

The duplicate-host behavior is a technical weakness we can correct. The export
does not identify the eight unindexed URLs, so it cannot prove that these
duplicates caused their exclusion. Canonical alternates are often expected,
and the HTTPS export does not establish an HTTPS failure.

## Changes implemented

1. Permanent 301 redirects consolidate public GET/HEAD requests on HTTP or
   `www` onto `https://verceltics.com`, preserving paths and query parameters.
   Known HTML route aliases (`.html`, `/index.html`, trailing slash) normalize
   in the same hop. Missing paths retain normal 404 behavior. Local development
   hosts and signed Discord POST requests are not redirected.
2. Cloudflare now invokes the Worker before static asset delivery so website
   redirects actually run. Canonical requests delegate to the asset binding.
3. Sitemap and homepage canonical URLs agree, including the root slash.
   `lastmod` records October 3 for the five pages changed in this update and the
   preceding documentation update. Future dates should change only with a
   significant page update, never automatically on every deployment.
4. Homepage description and application identity are clearer. Software schema
   identifies Verceltics honestly and retains the public App Store version 2.0;
   the prepared 2.1 source build remains marked as a preview.
5. The Vercel page has a focused title/description, visible breadcrumbs, setup
   steps, supported reports, troubleshooting, purchase/plan limits, mobile
   dashboard context, and links to official Vercel setup/API documentation.
   The hosting-selector screenshot is accurately labeled.
6. The homepage and Vercel integration card link contextually to the landing
   page. The landing page links back to the provider directory and privacy page.
7. Visible questions remain, but redundant FAQ rich-result markup was removed.
   WebPage, breadcrumb, application, organization, and integration-list data
   remain. No invented reviews, ratings, or ranking promises were added.

## Search Console follow-up after deployment

1. Use `https://verceltics.com/sitemap.xml` as the canonical sitemap submission.
   The old `www` sitemap entry can be removed from the report after checking
   that it redirects successfully; removing it does not remove pages from Google.
2. Inspect the homepage, `/vercel-analytics-ios`, and `/integrations`, run the
   live URL check, and request indexing once for each updated canonical page.
3. Export the affected URL tables for both unindexed reasons. Check those URLs
   individually before changing content, redirects, or indexing directives.
   This summary export lacks those URL tables.
4. Recheck host/canonical consolidation as Google recrawls. Do not request that
   duplicate `www`, HTTP, or `.html` pages be separately indexed.
5. Compare the Vercel page's impressions, clicks, CTR and query positions over
   the next 28 days against the baseline above. Indexing and ranking decisions
   remain Google's; deployment does not guarantee inclusion or faster rankings.

## Primary references

- [Google: consolidate duplicate URLs](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls)
- [Google: build and submit a sitemap](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap)
- [Google: search documentation updates, including FAQ rich-result retirement](https://developers.google.com/search/updates)
- [Cloudflare: run the Worker before static assets](https://developers.cloudflare.com/workers/static-assets/routing/worker-script/)
- [Vercel: Web Analytics setup](https://vercel.com/docs/analytics/quickstart)
- [Vercel: Web Analytics API](https://vercel.com/docs/analytics/web-analytics-api)
