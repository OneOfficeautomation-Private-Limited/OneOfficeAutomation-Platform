import { CommandMenu as Menu } from "../shell/CommandMenu";
import { MENU_SECTIONS } from "../shell/menu";

/**
 * Studio's Command Center menu: the console's menu (shell/menu.ts), with BUILD holding only Studio's own Automation
 * page (workflows, forms and the rest are made there with Create and called from agents' steps). The other pages
 * open in the Command Center Studio is connected to.
 */
export type StudioView = "automation";

const SECTIONS = MENU_SECTIONS.map((s) => s.id === "build" ? { ...s, pages: [{ id: "automation", label: "Automation" }] } : s);
const LOCAL = new Set(["automation"]);

export function CommandMenu({ view, onView, controlRoom, editing = false }:
  { view: StudioView | null; onView: (v: StudioView) => void; controlRoom: string | null; editing?: boolean }) {
  return (
    <Menu sections={SECTIONS} current={view} local={LOCAL} onLocal={(id) => onView(id as StudioView)} editing={editing}
      hrefFor={(page) => controlRoom ? controlRoom + "?page=" + page : undefined} home={{ href: controlRoom ?? undefined }}
      offTitle="Connect Studio to a Command Center to open this page" />
  );
}
