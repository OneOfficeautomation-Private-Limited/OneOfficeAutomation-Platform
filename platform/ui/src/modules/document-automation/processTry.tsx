import { useEffect, useRef, useState } from "react";
import { Button, Dialog } from "../../components";
import type { DocFlowApi, DocType, ProcessDesign, ProcessTryResult } from "./api";
import { dfHash, reportError } from "./ui";

/** Engines that copy values from the page only: they cannot work a value out or pick one (fields read "By AI"). */
const COPY_ONLY = new Set(["builtin-rules", "azure-di", "aws-textract", "google-docai", "abbyy"]);
const OUTCOME: Record<string, string> = {
  success: "Would go straight through", validated: "Would go straight through", validation: "Would go to a person for review",
  invalid: "Would be rejected", failed: "Could not be read",
};

/**
 * Step 8 (walkthrough 28 Nov 2026): try a sample document in this process as it is saved (its engines, checks and
 * review settings; DocFlow 3.4.1). Nothing is kept, reviewed or delivered; the answer says what would happen.
 */
export function ProcessTry({ api, design, dirty, onTried }: { api: DocFlowApi; design: ProcessDesign; dirty: boolean; onTried: () => void }) {
  const picker = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ProcessTryResult | null>(null);
  const [missing, setMissing] = useState(false);   // the DocFlow in use has no Try (before 3.4.1)
  const run = async () => {
    if (!file) return;
    setBusy(true);
    try { setResult(await api.tryInProcess(design.id, file)); onTried(); }
    catch (e) {
      if (e instanceof Error && /not found|404/i.test(e.message)) setMissing(true); else reportError(e);
    } finally { setBusy(false); }
  };
  const label = (k: string) => design.fields.find((f) => f.name === k)?.label ?? k;
  const show = (v: unknown) => v === null || v === undefined || v === "" ? "—" : typeof v === "object" ? JSON.stringify(v) : String(v);
  return (
    <section className="df-sec" aria-label="Try in this process">
      <div className="df-sec-head"><b>Try in this process</b><span className="df-small">read as {design.name} would read it; nothing is kept, reviewed or sent</span></div>
      <div className="df-sec-body" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
          <input ref={picker} type="file" hidden accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff,.bmp,.webp,.xml" aria-label="Sample document"
            onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
          <Button onClick={() => picker.current?.click()}>{file ? "Another document…" : "Choose a document…"}</Button>
          <span className="df-small" style={{ flex: 1, minWidth: 0, overflowWrap: "anywhere" }}>{file ? file.name : "A PDF, image or e-invoice like the ones this process will get."}</span>
          <Button kind="primary" disabled={!file || busy || missing} onClick={run}>{busy ? "Reading…" : "Try it"}</Button>
        </div>
        {dirty && <p className="df-small df-text-warn" style={{ margin: 0 }}>The try uses the saved process: save your changes first to try them.</p>}
        {missing && <p className="df-small df-text-warn" style={{ margin: 0 }}>This needs DocFlow 3.4.1. Until then, test the document type below.</p>}
        {result && <div aria-label="What would happen" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <p style={{ margin: 0 }}><b className={result.status === "validation" || result.status === "invalid" || result.status === "failed" ? "df-text-warn" : ""}>
            {OUTCOME[result.status] ?? result.status}</b>{result.doctypeName ? ` · read as ${result.doctypeName}` : ""}{result.error ? ` · ${result.error}` : ""}</p>
          {result.reviewReasons.length > 0 && <ul style={{ margin: 0, paddingInlineStart: 18 }} aria-label="Why">
            {result.reviewReasons.map((r, i) => <li key={i} className="df-small">{r.message || r.reason}</li>)}</ul>}
          {result.flags.length > 0 && <ul style={{ margin: 0, paddingInlineStart: 18 }} aria-label="Checks">
            {result.flags.map((f, i) => <li key={i} className="df-small">{f.rule}: {f.message} ({f.action})</li>)}</ul>}
          {Object.keys(result.fields).length > 0 && <table className="df-table cc-no-filter" aria-label="Values read">
            <thead><tr><th>Field</th><th>Value</th><th>Sure</th></tr></thead>
            <tbody>{Object.entries(result.fields).map(([k, v]) => <tr key={k}><td>{label(k)}</td><td>{show(v)}</td>
              <td>{result.confidence[k] == null ? "—" : Math.round(result.confidence[k]! * 100) + "%"}</td></tr>)}</tbody>
          </table>}
          <p className="df-small" style={{ margin: 0 }}>{result.deliveries.length ? `Would send: ${result.deliveries.join(", ")}${result.live ? "" : " (held while the process is a draft)"}.` : "Nothing would be sent (step 7)."}</p>
          {result.warnings.length > 0 && <p className="df-small" style={{ margin: 0 }}>{result.warnings.join(" · ")}</p>}
        </div>}
      </div>
    </section>
  );
}

/** One line of the go-live checklist: done or worth a look, with the step it is set in. */
interface Check { ok: boolean; text: string; step: number }

/**
 * Step 8: what is ready and what is left before going live (it does not stop going live). steps: the step numbers
 * (0-based) the lines link to.
 */
export function GoLiveChecklist({ api, design, tried, onStage }: { api: DocFlowApi; design: ProcessDesign; tried: boolean; onStage: (i: number) => void }) {
  const [types, setTypes] = useState<DocType[] | null>(null);
  const [defaultEngine, setDefaultEngine] = useState<string | null>(null);
  useEffect(() => {
    api.docTypes().then(setTypes, () => setTypes([]));
    api.engines().then((e) => setDefaultEngine(e.routing.extraction || "builtin-rules"), () => setDefaultEngine(null));
  }, [api]);
  const s = design.settings;
  const mine = (types ?? []).filter((t) => design.docTypeIds.includes(t.id));
  const engineOf = (id: string) => s.extractionByType?.[id] || (s.extraction === "auto" ? defaultEngine : s.extraction);
  const byAi = mine.filter((t) => { const e = engineOf(t.id); return !!e && COPY_ONLY.has(e) && t.fields.some((f) => f.method === "work-out" || f.method === "pick"); });
  const secret = design.deliveries.some((d) => Object.values(d.headers ?? {}).some((v) => v.startsWith("vault:")));
  const checks: Check[] = [
    { ok: mine.length > 0 && mine.every((t) => (t.description ?? "").trim() && t.fields.length), step: 2,
      text: mine.length && mine.every((t) => (t.description ?? "").trim() && t.fields.length) ? "The document type has a description and fields" : "The document type needs a description and fields" },
    { ok: true, step: 1, text: `Documents come in by ${["upload", s.inputFolder ? "a watched folder" : "", s.email?.enabled ? "an e-mail inbox" : ""].filter(Boolean).join(", ")}` },
    ...(byAi.length ? [{ ok: false, step: 3, text: "Fields read By AI need an AI engine: the engine set cannot do it" }] : []),
    { ok: design.deliveries.length > 0, step: 6, text: design.deliveries.length ? `${design.deliveries.length} deliver${design.deliveries.length === 1 ? "y sends" : "ies send"} the results` : "Nothing is delivered: results stay in DocFlow" },
    ...(secret && s.reviewMode === undefined ? [{ ok: false, step: 6, text: "Secret webhook headers need DocFlow 3.4.1" }] : []),
    { ok: tried, step: 7, text: tried ? "Tried on a sample document" : "Not tried on a sample document yet" },
  ];
  return (
    <section className="df-sec" aria-label="Before going live">
      <div className="df-sec-head"><b>Before going live</b><span className="df-small">what is ready, and what is worth a look; going live is never blocked</span></div>
      <ul className="df-sec-body df-checklist" style={{ margin: 0, listStyle: "none", display: "flex", flexDirection: "column", gap: 4 }}>
        {types === null ? <li className="df-small">Looking…</li> : checks.map((c, i) => (
          <li key={i} style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
            <span aria-hidden="true" className={c.ok ? "df-text-ok" : "df-text-warn"} style={{ fontWeight: 700, width: 16 }}>{c.ok ? "✓" : "!"}</span>
            <span style={{ flex: 1 }}>{c.ok ? "" : <span className="sr-only">To look at: </span>}{c.text}</span>
            <button type="button" className="df-link" onClick={() => onStage(c.step)}>Step {c.step + 1}</button>
          </li>))}
      </ul>
    </section>
  );
}

/** Step 8: says what going live (or taking offline) does before it happens. */
export function GoLiveConfirm({ design, live, busy, onConfirm, onClose }: { design: ProcessDesign; live: boolean; busy: boolean; onConfirm: () => void; onClose: () => void }) {
  const s = design.settings;
  const from = ["uploads", "agents", s.inputFolder ? `the watched folder ${s.inputFolder}` : "", s.email?.enabled ? "the e-mail inbox" : ""].filter(Boolean).join(", ");
  return (
    <Dialog open onClose={onClose} title={live ? `Take ${design.name} offline?` : `Go live with ${design.name}?`} width={520}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind={live ? "primary" : "run"} disabled={busy} onClick={onConfirm}>{live ? "Take offline" : "Go live"}</Button></>}>
      {live ? <p style={{ margin: 0 }}>It goes back to a draft: agents and the watched folder can no longer send documents to it, and its deliveries are held.
        Documents already in it stay where they are.</p>
        : <p style={{ margin: 0 }}>New documents from {from} use this version{design.deliveries.length ? ", and its deliveries start sending" : ""}.
          Unsaved changes are saved first.</p>}
      <p className="df-small" style={{ margin: 0 }}>To check many documents whose right values you know, use <a href={dfHash() + "testlab/sets"}>Test lab, Test sets</a>.</p>
    </Dialog>
  );
}
