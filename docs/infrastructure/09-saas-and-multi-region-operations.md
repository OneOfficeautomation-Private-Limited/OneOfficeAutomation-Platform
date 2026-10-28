# 09. SaaS and multi-region operations

## What this covers / audience

How to operate ONEOFFICEAUTOMATION as a multi-tenant service: regions (India, US, EU), tenants and their isolation,
the operator token and the region commands, the optional global directory, network egress rules, sizing, backups,
licensing of tenants, and upgrading region by region. For the service's own operations and SRE team. On-premises
customers do not need this page.

## Contents

1. Architecture
2. What tenants may not do
3. Preparing a region
4. Region settings
5. Starting a region
6. Operator token and the operator API
7. Tenant lifecycle commands
8. Shared templates
9. Several regions: the directory
10. Egress rules
11. Several nodes and sizing
12. Backups and tenant exports
13. Tenant licensing
14. Upgrades region by region
15. Monitoring a region
16. Limits of this version

## 1. Architecture

| Piece | What it is |
| --- | --- |
| Region | One deployment per geography, for example `in` (India), `us`, `eu`: nodes, one PostgreSQL database, shared storage. A tenant's data never leaves its region. |
| Tenant | One customer: its own schema `t_<id>` in the region's database, its own folder `DATA/tenants/<id>`, and its own vault key, stored in the catalog encrypted with the region's master key |
| Catalog | Schema `ooa_catalog`: each tenant's address name, company, status, seats and encrypted key |
| Router | All tenants on one port; the host name picks the tenant: `acme.in.example.com` is tenant `acme` in region `in` |
| Directory (optional) | Maps address name to region only; `https://example.com/t/acme` redirects to the right region. No customer data. |

Each tenant runs the same Command Center code as an on-premises install. Separate schemas mean a missed filter
cannot show one tenant's rows to another; CI checks isolation with two tenants that use identical names.

Customers' runners stay in their own networks and connect out to their tenant address:
`ooa agent register --url https://acme.in.example.com --key ooareg_...`.

## 2. What tenants may not do

| Restriction | Error |
| --- | --- |
| The server connects only to public addresses on a tenant's behalf (mail, syslog, mailboxes, trigger sources, external vaults, identity provider, callbacks, promotion targets, Git remote). Loopback, private, link-local (cloud metadata), carrier-grade NAT, unique-local and multicast addresses and `*.internal`/`localhost` names are refused. | `SAAS.ADDRESS_BLOCKED` |
| Git remotes must be `https://` or SSH | - |
| No custom toolkit uploads (no tenant code in the server) | `SAAS.NOT_AVAILABLE` |
| Serverless API tasks off unless the region sets `serverless=true` | - |
| Backup folder and repository folder cannot be changed | `SAAS.NOT_AVAILABLE` |
| The outgoing proxy setting has no effect | - |
| Licence files cannot be installed (seats come from the operator) | - |

## 3. Preparing a region

1. **Database**: PostgreSQL 14 or later, and an account that may create schemas:
   ```sql
   CREATE DATABASE ooa;
   CREATE ROLE ooa_region LOGIN PASSWORD '...';
   GRANT CREATE ON DATABASE ooa TO ooa_region;
   ```
2. **DNS and certificate**: wildcard `*.in.example.com` and `in.example.com` to the region's load balancer, with a
   wildcard certificate. TLS ends at the balancer or ingress, which sends `X-Forwarded-Proto: https`.
3. **Master key**: `OOA_MASTER_KEY` or `OOA_MASTER_KEY_SOURCE` from a secret store. It encrypts every tenant's key:
   losing it loses every tenant's vault. Back it up separately.
4. **Operator token**: `ooa region token` prints a new token `ooaop_...` (once) and its SHA-256. Keep the token in
   the secret store; only the SHA-256 goes into the settings.
5. **Shared storage** for `DATA/tenants` when the region has several nodes (NFS, Azure Files, EFS, Filestore).
6. **Egress rules** (section 10).

## 4. Region settings

File `DATA/config/ooa-region.properties`, or, when there is no file, environment variables (containers):

| Property | Variable | Meaning |
| --- | --- | --- |
| `region` | `OOA_REGION` | Short name: lower-case letter first, up to 20 characters (`in`, `us`, `eu`) |
| `domain` | `OOA_DOMAIN` | Region domain, for example `in.example.com` |
| `db.url` | `OOA_DB_URL` | `jdbc:postgresql://...` (PostgreSQL only; without it H2 in the data folder, tests only) |
| `db.user` | `OOA_DB_USER` | Account that may create schemas |
| `db.password_env` | `OOA_DB_PASSWORD` (the variable itself) | Name of the variable with the password |
| `operator.token_sha256` | `OOA_OPERATOR_TOKEN_SHA256` | 64 hex digits; without it the operator API is off |
| `directory.url` | `OOA_DIRECTORY_URL` | Global directory, with several regions |
| `directory.token_env` | `OOA_DIRECTORY_TOKEN` (the variable itself) | This region's directory token |
| `serverless` | `OOA_SERVERLESS` | `true` only with egress rules in place |

Secrets never go in the file, only the names of the variables that hold them.

## 5. Starting a region

```
ooa region start --data DATA [--host 0.0.0.0] [--port 8720]
docker run -e OOA_REGION=in -e OOA_DOMAIN=in.example.com -e OOA_DB_URL=... -e OOA_DB_USER=ooa_region \
  -e OOA_DB_PASSWORD=... -e OOA_MASTER_KEY=... -e OOA_OPERATOR_TOKEN_SHA256=... \
  -v region-data:/data -p 8720:8720 ooa-command-center:VERSION region start --data /data
```

The host defaults to `0.0.0.0` (TLS ends at the balancer). At start the node opens every active tenant's Command
Center (so schedules run) and upgrades each tenant's schema. The start-up lines show the region, tenants open, the
database, whether the operator API and directory are on, and whether serverless runs are allowed.

`docker/compose.saas.yaml` starts one region with PostgreSQL for a trial (tenants at `NAME.in.localtest.me:8720`).

## 6. Operator token and the operator API

The operator API answers only on the region's own address (or a node's IP address), never on a tenant address, and
only when `operator.token_sha256` is set:

| Call | Effect |
| --- | --- |
| `GET /api/operator/tenants` | List tenants |
| `POST /api/operator/tenants` `{slug, name, admin_email, seats}` | Create a tenant |
| `POST /api/operator/tenants/NAME/seats` `{seats}` | Change seats |
| `POST /api/operator/tenants/NAME/suspend` and `/resume` | Suspend or resume |
| `DELETE /api/operator/tenants/NAME` | Delete a suspended tenant |
| `GET /api/operator/templates[?status=...]`, `POST /api/operator/templates/ID/approve\|reject` | Shared templates |

Restrict the region's own address to operator networks at the balancer. Rotate the token by making a new one,
changing the SHA-256 in the settings and restarting the nodes.

## 7. Tenant lifecycle commands

All take `--url https://REGION-DOMAIN` and `--token-env VAR` (the variable holding the operator token):

```
set OOA_OPERATOR_TOKEN=ooaop_...
ooa region tenant create  --url https://in.example.com --token-env OOA_OPERATOR_TOKEN --slug acme --name "Acme Ltd" --admin-email it@acme.example --seats developer=2,unattended_runner=5
ooa region tenant list    --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region tenant seats   acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN --seats developer=5,unattended_runner=10
ooa region tenant suspend acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region tenant resume  acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region tenant delete  acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
```

| Command | Effect |
| --- | --- |
| `create` | Makes the schema, folder, key and first administrator; prints `https://acme.in.example.com/` and the administrator's one-time password (shown once). Send the address and password to the customer by separate channels. |
| `list` | Tenants with status and seats |
| `seats` | New seat counts (`developer`, `citizen_developer`, `attended_runner`, `unattended_runner`); none given means no limit |
| `suspend` | The tenant's people and runners are refused (its address answers 404 like an unknown one); schedules and triggers stop on every node within 30 seconds; data stays |
| `resume` | Starts it again |
| `delete` | Only for a suspended tenant: removes its schema, folder and catalog entry with its key, for good. Export first if the customer wants their data. |

Address names: 3 to 40 lower-case letters, digits and hyphens, starting with a letter; `www`, `api`, `admin` and
similar are reserved.

## 8. Shared templates

Tenants can offer agents to the region (Build > Templates > Share an agent). An operator reviews them:

```
ooa region template list    --url https://in.example.com --token-env OOA_OPERATOR_TOKEN [--status pending|approved|rejected|all]
ooa region template approve tpl_... --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region template reject  tpl_... --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
```

Review what a template does before approving: every tenant of the region can install it. Rejecting an approved one
removes it from the catalogue; installed copies stay. Only agents are shared, never credentials or runs.

## 9. Several regions: the directory

1. `ooa region token` once per region.
2. Directory settings `DATA/config/ooa-directory.properties` (own small PostgreSQL, or H2 for a trial):
   ```
   db.url=jdbc:postgresql://directory-db:5432/ooa_directory
   db.user=ooa_directory
   db.password_env=OOA_DB_PASSWORD
   region.in.domain=in.example.com
   region.in.token_sha256=<SHA-256 of the in token>
   region.us.domain=us.example.com
   region.us.token_sha256=<...>
   region.eu.domain=eu.example.com
   region.eu.token_sha256=<...>
   ```
3. `ooa directory start --data DATA --port 8720` behind `https://example.com` (host defaults to `0.0.0.0`).
4. In each region: `directory.url=https://example.com` and `directory.token_env=OOA_DIRECTORY_TOKEN`.

A region reserves an address name in the directory before creating a tenant (refused if another region has it) and
releases it on delete. The directory holds only name, region and date. Choose the region with the customer at
creation: tenants do not move between regions.

## 10. Egress rules

Block the region nodes' outgoing traffic to private and internal ranges, except the region's own database and
storage:

| Range | What |
| --- | --- |
| `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` | Private networks |
| `100.64.0.0/10` | Carrier-grade NAT |
| `169.254.0.0/16` | Link-local, cloud metadata |
| `127.0.0.0/8` | Loopback |
| `fc00::/7`, `fe80::/10` | IPv6 unique-local and link-local |

The product checks addresses before connecting, but a name is resolved again when connecting, so the network rule
is the real protection. In Kubernetes use a NetworkPolicy with `ipBlock` and `except`; on virtual machines, security
groups or the cloud firewall. Set `serverless=true` only with these rules in place.

## 11. Several nodes and sizing

- Run several nodes with the same settings and database behind the balancer; `DATA/tenants` on shared storage.
- Each tenant's background work runs on one node at a time (its own lease, 30 seconds, renewed every 10).
- Each node opens every active tenant at start and keeps up to 3 database connections per tenant, closed after 10
  idle minutes. Size PostgreSQL `max_connections` for nodes x active tenants x 3; with more than a few hundred
  tenants per region, put PgBouncer in session mode in front.
- Health: `https://in.example.com/healthz` (and `/api/health`) answers `{"status":"ok"}`.

## 12. Backups and tenant exports

- **Region**: back up PostgreSQL (every schema, with point-in-time recovery) and the data folder with your tools;
  keep the master key apart.
- **One tenant**: the tenant administrator makes backups in Administration > Backups into the tenant folder and can
  download them as an export. Restoring one tenant from its own backup is not in this version; restore the region's
  database (or that tenant's schema with PostgreSQL tools).
- **Moving a tenant to another region**: export it, create it in the other region, restore the export there.

## 13. Tenant licensing

Tenants need no licence file. Seats given at creation (or with `seats`) apply; no seats means no limit. The tenant's
licence state is `subscription`: free seats go to people and runners at first use; when all are used the tenant
administrator frees one in Administration > Licenses or asks for more.

## 14. Upgrades region by region

1. Publish a sandbox preview first (see [10](10-upgrades-releases-and-patching.md)) and test with a staging region.
2. Upgrade one region at a time, a week apart, starting with the smallest.
3. In a region: back up PostgreSQL, stop all nodes, roll out the new image, start one node (it upgrades each tenant
   schema as it opens it), check `/healthz` and a tenant sign-in, then start the other nodes.
4. Tell tenants to update their runners from Runners > Update (runners newer than the Command Center get no work, so
   the region goes first).
5. Upgrade the directory independently; it holds only the name table.

## 15. Monitoring a region

| What | How |
| --- | --- |
| Liveness | `/healthz` on the region address |
| Per tenant readiness | `https://TENANT.in.example.com/api/health/ready` |
| Database connections | PostgreSQL `pg_stat_activity` grouped by schema / application name `OOA Command Center` |
| Tenant audit | Each tenant's audit trail; each tenant can set its own syslog target (public addresses only) |
| Node logs | Container stdout (`docker logs`, the orchestrator's log service) |

## 16. Limits of this version

- No self-service sign-up or billing; operators create tenants.
- Tenants stay in the region they were created in.
- No single-tenant restore from its own backup.
- Custom toolkits run only on customers' runners.

## Related documents

- [README](README.md)
- [03. Installation on Linux and in containers](03-installation-linux-and-containers.md)
- [06. High availability and scaling](06-high-availability-and-scaling.md)
- [08. Security hardening](08-security-hardening.md)
- [10. Upgrades, releases and patching](10-upgrades-releases-and-patching.md)
- [The SaaS service](../SAAS.md)
