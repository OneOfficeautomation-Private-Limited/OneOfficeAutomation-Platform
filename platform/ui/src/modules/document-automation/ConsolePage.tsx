import "./consoleApi";
import { useEffect, useState } from "react";
import type { ConsolePlugin, CrApi } from "../../api/cr";
import type { DocFlowVersion } from "./consoleApi";
import { HelpButton, type HelpTopic } from "../../components";

/**
 * AI > Document Automation, as in AA's Control Room: a plug-in page. An optional module fills it (it lists itself as a
 * ConsolePlugin on the server); without it, the page says what the module does and that it is not installed.
 * Review round 2 (1 Nov 2026): the title row of the other pages (what it does on hover, Installed or Not installed).
 * Item 15 (docs/research/input-help-check.md): a start screen. Open Document Automation on the left under the title,
 * the service's state, a card for each of its areas that opens it directly, and the first steps.
 */
/** Its areas, each opened at its own address in the module's pages. */
const AREAS: { tab: string; title: string; about: string }[] = [
  { tab: "overview", title: "Overview", about: "Documents read, waiting and done, and how accurate the reading is" },
  { tab: "inbox", title: "Inbox", about: "Documents waiting for a person to check what was read" },
  { tab: "processes", title: "Processes", about: "How each kind of document is read, checked and delivered" },
  { tab: "types", title: "Document types", about: "The fields to read from each kind of document" },
  { tab: "refdata", title: "Reference data", about: "Lists that checks look values up in" },
  { tab: "engines", title: "Engines", about: "Which engines read the pages, in this country or in the cloud" },
  { tab: "testlab", title: "Test lab", about: "Try a document, or compare engines on sample sets" },
];
const STEPS = ["Describe a document type: the fields to read", "Build a process: how documents arrive, are read, checked and delivered",
  "Try it in the Test lab with sample documents", "Check results in the Inbox, then let agents use them"];
const HELP: HelpTopic = { title: "Document Automation", about: "Reads documents such as invoices, receipts and forms, and pulls their fields out with AI, "
  + "for agents to use. It is an add-on module; this page shows whether it is installed and opens it.",
  items: [
    { name: "Installed, Not installed", text: "Whether the Document Automation module is installed on this One CommandCenter. "
      + "When it is not, ask your administrator about it." },
    { name: "Open Document Automation", text: "Shows once the module is installed, and opens its own pages." },
    { name: "Service", text: "The Document Automation service (DocFlow) this One CommandCenter uses: its version, and whether it is the "
      + "version this release was tested with. A newer version of the same series should work; an older one should be updated." },
    { name: "Learning instance", text: "One for each kind of document: the fields to extract, how the text is read, and the AI model that extracts it." },
    { name: "Validation queue", text: "Where people check the extracted results before agents use them." },
    { name: "Accuracy", text: "Measured on sample documents before a learning instance goes live." },
  ] };

const LABEL: Record<DocFlowVersion["status"], string> = {
  compatible: "Compatible", newer: "Newer than tested", too_old: "Needs an update", other_contract: "Not compatible",
  unreachable: "Not reachable", not_set_up: "Not set up", unknown: "Version unknown",
};
const PILL: Record<DocFlowVersion["status"], string> = {
  compatible: "active", newer: "pending", too_old: "failed", other_contract: "failed", unreachable: "failed", not_set_up: "disabled", unknown: "pending",
};

export function DocumentAutomation({ api }: { api: CrApi }) {
  const [plugin, setPlugin] = useState<ConsolePlugin | null | undefined>(undefined);   // undefined: still asking
  const [error, setError] = useState<string | null>(null);
  const [service, setService] = useState<DocFlowVersion | null>(null);
  useEffect(() => {
    api.plugins().then((all) => setPlugin(all.find((p) => p.id === "document-automation") ?? null), (e) => setError(e instanceof Error ? e.message : String(e)));
  }, [api]);
  useEffect(() => {
    if (!plugin) return;
    // only a hint: when it cannot be asked (an older server, no permission) the page simply does not show it
    api.docflowVersion().then(setService, () => setService(null));
  }, [api, plugin]);

  const open = (tab?: string) => plugin ? plugin.path + (tab ? "#/" + tab : "") : "#";
  return (
    <section className="st-page cc-home cc-list df-start" aria-labelledby="cc-da-title">
      <header className="cc-list-head">
        <h1 id="cc-da-title">Document Automation</h1>
        <HelpButton topic={HELP} />
        {plugin !== undefined && <span className={"cc-pill is-" + (plugin ? "active" : "disabled")}><span className="cc-pill-dot" />{plugin ? "Installed" : "Not installed"}</span>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {plugin === undefined && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {plugin && <>
        <div className="df-start-lead">
          <a className="ui-btn ui-btn-primary" href={open()}>Open Document Automation</a>
          <a className="ui-btn ui-btn-secondary" href={open("inbox")}>Open the Inbox</a>
          <p className="df-start-about">{plugin.description}</p>
        </div>
        <div className="df-start-grid">
          <div className="cc-card df-start-service" aria-label="Document Automation service">
            <h2>Service</h2>
            {service ? <>
              <p className="df-start-state"><span className={"cc-pill is-" + PILL[service.status]}><span className="cc-pill-dot" />{LABEL[service.status]}</span>
                {service.version && <strong>DocFlow {service.version}</strong>}</p>
              <p>{service.message}</p>
              <p className="st-muted">This release is tested with DocFlow {service.tested} (works from {service.minimum}).</p>
            </> : <p className="st-muted">The service's state is not available here.</p>}
          </div>
          <div className="cc-card df-start-steps" aria-label="First steps">
            <h2>First steps</h2>
            <ol>{STEPS.map((t) => <li key={t}>{t}</li>)}</ol>
          </div>
        </div>
        <h2 className="df-start-h">Go to</h2>
        <nav className="df-start-areas" aria-label="Document Automation areas">
          {AREAS.map((a) => <a key={a.tab} className="df-start-area" href={open(a.tab)}>
            <strong>{a.title}</strong><span>{a.about}</span><span className="df-start-go">Open ›</span></a>)}
        </nav>
      </>}
      {plugin === null && (
        <div className="cc-card">
          <h2>Not installed on this One CommandCenter</h2>
          <p>Document Automation is an add-on module. Once it is installed, its pages open here, and agents can use it.</p>
          <ul>
            <li>A learning instance for each kind of document: the fields to extract, how text is read, and the AI model that extracts it.</li>
            <li>Results checked by people in a validation queue before agents use them.</li>
            <li>Accuracy measured on sample documents before it goes live.</li>
          </ul>
          <p className="st-muted">Ask your administrator about the module. It works on premises and in the cloud.</p>
        </div>
      )}
    </section>
  );
}
