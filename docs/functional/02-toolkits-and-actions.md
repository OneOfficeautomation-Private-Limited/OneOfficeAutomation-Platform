# 02 Toolkits and actions

## What this covers

What a toolkit is, the catalogue of toolkits grouped by area, and the behaviour every action shares: how inputs are
checked, sessions, saved connections, credentials in steps, the settings panel and its More options, conditions, and
the error handling options of each step (continue on error, retries, timeouts, Try and Catch). The full list of
actions with their inputs and outputs is generated from the toolkits themselves and kept in
[../reference/toolkits/](../reference/toolkits/); the mapping to another vendor's packages is in
[../COMMAND_CATALOG.md](../COMMAND_CATALOG.md).

## Contents

1. Toolkits, actions, conditions and iterators
2. The catalogue by area
3. How an action step runs
4. Sessions
5. Saved connections and credentials in steps
6. The settings panel and More options
7. Conditions
8. Error handling per step
9. Error codes and error kinds
10. Managing toolkits in the Command Center
11. Your own toolkits

## 1. Toolkits, actions, conditions and iterators

A **toolkit** (called "package" in the API and in agent files) is a named, versioned set of:

- **actions**, the commands a step runs (`toolkit/action`, for example `ooa.excel/get_cell`);
- **conditions**, tests that If, Loop (while) and Wait for condition can use (for example a web element exists);
- **iterators**, sources a Loop can go through (for example the trigger loop).

Each action declares its inputs (name, type, required or not, default, allowed values, pattern, range, help text, when
it applies), its outputs, its default timeout, and capabilities such as `desktop` or `network`. Studio builds its
settings panel from these declarations, the validator checks steps against them, and the runner converts values to
the declared types.

## 2. The catalogue by area

The Studio Actions panel groups actions by technology, under these sections. The toolkit ids are the stable names
used in agent files.

| Area | Toolkits (ids) |
| --- | --- |
| Flow and control | `core` (If, Loop, Try, Step, Parallel, Assign, Log, Delay, Wait for condition, Throw, Stop, Pause, Run Agent, Comment, Exit and Continue loop), `ooa.interrupts`, `ooa.triggerloop`, `ooa.run_context` |
| Data and variables | `ooa.string`, `ooa.number`, `ooa.boolean`, `ooa.datetime`, `ooa.list`, `ooa.dictionary`, `ooa.datatable`, `ooa.global_values`, `ooa.fuzzy`, `ooa.currency` |
| Data formats | `ooa.json`, `ooa.xml`, `ooa.csv` |
| Files and folders | `ooa.file`, `ooa.folder`, `ooa.file_transfer` (FTP/SFTP), `ooa.printer`, `ooa.log` |
| Office documents | `ooa.excel` (no Excel needed), `ooa.excel_desktop` (needs Excel), `ooa.word`, `ooa.powerpoint`, `ooa.pdf` |
| Web and desktop | `ooa.browser`, `ooa.window`, `ooa.desktop` (mouse, keys, clipboard, dialogs, screen), `ooa.desktop_ui` (UI Automation and legacy MSAA objects), `ooa.java_apps`, `ooa.image`, `ooa.ocr`, `ooa.application`, `ooa.terminal`, `ooa.sap`, `ooa.sound`, `ooa.forms` |
| Email and collaboration | `ooa.email`, `ooa.outlook_desktop`, `ooa.microsoft365`, `ooa.google`, `ooa.slack` |
| APIs and databases | `ooa.rest`, `ooa.soap`, `ooa.database`, `ooa.connector` (custom connectors from OpenAPI, Swagger or Postman files), `ooa.snowflake`, `ooa.databricks`, `ooa.kafka` |
| Business applications | `ooa.salesforce`, `ooa.servicenow`, `ooa.workday`, `ooa.jira`, `ooa.github`, `ooa.azure_devops`, `ooa.asana`, `ooa.zendesk`, `ooa.okta`, `ooa.box`, `ooa.docusign` |
| Cloud infrastructure | `ooa.aws_ec2` (see below) |
| System and security | `ooa.system`, `ooa.services`, `ooa.network`, `ooa.active_directory`, `ooa.credential_manager`, `ooa.crypto`, `ooa.pgp`, `ooa.dll` |
| Scripts | `ooa.script` (Python, PowerShell, VBScript and VBA, JavaScript with Node.js) |
| AI and documents | `ooa.genai`, `ooa.ai_skills`, `ooa.ai_agent`, `ooa.web_agent`, `ooa.knowledge`, `ooa.insights` |
| Work management | `ooa.workload` (work queues from inside an agent) |

Some toolkits ship in optional modules (browser, e-mail, Office, PDF, file transfer, Windows); a runner reports which
toolkits and versions it has with its heartbeat ([document 03](03-runs-runners-and-devices.md)).

### AWS EC2 (`ooa.aws_ec2`)

The AWS EC2 toolkit manages Amazon EC2 virtual machines through a session opened with **Connect**.

| Action | Category | What it does |
| --- | --- | --- |
| Connect, Disconnect | Connection | Open a session for a region with an access key id and secret access key (Credential inputs from the vault; optionally a session token and another endpoint); requests are signed with AWS Signature Version 4. Disconnect closes the session and forgets the keys |
| Describe instances | Instances | List instances with their details, optionally filtered |
| Get instance status | Instances | Read the state and status checks of instances |
| Start instances, Stop instances, Reboot instances, Terminate instances | Instances | Change the power state of instances; Terminate ends them for good |
| Enable monitoring, Disable monitoring | Monitoring | Turn detailed monitoring on or off |
| Launch instances | Instances | Start new instances from an image with the chosen settings |
| Set tags | Instances | Add or change tags on instances |
| Wait for state | Instances | Wait until instances reach a state (for example running or stopped) |

## 3. How an action step runs

1. The step's inputs are resolved: variables and calculations are evaluated.
2. When the step names a saved connection, the inputs the step leaves empty are filled from it (section 5).
3. Inputs are converted to their declared types. A value that cannot be converted fails with `VAR.TYPE_MISMATCH`
   ("Input 'x' expects Dictionary, got List"); a text that does not match the input's pattern fails with
   `ACTION.INPUT_INVALID`; a required input that resolves to empty fails with `ACTION.INPUT_MISSING`. Empty optional
   inputs take their defaults.
4. The action runs on the run's worker thread with a time limit: the step's own **Timeout (seconds)**, or else the
   action's default. Past it the step fails with `STEP.TIMEOUT` (a temporary error).
5. The outputs named in the step are stored in their variables. An output the action did not return fails with
   `ACTION.OUTPUT_MISSING`. Secret outputs are kept as secrets and masked in every log.
6. An unexpected error inside a toolkit becomes `ACTION.UNEXPECTED`, naming the toolkit and action.

Collection actions (list, dictionary, table) return the changed collection; when the input is a variable and the
same-named output is not stored elsewhere, Studio stores it back into that variable.

## 4. Sessions

Actions that open something lasting (a workbook, a browser, a mail connection, an EC2 connection, a terminal) keep it
in a **session** of the run under a name. Later steps name the session to work on it.

- A step that keeps the toolkit's default session name uses the one open session of that kind when nothing is open
  under the default name (a workbook opened as `report` is used by steps left at `excel`). With several open, the step
  must name one.
- A session of the wrong kind under the name fails the step.
- All sessions are closed when the agent ends, unless they were lent by a parent agent (`same_session` on Run Agent),
  in which case they stay open for the parent.

## 5. Saved connections and credentials in steps

**Credentials in steps.** An input of type Credential takes a Credential variable or a vault reference; the value is
fetched from the vault only when the step runs, as the run's user, and is masked as `****` in logs, events, variables
and errors. Typing a secret into an ordinary input is reported by code analysis ([document 08](08-repository-versions-and-templates.md)).
Vault attributes marked **Password fields only** may only be typed into password fields; the browser's Set text
action and the web goal agent check the field first.

**Saved connections** (console: Manage > Connections; Studio: the Connection field of a connector step) keep a
connector's address and other repeated settings, plus vault references for its secrets, under a name.

| Rule | Detail |
| --- | --- |
| Which toolkits | Toolkits with at least 3 actions whose shared inputs (used by at least 80% of the actions) include a secret. Excel desktop, PDF, REST and Generative AI have none. |
| Name | Letters, digits, spaces, `_`, `.`, `-`, up to 80 characters |
| Values | Text, numbers or true/false; credentials must be vault references `folder/credential/attribute` |
| At run time | Every saved connection travels with the run by name (references only). Inputs the step sets itself win; empty ones are taken from the connection; secrets are fetched just in time as the run's user. |
| Validation | A step with a connection is not reported for missing required inputs the connection supplies. |
| Permissions | `globals.view` to see, `globals.manage` to create, change and delete |

## 6. The settings panel and More options

When a step is selected, the Action settings panel shows (in order): the action's inputs, its outputs ("Assign the
result to"), **Error handling**, and **General**.

- **Inputs in view**: required inputs, the inputs that are the key choice for that action, and the first few optional
  ones. A toolkit may lay out its own panel: the order of fields, which go under More options, tick boxes that reveal
  further fields, and choices that show only the chosen option's fields.
- **More options** (a collapsed section with a count): rarely used optional inputs with a default, and advanced inputs
  such as self-healing alternatives and session names.
- Inputs that do not apply to the current choices (by the action's "applies when" rules) are hidden; an input
  controlled by a variable counts as "may apply".
- Drop-down values are stored as codes (for example `not_contains`) but shown in words.
- A required field that is still empty says so under the field before Validate is pressed.
- **General** holds the step note ("What this step does"), shown on the step card instead of the settings summary.

## 7. Conditions

If, Else if, Loop (while) and Wait for condition take a condition, which is one of:

- a comparison: left value, operator and right value. Operators: equals, not equals, less, less or equal, greater,
  greater or equal, contains, does not contain, starts with, does not start with, ends with, does not end with, matches
  (regular expression), does not match, is empty, is not empty, is in (a list), is not in, between, not between. Text
  options: ignore case, trim spaces; for date-times, compare the date only.
- a toolkit condition (for example a browser element exists), with its own inputs;
- an expression such as `$total > 1000 and $status == "open"`;
- groups combining conditions with **all** (and), **any** (or) and **not**.

Studio's If builder shows the condition as a sentence and can **Test** it once with sample values.

## 8. Error handling per step

| Option (panel label) | Stored as | Applies to | Behaviour |
| --- | --- | --- | --- |
| Disabled (skip this step) | `disabled` | Every step | The step and its contents are skipped |
| Continue the agent if this step fails | `continueOnError` | Every step | The failure is logged as a warning ("Continuing after error CODE") and the run goes on with the next step. Stopping a run is never swallowed. |
| Retries on temporary errors | `retry: {max, backoff, delayMs, codes}` | Action steps | Up to `max` further attempts (Studio offers 0-10, exponential backoff from 500 ms: 0.5 s, 1 s, 2 s …). Without `codes`, only errors marked temporary are retried; with `codes`, only errors matching them. Each retry is logged as `step.retry`. |
| Timeout (seconds) | `timeoutSeconds` | Action steps | Replaces the action's default time limit (`STEP.TIMEOUT` past it) |
| Agent default retry | `errorHandling.retry` | The whole agent | Used by action steps that have no retry policy of their own |

**Try, Catch and Finally** (`core/try`):

- A Catch lists error codes (exact codes, families with a star, kinds, or `*`). The first Catch whose list matches the
  error runs; a Catch with an empty list catches every error. An error no Catch matches leaves the Try and fails the
  enclosing block. A stopped run is never caught.
- A Catch can store the error in a variable (code, message, suggested fix, step id, the failing action and its line
  number), the message in another, and the line number in a third.
- A Catch can take a screenshot (to a file under `screenshots/` of the run's workspace, or a name given); failing to
  take one never fails the Catch.
- A Catch can be set to continue on errors within its own steps, and to **rethrow** the error after its steps.
- Finally runs after the Try or Catch in every case, including Stop agent and stopped runs passing through.
- Throw error raises a custom error (for example in the `BUSINESS` family) that a Catch can match, optionally marked
  temporary so retry policies try again.

## 9. Error codes and error kinds

Error codes have the form `FAMILY.NAME` (`FILE.NOT_FOUND`, `BROWSER.OBJECT_NOT_FOUND`, `EXCEL.SHEET_NOT_FOUND` …). A
Catch or retry entry may be an exact code, a family with a star (`BROWSER.*`), or one of these kinds that cut across
toolkits:

| Kind | Covers |
| --- | --- |
| `NOT_FOUND` | Anything not found: file, folder, window, object, record, key |
| `FILE_NOT_FOUND` | A file or folder that does not exist |
| `WINDOW_NOT_FOUND` | A browser or desktop window that is not open |
| `OBJECT_NOT_FOUND` | A control, element, field or image on screen that is not there |
| `TIMEOUT` | Waited too long: page, element, window, process, service or the whole run |
| `AUTH_FAILED` | Sign-in or permission refused by a service |
| `RATE_LIMITED` | A service asked to slow down |
| `SAP.ERROR` | Any SAP GUI scripting error (older name kept so existing Catch blocks still work) |

## 10. Managing toolkits in the Command Center

Administration (Manage) > **Toolkits** lists every installed toolkit with its version, publisher, number of actions and
conditions, the number of agents that use it (with the list), whether it is on, and its installed versions.

- **Switch off** (needs `toolkits.manage`): agents that use the toolkit can no longer be checked in or run
  (`CR.TOOLKIT_DISABLED`, naming the step). The `core` toolkit cannot be switched off.
- **Versions**: the agents that use a toolkit, the version range each requires, and whether the installed version fits.
  **Update agents** sets a new range on all of them; each gets a new version, its live version is unchanged, and agents
  checked out by someone else are left alone and reported.
- Seeing the toolkit list over the API needs `repo.view`; the console page itself is shown to people with `toolkits.manage`.

## 11. Your own toolkits

Signed toolkits written with the SDK can be added by people with `toolkits.manage`:

| Rule | Detail |
| --- | --- |
| Trusted publishers | A publisher's public key must be added first; a publisher can be removed once none of its versions is left |
| Upload refused when | The jar is unsigned, signed by an untrusted publisher or another key, uses a product toolkit id or another publisher's id, or reuses a version number with different contents |
| Side by side | Each version loads in its own class loader; agents pick a version with their range, or get the newest |
| On the runner | The jar travels with the run; its SHA-256 and publisher signature are checked again before loading, otherwise the run fails with `RUN.TOOLKIT_LOAD` |
| Deleting a version | Allowed unless it is the only version and agents use it |
| Rights | A trusted publisher's code runs with the runner's rights, like the product's own toolkits |

## Related documents

- [01 Agents and automation items](01-agents-and-automation-items.md)
- [07 Vault, credentials and connections](07-vault-credentials-and-connections.md)
- [14 One AgentStudio](14-studio-features.md)
- [Command catalogue](../COMMAND_CATALOG.md)
- [Toolkit reference](../reference/toolkits/)
- [Command Center and runner, sections 9a and 9b](../user-guide/00-README.md)
