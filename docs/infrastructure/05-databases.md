# 05. Databases

## What this covers / audience

The database engines the Command Center supports, the accounts and rights it needs, Windows integrated sign-in with
a service account or gMSA, the `ooa db` commands, every connection setting, moving data from the evaluation
database, and schema upgrades. For database administrators and server administrators.

## Contents

1. Engines and when to use them
2. What is in the database, and what is not
3. Accounts and rights
4. SQL Server set-up
5. PostgreSQL set-up
6. Windows sign-in (service account or gMSA)
7. The `ooa db` commands
8. The settings file `ooa-db.properties`
9. Encrypted connections
10. Moving the evaluation data
11. Schema upgrades
12. Database maintenance

## 1. Engines and when to use them

| Engine | Use | Notes |
| --- | --- | --- |
| Microsoft SQL Server 2019 or later | Production; the usual choice | Required (or PostgreSQL) before a licence can be installed. Azure SQL, Managed Instance, RDS and Cloud SQL work. |
| PostgreSQL 14 or later | Production; the only engine for SaaS regions | |
| H2, embedded | Evaluation on one computer; the default when there is no settings file | File `DATA\controlroom.mv.db`; one process only; no licence |

The engine is chosen by `DATA\config\ooa-db.properties`, or by `--db-url` / `OOA_DB_URL`. Without either, the
embedded database is used.

## 2. What is in the database, and what is not

In the database: users, roles, teams, sessions, agents and every version, runs and step logs, schedules, triggers,
work queues and items, workflows, vault values (encrypted), settings, licence data, the audit trail, cluster nodes
and the leader lease.

In the data folder, not the database: `config\` (database and TLS settings), `repo-files\` (repository files and
custom toolkit jars, unless `repo_files_folder` points elsewhere), `master.key` (when no key is given), backups,
logs, audit checkpoints and archives. Back these up with the database (see [07](07-backup-restore-and-disaster-recovery.md)).

## 3. Accounts and rights

| Account | Example | Rights | Used by |
| --- | --- | --- | --- |
| Setup | `ooa_setup` | SQL Server: `dbcreator` server role (it then owns the OOA database). PostgreSQL: may create databases. | `ooa db init`, `ooa db migrate`, `ooa db copy`, `ooa backup restore` |
| Runtime | `ooa_app` | SQL Server: `db_datareader` and `db_datawriter` on the OOA database only. PostgreSQL: `USAGE` on schema `public` and `SELECT, INSERT, UPDATE, DELETE` on its tables. | The Command Center itself |

`ooa db init` and `ooa db migrate` grant the runtime account its rights. If the setup and runtime accounts are the
same, the commands say so; use a separate data-only account in production. `ooa db test` reports whether the runtime
account has data rights only ("as recommended") or more.

The runtime account cannot change tables, so a release that changes the schema stops at start with
`CR.DB_UPGRADE_NEEDED` until `ooa db migrate` is run (section 11).

## 4. SQL Server set-up

A DBA creates the logins:

```sql
CREATE LOGIN ooa_setup WITH PASSWORD = N'...', CHECK_POLICY = ON;
ALTER SERVER ROLE dbcreator ADD MEMBER ooa_setup;
CREATE LOGIN ooa_app WITH PASSWORD = N'...', CHECK_POLICY = ON;
```

Then, with the Command Center stopped, from `bin`:

```
ooa db configure --engine sqlserver --host sql01 --port 1433 --database ooa_command_center --user ooa_app --setup-user ooa_setup --tls required --tls-ca C:\certs\db-ca.crt --data D:\OOA\cc
ooa db set-password --data D:\OOA\cc
ooa db set-password --setup --data D:\OOA\cc
ooa db init --data D:\OOA\cc
ooa db test --data D:\OOA\cc
```

`ooa db init` creates the database if missing and turns on `READ_COMMITTED_SNAPSHOT`, creates every table, then
adds `ooa_app` as a user with `db_datareader` and `db_datawriter`. The setup package does the same with
`-DbServer`.

A DBA who prefers to create the schema by hand prints it with `ooa db script --engine sqlserver`, runs it in an
empty database, and grants the runtime account `db_datareader` and `db_datawriter`.

Named instances: `--instance NAME` needs the SQL Browser service; a fixed port is better. For Always On, point
`--host` at the availability group listener.

## 5. PostgreSQL set-up

```sql
CREATE ROLE ooa_setup LOGIN CREATEDB PASSWORD '...';
CREATE ROLE ooa_app LOGIN PASSWORD '...';
```

```
ooa db configure --engine postgresql --host pg01 --user ooa_app --setup-user ooa_setup --tls required --tls-ca /etc/ssl/db-ca.crt --data /var/lib/ooa
ooa db set-password --data /var/lib/ooa
ooa db set-password --setup --data /var/lib/ooa
ooa db init --data /var/lib/ooa
```

`init` connects to the `postgres` database to create `ooa_command_center` if needed, creates the tables in
`public`, and grants `ooa_app` its rights. `migrate` grants them again after an upgrade (new tables).

## 6. Windows sign-in (service account or gMSA)

The Command Center can sign in to SQL Server as the Windows account its service runs as; no password is stored.

1. Run the service as that account:
   `ooa service install --mode server --account CORP\ooa-svc$ --host 0.0.0.0 --data D:\OOA\cc` (a gMSA, ending in `$`,
   needs no password; a normal account needs `--password-env VAR`).
2. Configure: `ooa db configure --host sql01 --windows-auth --windows-login CORP\ooa-svc$ --data D:\OOA\cc`.
   No `--user` and no `set-password`.
3. Run `ooa db init` as a Windows user who may create databases (or add `--setup-user` at configure time and store
   its password with `set-password --setup` to use a SQL login for set-up). It creates the database, creates a
   Windows login for `CORP\ooa-svc$` if needed, and gives it `db_datareader` and `db_datawriter`.

The driver's sign-in library `lib\mssql-jdbc_auth-12.8.1.x64.dll` ships with the product and is loaded by the start
scripts and services. Windows sign-in works only when the Command Center runs on Windows.

## 7. The `ooa db` commands

Stop the Command Center first. Every command takes `--data DIR` and `--config FILE` (default
`DIR\config\ooa-db.properties`).

| Command | Does |
| --- | --- |
| `ooa db configure` | Writes the settings file (options below) |
| `ooa db set-password [--setup] [--from-env VAR]` | Stores the runtime (or setup) password, encrypted with the vault key; asks when no variable is given |
| `ooa db test` | Connects as the runtime account: version, edition, account, collation, encryption, schema version, rights |
| `ooa db init` | Creates database and schema; grants the runtime account |
| `ooa db migrate` | Upgrades the schema with the setup account |
| `ooa db script [--engine sqlserver\|postgresql\|h2]` | Prints the schema SQL |
| `ooa db copy --from DIR [--replace]` | Copies an embedded database into the configured one (section 10) |

`ooa db configure` options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--engine` | `sqlserver` | `sqlserver`, `postgresql` (or `h2`) |
| `--host` | Required | Server name or listener |
| `--port` | 1433 / 5432 | Port |
| `--instance` | - | SQL Server named instance |
| `--database` | `ooa_command_center` | Database name (letters, digits, `.`, `-`, `_`) |
| `--user` | Required unless Windows sign-in | Runtime account |
| `--setup-user` | - | Setup account |
| `--windows-auth` | Off | SQL Server Windows sign-in |
| `--windows-login DOMAIN\NAME` | - | The Windows account to grant (a gMSA ends in `$`) |
| `--tls required\|off` | `required` | Encrypted connection |
| `--tls-ca FILE` | Java's trusted authorities | CA chain the server certificate must match |
| `--trust-any-certificate` | Off | Accept any server certificate (tests only) |
| `--pool-max N` | 20 | Connections at most per node |
| `--timeout-seconds N` | 30 | Connection and login timeout |

The server and the `backup` and `reset-admin` commands also accept `--db-url URL --db-user NAME
--db-password-env VAR` (and `--db-config FILE`) to use a database without the settings file; the container image
uses these.

## 8. The settings file `ooa-db.properties`

```properties
engine=sqlserver                 # h2 | sqlserver | postgresql
host=sql01
port=1433
instance=
database=ooa_command_center
user=ooa_app
password=enc:v1:...              # written by ooa db set-password, or env:VARIABLE
setup.user=ooa_setup
setup.password=enc:v1:...        # may be removed after set-up; add it again before the next upgrade
auth=sql                         # sql | windows
windows.login=                   # with auth=windows
tls=required                     # required | off
tls.ca=C:\\certs\\db-ca.crt
tls.trustAnyCertificate=false
pool.max=20
pool.timeoutSeconds=30
```

- Passwords are never in clear text: a clear `password=` is refused at start (`CR.DB_CONFIG`). `enc:` values are
  encrypted with the vault key, so the key must travel with the file. `env:NAME` reads the variable at start.
- Backslashes are doubled in a properties file; `ooa db configure` writes them that way.
- The server prints the database at start, for example
  `Database: SQL Server sql01:1433 / ooa_command_center (TLS) as ooa_app`, and Administration > Settings > Database
  shows the same, read-only.

## 9. Encrypted connections

| Engine | `tls=required` means | Trust |
| --- | --- | --- |
| SQL Server | `encrypt=true` | Java's authorities, or `tls.ca` (converted to a trust store in the data folder), or `trustServerCertificate=true` with `tls.trustAnyCertificate=true` |
| PostgreSQL | `sslmode=verify-full` (certificate and host name checked) | Java's authorities, or `sslrootcert=tls.ca` |

`tls=off` disables encryption; `ooa check` and `ooa db test` warn about it.

## 10. Moving the evaluation data

Before licensing, move the embedded database:

```
ooa db configure ...
ooa db set-password
ooa db set-password --setup
ooa db init
ooa db copy --from "%USERPROFILE%\OOA Command Center"
```

`copy` copies every table in one transaction, then compares row counts table by table (exit code 2 on a mismatch).
It refuses a target that already holds rows unless `--replace` is given. Vault values and the audit chain are copied
as they are, so keep the same vault key (copy `master.key` into the new data folder or set `OOA_MASTER_KEY`; the
command reminds you) and run Audit Trail > Verify integrity afterwards.

Between two databases of the same kind, the database's own backup and restore also works; between engines use
`ooa backup now` and `ooa backup restore` (see [07](07-backup-restore-and-disaster-recovery.md)).

## 11. Schema upgrades

The schema is versioned (this release: version 23). At start:

| Situation | Result |
| --- | --- |
| Schema current | Starts |
| Older schema, account may change tables (embedded H2, containers with an owner account) | Upgraded at start in one transaction |
| Older schema, data-only runtime account | Stops with `CR.DB_UPGRADE_NEEDED`: run `ooa db migrate` |
| Newer schema (made by a later release) | Stops with `CR.DB_NEWER`: start the newer release, or restore a backup taken before the upgrade |

Upgrade procedure:

1. Back up (`ooa backup now` and the database's own backup).
2. Stop every Command Center node.
3. Install the new release (the setup package runs `ooa db migrate` itself when it finds the settings file).
4. `ooa db migrate` (signs in with the setup account; add `setup.password` again if it was removed).
5. Start the nodes.

## 12. Database maintenance

- Back up with the DBA's usual plan (full, differential, log; or PostgreSQL base backups and WAL archiving).
- Index maintenance and statistics as for any OLTP database.
- Keep the database small with the retention settings (runs, step logs, AI calls, work items, audit).
- For high availability use an Always On availability group or a PostgreSQL HA cluster; point `host` at the
  listener or service name, never one server (see [06](06-high-availability-and-scaling.md)).
- Running the tests against SQL Server creates `ooa_t_*` databases; drop them afterwards (development only).

## Related documents

- [README](README.md)
- [02. Installation on Windows](02-installation-windows.md)
- [06. High availability and scaling](06-high-availability-and-scaling.md)
- [07. Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md)
- [08. Security hardening](08-security-hardening.md)
- [Functional 13. Licensing and editions](../functional/13-licensing-and-editions.md)
- [Architecture 05. Data model](../architecture/05-data-model.md)
