import { useCallback, useEffect, useMemo, useState } from "react";
import type { AgentApi, CrApi, CrUser, Runner, RunnerPool } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Build > Agent APIs, built as AA's API tasks (checked against AA's docs and UiPath's API triggers, 31 Oct 2026): an
 * agent behind its own address and token, for other systems to call like a web service; the call runs the agent and
 * answers with its outputs. Each shows its agent, where it runs (a runner, a pool, or serverless on the Command
 * Center), how long a caller waits, calls and last use. The token is shown once, with an example call.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function AgentApis({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<AgentApi[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "new" } | { kind: "open"; t: AgentApi } | { kind: "token"; name: string; url: string; token: string }>(null);
  const load = useCallback(async () => {
    try {
      const [l, d, p] = await Promise.all([api.agentApis(), api.devices().catch(() => []), api.pools().catch(() => [])]);
      setList(l); setRunners(d); setPools(p); setError(null);
    } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const where = (t: AgentApi) => t.serverless ? "The Command Center (serverless)" : t.pool_id ? "Pool " + (pools.find((p) => p.id === t.pool_id)?.name ?? t.pool_id) : runners.find((r) => r.id === t.device_id)?.name ?? t.device_id ?? "";
  const shown = useMemo(() => (list ?? []).filter((t) => !q.trim() || (t.name + " " + t.bot_path).toLowerCase().includes(q.trim().toLowerCase())), [list, q]);
  const act = (fn: () => Promise<unknown>, done: string) => fn().then(() => { onNotice(done); setDialog(null); load(); }, (e) => setError(message(e)));

  return (
    <section className="st-page cc-home" aria-labelledby="cc-apis-title">
      <header className="st-page-head">
        <div><h1 id="cc-apis-title">Agent APIs</h1><p>An agent behind its own address and token, for other systems to call like a web service: the call runs the agent and answers with its outputs.</p></div>
        {user.permissions.includes("apitasks.manage") && <Button kind="primary" onClick={() => setDialog({ kind: "new" })}>Create Agent API</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search Agent APIs" placeholder="Search Agent APIs" value={q} onChange={(e) => setQ(e.target.value)} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Agent APIs list">
          <thead><tr><th scope="col">State</th><th scope="col">Agent API</th><th scope="col">Agent</th><th scope="col">Runs on</th><th scope="col">Waits up to</th><th scope="col">Calls</th><th scope="col">Last call</th></tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={() => setDialog({ kind: "open", t })}>
                <td className={"cc-status is-" + (t.enabled ? "success" : "disabled")}>{t.enabled ? "On" : "Off"}</td>
                <td>{t.name}<div className="st-muted ui-mono">POST {location.origin + t.url}</div></td>
                <td>{agentName(t.bot_path)}</td><td>{where(t)}</td><td>{t.timeout_seconds} s</td><td>{t.calls}</td><td>{t.last_used ? when(t.last_used) : "Never"}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={7} className="st-muted">{list.length ? "No Agent APIs match." : "No Agent APIs yet."}</td></tr>}
            {!list && !error && <tr><td colSpan={7} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog?.kind === "new" && <NewApi api={api} runners={runners} pools={pools} onClose={() => setDialog(null)}
        onCreated={(r) => { onNotice("Created the Agent API " + r.name + "."); load(); setDialog({ kind: "token", ...r }); }} />}
      {dialog?.kind === "token" && <TokenShown name={dialog.name} url={dialog.url} token={dialog.token} onClose={() => setDialog(null)} />}
      {dialog?.kind === "open" && (
        <Dialog open title={"Agent API " + dialog.t.name} width={640} onClose={() => setDialog(null)} footer={user.permissions.includes("apitasks.manage") ? <>
          <Button kind="danger" onClick={() => act(() => api.deleteAgentApi(dialog.t.id), "Deleted " + dialog.t.name + ".")}>Delete</Button>
          <Button kind="secondary" onClick={() => act(() => api.setAgentApiEnabled(dialog.t.id, !dialog.t.enabled), (dialog.t.enabled ? "Switched off " : "Switched on ") + dialog.t.name + ".")}>{dialog.t.enabled ? "Switch off" : "Switch on"}</Button>
          <span className="st-bar-fill" />
          <Button kind="primary" onClick={() => api.newAgentApiToken(dialog.t.id).then((n) => setDialog({ kind: "token", name: dialog.t.name, url: dialog.t.url, token: n.token }), (e) => setError(message(e)))}>New token</Button></> : null}>
          <dl className="cc-facts">
            <dt>Address</dt><dd className="ui-mono">POST {location.origin + dialog.t.url}</dd>
            <dt>Agent</dt><dd>{dialog.t.bot_path} (its live version)</dd>
            <dt>Runs on</dt><dd>{where(dialog.t)}</dd>
            <dt>Waits up to</dt><dd>{dialog.t.timeout_seconds} seconds, then answers 202 with the run id</dd>
            <dt>Calls</dt><dd>{dialog.t.calls}{dialog.t.last_used ? ", last " + when(dialog.t.last_used) : ""}</dd>
            <dt>Made</dt><dd>{when(dialog.t.created)}{dialog.t.created_by ? " by " + dialog.t.created_by : ""}; calls run as them, at high priority</dd>
          </dl>
          <p className="st-muted">A new token stops the current one at once.</p>
        </Dialog>
      )}
    </section>
  );
}

function NewApi({ api, runners, pools, onClose, onCreated }: { api: CrApi; runners: Runner[]; pools: RunnerPool[]; onClose: () => void; onCreated: (r: { name: string; url: string; token: string }) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [v, setV] = useState({ name: "", bot: "", where: "runner", device: runners[0]?.id ?? "", pool: "", timeout: "60" });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); setV((o) => ({ ...o, bot: o.bot || b[0]?.path || "" })); }, () => {}); }, [api]);
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const create = () => api.createAgentApi(v.where === "serverless" ? { name: v.name.trim(), bot_path: v.bot, serverless: true, timeout_seconds: Number(v.timeout) }
    : { name: v.name.trim(), bot_path: v.bot, device_id: v.where === "runner" ? v.device : null, pool_id: v.where === "pool" ? v.pool : null, timeout_seconds: Number(v.timeout) })
    .then((r) => onCreated({ name: r.name ?? v.name.trim(), url: r.url, token: r.token }), (e) => setError(message(e)));
  return (
    <Dialog open title="Create Agent API" width={620} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim() || !v.bot} onClick={create}>Create Agent API</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} placeholder="Invoice lookup" onChange={set("name")} />}</Field>
        <Field label="Agent (its live version)">{(id) => <Select id={id} value={v.bot} onChange={set("bot")} options={agents.length ? agents.map((a) => ({ value: a, label: agentName(a) })) : [{ value: "", label: "No agent is live yet" }]} />}</Field>
      </div>
      <div role="radiogroup" aria-label="Runs on" className="cc-inline">
        {([["runner", "A runner"], ["pool", "A runner pool"], ["serverless", "The Command Center (serverless)"]] as const).map(([k, l]) =>
          <label key={k} className="st-check-line"><input type="radio" name="apiwhere" checked={v.where === k} onChange={() => setV({ ...v, where: k })} /> {l}</label>)}
      </div>
      {v.where === "runner" && <Field label="Runner">{(id) => <Select id={id} value={v.device} onChange={set("device")} options={runners.map((r) => ({ value: r.id, label: r.name + " (" + r.status + ")" }))} />}</Field>}
      {v.where === "pool" && <Field label="Runner pool">{(id) => <Select id={id} value={v.pool} onChange={set("pool")} options={[{ value: "", label: "Choose a pool" }, ...pools.map((p) => ({ value: p.id, label: p.name }))]} />}</Field>}
      {v.where === "serverless" && <p className="st-muted">Serverless suits agents that only call web services and work on data (REST, SOAP, cloud apps, Excel, PDF, JSON). They run for at most 5 minutes, four at a time, with files only in their own workspace.</p>}
      <Field label="Wait for the result up to (seconds, 1-300)">{(id) => <TextInput id={id} type="number" min={1} max={300} value={v.timeout} onChange={set("timeout")} />}</Field>
      <p className="st-muted">Calls run as you, at high priority. When the agent takes longer than the wait, the caller gets 202 with the run id and asks again.</p>
    </Dialog>
  );
}

/** The address and token, shown once, with an example call. */
function TokenShown({ name, url, token, onClose }: { name: string; url: string; token: string; onClose: () => void }) {
  const full = location.origin + url;
  return (
    <Dialog open title={"Agent API " + name + ": its token"} width={680} onClose={onClose} footer={<Button kind="primary" onClick={onClose}>Done</Button>}>
      <p>Give the address and token to the system that calls it. The token is shown only now; make a new one if it is lost.</p>
      <code className="cc-key" aria-label="Address">{full}</code>
      <code className="cc-key" aria-label="Token">{token}</code>
      <h3 className="cc-log-title">Example</h3>
      <pre className="cc-codes">{'curl -X POST "' + full + '" -H "X-OOA-Task-Token: <token>" -H "Content-Type: application/json" -d \'{"inputs": {"text": "hello"}}\''}</pre>
      <p className="st-muted">The answer is status, run_id, outputs and error: 200 when the run finished, 202 while it still runs (then GET {url.replace(/\/run$/, "/runs/")}&lt;run_id&gt; with the same token).</p>
    </Dialog>
  );
}
