import type { UiModulePages } from "../pages";
import { DocumentAutomation } from "./ConsolePage";

/** document-automation's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "docautomation": (p) => <DocumentAutomation api={p.api} />,
  },
};
