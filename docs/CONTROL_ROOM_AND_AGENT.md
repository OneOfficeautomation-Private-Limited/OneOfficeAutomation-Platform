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
| `--host 0.0.0.0` | Accept connections from other machines (put an HTTPS reverse proxy in front first) |
| `--data D:\OOA\cr` | Keep data elsewhere |
| `--db-url jdbc:postgresql://dbhost:5432/ooa --db-user ooa --db-password-env OOA_DB_PASSWORD` | Use PostgreSQL |

Production: set the environment variable `OOA_MASTER_KEY` (32 random bytes, Base64) and keep it outside the data folder.
Without it a key file is created next to the database.

## Finding your way: the menu

The menu on the left has **Home** and three groups (each can be collapsed):

| Group | Pages |
| --- | --- |
| **Build** | Agent repository, One Agent Studio (opens in a new tab), One Workflow, Work queues |
| **Run & Monitor** | Agent Runs (Live and History), Task inbox, Schedules, Triggers, Runners |
| **Govern** | Users, Roles, One Agent Vault, Shared values, Toolkits, One Model Connections, AI governance, One Audit Trail, Settings |

People only see the pages their role allows.

## 2. People, roles and sign-in

| Built-in role | Can |
| --- | --- |
| Administrator | Everything: users, roles, runners and runner pools, live production versions, settings, the One Audit Trail |
| Agent builder | Check agents in and out, run them, manage schedules, triggers, work queues and their own vault folders |
| Agent operator | View agents, run and stop them, add and work on work queue items |
| Viewer | Read-only |

- **Custom roles** (Administration > Roles): tick the permissions a role needs, for example a "queue clerk" that may
  only see and add work items. Roles in use cannot be deleted.
- **New users** (Users > New user) get a one-time temporary password and must change it at first sign-in. Five wrong
  passwords lock an account for 15 minutes. Administration > Settings can disable accounts that have not signed in for
  a number of days.
- **Single sign-on** (Administration > Settings > Single sign-on): enter the OpenID Connect issuer URL and client id of
  Microsoft Entra ID, Okta, Google, Keycloak or another provider. The client secret is a vault reference, never typed into
  settings. Register the redirect address shown there with the provider. The sign-in page then shows a "Sign in with
  your organisation account" button.
  - On the first sign-in, an existing account with the same verified e-mail address or user name is linked.
  - Otherwise a new account is created with the role you choose. Leave that role empty to allow existing accounts only.
  - The provider's signature, issuer, audience, expiry and a one-time nonce are checked, using PKCE.
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

3. To start the runner automatically at sign-in (and restart it if it stops):

       powershell -ExecutionPolicy Bypass -File scripts\install-agent-task.ps1

The runner runs each agent in its own process, so a crashing agent never stops the runner. Its log files are in
`C:\Users\<you>\OOA Agent Workspace\logs`.

**Runner pools** (Runners > Runner pools) group runners. A run, schedule or work queue sent to a pool goes to its least
busy online runner.

## 4. The agent repository: check-in and check-out

- Your One Agent Studio workspace (My Workspace) is **private**. The Command Center's agent repository is **public** and
  keeps every version.
- In Studio, connect at the bottom of the Explorer (One Command Center section).
- **Agent check-in** sends the open agent as a new version. Agents are validated first; problems block the check-in.
  Workflows and forms are published with **Publish to Command Center** in their designers.
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
- An empty folder can be deleted.

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
- **Run mode:** unattended (default), attended, or headless. Headless refuses agents that need a desktop.
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
- **Schedules** run every N minutes, daily, or weekly in a time zone, on a runner or a pool.
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
  - Triggers can be paused and resumed (`POST /api/triggers/{id}/pause|resume`); `GET /api/triggers` shows the fire
    count, last fired time and last error. `POST /api/triggers/{id}/poll` checks a server trigger at once.
- UI agents can add **interrupt rules** (`ooa.interrupts`): when a window or element appears, click, press keys, close
  the window or run a child agent. Desktop, window and browser actions check them before acting and when an element is
  missing. Browser element actions and window lookups also **self-heal**: when the selector or title finds nothing, the
  alternatives recorded with the element (Pick records them) are tried in order and the one that worked is logged.

## 6. Work queues

Work queues hold items (invoices, tickets, orders) that an agent processes one at a time on a runner pool.

1. Work queues > New work queue: choose the agent, the runner pool, the attempts per item and a service level in
   minutes, and optionally a **work item model**.
   - A model (Work item models) lists the fields every item has, each text, number, true/false or date, optionally required.
   - Items are checked against it: required fields present, values of the right type, no unknown fields. CSV cells are
     converted to the field's type.
   - While items use a model, it can gain optional fields but not lose fields or change them.
2. Add items by pasting JSON or CSV, or by uploading a file. A `reference` column names each item; a `priority` column
   puts it first. Items can also be added through the API (`POST /api/queues/{id}/items`, or `/items/csv` with CSV text).
3. Start processing. The agent receives each item in the input `work_item` (a dictionary) and its id in `work_item_id`.
   Its outputs are kept with the item. A failed item is retried until its attempts are used.
4. The queue shows counts by status, items past the service level, and each item's result or error with a link to its run.
   - Failed or deferred items can be retried, waiting items can be deferred, and items not being worked on can be deleted.
   - **Open** an item to change its data, reference or priority (the form follows the model).
   - **Export CSV** downloads the items (filtered by status). Cells that a spreadsheet would read as a formula are made safe.
5. **Members** (optional) limit who can use a queue.
   - Owners manage it, participants add and change items, and consumers start processing and retry items.
   - Without members, the roles alone decide.
   - People with `queues.all` see every queue.

## 7. One Workflow and the task inbox

A workflow chains agents, people and decisions. Design it in One Agent Studio (Create > Workflow) with these stages:

| Stage | What it does |
| --- | --- |
| Agent task | Runs an agent on any online runner (or a pool) as the person who started the workflow. Its outputs join the workflow data. |
| Human task | Puts a form in the Task inbox of a user or role (`user:jane`, `role:bot_runner`), optionally with a due time. The answers join the data, also as `$form.field`. |
| Approval | Asks a person to approve or reject. A rejection ends the workflow, continues anyway, or jumps to a stage you choose. |
| Decision | Evaluates a condition such as `$amount > 1000` and goes to one stage or another. |
| Notification | Sends an inbox notice. Text can use `${name}`. |

1. Publish the workflow and the forms it uses (**Publish to Command Center**), and check in the agents it calls.
2. Start it from **One Workflow** (or Start in the agent repository), optionally with starting data such as `{"amount": 1200}`.
3. People see their steps in the **Task inbox**: take a task, fill in the form or approve or reject it, or give it back.
   Required fields, numbers, choices, dates and e-mail addresses are checked.
4. **One Workflow** shows every workflow run with its current stage, a timeline of events and its data. A run can be
   cancelled, which withdraws its open tasks.

## 8. One Agent Vault

- One Agent Vault > New vault folder. Owners manage values; consumers (`role:bot_runner`, `user:jane`) may use them in agents.
  Values are encrypted (AES-256-GCM), never shown again, handed to an agent only while it runs, and every use is audited.
- **External vaults**: a vault folder can be linked to HashiCorp Vault (KV v2), Azure Key Vault, AWS Secrets Manager or the
  CyberArk Central Credential Provider.
  - Agents still ask for `folder/credential/attribute`, so owners, consumers and the audit trail work as before.
  - The value is read live from the external vault. A credential name is the secret's name, and the attribute is a
    field of it; use `value` for single-value secrets.
  - The secret the Command Center uses to sign in to the external vault is stored encrypted and is never shown.
  - **Test reading** checks access without revealing the value.

## 9. AI: One Model Connections, governance and assistants

- **One Model Connections** (Administration): named AI model connections (OpenAI, Azure OpenAI, Anthropic, or an
  OpenAI-compatible local or hosted model). Each stores its key as a vault reference. Agents choose a connection by name
  in the One Prompt Skill, AI agent and generative AI actions.
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

## 10. One Audit Trail

Every sign-in, change, run, credential use, task and workflow event is recorded and hash-chained. One Audit Trail >
Verify integrity re-checks the whole chain and reports any record that was edited or deleted.
