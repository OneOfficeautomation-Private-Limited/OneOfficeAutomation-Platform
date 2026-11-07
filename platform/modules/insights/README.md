# Insights module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 3.
Version: `InsightsModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `InsightsModule.java` |
| What it serves | /api/analytics (except analytics/goal and analytics/queue-savings, the kernel's), /api/dashboards, /api/alerts, /api/alert-rules; the run request raise_alert |
| Its classes | Alerts.java, Analytics.java, Dashboards.java |
| Screens | `platform/ui/src/modules/insights` |

Alert rules and mailed dashboards run on the clock (every 15 s, on the leader); KPIs come from successful runs.

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :insights:test`.
