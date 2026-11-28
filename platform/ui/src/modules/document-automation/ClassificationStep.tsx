import { useState } from "react";
import { ChoiceFilter, Select } from "../../components";
import type { DocFlowApi, DocType, ProcessDesign, ProcessSettings } from "./api";
import { DocTypeEditor, NameDialog, TeachDialog } from "./DocTypes";
import { Loading, dfHash, useLoad } from "./ui";

export const READ: Record<string, string> = { copy: "From the page", "work-out": "By AI", pick: "Picked by AI" };
/** How many of the type's fields step 3 lists before "and n more". */
export const SHOWN_FIELDS = 8;

/**
 * Step 3 of the process builder, Document classification (28 Nov 2026, artboard "DA step 3"). Only this process's
 * choices are set here: what arrives (one kind, with its type, or several kinds recognised from their descriptions),
 * whether batch scans are split, and what happens to a document that fits no type. The document type itself is shared
 * by processes, so it is shown read-only, with "Edit this type" to Libraries, Document types. A type made here (New type,
 * Teach from samples) opens in full below until it is created.
 */
export function ClassificationStep({ api, design, setSettings, onType, onTypes }:
  { api: DocFlowApi; design: ProcessDesign; setSettings: (p: Partial<ProcessSettings>) => void; onType: (t: DocType) => void;
    /** Several kinds: the process recognises each of these types (the first is the main one). */
    onTypes?: (t: DocType[]) => void }) {
  const [types, reload] = useLoad(() => api.docTypes(), [api]);
  const [pending, setPending] = useState<{ type: DocType; addField: boolean; n: number } | null>(null);
  const [teaching, setTeaching] = useState(false);
  const [naming, setNaming] = useState(false);
  if (!types) return <Loading />;

  const s = design.settings;
  const anyType = design.docType === "Any" && !design.docTypeIds.length;   // the One Inbox reads every type
  const chosen = types.find((t) => design.docTypeIds.includes(t.id)) ?? types.find((t) => t.name === design.docType);
  const several = s.classifyMode === "auto";
  const propose = (type: DocType, addField: boolean) => setPending({ type, addField, n: (pending?.n ?? 0) + 1 });
  const others = several ? design.docTypeIds.filter((id) => id !== chosen?.id).length : 0;

  return (
    <>
      <section className="df-card df-pad" aria-label="Document classification" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <div><b style={{ fontSize: 15 }}>3 · Document classification</b>
          <div className="df-muted">What kind of documents this process receives, and what it does with each.</div></div>
        <div className="df-classify">
          <section className="df-sec" aria-label="What arrives">
            <div className="df-sec-head"><b>What arrives</b></div>
            <div className="df-sec-body">
              {anyType ? <p className="df-small" style={{ margin: 0 }}>This process recognises every document type and sends each to its process.</p> : <>
                <label className={`df-arrive${several ? "" : " is-on"}`}>
                  <input type="radio" name="df-classify" checked={!several} onChange={() => setSettings({ classifyMode: "single" })} />
                  <span className="df-arrive-text"><b>One kind of document</b><span className="df-small">Every file is the same type. Fastest.</span>
                    {!several && <span className="df-arrive-type">
                      <Select aria-label="Document type" value={pending ? "" : chosen?.id ?? ""} onChange={(e) => {
                        const t = types.find((x) => x.id === e.target.value);
                        if (t) { setPending(null); onType(t); }
                      }} options={[...(pending || !chosen ? [{ value: "", label: pending ? pending.type.name + " (not saved yet)" : "Choose a document type" }] : []),
                        ...types.map((t) => ({ value: t.id, label: t.name }))]} />
                      <button type="button" className="df-link" onClick={() => setNaming(true)}>New type…</button>
                      <button type="button" className="df-link" onClick={() => setTeaching(true)}>Teach from samples…</button>
                    </span>}
                    {!several && <label className="df-small df-arrive-check"><input type="checkbox" checked={!!s.checkType} onChange={(e) => setSettings({ checkType: e.target.checked })} />
                      Treat a file that does not look like this type as fitting no type (below)</label>}
                  </span>
                </label>
                <label className={`df-arrive${several ? " is-on" : ""}`}>
                  <input type="radio" name="df-classify" checked={several} onChange={() => setSettings({ classifyMode: "auto" })} />
                  <span className="df-arrive-text"><b>Several kinds: recognise each document</b><span className="df-small">Pick the types it may be. Each is recognised from its description.</span>
                    {several && onTypes && <span className="df-arrive-type">
                      <ChoiceFilter label="Types" any="Choose the types" search value={design.docTypeIds}
                        options={types.map((t) => ({ value: t.id, label: t.name }))}
                        onChange={(ids) => onTypes(ids.map((id) => types.find((x) => x.id === id)).filter((x): x is DocType => !!x))} />
                    </span>}
                  </span>
                </label>
              </>}
              <div className="df-arrive-rows">
                <span className="df-sec-label">Batch scans</span>
                <span className="df-arrive-switch">
                  <button type="button" role="switch" className="df-switch" aria-checked={s.split} aria-labelledby="df-split-label" onClick={() => setSettings({ split: !s.split })} />
                  <span id="df-split-label">Split into separate documents</span></span>
                <label className="df-sec-label" htmlFor="df-no-type">If a document fits no type</label>
                <Select id="df-no-type" value={s.whenNoType ?? "review"} onChange={(e) => setSettings({ whenNoType: e.target.value as ProcessSettings["whenNoType"] })}
                  options={[{ value: "review", label: "Send to review: a person chooses the type" }, { value: "other", label: "Keep it as an Other document" }, { value: "reject", label: "Reject it, with the reason" }]} />
              </div>
            </div>
          </section>

          <section className="df-sec" aria-label="What is read">
            <div className="df-sec-head"><b>What is read{chosen && !anyType ? ": " + chosen.name : ""}</b><span className="df-grow" />
              {chosen && !anyType && <a href={dfHash() + "types/" + encodeURIComponent(chosen.id)} style={{ fontWeight: 600 }}>Edit this type ↗</a>}</div>
            <div className="df-sec-body">
              {anyType ? <p className="df-small" style={{ margin: 0 }}>Each type's fields are in Libraries, <a href={dfHash() + "types"}>Document types</a>.</p>
                : !chosen ? <p className="df-small" style={{ margin: 0 }}>Choose a document type on the left.</p> : <>
                  {chosen.description ? <p className="df-type-quote">“{chosen.description}”</p>
                    : <p className="df-small df-text-warn" style={{ margin: 0 }}>No description yet: add one with Edit this type, so AI engines can recognise it.</p>}
                  {chosen.fields.length ? <table className="df-table" aria-label={"Fields of " + chosen.name}>
                    <thead><tr><th>Field</th><th>Kind</th><th>Read</th><th>Required</th></tr></thead>
                    <tbody>{chosen.fields.slice(0, SHOWN_FIELDS).map((f) => <tr key={f.key}><td>{f.label}</td><td>{f.type}</td><td>{READ[f.method ?? "copy"]}</td><td>{f.required ? "Yes" : "No"}</td></tr>)}</tbody>
                  </table> : <p className="df-small" style={{ margin: 0 }}>No fields yet: add them with Edit this type.</p>}
                  <span className="df-small">{chosen.fields.length > SHOWN_FIELDS ? `${chosen.fields.length} fields · showing ${SHOWN_FIELDS}. ` : ""}
                    {others ? `And ${others} more type${others === 1 ? "" : "s"}. ` : ""}
                    {chosen.usedBy.length > 1 ? `The type is shared by ${chosen.usedBy.length} processes: change it in Libraries, Document types.` : "Change the type in Libraries, Document types."}</span>
                </>}
            </div>
          </section>
        </div>
      </section>

      {/* a type made here opens in full until it is created; then it is shown read-only as any other */}
      {pending && <DocTypeEditor key={"new-" + pending.n} api={api} value={pending.type} addField={pending.addField} showTest={false}
        onSaved={(t) => { setPending(null); reload(); onType(t); }} onDiscard={() => setPending(null)} />}

      {teaching && <TeachDialog api={api} onClose={() => setTeaching(false)} onProposal={(d) => { setTeaching(false); propose(d, false); }} />}
      {naming && <NameDialog onClose={() => setNaming(false)} onDone={(name) => {
        setNaming(false);
        propose({ id: "", name, origin: "made-here", meta: "", keywords: [], languages: [], usedBy: [], fields: [] }, true);
      }} />}
    </>
  );
}
