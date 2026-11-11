import { useId, useState } from "react";
import type { Flow, FlowStep } from "../api/client";
import { Button, Select, TextInput } from "../components";
import { enumWord } from "./enumWords";
import { parseLoose, showValue } from "./flowEdit";

/**
 * The columns of the table a Data Table step works on, as far as the agent tells (command review D4, 10 Nov 2026): the
 * table variable's default, and the columns other Data Table steps on the same table name. Typing stays possible.
 */
export function knownColumns(flow: Flow, table: unknown): string[] {
  const out = new Set<string>();
  const ref = typeof table === "string" ? table.trim() : "";
  const name = ref.startsWith("$") ? ref.slice(1).replace(/^\{|\}$/g, "") : "";
  const v = (flow.variables ?? []).find((x) => x.name === name);
  const d = v?.default as { columns?: unknown[] } | undefined;
  if (d && Array.isArray(d.columns)) for (const c of d.columns) if (typeof c === "string") out.add(c);
  const walk = (steps: FlowStep[]) => {
    for (const s of steps) {
      if (s.action.startsWith("ooa.datatable/") && s.args?.table === table) {
        for (const k of ["column", "return_column", "new_name"]) if (typeof s.args?.[k] === "string" && !String(s.args[k]).startsWith("$")) out.add(String(s.args[k]));
        for (const k of ["columns", "then_by"]) if (Array.isArray(s.args?.[k])) for (const c of s.args![k] as unknown[]) if (typeof c === "string") out.add(c);
      }
      for (const inner of [s.children, s.else, s.finally, s.default, ...(s.elseIf ?? []).map((b) => b.children), ...(s.catch ?? []).map((c) => c.children)])
        if (inner) walk(inner);
    }
  };
  walk(flow.steps);
  return [...out].sort((a, b) => a.localeCompare(b));
}

/** A column name, typed or picked from the table's known columns. */
export function ColumnField({ id, value, columns, placeholder, onCommit }: { id: string; value: unknown; columns: string[]; placeholder?: string; onCommit: (v: string | undefined) => void }) {
  const listId = useId();
  const text = value === undefined || value === null ? "" : String(value);
  return <>
    <TextInput key={text} id={id} defaultValue={text} list={columns.length ? listId : undefined} placeholder={placeholder ?? (columns.length ? "Pick or type a column" : "Column name")}
      onBlur={(e) => { const t = e.currentTarget.value.trim(); if (t !== text) onCommit(t || undefined); }}
      onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); const t = e.currentTarget.value.trim(); if (t !== text) onCommit(t || undefined); } }} />
    {columns.length > 0 && <datalist id={listId}>{columns.map((c) => <option key={c} value={c} />)}</datalist>}
  </>;
}

type Cond = { column?: string; operator?: string; value?: unknown; match_case?: boolean };
const OPS = ["eq", "ne", "gt", "ge", "lt", "le", "contains", "not_contains", "starts_with", "ends_with", "matches", "empty", "not_empty"];

/** Filter rows' further conditions (D3): rows of column, comparison and value, as UiPath's Filter Data Table. */
export function MoreConditions({ value, columns, onCommit }: { value: unknown; columns: string[]; onCommit: (v: Cond[] | undefined) => void }) {
  const list: Cond[] = Array.isArray(value) ? (value as Cond[]) : [];
  const [rows, setRows] = useState<Cond[]>(list);
  const save = (next: Cond[]) => { setRows(next); const kept = next.filter((r) => r.column); onCommit(kept.length ? kept : undefined); };
  return (
    <div className="st-more-conds">
      {rows.map((r, i) => (
        <div key={i} className="st-cond-row" role="group" aria-label={"Condition " + (i + 2)}>
          <ColumnField id={"mc-" + i} value={r.column} columns={columns} onCommit={(c) => save(rows.map((x, k) => k === i ? { ...x, column: c } : x))} />
          <Select aria-label="Comparison" value={r.operator ?? "eq"} onChange={(e) => save(rows.map((x, k) => k === i ? { ...x, operator: e.target.value } : x))}
            options={OPS.map((o) => ({ value: o, label: enumWord(o) }))} />
          {!["empty", "not_empty"].includes(r.operator ?? "eq") && <TextInput aria-label="Value" key={showValue(r.value)} defaultValue={r.value === undefined ? "" : showValue(r.value)}
            onBlur={(e) => { const t = e.currentTarget.value; save(rows.map((x, k) => k === i ? { ...x, value: t === "" ? undefined : parseLoose(t) } : x)); }} />}
          <Button kind="plain" aria-label={"Remove condition " + (i + 2)} onClick={() => save(rows.filter((_, k) => k !== i))}>Remove</Button>
        </div>
      ))}
      <Button kind="secondary" icon="plus" onClick={() => setRows([...rows, { operator: "eq" }])}>Add a condition</Button>
    </div>
  );
}

/** Whether a step's action opens a session (Open workbook, Connect, Start session, Log in, Create...). */
const OPENS = /^(open|connect|create|start|login|log_in|launch|attach|begin)(_|$)/;
/** Marks the default session (the field's own default name) among the sessions an agent opens. */
const DEFAULT = "\u0000default";

/**
 * The session names the agent's own steps of a toolkit use, the ones its Open/Connect steps name first (command review
 * X3, 11 Nov 2026): what a session field offers. An agent with only the default session needs none of this.
 */
export function knownSessions(flow: Flow, toolkit: string): { opened: string[]; used: string[] } {
  const opened = new Set<string>(), used = new Set<string>();
  const walk = (steps: FlowStep[]) => {
    for (const s of steps) {
      const [pid, aid] = s.action.split("/");
      const name = s.args?.session;
      if (pid === toolkit && typeof name === "string" && name.trim() && !name.startsWith("$")) (OPENS.test(aid ?? "") ? opened : used).add(name.trim());
      else if (pid === toolkit && name === undefined && OPENS.test(aid ?? "")) opened.add(DEFAULT);   // an Open step left on the default session
      for (const inner of [s.children, s.else, s.finally, s.default, ...(s.elseIf ?? []).map((b) => b.children), ...(s.catch ?? []).map((c) => c.children),
        ...(s.cases ?? []).map((c) => c.children), ...(s.states ?? []).flatMap((st) => [st.entry, ...(st.transitions ?? []).map((t) => t.steps)])])
        if (inner) walk(inner);
    }
  };
  walk(flow.steps);
  for (const sub of flow.subflows ?? []) walk(sub.steps);
  if (flow.onError?.steps) walk(flow.onError.steps);
  return { opened: [...opened].sort(), used: [...used].filter((n) => !opened.has(n)).sort() };
}

/** A session name, picked from the agent's own sessions of the toolkit or typed; says so when no step opens it. */
export function SessionField({ id, value, fallback, opens, sessions, onCommit }: { id: string; value: unknown; fallback?: string; opens: boolean;
  sessions: { opened: string[]; used: string[] }; onCommit: (v: string | undefined) => void }) {
  const listId = useId();
  const text = value === undefined || value === null ? "" : String(value);
  const opened = sessions.opened.map((n) => n === DEFAULT ? fallback ?? "" : n).filter(Boolean);
  const names = [...new Set([...opened, ...sessions.used, ...(fallback ? [fallback] : [])])];
  const shown = text || fallback || "";
  const unknown = !opens && shown && !shown.startsWith("$") && opened.length > 0 && !opened.includes(shown);
  return <>
    <TextInput key={text} id={id} defaultValue={text} list={listId} placeholder={fallback ? "Default: " + fallback : "Session name"}
      onBlur={(e) => { const t = e.currentTarget.value.trim(); if (t !== text) onCommit(t || undefined); }}
      onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); const t = e.currentTarget.value.trim(); if (t !== text) onCommit(t || undefined); } }} />
    <datalist id={listId}>{names.map((n) => <option key={n} value={n}>{opened.includes(n) ? "opened by a step of this agent" : ""}</option>)}</datalist>
    {unknown && <div className="st-check" role="note">No step of this agent opens a session named “{shown}”. Opened: {opened.join(", ")}.</div>}
  </>;
}
