import assert from "node:assert/strict";
import test from "node:test";

import {
  fetchStarHistory,
  monotoneCurvePath,
  niceMaximum,
  renderStarHistorySvg,
} from "./index.mjs";

test("niceMaximum creates readable chart ceilings", () => {
  assert.equal(niceMaximum(0), 5);
  assert.equal(niceMaximum(5), 5);
  assert.equal(niceMaximum(6), 10);
  assert.equal(niceMaximum(22), 25);
  assert.equal(niceMaximum(101), 125);
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
  assert.match(light, /fill="#FBFCFE"/);
  assert.match(dark, /fill="#090A0E"/);
  assert.match(dark, /linearGradient id="line-gradient"/);
  assert.match(dark, / C[\d.]+ [\d.]+/);
});

test("monotoneCurvePath renders a smooth cubic curve without sharp steps", () => {
  const path = monotoneCurvePath([
    [0, 100],
    [50, 80],
    [100, 80],
    [150, 40],
  ]);

  assert.match(path, /^M0\.00 100\.00 C/);
  assert.equal((path.match(/ C/g) ?? []).length, 3);
  assert.doesNotMatch(path, / L/);
});

test("fetchStarHistory paginates and sorts timestamps", async () => {
  const firstPage = Array.from({ length: 100 }, (_, index) => ({
    starredAt: `2026-06-${String((index % 28) + 1).padStart(2, "0")}T00:00:00Z`,
  }));
  const responses = [
    new Response(
      JSON.stringify({
        data: {
          repository: {
            stargazers: {
              edges: firstPage,
              pageInfo: { hasNextPage: true, endCursor: "next-page" },
            },
          },
        },
      }),
    ),
    new Response(
      JSON.stringify({
        data: {
          repository: {
            stargazers: {
              edges: [{ starredAt: "2026-05-01T00:00:00Z" }],
              pageInfo: { hasNextPage: false, endCursor: null },
            },
          },
        },
      }),
    ),
  ];
  const requests = [];
  const fakeFetch = async (url, options) => {
    requests.push({ url, options });
    return responses.shift();
  };

  const stars = await fetchStarHistory("test-token", fakeFetch);

  assert.equal(requests.length, 2);
  assert.equal(requests[1].url, "https://api.github.com/graphql");
  assert.equal(requests[0].options.headers.Authorization, "Bearer test-token");
  assert.equal(
    JSON.parse(requests[1].options.body).variables.cursor,
    "next-page",
  );
  assert.equal(stars[0], "2026-05-01T00:00:00Z");
  assert.equal(stars.length, 101);
});
