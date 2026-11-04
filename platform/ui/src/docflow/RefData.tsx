import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Field, TextInput } from "../components";
import type { DocFlowApi, RefRow, RefTable } from "./api";
import { Empty, FilterBar, FilterSelect, Loading, SearchBox, matches, reportError, useLoad } from "./ui";

const cell = (v: unknown) => (v === null || v === undefined ? "" : String(v));

/** A table as CSV, for Download (quotes where needed, so Excel opens it as it was uploaded). */
function toCsv(columns: string[], rows: RefRow[]) {
  const q = (v: unknown) => { const t = cell(v); return /[",\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t; };
  return [columns.map(q).join(","), ...rows.map((r) => columns.map((c) => q(r[c])).join(","))].join("\r\n");
}

/** Reference data: master data that lookup checks use, e.g. "is this supplier known?". Uploaded as CSV or Excel. */
export function RefData({ api }: { api: DocFlowApi }) {
  const [tables, reload] = useLoad(() => api.refTables(), [api]);
  const [chosen, setChosen] = useState("");
  const [rows, setRows] = useState<RefRow[] | null>(null);
  const [listQ, setListQ] = useState("");
  const [q, setQ] = useState("");
  const [column, setColumn] = useState("all");
  const [facets, setFacets] = useState<Record<string, string>>({});
  const [uploading, setUploading] = useState<RefTable | "new" | null>(null);
  const [deleting, setDeleting] = useState<RefTable | null>(null);
  const t = tables?.find((x) => x.id === chosen) ?? tables?.[0];

  useEffect(() => {
    if (!t) return;
    let live = true;
    setRows(null); setQ(""); setColumn("all"); setFacets({});
    api.refTableRows(t.id).then((r) => { if (live) setRows(r); }, reportError);
    return () => { live = false; };
  }, [api, t?.id, t?.rows, t?.updated]);

  if (!tables) return <Loading />;
  const head = (
    <div className="df-page-head">
      <div><h2>Reference data</h2><p>Master data that checks look values up in, e.g. "is this supplier known?". Upload a CSV or Excel file.</p></div>
      <span className="df-grow" /><Button kind="primary" icon="plus" onClick={() => setUploading("new")}>Upload a table</Button>
    </div>
  );
  const dialogs = <>
    {uploading && <UploadDialog key={uploading === "new" ? "new" : uploading.id} api={api} replacing={uploading === "new" ? null : uploading}
      onClose={() => setUploading(null)} onDone={(id) => { setUploading(null); setChosen(id); reload(); }} />}
    <Dialog open={!!deleting} onClose={() => setDeleting(null)} title={`Delete ${deleting?.name ?? ""}?`} width={480}
      footer={<><Button onClick={() => setDeleting(null)}>Cancel</Button>
        <Button kind="danger" onClick={() => { const d = deleting!; setDeleting(null); api.deleteRefTable(d.id).then(() => { setChosen(""); reload(); }, reportError); }}>Delete</Button></>}>
      {deleting?.usedBy?.length
        ? <p className="df-text-warn" style={{ margin: 0 }}><b>Used by {deleting.usedBy.join(", ")}.</b> Their lookup checks will flag every document until you upload the table again or change the check.</p>
        : <p style={{ margin: 0 }}>No check uses this table.</p>}
    </Dialog>
  </>;
  if (!tables.length || !t) return <div className="df-page">{head}<Empty>No tables yet. Upload a list of suppliers, purchase orders or cost centres for checks to look values up in.</Empty>{dialogs}</div>;

  const list = tables.filter((x) => matches(listQ, x.name, x.meta));
  const loaded = rows ?? [];
  // columns with only a few repeating values (country, status…) get their own dropdowns
  const facetColumns = t.columns.filter((c) => { const n = new Set(loaded.map((r) => cell(r[c]))).size; return n > 1 && n <= 8 && n < loaded.length; }).slice(0, 2);
  const shown = loaded.filter((r) => (column === "all" ? matches(q, ...t.columns.map((c) => cell(r[c]))) : matches(q, cell(r[column])))
    && facetColumns.every((c) => !facets[c] || facets[c] === "all" || cell(r[c]) === facets[c]));
  const download = async () => {
    try {
      const all = await api.refTableRows(t.id, Math.max(t.rows, 1));
      const url = URL.createObjectURL(new Blob(["﻿" + toCsv(t.columns, all)], { type: "text/csv;charset=utf-8" }));
      const a = document.createElement("a");
      a.href = url; a.download = t.name + ".csv"; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { reportError(e); }
  };

  return (
    <div className="df-page">
      {head}
      <div className="df-split">
        <nav className="df-card df-list" aria-label="Tables">
          <SearchBox value={listQ} onChange={setListQ} placeholder="Search tables" />
          {list.map((x) => (
            <button key={x.id} type="button" className="df-list-item" aria-current={x.id === t.id} onClick={() => setChosen(x.id)}>
              <span style={{ display: "flex", gap: 8 }}><b>{x.name}</b><span className="df-grow" /><span className="df-small df-num">{x.rows.toLocaleString("en")}</span></span>
              <span className="df-small">{x.meta}</span>
            </button>
          ))}
        </nav>

        <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
          <section className="df-card df-pad" aria-label={t.name}>
            <div className="df-head-row">
              <b style={{ fontSize: 16 }}>{t.name}</b>
              <span className="df-muted">{t.rows.toLocaleString("en")} rows · {t.columns.length} columns</span>
              <span className="df-grow" />
              <Button onClick={download}>Download</Button>
              <Button onClick={() => setUploading(t)}>Replace with a new file</Button>
              <Button kind="plain" onClick={() => setDeleting(t)}>Delete</Button>
            </div>
            <div className="df-grid df-g3" style={{ marginTop: 12 }}>
              <div><div className="df-small">Updated</div>{t.updated ?? "—"}</div>
              <div><div className="df-small">Columns</div>{t.columns.join(", ") || "—"}</div>
              <div><div className="df-small">Used by</div>{t.usedBy?.length ? t.usedBy.join(", ") : "No check yet: add a lookup check in Processes"}</div>
            </div>
          </section>

          <section className="df-card" aria-label="Rows" style={{ overflow: "hidden" }}>
            <FilterBar onClear={() => { setQ(""); setColumn("all"); setFacets({}); }}
              count={rows ? `${shown.length} of ${t.rows.toLocaleString("en")} rows${loaded.length < t.rows ? ` (first ${loaded.length} shown)` : ""}` : undefined}>
              <SearchBox value={q} onChange={setQ} placeholder="Search the rows" />
              <FilterSelect label="Search in" value={column} onChange={setColumn} options={[{ value: "all", label: "All columns" }, ...t.columns.map((c) => ({ value: c, label: c }))]} />
              {facetColumns.map((c) => <FilterSelect key={c} label={c} value={facets[c] ?? "all"} onChange={(v) => setFacets({ ...facets, [c]: v })}
                options={[{ value: "all", label: "All" }, ...[...new Set(loaded.map((r) => cell(r[c])))].sort().map((v) => ({ value: v, label: v || "(empty)" }))]} />)}
            </FilterBar>
            <div className="df-table-wrap">
              {!rows ? <Loading /> : (
                <table className="df-table" style={{ minWidth: Math.max(480, t.columns.length * 140) }}>
                  <thead><tr>{t.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
                  <tbody>{shown.map((r, i) => <tr key={i}>{t.columns.map((c) => <td key={c}>{cell(r[c])}</td>)}</tr>)}</tbody>
                </table>
              )}
              {rows && !loaded.length && <div className="df-empty">This table has no rows.</div>}
              {rows && loaded.length > 0 && !shown.length && <div className="df-empty">No row matches these filters.</div>}
            </div>
          </section>
        </div>
      </div>
      {dialogs}
    </div>
  );
}

function UploadDialog({ api, replacing, onClose, onDone }: { api: DocFlowApi; replacing: RefTable | null; onClose: () => void; onDone: (id: string) => void }) {
  const picker = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState(replacing?.name ?? "");
  const [busy, setBusy] = useState(false);
  const upload = async () => {
    if (!file) return;
    setBusy(true);
    try { onDone((await api.uploadRefTable(file, name.trim() || file.name.replace(/\.[^.]+$/, ""))).id); } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title={replacing ? `Replace ${replacing.name}` : "Upload a table"} width={520}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!file || busy} onClick={upload}>{busy ? "Uploading…" : "Upload"}</Button></>}>
      <div className="df-files">
        <div>
          <input ref={picker} type="file" hidden accept=".csv,.xlsx,.xls" aria-label="Choose a file"
            onChange={(e) => { const f = e.target.files?.[0] ?? null; setFile(f); if (f && !name) setName(f.name.replace(/\.[^.]+$/, "")); }} />
          {file ? <b>{file.name}</b> : <span className="df-muted">No file yet</span>}<span className="df-grow" />
          <button type="button" className="df-link" onClick={() => picker.current?.click()}>{file ? "Choose another" : "+ Choose a file"}</button>
        </div>
      </div>
      <p className="df-small" style={{ margin: 0 }}>CSV or Excel. The first row holds the column names.</p>
      <Field label="Table name" hint={replacing ? "Keep the name to replace this table." : "A table with the same name is replaced."}>
        {(id) => <TextInput id={id} value={name} disabled={!!replacing} onChange={(e) => setName(e.target.value)} />}
      </Field>
    </Dialog>
  );
}
