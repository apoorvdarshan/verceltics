# Vercie — Verceltics Discord bot

Vercie runs inside the existing Verceltics Cloudflare Worker. No laptop process or Discord Gateway connection is required.

| Command | Result |
| --- | --- |
| `/ask question:…` | Sends the question to Google Gemini and replies in the channel with Verceltics help. Maximum 1,500 characters. |
| `/bug report:…` | Creates a **public** bug issue in `apoorvdarshan/verceltics`. Include the platform, steps, expected result, and actual result. Maximum 4,000 characters. |
| `/feature report:…` | Creates a **public** enhancement issue in that repository. Maximum 4,000 characters. |

Reports keep the original submitted text and may include a Gemini-organized summary. If Gemini is unavailable, issue creation can use the original report. Vercie does not add the submitter's Discord username or ID to the public issue. Information included in the report itself is public. Successful replies and failures after a command has started appear in-channel; immediate validation or configuration errors are private to the caller.

Do not submit credentials, private account data, personal information, or security vulnerabilities through these commands. Use [private security reporting](../../SECURITY.md) for vulnerabilities.

## Files and endpoint

- Public endpoint: `https://verceltics.com/api/discord/interactions`
- Worker entry point: [`web/worker/index.mjs`](../../web/worker/index.mjs)
- Worker configuration: [`web/wrangler.jsonc`](../../web/wrangler.jsonc)
- Command registration: [`register-commands.mjs`](./register-commands.mjs)
- Configuration template: [`.env.example`](./.env.example)
- Published policies: [Privacy](https://verceltics.com/privacy#discord) and [Terms](https://verceltics.com/terms#discord)

The Worker validates Discord's Ed25519 request signature and application ID. Commands are accepted only from the configured Verceltics server. Discord's signed setup PING can succeed before a server ID is configured, so endpoint verification does not by itself enable commands.

## Configuration

### Worker variables and bindings

Set non-secret values in `web/wrangler.jsonc`:

| Name | Purpose |
| --- | --- |
| `DISCORD_APPLICATION_ID` | Vercie application ID: `1555610391508426783`. |
| `DISCORD_PUBLIC_KEY` | Public verification key from the Verceltics application's General Information page. This is **not** the bot token. |
| `DISCORD_GUILD_ID` | The Verceltics Discord server ID. Required to accept commands. |
| `DISCORD_GEMINI_MODEL` | Default: `gemini-3.5-flash-lite`. Must be available to the configured Gemini API project. |
| `DISCORD_ANNOUNCEMENTS_CHANNEL_ID` | Optional target for release announcements. |
| `DISCORD_IOS_BUG_CHANNEL_ID` | Optional iOS bug channel used when the report does not identify its platform. |
| `DISCORD_ANDROID_BUG_CHANNEL_ID` | Optional Android bug channel used when the report does not identify its platform. |
| `DISCORD_STATE` | Cloudflare KV binding for short-lived duplicate/cooldown markers and release versions. |

Create a dedicated KV namespace if none is configured:

```bash
cd web
npx wrangler kv namespace create DISCORD_STATE
```

Add the returned namespace ID to `kv_namespaces` under the `DISCORD_STATE` binding. Do not put a placeholder namespace ID into a production deployment.

### Secrets

| Secret | Required for |
| --- | --- |
| `DISCORD_BOT_TOKEN` | Registering commands and sending scheduled release announcements. Obtain it from the **Verceltics** application's Bot page. |
| `DISCORD_GEMINI_API_KEY` | `/ask`; optional AI organization for `/bug` and `/feature`. Use a Gemini key intended for Vercie. |
| `DISCORD_GITHUB_TOKEN` | Optional dedicated token for issue creation; otherwise the Worker uses its existing `GITHUB_TOKEN`. |
| `GITHUB_TOKEN` | Existing website GitHub access; also the issue token fallback. Issue creation needs access to `apoorvdarshan/verceltics` with **Issues: read and write**. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Optional Android production-release checking through Google Play. Requires read access to the Verceltics app in Play Console. |

A fine-grained GitHub token limited to `apoorvdarshan/verceltics` with Issues read/write is sufficient for report submission. If the existing website token has only read permissions, provide a separate `DISCORD_GITHUB_TOKEN` instead of expanding unrelated access.

Add Worker secrets interactively from `web/`:

```bash
npx wrangler secret put DISCORD_BOT_TOKEN
npx wrangler secret put DISCORD_GEMINI_API_KEY
npx wrangler secret put DISCORD_GITHUB_TOKEN
```

The third command is needed only when using a dedicated issue token. Do not overwrite an existing working `GITHUB_TOKEN` without checking its other website uses. Add `PLAY_SERVICE_ACCOUNT_JSON` only if enabling Android announcements. Never place secret values in `wrangler.jsonc`, source files, shell history, or a committed environment file.

## Discord setup

1. Open the **Verceltics** application in the [Developer Portal](https://discord.com/developers/applications). Its bot identity is **Vercie**. Keep Fuddy's application and credentials separate.
2. Under **Installation**, use **Guild Install** with the `bot` and `applications.commands` scopes. Grant **View Channels**, **Send Messages**, and **Embed Links** in the channels where Vercie will respond or announce releases. Administrator and Message Content intent are not required for this HTTP-command implementation.
3. Install Vercie in the Verceltics server. Copy the server ID and announcement channel ID with Discord Developer Mode enabled, then configure the Worker variables above.
4. After deploying the Worker, set **General Information → Interactions Endpoint URL** to `https://verceltics.com/api/discord/interactions` and save. Discord sends a signed PING; the Worker returns its verification response.
5. Set **Terms of Service URL** to `https://verceltics.com/terms` and **Privacy Policy URL** to `https://verceltics.com/privacy` after the updated pages are deployed. No Linked Roles Verification URL is needed.
6. Register the server commands as described below. They are scoped to this server, not direct messages or other servers.

Vercie does not receive ordinary channel messages, `@mentions`, voice, or message history. Leave privileged Gateway intents disabled unless a separate future feature needs them.

## Register commands

Use Node.js 22 or newer. Keep registration credentials in an external file, for example `~/.config/verceltics/discord.env`, readable only by your user. Copy the template and fill in `DISCORD_BOT_TOKEN`, `DISCORD_APPLICATION_ID`, and `DISCORD_GUILD_ID`. Other keys in that file are not needed by the registration script. Loading an env file here does not upload its values to Cloudflare.

From the repository root:

```bash
node --env-file="$HOME/.config/verceltics/discord.env" services/discord-bot/register-commands.mjs
```

The script first verifies that the token belongs to the stated application and that the bot can access the target server. It then upserts `/ask`, `/bug`, and `/feature` individually. Existing unrelated commands are preserved. Rerunning it safely updates these three definitions.

To inspect the definitions without credentials or network access:

```bash
node services/discord-bot/register-commands.mjs --dry-run
```

Discord documents guild registration and its per-name update behavior in [Application Commands](https://docs.discord.com/developers/interactions/application-commands#create-guild-application-command).

## Deploy

From `web/`, with the Cloudflare account authenticated and configuration complete:

```bash
npm run deploy
```

This builds the static website, including the Discord policy sections, and deploys the Worker and its assets. Mobile app builds and device installs are not part of this backend change.

## Release announcements

The scheduled Worker checks hourly for new public releases and posts them to `DISCORD_ANNOUNCEMENTS_CHANNEL_ID` using `DISCORD_BOT_TOKEN`. `DISCORD_STATE` stores observed versions and release markers for each platform. The first successful check records the already-live release without posting a backlog announcement.

- **iOS:** uses Apple's public iTunes lookup for Verceltics, App Store ID `6761645656`.
- **Android:** optional. Uses `PLAY_SERVICE_ACCOUNT_JSON` to inspect the completed production track and requires its release notes to appear in the public Play listing before announcing. Missing, reused, or unparseable release notes are skipped because they cannot establish which build is public. A production-track entry still under review or a testing-track release is not enough. The API reads use a temporary Play edit that is never committed and is deleted afterward; no release is published or changed.

Without the bot token, announcements channel, or KV binding, announcement posting stays disabled. iOS checks do not require Play credentials. Provide Play credentials only if the app has a configured production release to announce.

## Data handling and limits

- Only explicitly submitted command text is sent to Gemini. The bot does not ingest conversations or app credentials.
- `/bug` and `/feature` publish the report and any AI summary to the public Verceltics GitHub repository. Their command descriptions disclose this before submission.
- KV contains release markers and hashes of identifiers for request deduplication and per-user cooldowns; no submitted text or Discord interaction tokens are stored there. Duplicate markers expire after 15 minutes; cooldowns are 60 seconds for `/ask` and 300 seconds for reports.
- KV is eventually consistent, so deduplication and cooldowns are best-effort and do not guarantee exactly-once processing across locations.
- Bot logs omit submitted text. Discord, GitHub, Google, and Cloudflare have their own processing and retention policies.
- Gemini's treatment of prompts depends on its service and billing configuration. Do not promise users that their text is excluded from provider retention or model improvement. See [Gemini API terms](https://ai.google.dev/gemini-api/terms).

If a command fails, check the configured server/application IDs, required secrets, KV binding, GitHub Issues permissions, and the selected Gemini model's availability. Avoid repeating a report before checking whether its issue was already created.
