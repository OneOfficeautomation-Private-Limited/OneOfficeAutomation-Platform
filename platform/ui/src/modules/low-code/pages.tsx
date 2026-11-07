import type { UiModulePages } from "../pages";
import "./low-code.css";
import { Apps } from "./Apps";
import { DataStore } from "./DataStore";
import { EmbedApp } from "./EmbedApp";

/** low-code's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "apps": (p) => <Apps api={p.api} user={p.user} onNotice={p.onNotice} />,
    "data": (p) => <DataStore api={p.api} user={p.user} onNotice={p.onNotice} />,
  },
  parts: {
    "embed-app": EmbedApp,
  },
};
