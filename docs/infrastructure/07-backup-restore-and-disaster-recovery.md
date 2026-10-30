# 07. Backup, restore and disaster recovery

## What this covers / audience

What to back up, the built-in scheduled backups, the `ooa backup` commands, how the vault key must be handled, how to
restore and move a Command Center, and how to build, fail over to and fail back from a disaster recovery site. For
backup administrators, DBAs and business continuity planners.

## Contents

1. What must be protected
2. Built-in backups
3. `ooa backup now`, `list` and `restore`
4. The vault key and backups
5. Database-native backups
6. Restore scenarios
7. Moving a Command Center to new servers or a cloud
8. Disaster recovery site
9. Failover
10. Failback
11. Checking a site with `ooa check`
12. Recovery objectives

## 1. What must be protected

| Item | Where | Changes | Protect with |
| --- | --- | --- | --- |
| Database | SQL Server, PostgreSQL, or `DATA\controlroom.mv.db` | Constantly | Built-in backups and the database's own backups |
| Repository files and custom toolkits | `DATA\repo-files` or `repo_files_folder` | With uploads | Built-in backups (included), file backup or replication |
| Configuration | `DATA\config` (`ooa-db.properties`, `ooa-tls.properties`, certificate files) | Rarely | Built-in backups (included), configuration management |
| Vault key | `OOA_MASTER_KEY`, a key store, or `DATA\master.key` | Only when rotated | Separately, in a secrets store, never with the backups |
| Audit checkpoints and archive | `DATA\audit-checkpoints.log`, `DATA\audit-archive` | Hourly / with retention | Copy to write-once or log storage |
| Runner registrations | `%USERPROFILE%\.ooa-agent\agent.json` on each runner | Every 30 days (secret rotation) | Not needed: re-register a lost runner |
| Licence file | Stored in the database | On renewal | Keep the `.lic` file too |

## 2. Built-in backups

Administration > Settings > Backups:

| Setting | Range | Default | Meaning |
| --- | --- | --- | --- |
| `backup_hours` | 0 to 168 | 0 (off) | Make a backup when the newest is older than this many hours |
| `backup_keep_days` | 1 to 3650 | 7 | Remove older backups; the newest is always kept |
| `backup_folder` | A folder | `DATA\backups` | Where backups go (fixed in a SaaS tenant) |

A backup is one zip named `ooa-backup-YYYYMMDD-HHMMSS.zip` (UTC) with:

- every table as JSON lines, read through JDBC, so the format is the same for H2, SQL Server and PostgreSQL;
- the repository files and the configuration files of the data folder;
- a manifest with format `ooa-backup-1`, the schema version, row counts and the vault key's fingerprint (not the
  key).

Vault values stay encrypted inside. The leader node makes the scheduled backups; **Back up now** makes one at once.
Failures show on the settings page and in the audit trail. Point `backup_folder` at storage outside the server (a
share, a mounted bucket) and give the service account write access.

## 3. `ooa backup now`, `list` and `restore`

| Command | Effect |
| --- | --- |
| `ooa backup now [--data DIR]` | Make a backup into the backup folder; prints the file, rows and size, and a reminder about the key |
| `ooa backup list [--data DIR]` | Backups in the folder, newest first, with time and size |
| `ooa backup restore FILE [--replace] [--data DIR]` | Put a backup back. Stop the Command Center first. |

All three accept `--db-url URL --db-user NAME --db-password-env VAR` (and `--db-config FILE`), which the container
uses. Restore with a settings file signs in with the setup account when one is configured.

Restore checks, in order, and refuses with `CR.BACKUP` when:

- the file is not an OOA backup or has an unknown format;
- the backup comes from a newer release (schema newer than this release): restore it with that release;
- the backup was made with another vault key (fingerprints differ): put that key in place first;
- the target database already holds rows and `--replace` was not given.

It then loads every table, the repository files and the configuration files, and prints the row and table counts.

## 4. The vault key and backups

- A backup never contains the vault key. Without the key, its vault values, database and TLS passwords, two-step
  secrets and the install id (and therefore the licence binding) cannot be read.
- Keep the key in a secrets store with its own backup and access control, apart from backup media.
- Restoring needs the same key the backup was made with. After a key rotation, older backups need the old key: the
  rotation keeps the old key file as `master.key.old-<time>`; archive it with the backups it belongs to.

## 5. Database-native backups

Use the DBA's normal plan as well:

- SQL Server: full, differential and log backups (point-in-time restore).
- PostgreSQL: base backups and WAL archiving (point-in-time recovery).

Back up `DATA\config` and the repository folder with them. A database restore brings back the vault values
encrypted; the same vault key is still required.

## 6. Restore scenarios

| Scenario | Steps |
| --- | --- |
| Lost Command Center server, database intact | Install the same version on a new server, same `ooa-db.properties`, same vault key, restore the repository folder (or point to the share), `ooa check`, start |
| Corrupted or lost database | Stop all nodes; restore the database natively to a point in time, or create an empty database and `ooa backup restore FILE`; `ooa check`; start |
| Accidental data change | Restore a database copy side by side and recover the rows, or restore a built-in backup into a separate test install to inspect |
| Container lost | Start a new container with the same volume (or restore into a new one with `backup restore`) and the same key |

After any restore: sign in, check Runners shows them online, run a test agent, and run Audit Trail > Verify
integrity.

## 7. Moving a Command Center to new servers or a cloud

1. Plan the address. If the DNS name moves with the service, runners need nothing.
2. Stop the old Command Center and run `ooa backup now`. Note where the vault key is.
3. Install the new one with the **same vault key** on an empty database.
4. `ooa backup restore FILE` (in a container, see [03](03-installation-linux-and-containers.md)). Or use the
   database's own backup and restore between servers of the same engine, or `ooa db copy` from an evaluation.
5. On each runner whose address changed: `ooa agent move --url https://new-address [--ca FILE]`, then restart the
   runner. The move is refused, and nothing changes, if the new Command Center does not know the runner (data not
   restored yet). The runner keeps its registration, schedules and seat.
6. `ooa check`, sign in, confirm runners are online, run an agent, retire the old install.

The licence moves with the data when the install id comes along, which it does with the same vault key: a licence
issued for that install id keeps working. Ask the vendor for a new licence only when you set up a new install instead
of restoring (a new install has a new install id).

For a cloud target, build the new install as in [02](02-installation-windows.md), section 13 (Windows virtual
machine) or [03](03-installation-linux-and-containers.md) (Linux package or container).

## 8. Disaster recovery site

A second site takes over when the main one is lost. Keep three things in step:

| What | How | How far behind |
| --- | --- | --- |
| Database | SQL Server Always On with an asynchronous replica at the second site, or log shipping; PostgreSQL streaming replication | Seconds (asynchronous replica, streaming) up to the log backup interval (log shipping) |
| Repository files and toolkit jars (`repo_files_folder`) | DFS Replication, storage replication, or a scheduled `robocopy /MIR` | The copy interval |
| Vault key | The same `OOA_MASTER_KEY` (or key source) at both sites | Never changes except on rotation |

Second-site preparation:

- Install the same product version on the standby nodes, with the same `ooa-db.properties` pointing at the
  database listener or DNS name (not a single server) and the same TLS settings.
- Leave the standby nodes **stopped**: the replica is read-only, and only one site may write.
- People and runners use one DNS name (for example `ooa.company.com`) that you can point at either site, with a low
  time-to-live.
- Install the certificate for that name at both sites.
- Rehearse at least twice a year.

## 9. Failover

1. Fail the database over to the second site (or restore the latest backup there) so it can be written.
2. Make the second site's copy of the repository folder the live one (and point `repo_files_folder` at it if the
   path differs; that setting is in the database).
3. Run `ooa check --data DIR` on a second-site node. Besides the computer, it confirms that the vault key opens the
   stored credentials and that every repository file and toolkit jar the database lists is in the folder. Fix every
   FAIL first.
4. Start the second-site nodes; point the DNS name or the load balancer at them. One node leads within about 30 to 40
   seconds.
5. Runners reconnect through the DNS name by themselves. A runner registered with a server's own name needs
   `ooa agent move --url https://ooa.company.com`.
6. What happens to work:
   - a run that was going finishes if its runner reaches the second site; otherwise it fails with `AGENT.LOST` once
     its runner has not reported for two minutes;
   - serverless runs fail after their time limit;
   - queued runs, schedules and work queues carry on;
   - with an asynchronous replica, the last seconds of changes before the loss may be missing.

## 10. Failback

1. Re-establish replication from the second site back to the main site (database and repository folder).
2. In a planned window: stop the second-site nodes, fail the database back, make the main site's folder live.
3. `ooa check` on a main-site node, start the main nodes, point the DNS name back.
4. Re-protect: the second site becomes the standby again.

If the replica cannot be used, restore the latest built-in backup with `ooa backup restore` (same key) at the site
that will run.

## 11. Checking a site with `ooa check`

Run `ooa check --data DIR` on the node you are about to start. Lines that matter for recovery:

| Line | FAIL means |
| --- | --- |
| Database | Cannot connect: listener, DNS, firewall or credentials |
| Vault key and database | The key does not open the stored credentials: use the main site's key |
| Repository files | N of M files missing in the folder: copy the folder or point `repo_files_folder` at the shared copy |
| HTTPS certificate | Expired or unreadable on this site |

The exit code is the number of FAIL lines, so it can gate an automated runbook.

## 12. Recovery objectives

| Design | Typical RPO | Typical RTO |
| --- | --- | --- |
| Built-in backups only (`backup_hours=24`) | Up to 24 hours | Hours (new server, restore) |
| Database log backups every 15 minutes, file backups | About 15 minutes | 1 to 2 hours |
| Asynchronous replica, replicated folder, standby nodes | Seconds to minutes | 15 to 30 minutes, mostly DNS and checks |
| Synchronous replica in one site plus several nodes | Near zero for a node or database failure | Under a minute (lease takeover) |

These are planning figures for your own runbooks, not guarantees.

## Related documents

- [README](README.md)
- [05. Databases](05-databases.md)
- [06. High availability and scaling](06-high-availability-and-scaling.md)
- [08. Security hardening](08-security-hardening.md)
- [11. Monitoring, logging and troubleshooting](11-monitoring-logging-and-troubleshooting.md)
- [02. Installation on Windows (section 13: in a cloud)](02-installation-windows.md)
