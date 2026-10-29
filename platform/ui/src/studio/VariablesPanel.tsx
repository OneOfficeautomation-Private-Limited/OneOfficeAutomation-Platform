import { createContext, useContext, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import type { AgentSummary, Builtins, Flow, FlowVariable } from "../api/client";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";
import { parseLoose, showValue } from "./flowEdit";
import { plainValue } from "./toolkits";
import {
  compatible, directionOf, GROUPS, guessType, isInput, isOutput, saveVariable, TYPE_STYLE, TYPES, uniqueName, usageCount, type Direction,
} from "./variables";

/**
 * What a new variable starts as, and what to change once it is made (put it in the field that asked): a change to
 * the agent with the variable in it, so both land as one edit (one undo).
 */
export type NewVariable = { type?: string; name?: string; direction?: Direction; description?: string; then?: (name: string, flow: Flow) => Flow };

/**
 * The open agent's variables for every field below it: the {x} picker, the output and credential choosers, and
 * "+ New variable" (which opens the variable editor and hands the new name back).
 */
export type VarsCtx = { flow: Flow; builtins: Builtins; agents: AgentSummary[]; edit: (next: Flow) => void; newVariable: (preset: NewVariable) => void };
export const VarsContext = createContext<VarsCtx | null>(null);
export const useVars = () => useContext(VarsContext);

export function TypeBadge({ type }: { type: string }) {
  const st = TYPE_STYLE[type] ?? TYPE_STYLE.Any;
  return <span className="st-type-badge" style={{ background: st.color }} title={type + ": " + st.hint} aria-hidden="true">{st.short}</span>;
}

const preview = (v: unknown) => plainValue(v, 42);   // lists joined, calculations as their text
const usedAs = (v: FlowVariable) => GROUPS.find((g) => g.dir === (v.direction ?? "local"))?.label ?? "";

/**
 * Pick a variable to put in a field: the agent's variables of the field's type first (text takes any), then the
 * platform's $sys values and the shared $global values; the rest one click away; or make a new one of that type.
 */
export function VarPicker({ want, onPick, onNew, onClose, nameHint, anchor }:
  { want?: string; onPick: (ref: string) => void; onNew?: (ref: string, flow: Flow) => Flow; onClose: () => void; nameHint?: string; anchor?: DOMRect }) {
  const ctx = useVars();
  const [q, setQ] = useState("");
  const [all, setAll] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => { box.current?.querySelector("input")?.focus(); }, []);
  // closes on a click outside it
  useEffect(() => {
    // (the {x} button that opened it toggles it itself)
    const away = (e: MouseEvent) => { const t = e.target as HTMLElement; if (box.current && !box.current.contains(t) && !t.closest?.(".st-x-btn")) onClose(); };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [onClose]);
  if (!ctx) return null;
  const f = q.trim().toLowerCase();
  const typed = want && want !== "Any" ? want : undefined;
  const fits = (t: string) => all || typed === "String" || compatible(typed, t);
  const vars = (ctx.flow.variables ?? []).filter((v) => !f || v.name.toLowerCase().includes(f));
  const good = vars.filter((v) => fits(v.type));
  const hidden = all ? 0 : vars.length - good.length;
  const builtin = (prefix: string, values: Record<string, unknown>) => Object.entries(values)
    .filter(([k, val]) => (!f || (prefix + k).toLowerCase().includes(f)) && fits(guessType(val)));
  const sys = builtin("sys.", ctx.builtins.sys), glob = builtin("global.", ctx.builtins.global);
  const keys = (e: KeyboardEvent) => {
    const items = [...(box.current?.querySelectorAll<HTMLElement>(".st-vp-item") ?? [])];
    const i = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); onClose(); }
    else if (e.key === "ArrowDown") { e.preventDefault(); (items[i + 1] ?? items[0])?.focus(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); if (i <= 0) box.current?.querySelector("input")?.focus(); else items[i - 1].focus(); }
    else if (e.key === "Enter" && (e.target as HTMLElement).tagName === "INPUT" && items[0]) { e.preventDefault(); items[0].click(); }
  };
  const item = (ref: string, type: string, note: string) => (
    <button key={ref} type="button" role="option" aria-selected="false" className="st-vp-item" onClick={() => onPick(ref)}>
      <TypeBadge type={type} /><span className="ui-mono">{ref}</span>{note && <span className="st-vp-note">{note}</span>}
    </button>
  );
  // above the page, next to the field, so a scrolling panel does not cut it off
  const W = 340, H = 360;
  const at = anchor ? { left: Math.max(8, Math.min(anchor.left, window.innerWidth - W - 8)),
    top: anchor.bottom + H + 8 > window.innerHeight ? Math.max(8, anchor.top - H - 4) : anchor.bottom + 4 } : undefined;
  return createPortal(
    <div className="st-vp" role="dialog" aria-label="Insert a variable" ref={box} onKeyDown={keys} style={at}>
      <TextInput type="search" aria-label="Find a variable" placeholder="Find a variable" value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="st-vp-list" role="listbox" aria-label="Variables">
        {good.length > 0 && <div className="st-vp-head">{typed && typed !== "String" && !all ? typed + " variables" : "Agent variables"}</div>}
        {good.map((v) => item("$" + v.name, v.type, v.direction && v.direction !== "local" ? usedAs(v).toLowerCase() : ""))}
        {sys.length > 0 && <div className="st-vp-head">System</div>}
        {sys.map(([k, val]) => item("$sys." + k, guessType(val), preview(val)))}
        {glob.length > 0 && <div className="st-vp-head">Shared values</div>}
        {glob.map(([k, val]) => item("$global." + k, guessType(val), preview(val)))}
        {!good.length && !sys.length && !glob.length && <p className="st-vp-empty">{typed && !all ? "No " + typed + " variables yet." : "No variables match."}</p>}
      </div>
      <div className="st-vp-foot">
        {hidden > 0 && <Button kind="plain" onClick={() => setAll(true)}>Show all ({hidden} of another type)</Button>}
        <Button kind="plain" icon="plus" onClick={() => { onClose(); ctx.newVariable({ type: typed ?? "String", name: nameHint, then: onNew && ((n, fl) => onNew("$" + n, fl)) }); }}>
          New variable{typed ? " (" + typed + ")" : ""}</Button>
      </div>
    </div>, document.body);
}

/** A default value in the shape of its type: a yes/no choice, a number, a date, list items, keys and values, or JSON. */
function ValueEditor({ type, value, onChange, options }: { type: string; value: unknown; onChange: (v: unknown) => void; options?: unknown[] }) {
  if (options?.length) return <Select aria-label="Default value" value={value === undefined ? "" : String(value)}
    onChange={(e) => onChange(e.target.value === "" ? undefined : options.find((x) => String(x) === e.target.value))}
    options={[{ value: "", label: "(none)" }, ...options.map((o) => ({ value: String(o), label: String(o) }))]} />;
  if (type === "Boolean") return <Select aria-label="Default value" value={value === true ? "true" : value === false ? "false" : ""}
    onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value === "true")}
    options={[{ value: "", label: "(none)" }, { value: "true", label: "True" }, { value: "false", label: "False" }]} />;
  if (type === "Number") return <TextInput aria-label="Default value" inputMode="decimal" placeholder="42 or 3.5" defaultValue={value === undefined ? "" : showValue(value)}
    onChange={(e) => { const t = e.target.value.trim(); const n = Number(t); onChange(t === "" ? undefined : Number.isFinite(n) ? n : t); }} />;
  if (type === "DateTime") return <TextInput aria-label="Default value" type="datetime-local" step="1"
    defaultValue={typeof value === "string" ? value.slice(0, 19) : ""} onChange={(e) => onChange(e.target.value ? (e.target.value.length === 16 ? e.target.value + ":00" : e.target.value) : undefined)} />;
  if (type === "List") return <ListEditor value={Array.isArray(value) ? value : []} onChange={(l) => onChange(l.length ? l : undefined)} />;
  if (type === "Dictionary" || type === "Record") return <PairsEditor value={value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {}}
    onChange={(o) => onChange(Object.keys(o).length ? o : undefined)} />;
  if (type === "Table" || type === "Any") return <TextArea aria-label="Default value" rows={3} defaultValue={value === undefined ? "" : JSON.stringify(value, null, 2)}
    placeholder={type === "Table" ? '{"columns": ["Name"], "rows": [["Ada"]]}' : "Text, a number, true/false or JSON"} onChange={(e) => onChange(parseLoose(e.target.value))} />;
  if (type === "Credential") return <p className="st-muted">A credential never has a default: whoever runs the agent maps it to the vault.</p>;
  return <TextInput aria-label="Default value" defaultValue={value === undefined ? "" : showValue(value)}
    placeholder={type === "File" ? "data/orders.csv or C:\\Folder\\file.xlsx" : type === "Window" ? "Window title, e.g. *Notepad" : ""}
    onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)} />;
}

function ListEditor({ value, onChange }: { value: unknown[]; onChange: (v: unknown[]) => void }) {
  const [items, setItems] = useState<string[]>(value.map(showValue));
  const put = (next: string[]) => { setItems(next); onChange(next.filter((x) => x !== "").map((x) => parseLoose(x))); };
  return (
    <div className="st-ve">
      {items.map((it, i) => <div key={i} className="st-ve-row"><span className="st-ve-n">{i + 1}</span>
        <TextInput aria-label={"Item " + (i + 1)} value={it} onChange={(e) => put(items.map((x, j) => j === i ? e.target.value : x))} />
        <Button kind="plain" aria-label={"Remove item " + (i + 1)} onClick={() => put(items.filter((_, j) => j !== i))}>Remove</Button></div>)}
      {!items.length && <p className="st-muted">No items yet.</p>}
      <Button kind="secondary" icon="plus" onClick={() => setItems([...items, ""])}>Item</Button>
    </div>
  );
}

function PairsEditor({ value, onChange }: { value: Record<string, unknown>; onChange: (v: Record<string, unknown>) => void }) {
  const [pairs, setPairs] = useState<[string, string][]>(Object.entries(value).map(([k, v]) => [k, showValue(v)]));
  const put = (next: [string, string][]) => { setPairs(next); onChange(Object.fromEntries(next.filter(([k]) => k.trim()).map(([k, v]) => [k.trim(), parseLoose(v) ?? ""]))); };
  return (
    <div className="st-ve">
      {pairs.map(([k, v], i) => <div key={i} className="st-ve-row">
        <TextInput aria-label={"Key " + (i + 1)} placeholder="key" value={k} onChange={(e) => put(pairs.map((p, j) => j === i ? [e.target.value, p[1]] : p))} />
        <TextInput aria-label={"Value " + (i + 1)} placeholder="value" value={v} onChange={(e) => put(pairs.map((p, j) => j === i ? [p[0], e.target.value] : p))} />
        <Button kind="plain" aria-label={"Remove key " + (k || i + 1)} onClick={() => put(pairs.filter((_, j) => j !== i))}>Remove</Button></div>)}
      {!pairs.length && <p className="st-muted">No keys yet.</p>}
      <Button kind="secondary" icon="plus" onClick={() => setPairs([...pairs, ["", ""]])}>Key</Button>
    </div>
  );
}

/**
 * Create or change a variable: name (a rename updates every use), type, Use as input and Use as output, required,
 * the allowed values of an input, its default in the shape of its type, a business figure for outputs, and what it holds.
 */
export function VariableDialog({ flow, existing, preset, onSave, onClose }:
  { flow: Flow; existing?: FlowVariable; preset?: NewVariable; onSave: (flow: Flow, name: string) => void; onClose: () => void }) {
  const [v, setV] = useState<FlowVariable>(() => existing ? structuredClone(existing)
    : { name: uniqueName(flow, preset?.name ?? "new_variable"), type: preset?.type ?? "String", direction: preset?.direction === "local" ? undefined : preset?.direction,
        description: preset?.description });
  const [allowed, setAllowed] = useState((existing?.enum ?? []).join(", "));
  const [error, setError] = useState<string | null>(null);
  const [defKey, setDefKey] = useState(0);   // a new type starts a fresh default editor
  const put = (patch: Partial<FlowVariable>) => setV({ ...v, ...patch });
  const setUse = (input: boolean, output: boolean) => put({ direction: directionOf(input, output) });
  const options = isInput(v) && ["String", "Number"].includes(v.type) ? allowed.split(",").map((x) => x.trim()).filter(Boolean)
    .map((x) => v.type === "Number" && Number.isFinite(Number(x)) ? Number(x) : x) : [];
  const save = () => {
    try {
      const out: FlowVariable = { ...v, name: v.name.trim(), enum: options.length ? options : undefined };
      if (out.enum && out.default !== undefined && !out.enum.includes(out.default)) delete out.default;
      if (!out.enum) delete out.enum;
      const next = saveVariable(flow, out, existing?.name);
      onSave(next, out.name);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  const uses = existing ? usageCount(flow, existing.name) : 0;
  return (
    <Dialog open onClose={onClose} title={existing ? "Variable $" + existing.name : "New variable"} width={620}
      footer={<><Button kind="secondary" onClick={onClose}>Cancel</Button><span className="st-bar-fill" />
        <Button kind="primary" onClick={save}>{existing ? "Save changes" : "Create variable"}</Button></>}>
      <form className="st-form" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <Field label="Name" hint={"Use it in any field as $" + (v.name || "name") + (existing && uses ? `. Renaming updates its ${uses} use${uses > 1 ? "s" : ""} in this agent.` : ".")}>{(id) =>
          <TextInput id={id} mono value={v.name} spellCheck={false} onChange={(e) => put({ name: e.target.value.replace(/^\$/, "") })} />}</Field>
        <Field label="Type" hint={(TYPE_STYLE[v.type] ?? TYPE_STYLE.Any).hint}>{(id) =>
          <Select id={id} value={v.type} onChange={(e) => { setV({ ...v, type: e.target.value, default: undefined }); setAllowed(""); setDefKey(defKey + 1); }}
            options={TYPES.map((t) => ({ value: t, label: t + " · " + TYPE_STYLE[t].hint }))} />}</Field>
        <fieldset className="st-fieldset">
          <legend>Use</legend>
          <label className="st-check-line"><input type="checkbox" checked={isInput(v)} onChange={(e) => setUse(e.target.checked, isOutput(v))} /> Use as input: given when the agent starts (by a person, a schedule or a parent agent)</label>
          <label className="st-check-line"><input type="checkbox" checked={isOutput(v)} onChange={(e) => setUse(isInput(v), e.target.checked)} /> Use as output: returned when the agent ends</label>
          {isInput(v) && <label className="st-check-line"><input type="checkbox" checked={!!v.required} onChange={(e) => put({ required: e.target.checked || undefined })} /> Required: the agent cannot start without a value</label>}
          {isOutput(v) && ["Number", "String", "Boolean"].includes(v.type) && <label className="st-check-line"><input type="checkbox" checked={!!v.kpi}
            onChange={(e) => put({ kpi: e.target.checked || undefined })} /> Business figure: each successful run records it for Command Center dashboards</label>}
        </fieldset>
        {isInput(v) && ["String", "Number"].includes(v.type) && <Field label="Allowed values" hint="Optional: the only values a caller may give, separated by commas.">{(id) =>
          <TextInput id={id} value={allowed} onChange={(e) => setAllowed(e.target.value)} />}</Field>}
        <div className="ui-field ui-field-above">
          <span className="st-label">Default value</span>
          <ValueEditor key={v.type + defKey + options.join()} type={v.type} value={v.default} options={options} onChange={(d) => setV((cur) => ({ ...cur, default: d }))} />
          <div className="ui-field-hint">{v.type === "Credential" ? "" : isInput(v) ? "Used when the caller gives no value." : "The value it starts with. Empty for none."}</div>
        </div>
        <Field label="Description" hint="People running the agent and AI agents read this.">{(id) =>
          <TextArea id={id} rows={2} value={v.description ?? ""} onChange={(e) => put({ description: e.target.value })} />}</Field>
        {error && <p role="alert" className="st-error">{error}</p>}
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}

/**
 * The agent's variables, grouped by use (inputs, inputs and outputs, outputs, inside the agent), each with its type,
 * default or last value, and how often it is used; then the platform's System values and the Shared values.
 */
export function VariablesPanel({ flow, builtins, last, onEdit, onRefresh }:
  { flow: Flow; builtins: Builtins; last: Map<string, unknown>; onEdit: (next: Flow) => void; onRefresh: () => void }) {
  const ctx = useVars();
  const [q, setQ] = useState("");
  const [editing, setEditing] = useState<FlowVariable | null>(null);
  const f = q.trim().toLowerCase();
  const vars = (flow.variables ?? []).filter((v) => !f || v.name.toLowerCase().includes(f) || v.type.toLowerCase().includes(f) || (v.description ?? "").toLowerCase().includes(f));
  const remove = (v: FlowVariable) => {
    const uses = usageCount(flow, v.name);
    if (uses && !window.confirm("$" + v.name + " is used " + uses + " time" + (uses > 1 ? "s" : "") + " in this agent. Delete it anyway?")) return;
    onEdit({ ...flow, variables: (flow.variables ?? []).filter((x) => x !== v) });
  };
  const duplicate = (v: FlowVariable) => onEdit({ ...flow, variables: [...(flow.variables ?? []), { ...structuredClone(v), name: uniqueName(flow, v.name + "_copy") }] });
  const row = (v: FlowVariable) => {
    const uses = usageCount(flow, v.name);
    const meta = [v.type, v.required ? "required" : null, v.default !== undefined ? "= " + preview(v.default) : null,
      last.has(v.name) ? "last run: " + preview(last.get(v.name)) : null].filter(Boolean).join(" · ");
    return (
      <li key={v.name} className="st-var-row">
        <button type="button" className="st-var-main" onClick={() => setEditing(v)} title={(v.description ? v.description + "\n" : "") + "Click to change"}>
          <TypeBadge type={v.type} />
          <span className="st-var-text"><span className="st-var ui-mono">${v.name}</span><span className="st-var-meta">{meta}</span></span>
          <span className={"st-uses" + (uses ? "" : " is-none")}>{uses ? uses + (uses === 1 ? " use" : " uses") : "unused"}</span>
        </button>
        <Button kind="plain" aria-label={"Duplicate $" + v.name} onClick={() => duplicate(v)}>Copy</Button>
        <Button kind="plain" aria-label={"Delete $" + v.name} onClick={() => remove(v)}>Delete</Button>
      </li>
    );
  };
  const builtin = (title: string, prefix: string, values: Record<string, unknown>, note: string) => {
    const rows = Object.entries(values).filter(([k]) => !f || (prefix + k).toLowerCase().includes(f));
    return (
      <details className="st-var-builtins">
        <summary><span>{title}</span><span className="st-count">{rows.length}</span></summary>
        <p className="st-muted st-var-note">{note}</p>
        {rows.length ? <ul className="st-vars">{rows.map(([k, val]) => <li key={k} className="st-var-row is-builtin">
          <span className="st-var-main"><TypeBadge type={guessType(val)} /><span className="st-var-text"><span className="st-var ui-mono">{prefix + k}</span><span className="st-var-meta">{preview(val)}</span></span></span>
        </li>)}</ul> : <p className="st-muted st-var-note">None defined.</p>}
      </details>
    );
  };
  return (
    <div className="st-variables">
      <div className="st-var-bar">
        <TextInput type="search" aria-label="Search variables" placeholder="Search variables" value={q} onChange={(e) => setQ(e.target.value)} />
        <Button kind="primary" icon="plus" onClick={() => ctx?.newVariable({})}>New</Button>
      </div>
      {!(flow.variables ?? []).length ? (
        <div className="st-var-empty"><strong>No variables yet</strong>
          <p>Variables hold values while the agent runs. Inputs are given when it starts; outputs are returned at the end.</p></div>
      ) : GROUPS.map((g) => {
        const list = vars.filter((v) => (v.direction ?? "local") === g.dir);
        if (g.dir === "in_out" && !list.length) return null;
        return (
          <section key={g.dir} className="st-var-group" aria-label={g.label}>
            <div className="st-var-group-head"><strong>{g.label}</strong><span className="st-count">{list.length}</span><span className="st-muted">{g.hint}</span>
              <Button kind="plain" icon="plus" aria-label={"New " + g.label.toLowerCase() + " variable"} onClick={() => ctx?.newVariable({ direction: g.dir })}>Add</Button></div>
            {list.length ? <ul className="st-vars">{list.map(row)}</ul> : <p className="st-muted st-var-note">{f ? "No matches." : "None."}</p>}
          </section>
        );
      })}
      {builtin("System variables", "$sys.", builtins.sys, "Read-only values from the platform at run time, such as today's date or the machine name.")}
      {builtin("Shared values", "$global.", builtins.global, "Shared by all agents; managed in Command Center under Shared values.")}
      <div className="st-pad"><Button kind="plain" onClick={onRefresh}>Refresh system and shared values</Button></div>
      {editing && <VariableDialog flow={flow} existing={editing} onClose={() => setEditing(null)} onSave={(next) => { onEdit(next); setEditing(null); }} />}
    </div>
  );
}
