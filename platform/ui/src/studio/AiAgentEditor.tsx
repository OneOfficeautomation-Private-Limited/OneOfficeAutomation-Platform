import { useState } from "react";
import { ApiFailure, type AgentSummary, type Flow, type ModelConnections, type StudioApi } from "../api/client";
import { Button, Field, SearchSelect, Select, TabPanel, Tabs, TextInput } from "../components";
import { aiStep } from "./aiAgent";
import { setArg } from "./flowEdit";
import { isFinished, type LiveRun } from "./RunPanel";
import { oneStep, runOnce } from "./runOnce";

type Args = Record<string, unknown>;
const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));
const list = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);

function TextField({ label, value, onCommit, multiline, placeholder, hint, mono, type }:
  { label: string; value: string; onCommit: (v: string) => void; multiline?: boolean; placeholder?: string; hint?: string; mono?: boolean; type?: string }) {
  return (
    <Field label={label} hint={hint}>{(id) => multiline
      ? <textarea key={value} id={id} className={"ui-input ui-textarea" + (mono ? " ui-mono" : "")} rows={3} defaultValue={value} placeholder={placeholder}
          onBlur={(e) => { if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); }} />
      : <TextInput key={value} id={id} type={type} mono={mono} defaultValue={value} placeholder={placeholder}
          onBlur={(e) => { if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); }}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); if (e.currentTarget.value !== value) onCommit(e.currentTarget.value); } }} />}
    </Field>
  );
}

/**
 * The AI agent editor (approved design V6.6): describe the job in a sentence and let the model write the role,
 * instructions and plan (which you can change); choose the agents it may use as tools, and which need a person's
 * approval; the model, the values it must return, and its limits; and try it on a request, with the answer and the
 * trace of what it did. Everything is saved as the arguments of the agent's one Run AI agent step.
 */
export function AiAgentEditor({ api, flow, onEdit, agents, connections, onNewConnection, run, onTry, onShowSteps }:
  { api: StudioApi; flow: Flow; onEdit: (next: Flow) => void; agents: AgentSummary[]; connections: ModelConnections | null;
    onNewConnection: () => void; run: LiveRun | null; onTry: (request: string) => void; onShowSteps: () => void }) {
  const step = aiStep(flow)!;
  const a: Args = step.args ?? {};
  const put = (k: string, v: unknown) => onEdit(setArg(flow, step.id, k, v === "" ? undefined : v));
  const [tab, setTab] = useState("instructions");
  const [brief, setBrief] = useState(flow.description ?? "");
  const [drafting, setDrafting] = useState<string | null>(null);
  const [request, setRequest] = useState("");
  const tools = list(a.tools), sensitive = list(a.sensitive_tools);
  const nameOf = (id: string) => agents.find((x) => x.id === id)?.name ?? id;
  const fields = (a.output_fields && typeof a.output_fields === "object" ? a.output_fields : {}) as Record<string, string>;
  const conns = connections?.connections ?? [];

  /** Asks the model (the default connection) to write a role, instructions and plan from the brief. */
  const draft = async () => {
    if (!brief.trim()) { setDrafting("Describe the job first."); return; }
    const conn = str(a.connection) || connections?.default;
    if (!conn) { setDrafting("Add a model connection first (Model tab), so the model can write these."); return; }
    setDrafting("Writing…");
    const tools = agents.filter((x) => list(a.tools).includes(x.id)).map((x) => x.name + " (" + x.id + ")").join(", ") || "none yet";
    const prompt = "You set up an AI agent for business automation. The job: " + brief.trim() + "\nTools it may use: " + tools +
      "\nReturn JSON with keys role (one sentence: who it acts as), instructions (rules and policy, a few short sentences) and action_plan (numbered steps naming the tools, and when to stop or ask a person).";
    try {
      const r = await runOnce(api, oneStep("ooa.genai/prompt", { prompt, connection: conn, response_format: "json", max_tokens: 700 }, { json: "json" }));
      const j = r.outputs?.json as Record<string, unknown> | undefined;
      if (r.status !== "success" || !j) { setDrafting("The model could not write it: " + (r.error?.message ?? r.status)); return; }
      let next: Flow = { ...flow, description: brief.trim() };
      for (const k of ["role", "instructions", "action_plan"]) if (typeof j[k] === "string") next = setArg(next, step.id, k, j[k]);
      onEdit(next);
      setDrafting("Written. Change anything below.");
    } catch (e) { setDrafting("The model could not write it: " + (e instanceof ApiFailure ? e.error.message : String(e))); }
  };

  const busy = !!run && !isFinished(run);
  const out = run?.result?.outputs ?? {};
  const trace = Array.isArray(out.trace) ? out.trace : [];
  return (
    <div className="st-ai">
      <main className="st-ai-main">
        <section className="st-card" aria-label="Describe the agent">
          <Field label="What should this agent do?">{(id) =>
            <textarea id={id} className="ui-input ui-textarea" rows={2} value={brief} onChange={(e) => setBrief(e.target.value)}
              onBlur={() => { if (brief !== (flow.description ?? "")) onEdit({ ...flow, description: brief }); }}
              placeholder="Check each new invoice against its purchase order. If amounts or suppliers do not match, ask finance; otherwise queue it for payment." />}</Field>
          <div className="st-row">
            <Button kind="primary" icon="spark" onClick={draft} disabled={drafting === "Writing…"}>Write role, instructions and plan</Button>
            {drafting && <span role="status" className="st-muted">{drafting}</span>}
            <span className="st-bar-fill" />
            <Button kind="plain" onClick={onShowSteps}>Show as steps</Button>
          </div>
        </section>
        <Tabs label="AI agent settings" value={tab} onChange={setTab} tabs={[{ value: "instructions", label: "Instructions" }, { value: "tools", label: `Tools (${tools.length})` },
          { value: "model", label: "Model" }, { value: "outputs", label: "Outputs" }, { value: "limits", label: "Limits" }]}>
          <TabPanel value="instructions"><div className="st-ai-fields">
            <TextField label="Role" value={str(a.role)} placeholder="Accounts payable assistant for Acme Finance" onCommit={(v) => put("role", v)} />
            <TextField label="Goal" mono value={str(a.goal)} onCommit={(v) => put("goal", v)} hint="What to achieve each time; ${request} is what the agent is asked when it runs." />
            <TextField label="Instructions" multiline value={str(a.instructions)} placeholder="Rules and policy: never invent results; ask a person when unsure." onCommit={(v) => put("instructions", v)} />
            <TextField label="Plan" multiline value={str(a.action_plan)} placeholder="1. Read the invoice. 2. Find the order. 3. Compare. 4. Queue it or ask finance." onCommit={(v) => put("action_plan", v)} />
          </div></TabPanel>
          <TabPanel value="tools"><div className="st-ai-fields">
            <p className="st-muted">The agent may use only these agents. Each agent's description tells the model when to use it.</p>
            <table className="st-table">
              <thead><tr><th scope="col">Agent</th><th scope="col">Needs a person's approval</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
              <tbody>
                {tools.map((id) => (
                  <tr key={id}>
                    <td><strong>{nameOf(id)}</strong> <span className="st-muted">{id}</span></td>
                    <td><input type="checkbox" aria-label={"Ask a person before each use of " + nameOf(id)} checked={sensitive.includes(id)}
                      onChange={(e) => put("sensitive_tools", e.target.checked ? [...sensitive, id] : sensitive.filter((x) => x !== id))} /></td>
                    <td className="st-row-actions"><Button kind="plain" aria-label={"Remove " + nameOf(id)}
                      onClick={() => { let f = setArg(flow, step.id, "tools", tools.filter((x) => x !== id)); f = setArg(f, step.id, "sensitive_tools", sensitive.filter((x) => x !== id).length ? sensitive.filter((x) => x !== id) : undefined); onEdit(f); }}>Remove</Button></td>
                  </tr>
                ))}
                {!tools.length && <tr><td colSpan={3} className="st-empty">No tools yet: add the agents it may use.</td></tr>}
              </tbody>
            </table>
            <Field label="Add an agent as a tool">{(id) => <SearchSelect id={id} value="" placeholder="Search your agents"
              options={agents.filter((x) => x.id && x.id !== flow.id && !tools.includes(x.id)).map((x) => ({ value: x.id, label: x.name, description: x.path }))}
              onChange={(v) => { if (v) put("tools", [...tools, v]); }} />}</Field>
          </div></TabPanel>
          <TabPanel value="model"><div className="st-ai-fields">
            <Field label="Model connection">{(id) => <Select id={id} value={str(a.connection)} onChange={(e) => put("connection", e.target.value)}
              options={[{ value: "", label: connections?.default ? "The default connection (" + connections.default + ")" : "No connection yet" },
                ...conns.map((c) => ({ value: c.name, label: c.name + " · " + c.provider + (c.model ? " · " + c.model : "") }))]} />}</Field>
            <div><Button kind="secondary" icon="plus" onClick={onNewConnection}>New connection</Button></div>
            <TextField label="How creative" type="number" value={str(a.temperature)} placeholder="0 = consistent" onCommit={(v) => put("temperature", v === "" ? undefined : Number(v))} />
            <label className="st-check-line"><input type="checkbox" checked={a.mask_pii !== false} onChange={(e) => put("mask_pii", e.target.checked ? undefined : false)} /> Hide personal data from the model (emails, phone and card numbers)</label>
          </div></TabPanel>
          <TabPanel value="outputs"><div className="st-ai-fields">
            <p className="st-muted">Values the agent must return, besides its answer. Leave empty for a plain answer.</p>
            <OutputFields value={fields} onChange={(v) => put("output_fields", Object.keys(v).length ? v : undefined)} />
            <label className="st-check-line"><input type="checkbox" checked={!!a.allow_cancel} onChange={(e) => put("allow_cancel", e.target.checked || undefined)} /> Let the agent cancel when the request should not be done</label>
          </div></TabPanel>
          <TabPanel value="limits"><div className="st-ai-fields st-grid2">
            <TextField label="Most steps (model turns)" type="number" value={str(a.max_steps)} placeholder="8" onCommit={(v) => put("max_steps", v === "" ? undefined : Number(v))} />
            <TextField label="Most tokens" type="number" value={str(a.max_tokens_total)} placeholder="60000" onCommit={(v) => put("max_tokens_total", v === "" ? undefined : Number(v))} />
            <TextField label="Most seconds (0 = no limit)" type="number" value={str(a.max_duration_seconds)} placeholder="0" onCommit={(v) => put("max_duration_seconds", v === "" ? undefined : Number(v))} />
          </div></TabPanel>
        </Tabs>
      </main>

      <aside className="st-ai-try" aria-label="Try it">
        <div className="ui-panel-bar"><strong>Try it</strong>{run && <span className={"st-run-state is-" + run.state}>{busy ? "Working" : run.state === "success" ? "Done" : run.state}</span>}</div>
        <div className="st-ai-fields">
          <Field label="Request">{(id) => <textarea id={id} className="ui-input ui-textarea" rows={4} value={request} onChange={(e) => setRequest(e.target.value)}
            placeholder="Paste an email, a ticket or a question" />}</Field>
          <Button kind="run" icon="play" onClick={() => onTry(request)} disabled={busy || !request.trim()}>Run a test</Button>
          {run?.result && <>
            {run.result.error && <p className="st-error">{run.result.error.message}{run.result.error.suggestedFix ? " " + run.result.error.suggestedFix : ""}</p>}
            {out.answer != null && <><h3 className="st-prop-section">Answer</h3><p className="st-ai-answer">{String(out.answer)}</p></>}
            {out.status != null && <p className="st-muted">Status: {String(out.status)}</p>}
            {trace.length > 0 && <>
              <h3 className="st-prop-section">What it did</h3>
              <ol className="st-run-lines">{trace.map((t, i) => <li key={i}>{traceLine(t)}</li>)}</ol>
            </>}
          </>}
        </div>
      </aside>
    </div>
  );
}

/** One entry of the agent's trace in words: a tool call with its agent, or a model turn. */
function traceLine(t: unknown): string {
  if (!t || typeof t !== "object") return String(t);
  const o = t as Record<string, unknown>;
  const tool = o.tool ?? o.agent ?? o.name;
  if (tool) return "Used " + String(tool) + (o.status ? " · " + String(o.status) : "") + (o.error ? " · " + String(o.error) : "");
  const text = String(o.text ?? o.content ?? o.message ?? JSON.stringify(o));
  return (o.type ? String(o.type) + ": " : "") + (text.length > 160 ? text.slice(0, 159) + "…" : text);
}

function OutputFields({ value, onChange }: { value: Record<string, string>; onChange: (v: Record<string, string>) => void }) {
  const [name, setName] = useState(""), [desc, setDesc] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <div>
      <table className="st-table">
        <thead><tr><th scope="col">Name</th><th scope="col">What it is</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
        <tbody>
          {Object.entries(value).map(([k, d]) => (
            <tr key={k}><td className="ui-mono">{k}</td><td>{d}</td>
              <td className="st-row-actions"><Button kind="plain" aria-label={"Remove " + k} onClick={() => { const v = { ...value }; delete v[k]; onChange(v); }}>Remove</Button></td></tr>
          ))}
          {!Object.keys(value).length && <tr><td colSpan={3} className="st-empty">None: the agent returns a plain answer.</td></tr>}
        </tbody>
      </table>
      <form className="st-row" onSubmit={(e) => {
        e.preventDefault();
        if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(name.trim())) { setError("A name starts with a letter or _ and has only letters, digits and _."); return; }
        onChange({ ...value, [name.trim()]: desc.trim() }); setName(""); setDesc(""); setError(null);
      }}>
        <TextInput aria-label="Output name" mono value={name} placeholder="decision" onChange={(e) => setName(e.target.value)} />
        <TextInput aria-label="What it is" value={desc} placeholder="pay, ask_finance or reject" onChange={(e) => setDesc(e.target.value)} />
        <Button kind="secondary" type="submit">Add</Button>
      </form>
      {error && <p role="alert" className="st-error">{error}</p>}
    </div>
  );
}

