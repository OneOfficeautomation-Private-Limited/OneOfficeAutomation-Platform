# Control Room and Bot Agent: setup and daily use

## 1. Start Control Room

**On a developer computer, use the one-step start**: double-click `platform\scripts\start-ooa.bat` (or
`.\gradlew.bat :cli:run --args="server"`). Control Room, Studio (at `/studio/`) and a Bot Agent for this computer start
together on http://localhost:8720, and the agent registers itself, so section 3 below is only needed for other machines.

To run Control Room on its own (for example on a server), from PowerShell in the `platform` folder:

    .\gradlew.bat :cli:run --args="control-room"

Open http://localhost:8720. On the very first start the console window prints a one-time password for the
user `admin`; sign in with it and choose your own password (at least 12 characters, letters and digits).

Data lives in `C:\Users\<you>\OOA Control Room` (embedded database and the vault key file). Back this folder up.

Options:

| Option | Meaning |
| --- | --- |
| `--port 8721` | Use another port |
| `--host 0.0.0.0` | Accept connections from other machines (put an HTTPS reverse proxy in front first) |
| `--data D:\OOA\cr` | Keep data elsewhere |
| `--db-url jdbc:postgresql://dbhost:5432/ooa --db-user ooa --db-password-env OOA_DB_PASSWORD` | Use PostgreSQL |

Production: set the environment variable `OOA_MASTER_KEY` (32 random bytes, Base64) and keep it outside the data
folder; without it a key file is created next to the database.

## 2. Users and roles

| Role | Can |
| --- | --- |
| Admin | Everything, including users, devices, production versions, the audit log |
| Bot creator | Check bots in and out, run them, manage schedules, triggers and their own lockers |
| Bot runner | View bots, run and stop them |
| Viewer | Read-only |

Users > New user shows a one-time temporary password; the user must change it at first sign-in.
Five wrong passwords lock an account for 15 minutes.

## 3. Register a device and start the Bot Agent

1. Control Room > Devices > Register a device. Copy the command (the key works once, for 24 hours).
2. On the device, in the platform folder:

       .\gradlew.bat :cli:run --args="agent register --url http://localhost:8720 --key ooareg_..."
       .\gradlew.bat :cli:run --args="agent start"

3. To start the agent automatically at sign-in (and restart it if it stops):

       powershell -ExecutionPolicy Bypass -File scripts\install-agent-task.ps1

The agent runs each bot in its own process, so a crashing bot never stops the agent. Its log files are in
`C:\Users\<you>\OOA Agent Workspace\logs`.

## 4. Private and public bots: check in and check out

- Your Studio workspace is **private**. Control Room's repository is **public** and keeps every version.
- In Studio, connect at the bottom of the Explorer (Control Room section).
- **Check in** (Explorer toolbar) sends the open bot as a new version. Bots are validated first; problems block the check-in.
- **Check out** locks a public bot for you and copies it into your workspace under `Control Room/...`; others see who has it.
- Checking in releases the lock. **Undo check-out** releases it without a new version.
- Admins choose which version is the **production** version; runs, schedules and triggers always use production.

## 5. Running bots

- Automation > Run: choose a device and give inputs. Credential inputs are vault references such as
  `{"$credential": "finance/erp/password"}`, never the secret itself.
- **Activity > In progress** shows every queued and running bot: the device, who started it and how, how long it has run,
  a progress bar, and the step running now. Pause, Resume and Stop reach the device within seconds.
- **Activity > History** lists finished runs with status, duration, steps and error code, filtered by status, bot, device,
  source (manual, schedule, trigger, rerun), who started it, dates and free text, with paging and CSV export. **Rerun**
  runs the current production version again on the same device with the same inputs.
- Selecting a run opens its details: the error with the failed step and suggested fix, a step-by-step timeline with
  durations, the full log (filter errors, log messages or variables; **Download log**), the last value of each variable,
  and the inputs and outputs.
- **Home** shows runs in the last 7 days, success rate, average run time, recent failures, the most used bots and device
  health. **Automation** opens a bot's inputs, outputs, recent runs and versions, and compares two versions line by line.
  **Devices** can be renamed and show what they are running and their history. **Schedules** have **Run now**. Every list
  has search, sortable columns, paging and CSV export.
- Schedules (every N minutes, daily, or weekly in a time zone) and Triggers (a file arriving in a folder on the device, or a
  webhook) start bots automatically. Studio keeps its own local triggers for development.

## 6. Credentials

Credentials > New locker. Owners manage values; consumers (`role:bot_runner`, `user:jane`) may use them in bots.
Values are encrypted (AES-256-GCM), never shown again, handed to a bot only while it runs, and every use is in the audit log.

## 7. Audit log

Every sign-in, change, run and credential use is recorded and hash-chained. Audit log > Verify integrity re-checks the
whole chain and reports any record that was edited or deleted.
