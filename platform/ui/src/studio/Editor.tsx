import { useEffect, useMemo, useRef, useState, type CSSProperties, type ClipboardEvent, type PointerEvent as ReactPointerEvent, type DragEvent, type KeyboardEvent, type MouseEvent } from "react";
import type { AgentSummary, Builtins, DebugCommand, Flow, FlowStep, OpenWindow, PackageManifest, Problem, StudioApi } from "../api/client";
import { actionFor, desktopActionFor, fillWindowFromCapture, longRecordedVariables, recordedVariables, shortenRecordedNames, type Captured } from "./capture";
import { WhereDialog } from "./CaptureEditor";
import { AssistantDialog, DependenciesPanel, FlowDiagram } from "./AgentsTogether";
import { Button, Dialog, Field, Icon, IconTile, Menu, SidePanel, Splitter, TabPanel, Tabs, TextInput, usePanelSize, usePersistent, type MenuItem } from "../components";
import { ConditionEditor } from "./ConditionEditor";
import {
  addBranch, blockIds, CLIP_KEY, copySteps, duplicateSteps, foldedSet, innerLists, isBlock, locate, moveManyTo, moveSelection, partList, parseSection,
  pasteSteps, readClip, removeBranch, removeSteps, sectionKey, setArg, setCatchCodes, setElseIfCondition, setFolded,
  setNote, spotFor, START, toggleDisabledMany, topSteps, type Clip, type Part, type Place,
} from "./flowEdit";
import { DebugValues, RunOutput, runningStep, type LiveRun } from "./RunPanel";
import { CommitInput, StepForm } from "./StepForm";
import { TriggersPanel } from "./TriggersPanel";
import { lastValues } from "./variables";
import { VariableDialog, VariablesPanel, VarsContext, type NewVariable, type VarsCtx, type WhereChoice } from "./VariablesPanel";

/** The address of the agent's first Open page step, to suggest when a capture opens a new page. */
const openUrl = (flow: Flow): string | undefined => {
  const find = (list: FlowStep[]): string | undefined => { for (const s of list) {
    if (s.action === "ooa.browser/open" && typeof s.args?.url === "string" && !s.args.url.startsWith("$")) return s.args.url;
    const inner = find([...(s.children ?? []), ...(s.else ?? [])]); if (inner) return inner; } return undefined; };
  return find(flow.steps);
};
import { describeAction, stepRows, summarize, toolkitMark } from "./toolkits";

/** Drag data: a step being moved, or an action from the library being added. */
const STEP_DRAG = "application/x-ooa-step";
const ACTION_DRAG = "application/x-ooa-action";

/**
 * The action library on the left: toolkits with their mark and count, searchable, one open at a time. Clicking an
 * action adds it after the selected step (or inside it, when it is a block); dragging puts it where it is dropped.
 */
export function ActionsPanel({ packages, onAdd }: { packages: PackageManifest[]; onAdd: (action: string) => void }) {
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
  const list = packages.map((p) => ({ p, actions: q ? p.actions.filter((a) => a.name.toLowerCase().includes(q) || p.name.toLowerCase().includes(q)) : p.actions }))
    .filter((x) => x.actions.length).sort((a, b) => a.p.name.localeCompare(b.p.name));
  return (
    <><SidePanel title="Actions" side="left" collapsed={hidden} onToggle={() => setHidden(!hidden)} className="st-actions"
      style={width ? { width } : undefined} panelRef={(el) => { panel.current = el; }}>
      <div className="st-panel-search"><TextInput aria-label="Search actions" placeholder={`Search ${total.toLocaleString()} actions`} value={query} onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter" && q) { e.preventDefault(); const b = best(); if (b) onAdd(b); } }} /></div>
      <p className="st-panel-hint">Click an action to add it after the selected step, or drag it to where it goes. Enter adds the best match.</p>
      <ul className="st-groups">
        {list.map(({ p, actions }) => {
          const isOpen = open === p.id || (!!q && list.length <= 3);
          const m = toolkitMark(p.id);
          return (
            <li key={p.id}>
              <button type="button" className={`st-group${isOpen ? " is-open" : ""}`} aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : p.id)}>
                <IconTile name={m.icon} color={m.color} size={22} /><span className="st-group-name">{p.name}</span><span className="st-count">{actions.length}</span>
              </button>
              {isOpen && <ul className="st-group-actions">{actions.map((a) => (
                <li key={a.id}><button type="button" className="st-action" title={a.description} draggable
                  onDragStart={(e) => { e.dataTransfer.setData(ACTION_DRAG, p.id + "/" + a.id); e.dataTransfer.effectAllowed = "copy"; }}
                  onClick={() => onAdd(p.id + "/" + a.id)}>{a.name}</button></li>
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
export function AgentView({ api, path, flow, packages, selected, onSelect, onEdit, onInsert, problems, checked, canUndo, canRedo, onUndo, onRedo,
  run, breakpoints, onToggleBreakpoint, onStop, onDebug, clipboard, onClipboard, onRunFrom, onNotice, onError, onOpenChild, onOpenPath, onNewConnection, dirty }:
  { api: StudioApi; path: string; flow: Flow; packages: PackageManifest[]; selected: string[]; onSelect: (ids: string[]) => void;
    onEdit: (next: Flow, select?: string[]) => void; onInsert: (action: string, targetId: string | null, where: Place, part?: Part) => void;
    problems: Problem[]; checked: boolean; canUndo: boolean; canRedo: boolean; onUndo: () => void; onRedo: () => void;
    run: LiveRun | null; breakpoints: string[]; onToggleBreakpoint: (id: string) => void; onStop: () => void; onDebug: (c: DebugCommand) => void;
    clipboard: StepsClipboard | null; onClipboard: (c: StepsClipboard) => void;
    onRunFrom: (stepId: string, only: boolean, defaults: boolean) => void; onNotice: (text: string) => void; onError: (e: unknown) => void;
    onOpenChild: (stepId: string) => void; onOpenPath: (path: string) => void; onNewConnection: () => void; dirty: boolean }) {
  const folded = useMemo(() => foldedSet(flow), [flow]);
  const rows = useMemo(() => stepRows(flow.steps, folded), [flow, folded]);
  const [rightTab, setRightTab] = useState("properties");
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
  const [anchor, setAnchor] = useState<string | null>(null);   // where a Shift range starts
  const [cursor, setCursor] = useState<string | null>(null);   // the end of it that Shift+Up/Down moves
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const list = useRef<HTMLOListElement>(null);
  const pasted = useRef(false);
  const [builtins, setBuiltins] = useState<Builtins>({ sys: {}, global: {} });
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [newVar, setNewVar] = useState<NewVariable | null>(null);
  const loadBuiltins = () => { api.builtins().then(setBuiltins, () => {}); };
  useEffect(() => { loadBuiltins(); api.agents().then(setAgents, () => {}); }, [api]);
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
        const object = (r.object ?? r) as Captured;
        const type = String((object.properties ?? []).find((x) => x.name === "ControlType")?.value ?? "");
        const name = String((object.properties ?? []).find((x) => x.name === "Name")?.value ?? "");
        insertSteps(flow, [{ id: "new", action: "ooa.desktop_ui/object_action", args: { object, action: desktopActionFor(object) },
          note: "Captured " + (type || "control") + (name ? ": " + name.slice(0, 30) : "") }]);
        onNotice("Captured " + (type || "a control") + ".");
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
    notice: onNotice, fail: onError, where, openChild: onOpenChild };
  const [view, setView] = usePersistent<"list" | "flow">("agent.view", "list");
  const [assistant, setAssistant] = useState<"build" | "explain" | null>(null);
  const agentName = (id: unknown) => { const a = agents.find((x) => x.id === id); return a ? a.name || a.id : String(id ?? ""); };
  useEffect(() => { if (run) setBottomTab("run"); }, [run?.id]);   // a new run: show its output
  const at = runningStep(run);
  const lineOf = (id: string) => { const r = rows.find((x) => x.kind === "step" && x.step.id === id); return r?.kind === "step" ? `Line ${r.n} ${describeAction(r.step.action, packages).name}` : id; };
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
      const survivor = [after, before].find((id) => id && locate(next.steps, id));
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
      // the computer's clipboard too, in the shape Classic Studio uses, so either Studio pastes it
      navigator.clipboard?.writeText(JSON.stringify({ [CLIP_KEY]: clip })).then(() => keep(true), () => {});
      return true;
    },
    cut: () => { if (act.copy()) onEdit(removeSteps(flow, chosen), []); },
    paste: (clip: Clip | null = clipboard) => {
      if (!clip?.steps.length) return;
      const p = pasteSteps(flow, clip, spotFor(flow, selected, false));
      onEdit(p.flow, p.ids);
    },
    share: () => { const clip = copySteps(flow, chosen); if (clip) api.putSharedClipboard(clip.steps, clip.variables).catch(() => {}); },
    fromShared: async () => {
      try { const c = await api.sharedClipboard(); act.paste({ steps: c.steps ?? [], variables: c.variables ?? [] }); } catch { /* nothing shared */ }
    },
    fold: (ids: string[], fold: boolean) => { if (ids.length) onEdit(setFolded(flow, ids, fold)); },
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
  const onKeys = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement).closest("input, textarea, select, [role=menu]")) return;
    const ctrl = e.ctrlKey || e.metaKey, k = e.key.toLowerCase();
    const i = chosen.length ? ids.indexOf(chosen[chosen.length - 1]) : -1;
    const c = ids.indexOf(cursor && chosen.includes(cursor) ? cursor : chosen[chosen.length - 1] ?? "");
    if (e.key === "Delete") act.remove();
    else if (e.altKey && e.key === "ArrowUp") act.up();
    else if (e.altKey && e.key === "ArrowDown") act.down();
    else if (e.shiftKey && e.key === "ArrowDown" && c >= 0 && c + 1 < ids.length) extendTo(ids[c + 1]);
    else if (e.shiftKey && e.key === "ArrowUp" && c > 0) extendTo(ids[c - 1]);
    else if (e.key === "ArrowDown" && i + 1 < ids.length) selectOne(ids[i + 1]);
    else if (e.key === "ArrowUp" && i > 0) selectOne(ids[i - 1]);
    else if (e.key === "Escape" && selected.length) onSelect([]);
    else if (ctrl && k === "d") act.duplicate();
    else if (ctrl && k === "a") onSelect(ids);
    else if (ctrl && k === "c") act.copy();
    else if (ctrl && k === "x") act.cut();
    else if (ctrl && k === "v") {
      // a paste event follows when the browser gives one (it carries steps copied in Classic Studio); on a step
      // Chrome gives none, so then the Studio's own clipboard is pasted
      pasted.current = false;
      setTimeout(() => { if (!pasted.current) act.paste(); }, 0);
      return;
    }
    else if (ctrl && e.key === "/") act.disable();
    else if (e.key === "F9" && chosen.length) chosen.forEach(onToggleBreakpoint);
    else if ((e.key === "ContextMenu" || (e.shiftKey && e.key === "F10")) && chosen.length) setMenuFor(chosen[chosen.length - 1]);
    else return;
    e.preventDefault();
  };
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
      { key: "cut", label: "Cut", shortcut: "Ctrl+X", onSelect: act.cut },
      { key: "copy", label: "Copy", shortcut: "Ctrl+C", onSelect: () => { act.copy(); } },
    ];
    if (clipboard) items.push({ key: "paste", label: "Paste after", description: clipboard.steps.length + (clipboard.steps.length === 1 ? " step" : " steps") + " copied", shortcut: "Ctrl+V", onSelect: () => act.paste() });
    items.push(
      { key: "share", label: "Copy to shared clipboard", description: "Paste in any agent, in any Studio window", separatorBefore: true, onSelect: act.share },
      { key: "from-shared", label: "Paste from shared clipboard", onSelect: act.fromShared },
      { key: "duplicate", label: "Duplicate", shortcut: "Ctrl+D", separatorBefore: true, onSelect: act.duplicate },
      { key: "disable", label: allOff ? "Switch on" : "Switch off", shortcut: "Ctrl+/", onSelect: act.disable },
      { key: "delete", label: "Delete", shortcut: "Del", onSelect: act.remove },
      { key: "up", label: "Move up", shortcut: "Alt+↑", separatorBefore: true, onSelect: act.up },
      { key: "down", label: "Move down", shortcut: "Alt+↓", onSelect: act.down },
      { key: "note", label: step.note ? "Edit note" : "Add note", separatorBefore: true, onSelect: act.note },
      { key: "breakpoint", label: breakpoints.includes(step.id) ? "Remove breakpoint" : "Breakpoint", shortcut: "F9", onSelect: () => chosen.forEach(onToggleBreakpoint) },
    );
    if (step.action === "core/run_bot" && step.args?.automation && chosen.length === 1)
      items.push({ key: "child", label: "Open the agent it runs", description: agentName(step.args.automation), separatorBefore: true, onSelect: () => onOpenChild(step.id) });
    // run part of the agent: the skipped steps' variables keep the values the last run here had at this step
    if (!running && chosen.length === 1) items.push(
      { key: "from", label: "Run from here", description: "Earlier steps are skipped; their values come from the last run", separatorBefore: true, onSelect: () => onRunFrom(step.id, false, false) },
      { key: "from-defaults", label: "Run from here with default values", onSelect: () => onRunFrom(step.id, false, true) },
      { key: "only", label: "Run this step only", onSelect: () => onRunFrom(step.id, true, false) },
    );
    if (innerLists(step).length) items.push({ key: "fold", label: folded.has(step.id) ? "Unfold" : "Fold", onSelect: () => act.fold([step.id], !folded.has(step.id)) });
    return items;
  };

  return (
    <VarsContext.Provider value={vars}>
    <div className={`st-agent-wrap is-out-${outMin ? "min" : outAt}`}>
      <div className="st-agent">
        <section className="st-steps" aria-label="Agent steps" onKeyDown={onKeys} onPaste={onPaste}>
          <div role="toolbar" aria-label="Edit steps" className="st-edit-bar">
            <Button kind="plain" onClick={onUndo} disabled={!canUndo} title="Undo (Ctrl+Z)">Undo</Button>
            <Button kind="plain" onClick={onRedo} disabled={!canRedo} title="Redo (Ctrl+Y)">Redo</Button>
            <span className="st-bar-gap" />
            <Button kind="plain" onClick={act.cut} disabled={!chosen.length} title="Ctrl+X">Cut</Button>
            <Button kind="plain" onClick={() => act.copy()} disabled={!chosen.length} title="Ctrl+C">Copy</Button>
            <Button kind="plain" onClick={() => act.paste()} disabled={!clipboard} title="Ctrl+V">Paste</Button>
            <span className="st-bar-gap" />
            <Button kind="plain" onClick={act.remove} disabled={!chosen.length} title="Delete">Delete</Button>
            <Button kind="plain" onClick={act.duplicate} disabled={!chosen.length} title="Ctrl+D">Duplicate</Button>
            <Button kind="plain" onClick={act.disable} disabled={!chosen.length} title="Ctrl+/">{allOff ? "Switch on" : "Switch off"}</Button>
            <Menu label="More step actions" align="start" items={[
              ...(chosen.length ? [
                { key: "up", label: "Move up", shortcut: "Alt+↑", onSelect: act.up },
                { key: "down", label: "Move down", shortcut: "Alt+↓", onSelect: act.down },
                { key: "bp", label: "Breakpoint", description: "Stop here when debugging", shortcut: "F9", onSelect: () => chosen.forEach(onToggleBreakpoint) },
              ] : []),
              { key: "all", label: "Select all", shortcut: "Ctrl+A", onSelect: () => onSelect(ids) },
              ...(blocks.length ? [{ key: "fold", label: "Fold all blocks", separatorBefore: true, onSelect: () => act.fold(blocks, true) }] : []),
              ...(folded.size ? [{ key: "unfold", label: "Unfold all", onSelect: () => onEdit(setFolded(flow, [...folded], false)) }] : []),
            ]} trigger={<Button kind="plain" icon="chevronDown">More</Button>} />
            <span className="st-bar-gap" />
            <div className="st-seg st-view-seg" role="radiogroup" aria-label="Show the steps as">
              {([["list", "List"], ["flow", "Flow"]] as const).map(([k, t]) => <button key={k} type="button" role="radio" aria-checked={view === k} className="st-seg-btn" onClick={() => setView(k)}>{t}</button>)}
            </div>
            <Button kind="plain" icon="spark" onClick={() => setAssistant("build")} title="Build steps from a description, or explain this agent">Assistant</Button>
            <Button kind="plain" className="st-record-btn" onClick={record} disabled={!!recording} title="Record actions in a web browser or a Windows application">
              <span className="st-rec-dot" aria-hidden="true" />{recording ? "Recording…" : "Record"}</Button>
            <Menu label="Capture" align="start" items={[
              { key: "web", label: "Capture a web element", description: "Click it in a browser; adds an action for it", onSelect: () => captureStep(false) },
              { key: "desktop", label: "Capture a desktop control", description: "Hover it in a Windows application and press Ctrl+Shift+C", onSelect: () => captureStep(true) },
            ]} trigger={<Button kind="plain" icon="chevronDown" disabled={capturing}>{capturing ? "Capturing…" : "Capture"}</Button>} />
            <span className="st-steps-meta" aria-live="polite">{chosen.length > 1 ? `${chosen.length} selected of ` : ""}{ids.length} steps{problems.length ? ` · ${problems.length} problem${problems.length > 1 ? "s" : ""}` : ""}</span>
          </div>
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
            {rows.map((r) => {
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
                    <span className="st-step-sum">{r.step.action === "core/run_bot" && r.step.args?.automation && !r.step.note
                      ? "runs " + agentName(r.step.args.automation) : summarize(r.step, packages)}</span>
                    {r.folded && <span className="st-pill">{r.hidden} {r.hidden === 1 ? "step" : "steps"} folded</span>}
                    {r.step.disabled && <span className="st-badge">OFF</span>}
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
            })}
            {!ids.length && <li><p className="st-empty-steps">No steps yet. Pick an action in the Actions panel on the left, or drag one here.</p></li>}
            <li>
              <div className={`st-marker-row${drop?.key === null ? " drop-inside" : ""}`}
                onDragOver={(e) => over(e, null, "after")} onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, null, "after")}>
                <span className="st-marker st-end">END</span>
              </div>
            </li>
          </ol>}
        </section>

        {!rightHidden && <Splitter label="Resize the details panel" axis="x" grow={-1} value={rightWidth} min={260} max={680}
          measure={() => rightPanel.current?.getBoundingClientRect().width ?? 340} onChange={setRightWidth} />}
        <SidePanel title="Details" label="Properties, variables and triggers" side="right" collapsed={rightHidden}
          onToggle={() => setRightHidden(!rightHidden)} className="st-right" style={rightWidth ? { width: rightWidth } : undefined}
          panelRef={(el) => { rightPanel.current = el; }}>
          <Tabs label="Right panel" value={rightTab} onChange={setRightTab}
            tabs={[{ value: "properties", label: "Properties" }, { value: "variables", label: "Variables" }, { value: "triggers", label: "Triggers" }, { value: "deps", label: "Dependencies" }]}>
            <TabPanel value="properties">
              {selStep && selInfo ? <>
                <div className="st-prop-head"><IconTile name={selInfo.mark.icon} color={selInfo.mark.color} size={28} />
                  <div><strong>{selInfo.name}</strong><div>Line {selStep.n} · {selInfo.toolkit}</div></div></div>
                <StepForm step={selStep.step} packages={packages}
                  onArg={(name, v) => onEdit(setArg(flow, selStep.step.id, name, v))}
                  onNote={(n) => onEdit(setNote(flow, selStep.step.id, n))} />
                <BlockParts step={selStep.step} onAdd={(kind) => { const b = addBranch(flow, selStep.step.id, kind); if (b.part) onEdit(b.flow, [sectionKey(selStep.step.id, b.part)]); }} />
              </> : selSection && sectionRow?.kind === "section" ? (
                <PartProperties flow={flow} label={sectionRow.label} id={selSection.id} part={selSection.part} onEdit={onEdit} />
              ) : selected.includes(START) ? <p className="st-pad">START is selected: actions you click in the library go first, before line 1. Pasted steps go there too.</p>
                : chosen.length > 1
                ? <p className="st-pad">{chosen.length} steps selected. Cut, Copy, Delete, Move, Duplicate and Switch off act on all of them; select one step to change its settings.</p>
                : <p className="st-muted st-pad">Select a step to change its settings.</p>}
            </TabPanel>
            <TabPanel value="variables"><VariablesPanel flow={flow} builtins={builtins} last={last} onEdit={onEdit} onRefresh={loadBuiltins} /></TabPanel>
            <TabPanel value="triggers">
              <TriggersPanel api={api} path={path} />
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
              {!checked ? <p className="st-muted st-pad">Save to check the agent.</p>
                : problems.length === 0 ? <p className="st-pad">No problems found at the last save.</p>
                : <ul className="st-problem-list">{problems.map((p, i) => {
                    const row = rows.find((r) => r.kind === "step" && r.step.id === p.stepId);
                    return (
                      <li key={i}>
                        <span className="st-check">CHECK</span>
                        <span>{row?.kind === "step" ? `Line ${row.n}: ` : ""}{p.message}{p.suggestedFix ? ` (${p.suggestedFix})` : ""}</span>
                        {row?.kind === "step" && <Button kind="plain" onClick={() => { onSelect([p.stepId!]); setRightTab("properties"); }}>Go to line {row.n}</Button>}
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
function BlockParts({ step, onAdd }: { step: FlowStep; onAdd: (kind: "elseIf" | "else" | "catch" | "finally") => void }) {
  if (step.action !== "core/if" && step.action !== "core/try") return null;
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
    </div>
  </>;
}

/** Settings of a selected block part: an Else if's condition, the errors a Catch takes; and removing the part. */
function PartProperties({ flow, label, id, part, onEdit }: { flow: Flow; label: string; id: string; part: Part; onEdit: (next: Flow, select?: string[]) => void }) {
  const step = locate(flow.steps, id)?.step;
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
        {kind === "catch" && <Field label="Errors it catches" hint="Error codes, separated by commas. Empty catches every error." layout="beside">{(fid) =>
          <CommitInput key={id + part + (step.catch?.[+k]?.codes ?? []).join(",")} id={fid} mono initial={(step.catch?.[+k]?.codes ?? []).join(", ")} placeholder="Any error"
            onCommit={(t) => onEdit(setCatchCodes(flow, id, +k, t.split(",").map((x) => x.trim()).filter(Boolean)))} />}</Field>}
      </div>
      {part !== "children" && removed !== flow && <div className="st-pad">
        <Button kind="secondary" icon="trash" onClick={() => onEdit(removed, [id])}>Remove this part{inside ? ` and its ${inside} step${inside > 1 ? "s" : ""}` : ""}</Button>
      </div>}
    </div>
  );
}
