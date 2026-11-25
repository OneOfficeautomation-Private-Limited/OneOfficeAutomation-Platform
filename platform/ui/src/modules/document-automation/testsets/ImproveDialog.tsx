import { useEffect, useState } from "react";
import { Button, Dialog } from "../../../components";
import type { DocFlowApi, Improvement } from "../api";
import { Loading, reportError } from "../ui";

/**
 * Improve with AI (like blueprint instruction optimisation): from what the last test run read wrong, an AI reading
 * engine proposes a clearer instruction ("What it is") for each such field. The person ticks the ones to keep;
 * Apply saves them to the document type, and running the test again shows whether they helped.
 */
export function ImproveDialog({ api, setId, runId, onClose, onApplied }:
  { api: DocFlowApi; setId: string; runId?: string; onClose: () => void; onApplied: () => void }) {
  const [got, setGot] = useState<Improvement | null>(null);
  const [failed, setFailed] = useState("");
  const [keep, setKeep] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api.improveInstructions(setId, runId).then((r) => { setGot(r); setKeep(new Set(r.proposals.map((p) => p.name))); },
      (e) => setFailed(e instanceof Error ? e.message : String(e)));
  }, [api, setId, runId]);
  const apply = async () => {
    if (!got) return;
    setBusy(true);
    try {
      const type = (await api.docTypes()).find((t) => t.id === got.docType);
      if (!type) throw new Error("The document type is not there any more");
      const text = Object.fromEntries(got.proposals.filter((p) => keep.has(p.name)).map((p) => [p.name, p.proposed]));
      await api.saveDocType({ ...type, fields: type.fields.map((f) => (text[f.key] ? { ...f, description: text[f.key] } : f)) });
      onApplied();
    } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  return (
    <Dialog open onClose={onClose} title="Improve the instructions with AI" width={760}
      footer={<><Button onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!got || !keep.size || busy} onClick={apply}>{busy ? "Saving…" : `Apply ${keep.size} instruction${keep.size === 1 ? "" : "s"}`}</Button></>}>
      {failed ? <p className="df-text-warn" style={{ margin: 0 }}>{failed}</p> : !got ? <Loading /> : !got.proposals.length
        ? <p style={{ margin: 0 }}>Every field was read right in the last run: nothing to improve.</p> : <>
          <p className="df-muted" style={{ margin: 0 }}>For each field read wrong in the last run, {got.engine ?? "AI reading"} proposes a clearer instruction.
            Apply saves the ticked ones to the document type (every process that uses it); run the test again to compare.</p>
          <div className="df-table-wrap">
            <table className="df-table">
              <thead><tr><th><span className="sr-only">Keep</span></th><th>Field</th><th>Read wrong</th><th>Now</th><th>Proposed</th></tr></thead>
              <tbody>{got.proposals.map((p) => (
                <tr key={p.name}>
                  <td><input type="checkbox" aria-label={"Keep the instruction for " + p.label} checked={keep.has(p.name)}
                    onChange={(e) => { const k = new Set(keep); if (e.target.checked) k.add(p.name); else k.delete(p.name); setKeep(k); }} /></td>
                  <td><b>{p.label}</b><div className="df-small">{p.examples.slice(0, 2).map((x) => `right ${x.right}, read ${x.read ?? "nothing"}`).join("; ")}</div></td>
                  <td className="df-num">{p.wrong}</td>
                  <td className="df-muted">{p.current || "—"}</td>
                  <td>{p.proposed}</td>
                </tr>))}</tbody>
            </table>
          </div>
        </>}
    </Dialog>
  );
}
