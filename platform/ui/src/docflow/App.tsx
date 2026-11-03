import { useEffect, useState } from "react";
import { Icon, usePersistent } from "../components";
import { CommandMenu } from "../studio/CommandMenu";
import type { DocFlowApi } from "./api";
import { Builder } from "./Builder";
import { DocTypes } from "./DocTypes";
import { Engines } from "./Engines";
import { Inbox } from "./Inbox";
import { OverviewPage } from "./Overview";
import { ProcessPage } from "./ProcessPage";
import { RefData } from "./RefData";
import { Review } from "./Review";
import { TestLab } from "./TestLab";
import { HELP, type HelpPage } from "./help";
import { Chip, DF_ERROR, HelpButton, HelpPanel, reportError } from "./ui";

/**
 * Document Automation in the Command Center: AI > Document Automation, with its own tabs. Screens are addressed by the
 * hash (#/inbox, #/process/<id>, #/review/<document>), so links, Back and a reload all keep the place.
 */
const TAB_IDS = ["overview", "inbox", "processes", "types", "refdata", "engines", "testlab"] as const;
/** process: a process page; design: a process open in the builder (#/processes/<id>); document: a review. */
export type Route = { tab: typeof TAB_IDS[number] | "review"; process?: string; design?: string; document?: string };

export function parseRoute(hash: string): Route {
  const [first, second] = hash.replace(/^#\/?/, "").split("/").map(decodeURIComponent);
  if (first === "process" && second) return { tab: "inbox", process: second };
  if (first === "review" && second) return { tab: "review", document: second };
  if (first === "processes" && second) return { tab: "processes", design: second };
  return { tab: (TAB_IDS as readonly string[]).includes(first) ? first as typeof TAB_IDS[number] : "overview" };
}

const TABS: { tab: Route["tab"]; label: string }[] = [
  { tab: "overview", label: "Overview" }, { tab: "inbox", label: "Inbox" }, { tab: "processes", label: "Processes" },
  { tab: "types", label: "Document types" }, { tab: "refdata", label: "Reference data" }, { tab: "engines", label: "Engines" }, { tab: "testlab", label: "Test lab" },
];

export function App({ api, controlRoom = "/", tenants = ["Acme Trading Co."], user = "Sara Khan" }:
  { api: DocFlowApi; controlRoom?: string | null; tenants?: string[]; user?: string }) {
  const [route, setRoute] = useState<Route>(() => parseRoute(location.hash));
  const [optIn, setOptIn] = useState(false);
  const [error, setError] = useState("");
  // "How to use this page" shows on a person's first visit to each page, until they hide it
  const [helpHidden, setHelpHidden] = usePersistent<Record<string, boolean>>("docflow.helpHidden", {});
  useEffect(() => {
    const onHash = () => { setRoute(parseRoute(location.hash)); setError(""); };
    const onError = (e: Event) => setError((e as CustomEvent<string>).detail);
    window.addEventListener("hashchange", onHash);
    window.addEventListener(DF_ERROR, onError);
    return () => { window.removeEventListener("hashchange", onHash); window.removeEventListener(DF_ERROR, onError); };
  }, []);
  useEffect(() => { api.engines().then((e) => setOptIn(e.routing.cloudOptIn), reportError); }, [api, route]);

  const reviewing = route.tab === "review";
  const helpPage: HelpPage = reviewing ? "review" : route.process ? "process" : route.tab as HelpPage;
  const helpOpen = !helpHidden[helpPage];
  const toggleHelp = () => setHelpHidden({ ...helpHidden, [helpPage]: helpOpen });
  const help = helpOpen && <HelpPanel {...HELP[helpPage]} onHide={toggleHelp} />;
  const crumb = reviewing ? "Review" : route.process ? "Process" : TABS.find((t) => t.tab === route.tab)!.label;
  const initials = user.split(" ").map((w) => w[0]).join("").slice(0, 2).toUpperCase();

  return (
    <div className="st-app">
      <header className="st-top">
        <div className="st-brand"><span className="st-logo" aria-hidden="true" />One Command Center</div>
        <nav className="st-crumbs" aria-label="Breadcrumb">
          {reviewing ? <span>MANAGE</span> : <span>AI</span>}<Icon name="chevronRight" size={12} />
          {reviewing ? <span>Task inbox</span> : <span>Document Automation</span>}<Icon name="chevronRight" size={12} />
          <span className="st-crumb-current">{crumb}</span>
        </nav>
        <div className="st-top-actions">
          {tenants.length > 1 && (
            <label className="df-filter" style={{ color: "var(--ui-frame-text-2)" }}>
              <span style={{ fontWeight: 400 }}>Tenant</span>
              <select className="ui-input ui-select" aria-label="Tenant" style={{ height: 28, minWidth: 0 }}>
                {tenants.map((t) => <option key={t}>{t}</option>)}
              </select>
            </label>
          )}
          {tenants.length === 1 && <span style={{ color: "var(--ui-frame-text-2)" }}>Tenant: {tenants[0]}</span>}
          <span className="df-avatar" title={user} aria-label={"Signed in as " + user}>{initials}</span>
        </div>
      </header>
      <div className="st-body">
        <CommandMenu view={null} onView={() => undefined} controlRoom={controlRoom} currentPage={reviewing ? "inbox" : "documents"} />
        <main className="df-main">
          {!reviewing && (
            <div className="df-head">
              <div className="df-head-row">
                <h1>Document Automation</h1>
                {optIn
                  ? <Chip tone="ai">Cloud opt-in on: weak pages may go to cloud engines</Chip>
                  : <Chip tone="ok">Local-only: nothing leaves this server</Chip>}
                <span className="df-grow" />
                <HelpButton open={helpOpen} onToggle={toggleHelp} />
              </div>
              <nav className="df-tabs" aria-label="Document Automation">
                {TABS.map((t) => (
                  <a key={t.tab} className="df-tab" href={"#/" + t.tab} aria-current={route.tab === t.tab ? "page" : undefined}>{t.label}</a>
                ))}
              </nav>
            </div>
          )}
          {error && (
            <div className="df-alert" role="alert">
              <b>Something went wrong.</b> {error}
              <button type="button" className="df-link" onClick={() => setError("")}>Dismiss</button>
            </div>
          )}
          {reviewing
            ? <div className="df-help-row"><HelpButton open={helpOpen} onToggle={toggleHelp} />{help}</div>
            : help && <div className="df-help-row">{help}</div>}
          <Screen route={route} api={api} onOptIn={setOptIn} />
        </main>
      </div>
    </div>
  );
}

function Screen({ route, api, onOptIn }: { route: Route; api: DocFlowApi; onOptIn: (on: boolean) => void }) {
  if (route.process) return <ProcessPage api={api} processId={route.process} />;
  switch (route.tab) {
    case "review": return <Review api={api} documentId={route.document!} />;
    case "inbox": return <Inbox api={api} />;
    case "processes": return <Builder key={route.design ?? ""} api={api} processId={route.design} />;
    case "types": return <DocTypes api={api} />;
    case "refdata": return <RefData api={api} />;
    case "engines": return <Engines api={api} onOptIn={onOptIn} />;
    case "testlab": return <TestLab api={api} />;
    default: return <OverviewPage api={api} />;
  }
}
