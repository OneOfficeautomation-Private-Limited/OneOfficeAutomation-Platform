# Jobs and alerts

Toolkit `ooa.jobs`, version 1.0.0. See and stop other agent runs, and raise alerts to people, from an agent run by One CommandCenter.

[All toolkits](README.md)

## Contents

- **Jobs**: Get job, List jobs, Stop job
- **Alerts**: Raise alert

## Jobs

### Get job

`ooa.jobs/get_job` (action)

Read the state of another run: running, success, failed or stopped, when it started and ended, and its error. The run's user must be allowed to see runs of that agent.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Job ID | `job_id` | Text | Yes |  | Main | The run's id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `job` | Dictionary | id, bot_path (the agent), status, requested_by, run_as, device_name, created, started, finished, duration_ms, error_code, error_message. |
| `status` | Text | queued, running, success, failed or stopped. |

*Time limit 60 s; safe to repeat.*

### List jobs

`ooa.jobs/list_jobs` (action)

List runs the run's user may see: the active ones (queued or running), the finished ones, or all; of one agent or of every agent; newest first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Status | `status` | Text | No | `active` | Main | active: queued or running; history: finished; all. One of: `active`, `history`, `all` |
| Agent | `agent` | Text | No |  | Main | Only runs of this agent (its path in the repository); every agent when empty. Example: `Finance/Invoice intake.flow.json` |
| Limit | `limit` | Number | No | `50` | More options | At most this many runs. Range: 1 to 200 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `jobs` | List | One record per run, as Get job returns it. |
| `count` | Number | How many runs are listed. |

*Time limit 60 s; safe to repeat.*

### Stop job

`ooa.jobs/stop_job` (action)

Stop another run: a queued run is removed, a running one stops at its next step. Needs the run's user to be allowed to stop runs; an agent stops itself with Stop agent instead.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Job ID | `job_id` | Text | Yes |  | Main | The run's id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `stopped` | Yes/No | True when the run was queued or running and is being stopped; false when it had already ended. |
| `status` | Text | stopping, or the state it had already ended in. |

*Time limit 60 s.*

## Alerts

### Raise alert

`ooa.jobs/raise_alert` (action)

Raise an alert in Operational Insights > Alerts. It goes to the people, e-mail addresses and webhook of the alert rules of kind Raised by an agent (for every agent or this one); with no such rule it is only listed there. It stays open until someone acknowledges it. A run raises at most 20 alerts, and the same subject once.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | Yes |  | Main | What happened and what a person should do. Example: `Invoice 4711 from Acme is over the approval limit` |
| Severity | `severity` | Text | No | `warning` | Main | How serious it is; the alert's title says Critical for critical. One of: `warning`, `critical` |
| Subject | `subject` | Text | No |  | More options | What the alert is about; the same subject is raised once per run. Every alert is new when empty. Example: `invoice 4711` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `alerts` | Number | How many alerts were raised (one per rule that takes them; 0 when this subject was already raised by this run). |

*Time limit 60 s.*
