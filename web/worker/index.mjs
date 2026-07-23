const OWNER = "apoorvdarshan";
const REPOSITORY = "verceltics";
const CACHE_SECONDS = 6 * 60 * 60;
const STAR_HISTORY_QUERY = `
  query StarHistory($owner: String!, $repository: String!, $cursor: String) {
    repository(owner: $owner, name: $repository) {
      stargazers(first: 100, after: $cursor) {
        edges {
          starredAt
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
  }
`;

const THEMES = {
  dark: {
    background: "#0D1117",
    border: "#30363D",
    grid: "#21262D",
    text: "#F0F6FC",
    muted: "#8B949E",
  },
  light: {
    background: "#FFFFFF",
    border: "#D0D7DE",
    grid: "#D8DEE4",
    text: "#1F2328",
    muted: "#656D76",
  },
};

export default {
  async fetch(request, env, context) {
    const url = new URL(request.url);

    if (url.pathname !== "/api/star-history.svg") {
      return env.ASSETS.fetch(request);
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", {
        status: 405,
        headers: { Allow: "GET, HEAD" },
      });
    }

    const themeName = url.searchParams.get("theme") === "dark" ? "dark" : "light";
    const cacheUrl = new URL(url);
    cacheUrl.search = `?theme=${themeName}&v=1`;
    const cacheKey = new Request(cacheUrl.toString(), { method: "GET" });
    const cache = caches.default;
    const cached = await cache.match(cacheKey);

    if (cached) {
      return request.method === "HEAD"
        ? new Response(null, { status: cached.status, headers: cached.headers })
        : cached;
    }

    try {
      const stars = await fetchStarHistory(env.GITHUB_TOKEN);
      const svg = renderStarHistorySvg(stars, themeName);
      const response = svgResponse(svg, CACHE_SECONDS);

      context.waitUntil(cache.put(cacheKey, response.clone()));

      return request.method === "HEAD"
        ? new Response(null, { status: response.status, headers: response.headers })
        : response;
    } catch (error) {
      console.error("Unable to render star history", error);
      const response = svgResponse(renderErrorSvg(themeName), 60, 503);

      return request.method === "HEAD"
        ? new Response(null, { status: response.status, headers: response.headers })
        : response;
    }
  },
};

export async function fetchStarHistory(token, fetchImplementation = fetch) {
  if (!token) {
    throw new Error("GITHUB_TOKEN is not configured");
  }

  const stars = [];
  let cursor = null;
  let page = 0;

  while (true) {
    const response = await fetchImplementation("https://api.github.com/graphql", {
      method: "POST",
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        "User-Agent": "verceltics-star-history",
      },
      body: JSON.stringify({
        query: STAR_HISTORY_QUERY,
        variables: {
          owner: OWNER,
          repository: REPOSITORY,
          cursor,
        },
      }),
    });

    if (!response.ok) {
      throw new Error(`GitHub returned ${response.status}`);
    }

    const payload = await response.json();
    const connection = payload?.data?.repository?.stargazers;

    if (Array.isArray(payload?.errors) && payload.errors.length > 0) {
      throw new Error("GitHub GraphQL returned an error");
    }

    if (!connection || !Array.isArray(connection.edges)) {
      throw new Error("GitHub returned an unexpected response");
    }

    for (const edge of connection.edges) {
      if (typeof edge.starredAt === "string") {
        stars.push(edge.starredAt);
      }
    }

    if (!connection.pageInfo?.hasNextPage) {
      break;
    }

    page += 1;
    cursor = connection.pageInfo.endCursor;

    if (!cursor || page > 100) {
      throw new Error("Star history exceeded the pagination safety limit");
    }
  }

  return stars.sort((left, right) => left.localeCompare(right));
}

export function renderStarHistorySvg(starredAtValues, themeName = "light") {
  const theme = THEMES[themeName] ?? THEMES.light;
  const width = 960;
  const height = 560;
  const plot = { left: 84, top: 108, right: 912, bottom: 470 };
  const now = Date.now();
  const parsedDates = starredAtValues
    .map((value) => new Date(value).getTime())
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const earliestStar = parsedDates[0] ?? now;
  const oneDay = 24 * 60 * 60 * 1000;
  const rangeStart = Math.min(earliestStar - oneDay, now - 30 * oneDay);
  const rangeEnd = Math.max(now, rangeStart + oneDay);
  const maxStars = Math.max(parsedDates.length, 1);
  const yMax = niceMaximum(maxStars);
  const x = (timestamp) =>
    plot.left +
    ((timestamp - rangeStart) / (rangeEnd - rangeStart)) *
      (plot.right - plot.left);
  const y = (count) =>
    plot.bottom - (count / yMax) * (plot.bottom - plot.top);

  const linePoints = [[rangeStart, 0]];

  parsedDates.forEach((timestamp, index) => {
    linePoints.push([timestamp, index]);
    linePoints.push([timestamp, index + 1]);
  });

  linePoints.push([rangeEnd, parsedDates.length]);

  const linePath = linePoints
    .map(([timestamp, count], index) => {
      const command = index === 0 ? "M" : "L";
      return `${command}${x(timestamp).toFixed(2)} ${y(count).toFixed(2)}`;
    })
    .join(" ");
  const areaPath =
    `${linePath} L${plot.right} ${plot.bottom} ` +
    `L${plot.left} ${plot.bottom} Z`;
  const yTicks = tickValues(yMax, 5);
  const xTicks = dateTicks(rangeStart, rangeEnd, 5);
  const currentStars = parsedDates.length;
  const currentX = x(rangeEnd);
  const currentY = y(currentStars);

  const yGrid = yTicks
    .map((value) => {
      const yPosition = y(value);
      return `
        <line x1="${plot.left}" y1="${yPosition}" x2="${plot.right}" y2="${yPosition}" class="grid" />
        <text x="${plot.left - 18}" y="${yPosition + 5}" text-anchor="end" class="axis">${value}</text>`;
    })
    .join("");
  const xLabels = xTicks
    .map((timestamp, index) => {
      const anchor = index === 0 ? "start" : index === xTicks.length - 1 ? "end" : "middle";
      return `<text x="${x(timestamp)}" y="${plot.bottom + 38}" text-anchor="${anchor}" class="axis">${formatDate(timestamp, rangeEnd - rangeStart)}</text>`;
    })
    .join("");

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title description">
  <title id="title">Verceltics GitHub star history</title>
  <desc id="description">${currentStars} GitHub stars over time for ${OWNER}/${REPOSITORY}.</desc>
  <defs>
    <linearGradient id="line-gradient" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#1687FF" />
      <stop offset="100%" stop-color="#B65CFF" />
    </linearGradient>
    <linearGradient id="area-gradient" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#1687FF" stop-opacity="0.24" />
      <stop offset="100%" stop-color="#B65CFF" stop-opacity="0.02" />
    </linearGradient>
    <filter id="point-glow" x="-100%" y="-100%" width="300%" height="300%">
      <feGaussianBlur stdDeviation="5" result="blur" />
      <feMerge>
        <feMergeNode in="blur" />
        <feMergeNode in="SourceGraphic" />
      </feMerge>
    </filter>
    <style>
      .axis { fill: ${theme.muted}; font: 500 14px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .grid { stroke: ${theme.grid}; stroke-width: 1; }
      .label { fill: ${theme.text}; font: 650 17px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .muted { fill: ${theme.muted}; font: 500 14px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    </style>
  </defs>
  <rect x="0.5" y="0.5" width="${width - 1}" height="${height - 1}" rx="12" fill="${theme.background}" stroke="${theme.border}" />
  <g transform="translate(48 34)">
    <path d="M0 4 C11 4 14 18 26 18 H44" fill="none" stroke="#1687FF" stroke-width="4" stroke-linecap="round" />
    <path d="M0 18 H22" fill="none" stroke="${theme.text}" stroke-width="4" stroke-linecap="round" />
    <path d="M0 32 H16 C27 32 29 22 38 22" fill="none" stroke="#B65CFF" stroke-width="4" stroke-linecap="round" />
  </g>
  <text x="112" y="54" class="label">${OWNER}/${REPOSITORY}</text>
  <text x="112" y="78" class="muted">GitHub star history</text>
  <text x="${plot.right}" y="58" text-anchor="end" fill="${theme.text}" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif" font-size="30" font-weight="700">${currentStars}</text>
  <text x="${plot.right}" y="80" text-anchor="end" class="muted">stars</text>
  ${yGrid}
  ${xLabels}
  <path d="${areaPath}" fill="url(#area-gradient)" />
  <path d="${linePath}" fill="none" stroke="url(#line-gradient)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
  <circle cx="${currentX}" cy="${currentY}" r="6" fill="#B65CFF" stroke="${theme.background}" stroke-width="3" filter="url(#point-glow)" />
  <line x1="${plot.left}" y1="${plot.bottom}" x2="${plot.right}" y2="${plot.bottom}" stroke="${theme.border}" />
</svg>`;
}

export function niceMaximum(value) {
  if (value <= 5) return 5;

  const exponent = 10 ** Math.floor(Math.log10(value));
  const fraction = value / exponent;
  const niceFraction = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;

  return niceFraction * exponent;
}

function tickValues(maximum, count) {
  return Array.from({ length: count }, (_, index) =>
    Math.round((maximum / (count - 1)) * index),
  );
}

function dateTicks(start, end, count) {
  return Array.from(
    { length: count },
    (_, index) => start + ((end - start) / (count - 1)) * index,
  );
}

function formatDate(timestamp, range) {
  return new Intl.DateTimeFormat("en", {
    month: "short",
    ...(range >= 365 * 24 * 60 * 60 * 1000
      ? { year: "numeric" }
      : { day: "numeric" }),
    timeZone: "UTC",
  }).format(new Date(timestamp));
}

function svgResponse(svg, cacheSeconds, status = 200) {
  return new Response(svg, {
    status,
    headers: {
      "Cache-Control": `public, max-age=3600, s-maxage=${cacheSeconds}, stale-if-error=86400`,
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'",
      "Content-Type": "image/svg+xml; charset=utf-8",
      "Cross-Origin-Resource-Policy": "cross-origin",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function renderErrorSvg(themeName) {
  const theme = THEMES[themeName] ?? THEMES.light;

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="960" height="180" viewBox="0 0 960 180" role="img" aria-label="Star history is temporarily unavailable">
  <rect x="0.5" y="0.5" width="959" height="179" rx="12" fill="${theme.background}" stroke="${theme.border}" />
  <text x="48" y="78" fill="${theme.text}" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif" font-size="24" font-weight="650">Star history is refreshing</text>
  <text x="48" y="116" fill="${theme.muted}" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif" font-size="17">The cached chart will return shortly.</text>
</svg>`;
}
