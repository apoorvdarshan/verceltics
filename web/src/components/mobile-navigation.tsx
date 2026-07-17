"use client";

import Link from "next/link";
import { useRef, type KeyboardEvent } from "react";

import { ArrowUpRight } from "@/components/arrow-up-right";

interface MobileNavigationProps {
  githubUrl: string;
}

export function MobileNavigation({ githubUrl }: MobileNavigationProps) {
  const detailsRef = useRef<HTMLDetailsElement>(null);

  function closeMenu() {
    detailsRef.current?.removeAttribute("open");
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDetailsElement>) {
    if (event.key !== "Escape") return;

    closeMenu();
    detailsRef.current?.querySelector<HTMLElement>("summary")?.focus();
  }

  return (
    <details className="mobile-menu" onKeyDown={handleKeyDown} ref={detailsRef}>
      <summary>Menu</summary>
      <nav aria-label="Mobile navigation">
        <Link href="/#patchbay" onClick={closeMenu}>Connections</Link>
        <Link href="/#workflows" onClick={closeMenu}>Workflows</Link>
        <Link href="/privacy" onClick={closeMenu}>Privacy</Link>
        <Link href="/#pricing" onClick={closeMenu}>Pricing</Link>
        <a href={githubUrl} onClick={closeMenu} rel="noreferrer" target="_blank">Source <ArrowUpRight /></a>
      </nav>
    </details>
  );
}
