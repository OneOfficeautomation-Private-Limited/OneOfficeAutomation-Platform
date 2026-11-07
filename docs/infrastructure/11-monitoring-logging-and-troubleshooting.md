# 11. Monitoring, logging and troubleshooting

## What this covers / audience

What to monitor, where every log is, the health endpoints, the two diagnostic commands (`ooa check` on servers and
`ooa agent diagnose` on runners), and fixes for the problems seen most often: runners offline, certificate and proxy
errors, locked screens, database and upgrade errors, the browser connector not connecting, and installing it from
inside another packaged app. For operations and support teams.

## Contents

1. What to monitor
2. Health endpoints
3. Logs and where they are
4. Audit trail and SIEM
5. `ooa check`
6. `ooa agent diagnose`
7. Common problems and fixes
8. Browser connector problems
9. Collecting information for support

## 1. What to monitor

| Signal | Source | Alert when |
| --- | --- | --- |
| Liveness | `GET /api/health` | Not 200 for 1 minute |
| Readiness | `GET /api/health/ready` | 503, or any check `fail` |
| Disk on the data volume | Readiness `disk`, OS monitoring | `warn` (under 2 GB) or `fail` (under 200 MB) |
| Runners online | Readiness `runners_online`; Runners page | Fewer than expected |
| Leader present | Administration > Settings > Several nodes | No leader for more than a minute |
| Failed runs | Run & Monitor > Operations, Agent Runs > History | Error rate above normal; `AGENT.LOST`, `RUN.NO_DESKTOP` |
| Work queue backlog | Work queues page (items past service level) | Items past their service level |
| Backups | Settings > Backups status, audit trail | Last backup older than `backup_hours` + margin |
| Certificate expiry | `ooa check` (WARN under 30 days), `ooa tls show` | Under 30 days |
| Licence | Licenses page, reminder e-mails (30, 15, 7, 3, 2, 1 days) | Expiring |
| Audit integrity | Audit Trail > Verify integrity | Not valid |
| Service state | Windows Services / `systemctl` / orchestrator | Restarting repeatedly |
| Run failures, backlog, runners, memory | `GET /metrics` (Prometheus) | `ooa_runs_finished_total{status="failed"}` rising, `ooa_queue_items` growing, `ooa_runners{state="online"}` low, `ooa_up{part="database"} 0` |
| Event deliveries | Administration > Event subscriptions; `ooa_events_total` | Failures in a row, `outcome="dropped"` rising |

## 2. Health endpoints

| Endpoint | Answer |
| --- | --- |
| `GET /api/health` | `200 {"status":"ok"}` (plus `studio` for browsers on the same computer when Studio is mounted) |
| `GET /api/health/ready` | `200` or `503`, `{"status":"ready"|"not_ready","checks":{"database","schema","vault","disk","runners_online"}}`; values `ok`, `warn`, `fail`, never paths or secrets |
| `GET /healthz` | SaaS region address: `{"status":"ok"}` |

Health routes are exempt from the IP allow list and from sign-in.

### Metrics: `GET /metrics`

One CommandCenter's figures in the Prometheus text format: runs finished by status and their durations (a histogram),
runs queued and running, work items per queue and status, runners enabled, online and busy, open alerts, licence
seats, events delivered, failed and dropped, and the Java process (memory, threads, garbage collection, uptime). Every
series has a `node` label. Counters are the node's own since it started; figures from the database are the same on
every node (use `max by` the other labels across nodes). The full list is in
[functional/11](../functional/11-operations-audit-and-dashboards.md#event-subscriptions-and-metrics-gap-g38).

Unlike the health routes, `/metrics` needs the metrics token (Administration > Event subscriptions > Make a metrics
token) as a bearer token, or a signed-in administrator, and the IP allow list applies. It is made at most every 5
seconds, so a 15 to 60 second scrape interval costs little. Prometheus:

```yaml
scrape_configs:
  - job_name: ooa-command-center
    scheme: https
    metrics_path: /metrics
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/ooa-metrics-token
    static_configs:
      - targets: ["cc-node1.example.com:8443", "cc-node2.example.com:8443"]
```

OpenTelemetry: One CommandCenter has no OTLP exporter of its own. Run the OpenTelemetry Collector with its
`prometheus` receiver using the same scrape configuration, and send on with any Collector exporter (OTLP to your
observability platform, for example).

## 3. Logs and where they are

| Log | Location | Contents |
| --- | --- | --- |
| One CommandCenter (console window) | Standard output of `START-OOA.cmd`, `ooa server`, `ooa control-room` | Start-up summary (address, database, vault key source, HTTPS), first-start password, errors |
| One CommandCenter service (Windows) | `DATA\logs\service-server.log` or `service-control-room.log` | Same, appended, with start and stop markers |
| One CommandCenter on Linux | `journalctl -u ooa-command-center` | Same |
| Container | `docker logs`, or the orchestrator's log service | Same |
| Runner service | `<runner workspace>\logs\service-agent.log` | Runner messages (connections, runs, updates, secret rotation) |
| Runner in a console | The console window (`ooa agent start`) | Runner messages |
| Runner started by the sign-in task | Not written to a file (it runs with `javaw`, no console) | Use the per-run logs, One CommandCenter's run details and `ooa agent diagnose` |
| Each run | `<runner workspace>\logs\<run id>.log` (default workspace `%USERPROFILE%\OOA Agent Workspace`) | The worker's output and errors; the last lines are quoted when a worker crashes |
| Run details | One CommandCenter > Agent Runs > a run | Step timeline, log (download), variables, inputs and outputs, AI calls |
| Install / uninstall | `%TEMP%\ooa-install.log`; MSI log from `/l*v` | Every step and `ooa` command run by the installer |
| Audit checkpoints | `DATA\audit-checkpoints.log` | Hourly chain checkpoints |

The runner's log level (error, warning, info, debug) is set centrally: Runners > Update runners > Log level; runners
pick it up with the next heartbeat.

## 4. Audit trail and SIEM

Forward every audit entry to the SIEM with `syslog_target` and `syslog_protocol` (see
[08](08-security-hardening.md)), or subscribe a receiver to `audit.entry` and the run log lines in Administration >
Event subscriptions (Splunk's HTTP Event Collector format is built in). Useful actions to alert on: failed sign-ins and lockouts, `settings.update`,
`vault.rotate`, `run.lost`, `device.expired`, `license.*`, failed backups.

## 5. `ooa check`

```
ooa check [--data DIR] [--port N]
```

Read-only. Tests operating system, 64-bit, Java, memory, processors, data folder, free disk, port, database
connection, schema and encryption, whether the vault key opens stored credentials, whether repository files are
present, the HTTPS certificate, and the vault key source (PASS from `OOA_MASTER_KEY` or from a key store named by
`OOA_MASTER_KEY_SOURCE`; WARN only for a key file). One line each, PASS / WARN / FAIL with what to do; the last
line says `Ready.` or `N problem(s) to fix first.`; the exit code is the number of FAILs. Details in
[01](01-system-requirements-and-sizing.md).

Run it with the same account and environment as the service (same `--data`, same vault key variables); otherwise it
checks a different folder or cannot decrypt the settings.

## 6. `ooa agent diagnose`

```
ooa agent diagnose [--config FILE] [--workspace DIR]
```

Run on the runner, as the account the runner uses. It changes nothing.

| Line | Checks | Typical FAIL and fix |
| --- | --- | --- |
| Runner settings | `agent.json` readable | Not registered: `ooa agent register` |
| Name lookup | DNS for One CommandCenter host | Unknown host with `--proxy none`: fix DNS or the URL (only WARN when a proxy may resolve it) |
| Proxy | Which proxy is used for the URL | Shows `direct connection` or the proxy address |
| Network | TCP connect (direct connections only) | Firewall, wrong port, server down |
| Certificate | TLS handshake with the runner's trust settings | Not trusted: `--ca FILE` or install the company CA in Windows |
| One CommandCenter | `GET /api/health` answers 200 | No answer; for a proxy that asks for sign-in: `ooa agent network --proxy-user ...` |
| Clock | Server `Date` header within 60 seconds | Sync both with the same time server |
| Runner sign-in | A heartbeat with the device id and secret | Runner deleted or disabled: register again |
| Screen | unlocked / locked / no_desktop | WARN: screen agents will not start |
| Free disk space | 1 GB or more | WARN under 1 GB |
| Toolkits | Number installed | - |

The exit code is the number of FAILs.

## 7. Common problems and fixes

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Other computers cannot open One CommandCenter, but `http://localhost:8720` works on the server | The server listens on 127.0.0.1 (the default for `ooa server`, `ooa control-room`, `ooa service install` without `--host`, and installs made with `-ListenAddress 127.0.0.1` / `LISTEN_ADDRESS=127.0.0.1`), or a firewall blocks the port | Turn on HTTPS, then `ooa service uninstall` and install the service again with `--host 0.0.0.0`, or put a reverse proxy on the server; open the port in the firewall ([02](02-installation-windows.md)) |
| `CR.PORT_IN_USE` at start | Another One CommandCenter or program on the port | Stop it, or `--port 8721`; `ooa check` shows who answers |
| Runner shows **offline** | No heartbeat for 60 seconds: runner stopped, signed out, network, proxy, certificate, deleted registration | Run `ooa agent diagnose`; check the "OOA Runner" task or service is running; for screen runners make sure the user is signed in |
| Runner offline after One CommandCenter moved | Old address in `agent.json` | `ooa agent move --url https://new-address [--ca FILE]`, restart the runner |
| Runner registered but never online after a database restore elsewhere | The new One CommandCenter does not know the runner | Restore the data first; `agent move` refuses until then; or register again |
| `ooa agent diagnose` shows `FAIL Runner sign-in` | The runner was deleted or disabled in One CommandCenter, or its saved secret no longer matches (for example `agent.json` restored from an old copy after a rotation) | Register the runner again with a new key |
| Runs fail with `AGENT.LOST` | Runner stopped reporting for 2 minutes during a run (reboot, sleep, network) | Disable sleep on runners; check network stability |
| Runs fail at once with `RUN.NO_DESKTOP` | Screen agent sent to a runner whose screen is locked, signed out, or which runs as a service | Keep screen runners signed in and unlocked (dedicated account, no screen saver lock); do not set After unattended runs to lock on runners that get consecutive screen runs; send screen agents to desktop runners |
| `RUN.NEEDS_PERSON` | Unattended run hit a dialog without a timeout | Give dialogs a timeout or run attended |
| `RUN.TOOLKIT_MISSING` | Runner lacks a toolkit or version | Update the runner (Runners > Update) |
| Runner gets no work and shows "update One CommandCenter" | Runner newer than One CommandCenter | Upgrade One CommandCenter |
| Certificate error on runners (`PKIX path building failed`, `FAIL Certificate`) | Self-signed or private CA not trusted; name mismatch | `ooa tls export-cert --out ooa.cer` and `ooa agent network --ca ooa.cer`; register with a name that is in the certificate |
| Browsers warn about the certificate | Self-signed test certificate | Use a company certificate for production |
| Proxy error, `407` or "no answer" behind a proxy | Proxy needs sign-in, or the service account has different Windows proxy settings | `ooa agent network --proxy http://proxy:port --proxy-user USER --proxy-password-env VAR`; NTLM-only proxies: allow One CommandCenter's address without sign-in |
| `AGENT.PROXY_PASSWORD` | On Windows, the DPAPI-encrypted password cannot be read on this computer (copied config); on Linux and macOS, the `proxy-password` file cannot be read (missing, or another account); or the variable is not set | Set it again with `ooa agent network --proxy-user ... --proxy-password-env VAR`, as the runner's account |
| `AGENT.BAD_PROXY` | Proxy not `system`, `none` or `http://host:port` | Correct the value |
| One CommandCenter cannot reach SSO, Git or AI providers | Outbound proxy needed | Set `outbound_proxy` and `outbound_proxy_bypass` |
| `CR.DB_UPGRADE_NEEDED` at start | New release, data-only account | Stop, `ooa db migrate`, start |
| `CR.DB_NEWER` | Database upgraded by a newer release | Start the newer release, or restore a pre-upgrade backup |
| `CR.DB_CONFIG ... stored in clear text` | A plain `password=` in `ooa-db.properties` | `ooa db set-password` |
| `CR.VAULT_TAMPERED` or vault values cannot be read | Wrong vault key | Use the key the data was created with; `ooa check` shows "Vault key and database" |
| `CR.VAULT_KEY` at start | Key store unreachable, wrong format (not 32 bytes Base64), `prompt` without a console | Fix identity/network to the key store; `openssl rand -base64 32`; do not use `prompt` for services |
| `LICENSE.DB_REQUIRED` | Licence installed on the embedded database | Move to SQL Server or PostgreSQL first ([05](05-databases.md)) |
| `CR.RATE_LIMITED` (429) | More than 60 sign-ins/token calls a minute from one address, or 1,200 webhook/API task/MCP calls | Spread calls; behind a remote balancer all clients share its address |
| `CR.IP_BLOCKED` (403) | Address not on `ip_allow_list`; or, behind a load balancer not listed in `trusted_proxies`, the balancer's address is checked instead of the client's | Add the address, or list the balancer under Trusted proxies; from the server itself (directly, not through a proxy) you can always fix the list |
| Forgotten administrator password or lost authenticator | - | Stop the server, `ooa reset-admin [--user NAME] --data DIR` |
| Backup fails (`CR.BACKUP`) | Folder missing or no write access | Check `backup_folder` and the service account's rights |
| Restore refused: another vault key | Backup made with a different key | Put that key in place (`master.key` or `OOA_MASTER_KEY`) and retry |
| Service starts then stops | Error at start (database, key, port) | Read `DATA\logs\service-<mode>.log`; run the same command in a console |

## 8. Browser connector problems

The browser connector (extension plus native messaging host) lets agents work in the user's own Edge, Chrome or
Firefox. Set it up once per Windows user with `ooa browser-connector install`, load the extension, and check with
`ooa browser-connector status`. Details: [Browser connector](../user-guide/08-browser-connector-guide.md).

| Symptom | Cause | Fix |
| --- | --- | --- |
| Extension icon does not show **Connected** | Host not registered for this Windows user | Run `ooa browser-connector install` as that user, then restart the browser |
| `install` reported success but browsers still do not connect; `status` from another window shows nothing registered | It was run from inside a packaged (MSIX) application, such as a terminal or tool packaged from the Store. Windows redirects such an app's writes to `HKCU` and `%LOCALAPPDATA%` into the app's private copy, which the browsers never see. | Open a normal PowerShell or Command Prompt from the Start menu (not from inside the packaged app) and run `ooa browser-connector install` again. Verify with `reg query "HKCU\Software\Google\Chrome\NativeMessagingHosts\com.oneofficeautomation.bridge"` from a normal window. |
| Connected in one browser only | The extension is loaded per browser | Load unpacked `browser-extension` in each Edge/Chrome; Firefox uses `browser-extension-firefox` |
| Firefox add-on gone after restart | Temporary add-on | Use a signed add-on deployed by Firefox policy |
| Firefox not connecting right after loading | Firefox has not started the extension yet | Open the add-on's window once from the extensions menu |
| Company browsers block "Load unpacked" | Developer mode disabled by policy | Deploy the extension by browser policy; the extension id is `fnceeakhokkekgdehkobdpdehpfnemah` |
| Run JavaScript shows a "being debugged" bar | Page content security policy forbids evaluating code; the debugging interface is used briefly | Expected; in Firefox such steps stop with a message: run them in Edge or Chrome |
| Agents on a runner service cannot use the connector | A service has no desktop browser session | Use a desktop runner started at sign-in |

Files: host and manifests in `%LOCALAPPDATA%\OneOfficeAutomation\browser-connector`; registry keys
`HKCU\Software\Microsoft\Edge\NativeMessagingHosts\com.oneofficeautomation.bridge`, the same under
`Google\Chrome` and `Mozilla`; running hosts register under `%USERPROFILE%\.ooa\bridge`.
`ooa browser-connector uninstall` removes the host and keys (remove the extension in each browser).

## 9. Collecting information for support

1. `ooa check --data DIR` output (server) and `ooa agent diagnose` output (runner).
2. The service log (`DATA\logs\service-*.log`) around the time of the problem.
3. The run id and its downloaded log, or `<runner workspace>\logs\<run id>.log`.
4. `%TEMP%\ooa-install.log` for installation problems.
5. Product version (start-up lines, `ooa --version`) of One CommandCenter and the runner.

Remove passwords, keys and personal data before sending anything. The product never writes vault values to logs,
but agent outputs and inputs may hold business data.

## Related documents

- [README](README.md)
- [01. System requirements and sizing](01-system-requirements-and-sizing.md)
- [02. Installation on Windows](02-installation-windows.md)
- [04. Network, ports, proxies and certificates](04-network-ports-proxies-and-certificates.md)
- [05. Databases](05-databases.md)
- [07. Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md)
- [Browser connector](../user-guide/08-browser-connector-guide.md)
- [Enterprise checks](08-security-hardening.md)
