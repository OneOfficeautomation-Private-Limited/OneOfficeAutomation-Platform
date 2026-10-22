# Reference product platform: gap list (Control Room, AI, security)

Sources, read 2026-10-23:
- the reference product's public documentation (enterprise overview, Control Room API index, AI package, security architecture);
- the API index published by the Community Edition Control Room (`/swagger/`), which lists every module and endpoint.

Everything below is a summary written in our own words. No text or files from the reference product are copied into the product.

## 1. What the reference platform offers (overview)

- **Authoring:** a visual designer with flow, list and dual views. A recorder for Windows, Citrix and SAP. JavaScript, Python and VBScript. Packages as building blocks. A no-code connector builder for REST APIs.
- **Running:** unattended and attended runners. "API tasks" that run in the cloud without a local runner, started by URL plus token. Device pools.
- **Business users:** a co-pilot where people start automations, answer requests to resume them, and escalate. Teams. Attended plug-ins for business applications.
- **Operations:** dashboards for runs, durations, failures, licences and devices. Bot analytics with variable profiles.
- **Governance:** code analysis with policy rules. Lifecycle management (export, import, promote between Control Rooms). An app store. CoE pipeline and ROI.
- **AI:** an agent studio with custom model connections, guardrails and governance. A generator that builds automations from a description. AI packages for hosted model endpoints (authenticate first, then invoke).
- **Documents and discovery:** document automation (IDP). Process discovery, with redaction of personal data before upload.
- **Security:** least privilege and separation of duties, with separate control planes for building and running. Role-based access down to individual bots, runners and domains. A credential vault (lockers, owners/managers/participants/consumers, keys, a master key) plus third-party vaults. TLS everywhere. Versioned bots with rollback. Full auditing with non-repudiation, sent on to a SIEM. Controls mapped to NIST 800-53, ISO 27002 and SOX.
- **Deployment:** SaaS, the customer's own servers, the customer's own cloud subscription, or the free Community Edition.

## 2. Control Room API: where we stand

Our Control Room (Command Center) already covers the following, with routes under `/api/`:

- sign-in, SSO, sign-out and API keys;
- users and roles (RBAC);
- devices, device pools and registration keys;
- runs: search, detail, stop, pause, resume and rerun;
- deploy to a device or pool, with a callback;
- schedules, including activate/deactivate and run now;
- triggers, including webhooks and polling;
- queues with work items, SLA and retry/defer;
- lockers and credentials, with external vault links;
- global values;
- audit, including a hash-chain check;
- repository: check-out/check-in, versions and the production version;
- model connections and AI governance;
- an MCP server, workflows (processes) and human tasks;
- holiday calendars;
- toolkits (packages) and where they are used;
- the dashboard.

We go beyond the reference with the MCP server, the AI governance view, audit tamper checks, webhook and polling triggers, calendars, queue SLA, rerun, and ROI minutes saved.

Gaps, ranked by value for an enterprise customer:

| # | Gap | Reference capability | Status |
| --- | --- | --- | --- |
| 1 | Bot lifecycle | Export a bot with its child bots and dependencies (by version or label); import with overwrite/skip; promote to another Control Room; job status; download | Done (2026-10-23) |
| 2 | Repository folders and permissions | Folder objects (create, rename, delete); role permissions per folder; delete and recover bots; version labels; file dependencies and parents | Done, except renaming folders (2026-10-23) |
| 3 | Run-as users | Each user is bound to a default device; deploy to users (several at once); run mode (attended, unattended, headless) | Done (2026-10-23) |
| 4 | Work queues, complete | Work item models (field schemas); get, update and delete items; bulk delete; items from CSV/Excel; export items; queue owners, participants and consumers; queue export/import | Done, except Excel files and queue export/import (2026-10-23) |
| 5 | Credential vault model | Whole-credential get and delete; delete a locker; locker managers and participants; each user's own attribute values; credential owner; vault mode and master-key status, unlock and rotation | Done (2026-10-23); unlock with a typed master key not needed: the key comes from the key file or OOA_MASTER_KEY |
| 6 | Audit search | Field filters, date range, sort, paging, total, CSV export | Done (2026-10-23) |
| 7 | Teams | Teams with members, used to scope folders, lockers and queues | Done (2026-10-23) |
| 8 | Operations analytics | Duration distribution, longest running, most failed, total run time, per-bot statistics, CSV exports | Done (2026-10-23) |
| 9 | Token lifecycle | Exchange an API key for a token; refresh; verify | Done (2026-10-23) |
| 10 | Uniform API | Get one by id for users, roles, pools, queues, schedules, triggers and devices; delete device and user; list filters and paging | Done (2026-10-23) |
| 11 | Scheduler extras | Activate or deactivate several at once; change owner; run-as mapping for triggers | Done (2026-10-23) |
| 12 | Code analysis | Analyse a bot against policy rules (on demand and at check-in), with stored reports | Done (2026-10-23) |
| 13 | Process composer helpers | A process's first form; a request by reference | Done (2026-10-23) |
| 14 | API tasks | A per-agent URL and scoped token that runs an agent and waits for the result | Done (2026-10-23); runs on a runner or pool, not inside the Command Center |
| 15 | Lower priority | Package-version usage; bulk package version update; licence endpoints; variable profile and log purge (retention); migration from other products | Missing |

## 3. Next checks in the Community Edition (needs a sign-in)

- Control Room screens for each of the gaps above: folder permissions, lockers (members), queues (work item models), lifecycle (export/import), run-as users, teams, code analysis policies, the Operations dashboards.
- The AI Agent Studio screens: model connections, guardrails and the governance view.
- The AI package actions in the bot editor: their inputs and outputs. The documentation pages for these do not load.
