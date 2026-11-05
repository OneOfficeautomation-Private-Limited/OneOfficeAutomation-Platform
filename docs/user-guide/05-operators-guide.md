# Operator's guide

**Audience:** operators: the people who run agents, keep schedules, triggers and work queues going, watch what
happens and deal with failures. Builders and administrators will find it useful too.

**You need:** an account with the **Agent operator** role (or a custom role with the permissions named below). The
built-in Agent operator may view agents, start and stop runs, see runners, schedules, triggers and shared values, and
work on work queue items. Creating schedules, triggers, work queues and Agent APIs needs the **Agent builder** role
(or `schedules.manage`, `triggers.manage`, `queues.manage`, `apitasks.manage`); runner pools need `pools.manage`.

## Contents

1. [Your working day at a glance](#1-your-working-day-at-a-glance)
2. [Agent Activity: what is running and what ran](#2-agent-activity-what-is-running-and-what-ran)
3. [Running an agent: runners, pools and run-as users](#3-running-an-agent-runners-pools-and-run-as-users)
4. [Runners and runner pools from the operator's side](#4-runners-and-runner-pools-from-the-operators-side)
5. [Schedules](#5-schedules)
6. [Triggers](#6-triggers)
7. [Work queues](#7-work-queues)
8. [Workflow and the Task inbox](#8-workflow-and-the-task-inbox)
9. [Agent APIs](#9-agent-apis)
10. [Agent tests](#10-agent-tests)
11. [Operational Insights: Overview, Alerts and Operations](#11-operational-insights-overview-alerts-and-operations)
12. [Business: agents, value and dashboards](#12-business-agents-value-and-dashboards)
13. [Handling failures](#13-handling-failures)
14. [Related guides](#related-guides)

## 1. Your working day at a glance

1. Open **Home**, or **Operational Insights > Overview** for everything that needs attention in one list. Check
   failed runs, open alerts and runners online.
2. Open **Manage > Agent Activity**: the **In progress** tab shows what runs now and what waits.
3. Look at **Manage > Work queues** for items past their service level (a red "overdue" pill).
4. Look at **Manage > Triggers** for any trigger whose **Status** is **error**.
5. Deal with failures (section 13), then rerun what needs rerunning.

## 2. Agent Activity: what is running and what ran

**Manage > Agent Activity** (needs `runs.view`) has three tabs: **In progress** and **Scheduled**, each with a count,
and **Completed**. The figures above them (**Running now**, **Waiting for a runner**, **Failed in the last 24 hours**,
**Ended in the last 24 hours** and the next scheduled run) open the runs behind them. **Scheduled** (with
`schedules.view`) lists the next run of each schedule, soonest first; tick schedules to **Run now**, **Turn on** or
**Turn off** them. Schedules are made and changed on **Schedules** (section 5).

### The In progress tab

Every queued and running run, one line each; the list refreshes every 5 seconds. Columns: **Status** (**Queued**,
**Running**, **Paused**, **Stopping**), **Agent** (with its version), **Folder**, **Progress** (**Step n of m: action
name**, or "Waiting for the runner"), **Runner**, **Started by** (for example `admin as jane`), **How started** (for
example **Schedule**), **Queued** and **Started**.

With `runs.stop`, tick runs and use these buttons above the list (a run's **⋯** menu also has **Stop**):

| Button | What happens |
| --- | --- |
| **Pause** | The agent pauses after the current step. Its status then shows **Paused**. |
| **Resume** | The paused run carries on. |
| **Stop** | The run stops after the current step; the runner gets the request within seconds. |

When nothing runs you see "Nothing is running or waiting."

### The Completed tab

Runs that ended (completed, failed, stopped) in the chosen period, 25 per page, newest first and grouped by day.
Columns: **Status**, **Agent**, **Runner**, **Started by**, **How started**, **Started**, **Took** and **Why it ended**
(the error code and message, cut short; hover for the whole text). **Columns** adds **Folder**, **Ended** and **Steps**
(they start hidden so a run fits on one line at 1366 px). Tick
runs and press **Run again**, or choose **Run again** in a run's **⋯** menu.

Filters (above the table):

- a search box (**Search agent, run id, user or error**);
- **Period**: Last 24 hours, Last 7 days, Last 90 days (the default) or Chosen dates;
- **Status**: Completed, Failed, Stopped;
- **Agent**, **Runner**;
- **How started**: Run now, Schedule, Trigger, Work queue, API, Run again, AI assistant (MCP);
- **More filters**: **Started by (user name)** and, for Chosen dates, **From** and **To** (whole days in your time
  zone);
- **Clear filters**, **Columns**, and **Export CSV** (every run that matches the filters; with runs ticked, **Export
  selected**).

> **Tip.** On **Schedules**, click a schedule's **Last run** to open that run here.

### Run details

Click a run in either list (or a link to a run elsewhere, such as a schedule's **Last run**, or a run id typed in the
top bar's search) to open its details beside the list:

1. The status, "Running for ...", "Finished in ..." or "Ended after ...", and a progress bar with the step running
   now.
2. When the run failed: a red box with the **error code**, the message, **At step** *id*, whether running it again may
   help, **Fix:** a suggested fix, and **Every run that failed this way**.
3. **Agent** (with version), **Runner**, **Started by** (and how), **Queued**, **Started**, **Ended**, **Steps run** and
   **Run id**.
4. Tabs:
   - **Steps**: a timeline of each step with its duration, retries and error.
   - **Log**: every event with its time from the start. Filter with **Everything**, **Errors and retries**, **Log
     messages**, **Variables set**.
   - **Variables**: the last value of each variable. Credentials always show as `****`.
   - **Inputs and outputs**: as JSON. Outputs appear when the run ends.
   - **AI calls**: shown when the run made AI model calls: each call's time, purpose, model and connection, tokens in
     and out, time taken, what was masked and which tools it asked for. Prompts and answers are never stored.
5. Buttons: **Pause**/**Resume** and **Stop** while it runs; **Run again** once it ended; **Download log** at any time;
   **‹ Newer** and **Older ›** open the run above or below in the list.

While a run is active the panel refreshes every two seconds.

**Run again** queues the agent again with the same inputs, using the **live production version** (which may be newer
than the version that ran). A message gives the new run's id; from a run's details, the new run opens.

## 3. Running an agent: runners, pools and run-as users

Agents run from **Build > Automation** (needs `runs.start`). Only agents with a live production version offer **Run…**
in their row's **⋯** menu (or **Run** on the agent's page); a live workflow's page has **Start in Workflow** instead.

1. Choose **Run…**. The **Run <agent>** dialog opens and says which live version it runs.
2. Choose **Run on**: a runner (each shows its status), or a pool, shown as **Pool <name> (least busy runner)**. A
   run sent to a pool goes to its least busy online runner.
3. Fill in the inputs. Each input is a field with its type, and * marks a required one; an empty field uses the
   input's default. True/False inputs are a list; lists, tables and dictionaries are typed as JSON. A **Credential**
   input takes a vault reference such as `finance/erp/password`, never the secret itself.
4. Press **Run**. A message says "Started <agent> (run <id>): follow it in Agent Activity."

Run-as users, a run mode, a priority and a result callback are not in this dialog. The classic console's run dialog
still has them (**Help > Classic console**, then **Agent Repository** and **Run**): **Run-as users (each on their
default runner)** gives each person ticked their own run on their default runner, acting as them, with their own vault
values; **Run mode, priority and result callback** sets the run mode (below), a **Priority on the runner** (Low,
Normal, High, Urgent) and a **Callback URL** that receives the result as JSON when the run ends.

**Run modes in practice:**

- **Unattended** (the default): nobody is at the runner. A message box, prompt or file picker without a timeout fails
  at once with `RUN.NEEDS_PERSON` instead of waiting for ever; one with a timeout still shows and closes by itself.
  Forms fail and AI tool approvals are refused. After the run the runner may lock its screen or sign out
  ([07 Runner guide](07-runner-guide.md)).
- **Attended**: someone sits at the runner; dialogs and forms wait for them.
- **Headless**: no desktop at all. Agents that need the screen are refused when the run starts, and browsers open
  without a window.

**Deploying an agent** therefore means: make sure the version you want is the live production version (see
[15 Publishing](15-publishing-to-command-center.md)), then point runs, schedules, triggers, work queues or Agent APIs
at a runner or a pool. You never copy agent files to runners: the runner receives the live version, the repository
files it lists under `requires.files` (up to 50 MB per run) and any custom toolkit it needs, with every run.

## 4. Runners and runner pools from the operator's side

**Manage > Runner Machines** (needs `devices.view`) has two tabs, **Runners** and **Runner pools**. A runner is
**online** when it checked in during the last minute; the list refreshes every 15 seconds. The columns shown at first:

| Column | Meaning |
| --- | --- |
| **Status** | Online, Busy (above a CPU or memory limit), Maintenance, Offline or Disabled |
| **Runner** | its name |
| **Runner pools** | the pools it is in |
| **Runner type** | Single-user, Multi-user, Serverless (in One CommandCenter) |
| **Default users** | whose default runner it is |
| **Running** | how many agents it runs now |
| **CPU** | its CPU use |
| **Runner version** | its OneRunner version, with "update available" when a newer program waits |
| **Last seen** | the last heartbeat |

**Columns** adds more, among them **Lifespan** (**Temporary** for runners removed by themselves), **Screen**
(**Unlocked**: usable; **Locked**: screen agents cannot start; **No desktop (service)**: a Windows service), **After a
run** (what it does after unattended runs), **Memory** and **Program** (**Up to date**, **Updating** or **Update
available**).

Click a runner to see its details by topic: **General** (name, status, **Computer**, **Platform**, pools), **Health**
(CPU and memory), **Sessions** (runner type, default users, screen, after a run, **Running now**) and **Logs and
program** (log level, version, program, toolkits). To see its runs, filter **Manage > Agent Activity** by **Runner**;
to run an agent on it, choose it under **Run on** (section 3).

The **Runner pools** tab lists each pool with its **Description**, **Runners** (a count; hover for their names),
**Online** "n of m", **Owners** and **Users**. With `pools.manage`, **New runner pool** asks for a **Name**,
**Description**, **Owners** (who may change and delete the pool), **Users** (the roles that may send runs to it; empty
means anyone who may start runs) and the runners in the pool; on the **Runners** tab, tick runners and press **Make a
pool** to start one with them. Click a pool (or **Open** in its **⋯** menu) to change it or **Delete pool**; **Show its
runners** lists them on the **Runners** tab. Administrators handle registering, removing and updating runners; see
[07 Runner guide](07-runner-guide.md).

## 5. Schedules

**Manage > Schedules** (needs `schedules.view`; changing needs `schedules.manage`).

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
8. **Times missed while One CommandCenter was down**: **Run once for them** (default) or **Skip them** (a time more
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
- A schedule whose owner is disabled does not run; this is written to the Audit Trail. Hand schedules to another
  owner before someone leaves.
- Schedules always run the live production version.

## 6. Triggers

**Manage > Triggers** (needs `triggers.view`; changing needs `triggers.manage`) start an agent when something
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

   **On One CommandCenter** (checked every interval, 15 seconds or more):

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

Columns: **Trigger**, **Kind** ("Checked by One CommandCenter" or "Watched on the runner"), **Agent**, **Runner**
(and run-as user), **Details**, **Monitoring** (times fired, when last, the last error) and **Status** (**active**,
**paused** or **error**). Row buttons: **Check now** (server triggers: check at once instead of waiting),
**Pause**/**Resume**, **Edit** (name, owner, run-as user) and **Delete**.

> **Tip.** The universal poller only remembers the items present at its first check; it fires for items that appear
> after that.

## 7. Work queues

**Manage > Work queues** (needs `queues.view`) hold items such as invoices, tickets or orders that an agent works
one by one on a runner pool, with retries and a service level.

### Set up a queue (`queues.manage`)

1. Optional: **Work item models** > **Create model**: a **Name**, a **Description** and the fields every item has
   (**Add field**), each **Text**, **Number**, **True/false** or **Date**, **Required** or not and **Shown in the
   list**; then **Save model**. While queues using a model have items, it can gain optional fields but not lose or
   change fields.
2. Press **Create work queue**: **Name**, **Description**, **Work item model**, **Agent that works the items**,
   **Runner pool**, **Service level (minutes from added to done)** and **Attempts per item** (1 to 10).
3. Press **Create work queue** in the dialog.

The agent receives each item in its input `work_item` (a dictionary) and the item's id in `work_item_id`. Its outputs
are kept with the item. A failed item is retried until its attempts are used.

### Add items (`queues.work`)

Open the queue (click its row) and press **Add items**. Paste a JSON list or CSV with a header row, or choose a CSV,
JSON or Excel (`.xlsx`) file (for Excel, name the sheet or leave it empty for the first). A `reference` column names
each item; a `priority` column puts it first. Dates in Excel come as yyyy-mm-dd. Items are checked against the model:
required fields, right types, no unknown fields.

> **Tip.** Large Excel files travel inside a JSON request, which is limited by **Administration > Settings > General >
> Largest upload** (MB; 25 MB by default). Ask an administrator to raise it, or split the file.

### Work the queue

- **Start processing**/**Stop processing** in the row's **⋯** menu (or on the queue's page) switches processing on
  and off. The **Processing** column shows **On** or **Off**.
- The list shows **Waiting** (new items and items waiting to retry), **In progress**, **Succeeded**, **Failed** and
  **Service level**, with the number of items past it in red.
- The queue's page has three tabs: **Work items**, **Details** and **Members**. **Work items** has a chip for each
  status with its count (All, New, In progress, To validate, Waiting to retry, Succeeded, Failed, Deferred) that
  filters the list, a search box (**Search reference or data**) and the items with **Reference**, **Data**, **Status**,
  **Attempts**, **Priority**, **Added** and **Result or error**.
- Tick items and use **Retry**, **Defer** or **Delete** (items being worked on are kept). **Defer** asks **Until (your
  time)**: after that time processing picks the items up again by itself. Left empty, they wait until someone presses
  **Retry**. The status shows "until <time>" or "until Retry".
- Click an item (or **Open** in its **⋯** menu) to change its data, reference or priority (the form follows the model)
  and press **Save changes**; while it is in progress you can only look at it.
- **Validate** appears in the **⋯** menu of items **To validate**: the agent was unsure of some fields (marked red with
  their confidence). Correct them and **Approve** (the item goes back to the agent with `validated_fields`, `validated`
  and `validated_by`), or type a reason and **Reject** (the item fails). Both are audited.
- **Export CSV** downloads the items (filtered by status); cells a spreadsheet would read as a formula are made safe.
- **Export definition** (in the queue's **⋯** menu) saves the queue's settings, model, members and waiting items;
  **Import queue** on the list page makes a queue from such a file and lists anything it could not link.
- **Members** (**Change members**): owners manage the queue, participants add and change items, consumers process the
  items (start processing and retry). Without members, roles alone decide. People with `queues.all` see every queue.

## 8. Workflow and the Task inbox

**Manage > Workflow** (needs `runs.view`) lists every workflow run with **Workflow** (and version), **Status**
(running, waiting, completed, failed, cancelled), **Current stage**, **Started by**, **Started** and **Updated**.

To start one (`runs.start`): press **Start a workflow**, choose the **Workflow (live production version)**, fill in
its start form (or **Starting data (JSON, optional)**), optionally a **Reference** such as `PO-2026-0042` to find the
request again, and press **Start**.

Click a run to monitor it: **Reference**, **Status**, **Current stage**, **Started**, **Finished**, **Error**, a
**Timeline** of events (agent started, finished or failed, task created or completed, approved, rejected, decided,
notified) with **Open agent run** links, and the workflow **Data**. **Cancel run** withdraws its open tasks.

People's steps arrive in **Manage > Task inbox** (tabs **To do** and **Done**). Administrators can tick
**Everyone's tasks** to see who is holding what. Each task can be taken (**Take it**), given back (**Give back**),
filled in and submitted, approved or rejected, or marked as read. See [14 Business users](14-business-users.md).

## 9. Agent APIs

**Build > Agent APIs** (needs `apitasks.manage`) put an agent behind its own web address and token for other systems.

1. Press **New API task**: **Name**, **Agent**, and either a **Runner** / **Or a runner pool**, or tick **Run on the
   One CommandCenter, with no runner (serverless)** for agents that only call web services and work on data (at most 5
   minutes per run, four at a time per node, files only in the run's own workspace).
2. **Wait for the result up to (seconds, 1-300)**.
3. Press **Create**. Copy the **Address** and the **Token** (shown once) and the example `curl` command.

Callers send `POST` with `X-OOA-Task-Token` (or `Authorization: Bearer`) and `{"inputs": {...}}`. They get 200 with
`{status, run_id, outputs, error}` when the run finished in time, or 202 and then ask `GET .../runs/<run_id>`. Calls
run as the person who made the task, at high priority, and show in Agent Activity like any run. The list shows **Runs on**,
**Waits up to**, **Calls** (and last use) and **Status**; buttons **Switch off**/**Switch on**, **New token** (the old
one stops at once) and **Delete**.

## 10. Agent tests

**Build > Agent tests** keep test sets: an agent and cases with inputs and expected results. Press **Run**, choose a
runner or pool, and each case runs the live production version once. The test run is scored when every case answered
(for example "4 / 5"); each case says why it passed or failed. **History** shows the scores over time. Builders write
the cases ([04 Running and debugging](04-studio-running-and-debugging.md)); operators run them after changes to
runners, toolkits or connected systems.

## 11. Operational Insights: Overview, Alerts and Operations

The **Operational Insights** section of the menu has four pages. **Operations** looks at how the work ran; **Business**
(section 12) looks at what the work is worth.

| Page | Tabs | What it tells you | You need |
| --- | --- | --- | --- |
| **Overview** | | How things stand now, and what needs attention | `analytics.view` |
| **Alerts** | **Alerts**, **Rules** | Alerts raised by rules that watch runs, runners, work queues and AI budgets | `analytics.view`; rules need `alerts.manage` |
| **Operations** | **Agents**, **Runners**, **Work queues**, **Audit**, **AI usage ↗** | How agents, runners and queues worked over a period, and who did what | `analytics.view`; **Work queues** also `queues.view`; **Audit** and **AI usage** `audit.view` |
| **Business** | **Agents**, **Value**, **Dashboards** | The values agents return, the work they saved, your own dashboards | `analytics.view` |

You only see the tabs your role allows. The open tab is part of the page's address (for example
`#/operations?tab=runners`), so you can bookmark a tab or send a link to it, and the browser's Back button returns to
the tab you came from. The pages that used to stand on their own are now tabs: **Runner capacity** is
**Operations > Runners**, **Work queue insights** is **Operations > Work queues**, and **Value** and **Dashboards** are
under **Business**. Old links and bookmarks still work and open the new tab.

Every page has an **i** after its title that opens help on each figure and button.

### Overview

**Overview** shows on one screen, for the last **24 hours** or **7 days**: open alerts, runs going on and waiting, the
share of finished runs that completed, failed runs, runners online, work queue items waiting and overdue, AI tokens used
this month and hours saved. **Needs attention** lists everything to look at: open alerts, why runs failed (each error
with its runs and agents), runners that are offline, out of service, over their CPU or memory limit or on an old
OneRunner, and queues with overdue items. Click any figure, error or row to open the page behind it, already filtered.
The page refreshes every 30 seconds.

### Alerts

An **alert rule** watches one thing and tells people when it happens: in their **Task inbox**, by e-mail, or by a
webhook (for example to Teams or Slack).

| Watch for | Clears |
| --- | --- |
| A run fails | When someone acknowledges it |
| Too many runs fail (a share of finished runs over a time) | When the share drops again |
| A run takes too long | When the run ends |
| A runner stops reporting | When the runner reports again |
| Work queue items are overdue | When no item is overdue |
| An AI budget is nearly used | The next month |
| An agent raises an alert (its **Raise alert** step) | When someone acknowledges it |

An agent can raise an alert itself with the **Jobs and alerts** toolkit's **Raise alert** step: a message, Warning or
Critical, and what it is about. It goes to the people of the rules of kind **An agent raises an alert** (for every
agent or that one); with no such rule it is only listed under Alerts. A run raises at most 20 alerts. The same
toolkit's **Get job**, **List jobs** and **Stop job** let an agent see and stop other runs, with the permissions of the
user the agent runs as.

To make a rule (needs `alerts.manage`):

1. Press **New alert rule**. Give a **Name** and choose **Watch for**.
2. In **In**, choose the agent, runner, work queue or model connection to watch, or leave it at any.
3. Change the limit if you like (for example **Running longer than (minutes)**), and choose the **Severity**:
   **Warning** or **Critical**.
4. Fill in who to tell: **People, in their task inbox** (`role:NAME` or `user:NAME`), **E-mail addresses** and a
   **Webhook address**.
5. Press **Make the rule**. Use **Send a test** in the rule's **⋯** menu to check the people and addresses get it.

On the **Alerts** tab, tick open alerts and press **Acknowledge** to show that someone is on it. Filter by status,
severity or rule. A new rule only counts what happens after it is made. The bell in the top bar shows how many alerts
are open.

### Operations > Agents: how agents ran

**Agents** summarises a period, from the last 24 hours to the last year, for **All agents** or one, **All runners**
or one, and runs **Started any way** or one way (schedule, trigger, work queue and so on):

- the figures, in this order: **Runs**, succeeded (with the success rate), in progress or waiting, failed and stopped,
  the average run time and **95% of runs finish within**, each compared with the period of the same length just before;
- how long runs take (a distribution) and runs by week, split by how they ended;
- **When runs start**: a grid of weekdays and hours in your time zone; darker means more runs, and hours with failed
  runs are outlined;
- **Why runs failed**, **Agents that fail most**, **Agents run most** and the agents that take longest, with their most
  common errors;
- **Runners** and **How runs were started**.

Click a figure or an error to see those runs in **Agent Activity**. Each list has a **CSV** button; **Run history
CSV** downloads every run of the period with the filters you chose. Click an agent's name for its **Statistics**:
its errors, runners and the **variable profile** (for each output, how often it had a value, its most common values
and, for numbers, the range and the average). Only agents you may see are counted.

### Operations > Runners

**Runners** shows, for the last 24 hours to 90 days, how much of your runners' time agents use and how long runs wait
for a runner:

- runners **Online now** and **Offline now**, and the **Schedules on** with the next one to run;
- the share of runner time in use, busy hours (also as full-time people working 40 hours a week), runs waiting for a
  runner now, and how long runs waited to start;
- each runner with its state, pools, succeeded and failed runs, last run, most common error, busy time, average wait and
  its CPU and memory now;
- the most common errors on runners, a grid of when runners are busy, and busy hours per day.

When runners are busy 80% of the time or more, or runs wait 10 minutes or longer, the page suggests adding a runner or
moving schedules to quieter hours. A runner's **⋯** menu opens it on **Runner Machines** or shows its runs.

### Operations > Work queues

**Work queues** (also needs `queues.view`; only the queues you may see) shows, for the last 24 hours to 30 days, items
waiting, overdue and at risk (waiting more than three quarters of the queue's target time), and per queue: the oldest
waiting item, the shares completed and done in time, items an hour, handling time and wait, and the **Runners** needed
to clear what waits in time next to the runners in its pool. Advice appears when processing is off while items wait,
when a queue needs more runners than its pool has, or when items are overdue. Click a row for items added and finished
per day and why items failed; **Open the queue** opens it in **Work queues**.

### Operations > Audit

**Audit** (needs `audit.view`) shows who did what over a period: the audit entries, the people who acted, sign-ins and
failed sign-ins, actions not done (failed or refused) and the busiest day; entries by activity, by who and by result,
and per day; and the latest 20 actions that failed or were refused. Click a bar to filter the page by it (**Who**,
**Activity**, **Result**). **Open in Audit Trail** opens the Audit Trail with the same filters, to read each record or
export them.

### Operations > AI usage

**AI usage ↗** opens **AI > AI governance**: every AI call, its tokens and cost, by model and by agent
([06 Administrator's guide](06-administrators-guide.md#15-ai-model-connections-prompt-skills-and-ai-governance)).

## 12. Business: agents, value and dashboards

### Business > Agents: each agent's own dashboard

A builder can mark an output of an agent as **Business figure (KPI)** in One AgentStudio, but every value an agent's
runs return (its outputs) can be looked at here.

1. Open **Operational Insights > Business**. The **Agents** tab lists every agent whose runs returned values in the
   period, KPI-marked ones first. Choose the period, or search for an agent or a value.
2. Click an agent. Its page has three tabs:
   - **Dashboard**: made for you from its values: runs that returned values, the total of each number value, the first
     one per day, how its runs ended, and each run's values (the latest 20; click a run to open it).
   - **Data profile**: for each value, its kind (number, text, yes/no, list, record or mixed), how many runs returned
     it, the smallest, largest, average and total, how many different values there were and the ten most common.
   - **Compare**: this agent next to another agent in the same period, or next to itself in the period before.
3. To keep the dashboard and change it, press **Make my own copy** (needs `insights.design`). It is saved under
   **Business > Dashboards** as one of yours, with widgets over the agent's outputs.

**Open in Automation** opens the agent in the repository.

### Business > Value: the work your agents saved

**Value** shows the manual work the agents' successful runs saved: **hours** (each successful run times its agent's
minutes saved), **money** (hours times the cost of an hour of the manual work, set by an administrator in **Settings**
with the currency) and **full-time people** (40 hours a week), each compared with the period before. Choose the
period: the last 7, 30 or 90 days, the last year, or **This year** (from 1 January).

Below the figures, three tabs:

| Tab | One line per | Shows |
| --- | --- | --- |
| **By agent** | Agent | Runs, **Minutes a run**, **Cost of an hour**, **Estimated a month**, **Actual a month**, hours and money saved |
| **By work queue** | Work queue | **Items done**, **Minutes an item**, **Cost of an hour**, hours and money saved |
| **By folder** | Top folder | Runs, hours and money saved. Click a folder to see only its agents in **By agent** |

A run counts once. Work done through a work queue counts each item at the queue's own minutes saved and cost of an
hour when they are set, instead of the agent's, so nothing is counted twice.

**Change the savings of an agent** (needs `insights.admin` or `repo.production`):

1. On **By agent**, click the agent (or choose **Change savings…** in its **⋯** menu).
2. Fill in **Minutes of manual work one successful run saves**, **Cost of an hour of that work** and **Runs a month
   expected** (the number agreed when the agent was approved). Leave a box empty to use the default from Settings.
3. Press **Save**. **Estimated a month** is then the expected runs times the minutes saved; **Actual a month** is what
   its runs really saved, as a month, so you can see whether the agent meets its promise.

**Change the savings of a work queue** (needs `insights.admin`): on **By work queue**, choose **Change savings…** in
the queue's **⋯** menu and fill in **Minutes of manual work one item saves** and **Cost of an hour of that work**.

**This year's goal** (needs `insights.admin`): press **Set the goal…** and give the **Hours of manual work to save this
year**, the **Money to save this year**, or both. The card then shows how much of the goal is reached and where the
present pace ends by 31 December. **Change the goal…** changes it.

Hints appear under the figures while some agents count nothing (no minutes of their own and a default of 0) or no
cost of an hour is set. Only agents you may see are counted.

### Business > Dashboards

**Dashboards** shows dashboards of widgets: your own and those shared with you. Pick one in the list at the top; beside
it you see whose it is and who it is shared with. Making, changing, copying and e-mailing dashboards needs
`insights.design` (administrators have it; a custom role can be given it).

**Make a dashboard:**

1. Press **New dashboard**. In **Start from**, choose **Nothing: add widgets myself** or a ready-made dashboard
   (*Operations at a glance*, *Value*, or one over a business figure your agents record).
2. Give a **Name**, **Share with** (`role:viewer`, `team:finance`, `user:jane`, `*` for everyone who may see
   dashboards, or empty to keep it yours) and a **Description**.
3. Press **Add a widget** and fill in the form:
   - **Title** (empty: named after its figure) and **Show as**: a number, bars, a line, a table or a pie;
   - **Figure from**: **The runs** (then **Figure**: how many ran, the success rate, failed runs, the average run time
     or the hours saved), **A KPI agents record** (then the **KPI** and **Work out**: total, average, count, smallest,
     largest or latest), or **An agent's output** (any output, KPI-marked or not, of the agent you choose);
   - **Agent** (one, or all), **Split by** (day, week, month, agent or value) and **Days looked at** (1 to 365).
   Press **Done**. Use each widget's **⋯** menu to change, move or remove it. **Edit as JSON** shows every widget as
   text, to copy or change many at once.
4. Press **Save**.

Each widget has a **CSV** button for its figures. A pie shows the three largest parts and the rest as *Others*.

The **⋯** menu next to the dashboard list has:

| Entry | What it does |
| --- | --- |
| **Edit** | Change a dashboard you may change |
| **Copy as mine** | Make your own copy of any dashboard you can see |
| **Print or save as PDF** | Opens the browser's print window with only the dashboard, its name and the time. Choose **Save as PDF** there to keep or send it |
| **E-mail it on a schedule…** | Sends the dashboard's figures to people by e-mail, with a PDF of them; see below |
| **Delete…** | Deletes a dashboard you may change, after asking |

**Send a link.** The address in the browser names the dashboard you are looking at
(`#/business?tab=dashboards&dashboard=...`). Copy it and send it: anyone who may see the dashboard opens it directly.

**E-mail a dashboard on a schedule** (needs e-mail set up by an administrator):

1. Open the dashboard and choose **E-mail it on a schedule…** in the **⋯** menu.
2. In **To**, give `user:NAME`, `role:NAME` or e-mail addresses, separated by commas (50 at most).
3. Choose **How often**: **Each day**, **Each week** (with the weekday in **On**) or **Each month (the 1st)**; the time
   in **At**; and the **Time zone**.
4. Press **Add the e-mail**.

Each e-mail holds every widget's figure (or its first ten parts) and a link to the dashboard, and carries the same
figures as a PDF file, to keep or forward. The figures are worked out as the dashboard's maker sees them. The dialog lists each schedule with its next time and when it was last sent, or
what went wrong. In a schedule's **⋯** menu, **Send now** sends it at once (a good test) and **Stop** ends it.

### Figures for Power BI, Tableau or a data warehouse

The same figures can be read by other tools through the **analytics data API**: every run (with its outputs), every
business figure recorded and, with `audit.view`, the audit entries, a page at a time. A tool signs in with an API key of
a person who may see the figures, and gets only the agents that person may see. Your administrator or developer sets
it up; the calls are described in
[Operations, audit and dashboards](../functional/11-operations-audit-and-dashboards.md#the-analytics-data-api).

## 13. Handling failures

### A step-by-step routine

1. Open the failed run (Home > **Recent failures**, or **Agent Activity > Completed** filtered to **Failed**).
2. Read the red error box: the **code**, the message, the step and the **Fix**.
3. Open **Steps** to see where it stopped and whether it retried; **Log** with **Errors** for the detail;
   **Variables** for the values just before the failure.
4. Decide: a passing problem (a slow system, a locked file) → **Run again**. A problem with the runner → section 4 and
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
| Runs stay **queued** on one runner | The runner is offline, disabled, or busy with a long run; check **Manage > Runner Machines** |
| A run for one runner is refused at once, saying the runner is offline | Your administrator has switched on **Refuse runs for a runner that is offline**. Start the runner, or send the run to a runner pool |
| A trigger stopped by itself: "Switched off after n failed runs in a row" | Its last runs all failed, as many times in a row as your administrator allows. Fix the agent, then **Resume** the trigger |
| A work item failed as abandoned | It stayed in progress longer than your administrator allows (for example after a runner crashed). Retry it |
| A failed step has no screenshot | Your administrator has switched off **Screenshots when a step fails**, for example where screens show private data; the run log says so |
| A runner shows "update One CommandCenter" in **Agent** | The runner is newer than One CommandCenter and gets no work until One CommandCenter is updated |
| Scheduled runs never appear | The schedule is disabled, its owner is disabled, today is in its holiday calendar, or the agent has no live version |
| A trigger shows **error** | Read **Monitoring**: often a wrong vault reference, a password change, or an unreachable server; fix it and press **Check now** |
| Every run is refused after a date | The licence or evaluation expired; tell an administrator |

## Related guides

- [00 Index](00-README.md)
- [01 Getting started](01-getting-started.md)
- [06 Administrator's guide](06-administrators-guide.md)
- [07 Runner guide](07-runner-guide.md)
- [14 Business users](14-business-users.md)
- [15 Publishing to One CommandCenter](15-publishing-to-command-center.md)
