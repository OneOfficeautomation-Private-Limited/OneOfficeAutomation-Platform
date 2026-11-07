import type { UiModulePages } from "../pages";
import { KnowledgeBases } from "./KnowledgeBases";
import { Skills } from "./Skills";

/** ai-services's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "skills": (p) => <Skills api={p.api} user={p.user} onNotice={p.onNotice} />,
    "knowledge": (p) => <KnowledgeBases api={p.api} user={p.user} onNotice={p.onNotice} />,
  },
};
