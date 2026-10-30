# 01. System requirements and sizing

## What this covers / audience

The operating systems, Java runtime, browsers, databases and hardware that ONEOFFICEAUTOMATION needs, how to size a
Command Center and its runners, the load figures measured so far, and how much disk the repository and backups take.
For infrastructure architects and server administrators planning an installation.

## Contents

1. Supported platforms
2. Java runtime
3. Browsers
4. Databases
5. Command Center hardware by size
6. Runner hardware
7. What `ooa check` tests
8. Measured load figures
9. Disk space: data folder, repository files and backups
10. Memory settings of the Java process

## 1. Supported platforms

| Component | Supported | Tested in CI on every push |
| --- | --- | --- |
| Command Center (Windows) | 64-bit Windows Server 2022 (the version used by the cloud install and security tests) or another 64-bit Windows that runs Java 21; Windows 10/11 for evaluation | `windows-latest`: full build and every test suite; MSI install with Command Center and runner services |
| Command Center (Linux package) | Any 64-bit Linux with Java 21: Ubuntu 22.04 and later, RHEL 9 and later | `ubuntu-latest`: the customer package started without a container and checked with `/api/health/ready` |
| Command Center (container) | Any OCI runtime: Docker, Amazon ECS/Fargate, Azure Container Apps/AKS, Google GKE/Cloud Run (always at least one instance) | `ubuntu-latest`: image alone, with PostgreSQL 16, backup and restore, a SaaS region with two tenants |
| Runner for screen agents | 64-bit Windows 10/11 or Windows Server with a signed-in, unlocked desktop session | Windows build-and-test job |
| Headless runner (no screen) | Windows service; Linux; macOS | `ubuntu-latest` on every push, `macos-latest` on the main branch |
| Agent Studio | Windows (same machine as `ooa server`), opened in a browser on that machine | Studio UI tests on Linux |

`ooa check` reports a non-Windows operating system as WARN, not FAIL: the Command Center is supported there, but
runners that use the screen need Windows. A 32-bit system is a FAIL.

Not supported in this version:

- Hardware security modules (PKCS#11) for the vault key.
- Proxies that accept only Windows (NTLM or Kerberos) sign-in, for runners.
- Unattended runners signing a user in to a locked or signed-out desktop by themselves.
- Windows sign-in to SQL Server from Linux (package or container).

## 2. Java runtime

| Package | Java |
| --- | --- |
| Setup package `ooa-setup-<version>.zip` and the MSI `ooa-<version>.msi` | Bring their own Java 21 runtime in `runtime\` (built with jlink). The start scripts use it before any Java on the machine. Nothing else to install. |
| Customer zip `ooa-customer-<version>.zip` | Needs Java 21 or later installed (`JAVA_HOME` or `java` on the path). |
| Container image | Based on `eclipse-temurin:21-jre-jammy`. |
| Linux package | Needs Java 21 installed (for example the distribution's OpenJDK 21 or Eclipse Temurin 21). |

The bundled Java is not replaced by a runner self-update; update it by installing the new setup package or MSI.

## 3. Browsers

| Use | Browsers |
| --- | --- |
| Command Center console | Current Chrome, Edge or Firefox |
| Agent Studio | Chrome or Edge on the Studio computer |
| Agents that start their own browser | Playwright-driven Chromium-family browser started by the runner |
| Browser connector (agents in the user's own browser) | Edge and Chrome (Manifest V3 extension), Firefox 140 or later |

## 4. Databases

| Engine | Version | Use |
| --- | --- | --- |
| Microsoft SQL Server | 2019 or later (Express, Standard, Enterprise; Azure SQL Database and Managed Instance; Amazon RDS; Cloud SQL) | Production, the usual choice. Tested on SQL Server 2022. |
| PostgreSQL | 14 or later | Production; required for SaaS regions. Tested on PostgreSQL 16. |
| H2 (embedded) | Bundled | Evaluation on one computer only. A licence cannot be installed while the Command Center runs on it. |

Details are in [05. Databases](05-databases.md).

## 5. Command Center hardware by size

The figures below are starting points. They follow from the checks built into `ooa check` (8 GB memory and 4
processors recommended, 2 GB free disk the minimum without a warning) and from the load test in section 8. Measure
your own load and adjust.

| Size | Typical use | Nodes | CPU per node | Memory per node | Data folder disk | Database |
| --- | --- | --- | --- | --- | --- | --- |
| Evaluation | One builder, the local runner | 1 (`ooa server`) | 2 cores (WARN below 4) | 8 GB (FAIL below 4 GB) | 10 GB | Embedded H2 |
| Small | Up to about 20 runners, thousands of runs a day | 1 | 4 vCPU | 16 GB | 50 GB | SQL Server Express/Standard or PostgreSQL, 2 vCPU, 8 GB |
| Medium | 20 to 100 runners, work queues, schedules every few minutes | 2 behind a load balancer | 4 vCPU | 16 GB | 100 GB on shared storage for repository files | SQL Server Standard or PostgreSQL with high availability, 4 vCPU, 16 GB |
| Large | 100 runners or more, many queues and triggers | 3 or more | 8 vCPU | 16 to 32 GB | 200 GB or more, shared | SQL Server Enterprise (Always On) or PostgreSQL HA, 8 vCPU, 32 GB |

Notes:

- Only one node at a time runs the background work (schedules, triggers, queue dispatch, housekeeping, backups); more
  nodes add capacity for the console, the API and runner traffic, and give failover. See
  [06. High availability and scaling](06-high-availability-and-scaling.md).
- Waiting runners hold one request open each (up to 25 seconds). The server serves every request on a virtual
  thread, so a waiting runner costs very little memory.
- Serverless API tasks run inside the Command Center: at most 4 at once per node, 5 minutes each. Plan CPU for them
  if you use them.
- A cloud Windows virtual machine starts at 4 vCPU and 16 GB ([02](02-installation-windows.md), section 13).

## 6. Runner hardware

A runner runs one agent at a time, each in its own Java worker process. Size it for the applications the agents
drive, plus:

| Item | Recommendation |
| --- | --- |
| CPU | 2 vCPU minimum, 4 vCPU when agents drive Office, browsers or OCR |
| Memory | 8 GB; 16 GB for heavy Excel, browser or document work |
| Disk | 1 GB free at the least (`ooa agent diagnose` warns below 1 GB) plus the applications and the agent workspace (`%USERPROFILE%\OOA Agent Workspace`) |
| Session | For screen agents: a dedicated Windows account, signed in, screen saver lock off |
| Clock | Within 60 seconds of the Command Center (`ooa agent diagnose` warns otherwise) |

Repository files travel with each run: up to 50 MB per run, each file up to 25 MB.

## 7. What `ooa check` tests

Run `ooa check [--data DIR] [--port N]` before installing or upgrading a Command Center. The setup package runs it
first (skip with `-SkipCheck`). Each line is PASS, WARN or FAIL; the exit code is the number of FAIL lines.

| Check | PASS | WARN | FAIL |
| --- | --- | --- | --- |
| Operating system | Windows | Another OS (runners for the screen need Windows) | - |
| 64-bit | 64-bit Java | - | 32-bit |
| Java | Always shows version and folder | - | - |
| Memory | 8 GB or more | 4 to 8 GB, or unknown | Under 4 GB |
| Processors | 4 or more | Fewer than 4 | - |
| Data folder | Can be created and written | - | Cannot be created or written |
| Free disk space | 2 GB or more | 200 MB to 2 GB | Under 200 MB |
| Port | Free, or a Command Center already answers on it | - | Used by another program |
| Database | Configured, reachable, schema current | Built-in evaluation database, or empty schema | Cannot connect |
| Database encryption | Connection encrypted | Not encrypted | - |
| Vault key and database | The key opens the stored credentials | - | It does not (wrong key) |
| Repository files | Every file the database lists is present | - | Files missing |
| HTTPS certificate | Valid 30 days or more | Off, or under 30 days left | Expired or unreadable |
| Vault key | From `OOA_MASTER_KEY`, or from `OOA_MASTER_KEY_SOURCE` (the line names the key store, for example Azure Key Vault) | Key file in the data folder | - |

## 8. Measured load figures

A load test ships with the product (`gradlew :cli:loadTest`, tagged `load`, not part of the normal build). It starts a
Command Center over HTTP with simulated runners that send heartbeats and long polls, drains a work queue through a
runner pool, and writes and verifies a large audit trail. Its parameters are `-Pooa.load.runners`,
`-Pooa.load.items`, `-Pooa.load.audit`, and `-Pooa.test.db=...` to run it on SQL Server or PostgreSQL. CI runs a
short version on every push (20 runners, 300 items, 2,000 audit records).

The last full run on record (26 October 2026, one developer laptop, embedded H2 database, an empty agent):

| Measure | Result |
| --- | --- |
| Set-up | 50 runners, 1,000 work items, 50,000 audit records |
| Adding 1,000 work items | 85 ms |
| Queue throughput | 1,000 of 1,000 items in 10.7 s: **about 93 items per second**, no request errors |
| Heartbeat (n=1,050) | p50 86 ms, p95 333 ms, p99 409 ms |
| Long poll that brought work (n=1,000) | p50 86 ms, p95 2.4 s, p99 3.3 s |
| Events and finish (n=1,000) | p50 189 ms, p95 348 ms, p99 388 ms |
| Audit writes | 50,000 records in 332 s: **about 151 audit records per second** |
| Audit verify | The whole chain of 50,000 records verified in 356 ms |

How to read them:

- The queue figure is the coordination overhead of the Command Center: real agents take seconds or minutes each, so
  real throughput is set by the number of runners, not by the server.
- Each audit record is chained (HMAC) to the one before it, so writes are serialized; about 150 a second is the
  ceiling measured on H2. Every sign-in, change, run and credential use writes one. Repeat the test on your
  production database engine before relying on a higher rate.
- Run the test yourself on the target database with the expected number of runners before a large rollout.

## 9. Disk space: data folder, repository files and backups

| Item | Where | Size guidance |
| --- | --- | --- |
| Embedded database (evaluation only) | `DATA\controlroom.mv.db` | Grows with runs and logs; use retention settings |
| Repository files (templates, workbooks, custom toolkit jars) | `DATA\repo-files`, or the shared `repo_files_folder` | Sum of uploaded files; every upload is a new version (25 MB per file) |
| Backups | `DATA\backups`, or `backup_folder` | One zip per backup: every table plus the repository files. Keep at least `backup_keep_days` (default 7) times backups per day, plus room for one more |
| Audit archive | `DATA\audit-archive` | Compressed records removed from the database by `audit_retention_days` |
| Audit checkpoints | `DATA\audit-checkpoints.log` | One short line per hour |
| Logs | `DATA\logs` (services) | Small; rotate or archive with your log tooling |
| Runner per-run logs | `<runner workspace>\logs\<run id>.log` | One file per run; clean up periodically |

Readiness (`/api/health/ready`) reports the disk as `fail` under 200 MB free and `warn` under 2 GB, so monitor free
space on the data folder's volume.

Retention settings that keep the database small (Administration > Settings): `run_retention_days`,
`run_log_retention_days`, `ai_call_retention_days`, `work_item_retention_days` (0 keeps everything), and
`audit_retention_days` (0, or at least 90).

## 10. Memory settings of the Java process

| How it runs | Heap limit set by the product | Change it |
| --- | --- | --- |
| Windows service (`ooa service install`) | `-Xmx1g` on the service command line | Reinstall the service after editing, or change the service's command line in the registry; plan for 2 GB or more on medium and large installs |
| `START-OOA.cmd`, `bin\ooa.bat` | Java default (a quarter of physical memory) | Set `JAVA_OPTS` (for example `-Xmx4g`) before starting |
| Linux systemd unit | `JAVA_OPTS=-Xmx2g -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true` | Edit the `Environment=JAVA_OPTS=` line |
| Container image | `JAVA_OPTS="-Xmx2g -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"` | Pass `-e JAVA_OPTS=...` |

Leave memory for the operating system and, on runners, for the applications agents drive.

## Related documents

- [README: index and deployment decision guide](README.md)
- [02. Installation on Windows](02-installation-windows.md)
- [03. Installation on Linux and in containers](03-installation-linux-and-containers.md)
- [05. Databases](05-databases.md)
- [06. High availability and scaling](06-high-availability-and-scaling.md)
- [11. Monitoring, logging and troubleshooting](11-monitoring-logging-and-troubleshooting.md)
- [Enterprise checks](08-security-hardening.md)
