import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiFailure, type CrStatus, StudioApi, type AgentSummary, type Blueprint, type DebugCommand, type ModelConnections, type Flow, type FlowStep, type NewItemKind, type PackageManifest, type Problem, type RunOptions, type StudioInfo, type WorkspaceNode,
} from "../api/client";
import { Button, Dialog, Field, Icon, Menu, TextInput, usePersistent, type MenuItem } from "../components";
import { Automation } from "./Automation";
import { CheckInDialog, PublicRepository } from "./ControlRoom";
import { DocumentEditor, FormEditor, SkillEditor, TextEditor, WorkflowEditor } from "./ItemEditors";
import { displayName } from "./itemTypes";
import { CommandMenu, type StudioView } from "./CommandMenu";
import { ActionsPanel, AgentView, type StepsClipboard } from "./Editor";
import { addStep, insertAt, locate, newStep, parseLoose, type Part, type Place } from "./flowEdit";
import { isFinished, type LiveRun } from "./RunPanel";
import { aiAgentTemplate, isAiAgent } from "./aiAgent";
import { AiAgentEditor } from "./AiAgentEditor";
import { ConnectionWizard } from "./ConnectionWizard";
import { allInstalled, stepRows } from "./toolkits";
import { oneStep, runOnce } from "./runOnce";

/**
 * One open agent: the flow being edited, the last saved copy (to know about unsaved changes), undo and redo
 * history, the selected step, what the last save's check found, its run and its breakpoints.
 */
type AgentTab = {
  kind: "agent"; path: string; name: string; flow: Flow; saved: string; past: Flow[]; future: Flow[]; selected: string[]; problems: Problem[]; checked: boolean;
  run: LiveRun | null; breakpoints: string[];
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

/** The new Studio (docs/architecture/12-ui-redesign.md): Command Center menu, Automation page, agents in tabs. */
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
  const [cr, setCr] = useState<CrStatus>({ connected: false });   // the Command Center sign-in, for the Public tab and Check in
  const [checkingIn, setCheckingIn] = useState(false);
  // Display menu: light or dark, standard or compact; kept in this browser and set on the page root for theme.css
  const [theme, setTheme] = usePersistent<"light" | "dark">("display.theme", "light");
  const [density, setDensity] = usePersistent<"standard" | "compact">("display.density", "standard");
  useEffect(() => { document.documentElement.dataset.theme = theme; document.documentElement.dataset.density = density; }, [theme, density]);
  const choice = (key: string, label: string, on: boolean, pick: () => void, description: string, separatorBefore = false): MenuItem =>
    ({ key, label, description: on ? "In use. " + description : description, icon: <span className="st-menu-tick" aria-hidden="true">{on ? "✓" : ""}</span>, separatorBefore, onSelect: pick });
  const displayItems = [
    choice("light", "Light theme", theme === "light", () => setTheme("light"), "White panels in a dark frame"),
    choice("dark", "Dark theme", theme === "dark", () => setTheme("dark"), "Dark panels, easier on the eyes in a dim room"),
    choice("standard", "Standard density", density === "standard", () => setDensity("standard"), "The usual line height", true),
    choice("compact", "Compact density", density === "compact", () => setDensity("compact"), "Shorter lines: more steps on screen"),
  ];   // AI agents shown as plain steps instead of in the AI agent editor

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
  const add = (action: string) => {
    if (!agent) return;
    const step = newStep(action, agent.flow, packages);
    edit(addStep(agent.flow, agent.selected, step), [step.id]);
  };
  // an action dragged from the library onto a place in the steps
  const insert = (action: string, targetId: string | null, where: Place, part?: Part) => {
    if (!agent) return;
    const step = newStep(action, agent.flow, packages);
    edit(insertAt(agent.flow, step, targetId, where, part), [step.id]);
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

  // Ctrl+S saves, Ctrl+Z and Ctrl+Y undo and redo (outside text fields, which keep their own undo)
  const keys = useRef({ save, undo, redo, current });
  keys.current = { save, undo, redo, current };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const { current: tab } = keys.current;
      if (!tab || !(e.ctrlKey || e.metaKey)) return;
      const k = e.key.toLowerCase();
      const inField = (e.target as HTMLElement | null)?.closest?.("input, textarea, select");
      if (k === "s") { e.preventDefault(); keys.current.save(tab.path); }
      else if (!inField && k === "z" && !e.shiftKey) { e.preventDefault(); keys.current.undo(); }
      else if (!inField && (k === "y" || (k === "z" && e.shiftKey))) { e.preventDefault(); keys.current.redo(); }
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
    const step = locate(agent.flow.steps, stepId)?.step;
    const id = String(step?.args?.automation ?? "");
    const child = agents.find((a) => a.id === id);
    if (!child) { setError(id ? "Agent " + id + " is not in this workspace." : "Choose the agent to run first."); return; }
    const line = stepRows(agent.flow.steps).find((r) => r.kind === "step" && r.step.id === stepId);
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
      const inner = walk([...(s.children ?? []), ...(s.else ?? []), ...(s.finally ?? []), ...(s.elseIf ?? []).flatMap((b) => b.children ?? []), ...(s.catch ?? []).flatMap((c) => c.children ?? [])]); if (inner) return inner; } return undefined; };
    const step = flow ? walk(flow.steps) : undefined;
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
  // ---- the Command Center agent repository ----
  const crHome = info?.controlRoom ?? "../../";
  /** Check out (or open my copy): the repository's version becomes the private copy in the workspace, and opens. */
  const checkOut = async (repoPath: string, local?: string) => {
    const open1 = local ? tabsRef.current.find((t) => t.path === local) : undefined;
    if (open1 && isDirty(open1)) { setError(open1.name + " has unsaved changes. Save or close it before checking out again."); return; }
    const r = await api.crCheckout(repoPath);
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
    setNotice(`Checked out v${r.version} into your workspace: ${r.local}.`);
  };
  /** Check in: saves the open agent, then sends it to the repository as a new version (live only when asked). */
  const checkIn = async (comment: string, live: boolean) => {
    if (!agent) return;
    if (isDirty(agent) && !(await save(agent.path))) { setCheckingIn(false); return; }
    try {
      const r = await api.crCheckin(agent.path, comment, live);
      setNotice(`Checked in ${r.path} as version ${r.version}` + (r.production_request ? "; it waits for approval before it goes live."
        : r.production ? "; it is now the live version." : "; the live version is unchanged."));
    } catch (e) { fail(e); }
    setCheckingIn(false);
  };

  const close = (path: string) => {
    const tab = tabs.find((t) => t.path === path);
    if (tab && isDirty(tab)) setClosing(path); else forceClose(path);
  };
  const created = async (kind: NewItemKind, parent: string, name: string, from: Blueprint | null) => {
    const path = await api.create(kind, parent, name);
    if (kind === "AiAgent") await api.saveBot(path, aiAgentTemplate(await api.bot(path)));
    if (from) {
      // the new agent keeps its own id and name; the blueprint gives its description, variables and steps
      const flow = await api.bot(path);
      await api.saveBot(path, { ...flow, description: from.description, variables: structuredClone(from.variables), steps: structuredClone(from.steps) });
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
        <div className="st-brand"><span className="st-logo" />One Agent Studio</div>
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
          <span className="st-preview-tag">New Studio preview</span>
        </nav>
        <label className="st-search"><Icon name="search" size={14} /><input aria-label="Search items" placeholder="Search items in the workspace" value={search}
          onChange={(e) => { setSearch(e.target.value); setActive(null); }} /></label>
        <Menu label="Display" title="Display" items={displayItems}
          trigger={<button type="button" className="st-top-icon" aria-label="Display: theme and density" title="Display: theme and density"><Icon name="sliders" size={16} /></button>} />
        <div className="st-top-actions">
          {/* only what works today, never faded buttons: Run and Debug join here in P3 */}
          {current
            ? <>
                <Button kind="primary" icon="save" onClick={() => save(current.path)} title="Save (Ctrl+S)">Save</Button>
                {!agent ? null : running
                  ? <Button kind="light" onClick={stopRun}>Stop run</Button>
                  : <>
                      <Button kind="run" icon="play" onClick={() => startRun(false)} title="Run the agent as it is in the editor, on this computer">Run</Button>
                      <Button kind="debug" icon="bug" onClick={() => startRun(true)} title="Run and stop at breakpoints (or at the first step)">Debug</Button>
                    </>}
                {agent && <Button kind="light" icon="globe" onClick={() => setCheckingIn(true)} title="Check the agent in to the Command Center agent repository as a new version">Check in</Button>}
                <Button kind="light" icon="close" onClick={() => close(current.path)}>Close</Button>
              </>
            : <a className="st-classic" href="../">Classic Studio</a>}
        </div>
      </header>

      {error && <div className="st-alert" role="alert"><span>{error}</span><Button kind="secondary" onClick={() => setError(null)}>Dismiss</Button></div>}
      {notice && !error && <div className="st-notice" role="status"><span>{notice}</span><Button kind="secondary" onClick={() => setNotice(null)}>OK</Button></div>}

      <div className="st-body">
        <CommandMenu view={current ? null : view} editing={!!current} controlRoom={info?.controlRoom ?? null} onView={(v) => { setView(v); setActive(null); }} />
        {agent && !(isAiAgent(agent.flow) && !asSteps.includes(agent.path)) && <ActionsPanel packages={packages} onAdd={add} />}
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
              <AgentView key={agent.path} flow={agent.flow} packages={packages} selected={agent.selected}
                onSelect={(ids) => patch(agent.path, (t) => ({ ...t, selected: ids }))} onEdit={edit} onInsert={insert}
                problems={agent.problems} checked={agent.checked} api={api} path={agent.path}
                run={agent.run} breakpoints={agent.breakpoints} onToggleBreakpoint={toggleBreakpoint} onStop={stopRun} onDebug={debugCommand}
                canUndo={agent.past.length > 0} canRedo={agent.future.length > 0} onUndo={undo} onRedo={redo}
                clipboard={clipboard} onClipboard={setClipboard} onRunFrom={runFrom} onNotice={setNotice} onError={fail}
                onOpenChild={openChild} onOpenPath={openPath} onNewConnection={() => setWizard(true)} dirty={isDirty(agent)} /></>
            : item
            ? <ItemView key={item.path} tab={item} tree={tree} onChange={editItem} canUndo={item.past.length > 0} canRedo={item.future.length > 0} onUndo={undo} onRedo={redo}
                api={api} packages={packages} onSave={() => save(item.path)} onExtraction={() => makeExtraction(item)}
                onCopyStep={(step, label) => { setClipboard({ steps: [{ id: "s1", ...step }], variables: [], system: false }); setNotice("Copied a “" + label + "” step. Open an agent and paste it (Ctrl+V or Paste)."); }} />
            : tree
              ? <Automation view={view} tree={tree}
                  publicView={<PublicRepository api={api} status={cr} home={crHome} onStatus={setCr} onCheckOut={checkOut} onNotice={setNotice} onError={fail} />} search={search} blueprints={blueprints.filter((b) => allInstalled(b.steps, packages))} onOpen={open} onCreated={created} onNewFolder={(p) => setNewFolderIn(p)} onDelete={setDeleting}
                  api={api} onMoved={moved} onChanged={reloadTree} onNotice={setNotice} onError={fail} />
              : <p className="st-pad">Loading the workspace…</p>}
        </main>
      </div>

      {checkingIn && agent && <CheckInDialog api={api} name={agent.name} status={cr} home={crHome} onStatus={setCr} onClose={() => setCheckingIn(false)} onCheckIn={checkIn} />}
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
        <Button kind="plain" onClick={onUndo} disabled={!canUndo} title="Undo (Ctrl+Z)">Undo</Button>
        <Button kind="plain" onClick={onRedo} disabled={!canRedo} title="Redo (Ctrl+Y)">Redo</Button>
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
