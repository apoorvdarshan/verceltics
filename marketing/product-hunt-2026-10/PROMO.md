# Product Hunt offer — October 2026

Created and read back from App Store Connect on October 3, 2026.

## Paste into Product Hunt

| Field | Value |
| --- | --- |
| What is the offer? | 1 month of Verceltics Pro free for new subscribers. First 500 redemptions. No automatic renewal. |
| Promo code | `VERCELTICSPH` |
| Expiration date | December 31, 2026 |

Redemption link:
https://apps.apple.com/redeem?ctx=offercodes&id=6761645656&code=VERCELTICSPH

Apple expires codes at **12:00 a.m. Pacific on December 31**, the start of that
date. This is 1:30 p.m. India Standard Time on December 31, 2026. It may take up
to one hour after creation for a code to become redeemable.
See [Apple's offer-code guide](https://developer.apple.com/help/app-store-connect/manage-subscriptions/set-up-subscription-offer-codes/).

## Verified configuration

- App: `6761645656` — Verceltics for iOS/iPadOS.
- Subscription: `com.apoorvdarshan.verceltics.monthly` (`6761646252`, approved).
- Offer: `942f13ef-a5a4-4119-b076-13f61bd835c9`.
- Custom-code record: `606461`, active, 500 redemptions.
- Eligibility: new subscribers only (`NEW`).
- Benefit: `FREE_TRIAL`, `ONE_MONTH`, one period.
- Automatic renewal: **false**.
- Introductory-offer behavior: replaces the introductory offer.
- Territory pricing: all 175 currently available subscription territories.
- Expiration returned by Apple: `2026-12-31`.

Apple rejected 100 redemptions; the owner authorized 500 before code creation.
The existing Porkbun offer was left intact. No app version was submitted,
released, or built, and no release switch was enabled.

## Customer instructions

Open the redemption link on your iPhone or iPad using the App Store account you
use for Verceltics. Complete Apple's redemption flow, then open Verceltics.
If Pro does not appear immediately, use **Restore purchases** in the paywall.

The configuration was verified through Apple's API. An actual customer
redemption and RevenueCat entitlement update have not been tested.
