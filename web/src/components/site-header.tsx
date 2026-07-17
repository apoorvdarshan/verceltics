import Image from "next/image";
import Link from "next/link";

import { ArrowUpRight } from "@/components/arrow-up-right";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

function RouteThreads() {
  return (
    <span aria-hidden="true" className="route-threads">
      <i className="route-thread route-thread--hosting" />
      <i className="route-thread route-thread--registrars" />
      <i className="route-thread route-thread--sites" />
    </span>
  );
}

export function SiteHeader() {
  return (
    <>
      <a className="skip-link" href="#main-content">Skip to content</a>

      <aside aria-label="Primary navigation" className="route-rail">
        <Link aria-label="Verceltics home" className="rail-brand" href="/" translate="no">
          <Image alt="" height={42} priority src="/icon.png" width={42} />
          <strong>Verceltics</strong>
        </Link>

        <nav aria-label="Workspaces" className="rail-workspaces">
          <Link className="rail-route rail-route--hosting" href="/#hosting"><i />Hosting</Link>
          <Link className="rail-route rail-route--registrars" href="/#registrars"><i />Registrars</Link>
          <Link className="rail-route rail-route--sites" href="/#sites"><i />Sites</Link>
        </nav>

        <nav aria-label="Site links" className="rail-secondary">
          <Link href="/#providers">Providers</Link>
          <Link href="/#privacy">Privacy</Link>
          <a href={GITHUB} rel="noreferrer" target="_blank">Source</a>
        </nav>

        <a aria-label="Get Verceltics on the App Store" className="rail-store" href={APP_STORE} rel="noreferrer" target="_blank">
          <span>Get app</span>
          <ArrowUpRight />
        </a>
      </aside>

      <header className="mobile-header">
        <Link aria-label="Verceltics home" className="mobile-brand" href="/" translate="no">
          <Image alt="" height={38} priority src="/icon.png" width={38} />
          <span>Verceltics</span>
        </Link>
        <RouteThreads />
        <a className="mobile-store" href={APP_STORE} rel="noreferrer" target="_blank">Get app <ArrowUpRight /></a>
      </header>
    </>
  );
}
