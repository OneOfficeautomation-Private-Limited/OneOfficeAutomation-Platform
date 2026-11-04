# Windows services

Toolkit `ooa.services`, version 1.1.0. Get the status of, start, stop, pause, resume and restart Windows services on this or another computer.

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
| Computer | `computer` | Text | No |  | Main; tick Service on another computer | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main; tick Service on another computer | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main; tick Service on another computer | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |

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
| Computer | `computer` | Text | No |  | Main; tick Service on another computer | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main; tick Service on another computer | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main; tick Service on another computer | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |
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
| Computer | `computer` | Text | No |  | Main; tick Service on another computer | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main; tick Service on another computer | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main; tick Service on another computer | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait for each phase. Range: 1 to  |

*Time limit 360 s.*

### Resume service

`ooa.services/resume` (action)

Continue a paused service and wait until it is running again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Service name. |
| Computer | `computer` | Text | No |  | Main; tick Service on another computer | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main; tick Service on another computer | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main; tick Service on another computer | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |
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
| Computer | `computer` | Text | No |  | Main; tick Service on another computer | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main; tick Service on another computer | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main; tick Service on another computer | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |
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
| Computer | `computer` | Text | No |  | Main; tick Service on another computer | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main; tick Service on another computer | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main; tick Service on another computer | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |
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
| Computer | `computer` | Text | No |  | Main | Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule. Example: `FILESRV01` |
| User | `user` | Text | No |  | Main | Windows user (DOMAIN\\user) for the other computer; the runner's account when empty. Shown when: `{"computer": ["!", ""]}` |
| Password | `password` | Credential (vault) | No |  | Main | Password of user, from the vault. Shown when: `{"computer": ["!", ""]}` |
