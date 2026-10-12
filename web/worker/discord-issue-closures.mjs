/** Private Discord report routing and signed GitHub issue-closure notifications. */
export const GITHUB_ISSUES_WEBHOOK_PATH = "/api/discord/github-issues";
const REPOSITORY = "apoorvdarshan/verceltics";
const RETENTION = 730 * 24 * 60 * 60;
const QUEUE_RETENTION = 7 * 24 * 60 * 60;
const PREFIX = "issue-close:pending:";
const SNOWFLAKE = /^\d{16,22}$/;

const active = new WeakMap                           ();
const linkKey = (number        ) => `issue-close:link:${number}`;
const eventKey = (event         ) => `${event.number}:${event.closedAt}`;
const validLink = (link           ) => link && [link.userId, link.channelId, link.messageId].every(id => typeof id === "string" && SNOWFLAKE.test(id));

/** No report text, usernames, interaction tokens, or credentials are retained. */
export async function rememberDiscordIssue(env            , number        , link           )                   {
  if (!env.DISCORD_STATE || !Number.isSafeInteger(number) || number < 1 || !validLink(link)) return false;
  try {
    await env.DISCORD_STATE.put(linkKey(number), JSON.stringify(link), { expirationTtl: RETENTION });
    return true;
  } catch {
    console.warn("discord_issue_link_unavailable");
    return false;
  }
}

export function closureMessage(number        , userId        , reason               )         {
  const outcome = reason === "completed" ? "closed as completed"
    : reason === "not_planned" ? "closed without planned action"
    : reason === "duplicate" ? "closed as a duplicate" : "closed";
  return `<@${userId}> Your report was ${outcome} on GitHub: https://github.com/${REPOSITORY}/issues/${number}\nThis is an issue-status update, not confirmation of an app release.`;
}

async function verify(body            , signature        , secret        )                   {
  if (!/^sha256=[a-f0-9]{64}$/.test(signature)) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const bytes = new Uint8Array(signature.slice(7).match(/../g) .map(pair => parseInt(pair, 16)));
  return crypto.subtle.verify("HMAC", key, bytes, body                           );
}

async function boundedBody(request         )                                   {
  const reader = request.body?.getReader();
  const chunks               = [];
  let size = 0;
  if (reader) {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.length;
      if (size > 262144) { await reader.cancel(); throw new Error("body_too_large"); }
      chunks.push(part.value);
    }
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
  return body;
}

export async function handleGitHubIssueClosure(request         , env            , ctx                                                 )                    {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
  if (!env.DISCORD_GITHUB_WEBHOOK_SECRET || !env.DISCORD_STATE || !env.DISCORD_BOT_TOKEN || !ctx) return new Response("Not configured", { status: 503 });
  let raw                         ;
  try { raw = await boundedBody(request); } catch { return new Response("Payload too large", { status: 413 }); }
  if (!await verify(raw, request.headers.get("x-hub-signature-256") || "", env.DISCORD_GITHUB_WEBHOOK_SECRET)) return new Response("Invalid signature", { status: 401 });
  let payload;
  try { payload = JSON.parse(new TextDecoder().decode(raw)); } catch { return new Response("Invalid JSON", { status: 400 }); }
  if (payload?.repository?.full_name !== REPOSITORY) return new Response("Wrong repository", { status: 403 });
  const event = request.headers.get("x-github-event");
  if (event === "ping") return new Response("OK");
  const issue = payload?.issue;
  if (event !== "issues" || payload?.action !== "closed" || issue?.pull_request) return new Response(null, { status: 204 });
  if (!Number.isSafeInteger(issue?.number) || issue.number < 1 || issue.state !== "closed" || typeof issue.closed_at !== "string" || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(issue.closed_at) || !Number.isFinite(Date.parse(issue.closed_at))) return new Response("Invalid closure", { status: 400 });
  const closure          = { number: issue.number, closedAt: issue.closed_at, reason: typeof issue.state_reason === "string" ? issue.state_reason : null };
  const key = eventKey(closure);
  try {
    // Never derive a recipient from issue text, labels, or webhook sender data.
    if (!await env.DISCORD_STATE.get(linkKey(issue.number))) return new Response(null, { status: 204 });
    if (await env.DISCORD_STATE.get(`issue-close:sent:${key}`)) return new Response(null, { status: 204 });
    await env.DISCORD_STATE.put(PREFIX + key, JSON.stringify(closure), { expirationTtl: QUEUE_RETENTION });
    ctx.waitUntil(deliverClosure(env, closure));
    return new Response("Queued", { status: 202 });
  } catch {
    console.warn("discord_closure_queue_unavailable");
    return new Response("Try again later", { status: 503 });
  }
}

async function deliverClosure(env            , event         )                {
  const state = env.DISCORD_STATE;
  if (!state || !env.DISCORD_BOT_TOKEN) return;
  const key = eventKey(event);
  let running = active.get(state);
  if (!running) { running = new Set(); active.set(state, running); }
  if (running.has(key)) return;
  running.add(key);
  try {
    if (await state.get(`issue-close:sent:${key}`)) {
      await state.delete?.(PREFIX + key);
      return;
    }
    const raw = await state.get(linkKey(event.number));
    if (!raw) return;
    const link            = JSON.parse(raw);
    if (!validLink(link)) return;
    // The same nonce deduplicates concurrent sends/retries in Discord's recent-message window.
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(REPOSITORY + key));
    const nonce = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("").slice(0, 24);
    const response = await fetch(`https://discord.com/api/v10/channels/${link.channelId}/messages`, {
      method: "POST", redirect: "error", signal: AbortSignal.timeout(6000),
      headers: { Authorization: `Bot ${env.DISCORD_BOT_TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        content: closureMessage(event.number, link.userId, event.reason),
        allowed_mentions: { parse: [], users: [link.userId], replied_user: false },
        message_reference: { message_id: link.messageId, channel_id: link.channelId, fail_if_not_exists: false },
        nonce, enforce_nonce: true,
      }),
    });
    if (!response.ok) { await response.body?.cancel(); throw new Error("discord_send_failed"); }
    await response.body?.cancel();
    await state.put(`issue-close:sent:${key}`, "1", { expirationTtl: RETENTION });
    await state.delete?.(PREFIX + key);
  } catch {
    // Queue survives Discord/KV outages. Never log raw errors or credential-bearing URLs.
    console.warn("discord_closure_delivery_pending");
  } finally { running.delete(key); }
}

/** Bounded retry batches; cursor prevents a failing early item starving later reports. */
export async function retryDiscordIssueClosures(env            )                {
  const state = env.DISCORD_STATE;
  if (!state?.list || !env.DISCORD_BOT_TOKEN) return;
  try {
    const cursor = await state.get("issue-close:cursor");
    const page = await state.list({ prefix: PREFIX, limit: 25, ...(cursor ? { cursor } : {}) });
    for (const item of page.keys) {
      const raw = await state.get(item.name);
      if (raw) await deliverClosure(env, JSON.parse(raw));
    }
    await state.put("issue-close:cursor", page.list_complete ? "" : page.cursor || "", { expirationTtl: QUEUE_RETENTION });
  } catch { console.warn("discord_closure_retry_unavailable"); }
}
