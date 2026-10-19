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
| `packages-windows` *(optional)* | `ooa.window` window control, `ooa.dll` native calls | JNA |
| `adapters-jackson` | `JacksonJsonCodec`, `NetworkntFlowSchemaValidator` | Jackson, networknt |
| `control-room` | Control Room: users and roles, public repository with check-in/check-out and versions, devices, runs, schedules, triggers, credential lockers, global values, audit log, web console | H2 (embedded) or PostgreSQL |
| `agent` | Bot Agent: device registration, heartbeats, jobs, one worker process per run, file triggers | none |
| `cli` | `ooa manifest`, `validate`, `run`, `conformance`, `studio`, `control-room`, `agent register`, `agent start` | Logback |

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

Start the Studio (then open http://localhost:8710 in your browser):

```
.\gradlew.bat :cli:run --args="studio"
```

The Studio keeps your work in a **workspace folder outside the project**: by default
`C:\Users\<you>\OOA Workspace`. Updating or replacing the project never touches it. On first start it is filled
with an `Examples` folder (the refund bots), a `data` folder and an empty `My Bots` folder.
Use `--args="studio --workspace D:\Bots --port 8710"` to choose another folder. Press Ctrl+C to stop.

Studio features (design A): multi-select (Ctrl/Shift+click), cut, copy, paste, delete, undo/redo, a shared clipboard between bots, a right-click menu and keyboard shortcuts; resizable and collapsible panels; a condition builder for If, Else if, Wait and While; system ($sys), global ($global) and bot variables; live runs with step highlighting, pause, resume and stop; Capture and Pick for web elements; search across bots; icon rail with Explorer, Commands, Variables and Triggers; header buttons to collapse the left, right and run panels; four colour themes plus a dark theme and two densities (gear button); foldable If/Loop/Try blocks with Expand/Collapse all; foldable command groups and settings sections; a run dock that can sit at the bottom, on the right, float or shrink to a mini bar; a Record button for web actions; triggers (file arrives, schedule, local webhook) that run bots while Studio is open. Explorer with folders and sub-folders (create, rename, drag to move, delete to the workspace's
hidden `.trash` folder); command library with search, click-to-add and drag-and-drop; canvas with drag-to-reorder
and drop into If/Loop/Try blocks; command settings generated from package manifests; variables; live validation;
run console with outputs, errors and run log; JSON view.

AI commands write a governance record of every model call (no prompt text) to `ai-governance.jsonl` in the workspace. AI Skills are JSON prompt templates in the workspace's `ai-skills` folder. Work queues live in `queues/`, insights in `insights/`, triggers in `triggers.json`.

For local testing of Credential inputs, set `OOA_SECRET_LOCKER_CREDENTIAL_ATTRIBUTE` environment variables before
starting (e.g. `test/api/token` reads `OOA_SECRET_TEST_API_TOKEN`).

In IntelliJ IDEA: File > Open, select the `platform` folder, and trust the Gradle project.

The Studio preview is a developer workbench: it binds to 127.0.0.1 only, refuses cross-site requests and only
touches files in its workspace. The production Studio is the React app served by Control Center.

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

## Next slices

Remaining packages (Excel via Apache POI, Email, REST, Database, PDF, Browser via Playwright), AI Gateway and
agent runtime in Java, the AI developer assistant, Control Center (Spring Boot + PostgreSQL), Studio (React),
Bot Agent for Windows. PF4J classloader isolation and the compile-time manifest annotation processor replace
the runtime introspection used here.


Control Room and Bot Agent setup: see `../docs/CONTROL_ROOM_AND_AGENT.md`.
