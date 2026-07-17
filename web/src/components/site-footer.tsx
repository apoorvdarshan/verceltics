import Image from "next/image";
import Link from "next/link";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="footer-route" aria-hidden="true"><i /><i /><i /></div>
      <div className="site-footer-grid">
        <div className="footer-brand">
          <Image alt="" height={52} src="/icon.png" width={52} />
          <div>
            <strong>Verceltics</strong>
            <p>Native infrastructure for iPhone and iPad.</p>
          </div>
        </div>

        <nav aria-label="Product links" className="footer-links">
          <strong>Product</strong>
          <Link href="/#hosting">Hosting</Link>
          <Link href="/#registrars">Registrars</Link>
          <Link href="/#sites">Sites</Link>
          <Link href="/#providers">Providers</Link>
        </nav>

        <nav aria-label="Project links" className="footer-links">
          <strong>Project</strong>
          <a href={APP_STORE} rel="noreferrer" target="_blank">App Store</a>
          <a href={GITHUB} rel="noreferrer" target="_blank">Source code</a>
          <Link href="/privacy">Privacy</Link>
          <Link href="/terms">Terms</Link>
        </nav>

        <div className="footer-note">
          <strong>Direct by design.</strong>
          <p>Credentials stay on your device. Provider requests go to official APIs.</p>
        </div>
      </div>

      <div className="site-footer-bottom">
        <span>© 2026 Verceltics</span>
        <span>Independent and open source</span>
        <span>Not affiliated with supported providers.</span>
      </div>
    </footer>
  );
}
