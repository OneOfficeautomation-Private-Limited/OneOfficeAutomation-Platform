# 11 Operations, audit and dashboards

## What this covers

What One CommandCenter shows and keeps about the work it does: the console menu and live updates, Home, the Agent
Runs pages (Live and History) and run details, the Operations analytics, dashboards built on business figures (KPIs),
the Audit Trail with its integrity check, export and forwarding, retention of runs, logs, AI calls, work items and
audit records, e-mail from One CommandCenter, backups, and One CommandCenter details page.

## Contents

1. The console menu and live updates
2. Home
3. Agent Activity: In progress and Scheduled
4. Agent Activity: Completed and run details
5. Operational Insights: Overview and Operations
6. Business figures (KPIs) and dashboards
7. Audit Trail
8. Retention
9. E-mail from One CommandCenter
10. Backups
11. This One CommandCenter and several nodes
12. Permissions

## 1. The console menu and live updates

The menu has **Home** and five sections; a person sees only the pages their permissions allow.

| Section | Pages (permission) |
| --- | --- |
| Build | Automation (`repo.view`; Public, and Private, the builder's own workspace, for `repo.checkin`), Agent Templates (`repo.view`), Agent APIs (`apitasks.manage`), Agent tests (`repo.view`) |
| Manage | Agent Activity (`runs.view`), Task inbox (everyone), Runner Machines (`devices.view`), Schedules (`schedules.view`), Triggers (`triggers.view`), Work queues (`queues.view`), Workflow (`runs.view`), Shared Global values (`globals.view`), Connections (`globals.view`), Credential vault (members), Agent ToolKits (`toolkits.manage`) |
| Operational Insights | Overview, Alerts, Operations (Agents, Runners, Work queues with `queues.view`, Audit with `audit.view`, AI usage), Business (Agents, Value, Dashboards) (`analytics.view`) |
| Administration | Users (`users.manage`), Roles (`users.manage`), Teams (`teams.manage`, or anyone who leads a team), Policies (`settings.manage`), Licenses (`users.manage` or `settings.manage`; installing a licence needs `settings.manage`), Audit Trail (`audit.view`), Settings (`settings.manage`) |
| AI | Model Connections (`connections.manage`), Prompt Skills (`skills.view`), Document Automation (`repo.view`), AI governance (`audit.view`) |

The section of the current page is open; the menu can be narrowed to icons. Pages redraw themselves within about a
second of a change: the console keeps one request open (`GET /api/changes?since=TOKEN&wait=25`) that answers as soon as
anything changes, on any node. Lists with an export also have **Columns** to hide columns (remembered per browser).
The console is available in English, Deutsch, Français, Español and 日本語 (user menu > Language; kept per browser).

## 2. Home

Home shows its figures in two strips of equal cells, **Runs · last 24 hours** and **Platform**, red only when
something is wrong; each figure opens the page behind it. It shows, for the person signed in: agents in the repository, runners online of the total, runs running and queued,
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

Since 1 Nov 2026 the menu follows AA's split of its dashboards into operations and business: **Overview · Alerts ·
Operations · Business**. Operations has the tabs **Agents**, **Runners**, **Work queues** and **Audit**, and **AI usage**
opens AI governance; Business has **Agents**, **Value** and **Dashboards**. The tab is in the address
(`#/operations?tab=runners`), and the old pages' addresses (`#/capacity`, `#/queueinsights`, `#/value`, `#/dashboards`)
open their tab.

**Operations > Agents** (`analytics.view`) shows how agents ran over a period (24 hours to a year), for all
agents or one agent, runner or way of starting, counting only agents the viewer may see:

- in AA's order: runs, succeeded (with the success rate), in progress or waiting, failed and stopped, then the average
  run time and the time within which 95% of runs finish, compared with the period just before (as long as this one);
- the distribution of run times, and runs by week split by how they ended;
- when runs start: a weekday-by-hour heatmap in the viewer's time zone, with hours that had failed runs outlined;
- why runs failed (as on Overview), and the agents run most, that take longest and that fail most (with their most
  common errors);
- use per runner and by way of starting.

Each list has a CSV download; **Run history CSV** lists every run. An agent's **Statistics** shows its errors, runners and
**variable profile**: for each output, how often it had a value, its most common values and, for numbers, the range
and average.

| API | Returns |
| --- | --- |
| `GET /api/analytics?days=&from=&to=&bot=&device=&user=&source=&top=&tz=` | The Operations figures for the period and filters (`source` matches a prefix), with `failure_reasons` (each error code, its failed runs, agents, the agent failing with it most and the last time), `by_hour` (runs and failed runs per weekday, Monday first, and hour in the time zone `tz`, UTC by default) and `previous` (the totals of the period before) |
| `GET /api/analytics/agent?path=` | One agent's statistics |
| `GET /api/analytics/variables?path=` | One agent's variable profile |
| `GET /api/analytics/export?report=` | One list as CSV: `most_run`, `longest`, `most_failed`, `by_runner`, `by_day`, `by_source` or `run_history` |

**Operations > Audit** (`audit.view`), as AA's Audit dashboard: for a period, the audit entries, the people who acted
(sign-in attempts with unknown names count), sign-ins and failed sign-ins, actions not done (failed or denied) and the
busiest day; entries by activity, by who (a runner once, whoever it acted for) and by result, and per day; and the
latest 20 that failed or were denied. Bars set filters (who, activity, result); rows and figures open the Audit Trail
with the same filters (it reads `actor`, `area`, `result`, `object_id` and `range` from its address).
`GET /api/analytics/audit?days=&actor=&kind=&result=&tz=`.

**Operations > Runners** adds AA's Devices dashboard figures to Runner capacity: runners online and offline now,
schedules switched on and the next one, each runner's succeeded and failed runs, last run and most common error, and
the most common errors on runners.

### Runner capacity

**Runner capacity** (now Operations > Runners; `analytics.view`, new console) shows, for the last 24 hours to 90 days: the share of the switched-on
runners' time in use (at most 100% for a runner: only runs still going count up to now, and a run lost with its runner
ends at the runner's last report); busy hours and what they equal in full-time people (40 hours a week); runs waiting for a runner
now; how long runs waited to start (average and 95%); each runner with its state, pools, runs, busy time, share of the
period, average wait and current CPU and memory; the share of runner time in use per weekday and hour (in the viewer's
time zone); and busy hours per day. When runners are in use 80% of the time or more, or 95% of runs wait 10 minutes or
more, it suggests adding a runner or moving schedules into quieter hours; when several runners are under 10% in use,
that they have room. A run's running time inside the period counts (a run still going, up to now). These are runner
figures: every run counts and no agent is named.

`GET /api/analytics/capacity?days=&from=&to=&tz=` returns `runners` (`device_id`, `busy_ms`, `runs`, `use`,
`avg_wait_ms`), `enabled_runners`, `busy_ms`, `use`, `full_time_people`, `avg_wait_ms`, `p95_wait_ms`, `waiting_now`,
`heat` (`time_zone`, `use`: per weekday, Monday first, and hour; null for hours outside the period) and `by_day`.

### Work queue insights

**Work queue insights** (now Operations > Work queues; `queues.view`, new console; only queues the viewer may see) shows, for the last 24 hours to 30
days: items waiting, overdue and at risk (waiting more than three quarters of the queue's target time) across queues,
and items finished with the share completed. Per queue: waiting now, the oldest waiting item's age, overdue, at risk,
the target time, items finished, the shares completed and finished within the target time, items finished an hour,
average handling time (the item's run time) and wait to start, the runners needed to clear what waits within the target
time (waiting items x average handling time / target time, set against the runners of the queue's pool) and whether
processing is on. A queue opens to its items added and finished per day and its failures by error code. Advice is shown
when processing is off while items wait, when a queue needs more runners than its pool has, or when items are overdue.

`GET /api/analytics/queues?days=&from=&to=&tz=` returns `queues`, each with `counts`, `waiting`, `oldest_waiting`,
`overdue`, `at_risk`, `added`, `finished`, `success`, `failed`, `success_rate`, `in_time_rate`, `per_hour`,
`avg_handling_ms`, `avg_wait_ms`, `runners_needed`, `errors` and `by_day`.

### Value

**Value** (now Business > Value; `analytics.view`) shows the manual work the agents' successful runs saved over the last 7 days
to a year: hours (each successful run x its agent's minutes saved, or `default_minutes_saved`), money (hours x
`hourly_cost`, in `currency`; both in Settings > General) and full-time people (40 hours a week), each compared with the
period before; hours saved per day (per week for long periods); and per top folder and per agent the successful runs,
hours, money, run time and hours saved per hour run. People with `repo.production` change an agent's minutes saved in
place (or return it to the default). Advice is shown while agents count nothing (no minutes of their own and a default
of 0) or no hourly cost is set. A run counts once, so a work queue's items are not counted twice. Only agents the
viewer may see count.

`GET /api/analytics/value?days=&from=&to=&tz=` returns `currency`, `hourly_cost`, `default_minutes_saved`,
`successful_runs`, `hours`, `money`, `full_time_people`, `previous_hours`, `previous_money`, `agents` (with
`minutes_each` and `own_value`), `folders` and `by_day`. `POST /api/repo/roi` `{path, minutes_saved}` (null for the
default) sets an agent's minutes.

### Alerts

**Alerts** (new console) holds rules that watch one condition each. The server checks them once a minute on the leader
node; each condition met raises one alert per subject and sends it to the task inbox (`user:NAME`, `role:NAME`), to
e-mail addresses (Settings > E-mail) and to a webhook (posted as JSON, three attempts; on a SaaS tenant only to public
addresses). A rule is **Warning** or **Critical** and can be turned off.

| Watch for | In (optional) | When | Clears |
| --- | --- | --- | --- |
| A run fails | an agent | each failed run | when acknowledged |
| Too many runs fail | an agent (or all) | the failed share of finished runs in the period (default 50% over 60 minutes) reaches the threshold, once 3 runs have finished | when the share drops |
| A run takes too long | an agent | a run still running after the time (default 60 minutes) | when it ends |
| A runner stops reporting | a runner | an enabled runner not in maintenance is silent for the time (default 5 minutes) | when it reports |
| Work queue items are overdue | a queue | at least the number of items (default 1) wait longer than the queue's target time | when none is overdue |
| An AI budget is nearly used | a model connection | tokens this month reach the share of the budget (default 80%) | next month |
| An agent raises an alert | an agent (or all) | an agent's Raise alert step runs (`ooa.jobs/raise_alert`: message, severity, subject); the subject once per run, at most 20 per run; with no rule it is listed under "Raised by an agent" and sent to nobody | when acknowledged |

A new rule counts what happens after it is made. One check raises at most 20 alerts per rule and one more that says
how many were held back. **Send a test** sends a test alert through the rule's recipients. Alerts are **Open**,
**Acknowledged** (seen) or **Cleared**; deleting a rule clears its alerts and keeps their history. Alerts about an agent
are shown only to people who may see it. Overview shows the open alerts.

| API | Does |
| --- | --- |
| `GET /api/alerts?status=open\|acknowledged\|cleared\|all&rule=&limit=&offset=` | Alerts, newest first, with the number open |
| `POST /api/alerts/acknowledge` `{ids}` | Acknowledges open alerts |
| `GET /api/alert-rules`, `POST`, `PUT /api/alert-rules/{id}`, `PATCH` `{enabled}`, `DELETE` | The rules (`kind`, `scope`, `threshold`, `window_minutes`, `notify_to`, `webhook_url`, `severity`, `enabled`) |
| `POST /api/alert-rules/{id}/test` | Sends a test alert |

## 6. Business figures (KPIs) and dashboards

**Business > Agents** (`analytics.view`), as AA's Business dashboards: every agent whose runs returned values (outputs)
in the period, KPI-marked ones first. Each agent has a page of its own:

- **Dashboard**, made from its values: runs with outputs, the total of each number value, the first one per day, how
  its runs ended, and each run's values (the latest 20);
- **Data profile**, as AA's: for each value its kind (number, text, yes/no, list, record or mixed), the runs that
  returned it, the smallest, largest, average and total, the number of different values and the ten most common;
- **Compare**: the agent against another agent, or against itself in the period before.

**Make my own copy** saves the made dashboard as one of the person's dashboards (`insights.design`), with widgets over
the agent's outputs. `GET /api/analytics/business-agents?days=`; the profile is `GET /api/analytics/variables?path=`
(now with `kind`, `sum`, `distinct`, `by_day` and the latest runs' values in `recent`).

**Business > Value** adds UiPath's Business ROI: *This year*; a yearly goal in hours or money with the share reached
and where this pace ends by 31 December; each agent's own cost of an hour and the runs a month expected (estimated
against actual hours a month); and **By work queue**: a queue item done counts its queue's minutes saved and cost of an
hour, when set, instead of its agent's, so nothing is counted twice. Savings and the goal need `insights.admin`
(an agent's savings also `repo.production`, as before). API: `POST /api/repo/roi` `{path, minutes_saved, hourly_cost,
estimated_runs_month}`, `POST /api/analytics/queue-savings` `{queue_id, minutes_saved, hourly_cost}`,
`POST /api/analytics/goal` `{hours, money}`.

- In Studio, an output variable of type Number, String or Boolean can be ticked **Business figure (KPI)**. Each
  successful run records its value.
- Operational Insights > **Dashboards** (new console) holds dashboards of widgets. Widgets are made in a form (what to
  show, the figure, the agent, how to split it, the days) or edited as JSON. A new dashboard can start from a ready-made
  one: *Operations at a glance*, *Value*, or one over the first KPI recorded. Each widget has a CSV of its figures; any
  dashboard a person may see can be copied as their own (not shared). A pie shows the three largest parts and the
  rest as *Others*, with a legend of values and shares.

| Widget property | Values |
| --- | --- |
| Source | `kpi` (one KPI, optionally of one agent, aggregated with `sum`, `avg`, `count`, `min`, `max` or `last`), `output` (one output of one agent, KPI-marked or not, named in `kpi`, with `bot`) or `runs` (metric `runs`, `success_rate`, `failed`, `avg_seconds`, `hours_saved`) |
| Group | `none`, `day`, `week`, `month`, `agent`, or `value` (by the KPI's own value or, with `by`, by another KPI of the same run) |
| Type | number, bars, line, table, pie |
| Period | the last 1 to 365 days (default 30) |

- A dashboard belongs to its maker until shared with `role:…`, `team:…`, `user:…` or `*` (everyone with `analytics.view`).
  Making, changing and copying dashboards needs `insights.design`.
- The address names the dashboard shown (`#/business?tab=dashboards&dashboard=ID`), so a link opens it.
- **Print or save as PDF** opens the browser's print window with only the dashboard, its name and the time.
- **E-mail it on a schedule…** sends the dashboard's figures (each widget's number, or its first ten parts), the same
  figures as an attached PDF (`<dashboard>.pdf`, written without a PDF library) and a link
  each day, week (on a weekday) or month (its first day) at a time in a time zone, to `user:…`, `role:…` or e-mail
  addresses (at most 50). The figures are those its maker sees. Each schedule shows its next time, the last sending
  or what went wrong; **Send now** checks it. Needs `insights.design` and e-mail set up (section 9). API:
  `GET`/`POST /api/dashboards/{id}/mail`, `DELETE /api/dashboards/mail/{schedule}`, `POST /api/dashboards/mail/{schedule}/send`.
- Only agents the viewer may see are counted. Days are counted in the viewer's time zone.
- API: `/api/dashboards` (GET lists, POST makes, `PUT`/`DELETE /api/dashboards/{id}`), `GET /api/dashboards/{id}/data?tz=`
  (the widgets' figures, with days in that time zone) and `GET /api/dashboards/kpis` (the KPIs that can be chosen).

### The analytics data API

For Power BI, Tableau or a data warehouse: rows a page at a time, oldest first, with an API key
(`Authorization: Bearer ooak_...`). Each answer is `{items, next}`; pass `next` back as `after` until it is `null`.
`limit` is 1 to 1000 (500 by default). Only agents the key's owner may see are included.

| API | Rows | Permission |
| --- | --- | --- |
| `GET /api/analytics/data/runs?from=&to=&after=&limit=` | id, agent, version, status, created, started, finished, duration_ms, runner, source, started_by, error_code, outputs | `analytics.view` |
| `GET /api/analytics/data/kpis?from=&to=&after=&limit=` | run_id, agent, name, number, text, time | `analytics.view` |
| `GET /api/analytics/data/audit?after=&limit=` | seq, ts, actor, action, object_type, object_id, details, result, ip (`after` is a sequence number) | `audit.view` |

Audit entries can also be forwarded as they happen, by syslog or HTTPS, to a SIEM (Settings > E-mail, proxy and SIEM).

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
  queue, so a slow collector never slows One CommandCenter; records that do not fit are counted as dropped. Settings
  has a test message.
- API (`audit.view`): `GET /api/audit?q=&actor=&action=&object_type=&object_id=&result=&from=&to=&sort=&limit=&offset=`,
  `GET /api/audit/export` with the same filters (CSV), and `GET /api/audit/verify`.

### Event subscriptions and metrics (gap G38)

**Administration > Event subscriptions** (`events.manage`, administrators) sends One CommandCenter's events to other
systems as they happen: a webhook receiver, an integration platform, or Splunk's HTTP Event Collector, and with them
the run log lines. It sits beside the per-run `callback_url`, the alert webhook and SIEM forwarding, which stay as they
were.

| Event | When | Data |
| --- | --- | --- |
| `run.queued` | A run is asked for | `run_id`, `bot_path`, `requested_by`, `source`, `runner_id` |
| `run.started` | A runner takes the run | as above, `runner` |
| `run.finished` | A run ends however it ends | as above, `status` (`success`, `failed`, `stopped`), `duration_ms`; `error_code` `AGENT.LOST` when its runner was lost |
| `run.failed` | A run ends failed (also sent as `run.finished`) | as `run.finished` |
| `run.paused`, `run.resumed` | Someone pauses or resumes a run | `run_id`, `by`, the agent |
| `run.log` | A runner sends a batch of step log entries | `run_id`, `bot_path`, `runner_id`, `lines` (the entries as the run log keeps them) |
| `queue.item_added`, `queue.item_started`, `queue.item_finished`, `queue.item_abandoned` | A work item is added, taken, ends (`success`, `failed`, `retry`, `review`), or is abandoned | `item_id`, `queue_id`, `queue`, `status`, `reference`, `run_id`, the queue's agent; never the item's data |
| `runner.online`, `runner.offline` | A runner's first report after a minute or more without one; the leader sees it silent for a minute | `runner_id`, `name`, `hostname` or `last_seen` |
| `alert.raised` | An alert rule raises an alert | `alert_id`, `rule`, `kind`, `severity`, `subject`, `message`, `bot_path` |
| `audit.entry` | Every audit entry | the entry: `seq`, `ts`, `actor`, `action`, `object_type`, `object_id`, `details`, `result`, `ip` |

A subscription lists the types it takes; a type ending in `*` takes a family (`queue.item_*`, `run.*`, `*`). **Only
for** limits it to folders or agents (path prefixes such as `Finance/`); events that name no agent (runners, most audit
entries) always pass.

**The message.** Each event is one `POST` with `Content-Type: application/json` and the body
`{"id", "type", "time", "node", "tenant" (SaaS only), "data"}`. Headers: `X-OOA-Event` (the type), `X-OOA-Event-Id`
(the same id: an event may arrive twice, so a receiver keeps the ids it has seen), `X-OOA-Timestamp` (seconds since
1970) and, for the `json` format, `X-OOA-Signature: sha256=<hex>`, the HMAC-SHA256 of `<timestamp>.<body>` keyed with
the subscription's secret. A receiver computes the same, compares in constant time and refuses timestamps more than a
few minutes old. The `splunk` format sends the collector's own shape (`time`, `host`, `source` `ooa-command-center`,
`sourcetype` `ooa:event`, `event`) with `Authorization: Splunk <secret>`, the secret being the collector token; a
`run.log` batch becomes one collector event per line. **Send a test event** sends one event of type `test`.

**The secret.** A new `json` subscription without one gets a secret made for it (`whsec_...`), returned once in the
answer; `rotate_secret` makes a new one the same way. It is kept encrypted with the vault's key and never returned or
written to the audit trail.

**Delivery.** Events are queued in memory (bounded: 5,000 waiting, 2,000 being sent) and sent by four threads, so a
slow receiver never holds up a run or a request; events that do not fit are dropped and counted. A delivery with no
answer, or answered 408, 429 or 5xx, is tried again after 2 s, 10 s, 30 s and 2 minutes; other answers end it.
Redirects are not followed. `failures` counts deliveries that failed in a row, with the last status and error; a
success resets it. Each event is sent by the node where it happened (a runner's request, the leader's clock), so with
several nodes it is sent once. Events are not stored: when a node stops, what it had not sent is lost, as for SIEM
forwarding.

**Addresses.** `https://` only, unless the setting `events_allow_http` is on; only public internet addresses, unless
`events_allow_private` is on (loopback, private, link-local such as cloud metadata, carrier-grade NAT and unique-local
addresses are refused). The address is checked when saved and again before every attempt. A SaaS tenant is always
held to public addresses.

| API (`events.manage`) | Does |
| --- | --- |
| `GET /api/event-subscriptions` | `{subscriptions, types, allow_http, allow_private, metrics_token, node}`; each subscription without its secret (`has_secret`), with `this_node` counts |
| `POST /api/event-subscriptions` | Makes one: `name`, `url`, `events` (list or comma text), `format` (`json`, `splunk`), `filters`, `enabled`, `secret` (optional for `json`; the collector token for `splunk`). The answer carries `secret` once when one was made |
| `PUT /api/event-subscriptions/{id}` | Changes it (fields left out keep their value; `rotate_secret: true` makes a new secret) |
| `PATCH /api/event-subscriptions/{id}` `{enabled}` | Turns it on or off (on resets `failures`) |
| `DELETE /api/event-subscriptions/{id}` | Deletes it |
| `POST /api/event-subscriptions/{id}/test` | Sends a test event now, once: `{ok, status, error, event_id}` |
| `POST /api/metrics-token`, `DELETE /api/metrics-token` | Makes (shown once as `token`, kept as a SHA-256 hash) or removes the metrics token |

Every change is audited: `events.subscription_create`, `_update`, `_enable`, `_disable`, `_delete`, `events.test`,
`metrics.token_create`, `metrics.token_delete`.

**Metrics.** `GET /metrics` answers in the Prometheus text format (`text/plain; version=0.0.4`). It takes the metrics
token as `Authorization: Bearer ooam_...`, or a signed-in session with `metrics.view` (administrators); the IP allow
list applies. Every series has a `node` label (and `tenant` in the SaaS service):

| Metric | Kind | Counted |
| --- | --- | --- |
| `ooa_runs_finished_total{status}` | counter | Runs that ended on this node since it started |
| `ooa_run_duration_seconds` | histogram | Their durations (buckets 1 s to 1 h) |
| `ooa_runs{status}` | gauge | Runs queued and running (all nodes) |
| `ooa_queue_items{queue,status}` | gauge | Work items new, retry, deferred, in progress or in review, per queue |
| `ooa_runners{state}` | gauge | Runners enabled, online (reported in the last minute), busy |
| `ooa_alerts_open{severity}` | gauge | Open alerts |
| `ooa_license_seats_used{type}`, `ooa_license_seats_total{type}` | gauge | Licence seats (-1: no limit) |
| `ooa_events_total{outcome}`, `ooa_events_pending` | counter, gauge | Events delivered, failed, dropped and waiting on this node |
| `ooa_jvm_memory_used_bytes{area}`, `ooa_jvm_memory_max_bytes`, `ooa_jvm_threads`, `ooa_jvm_gc_seconds_total{gc}`, `ooa_process_uptime_seconds`, `ooa_process_cpu_count` | gauge, counter | This Java process |
| `ooa_info{leader,schema}`, `ooa_up{part="database"}` | gauge | The node, whether it leads, and whether it could read the database |

The text is made at most once every 5 seconds per node, with a handful of counting queries on indexed columns, so
scraping every 15 to 60 seconds costs little. The figures read from the database are the same on every node: in
queries, take `max by` the other labels across nodes. For OpenTelemetry, point the OpenTelemetry Collector's
`prometheus` receiver at `/metrics` (with the bearer token) and export from the Collector over OTLP; One CommandCenter
has no OTLP exporter of its own, which would need new libraries.

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

## 9. E-mail from One CommandCenter

Administration > Settings > E-mail: mail server, port (default 587), security (`starttls`, `ssl`, or `none` on a trusted
network only), account, password as a vault reference, and sender address. **Send a test e-mail** checks it. The
One CommandCenter uses it for workflow notifications to e-mail addresses and for licence reminders (to administrators with
an e-mail address and `license_mail_to`). Messages are plain text; the server's certificate must name the server.

## 10. Backups

- Administration > Settings > Backups: a backup every `backup_hours` (0-168, 0 off) into the data folder's `backups`
  folder or `backup_folder`, kept for `backup_keep_days` (1-3650, default 7). **Back up now** makes one at once.
- A backup is one zip with every table and the repository files; vault values stay encrypted and the vault key is
  never included. Restore refuses a backup made with another vault key.
- In the SaaS service, choosing a folder on the server is refused.

## 11. This One CommandCenter and several nodes

Settings shows **This One CommandCenter**: the edition (from the licence or the evaluation), and counts of enabled users,
enabled runners, agents, toolkits and switched-off toolkits. With several nodes on one database, **Several nodes** lists
the nodes and the leader, which runs schedules, triggers, queues, housekeeping and backups; another node takes over
within about 30 seconds.

## 12. Permissions

| Action | Permission |
| --- | --- |
| Agent Activity and run details | `runs.view` |
| Overview, Operations, Business, dashboards, alerts (see, acknowledge), the runs and KPI data API | `analytics.view` |
| Make, change, share, copy and e-mail dashboards | `insights.design` (admin only unless a custom role grants it) |
| Savings per agent and work queue, the yearly goal | `insights.admin` (admin only unless a custom role grants it) |
| Alert rules (make, change, test, delete) | `alerts.manage` (admin only) |
| Event subscriptions and the metrics token | `events.manage` (admin only) |
| `GET /metrics` with a signed-in session (or the metrics token) | `metrics.view` (admin only) |
| Audit Trail, export, verify, Operations > Audit, the audit data API | `audit.view` |
| Retention, e-mail, backups, syslog, nodes | `settings.manage` |

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [09 AI features](09-ai-features.md)
- [10 Users, roles, teams and security](10-users-roles-teams-and-security-features.md)
- [One CommandCenter and runner, sections 10 to 10d](../user-guide/00-README.md)
- [Database](../infrastructure/05-databases.md)
