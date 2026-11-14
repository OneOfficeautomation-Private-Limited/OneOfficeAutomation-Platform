import { errorText, runErrorText } from "../api/client";
import { useEffect, useRef, useState } from "react";
import type { ActionManifest, FieldSpec, PackageManifest } from "../api/client";
import { Button, Select, TextInput } from "../components";
import { parseLoose, showValue } from "./flowEdit";
import { OPS, conditionText } from "./toolkits";
import { CommitInput } from "./ValueSources";
import { WindowField, isWindowTitle } from "./FieldKinds";
import { hasReadState, readState, readStateArgs, READ_STATES, type ReadState } from "./mailFields";
import { useVars } from "./VariablesPanel";

/**
 * Try the condition: a value for each of the agent's variables it uses (their defaults to start with), then whether
 * it is true or false, worked out by the server as a run would.
 */
function ConditionTest({ value }: { value: unknown }) {
  const vars = useVars();
  const used = vars ? (vars.flow.variables ?? []).filter((v) => new RegExp("\\$\\{?" + v.name + "\\b").test(JSON.stringify(value ?? ""))) : [];
  const [values, setValues] = useState<Record<string, string>>({});
  const [answer, setAnswer] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  if (!vars || value === undefined) return null;
  const valueOf = (name: string, fallback: unknown) => name in values ? values[name] : showValue(fallback);
  const test = async () => {
    setBusy(true);
    try {
      const r = await vars.api.testCondition(value, used.map((v) => {
        const text = valueOf(v.name, v.default);
        return text === "" ? { name: v.name, type: v.type } : { name: v.name, type: v.type, value: parseLoose(text) };
      }));
      setAnswer(r.error !== undefined ? { ok: false, text: "Could not be checked: " + (typeof r.error === "string" ? r.error : runErrorText(r.error, "unknown error")) }
        : { ok: true, text: r.result ? "True: the step runs its first part." : "False." });
    } catch (e) { setAnswer({ ok: false, text: "Could not be checked: " + errorText(e) }); }
    finally { setBusy(false); }
  };
  return (
    <details className="st-cond-test">
      <summary>Test the condition</summary>
      {used.length > 0 ? used.map((v) => (
        <div key={v.name} className="st-cond-test-row">
          <label className="st-cond-var" htmlFor={"cond-test-" + v.name}>${v.name}</label>
          <TextInput id={"cond-test-" + v.name} aria-label={"Value of " + v.name} value={valueOf(v.name, v.default)}
            onChange={(e) => { setValues({ ...values, [v.name]: e.target.value }); setAnswer(null); }} />
        </div>)) : <p className="st-muted">It uses none of the agent's variables.</p>}
      <div className="st-row-tight"><Button kind="secondary" onClick={test} disabled={busy}>{busy ? "Testing…" : "Test"}</Button>
        {answer && <span role="status" className={answer.ok ? "st-cond-answer" : "st-check"}>{answer.text}</span>}</div>
    </details>
  );
}

/**
 * The condition as a tree (usage review C1 and C7, 9 Nov 2026, as AA's If): groups ("All of these" / "Any of these",
 * inside each other as AA's Add group) of checks. A check compares a value ("$total is greater than 1000") or is one of
 * the toolkits' conditions (File exists, Window exists, Application is running…) with its own fields, which can be
 * turned round ("is not").
 */
type ValueCheck = { kind: "value"; left: string; op: string; right: string };
type ToolkitCheck = { kind: "toolkit"; condition: string; args: Record<string, unknown>; not: boolean };
type Group = { kind: "group"; mode: "all" | "any"; items: Node[] };
type Node = ValueCheck | ToolkitCheck | Group;
type Model = { kind: "tree"; root: Group } | { kind: "advanced"; text: string };

const NO_RIGHT = new Set(["empty", "notEmpty"]);
const isObj = (c: unknown): c is Record<string, unknown> => typeof c === "object" && c !== null && !Array.isArray(c);
const blankValue = (): ValueCheck => ({ kind: "value", left: "", op: "eq", right: "" });

/** A part of a condition as a node; null when it is richer than the builder shows (an expression, a Not around a group). */
function toNode(c: unknown, depth = 0): Node | null {
  if (!isObj(c) || depth > 8) return null;
  if (typeof c.op === "string") return { kind: "value", left: showValue(c.left), op: c.op, right: showValue(c.right) };
  if (typeof c.condition === "string") return { kind: "toolkit", condition: c.condition, args: isObj(c.args) ? { ...c.args } : {}, not: false };
  if (isObj(c.not) && typeof c.not.condition === "string") return { kind: "toolkit", condition: c.not.condition, args: isObj(c.not.args) ? { ...c.not.args } : {}, not: true };
  for (const mode of ["all", "any"] as const) {
    const list = c[mode];
    if (Array.isArray(list) && list.length) {
      const items = list.map((x) => toNode(x, depth + 1));
      return items.every((x) => x !== null) ? { kind: "group", mode, items: items as Node[] } : null;
    }
  }
  return null;
}

function toModel(value: unknown): Model {
  if (value === undefined || value === "") return { kind: "tree", root: { kind: "group", mode: "all", items: [blankValue()] } };
  const n = toNode(value);
  if (!n) return { kind: "advanced", text: showValue(value) };
  return { kind: "tree", root: n.kind === "group" ? n : { kind: "group", mode: "all", items: [n] } };
}

/** The condition the runner reads; empty rows are left out and a group of one is just that one. */
function toValue(n: Node): unknown {
  if (n.kind === "value") {
    if (n.left.trim() === "") return undefined;
    const out: Record<string, unknown> = { op: n.op, left: n.left.trim() };
    if (!NO_RIGHT.has(n.op)) out.right = parseLoose(n.right) ?? "";
    return out;
  }
  if (n.kind === "toolkit") {
    if (!n.condition) return undefined;
    const args = Object.fromEntries(Object.entries(n.args).filter(([, v]) => v !== undefined && v !== ""));
    const c: Record<string, unknown> = { condition: n.condition, ...(Object.keys(args).length ? { args } : {}) };
    return n.not ? { not: c } : c;
  }
  const items = n.items.map(toValue).filter((x) => x !== undefined);
  if (!items.length) return undefined;
  return items.length === 1 ? items[0] : { [n.mode]: items };
}
const modelValue = (m: Model): unknown => m.kind === "advanced" ? parseLoose(m.text) : toValue(m.root);

/** "What to check": a value, or a toolkit's condition, grouped by toolkit (AA lists them by kind). */
function whatOptions(packages: PackageManifest[]) {
  const opts: { value: string; label: string; group?: string }[] = [{ value: "", label: "A value (compare it)" }];
  for (const p of [...packages].filter((x) => x.conditions?.length).sort((a, b) => a.name.localeCompare(b.name)))
    for (const c of p.conditions!) opts.push({ value: p.id + "/" + c.id, label: c.name, group: p.name });
  return opts;
}
const specOf = (packages: PackageManifest[], address: string): ActionManifest | undefined => {
  const [pid, cid] = address.split("/");
  return packages.find((p) => p.id === pid)?.conditions?.find((c) => c.id === cid);
};
const labelOf = (name: string, spec?: FieldSpec) => (spec?.["x-ooa-label"] as string | undefined) ?? (name.charAt(0).toUpperCase() + name.slice(1).replace(/_/g, " "));

/** A toolkit condition's own fields. */
function ToolkitFields({ node, spec, onChange }: { node: ToolkitCheck; spec?: ActionManifest; onChange: (args: Record<string, unknown>) => void }) {
  return <SpecFields args={node.args} spec={spec} keyPrefix={node.condition} onChange={onChange} />;
}

/**
 * The fields a toolkit's condition or loop type declares (C1, C2), each taking a value or a $variable; a list of
 * choices is a drop-down, yes/no a Yes/No choice. An empty field is left out, so the toolkit's default applies.
 */
export function SpecFields({ args, spec, keyPrefix, onChange }: { args: Record<string, unknown>; spec?: ActionManifest; keyPrefix: string;
  onChange: (args: Record<string, unknown>) => void }) {
  const node = { args, condition: keyPrefix };
  const props = spec?.inputs?.properties ?? {};
  const required = new Set(spec?.inputs?.required ?? []);
  const names = Object.keys(props);
  if (!names.length) return null;
  const put = (name: string, v: unknown) => onChange({ ...node.args, [name]: v });
  return (
    <div className="st-cond-fields">
      {names.map((name) => {
        const s = props[name];
        // A4: a mail loop's unread_only and read_only as one choice, stored as the two ticks
        if (name === "read_only" && hasReadState(props)) return null;
        if (name === "unread_only" && hasReadState(props))
          return <label key={name} className="st-cond-field"><span>Read state</span>
            <Select aria-label="Read state" value={readState(node.args)} options={READ_STATES}
              onChange={(e) => onChange({ ...node.args, ...readStateArgs(e.target.value as ReadState) })} /></label>;
        const label = labelOf(name, s) + (required.has(name) ? " *" : "");
        const cur = node.args[name];
        if (Array.isArray(s.enum) && s.enum.length)
          return <label key={name} className="st-cond-field"><span>{label}</span>
            <Select aria-label={label} value={cur === undefined ? "" : String(cur)} onChange={(e) => put(name, e.target.value === "" ? undefined : e.target.value)}
              options={[{ value: "", label: s.default !== undefined ? "Default: " + String(s.default) : "(choose)" }, ...s.enum.map((v) => ({ value: String(v), label: String(v) }))]} /></label>;
        if (s.type === "boolean" || s["x-ooa-type"] === "Boolean")
          return <label key={name} className="st-cond-field"><span>{label}</span>
            <Select aria-label={label} value={cur === undefined ? "" : String(cur)} onChange={(e) => put(name, e.target.value === "" ? undefined : e.target.value === "true")}
              options={[{ value: "", label: s.default !== undefined ? "Default: " + (s.default ? "Yes" : "No") : "(not set)" }, { value: "true", label: "Yes" }, { value: "false", label: "No" }]} /></label>;
        if (isWindowTitle(keyPrefix, name, s) && (s["x-ooa-type"] === "String" || s["x-ooa-type"] === undefined))
          return <div key={name} className="st-cond-field"><span>{label}</span>
            <WindowField id={"cond-" + keyPrefix + "-" + name} name={name} value={cur} fallback={s.default !== undefined ? "Default: " + showValue(s.default) : ""}
              onCommit={(v) => put(name, v)} /></div>;
        return <label key={name} className="st-cond-field"><span>{label}</span>
          <CommitInput key={node.condition + name + showValue(cur)} label={label} initial={cur === undefined ? "" : showValue(cur)} want={(s["x-ooa-type"] as string) ?? "Any"}
            placeholder={s.default !== undefined ? "Default: " + showValue(s.default) : s.description?.slice(0, 40)}
            onCommit={(t) => put(name, t === "" ? undefined : parseLoose(t))} /></label>;
      })}
    </div>
  );
}

/**
 * Builds a condition in plain words, as AA and UiPath do: groups of checks, "All of these are true" or "Any of these is
 * true"; a check compares a value or is a toolkit's condition with its fields. The sentence it makes is shown below.
 * Conditions too rich for the builder (expressions) stay editable as text. Each change is one undo step: text when its
 * field loses focus.
 */
export function ConditionEditor({ value, onCommit }: { value: unknown; onCommit: (v: unknown) => void }) {
  const vars = useVars();
  const packages = vars?.packages ?? [];
  const [model, setModelState] = useState<Model>(() => toModel(value));
  // the latest model, also between a keystroke and the next redraw, so leaving a field saves what is in it
  const latest = useRef(model);
  const setModel = (m: Model) => { latest.current = m; setModelState(m); };
  // the condition changed from outside (Undo, Redo): show it; our own changes match already, so focus stays put
  useEffect(() => {
    if (JSON.stringify(modelValue(latest.current)) !== JSON.stringify(value)) setModel(toModel(value));
  }, [value]);
  const commit = (m: Model) => { setModel(m); const v = modelValue(m); if (JSON.stringify(v) !== JSON.stringify(value)) onCommit(v); };

  if (model.kind === "advanced") {
    return (
      <div className="st-cond">
        <TextInput aria-label="Condition as an expression" mono defaultValue={model.text} onBlur={(e) => commit({ kind: "advanced", text: e.currentTarget.value })} />
        <p className="st-cond-sentence">{value === undefined ? "No condition yet." : conditionText(value, packages)}</p>
        <Button kind="plain" onClick={() => setModel(toModel(undefined))}>Build it with checks instead</Button>
        <ConditionTest value={value} />
      </div>
    );
  }
  const what = whatOptions(packages);
  /** Changes the node at a path (indexes from the top group) and keeps or commits the result. */
  const update = (path: number[], change: (n: Node) => Node | null, now = true) => {
    const m = latest.current;
    if (m.kind !== "tree") return;
    const walk = (n: Node, rest: number[]): Node | null => {
      if (!rest.length) return change(n);
      if (n.kind !== "group") return n;
      const [i, ...more] = rest;
      const items = n.items.map((x, k) => k === i ? walk(x, more) : x).filter((x): x is Node => x !== null);
      return { ...n, items: items.length ? items : [blankValue()] };
    };
    const root = walk(m.root, path) as Group;
    const next: Model = { kind: "tree", root };
    if (now) commit(next); else setModel(next);
  };

  const row = (n: ValueCheck | ToolkitCheck, path: number[], removable: boolean) => {
    const label = "Check " + path.map((i) => i + 1).join(".");
    const choose = (address: string) => update(path, () => address ? { kind: "toolkit", condition: address, args: {}, not: false } : blankValue());
    return (
      <div className="st-cond-row" role="group" aria-label={label}>
        <Select aria-label="What to check" className="st-cond-what" value={n.kind === "toolkit" ? n.condition : ""} onChange={(e) => choose(e.target.value)} options={what} />
        {n.kind === "value" ? <>
          {/* item 4 (14 Nov usability round): the condition's values take Insert value, $ and F2 as every field does */}
          <CommitInput key={label + ":l:" + n.left} label="Value" mono want="Any" initial={n.left} placeholder="$total$"
            onCommit={(t) => update(path, (x) => ({ ...(x as ValueCheck), left: t }))} />
          <Select aria-label="Comparison" value={n.op} onChange={(e) => update(path, (x) => ({ ...(x as ValueCheck), op: e.target.value }))}
            options={Object.entries(OPS).map(([op, words]) => ({ value: op, label: words }))} />
          {!NO_RIGHT.has(n.op) && <CommitInput key={label + ":r:" + n.right} label="Compared with" want="Any" initial={n.right} placeholder="1000"
            onCommit={(t) => update(path, (x) => ({ ...(x as ValueCheck), right: t }))} />}
        </> : <>
          <Select aria-label="Is or is not" value={n.not ? "not" : "is"} onChange={(e) => update(path, (x) => ({ ...(x as ToolkitCheck), not: e.target.value === "not" }))}
            options={[{ value: "is", label: "is true" }, { value: "not", label: "is not true" }]} />
          <ToolkitFields node={n} spec={specOf(packages, n.condition)} onChange={(args) => update(path, (x) => ({ ...(x as ToolkitCheck), args }))} />
        </>}
        {removable && <Button kind="plain" aria-label={"Remove " + label.toLowerCase()} onClick={() => update(path, () => null)}>Remove</Button>}
      </div>
    );
  };

  const group = (g: Group, path: number[]): React.ReactNode => (
    <div className={"st-cond-group" + (path.length ? " is-inner" : "")} role="group" aria-label={path.length ? "Group " + path.map((i) => i + 1).join(".") : "Condition"}>
      {(g.items.length > 1 || path.length > 0) && (
        <div className="st-cond-group-head">
          <Select aria-label={path.length ? "Combine checks in group " + path.map((i) => i + 1).join(".") : "Combine checks"} value={g.mode}
            onChange={(e) => update(path, (x) => ({ ...(x as Group), mode: e.target.value as "all" | "any" }))}
            options={[{ value: "all", label: "All of these are true" }, { value: "any", label: "Any of these is true" }]} />
          {path.length > 0 && <Button kind="plain" aria-label={"Remove group " + path.map((i) => i + 1).join(".")} onClick={() => update(path, () => null)}>Remove group</Button>}
        </div>
      )}
      {g.items.map((n, i) => <div key={i}>{n.kind === "group" ? group(n, [...path, i]) : row(n, [...path, i], g.items.length > 1 || path.length > 0)}</div>)}
      <div className="st-cond-actions">
        <Button kind="secondary" icon="plus" onClick={() => update(path, (x) => ({ ...(x as Group), items: [...(x as Group).items, blankValue()] }), false)}>Add a check</Button>
        {path.length < 3 && <Button kind="plain" icon="plus" onClick={() => update(path, (x) => ({ ...(x as Group), items: [...(x as Group).items,
          { kind: "group", mode: (x as Group).mode === "all" ? "any" : "all", items: [blankValue()] }] }), false)}>Add a group</Button>}
        {!path.length && <Button kind="plain" onClick={() => setModel({ kind: "advanced", text: showValue(value) })}>Write it as an expression</Button>}
      </div>
    </div>
  );

  return (
    <div className="st-cond">
      {group(model.root, [])}
      <p className="st-cond-sentence">{value === undefined ? "No condition yet." : "Reads: " + conditionText(value, packages)}</p>
      <ConditionTest value={value} />
    </div>
  );
}
