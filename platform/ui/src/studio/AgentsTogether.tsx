import { useEffect, useState, type DragEvent, type ReactNode } from "react";
import type { Dependencies, Dependency, Flow, FlowStep, ModelConnections, PackageManifest, Proposal, StudioApi } from "../api/client";
import { ApiFailure } from "../api/client";
import { Button, Dialog, Field, IconTile, Select, TextArea } from "../components";
import { type Place } from "./flowEdit";
import { conditionText, describeAction, plainValue, summarize } from "./toolkits";
import { isInput, isOutput } from "./variables";

/** The agent in plain words: what it needs and returns, then its steps, blocks indented (Classic's Explain this agent). */
export function explainAgent(flow: Flow, packages: PackageManifest[] = []): string {
  const lines: string[] = [];
  const walk = (steps: FlowStep[] | undefined, depth: number) => (steps ?? []).forEach((s, i) => {
    if (s.disabled) return;
    const pad = "  ".repeat(depth) + (depth ? "- " : (i + 1) + ". ");
    const a = s.args ?? {};
    let text: string;
    if (s.action === "core/if") text = "If " + conditionText(a.condition, packages) + ":";
    else if (s.action === "core/loop") text = "Repeat " + (a.iterator === "times" ? plainValue(a.count) + " times" : a.iterator === "while" ? "while " + conditionText(a.condition, packages)
      : "for each item of " + plainValue(a.over) + (a.as ? " (as $" + a.as + ")" : "")) + ":";
    else if (s.action === "core/try") text = "Try the following, and handle errors:";
    else if (s.action === "core/set") text = "Set $" + a.variable + " to " + plainValue(a.value);
    else if (s.action === "core/comment") text = "Note: " + plainValue(a.text);
    else text = describeAction(s.action, packages).name + (s.note ? " (" + s.note + ")" : "")
      + (Object.keys(a).length ? " with " + Object.entries(a).slice(0, 3).map(([k, v]) => k.replace(/_/g, " ") + " " + plainValue(v, 40)).join(", ") : "")
      + (s.out ? ", storing " + Object.values(s.out).map((v) => "$" + v).join(" and ") : "");
    lines.push(pad + text);
    walk(s.children, depth + 1);
    for (const b of s.elseIf ?? []) { lines.push("  ".repeat(depth + 1) + "Otherwise, if " + conditionText(b.condition, packages) + ":"); walk(b.children, depth + 2); }
    if (s.action === "core/if" && s.else?.length) { lines.push("  ".repeat(depth + 1) + "Otherwise:"); walk(s.else, depth + 2); }
    for (const c of s.catch ?? []) if (c.children?.length) { lines.push("  ".repeat(depth + 1) + "If an error happens" + (c.as ? " (kept in $" + c.as + ")" : "") + ":"); walk(c.children, depth + 2); }
    if (s.finally?.length) { lines.push("  ".repeat(depth + 1) + "Always, at the end:"); walk(s.finally, depth + 2); }
  });
  const ins = (flow.variables ?? []).filter(isInput), outs = (flow.variables ?? []).filter(isOutput);
  walk(flow.steps, 0);
  return [(flow.name || "This agent") + (flow.description ? ": " + flow.description : ""),
    ins.length ? "It needs: " + ins.map((v) => "$" + v.name + " (" + v.type + (v.required ? ", required" : "") + ")").join(", ") + "." : "It needs no inputs.",
    outs.length ? "It returns: " + outs.map((v) => "$" + v.name + " (" + v.type + ")").join(", ") + "." : "It returns no outputs.", "", "Steps:",
    ...(lines.length ? lines : ["(no steps yet)"])].join("\n");
}

/**
 * What this agent needs to run (child agents, scripts, Prompt Skills, files) and which agents use it, each opening
 * from here. Read from the saved file: unsaved changes show after Save.
 */
export function DependenciesPanel({ api, path, dirty, onOpen, onStep }:
  { api: StudioApi; path: string; dirty: boolean; onOpen: (path: string) => void; onStep: (stepId: string) => void }) {
  const [deps, setDeps] = useState<Dependencies | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => { setDeps(null); api.deps(path).then((d) => { setDeps(d); setError(null); }, (e) => setError(e instanceof ApiFailure ? e.error.message : String(e))); };
  useEffect(load, [api, path]);   // eslint-disable-line react-hooks/exhaustive-deps
  const group = (title: string, items: Dependency[], empty: string, mark: { icon: "bolt" | "code" | "spark" | "doc" | "branch"; color: string }, usedBy = false) => (
    <section className="st-dep-group" aria-label={title}>
      <div className="st-dep-head"><IconTile name={mark.icon} color={mark.color} size={20} /><strong>{title}</strong><span className="st-count">{items.length}</span></div>
      {items.length ? <ul className="st-dep-list">{items.map((x, i) => <li key={i} className={x.missing ? "is-missing" : ""}>
        {x.path ? <button type="button" className="st-link ui-mono" onClick={() => onOpen(x.path!)}>{x.path}</button> : <span className="ui-mono">{x.name}</span>}
        {x.missing && <span className="st-check">Not in the workspace</span>}
        {x.step && !usedBy && <button type="button" className="st-link st-dep-step" onClick={() => onStep(x.step!)}>step {x.step}</button>}
      </li>)}</ul> : <p className="st-muted st-dep-empty">{empty}</p>}
    </section>
  );
  return (
    <div className="st-deps">
      {dirty && <p className="st-alert" role="note">Save the agent to include your latest changes.</p>}
      <p className="st-muted st-pad">What this agent needs to run, and which agents use it. Check child agents in too before running this agent from Command Center.</p>
      {error ? <p className="st-error st-pad" role="alert">{error}</p> : !deps ? <p className="st-muted st-pad">Looking through the workspace…</p> : <>
        {group("Child agents and agent tools", deps.bots, "This agent does not call other agents.", { icon: "bolt", color: "#6d28d9" })}
        {group("Scripts", deps.scripts, "No script files.", { icon: "code", color: "#0f766e" })}
        {group("Prompt Skills", deps.skills, "No Prompt Skills.", { icon: "spark", color: "#a21caf" })}
        {group("Files", deps.files, "No workspace files referenced.", { icon: "doc", color: "#475569" })}
        {group("Used by", deps.usedBy, "No other agent in the workspace calls this agent.", { icon: "branch", color: "#1d4ed8" }, true)}
      </>}
      <div className="st-pad"><Button kind="plain" onClick={load}>Look again</Button></div>
    </div>
  );
}

const STEP_DRAG = "application/x-ooa-step";
const ACTION_DRAG = "application/x-ooa-action";

/**
 * The agent as a diagram: Start, one box per step joined by lines, an If's parts side by side, a loop's or a Try's
 * steps framed inside it, and End. Clicking a box selects the step (its settings open); an action or a step dropped
 * on a line goes there.
 */
export function FlowDiagram({ flow, packages, selected, onSelect, onInsert, onMove, running }:
  { flow: Flow; packages: PackageManifest[]; selected: string[]; onSelect: (id: string) => void; running: string | null;
    onInsert: (action: string, targetId: string | null, where: Place) => void; onMove: (ids: string[], targetId: string | null, where: Place) => void }) {
  const [over, setOver] = useState<string | null>(null);
  const dropOn = (e: DragEvent, key: string, targetId: string | null, where: Place) => {
    e.preventDefault(); setOver(null);
    const action = e.dataTransfer.getData(ACTION_DRAG), steps = e.dataTransfer.getData(STEP_DRAG);
    if (action) onInsert(action, targetId, where);
    else if (steps) onMove(JSON.parse(steps) as string[], targetId, where);
    void key;
  };
  const line = (key: string, targetId: string | null, where: Place) => (
    <div key={"l" + key} className={"st-fline" + (over === key ? " is-drop" : "")} aria-hidden="true"
      onDragOver={(e) => { e.preventDefault(); if (over !== key) setOver(key); }} onDragLeave={() => setOver(null)} onDrop={(e) => dropOn(e, key, targetId, where)} />
  );
  const seq = (steps: FlowStep[] | undefined, parentKey: string): ReactNode[] => {
    const out: ReactNode[] = [];
    (steps ?? []).forEach((s, i) => {
      if (i === 0) out.push(line(parentKey + ":first:" + s.id, s.id, "before"));
      out.push(node(s));
      out.push(line(parentKey + ":after:" + s.id, s.id, "after"));
    });
    if (!steps?.length) out.push(<div key={"e" + parentKey} className="st-fempty">(no steps)</div>);
    return out;
  };
  const branch = (label: string, steps: FlowStep[] | undefined, key: string) => (
    <div key={key} className="st-fbranch"><div className="st-fbranch-label">{label}</div><div className="st-fseq">{seq(steps, key)}</div></div>
  );
  const node = (s: FlowStep): ReactNode => {
    const info = describeAction(s.action, packages);
    const isSel = selected.includes(s.id);
    const box = (
      <button type="button" className={"st-fnode" + (isSel ? " is-selected" : "") + (s.disabled ? " is-disabled" : "") + (running === s.id ? " is-running" : "")}
        data-step-id={s.id} aria-pressed={isSel} onClick={() => onSelect(s.id)} draggable
        onDragStart={(e) => { e.dataTransfer.setData(STEP_DRAG, JSON.stringify(selected.includes(s.id) ? selected.filter((x) => !x.startsWith("@")) : [s.id])); e.dataTransfer.effectAllowed = "move"; }}>
        <IconTile name={info.mark.icon} color={info.mark.color} size={22} />
        <span className="st-fnode-text"><strong>{info.name}</strong><span>{summarize(s, packages)}</span></span>
      </button>
    );
    if (s.action === "core/if") return (
      <div key={s.id} className="st-fblock">{box}
        <div className="st-fbranches">
          {branch("Then", s.children, s.id + ":then")}
          {(s.elseIf ?? []).map((b, k) => branch("Else if " + conditionText(b.condition, packages), b.children, s.id + ":elseif" + k))}
          {s.else && branch("Else", s.else, s.id + ":else")}
        </div></div>
    );
    if (s.children || s.catch || s.finally) return (
      <div key={s.id} className="st-fblock">{box}
        <div className="st-fframe">
          <div className="st-fseq">{seq(s.children, s.id + ":body")}</div>
          {(s.catch ?? []).map((c, k) => branch(c.codes?.length ? "Catch " + c.codes.join(", ") : "Catch (any error)", c.children, s.id + ":catch" + k))}
          {s.finally && branch("Finally (always)", s.finally, s.id + ":finally")}
        </div></div>
    );
    return <div key={s.id} className="st-fblock">{box}</div>;
  };
  return (
    <div className="st-flow" aria-label="Agent as a diagram">
      <div className="st-fterm is-start">Start</div>
      <div className="st-fseq">{flow.steps.length ? seq(flow.steps, "top") : line("top:empty", null, "after")}</div>
      <div className="st-fterm is-end">End</div>
    </div>
  );
}

/**
 * The agent assistant: Build from a description (a language model proposes steps, checked, to insert) or Explain this
 * agent in plain words. Building needs a One Model Connection.
 */
export function AssistantDialog({ api, flow, packages, initial = "build", onInsert, onNewConnection, onClose }:
  { api: StudioApi; flow: Flow; packages: PackageManifest[]; initial?: "build" | "explain"; onInsert: (p: Proposal) => void; onNewConnection: () => void; onClose: () => void }) {
  const [tab, setTab] = useState(initial);
  const [conns, setConns] = useState<ModelConnections | null>(null);
  const [connection, setConnection] = useState("");
  const [prompt, setPrompt] = useState("");
  const [busy, setBusy] = useState(false);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.modelConnections().then((c) => { setConns(c); setConnection(c.default || c.connections[0]?.name || ""); }, () => setConns({ default: "", connections: [] })); }, [api]);
  const text = tab === "explain" ? explainAgent(flow, packages) : "";
  const generate = async () => {
    if (!prompt.trim()) { setError("Describe what the agent should do."); return; }
    setBusy(true); setError(null); setProposal(null);
    try { setProposal(await api.assistantBuild(prompt, connection, flow)); }
    catch (e) { setError(e instanceof ApiFailure ? e.error.message + (e.error.hint ? " " + e.error.hint : "") : String(e)); }
    finally { setBusy(false); }
  };
  const flat = (steps: FlowStep[] = []): FlowStep[] => steps.flatMap((s) => [s, ...flat(s.children), ...flat(s.else)]);
  return (
    <Dialog open onClose={onClose} title="Agent assistant" width={720}
      footer={<><Button kind="secondary" onClick={onClose}>Close</Button><span className="st-bar-fill" />
        {tab === "explain" && <Button kind="secondary" onClick={() => { navigator.clipboard?.writeText(text).catch(() => {}); }}>Copy</Button>}
        {tab === "build" && proposal && <Button kind="primary" onClick={() => onInsert(proposal)}>Insert into the agent</Button>}
        {tab === "build" && !proposal && !!conns?.connections.length && <Button kind="primary" icon="spark" onClick={generate} disabled={busy}>{busy ? "Thinking…" : "Generate steps"}</Button>}</>}>
      <div className="st-seg" role="tablist" aria-label="Assistant">
        {([["build", "Build from a description"], ["explain", "Explain this agent"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" aria-selected={tab === k} className="st-seg-btn" onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "explain" ? <pre className="st-explain">{text}</pre> : <div className="st-form">
        <Field label="What should the agent do?">{(id) => <TextArea id={id} rows={4} value={prompt} onChange={(e) => setPrompt(e.target.value)}
          placeholder="Read invoices.csv, and for every row with an amount above 1000 send an email to approvals@company.com with the invoice number." />}</Field>
        {conns === null ? <p className="st-muted">Reading the model connections…</p>
          : conns.connections.length ? <Field label="One Model Connection">{(id) => <Select id={id} value={connection} onChange={(e) => setConnection(e.target.value)}
              options={conns.connections.map((c) => ({ value: c.name, label: c.name + " (" + c.provider + (c.model ? ", " + c.model : "") + ")" }))} />}</Field>
          : <div className="st-alert" role="note">The assistant needs a language model. <Button kind="secondary" onClick={onNewConnection}>Set up a One Model Connection</Button> first.</div>}
        {busy && <p className="st-muted" role="status">Thinking… this can take up to a minute.</p>}
        {error && <p className="st-error" role="alert">{error}</p>}
        {proposal && <>
          {proposal.notes && <p>{proposal.notes}</p>}
          <ol className="st-proposal">{flat(proposal.steps).map((s, i) => { const info = describeAction(s.action, packages);
            return <li key={i}><IconTile name={info.mark.icon} color={info.mark.color} size={20} /><strong>{info.name}</strong><span className="st-muted">{summarize(s, packages)}</span></li>; })}</ol>
          {!!proposal.problems?.length && <p className="st-alert">{proposal.problems.length} problem{proposal.problems.length > 1 ? "s" : ""} to fix after inserting: {proposal.problems.slice(0, 3).map((p) => p.message).join("; ")}</p>}
        </>}
      </div>}
    </Dialog>
  );
}
