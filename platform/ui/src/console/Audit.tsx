import { useCallback, useEffect, useState } from "react";
import type { AuditRecord, CrApi } from "../api/cr";
import { Button, Dialog, Select, TextInput, usePersistent } from "../components";

/**
 * Administration > Audit Trail, built as AA's Audit log (checked against AA's docs, UiPath's Audit page and Blue
 * Prism's audit log viewer, 31 Oct 2026): a time range (last 24 hours, 7 or 30 days, or chosen dates), filters by area
 * (AA's item types), action, person, result and object; a column chooser; a record's details with the changed values
 * and "more about this object" (UiPath's related records); CSV export of the search; and the hash-chain check, which
 * shows whether history was edited (Blue Prism-style tamper evidence).
 */
type Col = { key: string; label: string; cell: (a: AuditRecord) => string };

/** Areas by the first part of the action (user.create is Users). */
const AREAS: [string, string][] = [["auth.", "Sign-ins"], ["user.", "Users"], ["role.", "Roles"], ["team.", "Teams"], ["locker.", "Vault folders"], ["vault.", "Credential vault"],
  ["credential.", "Credentials"], ["repo.", "Agent Repository"], ["run.", "Runs"], ["device.", "Runner Machines"], ["pool.", "Runner pools"], ["schedule.", "Schedules"],
  ["trigger.", "Triggers"], ["queue.", "Work queues"], ["settings.", "Settings"], ["license.", "Licenses"], ["apitask.", "Agent APIs"], ["apikey.", "API keys"], ["audit.", "Audit Trail"]];
const RESULTS: [string, string][] = [["ok", "Succeeded"], ["failed", "Failed"], ["denied", "Refused"], ["not_found", "Not found"]];
const RANGES: [string, string][] = [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["all", "Any time"], ["custom", "Chosen dates"]];
const resultName = (r: string) => RESULTS.find(([k]) => k === r)?.[1] ?? r;
const when = (t: string) => new Date(t).toLocaleString();
function detailsText(d?: string | null) {
  if (!d) return "";
  try { const o = JSON.parse(d); return Object.entries(o).map(([k, v]) => k + ": " + (typeof v === "object" ? JSON.stringify(v) : String(v))).join("; "); } catch { return d; }
}
const COLS: Col[] = [
  { key: "seq", label: "#", cell: (a) => String(a.seq) },
  { key: "ts", label: "Time", cell: (a) => when(a.ts) },
  { key: "actor", label: "Who", cell: (a) => a.actor },
  { key: "action", label: "Action", cell: (a) => a.action },
  { key: "object", label: "Object", cell: (a) => [a.object_type, a.object_id].filter(Boolean).join(" ") },
  { key: "result", label: "Result", cell: (a) => resultName(a.result) },
  { key: "ip", label: "From address", cell: (a) => a.ip ?? "" },
  { key: "details", label: "Details", cell: (a) => detailsText(a.details) },
];
const SIZE = 100;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const dayStart = (v: string, next: boolean) => { const d = new Date(v + "T00:00:00"); if (next) d.setDate(d.getDate() + 1); return d.toISOString(); };

export function Audit({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [hidden, setHidden] = usePersistent<string[]>("audit.hidden", ["ip"]);
  const [range, setRange] = usePersistent<string>("audit.range", "1");
  const [f, setF] = useState<Record<string, string>>({});
  const [offset, setOffset] = useState(0);
  const [data, setData] = useState<{ records: AuditRecord[]; total: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [check, setCheck] = useState<{ valid: boolean; text: string } | null>(null);
  const [dialog, setDialog] = useState<null | { kind: "columns" } | { kind: "record"; record: AuditRecord }>(null);

  /** The query: filters, then the time range (Any time adds nothing). */
  const params = useCallback(() => {
    const p: Record<string, string> = {};
    for (const k of ["q", "actor", "object_type", "object_id", "result", "sort"]) if (f[k]?.trim()) p[k] = f[k].trim();
    const action = f.action?.trim() || f.area || "";
    if (action) p.action = action;
    if (range === "custom") { if (f.from) p.from = dayStart(f.from, false); if (f.to) p.to = dayStart(f.to, true); }
    else if (range !== "all") p.from = new Date(Date.now() - Number(range) * 86_400_000).toISOString();
    return p;
  }, [f, range]);
  const load = useCallback(async () => {
    try { setData(await api.audit({ ...params(), limit: String(SIZE), offset: String(offset) })); setError(null); } catch (e) { setError(message(e)); }
  }, [api, params, offset]);
  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t); }, [load]);   // typing waits a moment

  const setFilter = (k: string, v: string) => { setF({ ...f, [k]: v }); setOffset(0); };
  const cols = COLS.filter((c) => !hidden.includes(c.key));
  const exportCsv = async () => {
    try {
      const text = await api.auditCsv(params());
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
      a.download = "audit-trail.csv";
      document.body.appendChild(a); a.click(); a.remove();
      onNotice("Exported the audit records that match (the export is recorded too).");
    } catch (e) { setError(message(e)); }
  };
  const verify = async () => {
    try {
      const v = await api.auditVerify();
      setCheck({ valid: v.valid, text: v.valid ? "History intact: " + v.records + " records checked." : "History was changed: the chain breaks at record " + v.broken_at + "." });
    } catch (e) { setError(message(e)); }
  };
  const records = data?.records ?? [];

  return (
    <section className="st-page cc-home" aria-labelledby="cc-audit-title">
      <header className="st-page-head">
        <div><h1 id="cc-audit-title">Audit Trail</h1><p>Every sign-in, change, run and credential use. Records are chained by hashes, so any edit to history shows.</p></div>
        <span className="cc-inline"><Button kind="secondary" onClick={verify}>Check history</Button></span>
      </header>
      {check && <p className={check.valid ? "st-notice" : "st-alert"} role="status">{check.text}</p>}
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <Select aria-label="Time" value={range} onChange={(e) => { setRange(e.target.value); setOffset(0); }} options={RANGES.map(([value, label]) => ({ value, label }))} />
        {range === "custom" && <>
          <label className="cc-date">From <TextInput type="date" aria-label="From" value={f.from ?? ""} onChange={(e) => setFilter("from", e.target.value)} /></label>
          <label className="cc-date">To <TextInput type="date" aria-label="To" value={f.to ?? ""} onChange={(e) => setFilter("to", e.target.value)} /></label>
        </>}
        <TextInput type="search" aria-label="Search the audit trail" placeholder="Search everything" value={f.q ?? ""} onChange={(e) => setFilter("q", e.target.value)} />
        <Select aria-label="Area" value={f.area ?? ""} onChange={(e) => setFilter("area", e.target.value)} options={[{ value: "", label: "Every area" }, ...AREAS.map(([value, label]) => ({ value, label }))]} />
        <TextInput aria-label="Action" placeholder="Action, e.g. user.create" value={f.action ?? ""} onChange={(e) => setFilter("action", e.target.value)} />
        <TextInput aria-label="Who" placeholder="Who (user name)" value={f.actor ?? ""} onChange={(e) => setFilter("actor", e.target.value)} />
        <Select aria-label="Result" value={f.result ?? ""} onChange={(e) => setFilter("result", e.target.value)} options={[{ value: "", label: "Any result" }, ...RESULTS.map(([value, label]) => ({ value, label }))]} />
        <Select aria-label="Order" value={f.sort ?? "newest"} onChange={(e) => setFilter("sort", e.target.value)} options={[{ value: "newest", label: "Newest first" }, { value: "oldest", label: "Oldest first" }]} />
        {(f.object_type || f.object_id) && <span className="cc-tag">Object: {[f.object_type, f.object_id].filter(Boolean).join(" ")}</span>}
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => { setF({}); setOffset(0); }}>Clear filters</Button>}
      </div>
      <div className="cc-bar" role="toolbar" aria-label="Audit actions">
        {data && <span className="cc-chosen">{data.total} record{data.total === 1 ? "" : "s"}</span>}
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
        <Button kind="plain" onClick={() => setDialog({ kind: "columns" })}>Columns</Button>
        <Button kind="secondary" onClick={exportCsv}>Export CSV</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Audit records">
          <thead><tr>{cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}</tr></thead>
          <tbody>
            {records.map((a) => (
              <tr key={a.seq} onClick={() => setDialog({ kind: "record", record: a })}>
                {cols.map((c) => <td key={c.key} className={c.key === "result" ? "cc-status is-" + (a.result === "ok" ? "success" : "failed") : c.key === "details" ? "cc-clip" : undefined}>{c.cell(a)}</td>)}
              </tr>
            ))}
            {data && !records.length && <tr><td colSpan={cols.length} className="st-muted">No records match.</td></tr>}
            {!data && !error && <tr><td colSpan={cols.length} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {data && data.total > SIZE && (
        <div className="cc-pager">
          <Button kind="plain" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - SIZE))}>Previous</Button>
          <span>{offset + 1}–{Math.min(offset + SIZE, data.total)} of {data.total}</span>
          <Button kind="plain" disabled={offset + SIZE >= data.total} onClick={() => setOffset(offset + SIZE)}>Next</Button>
        </div>
      )}
      {dialog?.kind === "columns" && (
        <Dialog open title="Columns" width={360} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {COLS.map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)} disabled={c.key === "action"}
              onChange={(e) => setHidden(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
          </div>
        </Dialog>
      )}
      {dialog?.kind === "record" && <RecordDialog record={dialog.record} onClose={() => setDialog(null)}
        onRelated={(type, id) => { setF({ object_type: type, object_id: id }); setRange("all"); setOffset(0); setDialog(null); }} />}
    </section>
  );
}

/** One record in full: its details as name and value, and a way to see every record about the same object. */
function RecordDialog({ record: a, onClose, onRelated }: { record: AuditRecord; onClose: () => void; onRelated: (type: string, id: string) => void }) {
  let details: [string, string][] = [];
  try { details = Object.entries(JSON.parse(a.details ?? "{}")).map(([k, v]) => [k, typeof v === "object" ? JSON.stringify(v, null, 1) : String(v)]); }
  catch { details = a.details ? [["details", a.details]] : []; }
  return (
    <Dialog open title={"Audit record " + a.seq} width={640} onClose={onClose} footer={a.object_type && a.object_id
      ? <Button kind="secondary" onClick={() => onRelated(a.object_type!, a.object_id!)}>Every record about this {a.object_type}</Button> : null}>
      <dl className="cc-facts">
        <dt>Time</dt><dd>{when(a.ts)}</dd>
        <dt>Who</dt><dd>{a.actor}</dd>
        <dt>Action</dt><dd className="ui-mono">{a.action}</dd>
        <dt>Object</dt><dd>{[a.object_type, a.object_id].filter(Boolean).join(" ") || "None"}</dd>
        <dt>Result</dt><dd className={"cc-status is-" + (a.result === "ok" ? "success" : "failed")}>{resultName(a.result)}</dd>
        <dt>From address</dt><dd>{a.ip ?? "Not recorded"}</dd>
      </dl>
      <h3 className="cc-log-title">Details</h3>
      {details.length ? <dl className="cc-facts">{details.map(([k, v]) => [<dt key={k + "t"}>{k}</dt>, <dd key={k + "d"} className="ui-mono">{v}</dd>])}</dl>
        : <p className="st-muted">No further details.</p>}
    </Dialog>
  );
}
