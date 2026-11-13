import { useSyncExternalStore } from "react";
import { Icon } from "../components";
import logo from "../assets/logo-top.png";

/**
 * The left of the top bar (round 3 of 13 Nov 2026, option T1 chosen on the canvas): ☰ folds and opens the menu, then
 * the company's logo (the sign-in page's) on a small white plate; the product name no longer sits on the bar (only
 * the agent editor names itself, OneAgentStudio). The company name sits centred in the bar's free space (CompanyName),
 * hidden where the bar is too narrow. Used by the console, Studio and Document Automation, so the bar is one.
 */
let folded = false;
const subscribers = new Set<() => void>();
/** Whether the menu is folded to icons: the menu (CommandMenu) sets it; the ☰ button shows it. */
export const menuFold = {
  get: () => folded,
  set(v: boolean) { if (v !== folded) { folded = v; subscribers.forEach((f) => f()); } },
  subscribe(f: () => void) { subscribers.add(f); return () => { subscribers.delete(f); }; },
};
/** The event the ☰ button sends; the menu on the page folds or opens on it. */
export const MENU_TOGGLE = "ooa-menu-toggle";

export function TopBrand({ product }: { product?: string }) {
  const mini = useSyncExternalStore(menuFold.subscribe, menuFold.get);
  return (
    <div className="st-brand st-brand-t1">
      <button type="button" className="st-top-icon st-top-fold" aria-expanded={!mini} aria-label={mini ? "Expand menu" : "Collapse menu"}
        title={mini ? "Expand menu" : "Collapse menu"} onClick={() => window.dispatchEvent(new Event(MENU_TOGGLE))}>
        <Icon name="menu" size={18} />
      </button>
      <span className="st-brand-plate"><img src={logo} alt="ONEOFFICEAUTOMATION" /></span>
      {product && <span className="st-brand-product">{product}</span>}
    </div>
  );
}

/** The company's name in the top bar's free space (T1): quiet, centred, and gone on narrow screens. */
export function CompanyName() {
  return <span className="st-company" aria-hidden="true">OneOfficeAutomation</span>;
}
