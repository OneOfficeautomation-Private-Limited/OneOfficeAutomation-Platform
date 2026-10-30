# 12. UI redesign: TypeScript and React, Studio first

Status: **approved** (2026-10-29): screens and build decisions (section 7). Building has started with P0. Pending items V1 (screens in TypeScript with React, Studio first), V2
(Studio from the Command Center later) and V6 (easy UI, gap list in `docs/research/easy-ui-study.md`).

## 1. Goal

Make Studio, then the Command Center, as easy to use as the best of AA, UiPath and Blue Prism, without losing what
works today (strict security policy, offline installs, the shared look, 278 Studio UI checks) and without building
any screen twice.

## 2. What stays

- **The servers and their APIs.** `StudioServer` and the Command Center keep every endpoint. The new screens are
  another client of the same API.
- **The design tokens** (`ooa-tokens.css`): colours, spacing, type, light, dark and Studio themes. New components are
  built on them, so the new screens look like OOA, not like a library's default.
- **The security policy** `script-src 'self'`. The build makes plain files with no inline script, served by our own
  servers. No CDN, no fonts from outside.
- **Offline installs.** The built files go inside the product (`ooa` executable, MSI, container). Customers never
  need Node or the internet.
- **The translation catalogue** (`ooa-i18n-*.json`, German, Spanish, French, Japanese): the new screens use the same keys.

## 3. Architecture

```
platform/ui/                one npm project, built by Gradle (:cli:uiBuild)
  package.json, package-lock.json, .npmrc   exact versions; "npm ci"; no install scripts
  src/
    theme.css     the approved look as CSS variables (colours, sizes, radius)
    components/   buttons, fields, tabs, menus, dialogs, tree, icons; Radix for behaviour, our CSS for the look
    api/          typed client for the Studio API; one base URL (local now, Command Center later)
    studio/       the Studio application
    console/      the Command Center application (after Studio)
  built into cli/build/generated/ui/studio-next, served by Studio at its root
```

One project instead of several packages keeps the build simple; the folders keep the same boundaries, and can
become packages later if another product needs the components.

| Choice | Decision | Why |
| --- | --- | --- |
| Language | TypeScript, strict | Errors found at build time; easier to grow and hire for |
| UI library | React 18 | Decided in V1; most common, largest pool of people |
| Accessible parts | Radix UI primitives (MIT), styled by us | Keyboard, focus and screen-reader behaviour done right, with our own look |
| Flow and workflow canvas | Our own list canvas for agents (as today); React Flow (MIT) for workflows and AI agents' tool maps | Agents read top to bottom like today; workflows are graphs |
| Build | Vite | Fast; outputs plain hashed files that pass the security policy |
| Tests | Vitest for components; Playwright for whole screens (already used) | Keeps the 278 Studio checks as the parity bar |
| Gradle | `:cli:uiInstall` runs `npm ci`; `:cli:uiBuild` runs the checks and `vite build` into the CLI's resources | One build command for everything, in CI and locally |

**Dependencies stay few**, each pinned with its licence checked (all MIT): React, React DOM, Radix primitives, React
Flow, TypeScript, Vite, Vitest. CI runs `npm audit` and fails on high findings.

**One client, two places (V2).** The API client (`src/api`) takes a base URL. Locally it is `StudioServer`; later, Studio served by the
Command Center uses the same screens, with the Runner as the local helper for recording and test runs. Designing this
seam now means the cloud designer needs no second rebuild.

## 4. New Studio layout (approved 2026-10-29)

```
+----------------------------------------------------------------------------------------------+
| Top bar (dark): logo · breadcrumb · search (white field, Ctrl+K) · Save · Run · Debug · Close |
+--------------+-------------+-------------------------------------+---------------------------+
| Command      | Actions     | Editor tabs: Invoice intake × | ... | Properties | Variables |  |
| Center menu  | library     | Toolbar: Undo Redo Cut Copy Paste   | Triggers (tabs)           |
| Home         | (search,    |-------------------------------------|                           |
| BUILD        | toolkits    | Steps: numbered rows, coloured      | Labels beside fields,     |
| MANAGE       | with        | toolkit icons, guide lines for      | sections Input, Output,   |
| ADMINISTRATION coloured    | blocks                              | If unsure                 |
| AI           | icons)      |-------------------------------------|                           |
|              |             | Problems · Run output · Debug values|                           |
+--------------+-------------+-------------------------------------+---------------------------+
```

The design canvas https://claude.ai/artifact/HafD989gNmovYiwHkHmZrB has four clickable screens, approved on
2026-10-29: the workbench, Automation with Create, the AI agent editor and the model connection wizard.

**Layout, as in AA's Control Room and bot editor**
- The left menu is the Command Center's own: Home, then four sections in this order, BUILD (Automation only:
  workflows, forms and the rest are made there with Create and called from agents' steps), MANAGE (Activity, Runners, Schedules and triggers, Work queues, Task inbox), ADMINISTRATION
  (Users and roles, Vault, Audit, Licenses, Settings) and AI (Model connections, Prompt Skills, AI governance). Headings are bold
  white capitals with a coloured icon and a divider; items are indented under a guide line; the current one is solid
  blue. The menu can be collapsed.
- Actions on the left; Properties, Variables and Triggers as tabs on the right.
- Editor tabs: several items open at once (today only one).
- **Creating an item follows AA's order**: pick the folder in Automation, press Create, choose the type (Agent, AI
  agent, Workflow, Form, Document type, Prompt Skill, API agent, Script), then a short dialog with name, description,
  the folder already filled in, and Start from (blank or a template). Close with X, Esc or Cancel. Templates live only
  here (decided 2026-10-29), not in the menu: the agent blueprints are one shared list, `studio/blueprints.json`,
  served at `api/blueprints` to both Studios, and only those whose toolkits are installed are offered.
- Model connections: a wizard of four steps (Service, Model and region, Sign-in, Test and share) using dropdowns,
  with Save as draft.
- Plain words everywhere; codes only in tooltips.

**Look (dense and professional)**
- White panels and a dark frame (top bar and menu). Grey only for borders, never behind text.
- Text #17202b, secondary #353e4c, nothing lighter than #4b5565 on white.
- Rows 32 to 36 px; tabs, fields and buttons share one height (30 to 32 px), one 6 px radius and one field border
  (#7d8797, 3.6:1).
- Buttons are never grey: Save solid blue, Run solid green, Debug solid amber, Close solid white on the dark bar;
  secondary buttons white with a blue border and blue text; toolbar buttons plain dark text.
- Every colour pair is checked by calculation: text at least 4.5:1, borders and icons at least 3:1. White text on
  teal or cyan uses the darker shades `#0f766e` and `#0e7490`.

New and changed screens from the V6 gap list that live in Studio:

| Gap | In Studio |
| --- | --- |
| V6.6 AI agent editor | New item type. Prompt first, expanded into role, goal and plan; tabs Model, Inputs and outputs, Tools, Limits, Test; templates |
| V6.5 Model connections | Wizard with dropdowns: service, region, model, separate vault keys, test on the server, default use, on or off |
| V6.7 Filters and saved views | Explorer, action library and run history |
| V6.11 Wizards with drafts | New agent from template, new connection, new trigger |
| V6.1 Menu | Command Center menu by task: BUILD, MANAGE, INSIGHTS, ADMINISTRATION, AI. **Done in today's console 2026-10-30** (the user chose Insights as its own section; the Vault under Administration, runs, schedules, triggers and queues under Manage); the rebuilt console keeps it |
| V6.2 Work for people | Forms and workflows made in Automation and called from agents; the Task inbox under MANAGE |

## 5. Moving over without a big bang

The old Studio stays the default until the new one passes the same checks. Both are served side by side: the new
one at `/studio/` behind a "Try the new Studio" switch, the old one as "Classic".

| Step | What | Size |
| --- | --- | --- |
| P0 | Workspace, build in Gradle and CI, first components, typed API client, test harness. **Done 2026-10-29**, with a read-only start on P1: the menu, Automation with Create, agents in tabs | M |
| P1 | Shell: top bar, rail, side panel, tabs, search everything, New dialog; Explorer | M |
| P2 | Agent canvas and Properties panel (the largest part: drag and drop, blocks, selection, undo). **First part done 2026-10-29**: add steps from the library (after the selected step, or inside a selected block), delete, move, duplicate, switch off, undo and redo, settings by the catalogue (main and More options), outputs, comments, variables, Save with the check's problems shown, ask before closing unsaved. Second part done the same day: drag and drop (before, after or inside a block; actions straight from the library), a condition builder in plain words for If, Loop (while) and Wait until, several steps selected with Ctrl and Shift, inputs shown only when they apply. P2 is done | L |
| P3 | Action library, variables, triggers, run, debug, bottom panel. **Done 2026-10-29**: Run (asking for the agent's inputs), live run output and outputs, Debug with breakpoints (F9), Continue, Step over, into and out, values at the stopped step, the running step highlighted; schedule, folder and webhook triggers per agent; Problems, Run output and Debug values in the bottom panel | L |
| P4 | Other editors: form, workflow, document type, Prompt Skill, script. **Done 2026-10-29**: forms (13 field kinds, ids, options, limits), workflows (agent tasks, human tasks, approvals, decisions, notifications), document types (provider, model, accepted files, fields to extract), Prompt Skills and scripts, each with undo, save and the unsaved check; new items open in their editor; a JSON file that does not parse opens as text to repair. Still in Classic: Create extraction agent from a document type, and running a script on its own | L |
| P5 | AI agent editor (new, V6.6) and model connections (V6.5). **Done 2026-10-29**: Create → AI agent makes an agent with one AI step and opens it in its own editor: a plain brief that can write the role, instructions and plan through the default connection; tabs for instructions, tools (other agents, with approval before sensitive ones), model, outputs and limits; a Try it panel with the answer, status and each step it took; Show as steps for the ordinary view. New connection is a four-step wizard (service, model and address, sign-in from the vault or an environment variable, never the key itself; save, then a one-prompt test) | M |
| P6 | Parity with Classic (section 5.1, groups E1 to E8); make it the default; remove Classic. **Done 2026-10-30**: default, then Classic removed once real capture, recording and Command Center check-in had been tried in the new Studio | L |

Then the Command Center, on the same components (V6.1 menu, V6.2, V6.3, V6.4, V6.7 to V6.10), in these steps (agreed
2026-10-30):

| Step | What | Size |
| --- | --- | --- |
| C0 | The console's shell beside today's, at `/next/`: sign-in (with the two-step code), the menu by task shared with Studio (`ui/src/shell`), Home. **Done 2026-10-30**: `console.html` is a second page of the same build; the Command Center serves it with its own style nonce; pages not rebuilt yet open in today's console from the menu, so nothing is a placeholder. Rebuilt since (2026-10-30, the five changes of the AA comparison): AI > Document Automation (a plug-in page), Agent Runs (AA's Activity: In progress, Completed), sign-in (single sign-on coming back to the page, groups to roles, a new password and two-step setup in the console), and Runners (AA's Devices: search in a chosen field, status and pool filters, columns, settings, program update, out of service and back, delete, details, pools made from selected runners) | M |
| C1 | One table for every list, with column filters and saved views (V6.7): runs, runners, queues, tasks | M |
| C2 | Work for people (V6.2) and bulk approve and reject (V6.8) | L |
| C3 | Roles in plain words (V6.3) and settings in short pages with search (V6.4) | M |
| C4 | Queue service levels (V6.10) and one Insights area (V6.9) | L |
| C5 | Wizards with drafts (V6.11) | M |
| C6 | Parity with today's console (each page, and every server route it calls, compared first), then the default; today's console removed after the user's own checks | L |

The menu by task (V6.1) is already in today's console (2026-10-30): Build, Manage, Insights, Administration and AI.

### 5.1 Parity with Classic (P6)

The 277 Classic checks (`cli/src/test/ui/studio.ui.test.js`, removed with Classic) drove Classic's own page by its element ids, so they could not
run on the new Studio as they are. Parity means: each behaviour they check works in the new Studio and has its own
test there. Checks 1 to 277 fall into these groups (counts are approximate; the server-side ones, such as paths
outside the workspace and webhook secrets, keep passing whatever the screen):

| Group | Classic checks | New Studio |
| --- | --- | --- |
| Workspace, Create, open, save, close with the unsaved check, delete to the trash | 3-13, 23, 28-32, 98-105, 109, 229-232 | Have |
| Drag and drop, blocks, condition builder, multi-select, undo, Delete, Duplicate, move | 14-22, 63-65, 67-71, 77-82, 192-195, 198-208 | Have |
| Run, live step, stop, pause, Debug with breakpoints, triggers | 50, 54-62, 84-90, 122, 129-130, 233-237 | Have (the Run window is the bottom panel) |
| **E1 Step editing**: copy, cut, paste (also into another agent, with its variables), Ctrl+/, Esc, Shift+Up/Down, the step menu (⋮ and right-click, keyboard), Add note, fold blocks, line numbers through blocks, fixed Start and End rows, Else if, + Catch, + Finally, quick add with Enter | 33-38, 66, 72-76, 81, 123-124, 153, 159-166, 181, 196-197, 200-201, 209-218, 259-266 | **Done 2026-10-30**: Cut, Copy and Paste (toolbar, keys, menu; the clipboard shape Classic uses, so steps move between the two Studios with their variables), the step menu on ⋮, right-click or the menu key, Shift+arrows, Esc, Ctrl+/, moving a whole selection, Start and block parts that can be selected and dropped on, + Else if, + Else, + Catch (with its error codes), + Finally, folding kept in the agent's ui section, Enter in the action search adds the best match. The flow diagram view is in E7 |
| **E2 Variables**: the {x} picker, rename everywhere, typed defaults, input and output, system and global variables, credential variables, only matching types offered, last values after a run | 42, 51, 83, 116-121, 154-158, 173-176, 267 | **Done 2026-10-30**: the variable editor (type, Use as input and Use as output, required, allowed values, a default in the shape of its type, business figure, description; a rename updates every use), the panel grouped by use with uses, defaults and last-run values, System and Shared values, the {x} picker (Ctrl+Space) with the field's type first and + New variable, results kept only in variables of their type, Credential / Variable / Text / Calculation for typed text, Text or Calculation for Assign, Run agent with the child's inputs and outputs, and the shared clipboard. Fixed: input-and-output variables were written as "both"; the flow format says in_out |
| **E3 Settings by kind**: Loop kinds, business-day options, click and typing options, time-out box, outcome variable, window presets, connector connection | 167-172, 177-180, 223-224, 252-258, 270 | **Done 2026-10-30** except 179: help behind an ⓘ (with a number's limits), element actions with the actions of the captured control first, only the fields of the chosen action, the value named for it and its result kept in a new variable, the System time-out box (Basic / Advanced), Loop by kind (N times, For each, While) with only that kind's fields, Number fields that take a calculation, the window editor for web actions (open windows, title match, Case sensitive, browser, address), a saved connection on connector steps. Fixed: "show unless" rules (a leading "!") were read as values, so Loop and similar fields showed at the wrong times. Desktop window presets (179) came with E6 |
| **E4 Explorer**: rename, move by drag, copy, paste, duplicate, the item menu, search by description and by the actions used | 24-27, 91-93, 238-247 | **Done 2026-10-30**: sub-folders listed with the items, Ctrl+click and Shift+click selection, a ⋮ menu and right-click menu on rows and folders (Open, Rename, Duplicate, Copy, Cut, Paste into a folder, New folder, Delete), the same from a selection bar and the keyboard (Ctrl+C, Ctrl+X, Ctrl+V, F2, Delete), dragging items or folders onto a folder to move them (never into themselves), several items to the trash at once, open tabs following a rename or move, and search by description, agent id, the actions used and variables, with what matched |
| **E5 Run options**: Run from here, Run this step only, with default values | 219-222 | **Done 2026-10-30**: in the step menu, Run from here (the skipped steps' variables take the last run's values at that step), Run from here with default values, and Run this step only; the agent's inputs are asked for first, as for Run |
| **E6 Web and desktop objects**: Capture, the recorder, the captured object and window editors, anchors, selectors, numbered window and value variables, run in background | 53, 96, 131-152, 182-183, 225-228, 268-269 | **Done 2026-10-30**, with 179: Record (a connected browser tab, a new page or a desktop application, with a live count and Cancel) and Capture (web element or desktop control) on the steps toolbar; the captured object editor (Recapture, Test, Main and Anchor, only the ticked properties at first with readable names, Use and Match per property, your own properties, search by properties, CSS or XPath, which match, ignore case, self-heal, the page); the desktop window with presets (the window with the title, in front, desktop, taskbar), title match, process, program, Resize window and one shared window variable; numbered Window_1, Text_1 and Choice_1 variables from captures and recordings, a $password credential, and the offer to shorten long names from older recordings; Use a selector instead, with its own Capture. A real capture or recording was not run in this check (it opens a browser or waits for keys on the desktop); the server side is Classic's, already tested |
| **E7 Agents together**: open the agent a Run agent step runs and come back, "Run by", Dependencies, templates, the flow diagram, the assistant, document type to extraction agent, run a script | 106-108, 110-115, 125-128, 271-274 | **Done 2026-10-30**: a Run agent step shows the agent it runs and opens it (step menu, or Open this agent) with a breadcrumb back to the step; an agent says which agents run it (Run by); the Dependencies tab (child agents, scripts, Prompt Skills, files, used by); the flow diagram (List or Flow; If parts side by side, blocks framed, drop on a line); the assistant (Build from a description with a One Model Connection, Explain this agent); Create extraction agent from a document type; Run script with its output, and Copy as an agent step. Templates and the AI agent were already in P1 and P5 |
| **E8 Control Room**: sign in and check in from the Studio | 275-277 | **Done 2026-10-30**: as in AA, the Automation page has Private (the workspace) and Public (the Command Center agent repository). Public signs in (or uses the browser's Command Center sign-in when both run together), lists the repository with Check out, Open my copy, Undo check-out and Versions; an open agent has Check in (signs in first when needed, saves, then a new version, live only when ticked). Sign-in errors say what to do |
| **Classic-only options**: theme switch, compact density, dock right, floating or minimised, drag handles to resize panels, the logo | 1-2, 39-49, 94-95, 248-251 | **Done 2026-10-30** (kept by decision): drag the edge of the folders, actions, details and output panels (or focus it and use the arrow keys; a double-click puts it back), sizes remembered in the browser; the output panel at the bottom, on the right, floating (moved by its grip, resized at its corner) or minimised to a bar with the problem count and run state; the Display menu in the top bar for a light or dark theme and standard or compact density. Colours are theme tokens, and tools/check-contrast.mjs checks every text and border pair in both themes by calculation in `npm run check`. Classic's other colour themes and the logo choice were not kept |
| Library groups by technology | 97, 184-191 | Have (toolkit families) |

The new Studio becomes the default when E1 to E8 and the kept Classic options are done. Order: E1 first.

**Default since 2026-10-30.** E1 to E8 and the kept options are done, so the new Studio is served at Studio's root (`/studio/` under `ooa server`), and the preview's address `/next/` redirects there.

**Classic removed 2026-10-30**, after the new Studio's capture, recording and Command Center sign-in and check-in were tried for real. A last comparison of the server routes each Studio called found five things only Classic still did; they were added first: saved connections (list, new, edit, delete, from the More menu or a connector step's Manage connections…), custom connectors (import an OpenAPI, Swagger or Postman file, browse its operations, add a Call operation step, delete), Test the condition in the condition builder, Upload files, Download and Show in File Explorer on the Automation page, and Check the agent without saving. Classic's page, files and `/classic/` route are gone (Studio answers 404 there). The server checks of its page test now live in `StudioServerRoutesTest` (the seeded workspace and a run, folders and agents, the trash, names and paths that leave the workspace, items, the shared clipboard, search, dependencies, file, schedule and webhook triggers with their secrets). Its texts left the translation key list.

## 6. Quality bar (checked at every step)

- **Architecture**: no screen calls the server except through the API client (`src/api`); no business rules in the screens; no new
  server services.
- **UI**: each screen reviewed against the V6 study before it is built (a mockup), and in the browser after.
- **Ease of use before speed** (decided 2026-10-29). Performance may be close to AA's: AA's Control Room loads a
  script of about 8 MB. Every screen is still measured in CI, and anything slower than these is reported, not
  blocked:
  - Studio opens in under 3 s on a local install.
  - Editors and large libraries (diagrams, code editor) load only when opened.
  - An agent of 1,000 steps scrolls without stalls.
- **Accessibility**: WCAG 2.2 AA. Every action works from the keyboard, the existing focus ring is used, and
  automated axe checks run in CI.
- **Security**: the security policy is unchanged; no secrets in browser storage; vault values are never sent to the
  screens (as today).
- **Translations**: no text outside the catalogue; the catalogue check covers the new screens.

## 7. Decisions (made 2026-10-29)

AA's Control Room, checked from its public sign-in page, is built the same way: React, built with Vite from npm
packages, with its own component library.

1. **npm packages**: yes, in `platform/ui` only, on developer machines and in CI. Versions are exact and locked
   (`package-lock.json`, `npm ci`), install scripts are off (`.npmrc`), and CI runs `npm audit` and a licence check
   (MIT, BSD, Apache, ISC). Customers get only the built files. Build tools may also be MPL-2.0 (lightningcss,
   Vite's CSS minifier), whose code does not ship. npm 11.5 cannot resolve Vitest 5's optional peers, so npm installs
   no peers by itself (`legacy-peer-deps`); every package is listed, and `tools/check-peers.mjs` checks the peers.
   The packages that ship are in the SBOM (`sbom.cdx.json`) with their npm address and SHA-512.
2. **Radix primitives** with our own look: the behaviour of menus, dialogs and tabs (keyboard, focus, screen
   readers) comes from Radix; every pixel of the look is ours.
3. **Editor tabs**: yes. Each tab tracks its own unsaved changes; closing an unsaved tab asks first; at most 10 open.
