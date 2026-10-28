# ONEOFFICEAUTOMATION documentation

Documentation for ONEOFFICEAUTOMATION (OOA) 0.2.0: One Command Center, One Agent Studio, One Runner and the toolkits.
It is in five collections, each for a different reader. Start with the one that matches what you need to do.

| Collection | For | What it answers |
| --- | --- | --- |
| [User guides](user-guide/00-README.md) | Builders, business users, operators, administrators, runner owners | How do I do this, step by step? |
| [Functional specification](functional/README.md) | Product owners, analysts, testers, support | What does each feature do, with which rules, states, limits and permissions? |
| [Architecture](architecture/README.md) | Architects, developers, security reviewers | How is the product built, and why? |
| [Infrastructure](infrastructure/README.md) | IT, platform and security teams | What do I need, and how do I install, connect, secure, scale, back up and upgrade it? |
| [Reference](#reference) | Everyone | Every toolkit action and field, and every command-line command |

## Where to start

| You want to | Read |
| --- | --- |
| Try the product on one computer | [Getting started](user-guide/01-getting-started.md) |
| Build agents | [Studio basics](user-guide/02-studio-basics.md), then [Building agents](user-guide/03-studio-building-agents.md) and [Running and debugging](user-guide/04-studio-running-and-debugging.md) |
| Look after runs, schedules and queues | [Operator's guide](user-guide/05-operators-guide.md) |
| Set up users, the vault and settings | [Administrator's guide](user-guide/06-administrators-guide.md) |
| Plan an installation | [Infrastructure](infrastructure/README.md), starting with [system requirements and sizing](infrastructure/01-system-requirements-and-sizing.md) |
| Review security | [Security architecture](architecture/06-security-architecture.md) and [security hardening](infrastructure/08-security-hardening.md) |
| Find the fields of an action | [Toolkit reference](reference/toolkits/README.md) |

## User guides

| Guide | Role |
| --- | --- |
| [Index of the user guides](user-guide/00-README.md) | All |
| [Getting started](user-guide/01-getting-started.md) | All |
| [One Agent Studio basics](user-guide/02-studio-basics.md) | Builder |
| [Building agents](user-guide/03-studio-building-agents.md) | Builder |
| [Running and debugging agents](user-guide/04-studio-running-and-debugging.md) | Builder |
| [Operator's guide](user-guide/05-operators-guide.md) | Operator |
| [Administrator's guide](user-guide/06-administrators-guide.md) | Administrator |
| [Runner guide](user-guide/07-runner-guide.md) | Runner owner |
| [Browser connector guide](user-guide/08-browser-connector-guide.md) | Builder, runner owner |
| [Child agents](user-guide/09-child-agents.md) | Builder |
| [Web and desktop automation](user-guide/10-web-and-desktop-automation.md) | Builder |
| [Office files, email and documents](user-guide/11-working-with-office-files-email-and-documents.md) | Builder |
| [Cloud and business applications](user-guide/12-cloud-and-business-apps.md) (with an AWS EC2 example) | Builder |
| [AI in agents](user-guide/13-ai-in-agents.md) | Builder |
| [For business users](user-guide/14-business-users.md) | Business user |
| [Publishing to One Command Center](user-guide/15-publishing-to-command-center.md) | Builder |

## Functional specification

| Document | Covers |
| --- | --- |
| [Index](functional/README.md) | Reading order and terms |
| [Agents and automation items](functional/01-agents-and-automation-items.md) | Agents, scripts, forms, workflows, variables |
| [Toolkits and actions](functional/02-toolkits-and-actions.md) | Toolkits, sessions, connections, error options |
| [Runs, runners and devices](functional/03-runs-runners-and-devices.md) | Run states, runner kinds, pools |
| [Schedules and triggers](functional/04-schedules-and-triggers.md) | Schedules, calendars, triggers |
| [Work queues](functional/05-work-queues.md) | Queues, work items, service levels |
| [Workflows and human tasks](functional/06-workflows-and-human-tasks.md) | One Workflow, task inbox |
| [Vault, credentials and connections](functional/07-vault-credentials-and-connections.md) | Lockers, credentials, shared values |
| [Repository, versions and templates](functional/08-repository-versions-and-templates.md) | Check-in, versions, production, templates |
| [AI features](functional/09-ai-features.md) | Model connections, governance, AI steps |
| [Users, roles, teams and security features](functional/10-users-roles-teams-and-security-features.md) | Permissions, SSO, MFA |
| [Operations, audit and dashboards](functional/11-operations-audit-and-dashboards.md) | Run pages, audit trail, KPIs |
| [Agent tests](functional/12-agent-tests.md) | Test sets and scoring |
| [Licensing and editions](functional/13-licensing-and-editions.md) | Evaluation, licences, seats |
| [One Agent Studio](functional/14-studio-features.md) | Editor, run, debug, Run from here |

## Architecture

| Document | Covers |
| --- | --- |
| [Index](architecture/README.md) | Reading order and names |
| [System overview](architecture/01-system-overview.md) | Context, parts, deployment shapes |
| [Components and modules](architecture/02-components-and-modules.md) | Modules, dependencies, technology |
| [Agent model and runner](architecture/03-agent-model-and-runner.md) | Flow format, execution, events |
| [Command Center internals](architecture/04-command-center-internals.md) | Server, dispatch, scheduler, queues |
| [Data model](architecture/05-data-model.md) | Tables and databases |
| [Security architecture](architecture/06-security-architecture.md) | Sign-in, permissions, vault, audit |
| [Toolkit architecture](architecture/07-toolkit-architecture.md) | How toolkits are built and loaded |
| [AI architecture](architecture/08-ai-architecture.md) | Models, governance, agents, MCP |
| [Multi-tenant SaaS and regions](architecture/09-multi-tenant-saas-and-regions.md) | Tenants, regions, isolation |
| [Extensibility and integration](architecture/10-extensibility-and-integration.md) | API, MCP, custom toolkits |

## Infrastructure

| Document | Covers |
| --- | --- |
| [Index and deployment decision guide](infrastructure/README.md) | Which set-up for which need |
| [System requirements and sizing](infrastructure/01-system-requirements-and-sizing.md) | Hardware, software, sizes |
| [Installation on Windows](infrastructure/02-installation-windows.md) | Setup package, MSI, services |
| [Installation on Linux and in containers](infrastructure/03-installation-linux-and-containers.md) | Package, systemd, images |
| [Network, ports, proxies and certificates](infrastructure/04-network-ports-proxies-and-certificates.md) | Ports, proxies, HTTPS |
| [Databases](infrastructure/05-databases.md) | SQL Server, PostgreSQL, embedded |
| [High availability and scaling](infrastructure/06-high-availability-and-scaling.md) | Several nodes, runners |
| [Backup, restore and disaster recovery](infrastructure/07-backup-restore-and-disaster-recovery.md) | Backups, DR site |
| [Security hardening](infrastructure/08-security-hardening.md) | Keys, TLS, checklist |
| [SaaS and multi-region operations](infrastructure/09-saas-and-multi-region-operations.md) | Running the SaaS service |
| [Upgrades, releases and patching](infrastructure/10-upgrades-releases-and-patching.md) | Releases, upgrade order |
| [Monitoring, logging and troubleshooting](infrastructure/11-monitoring-logging-and-troubleshooting.md) | Logs, checks, common problems |

## Reference

| Document | Covers |
| --- | --- |
| [Toolkit reference](reference/toolkits/README.md) | Every toolkit, action, condition and loop type, with every field and result (generated) |
| [Command-line reference](reference/cli.md) | Every `ooa` command and option (generated) |
| [Command catalogue](COMMAND_CATALOG.md) | Toolkits compared with the reference product's packages |

The two generated pages are made from the product itself: `ooa manifest` with `research/tools/make-reference.py`, and
`ooa ... --help` with `research/tools/make-cli-reference.py`. Run them again after a change to toolkits or commands.

## Earlier single documents

These came before the collections and stay for now; the collections above cover and extend them.
[ARCHITECTURE.md](ARCHITECTURE.md), [CONTROL_ROOM_AND_AGENT.md](CONTROL_ROOM_AND_AGENT.md), [DATABASE.md](DATABASE.md),
[CLOUD_INSTALL.md](CLOUD_INSTALL.md), [SAAS.md](SAAS.md), [LICENSING.md](LICENSING.md), [BROWSER_CONNECTOR.md](BROWSER_CONNECTOR.md),
[RELEASING.md](RELEASING.md), [RUN_LOCALLY.md](RUN_LOCALLY.md), [SECURITY_REVIEW.md](SECURITY_REVIEW.md),
[ENTERPRISE_CHECKS.md](ENTERPRISE_CHECKS.md), [VALIDATION_REPORT.md](VALIDATION_REPORT.md), [TRACEABILITY.md](TRACEABILITY.md).
Where they disagree with the collections, the collections follow the code as of 2026-10-28.
