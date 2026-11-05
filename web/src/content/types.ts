export type Task = {
  slug: string;
  title: string;
  summary: string;
  context: string;
  steps: string[];
  verify: string;
  caution: string;
};

export type ProviderDetail = {
  slug: string;
  published: string;
  updated: string;
  overview: string[];
  capabilities: string[];
  fields: string[];
  setup: string[];
  troubleshooting: string[];
  limit: string;
  reference: string;
  source: string;
  tasks: Task[];
};

export type ArticleSection = { heading: string; paragraphs?: string[]; items?: string[] };
export type Article = {
  slug: string;
  title: string;
  description: string;
  category: string;
  published: string;
  updated: string;
  provider?: string;
  sections: ArticleSection[];
  related: string[];
  sources: { label: string; url: string }[];
};
