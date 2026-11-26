import type { UiModulePages } from "../pages";
import "./orchestration.css";
import { Workflows } from "./Workflows";
import { WorkflowEditor } from "./WorkflowEditor";
import { WorkflowDiagram } from "./WorkflowDiagram";

/** orchestration's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "workflows": (p) => <Workflows api={p.api} user={p.user} onNotice={p.onNotice} />,
  },
  // OneAgentStudio's workflow editor and the diagram (review round, 26 Nov 2026): Studio shows them when this module is in
  parts: {
    "workflow-editor": WorkflowEditor,
    "workflow-diagram": WorkflowDiagram,
  },
};
