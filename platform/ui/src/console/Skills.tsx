import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, PromptSkill, SkillDefinition } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput } from "../components";

/**
 * AI > Prompt Skills, built as AA's AI Skills (checked against AA's docs and UiPath's prompt and agent governance, 31 Oct
 * 2026): reviewed prompt templates agents run with the Prompt Skills toolkit. Private until shared; every save is a new
 * version with a comment. A skill opens with its instructions, prompt (${name} for inputs, found by themselves), answer
 * kind, temperature, connection and its versions. Templates give a starting point.
 */
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const found = (prompt: string) => [...new Set((prompt.match(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g) ?? []).map((x) => x.slice(2, -1)))];

export function Skills({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<PromptSkill[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [who, setWho] = useState("");
  const [dialog, setDialog] = useState<null | { kind: "skill"; name: string | null } | { kind: "templates" }>(null);
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => { try { setList(await api.skills()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((s) => (!q.trim() || (s.name + " " + (s.description ?? "")).toLowerCase().includes(q.trim().toLowerCase()))
    && (!who || (who === "mine" ? s.owner === user.username : who === "shared" ? s.shared : !!s.template))), [list, q, who, user.username]);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-sk-title">
      <header className="st-page-head">
        <div><h1 id="cc-sk-title">Prompt Skills</h1><p>Reviewed prompt templates agents run with the Prompt Skills toolkit. Private until shared; every save is a new version.</p></div>
        {can("skills.manage") && <span className="cc-inline">
          <Button kind="secondary" onClick={() => setDialog({ kind: "templates" })}>From a template</Button>
          <Button kind="primary" onClick={() => setDialog({ kind: "skill", name: null })}>Create skill</Button>
        </span>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search skills" placeholder="Search skills" value={q} onChange={(e) => setQ(e.target.value)} />
        <Select aria-label="Show" value={who} onChange={(e) => setWho(e.target.value)} options={[{ value: "", label: "Every skill" }, { value: "mine", label: "Mine" }, { value: "shared", label: "Shared" }, { value: "template", label: "Templates" }]} />
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Skills list">
          <thead><tr><th scope="col">Skill</th><th scope="col">Shared</th><th scope="col">Version</th><th scope="col">Updated</th></tr></thead>
          <tbody>
            {shown.map((s) => (
              <tr key={s.name} onClick={() => setDialog({ kind: "skill", name: s.name })}>
                <td>{s.name}{s.template && <span className="cc-tag">template</span>}{s.description && <div className="st-muted">{s.description}</div>}</td>
                <td>{s.shared ? "Shared" : "Private to " + s.owner}</td><td>v{s.version}</td><td>{when(s.updated)}{s.updated_by ? " by " + s.updated_by : ""}</td>
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={4} className="st-muted">{list.length ? "No skills match." : "No Prompt Skills yet. Start from a template."}</td></tr>}
            {!list && !error && <tr><td colSpan={4} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog?.kind === "skill" && <SkillEditor key={dialog.name ?? "new"} api={api} name={dialog.name} user={user} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "templates" && <Templates api={api} onClose={() => setDialog(null)} onMade={(n) => { onNotice("Made " + n + " from a template."); load(); setDialog({ kind: "skill", name: n }); }} />}
    </section>
  );
}

/** A skill: instructions, prompt, answer, temperature, connection, inputs (with those the prompt uses), sharing, versions. */
function SkillEditor({ api, name, user, onClose, onSaved }: { api: CrApi; name: string | null; user: CrUser; onClose: () => void; onSaved: (t: string) => void }) {
  type Full = Awaited<ReturnType<CrApi["skill"]>>;
  const [s, setS] = useState<Full | null>(null);
  const [v, setV] = useState({ name: "", description: "", connection: "", system: "", prompt: "", output: "text", temperature: "0.2", inputs: "", shared: false, template: false, comment: "" });
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  useEffect(() => {
    if (!name) return;
    api.skill(name).then((x) => { setS(x); const d = x.definition;
      setV({ name: x.name, description: x.description ?? "", connection: d.connection ?? "", system: d.system ?? "", prompt: d.prompt ?? "", output: d.output ?? "text",
        temperature: String(d.temperature ?? 0.2), inputs: (d.inputs ?? []).map((i) => i.name + (i.description ? " - " + i.description : "")).join("\n"), shared: x.shared, template: !!x.template, comment: "" }); },
    (e) => setError(message(e)));
  }, [api, name]);
  if (name && !s) return <Dialog open title="Prompt Skill" width={760} onClose={onClose} footer={null}>{error ? <p className="st-alert" role="alert">{error}</p> : <p className="st-muted" role="status">Loading…</p>}</Dialog>;
  const can = (p: string) => user.permissions.includes(p);
  const mine = !s || s.owner === user.username || can("skills.all");
  const editable = mine && can("skills.manage");
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value });
  const save = () => {
    const listed = v.inputs.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const i = l.indexOf(" - "); return i > 0 ? { name: l.slice(0, i).trim(), description: l.slice(i + 3).trim() } : { name: l }; });
    for (const n of found(v.prompt)) if (!listed.some((i) => i.name === n)) listed.push({ name: n });
    const def: SkillDefinition = { prompt: v.prompt, output: v.output as "text" | "json", temperature: Number(v.temperature || 0), inputs: listed };
    if (v.system.trim()) def.system = v.system;
    if (v.connection.trim()) def.connection = v.connection.trim();
    api.saveSkill(v.name.trim(), { description: v.description, definition: def, shared: v.shared, template: v.template, comment: v.comment || null })
      .then((r) => onSaved("Saved " + r.name + " v" + r.version + "."), (e) => setError(message(e)));
  };
  const uses = found(v.prompt);
  return (
    <Dialog open title={s ? s.name + " (v" + s.version + ")" : "Create Prompt Skill"} width={820} onClose={onClose} footer={editable ? <>
      {s && <Button kind="danger" onClick={() => setConfirm(true)}>Delete</Button>}
      <span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!/^[A-Za-z0-9_.-]{1,80}$/.test(v.name.trim()) || !v.prompt.trim()} onClick={save}>{s ? "Save as a new version" : "Create skill"}</Button></> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert" role="alert"><span>Delete {s!.name} and its versions? Agents that run it will fail.</span>
        <Button kind="danger" onClick={() => api.deleteSkill(s!.name).then(() => onSaved("Deleted " + s!.name + "."), (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      {!editable && s && <p className="st-muted">Owned by {s.owner}: read only.</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} disabled={!!s} placeholder="summarise_complaint" onChange={set("name")} />}</Field>
        <Field label="Model connection (empty: the run's default)">{(id) => <TextInput id={id} value={v.connection} disabled={!editable} onChange={set("connection")} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={v.description} disabled={!editable} onChange={set("description")} />}</Field>
      <Field label="Instructions for the model (system)">{(id) => <TextArea id={id} rows={3} value={v.system} disabled={!editable} onChange={set("system")} />}</Field>
      <Field label="Prompt (use ${name} for the values the agent passes in)">{(id) => <TextArea id={id} rows={6} value={v.prompt} disabled={!editable} onChange={set("prompt")} />}</Field>
      {uses.length > 0 && <p className="st-muted">The prompt uses: {uses.join(", ")}.</p>}
      <div className="cc-grid2">
        <Field label="Answer">{(id) => <Select id={id} value={v.output} disabled={!editable} onChange={set("output")} options={[{ value: "text", label: "Text" }, { value: "json", label: "JSON" }]} />}</Field>
        <Field label="Temperature (0 = steady, 2 = varied)">{(id) => <TextInput id={id} type="number" step="0.1" min={0} max={2} value={v.temperature} disabled={!editable} onChange={set("temperature")} />}</Field>
      </div>
      <Field label="Inputs (one per line: name - description)">{(id) => <TextArea id={id} rows={3} value={v.inputs} disabled={!editable} onChange={set("inputs")} />}</Field>
      <label className="st-check-line"><input type="checkbox" checked={v.shared} disabled={!editable} onChange={(e) => setV({ ...v, shared: e.target.checked })} /> Shared: every agent may use it</label>
      {can("skills.all") && <label className="st-check-line"><input type="checkbox" checked={v.template} onChange={(e) => setV({ ...v, template: e.target.checked })} /> Template: offered as a starting point</label>}
      {editable && <Field label="What changed (optional)">{(id) => <TextInput id={id} value={v.comment} onChange={set("comment")} />}</Field>}
      {s?.versions && s.versions.length > 1 && <>
        <h3 className="cc-log-title">Versions</h3>
        <table className="st-table" aria-label="Versions"><tbody>{s.versions.map((x) => <tr key={x.version}><td>v{x.version}</td><td>{x.updated_by ?? ""}</td><td>{when(x.updated)}</td><td>{x.comment ?? ""}</td></tr>)}</tbody></table>
      </>}
    </Dialog>
  );
}

function Templates({ api, onClose, onMade }: { api: CrApi; onClose: () => void; onMade: (name: string) => void }) {
  const [list, setList] = useState<{ name: string; description?: string }[] | null>(null);
  const [pick, setPick] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.skillTemplates().then(setList, (e) => setError(message(e))); }, [api]);
  return (
    <Dialog open title="Start from a template" width={640} onClose={onClose} footer={pick ? <>
      <Button kind="plain" onClick={() => setPick(null)}>Back</Button>
      <Button kind="primary" disabled={!name.trim()} onClick={() => api.skillFromTemplate(pick, name.trim()).then((s) => onMade(s.name), (e) => setError(message(e)))}>Make my skill</Button></> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {pick ? <Field label="Name of your new skill">{(id) => <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        : <table className="st-table" aria-label="Templates"><tbody>
          {(list ?? []).map((t) => <tr key={t.name}><td>{t.name}</td><td>{t.description ?? ""}</td>
            <td><Button kind="secondary" onClick={() => { setPick(t.name); setName(t.name.replace(/^template_/, "")); }}>Use</Button></td></tr>)}
          {list && !list.length && <tr><td className="st-muted">No templates yet.</td></tr>}
        </tbody></table>}
    </Dialog>
  );
}
