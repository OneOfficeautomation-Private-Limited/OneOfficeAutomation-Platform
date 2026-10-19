# Action gap analysis: Automation 360 Community Edition vs ONEOFFICEAUTOMATION

Source: the AA Community Edition bot editor's Actions and Triggers panels, read on 19 Oct 2026
(`aa-ce-actions-2026-10-19.json`: 105 relevant packages, 921 actions, 10 trigger types), compared with our action
manifests (`ooa manifest`). This compares **action names** only; the settings inside each action are a separate pass.

"Covered" means one of our actions does the same job even if the name differs (for example AA String > Uppercase is our
Change case). "Expression" means our expression language already does it (for example `${len($text)}`), but there is no
separate action for it yet.

## 1. AA packages we do not have (26)

| Area | AA package (actions) | Recommendation |
| --- | --- | --- |
| Productivity apps | PowerPoint (14) | **Build**: open, slides, text, pictures, tables, save as (Apache POI, like Word) |
| Collaboration and dev | GitHub (14), Azure DevOps (27), Asana (30), Zendesk (20), Okta (13), Box (13), DocuSign (18) | **Build** as REST toolkits (same pattern as Jira and ServiceNow) |
| Data platforms | Snowflake (10), Databricks (13), Kafka (5) | **Build**: Snowflake via JDBC, Databricks REST, Kafka client |
| SAP | SAP BAPI (10) | **Build** (needs the customer's SAP JCo library at run time) |
| Microsoft | SharePoint (3: auth, folder and file, list items) | **Build** list items; files already work through Microsoft 365 |
| AI | AI (SageMaker, Hugging Face), Google Document AI (3), ML anomaly detection (2), Aisera (2) | **Build** Hugging Face and SageMaker providers; Document AI as a One Document Intelligence engine |
| Attended | Interactive forms (17: display, show/hide, get/set, validate, dynamic area) | **Build**: runs our Studio forms on the desktop during an attended run |
| Process | Process Composer (10: human tasks, requests, storage files) | **Build** with the Workflow runtime (Assist / task inbox) |
| Utilities | Fuzzy Match (1), Currency Conversion (2), Trigger loop (3), Credential Manager (Windows, 1) | **Build** fuzzy match, trigger loop and Windows Credential Manager; currency via REST blueprint |

## 2. Packages we have, with missing actions

| AA package | AA | Ours | Missing actions |
| --- | --- | --- | --- |
| Excel advanced | 59 | 17 | Append workbook, append worksheet, delete cells, delete table column, insert table column, workbook links (get, update, break), screen updating on/off, filter, find next empty cell, go to next empty cell, get cell colour, current sheet name, number of rows, cell address, column name, row number, table range, go to cell, hide/unhide rows, columns and sheets (+ unhide all), insert/delete rows and columns, protect/unprotect workbook, password-protect sheet, open protected sheet, read cell formula, read column, read row, remove blank rows, rename sheet, replace, sheet count, save as, select range, sort, switch to sheet, sensitivity labels (get, set) |
| Excel basic | 14 | (same package) | Delete cells, cell address, column name, row number, go to cell, replace, switch to sheet |
| Microsoft 365 Excel (online) | 52 | 2 | Almost all: workbook, sheet, table, row, column, range, format and colour actions |
| Google Sheets | 36 | 3 | Almost all: open, sheets, rows and columns, ranges, formats and colours, formulas, autofit |
| Google Drive | 20 | 3 | Copy, move, rename and delete files and folders, create folder, find, file info, permissions (check, create, get, delete) |
| Gmail | 14 | 1 | Read and find, change status, delete (all), forward, move (all), reply (all), save attachments, save email |
| Google Calendar | 4 | 1 | Delete event, list events |
| Microsoft 365 Outlook | 16 | 5 | Change status, check folder exists, delete (all), forward, move all, reply (all), save email |
| Microsoft 365 OneDrive | 15 | 3 | Copy, move, rename and delete files and folders, create folder, find, info, check permission, export to PDF, restore last version |
| Microsoft 365 Calendar | 11 | 2 | Add attachment, add and delete attendees, cancel, modify, respond, delete, get free slots |
| Microsoft Teams | 9 | 1 | Get team and channel (by name, details), user details, send chat message |
| Email (IMAP/SMTP) | 15 | 10 | Forward, reply, reply all, delete all, move all, save email (.eml), check folder exists |
| Active Directory | 33 | 11 | Create, delete, rename and move users, groups, computers and OUs; group, computer and OU properties; all users of a group; run LDAP query; update account options |
| Data Table | 18 | 10 | Insert and delete row, insert and delete column, column count, column name, change column type, clear, join, merge, search for value, write to file |
| String | 17 | 4 | Compare, find, length, substring, trim, reverse, random string, to number, to locale number (most are expressions today) |
| SAP (GUI) | 26 | 9 | Click, double and right click, click menu, check box, radio option, select item, expand tree node, children names and text, item, row, column and cell counts, table cell get/set and index, export table |
| Terminal emulator | 15 | 7 | Get field, get all fields, set field, search field, set cursor, clear, show/hide terminal, key mappings |
| Jira | 14 | 6 | Get, update and delete comments; update and delete issue; create, get, update and delete projects |
| Slack | 10 | 2 | Direct message, channel and workspace lookups, conversation and user details |
| Word | 13 | 8 | Create document, insert text, insert picture, find and replace picture, format text, save as |
| JSON | 11 | 5 | Insert node, update node, get node array, JSON to table, validate (schema) |
| Database | 11 | 7 | Bulk insert, stored procedures (run, manage), export to file |
| Datetime | 10 | 6 | Is after, is before, is equal (also as conditions), is leap year, subtract |
| SNMP | 5 | 0 | Get, get next, set, walk, send trap |
| Salesforce | 11 | 5 | Upsert by external id, get and upload attachments |
| Workday | 4 | 2 | Get report (RaaS), launch EIB, integration events |
| Browser | 8 | 19 | Get page source, call a JavaScript function, find broken links |
| Printer | 3 | 2 | Get default, set default, remove printer |
| File / Folder | 22 | 13 | Create shortcut, create symbolic link, print multiple files, open folder |
| FTP/SFTP | 11 | 8 | Change folder, delete folder, upload and download whole folders |
| List | 9 | 7 | Clear, set item, append list |
| Number | 5 | 3 | Increment, decrement, to text (expressions today) |
| Service | 5 | 4 | Pause, resume |
| Window | 10 | 10 | Close all, set title |
| Logging | 3 | 2 | Log to Command Center, log variables to file |
| PDF | 8 | 9 | Extract images, document properties |
| Others (1 each) | | | Boolean to number; Dictionary size; PGP create keys; Python execute function; REST download as file; Text file read variables; XML XPath function; Wait for screen change; OCR from URL; Image find window in window; Task: pause run |

Already complete or ahead: System, Application, Clipboard, Mouse, Keystrokes, Prompt, Message box, Delay, If, Loop,
Error handler, Step, Comment, Dictionary, Record, CSV, Image recognition, OCR, PDF, PGP, Sound, Script runners,
Workload (we have 8 actions to AA's 2), Recorder (web), Generative AI (prompt, classify, extract, summarise, translate,
sentiment; AA lists providers instead: we still need Amazon Bedrock and Google Vertex AI as providers).

## 3. Triggers

| AA trigger | Ours |
| --- | --- |
| File and folder | File arrives (Studio and Command Center). **Add**: file changed or deleted, folder created/deleted |
| Email (IMAP), Microsoft 365 Outlook | **Add** |
| Hot key | **Add** (Runner on the desktop) |
| Window (opened, closed) | **Add** |
| Process (started, stopped) | **Add** |
| Service (started, stopped) | **Add** |
| ServiceNow (new or updated record) | **Add** |
| Interface trigger (form button) | **Add** with Interactive forms |
| Schedule, webhook | Already ours (AA's Community Edition has no webhook trigger in the editor) |

## 4. Size of the work

About 400 missing actions in existing packages, about 270 actions in the 26 missing packages, and 9 triggers. The
largest blocks are the online office suites (Microsoft 365 Excel, Google Sheets), Excel advanced, and the new REST
toolkits; each REST toolkit follows the existing Jira/ServiceNow pattern.

## 5. Control Room, admin and editor features (Community Edition, read 19 Oct 2026)

Community Edition exposes these areas: Explore, Home, Automation, AI (Skills, Model connections, Document Automation),
Operations (Activity, Bot Insights), Manage (Devices, Global values, Credentials, Packages), Administration (Users).
Roles, audit log, settings, workload queues and schedules screens are not shown in Community Edition (Enterprise only).

| AA area | What it offers | Ours today | Gap to build |
| --- | --- | --- | --- |
| Home | My automations of the last 7 days, quick links, recently visited, learning links | Dashboard with runs, failures, devices | **Recent items and "continue where you left off"**, learning/help panel |
| Automation | Private and public folders, Create: Process, Task Bot, AI Skill, Form; Manage: Import; upload files; customise columns; search in subfolders; per-item menu | Studio home (private), Command Center repository (public) | **Import/export of agents and folders as a package**, upload files to the repository, folder tree in Command Center, customisable columns |
| AI > Skills | Public/private AI Skills, skill templates | AI Skills in the workspace | **Prompt Skill templates**, public/private skills in Command Center, test-run a skill from its page |
| AI > Model connections | Central LLM connections (vendor, model, credentials) used by skills and agents | Provider/model set per action or skill | **One Model Connections**: named connections managed in Command Center, referenced by name, with role access and the vault for keys |
| Document Automation | Learning instances (document types), validation, providers | Document intelligence designer + generated bot | **Validation queue** (human review of low-confidence fields), test documents, extraction history |
| Activity | In progress and Historical with priority, current action, run-as user, team/role, time filter (90 days), CSV export, customise columns | Built in Part 3 | **Run priority**, run-as user column, customisable columns, time-range presets |
| Bot Insights | Operational and business dashboards | Home dashboard | **One Insights**: business KPI dashboards from tagged values (our insights package already records them) |
| Devices | Status, nickname, single/multi-user, lifespan, log level, agent version, device pools, default device, connect local device, update agents, CSV export | Devices with status, rename, history | **Device pools**, default device per user, agent log level and update, multi-user devices, device credentials (run-as) |
| Global values | Typed values, changeable scope (user can override) | Shared values (JSON) | **Typed values and per-user override** |
| Credentials | Credentials, lockers, credential requests (user-provided values), attributes, owners/participants/consumers | Lockers, credentials, owners/consumers | **Credential requests** (ask a user to supply their own value), user-provided attributes, external vault connectors |
| Packages | Published packages with versions, vendor, compatibility, action/trigger counts, enable/disable, default version, upload custom package | Built-in toolkits only | **Toolkit manager**: list, versions, enable/disable, default version, upload a custom toolkit (signed) |
| Users | User type, roles, API key expiry, last login, CSV export | Users and four roles | **Custom roles with fine permissions**, API keys for users, user types (developer, runner, viewer), teams |
| Bot editor | Flow, List and Dual views; Quick add; search in automation; zoom; breakpoints and debugger; Assistant and Co-Pilot; run-time options; recorder; shared clipboard; triggers in the flow | Canvas (list), shared clipboard, recorder, run with pause/stop | **Flow (diagram) view**, **debugger with breakpoints, step over/into and variable watch**, **AI assistant that builds steps from a description**, Quick add (type to insert), run-time options (device, inputs, log level), triggers placed in the flow |

## 6. Recent platform capabilities (AA release notes v.33 to v.42, Jun 2024 to Oct 2026)

Read from the public release notes and written here as our own capability backlog (no vendor text). Grouped by
priority for ONEOFFICEAUTOMATION. "Have" means it already exists in our product.

| Priority | Capability | Ours |
| --- | --- | --- |
| P1 | **Goal-driven agents** that plan and call agents, APIs, other agents and tools, with human approval and escalation checkpoints, run logs and test runs | Partial: AI agent action with tools and approvals (Python reference); bring to Java and the designer |
| P1 | **MCP server and A2A**: outside assistants discover and run our agents under role-based access, OAuth and a run-as identity per tool | Partial: MCP over stdio (Python); add HTTP with OAuth, A2A outbound |
| P1 | **AI guardrails and governance**: PII masking with restore, toxicity check, prompt and tool-call traces, governance dashboard | Partial: PII masking and governance log; add dashboard, toxicity, traces UI |
| P1 | **One Model Connections** catalog: named models, bring-your-own/local LLMs, keys in the vault, role access, RAG sources | Gap |
| P1 | **Debugger**: breakpoints (also in child agents), step over/into, call stack, watch and edit variables | Gap (pause/resume only) |
| P1 | **Workload queues in Command Center**: SLAs, priority, sort columns, parallel processing across runners, auto-requeue on failure, statistics | Partial (per device); central queues planned |
| P1 | **Universal triggers**: webhook listener and poller with filters, pause/resume, payload formats (JSON, XML, form, text), OAuth token refresh, trigger monitoring page | Partial: webhook, file, schedule |
| P1 | **Self-healing UI automation** (fallback strategies when selectors break) and an **interrupt handler** for unexpected pop-ups | Gap |
| P2 | **Natural-language agent building** (describe it, get steps; plan; explain this agent) | Gap |
| P2 | **One Workflow runtime**: BPMN-style orchestrator, parallel split/merge, timers, message events, pause/resume/retry/skip, human tasks with dynamic assignment | Designer only |
| P2 | **Connector builder**: create a toolkit from an OpenAPI/Swagger/Postman file, OAuth, API keys, AWS SigV4, file streaming | Gap |
| P2 | **AI evaluation**: test sets and scores for agents, prompt skills and document extraction | Gap |
| P2 | **Document intelligence**: test mode with side-by-side compare, validation queue, accuracy scoring, local LLMs | Partial (designer + extraction agent) |
| P2 | **External vaults** (HashiCorp, CyberArk, Azure Key Vault, AWS Secrets Manager), multiple per tenant, key rotation, credential ownership transfer, change alerts | Gap (own vault only) |
| P2 | **Monitoring and insights**: live operations view, run-hour charts by agent/runner/user, ROI tracking, custom dashboards with sharing | Partial (dashboard) |
| P2 | **Headless API agents**: callbacks to a URL with outputs, stop a running API agent, call a child API agent, read repository files, auto-scaling runtime | Partial |
| P3 | Admin hygiene: auto-disable inactive users, disable built-in roles, IP allow-list, announcement banner, audit search 180+ days with before/after values, upload size limit, proxy settings | Partial (audit) |
| P3 | Command Center as OAuth/OIDC provider; API keys per user; SSO (SAML/OIDC) | Gap |
| P3 | Toolkit lifecycle: versions, default version, "where used", bulk upgrade, compatibility check, delete unused versions | Gap |
| P3 | Scheduling: holiday calendars, schedule ownership transfer, schedule processes directly, rename schedules, alert when run-as user is disabled | Partial |
| P3 | Runner fleet: device pools with load balancing, bulk registration, proxy, RDP/virtual sessions, macOS and Linux runners | Partial (Windows runner) |
| P3 | Editor polish: quick add, annotations, breadcrumbs between parent/child agents, selective check-in of child agents, change variable type safely, export from My Workspace, run in a floating test window, code-analysis rules before check-in | Partial |
| P3 | Git sync for the agent repository | Gap |

## 7. Not yet compared

- The settings inside each action (fields, options, defaults). Next pass: open each action in the AA editor (read-only)
  for the ten most-used packages (Excel, Email, Browser/Recorder, Data Table, String, File/Folder, REST, Database, Loop,
  If) and compare field by field.
- Command Center screens (Activity, Devices, Credentials, Workload queues, Bot Insights, Administration).
