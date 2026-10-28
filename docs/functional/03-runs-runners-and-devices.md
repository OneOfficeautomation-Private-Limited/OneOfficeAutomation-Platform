# 03 Runs, runners and devices

## What this covers

What a run is, how it is started, which checks it passes, the states it moves through and what ends it; priorities,
run-as users, the three run modes, serverless API tasks; and everything about runners (called devices in the API):
registration, online state, pools, default runners per user, single-user and multi-user runners, temporary runners,
the screen state, what a runner does after an unattended run, runner settings, secrets and updates.

## Contents

1. Runs and their sources
2. Starting a run: the checks
3. Run states and transitions
4. What ends a run with an error before or during the run
5. Controls: pause, resume, stop, rerun
6. Priorities and the order of work on a runner
7. Run-as users and deploying to several targets
8. Run modes: attended, unattended, headless
9. API tasks and serverless API tasks
10. Runners: registration and identity
11. Online, offline and disabled runners
12. Runner pools
13. Default runners, single-user and multi-user runners
14. Temporary runners
15. The screen state and after-run actions
16. Runner settings, secrets, toolkits and updates
17. Removing a runner
18. Permissions

## 1. Runs and their sources

A run is one execution of the live production version of an agent (`.flow.json`) on one runner. Every run records
who asked for it (the requester), who it runs as (the run-as user, when different), the source, the version, the
runner, its inputs, its status and, when it ends, its duration, steps run, outputs and error.

| Source (as stored) | Started by |
| --- | --- |
| `manual` | A person, from Agent repository > Run, or the API (`POST /api/runs`) |
| `api` | Deployment to several targets through the API with `source: "api"` |
| `schedule:ID` | A schedule, or its **Run now** |
| `trigger:ID`, `file:ID`, `webhook:ID` | A trigger |
| `queue:ID` | Work queue processing |
| `workflow:ID` | An agent task of a workflow |
| `api_task:ID` | An API task call |
| `test:ID` | An agent test run |
| `mcp` | An AI assistant over the Model Context Protocol |
| `rerun` | **Rerun** of an earlier run |

Studio runs on the designer's own computer are local test runs; they do not appear in the Command Center.

## 2. Starting a run: the checks

In this order; the first failing check refuses the run and nothing is queued:

| Check | Refusal |
| --- | --- |
| The requester (or, for schedules, triggers and queues, the owner) has `runs.start` through any of their roles | `CR.FORBIDDEN` |
| The path is an agent (`.flow.json`) | `CR.NOT_AN_AGENT` |
| Folder access of at least **run** on the agent's folder | `CR.FORBIDDEN` |
| The mode is `unattended`, `attended` or `headless` | `CR.BAD_INPUT` |
| A run-as user exists and is enabled; without a runner or pool, they have a default runner | `CR.NOT_FOUND`, `CR.NO_DEFAULT_RUNNER` |
| A pool has an online runner | `CR.NO_RUNNER` (temporary error) |
| Only serverless API tasks may use the Command Center's virtual runner | `CR.BAD_INPUT` |
| A callback URL is `http(s)://` | `CR.BAD_INPUT` |
| The agent has a live production version and is not deleted | `CR.NO_PRODUCTION`, `CR.DELETED` |
| No switched-off toolkit is used | `CR.TOOLKIT_DISABLED` |
| A headless run is not of an agent that requires the desktop | `CR.BAD_INPUT` |
| The runner exists and is enabled | `CR.DEVICE_DISABLED` |
| The licence allows the run on that runner ([document 13](13-licensing-and-editions.md)) | `CR.LICENSE_REQUIRED`, `CR.LICENSE_EXPIRED` |
| Every input is a declared input; Credential inputs are vault references | `CR.BAD_INPUT` |

The start is recorded in the One Audit Trail (`run.start`) and the runner is woken at once.

## 3. Run states and transitions

| State | Meaning | Shown in |
| --- | --- | --- |
| `queued` | Waiting on its runner | Agent Runs > Live |
| `running` | Claimed by the runner and executing | Agent Runs > Live, with progress |
| `running` + pause requested | Shown as **Paused**; the run halts before its next step | Live |
| `running` + stop requested | Shown as **Stopping** | Live |
| `success` | Ended normally (including Stop agent with status success) | History |
| `failed` | Ended with an error, or failed before it could start | History |
| `stopped` | Stopped by a person (or a cancelled workflow) | History |

Transitions:

- `queued` → `running` when the runner claims it. At that moment the licence is checked again; required files, Prompt
  Skills and custom toolkits are gathered; the runner's toolkits are compared with what the agent and its children use.
- `queued` → `stopped` when someone stops it before it starts (error `RUN.CANCELLED`, "Stopped by NAME before it started").
- `queued` → `failed` when a check at claim time fails (licence, files, skills, toolkits, screen).
- `running` → `success` / `failed` / `stopped` when the runner reports the result. A result for a run that is no longer
  running is ignored, so a result sent twice is harmless.
- `running` → `failed` with `AGENT.LOST` when the runner has not reported for 2 minutes.

When a run ends: its AI calls are recorded, a work item it processed is updated, KPIs are recorded (success only), a
test case is judged, a waiting workflow continues, and the callback URL (if any) receives `{run_id, status, outputs,
error}` (3 attempts, 1 s and 2 s apart; each delivery is audited).

## 4. What ends a run with an error before or during the run

| Code | Cause |
| --- | --- |
| `CR.LICENSE_REQUIRED`, `CR.LICENSE_EXPIRED` | No seat for the runner, or the licence ended, when the run was claimed |
| `RUN.TOOLKIT_MISSING` | The runner lacks a toolkit the agent or a child uses (listed) |
| `RUN.TOOLKIT_LOAD` | A custom toolkit's checksum or signature did not verify on the runner |
| `RUN.NO_DESKTOP` | The agent uses the screen but the runner is locked or has no desktop |
| `RUN.NEEDS_PERSON` | An unattended run reached a message box, prompt or file picker with no timeout, or a form |
| `RUN.TIMEOUT` | The run passed its maximum duration (24 hours on a runner, 5 minutes serverless) |
| `STEP.TIMEOUT` | One action passed its time limit |
| `AGENT.LOST` | The runner stopped reporting for 2 minutes |
| `AGENT.STOPPED` | The agent ran Stop agent with status failed |
| `VAULT.DENIED`, `VAULT.NOT_FOUND` | A credential the run's user may not use, or that does not exist |
| a missing or forbidden repository file or Prompt Skill | Fails at claim time with the reason |

## 5. Controls: pause, resume, stop, rerun

| Control | Who | Behaviour |
| --- | --- | --- |
| Pause | `runs.stop` | The runner is woken and halts the run before its next step; children pause too |
| Resume | `runs.stop` | The run continues |
| Stop | `runs.stop` | A queued run becomes `stopped` at once; a running one is asked to stop and ends `stopped` after its current action; Finally blocks still run |
| Rerun | `runs.start` | Starts the current live production version again on the same runner with the same inputs (source `rerun`) |

Controls reach the runner within seconds: a runner waiting for work keeps one request open, which the Command Center
answers as soon as a run of that runner is queued, paused, resumed or stopped.

## 6. Priorities and the order of work on a runner

- A runner works on **one run at a time**; each run executes in its own process, so a crashing agent never stops the runner.
- Queued runs of a runner are taken in order of priority (higher first), then age.
- Priorities set by the product: manual and scheduled runs 0 (a manual run may set another value), workflow agent tasks
  3, API task calls and AI assistant runs 5. Work queue items have their own priority within the queue (document 05).
- A runner whose version is newer than the Command Center's gets no work until the Command Center is updated; the
  Runners list shows it.

## 7. Run-as users and deploying to several targets

- A run can be started **as** another enabled user. It then goes to that user's default runner unless a runner or pool
  is given, and the agent's vault credentials, people's own vault values, shared values and repository files are
  checked against that user. The Agent Runs lists show "requester as run-as user".
- Schedules and triggers can carry a run-as user; work queues run as the person who started processing.
- **Deploy** (`POST /api/runs` with `run_as_users` and/or `device_ids`) starts one run per target. A target that cannot
  run is reported with its error while the others start; the answer gives the number started and failed.
- The list of possible run-as users shows each enabled user with their default runner and its state (online, offline,
  disabled). It needs `devices.view`.

## 8. Run modes: attended, unattended, headless

| Mode | Meaning | Dialogs and forms | Desktop |
| --- | --- | --- | --- |
| Unattended (default) | Nobody is at the runner | A message box, prompt or file picker without a timeout fails at once with `RUN.NEEDS_PERSON`; one with a timeout shows and closes by itself. Forms fail. AI tool approvals are refused. | Required for agents that use the screen; after the run the runner may lock or sign out |
| Attended | Someone is at the runner | Wait for the person as usual | Required for screen agents |
| Headless | No desktop at all | As unattended | None: agents that require the desktop are refused at start; browsers open without a window |

## 9. API tasks and serverless API tasks

An API task (Build > API tasks, `apitasks.manage`) puts an agent behind its own address and token.

| Property | Rule |
| --- | --- |
| Name | 1-120 characters |
| Agent | A `.flow.json` agent; its live production version runs |
| Where | A runner or a runner pool (required), or the Command Center (serverless) |
| Runs as | The person who made the task; refused if that person is disabled |
| Time limit | 1-300 seconds (default 60): the call waits that long for the result |
| Token | Shown once, stored as a hash, works only for its task, replaceable (the old one stops at once) |
| Switch | A task can be switched off |
| Call | `POST /api/apitasks/{id}/run` with header `X-OOA-Task-Token` and `{"inputs": {...}}` |
| Answer | 200 with `{status, run_id, outputs, error}` when the run finished in time; 202 with the run id otherwise (also at once when 8 calls are already waiting on that Command Center node); then `GET /api/apitasks/{id}/runs/{run_id}` with the same token |

**Serverless API tasks** (tick **Run on the Command Center**) run inside the Command Center on the virtual runner
"Command Center (serverless)", made with the first such task and usable only by serverless API tasks.

| Rule | Detail |
| --- | --- |
| Allowed toolkits | core, text, numbers, booleans, dates, lists, dictionaries, tables, JSON, CSV, XML, log, crypto, fuzzy, currency, shared values, PGP, REST, SOAP, generative AI, Prompt Skills, Knowledge Q&A, work queues, PDF, Excel, Word, PowerPoint, e-mail, Microsoft 365, Google, Salesforce, ServiceNow, Jira, Slack, Workday, GitHub, Azure DevOps, Asana, Zendesk, Okta, Box, DocuSign, Snowflake, Databricks, Kafka, custom connectors |
| Refused | Any other toolkit (desktop, browser, programs, scripts, databases, server files, custom toolkits) in the agent or any agent it calls: `CR.NOT_SERVERLESS` |
| Files | Every path stays inside the run's own temporary workspace (`FILE.OUTSIDE_WORKSPACE`), removed afterwards |
| Concurrency | At most 4 serverless runs at once per Command Center node; more wait queued |
| Duration | At most 5 minutes |
| Licence | The virtual runner needs an Unattended Runner seat while a licence is installed |
| Node stops | A run cut off by a stopped node is failed once it is past the longest time a run may take |

## 10. Runners: registration and identity

- **Register a runner** (Runners, `devices.manage`) makes a one-time key `ooareg_…`, valid 1 to 168 hours (24 by
  default in the console). A wrong, expired or used key is refused with `CR.BAD_REGISTRATION_KEY`.
- Tick **Temporary runner** to make a key for temporary runners (section 14).
- Registering stores the runner's name, host name, operating system and version, and gives it its own secret. The key
  then shows which runner used it.
- `ooa server` registers a runner for its own computer by itself, with a one-hour key nobody sees.
- A runner can be pointed at a Command Center that moved (`ooa agent move`) and keeps its registration, provided the
  new address accepts its credentials.

## 11. Online, offline and disabled runners

| State | Rule |
| --- | --- |
| Online | Enabled and heard from within the last 60 seconds (runners send a heartbeat every 10 seconds) |
| Offline | Enabled but not heard from for 60 seconds |
| Disabled | Switched off by an administrator; runs cannot be started on it (`CR.DEVICE_DISABLED`) |

Runs already queued on an offline runner wait for it. A running run whose runner is silent for 2 minutes fails
(`AGENT.LOST`).

## 12. Runner pools

- A pool (Runners > Runner pools, `pools.manage`) is a named group of runners.
- A run, schedule, test, API task or workflow stage sent to a pool goes to its **least busy online, enabled runner**
  (fewest queued and running runs) at the moment it starts. With none online the start fails with `CR.NO_RUNNER`.
- Work queues process items only on **idle** pool runners (nothing queued or running).
- A pool still used by a work queue cannot be deleted.

## 13. Default runners, single-user and multi-user runners

- Each user can have one **default runner** (Users > Default runner, `users.manage`). Runs as that user, schedules with a
  run-as user and no runner, and the notification-area assistant use it.
- A runner is **single-user** (the default runner of at most one person) or **multi-user** (of several; the default for
  runners nobody set). Making a runner single-user while several people use it as default is refused, and so is giving
  a single-user runner to a second person (`CR.CONFLICT`).
- A licence holder with an Attended Runner, Developer or Citizen Developer seat may run agents by hand on their own
  default runner without an Unattended Runner seat on it ([document 13](13-licensing-and-editions.md)).

## 14. Temporary runners

Runners registered with a temporary key (pooled virtual desktops, short-lived machines) are removed by the Command
Center once they have been offline for `temporary_runner_hours` (Administration > Settings, 1-720, default 8). Removal
takes them off pools and default runners and frees their licence seat; it is recorded as `device.expired`. A temporary
runner with queued or running work, or used by a schedule or trigger, stays and is checked again later.

## 15. The screen state and after-run actions

Each heartbeat reports the runner's screen:

| Screen | Meaning |
| --- | --- |
| Unlocked | Someone is signed in and the desktop takes input |
| Locked | The lock screen (or a system prompt) has the desktop |
| No desktop | A Windows service, or a machine without a display |

A run of an agent that uses the screen (keyboard, mouse, windows, images, desktop dialogs) does not start on a locked
runner or one without a desktop: it fails at once with `RUN.NO_DESKTOP` and the reason. Headless runs and agents that
need no screen run anywhere. Signing a user in on a locked runner is not done by the product.

**After unattended runs** (Runners > Update runners): `nothing` (default), `lock` (lock the screen) or `sign_out` (sign
the user out). The runner picks the setting up with its next heartbeat and applies it after each unattended run.

## 16. Runner settings, secrets, toolkits and updates

**Update runners** (`devices.manage`) changes ticked runners at once:

| Setting | Values | Takes effect |
| --- | --- | --- |
| Log level | error, warning, info (default), debug | Next heartbeat |
| After unattended runs | nothing, lock, sign out | Next heartbeat |
| Runner type | single-user, multi-user | At once |

- **Runner secrets** are replaced automatically every `runner_secret_days` (0-365, default 30; 0 turns it off). The old
  secret keeps working for a short while in case saving the new one is interrupted.
- **Toolkits on the runner**: each runner reports its toolkit ids and versions; runs needing one it lacks fail with
  `RUN.TOOLKIT_MISSING`.
- **Updates**: the Runners list shows whether each runner has the Command Center's program files ("Up to date" or
  "Update available"). **Update** on a runner, or the setting `runner_auto_update`, tells an idle runner to download
  the files it lacks, check every SHA-256, swap its program folder and restart. The bundled Java is not updated this
  way; a development build offers nothing.

## 17. Removing a runner

A runner can be removed when nothing is queued or running on it (`CR.CONFLICT` otherwise) and no schedule or trigger
uses it (`CR.IN_USE`, listing them). Removal takes it off pools and default runners and frees its licence seat.

## 18. Permissions

| Action | Permission |
| --- | --- |
| See runs, run details and logs | `runs.view` |
| Start runs, rerun, deploy | `runs.start` (plus folder access run) |
| Pause, resume, stop | `runs.stop` |
| See runners and run-as users | `devices.view` |
| Register, enable, disable, update, remove runners | `devices.manage` |
| Manage pools | `pools.manage` |
| Set default runners | `users.manage` |
| Manage API tasks | `apitasks.manage` |

## Related documents

- [04 Schedules and triggers](04-schedules-and-triggers.md)
- [05 Work queues](05-work-queues.md)
- [11 Operations, audit and dashboards](11-operations-audit-and-dashboards.md)
- [13 Licensing and editions](13-licensing-and-editions.md)
- [Command Center and runner, sections 3 and 5](../CONTROL_ROOM_AND_AGENT.md)
