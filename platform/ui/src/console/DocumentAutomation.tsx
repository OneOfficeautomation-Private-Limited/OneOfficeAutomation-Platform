import { useEffect, useState } from "react";
import type { ConsolePlugin, CrApi, DocFlowVersion } from "../api/cr";
import { HelpButton, type HelpTopic } from "../components";

/**
 * AI > Document Automation, as in AA's Control Room: a plug-in page. An optional module fills it (it lists itself as a
 * ConsolePlugin on the server); without it, the page says what the module does and that it is not installed.
 * Review round 2 (1 Nov 2026): the title row of the other pages (what it does on hover, Installed or Not installed, and
 * Open on the right when installed).
 */
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

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-da-title">
      <header className="cc-list-head">
        <h1 id="cc-da-title">Document Automation</h1>
        <HelpButton topic={HELP} />
        {plugin !== undefined && <span className={"cc-pill is-" + (plugin ? "active" : "disabled")}><span className="cc-pill-dot" />{plugin ? "Installed" : "Not installed"}</span>}
        <span className="ov-fill" />
        {plugin && <a className="ui-btn ui-btn-primary" href={plugin.path}>Open Document Automation</a>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {plugin === undefined && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {plugin && (
        <div className="cc-card"><p>{plugin.description}</p></div>
      )}
      {plugin && service && (
        <div className="cc-card" aria-label="Document Automation service">
          <h2>
            Service{service.version ? ": DocFlow " + service.version : ""}{" "}
            <span className={"cc-pill is-" + PILL[service.status]}><span className="cc-pill-dot" />{LABEL[service.status]}</span>
          </h2>
          <p>{service.message}</p>
          <p className="st-muted">This release is tested with DocFlow {service.tested} (works from {service.minimum}).</p>
        </div>
      )}
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
