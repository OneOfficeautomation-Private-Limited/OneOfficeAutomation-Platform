import { errorText } from "../api/client";
import { useEffect, useMemo, useRef, useState, type CSSProperties, type ClipboardEvent, type PointerEvent as ReactPointerEvent, type DragEvent, type KeyboardEvent, type MouseEvent } from "react";
import { simplePackages } from "./simpleMode";
import { SapImportDialog } from "./sapRecording";
import type { AgentSummary, Builtins, DebugCommand, Flow, FlowStep, OpenWindow, PackageManifest, Problem, RetryPolicy, StudioApi } from "../api/client";
import { RetryFields } from "./ErrorPolicy";
import { AgentRepoFiles } from "./RepoFiles";
import { actionFor, desktopActionFor, fillWindowFromCapture, longRecordedVariables, recordedVariables, shareDesktopWindow, shortenRecordedNames, type Captured } from "./capture";
import { WhereDialog } from "./CaptureEditor";
import { AssistantDialog, DependenciesPanel, FlowDiagram } from "./AgentsTogether";
import { Button, Dialog, Field, HelpButton, Icon, IconButton, IconTile, is, keyOf, Menu, Select, SidePanel, Splitter, TabPanel, Tabs, TextArea, TextInput, usePanelSize, usePersistent, withKey, type MenuItem, reportMinor } from "../components";
import { ACTIONS_HELP, DETAILS_HELP, EDITOR_HELP, OUTPUT_HELP } from "./studioHelp";
import { ConditionEditor } from "./ConditionEditor";
import {
  addBranch, addOnError, blockIds, CLIP_KEY, setCatch, copySteps, disabledState, duplicateSteps, findStep, foldedSet, inHandler, innerLists, isBlock, moveManyTo, moveSelection, ON_ERROR, partList, parseSection,
  pasteSteps, readClip, removeBranch, removeOnError, removeSteps, sectionKey, setArg, setCatchCodes, setElseIfCondition, setFolded, setOnError,
  replaceInSteps, setNote, spotFor, START, toggleDisabledMany, topSteps, type Clip, type Part, type Place,
  moveCase, parseLoose, setCaseValues, showValue,
  addTransition, moveState, moveTransition, renameState, setStateFinal, setTransition,
  addSubflow, listOf, removeSubflow, renameSubflow, swapSubflow,
} from "./flowEdit";
import { DebugValues, RunOutput, runningStep, type LiveRun } from "./RunPanel";
import { CommitInput, StepForm } from "./StepForm";
import { TriggersPanel } from "./TriggersPanel";
import { KINDS, TRIGGER_GROUPS } from "../shell/triggerKinds";
import { lastValues } from "./variables";
import { ConnectorsDialog, SavedConnectionsDialog } from "./WorkspaceTools";
import { VariableDialog, VariablesPanel, VarsContext, type NewVariable, type VarsCtx, type WhereChoice } from "./VariablesPanel";

/** The address of the agent's first Open page step, to suggest when a capture opens a new page. */
const openUrl = (flow: Flow): string | undefined => {
  const find = (list: FlowStep[]): string | undefined => { for (const s of list) {
    if (s.action === "ooa.browser/open" && typeof s.args?.url === "string" && !s.args.url.startsWith("$")) return s.args.url;
    const inner = find([...(s.children ?? []), ...(s.else ?? [])]); if (inner) return inner; } return undefined; };
  return find(flow.steps);
};
import { VariableChooser } from "./ValueSources";
import { agentRows, describeAction, startState, stepRows, summarize, toolkitMark, type StepRow } from "./toolkits";
import { AA_NAMES } from "./aaNames";
import { FORMER_NAMES } from "./formerNames";
import { MailSwitchDialog } from "./MailSwitchDialog";

/**
 * Actions kept so older agents still run, but no longer offered in the library (usage review item 26): Open file is
 * part of Open program/file, as in AA.
 */
export const RETIRED_ACTIONS = new Set(["ooa.application/open_file"]);
/** The AA command name that matched a search, when the action's own name did not. */
const aaMatch = (id: string, q: string) => q ? AA_NAMES[id]?.find((n) => n.toLowerCase().includes(q)) : undefined;
/** A name the action had before it was renamed, when it matched a search and its own name did not. */
const formerMatch = (id: string, q: string) => q ? FORMER_NAMES[id]?.find((n) => n.toLowerCase().includes(q)) : undefined;

/** Drag data: a step being moved, or an action from the library being added. */
const STEP_DRAG = "application/x-ooa-step";
const ACTION_DRAG = "application/x-ooa-action";

/**
 * The action library on the left: toolkits with their mark and count, searchable, one open at a time. Clicking an
 * action adds it after the selected step (or inside it, when it is a block); dragging puts it where it is dropped.
 */
/** The triggers in the command list: the workspace's own (Details > Triggers) and One CommandCenter's (after check-in). */
const STUDIO_TRIGGERS: [string, string][] = [["schedule", "On a schedule"], ["file", "When a file arrives in a folder"], ["webhook", "When a web address is called (webhook)"]];

export function ActionsPanel({ packages: all, onAdd, onTrigger, simple = false, onSimple }: { packages: PackageManifest[]; onAdd: (action: string) => void;
  onTrigger?: (kind: string, where: "studio" | "server") => void; simple?: boolean; onSimple?: (on: boolean) => void }) {
  // Simple mode (G47): everyday toolkits under plain names; no triggers
  const packages = (simple ? simplePackages(all) : all).map((p) => ({ ...p, actions: p.actions.filter((a) => !RETIRED_ACTIONS.has(p.id + "/" + a.id)) }));
  if (simple) onTrigger = undefined;
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const q = query.trim().toLowerCase();
  const [hidden, setHidden] = usePersistent("panel.actions.hidden", false);
  const [width, setWidth] = usePanelSize("actions");
  const panel = useRef<HTMLElement | null>(null);
  const total = packages.reduce((n, p) => n + p.actions.length, 0);
  // Enter in the search adds the best match: same name, then a name starting with the words, then the first found
  const best = () => {
    const all = list.flatMap(({ p, actions }) => actions.map((a) => ({ id: p.id + "/" + a.id, name: a.name.toLowerCase() })));
    return (all.find((a) => a.name === q) ?? all.find((a) => a.name.startsWith(q)) ?? all[0])?.id;
  };
  // a search also finds an action by its AA command name ("Open program/file", "Simulate keystrokes")
  const list = packages.map((p) => ({ p, actions: q ? p.actions.filter((a) => a.name.toLowerCase().includes(q) || p.name.toLowerCase().includes(q) || !!aaMatch(p.id + "/" + a.id, q) || !!formerMatch(p.id + "/" + a.id, q)) : p.actions }))
    .filter((x) => x.actions.length).sort((a, b) => a.p.name.localeCompare(b.p.name));
  return (
    <><SidePanel title="Actions" side="left" collapsed={hidden} onToggle={() => setHidden(!hidden)} className="st-actions"
      actions={<HelpButton topic={ACTIONS_HELP} />}
      style={width ? { width } : undefined} panelRef={(el) => { panel.current = el; }}>
      {/* Editor A (improvement list 12 Nov 2026, item 6): Simple became Common, a two-way switch beside All */}
      {onSimple && <div className="st-common-switch" role="radiogroup" aria-label="Which actions">
        <button type="button" role="radio" aria-checked={simple} onClick={() => onSimple(true)}
          title="The everyday actions only (Excel, e-mail, websites, files), for business users"><span aria-hidden="true">★ </span>Common</button>
        <button type="button" role="radio" aria-checked={!simple} onClick={() => onSimple(false)} title="Every action of every toolkit">All</button>
      </div>}
      <div className="st-panel-search"><TextInput aria-label="Search actions" placeholder={`Search ${total.toLocaleString()} actions`} value={query} onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter" && q) { e.preventDefault(); const b = best(); if (b) onAdd(b); } }} /></div>
      <p className="st-panel-hint">{simple ? "Everyday actions. Click one to add it after the selected step, or drag it to where it goes. All shows every action."
        : "Click an action to add it after the selected step, or drag it to where it goes. Enter adds the best match."}</p>
      <ul className="st-groups">
        {onTrigger && (() => {
          // as AA's editor, the triggers that start the agent sit beside its actions
          type Item = [kind: string, label: string, where: "studio" | "server"];
          const all: [string, Item[]][] = [["In this workspace", STUDIO_TRIGGERS.map(([k, l]): Item => [k, l, "studio"])],
            ...TRIGGER_GROUPS.map(([g, ks]): [string, Item[]] => ["One CommandCenter: " + g.toLowerCase() + " (after check-in)", ks.map((k): Item => [k, KINDS[k]?.label ?? k, "server"])])];
          const kinds = all.map(([g, ks]): [string, Item[]] => [g, ks.filter(([, l]) => !q || l.toLowerCase().includes(q) || "trigger".includes(q))]).filter(([, ks]) => ks.length);
          if (!kinds.length) return null;
          const isOpen = open === "triggers" || (!!q && list.length <= 3);
          return <li>
            <button type="button" className={`st-group${isOpen ? " is-open" : ""}`} aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : "triggers")}>
              <IconTile name="bolt" color="#b45309" size={18} /><span className="st-group-name">Triggers</span><span className="st-count">{kinds.reduce((n, [, ks]) => n + ks.length, 0)}</span>
            </button>
            {isOpen && <ul className="st-group-actions">{kinds.map(([g, ks]) => <li key={g}>
              <span className="st-trigger-where">{g}</span>
              <ul>{ks.map(([k, l, where]) => <li key={where + k}><button type="button" className="st-action"
                title={where === "studio" ? "Add it in Details > Triggers" : "Opens One CommandCenter's Create trigger for this agent"} onClick={() => onTrigger(k, where)}>{l}</button></li>)}</ul>
            </li>)}</ul>}
          </li>;
        })()}
        {list.map(({ p, actions }) => {
          const isOpen = open === p.id || (!!q && list.length <= 3);
          const m = toolkitMark(p.id);
          return (
            <li key={p.id}>
              <button type="button" className={`st-group${isOpen ? " is-open" : ""}`} aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : p.id)}>
                <IconTile name={m.icon} color={m.color} size={18} /><span className="st-group-name">{p.name}</span><span className="st-count">{actions.length}</span>
              </button>
              {isOpen && <ul className="st-group-actions" style={{ "--kit": m.color } as CSSProperties}>{actions.map((a) => (
                <li key={a.id}><button type="button" className="st-action" title={a.description} draggable
                  onDragStart={(e) => { e.dataTransfer.setData(ACTION_DRAG, p.id + "/" + a.id); e.dataTransfer.effectAllowed = "copy"; }}
                  onClick={() => onAdd(p.id + "/" + a.id)}>{a.name}
                  {(() => { const own = a.name.toLowerCase().includes(q); const aa = !own ? aaMatch(p.id + "/" + a.id, q) : undefined; const was = !own && !aa ? formerMatch(p.id + "/" + a.id, q) : undefined;
                    return aa ? <span className="st-aa-name">AA: {aa}</span> : was ? <span className="st-aa-name">Formerly: {was}</span> : null; })()}</button></li>
              ))}</ul>}
            </li>
          );
        })}
      </ul>
    </SidePanel>
    {!hidden && <Splitter label="Resize the actions panel" axis="x" grow={1} value={width} min={200} max={520}
      measure={() => panel.current?.getBoundingClientRect().width ?? 272} onChange={setWidth} />}</>
  );
}


/** Where a drop over a step lands: its top quarter before it, its bottom quarter after it, the middle inside a block. */
function placeFor(e: DragEvent, step: FlowStep): Place {
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
  const y = r.height ? (e.clientY - r.top) / r.height : 1;
  if (isBlock(step.action)) return y < 0.25 ? "before" : y > 0.75 ? "after" : "inside";
  return y < 0.5 ? "before" : "after";
}

/** The steps clipboard, shared by every open agent; system says the computer's clipboard holds the same copy. */
export type StepsClipboard = Clip & { system: boolean };

/**
 * One open agent, editable: numbered steps with the toolkit mark and guide lines for blocks, fixed Start and End rows,
 * block parts (Else if, Else, Catch, Finally) that can be selected and dropped on, a toolbar, a menu on every step
 * (⋮ or right-click) and keyboard shortcuts for the selected steps, drag and drop, folding, the settings of the
 * selected step, the agent's variables, and the problems the last save found. Ctrl+click adds a step to the
 * selection, Shift+click (or Shift+Up/Down) a range.
 */
/**
 * With no step selected, the agent's own settings, as AA's bot properties: its id, by which One CommandCenter's agent
 * repository and Run agent steps know it (each agent needs its own), and its description, which people and AI agents read.
 */
/** The agent's error handling with a change; empty settings are left out. */
function withErrorHandling(flow: Flow, patch: { default?: "continue"; retry?: RetryPolicy | undefined }): Flow {
  const eh = { ...(flow.errorHandling ?? {}) };
  if ("default" in patch) { if (patch.default) eh.default = patch.default; else delete eh.default; }
  if ("retry" in patch) { if (patch.retry) eh.retry = patch.retry; else delete eh.retry; }
  const next = { ...flow };
  if (Object.keys(eh).length) next.errorHandling = eh; else delete next.errorHandling;
  return next;
}

function AgentProperties({ flow, onEdit }: { flow: Flow; onEdit: (next: Flow) => void }) {
  const [id, setId] = useState(flow.id);
  const [about, setAbout] = useState(flow.description ?? "");
  useEffect(() => { setId(flow.id); setAbout(flow.description ?? ""); }, [flow.id, flow.description]);
  const bad = !/^[A-Za-z0-9_.-]+$/.test(id.trim());
  return (
    <div className="st-pad">
      <p className="st-muted">Select a step to change its settings. With none selected, these are the agent's own.</p>
      <Field label="Agent id" hint="One CommandCenter knows the agent by it, and Run agent steps find it by it; each agent needs its own. Letters, digits, _ . and -.">
        {(fid) => <TextInput id={fid} className="ui-mono" value={id} onChange={(e) => setId(e.target.value)}
          onBlur={() => { const v = id.trim(); if (!bad && v !== flow.id) onEdit({ ...flow, id: v }); }} />}</Field>
      {bad && <p className="st-alert" role="alert">An agent id has letters, digits, _ . and - only. It stays {flow.id}.</p>}
      <Field label="Description" hint="What the agent does: people who run it read this, and AI agents that may call it.">
        {(fid) => <TextArea id={fid} rows={3} value={about} onChange={(e) => setAbout(e.target.value)}
          onBlur={() => { if (about !== (flow.description ?? "")) onEdit({ ...flow, description: about }); }} />}</Field>
      {/* F1: files kept in One CommandCenter that go to the runner with the agent */}
      <AgentRepoFiles flow={flow} onEdit={onEdit} />
      {/* C3: the agent's default when a step fails outside a Try; a step's own setting wins */}
      <h3 className="st-prop-section">When a step fails</h3>
      <Field label="Outside a Try block" layout="beside" hint="A step's own If it fails setting wins. Errors inside a Try go to its Catch.">{(fid) =>
        <Select id={fid} value={flow.errorHandling?.default ?? "stop"} onChange={(e) => onEdit(withErrorHandling(flow, { default: e.target.value === "continue" ? "continue" : undefined }))}
          options={[{ value: "stop", label: "Stop the agent (On error runs first)" }, { value: "continue", label: "Log it and continue with the next step" }]} />}</Field>
      <RetryFields idPrefix="agent" value={flow.errorHandling?.retry} onChange={(r) => onEdit(withErrorHandling(flow, { retry: r }))} />
    </div>
  );
}

export function AgentView({ api, path, flow: whole, list: shown = null, onList: setShown = () => {}, packages, selected, onSelect, onEdit: onEditWhole, onInsert, problems, checked, canUndo, canRedo, onUndo, onRedo, askTrigger,
  run, breakpoints, onToggleBreakpoint, onStop, onDebug, clipboard, onClipboard, onRunFrom, onNotice, onError, onOpenChild, onOpenPath, onNewConnection, onChecked, dirty }:
  { api: StudioApi; path: string; flow: Flow;
    /** The subflow shown (null: the main steps), and switching it. */
    list?: string | null; onList?: (name: string | null) => void; packages: PackageManifest[]; selected: string[]; onSelect: (ids: string[]) => void;
    onEdit: (next: Flow, select?: string[]) => void; onInsert: (action: string, targetId: string | null, where: Place, part?: Part) => void;
    problems: Problem[]; checked: boolean; canUndo: boolean; canRedo: boolean; onUndo: () => void; onRedo: () => void;
    /** A workspace trigger kind picked in the command list (n changes each time). */
    askTrigger?: { kind: string; n: number } | null;
    run: LiveRun | null; breakpoints: string[]; onToggleBreakpoint: (id: string) => void; onStop: () => void; onDebug: (c: DebugCommand) => void;
    clipboard: StepsClipboard | null; onClipboard: (c: StepsClipboard) => void;
    onRunFrom: (stepId: string, only: boolean, defaults: boolean, note?: string) => void; onNotice: (text: string) => void; onError: (e: unknown) => void;
    onOpenChild: (stepId: string) => void; onOpenPath: (path: string) => void; onNewConnection: () => void;
    /** The problems a check without saving found. */
    onChecked: (problems: Problem[]) => void; dirty: boolean }) {
  // Subflows: the steps list shows the main steps or one subflow. Everything below edits `flow`, a view of the agent with
  // the shown subflow's steps as its steps (swapSubflow); each edit is swapped back before it reaches the agent. The
  // Studio keeps which list is shown (library clicks and drops go into it too).
  const sub = shown !== null && whole.subflows?.some((s) => s.name === shown) ? shown : null;
  const flow = useMemo(() => swapSubflow(whole, sub), [whole, sub]);
  const onEdit = (next: Flow, select?: string[]) => { if (next !== flow) onEditWhole(swapSubflow(next, sub), select); };
  const [subDialog, setSubDialog] = useState<{ rename?: string } | null>(null);
  /** Shows the list that holds a step (a selected step, or where a debugged run stopped) when it is not the one shown. */
  const showListOf = (id: string | undefined | null) => {
    if (!id || findStep(flow, id)) return;
    const where = listOf(whole, id);
    if (where !== undefined) setShown(where);
  };
  useEffect(() => { showListOf(selected.find((k) => !k.startsWith("@"))); }, [selected]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { showListOf(run?.stopped?.stepId); }, [run?.stopped?.stepId]);   // eslint-disable-line react-hooks/exhaustive-deps
  // Full screen: only the canvas (its toolbar, the steps and the details panel); the browser's own full screen when it
  // allows it, else the page area. Esc, or the button again, brings everything back.
  const [full, setFull] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const on = () => { if (!document.fullscreenElement) setFull(false); };
    document.addEventListener("fullscreenchange", on);
    return () => document.removeEventListener("fullscreenchange", on);
  }, []);
  const toggleFull = () => {
    if (full) { if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); setFull(false); return; }
    setFull(true);
    wrapRef.current?.requestFullscreen?.().catch(() => {});   // refused or not offered: the page area is used
  };
  const folded = useMemo(() => foldedSet(flow), [flow]);
  const { rows, handlerFrom } = useMemo(() => agentRows(flow, folded), [flow, folded]);
  const [rightTab, setRightTab] = useState("properties");
  useEffect(() => { if (askTrigger) { setRightTab("triggers"); setRightHidden(false); } }, [askTrigger]);   // eslint-disable-line react-hooks/exhaustive-deps
  const [rightHidden, setRightHidden] = usePersistent("panel.details.hidden", false);
  const [rightWidth, setRightWidth] = usePanelSize("details");
  // the output panel: at the bottom, on the right or floating (moved by its grip), or minimised to a bar
  const [outAt, setOutAt] = usePersistent<"bottom" | "right" | "float">("output.at", "bottom");
  const [outMin, setOutMin] = usePersistent("output.min", false);
  const [outHeight, setOutHeight] = usePanelSize("output");
  const [outWidth, setOutWidth] = usePanelSize("output.right");
  const [outPos, setOutPos] = usePersistent<{ x: number; y: number } | null>("output.float", null);
  const outPanel = useRef<HTMLElement | null>(null);
  const moveOutput = (e: ReactPointerEvent<HTMLElement>) => {
    const box = outPanel.current?.getBoundingClientRect();
    if (!box || e.button !== 0) return;
    e.preventDefault();
    const el = e.currentTarget, dx = e.clientX - box.left, dy = e.clientY - box.top;
    el.setPointerCapture?.(e.pointerId);
    const at = (ev: { clientX: number; clientY: number }) => ({ x: Math.round(Math.max(0, Math.min(window.innerWidth - 120, ev.clientX - dx))),
      y: Math.round(Math.max(0, Math.min(window.innerHeight - 60, ev.clientY - dy))) });
    const move = (ev: PointerEvent) => { const p = at(ev); if (outPanel.current) { outPanel.current.style.left = p.x + "px"; outPanel.current.style.top = p.y + "px"; } };
    const up = (ev: PointerEvent) => { el.removeEventListener("pointermove", move); el.removeEventListener("pointerup", up); setOutPos(at(ev)); };
    el.addEventListener("pointermove", move); el.addEventListener("pointerup", up);
  };
  const outStyle: CSSProperties | undefined = outAt === "float" ? (outPos ? { left: outPos.x, top: outPos.y, right: "auto", bottom: "auto" } : undefined)
    : outAt === "right" ? (outWidth ? { width: outWidth } : undefined) : outHeight ? { height: outHeight } : undefined;
  const rightPanel = useRef<HTMLElement | null>(null);
  const [drop, setDrop] = useState<{ key: string | null; where: Place } | null>(null);
  const [bottomTab, setBottomTab] = useState("problems");
  const [tool, setTool] = useState<"connections" | "connectors" | "sap" | "mail" | null>(null);
  const [checking, setChecking] = useState(false);
  /** Checks the agent as it is in the editor, without saving it. */
  const checkNow = async () => {
    setChecking(true);
    try {
      const found = await api.validate(whole);
      onChecked(found);
      setBottomTab("problems");
      onNotice(found.length ? found.length + " problem" + (found.length > 1 ? "s" : "") + " to check." : "No problems found.");
    } catch (e) { onError(e); }
    finally { setChecking(false); }
  };
  const [anchor, setAnchor] = useState<string | null>(null);   // where a Shift range starts
  const [cursor, setCursor] = useState<string | null>(null);   // the end of it that Shift+Up/Down moves
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const list = useRef<HTMLOListElement>(null);
  const pasted = useRef(false);
  const [builtins, setBuiltins] = useState<Builtins>({ sys: {}, global: {} });
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [newVar, setNewVar] = useState<NewVariable | null>(null);
  const loadBuiltins = () => { api.builtins().then(setBuiltins, (e) => reportMinor("the built-in values", e)); };
  useEffect(() => { loadBuiltins(); api.agents().then(setAgents, (e) => reportMinor("the list of agents", e)); }, [api]);
  const last = useMemo(() => lastValues(run?.events), [run?.events]);
  // Capture and Record: which window (an open browser tab, a new page, a desktop application), then the work itself
  const [whereAsk, setWhereAsk] = useState<{ mode: "capture" | "record"; tabs: OpenWindow[]; url?: string; resolve: (c: WhereChoice | null) => void } | null>(null);
  const [recording, setRecording] = useState<{ count: number } | null>(null);
  const [capturing, setCapturing] = useState(false);
  const openWindows = async () => { try { return (await api.windows()).filter((w) => w.title || w.url); } catch { return [] as OpenWindow[]; } };
  const where = async (mode: "capture" | "record", named?: { title?: string; url?: string }): Promise<WhereChoice | null> => {
    const tabs = await openWindows();
    const same = named?.title ? tabs.find((t) => (t.source ?? "browser") === "browser" && (t.endpoint || t.connector) && t.title === named.title) : undefined;
    if (same && mode === "capture") return same.connector || same.tab_id !== undefined ? { tab_id: same.tab_id, browser: same.browser || "edge" }
      : { endpoint: same.endpoint, tab_title: same.title, tab_url: same.url, browser: same.browser || "edge" };
    return new Promise((resolve) => setWhereAsk({ mode, tabs, url: named?.url ?? openUrl(flow), resolve }));
  };
  const insertSteps = (f: Flow, steps: FlowStep[]) => { const p = pasteSteps(f, { steps, variables: [] }, spotFor(f, selected, false)); onEdit(p.flow, p.ids); };
  const captureStep = async (desktop: boolean) => {
    setCapturing(true);
    try {
      if (desktop) {
        onNotice("Hover over the control in the application (a red frame follows the pointer), then press Ctrl+Shift+C.");
        const r = await api.captureDesktop({ timeout_seconds: 60, screenshot: true, highlight: true });
        const captured = (r.object ?? r) as Captured;
        const type = String((captured.properties ?? []).find((x) => x.name === "ControlType")?.value ?? "");
        const name = String((captured.properties ?? []).find((x) => x.name === "Name")?.value ?? "");
        // the window goes into a numbered window variable, shared by the steps in that window, as AA's Capture does
        const shared = shareDesktopWindow(flow, captured);
        insertSteps(shared.flow, [{ id: "new", action: "ooa.desktop_ui/object_action", args: { object: shared.obj, action: desktopActionFor(captured) },
          note: "Captured " + (type || "control") + (name ? ": " + name.slice(0, 30) : "") }]);
        onNotice("Captured " + (type || "a control") + (shared.name ? "; the window is in $" + shared.name : "") + ".");
        return;
      }
      const choice = await where("capture");
      if (!choice) return;
      onNotice("Click the element in the browser window (Esc cancels).");
      const r = await api.capture(choice);
      const object = r.object as Captured | undefined;
      const step: FlowStep = { id: "new", action: "ooa.browser/element_action", args: object ? { object, action: actionFor(r.element) } : { selector: r.selector, action: actionFor(r.element) },
        note: "Captured " + (r.element?.tag ?? "element") + (r.element?.text ? ": " + r.element.text.slice(0, 30) : "") };
      insertSteps(object ? fillWindowFromCapture(flow, step, object, true) : flow, [step]);
      onNotice("Captured " + (r.element?.tag ?? "an element") + " with " + (object?.properties?.length ?? 0) + " properties.");
    } catch (e) { onError(e); }
    finally { setCapturing(false); }
  };
  const record = async () => {
    const choice = await where("record");
    if (!choice) return;
    setRecording({ count: 0 });
    onNotice(choice.target === "desktop" ? "Recording in " + (choice.window_title ? "“" + choice.window_title + "”" : "the application") + ". Work in it, then press Stop recording on the bar at the top right of the screen."
      : "Recording in the browser. Press Stop recording on the page (or close the window) when you are done.");
    const poll = setInterval(() => { api.recordProgress().then((p) => setRecording((r) => r && { count: p.count || 0 }), () => {}); }, 800);
    try {
      const res = await api.record(choice);
      if (res.cancelled) { onNotice("Recording cancelled; nothing was added."); return; }
      const steps = res.steps ?? [];
      const v = recordedVariables(flow, steps);
      insertSteps(v.flow, steps);
      onNotice("Added " + steps.length + " recorded steps" + (v.made.length ? " and " + v.made.length + " variable" + (v.made.length === 1 ? "" : "s") + ": " + v.made.map((n) => "$" + n).join(", ") : "") + ".");
    } catch (e) { onError(e); }
    finally { clearInterval(poll); setRecording(null); }
  };
  const longNames = longRecordedVariables(flow).length;
  const [noRename, setNoRename] = useState(false);
  const vars: VarsCtx = { api, flow, builtins, agents, edit: (next) => onEdit(next), newVariable: (preset) => { setNewVar(preset); setRightHidden(false); },
    notice: onNotice, fail: onError, where, openChild: onOpenChild, openPath: onOpenPath, packages };
  const [view, setView] = usePersistent<"list" | "flow">("agent.view", "list");
  const [assistant, setAssistant] = useState<"build" | "explain" | null>(null);
  const agentName = (id: unknown) => { const a = agents.find((x) => x.id === id); return a ? a.name || a.id : String(id ?? ""); };
  useEffect(() => { if (run) setBottomTab("run"); }, [run?.id]);   // a new run: show its output
  const at = runningStep(run);
  // a step of the list on screen by its line; one in another list (a subflow, or the main steps) by its line there and the list's name
  const lineOf = (id: string) => {
    const name = (r: StepRow | undefined, where?: string) => r?.kind === "step" ? `Line ${r.n}${where ? " of " + where : ""} ${describeAction(r.step.action, packages).name}` : undefined;
    const here = name(rows.find((x) => x.kind === "step" && x.step.id === id));
    if (here) return here;
    const list = listOf(whole, id);
    if (list === undefined) return id;
    // the main steps are numbered with their On error steps after them, as Main shows them
    const other = list === null ? agentRows(whole).rows : stepRows(whole.subflows?.find((s) => s.name === list)?.steps ?? []);
    return name(other.find((x) => x.kind === "step" && x.step.id === id), list === null ? "Main" : list) ?? id;
  };
  const ids = rows.flatMap((r) => r.kind === "step" ? [r.step.id] : []);
  const chosen = ids.filter((id) => selected.includes(id));   // in the agent's order
  const one = chosen.length === 1 ? rows.find((r) => r.kind === "step" && r.step.id === chosen[0]) : undefined;
  const selStep = one?.kind === "step" ? one : null;
  const selInfo = selStep ? describeAction(selStep.step.action, packages) : null;
  const selSection = selected.length === 1 ? parseSection(selected[0]) : null;
  const sectionRow = selSection ? rows.find((r) => r.kind === "section" && r.stepId === selSection.id && r.part === selSection.part) : undefined;
  const problemSteps = new Set(problems.map((p) => p.stepId).filter(Boolean));
  const allOff = chosen.length > 0 && chosen.every((id) => rows.some((r) => r.kind === "step" && r.step.id === id && r.step.disabled));
  const blocks = blockIds(flow);
  const running = !!run && !["success", "failed", "cancelled", "stopped", "timeout"].includes(run.state);

  // keyboard focus follows the selection when it is in the steps list (arrow keys, Delete, paste)
  useEffect(() => {
    const el = list.current;
    if (!el || !el.contains(document.activeElement) || !selected.length) return;
    const key = cursor && selected.includes(cursor) ? cursor : selected[selected.length - 1];
    el.querySelector<HTMLElement>(`[data-key="${CSS.escape(key)}"]`)?.focus();
  }, [selected]);

  const act = {
    remove: () => {
      if (!chosen.length) return;
      // then select the step after the last removed one (or before the first), so Delete can be pressed again
      const after = ids.slice(ids.indexOf(chosen[chosen.length - 1]) + 1).find((id) => !chosen.includes(id));
      const before = ids.slice(0, ids.indexOf(chosen[0])).reverse()[0];
      const next = removeSteps(flow, chosen);
      const survivor = [after, before].find((id) => id && findStep(next, id));
      onEdit(next, survivor ? [survivor] : []);
    },
    up: () => { if (chosen.length) onEdit(moveSelection(flow, chosen, -1)); },
    down: () => { if (chosen.length) onEdit(moveSelection(flow, chosen, 1)); },
    duplicate: () => { if (chosen.length) { const d = duplicateSteps(flow, topSteps(flow, chosen).map((s) => s.id)); onEdit(d.flow, d.ids); } },
    disable: () => { if (chosen.length) onEdit(toggleDisabledMany(flow, chosen)); },
    copy: () => {
      const clip = copySteps(flow, chosen);
      if (!clip) return false;
      const keep = (system: boolean) => onClipboard({ ...clip, system });
      keep(false);
      // the computer's clipboard too, in the shape Classic Studio used, so older copies paste too
      navigator.clipboard?.writeText(JSON.stringify({ [CLIP_KEY]: clip })).then(() => keep(true), () => {});
      return true;
    },
    cut: () => { if (act.copy()) onEdit(removeSteps(flow, chosen), []); },
    paste: (clip: Clip | null = clipboard) => {
      if (!clip?.steps.length) return;
      const p = pasteSteps(flow, clip, spotFor(flow, selected, false));
      onEdit(p.flow, p.ids);
    },
    share: () => { const clip = copySteps(flow, chosen); if (clip) api.putSharedClipboard(clip.steps, clip.variables).catch((e) => reportMinor("the steps into the shared clipboard (they are copied here only)", e)); },
    fromShared: async () => {
      try { const c = await api.sharedClipboard(); act.paste({ steps: c.steps ?? [], variables: c.variables ?? [] }); } catch { /* nothing shared */ }
    },
    fold: (ids: string[], fold: boolean) => { if (ids.length) onEdit(setFolded(flow, ids, fold)); },
    /** AA's Enter on a step: the Details panel's Properties, with the cursor in its first field. */
    settings: () => {
      setRightTab("properties"); setRightHidden(false);
      setTimeout(() => rightPanel.current?.querySelector<HTMLElement>("input, select, textarea")?.focus(), 30);
    },
    note: () => {
      setRightTab("properties"); setRightHidden(false);
      setTimeout(() => [...document.querySelectorAll("label")].find((l) => l.textContent === "Shown on the step")?.control?.focus(), 30);
    },
  };
  const click = (e: MouseEvent, id: string) => {
    if (e.ctrlKey || e.metaKey) { onSelect(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected.filter((x) => !x.startsWith("@")), id]); setAnchor(id); setCursor(id); }
    else if (e.shiftKey && (anchor ?? chosen[0])) extendTo(id);
    else selectOne(id);
  };
  const extendTo = (id: string) => {
    const from = anchor && ids.includes(anchor) ? anchor : chosen[0] ?? id;
    const a = ids.indexOf(from), b = ids.indexOf(id);
    onSelect(ids.slice(Math.min(a, b), Math.max(a, b) + 1));
    setAnchor(from); setCursor(id);
  };
  const selectOne = (id: string) => { onSelect([id]); setAnchor(id); setCursor(id); };
  const onKeys = (ev: KeyboardEvent) => {
    if ((ev.target as HTMLElement).closest("input, textarea, select, [role=menu]")) return;
    const e = ev.nativeEvent;
    const i = chosen.length ? ids.indexOf(chosen[chosen.length - 1]) : -1;
    const c = ids.indexOf(cursor && chosen.includes(cursor) ? cursor : chosen[chosen.length - 1] ?? "");
    const chosenBlocks = chosen.filter((id) => blocks.includes(id));
    if (is(e, "steps.delete")) act.remove();
    else if (is(e, "steps.up")) act.up();
    else if (is(e, "steps.down")) act.down();
    else if (is(e, "steps.extendDown") && c >= 0 && c + 1 < ids.length) extendTo(ids[c + 1]);
    else if (is(e, "steps.extendUp") && c > 0) extendTo(ids[c - 1]);
    else if (is(e, "steps.fold") && chosenBlocks.length) act.fold(chosenBlocks, true);   // before the arrows: ← and → fold a selected block
    else if (is(e, "steps.unfold") && chosenBlocks.length) act.fold(chosenBlocks, false);
    else if (is(e, "steps.next") && i + 1 < ids.length) selectOne(ids[i + 1]);
    else if (is(e, "steps.previous") && i > 0) selectOne(ids[i - 1]);
    else if (is(e, "steps.clear") && selected.length) onSelect([]);
    else if (is(e, "steps.foldAll") && blocks.length) act.fold(blocks, true);
    else if (is(e, "steps.unfoldAll") && folded.size) onEdit(setFolded(flow, [...folded], false));
    else if (is(e, "steps.settings") && chosen.length) act.settings();
    else if (is(e, "steps.details")) setRightHidden(!rightHidden);
    else if (is(e, "steps.find")) setFinding("");
    else if (is(e, "steps.replace")) setReplacing(true);
    else if (is(e, "steps.rename") && chosen.length === 1) act.note();
    else if (is(e, "steps.record") && !recording) record();
    else if (is(e, "steps.duplicate")) act.duplicate();
    else if (is(e, "steps.all")) onSelect(ids);
    else if (is(e, "steps.copy")) act.copy();
    else if (is(e, "steps.cut")) act.cut();
    else if (is(e, "steps.paste")) {
      // a paste event follows when the browser gives one (it carries steps copied in another Studio window); on a step
      // Chrome gives none, so then the Studio's own clipboard is pasted
      pasted.current = false;
      setTimeout(() => { if (!pasted.current) act.paste(); }, 0);
      return;
    }
    else if (is(e, "steps.disable")) act.disable();
    else if (is(e, "run.breakpoint") && chosen.length) chosen.forEach(onToggleBreakpoint);
    else if (is(e, "run.clearBreakpoints") && breakpoints.length) breakpoints.forEach(onToggleBreakpoint);
    else if (is(e, "steps.menu") && chosen.length) setMenuFor(chosen[chosen.length - 1]);
    else return;
    ev.preventDefault();
  };
  // Find in this agent (AA's Ctrl+Shift+F): the steps whose name or settings hold the words are selected; Enter goes to the next
  const [finding, setFinding] = useState<string | null>(null);
  const findBox = useRef<HTMLInputElement | null>(null);
  useEffect(() => { if (finding === "") findBox.current?.focus(); }, [finding]);
  /** The steps whose line (as shown) or settings hold the words. */
  const findSteps = (q: string) => {
    const t = q.trim().toLowerCase();
    if (!t) return [];
    const shown = new Map([...document.querySelectorAll<HTMLElement>(".st-steps .st-step[data-key]")].map((b) => [b.dataset.key!, (b.textContent ?? "").toLowerCase()]));
    return rows.flatMap((r) => "step" in r && ((shown.get(r.step.id) ?? "").includes(t) || JSON.stringify(r.step).toLowerCase().includes(t)) ? [r.step.id] : []);
  };
  const [hits, setHits] = useState<string[]>([]);
  const [replacing, setReplacing] = useState(false);   // Find and replace (Ctrl+H)
  const onPaste = (e: ClipboardEvent) => {
    if ((e.target as HTMLElement).closest("input, textarea, select")) return;
    pasted.current = true;
    const fromSystem = readClip(e.clipboardData.getData("text/plain"));
    // the computer's clipboard wins unless the last Copy here could not write to it
    act.paste(fromSystem && (!clipboard || clipboard.system) ? fromSystem : clipboard);
    e.preventDefault();
  };
  const dragIds = (id: string) => selected.includes(id) ? chosen : [id];
  const dropOn = (e: DragEvent, targetId: string | null, where: Place, part?: Part) => {
    e.preventDefault();
    setDrop(null);
    const action = e.dataTransfer.getData(ACTION_DRAG);
    const steps = e.dataTransfer.getData(STEP_DRAG);
    if (action) onInsert(action, targetId, where, part);
    else if (steps) { const moving = JSON.parse(steps) as string[]; onEdit(moveManyTo(flow, moving, targetId, where, part), moving); }
  };
  const over = (e: DragEvent, key: string | null, where: Place) => { e.preventDefault(); if (drop?.key !== key || drop.where !== where) setDrop({ key, where }); };
  const menuItems = (step: FlowStep): MenuItem[] => {
    const items: MenuItem[] = [
      { key: "cut", label: "Cut", shortcut: keyOf("steps.cut"), onSelect: act.cut },
      { key: "copy", label: "Copy", shortcut: keyOf("steps.copy"), onSelect: () => { act.copy(); } },
    ];
    if (clipboard) items.push({ key: "paste", label: "Paste after", description: clipboard.steps.length + (clipboard.steps.length === 1 ? " step" : " steps") + " copied", shortcut: keyOf("steps.paste"), onSelect: () => act.paste() });
    items.push(
      { key: "share", label: "Copy to shared clipboard", description: "Paste in any agent, in any OneAgentStudio window", separatorBefore: true, onSelect: act.share },
      { key: "from-shared", label: "Paste from shared clipboard", onSelect: act.fromShared },
      { key: "rename", label: "Rename", description: "Shown on the step", shortcut: keyOf("steps.rename"), onSelect: act.note },
      { key: "duplicate", label: "Duplicate", shortcut: keyOf("steps.duplicate"), separatorBefore: true, onSelect: act.duplicate },
      { key: "disable", label: (allOff ? "Enable " : "Disable ") + (chosen.length > 1 ? "steps" : "step"),
        description: allOff ? "It runs again" : "Kept in the agent, but skipped when it runs", shortcut: keyOf("steps.disable"), onSelect: act.disable },
      { key: "delete", label: "Delete", shortcut: keyOf("steps.delete"), onSelect: act.remove },
      { key: "up", label: "Move up", shortcut: keyOf("steps.up"), separatorBefore: true, onSelect: act.up },
      { key: "down", label: "Move down", shortcut: keyOf("steps.down"), onSelect: act.down },
      { key: "note", label: step.note ? "Edit note" : "Add note", separatorBefore: true, onSelect: act.note },
      { key: "breakpoint", label: breakpoints.includes(step.id) ? "Remove breakpoint" : "Breakpoint", shortcut: keyOf("run.breakpoint"), onSelect: () => chosen.forEach(onToggleBreakpoint) },
    );
    if (step.action === "core/run_bot" && step.args?.automation && chosen.length === 1)
      items.push({ key: "child", label: "Open the agent it runs", description: agentName(step.args.automation), separatorBefore: true, onSelect: () => onOpenChild(step.id) });
    const target = step.action === "core/run_subflow" ? String(step.args?.subflow ?? "") : "";
    if (target && whole.subflows?.some((s) => s.name === target) && chosen.length === 1)
      items.push({ key: "subflow", label: "Open the subflow it runs", description: target, separatorBefore: true, onSelect: () => { setShown(target); onSelect([]); } });
    // run part of the agent: the skipped steps' variables keep the values the last run here had at this step
    // a disabled step (or one in a disabled block) does not run: Run from here starts at the next enabled step (item 25)
    const off = disabledState(flow.steps, step.id) ?? null;
    const offNote = off === "self" ? "This step is disabled, so the run starts at the next enabled step"
      : off === "inside" ? "This step is in a disabled block, so the run starts at the next enabled step after the block" : undefined;
    if (!running && chosen.length === 1 && !inHandler(flow, step.id)) items.push(   // the error handler's steps run only after an error
      { key: "from", label: "Run from here", description: offNote ?? "Earlier steps are skipped; their values come from the last run", separatorBefore: true,
        onSelect: () => onRunFrom(step.id, false, false, offNote) },
      { key: "from-defaults", label: "Run from here with default values", onSelect: () => onRunFrom(step.id, false, true, offNote) },
      { key: "only", label: "Run this step only", disabled: !!off, description: off ? "Disabled steps do not run: enable it first" : undefined,
        onSelect: () => onRunFrom(step.id, true, false) },
    );
    if (innerLists(step).length) items.push({ key: "fold", label: folded.has(step.id) ? "Unfold" : "Fold", onSelect: () => act.fold([step.id], !folded.has(step.id)) });
    return items;
  };

  /** One line of the steps list: a step, or the heading of a block's part. */
  const rowItem = (r: StepRow) => {
    if (r.kind === "section") {
      const key = sectionKey(r.stepId, r.part);
      const isSel = selected.includes(key);
      return (
        <li key={key}>
          <button type="button" data-key={key} className={`st-section${isSel ? " is-selected" : ""}${drop?.key === key ? " drop-inside" : ""}`}
            style={{ paddingLeft: 46 + r.depth * 22 }} aria-pressed={isSel} onClick={() => onSelect([key])}
            onDragOver={(e) => over(e, key, "inside")} onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, r.stepId, "inside", r.part)}>
            {r.label}
          </button>
        </li>
      );
    }
    const info = describeAction(r.step.action, packages);
    const isSel = selected.includes(r.step.id);
    const hint = (drop?.key === r.step.id ? " drop-" + drop.where : "") + (at === r.step.id ? " is-running" : "");
    const bp = breakpoints.includes(r.step.id);
    const hasParts = innerLists(r.step).length > 0;
    return (
      <li key={r.step.id} className="st-step-row"
        onContextMenu={(e) => { e.preventDefault(); if (!isSel) selectOne(r.step.id); setMenuFor(r.step.id); }}>
        <button type="button" data-key={r.step.id} className={`st-step${isSel ? " is-selected" : ""}${r.step.disabled ? " is-disabled" : ""}${hint}`} aria-pressed={isSel}
          draggable onDragStart={(e) => { e.dataTransfer.setData(STEP_DRAG, JSON.stringify(dragIds(r.step.id))); e.dataTransfer.effectAllowed = "move"; }}
          onDragOver={(e) => over(e, r.step.id, placeFor(e, r.step))}
          onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, r.step.id, placeFor(e, r.step))}
          onClick={(e) => click(e, r.step.id)}>
          <span className="st-step-n">{bp && <span className="st-bp" title="Breakpoint" aria-label="breakpoint" />}{r.n}</span>
          <span className="st-guides" style={{ width: r.depth * 22 }} />
          <IconTile name={info.mark.icon} color={info.mark.color} size={24} />
          <strong>{info.name}</strong>
          {(() => { const sum = r.step.action === "core/run_bot" && r.step.args?.automation && !r.step.note ? "runs " + agentName(r.step.args.automation) : summarize(r.step, packages);
            return <span className="st-step-sum" title={sum || undefined}>{sum}</span>; })()}
          {r.folded && <span className="st-pill">{r.hidden} {r.hidden === 1 ? "step" : "steps"} folded</span>}
          {r.step.disabled && <span className="st-badge" title="Disabled: kept in the agent, skipped when it runs">Disabled</span>}
          {r.step.retry?.max ? <span className="st-badge is-info" title="Tried again when it fails (If this step fails)">Retries {r.step.retry.max}×</span> : null}
          {r.step.continueOnError && <span className="st-badge is-info" title="Logs its error and goes on (If this step fails)">Continues on error</span>}
          {problemSteps.has(r.step.id) && <span className="st-check">CHECK</span>}
        </button>
        <span className="st-step-tools">
          {hasParts && <button type="button" className="ui-icon-btn" aria-label={(r.folded ? "Unfold line " : "Fold line ") + r.n} title={r.folded ? "Unfold" : "Fold"}
            aria-expanded={!r.folded} onClick={() => act.fold([r.step.id], !r.folded)}><Icon name={r.folded ? "chevronRight" : "chevronDown"} size={14} stroke={2.4} /></button>}
          <Menu label={"Line " + r.n + " menu"} items={menuItems(r.step)} open={menuFor === r.step.id}
            onOpenChange={(o) => { if (o && !isSel) selectOne(r.step.id); setMenuFor(o ? r.step.id : null); }}
            trigger={<button type="button" className="ui-icon-btn" aria-label={"Line " + r.n + " actions"} title="Step menu (right-click)"><Icon name="more" size={16} stroke={3} /></button>} />
        </span>
      </li>
    );
  };

  return (
    <VarsContext.Provider value={vars}>
    <div className={`st-agent-wrap is-out-${outMin ? "min" : outAt}${full ? " is-full" : ""}`} ref={wrapRef}
      onKeyDown={(e) => { if (e.key === "Escape" && full && !document.fullscreenElement) setFull(false); }}>
      <div className="st-agent">
        {replacing && <ReplaceDialog flow={flow} selected={chosen} onClose={() => setReplacing(false)}
          onReplace={(next, n) => { onEdit(next); setReplacing(false); onNotice(n + (n === 1 ? " place" : " places") + " changed. " + keyOf("app.undo") + " undoes it."); }} />}
        <section className="st-steps" aria-label="Agent steps" onKeyDown={onKeys} onPaste={onPaste}>
          {/* review round 2 (1 Nov 2026): one line, icons with their name and shortcut on hover (as AA's editor), the step
              actions only while steps are selected and Paste only while something is copied; the count is at the list's foot */}
          <div role="toolbar" aria-label="Edit steps" className="st-edit-bar">
            <button type="button" className="st-tool" aria-label="Undo" title={withKey("Undo", "app.undo")} disabled={!canUndo} onClick={onUndo}><Icon name="undo" size={16} /></button>
            <button type="button" className="st-tool" aria-label="Redo" title={withKey("Redo", "app.redo")} disabled={!canRedo} onClick={onRedo}><Icon name="redo" size={16} /></button>
            {(chosen.length > 0 || clipboard) && <span className="st-bar-gap" />}
            {chosen.length > 0 && <>
              <button type="button" className="st-tool" aria-label="Cut" title={withKey("Cut", "steps.cut")} onClick={act.cut}><Icon name="cut" size={16} /></button>
              <button type="button" className="st-tool" aria-label="Copy" title={withKey("Copy", "steps.copy")} onClick={() => act.copy()}><Icon name="copy" size={16} /></button>
            </>}
            {clipboard && <button type="button" className="st-tool" aria-label="Paste" title={withKey("Paste", "steps.paste")} onClick={() => act.paste()}><Icon name="paste" size={16} /></button>}
            {chosen.length > 0 && <>
              <button type="button" className="st-tool" aria-label="Delete" title={withKey("Delete", "steps.delete")} onClick={act.remove}><Icon name="trash" size={16} /></button>
              <button type="button" className="st-tool" aria-label="Duplicate" title={withKey("Duplicate", "steps.duplicate")} onClick={act.duplicate}><Icon name="duplicate" size={16} /></button>
              <button type="button" className="st-tool" aria-label={allOff ? "Enable" : "Disable"} title={withKey(allOff ? "Enable (it runs again)" : "Disable (kept, but skipped when the agent runs)", "steps.disable")} onClick={act.disable}><Icon name="power" size={16} /></button>
            </>}
            <Menu label="More step actions" align="start" items={[
              ...(chosen.length ? [
                { key: "up", label: "Move up", shortcut: keyOf("steps.up"), onSelect: act.up },
                { key: "down", label: "Move down", shortcut: keyOf("steps.down"), onSelect: act.down },
                { key: "bp", label: "Breakpoint", description: "Stop here when debugging", shortcut: keyOf("run.breakpoint"), onSelect: () => chosen.forEach(onToggleBreakpoint) },
              ] : []),
              ...(breakpoints.length ? [{ key: "nobp", label: "Remove every breakpoint", shortcut: keyOf("run.clearBreakpoints"), onSelect: () => breakpoints.forEach(onToggleBreakpoint) }] : []),
              { key: "all", label: "Select all", shortcut: keyOf("steps.all"), onSelect: () => onSelect(ids) },
              { key: "find", label: "Find in this agent", shortcut: keyOf("steps.find"), onSelect: () => setFinding("") },
              { key: "replace", label: "Find and replace…", shortcut: keyOf("steps.replace"), onSelect: () => setReplacing(true) },
              ...(blocks.length ? [{ key: "fold", label: "Fold all blocks", shortcut: keyOf("steps.foldAll"), separatorBefore: true, onSelect: () => act.fold(blocks, true) }] : []),
              ...(folded.size ? [{ key: "unfold", label: "Unfold all", shortcut: keyOf("steps.unfoldAll"), onSelect: () => onEdit(setFolded(flow, [...folded], false)) }] : []),
              { key: "add-subflow", label: "Add a subflow…", description: "A named list of steps that Run subflow steps run", separatorBefore: true, onSelect: () => setSubDialog({}) },
              ...(sub === null ? [flow.onError ? { key: "onerror", label: "Go to On error", description: "The steps that run when an error ends the agent", separatorBefore: true, onSelect: () => onSelect([ON_ERROR]) } : { key: "onerror", label: "Add error steps (On error)", description: "Steps that run when an error ends the agent", separatorBefore: true, onSelect: () => onEdit(addOnError(flow), [ON_ERROR]) }] : []),
              { key: "check", label: "Check the agent", description: "Find problems without saving", separatorBefore: true, onSelect: checkNow },
              { key: "connections", label: "Saved connections…", description: "A connector's address and sign-in, kept once", separatorBefore: true, onSelect: () => setTool("connections") },
              { key: "connectors", label: "Custom connectors…", description: "Import an OpenAPI, Swagger or Postman file; add a step for an operation", onSelect: () => setTool("connectors") },
              { key: "sap", label: "Import SAP GUI recording…", description: "Turn a script recorded in SAP GUI into SAP GUI steps", onSelect: () => setTool("sap") },
              { key: "mail", label: "Switch mail service…", description: "Turn the mail steps into another service's, e.g. Exchange to Microsoft 365", onSelect: () => setTool("mail") },
            ]} trigger={<button type="button" className="st-tool" aria-label="More" title="More step actions"><Icon name="more" size={16} stroke={3} /></button>} />
            {finding !== null && <span className="st-find">
              <input ref={findBox} type="search" aria-label="Find in this agent" placeholder="Find in this agent" value={finding}
                onChange={(e) => { setFinding(e.target.value); const found = findSteps(e.target.value); setHits(found); if (found.length) onSelect(found); }}
                onKeyDown={(e) => {
                  if (e.key === "Escape") { e.preventDefault(); setFinding(null); }
                  else if (e.key === "Enter" && hits.length) { e.preventDefault(); const at = hits.indexOf(chosen[chosen.length - 1] ?? ""); selectOne(hits[(at + 1) % hits.length]); }
                }} />
              <span className="st-find-n" aria-live="polite">{finding.trim() ? hits.length + (hits.length === 1 ? " step" : " steps") : ""}</span>
              <button type="button" className="ui-icon-btn" aria-label="Close the search" onClick={() => setFinding(null)}><Icon name="close" size={12} /></button>
            </span>}
            <span className="st-bar-gap" />
            <div className="st-seg st-view-seg" role="radiogroup" aria-label="Show the steps as">
              {([["list", "List"], ["flow", "Flow"]] as const).map(([k, t]) => <button key={k} type="button" role="radio" aria-checked={view === k} className="st-seg-btn" onClick={() => setView(k)}>{t}</button>)}
            </div>
            {/* Editor X (improvement list 13 Nov 2026, item 5): Assistant, Record and Capture as one squared tool group */}
            <span className="st-tool-group" role="group" aria-label="Build steps">
            <button type="button" className="st-tool-text is-ai" title="Assistant: build steps from a description, or explain this agent" onClick={() => setAssistant("build")}><Icon name="spark" size={13} />Assistant</button>
            {recording
              ? <span className="st-tool-busy" role="status"><span className="st-rec-dot" aria-hidden="true" />Recording…</span>
              : <button type="button" className="st-tool-text is-rec" title={withKey("Record actions in a web browser or a Windows application", "steps.record")} onClick={record}><span className="st-rec-dot" aria-hidden="true" />Record</button>}
            {capturing
              ? <span className="st-tool-busy" role="status">Capturing…</span>
              : <Menu label="Capture" align="start" items={[
                  { key: "web", label: "Capture a web element", description: "Click it in a browser; adds an action for it", onSelect: () => captureStep(false) },
                  { key: "desktop", label: "Capture a desktop control", description: "Hover it in a Windows application and press Ctrl+Shift+C", onSelect: () => captureStep(true) },
                ]} trigger={<button type="button" className="st-tool-text is-cap" title="Capture a web element or a desktop control"><Icon name="target" size={13} />Capture</button>} />}
            </span>
            <button type="button" className="ui-icon-btn st-full-btn" aria-pressed={full} onClick={() => toggleFull()}
              aria-label={full ? "Exit full screen" : "Full screen"} title={full ? "Exit full screen (Esc)" : "Full screen: only the canvas"}>
              <Icon name={full ? "shrink" : "expand"} size={16} stroke={2.2} /></button>
            <HelpButton topic={EDITOR_HELP} />
          </div>
          {!!whole.subflows?.length && <div className="st-subflow-bar">
            <div className="st-seg" role="radiogroup" aria-label="Steps shown">
              {[null, ...whole.subflows.map((s) => s.name)].map((n) => <button key={n ?? "@main"} type="button" role="radio" aria-checked={sub === n} className="st-seg-btn"
                title={n === null ? "The agent's main steps" : "Subflow " + n} onClick={() => { if (sub !== n) { setShown(n); onSelect([]); } }}>{n ?? "Main"}</button>)}
            </div>
            <button type="button" className="st-tool" aria-label="Add a subflow" title="Add a subflow" onClick={() => setSubDialog({})}><Icon name="plus" size={16} /></button>
            {sub !== null && <Menu label={"Subflow " + sub} align="start" items={[
              { key: "rename", label: "Rename subflow…", description: "Run subflow steps follow the new name", onSelect: () => setSubDialog({ rename: sub }) },
              { key: "delete", label: "Delete subflow", description: "With its steps; " + keyOf("app.undo") + " brings it back",
                onSelect: () => { onEditWhole(removeSubflow(whole, sub), []); setShown(null); onNotice("Deleted subflow " + sub + ". " + keyOf("app.undo") + " undoes it."); } },
            ]} trigger={<button type="button" className="st-tool" aria-label={"Subflow " + sub + " actions"} title="Rename or delete this subflow"><Icon name="more" size={16} stroke={3} /></button>} />}
          </div>}
          {subDialog && <SubflowDialog rename={subDialog.rename} onClose={() => setSubDialog(null)} onSave={(name) => {
            try {
              if (subDialog.rename !== undefined) onEditWhole(renameSubflow(whole, subDialog.rename, name));
              else onEditWhole(addSubflow(whole, name), []);
              setShown(name.trim()); setSubDialog(null);
              return null;
            } catch (e) { return errorText(e); }
          }} />}
          {longNames > 0 && !noRename && <div className="st-offer" role="status">
            <span>{longNames} recorded variable{longNames === 1 ? " has a long name" : "s have long names"} (made from page titles or field labels). Rename to Window_1, Text_1, Choice_1 …?</span>
            <Button kind="primary" onClick={() => { onEdit(shortenRecordedNames(flow)); onNotice("Renamed the recorded variables to short numbered names. Undo with Ctrl+Z."); }}>Rename</Button>
            <Button kind="plain" onClick={() => setNoRename(true)}>Not now</Button>
          </div>}
          {view === "flow" ? <FlowDiagram flow={flow} packages={packages} selected={selected} running={at}
            onSelect={(id) => { selectOne(id); setRightTab("properties"); }} onInsert={onInsert}
            onMove={(moving, targetId, where) => onEdit(moveManyTo(flow, moving, targetId, where), moving)} /> :
          <ol className="st-step-list" ref={list}>
            <li>
              <button type="button" data-key={START} className={`st-marker-row${selected.includes(START) ? " is-selected" : ""}${drop?.key === START ? " drop-inside" : ""}`}
                aria-pressed={selected.includes(START)} onClick={() => onSelect([START])}
                onDragOver={(e) => over(e, START, "before")} onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, null, "before")}>
                <span className="st-marker st-start">START</span>
              </button>
            </li>
            {rows.slice(0, handlerFrom).map(rowItem)}
            {!ids.length && <li><p className="st-empty-steps">No steps yet. Pick an action in the Actions panel on the left, or drag one here.</p></li>}
            <li>
              <div className={`st-marker-row${drop?.key === null ? " drop-inside" : ""}`}
                onDragOver={(e) => over(e, null, "after")} onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, null, "after")}>
                <span className="st-marker st-end">END</span>
              </div>
            </li>
            {flow.onError && <>
              {/* the agent's error handler: its own heading, then its steps, numbered on from the last line */}
              <li>
                <button type="button" data-key={ON_ERROR} className={`st-marker-row st-onerror-row${selected.includes(ON_ERROR) ? " is-selected" : ""}${drop?.key === ON_ERROR ? " drop-inside" : ""}`}
                  aria-pressed={selected.includes(ON_ERROR)} onClick={() => onSelect([ON_ERROR])}
                  onDragOver={(e) => over(e, ON_ERROR, "inside")} onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, ON_ERROR, "inside")}>
                  <span className="st-marker st-onerror">ON ERROR</span>
                  <span className="st-onerror-sum">{onErrorSummary(flow)}</span>
                </button>
              </li>
              {rows.slice(handlerFrom).map(rowItem)}
              {handlerFrom === rows.length && <li><p className="st-empty-steps">No error steps yet. Select ON ERROR, then pick actions: they run when an error ends the agent.</p></li>}
            </>}
          </ol>}
          <p className="st-steps-meta st-steps-foot" aria-live="polite">{chosen.length > 1 ? `${chosen.length} selected of ` : ""}{ids.length} steps{problems.length ? ` · ${problems.length} problem${problems.length > 1 ? "s" : ""}` : ""}</p>
        </section>

        {!rightHidden && <Splitter label="Resize the details panel" axis="x" grow={-1} value={rightWidth} min={260} max={680}
          measure={() => rightPanel.current?.getBoundingClientRect().width ?? 340} onChange={setRightWidth} />}
        <SidePanel title="Details" label="Properties, variables and triggers" side="right" collapsed={rightHidden} actions={<HelpButton topic={DETAILS_HELP} />}
          onToggle={() => setRightHidden(!rightHidden)} className="st-right" style={rightWidth ? { width: rightWidth } : undefined}
          panelRef={(el) => { rightPanel.current = el; }}>
          <Tabs label="Right panel" value={rightTab} onChange={setRightTab}
            tabs={[{ value: "properties", label: "Properties" }, { value: "variables", label: "Variables" }, { value: "triggers", label: "Triggers" }, { value: "deps", label: "Dependencies" }]}>
            <TabPanel value="properties">
              {selStep && selInfo ? <>
                <div className="st-prop-head"><IconTile name={selInfo.mark.icon} color={selInfo.mark.color} size={28} />
                  <div><strong>{selInfo.name}</strong><div>Line {selStep.n} · {selInfo.toolkit}</div></div></div>
                {selInfo.description && <p className="st-prop-about">{selInfo.description}</p>}
                <StepForm step={selStep.step} packages={packages}
                  onArg={(name, v) => onEdit(setArg(flow, selStep.step.id, name, v))}
                  onNote={(n) => onEdit(setNote(flow, selStep.step.id, n))} />
                <BlockParts step={selStep.step} onAdd={(kind) => { const b = addBranch(flow, selStep.step.id, kind); if (b.part) onEdit(b.flow, [sectionKey(selStep.step.id, b.part)]); }} />
              </> : selSection && sectionRow?.kind === "section" ? (
                <PartProperties flow={flow} label={sectionRow.label} id={selSection.id} part={selSection.part} onEdit={onEdit} />
              ) : selected.includes(ON_ERROR) && flow.onError ? <OnErrorProperties flow={flow} onEdit={onEdit} />
              : selected.includes(START) ? <p className="st-pad">START is selected: actions you click in the library go first, before line 1. Pasted steps go there too.</p>
                : chosen.length > 1
                ? <p className="st-pad">{chosen.length} steps selected. Cut, Copy, Delete, Move, Duplicate and Disable act on all of them; select one step to change its settings.</p>
                : <AgentProperties flow={flow} onEdit={onEdit} />}
              {!selected.length && <div className="st-pad">
                <h3 className="st-prop-section">If an error ends the agent</h3>
                {flow.onError
                  ? <p>{onErrorSummary(flow)}. <Button kind="plain" onClick={() => onSelect([ON_ERROR])}>Change</Button></p>
                  : <><p className="st-muted">Steps that run once when an error no Try block catches ends the agent, before its windows and files close: take a screenshot, close applications, send a mail.</p>
                    <Button kind="secondary" icon="plus" onClick={() => onEdit(addOnError(flow), [ON_ERROR])}>Add error steps (On error)</Button></>}
              </div>}
            </TabPanel>
            <TabPanel value="variables"><VariablesPanel flow={flow} builtins={builtins} last={last} onEdit={onEdit} onRefresh={loadBuiltins} /></TabPanel>
            <TabPanel value="triggers">
              <TriggersPanel api={api} path={path} kind={askTrigger?.kind} />
            </TabPanel>
            <TabPanel value="deps">
              {rightTab === "deps" && <DependenciesPanel api={api} path={path} dirty={dirty} onOpen={onOpenPath} onStep={(id) => { selectOne(id); setRightTab("properties"); }} />}
            </TabPanel>
          </Tabs>
        </SidePanel>
      </div>

      {outMin
        ? <div className="st-out-mini" role="region" aria-label="Output">
            <strong>Output</strong>
            {checked && problems.length > 0 && <span className="st-check">{problems.length} problem{problems.length > 1 ? "s" : ""}</span>}
            {run && <span className={`st-run-state is-${run.state}`}>Run: {run.state}</span>}
            <Button kind="plain" onClick={() => setOutMin(false)}>Show output</Button>
          </div>
        : <>
      {outAt !== "float" && <Splitter label="Resize the output panel" axis={outAt === "right" ? "x" : "y"} grow={-1}
        value={outAt === "right" ? outWidth : outHeight} min={outAt === "right" ? 280 : 120} max={outAt === "right" ? 820 : Math.max(240, window.innerHeight - 220)}
        measure={() => { const r = outPanel.current?.getBoundingClientRect(); return (outAt === "right" ? r?.width : r?.height) ?? 260; }}
        onChange={outAt === "right" ? setOutWidth : setOutHeight} />}
      <section className="st-bottom" aria-label="Output" ref={outPanel} style={outStyle}>
        <Tabs label="Output" value={bottomTab} onChange={setBottomTab}
          actions={<>
            <HelpButton topic={OUTPUT_HELP} />
            {outAt === "float" && <span className="st-out-grip" onPointerDown={moveOutput} title="Drag to move the output panel">⠿ Move</span>}
            <select className="ui-input st-out-place" aria-label="Place the output panel" value={outAt} onChange={(e) => setOutAt(e.target.value as typeof outAt)}>
              <option value="bottom">Bottom</option><option value="right">Right</option><option value="float">Floating</option>
            </select>
            <Button kind="plain" onClick={() => setOutMin(true)} title="Fold the output panel to a bar">Minimise</Button>
          </>}
          tabs={[{ value: "problems", label: <>Problems{problems.length > 0 && <span className="st-check st-tab-count">{problems.length}</span>}</> },
            { value: "run", label: "Run output" }, { value: "values", label: "Debug values" }]}>
          <TabPanel value="problems">
            <div role="region" aria-label="Problems">
              <div className="st-pad st-row-tight"><Button kind="secondary" onClick={checkNow} disabled={checking}>{checking ? "Checking…" : "Check now"}</Button>
                <span className="st-muted">Finds problems without saving; Save checks too.</span></div>
              {!checked ? <p className="st-muted st-pad">Not checked yet.</p>
                : problems.length === 0 ? <p className="st-pad">No problems found at the last check.</p>
                : <ul className="st-problem-list">{problems.map((p, i) => {
                    const row = rows.find((r) => r.kind === "step" && r.step.id === p.stepId);
                    // a step in a list not shown now (the main steps or another subflow): name the list and offer to show it
                    const other = !row && p.stepId ? listOf(whole, p.stepId) : undefined;
                    return (
                      <li key={i}>
                        <span className="st-check">CHECK</span>
                        <span>{row?.kind === "step" ? `Line ${row.n}: ` : other !== undefined ? (other === null ? "Main steps: " : `Subflow ${other}: `) : ""}{p.message}{p.suggestedFix ? ` (${p.suggestedFix})` : ""}</span>
                        {row?.kind === "step" && <Button kind="plain" onClick={() => { onSelect([p.stepId!]); setRightTab("properties"); }}>Go to line {row.n}</Button>}
                        {other !== undefined && <Button kind="plain" onClick={() => { onSelect([p.stepId!]); setRightTab("properties"); }}>Show the step</Button>}
                      </li>
                    );
                  })}</ul>}
            </div>
          </TabPanel>
          <TabPanel value="run"><RunOutput run={run} stepName={lineOf} onStop={onStop} onDebug={onDebug} /></TabPanel>
          <TabPanel value="values"><DebugValues run={run} /></TabPanel>
        </Tabs>
      </section></>}
      {assistant && <AssistantDialog api={api} flow={flow} packages={packages} initial={assistant} onClose={() => setAssistant(null)}
        onNewConnection={() => { setAssistant(null); onNewConnection(); }}
        onInsert={(proposal) => {
          const have = new Set((flow.variables ?? []).map((v) => v.name));
          const p = pasteSteps(flow, { steps: proposal.steps ?? [], variables: (proposal.variables ?? []).filter((v) => v?.name && !have.has(v.name)) }, spotFor(flow, selected, false));
          onEdit(p.flow, p.ids); setAssistant(null);
          onNotice("Inserted " + p.ids.length + " step" + (p.ids.length === 1 ? "" : "s") + ". Review them before running.");
        }} />}
      {whereAsk && <WhereDialog mode={whereAsk.mode} tabs={whereAsk.tabs} url={whereAsk.url} onRefresh={openWindows}
        onChoose={(c) => { whereAsk.resolve(c); setWhereAsk(null); }} onClose={() => { whereAsk.resolve(null); setWhereAsk(null); }} />}
      {tool === "connections" && <SavedConnectionsDialog api={api} packages={packages} onClose={() => setTool(null)} onNotice={onNotice} onError={onError} />}
      {tool === "connectors" && <ConnectorsDialog api={api} onClose={() => setTool(null)} onNotice={onNotice} onError={onError}
        onInsert={(step) => { insertSteps(flow, [step]); onNotice("Added a Call operation step for " + String(step.note ?? "the operation") + "."); }} />}
      {tool === "mail" && <MailSwitchDialog flow={flow} packages={packages} onClose={() => setTool(null)}
        onApply={(f, note) => { onEdit(f); setTool(null); onNotice(note); }} />}
      {tool === "sap" && <SapImportDialog onClose={() => setTool(null)} onInsert={(steps, note) => { insertSteps(flow, steps); setTool(null); onNotice(note); }} />}
      {recording && <Dialog open title="Recording" width={460} onClose={() => { api.recordCancel().catch(() => {}); }}
        footer={<Button kind="secondary" onClick={() => { api.recordCancel().catch(() => {}); }}>Cancel</Button>}>
        <p role="status">Recording actions (<strong>{recording.count}</strong>)…</p>
        <p className="st-muted">Press Stop recording in the browser or on the bar at the top right of the screen when you are done.</p>
      </Dialog>}
      {newVar && <VariableDialog flow={flow} preset={newVar} onClose={() => setNewVar(null)}
        onSave={(next, name) => { setNewVar(null); onEdit(newVar.then ? newVar.then(name, next) : next); }} />}
    </div>
    </VarsContext.Provider>
  );
}

/** The parts an If or a Try can have, with buttons to add them; each new part is selected so clicked actions go in it. */
/** Find and replace (AA's Ctrl+H) in the steps' settings: the whole agent or the selected steps; the count shows first. */
function ReplaceDialog({ flow, selected, onClose, onReplace }: { flow: Flow; selected: string[]; onClose: () => void; onReplace: (next: Flow, count: number) => void }) {
  const [find, setFind] = useState("");
  const [to, setTo] = useState("");
  const [matchCase, setMatchCase] = useState(false);
  const [onlySelected, setOnlySelected] = useState(selected.length > 1);
  const result = useMemo(() => replaceInSteps(flow, find, to, matchCase, onlySelected ? selected : []), [flow, find, to, matchCase, onlySelected, selected]);
  return (
    <Dialog open title="Find and replace" width={520} onClose={onClose} footer={<>
      <span className="st-muted">{find ? result.count + (result.count === 1 ? " place" : " places") + (onlySelected ? " in the selected steps" : " in this agent") : ""}</span>
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!result.count} onClick={() => onReplace(result.flow, result.count)}>Replace all</Button></>}>
      <Field label="Find" hint="In the steps' settings, outputs and names; not in ids or actions.">{(id) => <TextInput id={id} autoFocus value={find} onChange={(e) => setFind(e.target.value)} />}</Field>
      <Field label="Replace with">{(id) => <TextInput id={id} value={to} onChange={(e) => setTo(e.target.value)} />}</Field>
      <label className="st-check-line"><input type="checkbox" checked={matchCase} onChange={(e) => setMatchCase(e.target.checked)} /> Match case</label>
      {selected.length > 0 && <label className="st-check-line"><input type="checkbox" checked={onlySelected} onChange={(e) => setOnlySelected(e.target.checked)} /> {selected.length === 1 ? "Only the selected step (and the steps inside it)" : "Only the " + selected.length + " selected steps (and the steps inside them)"}</label>}
    </Dialog>
  );
}

/** Name a new subflow, or rename one; onSave returns a message when the name cannot be used. */
function SubflowDialog({ rename, onClose, onSave }: { rename?: string; onClose: () => void; onSave: (name: string) => string | null }) {
  const [name, setName] = useState(rename ?? "");
  const [error, setError] = useState<string | null>(null);
  const save = () => setError(onSave(name));
  return (
    <Dialog open title={rename === undefined ? "Add a subflow" : "Rename subflow " + rename} width={440} onClose={onClose} footer={<>
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!name.trim()} onClick={save}>{rename === undefined ? "Add" : "Rename"}</Button></>}>
      <Field label="Name" hint="Run subflow steps choose it by this name. Letters, digits, spaces, _ and -.">{(id) =>
        <TextInput id={id} autoFocus value={name} onChange={(e) => { setName(e.target.value); setError(null); }}
          onKeyDown={(e) => { if (e.key === "Enter" && name.trim()) { e.preventDefault(); save(); } }} />}</Field>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {rename === undefined && <p className="st-muted">A subflow is a list of steps kept beside the main steps. It uses the agent's variables, and runs where a Run subflow step runs it.</p>}
    </Dialog>
  );
}

function BlockParts({ step, onAdd }: { step: FlowStep; onAdd: (kind: "elseIf" | "else" | "catch" | "finally" | "cases" | "default" | "states") => void }) {
  if (step.action !== "core/if" && step.action !== "core/try" && step.action !== "core/switch" && step.action !== "core/state_machine") return null;
  return <>
    <h3 className="st-prop-section">Parts</h3>
    <div className="st-row st-pad">
      {step.action === "core/if" && <>
        <Button kind="secondary" icon="plus" onClick={() => onAdd("elseIf")}>Else if</Button>
        {!step.else && <Button kind="secondary" icon="plus" onClick={() => onAdd("else")}>Else</Button>}
      </>}
      {step.action === "core/try" && <>
        <Button kind="secondary" icon="plus" onClick={() => onAdd("catch")}>Catch</Button>
        {!step.finally && <Button kind="secondary" icon="plus" onClick={() => onAdd("finally")}>Finally</Button>}
      </>}
      {step.action === "core/switch" && <>
        <Button kind="secondary" icon="plus" onClick={() => onAdd("cases")}>Case</Button>
        {!step.default && <Button kind="secondary" icon="plus" onClick={() => onAdd("default")}>Default</Button>}
      </>}
      {step.action === "core/state_machine" && <Button kind="secondary" icon="plus" onClick={() => onAdd("states")}>State</Button>}
    </div>
    {step.action === "core/state_machine" && <p className="st-pad st-muted">Select a state's heading in the list to rename it, mark it final or add transitions; select a transition's heading to choose where it leads and when.</p>}
  </>;
}

/**
 * A State machine's state: its name, whether the machine starts or ends here, its place, and adding a transition. The
 * first state is the start unless the start setting names another.
 */
function StateFields({ flow, id, k, onEdit }: { flow: Flow; id: string; k: number; onEdit: (next: Flow, select?: string[]) => void }) {
  const [error, setError] = useState<string | null>(null);
  const step = findStep(flow, id)?.step;
  const st = step?.states?.[k];
  if (!step || !st) return null;
  const isStart = st.name === startState(step);
  const count = step.states!.length;
  const rename = (t: string) => { try { setError(null); onEdit(renameState(flow, id, k, t)); } catch (e) { setError(errorText(e)); } };
  const select = (m: { flow: Flow; part: Part | null }) => { if (m.part) onEdit(m.flow, [sectionKey(id, m.part)]); };
  return <>
    <Field label="Name" layout="beside">{(fid) => <CommitInput key={id + k + st.name} id={fid} initial={st.name} onCommit={rename} />}</Field>
    {error && <p className="st-alert" role="alert">{error}</p>}
    <label className="st-check-line"><input type="checkbox" checked={isStart} disabled={isStart}
      onChange={() => onEdit(setArg(flow, id, "initial", k === 0 ? undefined : st.name))} /> Start here{isStart ? " (the start state)" : ""}</label>
    <label className="st-check-line"><input type="checkbox" checked={!!st.final} onChange={(e) => onEdit(setStateFinal(flow, id, k, e.target.checked))} /> Final: the state machine ends after this state's steps</label>
    {st.final && !!st.transitions?.length && <p className="st-alert" role="status">A final state has no transitions: remove them, or untick Final.</p>}
    <div className="st-row">
      {!st.final && <Button kind="secondary" icon="plus" onClick={() => select(addTransition(flow, id, k))}>Transition</Button>}
      {count > 1 && <>
        <Button kind="secondary" icon="up" disabled={k === 0} onClick={() => select(moveState(flow, id, k, -1))}>Move state up</Button>
        <Button kind="secondary" icon="down" disabled={k === count - 1} onClick={() => select(moveState(flow, id, k, 1))}>Move state down</Button>
      </>}
    </div>
  </>;
}

/** A transition: the state it leads to, and when it is taken (a condition, or otherwise: when no transition above it is). */
function TransitionFields({ flow, id, k, j, onEdit }: { flow: Flow; id: string; k: number; j: number; onEdit: (next: Flow, select?: string[]) => void }) {
  const step = findStep(flow, id)?.step;
  const t = step?.states?.[k]?.transitions?.[j];
  const [conditional, setConditional] = useState(t?.when !== undefined);
  if (!step || !t) return null;
  const names = (step.states ?? []).map((x) => x.name);
  const count = step.states![k].transitions!.length;
  const select = (m: { flow: Flow; part: Part | null }) => { if (m.part) onEdit(m.flow, [sectionKey(id, m.part)]); };
  const when = conditional || t.when !== undefined;
  return <>
    <Field label="Goes to" layout="beside">{(fid) => <Select id={fid} value={t.to} onChange={(e) => onEdit(setTransition(flow, id, k, j, { to: e.target.value }))}
      options={[...names.map((n) => ({ value: n, label: n })), ...(names.includes(t.to) ? [] : [{ value: t.to, label: t.to + " (not a state)" }])]} />}</Field>
    <Field label="Taken" layout="beside" hint="Transitions are tried from the top; the first that holds is taken.">{() =>
      <div className="st-seg" role="radiogroup" aria-label="When the transition is taken">
        {([[true, "When a condition holds"], [false, "Otherwise (no condition)"]] as const).map(([c, label]) =>
          <button key={label} type="button" role="radio" aria-checked={when === c} className="st-seg-btn"
            onClick={() => { setConditional(c); if (!c) onEdit(setTransition(flow, id, k, j, { when: undefined })); }}>{label}</button>)}
      </div>}</Field>
    {when && <Field label="Condition">{() =>
      <ConditionEditor key={id + ":" + k + ":" + j} value={t.when} onCommit={(v) => onEdit(setTransition(flow, id, k, j, { when: v }))} />}</Field>}
    {count > 1 && <div className="st-row">
      <Button kind="secondary" icon="up" disabled={j === 0} onClick={() => select(moveTransition(flow, id, k, j, -1))}>Move up</Button>
      <Button kind="secondary" icon="down" disabled={j === count - 1} onClick={() => select(moveTransition(flow, id, k, j, 1))}>Move down</Button>
    </div>}
  </>;
}

/**
 * The values that select a Switch case, one field each: a value to compare (text, number, $variable), or a condition
 * when the Switch matches by expression. An empty last field adds a value.
 */
function CaseValues({ flow, id, k, onEdit }: { flow: Flow; id: string; k: number; onEdit: (next: Flow, select?: string[]) => void }) {
  const step = findStep(flow, id)?.step;
  const values = step?.cases?.[k]?.values ?? [];
  const byCondition = step?.args?.match === "expression";
  const put = (i: number, v: unknown) => { const next = [...values]; next[i] = v; onEdit(setCaseValues(flow, id, k, next)); };
  const drop = (i: number) => onEdit(setCaseValues(flow, id, k, values.filter((_, j) => j !== i)));
  return (
    <div className="st-case-values">
      {[...values, undefined].map((v, i) => (
        <div key={id + ":" + k + ":" + i + ":" + values.length + ":" + JSON.stringify(v ?? null)} className="st-row-tight">
          <span className="st-bar-fill">{byCondition
            ? <ConditionEditor value={v} onCommit={(c) => put(i, c)} />
            : <CommitInput initial={showValue(v)} mono want="Any" label={i < values.length ? "Value " + (i + 1) : "New value"}
                placeholder={i < values.length ? "" : i ? "Another value" : "A value, e.g. Approved or $status"}
                onCommit={(t) => { const p = parseLoose(t); if (p === undefined) { if (i < values.length) drop(i); } else put(i, p); }} />}</span>
          {i < values.length && <IconButton icon="trash" label={"Remove value " + (i + 1)} onClick={() => drop(i)} />}
        </div>
      ))}
    </div>
  );
}

/** The error handler in a few words: what it keeps the error in and how the run ends after it. */
function onErrorSummary(flow: Flow): string {
  const h = flow.onError;
  if (!h) return "";
  const n = (h.steps ?? []).length;
  return (n ? n + (n === 1 ? " step" : " steps") : "No steps") + (h.as ? ", error in $" + h.as : "")
    + (h.then === "succeed" ? ", then the run succeeds" : ", then the run fails");
}

/**
 * Settings of the agent's error handler (On error): the variable that gets the error, whether the run fails (the
 * default, with the original error) or succeeds after the handler, a screenshot first; and removing it.
 */
function OnErrorProperties({ flow, onEdit }: { flow: Flow; onEdit: (next: Flow, select?: string[]) => void }) {
  const h = flow.onError!;
  const n = (h.steps ?? []).length;
  return (
    <div>
      <div className="st-prop-head"><div><strong>On error</strong><div>Runs once when an error no Try block catches ends the agent, while its windows and files are still open. Steps you click in the library go at its end{n ? ` (${n} now)` : ""}.</div></div></div>
      <div className="st-prop-fields">
        <Field label="Keep the error message in" hint="A text variable, for a log line or an e-mail." layout="beside">{() =>
          <VariableChooser value={h.message_to} type="String" label="Keep the error message in" nameHint="error_message"
            onPick={(f, n) => setOnError(f, { message_to: n ?? "" })} />}</Field>
        <Field label="Keep the line number in" hint="A number variable: the line that failed." layout="beside">{() =>
          <VariableChooser value={h.line_to} type="Number" label="Keep the line number in" nameHint="error_line"
            onPick={(f, n) => setOnError(f, { line_to: n ?? "" })} />}</Field>
        <Field label="Keep the whole error in" hint="A Dictionary variable: code, message, suggestedFix, stepId, line, action and stack." layout="beside">{() =>
          <VariableChooser value={h.as} type="Dictionary" label="Keep the whole error in" nameHint="error"
            onPick={(f, n) => setOnError(f, { as: n ?? "" })} />}</Field>
        <Field label="Then" hint="Fail keeps the original error, so One CommandCenter, retries and parent agents see the real cause." layout="beside">{() =>
          <div className="st-seg" role="radiogroup" aria-label="After the error steps">
            {([["fail", "Fail the run"], ["succeed", "Succeed with a warning"]] as const).map(([k, t]) =>
              <button key={k} type="button" role="radio" aria-checked={(h.then ?? "fail") === k} className="st-seg-btn"
                onClick={() => onEdit(setOnError(flow, { then: k }))}>{t}</button>)}
          </div>}</Field>
        <label className="st-check-line"><input type="checkbox" checked={!!h.screenshot} onChange={(e) => onEdit(setOnError(flow, { screenshot: e.target.checked }))} /> Take a screenshot first (saved under screenshots/)</label>
      </div>
      <div className="st-pad">
        <Button kind="secondary" icon="trash" onClick={() => onEdit(removeOnError(flow), [])}>Remove On error{n ? ` and its ${n} step${n > 1 ? "s" : ""}` : ""}</Button>
      </div>
    </div>
  );
}

/**
 * A Catch part's settings in AA's order (usage review item 19, 9 Nov 2026): the errors it catches, whether its steps go
 * on after one of them fails, where the error message and line number go; then ours: the whole error, a screenshot,
 * and raising the error again after its steps.
 */
function CatchFields({ flow, id, k, onEdit }: { flow: Flow; id: string; k: number; onEdit: (next: Flow, select?: string[]) => void }) {
  const c = findStep(flow, id)?.step.catch?.[k];
  if (!c) return null;
  const codes = c.codes ?? [];
  return <>
    <Field label="Errors it catches" hint="Error codes or kinds (NOT_FOUND, TIMEOUT, FILE.*), separated by commas. Empty catches every error." layout="beside">{(fid) =>
      <CommitInput key={id + k + codes.join(",")} id={fid} mono initial={codes.join(", ")} placeholder="Any error"
        onCommit={(t) => onEdit(setCatchCodes(flow, id, k, t.split(",").map((x) => x.trim()).filter(Boolean)))} />}</Field>
    <label className="st-check-line"><input type="checkbox" checked={!!c.continueOnError} onChange={(e) => onEdit(setCatch(flow, id, k, { continueOnError: e.target.checked }))} />
      On error, continue with the next step (every step of this Catch runs, even when one fails)</label>
    <Field label="Keep the error message in" hint="A text variable, for a log line or an e-mail." layout="beside">{() =>
      <VariableChooser value={c.message_to} type="String" label="Keep the error message in" nameHint="error_message" onPick={(f, n) => setCatch(f, id, k, { message_to: n })} />}</Field>
    <Field label="Keep the line number in" hint="A number variable: the line that failed." layout="beside">{() =>
      <VariableChooser value={c.line_to} type="Number" label="Keep the line number in" nameHint="error_line" onPick={(f, n) => setCatch(f, id, k, { line_to: n })} />}</Field>
    <Field label="Keep the whole error in" hint="A Dictionary variable: code, message, suggestedFix, stepId, line, action and stack." layout="beside">{() =>
      <VariableChooser value={c.as} type="Dictionary" label="Keep the whole error in" nameHint="error" onPick={(f, n) => setCatch(f, id, k, { as: n })} />}</Field>
    <label className="st-check-line"><input type="checkbox" checked={!!c.screenshot} onChange={(e) => onEdit(setCatch(flow, id, k, { screenshot: e.target.checked }))} />
      Take a screenshot when it catches an error (saved under screenshots/)</label>
    <label className="st-check-line"><input type="checkbox" checked={!!c.rethrow} onChange={(e) => onEdit(setCatch(flow, id, k, { rethrow: e.target.checked }))} />
      Raise the error again after these steps (an outer Try or On error then handles it)</label>
  </>;
}

/** Settings of a selected block part: an Else if's condition, the errors a Catch takes; and removing the part. */
function PartProperties({ flow, label, id, part, onEdit }: { flow: Flow; label: string; id: string; part: Part; onEdit: (next: Flow, select?: string[]) => void }) {
  const step = findStep(flow, id)?.step;
  if (!step) return null;
  const [kind, k] = part.split(":");
  const removed = removeBranch(flow, id, part);
  const inside = partList(step, part)?.length ?? 0;
  return (
    <div>
      <div className="st-prop-head"><div><strong>{label}</strong><div>Steps you click in the library go at the end of this part{inside ? ` (${inside} now)` : ""}.</div></div></div>
      <div className="st-prop-fields">
        {kind === "elseIf" && <Field label="Condition">{() =>
          <ConditionEditor key={id + part} value={step.elseIf?.[+k]?.condition} onCommit={(v) => onEdit(setElseIfCondition(flow, id, +k, v))} />}</Field>}
        {kind === "catch" && <CatchFields flow={flow} id={id} k={+k} onEdit={onEdit} />}
        {kind === "cases" && <Field label={step.args?.match === "expression" ? "Conditions (any one selects this case)" : "Values (any one selects this case)"}
          hint="Cases are tried from the top; only the first that matches runs.">{() => <CaseValues flow={flow} id={id} k={+k} onEdit={onEdit} />}</Field>}
        {kind === "entry" && <StateFields key={id + part} flow={flow} id={id} k={+k} onEdit={onEdit} />}
        {kind === "trans" && <TransitionFields key={id + part} flow={flow} id={id} k={+k} j={+part.split(":")[2]} onEdit={onEdit} />}
        {kind === "cases" && (step.cases?.length ?? 0) > 1 && <div className="st-row">
          <Button kind="secondary" icon="up" disabled={+k === 0} onClick={() => { const m = moveCase(flow, id, +k, -1); if (m.part) onEdit(m.flow, [sectionKey(id, m.part)]); }}>Move case up</Button>
          <Button kind="secondary" icon="down" disabled={+k === (step.cases?.length ?? 0) - 1} onClick={() => { const m = moveCase(flow, id, +k, 1); if (m.part) onEdit(m.flow, [sectionKey(id, m.part)]); }}>Move case down</Button>
        </div>}
      </div>
      {part !== "children" && removed !== flow && <div className="st-pad">
        <Button kind="secondary" icon="trash" onClick={() => onEdit(removed, [id])}>{kind === "entry" ? "Remove this state" : kind === "trans" ? "Remove this transition" : "Remove this part"}{inside ? ` and its ${inside} step${inside > 1 ? "s" : ""}` : ""}</Button>
      </div>}
    </div>
  );
}
