import { useMemo, useState, type KeyboardEvent } from "react";
import type { Flow, FlowVariable, PackageManifest, Problem } from "../api/client";
import { Button, Field, IconTile, Select, SidePanel, TabPanel, Tabs, TextInput, usePersistent } from "../components";
import { duplicateStep, moveStep, putVariable, removeStep, removeVariable, setArg, setNote, setOut, toggleDisabled } from "./flowEdit";
import { StepForm } from "./StepForm";
import { describeAction, stepRows, summarize, toolkitMark } from "./toolkits";

/**
 * The action library on the left: toolkits with their mark and count, searchable, one open at a time. Clicking an
 * action adds it to the open agent, after the selected step (or inside it, when the selected step is a block).
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
      <p className="st-panel-hint">Click an action to add it after the selected step.</p>
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
                <li key={a.id}><button type="button" className="st-action" title={a.description} onClick={() => onAdd(p.id + "/" + a.id)}>{a.name}</button></li>
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

/**
 * One open agent, editable: numbered steps with the toolkit mark and guide lines for blocks, a toolbar and keyboard
 * shortcuts for the selected step, its settings, the agent's variables, and the problems the last save found.
 */
export function AgentView({ flow, packages, selected, onSelect, onEdit, problems, checked, canUndo, canRedo, onUndo, onRedo }:
  { flow: Flow; packages: PackageManifest[]; selected: string | null; onSelect: (id: string | null) => void;
    onEdit: (next: Flow, select?: string | null) => void; problems: Problem[]; checked: boolean;
    canUndo: boolean; canRedo: boolean; onUndo: () => void; onRedo: () => void }) {
  const rows = useMemo(() => stepRows(flow.steps), [flow]);
  const [rightTab, setRightTab] = useState("properties");
  const [rightHidden, setRightHidden] = usePersistent("panel.details.hidden", false);
  const sel = rows.find((r) => r.kind === "step" && r.step.id === selected);
  const selStep = sel?.kind === "step" ? sel : null;
  const selInfo = selStep ? describeAction(selStep.step.action, packages) : null;
  const ids = rows.flatMap((r) => r.kind === "step" ? [r.step.id] : []);
  const problemSteps = new Set(problems.map((p) => p.stepId).filter(Boolean));

  const act = {
    remove: () => {
      if (!selected) return;
      // select the next step (or the one before) so Delete can be pressed again
      const i = ids.indexOf(selected);
      onEdit(removeStep(flow, selected), ids[i + 1] ?? ids[i - 1] ?? null);
    },
    up: () => { if (selected) onEdit(moveStep(flow, selected, -1)); },
    down: () => { if (selected) onEdit(moveStep(flow, selected, 1)); },
    duplicate: () => { if (selected) { const d = duplicateStep(flow, selected); onEdit(d.flow, d.id); } },
    disable: () => { if (selected) onEdit(toggleDisabled(flow, selected)); },
  };
  const onKeys = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement).closest("input, textarea, select")) return;
    const i = selected ? ids.indexOf(selected) : -1;
    if (e.key === "Delete") act.remove();
    else if (e.altKey && e.key === "ArrowUp") act.up();
    else if (e.altKey && e.key === "ArrowDown") act.down();
    else if (e.key === "ArrowDown" && i + 1 < ids.length) onSelect(ids[i + 1]);
    else if (e.key === "ArrowUp" && i > 0) onSelect(ids[i - 1]);
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "d") act.duplicate();
    else return;
    e.preventDefault();
  };

  return (
    <div className="st-agent-wrap">
      <div className="st-agent">
        <section className="st-steps" aria-label="Agent steps" onKeyDown={onKeys}>
          <div role="toolbar" aria-label="Edit steps" className="st-edit-bar">
            <Button kind="plain" onClick={onUndo} disabled={!canUndo} title="Undo (Ctrl+Z)">Undo</Button>
            <Button kind="plain" onClick={onRedo} disabled={!canRedo} title="Redo (Ctrl+Y)">Redo</Button>
            <span className="st-bar-gap" />
            <Button kind="plain" onClick={act.remove} disabled={!selected} title="Delete">Delete</Button>
            <Button kind="plain" onClick={act.up} disabled={!selected} title="Alt+Up">Move up</Button>
            <Button kind="plain" onClick={act.down} disabled={!selected} title="Alt+Down">Move down</Button>
            <Button kind="plain" onClick={act.duplicate} disabled={!selected} title="Ctrl+D">Duplicate</Button>
            <Button kind="plain" onClick={act.disable} disabled={!selected}>{selStep?.step.disabled ? "Switch on" : "Switch off"}</Button>
            <span className="st-steps-meta">{ids.length} steps{problems.length ? ` · ${problems.length} problem${problems.length > 1 ? "s" : ""}` : ""}</span>
          </div>
          <div className="st-marker st-start">START</div>
          <ol className="st-step-list">
            {rows.map((r, i) => {
              if (r.kind === "section") return <li key={"s" + i} className="st-section" style={{ paddingLeft: 46 + r.depth * 22 }}>{r.label}</li>;
              const info = describeAction(r.step.action, packages);
              const isSel = r.step.id === selected;
              return (
                <li key={r.step.id}>
                  <button type="button" className={`st-step${isSel ? " is-selected" : ""}${r.step.disabled ? " is-disabled" : ""}`} aria-pressed={isSel}
                    onClick={() => onSelect(r.step.id)}>
                    <span className="st-step-n">{r.n}</span>
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
          {!ids.length && <p className="st-empty-steps">No steps yet. Pick an action in the Actions panel on the left to add the first one.</p>}
          <div className="st-marker st-end">END</div>
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
              </> : <p className="st-muted st-pad">Select a step to change its settings.</p>}
            </TabPanel>
            <TabPanel value="variables"><Variables flow={flow} onEdit={onEdit} /></TabPanel>
            <TabPanel value="triggers">
              <p className="st-pad">Triggers start this agent automatically (new email, a file arriving, a schedule). They are set in Classic Studio until P3.</p>
            </TabPanel>
          </Tabs>
        </SidePanel>
      </div>

      <section className="st-problems" aria-label="Problems">
        <div className="st-problems-head"><strong>Problems</strong>{problems.length > 0 && <span className="st-check">{problems.length}</span>}</div>
        {!checked ? <p className="st-muted st-pad">Save to check the agent.</p>
          : problems.length === 0 ? <p className="st-pad">No problems found at the last save.</p>
          : <ul className="st-problem-list">{problems.map((p, i) => {
              const row = rows.find((r) => r.kind === "step" && r.step.id === p.stepId);
              return (
                <li key={i}>
                  <span className="st-check">CHECK</span>
                  <span>{row?.kind === "step" ? `Line ${row.n}: ` : ""}{p.message}{p.suggestedFix ? ` (${p.suggestedFix})` : ""}</span>
                  {row?.kind === "step" && <Button kind="plain" onClick={() => { onSelect(p.stepId!); setRightTab("properties"); }}>Go to line {row.n}</Button>}
                </li>
              );
            })}</ul>}
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
