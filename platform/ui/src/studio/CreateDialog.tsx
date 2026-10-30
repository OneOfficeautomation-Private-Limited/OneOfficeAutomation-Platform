import { useState } from "react";
import { ApiFailure, type Blueprint, type NewItemKind } from "../api/client";
import { Button, Dialog, Field, IconTile, SearchSelect, TextInput } from "../components";
import type { ItemType } from "./itemTypes";

/**
 * Step 3 of creating, in AA's order: the folder is already chosen, the type picked from Create; here the name, and
 * for an agent what to start from (blank or a blueprint). Templates live only here, not in the menu. The folder is
 * shown, with Change to go back and pick another.
 */
export function CreateDialog({ type, folderPath, blueprints = [], onCreate, onClose, onChangeFolder }:
  { type: ItemType | null; folderPath: string; blueprints?: Blueprint[]; onCreate: (kind: NewItemKind, name: string, from: Blueprint | null) => Promise<void>;
    onClose: () => void; onChangeFolder: () => void }) {
  const [name, setName] = useState("");
  const [start, setStart] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!type) return null;
  const offers = type.kind === "TaskBot" ? blueprints : [];
  const from = offers.find((b) => b.id === start) ?? null;

  const submit = async () => {
    if (!name.trim()) { setError("Give it a name."); return; }
    setBusy(true); setError(null);
    try { await onCreate(type.kind, name.trim(), from); }
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
        <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder={"For example: Invoice intake Germany"}
          onChange={(e) => setName(e.target.value)} aria-invalid={!!error} />}</Field>
        {offers.length > 0 && (
          <Field label="Start from" hint={(from ? from.description : "An empty agent.") + " Type to search."}>{(id) =>
            <SearchSelect id={id} value={start} placeholder="Search blueprints" onChange={(v) => {
              const b = offers.find((x) => x.id === v);
              setStart(v);
              if (b && !name.trim()) setName(b.name);   // a sensible name to start with; it can be changed
            }} options={[{ value: "", label: "Blank agent", description: "An empty agent." }, ...offers.map((b) => ({ value: b.id, label: b.name, description: b.description }))]} />}</Field>
        )}
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
