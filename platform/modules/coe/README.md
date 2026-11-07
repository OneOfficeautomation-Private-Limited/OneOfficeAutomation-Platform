# Automation ideas module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 2.
Version: `CoeModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `CoeModule.java` |
| What it serves | /api/ideas; Build > Automation ideas |
| Its classes | Ideas.java |
| Screens | `platform/ui/src/modules/coe` |

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :coe:test`.
