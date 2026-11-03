import { useState } from "react";
import { Button } from "../components";
import type { DocFlowApi } from "./api";
import { Chip, Empty, FilterBar, FilterSelect, Loading, SearchBox, matches, useLoad } from "./ui";

/** Reference data: master data that lookup checks use, e.g. "is this supplier known?". Uploaded as CSV or Excel. */
export function RefData({ api }: { api: DocFlowApi }) {
  const [tables] = useLoad(() => api.refTables(), [api]);
  const [chosen, setChosen] = useState("suppliers");
  const [listQ, setListQ] = useState("");
  const [q, setQ] = useState("");
  const [country, setCountry] = useState("all");
  const [active, setActive] = useState<"all" | "active" | "blocked">("all");
  const [matched, setMatched] = useState<"all" | "month" | "never">("all");
  if (!tables) return <Loading />;
  if (!tables.length) {
    return (
      <div className="df-page">
        <div className="df-page-head">
          <div><h2>Reference data</h2><p>Master data that checks look values up in, e.g. "is this supplier known?". Upload a CSV or Excel file.</p></div>
          <span className="df-grow" /><Button kind="primary" icon="plus">Upload a table</Button>
        </div>
        <Empty>No tables yet. Upload a list of suppliers, purchase orders or cost centres for checks to look values up in.</Empty>
      </div>
    );
  }

  const list = tables.filter((t) => matches(listQ, t.name, t.meta));
  const t = tables.find((x) => x.id === chosen) ?? tables[0];
  const sample = t.sample ?? [];
  const rows = sample.filter((r) => matches(q, r.name, r.vat, r.code) && (country === "all" || r.country === country)
    && (active === "all" || r.active === (active === "active")) && (matched === "all" || (matched === "month" ? r.matched > 0 : r.matched === 0)));

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Reference data</h2><p>Master data that checks look values up in, e.g. "is this supplier known?". Upload a CSV or Excel file.</p></div>
        <span className="df-grow" /><Button kind="primary" icon="plus">Upload a table</Button>
      </div>
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
              <span className="df-muted">{t.rows.toLocaleString("en")} rows{t.file ? " · from " + t.file : ""}</span>
              <span className="df-grow" /><Button>Download</Button><Button>Replace with a new file</Button>
            </div>
            <div className="df-grid df-g3" style={{ marginTop: 12 }}>
              <div><div className="df-small">Updated</div>{t.updated ?? "—"}</div>
              <div><div className="df-small">Match on</div>{t.matchOn ?? "—"}</div>
              <div><div className="df-small">Used by checks</div>{t.usedBy?.length ? "Known supplier in " + t.usedBy.join(", ") : t.meta}</div>
            </div>
          </section>

          <section className="df-card" aria-label="Rows" style={{ overflow: "hidden" }}>
            <FilterBar onClear={() => { setQ(""); setCountry("all"); setActive("all"); setMatched("all"); }} count={`${rows.length} of ${t.rows.toLocaleString("en")} rows`}>
              <SearchBox value={q} onChange={setQ} placeholder="Search name, VAT number or code" />
              <FilterSelect label="Country" value={country} onChange={setCountry} options={[{ value: "all", label: "All" },
                ...[...new Set(sample.map((r) => r.country))].map((c) => ({ value: c, label: c }))]} />
              <FilterSelect label="Status" value={active} onChange={setActive} options={[{ value: "all", label: "All" }, { value: "active", label: "Active" }, { value: "blocked", label: "Blocked" }]} />
              <FilterSelect label="Matched" value={matched} onChange={setMatched} options={[{ value: "all", label: "Any time" },
                { value: "month", label: "Matched this month" }, { value: "never", label: "Never matched" }]} />
            </FilterBar>
            <div className="df-table-wrap">
              <table className="df-table" style={{ minWidth: 760 }}>
                <thead><tr><th>Supplier code</th><th>Name</th><th>VAT number</th><th>Country</th><th>Status</th><th className="df-right">Matched this month</th></tr></thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.code}>
                      <td className="ui-mono">{r.code}</td><td><b>{r.name}</b></td><td className="df-num">{r.vat}</td><td>{r.country}</td>
                      <td><Chip tone={r.active ? "ok" : "bad"}>{r.active ? "Active" : "Blocked"}</Chip></td><td className="df-right df-num">{r.matched}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!sample.length && <div className="df-empty">Preview not loaded for this table.</div>}
              {sample.length > 0 && !rows.length && <div className="df-empty">No row matches these filters.</div>}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
