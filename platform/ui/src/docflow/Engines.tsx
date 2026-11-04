import { useEffect, useState } from "react";
import type { DocFlowApi, EngineOption, Routing } from "./api";
import { CostSection } from "./engines/CostSection";
import { EngineCatalog } from "./engines/EngineCatalog";
import { OptInSwitch } from "./engines/OptInSwitch";
import { RoutingSection } from "./engines/RoutingSection";
import { Loading, reportError, useLoad } from "./ui";

export const ENGINE_SECTIONS = [
  { id: "routing", label: "How pages are routed" }, { id: "catalog", label: "Engines" }, { id: "cost", label: "Monthly cost" },
] as const;
type SectionId = typeof ENGINE_SECTIONS[number]["id"];
const OPTION_IDS: EngineOption["id"][] = ["onecore", "oneconnect", "onemind"];

/**
 * Engines, in three sections of their own, each with the whole page: how a page is routed, the engines of each option,
 * and the monthly cost. Cloud opt-in stays at the top, as it changes all three. #/engines/<section>[/<option>].
 */
export function Engines({ api, onOptIn, section, option }: { api: DocFlowApi; onOptIn?: (on: boolean) => void; section?: string; option?: string }) {
  const [data, reload] = useLoad(() => api.engines(), [api]);
  const [routing, setRouting] = useState<Routing | null>(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => { if (data) setRouting(data.routing); }, [data]);
  if (!data || !routing) return <Loading />;

  const current: SectionId = ENGINE_SECTIONS.some((s) => s.id === section) ? section as SectionId : "routing";
  const optionId = OPTION_IDS.includes(option as EngineOption["id"]) ? option as EngineOption["id"] : "onecore";
  const save = async (r: Routing) => {
    try { await api.saveRouting(r); setSaved(true); onOptIn?.(r.cloudOptIn); reload(); } catch (e) { reportError(e); }
  };
  const cloudEngines = data.options.find((o) => o.id === "oneconnect")?.engines.filter((e) => e.id !== "abbyy") ?? [];

  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Engines</h2><p>Which engines read the pages, when the cloud may be used, and what it costs.</p></div>
        <span className="df-grow" />
        <OptInSwitch on={routing.cloudOptIn} onChange={(on) => { const r = { ...routing, cloudOptIn: on }; setRouting(r); void save(r); }} />
      </div>
      <nav className="df-seg df-seg-wide" aria-label="Engine sections">
        {ENGINE_SECTIONS.map((s) => (
          <a key={s.id} href={"#/engines/" + s.id} aria-current={s.id === current ? "page" : undefined}>{s.label}</a>
        ))}
      </nav>
      {current === "routing" && <RoutingSection routing={routing} cloudEngines={cloudEngines} saved={saved}
        onChange={(r) => { setSaved(false); setRouting(r); }} onSave={() => save(routing)} />}
      {current === "catalog" && <EngineCatalog api={api} options={data.options} optionId={optionId} optIn={routing.cloudOptIn}
        onOption={(id) => { location.hash = "#/engines/catalog/" + id; }} />}
      {current === "cost" && <CostSection optIn={routing.cloudOptIn} />}
    </div>
  );
}
