# Findings from writing the documentation (2026-10-28)

While writing the architecture, functional, infrastructure and user-guide collections, the writers read the code
closely and found places where the product misbehaves, surprises its users, or where the older single documents are
out of date. The new collections describe the product as it is; this list is what should change in the product or the
older docs. Items marked (checked) were confirmed in the code while reviewing.

## Product issues: likely bugs

| # | Area | What happens | Suggested fix |
| --- | --- | --- | --- |
| P1 | Install | A Command Center installed as a Windows service (`Install.cmd -Service`, MSI `SERVICE=1`) listens only on 127.0.0.1, because the installers call `ooa service install` without `--host` and the server's default is this computer only. Other machines (runners, a load balancer) cannot reach it. (checked) | Installers pass `--host 0.0.0.0` (or a parameter), with a warning to turn on HTTPS. |
| P2 | Studio | Saved connections do not work in Studio: `openConnections` is defined twice in studio.js (the model-connections one wins), and `StudioServer` answers `api/connections` from model-connections.json, so a step's Connection list is always empty. | Rename one function and give saved connections their own route. |
| P3 | Studio / repository | Every Studio "Agent check-in" goes live: `ControlRoomLink.checkin` always sends `production: true`. | Ask at check-in, or default to not live. |
| P4 | Install (MSI) | Without `DATA_DIR`, an MSI install keeps its data under `C:\Windows\System32\config\systemprofile`. Giving `ROLE` again on an upgrade tries to create the service a second time and fails. | Default `DATA_DIR` to ProgramData; skip service creation when it exists. |
| P5 | Vault | Rotating the vault key is refused when the key comes from `OOA_MASTER_KEY` or a key source, and nothing re-encrypts stored values; the older guide's "rotate in the key store" advice would make stored values unreadable. | A supported rotation path for external keys. |
| P6 | Work queues | Deferred work items never start again by themselves; only `new` and `retry` items are picked up. | Pick up deferred items when their time comes. |
| P7 | Schedules | Missed times after downtime are not caught up, and nothing checks for an overlap with a run that is still going. | Options for catch-up and overlap. |
| P8 | Repository | Deleting an agent checks schedules, triggers and queues but not API tasks, so an API task can point at a deleted agent. | Include API tasks in the check. |
| P9 | Versions | `ControlRoom.VERSION` and `Agent.VERSION` say 0.2.0 while the build is 0.1.1; a runner newer than the Command Center's version is refused work. | One version source. |
| P10 | Proxies | Behind a remote load balancer, rate limits and the IP allow list see the balancer's address (`X-Forwarded-For` is only trusted from the same computer, and the allow list never reads it). | A setting for trusted proxy addresses. |
| P11 | `ooa check` | The vault-key check only looks at `OOA_MASTER_KEY`; with `OOA_MASTER_KEY_SOURCE` it still warns "key file in the data folder". | Check both. |
| P12 | Runner (Linux) | Runner proxy passwords use Windows DPAPI, so `--proxy-password-env` is refused on Linux. | Another protection on Linux. |
| P13 | Console | Licenses page shows for `users.manage`, but installing a licence needs `settings.manage`; Teams page shows only for `teams.manage`, so team leads cannot reach their team. | Align page visibility with the API. |
| P14 | Requests | "Largest upload (MB)" defaults to 5 MB for every JSON request, so larger Excel work-item and licence uploads fail with CR.TOO_LARGE. | Raise for those routes. |

### Status: P1 to P14 fixed (2026-10-28)

All fourteen issues above are fixed in the code, and the collections describe the fixed product.

| # | How it was fixed |
| --- | --- |
| P1 | The setup package (`-ListenAddress`) and the MSI (`LISTEN_ADDRESS`) install the Command Center service on `0.0.0.0` by default, so runners on other machines reach it; the installer logs a reminder to set up HTTPS (`ooa tls`). `127.0.0.1` keeps it on this computer only. |
| P2 | Model connections moved to their own Studio route (`api/model-connections`); **Manage connections…** and a step's **Connection** list show the saved connector connections (`connections.json`). |
| P3 | Studio's **Agent check-in** opens a dialog with **What changed** and the tick **Make this the live version** (off by default). Off: a new version only; on: live, or a production request when approval is on. Publishing workflows and forms still makes them live. |
| P4 | Without `DATA_DIR` the MSI puts the data in `%ProgramData%\OneOfficeAutomation\Command Center`. Running the setup or the MSI again keeps an existing service ("OOA Command Center" / "OOA Runner") with its settings; to change them, `ooa service uninstall` and install again. |
| P5 | `ooa vault rekey --new-key-env VAR` (Command Center stopped) re-encrypts every stored value for keys in `OOA_MASTER_KEY` or a key store. |
| P6 | Deferred work items with a time are picked up again by processing once that time has passed. |
| P7 | Schedule options `missed_runs` (`once`/`skip`) and `overlap` (`allow`/`skip`), shown as "Times missed while the Command Center was down" and "When the previous run is still going". |
| P8 | Deleting an agent also checks API tasks (`CR.IN_USE` "API task NAME"). |
| P9 | The version is 0.2.0 everywhere: `ControlRoom.VERSION` and `Agent.VERSION` come from `sdk` `BuildInfo`, generated from the root `build.gradle.kts`. |
| P10 | New setting **Trusted proxies** (`trusted_proxies`). From this computer or a trusted proxy, the client address is the last `X-Forwarded-For` address that is not a trusted proxy; the allow list, rate limits and audit trail use it. Other clients cannot choose their address, and a local reverse proxy no longer lets remote clients skip the allow list. |
| P11 | `ooa check` reports the vault key as PASS from `OOA_MASTER_KEY` or from `OOA_MASTER_KEY_SOURCE` (naming the key store); WARN only for a key file. |
| P12 | On Linux and macOS the runner keeps its proxy password in a file `proxy-password` next to its config, readable by its owner only (`file:PATH`); `--proxy-password-env` works there. Windows still uses DPAPI. |
| P13 | Licenses shows for `users.manage` or `settings.manage` (installing still needs `settings.manage`); Teams shows for `teams.manage` or for anyone who leads a team (`auth/me` returns `leads_team`). |
| P14 | "Largest upload (MB)" (`max_upload_mb`) defaults to 25. |

## Smaller product points

- Studio: the Run window has no Step out (the dock has it); Record and Capture "Open a new page" always use Edge; the
  Window editor's browser filter has no Firefox; Delete moves to `.trash` with no restore in Studio; Versions shows in a
  plain alert box; checked-out copies go to a folder named `Control Room`.
- Studio groups: `ooa.aws_ec2` and `ooa.web_agent` were not in a group. **Fixed:** AWS EC2 is under "APIs and
  databases" and Web goal agent under "AI". `ooa.forms` still falls under More toolkits.
- Word toolkit: Save as offers docx and txt only (no PDF).
- Help texts: `agent register --key` says "Devices > Registration keys" (now Runners > Register a runner);
  `browser-connector install/status` say "Edge and Chrome" (Firefox too); `db configure --windows-auth` says `db_owner`
  where `db init` gives `db_datareader` and `db_datawriter`.
- Code comments out of date: `ApiTasks` (thread count), `CredentialVault` and `EnvVault` (OpenBao, CyberArk).
- The Windows service starts Java with `-Xmx1g`; Linux and container use `-Xmx2g`.

## Older documents now out of date

- CONTROL_ROOM_AND_AGENT.md: menu sections (the console has Build, Manage, Operations, Administration, AI), default
  data folder (`OOA Command Center`), run history source filter, schema version, runner log location, key rotation.
- ARCHITECTURE.md: schema versions (code is at 23), toolkit and action counts, runner polling (long poll), custom
  toolkit upload and Git sync listed as gaps but built, variable types and directions, third-party libraries.
- platform/README.md: package count, Command Center databases (SQL Server missing), future-work list.
- ENTERPRISE_CHECKS.md (forwarded addresses), SECURITY_REVIEW.md (PostgreSQL missing), VALIDATION_REPORT.md (menu).
