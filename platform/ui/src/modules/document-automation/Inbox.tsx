import { useState } from "react";
import { Button } from "../../components";
import { INBOX_ID, type DocFlowApi } from "./api";
import { NewProcessDialog } from "./dialogs";
import { Kpi, Loading, useLoad, dfHash, straightPct, straightText } from "./ui";
import { NeedsYou, type NeedFilter } from "./needsYou";

/**
 * Inbox: one row per process (minimal); a row opens the process page with every document. Documents are added on a
 * process's own page (or in its builder step 2); documents of mixed types go to the One Inbox, which recognises them.
 */
export function Inbox({ api }: { api: DocFlowApi }) {
  const [processes, reload] = useLoad(() => api.processes(), [api]);
  const [creating, setCreating] = useState(false);
  const [need, setNeed] = useState<NeedFilter>("all");
  const [oldest, setOldest] = useState<string | null>(null);   // the oldest document waiting for review
  if (!processes) return <Loading />;

  const total = (k: "review" | "processing" | "done" | "failed" | "today") => processes.reduce((n, p) => n + p[k], 0);
  // straight through as on every screen: documents that needed no person, of every document read so far
  const straight = processes.reduce((n, p) => n + (p.straight ?? 0), 0);
  const processed = processes.reduce((n, p) => n + (p.processed ?? 0), 0);
  const waiting = processes.find((p) => p.review > 0);

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Inbox</h2><p>What needs someone now, across every process: documents waiting for review, and ones that failed.</p></div>
        <span className="df-grow" />
        <Button icon="plus" onClick={() => setCreating(true)}>New process</Button>
      </div>

      <div className="df-grid df-g4">
        <Kpi label="Received today" value={total("today")} />
        <Kpi label="Straight through" value={straightPct(straight, processed) + "%"} sub={straightText(straight, processed)} tone="ok" />
        <Kpi label="Waiting for review" value={total("review")} sub={waiting ? <>
          {oldest ? <a href={dfHash() + "review/" + encodeURIComponent(oldest)}>Review the oldest</a> : <a href={dfHash() + "process/" + encodeURIComponent(waiting.id)}>Open {waiting.name}</a>}
          {" · "}<button type="button" className="df-link" onClick={() => setNeed("review")}>Show them</button></> : undefined} />
        <Kpi label="Failed" value={total("failed")} tone={total("failed") ? "bad" : undefined}
          sub={total("failed") ? <button type="button" className="df-link" onClick={() => setNeed("failed")}>Show them, and why</button> : undefined} />
      </div>

      <NeedsYou api={api} processes={processes} filter={need} onFilter={setNeed} onChanged={reload} onOldest={setOldest} />

      <p className="df-small" style={{ margin: 0 }}>Every process, with its numbers and how it is set up: <a href={dfHash() + "overview/table"}>Overview, Table</a>.</p>
      {processes.some((p) => p.id === INBOX_ID) && <p className="df-small" style={{ margin: 0 }}>Documents of different kinds?{" "}
        <a href={dfHash() + "process/" + INBOX_ID}>Add them in {processes.find((p) => p.id === INBOX_ID)!.name}</a>: it recognises each one and sends it to its process.</p>}

      <NewProcessDialog api={api} open={creating} onClose={() => setCreating(false)}
        onCreated={(id) => { setCreating(false); reload(); location.hash = dfHash() + "processes/" + encodeURIComponent(id) + "/intake"; }} />
    </div>
  );
}
