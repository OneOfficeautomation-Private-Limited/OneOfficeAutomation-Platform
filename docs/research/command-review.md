# Command review: every command, the way it is designed

Started 9 Nov 2026, after the usage-review build (items 16, 19 to 27). This is research; nothing here is built until
you choose.

## Method (each command)

| Lens | What is checked |
|---|---|
| **Usage** | How AA documents its use (the offline AA 360 library). How Blue Prism does the same job. UiPath will be added when its docs arrive. |
| **UI** | Our panel as a builder sees it: field names and order, help text, pickers, what can be reached without typing JSON. |
| **Function** | What the runner really does, every option included. |
| **Technical logic** | The code path: validation, errors and their codes, limits, edge cases. |
| **Reuse** | Whether it works with variables, other commands, child agents and subflows. |
| **Upgrade and migrate** | Whether old agents keep running, whether AA bots map onto it (the migration map), and whether the names are the ones people search for. |

## Batches (most used first, as AA's documentation and examples weigh them)

1. **Core flow**: 23 commands (If, Loop, Try, Assign, Run Agent, Delay, Wait…). This document, below.
2. Variables and data: String, Number, Datetime, List, Dictionary, Boolean, Data table, Record, JSON, XML.
3. Files: File, Folder, CSV/Text, PDF, Zip, Log to file.
4. Excel (basic, advanced, Microsoft 365), Word, PowerPoint.
5. Browser and capture: Browser, Recorder/UI objects, Image, OCR.
6. Desktop: Window, Application, Mouse, Keyboard, Screen, Clipboard, Terminal.
7. E-mail: Email, Outlook, Exchange, Gmail.
8. Integration: Database, REST, SOAP, Web services, FTP, PGP.
9. AI and documents: AI skills, Generative AI, Document extraction.
10. Cloud connectors: Salesforce, ServiceNow, Jira, SharePoint and the rest, grouped by family.

There are 1,287 commands in 101 toolkits, plus 50 conditions and 15 loop types.

---

## Batch 1: core flow (23 commands)

### What AA and Blue Prism teach about how these are used

- **If** (AA *If package*): the condition list is the heart of it. It is organised by kind: Application running,
  File exists or date, Folder exists, Window exists, Data table empty, Dictionary key, List contains, Boolean,
  Datetime, Number, String. It allows up to 32 conditions with **And/Or, and groups** of conditions. The If, Wait for
  condition and Loop While all offer the same conditions.
- **Loop** (AA *Loop package*): the iterator list is what people use: each row of a worksheet or CSV, each file in a
  folder, each mail in a mailbox, each row of a data table, N times. Loops can have **labels**, and Break and Continue
  **pick the label from a list**.
- **Error handler** (AA): Try, Catch (by exception kind, with message and line variables), Finally, Throw (pick an
  **exception from a list**, message optional). Blue Prism's **Recover** stage has *"limit the number of times this
  recover is attempted"*, which is a retry. Its **Exception** stage can *preserve the current exception* (rethrow) and
  *save a screen capture*.
- **Task Bot Run** (AA): choose the child (this bot, a Control Room file, or **a variable path**). Map inputs, with
  **Quick map**, which maps variables of the same name and type and creates the missing ones. Repeat the run and delay
  between repeats. "Upon error continue". Outputs go to **several variables or one Dictionary**.
- **Delay** (AA): Regular or Random (From to To), in milliseconds or seconds.
- **Wait** (AA): Wait for condition (one condition, same list as If), Wait for screen change, Wait for window.
- **Goto** (AA): available only on request, *for migrated bots*. It is not listed in the editor.
- **Step**, **Comment**, **Pause** (with a Resume button) and **Stop** (it explains how Finally behaves) all match ours.

### Our commands, one by one

| Command | UI | Function and logic | Verdict |
|---|---|---|---|
| If / Else if / Else | The condition builder offers **only value comparisons** (left, op, right) with All/Any. The 50 toolkit conditions (File exists, Window exists, Application is running, Data table is empty…) can only be typed as JSON under Advanced. There are no mixed And/Or groups. | The runner and validator handle toolkit conditions and nesting fully. | **Gap C1, C7** |
| Loop | N times / For each / While. For each offers **5 core kinds only**. The 15 toolkit loop types (each Excel row, CSV row, database row, file in a folder, subfolder, e-mail, browser page, XML node, work item, trigger events) are not offered. | The runner supports them all. | **Gap C2** |
| Exit loop / Continue loop | The loop name is a free-text field. | Runs the innermost loop, or the named one. | **Gap C5** (pick from the agent's loop names) |
| Try / Catch / Finally | Fixed in item 19 (AA's fields). | Full. | OK |
| Throw error | Code and message are both required, as free text. | Fine. | **Gap C6**: a list of standard codes plus your own, message optional (AA) |
| (every step) Retry and On error continue | **No UI at all.** | The runner and schema support a per-step `retry` (attempts, back-off, codes) and `continueOnError`, and an agent-wide default (`errorHandling`: stop/continue and retry). | **Gap C3**: the biggest hidden feature |
| Run Agent | Agent picker, then inputs and outputs one by one. | Repeat and delay, fail_on_error, same session, timeout, version. | **Gap C4**: Quick map, outputs to one Dictionary, the agent from a variable |
| Run subflow / Exit subflow | Fine. | Beyond AA (UiPath has invoke and local workflows). | OK |
| Assign / Assign several | Fine: typed, $ list. | Fine. | OK |
| Switch, Parallel, State machine | Fine. | Beyond AA (UiPath has them). | OK |
| Delay | Seconds, unit, "random max". | Fine. | **Small, C9**: show Regular/Random as AA's choice (From/To) |
| Wait for condition | Same builder as If. | Timeout, poll, fail on timeout. | Inherits **C1** |
| Wait for window / image / screen change / element | In their toolkits. | Fine. | OK (search finds them by AA's names since item 26) |
| Go to / Label | Offered to everyone in the library. | Fine. | **Small, C8**: AA keeps it for migrated bots; ours stays, but under Simple mode it is hidden and code analysis warns on it |
| Log message, Comment, Step, Pause, Stop | Fine. Log to file is its own toolkit, as in AA. | Fine. | OK. **C11**: four core commands show other names until the catalogue loads (Write to log / Log message, Wait / Delay, Wait until / Wait for condition, Raise an error / Throw error); use one name each |

### Why this was missed before

The October check compared **what the runner and catalogue can do**, programmatically. The conditions, loop types,
retry and continue-on-error all work in the runner and pass its tests. Nobody built an agent *in the editor* and asked
"can I pick File exists here?". That is the item 28 method we use from now on.

### Proposals (choose; nothing built)

| # | Proposal | Size |
|---|---|---|
| **C1** | **Toolkit conditions in the condition builder** (If, Else if, Wait for condition, Loop While): every row picks *what to check*. The kinds are "A value" (today's comparison), File, Folder, Window, Application, Browser, Data table, List, Dictionary, Date, Text, Image, Network, Services and others, then its own fields with $ variables. AA's order and plain names. | Large: one builder, used everywhere |
| **C2** | **Toolkit loop types in For each**: "Each row of an Excel sheet", "Each file in a folder", "Each e-mail", "Each row of a CSV file", "Each row of a database query", and so on. Each shows its own settings (sheet, folder, filter) instead of "Iterator settings" JSON. | Medium |
| **C3** | **Retry and On error continue on every step** (UiPath's ContinueOnError and Retry Scope, Blue Prism's Recover attempts). It goes in the step's More options and the step menu: "On error: stop / continue / retry N times, waiting S seconds (only for these errors)". The agent's Details get a **default error policy** (stop or continue, default retry). | Medium |
| **C4** | **Run Agent**: *Quick map* (map same-name variables, create the missing ones), outputs to *one Dictionary* as an alternative, and the agent to run *from a variable* (a path). | Medium |
| **C5** | Exit loop and Continue loop pick the loop's name from the loops around the step. | Small |
| **C6** | Throw error: the code from a list (BUSINESS.ERROR, VALIDATION.FAILED, NOT_FOUND, TIMEOUT… or your own); the message is optional. | Small |
| **C7** | Groups in the condition builder: "All of these" / "Any of these" groups inside each other, as AA's Add group. | Medium (with C1) |
| **C8** | Go to and Label: hidden in Simple mode, with a code-analysis hint "prefer Loop, If or subflows". | Small |
| **C9** | Delay: a Regular/Random choice with From/To, as AA. | Small |
| **C11** | One name per core command everywhere. | Small |

Suggested order: C1 and C2 first (most used and most visible), then C3 and C4, then the small ones together.
