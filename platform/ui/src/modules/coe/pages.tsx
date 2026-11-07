import type { UiModulePages } from "../pages";
import "./coe.css";
import { Ideas } from "./Ideas";

/** coe's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "ideas": (p) => <Ideas api={p.api} onNotice={p.onNotice} />,
  },
};
