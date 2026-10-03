const CANONICAL_HOST = "verceltics.com";
const SITE_HOSTS = new Set([CANONICAL_HOST, `www.${CANONICAL_HOST}`]);
const HTML_ROUTES = new Set(["/", "/integrations", "/vercel-analytics-ios", "/privacy", "/terms"]);

/** Consolidate public GET/HEAD URLs without redirecting signed Discord POSTs. */
export function canonicalSiteRedirect(request) {
  const url = new URL(request.url);
  if (!SITE_HOSTS.has(url.hostname) || !["GET", "HEAD"].includes(request.method)) {
    return null;
  }

  let pathname = url.pathname;
  if (pathname.endsWith("/index.html")) {
    pathname = pathname.slice(0, -"/index.html".length) || "/";
  } else if (pathname.endsWith(".html")) {
    pathname = pathname.slice(0, -".html".length);
  } else if (pathname !== "/" && pathname.endsWith("/")) {
    pathname = pathname.slice(0, -1);
  }
  // Preserve missing routes and asset paths so they retain normal 404 behavior.
  if (!HTML_ROUTES.has(pathname)) pathname = url.pathname;

  if (url.protocol === "https:" && url.hostname === CANONICAL_HOST && pathname === url.pathname) {
    return null;
  }
  url.protocol = "https:";
  url.hostname = CANONICAL_HOST;
  url.port = "";
  url.pathname = pathname;
  return new Response(null, {
    status: 301,
    headers: { Location: url.toString(), "Cache-Control": "public, max-age=86400" },
  });
}
