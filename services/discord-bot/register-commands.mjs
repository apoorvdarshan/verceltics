#!/usr/bin/env node

// Run from the repository root with Node 22+:
// node --env-file=/absolute/path/to/discord.env services/discord-bot/register-commands.mjs
// --dry-run prints the command definitions without any network requests.

const commands = [
  {
    type: 1,
    name: "ask",
    description: "Ask Vercie about Verceltics. Gemini answers are visible in this channel.",
    options: [{
      type: 3,
      name: "question",
      description: "Your Verceltics question, sent to Google Gemini. Do not include secrets.",
      required: true,
      min_length: 1,
      max_length: 1500,
    }],
  },
  {
    type: 1,
    name: "bug",
    description: "Publish a Verceltics bug report as a PUBLIC GitHub issue. Do not include secrets.",
    options: [{
      type: 3,
      name: "report",
      description: "Public report: platform, steps, expected and actual behavior. Gemini may organize it.",
      required: true,
      min_length: 1,
      max_length: 4000,
    }],
  },
  {
    type: 1,
    name: "feature",
    description: "Publish a Verceltics feature request as a PUBLIC GitHub issue. Do not include secrets.",
    options: [{
      type: 3,
      name: "report",
      description: "Public request: what you want and why it helps. Gemini may organize it.",
      required: true,
      min_length: 1,
      max_length: 4000,
    }],
  },
];

function required(name, { snowflake = false } = {}) {
  const value = process.env[name]?.trim();
  if (!value || (snowflake && !/^\d{17,20}$/.test(value))) {
    throw new Error(`Set ${name} to ${snowflake ? "a valid Discord ID" : "a nonempty value"}.`);
  }
  return value;
}

async function discord(path, token, body) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    let response;
    try {
      response = await fetch(`https://discord.com/api/v10${path}`, {
        method: body ? "POST" : "GET",
        headers: {
          Authorization: `Bot ${token}`,
          "Content-Type": "application/json",
        },
        body: body ? JSON.stringify(body) : undefined,
        redirect: "error",
        signal: AbortSignal.timeout(20000),
      });
    } catch {
      // Do not log request headers, raw API responses, or error causes with credentials.
      throw new Error("Discord request failed or timed out. Rerun registration to resume safely.");
    }

    if (response.status === 429 && attempt < 2) {
      const limit = await response.json().catch(() => null);
      const seconds = Number(limit?.retry_after);
      if (Number.isFinite(seconds) && seconds >= 0 && seconds <= 15) {
        await new Promise((resolve) => setTimeout(resolve, Math.ceil(seconds * 1000) + 250));
        continue;
      }
    }

    if (!response.ok) {
      throw new Error(`Discord returned HTTP ${response.status}. Check the bot token, app ID, server installation, and permissions.`);
    }
    const data = await response.json().catch(() => null);
    if (!data || typeof data !== "object") {
      throw new Error("Discord returned an unexpected response. No credentials have been logged.");
    }
    return data;
  }
  throw new Error("Discord rate limit persists. Retry registration later.");
}

async function main() {
  const args = process.argv.slice(2);
  if (args.some((arg) => arg !== "--dry-run")) {
    throw new Error("Usage: node register-commands.mjs [--dry-run]");
  }
  if (args.includes("--dry-run")) {
    console.log(JSON.stringify(commands, null, 2));
    return;
  }

  const token = required("DISCORD_BOT_TOKEN");
  const appId = required("DISCORD_APPLICATION_ID", { snowflake: true });
  const guildId = required("DISCORD_GUILD_ID", { snowflake: true });

  // Verify the token belongs to this application before changing any commands.
  const app = await discord("/oauth2/applications/@me", token);
  if (app.id !== appId) {
    throw new Error("DISCORD_BOT_TOKEN belongs to a different application. No commands were changed.");
  }
  const bot = await discord("/users/@me", token);
  if (bot.bot !== true || (app.bot?.id && app.bot.id !== bot.id)) {
    throw new Error("The authenticated bot does not match the application. No commands were changed.");
  }
  const guild = await discord(`/guilds/${guildId}`, token);
  if (guild.id !== guildId) {
    throw new Error("The target server could not be verified. No commands were changed.");
  }

  console.log(`Verified application ${appId} and server ${guildId}.`);
  for (const command of commands) {
    // POST upserts this command by name; bulk PUT would erase unrelated commands.
    const registered = await discord(`/applications/${appId}/guilds/${guildId}/commands`, token, command);
    if (registered.name !== command.name || registered.application_id !== appId || registered.guild_id !== guildId) {
      throw new Error(`Discord returned an unexpected registration result for /${command.name}.`);
    }
    console.log(`Registered /${command.name} in server ${guildId}.`);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
