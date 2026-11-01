import type { HelpTopic } from "../components";

/** One AgentStudio's Help panel text (issue 11, design B, 1 Nov 2026): what each area is for and how to use it. */
export const AUTOMATION_HELP: HelpTopic = {
  title: "Automation (Private)", about: "Your own workspace on this computer: agents, workflows, forms, scripts and files, in folders. Only you see it until you check an agent in to One CommandCenter.",
  items: [
    { name: "Folders", text: "Pick a folder on the left to see what is in it. The ⋮ at the top of the pane, or a right-click on a folder, has New folder, Rename, Upload, Download as zip and more." },
    { name: "Create", text: "Pick a folder first, then press Create and choose what to make: an agent, an AI agent, a workflow, a form, a document type, a prompt skill or a script. It is made in that folder and opens." },
    { name: "New folder", text: "Makes a folder inside the folder you picked." },
    { name: "Type", text: "Shows only one kind of item, such as Agents or Folders. Any shows everything." },
    { name: "Columns", text: "Chooses the list's columns, as in One CommandCenter: Type, Status, Platform (Windows or any runner), Version, Label, Code analysis, Size, Last modified and Modified by. Your choice is remembered." },
    { name: "Tick boxes", text: "Tick items to act on several at once: the bar above the list then offers Copy, Cut, Duplicate, Rename and Delete. Ctrl+click and Shift+click also select." },
    { name: "⋮ on a row", text: "Everything you can do with that item: open, rename (F2), copy, cut, duplicate, download, show in File Explorer, delete. Right-click a row for the same menu." },
    { name: "Status", text: "Shown while you are signed in to One CommandCenter. New: not in the repository yet. Checked out: yours to change; edited means you changed it since. Checked in: the same as in the repository. Edited, not checked out: check it out on the Public tab first (your changes are kept)." },
    { name: "Check in, Undo check-out, Revert edits", text: "In a row's ⋮ menu. Check in sends the item to the same folder in the repository as a new version; tick up to 10 items to check them in together. Undo check-out lets others check it out again. Revert edits throws away your changes and goes back to the checked-out version, after asking." },
    { name: "Moving items", text: "Drag items onto a folder in the tree, or Cut them and Paste into a folder." },
    { name: "Delete", text: "Moves items to the workspace's trash folder, after asking. To get one back, move it out of trash in File Explorer." },
    { name: "Search", text: "The search box at the top (Ctrl+K) looks through the whole workspace, not just the open folder." },
  ] };

export const PUBLIC_HELP: HelpTopic = {
  title: "Automation (Public)", about: "The agent repository in One CommandCenter, shared with your team. Agents here have versions; the live version is the one runs, schedules and triggers use.",
  items: [
    { name: "Folders", text: "The repository's folders, as in Private. Pick one to see its sub-folders and agents; a search looks through every folder." },
    { name: "Check out", text: "Copies the latest version into the same folder in your workspace so you can change it, and stops others from checking it out meanwhile. Change it, then press Check in in the editor. If your own item already has that name in that folder, rename or move it first." },
    { name: "Open my copy", text: "Shown for agents you have checked out: opens your private copy as it is, with any changes you have not checked in yet." },
    { name: "Latest and Live", text: "Latest is the newest version checked in; Live is the one that runs. They differ when a new version waits to be made live." },
    { name: "Columns", text: "Shows more about each agent: where it can run, its labels, the code analysis of its latest version, its size, and who changed it last." },
    { name: "Checked out", text: "Who is working on the agent now. Only they, or an administrator, can check it in or undo the check-out." },
    { name: "Versions", text: "In the row's ⋮ menu: every version with who checked it in, when and what changed." },
    { name: "Check out a version", text: "In the row's ⋮ menu: pick an earlier version to check out. Check it in again to make it the newest version, for example to bring back one that worked." },
    { name: "Agents it runs", text: "Checking out an agent also copies the agents it runs into your workspace when you do not have them yet. They are not checked out, so others can still change them." },
    { name: "Undo check-out", text: "In the row's ⋮ menu, for your own check-outs: lets others check the agent out again. Your private copy stays; its changes are not checked in." },
    { name: "Sign in", text: "The Public tab needs a sign-in to One CommandCenter. When your Private workspace runs on the same server, your browser sign-in is used." },
  ] };

export const EDITOR_HELP: HelpTopic = {
  title: "Agent editor", about: "An agent is a list of steps run from START to END. Pick an action on the left to add a step, select a step to change its settings on the right, and press Run or Debug at the top to try it.",
  items: [
    { name: "Adding steps", text: "Click an action in the Actions panel to add it after the selected step, or drag it to where it goes. Select START to add at the very beginning." },
    { name: "Selecting steps", text: "Click a step; Ctrl+click or Shift+click selects several; the arrow keys move between steps. The toolbar then shows Cut, Copy, Delete, Duplicate, Switch off and Breakpoint." },
    { name: "⋮ on a step", text: "Everything for that step: run from here, run only this step, move up or down, fold, breakpoint and more. Right-click a step for the same menu." },
    { name: "Switch off", text: "Keeps a step in the agent but skips it when the agent runs (Ctrl+/). Switch it on again the same way." },
    { name: "List and Flow", text: "Shows the steps as a numbered list or as a flow chart. Both edit the same agent." },
    { name: "Record and Capture", text: "Record clicks and typing in a browser or a Windows application and turn them into steps; Capture adds one step for a single web element or desktop control." },
    { name: "Assistant", text: "Describe what the agent should do and it suggests steps, or ask it to explain the agent." },
    { name: "Run and Debug", text: "Run (Ctrl+F5) runs the agent on this computer as it is in the editor. Debug (F5) stops at breakpoints (F9) so you can step through with F10, F11 and Shift+F11; Shift+F5 stops." },
    { name: "Save and Check in", text: "Save (Ctrl+S) keeps the agent in your workspace. Check in sends it to One CommandCenter as a new version for others to run. The dialog lists the agents it runs: new ones are checked in with it, first, and ones checked out to you can be ticked to go in too." },
    { name: "Undo and Redo", text: "Ctrl+Z and Ctrl+Y, or the arrows at the left of the toolbar." },
  ] };

export const ACTIONS_HELP: HelpTopic = {
  title: "Actions", about: "Every action your toolkits offer, grouped by toolkit. An action becomes a step of the agent.",
  items: [
    { name: "Search actions", text: "Type part of a name, such as 'excel' or 'click'. Enter adds the best match after the selected step." },
    { name: "Adding", text: "Click an action to add it after the selected step, or drag it into the step list where it should go." },
    { name: "Hide and width", text: "The arrow at the top folds the panel away; drag its edge to make it wider or narrower." },
  ] };

export const DETAILS_HELP: HelpTopic = {
  title: "Details", about: "Settings of the selected step and of the whole agent, in four tabs.",
  items: [
    { name: "Properties", text: "The selected step's settings. Fields marked required must be filled before the agent can run; a value can be typed, or taken from a variable with the picker next to the field." },
    { name: "Variables", text: "The agent's variables: inputs it is given, outputs it returns, and values it keeps while it runs. Add one with its type; the ⋮ on a variable renames, copies or deletes it." },
    { name: "Triggers", text: "Start this agent on its own on this computer, for example every few minutes or when a file appears, while One AgentStudio is running." },
    { name: "Dependencies", text: "What the agent uses: other agents it runs, files, forms and toolkits. Click one to open it or to go to the step that uses it." },
    { name: "Width and hiding", text: "Drag the panel's edge to resize it, or fold it away with the arrow at the top." },
  ] };

export const OUTPUT_HELP: HelpTopic = {
  title: "Output", about: "What checking and running the agent tell you.",
  items: [
    { name: "Problems", text: "What stops the agent from running, such as a required field left empty. Check now looks again without saving; click a problem to go to its step." },
    { name: "Run output", text: "What happens while the agent runs: each step, its time, and the result. While a debug run waits at a step, Continue (F5), Step over (F10), Step into (F11) and Step out (Shift+F11) show here." },
    { name: "Debug values", text: "The variables' values at the step where a debug run is waiting." },
    { name: "Place and Minimise", text: "Put the panel at the bottom, at the right or floating; Minimise folds it to a bar." },
  ] };
