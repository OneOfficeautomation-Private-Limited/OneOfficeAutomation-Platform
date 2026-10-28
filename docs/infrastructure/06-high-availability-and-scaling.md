# 06. High availability and scaling

## What this covers / audience

How to run several Command Center nodes on one database, how the leader lease works and how long failover takes,
the shared repository folder, load balancer settings, and how to scale runners and runner pools. For architects and
platform engineers designing a production installation.

## Contents

1. The model: many nodes, one leader
2. Requirements for several nodes
3. The leader lease and failover time
4. Setting up a second node
5. Shared repository folder
6. Load balancer settings
7. Database high availability
8. Scaling runners and runner pools
9. Scaling serverless API tasks
10. Planned maintenance on a cluster
11. Limits

## 1. The model: many nodes, one leader

Two or more Command Centers can share one SQL Server or PostgreSQL database.

| Work | Done by |
| --- | --- |
| Console, REST API, runner API (heartbeats, long polls, results), webhooks, API tasks, MCP | Every node |
| Sign-ins in progress (two-step codes, SAML, OpenID Connect state) | Kept in the database, so any node can finish them |
| Schedules, run dispatch, work queues, workflows, server triggers, housekeeping (lost runs, temporary runners, inactive users, retention), audit checkpoints, scheduled backups, licence e-mails | The **leader** only |
| Outgoing proxy setting | Each node re-reads it every minute |
| Serverless API tasks | Each node, up to 4 at once |

No sticky sessions are needed.

## 2. Requirements for several nodes

| Requirement | Why |
| --- | --- |
| SQL Server or PostgreSQL (not H2) | Shared state and the lease |
| The same vault key on every node (`OOA_MASTER_KEY` or the same `OOA_MASTER_KEY_SOURCE`) | Every node must decrypt the vault, the database passwords and the TLS password |
| The same `ooa-db.properties` (and TLS settings if TLS ends on the nodes) | Same database, same certificate |
| A shared folder for repository files, set in `repo_files_folder` | Files uploaded through one node must be readable by all; custom toolkit jars live there too |
| Same product version on every node | Mixed versions are not supported; stop all before upgrading |
| Synchronized clocks (NTP) | The lease compares times |

## 3. The leader lease and failover time

The leader holds a row in the `cluster_lease` table. Every node runs a tick every **10 seconds**: it records its
heartbeat in `cluster_nodes` and renews the lease if it holds it, or takes it if the lease has expired. A lease lasts
**30 seconds** from its last renewal.

| Event | Time until another node leads |
| --- | --- |
| The leader is stopped cleanly (service stop, Ctrl+C) | It releases the lease at once; another node takes it at its next tick, within 10 seconds |
| The leader crashes, hangs or loses the database | The lease expires 30 seconds after the last renewal; another node takes it at its next tick: about 30 to 40 seconds |
| A node loses the database | It stops leading at once (it does not do background work without the database) |

During a failover only background work pauses. Requests keep being served by the other nodes. Work that falls due
while nobody leads (schedules, queue dispatch) is picked up by the new leader. Runs that were in progress on runners
continue; their results go to any node.

Administration > Settings > Several nodes lists the nodes seen in the last minute, when each started, its last
heartbeat and which one leads. Nodes not seen for a day are removed from the list.

## 4. Setting up a second node

1. Install the same version on the second server (setup package or MSI), with its own local data folder.
2. Copy `DATA\config\ooa-db.properties` (and `ooa-tls.properties` plus the `.pfx` if TLS ends on the nodes) from the
   first node.
3. Give it the same vault key (environment variable or key source).
4. Install the service with `--host 0.0.0.0` (see [02](02-installation-windows.md)).
5. Run `ooa check --data DIR`: database, vault key, repository files must PASS.
6. Start it and add it to the load balancer once `/api/health/ready` answers 200.

## 5. Shared repository folder

Set Administration > Settings > Several nodes > Shared folder (`repo_files_folder`), for example
`\\fileserver\ooa\repo-files`, an EFS mount, Azure Files or Filestore.

- Every node's service account needs read and write access.
- Use a highly available share (DFS, clustered file server, cloud file service).
- Custom toolkit jars are kept in its `toolkits` subfolder.
- `ooa check` verifies that every file the database lists is present in the folder.
- In a SaaS region the setting is fixed; the whole `DATA/tenants` folder must be shared instead.

## 6. Load balancer settings

| Setting | Value |
| --- | --- |
| Algorithm | Round robin or least connections; no affinity needed |
| Health probe | `GET /api/health/ready`, expect 200 (503 means take the node out) |
| Idle timeout | 60 seconds or more (runner long polls and the console change feed hold up to 25 seconds) |
| TLS | End at the balancer (send `X-Forwarded-Proto: https`) or pass through to `ooa tls` |
| Request size | At least `max_upload_mb` x 4/3 + 1 MB |
| WebSockets | Not used |
| Address | One DNS name (for example `ooa.company.com`) used by people and runners, so nodes can change behind it |

See [04](04-network-ports-proxies-and-certificates.md) for client address handling behind a balancer.

## 7. Database high availability

| Engine | Options |
| --- | --- |
| SQL Server | Always On availability group with a synchronous replica in the same site (listener name in `host`); failover cluster instance; Azure SQL / Managed Instance zone redundancy; RDS Multi-AZ |
| PostgreSQL | Patroni or a managed service with automatic failover (Azure Flexible Server, RDS/Aurora, Cloud SQL HA); point `host` at the service name |

During a database failover the nodes see connection errors for its duration; readiness reports not ready; the lease
cannot be renewed, so background work pauses and resumes when the database is back. The connection pool
(`pool.max`, default 20 per node) reconnects by itself.

Size the database for `pool.max` x number of nodes connections.

## 8. Scaling runners and runner pools

| Topic | How it works |
| --- | --- |
| Concurrency | One run at a time per runner. More parallel work means more runners. |
| Pools | Runners > Runner pools groups runners. A run, schedule or work queue sent to a pool goes to its least busy online runner. |
| Work queues | Each queue processes on a pool; add runners to the pool to drain faster. Items retry up to the queue's attempts. |
| Priority | Urgent runs start first on a busy runner. |
| Run-as users | A run for a person goes to that person's default runner. |
| Temporary runners | Removed after `temporary_runner_hours` offline (1 to 720, default 8), freeing the licence seat. Suits VDI pools and autoscaled machines. |
| Licences | Each runner taking Command Center work needs an Unattended Runner seat while a licence is installed. |
| Placement | Screen agents need Windows sessions that are signed in and unlocked; headless agents can run on services, Linux or macOS. |
| Measured overhead | About 93 queue items a second through 50 runners on one node in the load test (see [01](01-system-requirements-and-sizing.md)): the server is rarely the limit. |

Plan runner capacity as: items per day x average minutes per item / (60 x working hours per day), plus headroom for
retries and peaks.

## 9. Scaling serverless API tasks

Serverless API tasks run on the virtual runner "Command Center (serverless)", at most 4 at once on each node, 5
minutes each. More nodes give more serverless capacity. A run cut off by a stopped node is failed once it is past the
longest time a run may take.

## 10. Planned maintenance on a cluster

| Task | Procedure |
| --- | --- |
| Patch the operating system of one node | Take it out of the balancer, stop the service (the lease moves at once if it led), patch, start, put it back |
| Change a setting | Administration > Settings; saved in the database for all nodes |
| Rotate the TLS certificate | Update the `.pfx` on every node (or the balancer), then restart nodes one at a time |
| Upgrade the product | Stop all nodes, upgrade, migrate, start (see [10](10-upgrades-releases-and-patching.md)); rolling upgrades are not supported |
| Rotate the vault key | Plan a short stop of all nodes; follow the procedure in [08](08-security-hardening.md) |

## 11. Limits

- One leader at a time; background work does not spread across nodes.
- No rolling upgrades across versions.
- The embedded database cannot be shared.
- A SaaS region runs one leader per tenant (its own lease), with the same timings.

## Related documents

- [README](README.md)
- [01. System requirements and sizing](01-system-requirements-and-sizing.md)
- [04. Network, ports, proxies and certificates](04-network-ports-proxies-and-certificates.md)
- [05. Databases](05-databases.md)
- [07. Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md)
- [Command Center and runner setup](../CONTROL_ROOM_AND_AGENT.md)
