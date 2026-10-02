const assert = require("node:assert/strict");
const test = require("node:test");
const fc = require("fast-check");

const workerModule = import("./index.mjs");
const NOW = Date.UTC(2026, 9, 2);
const EARLIEST = Date.UTC(2008, 0, 1);
const FUZZ_RUNS = { numRuns: 250 };
const TOKEN = "fuzz-test-credential-not-a-real-secret";
const timestamp = fc
  .integer({ min: EARLIEST, max: NOW })
  .map((value) => new Date(value).toISOString());
const untrustedString = fc.oneof(
  fc.string({ maxLength: 96 }),
  fc.constantFrom(
    '</text><script>alert("fuzz")</script>',
    '"><image href="https://attacker.invalid/pixel" onload="alert(1)"/>',
    '</style><foreignObject><iframe src="javascript:alert(1)"/></foreignObject>',
    '<!DOCTYPE svg [<!ENTITY probe SYSTEM "file:///etc/passwd">]>&probe;',
    '"}) { viewer { login } } #',
    "__proto__",
    "constructor",
    "dark&theme=light&token=leak",
    "\u0000\u202e\ud800&<>'\"",
  ),
);
// GitHub timestamps are finite dates in the repository's lifetime. Keep invalid
// strings for parser/injection coverage without fuzzing Date's overflow limits.
const chartValue = fc.oneof(
  timestamp,
  untrustedString.filter((value) => {
    const parsed = new Date(value).getTime();
    return !Number.isFinite(parsed) || (parsed >= EARLIEST && parsed <= NOW);
  }),
);

function githubPage(edges, hasNextPage = false, endCursor = null) {
  return {
    data: {
      repository: {
        stargazers: { edges, pageInfo: { hasNextPage, endCursor } },
      },
    },
  };
}

function assertSafeSvg(svg) {
  assert.match(svg, /^<\?xml[^>]+>\s*<svg\b/);
  assert.match(svg, /<\/svg>$/);
  assert.doesNotMatch(
    svg,
    /<\/?(?:script|image|foreignObject|iframe|a)\b|<!DOCTYPE|<!ENTITY|\bon\w+\s*=|javascript:/i,
  );
  assert.doesNotMatch(svg, /\b(?:NaN|Infinity|undefined)\b/);
  for (const [, path] of svg.matchAll(/\bd="([^"]*)"/g)) {
    assert.match(path, /^[MLCHVZ0-9.,\s-]+$/);
    for (const coordinate of path.match(/-?\d+(?:\.\d+)?/g) ?? []) {
      assert.ok(Number.isFinite(Number(coordinate)));
    }
  }
  for (const [, coordinate] of svg.matchAll(
    /\b(?:x|y|x1|y1|x2|y2|cx|cy|r|width|height)="([^"]+)"/g,
  )) {
    assert.ok(Number.isFinite(Number(coordinate.replace(/%$/, ""))));
  }
}

function installCache(t, cache) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, "caches");
  Object.defineProperty(globalThis, "caches", {
    configurable: true,
    value: { default: cache },
  });
  t.after(() => {
    if (previous) Object.defineProperty(globalThis, "caches", previous);
    else delete globalThis.caches;
  });
}

test("fuzz: chart inputs cannot inject SVG and chart coordinates stay finite", async (t) => {
  const { renderStarHistorySvg } = await workerModule;
  t.mock.method(Date, "now", () => NOW);
  fc.assert(
    fc.property(
      fc.array(chartValue, { maxLength: 100 }),
      fc.constantFrom("light", "dark"),
      (values, theme) => {
        const svg = renderStarHistorySvg(values, theme);
        const validDates = values
          .map((value) => new Date(value).getTime())
          .filter(Number.isFinite);
        assertSafeSvg(svg);
        assert.match(svg, new RegExp(`${validDates.length} GitHub stars over time`));
        // Equivalent safe timestamps must produce exactly the same document;
        // no original input text may survive into markup or CSS.
        assert.equal(
          svg,
          renderStarHistorySvg(validDates.map((value) => new Date(value).toISOString()), theme),
        );
        assert.equal(svg, renderStarHistorySvg([...values].reverse(), theme));
      },
    ),
    FUZZ_RUNS,
  );
});

test("fuzz: pagination keeps cursors in variables and credentials at GitHub", async () => {
  const { fetchStarHistory } = await workerModule;
  await fc.assert(
    fc.asyncProperty(
      untrustedString.filter((value) => value.length > 0),
      fc.array(chartValue, { maxLength: 30 }),
      fc.array(chartValue, { maxLength: 30 }),
      async (cursor, first, second) => {
        const requests = [];
        const pages = [
          githubPage(first.map((starredAt) => ({ starredAt })), true, cursor),
          githubPage(second.map((starredAt) => ({ starredAt }))),
        ];
        const result = await fetchStarHistory(TOKEN, async (url, options) => {
          assert.ok(requests.length < 2, "pagination must stop after the final page");
          requests.push({ url, options });
          return new Response(JSON.stringify(pages[requests.length - 1]));
        });
        assert.equal(requests.length, 2);
        const bodies = requests.map(({ url, options }) => {
          assert.equal(url, "https://api.github.com/graphql");
          assert.equal(options.method, "POST");
          assert.equal(options.headers.Authorization, `Bearer ${TOKEN}`);
          assert.ok(!options.body.includes(TOKEN));
          return JSON.parse(options.body);
        });
        assert.equal(bodies[0].query, bodies[1].query);
        assert.match(bodies[0].query, /after: \$cursor/);
        assert.deepEqual(bodies[0].variables, {
          owner: "apoorvdarshan", repository: "verceltics", cursor: null,
        });
        assert.deepEqual(bodies[1].variables, { ...bodies[0].variables, cursor });
        assert.deepEqual(result, [...first, ...second].sort((a, b) => a.localeCompare(b)));
      },
    ),
    FUZZ_RUNS,
  );
});

test("fuzz: query strings share only normalized theme cache keys for GET and HEAD", async (t) => {
  const { default: worker } = await workerModule;
  t.mock.method(Date, "now", () => NOW);
  let scenario;
  installCache(t, {
    async match(key) {
      scenario.matches.push(key);
      return scenario.hit ? new Response("cached-chart", {
        headers: { "Content-Type": "image/svg+xml" },
      }) : undefined;
    },
    async put(key, response) { scenario.writes.push({ key, response }); },
  });
  t.mock.method(globalThis, "fetch", async (url, options) => {
    scenario.requests.push({ url, options });
    return new Response(JSON.stringify(githubPage([])));
  });
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.oneof(untrustedString, fc.constant("dark")), { maxLength: 4 }),
      untrustedString,
      fc.constantFrom("GET", "HEAD"),
      fc.boolean(),
      async (themes, extra, method, hit) => {
        scenario = { hit, matches: [], writes: [], requests: [] };
        const url = new URL("https://verceltics.app/api/star-history.svg");
        for (const theme of themes) url.searchParams.append("theme", theme);
        url.searchParams.set("upstream", extra);
        url.searchParams.set("v", extra);
        const pending = [];
        const response = await worker.fetch(new Request(url, { method }), {
          GITHUB_TOKEN: TOKEN,
          ASSETS: { fetch: () => assert.fail("chart request must not reach assets") },
        }, { waitUntil: (promise) => pending.push(promise) });
        await Promise.all(pending);
        const expectedTheme = themes[0] === "dark" ? "dark" : "light";
        const expectedKey = `https://verceltics.app/api/star-history.svg?theme=${expectedTheme}&v=3`;
        assert.equal(scenario.matches.length, 1);
        assert.equal(scenario.matches[0].url, expectedKey);
        assert.equal(scenario.matches[0].method, "GET");
        assert.equal(response.status, 200);
        const body = await response.text();
        if (method === "HEAD") assert.equal(body, "");
        else if (hit) assert.equal(body, "cached-chart");
        else assertSafeSvg(body);
        assert.equal(scenario.requests.length, hit ? 0 : 1);
        assert.equal(scenario.writes.length, hit ? 0 : 1);
        if (!hit) {
          assert.equal(scenario.requests[0].url, "https://api.github.com/graphql");
          assert.equal(scenario.requests[0].options.headers.Authorization, `Bearer ${TOKEN}`);
          assert.equal(scenario.writes[0].key.url, expectedKey);
          assert.equal(scenario.writes[0].key.method, "GET");
          const cachedSvg = await scenario.writes[0].response.text();
          assertSafeSvg(cachedSvg);
          assert.ok(cachedSvg.includes(expectedTheme === "dark" ? 'fill="#090A0E"' : 'fill="#FBFCFE"'));
          assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
          assert.equal(response.headers.get("Content-Security-Policy"), "default-src 'none'; style-src 'unsafe-inline'");
        }
      },
    ),
    FUZZ_RUNS,
  );
});

test("fuzz: malformed upstream shapes return an uncached safe 503 SVG", async (t) => {
  const { default: worker } = await workerModule;
  const json = fc.jsonValue({ maxDepth: 3, depthSize: "small" });
  const malformedPayload = fc.oneof(
    json.filter((value) => !Array.isArray(value?.data?.repository?.stargazers?.edges)),
    json.filter((value) => !Array.isArray(value)).map((edges) => githubPage(edges)),
    json.filter((value) => !Array.isArray(value?.stargazers?.edges))
      .map((value) => ({ data: { repository: value } })),
    fc.array(json, { minLength: 1, maxLength: 5 }).map((errors) => ({ ...githubPage([]), errors })),
    fc.constant(githubPage([null])),
    fc.constant(githubPage([], true, "")),
  );
  let payload;
  let requests;
  installCache(t, {
    match: async () => undefined,
    put: () => assert.fail("upstream failures must not be cached"),
  });
  t.mock.method(console, "error", () => {});
  t.mock.method(globalThis, "fetch", async (url) => {
    assert.equal(url, "https://api.github.com/graphql");
    requests += 1;
    assert.ok(requests <= 1, "malformed payload must fail on its first page");
    return new Response(JSON.stringify(payload));
  });
  await fc.assert(
    fc.asyncProperty(malformedPayload, untrustedString, fc.constantFrom("GET", "HEAD"), async (value, theme, method) => {
      payload = value;
      requests = 0;
      const url = new URL("https://verceltics.app/api/star-history.svg");
      url.searchParams.set("theme", theme);
      const response = await worker.fetch(new Request(url, { method }), { GITHUB_TOKEN: TOKEN }, {
        waitUntil: () => assert.fail("no cache work expected for an upstream failure"),
      });
      assert.equal(response.status, 503);
      assert.equal(requests, 1);
      assert.match(response.headers.get("Content-Type"), /^image\/svg\+xml/);
      assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
      const body = await response.text();
      assert.ok(!body.includes(TOKEN));
      if (method === "HEAD") assert.equal(body, "");
      else {
        assertSafeSvg(body);
        assert.match(body, /Star history is refreshing/);
      }
    }),
    FUZZ_RUNS,
  );
});
