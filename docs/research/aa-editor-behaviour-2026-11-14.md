# AA's Task Bot editor: how it is used (checked 14 Nov 2026)

Checked in Automation 360 Community Edition on the W1 use-case bot, without saving anything (two Run steps were added to
see the Run panel, then the editor was left with "changes will be lost"). Summarised in our own words; what we built
from it is listed at the end.

## Tool bar

- Every icon button in the editor's bar is the same size (26 px) on one centre line: record, find, search, undo, redo,
  copy, cut, paste, delete, shared clipboard, breakpoints.
- Flow, List and Dual are plain text tabs on the right of the bar; the chosen one is underlined.

## Choosing a step and its details

- With the details panel open, a click on another step shows that step's settings.
- A click on the step already chosen closes the details panel; the step list takes the whole width.
- With the panel closed, a click on another step only chooses it; a second click on it opens the panel.
- The panel's head names the command, says what it does in one line, and has a link to its documentation.

## Putting a variable in a field

- Each field has a small button, also on F2, that opens a dialog **Insert a value**: a preview of the field, then a
  variable chooser. Cancel or Insert close it.
- The chooser lists **Your variables** first (with a type icon each), then the built-in values grouped by package, each
  with a one-line description of what it returns. Typing narrows the list.
- A field that saves a result has a **create variable** button: a form with the field's type already chosen (type, name,
  description, use as input/output, constant, default) and **Create & select**.
- Variables show as `$name$` (a coloured token in the field).

## Running another bot (Task Bot: Run)

- **Task Bot to run**: the current bot (it runs itself), a Control Room file, or a variable.
- **Choose…** opens **Find a Task Bot** with two tabs: **Browse** (the folder tree on the left; the folder's bots as a
  table: type, name, status, platform, size, last modified, modified by) and **Search** (every folder). No file
  extensions are shown; the field then shows the bot's folder path and name.
- **Input values**: one row per input, each with a tick box that turns the field on; a credential input has a
  Credential or Variable switch.
- **Quick map** (an icon beside Input values) opens a small dialog: on the left the child's inputs grouped by state
  (for example "New variable needed"), each with its type; on the right what it does (same name and type are mapped)
  and two choices: create the missing variables (on) and overwrite values already mapped (off); then Apply.
- After Apply every input is ticked and shows its `$name$`; credential inputs switch to Variable.
- The step's line then reads as "Run <bot> and assign output to variable".
- **Save the outcome to a variable**: several variables, one per output, or one Dictionary.

## What we built from it (14 Nov 2026 list, items A to I)

| AA | OneAgentStudio |
| --- | --- |
| One size on one centre line in the bar | `--ui-bar-h` for every tool bar control; `tools/check-alignment.js` measures every page |
| Flow / List / Dual as text tabs | List and Flow as underline tabs |
| Second click on a step closes the panel | Same; a first click on a new step opens Properties (the panel opens even when it was closed) |
| Insert a value (F2) | Insert a value ({x}, F2, Ctrl+Space or `$`): preview, Your variables, System values with descriptions, Shared Global values |
| Create & select | Create and use |
| `$name$` | `$name$` written and shown; the runner also reads `$name` and `${name}`; saving rewrites them |
| Find a Task Bot (Browse, Search) | Find an agent (Browse, Search), Agents › Folder › Name, no `.flow.json` |
| Input tick boxes, Quick map dialog | Same, with the groups Ready to map, New variable needed, Already set, Same name and another type |
| Documentation link per command | Docs and Video per command in the Actions list, beside each step on the canvas, in the step menu and in Details |
