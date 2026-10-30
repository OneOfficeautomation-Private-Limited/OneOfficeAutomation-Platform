# Shared Global values

Toolkit `ooa.global_values`, version 1.0.0. Read shared values that many agents use, such as folders, thresholds or URLs.

[All toolkits](README.md)

## Contents

- **Shared Global values**: Get shared value, Get all shared values
- **Conditions**: Shared value is defined

## Shared Global values

### Get shared value

`ooa.global_values/get` (action)

Read one shared value by name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Shared value name. |
| Default | `default` | Any | No |  | Main; tick Use a fallback when it is not defined | Value when the name is not defined; without it a missing name is an error. |
| Type | `type` | Text | No | `Any` | More options | Convert the value to this type; fails with GLOBALS.WRONG_TYPE when it cannot be converted. One of: `Any`, `String`, `Number`, `Boolean`, `DateTime`, `List`, `Dictionary` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | The value. |

*Time limit 60 s; safe to repeat.*

### Get all shared values

`ooa.global_values/get_all` (action)

Read every shared value as a dictionary.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Prefix | `prefix` | Text | No |  | Main | Return only values whose name starts with this text, e.g. finance_. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | Name to value. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Shared value is defined

`ooa.global_values/is_defined` (condition)

True when a shared value with this name exists.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Shared value name. |
