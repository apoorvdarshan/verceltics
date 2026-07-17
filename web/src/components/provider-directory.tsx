import Image from "next/image";
import type { CSSProperties } from "react";

type Provider = { name: string; icon: string };
type ProviderGroup = { id: string; label: string; count: number; accent: string; detail: string; providers: readonly Provider[] };

const groups: readonly ProviderGroup[] = [
  {
    id: "hosting",
    label: "Hosting",
    count: 10,
    accent: "#2450ff",
    detail: "Deploys, logs, DNS, jobs and releases",
    providers: [
      { name: "Vercel", icon: "VercelMark.svg" },
      { name: "Cloudflare", icon: "CloudflareMark.svg" },
      { name: "Netlify", icon: "NetlifyMark.svg" },
      { name: "Railway", icon: "RailwayMark.svg" },
      { name: "Render", icon: "RenderMark.svg" },
      { name: "DigitalOcean", icon: "DigitalOceanMark.svg" },
      { name: "Heroku", icon: "HerokuMark.svg" },
      { name: "Fly.io", icon: "FlyMark.svg" },
      { name: "Firebase", icon: "FirebaseMark.svg" },
      { name: "AWS Amplify", icon: "AWSAmplifyMark.svg" },
    ],
  },
  {
    id: "registrars",
    label: "Registrars",
    count: 8,
    accent: "#ff5637",
    detail: "Domains, renewals, contacts and transfers",
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
    label: "Site services",
    count: 9,
    accent: "#8050f5",
    detail: "Search, traffic, speed and uptime",
    providers: [
      { name: "Search Console", icon: "GoogleSearchConsoleMark.svg" },
      { name: "Google Analytics", icon: "GoogleAnalyticsMark.svg" },
      { name: "PageSpeed", icon: "PageSpeedMark.svg" },
      { name: "Bing Webmaster", icon: "BingWebmasterMark.svg" },
      { name: "Clarity", icon: "MicrosoftClarityMark.svg" },
      { name: "Plausible", icon: "PlausibleMark.svg" },
      { name: "Umami", icon: "UmamiMark.svg" },
      { name: "UptimeRobot", icon: "UptimeRobotMark.svg" },
      { name: "Better Stack", icon: "BetterStackMark.svg" },
    ],
  },
] as const;

export function ProviderPatchbay() {
  return (
    <div className="patchbay">
      {groups.map((group, groupIndex) => (
        <section
          className={`patch-rail patch-rail--${group.id}`}
          key={group.id}
          style={{ "--rail-accent": group.accent } as CSSProperties}
        >
          <header className="rail-label">
            <span>Bank 0{groupIndex + 1}</span>
            <h3>{group.label}</h3>
            <p>{group.detail}</p>
            <strong>{String(group.count).padStart(2, "0")} ports</strong>
          </header>
          <ul aria-label={`${group.label} providers. Scroll horizontally to inspect every connection.`} className="provider-ports" role="region" tabIndex={0}>
            {group.providers.map((provider, index) => (
              <li key={provider.name} translate="no">
                <span className="port-number">{String(index + 1).padStart(2, "0")}</span>
                <span className="port-socket"><span><Image alt="" fill sizes="28px" src={`/providers/${provider.icon}`} /></span></span>
                <strong>{provider.name}</strong>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
