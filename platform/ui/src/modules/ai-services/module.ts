import type { UiModuleInfo } from "../registry";

/** ai-services: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "ai-services",
  menu: [
    { section: "ai", after: "connections", page: {"id": "skills", "label": "Prompt Skills", "perm": "skills.view"} },
    { section: "ai", after: "docautomation", page: {"id": "knowledge", "label": "Knowledge bases", "perm": "repo.view"} }
  ],
};
