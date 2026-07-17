"use client";

import Image from "next/image";
import { useRef, useState } from "react";

import { ArrowUpRight } from "@/components/arrow-up-right";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";

const modes = [
  {
    id: "hosting",
    shortLabel: "Host",
    label: "Hosting",
    count: "10",
    accent: "blue",
    screenshot: "/screens/ios/hosting.webp",
    alt: "Hosting platforms in Verceltics on iPhone",
    summary: "Deploys, releases, logs and DNS",
  },
  {
    id: "registrars",
    shortLabel: "Names",
    label: "Registrars",
    count: "08",
    accent: "orange",
    screenshot: "/screens/ios/registrars.webp",
    alt: "Domain registrars in Verceltics on iPhone",
    summary: "Domains, renewals and transfers",
  },
  {
    id: "sites",
    shortLabel: "Signals",
    label: "Site services",
    count: "09",
    accent: "violet",
    screenshot: "/screens/ios/services.webp",
    alt: "Site intelligence services in Verceltics on iPhone",
    summary: "Search, traffic, speed and uptime",
  },
] as const;

type ModeId = (typeof modes)[number]["id"];

export function InstrumentHero() {
  const [activeId, setActiveId] = useState<ModeId>("hosting");
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const activeIndex = modes.findIndex((mode) => mode.id === activeId);
  const active = modes[activeIndex];

  function moveFocus(index: number) {
    const next = (index + modes.length) % modes.length;
    setActiveId(modes[next].id);
    tabRefs.current[next]?.focus();
  }

  return (
    <section className={`instrument-hero instrument-hero--${active.accent}`} id="overview">
      <div className="hero-chassis">
        <span aria-hidden="true" className="chassis-screw chassis-screw--one" />
        <span aria-hidden="true" className="chassis-screw chassis-screw--two" />
        <span aria-hidden="true" className="chassis-screw chassis-screw--three" />
        <span aria-hidden="true" className="chassis-screw chassis-screw--four" />

        <div className="instrument-copy">
          <div className="serial-plate">
            <span>VRC-27</span>
            <span>Mobile operations instrument</span>
          </div>
          <p className="hero-eyebrow"><i /> Native on iPhone and iPad</p>
          <h1><span translate="no">Verceltics:</span>{" "}Check the whole stack. Close the laptop.</h1>
          <p className="hero-deck">
            Verceltics is a native iPhone and iPad app for monitoring and managing hosting, domains, deployments, DNS, analytics, search performance, speed, and uptime from the services you connect.
          </p>
          <aside aria-label="How Verceltics uses Google account data" className="google-data-plate">
            <header>
              <span><i aria-hidden="true" /> How Verceltics uses Google data</span>
              <b>Connected features only</b>
            </header>
            <p>When you connect Google, Verceltics uses read-only Google Search Console and Google Analytics access to display your verified sites, search performance, and GA4 reports. Firebase Hosting access displays hosting resources and performs only actions you initiate. Google user data is used only to provide these app features. OAuth tokens stay in your device&apos;s iOS Keychain, and requests go directly to Google&apos;s official APIs—not through a Verceltics server.</p>
            <a href="/privacy#google-data">How Google data is handled <span aria-hidden="true">→</span></a>
          </aside>
          <div className="hero-actions">
            <a className="primary-control" href={APP_STORE} rel="noreferrer" target="_blank">
              Get Verceltics <ArrowUpRight />
            </a>
            <a className="text-control" href="#patchbay">Inspect all 27 connections <span aria-hidden="true">↓</span></a>
          </div>
          <dl className="hero-specs">
            <div><dt>Connections</dt><dd>27</dd></div>
            <div><dt>Credential proxy</dt><dd>None</dd></div>
            <div><dt>Platform</dt><dd>iOS 18+</dd></div>
          </dl>
        </div>

        <div className="control-deck">
          <header className="deck-header">
            <div><span>Verceltics</span><strong>Control surface</strong></div>
            <p><i /> Ready</p>
          </header>

          <div className="deck-stage">
            <figure
              aria-labelledby={`mode-tab-${active.id}`}
              className="phone-bay"
              id="workspace-panel"
              key={active.id}
              role="tabpanel"
            >
              <div className="phone-screen">
                <Image
                  alt={active.alt}
                  fill
                  loading={active.id === "hosting" ? "eager" : "lazy"}
                  priority={active.id === "hosting"}
                  sizes="(max-width: 760px) 76vw, 390px"
                  src={active.screenshot}
                />
              </div>
              <figcaption><span>Live app surface</span><strong>{active.label}</strong></figcaption>
            </figure>

            <div className="mode-console">
              <div aria-live="polite" className="mode-readout">
                <span>Selected bank</span>
                <strong>{active.label}</strong>
                <p>{active.summary}</p>
              </div>
              <div aria-label="Choose workspace" aria-orientation="vertical" className="mode-switch" role="tablist">
                {modes.map((mode, index) => (
                  <button
                    aria-label={`${mode.label}, ${mode.count} connections`}
                    aria-controls="workspace-panel"
                    aria-selected={active.id === mode.id}
                    className={`mode-key mode-key--${mode.accent}`}
                    id={`mode-tab-${mode.id}`}
                    key={mode.id}
                    onClick={() => setActiveId(mode.id)}
                    onKeyDown={(event) => {
                      if (event.key === "ArrowDown" || event.key === "ArrowRight") {
                        event.preventDefault();
                        moveFocus(index + 1);
                      }
                      if (event.key === "ArrowUp" || event.key === "ArrowLeft") {
                        event.preventDefault();
                        moveFocus(index - 1);
                      }
                      if (event.key === "Home") {
                        event.preventDefault();
                        moveFocus(0);
                      }
                      if (event.key === "End") {
                        event.preventDefault();
                        moveFocus(modes.length - 1);
                      }
                    }}
                    ref={(node) => { tabRefs.current[index] = node; }}
                    role="tab"
                    tabIndex={active.id === mode.id ? 0 : -1}
                    type="button"
                  >
                    <span>{mode.shortLabel}</span>
                    <strong>{mode.count}</strong>
                    <i aria-hidden="true" />
                  </button>
                ))}
              </div>
              <div className="safety-note"><b>Direct</b><span>Official provider APIs only</span></div>
            </div>
          </div>

          <div className="deck-status" aria-label="Connection architecture status" role="group">
            <span><i /> Keychain locked</span>
            <span><i /> Direct HTTPS</span>
            <span><i /> 27 ports ready</span>
          </div>
        </div>
      </div>
    </section>
  );
}
