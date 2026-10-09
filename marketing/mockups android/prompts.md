# Exact built-in image_gen prompts

Used the built-in image_gen tool. Images 1 and 3 include a generation prompt followed by a correction prompt. Final PNGs were resized to 1080 x 1920 using sips.

## prompt1

```text
Use case: ads-marketing. Create ONE final Google Play promotional phone screenshot for Verceltics Android, image 1 of a coordinated four-image set. Output PNG, exactly 9:16 portrait, 1080x1920 or 1440x2560.
Input 1 is the iOS promotional mockup: use its composition, colors, typography, line logo and illustration as the visual reference. Input 2 is the Android analytics screenshot: use it as the ONLY phone screen content, faithfully inserted, preserving all UI text, icons, colors, spacing and values. Do not redraw or invent a different interface.
Recreate input 1 as its Android twin, adapting the taller reference composition to 9:16. Solid black upper block roughly 36% of canvas, electric cobalt blue #147BFA lower area. Large thin clean geometric sans-serif white headline at upper left, verbatim "Check your whole stack. Close the laptop." with line breaks "Check your" / "whole stack." / "Close the" / "laptop.". At the lower right of the black block retain the blue/white/purple curved line-and-dot logo motif. Huge phone in the lower area tilted clockwise, silver edge, top edge sloping down to the right, cropped by bottom, same tilt and crop as reference. Flat cartoon curly black-haired woman with white headphones, orange top, chartreuse jacket, blue jeans overlaps the phone at right, cropped at right and bottom, same illustration style as reference.
CHANGE ONLY PHONE HARDWARE AND SCREEN: generic modern Android phone, flat display, thin even bezels, modest corner radius, small single CENTERED circular punch-hole camera. No Dynamic Island, no pill, no notch, no Apple features, no hardware brand or names. Match Android analytics input faithfully: Sample data / Exit preview, studio-web, studio.example, 7 Days, Production, Updated just now, VISITORS 12.5K +14%, PAGE VIEWS 18.3K +19%, BOUNCE RATE 34% -4%, Visitors/Page Views/Bounce Rate tabs, VISITORS 12463 Peak 2133, blue line graph. Keep actual supplied screenshot, transformed only to fit the phone perspective; natural cropping from composition is allowed. No other text outside phone except exact headline. No watermark.
```

## prompt1_revision

```text
Edit input 1 (the generated Android marketing image), using input 2 (original Android analytics screenshot) as the exact replacement for phone display content. Keep the outside-phone artwork completely unchanged: headline "Check your whole stack. Close the laptop.", black/blue blocks, line-and-dot motif, cartoon woman, phone frame, centered circular Android punch-hole, tilt, crop, lighting.
Fix ONLY the screen fidelity. Paste the original screenshot uniformly into the tilted display, preserving all original relative positions, exact text, typography, icons, values and colors. In particular "Sample data" and "Exit preview" must be ON THE SAME ROW, near upper left and upper right respectively. The BACK arrow, "studio-web" title and REFRESH icon must be ON THE NEXT SAME ROW. Do not move Exit preview into the title row or refresh into the domain row. "S" icon and "studio.example" pill occupy the next row. Below are "7 Days" and "Production" side by side; "Updated just now"; three metric cards VISITORS 12.5K +14%, PAGE VIEWS 18.3K +19%, BOUNCE RATE 34% -4%; graph tabs Visitors, Page Views, Bounce Rate; VISITORS 12463 with Peak 2133; blue graph; bottom Hosting, Registrars, Sites, About, Search navigation exactly as screenshot. Treat input 2 as a raster insert, never invent/recompose UI. Preserve all visible content with perspective transform only, occlusion/canvas crop as existing. No iOS notch/Dynamic Island. Output one PNG at 1080x1920 exactly 9:16.
```

## prompt2

```text
Use case: ads-marketing. Create ONE final Google Play promotional phone screenshot for Verceltics Android, image 2 of a coordinated four-image set. PNG exactly 9:16 portrait, at least 1080x1920; prefer 1440x2560.
Input 1: iOS mockup, visual composition/style reference. Input 2: Android Hosting screenshot, exact screen insert. Make an Android twin of input 1. Preserve the black upper block (36% height), solid electric cobalt-blue #147BFA lower block, large clean thin geometric sans-serif headline, flat cartoon illustration and purple starburst. Adapt spacing to 9:16 without changing layout.
Exact outside-phone headline, no punctuation: "Connect your hosting stack in one place", broken into "Connect your" / "hosting stack" / "in one place", white at upper left. Purple toothed starburst on right overlapping black/blue boundary and phone top, black bold badge text exactly "100% Open Source" on three lines "100%" / "Open" / "Source". Centered upright huge phone begins about 42% down canvas and is cropped by bottom. Curly black-haired cartoon woman with white headphones, orange top, chartreuse jacket and blue jeans, holding a tablet, cropped along left edge overlapping phone, exactly the reference's flat hand-drawn style.
Only change the hardware and phone screen. Generic unbranded modern Android handset: flat display, thin equal bezels, small centered circular punch-hole camera, modest rounded corners and silver edge. NO notch, Dynamic Island, pill camera, Apple styling or hardware branding.
CRITICAL: insert input 2 as an intact screen image, not a reinterpretation. Preserve its entire relative UI geometry, all exact text, icons and provider colors. It includes Android 11:17 status bar; "Connect your first service"; "Pick a provider to get started. You can add more at any time."; Hosting blue selected, Registrars, Sites; Search hosting; CONNECT A HOSTING PLATFORM; Connect Vercel, Cloudflare, Netlify, Railway, Render, DigitalOcean, partial Heroku. Keep descriptions as supplied. Screen content should scale uniformly, never individually reposition app elements or remove the top title/search bar. Only natural occlusion by badge/cartoon or crop below canvas is allowed. No iOS UI. No text outside phone except the exact headline and badge. No watermark.
```

## prompt3

```text
Use case: ads-marketing. Create ONE final Google Play promotional phone screenshot for Verceltics Android, image 3 of a coordinated four-image set. PNG exactly 9:16 portrait, at least 1080x1920, prefer 1440x2560.
Input 1 is the iOS mockup: composition/style reference. Input 2 is Android Sites connect screenshot: exact screen content.
Recreate input 1 as an Android twin adapting to 9:16. Solid black upper area about 65% of canvas, electric cobalt-blue #147BFA lower area. Huge upright silver-rim phone in upper area: phone top cropped above canvas, lower rounded edge at about 60% height. Purple toothed starburst overlaps right-middle of phone with bold black verbatim text "25+ Integrations", split "25+" / "Integrations". Large thin clean geometric sans-serif BLACK headline left-aligned on the lower BLUE block, verbatim "Search, analytics, speed, and uptime in one workspace". Preserve reference line breaks "Search, analytics," / "speed, and" / "uptime in" / "one" / "workspace"; fit every word completely within canvas. Same flat cartoon woman with curly black hair, golden-yellow top, olive-green sleeves, salmon trousers, holding a pale smartphone, cropped at the right and bottom overlapping the phone and blue area. Use exactly the reference's illustration style, palette and layering.
Phone hardware must be generic unbranded modern Android, flat display, thin even bezels, modest corner rounding, small CENTERED circular punch-hole if top is visible. No notch, Dynamic Island, pill, Apple hardware features, hardware logos/names.
SCREEN FIDELITY: faithfully insert the supplied Android Sites screenshot without redesigning. Because phone TOP is cropped by canvas, its upper Android status/onboarding/tabs/search content may be naturally above the canvas, exactly like the reference composition, leaving CONNECT A SITE SERVICE and the provider rows visible. Keep actual relative positions and colors from screenshot. Provider rows in supplied order: Connect Google Search Console; Connect Google Analytics; Connect PageSpeed & CrUX; Connect Bing Webmaster; Connect Microsoft Clarity; Connect Plausible; Connect Umami. Keep every visible description, icon and arrow verbatim as supplied; natural badge/cartoon occlusion allowed. Preserve Android colored cards instead of iOS dark monochrome cards. No invented UI, iOS sheet handle or iOS elements. No outside-phone text except the exact headline and badge, no watermark.
```

## prompt3_revision

```text
Edit input 1, the generated Android Sites promotional artwork, ONLY to make its phone screen faithfully match input 2, the supplied Android Sites screenshot. Keep headline "Search, analytics, speed, and uptime in one workspace", purple "25+ Integrations" starburst, illustration, black/blue background, phone frame and crop completely unchanged.
Phone display must use source screenshot rather than content from iOS reference. Keep its exact provider icon shapes, solid colored cards, text, relative spacing. Preserve source descriptions:
Google Search Console: "Search performance, indexing, sitemaps and URL inspection"
Google Analytics: "GA4 visitors, sessions, traffic, events and realtime"
PageSpeed & CrUX: "Lighthouse audits and Chrome UX field data"
Bing Webmaster: "Bing search traffic, crawling and verified sites"
Microsoft Clarity: "Behavioral insights, sessions and interaction signals"
Plausible: "Privacy-friendly visitors, visits, views and engagement"
The last Umami row must be clipped by the source screenshot's black footer as in input 2. Do NOT invent its description "30-day traffic across Cloud or self-hosted sites" from the iOS reference. At phone bottom, show the supplied black footer with the original blue "Explore sample data" control and thin white Android navigation gesture bar. Never add iOS elements or notch. Phone upper content is naturally off canvas as in existing composition. Only change screen content, no text outside phone beyond existing exact headline and badge. Output PNG portrait 9:16.
```

## prompt4

```text
Use case: ads-marketing. Create ONE final Google Play promotional phone screenshot for Verceltics Android, image 4 of a coordinated four-image set. PNG exactly 9:16 portrait, at least 1080x1920, prefer 1440x2560.
Input 1: iOS mockup visual composition and illustration reference. Input 2: Android Registrars screenshot, ONLY source of screen content.
Make the Android twin of input 1, adapting reference to 9:16. Solid black upper block about 36% height and electric cobalt-blue #147BFA lower area. Large thin clean geometric sans-serif WHITE headline at upper left, exact "Domains, DNS, and renewals from your phone", with line breaks "Domains, DNS," / "and renewals" / "from your" / "phone". No badge. Huge upright front-view phone centered in lower area, top at about 40% canvas height, cropped by bottom. Retain flat cartoon woman cropped along left and bottom overlapping phone: curly black hair, yellow top with light blue/black horizontal trim, olive-green sleeves, salmon trousers, same reference illustration style and composition.
Change only device and screen. Generic unbranded modern Android handset, flat display, thin even bezels, silver frame, modest rounded corners, a small CENTERED circular punch-hole camera. No notch, pill or Dynamic Island, no Apple hardware styling, no hardware brands/names.
Faithfully insert input 2 into phone display as a uniform image: preserve Android 11:17 status bar, title "Connect your first service", subtitle "Pick a provider to get started. You can add more at any time.", Hosting/Registrars/Sites tabs with BLUE selected Registrars, Search registrars field, CONNECT A REGISTRAR, colored cards in supplied order Connect Name.com, Connect Namecheap, Connect Porkbun, Connect Spaceship, Connect Dynadot, Connect NameSilo, partial Connect Gandi. Preserve every visible description and provider icon exactly as Android input. Do not substitute iOS UI or iOS sheet handle, do not omit/reposition screen controls, do not invent content. Uniform scaling and natural canvas crop/cartoon occlusion only. No outside-phone text except exact headline. No watermark.
```


