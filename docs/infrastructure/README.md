# ONEOFFICEAUTOMATION infrastructure guide

Product version 0.1.1. This collection describes how to plan, install, connect, protect and run the servers and
runners of ONEOFFICEAUTOMATION (OOA).

## What this covers / audience

Written for IT operations, platform engineering, database administrators, network teams and security teams. It
assumes you know Windows Server or Linux administration, TLS certificates and relational databases. It does not
explain how to build agents or use the console day to day; those topics are in the functional and user-guide
collections.

The facts in these pages were checked against the product's code and scripts (the `platform/cli`,
`platform/control-room`, `platform/agent`, `platform/customer-setup`, `platform/docker` and `platform/linux`
folders, and the CI workflow). Where an older document says something different, the code was followed.

## The parts you operate

| Part | What it is | Where it runs | Network |
| --- | --- | --- | --- |
| **One Command Center** | The central Java server: web console, REST API, the runner API, webhooks, API tasks, the MCP endpoint, schedules, triggers, work queues, vault, audit trail | Windows Server, or Linux (package or container) | Listens on TCP 8720 by default (HTTP or HTTPS) |
| **One Agent Studio** | The agent editor, served by the Command Center at `/studio/` when started with `ooa server`, or on its own with `ooa studio` (port 8710) | The builder's own computer | Loopback (127.0.0.1) only |
| **One Runner** | The process that takes work from the Command Center and runs agents, each in its own worker process | Windows (desktop or service); headless runners also on Linux and macOS | Outbound only, to the Command Center |
| **Database** | Everything the Command Center keeps: users, agents, runs, schedules, vault values, audit trail | SQL Server 2019+, PostgreSQL 14+, or the embedded H2 for evaluation | The Command Center connects out to it |
| **Data folder** | Configuration files, the vault key file (if used), repository files, backups, logs, audit checkpoints | Local disk, or shared storage for several nodes | - |
| **Browser connector** | Extension plus a native messaging host so agents can drive the user's own Edge, Chrome or Firefox | Runner and builder computers | Local only |
| **SaaS region / directory** | Multi-tenant operation of the Command Center for many customers | Containers on any cloud, with PostgreSQL | One port per region behind a load balancer |

## The documents

| # | Document | Read it when you |
| --- | --- | --- |
| 01 | [System requirements and sizing](01-system-requirements-and-sizing.md) | plan hardware, operating systems, databases, browsers and disk |
| 02 | [Installation on Windows](02-installation-windows.md) | install with the setup package or the MSI, run services, uninstall |
| 03 | [Installation on Linux and in containers](03-installation-linux-and-containers.md) | use the Linux package with systemd, or the container image and compose files |
| 04 | [Network, ports, proxies and certificates](04-network-ports-proxies-and-certificates.md) | open firewalls, set up proxies, turn on HTTPS, make runners trust certificates |
| 05 | [Databases](05-databases.md) | choose and set up SQL Server or PostgreSQL, accounts, Windows sign-in, schema upgrades |
| 06 | [High availability and scaling](06-high-availability-and-scaling.md) | run several Command Center nodes, plan load balancers, scale runners |
| 07 | [Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md) | plan backups, restore, build a second site and rehearse failover |
| 08 | [Security hardening](08-security-hardening.md) | protect the vault key, TLS, accounts, audit trail and network |
| 09 | [SaaS and multi-region operations](09-saas-and-multi-region-operations.md) | operate the multi-tenant service in India, US and EU regions |
| 10 | [Upgrades, releases and patching](10-upgrades-releases-and-patching.md) | verify downloads, upgrade in the right order, update runners |
| 11 | [Monitoring, logging and troubleshooting](11-monitoring-logging-and-troubleshooting.md) | watch health, find logs and fix common problems |

## Deployment decision guide

Choose the smallest topology that meets the need. Every topology runs the same product; you can move from one to the
next with a backup and restore (see [07](07-backup-restore-and-disaster-recovery.md)).

### Step 1: what is the purpose?

| Need | Topology | Database | How to install |
| --- | --- | --- | --- |
| Try the product, build first agents on one PC | **A. Single computer, all in one** (`ooa server`: Command Center, Studio and a local runner in one process) | Embedded H2 | Setup package, guided, or `START-OOA.cmd` |
| A pilot or a small team, a few runners | **B. One Command Center server, separate runners** | SQL Server or PostgreSQL (needed for a licence) | Setup package `-Service -DbServer ...`, or MSI and `ooa db` commands |
| Production in one data centre | **C. Two or more Command Center nodes behind a load balancer** | SQL Server (Always On) or PostgreSQL with high availability | As B on each node, shared repository folder, same vault key |
| Production with a second site | **D. Active site plus a cold standby site** | SQL Server availability group with an asynchronous replica, log shipping, or PostgreSQL streaming replication | As C on both sites, standby nodes stopped |
| Your own cloud account (AWS, Azure, Google Cloud) | **B or C on cloud machines or containers** | Managed SQL Server or PostgreSQL | Windows VM with the MSI, Linux package with systemd, or the container image |
| Run OOA as a service for many customers | **E. SaaS region(s)** | PostgreSQL per region | Container image with `region start`; optional global directory |

### Step 2: where do runners go?

| Agent needs | Runner placement |
| --- | --- |
| The screen (keyboard, mouse, windows, images, desktop dialogs) | A Windows computer with a signed-in, unlocked session, started at sign-in (`ooa agent install-task`). A Windows service has no desktop. |
| Only web services, files and data (no screen) | A Windows service (`ooa service install --mode agent`, or the MSI with `ROLE=Runner`), or a headless runner on Linux or macOS |
| Pooled virtual desktops or short-lived machines | Register with **Temporary runner** ticked; offline runners are removed after `temporary_runner_hours` (8 by default) |
| Calls to web services only, no runner at all | Serverless API tasks inside the Command Center (at most 4 at once per node, 5 minutes each) |

### Step 3: which constraints apply?

| Constraint | Consequence |
| --- | --- |
| A licence will be installed | The Command Center must run on SQL Server or PostgreSQL; the embedded database refuses a licence (`LICENSE.DB_REQUIRED`). |
| Other machines connect to the Command Center | Turn on HTTPS (`ooa tls`) or put a TLS load balancer or reverse proxy in front, and make the server listen on more than 127.0.0.1 (see [02](02-installation-windows.md) and [04](04-network-ports-proxies-and-certificates.md)). |
| The vault key must not be on disk | Use `OOA_MASTER_KEY`, or `OOA_MASTER_KEY_SOURCE` with Azure Key Vault, AWS Secrets Manager, Google Secret Manager, or `prompt` (see [08](08-security-hardening.md)). |
| Several nodes | SQL Server or PostgreSQL, a shared `repo_files_folder`, the same vault key on every node. |
| Runners sit behind a proxy | Runners use the Windows proxy by default, or a fixed proxy with optional user name and password; NTLM-only proxies are not supported. |
| Windows sign-in to SQL Server (service account or gMSA) | The Command Center must run on Windows; the Linux package and container cannot use it. |

### Typical layouts

```
A. Evaluation                 B. One server                     C. Several nodes
+----------------------+      +---------------+   +---------+   +----+  +--------------+   +-------------+
| PC: ooa server       |      | Command Center|-->| SQL/PG  |   | LB |->| CC node 1..n |-->| SQL Server  |
|  CC + Studio + runner|      | (service)     |   +---------+   +----+  +--------------+   | or PG (HA)  |
|  embedded H2         |      +---------------+                   ^          |             +-------------+
+----------------------+         ^       ^                        |          v
                                 |       |                        |    shared repo-files folder
                           runners (outbound HTTPS)          runners (outbound HTTPS)
```

## Conventions used in these pages

- `ooa` means `bin\ooa.bat` on Windows and `bin/ooa` on Linux, in the installed product folder.
- `DATA` means the Command Center data folder. Unless `--data` is given, it is `%USERPROFILE%\OOA Command Center`
  (or `~/OOA Command Center`) of the account that runs the command. An older folder named `OOA Control Room` is still
  used when it already holds a database. Because the default depends on the account, always pass `--data` on
  servers.
- Settings named in `code` style (for example `backup_hours`) are the names used by the settings API and by
  Administration > Settings in the console.

## Related documents

- [Command Center and runner setup and daily use](../CONTROL_ROOM_AND_AGENT.md)
- [Installing in a cloud](../CLOUD_INSTALL.md)
- [Command Center database](../DATABASE.md)
- [The SaaS service](../SAAS.md)
- [Releasing to customers](../RELEASING.md)
- [Enterprise checks and validations](../ENTERPRISE_CHECKS.md)
- [Security review pack](../SECURITY_REVIEW.md)
- [Licensing](../LICENSING.md)
- [Browser connector](../BROWSER_CONNECTOR.md)
