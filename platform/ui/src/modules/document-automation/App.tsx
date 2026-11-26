import { useEffect, useState, type ReactNode } from "react";
import { Icon, Menu, usePersistent } from "../../components";
import { CompanyName, TopBrand } from "../../shell/TopBrand";
import { CommandMenu } from "../../studio/CommandMenu";
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
import { Chip, DF_ERROR, HelpButton, HelpPanel, reportError, dfHash, setDfHashBase } from "./ui";
import { PageIntro } from "../../shell/PageIntro";
import { loadHelpConfig } from "../../shell/helpCatalogue";

/**
 * Document Automation in the Command Center: Build > Document Automation, with its own tabs. Screens are addressed by the
 * hash (#/inbox, #/process/<id>, #/review/<document>), so links, Back and a reload all keep the place.
 */
const TAB_IDS = ["overview", "inbox", "processes", "types", "refdata", "engines", "testlab"] as const;
/** process: a process page; design: a process open in the builder (#/processes/<id>); document: a review. */
/** section / item: a part of a page and what is open in it, e.g. #/overview/dashboard, #/engines/catalog/onemind, #/testlab/sets/<set>. */
export type Route = { tab: typeof TAB_IDS[number] | "review"; process?: string; design?: string; stage?: string; document?: string; section?: string; item?: string };

export function parseRoute(hash: string): Route {
  // inside the console its addresses are #/docautomation/<route> (fix list 27 Nov, item 3c)
  const [first, second, third] = hash.replace(/^#\/?/, "").replace(/^docautomation(\/|$)/, "").split("/").map(decodeURIComponent);
  if (first === "process" && second) return { tab: "inbox", process: second };
  if (first === "review" && second) return { tab: "review", document: second };
  if (first === "processes" && second) return { tab: "processes", design: second, ...(third ? { stage: third } : {}) };
  if (first === "overview" && second) return { tab: "overview", section: second };
  if (first === "engines" && second) return { tab: "engines", section: second, ...(third ? { item: third } : {}) };
  if (first === "testlab" && second) return { tab: "testlab", section: second, ...(third ? { item: third } : {}) };
  return { tab: (TAB_IDS as readonly string[]).includes(first) ? first as typeof TAB_IDS[number] : "overview" };
}

type Tab = { tab: Route["tab"]; label: string; about?: string };
/**
 * The tabs: Overview first (where things stand), then the Inbox (the documents) and Processes (each built step by
 * step, from where documents come in); the Libraries the process steps use are in a small menu.
 */
export const TAB_GROUPS: { label: string; tabs: Tab[]; menu?: boolean }[] = [
  { label: "Pages", tabs: [{ tab: "overview", label: "Overview" }, { tab: "inbox", label: "Inbox" }, { tab: "processes", label: "Processes" }] },
  { label: "Libraries", menu: true, tabs: [
    { tab: "types", label: "Document types", about: "What to read from each kind of document" },
    { tab: "refdata", label: "Reference data", about: "Lists that checks look values up in" },
    { tab: "engines", label: "Engines", about: "Which engines read the pages; cloud opt-in; cost" },
    { tab: "testlab", label: "Test lab", about: "Try a document, or compare engines on test sets" },
  ] },
];
const TABS = TAB_GROUPS.flatMap((g) => g.tabs);

export function App({ api, controlRoom = "/", tenants = ["Acme Trading Co."], user = "Sara Khan", embedded = false, status }:
  { api: DocFlowApi; controlRoom?: string | null; tenants?: string[]; user?: string;
    /** Inside the console's frame (Build > Document Automation): no top bar or menu of its own, the console's header and tab look. */
    embedded?: boolean; status?: ReactNode }) {
  setDfHashBase(embedded ? "#/docautomation/" : "#/");
  const [route, setRoute] = useState<Route>(() => parseRoute(location.hash));
  useEffect(() => { loadHelpConfig(); }, []);   // where Docs and Video point (round 3, item 6)
  const [optIn, setOptIn] = useState(false);
  // the tenant chosen at the top (kept for the next visit); one no longer offered falls back to the first
  const [savedTenant, setSavedTenant] = usePersistent("docflow.tenant", "");
  const tenant = tenants.includes(savedTenant) ? savedTenant : tenants[0] ?? "";
  if (tenant) api.setTenant?.(tenant);   // before the screens below load anything
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
  useEffect(() => { api.engines().then((e) => setOptIn(e.routing.cloudOptIn), reportError); }, [api, route, tenant]);

  const reviewing = route.tab === "review";
  const helpPage: HelpPage = reviewing ? "review" : route.process ? "process" : route.tab === "testlab" && route.section === "sets" ? "testsets"
    : route.tab === "overview" && route.section === "dashboard" ? "dashboard" : route.tab as HelpPage;
  const helpOpen = !helpHidden[helpPage];
  const toggleHelp = () => setHelpHidden({ ...helpHidden, [helpPage]: helpOpen });
  const help = helpOpen && <HelpPanel {...HELP[helpPage]} onHide={toggleHelp} />;
  const crumb = reviewing ? "Review" : route.process ? "Process" : TABS.find((t) => t.tab === route.tab)!.label;
  const initials = user.split(" ").map((w) => w[0]).join("").slice(0, 2).toUpperCase();

  const head = !reviewing && (
            <div className={embedded ? "df-head df-head-embedded" : "df-head"}>
              <div className={embedded ? "df-head-row cc-list-head st-agents-head" : "df-head-row"}>
                <h1>Document Automation</h1><PageIntro k={"docs:" + route.tab} />
                {optIn
                  ? <Chip tone="ai">Cloud opt-in on: weak pages may go to cloud engines</Chip>
                  : <Chip tone="ok">Local-only: nothing leaves this server</Chip>}
                {embedded && status}
                {embedded && tenants.length === 1 && <span className="cc-list-count">Tenant: {tenants[0]}</span>}
                <span className="df-grow" />
                <HelpButton open={helpOpen} onToggle={toggleHelp} />
              </div>
              <nav className={embedded ? "df-tabs ov-tabs cc-ins-tabs" : "df-tabs"} aria-label="Document Automation">
                {TAB_GROUPS.map((g) => (
                  <div key={g.label} className={`df-tab-group${g.menu ? " is-menu" : ""}`} role="group" aria-label={g.label}>
                    {g.menu ? <LibraryMenu tabs={g.tabs} current={route.tab} /> : g.tabs.map((t) => (
                      <a key={t.tab} className={embedded ? "df-tab ov-tab" : "df-tab"} href={dfHash() + t.tab} aria-current={route.tab === t.tab ? "page" : undefined}>{t.label}</a>
                    ))}
                  </div>
                ))}
              </nav>
            </div>
          );
  const body = (<>
          {error && (
            <div className="df-alert" role="alert">
              {error}
              <button type="button" className="df-link" onClick={() => setError("")}>Dismiss</button>
            </div>
          )}
          {reviewing
            ? <div className="df-help-row"><HelpButton open={helpOpen} onToggle={toggleHelp} />{help}</div>
            : help && <div className="df-help-row">{help}</div>}
          {/* a new tenant loads every screen again, for that tenant */}
          <Screen key={tenant} route={route} api={api} onOptIn={setOptIn} />
        </>);
  // inside the console: its top bar, menu and breadcrumb (Build > Document Automation) are used
  if (embedded) return <div className="df-embedded">{head}{body}</div>;

  return (
    <div className="st-app cc-app">
      <header className="st-top">
        <TopBrand />
        <nav className="st-crumbs" aria-label="Breadcrumb">
          {reviewing ? <span>Run</span> : <span>Build</span>}<Icon name="chevronRight" size={12} />
          {reviewing ? <span>Task inbox</span> : <span>Document Automation</span>}<Icon name="chevronRight" size={12} />
          <span className="st-crumb-current">{crumb}</span>
        </nav>
        <CompanyName />
        <div className="st-top-actions">
          {tenants.length > 1 && (
            <label className="df-filter" style={{ color: "var(--ui-frame-text-2)" }}>
              <span style={{ fontWeight: 400 }}>Tenant</span>
              <select className="ui-input ui-select" aria-label="Tenant" style={{ height: 28, minWidth: 0 }} value={tenant}
                onChange={(e) => setSavedTenant(e.target.value)}>
                {tenants.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </label>
          )}
          {tenants.length === 1 && <span style={{ color: "var(--ui-frame-text-2)" }}>Tenant: {tenants[0]}</span>}
          <span className="df-avatar" title={user} aria-label={"Signed in as " + user}>{initials}</span>
        </div>
      </header>
      <div className="st-body">
        <CommandMenu view={null} controlRoom={controlRoom} currentPage={reviewing ? "inbox" : "documents"} />
        <main className="df-main">
          {head}
          {body}
        </main>
      </div>
    </div>
  );
}

/** Libraries: one button that names the library open now (with "Libraries" beside it), and a menu of all of them. */
function LibraryMenu({ tabs, current }: { tabs: Tab[]; current: Route["tab"] }) {
  const open = tabs.find((t) => t.tab === current);
  return (<>
    {open && <span className="df-tab-group-label" aria-hidden="true">Libraries</span>}
    <Menu label="Libraries" align="start"
      trigger={<button type="button" className="df-tab" aria-current={open ? "page" : undefined}>{open ? open.label : "Libraries"}<Icon name="chevronDown" size={12} stroke={2.4} /></button>}
      items={tabs.map((t) => ({ key: t.tab, label: t.label, description: t.about, onSelect: () => { location.hash = dfHash() + "" + t.tab; } }))} />
  </>);
}

function Screen({ route, api, onOptIn }: { route: Route; api: DocFlowApi; onOptIn: (on: boolean) => void }) {
  if (route.process) return <ProcessPage api={api} processId={route.process} />;
  switch (route.tab) {
    case "review": return <Review api={api} documentId={route.document!} />;
    case "inbox": return <Inbox api={api} />;
    case "processes": return <Builder key={route.design ?? ""} api={api} processId={route.design} stage={route.stage} />;
    case "types": return <DocTypes api={api} />;
    case "refdata": return <RefData api={api} />;
    case "engines": return <Engines api={api} onOptIn={onOptIn} section={route.section} option={route.item} />;
    case "testlab": return <TestLab api={api} section={route.section} item={route.item} />;
    default: return <OverviewPage api={api} section={route.section} />;
  }
}
