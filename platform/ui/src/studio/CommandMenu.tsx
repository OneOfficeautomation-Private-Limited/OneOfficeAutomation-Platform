import { CommandMenu as Menu } from "../shell/CommandMenu";
import { MENU_SECTIONS } from "../shell/menu";

/**
 * Studio's One CommandCenter menu: the console's menu (shell/menu.ts), with BUILD holding only Studio's own Automation
 * page (workflows, forms and the rest are made there with Create and called from agents' steps). The other pages
 * open in the One CommandCenter console Studio is connected to (next/#/<page>; ?page=<id> would be the classic
 * console). Since 1 Nov 2026 it has the console's look too: find a page, favourites, one section open at a time,
 * Collapse and the width at the foot.
 */
export type StudioView = "automation";

const SECTIONS = MENU_SECTIONS.map((s) => s.id === "build" ? { ...s, pages: [{ id: "automation", label: "Automation" }] } : s);
const LOCAL = new Set(["automation"]);

export function CommandMenu({ view, onView, controlRoom, editing = false }:
  { view: StudioView | null; onView: (v: StudioView) => void; controlRoom: string | null; editing?: boolean }) {
  return (
    <Menu sections={SECTIONS} current={view} local={LOCAL} onLocal={(id) => onView(id as StudioView)} editing={editing}
      hrefFor={(page) => controlRoom ? controlRoom + "next/#/" + page : undefined} home={{ href: controlRoom ? controlRoom + "next/" : undefined }}
      offTitle="Connect One AgentStudio to One CommandCenter to open this page" console />
  );
}
