import { useState, type ReactNode } from "react";
import type { DocFlowApi, ProcessRow, Source } from "../api";
import { folderLabel } from "../dialogs";
import { Chip, FilterBar, FilterSelect, Loading, SearchBox, matches, useLoad } from "../ui";

const SOURCE: Record<Source, string> = { upload: "Upload", folder: "Watched folder", email: "Email", bot: "Bot", split: "Split" };
type Col = { key: string; label: string; right?: boolean; sort: (p: ProcessRow) => string | number; cell: (p: ProcessRow) => ReactNode };

/** The last document's time: the hour today, else the day. */
function when(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  return d.toDateString() === new Date().toDateString()
    ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : d.toLocaleDateString([], { day: "numeric", month: "short" });
}
const n = (v: number, tone?: "bad" | "warn") => <span className={`df-num${v && tone ? " df-text-" + tone : ""}`}>{v.toLocaleString("en")}</span>;

/** The columns: what the process is and how it is set up, then what is happening. */
const COLS: Col[] = [
  { key: "name", label: "Process", sort: (p) => p.name.toLowerCase(),
    cell: (p) => <a href={"#/process/" + encodeURIComponent(p.id)} onClick={(e) => e.stopPropagation()}>{p.name}</a> },
  { key: "folder", label: "Folder", sort: (p) => folderLabel(p.folder), cell: (p) => folderLabel(p.folder) || <span className="df-muted">None</span> },
  { key: "state", label: "Status", sort: (p) => p.state + p.version,
    cell: (p) => <Chip tone={p.state === "live" ? "ok" : "mute"}>{p.state === "live" ? "Live" : "Draft"} · v{p.version}</Chip> },
  { key: "types", label: "Document types", sort: (p) => p.docTypes.join(", "), cell: (p) => (p.isInbox ? "Any type" : p.docTypes.join(", ") || "—") },
  { key: "sources", label: "Comes in from", sort: (p) => p.sources.join(), cell: (p) => p.sources.map((s) => SOURCE[s]).join(", ") },
  { key: "classify", label: "Classification", sort: (p) => p.classifyMode, cell: (p) => (p.classifyMode === "auto" ? "Recognised" : "One type") },
  { key: "extraction", label: "Extraction engine", sort: (p) => p.extraction, cell: (p) => p.extraction },
  { key: "checks", label: "Checks on", right: true, sort: (p) => p.checksOn, cell: (p) => <span className="df-num">{p.checksOn} of {p.checks}</span> },
  { key: "review", label: "Review", sort: (p) => p.fieldConfidence,
    cell: (p) => <span className="df-num">Under {Math.round(p.fieldConfidence * 100)}% · {p.slaHours} h</span> },
  { key: "deliveries", label: "Delivers to", sort: (p) => p.deliversTo.join(", "), cell: (p) => p.deliversTo.join(", ") || <span className="df-muted">Not set</span> },
  { key: "month", label: "This month", right: true, sort: (p) => p.month, cell: (p) => n(p.month) },
  { key: "today", label: "Today", right: true, sort: (p) => p.today, cell: (p) => n(p.today) },
  { key: "done", label: "Done", right: true, sort: (p) => p.done, cell: (p) => n(p.done) },
  { key: "stp", label: "Straight through", right: true, sort: (p) => p.straightThrough, cell: (p) => <span className="df-num">{p.straightThrough}%</span> },
  { key: "waiting", label: "Waiting for review", right: true, sort: (p) => p.review, cell: (p) => n(p.review, "warn") },
  { key: "overdue", label: "Overdue", right: true, sort: (p) => p.overdue, cell: (p) => n(p.overdue, "bad") },
  { key: "failed", label: "Failed", right: true, sort: (p) => p.failed, cell: (p) => n(p.failed, "bad") },
  { key: "last", label: "Last document", sort: (p) => p.lastAt ?? "",
    cell: (p) => <><span className="df-num">{when(p.lastAt)}</span>{p.lastAt && <div className="df-small">{p.lastDocument}</div>}</> },
];

/** The columns each view shows: what is happening (the default), or how each process is set up. Both fit the screen. */
const VIEWS = {
  activity: ["name", "state", "month", "today", "done", "stp", "waiting", "overdue", "failed", "last"],
  setup: ["name", "folder", "state", "types", "sources", "classify", "extraction", "checks", "review", "deliveries"],
} as const;
type View = keyof typeof VIEWS;

/**
 * Overview, Table: one row per process (One Inbox too). Search, filter and sort; a row opens the process. Since
 * 13 Nov 2026 (improvement list item 11) it shows ten columns at a time, Activity or Set-up, so it fits the screen.
 */
export function ProcessTable({ api }: { api: DocFlowApi }) {
  const [all] = useLoad(() => api.processTable(), [api]);
  const [q, setQ] = useState("");
  const [state, setState] = useState<"all" | "live" | "draft">("all");
  const [folder, setFolder] = useState("all");
  const [source, setSource] = useState<"all" | Source>("all");
  const [sort, setSort] = useState<{ key: string; up: boolean }>({ key: "name", up: true });
  const [view, setView] = useState<View>("activity");
  if (!all) return <Loading />;
  const shown = COLS.filter((c) => (VIEWS[view] as readonly string[]).includes(c.key));

  const top = (f: string) => folderLabel(f).split(" / ")[0];
  const col = COLS.find((c) => c.key === sort.key)!;
  const rows = all.filter((p) => matches(q, p.name, folderLabel(p.folder), ...p.docTypes, p.lastDocument) && (state === "all" || p.state === state)
    && (folder === "all" || top(p.folder) === folder) && (source === "all" || p.sources.includes(source)))
    .sort((a, b) => { const x = col.sort(a), y = col.sort(b); return (x < y ? -1 : x > y ? 1 : 0) * (sort.up ? 1 : -1); });
  const clear = () => { setQ(""); setState("all"); setFolder("all"); setSource("all"); };
  const by = (key: string) => setSort(sort.key === key ? { key, up: !sort.up } : { key, up: !COLS.find((c) => c.key === key)!.right });

  return (
    <section className="df-card" aria-label="Processes" style={{ overflow: "hidden" }}>
      <FilterBar onClear={clear} count={`${rows.length} of ${all.length} processes`}>
        <SearchBox value={q} onChange={setQ} placeholder="Search processes, folders or types" />
        <FilterSelect label="Status" value={state} onChange={setState}
          options={[{ value: "all", label: "Live and draft" }, { value: "live", label: "Live" }, { value: "draft", label: "Draft" }]} />
        <FilterSelect label="Folder" value={folder} onChange={setFolder} options={[{ value: "all", label: "All folders" },
          ...[...new Set(all.map((p) => top(p.folder)).filter(Boolean))].sort().map((f) => ({ value: f, label: f }))]} />
        <FilterSelect label="Comes in from" value={source} onChange={setSource} options={[{ value: "all", label: "Any way" },
          { value: "upload", label: "Upload" }, { value: "folder", label: "Watched folder" }, { value: "email", label: "Email" }, { value: "bot", label: "Bot" }]} />
        <div className="df-seg" role="group" aria-label="Show">
          <button type="button" aria-pressed={view === "activity"} onClick={() => setView("activity")}>Activity</button>
          <button type="button" aria-pressed={view === "setup"} onClick={() => setView("setup")}>Set-up</button>
        </div>
      </FilterBar>
      <div className="df-table-wrap" role="group" aria-label="Process table" tabIndex={0}>
        <table className="df-table df-wide-table">
          <thead><tr>
            {shown.map((c) => (
              <th key={c.key} className={c.right ? "df-right" : undefined} aria-sort={sort.key === c.key ? (sort.up ? "ascending" : "descending") : "none"}>
                <button type="button" className="df-sort" onClick={() => by(c.key)}>{c.label}{sort.key === c.key && <span aria-hidden="true">{sort.up ? " ▲" : " ▼"}</span>}</button>
              </th>
            ))}
          </tr></thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.id} className="df-row-link" onClick={() => { location.hash = "#/process/" + encodeURIComponent(p.id); }}>
                {shown.map((c) => <td key={c.key} className={c.right ? "df-right" : undefined}>{c.cell(p)}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && <div className="df-empty">No process matches these filters. <button type="button" className="df-link" onClick={clear}>Clear filters</button></div>}
      </div>
    </section>
  );
}
