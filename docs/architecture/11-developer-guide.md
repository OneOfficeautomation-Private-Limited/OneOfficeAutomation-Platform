# 11 Developer guide

## What this covers / who it is for

This document is for developers who change the product itself: how to build and run it from source, how the test
suites are organised and run, how to add a toolkit, a Command Center feature, a Studio capability, a trigger kind or a
model provider, how Studio's own API and UI are put together, the rules every change follows, and the known limits of
the current design. Customers who only add actions should read
[10-extensibility-and-integration.md](10-extensibility-and-integration.md#7-custom-toolkits-with-the-sdk) instead.

## Contents

1. [Build and run from source](#1-build-and-run-from-source)
2. [Tests](#2-tests)
3. [Adding a first-party toolkit](#3-adding-a-first-party-toolkit)
4. [Adding a Command Center feature](#4-adding-a-command-center-feature)
5. [Adding a Studio capability](#5-adding-a-studio-capability)
6. [Adding a trigger kind or a model provider](#6-adding-a-trigger-kind-or-a-model-provider)
7. [Inside Studio](#7-inside-studio)
8. [Rules for every change](#8-rules-for-every-change)
9. [Known limits](#9-known-limits)
10. [Related documents](#related-documents)

---

## 1. Build and run from source

All commands run from `platform/` in PowerShell (`gradlew` on Linux and macOS).

| Task | Command |
| --- | --- |
| Everything on one port (Command Center, Studio, a local runner) | `.\gradlew.bat :cli:run --args="server"`, or `scripts\start-ooa.bat` |
| Any other `ooa` command | `.\gradlew.bat :cli:run --args="<command> <options>"` |
| An installed copy to run without Gradle | `.\gradlew.bat :cli:installDist`, then `cli\build\install\ooa\bin\ooa.bat` |
| Customer zip, setup package, MSI, native executable, SBOM | See [02-components-and-modules.md](02-components-and-modules.md#8-builds-and-distributions) |
| Leave out an optional toolkit module | Remove it from `ooa.optionalModules` in `gradle.properties` |
| A forgotten administrator password (server stopped) | `ooa reset-admin [--user NAME] [--data DIR]`; it is audited |
| The new Studio preview (platform/ui) | Built by every Gradle build (`:cli:uiBuild`, needs Node.js 22); served at `/studio/next/`. See 7.3 |

`ooa server` stores its data in `%USERPROFILE%\OOA Command Center` and Studio files in `%USERPROFILE%\OOA Workspace`
unless `--data` and `--workspace` say otherwise. The vault key comes from `OOA_MASTER_KEY`, `OOA_MASTER_KEY_SOURCE`
or `master.key` in the data folder. The other folders and settings are listed in
[01-system-overview.md](01-system-overview.md#6-ports-addresses-and-folders) and
[04-command-center-internals.md](04-command-center-internals.md#15-settings).

## 2. Tests

### 2.1 Suites

| Suite | How to run (from `platform`) | Covers |
| --- | --- | --- |
| All Java tests | `.\gradlew.bat test` (or `build`, which also builds) | The runner, every toolkit against local fake services, the Command Center end to end (`ControlRoomE2ETest`, `PlatformTest`, `EnterpriseFeaturesTest`, `UnifiedServerTest`, trigger tests), the debugger, the architecture rules |
| One module or class | `.\gradlew.bat :packages:test --tests "*GitHubPackageTest*"` | |
| Command Center on SQL Server or PostgreSQL | `.\gradlew.bat :control-room:test "-Pooa.test.db=jdbc:..." -Pooa.test.db.user=U -Pooa.test.db.password=P` | Each test data folder gets its own `ooa_t_*` database |
| Load test | `.\gradlew.bat :cli:loadTest "-Pooa.load.runners=20" "-Pooa.load.items=300" "-Pooa.load.audit=2000"` | Many runners, a work queue and the audit trail; tests tagged `load` run only here |
| Conformance cases | `.\gradlew.bat :cli:run --args="conformance ../contracts/conformance/cases"` | The shared runner cases (see [03-agent-model-and-runner.md](03-agent-model-and-runner.md#16-python-reference-runner-and-conformance)) |
| Console UI | `node console.ui.test.js` in `control-room/src/test/ui`, against a running `ooa control-room`; variables `CONSOLE_DIR`, `CR_URL`, `CR_ADMIN_PASSWORD` | The console in jsdom |
| Studio UI | `node studio.ui.test.js` in `cli/src/test/ui`, against a running `ooa studio`; variables `STUDIO_DIR`, `OOA_WS`, `OOA_STUDIO_URL` (default `http://localhost:8710`) | Studio in jsdom |
| Command panels | `node panels.ui.test.js` in `cli/src/test/ui`; `PANEL_FILTER` limits it to action ids that start with a prefix, for example `ooa.excel/` | Every action's settings panel that declares a layout: field order, More options, tick boxes, required markers |

The UI tests need Node.js with `jsdom` on the module path.

### 2.2 What CI runs

`.github/workflows/ci.yml` runs on every push and pull request:

| Job | Platform | What it checks |
| --- | --- | --- |
| `build-test` | Windows | The whole build and every Java test on H2, a short load test, the customer zip and setup package (no vendor files; Java, installer and SBOM present), the SBOM as an artefact |
| `sqlserver`, `postgres` | Linux | The Command Center tests against throwaway SQL Server 2022 and PostgreSQL 16 containers |
| `python` | Linux | The Python reference runner's tests, conformance cases included |
| `headless` | Linux on every push, macOS on `master` | A Command Center and a headless runner end to end, the runner and SDK tests and the conformance cases |
| `native` | Linux and Windows | The GraalVM executable gives the same answers as the Java program |
| `container` | Linux | The image alone and with PostgreSQL, backup and restore into another database, a SaaS region with two tenants |
| `ui` | Linux | The console and Studio UI tests against servers started from the build |
| `msi` | Windows | Builds the MSI, installs it as a Command Center service, adds a runner service, uninstalls |

### 2.3 Conventions

- Cloud toolkits are tested against a local fake HTTP server (`ToolkitTestSupport`, `RestToolkitTestSupport`, the fake
  in `ConnectorsTest`). Each test checks the method, path, query, headers and body sent, the outputs, the error codes,
  and that no secret appears in the logs.
- Samples that reach live public websites are skipped when `OOA_INTERNET_SAMPLES=0` (as in CI); run them locally.
- Run the Java tests from PowerShell or cmd, not Git Bash. The PGP tests need a Windows `gpg` and are skipped when none
  is installed.
- On a desktop, `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=true'` keeps tests from opening windows.
- Check a changed browser script with `node --check` before running the UI tests.

## 3. Adding a first-party toolkit

1. Create `packages/src/main/java/com/oneofficeautomation/packages/<Name>Package.java`. If it needs a heavy library,
   make an optional module instead: a folder `packages-<x>` with its own `build.gradle.kts`, added to
   `ooa.optionalModules`.
2. Annotate it as in [07-toolkit-architecture.md](07-toolkit-architecture.md#2-annotations). For a web API, copy
   `JiraPackage` or `GitHubPackage`: `HttpJson`, a base-URL input, a Credential token input, paging, errors mapped to
   `NAME.CODE`, and 429 and 5xx marked retryable.
3. Register it in `FirstPartyPackages`: the class in `ALL`, or its class name in `OPTIONAL` for an optional module.
4. Write `<Name>PackageTest` against the fake server. Run `.\gradlew.bat :packages:test --tests "*<Name>*"` and
   `ArchitectureTest`.
5. Regenerate the toolkit reference (`docs/reference/toolkits/`, made from the manifests by
   `docs/research/tools/make-reference.py`; never edited by hand). Give it a row in `GROUP_LIST` in `studio.js` (group
   name, icon, colour and section). Without one it gets a group of its own, named after the toolkit, under "More
   toolkits". A toolkit that covers several technologies is split by action id in `groupOf()`.
6. Nothing else is needed. Studio's action list and panels, validation, the AI assistant and the Command Center's
   Toolkits page all read the manifest.

## 4. Adding a Command Center feature

1. **Data:** add a new array `SCHEMA_Vn` in `Db.java`, append it to `SCHEMA` and raise `Db.LATEST`. Never edit an
   earlier array: it may already be applied to someone's database. Use SQL that `SqlServerDialect` can translate, and
   run the tests on SQL Server and PostgreSQL too (see [05-data-model.md](05-data-model.md)).
2. **Logic:** add a service method in `Platform` (or a class of its own, like `Workflows`). Start it with
   `cr.require(user, "<permission>", "<what>")`, use `db.query`, `db.one` and `db.update` with `?` placeholders only,
   and record `cr.audit.record(...)`.
3. **Setting:** a new setting needs only a default in `Platform.DEFAULTS` (and a range in `RANGES` for a number); it
   then appears in the API. `updateSettings` checks every change before saving.
4. **Permission:** add it to `Rbac`, and to `ADMIN_ONLY` when only administrators should hold it by default.
5. **API:** add a route in `ControlRoomServer.api(...)`, chosen by the first path segment.
6. **UI:** add a page to `PAGES` and `VIEWS` in `cr.js`. Build it with the helpers `h()`, `header()`, `dataTable()`,
   `modal()`, `openDrawer()`, `tabStrip()`, `kv()` and `pill()`. Put styles in `cr.css`.
7. **Tests:** a Java end-to-end test (see `PlatformTest`) and checks in `console.ui.test.js`.

## 5. Adding a Studio capability

- **API:** add a route in `StudioServer.api(...)`. Studio already refuses callers that are not on this computer.
- **UI:** add it to `studio.js`, using the same `h()` helper, dialogs and `api()` wrapper.
- **Engine changes:** add an interface or hook in `runner` (as `Runner.Debugger` and `ValueTrail` were added), never
  Studio code in the runner.
- **Tests:** checks in `studio.ui.test.js`; for a panel change, `panels.ui.test.js`.

## 6. Adding a trigger kind or a model provider

| Addition | Steps |
| --- | --- |
| Runner-side trigger | A watcher in `LocalTriggers` (module `agent`) and the kind in `TriggerKinds` |
| Server-side trigger | A source in `TriggerPollers` (module `control-room`) and the kind in `TriggerKinds`; keep the cursor in `triggers.last_hash` |
| Either kind | Its fields in `triggerDialog()` in `cr.js`; the agent receives the event as `trigger_<kind>` and `trigger_event` |
| Model provider | See [08-ai-architecture.md](08-ai-architecture.md#13-adding-a-provider) |
| External vault | A `kind` in `ExternalVaults` with its required settings and a read method |

## 7. Inside Studio

### 7.1 Studio API (`/studio/api/...`)

`StudioServer` answers on 127.0.0.1 only, refuses other `Host` headers and cross-site `Origin` values, and inside
`ooa server` reuses the browser's Command Center sign-in (see
[06-security-architecture.md](06-security-architecture.md#9-studio-and-local-components)).

| Area | Routes |
| --- | --- |
| Workspace | `info`, `tree`, `agents` (every agent with its id, name, variables and the agents its Run agent steps run), `bot` (GET and PUT an agent; PUT returns the validation problems), `item` (other item types), `deps`, `search`, `builtins` (`$sys` and `$global` values) |
| Explorer files | `POST files/{folder, bot, item, rename, move, copy, duplicate, delete, reveal}`; `POST files/upload?parent=&name=` with the raw bytes (50 MB per file; unsafe characters in the name become `_`); `GET files/download?path=` (a folder comes as a .zip). `copy` gives a taken name " - Copy"; `duplicate` gives a copied agent its own id; `delete` moves to `.trash`; `reveal` opens File Explorer (Windows) |
| Toolkits and checks | `packages` (all manifests), `validate`, `condition/test` |
| Runs | `POST run` (waits for the result, 5 minutes at most); `POST runs` (in the background) with `flow`, `inputs`, `debug`, `breakpoints`, `start_at`, `only_step`, `carry_over`, `run_window`, `record_objects`; `GET runs/{id}?after=n` (new events, state, result); `POST runs/{id}/{pause, resume, stop, debug, breakpoints, variable}`; `GET runs/{id}/objects` (elements found by selector, for "Selectors to objects") |
| Capture and record | `capture`, `capture/test`, `capture-desktop`, `windows`, `record` (`target: "desktop"` for desktop), `record/progress`, `record/cancel` |
| Connections | `connections` (saved connector connections, `connections.json`), `model-connections` (`model-connections.json`), `connectors` (custom connectors) |
| Command Center link | `cr/status`, `cr/connect`, `cr/disconnect`, `cr/repo`, `cr/versions`, `cr/checkout`, `cr/checkin` (`production` true makes the version live), `cr/undo-checkout` |
| Other | `assistant/build` (AI assistant), `clipboard` (steps shared between Studio windows, `.ooa/shared-clipboard.json`), `triggers`, `triggers/{id}/run`, `hooks/{id}` (local test triggers) |

Run limits: at most four background runs at once (`STUDIO.TOO_MANY_RUNS`); a run may take an hour, a debug run a
day. Finished runs are forgotten after 10 minutes. Every path goes through `Workspace.resolve`, so nothing outside My
Workspace and nothing hidden is reachable.

### 7.2 Studio UI building blocks (`studio.js`)

| Piece | Role |
| --- | --- |
| `GROUP_LIST`, `groupOf()` | Action groups, one per technology under a section heading; `groupOf()` splits a toolkit that holds several technologies |
| `state.multi`, `state.anchorId` | The canvas selection; Shift extends a range from the anchor step |
| `openMenu()` | Keyboard-accessible pop-up menus (step menu, Explorer menu) |
| `renderRunWindow()` | The floating Run window; the Run dock shows the same run |
| `windowEditor()` | Edits a step's `window` input; still works when `GET windows` is not available |
| `captureEditor()` | Edits a captured object (see [07-toolkit-architecture.md](07-toolkit-architecture.md#14-captured-objects-and-window-choice)) |
| `StudioDebugger` (Java) | Pauses the runner thread before a step and resumes it on the UI's commands |

### 7.3 The new Studio (`platform/ui`)

The redesign ([12-ui-redesign.md](12-ui-redesign.md)) is built in TypeScript and React next to the classic Studio and
served at `/next/` (`/studio/next/` under `ooa server`) until it replaces it.

| Task | Command (in `platform/ui`) |
| --- | --- |
| Install the locked packages | `npm ci` (Gradle's `:cli:uiInstall` does this) |
| Type-check, test, check licences and peers, build | `npm run check` (Gradle's `:cli:uiBuild`) |
| Tests only, or one file | `npm test`, `npx vitest run src/studio/App.test.tsx` |
| Security audit (needs the internet; CI runs it) | `npm run audit` |

Rules: packages are added with exact versions at least two weeks old, and only with a permissive licence if they
ship (`tools/check-licenses.mjs`). Screens call the server only through `src/api/client.ts`. Components live in
`src/components` (Radix for behaviour, our CSS for the look), colours and sizes in `src/theme.css`. The built files go
to `cli/build/generated/ui/studio-next` and into the jar; the npm packages that ship are listed in the SBOM.

## 8. Rules for every change

- Keep the module dependency direction. `ArchitectureTest` fails the build when it is broken (see
  [02-components-and-modules.md](02-components-and-modules.md#4-dependencies-between-modules)).
- **Secrets:** never take a secret as plain text (use Credential inputs or vault references), never log one, never
  return a stored secret to the browser.
- Every user-facing text uses the product names (agent, One Runner, Command Center), never "bot".
- Every action and input has a description; ids are snake case; secret inputs are `OoaType.CREDENTIAL`.
- No new library without need. A new one must have a permissive licence (Apache-2.0, MIT or similar) and, when heavy,
  goes into an optional module.
- Programs start with argument lists, never through a shell; XML is parsed only through `XmlSupport`; Command Center
  SQL uses `?` placeholders only.

### Interface languages

The console and Studio show their texts in English, German, French, Spanish and Japanese. After a change to visible
texts:

1. `python scripts/i18n-extract.py` (in `platform`) refreshes the list of texts,
   `control-room/src/main/resources/ooa-ui/ooa-i18n-keys.json`, from `cr.js`, `studio.js` and both `index.html` files.
   A text built from parts, such as `"Delete " + name + "?"`, becomes one key with a place: `Delete {}?`.
2. Translate the new texts and merge them: `python scripts/i18n-merge.py de translations.txt` (each line is the text's
   number in the key list, a tab, and the translation; every `{}` must stay).
3. `I18nCatalogsTest` fails when a catalogue holds a text that is no longer in the list, loses a `{}` place, or covers
   less than 85% of the texts.

Elements with the class `mono`, or the attribute `data-no-i18n`, are never translated (ids, paths, code).

## 9. Known limits

| Limit | Note |
| --- | --- |
| First-party toolkits share one class path | Custom toolkits each load in their own class loader, so versions live side by side; first-party toolkits do not |
| Manifests are built at run time | `PackageIntrospector` reads the annotations by reflection when a toolkit is installed; there is no compile-time annotation processor |
| SAP | SAP GUI scripting only; no BAPI or RFC calls (they would need the customer's SAP JCo library) |
| Screen runners | Agents that use the screen need a Windows runner; headless runners also run on Linux and macOS |
| Live systems | Cloud toolkits, single sign-on, external vaults and mail triggers are tested automatically against fake servers and simulators only; checks against real tenants are done by hand (`docs/REAL_SYSTEMS_TEST_PLAN.md`) |
| Security review | The independent penetration test is still open (see [06-security-architecture.md](06-security-architecture.md#14-independent-security-review)) |

## Related documents

- [README.md](README.md)
- [02-components-and-modules.md](02-components-and-modules.md)
- [03-agent-model-and-runner.md](03-agent-model-and-runner.md)
- [04-command-center-internals.md](04-command-center-internals.md)
- [05-data-model.md](05-data-model.md)
- [06-security-architecture.md](06-security-architecture.md)
- [07-toolkit-architecture.md](07-toolkit-architecture.md)
- [08-ai-architecture.md](08-ai-architecture.md)
- [10-extensibility-and-integration.md](10-extensibility-and-integration.md)
- [Toolkit reference](../reference/toolkits/README.md), [Command line reference](../reference/cli.md)
