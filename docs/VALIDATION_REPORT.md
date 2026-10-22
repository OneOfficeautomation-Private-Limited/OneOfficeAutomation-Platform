# Validation report: Part 1 (Studio editing, conditions, variables, live runs, Capture, search)

Date: 19 October 2026. Scope: everything delivered in this version. Part 2 (Control Room, Bot Agent, public
repository with check-in/check-out) gets its own report.

## Automated results

| Suite | Result |
| --- | --- |
| Java unit and integration tests (SDK, Runner, packages, connectors, AI, OCR, PGP, queues, Studio) | 68 passed, 0 failed |
| Conformance cases shared with the Python reference | 15 of 15 passed |
| Browser tests against a real Studio server (`cli/src/test/ui/studio.ui.test.js`) | 96 passed, 0 failed |
| Architecture rules (`ArchitectureTest`, runs in every build) | 5 of 5 passed |

## Module-level checks (enforced by ArchitectureTest)

- The SDK depends on nothing else; the Runner knows no packages and no Studio code.
- Built-in packages never depend on Studio or on optional modules.
- Optional modules (Office, Email, PDF, Browser, File transfer, Windows) depend only on the SDK, Runner and built-in
  packages, never on each other; Studio loads them by name, so removing one from `gradle.properties` never breaks a build.
- Every command, condition and loop iterator has a display name, a description and documented inputs; ids are
  snake_case; first-party package ids start with `ooa.`; package ids are unique.

## Enterprise checks

- **Security:** no shell-based process execution anywhere (argument lists only); all XML through the XXE-hardened parser;
  packages never print to the console, so every log line passes through secret masking; Studio listens on 127.0.0.1 only
  and rejects cross-site requests; webhook secrets are compared in constant time; built-in variables are read-only and
  cannot be overwritten or used as output targets.
- **Data integrity:** bot, trigger and work-queue files are written atomically (temporary file, then swap); undo history
  is bounded (100 steps); paste always assigns new unique step ids.
- **Resource limits:** request bodies capped at 5 MB; at most four concurrent Studio runs; finished live runs expire after
  10 minutes; search is capped at 100 results and does not follow links.
- **Run control:** pause and stop also reach child bots and parallel branches; stopping a paused run ends it cleanly.

## Issues found during validation and fixed in this version

1. Trigger definitions were saved non-atomically: now atomic.
2. The Variables panel cached global values from its first opening: it now reloads every time.
3. The built-in variables endpoint returned the literal text "$sys": now evaluated through a real step.
4. Saving a newly created bot file was not atomic: now atomic.

## Known limitations (tracked)

- The Python reference implementation does not yet know `$sys` and `$global`; they are Java-only until it is updated, so no
  conformance case uses them.
- Optional modules (including the Capture picker, element actions and the web recorder) compile only on a machine with
  Maven access; they were written against stable library APIs but are first compiled and tested on your laptop.
- Desktop (Windows UI Automation), Java and SAP recording, and email/window/hotkey triggers need the Bot Agent (Part 2).

---

# Validation report: Part 2 (Control Room, Bot Agent, public repository)

## Automated results

| Suite | Result |
| --- | --- |
| All Java tests (Part 1 suites plus the new ones below) | 71 passed, 0 failed |
| Control Room + Bot Agent end to end (`ControlRoomE2ETest`: real HTTP server, real H2 database, real agent with worker processes) | passed |
| Studio with Control Room, two developers (`StudioControlRoomTest`) | passed |
| Control Room console in a browser engine (`control-room/src/test/ui/console.ui.test.js`, against `ooa control-room`) | 21 passed, 0 failed |
| Studio browser tests (now including the Control Room section) | 99 passed, 0 failed |
| Architecture rules (now 6, including Control Room and Agent boundaries and parameterised SQL) | 6 of 6 passed |
| Conformance cases | 15 of 15 passed |

## What the end-to-end tests prove

- **Accounts:** one-time admin password; forced change at first sign-in (nothing else works until then); password policy;
  lockout after five failures; disabled users and expired sessions refused.
- **Access control:** each API call checks a permission; viewers cannot check in; only admins set production versions,
  register devices or read the audit log; only locker owners change credentials.
- **Repository:** invalid bots are refused at check-in with the problem list; updates need a check-out lock; a second user is
  told who holds it; bot ids are unique; versions are kept; a checked-out copy round-trips to the same repository path.
- **Devices and runs:** single-use registration keys; device secrets stored only as hashes; runs claimed atomically; each bot
  runs in its own worker process; live events stored; stop reaches the worker; lost devices fail their runs after 2 minutes.
- **Vault:** AES-256-GCM bound to each credential's name; plain secrets refused as run inputs; values never returned by the
  API or shown in the console; a bot gets a value only while running and only if its requester is a locker consumer; the
  secret never appears in run data; every read is audited.
- **Automation:** schedules fire once per due time (guarded update); webhook secrets compared in constant time; file triggers
  fire on the agent's device and pass the file path.
- **Audit:** hash-chained records; editing one past record is detected by Verify integrity.
- **Web security:** HttpOnly SameSite=Strict session cookie; a custom header required on every change (CSRF); strict
  content-security policy (no inline scripts or styles); clickjacking blocked; all data rendered as text (HTML in data is
  shown, never run).

## Module-level checks

- Control Room depends on neither Studio nor the Bot Agent; the agent talks to Control Room only over HTTP; the Runner is the
  same in Studio, on agents and in Control Room validation (all enforced by ArchitectureTest).
- All SQL is constant text with placeholders; values are never concatenated into statements (enforced).
- Portable SQL: tested on H2 (PostgreSQL mode); PostgreSQL is selected with `--db-url`. During testing a reserved word
  (`value`) was found that H2 rejects and was renamed, which is exactly what the real-database test exists to catch.

## Issues found and fixed during Part 2 validation

1. Column name `value` is reserved in H2: renamed to `json_value`.
2. Studio returned 400 for Control Room conflicts: it now passes 409 (locked), 403 and 404 through.
3. Studio webhooks with a wrong secret now return 403 instead of 400.

## Known limitations (tracked)

- PostgreSQL is supported by configuration but was not tested in this environment (no PostgreSQL server here); run the
  end-to-end test against PostgreSQL before production.
- Work queues are still per device (workspace files); central Control Room queues with SLAs come next.
- Control Room serves plain HTTP; use an HTTPS reverse proxy (IIS, nginx) when other machines connect.
- The agent runs as a scheduled task in the signed-in user's session; unattended auto-login and a Windows service wrapper
  are part of the installer phase.
- Process Composer / Co-Pilot (forms and human tasks) and the Bot Store remain planned.

---

# Validation report: Part 3 (one address, Automation items, colourful designer, variables, full Activity)

## What was added

| Item | Result |
| --- | --- |
| One address and one-step start | `ooa server` (or `scripts\start-ooa.bat`, or a desktop shortcut) runs Control Room at `/`, Studio at `/studio/` and a self-registering Bot Agent in one window on one port, and opens the browser. Studio reuses the browser's Control Room sign-in. |
| Colourful designer | Commands grouped into 13 colour-coded categories with icons and a "Recently used" group; canvas steps carry their category colour and icon; If/Loop/Try are drawn as tinted blocks with coloured section labels. |
| Automation home and typed items | Home list with type filters, search and sort; + Create for Task Bot, API Task, bot from template (7 templates), Form, Process, Document automation, AI Skill and scripts (Python, PowerShell, VBScript, JavaScript), each with its own editor; a Dependencies tab on bots. |
| Variables | Grouped pane (inputs, outputs, inside the bot, system, global) with type badges and use counts; an editor with a value editor for every type (Table grid, List and Dictionary rows, DateTime picker, file suggestions, JSON); rename updates every `$name` and `${name}`; `{x}` picker on every command field. Run inputs use the same typed editors. |
| Control Room | Activity with live progress and the current step, history filters, paging, CSV export, Rerun, and a run details panel (error, timeline, filtered log, download, variables, inputs and outputs); dashboard with a 7-day chart, success rate, failures, most used bots and devices; bot, device and user details; version compare; Run now for schedules; device rename; search, sorting, paging and export on every list. |

## Automated results

| Suite | Result |
| --- | --- |
| All Java tests (Parts 1 and 2 plus the new `UnifiedServerTest`) | 81 passed, 0 failed |
| `UnifiedServerTest`: one server (redirect, relative assets, health link, shared sign-in, check-in through `/studio`), local agent registers once and runs bots, history filters, paging, rerun, schedule Run now, device rename, dashboard, command names; workspace item types, safe save, rename keeps the type, dependencies and "used by" | passed |
| Studio browser tests (now including Part 3: home, Create menu, form/process/document/skill/script editors, script run, every template validated, coloured blocks, dependencies, variable editor, picker, rename) | 122 passed, 0 failed (Record and Capture checks skipped where the optional Browser module is installed) |
| Control Room console in a browser engine (now including the dashboard chart and Activity tabs, filters and export) | 24 passed, 0 failed |
| Manual check in a real browser with `ooa server`: check-in from Studio through the shared sign-in, runs on the local agent, live In progress card, history, run details | passed |

## Issues found and fixed during Part 3 validation

1. `DatabasePackageTest` used a 20-character column for a 28-character test value: widened (test-only).
2. Optional parts passed to `replaceChildren` were printed as the text "null": both consoles now skip empty parts.
3. Switching Control Room sections quickly could let a slow page overwrite the newer one: each visit now renders into its own container, and late timers are not started.
4. Run inputs with allowed values used JSON-encoded option values: now plain values.
5. `${name}` interpolations were not counted or renamed with their variable: fixed.

## Known limitations (tracked)

- Processes are designed and saved, but running them needs the Assist portal (task inbox), which is still planned.
- Document automation generates an extraction bot for PDF and text files; scanned images need an OCR step first.
- The PGP test needs the Windows `gpg`; run the Java tests from PowerShell or cmd, not Git Bash.
- Activity refreshes by polling every 2 to 2.5 seconds; a push channel can replace it when Control Room moves to the production stack.

# Validation report: Part 4 (enterprise look, One-branded agentic naming, feature parity and beyond)

## What was added

| Item | Result |
| --- | --- |
| Enterprise look | Light and dark themes in One Agent Studio and One Command Center, remembered per browser. |
| One-branded agentic naming | Agents, Headless API agents, One Agent Studio, One Command Center, One Runner, Agent repository (Public / My Workspace), Agent check-in/check-out, Live production version, Agent Runs (Live/History), One Agent Vault, Shared values, One Workflow, One Document Intelligence, One Prompt Skill, One Audit Trail; roles Administrator, Agent builder, Agent operator, Viewer. |
| Studio editor | Debugger (breakpoints also in child agents, step into/over/out, call stack, variable edits), Quick add, flow view, AI assistant that builds and explains agents, One Model Connections, custom connectors from OpenAPI/Swagger/Postman, Publish to Command Center for workflows and forms. |
| Toolkits | From 328 to 875 actions in 72 toolkits. New: PowerPoint, GitHub, Azure DevOps, Asana, Zendesk, Okta, Box, DocuSign, Snowflake, Databricks, Kafka, custom connectors, fuzzy match, currency, trigger loop, Windows Credential Manager, interrupts; large additions to Excel, Word, Microsoft 365, Google Workspace, email, data, system, database, REST, FTP, PDF, browser, window, SAP GUI, Active Directory, terminal, Jira, Slack, Salesforce, Workday and ServiceNow; Hugging Face and SageMaker AI providers. |
| One Command Center | Work queues (SLA, priority, retries, CSV/JSON import), runner pools, run priority and result callbacks, holiday calendars and schedule ownership, custom roles, API keys, settings and announcement, toolkit manager with "used by", hours saved, external vaults (HashiCorp, Azure Key Vault, AWS Secrets Manager, CyberArk), AI governance, MCP server for AI assistants, single sign-on (OpenID Connect), One Workflow runtime with a Task inbox. |
| Triggers and resilience | Runner triggers (file/folder changes, window, process, service, hotkey) and server triggers (IMAP, Outlook, ServiceNow, universal poller) with pause/resume and monitoring; interrupt rules and self-healing locators for UI agents. |

## Automated results

| Suite | Result |
| --- | --- |
| All Java tests (PowerShell, `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`) | 314 tests, 0 failed, 1 skipped (a Linux/macOS-only Credential Manager case) |
| New end-to-end tests | `EnterpriseFeaturesTest` (external vault read at run time, AI governance, MCP initialize/list/call, OpenID Connect sign-in with PKCE and forged-token refusal, workflow with form, decision, approval, agent and notification), `PlatformTest` (API keys, custom roles, settings, pools, callbacks, queues with retries, calendars, toolkits), `DebuggerTest`, trigger API/poller/runner tests, UI resilience tests, a gap test class per toolkit |
| Studio browser tests | 131 passed, 0 failed |
| Command Center console in a browser engine | 58 passed, 0 failed (queues, pools, calendars, administration, roles, model connections, toolkits, API keys, task inbox, One Workflow, AI governance, external vault dialog, trigger kinds, announcement for other users) |

## Issues found and fixed during Part 4 validation

1. The naming conversion had turned `Rbac.allowed` into a call to itself; saving a custom role crashed the request. Fixed, and the server now answers every failure (including errors) with a 500 instead of dropping the connection.
2. A schedule on a runner pool could not be saved (runner column was required). Schema V2 relaxes it; Run now uses the pool.
3. Queue item paging concatenated numbers into SQL, which the architecture rule rejects: now placeholders.
4. Workday SOAP replies were parsed without the hardened XML parser: now through `XmlSupport`.
5. The AI agent's approval dialog could wait forever on an unattended desktop: it now closes after `ooa.approval.timeoutSeconds` (default 300) and counts as "no".
6. The toolkit list hid most toolkits behind paging after the toolkit count grew: 100 per page.
7. Merge conflicts between parallel toolkit branches (shared HTTP helper, fake test server, test helpers, toolkit registry, window toolkit) were resolved by combining both sides.

## Known limitations (tracked)

- Cloud toolkits (Microsoft 365, Google, GitHub, Azure DevOps, Asana, Zendesk, Okta, Box, DocuSign, Snowflake, Databricks, Kafka, ServiceNow, Workday) and the IMAP/Outlook/ServiceNow triggers are tested against local simulators, not live tenants. SAP GUI and x3270 actions are checked by the scripts they generate.
- SSO and external vaults are tested against simulated providers; check them once against your identity provider and vault.
- Workflows run agent tasks on any online runner (or a pool per stage); e-mail recipients in notification stages need an agent task with the Email toolkit.
- Desktop interrupt clicks use screen coordinates (no UI Automation element model yet); window and hotkey triggers need a signed-in desktop session.
- SAP BAPI needs the customer's SAP JCo library and is not included.
- The PGP tests need a native Windows gpg (Gpg4win); run the Java tests from PowerShell or cmd.


# Validation report: Part 5 (advanced Capture and action-by-action property parity)

## What was added

| Item | Result |
| --- | --- |
| Web Capture | Capture records a captured object: every useful property (tag, id, name, class, type, text, label, ARIA label, placeholder, title, role, link and image targets, `data-*` attributes, value), CSS, relative and absolute XPath, iframe path, page, position and a screenshot. Studio shows it as an editable table: Use, Value (may contain `$variables`), Match (equals, not equals, contains, starts with, ends with, wildcard, regex), search by properties/CSS/XPath, index, ignore case, self-heal, page match, Recapture and Test (outlines every match in a real browser). |
| Element actions | `ooa.browser/element_action` takes the captured object (or a selector): mouse or JavaScript clicks with modifiers and position, middle click, fill or key-by-key typing with delay, clear or append, force, wait after, switch to the captured page; new actions to read all properties, HTML, position, a table or child items, wait to appear/disappear, highlight, drag, upload files and take an element screenshot. Design-time `test_object`. |
| Desktop Capture | New `ooa.desktop_ui` toolkit (Windows UI Automation): Capture desktop (hover, Ctrl+Shift+C) records Name, AutomationId, ClassName, ControlType and more with match rules, window title/process and index; actions click, type or set text, read properties, select, expand/collapse, toggle, check, invoke, list items, read a grid as a table, wait, coordinates; self-healing across recorded properties. |
| Property parity with AA (public documentation, own wording) | Five audits compared each action's properties with AA's and added the missing ones as optional inputs (defaults keep existing agents unchanged): data toolkits and core (If, Loop, error handler, child agents), email/Microsoft 365/Google/Slack, system/files/desktop/FTP/window/DLL, enterprise apps and AI, Office/PDF/OCR/image. About 940 new options and 63 new actions; 945 actions in 73 toolkits. Comparison tables: `docs/research/property-gaps/`. |

## Automated results

| Suite | Result |
| --- | --- |
| All Java tests (PowerShell, headless) | 452 tests, 0 failed, 1 skipped |
| Capture in a real browser (`CaptureObjectTest`) | match rules, index, iframes, CSS/XPath search, self-healing, JavaScript click on a covered element, key-by-key typing, tables, upload, element screenshot |
| Desktop Capture (`DesktopUiLiveTest`) | real Windows Character Map: find by ControlType, set and read text, capture and find again |
| Studio browser tests | 135 passed (new: captured-object editor) |
| Command Center console | 58 passed |

## Known limitations (tracked)

- Rows in the comparison tables marked as based on package overviews could not be read in full from AA's public pages; spot-check them.
- Not tested against the real products here: Tesseract OCR, Excel desktop, SAP GUI, mainframe terminals, live cloud tenants (simulated services are used).
- Desktop Capture cannot see inside Java Swing, SAP GUI, Citrix/RDP sessions or canvas-drawn apps (UI Automation limits); the Ctrl+Shift+C capture key also reaches the app under the pointer.
- Left out on purpose (reasons in the tables): EWS, .msg export, password-protected zip, NTLM, SNMP v3, ODBC, MP3, terminal types other than 3270, weak PDF encryption.


# Validation report: Part 6 (One Agent Studio usability)

## What was added

| Item | Result |
| --- | --- |
| Action groups | One group per technology under section headings (Flow and control, Data and variables, Data formats, Files and folders, Office documents, Web and desktop, APIs and databases, Email and collaboration, Business applications, AI, Scripts, Operations, System and security). Excel, Excel desktop, Word, PowerPoint and PDF; XML, JSON and CSV/Text; REST, SOAP and Database; Email, Microsoft 365 and Google Workspace; Browser, Capture/Desktop objects, Window and Mouse and keyboard are separate. The Scripts toolkit is split into Python, JavaScript, VBScript and VBA, and PowerShell by action id. `ooa.terminal` is the **Terminal emulator** group. Toolkits without a listed group get their own group. Search covers group names too. |
| Canvas multi-select | Click, Ctrl+click (add or remove), Shift+click (range from the last clicked step, same list), Ctrl+A (canvas focus), Esc. "N steps selected" in the toolbar. Copy, Cut, Paste (after the last selected step), Delete, Duplicate, Disable/Enable, Move up/down and Copy to shared act on the whole selection from the toolbar, keys (Ctrl+C/X/V/D, Del, Ctrl+/, Alt+Up/Down) and menus. |
| Step menu | A ⋮ button on every step card (list and flow view), right-click, Shift+F10 or the menu key. Run from here, Run this step only, Copy, Cut, Paste after, Duplicate, Delete, Disable/Enable, Toggle breakpoint, Move up/down, Add note, shared clipboard. Arrow keys, Home/End, Esc (focus returns), Tab and outside clicks. |
| Run from here | `Runner.RunOptions.startAt` / `onlyStep`; `POST runs` accepts `start_at` and `only_step`. Blocks around the start step are entered without evaluating them (documented in ARCHITECTURE 5.3). |
| Layout | The workbench row gives the side panels and the canvas minimum widths; side panels shrink first, the canvas clips instead of covering a neighbour, and the workbench scrolls sideways when even the minimums do not fit. Measured in a real browser: 1280 px (side 52-340, canvas 341-939, settings 940-1280), 1024 px (52-340, 341-683, 684-1024), 800 px (52-257, 258-558, 559-800); with the dock on the right at 800 px the workbench scrolls instead of overlapping. |
| Density | Compact (new default) and Comfortable in Theme and density. Compact cards: smaller padding, icon tiles, numbers and fonts. |
| Close | ✕ next to the open item's name (and Ctrl+W where the browser lets a page use it): asks Save / Don't save / Cancel when there are unsaved changes, then returns to the Automation home. Home does the same. |
| Action settings | Tighter spacing and smaller labels; help text behind an ⓘ (hover or keyboard focus); inputs that are not required, have a default and come after the first three sit under a closed "More options" (opened automatically when one of them is set). |
| Run window | A floating, draggable, minimisable window during Studio runs: agent name, current step number, action and note, elapsed time, progress, Pause/Resume and Stop, Continue/Step/Step into while debugging. The running step is highlighted and scrolled into view. Position and minimised state are remembered. |
| Explorer | Right-click or ⋮: new folder, agent, form, workflow, Python or PowerShell script here; Rename, Copy, Cut, Paste, Duplicate, Delete (confirmed, to the trash); Move to…, Copy path, Open in File Explorer; Upload file(s), Download (folders as .zip). Ctrl/Shift multi-select; dragging a selection moves all of it. New endpoints `files/copy`, `files/duplicate`, `files/upload`, `files/download`, `files/reveal`, all through `Workspace.resolve`. |
| Window editor | A web action input named `window` (Dictionary) gets a compact editor: Browser / Application / Variable, title with wildcard/contains/equals/regex, a pick list from `GET windows` (works without it), address and browser for Browser, a variable for Variable. |

## Automated results

| Suite | Result |
| --- | --- |
| `.\gradlew.bat :runner:test :cli:test` (PowerShell, headless) | runner 39 tests, cli 21 tests, 0 failed (new: `RunFromStepTest` 8, `StudioFilesTest` 2; `ArchitectureTest` passes with the File Explorer launcher) |
| Studio browser tests (jsdom) | 205 passed, 0 failed (new: groups, multi-select, step menu, Run from here, Close, More options, Run window, Explorer menu and file operations, Window editor, layout rules at 1280/1024/800 px) |

## Known limitations

- Ctrl+W is reserved by most browsers for closing the tab, so the ✕ button is the dependable way to close an item.
- jsdom has no layout engine: the UI test reads the CSS rules and checks the flex arithmetic; the real pixel positions above were measured in a browser.
- The `GET windows` endpoint and the toolkit side of the `window` option belong to another change; until they land, the pick list says that open windows are not available.
- Run from here does not replay skipped steps, so variables those steps would set keep their defaults.

## Part 6 additions merged with it

| Item | Result |
| --- | --- |
| Command Center menu | Home plus three collapsible groups: Build, Run & Monitor, Govern; Settings, Roles, Toolkits and One Model Connections are their own pages |
| Window choice for web actions | `window` input on every element action: Browser (tab by title/address, wildcards and variables), Application (browser window by desktop title), Variable (session); `open_for_automation`, `attach`, `list_windows`; Studio `GET windows` and the window editor |
| Final results (merged) | Java 465 tests, 0 failed (1 skipped); Studio UI 205; console UI 61; checked by eye in a real Studio and Command Center |


# Validation report: Part 7 (web Capture properties, anchor, recorder objects)

The Capture action of the reference product was inspected again on a sample page (the-internet.herokuapp.com/login in
Edge), using a test agent that was never saved. It shows a preview, Main and Anchor tabs, the actions for the
element, and a Name / Value property sheet: the ticked properties first ("11 of 50"), and all 50 when expanded (HTML
tag, DOMXPath, Path, CSS selector, HTML ID, name, class, type, inner text, inner and outer HTML, frame source, has frame,
tag index, source index, position and size, visible and others). Values are text with `*` wildcards. Its system
time-out has a Basic and an Advanced view (wait for the browser, wait for the object, and "stop with an error" or
"skip and carry on" when the time runs out). The same ideas are implemented here in our own words and code.

## What was added

| Item | Result |
| --- | --- |
| Property set | One shared reader (`WebObjects.LIB_JS`) for Capture, the Recorder, the search and `get_all_properties`: attributes, inner text, value, field label, DOM XPath, full XPath, CSS selector, element path, parent, tag and source index, child count, left/top/width/height, visible, enabled, checked, selected item, inside a frame, frame address, inner and outer HTML (about 40 per element). |
| Search by a selector property | A ticked CSS selector or XPath with `equals` matches the element the selector finds; Search by XPath tries the DOM XPath first, then the full XPath. Self-heal tries the CSS selector, then the XPaths. |
| Anchor | `anchor` with an offset: picks the right element when several match, and finds it next to the anchor when its own properties no longer match (logged). Studio: Anchor tab with Capture anchor, Recapture, Remove, and the anchor's own property table. |
| Time-out options | `element_action`: `wait_for_page_ms` and `if_not_found` (error / continue, with output `found`). |
| Recorder | Every recorded click, typing and selection carries the full captured object (with iframe path); selections use the option's visible text; typed text is not kept as a property. |
| Studio editor | Preview, Main / Anchor tabs, "Object properties: N ticked of M" with All / Ticked, a search box, readable names (stored name on hover), Name / Value / Match on one line in a wide panel and the value under the name in a narrow one. |

## Automated results

| Suite | Result |
| --- | --- |
| `CaptureObjectTest`, `BrowserWindowTest` (Edge, headless) | 5 tests, 0 failed. New checks: DOM XPath, CSS, path, parent and label read back; ticked DOM XPath and CSS rules; XPath order; parent and tag index rules; anchor choosing between two matches and finding a renamed element; `if_not_found` continue and error |
| Studio browser tests (jsdom) | 209 passed, 0 failed (new: readable names and ticked count, Ticked view, Anchor tab, anchor offset and properties) |
| Checked by eye | The new editor in a real Studio at 800 px and 1500 px wide |

## Desktop Capture (Windows applications)

The same check was made for a Windows application (Notepad, test agent never saved). The reference product lists
the window by title (text or pattern, wildcards, case-sensitive) with its program path and an optional resize to a
width and height, offers presets (active window, desktop, taskbar), and captures with automatic detection, Active
Accessibility or UI Automation. A Notepad text box showed 27 properties with Active Accessibility and 52 with UI
Automation (Path and XPath ticked by default; index, parent, position, states, role, class, text attributes and
more), the actions Get property, Set text, Append text and the clicks, and the same Main / Anchor tabs and time-out.

| Item | Result |
| --- | --- |
| Properties | Text, Path, XPath, Index, Parent, Left/Top/Width/Height (relative to the window), States, access and shortcut keys, item type and status, focus, password, read-only, checked, selected, expanded, range value, patterns and process, besides the UI Automation identity properties |
| Search | Any of these can be ticked; the helper reads the details only when a ticked property needs them |
| Window | Program path and size recorded; Resize window (width, height) applied before the search |
| Anchor | Desktop Anchor tab; nearest control of the same type to anchor + offset (screen rectangles) |
| Skip | `object_action` `if_not_found` = continue, output `found` |
| Studio | Readable desktop names, Main / Anchor tabs, window section with program and resize |

| Suite | Result |
| --- | --- |
| `:packages-windows:test` (live, Character Map) | 26 tests, 0 failed, 1 skipped (unrelated). New live checks: Path, XPath, Index, Parent, Text, States, position, program path; search by Path + XPath only; anchor fallback (`healed_by` = anchor); continue when not found. New unit test: details needed, anchor and resize parsing, nearest control |
| Studio browser tests (jsdom) | 212 passed, 0 failed (new: desktop names, program path, Resize window, desktop Anchor tab) |

## Known limitations

- Active Accessibility (MSAA) capture is not offered: UI Automation reads the same controls on current Windows.
- Text formatting attributes (font, colours) are not recorded.
- The anchor offset assumes the page layout is about the same as at capture; on a very different layout use more
  properties or the index instead.
- The Recorder does not take element screenshots (Capture does).


# Validation report: Part 7 fixes (reported after trying it)

| Reported | Cause | Fix | Checked |
| --- | --- | --- | --- |
| Edge closes at the end of a run without a Close step | The run ends every session, and a browser Playwright started dies with it | `open` (`keep_open`, default true, Edge and Chrome with a window) starts the browser with a debugging port (Edge 9222, Chrome 9223, own profile under `.ooa/browser-profiles`) or opens a new window in the one already running, and works through DevTools; the run ending only disconnects. Close browser closes the windows that session opened | Real Edge: the window stays after a run without Close; a run with Close leaves no window |
| Window title and address not captured | Capture opened a new browser and filled only the object; the step's window stayed empty and sat under the captured object | The window comes first on web actions; Capture fills it as the reference product does: browser title (equals), link of the web page (shown, not matched), browser. Capture works inside an open window: the step's window when it is open, else a list of open browser windows (browsers agents opened stay open and are listed), else a new page | Real Edge: capture in the open checkboxes tab returned the element with the page title and address; the tab stayed open and works normally afterwards. Studio UI: window first, title/link/browser filled |
| No variables made by the recorder | Recorded values were written into the steps | Each typed value and selection becomes an input variable named after the field (label, name, id), with the recorded value as its default; the step uses `$name`. Passwords stay a Credential variable | Studio UI |
| Dragging several selected steps moved only one | The drop handled only the dragged step | Dragging one of the selected steps moves all of them, in order, to the drop point (blocks with their contents) | Studio UI |

Results: packages-browser 7 tests, cli 21, runner 39, 0 failed; Studio UI 216 passed.


# Validation report: Browser Connector

Built to match how the reference product drives the person's own browser (extension + native-messaging host),
in our own design and code. Details: docs/BROWSER_CONNECTOR.md.

| Check (real Edge with the extension, through a test Studio) | Result |
| --- | --- |
| Open a window through the connector, set text and read it back, select by text, check a box, count matches, page title, Run JavaScript, screenshot | pass |
| A later agent finds the window by title and address; the window stayed open with its text | pass |
| if_not_found continue through the connector | pass |
| Studio's window list shows the connector's tabs | pass |
| Capture in an open tab: element, all properties, page title and address, preview image | pass |
| Close browser closes the tab | pass |
| Record in an open tab: typing, click and selection become three steps with full captured objects and their window; the page's Stop recording ends it | pass |
| Native host unit test: framing, registration, token check, request/response routing, events, clean exit | pass |
| Java suites | 467 tests, 0 failed, 1 skipped |
| Studio browser tests | 216 passed |

Fixed while testing: the worker connected twice (one host too many); a screenshot of a window hidden behind other
windows never finished (now brought forward, with a time limit and a fallback through the browser's own renderer);
several matching tabs now prefer the one in front.
