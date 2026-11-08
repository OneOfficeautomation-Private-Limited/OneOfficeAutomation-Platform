import type { UiModuleInfo } from "../registry";

/** insights: what it adds to the menu (modules/registry.ts finds this file; its pages are in pages.tsx). */
export const info: UiModuleInfo = {
  id: "insights",
  menu: [
    { section: "insights", after: null, page: {"id": "insights", "label": "Overview", "perm": "analytics.view"} },
    { section: "insights", after: "insights", page: {"id": "alerts", "label": "Alerts", "perm": "analytics.view"} },
    { section: "insights", after: "alerts", page: {"id": "alert-rules", "label": "Alert rules", "perm": "analytics.view"} },
    { section: "insights", after: "alert-rules", page: {"id": "operations", "label": "Operations", "perm": "analytics.view|queues.view|audit.view"} },
    { section: "insights", after: "operations", page: {"id": "business", "label": "Business", "perm": "analytics.view"} },
    { section: "insights", after: "business", page: {"id": "runner-status", "label": "Runner status", "perm": "analytics.view"} },
    { section: "insights", after: "runner-status", page: {"id": "upcoming", "label": "Upcoming runs", "perm": "analytics.view"} }
  ],
  moved: {"capacity": ["operations", "runners"], "queueinsights": ["operations", "queues"], "value": ["business", "value"], "dashboards": ["business", "dashboards"]},
};
