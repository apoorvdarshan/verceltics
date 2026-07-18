import Image from "next/image";
import Link from "next/link";
import type { CSSProperties } from "react";

import { integrationGroups } from "@/data/integrations";

export function ProviderPatchbay() {
  return (
    <div className="patchbay">
      {integrationGroups.map((group, groupIndex) => (
        <section
          className={`patch-rail patch-rail--${group.id}`}
          key={group.id}
          style={{ "--rail-accent": group.accent } as CSSProperties}
        >
          <header className="rail-label">
            <span>Bank 0{groupIndex + 1}</span>
            <h3>{group.label}</h3>
            <p>{group.detail}</p>
            <strong>{String(group.count).padStart(2, "0")} ports</strong>
          </header>
          <ul aria-label={`${group.label} providers. Scroll horizontally to inspect every connection.`} className="provider-ports" role="region" tabIndex={0}>
            {group.providers.map((provider, index) => (
              <li key={provider.name}>
                <Link aria-label={`View ${provider.name} integration details`} href={`/integrations#${provider.slug}`} translate="no">
                  <span className="port-number">{String(index + 1).padStart(2, "0")}</span>
                  <span className="port-socket"><span><Image alt="" fill sizes="28px" src={`/providers/${provider.icon}`} /></span></span>
                  <strong>{provider.name}</strong>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
