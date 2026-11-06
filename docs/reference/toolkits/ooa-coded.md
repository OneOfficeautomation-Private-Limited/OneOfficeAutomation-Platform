# Coded agents

Toolkit `ooa.coded`, version 1.0.0. Run an agent written in Python (main(inputs, ooa)) that calls toolkit commands through the run.

[All toolkits](README.md)

## Contents

- **Coded agents**: Run Python agent

## Coded agents

### Run Python agent

`ooa.coded/run_python_agent` (action)

Run main(inputs, ooa) of a Python module: it gets the inputs, calls toolkit commands through ooa.call(), and returns the outputs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Code = A file | The Python module, in the agent's files. Example: `agents/invoices.py`; Shown when: `{"code": [""]}` |
| Code | `code` | Text | No |  | Main; choice Code = Typed here | The module's code, typed here. Shown when: `{"file": [""]}` |
| Inputs | `inputs` | Dictionary | No |  | Main | What main gets as inputs (and as ooa.inputs). |
| Uses | `uses` | List | No |  | Main | The toolkits the code may call; any other is refused. Example: `["ooa.excel", "ooa.email"]` |
| Entry | `entry` | Text | No | `main` | More options | The function to run. |
| Interpreter | `interpreter` | Text | No |  | More options | Python program to use (a virtual environment's python); found on the PATH when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `outputs` | Dictionary | What main returned (a dictionary; anything else comes back as result). |
| `calls` | Number | How many toolkit commands the code called. |

*Time limit 3600 s; needs: process, filesystem.*
