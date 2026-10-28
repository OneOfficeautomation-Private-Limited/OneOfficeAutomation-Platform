# 03. Installation on Linux and in containers

## What this covers / audience

How to run the Command Center on a Linux server with systemd, and as a container image: building the image, its
environment variables, volumes, health checks and non-root user, and the three compose files (single server, SQL
Server, SaaS region). For Linux administrators and platform engineers.

Runners that use the screen stay on Windows. Headless runners (web services, files, data) can also run on Linux.

## Contents

1. What runs on Linux
2. The Linux package with systemd
3. The systemd unit explained
4. Building the container image
5. Running the image: environment variables
6. Volumes and the data folder
7. Health checks
8. Running `ooa` commands in the container
9. Compose files
10. Orchestrators (ECS, Container Apps, Kubernetes, Cloud Run)
11. Limits on Linux

## 1. What runs on Linux

| Component | Linux | Notes |
| --- | --- | --- |
| Command Center | Yes | Package with systemd, or the container image |
| SaaS region and directory | Yes | Container image recommended (`region start`, `directory start`) |
| Headless runner | Yes | `ooa agent register` and `ooa agent start`; screen agents are refused |
| Runner for screen agents | No | Windows only |
| Windows sign-in to SQL Server | No | Use SQL logins or PostgreSQL |

## 2. The Linux package with systemd

The customer zip (`ooa-customer-<version>.zip`) contains `bin/ooa`, `lib/`, `linux/ooa-command-center.service` and
`sbom.cdx.json`. It needs Java 21 installed.

```
sudo useradd --system --home /var/lib/ooa --shell /usr/sbin/nologin ooa
sudo mkdir -p /opt/ooa /var/lib/ooa /etc/ooa
sudo chown ooa:ooa /var/lib/ooa
sudo unzip ooa-customer-<version>.zip -d /tmp/ooa
sudo cp -r /tmp/ooa/ooa-customer-<version>/* /opt/ooa/
sudo chmod 755 /opt/ooa/bin/ooa

# secrets: readable by root only; systemd passes them to the service
echo "OOA_MASTER_KEY=<from your secret store>" | sudo tee /etc/ooa/ooa.env
sudo chmod 600 /etc/ooa/ooa.env

# check, then install the unit
sudo -u ooa /opt/ooa/bin/ooa check --data /var/lib/ooa
sudo cp /opt/ooa/linux/ooa-command-center.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ooa-command-center
```

With SQL Server or PostgreSQL, run the database commands as the `ooa` user before starting, with the same vault key
in the environment (the stored passwords are encrypted with it):

```
sudo -u ooa env OOA_MASTER_KEY=... /opt/ooa/bin/ooa db configure --engine postgresql --host db01 --user ooa_app --setup-user ooa_setup --data /var/lib/ooa
```

and the other `ooa db` steps in [05. Databases](05-databases.md). Alternatively keep the passwords out of the file
entirely: put `password=env:OOA_DB_PASSWORD` in `config/ooa-db.properties` and the variable in `/etc/ooa/ooa.env`.

First sign-in: the one-time `admin` password is in the journal (`journalctl -u ooa-command-center`).

## 3. The systemd unit explained

| Line | Effect |
| --- | --- |
| `User=ooa`, `Group=ooa` | Runs as the unprivileged `ooa` account |
| `EnvironmentFile=-/etc/ooa/ooa.env` | Reads `OOA_MASTER_KEY` (or `OOA_MASTER_KEY_SOURCE`) and database passwords; optional file |
| `Environment=JAVA_OPTS=-Xmx2g -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true` | 2 GB heap; exits on out-of-memory so systemd restarts it |
| `ExecStart=/opt/ooa/bin/ooa control-room --host 0.0.0.0 --port 8720 --data /var/lib/ooa` | Command Center only, on all interfaces |
| `After=`/`Wants=network-online.target` | Starts after the network is up |
| `Restart=on-failure`, `RestartSec=10` | Restarted 10 seconds after a failure |
| `NoNewPrivileges=true` | No privilege escalation |
| `ProtectSystem=strict`, `ReadWritePaths=/var/lib/ooa` | The file system is read-only except the data folder |
| `ProtectHome=true`, `PrivateTmp=true` | No access to home folders; a private `/tmp` |

Because the unit listens on `0.0.0.0`, turn on HTTPS (`ooa tls`, see
[04](04-network-ports-proxies-and-certificates.md)) or restrict the port to the load balancer with the host
firewall. A backup folder outside `/var/lib/ooa` must be added to `ReadWritePaths`.

## 4. Building the container image

From the `platform` folder of a source checkout, on a build machine with Docker:

```
gradlew :cli:installCustomerDist
docker build -f docker/Dockerfile -t ooa-command-center:0.1.1 .
```

The image:

| Property | Value |
| --- | --- |
| Base | `eclipse-temurin:21-jre-jammy` |
| Program | `/opt/ooa`, owned by root, read-only to the service user (customer distribution, no vendor tools) |
| User | `ooa` (system user and group, no login shell, home `/data`) |
| Data | `VOLUME /data`, owned by `ooa` |
| Port | `EXPOSE 8720` (`OOA_PORT`) |
| Entry point | `/opt/ooa/docker-entrypoint.sh` |
| Health check | Every 30 s, timeout 5 s, start period 60 s, 3 retries: `GET /api/health` must answer 200 |
| Java options | `-Xmx2g -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true` |

Push it to your registry (Amazon ECR, Azure Container Registry, Google Artifact Registry).

## 5. Running the image: environment variables

```
docker run -d -p 8720:8720 -v ooa-data:/data -e OOA_MASTER_KEY=... ooa-command-center:0.1.1
docker run -d -p 8720:8720 -v ooa-data:/data -e OOA_MASTER_KEY=... \
  -e OOA_DB_URL="jdbc:sqlserver://sql01:1433;databaseName=ooa_command_center;encrypt=true" \
  -e OOA_DB_USER=ooa_app -e OOA_DB_PASSWORD=... ooa-command-center:0.1.1
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `OOA_PORT` | 8720 | Port inside the container |
| `OOA_MASTER_KEY` | - | Vault key, 32 random bytes in Base64. Without it (and without a key source) a key file is made in `/data`. |
| `OOA_MASTER_KEY_SOURCE` | - | `azure-keyvault:...`, `aws-secrets:...`, `gcp-secret:...` (not `prompt` in a container) |
| `OOA_DB_URL` | - | JDBC address of SQL Server or PostgreSQL. Without it the embedded evaluation database in `/data` is used. |
| `OOA_DB_USER` | - | Runtime account |
| `OOA_DB_PASSWORD` | - | Its password, read from the environment by the server (never on a command line) |
| `JAVA_OPTS` | See above | Java options |
| `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | - | Credentials for a key source when no managed identity or task role is available |

When `OOA_DB_URL` is set, the entry point starts
`ooa control-room --host 0.0.0.0 --port $OOA_PORT --data /data --db-url ... --db-user ... --db-password-env OOA_DB_PASSWORD`.
The schema is created and upgraded at start by that account, so for a container the account needs rights to change
tables (or run `ooa db migrate` with a setup account before rolling out a new image). The SaaS region variables are in
[09](09-saas-and-multi-region-operations.md).

TLS: put it on the load balancer or ingress (port 8720 behind it). The proxy must send `X-Forwarded-Proto: https`
so the session cookie is marked `Secure` and HSTS is sent; set `public_url` to the HTTPS address.

## 6. Volumes and the data folder

| Path | Contents | Storage |
| --- | --- | --- |
| `/data` | `config/` (TLS, database settings), `repo-files/`, `backups/`, `logs/`, `audit-checkpoints.log`, `audit-archive/`, `master.key` if no key is given, the embedded database for evaluation | A persistent disk (EBS, managed disk, persistent disk). For several nodes put repository files on shared storage (EFS, Azure Files, Filestore) and point `repo_files_folder` at it. |

Never store `master.key` on the same volume as backups you send elsewhere; prefer `OOA_MASTER_KEY` from a secret
store.

## 7. Health checks

| Address | Answers | Use |
| --- | --- | --- |
| `GET /api/health` | `200 {"status":"ok"}` while the process serves requests | Liveness; the image's own `HEALTHCHECK` |
| `GET /api/health/ready` | `200` when ready, `503` when not; JSON with `status` (`ready` or `not_ready`) and `checks`: `database`, `schema`, `vault`, `disk`, `runners_online` | Readiness for load balancers and monitoring |
| `GET /healthz` | `{"status":"ok"}` on a SaaS region's own address | Region load balancer |

Readiness fails when the database does not answer, the schema is not the version this release needs, the vault key
cannot encrypt and decrypt, or less than 200 MB is free. Health routes are never blocked by the IP allow list.

## 8. Running `ooa` commands in the container

With arguments the entry point runs that `ooa` command instead of the server. For `backup` it adds
`--data /data` and the database options from the environment:

```
docker exec <container> /opt/ooa/docker-entrypoint.sh backup now
docker run --rm -v ooa-data:/data -v /backups:/backups -e OOA_MASTER_KEY=... -e OOA_DB_URL=... \
  -e OOA_DB_USER=... -e OOA_DB_PASSWORD=... ooa-command-center:0.1.1 backup restore /backups/FILE
docker run --rm ooa-command-center:0.1.1 region token
```

Stop the Command Center container before a restore.

## 9. Compose files

All three read secrets from a `.env` file next to the compose file. Never commit it.

### `docker/compose.yaml`: Command Center with SQL Server 2022

```
docker compose -f docker/compose.yaml up -d
```

| Service | What it does |
| --- | --- |
| `sql` | `mcr.microsoft.com/mssql/server:2022-latest`, data in volume `sql-data`, health check with `sqlcmd` |
| `ooa-db-init` | Runs once: creates `ooa_command_center` with `READ_COMMITTED_SNAPSHOT ON` and the login `ooa_app`, then exits |
| `command-center` | The image on port 8720, data in volume `ooa-data`, `restart: unless-stopped` |

`.env`: `SA_PASSWORD`, `OOA_DB_PASSWORD`, `OOA_MASTER_KEY`. For the test set-up the init step makes `ooa_app` a
`db_owner` and the connection trusts the server certificate (`trustServerCertificate=true`); for production use a
data-only account and a trusted certificate (see [05](05-databases.md)).

### Single container without a database server

`docker run` as in section 5 without `OOA_DB_URL`: embedded database in the volume. Evaluation only.

### `docker/compose.saas.yaml`: one SaaS region with PostgreSQL 16

| Service | What it does |
| --- | --- |
| `db` | `postgres:16`, database `ooa`, user `ooa_region`, volume `region-db` |
| `region` | The image with `region start --data /data`, region `in`, domain `in.localtest.me`, volume `region-data` |

`.env`: `OOA_DB_PASSWORD`, `OOA_MASTER_KEY`, `OOA_OPERATOR_TOKEN_SHA256` (from `ooa region token`). Tenants answer
at `NAME.in.localtest.me:8720`. See [09](09-saas-and-multi-region-operations.md) for production.

## 10. Orchestrators

| Platform | Notes |
| --- | --- |
| Amazon ECS (EC2 or Fargate) | Task role for `aws-secrets:` key source; EFS for the shared repository folder; ALB with an ACM certificate |
| Azure Container Apps or AKS | Managed identity for `azure-keyvault:`; Azure Files for shared storage; Application Gateway or ingress for TLS |
| Google GKE or Cloud Run | Service account for `gcp-secret:`; Filestore for shared storage; HTTPS load balancer. Cloud Run: minimum instances at least 1 and CPU always allocated. |
| Kubernetes in general | Liveness probe `/api/health`, readiness probe `/api/health/ready`; a `PersistentVolumeClaim` for `/data`; a NetworkPolicy for egress (required for SaaS) |

Never let the Command Center scale to zero: schedules, triggers and queues run in the background.

## 11. Limits on Linux

- `ooa service` and `ooa agent install-task` are Windows only; use systemd on Linux.
- Windows sign-in to SQL Server (`--windows-auth`) is Windows only.
- Runner proxy passwords are stored with Windows DPAPI, so `--proxy-password-env` is refused on Linux. There, put
  `"proxy_password": "env:NAME"` (with `proxy_user`) in the runner's `agent.json` and set `NAME` for the runner.
- Runner self-update is Windows only; update Linux runners by replacing the package.

## Related documents

- [README](README.md)
- [01. System requirements and sizing](01-system-requirements-and-sizing.md)
- [04. Network, ports, proxies and certificates](04-network-ports-proxies-and-certificates.md)
- [05. Databases](05-databases.md)
- [07. Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md)
- [09. SaaS and multi-region operations](09-saas-and-multi-region-operations.md)
- [Installing in a cloud](../CLOUD_INSTALL.md)
