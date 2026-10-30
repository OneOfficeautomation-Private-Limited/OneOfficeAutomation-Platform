# 14 One Agent Studio

## What this covers

The feature specification of One Agent Studio, the browser-based designer served at `/studio/`: the layout and the
Automation home, the Explorer and the private workspace, the editor views of an agent, the canvas and its editing
commands, the Actions and Variables panels, the step settings panel (choices, tick boxes, More options, error
handling), validation and the Problems tab, running and the Run window, debugging with breakpoints, Run from here (with
the last run's values or with default values) and Run this step only, opening child agents with breadcrumbs and the
"Run by" note, recording and capture, the assistant, local triggers, and the link to One Command Center (connect,
check-out, check-in).

## Contents

1. Layout
2. Automation home and the Create menu
3. Explorer and the workspace
4. Editor views of an agent
5. The canvas
6. Actions panel and Quick add
7. Variables panel
8. Step settings panel
9. Validation and the Problems tab
10. Running an agent
11. The Run window and the Run dock
12. Debugging and breakpoints
13. Run from here and Run this step only
14. Child agents: opening, breadcrumbs and "Run by"
15. Recorder, Capture and Selectors to objects
16. Connectors and connections
17. The assistant
18. Local triggers
19. One Command Center link
20. Other item editors
21. Preferences and keyboard

## 1. Layout

| Area | Contents |
| --- | --- |
| Top bar | Automation home, breadcrumbs of the open item, Close, "Unsaved" marker, One Command Center link (opens the console in another tab), toggles for the left panel, Run dock and Action settings, theme and density, One Model Connections…, Connectors…, Save, Validate, Assistant, Debug, Run |
| Left rail | Explorer, Actions, Variables, Triggers |
| Centre | The Automation home, the agent editor, or another item's editor |
| Right | Action settings of the selected step (collapsible) |
| Bottom | Run dock with tabs Run, Problems (with a count), Run log, Variables |

Validate, Debug and Assistant show only while an agent is open; Run shows for agents and scripts.

## 2. Automation home and the Create menu

The home lists the workspace's items. **+ Create** offers: Agent, Agent from a blueprint, Headless API agent, Form,
Workflow, Document intelligence, Prompt Skill, Python script, PowerShell script, VBScript, JavaScript, and Folder.
New items go into the selected folder (or the folder of the selected file). Close asks to save unsaved changes first
(Save or Don't save).

## 3. Explorer and the workspace

- The workspace is a folder on the designer's computer (shown at the bottom of the Explorer); it is private.
- Selection: a click selects and opens; Ctrl+click adds or removes; Shift+click selects the visible range.
- Item menu (right-click or ⋮), acting on the selection: Rename, Move to… (a folder dialog), Cut, Copy, Paste (copy, or
  move after Cut; an open agent follows a move), Copy path, Upload file(s)… (a taken name gets " (2)"), Download (files
  as they are, folders as .zip), Delete (to the workspace trash, after confirmation).
- **Search** finds agents by name, description or action.
- Agents checked out from the Command Center are copied under `Control Room/<repository path>`; the mapping is
  remembered so check-in sends the copy back to the right path.

## 4. Editor views of an agent

| Tab | Contents |
| --- | --- |
| Canvas | The steps as cards (section 5) |
| Agent settings | Name, id ("used by parent agents and AI agents to call this agent"), description (read by AI agents), file (rename or move in the Explorer), and other agent-wide settings |
| Dependencies | The agents it calls and the toolkits it uses |
| JSON | The agent document; **Apply JSON** replaces the agent with edited JSON (it must be a JSON object), **Format** tidies it |

## 5. The canvas

- Steps are numbered from the top, counting steps inside blocks, in the order shown; errors and Catch "Line number to"
  use the same numbers. Block steps (If, Loop, Try, Step group, Parallel) show their sections: Then / Else if / Else,
  Try / each Catch / Finally, Repeat. An empty Finally is not shown.
- Cards show a summary in words (conditions as sentences, loops as "for each …", selectors by their telling end), or
  the step's note instead when it has one. Steps with problems carry a flag with the count.
- Start and End markers: selecting one makes clicked actions go first or last; the insertion hint says where actions
  will go.
- Selection: click, Ctrl+click, Shift+click (range within a list), Up and Down, Shift+Up/Down, Ctrl+A (every step).
- Commands (toolbar, step menu and keys) act on the whole selection: Undo (Ctrl+Z), Redo (Ctrl+Y), Cut (Ctrl+X), Copy
  (Ctrl+C), Paste after (Ctrl+V), Duplicate (Ctrl+D; copies get new ids), Delete (Del), Disable/Enable (Ctrl+/), Move up
  and down (Alt+Up/Down; selected neighbours move together), Copy to shared clipboard and Paste from shared clipboard
  (between agents), Expand all, Collapse all, Add note….
- Drag and drop: library actions are inserted and steps moved; a card takes drops on its top half (before) and bottom
  half (after, or into an open block); the canvas scrolls near its edges.
- The step menu (⋮, right-click, Shift+F10) also has Run from here, Run from here with default values, Run this step
  only, Toggle breakpoint (F9), and, on a Run Agent step, **Open the agent it runs**.

## 6. Actions panel and Quick add

The Actions panel lists every action grouped by technology under sections (Flow and control, Data and variables, Data
formats, Files and folders, Office documents, Web and desktop, Email and collaboration, APIs and databases, Business
applications, System and security, Scripts, AI …), with a search and Collapse all. A toolkit without a listed group gets
its own. **+ Quick add** (Ctrl+K or `/`) finds an action by typing and inserts it with Enter.

## 7. Variables panel

- Create or edit a variable: name, type, use as input and/or output, default value (with an editor for the type: text,
  number, true/false, date and time, list, keys and values, table grid, file path with suggestions, or JSON; "Edit as
  JSON" switch), description, required, and **Business figure (KPI)** on outputs.
- Renaming a variable updates every `$name`, `${name}`, output and loop or catch name that uses it.
- System variables (`$sys`) and shared values (`$global`) are listed for insertion.
- A {x} button on text fields inserts a variable (fitting types first); Ctrl+Space or typing `$` does the same.

## 8. Step settings panel

For the selected step, in this order:

1. **Inputs** — required inputs and the key choice for the action in view; inputs hidden when they do not apply to the
   current choices; tick boxes that reveal further fields and choices that show only the chosen option's fields (as
   the toolkit lays them out); **More options** (collapsed, with a count) for rarely used inputs and advanced ones
   (self-healing alternatives, session names). Each field has help on hover (ⓘ). An empty required field says so at once.
2. Special editors replace JSON where it helps: the If builder (rows joined with AND or OR, groups, or an expression,
   shown as a sentence, with **Test**), the Loop panel (kind of loop first: N times, For each, While; then only its
   fields), Assign variable (Text or value, or Calculation), list and table editors, command lines, window choice
   (browser, application or variable; title match wildcard, contains, equals or regex; picked from open windows).
3. **Connection** — on connector steps, a saved connection replaces the address and sign-in inputs.
4. **Outputs** — the variables that receive the results, listing only variables of a fitting type (a suggested new
   variable for actions that return something).
5. **Catch blocks** and **Finally** on Try steps — codes (with suggestions such as `BROWSER.*`, `FILE.NOT_FOUND`,
   `BUSINESS.*` and the cross-toolkit kinds), screenshot, variables for the error, message and line, rethrow.
6. **Error handling** — Disabled (skip this step), Continue the agent if this step fails, Retries on temporary errors
   (0-10, exponential from 500 ms), Timeout (seconds) with the action's default shown.
7. **General** — the step note.
8. Move up, Move down, Duplicate, Delete.

Text with a bare `$name` inside other text gets a note that it is sent literally and `${name}` inserts the value.

## 9. Validation and the Problems tab

- The open agent is validated automatically shortly after each change and when opened; **Validate** checks at once and
  reports the count.
- **Problems** lists each problem with its step id (a link that selects the step) and the suggested fix. Save keeps an
  agent with problems and reports their count; check-in and runs refuse it.

## 10. Running an agent

- **Run** shows the agent's inputs in the Run tab (with defaults as placeholders; Credential inputs take a vault
  reference) and **Run agent** starts a local test run on this computer with the saved or open agent.
- Studio runs use the workspace (files, `global-values.json`, Studio's model and saved connections) and last at most
  one hour (24 hours when debugging). They do not appear in the Command Center.
- While running: steps are highlighted as they run, finish or fail, and the canvas keeps the running step in view. When
  the run ends, the result, the Run log and the final Variables are shown, and a toast gives the status and duration.
- A run that cannot start (for example validation problems) shows the error and opens the Problems tab.
- Scripts run with the Scripts toolkit as an agent would, showing output, error output and exit code.

## 11. The Run window and the Run dock

The floating **Run window** shows the agent name, the current step (number, action and note), elapsed time, progress
and the controls: Pause/Resume and Stop; Continue and Step while debugging. It can be dragged and minimised, its place
is remembered, and it stays after the run with the result until closed or the next run. On the desktop it stays on top
of the applications the agent brings forward. The Run dock can be minimised; its Run log tab shows every event.

## 12. Debugging and breakpoints

- **Toggle breakpoint**: F9, the step menu, or a click on the step number. Breakpoints are kept in the agent file
  (designer data only) and can be changed while a run is going.
- **Debug** runs with the debugger: it stops at breakpoints, or before the first step when there are none.
- When stopped ("At breakpoint" or "Paused"): **Continue** (F5), **Step over** (F10), **Step into** (F11, into blocks
  and child agents), **Step out** (Shift+F11), **Stop**. The **Call stack** shows the agents running, outermost first.
- **Variables (edit and press Set)**: the current agent's variables can be changed while stopped (converted to the
  variable's type). Credentials are masked and cannot be changed (`DEBUG.READ_ONLY`).
- Breakpoints also apply inside child agents.

## 13. Run from here and Run this step only

From the step menu (not while a run is going):

| Command | Behaviour |
| --- | --- |
| Run from here | Steps before the chosen step (in document order) are skipped: they do not run and emit no events. The skipped steps' variables start with the values **this agent's last run in Studio** had when it first reached this step; if that run never reached it, the values it ended with. A toast says which. |
| Run from here with default values | As above, but variables keep their defaults and the run's inputs |
| Run this step only | Runs the chosen step (and, for a block, everything inside it), then ends successfully |

Rules for both:

- Blocks that contain the chosen step are entered without being evaluated: an If's condition is not checked, a Loop
  runs one pass without setting its item or index variable, a Parallel runs only the branch holding the step, and a
  Try keeps its Catch and Finally when the step is in its Try part. After the enclosing blocks end, the run carries on
  normally (Run from here).
- Inputs given in the Run tab win over carried-over values; credentials, undeclared names and values of the wrong type
  are never carried over; secret values are never kept between runs.
- They apply to the agent being run, not inside its child agents. Progress counts only the steps from the chosen one.

## 14. Child agents: opening, breadcrumbs and "Run by"

- **Open the agent it runs** on a Run Agent step opens the child agent from the workspace (a message says when it is not
  in the workspace, or when the step has no agent yet).
- The breadcrumbs then show the way back instead of the folder path: "Automation / Parent › line N / Child". Each parent
  link reopens that agent at its Run Agent step (and drops the deeper part of the trail).
- An agent that other workspace agents call shows **Run by** after its name, listing those agents; each link opens the
  caller at its first Run Agent step for this agent.

## 15. Recorder, Capture and Selectors to objects

| Tool | Behaviour |
| --- | --- |
| Record | Records actions in a browser window (an open window through the ONEOFFICEAUTOMATION Browser Connector, or a new page) or in a desktop application, until Stop recording; a dialog counts recorded actions and can cancel (nothing added). Typed values become input variables `Text_1`, `Text_2` … with the recorded text as default, selected options `Choice_1` …; windows become `Window_1` … variables. Older recordings with long names are offered shorter names (nothing changes unasked). |
| Capture | Captures one element of a web page (in the window the step names, an open browser window, or a new page) and adds or fills an element action; the step's window becomes the captured page when none is set |
| Capture desktop | Captures one control of a Windows application (hover it and press Ctrl+Shift+C) and adds a Desktop objects step |
| Captured object editor | Preview, main and anchor element, property table (tick the properties used to find it), search options, page or window, time-out options |
| Use a selector instead / Capture the element instead | Switches an element field between a captured object and a selector |
| Selectors to objects | Runs the agent once (with the Run tab's inputs), captures every element its web steps find by selector, and replaces those selectors with captured objects. A selector stays when it contains a variable, picks the n-th match, lists alternatives, was not reached, or met several different elements. Save to keep the change. |
| Window variable | Moves a desktop window choice into a variable used by every step with the same window |

## 16. Connectors and connections

- **Connectors…** imports an OpenAPI, Swagger or Postman file as a custom connector, lists its operations (with a
  filter) and inserts a call step with its parameters filled from defaults.
- Saved connections of the workspace can be listed, created, edited and deleted (secrets as vault references).
- **One Model Connections…** keeps Studio's own named model settings used by AI actions, Prompt Skills and the assistant.

## 17. The assistant

**Assistant** (an agent must be open; needs a model connection) has **Build from a description**, which proposes steps
and variables and, on Insert, adds them at the insertion point (variables only when the name is free; steps with fresh
ids, selected for review), and **Explain this agent**.

## 18. Local triggers

The Triggers panel keeps Studio's own triggers: file arrives in a folder (pattern), schedule (every N minutes or daily
at HH:mm), local webhook (secret shown once). Each can be switched on or off, test-run and deleted; recent activity is
listed. They work only while Studio runs ([document 04](04-schedules-and-triggers.md)).

## 19. One Command Center link

- **Connect** at the bottom of the Explorer (One Command Center section) with the address, user name, password and, for
  two-step accounts, a code; or by using the console's own sign-in when Studio is served by the same server. The session
  token stays in the Studio process, never in the browser.
- The section lists the repository (searchable) with each agent's state; **Check out** copies the latest version into the
  workspace and opens it ("Checked out vN into My Workspace"); **Versions** lists versions with author, time, comment
  and which is live; **Undo check-out** (for the person who has it) releases the lock and keeps the private copy.
- **Agent check-in** saves the open agent and opens a dialog with **What changed** (shown in the version history) and
  the tick **Make this the live version**, off by default. Off: a new version only; the live version is unchanged.
  On: the new version becomes the live production version (or, with approval before production, a request: "sent for
  approval before it goes live"). An agent with no live version yet gets its first one live even with the tick off. The Command Center validates it first; problems open the Problems tab. Check-in
  needs `repo.checkin`, edit access on the folder (manage access too when the tick is on), and a Developer or Citizen
  Developer seat. A new agent checked in with the tick off has no live version until one is set.
- Workflows and forms are published with **Publish to Command Center** in their editors. Publishing always makes the
  new version live (or asks for approval).

## 20. Other item editors

Forms (fields and their types), workflows (stages, start form, due hours, what a rejection does), document
intelligence (fields to extract), Prompt Skills (instructions, prompt, inputs, answer type, temperature), scripts and
text files open in their own editors in the centre area, with Save and, for scripts, Run.

## 21. Preferences and keyboard

Theme and density (top bar), language (view menu: English, Deutsch, Français, Español, 日本語), panel and dock
visibility, and the Run window position are remembered in the browser. Keys: Ctrl+S save, Ctrl+Z/Y undo/redo, Ctrl+C/X/V,
Ctrl+D, Del, Ctrl+/, Alt+Up/Down, Ctrl+A, Ctrl+K or `/` Quick add, F9 breakpoint, F5/F10/F11/Shift+F11 while debugging,
Esc closes menus.

## Related documents

- [01 Agents and automation items](01-agents-and-automation-items.md)
- [02 Toolkits and actions](02-toolkits-and-actions.md)
- [08 Repository, versions and templates](08-repository-versions-and-templates.md)
- [04 Schedules and triggers](04-schedules-and-triggers.md)
- [Command Center and runner, section 4](../user-guide/00-README.md)
- [Browser Connector](../user-guide/08-browser-connector-guide.md)
