// Only scheduled invocations use this module. Credentials and a channel must be
// configured before it does any work; Android additionally requires a Play key.
const APP_STORE_ID = 6761645656;
const PACKAGE_NAME = "com.apoorvdarshan.verceltics";
const APP_STORE_URL = `https://apps.apple.com/app/id${APP_STORE_ID}`;
const PLAY_URL = `https://play.google.com/store/apps/details?id=${PACKAGE_NAME}&hl=en&gl=US`;
const PLAY_API = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}`;
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const USER_AGENT = "Mozilla/5.0 (compatible; Vercie/1.0; +https://verceltics.com)";
const STATE_PREFIX = "vercie:releases:v1";

class AnnouncementError extends Error {
  constructor(code) {
    super(code);
    this.name = "AnnouncementError";
  }
}

/** Check both stores independently. Missing configuration leaves this disabled. */
export async function announceReleases(env) {
  const token = String(env.DISCORD_BOT_TOKEN || "").trim();
  const channel = String(env.DISCORD_ANNOUNCEMENTS_CHANNEL_ID || "").trim();
  if (!token || !/^\d{17,20}$/.test(channel) || !env.DISCORD_STATE) return;

  const outcomes = await Promise.allSettled([
    announceIOS(env, token, channel),
    env.PLAY_SERVICE_ACCOUNT_JSON
      ? announceAndroid(env, token, channel)
      : Promise.resolve(),
  ]);
  outcomes.forEach((outcome, index) => {
    if (outcome.status === "rejected") {
      // Never log upstream bodies, credentials, or arbitrary exception text.
      console.error("Vercie release check failed", {
        platform: index === 0 ? "ios" : "android",
        code: outcome.reason instanceof AnnouncementError
          ? outcome.reason.message
          : "unexpected_failure",
      });
    }
  });
}

async function announceIOS(env, token, channel) {
  const body = await request(
    `https://itunes.apple.com/lookup?id=${APP_STORE_ID}&country=us`,
    { headers: { "User-Agent": USER_AGENT, Accept: "application/json" } },
    "appstore_lookup",
  );
  const release = body.results?.find(
    (result) => result.trackId === APP_STORE_ID && result.bundleId === PACKAGE_NAME,
  );
  const version = plainText(release?.version).slice(0, 80);
  if (!version) return;

  const baselineKey = `${STATE_PREFIX}:ios:initialized`;
  const versionKey = `${STATE_PREFIX}:ios:seen:${encodeURIComponent(version)}`;
  const baseline = await env.DISCORD_STATE.get(baselineKey);
  if (!baseline) {
    // Do not announce the already released version when the bot is first enabled.
    await env.DISCORD_STATE.put(versionKey, "1");
    await env.DISCORD_STATE.put(baselineKey, "1");
    return;
  }
  if (await env.DISCORD_STATE.get(versionKey)) return;

  await postAnnouncement(token, channel, announcementText(
    `Verceltics ${version} is on the App Store.`,
    release.releaseNotes,
    APP_STORE_URL,
  ));
  // Mark only after Discord accepts the message, so a failed post is retried.
  await env.DISCORD_STATE.put(versionKey, "1");
}

async function announceAndroid(env, token, channel) {
  const release = await fetchProductionRelease(env.PLAY_SERVICE_ACCOUNT_JSON);
  if (!release) return;

  const baselineKey = `${STATE_PREFIX}:android:baseline`;
  const versionKey = `${STATE_PREFIX}:android:seen:${release.versionCode}`;
  const baselineRaw = await env.DISCORD_STATE.get(baselineKey);
  let baseline = null;
  if (baselineRaw) {
    try {
      baseline = JSON.parse(baselineRaw);
    } catch {
      throw new AnnouncementError("play_state_invalid");
    }
    if (typeof baseline?.notes !== "string") {
      throw new AnnouncementError("play_state_invalid");
    }
  }
  if (await env.DISCORD_STATE.get(versionKey)) return;

  // A completed production track can still be waiting for Google review. Only
  // announce if its own release notes have appeared on the public store page.
  const html = await request(PLAY_URL, {
    headers: { "User-Agent": USER_AGENT, "Accept-Language": "en-US,en;q=0.9" },
  }, "play_listing", "text");
  if (!html.includes(PACKAGE_NAME)) return;
  const liveNotes = normalizedNotes(playWhatsNew(html));
  const expectedNotes = normalizedNotes(release.notes);
  const isLive = expectedNotes.length > 0 && liveNotes.includes(expectedNotes);

  if (!baseline) {
    // Seed what is public today. A pending version remains unseen and can be
    // announced later once its notes actually reach the public listing.
    if (isLive) await env.DISCORD_STATE.put(versionKey, "1");
    await env.DISCORD_STATE.put(baselineKey, JSON.stringify({ notes: liveNotes }));
    return;
  }
  // Reused or absent notes cannot prove which build is public; skip those.
  if (!isLive || liveNotes === baseline.notes) return;

  const title = release.name
    ? `Verceltics Android ${release.name} (build ${release.versionCode})`
    : `Verceltics Android build ${release.versionCode}`;
  await postAnnouncement(token, channel, announcementText(
    `${title} is on the Play Store.`, release.notes, PLAY_URL,
  ));
  await env.DISCORD_STATE.put(versionKey, "1");
  await env.DISCORD_STATE.put(baselineKey, JSON.stringify({ notes: liveNotes }));
}

async function postAnnouncement(token, channel, content) {
  await request(`https://discord.com/api/v10/channels/${channel}/messages`, {
    method: "POST",
    headers: {
      Authorization: `Bot ${token}`,
      "Content-Type": "application/json",
      "User-Agent": "Vercie (https://verceltics.com, 1.0)",
    },
    body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
  }, "discord_post");
}

function announcementText(heading, notes, storeUrl) {
  const lead = discordText(heading).slice(0, 240);
  const tail = `\n\n${storeUrl}`;
  const text = discordText(notes);
  const prefix = text ? "\n\nWhat’s new:\n" : "";
  const budget = 1990 - lead.length - prefix.length - tail.length;
  const fitted = text.length > budget
    ? `${text.slice(0, Math.max(0, budget - 1)).replace(/[\uD800-\uDBFF]$/, "").trimEnd()}…`
    : text;
  return `${lead}${prefix}${fitted}${tail}`;
}

function plainText(value) {
  return typeof value === "string"
    ? value.replace(/<[^>]*>/g, " ")
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u202A-\u202E\u2066-\u2069]/g, "")
      .replace(/\r\n?/g, "\n").trim()
    : "";
}

function discordText(value) {
  return plainText(value)
    .replace(/@/g, "@\u200B")
    .replace(/([\\*_`~|>\[\]])/g, "\\$1");
}

function normalizedNotes(value) {
  return plainText(value)
    .replace(/&#(\d+);/g, (match, digits) => {
      const code = Number(digits);
      return code > 0 && code <= 0x10FFFF ? String.fromCodePoint(code) : match;
    })
    .replace(/&(amp|quot|apos|rsquo|nbsp);/g, (_, entity) => ({
      amp: "&", quot: '"', apos: "'", rsquo: "’", nbsp: " ",
    })[entity])
    .replace(/\s+/g, " ").trim().toLowerCase();
}

function playWhatsNew(html) {
  const start = html.search(/What(?:'|’|&#39;|&rsquo;)s new/i);
  if (start < 0) return "";
  const section = html.slice(start, start + 6000);
  const end = section.search(/flag Flag as inappropriate|Data safety|You might also like/i);
  return end > 0 ? section.slice(0, end) : section;
}

async function fetchProductionRelease(serviceAccountJson) {
  let account;
  try {
    account = JSON.parse(serviceAccountJson);
  } catch {
    throw new AnnouncementError("play_credentials_invalid");
  }
  if (typeof account?.client_email !== "string" || typeof account?.private_key !== "string") {
    throw new AnnouncementError("play_credentials_invalid");
  }
  const token = await googleAccessToken(account);
  const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
  // Google exposes track reads through temporary edits. This edit is never
  // changed or committed and is deleted in finally, including on API failures.
  const edit = await request(`${PLAY_API}/edits`, {
    method: "POST", headers, body: "{}",
  }, "play_edit");
  if (typeof edit.id !== "string" || !/^[-\w]+$/.test(edit.id)) {
    throw new AnnouncementError("play_edit_invalid");
  }
  const editUrl = `${PLAY_API}/edits/${encodeURIComponent(edit.id)}`;
  try {
    const track = await request(`${editUrl}/tracks/production`, { headers }, "play_track");
    let latest = null;
    for (const release of track.releases || []) {
      if (release.status !== "completed") continue;
      for (const code of release.versionCodes || []) {
        if (typeof code !== "string" || !/^\d{1,10}$/.test(code)) continue;
        if (latest && Number(code) <= Number(latest.versionCode)) continue;
        const notes = Array.isArray(release.releaseNotes) ? release.releaseNotes : [];
        const english = notes.find((note) => note.language === "en-US")
          || notes.find((note) => typeof note.language === "string" && note.language.startsWith("en"));
        latest = {
          versionCode: code,
          name: plainText(release.name).slice(0, 80),
          notes: typeof english?.text === "string" ? english.text : "",
        };
      }
    }
    return latest;
  } finally {
    try {
      await request(editUrl, { method: "DELETE", headers }, "play_edit_cleanup", "empty");
    } catch {
      console.error("Vercie release check cleanup failed", { code: "play_edit_cleanup_failed" });
    }
  }
}

async function googleAccessToken(account) {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${base64Url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: GOOGLE_TOKEN_URL,
    iat: now,
    exp: now + 3600,
  }))}`;
  let signature;
  try {
    const pemBody = account.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
    const key = await crypto.subtle.importKey("pkcs8", Uint8Array.from(atob(pemBody), (character) => character.charCodeAt(0)),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  } catch {
    throw new AnnouncementError("play_credentials_invalid");
  }
  const result = await request(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${base64Url(new Uint8Array(signature))}`,
    }),
  }, "play_token");
  if (typeof result.access_token !== "string" || !result.access_token) {
    throw new AnnouncementError("play_token_missing");
  }
  return result.access_token;
}

function base64Url(value) {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  return btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function request(url, options, code, format = "json") {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal, redirect: "error" });
    if (!response.ok) throw new AnnouncementError(`${code}_http_${response.status}`);
    if (format === "empty") {
      await response.body?.cancel();
      return null;
    }
    const body = await response.text();
    if (body.length > (format === "text" ? 4_000_000 : 250_000)) {
      throw new AnnouncementError(`${code}_response_too_large`);
    }
    return format === "text" ? body : JSON.parse(body);
  } catch (error) {
    if (error instanceof AnnouncementError) throw error;
    throw new AnnouncementError(`${code}_request_failed`);
  } finally {
    clearTimeout(timeout);
  }
}
