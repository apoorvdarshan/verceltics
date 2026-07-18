import Image from "next/image";
import Link from "next/link";

import { ArrowUpRight } from "@/components/arrow-up-right";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";
const GITHUB = "https://github.com/apoorvdarshan/verceltics";

const channelGroups = [
  {
    id: "proof",
    label: "Build & proof",
    detail: "Source and launch records",
    links: [
      { label: "GitHub", detail: "Source code", href: GITHUB, mark: "/brands/github.svg", newTab: true },
      { label: "Product Hunt", detail: "Launch page", href: "https://www.producthunt.com/products/verceltics", mark: "/brands/product-hunt.svg", newTab: true },
      { label: "TrustMRR", detail: "Verified profile", href: "https://trustmrr.com/startup/verceltics-hosting-domains", mark: "/brands/trustmrr.svg", newTab: true },
    ],
  },
  {
    id: "network",
    label: "Follow",
    detail: "Updates from the project",
    links: [
      { label: "LinkedIn", detail: "Company page", href: "https://www.linkedin.com/company/verceltics", mark: "/brands/linkedin.svg", newTab: true },
      { label: "X", detail: "Founder notes", href: "https://x.com/apoorvdarshan", mark: "/brands/x.svg", newTab: true },
    ],
  },
  {
    id: "direct",
    label: "Direct line",
    detail: "Support and contact",
    links: [
      { label: "Ko-fi", detail: "Support development", href: "https://ko-fi.com/apoorvdarshan", mark: "/brands/kofi.svg", newTab: true },
      { label: "PayPal", detail: "Send support", href: "https://paypal.me/apoorvdarshan", mark: "/brands/paypal.svg", newTab: true },
      { label: "Report issue", detail: "Open a ticket", href: "https://github.com/apoorvdarshan/verceltics/issues", mark: "/brands/issue.svg", newTab: true },
      { label: "Contact", detail: "Email Apoorv", href: "mailto:ad13dtu@gmail.com", mark: "/brands/email.svg", newTab: false },
    ],
  },
] as const;

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="footer-plate">
        <section className="footer-brand-rack">
          <div className="footer-brand">
            <span><Image alt="" height={52} src="/icon.png" width={52} /></span>
            <div><strong>Verceltics</strong><p>Check production without opening the laptop.</p></div>
          </div>
          <nav aria-label="Product links" className="footer-local-links">
            <Link href="/#patchbay">27 connections</Link>
            <Link href="/#workflows">Workflows</Link>
            <a href={APP_STORE} rel="noreferrer" target="_blank">App Store <ArrowUpRight /></a>
          </nav>
        </section>

        <section className="footer-readout">
          <span><i /> Architecture status</span>
          <strong>No credential proxy.</strong>
          <p>Your device talks directly to provider APIs or your selected self-hosted endpoint.</p>
          <span className="footer-encryption"><i /> Device-only tokens</span>
        </section>

        {channelGroups.map((group) => (
          <nav
            aria-label={`${group.label} links`}
            className={`footer-signal-group footer-signal-group--${group.id}`}
            key={group.id}
          >
            <header>
              <span><i /> {group.label}</span>
              <p>{group.detail}</p>
            </header>
            <ul>
              {group.links.map((link) => (
                <li key={link.label}>
                  <a
                    href={link.href}
                    rel={link.newTab ? "noreferrer" : undefined}
                    target={link.newTab ? "_blank" : undefined}
                  >
                    <span className="footer-signal-mark">
                      <Image alt="" height={20} src={link.mark} width={20} />
                    </span>
                    <span className="footer-signal-copy">
                      <strong>{link.label}</strong>
                      <small>{link.detail}</small>
                    </span>
                    <ArrowUpRight />
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>
      <div className="footer-bottom">
        <span>© 2026 Verceltics</span>
        <nav aria-label="Legal links">
          <Link href="/privacy">Privacy</Link>
          <i aria-hidden="true">·</i>
          <Link href="/terms">Terms</Link>
        </nav>
        <span>Independent · Open source · Not affiliated with supported providers</span>
      </div>
    </footer>
  );
}
