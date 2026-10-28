# 10 Extensibility and integration

## What this covers / who it is for

This document lists the ways other systems and developers can integrate with and extend ONEOFFICEAUTOMATION: the REST
API (with authentication, starting runs, reading results and the live change feed), API tasks for calling one agent
like a web service, the MCP endpoint for AI assistants, incoming webhooks and outgoing run callbacks, custom toolkits
built with the SDK, custom connectors imported from API descriptions, saved connections, the Git mirror of the agent
repository, lifecycle export, import and promotion between environments, the templates catalogue, and the command line
for automation pipelines. It is for integration developers, DevOps engineers and partners.

## Contents

1. [Integration points at a glance](#1-integration-points-at-a-glance)
2. [REST API](#2-rest-api)
3. [API tasks](#3-api-tasks)
4. [MCP for AI assistants](#4-mcp-for-ai-assistants)
5. [Webhooks in, callbacks out](#5-webhooks-in-callbacks-out)
6. [Triggers from other systems](#6-triggers-from-other-systems)
7. [Custom toolkits with the SDK](#7-custom-toolkits-with-the-sdk)
8. [Custom connectors and saved connections](#8-custom-connectors-and-saved-connections)
9. [Git mirror of the repository](#9-git-mirror-of-the-repository)
10. [Lifecycle: export, import, promote](#10-lifecycle-export-import-promote)
11. [Templates catalogue](#11-templates-catalogue)
12. [Command line and pipelines](#12-command-line-and-pipelines)
13. [Monitoring and SIEM integration](#13-monitoring-and-siem-integration)
14. [Extending the platform itself](#14-extending-the-platform-itself)
15. [Related documents](#related-documents)

---

## 1. Integration points at a glance

| Need | Use | Direction |
| --- | --- | --- |
| Start agents and read results from another program | REST API with an API key | In |
| Expose one agent as a simple web service | API task (own URL and token) | In |
| Let an AI assistant run approved agents | MCP at `/api/mcp` | In |
| Start an agent when another system posts an event | Webhook trigger `POST /api/hooks/{id}` | In |
| Be told when a run finishes | `callback_url` on the run | Out |
| React to mail, ServiceNow records or any HTTP feed | Server-side triggers | Out (polling) |
| Call a system that has no toolkit | REST/SOAP toolkits or a custom connector | Out (from runners) |
| Add new actions in Java | Custom toolkit (signed jar) | Extension |
| Keep agents in Git | Git mirror | Out |
| Move agents between environments | Lifecycle export/import/promote | Both |
| Share ready-made agents | Templates catalogue | In |
| Forward audit to a SIEM | Syslog | Out |

## 2. REST API

### 2.1 Authentication

| Method | How |
| --- | --- |
| API key | Create in the console (top bar > API keys; `POST /api/apikeys`); send `Authorization: Bearer ooak_...`. Keys have an optional expiry (up to 730 days) and act with their owner's roles |
| Session token | `POST /api/auth/login` `{username, password}` (then `auth/mfa` with a code if required), or exchange an API key with `POST /api/auth/token` `{api_key}`; send as Bearer |
| Browser cookie | Only for the console; requires `X-OOA-Request: 1` on changes |

Every call checks a permission of the caller's role and folder permissions. Errors are JSON
`{"error": {"code", "message", "suggestedFix", "retryable"}}` with the HTTP statuses described in
[04-command-center-internals.md](04-command-center-internals.md#3-request-handling).

### 2.2 Common calls

| Call | Purpose |
| --- | --- |
| `GET /api/repo?q=` | List agents the caller may see |
| `GET /api/repo/bot?path=&version=` | Read an agent's definition |
| `POST /api/repo/checkin` | Check in a new version (from Studio or a pipeline) |
| `POST /api/runs` `{bot_path, inputs, device_id? \| pool_id? \| run_as?, priority?, callback_url?, mode?, source: "api"}` | Start a run; `run_as_users` or `device_ids` lists start one run per target |
| `GET /api/runs/{id}?after=N` | Run state, result and events after sequence N |
| `POST /api/runs/{id}/{stop\|pause\|resume}` | Control a run |
| `POST /api/runs/{id}/rerun` | Run again with the same inputs |
| `GET /api/runs?...` | Search runs (by agent, runner, user, status, dates, text), paged |
| `/api/queues/{id}/items`, `/api/queues/{id}/export`, `POST /api/queues/import` | Add, list and manage work items; export and import queues |
| `POST /api/workflows/start`, `GET /api/workflows/runs?reference=` | Start a workflow; find a run by business reference |
| `GET /api/tasks` and related | Human tasks in the inbox |
| `GET /api/analytics`, `/api/analytics/export` | Run statistics, CSV |
| `GET /api/audit`, `/api/audit/export`, `/api/audit/verify` | Audit search, CSV, integrity |
| `GET /api/changes?since=TOKEN&wait=25` | Long poll that answers when something visible changed |
| `GET /api/health`, `/api/health/ready` | Liveness and readiness |

`GET /api/catalog` returns the names of all actions; Studio's `GET /studio/api/packages` returns the full manifests.

## 3. API tasks

An API task (`ApiTasks`) puts one agent behind its own address and token, for systems that should not hold a user's
API key:

```
POST /api/apitasks/{task id}/run
X-OOA-Task-Token: <token>
{"inputs": {"invoice_id": "4711"}}

200 {"status": "success", "run_id": "...", "outputs": {...}, "error": null}
202 {"status": "running", "run_id": "..."}        → GET /api/apitasks/{task id}/runs/{run id} with the same token
```

- The run uses the live version, on the task's runner or the least busy runner of its pool, as the task's creator.
- The call waits up to the task's time limit (at most 300 seconds); with 8 calls already waiting, new calls get 202 at
  once.
- Agents of kind `ApiTask` can run **serverless** inside the Command Center when they use only data, web-service and
  document toolkits (see [04-command-center-internals.md](04-command-center-internals.md#11-api-tasks-and-serverless-runs)).

## 4. MCP for AI assistants

The Command Center is an MCP server at `POST /api/mcp` (JSON-RPC 2.0, protocol `2025-06-18`). An administrator lists
the agents to expose (`mcp_agents`), the runner or pool to use (`mcp_runner`) and the time limit
(`mcp_timeout_seconds`). Clients authenticate with an API key, a session, or an OAuth access token from the company's
identity provider (`mcp_oauth_audience`, `mcp_oauth_scope`; discovery at `/.well-known/oauth-protected-resource`).
`tools/list` returns the exposed agents the caller may run with input schemas from their variables; `tools/call` runs
one as the caller. Details: [08-ai-architecture.md](08-ai-architecture.md#11-mcp-server).

## 5. Webhooks in, callbacks out

**Incoming webhooks**: a trigger of kind `webhook` gets an id and a secret (shown once, stored as SHA-256). Another
system posts JSON to `POST /api/hooks/{trigger id}` with header `X-OOA-Secret: <secret>`; the Command Center answers
202 and starts the trigger's agent with the payload as input. Webhook calls share the "calls" rate limit of 1,200 per
minute per address.

**Outgoing callbacks**: a run started with `callback_url` is reported when it finishes: `POST` of
`{run_id, status, outputs, error}` to that URL, up to 3 attempts in the background; failures are audited and never
affect the run. In SaaS the URL must be public (`NetGuard`).

Studio also has local triggers (file arrives, schedule, local webhook) in `triggers.json` for testing while Studio is
open (`TriggerService`).

## 6. Triggers from other systems

Without changing the other system, the Command Center can poll it (`TriggerPollers`):

| Kind | Source | Cursor |
| --- | --- | --- |
| `email` | IMAP mailbox (`ImapMailSource`) | Last UID |
| `outlook` | Microsoft 365 mailbox through Microsoft Graph (`GraphMailSource`) | Received time |
| `servicenow` | ServiceNow table query | Update time |
| `http_poll` | Any HTTP endpoint returning JSON; items selected with a JSON path (`PollHttp`, `JsonPath`) | Hashes of seen item ids |

Each polls at its own interval (at least 15 seconds) and starts one run per new item. Credentials are vault references.
Runner-side triggers (files, windows, processes, services, hotkeys) cover events on the runner's machine.

## 7. Custom toolkits with the SDK

Customers and partners add actions in Java:

1. Depend on the `sdk` module (no other OOA module is needed at compile time) and write a class with `@OoaPackage`,
   `@Action`, `@Input`, `@Output` (see [07-toolkit-architecture.md](07-toolkit-architecture.md)).
2. `ooa toolkit keygen --publisher acme` creates `acme.key` (keep it secret) and `acme.pub`.
3. Build the jar and sign it: `ooa toolkit sign --jar crm.jar --class com.acme.CrmPackage --key acme.key`.
4. `ooa toolkit inspect --jar crm.jar` shows id, version, publisher and actions.
5. An administrator trusts `acme.pub` (Toolkits > Publishers, `POST /api/toolkits/publishers`) and uploads the jar
   (`POST /api/toolkits/custom`).
6. Agents use the actions like any other; the jar travels with each run and is checked again on the runner. Several
   versions can be installed at once; agents choose with `requires.packages`.

For quick local tests, `ooa run FILE` and `ooa manifest` work with toolkits on the class path. Custom toolkits cannot be
uploaded in the SaaS service.

## 8. Custom connectors and saved connections

- **Custom connectors**: import an OpenAPI 3.x, Swagger 2.0 or Postman v2.1 file in Studio or with
  `ooa.connector/import_connector`; call operations with `ooa.connector/call_operation`. Supported authentication:
  API key, basic, bearer, OAuth 2 client credentials, AWS Signature V4. Definitions live in
  `connectors/<name>.connector.json`.
- **REST and SOAP toolkits** (`ooa.rest`, `ooa.soap` with WSDL support) call any service directly.
- **Saved connections** (`connections` table, `PUT /api/saved-connections/{name}`; `connections.json` in a workspace)
  store a toolkit's address and other common inputs plus vault references, so steps choose a connection by name instead
  of repeating settings. `GET /api/connection-kinds` tells which inputs each toolkit's connections hold.

## 9. Git mirror of the repository

`GitSync` mirrors the agent repository to a Git remote (GitHub, GitLab, Azure DevOps or any Git server) using JGit:

- Settings: `git_remote_url` (`https://`, or `file:` for a local test remote), `git_branch` (default `main`),
  `git_username`, `git_token_ref` (a vault reference to the access token), `git_auto_push` (1: sync after every
  check-in, production change or deletion).
- Each sync writes the latest version of every agent, workflow and form as indented JSON under `agents/`, plus
  `ooa-repository.json` with each item's latest and live production version and SHA-256. It commits only when something
  changed, with the change's comment and author, and pushes.
- The Command Center stays the source of truth: changes made in Git are not read back and are overwritten by the next
  sync. The working copy is `<data>/git-sync`.
- Routes: `GET /api/git/status`, `POST /api/git/sync`. In SaaS the remote must be public and `https://` or SSH.

## 10. Lifecycle: export, import, promote

`Lifecycle` moves agents between Command Centers (for example development, test, production):

| Operation | Route | Behaviour |
| --- | --- | --- |
| Export | `POST /api/lifecycle/export` | A bundle of chosen agents at a version (latest, production, a number or a label) and optionally every agent they call (production, else latest), with each version's exact content and SHA-256 and a checksum over the list |
| Import | `POST /api/lifecycle/import` | Checks format and checksums, validates every agent as check-in does, then stores all or none in one transaction; existing agents are skipped or get a new version; history is never replaced; "as production" respects production approval |
| Promote | `POST /api/lifecycle/promote` | Exports here and imports into another Command Center over its API, signing in with an API key read from the vault (never typed) |

Permissions `lifecycle.export` and `lifecycle.import` are administrator-only unless a custom role grants them.

## 11. Templates catalogue

`Templates` lets people install ready-made agents into a folder of their repository:

- **Vendor catalogue**: a feed `{format: ooa-templates-1, payload, signature}` signed with the vendor key and published
  with each release (setting `template_catalog_url`, `off` for none). A feed file can be uploaded for Command Centers
  without internet access. A feed with a bad signature is refused as a whole.
- **Region catalogue** (SaaS): templates shared by tenants and approved by the region's operators (see
  [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md#10-shared-templates)).
- Installing uses the same checks as lifecycle import (checksums, validation, all or nothing).
- Routes: `GET /api/templates` (what can be installed), `POST /api/templates/catalogue` (upload a feed file),
  `POST /api/templates/install` `{source, id, folder}`, `POST /api/templates/share`.

## 12. Command line and pipelines

`ooa` (picocli; `ooa --help`, `ooa <command> --help`, `source <(ooa completion)` for shell completion):

| Command | Use in automation |
| --- | --- |
| `ooa validate FILE` | Check an agent in a build pipeline (exit code) |
| `ooa run FILE [inputs]` | Run an agent locally with a console log |
| `ooa manifest [--summary]` | Print toolkit manifests |
| `ooa conformance DIR` | Run the conformance cases |
| `ooa release verify MANIFEST` | Check downloaded release files |
| `ooa license show FILE` | Check a licence file |
| `ooa server`, `ooa control-room`, `ooa studio` | Start the servers |
| `ooa agent register`, `agent start`, `agent diagnose`, `agent install-task`, `agent move` | Runners |
| `ooa db configure`, `set-password`, `init`, `migrate`, `copy`, `test`, `script` | Database |
| `ooa tls ...`, `ooa service ...`, `ooa backup ...`, `ooa check` | Operations |
| `ooa toolkit keygen`, `sign`, `inspect` | Custom toolkits |
| `ooa region ...`, `ooa directory start` | SaaS operators |
| `ooa assistant` | Desktop assistant |
| `ooa browser-connector install`, `status`, `uninstall`; `ooa native-host` | Browser connector |

The GraalVM native executable (`gradlew :cli:nativeExecutable`) contains `validate`, `run`, `manifest`, `conformance`,
`license show` and `release verify`; it starts at once and needs no Java runtime, which suits CI pipelines.

## 13. Monitoring and SIEM integration

- **Health**: `GET /api/health` (liveness) and `GET /api/health/ready` (503 when not ready); `/healthz` on a SaaS
  region's own address.
- **Audit to SIEM**: `syslog_target` (host:port) and `syslog_protocol` (udp or tcp) forward every audit entry as RFC
  5424 syslog, including hourly audit checkpoints; `POST /api/settings/syslog-test` sends a test message.
- **Run data**: `GET /api/analytics` and dashboards (`/api/dashboards`) with KPIs recorded from agents' `kpi` outputs.
- **Mail**: SMTP settings (`mail_smtp_*`) for licence reminders and notifications; `POST /api/settings/mail-test`.

## 14. Extending the platform itself

| Extension | Where | Pattern |
| --- | --- | --- |
| New first-party toolkit | `packages` or a new optional module | Annotated class, registration in `FirstPartyPackages`, fake-server test, catalogue row, `GROUP_LIST` row |
| New Command Center feature | `control-room` | New `SCHEMA_Vn`, service method with `require`, `?` SQL and audit, a route in `ControlRoomServer.api`, a page in `cr.js`, an end-to-end test |
| New Studio capability | `cli` | A route in `StudioServer` (loopback only), UI in `studio.js`; engine hooks only through interfaces in `runner` |
| New trigger kind | `agent` (`LocalTriggers`) or `control-room` (`TriggerPollers`) | Add to `TriggerKinds`; keep the cursor in `triggers.last_hash`; pass the event as `trigger_<kind>` and `trigger_event` |
| New model provider | `packages` (`LlmClient`) | See [08-ai-architecture.md](08-ai-architecture.md#13-adding-a-provider) |
| New external vault | `control-room` (`ExternalVaults`) | A `kind` with its required settings and a read method |

Keep to the module rules checked by `ArchitectureTest` ([02-components-and-modules.md](02-components-and-modules.md#4-dependencies-between-modules)).

## Related documents

- [README.md](README.md)
- [01-system-overview.md](01-system-overview.md)
- [02-components-and-modules.md](02-components-and-modules.md)
- [03-agent-model-and-runner.md](03-agent-model-and-runner.md)
- [04-command-center-internals.md](04-command-center-internals.md)
- [05-data-model.md](05-data-model.md)
- [06-security-architecture.md](06-security-architecture.md)
- [07-toolkit-architecture.md](07-toolkit-architecture.md)
- [08-ai-architecture.md](08-ai-architecture.md)
- [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md)
