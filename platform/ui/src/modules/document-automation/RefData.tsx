import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Field, SidePanel, TextInput, usePersistent } from "../../components";
import type { DocFlowApi, RefPreview, RefRow, RefTable } from "./api";
import { Empty, FilterBar, FilterSelect, Loading, SearchBox, matches, reportError, useLoad, dfHash } from "./ui";

/** A list older than this many days is worth a second look (supplier lists change). */
const STALE_DAYS = 90;
/** At most this many rows are loaded so the search covers a whole table. */
const SEARCH_ALL_UP_TO = 20000;
/** An example list to start from: what a lookup check needs is a column of the values to look up. */
const EXAMPLE_CSV = "Supplier name,VAT number,Country\r\nACME Trading Co.,300000000000003,SA\r\nGulf Office Supplies,310482766100003,AE\r\nNordic Paper AB,SE556677889901,SE\r\n";
const exampleHref = "data:text/csv;charset=utf-8," + encodeURIComponent("\ufeff" + EXAMPLE_CSV);

const cell = (v: unknown) => (v === null || v === undefined ? "" : String(v));

/** A table as CSV, for Download (quotes where needed, so Excel opens it as it was uploaded). */
function toCsv(columns: string[], rows: RefRow[]) {
  const q = (v: unknown) => { const t = cell(v); return /[",\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t; };
  return [columns.map(q).join(","), ...rows.map((r) => columns.map((c) => q(r[c])).join(","))].join("\r\n");
}

/** Reference data: master data that lookup checks use, e.g. "is this supplier known?". Uploaded as CSV or Excel. */
export function RefData({ api }: { api: DocFlowApi }) {
  const [tables, reload] = useLoad(() => api.refTables(), [api]);
  // "Used by" opens the process's checks (step 5): processes by name
  const [processes] = useLoad(() => api.processes().catch(() => []), [api]);
  const [everything, setEverything] = useState<RefRow[] | null>(null);   // every row, once a search needs them
  const loadingAll = useRef<string | null>(null);                          // the table whose every row is being loaded
  const [chosen, setChosen] = useState("");
  const [rows, setRows] = useState<RefRow[] | null>(null);
  const [listQ, setListQ] = useState("");
  const [listHidden, setListHidden] = usePersistent("panel.docs.refdata.hidden", false);
  const [q, setQ] = useState("");
  const [column, setColumn] = useState("all");
  const [facets, setFacets] = useState<Record<string, string>>({});
  const [uploading, setUploading] = useState<RefTable | "new" | null>(null);
  const [deleting, setDeleting] = useState<RefTable | null>(null);
  const t = tables?.find((x) => x.id === chosen) ?? tables?.[0];

  useEffect(() => {
    if (!t) return;
    let live = true;
    setRows(null); setQ(""); setColumn("all"); setFacets({}); setEverything(null); loadingAll.current = null;
    api.refTableRows(t.id).then((r) => { if (live) setRows(r); }, reportError);
    return () => { live = false; };
  }, [api, t?.id, t?.rows, t?.updated]);

  if (!tables) return <Loading />;
  const head = (
    <div className="df-page-head">
      <div><h2>Reference data</h2><p>Lists your checks look values up in, for example your approved suppliers or open purchase orders. Upload a CSV or Excel file.</p></div>
      <span className="df-grow" /><Button kind="primary" icon="plus" onClick={() => setUploading("new")}>Upload a table</Button>
    </div>
  );
  const dialogs = <>
    {uploading && <UploadDialog key={uploading === "new" ? "new" : uploading.id} api={api} existing={tables ?? []} replacing={uploading === "new" ? null : uploading}
      onClose={() => setUploading(null)} onDone={(id) => { setUploading(null); setChosen(id); reload(); }} />}
    <Dialog open={!!deleting} onClose={() => setDeleting(null)} title={`Delete ${deleting?.name ?? ""}?`} width={480}
      footer={<><Button onClick={() => setDeleting(null)}>Cancel</Button>
        <Button kind="danger" onClick={() => { const d = deleting!; setDeleting(null); api.deleteRefTable(d.id).then(() => { setChosen(""); reload(); }, reportError); }}>Delete</Button></>}>
      {deleting?.usedBy?.length
        ? <p className="df-text-warn" style={{ margin: 0 }}><b>Used by {deleting.usedBy.join(", ")}.</b> Their lookup checks will flag every document until you upload the table again or change the check.</p>
        : <p style={{ margin: 0 }}>No check uses this table.</p>}
    </Dialog>
  </>;
  if (!tables.length || !t) return <div className="df-page">{head}<Empty>No tables yet. Upload a list of suppliers, purchase orders or cost centres for checks to look values up in.
    {" "}<a href={exampleHref} download="Example suppliers.csv">Download an example file</a> to start from. Then add a Reference data lookup check in
    {" "}<a href={dfHash() + "processes"}>a process's step 5</a>.</Empty>{dialogs}</div>;

  const list = tables.filter((x) => matches(listQ, x.name, x.meta));
  const loaded = everything ?? rows ?? [];
  const partial = !everything && (rows?.length ?? 0) < t.rows;
  // a search covers the whole table: the rest of the rows are loaded once a search starts
  if ((q || Object.values(facets).some((v) => v && v !== "all")) && partial && rows && t.rows <= SEARCH_ALL_UP_TO)
    if (loadingAll.current !== t.id) { loadingAll.current = t.id; void api.refTableRows(t.id, t.rows).then(setEverything, reportError); }
  const ageDays = t.updatedAt ? Math.floor((Date.now() - new Date(t.updatedAt).getTime()) / 86400000) : null;
  const processOf = (name: string) => processes?.find((p) => p.name === name);
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
        {/* fix list 27 Nov, item 4: folds to a strip and back (remembered) */}
        <SidePanel as="nav" title="Tables" side="left" collapsed={listHidden} onToggle={() => setListHidden(!listHidden)} className="df-card df-list">
          <SearchBox value={listQ} onChange={setListQ} placeholder="Search tables" />
          {list.map((x) => (
            <button key={x.id} type="button" className="df-list-item" aria-current={x.id === t.id} onClick={() => setChosen(x.id)}>
              <span style={{ display: "flex", gap: 8 }}><b>{x.name}</b><span className="df-grow" /><span className="df-small df-num">{x.rows.toLocaleString()}</span></span>
              <span className="df-small">{x.meta}</span>
            </button>
          ))}
        </SidePanel>

        <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
          <section className="df-card df-pad" aria-label={t.name}>
            <div className="df-head-row">
              <b style={{ fontSize: 16 }}>{t.name}</b>
              <span className="df-muted">{t.rows.toLocaleString()} rows · {t.columns.length} columns</span>
              <span className="df-grow" />
              <Button onClick={download}>Download</Button>
              <Button onClick={() => setUploading(t)}>Replace with a new file</Button>
              <Button kind="plain" onClick={() => setDeleting(t)}>Delete</Button>
            </div>
            <div className="df-grid df-g3" style={{ marginTop: 12 }}>
              <div><div className="df-small">Updated</div>{t.updated ?? "—"}</div>
              <div><div className="df-small">Columns</div>{t.columns.join(", ") || "—"}</div>
              <div><div className="df-small">Used by</div>{t.usedBy?.length ? t.usedBy.map((u, i) => { const p = processOf(u); return <span key={u}>{i ? ", " : ""}
                {p ? <a href={dfHash() + "processes/" + encodeURIComponent(p.id) + "/checks"}>{u}</a> : u}</span>; })
                : <>No check yet: add a lookup check in <a href={dfHash() + "processes"}>a process's step 5</a></>}</div>
            </div>
            {ageDays != null && ageDays > STALE_DAYS && <p className="df-small df-text-warn" role="note" style={{ margin: "10px 0 0" }}>
              Updated {ageDays} days ago: is it still current? An old list flags good documents. Replace it with a new file, or let an agent upload a fresh copy on a schedule.</p>}
          </section>

          <section className="df-card" aria-label="Rows" style={{ overflow: "hidden" }}>
            <FilterBar onClear={() => { setQ(""); setColumn("all"); setFacets({}); }}
              count={rows ? `${shown.length} of ${t.rows.toLocaleString()} rows${loaded.length < t.rows ? ` (first ${loaded.length} shown; a search looks through them all)` : ""}` : undefined}>
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

function UploadDialog({ api, replacing, existing = [], onClose, onDone }: { api: DocFlowApi; replacing: RefTable | null; existing?: RefTable[]; onClose: () => void; onDone: (id: string) => void }) {
  const picker = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState(replacing?.name ?? "");
  const [busy, setBusy] = useState(false);
  const upload = async () => {
    if (!file) return;
    setBusy(true);
    const title = name.trim() || file.name.replace(/\.[^.]+$/, "");
    try { onDone((await api.uploadRefTable(file, keepBoth && clash ? freeName(title) : title, replacing?.id, headerRow)).id); } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  // another table has this name: uploading replaces it, unless both are kept (the new one gets "(2)")
  const clash = !replacing && existing.some((t) => t.name.trim().toLowerCase() === name.trim().toLowerCase());
  const [keepBoth, setKeepBoth] = useState(false);
  const [headerRow, setHeaderRow] = useState(1);
  const [preview, setPreview] = useState<RefPreview | null | "none">(null);   // "none": it could not be read here
  useEffect(() => {
    if (!file) { setPreview(null); return; }
    let live = true;
    api.previewRefTable(file, headerRow).then((p) => { if (live) setPreview(p); }, () => { if (live) setPreview("none"); });
    return () => { live = false; };
  }, [api, file, headerRow]);
  const freeName = (n: string) => { let k = 2; while (existing.some((t) => t.name.toLowerCase() === `${n} (${k})`.toLowerCase())) k++; return `${n} (${k})`; };
  return (
    <Dialog open onClose={onClose} title={replacing ? `Replace ${replacing.name}` : "Upload a table"} width={520}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!file || busy} onClick={upload}>{busy ? "Uploading…" : "Upload"}</Button></>}>
      <div className="df-files">
        <div>
          <input ref={picker} type="file" hidden accept=".csv,.tsv,.txt,.xlsx,.xlsm,.ods,.json" aria-label="Choose a file"
            onChange={(e) => { const f = e.target.files?.[0] ?? null; setFile(f); if (f && !name) setName(f.name.replace(/\.[^.]+$/, "")); }} />
          {file ? <b>{file.name}</b> : <span className="df-muted">No file yet</span>}<span className="df-grow" />
          <button type="button" className="df-link" onClick={() => picker.current?.click()}>{file ? "Choose another" : "+ Choose a file"}</button>
        </div>
      </div>
      <div className="df-grid df-g2">
        <p className="df-small" style={{ margin: 0, alignSelf: "center" }}>CSV, TSV or text, Excel, OpenDocument (.ods) or JSON (a list of rows).</p>
        <Field label="Column names are in row" hint="0: the file has none; the columns are named col1, col2...">
          {(id) => <TextInput id={id} inputMode="numeric" value={String(headerRow)} onChange={(e) => setHeaderRow(Math.max(0, Number(e.target.value) || 0))} />}
        </Field>
      </div>
      {preview && preview !== "none" && <div aria-label="What the file gives" style={{ display: "flex", flexDirection: "column", gap: 4 }}>
        <span className="df-small"><b>{preview.rowCount.toLocaleString()} rows</b> · columns: {preview.columns.join(", ") || "none found"}. The first rows:</span>
        <div className="df-table-wrap"><table className="df-table cc-no-filter" aria-label="First rows">
          <thead><tr>{preview.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
          <tbody>{preview.rows.map((r, i) => <tr key={i}>{preview.columns.map((c) => <td key={c}>{cell(r[c])}</td>)}</tr>)}</tbody>
        </table></div>
        <span className="df-small">Not the column names you expected? Change "Column names are in row".</span>
      </div>}
      <Field label="Table name" hint={replacing ? "This table is replaced; you may rename it." : clash ? "A table with this name exists." : undefined}>
        {(id) => <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      {clash && <label style={{ display: "flex", gap: 8, alignItems: "center" }}><input type="checkbox" checked={keepBoth} onChange={(e) => setKeepBoth(e.target.checked)} />
        Keep both: save this one as {freeName(name.trim())} instead of replacing the table</label>}
    </Dialog>
  );
}
