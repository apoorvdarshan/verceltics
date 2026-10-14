# Google Play store listing (Android 1.0)

Android 1.0 connects all 27 integrations: 10 hosting platforms, 8 registrars and 9 site services.

## Listing text

The app name, short description and full description live in
[`store/metadata/play/en-US/`](../metadata/play/en-US/) (`title.txt`, 30
characters at most; `short_description.txt`, 80; `full_description.txt`, 4000).
`android-release.yml` uploads them, and the phone screenshots below, only when
`ANDROID_UPLOAD_LISTING` / `ANDROID_UPLOAD_SCREENSHOTS` are on (see
[store/README.md](../README.md)). Otherwise copy them into Play Console →
Grow → Store presence → Main store listing. Check them with
`python3 scripts/store/play_listing.py check`.

## Other fields

| Field | Value |
|---|---|
| App category | Tools |
| Tags | Developer tools, Productivity |
| Contact email | ad13dtu@gmail.com |
| Website | https://verceltics.com |
| Privacy policy | https://verceltics.com/privacy |

## Graphics

| Asset | Status |
|---|---|
| App icon 512×512 | `app-icon-512.png` |
| Feature graphic 1024×500 | `feature-graphic.jpg` (source: `feature-graphic.html`) |
| Phone screenshots | `screenshots/phone/` (6 Android screenshots, 1080×1920, sample data on the FudAI_API_36 emulator) |

## Sign in details (for reviewers)

Submitted as "Is any part of your app restricted? Yes", one entry named "Sample data preview" with no username or password, marked as giving full access.

```text
No account, sign in or payment is needed. On the first screen tap Choose what to connect, then Explore sample data. The app opens with fictional hosting, registrar and site service accounts, and every Verceltics Pro screen (project analytics, domain details, site reports and provider dashboards) is unlocked there. Tap Exit preview to leave. Connecting a real provider needs your own API token for that provider.
```
