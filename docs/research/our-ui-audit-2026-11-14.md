# First-time-user audit of the web UI (read-only, code only)

Root: `C:\OOA\OneOfficeAutomation-Platform\.claude\worktrees\gallant-mclaren-080a06\platform\ui\src` (paths below are relative to it).
Confidence: **sure** = the code shows it; **likely** = the code strongly suggests it, but the running app should be checked; **check** = the code alone cannot settle it.
Numbers in brackets show which of your 7 questions a finding answers: [1] icon-only, [2] symbol, [3] hover-only, [4] small target, [5] jargon/JSON, [6] empty/error text, [7] hidden steps.

---

## A. Studio editor (OneAgentStudio)

### A1. Run Agent step (the complaint and its neighbours)
1. **studio/ValueSources.tsx:306-307** [1][2] Quick map is a 30 px `st-tool` with only the ⇄ (`swap`) icon. The name shows only on hover. A newcomer will not find it or guess what it does. **Fix:** a small secondary button with an icon and the words "Quick map", placed beside the "Input values" heading. **Sure.**
2. **studio/ValueSources.tsx:320** [2] Each input shows a coloured TypeBadge ("Abc", "123", "Key"...) and then a type chip with the same type in words. Two marks for one fact. **Fix:** keep only the chip in words, or the badge with the word inside it. **Sure.**
3. **studio/ValueSources.tsx:297** [5] Label "Variable with the agent id", placeholder `$agent_id$`. "id" is an internal word. **Fix:** "Variable that holds the agent's name or id", with a hint that gives an example value. **Sure.**
4. **studio/ValueSources.tsx:302, 346** [5][7] The hints send people to "Save results in › Outputs, below". That is a section further down the form, and only for a variable-chosen agent. **Fix:** name the section exactly as it is labelled, or link to it so a click scrolls there. **Likely.**

### A2. Value fields everywhere
5. **studio/ValueSources.tsx:47-48** [1][2] The button that inserts a variable reads `{x}` in mono text. The words "Insert a value" are only in aria-label and title. This button sits on almost every text field, so it is the most-used control a newcomer cannot read. **Fix:** show the word, for example "$ Value" or "Insert value", or a variable icon with a visible word on wide panels. Mention in the hint that typing `$` opens the same list. **Sure.**
6. **studio/ValueSources.tsx:233-235** [1][2] Assign-many rows use bare ↑, ↓ and × as text buttons. These are fairly well known, but × next to a value can be read as "clear the value". **Fix:** use the trash icon, with "Remove" written on wide panels. **Low priority.**
7. **studio/ConditionEditor.tsx:226, 230** [5][7] The condition builder's "Value" and "Compared with" are plain `TextInput`s (placeholder `$total$`). They have no Insert-a-value button, unlike every other field. A newcomer must type `$name$` by hand. **Fix:** use `CommitInput want=...` so the same {x} picker is there. **Sure.**
8. **studio/ConditionEditor.tsx:195** [5] "Write it as an expression" opens a raw expression box with no picker and no example of the syntax. **Fix:** add an example hint and the same value picker. **Sure.**

### A3. Steps list and the edit bar
9. **studio/Editor.tsx:659-671, 691** [1] The edit bar is icon-only: Undo and Redo (well known), Cut, Copy and Paste (known to Office users), Delete with trash (known), Duplicate (NOT obvious), Disable with the power icon (NOT obvious: it reads as "turn off the app"), and More ⋮ (known). **Fix:** give Duplicate and Disable/Enable visible words, or move them into a "Step ▾" menu button with words. **Sure.**
10. **studio/Editor.tsx:566-590** [7] Breakpoint, Run from here, Run from here with default values and Run this step only can be reached only through the step's ⋮ menu or a right-click (plus Breakpoint in More). There is no click on the line number for a breakpoint, as AA and UiPath allow. **Fix:** let a click on the line-number column set a breakpoint, and put a "Run from here" button in the Properties header of the selected step. **Sure.**
11. **studio/Editor.tsx:684-686, 729** [7] "Add a subflow…", "Add error steps (On error)", "Check the agent", Saved connections and Custom connectors live only in More ⋯. The subflow bar with its `+` appears only once a subflow exists. On error does have a visible button in agent properties (813), and Check is also in the Problems tab (853). Subflows have no visible entry at all. **Fix:** a "Subflows: Main | + Add subflow" strip that is always shown, or a text item "Add subflow" in the bar. **Sure.**
12. **studio/Editor.tsx:729** [1] The subflow `+` button is icon-only (title "Add a subflow"). A plus next to tabs is ambiguous. **Fix:** "+ Subflow" with the word. **Sure.**
13. **studio/Editor.tsx:633** [2] A step with a problem shows a "CHECK" badge with no title. A newcomer cannot tell what to check or where. **Fix:** "Problem" with a title giving the message, or make the badge open the Problems tab at that line. **Sure.**
14. **studio/Editor.tsx:623** [2][3] The breakpoint is a 9 px red dot (`studio.css:236`) whose meaning is in the title only. **Fix:** acceptable once breakpoints can be set from the gutter. Add "Breakpoint" to the Output legend or help. **Low.**
15. **studio/Editor.tsx:637 + studio.css:1011-1012** [3] A step's Docs and Video links are `display:none` unless the row is hovered or selected. They are 13 px icons with no words (`is-compact`), and the "play" icon next to a step looks like "run this step". **Fix:** show them only in the Properties header (already done at 795). On the canvas, use the word "Docs", or a ? icon instead of play. **Sure.**
16. **studio/Editor.tsx:719-721** [1] Full screen is expand/shrink icon-only. This is well known. **OK.**

### A4. Actions panel
17. **studio/Editor.tsx:129** [3] An action's description is only in `title`. A newcomer browsing "Excel" sees names only and must hover each one. **Fix:** show the description on keyboard focus or selection in a one-line strip at the foot of the panel. **Sure.**
18. **studio/studio.css:945-946** [3] The Docs and Video links in the Actions list are `opacity:0` until hover or focus. Touch users never see them. **Fix:** same as 15. **Sure.**
19. **studio/Editor.tsx:133** [5] Search matches are tagged "AA: …" and "Formerly: …". "AA" means nothing to someone who never used Automation Anywhere. **Fix:** "Also called: …". **Sure.**
20. **studio/Editor.tsx:93** [2][3] The "★ Common / All" switch explains itself only in a title. **Fix:** a one-line hint under it already exists for Common (98). Make the All text say the same. **Low.**

### A5. Properties and step form
21. **studio/Editor.tsx:187-190** [5] "Agent id" is the FIRST field in agent properties. It is editable and mono. Changing it breaks Run agent steps in other agents. **Fix:** move it under a "More options" fold and label it "Agent id (used by other agents and One CommandCenter)". **Sure.**
22. **studio/Editor.tsx:1104-1106** [5] Catch: "Errors it catches" must be typed as codes (`NOT_FOUND, TIMEOUT, FILE.*`). **Fix:** a multi-pick of the known error kinds, plus "Other code…" (as ThrowCode in StepForm.tsx:358 already does). **Sure.**
23. **studio/StepForm.tsx:104** [5] When a toolkit field has no `x-ooa-label`, the label is the raw arg name with `_` turned into spaces ("iterator args", "sign in private key"). **Fix:** add a test that every catalogue field has `x-ooa-label`. **Check** (depends on catalogue coverage).
24. **studio/StepForm.tsx:242** [5] The loop kinds read "N times / For each / While". "N" is maths shorthand. **Fix:** "A number of times". **Low.**
25. **studio/FieldKinds.tsx:79** [5] Table-type fields are typed as JSON (`{"columns": [...], "rows": [[...]]}`). **Fix:** a small grid editor, or "Use a Table variable" as the main path, with JSON behind "Advanced". **Sure.**
26. **studio/VariablesPanel.tsx:153-154** [5] The default value of Table and Any variables is a JSON text area. **Fix:** same as 25. For Any, offer the type choice first. **Sure.**

### A6. Variables panel and type badges
27. **studio/variables.ts:15-23, VariablesPanel.tsx:42** [2][3] The type badges use abbreviations: Abc, 123, Y/N, [ ], { }, Tbl, Rec, Win, UI, Key (Credential), Dur, Img, Bin. Their meaning is in a `title` on an `aria-hidden` span, so keyboard and touch users never get it. "Key" for a credential and "Dur", "Bin" and "Rec" are not guessable. **Fix:** where space allows, write the type word beside the badge (VarPicker already does at :97). In QuickMap rows (RunAgentParts.tsx:165), the built-in values list (VariablesPanel.tsx:306) and "Save outputs to" (ValueSources.tsx:335), add the word. Rename "Key" to "Secret" or a lock icon. **Sure.**
28. **studio/VariablesPanel.tsx:295** [1][7] Each variable's ⋮ holds Change, Copy and Delete. A row click opens Change (good). Copy and Delete are hidden, but they are rare tasks. **OK.**
29. **studio/VariablesPanel.tsx:320-321** [6] The empty state says what variables are, but not what to press. **Fix:** add "Press New to make one, or type $ in any field." **Sure.**

### A7. Run dialog (Studio)
30. **studio/App.tsx:701-705** [5] The Run/Debug inputs are all plain text boxes labelled `$name (Type)`. List, Dictionary and Table inputs must be typed as JSON. Boolean has no Yes/No choice (the console's Run dialog has one at Repository.tsx:604). **Fix:** reuse the variable default editors (ListEditor, PairsEditor, the Boolean select). Drop the `$` from the label. **Sure.**

### A8. Form and workflow editors (Studio items)
31. **studio/ItemEditors.tsx:204-206** [5][6] A workflow agent stage takes "Inputs (JSON)". Invalid JSON is silently ignored ("kept as it was"), with no error shown. **Fix:** one row per input of the chosen agent (as Run agent's Input values do). At the least, show "Not valid JSON: nothing saved". **Sure.**
32. **studio/ItemEditors.tsx:210, 214, 225** [5] Assign to, Approver and To take typed `role:bot_runner or user:jane`. **Fix:** a people/role picker (see C7). **Sure.**
33. **studio/ItemEditors.tsx:175 (pick)** [5] The agent and form dropdowns list workspace file paths (`Finance/Refund.flow.json`). **Fix:** show the display name, with the folder as the group. **Sure.**
34. **studio/ItemEditors.tsx:130** [5] "Answers come back as a Dictionary keyed by field id." **Fix:** "Each answer is kept under the field's name, e.g. $answers.amount$". **Sure.**
35. **studio/ItemEditors.tsx:64-66** [1] Move up, Move down and Delete are icon-only. Up and down arrows are understandable. **Low.**

## B. Studio Agents page (Private workspace)

36. **studio/Automation.tsx:236, 250** [7] Upload files only appears in a folder's ⋮ menu (row menu, tree ⋮, panel ⋮). It has no visible button. Bringing an Excel or data file into the workspace is a common first task. **Fix:** an "Upload" button next to New folder and Create (277-281). **Sure.**
37. **studio/Automation.tsx:226-227** [7] Undo check-out and Revert edits are only in the row ⋮ or a right-click. Check in is in the selection bar (325), which is good. **Fix:** add Undo check-out and Revert to the selection bar when they apply. **Sure.**
38. **studio/Automation.tsx:302 + components.css:149-151** [3][4] The folder tree's ⋮ is `opacity:0` until hover, focus or selection, and 22x22 (`.ui-tree-more`). **Fix:** keep it visible on the selected folder (done) and on hover, and make it 24 px. Fine for experts, but a newcomer needs right-click. **Sure.**
39. **studio/Automation.tsx:204, 54-62** [3] The Status pill ("Checked out · edited", "Cloned"...) explains itself only in a title. **Fix:** the Help panel already lists them. Add an ⓘ legend under the table, or show the explanation in the selection bar. **Likely.**
40. **studio/Automation.tsx:286** [3] The Public and Private tabs explain themselves only in a title ("One CommandCenter agent repository" / "Your workspace on this computer"). This is the most confusing split for a newcomer. **Fix:** a one-line caption under the tabs. **Sure.**
41. **studio/Automation.tsx:317, ControlRoom.tsx:202** [1] Refresh is an icon only (loop arrows). Well known. **OK.**
42. **studio/CreateDialog.tsx:154; studio.css:720** [4] Folder-tree ▾ and ▸ carets are 16x20 px. **Fix:** at least 24x24 hit area. **Sure.**
43. **studio/App.tsx:507** [1][2] The top-bar "Theme" button uses the `sliders` icon, which people read as "filters" or "settings". **Fix:** a sun/moon icon, or "Display ▾" with the word. **Sure.**

## C. One CommandCenter (console) pages

### C1. Shared list pattern (every list page)
44. **console/TableTools.ts:152-154 + console.css:676-678** [3][4] Each column's filter button is 18x18 px with an 11 px funnel at 55% opacity, at full strength only on header hover. The column-resize grip (console.css:682) is invisible until hover. **Fix:** a visible "Filter" chip row above the table, or the funnel at full contrast and 24 px. **Sure.**
45. **console/ListParts.tsx:26-29** [1][7] The row ⋯ (26x26) is the only place for row actions such as Run again, Stop, Unlock and Reset password. A row click opens the item, which usually repeats the main actions as buttons (Repository.tsx:378-382, Users.tsx:364). The pattern is consistent and acceptable. Exceptions are listed below. **OK.**
46. **console/Activity.tsx:386-387, Audit.tsx:289-290, Devices.tsx:241-242, Users.tsx:252-253** [1][2] Pagination is a bare ‹ › with aria-label and no title. Well known. **OK** (add a title for mouse users).
47. **Empty states that do not name the next step** [6]: Schedules.tsx:211 "No schedules yet.", Triggers.tsx:150 "No triggers yet.", Teams.tsx:132 "No teams yet.", Globals.tsx:110 "No shared values yet.", Vault.tsx:248 "No credentials you can see yet.", ModelConnections.tsx:110 "No model connections yet.", insights/RunnerStatus.tsx:68 "No runners yet.", RepoTools.tsx:74 "No agents yet." Compare the good ones: Ideas.tsx:110 "Suggest one with Suggest an idea.", EventSubscriptions.tsx:127 and Devices.tsx:233. **Fix:** add "Make one with Create …" (or "Ask an administrator" when the person lacks the permission). **Sure.**
48. **"No … match." / "Nothing matches."** (Activity.tsx:377, Schedules.tsx:211, Repository.tsx:264...) [6] There is no way to clear the filters from the empty row. **Fix:** "Nothing matches. Clear filters" as a link. **Sure.**

### C2. Agents (Repository)
49. **console/Repository.tsx:112** [7] Run… is only in the row ⋯ on the list. The agent page has a visible Run (382). **Fix:** a Run button in the selection bar, or make a single row show "Run" on hover and focus. **Likely** (a newcomer will open the agent, so this is medium).
50. **console/Repository.tsx:607** [5] In the Run dialog, a Credential input is typed as `vault folder/credential/attribute`. **Fix:** three dropdowns (folder, credential, attribute) as the vault already knows them. **Sure.**
51. **console/Repository.tsx:605-607** [5] List, Dictionary and Table inputs are plain text, and the default shows as `JSON.stringify` (quotes and brackets). **Fix:** same editors as A7 30. Show the default in plain words. **Sure.**
52. **console/Repository.tsx:432** [5] Every step line in the agent's Steps tab shows its internal step id (`cc-code-id`). **Fix:** hide it, or show it only in the selected-line panel under "Technical". **Sure.**
53. **console/Repository.tsx:431** [5] A disabled step is marked "OFF" here and "Disabled" in Studio (Editor.tsx:630). **Fix:** use one word, "Disabled". **Sure.**
54. **console/Repository.tsx:506-507** [4] The × that removes a version label is a bare `ui-btn-plain` with no padding inside a 20 px tag. That makes it tiny and easy to hit by accident. **Fix:** a 20-24 px × with a confirm, or remove labels from a "Labels…" dialog. **Sure.**
55. **console/Repository.tsx:229** [7] Folder actions (rename, move, delete, new subfolder) are only in a ⋯ beside the crumbs. **Fix:** acceptable. Name the button "Folder ▾". **Low.**

### C3. Schedules
56. **console/Schedules.tsx:319** [5] "Inputs (JSON, optional)" with placeholder `{}`. A business user cannot schedule an agent that has inputs without writing JSON. **Fix:** one field per input of the chosen agent, as Run does. **Sure.**
57. **console/Schedules.tsx:316** [5] Time zone is a free-text box. Studio already has a TimeZoneField picker (StepForm.tsx:391). **Fix:** reuse it. **Sure.**
58. **console/Schedules.tsx:308** [5] "Time (HH:MM)" is typed text. **Fix:** `type="time"`. **Sure.**
59. **console/Schedules.tsx:304-306** [5] "Cron expression" is offered as a peer of Daily and Weekly, with a Quartz hint. Fine for experts. **Fix:** move it to the end as "Advanced: cron expression". **Low.**

### C4. Triggers
60. **console/Triggers.tsx:249-250** [5] When editing a trigger, Owner and Run as are free-text user names. Create uses dropdowns (208-209). **Fix:** the same people dropdown. **Sure.**

### C5. Work queues
61. **console/Queues.tsx:453** [5] Add items: a text area that expects a JSON array with a `_reference` key (`[{"_reference": "INV-1001", ...}]`). A file upload is also offered, but the paste path is pure JSON. **Fix:** a small grid (Reference + the queue model's fields), with JSON behind "Paste JSON". **Sure.**
62. **console/Queues.tsx:476** [5] Member is typed as `user:jane or role:bot_runner`. **Fix:** see C7. **Sure.**
63. **console/Queues.tsx:519** [5] An item's data is edited as JSON. When the queue has a model, use the model's fields as a form (the review form at 545 already does). **Likely.**

### C6. Credential vault
64. **console/Vault.tsx:536-539, 579** [5] Owners, Managers, Participants and Consumers are comma-separated `role:NAME, team:NAME, user:NAME` text. **Fix:** see C7. **Sure.**
65. **console/Vault.tsx:468** [5] Owner is free text. **Fix:** a people dropdown. **Sure.**
66. **console/Vault.tsx:471** [5] "Each person's own attributes (separated by commas)" is AA jargon. **Fix:** "Values each person sets for themselves (e.g. password)". **Likely.**

### C7. Pattern: who-fields typed as `role:/team:/user:` [5]
Also at insights/Alerts.tsx:243, insights/Dashboards.tsx:231 and 286, and studio/ItemEditors.tsx:210, 214 and 225. The Repository permission dialog already has a proper "Add a role" Select (Repository.tsx:669). **Fix:** one shared "People, teams and roles" picker with chips, used everywhere. **Sure.**

### C8. Audit
67. **console/Audit.tsx:269** [5] Filter "Object id" is typed. **Fix:** "Item (name or id)", with a search of known agents, users and runners. **Likely.**

### C9. Top bar
68. **console/TopBar.tsx:50-51** [1][7] The "?" Help icon opens a menu with only "Keyboard shortcuts". A newcomer expects the user guide or "How do I…". **Fix:** add "User guide" (docs/user-guide), "What's on this page" (opens the page's Help panel) and "Videos". **Sure.**
69. **console/TopBar.tsx:57** [1] The account menu is a person icon only. Well known. **OK.**

## D. Document Automation

70. **modules/document-automation/Review.tsx:116-117** [1][6] The "Previous task" and "Next task" icon buttons have NO onClick. They do nothing, and Next is always enabled. A reviewer clicking Next sees nothing happen. **Fix:** wire them up, or remove them. **Sure** (no handler in the code).
71. **modules/document-automation/App.tsx:96-99** [6] The Tenant `<select>` has no value or onChange, so choosing a tenant changes nothing. **Fix:** wire it up, or show the tenant as text. **Sure** (unless a later change adds it).
72. **modules/document-automation/Review.tsx:103; Builder.tsx:117** [1] "Back to the process" is a chevron-only link. **Fix:** "‹ Back" with the word, as the console uses (Repository.tsx:372 "‹ Back"). **Sure.**
73. **modules/document-automation/review/PageView.tsx:34-37** [1][2] Zoom out is the text "−", zoom in is a "+" icon: two styles side by side. "Fit width" really sets 100%, not the page width. **Fix:** the same control style for both. Rename to "100%", or make it fit the width. **Sure.**
74. **modules/document-automation/DocTypes.tsx:176-178, 129** [5] "Format check" is a regular expression typed by hand (hint `3\d{13}3`) and shown raw in the table. **Fix:** a list of ready checks (VAT number, IBAN, date, amount…), with "Own pattern (advanced)". In the table, show the check's name. **Sure.**
75. **modules/document-automation/DocTypes.tsx:125** [5] The field's internal key shows in mono under its name. **Fix:** hide it, or show it only in the Edit dialog ("Saved as …" already does). **Low.**
76. **modules/document-automation/DocTypes.tsx:131-132** [4] The "×" that forgets a learned label is an inline `df-link` with padding 0, about 8 px wide. **Fix:** at least a 20-24 px button, with Undo. **Sure.**
77. **modules/document-automation/overview/Dashboard.tsx:100, 117** [3] Bar values are in the title only. **Fix:** a value label on the bars, or a table toggle. **Likely.**
78. **modules/document-automation/App.tsx:102** [1] The signed-in user is shown as initials only, with no menu. **Check** whether sign out and account settings are reachable from this page.

## E. Other modules

79. **modules/low-code/Apps.tsx:280-283, 288-292** [5] An app button's action asks for the agent as a typed file path (`Finance/Refund.flow.json`), "Runner pool id", "Runner id", "Entity" and "Work queue" as typed names, and Inputs, Values and Item data as JSON (JsonField at 244-248). **Fix:** dropdowns for agent, pool, runner, entity and queue. Inputs come from the chosen agent's input list. **Sure.**
80. **modules/low-code/Apps.tsx:228-230** [2][5] Component rows show the internal id in `<code>`, and move with bare ↑ and ↓. **Fix:** show the component's label. Arrows are OK. **Low.**
81. **Variable syntax differs by module** [5]: Studio uses `$name$` (ValueSources.tsx:151), Prompt Skills use `${name}` (ai-services/Skills.tsx:193), app messages use `{field}` (Apps.tsx:278) and workflows use `$form.field$` (ItemEditors.tsx:204-206). A newcomer learns one and fails in the others. **Fix:** one syntax, or each field gets the same Insert-a-value picker. **Sure.**
82. **modules/ai-services/Skills.tsx:201** [5] "knowledge index (e.g. knowledge/policies.json)" is a typed file path. **Fix:** a dropdown of the Knowledge Bases (that module exists). **Likely.**
83. **modules/insights/Dashboards.tsx:246, 325** [5] The dashboard says "mark an agent's outputs as KPI in OneAgentStudio", but Studio calls it "Business figure" (VariablesPanel.tsx:244-245). **Fix:** one name in both places. **Sure.**
84. **modules/orchestration/Workflows.tsx:157** [5] Starting a workflow with no start form needs "Starting data (JSON, optional)". **Fix:** key/value rows (PairsEditor exists). **Sure.**
85. **modules/testing/Tests.tsx:341** [1][2] A test set's close is a bare "×", while every other console view uses the labelled "Close" button (ListParts.tsx:18 CloseView, made for exactly this). **Fix:** use CloseView. **Sure.**
86. **modules/insights/Capacity.tsx:168, Operations.tsx:250** [3] Heat-map cells show their numbers only on hover. **Fix:** a table view, or the numbers in the cells at large sizes. **Low.**

## F. Shell and shared components

87. **components/Help.tsx:30 + components.css:130-131** [1][4] The main help entry on every page is a 16x16 italic "i" after the title. It is easy to miss and below 24 px. **Fix:** a 24 px "? Help" text button (or "i Help"). Keep the panel. **Sure.**
88. **components/Field.tsx:19 + components.css:110** [4] The field "i" (long help) is 16x16. Acceptable because short help is already written under the field. **Fix:** 20-24 px hit area. **Low.**
89. **shell/CommandMenu.tsx:85, 94, 106** [3] Menu entries that are off (for example, not connected to One CommandCenter) explain why only in `title={offTitle}`. **Fix:** a line at the top of the menu ("Sign in to One CommandCenter to open these") or an "(off)" suffix. **Sure.**
90. **components/Tree.tsx:54 + components.css:77** [4] The tree expand caret is a 14 px-wide `<span>` with an onClick: not a button and smaller than 24 px. **Fix:** a button of at least 24 px. **Sure.**
91. **console/console.css:605** [4] The `.cc-tree-chev` carets (Repository, Templates and Tests trees) are 18x28. **Fix:** 24 px wide. **Low.**
92. **components/components.css:121-125** [3] The splitters between panels are invisible until hover (6 px). They have keyboard support. **Fix:** a faint permanent line or grip dots. **Low.**

---

## Top 15

1. **Quick map ⇄ icon** (studio/ValueSources.tsx:306): make it a labelled "Quick map" button.
2. **`{x}` Insert-a-value button** on every field (studio/ValueSources.tsx:47-48): add a visible word ("Insert value"), and say in the hint that `$` opens the list.
3. **Who-fields typed as `role:/team:/user:`** (console/Vault.tsx:536-539, 579; Queues.tsx:476; insights/Alerts.tsx:243; Dashboards.tsx:231, 286; studio/ItemEditors.tsx:210, 214, 225): one shared people, teams and roles picker.
4. **Agent inputs typed as JSON** when scheduling (console/Schedules.tsx:319), in workflow stages (studio/ItemEditors.tsx:204, which also drops bad JSON silently), in low-code apps (Apps.tsx:283) and when starting workflows (Workflows.tsx:157): one field per input of the chosen agent.
5. **Document Automation Previous/Next task do nothing** (Review.tsx:116-117), and the dead Tenant select (App.tsx:96).
6. **Breakpoint and Run from here only in the step ⋮ or right-click** (studio/Editor.tsx:566-590): click on the line number for a breakpoint, and a "Run from here" button on the selected step.
7. **Edit-bar Disable (power icon) and Duplicate are icon-only** (studio/Editor.tsx:669-670): visible words.
8. **The condition builder has no Insert-a-value** (studio/ConditionEditor.tsx:226, 230): use CommitInput with the picker.
9. **Type badge abbreviations** ("Key", "Dur", "Bin", "Rec", "Tbl"...), with the meaning in the title only (studio/variables.ts:15-23, VariablesPanel.tsx:42): write the type word, and rename Key to Secret.
10. **Page help is a 16 px "i"** (components/Help.tsx:30, components.css:130): a 24 px "? Help" button. And **the top-bar "?" holds only Keyboard shortcuts** (console/TopBar.tsx:50-51): add the user guide.
11. **Run dialogs take List, Dictionary, Table and Credential inputs as typed text or paths** (studio/App.tsx:701-705; console/Repository.tsx:605-607): proper editors, and vault dropdowns for credentials.
12. **Hover-only Docs/Video and action descriptions** (studio.css:945, 1011; Editor.tsx:129) and the **hidden tree ⋮** (components.css:149): show on focus or selection, or in a footer strip.
13. **Subflows have no visible entry** (studio/Editor.tsx:684, 729): an always-visible "Main | + Add subflow" strip.
14. **Upload files only in folder ⋮ menus** (studio/Automation.tsx:236, 250): an "Upload" button beside New folder and Create.
15. **Internal ids and raw syntax shown to users**: "Agent id" as the first agent property (studio/Editor.tsx:187), step ids in the console Steps tab (Repository.tsx:432), regex "Format check" (DocTypes.tsx:176), Catch error codes typed (Editor.tsx:1104), and four different variable syntaxes across modules (Skills.tsx:193 vs ValueSources.tsx:151).

Close runners-up: the faint 18 px column-filter funnel (console.css:676), the "CHECK" badge with no explanation (Editor.tsx:633), empty states that do not name the button (Schedules.tsx:211, Triggers.tsx:150 and others), and "KPI" vs "Business figure" (Dashboards.tsx:246 vs VariablesPanel.tsx:244).
