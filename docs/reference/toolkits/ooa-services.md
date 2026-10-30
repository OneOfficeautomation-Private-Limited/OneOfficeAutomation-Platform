# Windows services

Toolkit `ooa.services`, version 1.0.0. Get the status of, start, stop and restart Windows services.

[All toolkits](README.md)

## Contents

- **Services**: Get service status, Pause service, Restart service, Resume service, Start service, Stop service
- **Conditions**: Service is running

## Services

### Get service status

`ooa.services/get_status` (action)

Return RUNNING, STOPPED, PAUSED, START_PENDING or STOP_PENDING.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. Example: `Spooler` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | Service state. |

*Time limit 60 s; safe to repeat.*

### Pause service

`ooa.services/pause` (action)

Pause a running service that supports pausing and wait until it is paused.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until the service reaches the new state; false continues as soon as the request is sent. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait. Range: 1 to ; Shown when: `{"wait": ["true"]}` |

*Time limit 180 s.*

### Restart service

`ooa.services/restart` (action)

Stop then start a service.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait for each phase. Range: 1 to  |

*Time limit 360 s.*

### Resume service

`ooa.services/resume` (action)

Continue a paused service and wait until it is running again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until the service reaches the new state; false continues as soon as the request is sent. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait. Range: 1 to ; Shown when: `{"wait": ["true"]}` |

*Time limit 180 s.*

### Start service

`ooa.services/start` (action)

Start a service and wait until it is running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until the service reaches the new state; false continues as soon as the request is sent. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait. Range: 1 to ; Shown when: `{"wait": ["true"]}` |

*Time limit 180 s.*

### Stop service

`ooa.services/stop` (action)

Stop a service and wait until it has stopped.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until the service reaches the new state; false continues as soon as the request is sent. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait. Range: 1 to ; Shown when: `{"wait": ["true"]}` |

*Time limit 180 s.*

## Conditions

Used in If, Loop while and Wait until steps.

### Service is running

`ooa.services/is_running` (condition)

True when the service state is RUNNING.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
