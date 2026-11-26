import providerData from "./providers.json";
import workflowData from "./workflows.json";
import blogData from "./blog.json";
import { integrationGroups } from "@/data/integrations";
import type { Article, ProviderDetail } from "./types";

export const providers: ProviderDetail[] = providerData;
export const blogPosts: Article[] = blogData;
const repository = "https://github.com/apoorvdarshan/verceltics/blob/main/";

export function providerCatalog(slug: string) {
  for (const group of integrationGroups) {
    const provider = group.providers.find(item => item.slug === slug);
    if (provider) return { provider, group };
  }
  throw new Error(`Unknown provider: ${slug}`);
}

export function providerSources(detail: ProviderDetail) {
  const { provider } = providerCatalog(detail.slug);
  return [
    { label: `${provider.name} documentation`, url: detail.reference },
    { label: "Verceltics provider implementation", url: `${repository}${detail.source}` },
    { label: "Native platform support and limitations", url: `${repository}docs/native-mobile-architecture.md` },
  ];
}

export const guides: Article[] = [
  ...providers.flatMap(detail => {
    const { provider, group } = providerCatalog(detail.slug);
    const setup: Article = {
      slug: `connect-${detail.slug}`, title: `Connect ${provider.name} to Verceltics`,
      description: `Set up ${provider.name} in Verceltics. Connection method: ${provider.connection}. Check required fields, permissions and setup problems on iOS and Android.`,
      category: "Setup", published: detail.published, updated: detail.updated, provider: detail.slug,
      sections: [
        { heading: "What you need", paragraphs: [`Use a ${provider.name} account that can access the resources you want to inspect. The connection belongs in the ${group.label} workspace. Prepare the following values for the installed app’s protected connection form; never post them in GitHub issues, Discord or screenshots.`], items: detail.fields },
        { heading: "Set up the connection", items: detail.setup },
        { heading: "If the expected data does not appear", paragraphs: detail.troubleshooting },
        { heading: "Check the permission boundary", paragraphs: [detail.limit] },
        { heading: "After connecting", paragraphs: [`Find a known ${provider.name} resource and verify the account context before interpreting its report or state. Connections and list browsing stay free; Pro unlocks supported detail views and provider tools. A successful connection does not prove permission for every operation.`], items: ["Keep provider credentials in the app’s protected fields. iOS uses device-only Keychain records; Android uses Keystore-backed encryption.", "Refresh before a decision that depends on current provider state. Treat a saved view and a new successful response as separate observations."] },
      ],
      related: [`/integrations/${detail.slug}`, ...detail.tasks.map(task => `/guides/${task.slug}`), "/guides/provider-authentication-permission-errors"],
      sources: providerSources(detail),
    };
    return [setup, ...detail.tasks.map(task => ({
      slug: task.slug, title: task.title, description: task.summary, category: group.label,
      published: detail.published, updated: detail.updated, provider: detail.slug,
      sections: [
        { heading: "When this check helps", paragraphs: [task.context] },
        { heading: "Follow the resource context", items: task.steps },
        { heading: "Verify the result", paragraphs: [task.verify] },
        { heading: "Limits and next steps", paragraphs: [task.caution, detail.limit] },
      ],
      related: [`/integrations/${detail.slug}`, `/guides/connect-${detail.slug}`, ...detail.tasks.filter(item => item.slug !== task.slug).map(item => `/guides/${item.slug}`)],
      sources: providerSources(detail),
    } satisfies Article))];
  }),
  ...workflowData,
];
