# Integration module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 3.
Version: `IntegrationModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `IntegrationModule.java` |
| What it serves | /api/apitasks (run and poll with the task token before sign-in), /api/mcp, /api/git |
| Its classes | ApiTasks.java, GitSync.java, McpServer.java |
| Screens | `platform/ui/src/modules/integration` |

Git sync follows repository changes (RepositoryChange). The server-side runner and SCIM stay the kernel's.

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :integration:test`.
