import type { UiModuleInfo } from "../registry";

/** testing: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "testing",
  menu: [
    { section: "build", after: "apitasks", page: {"id": "tests", "label": "Agent tests", "perm": "repo.view"} }
  ],
};
