# OneAgentStudio basics

**Audience:** everyone who builds agents: new builders, business analysts who automate their own work, and
developers who are new to ONEOFFICEAUTOMATION.

**You will learn to:** open OneAgentStudio, find your way around its screen, create and organise agents and other
items in your workspace, upload files, and work faster with keyboard shortcuts.

## Contents

1. [What OneAgentStudio is](#1-what-one-agent-studio-is)
2. [Open OneAgentStudio](#2-open-one-agent-studio)
3. [The Agents page](#3-the-agents-page)
4. [The screen when an agent is open](#4-the-screen-when-an-agent-is-open)
5. [Create agents, folders and other items](#5-create-agents-folders-and-other-items)
6. [Save, close, rename, copy, move and delete](#6-save-close-rename-copy-move-and-delete)
7. [Upload and download files](#7-upload-and-download-files)
8. [Find agents and actions](#8-find-agents-and-actions)
9. [Arrange the screen to suit you](#9-arrange-the-screen-to-suit-you)
10. [Keyboard shortcuts](#10-keyboard-shortcuts)
11. [If it goes wrong](#11-if-it-goes-wrong)
12. [Related guides](#related-guides)

---

## 1. What OneAgentStudio is

OneAgentStudio is the editor where you build, test and debug agents: the screen you get when an agent (or another
item) is open. Everything else is One CommandCenter, as in AA, where there is one Control Room: the **Private** tab of
**Build > Agents** is your own workspace, and opening an item there opens it in OneAgentStudio. It runs in your web
browser (Chrome or Edge), but only on your own computer: it reads and writes your private workspace, called **My
Workspace**.

- An **agent** is a list of **steps**. Each step runs one **action**, such as *Open workbook*, *Send email* or *Click
  element*.
- Actions come in **toolkits** (Excel, Browser, Email, Salesforce and so on). The Actions panel shows them in groups.
- Your workspace is private. When an agent is ready to share or to run on other computers, you **check it in** to One
  One CommandCenter (see [Publishing to One CommandCenter](15-publishing-to-command-center.md)).

Besides agents, the workspace holds **forms**, **workflows**, **document intelligence** items, **Prompt Skills**,
**scripts** (Python, PowerShell, VBScript, JavaScript) and ordinary files such as workbooks and CSV files.

## 2. Open OneAgentStudio

1. Make sure ONEOFFICEAUTOMATION is running on your computer (see [Getting started](01-getting-started.md)).
2. In One CommandCenter open **Build > Agents** and click the **Private** tab (it is there for people who may check
   agents in). You can also go to **http://localhost:8720/studio/** in Chrome or Edge.
   - If One CommandCenter runs on the same computer, your private workspace uses the sign-in you already have there.
   - If Studio runs on its own, the window that started it shows its address (`Open: http://localhost:<port>`).
3. The **Automation** page opens on your **Private** tab.
4. Click an agent (or make one with **Create ▾**). It opens in **OneAgentStudio**, the editor.

**What you see:** the same top bar and menu as everywhere in One CommandCenter. The top bar says **One
CommandCenter** on the Automation page and changes to **OneAgentStudio** only while an agent or other item is open in
the editor; the browser tab then shows the item's name. Close the item to come back to the Automation page.

> **Tip:** Studio only answers browsers on the same computer. Opening it from another machine gives
> "OneAgentStudio is only available on this computer". This is on purpose: your workspace is private.

The first time Studio starts with an empty workspace, it copies in the sample agents so you have something to look at.

## 3. The Agents page

The home page is **Agents** (**Build > Agents** in the menu on the left). As in One CommandCenter, it has two
tabs:

| Tab | What it holds |
| --- | --- |
| **Public** (left) | One CommandCenter's agent repository, shared with your team: the agents checked in, with their versions. Here you **Check out** an agent to change it. It needs a sign-in to One CommandCenter (when Studio runs on the same server, your browser's sign-in is used). |
| **Private** (right) | Your own workspace on this computer (My Workspace): agents, workflows, forms, scripts and files, in folders. Only you see it until you check an item in. |

The **Private** tab is for people who build agents. One CommandCenter's **Agents** page has the same two tabs; its
**Private** tab opens this page, and its **Create > New agent** opens **Create > Agent** here.

On the **Private** tab:

| Part | What it does |
| --- | --- |
| Folders (left) | The folder tree, with **Agents** at the top. Click a folder to list what is in it and to open its sub-folders. Folders show only in the tree, not as rows of the list. The **⋮** at the top of the pane, or a right-click on a folder, has **New folder**, **Rename**, **Upload**, **Download as zip** and more. |
| **Create ▾** | Makes a new item in the folder you picked: **Agent**, **AI agent**, **Workflow**, **Form**, **Document type**, **Prompt Skill**, **API agent**, **Python script** or **PowerShell script** (section 5). |
| **Type** | Shows only one kind of item, such as Agents or Folders. **Type: Any** shows everything. |
| **Columns** | Chooses the columns: **Type**, **Status**, **Platform**, **Version**, **Label**, **Code analysis**, **Size**, **Last modified** and **Modified by**. The ones from the repository show while you are signed in to One CommandCenter. |
| Tick boxes | Tick items to act on several at once: a bar then offers **Copy**, **Cut**, **Duplicate**, **Rename** and **Delete**. |
| **Status** | **New** (not in the repository yet), **Checked out** (yours to change; **Checked out · edited** once you changed it), **Checked in** (the same as in the repository), **Edited, not checked out** (check it out on the **Public** tab first; your changes are kept). |
| **⋮** on a row | Everything you can do with that item, including **Check in**, **Undo check-out** and **Revert edits**. Right-click a row for the same menu. Click a row (or press Enter on it) to open the item. |

The **Public** tab is laid out the same way: the same folder tree with **Agents** at the top, the number of items
first in its tools, and a **Version** column. Only the actions differ: **Check out**, **Versions** and **Live** on
**Public**; **New folder**, **Create** and the row menu on **Private**.

The **i** after the page title explains each part.

To come back to the home page at any time, click the **house** button in the top bar or the word **Agents** in the
breadcrumb. If the open item has unsaved changes, Studio asks **Save changes?** with **Save**,
**Don't save** and **Cancel**.

## 4. The screen when an agent is open

Open an agent (click it on the **Agents** page). The top bar now says **OneAgentStudio**, and the screen has
these parts:

```
┌ top bar: OneAgentStudio · breadcrumb · Unsaved · Search items · Save · Run · Debug · Check in · Close ──────────┐
│menu│ Actions            │  agent tab · steps toolbar (Undo, Redo, ⋯, List | Flow, …)│ Details                  │
│    │ (search, groups,   │  START ─ 1 step ─ 2 step … ─ END                         │ Properties · Variables · │
│    │  Triggers)         │──────────────────────────────────────────────────────────│ Triggers · Dependencies  │
│    │                    │  Problems · Run output · Debug values                    │                          │
└────┴────────────────────┴──────────────────────────────────────────────────────────┴──────────────────────────┘
```

### 4.1 Top bar

| Item | What it does |
| --- | --- |
| **OneAgentStudio** | The editor's name; every other page says One CommandCenter. |
| Breadcrumb | **Folder › agent name**. When you opened this agent from a parent agent, it shows the way back (see [Child agents](09-child-agents.md)). A **Run by** note lists agents that run this one. |
| **Unsaved** | Appears when you have changes that are not saved. |
| **Search items in the workspace** | Finds agents and other items (Ctrl+K). |
| **Save** | Saves the agent (Ctrl+S) and checks it; problems show on the **Problems** tab. The one filled (main) button of the bar. |
| **Run** and **Debug** | Run the agent on this computer, or run it and stop at breakpoints (see [Running and debugging](04-studio-running-and-debugging.md)). White buttons with a small coloured icon; in the Run and Debug dialogs they are the filled main button. |
| **Stop run** | Takes the place of **Run** and **Debug** while a run goes, in red; stops the run. |
| **Check in** | Checks the agent in to One CommandCenter (see [Publishing](15-publishing-to-command-center.md)). |
| **Close** | Closes the agent and returns to the **Agents** page; asks to save changes first. |

### 4.2 The left side

The menu on the far left is One CommandCenter's menu, narrow while an agent is open. Next to it, the **Actions** panel
lists every action in groups by toolkit, with a count each. Type in **Search actions** and press Enter to add the best
match after the selected step, or click an action, or drag it to where it goes. The **Triggers** group lists the triggers
an agent can have (see [Running and debugging](04-studio-running-and-debugging.md#10-local-triggers)).

**Simple mode** (the **Simple** switch at the top of the Actions panel) is for business users who automate their own
work. The Actions panel then shows only everyday toolkits under plain names: Excel files, e-mail and Outlook,
Microsoft 365, websites, files and folders, PDF and Word files, dates, text, asking a person, and forms. The building
blocks are If, Loop, Assign, Comment, Log, Delay, Break and Try, and triggers are hidden. The agent itself is an
ordinary agent: with Simple mode off you see and use every action. Studio remembers the choice for you on this
computer. A Citizen Developer seat cannot check in agents that use scripts or code (Administrator's guide, Licenses).

### 4.3 The steps

The steps toolbar has **Undo** and **Redo**, the **⋯** menu (**Select all**, **Find in this agent**, **Find and
replace…** (Ctrl+H), fold and unfold, **Check the agent**, **Saved connections…** and **Custom connectors…**), **List** and
**Flow**, the **Assistant** (build steps from a description, or explain the agent), **Record** and **Capture** (these three
sit together as one group of icon buttons), and **Full screen**.

**List** and **Flow** are two tabs: the one shown is underlined.

**Clicking a step** (as in AA's editor): a click on a step chooses it and opens its **Properties** in **Details**; a
second click on the same step folds **Details** away, so the steps get the whole width, and the next click opens it
again. Clicking another step while Details is open shows that step's properties. Each step also shows its command's
**Docs** and **Video** beside its **⋮** when it is chosen or under the pointer, and the step menu has **Docs for this
command** and **Video for this command**.

More on the step list:

- **Breakpoint**: click a step's line number (or press F9); click it again to remove it. A debug run stops there.
- **Run from here**: the chosen step shows this button; earlier steps are skipped and their values come from the last run.
- **+ Add step**: under the chosen step (and the one under the pointer); it opens **Actions** with the cursor in its
  search, and the action you pick goes after that step.
- **Duplicate** and **Disable** in the step tools say what they do; the common ones (undo, redo, cut, copy, delete)
  keep their icons, with their name and key on hover.
- Pointing at an action in **Actions** (or moving to it with the keyboard) shows what it does at the panel's foot.
- Type badges say the type in a word: **Text**, **Number**, **Yes/No**, **Date**, **List**, **Dictionary**, **Table**,
  **Secret** (a credential), **Duration** and so on.
- **Add a subflow** is under the steps (and **+ Subflow** beside the subflow tabs once there is one).

- **List** shows the steps as numbered lines, one under another, between **START** and **END**. Blocks (If, Loop, Try,
  Step) have their sections indented below them, labelled **Then**, **Else**, **Repeat**, **Try**, **Catch all
  errors**, **Finally (always)** and so on.
- **Flow** shows the same agent as a diagram: boxes joined by lines, with branches side by side.

Select **START** and click actions to add them as step 1. Each step shows its line number (click it to set a
breakpoint), a fold arrow on blocks, the action's icon and name, a one-line summary of its settings (or its note) and
badges such as **Disabled** or the number of problems. F2 renames the selected step (its *Shown on the step* text).

### 4.4 The Details panel

On the right, **Details** has four tabs:

| Tab | Shows |
| --- | --- |
| **Properties** | The selected step's settings: its inputs, outputs, error handling and note. With **no step selected** (press Esc), the agent's own id (under **Advanced: the agent's id**; One CommandCenter and Run agent steps know the agent by it, so change it only on purpose with **Change the id…**) and **Description** (people who run it and AI agents read it). |
| **Variables** | The agent's variables: inputs, outputs and those inside the agent, plus system variables and Shared Global values. |
| **Triggers** | Triggers that start the agent on this computer while the workspace runs. |
| **Dependencies** | What the agent needs and who uses it: **Child agents and agent tools**, **Scripts**, **Prompt Skills**, **Files** and **Used by**. |

### 4.5 The Run dock

At the bottom, the **Run dock** has three tabs:

| Tab | Shows |
| --- | --- |
| **Problems** | What stops the agent from running. **Check now** looks again without saving; click a problem to go to its step. |
| **Run output** | What happens while the agent runs: each step, its time and the result; while a debug run waits, Continue, Step over, Step into and Step out. |
| **Debug values** | The variables' values at the step where a debug run is waiting. |

The **Dock** list moves the dock: **Bottom**, **Right**, **Floating** (drag it by its title bar) or **Mini bar**.
**Minimise** turns it into the mini bar; **Expand** on the mini bar brings it back.

## 5. Create agents, folders and other items

### 5.1 Create an agent

1. On the **Agents** page (**Private** tab), pick the folder where the agent should go (or none, for the top of
   the workspace).
2. Press **Create ▾** and choose **Agent**. The **New agent** dialog opens, in two parts.
3. On the right, **Start from** is **Blank** (an empty agent) or **Template** (a ready-made agent). With **Template**,
   the left part lists the templates as a table: **Name**, **Category** (the toolkit it mostly uses) and **Steps**.
   Click a heading to sort by it, type in **Search … templates**, or choose a **Category** (each with its count).
   Click a template to choose it; its description shows under the table.
4. Type a **Name**.
5. **Save in** shows the folder tree, with **Agents** at the top, and under it the path the agent will get. Click
   another folder to put it there.
6. Press **Create and edit** (**Cancel** or Esc closes the dialog).

**What you see:** the toast "Agent created", the new agent in the tree, and an empty canvas with the text "This agent
has no steps yet. Drag an action here from Actions, click an action, or press Record." The side panel switches to
**Actions**.

The **Create ▾** menu also offers (each opens the same dialog, with **Save in** for the folder; the template part
shows only for items that have templates):

| Entry | Use it for |
| --- | --- |
| **AI agent** | A goal; the model picks which of your agents to use (see [AI in agents](13-ai-in-agents.md)). |
| **Workflow** | A chain of agents, people and approvals. |
| **Form** | A screen people fill in or approve (used by workflows and by the Interactive forms toolkit). |
| **Document type** | Fields to read from documents with AI (see [AI in agents](13-ai-in-agents.md)). |
| **Prompt Skill** | A reusable, tested AI prompt. Prompt Skills always go into the `ai-skills` folder. |
| **API agent** | An agent that runs on the server with no desktop. |
| **Python script**, **PowerShell script** | A script file that agents call with the Scripts toolkit. |

A blueprint (a ready-made pattern such as Loop through a CSV file or Agent with error handling) is a template: choose
**Template** in the **New agent** dialog's **Start from** and pick it from the list.

Blueprints for common agent shapes:

| Blueprint | What you get |
|---|---|
| **Transactional framework (work queue)** | Subflows **Init**, **Get item**, **Process** and **End**. It takes items from the queue named in the `queue` input one at a time, marks each one completed, and marks it failed. A `BUSINESS.*` error (use **Throw**, for example `BUSINESS.REJECTED`) fails the item at once. Any other error puts it back for another try, up to `max_attempts`. The outputs `processed` and `failed` give the counts. An error in Init or End stops the agent through its **On error** handler. Put your steps in **Process**, using `item_data`. |
| **Attended: ask, confirm, act** | For an agent a person starts at their desk. It asks for a reference, asks for a yes before acting, and shows the result. Cancelling ends the run quietly. |
| **Attended: pick a file and process it** | The person chooses a file, and the agent works on it and shows the outcome. An error is shown in a message and still fails the run. |

### 5.2 Create a folder

1. Select the parent folder in the Explorer.
2. Click **+ Folder** (or right-click a folder and choose **New folder**).
3. Type the name. The toast says "Folder created".

### 5.3 The Explorer menu

Right-click any item in the Explorer (or click its **⋮** button, or press Shift+F10) for:

- **New folder**, **New agent here**, **New form here**, **New workflow here**, **New Python script here**,
  **New PowerShell script here**
- **Rename** (F2), **Copy** (Ctrl+C), **Cut** (Ctrl+X), **Paste** (Ctrl+V), **Duplicate**, **Delete** (Del)
- **Move to…**, **Copy path**, **Open in File Explorer**
- **Upload file(s)…**, **Download**

Select several items with Ctrl+click (one at a time) or Shift+click (a range). The menu then acts on all of them, for
example "Delete 3 items".

## 6. Save, close, rename, copy, move and delete

| Task | How |
| --- | --- |
| **Save** | Click **Save** or press Ctrl+S. The toast says "Saved <path>". When the agent still has problems it says "(n problem(s) to fix)" in red; the file is saved anyway. |
| **Close** | Click ✕ in the top bar. Unsaved changes: **Save changes?** (Ctrl+W is kept by the browser for closing its tab.) |
| **Rename** | Select the item and click **Rename** (or press F2). Type the new name. The open agent follows the rename. |
| **Copy to another folder** | Right-click > **Copy**, then right-click the target folder > **Paste**. |
| **Duplicate** | Right-click > **Duplicate**. A duplicated agent gets its own agent id, so it does not clash with the original. |
| **Move** | Drag the item onto a folder, or right-click > **Move to…** and pick the folder, or **Cut** and **Paste**. |
| **Delete** | Select and click **Delete** (or press Del). Confirm "Move … to the workspace trash?". |

> **Tip:** Delete moves items to the hidden `.trash` folder of your workspace; nothing is lost at once. Studio has no
> restore button: to get an item back, open the workspace folder (right-click the top **Workspace** row >
> **Open in File Explorer**) and move it out of `.trash` yourself.

> **Tip:** Studio warns you before you leave the page with unsaved changes, and asks "Discard unsaved changes to the
> open agent?" when you open another agent.

## 7. Upload and download files

Agents often need files: a workbook to read, a template letter, a list of customers. Keep them in your workspace and
refer to them by their path (for example `data/orders.csv`).

**Upload:**

1. Right-click the folder that should hold the files and choose **Upload file(s)…**.
2. Choose one or more files in the browser's file dialog.
3. The toast says "Uploaded n file(s) to <folder>".

A file whose name is taken gets " (2)" added. Each file may be up to 50 MB.

**Download:** right-click an item and choose **Download**. A file downloads as it is; a folder downloads as a `.zip`.

**Use a file in a step:** in a **File** field, start typing; Studio suggests paths from your workspace. Clicking a file
that Studio cannot edit shows a toast with its size and the path agents use to read it. Text files (`.json`, `.txt`,
`.csv`, `.xml`, `.md`, `.sql`, `.html`, `.ini`, `.yml`, scripts) open in Studio's text editor.

## 8. Find agents and actions

- **Search agents** (box above the Explorer tree): "Search agents by name, description or action". Type two or more
  letters. Results show which parts matched; click one to open it.
- **Search actions** (top of the Actions panel): type a word such as `pdf`, `email` or `click`. Groups open to show the
  matches. **Recently used** lists the last actions you added.
- **Enter in the search** adds the best match after the selected step (the same name, then a name starting with the
  words, then the first found). Ctrl+K goes to the workspace search at the top, not to the actions.

## 9. Arrange the screen to suit you

- Drag the thin bars between panels to make them wider or narrower. Studio remembers the sizes.
- **Theme**: One Dark (default), One Light (blue), Ocean teal, Graphite, Sunrise orange.
- **Density**: Compact (more steps on screen) or Comfortable.
- **Language**: English, Deutsch, Français, Español, 日本語. Names, ids and messages from the server stay in English.
- **Expand all** and **Collapse all** in the canvas toolbar open or fold every block. Left and Right arrow keys fold or
  open the selected block.

These choices are kept in your browser.

## 10. Keyboard shortcuts

Press **?** (or choose **Keyboard shortcuts** in the Display menu) to see every key, grouped by area. In that window you
can switch off the single-key shortcuts (`[`, `]`, `/` and `?`), for example if you use speech input. Keys work while
the page has focus; text fields keep their own keys, and keys the browser keeps for itself are never used.

| Keys | Action |
| --- | --- |
| Ctrl+K | Go to the search |
| Ctrl+S or Ctrl+Enter | Save the open item |
| Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) | Undo / Redo (outside text fields) |
| F5 | Debug; while stopped at a step, continue |
| Ctrl+F5 / Shift+F5 | Run without stopping at breakpoints / Stop the run |
| F10 or F6 / F11 / Shift+F11 | While stopped at a step: step over / step into / step out |
| F9 / Shift+F9 | Breakpoint on the selected steps / remove every breakpoint |
| ↑ / ↓, Shift+↑ / Shift+↓ | Select the step above or below / extend the selection |
| Ctrl+A, Esc | Select every step / clear the selection |
| Enter | Go to the selected step's settings (Details > Properties) |
| Space | Show or hide the Details panel |
| Ctrl+C, Ctrl+X, Ctrl+V | Copy, cut, paste steps (paste goes after the selection) |
| Ctrl+D, Ctrl+/, Del | Duplicate, switch off or on, delete the selected steps |
| Alt+↑ / Alt+↓ | Move the selected steps up or down |
| `[` or ← / `]` or → | Fold / unfold the selected block |
| Ctrl+[ / Ctrl+] | Fold / unfold every block |
| Ctrl+Shift+F | Find in this agent (the steps found are selected; Enter goes to the next) |
| Ctrl+H | Find and replace in the steps' settings (the whole agent or the selected steps) |
| F2 | Rename the selected step (its **Shown on the step** text) |
| Ctrl+Shift+R | Record |
| Shift+F10 or the menu key | Open the step's menu |
| F2 or Ctrl+Space | Insert a value (a variable) in a field |
| F2, Del, Ctrl+C/X/V, Ctrl+A, Esc in the Automation list | Rename, delete, copy, cut, paste, select all, clear the selection |

In One CommandCenter's lists: `/` goes to the list's search, ↓ and ↑ move between rows, Enter opens a row and Shift+F10
opens its ⋯ menu. **?** or **Help > Keyboard shortcuts** shows them.

## 11. If it goes wrong

| What you see | What to do |
| --- | --- |
| "Cannot reach OneAgentStudio server: …" | ONEOFFICEAUTOMATION is not running, or was stopped. Start it again and reload the page. |
| The page does not open; "port … is already in use" in the start window | Another Studio is still running. Close it, or start this one on another port (`--port`). |
| "OneAgentStudio is only available on this computer" | Open Studio on the computer where it runs. |
| "Open or create an agent first (Explorer tab)." | You clicked an action with no agent open. Open or create one. |
| "Select a file or folder to rename." | Select an item in the Explorer first; the top **Workspace** row cannot be renamed. |
| An upload fails with "Uploads are limited to 50 MB per file" | Split or compress the file, or keep it on a shared drive and use its full path. |
| A deleted item is needed again | Look in the workspace's `.trash` folder (see section 6). |

## Related guides

- [User guide overview](00-README.md)
- [Getting started](01-getting-started.md)
- [Building agents in Studio](03-studio-building-agents.md)
- [Running and debugging](04-studio-running-and-debugging.md)
- [Child agents](09-child-agents.md)
- [Publishing to One CommandCenter](15-publishing-to-command-center.md)
