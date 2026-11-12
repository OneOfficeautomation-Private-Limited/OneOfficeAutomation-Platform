import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, RunWait } from "../api/cr";
import { Button, TextInput, PageError } from "../components";
import { RefreshButton, rowClick, when } from "./ListParts";

/**
 * Agent Activity > Suspended (gap G36, long-running agents): runs that ended for now to wait for a time, a Task inbox
 * task, a work item, another run or a call to their callback URL, with what each waits for and until when. A new run
 * carries on from the waiting step when the wait is over. People who may stop runs (runs.stop) resume one at once or
 * stop it (which cancels its wait), asked first; both are audited by One CommandCenter.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const agentName = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");

/** What a wait is for, in words. */
export function waitsFor(w: RunWait) {
  switch (w.kind) {
    case "time": return "A time: " + when(w.resume_at ?? w.ref ?? "");
    case "task": return "Task " + (w.ref ?? "") + " to be done";
    case "queue_item": return "Work item " + (w.ref ?? "") + " to end";
    case "job": return "Run " + (w.ref ?? "") + " to end";
    case "callback": return "A call to its callback URL";
    default: return w.kind;
  }
}

export function SuspendedRuns({ api, user, onNotice, onOpen }: { api: CrApi; user: CrUser; onNotice: (t: string) => void; onOpen: (runId: string) => void }) {
  const [list, setList] = useState<RunWait[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [ask, setAsk] = useState<{ wait: RunWait; what: "resume" | "stop" } | null>(null);
  const control = user.permissions.includes("runs.stop");
  const load = useCallback(async () => {
    try { setList(await api.runWaits()); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((w) => !q.trim()
    || [w.bot_path, w.run_id, w.created_by, w.run_as, w.kind, w.ref, w.device_name].join(" ").toLowerCase().includes(q.trim().toLowerCase())), [list, q]);
  const act = ({ wait, what }: { wait: RunWait; what: "resume" | "stop" }) => (what === "resume"
    ? api.resumeRunWait(wait.id).then((r) => onNotice("Resumed " + agentName(wait.bot_path) + " as run " + r.run_id + "."))
    : api.cancelRunWait(wait.id).then(() => onNotice("Stopped the suspended run of " + agentName(wait.bot_path) + "; its wait is cancelled.")))
    .then(() => { setAsk(null); load(); }, (e) => { setAsk(null); setError(message(e)); });
  const cols = control ? 7 : 6;

  return (
    <>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search suspended runs" placeholder="Search agent, run id or user" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {ask && <div className="cc-list-bulk" role="alert">
          <span>{ask.what === "resume" ? "Resume " + agentName(ask.wait.bot_path) + " now? A new run carries on from the waiting step as if the wait were over."
            : "Stop the suspended run of " + agentName(ask.wait.bot_path) + "? Its wait is cancelled and it will not carry on."}</span>
          <Button kind={ask.what === "stop" ? "danger" : "primary"} onClick={() => act(ask)}>{ask.what === "resume" ? "Resume now" : "Stop"}</Button>
          <Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Suspended runs">
            <thead><tr><th scope="col">Agent</th><th scope="col">Waits for</th><th scope="col">Since</th><th scope="col">Times out</th>
              <th scope="col">Started by</th><th scope="col">Runner</th>{control && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {shown.map((w) => (
                <tr key={w.id} onClick={rowClick(() => onOpen(w.run_id))}>
                  <td className="cc-clip" title={w.bot_path + " · run " + w.run_id + (w.step_id ? " · at step " + w.step_id : "")}>
                    <strong>{agentName(w.bot_path)}</strong>{w.version ? <span className="cc-none"> v{w.version}</span> : null}</td>
                  <td className="cc-clip">{waitsFor(w)}</td>
                  <td>{when(w.suspended ?? w.created)}</td>
                  <td>{w.timeout_at ? when(w.timeout_at) : <span className="cc-none">Never</span>}</td>
                  <td>{(w.created_by ?? "") + (w.run_as && w.run_as !== w.created_by ? " as " + w.run_as : "")}</td>
                  <td>{w.device_name ?? ""}</td>
                  {control && <td className="cc-list-menu">
                    <Button kind="secondary" aria-label={"Resume " + agentName(w.bot_path) + " now"} onClick={() => setAsk({ wait: w, what: "resume" })}>Resume now</Button>{" "}
                    <Button kind="plain" aria-label={"Stop the suspended run of " + agentName(w.bot_path)} onClick={() => setAsk({ wait: w, what: "stop" })}>Stop</Button></td>}
                </tr>
              ))}
              {list && !shown.length && <tr className="is-static"><td colSpan={cols} className="cc-none">{list.length ? "No suspended runs match." : "No run is suspended now."}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={cols} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}</span></div>}
      </div>
    </>
  );
}
