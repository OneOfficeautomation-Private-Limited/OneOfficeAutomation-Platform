import { useEffect, useMemo, useState, type DragEvent, type KeyboardEvent, type MouseEvent } from "react";
import type { DebugCommand, Flow, FlowStep, FlowVariable, PackageManifest, Problem, StudioApi } from "../api/client";
import { Button, Field, IconTile, Select, SidePanel, TabPanel, Tabs, TextInput, usePersistent } from "../components";
import {
  duplicateSteps, isBlock, moveStep, moveTo, putVariable, removeSteps, removeVariable, setArg, setNote, setOut, toggleDisabledMany, type Place,
} from "./flowEdit";
import { DebugValues, RunOutput, runningStep, type LiveRun } from "./RunPanel";
import { StepForm } from "./StepForm";
import { TriggersPanel } from "./TriggersPanel";
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
  const total = packages.reduce((n, p) => n + p.actions.length, 0);
  const list = packages.map((p) => ({ p, actions: q ? p.actions.filter((a) => a.name.toLowerCase().includes(q) || p.name.toLowerCase().includes(q)) : p.actions }))
    .filter((x) => x.actions.length).sort((a, b) => a.p.name.localeCompare(b.p.name));
  return (
    <SidePanel title="Actions" side="left" collapsed={hidden} onToggle={() => setHidden(!hidden)} className="st-actions">
      <div className="st-panel-search"><TextInput aria-label="Search actions" placeholder={`Search ${total.toLocaleString()} actions`} value={query} onChange={(e) => setQuery(e.target.value)} /></div>
      <p className="st-panel-hint">Click an action to add it after the selected step, or drag it to where it goes.</p>
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
  );
}

const TYPES = ["String", "Number", "Boolean", "DateTime", "List", "Dictionary", "Table", "Record", "File", "Window", "UIElement", "Credential", "Any"];
const DIRECTIONS = [{ value: "", label: "Inside this agent" }, { value: "input", label: "Input" }, { value: "output", label: "Output" }, { value: "both", label: "Input and output" }];

/** Where a drop over a step lands: its top quarter before it, its bottom quarter after it, the middle inside a block. */
function placeFor(e: DragEvent, step: FlowStep): Place {
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
  const y = r.height ? (e.clientY - r.top) / r.height : 1;
  if (isBlock(step.action)) return y < 0.25 ? "before" : y > 0.75 ? "after" : "inside";
  return y < 0.5 ? "before" : "after";
}

/**
 * One open agent, editable: numbered steps with the toolkit mark and guide lines for blocks, a toolbar and keyboard
 * shortcuts for the selected steps, drag and drop, the settings of the selected step, the agent's variables, and the
 * problems the last save found. Ctrl+click adds a step to the selection, Shift+click a range.
 */
export function AgentView({ api, path, flow, packages, selected, onSelect, onEdit, onInsert, problems, checked, canUndo, canRedo, onUndo, onRedo,
  run, breakpoints, onToggleBreakpoint, onStop, onDebug }:
  { api: StudioApi; path: string; flow: Flow; packages: PackageManifest[]; selected: string[]; onSelect: (ids: string[]) => void;
    onEdit: (next: Flow, select?: string[]) => void; onInsert: (action: string, targetId: string | null, where: Place) => void;
    problems: Problem[]; checked: boolean; canUndo: boolean; canRedo: boolean; onUndo: () => void; onRedo: () => void;
    run: LiveRun | null; breakpoints: string[]; onToggleBreakpoint: (id: string) => void; onStop: () => void; onDebug: (c: DebugCommand) => void }) {
  const rows = useMemo(() => stepRows(flow.steps), [flow]);
  const [rightTab, setRightTab] = useState("properties");
  const [rightHidden, setRightHidden] = usePersistent("panel.details.hidden", false);
  const [drop, setDrop] = useState<{ id: string | null; where: Place } | null>(null);
  const [bottomTab, setBottomTab] = useState("problems");
  useEffect(() => { if (run) setBottomTab("run"); }, [run?.id]);   // a new run: show its output
  const at = runningStep(run);
  const lineOf = (id: string) => { const r = rows.find((x) => x.kind === "step" && x.step.id === id); return r?.kind === "step" ? `Line ${r.n} ${describeAction(r.step.action, packages).name}` : id; };
  const ids = rows.flatMap((r) => r.kind === "step" ? [r.step.id] : []);
  const chosen = ids.filter((id) => selected.includes(id));   // in the agent's order
  const one = chosen.length === 1 ? rows.find((r) => r.kind === "step" && r.step.id === chosen[0]) : undefined;
  const selStep = one?.kind === "step" ? one : null;
  const selInfo = selStep ? describeAction(selStep.step.action, packages) : null;
  const problemSteps = new Set(problems.map((p) => p.stepId).filter(Boolean));
  const allOff = chosen.length > 0 && chosen.every((id) => rows.some((r) => r.kind === "step" && r.step.id === id && r.step.disabled));

  const act = {
    remove: () => {
      if (!chosen.length) return;
      // then select the step after the last removed one (or before the first), so Delete can be pressed again
      const after = ids.slice(ids.indexOf(chosen[chosen.length - 1]) + 1).find((id) => !chosen.includes(id));
      const before = ids.slice(0, ids.indexOf(chosen[0])).reverse()[0];
      const next = removeSteps(flow, chosen);
      const survivor = [after, before].find((id) => id && rows.some((r) => r.kind === "step" && r.step.id === id));
      onEdit(next, survivor ? [survivor] : []);
    },
    up: () => { if (chosen.length === 1) onEdit(moveStep(flow, chosen[0], -1)); },
    down: () => { if (chosen.length === 1) onEdit(moveStep(flow, chosen[0], 1)); },
    duplicate: () => { if (chosen.length) { const d = duplicateSteps(flow, chosen); onEdit(d.flow, d.ids); } },
    disable: () => { if (chosen.length) onEdit(toggleDisabledMany(flow, chosen)); },
  };
  const click = (e: MouseEvent, id: string) => {
    if (e.ctrlKey || e.metaKey) onSelect(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
    else if (e.shiftKey && selected.length) {
      const a = ids.indexOf(selected[selected.length - 1]), b = ids.indexOf(id);
      onSelect(ids.slice(Math.min(a, b), Math.max(a, b) + 1));
    } else onSelect([id]);
  };
  const onKeys = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement).closest("input, textarea, select")) return;
    const i = chosen.length ? ids.indexOf(chosen[chosen.length - 1]) : -1;
    if (e.key === "Delete") act.remove();
    else if (e.altKey && e.key === "ArrowUp") act.up();
    else if (e.altKey && e.key === "ArrowDown") act.down();
    else if (e.key === "ArrowDown" && i + 1 < ids.length) onSelect([ids[i + 1]]);
    else if (e.key === "ArrowUp" && i > 0) onSelect([ids[i - 1]]);
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "d") act.duplicate();
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "a") onSelect(ids);
    else if (e.key === "F9" && chosen.length) chosen.forEach(onToggleBreakpoint);
    else return;
    e.preventDefault();
  };
  const dropOn = (e: DragEvent, targetId: string | null, where: Place) => {
    e.preventDefault();
    setDrop(null);
    const action = e.dataTransfer.getData(ACTION_DRAG);
    const step = e.dataTransfer.getData(STEP_DRAG);
    if (action) onInsert(action, targetId, where);
    else if (step && targetId) onEdit(moveTo(flow, step, targetId, where), [step]);
    else if (step && !targetId && flow.steps.length) onEdit(moveTo(flow, step, flow.steps[flow.steps.length - 1].id, "after"), [step]);
  };

  return (
    <div className="st-agent-wrap">
      <div className="st-agent">
        <section className="st-steps" aria-label="Agent steps" onKeyDown={onKeys}>
          <div role="toolbar" aria-label="Edit steps" className="st-edit-bar">
            <Button kind="plain" onClick={onUndo} disabled={!canUndo} title="Undo (Ctrl+Z)">Undo</Button>
            <Button kind="plain" onClick={onRedo} disabled={!canRedo} title="Redo (Ctrl+Y)">Redo</Button>
            <span className="st-bar-gap" />
            <Button kind="plain" onClick={act.remove} disabled={!chosen.length} title="Delete">Delete</Button>
            <Button kind="plain" onClick={act.up} disabled={chosen.length !== 1} title="Alt+Up">Move up</Button>
            <Button kind="plain" onClick={act.down} disabled={chosen.length !== 1} title="Alt+Down">Move down</Button>
            <Button kind="plain" onClick={act.duplicate} disabled={!chosen.length} title="Ctrl+D">Duplicate</Button>
            <Button kind="plain" onClick={act.disable} disabled={!chosen.length}>{allOff ? "Switch on" : "Switch off"}</Button>
            <Button kind="plain" onClick={() => chosen.forEach(onToggleBreakpoint)} disabled={!chosen.length} title="Stop here when debugging (F9)">Breakpoint</Button>
            <span className="st-steps-meta">{chosen.length > 1 ? `${chosen.length} of ` : ""}{ids.length} steps{problems.length ? ` · ${problems.length} problem${problems.length > 1 ? "s" : ""}` : ""}</span>
          </div>
          <div className="st-marker st-start">START</div>
          <ol className="st-step-list">
            {rows.map((r, i) => {
              if (r.kind === "section") return <li key={"s" + i} className="st-section" style={{ paddingLeft: 46 + r.depth * 22 }}>{r.label}</li>;
              const info = describeAction(r.step.action, packages);
              const isSel = selected.includes(r.step.id);
              const hint = (drop?.id === r.step.id ? " drop-" + drop.where : "") + (at === r.step.id ? " is-running" : "");
              const bp = breakpoints.includes(r.step.id);
              return (
                <li key={r.step.id}>
                  <button type="button" className={`st-step${isSel ? " is-selected" : ""}${r.step.disabled ? " is-disabled" : ""}${hint}`} aria-pressed={isSel}
                    draggable onDragStart={(e) => { e.dataTransfer.setData(STEP_DRAG, r.step.id); e.dataTransfer.effectAllowed = "move"; }}
                    onDragOver={(e) => { e.preventDefault(); const where = placeFor(e, r.step); if (drop?.id !== r.step.id || drop.where !== where) setDrop({ id: r.step.id, where }); }}
                    onDragLeave={() => setDrop(null)} onDrop={(e) => dropOn(e, r.step.id, placeFor(e, r.step))}
                    onClick={(e) => click(e, r.step.id)}>
                    <span className="st-step-n">{bp && <span className="st-bp" title="Breakpoint" aria-label="breakpoint" />}{r.n}</span>
                    <span className="st-guides" style={{ width: r.depth * 22 }} />
                    <IconTile name={info.mark.icon} color={info.mark.color} size={24} />
                    <strong>{info.name}</strong>
                    <span className="st-step-sum">{summarize(r.step, packages)}</span>
                    {r.step.disabled && <span className="st-badge">OFF</span>}
                    {problemSteps.has(r.step.id) && <span className="st-check">CHECK</span>}
                  </button>
                </li>
              );
            })}
          </ol>
          {!ids.length && <p className="st-empty-steps">No steps yet. Pick an action in the Actions panel on the left, or drag one here.</p>}
          <div className={`st-marker st-end${drop?.id === null ? " drop-end" : ""}`}
            onDragOver={(e) => { e.preventDefault(); setDrop({ id: null, where: "after" }); }} onDragLeave={() => setDrop(null)}
            onDrop={(e) => dropOn(e, null, "after")}>END</div>
        </section>

        <SidePanel title="Details" label="Properties, variables and triggers" side="right" collapsed={rightHidden}
          onToggle={() => setRightHidden(!rightHidden)} className="st-right">
          <Tabs label="Right panel" value={rightTab} onChange={setRightTab}
            tabs={[{ value: "properties", label: "Properties" }, { value: "variables", label: "Variables" }, { value: "triggers", label: "Triggers" }]}>
            <TabPanel value="properties">
              {selStep && selInfo ? <>
                <div className="st-prop-head"><IconTile name={selInfo.mark.icon} color={selInfo.mark.color} size={28} />
                  <div><strong>{selInfo.name}</strong><div>Line {selStep.n} · {selInfo.toolkit}</div></div></div>
                <StepForm step={selStep.step} packages={packages}
                  onArg={(name, v) => onEdit(setArg(flow, selStep.step.id, name, v))}
                  onOut={(o, v) => onEdit(setOut(flow, selStep.step.id, o, v))}
                  onNote={(n) => onEdit(setNote(flow, selStep.step.id, n))} />
              </> : chosen.length > 1
                ? <p className="st-pad">{chosen.length} steps selected. Delete, Duplicate and Switch off act on all of them; select one step to change its settings.</p>
                : <p className="st-muted st-pad">Select a step to change its settings.</p>}
            </TabPanel>
            <TabPanel value="variables"><Variables flow={flow} onEdit={onEdit} /></TabPanel>
            <TabPanel value="triggers">
              <TriggersPanel api={api} path={path} />
            </TabPanel>
          </Tabs>
        </SidePanel>
      </div>

      <section className="st-bottom" aria-label="Output">
        <Tabs label="Output" value={bottomTab} onChange={setBottomTab}
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
      </section>
    </div>
  );
}

function Variables({ flow, onEdit }: { flow: Flow; onEdit: (next: Flow) => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState("String");
  const [direction, setDirection] = useState("");
  const [error, setError] = useState<string | null>(null);
  const add = () => {
    try {
      const v: FlowVariable = { name: name.trim().replace(/^\$/, ""), type };
      if (direction) v.direction = direction as FlowVariable["direction"];
      onEdit(putVariable(flow, v));
      setName(""); setError(null);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  return (
    <div>
      <ul className="st-vars">
        {(flow.variables ?? []).map((v) => (
          <li key={v.name}><span className="st-var">${v.name}</span><span className="st-type">{v.type}</span>
            <span className="st-dir">{DIRECTIONS.find((d) => d.value === (v.direction ?? ""))?.label}</span>
            <Button kind="plain" aria-label={"Delete $" + v.name} onClick={() => onEdit(removeVariable(flow, v.name))}>Delete</Button></li>
        ))}
        {!flow.variables?.length && <li className="st-muted">No variables yet.</li>}
      </ul>
      <form className="st-var-form" onSubmit={(e) => { e.preventDefault(); add(); }}>
        <h3 className="st-prop-section">New variable</h3>
        <Field label="Name" layout="beside">{(id) => <TextInput id={id} mono value={name} placeholder="$total" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Type" layout="beside">{(id) => <Select id={id} value={type} onChange={(e) => setType(e.target.value)} options={TYPES.map((t) => ({ value: t, label: t }))} />}</Field>
        <Field label="Used as" layout="beside">{(id) => <Select id={id} value={direction} onChange={(e) => setDirection(e.target.value)} options={DIRECTIONS} />}</Field>
        {error && <p role="alert" className="st-error">{error}</p>}
        <Button kind="secondary" type="submit">Add variable</Button>
      </form>
    </div>
  );
}
