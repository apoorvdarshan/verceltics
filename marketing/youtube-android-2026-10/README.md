# Verceltics Android beta — YouTube A/B thumbnails

Created October 10, 2026 with the built-in image_gen tool (no CLI fallback). Three distinct concepts. Upload JPEGs are 1280 × 720, JPEG quality 92, under 2 MB. Original final generated PNGs are 1672 × 941 and retained in `originals/`. JPEG export used ImageMagick for resizing and encoding only.

Exact headlines and Android centered punch-hole hardware were visually inspected. Screens were generated using the real Android screenshots as references; small rendered UI details can differ from source pixels. The warm chart received a targeted correction. Logo-wall connectors received a targeted correction. No commit or push requested or performed.

## 01-warm-editorial

Upload: `01-warm-editorial.jpg`

Original: `originals/01-warm-editorial.png`

References in input order:

- `marketing/Verceltics-Logo.png`
- `marketing/youtube-ab-2026-10-v2/selected/A-Your-Stack-Anywhere.jpg`
- `store/google-play/screenshots/phone/03-analytics.png`

### Exact generation prompt

Use case: ads-marketing. Create one beautiful expensive editorial product-launch YouTube thumbnail, landscape 16:9, 1280x720 composition, readable at 320px wide. Inputs in order: 1 exact Verceltics logo, preserve its blue upper descending branch with circular endpoints, short white middle line and violet lower rising branch faithfully; 2 warm editorial STYLE AND LAYOUT reference only, replace its copy and iPhone; 3 actual Android analytics screenshot, use this exact screen as a texture on the phone, preserve layout, data and UI rather than inventing UI.
Concept warm-editorial: warm off-white paper studio with tactile subtle paper texture and soft natural daylight shadows. Small exact logo on black rounded square plus wordmark spelled exactly "Verceltics" at top left. Elegant high-contrast bold serif headline on left, exactly TWO lines: "Your stack," then "now on Android." The ENTIRE second line must stay together on ONE line in white serif on a cobalt blue rectangular highlight bar. Carefully size the second line to fit; first line may be larger. Headline visual priority, generous 50px safety margins, sophisticated magazine typography. One modern generic Android phone gently angled on right, flat display, thin even bezels, small centered circular punch-hole camera, NO notch or Dynamic Island, no Apple logo, no hardware brand. Phone screen faithfully displays input 3, including 12.5K visitors, 18.3K page views, 34% bounce rate, dark analytics cards and blue chart; screen can crop with the phone at bottom edge as in reference. No device overlaps the headline. No other objects. No additional copy, faces, watermarks, promotional URLs, CTA buttons, Google Play badge, Android robot, neon rim lights, sci-fi objects. Existing sample domain labels within faithful screenshot are UI content only. Quiet premium crafted photography, ink black, cream and cobalt.

### Exact final correction prompt

Correction references: initial generated thumbnail, then `store/google-play/screenshots/phone/03-analytics.png`.

Use case: compositing. Edit input 1 warm editorial thumbnail, preserving its entire typography, wordmark, brand mark, cream paper, composition, lighting and Android hardware exactly. Input 2 is the actual analytics screenshot that must be displayed on this phone. Correct ONLY the phone screen: faithfully reproduce the screenshot 2 layout and its actual BLUE CHART. The chart is a straight steadily rising blue line from left around 1.4K up to right around 2.1K with a blue area beneath, NOT a low flat curved valley. Preserve the 3.0K and 2.0K tick labels and dashed horizontal reference line. Keep actual screenshot metrics 12.5K / 18.3K / 34%, +14% / +19% / -4%, total 12463 Peak 2133. Use screenshot as faithful texture with perspective. Do not rearrange or invent UI. Retain small centered circular Android punch hole, no notch or island. Preserve EXACT headline "Your stack," / "now on Android." and exact "Verceltics". Output same landscape 16:9.

## 02-mint-studio

Upload: `02-mint-studio.jpg`

Original: `originals/02-mint-studio.png`

References in input order:

- `marketing/Verceltics-Logo.png`
- `marketing/youtube-ab-2026-10-v2/selected/B-Your-Stack-Simplified.jpg`
- `store/google-play/screenshots/phone/02-hosting.png`
- `store/google-play/screenshots/phone/04-sites.png`

### Exact generation prompt

Use case: ads-marketing. Create ONE premium crafted product-launch YouTube thumbnail, landscape 16:9, composed for 1280x720 and readable at 320px. Input 1 is EXACT Verceltics logo: faithfully preserve blue upper descending branch with circular endpoints, white short middle line, violet lower rising branch. Input 2 is studio STYLE/composition reference only, replace lavender with pale mint green and replace Apple phones. Inputs 3 and 4 are actual Android screenshot textures, NOT inspiration: faithfully place screenshot 3 Hosting on front phone and screenshot 4 Sites on rear phone. Preserve their whole visible layout, text, cards, status bar and bottom navigation; no invented UI.
Concept mint-studio: soft pastel mint / Android-green studio background and low satin mint plinth, soft beautiful photographic light, calm expensive product campaign. Top centered small black rounded logo square plus exact black "Verceltics" wordmark. Below it centered massive bold CONDENSED black headline exactly TWO lines: "YOUR STACK," then "NOW ON ANDROID." Keep second line together, headline visual priority and generous safe margins. Bottom two thirds two upright modern generic Android phones: front slightly left showing Hosting input 3 and rear slightly right showing Sites input 4. Screens flat with thin even bezels and ONLY small centered circular punch-hole camera; no notch, Dynamic Island, Apple logo, hardware branding. Devices separated enough to see Sites cards. Front screen has Hosting title, search, sample workspace and studio-web project; rear has Sites title and Search Console, PageSpeed & CrUX, Analytics cards. Use exact screenshot textures, fit faithfully inside devices, not redesigned UI. Small white rounded physical provider tiles on little pedestals beside phones: recognizable black Vercel triangle left; orange Cloudflare cloud and red-orange Namecheap mark right. No extra copy on tiles. Screens must not cover headline. Subtle natural shadows, elegant proportions, no neon, faces, watermark, promotional URL, CTA button, Google Play badge, robot mascot or sci-fi object. Existing sample domain labels in screenshot UI only.

## 03-logo-wall

Upload: `03-logo-wall.jpg`

Original: `originals/03-logo-wall.png`

References in input order:

- `marketing/Verceltics-Logo.png`
- `marketing/youtube-ab-2026-10-v2/selected/C-Your-Tools-Together.jpg`

### Exact generation prompt

Use case: ads-marketing. ONE expensive crafted product-launch editorial YouTube thumbnail, landscape 16:9, 1280x720 composition, readable at 320px wide. Input 1 EXACT Verceltics brand logo, faithfully preserve geometry: blue upper branch begins left with circle, runs horizontally then smoothly descends to right horizontal end with circle; short straight white middle line begins with left circle; violet bottom branch begins left circle, horizontal then curves up to an endpoint without circle. Input 2 is white logo-wall style reference only, replace copy and use exactly FOUR provider tiles, no Google G.
Concept logo-wall: pristine clean white background, premium physical product photograph of a shallow 3D icon arrangement on right, soft precisely controlled natural shadows. Top left small black rounded Verceltics logo square and exact black "Verceltics" wordmark. Left headline huge heavy very condensed black letters exactly "ANDROID" on first line then huge cobalt blue "BETA." on second line. Keep both words entirely in left half, with generous safe margins, maximum legibility and exquisite spacing. Right half: a LARGE glossy 3D black rounded-square Verceltics app icon containing the EXACT input 1 mark without any distortion of branching geometry, perspective applied naturally to whole face only. Around it FOUR small white rounded 3D tiles: black Vercel triangle top-left, orange Cloudflare cloud top-right, red-orange recognizable Namecheap ribbon left/lower-left, orange-yellow Google Analytics bars lower-right. A couple fine blue/violet connecting lines quietly connect tiles behind hero icon; no thick spaghetti ribbons. Carefully composed shallow relief, restrained gloss, beautiful delicate shadows, quiet white negative space. No phone. No other logos. No extra copy, faces, watermarks, URLs, CTA buttons, Google Play badge, Android robot mascot, neon rim lights, sci-fi objects. The large headline has visual priority.

### Exact final correction prompt

Correction references: initial generated thumbnail, then `marketing/Verceltics-Logo.png`.

Use case: precise-object-edit. Edit input 1 logo-wall thumbnail. Preserve exact headline "ANDROID" black and "BETA." cobalt blue, wordmark "Verceltics", typography, white background, all four provider tile positions and lighting. Two targeted corrections: (1) replace the thick blue and violet connector ribbons behind the black hero tile with just TWO delicate fine lines, approximately 2-3 pixels wide at 1280x720, discreet blue and violet strokes, no thick cables. (2) faithfully apply input 2 exact Verceltics logo as the hero icon face artwork, preserving its branch geometry: upper blue branch starts with a round left endpoint, a LONG straight horizontal segment, smooth descent, then LONG straight horizontal segment ending at right round endpoint. White middle stroke is short and straight with round left endpoint. Violet lower branch starts with round left endpoint and LONG straight horizontal segment before curving smoothly upward to rounded stroke end. Perspective-transform whole artwork naturally onto face, do not reshape logo. Keep subtle glossy black 3D icon material. Do not add anything else. Same landscape 16:9 composition and margins.


## A/B test titles

1. Verceltics for Android: Vercel, Cloudflare and Domains in One App (Beta)
2. Your Whole Web Stack, Now on Android | Verceltics Beta
3. I Built a Native Android App for Vercel, Cloudflare and 25 More Services
