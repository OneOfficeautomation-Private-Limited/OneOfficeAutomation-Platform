import { useRef, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import type { DocFlowApi, DocType, TestRun, TestSet } from "./api";
import { SetDetail } from "./testsets/SetDetail";
import { SetList } from "./testsets/SetList";
import { READABLE_FILES } from "./formats";
import { AdvancedToggle, FilterBar, FilterSelect, Kpi, reportError, useLoad, dfHash } from "./ui";

export const TESTLAB_SECTIONS = [{ id: "try", label: "Try one document" }, { id: "sets", label: "Test sets" }] as const;

/**
 * Test lab, in two sections: Try one document (one engine, or two side by side; nothing is saved) and Test sets
 * (documents whose right values are known, run on engine combinations). #/testlab/<section>[/<set>].
 * In the builder's last step only Try one document shows, on the process's document type, without its own title.
 */
export function TestLab({ api, initialType = "", embedded = false, section, item }:
  { api: DocFlowApi; initialType?: string; embedded?: boolean; section?: string; item?: string }) {
  if (embedded) return <TryOne api={api} initialType={initialType} embedded />;
  const sets = section === "sets";
  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Test lab</h2><p>{sets ? "Run engines on documents whose right values you know, and compare them before you change anything."
          : <>Compare engines on a document before you change anything. Nothing is saved to a process or sent to review. To try a document the way a
            process handles it (its checks, review and deliveries), use that process's <a href={dfHash() + "processes"}>step 8</a>.</>}</p></div>
      </div>
      <nav className="df-seg df-seg-wide" aria-label="Test lab sections">
        {TESTLAB_SECTIONS.map((s) => (
          <a key={s.id} href={dfHash() + "testlab/" + s.id} aria-current={(s.id === "sets") === sets ? "page" : undefined}>{s.label}</a>
        ))}
      </nav>
      {sets ? (item ? <SetDetail key={item} api={api} setId={item} /> : <SetList api={api} />) : <TryOne api={api} initialType={initialType} embedded toSets />}
    </div>
  );
}

/** Try one document: one engine, or two side by side. */
function TryOne({ api, initialType = "", embedded = false, toSets = false }: { api: DocFlowApi; initialType?: string; embedded?: boolean;
  /** Offer Add to a test set after a run (the Test lab, not a process's step 8). */
  toSets?: boolean }) {
  const [setup] = useLoad(() => Promise.all([api.docTypes(), api.engines()]), [api]);
  const picker = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [docType, setDocType] = useState("");
  const [compare, setCompare] = useState(false);
  const [advanced, setAdvanced] = useState(false);   // DA-1: engines and comparing are advanced
  const [a, setA] = useState("auto");
  const [b, setB] = useState("tesseract");
  // what is compared: the engines that read the page, or the ones that read the fields (DocFlow 3.4.2)
  const [what, setWhat] = useState<"pages" | "fields">("pages");
  const [saving, setSaving] = useState<TestSet[] | null>(null);   // Add to a test set: the sets of this type
  const [result, setResult] = useState<TestRun | null>(null);
  const [running, setRunning] = useState(false);
  const [show, setShow] = useState<"all" | "diff" | "failed">("all");
  const types: DocType[] = setup?.[0] ?? [];
  const typeId = docType || types.find((t) => t.id === initialType || t.name === initialType)?.id || types[0]?.id || "";
  // the engines that can run now for what is compared (by what each does, not by name), plus the default
  // page readers are the on-server and cloud document engines; field readers the AI models and the Rules extractor
  const options = setup?.[1].options ?? [];
  const ok = (e: { status: string; can?: string[] }) => e.status === "ready" && !e.can?.includes("split");
  const pageReaders = options.filter((o) => o.id !== "onemind").flatMap((o) => o.engines).filter((e) => ok(e) && !["builtin-rules", "abbyy"].includes(e.id));
  const fieldReaders = options.flatMap((o) => o.engines).filter((e) => ok(e) && (e.id === "builtin-rules" || options.find((o) => o.id === "onemind")?.engines.includes(e)));
  const engines = [{ value: "auto", label: what === "pages" ? "As the process would read it" : "The default field engine" },
    ...(what === "pages" ? pageReaders : fieldReaders).map((e) => ({ value: e.id, label: e.name }))];
  const name = (v: string) => engines.find((e) => e.value === v)?.label ?? v;

  const run = async () => {
    if (!file) return;
    setRunning(true);
    try { setResult(await api.runTest(file, typeId, a, compare ? b : null, what)); } catch (e) { reportError(e); } finally { setRunning(false); }
  };
  const results = result?.rows ?? [];
  const rows = results.filter((r) => show === "all" || (show === "diff" ? r.differs : /fail|missing|must|invalid/i.test(r.check)));
  const found = (side: "a" | "b") => results.filter((r) => (side === "a" ? r.a : r.b) !== "—" && !(side === "a" ? r.a : r.b).startsWith("not")).length;
  const [ra, rb] = result?.runs ?? [];
  const both = (fn: (x: NonNullable<typeof ra>) => string) => compare && rb ? `A ${ra ? fn(ra) : "—"} · B ${fn(rb)}` : ra ? fn(ra) : "—";

  return (
    <div className={embedded ? "df-stack" : "df-page"}>
      <div className="df-page-head">
        {!embedded && <div><h2>Test lab</h2><p>Compare engines on a document before you change anything. Nothing is saved to a process or sent to review.
          To try a document the way a process handles it (its checks, review and deliveries), use that process's <a href={dfHash() + "processes"}>step 8</a>.</p></div>}
        <span className="df-grow" />
        {advanced && <div className="df-seg" role="group" aria-label="What is compared">
          <button type="button" aria-pressed={what === "pages"} onClick={() => { setWhat("pages"); setA("auto"); setB("tesseract"); setResult(null); }}>Reading the page</button>
          <button type="button" aria-pressed={what === "fields"} onClick={() => { setWhat("fields"); setA("auto"); setB("builtin-rules"); setResult(null); }}>Reading the fields</button>
        </div>}
        {advanced && <div className="df-seg" role="group" aria-label="Mode">
          <button type="button" aria-pressed={!compare} onClick={() => { setCompare(false); setResult(null); }}>One engine</button>
          <button type="button" aria-pressed={compare} onClick={() => { setCompare(true); setResult(null); }}>Compare two</button>
        </div>}
      </div>

      <section className="df-card df-pad" aria-label="Set up the test">
        <div className="df-grid" style={{ gridTemplateColumns: `minmax(0, 1.4fr) repeat(${advanced ? (compare ? 3 : 2) : 1}, minmax(0, 1fr)) auto`, alignItems: "end", gap: 12 }}>
          <div className="ui-field">
            <span className="ui-field-label">Document</span>
            <input ref={picker} type="file" hidden accept={READABLE_FILES} aria-label="Choose a document"
              onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
            <Button onClick={() => picker.current?.click()}>{file ? file.name : "Choose a document…"}</Button>
          </div>
          <Field label="Document type">{(id) => <Select id={id} value={typeId} onChange={(e) => setDocType(e.target.value)} options={types.map((t) => ({ value: t.id, label: t.name }))} />}</Field>
          {advanced && <Field label={compare ? "Engine A" : "Engine"}>{(id) => <Select id={id} value={a} onChange={(e) => setA(e.target.value)} options={engines} />}</Field>}
          {advanced && compare && <Field label="Engine B">{(id) => <Select id={id} value={b} onChange={(e) => setB(e.target.value)} options={engines} />}</Field>}
          <Button kind="primary" disabled={running || !file || !typeId} onClick={run}>{running ? "Reading…" : "Run test"}</Button>
        </div>
        <div style={{ marginTop: 10 }}>
          <AdvancedToggle open={advanced} onToggle={() => { if (advanced) { setCompare(false); setA("auto"); } setAdvanced(!advanced); setResult(null); }}
            what="choose the engine, or compare two engines side by side" />
        </div>
      </section>

      {result && (
        <>
          {result.runs.filter((x) => x.error).map((x) => <div key={x.engine} className="df-alert" role="alert"><b>{name(x.engine)} could not read it.</b> {x.error}</div>)}
          <div className="df-grid df-g4">
            <Kpi label="Fields found" value={compare && rb ? `A ${found("a")} of ${results.length} · B ${found("b")} of ${results.length}` : `${found("a")} of ${results.length}`} />
            <Kpi label="Page confidence" value={both((x) => (x.confidence === null ? "—" : x.confidence + "%"))} />
            <Kpi label="Time" value={both((x) => x.seconds.toFixed(1) + " s")} />
            <Kpi label="Would go to" value={<span style={{ fontSize: 16 }}>{both((x) => (x.error ? "—" : x.review ? "review" : "straight through"))}</span>}
              sub="at the default review level, not a process's" />
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
            <div className="df-pad" style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
              <p className="df-small" style={{ margin: 0, flex: 1 }}>Read by: {result.runs.map((x) => `${compare && rb ? (x === ra ? "A " : "B ") : ""}${x.readBy}`).join(" · ")}</p>
              {toSets && file && <Button onClick={() => api.testSets(typeId).then(setSaving, reportError)}>Add to a test set…</Button>}
            </div>
          </section>
        </>
      )}
      {!result && <div className="df-card df-empty">Choose a document, its type and {compare ? "two engines" : "an engine"}, then Run test.</div>}
      {saving && file && result && <SaveToSet api={api} sets={saving} file={file} rows={result.rows} onClose={() => setSaving(null)} />}
    </div>
  );
}

/**
 * Test lab (walkthrough 28 Nov 2026): the document just tried becomes a test set item; the values read are the
 * starting point for its right values, corrected here before saving.
 */
function SaveToSet({ api, sets, file, rows, onClose }: { api: DocFlowApi; sets: TestSet[]; file: File; rows: TestRun["rows"]; onClose: () => void }) {
  const [setId, setSetId] = useState(sets[0]?.id ?? "");
  const read = (v: string) => (v === "—" || v.startsWith("not") ? "" : v);
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(rows.map((r) => [r.key ?? r.field, read(r.a)])));
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await api.addTestItem(setId, file, Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim())), []);
      setDone(true);
    } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title="Add to a test set" width={600}
      footer={<><Button onClick={onClose}>{done ? "Close" : "Cancel"}</Button>{!done && <Button kind="primary" disabled={!setId || busy} onClick={save}>Add to the set</Button>}</>}>
      {done ? <p role="status" style={{ margin: 0 }}>{file.name} is in the set with these right values.</p>
        : !sets.length ? <p style={{ margin: 0 }}>No test set of this document type yet: make one in <a href={dfHash() + "testlab/sets"}>Test sets</a> first.</p> : <>
        <Field label="Test set">{(id) => <Select id={id} value={setId} onChange={(e) => setSetId(e.target.value)} options={sets.map((x) => ({ value: x.id, label: x.name }))} />}</Field>
        <p className="df-small" style={{ margin: 0 }}>The right values start as what was read: correct any that are wrong. Empty ones are left out.</p>
        <div className="df-grid df-g2" style={{ gap: 8 }}>{rows.map((r) => (
          <Field key={r.key ?? r.field} label={r.field}>{(id) => <TextInput id={id} value={values[r.key ?? r.field] ?? ""}
            onChange={(e) => setValues({ ...values, [r.key ?? r.field]: e.target.value })} />}</Field>))}
        </div>
      </>}
    </Dialog>
  );
}
