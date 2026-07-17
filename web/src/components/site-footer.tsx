import Image from "next/image";
import Link from "next/link";

import { ArrowUpRight } from "@/components/arrow-up-right";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

const externalLinks = [
  { code: "01", label: "GitHub", href: GITHUB, newTab: true },
  { code: "02", label: "Product Hunt", href: "https://www.producthunt.com/products/verceltics", newTab: true },
  { code: "03", label: "TrustMRR", href: "https://trustmrr.com/startup/vercel-analytics-verceltics", newTab: true },
  { code: "04", label: "LinkedIn", href: "https://www.linkedin.com/company/verceltics", newTab: true },
  { code: "05", label: "X", href: "https://x.com/apoorvdarshan", newTab: true },
  { code: "06", label: "Instagram", href: "https://www.instagram.com/verceltics/", newTab: true },
  { code: "07", label: "Report issue", href: "https://github.com/apoorvdarshan/verceltics/issues", newTab: true },
  { code: "08", label: "Contact", href: "mailto:ad13dtu@gmail.com", newTab: false },
  { code: "09", label: "Ko-fi support", href: "https://ko-fi.com/apoorvdarshan", newTab: true },
  { code: "10", label: "PayPal support", href: "https://paypal.me/apoorvdarshan", newTab: true },
] as const;

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
          <Link href="/privacy">Privacy</Link>
          <Link href="/terms">Terms</Link>
        </nav>

        <nav aria-label="External, community, and support links" className="footer-patchbay">
          <header className="footer-patchbay-header">
            <span><i /> External patch panel</span>
            <strong>10 public routes</strong>
            <p>Project profiles, support, and direct contact. Every public route stays in view.</p>
          </header>
          <ul className="footer-patch-grid">
            {externalLinks.map((link) => (
              <li key={link.code}>
                <a
                  href={link.href}
                  rel={link.newTab ? "noreferrer" : undefined}
                  target={link.newTab ? "_blank" : undefined}
                >
                  <span aria-hidden="true">{link.code}</span>
                  <strong>{link.label}</strong>
                  <ArrowUpRight />
                </a>
              </li>
            ))}
          </ul>
        </nav>
      </div>
      <div className="footer-bottom">
        <span>© 2026 Verceltics</span>
        <span>Independent · Open source · Not affiliated with supported providers</span>
      </div>
    </footer>
  );
}
