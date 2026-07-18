export type Integration = {
  name: string;
  slug: string;
  icon: string;
  summary: string;
  connection: string;
};

export type IntegrationGroup = {
  id: "hosting" | "registrars" | "sites";
  label: string;
  heading: string;
  count: number;
  accent: string;
  detail: string;
  description: string;
  providers: readonly Integration[];
};

export const integrationGroups: readonly IntegrationGroup[] = [
  {
    id: "hosting",
    label: "Hosting",
    heading: "Hosting dashboards on iPhone and iPad",
    count: 10,
    accent: "#2450ff",
    detail: "Deploys, logs, DNS, jobs and releases",
    description:
      "Inspect projects, releases, environments, logs, domains and provider-specific operations. Available data and actions follow the connected account, provider API and plan.",
    providers: [
      { name: "Vercel", slug: "vercel", icon: "VercelMark.svg", summary: "Projects, deployments and Web Analytics", connection: "Personal access token" },
      { name: "Cloudflare", slug: "cloudflare", icon: "CloudflareMark.svg", summary: "Zones, Pages, Workers, DNS and analytics", connection: "Scoped API token" },
      { name: "Netlify", slug: "netlify", icon: "NetlifyMark.svg", summary: "Sites, deploys, domains and build controls", connection: "Personal access token" },
      { name: "Railway", slug: "railway", icon: "RailwayMark.svg", summary: "Projects, services, environments and logs", connection: "API token" },
      { name: "Render", slug: "render", icon: "RenderMark.svg", summary: "Services, deploys, jobs and environments", connection: "API key" },
      { name: "DigitalOcean", slug: "digitalocean", icon: "DigitalOceanMark.svg", summary: "Apps, deployments, logs and bandwidth", connection: "Personal access token" },
      { name: "Heroku", slug: "heroku", icon: "HerokuMark.svg", summary: "Apps, releases, dynos, domains and logs", connection: "Provider API credential" },
      { name: "Fly.io", slug: "fly-io", icon: "FlyMark.svg", summary: "Apps, Machines, regions and volumes", connection: "Access token" },
      { name: "Firebase Hosting", slug: "firebase-hosting", icon: "FirebaseMark.svg", summary: "Hosting sites, channels, versions and releases", connection: "Google OAuth" },
      { name: "AWS Amplify", slug: "aws-amplify", icon: "AWSAmplifyMark.svg", summary: "Apps, branches, jobs and domains", connection: "AWS credentials" },
    ],
  },
  {
    id: "registrars",
    label: "Registrars",
    heading: "Domain registrar and DNS tools",
    count: 8,
    accent: "#ff5637",
    detail: "Domains, renewals, contacts and transfers",
    description:
      "Open registrar-specific domain, DNS, renewal, transfer, privacy, contact and certificate tools where the provider API supports them. Confirmed writes remain provider-bound.",
    providers: [
      { name: "Name.com", slug: "name-com", icon: "NameDotComMark.svg", summary: "Domains, DNS, renewals, transfers and privacy", connection: "CORE API username and token" },
      { name: "Namecheap", slug: "namecheap", icon: "NamecheapMark.svg", summary: "Domains, DNS, contacts, renewals and transfers", connection: "API user, key and required ClientIp" },
      { name: "Porkbun", slug: "porkbun", icon: "PorkbunMark.svg", summary: "Domains, DNS, SSL, forwarding and marketplace", connection: "API key and secret" },
      { name: "Spaceship", slug: "spaceship", icon: "SpaceshipMark.svg", summary: "Domains, contacts, DNS and nameservers", connection: "API credentials" },
      { name: "Dynadot", slug: "dynadot", icon: "DynadotMark.svg", summary: "Domains, DNS, renewals, auctions and aftermarket", connection: "API key" },
      { name: "NameSilo", slug: "namesilo", icon: "NameSiloMark.svg", summary: "Domains, DNS, renewals, contacts and transfers", connection: "API key" },
      { name: "Gandi", slug: "gandi", icon: "GandiMark.svg", summary: "Domains, LiveDNS, certificates, mail and billing", connection: "Personal access token" },
      { name: "GoDaddy", slug: "godaddy", icon: "GoDaddyMark.svg", summary: "Domains, DNS, renewals, privacy and transfers", connection: "API key and secret" },
    ],
  },
  {
    id: "sites",
    label: "Site services",
    heading: "Website analytics, search, speed and uptime",
    count: 9,
    accent: "#8050f5",
    detail: "Search, traffic, speed and uptime",
    description:
      "Keep search, analytics, performance and monitoring services in separate native dashboards. Verceltics does not merge provider data or send it through a credential proxy.",
    providers: [
      { name: "Google Search Console", slug: "google-search-console", icon: "GoogleSearchConsoleMark.svg", summary: "Search performance, indexing, sitemaps and URL inspection", connection: "Google OAuth" },
      { name: "Google Analytics", slug: "google-analytics", icon: "GoogleAnalyticsMark.svg", summary: "GA4 visitors, sessions, traffic, events and real-time reports", connection: "Google OAuth" },
      { name: "PageSpeed & CrUX", slug: "pagespeed-crux", icon: "PageSpeedMark.svg", summary: "Lighthouse audits and Chrome UX field data", connection: "Site URL and Google API access" },
      { name: "Bing Webmaster", slug: "bing-webmaster", icon: "BingWebmasterMark.svg", summary: "Bing search traffic, crawling and verified sites", connection: "Bing API credential" },
      { name: "Microsoft Clarity", slug: "microsoft-clarity", icon: "MicrosoftClarityMark.svg", summary: "Behavioral insights, sessions and interaction signals", connection: "Clarity API credential" },
      { name: "Plausible", slug: "plausible", icon: "PlausibleMark.svg", summary: "Privacy-friendly visitors, visits, views and engagement", connection: "Cloud or self-hosted API access" },
      { name: "Umami", slug: "umami", icon: "UmamiMark.svg", summary: "30-day traffic across Cloud or self-hosted sites", connection: "Cloud or self-hosted API access" },
      { name: "UptimeRobot", slug: "uptimerobot", icon: "UptimeRobotMark.svg", summary: "Monitor state, uptime ratios and response time", connection: "Read-only API key" },
      { name: "Better Stack", slug: "better-stack", icon: "BetterStackMark.svg", summary: "Monitor state, check cadence and availability", connection: "Uptime API token" },
    ],
  },
] as const;

export const integrationCount = integrationGroups.reduce((total, group) => total + group.providers.length, 0);
