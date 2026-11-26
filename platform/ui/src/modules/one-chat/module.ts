import type { UiModuleInfo } from "../registry";

/** one-chat: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "one-chat",
  menu: [
    { section: "run", after: "locks", page: {"id": "chat", "label": "One Chat", "perm": "runs.view"} }
  ],
};
