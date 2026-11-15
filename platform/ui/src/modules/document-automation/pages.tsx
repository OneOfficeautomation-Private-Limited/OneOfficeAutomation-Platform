import { lazy, Suspense } from "react";
import type { UiModulePages } from "../pages";
import "./document-automation.css";
import { DocumentAutomation } from "./ConsolePage";

/** DA-1: Settings > Document Automation, "Folders processes may use"; loaded when that section opens. */
const Folders = lazy(() => import("./allowedFolders"));
function AllowedFoldersPart(p: { onNotice: (t: string) => void; onError: (e: string) => void }) {
  return <Suspense fallback={<p className="st-muted">Loading the folders…</p>}><Folders {...p} /></Suspense>;
}

/** document-automation's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "docautomation": (p) => <DocumentAutomation api={p.api} />,
  },
  parts: {
    "allowed-folders": AllowedFoldersPart,
  },
};
