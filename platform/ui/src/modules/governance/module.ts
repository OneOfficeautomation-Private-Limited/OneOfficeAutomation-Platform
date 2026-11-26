import type { UiModuleInfo } from "../registry";

/** governance: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "governance",
  menu: [
    { section: "manage", after: "credentials", page: {"id": "policies", "label": "Policies", "perm": "settings.manage"} },
    { section: "monitor", after: "alert-rules", page: {"id": "governance", "label": "AI governance", "perm": "audit.view"} }
  ],
};
