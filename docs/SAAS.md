# The SaaS service: regions and tenants

ONEOFFICEAUTOMATION can run as a multi-tenant service (C3) in several regions (C4), on any cloud or your own servers:
containers and PostgreSQL, nothing provider-specific. On-premises installs are unchanged; everything here is for the
operators of the service.

## How it is built

| Piece | What it is |
| --- | --- |
| **Region** (`ooa region start`) | One deployment per region (India, US, EU): its nodes, its PostgreSQL, its storage. A tenant's data never leaves its region. |
| **Tenant** | One customer: its own **database schema** in the region's PostgreSQL (`t_<id>`), its own **data folder** (`DATA/tenants/<id>`) and its own **vault key**, kept in the catalog encrypted with the region's master key. |
| **Catalog** | Schema `ooa_catalog` in the region's database: each tenant's address name, company, status, seats and encrypted key. |
| **Router** | The region serves every tenant on one port; the host name picks the tenant: `acme.in.example.com` is tenant `acme` in region `in`. |
| **Directory** (`ooa directory start`) | Optional, with several regions: knows only *address name → region*. `https://example.com/t/acme` sends people to `acme.in.example.com`, and a name is used in one region only. It holds no customer data. |

Each tenant runs the same Command Center code as an on-premises install, with its own users, agents, runners, vault,
schedules, audit trail and backups. Because each tenant has its own schema, one tenant's data cannot appear in another's
through a missed filter; tests check it with two tenants that use the same user names, agent paths, runner names and
credential names (`TenantIsolationTest`).

Customers' runners stay in their own networks and connect out to their tenant's address
(`ooa agent register --url https://acme.in.example.com --key ...`), as with an on-premises Command Center.

## What is different for a tenant

On a shared server a tenant may not do what an on-premises administrator may:

- **The server connects only to public addresses** for a tenant: its mail server, syslog collector, mailboxes and
  other trigger sources, external vaults, identity provider, run callbacks, promotion targets and Git remote. Loopback,
  private, link-local (cloud metadata), carrier-grade NAT and unique-local addresses are refused with
  `SAAS.ADDRESS_BLOCKED`. The Git remote must be `https://` or SSH. For systems in the customer's own network, they use
  a runner there.
- **No tenant code runs in the server.** Custom toolkit jars cannot be uploaded (`SAAS.NOT_AVAILABLE`). Serverless API
  tasks are off unless the region allows them (`serverless=true`), which needs the egress rules below.
- **Files stay in the tenant's folder.** The backup folder and repository-files folder settings cannot be changed.
- **The outgoing proxy** setting does nothing (it is process-wide); the region's own network decides.

The address check resolves the name before connecting and the connection resolves it again, so the region's network
must block those ranges as well (egress rules, below).

## Run a region

1. A PostgreSQL 14+ database for the region, and an account that may create schemas:
   ```
   CREATE DATABASE ooa;  CREATE ROLE ooa_region LOGIN PASSWORD '...';  GRANT CREATE ON DATABASE ooa TO ooa_region;
   ```
2. DNS: a wildcard record `*.in.example.com` and `in.example.com` to the region's load balancer, with a wildcard
   certificate. TLS ends at the load balancer or ingress, which sends `X-Forwarded-Proto: https` to the nodes.
3. The region's vault master key in your secret store: `OOA_MASTER_KEY` (32 random bytes, Base64:
   `openssl rand -base64 32`), or `OOA_MASTER_KEY_SOURCE` for Azure Key Vault, AWS or Google (see CLOUD_INSTALL.md). It
   encrypts every tenant's key: losing it loses every tenant's vault.
4. An operator token: `ooa region token` prints a new token (keep it in your secret store) and its SHA-256.
5. The region settings, `DATA/config/ooa-region.properties`:
   ```
   region=in
   domain=in.example.com
   db.url=jdbc:postgresql://db.in.internal:5432/ooa?sslmode=require
   db.user=ooa_region
   db.password_env=OOA_DB_PASSWORD
   operator.token_sha256=<the SHA-256 from step 4>
   serverless=false
   ```
   In a container the same settings come from the environment: `OOA_REGION`, `OOA_DOMAIN`, `OOA_DB_URL`, `OOA_DB_USER`,
   `OOA_DB_PASSWORD`, `OOA_OPERATOR_TOKEN_SHA256`, `OOA_DIRECTORY_URL`, `OOA_DIRECTORY_TOKEN`, `OOA_SERVERLESS`.
6. Start it: `ooa region start --data DATA --port 8720`, or the container image:
   `docker run -e OOA_REGION=in ... ooa-command-center:VERSION region start --data /data`.
   `docker/compose.saas.yaml` starts one region with PostgreSQL for a trial.

`https://in.example.com/healthz` (and `/api/health`) answers `{"status":"ok"}` for load balancers.

**Several nodes:** run more nodes with the same settings and database behind the load balancer. Each tenant's
background work (schedules, triggers, queues) runs on one node at a time (its cluster lease); a tenant suspended on one
node stops on the others within 30 seconds. The data folder (`DATA/tenants`) must be shared storage (NFS, Azure Files,
EFS, Filestore).

**Size:** a node opens every active tenant's Command Center at start (so schedules run) and keeps up to 3 database
connections per tenant, closed after 10 idle minutes. With more than a few hundred tenants per region, put PgBouncer in
session mode in front of PostgreSQL.

## Egress rules

Block the nodes' outgoing traffic to private and internal ranges, except the region's own database and storage:
`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, `169.254.0.0/16` (cloud metadata), `127.0.0.0/8`,
`fc00::/7`, `fe80::/10`. In Kubernetes a NetworkPolicy with an `ipBlock` and `except` does it; on virtual machines,
the cloud's security groups or firewall. Only with these rules in place set `serverless=true`.

## Tenants

Operators manage tenants through the region's operator API, on the region's own address with the operator token:

```
set OOA_OPERATOR_TOKEN=<token>
ooa region tenant create  --url https://in.example.com --token-env OOA_OPERATOR_TOKEN --slug acme --name "Acme Ltd" --admin-email it@acme.example --seats developer=2,unattended_runner=5
ooa region tenant list    --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region tenant seats   acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN --seats developer=5,unattended_runner=10
ooa region tenant suspend acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region tenant resume  acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region tenant delete  acme --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
```

- **create** makes the schema, folder, key and first administrator, and prints `https://acme.in.example.com/` and the
  administrator's one-time password (shown once). Send the two to the customer separately. They choose a new password at
  the first sign-in and then set up users, single sign-on and runners themselves.
- **Address names** are 3 to 40 lower-case letters, digits and hyphens, starting with a letter; `www`, `api`, `admin`
  and similar are reserved.
- **suspend** refuses the tenant's people and runners (its address answers 404, like an unknown one) and stops its
  schedules and triggers; its data stays. **resume** starts it again.
- **delete** removes a suspended tenant for good: its schema, its folder and its catalog entry with its key. Take an
  export first (below) if the customer wants their data.

The API (`/api/operator/tenants`) is off when `operator.token_sha256` is not set, and answers only on the region's own
address or the node's IP address, never on a tenant's.

## Shared templates

Tenants can offer their agents to the other tenants of the region as templates (Build > Templates > Share an agent).
They wait for an operator:

```
ooa region template list    --url https://in.example.com --token-env OOA_OPERATOR_TOKEN              (waiting ones; --status all)
ooa region template approve tpl_... --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
ooa region template reject  tpl_... --url https://in.example.com --token-env OOA_OPERATOR_TOKEN
```

Look at what a template does before approving it: once approved, every tenant of the region sees it and can install
it. Rejecting an approved template takes it back from the catalogue (copies already installed stay).

## Several regions: the directory

1. Make a token for each region with `ooa region token`.
2. The directory's settings, `DATA/config/ooa-directory.properties` (its own small PostgreSQL database, or H2 for a
   trial):
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
3. `ooa directory start --data DATA --port 8720` behind `https://example.com`.
4. In each region's settings: `directory.url=https://example.com` and `directory.token_env=OOA_DIRECTORY_TOKEN`, with
   that region's token in the variable.

A region reserves a name in the directory before creating a tenant (refused when another region has it) and releases it
when the tenant is deleted. `https://example.com/` asks for the company's address name and sends people to their
region. The directory's table holds the name, the region and the date: no customer data.

Pick the region with the customer when creating the tenant: a tenant does not move between regions. To move one, export
it, create it in the other region and restore the export there.

## Backups and exports

- **The region:** back up the region's PostgreSQL (every schema) and the data folder, with the database's own tools
  (point-in-time recovery), and keep the master key apart from the backups.
- **One tenant:** the tenant's administrator makes backups in Administration > Backups; they go to the tenant's folder,
  and the customer can download them as their export. Restoring a single tenant from such a backup is not in this
  version: restore the region's database backup instead (or the tenant's schema from it, with PostgreSQL's tools).

## Licensing

Tenants do not need license files. The operator gives seats when creating the tenant (`--seats developer=2,...`) and
changes them with `ooa region tenant seats NAME --seats ...`; no seats given means no limit. A tenant's license state is
`subscription`: free seats go to people and runners on first use, and when all are in use the tenant's administrator
takes one back in Administration > Licenses or asks for more. Installing license files is refused. On-premises installs
keep their signed license files.

## Limits of this version

- No self-service sign-up or billing: operators create tenants.
- A tenant stays in the region it was created in.
- Restoring one tenant from its own backup (restore the region's database instead).
- Custom toolkits run only on customers' runners, not in the Command Center.
