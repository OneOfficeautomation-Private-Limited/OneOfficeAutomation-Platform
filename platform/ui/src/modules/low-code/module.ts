import type { UiModuleInfo } from "../registry";

/** low-code: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "low-code",
  menu: [
    { section: "build", after: "ideas", page: {"id": "apps", "label": "Apps"} },
    { section: "manage", after: "globals", page: {"id": "data", "label": "Data store", "perm": "data.view"} }
  ],
};
