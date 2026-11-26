import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { AgentSummary, WorkspaceNode } from "../../api/client";
import { AgentInputs, Button, Field, PrincipalPicker, Select, SidePanel, TextInput, usePersistent } from "../../components";
import { WorkflowDiagram, type Slot, type StepState } from "./WorkflowDiagram";
import { DryRun } from "./DryRun";
import {
  branchesOf, flatten, insertAfter, locate, mapLists, move, newStep, numbers, problems, removeStep, replaceStep, SHORTCUTS,
  STEP_TYPES, stagesOf, str, typeLabel, whoOf, withNewIds, type Branch, type Obj, type Step,
} from "./flow";

/**
 * OneAgentStudio's workflow editor (review round, 26 Nov 2026, design A on the canvas; it replaces the list of stages):
 * the step list on the left (searchable; drag a step onto the diagram, or double-click it to add it after the selected
 * one), the diagram in the middle, and the selected step's settings on the right. Tabs: Flow, Start and data, Who does
 * what, Problems. Copy, paste, delete, move, find, zoom, fit and a mini map; Check lists the problems; Test run... is a
 * dry run where nothing runs. It edits the workflow file (Name.process.json) and hands every change up as a new copy
 * (one undo step), as Studio's other editors do. It is the orchestration module's part "workflow-editor".
 */
export type WorkflowEditorProps = {
  data: Obj; onChange: (next: Obj) => void; tree: WorkspaceNode | null;
  agents?: () => Promise<AgentSummary[]>;
  /** An agent file's text (to find the agents that read documents or use AI), when Studio can read it. */
  readItem?: (path: string) => Promise<string>;
  /** A formula's value with the given data (Studio's formula preview), for the dry run's decisions. */
  evaluate?: (expr: string, vars: Record<string, unknown>) => Promise<{ value?: unknown; error?: { message: string }; missing?: string[] }>;
};
type Tab = "flow" | "start" | "who" | "problems";

const everything = (n: WorkspaceNode): WorkspaceNode[] => (n.children ?? []).flatMap((c) => [c, ...everything(c)]);
const DOC_ACTIONS = /"action"\s*:\s*"ooa\.(idp|documents)\//;
const AI_ACTIONS = /"action"\s*:\s*"ooa\.(genai|ai_agent|ai_skills|azure_ai|aws_ai|google_ai)\/|"skill"\s*:/;

/** A text field that hands its value up when it loses focus or on Enter (one undo step per change). */
function Text({ label, value, onCommit, placeholder, multiline, mono, hint, type }: { label: string; value: string; onCommit: (v: string) => void; placeholder?: string; multiline?: boolean; mono?: boolean; hint?: string; type?: string }) {
  return <Field label={label} hint={hint}>{(id) => multiline
    ? <textarea key={value} id={id} className={"ui-input ui-textarea" + (mono ? " ui-mono" : "")} rows={3} defaultValue={value} placeholder={placeholder}
        onBlur={(e) => { if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); }} />
    : <TextInput key={value} id={id} type={type} mono={mono} defaultValue={value} placeholder={placeholder}
        onBlur={(e) => { if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); }}
        onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); } }} />}</Field>;
}

/** Sets a key, or removes it when the value is empty. */
function withKey<T extends Obj>(o: T, k: string, v: unknown): T {
  const out = { ...o } as Obj;
  if (v === undefined || v === "" || v === false || v === null || (Array.isArray(v) && !v.length)) delete out[k]; else out[k] = v;
  return out as T;
}

/** Who a task goes to: the people and roles named (whoever opens it first takes it), the starter, or one random member. */
function Assignee({ label, value, onCommit, emails }: { label: string; value: string; onCommit: (v: string) => void; emails?: boolean }) {
  const mode = value === "starter" ? "starter" : value.startsWith("random:team:") ? "team" : value.startsWith("random:role:") ? "role" : "named";
  const name = mode === "team" || mode === "role" ? value.slice(12) : "";
  return <div className="wfe-assignee" role="group" aria-label={label}>
    <Field label={label}>{(id) => <Select id={id} value={mode} onChange={(e) => {
      const m = e.target.value;
      onCommit(m === "starter" ? "starter" : m === "team" ? "random:team:" : m === "role" ? "random:role:" : "");
    }} options={[{ value: "named", label: "The people and roles named (first to open takes it)" }, { value: "starter", label: "The person who started the workflow" },
      { value: "team", label: "One random member of a team" }, { value: "role", label: "One random member of a role" }]} />}</Field>
    {mode === "named" && <Field label="Named">{(id) => <PrincipalPicker id={id} label={label} kinds={["user", "role"]} emails={emails} value={value} onChange={onCommit} />}</Field>}
    {(mode === "team" || mode === "role") && <Text label={mode === "team" ? "Team" : "Role"} value={name} placeholder={mode === "team" ? "Finance" : "Approver"} onCommit={(v) => onCommit("random:" + mode + ":" + v.trim())} />}
  </div>;
}

export function WorkflowEditor({ data, onChange, tree, agents: loadAgents, readItem, evaluate }: WorkflowEditorProps) {
  const stages = stagesOf(data);
  const [tab, setTab] = useState<Tab>("flow");
  const [sel, setSel] = useState<string | null>(null);
  const [find, setFind] = useState("");
  const [palette, setPalette] = useState("");
  const [palHidden, setPalHidden] = usePersistent("panel.workflow.steps.hidden", false);
  const [propsHidden, setPropsHidden] = usePersistent("panel.workflow.settings.hidden", false);
  const [zoom, setZoom] = useState(1);
  const [mini, setMini] = useState(false);
  const [clip, setClip] = useState<Step | null>(null);
  const [agents, setAgents] = useState<AgentSummary[] | null>(null);
  const [kinds, setKinds] = useState<Record<string, string>>({});   // agent path -> its text, read when a shortcut step needs it
  const [dry, setDry] = useState<null | Record<string, StepState>>(null);
  const [dryOpen, setDryOpen] = useState(false);
  const canvas = useRef<HTMLDivElement>(null);
  useEffect(() => { if (loadAgents) loadAgents().then(setAgents, () => setAgents(null)); }, [loadAgents]);
  const items = tree ? everything(tree) : [];
  const pick = (type: WorkspaceNode["type"], none: string) => [{ value: "", label: none }, ...items.filter((x) => x.type === type).map((x) => ({ value: x.path, label: x.path }))];
  const all = flatten(stages);
  const nums = numbers(stages);
  const chosen = sel ? all.find((s) => s.id === sel) ?? null : null;
  const found = problems(data, agents);
  const problemSteps = useMemo(() => new Set(found.map((p) => p.step).filter(Boolean) as string[]), [found]);
  const setStages = (next: Step[], select?: string | null) => { onChange({ ...data, stages: next }); if (select !== undefined) setSel(select); };
  const update = (s: Step) => setStages(replaceStep(stages, s.id, s));

  /** Adds a step of a type (or a shortcut) at a place: after a step, at the start of a list, or at the end of a branch. */
  const add = (what: string, slot: Slot) => {
    const sc = SHORTCUTS[what];
    const step = sc ? { ...newStep(stages, "bot"), name: sc.label + " " + (all.filter((s) => s.agent_kind === sc.filter).length + 1), agent_kind: sc.filter } : newStep(stages, what);
    setStages(put(stages, slot, step), step.id);
  };
  const put = (list: Step[], slot: Slot, step: Step): Step[] => {
    if (slot.after) return insertAfter(list, slot.after, step);
    if (slot.branch) return mapLists(list, (l) => l.map((s) => s.id === slot.branch!.par ? { ...s, branches: branchesOf(s).map((b, i) => i === slot.branch!.index ? { ...b, stages: [step, ...(b.stages ?? [])] } : b) } : s));
    return [step, ...list];
  };
  const addAfterSelected = (what: string) => add(what, sel ? { after: sel } : { after: all.length ? stages[stages.length - 1].id : null });
  const remove = () => { if (sel) setStages(removeStep(stages, sel), null); };
  const copy = () => { if (chosen) setClip(chosen); };
  const paste = () => { if (clip) { const c = withNewIds(stages, clip); setStages(sel ? insertAfter(stages, sel, c) : [...stages, c], c.id); } };
  const fit = () => {
    const el = canvas.current, inner = el?.querySelector(".wfd") as HTMLElement | null;
    if (!el || !inner) return;
    const w = inner.scrollWidth / zoom;
    setZoom(Math.max(0.4, Math.min(1.4, Math.floor(((el.clientWidth - 24) / w) * 10) / 10)));
  };
  const onKey = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement).closest("input, textarea, select")) return;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "c") { copy(); e.preventDefault(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "v") { paste(); e.preventDefault(); }
    else if (e.key === "Delete" && sel) { remove(); e.preventDefault(); }
  };
  // the agents a shortcut step may choose: those whose file uses a document or an AI command (read once each)
  useEffect(() => {
    if (!chosen || !chosen.agent_kind || !readItem) return;
    const missing = items.filter((x) => x.type === "bot" && kinds[x.path] === undefined);
    if (!missing.length) return;
    Promise.all(missing.slice(0, 200).map((x) => readItem(x.path).then((t) => [x.path, t] as const, () => [x.path, ""] as const)))
      .then((pairs) => setKinds((k) => ({ ...k, ...Object.fromEntries(pairs) })));
  }, [chosen?.id, chosen?.agent_kind]);   // eslint-disable-line react-hooks/exhaustive-deps

  const groups = ["Work", "Paths", "Waits", "Finish"] as const;
  const q = palette.trim().toLowerCase();
  const paletteItem = (key: string, label: string, color: string, hint?: string, shape?: string) =>
    (!q || label.toLowerCase().includes(q)) && <li key={key}>
      <button type="button" className="wfe-pal-item" draggable aria-label={label} title={(hint ? hint + ". " : "") + "Drag onto the diagram, or double-click to add after the selected step."}
        onDragStart={(e) => e.dataTransfer.setData("text/plain", key)} onDoubleClick={() => addAfterSelected(key)}
        onKeyDown={(e) => { if (e.key === "Enter") addAfterSelected(key); }}>
        <i className={"wfe-dot" + (shape ? " is-" + shape : "")} style={{ background: color }} aria-hidden="true" />{label}</button></li>;

  const whoRows = useMemo(() => {
    const rows = new Map<string, { who: string; kind: string; steps: string[] }>();
    for (const s of all) for (const [field, text] of [["assignee", s.assignee], ["to", s.to], ["escalate", s.escalate && (s.escalate as Obj).to]] as const) {
      if (!text) continue;
      for (const w of whoOf(str(text))) {
        const k = w.kind + ":" + w.who, r = rows.get(k) ?? { ...w, steps: [] };
        r.steps.push(nums.get(s.id) + " " + (str(s.name) || s.id) + (field === "escalate" ? " (when late)" : field === "to" ? " (told)" : ""));
        rows.set(k, r);
      }
    }
    return [...rows.values()];
  }, [data]);   // eslint-disable-line react-hooks/exhaustive-deps

  const props = chosen ? <StepSettings key={chosen.id} s={chosen} stages={stages} pick={pick} agents={agents} kinds={kinds} onChange={update} /> : null;

  return (
    <div className="wfe">
      <div className="wfe-tabs" role="tablist" aria-label="Workflow">
        {([["flow", "Flow"], ["start", "Start and data"], ["who", "Who does what"], ["problems", "Problems"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="st-page-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}{k === "problems" && found.length > 0 && <span className="wfe-bad">{found.length}</span>}</button>)}
        <span className="st-bar-gap" />
        <Button kind="secondary" onClick={() => setTab("problems")}>Check</Button>
        <Button kind="secondary" onClick={() => setDryOpen(true)} disabled={!stages.length}>Test run…</Button>
      </div>
      {tab === "flow" && <div className="wfe-body">
        {/* fix list 27 Nov, item 4: folds to a strip and back (remembered) */}
        <SidePanel title="Steps to add" side="left" collapsed={palHidden} onToggle={() => setPalHidden(!palHidden)} className="wfe-palette">
          <TextInput type="search" aria-label="Search steps" placeholder="Search steps" value={palette} onChange={(e) => setPalette(e.target.value)} />
          {groups.map((g) => <section key={g} aria-label={g}><h3 className="wfe-pal-h">{g}</h3><ul className="wfe-pal">
            {Object.entries(STEP_TYPES).filter(([, t]) => t.group === g).map(([k, t]) => paletteItem(k, t.label, t.color, undefined, t.shape))}
            {g === "Work" && Object.entries(SHORTCUTS).map(([k, sc]) => paletteItem(k, sc.label, sc.color, sc.hint))}
          </ul></section>)}
          <p className="st-muted wfe-pal-tip">Drag a step onto a + in the diagram, or double-click it to add it after the selected step.</p>
        </SidePanel>
        <section className="wfe-canvas" aria-label="Diagram" tabIndex={0} onKeyDown={onKey} ref={canvas}>
          <div className="wfe-tools" role="toolbar" aria-label="Diagram tools">
            <button type="button" className="st-tool" onClick={copy} disabled={!chosen}>Copy</button>
            <button type="button" className="st-tool" onClick={paste} disabled={!clip}>Paste</button>
            <button type="button" className="st-tool" onClick={remove} disabled={!chosen}>Delete</button>
            <button type="button" className="st-tool" onClick={() => sel && setStages(move(stages, sel, -1))} disabled={!chosen || locate(stages, sel!)?.index === 0}>Move up</button>
            <button type="button" className="st-tool" onClick={() => { const l = sel ? locate(stages, sel) : null; if (l) setStages(move(stages, sel!, 1)); }}
              disabled={!chosen || (() => { const l = locate(stages, sel!); return !l || l.index === l.list.length - 1; })()}>Move down</button>
            <TextInput type="search" aria-label="Find a step" placeholder="Find a step" value={find} onChange={(e) => setFind(e.target.value)} />
            <span className="st-bar-gap" />
            <button type="button" className="st-tool" aria-label="Zoom out" onClick={() => setZoom((z) => Math.max(0.4, Math.round((z - 0.1) * 10) / 10))}>−</button>
            <span className="wfe-zoom" aria-live="polite">{Math.round(zoom * 100)}%</span>
            <button type="button" className="st-tool" aria-label="Zoom in" onClick={() => setZoom((z) => Math.min(1.6, Math.round((z + 0.1) * 10) / 10))}>+</button>
            <button type="button" className="st-tool" onClick={fit}>Fit</button>
            <button type="button" className="st-tool" aria-pressed={mini} onClick={() => setMini(!mini)}>Mini map</button>
          </div>
          <div className="wfe-scroll">
            <div style={{ transform: "scale(" + zoom + ")", transformOrigin: "top center" }}>
              <WorkflowDiagram stages={stages} selected={sel} onSelect={(id) => setSel(id === sel ? null : id)} editable find={find} problemSteps={problemSteps}
                states={dry ?? undefined}
                onSlot={(slot) => add(palette && Object.keys({ ...STEP_TYPES, ...SHORTCUTS }).find((k) => (STEP_TYPES[k]?.label ?? SHORTCUTS[k]?.label ?? "").toLowerCase().includes(palette.trim().toLowerCase())) || "bot", slot)}
                onDropAt={(slot, what) => (STEP_TYPES[what] || SHORTCUTS[what]) && add(what, slot)} />
            </div>
          </div>
          {mini && <div className="wfe-mini" aria-label="Mini map"><div style={{ transform: "scale(0.22)", transformOrigin: "top left" }}>
            <WorkflowDiagram stages={stages} selected={sel} onSelect={(id) => { setSel(id); document.querySelector(".wfe-scroll [aria-pressed='true']")?.scrollIntoView({ block: "center" }); }} label="Mini map of the workflow" />
          </div></div>}
        </section>
        <SidePanel title="Settings" side="right" collapsed={propsHidden} onToggle={() => setPropsHidden(!propsHidden)} className="wfe-props st-right">
          {dryOpen ? <DryRun data={data} evaluate={evaluate} onStates={setDry} onClose={() => { setDryOpen(false); setDry(null); }} />
            : props ?? <div className="wfe-none">
              <strong>{str(data.name) || "Workflow"}</strong>
              <p className="st-muted">Select a step to change its settings. {stages.length ? stages.length + " step(s) at the top level, " + all.length + " in all." : "Add the first step from the list on the left."}</p>
            </div>}
        </SidePanel>
      </div>}
      {tab === "start" && <section className="wfe-page st-card" aria-label="Start and data">
        <Text label="Name" value={str(data.name)} onCommit={(v) => onChange(withKey(data, "name", v))} />
        <Text label="Description" multiline value={str(data.description)} onCommit={(v) => onChange(withKey(data, "description", v))} />
        <fieldset className="wfe-radio"><legend>Start with</legend>
          <label className="st-check-line"><input type="radio" name="wfe-start" checked={!data.start_form && data.start_with !== "form"} onChange={() => onChange(withKey(withKey(data, "start_form", ""), "start_with", ""))} /> Inputs given by whoever starts it</label>
          <label className="st-check-line"><input type="radio" name="wfe-start" checked={!!data.start_form || data.start_with === "form"} onChange={() => onChange({ ...data, start_with: "form" })} /> A form they fill in</label>
        </fieldset>
        {(!!data.start_form || data.start_with === "form")
          ? <Field label="Start form">{(id) => <Select id={id} value={str(data.start_form)} onChange={(e) => onChange(withKey(data, "start_form", e.target.value))} options={pick("form", "Choose a form")} />}</Field>
          : <Text label="Inputs (names, separated by commas)" mono value={Array.isArray(data.inputs) ? (data.inputs as string[]).join(", ") : ""} placeholder="number, amount, supplier"
              onCommit={(v) => onChange(withKey(data, "inputs", v.split(",").map((x) => x.trim()).filter(Boolean)))} hint="Steps use them as $name$." />}
        <Text label="Run title" value={str(data.title)} placeholder="Invoice $number$" hint="Names each run in Run > Workflow runs when no reference is given." onCommit={(v) => onChange(withKey(data, "title", v))} />
        <Text label="Outputs (names, separated by commas)" mono value={Array.isArray(data.outputs) ? (data.outputs as string[]).join(", ") : ""} placeholder="posted, document_id"
          hint="The values that matter when the workflow ends, shown first in a run's Data." onCommit={(v) => onChange(withKey(data, "outputs", v.split(",").map((x) => x.trim()).filter(Boolean)))} />
      </section>}
      {tab === "who" && <section className="wfe-page" aria-label="Who does what">
        {whoRows.length ? <table className="st-table" aria-label="Who does what"><thead><tr><th scope="col">Who</th><th scope="col">Is</th><th scope="col">Steps</th></tr></thead>
          <tbody>{whoRows.map((r) => <tr key={r.kind + r.who}><td>{r.who}</td><td>{r.kind}</td><td>{r.steps.join("; ")}</td></tr>)}</tbody></table>
          : <p className="st-muted">No step is given to anyone yet. Human tasks, approvals and notifications say who they go to.</p>}
      </section>}
      {tab === "problems" && <section className="wfe-page" aria-label="Problems">
        {found.length ? <ul className="wfe-problems">{found.map((p, i) => <li key={i}>
          {p.step ? <button type="button" className="cc-link" onClick={() => { setSel(p.step!); setTab("flow"); }}>{nums.get(p.step) ?? ""} {str(all.find((s) => s.id === p.step)?.name) || p.step}</button> : <strong>Workflow</strong>}: {p.message}</li>)}</ul>
          : <p className="wfe-ok" role="status">No problems found. The same checks run when the workflow is checked in.</p>}
      </section>}
    </div>
  );
}

/** The selected step's settings, by its type. */
function StepSettings({ s, stages, pick, agents, kinds, onChange }: { s: Step; stages: Step[]; pick: (t: WorkspaceNode["type"], none: string) => { value: string; label: string }[];
  agents: AgentSummary[] | null; kinds: Record<string, string>; onChange: (s: Step) => void }) {
  const put = (k: string, v: unknown) => onChange(withKey(s, k, v));
  const loc = locate(stages, s.id);
  const here = loc ? loc.list : stages;
  const targets = [{ value: "", label: "The next step" }, { value: "end", label: "End the workflow" }, ...here.filter((x) => x.id !== s.id).map((x) => ({ value: x.id, label: "Go to " + (str(x.name) || x.id) }))];
  const sel = (label: string, k: string, options: { value: string; label: string }[], value = str(s[k])) =>
    <Field label={label}>{(id) => <Select id={id} value={value} onChange={(e) => put(k, e.target.value)} options={options} />}</Field>;
  const t = STEP_TYPES[s.type];
  const agentOptions = (() => {
    const base = pick("bot", "Choose an agent");
    if (!s.agent_kind) return base;
    const re = s.agent_kind === "document" ? DOC_ACTIONS : AI_ACTIONS;
    const known = Object.keys(kinds).length > 0;
    return [base[0], ...base.slice(1).filter((o) => !known || re.test(kinds[o.value] ?? "") || o.value === s.bot)];
  })();
  const inputsOf = (p: string) => { const a = agents?.find((x) => x.path === p); return a ? a.variables.filter((v) => v.direction === "input" || v.direction === "in_out") : null; };
  const due = <>
    <Text label="Due within (hours)" type="number" value={str(s.due_hours)} onCommit={(v) => put("due_hours", v === "" ? undefined : Number(v))} />
    {!!s.due_hours && <fieldset className="wfe-fieldset"><legend>When it is late</legend>
      <Field label="What happens">{(id) => <Select id={id} value={s.escalate ? str((s.escalate as Obj).action) || "remind" : ""} onChange={(e) => put("escalate", e.target.value ? { ...(s.escalate as Obj ?? {}), action: e.target.value } : undefined)}
        options={[{ value: "", label: "Nothing (it shows as Late)" }, { value: "remind", label: "Remind the people it is with" }, { value: "reassign", label: "Give it to someone else" }]} />}</Field>
      {!!s.escalate && (s.escalate as Obj).action === "reassign" && <Field label="Give it to">{(id) => <PrincipalPicker id={id} label="Give it to" kinds={["user", "role"]} value={str((s.escalate as Obj).to)}
        onChange={(v) => put("escalate", { ...(s.escalate as Obj), to: v })} />}</Field>}
    </fieldset>}
  </>;
  return <div className="wfe-settings">
    <div className="wfe-set-head"><i className="wfe-dot" style={{ background: t?.color }} aria-hidden="true" /><strong>{s.agent_kind ? SHORTCUTS[str(s.agent_kind)]?.label ?? typeLabel(s.type) : typeLabel(s.type)}</strong><span className="st-muted">{s.id}</span></div>
    <Text label="Name" value={str(s.name)} onCommit={(v) => put("name", v)} />
    {s.type === "bot" && <>
      {sel("Agent", "bot", agentOptions)}
      {!!s.agent_kind && <p className="st-muted">{SHORTCUTS[str(s.agent_kind)]?.hint}. {Object.keys(kinds).length ? "Only such agents are listed." : "Finding which agents those are…"}</p>}
      <Inputs key={str(s.bot)} label="Inputs of the agent" inputs={inputsOf(str(s.bot))} value={(s.inputs && typeof s.inputs === "object" ? s.inputs : {}) as Obj}
        onCommit={(o) => put("inputs", Object.keys(o).length ? o : undefined)} refs="$form.field$" empty="The chosen agent has no inputs." />
      {s.bot && !inputsOf(str(s.bot)) && <p className="st-muted">Choose an agent from this workspace to see its inputs; until then, give names and values.</p>}
      <Text label="Runner pool (optional)" mono value={str(s.pool_id)} placeholder="Any online runner" onCommit={(v) => put("pool_id", v.trim())} />
    </>}
    {s.type === "form" && <>
      {sel("Form", "form", pick("form", "Choose a form"))}
      <Assignee label="Assign to" value={str(s.assignee)} onCommit={(v) => put("assignee", v)} />
      {due}
    </>}
    {s.type === "approval" && <>
      <Assignee label="Approvers" value={str(s.assignee)} onCommit={(v) => put("assignee", v)} />
      <Field label="Approvals needed">{(id) => <Select id={id} value={s.approvals_needed === "all" ? "all" : typeof s.approvals_needed === "number" && s.approvals_needed > 1 ? "n" : "one"}
        onChange={(e) => put("approvals_needed", e.target.value === "all" ? "all" : e.target.value === "n" ? 2 : undefined)}
        options={[{ value: "one", label: "Any one" }, { value: "n", label: "A number of them" }, { value: "all", label: "Everyone named" }]} />}</Field>
      {typeof s.approvals_needed === "number" && <Text label="How many" type="number" value={str(s.approvals_needed)} onCommit={(v) => put("approvals_needed", Math.max(1, Number(v) || 1))} />}
      {sel("Show the approver (optional)", "form", pick("form", "The workflow's data"))}
      <div className="wfe-two"><Text label="Yes button" value={str(s.approve_label)} placeholder="Approve" onCommit={(v) => put("approve_label", v.trim())} />
        <Text label="No button" value={str(s.reject_label)} placeholder="Reject" onCommit={(v) => put("reject_label", v.trim())} /></div>
      {sel("If rejected", "on_reject", [{ value: "end", label: "End the workflow" }, { value: "continue", label: "Continue anyway" },
        ...here.filter((x) => x.id !== s.id).map((x) => ({ value: x.id, label: "Go to " + (str(x.name) || x.id) }))], str(s.on_reject) || "end")}
      <label className="st-check-line"><input type="checkbox" checked={!!s.reason_required} onChange={(e) => put("reason_required", e.target.checked)} /> Ask for a reason when rejecting</label>
      {due}
    </>}
    {s.type === "decision" && <DecisionSettings s={s} here={here} onChange={onChange} />}
    {s.type === "notify" && <>
      <Field label="To">{(id) => <PrincipalPicker id={id} label="To" kinds={["user", "role"]} emails value={str(s.to)} onChange={(v) => put("to", v)} />}</Field>
      <Text label="E-mail subject" value={str(s.subject)} placeholder="Order $order$ approved" hint="For e-mail addresses." onCommit={(v) => put("subject", v)} />
      <Text label="Message" multiline value={str(s.message)} onCommit={(v) => put("message", v)} />
    </>}
    {s.type === "parallel" && <ParallelSettings s={s} onChange={onChange} />}
    {s.type === "wait" && <>
      <Field label="Wait">{(id) => <Select id={id} value={s.until !== undefined ? "until" : "for"} onChange={(e) => onChange(e.target.value === "until"
        ? withKey(withKey(withKey({ ...s, until: "" }, "days", undefined), "hours", undefined), "minutes", undefined) : withKey({ ...s, hours: 1 }, "until", undefined))}
        options={[{ value: "for", label: "For a period" }, { value: "until", label: "Until a date and time" }]} />}</Field>
      {s.until !== undefined ? <Text label="Until" value={str(s.until)} placeholder="2026-12-01T09:00 or $due_date$" hint="A date and time, or a value from the data." onCommit={(v) => onChange({ ...s, until: v.trim() })} />
        : <div className="wfe-three">
          <Text label="Days" type="number" value={str(s.days)} onCommit={(v) => put("days", v === "" ? undefined : Number(v))} />
          <Text label="Hours" type="number" value={str(s.hours)} onCommit={(v) => put("hours", v === "" ? undefined : Number(v))} />
          <Text label="Minutes" type="number" value={str(s.minutes)} onCommit={(v) => put("minutes", v === "" ? undefined : Number(v))} /></div>}
    </>}
    {s.type === "subflow" && <>
      {sel("Workflow", "workflow", pick("process", "Choose a workflow"))}
      <Inputs label="Data it starts with (empty: all of this workflow's data)" inputs={null} value={(s.inputs && typeof s.inputs === "object" ? s.inputs : {}) as Obj}
        onCommit={(o) => put("inputs", Object.keys(o).length ? o : undefined)} refs="$name$" />
      <p className="st-muted">The workflow waits until the sub-workflow ends; its data comes back here. If it fails, this workflow fails.</p>
    </>}
    {s.type === "end" && <>
      {sel("Ends as", "status", [{ value: "completed", label: "Completed" }, { value: "failed", label: "Failed" }, { value: "cancelled", label: "Cancelled" }], str(s.status) || "completed")}
      <Text label="Message (optional)" value={str(s.message)} placeholder="Invoice $number$ posted" onCommit={(v) => put("message", v)} />
    </>}
    {!["decision", "end"].includes(s.type) && sel("Then", "next", targets)}
    <Text label="Notes" multiline value={str(s.description)} placeholder="Why this step is here…" onCommit={(v) => put("description", v)} />
  </div>;
}

/** Inputs kept while they are edited and handed up when focus leaves them (one undo step); JSON that is not valid shows as an error. */
function Inputs({ label, inputs, value, onCommit, refs, empty }: { label: string; inputs: Parameters<typeof AgentInputs>[0]["inputs"]; value: Obj; onCommit: (o: Obj) => void; refs: string; empty?: string }) {
  const [draft, setDraft] = useState(value);
  const [bad, setBad] = useState<string | null>(null);
  return <div onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null) && !bad && JSON.stringify(draft) !== JSON.stringify(value)) onCommit(draft); }}>
    <AgentInputs label={label} inputs={inputs} value={draft} onChange={setDraft} refs={refs} onJsonError={setBad} empty={empty} />
  </div>;
}

/** A decision's ways: If, Else if... (each a condition and where it goes), then Else. */
function DecisionSettings({ s, here, onChange }: { s: Step; here: Step[]; onChange: (s: Step) => void }) {
  // an old yes/no decision turns into branches when edited here
  const branches = (Array.isArray(s.branches) ? s.branches : str(s.condition) ? [{ name: "If", condition: s.condition, goto: s.if_true ?? "" }] : []) as Obj[];
  const elseTo = Array.isArray(s.branches) ? str(s.else) : str(s.if_false);
  const save = (bs: Obj[], el: string) => { const n = { ...s, branches: bs, else: el } as Step; delete n.condition; delete n.if_true; delete n.if_false; onChange(n); };
  const options = [{ value: "", label: "The next step" }, { value: "end", label: "End the workflow" }, ...here.filter((x) => x.id !== s.id).map((x) => ({ value: x.id, label: "Go to " + (str(x.name) || x.id) }))];
  return <fieldset className="wfe-fieldset"><legend>Ways</legend>
    {branches.map((b, i) => <div key={i} className="wfe-branch" role="group" aria-label={(i === 0 ? "If" : "Else if") + " " + (i + 1)}>
      <Text label={(i === 0 ? "If" : "Else if") + ": name"} value={str(b.name)} placeholder={i === 0 ? "Big" : "Medium"} onCommit={(v) => save(branches.map((x, j) => j === i ? withKey(x, "name", v) : x), elseTo)} />
      <Text label="Condition" mono value={str(b.condition)} placeholder="$amount$ > 100000" onCommit={(v) => save(branches.map((x, j) => j === i ? { ...x, condition: v } : x), elseTo)} />
      <Field label="Goes to">{(id) => <Select id={id} value={str(b.goto)} onChange={(e) => save(branches.map((x, j) => j === i ? withKey(x, "goto", e.target.value) : x), elseTo)} options={options} />}</Field>
      {branches.length > 1 && <Button kind="plain" onClick={() => save(branches.filter((_, j) => j !== i), elseTo)}>Remove this way</Button>}
    </div>)}
    <Button kind="secondary" onClick={() => save([...branches, { name: "Else if " + (branches.length + 1), condition: "" }], elseTo)}>Add Else if</Button>
    <Field label="Else goes to">{(id) => <Select id={id} value={elseTo} onChange={(e) => save(branches, e.target.value)} options={options} />}</Field>
  </fieldset>;
}

/** A Parallel step's branches (named; their steps are added on the diagram) and what happens when one fails. */
function ParallelSettings({ s, onChange }: { s: Step; onChange: (s: Step) => void }) {
  const bs = branchesOf(s);
  const save = (next: Branch[]) => onChange({ ...s, branches: next });
  return <>
    <fieldset className="wfe-fieldset"><legend>Branches (they run at the same time)</legend>
      {bs.map((b, i) => <div key={i} className="wfe-two">
        <Text label={"Branch " + (i + 1)} value={str(b.name)} onCommit={(v) => save(bs.map((x, j) => j === i ? { ...x, name: v } : x))} />
        <span className="st-muted wfe-count">{(b.stages ?? []).length} step(s){bs.length > 2 && <> · <button type="button" className="cc-link" onClick={() => save(bs.filter((_, j) => j !== i))}>Remove</button></>}</span>
      </div>)}
      <Button kind="secondary" onClick={() => save([...bs, { name: "Branch " + (bs.length + 1), stages: [] }])}>Add a branch</Button>
      <p className="st-muted">Add a branch's steps with the + under its name on the diagram. The workflow goes on when every branch is done.</p>
    </fieldset>
    <Field label="When a branch fails">{(id) => <Select id={id} value={str(s.on_failure) || "stop"} onChange={(e) => onChange({ ...s, on_failure: e.target.value })}
      options={[{ value: "stop", label: "Stop the other branches at once; the workflow fails" }, { value: "finish", label: "Let the others finish, then the workflow fails" }]} />}</Field>
  </>;
}

