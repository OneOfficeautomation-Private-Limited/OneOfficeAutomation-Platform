import { useState } from "react";
import { Button } from "../components";
import type { DocFlowApi } from "./api";
import { Chip, FilterBar, FilterSelect, Loading, SearchBox, matches, useLoad } from "./ui";

/** Document types: what is read from each kind of document and how it is checked; learned labels come from reviews. */
export function DocTypes({ api }: { api: DocFlowApi }) {
  const [types] = useLoad(() => api.docTypes(), [api]);
  const [chosen, setChosen] = useState("supplier-invoice");
  const [listQ, setListQ] = useState("");
  const [origin, setOrigin] = useState<"all" | "built-in" | "made-here">("all");
  const [q, setQ] = useState("");
  const [type, setType] = useState("all");
  const [req, setReq] = useState<"all" | "required" | "optional">("all");
  if (!types) return <Loading />;

  const list = types.filter((t) => matches(listQ, t.name, t.meta) && (origin === "all" || t.origin === origin));
  const t = types.find((x) => x.id === chosen) ?? types[0];
  const fields = t.fields.filter((f) => matches(q, f.name, f.labels) && (type === "all" || f.type === type) && (req === "all" || f.required === (req === "required")));

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Document types</h2><p>What DocFlow reads from each kind of document, and how it checks it. Used by processes.</p></div>
        <span className="df-grow" /><Button>Teach from samples</Button><Button kind="primary" icon="plus">New document type</Button>
      </div>
      <div className="df-split">
        <nav className="df-card df-list" aria-label="Document types">
          <SearchBox value={listQ} onChange={setListQ} placeholder="Search types" />
          <select className="ui-input ui-select" aria-label="Origin" value={origin} onChange={(e) => setOrigin(e.target.value as typeof origin)}>
            <option value="all">All types ({types.length})</option>
            <option value="built-in">Built in ({types.filter((x) => x.origin === "built-in").length})</option>
            <option value="made-here">Made here ({types.filter((x) => x.origin === "made-here").length})</option>
          </select>
          {list.map((x) => (
            <button key={x.id} type="button" className="df-list-item" aria-current={x.id === t.id} onClick={() => setChosen(x.id)}>
              <span style={{ display: "flex", gap: 8 }}><b>{x.name}</b><span className="df-grow" /><span className="df-small">{x.fields.length ? x.fields.length + " fields" : ""}</span></span>
              <span className="df-small">{x.meta}</span>
            </button>
          ))}
        </nav>

        <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
          <section className="df-card df-pad" aria-label={t.name}>
            <div className="df-head-row">
              <b style={{ fontSize: 16 }}>{t.name}</b><Chip tone="mute" dot={false}>{t.origin === "built-in" ? "Built in" : "Made here"}</Chip>
              <span className="df-grow" /><Button>Test on a document</Button><Button kind="primary">Save</Button>
            </div>
            <div className="df-grid df-g3" style={{ marginTop: 12 }}>
              <div><div className="df-small">Recognised by</div>{t.keywords}</div>
              <div><div className="df-small">Languages</div>{t.languages}</div>
              <div><div className="df-small">Used by</div>{t.usedBy.length ? t.usedBy.join(", ") : "No process yet"}</div>
            </div>
          </section>

          <section className="df-card" aria-label="Fields to read" style={{ overflow: "hidden" }}>
            <div className="df-head-row df-pad" style={{ paddingBottom: 0 }}><b>Fields to read</b><span className="df-grow" /><Button icon="plus">Add field</Button></div>
            <FilterBar onClear={() => { setQ(""); setType("all"); setReq("all"); }} count={`${fields.length} of ${t.fields.length} fields`}>
              <SearchBox value={q} onChange={setQ} placeholder="Search fields or labels" />
              <FilterSelect label="Type" value={type} onChange={setType} options={[{ value: "all", label: "All types" },
                ...["Text", "Date", "Amount", "Table"].map((v) => ({ value: v, label: v }))]} />
              <FilterSelect label="Required" value={req} onChange={setReq} options={[{ value: "all", label: "All" }, { value: "required", label: "Required" }, { value: "optional", label: "Optional" }]} />
            </FilterBar>
            <div className="df-table-wrap">
              <table className="df-table" style={{ minWidth: 780 }}>
                <thead><tr><th>Field</th><th>Labels on the page</th><th>Type</th><th>Required</th><th>Format check</th><th>Learned from reviews</th><th><span className="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  {fields.map((f) => (
                    <tr key={f.name}>
                      <td><b>{f.name}</b></td><td className="df-muted">{f.labels}</td><td>{f.type}</td><td>{f.required ? "Required" : "Optional"}</td>
                      <td className="ui-mono">{f.check}</td><td className="df-muted">{f.learned}</td>
                      <td className="df-right"><Button kind="plain">Edit</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!t.fields.length && <div className="df-empty">No fields yet. Add them, or use Teach from samples.</div>}
              {t.fields.length > 0 && !fields.length && <div className="df-empty">No field matches these filters.</div>}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
