# 02. Installation on Windows

## What this covers / audience

How to install the Command Center and runners on Windows with the setup package (guided or silent) or the MSI, how
the Windows services and the runner sign-in task work, the first sign-in, uninstalling, and the logs and exit codes.
For Windows administrators and software-distribution teams (SCCM, Intune, Group Policy).

## Contents

1. Packages
2. Before you start
3. Setup package: guided install
4. Setup package: silent install and all parameters
5. MSI: all properties
6. Windows services (`ooa service`)
7. Runners started at sign-in (`ooa agent install-task`)
8. First sign-in
9. Updating in place
10. Uninstalling
11. Logs and exit codes
12. Known behaviours to plan for
13. In a cloud (AWS, Azure, Google Cloud)

## 1. Packages

| File | Contains | Use |
| --- | --- | --- |
| `ooa-setup-<version>.zip` | The product, its own Java runtime, `Install.cmd`, `Install-OOA.ps1`, `Uninstall-OOA.ps1` | Guided or silent installs of Command Centers and runners, per user or for all users |
| `ooa-<version>.msi` | The same product and Java, for all users, in `Program Files\ONEOFFICEAUTOMATION` | Software distribution; Command Center service, or a runner service |
| `ooa-customer-<version>.zip` | The product without Java and without an installer | Unzip and double-click `START-OOA.cmd` (needs Java 21 installed) |

Every package carries `sbom.cdx.json` (CycloneDX software bill of materials). Check a download first with
`ooa release verify` (see [10. Upgrades](10-upgrades-releases-and-patching.md)).

## 2. Before you start

- Decide the role of the computer: **Command Center** (the server people sign in to; includes Agent Studio) or
  **Runner** (runs agents for a Command Center elsewhere).
- For a Command Center that will be licensed, prepare SQL Server (or PostgreSQL) and two accounts: see
  [05. Databases](05-databases.md).
- Set the vault key for production before the first start: `setx OOA_MASTER_KEY "<32 random bytes, Base64>" /M`, or
  `OOA_MASTER_KEY_SOURCE` (see [08. Security hardening](08-security-hardening.md)). Without either, a key file
  `master.key` is created in the data folder at the first start.
- For a runner: create a registration key in the Command Center (Runners > Register a runner). It works once, for at
  most 24 hours. Tick **Temporary runner** for pooled virtual desktops.
  Running setup again on a registered runner (an update or a repair) keeps its registration, so no new key is
  needed then; leave the key empty. A new key is needed only when the Command Center no longer accepts the runner
  (deleted or disabled) or it should join another Command Center.
- Administrator rights are needed for `-AllUsers`, `-Service` and the MSI.

## 3. Setup package: guided install

1. Unzip the whole package.
2. Double-click `Install.cmd`.
3. Answer the questions: Command Center (1) or Runner (2); the install folder; for a Command Center, the SQL Server
   (empty for the built-in evaluation database) and the two passwords (typed hidden); for a runner, the Command
   Center address and the registration key.
4. The window stays open if something fails; read the message and `%TEMP%\ooa-install.log`.

Default folders: `%LOCALAPPDATA%\Programs\ONEOFFICEAUTOMATION` for the current user, or
`%ProgramFiles%\ONEOFFICEAUTOMATION` with `-AllUsers`.

## 4. Setup package: silent install and all parameters

`Install.cmd` passes every argument to `Install-OOA.ps1`. Examples:

```
Install.cmd -Silent -Role CommandCenter -AllUsers -Service -Port 8720 -DataDir D:\OOA\cc
Install.cmd -Silent -Role CommandCenter -AllUsers -Service -ListenAddress 127.0.0.1 -DataDir D:\OOA\cc
Install.cmd -Silent -Role CommandCenter -AllUsers -Service -DataDir D:\OOA\cc -DbServer SQL01 -DbPasswordEnv OOA_APP_PW -DbSetupPasswordEnv OOA_SETUP_PW
Install.cmd -Silent -Role Runner -AllUsers -Url https://ooa.example.com:8720 -RegistrationKey ooareg_... -CaFile C:\certs\ooa.cer
Install.cmd -Silent -Role Runner -AllUsers -Service -Url https://ooa.example.com:8720 -RegistrationKey ooareg_... -Proxy http://proxy.corp:8080
```

| Parameter | Role | Default | Meaning |
| --- | --- | --- | --- |
| `-Role CommandCenter\|Runner` | Both | Asked; `CommandCenter` when silent | What this computer is |
| `-InstallDir DIR` | Both | Program Files (all users) or `%LOCALAPPDATA%\Programs` | Where the product goes |
| `-AllUsers` | Both | Off | Install for everyone; needs administrator |
| `-Silent` | Both | Off | Ask nothing; fail with an exit code when something is missing |
| `-NoShortcuts` | Both | Off | No Start menu entries |
| `-NoStart` | Both | Off | Do not start the service or runner after installing |
| `-Service` | Command Center | Off | Run as the Windows service "OOA Command Center", starting with Windows |
| `-Port N` | Command Center | 8720 | The port |
| `-ListenAddress ADDR` | Command Center | `0.0.0.0` (every network card) | The address the service listens on. The default lets runners on other machines reach it; set up HTTPS (`ooa tls`) before they do. `127.0.0.1` keeps it on this computer only. |
| `-DataDir DIR` | Command Center | `%USERPROFILE%\OOA Command Center` of the installing account (the MSI: `%ProgramData%\OneOfficeAutomation\Command Center`) | The data folder. Always give it for a service. |
| `-DbServer NAME` | Command Center | None (built-in evaluation database) | SQL Server host or `host\instance` |
| `-DbName NAME` | Command Center | `ooa_command_center` | The database |
| `-DbUser NAME` | Command Center | `ooa_app` | The runtime account (data rights only) |
| `-DbSetupUser NAME` | Command Center | `ooa_setup` | The account that creates and upgrades the schema |
| `-DbPasswordEnv VAR` | Command Center | Asked when not silent | Environment variable holding the runtime password |
| `-DbSetupPasswordEnv VAR` | Command Center | Asked when not silent | Environment variable holding the setup password |
| `-DbTrustCertificate` | Command Center | Off | Accept a test SQL Server's self-signed certificate |
| `-SkipCheck` | Command Center | Off | Install even if `ooa check` finds a FAIL |
| `-Url URL` | Runner | Required | Command Center address, `https://...` or `http://...` |
| `-RegistrationKey KEY` | Runner | Required | One-time key `ooareg_...` |
| `-RunnerName NAME` | Runner | Computer name | Name shown in the Command Center |
| `-CaFile FILE` | Runner | None | Certificate to trust (for a self-signed Command Center certificate) |
| `-Proxy system\|none\|URL` | Runner | `system` | How the runner reaches the Command Center |
| `-Service` | Runner | Off | Runner as the Windows service "OOA Runner" (agents without a screen only). Without it the runner starts at the user's sign-in (scheduled task "OOA Runner"). |

What the script does, in order, for a Command Center: checks the package is complete, checks administrator rights,
copies files, creates the Apps and Features entry and Start menu shortcuts, runs `ooa check --port N`, with
`-DbServer` runs `ooa db configure`, `db set-password`, `db set-password --setup` and `db init`, and with `-Service`
runs `ooa service install --mode server --port N --host ADDR [--data DIR]`. When the service listens beyond this
computer, the log reminds you to set up HTTPS (`ooa tls`). For a runner: `ooa agent register` (the key is hidden in
the log), then `ooa service install --mode agent` or `ooa agent install-task` and starts the task.

When the service "OOA Command Center" (or, for a runner, "OOA Runner") exists already, for example on an update or
when the MSI runs again with `ROLE`, the script keeps it with its settings and logs that it did. To change a
service's settings (port, listen address, data folder), run `ooa service uninstall`, then install it again.

Passwords travel only in environment variables, never on the command line or in the log.

## 5. MSI: all properties

```
msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720 DATA_DIR="D:\OOA\cc" /l*v install.log
msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 LISTEN_ADDRESS=127.0.0.1 /l*v install.log
msiexec /i ooa-<version>.msi /qn ROLE=Runner URL=https://ooa.example.com:8720 REGISTRATION_KEY=ooareg_... RUNNER_NAME=RPA-07 CA_FILE=C:\certs\ooa.cer PROXY=system
```

| Property | Default | Meaning |
| --- | --- | --- |
| `ROLE` | Empty: files only | `CommandCenter` or `Runner`. Without it only the files are installed. |
| `SERVICE` | Empty | `1` (or `yes`, `true`): install the Command Center as a service. A runner installed by the MSI is always a service. |
| `PORT` | 8720 | Command Center port |
| `LISTEN_ADDRESS` | `0.0.0.0` (every network card) | The address the Command Center service listens on. `127.0.0.1` keeps it on this computer only. Set up HTTPS before runners on other machines use it. |
| `DATA_DIR` | `%ProgramData%\OneOfficeAutomation\Command Center` | Command Center data folder. The MSI configures as the Local System account, so without `DATA_DIR` the data goes to ProgramData, not to that account's profile. |
| `URL` | - | Runner: the Command Center address |
| `REGISTRATION_KEY` | - | Runner: the one-time key (a hidden property, kept out of the MSI log) |
| `RUNNER_NAME` | Computer name | Runner name |
| `CA_FILE` | - | Certificate to trust |
| `PROXY` | `system` | `system`, `none` or `http://host:port` |

Notes:

- The MSI installs per machine into `Program Files\ONEOFFICEAUTOMATION`, with a Start menu shortcut to
  `START-OOA.cmd`, and writes `HKLM\Software\ONEOFFICEAUTOMATION\InstallDir`.
- It runs `Install-OOA.ps1 -ConfigureOnly` as a deferred custom action without impersonation (Local System). The
  prerequisite check is not run by the MSI; run `bin\ooa.bat check` yourself.
- The MSI has no database properties. After installing, run the `ooa db` commands (see
  [05. Databases](05-databases.md)) and restart the service.
- A runner installed by the MSI is a service and has no desktop. For desktop runners use `Install.cmd` as the user
  who will be signed in.
- Upgrades are major upgrades (same upgrade code); a newer installed version blocks a downgrade.
- Running the MSI again with `ROLE` and `SERVICE=1` keeps an existing service and its settings instead of failing.
- Repair and modify are switched off.

## 6. Windows services (`ooa service`)

Run from an administrator prompt in the installed `bin` folder.

```
ooa service install --mode control-room --data D:\OOA\cc --port 8720 --host 0.0.0.0
ooa service install --mode agent --account CORP\svc-rpa --password-env SVC_PW
ooa service status   [--name NAME]
ooa service start|stop [--name NAME]
ooa service uninstall [--name NAME]
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--mode server\|control-room\|agent` | `server` | `server`: Command Center with Studio (no built-in runner, browser not opened). `control-room`: Command Center only. `agent`: a runner. |
| `--name NAME` | `OOA Command Center`, or `OOA Runner` for `agent` | Service name |
| `--port N` | 8720 | Command Center port |
| `--host HOST` | Not passed, so the server's default `127.0.0.1` (the installers pass `0.0.0.0`, see section 4) | Address to listen on. Use `0.0.0.0` (with HTTPS) so other machines can connect. |
| `--data DIR` | The installing account's `OOA Command Center` | Data folder, written out in full |
| `--workspace DIR` | `OOA Workspace` / `OOA Agent Workspace` of the installing account | Studio or runner workspace |
| `--agent-config FILE` | `%USERPROFILE%\.ooa-agent\agent.json` of the installing account | The runner registration (agent mode) |
| `--account USER` | Local System | Account the service runs as; a gMSA (`DOMAIN\name$`) needs no password |
| `--password-env VAR` | - | Environment variable with the account's password |
| `--no-start` | Off | Install without starting |

Service behaviour:

- Start type automatic; on failure Windows restarts it after one minute, three times; the failure count resets
  after a day.
- The service runs `java.exe -Xmx1g ... service-run MODE ...` from the install's own Java.
- Every path is written into the service command line, because the service account has a different profile.
- Log: `DATA\logs\service-server.log` or `service-control-room.log`; for a runner, `<workspace>\logs\service-agent.log`.
- A runner service runs in session 0: it has **no desktop**. Runs of agents that use the screen fail at once with
  `RUN.NO_DESKTOP`.
- `platform\scripts\test-windows-service.ps1` (source checkout, as administrator) installs a throw-away service
  with a self-signed HTTPS certificate, checks it and removes it.

`Install.cmd -Service` and the MSI with `SERVICE=1` install the Command Center service listening on every network
card (`0.0.0.0`), so runners on other machines can reach it. Pass `-ListenAddress 127.0.0.1` (MSI:
`LISTEN_ADDRESS=127.0.0.1`) to keep it on this computer only, for example behind a reverse proxy on the same machine.
To change the address of a service that exists already, remove it and install it again:

```
ooa service uninstall
ooa service install --mode server --host 127.0.0.1 --port 8720 --data D:\OOA\cc
```

Turn on HTTPS before other machines connect (see [04](04-network-ports-proxies-and-certificates.md)); the installer
logs a reminder, and the server prints a warning when it listens beyond this computer without TLS.

## 7. Runners started at sign-in (`ooa agent install-task`)

Runners whose agents use the screen must run in the signed-in user's session:

```
ooa agent register --url https://ooa.example.com:8720 --key ooareg_... [--name NAME] [--ca ooa.cer] [--proxy ...]
ooa agent install-task [--config FILE] [--workspace DIR]
ooa agent uninstall-task
```

`install-task` registers the scheduled task **OOA Runner** for the current user: it starts at sign-in with
`javaw.exe` (no console window), restarts every minute if it stops (up to 999 times), has no time limit, ignores a
second copy, and runs on battery. It is started at once. After a self-update the runner starts this task again.

Keep such machines signed in and unlocked (a dedicated account, screen saver lock off). Runners > Update runners >
**After unattended runs** can lock the screen or sign out after each unattended run; note that a locked screen
stops the next screen agent from starting there.

## 8. First sign-in

1. Open `http://localhost:8720` (or the HTTPS address).
2. At the very first start the server prints a one-time password for the user `admin`: in the console window, or in
   the service log `DATA\logs\service-server.log`.
3. Sign in and choose a new password: at least 12 characters (setting `password_min_length`), letters and digits,
   not the user name, no character four times in a row, not a common password.
4. Install a licence (Administration > Licenses) after moving to SQL Server or PostgreSQL. Without one, a customer
   install runs a 60-day evaluation.

Forgotten administrator password: stop the server, then `ooa reset-admin [--user NAME] [--data DIR]`. It prints a
one-time password, re-enables and unlocks the account, clears two-step sign-in and records the reset in the audit
trail.

## 9. Updating in place

Running the setup package again over an installation updates it: running services "OOA Command Center" and "OOA
Runner" are stopped, `lib` and `runtime` are replaced, the database is upgraded with `ooa db migrate` when a
`config\ooa-db.properties` exists in the default data folder (otherwise at the next start), and the services are
started again. The data folder is never touched. With the MSI, install the newer MSI; it is a major upgrade. Follow
the upgrade order in [10. Upgrades](10-upgrades-releases-and-patching.md).

## 10. Uninstalling

| Method | Command |
| --- | --- |
| Apps and Features | ONEOFFICEAUTOMATION > Uninstall (runs `Uninstall-OOA.ps1`) |
| Silent, setup package | `powershell -ExecutionPolicy Bypass -File "<install>\Uninstall-OOA.ps1" -Silent` |
| MSI | `msiexec /x ooa-<version>.msi /qn` |

Uninstall removes the services (`ooa service uninstall`), the sign-in task, ends Java processes running from the
install folder, removes the Start menu entries, the Apps entry and the program folder. It keeps: the Command Center
data folder, the runner registration (`%USERPROFILE%\.ooa-agent`), workspaces, and the vault key. Delete those by
hand if the machine is retired, after taking a backup.

## 11. Logs and exit codes

| Log | Where |
| --- | --- |
| Setup and uninstall | `%TEMP%\ooa-install.log` (of the account that ran it; for the MSI, Local System's temp folder) |
| MSI | The file given with `/l*v` |
| Command Center service | `DATA\logs\service-<mode>.log` |
| Runner service | `<runner workspace>\logs\service-agent.log` |
| Each run on a runner | `<runner workspace>\logs\<run id>.log` |

`Install.cmd` / `Install-OOA.ps1` exit codes:

| Code | Meaning |
| --- | --- |
| 0 | Installed |
| 1 | A parameter is missing or wrong (for example a runner without `-Url`/`-RegistrationKey`, or `-DbServer` without password variables when silent) |
| 2 | The install failed (copying files, database set-up, registration, service) |
| 3 | Administrator rights are needed (`-AllUsers` or `-Service`) |
| 4 | The prerequisite check (`ooa check`) found a FAIL |

The MSI returns the usual Windows Installer codes (0 success, 3010 restart needed, 1603 fatal error; the
configuration custom action fails the install when the script returns non-zero). `Uninstall-OOA.ps1` returns 0, or 1
when the user answered no.

## 12. Known behaviours to plan for

- The default data folder belongs to the account that runs the command. Pass `-DataDir`/`--data`/`DATA_DIR` on
  servers so the service, the `ooa` commands and backups all use the same folder.
- An MSI-installed Command Center without `DATA_DIR` keeps its data in
  `%ProgramData%\OneOfficeAutomation\Command Center`. Pass the same folder with `--data` to the `ooa` commands.
- Services installed by the installers listen on every network card (`0.0.0.0`) unless you give
  `-ListenAddress`/`LISTEN_ADDRESS` (section 6). Turn on HTTPS before runners on other machines connect.
- Installing again keeps an existing service and its settings; to change them, `ooa service uninstall` first.
- The runner's own messages go to its console; a runner started by the sign-in task has none, so use the per-run
  logs and `ooa agent diagnose`.

## 13. In a cloud (AWS, Azure, Google Cloud)

A Command Center in your own cloud account is an on-premises install on rented machines: the same product, your
network, your data. Use a Windows virtual machine (below) or the Linux package or container image
([03](03-installation-linux-and-containers.md)). Runners for agents that use the screen stay on Windows machines,
virtual or physical. To run the multi-tenant service for many customers, see
[09](09-saas-and-multi-region-operations.md).

### Cloud building blocks

| Piece | AWS | Azure | Google Cloud |
| --- | --- | --- | --- |
| Database | RDS for SQL Server (or PostgreSQL) | Azure SQL Database, SQL Managed Instance (or PostgreSQL Flexible Server) | Cloud SQL for SQL Server (or PostgreSQL) |
| Vault key (`OOA_MASTER_KEY_SOURCE`) | `aws-secrets:REGION/SECRET-ID` (Secrets Manager) | `azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET` (Key Vault) | `gcp-secret:projects/P/secrets/S/versions/latest` (Secret Manager) |
| Data folder | EBS volume; EFS for repository files shared by several nodes | Managed disk; Azure Files for several nodes | Persistent disk; Filestore for several nodes |
| HTTPS | Application Load Balancer with an ACM certificate, or `ooa tls` | Application Gateway, or `ooa tls` | HTTPS load balancer, or `ooa tls` |
| Backups | Automated database backups, plus `ooa backup` into S3 (a mounted bucket or a copy job) | Database backups, plus `ooa backup` into Azure Files or Blob storage | Database backups, plus `ooa backup` into Cloud Storage |

The key sources and how each signs in are in [08. Security hardening](08-security-hardening.md). Keep the vault key
apart from the backups: a backup holds encrypted vault values, never the key.

### A Windows virtual machine

1. Create a Windows Server 2022 machine (4 vCPU and 16 GB to start, see
   [01](01-system-requirements-and-sizing.md)) in a private subnet.
2. Create the database (SQL Server 2019 or later, or PostgreSQL 14 or later) and the setup and runtime accounts
   ([05](05-databases.md)). Allow the machine to reach the database port.
3. Give the machine its vault key as a machine-wide variable, `OOA_MASTER_KEY` or `OOA_MASTER_KEY_SOURCE`
   (`setx NAME "VALUE" /M`), taken from your secret store. Windows services see a new machine-wide variable only
   after Windows restarts, so restart before installing.
4. Copy the MSI (or the setup package) to the machine and install, as administrator:
   ```
   msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720 DATA_DIR="D:\OOA\cc" /l*v install.log
   ```
   Or use `Install.cmd -Silent -Role CommandCenter -AllUsers -Service -DataDir D:\OOA\cc -DbServer ...`, which also sets
   up the database (section 4).
5. With the MSI, set up the database: `ooa db configure`, `ooa db set-password`, `ooa db set-password --setup`,
   `ooa db init`, each with `--data D:\OOA\cc` ([05](05-databases.md)), then restart the service.
6. Put the load balancer in front (HTTPS on 443 to the machine's port 8720, sending `X-Forwarded-Proto: https`, with
   its addresses in **Trusted proxies**), or run `ooa tls configure --pfx FILE --data D:\OOA\cc`
   ([04](04-network-ports-proxies-and-certificates.md)).
7. Run `bin\ooa.bat check --data D:\OOA\cc`: there must be no FAIL lines, and the vault key line must name your key
   source, not a key file.
8. Register runners from Runners > Register a runner. They connect outbound only, to the Command Center's HTTPS
   address, so they can sit in another network (or on premises) as long as they reach that address. Tick
   **Temporary runner** for pooled virtual desktops.

For several nodes behind the load balancer on one database, see [06](06-high-availability-and-scaling.md). To move an
existing on-premises Command Center into the cloud, see [07](07-backup-restore-and-disaster-recovery.md), section 7.

## Related documents

- [README](README.md)
- [01. System requirements and sizing](01-system-requirements-and-sizing.md)
- [04. Network, ports, proxies and certificates](04-network-ports-proxies-and-certificates.md)
- [05. Databases](05-databases.md)
- [10. Upgrades, releases and patching](10-upgrades-releases-and-patching.md)
- [11. Monitoring, logging and troubleshooting](11-monitoring-logging-and-troubleshooting.md)
- [Customer package README](../../platform/customer/README.txt)
