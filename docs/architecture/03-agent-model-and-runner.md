# 03 Agent model and runner

## What this covers / who it is for

This document describes the agent definition format (the "Flow JSON") and how the Runner engine executes it: kinds of
agent, variables and types, steps and blocks, expressions, sessions, error handling and retries, child agents, the
events a run emits, the plan hash, the debugger hooks, "Run from here" with carried-over values, and the Python
reference runner with its conformance cases. It is for developers who work on the engine or on Studio, toolkit
authors who need to know how their actions are called, and anyone who generates agent JSON by program.

## Contents

1. [Where the format is defined](#1-where-the-format-is-defined)
2. [Top-level structure](#2-top-level-structure)
3. [Variables and types](#3-variables-and-types)
4. [Steps](#4-steps)
5. [Blocks and control flow](#5-blocks-and-control-flow)
6. [Values, expressions and interpolation](#6-values-expressions-and-interpolation)
7. [Conditions](#7-conditions)
8. [Sessions and saved connections](#8-sessions-and-saved-connections)
9. [Error handling and retries](#9-error-handling-and-retries)
10. [Child agents](#10-child-agents)
11. [Run lifecycle, events and result](#11-run-lifecycle-events-and-result)
12. [Determinism: the plan hash](#12-determinism-the-plan-hash)
13. [Debugger hooks](#13-debugger-hooks)
14. [Run from here and carried-over values](#14-run-from-here-and-carried-over-values)
15. [Where the engine runs](#15-where-the-engine-runs)
16. [Python reference runner and conformance](#16-python-reference-runner-and-conformance)
17. [Related documents](#related-documents)

---

## 1. Where the format is defined

| Artefact | Role |
| --- | --- |
| `contracts/flow-schema/flow.v1.json` | JSON Schema of an agent. Checked when `adapters-jackson` is on the class path (`NetworkntFlowSchemaValidator`). |
| `runner/.../FlowValidator.java` | Semantic checks: kinds, declared variables, known actions and inputs, capability rules. Always runs. |
| `runner/.../Runner.java` | The interpreter. |
| `runner/.../CorePackage.java` | Declares the `core/*` actions (manifest only; the engine implements them). |
| `contracts/conformance/cases/*.json` | Cases every runner implementation must pass identically. |

Validation failures raise `FLOW.INVALID` with the number of problems and the first one.

## 2. Top-level structure

```json
{
  "kind": "TaskBot",
  "id": "invoice_worker",
  "name": "Invoice worker",
  "description": "Posts one invoice",
  "version": "1.0.0",
  "tags": ["finance"],
  "requires": {
    "packages": {"ooa.excel": "^1.0.0"},
    "capabilities": ["desktop"],
    "files": ["Templates/invoice.xlsx"]
  },
  "variables": [ ... ],
  "steps": [ ... ],
  "errorHandling": {"default": "stop", "retry": {"max": 2, "backoff": "exponential", "delayMs": 500}},
  "ui": { ... }
}
```

| Field | Meaning |
| --- | --- |
| `kind` (required) | `TaskBot` (an ordinary agent), `ApiTask` (headless agent; may not require the `desktop` capability nor use a desktop action), `Process` (a workflow), `Form`, or `Agent` (an AI agent definition with an `agent` section instead of `steps`) |
| `id`, `name` (required) | Agent id (unique in the repository) and display name |
| `requires.packages` | Toolkit id to version range: `^1.2.0`, `~1.2.0`, `>=1.2.0` or an exact `1.2.0` (`PackageRegistry.satisfies`) |
| `requires.runtime` | Runner version range |
| `requires.capabilities` | `desktop`, `browser`, `network`, `filesystem`, `ai` |
| `requires.files` | Repository files the runner receives with the job, placed at the same path in its workspace |
| `errorHandling.default` | `stop` (default) or `continue` for failures outside any Try block (top level only) |
| `errorHandling.retry` | Default retry policy for every step that has none |
| `agent` | For `kind: Agent`: `instructions`, `goalTemplate`, `modelConnection`, `tools`, `maxSteps`, `maxCostUsd`, `temperature`, `outputSchema` |
| `ui` | Designer layout only; ignored by the engine and left out of the plan hash |

The Java Runner refuses `kind: Agent` with `RUN.WRONG_KIND`; AI agents in the Java platform are built with the
`ooa.ai_agent/run` action inside an ordinary agent (see [08-ai-architecture.md](08-ai-architecture.md)). The
`Agent` kind with its own runtime exists in the Python reference (`reference/ooa/agents/runtime.py`).

Other workspace item types sit beside agents: forms `*.form.json`, workflows `*.process.json`, document automations
`*.doc.json`, Prompt Skills `ai-skills/*.json`, scripts `*.py`, `*.ps1`, `*.vbs`, `*.js`, and custom connectors
`connectors/*.connector.json`.

## 3. Variables and types

```json
{"name": "work_item", "type": "Dictionary", "direction": "input", "required": true, "description": "The queue item"}
{"name": "total", "type": "Number", "direction": "output", "kpi": true}
{"name": "mode", "type": "String", "direction": "input", "enum": ["fast", "safe"], "default": "safe"}
```

| Property | Values |
| --- | --- |
| `type` | `String`, `Number`, `Boolean`, `DateTime`, `List`, `Dictionary`, `Table`, `Record`, `File`, `Window`, `UIElement`, `Credential`, `Any` (`OoaType`) |
| `direction` | `input` (supplied by the caller), `output` (returned), `in_out` (both), `local` (default) |
| `required` | For inputs: missing gives `RUN.INPUT_MISSING` |
| `default` | Starting value (deep-copied for each run) |
| `enum` | Allowed input values (`RUN.INPUT_INVALID` otherwise) |
| `kpi` | Output recorded as a business KPI after each successful run (see `Dashboards`) |

Rules enforced by the engine (`Runner.Scope`):

- Every variable must be declared; reading or writing an undeclared one fails with `VAR.UNDECLARED`.
- Values are converted to the declared type on every assignment (`Values.coerce`). Numbers are always `BigDecimal`
  (expressions use `MathContext.DECIMAL128`), so money arithmetic is exact.
- A Table is `{"columns": [...], "rows": [[...], ...]}`.
- Supplying an input that is not declared as `input` or `in_out` fails with `RUN.INPUT_UNKNOWN`.
- An input given as `{"$credential": "folder/credential/attribute"}` is resolved from the vault before the run starts
  and becomes a `SecureString`.
- `sys` and `global` are reserved names. `$sys.*` is computed at the moment of use (date, time, now, today, year …
  timestamp, os, os_version, machine, user, user_domain, home_folder, temp_folder, workspace, processors, CPU and RAM
  use, run_id, bot_id, bot_name, principal, newline, tab, empty). `$global.*` comes from the `global_values` service
  (Command Center shared values, with a person's own value where the value is changeable) or `global-values.json` in
  the workspace. Both are read-only (`VAR.READ_ONLY`).

## 4. Steps

```json
{"id": "s3", "action": "ooa.excel/read_range",
 "args": {"session": "excel", "range": "A1:D20"},
 "out": {"table": "rows"},
 "retry": {"max": 3, "backoff": "exponential", "delayMs": 1000, "codes": ["EXCEL.*"]},
 "timeoutSeconds": 120,
 "connection": "finance_sharepoint",
 "continueOnError": false,
 "disabled": false,
 "description": "Read the invoice lines"}
```

| Field | Meaning |
| --- | --- |
| `id` | Unique step id; used by events, breakpoints, Run from here and error reports |
| `action` | `<toolkit id>/<action id>`, for example `ooa.excel/open`, or `core/<action>` |
| `args` | Inputs (see section 6 for value forms) |
| `out` | Map from the action's output name to a variable name |
| `children`, `else`, `elseIf`, `catch`, `finally` | Nested step lists for blocks |
| `retry` | Step retry policy (overrides `errorHandling.retry`) |
| `timeoutSeconds` | Overrides the action's own timeout (`@Action(timeoutSeconds)`, default 60) |
| `connection` | Name of a saved connection that fills empty inputs (section 8) |
| `continueOnError` | Log a warning and go on when the step fails |
| `disabled` | Skipped entirely (no events) |

For each leaf step the engine: checks for cancel, pause and the run deadline; calls the debugger hook; notes the
value trail; emits `step.started`; resolves `args`; merges the saved connection; checks inputs against the manifest
(required, pattern, enum, min/max); calls the action on a worker thread with the timeout; stores outputs through
`out`; emits `step.finished` or `step.failed`.

## 5. Blocks and control flow

All control flow is expressed with `core/*` actions:

| Action | Behaviour |
| --- | --- |
| `core/if` | `args.condition`; `children` when true, then each `elseIf` entry `{condition, children}`, then `else` |
| `core/loop` | `args.iterator`: `times` (`count`, start and step), `while` (`condition`), `list`, `dictionary`, `dictionary_keys`, `dictionary_values`, `table_rows` (`over`), or a toolkit iterator `pkg/iterator` with `iterator_args`. Options: `from_index`, `to_index`, `step`, `reverse`, `label`, `max_iterations` (default 100,000) |
| `core/break`, `core/continue` | Leave or skip the current (or labelled) loop; outside a loop the run fails with `FLOW.BAD_BREAK` |
| `core/try` | `children`, then `catch` entries `{codes, as, message_to, line_to, screenshot, continueOnError, rethrow, children}`, then `finally` (always runs) |
| `core/parallel` | Each child is a branch run on a virtual thread; `max_concurrency` default 4; the first error is raised after all branches end |
| `core/step` | A named group of steps (for structure and folding) |
| `core/set` | Assign a value to a variable |
| `core/log` | Log a message at a level |
| `core/delay` | Wait `seconds` (optionally random up to `random_max`; unit seconds, milliseconds or minutes) |
| `core/wait_until` | Poll a condition (`timeout_seconds` default 30, `poll_seconds` default 1, `fail_on_timeout`); output `met` |
| `core/throw` | Raise an error with `code`, `message`, `suggested_fix`, `retryable`, `details` |
| `core/stop` | End the current agent, as success or as failure (`AGENT.STOPPED`); Finally blocks still run |
| `core/pause` | Pause the run from inside, as the Pause button does |
| `core/comment` | Nothing at run time |
| `core/run_bot` | Run a child agent (section 10) |

Toolkit iterators can be endless (for example the trigger loop), so range options are applied while reading.

## 6. Values, expressions and interpolation

`Expressions.resolve` turns each argument into a value:

| Form | Example | Result |
| --- | --- | --- |
| Whole reference | `"$invoice.lines[0].amount"` | The value with its type kept |
| Interpolated text | `"Dear ${customer.name}"` | Text; inside `${...}` a bare path means a variable |
| Expression object | `{"expr": "max($a, 3) * 2"}` | Evaluated result |
| Vault reference | `{"$credential": "finance/sap/password"}` | `SecureString` fetched just in time |
| Anything else | literal JSON | As written; maps and lists are resolved recursively |

The expression language is a small, sandboxed grammar implemented with a hand-written Pratt parser (`Expressions`):
literals, `$variables` with `.field` and `[index]` access, list literals, unary and binary operators (`+ - * / %`,
comparisons, `and`, `or`, `not`, `&&`, `||`, `!`) and calls to allow-listed functions only. There is no reflection
and no method call on objects. The functions (`Functions`) are:

`upper lower trim len concat contains startsWith endsWith replace substring split join toNumber toString toBoolean
round abs min max sum now today dateAdd dateDiff formatDate parseDate coalesce isEmpty keys values get regexMatch
regexExtract jsonParse jsonStringify if`

Date units are seconds, minutes, hours, days and weeks. Bad syntax raises `EXPR.INVALID`.

## 7. Conditions

A condition (used by `core/if`, `elseIf`, `while` loops and `wait_until`) may be:

- a Boolean, or a string evaluated as an expression;
- `{"all": [...]}`, `{"any": [...]}`, `{"not": ...}`;
- `{"expr": "..."}`;
- a comparison `{"op": "eq", "left": ..., "right": ...}` with `op` one of `eq ne lt le gt ge contains matches empty in
  between` and options `ignoreCase`, `trim`, `includeTime`, `ignoreLineBreaks` (`Conditions.compare`);
- a toolkit condition `{"condition": "ooa.browser/element_exists", "args": {...}}` (methods marked `@Condition`);
- any of these with `waitSeconds` and `pollSeconds`, which re-checks until true or the wait ends.

## 8. Sessions and saved connections

- **Sessions** are named objects an action opens and later steps use: a workbook, a browser, an SSH terminal. They
  live in the run's `sessions` map. `ActionContext.session(name, type)` fetches one; `sessionOrOnly` uses the only open
  session of that type when the step keeps the default name. Missing sessions raise `SESSION.NOT_FOUND`. All sessions
  that implement `AutoCloseable` are closed when the run ends, unless a child agent borrowed its parent's sessions.
- **Saved connections** hold a toolkit's address and other values plus vault references for its credentials. A step
  names one with `connection`; empty inputs are filled from it, and credentials are fetched from the vault at that
  moment. Connections come from the `connections` service (Command Center table `connections`, sent with each job) or
  `connections.json` in the workspace. A connection for another toolkit gives `CONNECTION.WRONG_TOOLKIT`.

## 9. Error handling and retries

- Errors are `OoaException` (`code`, `message`, `suggestedFix`, `retryable`, `details`, `stepId`). Codes are upper
  case and dotted (`FILE.NOT_FOUND`). An unexpected Java exception inside an action becomes `ACTION.UNEXPECTED`; a
  missing class in a slim program becomes `ACTION.NOT_IN_THIS_PROGRAM`.
- **Catch matching** (`ErrorCodes.matches`): an exact code, `*`, a prefix such as `BROWSER.*`, or a kind name that cuts
  across toolkits: `NOT_FOUND`, `FILE_NOT_FOUND`, `WINDOW_NOT_FOUND`, `OBJECT_NOT_FOUND`, `TIMEOUT`, `AUTH_FAILED`,
  `RATE_LIMITED`, plus legacy names such as `SAP.ERROR`.
- A Catch can store the error as a Dictionary (`as`), its message (`message_to`) and line (`line_to`), take a
  screenshot (service `screen_capture`, else the desktop), run its body with `continueOnError`, and `rethrow`.
- **Retries** apply to leaf actions. Policy: `max` (default 0), `delayMs` (default 500), `backoff: exponential`
  (doubles each attempt) and optional `codes`. Without `codes`, only errors marked retryable (for example HTTP 429
  and 5xx in cloud toolkits, `STEP.TIMEOUT`, `WAIT.TIMEOUT`) are retried. Each retry emits `step.retry`.
- **Timeouts**: a step over its time gives `STEP.TIMEOUT` (retryable); the whole run over `maxRunSeconds` gives
  `RUN.TIMEOUT`. Run workers set 24 hours.
- **Stopping**: the cancel flag makes the next check raise `RUN.CANCELLED`, which ends the run as `stopped` and is never
  caught by a Catch block.

## 10. Child agents

`core/run_bot` runs another agent inside the same run:

| Input | Meaning |
| --- | --- |
| `automation` | The child agent's id |
| `version` | A version number, a label, or `production` (default) |
| `inputs` | The child's inputs |
| `output_map` | Child output name to parent variable |
| `repeat`, `repeat_delay_seconds` | Run it several times |
| `fail_on_error` | Default true; false records the failure in the outputs and carries on |
| `same_session` | Share the parent's open sessions (browser, workbook) |
| `timeout_seconds` | Limit for the child (`CHILD.TIMEOUT`) |

- The child gets a new variable scope with only its own inputs and outputs. Its run id is
  `<parent run>.<step id>[.<n>]`.
- The parent's secrets are added to the child's mask list, and pause, cancel and the debugger pass down.
- Nesting is limited to `Runner.MAX_CHILD_DEPTH` = 10 (`CHILD.DEPTH`).
- Child definitions come from the `repository` service (`AutomationRepository.get(id, version)`). On a runner, the
  Command Center sends the production versions of all child agents with the job (collected transitively, at most 10
  levels and 100 agents).
- Toolkit actions can also start a child agent through `ChildAgents` (session key `ooa.child_agents`), for example the
  interrupt handlers that run a recovery agent when an unexpected window appears.

## 11. Run lifecycle, events and result

Each event is a `RunEvent(runId, seq, type, ts, stepId, data)`; `seq` increases by one per event in the run.

| Event | Data |
| --- | --- |
| `run.started` | `automation`, `kind`, `planHash`, `depth`, `totalSteps`, `startAt`, `onlyStep` |
| `step.started` | `action` |
| `step.finished` | `action`, `durationMs` |
| `step.failed` | `action`, `durationMs`, `code`, `message`, `suggestedFix`, … |
| `step.retry` | `attempt`, `code`, `delayMs` |
| `variable.set` | `name`, `value` (safe text form; `edited` or `carried` when set by the debugger or carry-over) |
| `log` | `level`, `message` (secrets replaced by `****`) |
| `run.paused`, `run.resumed` | Optional `message` and `byAgent` for `core/pause` |
| `run.finished` | `status`, `durationMs`, `error`, `outputs` |

`EventSink` implementations: an in-memory list (tests, Studio), a console printer (`ooa run`), and the run worker's
JSON-line stream to the runner, which posts batches to the Command Center (`run_events`).

The `RunResult` holds `runId`, `automationId`, `status` (`success`, `failed`, `stopped`), `outputs` (all `output` and
`in_out` variables), `error`, `durationMs`, `planHash` and `stepsExecuted`. At the end of a top-level run every
`SecureString` is wiped from memory.

## 12. Determinism: the plan hash

`planHash` is the SHA-256 over three canonical JSON documents: the agent without its `ui` section, the resolved
version of every toolkit its steps use, and the inputs. The same agent, inputs and toolkit versions always give the
same hash, which lets operators compare runs, replay with confidence and tie a run to exactly what was executed.

## 13. Debugger hooks

`RunOptions.debugger(Runner.Debugger)` installs a hook called before every step, in child agents too. The hook gets a
`Frame`:

| Member | Meaning |
| --- | --- |
| `stepId()`, `action()`, `automation()` | Where the run is |
| `depth()` | 0 for the agent being run, 1 for its children, … |
| `level()` | Step nesting across agents, for step over and step out |
| `stack()` | `{automation, stepId}` for each running agent, outermost first |
| `variables()` | Declared variables, credentials masked |
| `setVariable(name, value)` | Change a value while stopped (converted to its type; emits `variable.set` with `edited`) |

Studio's `StudioDebugger` implements breakpoints (step ids, also in child agents), step into, step over and step out
by blocking in `beforeStep`. The runner itself has no Studio code; only this interface.

## 14. Run from here and carried-over values

`RunOptions` supports two test modes, used by Studio's step menu:

- `startAt(stepId)` – **Run from here**. Steps before the start step (in document order) are skipped and emit no
  events. Blocks that contain the start step are entered without being evaluated: an If does not check its condition,
  a Loop runs one pass without setting its item or index, a Parallel runs only the branch with the start step, and a
  Try keeps its Catch and Finally when the start step is in the Try part. After those blocks the run continues
  normally. An unknown step gives `RUN.START_NOT_FOUND`.
- `onlyStep(stepId)` – **Run this step only**. As above, then the run ends successfully after that step (for a block,
  after everything in it).
- `trail(ValueTrail)` – records the agent's variables (plain JSON copies, never credentials or secret values) when each
  step starts for the first time and at the end. `ValueTrail.before(stepId)` returns the values at that step, or the
  end values when the run failed or stopped before reaching it. `keepFrom(older)` keeps an earlier trail's values for
  the steps a partial run skipped.
- `carryOver(map)` – values to place in variables before the start step. Inputs given to the run win; credentials,
  undeclared names and values of the wrong type are left out. Carried values are logged and emitted as `variable.set`
  with `carried: true`.

Studio keeps the last trail per agent. `POST /studio/api/runs` with `start_at` or `only_step` uses it by default and
answers `carried_over: "step"` or `"end"`; `carry_over: false` starts from the defaults. These options apply to the
agent being run, not to its child agents.

## 15. Where the engine runs

```
Runner.builder(registry)
      .sink(eventSink).workDir(dir).tenant(t).principal(user)
      .service("vault", vault).service("repository", repo).service("bot_invoker", invoker)
      .service("global_values", supplier).service("model_connections", ...).service("connections", supplier)
      .schemaValidator(validator)
      .build()
      .run(flow, inputs, options)
```

| Host | How it builds the Runner |
| --- | --- |
| Studio (`StudioServer`) | In the Studio process; vault from environment variables (`EnvVault`) or the linked Command Center; debugger and value trail |
| Run worker (`RunWorker`) | One JVM per job. Line protocol with the runner: stdin carries the job, then `credential` answers and `cancel`/`pause`/`resume`; stdout carries `event` lines, `credential` requests (`{id, ref}`) and the final `result`. Secrets arrive only on request and live only in memory |
| Command Center (`ServerlessRuns`) | Serverless API tasks on the virtual runner `dev_serverless`, with a limited toolkit list, paths confined to the run's temporary workspace (`confine_paths`), 4 at once, 5 minutes each |
| Command line | `ooa run FILE` with a console sink; `ooa validate`; `ooa conformance DIR` |

## 16. Python reference runner and conformance

`reference/` holds the Python 3.12 reference implementation (`oneofficeautomation-reference` 0.1.0): SDK
(`ooa/sdk`), runner (`ooa/runner/interpreter.py`, `expressions.py`, `validate.py`, `error_codes.py`, `events.py`), a
few packages, an AI gateway and the `Agent`-kind runtime with approvals and an MCP server (`ooa/agents`). It is a design
source, not shipped.

`contracts/conformance/cases` holds 18 cases (decimal arithmetic, interpolation, if/else-if/else, loops with break and
continue, table and dictionary loops, while limits, try/catch by prefix with finally, uncaught errors, continue on
error, validation of undeclared variables and unknown inputs, required inputs, secrets never leaking, toolkit actions
and conditions, dates, loop ranges, `$sys` and `$global`). Each case gives a `flow`, `inputs`, and `expect` (`status`,
`outputs`, `errorCode`, `errorStepId`). The harness supplies an in-memory vault with `test/app/password = s3cr3t`.
Cases are additive: an existing case's expectations are never changed within `ooa.flow/v1`.

Run them with `gradlew :cli:run --args="conformance ../contracts/conformance/cases"` (Java) and the reference's
`tests/test_conformance.py` (Python).

## Related documents

- [README.md](README.md)
- [01-system-overview.md](01-system-overview.md)
- [02-components-and-modules.md](02-components-and-modules.md)
- [04-command-center-internals.md](04-command-center-internals.md) – how jobs reach the engine on runners
- [05-data-model.md](05-data-model.md)
- [06-security-architecture.md](06-security-architecture.md) – secret masking and the vault
- [07-toolkit-architecture.md](07-toolkit-architecture.md) – how actions are declared and invoked
- [08-ai-architecture.md](08-ai-architecture.md)
- [09-multi-tenant-saas-and-regions.md](09-multi-tenant-saas-and-regions.md)
- [10-extensibility-and-integration.md](10-extensibility-and-integration.md)
