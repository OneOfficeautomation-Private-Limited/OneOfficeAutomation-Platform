# Agent testing module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 3.
Version: `TestingModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `TestingModule.java` |
| What it serves | /api/test-sets (test sets, cases and mocks); a finished run of a test case is checked against its expectations |
| Its classes | TestSets.java |
| Screens | `platform/ui/src/modules/testing` |

It hooks into run ends (RunEnded) to grade test runs.

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :testing:test`.
