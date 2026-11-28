import { useEffect, useState } from "react";
import { Button } from "../../components";
import type { DocFlowApi, DocumentRow, ProcessSummary } from "./api";
import { Chip, dfHash, reportError } from "./ui";

/** Reads a failed document once more as its type (the process's first type when it had none). */
export async function retryDocument(api: DocFlowApi, processId: string, d: { id: string; docTypeId?: string | null }) {
  const type = d.docTypeId || (await api.processDesign(processId)).docTypeIds[0];
  if (!type) throw new Error("The process has no document type to read it as: choose one in step 3");
  await api.changeDocType(d.id, type);
}

/** What the Needs you list shows. */
export type NeedFilter = "all" | "review" | "failed";
/** How many documents the list shows at once ("Show all" shows the rest). */
const SHOWN = 20;
/** At most this many documents are read again at once by "Try again (n)". */
const RETRY_AT_ONCE = 50;

/**
 * Inbox (walkthrough 28 Nov 2026): the documents that need someone, across every process, newest first in each:
 * waiting for review (Review) and failed (Try again), with why. Failed documents are grouped by their reason, so all
 * of one reason can be read again once it is fixed.
 */
export function NeedsYou({ api, processes, filter, onFilter, onChanged, onOldest }: {
  api: DocFlowApi; processes: ProcessSummary[]; filter: NeedFilter; onFilter: (f: NeedFilter) => void; onChanged: () => void;
  /** The oldest document waiting for review, once known (the Waiting for review figure opens it). */
  onOldest: (id: string | null) => void;
}) {
  const [docs, setDocs] = useState<(DocumentRow & { processName: string })[] | null>(null);
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [all, setAll] = useState(false);
  const [n, setN] = useState(0);
  const wanted = processes.filter((p) => p.review > 0 || p.failed > 0);
  useEffect(() => {
    let live = true;
    Promise.all(wanted.map((p) => api.documents(p.id, 100).then((ds) => ds.filter((d) => d.status === "review" || d.status === "failed")
      .map((d) => ({ ...d, processName: p.name }))).catch(() => [])))
      .then((lists) => {
        if (!live) return;
        const flat = lists.flat();
        setDocs(flat);
        const waiting = flat.filter((d) => d.status === "review");
        onOldest(waiting.length ? waiting[waiting.length - 1].id : null);
      });
    return () => { live = false; };
  }, [api, wanted.map((p) => p.id + p.review + p.failed).join(), n]);
  const retry = async (list: (DocumentRow & { processName: string })[]) => {
    setBusy((s) => new Set([...s, ...list.map((d) => d.id)]));
    for (const d of list.slice(0, RETRY_AT_ONCE)) {
      try { await retryDocument(api, d.processId, d); } catch (e) { reportError(e); break; }
    }
    setBusy(new Set());
    setN((x) => x + 1);
    onChanged();
  };
  if (!wanted.length) return null;
  const rows = (docs ?? []).filter((d) => filter === "all" || d.status === filter);
  const failed = (docs ?? []).filter((d) => d.status === "failed");
  const reasons = [...failed.reduce((m, d) => m.set(d.note || "Could not be read", [...(m.get(d.note || "Could not be read") ?? []), d]), new Map<string, typeof failed>())]
    .sort((a, b) => b[1].length - a[1].length);
  return (
    <section className="df-sec" aria-label="Needs you">
      <div className="df-sec-head"><b>Needs you</b><span className="df-small">documents waiting for review or that failed, across every process</span>
        <span className="df-grow" />
        <span className="df-seg" role="group" aria-label="Show">
          {(["all", "review", "failed"] as NeedFilter[]).map((f) => <button key={f} type="button" aria-pressed={filter === f} onClick={() => onFilter(f)}>
            {f === "all" ? "All" : f === "review" ? "Waiting for review" : "Failed"}</button>)}
        </span></div>
      <div className="df-sec-body" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {docs === null ? <span className="df-small">Looking…</span> : <>
          {filter !== "review" && reasons.length > 0 && <div aria-label="Why documents failed" style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {reasons.slice(0, 5).map(([why, list]) => (
              <div key={why} style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
                <span style={{ flex: 1, minWidth: 0 }}><b>{list.length}</b> · {why}</span>
                <Button kind="plain" disabled={list.some((d) => busy.has(d.id))} onClick={() => retry(list)}>
                  {list.some((d) => busy.has(d.id)) ? "Reading…" : `Try again (${Math.min(list.length, RETRY_AT_ONCE)})`}</Button>
              </div>))}
            <span className="df-small">Fix the cause first (for example set up the engine), then try them again.</span>
          </div>}
          {rows.length ? <table className="df-table cc-no-filter" aria-label="Documents that need you">
            <thead><tr><th>Document</th><th>Process</th><th>Why</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{rows.slice(0, all ? rows.length : SHOWN).map((d) => (
              <tr key={d.id}>
                <td>{d.name}<div className="df-small">{d.time}</div></td>
                <td><a href={dfHash() + "process/" + encodeURIComponent(d.processId)}>{d.processName}</a></td>
                <td>{d.status === "review" ? <><Chip tone="warn">Waiting for review</Chip>{d.note && <div className="df-small">{d.note}</div>}</>
                  : <><Chip tone="bad">Failed</Chip><div className="df-small">{d.note || "Could not be read"}</div></>}</td>
                <td className="df-right" style={{ whiteSpace: "nowrap" }}>{d.status === "review"
                  ? <a className="ui-btn ui-btn-secondary" href={dfHash() + "review/" + encodeURIComponent(d.id)}>Review</a>
                  : <Button disabled={busy.has(d.id)} onClick={() => retry([d])}>{busy.has(d.id) ? "Reading…" : "Try again"}</Button>}</td>
              </tr>))}</tbody>
          </table> : <span className="df-small">Nothing {filter === "review" ? "waiting for review" : filter === "failed" ? "failed" : "needs you"} right now.</span>}
          {rows.length > SHOWN && <button type="button" className="df-link" style={{ alignSelf: "flex-start" }} onClick={() => setAll(!all)}>
            {all ? "Show fewer" : `Show all ${rows.length}`}</button>}
        </>}
      </div>
    </section>
  );
}
