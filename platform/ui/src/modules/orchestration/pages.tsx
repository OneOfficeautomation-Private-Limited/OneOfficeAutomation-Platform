import type { UiModulePages } from "../pages";
import "./orchestration.css";
import { Workflows } from "./Workflows";

/** orchestration's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "workflows": (p) => <Workflows api={p.api} user={p.user} onNotice={p.onNotice} />,
  },
};
