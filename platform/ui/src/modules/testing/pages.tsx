import type { UiModulePages } from "../pages";
import "./testing.css";
import { Tests } from "./Tests";

/** testing's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "tests": (p) => <Tests api={p.api} user={p.user} onNotice={p.onNotice} />,
  },
};
