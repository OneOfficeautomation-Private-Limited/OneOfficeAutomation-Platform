import { useEffect, useState } from "react";
import { type StudioApi, type Trigger, errorText } from "../api/client";
import { Button, Field, Icon, Menu, PathRow, Select, TextInput } from "../components";
import { PathBrowse } from "./FieldKinds";

const KINDS = [
  { value: "schedule", label: "On a schedule" },
  { value: "file", label: "When a file arrives in a folder" },
  { value: "webhook", label: "When a web address is called (webhook)" },
];

/** What a trigger does, in words. */
export function triggerText(t: Trigger): string {
  if (t.type === "schedule") return t.every_minutes ? `Every ${t.every_minutes} minute${t.every_minutes === 1 ? "" : "s"}` : `Every day at ${t.daily_at}`;
  if (t.type === "file") return `A file${t.pattern && t.pattern !== "*" ? " matching " + t.pattern : ""} arrives in ${t.folder}`;
  return "The webhook address is called";
}

/** A secret for a new webhook: 24 random characters, more than the 16 the server asks for. */
function newSecret(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...bytes)).replace(/[+/=]/g, "x");
}

/**
 * The triggers that start this agent on their own. The workspace keeps one list of triggers for every agent; this
 * panel shows and changes only this agent's, and saves the whole list back (the server checks each trigger).
 */
export function TriggersPanel({ api, path, kind: asked }: { api: StudioApi; path: string; kind?: string }) {
  const [all, setAll] = useState<Trigger[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [kind, setKind] = useState<Trigger["type"]>("schedule");
  useEffect(() => { if (asked && KINDS.some((k) => k.value === asked)) setKind(asked as Trigger["type"]); }, [asked]);   // chosen in the command list
  const [every, setEvery] = useState("60");
  const [daily, setDaily] = useState("");
  const [folder, setFolder] = useState("");
  const [pattern, setPattern] = useState("*");
  const [shownSecret, setShownSecret] = useState<string | null>(null);

  useEffect(() => { api.triggers().then(setAll).catch((e) => setError(msg(e))); }, [api]);
  const mine = (all ?? []).filter((t) => t.bot === path);
  const [asking, setAsking] = useState<Trigger | null>(null);   // review round 2: a trigger to delete, asked first (it is saved at once, with no undo)
  const save = async (next: Trigger[]) => {
    try { setAll(await api.saveTriggers(next)); setError(null); return true; } catch (e) { setError(msg(e)); return false; }
  };
  const add = async () => {
    const id = "t" + Date.now().toString(36);
    const t: Trigger = { id, bot: path, type: kind, enabled: true };
    if (kind === "schedule") { if (daily) t.daily_at = daily; else t.every_minutes = Number(every); }
    if (kind === "file") { t.folder = folder.trim(); t.pattern = pattern.trim() || "*"; }
    if (kind === "webhook") t.secret = newSecret();
    if (await save([...(all ?? []), t]) && t.secret) setShownSecret(t.secret);
  };

  if (all === null) return <p className="st-muted st-pad">{error ?? "Loading triggers…"}</p>;
  const remove = async (t: Trigger) => { setAsking(null); await save(all.filter((x) => x.id !== t.id)); };
  return (
    <div className="st-triggers">
      <p className="st-pad st-muted">Start this agent on its own, on this computer, while OneAgentStudio is running.</p>
      {asking && <div className="st-ask" role="alert"><span>Delete the trigger “{triggerText(asking)}”? It stops starting this agent.</span>
        <Button kind="danger" onClick={() => { remove(asking); }}>Delete</Button><Button kind="plain" onClick={() => setAsking(null)}>Cancel</Button></div>}
      <ul className="st-trigger-list">
        {mine.map((t) => (
          <li key={t.id}>
            <div><strong>{triggerText(t)}</strong>{!t.enabled && <span className="st-badge">OFF</span>}</div>
            <Menu label={"Trigger " + triggerText(t) + " menu"} align="end" items={[
              { key: "run", label: "Run now", onSelect: () => { api.runTrigger(t.id).catch((e) => setError(msg(e))); } },
              { key: "switch", label: t.enabled ? "Switch off" : "Switch on", onSelect: () => { save(all.map((x) => x.id === t.id ? { ...x, enabled: !x.enabled } : x)); } },
              { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setAsking(t) },
            ]} trigger={<button type="button" className="st-tool" aria-label={"Actions for trigger: " + triggerText(t)} title="Actions"><Icon name="more" size={16} stroke={3} /></button>} />
          </li>
        ))}
        {!mine.length && <li className="st-muted">No triggers yet.</li>}
      </ul>
      {shownSecret && <p className="st-secret" role="status">Webhook secret, shown once: <code>{shownSecret}</code>. Callers send it in the X-OOA-Secret header.</p>}
      <form className="st-var-form" onSubmit={(e) => { e.preventDefault(); add(); }}>
        <h3 className="st-prop-section">New trigger</h3>
        <Field label="Start it" layout="beside">{(id) => <Select id={id} value={kind} onChange={(e) => setKind(e.target.value as Trigger["type"])} options={KINDS} />}</Field>
        {kind === "schedule" && <>
          <Field label="Every (minutes)" layout="beside">{(id) => <TextInput id={id} type="number" min={1} value={every} onChange={(e) => setEvery(e.target.value)} disabled={!!daily} />}</Field>
          <Field label="Or daily at" layout="beside" hint="Use a time such as 08:30 instead of minutes.">{(id) => <TextInput id={id} type="time" value={daily} onChange={(e) => setDaily(e.target.value)} />}</Field>
        </>}
        {kind === "file" && <>
          <Field label="Folder" layout="beside">{(id) => <PathRow><TextInput id={id} value={folder} placeholder="C:\\Inbox or a workspace folder" onChange={(e) => setFolder(e.target.value)} />
            <PathBrowse api={api} folder repo={false} start={folder} onPick={(p) => setFolder(p)} /></PathRow>}</Field>
          <Field label="Files" layout="beside">{(id) => <TextInput id={id} value={pattern} placeholder="*.pdf" onChange={(e) => setPattern(e.target.value)} />}</Field>
        </>}
        {error && <p role="alert" className="st-error">{error}</p>}
        <Button kind="secondary" type="submit">Add trigger</Button>
      </form>
    </div>
  );
}

const msg = errorText;
