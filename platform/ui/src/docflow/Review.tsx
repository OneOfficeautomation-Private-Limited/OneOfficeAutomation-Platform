import { useEffect, useState } from "react";
import { Button, Dialog, Field, Icon, IconButton, Select } from "../components";
import type { DocFlowApi, DocType, ReviewTask } from "./api";
import { HINTS } from "./help";
import { Chip, Hint, Loading, reportError } from "./ui";

/**
 * The review task a reviewer opens from the Task inbox: the page with the flagged value marked, the fields with what
 * needs them first, the checks, and Approve and next (Ctrl+Enter).
 */
export function Review({ api, documentId }: { api: DocFlowApi; documentId: string }) {
  const [task, setTask] = useState<ReviewTask | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [zoom, setZoom] = useState(100);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    let live = true;
    void api.reviewTask(documentId).then((t) => { if (live) { setTask(t); setValues(Object.fromEntries(t.fields.map((f) => [f.id, f.value]))); } });
    return () => { live = false; };
  }, [api, documentId]);

  const [types, setTypes] = useState<DocType[] | null>(null);
  const [newType, setNewType] = useState("");
  const back = task?.processId ? "#/process/" + encodeURIComponent(task.processId) : "#/inbox";
  const finish = async (decision: "approve" | "reject") => {
    if (!task || saving) return;
    setSaving(true);
    try {
      const r = await api.completeReview(task.documentId, values, decision);
      location.hash = r.next ? "#/review/" + encodeURIComponent(r.next) : back;   // Approve and next: the next one waiting
    } catch (e) { reportError(e); } finally { setSaving(false); }
  };
  const openTypes = () => { setNewType(""); api.docTypes().then(setTypes, reportError); };
  const reclassify = async () => {
    if (!task || !newType) return;
    setSaving(true);
    try {
      const r = await api.changeDocType(task.documentId, newType);
      setTypes(null);
      location.hash = r.needsReview ? "#/review/" + encodeURIComponent(r.documentId) : back;
    } catch (e) { reportError(e); } finally { setSaving(false); }
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.ctrlKey && e.key === "Enter") { e.preventDefault(); void finish("approve"); } };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
  if (!task) return <Loading />;

  const flagged = task.fields.filter((f) => f.flagged);
  const fine = task.fields.filter((f) => !f.flagged);
  const fixed = (id: string) => { const f = task.fields.find((x) => x.id === id); return f?.flagged?.suggestion && values[id] === f.flagged.suggestion; };

  return (
    <div style={{ display: "flex", flexDirection: "column", flex: 1, minHeight: 0 }}>
      <div className="df-head-row" style={{ padding: "12px 24px", borderBottom: "1px solid var(--ui-divider)" }}>
        <a className="ui-icon-btn" href={back} aria-label="Back to the process"><Icon name="chevronLeft" size={14} stroke={2.4} /></a>
        <div style={{ minWidth: 0 }}>
          <div className="df-head-row"><h1 style={{ margin: 0, fontSize: 18 }}>{task.name}</h1>
            {task.status === "review" ? <Chip tone="warn">Needs review</Chip>
              : task.status === "done" ? <Chip tone="ok">{task.reviewedBy ? "Approved by " + task.reviewedBy : "Done"}</Chip>
              : task.status === "failed" ? <Chip tone="bad">{task.reviewedBy ? "Rejected by " + task.reviewedBy : "Failed"}</Chip>
              : <Chip tone="mute">Processing</Chip>}</div>
          <div className="df-muted">{task.processName} · {task.docType} · came in {task.cameIn} · {task.status === "review" ? <><b className="df-text-warn">due in {task.dueIn}</b> ({task.targetHours} h target)</> : <>{task.targetHours} h review target</>}</div>
        </div>
        <span className="df-grow" />
        <span className="df-muted">Task {task.position} of {task.of}</span>
        <IconButton icon="chevronLeft" label="Previous task" disabled={task.position === 1} />
        <IconButton icon="chevronRight" label="Next task" />
      </div>

      <div className="df-review">
        <section className="df-review-page" aria-label="Document page">
          <div className="df-review-tools">
            <Button kind="plain" aria-label="Zoom out" onClick={() => setZoom(Math.max(50, zoom - 10))}>−</Button>
            <span className="df-num" style={{ minWidth: 44, textAlign: "center" }} aria-live="polite">{zoom}%</span>
            <IconButton icon="plus" label="Zoom in" onClick={() => setZoom(Math.min(200, zoom + 10))} />
            <Button kind="plain" onClick={() => setZoom(100)}>Fit width</Button>
            <span className="df-grow" />
            <span className="df-muted">Page 1 of 1 · {task.engineTrail}</span>
          </div>
          <div style={{ overflow: "auto", flex: 1 }}>
            <div className="df-paper" style={{ transform: `scale(${zoom / 100})` }}>
              {task.pageLines.map((l, i) => (
                <p key={i} className={l.mark === "flag" ? "is-flag" : l.mark === "ok" ? "is-ok" : undefined}
                  style={{ fontWeight: l.bold ? 700 : 400, fontSize: i === 0 ? 20 : 13, marginBottom: i === 1 || i === 5 ? 8 : 0 }}>{l.text}</p>
              ))}
            </div>
          </div>
        </section>

        <section className="df-review-fields" aria-label="Fields">
          <div style={{ padding: "14px 20px", borderBottom: "1px solid var(--ui-divider)" }}>
            <b style={{ fontSize: 14 }}>{flagged.filter((f) => !fixed(f.id)).length === 0 ? "All flagged fields checked" : `${flagged.length} field${flagged.length > 1 ? "s" : ""} need${flagged.length > 1 ? "" : "s"} you`}</b>
            <div className="df-muted">Check the highlighted value against the page, then approve.</div>
          </div>
          <div style={{ padding: "16px 20px", display: "flex", flexDirection: "column", gap: 14, overflow: "auto", flex: 1 }}>
            {flagged.map((f) => (
              <div key={f.id} className="df-flag">
                <label htmlFor={"df-f-" + f.id} style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 600 }}>
                  {f.label}<span className="df-grow" />{fixed(f.id) ? <Chip tone="ok">Corrected</Chip> : <Chip tone="warn">Read at {f.confidence}%</Chip>}</label>
                <input id={"df-f-" + f.id} className="ui-input df-num" style={{ marginTop: 6 }} value={values[f.id] ?? ""}
                  onChange={(e) => setValues({ ...values, [f.id]: e.target.value })} />
                {!fixed(f.id) && <div className="df-text-warn" style={{ marginTop: 6 }}>{f.flagged!.reason}</div>}
                {f.flagged!.suggestion && !fixed(f.id) && (
                  <div className="df-card df-head-row" style={{ marginTop: 10, padding: "8px 10px" }}>
                    <span>Suggested <b className="df-num">{f.flagged!.suggestion}</b>: makes both checks pass</span><span className="df-grow" />
                    <Button onClick={() => setValues({ ...values, [f.id]: f.flagged!.suggestion! })}>Use {f.flagged!.suggestion}</Button>
                  </div>
                )}
              </div>
            ))}

            <div className="df-small" style={{ fontWeight: 700, marginTop: 4 }}>CHECKED, NO ACTION NEEDED</div>
            {fine.map((f) => (
              <div key={f.id} className="df-field-row">
                <label htmlFor={"df-f-" + f.id} className="df-muted">{f.label}</label>
                <input id={"df-f-" + f.id} className="ui-input df-num" value={values[f.id] ?? ""} onChange={(e) => setValues({ ...values, [f.id]: e.target.value })} />
                <Chip tone="ok" dot={false}>{f.confidence}%</Chip>
              </div>
            ))}

            <div className="df-small" style={{ fontWeight: 700, marginTop: 8 }}>CHECKS</div>
            {task.checks.map((c) => {
              const passed = c.passed || (!c.passed && flagged.every((f) => fixed(f.id)));
              return (
                <div key={c.text} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <span className="df-mark" style={{ background: passed ? "var(--ui-run)" : "#c0233f" }} aria-hidden="true">{passed ? "✓" : "✕"}</span>
                  <span>{c.text}</span><span className="df-small">{passed && !c.passed ? "passes with the corrected value" : c.detail}</span>
                  <span className="sr-only">{passed ? "passed" : "failed"}</span>
                </div>
              );
            })}
          </div>
          <div className="df-head-row" style={{ padding: "12px 20px", borderTop: "1px solid var(--ui-border)", background: "var(--ui-soft)" }}>
            <Button kind="danger" disabled={saving} onClick={() => finish("reject")}>Reject</Button><Hint about="Reject" text={HINTS.reviewReject} />
            <Button kind="plain" disabled={saving} onClick={openTypes}>Change document type</Button><Hint about="Change document type" text={HINTS.changeType} />
            <span className="df-grow" />
            <Button kind="run" disabled={saving} onClick={() => finish("approve")}>Approve and next <span style={{ fontWeight: 400, opacity: .85, fontSize: 12 }}>Ctrl+Enter</span></Button>
          </div>
        </section>
      </div>
      <Dialog open={!!types} onClose={() => setTypes(null)} title="Change document type" width={480}
        footer={<><Button onClick={() => setTypes(null)}>Cancel</Button><Button kind="primary" disabled={!newType || saving} onClick={reclassify}>Read again</Button></>}>
        <p style={{ margin: 0 }}>DocFlow reads {task.name} again as the type you choose. The current reading is marked as replaced.</p>
        <Field label="Document type">{(id) => <Select id={id} value={newType} onChange={(e) => setNewType(e.target.value)}
          options={[{ value: "", label: "Choose a type" }, ...(types ?? []).filter((t) => t.name !== task.docType).map((t) => ({ value: t.id, label: t.name }))]} />}</Field>
      </Dialog>
    </div>
  );
}
