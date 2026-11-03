import { useState } from "react";
import { Button, Field, Select } from "../components";
import type { DocFlowApi, TestResult } from "./api";
import { FilterBar, FilterSelect, Kpi } from "./ui";

const ENGINES = [{ value: "tesseract", label: "Tesseract 5" }, { value: "paddleocr", label: "PaddleOCR" }, { value: "routing", label: "Routing (as live)" }];

/** Test lab: try a document on one engine, or two side by side, before it goes live. Nothing is saved or sent to review. */
export function TestLab({ api }: { api: DocFlowApi }) {
  const [compare, setCompare] = useState(true);
  const [a, setA] = useState("tesseract");
  const [b, setB] = useState("paddleocr");
  const [results, setResults] = useState<TestResult[] | null>(null);
  const [running, setRunning] = useState(false);
  const [show, setShow] = useState<"all" | "diff" | "failed">("all");
  const name = (v: string) => ENGINES.find((e) => e.value === v)?.label ?? v;

  const run = async () => { setRunning(true); setResults(await api.runTest("fax_0931.png", a, compare ? b : null)); setRunning(false); };
  const rows = (results ?? []).filter((r) => show === "all" || (show === "diff" ? r.differs : /fail|missing/.test(r.check)));
  const right = (side: "a" | "b") => (results ?? []).filter((r) => !(side === "a" ? r.differs : false) && (side === "a" ? r.a !== "—" : r.b)).length;

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Test lab</h2><p>Try a document before it goes live. Nothing is saved to a process or sent to review.</p></div>
        <span className="df-grow" />
        <div className="df-seg" role="group" aria-label="Mode">
          <button type="button" aria-pressed={!compare} onClick={() => { setCompare(false); setResults(null); }}>One engine</button>
          <button type="button" aria-pressed={compare} onClick={() => { setCompare(true); setResults(null); }}>Compare two</button>
        </div>
      </div>

      <section className="df-card df-pad" aria-label="Set up the test">
        <div className="df-grid" style={{ gridTemplateColumns: `minmax(0, 1.4fr) repeat(${compare ? 3 : 2}, minmax(0, 1fr)) auto`, alignItems: "end", gap: 12 }}>
          <Field label="Sample document">{(id) => <Select id={id} options={[{ value: "fax", label: "fax_0931.png · 1 page" }, { value: "scan", label: "scan_0457.jpg · 1 page" }]} />}</Field>
          <Field label="Document type">{(id) => <Select id={id} options={[{ value: "inv", label: "Supplier invoice" }, { value: "rec", label: "Receipt" }]} />}</Field>
          <Field label={compare ? "Engine A" : "Engine"}>{(id) => <Select id={id} value={a} onChange={(e) => setA(e.target.value)} options={ENGINES} />}</Field>
          {compare && <Field label="Engine B">{(id) => <Select id={id} value={b} onChange={(e) => setB(e.target.value)} options={ENGINES} />}</Field>}
          <Button kind="primary" disabled={running} onClick={run}>{running ? "Running…" : "Run test"}</Button>
        </div>
      </section>

      {results && (
        <>
          <div className="df-grid df-g4">
            <Kpi label="Fields correct" value={compare ? `A ${right("a")} of ${results.length} · B ${right("b")} of ${results.length}` : `${right("a")} of ${results.length}`} />
            <Kpi label="Page confidence" value={compare ? "A 78% · B 98%" : "78%"} />
            <Kpi label="Time" value={compare ? "A 0.6 s · B 7.3 s" : "0.6 s"} />
            <Kpi label="Would go to" value={<span style={{ fontSize: 16 }}>{compare ? "A: review · B: straight through" : "Review (2 issues)"}</span>} />
          </div>
          <section className="df-card" aria-label="Field results" style={{ overflow: "hidden" }}>
            <FilterBar count={`${rows.length} of ${results.length} fields`} actions={<Button>Save as test case</Button>}>
              <b>Field results</b>
              <FilterSelect label="Show" value={show} onChange={setShow} options={[{ value: "all", label: "All fields" },
                ...(compare ? [{ value: "diff" as const, label: "Only differences" }] : []), { value: "failed", label: "Only failed checks" }]} />
            </FilterBar>
            <div className="df-table-wrap">
              <table className="df-table" style={{ minWidth: 760 }}>
                <thead><tr><th>Field</th><th>{compare ? "Engine A · " : ""}{name(a)}</th>{compare && <th>Engine B · {name(b)}</th>}<th>Check</th></tr></thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.field} className={r.differs ? "df-row-flag" : undefined}>
                      <td><b>{r.field}</b></td>
                      <td><span className={`df-num${r.differs ? " df-text-bad" : ""}`} style={{ fontWeight: r.differs ? 600 : 400 }}>{r.a}</span> <span className="df-small">{r.aConf}</span></td>
                      {compare && <td><span className="df-num">{r.b}</span> <span className="df-small">{r.bConf}</span></td>}
                      <td className="df-muted">{r.check}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
      {!results && <div className="df-card df-empty">Choose a sample and {compare ? "two engines" : "an engine"}, then Run test.</div>}
    </div>
  );
}
