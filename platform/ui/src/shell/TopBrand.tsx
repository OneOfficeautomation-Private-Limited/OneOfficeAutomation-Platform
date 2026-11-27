import { useSyncExternalStore } from "react";
import { Icon } from "../components";
import logo from "../assets/logo-bar.png";

/**
 * The left of the top bar (fix list 27 Nov round 2, item 1, option A2 chosen on the canvas): a round ‹ that folds and
 * opens the menu (the same button as at the menu's foot), the company's logo with its lettering in white for the dark
 * bar (no white plate), a thin line and the product's name: One CommandCenter, or OneAgentStudio in the agent editor.
 * The bar is dark in both themes, so this is the same in Light and Dark. Used by the console, Studio and Document
 * Automation, so the bar is one.
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

export function TopBrand({ product = "One CommandCenter" }: { product?: string }) {
  const mini = useSyncExternalStore(menuFold.subscribe, menuFold.get);
  return (
    <div className="st-brand st-brand-a2">
      <button type="button" className="st-menu-round st-top-fold" aria-expanded={!mini} aria-label={mini ? "Expand menu" : "Collapse menu"}
        title={mini ? "Expand menu" : "Collapse menu"} onClick={() => window.dispatchEvent(new Event(MENU_TOGGLE))}>
        <Icon name={mini ? "chevronRight" : "chevronLeft"} size={14} stroke={2.6} />
      </button>
      <img className="st-brand-logo" src={logo} alt="ONEOFFICEAUTOMATION" />
      <span className="st-brand-line" aria-hidden="true" />
      <span className="st-brand-product">{product}</span>
    </div>
  );
}
