import Image from "next/image";
import type { CSSProperties } from "react";

type Provider = { name: string; icon: string };
type ProviderGroup = { id: string; label: string; count: number; accent: string; tagline: string; providers: readonly Provider[] };

const groups: readonly ProviderGroup[] = [
  {
    id: "hosting",
    label: "Hosting",
    count: 10,
    accent: "#146cff",
    tagline: "Deploys, logs, DNS, jobs & releases",
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
    accent: "#d9dee7",
    tagline: "Domains, renewals, contacts & transfers",
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
    accent: "#9747ff",
    tagline: "Search, traffic, speed & uptime",
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
    <div className="provider-banks">
      {groups.map((group) => (
        <section className={`provider-bank provider-bank--${group.id}`} key={group.id} style={{ "--bank-accent": group.accent } as CSSProperties}>
          <header>
            <p>{group.label} / {group.count}</p>
            <h3>{group.tagline}</h3>
          </header>
          <ul>
            {group.providers.map((provider) => (
              <li key={provider.name} translate="no">
                <span className="provider-mark"><span className="provider-icon-slot"><Image alt="" fill sizes="22px" src={`/providers/${provider.icon}`} /></span></span>
                <span>{provider.name}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
