# Building agents in One AgentStudio

**Audience:** agent builders who know the Studio screen (see [Studio basics](02-studio-basics.md)) and want to build
real agents: adding steps, filling in settings, working with variables, conditions, loops and error handling.

## Contents

1. [Add steps](#1-add-steps)
2. [Select, move and arrange steps](#2-select-move-and-arrange-steps)
3. [The Action settings panel](#3-the-action-settings-panel)
4. [Variables](#4-variables)
5. [Values, `$variables` and `${...}` text](#5-values-variables-and--text)
6. [Calculations (expressions)](#6-calculations-expressions)
7. [Conditions: the If builder](#7-conditions-the-if-builder)
8. [Loops](#8-loops)
9. [Error handling](#9-error-handling)
10. [Step groups, notes and comments](#10-step-groups-notes-and-comments)
11. [Disable steps](#11-disable-steps)
12. [Copy, paste and the shared clipboard](#12-copy-paste-and-the-shared-clipboard)
13. [Undo and redo](#13-undo-and-redo)
14. [A worked example](#14-a-worked-example)
15. [If it goes wrong](#15-if-it-goes-wrong)
16. [Related guides](#related-guides)

---

## 1. Add steps

There are four ways to add an action as a step. Pick whichever suits you.

**Click it.**

1. Open the **Actions** panel (rail button with four squares).
2. Find the action: open its group, or type in **Search actions**.
3. Click the action (or press Enter on it).

It goes where the canvas hint says:

| What is selected | Where the new step goes |
| --- | --- |
| Nothing | At the end |
| **Start** | First (step 1) |
| **End** | At the end |
| A step | Right after it |
| A section of a block (click **Then**, **Repeat**, **Try** …) | At the end of that section, which is highlighted |

**Drag it.** Drag the action from the Actions panel onto the canvas.

- Drop it on the gap between two steps, on **Start** or **End**, or on a section heading.
- Drop it on a step card: the top half puts it **before** that step, the bottom half **after** it. On an open block
  (If, Loop, Try) the bottom half puts it **inside**, at the start of the first section. A line on the card shows where
  it will land.
- Near the top or bottom edge of the canvas, the canvas scrolls by itself.

**Quick add.** Press **Ctrl+K** or **/** on the canvas (or click **+ Quick add**). Type a few words, use the arrow keys
and press Enter. The header tells you where the step goes ("after the selected step", "at the end" …).

**Record or capture.** **Record**, **Capture** and **Capture desktop** add steps for what you do on screen (see
[Web and desktop automation](10-web-and-desktop-automation.md)). The **Assistant** can also propose steps from a
description (see [AI in agents](13-ai-in-agents.md)).

**What you see:** the toast "Added <action name>", the new step selected, and its settings in **Action settings**. A
block (If, Loop, Try, Step) is added open, with its first section highlighted so the next actions you click go inside.

> **Tip:** **Catch** and **Finally** appear in the Actions panel next to **Try**. Drop one on a Try (or just after it)
> to add that section. When there is no Try, Studio makes one for you.

## 2. Select, move and arrange steps

- **Click** a step to select it. **Ctrl+click** adds or removes steps. **Shift+click** selects a range within the same
  block. **Ctrl+A** selects everything. **Esc** clears the selection. With two or more selected, the toolbar shows
  "n steps selected" with ✕ to clear.
- **Move** steps by dragging them (all selected steps move together), with **Alt+↑ / Alt+↓**, with the ↑ ↓ buttons in
  the canvas toolbar, or with **Move up** / **Move down** at the bottom of Action settings. A block cannot be moved inside
  itself.
- **Fold** a block with its arrow (or ← →) to hide its steps; the card then says "n steps hidden".
- The **step menu** (⋮ on a card, right-click, or Shift+F10) offers: **Run from here**, **Run from here with default
  values**, **Run this step only**, **Open the agent it runs** (Run Agent steps), **Copy**, **Cut**, **Paste after**,
  **Duplicate**, **Delete**, **Disable** / **Enable**, **Toggle breakpoint**, **Move up**, **Move down**, **Add note…**,
  **Copy to shared clipboard** and **Paste from shared clipboard**.

Deleting a block asks "Delete this step and the n step(s) inside?".

## 3. The Action settings panel

Select a step to see its settings on the right. From top to bottom:

1. **Title**: the action's icon, name, toolkit and address (for example `ooa.excel/open`), then its description.
2. **Inputs**: the fields the action needs.
3. **Outputs**: where to keep what the action returns.
4. Extra sections for some blocks: **Else if branches** (If), **Catch blocks** and **Finally** (Try).
5. **Error handling** (folded): **Disabled (skip this step)**, **Continue the agent if this step fails**, and for toolkit
   actions **Retries on temporary errors** and **Timeout (seconds)**.
6. **General** (folded): **Step id** and **Note**.
7. Buttons: **Move up**, **Move down**, **Duplicate**, **Delete**. With several steps selected they act on all of them.

### 3.1 Fields

| Field looks like | What to enter |
| --- | --- |
| Text box | Text, a `$variable`, or text with `${variable}` in it. |
| Number box | A number, a `$variable`, or a small calculation such as `$n - 1`. A value outside the allowed range turns red. |
| Drop-down with "(default: …)" | Leave it on the default or pick another choice. |
| true / false drop-down | For Boolean fields. |
| "(choose a … variable)" list | For Table, List, Dictionary and Record fields: pick a variable of that type, **+ New … variable**, or **Enter a value or expression…**. |
| **{x}** button | Opens the variable picker (also Ctrl+Space, or typing `$`). |
| ⓘ | Hover or focus it for the field's help, allowed range and an example. |

A required field that is still empty is marked with ***** and says **Required** under it, before you even validate.

### 3.2 Choices, tick boxes and More options

Toolkits lay out their panels so the common case stays short:

- **Choices** are a row of buttons that show only the chosen option's fields. Example: **Excel > Get cell** asks
  **Cell: The active cell / A specific cell**; only *A specific cell* shows the **Cell** box. Choosing another option
  clears the fields of the one you left.
- **Tick boxes that reveal fields**: the fields stay hidden until you tick the box. Example: **Excel > Open workbook**
  has **The file has a password** (reveals **Password**) and **The file is on OneDrive or SharePoint** (reveals
  **Access token** and **Drive**). Unticking clears those fields.
- **More options** folds away settings you rarely need. Its heading shows how many fields it holds and how many you have
  changed ("· 2 changed"). It opens by itself when one of its fields is set.
- Some fields appear only when another field has a certain value (for example a loop's **Number of times** only for
  *N times*).

### 3.3 Outputs

Each output reads **Save <output> in**, with the type expected. Choose a variable, or click **+ New** to create one with
the right type. Only variables that can hold that type are listed; a Text result may also go into a Number or Boolean
variable ("converted from text").

> **Tip:** Actions that change a table, list or dictionary return the changed copy. When you choose a variable as their
> input, Studio stores the result back into the same variable for you and says so: "The result is stored back in
> $rows (see Outputs)."

## 4. Variables

Variables hold values while the agent runs. Open the **Variables** panel from the rail.

### 4.1 Create a variable

1. Click **+ New** in the Variables panel (or **+ Add** next to a group, or **+ New variable** in the variable picker).
2. In **New variable**, fill in:
   - **Name**: letters, digits and `_`, starting with a letter or `_`. You use it as `$name`.
   - **Type**: one of the tiles below.
   - **Use**: tick **Use as input** (given when the agent starts, by a person, a schedule or a parent agent) and/or
     **Use as output** (returned when the agent ends). Neither ticked: a working value inside the agent.
   - **Required: the agent cannot start without a value** (inputs only).
   - **Business figure (KPI)** (Number, String or Boolean outputs): each successful run records it for dashboards in One
     Command Center.
   - **Default value**: an editor that suits the type.
   - **Allowed values** (String and Number inputs): a comma-separated list; people then pick from it.
   - **Description**: what it holds. People running the agent, and AI agents, read it.
3. Click **Create variable**.

| Type | Holds |
| --- | --- |
| String | Text |
| Number | A whole or decimal number |
| Boolean | True or false |
| DateTime | A date and time |
| List | Ordered items |
| Dictionary | Named keys and values |
| Table | Rows and columns |
| Record | One row of named values |
| File | A file or folder path |
| Window | An application window |
| UIElement | An element on screen or in a web page |
| Credential | A secret from the vault |
| Any | Any kind of value |

Lists, dictionaries, records and tables get small editors (**+ Item**, **+ Key**, **+ Column**, **+ Row**) with
**Edit as JSON** for when you prefer to type.

### 4.2 Work with variables

- Click a variable to edit it. Renaming updates every use in the agent.
- Each row shows the type, default and how often it is used ("3 uses" or "unused").
- **→** inserts `$name` into the field you edited last; **⧉** duplicates; **✕** deletes (Studio warns when it is in use).
- **System variables** (`$sys.…`) are read-only values from the platform, such as today's date or the machine name.
- **Shared Global values** (`$global.…`) are shared by all agents. They are managed in One CommandCenter > Shared Global values (or
  `global-values.json` in the workspace for local runs). **Refresh** reloads both lists.

### 4.3 Credentials

Never type a password or token into a step. Use a **Credential** variable instead:

1. Create a variable of type **Credential**, usually as an input. Credentials never have a default.
2. Use `$password` (or your name) in the step's credential field. In **Set text** fields choose **Credential** and pick
   the variable.
3. Whoever runs the agent gives a **vault reference** in the form `folder/credential/attribute`, for example
   `finance/erp/password`. The secret is fetched only while the step runs and is masked in every log.

> **Tip:** For local test runs in Studio, the reference `finance/erp/password` is read from the environment variable
> `OOA_SECRET_FINANCE_ERP_PASSWORD`, set before ONEOFFICEAUTOMATION starts. Runs in One CommandCenter read One Agent
> Vault.

## 5. Values, `$variables` and `${...}` text

| You type | It means |
| --- | --- |
| `Hello` | The text Hello |
| `42`, `true`, `["a","b"]`, `{"k": 1}` | A number, a Boolean, a list, a dictionary (written as JSON) |
| `$total` | The value of the variable `total`, with its own type |
| `$row.Customer` or `$row["First Name"]` | A column of a record, or a key of a dictionary |
| `$items[0]` | The first item of a list |
| `Invoice ${number} is due` | Text with the value put in |
| `$sys.…`, `$global.…` | System and shared values |

> **Important:** inside other text, write `${name}`, not `$name`. `Dear $name` is sent literally. Studio warns you with
> the toast "Inside text, write ${name} to put in the value of $name."

## 6. Calculations (expressions)

Calculations are worked out when the step runs.

- **Assign variable** has two modes: **Text or value** (saved as typed) and **Calculation**. Text that looks like a
  calculation, such as `$i + 1`, is saved as one automatically ("Saved as a calculation: …").
- Number fields accept a calculation directly: `$count - 1`.
- **Set text** fields have a **Calculation** mode too: `upper($row.Name)` or `$qty * 2`.

You can use `+ - * / %`, comparisons (`== != < > <= >=`), `&&` and `||`, and functions such as `round($x, 2)`,
`toNumber($text)` and `upper($name)`.

## 7. Conditions: the If builder

**If**, **Else if**, **Wait for condition** and **Loop > While** use the same condition builder.

1. Add an **If** step. It comes with **Then** and **Else** sections.
2. Under **Condition**, the first row is **Compare two values**:
   - left box: a `$variable` or value;
   - operator: **equals**, **does not equal**, **is greater than**, **is less than**, **contains**, **starts with**,
     **ends with**, **matches pattern (regex)**, **is empty**, **is not empty**, **is one of (list)**,
     **is between [low, high]** and their opposites;
   - right box: the value to compare with.
3. **Text options** (folded) add **Ignore case**, **Ignore spaces around** and **Ignore line breaks**: useful for text
   read from pages and files.
4. Tick **Not** to reverse a row.
5. **+ Condition** adds a row. With two or more rows, choose **All of these are true (AND)** or
   **Any of these is true (OR)**.
6. **+ Group** adds rows combined the other way, for example *A and (B or C)*.
7. The first drop-down of a row also offers **Is true**, **Is false**, and conditions from toolkits, such as
   **File exists**, **Element exists**, **Window exists**, **Table is empty** or **Date is between**, with their own fields.
8. Prefer typing? **Write an expression instead**, for example `$amount > 1000 && $status == 'open'`.
   **Use the condition builder** takes you back.

**Else if:** open **Else if branches** in Action settings and click **+ Else if**. Each branch has its own condition and
its own section on the canvas.

**Test it with sample values.** Below the builder, the condition is written as a sentence ("Reads: If $amount is greater
than 1000"). Click it to open the test:

1. A box appears for each variable the condition uses, filled with its default.
2. Type sample values and click **Test**.
3. The answer is **True: the steps inside If run.** or **False: the Else steps run.**, or the reason it could not be
   checked.

## 8. Loops

Add a **Loop** step. Its settings start with **Kind of loop**:

**N times**

- **Number of times** (required): a number or `$count`.
- **Counter (optional)**: a Number variable that receives 0, 1, 2 … or, with **Start counting at** `1`, 1, 2, 3 ….
- More options: **Count up by**.

**For each**

- **Go through**: *Each item in a list*, *Each row in a table*, *Each key and value in a dictionary*, *Each key in a
  dictionary*, *Each value in a dictionary or record*, or an iterator from a toolkit (for example the rows of an Excel
  sheet, the files of a folder, or the trigger loop's events).
- **Table to go through** / **List to go through**: pick the variable.
- **Store each row in** / **Store each item in**: the variable that receives the current one. Read columns as
  `$row.Name` or `$row["First Name"]`.
- **Columns into variables (optional)**: **+ Column** maps a column to its own variable, so later steps can say
  `$customer` instead of `$row.Customer`.
- **Only the first (optional)**: go through only this many.
- **Position (optional)**: a Number variable with 0 for the first item, 1 for the second ….
- More options: **Start at position**, **Stop after position (0 = first)**, **Take every**, **Go through in reverse order**.

**While**

- **Repeat while**: a condition, checked before each pass.
- **Check after each pass (the steps run at least once)**.

All kinds have, under More options, **Loop name** (so **Exit loop** and **Continue loop** can name an outer loop) and
**Safety limit** (the agent stops with an error after that many passes; 100000 by default).

Inside a loop, **Exit loop** leaves it and **Continue loop** skips to the next pass.

## 9. Error handling

### 9.1 Try / Catch / Finally

1. Add a **Try** step. It has **Try** and **Catch all errors** sections.
2. Put the steps that may fail in **Try**.
3. Put what to do on an error in **Catch**: log it, send a notice, skip the item.
4. In Action settings, **Catch blocks** lets you set, for each Catch:
   - **Errors to catch**: empty catches everything. Name a code (`FILE.NOT_FOUND`), a toolkit (`BROWSER.*`) or a kind
     of error from any toolkit (`NOT_FOUND`, `TIMEOUT`, `AUTH_FAILED`, `RATE_LIMITED` …). The list suggests codes.
     The first Catch that matches runs, so put specific ones first.
   - **Error message to**, **Line number to**, **All error details to** (a Dictionary with code, message,
     suggestedFix, line, action and stepId).
   - **Take a screenshot when the error is caught**: saved under `screenshots\` in the workspace.
   - **Raise the error again after this Catch (the agent still fails)**.
   - **+ Catch** adds another Catch block.
5. **+ Finally** (in the **Finally** section of the settings) adds steps that always run at the end, error or not:
   close a file, log out. **Remove Finally** works once it is empty.

### 9.2 Raise your own errors

**Throw error** stops with your own code and message, for example `BUSINESS.DUPLICATE` with "Invoice 123 was already
booked". It starts as `BUSINESS.ERROR`; the list offers `BUSINESS.REFUSED`, `BUSINESS.NOT_FOUND`, `DATA.INVALID` and
others. A Catch for `BUSINESS.*` handles them.

### 9.3 Continue on error and retries

In a step's **Error handling** section:

- **Continue the agent if this step fails**: the agent carries on after a failure of this one step.
- **Retries on temporary errors** (0 to 10): retries with growing pauses when the error is temporary (a busy server, a
  rate limit).
- **Timeout (seconds)**: how long the step may take; the placeholder shows the action's default.

## 10. Step groups, notes and comments

- **Step** (in Logic and flow) is a named group of steps. Give it a **Title**; it changes nothing when the agent runs
  but keeps long agents readable, and you can fold it.
- **Notes**: **Add note…** in the step menu, or the **Note** field in **General**. The note replaces the settings
  summary on the card. Leave it empty to remove it.
- **Comment** is a step that does nothing; use it for longer explanations.

## 11. Disable steps

Select steps and press **Ctrl+/** (or **Disable** in the toolbar or step menu). Disabled steps are greyed, marked
**Disabled**, and skipped when the agent runs. The same button then reads **Enable**.

## 12. Copy, paste and the shared clipboard

- **Copy** / **Cut** / **Paste** (Ctrl+C / X / V, toolbar or step menu) work within Studio. Pasted steps get new step
  ids and go after the selection. The variables they use come along when the target agent lacks them: "Pasted 3 steps
  and 1 variable(s)".
- **Duplicate** (Ctrl+D) copies the selection right after itself without touching the clipboard.
- **Copy to shared** / **Paste shared** use a clipboard kept in the workspace, so you can carry steps to another agent
  even after reloading Studio.
- Script and Prompt Skill editors have **Copy as an agent step**: open an agent and press Ctrl+V to add the step.

## 13. Undo and redo

**Undo** (Ctrl+Z, ↶) and **Redo** (Ctrl+Y or Ctrl+Shift+Z, ↷) cover your last 100 changes to the open agent. Undo history
starts again when you open another agent.

## 14. A worked example

Goal: read `data/orders.csv`, and log every order above 1,000.

1. **+ Create > Agent from a blueprint > Loop through a CSV file**. Name it *Big orders*.
2. Open **Variables**. Edit `csv_file` and set its default to `data/orders.csv`.
3. Select the **Comment** inside the loop and delete it (Del).
4. Click the **Repeat** section of the loop, then add **If**.
5. In the condition: left `$row.amount`, operator **is greater than**, right `1000`. Open "Reads: If …", type a sample
   value and click **Test**.
6. Click the If's **Then** section and add **Log message** with *Message* `Big order ${row.order_id}: ${row.amount}`.
7. Drag the existing **Log message** ("Row: ${row}") into **Else**, or delete it.
8. Click **Validate**; the Problems tab should say "No problems." Save with Ctrl+S.
9. Click **Run**. See [Running and debugging](04-studio-running-and-debugging.md).

## 15. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| A field is outlined and says **Required** | Fill it in, or choose the option that does not need it. |
| A number box turns red | The value is outside the allowed range shown in ⓘ. |
| "Step ids use letters, digits, '.', '-' and '_'." / "Another step already has id …" | Pick another step id in **General**. |
| "Variable names start with a letter or _ and use letters, digits and _." | Rename the variable. |
| "A block cannot be moved inside itself." | Drop the block outside its own sections. |
| The text `$name` appears literally in an email | Write `${name}` inside text. |
| The output list does not offer your variable | Its type cannot hold the result; create one with **+ New**. |
| "Unknown condition … (is its toolkit installed?)" / "Unknown iterator …" | The toolkit is missing or switched off. Ask your administrator. |
| "Invalid JSON: …" after **Apply JSON** | Fix the JSON text, or use **Format** to find the error. |

## Related guides

- [Studio basics](02-studio-basics.md)
- [Running and debugging](04-studio-running-and-debugging.md)
- [Child agents](09-child-agents.md)
- [Web and desktop automation](10-web-and-desktop-automation.md)
- [Office files, email and documents](11-working-with-office-files-email-and-documents.md)
- [AI in agents](13-ai-in-agents.md)
