# Environment locks

Toolkit `ooa.locks`, version 1.0.0. Named locks that one agent run holds at a time, so two runners never use the same single-user system together.

[All toolkits](README.md)

## Contents

- **Environment locks**: Acquire lock, Check lock, Release lock
- **Conditions**: Lock is held

## Environment locks

### Acquire lock

`ooa.locks/acquire` (action)

Take a named lock so no other agent run uses the same system at the same time. Optionally wait until the run holding it gives it back. The lock is given back by Release lock, or when this agent ends. With One CommandCenter the lock is shared by every runner; in One AgentStudio on its own it works on this computer only.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | The lock's name, e.g. SAP production login. Names are compared without case. |
| Wait (seconds) | `wait_seconds` | Number | No | `0` | Main | How long to wait for the lock when another run holds it; 0 tries once. Range: 0 to 86400 |
| Fail if not acquired | `fail_if_not_acquired` | Yes/No | No | No | Main; tick Fail when the lock is not acquired | Fail with LOCK.TIMEOUT when the lock is not acquired, instead of returning acquired false. |
| Lease (seconds) | `lease_seconds` | Number | No | `3600` | More options | How long the lock is held at most. After it, another run may take the lock. Acquire it again to renew the lease. Range: 5 to 604800 |
| Comment | `comment` | Text | No |  | More options | Why the lock is held, shown in One CommandCenter. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `acquired` | Yes/No | True when this run now holds the lock. |
| `held_by` | Text | When not acquired: the run, runner and person holding it, and since when. |

*Time limit 86520 s.*

### Check lock

`ooa.locks/check` (action)

See whether a lock is held, by which run, runner and person, and since when. A lock past its lease counts as free.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | The lock's name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `held` | Yes/No | True when a run holds the lock. |
| `mine` | Yes/No | True when this run holds it. |
| `held_by` | Text | The run, runner and person holding it, and since when; empty when free. |
| `since` | Text | When it was taken (ISO 8601), or empty. |
| `expires` | Text | When its lease runs out (ISO 8601), or empty. |

*Time limit 60 s; safe to repeat.*

### Release lock

`ooa.locks/release` (action)

Give back a lock this run holds, so another run can take it. A lock this run does not hold is left alone: released is false and the log says why.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | The lock's name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `released` | Yes/No | True when this run held the lock and gave it back. |

*Time limit 60 s.*

## Conditions

Used in If, Loop while and Wait until steps.

### Lock is held

`ooa.locks/is_held` (condition)

True when a run (this one or another) holds the lock now.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | The lock's name. |
