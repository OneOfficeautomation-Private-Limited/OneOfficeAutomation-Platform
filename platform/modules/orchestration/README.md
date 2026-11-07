# Orchestration module (workflow engine)

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 4.
Version: `OrchestrationModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `OrchestrationModule.java` |
| What it serves | /api/workflows (runs, form, start, runs/{id}/cancel) |
| Its classes | Workflows.java |
| Screens | `platform/ui/src/modules/orchestration` |

It checks workflows and forms at check-in (DefinitionCheck), continues a workflow when its agent run ends (RunEnded) and when a task is done in the kernel's Task inbox (WorkflowTasks), and retries runs waiting for a runner every 5 s.

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :orchestration:test`.
