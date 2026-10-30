# 06 Workflows and human tasks

## What this covers

Workflow chains agents, people and decisions into one long-running process. This document specifies the
workflow definition and its stages, start forms and references, the workflow run and its states, how data flows
between stages, the Task inbox (assignment, taking and giving back tasks, completing forms and approvals), due times,
notifications by inbox and e-mail, cancellation, and the permissions involved.

## Contents

1. Workflows and forms as repository items
2. Stages
3. Validation of a workflow
4. Starting a workflow
5. Workflow run states
6. How the engine moves a run
7. Workflow data
8. Human tasks and the Task inbox
9. Forms and their checks
10. Approvals
11. Due times
12. Notifications
13. Cancelling a workflow run
14. Permissions and audit

## 1. Workflows and forms as repository items

- A workflow is a `Name.process.json` item and a form a `Name.form.json` item, designed in One AgentStudio (Create >
  Workflow, Create > Form) and published with **Publish to Command Center**, which checks them in as the live
  production version.
- Like agents they have versions, check-out and a live production version. A workflow run uses the live version of the
  workflow at its start and keeps that version to the end; forms and agents are read at their live versions when a
  stage reaches them.
- A workflow names agents and forms by path. Moving or renaming an agent or folder that a workflow names is refused
  until the workflow is changed ([document 08](08-repository-versions-and-templates.md)).

## 2. Stages

A workflow is an ordered list of stages, each with an id, a name and a type.

| Stage (type) | Settings | What it does |
| --- | --- | --- |
| Agent task (`bot`) | Agent path (`.flow.json`), inputs (values or `$name` from the workflow data), optional runner pool | Starts the agent's live version as the person who started the workflow, at priority 3, on the pool or on the least busy online runner. The workflow waits for the run. |
| Human task (`form`) | Form path (`.form.json`), assignee, optional description, optional due time in hours | Puts the form in the Task inbox of the assignee and waits for it |
| Approval (`approval`) | Assignee, optional description, due time, what a rejection does (`on_reject`) | Asks a person to approve or reject, and waits |
| Decision (`decision`) | Condition (an expression such as `$amount > 1000`), target if true, target if false | Evaluates the condition and goes to one stage or another |
| Notification (`notify`) | Recipients (`to`), message, optional subject | Sends an inbox notice and/or an e-mail, then moves on without waiting |

**Next stage.** After a stage the run goes to the stage named in its `next`, else to the following stage in the list,
else it ends. `end` as a target ends the run. A decision without a target for its outcome continues with the following stage.

## 3. Validation of a workflow

Checked at check-in and publish; problems block it:

| Rule |
| --- |
| The workflow has at least one stage; every stage is an object with a known type |
| An agent task names a `.flow.json` agent |
| A human task names a `.form.json` form |
| A human task or approval has an assignee |
| A decision has a condition that parses as an expression |
| A notification has a recipient |
| `next`, `if_true`, `if_false` and `on_reject` point to an existing stage, `end` (or, for `on_reject`, `continue`) |
| A start form, when given, is a `.form.json` |

A form must have a `fields` list.

## 4. Starting a workflow

- From **Workflow** (or Start in the agent repository), or `POST` to the workflow API, with optional starting data
  (for example `{"amount": 1200}`).
- **Start form**: a workflow can name a start form. Its fields are asked when the workflow starts, and data from the API
  is checked against them like a submitted form. `GET /api/workflows/form?path=` returns the start form.
- **Reference**: a start can carry a reference of up to 200 characters (a ticket or order number). Runs can be found by
  reference (`GET /api/workflows/runs?reference=…`); the newest run with that reference is returned.
- A workflow without stages, or without a live version, cannot start (`WF.EMPTY`, `CR.NO_PRODUCTION`).

## 5. Workflow run states

| State | Meaning |
| --- | --- |
| `running` | The engine is moving the run, or an agent task waits for a runner to come online |
| `waiting` | Waiting for an agent run, a human task or an approval |
| `completed` | Reached the end (including a rejection whose `on_reject` is `end`) |
| `failed` | An agent task failed or was stopped (`WF.AGENT_FAILED`, `WF.AGENT_STOPPED`), the starter was disabled (`WF.STARTER_DISABLED`), or a stage raised an error (`WF.STAGE_FAILED` or the stage's own code) |
| `cancelled` | Cancelled by a person (`WF.CANCELLED`) |

When a run ends (any final state), its open human tasks are set to `cancelled`; notifications stay.

## 6. How the engine moves a run

- The engine moves a run through stages until it must wait or ends (at most 200 stage moves in one go).
- **Agent task without an online runner** (and no pool): the run stays `running`, a timeline entry says "No runner is
  online; the agent starts when one is", and the clock tries again.
- **Agent task finished**: success merges the agent's outputs into the data and continues; failure or stop fails the workflow.
- **Human task or approval completed**: the answers or outcome are merged and the run continues.
- Every event is added to the run's **timeline** (history) with time, stage and details: `agent_started`,
  `agent_finished`, `task_created`, `task_completed`, `approved`, `rejected`, `decided`, `notified`, `waiting`,
  `failed`, `cancelled`.

## 7. Workflow data

- The data starts with the starting data (checked by the start form when there is one).
- A stage's result is kept under the stage id and its fields are also merged at the top level, so later stages use
  `$name` or `$stage_id.name`. A human task's answers are also available as `$form.field`.
- Approvals add `approved` (true or false), `approver` and, when given, `comment`.
- Agent task inputs and notification texts can use values from the data (`$name`, `${name}` in texts).
- Internal bookkeeping fields (starting with `_`) are never shown.

## 8. Human tasks and the Task inbox

| Task property | Meaning |
| --- | --- |
| Kind | `form` (human task), `approval`, or `notification` |
| Title | "Stage name — Workflow name" |
| Assignee | One or more of `user:NAME`, `role:ROLE`, `role:*` (everyone), separated by commas |
| Data | The workflow data at the time the task was made |
| Due | Optional due time |

Task states:

| State | Meaning |
| --- | --- |
| `open` | Waiting for any assignee |
| `claimed` | Taken by one person; only they (or an administrator) may act on it |
| `completed` | Done, with its outcome (`submitted`, `approved`, `rejected`, `done`) and who completed it |
| `cancelled` | Withdrawn because the workflow run ended |

Rules:

- The **Task inbox** shows a person the tasks assigned to them directly or through any of their roles, plus tasks they
  claimed or completed. People with `users.manage` can see all tasks and act on any.
- **Take** (claim) moves an open task to claimed; if someone else took it first, the action is refused ("Someone else
  took this task"). **Give back** returns it to open.
- A task can be completed while open or claimed by someone allowed to act; a completed or cancelled task cannot be
  completed again (`CR.CONFLICT`).
- Open tasks are listed by due time, then age (up to 500); completed ones newest first.
- The top bar shows the count of open tasks for the person (claimed ones count only for the person who took them).
- The notification-area assistant also counts open tasks and shows a notice for new ones.

## 9. Forms and their checks

Field types and the checks made when a form is submitted (also for start forms):

| Field type | Check |
| --- | --- |
| Text (and other text types) | Kept as text |
| Number | Must be a number; within min and max when given |
| Checkbox | True or false; empty means false; never "required" |
| Dropdown, Radio | Must be one of the options |
| Email | Must look like an e-mail address |
| Date | Must be `YYYY-MM-DD` |
| Heading, Paragraph | Display only |

A required field (other than a checkbox) that is empty is refused with "LABEL is required".

## 10. Approvals

- The outcome must be `approved` or `rejected`; a comment is optional.
- On rejection the stage's `on_reject` decides:

| `on_reject` | Effect |
| --- | --- |
| `end` (default) | The workflow ends; its state is `completed`, with the rejection in the data and timeline |
| `continue` | The workflow continues with the next stage as after an approval |
| a stage id | The workflow jumps to that stage |

## 11. Due times

A human task or approval can have a due time in hours (decimals allowed) from the moment it is made. An open or
claimed task past its due time is shown as **Overdue** in the inbox. A due time does not reassign, escalate or cancel
the task.

## 12. Notifications

- Recipients written as `user:` or `role:` get one inbox notice (kind notification), which they dismiss when read.
- Plain e-mail addresses get an e-mail through the Command Center's mail server (Administration > Settings > E-mail),
  with the subject given (or "Notification name — Workflow name"), as plain text. Texts may use `${name}` from the data.
- When e-mail is not set up, or sending fails, the addresses are recorded as not delivered in the timeline with the
  reason; the workflow still moves on.

## 13. Cancelling a workflow run

- The person who started a run may cancel it; others need `runs.stop`. A run that has already ended cannot be cancelled.
- Cancelling asks a running agent task to stop, stops its queued runs, withdraws open human tasks, and ends the run as
  `cancelled`.

## 14. Permissions and audit

| Action | Permission |
| --- | --- |
| Start a workflow, read its start form | `runs.start` |
| See workflow runs and their timelines | `runs.view` |
| Cancel someone else's workflow run | `runs.stop` |
| Act on a task | Being an assignee (or the person who claimed it); `users.manage` for any task |
| Publish workflows and forms | `repo.checkin` with folder access edit (manage to make them live) |

Agent tasks run as the starter, so the starter's folder access and vault permissions apply to them.

Audited: `workflow.start`, `workflow.completed`, `workflow.failed`, `workflow.cancelled`, `workflow.cancel`,
`task.claim`, `task.release`, `task.complete`.

## Related documents

- [01 Agents and automation items](01-agents-and-automation-items.md)
- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [11 Operations, audit and dashboards](11-operations-audit-and-dashboards.md) (e-mail settings)
- [14 One AgentStudio](14-studio-features.md)
- [Command Center and runner, section 7](../user-guide/00-README.md)
