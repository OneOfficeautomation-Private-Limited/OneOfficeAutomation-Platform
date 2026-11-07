# 13. Modules: one platform, separately changed parts

Status: **approved** (2026-11-07). Phase 0 (the module map and the boundary report), phase 1 (the module
contract, with Document Automation as the first module moved out), phase 2 (automation ideas, AI services,
low-code and One Chat moved out), phase 3 (testing, governance, insights, integration, runner management), phase 4
(the workflow engine as the orchestration module; the Task inbox stays in the kernel) and phase 5 (toolkit groups
found with ServiceLoader) are built. Phases 6 to 8 follow.

## 1. Why

Every feature of One CommandCenter used to be wired in through the same five kernel files: `ControlRoom.java`
(which created all 38 features), `ControlRoomServer.java` (one route switch), `Db.java` (one numbered schema),
`Rbac.java` and `Platform.java` (permissions and settings). In the UI, one API client (`cr.ts`), one stylesheet
(`console.css`) and one menu did the same. So every feature touched every other feature's files: merging gaps
G39-G83 and One Chat on 7 Nov 2026 needed 26 conflicts resolved by hand, One Chat's tables had to be renumbered from
schema 25 to 45, and the Apps page's `.cc-app` class squeezed the whole console to 760 pixels. CI ran every job for
every change (about 30 minutes).

The goal: a change to one area touches only that area's files and runs only that area's tests, and each area has its
own version.

## 2. The decision

**One repository with many Gradle modules** (not one repository per module): one build, one CI, boundaries checked by
tests on every build. DocFlow stays the one separate repository and service, reached through its versioned interface
(`/api/docs/v1`, `docs/integration/document-automation-contract.md`), because it is a different program (Python).

Further decisions (2026-11-07, chosen for future development and upgrades):

| Question | Decision | Why |
| --- | --- | --- |
| One repository or one per module | One repository, one Gradle project per module | One build and one CI see every module together, so a kernel change is tested against all modules at once; boundaries are enforced by tests, not by repository walls. Separate repositories would bring version skew between kernel and modules on every release |
| Turn modules on and off by licence | Not now: every edition ships every module (as decided for Document Automation's engine packs). The contract already allows it later: a module missing from the class path simply is not there | Avoids a licence check in every module; one licence covers the platform |
| Who owns a module's permissions | The module, through the contract (phase 8 adds `ModuleContext.permission`). Until then `docs.*` stays listed in `Rbac` | Moving permissions touches custom roles in existing databases, so it gets its own careful step |
| The classic console (`cr.js`) | Frozen: no new features; new screens only in the new console, under `ui/src/modules/<name>` | One place per screen; the classic console retires once nothing needs it |
| Version numbers | Each module has its own (`version()`), the platform release lists them; the DocFlow pin stays in the module | A fix in one module ships without renumbering the rest |

Five layers; a module uses only modules in lower layers and the kernel:

| Layer | Modules |
| --- | --- |
| 0 Foundation | `sdk`, `runner`, `adapters-jackson`, contracts |
| 1 Toolkits | `packages` (core) and `packages-*`, and the toolkit groups in `platform/toolkits/<group>` (microsoft, google, enterprise, cloud-data, ai, ...), each found with `ServiceLoader` (`ToolkitGroup`) since phase 5 |
| 2 Command Center kernel | sign-in, users, roles, folders, repository, runs and dispatch, audit, vault, settings, database, cluster, licensing, tenants, and the module contract |
| 3 Feature modules | document-automation, one-chat, orchestration, governance, insights, ai-services, low-code, coe, testing, runner-management, integration, enterprise-ops |
| 4 Applications and packaging | `cli` and Studio, `agent` (the runner), the UI shell, installers, containers, Helm |

`module-map.json` at the repository root says which module owns every file. The most specific path wins, so a
feature module's file inside `platform/control-room` belongs to it until it moves out.

Rules, checked by tests:

1. The kernel never names a feature module's classes (`ArchitectureTest.moduleBoundaries`).
2. A feature module uses only the kernel's published contract, `com.oneofficeautomation.controlroom.module`, and the
   few kernel types it carries (`ControlRoom.User`, `Db`, `Vault`, `MailSource`, `DbConfig`, `ConsolePlugin`).
3. Feature modules never name each other's classes; they talk through kernel events.
4. Every Command Center class and console page belongs to a module (`ModuleMapTest`), and the boundary report counts
   where modules still reach into each other.

## 3. The module contract

A feature module is a Gradle project in `platform/modules/<name>` with one class implementing
`CommandCenterModule`, listed in `META-INF/services/com.oneofficeautomation.controlroom.module.CommandCenterModule`.
The kernel finds it with `ServiceLoader` (the `Modules` registry), brings its tables up to date, and calls `start`
with a `ModuleContext`. Without the module on the class path the kernel runs as before, without that feature.

What a module asks the kernel for (`ModuleContext`): the tenant, the data folder, the database, the vault, active
users, permission checks, folder access, vault folder access, system credentials, the Audit Trail, settings, the
outbound address check, agents' content and inputs, starting runs, a runner's runs, firing triggers, mailboxes, and
the HTTP helpers its routes need.

What a module adds, in `start`:

| It adds | With | The kernel then |
| --- | --- | --- |
| Screens' API | `route(prefix, handler)` | sends `/api/{prefix}/...` to it after sign-in; the kernel's own routes come first |
| A runner API | `agentRoute(route, handler)` | sends `/api/agent/{route}` to it, authenticated as the runner |
| A call from another service | `callbackRoute(route, handler)` | sends `POST /api/{route}` to it before sign-in; the module checks the service token |
| Background work | `every(delay, period, job)` | runs it on the cluster's leader |
| Settings | `defineSetting(key, default, check)` | keeps the value with the others and runs the check when it is saved |
| Task inbox tasks | `taskKind(kind, rules)` | asks the rules who sees a task and whether it closes elsewhere |
| Trigger kinds | `triggerKind(kind, label, rules)` | lists the kind, stores its settings as the module cleans them, and never polls it |
| AI assistant tools | `mcpTools(tools)` | offers them through the MCP server before the agent tools |
| Template content | `templatePart(part)` | asks the part to add to a shared bundle and to create its items on install |
| A part of every run's job | `jobPart(key, part)` | puts it in the job under the key when a runner takes the run; a refusal fails the run before it starts (Prompt Skills) |
| Requests before sign-in | `publicRoute(prefix, handler)` | sends `/api/{prefix}/...` to it; the module checks its own credential (low-code's embed key) |
| A page other sites may frame | `embeddablePage(path, origins)` | serves the console page at `/{path}/{id}` with frame-ancestors from the module; an unknown id is 404 |
| Runs that end | `onRunEnded(listener)` | tells it after the kernel's own follow-up (One Chat tells the chat at once) |
| Task inbox stages | `taskStage(stage, rules)` | asks it before such a task is completed and tells it the decision (One Chat's R3 approvals) |
| Objects for others | `provide(type, object)` | finds it with `Modules.service(type)` (tests, and other modules through a kernel interface) |
| Check-in rules | `checkinListener(listener)` | asks it before a version is stored (it may refuse) and tells it afterwards (governance's code analysis) |
| Approval before production | `productionGate(gate)` | turns "make live" into a request the gate keeps (one module) |
| AI guardrails per run | `guardrails(source)` | puts the run's folder guardrails in its job (governance's policies) |
| Repository changes | `onRepositoryChanged(listener)` | tells it after every check-in, move or delete (integration's Git sync) |
| Requests from a running agent | `runRequest(kind, handler)` | sends a runner's request of that kind to it (insights' `raise_alert`) |
| Work with no runner | `onNoRunner(listener)` | tells it the pool that has waiting work and no runner (runner management scales the pool up) |
| The workflow engine | `workflowTasks(source)` | asks it what a workflow task shows in the Task inbox, where a rejection goes, and tells it a task is done (orchestration, one module) |
| Definition rules | `definitionCheck(suffix, check)` | checks files with that ending at check-in instead of the agent schema (orchestration: `.process.json`, `.form.json`); without one they are stored as they are |
| Its own tables | `schema()`: numbered steps | applies the missing steps after the kernel's schema and records the module's version in `module_schema` (kernel schema 46) |

`GET /api/modules` lists the modules running, with their versions.

## 4. Document Automation, the first module (phase 1)

| Part | Where |
| --- | --- |
| Server side | `platform/modules/document-automation` (Gradle project `:document-automation`, package `com.oneofficeautomation.modules.docauto`): `DocumentAutomationModule`, `DocFlowProxy`, `DocFlowConfig`, `McpDocuments`, `DocTemplates`, `DocumentAutomationPlugin`, `docflow-compat.properties` |
| Screens | `platform/ui/src/modules/document-automation` (the pages at `/documents/`, and the console's AI > Document Automation page) |
| Tests | the module's own (`:document-automation:test`), including `DocFlowLiveTest` against a real DocFlow |
| CI | the `docflow` and `docflow-windows` jobs run only when the module, something it depends on, or the installer changed (`changes` job); master runs everything |
| Tables | `docflow_mail` stays in the kernel's frozen baseline (version 33); new tables go in the module's `schema()` |

What moved out of the kernel: the DocFlow proxy and its routes, the review-task sync and email inboxes (now module
clock jobs), the settings `docflow_url`, `docflow_token_ref` and `mcp_documents`, the `document_review` task rules in
Workflows, the six "On Document Automation" trigger kinds and their settings check, the documents_ MCP tools, and
processes in templates. The Windows installer reads the DocFlow pin from the `document-automation` jar.

Still to move (later phases): the `docs.*` permissions are still listed in `Rbac` (phase 8: permissions registered by
modules); the console wires the module's page in directly (phase 6: a UI module registry); the toolkit
`DocumentAutomationPackage` stays in the toolkits layer and talks to the module over HTTP.

### What stays in the kernel (phases 3 and 4)

Some parts the plan first listed under a feature module are part of every run or every sign-in. They stay in the
kernel, and feature modules plug into them through the contract:

| Stays in the kernel | Why |
| --- | --- |
| Schedules, triggers and trigger pollers, run waits (long-running agents), queues, environment locks | Run dispatch: every run passes through them. Modules add trigger kinds (`triggerKind`) |
| The Task inbox (`TaskInbox`, table `human_tasks`) and its delivery (`ApprovalChannels`: e-mail, phone, Teams) | Every module puts tasks there (workflows, Document Automation reviews, One Chat approvals, AI agents' requests). The workflow engine describes and continues its own tasks through `WorkflowTasks` |
| Event subscriptions, metrics, syslog forwarding | Observability of the whole platform |
| SCIM | Identity, with sign-in and users |
| The server-side runner (`ServerlessRuns`) | A runner, used by runs from any source |
| Runner care and runner updates | Part of every heartbeat and of dispatch |
| The savings settings (`analytics/goal`, `analytics/queue-savings`), the setting `production_approval` | Kept with the other settings so existing databases need no change |

## 5. Phases

| Phase | What | State |
| --- | --- | --- |
| P0 | Module map, boundary report (87 crossings on 7 Nov 2026), this record | done |
| P1 | Module contract, `Modules` registry, schema 46 (`module_schema`), Document Automation moved out, CI `changes` job | done |
| P2 | coe (automation ideas), ai-services (Prompt Skills, knowledge bases), low-code (apps, embed links, data store), one-chat moved out; contract grows job parts, public routes, embeddable pages, run-ended listeners, task stages, services (boundary crossings 81 -> 59) | done |
| P3 | testing (test sets), governance (code analysis, policies, production approval), insights (analytics, dashboards, alerts), integration (Agent APIs, MCP server, Git sync), runner-management (pool auto-scaling); contract grows check-in listeners, the production gate, guardrails, repository changes, run requests, the no-runner hook; mixed tests move to the CLI's whole-product tests (boundary crossings 59 -> 26) | done |
| P4 | orchestration: the workflow engine moves out; the Task inbox (`TaskInbox`) and `FlowFiles` stay the kernel's; contract grows the workflow engine hook (`WorkflowTasks`), definition checks, licence features and task notifications | done |
| P5 | Toolkit groups in `platform/toolkits/<group>`, found with ServiceLoader (`ToolkitGroup`) instead of one list in `FirstPartyPackages`; One CommandCenter ships every group | done |
| P6 | UI: a module registry for menu and routes, the API client, stylesheet and translations split per module, class prefixes checked | next |
| P7 | CI runs each module's tests only when it is affected; build cache; CODEOWNERS per module | |
| P8 | The rest of the kernel into packages (auth, runs, repository, vault, settings); permissions registered by modules; Settings > About lists module versions | |

Each phase moves one module at a time on a short branch, keeps behaviour the same, and merges only with CI green.
New feature work pauses only for the module being moved.

## 6. Adding or moving a module

1. Add it to `module-map.json` (id, layer, paths, dependsOn).
2. Create `platform/modules/<name>/build.gradle.kts` depending on `:control-room` (and `:sdk`), include it in
   `platform/settings.gradle.kts`, and add it to the `cli` dependencies.
3. Write the `CommandCenterModule` class and its services file; register everything in `start`.
4. Move the classes, changing every kernel call to a `ModuleContext` call (the kernel's ids, times, errors, tokens and
   hashes are static methods of `ModuleContext`). If the module needs something the context
   does not offer, add it to the contract (a public, documented method), never a back door.
5. Move its tests and screens (`platform/ui/src/modules/<name>`); the tests stay in the kernel's package and use the
   kernel's test kit (the module's build script adds the kernel's test classes), and reach the module's objects with
   `cr.modules.service(Type.class)`. Then add the module to `ENFORCED` in
   `ModuleMapTest` and to `FEATURE_MODULES` in `ArchitectureTest`.
6. Give CI jobs that test only this module `needs: changes` and an `if:` on its output.

`python -I platform/scripts/affected_modules.py <base> [<head>]` shows which modules a change affects.
