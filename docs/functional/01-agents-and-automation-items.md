# 01 Agents and automation items

## What this covers

The kinds of automation item that ONEOFFICEAUTOMATION stores and runs, what each one is for, and the rules that apply
to their content: variables and data types, inputs and outputs, built-in values, child agents, the requirements an
agent declares, event handling inside an agent, and how versions relate to the items. Running, scheduling and the
repository are covered in later documents.

## Contents

1. Kinds of automation item
2. The agent document
3. Variables and data types
4. Inputs and outputs
5. Built-in values ($sys and $global)
6. The core actions (flow and control)
7. Child agents (Run Agent)
8. Requirements an agent declares
9. Event handling inside an agent
10. Versions of an item
11. Validation rules for agents

## 1. Kinds of automation item

Every item lives as a file in the designer's Studio workspace and, once checked in, as a versioned entry in the
Command Center's agent repository. The file name ending decides how the repository treats it.

| Item (Studio "Create" menu) | Stored as | Kind field | Purpose | Runs where |
| --- | --- | --- | --- | --- |
| Agent | `Name.flow.json` | `TaskBot` | Automates applications, files, the desktop and the web | A runner (attended, unattended or headless) |
| Agent from a blueprint | `Name.flow.json` | `TaskBot` | A new agent started from a ready-made pattern | As an agent |
| Headless API agent | `Name.flow.json` | `ApiTask` | Automation that needs no desktop; may not use actions that need one | A runner, or the Command Center as a serverless API task |
| AI agent definition | `Name.flow.json` | `Agent` | A flow with an `agent` section instead of steps, used by the AI agent action | Called by other agents |
| Form | `Name.form.json` | `Form` | Fields people fill in: workflow start forms and human tasks | Inside a workflow (the Task inbox) |
| Workflow | `Name.process.json` | `Process` | Chains agents, forms, approvals, decisions and notifications | The Command Center workflow engine |
| Document intelligence | workspace item | | Defines fields to extract from documents with AI | Used by the document actions of agents |
| Prompt Skill | workspace item, or kept in the Command Center | | A reviewed prompt template run by the Prompt Skills toolkit | Used by agents (see [document 09](09-ai-features.md)) |
| Python script, PowerShell script, VBScript, JavaScript | script file in the workspace | | A script run by the Scripts toolkit actions | Inside an agent step |

Rules that follow from the file kind:

- Only `.flow.json` items can be run, scheduled, triggered, queued or put behind an API task. Starting a run of
  anything else is refused with `CR.NOT_AN_AGENT` ("Start workflows from One Workflow; forms run inside workflows").
- Workflows and forms are versioned, checked out and given a live production version like agents, but they are
  validated by the workflow rules ([document 06](06-workflows-and-human-tasks.md)), not by the agent validator.
- A workflow's or form's repository id is made from its path (`workflow:Folder/Name`, `form:Folder/Name`). An agent's
  id is the `id` field in the file and must be unique in the repository (`CR.DUPLICATE_ID` otherwise).
- Repository files that are not automation items (templates, workbooks, lists, pictures) are stored next to agents
  ([document 08](08-repository-versions-and-templates.md)).

## 2. The agent document

An agent is one JSON document (format "OOA Flow v1"). The parts that carry behaviour:

| Part | Meaning |
| --- | --- |
| `id`, `name`, `description` | Identity. The id is what other agents call; the description is read by AI agents that decide when to call it. Both id and name are required. |
| `kind` | `TaskBot`, `ApiTask`, `Process`, `Form` or `Agent`. |
| `variables` | Declared variables (section 3). |
| `steps` | The ordered list of steps; block steps hold further lists (`children`, `else`, `elseIf`, `catch`, `finally`). |
| `requires` | Toolkit version ranges, capabilities (for example `desktop`) and repository files (section 8). |
| `errorHandling` | Agent-wide default retry policy for action steps that have none of their own ([document 02](02-toolkits-and-actions.md)). |
| `ui` | Designer-only data such as breakpoints; it has no effect on runs. |

Each step has a unique `id`, an `action` address `toolkit/action` (for example `ooa.excel/open`), `args` (the input
values), `out` (which outputs go into which variables), and optionally `description` (the note shown on the step
card), `disabled`, `continueOnError`, `retry`, `timeoutSeconds` and `connection`.

## 3. Variables and data types

Every value an agent keeps must be in a declared variable. A variable has a name, a type, a use, an optional default
value and an optional description.

### Types

| Type | Holds |
| --- | --- |
| String | Text |
| Number | A decimal number (whole or with decimals) |
| Boolean | True or false |
| DateTime | A date and time |
| List | An ordered list of values |
| Dictionary | Keys with values |
| Table | Rows and named columns |
| Record | One row of named values (handled as a dictionary) |
| File | A file path |
| Window | A window choice (title, source, browser) used by desktop and web steps |
| UIElement | A captured element of a screen |
| Credential | A secret taken from the vault at run time; never shown in logs, debuggers or run results |
| Any | A value of any type |

### Use (direction)

| Use in Studio | Stored direction | Meaning |
| --- | --- | --- |
| Use as input | `input` | Can be given when the agent starts |
| Use as output | `output` | Returned when the agent ends |
| Both ticked | `in_out` | Given at the start and returned at the end |
| Neither | (none) | Internal to the agent |

### Rules

- Names must be unique within the agent. `sys` and `global` are reserved.
- A Credential variable may not have a default value; a vault reference is supplied at run time.
- A variable is created with its default value, or empty. A step can only store an output in a declared variable,
  and a declared variable of a mismatched type refuses the value (`VAR.TYPE_MISMATCH`).
- A string, number or boolean output can be marked **Business figure (KPI)**; each successful run then records its
  value for dashboards ([document 11](11-operations-audit-and-dashboards.md)).
- In text fields, `${name}` inserts a value into text; a bare `$name` inside other text is kept literally. A field that
  holds only `$name` (or `$row.Name`, `$row["First Name"]`) passes the value with its own type.

## 4. Inputs and outputs

- A run may only supply declared inputs (`input` or `in_out`). An unknown input name is refused with `CR.BAD_INPUT`,
  listing the agent's inputs.
- A Credential input must be given as a vault reference `{"$credential": "folder/credential/attribute"}`; a plain
  value is refused. The runner fetches the secret just in time, as the run's user ([document 07](07-vault-credentials-and-connections.md)).
- When a run ends, the values of all `output` and `in_out` variables are returned as the run's outputs. They are shown
  in the run details, returned to API callers and callback URLs, kept with work items and merged into workflow data.
- Inputs that the Command Center fills by itself, when the agent declares them:

| Input name | Filled by |
| --- | --- |
| `work_item`, `work_item_id` | Work queue processing ([document 05](05-work-queues.md)) |
| `trigger_event` and, by kind, `trigger_file`, `trigger_window`, `trigger_process`, `trigger_service`, `trigger_hotkey`, `trigger_email`, `trigger_record`, `trigger_item`, `payload` | Triggers ([document 04](04-schedules-and-triggers.md)) |

## 5. Built-in values ($sys and $global)

`$sys.*` values are read at the moment of use, so dates and times are always current:

| Group | Values |
| --- | --- |
| Date and time | `date`, `time` (local), `now`, `today` (UTC), `year`, `month`, `day`, `weekday`, `hour`, `minute`, `second`, `millisecond`, `day_of_year`, `timestamp` |
| Machine | `machine`, `os`, `os_version`, `processors`, `cpu_usage`, `total_ram_mb`, `ram_usage_mb`, `user`, `user_domain`, `home_folder`, `temp_folder`, `file_separator` |
| Run | `run_id`, `bot_id`, `bot_name`, `principal` (the person the run is for), `workspace` |
| Text | `newline`, `tab`, `empty` |

`$global.NAME` reads a shared value from the Command Center (or, in Studio, the workspace's `global-values.json`). A
person's own value wins for shared values that people may change for themselves ([document 07](07-vault-credentials-and-connections.md)).

## 6. The core actions (flow and control)

The core toolkit is always present and cannot be switched off.

| Action | Name | Behaviour |
| --- | --- | --- |
| `core/if` | If | Runs its children when the condition holds; otherwise the first matching Else if branch, then Else |
| `core/loop` | Loop | Iterators `times`, `while`, `list`, `dictionary`, `dictionary_keys`, `dictionary_values`, `table_rows`, or a toolkit iterator; options for item and index variables, columns to variables, from/to index, step, reverse, check at end (while), a label, and `max_iterations` (default 100,000) |
| `core/try` | Try | Runs its children; on an error runs the first Catch whose codes match, then always Finally |
| `core/step` | Step | A named group of steps; no effect on the run |
| `core/parallel` | Parallel | Runs each child as a concurrent branch (`max_concurrency` 1-64, default 4) and waits for all; the first branch error fails the block |
| `core/set` | Assign variable | Sets a declared variable to a value or a calculation |
| `core/log` | Log message | Writes to the run log at debug, info, warning or error |
| `core/delay` | Delay | Waits a fixed time, or a random time within a range, in seconds, milliseconds or minutes |
| `core/wait_until` | Wait for condition | Checks a condition every `poll_seconds` (default 1) until true or `timeout_seconds` (default 30); fails with `WAIT.TIMEOUT` unless told not to |
| `core/throw` | Throw error | Raises an error with a code (for example `BUSINESS.INVOICE_DUPLICATE`), message, suggested fix, details, and whether it is temporary (retryable) |
| `core/comment` | Comment | A note; does nothing |
| `core/break`, `core/continue` | Exit loop, Continue loop | Leave or skip to the next pass of the innermost loop, or of the loop with the given label |
| `core/stop` | Stop agent | Ends the current agent at once with status success, or failed with `AGENT.STOPPED`; Finally blocks still run |
| `core/pause` | Pause agent | Pauses the run until resumed from Studio or the Command Center, or until the optional timeout |
| `core/run_bot` | Run Agent | Runs a child agent (section 7) |

A disabled step is skipped and its variables keep their values.

## 7. Child agents (Run Agent)

| Option | Default | Meaning |
| --- | --- | --- |
| `automation` | required | The id of the child agent |
| `version` | `production` | A version number, or the live production version |
| `inputs` | none | Values for the child's input variables |
| `output_map` | none | Child output name to the variable of this agent that receives it; a missing output fails with `CHILD.OUTPUT_MISSING` |
| `timeout_seconds` | no limit | One child run longer than this fails with `CHILD.TIMEOUT` |
| `same_session` | false | The child shares this agent's open sessions (workbooks, browsers, connections); sessions it opens stay open for the parent |
| `fail_on_error` | true | When off, a failed child does not fail the step; the step's `status` output is `failed` and `error` holds the child's error |
| `repeat`, `repeat_delay_seconds` | 1, 0 | Run the child several times in a row with a wait between runs |

Rules:

- The step returns `outputs`, `status` (success or failed), `error` and `runs`.
- Child agents run inside the parent's run, with their own variable scope (only inputs and outputs cross over). Stop and
  pause apply to the children too; stopping a child stops the parent.
- Nesting is limited to 10 levels (`CHILD.DEPTH`).
- When a run is sent to a runner, the Command Center adds the live production versions of every agent it calls
  (Run Agent steps and the tools of AI agent steps), followed transitively up to 10 levels and 100 agents. A child
  without a live production version is left out, and the runner reports it when the step is reached.
- A deleted agent cannot be used as a child (`CR.DELETED`).

## 8. Requirements an agent declares

| `requires` entry | Effect |
| --- | --- |
| `packages` | A version range per toolkit (for example `^2.0.0`). The newest installed version that fits is used; without a range, the newest version. |
| `capabilities` | `desktop` marks an agent that needs a desktop: it cannot run headless (`CR.BAD_INPUT`), and an `ApiTask` may not declare it. |
| `files` | Repository files the run needs, placed at the same path in the run's workspace before it starts (up to 50 MB per run). A missing file, or one in a folder the run's person may not view, fails the run before it starts. |

Toolkits the runner lacks fail the run at once with `RUN.TOOLKIT_MISSING`; a toolkit an administrator switched off
refuses check-in and runs with `CR.TOOLKIT_DISABLED`.

## 9. Event handling inside an agent

Besides the Command Center's triggers ([document 04](04-schedules-and-triggers.md)), an agent can react to events while it runs:

- **Trigger loop** (`ooa.triggerloop` iterators used in a Loop): the loop waits for events (for example files or
  e-mails) and runs its body once per event while the agent is running. The range options of the loop are applied as
  events arrive, so an endless trigger loop is limited by `max_iterations`, a Break, or the run's time.
- **Interrupt rules** (`ooa.interrupts`): when a window or element appears, the rule clicks, presses keys, closes the
  window or runs a child agent. Desktop, window and browser actions check the rules before acting and when an element
  is missing.
- **Self-healing**: browser element actions and window lookups try the alternatives recorded with an element, in order,
  when the selector or title finds nothing, and log which one worked.

## 10. Versions of an item

- The Studio workspace holds one working copy of each item. Saving in Studio does not create a version.
- **Agent check-in** stores a new numbered version (1, 2, 3 …) in the repository with a comment, author, time and
  SHA-256 checksum. Versions are never changed or removed by later check-ins.
- One version can be the **live production version**. Runs, schedules, triggers, work queues, API tasks and workflows
  always use it; an item without one cannot be run (`CR.NO_PRODUCTION`). A check-in sets it only when asked to
  (Studio: the tick **Make this the live version**; an agent's first Studio check-in always does, and publishing a
  workflow or form always does).
- A version whose stored content no longer matches its checksum is never sent to a runner or editor (`CR.AGENT_TAMPERED`).
- Labels, approval, check-out and lifecycle are in [document 08](08-repository-versions-and-templates.md).

## 11. Validation rules for agents

Validation runs in Studio while editing and again at check-in and before a run. All problems are returned at once,
each linked to a step id, with a suggested fix. The rules:

| Rule | Problem reported |
| --- | --- |
| Structure | Unknown `kind`; missing `id` or `name`; missing `steps` (or `agent` section for kind Agent) |
| Variables | Duplicate names; reserved names; unknown types; a Credential with a default |
| Steps | Duplicate step ids; an action address not in `toolkit/action` form; a toolkit or action that does not exist |
| References | A `$name` that is not declared; an output stored in an undeclared or built-in variable; undeclared loop, catch or Assign variables |
| Blocks | Children on a step that is not If, Loop, Try, Step or Parallel; Catch or Finally outside Try; Else outside If |
| Inputs | Unknown inputs; required inputs missing (unless the step uses a saved connection); fixed values of the wrong type, outside the allowed list, not matching the pattern, or outside the allowed range |
| Outputs | An output name the action does not have |
| Headless API agents | A step that needs a desktop |

At check-in, the repository's code analysis policy is applied as well ([document 08](08-repository-versions-and-templates.md)).

## Related documents

- [02 Toolkits and actions](02-toolkits-and-actions.md)
- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [08 Repository, versions and templates](08-repository-versions-and-templates.md)
- [14 One Agent Studio](14-studio-features.md)
- [Command catalogue](../COMMAND_CATALOG.md)
- [Architecture](../architecture/README.md)
