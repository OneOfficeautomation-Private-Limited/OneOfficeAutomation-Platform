import { useState } from "react";
import { Button } from "../../../components";
import type { DocFlowApi, Engine, EngineOption } from "../api";
import { Chip, FilterBar, FilterSelect, SearchBox, matches } from "../ui";
import { EngineSettings } from "./EngineSettings";

export const STATUS = { ready: { tone: "ok", label: "Ready" }, optin: { tone: "mute", label: "Needs cloud opt-in" }, setup: { tone: "warn", label: "Not set up" } } as const;
type Tested = Record<string, { ok: boolean; message: string } | "running">;

/**
 * The three engine options. Choosing one opens it across the page: what it is, how many of its engines can run, and
 * each engine as a card with its status, where it runs, its price and key, and Test / Configure.
 */
export function EngineCatalog({ api, options, optionId, optIn, onOption }:
  { api: DocFlowApi; options: EngineOption[]; optionId: EngineOption["id"]; optIn: boolean; onOption: (id: EngineOption["id"]) => void }) {
  const option = options.find((o) => o.id === optionId) ?? options[0];
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0 }}>
      <div role="tablist" aria-label="Engine options" className="df-option-row">
        {options.map((o) => {
          const ready = o.engines.filter((e) => e.status === "ready").length;
          return (
            <button key={o.id} type="button" role="tab" className="df-option" aria-selected={o.id === option.id} style={{ borderTopColor: o.color }}
              onClick={() => onOption(o.id)}>
              <span style={{ display: "flex", width: "100%", alignItems: "center", gap: 8 }}><b style={{ fontSize: 15 }}>{o.name}</b><span className="df-grow" />
                <span className="df-small" style={{ fontWeight: 600 }}>{ready} of {o.engines.length} ready</span></span>
              <span className="df-small">{o.description}</span>
            </button>
          );
        })}
      </div>
      <OptionDetail key={option.id} api={api} option={option} optIn={optIn} />
    </div>
  );
}

function OptionDetail({ api, option, optIn }: { api: DocFlowApi; option: EngineOption; optIn: boolean }) {
  const [tested, setTested] = useState<Tested>({});
  const [configuring, setConfiguring] = useState<Engine | null>(null);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"all" | "ready" | "optin" | "setup">("all");
  const [runs, setRuns] = useState<"all" | "server" | "cloud">("all");
  const [price, setPrice] = useState<"all" | "free" | "paid">("all");
  const test = (id: string) => {
    setTested((t) => ({ ...t, [id]: "running" }));
    api.testEngine(id).then((r) => setTested((t) => ({ ...t, [id]: r })), (e) => setTested((t) => ({ ...t, [id]: { ok: false, message: e instanceof Error ? e.message : String(e) } })));
  };
  const rows = option.engines.filter((e) => matches(q, e.name, e.does) && (status === "all" || e.status === status)
    && (runs === "all" || e.runs === runs) && (price === "all" || (price === "free") === e.free));
  const ready = option.engines.filter((e) => e.status === "ready").length;
  const cloud = option.engines.some((e) => e.runs === "cloud");
  return (
    <section className="df-card" aria-label={option.name + " engines"} style={{ overflow: "hidden", borderTop: `3px solid ${option.color}` }}>
      <div className="df-pad df-head-row" style={{ alignItems: "flex-start" }}>
        <div style={{ minWidth: 0 }}>
          <h3 className="df-section-title">{option.name}</h3>
          <p className="df-muted" style={{ margin: "2px 0 0" }}>{option.description}</p>
        </div>
        <span className="df-grow" />
        <Chip tone={ready ? "ok" : "mute"}>{ready} of {option.engines.length} ready</Chip>
        {cloud && !optIn && <Chip tone="mute">Cloud engines wait for cloud opt-in</Chip>}
      </div>
      <FilterBar onClear={() => { setQ(""); setStatus("all"); setRuns("all"); setPrice("all"); }} count={`${rows.length} of ${option.engines.length}`}>
        <SearchBox value={q} onChange={setQ} placeholder="Search engines" />
        <FilterSelect label="Status" value={status} onChange={setStatus} options={[{ value: "all", label: "Any status" }, { value: "ready", label: "Ready" },
          { value: "optin", label: "Needs cloud opt-in" }, { value: "setup", label: "Not set up" }]} />
        <FilterSelect label="Runs" value={runs} onChange={setRuns} options={[{ value: "all", label: "Anywhere" }, { value: "server", label: "This server" }, { value: "cloud", label: "Cloud" }]} />
        <FilterSelect label="Price" value={price} onChange={setPrice} options={[{ value: "all", label: "Any price" }, { value: "free", label: "Free" }, { value: "paid", label: "Paid" }]} />
      </FilterBar>
      <div className="df-engine-grid df-pad">
        {rows.map((e) => <EngineCard key={e.id} engine={e} tested={tested[e.id]} onTest={() => test(e.id)} onConfigure={() => setConfiguring(e)} />)}
        {!rows.length && <div className="df-empty" style={{ gridColumn: "1 / -1" }}>No engine matches these filters.</div>}
      </div>
      {configuring && <EngineSettings key={configuring.id} api={api} engine={configuring} onClose={() => setConfiguring(null)} />}
    </section>
  );
}

/** One engine: what it does, whether it can run, where, for how much, its key, and Test / Configure. */
export function EngineCard({ engine: e, tested, onTest, onConfigure }:
  { engine: Engine; tested?: Tested[string]; onTest: () => void; onConfigure: () => void }) {
  return (
    <article className="df-engine" aria-label={e.name}>
      <div className="df-head-row" style={{ alignItems: "flex-start", gap: 8 }}>
        <div style={{ minWidth: 0 }}><b>{e.name}</b><div className="df-small">{e.does}</div></div>
        <span className="df-grow" /><Chip tone={STATUS[e.status].tone}>{STATUS[e.status].label}</Chip>
      </div>
      <dl className="df-engine-facts">
        <div><dt>Runs</dt><dd>{e.runs === "server" ? "This server" : "Cloud"}</dd></div>
        <div><dt>Per 1,000 pages</dt><dd className="df-num">{e.price}</dd></div>
        <div><dt>Key</dt><dd>{e.key}</dd></div>
      </dl>
      {tested && <div className={`df-small ${tested === "running" ? "" : tested.ok ? "df-text-ok" : "df-text-bad"}`} role="status">
        {tested === "running" ? "Testing…" : tested.message}</div>}
      <div className="df-head-row" style={{ gap: 8 }}>
        <Button disabled={tested === "running"} onClick={onTest} aria-label={"Test " + e.name}>Test</Button>
        <Button kind="plain" onClick={onConfigure} aria-label={"Configure " + e.name}>Configure</Button>
      </div>
    </article>
  );
}
