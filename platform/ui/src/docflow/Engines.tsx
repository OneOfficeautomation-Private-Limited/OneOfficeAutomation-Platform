import { useEffect, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { estimate, type DocFlowApi, type EngineOption, type Routing } from "./api";
import { Chip, FilterBar, FilterSelect, Loading, SearchBox, matches, useLoad } from "./ui";

const STATUS = { ready: { tone: "ok", label: "Ready" }, optin: { tone: "mute", label: "Needs cloud opt-in" }, setup: { tone: "warn", label: "Not set up" } } as const;

/** Engines: how a page is routed (strategy plan), cloud opt-in, the three options one at a time, and the monthly cost. */
export function Engines({ api, onOptIn }: { api: DocFlowApi; onOptIn?: (on: boolean) => void }) {
  const [data, reload] = useLoad(() => api.engines(), [api]);
  const [routing, setRouting] = useState<Routing | null>(null);
  const [optionId, setOptionId] = useState<EngineOption["id"]>("onecore");
  const [confirmOptIn, setConfirmOptIn] = useState(false);
  const [saved, setSaved] = useState(false);
  useEffect(() => { if (data) setRouting(data.routing); }, [data]);
  if (!data || !routing) return <Loading />;

  const save = async (r: Routing) => { await api.saveRouting(r); setSaved(true); onOptIn?.(r.cloudOptIn); reload(); };
  const setOptIn = (on: boolean) => { const r = { ...routing, cloudOptIn: on }; setRouting(r); void save(r); };
  const option = data.options.find((o) => o.id === optionId)!;
  const cloudEngines = data.options.find((o) => o.id === "oneconnect")!.engines.filter((e) => e.id !== "abbyy");
  const pct = Math.round(routing.retryBelow * 100);
  const steps = [
    { n: "1 · ALWAYS", title: "Text layer", text: "Digital PDFs: read directly, no OCR", chip: <Chip tone="ok">PDF text · Ready</Chip>, color: "#4b5565" },
    { n: "2 · LOCAL", title: "Read every page", text: "On this server, free", chip: <Chip tone="ok">Tesseract · Ready</Chip>, color: "#2f6fde" },
    { n: routing.localRetry ? "3 · LOCAL" : "3 · SKIPPED", title: `Retry pages under ${pct}%`, text: "The better read wins",
      chip: routing.localRetry ? <Chip tone="ok">PaddleOCR · Ready</Chip> : <Chip tone="mute">Off</Chip>, color: "#2f6fde", off: !routing.localRetry },
    { n: routing.cloudOptIn && routing.cloudEngine ? "4 · CLOUD" : "4 · CLOUD · SKIPPED", title: "Still weak: cloud OCR", text: "Costs per page",
      chip: !routing.cloudOptIn ? <Chip tone="mute">Needs cloud opt-in</Chip> : routing.cloudEngine
        ? <Chip tone="warn">{cloudEngines.find((e) => e.id === routing.cloudEngine)?.name.split(" ")[0]} · key not set</Chip> : <Chip tone="mute">No engine chosen</Chip>,
      color: "#8b6ae8", off: !routing.cloudOptIn || !routing.cloudEngine },
    { n: "5 · LOCAL", title: "Read the fields", text: "Rules from the document type", chip: <Chip tone="ok">Rules · Ready</Chip>, color: "#2f6fde" },
    { n: "6 · ALWAYS", title: "Checks and review", text: `Under ${pct}% or a failed check`, chip: <a className="df-chip df-info" href="#/review/scan_0457">Task inbox</a>, color: "#4b5565" },
  ];

  return (
    <div className="df-page">
      <section className="df-card df-pad" aria-label="How a page is routed">
        <div className="df-head-row">
          <div><h2 style={{ margin: 0, fontSize: 16 }}>How a page is routed</h2>
            <p className="df-muted" style={{ margin: "2px 0 0" }}>Free local engines first. Paid cloud engines only for weak pages, and only when the client has opted in.</p></div>
          <span className="df-grow" />
          <b id="df-optin-label">Cloud opt-in</b>
          <button type="button" role="switch" className="df-switch" aria-checked={routing.cloudOptIn} aria-labelledby="df-optin-label"
            onClick={() => routing.cloudOptIn ? setOptIn(false) : setConfirmOptIn(true)} />
          <span className="df-muted">{routing.cloudOptIn ? "On" : "Off"}</span>
        </div>
        <ol className="df-grid df-g6" style={{ listStyle: "none", padding: 0, margin: "14px 0 0" }}>
          {steps.map((s) => (
            <li key={s.n} className={`df-step${s.off ? " is-off" : ""}`} style={{ borderTopColor: s.color }}>
              <div className="df-step-n">{s.n}</div><b style={{ display: "block", margin: "2px 0" }}>{s.title}</b>
              <div className="df-small">{s.text}</div><div style={{ marginTop: 8 }}>{s.chip}</div>
            </li>
          ))}
        </ol>
        <div className="df-grid" style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr)) auto", alignItems: "end", marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--ui-divider)" }}>
          <Field label="Retry pages below">{(id) => <Select id={id} value={String(routing.retryBelow)} onChange={(e) => { setSaved(false); setRouting({ ...routing, retryBelow: Number(e.target.value) }); }}
            options={[{ value: "0.85", label: "85% confidence (plan)" }, { value: "0.8", label: "80%" }, { value: "0.9", label: "90%" }, { value: "0", label: "Never retry" }]} />}</Field>
          <Field label="Local retry engine">{(id) => <Select id={id} value={routing.localRetry} onChange={(e) => { setSaved(false); setRouting({ ...routing, localRetry: e.target.value }); }}
            options={[{ value: "paddleocr", label: "PaddleOCR" }, { value: "", label: "None" }]} />}</Field>
          <Field label="Cloud engine for pages still weak">{(id) => <Select id={id} value={routing.cloudEngine} onChange={(e) => { setSaved(false); setRouting({ ...routing, cloudEngine: e.target.value }); }}
            options={[{ value: "", label: "None" }, ...cloudEngines.map((e) => ({ value: e.id, label: e.name }))]} />}</Field>
          <Button kind="primary" onClick={() => save(routing)}>{saved ? "Saved" : "Save routing"}</Button>
        </div>
      </section>

      <div className="df-grid df-g3">
        <div className="df-span2" style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0 }}>
          <div role="tablist" aria-label="Engine options" className="df-grid df-g3" style={{ gap: 8 }}>
            {data.options.map((o) => {
              const ready = o.engines.filter((e) => e.status === "ready").length;
              return (
                <button key={o.id} type="button" role="tab" className="df-option" aria-selected={o.id === optionId} style={{ borderTopColor: o.color }}
                  onClick={() => setOptionId(o.id)}>
                  <span style={{ display: "flex", width: "100%", alignItems: "center" }}><b style={{ fontSize: 15 }}>{o.name}</b><span className="df-grow" />
                    <span className="df-small" style={{ fontWeight: 600 }}>{o.id === "onecore" ? `${ready} of ${o.engines.length} ready` : `${ready} of ${o.engines.length} in use`}</span></span>
                  <span className="df-small">{o.description}</span>
                </button>
              );
            })}
          </div>
          <EngineTable key={option.id} option={option} />
        </div>
        <CostCard optIn={routing.cloudOptIn} />
      </div>

      <Dialog open={confirmOptIn} onClose={() => setConfirmOptIn(false)} title="Turn on cloud opt-in?" width={520}
        footer={<><Button onClick={() => setConfirmOptIn(false)}>Cancel</Button><Button kind="primary" onClick={() => { setConfirmOptIn(false); setOptIn(true); }}>Turn on</Button></>}>
        <p style={{ margin: 0 }}>Weak pages and field extraction may then be sent to the cloud engines set up in OneConnect and OneMind.</p>
        <p style={{ margin: 0 }} className="df-text-warn"><b>Only turn this on for clients who agreed to it in their contract.</b></p>
      </Dialog>
    </div>
  );
}

function EngineTable({ option }: { option: EngineOption }) {
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"all" | "ready" | "optin" | "setup">("all");
  const [runs, setRuns] = useState<"all" | "server" | "cloud">("all");
  const [price, setPrice] = useState<"all" | "free" | "paid">("all");
  const rows = option.engines.filter((e) => matches(q, e.name, e.does) && (status === "all" || e.status === status)
    && (runs === "all" || e.runs === runs) && (price === "all" || (price === "free") === e.free));
  return (
    <section className="df-card" aria-label={option.name + " engines"} style={{ overflow: "hidden" }}>
      <FilterBar onClear={() => { setQ(""); setStatus("all"); setRuns("all"); setPrice("all"); }} count={`${rows.length} of ${option.engines.length}`}>
        <SearchBox value={q} onChange={setQ} placeholder="Search engines" />
        <FilterSelect label="Status" value={status} onChange={setStatus} options={[{ value: "all", label: "Any status" }, { value: "ready", label: "Ready" },
          { value: "optin", label: "Needs cloud opt-in" }, { value: "setup", label: "Not set up" }]} />
        <FilterSelect label="Runs" value={runs} onChange={setRuns} options={[{ value: "all", label: "Anywhere" }, { value: "server", label: "This server" }, { value: "cloud", label: "Cloud" }]} />
        <FilterSelect label="Price" value={price} onChange={setPrice} options={[{ value: "all", label: "Any price" }, { value: "free", label: "Free" }, { value: "paid", label: "Paid" }]} />
      </FilterBar>
      <div className="df-table-wrap">
        <table className="df-table" style={{ minWidth: 680 }}>
          <thead><tr><th>Engine</th><th>Status</th><th>Runs</th><th className="df-right">Per 1,000 pages</th><th>Key</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id}>
                <td><b>{e.name}</b><div className="df-small">{e.does}</div></td>
                <td><Chip tone={STATUS[e.status].tone}>{STATUS[e.status].label}</Chip></td>
                <td className="df-muted">{e.runs === "server" ? "This server" : "Cloud"}</td>
                <td className="df-right df-num">{e.price}</td>
                <td className="df-muted">{e.key}</td>
                <td className="df-right" style={{ whiteSpace: "nowrap" }}><Button>Test</Button> <Button kind="plain">Configure</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && <div className="df-empty">No engine matches these filters.</div>}
      </div>
    </section>
  );
}

const money = (v: number) => "$" + v.toLocaleString("en", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** The plan's cost model: 10,000 pages, 20% weak pages to cloud OCR, 30% with fields gives $33 a month. */
function CostCard({ optIn }: { optIn: boolean }) {
  const [pages, setPages] = useState("10000");
  const [cloud, setCloud] = useState("20");
  const [fields, setFields] = useState("30");
  const [ocr, setOcr] = useState("azure-di");
  const [extract, setExtract] = useState("azure-di");
  const e = estimate(Number(pages) || 0, (Number(cloud) || 0) / 100, (Number(fields) || 0) / 100, ocr, extract);
  return (
    <section className="df-card df-pad" aria-label="Monthly cost estimate" style={{ display: "flex", flexDirection: "column", gap: 12, alignSelf: "start" }}>
      <div className="df-head-row"><b style={{ fontSize: 15 }}>Monthly cost estimate</b><span className="df-grow" /><Chip tone="mute" dot={false}>list prices</Chip></div>
      <Field label="Pages per month">{(id) => <TextInput id={id} inputMode="numeric" value={pages} onChange={(x) => setPages(x.target.value)} />}</Field>
      <div className="df-grid df-g2" style={{ gap: 10 }}>
        <Field label="Weak pages to cloud (%)">{(id) => <TextInput id={id} inputMode="numeric" value={cloud} onChange={(x) => setCloud(x.target.value)} />}</Field>
        <Field label="Pages with fields (%)">{(id) => <TextInput id={id} inputMode="numeric" value={fields} onChange={(x) => setFields(x.target.value)} />}</Field>
      </div>
      <Field label="Cloud OCR by">{(id) => <Select id={id} value={ocr} onChange={(x) => setOcr(x.target.value)}
        options={[{ value: "azure-di", label: "Azure Document Intelligence" }, { value: "google-docai", label: "Google Document AI" }, { value: "aws-textract", label: "AWS Textract" }]} />}</Field>
      <Field label="Field extraction by">{(id) => <Select id={id} value={extract} onChange={(x) => setExtract(x.target.value)}
        options={[{ value: "azure-di", label: "Azure prebuilt invoice" }, { value: "aws-textract", label: "AWS AnalyzeExpense" }, { value: "claude-sonnet-5-5", label: "Claude Sonnet 5.5" },
          { value: "claude-opus-5-5", label: "Claude Opus 5.5" }, { value: "claude-haiku-5-5", label: "Claude Haiku 5.5" }, { value: "builtin-rules", label: "Local rules (free)" }]} />}</Field>
      <table className="df-table">
        <tbody>
          <tr><td style={{ paddingLeft: 0 }}>Local OCR · {e.localPages.toLocaleString("en")} pages</td><td className="df-right df-num" style={{ paddingRight: 0 }}>{money(0)}</td></tr>
          <tr><td style={{ paddingLeft: 0 }}>Cloud OCR · {e.cloudPages.toLocaleString("en")} × {money(e.ocrRate)}/1,000</td><td className="df-right df-num" style={{ paddingRight: 0 }}>{money(e.ocr)}</td></tr>
          <tr><td style={{ paddingLeft: 0 }}>Fields · {e.fieldPages.toLocaleString("en")} × {money(e.fieldRate)}/1,000</td><td className="df-right df-num" style={{ paddingRight: 0 }}>{money(e.fields)}</td></tr>
        </tbody>
      </table>
      <div className="df-head-row" style={{ fontWeight: 700 }}><span>Total per month</span><span className="df-grow" /><span className="df-num" style={{ fontSize: 18 }} aria-live="polite">{money(e.total)}</span></div>
      {optIn
        ? <div className="df-note df-info">Cloud opt-in is on, so this is what the current routing would cost.</div>
        : <div className="df-note df-ok">Today this costs <b>$0</b>: cloud opt-in is off, so every page stays on this server.</div>}
      <div className="df-small">Plus the server for local engines. Approximate list prices; check vendor pricing before quoting a client.</div>
    </section>
  );
}
