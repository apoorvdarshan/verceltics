# Google Play Data safety (Android 1.0)

Answers for Play Console → Policy → App content → Data safety. They follow RevenueCat's own guidance for the SDK and how the Android app handles provider data. You are responsible for the final answers. Review them before submitting.

## What the app sends off the device

| Destination | What | Why |
|---|---|---|
| Provider APIs you connect (Vercel, Cloudflare, Netlify, Google) | Your token or API key, plus the requests needed to show your data | Only when you connect or refresh a provider. Requests go straight from the phone to that provider. Verceltics never receives them. |
| RevenueCat (purchase service) | Anonymous app-user ID, purchase history, product IDs, entitlement status, device and app version, locale | Purchases, restore, and purchase analytics |
| Google Play Billing | Purchases | Handled by Google |

The app has no ads, no Verceltics analytics, no crash reporting SDK, and no Verceltics servers.

## Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (RevenueCat purchase history) |
| Is all of the user data collected by your app encrypted in transit? | **Yes** |
| Do you provide a way for users to request that their data is deleted? | **Yes**: the privacy page's "Your controls" section, or by email to ad13dtu@gmail.com. RevenueCat customers can be deleted from the dashboard. Uninstalling removes everything stored on the device. |
| Account creation | **My app does not allow users to create an account** |
| Can users log in with accounts created outside the app? | **Yes → Other**: users connect accounts they already have with hosting providers, registrars and Google services, using that provider's API token or Google sign in |
| Delete data URL | https://verceltics.com/privacy#controls |

## Data types

Mark these two, as submitted on October 9, 2026:

**Financial info → Purchase history**

| Question | Answer |
|---|---|
| Collected or shared? | **Collected** (not shared) |
| Processed ephemerally? | **No** |
| Required or optional? | **Required** (cannot be turned off) |
| Why is it collected? | **App functionality**, **Analytics** |

**Device or other IDs → Device or other IDs** (RevenueCat's anonymous app user ID, as Fud AI declares it)

| Question | Answer |
|---|---|
| Collected or shared? | **Collected** (not shared) |
| Processed ephemerally? | **No** |
| Required or optional? | **Required** |
| Why is it collected? | **App functionality** |

Leave everything else unchecked: location, personal info, messages, photos, audio, files, calendar, contacts, app activity, web browsing, and app info and performance. The app doesn't use an advertising ID (Advertising ID declaration: **No**) or RevenueCat attribution integrations.

## Judgment call: provider tokens

Provider tokens and dashboard data are sent only to the provider you choose to connect, when you ask to load it. Verceltics and its service providers never receive them, so these answers don't list them as collected. If you'd rather be conservative, you can add **App activity → Other actions** or **Personal info → Other info** as collected for app functionality. Google's guidance treats any off-device transfer as collection, so decide which reading you're comfortable with.

## Privacy policy

https://verceltics.com/privacy covers Android and Google Play Billing (updated October 9, 2026), so it matches these answers.
