# Run context

Toolkit `ooa.run_context`, version 1.0.0. Information about the current run: run id, runner, user and workspace.

[All toolkits](README.md)

## Contents

- **Run**: Get run information, Get one run value

## Run

### Get run information

`ooa.run_context/get` (action)

Return the run id, tenant, runner name, operating system user and working folder.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | run_id, step_id, tenant, device, os_user, working_folder, os_name, os_version, java_version, processors, temp_folder. |

*Time limit 60 s; safe to repeat.*

### Get one run value

`ooa.run_context/get_value` (action)

Return a single item of the run information by name, e.g. run_id or device.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Which value to return. One of: `run_id`, `step_id`, `tenant`, `device`, `os_user`, `working_folder`, `os_name`, `os_version`, `java_version`, `processors`, `temp_folder` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Text | The value as text. |

*Time limit 60 s; safe to repeat.*
