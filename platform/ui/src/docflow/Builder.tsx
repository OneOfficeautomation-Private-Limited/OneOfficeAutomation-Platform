import { useEffect, useState } from "react";
import { Button, Icon } from "../components";
import type { Check, DocFlowApi, ProcessDesign } from "./api";
import { Chip, FilterBar, FilterSelect, Loading, SearchBox, matches } from "./ui";

const IF_FAILS = { review: "Send to review", reject: "Reject", warn: "Warn only" } as const;

/** The process builder: six stages; the Checks and lookups stage holds the rules, each with what happens when it fails. */
export function Builder({ api }: { api: DocFlowApi }) {
  const [design, setDesign] = useState<ProcessDesign | null>(null);
  const [stage, setStage] = useState(3);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("all");
  const [onOff, setOnOff] = useState<"all" | "on" | "off">("all");
  const [fails, setFails] = useState<"all" | Check["ifFails"]>("all");
  const [savedAs, setSavedAs] = useState<"" | "draft" | "live">("");
  useEffect(() => { void api.processDesign("supplier-invoices-ksa").then(setDesign); }, [api]);
  if (!design) return <Loading />;

  const setCheck = (id: string, patch: Partial<Check>) => {
    setSavedAs("");
    setDesign({ ...design, checks: design.checks.map((c) => c.id === id ? { ...c, ...patch } : c) });
  };
  const rows = design.checks.filter((c) => matches(q, c.name, c.test, c.kind) && (kind === "all" || c.kind === kind)
    && (onOff === "all" || c.on === (onOff === "on")) && (fails === "all" || c.ifFails === fails));
  const onCount = design.checks.filter((c) => c.on).length;
  const live = savedAs === "live" || design.state === "live";
  const current = design.stages[stage];

  return (
    <div className="df-page">
      <div className="df-page-head">
        <a className="ui-icon-btn" href="#/inbox" aria-label="Back to the Inbox"><Icon name="chevronLeft" size={14} stroke={2.4} /></a>
        <h2 style={{ fontSize: 20 }}>{design.name}</h2>
        <Chip tone={live ? "ok" : "mute"}>{live ? "Live · version " + design.version : "Draft · version " + design.version}</Chip>
        <span className="df-muted">{design.folder} · {design.docType}</span>
        <span className="df-grow" />
        <Button>Test with a sample</Button>
        <Button onClick={() => setSavedAs("draft")}>{savedAs === "draft" ? "Draft saved" : "Save draft"}</Button>
        <Button kind="run" disabled={live} onClick={() => setSavedAs("live")}>{live ? "Live" : "Go live"}</Button>
      </div>

      <div role="tablist" aria-label="Stages" style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {design.stages.map((s, i) => (
          <button key={s.name} type="button" role="tab" className="df-stage" aria-selected={i === stage} onClick={() => setStage(i)}>
            <span className={`df-stage-n${s.done ? " is-done" : i === stage ? " is-current" : ""}`}>{s.done ? "✓" : i + 1}</span>
            <span style={{ minWidth: 0 }}><b style={{ display: "block" }}>{s.name}</b><span className="df-small">{i === 3 ? `${design.checks.length} checks` : s.summary.split(";")[0]}</span></span>
          </button>
        ))}
      </div>

      <div className="df-grid df-g3">
        {stage === 3 ? (
          <section className="df-card df-span2" aria-label="Checks and lookups" style={{ overflow: "hidden" }}>
            <div className="df-head-row df-pad">
              <div><b style={{ fontSize: 15 }}>4 · Checks and lookups</b><div className="df-muted">Run on every document after its fields are read. Each check decides what happens when it fails.</div></div>
              <span className="df-grow" /><Button kind="primary" icon="plus">Add check</Button>
            </div>
            <FilterBar onClear={() => { setQ(""); setKind("all"); setOnOff("all"); setFails("all"); }} count={`${rows.length} of ${design.checks.length} checks`}>
              <SearchBox value={q} onChange={setQ} placeholder="Search checks" />
              <FilterSelect label="Kind" value={kind} onChange={setKind} options={[{ value: "all", label: "All kinds" },
                ...[...new Set(design.checks.map((c) => c.kind))].map((k) => ({ value: k, label: k }))]} />
              <FilterSelect label="On / off" value={onOff} onChange={setOnOff} options={[{ value: "all", label: "All" }, { value: "on", label: "On" }, { value: "off", label: "Off" }]} />
              <FilterSelect label="If it fails" value={fails} onChange={setFails} options={[{ value: "all", label: "Any action" },
                ...(Object.keys(IF_FAILS) as Check["ifFails"][]).map((k) => ({ value: k, label: IF_FAILS[k] }))]} />
            </FilterBar>
            <div className="df-table-wrap">
              <table className="df-table" style={{ minWidth: 720 }}>
                <thead><tr><th>On</th><th>Check</th><th>What it tests</th><th>If it fails</th><th><span className="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.id}>
                      <td><input type="checkbox" checked={c.on} aria-label={"Check on: " + c.name} onChange={(e) => setCheck(c.id, { on: e.target.checked })} /></td>
                      <td><b>{c.name}</b><div className="df-small">{c.kind}</div></td>
                      <td className="df-muted">{c.test}</td>
                      <td><select className="ui-input ui-select" aria-label={`If ${c.name} fails`} value={c.ifFails} style={{ minWidth: 170 }}
                        onChange={(e) => setCheck(c.id, { ifFails: e.target.value as Check["ifFails"] })}>
                        {(Object.keys(IF_FAILS) as Check["ifFails"][]).map((k) => <option key={k} value={k}>{IF_FAILS[k]}</option>)}
                      </select></td>
                      <td className="df-right"><Button kind="plain">Edit</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!rows.length && <div className="df-empty">No check matches these filters.</div>}
            </div>
          </section>
        ) : (
          <section className="df-card df-pad df-span2" aria-label={current.name}>
            <b style={{ fontSize: 15 }}>{stage + 1} · {current.name}</b>
            <p className="df-muted">{current.summary}</p>
            <p className="df-small">This stage's settings come in the next build; the checks stage shows the pattern each stage follows.</p>
          </section>
        )}

        <section className="df-card df-pad" aria-label="Process summary" style={{ alignSelf: "start" }}>
          <b style={{ fontSize: 15 }}>Process summary</b>
          <div className="df-muted" style={{ margin: "2px 0 6px" }}>What this process does, stage by stage.</div>
          {design.stages.map((s, i) => (
            <div key={s.name} style={{ display: "grid", gridTemplateColumns: "24px minmax(0, 1fr)", gap: "2px 10px", padding: "10px 0", borderBottom: "1px solid var(--ui-divider)" }}>
              <span className={`df-stage-n${s.done ? " is-done" : i === stage ? " is-current" : ""}`} style={{ width: 22, height: 22, fontSize: 11 }}>{s.done ? "✓" : i + 1}</span>
              <b>{s.name}</b><span /><span className="df-muted">{i === 3 ? `${onCount} of ${design.checks.length} checks on` : s.summary}</span>
            </div>
          ))}
          <div className="df-note df-warn" style={{ marginTop: 12 }}>Before going live: run a test with 1–3 sample invoices to see what each stage would do.</div>
        </section>
      </div>
    </div>
  );
}
