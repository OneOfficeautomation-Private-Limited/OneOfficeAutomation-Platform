import { useEffect, useState } from "react";
import { Button, Icon } from "../../components";
import { INBOX_ID, stagesOf, type Check, type Delivery, type DocFlowApi, type DocType, type InboxStatus, type ProcessDesign, type ProcessSettings, type ProcessSummary, type RefTable } from "./api";
import { ClassificationStep } from "./ClassificationStep";
import { HINTS } from "./help";
import { NewProcessDialog, folderLabel } from "./dialogs";
import { SelectStep } from "./SelectStep";
import { CheckDialog, DeliverStage, DeliveryDialog, ExtractStage, IF_FAILS, IntakeStage, ReviewStage } from "./stages";
import { TestLab } from "./TestLab";
import { Chip, FilterBar, FilterSelect, Hint, Loading, SearchBox, matches, reportError } from "./ui";
import { RecentUploads, UploadFiles } from "./upload";

/**
 * The process builder: the path a developer follows, one numbered step at a time, from choosing the process
 * (1 Select a process) and where documents come in (2 Document ingestion, where files can be uploaded too) to trying
 * samples and going live (8 Testing & go-live). Steps 2 to 8 open once a process is chosen; a process in the address
 * (#/processes/<id>) is chosen and opens on step 2. Document classification shows and edits the document type on the
 * same screen; the other steps link to the library they use (reference data, engines). Validation holds the checks,
 * each with what happens when it fails. Changes stay on this page until saved. A draft reads uploads only; Go live also
 * starts its watched folder. DocFlow keeps one version of a process, so saving a live process applies at once.
 */
const STAGE_IDS = ["select", "intake", "classify", "extract", "checks", "review", "deliver", "test"];
const SELECT = 0, INTAKE = 1, CLASSIFY = 2, EXTRACT = 3, CHECKS = 4, REVIEW = 5, DELIVER = 6, TEST = 7;
const LATER = ["Document ingestion", "Document classification", "Data extraction", "Validation", "Human review", "Export & integration", "Testing & go-live"];
const firstStep = (processId?: string, stage?: string) =>
  !processId ? SELECT : STAGE_IDS.includes(stage ?? "") ? STAGE_IDS.indexOf(stage!) : INTAKE;
const fieldsOf = (t: DocType) => t.fields.filter((f) => f.type !== "Table").map((f) => ({ name: f.key, label: f.label }));

export function Builder({ api, processId, stage: firstStage }: { api: DocFlowApi; processId?: string; stage?: string }) {
  const [processes, setProcesses] = useState<ProcessSummary[] | null>(null);
  const [design, setDesign] = useState<ProcessDesign | null>(null);
  const [saved, setSaved] = useState<ProcessDesign | null>(null);
  const [tables, setTables] = useState<RefTable[]>([]);
  const [stage, setStage] = useState(firstStep(processId, firstStage));
  const [creating, setCreating] = useState(false);
  const [inbox, setInbox] = useState<InboxStatus | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("all");
  const [onOff, setOnOff] = useState<"all" | "on" | "off">("all");
  const [fails, setFails] = useState<"all" | Check["ifFails"]>("all");
  const [busy, setBusy] = useState(false);
  const [editCheck, setEditCheck] = useState<Check | "new" | null>(null);
  const [editDelivery, setEditDelivery] = useState<Delivery | "new" | null>(null);
  const [uploads, setUploads] = useState(0);   // counts uploads, so the last documents load again

  // every process, for step 1; the one in the address is the one chosen
  useEffect(() => {
    let live = true;
    api.processes().then((ps) => {
      if (!live) return;
      setProcesses(ps);
      if (!processId) return;
      api.inboxStatus(processId).then((s) => { if (live) setInbox(s); }, () => undefined);
      return api.processDesign(processId).then((d) => { if (live) { setDesign(d); setSaved(d); } });
    }).catch(reportError);
    api.refTables().then((t) => { if (live) setTables(t); }, () => undefined);
    return () => { live = false; };
  }, [api, processId]);
  // a link to another step of the same process (#/processes/<id>/<stage>) opens that step
  useEffect(() => { if (firstStage) setStage(firstStep(processId, firstStage)); }, [processId, firstStage]);

  const newProcess = <NewProcessDialog api={api} open={creating} onClose={() => setCreating(false)}
    onCreated={(id) => { setCreating(false); location.hash = "#/processes/" + id.split("/").map(encodeURIComponent).join("/") + "/intake"; }} />;
  const choose = (id: string) => { location.hash = "#/processes/" + encodeURIComponent(id); };
  if (!processes || (processId && (!design || !saved))) return <Loading />;
  if (!design || !saved)
    return (
      <div className="df-page">
        <div className="df-page-head"><div><h2>Processes</h2><p>Build a process step by step. Start by choosing one.</p></div></div>
        <Steps steps={[{ name: "Select a process", summary: "Choose one, or New process", done: false }, ...LATER.map((name) => ({ name, summary: "Choose a process first", done: false }))]}
          stage={SELECT} onStage={() => undefined} enabled={(i) => i === SELECT} />
        <SelectStep choices={processes} chosen={null} onChoose={choose} onNew={() => setCreating(true)} />
        {newProcess}
      </div>
    );

  const dirty = JSON.stringify({ ...design, stages: [] }) !== JSON.stringify({ ...saved, stages: [] });
  const change = (patch: Partial<ProcessDesign>) => {
    const next = { ...design, ...patch };
    setDesign({ ...next, stages: stagesOf(next, (e) => e) });
  };
  const setSettings = (p: Partial<ProcessSettings>) => change({ settings: { ...design.settings, ...p } });
  const setCheck = (id: string, patch: Partial<Check>) => change({ checks: design.checks.map((c) => c.id === id ? { ...c, ...patch } : c) });
  // another document type for this process; or its own type saved with new fields (that is no change to the process)
  const onType = (t: DocType) => {
    if (!design.docTypeIds.includes(t.id)) return change({ docTypeIds: [t.id], docType: t.name, fields: fieldsOf(t) });
    const patch = { docType: t.name, fields: fieldsOf(t) };
    const next = { ...design, ...patch };
    setDesign({ ...next, stages: stagesOf(next, (e) => e) }); setSaved({ ...saved, ...patch });
  };
  const saveDraft = async () => {
    setBusy(true);
    try { const d = await api.saveProcess(design); setDesign(d); setSaved(d); } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  const goLive = async (live: boolean) => {
    setBusy(true);
    try {
      const d = dirty ? await api.saveProcess(design) : design;
      await api.setLive(d.id, live);
      const after = { ...d, state: live ? "live" as const : "draft" as const };
      setDesign(after); setSaved(after);
    } catch (e) { reportError(e); } finally { setBusy(false); }
  };

  const rows = design.checks.filter((c) => matches(q, c.name, c.test, c.kind) && (kind === "all" || c.kind === kind)
    && (onOff === "all" || c.on === (onOff === "on")) && (fails === "all" || c.ifFails === fails));
  const onCount = design.checks.filter((c) => c.on).length;
  const live = design.state === "live";
  const steps = [{ name: "Select a process", summary: design.name, done: true }, ...design.stages,
    { name: "Testing & go-live", summary: live ? "Live; new documents use this version" : "Draft; try 1–3 samples, then go live", done: live }];
  const current = steps[stage];
  const choices = processes.some((p) => p.id === design.id) ? processes : [{ id: design.id, name: design.name }, ...processes];

  return (
    <div className="df-page">
      <div className="df-page-head">
        <a className="ui-icon-btn" href={"#/process/" + encodeURIComponent(design.id)} aria-label="Back to the process"><Icon name="chevronLeft" size={14} stroke={2.4} /></a>
        <h2 style={{ fontSize: 18 }}>{design.name}</h2>
        <Chip tone={live ? "ok" : "mute"}>{live ? "Live · version " + design.version : "Draft · version " + design.version}</Chip>
        <span className="df-muted">{folderLabel(design.folder)}{design.folder ? " · " : ""}{design.docType}</span>
        <span className="df-grow" />
        {dirty && <span className="df-text-warn" role="status">{live ? "Unsaved changes: new documents use them once saved" : "Unsaved changes"}</span>}
        <Button disabled={!dirty || busy} onClick={saveDraft}>{!dirty ? "Saved" : live ? "Save changes" : "Save draft"}</Button>
        {live
          ? <Button disabled={busy} onClick={() => goLive(false)}>Take offline</Button>
          : <Button kind="run" disabled={busy} onClick={() => goLive(true)}>Go live</Button>}
      </div>

      <Steps steps={steps.map((s, i) => (i === CHECKS ? { ...s, summary: `${design.checks.length} checks` } : s))} stage={stage} onStage={setStage} enabled={() => true} />

      {stage === SELECT ? <SelectStep choices={choices} chosen={design} onChoose={(id) => (id === design.id ? setStage(INTAKE) : choose(id))} onNew={() => setCreating(true)} />
        : stage === CLASSIFY ? <ClassificationStep api={api} design={design} setSettings={setSettings} onType={onType} /> : stage === TEST ? (
        <section className="df-card df-pad" aria-label="Testing & go-live" style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0 }}>
          <div><b style={{ fontSize: 15 }}>8 · Testing & go-live</b><div className="df-muted">Try 1–3 sample documents. Nothing is saved or sent to review. When the results are right, go live.</div></div>
          <TestLab api={api} initialType={design.docType} embedded />
          <div className={`df-note ${live ? "df-ok" : "df-info"}`} style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
            <span style={{ flex: 1, minWidth: 200 }}>{live ? "This process is live: new documents use this version." : "Results look right? Go live: new documents then use this version."}</span>
            {live
              ? <Button disabled={busy} onClick={() => goLive(false)}>Take offline</Button>
              : <Button kind="run" disabled={busy} onClick={() => goLive(true)}>Go live</Button>}
          </div>
        </section>
      ) : (
        <div className="df-grid df-g3">
          {stage === CHECKS ? (
            <section className="df-card df-span2" aria-label="Validation" style={{ overflow: "hidden" }}>
              <div className="df-head-row df-pad">
                <div><b style={{ fontSize: 15 }}>5 · Validation</b><div className="df-muted">Checks run on every document after its fields are read. Each check decides what happens when it fails.
                  Lists to look values up in are kept in <a href="#/refdata">Reference data</a>.</div></div>
                <span className="df-grow" /><Button kind="primary" icon="plus" onClick={() => setEditCheck("new")}>Add check</Button>
              </div>
              <FilterBar onClear={() => { setQ(""); setKind("all"); setOnOff("all"); setFails("all"); }} count={`${rows.length} of ${design.checks.length} checks`}>
                <SearchBox value={q} onChange={setQ} placeholder="Search checks" />
                <FilterSelect label="Kind" value={kind} onChange={setKind} options={[{ value: "all", label: "All kinds" },
                  ...[...new Set(design.checks.map((c) => c.kind))].map((k) => ({ value: k, label: k }))]} />
                <FilterSelect label="On / off" value={onOff} onChange={setOnOff} options={[{ value: "all", label: "All" }, { value: "on", label: "On" }, { value: "off", label: "Off" }]} />
                <FilterSelect label="If it fails" value={fails} onChange={setFails} options={[{ value: "all", label: "Any action" },
                  ...(Object.keys(IF_FAILS) as Check["ifFails"][]).map((k) => ({ value: k, label: IF_FAILS[k] }))]} />
              </FilterBar>
              <div className="df-table-wrap">
                <table className="df-table" style={{ minWidth: 720 }}>
                  <thead><tr><th>On</th><th>Check</th><th>What it tests</th><th>If it fails<Hint about="If it fails" text={HINTS.ifFails} /></th><th><span className="sr-only">Actions</span></th></tr></thead>
                  <tbody>
                    {rows.map((c) => (
                      <tr key={c.id}>
                        <td><input type="checkbox" checked={c.on} aria-label={"Check on: " + c.name} onChange={(e) => setCheck(c.id, { on: e.target.checked })} /></td>
                        <td><b>{c.name}</b><div className="df-small">{c.kind}</div></td>
                        <td className="df-muted">{c.test}</td>
                        <td><select className="ui-input ui-select" aria-label={`If ${c.name} fails`} value={c.ifFails} style={{ minWidth: 170 }}
                          onChange={(e) => setCheck(c.id, { ifFails: e.target.value as Check["ifFails"] })}>
                          {(Object.keys(IF_FAILS) as Check["ifFails"][]).map((k) => <option key={k} value={k}>{IF_FAILS[k]}</option>)}
                        </select></td>
                        <td className="df-right" style={{ whiteSpace: "nowrap" }}>
                          <Button kind="plain" onClick={() => setEditCheck(c)}>Edit</Button>
                          <Button kind="plain" aria-label={"Remove " + c.name} onClick={() => change({ checks: design.checks.filter((x) => x.id !== c.id) })}>Remove</Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!design.checks.length && <div className="df-empty">No checks yet. Click Add check.</div>}
                {design.checks.length > 0 && !rows.length && <div className="df-empty">No check matches these filters.</div>}
              </div>
            </section>
          ) : (
            <section className="df-card df-pad df-span2" aria-label={current.name} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <div><b style={{ fontSize: 15 }}>{stage + 1} · {current.name}</b><div className="df-muted">{current.summary}</div></div>
              {stage === INTAKE && <>
                <IntakeStage s={design.settings} set={setSettings} inbox={inbox} live={live} />
                <section aria-label="Upload files" style={{ display: "flex", flexDirection: "column", gap: 10, paddingTop: 14, borderTop: "1px solid var(--ui-divider)" }}>
                  <div><b>Upload files</b><span className="df-small" style={{ display: "block" }}>
                    Into {design.name}{live ? "" : ", while it is a draft too"}{dirty ? ". They are read with the saved settings" : ""}.</span></div>
                  <UploadFiles api={api} process={design} inbox={design.id === INBOX_ID} inputId="df-step-files" onAdded={() => setUploads((n) => n + 1)} />
                  <RecentUploads api={api} processId={design.id} n={uploads} />
                </section>
              </>}
              {stage === EXTRACT && <>
                <ExtractStage s={design.settings} set={setSettings} />
                <p className="df-small" style={{ margin: 0 }}>How pages are read (OCR, and when the cloud may be used) is set once for every process on <a href="#/engines/routing">Engines</a>.</p>
              </>}
              {stage === REVIEW && <ReviewStage s={design.settings} set={setSettings} fields={design.fields} />}
              {stage === DELIVER && <DeliverStage deliveries={design.deliveries} onAdd={() => setEditDelivery("new")} onEdit={setEditDelivery}
                onRemove={(id) => change({ deliveries: design.deliveries.filter((d) => d.id !== id) })} />}
            </section>
          )}

          <section className="df-card df-pad" aria-label="Process summary" style={{ alignSelf: "start" }}>
            <b style={{ fontSize: 15 }}>Process summary</b>
            <div className="df-muted" style={{ margin: "2px 0 6px" }}>What this process does, stage by stage.</div>
            {steps.map((s, i) => i === SELECT ? null : (
              <div key={s.name} style={{ display: "grid", gridTemplateColumns: "24px minmax(0, 1fr)", gap: "2px 10px", padding: "10px 0", borderBottom: "1px solid var(--ui-divider)" }}>
                <span className={`df-stage-n${s.done ? " is-done" : i === stage ? " is-current" : ""}`} style={{ width: 22, height: 22, fontSize: 11 }}>{s.done ? "✓" : i + 1}</span>
                <b>{s.name}</b><span /><span className="df-muted">{i === CHECKS ? `${onCount} of ${design.checks.length} checks on` : s.summary}</span>
              </div>
            ))}
            {!live && <div className="df-note df-warn" style={{ marginTop: 12 }}>Before going live: try 1–3 sample documents in{" "}
              <button type="button" className="df-link" onClick={() => setStage(TEST)}>step 8, Testing & go-live</button>.</div>}
          </section>
        </div>
      )}

      {editCheck && <CheckDialog key={editCheck === "new" ? "new" : editCheck.id} open value={editCheck === "new" ? null : editCheck} design={design} tables={tables}
        onClose={() => setEditCheck(null)}
        onSave={(c) => { change({ checks: design.checks.some((x) => x.id === c.id) ? design.checks.map((x) => x.id === c.id ? c : x) : [...design.checks, c] }); setEditCheck(null); }} />}
      {editDelivery && <DeliveryDialog key={editDelivery === "new" ? "new" : editDelivery.id} open value={editDelivery === "new" ? null : editDelivery}
        onClose={() => setEditDelivery(null)}
        onSave={(d) => { change({ deliveries: design.deliveries.some((x) => x.id === d.id) ? design.deliveries.map((x) => x.id === d.id ? d : x) : [...design.deliveries, d] }); setEditDelivery(null); }} />}
      {newProcess}
    </div>
  );
}

/** The numbered steps; a step that is not open yet (no process chosen) shows, but cannot be clicked. */
function Steps({ steps, stage, onStage, enabled }:
  { steps: { name: string; summary: string; done: boolean }[]; stage: number; onStage: (i: number) => void; enabled: (i: number) => boolean }) {
  return (
    <div role="tablist" aria-label="Steps" className="df-steps">
      {steps.map((s, i) => (
        <button key={s.name} type="button" role="tab" className="df-stage" aria-selected={i === stage} disabled={!enabled(i)} onClick={() => onStage(i)}>
          <span className={`df-stage-n${s.done ? " is-done" : i === stage ? " is-current" : ""}`}>{s.done ? "✓" : i + 1}</span>
          <span style={{ minWidth: 0 }}><span className="df-step-label">Step {i + 1}</span><b style={{ display: "block" }}>{s.name}</b>
            <span className="df-small">{s.summary.split(";")[0]}</span></span>
        </button>
      ))}
    </div>
  );
}
