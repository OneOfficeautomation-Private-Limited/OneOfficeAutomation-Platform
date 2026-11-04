import { useState } from "react";
import { Button } from "../components";
import type { DocFlowApi, Source } from "./api";
import { AddDocumentsDialog, NewProcessDialog, folderLabel } from "./dialogs";
import { Chip, FilterBar, FilterSelect, Kpi, Loading, SearchBox, matches, useLoad } from "./ui";

/** Inbox: one row per process (minimal); a row opens the process page with every document. */
export function Inbox({ api }: { api: DocFlowApi }) {
  const [processes, reload] = useLoad(() => api.processes(), [api]);
  const [adding, setAdding] = useState<string | null>(null);   // "" = from the toolbar, else a process id
  const [dropped, setDropped] = useState<File[]>([]);           // files dropped on the box go straight into the dialog
  const [creating, setCreating] = useState(false);
  const [over, setOver] = useState(false);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"all" | "review" | "processing" | "done" | "failed">("all");
  const [folder, setFolder] = useState("all");
  const [source, setSource] = useState<"all" | Source>("all");
  const [period, setPeriod] = useState("today");
  if (!processes) return <Loading />;

  const total = (k: "review" | "processing" | "done" | "failed" | "today") => processes.reduce((n, p) => n + p[k], 0);
  // done documents that needed nobody: each process's straight-through share of its done documents
  const straight = processes.reduce((n, p) => n + Math.round(p.done * p.straightThrough / 100), 0);
  const waiting = processes.find((p) => p.review > 0);
  const rows = processes.filter((p) => matches(q, p.name, p.folder, p.lastDocument) && (folder === "all" || p.folder.startsWith(folder))
    && (source === "all" || p.sources.includes(source)) && (status === "all" || p[status] > 0));
  const clear = () => { setQ(""); setStatus("all"); setFolder("all"); setSource("all"); setPeriod("today"); };

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Inbox</h2><p>Today's documents, by process. Open a process to see every document.</p></div>
        <span className="df-grow" />
        <Button icon="plus" onClick={() => setCreating(true)}>New process</Button>
        <Button kind="primary" onClick={() => setAdding("")}>Add documents</Button>
      </div>

      <button type="button" className={`df-drop${over ? " is-over" : ""}`} onClick={() => setAdding("")}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)} onDrop={(e) => { e.preventDefault(); setOver(false); setDropped(Array.from(e.dataTransfer.files)); setAdding(""); }}>
        <span><b>Drop files here</b> or click to choose. You pick the process and folder next.</span>
      </button>

      <div className="df-grid df-g4">
        <Kpi label="Received today" value={total("today")} />
        <Kpi label="Straight through" value={straight} sub={Math.round(straight / Math.max(1, total("done")) * 100) + "%"} tone="ok" />
        <Kpi label="Waiting for review" value={total("review")} sub={waiting ? <a href={"#/process/" + encodeURIComponent(waiting.id)}>Open {waiting.name}</a> : undefined} />
        <Kpi label="Failed" value={total("failed")} tone={total("failed") ? "bad" : undefined} />
      </div>

      <section className="df-card" aria-label="Processes" style={{ overflow: "hidden" }}>
        <FilterBar onClear={clear} count={`${rows.length} of ${processes.length} processes`}>
          <SearchBox value={q} onChange={setQ} placeholder="Search processes or documents" />
          <FilterSelect label="Status" value={status} onChange={setStatus} options={[
            { value: "all", label: `All statuses (${total("today")})` }, { value: "review", label: `Needs review (${total("review")})` },
            { value: "processing", label: `Processing (${total("processing")})` }, { value: "done", label: `Done (${total("done")})` },
            { value: "failed", label: `Failed (${total("failed")})` }]} />
          <FilterSelect label="Folder" value={folder} onChange={setFolder} options={[{ value: "all", label: "All folders" },
            ...[...new Set(processes.map((p) => p.folder.split(" / ")[0]))].map((f) => ({ value: f, label: f }))]} />
          <FilterSelect label="Comes in from" value={source} onChange={setSource} options={[{ value: "all", label: "Any way" },
            { value: "upload", label: "Upload" }, { value: "folder", label: "Watched folder" }, { value: "email", label: "Email" }, { value: "bot", label: "Bot" }]} />
          <FilterSelect label="Period" value={period} onChange={setPeriod}
            options={[{ value: "today", label: "Today" }, { value: "7d", label: "Last 7 days" }, { value: "30d", label: "Last 30 days" }]} />
        </FilterBar>
        <div className="df-table-wrap">
          <table className="df-table" style={{ minWidth: 900 }}>
            <thead><tr><th>Process</th><th className="df-right">Documents</th><th>Status</th><th>Last document</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id}>
                  <td><a href={"#/process/" + p.id} style={{ fontSize: 14 }}>{p.name}</a><div className="df-small">{folderLabel(p.folder)}</div></td>
                  <td className="df-right df-num" style={{ fontSize: 15, fontWeight: 600 }}>{p.today}</td>
                  <td><span style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                    {p.review > 0 && <Chip tone="warn">{p.review} need{p.review === 1 ? "s" : ""} review</Chip>}
                    {p.processing > 0 && <Chip tone="info">{p.processing} processing</Chip>}
                    {p.failed > 0 && <Chip tone="bad">{p.failed} failed</Chip>}
                    {p.done > 0 && <Chip tone="ok">{p.done} done</Chip>}
                    {p.today === 0 && <Chip tone="mute">No documents yet</Chip>}
                  </span></td>
                  <td className="df-muted" style={{ whiteSpace: "nowrap" }}>{p.lastTime ? `${p.lastTime} · ${p.lastDocument}` : "—"}</td>
                  <td className="df-right" style={{ whiteSpace: "nowrap" }}>
                    <Button onClick={() => setAdding(p.id)} aria-label={"Add documents to " + p.name}>Add documents</Button>{" "}
                    <a className="ui-btn ui-btn-plain" href={"#/process/" + p.id} aria-label={"Open " + p.name}>Open</a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!rows.length && <div className="df-empty">No process matches these filters. <button type="button" className="df-link" onClick={clear}>Clear filters</button></div>}
        </div>
      </section>

      {adding !== null && (
        <AddDocumentsDialog api={api} open onClose={() => setAdding(null)} processes={processes} initialProcess={adding || undefined} initialFiles={dropped}
          onNewProcess={() => { setAdding(null); setDropped([]); setCreating(true); }}
          onAdded={(id) => { setAdding(null); setDropped([]); location.hash = "#/process/" + id; }} />
      )}
      <NewProcessDialog api={api} open={creating} onClose={() => setCreating(false)}
        onCreated={(id) => { setCreating(false); reload(); location.hash = "#/processes/" + id.split("/").map(encodeURIComponent).join("/"); }} />
    </div>
  );
}
