import { useState } from "react";
import { Button, Icon } from "../../components";
import { INBOX_ID, type DocFlowApi, type DocStatus, type Source } from "./api";
import { folderLabel } from "./dialogs";
import { Chip, ConfidenceBar, FilterBar, FilterSelect, Kpi, Loading, SearchBox, StatusChip, matches, useLoad, dfHash, reportError } from "./ui";
import { retryDocument } from "./needsYou";
import { UploadFiles } from "./upload";
import { AccuracyCard } from "./accuracy";
import { SplitDialog } from "./splitDialog";

const SOURCE_LABEL: Record<Source, string> = { upload: "Upload", folder: "Watched folder", email: "Email", bot: "Agent", split: "Split from a batch" };

/** One process: what it is, its numbers, adding documents to it, and every document with full details. */
export function ProcessPage({ api, processId, show }: { api: DocFlowApi; processId: string;
  /** A status to show only (the Inbox's chips open #/process/<id>/<status>). */
  show?: string }) {
  // the newest documents, 200 at a time ("Show more" loads more)
  const [limit, setLimit] = useState(200);
  const [data, reload] = useLoad(() => Promise.all([api.processes(), api.documents(processId, limit)]), [api, processId, limit]);
  // columns of values read: the invoice ones by default; any field the documents have can be shown instead
  const [columns, setColumns] = useState<string[] | null>(null);
  const [splitting, setSplitting] = useState<{ id: string; name: string; pages: number } | null>(null);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"all" | DocStatus>(() => (["review", "failed", "done", "processing", "split"].includes(show ?? "") ? show as DocStatus : "all"));
  const [retrying, setRetrying] = useState<Set<string>>(new Set());
  // Try again: the failed document is read once more as its type (the process's type when it had none)
  const retry = async (d: { id: string; docTypeId?: string | null }) => {
    setRetrying((s) => new Set([...s, d.id]));
    try { await retryDocument(api, processId, d); reload(); } catch (e) { reportError(e); }
    finally { setRetrying((s) => { const n = new Set(s); n.delete(d.id); return n; }); }
  };
  const [source, setSource] = useState<"all" | Source>("all");
  const [readBy, setReadBy] = useState("all");
  const [conf, setConf] = useState("all");
  if (!data) return <Loading />;
  const [processes, docs] = data;
  const p = processes.find((x) => x.id === processId);
  if (!p) return <div className="df-page"><a className="df-back" href={dfHash() + "inbox"}><Icon name="chevronLeft" size={14} />Inbox</a><p>This process does not exist (any more).</p></div>;

  // "Read by": the engines that really read these documents (each step of "Tesseract, then PaddleOCR")
  const engines = [...new Set(docs.filter((d) => d.status !== "processing").flatMap((d) => d.engine.split(", then ")).filter((e) => e && e !== "—"))].sort();
  const engineOk = (e: string) => readBy === "all" || e.split(", then ").includes(readBy);
  const confOk = (c: number | null) => conf === "all" || (c != null && (conf.startsWith("under") ? c < Number(conf.slice(5)) : c >= Number(conf.slice(4))));
  const fieldKeys = [...new Set(docs.flatMap((d) => Object.keys(d.values ?? {})))];
  const invoiceLike = docs.some((d) => d.supplier !== "—" || d.invoice !== "—");
  const shown = columns ?? (invoiceLike ? [] : fieldKeys.slice(0, 3));
  const rows = docs.filter((d) => matches(q, d.name, d.supplier, d.invoice) && (status === "all" || d.status === status)
    && (source === "all" || d.source === source) && engineOk(d.engine) && confOk(d.confidence));
  const count = (s: DocStatus) => docs.filter((d) => d.status === s).length;
  const clear = () => { setQ(""); setStatus("all"); setSource("all"); setReadBy("all"); setConf("all"); };

  return (
    <div className="df-page">
      <a className="df-back" href={dfHash() + "inbox"}><Icon name="chevronLeft" size={14} />Inbox</a>
      <div className="df-page-head">
        <h2 style={{ fontSize: 20 }}>{p.name}</h2>
        <Chip tone={p.state === "live" ? "ok" : "mute"}>{p.state === "live" ? `Live · version ${p.version}` : "Draft"}</Chip>
        <span className="df-grow" />
        <a className="ui-btn ui-btn-secondary" href={dfHash() + "processes/" + encodeURIComponent(p.id)}>Process settings</a>
        {p.sources.includes("upload") && <Button kind="primary" onClick={() => document.getElementById("df-add-files")?.click()}>Add documents</Button>}
      </div>

      {p.sources.includes("upload") && <UploadFiles api={api} process={p} inbox={p.id === INBOX_ID} inputId="df-add-files" onAdded={reload} />}

      <div className="df-card df-pad df-grid df-g4">
        <div><div className="df-small">Document type</div>{p.docType}</div>
        <div><div className="df-small">Folder</div>{p.folder ? folderLabel(p.folder) : "None (open to all)"}</div>
        <div><div className="df-small">Documents come in from</div>{p.sources.map((s) => SOURCE_LABEL[s]).join(", ")}</div>
        <div><div className="df-small">Delivers to</div>{p.deliversTo.join(", ") || "Not set yet"}</div>
      </div>

      <div className="df-grid df-g4">
        <Kpi label="Today" value={p.today} />
        <Kpi label="Straight through" value={p.done} sub={p.straightThrough + "%"} tone="ok" />
        <Kpi label="Need review" value={p.review} />
        <Kpi label="Processing" value={p.processing} />
      </div>

      {p.id !== INBOX_ID && <AccuracyCard api={api} processId={p.id} />}

      <section className="df-card" aria-label={"Documents in " + p.name} style={{ overflow: "hidden" }}>
        <FilterBar onClear={clear} count={`${rows.length} of ${docs.length} documents`} actions={<a className="ui-btn ui-btn-secondary" href={api.exportUrl(p.id, "xlsx")} download={p.name + ".xlsx"} title="Done and approved documents, one row each">Export to Excel</a>}>
          <SearchBox value={q} onChange={setQ} placeholder="File, supplier or invoice number" />
          <FilterSelect label="Status" value={status} onChange={setStatus} options={[{ value: "all", label: `All statuses (${docs.length})` },
            { value: "review", label: `Needs review (${count("review")})` }, { value: "processing", label: `Processing (${count("processing")})` },
            { value: "done", label: `Done (${count("done")})` }, { value: "failed", label: `Failed (${count("failed")})` }, { value: "split", label: `Split (${count("split")})` }]} />
          <FilterSelect label="Came in from" value={source} onChange={setSource}
            options={[{ value: "all", label: "Any way" }, ...(Object.keys(SOURCE_LABEL) as Source[]).map((s) => ({ value: s, label: SOURCE_LABEL[s] }))]} />
          <FilterSelect label="Read by" value={readBy} onChange={setReadBy} options={[{ value: "all", label: "Any engine" }, ...engines.map((e) => ({ value: e, label: e }))]} />
          <FilterSelect label="Confidence" value={conf} onChange={setConf} options={[{ value: "all", label: "Any" },
            ...[95, 90, 85, 70].map((v) => ({ value: "over" + v, label: `${v}% and above` })), ...[95, 90, 85, 70, 50].map((v) => ({ value: "under" + v, label: `Under ${v}%` }))]} />
          {fieldKeys.length > 0 && <details className="df-columns"><summary className="df-small">Columns</summary>
            <div style={{ display: "flex", flexDirection: "column", gap: 4, padding: 8 }}>
              {fieldKeys.map((k) => <label key={k} style={{ display: "flex", gap: 6 }}><input type="checkbox" checked={shown.includes(k)}
                onChange={(e) => setColumns(e.target.checked ? [...shown, k] : shown.filter((x) => x !== k))} />{k}</label>)}
            </div></details>}
        </FilterBar>
        <div className="df-table-wrap">
          <table className="df-table" aria-label="Documents" style={{ minWidth: 1000 }}>
            <thead><tr><th>Document</th>{!columns && invoiceLike && <><th>Supplier · invoice no.</th><th className="df-right">Total</th></>}
              {shown.map((k) => <th key={k}>{k}</th>)}<th>Came in</th><th>Status</th><th>Read by</th><th>Confidence</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.id}>
                  <td><a href={d.status === "review" ? dfHash() + "review/" + d.id : dfHash() + "process/" + p.id}>{d.name}</a><div className="df-small">{d.pages}</div></td>
                  {!columns && invoiceLike && <><td>{d.supplier}<div className="df-small">{d.invoice}</div></td>
                  <td className="df-right df-num" style={{ whiteSpace: "nowrap" }}>{d.total}</td></>}
                  {shown.map((k) => <td key={k} dir="auto">{d.values?.[k] || "—"}</td>)}
                  <td style={{ whiteSpace: "nowrap" }}><span className="df-num">{d.time}</span><div className="df-small">{d.sourceLabel}</div></td>
                  <td><StatusChip status={d.status} />{d.note && <div className="df-small" style={{ marginTop: 3 }}>{d.note}</div>}</td>
                  <td className="df-muted">{d.engine}</td>
                  <td><ConfidenceBar value={d.confidence} /></td>
                  <td className="df-right">
                    {d.status === "review" ? <a className="ui-btn ui-btn-secondary" href={dfHash() + "review/" + d.id}>Review</a>
                      : d.status === "failed" ? <Button disabled={retrying.has(d.id)} onClick={() => retry(d)}>{retrying.has(d.id) ? "Reading…" : "Try again"}</Button>
                      : d.status === "split" ? <Button kind="plain" onClick={() => setSplitting({ id: d.id, name: d.name, pages: parseInt(d.pages, 10) || 1 })}>Change the split</Button>
                      : <a className="ui-btn ui-btn-plain" href={dfHash() + "review/" + encodeURIComponent(d.id)} aria-label={"Open " + d.name}>Open</a>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!rows.length && <div className="df-empty">No document matches these filters. <button type="button" className="df-link" onClick={clear}>Clear filters</button></div>}
          {splitting && <SplitDialog api={api} documentId={splitting.id} name={splitting.name} pages={splitting.pages}
            onClose={() => setSplitting(null)} onDone={() => { setSplitting(null); reload(); }} />}
          {docs.length >= limit && <div className="df-empty"><button type="button" className="df-link" onClick={() => setLimit(limit + 500)}>Show older documents</button></div>}
        </div>
      </section>
    </div>
  );
}
