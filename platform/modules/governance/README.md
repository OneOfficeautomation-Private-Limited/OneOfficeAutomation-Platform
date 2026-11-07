# Governance module

A feature module of One CommandCenter (`docs/architecture/13-modules.md`), moved out of the kernel in phase 3.
Version: `GovernanceModule.VERSION`.

| What | Where |
| --- | --- |
| Entry point (everything the module adds) | `GovernanceModule.java` |
| What it serves | /api/policies, /api/repo/analyze, /api/repo/analysis, /api/repo/policy, /api/repo/production-requests |
| Its classes | CodeAnalysis.java, Policies.java, ProductionApproval.java |
| Screens | `platform/ui/src/modules/governance` |

It plugs into check-in (CheckinListener), making a version live (ProductionGate) and run dispatch (Guardrails).

It reaches the kernel only through `com.oneofficeautomation.controlroom.module.ModuleContext`; `ArchitectureTest` and
`ModuleMapTest` check that. Its tests run with `./gradlew :governance:test`.
