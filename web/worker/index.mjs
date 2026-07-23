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
    background: "#090A0E",
    border: "#272A35",
    grid: "#252833",
    text: "#F7F8FA",
    muted: "#8D95A5",
  },
  light: {
    background: "#FBFCFE",
    border: "#D9DEE8",
    grid: "#E3E7EF",
    text: "#141820",
    muted: "#6D7482",
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
    cacheUrl.search = `?theme=${themeName}&v=3`;
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
  const height = 520;
  const plot = { left: 76, top: 138, right: 904, bottom: 424 };
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
  const samples = cumulativeSamples(parsedDates, rangeStart, rangeEnd, 32);
  const linePoints = samples.map(([timestamp, count]) => [
    x(timestamp),
    y(count),
  ]);
  const linePath = monotoneCurvePath(linePoints);
  const areaPath =
    `${linePath} L${plot.right} ${plot.bottom} ` +
    `L${plot.left} ${plot.bottom} Z`;
  const yTicks = tickValues(yMax, 6);
  const xTicks = dateTicks(rangeStart, rangeEnd, 4);
  const currentStars = parsedDates.length;
  const currentX = x(rangeEnd);
  const currentY = y(currentStars);

  const yGrid = yTicks
    .map((value) => {
      const yPosition = y(value);
      return `
        <line x1="${plot.left}" y1="${yPosition}" x2="${plot.right}" y2="${yPosition}" class="grid" />
        <text x="${plot.left - 16}" y="${yPosition + 5}" text-anchor="end" class="axis">${value}</text>`;
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
      <stop offset="52%" stop-color="#557BFF" />
      <stop offset="100%" stop-color="#B65CFF" />
    </linearGradient>
    <linearGradient id="area-gradient" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="#6177FF" stop-opacity="0.26" />
      <stop offset="72%" stop-color="#7B69FF" stop-opacity="0.06" />
      <stop offset="100%" stop-color="#B65CFF" stop-opacity="0" />
    </linearGradient>
    <radialGradient id="ambient-glow" cx="82%" cy="15%" r="68%">
      <stop offset="0%" stop-color="#B65CFF" stop-opacity="${themeName === "dark" ? "0.11" : "0.07"}" />
      <stop offset="55%" stop-color="#1687FF" stop-opacity="${themeName === "dark" ? "0.04" : "0.025"}" />
      <stop offset="100%" stop-color="#1687FF" stop-opacity="0" />
    </radialGradient>
    <filter id="curve-glow" x="-15%" y="-35%" width="130%" height="170%">
      <feGaussianBlur stdDeviation="7" result="blur" />
      <feMerge>
        <feMergeNode in="blur" />
        <feMergeNode in="SourceGraphic" />
      </feMerge>
    </filter>
    <clipPath id="plot-clip">
      <rect x="${plot.left}" y="${plot.top - 12}" width="${plot.right - plot.left}" height="${plot.bottom - plot.top + 12}" />
    </clipPath>
    <style>
      .axis { fill: ${theme.muted}; font: 500 13px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .grid { stroke: ${theme.grid}; stroke-width: 1; stroke-dasharray: 2 8; stroke-linecap: round; }
      .label { fill: ${theme.text}; font: 670 17px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .muted { fill: ${theme.muted}; font: 500 13px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .eyebrow { fill: ${theme.muted}; font: 650 11px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; letter-spacing: 1.8px; }
    </style>
  </defs>
  <rect x="0.5" y="0.5" width="${width - 1}" height="${height - 1}" rx="20" fill="${theme.background}" stroke="${theme.border}" />
  <rect x="1" y="1" width="${width - 2}" height="${height - 2}" rx="19" fill="url(#ambient-glow)" />
  <g transform="translate(42 32)">
    <rect width="48" height="48" rx="14" fill="${themeName === "dark" ? "#111521" : "#F0F4FB"}" stroke="${theme.border}" />
    <path d="M10 13 C20 13 21 25 31 25 H38" fill="none" stroke="#1687FF" stroke-width="3.4" stroke-linecap="round" />
    <path d="M10 24 H27" fill="none" stroke="${theme.text}" stroke-width="3.4" stroke-linecap="round" />
    <path d="M10 35 H21 C29 35 30 29 36 29" fill="none" stroke="#B65CFF" stroke-width="3.4" stroke-linecap="round" />
  </g>
  <text x="108" y="50" class="label">${OWNER}/${REPOSITORY}</text>
  <text x="108" y="72" class="muted">Star momentum on GitHub</text>
  <text x="${plot.right}" y="41" text-anchor="end" class="eyebrow">CURRENT</text>
  <text x="${plot.right}" y="76" text-anchor="end" fill="${theme.text}" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif" font-size="34" font-weight="730">${currentStars}<tspan dx="8" fill="${theme.muted}" font-size="15" font-weight="550">STARS</tspan></text>
  <line x1="${plot.left}" y1="106" x2="${plot.right}" y2="106" stroke="${theme.border}" />
  ${yGrid}
  ${xLabels}
  <g clip-path="url(#plot-clip)">
    <path d="${areaPath}" fill="url(#area-gradient)" />
    <path d="${linePath}" fill="none" stroke="url(#line-gradient)" stroke-width="4.5" stroke-linecap="round" filter="url(#curve-glow)" opacity="0.42" />
    <path d="${linePath}" fill="none" stroke="url(#line-gradient)" stroke-width="4.5" stroke-linecap="round" />
  </g>
  <circle cx="${currentX}" cy="${currentY}" r="12" fill="#B65CFF" opacity="0.13" />
  <circle cx="${currentX}" cy="${currentY}" r="6.5" fill="#B65CFF" stroke="${theme.background}" stroke-width="3" />
  <line x1="${plot.left}" y1="${plot.bottom}" x2="${plot.right}" y2="${plot.bottom}" stroke="${theme.border}" />
</svg>`;
}

export function niceMaximum(value) {
  if (value <= 5) return 5;

  const exponent = 10 ** Math.floor(Math.log10(value));
  const fraction = value / exponent;
  const niceFraction = [1, 1.25, 2, 2.5, 5, 10].find(
    (candidate) => candidate >= fraction,
  );

  return niceFraction * exponent;
}

export function monotoneCurvePath(points) {
  if (points.length === 0) return "";
  if (points.length === 1) {
    return `M${points[0][0].toFixed(2)} ${points[0][1].toFixed(2)}`;
  }

  const segmentWidths = [];
  const segmentSlopes = [];

  for (let index = 0; index < points.length - 1; index += 1) {
    const width = points[index + 1][0] - points[index][0];
    segmentWidths.push(width);
    segmentSlopes.push(
      width === 0 ? 0 : (points[index + 1][1] - points[index][1]) / width,
    );
  }

  const tangents = new Array(points.length);
  tangents[0] = segmentSlopes[0];
  tangents[points.length - 1] = segmentSlopes.at(-1);

  for (let index = 1; index < points.length - 1; index += 1) {
    const previousSlope = segmentSlopes[index - 1];
    const nextSlope = segmentSlopes[index];

    if (previousSlope === 0 || nextSlope === 0 || previousSlope * nextSlope < 0) {
      tangents[index] = 0;
      continue;
    }

    const previousWidth = segmentWidths[index - 1];
    const nextWidth = segmentWidths[index];
    const previousWeight = 2 * nextWidth + previousWidth;
    const nextWeight = nextWidth + 2 * previousWidth;
    tangents[index] =
      (previousWeight + nextWeight) /
      (previousWeight / previousSlope + nextWeight / nextSlope);
  }

  for (let index = 0; index < segmentSlopes.length; index += 1) {
    const slope = segmentSlopes[index];

    if (slope === 0) {
      tangents[index] = 0;
      tangents[index + 1] = 0;
      continue;
    }

    const startRatio = tangents[index] / slope;
    const endRatio = tangents[index + 1] / slope;
    const magnitude = Math.hypot(startRatio, endRatio);

    if (magnitude > 3) {
      const scale = 3 / magnitude;
      tangents[index] = scale * startRatio * slope;
      tangents[index + 1] = scale * endRatio * slope;
    }
  }

  let path = `M${points[0][0].toFixed(2)} ${points[0][1].toFixed(2)}`;

  for (let index = 0; index < points.length - 1; index += 1) {
    const [startX, startY] = points[index];
    const [endX, endY] = points[index + 1];
    const controlWidth = (endX - startX) / 3;

    path +=
      ` C${(startX + controlWidth).toFixed(2)} ${(startY + tangents[index] * controlWidth).toFixed(2)}` +
      ` ${(endX - controlWidth).toFixed(2)} ${(endY - tangents[index + 1] * controlWidth).toFixed(2)}` +
      ` ${endX.toFixed(2)} ${endY.toFixed(2)}`;
  }

  return path;
}

function cumulativeSamples(starredAtValues, start, end, count) {
  let starIndex = 0;

  return dateTicks(start, end, count).map((timestamp) => {
    while (
      starIndex < starredAtValues.length &&
      starredAtValues[starIndex] <= timestamp
    ) {
      starIndex += 1;
    }

    return [timestamp, starIndex];
  });
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
