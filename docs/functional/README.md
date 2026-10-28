# ONEOFFICEAUTOMATION functional specification

**Product:** ONEOFFICEAUTOMATION (OOA) 0.2.0, One Command Center, One Agent Studio, One Runner
**Collection:** Functional (what each feature does, its rules, states, limits, permissions and options)

## What this covers

This collection describes the behaviour of every user-facing feature of ONEOFFICEAUTOMATION as it is built: which
objects exist, which states they move through, which rules the product enforces, which limits apply, who may do what,
and which options change the behaviour. It is a specification, not a set of instructions. Step-by-step instructions
are in the [user guides](../user-guide/00-README.md); how the parts are built is in the [architecture collection](../architecture/README.md); installation, networks,
databases and operations of the servers are in the [infrastructure collection](../infrastructure/README.md).

Where this collection names a page, a setting, a permission, a state or an error code, it uses the name the product
shows or stores. Settings are given with their stored name (for example `run_retention_days`) and the place in the
console where they are changed.

## Contents

| No. | Document | Subject |
| --- | --- | --- |
| 01 | [Agents and automation items](01-agents-and-automation-items.md) | Agents (task bots), headless API agents, scripts, forms, workflows, document intelligence, Prompt Skills; variables and types; inputs and outputs; child agents; triggers inside agents; versions |
| 02 | [Toolkits and actions](02-toolkits-and-actions.md) | The toolkit catalogue by area, sessions, saved connections, credentials in steps, More options, conditions, error handling per step |
| 03 | [Runs, runners and devices](03-runs-runners-and-devices.md) | Run lifecycle and states, priorities, run-as users, run modes, serverless API tasks, runner registration, pools, default runners, temporary runners, screen state, after-run actions, updates |
| 04 | [Schedules and triggers](04-schedules-and-triggers.md) | Schedule kinds, holiday calendars, time zones, trigger kinds, overlaps and missed runs |
| 05 | [Work queues](05-work-queues.md) | Queues, work item models, item states, service level, retries and deferral, processing, validation |
| 06 | [Workflows and human tasks](06-workflows-and-human-tasks.md) | One Workflow stages, start forms, the Task inbox, approvals, assignment, due times, notifications |
| 07 | [Vault, credentials and connections](07-vault-credentials-and-connections.md) | Vault folders and member roles, credentials and attributes, credential requests, external vaults, saved connections, shared values |
| 08 | [Repository, versions and templates](08-repository-versions-and-templates.md) | Check-in and check-out, versions, labels, live production version, production approval, folders, Git sync, lifecycle, templates |
| 09 | [AI features](09-ai-features.md) | One Model Connections, budgets, masking, content check, AI governance, Prompt Skills, AI agents, web goal agent, Knowledge Q&A, AI calls tab, assistants over MCP |
| 10 | [Users, roles, teams and security](10-users-roles-teams-and-security-features.md) | Users, built-in and custom roles, every permission, teams, sign-in, SSO, two-step sign-in, password policy, API keys, switched-off roles |
| 11 | [Operations, audit and dashboards](11-operations-audit-and-dashboards.md) | Agent Runs pages, Home, Operations, dashboards and KPIs, One Audit Trail, retention, e-mail, backups |
| 12 | [Agent tests](12-agent-tests.md) | Test sets, cases, expectations and comparisons, scoring, history |
| 13 | [Licensing and editions](13-licensing-and-editions.md) | Evaluation, licence files and states, seats, enforcement, SaaS subscription seats |
| 14 | [One Agent Studio](14-studio-features.md) | Explorer, editor views, step settings, validation, run and debug, Run from here, child agents, recorder and capture, assistant, Command Center link |

## Product parts named in this collection

| Name | What it is |
| --- | --- |
| One Command Center | The central server and its web console: repository, runs, runners, schedules, triggers, queues, workflows, vault, users, audit, settings. Its API is under `/api/`. |
| One Agent Studio | The browser-based designer for agents and other automation items, served at `/studio/` by `ooa server`, working on a private workspace on the designer's computer. |
| One Runner | The service on each machine that takes runs from the Command Center and runs each agent in its own worker process. Called "runner" or "device" in the API. |
| One Agent Vault | The encrypted credential store of the Command Center. |
| One Workflow | The workflow designer (in Studio) and runtime (in the Command Center) with the Task inbox. |
| One Model Connections | Named connections to AI model providers. |
| One Audit Trail | The hash-chained record of every sign-in, change, run and credential use. |

## Conventions

- **Permission** means one of the permission names listed in [document 10](10-users-roles-teams-and-security-features.md),
  for example `runs.start`. A page or action that needs a permission is hidden or refused for people without it.
- **Folder access** means the four levels view, run, edit and manage that folder permissions give in the agent
  repository ([document 08](08-repository-versions-and-templates.md)). Both the permission and the folder access must allow an action.
- **Principal** means a way to name people in a rule: `user:NAME`, `role:ROLE` (or `role:*` for every role), or `team:NAME`.
- **Vault reference** means `vault folder/credential/attribute`; secrets are never typed into settings, steps or inputs.
- Error codes are written as the product returns them (`CR.FORBIDDEN`, `RUN.NO_DESKTOP`, `VAULT.DENIED` …). Every error
  carries a message and, where the product knows one, a suggested fix.

## Related documents

- [Command Center and runner: setup and daily use](../CONTROL_ROOM_AND_AGENT.md)
- [Architecture](../ARCHITECTURE.md)
- [Command catalogue](../COMMAND_CATALOG.md) and the generated toolkit reference in [../reference/toolkits/](../reference/toolkits/)
- [Licensing](../LICENSING.md)
- [SaaS service](../SAAS.md)
- [Validation report](../VALIDATION_REPORT.md)
- [Platform modules](../../platform/README.md)
