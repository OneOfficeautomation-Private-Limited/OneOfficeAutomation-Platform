import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, EnvironmentLock } from "../api/cr";
import { Button, HelpButton, TextInput, type HelpTopic, PageError } from "../components";
import { RefreshButton, when } from "./ListParts";
import { PageIntro } from "../shell/PageIntro";

/**
 * Manage > Environment locks (gap G33, after Blue Prism's environment locks): the named locks runs hold now, so two
 * runners never use the same single-user system together. Agents take and give back locks with the Environment locks
 * toolkit; a lock goes with its run when the run ends. Administrators (Release environment locks) can give one back by
 * hand, asked first and audited.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

const HELP: HelpTopic = { title: "Environment locks", about: "Named locks that one agent run holds at a time, so two runners never use the same "
  + "single-user system together, such as one SAP login or one shared mailbox. Agents take them with the Environment locks toolkit (Acquire lock).",
  items: [
    { name: "Lock", text: "The lock's name, as the agent gave it. Names are compared without case." },
    { name: "Held by", text: "The run holding the lock, the agent it runs and the person it runs for." },
    { name: "Runner", text: "The runner machine the run is on." },
    { name: "Since", text: "When the run took the lock." },
    { name: "Lease until", text: "After this time another run may take the lock. Marked when it has passed. The agent renews it by taking the lock again." },
    { name: "Release", text: "Give the lock back by hand, for example when a run is stuck. The run that held it is not stopped. "
      + "It needs the Release environment locks permission (administrators), and it is written to the audit trail." },
    { name: "When locks are given back", text: "By the agent's Release lock step, when the agent that took it ends, and when its run ends however it ends "
      + "(succeeded, failed, stopped, or its runner stopped reporting)." },
  ] };

export function EnvironmentLocks({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<EnvironmentLock[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [ask, setAsk] = useState<EnvironmentLock | null>(null);   // a lock to release, asked first
  const manage = user.permissions.includes("locks.manage");
  const load = useCallback(async () => {
    try { setList(await api.environmentLocks()); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((l) => !q.trim()
    || [l.name, l.run_id, l.bot_path, l.held_by, l.device_name, l.comment].join(" ").toLowerCase().includes(q.trim().toLowerCase())), [list, q]);
  const release = (l: EnvironmentLock) => api.releaseEnvironmentLock(l.name)
    .then(() => { onNotice("Released the lock " + l.name + "."); setAsk(null); load(); }, (e) => { setAsk(null); setError(message(e)); });
  const cols = manage ? 6 : 5;

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-locks-title">
      <header className="cc-list-head">
        <h1 id="cc-locks-title">Environment locks</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:locks" />
        {list && <span className="cc-list-count">{list.length} held</span>}
        <span className="ov-fill" />
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search locks" placeholder="Search locks" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {ask && <div className="cc-list-bulk" role="alert"><span>Release the lock {ask.name}? Run {ask.run_id} keeps running, and another run may take the lock at once.</span>
          <Button kind="danger" onClick={() => release(ask)}>Release</Button><Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Environment locks list">
            <thead><tr><th scope="col">Lock</th><th scope="col">Held by</th><th scope="col">Runner</th><th scope="col">Since</th><th scope="col">Lease until</th>
              {manage && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {shown.map((l) => (
                <tr key={l.name} className="is-static">
                  <td className="cc-clip" title={l.comment || undefined}>{l.name}</td>
                  <td className="cc-clip" title={l.bot_path || undefined}>Run {l.run_id}{l.held_by ? " for " + l.held_by : ""}</td>
                  <td>{l.device_name ?? ""}</td>
                  <td>{when(l.acquired)}</td>
                  <td>{when(l.expires)}{l.expired ? " (passed)" : ""}</td>
                  {manage && <td className="cc-list-menu"><Button kind="secondary" aria-label={"Release " + l.name} onClick={() => setAsk(l)}>Release</Button></td>}
                </tr>
              ))}
              {list && !shown.length && <tr className="is-static"><td colSpan={cols} className="cc-none">{list.length ? "No locks match." : "No locks are held now."}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={cols} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}</span></div>}
      </div>
    </section>
  );
}
