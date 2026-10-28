# ONEOFFICEAUTOMATION architecture collection

## What this covers / who it is for

This collection explains how ONEOFFICEAUTOMATION (OOA) is built: its parts, how they talk to each other, where data
lives, how it is secured, and how it can be extended. It is written for architects, senior developers, security
reviewers and operators of large installations who need to understand the product below the user interface. It
describes product version 0.2.0 (Gradle `version` in `platform/build.gradle.kts`) on the `phase-2` branch. The code
in `platform/` is the source of truth; where this collection and the code disagree, the code wins.

The other collections cover different ground:

- the **functional** collection describes what each feature does for the business user;
- the **infrastructure** collection covers installation, sizing, networks and operations;
- the **user guide** collection gives step-by-step instructions for each screen.

## Contents

| # | Document | One line |
| --- | --- | --- |
| 0 | [README.md](README.md) | This index and the reading order |
| 1 | [01-system-overview.md](01-system-overview.md) | The system in context: people, parts, external services and deployment shapes |
| 2 | [02-components-and-modules.md](02-components-and-modules.md) | Every Gradle module, what it holds, how modules depend on each other, the technology stack |
| 3 | [03-agent-model-and-runner.md](03-agent-model-and-runner.md) | The agent (Flow JSON) format and how the Runner engine executes it |
| 4 | [04-command-center-internals.md](04-command-center-internals.md) | The Command Center server: requests, APIs, scheduling, dispatch, queues, workflows, clustering |
| 5 | [05-data-model.md](05-data-model.md) | Every database table by area, supported databases and schema-per-tenant |
| 6 | [06-security-architecture.md](06-security-architecture.md) | Authentication, permissions, vault, masking, audit chain, TLS, runner trust, signing |
| 7 | [07-toolkit-architecture.md](07-toolkit-architecture.md) | How toolkits are built, signed, versioned and shown in Studio |
| 8 | [08-ai-architecture.md](08-ai-architecture.md) | Model connections, AI governance, AI agents, Prompt Skills, web agent, knowledge Q&A, MCP |
| 9 | [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md) | Tenants, regions, routing, the operator API and the directory |
| 10 | [10-extensibility-and-integration.md](10-extensibility-and-integration.md) | REST API, MCP, webhooks, custom toolkits, connectors, Git mirror, templates |
| 11 | [11-developer-guide.md](11-developer-guide.md) | Building from source, tests and CI, adding toolkits and features, Studio internals, known limits |

## Reading order

1. Start with **01 System overview** for the big picture and the names used everywhere else.
2. Read **02 Components and modules** to map those names onto code.
3. Then read **03 Agent model and runner** and **04 Command Center internals**: together they explain what happens
   between "someone builds an agent" and "the agent ran on a runner".
4. **05 Data model** and **06 Security architecture** are reference material for database administrators and security
   reviewers.
5. **07 Toolkit architecture** and **10 Extensibility and integration** are for developers who add toolkits or
   integrate OOA with other systems.
6. **08 AI architecture** and **09 Multi-tenant SaaS and regions** cover the AI features and the hosted service.
7. **11 Developer guide** is for people who change the product itself.

## Names used in this collection

| Name in the product | Name in the code | Meaning |
| --- | --- | --- |
| One Command Center | `control-room` module, `ControlRoom`, `ControlRoomServer` | The shared server: repository, runs, users, vault, schedules, audit |
| One Agent Studio | `cli` module, `StudioServer`, `studio.js` | The browser-based designer, served on this computer only |
| One Runner | `agent` module, `Agent`, `RunWorker` | The worker that runs agents on a machine |
| Runner engine | `runner` module, `Runner` | The interpreter for an agent definition |
| Agent | a Flow JSON file (`*.flow.json`); "bot" in older code | One automation |
| Toolkit | a package class annotated `@OoaPackage` | A set of actions, conditions and loop iterators |
| Action | a method annotated `@Action` | One command an agent step calls |
| Vault folder | `lockers` table | A named group of credentials with owners and consumers |

Older code and some table names still say "bot", "device", "locker" and "Control Room". These are internal names.
The user-facing names are agent, runner, vault folder and Command Center.

## Conventions

- Paths are relative to the repository root `C:\OOA\oneofficeautomation` unless they start with a module name, in
  which case they are under `platform/`.
- Java classes are named without their package; the package is `com.oneofficeautomation.<module>`.
- Error codes are written as they appear in the code, for example `RUN.TIMEOUT` or `CR.LICENSE_LIMIT`.

## Related documents

- [01-system-overview.md](01-system-overview.md)
- [02-components-and-modules.md](02-components-and-modules.md)
- [03-agent-model-and-runner.md](03-agent-model-and-runner.md)
- [04-command-center-internals.md](04-command-center-internals.md)
- [05-data-model.md](05-data-model.md)
- [06-security-architecture.md](06-security-architecture.md)
- [07-toolkit-architecture.md](07-toolkit-architecture.md)
- [08-ai-architecture.md](08-ai-architecture.md)
- [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md)
- [10-extensibility-and-integration.md](10-extensibility-and-integration.md)
- [11-developer-guide.md](11-developer-guide.md)
