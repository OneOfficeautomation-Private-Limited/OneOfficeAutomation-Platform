# Document Automation (AI > Document Automation)

Document Automation reads business documents (invoices, receipts, purchase orders, bank statements, ID documents and
types you teach it), checks them, sends the unsure ones to a person, and delivers the results: to Excel, a folder, a
webhook or an agent. The screens are part of the Command Center; the reading is done by **DocFlow**, a separate
service (repository `oneoffice-docauto`, Python) that the Command Center talks to.

```
Browser ── /documents/ and /api/docflow/* ──> Command Center ── /api/docs/v1/* (service token, tenant, person) ──> DocFlow
                                                    ^                                                          │
Runner ── /api/agent/docflow/documents ─────────────┘   <── /api/docflow-callback/start-bot (service token) ───┘
```

## 1. Set it up (on premises)

1. **Install DocFlow** next to the Command Center (same server or one the Command Center can reach): follow
   `oneoffice-docauto/README.md` (Windows: `windows\install.ps1`, then `windows\start.ps1`; Docker: `docker compose up`).
   It listens on port 8085.
2. **Make a service token** (a long random value, for example from a password manager) and give it to both sides:
   - DocFlow: `DOCAUTO_API_TOKEN=<token>` in `config/local.env` (DocFlow then refuses every request without it).
   - Command Center: **One Agent Vault**: a vault folder (for example `docflow`) with a credential `service`, attribute
     `token` holding the same value.
3. **Tell DocFlow where the Command Center is**, for "Start a bot" deliveries: `DOCAUTO_PLATFORM_URL=http://<command
   center>:8720` in DocFlow's `config/local.env`.
4. **Command Center > Settings > Document Automation**: DocFlow address (empty = `http://127.0.0.1:8085`) and the
   service token as a vault reference (`docflow/service/token`). Restart nothing: it applies at once.
5. Open **AI > Document Automation**. A "not reachable" or "refused the token" message names what to fix.

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

## 6. Troubleshooting

| You see | Do |
| --- | --- |
| "The Document Automation service is not reachable" | Start DocFlow; check Settings > Document Automation (address). |
| "…refused the Command Center's token" | Put the same token in DocFlow (`DOCAUTO_API_TOKEN`) and in the vault credential. |
| A Start-a-bot delivery fails with "DOCAUTO_PLATFORM_URL is not set" | Set it in DocFlow's `config/local.env` and restart DocFlow. |
| Email inbox: "The mail server … is not known" / "does not answer" | Check the server name and port (993 for IMAP over TLS). |
| Email inbox: VAULT.DENIED | Make the person who set up the inbox a consumer of the vault folder, then save the process again. |
| A reviewer does not see a review task | They need `docs.review` and view access to the process's folder. |
