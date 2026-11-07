import type { UiModulePages } from "../pages";
import "./one-chat.css";
import { Chat } from "./Chat";

/** one-chat's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "chat": (p) => <Chat api={p.api} onOpenRuns={() => p.setPage("activity")} />,
  },
};
