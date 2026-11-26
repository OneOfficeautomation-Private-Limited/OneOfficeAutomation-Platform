import type { UiModuleInfo } from "../registry";

/** insights: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "insights",
  menu: [
    { section: "monitor", after: "status", page: {"id": "insights", "label": "Dashboards", "perm": "analytics.view"} },
    { section: "monitor", after: "business", page: {"id": "alerts", "label": "Open alerts", "perm": "analytics.view"} },
    { section: "monitor", after: "alerts", page: {"id": "alert-rules", "label": "Alert rules", "perm": "analytics.view"} },
    { section: "monitor", after: "insights", page: {"id": "operations", "label": "Runs", "perm": "analytics.view|queues.view|audit.view"} },
    { section: "monitor", after: "runner-status", page: {"id": "business", "label": "Business", "perm": "analytics.view"} },
    { section: "monitor", after: "operations", page: {"id": "runner-status", "label": "Runner status", "perm": "analytics.view"} },
    { section: "run", after: "triggers", page: {"id": "upcoming", "label": "Upcoming runs", "perm": "analytics.view"} }
  ],
  moved: {"capacity": ["operations", "runners"], "queueinsights": ["operations", "queues"], "value": ["business", "value"], "dashboards": ["business", "dashboards"]},
};
