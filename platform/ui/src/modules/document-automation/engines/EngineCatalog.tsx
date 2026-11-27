import { useState } from "react";
import { Button } from "../../../components";
import type { DocFlowApi, Engine, EngineOption } from "../api";
import { Chip, FilterBar, FilterSelect, SearchBox, matches } from "../ui";
import { EngineSettings } from "./EngineSettings";

/** The three options in plain words; OneCore, OneConnect and OneMind stay as their labels. */
export const PLAIN: Record<EngineOption["id"], string> = { onecore: "On this server", oneconnect: "Cloud reading", onemind: "AI reading", other: "Other engines" };

/** An engine's state in plain words: ready, off because the cloud may not be used yet, or what it still needs. */
export function engineStatus(e: Pick<Engine, "status" | "runs">): { tone: "ok" | "mute" | "warn"; label: string } {
  if (e.status === "ready") return { tone: "ok", label: "Ready" };
  if (e.status === "optin") return { tone: "mute", label: "Off: cloud not allowed" };
  return { tone: "warn", label: e.runs === "cloud" ? "Needs a key" : "Needs setting up" };
}

/** An option's state: ready (how many of its engines), off until cloud opt-in, or what it still needs. */
export function optionStatus(o: Pick<EngineOption, "engines">): { tone: "ok" | "mute" | "warn"; label: string } {
  const ready = o.engines.filter((e) => e.status === "ready").length;
  if (ready) return { tone: "ok", label: `Ready · ${ready} of ${o.engines.length}` };
  if (o.engines.some((e) => e.status === "optin")) return { tone: "mute", label: "Off: cloud not allowed" };
  return { tone: "warn", label: o.engines.some((e) => e.runs === "cloud") ? "Needs a key" : "Needs setting up" };
}
type Tested = Record<string, { ok: boolean; message: string } | "running">;

/**
 * The three engine options, in plain words (On this server, Cloud reading, AI reading) with their OneCore / OneConnect /
 * OneMind labels. Choosing one opens it across the page: what it is, whether it is ready, and each engine as a card with
 * its state, where it reads, its cost and key, and Test / Configure.
 */
export function EngineCatalog({ api, options, optionId, optIn, onOption, onChanged }:
  { api: DocFlowApi; options: EngineOption[]; optionId: EngineOption["id"]; optIn: boolean; onOption: (id: EngineOption["id"]) => void; onChanged?: () => void }) {
  const option = options.find((o) => o.id === optionId) ?? options[0];
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0 }}>
      <div role="tablist" aria-label="Engine options" className="df-option-row">
        {options.map((o) => {
          const st = optionStatus(o);
          return (
            <button key={o.id} type="button" role="tab" className="df-option" aria-selected={o.id === option.id} style={{ borderTopColor: o.color }}
              onClick={() => onOption(o.id)}>
              <span style={{ display: "flex", width: "100%", alignItems: "center", gap: 8 }}><b style={{ fontSize: 15 }}>{PLAIN[o.id]}</b>
                <span className="df-small">{o.name}</span><span className="df-grow" /><Chip tone={st.tone}>{st.label}</Chip></span>
              <span className="df-small">{o.description}</span>
            </button>
          );
        })}
      </div>
      <OptionDetail key={option.id} api={api} option={option} optIn={optIn} onChanged={onChanged} />
    </div>
  );
}

function OptionDetail({ api, option, optIn, onChanged }: { api: DocFlowApi; option: EngineOption; optIn: boolean; onChanged?: () => void }) {
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
  const st = optionStatus(option);
  const cloud = option.engines.some((e) => e.runs === "cloud");
  return (
    <section className="df-card" aria-label={option.name + " engines"} style={{ overflow: "hidden", borderTop: `3px solid ${option.color}` }}>
      <div className="df-pad df-head-row" style={{ alignItems: "flex-start" }}>
        <div style={{ minWidth: 0 }}>
          <h3 className="df-section-title">{PLAIN[option.id]} <span className="df-small" style={{ fontWeight: 400 }}>{option.name}</span></h3>
          <p className="df-muted" style={{ margin: "2px 0 0" }}>{option.description}</p>
        </div>
        <span className="df-grow" />
        <Chip tone={st.tone}>{st.label}</Chip>
        {cloud && !optIn && <Chip tone="mute">Cloud engines stay off until Cloud opt-in is on</Chip>}
      </div>
      <FilterBar onClear={() => { setQ(""); setStatus("all"); setRuns("all"); setPrice("all"); }} count={`${rows.length} of ${option.engines.length}`}>
        <SearchBox value={q} onChange={setQ} placeholder="Search engines" />
        <FilterSelect label="Status" value={status} onChange={setStatus} options={[{ value: "all", label: "Any status" }, { value: "ready", label: "Ready" },
          { value: "optin", label: "Off: cloud not allowed" }, { value: "setup", label: "Needs a key or setting up" }]} />
        <FilterSelect label="Where" value={runs} onChange={setRuns} options={[{ value: "all", label: "Anywhere" }, { value: "server", label: "On this server" }, { value: "cloud", label: "In the cloud" }]} />
        <FilterSelect label="Price" value={price} onChange={setPrice} options={[{ value: "all", label: "Any price" }, { value: "free", label: "Free" }, { value: "paid", label: "Paid" }]} />
      </FilterBar>
      <div className="df-engine-grid df-pad">
        {rows.map((e) => <EngineCard key={e.id} engine={e} tested={tested[e.id]} onTest={() => test(e.id)} onConfigure={() => setConfiguring(e)} />)}
        {!rows.length && <div className="df-empty" style={{ gridColumn: "1 / -1" }}>No engine matches these filters.</div>}
      </div>
      {configuring && <EngineSettings key={configuring.id} api={api} engine={configuring} onClose={() => setConfiguring(null)} onSaved={onChanged} />}
    </section>
  );
}

/** One engine: what it does, whether it is ready (in plain words), where it reads, its cost, its key, and Test / Configure. */
export function EngineCard({ engine: e, tested, onTest, onConfigure }:
  { engine: Engine; tested?: Tested[string]; onTest: () => void; onConfigure: () => void }) {
  return (
    <article className="df-engine" aria-label={e.name}>
      <div className="df-head-row" style={{ alignItems: "flex-start", gap: 8 }}>
        <div style={{ minWidth: 0 }}><b>{e.name}</b><div className="df-small">{e.does}</div></div>
        <span className="df-grow" /><Chip tone={engineStatus(e).tone}>{engineStatus(e).label}</Chip>
      </div>
      <dl className="df-engine-facts">
        <div><dt>Reads</dt><dd>{e.runs === "server" ? "On this server" : "In the cloud"}</dd></div>
        <div><dt>Cost per 1,000 pages</dt><dd className="df-num">{e.price}</dd></div>
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
