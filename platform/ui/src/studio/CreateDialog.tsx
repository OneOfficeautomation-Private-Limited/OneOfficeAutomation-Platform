import { useState } from "react";
import { ApiFailure, type NewItemKind } from "../api/client";
import { Button, Dialog, Field, IconTile, TextInput } from "../components";
import type { ItemType } from "./itemTypes";

/**
 * Step 3 of creating, in AA's order: the folder is already chosen, the type picked from Create; here only the name
 * is asked. The folder is shown, with Change to go back and pick another.
 */
export function CreateDialog({ type, folderPath, onCreate, onClose, onChangeFolder }:
  { type: ItemType | null; folderPath: string; onCreate: (kind: NewItemKind, name: string) => Promise<void>; onClose: () => void; onChangeFolder: () => void }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!type) return null;

  const submit = async () => {
    if (!name.trim()) { setError("Give it a name."); return; }
    setBusy(true); setError(null);
    try { await onCreate(type.kind, name.trim()); }
    catch (e) { setError(e instanceof ApiFailure ? e.error.message + (e.error.hint ? " " + e.error.hint : "") : String(e)); }
    finally { setBusy(false); }
  };

  return (
    <Dialog open onClose={onClose} title={"New " + type.name.toLowerCase()} icon={<IconTile name={type.icon} color={type.color} size={28} />}
      footer={<>
        <Button kind="secondary" onClick={onClose}>Cancel</Button>
        <Button kind="primary" onClick={submit} disabled={busy}>{busy ? "Creating…" : "Create and edit"}</Button>
      </>}>
      <form onSubmit={(e) => { e.preventDefault(); submit(); }} className="st-form">
        <Field label="Name">{(id) => <TextInput id={id} value={name} autoFocus placeholder={"For example: Invoice intake Germany"}
          onChange={(e) => setName(e.target.value)} aria-invalid={!!error} />}</Field>
        <Field label="Folder">{(id) => (
          <div className="st-folder-line" id={id}>
            <IconTile name="folder" color="#a16207" size={20} /><span className="st-folder-name">{folderPath ? folderPath.replace(/\//g, " › ") : "Workspace"}</span>
            <Button kind="plain" onClick={onChangeFolder}>Change</Button>
          </div>)}</Field>
        {error && <p role="alert" className="st-error">{error}</p>}
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}
