import Image from "next/image";
import Link from "next/link";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export function ArrowUpRight({ className = "" }: { className?: string }) {
  return (
    <svg aria-hidden="true" className={className} fill="none" viewBox="0 0 20 20">
      <path d="M5.5 14.5 14.5 5.5M7 5.5h7.5V13" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.6" />
    </svg>
  );
}

export function SiteHeader() {
  return (
    <>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <header className="site-header">
        <div className="site-header-inner">
          <Link aria-label="Verceltics home" className="brand-lockup" href="/" translate="no">
            <Image alt="" className="brand-icon" height={36} priority src="/icon.png" width={36} />
            <span><strong>Verceltics</strong><small>Native ops</small></span>
          </Link>

          <nav aria-label="Primary navigation" className="site-nav">
            <Link href="/#workspaces">Workspaces</Link>
            <Link href="/#providers">Providers</Link>
            <Link href="/#privacy">Privacy</Link>
            <Link href="/#pricing">Pricing</Link>
          </nav>

          <a className="header-download" href={APP_STORE} rel="noreferrer" target="_blank">
            Get Verceltics
            <ArrowUpRight />
          </a>
        </div>
      </header>
    </>
  );
}

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer-grid">
        <div className="footer-brand">
          <Link aria-label="Verceltics home" className="brand-lockup" href="/" translate="no">
            <Image alt="" className="brand-icon" height={40} src="/icon.png" width={40} />
            <span><strong>Verceltics</strong><small>Native ops</small></span>
          </Link>
          <p>Hosting, domains, analytics, search, speed, and uptime on iPhone and iPad.</p>
        </div>

        <nav aria-label="Product links" className="footer-links">
          <strong>Product</strong>
          <a href={APP_STORE} rel="noreferrer" target="_blank">App Store</a>
          <a href={GITHUB} rel="noreferrer" target="_blank">Source code</a>
          <Link href="/#providers">27 providers</Link>
        </nav>

        <nav aria-label="Legal links" className="footer-links">
          <strong>Company</strong>
          <Link href="/privacy">Privacy</Link>
          <Link href="/terms">Terms</Link>
          <a href="mailto:ad13dtu@gmail.com">Contact</a>
        </nav>
      </div>

      <div className="site-footer-bottom">
        <span>© 2026 Verceltics</span>
        <span>Independent &amp; open source</span>
        <span>Not affiliated with supported providers.</span>
      </div>
    </footer>
  );
}
