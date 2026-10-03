import { DISCORD_INTERACTIONS_PATH, handleDiscordInteractionsRequest } from "./discord-interactions.mjs";
import { announceReleases } from "./discord-announcements.mjs";
import { canonicalSiteRedirect } from "./site-routing.mjs";

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
    border: "#242C36",
    grid: "#222A35",
    text: "#F7F8FA",
    muted: "#8D95A5",
  },
  light: {
    background: "#FFFFFF",
    border: "#E1E6ED",
    grid: "#E9EDF3",
    text: "#141820",
    muted: "#6D7482",
  },
};

export default {
  async fetch(request, env, context) {
    const redirect = canonicalSiteRedirect(request);
    if (redirect) return redirect;
    const url = new URL(request.url);

    if (url.pathname === DISCORD_INTERACTIONS_PATH) {
      return handleDiscordInteractionsRequest(request, env, context);
    }

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
    cacheUrl.search = `?theme=${themeName}&v=6`;
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

  scheduled(_event, env, context) {
    context.waitUntil(announceReleases(env));
  },
};

export async function fetchStarHistory(token, fetchImplementation = fetch) {
  try {
    return await fetchGraphqlStarHistory(token, fetchImplementation);
  } catch {
    // GitHub restricts individual stargazer listings. Its public history API
    // still provides daily counts without exposing users or requiring a token.
    return fetchAggregateStarHistory(fetchImplementation);
  }
}

async function fetchAggregateStarHistory(fetchImplementation) {
  const stars = [];
  for (let page = 1; page <= 100; page += 1) {
    const response = await fetchImplementation(
      `https://api.github.com/repos/${OWNER}/${REPOSITORY}/stargazers/history?per_page=30&page=${page}`,
      {
        headers: {
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2026-03-10",
          "User-Agent": "verceltics-star-history",
        },
      },
    );
    if (!response.ok) {
      throw new Error(`GitHub star history REST API returned ${response.status}`);
    }
    const payload = await response.json();
    if (!Array.isArray(payload)) {
      throw new Error("GitHub returned unexpected star history data");
    }
    for (const week of payload) {
      if (!Number.isSafeInteger(week?.week) || week.week < 0 ||
          !Number.isFinite(new Date(week.week * 1000).getTime()) ||
          !Array.isArray(week.days) || week.days.length !== 7 ||
          week.days.some((count) => !Number.isSafeInteger(count) || count < 0)) {
        throw new Error("GitHub returned unexpected star history data");
      }
      for (const [day, count] of week.days.entries()) {
        if (stars.length + count > 10000) {
          throw new Error("Star history exceeded the sample safety limit");
        }
        const date = new Date((week.week + day * 86400) * 1000).toISOString();
        for (let index = 0; index < count; index += 1) stars.push(date);
      }
    }
    if (!response.headers.get("Link")?.includes('rel="next"')) {
      return stars.sort((left, right) => left.localeCompare(right));
    }
  }
  throw new Error("Star history exceeded the pagination safety limit");
}

async function fetchGraphqlStarHistory(token, fetchImplementation) {
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
  const height = 360;
  const plot = { left: 64, top: 112, right: 904, bottom: 282 };
  const now = Date.now();
  const parsedDates = starredAtValues
    .map((value) => new Date(value).getTime())
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const earliestStar = parsedDates[0] ?? now;
  const oneDay = 24 * 60 * 60 * 1000;
  const rangeStart = Math.min(earliestStar - oneDay, now - 30 * oneDay);
  const rangeEnd = Math.max(now, parsedDates.at(-1) ?? now, rangeStart + oneDay);
  const maxStars = Math.max(parsedDates.length, 1);
  const yMax = niceMaximum(maxStars * 1.12);
  const x = (timestamp) =>
    plot.left +
    ((timestamp - rangeStart) / (rangeEnd - rangeStart)) *
      (plot.right - plot.left);
  const y = (count) =>
    plot.bottom - (count / yMax) * (plot.bottom - plot.top);
  const samples = [[rangeStart, 0]];
  let total = 0;
  for (const timestamp of parsedDates) {
    total += 1;
    if (samples.at(-1)[0] === timestamp) samples.at(-1)[1] = total;
    else samples.push([timestamp, total]);
  }
  if (samples.at(-1)[0] < rangeEnd) samples.push([rangeEnd, total]);
  const linePoints = samples.map(([timestamp, count]) => [
    x(timestamp),
    y(count),
  ]);
  const linePath = linePoints.map(([px, py], index) =>
    `${index === 0 ? "M" : "L"}${px.toFixed(2)} ${py.toFixed(2)}`,
  ).join(" ");
  const areaPath =
    `${linePath} L${plot.right} ${plot.bottom} ` +
    `L${plot.left} ${plot.bottom} Z`;
  const yTicks = tickValues(yMax, 3);
  const xTicks = dateTicks(rangeStart, rangeEnd, 4);
  const currentStars = parsedDates.length;
  const currentX = x(rangeEnd);
  const currentY = y(currentStars);

  const yGrid = yTicks
    .map((value) => {
      const yPosition = y(value);
      return `
        <line x1="${plot.left}" y1="${yPosition}" x2="${plot.right}" y2="${yPosition}" class="grid" />
        <text x="${plot.left - 18}" y="${yPosition + 4}" text-anchor="end" class="axis">${value}</text>`;
    })
    .join("");
  const xLabels = xTicks
    .map((timestamp, index) => {
      const anchor = index === 0 ? "start" : index === xTicks.length - 1 ? "end" : "middle";
      return `<text x="${x(timestamp)}" y="${plot.bottom + 28}" text-anchor="${anchor}" class="axis">${formatDate(timestamp, rangeEnd - rangeStart)}</text>`;
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
      <stop offset="0%" stop-color="#6177FF" stop-opacity="0.15" />
      <stop offset="72%" stop-color="#7B69FF" stop-opacity="0.035" />
      <stop offset="100%" stop-color="#B65CFF" stop-opacity="0" />
    </linearGradient>
    <clipPath id="plot-clip">
      <rect x="${plot.left}" y="${plot.top - 12}" width="${plot.right - plot.left}" height="${plot.bottom - plot.top + 12}" />
    </clipPath>
    <style>
      .axis { fill: ${theme.muted}; font: 400 12px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .grid { stroke: ${theme.grid}; stroke-width: 1; }
      .label { fill: ${theme.text}; font: 650 21px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
      .muted { fill: ${theme.muted}; font: 400 12px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    </style>
  </defs>
  <rect x="0.5" y="0.5" width="${width - 1}" height="${height - 1}" rx="16" fill="${theme.background}" stroke="${theme.border}" />
  <g transform="translate(32 29)" fill="none" stroke-width="2.5" stroke-linecap="round">
    <path d="M2 5 H11 C20 5 20 20 29 20 H37" stroke="#1687FF" />
    <path d="M2 18 H15" stroke="${theme.text}" />
    <path d="M2 31 H13 C22 31 22 27 28 23" stroke="#B65CFF" />
    <circle cx="2" cy="5" r="1.6" fill="#1687FF" stroke="none" />
    <circle cx="2" cy="18" r="1.6" fill="${theme.text}" stroke="none" />
    <circle cx="2" cy="31" r="1.6" fill="#B65CFF" stroke="none" />
    <circle cx="37" cy="20" r="1.6" fill="#1687FF" stroke="none" />
  </g>
  <text x="84" y="46" class="label">Verceltics</text>
  <text x="84" y="66" class="muted">Open source, growing together</text>
  <rect x="782" y="27" width="146" height="44" rx="12" fill="${themeName === "dark" ? "#151C26" : "#F4F7FB"}" />
  <path d="M805 39 L808 46 L816 47 L810 52 L812 60 L805 56 L798 60 L800 52 L794 47 L802 46 Z" fill="none" stroke="${themeName === "dark" ? "#B7A1FF" : "#7955C8"}" stroke-width="1.6" stroke-linejoin="round" />
  <text x="827" y="55" fill="${theme.text}" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif" font-size="23" font-weight="650">${currentStars}<tspan dx="7" fill="${theme.muted}" font-size="13" font-weight="400">stars</tspan></text>
  ${yGrid}
  ${xLabels}
  <g clip-path="url(#plot-clip)">
    <path d="${areaPath}" fill="url(#area-gradient)" />
    <path d="${linePath}" fill="none" stroke="url(#line-gradient)" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" />
  </g>
  <circle cx="${currentX}" cy="${currentY}" r="7" fill="#B65CFF" opacity="0.12" />
  <circle cx="${currentX}" cy="${currentY}" r="3.5" fill="#B65CFF" stroke="${theme.background}" stroke-width="1.5" />
  <text x="32" y="337" class="muted">Cumulative GitHub stars</text>
  <text x="928" y="337" text-anchor="end" class="muted">Updated ${formatDate(now, 0)}</text>
</svg>`;
}

export function niceMaximum(value) {
  if (value <= 5) return 5;

  const exponent = 10 ** Math.floor(Math.log10(value));
  const fraction = value / exponent;
  const niceFraction = [1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].find(
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
