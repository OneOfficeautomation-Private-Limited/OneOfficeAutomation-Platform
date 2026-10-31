import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { AuditRecord, CrApi } from "../api/cr";
import { Button, Dialog, Select, TextInput, usePersistent } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";

/**
 * Administration > Audit Trail, built as AA's Audit log (checked against AA's docs, UiPath's Audit page and Blue
 * Prism's audit log viewer, 31 Oct 2026): a time range (last 24 hours, 7 or 30 days, or chosen dates), filters by area
 * (AA's item types), action, person, result and object; a column chooser; a record's details with the changed values
 * and "more about this object" (UiPath's related records); CSV export of the search; and the hash-chain check, which
 * shows whether history was edited (Blue Prism-style tamper evidence).
 * Laid out as the design plan's step 5 (approved on the canvas 31 Oct 2026): the list pattern with records grouped by
 * day and said in plain words, history checkpoints hidden by default, and a record in a side panel beside the list
 * (Newer / Older, details by name, the run's other records, one-click searches, what seals it).
 * Review round 2 (1 Nov 2026): fits at 1366 px (who, what happened and the object cut to one width, in full on hover),
 * only time, who, what happened and result beside an open record, the Who filter kept narrow, and Hide checkpoints moved
 * under More filters (the pager still says when they are hidden), so the toolbar takes two lines.
 */
type Col = { key: string; label: string; cell: (a: AuditRecord) => string };

/** Areas by the first part of the action (user.create is Users), with the mark and colour of their icon tile. */
const AREAS: [string, string, string, string][] = [["auth.", "Sign-ins", "In", "#2f6fde"], ["user.", "Users", "U", "#1d4ed8"], ["role.", "Roles", "Ro", "#475569"],
  ["team.", "Teams", "T", "#475569"], ["locker.", "Vault folders", "V", "#be185d"], ["vault.", "Credential vault", "V", "#be185d"], ["credential.", "Credentials", "V", "#be185d"],
  ["repo.", "Agent Repository", "A", "#2f6fde"], ["lifecycle.", "Export and import", "A", "#2f6fde"], ["template.", "Templates", "A", "#2f6fde"], ["run.", "Runs", "R", "#0f766e"],
  ["device.", "Runner Machines", "M", "#0e7490"], ["pool.", "Runner pools", "M", "#0e7490"], ["schedule.", "Schedules", "S", "#b45309"], ["trigger.", "Triggers", "S", "#b45309"],
  ["queue.", "Work queues", "Q", "#7c3aed"], ["dashboard.", "Dashboards", "D", "#0369a1"], ["alert.", "Alerts", "!", "#b91c1c"], ["settings.", "Settings", "Se", "#475569"],
  ["license.", "Licenses", "L", "#475569"], ["apitask.", "Agent APIs", "P", "#1d4ed8"], ["apikey.", "API keys", "K", "#1d4ed8"], ["audit.", "Audit Trail", "H", "#475569"]];
/** What happened, in plain words; other actions fall back to their area and verb. */
const WORDS: Record<string, string> = {
  "auth.login": "Signed in", "auth.logout": "Signed out", "auth.failed": "Sign-in refused", "user.create": "Created a user", "user.update": "Changed a user", "user.delete": "Deleted a user",
  "user.change_password": "Changed a password", "user.reset_password": "Reset a password", "user.recover": "Recovered an account", "user.bootstrap": "Created the first administrator",
  "user.unlock": "Unlocked a user", "role.save": "Saved a role", "role.delete": "Deleted a role", "team.save": "Saved a team", "team.delete": "Deleted a team", "team.members": "Changed a team's members",
  "locker.create": "Created a vault folder", "locker.members": "Changed a vault folder's members", "vault.rotate": "Rotated the vault key", "credential.set": "Set a credential value",
  "credential.read": "Read a credential", "credential.delete": "Deleted a credential", "repo.checkin": "Checked in an agent", "repo.import": "Imported agents", "repo.set_production": "Put a version in production",
  "repo.delete": "Deleted an agent", "lifecycle.import": "Imported a bundle", "lifecycle.export": "Exported a bundle", "template.install": "Installed a template", "run.start": "Started a run",
  "run.finish": "Finished a run", "run.stop": "Stopped a run", "device.register": "Registered a runner", "device.registration_key": "Made a runner registration key", "device.delete": "Deleted a runner",
  "device.disable": "Switched off a runner", "device.enable": "Switched on a runner", "device.logs_request": "Asked a runner for its logs", "device.logs": "Sent its logs",
  "dashboard.save": "Saved a dashboard", "dashboard.delete": "Deleted a dashboard", "settings.update": "Changed settings", "license.install": "Installed a licence", "audit.export": "Exported audit records",
  "audit.checkpoint": "History checkpoint", "audit.purged": "Archived old records",
};
/** Detail names in plain words. */
const DETAIL_NAMES: Record<string, string> = { bot: "Agent", version: "Version", mode: "How it ran", source: "Started by", device: "Runner", status: "Status", run: "Run",
  upto_seq: "Covers records up to", upto_hash: "Hash", widgets: "Widgets", name: "Name", shared_with: "Shared with", role: "Role" };
const RESULTS: [string, string, string][] = [["ok", "Succeeded", "success"], ["failed", "Failed", "failed"], ["denied", "Refused", "failed"], ["not_found", "Not found", "pending"]];
const RANGES: [string, string][] = [["1", "24 hours"], ["7", "7 days"], ["30", "30 days"], ["all", "Any time"], ["custom", "Dates…"]];
const RANGE_WORDS: Record<string, string> = { "1": "in the last 24 hours", "7": "in the last 7 days", "30": "in the last 30 days", all: "in all", custom: "between the chosen dates" };
const areaOf = (action: string) => AREAS.find(([p]) => action.startsWith(p));
const wordsOf = (action: string) => WORDS[action] ?? ((areaOf(action)?.[1] ?? action.split(".")[0]) + ": " + action.split(".").slice(1).join(" ").replace(/_/g, " "));
const resultOf = (r: string) => RESULTS.find(([k]) => k === r) ?? [r, r, "pending"];
const pad = (n: number) => String(n).padStart(2, "0");
const hhmm = (t: string) => { const d = new Date(t); return pad(d.getHours()) + ":" + pad(d.getMinutes()); };
const isoDay = (t: string) => { const d = new Date(t); return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); };
function dayLabel(t: string) {
  const d = new Date(t), today = new Date(), y = new Date(); y.setDate(today.getDate() - 1);
  const words = d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", ...(d.getFullYear() !== today.getFullYear() ? { year: "numeric" } : {}) });
  return isoDay(t) === isoDay(today.toISOString()) ? "Today · " + words : isoDay(t) === isoDay(y.toISOString()) ? "Yesterday · " + words : words;
}
function ago(t: string) {
  const s = (Date.now() - new Date(t).getTime()) / 1000;
  return s < 90 ? "just now" : s < 5400 ? Math.round(s / 60) + " minutes ago" : s < 129600 ? Math.round(s / 3600) + " hours ago" : Math.round(s / 86400) + " days ago";
}
function parseDetails(d?: string | null): [string, string][] {
  if (!d) return [];
  try { return Object.entries(JSON.parse(d)).map(([k, v]) => [k, typeof v === "object" ? JSON.stringify(v) : String(v)]); } catch { return [["details", d]]; }
}
const detailsText = (d?: string | null) => parseDetails(d).map(([k, v]) => k + ": " + v).join("; ");
const runOf = (a: AuditRecord) => a.object_type === "run" ? a.object_id ?? null : parseDetails(a.details).find(([k]) => k === "run")?.[1] ?? null;
/** The columns kept beside an open record. */
/** Cells cut to one width, their full text on hover. */
const CELL_CLASS: Record<string, string> = { details: "cc-clip", actor: "cc-au-cut cc-au-who-c", action: "cc-au-cut cc-au-what", object: "cc-au-cut cc-au-obj" };
const BESIDE_RECORD = new Set(["ts", "actor", "action", "result"]);
/** A long name cut for the Who filter (the whole name is the value). */
const cut = (t: string, n = 28) => t.length > n ? t.slice(0, n - 1) + "…" : t;
const COLS: Col[] = [
  { key: "seq", label: "#", cell: (a) => String(a.seq) },
  { key: "ts", label: "Time", cell: (a) => new Date(a.ts).toLocaleString() },
  { key: "actor", label: "Who", cell: (a) => a.actor },
  { key: "action", label: "What happened", cell: (a) => wordsOf(a.action) + " (" + a.action + ")" },
  { key: "object", label: "Object", cell: (a) => [a.object_type, a.object_id].filter(Boolean).join(" ") },
  { key: "result", label: "Result", cell: (a) => resultOf(a.result)[1] },
  { key: "ip", label: "From address", cell: (a) => a.ip ?? "" },
  { key: "details", label: "Details", cell: (a) => detailsText(a.details) },
];
const SIZE = 100;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const dayStart = (v: string, next: boolean) => { const d = new Date(v + "T00:00:00"); if (next) d.setDate(d.getDate() + 1); return d.toISOString(); };

/** The area's icon tile. */
function AreaIcon({ action }: { action: string }) {
  const [, , mark, colour] = areaOf(action) ?? ["", "", action.slice(0, 1).toUpperCase(), "#475569"];
  return <span className="cc-au-ic" style={{ background: colour }} aria-hidden="true">{mark}</span>;
}
/** Who did it: a person with their initials, System, or a runner (acting for someone). */
function Who({ actor }: { actor: string }) {
  if (actor === "system") return <span className="cc-au-who"><span className="cc-au-av is-system" aria-hidden="true">S</span>System</span>;
  if (actor.startsWith("agent:")) {
    const [name, forWhom] = actor.slice(6).split(" for ");
    return <span className="cc-au-who" title={actor}><span className="cc-au-av is-runner" aria-hidden="true">M</span>{name}{forWhom && <span className="cc-none"> for {forWhom}</span>}</span>;
  }
  return <span className="cc-au-who"><span className="cc-au-av" aria-hidden="true">{actor.slice(0, 2).toUpperCase()}</span>{actor}</span>;
}
const ResultPill = ({ result }: { result: string }) => { const [, label, cls] = resultOf(result); return <span className={"cc-pill is-" + cls}><span className="cc-pill-dot" />{label}</span>; };

export function Audit({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [hidden, setHidden] = usePersistent<string[]>("audit.hidden2", ["details"]);
  const [range, setRange] = usePersistent<string>("audit.range", "1");
  const [hideCheckpoints, setHideCheckpoints] = usePersistent<boolean>("audit.hideCheckpoints", true);
  const [f, setF] = useState<Record<string, string>>({});
  const [more, setMore] = useState(false);
  const [offset, setOffset] = useState(0);
  const [data, setData] = useState<{ records: AuditRecord[]; total: number } | null>(null);
  const [actors, setActors] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [check, setCheck] = useState<{ valid: boolean; text: string } | null>(null);
  const [open, setOpen] = useState<AuditRecord | null>(null);
  const [columns, setColumns] = useState(false);

  /** The query: filters, then the time (5 minutes around a record, or the range; Any time adds nothing). */
  const params = useCallback(() => {
    const p: Record<string, string> = {};
    for (const k of ["q", "actor", "object_type", "object_id", "result", "sort"]) if (f[k]?.trim()) p[k] = f[k].trim();
    const action = f.action?.trim() || f.area || "";
    if (action) p.action = action;
    if (hideCheckpoints && !action.startsWith("audit.")) p.checkpoints = "hide";
    if (f.around) { const t = new Date(f.around).getTime(); p.from = new Date(t - 300_000).toISOString(); p.to = new Date(t + 300_000).toISOString(); }
    else if (range === "custom") { if (f.from) p.from = dayStart(f.from, false); if (f.to) p.to = dayStart(f.to, true); }
    else if (range !== "all") p.from = new Date(Date.now() - Number(range) * 86_400_000).toISOString();
    return p;
  }, [f, range, hideCheckpoints]);
  const load = useCallback(async () => {
    try {
      const d = await api.audit({ ...params(), limit: String(SIZE), offset: String(offset) });
      setData(d); setError(null);
      setActors((a) => [...new Set([...a, ...d.records.map((r) => r.actor)])].sort());
    } catch (e) { setError(message(e)); }
  }, [api, params, offset]);
  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t); }, [load]);   // typing waits a moment

  const setFilter = (k: string, v: string) => { setF({ ...f, [k]: v }); setOffset(0); };
  // beside an open record the list keeps only what tells records apart, so both fit at 1366 px
  const cols = COLS.filter((c) => !hidden.includes(c.key) && (!open || BESIDE_RECORD.has(c.key)));
  const filtered = Object.values(f).some(Boolean);
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
      const at = hhmm(new Date().toISOString());
      setCheck({ valid: v.valid, text: v.valid ? "History intact · " + v.records + " records checked at " + at : "History was changed: the chain breaks at record " + v.broken_at });
    } catch (e) { setError(message(e)); }
  };
  const records = data?.records ?? [];
  /** Searches from a record: about its object, by its person that day, or 5 minutes around it. */
  const search = (next: Record<string, string>, nextRange?: string) => { setF(next); if (nextRange) setRange(nextRange); setOffset(0); setOpen(null); };
  const cell = (c: Col, a: AuditRecord) => {
    if (c.key === "ts") return <span title={new Date(a.ts).toLocaleString()}>{hhmm(a.ts)}</span>;
    if (c.key === "actor") return <Who actor={a.actor} />;
    if (c.key === "action") return <><AreaIcon action={a.action} />{wordsOf(a.action)}<span className="cc-au-code">{a.action}</span></>;
    if (c.key === "object") return a.object_type || a.object_id ? <span title={a.object_id ?? ""}><span className="cc-none">{a.object_type}</span> {a.object_id}</span> : <span className="cc-none">–</span>;
    if (c.key === "result") return <ResultPill result={a.result} />;
    if (c.key === "seq") return <span className="cc-none">{a.seq}</span>;
    const v = c.cell(a);
    return v || <span className="cc-none">–</span>;
  };
  const rows: ReactNode[] = [];
  records.forEach((a, i) => {
    if (i === 0 || isoDay(a.ts) !== isoDay(records[i - 1].ts)) rows.push(<tr key={"d" + a.seq} className="cc-au-day is-static"><td colSpan={cols.length + 1}>{dayLabel(a.ts)}</td></tr>);
    rows.push(
      <tr key={a.seq} className={open?.seq === a.seq ? "is-selected" : ""} onClick={rowClick(() => setOpen(a))}>
        {cols.map((c) => <td key={c.key} className={CELL_CLASS[c.key]} title={c.key in CELL_CLASS && c.key !== "details" ? c.cell(a) : undefined}>{cell(c, a)}</td>)}
        <td className="cc-list-menu"><RowMenu label={"Actions for record " + a.seq} items={[
          { key: "open", label: "Open", onSelect: () => setOpen(a) },
          ...(a.object_type && a.object_id ? [{ key: "about", label: "Every record about this " + a.object_type, onSelect: () => search({ object_type: a.object_type!, object_id: a.object_id! }, "all") }] : []),
          { key: "around", label: "5 minutes around it", onSelect: () => search({ around: a.ts }) }]} /></td>
      </tr>);
  });
  const index = open ? records.findIndex((r) => r.seq === open.seq) : -1;

  return (
    <section className={"st-page cc-home cc-list cc-au" + (open ? " has-panel" : "")} aria-labelledby="cc-audit-title">
      <header className="cc-list-head">
        <h1 id="cc-audit-title">Audit Trail</h1>
        <span className="ov-info" role="img" aria-label="About this page" title="Every sign-in, change, run and credential use. Records are chained by hashes, so any edit to history shows.">i</span>
        {data && <span className="cc-list-count">{data.total} record{data.total === 1 ? "" : "s"} {f.around ? "within 5 minutes of " + hhmm(f.around) : RANGE_WORDS[range]}</span>}
        {check && <span className={"cc-pill is-" + (check.valid ? "success" : "failed")} role="status"><span className="cc-pill-dot" />{check.text}</span>}
        <span className="ov-fill" />
        <Button kind="secondary" onClick={verify}>Check history</Button>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="ov-seg" role="group" aria-label="Time">
            {RANGES.map(([v, l]) => <button key={v} type="button" aria-pressed={!f.around && range === v} onClick={() => { setRange(v); setF({ ...f, around: "" }); setOffset(0); }}>{l}</button>)}
          </span>
          {range === "custom" && !f.around && <>
            <label className="cc-date">From <TextInput type="date" aria-label="From" value={f.from ?? ""} onChange={(e) => setFilter("from", e.target.value)} /></label>
            <label className="cc-date">To <TextInput type="date" aria-label="To" value={f.to ?? ""} onChange={(e) => setFilter("to", e.target.value)} /></label>
          </>}
          <span className="cc-list-search"><TextInput type="search" aria-label="Search the audit trail" placeholder="Search everything" value={f.q ?? ""} onChange={(e) => setFilter("q", e.target.value)} /></span>
          <span className={"cc-chip" + (f.area ? " is-on" : "")}><Select aria-label="Area" value={f.area ?? ""} onChange={(e) => setFilter("area", e.target.value)}
            options={[{ value: "", label: "Area: Any" }, ...AREAS.map(([value, label]) => ({ value, label: "Area: " + label }))]} /></span>
          <span className={"cc-chip" + (f.actor ? " is-on" : "")}><Select aria-label="Who" value={f.actor ?? ""} onChange={(e) => setFilter("actor", e.target.value)}
            options={[{ value: "", label: "Who: Anyone" }, ...[...new Set([...actors, ...(f.actor ? [f.actor] : [])])].map((a) => ({ value: a, label: "Who: " + (a === "system" ? "System" : cut(a)) }))]} /></span>
          <span className={"cc-chip" + (f.result ? " is-on" : "")}><Select aria-label="Result" value={f.result ?? ""} onChange={(e) => setFilter("result", e.target.value)}
            options={[{ value: "", label: "Result: Any" }, ...RESULTS.map(([value, label]) => ({ value, label: "Result: " + label }))]} /></span>
          <Button kind="plain" aria-expanded={more} onClick={() => setMore(!more)}>{more ? "Fewer filters" : "More filters"}</Button>
          {(f.object_type || f.object_id) && <span className="cc-tag">Object: {[f.object_type, f.object_id].filter(Boolean).join(" ")}</span>}
          {f.around && <span className="cc-tag">Within 5 minutes of {new Date(f.around).toLocaleString()}</span>}
          {filtered && <Button kind="plain" onClick={() => { setF({}); setOffset(0); }}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Audit Trail tools">
          <Button kind="plain" onClick={() => setColumns(true)}>Columns</Button>
          <Button kind="plain" onClick={exportCsv}>Export CSV</Button>
          <RefreshButton onClick={load} />
        </div>
      </div>
      {more && <div className="cc-list-filters cc-au-more" role="group" aria-label="More filters">
        <TextInput aria-label="Action" placeholder="Action, e.g. user.create" value={f.action ?? ""} onChange={(e) => setFilter("action", e.target.value)} />
        <TextInput aria-label="Object type" placeholder="Object type, e.g. run" value={f.object_type ?? ""} onChange={(e) => setFilter("object_type", e.target.value)} />
        <TextInput aria-label="Object id" placeholder="Object id" value={f.object_id ?? ""} onChange={(e) => setFilter("object_id", e.target.value)} />
        <span className="cc-chip"><Select aria-label="Order" value={f.sort ?? "newest"} onChange={(e) => setFilter("sort", e.target.value)}
          options={[{ value: "newest", label: "Newest first" }, { value: "oldest", label: "Oldest first" }]} /></span>
        <label className={"cc-chip-check" + (hideCheckpoints ? " is-on" : "")} title="History checkpoints seal the records before them; they are hidden to keep the list to what people and runners did">
          <input type="checkbox" checked={hideCheckpoints} onChange={(e) => { setHideCheckpoints(e.target.checked); setOffset(0); }} /> Hide checkpoints</label>
      </div>}
      <div className="cc-list-panel">
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Audit records">
            <thead><tr>{cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}<th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {rows}
              {data && !records.length && <tr className="is-static"><td colSpan={cols.length + 1} className="cc-none">No records match.</td></tr>}
              {!data && !error && <tr className="is-static"><td colSpan={cols.length + 1} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {data && <div className="cc-list-pager">
          <span>{data.total ? (offset + 1) + "–" + Math.min(offset + SIZE, data.total) + " of " + data.total : "0 of 0"}{hideCheckpoints ? " · history checkpoints hidden" : ""}</span>
          <span className="ov-fill" />
          <button type="button" className="cc-list-icon" aria-label="Previous page" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - SIZE))}>‹</button>
          <button type="button" className="cc-list-icon" aria-label="Next page" disabled={offset + SIZE >= data.total} onClick={() => setOffset(offset + SIZE)}>›</button>
        </div>}
      </div>
      {columns && (
        <Dialog open title="Columns" width={360} onClose={() => setColumns(false)} footer={<Button kind="primary" onClick={() => setColumns(false)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {COLS.map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)} disabled={c.key === "action"}
              onChange={(e) => setHidden(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
          </div>
        </Dialog>
      )}
      {open && <RecordPanel key={open.seq} api={api} record={open} onClose={() => setOpen(null)}
        newer={index > 0 ? () => setOpen(records[index - 1]) : null} older={index >= 0 && index < records.length - 1 ? () => setOpen(records[index + 1]) : null}
        onSearch={search} />}
    </section>
  );
}

/**
 * One record in a side panel beside the list: what happened and who, its details by name (agents, runners and runs as
 * links), the run's other records in order, searches from it, and the checkpoint that seals it.
 */
function RecordPanel({ api, record: a, onClose, newer, older, onSearch }: { api: CrApi; record: AuditRecord; onClose: () => void;
  newer: (() => void) | null; older: (() => void) | null; onSearch: (f: Record<string, string>, range?: string) => void }) {
  const run = runOf(a);
  const [runRecords, setRunRecords] = useState<AuditRecord[] | null>(null);
  const [seal, setSeal] = useState<AuditRecord | null | undefined>(undefined);
  const head = useRef<HTMLHeadingElement>(null);
  useEffect(() => { head.current?.focus(); }, []);
  useEffect(() => {
    if (run) api.audit({ q: run, sort: "oldest", limit: "50" }).then((d) => setRunRecords(d.records), () => setRunRecords([]));
    if (a.action !== "audit.checkpoint") api.audit({ action: "audit.checkpoint", sort: "oldest", from: a.ts, limit: "1" })
      .then((d) => setSeal(d.records.find((c) => Number(parseDetails(c.details).find(([k]) => k === "upto_seq")?.[1]) >= a.seq) ?? null), () => setSeal(null));
  }, [api, a, run]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, [onClose]);
  const details = useMemo(() => parseDetails(a.details), [a.details]);
  const value = (k: string, v: string) => {
    if (k === "run") return <a href={"#/activity?run=" + encodeURIComponent(v)}>{v}</a>;
    if (k === "bot") return <a href={"#/automation?q=" + encodeURIComponent(v.replace(/\.flow\.json$/, "").split("/").pop() ?? v)}>{v.replace(/\.flow\.json$/, "")}</a>;
    if (k === "source" && v === "rerun") return "Run again (a rerun)";
    if (k === "mode") return v.charAt(0).toUpperCase() + v.slice(1);
    return v;
  };
  return (
    <aside className="cc-au-panel" role="dialog" aria-modal="false" aria-label={"Audit record " + a.seq}>
      <div className="cc-au-panel-head">
        <AreaIcon action={a.action} />
        <h2 ref={head} tabIndex={-1}>{wordsOf(a.action)}</h2>
        <ResultPill result={a.result} />
        <span className="ov-fill" />
        <span className="cc-none">#{a.seq}</span>
        <Button kind="plain" disabled={!newer} onClick={() => newer?.()}>‹ Newer</Button>
        <Button kind="plain" disabled={!older} onClick={() => older?.()}>Older ›</Button>
        <button type="button" className="cc-list-icon" aria-label="Close" title="Close" onClick={onClose}>×</button>
      </div>
      <div className="cc-au-panel-body">
        <dl className="cc-facts">
          <dt>When</dt><dd>{new Date(a.ts).toLocaleString()} ({ago(a.ts)})</dd>
          <dt>Who</dt><dd><Who actor={a.actor} />{a.ip && <span className="cc-none"> from {a.ip}</span>}</dd>
          <dt>Object</dt><dd>{a.object_type || a.object_id ? <>{a.object_type} {run && a.object_type === "run" ? <a href={"#/activity?run=" + encodeURIComponent(run)}>{a.object_id}</a> : a.object_id}</> : "None"}</dd>
          <dt>Action code</dt><dd className="ui-mono">{a.action}</dd>
        </dl>
        <section aria-label="Details">
          <h3 className="cc-au-sec">Details</h3>
          {details.length ? <table className="cc-au-kv"><tbody>{details.map(([k, v]) => <tr key={k}><th scope="row">{DETAIL_NAMES[k] ?? k}</th><td className={k.endsWith("hash") ? "ui-mono" : undefined}>{value(k, v)}</td></tr>)}</tbody></table>
            : <p className="cc-none">No further details.</p>}
        </section>
        {run && <section aria-label="This run's records">
          <h3 className="cc-au-sec">This run's records</h3>
          {runRecords === null ? <p className="cc-none" role="status">Loading…</p> : <ul className="cc-au-tl">
            {runRecords.map((r) => <li key={r.seq} className={r.seq === a.seq ? "is-now" : ""}><AreaIcon action={r.action} /><span>{hhmm(r.ts)}</span><span>{wordsOf(r.action)}</span>
              <span className="ov-fill" /><span className="cc-none">{r.actor.replace(/^agent:/, "")}</span></li>)}
          </ul>}
        </section>}
        <div className="cc-au-links">
          {run && <a className="ui-btn ui-btn-secondary" href={"#/activity?run=" + encodeURIComponent(run)}>Open the run in Agent Activity</a>}
          {a.object_type && a.object_id && <Button kind="secondary" onClick={() => onSearch({ object_type: a.object_type!, object_id: a.object_id! }, "all")}>Every record about this {a.object_type}</Button>}
          {a.actor !== "system" && <Button kind="secondary" onClick={() => onSearch({ actor: a.actor, from: isoDay(a.ts), to: isoDay(a.ts) }, "custom")}>Everything {a.actor.replace(/^agent:/, "")} did that day</Button>}
          <Button kind="secondary" onClick={() => onSearch({ around: a.ts })}>5 minutes around it</Button>
        </div>
        <p className="cc-au-seal">
          <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"><path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z" /><path d="m9 12 2 2 4-4" /></svg>
          {a.seq > 1 ? "Chained to record #" + (a.seq - 1) + "; " : "The first record; "}
          {seal === undefined ? (a.action === "audit.checkpoint" ? "this checkpoint seals the records before it." : "looking for its checkpoint…")
            : seal ? "sealed by checkpoint #" + seal.seq + "." : "the next checkpoint will seal it."} Use Check history to prove nothing was edited.
        </p>
      </div>
    </aside>
  );
}
