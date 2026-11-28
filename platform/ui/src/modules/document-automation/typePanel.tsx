import { useEffect, useState } from "react";
import { Button, Dialog } from "../../components";
import type { DocFlowApi, DocType } from "./api";
import { DocTypeEditor } from "./DocTypes";
import { Loading, dfHash, reportError } from "./ui";

/**
 * Edit a document type beside the step (walkthrough 28 Nov 2026, artboard "Nav A"): the type opens in a wide panel
 * over the process step, so the process stays in sight and Save brings you back to the step. A type other processes
 * share says so, with "Make a copy for <process> only": the copy is made, this process reads it instead, and the
 * panel goes on with the copy (for example to keep only some of its fields). The full page stays one link away.
 */
export function TypePanel({ api, typeId, processName, step, onClose, onSaved, onCopied }: {
  api: DocFlowApi; typeId: string; processName: string;
  /** Where the panel was opened, for its path and its Save button ("step 3"). */
  step: string;
  onClose: () => void;
  /** The type was saved: the step shows it again. */
  onSaved: (t: DocType) => void;
  /** A copy for this process only was made: the process reads it from now on (the process still needs saving). */
  onCopied: (copy: DocType, from: DocType) => void;
}) {
  const [type, setType] = useState<DocType | null>(null);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api.docTypes().then((all) => {
      const t = all.find((x) => x.id === typeId);
      if (t) setType(t); else { reportError(new Error("This document type is gone: it may have been deleted")); onClose(); }
    }, (e) => { reportError(e); onClose(); });
  }, [api, typeId]);
  const others = type ? type.usedBy.filter((p) => p !== processName).length : 0;
  const copy = async () => {
    if (!type) return;
    setBusy(true);
    try {
      const made = await api.saveDocType({ ...structuredClone(type), id: "", origin: "made-here", name: `${type.name} (${processName})`, usedBy: [] });
      onCopied(made, type);
      setType(made); setCopied(true);
    } catch (e) { reportError(e); } finally { setBusy(false); }
  };
  const library = location.pathname + location.search + dfHash() + "types/" + encodeURIComponent(type?.id ?? typeId);
  return (
    <Dialog open onClose={onClose} className="df-type-panel" title={type ? type.name : "Document type"} width={900}
      footer={<>
        <a href={library} target="_blank" rel="noopener" style={{ fontWeight: 600, marginInlineEnd: "auto", alignSelf: "center" }}>Open in Libraries ↗</a>
        <Button onClick={onClose}>Close</Button>
      </>}>
      <span className="df-small" aria-label="Where you are">{processName} › {step} › document type</span>
      {!type ? <Loading /> : <>
        {copied ? <p className="df-panel-note is-done" role="status">Copy made: {processName} now reads <b>{type.name}</b>. Change it here (for example remove the fields
          you do not need) and save; then save the process to keep the change.</p>
          : others > 0 && <div className="df-panel-note" role="note">
            <span style={{ flex: 1 }}><b>Shared:</b> {processName} and {others} more process{others === 1 ? "" : "es"} read this type. A change here changes {others === 1 ? "both" : "them all"}.</span>
            <Button disabled={busy} onClick={copy}>Make a copy for {processName} only</Button>
          </div>}
        <DocTypeEditor key={type.id} api={api} value={type} showTest={false} saveLabel={`Save and back to ${step}`}
          onSaved={(t) => { onSaved(t); onClose(); }} />
      </>}
    </Dialog>
  );
}
