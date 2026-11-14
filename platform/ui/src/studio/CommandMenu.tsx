import { CommandMenu as Menu } from "../shell/CommandMenu";
import { MENU_GROUPS, MENU_SECTIONS } from "../shell/menu";

/**
 * Studio's One CommandCenter menu: the console's menu (shell/menu.ts), the same for administrators and developers as
 * in AA (only what a person may open differs). BUILD > Automation is Studio's own page (Public and Private tabs, as
 * AA's one Automation page); the other pages open in the One CommandCenter console Studio is connected to
 * (next/#/<page>). Since 1 Nov 2026 it has the console's look too: find a page, one section open at a time, Collapse
 * and the width at the foot. Since 13 Nov 2026 (improvement list item 3) it groups pages as the console does
 * (MENU_GROUPS: BUILD lists Automation, Ideas and Apps), so both menus show the same entries.
 */
export type StudioView = "automation";

const LOCAL = new Set(["automation"]);
const NONE = new Set<string>();

/**
 * `currentPage` marks a One CommandCenter page as the open one, for pages served outside Studio (Document Automation).
 * Without `onView` (outside Studio) no page is Studio's own: Agents too is a link, to the console's Agents page
 * (14 Nov 2026: from Document Automation, Build > Agents did nothing, as it asked Studio to switch view).
 */
export function CommandMenu({ view, onView, controlRoom, editing = false, currentPage }:
  { view: StudioView | null; onView?: (v: StudioView) => void; controlRoom: string | null; editing?: boolean; currentPage?: string }) {
  return (
    <Menu sections={MENU_SECTIONS} current={view ?? (currentPage === "documents" ? "docautomation" : currentPage ?? null)} local={onView ? LOCAL : NONE}
      onLocal={(id) => onView?.(id as StudioView)} editing={editing}
      // Document Automation has its own pages beside the console (/documents/); the others are console pages
      hrefFor={(page) => !controlRoom ? undefined : page === "docautomation" ? controlRoom.replace(/\/?$/, "/") + "documents/" : controlRoom + "next/#/" + page}
      home={{ href: controlRoom ? controlRoom + "next/" : undefined }}
      offTitle="Sign in to One CommandCenter (Agents, Public tab) to open this page" console groups={MENU_GROUPS} />
  );
}
