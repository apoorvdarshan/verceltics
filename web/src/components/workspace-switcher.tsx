"use client";

import Image from "next/image";
import { useEffect, useState, type CSSProperties } from "react";

type WorkspaceId = "hosting" | "registrars" | "sites";

type Workspace = {
  id: WorkspaceId;
  label: string;
  count: string;
  accent: string;
  phone: string;
  tablet: string;
  phoneAlt: string;
  tabletAlt: string;
};

const workspaces: readonly Workspace[] = [
  {
    id: "hosting",
    label: "Hosting",
    count: "10 providers",
    accent: "#2c91ff",
    phone: "/screens/ios/cloudflare.png",
    tablet: "/screens/ipad/cloudflare.png",
    phoneAlt: "Cloudflare traffic dashboard in Verceltics on iPhone",
    tabletAlt: "Cloudflare traffic dashboard in Verceltics on iPad",
  },
  {
    id: "registrars",
    label: "Registrars",
    count: "8 providers",
    accent: "#f1f3f7",
    phone: "/screens/ios/registrars.png",
    tablet: "/screens/ipad/registrars.png",
    phoneAlt: "Registrar connections in Verceltics on iPhone",
    tabletAlt: "Registrar connections in Verceltics on iPad",
  },
  {
    id: "sites",
    label: "Sites",
    count: "9 services",
    accent: "#a154ff",
    phone: "/screens/ios/search.png",
    tablet: "/screens/ipad/sites.png",
    phoneAlt: "Google Search Console dashboard in Verceltics on iPhone",
    tabletAlt: "Site service connections in Verceltics on iPad",
  },
] as const;

export function WorkspaceSwitcher() {
  const [selected, setSelected] = useState<WorkspaceId>("hosting");
  const workspace = workspaces.find((item) => item.id === selected) ?? workspaces[0];

  useEffect(() => {
    const workspaceId = window.location.hash.replace("#workspace-", "") as WorkspaceId;

    if (workspaces.some((item) => item.id === workspaceId)) {
      setSelected(workspaceId);
    }
  }, []);

  function chooseWorkspace(id: WorkspaceId) {
    setSelected(id);
    window.history.replaceState(null, "", `#workspace-${id}`);
  }

  return (
    <div className="hero-product" style={{ "--route-accent": workspace.accent } as CSSProperties}>
      <div aria-label="Preview a workspace" className="route-picker" role="group">
        {workspaces.map((item) => (
          <button
            aria-pressed={selected === item.id}
            className={selected === item.id ? `route-choice route-choice--${item.id} is-active` : `route-choice route-choice--${item.id}`}
            key={item.id}
            onClick={() => chooseWorkspace(item.id)}
            type="button"
          >
            <span aria-hidden="true" className="route-choice-line"><i /></span>
            <span><strong>{item.label}</strong><small>{item.count}</small></span>
          </button>
        ))}
      </div>

      <div className="device-composition" id={`workspace-${workspace.id}`} key={workspace.id}>
        <div className="tablet-device" aria-hidden="true">
          <div className="tablet-camera" />
          <Image alt="" fill priority sizes="(max-width: 900px) 92vw, 650px" src={workspace.tablet} />
        </div>
        <div className="phone-device">
          <div className="phone-speaker" aria-hidden="true" />
          <Image alt={workspace.phoneAlt} fill priority sizes="(max-width: 680px) 62vw, 235px" src={workspace.phone} />
        </div>
        <span className="device-caption"><i /> {workspace.label} workspace</span>
        <span className="sr-only">{workspace.tabletAlt}</span>
      </div>
    </div>
  );
}
