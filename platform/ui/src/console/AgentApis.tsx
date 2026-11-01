import { useCallback, useEffect, useMemo, useState } from "react";
import type { AgentApi, CrApi, CrUser, Runner, RunnerPool } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { FolderIcon } from "./Repository";

/**
 * Build > Agent APIs, built as AA's API tasks (checked against AA's docs and UiPath's API triggers, 31 Oct 2026): an
 * agent behind its own address and token, for other systems to call like a web service; the call runs the agent and
 * answers with its outputs. Each shows its agent, where it runs (a runner, a pool, or serverless on the Command
 * Center), how long a caller waits, calls and last use. The token is shown once, with an example call.
 * As AA, where an API task is a kind of automation, an agent becomes an Agent API from its menu in Automation too
 * (#/apitasks?new=<agent> opens Create with it chosen). Laid out as Automation (review round 2, 31 Oct 2026): the
 * agents' folders on the left, the Agent APIs in one table with their address to copy.
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const folderOf = (p: string) => p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "";
/** #/apitasks?new=<agent path>: Create opens with that agent chosen (from Automation). */
const linkedNew = () => new URLSearchParams(location.hash.split("?")[1] ?? "").get("new");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

const HELP: HelpTopic = { title: "Agent APIs", about: "An agent behind its own address and token, for other systems to call like a web service: "
  + "the call runs the agent's live version and answers with its outputs.",
  items: [
    { name: "Create Agent API", text: "Choose a name, a live agent, where it runs and how long a caller waits for the result. "
      + "The token is shown once when you create it, with an example call. It needs the Manage Agent APIs permission." },
    { name: "Folders", text: "The folders of the agents behind the Agent APIs, as in Automation. Pick one to see only its Agent APIs." },
    { name: "Runs on", text: "A runner, a runner pool, or One CommandCenter itself (serverless). "
      + "Serverless suits agents that only call web services and work on data; they run for at most 5 minutes." },
    { name: "Waits up to", text: "How long a caller waits for the run to finish. "
      + "When it takes longer, the caller gets 202 with the run id and asks again later." },
    { name: "Copy", text: "Copies the full address to call (with POST), to give to the system that calls it." },
    { name: "State", text: "Show only the Agent APIs that are on, or off. Switch one on or off from its ⋯ menu or when you open it." },
    { name: "Calls, Last call", text: "How many times the Agent API was called and when it was last used." },
    { name: "New token", text: "Makes a new token and shows it once; the old token stops working at once." },
    { name: "Actions (⋯)", text: "Open, copy the address, switch on or off, make a new token or delete. "
      + "Only Open and copying the address are there without the Manage Agent APIs permission." },
  ] };

export function AgentApis({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<AgentApi[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [folder, setFolder] = useState("");
  const [state, setState] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "new"; bot?: string } | { kind: "open"; t: AgentApi } | { kind: "token"; name: string; url: string; token: string }>(() => {
    const bot = linkedNew();
    return bot && user.permissions.includes("apitasks.manage") ? { kind: "new", bot } : null;
  });
  useEffect(() => { if (location.hash.includes("?")) history.replaceState(null, "", "#/apitasks"); }, []);
  const manage = user.permissions.includes("apitasks.manage");
  const load = useCallback(async () => {
    try {
      const [l, d, p] = await Promise.all([api.agentApis(), api.devices().catch(() => []), api.pools().catch(() => [])]);
      setList(l); setRunners(d); setPools(p); setError(null);
    } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const where = (t: AgentApi) => t.serverless ? "One CommandCenter (serverless)" : t.pool_id ? "Pool " + (pools.find((p) => p.id === t.pool_id)?.name ?? t.pool_id) : runners.find((r) => r.id === t.device_id)?.name ?? t.device_id ?? "";
  const shown = useMemo(() => (list ?? []).filter((t) => (!q.trim() || (t.name + " " + t.bot_path).toLowerCase().includes(q.trim().toLowerCase()))
    && (!folder || t.bot_path.startsWith(folder + "/")) && (!state || (state === "on") === t.enabled)), [list, q, folder, state]);
  /** The agents' folders and every folder above them, so the tree has its parents. */
  const folders = useMemo(() => { const out = new Set<string>(); for (const t of list ?? []) { let f = folderOf(t.bot_path); while (f) { out.add(f); f = folderOf(f); } } return [...out].sort(); }, [list]);
  const count = (f: string) => (list ?? []).filter((t) => !f || t.bot_path.startsWith(f + "/")).length;
  const address = (t: AgentApi) => location.origin + t.url;
  const copy = (t: AgentApi) => (navigator.clipboard?.writeText(address(t)) ?? Promise.reject(new Error("Copying is not allowed here"))).then(() => onNotice("Copied the address of " + t.name + "."), () => setError("Copy it from the Agent API instead: " + address(t)));
  const act = (fn: () => Promise<unknown>, done: string) => fn().then(() => { onNotice(done); setDialog(null); load(); }, (e) => setError(message(e)));

  const rowMenu = (t: AgentApi) => [
    { key: "open", label: "Open", onSelect: () => setDialog({ kind: "open", t }) },
    { key: "copy", label: "Copy the address", onSelect: () => { copy(t); } },
    ...(manage ? [{ key: "state", label: t.enabled ? "Switch off" : "Switch on", onSelect: () => { act(() => api.setAgentApiEnabled(t.id, !t.enabled), (t.enabled ? "Switched off " : "Switched on ") + t.name + "."); } },
      { key: "token", label: "New token…", onSelect: () => { api.newAgentApiToken(t.id).then((n) => setDialog({ kind: "token", name: t.name, url: t.url, token: n.token }), (e) => setError(message(e))); } },
      { key: "delete", label: "Delete", separatorBefore: true, onSelect: () => { act(() => api.deleteAgentApi(t.id), "Deleted " + t.name + "."); } }] : []),
  ];

  return (
    <section className="st-page cc-home cc-explorer" aria-labelledby="cc-apis-title">
      <header className="cc-list-head">
        <h1 id="cc-apis-title">Agent APIs</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} Agent API{list.length === 1 ? "" : "s"} · {list.filter((t) => t.enabled).length} on</span>}
        <span className="ov-fill" />
        {manage && <Button kind="primary" onClick={() => setDialog({ kind: "new" })}>Create Agent API</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-explorer-body">
        <nav className="cc-tree-pane" aria-label="Folders">
          <div className="cc-pane-head"><h2>Folders</h2></div>
          <ul className="cc-tree">
            <li><div className={"cc-tree-row" + (folder === "" ? " is-on" : "")} style={{ paddingLeft: 6 }}><span className="cc-tree-chev" />
              <button type="button" className="cc-tree-name" aria-current={folder === "" ? "page" : undefined} onClick={() => setFolder("")}><FolderIcon open />All Agent APIs<span className="cc-tree-n">{count("")}</span></button></div>
              <ul>{folders.map((f) => <li key={f}><div className={"cc-tree-row" + (folder === f ? " is-on" : "")} style={{ paddingLeft: 6 + 16 * f.split("/").length }}><span className="cc-tree-chev" />
                <button type="button" className="cc-tree-name" aria-current={folder === f ? "page" : undefined} onClick={() => setFolder(f)}><FolderIcon />{f.split("/").pop()}<span className="cc-tree-n">{count(f)}</span></button></div></li>)}</ul></li>
          </ul>
          <p className="cc-pane-foot">The folders of the agents behind them, as in Automation.</p>
        </nav>
        <div className="cc-files-pane">
          <div className="cc-pane-head"><span className="cc-crumbs-path"><FolderIcon open /><button type="button" className="cc-link" onClick={() => setFolder("")}>Agent APIs</button>
            {folder.split("/").filter(Boolean).map((c, i, a) => <span key={i}> › {i === a.length - 1 ? <strong>{c}</strong> : <button type="button" className="cc-link" onClick={() => setFolder(a.slice(0, i + 1).join("/"))}>{c}</button>}</span>)}</span></div>
          <div className="cc-list-tools cc-pane-tools">
            <div className="cc-list-filters" role="group" aria-label="Filters">
              <span className="cc-list-search"><TextInput type="search" aria-label="Search Agent APIs" placeholder="Search Agent APIs" value={q} onChange={(e) => setQ(e.target.value)} /></span>
              <span className={"cc-chip" + (state ? " is-on" : "")}><Select aria-label="State" value={state} onChange={(e) => setState(e.target.value)}
                options={[{ value: "", label: "State: Any" }, { value: "on", label: "State: On" }, { value: "off", label: "State: Off" }]} /></span>
              {(q.trim() || state) && <Button kind="plain" onClick={() => { setQ(""); setState(""); }}>Clear filters</Button>}
            </div>
            <div className="cc-list-right" role="toolbar" aria-label="Agent APIs list tools"><RefreshButton onClick={load} /></div>
          </div>
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Agent APIs list">
              <thead><tr><th scope="col">State</th><th scope="col">Agent API</th><th scope="col">Agent</th><th scope="col">Folder</th><th scope="col">Address</th><th scope="col">Runs on</th>
                <th scope="col" className="cc-num">Waits up to</th><th scope="col" className="cc-num">Calls</th><th scope="col">Last call</th><th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {shown.map((t) => (
                  <tr key={t.id} onClick={rowClick(() => setDialog({ kind: "open", t }))}>
                    <td><span className={"cc-pill is-" + (t.enabled ? "active" : "disabled")}><span className="cc-pill-dot" />{t.enabled ? "On" : "Off"}</span></td>
                    <td><span className="cc-aico is-api" aria-hidden="true">API</span><strong>{t.name}</strong></td>
                    <td>{agentName(t.bot_path)}</td><td>{folderOf(t.bot_path) || <span className="cc-none">(top)</span>}</td>
                    <td className="cc-api-addr"><span className="ui-mono" title={"POST " + address(t)}>POST {t.url}</span>
                      <button type="button" className="ui-btn ui-btn-plain cc-api-copy" aria-label={"Copy the address of " + t.name} onClick={() => copy(t)}>Copy</button></td>
                    <td>{where(t)}</td><td className="cc-num">{t.timeout_seconds} s</td><td className="cc-num">{t.calls}</td><td>{t.last_used ? when(t.last_used) : <span className="cc-none">Never</span>}</td>
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={rowMenu(t)} /></td>
                  </tr>
                ))}
                {list && !shown.length && <tr className="is-static"><td colSpan={10} className="cc-none">{list.length ? "No Agent APIs match." : <span className="cc-api-empty">
                  <strong>No Agent APIs yet.</strong> An Agent API puts one live agent behind its own address and token, so another system can run it like a web service and get its outputs back.
                  Make one with Create Agent API here, or from an agent's ⋯ menu in <a href="#/automation">Automation</a>.</span>}</td></tr>}
                {!list && !error && <tr className="is-static"><td colSpan={10} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}{q.trim() || state || folder ? " matching" : ""}</span></div>}
        </div>
      </div>
      {dialog?.kind === "new" && <NewApi api={api} runners={runners} pools={pools} preset={dialog.bot} onClose={() => setDialog(null)}
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

function NewApi({ api, runners, pools, preset, onClose, onCreated }: { api: CrApi; runners: Runner[]; pools: RunnerPool[]; preset?: string; onClose: () => void;
  onCreated: (r: { name: string; url: string; token: string }) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [v, setV] = useState({ name: preset ? agentName(preset) : "", bot: preset ?? "", where: "runner", device: runners[0]?.id ?? "", pool: "", timeout: "60" });
  useEffect(() => { if (!v.device && runners[0]) setV((o) => ({ ...o, device: runners[0].id })); }, [runners]);   // eslint-disable-line react-hooks/exhaustive-deps
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
        {([["runner", "A runner"], ["pool", "A runner pool"], ["serverless", "One CommandCenter (serverless)"]] as const).map(([k, l]) =>
          <label key={k} className="st-check-line"><input type="radio" name="apiwhere" checked={v.where === k} onChange={() => setV({ ...v, where: k })} /> {l}</label>)}
      </div>
      {v.where === "runner" && <Field label="Runner">{(id) => <Select id={id} value={v.device} onChange={set("device")} options={runners.map((r) => ({ value: r.id, label: r.name + " (" + r.status + ")" }))} />}</Field>}
      {v.where === "pool" && <Field label="Runner pool">{(id) => <Select id={id} value={v.pool} onChange={set("pool")} options={[{ value: "", label: "Choose a pool" }, ...pools.map((p) => ({ value: p.id, label: p.name }))]} />}</Field>}
      {v.where === "serverless" && <p className="st-muted">Serverless suits agents that only call web services and work on data (REST, SOAP, cloud apps, Excel, PDF, JSON). They run for at most 5 minutes, four at a time, with files only in their own workspace.</p>}
      <Field label="Wait for the result up to (seconds, 1-300)">{(id) => <TextInput id={id} type="number" min={1} max={300} value={v.timeout} onChange={set("timeout")} />}</Field>
      <p className="cc-list-hint">Calls run as you, at high priority. When the agent takes longer than the wait, the caller gets 202 with the run id and asks again.</p>
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
      <p className="cc-list-hint">The answer is status, run_id, outputs and error: 200 when the run finished, 202 while it still runs (then GET {url.replace(/\/run$/, "/runs/")}&lt;run_id&gt; with the same token).</p>
    </Dialog>
  );
}
