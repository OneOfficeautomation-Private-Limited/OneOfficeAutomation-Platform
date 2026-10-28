# Command catalog: Automation Anywhere packages and ONEOFFICEAUTOMATION equivalents

Built from the Command Catalog sheet of the AA feature map. **Built** = in the platform and tested in the build. **Built (library module, test on laptop)** = in an optional module (gradle.properties) whose tests run on your machine. **Partial** = main commands built, some planned. **Planned** = scheduled for a coming batch.

| Area | AA package | OOA package / commands | Status | Notes |
| --- | --- | --- | --- | --- |
| Core logic & data | If | core/if + package conditions | Built |  |
| Core logic & data | Loop | core/loop + package iterators | Built |  |
| Core logic & data | Error handler | core/try, core/throw | Built |  |
| Core logic & data | Step | core/step | Built |  |
| Core logic & data | Comment | core/comment | Built |  |
| Core logic & data | Task Bot | core/run_bot | Built |  |
| Core logic & data | Delay / Wait | core/delay, core/wait_until, ooa.image/wait_for_image | Built |  |
| Core logic & data | Boolean | ooa.boolean | Built |  |
| Core logic & data | Number | ooa.number | Built |  |
| Core logic & data | String | ooa.string + expression functions | Built |  |
| Core logic & data | DateTime | ooa.datetime | Built |  |
| Core logic & data | List | ooa.list | Built |  |
| Core logic & data | Dictionary | ooa.dictionary | Built |  |
| Core logic & data | Data Table | ooa.datatable | Built |  |
| Core logic & data | Record | Record type + ooa.datatable | Built | Records are dictionaries |
| Core logic & data | Clipboard | ooa.desktop/clipboard_* | Built | Needs a desktop session |
| Core logic & data | Log to file | ooa.log | Built |  |
| Core logic & data | Message box | ooa.desktop/message_box | Built | Attended |
| Core logic & data | Prompt | ooa.desktop/prompt_* | Built | Value, yes/no, password, file and folder |
| Core logic & data | Play sound / Beep | ooa.sound | Built |  |
| Core logic & data | Bot Migration / Legacy | - | Not needed | AA-specific |
| Core logic & data | Global values | ooa.global_values, $global | Built | Managed in Control Room; workspace file in Studio |
| UI automation | Recorder | ooa.browser/record, ooa.browser/element_action (Capture), Studio Record, Capture and Pick | Partial | Web recorder and web Capture built; desktop, Java and SAP capture need the Bot Agent |
| UI automation | Browser | ooa.browser | Built (library module, test on laptop) | Playwright; uses installed Edge or Chrome |
| UI automation | Window | ooa.window | Built (library module, test on laptop) | Win32 via JNA |
| UI automation | Desktop objects, legacy (MSAA) | ooa.desktop_ui/find_legacy_objects, legacy_object_action | Built | IAccessible through oleacc for older Win32, VB6 and Delphi applications: find by name and role, default action, set and get value |
| UI automation | Java applications (Swing/AWT) | ooa.java_apps | Built | Java Attach API and the applications' own accessibility (no Java Access Bridge setting needed): list apps, find controls, set and get text, press, focus |
| UI automation | Mouse | ooa.desktop/mouse_* | Built | Needs a desktop session |
| UI automation | Simulate keystrokes | ooa.desktop/type_text, press_keys | Built | Needs a desktop session |
| UI automation | Screen | ooa.desktop/capture_screen | Built | Window capture comes with the Window package |
| UI automation | Image recognition | ooa.image | Built |  |
| UI automation | Interrupt handler | ooa.interrupts | Built | Rules that answer unexpected pop-ups, checked before each UI step |
| UI automation | OCR | ooa.ocr | Built | Needs Tesseract installed; tested with real Tesseract |
| UI automation | Application | ooa.application | Built |  |
| UI automation | Legacy automation | - | Not needed | AA v11 compatibility |
| UI automation | Terminal Emulator | ooa.terminal | Partial | TN3270 via x3270 (tested with the real engine); VT100/ANSI over SSH (password or key, tested against an in-process SSH host); TN5250 for IBM i with our own engine (tested against a fake host and the public PUB400 system; see docs/research/terminal-vt-ssh.md) |
| UI automation | SAP | ooa.sap | Partial | SAP GUI Scripting built; BAPI/RFC needs SAP JCo from the customer |
| UI automation | Citrix / remote desktop | ooa.image, ooa.ocr | Partial | Image + OCR built; in-session agent later |
| UI automation | App-specific recorders | - | Planned | After the Recorder |
| Office & files | Excel advanced (needs Excel) | ooa.excel_desktop | Built | Windows + Excel; macros, refresh, PDF export, convert |
| Integration & system | Outlook desktop (needs Outlook) | ooa.outlook_desktop | Built | Windows + Outlook with a mail profile; find, read, send, reply, forward, move, mark, save attachments, folders, shared mailboxes |
| Office & files | Excel basic (no Excel needed) | ooa.excel | Built (library module, test on laptop) | Already verified on your laptop. Steps that keep the session name "excel" use the one open workbook, whatever it was opened as (Word and PowerPoint the same). A path such as onedrive:Reports/sales.xlsx (with a Graph token, and a SharePoint library as drive) is downloaded on open and saved back on save |
| Office & files | Office 365 Excel / Google Sheets | ooa.microsoft365 (Excel online), ooa.google (Sheets) | Built |  |
| Office & files | Microsoft Word | ooa.word | Built (library module, test on laptop) | Extract text also reads old .doc and .rtf files |
| Office & files | PowerPoint | ooa.powerpoint | Built (library module, test on laptop) | Open, edit and save decks; no PowerPoint needed |
| Office & files | CSV/TXT | ooa.csv | Built |  |
| Office & files | PDF | ooa.pdf | Built (library module, test on laptop) |  |
| Office & files | File | ooa.file | Built | Printing via ooa.printer |
| Office & files | Folder | ooa.folder | Built |  |
| Office & files | XML | ooa.xml | Built |  |
| Office & files | JSON | ooa.json | Built |  |
| Office & files | Printer | ooa.printer | Built |  |
| Integration & system | Email | ooa.email, ooa.microsoft365, ooa.google | Built (library module, test on laptop) | SMTP/IMAP with OAuth, Outlook via Graph, Gmail API |
| Integration & system | Office 365 / Google Calendar | ooa.microsoft365, ooa.google | Built |  |
| Integration & system | OneDrive / SharePoint / Google Drive | ooa.microsoft365, ooa.google | Built |  |
| Integration & system | Microsoft Teams / Slack | ooa.microsoft365/teams_message, ooa.slack | Built |  |
| Integration & system | REST Web Service | ooa.rest | Built |  |
| Integration & system | SOAP Web Service | ooa.soap | Built | Read WSDL lists the operations (SOAP 1.1 and 1.2) with SOAPAction, endpoint and a request body from the schema, imports included |
| Integration & system | Database | ooa.database | Built | Database drivers installed per device |
| Integration & system | FTP/SFTP | ooa.file_transfer | Built (library module, test on laptop) | FTP, FTPS, SFTP with host key pinning |
| Integration & system | Active Directory | ooa.active_directory | Built | Compiled and unit-tested; needs a directory server for a full test |
| Integration & system | Python script | ooa.script/run_python | Built |  |
| Integration & system | JavaScript | ooa.script/run_javascript | Built | Node.js |
| Integration & system | VBScript | ooa.script/run_vbscript | Built | Windows |
| Integration & system | DLL | ooa.dll | Built (library module, test on laptop) | JNA; trusted libraries only |
| Integration & system | Windows Credential Manager | ooa.credential_manager | Built (library module, tested on Windows) | Generic credentials via advapi32 (JNA); secrets returned as Credential |
| Core logic & data | Fuzzy match | ooa.fuzzy | Built | Levenshtein, Jaro-Winkler, token sort, token set; best match, fuzzy table join |
| Core logic & data | Currency conversion | ooa.currency | Built | ECB daily rates, any JSON rates URL, or a fixed rates Dictionary; cached per run |
| Integration & system | Trigger loop | ooa.triggerloop iterators in core/loop | Built | New file, timer tick and process start events; max events, max time, Exit loop or stop |
| Integration & system | PowerShell | ooa.script/run_powershell | Built |  |
| Integration & system | System | ooa.system | Built | Power actions need confirm = true |
| Integration & system | Services | ooa.services | Built | Windows; start and stop need administrator rights |
| Integration & system | Ping / SNMP | ooa.network | Built | SNMP v1, v2c and v3 get, get next, walk and set (v3: MD5, SHA, SHA-256, SHA-512 sign-in; DES or AES-128 privacy); v2c traps |
| Integration & system | Hash / Cryptography | ooa.crypto | Built | Hashes, HMAC, AES-256-GCM, RSA encrypt/sign |
| Integration & system | PGP | ooa.pgp | Built | Needs GnuPG installed; tested with real GnuPG |
| Integration & system | Credential / Vault | Credential type + Control Room lockers | Built | AES-256-GCM, owners and consumers, just-in-time delivery, audited |
| Integration & system | Triggers (event) | Studio triggers + Command Center triggers on agents | Built | File, schedule and webhook in both; email (IMAP, Microsoft 365), window, process, hotkey and service triggers on runners |
| Integration & system | Salesforce / ServiceNow / Workday / Jira | ooa.salesforce, ooa.servicenow, ooa.workday, ooa.jira | Built | Workday: workers + generic REST/RaaS |
| Integration & system | GitHub / Azure DevOps / Asana / Zendesk | ooa.github, ooa.azure_devops, ooa.asana, ooa.zendesk | Built | REST toolkits; base URL input for GitHub Enterprise, Azure DevOps Server and Zendesk subdomains |
| Integration & system | Okta / Box / DocuSign | ooa.okta, ooa.box, ooa.docusign | Built | Okta users, status and groups; Box files, folders, sharing; DocuSign envelopes, templates, embedded signing |
| Integration & system | Snowflake / Databricks / Kafka | ooa.snowflake, ooa.databricks, ooa.kafka | Built | REST only, no drivers: Snowflake SQL API v2, Databricks SQL/Jobs/Clusters/Files, Kafka REST Proxy |
| Integration & system | AWS EC2 | ooa.aws_ec2 | Built | Connect (region, access keys from the vault, session token), describe, status, start, stop, reboot, terminate, monitoring on/off, launch, tags, wait for state; signed with AWS Signature V4, no AWS SDK |
| AI, workload & process | Document Automation / IQ Bot | - | Excluded for now (your request) |  |
| AI, workload & process | Knowledge Q&A | ooa.knowledge | Built | Index text, Markdown, HTML, CSV, JSON, XML, .docx and PDF files into one index file (embeddings optional: OpenAI, Azure OpenAI, OpenAI-compatible such as Ollama, Vertex AI, Bedrock Titan/Cohere); search by words (BM25) and meaning; answers that cite their passages and say when the documents do not say |
| AI, workload & process | Generative AI | ooa.genai | Built | OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock (Converse, SigV4), Google Vertex AI (Gemini; service-account key or token), Hugging Face, Amazon SageMaker endpoints (SigV4), self-hosted; prompts and chats with pictures; picture generation (OpenAI, Azure OpenAI, Vertex AI Imagen, Bedrock); detect language, key phrases, named entities; PII masking; governance log |
| AI, workload & process | AI Skills / AI Agent | ooa.ai_skills, ooa.ai_agent | Built | Agents call bots as tools; sensitive bots need approval; step and token limits. Prompt Skills kept in the Command Center (shared, versioned, templates) reach runs with the job |
| AI, workload & process | Web goal agent | ooa.web_agent | Built | Start session, Reach a goal (an AI model works the page: click, type, select, scroll, go to, finish), End session; allowed sites; vault secrets typed through placeholders the model never sees; password-only check |
| AI, workload & process | Analyze | ooa.insights | Built | Tagged business values; dashboards in the Insights phase |
| AI, workload & process | Workload | ooa.workload | Built | Local queues with retries and deferral; central queues and SLAs with Control Room |
| AI, workload & process | Process / Co-Pilot | One Workflow (Studio designer, Command Center runtime, Task inbox) | Built | Agent tasks, human tasks with forms, approvals, decisions and notifications; timeline, cancel, due times |
| AI, workload & process | Bot Store packages | - | Planned | Needs the Hub marketplace; not a command |
| AI, workload & process | Run context | ooa.run_context | Built |  |

## Summary

- **Built**: 83 AA packages
- **Excluded for now (your request)**: 1 AA packages
- **Not needed**: 2 AA packages
- **Partial**: 4 AA packages
- **Planned**: 2 AA packages

Control Room features (users, roles, devices, schedules, queues, vault screens, audit) and the Windows Bot Agent are platform systems rather than commands and follow in their own phases, as described in the Technical Architecture.

## AA packages not in the catalogue yet (X9)

Checked on 2026-10-28 against the Packages page of a signed-in AA Community Edition Control Room, which lists 178
packages (read only, nothing changed). In our own words. Most are in the table above, some under other names:

- **Same toolkit, other name**: Logging (Log to file), Text file (CSV/TXT), Wait and Delay (Delay / Wait), Datetime
  (DateTime), Credential and Credential Manager (Credential / Vault, Windows Credential Manager), Interactive forms
  (ooa.forms), Process Composer, Process Any/Event/Form/Swimlane/Table/Tasks/UserGroup, Conditional and Process trigger
  (One Workflow), Gmail and G-Suite Apps (ooa.google), Microsoft 365 Calendar/Excel/OneDrive/Outlook (ooa.microsoft365),
  JSON utilities, JSON Object Manager and JSONParser (ooa.json), Word and MSWordPackage (ooa.word), Ping and SNMP
  (ooa.network), Service (ooa.services), Image Recognition (ooa.image), OpenAI (ooa.genai model connection), API Task
  and Python script for API Task (Command Center API tasks), SAP BAPI (ooa.sap, BAPI needs the customer's SAP JCo).
- **Built but missing from the table above**: PowerPoint (ooa.powerpoint) and Interrupt handler (ooa.interrupts: a
  block that answers unexpected pop-ups during UI steps). Both added below.
- **Triggers** (Email, File Folder, Hot key, Interface, Service, Universal web, Window, Microsoft 365 Outlook, and the
  app triggers in GitHub, Jira, ServiceNow, SharePoint, Teams, Google Calendar) are in Triggers (event) above.
- **Old copies and samples**: the "(Beta)" and 2019-2020 versions (AWS Comprehend NLP (Beta), Google Calendar/Drive/
  Sheets (Beta), G-Suite Apps (Beta), Microsoft LUI NLP (Beta), One Drive, SAP2, Office 365 Authentication and
  Calendarv1.0, which are switched off), and demos (A2019DemoPackage, hello-aaaction, Metric To Imperial, Teknei,
  Enterprise Claw Agent from an outside vendor, switched off). Not needed.

The rest:

| AA package | What it does in AA | OOA today | Proposal |
| --- | --- | --- | --- |
| PowerPoint | Open, edit and save slide decks | ooa.powerpoint | Built (catalogue row was missing) |
| Interrupt handler | A block that watches for pop-ups and answers them | ooa.interrupts | Built (catalogue row was missing) |
| AWS EC2 | Create, start, stop and end EC2 virtual machines (9 actions) | ooa.aws_ec2 (12 actions) | Built 2026-10-28 (row above) |
| AWS Comprehend NLP, Microsoft LUIS NLP (Beta) | Sentiment, entities, language and intent from cloud language services | ooa.genai sentiment, entities, language and key phrases | Covered through an AI model; a direct AWS Comprehend action only if a customer asks |
| ML - Microsoft Anomaly Detection | Finds unusual points in a series with an Azure service | Nothing | **Not needed now**: Azure retired the service; a REST call covers a replacement |
| IBM Watson Authentication, Speech to Text (Beta) | Turns an audio file into text with IBM's service | Nothing | **Build later**: a speech-to-text action in ooa.genai (any provider) if customers ask |
| Aisera | Sends content to Aisera and asks questions of it (2 actions) | ooa.knowledge does the same with our own store | Covered in purpose; a direct Aisera action only if a customer asks |
| AI (4 actions) | Calls machine-learning and generative models | ooa.genai (Hugging Face, AWS Bedrock and other connections) | Covered |
| AI Agent (Local) | Runs an AI agent on a chosen runner (1 action) | ooa.ai_agent on any runner | Covered |
| EnterpriseClaw | Autonomous agents next to the data; on this Control Room only as an outside vendor's package, switched off | ooa.ai_agent and API tasks | Covered in purpose |
| UI Agent - Web | Web automation from a plain-language goal (3 actions) | ooa.web_agent | Covered; a desktop version (Windows UI Automation) could follow |
| AISense, Legacy AISense | Recording on remote and virtual desktops by screen analysis | ooa.image, ooa.ocr (Citrix / remote desktop row) | Partial, as in that row |
| Process Discovery | Starts AA's process recorder and window capture for its discovery product | Nothing | **Not needed**: tied to AA's own discovery product |
| Genesys | Contact-centre contacts and interactions (54 actions) | Nothing; REST toolkit or a custom connector works | **Build later** if customers ask; a connector (OpenAPI import) covers it today |
| Google Application Integration | Runs Google Application Integration flows | Nothing; REST toolkit works | **Not needed now**: one REST call |
| Google Document AI | Structured data from documents with Google's models | Nothing | With Document Automation, **excluded for now (your request)** |
| Document Extraction, Document Extractor, IQ Bot, IQ Bot Extraction, Extraction360, Pre-processor, IQ Bot [Local Device] | AA's document automation | Nothing | **Excluded for now (your request)** |
| App integration | Reads a window's text into a variable | ooa.desktop_ui (find objects, read text) | Covered |
| FileUpload | Sends a file to a web API (1 action) | ooa.rest (multipart upload) | Covered |
| Utils, NumberUtils, PDFUtils | Old helpers: copy a file, text to number or date, value type, keystrokes, PDF pages | ooa.file, ooa.string, ooa.number, ooa.datetime, ooa.pdf, ooa.desktop | Covered |
| Pipeline Accelerator | Syncs AA CoE Manager data with other apps | Nothing | **Not needed**: AA-specific product |
| Goto, V11 Task Bot, Legacy Support, Bot Migration | Moving bots from other tools or older AA versions | Nothing | **Not needed**: AA-specific migration |
| Apple Calendar, Keynote, Mail, Numbers, Pages, AppleScript, Microsoft Outlook (macOS) | Mac desktop applications | macOS runners run headless agents only | **Later**: needs a macOS desktop runner first |
