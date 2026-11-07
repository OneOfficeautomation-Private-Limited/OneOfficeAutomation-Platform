import type { DocFlowApi } from "./api";
import { Dashboard } from "./overview/Dashboard";
import { ProcessTable } from "./overview/ProcessTable";

export const OVERVIEW_SECTIONS = [{ id: "table", label: "Table" }, { id: "dashboard", label: "Dashboard" }] as const;

/**
 * Overview, in two pages of its own: Table (a row per process, with its set-up and what is happening) and Dashboard
 * (the charts). #/overview/table, the default, and #/overview/dashboard.
 */
export function OverviewPage({ api, section }: { api: DocFlowApi; section?: string }) {
  const current = section === "dashboard" ? "dashboard" : "table";
  return (
    <div className="df-page">
      <div className="df-page-head">
        <div><h2>Overview</h2><p>{current === "table" ? "Every process: how it is set up and what is happening." : "How documents are doing across every process."}</p></div>
      </div>
      <nav className="df-seg df-seg-wide" aria-label="Overview sections">
        {OVERVIEW_SECTIONS.map((s) => <a key={s.id} href={"#/overview/" + s.id} aria-current={s.id === current ? "page" : undefined}>{s.label}</a>)}
      </nav>
      {current === "table" ? <ProcessTable api={api} /> : <Dashboard api={api} />}
    </div>
  );
}
