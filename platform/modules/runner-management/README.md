# Runner management module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 3.
Version: `RunnerManagementModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `RunnerManagementModule.java` |
| What it serves | /api/pool-scaling (also /api/pools/{id}/scaling through the kernel) |
| Its classes | PoolScaling.java |
| Screens | `platform/ui/src/modules/runner-management` |

Pools scale up when work waits with no runner (NoRunner) and are checked every 60 s. Runner care and updates stay the kernel's.

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :runner-management:test`.
