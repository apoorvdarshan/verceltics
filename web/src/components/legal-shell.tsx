import type { ReactNode } from "react";
import Link from "next/link";

import { SiteFooter, SiteHeader } from "@/components/site-chrome";

type LegalSection = {
  id: string;
  label: string;
};

type LegalShellProps = {
  title: string;
  eyebrow: string;
  summary: string;
  asideDescription: string;
  updated: string;
  sections: readonly LegalSection[];
  children: ReactNode;
};

export function LegalShell({ title, eyebrow, summary, asideDescription, updated, sections, children }: LegalShellProps) {
  return (
    <div className="legal-page site-canvas">
      <SiteHeader />
      <main className="legal-main">
        <aside className="legal-aside">
          <span className="micro-label">Verceltics legal</span>
          <strong className="legal-aside-title">{title}</strong>
          <p>{asideDescription}</p>
          <nav aria-label={`${title} sections`} className="legal-nav">
            {sections.map((section) => <a href={`#${section.id}`} key={section.id}>{section.label}</a>)}
          </nav>
          <Link className="text-link" href="/">← Back to home</Link>
        </aside>

        <article>
          <header className="legal-content-header">
            <span className="status-pill status-pill--signal"><i /> {eyebrow}</span>
            <h1>{title}</h1>
            <p>{summary}</p>
            <span className="legal-date">Last updated {updated} · applies to Verceltics 2.0</span>
          </header>
          <div className="legal-prose">{children}</div>
        </article>
      </main>
      <SiteFooter />
    </div>
  );
}
