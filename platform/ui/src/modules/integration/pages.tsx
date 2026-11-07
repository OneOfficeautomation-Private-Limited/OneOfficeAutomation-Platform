import type { UiModulePages } from "../pages";
import "./integration.css";
import { AgentApis } from "./AgentApis";

/** integration's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "apitasks": (p) => <AgentApis api={p.api} user={p.user} onNotice={p.onNotice} />,
  },
};
