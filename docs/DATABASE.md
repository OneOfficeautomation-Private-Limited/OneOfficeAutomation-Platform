# Command Center database

One Command Center keeps everything (users, agents and versions, runs and logs, schedules, vault values, audit trail)
in one relational database. Pick it in `config\ooa-db.properties` in the data folder:

| Engine | Use | Tested |
| --- | --- | --- |
| Microsoft SQL Server 2019 or later | Production (the usual choice) | Every Command Center test, the end-to-end suites and the console UI test, on SQL Server 2022 Express |
| PostgreSQL 14 or later | Production | Configured, not yet run against a real server |
| H2, embedded | Evaluation on one computer; the default when there is no configuration file | Every test |

Repository files (`repo-files`) and the vault key file (`master.key`) stay in the data folder.

## Accounts

The server runs with an account that may only read and write data. A separate setup account creates and upgrades the
schema, through the `ooa db` commands.

| Account | SQL Server rights | Used by |
| --- | --- | --- |
| Setup, for example `ooa_setup` | `dbcreator` on the server (it then owns the OOA database) | `ooa db init`, `ooa db migrate`, `ooa db copy` |
| Runtime, for example `ooa_app` | `db_datareader` and `db_datawriter` on the OOA database only (`ooa db init` grants them) | The Command Center service |

A DBA creates the two logins:

```sql
CREATE LOGIN ooa_setup WITH PASSWORD = N'...', CHECK_POLICY = ON;
ALTER SERVER ROLE dbcreator ADD MEMBER ooa_setup;
CREATE LOGIN ooa_app WITH PASSWORD = N'...', CHECK_POLICY = ON;
```

A DBA who prefers to create the schema by hand runs the output of `ooa db script --engine sqlserver` in an empty
database. They then give the runtime account `db_datareader` and `db_datawriter`.

## Setting it up

Stop the Command Center first. Every command takes `--data DIR` (default: the data folder the server uses) and
`--config FILE`.

```
ooa db configure --engine sqlserver --host dbserver01 --port 1433 --database ooa_command_center --user ooa_app --setup-user ooa_setup --tls required --tls-ca C:\certs\db-ca.crt
ooa db set-password                 (asks for the ooa_app password)
ooa db set-password --setup         (asks for the ooa_setup password)
ooa db init                         creates the database and schema, grants ooa_app its rights
ooa db copy --from "%USERPROFILE%\OOA Control Room"     only when moving an existing embedded database
ooa db test                         version, collation, encryption, schema and rights of ooa_app
```

Then start the server as usual. Its start-up lines name the database, for example
`Database: SQL Server dbserver01:1433 / ooa_command_center (TLS) as ooa_app`. Administration > Settings > Database
shows the same details, read-only.

The settings file:

```properties
engine=sqlserver                  # h2 | sqlserver | postgresql
host=dbserver01
port=1433
instance=                         # optional named instance (needs the SQL Browser service); a fixed port is better
database=ooa_command_center
user=ooa_app
password=enc:v1:...               # written by ooa db set-password; or env:VARIABLE
setup.user=ooa_setup
setup.password=enc:v1:...         # may be removed after setup; add it again for the next upgrade
tls=required                      # off | required
tls.ca=C:\certs\db-ca.crt         # the CA chain the server certificate must match (default: Java's trusted CAs)
tls.trustAnyCertificate=false     # true only for a test server with a self-signed certificate
pool.max=20
pool.timeoutSeconds=30
```

Passwords are never stored in clear text. A clear-text `password=` is refused at start. `enc:` values are encrypted
with the vault master key (`OOA_MASTER_KEY` or `master.key`), so keep that key with the settings file.

## Upgrades

A new release that changes the schema stops at start with `CR.DB_UPGRADE_NEEDED`, because the runtime account may
not change tables. To upgrade:

1. Stop the server.
2. Run `ooa db migrate`. It signs in with the setup account.
3. Start the server again.

The embedded H2 database upgrades itself at start.

## Moving existing data

`ooa db copy --from DIR` copies every table of an embedded database into the configured one in a single
transaction. It then compares the row counts table by table. It refuses a target that already holds rows unless you
add `--replace`. Vault values and the audit chain are copied as they are, so:

- Keep the same master key.
- Check Administration > One Audit Trail > Verify integrity afterwards.

## Backup

Back up the SQL Server database with the DBA's usual plan (full, differential and log backups). Back up the
following together with it:

- the data folder's `config` folder and `repo-files` folder;
- the vault master key.

## How it works

- `Db.java` holds the versioned schema (`Db.LATEST`).
- `SqlServerDialect.java` rewrites the portable SQL for SQL Server:
  - `TRUE` and `FALSE` become `1` and `0`;
  - `LIMIT`/`OFFSET` becomes `OFFSET ... FETCH` or `TOP`;
  - text types become Unicode, and booleans become `BIT`;
  - the reserved column name `external` is bracketed.
- `DbConfig.java` reads the settings file. The `ooa db` commands are in `cli/DbCommand.java`.

To run the tests against SQL Server, use:

```
gradlew :control-room:test "-Pooa.test.db=jdbc:sqlserver://localhost:1433;encrypt=false" -Pooa.test.db.user=ooa_setup -Pooa.test.db.password=...
```

Each test data folder gets its own `ooa_t_*` database. Drop those databases afterwards.
