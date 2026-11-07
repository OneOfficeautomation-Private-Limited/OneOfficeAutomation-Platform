import type { UiModuleInfo } from "../registry";

/** integration: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "integration",
  menu: [
    { section: "build", after: "templates", page: {"id": "apitasks", "label": "Agent APIs", "perm": "apitasks.manage"} }
  ],
};
