import Image from "next/image";
import Link from "next/link";

import { ArrowUpRight } from "@/components/arrow-up-right";
import { MobileNavigation } from "@/components/mobile-navigation";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export function SiteHeader() {
  return (
    <>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <header className="site-header">
        <div className="header-inner">
          <Link aria-label="Verceltics home" className="header-brand" href="/" translate="no">
            <span className="brand-mark"><Image alt="" height={34} priority src="/icon.png" width={34} /></span>
            <span><strong>Verceltics</strong><small>Mobile operations</small></span>
          </Link>

          <nav aria-label="Primary navigation" className="header-nav">
            <Link href="/#patchbay">Connections</Link>
            <Link href="/#workflows">Workflows</Link>
            <Link href="/#privacy">Privacy</Link>
            <Link href="/#pricing">Pricing</Link>
          </nav>

          <MobileNavigation githubUrl={GITHUB} />

          <div className="header-actions">
            <a className="source-link" href={GITHUB} rel="noreferrer" target="_blank">Open source <ArrowUpRight /></a>
            <a className="header-store" href={APP_STORE} rel="noreferrer" target="_blank">Get the app <ArrowUpRight /></a>
          </div>
        </div>
      </header>
    </>
  );
}
