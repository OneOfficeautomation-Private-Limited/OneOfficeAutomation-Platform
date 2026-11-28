import { useEffect, useState } from "react";
import { Button, Dialog, Field, Icon, IconButton, Select, TextInput } from "../../components";
import type { DocFlowApi, DocType, Place, ReviewTask, ReviewValues, TableRow } from "./api";
import { HINTS } from "./help";
import { AddToTestSet } from "./review/AddToTestSet";
import { EInvoicePanel } from "./review/EInvoicePanel";
import { PageView, type PageBox } from "./review/PageView";
import { TableField } from "./review/TableField";

/** Common reasons to reject a document; any other is typed. */
const REJECT_REASONS = ["Not a document we process", "Duplicate of one already received", "Unreadable or incomplete", "Wrong supplier or customer", "Sent to us by mistake"];
const OWN_REASON = "Other reason…";
import { Chip, Hint, Loading, reportError, dfHash, duration } from "./ui";

/** Python's errors when a check's formula meets a missing or wrong value: the check could not run. */
const CHECK_ERROR = /^(.*?)\s*\((TypeError|NameError|KeyError|ValueError|ZeroDivisionError)\)\s*$/;
/** A failed check in plain words (walkthrough 28 Nov 2026): a formula that could not run names the empty fields it uses. */
export function plainCheck(text: string, fields: { id: string; label: string }[], values: Record<string, string>): { text: string; formula?: string } {
  const m = CHECK_ERROR.exec(text.replace(/^Check failed:\s*/, ""));
  if (!m) return { text };
  const formula = m[1];
  const at = (f: { id: string }) => formula.search(new RegExp("(^|[^A-Za-z0-9_])" + f.id + "([^A-Za-z0-9_]|$)"));
  const used = fields.filter((f) => at(f) >= 0).sort((x, y) => at(x) - at(y));   // in the formula's order
  const empty = used.filter((f) => !(values[f.id] ?? "").trim());
  const names = (xs: { label: string }[]) => xs.length < 2 ? xs.map((x) => x.label).join("") : xs.slice(0, -1).map((x) => x.label).join(", ") + " and " + xs[xs.length - 1].label;
  return { formula, text: empty.length
    ? `Could not check ${names(used)}: ${names(empty)} ${empty.length === 1 ? "is" : "are"} empty`
    : "Could not run this check: a value it uses is missing or not a number" };
}

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
  const back = task?.processId ? dfHash() + "process/" + encodeURIComponent(task.processId) : dfHash() + "inbox";
  // Reject asks why: a reason from the list, or one typed (kept with the document)
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState(REJECT_REASONS[0]);
  const [ownReason, setOwnReason] = useState("");
  const finish = async (decision: "approve" | "reject", note = "") => {
    if (!task || saving) return;
    setSaving(true);
    try {
      // tables go back only when the reviewer changed them, so an unchanged table is not counted as a correction
      const changed = task.tables.filter((t) => JSON.stringify(rows[t.id]) !== JSON.stringify(t.rows));
      const out: ReviewValues = { ...values, ...Object.fromEntries(changed.map((t) => [t.id, rows[t.id]])) };
      const r = await api.completeReview(task.documentId, out, decision, note);
      location.hash = r.next ? dfHash() + "review/" + encodeURIComponent(r.next) : back;   // Approve and next: the next one waiting
    } catch (e) { reportError(e); } finally { setSaving(false); }
  };
  /** Previous task, Next task: another document waiting for review (what was typed here and not approved is not kept). */
  const open = (id?: string) => { if (id) location.hash = dfHash() + "review/" + encodeURIComponent(id); };
  const openTypes = () => { setNewType(""); api.docTypes().then(setTypes, reportError); };
  const reclassify = async () => {
    if (!task || !newType) return;
    setSaving(true);
    try {
      const r = await api.changeDocType(task.documentId, newType);
      setTypes(null);
      location.hash = r.needsReview ? dfHash() + "review/" + encodeURIComponent(r.documentId) : back;
    } catch (e) { reportError(e); } finally { setSaving(false); }
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); void finish("approve"); } };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
  if (!task) return <Loading />;

  const flagged = task.fields.filter((f) => f.flagged);
  // a value read but not found on the page may have been made up: it needs a look too
  const unplaced = task.fields.filter((f) => !f.flagged && f.value !== "" && f.place?.grounded === false);
  const fine = task.fields.filter((f) => !f.flagged && f.value !== "" && f.place?.grounded !== false);
  const absent = task.fields.filter((f) => !f.flagged && f.value === "");   // optional and not on the document
  const toCheck = flagged.length + unplaced.length;
  // 5: most required fields not found on an unsure document: it may be another kind of document
  const requiredMissing = flagged.filter((f) => f.value === "" && /^Required/i.test(f.flagged!.reason));
  const notThisType = requiredMissing.length >= 3;
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
              <AddToTestSet key={task.documentId} api={api} documentId={task.documentId} docTypeId={task.docTypeId} docType={task.docType} />}
            {task.status === "done" && task.reviewedBy && task.docTypeId && <TeachButton api={api} docTypeId={task.docTypeId} documentId={task.documentId} docType={task.docType} />}</div>
          <div className="df-muted">{task.processName} · {task.docType} · came in {task.cameIn} · {task.status === "review" ? (task.overdueHours ?? 0) > 0
            ? <><b className="df-text-bad">Overdue by {duration(Math.round(task.overdueHours! * 3600))}</b> ({task.targetHours} h target)</>
            : <><b className="df-text-warn">due in {task.dueIn}</b> ({task.targetHours} h target)</> : <>{task.targetHours} h review target</>}</div>
        </div>
        <span className="df-grow" />
        <span className="df-muted">Task {task.position} of {task.of}</span>
        <IconButton icon="chevronLeft" label="Previous task" disabled={!task.previousId} onClick={() => open(task.previousId)} />
        <IconButton icon="chevronRight" label="Next task" disabled={!task.nextId} onClick={() => open(task.nextId)} />
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
            <b style={{ fontSize: 14 }}>{flagged.filter((f) => !fixed(f.id)).length + unplaced.length > 0 ? `${toCheck} field${toCheck > 1 ? "s" : ""} need${toCheck > 1 ? "" : "s"} you`
              : !toCheck && task.documentReasons.length ? "The document needs your check" : "All flagged fields checked"}</b>
            <div className="df-muted">Click a field to see it on the page. Check the highlighted value, then approve.</div>
          </div>
          <div style={{ padding: "16px 20px", display: "flex", flexDirection: "column", gap: 14, overflow: "auto", flex: 1 }}>
            {notThisType && task.status === "review" && (
              <div className="df-panel-note" role="note" aria-label="It may be another kind of document">
                <span style={{ flex: 1 }}><b>This may not be {/^[aeiou]/i.test(task.docType) ? "an" : "a"} {task.docType}:</b> {requiredMissing.length} required fields were not found on it.</span>
                <Button disabled={saving} onClick={openTypes}>Change document type</Button>
                <Button kind="danger" disabled={saving} onClick={() => setRejecting(true)}>Reject</Button>
              </div>
            )}
            {task.documentReasons.length > 0 && (
              <div className="df-review-why" role="note" aria-label="Why this document needs review">
                <b>Why it needs review</b>
                <ul>{task.documentReasons.map((r) => { const p = plainCheck(r, task.fields, values); return <li key={r}>{p.text}{p.formula && <span className="df-small" style={{ display: "block" }}>{p.formula}</span>}</li>; })}</ul>
              </div>
            )}
            {toCheck > 0 && <div className="df-small" style={{ fontWeight: 700 }}>NEEDS YOUR CHECK</div>}
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

            {unplaced.map((f) => (
              <div key={f.id} className={`df-flag${isSel(f.id) ? " is-selected" : ""}`}>
                <label htmlFor={"df-f-" + f.id} style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 600 }}>
                  {f.label}<span className="df-grow" /><Chip tone="warn">Read at {f.confidence}%</Chip></label>
                <input id={"df-f-" + f.id} className="ui-input df-num" dir="auto" style={{ marginTop: 6 }} value={values[f.id] ?? ""}
                  onFocus={() => select(f.id)} onChange={(e) => setValue(f.id, e.target.value)} />
                <div className="df-text-warn" style={{ marginTop: 6 }}>Not found on the page: check it against the document</div>
              </div>
            ))}

            {fine.length > 0 && <div className="df-small" style={{ fontWeight: 700, marginTop: 4 }}>CHECKED, NO ACTION NEEDED</div>}
            {fine.map((f) => (
              <div key={f.id} className={`df-field-row${isSel(f.id) ? " is-selected" : ""}`}>
                <label htmlFor={"df-f-" + f.id} className="df-muted">{f.label}</label>
                <input id={"df-f-" + f.id} className="ui-input df-num" dir="auto" value={values[f.id] ?? ""}
                  onFocus={() => select(f.id)} onChange={(e) => setValue(f.id, e.target.value)} />
                <Chip tone="ok" dot={false}>{f.confidence}%</Chip>
                <Where place={f.place} grid />
              </div>
            ))}

            {absent.length > 0 && <>
              <div className="df-small" style={{ fontWeight: 700, marginTop: 4 }}>NOT ON THE DOCUMENT</div>
              <span className="df-small">Optional, and not found. Type a value if the document has one.</span>
              {absent.map((f) => (
                <div key={f.id} className={`df-field-row${isSel(f.id) ? " is-selected" : ""}`}>
                  <label htmlFor={"df-f-" + f.id} className="df-muted">{f.label}</label>
                  <input id={"df-f-" + f.id} className="ui-input df-num" dir="auto" value={values[f.id] ?? ""} placeholder="Not found"
                    onFocus={() => select(f.id)} onChange={(e) => setValue(f.id, e.target.value)} />
                </div>
              ))}
            </>}

            {task.tables.length > 0 && <div className="df-small" style={{ fontWeight: 700, marginTop: 8 }}>TABLES</div>}
            {task.tables.map((t) => {
              const cur = rows[t.id] ?? [];
              // 6: rows with nothing in them are not shown as rows: the table says nothing was read
              if (cur.length && cur.every((r) => t.columns.every((c) => !(r[c.name] ?? "").trim())) && cur === t.rows) return (
                <div key={t.id} className="df-field-row" aria-label={t.label}>
                  <span className="df-muted">{t.label}</span><span className="df-small">No {t.label.toLowerCase()} were read.</span>
                  <Button kind="plain" onClick={() => setRows({ ...rows, [t.id]: [Object.fromEntries(t.columns.map((c) => [c.name, ""]))] })}>Add a row</Button>
                </div>);
              return <TableField key={t.id} table={t} rows={cur} onChange={(r) => setRows({ ...rows, [t.id]: r })}
                selected={isSel(t.id)} selectedRow={isSel(t.id) ? sel!.row : null} onSelectRow={(r) => select(t.id, r)} />;
            })}

            {task.einvoice && <EInvoicePanel einvoice={task.einvoice} />}

            <div className="df-small" style={{ fontWeight: 700, marginTop: 8 }}>CHECKS</div>
            {task.checks.map((c) => {
              const passed = c.passed || (!c.passed && flagged.every((f) => fixed(f.id)));
              return (
                <div key={c.text} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <span className="df-mark" style={{ background: passed ? "var(--ui-run)" : "#c0233f" }} aria-hidden="true">{passed ? "✓" : "✕"}</span>
                  <span>{plainCheck(c.text, task.fields, values).text}</span><span className="df-small">{passed && !c.passed ? "passes with the corrected value" : plainCheck(c.text, task.fields, values).formula ?? c.detail}</span>
                  <span className="sr-only">{passed ? "passed" : "failed"}</span>
                </div>
              );
            })}
          </div>
          <div className="df-head-row" style={{ padding: "12px 20px", borderTop: "1px solid var(--ui-border)", background: "var(--ui-soft)" }}>
            <Button kind="danger" disabled={saving} onClick={() => setRejecting(true)}>Reject</Button><Hint about="Reject" text={HINTS.reviewReject} />
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
          options={[{ value: "", label: "Choose a type" }, ...(types ?? []).filter((t) => t.name !== task.docType && t.id !== "other").map((t) => ({ value: t.id, label: t.name })),
            ...((types ?? []).some((t) => t.id === "other") ? [{ value: "other", label: "None of these: keep it as an Other document" }] : [])]} />}</Field>
        <p className="df-small" style={{ margin: 0 }}>Not listed? Make the type on the Document types page (Teach from samples reads this kind of document for you), then come back.</p>
      </Dialog>
      <Dialog open={rejecting} onClose={() => setRejecting(false)} title={`Reject ${task.name}?`} width={480}
        footer={<><Button onClick={() => setRejecting(false)}>Cancel</Button>
          <Button kind="danger" disabled={saving || (reason === OWN_REASON && !ownReason.trim())}
            onClick={() => { setRejecting(false); void finish("reject", reason === OWN_REASON ? ownReason : reason); }}>Reject</Button></>}>
        <Field label="Why">{(id) => <Select id={id} value={reason} onChange={(e) => setReason(e.target.value)}
          options={[...REJECT_REASONS, OWN_REASON].map((r) => ({ value: r, label: r }))} />}</Field>
        {reason === OWN_REASON && <Field label="Reason">{(id) => <TextInput id={id} value={ownReason} onChange={(e) => setOwnReason(e.target.value)} />}</Field>}
        <p className="df-small" style={{ margin: 0 }}>The reason is kept with the document and sent with its results.</p>
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

/** Teach the type with it: an approved document becomes a teaching example; AI reading sees its right values. */
function TeachButton({ api, docTypeId, documentId, docType }: { api: DocFlowApi; docTypeId: string; documentId: string; docType: string }) {
  const [done, setDone] = useState(false);
  if (done) return <span className="df-small" role="status">A teaching example of {docType}</span>;
  return <Button kind="plain" title={`AI reading of ${docType} sees this document's approved values as an example`}
    onClick={() => api.addTeachingExample(docTypeId, documentId).then(() => setDone(true), reportError)}>Teach {docType} with it</Button>;
}
