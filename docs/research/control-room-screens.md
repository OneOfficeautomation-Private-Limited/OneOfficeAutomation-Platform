# Command Center screens compared with AA's Control Room (Q11)

Checked on 2026-10-28 in a signed-in AA Community Edition Control Room, read only (pages opened, nothing created or
saved). Community Edition shows a smaller menu than AA's enterprise product: Home, Automation, AI (Skills, Model
connections, Document Automation), Operations (Activity, Bot Insights), Manage (Devices, Global values, Credentials,
Packages) and Administration (Users). Schedules, queues, workload, roles, audit and settings are enterprise-only there,
so this comparison covers what Community Edition shows; `aa-platform-gap-list.md` covers the rest from AA's API index
and documentation. Written in our own words.

| AA screen | What it shows | Our page | Result |
| --- | --- | --- | --- |
| Home | Your automations changed in the last 7 days, quick links to AA products, pages you visited lately, learning links | Home: your recent agents and runs, recent failures, run and runner counts | Covered. We have no "pages visited lately" list; the menu search finds any page |
| Automation | Folder tree (private Bots folder, shared app store folder), files table with type, path, status, platform, size, last change; search in subfolders; Create and Manage menus | Agent repository (folders, versions, check-out state, live production version), Templates, One Agent Studio | Covered; ours adds versions and production labels in the table |
| AI > Skills | Prompt skills, public and private workspaces | Prompt Skills (shared or private, versions, templates) | Covered |
| AI > Model connections | Connections to language models, with a create button | One Model Connections (provider, model, fine-tuned flag, monthly token budget) and AI governance | Covered, with budgets and governance on top |
| AI > Document Automation | Learning instances for document extraction | Nothing | **Excluded for now** (your decision on document automation) |
| Operations > Activity | Two tabs: In progress (progress, current action, device, run-as user) and Historical (last 90 days by default; status, type, run time, start and end, device, run-as user, priority); export to CSV, column chooser, refresh | Agent Runs, with the same split into running and finished runs (live progress and current step; history filtered by status and dates; CSV export, column chooser) | Covered |
| Operations > Bot Insights | Separate analytics product (opens on its own) | Operations and Dashboards | Covered in our own pages |
| Manage > Devices | Runner table: status, platform, default users, nickname, single or multi-user, persistent or temporary, log level, install type, agent version, pool, which device is "my default" and "my local" device; Run button | Runners (status, screen locked or not, type, lifespan, default users, log level, agent version and updates, after-run action, last seen) and Pools | Covered, including each user's default runner (set per user). **Missing**: a nickname per runner |
| Manage > Global values | Name, type, whether each user may set their own value | Shared values (with per-user values) and Connections | Covered |
| Manage > Credentials | Three tabs: Credentials, Lockers, Credential requests (values users must fill in) | One Agent Vault (credentials, lockers with owners, managers, participants and consumers; Credential requests dialog; external vault links) | Covered |
| Manage > Packages | 178 packages with versions, default version, recommended agent and Control Room versions, action and trigger counts, on or off; a detail page per package with its actions and a version picker | Toolkits (actions and conditions, agents that use each, versions per agent, switch on or off, your own toolkits, trusted publishers) | Covered. Ours lists which agents use a toolkit, which AA's list does not |
| Administration > Users | User type, names, email, roles, last login, API key expiry | Users (roles, teams, last sign-in), Roles, Teams; API keys with expiry in their own panel | Covered; API key expiry is on the API keys panel, not in the user table |

## Follow-ups

1. **Runner nickname** (small): an optional display name for a runner, shown in the Runners table and the run pickers.
2. Nothing else from these screens is missing. The enterprise-only screens (schedules, queues, workload, roles, audit,
   settings) are compared in `aa-platform-gap-list.md`.
