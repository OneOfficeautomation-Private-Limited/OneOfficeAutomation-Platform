import { useState } from "react";
import { Button, Icon } from "../../components";
import { INBOX_ID, type DocFlowApi, type DocStatus, type Source } from "./api";
import { folderLabel } from "./dialogs";
import { Chip, ConfidenceBar, FilterBar, FilterSelect, Kpi, Loading, SearchBox, StatusChip, matches, useLoad } from "./ui";
import { UploadFiles } from "./upload";

const SOURCE_LABEL: Record<Source, string> = { upload: "Upload", folder: "Watched folder", email: "Email", bot: "Bot", split: "Split from a batch" };

/** One process: what it is, its numbers, adding documents to it, and every document with full details. */
export function ProcessPage({ api, processId }: { api: DocFlowApi; processId: string }) {
  const [data, reload] = useLoad(() => Promise.all([api.processes(), api.documents(processId)]), [api, processId]);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"all" | DocStatus>("all");
  const [source, setSource] = useState<"all" | Source>("all");
  const [readBy, setReadBy] = useState<"all" | "pdf" | "tesseract" | "paddle" | "cloud">("all");
  const [conf, setConf] = useState<"all" | "high" | "under85" | "under70">("all");
  if (!data) return <Loading />;
  const [processes, docs] = data;
  const p = processes.find((x) => x.id === processId);
  if (!p) return <div className="df-page"><a className="df-back" href="#/inbox"><Icon name="chevronLeft" size={14} />Inbox</a><p>This process does not exist (any more).</p></div>;

  const engineOk = (e: string) => readBy === "all" || (readBy === "pdf" ? e.startsWith("PDF") : readBy === "paddle" ? e.includes("PaddleOCR")
    : readBy === "tesseract" ? e.startsWith("Tesseract") : /cloud|azure|google|aws/i.test(e));
  const confOk = (c: number | null) => conf === "all" || (c != null && (conf === "high" ? c >= 85 : conf === "under85" ? c < 85 : c < 70));
  const rows = docs.filter((d) => matches(q, d.name, d.supplier, d.invoice) && (status === "all" || d.status === status)
    && (source === "all" || d.source === source) && engineOk(d.engine) && confOk(d.confidence));
  const count = (s: DocStatus) => docs.filter((d) => d.status === s).length;
  const clear = () => { setQ(""); setStatus("all"); setSource("all"); setReadBy("all"); setConf("all"); };

  return (
    <div className="df-page">
      <a className="df-back" href="#/inbox"><Icon name="chevronLeft" size={14} />Inbox</a>
      <div className="df-page-head">
        <h2 style={{ fontSize: 20 }}>{p.name}</h2>
        <Chip tone={p.state === "live" ? "ok" : "mute"}>{p.state === "live" ? `Live · version ${p.version}` : "Draft"}</Chip>
        <span className="df-grow" />
        <a className="ui-btn ui-btn-secondary" href={"#/processes/" + encodeURIComponent(p.id)}>Process settings</a>
        <Button kind="primary" onClick={() => document.getElementById("df-add-files")?.click()}>Add documents</Button>
      </div>

      <UploadFiles api={api} process={p} inbox={p.id === INBOX_ID} inputId="df-add-files" onAdded={reload} />

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

      <section className="df-card" aria-label={"Documents in " + p.name} style={{ overflow: "hidden" }}>
        <FilterBar onClear={clear} count={`${rows.length} of ${docs.length} documents`} actions={<a className="ui-btn ui-btn-secondary" href={api.exportUrl(p.id, "xlsx")} download={p.name + ".xlsx"} title="Done and approved documents, one row each">Export to Excel</a>}>
          <SearchBox value={q} onChange={setQ} placeholder="File, supplier or invoice number" />
          <FilterSelect label="Status" value={status} onChange={setStatus} options={[{ value: "all", label: `All statuses (${docs.length})` },
            { value: "review", label: `Needs review (${count("review")})` }, { value: "processing", label: `Processing (${count("processing")})` },
            { value: "done", label: `Done (${count("done")})` }, { value: "failed", label: `Failed (${count("failed")})` }, { value: "split", label: `Split (${count("split")})` }]} />
          <FilterSelect label="Came in from" value={source} onChange={setSource}
            options={[{ value: "all", label: "Any way" }, ...(Object.keys(SOURCE_LABEL) as Source[]).map((s) => ({ value: s, label: SOURCE_LABEL[s] }))]} />
          <FilterSelect label="Read by" value={readBy} onChange={setReadBy} options={[{ value: "all", label: "Any engine" }, { value: "pdf", label: "PDF text layer" },
            { value: "tesseract", label: "Tesseract" }, { value: "paddle", label: "Retried with PaddleOCR" }, { value: "cloud", label: "Cloud engine" }]} />
          <FilterSelect label="Confidence" value={conf} onChange={setConf} options={[{ value: "all", label: "Any" }, { value: "high", label: "85% and above" },
            { value: "under85", label: "Under 85%" }, { value: "under70", label: "Under 70%" }]} />
        </FilterBar>
        <div className="df-table-wrap">
          <table className="df-table" style={{ minWidth: 1000 }}>
            <thead><tr><th>Document</th><th>Supplier · invoice no.</th><th className="df-right">Total</th><th>Came in</th><th>Status</th><th>Read by</th><th>Confidence</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.id}>
                  <td><a href={d.status === "review" ? "#/review/" + d.id : "#/process/" + p.id}>{d.name}</a><div className="df-small">{d.pages}</div></td>
                  <td>{d.supplier}<div className="df-small">{d.invoice}</div></td>
                  <td className="df-right df-num" style={{ whiteSpace: "nowrap" }}>{d.total}</td>
                  <td style={{ whiteSpace: "nowrap" }}><span className="df-num">{d.time}</span><div className="df-small">{d.sourceLabel}</div></td>
                  <td><StatusChip status={d.status} />{d.note && <div className="df-small" style={{ marginTop: 3 }}>{d.note}</div>}</td>
                  <td className="df-muted">{d.engine}</td>
                  <td><ConfidenceBar value={d.confidence} /></td>
                  <td className="df-right">
                    {d.status === "review" ? <a className="ui-btn ui-btn-secondary" href={"#/review/" + d.id}>Review</a>
                      : d.status === "failed" ? <Button>Retry</Button> : <Button kind="plain">Open</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!rows.length && <div className="df-empty">No document matches these filters. <button type="button" className="df-link" onClick={clear}>Clear filters</button></div>}
        </div>
      </section>
    </div>
  );
}
