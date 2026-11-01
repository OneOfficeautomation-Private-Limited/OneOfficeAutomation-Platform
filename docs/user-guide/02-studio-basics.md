# One AgentStudio basics

**Audience:** everyone who builds agents: new builders, business analysts who automate their own work, and
developers who are new to ONEOFFICEAUTOMATION.

**You will learn to:** open One AgentStudio, find your way around its screen, create and organise agents and other
items in your workspace, upload files, and work faster with keyboard shortcuts.

## Contents

1. [What One AgentStudio is](#1-what-one-agent-studio-is)
2. [Open One AgentStudio](#2-open-one-agent-studio)
3. [The Automation home](#3-the-automation-home)
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

## 1. What One AgentStudio is

One AgentStudio is the place where you build, test and debug agents. It runs in your web browser (Chrome or Edge),
but it only works on your own computer: it reads and writes your private workspace, called **My Workspace**.

- An **agent** is a list of **steps**. Each step runs one **action**, such as *Open workbook*, *Send email* or *Click
  element*.
- Actions come in **toolkits** (Excel, Browser, Email, Salesforce and so on). The Actions panel shows them in groups.
- Your workspace is private. When an agent is ready to share or to run on other computers, you **check it in** to One
  One CommandCenter (see [Publishing to One CommandCenter](15-publishing-to-command-center.md)).

Besides agents, the workspace holds **forms**, **workflows**, **document intelligence** items, **Prompt Skills**,
**scripts** (Python, PowerShell, VBScript, JavaScript) and ordinary files such as workbooks and CSV files.

## 2. Open One AgentStudio

1. Make sure ONEOFFICEAUTOMATION is running on your computer (see [Getting started](01-getting-started.md)).
2. Open Chrome or Edge and go to **http://localhost:8720/studio/**.
   - If One CommandCenter runs on the same computer, Studio uses the sign-in you already have there.
   - If Studio runs on its own, the window that started it shows its address (`Open: http://localhost:<port>`).
3. The **Automation** home opens.

**What you see:** a dark bar across the top with the ONEOFFICEAUTOMATION logo and the words **One AgentStudio**, a
narrow rail of icons on the left, and the list of everything in your workspace in the middle.

> **Tip:** Studio only answers browsers on the same computer. Opening it from another machine gives
> "One AgentStudio is only available on this computer". This is on purpose: your workspace is private.

The first time Studio starts with an empty workspace, it copies in the sample agents so you have something to look at.

## 3. The Automation home

The home page is **Automation** (in the menu on the left: **Build > Automation**). As in One CommandCenter, it has two
tabs:

| Tab | What it holds |
| --- | --- |
| **Public** (left) | One CommandCenter's agent repository, shared with your team: the agents checked in, with their versions. Here you **Check out** an agent to change it. It needs a sign-in to One CommandCenter (when Studio runs on the same server, your browser's sign-in is used). |
| **Private** (right) | Your own workspace on this computer (My Workspace): agents, workflows, forms, scripts and files, in folders. Only you see it until you check an item in. |

The **Private** tab is for people who build agents. One CommandCenter's own **Automation** page has the same two tabs;
its **Private** tab opens this page in One AgentStudio.

On the **Private** tab:

| Part | What it does |
| --- | --- |
| Folders (left) | Pick a folder to see what is in it. The **⋮** at the top of the pane, or a right-click on a folder, has **New folder**, **Rename**, **Upload**, **Download as zip** and more. |
| **Create ▾** | Makes a new item in the folder you picked: **Agent**, **AI agent**, **Workflow**, **Form**, **Document type**, **Prompt Skill**, **API agent**, **Python script** or **PowerShell script** (section 5). |
| **Type** | Shows only one kind of item, such as Agents or Folders. **Type: Any** shows everything. |
| **Columns** | Chooses the columns: **Type**, **Status**, **Platform**, **Version**, **Label**, **Code analysis**, **Size**, **Last modified** and **Modified by**. The ones from the repository show while you are signed in to One CommandCenter. |
| Tick boxes | Tick items to act on several at once: a bar then offers **Copy**, **Cut**, **Duplicate**, **Rename** and **Delete**. |
| **Status** | **New** (not in the repository yet), **Checked out** (yours to change; **Checked out · edited** once you changed it), **Checked in** (the same as in the repository), **Edited, not checked out** (check it out on the **Public** tab first; your changes are kept). |
| **⋮** on a row | Everything you can do with that item, including **Check in**, **Undo check-out** and **Revert edits**. Right-click a row for the same menu. Click a row (or press Enter on it) to open the item. |

The **i** after the page title explains each part.

To come back to the home page at any time, click the **house** button in the top bar (Automation home) or the word
**Automation** in the breadcrumb. If the open item has unsaved changes, Studio asks **Save changes?** with **Save**,
**Don't save** and **Cancel**.

## 4. The screen when an agent is open

Open an agent (click it on the home page or in the Explorer). The screen fills with these parts:

```
┌──────────────────────── top bar: breadcrumb · Unsaved · panel toggles · Save · Validate · Assistant · Debug · Run ┐
│rail│ side panel        │  Canvas | Agent settings | Dependencies | JSON           │ Action settings          │
│    │ (Explorer,        │  canvas toolbar                                          │ (settings of the         │
│    │  Actions,         │  Start ─ step 1 ─ step 2 … ─ End                         │  selected step)          │
│    │  Variables,       │──────────────────────────────────────────────────────────│                          │
│    │  Triggers)        │  Run dock: Run · Problems · Run log · Variables          │                          │
└────┴───────────────────┴──────────────────────────────────────────────────────────┴──────────────────────────┘
```

### 4.1 Top bar

| Item | What it does |
| --- | --- |
| Breadcrumb | **Automation / folder / agent name**. When you opened this agent from a parent agent, it shows the way back (see [Child agents](09-child-agents.md)). A **Run by** note lists agents that run this one. |
| ✕ (Close) | Closes the item and returns to the home page; asks to save changes first. |
| **Unsaved** | Appears when you have changes that are not saved. |
| **One CommandCenter** | Opens One CommandCenter in another tab (shown when Studio runs together with it). |
| Panel toggles | Three small buttons: **Left panel**, **Run dock**, **Action settings**. Each shows or hides that panel. |
| Theme | **Light** or **Dark**. |
| **Save** | Saves the open item (Ctrl+S). |
| **Validate** | Checks the agent and shows the result in the **Problems** tab. |
| **Assistant** | Builds steps from a description, or explains the agent in plain words (see [AI in agents](13-ai-in-agents.md)). |
| **Debug** | Runs with the debugger (see [Running and debugging](04-studio-running-and-debugging.md)). |
| **Run** | Runs the agent. |

### 4.2 The rail and the side panel

The rail on the far left switches the side panel:

| Rail button | Side panel |
| --- | --- |
| **Explorer** (folder) | Your workspace as a tree, with **+ Create**, **+ Folder**, **Rename**, **Delete**, **↻** (Refresh) and **Agent check-in**. Below the tree: the workspace folder path and the **One CommandCenter** section. |
| **Actions** (four squares) | Every action you can add, in groups under section headings (Flow and control, Data and variables, Office documents, Web and desktop, APIs and databases, AI and so on). |
| **Variables** (braces) | The open agent's variables, grouped as **Inputs**, **Outputs**, **Inside the agent**, plus **System variables** and **Shared Global values**. |
| **Triggers** (lightning) | Local triggers that start agents while Studio runs. With an agent open they are in **Details > Triggers**, and the Actions panel has a **Triggers** group that also reaches One CommandCenter's triggers (see [Running and debugging](04-studio-running-and-debugging.md#10-local-triggers)). |

Click the active rail button again to hide the side panel.

### 4.3 The canvas

The **Canvas** tab is where you build. Above it are three more tabs:

- **Agent settings**: **Agent id**, **Name**, **Kind** (Agent or Headless API agent), **Description** and **File**.
  The description matters: AI agents read it to decide when to use your agent.
- **Dependencies**: what the agent needs and who uses it: **Child agents and agent tools**, **Scripts**,
  **Prompt Skills**, **Files** and **Used by**. Items missing from the workspace are marked **Not in the workspace**.
- **JSON**: the agent as text. **Format** tidies it; **Apply JSON** loads your edits into the canvas.

The canvas has two views, chosen with **List** and **Flow** in the canvas toolbar:

- **List** shows the steps as numbered cards, one under another. Blocks (If, Loop, Try, Step) have their sections
  indented below them, labelled **Then**, **Else**, **Repeat**, **Try**, **Catch all errors**, **Finally (always)** and
  so on.
- **Flow** shows the same agent as a diagram: boxes joined by lines, with branches side by side. You can drop actions on
  a box, on a line, or on **Start** and **End**.

Every canvas starts with **Start** and ends with **End**. Select **Start** and click actions to add them as step 1;
select **End** to add them at the end.

Each step card shows:

- a drag grip (⋮⋮), the **line number** (click it to set a breakpoint), and a fold arrow on blocks;
- a coloured icon, the action's name and its toolkit;
- a one-line summary of its settings, or the step's **note** when it has one;
- badges: **Disabled**, **n steps hidden** (a folded block), and **!n** when the step has problems;
- the **⋮** button that opens the step menu.

The text at the right end of the canvas toolbar tells you where clicked actions will go, for example "Clicked actions go
after the selected step."

### 4.4 The Action settings panel

On the right, **Action settings** shows the settings of the selected step: its inputs, outputs, **Error handling** and
**General** (step id and note). See [Building agents](03-studio-building-agents.md). **Collapse** hides the panel.

### 4.5 The Run dock

At the bottom, the **Run dock** has four tabs:

| Tab | Shows |
| --- | --- |
| **Run** | The agent's inputs, the **Run agent** button, and the result of the last run (status, time, error, outputs). |
| **Problems** | Validation problems. Click a step id to jump to the step. The count shows on the tab. |
| **Run log** | Every event of the last run with its time. |
| **Variables** | The last value of each variable in the last run, and the outputs. Credentials always show as `****`. |

The **Dock** list moves the dock: **Bottom**, **Right**, **Floating** (drag it by its title bar) or **Mini bar**.
**Minimise** turns it into the mini bar; **Expand** on the mini bar brings it back.

## 5. Create agents, folders and other items

### 5.1 Create an agent

1. On the **Automation** page (**Private** tab), pick the folder where the agent should go (or none, for the top of
   the workspace).
2. Press **Create ▾** and choose **Agent**. The **New agent** dialog opens.
3. Type a **Name**. In **Start from**, keep **Blank agent** or choose a blueprint (type to search).
4. **Folder** shows where the agent will go. To put it somewhere else, press **Change** and choose another folder from
   the list (type to search; **Workspace** is the top). You do not have to close the dialog.
5. Press **Create and edit**.

**What you see:** the toast "Agent created", the new agent in the tree, and an empty canvas with the text "This agent
has no steps yet. Drag an action here from Actions, click an action, or press Record." The side panel switches to
**Actions**.

The **Create ▾** menu also offers (each opens the same kind of dialog, with **Change** for the folder):

| Entry | Use it for |
| --- | --- |
| **AI agent** | A goal; the model picks which of your agents to use (see [AI in agents](13-ai-in-agents.md)). |
| **Workflow** | A chain of agents, people and approvals. |
| **Form** | A screen people fill in or approve (used by workflows and by the Interactive forms toolkit). |
| **Document type** | Fields to read from documents with AI (see [AI in agents](13-ai-in-agents.md)). |
| **Prompt Skill** | A reusable, tested AI prompt. Prompt Skills always go into the `ai-skills` folder. |
| **API agent** | An agent that runs on the server with no desktop. |
| **Python script**, **PowerShell script** | A script file that agents call with the Scripts toolkit. |

A blueprint (a ready-made pattern such as Loop through a CSV file or Agent with error handling) is chosen in the
**New agent** dialog's **Start from**.

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
| Ctrl+Space | Insert a variable in a field |
| F2, Del, Ctrl+C/X/V, Ctrl+A, Esc in the Automation list | Rename, delete, copy, cut, paste, select all, clear the selection |

In One CommandCenter's lists: `/` goes to the list's search, ↓ and ↑ move between rows, Enter opens a row and Shift+F10
opens its ⋯ menu. **?** or **Help > Keyboard shortcuts** shows them.

## 11. If it goes wrong

| What you see | What to do |
| --- | --- |
| "Cannot reach One AgentStudio server: …" | ONEOFFICEAUTOMATION is not running, or was stopped. Start it again and reload the page. |
| The page does not open; "port … is already in use" in the start window | Another Studio is still running. Close it, or start this one on another port (`--port`). |
| "One AgentStudio is only available on this computer" | Open Studio on the computer where it runs. |
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
