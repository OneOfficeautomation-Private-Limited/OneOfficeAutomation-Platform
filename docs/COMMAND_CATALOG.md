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
| UI automation | Mouse | ooa.desktop/mouse_* | Built | Needs a desktop session |
| UI automation | Simulate keystrokes | ooa.desktop/type_text, press_keys | Built | Needs a desktop session |
| UI automation | Screen | ooa.desktop/capture_screen | Built | Window capture comes with the Window package |
| UI automation | Image recognition | ooa.image | Built |  |
| UI automation | OCR | ooa.ocr | Built | Needs Tesseract installed; tested with real Tesseract |
| UI automation | Application | ooa.application | Built |  |
| UI automation | Legacy automation | - | Not needed | AA v11 compatibility |
| UI automation | Terminal Emulator | ooa.terminal | Partial | TN3270 via x3270 (tested with the real engine); TN5250 and VT planned |
| UI automation | SAP | ooa.sap | Partial | SAP GUI Scripting built; BAPI/RFC needs SAP JCo from the customer |
| UI automation | Citrix / remote desktop | ooa.image, ooa.ocr | Partial | Image + OCR built; in-session agent later |
| UI automation | App-specific recorders | - | Planned | After the Recorder |
| Office & files | Excel advanced (needs Excel) | ooa.excel_desktop | Built | Windows + Excel; macros, refresh, PDF export, convert |
| Office & files | Excel basic (no Excel needed) | ooa.excel | Built (library module, test on laptop) | Already verified on your laptop |
| Office & files | Office 365 Excel / Google Sheets | ooa.microsoft365 (Excel online), ooa.google (Sheets) | Built |  |
| Office & files | Microsoft Word | ooa.word | Built (library module, test on laptop) |  |
| Office & files | CSV/TXT | ooa.csv | Built |  |
| Office & files | PDF | ooa.pdf | Built (library module, test on laptop) |  |
| Office & files | File | ooa.file | Built | Printing via ooa.printer |
| Office & files | Folder | ooa.folder | Built |  |
| Office & files | XML | ooa.xml | Built |  |
| Office & files | JSON | ooa.json | Built |  |
| Office & files | Printer | ooa.printer | Built |  |
| Integration & system | Email | ooa.email, ooa.microsoft365, ooa.google | Built (library module, test on laptop) | SMTP/IMAP with OAuth, Outlook via Graph, Gmail API; Outlook desktop planned |
| Integration & system | Office 365 / Google Calendar | ooa.microsoft365, ooa.google | Built |  |
| Integration & system | OneDrive / SharePoint / Google Drive | ooa.microsoft365, ooa.google | Built |  |
| Integration & system | Microsoft Teams / Slack | ooa.microsoft365/teams_message, ooa.slack | Built |  |
| Integration & system | REST Web Service | ooa.rest | Built |  |
| Integration & system | SOAP Web Service | ooa.soap | Built | WSDL import planned |
| Integration & system | Database | ooa.database | Built | Database drivers installed per device |
| Integration & system | FTP/SFTP | ooa.file_transfer | Built (library module, test on laptop) | FTP, FTPS, SFTP with host key pinning |
| Integration & system | Active Directory | ooa.active_directory | Built | Compiled and unit-tested; needs a directory server for a full test |
| Integration & system | Python script | ooa.script/run_python | Built |  |
| Integration & system | JavaScript | ooa.script/run_javascript | Built | Node.js |
| Integration & system | VBScript | ooa.script/run_vbscript | Built | Windows |
| Integration & system | DLL | ooa.dll | Built (library module, test on laptop) | JNA; trusted libraries only |
| Integration & system | PowerShell | ooa.script/run_powershell | Built |  |
| Integration & system | System | ooa.system | Built | Power actions need confirm = true |
| Integration & system | Services | ooa.services | Built | Windows; start and stop need administrator rights |
| Integration & system | Ping / SNMP | ooa.network | Partial | SNMP planned |
| Integration & system | Hash / Cryptography | ooa.crypto | Built | Hashes, HMAC, AES-256-GCM, RSA encrypt/sign |
| Integration & system | PGP | ooa.pgp | Built | Needs GnuPG installed; tested with real GnuPG |
| Integration & system | Credential / Vault | Credential type + Control Room lockers | Built | AES-256-GCM, owners and consumers, just-in-time delivery, audited |
| Integration & system | Triggers (event) | Studio triggers + Control Room triggers on agents | Partial | File, schedule and webhook built in both; email, window, hotkey and service triggers planned |
| Integration & system | Salesforce / ServiceNow / Workday / Jira | ooa.salesforce, ooa.servicenow, ooa.workday, ooa.jira | Built | Workday: workers + generic REST/RaaS |
| AI, workload & process | Document Automation / IQ Bot | - | Excluded for now (your request) |  |
| AI, workload & process | Generative AI | ooa.genai | Built | OpenAI, Azure OpenAI, Anthropic, self-hosted; PII masking; governance log |
| AI, workload & process | AI Skills / AI Agent | ooa.ai_skills, ooa.ai_agent | Built | Agents call bots as tools; sensitive bots need approval; step and token limits |
| AI, workload & process | Analyze | ooa.insights | Built | Tagged business values; dashboards in the Insights phase |
| AI, workload & process | Workload | ooa.workload | Built | Local queues with retries and deferral; central queues and SLAs with Control Room |
| AI, workload & process | Process / Co-Pilot | - | Planned | Needs the Assist portal (forms, task inbox); not a command |
| AI, workload & process | Bot Store packages | - | Planned | Needs the Hub marketplace; not a command |
| AI, workload & process | Run context | ooa.run_context | Built |  |

## Summary

- **Built**: 64 AA packages
- **Excluded for now (your request)**: 1 AA packages
- **Not needed**: 2 AA packages
- **Partial**: 7 AA packages
- **Planned**: 3 AA packages

Control Room features (users, roles, devices, schedules, queues, vault screens, audit) and the Windows Bot Agent are platform systems rather than commands and follow in their own phases, as described in the Technical Architecture.
