"use client";

import Image from "next/image";
import { useRef, useState } from "react";

import { ArrowUpRight } from "@/components/arrow-up-right";

const APP_STORE = "https://apps.apple.com/us/app/verceltics/id6761645656";

const workspaces = [
  {
    id: "hosting",
    label: "Hosting",
    count: "10",
    screenshot: "/screens/ios/hosting.webp",
    alt: "Hosting connections in Verceltics on iPhone",
    fact: "Deploys, logs, DNS & releases",
  },
  {
    id: "registrars",
    label: "Registrars",
    count: "8",
    screenshot: "/screens/ios/registrars.webp",
    alt: "Domain registrar connections in Verceltics on iPhone",
    fact: "Domains, renewals & transfers",
  },
  {
    id: "sites",
    label: "Sites",
    count: "9",
    screenshot: "/screens/ios/services.webp",
    alt: "Site intelligence connections in Verceltics on iPhone",
    fact: "Search, traffic, speed & uptime",
  },
] as const;

type WorkspaceId = (typeof workspaces)[number]["id"];

export function HeroSwitcher() {
  const [activeId, setActiveId] = useState<WorkspaceId>("hosting");
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const activeIndex = workspaces.findIndex((workspace) => workspace.id === activeId);
  const active = workspaces[activeIndex];

  function selectWorkspace(index: number) {
    const normalized = (index + workspaces.length) % workspaces.length;
    setActiveId(workspaces[normalized].id);
    tabRefs.current[normalized]?.focus();
  }

  return (
    <section className={`switchboard-hero switchboard-hero--${active.id}`} id="overview">
      <div className="switchboard-grid">
        <div className="switchboard-copy">
          <p className="hero-overline">Native infrastructure / iPhone + iPad</p>
          <h1>Your stack.<br />Under one thumb.</h1>
          <p className="hero-deck">10 hosts. 8 registrars. 9 site services. Verceltics turns their official APIs into one native workspace—with no credential proxy in between.</p>
          <div className="hero-actions">
            <a className="button button--hero" href={APP_STORE} rel="noreferrer" target="_blank">Open in the App Store <ArrowUpRight /></a>
            <a className="button-link" href="#connections">Tour all 27 <span aria-hidden="true">↓</span></a>
          </div>
          <p className="hero-proof"><span>27 direct connections</span><span>iOS 18+</span><span>Open source</span></p>
        </div>

        <div aria-label="Choose a Verceltics workspace" className="switchboard-tabs" role="tablist">
          {workspaces.map((workspace, index) => (
            <button
              aria-controls="workspace-panel"
              aria-selected={active.id === workspace.id}
              className={`switchboard-tab switchboard-tab--${workspace.id}`}
              id={`workspace-tab-${workspace.id}`}
              key={workspace.id}
              onClick={() => setActiveId(workspace.id)}
              onKeyDown={(event) => {
                if (event.key === "ArrowRight" || event.key === "ArrowDown") {
                  event.preventDefault();
                  selectWorkspace(index + 1);
                }
                if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
                  event.preventDefault();
                  selectWorkspace(index - 1);
                }
                if (event.key === "Home") {
                  event.preventDefault();
                  selectWorkspace(0);
                }
                if (event.key === "End") {
                  event.preventDefault();
                  selectWorkspace(workspaces.length - 1);
                }
              }}
              ref={(node) => { tabRefs.current[index] = node; }}
              role="tab"
              tabIndex={active.id === workspace.id ? 0 : -1}
              type="button"
            >
              <span>{workspace.label}</span>
              <strong>{workspace.count}</strong>
              <small>{workspace.fact}</small>
            </button>
          ))}
        </div>

        <div aria-labelledby={`workspace-tab-${active.id}`} className="hero-product" id="workspace-panel" key={active.id} role="tabpanel">
          <div className="hero-product-meta">
            <span>{active.label} / {active.count}</span>
            <strong>{active.fact}</strong>
          </div>
          <figure className="hero-phone">
            <Image alt={active.alt} fill loading={active.id === "hosting" ? "eager" : "lazy"} priority={active.id === "hosting"} sizes="(max-width: 760px) calc(100vw - 48px), 390px" src={active.screenshot} />
          </figure>
        </div>
      </div>
    </section>
  );
}
