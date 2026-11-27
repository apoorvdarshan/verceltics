import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

export default function DocumentationLayout({ children }: { children: React.ReactNode }) {
  return <div className="site-shell"><SiteHeader />{children}<SiteFooter /></div>;
}
