import type { UiModulePages } from "../pages";
import "./one-chat.css";
import { Chat } from "./Chat";
import { AssistantPrefs } from "./AssistantPrefs";

/** one-chat's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "chat": (p) => <Chat api={p.api} onOpenRuns={() => p.setPage("activity")} />,
  },
  parts: {
    // My account > AI assistants (S2): the person's own model, language and instructions per assistant
    "assistant-prefs": AssistantPrefs,
  },
};
