# Marketing assets

Public-facing Verceltics assets for product listings, social posts, press coverage, and promotional material.

Current copy should use **iOS 2.1**, the current App Store release.
App Store listing text lives in [store/metadata/ios](../store/metadata/ios/) and the
Google Play listing in [store/metadata/play](../store/metadata/play/). The iPhone and iPad
mockups below are the App Store screenshots that `ios-release.yml` uploads when
`IOS_UPLOAD_SCREENSHOTS` is on.

## Contents

- `mockups ios/` — iPhone promotional artwork
- `mockups ipad/` — iPad promotional artwork
- `mockups android/` — Android twins of the iPhone artwork for Google Play (1080×1920, Codex imagegen; prompts in `prompts.md`, source screens in `android-mockup-sources/`)
- `ss ios/` — source iPhone screenshots
- `ss ipad/` — source iPad screenshots
- `Verceltics-Logo*.png` — app logos used in the mockups

This folder holds only app mockups and the images they're made from. Videos, YouTube
thumbnails, Product Hunt galleries and other launch assets live outside the repository
(the October 2026 set is in `~/Downloads/Verceltics-Marketing-2026-10-15/` on the
maintainer's Mac); earlier versions remain in git history.

These assets are intended for public marketing use.
