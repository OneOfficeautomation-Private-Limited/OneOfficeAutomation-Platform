# AI services module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 2.
Version: `AiServicesModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `AiServicesModule.java` |
| What it serves | /api/skills, /api/knowledge-bases, /api/agent/knowledge; the skills part of every run's job |
| Its classes | PromptSkills.java, KnowledgeBases.java |
| Screens | `platform/ui/src/modules/ai-services` |

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :ai-services:test`.
