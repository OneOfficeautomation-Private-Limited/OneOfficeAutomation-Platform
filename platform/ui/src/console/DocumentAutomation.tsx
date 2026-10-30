import { useEffect, useState } from "react";
import type { ConsolePlugin, CrApi } from "../api/cr";

/**
 * AI > Document Automation, as in AA's Control Room: a plug-in page. An optional module fills it (it lists itself as a
 * ConsolePlugin on the server); without it, the page says what the module does and that it is not installed.
 */
export function DocumentAutomation({ api }: { api: CrApi }) {
  const [plugin, setPlugin] = useState<ConsolePlugin | null | undefined>(undefined);   // undefined: still asking
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api.plugins().then((all) => setPlugin(all.find((p) => p.id === "document-automation") ?? null), (e) => setError(e instanceof Error ? e.message : String(e)));
  }, [api]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-da-title">
      <header className="st-page-head"><div><h1 id="cc-da-title">Document Automation</h1>
        <p>Reads documents such as invoices, receipts and forms, and pulls their fields out with AI, for agents to use.</p></div></header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {plugin === undefined && !error && <p className="st-muted cc-pad" role="status">Loading…</p>}
      {plugin && (
        <div className="cc-card"><p>{plugin.description}</p><a className="ui-btn ui-btn-primary" href={plugin.path}>Open Document Automation</a></div>
      )}
      {plugin === null && (
        <div className="cc-card">
          <h2>Not installed on this Command Center</h2>
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
