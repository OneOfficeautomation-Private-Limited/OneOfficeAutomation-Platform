import { useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import type { DocFlowApi } from "./api";
import { reportError, useLoad } from "./ui";

/** "1-3, 5" → [1, 2, 3, 5]; null when it is not a page list. */
export function pageList(text: string): number[] | null {
  const out: number[] = [];
  for (const part of text.split(/[,;\s]+/).filter(Boolean)) {
    const m = part.match(/^(\d+)(?:-(\d+))?$/);
    if (!m) return null;
    const a = Number(m[1]), b = Number(m[2] ?? m[1]);
    if (a < 1 || b < a) return null;
    for (let i = a; i <= b; i++) out.push(i);
  }
  return out.length ? out : null;
}

/**
 * Change the split of a scanned batch: which pages make each document and what type each is. The documents split
 * from it before are replaced and each part is read again (an approved part cannot be changed).
 */
export function SplitDialog({ api, documentId, name, pages, onClose, onDone }:
  { api: DocFlowApi; documentId: string; name: string; pages: number; onClose: () => void; onDone: () => void }) {
  const [types] = useLoad(() => api.docTypes(), [api]);
  const [parts, setParts] = useState<{ pages: string; type: string }[]>([{ pages: pages > 1 ? `1-${pages}` : "1", type: "" }]);
  const [busy, setBusy] = useState(false);
  const lists = parts.map((p) => pageList(p.pages));
  const used = lists.flatMap((l) => l ?? []);
  const problem = lists.some((l) => !l) ? "Write pages as 1-3, 5" : used.some((n) => n > pages) ? `The batch has ${pages} page${pages > 1 ? "s" : ""}`
    : new Set(used).size !== used.length ? "A page can be in one part only" : "";
  const save = async () => {
    setBusy(true);
    try { await api.resplit(documentId, parts.map((p, i) => ({ pages: lists[i]!, docType: p.type || null }))); onDone(); }
    catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title={`Change the split of ${name}`} width={600}
      footer={<><span className="df-text-warn" style={{ marginRight: "auto" }}>{problem}</span><Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!!problem || busy} onClick={save}>{busy ? "Splitting…" : "Split and read again"}</Button></>}>
      <p className="df-muted" style={{ margin: 0 }}>Each part becomes a document of its own. The documents split from this batch before are replaced.</p>
      {parts.map((p, i) => (
        <div key={i} className="df-grid" style={{ gridTemplateColumns: "1fr 2fr auto", gap: 10, alignItems: "end" }}>
          <Field label={`Part ${i + 1}: pages`}>{(id) => <TextInput id={id} value={p.pages} placeholder="1-3" onChange={(e) => setParts(parts.map((x, j) => (j === i ? { ...x, pages: e.target.value } : x)))} />}</Field>
          <Field label="Document type">{(id) => <Select id={id} value={p.type} onChange={(e) => setParts(parts.map((x, j) => (j === i ? { ...x, type: e.target.value } : x)))}
            options={[{ value: "", label: "Recognise it" }, ...(types ?? []).map((t) => ({ value: t.id, label: t.name }))]} />}</Field>
          <Button kind="plain" disabled={parts.length === 1} aria-label={`Remove part ${i + 1}`} onClick={() => setParts(parts.filter((_, j) => j !== i))}>Remove</Button>
        </div>
      ))}
      <div><Button icon="plus" onClick={() => setParts([...parts, { pages: "", type: "" }])}>Add a part</Button></div>
    </Dialog>
  );
}
