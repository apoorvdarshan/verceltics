import assert from "node:assert/strict";
import test from "node:test";
import { decodeHtml } from "../shared/html-entities.mjs";

test("static HTML attributes decode once without promoting nested entities", () => {
  assert.equal(decodeHtml("/guide?a=1&amp;b=2"), "/guide?a=1&b=2");
  assert.equal(decodeHtml("&lt;value&gt; &quot;hello&quot; &#39;a&#x27;"), '<value> "hello" \'a\'');
  assert.equal(decodeHtml("&amp;lt;script&amp;gt;"), "&lt;script&gt;");
  assert.equal(decodeHtml("&amp;amp;"), "&amp;");
  assert.equal(decodeHtml("Verceltics → iPhone"), "Verceltics → iPhone");
});
