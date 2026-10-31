# 11 Operations, audit and dashboards

## What this covers

What the Command Center shows and keeps about the work it does: the console menu and live updates, Home, the Agent
Runs pages (Live and History) and run details, the Operations analytics, dashboards built on business figures (KPIs),
the Audit Trail with its integrity check, export and forwarding, retention of runs, logs, AI calls, work items and
audit records, e-mail from the Command Center, backups, and the Command Center details page.

## Contents

1. The console menu and live updates
2. Home
3. Agent Activity: In progress and Scheduled
4. Agent Activity: Completed and run details
5. Operational Insights: Overview and Operations
6. Business figures (KPIs) and dashboards
7. Audit Trail
8. Retention
9. E-mail from the Command Center
10. Backups
11. This Command Center and several nodes
12. Permissions

## 1. The console menu and live updates

The menu has **Home** and five sections; a person sees only the pages their permissions allow.

| Section | Pages (permission) |
| --- | --- |
| Build | Agent Repository (`repo.view`), Agent Templates (`repo.view`), One AgentStudio (new tab), Agent APIs (`apitasks.manage`), Agent tests (`repo.view`) |
| Manage | Agent Activity (`runs.view`), Task inbox (everyone), Runner Machines (`devices.view`), Schedules (`schedules.view`), Triggers (`triggers.view`), Work queues (`queues.view`), Workflow (`runs.view`), Shared Global values (`globals.view`), Connections (`globals.view`), Agent ToolKits (`toolkits.manage`) |
| Operational Insights | Overview, Operations, Dashboards (all `analytics.view`) |
| Administration | Users (`users.manage`), Roles (`users.manage`), Teams (`teams.manage`, or anyone who leads a team), Credential vault (members), Licenses (`users.manage` or `settings.manage`; installing a licence needs `settings.manage`), Audit Trail (`audit.view`), Settings (`settings.manage`) |
| AI | Model Connections (`connections.manage`), Prompt Skills (`skills.view`), Document Automation (`repo.view`), AI governance (`audit.view`) |

The section of the current page is open; the menu can be narrowed to icons. Pages redraw themselves within about a
second of a change: the console keeps one request open (`GET /api/changes?since=TOKEN&wait=25`) that answers as soon as
anything changes, on any node. Lists with an export also have **Columns** to hide columns (remembered per browser).
The console is available in English, Deutsch, Français, Español and 日本語 (user menu > Language; kept per browser).

## 2. Home

Home shows, for the person signed in: agents in the repository, runners online of the total, runs running and queued,
schedules, runs per day (counted in the viewer's time zone), the most-run agents, recent failures, work items waiting,
open tasks, their recently used agents and recent runs, and hours saved over 30 days (minutes saved per successful run,
[document 08](08-repository-versions-and-templates.md)).

## 3. Agent Activity: In progress and Scheduled

On top, a summary: runs running now, runs waiting for a runner, runs failed and ended in the last 24 hours (with the
share completed), and the next scheduled run. Each figure opens the matching tab and filter.

**In progress**: every queued and running run: agent, runner, who started it and how (source), run-as user, how long it
has run, the step it is on (steps finished of the agent's total) and the command running now. Pause, Resume and Stop
reach the runner within seconds (`runs.stop`). A paused run shows **Paused**; one being stopped shows **Stopping**.
Stop asks the agent to stop after its current step; there is no forced stop.

**Scheduled** (`schedules.view`): each schedule's next run, soonest first, with how it repeats, where it runs, as whom
and the holiday calendar it skips. Selected schedules can be run now (`runs.start`) or turned on and off
(`schedules.manage`); schedules are made and changed on the Schedules page.

## 4. Agent Activity: Completed and run details

Finished runs (success, failed, stopped) with status, duration, steps and error code.

| Filter | Values |
| --- | --- |
| Status | queued, running, success, failed, stopped |
| Agent, runner, person | exact |
| Source | manual, API, schedule, trigger, work queue, workflow, API task, test, AI assistant (MCP), rerun |
| Period | last 24 hours, 7 days or 90 days (the default), or chosen dates (a date includes the whole day) |
| Text | agent path, run id, error, requester |

Paging (up to 500 per page) and CSV export. **Rerun** runs the live version again with the same inputs.

Run details (a link: `#/activity?run=<id>`) follow a running run live, and show the error with the failed step and a suggested fix; a step-by-step timeline with durations; the full
log (filter, **Download log**); the last value of each variable (credentials masked); inputs and outputs; and the
**AI calls** tab ([document 09](09-ai-features.md)). Log events larger than about 19,000 characters are cut; up to 2,000
events are returned per request.

## 5. Operational Insights: Overview and Operations

**Overview** (`analytics.view`, in the new console) shows on one screen how things stand, for the last 24 hours or 7
days: runs running and waiting, the share of finished runs that completed, runs failed, runners online (and how many
need attention), queue items overdue and waiting, and hours saved over 30 days. Below: why runs failed (each error code
with its runs, the number of agents and the agent failing with it most), the agents failing most, runners needing
attention (offline, out of service, above their CPU or memory limit, or with an old OneRunner), work queues with items
waiting (overdue against their target, processing on or off) and, for `connections.manage`, AI tokens used this month
against each connection's budget (80% or more is marked). Each figure and error opens the page behind it with the
matching filter (Agent Activity takes `tab`, `status`, `period`, `q` and `bot` in its address). Cards a viewer may not
see are left out; the screen refreshes every 30 seconds.

**Operations** (`analytics.view`) shows how agents ran over a period (24 hours to a year), for all agents or one, counting
only agents the viewer may see:

- runs, success rate, total and average run time, and the time within which 95% of runs finish;
- the distribution of run times and runs per day;
- the agents run most, that take longest and that fail most (with their most common errors);
- use per runner and by source.

Each list has a CSV download; **Run history CSV** lists every run. An agent's **Statistics** shows its errors, runners and
**variable profile**: for each output, how often it had a value, its most common values and, for numbers, the range
and average.

| API | Returns |
| --- | --- |
| `GET /api/analytics?days=&from=&to=&bot=&device=&user=&source=&top=` | The Operations figures for the period and filters (`source` matches a prefix), with `failure_reasons`: each error code, its failed runs, agents, the agent failing with it most and the last time |
| `GET /api/analytics/agent?path=` | One agent's statistics |
| `GET /api/analytics/variables?path=` | One agent's variable profile |
| `GET /api/analytics/export?report=` | One list as CSV: `most_run`, `longest`, `most_failed`, `by_runner`, `by_day`, `by_source` or `run_history` |

## 6. Business figures (KPIs) and dashboards

- In Studio, an output variable of type Number, String or Boolean can be ticked **Business figure (KPI)**. Each
  successful run records its value.
- Insights > **Dashboards** holds dashboards of widgets:

| Widget property | Values |
| --- | --- |
| Source | `kpi` (one KPI, optionally of one agent, aggregated with `sum`, `avg`, `count`, `min`, `max` or `last`) or `runs` (metric `runs`, `success_rate`, `failed`, `avg_seconds`, `hours_saved`) |
| Group | `none`, `day`, `week`, `month`, `agent`, or `value` (by the KPI's own value or, with `by`, by another KPI of the same run) |
| Type | number, bars, line, table |
| Period | the last 1 to 365 days (default 30) |

- A dashboard belongs to its maker until shared with `role:…`, `team:…`, `user:…` or `*` (everyone with `analytics.view`).
- Only agents the viewer may see are counted. Days are counted in the viewer's time zone.
- API: `/api/dashboards` (GET lists, POST makes, `PUT`/`DELETE /api/dashboards/{id}`), `GET /api/dashboards/{id}/data?tz=`
  (the widgets' figures, with days in that time zone) and `GET /api/dashboards/kpis` (the KPIs that can be chosen).

## 7. Audit Trail

- Every sign-in, change, run, credential use, task and workflow event is recorded with time, actor, action, object
  type and id, details, result (`ok`, `failed`, `denied`, `not_found`) and address.
- **Integrity**: records are hash-chained with a key kept encrypted by the vault key, so someone who can write to the
  database cannot rebuild a consistent chain. Every hour the last record's position and hash are appended to
  `audit-checkpoints.log` in the data folder. **Verify integrity** re-checks the whole chain against the database and
  the checkpoints and reports any record edited or deleted, including deleted newest records.
- **Search**: free text, actor, action (an exact action or a prefix such as `repo.`), object type and id, result, date
  range, newest or oldest first, paging (up to 1,000 per page) with the total.
- **Export CSV** downloads the search, up to 50,000 records; the export is itself recorded (`audit.export`).
- **Forwarding**: with a syslog target, every record is also sent to a SIEM (RFC 5424 over UDP or TCP) from a bounded
  queue, so a slow collector never slows the Command Center; records that do not fit are counted as dropped. Settings
  has a test message.
- API (`audit.view`): `GET /api/audit?q=&actor=&action=&object_type=&object_id=&result=&from=&to=&sort=&limit=&offset=`,
  `GET /api/audit/export` with the same filters (CSV), and `GET /api/audit/verify`.

## 8. Retention

All in Administration > Settings; 0 keeps everything. Applied at most once an hour by the leading node.

| Setting | Removes |
| --- | --- |
| `run_log_retention_days` | Step logs of runs finished longer ago |
| `run_retention_days` | Whole finished runs (with their logs) finished longer ago |
| `ai_call_retention_days` | AI call records |
| `work_item_retention_days` | Succeeded and failed work items |
| `audit_retention_days` | Audit records (at least 90 days when set); they are first moved to compressed files in `audit-archive`, and a record of the cut keeps the chain verifiable |

Queued and running runs are never removed; work items keep their results. **Remove now** applies given run and log
periods at once (`settings.manage`). Every removal is audited (`runs.purge`, `ai.calls_purged`, `queue.items_purged`).

## 9. E-mail from the Command Center

Administration > Settings > E-mail: mail server, port (default 587), security (`starttls`, `ssl`, or `none` on a trusted
network only), account, password as a vault reference, and sender address. **Send a test e-mail** checks it. The
Command Center uses it for workflow notifications to e-mail addresses and for licence reminders (to administrators with
an e-mail address and `license_mail_to`). Messages are plain text; the server's certificate must name the server.

## 10. Backups

- Administration > Settings > Backups: a backup every `backup_hours` (0-168, 0 off) into the data folder's `backups`
  folder or `backup_folder`, kept for `backup_keep_days` (1-3650, default 7). **Back up now** makes one at once.
- A backup is one zip with every table and the repository files; vault values stay encrypted and the vault key is
  never included. Restore refuses a backup made with another vault key.
- In the SaaS service, choosing a folder on the server is refused.

## 11. This Command Center and several nodes

Settings shows **This Command Center**: the edition (from the licence or the evaluation), and counts of enabled users,
enabled runners, agents, toolkits and switched-off toolkits. With several nodes on one database, **Several nodes** lists
the nodes and the leader, which runs schedules, triggers, queues, housekeeping and backups; another node takes over
within about 30 seconds.

## 12. Permissions

| Action | Permission |
| --- | --- |
| Agent Activity and run details | `runs.view` |
| Operations, dashboards | `analytics.view` |
| Audit Trail, export, verify | `audit.view` |
| Retention, e-mail, backups, syslog, nodes | `settings.manage` |

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [09 AI features](09-ai-features.md)
- [10 Users, roles, teams and security](10-users-roles-teams-and-security-features.md)
- [Command Center and runner, sections 10 to 10d](../user-guide/00-README.md)
- [Database](../infrastructure/05-databases.md)
