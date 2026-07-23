import assert from "node:assert/strict";
import test from "node:test";

import {
  fetchStarHistory,
  niceMaximum,
  renderStarHistorySvg,
} from "./index.mjs";

test("niceMaximum creates readable chart ceilings", () => {
  assert.equal(niceMaximum(0), 5);
  assert.equal(niceMaximum(5), 5);
  assert.equal(niceMaximum(6), 10);
  assert.equal(niceMaximum(22), 50);
  assert.equal(niceMaximum(101), 200);
});

test("renderStarHistorySvg creates matching light and dark charts", () => {
  const stars = [
    "2026-05-01T00:00:00Z",
    "2026-05-03T00:00:00Z",
    "2026-06-01T00:00:00Z",
  ];
  const light = renderStarHistorySvg(stars, "light");
  const dark = renderStarHistorySvg(stars, "dark");

  assert.match(light, /3 GitHub stars over time/);
  assert.match(light, /fill="#FFFFFF"/);
  assert.match(dark, /fill="#0D1117"/);
  assert.match(dark, /linearGradient id="line-gradient"/);
});

test("fetchStarHistory paginates and sorts timestamps", async () => {
  const firstPage = Array.from({ length: 100 }, (_, index) => ({
    starred_at: `2026-06-${String((index % 28) + 1).padStart(2, "0")}T00:00:00Z`,
  }));
  const responses = [
    new Response(JSON.stringify(firstPage)),
    new Response(JSON.stringify([{ starred_at: "2026-05-01T00:00:00Z" }])),
  ];
  const requests = [];
  const fakeFetch = async (url, options) => {
    requests.push({ url, options });
    return responses.shift();
  };

  const stars = await fetchStarHistory("test-token", fakeFetch);

  assert.equal(requests.length, 2);
  assert.match(requests[1].url, /page=2/);
  assert.equal(requests[0].options.headers.Authorization, "Bearer test-token");
  assert.equal(stars[0], "2026-05-01T00:00:00Z");
  assert.equal(stars.length, 101);
});
