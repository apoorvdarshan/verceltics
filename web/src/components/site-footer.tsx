import Image from "next/image";
import Link from "next/link";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="footer-main">
        <div className="footer-brand">
          <Image alt="" height={54} src="/icon.png" width={54} />
          <div><strong>Verceltics</strong><p>Your infrastructure, native on iPhone and iPad.</p></div>
        </div>

        <nav aria-label="Product links" className="footer-links">
          <strong>Explore</strong>
          <Link href="/#connections">27 connections</Link>
          <Link href="/#workflows">Workflows</Link>
          <Link href="/#privacy">Privacy</Link>
          <Link href="/#pricing">Pricing</Link>
        </nav>

        <nav aria-label="Project links" className="footer-links">
          <strong>Project</strong>
          <a href={APP_STORE} rel="noreferrer" target="_blank">App Store</a>
          <a href={GITHUB} rel="noreferrer" target="_blank">Source code</a>
          <Link href="/privacy">Privacy policy</Link>
          <Link href="/terms">Terms</Link>
        </nav>

        <div className="footer-statement">
          <span>NO PROXY</span>
          <strong>Credentials stay on your device.</strong>
          <p>Requests go directly to official provider APIs.</p>
        </div>
      </div>
      <div className="footer-bottom"><span>© 2026 Verceltics</span><span>Independent · Open source · Not affiliated with supported providers</span></div>
    </footer>
  );
}
