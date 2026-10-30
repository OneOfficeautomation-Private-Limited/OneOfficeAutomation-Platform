# ONEOFFICEAUTOMATION platform (Java 21)

The production platform, following the Technical Architecture: Java 21, Gradle multi-project, modules as in
architecture section 15. This first slice contains the Package SDK, Runner, first-party packages, the
Jackson/networknt adapters and the CLI. It passes the same conformance suite as the Python reference
(`../contracts/conformance`).

| Module | Contents | Third-party dependencies |
| --- | --- | --- |
| `sdk` | Annotations (`@OoaPackage`, `@Action`, `@Input`, `@Output`, `@Condition`, `@LoopIterator`), manifests, Ed25519 signing, registry, typed errors, `SecureString` | none |
| `runner` | Expression language, Flow validator, interpreter, events, vault and repository interfaces, `core` package | none |
| `packages` | 47 packages, including generative AI, AI Skills, AI agents, work queues, insights, TN3270 terminal and SAP GUI, plus: core, data (string, number, datetime, boolean, list, dictionary, datatable, json, xml, csv), files (file incl. zip, folder, log), integration (rest, soap, database, network), cloud (microsoft365, google, salesforce, servicenow, jira, slack, workday), system (system incl. power, services, printer, application, script, run_context, global_values, sound), security (crypto incl. AES, pgp, active_directory), desktop (desktop, image, ocr, excel_desktop) | none (H2 for tests; Tesseract, GnuPG, Excel used when installed) |
| `packages-office` *(optional)* | `ooa.excel`, `ooa.word` without Microsoft Office | Apache POI |
| `packages-email` *(optional)* | `ooa.email`: SMTP and IMAP with OAuth | Eclipse Angus Mail |
| `packages-pdf` *(optional)* | `ooa.pdf` | Apache PDFBox |
| `packages-browser` *(optional)* | `ooa.browser`: web automation and the web recorder | Playwright |
| `packages-transfer` *(optional)* | `ooa.file_transfer`: FTP, FTPS, SFTP | Commons Net, JSch |
| `packages-windows` *(optional)* | `ooa.window` window control, `ooa.dll` native calls, `ooa.credential_manager` Windows Credential Manager | JNA |
| `adapters-jackson` | `JacksonJsonCodec`, `NetworkntFlowSchemaValidator` | Jackson, networknt |
| `control-room` | Control Room: users and roles, public repository with check-in/check-out and versions, devices, runs, schedules, triggers, credential lockers, global values, audit log, web console | H2 (embedded) or PostgreSQL |
| `agent` | Bot Agent: device registration, heartbeats, jobs, one worker process per run, file triggers | none |
| `cli` | The `ooa` command line: `ooa --help` lists every command, `ooa COMMAND --help` its options; also a native executable | picocli, Logback |

**Optional modules** are listed in `gradle.properties` (`ooa.optionalModules`). If one fails to build on your machine,
remove it from that line: everything else keeps working and you can keep testing the other commands.

See `../docs/COMMAND_CATALOG.md` for every Automation Anywhere package mapped to its OOA equivalent and status.

## Build and test on Windows (PowerShell)

Prerequisite: Eclipse Temurin JDK 21 with `JAVA_HOME` set. Gradle downloads itself on first run.

```
cd C:\ooa\oneofficeautomation\platform
.\gradlew.bat test
```

Run the CLI:

```
.\gradlew.bat :cli:run --args="conformance ../contracts/conformance/cases"
.\gradlew.bat :cli:run --args="manifest --summary"
.\gradlew.bat :cli:run --args="validate ../examples/refund/bots/issue_refund.flow.json"
```

### The command line

picocli reads the command line: `ooa --help` lists the commands and `ooa db configure --help` (any command) shows
its options. An unknown or misspelt option, a missing required one or a port that is not a number is refused before
anything runs, with the help shown (exit code 1). Options take `--name value` or `--name=value`; given twice, the last
one counts. `source <(ooa completion)` adds Tab completion to bash and zsh. The commands are declared in
`cli/.../CoreCommands.java` and `FullCommands.java`.

### Native executable

`.\gradlew.bat :cli:nativeExecutable` builds `cli\build\native\ooa.exe` (or `ooa` on Linux and macOS) with GraalVM
for JDK 21 (`GRAALVM_HOME`, or `JAVA_HOME` pointing at GraalVM; on Windows run it from a Visual Studio developer
prompt). It starts at once and needs no Java runtime, for build pipelines and quick checks, and has the commands that
need no Command Center: `validate`, `run`, `manifest`, `conformance`, `license show` and `release verify`. It knows
every toolkit's commands, so `validate` checks any automation, but `run` runs only the built-in toolkits: an Office,
email, PDF, browser or Windows desktop step stops with `ACTION.NOT_IN_THIS_PROGRAM`; run those with the full install
(`bin\ooa.bat`) or on a runner. CI builds it on Linux and Windows, checks that it gives the Java program's answers,
and keeps both as build artifacts for 14 days.

### One step: everything on one address

Double-click `scripts\start-ooa.bat` (or run `.\gradlew.bat :cli:run --args="server"`). One window starts:

| Address | What |
| --- | --- |
| http://localhost:8720/ | Control Room: repository, Activity, devices, schedules, triggers, credentials, users, audit log |
| http://localhost:8720/studio/ | Studio: your private workspace and the bot designer (answers only browsers on this computer) |
| (background) | A Bot Agent for this computer. It registers itself the first time, so bots can run here straight away |

The browser opens by itself. Close the window (or press Ctrl+C) to stop everything. On the very first start the window
shows a one-time `admin` password for Control Room. Studio uses your Control Room sign-in in the same browser for check-in
and check-out, so you sign in once. Options: `--port 8730`, `--workspace D:\Bots`, `--data D:\OOA\cr`, `--no-agent`,
`--no-browser` (for example `start-ooa.bat --port 8730`). `powershell -ExecutionPolicy Bypass -File scripts\create-desktop-shortcut.ps1`
puts a shortcut on your desktop. The local agent keeps its registration in `%USERPROFILE%\.ooa-agent\local-agent.json`,
separate from an agent registered by hand.

The separate commands below still work, for example to run Control Room on a server and Studio on a laptop.

Start the Studio on its own (then open http://localhost:8710 in your browser):

```
.\gradlew.bat :cli:run --args="studio"
```

The Studio keeps your work in a **workspace folder outside the project**: by default
`C:\Users\<you>\OOA Workspace`. Updating or replacing the project never touches it. On first start it is filled
with an `Examples` folder (the refund bots), a `data` folder and an empty `My Bots` folder.
Use `--args="studio --workspace D:\Bots --port 8710"` to choose another folder. Press Ctrl+C to stop.

Studio features (design A): multi-select (Ctrl/Shift+click), cut, copy, paste, delete, undo/redo, a shared clipboard between bots, a right-click menu and keyboard shortcuts; resizable and collapsible panels; a condition builder for If, Else if, Wait and While that reads the condition back as a sentence and can Test it with sample values; system ($sys), global ($global) and bot variables; live runs with step highlighting, pause, resume and stop; Capture and Pick for web elements; search across bots; icon rail with Explorer, Commands, Variables and Triggers; header buttons to collapse the left, right and run panels; four colour themes plus a dark theme and two densities (gear button); foldable If/Loop/Try blocks with Expand/Collapse all; foldable command groups and settings sections; a run dock that can sit at the bottom, on the right, float or shrink to a mini bar; a Record button for web actions; triggers (file arrives, schedule, local webhook) that run bots while Studio is open. Explorer with folders and sub-folders (create, rename, drag to move, delete to the workspace's
hidden `.trash` folder); command library with search, click-to-add and drag-and-drop; canvas with drag-to-reorder
and drop into If/Loop/Try blocks; command settings generated from package manifests; variables; live validation;
run console with outputs, errors and run log; JSON view.

Studio (Part 3): an **Automation** home lists everything in the workspace with type filters, search and sorting, and a
**+ Create** menu makes Task Bots, API Tasks, bots from templates, **Forms** (`*.form.json`, form designer with preview),
**Processes** (`*.process.json`, stage designer: bot task, human task, approval, decision, notification; they run on One CommandCenter,
with human tasks in its Task inbox), **Document automations** (`*.doc.json`, fields to extract; generates an AI extraction bot), **AI Skills**
(`ai-skills/*.json`, prompt editor) and **scripts** (Python, PowerShell, VBScript, JavaScript; code editor with Run).
Commands, canvas steps and If/Loop/Try blocks are colour-coded by category. The **Variables** pane groups inputs, outputs
and working variables; every type (including Table, List, Dictionary, Record, DateTime, File, Window, UIElement and Credential)
has its own editor for default values and run inputs, renaming a variable updates every use, and each command field has a
`{x}` picker (also Ctrl+Space or typing `$`) that lists matching variables first. A bot's **Dependencies** tab shows the child
bots, scripts, AI Skills and files it uses, and which bots use it.

AI commands write a governance record of every model call (no prompt text) to `ai-governance.jsonl` in the workspace. AI Skills are JSON prompt templates in the workspace's `ai-skills` folder. Work queues live in `queues/`, insights in `insights/`, triggers in `triggers.json`.

For local testing of Credential inputs, set `OOA_SECRET_LOCKER_CREDENTIAL_ATTRIBUTE` environment variables before
starting (e.g. `test/api/token` reads `OOA_SECRET_TEST_API_TOKEN`).

In IntelliJ IDEA: File > Open, select the `platform` folder, and trust the Gradle project.

The Studio preview is a developer workbench: it binds to 127.0.0.1 only, refuses cross-site requests and only
touches files in its workspace. `ooa server` serves the same Studio from One CommandCenter, at `/studio/`.

## Writing a package

```java
@OoaPackage(id = "acme.crm", name = "CRM", version = "1.0.0", description = "Read and update CRM records.")
public final class CrmPackage {
    @Action(id = "get_customer", name = "Get customer", description = "Find a customer by id.", capabilities = "network")
    @Output(name = "customer", type = OoaType.DICTIONARY, description = "Customer record.")
    public Map<String, Object> getCustomer(ActionContext ctx,
            @Input(name = "customer_id", type = OoaType.STRING, description = "Customer id.") String id) {
        ...
        return Map.of("customer", record);
    }
}
```

Every action and input needs a description: Studio renders command panels from them and the AI assistant and
agents read them.

## What is in the platform now

- **One AgentStudio**: agents, headless API agents, forms, workflows, document intelligence, Prompt Skills and scripts;
  debugger with breakpoints, Quick add, flow view, AI assistant, Model Connections and custom connectors.
- **Toolkits**: core logic and data, files, Office (Excel, Word, PowerPoint), Microsoft 365, Google Workspace, email,
  PDF, browser, Windows, databases, REST/SOAP and custom connectors (OpenAPI, Swagger, Postman), and enterprise apps
  (Salesforce, ServiceNow, Jira, Slack, Workday, SAP GUI, Active Directory, terminal, GitHub, Azure DevOps, Asana,
  Zendesk, Okta, Box, DocuSign, Snowflake, Databricks, Kafka), plus generative AI, AI agents and Prompt Skills.
  See `../docs/COMMAND_CATALOG.md`.
- **One CommandCenter**: agent repository, Agent Runs, runners and runner pools, schedules with holiday calendars,
  triggers, work queues, Workflow with a Task inbox, Credential vault with external vaults, shared values, users,
  custom roles, API keys, single sign-on, AI governance, an MCP server for AI assistants, and the Audit Trail.

## Next slices

Production hardening: PF4J classloader isolation and a compile-time manifest processor instead of runtime
introspection, a push channel instead of polling for live views, signed custom toolkit uploads, Git sync for the agent
repository, SAP BAPI (needs the customer's SAP JCo), and macOS and Linux runners.

One CommandCenter and OneRunner setup: see `../docs/user-guide/00-README.md`. The multi-tenant service in
regions (`ooa region start`, `ooa directory start`): see `../docs/infrastructure/09-saas-and-multi-region-operations.md`.
