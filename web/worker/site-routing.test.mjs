import assert from "node:assert/strict";
import test from "node:test";
import paths from "../shared/site-paths.json" with { type: "json" };
import { canonicalSiteRedirect } from "./site-routing.mjs";

test("every canonical HTML route consolidates host, protocol and file variants in one redirect", () => {
  for (const { path } of paths) {
    assert.equal(canonicalSiteRedirect(new Request(`https://verceltics.com${path}`)), null);
    const alternatives = path === "/" ? ["/index.html"] : [`${path}/`, `${path}.html`, `${path}/index.html`];
    for (const variant of alternatives) {
      const response = canonicalSiteRedirect(new Request(`http://www.verceltics.com${variant}?utm_source=test`));
      assert.equal(response.status, 301);
      assert.equal(response.headers.get("Location"), `https://verceltics.com${path}?utm_source=test`);
    }
  }
});

test("routing preserves unknown routes, static assets, local preview and signed POST bodies", () => {
  assert.equal(canonicalSiteRedirect(new Request("https://verceltics.com/missing.html")), null);
  assert.equal(canonicalSiteRedirect(new Request("https://verceltics.com/missing/")), null);
  assert.equal(canonicalSiteRedirect(new Request("https://verceltics.com/icon.png")), null);
  assert.equal(canonicalSiteRedirect(new Request("http://localhost:8788/android/")), null);
  assert.equal(canonicalSiteRedirect(new Request("http://www.verceltics.com/api/discord/interactions", { method: "POST", body: "signed" })), null);
  const unknown = canonicalSiteRedirect(new Request("https://www.verceltics.com/missing.html"));
  assert.equal(unknown.headers.get("Location"), "https://verceltics.com/missing.html");
});
