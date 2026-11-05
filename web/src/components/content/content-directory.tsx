"use client";

import Link from "next/link";
import { useId, useState } from "react";

export type DirectoryEntry = { title: string; description: string; path: string; category: string };

export function ContentDirectory({ entries }: { entries: DirectoryEntry[] }) {
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("All");
  const inputId = useId();
  const categories = ["All", ...new Set(entries.map(entry => entry.category))];
  const needle = query.trim().toLowerCase();
  const visible = entries.filter(entry => (category === "All" || entry.category === category) && `${entry.title} ${entry.description}`.toLowerCase().includes(needle));
  return <>
    <div className="library-controls"><label htmlFor={inputId}>Find a provider or task</label><input id={inputId} type="search" placeholder="Search guides, providers, DNS…" value={query} onChange={event => setQuery(event.target.value)} /><div className="library-filters" aria-label="Filter by topic">{categories.map(value => <button key={value} type="button" aria-pressed={category === value} onClick={() => setCategory(value)}>{value}</button>)}</div><p role="status">{visible.length} {visible.length === 1 ? "article" : "articles"}</p></div>
    <div className="library-grid">{visible.map(entry => <article className="library-card" key={entry.path}><p className="instrument-label">{entry.category}</p><h2><Link href={entry.path}>{entry.title}</Link></h2><p>{entry.description}</p><Link className="text-control" href={entry.path}>Read the guide <span aria-hidden="true">→</span></Link></article>)}</div>
    {visible.length === 0 ? <p className="library-empty">No matching articles. Try another provider or clear the filters.</p> : null}
  </>;
}
