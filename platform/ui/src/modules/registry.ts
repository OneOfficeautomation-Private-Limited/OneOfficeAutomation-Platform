import type { MenuPage, MenuSection } from "../shell/menu";

/**
 * The console's feature modules (docs/architecture/13-modules.md, phase 6), as the server finds its modules with
 * ServiceLoader: every modules/<id>/module.ts is found when the console is built, and says what the module adds to the
 * menu. Its pages are in modules/<id>/pages.tsx (modules/pages.tsx finds those), so Studio, which shares the menu,
 * does not load them. A module that is not there simply adds nothing.
 */
export type ModuleMenuItem = {
  /** The menu section (build, manage, insights, administration, ai). */
  section: string;
  /** The page it follows (a page of the kernel or of another module), or null for the top of the section. */
  after: string | null;
  page: MenuPage;
};
export type UiModuleInfo = {
  id: string;
  menu: ModuleMenuItem[];
  /** Old page addresses that now open a tab of one of its pages: old id -> [page id, tab]. */
  moved?: Record<string, [string, string]>;
};

const found = import.meta.glob<{ info: UiModuleInfo }>("./*/module.ts", { eager: true });

/** The modules found, by id. */
export const UI_MODULES: UiModuleInfo[] = Object.values(found).map((m) => m.info).sort((a, b) => a.id.localeCompare(b.id));

/** The kernel's sections with every module's pages put in place; an item whose page to follow is missing goes last. */
export function withModules(sections: MenuSection[], modules: UiModuleInfo[]): MenuSection[] {
  const out = sections.map((s) => ({ ...s, pages: [...s.pages] }));
  let waiting = modules.flatMap((m) => m.menu);
  for (let progress = true; waiting.length && progress;) {
    progress = false;
    waiting = waiting.filter((item) => {
      const s = out.find((x) => x.id === item.section);
      if (!s) return true;
      const at = item.after === null ? 0 : s.pages.findIndex((p) => p.id === item.after) + 1;
      if (at === 0 && item.after !== null) return true;
      s.pages.splice(at, 0, item.page);
      progress = true;
      return false;
    });
  }
  for (const item of waiting) out.find((x) => x.id === item.section)?.pages.push(item.page);
  return out.filter((s) => s.pages.length > 0);
}

/** Every old address the modules have moved to a page's tab. */
export const MOVED_PAGES: Record<string, [string, string]> = Object.assign({}, ...UI_MODULES.map((m) => m.moved ?? {}));
