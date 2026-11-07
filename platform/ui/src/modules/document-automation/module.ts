import type { UiModuleInfo } from "../registry";

/** document-automation: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "document-automation",
  menu: [
    { section: "ai", after: "skills", page: {"id": "docautomation", "label": "Document Automation", "perm": "repo.view"} }
  ],
};
