import { useState } from "react";
import { Button, Field, Select } from "../components";
import type { DocFlowApi, DocType, ProcessDesign, ProcessSettings } from "./api";
import { DocTypeEditor, NameDialog, TeachDialog } from "./DocTypes";
import { ClassifyStage } from "./stages";
import { Loading, useLoad } from "./ui";

/**
 * Step 2 of the process builder, Document classification: how files are classified, and the document type they are
 * read as, chosen, taught or made here, with its fields shown and edited on the same screen. A document type is
 * shared, so saving it changes every process that uses it; choosing another one changes only this process.
 */
export function ClassificationStep({ api, design, setSettings, onType }:
  { api: DocFlowApi; design: ProcessDesign; setSettings: (p: Partial<ProcessSettings>) => void; onType: (t: DocType) => void }) {
  const [types, reload] = useLoad(() => api.docTypes(), [api]);
  const [pending, setPending] = useState<{ type: DocType; addField: boolean; n: number } | null>(null);
  const [teaching, setTeaching] = useState(false);
  const [naming, setNaming] = useState(false);
  if (!types) return <Loading />;

  const anyType = design.docType === "Any" && !design.docTypeIds.length;   // the Smart inbox reads every type
  const chosen = types.find((t) => design.docTypeIds.includes(t.id)) ?? types.find((t) => t.name === design.docType);
  const current = pending?.type ?? chosen;
  const propose = (type: DocType, addField: boolean) => setPending({ type, addField, n: (pending?.n ?? 0) + 1 });

  return (
    <>
      <section className="df-card df-pad" aria-label="Document classification" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        <div><b style={{ fontSize: 15 }}>2 · Document classification</b>
          <div className="df-muted">What kind of document each file is, and which fields are read from it.</div></div>
        <ClassifyStage s={design.settings} set={setSettings} />
        {anyType
          ? <div className="df-note df-info">This process recognises every document type. Each type's fields are in Libraries, Document types.</div>
          : <div style={{ paddingTop: 14, borderTop: "1px solid var(--ui-divider)" }}>
            <div className="df-head-row" style={{ alignItems: "flex-end" }}>
              <div style={{ flex: "1 1 260px", minWidth: 0 }}>
                <Field label="Document type">
                  {(id) => <Select id={id} value={pending ? "" : chosen?.id ?? ""} onChange={(e) => {
                    const t = types.find((x) => x.id === e.target.value);
                    if (t) { setPending(null); onType(t); }
                  }} options={[...(pending || !chosen ? [{ value: "", label: pending ? pending.type.name + " (not saved yet)" : "Choose a document type" }] : []),
                    ...types.map((t) => ({ value: t.id, label: t.name }))]} />}
                </Field>
              </div>
              <Button onClick={() => setTeaching(true)}>Teach from samples</Button>
              <Button icon="plus" onClick={() => setNaming(true)}>New document type</Button>
            </div>
            <p className="df-small" style={{ margin: "6px 0 0" }}>Choosing another type changes only this process.</p>
          </div>}
      </section>

      {!anyType && current && <>
        <div className="df-note df-warn">A document type is shared: saving its fields changes every process that uses it{current.usedBy.length ? ` (${current.usedBy.join(", ")})` : ""}.</div>
        <DocTypeEditor key={pending ? "new-" + pending.n : current.id} api={api} value={current} addField={pending?.addField} showTest={false}
          onSaved={(t) => { setPending(null); reload(); onType(t); }} onDiscard={() => setPending(null)} />
      </>}

      {teaching && <TeachDialog api={api} onClose={() => setTeaching(false)} onProposal={(d) => { setTeaching(false); propose(d, false); }} />}
      {naming && <NameDialog onClose={() => setNaming(false)} onDone={(name) => {
        setNaming(false);
        propose({ id: "", name, origin: "made-here", meta: "", keywords: [], languages: ["en", "ar"], usedBy: [], fields: [] }, true);
      }} />}
    </>
  );
}
