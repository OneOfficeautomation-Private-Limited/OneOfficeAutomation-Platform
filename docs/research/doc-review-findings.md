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

## Smaller product points

- Studio: the Run window has no Step out (the dock has it); Record and Capture "Open a new page" always use Edge; the
  Window editor's browser filter has no Firefox; Delete moves to `.trash` with no restore in Studio; Versions shows in a
  plain alert box; checked-out copies go to a folder named `Control Room`.
- Studio groups: `ooa.aws_ec2` and `ooa.web_agent` were not in a group (fixed with this change); `ooa.forms` still falls
  under More toolkits.
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
