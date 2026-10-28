# Toolkit reference

Every toolkit of this release with every action, condition and loop type: each field as the settings panel shows it
(label, name, type, whether it is required, its default, whether it is in the main area, under a choice or a tick,
or under More options), and each result. Generated from the toolkit manifests (`ooa manifest`) by
`docs/research/tools/make-reference.py`; do not edit these pages by hand.

Field names are what a flow file and `ooa run` use; labels are what Studio shows. Text fields accept `${...}`
values and `$variable` references; Credential fields take a credential from the vault.

| Toolkit | Id | Actions | Conditions | Loop types | What it does |
| --- | --- | --- | --- | --- | --- |
| [Core](core.md) | `core` | 16 | 0 | 0 | Control flow, variables and runtime built-ins. |
| [Active Directory](ooa-active_directory.md) | `ooa.active_directory` | 38 | 0 | 0 | Create, change, move and delete users, groups, computers and organizational units, reset passwords and manage group membership in Active Directory or LDAP. |
| [AI Agents](ooa-ai_agent.md) | `ooa.ai_agent` | 1 | 0 | 0 | Run an AI agent that works toward a goal using your agents as tools, with approvals and limits. |
| [Application](ooa-application.md) | `ooa.application` | 4 | 1 | 0 | Open files, start, run and stop programs on the runner. |
| [Asana](ooa-asana.md) | `ooa.asana` | 38 | 0 | 0 | Manage Asana work: workspaces, projects, sections, tasks and subtasks, followers, comments, attachments, tags and users. |
| [AWS EC2](ooa-aws_ec2.md) | `ooa.aws_ec2` | 13 | 0 | 0 | Start, stop, reboot, launch and end AWS EC2 virtual machines, read their state and tags, and switch detailed monitoring on or off. |
| [Azure DevOps](ooa-azure_devops.md) | `ooa.azure_devops` | 44 | 1 | 0 | Work with Azure DevOps: work items and WIQL queries, comments and attachments, Git repositories and pull requests, pipelines, builds and sprints. Works with the cloud service and Azure DevOps Server. |
| [Boolean](ooa-boolean.md) | `ooa.boolean` | 5 | 0 | 0 | Convert and invert true/false values. |
| [Box](ooa-box.md) | `ooa.box` | 16 | 0 | 0 | Work with Box folders and files: upload, download, copy, move, search, share links and invite collaborators. |
| [Browser](ooa-browser.md) | `ooa.browser` | 28 | 4 | 1 | Open web pages, click, type, select, read text and tables, run JavaScript, download files and take screenshots. |
| [CSV/TXT](ooa-csv.md) | `ooa.csv` | 2 | 0 | 1 | Read and write delimited text files as tables. |
| [Currency](ooa-currency.md) | `ooa.currency` | 3 | 0 | 0 | Convert amounts between currencies and look up exchange rates from the ECB, a JSON rates service or your own table. |
| [Custom Connectors](ooa-connector.md) | `ooa.connector` | 5 | 0 | 0 | Call any REST API imported from an OpenAPI, Swagger or Postman file as named operations with typed inputs and authentication. |
| [Data Table](ooa-datatable.md) | `ooa.datatable` | 27 | 5 | 0 | Build, filter, sort, search and change in-memory tables. |
| [Database](ooa-database.md) | `ooa.database` | 12 | 0 | 1 | Connect to databases and run parameterised queries, updates and transactions. |
| [Databricks](ooa-databricks.md) | `ooa.databricks` | 14 | 0 | 0 | Run SQL on a Databricks SQL warehouse, start and watch jobs, start or stop clusters, and move files in and out of the workspace. |
| [Date and time](ooa-datetime.md) | `ooa.datetime` | 14 | 7 | 0 | Current time, add, difference, format and time zone conversion. |
| [Desktop objects](ooa-desktop_ui.md) | `ooa.desktop_ui` | 9 | 1 | 0 | Capture controls in Windows applications and find them again by their UI Automation properties to click, type, read and select. |
| [Dictionary](ooa-dictionary.md) | `ooa.dictionary` | 7 | 4 | 0 | Put, get and remove key/value pairs. |
| [DLL](ooa-dll.md) | `ooa.dll` | 3 | 0 | 0 | Call a function in a trusted native library (DLL) with numbers and text, and get its return value. |
| [DocuSign](ooa-docusign.md) | `ooa.docusign` | 20 | 0 | 0 | Send documents for e-signature, track and manage envelopes and recipients, use templates and download signed documents. |
| [Email](ooa-email.md) | `ooa.email` | 18 | 0 | 1 | Send emails with attachments, read and search mailboxes, save attachments, move, mark and delete emails. |
| [Excel (desktop, needs Excel)](ooa-excel_desktop.md) | `ooa.excel_desktop` | 5 | 0 | 0 | Run macros, refresh data and pivots, update links, export to PDF and convert workbooks using the installed Microsoft Excel. |
| [Excel (no Office needed)](ooa-excel.md) | `ooa.excel` | 63 | 0 | 1 | Read and write .xlsx workbooks without Microsoft Office installed. |
| [File](ooa-file.md) | `ooa.file` | 16 | 4 | 1 | Read, write, copy, move and delete files. |
| [Folder](ooa-folder.md) | `ooa.folder` | 8 | 2 | 1 | Create, list, copy and delete folders. |
| [FTP / SFTP](ooa-file_transfer.md) | `ooa.file_transfer` | 13 | 0 | 0 | Connect to FTP, FTPS or SFTP servers to list, upload, download, rename and delete files. |
| [Fuzzy Match](ooa-fuzzy.md) | `ooa.fuzzy` | 3 | 1 | 0 | Score how alike two texts are, pick the closest candidate from a list and join tables on near-matching values. |
| [Generative AI](ooa-genai.md) | `ooa.genai` | 13 | 0 | 0 | Prompt large language models (OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock, Google Vertex AI, self-hosted) to answer, read pictures, draw pictures and analyse text. |
| [GitHub](ooa-github.md) | `ooa.github` | 21 | 1 | 0 | Work with GitHub repositories: issues, pull requests, branches, files, releases and Actions workflows. Works with github.com and GitHub Enterprise Server. |
| [Google Workspace](ooa-google.md) | `ooa.google` | 68 | 0 | 0 | Read and write Google Sheets, upload and download Drive files, manage Calendar events and send Gmail. |
| [Hash, encryption and encoding](ooa-crypto.md) | `ooa.crypto` | 14 | 0 | 0 | Hashes, HMAC signatures, AES-256 encryption of text and files, and Base64 encoding. |
| [Image recognition](ooa-image.md) | `ooa.image` | 5 | 2 | 0 | Find, wait for and click images on the screen or inside image files. |
| [Insights](ooa-insights.md) | `ooa.insights` | 4 | 0 | 0 | Record business values and timed transactions from agents for dashboards and ROI reports, and read them back. |
| [Interactive forms](ooa-forms.md) | `ooa.forms` | 18 | 0 | 0 | Show a form to the person at the runner, react to its buttons and changes, and read or change its fields while it is open. |
| [Interrupt handlers](ooa-interrupts.md) | `ooa.interrupts` | 5 | 0 | 0 | Rules that deal with unexpected windows and pop-ups during UI automation, checked before each UI action. |
| [Java applications (Swing/AWT)](ooa-java_apps.md) | `ooa.java_apps` | 3 | 0 | 0 | Find and work with the controls of desktop applications written in Java (Swing and AWT): read and type text, press buttons, read labels and tables. |
| [Jira](ooa-jira.md) | `ooa.jira` | 15 | 0 | 0 | Create, read and search Jira issues, add comments, move issues through the workflow and attach files. |
| [JSON](ooa-json.md) | `ooa.json` | 12 | 0 | 0 | Parse, query and write JSON documents. |
| [Kafka](ooa-kafka.md) | `ooa.kafka` | 6 | 0 | 0 | Send messages to Kafka topics, read messages, and create or inspect topics through a Kafka REST Proxy. |
| [Knowledge Q&A](ooa-knowledge.md) | `ooa.knowledge` | 3 | 0 | 0 | Index your documents and answer questions from them, with the passages each answer comes from. |
| [List](ooa-list.md) | `ooa.list` | 12 | 2 | 0 | Add, read, remove, sort and join list items. |
| [Log to file](ooa-log.md) | `ooa.log` | 3 | 0 | 0 | Write timestamped lines to a log file or to the run log in Command Center; secrets are masked automatically. |
| [Microsoft 365](ooa-microsoft365.md) | `ooa.microsoft365` | 92 | 0 | 0 | Outlook mail, calendar, OneDrive and SharePoint files and Teams messages through Microsoft Graph. |
| [Mouse, keyboard and screen](ooa-desktop.md) | `ooa.desktop` | 15 | 0 | 0 | Click, type, press keys, capture the screen, use the clipboard and show dialogs on the desktop. |
| [Network](ooa-network.md) | `ooa.network` | 7 | 2 | 0 | Check whether hosts and ports are reachable, and read or change devices over SNMP v1, v2c and v3. |
| [Number](ooa-number.md) | `ooa.number` | 6 | 0 | 0 | Round, format and generate numbers with exact decimal arithmetic. |
| [OCR](ooa-ocr.md) | `ooa.ocr` | 5 | 1 | 0 | Read text from images, image URLs or the screen, and find or click text on the screen (Tesseract). |
| [Okta](ooa-okta.md) | `ooa.okta` | 14 | 0 | 0 | Create and manage Okta users, change their status, reset passwords and manage group membership. |
| [Outlook (desktop, needs Outlook)](ooa-outlook_desktop.md) | `ooa.outlook_desktop` | 10 | 0 | 0 | Find, read, send, reply to, forward, move and file emails with the Outlook installed on the runner, using its mail profile. |
| [PDF](ooa-pdf.md) | `ooa.pdf` | 13 | 0 | 0 | Extract text, merge, split, read and fill forms, encrypt, decrypt and convert PDF pages to images. |
| [PGP](ooa-pgp.md) | `ooa.pgp` | 6 | 0 | 0 | Import keys, encrypt, decrypt, sign and verify files with OpenPGP (GnuPG). |
| [Play sound](ooa-sound.md) | `ooa.sound` | 2 | 0 | 0 | Beep or play a WAV file, e.g. to alert an attended user. |
| [PowerPoint](ooa-powerpoint.md) | `ooa.powerpoint` | 17 | 0 | 0 | Open or create .pptx presentations, add, copy and delete slides, edit text, pictures and tables, and save. |
| [Printer](ooa-printer.md) | `ooa.printer` | 5 | 0 | 0 | List printers and print files. |
| [Prompt Skills](ooa-ai_skills.md) | `ooa.ai_skills` | 2 | 0 | 0 | Run saved, reusable prompt templates (Prompt Skills) with your values. |
| [REST Web Service](ooa-rest.md) | `ooa.rest` | 3 | 0 | 0 | Call HTTP/REST APIs with JSON, headers and authentication. |
| [Run context](ooa-run_context.md) | `ooa.run_context` | 2 | 0 | 0 | Information about the current run: run id, runner, user and workspace. |
| [Salesforce](ooa-salesforce.md) | `ooa.salesforce` | 10 | 0 | 0 | Query Salesforce with SOQL and create, read, update and delete records. |
| [SAP GUI](ooa-sap.md) | `ooa.sap` | 30 | 0 | 0 | Log on to SAP, run transactions, set and read fields, press buttons, read grids and the status bar through SAP GUI Scripting. |
| [Scripts](ooa-script.md) | `ooa.script` | 7 | 0 | 0 | Run PowerShell, Python, JavaScript and VBScript with arguments and captured output. |
| [ServiceNow](ooa-servicenow.md) | `ooa.servicenow` | 13 | 0 | 0 | Query, create and update ServiceNow records (incidents, requests, any table), add comments and attachments. |
| [Shared values](ooa-global_values.md) | `ooa.global_values` | 2 | 1 | 0 | Read shared values that many agents use, such as folders, thresholds or URLs. |
| [Slack](ooa-slack.md) | `ooa.slack` | 10 | 0 | 0 | Post messages to Slack channels and users. |
| [Snowflake](ooa-snowflake.md) | `ooa.snowflake` | 11 | 0 | 0 | Run SQL in Snowflake, read results into data tables, browse databases, schemas and tables, and load rows. |
| [SOAP Web Service](ooa-soap.md) | `ooa.soap` | 2 | 0 | 0 | Read a service's WSDL, call SOAP 1.1/1.2 web services and read the response as a dictionary. |
| [String](ooa-string.md) | `ooa.string` | 13 | 2 | 0 | Text manipulation: extract, replace, split, change case. |
| [System](ooa-system.md) | `ooa.system` | 8 | 0 | 0 | Read environment variables and runner information; lock, sign out, restart or shut down the runner. |
| [Terminal emulator (TN3270, TN5250, VT over SSH)](ooa-terminal.md) | `ooa.terminal` | 18 | 0 | 0 | Connect to IBM mainframe 3270 screens, IBM i (AS/400) 5250 screens or VT100/ANSI hosts over SSH, type into fields, press Enter and function keys, and read screen text. |
| [Trigger Loop](ooa-triggerloop.md) | `ooa.triggerloop` | 0 | 1 | 3 | Wait for events (new files, timer ticks, process starts) and run the loop's nested steps once per event. |
| [Web goal agent](ooa-web_agent.md) | `ooa.web_agent` | 3 | 0 | 0 | Reach a goal on a web page described in plain words: an AI model reads the page and clicks, types and selects until done. |
| [Window](ooa-window.md) | `ooa.window` | 13 | 2 | 0 | Find, activate, move, resize, minimise, maximise, close and capture application windows on Windows. |
| [Windows Credential Manager](ooa-credential_manager.md) | `ooa.credential_manager` | 4 | 1 | 0 | Read, list, save and remove generic credentials in the Windows Credential Manager of the agent's user account. |
| [Windows services](ooa-services.md) | `ooa.services` | 6 | 1 | 0 | Get the status of, start, stop and restart Windows services. |
| [Word (no Office needed)](ooa-word.md) | `ooa.word` | 15 | 0 | 0 | Open or create .docx documents, read text and tables, replace text, add paragraphs and tables, save. |
| [Work queues](ooa-workload.md) | `ooa.workload` | 10 | 1 | 1 | Add work items to a queue, take the next item, and mark items completed, failed or deferred with retries. |
| [Workday](ooa-workday.md) | `ooa.workday` | 7 | 0 | 0 | Search and read Workday workers and call Workday REST endpoints or RaaS reports. |
| [XML](ooa-xml.md) | `ooa.xml` | 9 | 0 | 1 | Query and change XML with XPath, convert to dictionaries and validate against XSD. |
| [Zendesk](ooa-zendesk.md) | `ooa.zendesk` | 23 | 0 | 0 | Handle Zendesk Support tickets, comments, users, organizations, groups, attachments and views. |

In all: 79 toolkits, 1060 actions, 47 conditions and 12 loop types.
