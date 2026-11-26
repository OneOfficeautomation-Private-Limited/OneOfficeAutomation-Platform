import { useEffect, useRef, useState } from "react";
import { AgentInputs, Button } from "../../components";
import type { StepState } from "./WorkflowDiagram";
import { branchesOf, stagesOf, str, typeLabel, type Obj, type Step } from "./flow";

/**
 * Test run... (review round, 26 Nov 2026): a dry run of the workflow in Studio. Nothing runs: no agent starts, no task
 * or e-mail goes out. You give the starting data and, step by step, what each agent, form, approval or sub-workflow
 * gives back; decisions are worked out with Studio's formula check on the data so far; Parallel branches go one
 * after the other. The diagram shows the path taken (done, the step it is at now, failed). It follows the server's
 * rules (Workflows.advance): a step's result is kept under its id and its values at the top level, a form's answers
 * also as $form.field$, a rejection goes where the approval says.
 */
type Ask = { kind: "agent" | "form" | "subflow" | "approval"; step: Step } | { kind: "eval"; expr: string; step: Step };
type Answer = { values?: Obj; approved?: boolean; fail?: boolean } | unknown;
type Result = { status: "completed" | "failed" | "cancelled"; message?: string };
type Evaluate = (expr: string, vars: Record<string, unknown>) => Promise<{ value?: unknown; error?: { message: string }; missing?: string[] }>;

const truthy = (v: unknown) => !(v === false || v === null || v === undefined || v === 0 || v === "" || v === "false" || v === "no");
const resolve = (text: string, data: Obj) => text.replace(/\$([A-Za-z_][\w.]*)\$/g, (_, n: string) => { const v = n.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Obj)[k] : undefined), data); return v === undefined ? "$" + n + "$" : String(v); });

/** The workflow walked step by step; it stops (yields) where it needs an answer. */
function* walk(list: Step[], data: Obj, log: (id: string, text: string, state?: StepState) => void): Generator<Ask, Result, Answer> {
  let i = 0;
  const jump = (to: unknown): boolean => { const t = str(to); if (!t) { i++; return true; } if (t === "end") { i = list.length; return true; } const j = list.findIndex((s) => s.id === t); if (j < 0) return false; i = j; return true; };
  const merge = (s: Step, out: Obj) => { data[s.id] = out; Object.assign(data, out); };
  for (let guard = 0; i < list.length && guard < 500; guard++) {
    const s = list[i];
    log(s.id, "", "now");
    switch (s.type) {
      case "bot": case "subflow": {
        const a = (yield { kind: s.type === "bot" ? "agent" : "subflow", step: s }) as { values?: Obj; fail?: boolean };
        if (a.fail) { log(s.id, (s.type === "bot" ? "The agent" : "The sub-workflow") + " failed", "failed"); return { status: "failed", message: str(s.name) + " failed" }; }
        merge(s, a.values ?? {}); log(s.id, "Gave back " + JSON.stringify(a.values ?? {}), "done"); break;
      }
      case "form": {
        const a = (yield { kind: "form", step: s }) as { values?: Obj };
        merge(s, a.values ?? {}); data.form = a.values ?? {}; log(s.id, "Answered " + JSON.stringify(a.values ?? {}), "done"); break;
      }
      case "approval": {
        const a = (yield { kind: "approval", step: s }) as { approved?: boolean };
        merge(s, { approved: !!a.approved }); log(s.id, a.approved ? "Approved" : "Rejected", "done");
        if (!a.approved) {
          const r = str(s.on_reject) || "end";
          if (r === "end") return { status: "completed", message: "Ended after the rejection at " + str(s.name) };
          if (r !== "continue") { if (!jump(r)) return { status: "failed", message: "Missing step " + r }; continue; }
        }
        break;
      }
      case "decision": {
        const ways = (Array.isArray(s.branches) ? s.branches : str(s.condition) ? [{ name: "Yes", condition: s.condition, goto: s.if_true }] : []) as Obj[];
        let to: unknown = Array.isArray(s.branches) ? s.else : s.if_false, chosen = "Else";
        for (const w of ways) {
          const v = yield { kind: "eval", expr: str(w.condition), step: s };
          if (truthy(v)) { to = w.goto; chosen = str(w.name) || "If"; break; }
        }
        log(s.id, "Took " + chosen, "done");
        if (!jump(to)) return { status: "failed", message: "Missing step " + str(to) };
        continue;
      }
      case "notify": log(s.id, "Would tell " + str(s.to) + ": " + resolve(str(s.message), data), "done"); break;
      case "wait": log(s.id, "Would wait " + (s.until ? "until " + resolve(str(s.until), data) : [s.days && s.days + " d", s.hours && s.hours + " h", s.minutes && s.minutes + " min"].filter(Boolean).join(" ")), "done"); break;
      case "parallel": {
        const byBranch: Obj = {};
        for (const [n, b] of branchesOf(s).entries()) {
          const copy = { ...data };
          const r: Result = yield* walk(b.stages ?? [], copy, log);
          byBranch[b.name || "Branch " + (n + 1)] = copy;
          if (r.status !== "completed") { log(s.id, "A branch ended " + r.status, "failed"); return { status: "failed", message: (b.name || "A branch") + " of " + str(s.name) + " ended " + r.status }; }
        }
        for (const v of Object.values(byBranch)) Object.assign(data, v);
        data.branches = byBranch;
        log(s.id, "All branches done", "done"); break;
      }
      case "end": {
        const st = (str(s.status) || "completed") as Result["status"];
        log(s.id, "Ended " + st, st === "completed" ? "done" : "failed");
        return { status: st, message: s.message ? resolve(str(s.message), data) : undefined };
      }
      default: log(s.id, "Unknown step " + s.type, "failed"); return { status: "failed", message: "Unknown step type " + s.type };
    }
    if (str(s.next)) { if (!jump(s.next)) return { status: "failed", message: "Missing step " + str(s.next) }; } else i++;
  }
  return { status: "completed" };
}

export function DryRun({ data: def, evaluate, onStates, onClose }: { data: Obj; evaluate?: Evaluate; onStates: (s: Record<string, StepState>) => void; onClose: () => void }) {
  const [start, setStart] = useState<Obj>(() => Object.fromEntries(((Array.isArray(def.inputs) ? def.inputs : []) as string[]).map((n) => [n, ""])));
  const [ask, setAsk] = useState<Ask | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [log, setLog] = useState<{ id: string; text: string }[]>([]);
  const [values, setValues] = useState<Obj>({});
  const [error, setError] = useState<string | null>(null);
  const gen = useRef<Generator<Ask, Result, Answer> | null>(null);
  const data = useRef<Obj>({});
  const states = useRef<Record<string, StepState>>({});
  const names = useRef(new Map<string, string>());
  useEffect(() => { const walkNames = (l: Step[]) => { for (const s of l) { names.current.set(s.id, str(s.name) || s.id); for (const b of branchesOf(s)) walkNames(b.stages ?? []); } }; walkNames(stagesOf(def)); }, [def]);
  useEffect(() => () => onStates({}), []);   // eslint-disable-line react-hooks/exhaustive-deps

  const note = (id: string, text: string, state?: StepState) => {
    if (state) { states.current = { ...states.current, [id]: state }; onStates(states.current); }
    if (text) setLog((l) => [...l, { id, text }]);
  };
  /** Goes on until the walk needs a person's answer or ends; decisions are worked out on the way. */
  const go = async (answer?: Answer) => {
    let r = answer === undefined ? gen.current!.next() : gen.current!.next(answer);
    while (!r.done && r.value.kind === "eval") {
      const e = r.value;
      let v: unknown = false;
      if (evaluate) {
        const out: { value?: unknown; error?: { message: string }; missing?: string[] } = await evaluate(e.expr, data.current).catch((x) => ({ error: { message: String(x) } }));
        if (out.error || (out.missing && out.missing.length)) { setError(str(names.current.get(e.step.id)) + ": " + (out.error?.message ?? "no value for " + out.missing!.join(", ")) + ". Taken as not true."); }
        else v = out.value;
      } else setError("Decisions need OneAgentStudio's formula check; taken as not true.");
      r = gen.current!.next(v);
    }
    if (r.done) { setAsk(null); setResult(r.value); return; }
    setValues({}); setAsk(r.value);
  };
  const begin = () => {
    data.current = { ...start }; states.current = {}; onStates({}); setLog([]); setResult(null); setError(null);
    gen.current = walk(stagesOf(def), data.current, note);
    go();
  };

  return <section className="wfe-dry" aria-label="Test run">
    <div className="wfe-set-head"><strong>Test run</strong><span className="st-muted">nothing runs</span><span className="st-bar-gap" /><Button kind="plain" onClick={onClose}>Close</Button></div>
    {!gen.current && <>
      <p className="st-muted">Give the data it starts with, then answer for each step. Agents, tasks and e-mails do not run or go out.</p>
      <AgentInputs label="Starting data" value={start} onChange={setStart}
        inputs={Array.isArray(def.inputs) && def.inputs.length ? (def.inputs as string[]).map((n) => ({ name: n, type: "String", direction: "input" })) : null} />
      <Button kind="primary" onClick={begin}>Start the test run</Button>
    </>}
    {error && <p className="st-alert" role="alert">{error}</p>}
    {ask && ask.kind !== "eval" && <div className="wfe-ask" role="group" aria-label={"Step " + (names.current.get(ask.step.id) ?? ask.step.id)}>
      <strong>{names.current.get(ask.step.id)}</strong> <span className="st-muted">{typeLabel(ask.step.type)}</span>
      {ask.kind === "approval" ? <div className="wfe-ask-buttons">
          <Button kind="danger" onClick={() => go({ approved: false })}>{str(ask.step.reject_label) || "Reject"}</Button>
          <Button kind="primary" onClick={() => go({ approved: true })}>{str(ask.step.approve_label) || "Approve"}</Button></div>
        : <>
          <AgentInputs label={ask.kind === "form" ? "The answers" : ask.kind === "agent" ? "What the agent gives back" : "What the sub-workflow gives back"} inputs={null} value={values} onChange={setValues} />
          <div className="wfe-ask-buttons">
            {ask.kind !== "form" && <Button kind="danger" onClick={() => go({ fail: true })}>It fails</Button>}
            <Button kind="primary" onClick={() => go({ values })}>Continue</Button></div>
        </>}
    </div>}
    {result && <p className={result.status === "completed" ? "wfe-ok" : "st-alert"} role="status">The workflow ends {result.status}{result.message ? ": " + result.message : ""}.</p>}
    {log.length > 0 && <ol className="wfe-path" aria-label="Path taken">{log.map((l, i) => <li key={i}><strong>{names.current.get(l.id) ?? l.id}</strong>: {l.text}</li>)}</ol>}
    {(gen.current && (result || ask)) && <Button kind="secondary" onClick={() => { gen.current = null; setAsk(null); setResult(null); setLog([]); onStates({}); }}>Start again</Button>}
    {Object.keys(data.current).length > 0 && <details><summary>Data so far</summary><pre className="ui-mono wfe-data">{JSON.stringify(data.current, null, 2)}</pre></details>}
  </section>;
}
