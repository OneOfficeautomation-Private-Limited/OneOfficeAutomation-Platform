import type { UiModuleInfo } from "../registry";

/** coe: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "coe",
  menu: [
    { section: "build", after: "tests", page: {"id": "ideas", "label": "Automation ideas"} }
  ],
};
