import { useEffect, useState } from "react";
import { ApiFailure, type StudioApi, type Trigger } from "../api/client";
import { Button, Field, Select, TextInput } from "../components";

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
export function TriggersPanel({ api, path }: { api: StudioApi; path: string }) {
  const [all, setAll] = useState<Trigger[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [kind, setKind] = useState<Trigger["type"]>("schedule");
  const [every, setEvery] = useState("60");
  const [daily, setDaily] = useState("");
  const [folder, setFolder] = useState("");
  const [pattern, setPattern] = useState("*");
  const [shownSecret, setShownSecret] = useState<string | null>(null);

  useEffect(() => { api.triggers().then(setAll).catch((e) => setError(msg(e))); }, [api]);
  const mine = (all ?? []).filter((t) => t.bot === path);
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
  return (
    <div className="st-triggers">
      <p className="st-pad st-muted">Start this agent on its own, on this computer, while Studio is running.</p>
      <ul className="st-trigger-list">
        {mine.map((t) => (
          <li key={t.id}>
            <div><strong>{triggerText(t)}</strong>{!t.enabled && <span className="st-badge">OFF</span>}</div>
            <div className="st-trigger-actions">
              <Button kind="plain" onClick={() => save(all.map((x) => x.id === t.id ? { ...x, enabled: !x.enabled } : x))}>{t.enabled ? "Switch off" : "Switch on"}</Button>
              <Button kind="plain" onClick={() => api.runTrigger(t.id).catch((e) => setError(msg(e)))}>Run now</Button>
              <Button kind="plain" aria-label={"Delete trigger: " + triggerText(t)} onClick={() => save(all.filter((x) => x.id !== t.id))}>Delete</Button>
            </div>
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
          <Field label="Folder" layout="beside">{(id) => <TextInput id={id} value={folder} placeholder="C:\\Inbox or a workspace folder" onChange={(e) => setFolder(e.target.value)} />}</Field>
          <Field label="Files" layout="beside">{(id) => <TextInput id={id} value={pattern} placeholder="*.pdf" onChange={(e) => setPattern(e.target.value)} />}</Field>
        </>}
        {error && <p role="alert" className="st-error">{error}</p>}
        <Button kind="secondary" type="submit">Add trigger</Button>
      </form>
    </div>
  );
}

const msg = (e: unknown) => e instanceof ApiFailure ? e.error.message + (e.error.hint ? " " + e.error.hint : "") : String(e);
