import type { UiModulePages } from "../pages";
import "./governance.css";
import { Governance } from "./Governance";
import { Policies } from "./Policies";

/** governance's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "policies": (p) => <Policies api={p.api} user={p.user} onNotice={p.onNotice} />,
    "governance": (p) => <Governance api={p.api} onNotice={p.onNotice} />,
  },
};
