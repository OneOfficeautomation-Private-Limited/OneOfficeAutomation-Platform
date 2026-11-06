import { useEffect, useState } from "react";
import { Button, Dialog, Field, Icon, IconButton, Select } from "../components";
import type { DocFlowApi, DocType, Place, ReviewTask, ReviewValues, TableRow } from "./api";
import { HINTS } from "./help";
import { AddToTestSet } from "./review/AddToTestSet";
import { EInvoicePanel } from "./review/EInvoicePanel";
import { PageView, type PageBox } from "./review/PageView";
import { TableField } from "./review/TableField";
import { Chip, Hint, Loading, reportError } from "./ui";

/**
 * The review task a reviewer opens from the Task inbox: the page itself with a box over every value read from it, why the
 * document needs a person (a banner for the whole document, the reason under each field), the fields that need a check
 * first, the tables, the e-invoice data, the checks, and Approve and next (Ctrl+Enter).
 * Choosing a field shows its box (turning the page when needed); clicking a box chooses its field.
 */
type Selection = { field: string; row: number | null } | null;

export function Review({ api, documentId }: { api: DocFlowApi; documentId: string }) {
  const [task, setTask] = useState<ReviewTask | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [rows, setRows] = useState<Record<string, TableRow[]>>({});
  const [pageIndex, setPageIndex] = useState(0);
  const [sel, setSel] = useState<Selection>(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    let live = true;
    void api.reviewTask(documentId).then((t) => {
      if (!live) return;
      setTask(t); setPageIndex(0); setSel(null);
      setValues(Object.fromEntries(t.fields.map((f) => [f.id, f.value])));
      setRows(Object.fromEntries(t.tables.map((x) => [x.id, x.rows])));
    });
    return () => { live = false; };
  }, [api, documentId]);

  const [types, setTypes] = useState<DocType[] | null>(null);
  const [newType, setNewType] = useState("");
  const back = task?.processId ? "#/process/" + encodeURIComponent(task.processId) : "#/inbox";
  const finish = async (decision: "approve" | "reject") => {
    if (!task || saving) return;
    setSaving(true);
    try {
      // tables go back only when the reviewer changed them, so an unchanged table is not counted as a correction
      const changed = task.tables.filter((t) => JSON.stringify(rows[t.id]) !== JSON.stringify(t.rows));
      const out: ReviewValues = { ...values, ...Object.fromEntries(changed.map((t) => [t.id, rows[t.id]])) };
      const r = await api.completeReview(task.documentId, out, decision);
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
  const setValue = (id: string, v: string) => setValues({ ...values, [id]: v });

  /** Chooses a field (or a table row) and turns to the page it is on. */
  const select = (field: string, row: number | null = null) => {
    setSel({ field, row });
    const t = task.tables.find((x) => x.id === field);
    const place: Place | undefined = t ? (row !== null ? t.rowPlaces?.[row] : t.place) : task.fields.find((f) => f.id === field)?.place;
    const at = place?.page != null ? task.pages.findIndex((p) => p.number === place.page) : -1;
    if (at >= 0) setPageIndex(at);
  };
  /** A box on the page was clicked: choose its field and put the cursor in it. */
  const onBox = (key: string) => {
    const [field, row] = key.split("#");
    const t = task.tables.find((x) => x.id === field);
    if (t && row !== undefined) { select(field, Number(row)); document.getElementById(`df-t-${field}-${row}-${t.columns[0]?.name}`)?.focus(); }
    else if (t) select(field);
    else { select(field); document.getElementById("df-f-" + field)?.focus(); }
  };
  const pageNo = task.pages[pageIndex]?.number;
  const boxes: PageBox[] = [
    ...task.fields.filter((f) => f.place?.bbox && f.place.page === pageNo)
      .map((f) => ({ key: f.id, label: f.label, bbox: f.place!.bbox!, flag: !!f.flagged && !fixed(f.id) })),
    ...task.tables.flatMap((t): PageBox[] => sel?.field === t.id && t.rowPlaces
      ? t.rowPlaces.flatMap((r, i) => (r.bbox && r.page === pageNo ? [{ key: `${t.id}#${i}`, label: `${t.label}, row ${i + 1}`, bbox: r.bbox, row: true }] : []))
      : t.place?.bbox && t.place.page === pageNo ? [{ key: t.id, label: t.label, bbox: t.place.bbox }] : []),
  ];
  const selectedKey = sel ? (sel.row !== null ? `${sel.field}#${sel.row}` : sel.field) : null;
  const isSel = (id: string) => sel?.field === id;

  return (
    <div style={{ display: "flex", flexDirection: "column", flex: 1, minHeight: 0 }}>
      <div className="df-head-row" style={{ padding: "12px 24px", borderBottom: "1px solid var(--ui-divider)" }}>
        <a className="ui-icon-btn" href={back} aria-label="Back to the process"><Icon name="chevronLeft" size={14} stroke={2.4} /></a>
        <div style={{ minWidth: 0 }}>
          <div className="df-head-row"><h1 style={{ margin: 0, fontSize: 18 }}>{task.name}</h1>
            {task.status === "review" ? <Chip tone="warn">Needs review</Chip>
              : task.status === "done" ? <Chip tone="ok">{task.reviewedBy ? "Approved by " + task.reviewedBy : "Done"}</Chip>
              : task.status === "failed" ? <Chip tone="bad">{task.reviewedBy ? "Rejected by " + task.reviewedBy : "Failed"}</Chip>
              : <Chip tone="mute">Processing</Chip>}
            {task.status === "done" && task.reviewedBy && task.docTypeId &&
              <AddToTestSet key={task.documentId} api={api} documentId={task.documentId} docTypeId={task.docTypeId} docType={task.docType} />}</div>
          <div className="df-muted">{task.processName} · {task.docType} · came in {task.cameIn} · {task.status === "review" ? <><b className="df-text-warn">due in {task.dueIn}</b> ({task.targetHours} h target)</> : <>{task.targetHours} h review target</>}</div>
        </div>
        <span className="df-grow" />
        <span className="df-muted">Task {task.position} of {task.of}</span>
        <IconButton icon="chevronLeft" label="Previous task" disabled={task.position === 1} />
        <IconButton icon="chevronRight" label="Next task" />
      </div>

      <div className="df-review">
        <section className="df-review-page" aria-label="Document page">
          {task.pages.length ? (
            <PageView name={task.name} pages={task.pages} index={Math.min(pageIndex, task.pages.length - 1)} onIndex={setPageIndex}
              boxes={boxes} selected={selectedKey} onSelect={onBox} trail={task.engineTrail} />
          ) : (<>
            <div className="df-review-tools"><span className="df-muted">No page image: the text read is shown · {task.engineTrail}</span></div>
            <div className="df-page-view">
              <div className="df-paper">
                {task.pageLines.map((l, i) => <p key={i} dir="auto" style={{ fontWeight: l.bold ? 700 : 400, fontSize: 13 }}>{l.text}</p>)}
                {!task.pageLines.length && <p className="df-muted">Nothing to show.</p>}
              </div>
            </div>
          </>)}
        </section>

        <section className="df-review-fields" aria-label="Fields">
          <div style={{ padding: "14px 20px", borderBottom: "1px solid var(--ui-divider)" }}>
            <b style={{ fontSize: 14 }}>{flagged.filter((f) => !fixed(f.id)).length > 0 ? `${flagged.length} field${flagged.length > 1 ? "s" : ""} need${flagged.length > 1 ? "" : "s"} you`
              : !flagged.length && task.documentReasons.length ? "The document needs your check" : "All flagged fields checked"}</b>
            <div className="df-muted">Click a field to see it on the page. Check the highlighted value, then approve.</div>
          </div>
          <div style={{ padding: "16px 20px", display: "flex", flexDirection: "column", gap: 14, overflow: "auto", flex: 1 }}>
            {task.documentReasons.length > 0 && (
              <div className="df-review-why" role="note" aria-label="Why this document needs review">
                <b>Why it needs review</b>
                <ul>{task.documentReasons.map((r) => <li key={r}>{r}</li>)}</ul>
              </div>
            )}
            {flagged.length > 0 && <div className="df-small" style={{ fontWeight: 700 }}>NEEDS YOUR CHECK</div>}
            {flagged.map((f) => (
              <div key={f.id} className={`df-flag${isSel(f.id) ? " is-selected" : ""}`}>
                <label htmlFor={"df-f-" + f.id} style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 600 }}>
                  {f.label}<span className="df-grow" />{fixed(f.id) ? <Chip tone="ok">Corrected</Chip> : f.value === "" ? <Chip tone="warn">Not read</Chip> : <Chip tone="warn">Read at {f.confidence}%</Chip>}</label>
                <input id={"df-f-" + f.id} className="ui-input df-num" dir="auto" style={{ marginTop: 6 }} value={values[f.id] ?? ""}
                  onFocus={() => select(f.id)} onChange={(e) => setValue(f.id, e.target.value)} />
                <Where place={f.place} />
                {!fixed(f.id) && <div className="df-text-warn" style={{ marginTop: 6 }}>{f.flagged!.reason}</div>}
                {f.flagged!.suggestion && !fixed(f.id) && (
                  <div className="df-card df-head-row" style={{ marginTop: 10, padding: "8px 10px" }}>
                    <span>Suggested <b className="df-num">{f.flagged!.suggestion}</b>: makes both checks pass</span><span className="df-grow" />
                    <Button onClick={() => setValue(f.id, f.flagged!.suggestion!)}>Use {f.flagged!.suggestion}</Button>
                  </div>
                )}
              </div>
            ))}

            <div className="df-small" style={{ fontWeight: 700, marginTop: 4 }}>CHECKED, NO ACTION NEEDED</div>
            {fine.map((f) => (
              <div key={f.id} className={`df-field-row${isSel(f.id) ? " is-selected" : ""}`}>
                <label htmlFor={"df-f-" + f.id} className="df-muted">{f.label}</label>
                <input id={"df-f-" + f.id} className="ui-input df-num" dir="auto" value={values[f.id] ?? ""}
                  onFocus={() => select(f.id)} onChange={(e) => setValue(f.id, e.target.value)} />
                <Chip tone="ok" dot={false}>{f.confidence}%</Chip>
                <Where place={f.place} grid />
              </div>
            ))}

            {task.tables.length > 0 && <div className="df-small" style={{ fontWeight: 700, marginTop: 8 }}>TABLES</div>}
            {task.tables.map((t) => (
              <TableField key={t.id} table={t} rows={rows[t.id] ?? []} onChange={(r) => setRows({ ...rows, [t.id]: r })}
                selected={isSel(t.id)} selectedRow={isSel(t.id) ? sel!.row : null} onSelectRow={(r) => select(t.id, r)} />
            ))}

            {task.einvoice && <EInvoicePanel einvoice={task.einvoice} />}

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

/** Where a value came from, when that matters to the reviewer: not found on the page, or confirmed by e-invoice data. */
function Where({ place, grid }: { place?: Place; grid?: boolean }) {
  const text = place?.grounded === false ? "Not found on the page" : place?.groundedBy === "qr" ? "Confirmed by the QR code"
    : place?.groundedBy === "einvoice-xml" ? "From the e-invoice XML" : "";
  if (!text) return null;
  return <span className={`df-small${place?.grounded === false ? " df-text-warn" : ""}`} style={grid ? { gridColumn: "2 / -1" } : { display: "block", marginTop: 4 }}>{text}</span>;
}
