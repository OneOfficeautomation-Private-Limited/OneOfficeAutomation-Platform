import type { UiModulePages } from "../pages";
import "./insights.css";
import { Alerts } from "./Alerts";
import { BusinessPage, OperationsPage } from "./Insights";
import { Overview } from "./Overview";
import { RunnerStatus } from "./RunnerStatus";
import { UpcomingRuns } from "./UpcomingRuns";

/** insights's console pages (by menu page id) and the parts other pages show when it is installed (modules/pages.tsx finds this file). */
export const ui: UiModulePages = {
  pages: {
    "insights": (p) => <Overview api={p.api} user={p.user} />,
    "alerts": (p) => <Alerts api={p.api} user={p.user} onNotice={p.onNotice} />,
    "alert-rules": (p) => <Alerts key="rules" api={p.api} user={p.user} onNotice={p.onNotice} view="rules" />,
    "operations": (p) => <OperationsPage api={p.api} user={p.user} />,
    "business": (p) => <BusinessPage api={p.api} user={p.user} onNotice={p.onNotice} />,
    "runner-status": (p) => <RunnerStatus api={p.api} />,
    "upcoming": (p) => <UpcomingRuns api={p.api} />,
  },
};
