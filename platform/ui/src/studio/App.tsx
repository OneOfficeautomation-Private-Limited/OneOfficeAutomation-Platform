import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiFailure, StudioApi, type Blueprint, type DebugCommand, type Flow, type NewItemKind, type PackageManifest, type Problem, type StudioInfo, type WorkspaceNode,
} from "../api/client";
import { Button, Dialog, Field, Icon, TextInput } from "../components";
import { Automation } from "./Automation";
import { CommandMenu, type StudioView } from "./CommandMenu";
import { ActionsPanel, AgentView } from "./Editor";
import { addStep, insertAt, newStep, parseLoose, type Place } from "./flowEdit";
import { isFinished, type LiveRun } from "./RunPanel";
import { allInstalled } from "./toolkits";

/**
 * One open agent: the flow being edited, the last saved copy (to know about unsaved changes), undo and redo
 * history, the selected step, and what the last save's check found.
 */
type OpenTab = {
  path: string; name: string; flow: Flow; saved: string; past: Flow[]; future: Flow[]; selected: string[]; problems: Problem[]; checked: boolean;
  run: LiveRun | null; breakpoints: string[];
};
const MAX_TABS = 10;
const MAX_UNDO = 100;
const isDirty = (t: OpenTab) => JSON.stringify(t.flow) !== t.saved;

/** The new Studio (docs/architecture/12-ui-redesign.md): Command Center menu, Automation page, agents in tabs. */
export function App({ api: given }: { api?: StudioApi }) {
  // one client for the life of the page: a new one on every render would reload the data in a loop
  const [api] = useState(() => given ?? new StudioApi());
  const [info, setInfo] = useState<StudioInfo | null>(null);
  const [tree, setTree] = useState<WorkspaceNode | null>(null);
  const [packages, setPackages] = useState<PackageManifest[]>([]);
  const [blueprints, setBlueprints] = useState<Blueprint[]>([]);
  const [view, setView] = useState<StudioView>("automation");
  const [tabs, setTabs] = useState<OpenTab[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [newFolderIn, setNewFolderIn] = useState<string | null>(null);
  const [closing, setClosing] = useState<string | null>(null);   // a tab with unsaved changes being closed
  const [search, setSearch] = useState("");
  const [asking, setAsking] = useState<{ path: string; debug: boolean } | null>(null);   // the run dialog, for an agent with inputs

  const fail = (e: unknown) => setError(e instanceof ApiFailure ? e.error.message : String(e));
  const reloadTree = useCallback(() => api.tree().then(setTree).catch(fail), [api]);
  useEffect(() => {
    api.info().then(setInfo).catch(fail);
    api.packages().then(setPackages).catch(fail);
    api.blueprints().then((b) => setBlueprints(b ?? [])).catch(() => setBlueprints([]));   // without them Create offers a blank agent only
    reloadTree();
  }, [api, reloadTree]);

  const current = tabs.find((t) => t.path === active) ?? null;
  const tabsRef = useRef(tabs);
  tabsRef.current = tabs;
  const patch = (path: string, change: (t: OpenTab) => OpenTab) => setTabs((all) => all.map((t) => t.path === path ? change(t) : t));

  // ---- editing: every change keeps the previous flow for Undo ----
  const edit = (next: Flow, select?: string[]) => {
    if (!current || next === current.flow) return;   // nothing changed (a drop on itself, a move past the end)
    patch(current.path, (t) => ({ ...t, flow: next, past: [...t.past, t.flow].slice(-MAX_UNDO), future: [],
      selected: select ?? t.selected }));
  };
  const undo = () => current && current.past.length &&
    patch(current.path, (t) => ({ ...t, flow: t.past[t.past.length - 1], past: t.past.slice(0, -1), future: [t.flow, ...t.future] }));
  const redo = () => current && current.future.length &&
    patch(current.path, (t) => ({ ...t, flow: t.future[0], future: t.future.slice(1), past: [...t.past, t.flow] }));
  const add = (action: string) => {
    if (!current) return;
    const step = newStep(action, current.flow, packages);
    edit(addStep(current.flow, current.selected[current.selected.length - 1] ?? null, step), [step.id]);
  };
  // an action dragged from the library onto a place in the steps
  const insert = (action: string, targetId: string | null, where: Place) => {
    if (!current) return;
    const step = newStep(action, current.flow, packages);
    edit(insertAt(current.flow, step, targetId, where), [step.id]);
  };
  const save = async (path: string) => {
    // a field still being typed in (Ctrl+S without leaving it) commits first, then the latest flow is saved
    const focused = document.activeElement as HTMLElement | null;
    if (focused?.closest?.("input, textarea, select")) { focused.blur(); await new Promise((r) => setTimeout(r, 0)); }
    const tab = tabsRef.current.find((t) => t.path === path);
    if (!tab) return false;
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

  const open = async (n: WorkspaceNode) => {
    if (n.type !== "bot") return;
    if (!tabs.some((t) => t.path === n.path)) {
      if (tabs.length >= MAX_TABS) { setError(`Up to ${MAX_TABS} items can be open. Close one first.`); return; }
      try {
        const flow = await api.bot(n.path);
        setTabs((t) => [...t, { path: n.path, name: flow.name || n.name, flow, saved: JSON.stringify(flow), past: [], future: [],
          selected: flow.steps[0] ? [flow.steps[0].id] : [], problems: [], checked: false, run: null, breakpoints: [] }]);
      } catch (e) { fail(e); return; }
    }
    setActive(n.path);
  };
  const forceClose = (path: string) => {
    const rest = tabs.filter((t) => t.path !== path);
    setTabs(rest);
    if (active === path) setActive(rest.length ? rest[rest.length - 1].path : null);
  };
  const close = (path: string) => {
    const tab = tabs.find((t) => t.path === path);
    if (tab && isDirty(tab)) setClosing(path); else forceClose(path);
  };
  const created = async (kind: NewItemKind, parent: string, name: string, from: Blueprint | null) => {
    const path = await api.create(kind, parent, name);
    if (from) {
      // the new agent keeps its own id and name; the blueprint gives its description, variables and steps
      const flow = await api.bot(path);
      await api.saveBot(path, { ...flow, description: from.description, variables: structuredClone(from.variables), steps: structuredClone(from.steps) });
    }
    await reloadTree();
    if (kind === "TaskBot" || kind === "ApiTask") await open({ name, path, type: "bot" });
  };

  // ---- running and debugging the open agent, on this computer ----
  const inputsOf = (f: Flow) => (f.variables ?? []).filter((v) => v.direction === "input" || v.direction === "both");
  const startRun = (debug: boolean) => {
    if (!current) return;
    if (inputsOf(current.flow).length) setAsking({ path: current.path, debug }); else go(current.path, {}, debug);
  };
  const go = async (path: string, inputs: Record<string, unknown>, debug: boolean) => {
    const tab = tabsRef.current.find((t) => t.path === path);
    if (!tab) return;
    try {
      const id = await api.startRun(tab.flow, inputs, debug ? { debug: true, breakpoints: tab.breakpoints, stopAtStart: !tab.breakpoints.length } : {});
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
        if (!tab || (tab.run && tab.run.id !== id)) return;   // the agent was closed, or a newer run replaced this one
        await new Promise((r) => setTimeout(r, st.debug ? 600 : 350));
      } catch (e) { fail(e); return; }
    }
  };
  const stopRun = () => { if (current?.run) api.runControl(current.run.id, "stop").catch(fail); };
  const debugCommand = (c: DebugCommand) => { if (current?.run) api.debug(current.run.id, c).catch(fail); };
  const toggleBreakpoint = (id: string) => {
    if (!current) return;
    const next = current.breakpoints.includes(id) ? current.breakpoints.filter((x) => x !== id) : [...current.breakpoints, id];
    patch(current.path, (t) => ({ ...t, breakpoints: next }));
    if (current.run?.debug && !isFinished(current.run)) api.setBreakpoints(current.run.id, next).catch(fail);   // also for the run in progress
  };
  const running = !!current?.run && !isFinished(current.run);

  const dirty = current ? isDirty(current) : false;
  const closingTab = tabs.find((t) => t.path === closing) ?? null;

  return (
    <div className="st-app">
      <header className="st-top">
        <div className="st-brand"><span className="st-logo" />One Agent Studio</div>
        <nav aria-label="Breadcrumb" className="st-crumbs">
          {current ? current.path.split("/").slice(0, -1).map((p, i) => <span key={i}>{p}<span aria-hidden="true"> › </span></span>) : null}
          <span className="st-crumb-current">{current ? current.name : "Automation"}</span>
          {current && dirty && <span className="st-unsaved">Unsaved</span>}
          <span className="st-preview-tag">New Studio preview</span>
        </nav>
        <label className="st-search"><Icon name="search" size={14} /><input aria-label="Search items" placeholder="Search items in the workspace" value={search}
          onChange={(e) => { setSearch(e.target.value); setActive(null); }} /></label>
        <div className="st-top-actions">
          {/* only what works today, never faded buttons: Run and Debug join here in P3 */}
          {current
            ? <>
                <Button kind="primary" icon="save" onClick={() => save(current.path)} title="Save (Ctrl+S)">Save</Button>
                {running
                  ? <Button kind="light" onClick={stopRun}>Stop run</Button>
                  : <>
                      <Button kind="run" icon="play" onClick={() => startRun(false)} title="Run the agent as it is in the editor, on this computer">Run</Button>
                      <Button kind="debug" icon="bug" onClick={() => startRun(true)} title="Run and stop at breakpoints (or at the first step)">Debug</Button>
                    </>}
                <Button kind="light" icon="close" onClick={() => close(current.path)}>Close</Button>
              </>
            : <a className="st-classic" href="../">Classic Studio</a>}
        </div>
      </header>

      {error && <div className="st-alert" role="alert"><span>{error}</span><Button kind="secondary" onClick={() => setError(null)}>Dismiss</Button></div>}
      {notice && !error && <div className="st-notice" role="status"><span>{notice}</span><Button kind="secondary" onClick={() => setNotice(null)}>OK</Button></div>}

      <div className="st-body">
        <CommandMenu view={current ? null : view} editing={!!current} controlRoom={info?.controlRoom ?? null} onView={(v) => { setView(v); setActive(null); }} />
        {current && <ActionsPanel packages={packages} onAdd={add} />}
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
          {current
            ? <AgentView key={current.path} flow={current.flow} packages={packages} selected={current.selected}
                onSelect={(ids) => patch(current.path, (t) => ({ ...t, selected: ids }))} onEdit={edit} onInsert={insert}
                problems={current.problems} checked={current.checked} api={api} path={current.path}
                run={current.run} breakpoints={current.breakpoints} onToggleBreakpoint={toggleBreakpoint} onStop={stopRun} onDebug={debugCommand}
                canUndo={current.past.length > 0} canRedo={current.future.length > 0} onUndo={undo} onRedo={redo} />
            : tree
              ? <Automation view={view} tree={tree} search={search} blueprints={blueprints.filter((b) => allInstalled(b.steps, packages))} onOpen={open} onCreated={created} onNewFolder={(p) => setNewFolderIn(p)} />
              : <p className="st-pad">Loading the workspace…</p>}
        </main>
      </div>

      <NewFolderDialog parent={newFolderIn} onClose={() => setNewFolderIn(null)}
        onCreate={async (name) => { await api.createFolder(newFolderIn ?? "", name); await reloadTree(); setNewFolderIn(null); }} />

      {asking && (() => {
        const tab = tabs.find((t) => t.path === asking.path);
        return tab ? <RunDialog name={tab.name} debug={asking.debug} inputs={inputsOf(tab.flow)} onClose={() => setAsking(null)}
          onRun={(values) => { setAsking(null); go(tab.path, values, asking.debug); }} /> : null;
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
