import { useRef, useState } from "react";
import { Button, Field, Select } from "../components";
import type { DocFlowApi, DocType, TestRun } from "./api";
import { FilterBar, FilterSelect, Kpi, reportError, useLoad } from "./ui";

/**
 * Test lab: try a document on one engine, or two side by side, before it goes live. Nothing is saved or sent to review.
 * In the builder's last step it opens on the process's document type (by id or name), without its own title.
 */
export function TestLab({ api, initialType = "", embedded = false }: { api: DocFlowApi; initialType?: string; embedded?: boolean }) {
  const [setup] = useLoad(() => Promise.all([api.docTypes(), api.engines()]), [api]);
  const picker = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [docType, setDocType] = useState("");
  const [compare, setCompare] = useState(true);
  const [a, setA] = useState("auto");
  const [b, setB] = useState("tesseract");
  const [result, setResult] = useState<TestRun | null>(null);
  const [running, setRunning] = useState(false);
  const [show, setShow] = useState<"all" | "diff" | "failed">("all");
  const types: DocType[] = setup?.[0] ?? [];
  const typeId = docType || types.find((t) => t.id === initialType || t.name === initialType)?.id || types[0]?.id || "";
  // engines that read pages and can run now, plus "as live" (the routing on the Engines page)
  const engines = [{ value: "auto", label: "Routing (as live)" }, ...(setup?.[1].options ?? []).flatMap((o) => o.engines)
    .filter((e) => e.status === "ready" && !["builtin-rules", "llm-local", "llm-cloud", "claude"].includes(e.id)).map((e) => ({ value: e.id, label: e.name }))];
  const name = (v: string) => engines.find((e) => e.value === v)?.label ?? v;

  const run = async () => {
    if (!file) return;
    setRunning(true);
    try { setResult(await api.runTest(file, typeId, a, compare ? b : null)); } catch (e) { reportError(e); } finally { setRunning(false); }
  };
  const results = result?.rows ?? [];
  const rows = results.filter((r) => show === "all" || (show === "diff" ? r.differs : /fail|missing|must|invalid/i.test(r.check)));
  const found = (side: "a" | "b") => results.filter((r) => (side === "a" ? r.a : r.b) !== "—" && !(side === "a" ? r.a : r.b).startsWith("not")).length;
  const [ra, rb] = result?.runs ?? [];
  const both = (fn: (x: NonNullable<typeof ra>) => string) => compare && rb ? `A ${ra ? fn(ra) : "—"} · B ${fn(rb)}` : ra ? fn(ra) : "—";

  return (
    <div className={embedded ? "df-stack" : "df-page"}>
      <div className="df-page-head">
        {!embedded && <div><h2>Test lab</h2><p>Try a document before it goes live. Nothing is saved to a process or sent to review.</p></div>}
        <span className="df-grow" />
        <div className="df-seg" role="group" aria-label="Mode">
          <button type="button" aria-pressed={!compare} onClick={() => { setCompare(false); setResult(null); }}>One engine</button>
          <button type="button" aria-pressed={compare} onClick={() => { setCompare(true); setResult(null); }}>Compare two</button>
        </div>
      </div>

      <section className="df-card df-pad" aria-label="Set up the test">
        <div className="df-grid" style={{ gridTemplateColumns: `minmax(0, 1.4fr) repeat(${compare ? 3 : 2}, minmax(0, 1fr)) auto`, alignItems: "end", gap: 12 }}>
          <div className="ui-field">
            <span className="ui-field-label">Document</span>
            <input ref={picker} type="file" hidden accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff" aria-label="Choose a document"
              onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
            <Button onClick={() => picker.current?.click()}>{file ? file.name : "Choose a document…"}</Button>
          </div>
          <Field label="Document type">{(id) => <Select id={id} value={typeId} onChange={(e) => setDocType(e.target.value)} options={types.map((t) => ({ value: t.id, label: t.name }))} />}</Field>
          <Field label={compare ? "Engine A" : "Engine"}>{(id) => <Select id={id} value={a} onChange={(e) => setA(e.target.value)} options={engines} />}</Field>
          {compare && <Field label="Engine B">{(id) => <Select id={id} value={b} onChange={(e) => setB(e.target.value)} options={engines} />}</Field>}
          <Button kind="primary" disabled={running || !file || !typeId} onClick={run}>{running ? "Reading…" : "Run test"}</Button>
        </div>
      </section>

      {result && (
        <>
          {result.runs.filter((x) => x.error).map((x) => <div key={x.engine} className="df-alert" role="alert"><b>{name(x.engine)} could not read it.</b> {x.error}</div>)}
          <div className="df-grid df-g4">
            <Kpi label="Fields found" value={compare && rb ? `A ${found("a")} of ${results.length} · B ${found("b")} of ${results.length}` : `${found("a")} of ${results.length}`} />
            <Kpi label="Page confidence" value={both((x) => (x.confidence === null ? "—" : x.confidence + "%"))} />
            <Kpi label="Time" value={both((x) => x.seconds.toFixed(1) + " s")} />
            <Kpi label="Would go to" value={<span style={{ fontSize: 16 }}>{both((x) => (x.error ? "—" : x.review ? "review" : "straight through"))}</span>} />
          </div>
          <section className="df-card" aria-label="Field results" style={{ overflow: "hidden" }}>
            <FilterBar count={`${rows.length} of ${results.length} fields`}>
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
            <p className="df-small df-pad" style={{ margin: 0 }}>Read by: {result.runs.map((x) => `${compare && rb ? (x === ra ? "A " : "B ") : ""}${x.readBy}`).join(" · ")}</p>
          </section>
        </>
      )}
      {!result && <div className="df-card df-empty">Choose a document, its type and {compare ? "two engines" : "an engine"}, then Run test.</div>}
    </div>
  );
}
