import { useState } from "react";
import type { WorkspaceNode } from "../api/client";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/** Up to 50 characters, as AA's file names; the type ending (.flow.json, .form.json) is kept by the server. */
export const NAME_LIMIT = 50;

/** Every folder of the workspace as "Agents › Finance › Monthly", for the Folder choice. */
function folders(n: WorkspaceNode, out: { value: string; label: string }[] = [], trail: string[] = []): { value: string; label: string }[] {
  if (n.type !== "folder") return out;
  const here = n.path ? [...trail, n.name] : ["Agents"];
  out.push({ value: n.path, label: here.join(" › ") });
  for (const c of n.children ?? []) folders(c, out, here);
  return out;
}

/**
 * File properties while editing (Studio defects 21 Nov 2026, item 2, as AA's editor: the name at the top left has
 * Edit): the name (up to 50 characters), the description, and the folder it is in. Save renames and moves the file;
 * Run agent steps keep working because they find an agent by its id, not its file name.
 */
export function FileProperties({ name, description, path, tree, isAgent, readOnly, onSave, onClose }:
  { name: string; description?: string; path: string; tree: WorkspaceNode | null; isAgent: boolean; readOnly?: string;
    onSave: (next: { name: string; description: string; folder: string }) => Promise<void>; onClose: () => void }) {
  const folderNow = path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
  const [n, setN] = useState(name);
  const [d, setD] = useState(description ?? "");
  const [f, setF] = useState(folderNow);
  const [busy, setBusy] = useState(false);
  const bad = !n.trim() ? "Give it a name." : n.trim().length > NAME_LIMIT ? "At most " + NAME_LIMIT + " characters." : /[\\/:*?"<>|]/.test(n) ? "A name cannot hold \\ / : * ? \" < > |" : null;
  const save = async () => {
    if (bad || readOnly) return;
    setBusy(true);
    try { await onSave({ name: n.trim(), description: d, folder: f }); onClose(); }
    finally { setBusy(false); }
  };
  return (
    <Dialog open title="File properties" width={480} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" onClick={save} disabled={!!bad || !!readOnly || busy}>{busy ? "Saving…" : "Save"}</Button></>}>
      <form className="st-form" onSubmit={(e) => { e.preventDefault(); save(); }}>
        {readOnly && <p className="st-alert" role="alert">{readOnly}</p>}
        <Field label="Name *" hint={bad ?? (isAgent ? "Run agent steps keep working: they find the agent by its id." : undefined)}>
          {(id) => <TextInput id={id} value={n} maxLength={NAME_LIMIT + 10} onChange={(e) => setN(e.target.value)} data-autofocus aria-invalid={!!bad} disabled={!!readOnly} />}
        </Field>
        {isAgent && <Field label="Description" hint="What the agent does: people who run it read this, and AI agents that may call it.">
          {(id) => <TextArea id={id} rows={3} value={d} maxLength={255} onChange={(e) => setD(e.target.value)} disabled={!!readOnly} />}
        </Field>}
        {tree && <Field label="Folder">
          {(id) => <Select id={id} value={f} onChange={(e) => setF(e.target.value)} options={folders(tree)} disabled={!!readOnly} />}
        </Field>}
      </form>
    </Dialog>
  );
}
