# Reviewed release notes

`ios-v` sections match the iOS project's `MARKETING_VERSION`, and `android-v` sections
match the Android `versionName`. The same notes become the GitHub release body and, when
`IOS_UPLOAD_WHATS_NEW` or `ANDROID_UPLOAD_WHATS_NEW` is on, the App Store or Google Play
What's New text, converted to plain text (links keep their text, list markers become •,
heading, quote, bold and code markers are dropped; App Store: 4000 characters at most,
Google Play: 500). A tag without a section gets generated GitHub release notes, and a
What's New upload for it fails. Each tag may have only one section.

## ios-v2.1

Verceltics 2.1 brings interface refinements and new ways to get help.

• Refined native Hosting, Registrars, and Sites workspaces with separate provider dashboards.
• Adaptive iPhone and iPad layouts with System, Light, and Dark appearances.
• Join the Verceltics Discord community directly from About.
• Report an issue or request a feature using a GitHub template, or use Vercie on Discord without a GitHub account.
• Meet the developer in a new 2.1 welcome, with links to X, Instagram, Product Hunt, GitHub, and Discord. Close it once to dismiss it permanently on your device.
• Updated Instagram, LinkedIn, and Product Hunt links.

Provider data and operations depend on each API, account permissions, and plan.
