import { Button, Field, Select } from "../../components";
import type { Engine, Routing } from "../api";
import { HINTS } from "../help";
import { Chip } from "../ui";

/** The retry thresholds offered, and the saved one when it is another (set in DocFlow), so the list shows what is saved. */
const RETRY = [0.9, 0.85, 0.8, 0.75, 0.7];
const retryOptions = (saved: number) => [
  ...[...new Set([...RETRY, ...(saved > 0 ? [saved] : [])])].sort((a, b) => b - a)
    .map((v) => ({ value: String(v), label: `${Math.round(v * 100)}% confidence${v === 0.85 ? " (recommended)" : ""}` })),
  { value: "0", label: "Never retry" },
];

/** How a page is routed (the strategy plan): free local engines first, paid cloud engines only for weak pages. */
export function RoutingSection({ routing, cloudEngines, saved, onChange, onSave }:
  { routing: Routing; cloudEngines: Engine[]; saved: boolean; onChange: (r: Routing) => void; onSave: () => void }) {
  const pct = Math.round(routing.retryBelow * 100);
  const cloud = cloudEngines.find((e) => e.id === routing.cloudEngine);
  const steps = [
    { n: "1 · ALWAYS", title: "Text layer", text: "Digital PDFs: read directly, no OCR", chip: <Chip tone="ok">PDF text · Ready</Chip>, color: "#4b5565" },
    { n: "2 · LOCAL", title: "Read every page", text: "On this server, free", chip: <Chip tone="ok">Tesseract · Ready</Chip>, color: "#2f6fde" },
    { n: routing.localRetry ? "3 · LOCAL" : "3 · SKIPPED", title: `Retry pages under ${pct}%`, text: "The better read wins",
      chip: routing.localRetry ? <Chip tone="ok">PaddleOCR · Ready</Chip> : <Chip tone="mute">Off</Chip>, color: "#2f6fde", off: !routing.localRetry },
    { n: routing.cloudOptIn && routing.cloudEngine ? "4 · CLOUD" : "4 · CLOUD · SKIPPED", title: "Still weak: cloud OCR", text: "Costs per page",
      chip: !routing.cloudOptIn ? <Chip tone="mute">Needs cloud opt-in</Chip> : cloud
        ? <Chip tone={cloud.status === "ready" ? "ok" : "warn"}>{cloud.name.split(" ")[0]} · {cloud.status === "ready" ? "Ready" : "not set up"}</Chip> : <Chip tone="mute">No engine chosen</Chip>,
      color: "#8b6ae8", off: !routing.cloudOptIn || !routing.cloudEngine },
    { n: "5 · LOCAL", title: "Read the fields", text: "Rules from the document type", chip: <Chip tone="ok">Rules · Ready</Chip>, color: "#2f6fde" },
    { n: "6 · ALWAYS", title: "Checks and review", text: `Under ${pct}% or a failed check`, chip: <a className="df-chip df-info" href="#/inbox">Inbox</a>, color: "#4b5565" },
  ];
  return (
    <section className="df-card df-pad" aria-label="How a page is routed" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div><h3 className="df-section-title">How a page is routed</h3>
        <p className="df-muted" style={{ margin: "2px 0 0" }}>Free local engines first. Paid cloud engines only for weak pages, and only when the client has opted in.</p></div>
      <ol className="df-grid df-g6" style={{ listStyle: "none", padding: 0, margin: 0 }}>
        {steps.map((s) => (
          <li key={s.n} className={`df-step${s.off ? " is-off" : ""}`} style={{ borderTopColor: s.color }}>
            <div className="df-step-n">{s.n}</div><b style={{ display: "block", margin: "2px 0" }}>{s.title}</b>
            <div className="df-small">{s.text}</div><div style={{ marginTop: 8 }}>{s.chip}</div>
          </li>
        ))}
      </ol>
      <div className="df-grid" style={{ gridTemplateColumns: "repeat(3, minmax(0, 1fr)) auto", alignItems: "end", paddingTop: 14, borderTop: "1px solid var(--ui-divider)" }}>
        <Field label="Retry pages below" info={HINTS.retryBelow}>{(id) => <Select id={id} value={String(routing.retryBelow)} onChange={(e) => onChange({ ...routing, retryBelow: Number(e.target.value) })}
          options={retryOptions(routing.retryBelow)} />}</Field>
        <Field label="Local retry engine" info={HINTS.localRetry}>{(id) => <Select id={id} value={routing.localRetry} onChange={(e) => onChange({ ...routing, localRetry: e.target.value })}
          options={[{ value: "paddleocr", label: "PaddleOCR" }, { value: "", label: "None" }]} />}</Field>
        <Field label="Cloud engine for pages still weak" info={HINTS.cloudEngine}>{(id) => <Select id={id} value={routing.cloudEngine} onChange={(e) => onChange({ ...routing, cloudEngine: e.target.value })}
          options={[{ value: "", label: "None" }, ...cloudEngines.map((e) => ({ value: e.id, label: e.name }))]} />}</Field>
        <Button kind="primary" onClick={onSave}>{saved ? "Saved" : "Save routing"}</Button>
      </div>
    </section>
  );
}
