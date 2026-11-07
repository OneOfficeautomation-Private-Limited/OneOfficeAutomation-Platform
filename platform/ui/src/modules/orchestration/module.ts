import type { UiModuleInfo } from "../registry";

/** orchestration: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "orchestration",
  menu: [
    { section: "manage", after: "queues", page: {"id": "workflows", "label": "Workflow", "perm": "runs.view"} }
  ],
};
