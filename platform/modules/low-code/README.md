# Low-code apps and data store module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 2.
Version: `LowCodeModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `LowCodeModule.java` |
| What it serves | /api/apps, /api/data, /api/agent/data, /api/embed (embed key), the page /embed/{link} |
| Its classes | Apps.java, AppEmbeds.java, DataStore.java |
| Screens | `platform/ui/src/modules/low-code` |

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :low-code:test`.
