import Image from "next/image";
import Link from "next/link";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="footer-plate">
        <div className="footer-brand">
          <span><Image alt="" height={52} src="/icon.png" width={52} /></span>
          <div><strong>Verceltics</strong><p>Check production without opening the laptop.</p></div>
        </div>

        <div className="footer-readout">
          <span><i /> Architecture status</span>
          <strong>No credential proxy.</strong>
          <p>Your device talks directly to official provider APIs.</p>
        </div>

        <nav aria-label="Product links" className="footer-links">
          <strong>Instrument</strong>
          <Link href="/#patchbay">27 connections</Link>
          <Link href="/#workflows">Workflows</Link>
          <a href={APP_STORE} rel="noreferrer" target="_blank">App Store</a>
        </nav>

        <nav aria-label="Project links" className="footer-links">
          <strong>Project</strong>
          <a href={GITHUB} rel="noreferrer" target="_blank">Source code</a>
          <Link href="/privacy">Privacy</Link>
          <Link href="/terms">Terms</Link>
        </nav>
      </div>
      <div className="footer-bottom">
        <span>© 2026 Verceltics</span>
        <span>Independent · Open source · Not affiliated with supported providers</span>
      </div>
    </footer>
  );
}
