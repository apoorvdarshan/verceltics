import Image from "next/image";
import type { CSSProperties } from "react";

type Provider = { name: string; icon: string };
type ProviderGroup = { id: string; label: string; count: number; accent: string; providers: readonly Provider[] };

const groups: readonly ProviderGroup[] = [
  {
    id: "hosting",
    label: "Hosting",
    count: 10,
    accent: "#2c91ff",
    providers: [
      { name: "Vercel", icon: "VercelMark.svg" },
      { name: "Cloudflare", icon: "CloudflareMark.svg" },
      { name: "Netlify", icon: "NetlifyMark.svg" },
      { name: "Railway", icon: "RailwayMark.svg" },
      { name: "Render", icon: "RenderMark.svg" },
      { name: "DigitalOcean", icon: "DigitalOceanMark.svg" },
      { name: "Heroku", icon: "HerokuMark.svg" },
      { name: "Fly.io", icon: "FlyMark.svg" },
      { name: "Firebase Hosting", icon: "FirebaseMark.svg" },
      { name: "AWS Amplify", icon: "AWSAmplifyMark.svg" },
    ],
  },
  {
    id: "registrars",
    label: "Registrars",
    count: 8,
    accent: "#f1f3f7",
    providers: [
      { name: "Name.com", icon: "NameDotComMark.svg" },
      { name: "Namecheap", icon: "NamecheapMark.svg" },
      { name: "Porkbun", icon: "PorkbunMark.svg" },
      { name: "Spaceship", icon: "SpaceshipMark.svg" },
      { name: "Dynadot", icon: "DynadotMark.svg" },
      { name: "NameSilo", icon: "NameSiloMark.svg" },
      { name: "Gandi", icon: "GandiMark.svg" },
      { name: "GoDaddy", icon: "GoDaddyMark.svg" },
    ],
  },
  {
    id: "sites",
    label: "Sites",
    count: 9,
    accent: "#a154ff",
    providers: [
      { name: "Google Search Console", icon: "GoogleSearchConsoleMark.svg" },
      { name: "Google Analytics", icon: "GoogleAnalyticsMark.svg" },
      { name: "PageSpeed & CrUX", icon: "PageSpeedMark.svg" },
      { name: "Bing Webmaster", icon: "BingWebmasterMark.svg" },
      { name: "Microsoft Clarity", icon: "MicrosoftClarityMark.svg" },
      { name: "Plausible", icon: "PlausibleMark.svg" },
      { name: "Umami", icon: "UmamiMark.svg" },
      { name: "UptimeRobot", icon: "UptimeRobotMark.svg" },
      { name: "Better Stack", icon: "BetterStackMark.svg" },
    ],
  },
] as const;

export function ProviderDirectory() {
  return (
    <div className="provider-groups">
      {groups.map((group) => (
        <section className={`provider-group provider-group--${group.id}`} key={group.id} style={{ "--group-accent": group.accent } as CSSProperties}>
          <header>
            <span aria-hidden="true" className="group-route" />
            <div><h3>{group.label}</h3><p>{group.count} direct connections</p></div>
          </header>
          <ul>
            {group.providers.map((provider) => (
              <li key={provider.name} translate="no">
                <span className="provider-mark"><Image alt="" height={22} src={`/providers/${provider.icon}`} width={22} /></span>
                <span>{provider.name}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
