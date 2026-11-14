# Document Automation (AI > Document Automation)

Document Automation reads business documents (invoices, receipts, purchase orders, bank statements, ID documents and
types you teach it), checks them, sends the unsure ones to a person, and delivers the results: to Excel, a folder, a
webhook or an agent. The screens are part of the Command Center; the reading is done by **DocFlow**, a separate
service (repository `oneoffice-docauto`, Python) that the Command Center talks to.

```
Browser ── /documents/ and /api/docflow/* ──> Command Center ── /api/docs/v1/* (service token, tenant, person) ──> DocFlow
                                                    ^                                                          │
Runner ── /api/agent/docflow/documents ─────────────┤   <── /api/docflow-callback/start-bot (service token) ───┤
AI assistant ── /api/mcp (documents_ tools) ────────┘   <── /api/docflow-callback/events (service token) ──────┘
```

## 1. Set it up (on premises)

1. **Install DocFlow** next to the Command Center (same server or one the Command Center can reach): follow
   `oneoffice-docauto/README.md` (Windows: `windows\install.ps1`, then `windows\start.ps1`; Docker: `docker compose up`).
   It listens on port 8085.
2. **Make a service token** (a long random value, for example from a password manager) and give it to both sides:
   - DocFlow: `DOCAUTO_API_TOKEN=<token>` in `config/local.env` (DocFlow then refuses every request without it).
   - Command Center: **One Agent Vault**: a vault folder (for example `docflow`) with a credential `service`, attribute
     `token` holding the same value.
3. **Tell DocFlow where the Command Center is**, for "Start a bot" deliveries and document events: `DOCAUTO_PLATFORM_URL=http://<command
   center>:8720` in DocFlow's `config/local.env`.
4. **Command Center > Settings > Document Automation**: DocFlow address (empty = `http://127.0.0.1:8085`) and the
   service token as a vault reference (`docflow/service/token`). Restart nothing: it applies at once.
5. Open **AI > Document Automation**. A "not reachable" or "refused the token" message names what to fix.

### 1.1 DocFlow starts with One CommandCenter

When DocFlow is installed on the same server, you do not start it yourself. One CommandCenter (`ooa server` or
`ooa control-room`) looks for it (`OOA_DOCFLOW_HOME`, the `home` line of `ooa-docflow.properties`, the installer's
`DocFlow` folder next to the program, or `C:\OOA\oneoffice-docauto`) and then:

- when no address is set yet, records DocFlow's address and token (the token encrypted);
- when nothing answers at that address, starts DocFlow in the background (its own `windows\start.ps1`, hidden), with
  its log in `<data>\logs\docflow.log`; when DocFlow stops on its own before it ever answered (its database can be
  slow the first time), starts it again, at most twice;
- stops DocFlow when One CommandCenter stops, asking its database to shut down properly first, so the next start
  needs no repair;
- when DocFlow stops while its database is still starting (after an unclean stop the database repairs itself, which
  can take a minute or more), waits until the database says it is ready (up to 3 minutes) and starts DocFlow again,
  at most three times. Meanwhile **AI > Document Automation** says "Document Automation is starting" instead of "not
  reachable".

It does not do this in the SaaS service, nor when **Settings > Document Automation > DocFlow address** points at
another server. To switch it off, clear **Settings > Document Automation > Start with One CommandCenter** (on by
default) or set `OOA_DOCFLOW_AUTOSTART=false`; then start DocFlow yourself.

**AI > Document Automation > Overview** shows ten columns at a time: **Activity** (the default) or **Set-up**, chosen
with the switch above the table, so it fits the screen without scrolling sideways.

On the review screen, **Previous task** and **Next task** move between the documents waiting for review. A document
type's field can have a **Format check** chosen from common formats (digits only, an amount with 2 decimals, dates,
e-mail, phone, IBAN, GSTIN, PAN, EIN, VAT number, invoice number) or **Own pattern…**; the dialog says in words what
the format accepts.

In the **SaaS** service the region's operator sets `OOA_DOCFLOW_URL` and `OOA_DOCFLOW_TOKEN` for the region's Command
Centers (tenants cannot point it elsewhere), and DocFlow's `DOCAUTO_PLATFORM_URL` may contain `{tenant}`, replaced by
the tenant's address name.

## 2. Who can do what

| Permission | Roles that have it | Allows |
| --- | --- | --- |
| `docs.view` | viewer, bot runner, bot creator, admin | See processes, documents, document types, reference data, engines |
| `docs.review` | bot runner, bot creator, admin | Review documents (Task inbox and the review screen) |
| `docs.manage` | bot creator, admin | Add documents; change processes, document types, reference data; Test lab |
| `docs.engines` | admin (or a custom role) | Engines, routing, cloud opt-in, engine keys |

**Folders.** A process can be put in a platform folder (the folders of the Agent repository). That folder's
permissions then apply on top: **view** to see the process, its documents and its review tasks, **run** to add
documents (also from agents), **edit** to change the process. A process without a folder is open to everyone with the
permissions above. Folder permissions are set where they are for agents (Agent repository > folder > Permissions).

**Tenants.** Each tenant's documents, processes, document types, reference data and routing (cloud opt-in, retries,
thresholds) are kept apart by DocFlow (`<data folder>/tenants/<tenant>`). Engine keys and the deployment profile are
set for the whole DocFlow server.

## 3. How documents come in

- **Upload**: Add documents (or drop files on the Inbox). Choose a process, or let DocFlow recognise each document.
- **Watched folder**: a process's Intake stage; DocFlow reads new files there while the process is live.
- **Email inbox**: a process's Intake stage. Microsoft 365 (an app registration with Mail.Read) or IMAP; the password
  or client secret is a vault reference the person setting it up must be allowed to use. The Command Center reads the
  inbox of live processes every minute (the first check only marks where the inbox is: older mail is not read) and
  sends new PDF, PNG, JPG and TIFF attachments to the process. The Intake stage shows the last check and any problem.
- **From an agent**: the **Document Automation** toolkit's **Send document to process** action (process by name or
  id; optionally wait for the status and fields). The agent must run from the Command Center; the document goes in as
  the run's user, who needs `docs.view` and run access to the process's folder.
- **From an AI assistant**: the `documents_send` tool (section 7).

**Documents from the Command Center's triggers.** Any trigger (Manage > Triggers) can start an agent whose job is to
hand a document to a process with **Send document to process**. Give the agent an input named as the trigger's and
pass it on:

| Trigger | The agent's input | What goes in Send document to process > path |
| --- | --- | --- |
| File arrives in a folder (on a runner) | `trigger_file` (the file's full path) | `trigger_file` as it is |
| New email (IMAP or Microsoft 365) | `trigger_email` (from, subject, `uid`…; not the attachments) | First the Email toolkit's **Save attachments** (email id = `trigger_email.uid`, name pattern `*.pdf`), then Send document to process for each saved file |
| Webhook | `payload` (the request body) | A file the caller put on a share, or one the agent downloads first (Browser toolkit **Download file from address**) |
| Universal poller | `trigger_item` | As for a webhook: download the item's file first |

For a mailbox that only brings documents, a process's own **Email inbox** (above) is simpler: no agent is needed. A
process's **Watched folder** reads a folder on the DocFlow server; the "File arrives" trigger is for a folder on a
runner's machine.

## 4. Review in the Task inbox

A document that needs a person becomes a **Review document** task in **Task inbox**, for everyone with `docs.review`
who may see its process's folder, due at the process's review target. Opening it opens the review screen; approving
or rejecting closes the task as that person (it cannot be ticked off without reviewing). The Command Center checks
DocFlow's review queue every 20 seconds.

## 5. Results to agents ("Start a bot")

A process's Deliver stage can start an agent for each document (done, approved, rejected…). The agent runs as the
person who saved the delivery (they must be allowed to start it), on their default runner or a runner pool, and gets
the inputs it declares among: `document` (everything), `document_id`, `process`, `status`, `file_name`, and any field
by its name (`invoice_number`, `total`…).

## 6. Document events start agents (triggers "On Document Automation")

Manage > Triggers > Create trigger (and the Triggers group among OneAgentStudio's actions) has a group **On Document
Automation**:

| Event | Starts the agent when |
| --- | --- |
| Document processed | a document has been read and checked |
| Document needs review | a document waits for a person's review |
| Review approved / Review rejected | a reviewer approves or rejects a document |
| Delivery failed | a process could not deliver a document's results |
| Review overdue | a review is past its process's review target |

Settings: **Process** (its name or id; empty: any process) and **Document type** (empty: any type). As for other
triggers you choose the agent (its live version runs), the runner it runs on and, if you like, a person it runs as.
Nothing is checked on an interval: DocFlow tells the Command Center at once (`POST /api/docflow-callback/events`, with
the service token), so "Check now" is not offered.

The agent gets the inputs it declares among: `document_id`, `process`, `process_id`, `status`, `event`, `file_name`,
`doc_type`, any field by its name (`invoice_number`, `total`…), and `trigger_event` (everything DocFlow said). A field
never replaces one of the named inputs.

**Who may.** The trigger fires with its owner's permissions: the owner must be an active user with `docs.view` who may
see the process's folder. Otherwise the trigger is skipped: the Audit Trail has a `trigger.fire` entry marked denied,
and the trigger shows the reason as its last problem. Each agent started is in Agent Activity and the Audit Trail
(`run.start`, source `trigger:<id>`).

"Start a bot" deliveries (section 5) and these triggers both start agents: a delivery belongs to one process and is set
up there; a trigger is set up in the Command Center and can listen to every process.

## 7. AI assistants (MCP)

Assistants connected to the Command Center (Settings > AI assistants (MCP)) get Document Automation's tools besides the
agents offered there. Each call runs as the signed-in person, with their permissions and the processes' folders, and
is in the Audit Trail (`mcp.tool`):

| Tool | Does | Needs |
| --- | --- | --- |
| `documents_list_processes` | The processes the person may see | `docs.view` |
| `documents_send` | Sends one file (Base64, with its name) to a live process; answers at once with the document id | `docs.manage`, run access to the folder |
| `documents_status` | Still being read, read, waiting for review, approved, rejected or failed | `docs.view` |
| `documents_result` | The fields read, each with its value and confidence | `docs.view` |
| `documents_my_reviews` | The documents waiting for review that the person may review | `docs.review` |
| `documents_review` | Approves (optionally with corrected values) or rejects one; its Task inbox task closes | `docs.review` |

No tool waits for the reading: send, ask for the status until it is done, then ask for the result. **Settings > AI
assistants (MCP) > Document Automation tools** turns them off for everyone.

## 8. Templates with processes

An agent template (Build > Agent Templates) can carry Document Automation processes with its agents. When you **share
an agent** as a template (SaaS regions), tick the processes that go with it. The template carries each process's
settings and the document types it reads, but not what belongs to your company: its email inbox, its watched folder,
the review webhook, passwords and tokens of its deliveries, and what its document types learned from your documents.

**Installing** such a template (the person needs `docs.manage` and edit access to the chosen folder) installs the
agents first, then:

- adds the document types this company does not have yet;
- creates each process as a **draft** in the chosen folder (a process in "Finance / Payables" installed into "Kits"
  goes to "Kits / Finance / Payables"); a process of the same name already in that folder is kept as it is;
- points its "Start a bot" deliveries at the template's agents as installed, run as the person installing (who must be
  allowed to start them); deliveries to other agents, webhooks, folders and Excel files are switched off, to be checked
  and switched on;
- switches off lookup rules on reference data this company does not have (upload the data, then switch them on).

Then open the drafts in Document Automation, add an email inbox or watched folder, and make them live.
ONEOFFICEAUTOMATION's own catalogue can carry processes the same way. Templates do not carry triggers: create those
after installing.

## 9. Troubleshooting

| You see | Do |
| --- | --- |
| "The Document Automation service is not reachable" | Check that **Start with One CommandCenter** is on (Settings > Document Automation) and read `<data>\logs\docflow.log`, or start DocFlow yourself; check the address there too. |
| "…refused the Command Center's token" | Put the same token in DocFlow (`DOCAUTO_API_TOKEN`) and in the vault credential. |
| A Start-a-bot delivery fails with "DOCAUTO_PLATFORM_URL is not set" | Set it in DocFlow's `config/local.env` and restart DocFlow. |
| Email inbox: "The mail server … is not known" / "does not answer" | Check the server name and port (993 for IMAP over TLS). |
| Email inbox: VAULT.DENIED | Make the person who set up the inbox a consumer of the vault folder, then save the process again. |
| A reviewer does not see a review task | They need `docs.review` and view access to the process's folder. |
| An "On Document Automation" trigger shows "…may not see the process's folder" | Give the trigger's owner view access to that folder, or make someone who has it the owner. |
| An "On Document Automation" trigger never fires | Check DocFlow's `DOCAUTO_PLATFORM_URL`, the trigger's Process (name or id) and Document type, and that it is not paused. |
| An assistant does not offer the documents_ tools | Settings > AI assistants (MCP) > Document Automation tools must be on, Document Automation set up, and the person needs `docs.view`. |
