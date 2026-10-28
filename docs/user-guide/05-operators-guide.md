# Operator's guide

**Audience:** operators: the people who run agents, keep schedules, triggers and work queues going, watch what
happens and deal with failures. Builders and administrators will find it useful too.

**You need:** an account with the **Agent operator** role (or a custom role with the permissions named below). The
built-in Agent operator may view agents, start and stop runs, see runners, schedules, triggers and shared values, and
work on work queue items. Creating schedules, triggers, work queues and API tasks needs the **Agent builder** role
(or `schedules.manage`, `triggers.manage`, `queues.manage`, `apitasks.manage`); runner pools need `pools.manage`.

## Contents

1. [Your working day at a glance](#1-your-working-day-at-a-glance)
2. [Agent Runs: what is running and what ran](#2-agent-runs-what-is-running-and-what-ran)
3. [Running an agent: runners, pools and run-as users](#3-running-an-agent-runners-pools-and-run-as-users)
4. [Runners and runner pools from the operator's side](#4-runners-and-runner-pools-from-the-operators-side)
5. [Schedules](#5-schedules)
6. [Triggers](#6-triggers)
7. [Work queues](#7-work-queues)
8. [One Workflow and the Task inbox](#8-one-workflow-and-the-task-inbox)
9. [API tasks](#9-api-tasks)
10. [Agent tests](#10-agent-tests)
11. [Operations: how agents ran](#11-operations-how-agents-ran)
12. [Dashboards and business figures (KPIs)](#12-dashboards-and-business-figures-kpis)
13. [Handling failures](#13-handling-failures)
14. [Related guides](#related-guides)

## 1. Your working day at a glance

1. Open **Home**. Check **Failed (24 h)**, **Recent failures** and **Runners online**.
2. Open **Operations > Agent Runs**: the **Live** tab shows what runs now and what waits.
3. Look at **Operations > Work queues** for items past their service level (a red "overdue" pill).
4. Look at **Operations > Triggers** for any trigger whose **Status** is **error**.
5. Deal with failures (section 13), then rerun what needs rerunning.

## 2. Agent Runs: what is running and what ran

**Operations > Agent Runs** (needs `runs.view`) has two tabs, each with a count.

### The Live tab

Every queued and running run, as a card:

- the status (**queued**, **running**, **paused**, **stopping**), the agent and its version;
- a progress bar and **Step n of m: action name** with the step id, or "Waiting for *runner* to pick it up";
- **Runner**, **Started by** (for example `admin as jane (Schedule)`), **Queued** and **Running for**.

With `runs.stop` you get these buttons on each card:

| Button | What happens |
| --- | --- |
| **Pause** | The agent pauses after the current step. The card then shows **paused**. |
| **Resume** | The paused run carries on. |
| **Stop** | The run is stopped; the runner gets the request within seconds. |

When nothing runs you see "Nothing is running".

### The History tab

Finished runs (succeeded, failed, stopped), 25 per page, newest first. Columns: **Status**, **Agent**, **Runner**,
**Started by**, **Source**, **Started**, **Duration**, **Steps**, **Error** (the error code; hover for the message)
and a **Rerun** button.

Filters (above the table):

- a search box (**Search agent, run id, user or error**);
- **Any status**: Succeeded, Failed, Stopped;
- **Any agent**, **Any runner**;
- **Any source**: Manual, Schedule, Trigger, Rerun;
- **Started by (user name)**;
- **From** and **To** dates (whole days in your time zone);
- **Clear filters**, and **Export CSV** (up to 500 runs that match the filters).

> **Tip.** From **Schedules**, click a schedule's row to open the History tab already filtered to that schedule's
> agent and source.

### Run details

Click any run (on Live, History, Home or a runner's details) to open its details panel:

1. The status, "Running for ..." or "Finished in ...", and a progress bar with the step running now.
2. When the run failed: a red box with the **error code**, the message, **At step** *id* and **Fix:** a suggested fix.
3. **Run**, **Agent** (with version), **Runner**, **Started by** (and how), **Queued**, **Started**, **Finished**,
   **Steps run**.
4. Tabs:
   - **Steps**: a timeline of each step with its duration, retries and error.
   - **Log**: every event with its time from the start. Filter with **All**, **Errors**, **Log messages**,
     **Variables**.
   - **Variables**: the last value of each variable. Credentials always show as `****`.
   - **Inputs and outputs**: as JSON. Outputs appear when the run ends.
   - **AI calls**: shown when the run made AI model calls: each call's time, purpose, model and connection, tokens in
     and out, time taken, what was masked and which tools it asked for. Prompts and answers are never stored.
5. Buttons: **Pause**/**Resume** and **Stop** while it runs; **Rerun** once it ended; **Download log** at any time.

While a run is active the panel refreshes every two seconds.

**Rerun** queues the agent again with the same inputs, using the **live production version** (which may be newer than
the version that ran). The toast tells you the new run id and version.

## 3. Running an agent: runners, pools and run-as users

Agents run from **Build > Agent repository** (needs `runs.start`). Only agents with a live production version have a
**Run** button; workflows have **Start**.

1. Press **Run** on the agent's row (or in its details panel, or **Run an agent here** in a runner's details).
2. Choose **Run on**:
   - **A runner**: pick one from the list (each shows its status).
   - **A runner pool (least busy runner)**: shown when pools exist. The run goes to the pool's least busy online runner.
   - **Run-as users (each on their default runner)**: tick one or more people. Each gets their own run, on their
     default runner, acting as them: their own vault values are used and the agent's credentials are checked against
     them. People without a default runner cannot be ticked.
3. Fill in the inputs. Studio's input types become fields: text, numbers, dates, True/False lists, choice lists, and
   JSON boxes for lists, tables and dictionaries. A **Credential** input takes a vault reference such as
   `finance/erp/password`, never the secret itself.
4. Optional: **More inputs as JSON**.
5. Optional: **Run mode, priority and result callback**:

   | Field | Choices | Notes |
   | --- | --- | --- |
   | **Run mode** | Unattended (nobody at the runner), Attended (someone is at the runner), Headless (no desktop) | See below |
   | **Priority on the runner** | Low, Normal, High, Urgent | Higher priority starts first on a busy runner |
   | **Callback URL** | An https address | Receives the result as JSON when the run ends (three attempts) |

6. Press **Run now**. You are taken to **Agent Runs > Live** and the run's details open. For run-as users a toast
   says how many runs started and which could not start, and why.

**Run modes in practice:**

- **Unattended** (the default): nobody is at the runner. A message box, prompt or file picker without a timeout fails
  at once with `RUN.NEEDS_PERSON` instead of waiting for ever; one with a timeout still shows and closes by itself.
  Forms fail and AI tool approvals are refused. After the run the runner may lock its screen or sign out
  ([07 Runner guide](07-runner-guide.md)).
- **Attended**: someone sits at the runner; dialogs and forms wait for them.
- **Headless**: no desktop at all. Agents that need the screen are refused when the run starts, and browsers open
  without a window.

**Deploying an agent** therefore means: make sure the version you want is the live production version (see
[15 Publishing](15-publishing-to-command-center.md)), then point runs, schedules, triggers, work queues or API tasks
at a runner or a pool. You never copy agent files to runners: the runner receives the live version, the repository
files it lists under `requires.files` (up to 50 MB per run) and any custom toolkit it needs, with every run.

## 4. Runners and runner pools from the operator's side

**Manage > Runners** (needs `devices.view`) lists every runner. A runner is **online** when it checked in during the
last minute. The useful columns for operators:

| Column | Meaning |
| --- | --- |
| **Status** | online or offline |
| **Running** | how many agents it runs now |
| **Type** | Single-user, Multi-user, Serverless (in the Command Center); **Temporary** for runners removed by themselves |
| **Default users** | whose default runner it is |
| **Screen** | **Unlocked** (usable), **Locked** (screen agents cannot start), **No desktop** (a Windows service) |
| **After a run** | what it does after unattended runs |
| **Program** | **Up to date** or **Update available** |
| **Last seen** | the last heartbeat |

Click a runner to see its details: host name, operating system, agent version, toolkits, **Now** (its active runs) and
**History** (its last runs). **Run an agent here** starts a run on it.

The **Runner pools** tab lists the pools with their runners (a dot for each runner's status) and **Online** "n of m".
With `pools.manage`, **New runner pool** asks for a name, description and the runners in the pool; **Edit** and
**Delete** are on each row. Administrators handle registering, removing and updating runners; see
[07 Runner guide](07-runner-guide.md).

## 5. Schedules

**Operations > Schedules** (needs `schedules.view`; changing needs `schedules.manage`).

### Create a schedule

1. Press **New schedule**.
2. **Name**, and **Agent (live production version)**.
3. **Runner**, **Or a runner pool**.
4. **Run as (credentials are checked against this person)**: leave "(the owner, on the runner chosen here)", or pick a
   person. Tick **Use the run-as user's default runner** to follow that person's default runner instead of the one
   chosen above.
5. **Repeat**: **Every N minutes** (with **Minutes**), **Daily** (with **Time**, such as `08:30`) or **Weekly** (with
   the days, Monday to Friday ticked by default).
6. **Time zone** (your browser's, by default) and **Skip the dates of a holiday calendar**.
7. **Inputs (JSON, optional)**, for example `{"region": "EU"}`.
8. **Times missed while the Command Center was down**: **Run once for them** (default) or **Skip them** (a time more
   than 15 minutes in the past is not run).
9. **When the previous run is still going**: **Start another run** (default) or **Skip this time**.
10. Press **Create**. A toast shows the next run time.

### The list

Columns: **Schedule**, **Agent**, a tick box, **Runner** (a runner, "Pool: ..." or "default runner of ..."),
**When**, **Owner** (and "runs as ..."), **Next run**, **Last run**, **Enabled**. Row buttons: **Run now** (queue a
run at once), **Edit** (name, holiday calendar, owner, run-as user, missed times, previous run still going),
**Disable**/**Enable**, **Delete**. **When** also says "skips missed times" or "skips while the previous run is going"
when those options are set.

Several at once: tick schedules, then **Enable selected**, **Disable selected** or **Change owner**.

### Holiday calendars

**Holiday calendars** lists calendars with their number of dates and the next one. To add or change one, type a
**Name** and the **Dates** (YYYY-MM-DD, one per line or separated by commas) and press **Save calendar**.

### Rules to remember

- Scheduled runs use the **owner's** permissions (or the run-as user's vault access).
- A schedule whose owner is disabled does not run; this is written to the One Audit Trail. Hand schedules to another
  owner before someone leaves.
- Schedules always run the live production version.

## 6. Triggers

**Operations > Triggers** (needs `triggers.view`; changing needs `triggers.manage`) start an agent when something
happens.

### Create a trigger

1. Press **New trigger**. Fill in **Name**, **Agent (live production version)** and **Runner**.
2. Choose the **Event**. The fields change with it and a help line explains what the agent receives.

   **On the runner** (the runner checks its own machine every two seconds):

   | Event | Fields | Agent input |
   | --- | --- | --- |
   | File arrives in a folder, File changed, File deleted, Folder created, Folder deleted | Folder on the runner, File or folder name pattern, Include sub-folders | `trigger_file` |
   | Window opened, Window closed (Windows) | Window title pattern (text, `*` and `?`, or `re:` and a regular expression) | `trigger_window` |
   | Process started, Process stopped | Process name, such as `excel.exe` | `trigger_process` |
   | Service started, Service stopped (Windows) | Windows service name (from `sc query`, not the display name) | `trigger_service` |
   | Hotkey pressed (Windows, signed-in desktop) | Key combination, such as `CTRL+ALT+F9` | `trigger_hotkey` |

   **On the Command Center** (checked every interval, 15 seconds or more):

   | Event | Fields | Agent input |
   | --- | --- | --- |
   | New email (IMAP mailbox) | IMAP server, Port, Use SSL/TLS, User name, Password (vault reference), Mailbox folder, From contains, Subject contains, Check every | `trigger_email` |
   | New email (Microsoft 365 Outlook) | Graph access token (vault reference), or tenant id, client id and client secret reference; Mailbox; Mail folder; filters; interval | `trigger_email` |
   | ServiceNow record new or updated | Instance URL, Table, Encoded query, User name and Password (vault reference) or OAuth token reference, interval | `trigger_record` |
   | Universal poller (call a web address) | URL to call (GET), Items JSON path, Id field, Bearer token (vault reference), interval | `trigger_item` |
   | Webhook (HTTP call) | none | `payload` |

   Every agent also receives `trigger_event` with all details, if it declares it.
3. Passwords and tokens are always vault references such as `mail/imap/password`; the trigger's owner must be a
   consumer of that vault folder.
4. Press **Create**. For a webhook you then see its **URL** and **Secret** (send it in the `X-OOA-Secret` header),
   shown only once.

### Watching triggers

Columns: **Trigger**, **Kind** ("Checked by the Command Center" or "Watched on the runner"), **Agent**, **Runner**
(and run-as user), **Details**, **Monitoring** (times fired, when last, the last error) and **Status** (**active**,
**paused** or **error**). Row buttons: **Check now** (server triggers: check at once instead of waiting),
**Pause**/**Resume**, **Edit** (name, owner, run-as user) and **Delete**.

> **Tip.** The universal poller only remembers the items present at its first check; it fires for items that appear
> after that.

## 7. Work queues

**Operations > Work queues** (needs `queues.view`) hold items such as invoices, tickets or orders that an agent works
one by one on a runner pool, with retries and a service level.

### Set up a queue (`queues.manage`)

1. Optional: **Work item models** > **New model**: name the fields every item has, each **Text**, **Number**,
   **True/false** or **Date**, **Required** or not and **Shown in the list**. While queues using a model have items,
   it can gain optional fields but not lose or change fields.
2. Press **New work queue**: **Name**, **Description**, **Work item model**, **Agent that works the items**,
   **Runner pool**, **Service level (minutes from added to done)** and **Attempts per item** (1 to 10).
3. Press **Save**.

The agent receives each item in its input `work_item` (a dictionary) and the item's id in `work_item_id`. Its outputs
are kept with the item. A failed item is retried until its attempts are used.

### Add items (`queues.work`)

Open the queue (click its row) and press **Add items**. Paste a JSON list or CSV with a header row, or choose a CSV,
JSON or Excel (`.xlsx`) file (for Excel, name the sheet or leave it empty for the first). A `reference` column names
each item; a `priority` column puts it first. Dates in Excel come as yyyy-mm-dd. Items are checked against the model:
required fields, right types, no unknown fields.

> **Tip.** Large Excel files travel inside a JSON request, which is limited by **Settings > Largest upload (MB)**
> (25 MB by default). Ask an administrator to raise it, or split the file.

### Work the queue

- **Start**/**Stop** on the row (or **Start processing**/**Stop processing** in the queue) switches processing on and
  off. The **Processing** column shows **running** or **stopped**.
- The list shows **Waiting**, **In progress**, **Done** (succeeded / failed, with a bar) and **Service level** with
  the number of overdue items.
- Inside the queue: counts by status (new, in progress, to validate, retry, success, failed, deferred, and "past
  service level"), a status filter, a search box and the items with **Reference**, **Status**, **Attempts**,
  **Priority**, **Added** and **Result or error** (a link to the run).
- Tick items and use **Retry selected**, **Defer selected** or **Delete selected** (items being worked on are kept).
  **Defer selected** asks "until when?" (`YYYY-MM-DD HH:MM`, your time): after that time processing picks the items up
  again by itself. Left empty, they wait until someone presses **Retry**. The status shows "until <time>" or
  "until Retry".
- **Open** an item to change its data, reference or priority (the form follows the model); **View** while it is in
  progress.
- **Validate** appears on items **To validate**: the agent was unsure of some fields (marked red with their
  confidence). Correct them and **Approve** (the item goes back to the agent with `validated_fields`, `validated` and
  `validated_by`), or type a reason and **Reject** (the item fails). Both are audited.
- **Export CSV** downloads the items (filtered by status); cells a spreadsheet would read as a formula are made safe.
- **Export definition** saves the queue's settings, model, members and, if you choose, its waiting items;
  **Import queue** on the list page makes a queue from such a file and lists anything it could not link.
- **Members**: owners manage the queue, participants add and change items, consumers start processing and retry.
  Without members, roles alone decide. People with `queues.all` see every queue.

## 8. One Workflow and the Task inbox

**Build > One Workflow** (needs `runs.view`) lists every workflow run with **Workflow** (and version), **Status**
(running, waiting, completed, failed, cancelled), **Current stage**, **Started by**, **Started** and **Updated**.

To start one (`runs.start`): press **Start a workflow**, choose the **Workflow (live production version)**, fill in
its start form (or **Starting data (JSON, optional)**), optionally a **Reference** such as `PO-2026-0042` to find the
request again, and press **Start**.

Click a run to monitor it: **Reference**, **Status**, **Current stage**, **Started**, **Finished**, **Error**, a
**Timeline** of events (agent started, finished or failed, task created or completed, approved, rejected, decided,
notified) with **Open agent run** links, and the workflow **Data**. **Cancel run** withdraws its open tasks.

People's steps arrive in **Operations > Task inbox** (tabs **To do** and **Done**). Administrators can tick
**Everyone's tasks** to see who is holding what. Each task can be taken (**Take it**), given back (**Give back**),
filled in and submitted, approved or rejected, or marked as read. See [14 Business users](14-business-users.md).

## 9. API tasks

**Build > API tasks** (needs `apitasks.manage`) put an agent behind its own web address and token for other systems.

1. Press **New API task**: **Name**, **Agent**, and either a **Runner** / **Or a runner pool**, or tick **Run on the
   Command Center, with no runner (serverless)** for agents that only call web services and work on data (at most 5
   minutes per run, four at a time per node, files only in the run's own workspace).
2. **Wait for the result up to (seconds, 1-300)**.
3. Press **Create**. Copy the **Address** and the **Token** (shown once) and the example `curl` command.

Callers send `POST` with `X-OOA-Task-Token` (or `Authorization: Bearer`) and `{"inputs": {...}}`. They get 200 with
`{status, run_id, outputs, error}` when the run finished in time, or 202 and then ask `GET .../runs/<run_id>`. Calls
run as the person who made the task, at high priority, and show in Agent Runs like any run. The list shows **Runs on**,
**Waits up to**, **Calls** (and last use) and **Status**; buttons **Switch off**/**Switch on**, **New token** (the old
one stops at once) and **Delete**.

## 10. Agent tests

**Build > Agent tests** keep test sets: an agent and cases with inputs and expected results. Press **Run**, choose a
runner or pool, and each case runs the live production version once. The test run is scored when every case answered
(for example "4 / 5"); each case says why it passed or failed. **History** shows the scores over time. Builders write
the cases ([04 Running and debugging](04-studio-running-and-debugging.md)); operators run them after changes to
runners, toolkits or connected systems.

## 11. Operations: how agents ran

**Operations > Operations** (needs `analytics.view`) summarises a period (**Last 24 hours**, **7 days**, **30 days**,
**90 days**, **Last year**) for **All agents** or one:

- figures for **Runs**, **Success rate** (succeeded, failed, stopped), **Run time** (total and average) and
  **95% of runs finish within** (and the longest);
- **How long runs take** (a distribution) and **Runs per day**;
- **Agents run most**, **Agents that take longest (average)**, **Agents that fail most** (with failure rate and their
  most common error codes);
- **Runners** and **How runs were started**.

Each table has a **CSV** button; **Run history CSV** downloads every run in the period. Click an agent's name for its
**Statistics**: runs, success rate, average, 95% within, longest, last run, last failure, errors by code, runners and
the **Outputs (variable profile)**: how often each output had a value, the range and the most common values. Only
agents you may see are counted.

## 12. Dashboards and business figures (KPIs)

**Operations > Dashboards** (needs `analytics.view`) shows figures your agents record. A builder ticks **Business
figure (KPI)** on an output in Studio; each successful run then records its value.

1. Press **New dashboard**. Give a **Name**, **Share with** (`role:viewer`, `team:finance`, `user:jane`, `*` for
   everyone with `analytics.view`, or empty for only you) and a **Description**.
2. Edit **Widgets (JSON)**. An example is filled in. Each widget has a `title`; a `type` (`number`, `bars`, `line`
   or `table`); a source: a KPI (`kpi` with `agg` `sum`, `avg`, `count`, `min`, `max` or `last`, optionally one
   `bot`) or runs (`source: "runs"` with `metric` `runs`, `success_rate`, `failed`, `avg_seconds` or `hours_saved`);
   a `group` (`day`, `week`, `month`, `agent`, or `value` with `by` for another KPI of the same run); and `days`
   (1 to 365, 30 by default). The dialog lists the KPIs recorded so far.
3. Press **Save**. Pick dashboards from the list at the top; **Edit** and **Delete** appear on ones you may change.

## 13. Handling failures

### A step-by-step routine

1. Open the failed run (Home > **Recent failures**, or **Agent Runs > History** filtered to **Failed**).
2. Read the red error box: the **code**, the message, the step and the **Fix**.
3. Open **Steps** to see where it stopped and whether it retried; **Log** with **Errors** for the detail;
   **Variables** for the values just before the failure.
4. Decide: a passing problem (a slow system, a locked file) → **Rerun**. A problem with the runner → section 4 and
   [07 Runner guide](07-runner-guide.md). A problem with the agent → send the run id and log to its builder.
5. For a work queue, retry the failed items once the cause is fixed (**Retry selected**).

### Common error codes

| Code | Meaning | What to do |
| --- | --- | --- |
| `RUN.NO_DESKTOP` | The agent uses the screen, but the runner is locked or has no desktop (a Windows service) | Sign the runner in and unlock it, or send the run to a desktop runner |
| `RUN.NEEDS_PERSON` | An unattended run reached a dialog or form without a timeout | Run it **Attended**, or ask the builder to add a timeout |
| `RUN.TOOLKIT_MISSING` | The runner lacks a toolkit or version the agent needs | Update the runner ([07](07-runner-guide.md)) or use another runner |
| `RUN.TOOLKIT_LOAD` | A custom toolkit failed its checksum or signature check on the runner | Tell an administrator |
| `AI.BUDGET_REACHED` | The model connection's monthly token budget is used up | An administrator raises the budget, or wait for next month |
| `AI.CALL_LIMIT` | The run made more AI calls than the connection allows per run | Tell the builder or the administrator |
| `AI.CONTENT_FLAGGED` | The provider's moderation flagged the answer | Check the input data |
| `CR.LICENSE_LIMIT` | Not enough licence seats | An administrator assigns or frees a seat |
| `FILE.OUTSIDE_WORKSPACE` | A serverless run used a path outside its workspace | The agent must not run serverless |

### When runs do not start at all

| Symptom | Likely cause |
| --- | --- |
| Runs stay **queued** on one runner | The runner is offline, disabled, or busy with a long run; check **Manage > Runners** |
| A runner shows "update the Command Center" in **Agent** | The runner is newer than the Command Center and gets no work until the Command Center is updated |
| Scheduled runs never appear | The schedule is disabled, its owner is disabled, today is in its holiday calendar, or the agent has no live version |
| A trigger shows **error** | Read **Monitoring**: often a wrong vault reference, a password change, or an unreachable server; fix it and press **Check now** |
| Every run is refused after a date | The licence or evaluation expired; tell an administrator |

## Related guides

- [00 Index](00-README.md)
- [01 Getting started](01-getting-started.md)
- [06 Administrator's guide](06-administrators-guide.md)
- [07 Runner guide](07-runner-guide.md)
- [14 Business users](14-business-users.md)
- [15 Publishing to the Command Center](15-publishing-to-command-center.md)
- Reference: [CONTROL_ROOM_AND_AGENT.md](../CONTROL_ROOM_AND_AGENT.md)
