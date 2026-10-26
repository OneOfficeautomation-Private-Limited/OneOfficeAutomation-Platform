# One Command Center and One Runner: setup and daily use

## 1. Start One Command Center

**On a developer computer, use the one-step start**: double-click `platform\scripts\start-ooa.bat` (or
`.\gradlew.bat :cli:run --args="server"`). One Command Center, One Agent Studio (at `/studio/`) and a One Runner for this
computer start together on http://localhost:8720. The runner registers itself, so section 3 is only needed for other machines.

To run One Command Center on its own (for example on a server), from PowerShell in the `platform` folder:

    .\gradlew.bat :cli:run --args="control-room"

Open http://localhost:8720. On the very first start the console window prints a one-time password for the user `admin`.
Sign in with it and choose your own password (at least 12 characters, letters and digits).

Data lives in `C:\Users\<you>\OOA Control Room` (the embedded database and the vault key file). Back this folder up.
The database upgrades itself on start (schema versions 1 to 3).

| Option | Meaning |
| --- | --- |
| `--port 8721` | Use another port |
| `--host 0.0.0.0` | Accept connections from other machines (turn on HTTPS first, see below) |
| `--data D:\OOA\cr` | Keep data elsewhere |
| `ooa db configure ...`, then `ooa db init` | Use SQL Server or PostgreSQL ([DATABASE.md](DATABASE.md)); the server then reads `config\ooa-db.properties` |

**Outgoing proxy** (Administration > Settings > Outgoing connections): the Command Center's own connections to the
internet (single sign-on, Git, AI model connections, external vaults) go through `http://host:port` when set, except
the host names listed to reach directly and this computer. Every node picks a change up within a minute.

Production: set the environment variable `OOA_MASTER_KEY` (32 random bytes, Base64) and keep it outside the data folder.
Without it a key file is created next to the database. Or keep the key in a key store and set `OOA_MASTER_KEY_SOURCE`:

| `OOA_MASTER_KEY_SOURCE` | The key is read from | Signed in with |
| --- | --- | --- |
| `azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET` | An Azure Key Vault secret | The machine's managed identity, or `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` |
| `aws-secrets:REGION/SECRET-ID` | An AWS Secrets Manager secret | `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, the container's task role, or the EC2 instance role |
| `gcp-secret:projects/P/secrets/S/versions/latest` | A Google Secret Manager secret | The machine's service account |
| `prompt` | Nobody stores it: an operator types it when the Command Center starts (manual unlock; not for a service) | |

The secret holds the key as Base64 (`openssl rand -base64 32`). It is read at start and kept only in memory. Give the
machine's identity read access to that one secret. Rotate it in the key store, with a planned restart. Hardware
security modules (PKCS#11) are not supported yet.

**Before going live**, `bin\ooa.bat check` tests what the install needs: Java, memory, free disk space, the port, the data
folder, the vault key, the database connection and schema, and HTTPS. Each line is PASS, WARN or FAIL;
the installer runs it first. For a cloud virtual machine or the Linux container image see
[CLOUD_INSTALL.md](CLOUD_INSTALL.md).

### HTTPS

One Command Center serves HTTPS itself (TLS 1.2 and 1.3) once it has a certificate. Stop it, then from `bin`:

| Command | What it does |
| --- | --- |
| `ooa tls configure --pfx C:\certs\ooa.pfx` | Use the company's certificate: a .pfx/.p12 file with the private key |
| `ooa tls set-password` | Store the .pfx password, encrypted with the vault key (or `--from-env VAR`) |
| `ooa tls self-signed --host ooa.example.com` | Make a certificate for tests; browsers warn about it |
| `ooa tls show` | Names and validity of the certificate in use |
| `ooa tls export-cert --out ooa.cer` | The certificate without its key, for runners and browsers to trust |
| `ooa tls off` | Back to plain HTTP (the settings are kept aside) |

All take `--data DIR` when the data folder is not the default. The settings live in `config\ooa-tls.properties`. With
HTTPS on, the port no longer answers plain HTTP, the session cookie is `Secure` and responses carry
`Strict-Transport-Security`. The runner and Studio built into `ooa server` keep working without trusting the
certificate: they use a second, plain-HTTP listener that only this computer can reach (127.0.0.1).

Runners on other machines must trust the certificate. A certificate from a company authority that Windows and Java
already trust needs nothing more. For a self-signed one, copy `ooa.cer` to the runner and register with `--ca ooa.cer`
(section 3). A reverse proxy in front (IIS, nginx) still works as before; leave HTTPS off on the server then.

### Install with the setup package or the MSI

Customers install from `ooa-setup-<version>.zip` (Install.cmd, guided or `-Silent`) or `ooa-<version>.msi`
(`msiexec /qn ROLE=CommandCenter SERVICE=1` or `ROLE=Runner URL=... REGISTRATION_KEY=...`). Both bring their own
Java. Build them with `gradlew :cli:setupDistZip` and `scripts\build-msi.ps1` (WiX Toolset 5); CI builds and
install-tests the MSI on every push. The package's README.txt lists every option.

### Run as a Windows service

From an administrator command prompt, in the installed `bin` folder:

    ooa service install --mode control-room --data D:\OOA\cr --port 8720

| Option | Meaning |
| --- | --- |
| `--mode server` | Command Center with Studio (default); no built-in runner, since a service cannot use the desktop |
| `--mode control-room` | Command Center only |
| `--mode agent` | A runner for unattended work that needs no desktop (see section 3 for desktop runners) |
| `--name NAME` | Service name (default "OOA Command Center", or "OOA Runner") |
| `--account DOMAIN\user --password-env VAR` | Run as this account instead of Local System; the password is read from the variable |
| `--no-start` | Install only |

The service starts with Windows and restarts after a failure (three times, a minute apart). Manage it with
`ooa service start|stop|status|uninstall [--name NAME]` or in Windows Services. Its log is
`<data>\logs\service-<mode>.log`. Every path is written out in full when the service is installed, because a service
account has another user profile. To try it on a test machine, run `platform\scripts\test-windows-service.ps1` as
administrator: it installs a throw-away service with HTTPS, checks it, and removes it.

## Finding your way: the menu

The menu on the left has **Home** and three groups (each can be collapsed):

| Group | Pages |
| --- | --- |
| **Build** | Agent repository, One Agent Studio (opens in a new tab), One Workflow, Work queues |
| **Run & Monitor** | Agent Runs (Live and History), Task inbox, Schedules, Triggers, Runners |
| **Govern** | Users, Roles, One Agent Vault, Shared values, Toolkits, One Model Connections, AI governance, One Audit Trail, Settings |

People only see the pages their role allows.

Pages update by themselves: the console keeps one request open to the Command Center (`GET /api/changes?since=TOKEN&wait=25`), which answers the moment something changes, and the open page is drawn again within about a second. Lists with an export also have **Columns**, to hide columns you do not need (remembered in this browser).

## 2. People, roles and sign-in

| Built-in role | Can |
| --- | --- |
| Administrator | Everything: users, roles, runners and runner pools, live production versions, settings, the One Audit Trail |
| Agent builder | Check agents in and out, run them, manage schedules, triggers, work queues and their own vault folders |
| Agent operator | View agents, run and stop them, add and work on work queue items |
| Viewer | Read-only |

- **SAML single sign-on** (Administration > Settings > Single sign-on (SAML 2.0)): for Entra ID, Okta, ADFS and other
  SAML identity providers. Give the provider the metadata address shown there (`/api/auth/saml/metadata`), then fill in
  its sign-in address, entity id and signing certificate. The sign-in page gets a SAML button; new people get the
  role for new people, as with OpenID Connect. Have the provider sign the assertion (or the response) and leave it
  unencrypted: encrypted assertions are refused. Sign-ins must start at the Command Center's sign-in page.
- **Two-step sign-in**: anyone with a local password can turn on codes from an authenticator app (Microsoft
  Authenticator, Google Authenticator or another time-based app) in the user menu > Two-step sign-in. They get ten
  one-time recovery codes. Administration > Settings > Security can require it for administrators or for everyone;
  people who still need to set it up can do nothing else until they have. Single sign-on users are left to their
  identity provider, and API keys are not affected. For a lost phone, an administrator uses Reset two-step on the
  Users page (`ooa reset-admin` also clears it). Studio's connect form has a Code box for these accounts.
- **Custom roles** (Administration > Roles): tick the permissions a role needs, for example a "queue clerk" that may
  only see and add work items. Roles in use cannot be deleted.
- **Switching off built-in roles** (Administration > Settings > Security): a built-in role you do not use (Agent
  builder, Agent operator, Viewer) can be switched off. It then gives no permissions and cannot be given to anyone;
  people who still have it keep only their other roles. The Administrator role cannot be switched off.
- **Several roles per person** (Users > Edit): besides the main role, a person can have **further roles**. They have every
  permission of each of their roles, and rules that name a role (`role:bot_runner` in folder permissions, vault folders,
  work queues, workflow tasks, model connections) apply to them for each role. Administrator is only ever a main role.
  Edit also keeps a person's first name, last name and a description.
- **New users** (Users > New user) get a one-time temporary password and must change it at first sign-in. Five wrong
  passwords lock an account for 15 minutes. Administration > Settings can disable accounts that have not signed in for
  a number of days.
- **Password rules** (Administration > Settings > Security): minimum length, how many earlier passwords may not be used
  again, and after how many days a password expires. Someone whose password has expired signs in and must choose a new
  one before doing anything else. Single sign-on users are not affected.
- **Single sign-on** (Administration > Settings > Single sign-on): enter the OpenID Connect issuer URL and client id of
  Microsoft Entra ID, Okta, Google, Keycloak or another provider. The client secret is a vault reference, never typed into
  settings. Register the redirect address shown there with the provider. The sign-in page then shows a "Sign in with
  your organisation account" button.
  - On the first sign-in, an existing account with the same verified e-mail address or user name is linked.
  - Otherwise a new account is created with the role you choose. Leave that role empty to allow existing accounts only.
  - The provider's signature, issuer, audience, expiry and a one-time nonce are checked, using PKCE.
- **Teams** (Govern > Teams): named groups of people, each person a member or a lead.
  - Name a team as `team:finance` in folder permissions, vault folders (managers, participants, consumers) and work queue
    members. Adding someone to the team gives them all of that.
  - Administrators (`teams.manage`) create teams, and leads change their own team's members.
  - A team that a rule still names is not deleted.
- **Tokens for other systems.**
  - `POST /api/auth/token` with `{"api_key": "ooak_..."}` exchanges an API key for a session token (8 hours, extended
    while used).
  - `POST /api/auth/refresh` gives a new token and ends the old one.
  - `GET /api/auth/token` tells whether the token you send is valid, for whom and until when, without extending it.
- **Users** can be deleted when they own no schedules, triggers, API tasks, running queues or single-owner vault folders.
  Their sign-ins, keys, team places, own vault values and every rule naming them go; the audit trail keeps their name.
- **API keys** (top bar > API keys): other systems call the API as you with `Authorization: Bearer ooak_...`. A key is
  shown once, can expire, and can be revoked at any time. Only a hash is stored.
- **Announcement** (Administration > Settings): a banner shown to everyone, for example before maintenance.
- **Forgotten password**: stop ONEOFFICEAUTOMATION (Ctrl+C in its window), then in the `platform` folder run
  `.\gradlew.bat :cli:run --args="reset-admin"` (or `--args="reset-admin --user jane.doe"`). It prints a one-time
  password, re-enables and unlocks the account, and records the reset in the One Audit Trail. Start the server again
  and sign in; you are asked to choose a new password. Earlier versions kept their data in `OOA Control Room`; it is
  still used when it holds a database.

## 3. Register a runner and start the One Runner

1. Runners > Register a runner. Copy the command; the key works once, for 24 hours.
2. On the machine, in the platform folder:

       .\gradlew.bat :cli:run --args="agent register --url http://localhost:8720 --key ooareg_..."
       .\gradlew.bat :cli:run --args="agent start"

   For a Command Center on HTTPS with a self-signed certificate, add `--ca ooa.cer` (from `ooa tls export-cert`). The
   runner keeps a copy next to its settings and trusts it from then on. Runners on Windows also trust the Windows
   certificate store, so a company certificate authority rolled out by Group Policy needs nothing more.

   **Proxies:** by default the runner uses the Windows proxy settings (including automatic configuration scripts).
   Use `--proxy none` or `--proxy http://proxy.corp:8080` to choose, and `--proxy-user USER --proxy-password-env VAR`
   for a proxy that asks for a user name and password (stored encrypted with Windows DPAPI). Change them later with
   `ooa agent network`. Proxies that only accept Windows (NTLM) sign-in are not supported.

3. To start the runner automatically at sign-in (and restart it if it stops):

       ooa agent install-task

   (`ooa agent uninstall-task` removes it; from a source checkout, `scripts\install-agent-task.ps1` does the same.)
   Runners that drive the desktop must run this way, in a signed-in session. A runner for work without a desktop can
   instead be a Windows service: `ooa service install --mode agent`.

**Temporary runners** (pooled virtual desktops, short-lived build machines): tick **Temporary runner** when making
the registration key. Such a runner is removed by itself once it has been offline for the hours set in Administration
> Settings (8 by default), and its licence seat is freed. Deleting any runner frees its seat too.

**Runner secrets** are replaced by themselves every 30 days (Settings > Runner secret days; 0 turns it off): the runner
asks for a new secret with its heartbeat and saves it, and the old one keeps working for a short while in case the
save is interrupted. No one has to visit the machine.

**Toolkits on the runner**: each runner reports which toolkit versions it has, shown in its details. A run that needs a
toolkit (or version) the runner lacks does not start there: it fails at once with `RUN.TOOLKIT_MISSING`, naming what is
missing.

**When the Command Center moves** to a new address with its data, run `ooa agent move --url https://new-address:8720`
on each runner (and restart it): it keeps its registration. See [CLOUD_INSTALL.md](CLOUD_INSTALL.md).

**When a runner will not connect**, run `ooa agent diagnose` on it. It checks the runner's settings, name lookup, the proxy and
network, the certificate, the clock, the Command Center's answer, the runner's sign-in, the screen, free disk space
and toolkits, and says what to fix.

The runner runs each agent in its own process, so a crashing agent never stops the runner. Its log files are in
`C:\Users\<you>\OOA Agent Workspace\logs`.

**The runner's screen.** Each runner reports its screen with every heartbeat, shown in the Runners list: Unlocked
(someone is signed in), Locked, or No desktop (a runner running as a Windows service). A run of an agent that uses the
screen (keyboard, mouse, windows, images, desktop dialogs) does not start on a locked runner or one without a desktop:
it fails at once with `RUN.NO_DESKTOP` and the reason, instead of failing halfway. Agents that need no screen run
anywhere. Signing a user in on a locked or signed-out runner by itself is not built yet; keep unattended runners that
use the screen signed in and unlocked (for example a dedicated account with the screen saver lock off).

**Runner pools** (Runners > Runner pools) group runners. A run, schedule or work queue sent to a pool goes to its least
busy online runner.

**Runner updates**: the Runners list shows whether each runner is on this Command Center's program files. **Update**
on a runner (or Settings > Update runners automatically) makes an idle runner download the files it needs, check
every checksum, swap its program folder and start again. A runner service is started again by Windows, a runner at
sign-in by its task. The bundled Java is not updated this way. A runner installed for all users but started at a
user's sign-in may not be allowed to write to Program Files; update it with the MSI instead.

**Work arrives at once**: a waiting runner keeps one request open (up to 25 seconds), and the Command Center answers it
the moment a run is queued or one of its runs is stopped, so runs start without the old polling delay.

**Runner settings** (Runners > Update runners): tick runners, then change for all of them at once:

- **Log level** (error, warning, info, debug) of the runner's own log. Runners pick it up with their next heartbeat, so
  no one has to visit the machine.
- **After unattended runs**: leave the screen as it is, lock it, or sign out, so nobody walks up to a signed-in
  machine. Runners pick it up with their next heartbeat.
- **Runner type**. A single-user runner is the default runner of one person; a multi-user runner can be the default
  runner of several people (runners nobody set are multi-user). The Runners list shows whose default runner each one is.

## 4. The agent repository: check-in and check-out

- Your One Agent Studio workspace (My Workspace) is **private**. The Command Center's agent repository is **public** and
  keeps every version.
- In Studio, connect at the bottom of the Explorer (One Command Center section).
- **Agent check-in** sends the open agent as a new version. Agents are validated first; problems block the check-in.
  Workflows and forms are published with **Publish to Command Center** in their designers.
- **Approval before production** (Administration > Settings > Security): a version then goes live only when a second
  person with the approval right (administrators, or a custom role with *repo.approve*) approves it. Check-in "as
  production", **Ask to make live** in the version list and imports as production create a request; **Production
  approvals** in the Agent repository lists them. Nobody decides their own request; every step is in the audit trail.
- **Git sync** (Administration > Settings > Git sync of the agent repository): mirrors the repository to a Git branch
  on GitHub, GitLab, Azure DevOps or any Git server, with the latest version of every agent as indented JSON under
  `agents/` and `ooa-repository.json` listing each one's latest and live version. Give the remote, the branch and an
  access token kept in the vault (a vault reference); tick "Push after every check-in" or use **Save and sync now**.
  Each change becomes a commit with its comment and author. The Command Center stays the source of truth: changes
  made in Git are overwritten at the next sync.
- **Agent check-out** locks a public agent for you and copies it into your workspace; others see who has it. Checking
  in releases the lock; **Undo check-out** releases it without a new version.
- Administrators choose the **live production version**. Runs, schedules, triggers, work queues and workflows always use it.
- Each agent can record the **minutes of manual work** one successful run saves. Home shows the hours saved over 30 days;
  agents without a value use the default from Settings.
- Administration > Toolkits lists every toolkit with its version, number of actions and the agents that use it. Switching
  a toolkit off stops agents that use it from being checked in or run; the core toolkit is always on.

**Folders and folder permissions.** Agents live in folders (the path before the name). **New folder** creates one,
even empty. An administrator can give a folder **permissions**: roles or users get one level each.

| Level | What it allows |
| --- | --- |
| View | See the agents and export them |
| Run | Also run and schedule them |
| Edit | Also check out, check in, delete and recover |
| Manage | Also set the live version and labels |

How permissions apply:

- The nearest folder with permissions decides. A sub-folder without its own follows its parent.
- A folder with permissions shuts out everyone it does not name. Folders without permissions (up to the root) follow the roles alone.
- Role permissions still apply: folder access never grants what the role does not allow.
- People with the `repo.permissions` permission (administrators) see everything.

**Delete and recover.**

- **Delete** removes an agent from the list and stops it running; its versions are kept, and **Deleted agents** brings it back.
- An agent still used by a schedule, trigger or work queue is not deleted (they are listed).
- An empty folder can be deleted (no agents and no files).
- **Move** (agent details) moves one agent to another folder with its versions, labels, analysis reports, the schedules,
  triggers, queues and API tasks that run it, and its run history. It is refused while someone else has it checked out,
  or while a workflow names it by path.
- **Copy** (agent details) makes a new agent from the latest version: version 1, not live, with its own agent id (the old
  id plus `_copy`). Schedules and history are not copied.
- **Rename folder** renames or moves a folder with everything in it (its files too): agents with all versions, labels and analysis
  reports, the schedules, triggers, queues and API tasks that run them, their run history, sub-folders and folder
  permissions. It is refused while someone else has an agent in it checked out, or while a workflow names one of its
  agents or forms by path (change the workflow first).

**Files in the repository.** Templates, workbooks, lists and pictures that agents use live next to the agents, in the
same folders and under the same folder permissions.

- **Upload file** (under the agents of a folder) adds a file of up to 25 MB; uploading again makes a new version. Download
  and Delete are on each row. Agent files (`.flow.json`, `.process.json`, `.form.json`) are checked in from Studio instead.
- An agent lists the files it needs in its contract, `requires.files` (for example `"Finance/Templates/invoice.xlsx"`).
  Before the run, the runner places each file at the same path in its workspace, so the agent opens
  `Finance/Templates/invoice.xlsx`. Up to 50 MB go with one run.
- The person the run is for must be allowed to view each file's folder. A missing or forbidden file fails the run before
  it starts, with the reason.

**Labels and dependencies.**

- **Versions > Label** puts a label (UAT, Release-3 …) on a version. A label is on one version at a time, and runs and
  exports can ask for an agent by label.
- The agent's details show the agents it calls, the agents that call it, and its toolkits.

**Moving agents between Command Centers (lifecycle).**

- **Export / promote** makes a bundle of the ticked agents at a version (production, latest, a number or a label) and,
  optionally, every agent they call. Each agent's exact content goes in with its SHA-256, and the bundle has a checksum.
- **Import** checks the bundle and validates every agent as check-in does, with folder permissions. Then it stores all of
  them in one go, or none. An agent that already exists is skipped or gets a new version; history is never replaced.
  Import can put the agents under another folder and make the versions live.
- **Promote** does both at once against another Command Center. Create an API key there as someone allowed to import,
  store it in a vault folder here, and give its reference. The key is read from the vault and sent only to that address.
- Exporting and importing need `lifecycle.export` and `lifecycle.import` (administrators by default).

## 5. Running agents

- Agent repository > Run: choose a runner, a runner pool, or **run-as users**, and give inputs. Optionally set a
  priority (urgent runs start first on a busy runner) and a **callback URL** that receives the result as JSON when the
  run ends (3 attempts).
- **Run-as users.**
  - Each user can have a **default runner** (Users > Default runner).
  - A run for several run-as users starts one run each, on that user's default runner. The agent's vault credentials are
    checked against that user.
  - The list of runs shows "admin as jane".
  - Over the API: `POST /api/runs` with `run_as_users: [...]` and/or `device_ids: [...]`. Each target gets its own run,
    and a target that cannot run is reported while the others start.
- **Run mode:** unattended (default), attended, or headless. The runner acts on it:
  - **Unattended:** nobody is at the runner. A message box, prompt or file picker without a timeout fails at once
    (`RUN.NEEDS_PERSON`) instead of waiting forever; one with a timeout still shows and closes by itself. Forms fail,
    and AI tool approvals are refused. After the run the runner can lock its screen or sign out (below).
  - **Attended:** someone is at the runner; dialogs and forms wait for them as usual.
  - **Headless:** no desktop at all. Agents that need one are refused when the run starts, and browsers open without
    a window.
- A runner can be **removed** when nothing is queued or running on it and no schedule or trigger uses it. It is taken off
  pools and default runners.
  Credential inputs are vault references such as `{"$credential": "finance/erp/password"}`, never the secret itself.
- **Agent Runs > Live** shows every queued and running agent: the runner, who started it and how, how long it has run, a
  progress bar and the step running now. Pause, Resume and Stop reach the runner within seconds.
- **Agent Runs > History** lists finished runs with status, duration, steps and error code. Filter by status, agent,
  runner, source (manual, API, schedule, trigger, work queue, workflow, AI assistant), person, dates and free text, with
  paging and CSV export. **Rerun** runs the live production version again with the same inputs.
- Selecting a run shows its details:
  - the error, with the failed step and a suggested fix
  - a step-by-step timeline with durations
  - the full log (filter it, or **Download log**)
  - the last value of each variable
  - the inputs and outputs
- **Schedules** run every N minutes, daily, or weekly in a time zone, on a runner or a pool. They can run as a
  **run-as user**, on that person's default runner unless a runner is given, with credentials checked against them.
  Tick several schedules to enable, disable or hand them to another owner at once.
  - A **holiday calendar** skips the dates you list.
  - Schedules can be renamed or handed to another owner; runs use the owner's permissions.
  - A schedule whose owner is disabled does not run, and this is recorded in the One Audit Trail.
- **Triggers** start agents on events. Studio keeps its own local triggers for development. Trigger kinds:
  - **On the runner** (the One Runner polls its own machine every 2 s): file arrives, file changed, file deleted, folder
    created, folder deleted (optionally with sub-folders); window opened or closed (title pattern; Windows); process
    started or stopped (by name); Windows service started or stopped; global hotkey (Windows, needs the Window toolkit).
  - **On the Command Center** (polled on each trigger's interval, 15 s or more): new email in an IMAP folder (from and
    subject filters, password from the vault), new email in Microsoft 365 Outlook through Graph (token, or app sign-in with
    a client secret from the vault), new or updated ServiceNow records matching an encoded query, and the universal
    poller (call a URL, take items with a JSON path, fire once per new id).
  - **Webhook**: an HTTP call with the trigger's secret.
  - The agent receives `trigger_event` (all details) and, by kind, `trigger_file`, `trigger_window`, `trigger_process`,
    `trigger_service`, `trigger_hotkey`, `trigger_email`, `trigger_record`, `trigger_item` or `payload`, when it declares them.
  - **Edit** renames a trigger, hands it to another owner, or sets the run-as user its runs use.
  - Triggers can be paused and resumed (`POST /api/triggers/{id}/pause|resume`); `GET /api/triggers` shows the fire
    count, last fired time and last error. `POST /api/triggers/{id}/poll` checks a server trigger at once.
- UI agents can add **interrupt rules** (`ooa.interrupts`): when a window or element appears, click, press keys, close
  the window or run a child agent. Desktop, window and browser actions check them before acting and when an element is
  missing. Browser element actions and window lookups also **self-heal**: when the selector or title finds nothing, the
  alternatives recorded with the element (Pick records them) are tried in order and the one that worked is logged.

**Code analysis.** Agent repository > Code analysis sets the policy, a set of rules, each on or off and an error or a warning:

- Passwords, tokens or keys typed into steps.
- Forbidden actions or toolkits.
- No Try/Catch in larger agents.
- Empty Catch blocks.
- No description.
- Variable naming.
- Unused variables.
- Switched-off steps.
- Long fixed delays.
- Too many steps.
- Deep nesting.

Every check-in is analysed and the report is kept for that version. With "Refuse check-ins that have errors", a
check-in with errors is refused (`CR.POLICY_VIOLATION`, with the findings). An agent's **Code analysis** shows its
report and analyses again. The dialog also downloads a report for every agent.
API: `POST /api/repo/analyze`, `GET /api/repo/analysis?path=`, `GET /api/repo/analysis/report`, `GET`/`PUT /api/repo/policy`.

**API tasks** (Build > API tasks) put an agent behind its own address and token, for other systems to call like a web service:

- `POST /api/apitasks/{id}/run` with `X-OOA-Task-Token` and `{"inputs": {...}}` runs the agent's live version on its
  runner or pool, as the person who made the task, at high priority.
- It waits up to the task's limit (1-300 s) and answers `{status, run_id, outputs, error}`. The answer is 200 when the
  run has finished; when it takes longer, the answer is 202 and the caller asks `GET /api/apitasks/{id}/runs/{run_id}`.
- The token is shown once, works only for its task, and can be replaced; the task can be switched off.

## 6. Work queues

Work queues hold items (invoices, tickets, orders) that an agent processes one at a time on a runner pool.

1. Work queues > New work queue: choose the agent, the runner pool, the attempts per item and a service level in
   minutes, and optionally a **work item model**.
   - A model (Work item models) lists the fields every item has, each text, number, true/false or date, optionally required.
   - Items are checked against it: required fields present, values of the right type, no unknown fields. CSV cells are
     converted to the field's type.
   - While items use a model, it can gain optional fields but not lose fields or change them.
2. Add items by pasting JSON or CSV, or by uploading a file. A `reference` column names each item; a `priority` column
   puts it first. An **Excel workbook** (.xlsx) works the same way: its first sheet, or the one you name, with a header
   row; dates come as yyyy-mm-dd. Items can also be added through the API (`POST /api/queues/{id}/items`,
   `/items/csv` with CSV text, or `/items/xlsx` with the workbook as Base64).
3. Start processing. The agent receives each item in the input `work_item` (a dictionary) and its id in `work_item_id`.
   Its outputs are kept with the item. A failed item is retried until its attempts are used.
4. The queue shows counts by status, items past the service level, and each item's result or error with a link to its run.
   - Failed or deferred items can be retried, waiting items can be deferred, and items not being worked on can be deleted.
   - **Open** an item to change its data, reference or priority (the form follows the model).
   - **Export CSV** downloads the items (filtered by status). Cells that a spreadsheet would read as a formula are made safe.
5. **Export definition** saves a queue's settings, work item model, members and, if you choose, its waiting items as
   a file. **Import queue** makes a queue from such a file:
   - the model is reused when one with the same name and fields exists;
   - the agent and runner pool are linked when they exist here;
   - anything missing is listed.
6. **Members** (optional) limit who can use a queue.
   - Owners manage it, participants add and change items, and consumers start processing and retry items.
   - Without members, the roles alone decide.
   - People with `queues.all` see every queue.

## 7. One Workflow and the task inbox

A workflow can have a **start form** (`"start_form": "Requests/Purchase.form.json"`). Its fields are asked when the
workflow starts, and data from the API is checked against them. A start can carry a **reference** (for example a
ticket or order number) to find the request again: `GET /api/workflows/runs?reference=PO-1`,
`GET /api/workflows/runs/{id}` (with its tasks), and `GET /api/workflows/form?path=` for the start form.


A workflow chains agents, people and decisions. Design it in One Agent Studio (Create > Workflow) with these stages:

| Stage | What it does |
| --- | --- |
| Agent task | Runs an agent on any online runner (or a pool) as the person who started the workflow. Its outputs join the workflow data. |
| Human task | Puts a form in the Task inbox of a user or role (`user:jane`, `role:bot_runner`), optionally with a due time. The answers join the data, also as `$form.field`. |
| Approval | Asks a person to approve or reject. A rejection ends the workflow, continues anyway, or jumps to a stage you choose. |
| Decision | Evaluates a condition such as `$amount > 1000` and goes to one stage or another. |
| Notification | Sends an inbox notice to `user:` and `role:` recipients, and an e-mail to plain addresses (once e-mail is set up, section 10b; with an optional subject). Text can use `${name}`. |

1. Publish the workflow and the forms it uses (**Publish to Command Center**), and check in the agents it calls.
2. Start it from **One Workflow** (or Start in the agent repository), optionally with starting data such as `{"amount": 1200}`.
3. People see their steps in the **Task inbox**: take a task, fill in the form or approve or reject it, or give it back.
   Required fields, numbers, choices, dates and e-mail addresses are checked.
4. **One Workflow** shows every workflow run with its current stage, a timeline of events and its data. A run can be
   cancelled, which withdraws its open tasks.

## 8. One Agent Vault

- One Agent Vault > New vault folder. Values are encrypted (AES-256-GCM), never shown again, handed to an agent only while
  it runs, and every use is audited. A folder's **Members**:

  | Member | Can |
  | --- | --- |
  | Owners (people) | Everything, members included |
  | Managers | Add, change and delete every credential |
  | Participants | See the folder, add credentials and change the ones they own |
  | Consumers | Their agents may use the credentials |

  Managers, participants and consumers can be users (`user:jane`), roles (`role:bot_runner`) or teams (`team:finance`).
- **Credentials.**
  - Each credential has an **owner** (who created it; owners and managers can hand it over) and a description.
  - **Details** lists its attributes, never their values.
  - **Delete** removes the whole credential with all its values.
  - An empty folder can be deleted.
- **User-provided attributes** (Details): each person stores their own value (for example their own password) under
  **Your values**. A run reads the value of the person it runs as (the run-as user, or whoever started it). A shared
  value cannot be set for such an attribute.
- **Credential requests** (top of the vault page, with a count): the user-provided attributes of folders you may use that
  you have not filled in yet. Fill them in there.
- **Attribute settings** (Details, one row per attribute):
  - a **description**, shown to people filling in their own value;
  - **Masked** (the default): the value is never shown. Clear it for values that are not secret, such as a user name;
    owners and managers can then **Show** it, and each look is audited;
  - **Password fields only**: agents may type the value into password fields only. The browser's Set text action and the
    web goal agent check the field before typing; other uses are refused.
- **Vault key** (administrators): shows whether the master key comes from the key file or `OOA_MASTER_KEY`, and its
  fingerprint. **Rotate key** makes a new key and encrypts every stored value again in one transaction:
  - shared values, people's own values and external-vault sign-in secrets are all included;
  - the old key file is kept as `master.key.old-…`;
  - with `OOA_MASTER_KEY`, rotate where that variable is managed.
- **External vaults**: a vault folder can be linked to HashiCorp Vault (KV v2), Azure Key Vault, AWS Secrets Manager or the
  CyberArk Central Credential Provider.
  - Agents still ask for `folder/credential/attribute`, so owners, consumers and the audit trail work as before.
  - The value is read live from the external vault. A credential name is the secret's name, and the attribute is a
    field of it; use `value` for single-value secrets.
  - The secret the Command Center uses to sign in to the external vault is stored encrypted and is never shown.
  - **Test reading** checks access without revealing the value.

## 9. AI: One Model Connections, governance and assistants

- **One Model Connections** (Administration): named AI model connections: OpenAI, Azure OpenAI, Anthropic, Amazon
  Bedrock, Google Vertex AI, a model on an Amazon SageMaker endpoint, Hugging Face, or an OpenAI-compatible local or hosted
  model. Each stores its key as a vault reference. Agents choose a connection by name in the Prompt Skill, AI agent,
  generative AI and web goal agent actions.
  - **Type**: standard or fine-tuned model.
  - **Roles that may use it**: runs of people with none of these roles do not receive the connection. Empty means every role.
  - **Test connection** sends one very small request ("Answer with OK") with the key from the vault and shows the answer
    and the time. The key is used for that request only; the test is audited.
  - Amazon Bedrock: the model or inference profile id, the endpoint `https://bedrock-runtime.REGION.amazonaws.com`, and
    the key `ACCESS_KEY_ID:SECRET` in one credential. Google Vertex AI: the model (for example `gemini-2.5-flash`), the
    endpoint `https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION`, and a service-account JSON
    key (exchanged for an access token) or an access token.
- **Prompt Skills** (Build > Prompt Skills): reviewed prompt templates agents run with the Prompt Skills toolkit
  (`ooa.ai_skills/run`).
  - A skill has instructions for the model, a prompt with `${name}` inputs, the answer type (text or JSON), a temperature
    and optionally a connection.
  - A new skill is **private** to its owner until **Shared**. Every save is a new version with a comment; earlier
    versions stay readable.
  - **From a template** starts a skill from a built-in template (summary, request routing, invoice fields) or a template
    an administrator published.
  - Runs receive the skills their agents name (shared ones, and the run's person's own private ones). A skill in the
    Command Center wins over a skill file of the same name in the runner's workspace.
- **AI governance** (for auditors) lists every AI call agents made over a period you choose:
  - the provider and model, tokens used and answer time
  - whether personal data was masked
  - which tools the AI used
  - totals by model, agent and person
  
  Prompts are never stored, only a fingerprint (SHA-256).
- **AI assistants (Model Context Protocol)**: assistants that speak MCP connect to `https://<your address>/api/mcp` with a
  user's API key.
  - They can list and run the agents an administrator offers (Administration > Settings > AI assistants), as that user
    and with that user's permissions.
  - Agent inputs become the tool's input schema; credential inputs are never offered.
  - Each call is a normal run (source "AI assistant (MCP)") in Agent Runs and the One Audit Trail.

**Shared values people change for themselves.** In Shared values, Edit > **People may set their own value** lets each
person keep their own value (**My value**). Their runs, and runs as them, read it instead of the shared one. Turning it
off removes everyone's own values.

## 10. One Audit Trail

Every sign-in, change, run, credential use, task and workflow event is recorded and hash-chained. One Audit Trail >
Verify integrity re-checks the whole chain and reports any record that was edited or deleted.

The chain is keyed with a secret kept encrypted by the vault key, so someone who can write to the database cannot
rebuild a consistent chain after an edit. Every hour the last record's position and hash are also written to
`audit-checkpoints.log` in the data folder (copy it to a log collector or write-once storage); Verify compares the
database with it, so deleting the newest records is found too.

**Audit retention** (Administration > Settings, 90 days at least, 0 keeps everything): older records are moved to
compressed files in the data folder's `audit-archive` folder before they leave the database, and a record of the cut
keeps the chain verifiable.

Search by any text, who, action (a prefix such as `repo.` or an exact action), object type, result and a date range,
newest or oldest first, with paging and the total. **Export CSV** downloads the search (up to 50,000 records); the
export is itself recorded. API: `GET /api/audit?actor=&action=&object_type=&object_id=&result=&from=&to=&sort=&limit=&offset=`
and `GET /api/audit/export` with the same filters.

## 9a. Toolkit versions

Administration > Toolkits > **Versions** lists the agents that use a toolkit, the version range each requires, and
whether the installed version fits. **Update agents** sets a new range (for example `^2.0.0` after an upgrade) on all
of them. Each agent gets a new version; its live version stays until you choose the new one. Agents that someone else
has checked out are left as they are and reported.

## 10a. Operations

Run & Monitor > Operations shows how agents ran over a period (24 hours to a year), for all agents or one:

- runs, success rate, total and average run time, and the time within which 95% of runs finish;
- how long runs take (a distribution) and runs per day;
- the agents run most, that take longest and that fail most (with their most common errors);
- use per runner and by how runs were started.

Each list has a CSV download, and **Run history CSV** lists every run. An agent's Statistics also shows its **variable
profile**: for each output, how often it had a value, its most common values and, for numbers, the range and average.

**Run history retention** (Administration > Settings): step logs and whole runs older than the periods you set are
removed once an hour, and **Remove now** removes them at once. Queued and running runs are never removed.
Settings also shows **This Command Center**: the edition (self-hosted, no limits on people, runners or agents) and
what it holds. An agent's **Statistics** (agent details, or a
click in these lists) shows its errors and runners. Only agents the person may see (folder permissions) are counted.
API: `GET /api/analytics?days=&from=&to=&bot=&device=&user=&source=`, `GET /api/analytics/agent?path=`,
`GET /api/analytics/export?report=most_run|longest|most_failed|by_runner|by_day|by_source|run_history`.

**Other retention** (Administration > Settings): AI calls and finished work items older than the days you set are
removed with the run history (0 keeps them).

## 10b. E-mail from the Command Center

Administration > Settings > E-mail: the company's outgoing mail server, port, security (STARTTLS, SSL/TLS, or none on
a trusted network only), the account and its password as a vault reference (`folder/credential/attribute`), and the
sender address. **Send a test e-mail** checks it. The Command Center uses it for licence reminders
([LICENSING.md](LICENSING.md)). Messages are plain text; the server's certificate must name the server.

## 10c. Backups

Administration > Settings > Backups makes a backup every few hours (0 turns it off) into the data folder's `backups`
folder or a folder you choose, and keeps them for the days you set. **Back up now** makes one at once. A backup is
one zip with every table and the repository files. It holds vault values encrypted but never the vault key: keep
`OOA_MASTER_KEY` (or `master.key`) safely apart.

From `bin`: `ooa backup now`, `ooa backup list` and, with the Command Center stopped, `ooa backup restore FILE` (into an
empty database; `--replace` to overwrite). Restore refuses a backup made with another vault key. With SQL Server or
PostgreSQL, keep the database's own backups as well.

## 10d. Several Command Center nodes

Two or more Command Centers can share one SQL Server or PostgreSQL database behind a load balancer (no sticky
sessions needed). Every node serves the console, the API and runners; one node at a time leads the background work
(schedules, triggers, queues, housekeeping, backups) and another takes over within about 30 seconds if it stops.
Administration > Settings > Several nodes lists the nodes and which leads. Put repository files on a shared folder
there (for example `\\fileserver\ooa\repo-files`) and give every node the same `OOA_MASTER_KEY`.
