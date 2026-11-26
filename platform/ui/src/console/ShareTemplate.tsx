import { useEffect, useState } from "react";
import type { CrApi } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput, reportMinor } from "../components";

/**
 * Share as template (review round, 26 Nov 2026): from an agent's row menu in Build > Agents, its live version and the
 * agents it calls go to the region's template catalogue; once the service's operators approve it, others install it from
 * Create > Start from > Template. (Until 26 Nov 2026 it was "Share an agent" on the Agent Templates page.)
 */
const agentName = (p: string) => p.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function ShareTemplate({ api, categories, path, onClose, onDone }: { api: CrApi; categories: string[]; path?: string; onClose: () => void; onDone: (m: string) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [v, setV] = useState({ path: path ?? "", name: "", description: "", category: categories[0] ?? "Other" });
  const [error, setError] = useState<string | null>(null);
  const [flows, setFlows] = useState<{ id: string; name: string }[]>([]);
  const [chosen, setChosen] = useState<string[]>([]);
  useEffect(() => { api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); setV((o) => ({ ...o, path: o.path || b[0]?.path || "" })); }, (e) => reportMinor("the list of agents", e)); }, [api]);
  useEffect(() => { api.documentProcesses().then((f) => setFlows(f.filter((x) => !x.isInbox))); }, [api]);
  return (
    <Dialog open title="Share as template" width={600} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!v.path} onClick={() => api.shareTemplate({ ...v, name: v.name.trim() || agentName(v.path), description: v.description.trim(), ...(chosen.length ? { processes: chosen } : {}) })
        .then(() => onDone("Sent for approval: once approved it is offered in Create > Start from > Template."), (e) => setError(message(e)))}>Share</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">Its live version and the agents it calls go to your region's template catalogue. Once the service's operators approve it, every company in the region can install it.</p>
      <Field label="Agent">{(id) => <Select id={id} value={v.path} onChange={(e) => setV({ ...v, path: e.target.value })} options={agents.map((a) => ({ value: a, label: agentName(a) }))} />}</Field>
      <Field label="Template name">{(id) => <TextInput id={id} value={v.name} maxLength={120} placeholder={v.path ? agentName(v.path) : ""} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <Field label="What it does">{(id) => <TextArea id={id} rows={3} maxLength={2000} value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      <Field label="Category">{(id) => <Select id={id} value={v.category} onChange={(e) => setV({ ...v, category: e.target.value })} options={categories.map((c) => ({ value: c, label: c }))} />}</Field>
      {!!flows.length && <fieldset><legend>Document Automation processes that go with it (optional)</legend>
        {flows.map((f) => <label key={f.id} className="st-check-line"><input type="checkbox" checked={chosen.includes(f.id)}
          onChange={(e) => setChosen(e.target.checked ? [...chosen, f.id] : chosen.filter((x) => x !== f.id))} /> {f.name}</label>)}
        <p className="st-muted">Their settings and document types go along; their email inbox, watched folder, delivery passwords and what they learned stay here.</p></fieldset>}
    </Dialog>
  );
}
