# 05 Data model

## What this covers / who it is for

This document describes the Command Center's database: the supported engines, how the schema is created and
upgraded, every table grouped by business area with its purpose and key columns, what is kept outside the database,
and how a SaaS region gives each tenant its own schema. It is for database administrators, developers adding features
and security reviewers who need to know where sensitive data is stored. Table and column names come from
`control-room/.../Db.java` (schema versions 1 to 23), `Tenants.java` and `RegionDirectory.java`.

## Contents

1. [Supported databases](#1-supported-databases)
2. [Access layer](#2-access-layer)
3. [Schema versions and migrations](#3-schema-versions-and-migrations)
4. [Tables by area](#4-tables-by-area)
5. [Data kept outside the database](#5-data-kept-outside-the-database)
6. [Sensitive columns](#6-sensitive-columns)
7. [Schema per tenant and the region catalog](#7-schema-per-tenant-and-the-region-catalog)
8. [Conventions for changing the schema](#8-conventions-for-changing-the-schema)
9. [Related documents](#related-documents)

---

## 1. Supported databases

| Engine | Use | Notes |
| --- | --- | --- |
| Microsoft SQL Server 2019 or later | Production; required before a customer install can take a licence (PostgreSQL also accepted) | `SqlServerDialect` translates the portable SQL; SQL login or Windows sign-in (service account, gMSA) |
| PostgreSQL 14 or later | Production; the only engine for SaaS regions | Schemas per tenant |
| H2 2.2, embedded | Evaluation and tests; default without `config/ooa-db.properties` | File `controlroom.mv.db` in the data folder, PostgreSQL mode |

The settings file `config/ooa-db.properties` (`DbConfig`) names `engine` (`h2`, `sqlserver`, `postgresql`), host,
port, optional instance, database, runtime `user` and `password`, `setup.user` and `setup.password`, `tls`
(`off`/`required`), `tls.ca`, `tls.trustAnyCertificate`, `pool.max` (default 20 in the sample) and
`pool.timeoutSeconds`. Passwords are stored as `enc:v1:` values encrypted with the vault master key, or as
`env:VARIABLE`; a clear-text password is refused at start.

Two accounts are used: a **setup** account that creates and upgrades the schema (`ooa db init`, `migrate`, `copy`)
and a **runtime** account with data read/write rights only.

## 2. Access layer

`Db` is a small JDBC layer with no ORM:

- a connection pool (`borrow`/`giveBack`, validity check, `pool.max`);
- `tx(work)` for transactions; nested calls join the outer transaction; any exception rolls back;
- `query`, `one`, `update` with `?` placeholders only (enforced by `ArchitectureTest`);
- SQL states starting with `23` become `CR.CONFLICT`; other errors `CR.DB_ERROR`;
- timestamps come back as ISO-8601 text, CLOBs as strings, column names in lower case;
- a **change counter**: writes that the console shows (not heartbeats, sessions, settings, cluster or licence-usage
  bookkeeping) wake the `/api/changes` long poll;
- `inSchema(name, poolMax)` opens a pool bound to another schema (PostgreSQL and H2), creating it when missing;
  `dropSchema(name)` removes one; schema names must match `^[a-z][a-z0-9_]{0,62}$`.

`SqlServerDialect` rewrites statements for SQL Server: `TRUE`/`FALSE` to `1`/`0`, `LIMIT`/`OFFSET` to
`OFFSET … FETCH` or `TOP`, text types to Unicode, booleans to `BIT`, and brackets the reserved column name
`external`. Parameter order is adjusted when the rewrite moves them.

## 3. Schema versions and migrations

- The schema is a list of arrays `SCHEMA_V1` … `SCHEMA_V23` in `Db.java`; `Db.LATEST = 23`.
- The applied version is stored in `schema_version`. At start the missing versions are applied in order.
- H2 upgrades itself. SQL Server and PostgreSQL stop with `CR.DB_UPGRADE_NEEDED` until `ooa db migrate` runs with the
  setup account.
- A database at a higher version than the program stops with `CR.DB_NEWER`.
- `ooa db script --engine sqlserver` prints the full DDL for a DBA who prefers to create it by hand.

| Version | Added |
| --- | --- |
| 1 | users, sessions, audit, bots, bot_versions, devices, registration_keys, runs, run_events, schedules, triggers, lockers, credentials, global_values |
| 2 | settings, roles, api_keys, device_pools, queues, queue_items, model_connections, holiday_calendars, toolkit_settings; run callback, priority, queue item; schedule calendars and pools; trigger config and status; minutes saved; user e-mail |
| 3 | external vault links on lockers, ai_calls, workflow_runs, human_tasks, users.sso_subject |
| 4 | repo_folders, folder_permissions, soft delete of agents, bot_labels, user_devices, run_as and run_mode on runs, work_item_models, queue model, queue_members |
| 5 | teams, team_members, locker managers and participants, credential_meta, credential_user_values, audit and run indexes |
| 6 | run-as for schedules and triggers, workflow reference, code_policy, code_analysis, api_tasks |
| 7 | repo_files, credential attribute details, user_roles, user name details, changeable shared values and user_global_values, runner log level and type, model connection roles and type, prompt_skills, prompt_skill_versions |
| 8 | connections (saved connections) |
| 9 | licenses, license_assignments |
| 10 | runner screen state and after-run action |
| 11 | two-step sign-in columns on users |
| 12 | production_requests |
| 13 | runner build and update request |
| 14 | temporary runners, runner secret rotation |
| 15 | toolkits each runner has |
| 16 | audit chain algorithm |
| 17 | password history and last change |
| 18 | cluster_lease, cluster_nodes, auth_pending |
| 19 | license_usage; model connection budgets and limits; ai_calls.connection |
| 20 | test_sets, test_runs, test_results |
| 21 | PII analyzer and content check per model connection |
| 22 | kpi_values, dashboards |
| 23 | toolkit_publishers, custom_toolkits |

## 4. Tables by area

### 4.1 People and access

| Table | Purpose | Key columns |
| --- | --- | --- |
| `users` | People who sign in | `id`, `username` (unique), `display_name`, `first_name`, `last_name`, `email`, `password_hash` (PBKDF2), `role`, `enabled`, `must_change`, `failed`, `locked_until`, `last_login`, `sso_subject`, `mfa_enabled`, `mfa_secret` (vault-encrypted), `mfa_recovery` (hashes), `mfa_last_step`, `password_history`, `password_changed` |
| `user_roles` | Further roles per person | `username`, `role` |
| `sessions` | Signed-in sessions | `token_hash` (SHA-256), `user_id`, `created`, `expires`, `ip` |
| `roles` | Custom roles | `name`, `description`, `permissions` |
| `api_keys` | Personal API keys (`ooak_…`) | `id`, `user_id`, `name`, `key_hash` (unique), `expires`, `last_used` |
| `teams`, `team_members` | Teams with members and leads | `team_id`, `username`, `team_role` |
| `auth_pending` | Sign-ins in progress shared by nodes | `token` (kind:token), `kind`, `data`, `expires` |

### 4.2 Agent repository

| Table | Purpose | Key columns |
| --- | --- | --- |
| `bots` | One row per agent, workflow or form | `path` (PK, for example `Finance/Pay.flow.json`), `bot_id` (unique), `name`, `latest_version`, `production_version`, `locked_by`, `locked_at`, `minutes_saved`, `deleted_at`, `deleted_by`, `updated_by` |
| `bot_versions` | Every checked-in version | `path`, `version`, `content` (JSON), `sha256`, `comment`, `author`, `created` |
| `bot_labels` | Named version pointers | `path`, `label`, `version` |
| `repo_folders` | Folders | `path`, `description` |
| `folder_permissions` | Access per folder | `folder`, `principal` (`role:`, `team:`, `user:`), `access` (view, run, edit, manage) |
| `repo_files` | Non-agent files | `path`, `size`, `sha256`, `content_type`, `version`, `updated_by` |
| `production_requests` | Approval before going live | `path`, `version`, `requested_by`, `status`, `decided_by`, `note` |
| `code_policy` | Code analysis rules (one row) | `json_value` |
| `code_analysis` | Report per analysed version | `path`, `version`, `errors`, `warnings`, `violations` |
| `test_sets`, `test_runs`, `test_results` | Agent tests and scores | `cases`, `total`, `passed`, `status`, `detail` |

### 4.3 Runners and runs

| Table | Purpose | Key columns |
| --- | --- | --- |
| `devices` | Runners | `id`, `name`, `hostname`, `os`, `agent_version`, `build`, `secret_hash`, `secret_prev_hash`, `secret_prev_until`, `secret_set`, `enabled`, `last_seen`, `log_level`, `device_type`, `session_state`, `after_run`, `lifespan` (temporary), `update_requested`, `toolkits` |
| `registration_keys` | One-time runner keys | `key_hash`, `created_by`, `expires`, `used_by`, `temporary` |
| `device_pools` | Runner pools | `id`, `name`, `devices` (list) |
| `user_devices` | Each run-as user's default runner | `username`, `device_id` |
| `runs` | Every run | `id`, `bot_path`, `version`, `device_id`, `status` (queued, running, success, failed, stopped), `inputs`, `outputs`, `error`, `requested_by`, `run_as`, `run_mode`, `source`, `priority`, `queue_item_id`, `callback_url`, `cancel_requested`, `pause_requested`, `created`, `started`, `finished`, `duration_ms`, `steps` |
| `run_events` | Event stream per run | `run_id`, `seq`, `event` (JSON, cut at 19,000 characters) |

### 4.4 Automation

| Table | Purpose | Key columns |
| --- | --- | --- |
| `schedules` | Timed starts | `kind`, `every_minutes`, `at_time`, `days`, `time_zone`, `holiday_calendar`, `device_id`, `pool_id`, `run_as`, `inputs`, `next_run`, `last_run`, `enabled` |
| `holiday_calendars` | Dates to skip | `name`, `dates` |
| `triggers` | Event starts | `kind`, `device_id`, `folder`, `pattern`, `config` (JSON, vault references only), `secret_hash` (webhooks), `run_as`, `last_fired`, `fire_count`, `last_error`, `last_hash` (cursor) |
| `queues` | Work queues | `bot_path`, `pool_id`, `run_as`, `sla_minutes`, `max_attempts`, `processing`, `model_id` |
| `queue_items` | Work items | `queue_id`, `reference`, `data`, `status`, `priority`, `attempts`, `run_id`, `device_id`, `error`, `result` |
| `work_item_models` | Typed item fields | `name`, `fields` |
| `queue_members` | Queue roles | `queue_id`, `principal`, `member_role` (owner, participant, consumer) |
| `workflow_runs` | Workflow instances | `path`, `version`, `status`, `stage`, `data`, `history`, `reference`, `started_by`, `error` |
| `human_tasks` | Task inbox | `workflow_run_id`, `stage`, `kind`, `title`, `form`, `data`, `assignee`, `status`, `claimed_by`, `outcome`, `result`, `due`, `completed_by` |
| `api_tasks` | Agents behind a URL | `bot_path`, `device_id`, `pool_id`, `token_hash`, `timeout_seconds`, `enabled`, `calls`, `last_used` |

### 4.5 Vault, values and connections

| Table | Purpose | Key columns |
| --- | --- | --- |
| `lockers` | Vault folders | `name`, `owners`, `managers`, `participants`, `consumers`, `external` (link config), `external_secret` (vault-encrypted sign-in secret) |
| `credentials` | Shared values | `locker`, `name`, `attribute`, `ciphertext` (AES-256-GCM, bound to `locker/name/attribute`) |
| `credential_meta` | Credential details | `owner`, `description`, `user_attributes`, `attributes_info` (masked, password fields only) |
| `credential_user_values` | Each person's own value | `locker`, `name`, `attribute`, `username`, `ciphertext` |
| `global_values` | Shared values | `name`, `json_value`, `changeable` |
| `user_global_values` | A person's own shared value | `name`, `username`, `json_value` |
| `connections` | Saved connections for toolkits | `name`, `package`, `json_values`, `json_credentials` (vault references) |
| `settings` | Settings, plus internal keys such as `audit_chain_key` (vault-encrypted) | `name`, `json_value` |
| `toolkit_settings` | Toolkits switched on or off | `id`, `enabled` |
| `toolkit_publishers` | Trusted publishers for custom toolkits | `publisher`, `public_key` |
| `custom_toolkits` | Uploaded signed jars | `toolkit_id`, `version` (unique together), `publisher`, `sha256`, `size_bytes` |

### 4.6 AI

| Table | Purpose | Key columns |
| --- | --- | --- |
| `model_connections` | Central model connections | `name`, `provider`, `model`, `base_url`, `key_ref` (vault reference), `allowed_roles`, `model_type`, `monthly_tokens`, `calls_per_minute`, `calls_per_run`, `pii_analyzer_url`, `content_check` |
| `ai_calls` | Governance record per model call | `run_id`, `bot_path`, `requested_by`, `ts`, `purpose`, `provider`, `model`, `connection`, `input_tokens`, `output_tokens`, `latency_ms`, `masked`, `tool_calls`, `prompt_sha256` (never the prompt) |
| `prompt_skills`, `prompt_skill_versions` | Prompt Skills with history | `name`, `definition`, `version`, `shared`, `template`, `owner` |

### 4.7 Oversight, licensing and operations

| Table | Purpose | Key columns |
| --- | --- | --- |
| `audit` | Append-only audit chain | `seq`, `ts`, `actor`, `action`, `object_type`, `object_id`, `details`, `result`, `ip`, `prev_hash`, `hash`, `alg` (null = SHA-256, `h1` = HMAC-SHA256) |
| `licenses` | Installed licence files | `id`, `content`, `customer`, `expires`, `active` |
| `license_assignments` | Seats held | `principal_type`, `principal`, `license_type` |
| `license_usage` | Daily seat use | `snap_day`, `license_type`, `used`, `total` |
| `kpi_values` | Business KPIs from runs | `run_id`, `bot_path`, `name`, `num`, `text_value`, `ts` |
| `dashboards` | Dashboards | `owner`, `shared_with`, `widgets` |
| `cluster_lease`, `cluster_nodes` | Several nodes | `holder`, `expires`; `node_id`, `last_seen`, `leader_since` |
| `schema_version` | Applied schema version | – |

## 5. Data kept outside the database

| Item | Where | Notes |
| --- | --- | --- |
| Vault master key | `OOA_MASTER_KEY`, a key source (`OOA_MASTER_KEY_SOURCE`) or `master.key` in the data folder | Never in the database or in backups |
| Repository file contents and custom toolkit jars | `repo-files/<sha256>` (or the shared `repo_files_folder`) | The database keeps the metadata |
| Database and HTTPS settings | `config/ooa-db.properties`, `config/ooa-tls.properties` | Secrets as `enc:` values |
| Audit checkpoints and archive | `audit-checkpoints.log`, `audit-archive/*.jsonl.gz` | Compared by Verify integrity |
| Backups | `backups/` or `backup_folder` | Zip of all tables (JSON lines), repository files, configuration and a manifest; vault values stay encrypted |
| Git working copy | `git-sync/` | Mirror only |
| SaaS tenant folders | `DATA/tenants/<id>` | Shared storage in a multi-node region |

## 6. Sensitive columns

| Column | Protection |
| --- | --- |
| `users.password_hash`, `password_history` | PBKDF2-HMAC-SHA256, 210,000 iterations, random salt |
| `sessions.token_hash`, `api_keys.key_hash`, `devices.secret_hash`, `registration_keys.key_hash`, `api_tasks.token_hash`, `triggers.secret_hash` | SHA-256 only; the value is shown once |
| `credentials.ciphertext`, `credential_user_values.ciphertext`, `lockers.external_secret`, `users.mfa_secret`, the audit key in `settings` | AES-256-GCM with the vault key, bound to their location |
| `users.mfa_recovery` | SHA-256 hashes |
| `ai_calls` | Only a SHA-256 fingerprint of the prompt; tokens and masking counts |
| `runs.inputs`, `runs.outputs`, `run_events.event` | Credentials are never stored here; secret values are masked in events |

## 7. Schema per tenant and the region catalog

A SaaS region uses one PostgreSQL database:

| Schema | Content |
| --- | --- |
| `ooa_catalog` | `tenants` (`id`, `slug` unique, `name`, `status` active/suspended, `region`, `admin_email`, `seats`, `key_ciphertext` (the tenant's vault key encrypted with the region's master key), timestamps) and `shared_templates` (`name`, `category`, `tenant_id`, `publisher`, `bundle`, `status` pending/approved/rejected, review fields) |
| `t_<tenant id>` | The full Command Center schema above, one per tenant |

Each tenant's `Db` is bound to its own schema with a small pool (up to 3 connections, closed after 10 idle minutes).
Because every query of a tenant runs inside its own schema, a missing filter cannot return another tenant's rows.
Deleting a tenant drops its schema with `CASCADE`. SQL Server is not supported for tenants (`CR.DB_CONFIG`).

The optional **directory** service has its own small database with one table `directory` (`slug`, `region`,
`created`) and no customer data.

## 8. Conventions for changing the schema

- Add a new `SCHEMA_Vn` array and raise `Db.LATEST`; never edit a version that may already be applied.
- Write portable SQL (PostgreSQL style); check that `SqlServerDialect` covers it.
- Use `?` placeholders; never concatenate values into SQL.
- Store secrets encrypted with the vault, or as hashes; never in clear text.
- Run the Command Center tests on SQL Server and PostgreSQL with `-Pooa.test.db=...` as well as on H2.

## Related documents

- [README.md](README.md)
- [01-system-overview.md](01-system-overview.md)
- [02-components-and-modules.md](02-components-and-modules.md)
- [03-agent-model-and-runner.md](03-agent-model-and-runner.md)
- [04-command-center-internals.md](04-command-center-internals.md)
- [06-security-architecture.md](06-security-architecture.md)
- [07-toolkit-architecture.md](07-toolkit-architecture.md)
- [08-ai-architecture.md](08-ai-architecture.md)
- [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md)
- [10-extensibility-and-integration.md](10-extensibility-and-integration.md)
- Operations guide for the database: [../DATABASE.md](../DATABASE.md)
