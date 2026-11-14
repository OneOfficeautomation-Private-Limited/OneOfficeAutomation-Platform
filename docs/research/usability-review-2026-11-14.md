# Usability review: how people use AA and our platform (14 Nov 2026)

How a person new to the product finds their way, in Automation 360 (AA) and in ONEOFFICEAUTOMATION, and what AA users
keep struggling with. In our own words. Sources:

- **AA Community Edition**, walked through screen by screen (Home, Automation, the Task Bot editor, Activity, Devices,
  Credentials, Global values), read only; nothing saved.
- **AA's documentation** (the offline library, `AA360-docs.md`) for the components compared.
- **What AA users report**: 42 recurring problems from AA's community forum, support articles, known-issue lists and
  review sites (details and links: `aa-user-issues-2026-11-14.md` next to this file).
- **Our platform**: every console page measured on a test server, and a code audit of the web UI (92 findings with file
  and line: `our-ui-audit-2026-11-14.md` next to this file).

## 1. AA: what works, what does not

| Area | Works well (keep or copy) | Does not (do better) |
| --- | --- | --- |
| Menu | Few sections, words with icons, the current page marked | Sections fold, so a page two levels down is two clicks away |
| Automation list | Folder tree beside the list; "search within subfolders"; columns can be chosen | The list tools (create, upload, delete, refresh, filter, columns) are bare 26 px icons; a row's ⋮ opens a strip of **eight bare icons** (run, move, insights, view, edit, copy, dependencies, delete) named only on hover; at a laptop width the name column is cut to two letters |
| Create actions | **Create ▾** at the top of Automation is a labelled menu | Elsewhere the create action is a bare icon: "Create credential…", "Connect local device…" |
| Empty pages | They exist | They only say "When created, they will appear here", without naming the button to press |
| Editor tool bar | One size, one line; Run and Debug labelled | Record, find, search, undo, redo, copy, cut, paste, remove, shared clipboard, breakpoints: all bare icons |
| Editor steps | The step's ⋮ menu is good: words, icon and shortcut for each entry (Run from here, Copy, Cut, Remove, Disable, Add breakpoint); a **+** between steps on hover inserts one | At a laptop width the details panel squeezes the steps until they cannot be read |
| Values in fields | **Insert a value** (F2) with a preview; your variables first; built-in values described; **Create & select** | The button that opens it is a tiny "(x)"; the coloured `$name$` token hides what it is to a newcomer |
| Run another bot | Find a Task Bot (Browse, Search); Quick map with a preview of what it maps | Quick map is a bare ⇄ icon; mappings go stale when the child changes, without a warning (reported often) |
| Activity | In progress and Historical; columns can be chosen | A run's name is the bot name plus a timestamp plus an e-mail address |
| Errors | Each has a code | Many say "unknown error, contact your administrator"; the real reason is spread over the activity, the audit log and the runner's log files |

## 2. What AA users struggle with most (from the 42 reports)

1. Errors that do not say what is wrong or how to fix it (many reports).
2. Runner (bot agent) connections that drop, with trial-and-error fixes (the most common forum topic).
3. A child bot's outputs lost when the child fails, and mappings that go stale (many).
4. Runs that fail after deployment: the run-as user cannot read a credential, a dependency is missing, the screen is locked (many).
5. Captured objects that stop being found when a page changes; browser extension problems (many).
6. Debugging: too little shown, no way to change a value and go on (many).
7. Runs, schedules and work items that sit waiting without saying why (several).
8. Versions: no compare, no undo of a check-in, deleted bots gone (several).
9. Slow, cramped editor on large bots; copy and paste between bots loses variables (several).
10. Excel and other sessions named by free text: "session already exists", "no open session" (many).

## 3. Our platform: what works, what does not

**Works well already**

- Every console page has a one-line description, Docs and Video, and an **i** help panel; empty pages on Work queues,
  Connections, Event subscriptions, Alerts and Prompt Skills say what to do next.
- Errors carry a suggested fix (CommandFixesTest checks every module and service error has one).
- Versions can be compared and deleted agents recovered; a Runner status page exists; a run's details page shows its
  steps and log.
- Since 14 Nov: Insert a value, Find an agent, Quick map with a preview, `$name$`, one tool bar height, a click on a step
  opens and folds Details, Docs and Video on each step.
- Copy and paste of steps carries the variables they use, in one Studio window and across windows.

**Does not, or not well enough** (the code audit's list, checked)

| # | Where | What a newcomer meets | Better |
| --- | --- | --- | --- |
| 1 | Run Agent | Quick map is a bare ⇄ icon | A labelled **Quick map** button |
| 2 | Every field | `{x}` with no words; typing `$` is not mentioned | A small **Insert value** button, and "type $ for a variable" in the field's hint |
| 3 | Step edit bar | Disable (power icon), Duplicate, Cut, Copy are bare icons | Words next to the uncommon ones (Disable, Duplicate), or a labelled overflow |
| 4 | Steps | Breakpoint, Run from here, Run this step only only in ⋮ or a right-click | A click on the line number sets a breakpoint; **Run from here** on the chosen step |
| 5 | Condition builder | Value fields have no Insert value button | The same Insert value as every field |
| 6 | Type badges | "Abc", "Key", "Dur", "Bin", "Rec", "Tbl"; meaning only in a tooltip | The type word ("Text", "Number", "Secret") |
| 7 | Help | The page help is a 16 px "i"; the top bar's ? only has shortcuts | A **Help** button; the ? menu also opens the user guide and "What's new" |
| 8 | Who fields | Vault, queues, alerts, dashboards, workflow stages: "role:NAME, team:NAME, user:NAME" typed by hand | One people, teams and roles picker |
| 9 | Agent inputs | Schedules, workflow stages, low-code buttons, starting a workflow: inputs typed as JSON (bad JSON silently dropped in one place) | One field per input of the chosen agent, as the Run dialog does |
| 10 | Run dialogs | List, Dictionary and Table inputs typed as text; a credential typed as a vault path | The same editors Studio uses; a vault picker |
| 11 | Hover only | Action descriptions, Docs and Video, the folder tree's ⋮ | Shown on focus and on the chosen item too, and on touch |
| 12 | Studio Agents | Upload only in a folder's ⋮ | An **Upload** button beside New folder and Create |
| 13 | Subflows | Only in the More ⋯ menu | A visible **Subflows** tab or button when the agent has or may have them |
| 14 | Internal words | "Agent id" first in agent properties (changing it breaks Run agent steps elsewhere); step ids in the console's Steps tab; Catch codes and Format check (a regular expression) typed by hand | Hide or lock the id; pick error codes from a list; offer common formats |
| 15 | Console tables | An 18 px faint funnel on every column | A labelled **Filters** row (as Agent Activity already has), funnels kept for power users |
| 16 | Empty pages | "No schedules yet.", "No triggers yet.", "No teams yet.", "No shared values yet.", "No credentials you can see yet.", "No model connections yet.", Toolkits "None" | Name the button: "No schedules yet. Press **New schedule** to run an agent at set times." |
| 17 | Problem steps | A "CHECK" badge that does not say what to check | The problem in words on the badge's hover and a click that opens it |
| 18 | Same thing, two names | Disabled step: "OFF" in the console, "Disabled" in Studio; "KPI" vs "Business figure" | One word each |
| 19 | Small targets | Help "i" 16 px, column filter 18 px, tree ⋮ 22 px, tree carets 14 to 18 px, label × with no padding | At least 24 px |
| 20 | Variable notation in modules | Four ways to write a value: `$name$`, `${name}`, `{field}`, `$form.field$` | `$name$` everywhere |

**Broken (not only hard to use)**

- Document Automation's review screen: **Previous task** and **Next task** do nothing (no action behind them).
- Document Automation's **Tenant** list ignores the choice.

## 4. AA pain points: where we stand

| AA pain point | Ours today | Gap |
| --- | --- | --- |
| Errors without a cause or fix | Every error has a suggested fix | The run page could jump to the failing step and show its inputs |
| Runner connection drops | Runner status page, heartbeat, `ooa agent check` | A self-check on the runner page that names the cause (network, proxy, clock, service, certificate) |
| Child outputs lost, stale mappings | `error` and `status` outputs; Fail on error | Warn on save when a mapping names an input or output the child no longer has |
| Fails after deployment | Errors at run time | A check before run and check-in: credentials the run-as user can read, dependencies present, runner fits |
| Objects not found | Several locators, Test in capture | A "Test object" button on every element step |
| Debugging | Breakpoints, step over and into, values shown | Change a value while paused and go on |
| Waiting without saying why | Queue time and status | A "why is it waiting" line on queued runs, schedules and work items |
| Versions | Compare and recover | Restore a version in one click (if not already) |
| Sessions by free text | Session name field | Pick sessions from a list of the ones opened earlier in the agent; close them on error |

## 5. Proposed fix list

See the numbered list sent for approval on 14 Nov 2026 (in the session); the order puts the broken items and the
newcomer traps first.
