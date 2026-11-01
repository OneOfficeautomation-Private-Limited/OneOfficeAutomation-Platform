import { CommandMenu as Menu } from "../shell/CommandMenu";
import { MENU_SECTIONS } from "../shell/menu";

/**
 * Studio's One CommandCenter menu: the console's menu (shell/menu.ts), the same for administrators and developers as
 * in AA (only what a person may open differs). BUILD > Automation is Studio's own page (Public and Private tabs, as
 * AA's one Automation page); the other pages open in the One CommandCenter console Studio is connected to
 * (next/#/<page>). Since 1 Nov 2026 it has the console's look too: find a page, one section open at a time, Collapse
 * and the width at the foot.
 */
export type StudioView = "automation";

// the console's BUILD pages, without One AgentStudio (this is it)
const SECTIONS = MENU_SECTIONS.map((s) => s.id === "build" ? { ...s, pages: s.pages.filter((p) => p.id !== "studio") } : s);
const LOCAL = new Set(["automation"]);

export function CommandMenu({ view, onView, controlRoom, editing = false }:
  { view: StudioView | null; onView: (v: StudioView) => void; controlRoom: string | null; editing?: boolean }) {
  return (
    <Menu sections={SECTIONS} current={view} local={LOCAL} onLocal={(id) => onView(id as StudioView)} editing={editing}
      hrefFor={(page) => controlRoom ? controlRoom + "next/#/" + page : undefined} home={{ href: controlRoom ? controlRoom + "next/" : undefined }}
      offTitle="Connect One AgentStudio to One CommandCenter to open this page" console />
  );
}
