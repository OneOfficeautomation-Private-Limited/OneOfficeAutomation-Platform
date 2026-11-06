import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiFailure, type CrStatus, StudioApi, type AgentSummary, type Blueprint, type DebugCommand, type ModelConnections, type Flow, type FlowStep, type NewItemKind, type PackageManifest, type Problem, type RepoState, type RunOptions, type StudioInfo, type WorkspaceNode,
} from "../api/client";
import { BrandMark, Button, Dialog, Field, HelpPanel, HelpProvider, Icon, Menu, ShortcutsDialog, TextInput, inTextField, is, keyOf, usePersistent, withKey, type MenuItem } from "../components";
import { Automation, find as findNode } from "./Automation";
import { CheckInDialog, PublicRepository, type CheckInDep } from "./ControlRoom";
import { DocumentEditor, FormEditor, SkillEditor, TextEditor, WorkflowEditor } from "./ItemEditors";
import { displayName } from "./itemTypes";
import { CommandMenu, type StudioView } from "./CommandMenu";
import { ActionsPanel, AgentView, type StepsClipboard } from "./Editor";
import { addStep, insertAt, locateAny, newStep, parseLoose, stateLists, swapSubflow, type Part, type Place } from "./flowEdit";
import { isFinished, type LiveRun } from "./RunPanel";
import { aiAgentTemplate, isAiAgent } from "./aiAgent";
import { AiAgentEditor } from "./AiAgentEditor";
import { ConnectionWizard } from "./ConnectionWizard";
import { agentRows, allInstalled } from "./toolkits";
import type { Captured } from "./capture";
import { oneStep, runOnce } from "./runOnce";

/**
 * One open agent: the flow being edited, the last saved copy (to know about unsaved changes), undo and redo
 * history, the selected step, what the last save's check found, its run and its breakpoints.
 */
type AgentTab = {
  kind: "agent"; path: string; name: string; flow: Flow; saved: string; past: Flow[]; future: Flow[]; selected: string[]; problems: Problem[]; checked: boolean;
  run: LiveRun | null; breakpoints: string[];
  /** The subflow whose steps are shown and edited; none (the default) for the main steps. */
  list?: string | null;
  /** Opened from a Run agent step: the way back, parent by parent (breadcrumb). */
  trail?: Trail[];
};
type Trail = { path: string; name: string; stepId: string; line: number };
/** One open form, workflow, document type, Prompt Skill or script: its text, the saved text, and undo and redo history. */
type ItemTab = { kind: "item"; path: string; name: string; itemType: WorkspaceNode["type"]; text: string; saved: string; past: string[]; future: string[] };
type OpenTab = AgentTab | ItemTab;
const MAX_TABS = 10;
const MAX_UNDO = 100;
const EDITABLE: WorkspaceNode["type"][] = ["form", "process", "document", "skill", "script"];
const isDirty = (t: OpenTab) => t.kind === "agent" ? JSON.stringify(t.flow) !== t.saved : t.text !== t.saved;
const SCRIPT_LANGUAGE: Record<string, string> = { py: "Python script", ps1: "PowerShell script", vbs: "VBScript", js: "JavaScript" };
/** The Scripts toolkit action that runs each kind of script file. */
const SCRIPT_ACTION: Record<string, { action: string; name: string }> = { py: { action: "ooa.script/run_python", name: "Run Python" },
  ps1: { action: "ooa.script/run_powershell", name: "Run PowerShell" }, vbs: { action: "ooa.script/run_vbscript", name: "Run VBScript" }, js: { action: "ooa.script/run_javascript", name: "Run JavaScript" } };

/** The new Studio (docs/architecture/12-ui-redesign.md): One CommandCenter menu, Automation page, agents in tabs. */
export function App({ api: given }: { api?: StudioApi }) {
  // one client for the life of the page: a new one on every render would reload the data in a loop
  const [api] = useState(() => given ?? new StudioApi());
  const [info, setInfo] = useState<StudioInfo | null>(null);
  const [tree, setTree] = useState<WorkspaceNode | null>(null);
  const [packages, setPackages] = useState<PackageManifest[]>([]);
  const [clipboard, setClipboard] = useState<StepsClipboard | null>(null);   // copied steps, for every open agent
  const [blueprints, setBlueprints] = useState<Blueprint[]>([]);
  const [view, setView] = useState<StudioView>("automation");
  const [tabs, setTabs] = useState<OpenTab[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [newFolderIn, setNewFolderIn] = useState<string | null>(null);
  const [closing, setClosing] = useState<string | null>(null);   // a tab with unsaved changes being closed
  const [search, setSearch] = useState("");
  const [asking, setAsking] = useState<{ path: string; debug: boolean; from?: RunOptions } | null>(null);   // the run dialog, for an agent with inputs
  const [deleting, setDeleting] = useState<WorkspaceNode[] | null>(null);   // items or folders waiting for "Move to trash"
  const [agents, setAgents] = useState<AgentSummary[]>([]);   // for AI agents' tools
  const [connections, setConnections] = useState<ModelConnections | null>(null);
  const [wizard, setWizard] = useState(false);
  const [asSteps, setAsSteps] = useState<string[]>([]);
  const [cr, setCr] = useState<CrStatus>({ connected: false });   // One CommandCenter sign-in, for the Public tab and Check in
  const [checkingIn, setCheckingIn] = useState<string[] | null>(null);   // the items being checked in (the open agent, or ticked items)
  const [repoStates, setRepoStates] = useState<Record<string, RepoState>>({});   // each private copy's state against the repository
  const [undoing, setUndoing] = useState<{ local: string; repoPath: string } | null>(null);
  const [reverting, setReverting] = useState<string | null>(null);
  const [checkInDeps, setCheckInDeps] = useState<CheckInDep[]>([]);   // the agents one checked-in agent runs, for the review
  // Theme menu: light or dark only (one density, kept simple); kept in this browser and set on the page root for theme.css
  const [theme, setTheme] = usePersistent<"light" | "dark">("display.theme", "light");
  useEffect(() => { document.documentElement.dataset.theme = theme; }, [theme]);
  const choice = (key: string, label: string, on: boolean, pick: () => void, description: string, separatorBefore = false): MenuItem =>
    ({ key, label, description: on ? "In use. " + description : description, icon: <span className="st-menu-tick" aria-hidden="true">{on ? "✓" : ""}</span>, separatorBefore, onSelect: pick });
  const displayItems = [
    choice("light", "Light theme", theme === "light", () => setTheme("light"), "White panels in a dark frame"),
    choice("dark", "Dark theme", theme === "dark", () => setTheme("dark"), "Dark panels, easier on the eyes in a dim room"),
    { key: "keys", label: "Keyboard shortcuts", description: "Every key, and single-key shortcuts on or off", shortcut: keyOf("app.shortcuts"), separatorBefore: true, onSelect: () => setShowKeys(true) },
  ];
  const [showKeys, setShowKeys] = useState(false);
  const [askTrigger, setAskTrigger] = useState<{ kind: string; n: number } | null>(null);   // a workspace trigger picked in the command list   // AI agents shown as plain steps instead of in the AI agent editor

  const fail = (e: unknown) => setError(e instanceof ApiFailure ? e.error.message : String(e));
  const reloadTree = useCallback(() => {
    api.agents().then(setAgents).catch(() => setAgents([]));
    api.modelConnections().then(setConnections).catch(() => setConnections(null));
    return api.tree().then(setTree).catch(fail);
  }, [api]);
  useEffect(() => {
    api.info().then(setInfo).catch(fail);
    api.packages().then(setPackages).catch(fail);
    api.crStatus().then(setCr).catch(() => setCr({ connected: false }));
    api.blueprints().then((b) => setBlueprints(b ?? [])).catch(() => setBlueprints([]));   // without them Create offers a blank agent only
    reloadTree();
  }, [api, reloadTree]);

  const current = tabs.find((t) => t.path === active) ?? null;
  useEffect(() => { document.title = current ? current.name + " · One AgentStudio" : "One CommandCenter"; }, [current?.name]);   // eslint-disable-line react-hooks/exhaustive-deps
  const agent = current?.kind === "agent" ? current : null;
  const item = current?.kind === "item" ? current : null;
  // a file or folder was renamed or moved: open tabs in it follow (an item's tab takes the new name)
  const moved = (from: string, to: string) => {
    const map = (p: string) => p === from ? to : p.startsWith(from + "/") ? to + p.slice(from.length) : p;
    setTabs((all) => all.map((t) => {
      const path = map(t.path);
      if (path === t.path) return t;
      return t.kind === "item" ? { ...t, path, name: displayName({ name: path.split("/").pop()!, path, type: t.itemType }) } : { ...t, path };
    }));
    setActive((a) => a === null ? a : map(a));
  };
  const tabsRef = useRef(tabs);
  tabsRef.current = tabs;
  const patch = (path: string, change: (t: AgentTab) => AgentTab) => setTabs((all) => all.map((t) => t.path === path && t.kind === "agent" ? change(t) : t));
  const patchItem = (path: string, change: (t: ItemTab) => ItemTab) => setTabs((all) => all.map((t) => t.path === path && t.kind === "item" ? change(t) : t));

  // ---- editing: every change keeps the previous flow for Undo ----
  const edit = (next: Flow, select?: string[]) => {
    if (!agent || next === agent.flow) return;   // nothing changed (a drop on itself, a move past the end)
    patch(agent.path, (t) => ({ ...t, flow: next, past: [...t.past, t.flow].slice(-MAX_UNDO), future: [],
      selected: select ?? t.selected }));
  };
  const editItem = (text: string) => {
    if (!item || text === item.text) return;
    patchItem(item.path, (t) => ({ ...t, text, past: [...t.past, t.text].slice(-MAX_UNDO), future: [] }));
  };
  const undo = () => {
    if (agent?.past.length) patch(agent.path, (t) => ({ ...t, flow: t.past[t.past.length - 1], past: t.past.slice(0, -1), future: [t.flow, ...t.future] }));
    if (item?.past.length) patchItem(item.path, (t) => ({ ...t, text: t.past[t.past.length - 1], past: t.past.slice(0, -1), future: [t.text, ...t.future] }));
  };
  const redo = () => {
    if (agent?.future.length) patch(agent.path, (t) => ({ ...t, flow: t.future[0], future: t.future.slice(1), past: [...t.past, t.flow] }));
    if (item?.future.length) patchItem(item.path, (t) => ({ ...t, text: t.future[0], future: t.future.slice(1), past: [...t.past, t.text] }));
  };
  // a new step goes into the list shown: the main steps, or a subflow (edited as a view with its steps in front, swapped back)
  const add = (action: string) => {
    if (!agent) return;
    const list = agent.list ?? null, view = swapSubflow(agent.flow, list);
    const step = newStep(action, view, packages);
    edit(swapSubflow(addStep(view, agent.selected, step), list), [step.id]);
  };
  // an action dragged from the library onto a place in the steps
  const insert = (action: string, targetId: string | null, where: Place, part?: Part) => {
    if (!agent) return;
    const list = agent.list ?? null, view = swapSubflow(agent.flow, list);
    const step = newStep(action, view, packages);
    const next = insertAt(view, step, targetId, where, part);
    if (next !== view) edit(swapSubflow(next, list), [step.id]);
  };
  const save = async (path: string) => {
    // a field still being typed in (Ctrl+S without leaving it) commits first, then the latest flow is saved
    const focused = document.activeElement as HTMLElement | null;
    if (focused?.closest?.("input, textarea, select")) { focused.blur(); await new Promise((r) => setTimeout(r, 0)); }
    const tab = tabsRef.current.find((t) => t.path === path);
    if (!tab) return false;
    if (tab.kind === "item") {
      try {
        await api.saveItem(path, tab.text);
        patchItem(path, (t) => ({ ...t, saved: tab.text }));
        setNotice(`Saved ${tab.name}.`);
        return true;
      } catch (e) { fail(e); return false; }
    }
    try {
      const r = await api.saveBot(path, tab.flow);
      patch(path, (t) => ({ ...t, saved: JSON.stringify(tab.flow), problems: r.problems ?? [], checked: true }));
      setNotice(r.problems?.length ? `Saved ${tab.name}, with ${r.problems.length} problem${r.problems.length > 1 ? "s" : ""} to check.` : `Saved ${tab.name}.`);
      return true;
    } catch (e) { fail(e); return false; }
  };

  // Ctrl+K goes to the workspace search, as the console's search
  const searchBox = useRef<HTMLInputElement | null>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (is(e, "app.search")) { e.preventDefault(); searchBox.current?.focus(); searchBox.current?.select(); } };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  // the keys of the shortcut list (components/shortcuts.ts): save, undo and redo (outside text fields, which keep their
  // own undo); run and debug as UiPath's (F5 debug or continue, Ctrl+F5 run, Shift+F5 stop, F10 step over, F11 step into,
  // Shift+F11 step out), with AA's F6 as step over too; ? shows the Keyboard shortcuts
  const keys = useRef({ save, undo, redo, current, runKey: (_e: KeyboardEvent): boolean => false, shortcuts: () => {} });
  keys.current = { save, undo, redo, current, shortcuts: () => setShowKeys(true), runKey: (e: KeyboardEvent) => {
    const k = !agent ? null : is(e, "run.stop") ? "run.stop" : is(e, "run.run") ? "run.run" : is(e, "run.debug") ? "run.debug"
      : is(e, "run.stepOut") ? "run.stepOut" : is(e, "run.stepInto") ? "run.stepInto" : is(e, "run.stepOver") ? "run.stepOver" : null;
    if (!agent || !k) return false;
    const run = agent.run && !isFinished(agent.run) ? agent.run : null;
    if (k === "run.stop") { if (run) stopRun(); }
    else if (k === "run.debug" || k === "run.run") { if (run?.stopped) debugCommand("continue"); else if (!run) startRun(k === "run.debug"); }
    else if (!run?.stopped) return true;   // the step keys work while the run waits at a step
    else debugCommand(k === "run.stepInto" ? "stepInto" : k === "run.stepOut" ? "stepOut" : "stepOver");
    return true;
  } };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const { current: tab } = keys.current;
      const inField = inTextField(e);
      if (!inField && is(e, "app.shortcuts")) { e.preventDefault(); keys.current.shortcuts(); return; }
      if (tab && keys.current.runKey(e)) { e.preventDefault(); return; }
      if (!tab) return;
      if (is(e, "app.save")) { e.preventDefault(); keys.current.save(tab.path); }
      else if (!inField && is(e, "app.undo")) { e.preventDefault(); keys.current.undo(); }
      else if (!inField && is(e, "app.redo")) { e.preventDefault(); keys.current.redo(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  // leaving the page with unsaved changes: the browser asks first
  const anyDirty = tabs.some(isDirty);
  useEffect(() => {
    if (!anyDirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [anyDirty]);

  const open = async (n: WorkspaceNode, then?: { trail?: Trail[]; select?: string }) => {
    if (n.type !== "bot" && !EDITABLE.includes(n.type)) return;
    if (!tabs.some((t) => t.path === n.path)) {
      if (tabs.length >= MAX_TABS) { setError(`Up to ${MAX_TABS} items can be open. Close one first.`); return; }
      try {
        if (n.type === "bot") {
          const flow = await api.bot(n.path);
          setTabs((t) => [...t, { kind: "agent", path: n.path, name: flow.name || n.name, flow, saved: JSON.stringify(flow), past: [], future: [],
            selected: flow.steps[0] ? [flow.steps[0].id] : [], problems: [], checked: false, run: null, breakpoints: [] }]);
        } else {
          const it = await api.item(n.path);
          setTabs((t) => [...t, { kind: "item", path: n.path, name: displayName(n), itemType: it.type, text: it.text, saved: it.text, past: [], future: [] }]);
        }
      } catch (e) { fail(e); return; }
    }
    if (then) setTabs((all) => all.map((t) => t.path !== n.path || t.kind !== "agent" ? t
      : { ...t, ...(then.trail ? { trail: then.trail } : {}), ...(then.select ? { selected: [then.select] } : {}) }));
    setActive(n.path);
  };
  /** Opens a workspace file by its path (from Dependencies and the like). */
  const openPath = (path: string) => {
    const find = (n: WorkspaceNode | null): WorkspaceNode | null => !n ? null : n.path === path ? n : (n.children ?? []).reduce<WorkspaceNode | null>((f, c) => f ?? find(c), null);
    const n = find(tree);
    if (n) open(n); else setError(path + " is not in the workspace.");
  };
  /** Run agent step: opens the agent it runs, remembering the way back to this step. */
  const openChild = (stepId: string) => {
    if (!agent) return;
    const step = locateAny(agent.flow, stepId)?.step;   // main steps or a subflow
    const id = String(step?.args?.automation ?? "");
    const child = agents.find((a) => a.id === id);
    if (!child) { setError(id ? "Agent " + id + " is not in this workspace." : "Choose the agent to run first."); return; }
    const line = agentRows(agent.flow).rows.find((r) => r.kind === "step" && r.step.id === stepId);
    const here: Trail = { path: agent.path, name: agent.flow.name || agent.name, stepId, line: line?.kind === "step" ? line.n : 0 };
    open({ name: child.path.split("/").pop()!, path: child.path, type: "bot" }, { trail: [...(agent.trail ?? []), here] });
  };
  /** Breadcrumb: back to a parent agent, at its Run agent step. */
  const backTo = (i: number) => {
    const trail = agent?.trail ?? [];
    const t = trail[i];
    if (t) open({ name: t.path.split("/").pop()!, path: t.path, type: "bot" }, { trail: trail.slice(0, i), select: t.stepId });
  };
  /** An agent that runs this one: opens it at its Run agent step for this agent. */
  const openCaller = async (path: string) => {
    const childId = agent?.flow.id;
    const flow = tabsRef.current.find((t) => t.path === path && t.kind === "agent")?.kind === "agent" ? (tabsRef.current.find((t) => t.path === path) as AgentTab).flow : await api.bot(path).catch(() => null);
    const walk = (list: FlowStep[]): FlowStep | undefined => { for (const s of list) { if (s.action === "core/run_bot" && s.args?.automation === childId) return s;
      const inner = walk([...(s.children ?? []), ...(s.else ?? []), ...(s.finally ?? []), ...(s.elseIf ?? []).flatMap((b) => b.children ?? []), ...(s.catch ?? []).flatMap((c) => c.children ?? []),
        ...(s.cases ?? []).flatMap((c) => c.children ?? []), ...(s.default ?? []), ...stateLists(s).flat()]); if (inner) return inner; } return undefined; };
    const step = flow ? walk([...flow.steps, ...(flow.onError?.steps ?? []), ...(flow.subflows ?? []).flatMap((s) => s.steps ?? [])]) : undefined;
    open({ name: path.split("/").pop()!, path, type: "bot" }, step ? { select: step.id } : undefined);
  };
  /** A document type's extraction agent: read the document (PDF text when it can), then extract its fields with AI. */
  const makeExtraction = async (tab: ItemTab) => {
    let d: Record<string, unknown>;
    try { d = JSON.parse(tab.text); } catch { setError("Repair the document type first: it is not valid JSON."); return; }
    const fields = (Array.isArray(d.fields) ? d.fields : []) as { name: string; description?: string; type?: string; required?: boolean }[];
    if (!fields.length) { setError("Add at least one field first."); return; }
    if (isDirty(tab) && !(await save(tab.path))) return;
    const want: Record<string, string> = {};
    for (const f of fields) want[f.name] = (f.description || f.name) + (f.type && f.type !== "String" ? " (" + f.type + ")" : "") + (f.required ? "" : " (may be missing)");
    const accept = (Array.isArray(d.accept) ? d.accept : ["pdf", "txt"]) as string[];
    const usePdf = accept.includes("pdf") && packages.some((p) => p.id === "ooa.pdf" && p.actions.some((a) => a.id === "extract_text"));
    const read: FlowStep = { id: "s3", action: "ooa.file/read_text", args: { path: "$document" }, out: { text: "text" } };
    const extract: FlowStep = { id: "s4", action: "ooa.genai/extract", args: { text: "$text", fields: want, model: String(d.model || "gpt-4o-mini"), api_key: "$api_key" }, out: { fields: "fields" } };
    if (d.provider && d.provider !== "openai") extract.args!.provider = d.provider;
    const steps: FlowStep[] = usePdf ? [{ id: "s1", action: "core/if", args: { condition: { op: "endsWith", left: "$document", right: ".pdf" } },
      children: [{ id: "s2", action: "ooa.pdf/extract_text", args: { path: "$document" }, out: { text: "text" } }], else: [read] }, extract] : [read, extract];
    try {
      const parent = tab.path.includes("/") ? tab.path.slice(0, tab.path.lastIndexOf("/")) : "";
      const path = await api.create("TaskBot", parent, (String(d.name || "") || "Document") + " extraction");
      const made = await api.bot(path);
      await api.saveBot(path, { ...made, description: "Extracts " + Object.keys(want).join(", ") + " from a document with AI. Made from " + tab.path + ".",
        variables: [{ name: "document", type: "File", direction: "input", required: true, description: "The document to read (" + accept.join(", ") + ")." },
          { name: "api_key", type: "Credential", direction: "input", description: "API key for the AI service (a vault reference)." },
          { name: "text", type: "String" }, { name: "fields", type: "Dictionary", direction: "output", description: "The extracted fields." }], steps });
      await reloadTree();
      await open({ name: path.split("/").pop()!, path, type: "bot" });
      setNotice("Made " + path + ".");
    } catch (e) { fail(e); }
  };
  const forceClose = (path: string) => {
    const rest = tabs.filter((t) => t.path !== path);
    setTabs(rest);
    if (active === path) setActive(rest.length ? rest[rest.length - 1].path : null);
  };
  // ---- One CommandCenter agent repository ----
  const crHome = info?.controlRoom ?? "../";
  /**
   * Check out: the repository's version becomes the private copy at the same folder path, and opens. Open my copy (an
   * agent already checked out to me, with its copy here) opens that copy as it is, so changes not checked in stay.
   */
  const checkOut = async (repoPath: string, local?: string, mine = false, version?: number) => {
    const node = local && tree ? findNode(tree, local) : null;
    if (mine && node && !version) { await open(node); return; }
    const open1 = local ? tabsRef.current.find((t) => t.path === local) : undefined;
    if (open1 && isDirty(open1)) { setError(open1.name + " has unsaved changes. Save or close it before checking out again."); return; }
    const r = await api.crCheckout(repoPath, version);
    await reloadTree();
    const flow = await api.bot(r.local);
    if (tabsRef.current.some((t) => t.path === r.local))
      patch(r.local, (t) => ({ ...t, name: flow.name || t.name, flow, saved: JSON.stringify(flow), past: [], future: [], problems: [], checked: false }));
    else {
      if (tabsRef.current.length >= MAX_TABS) { setNotice(`Checked out v${r.version} into ${r.local}. Close an item to open it.`); return; }
      setTabs((t) => [...t, { kind: "agent", path: r.local, name: flow.name || r.local.split("/").pop()!, flow, saved: JSON.stringify(flow), past: [], future: [],
        selected: flow.steps[0] ? [flow.steps[0].id] : [], problems: [], checked: false, run: null, breakpoints: [] }]);
    }
    setActive(r.local);
    const also = r.cloned?.length ? ` Also copied ${r.cloned.length === 1 ? "the agent" : r.cloned.length + " agents"} it runs (not checked out): ${r.cloned.join(", ")}.` : "";
    setNotice((r.kept ? `Checked out to you. Your private copy ${r.local} has changes that were not checked in, so it was kept as it is.`
      : `Checked out v${r.version} into your workspace: ${r.local}.`) + also);
  };
  /** Check in: saves the open agent, then sends it to the repository as a new version (live only when asked). */
  const loadStates = useCallback(() => { api.crStates().then((r) => setRepoStates(r.states), () => setRepoStates({})); }, [api]);
  useEffect(() => { loadStates(); }, [loadStates, cr.connected, tree]);
  // one agent checked in: the agents it runs, with their states, for the review in the dialog
  useEffect(() => {
    setCheckInDeps([]);
    const one = checkingIn?.length === 1 ? checkingIn[0] : null;
    if (!one?.endsWith(".flow.json")) return;
    api.deps(one).then((d) => setCheckInDeps(d.bots.filter((b) => b.path).map((b) => ({ path: b.path!,
      name: displayName({ name: b.path!.split("/").pop()!, path: b.path!, type: "bot" }), status: repoStates[b.path!]?.status ?? "new" }))), () => {});
  }, [api, checkingIn]);   // eslint-disable-line react-hooks/exhaustive-deps
  /** Check in one item or several (up to 10, from the Private list), the agents ticked in the review first; each is saved first when it has unsaved changes. */
  const checkIn = async (comment: string, live: boolean, withDeps: string[] = []) => {
    const paths = [...withDeps.filter((p) => !(checkingIn ?? []).includes(p)), ...(checkingIn ?? [])];
    const done: string[] = [], failed: string[] = [];
    let last: Awaited<ReturnType<StudioApi["crCheckin"]>> | null = null;
    for (const path of paths) {
      const tab = tabsRef.current.find((t) => t.path === path);
      if (tab && isDirty(tab) && !(await save(path))) { failed.push(path.split("/").pop() + ": not saved"); continue; }
      try { last = await api.crCheckin(path, comment, live); done.push(last.path.split("/").pop()!.replace(/\.(flow|process|form)\.json$/, "") + " v" + last.version); }
      catch (e) { failed.push(path.split("/").pop() + ": " + (e instanceof ApiFailure ? e.error.message : String(e))); }
    }
    if (paths.length === 1 && last) setNotice(`Checked in ${last.path} as version ${last.version}` + (last.production_request ? "; it waits for approval before it goes live."
      : last.production ? "; it is now the live version." : "; the live version is unchanged."));
    else if (done.length) setNotice("Checked in " + done.join(", ") + ".");
    if (failed.length) setError("Not checked in: " + failed.join("; "));
    setCheckingIn(null);
    loadStates();
  };
  /** Revert edits: the private copy goes back to the checked-out version, and an open tab shows it. */
  const revert = async (local: string) => {
    setReverting(null);
    try {
      const r = await api.crRevert(local);
      if (tabsRef.current.some((t) => t.path === local)) {
        const flow = await api.bot(local);
        patch(local, (t) => t.kind === "agent" ? { ...t, flow, saved: JSON.stringify(flow), past: [], future: [], problems: [], checked: false } : t);
      }
      setNotice(`Reverted ${local} to the checked-out version ${r.version}.`);
      await reloadTree();
    } catch (e) { fail(e); }
  };

  const close = (path: string) => {
    const tab = tabs.find((t) => t.path === path);
    if (tab && isDirty(tab)) setClosing(path); else forceClose(path);
  };
  const created = async (kind: NewItemKind, parent: string, name: string, from: Blueprint | null) => {
    const path = await api.create(kind, parent, name);
    if (kind === "AiAgent") await api.saveBot(path, aiAgentTemplate(await api.bot(path)));
    if (from) {
      // the new agent keeps its own id and name; the blueprint gives its description, variables, steps, subflows and error handler
      const flow = await api.bot(path);
      await api.saveBot(path, { ...flow, description: from.description, variables: structuredClone(from.variables), steps: structuredClone(from.steps),
        ...(from.subflows ? { subflows: structuredClone(from.subflows) } : {}), ...(from.onError ? { onError: structuredClone(from.onError) } : {}) });
    }
    await reloadTree();
    // the new item opens in a tab, ready to edit
    const type = ({ TaskBot: "bot", AiAgent: "bot", ApiTask: "bot", form: "form", process: "process", document: "document", skill: "skill",
      "script-python": "script", "script-powershell": "script" } as const)[kind];
    await open({ name: path.split("/").pop()!, path, type });
  };

  // ---- running and debugging the open agent, on this computer ----
  const inputsOf = (f: Flow) => (f.variables ?? []).filter((v) => v.direction === "input" || v.direction === "in_out");
  const startRun = (debug: boolean) => {
    if (!agent) return;
    if (inputsOf(agent.flow).length) setAsking({ path: agent.path, debug }); else go(agent.path, {}, debug);
  };
  /** Run from here, with the last run's values or the defaults, or Run this step only, from the step menu. */
  const runFrom = (stepId: string, only: boolean, defaults: boolean) => {
    if (!agent) return;
    const from: RunOptions = only ? { only_step: stepId } : { start_at: stepId };
    if (defaults) from.carry_over = false;
    setNotice(only ? "Running only the chosen step." : "Running from the chosen step; the steps before it are skipped" + (defaults ? ", and their variables keep their default values." : "."));
    if (inputsOf(agent.flow).length) setAsking({ path: agent.path, debug: false, from }); else go(agent.path, {}, false, from);
  };
  const go = async (path: string, inputs: Record<string, unknown>, debug: boolean, from: RunOptions = {}) => {
    const tab = tabsRef.current.find((t) => t.path === path);
    if (!tab || tab.kind !== "agent") return;
    try {
      const id = await api.startRun(tab.flow, inputs, debug ? { debug: true, breakpoints: tab.breakpoints, stopAtStart: !tab.breakpoints.length } : from);
      patch(path, (t) => ({ ...t, run: { id, debug, state: "running", events: [], elapsed: 0 } }));
      follow(path, id);
    } catch (e) { fail(e); }
  };
  /** Reads what the run reports until it ends: new events only, a little slower while it waits at a breakpoint. */
  const follow = async (path: string, id: string) => {
    let after = 0;
    for (;;) {
      try {
        const st = await api.runState(id, after);
        after = st.events.reduce((m, e) => Math.max(m, e.seq), after);
        patch(path, (t) => t.run?.id !== id ? t : { ...t, run: { ...t.run, state: st.state, events: [...t.run.events, ...st.events], result: st.result,
          stopped: st.debug, elapsed: st.elapsed_ms } });
        if (["success", "failed", "cancelled", "stopped", "timeout"].includes(st.state)) return;
        const tab = tabsRef.current.find((t) => t.path === path);
        if (!tab || tab.kind !== "agent" || (tab.run && tab.run.id !== id)) return;   // the agent was closed, or a newer run replaced this one
        await new Promise((r) => setTimeout(r, st.debug ? 600 : 350));
      } catch (e) { fail(e); return; }
    }
  };
  const stopRun = () => { if (agent?.run) api.runControl(agent.run.id, "stop").catch(fail); };
  const debugCommand = (c: DebugCommand) => { if (agent?.run) api.debug(agent.run.id, c).catch(fail); };
  const toggleBreakpoint = (id: string) => {
    if (!agent) return;
    const next = agent.breakpoints.includes(id) ? agent.breakpoints.filter((x) => x !== id) : [...agent.breakpoints, id];
    patch(agent.path, (t) => ({ ...t, breakpoints: next }));
    if (agent.run?.debug && !isFinished(agent.run)) api.setBreakpoints(agent.run.id, next).catch(fail);   // also for the run in progress
  };
  const running = !!agent?.run && !isFinished(agent.run);

  const dirty = current ? isDirty(current) : false;
  const closingTab = tabs.find((t) => t.path === closing) ?? null;

  return (
    <div className="st-app">
      <header className="st-top">
        {/* one One CommandCenter as AA's Control Room: only the editor (the dev canvas) is One AgentStudio */}
        <div className="st-brand"><span className="st-logo"><BrandMark /></span>{current ? "One AgentStudio" : "One CommandCenter"}</div>
        <nav aria-label="Breadcrumb" className="st-crumbs">
          {current?.kind === "agent" && current.trail?.length
            ? current.trail.map((t, i) => <span key={i}><button type="button" className="st-crumb-link" title={"Back to " + t.name + ", at the Run agent step (line " + t.line + ")"}
                onClick={() => backTo(i)}>{t.name} › line {t.line}</button><span aria-hidden="true"> / </span></span>)
            : current ? current.path.split("/").slice(0, -1).map((p, i) => <span key={i}>{p}<span aria-hidden="true"> › </span></span>) : null}
          <span className="st-crumb-current">{current ? current.name : "Automation"}</span>
          {current?.kind === "agent" && (() => {
            const callers = agents.filter((a) => a.path !== current.path && current.flow.id && (a.runs ?? []).includes(current.flow.id));
            return callers.length ? <span className="st-crumb-callers" title="Agents in this workspace with a Run agent step for this agent"> · Run by {callers.map((a, i) =>
              <span key={a.path}>{i ? ", " : ""}<button type="button" className="st-crumb-link" onClick={() => openCaller(a.path)}>{a.name || a.id}</button></span>)}</span> : null;
          })()}
          {current && dirty && <span className="st-unsaved">Unsaved</span>}
        </nav>
        <label className="st-search"><Icon name="search" size={14} /><input ref={searchBox} aria-label="Search items" placeholder="Search items in the workspace" value={search}
          onChange={(e) => { setSearch(e.target.value); setActive(null); }} /><span className="st-search-kbd" aria-hidden="true">{keyOf("app.search").replace("+", " ")}</span></label>
        <Menu label="Display" title="Display" items={displayItems}
          trigger={<button type="button" className="st-top-icon" aria-label="Theme" title="Theme: light or dark"><Icon name="sliders" size={16} /></button>} />
        <div className="st-top-actions">
          {/* only what works today, never faded buttons: Run and Debug join here in P3 */}
          {current && <>
                <Button kind="primary" icon="save" onClick={() => save(current.path)} title={withKey("Save", "app.save")}>Save</Button>
                {!agent ? null : running
                  ? <Button kind="light" onClick={stopRun} title={withKey("Stop the run", "run.stop")}>Stop run</Button>
                  : <>
                      <Button kind="run" icon="play" onClick={() => startRun(false)} title={withKey("Run the agent as it is in the editor, on this computer", "run.run")}>Run</Button>
                      <Button kind="debug" icon="bug" onClick={() => startRun(true)} title={withKey("Run and stop at breakpoints, or at the first step", "run.debug")}>Debug</Button>
                    </>}
                {agent && <Button kind="light" icon="globe" onClick={() => setCheckingIn([current.path])} title="Check the agent in to One CommandCenter agent repository as a new version">Check in</Button>}
                <Button kind="light" icon="close" onClick={() => close(current.path)}>Close</Button>
              </>}
        </div>
      </header>

      {error && <div className="st-alert" role="alert"><span>{error}</span><Button kind="secondary" onClick={() => setError(null)}>Dismiss</Button></div>}
      {notice && !error && <div className="st-notice" role="status"><span>{notice}</span><Button kind="secondary" onClick={() => setNotice(null)}>OK</Button></div>}

      <HelpProvider><div className="st-body">
        <CommandMenu view={current ? null : view} editing={!!current} controlRoom={info?.controlRoom ?? null} onView={(v) => { setView(v); setActive(null); }} />
        {agent && !(isAiAgent(agent.flow) && !asSteps.includes(agent.path)) && <ActionsPanel packages={packages} onAdd={add} onTrigger={(kind, where) => {
          if (where === "studio") { setAskTrigger({ kind, n: Date.now() }); return; }
          // One CommandCenter's triggers start the agent checked in there: its Create trigger, for this agent
          const cr = info?.controlRoom;
          if (!cr) { setNotice("Connect One AgentStudio to One CommandCenter to add this trigger."); return; }
          const url = (extra: Record<string, string> = {}) => cr + "next/#/triggers?" + new URLSearchParams({ new: kind, bot: agent.path, ...extra }).toString();
          if (kind !== "mouse_clicked") { window.open(url(), "_blank", "noopener"); return; }
          // a click trigger: the element picker first (as Capture), then Create trigger with its window and element filled in.
          // The tab opens now, while the click still counts as the person's, and gets its address after the capture.
          const tab = window.open("about:blank", "_blank");
          if (tab) tab.opener = null;
          setNotice("Hover over the control the person will click (a red frame follows the pointer), then press Ctrl+Shift+C.");
          api.captureDesktop({ timeout_seconds: 60, screenshot: false, highlight: true }).then((r) => {
            const c = (r.object ?? r) as Captured;
            const prop = (n: string) => String((c.properties ?? []).find((x) => x.name === n)?.value ?? "");
            const w = typeof c.window === "object" && c.window ? c.window : {};
            const to = url({ ...(w.title ? { window: w.title } : {}), ...(w.process ? { process: w.process } : {}),
              ...(prop("Name") ? { element_name: prop("Name") } : {}), ...(prop("ControlType") ? { element_type: prop("ControlType") } : {}),
              ...(prop("AutomationId") ? { automation_id: prop("AutomationId") } : {}) });
            if (tab) tab.location.href = to; else window.open(to, "_blank", "noopener");
            setNotice("Captured " + (prop("ControlType") || "a control") + "; finish the trigger in One CommandCenter.");
          }, (e) => { tab?.close(); fail(e); });
        }} />}
        <main className="st-main">
          {tabs.length > 0 && (
            <div className="st-tabs" role="tablist" aria-label="Open items">
              {tabs.map((t) => (
                <div key={t.path} className={`st-tab${t.path === active ? " is-active" : ""}`}>
                  <button type="button" role="tab" aria-selected={t.path === active} onClick={() => setActive(t.path)}>
                    {t.name}{isDirty(t) && <span className="st-dot" aria-label="unsaved changes" title="Unsaved changes"> ●</span>}
                  </button>
                  <button type="button" className="st-tab-close" aria-label={"Close " + t.name} onClick={() => close(t.path)}><Icon name="close" size={12} stroke={2.6} /></button>
                </div>
              ))}
            </div>
          )}
          {agent && isAiAgent(agent.flow) && !asSteps.includes(agent.path)
            ? <AiAgentEditor key={agent.path} api={api} flow={agent.flow} onEdit={(f) => edit(f)} agents={agents} connections={connections}
                onNewConnection={() => setWizard(true)} run={agent.run} onTry={(request) => go(agent.path, { request }, false)}
                onShowSteps={() => setAsSteps([...asSteps, agent.path])} />
            : agent
            ? <>{isAiAgent(agent.flow) && <div className="st-ai-note"><span>This is an AI agent, shown as its steps.</span>
                <Button kind="secondary" icon="spark" onClick={() => setAsSteps(asSteps.filter((p) => p !== agent.path))}>Open in the AI agent editor</Button></div>}
              <AgentView key={agent.path} flow={agent.flow} packages={packages} selected={agent.selected} askTrigger={askTrigger}
                list={agent.list ?? null} onList={(l) => patch(agent.path, (t) => ({ ...t, list: l }))}
                onSelect={(ids) => patch(agent.path, (t) => ({ ...t, selected: ids }))} onEdit={edit} onInsert={insert}
                problems={agent.problems} checked={agent.checked} api={api} path={agent.path}
                run={agent.run} breakpoints={agent.breakpoints} onToggleBreakpoint={toggleBreakpoint} onStop={stopRun} onDebug={debugCommand}
                canUndo={agent.past.length > 0} canRedo={agent.future.length > 0} onUndo={undo} onRedo={redo}
                clipboard={clipboard} onClipboard={setClipboard} onRunFrom={runFrom} onNotice={setNotice} onError={fail}
                onOpenChild={openChild} onOpenPath={openPath} onNewConnection={() => setWizard(true)} dirty={isDirty(agent)}
                onChecked={(found) => patch(agent.path, (t) => ({ ...t, problems: found, checked: true }))} /></>
            : item
            ? <ItemView key={item.path} tab={item} tree={tree} onChange={editItem} canUndo={item.past.length > 0} canRedo={item.future.length > 0} onUndo={undo} onRedo={redo}
                api={api} packages={packages} onSave={() => save(item.path)} onExtraction={() => makeExtraction(item)}
                onCopyStep={(step, label) => { setClipboard({ steps: [{ id: "s1", ...step }], variables: [], system: false }); setNotice("Copied a “" + label + "” step. Open an agent and paste it (Ctrl+V or Paste)."); }} />
            : tree
              ? <Automation view={view} tree={tree}
                  publicView={<PublicRepository api={api} status={cr} home={crHome} onStatus={setCr} onCheckOut={checkOut} onNotice={setNotice} onError={fail} />} search={search} blueprints={blueprints.filter((b) => allInstalled([...b.steps, ...(b.subflows ?? []).flatMap((s) => s.steps), ...(b.onError?.steps ?? [])], packages))} onOpen={open} onCreated={created} onNewFolder={(p) => setNewFolderIn(p)} onDelete={setDeleting}
                  repo={cr.connected ? { states: repoStates, onCheckIn: setCheckingIn, onUndo: (local, repoPath) => setUndoing({ local, repoPath }), onRevert: setReverting } : undefined}
                  api={api} onMoved={moved} onChanged={() => { loadStates(); return reloadTree(); }} onNotice={setNotice} onError={fail} />
              : <p className="st-pad">Loading the workspace…</p>}
        </main>
        <HelpPanel page={active ?? view} />
        {showKeys && <ShortcutsDialog app="studio" onClose={() => setShowKeys(false)} />}
      </div></HelpProvider>

      {checkingIn && <CheckInDialog api={api} name={checkingIn.length === 1 ? displayName({ name: checkingIn[0].split("/").pop()!, path: checkingIn[0], type: "bot" }) : checkingIn.length + " items"}
        deps={checkInDeps}
        status={cr} home={crHome} onStatus={setCr} onClose={() => setCheckingIn(null)} onCheckIn={checkIn} />}
      {undoing && <Dialog open onClose={() => setUndoing(null)} title={"Undo check-out of " + undoing.local + "?"} width={480}
        footer={<><Button kind="secondary" onClick={() => setUndoing(null)}>Cancel</Button>
          <Button kind="primary" onClick={async () => { const u = undoing; setUndoing(null);
            try { await api.crUndoCheckout(u.repoPath); setNotice("Check-out undone. Your private copy stays in the workspace."); loadStates(); } catch (e) { fail(e); } }}>Undo check-out</Button></>}>
        <p>Others can check the agent out again. Your private copy stays in the workspace; changes in it are not checked in.</p>
      </Dialog>}
      {reverting && <Dialog open onClose={() => setReverting(null)} title={"Revert edits to " + reverting + "?"} width={480}
        footer={<><Button kind="secondary" onClick={() => setReverting(null)}>Cancel</Button><Button kind="danger" onClick={() => revert(reverting)}>Revert edits</Button></>}>
        <p>Your changes since you checked it out are thrown away, and the private copy goes back to the checked-out version. This cannot be undone.</p>
      </Dialog>}
      <NewFolderDialog parent={newFolderIn} onClose={() => setNewFolderIn(null)}
        onCreate={async (name) => { await api.createFolder(newFolderIn ?? "", name); await reloadTree(); setNewFolderIn(null); }} />

      {asking && (() => {
        const tab = tabs.find((t) => t.path === asking.path);
        return tab?.kind === "agent" ? <RunDialog name={tab.name} debug={asking.debug} inputs={inputsOf(tab.flow)} onClose={() => setAsking(null)}
          onRun={(values) => { setAsking(null); go(tab.path, values, asking.debug, asking.from); }} /> : null;
      })()}

      {wizard && connections && <ConnectionWizard api={api} existing={connections} onClose={() => setWizard(false)}
        onSaved={(c) => setConnections(c)} />}

      {deleting && (() => {
        const inside = (t: OpenTab) => deleting.some((d) => t.path === d.path || t.path.startsWith(d.path + "/"));
        const unsaved = tabs.filter((t) => inside(t) && isDirty(t));
        const one = deleting.length === 1 ? deleting[0] : null;
        const what = !one ? deleting.length + " items" : one.type === "folder" ? "the folder " + one.name + " and everything in it" : displayName(one);
        const remove = async () => {
          try {
            for (const d of deleting) await api.remove(d.path);
            setTabs((all) => all.filter((t) => !inside(t)));
            if (active && tabs.some((t) => t.path === active && inside(t))) setActive(null);
            setNotice("Moved " + what + " to the workspace trash.");
          } catch (e) { fail(e); }
          await reloadTree();
          setDeleting(null);
        };
        return (
          <Dialog open onClose={() => setDeleting(null)} title={"Delete " + (!one ? deleting.length + " items" : one.type === "folder" ? "folder " + one.name : displayName(one)) + "?"} width={500}
            footer={<><Button kind="secondary" onClick={() => setDeleting(null)}>Cancel</Button><Button kind="danger" icon="trash" onClick={remove}>Move to trash</Button></>}>
            <p>This moves {what} to the workspace's .trash folder. To get it back, move it out of .trash in File Explorer.</p>
            {!one && <ul className="st-delete-list">{deleting.map((d) => <li key={d.path}>{d.type === "folder" ? d.name + " (folder, with everything in it)" : displayName(d)}</li>)}</ul>}
            {unsaved.length > 0 && <p className="st-error">{unsaved.map((t) => t.name).join(", ")} {unsaved.length > 1 ? "have" : "has"} unsaved changes, which will be lost.</p>}
          </Dialog>
        );
      })()}

      {closingTab && (
        <Dialog open onClose={() => setClosing(null)} title={"Save changes to " + closingTab.name + "?"} width={480}
          footer={<>
            <Button kind="secondary" onClick={() => setClosing(null)}>Cancel</Button>
            <Button kind="secondary" onClick={() => { setClosing(null); forceClose(closingTab.path); }}>Don't save</Button>
            <Button kind="primary" onClick={async () => { if (await save(closingTab.path)) { setClosing(null); forceClose(closingTab.path); } }}>Save and close</Button>
          </>}>
          <p>The agent has changes that are not saved. If you close it without saving, they are lost.</p>
        </Dialog>
      )}
    </div>
  );
}

function NewFolderDialog({ parent, onClose, onCreate }: { parent: string | null; onClose: () => void; onCreate: (name: string) => Promise<void> }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  if (parent === null) return null;
  const submit = () => {
    if (!name.trim()) { setError("Give the folder a name."); return; }
    onCreate(name.trim()).catch((e) => setError(e instanceof ApiFailure ? e.error.message : String(e)));
  };
  return (
    <Dialog open onClose={onClose} title="New folder" width={460}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><Button kind="primary" onClick={submit}>Create folder</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }} className="st-form">
        <Field label="Name" hint={"In " + (parent || "Workspace")}>{(id) => <TextInput id={id} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        {error && <p role="alert" className="st-error">{error}</p>}
      </form>
    </Dialog>
  );
}

/** The agent's inputs for one run: each with its type, description and default. Empty means "use the default". */
function RunDialog({ name, debug, inputs, onRun, onClose }:
  { name: string; debug: boolean; inputs: Flow["variables"] & object; onRun: (values: Record<string, unknown>) => void; onClose: () => void }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const run = () => {
    const out: Record<string, unknown> = {};
    for (const v of inputs) { const x = parseLoose(values[v.name] ?? ""); if (x !== undefined) out[v.name] = x; }
    onRun(out);
  };
  return (
    <Dialog open onClose={onClose} title={(debug ? "Debug " : "Run ") + name} width={520}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button>
        <Button kind={debug ? "debug" : "run"} icon={debug ? "bug" : "play"} onClick={run}>{debug ? "Debug" : "Run"}</Button></>}>
      <form className="st-form" onSubmit={(e) => { e.preventDefault(); run(); }}>
        <p className="st-muted">The agent runs on this computer, as it is in the editor (saved or not).</p>
        {inputs.map((v) => (
          <Field key={v.name} label={"$" + v.name + " (" + v.type + ")"} hint={v.description}>{(id) =>
            <TextInput id={id} value={values[v.name] ?? ""} placeholder={v.default !== undefined ? "Default: " + String(v.default) : ""}
              onChange={(e) => setValues({ ...values, [v.name]: e.target.value })} />}</Field>
        ))}
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}

/**
 * A form, workflow, document type, Prompt Skill or script in its editor. JSON items are edited as data and written
 * back with two-space indents (as the server writes them); a file that is not valid JSON opens as text to repair.
 */
function ItemView({ tab, tree, onChange, canUndo, canRedo, onUndo, onRedo, api, packages, onSave, onExtraction, onCopyStep }:
  { tab: ItemTab; tree: WorkspaceNode | null; onChange: (text: string) => void; canUndo: boolean; canRedo: boolean; onUndo: () => void; onRedo: () => void;
    api: StudioApi; packages: PackageManifest[]; onSave: () => Promise<boolean>; onExtraction: () => void; onCopyStep: (step: Omit<FlowStep, "id">, label: string) => void }) {
  const [scriptRun, setScriptRun] = useState<{ busy: boolean; status?: string; output?: string; error?: string; exit?: unknown } | null>(null);
  let data: Record<string, unknown> | null = null;
  if (tab.itemType !== "script") { try { data = JSON.parse(tab.text); } catch { data = null; } }
  const set = (d: Record<string, unknown>) => onChange(JSON.stringify(d, null, 2));
  const ext = tab.path.split(".").pop()?.toLowerCase() ?? "";
  const cmd = tab.itemType === "script" ? SCRIPT_ACTION[ext] : undefined;
  const runnable = !!cmd && packages.some((p) => p.id === cmd.action.split("/")[0] && p.actions.some((a) => a.id === cmd.action.split("/")[1]));
  /** Runs the saved script once, as an agent's script step would, and shows what it printed. */
  const runScript = async () => {
    if (!cmd) return;
    if (tab.text !== tab.saved && !(await onSave())) return;
    setScriptRun({ busy: true });
    try {
      const r = await runOnce(api, oneStep(cmd.action, { file: tab.path, fail_on_error: false }, { output: "output", error_output: "error_output", exit_code: "exit_code" }), {}, 120000);
      setScriptRun({ busy: false, status: r.status, output: String(r.outputs?.output ?? ""), error: r.error?.message ?? String(r.outputs?.error_output ?? ""), exit: r.outputs?.exit_code });
    } catch (e) { setScriptRun({ busy: false, status: "failed", error: e instanceof ApiFailure ? e.error.message : String(e) }); }
  };
  return (
    <div className="st-item-wrap">
      <div role="toolbar" aria-label="Edit" className="st-edit-bar st-item-bar">
        <button type="button" className="st-tool" aria-label="Undo" title={withKey("Undo", "app.undo")} disabled={!canUndo} onClick={onUndo}><Icon name="undo" size={16} /></button>
        <button type="button" className="st-tool" aria-label="Redo" title={withKey("Redo", "app.redo")} disabled={!canRedo} onClick={onRedo}><Icon name="redo" size={16} /></button>
        {cmd && <>
          <span className="st-bar-gap" />
          {runnable && <Button kind="run" icon="play" onClick={runScript} disabled={scriptRun?.busy}>{scriptRun?.busy ? "Running…" : "Run script"}</Button>}
          <Button kind="plain" onClick={() => onCopyStep({ action: cmd.action, args: { file: tab.path } }, cmd.name)}>Copy as an agent step</Button>
        </>}
        {tab.itemType === "document" && <><span className="st-bar-gap" /><Button kind="secondary" onClick={onExtraction}
          title="An agent that reads a document and extracts these fields with AI">Create extraction agent</Button></>}
        <span className="st-steps-meta">{{ form: "Form", process: "Workflow", document: "Document type", skill: "Prompt Skill", script: SCRIPT_LANGUAGE[ext] ?? "Script" }[tab.itemType as string] ?? "File"}</span>
      </div>
      {tab.itemType === "script" || !data
        ? <>{!data && tab.itemType !== "script" && <p className="st-alert" role="alert">This file is not valid JSON, so it opens as text. Repair it and save.</p>}
            <TextEditor text={tab.text} onChange={onChange} language={tab.itemType === "script" ? SCRIPT_LANGUAGE[ext] ?? "Script" : "Text"} />
            {scriptRun && !scriptRun.busy && <section className="st-card st-script-out" aria-label="Script output">
              <h2>Output <span className={"st-run-state is-" + (scriptRun.status === "success" ? "success" : "failed")}>{scriptRun.status === "success" ? "Done" : scriptRun.status}</span>
                {scriptRun.exit !== undefined && <span className="st-muted"> exit code {String(scriptRun.exit)}</span>}</h2>
              <pre>{scriptRun.output || "(nothing printed)"}</pre>
              {scriptRun.error && <pre className="st-error">{scriptRun.error}</pre>}
            </section>}</>
        : tab.itemType === "form" ? <FormEditor data={data} onChange={set} />
        : tab.itemType === "process" ? <WorkflowEditor data={data} onChange={set} tree={tree} />
        : tab.itemType === "document" ? <DocumentEditor data={data} onChange={set} />
        : <SkillEditor data={data} onChange={set} name={tab.path.split("/").pop()!.replace(/\.json$/i, "")} />}
    </div>
  );
}
