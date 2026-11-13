import "./consoleApi";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser } from "../../api/cr";
import type { PromptSkill, SkillDefinition } from "./consoleApi";
import { Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic, PageError } from "../../components";
import { RefreshButton, RowMenu, rowClick, when, CloseView } from "../../console/ListParts";

/**
 * AI > Prompt Skills, built as AA's AI Skills (checked against AA's docs and UiPath's prompt and agent governance, 31 Oct
 * 2026): reviewed prompt templates agents run with the Prompt Skills toolkit. Private until shared; every save is a new
 * version with a comment. A skill opens with its instructions, prompt (${name} for inputs, found by themselves), answer
 * kind, temperature, connection and its versions. Templates give a starting point.
 * Review round 2 (1 Nov 2026): one line per skill (its description on hover), a menu per skill (Open, Copy the name,
 * Delete asked first), and a skill opening as its own page (Prompt; Versions), as AA's skill editor.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const found = (prompt: string) => [...new Set((prompt.match(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g) ?? []).map((x) => x.slice(2, -1)))];

/** The bar that asks before a skill and its versions are deleted. */
function AskDelete({ name, onYes, onNo }: { name: string; onYes: () => void; onNo: () => void }) {
  return <div className="cc-list-bulk" role="alert"><span>Delete {name} and its versions? Agents that run it will fail.</span>
    <Button kind="danger" onClick={onYes}>Delete</Button><Button kind="plain" onClick={onNo}>Cancel</Button></div>;
}

const HELP: HelpTopic = { title: "Prompt Skills",
  about: "Reviewed prompt templates that agents run with the Prompt Skills toolkit, so everyone uses the same tested prompt. A skill is private until "
    + "shared, and every save is a new version.",
  items: [
    { name: "Create skill", text: "Opens a new skill's page where you write its prompt and choose its answer. Needs permission to manage prompt skills." },
    { name: "From a template", text: "Lists ready-made skills; pick one with Use, give your copy a name and change it as you like." },
    { name: "Show", text: "Every skill, only yours, only those shared with everyone, or only templates." },
    { name: "Shared", text: "A shared skill may be used by every agent; a private one only by its owner's agents." },
    { name: "Open", text: "Opens the skill's page with its prompt and its versions. Clicking the row does the same." },
    { name: "Copy the name", text: "Copies the skill's name so you can paste it into the Prompt Skills step in One AgentStudio." },
    { name: "Delete…", text: "Deletes the skill and all its versions after you confirm. Agents that run it will then fail. Shown to its owner and to those "
      + "who manage every skill." },
  ] };

const HELP_SKILL: HelpTopic = { title: "Prompt Skill",
  about: "One skill: what the model is told and asked, what it answers, and every saved version. Saving makes a new version, so earlier ones stay in "
    + "Versions.",
  items: [
    { name: "Name", text: "Letters, digits, _ . and -, up to 80 long. Agents call the skill by this name, so it cannot change once the skill is made." },
    { name: "Model connection", text: "The model connection the skill uses. Leave it empty to use the default of the run." },
    { name: "Instructions for the model (system)", text: "Standing guidance for the model, such as its role, tone or rules, sent before the prompt." },
    { name: "Prompt", text: "What the model is asked. Write ${name} where a value the agent passes in should go; those names are added to the inputs by "
      + "themselves." },
    { name: "Answer and Temperature", text: "Whether the model answers in plain text or JSON, and how varied its answers are, from 0 (steady) to 2 "
      + "(varied)." },
    { name: "Inputs", text: "The values the agent passes in, one per line, written as name - description." },
    { name: "Shared and Template", text: "Shared lets every agent use the skill. Template offers it as a starting point in From a template; only those who "
      + "manage every skill see this box." },
    { name: "Save as a new version", text: "Saves your changes as the next version; add a few words under What changed so others know why." },
    { name: "Versions", text: "Every saved version with who saved it, when, and what changed." },
  ] };

export function Skills({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<PromptSkill[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [who, setWho] = useState("");
  const [page, setPage] = useState<null | { name: string | null }>(null);   // a skill's own page (name null: a new one), in place of the list
  const [templates, setTemplates] = useState(false);
  const [ask, setAsk] = useState<PromptSkill | null>(null);   // a skill to delete, asked first
  const can = (p: string) => user.permissions.includes(p);
  const load = useCallback(async () => { try { setList(await api.skills()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  const shown = useMemo(() => (list ?? []).filter((s) => (!q.trim() || (s.name + " " + (s.description ?? "")).toLowerCase().includes(q.trim().toLowerCase()))
    && (!who || (who === "mine" ? s.owner === user.username : who === "shared" ? s.shared : !!s.template))), [list, q, who, user.username]);
  const canChange = (s: PromptSkill) => can("skills.manage") && (s.owner === user.username || can("skills.all"));
  const copy = (s: PromptSkill) => (navigator.clipboard?.writeText(s.name) ?? Promise.reject(new Error("no clipboard")))
    .then(() => onNotice("Copied " + s.name + "."), () => setError("Copying is not allowed here: choose " + s.name + " on the Prompt Skills step."));
  const del = (s: PromptSkill) => api.deleteSkill(s.name).then(() => { onNotice("Deleted " + s.name + "."); setAsk(null); load(); }, (e) => setError(message(e)));
  const rowMenu = (s: PromptSkill) => [{ key: "open", label: "Open", onSelect: () => setPage({ name: s.name }) },
    { key: "copy", label: "Copy the name", onSelect: () => { copy(s); } },
    ...(canChange(s) ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setAsk(s) }] : [])];

  if (page) return (
    <section className="st-page cc-home cc-explorer" aria-label="Prompt Skill">
      <SkillPage key={page.name ?? "new"} api={api} name={page.name} user={user} onClose={() => { setPage(null); load(); }}
        onSaved={(t, name) => { onNotice(t); load(); setPage({ name }); }} onDeleted={(t) => { onNotice(t); setPage(null); load(); }} />
    </section>
  );
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-sk-title">
      <header className="cc-list-head">
        <h1 id="cc-sk-title">Prompt Skills</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} skill{list.length === 1 ? "" : "s"}</span>}
        <span className="ov-fill" />
        {can("skills.manage") && <>
          <Button kind="secondary" onClick={() => setTemplates(true)}>From a template</Button>
          <Button kind="primary" onClick={() => setPage({ name: null })}>Create skill</Button></>}
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
      <div className="cc-list-filters" role="group" aria-label="Filters">
        <span className="cc-list-search"><TextInput type="search" aria-label="Search skills" placeholder="Search skills" value={q} onChange={(e) => setQ(e.target.value)} /></span>
        <span className="cc-chip"><Select aria-label="Show" value={who} onChange={(e) => setWho(e.target.value)} options={[{ value: "", label: "Every skill" }, { value: "mine", label: "Mine" }, { value: "shared", label: "Shared" }, { value: "template", label: "Templates" }]} /></span>
      </div>
      <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {ask && <AskDelete name={ask.name} onYes={() => del(ask)} onNo={() => setAsk(null)} />}
        <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Skills list">
          <thead><tr><th scope="col">Skill</th><th scope="col">Shared</th><th scope="col">Version</th><th scope="col">Last modified</th>
            <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
          <tbody>
            {shown.map((s) => (
              <tr key={s.name} onClick={rowClick(() => setPage({ name: s.name }))}>
                <td className="cc-clip" title={s.description || undefined}>{s.name}{s.template && <span className="cc-tag">template</span>}</td>
                <td>{s.shared ? "Shared" : "Private to " + s.owner}</td><td>v{s.version}</td><td>{when(s.updated)}{s.updated_by ? " by " + s.updated_by : ""}</td>
                <td className="cc-list-menu"><RowMenu label={"Actions for " + s.name} items={rowMenu(s)} /></td>
              </tr>
            ))}
            {list && !shown.length && <tr className="is-static"><td colSpan={5} className="cc-none">{list.length ? "No skills match." : "No Prompt Skills yet. Start from a template."}</td></tr>}
            {!list && !error && <tr className="is-static"><td colSpan={5} className="cc-none" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}</span></div>}</div>
      {templates && <Templates api={api} onClose={() => setTemplates(false)} onMade={(n) => { onNotice("Made " + n + " from a template."); setTemplates(false); load(); setPage({ name: n }); }} />}
    </section>
  );
}

/** A skill's own page: its prompt (instructions, prompt, answer, temperature, connection, inputs, sharing) and its versions. */
function SkillPage({ api, name, user, onClose, onSaved, onDeleted }: { api: CrApi; name: string | null; user: CrUser; onClose: () => void;
  onSaved: (t: string, name: string) => void; onDeleted: (t: string) => void }) {
  type Full = Awaited<ReturnType<CrApi["skill"]>>;
  const [s, setS] = useState<Full | null>(null);
  const [tab, setTab] = useState<"prompt" | "versions">("prompt");
  const [v, setV] = useState({ name: "", description: "", connection: "", system: "", prompt: "", output: "text", temperature: "0.2", inputs: "", shared: false, template: false, comment: "",
    kIndex: "", kTop: "4", kQuery: "" });
  const [error, setError] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  useEffect(() => {
    if (!name) return;
    api.skill(name).then((x) => { setS(x); const d = x.definition;
      setV({ name: x.name, description: x.description ?? "", connection: d.connection ?? "", system: d.system ?? "", prompt: d.prompt ?? "", output: d.output ?? "text",
        temperature: String(d.temperature ?? 0.2), inputs: (d.inputs ?? []).map((i) => i.name + (i.description ? " - " + i.description : "")).join("\n"), shared: x.shared, template: !!x.template, comment: "",
        kIndex: d.knowledge?.index ?? "", kTop: String(d.knowledge?.top_k ?? 4), kQuery: d.knowledge?.query ?? "" }); },
    (e) => setError(message(e)));
  }, [api, name]);
  const close = <CloseView onClick={onClose} to="the skills" />;
  if (name && !s) return <div className="cc-agent" role="dialog" aria-modal="false" aria-label="Prompt Skill"><div className="cc-agent-head"><h1>{name}</h1><span className="ov-fill" />{close}</div>
    {error ? <p className="st-alert" role="alert">{error}</p> : <p className="cc-none cc-agent-pad" role="status">Loading…</p>}</div>;
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
    if (v.kIndex.trim()) def.knowledge = { index: v.kIndex.trim(), top_k: Math.min(20, Math.max(1, Number(v.kTop || 4))), ...(v.kQuery.trim() ? { query: v.kQuery.trim() } : {}) };
    api.saveSkill(v.name.trim(), { description: v.description, definition: def, shared: v.shared, template: v.template, comment: v.comment || null })
      .then((r) => onSaved("Saved " + r.name + " v" + r.version + ".", r.name), (e) => setError(message(e)));
  };
  const remove = () => { setAsking(false); api.deleteSkill(s!.name).then(() => onDeleted("Deleted " + s!.name + "."), (e) => setError(message(e))); };
  const uses = found(v.prompt);
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={s ? s.name + " (v" + s.version + ")" : "Create Prompt Skill"}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Prompt Skills</button> ›</span>
        <h1>{s ? s.name : "Create skill"}</h1>
        <HelpButton topic={HELP_SKILL} />
        {s && <><span className="cc-none">v{s.version}</span>
          <span className={"cc-pill is-" + (s.shared ? "active" : "disabled")}><span className="cc-pill-dot" />{s.shared ? "Shared" : "Private"}</span>
          <span className="cc-none">{s.owner === user.username ? "Yours" : "Owned by " + s.owner}{!editable ? " · read only" : ""}</span></>}
        <span className="ov-fill" />
        {editable && <Button kind="primary" disabled={!/^[A-Za-z0-9_.-]{1,80}$/.test(v.name.trim()) || !v.prompt.trim()} onClick={save}>{s ? "Save as a new version" : "Create skill"}</Button>}
        {s && editable && <RowMenu label={"More for " + s.name} items={[{ key: "delete", label: "Delete…", onSelect: () => setAsking(true) }]} />}
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {asking && s && <AskDelete name={s.name} onYes={remove} onNo={() => setAsking(false)} />}
      {s && <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this skill">
        {([["prompt", "Prompt"], ["versions", "Versions (" + (s.versions?.length ?? 1) + ")"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>}
      {tab === "prompt" && <div className="cc-agent-tab">
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
        <div className="cc-grid2">
          <Field label="Ground answers in documents: knowledge index (e.g. knowledge/policies.json)">{(id) => <TextInput id={id} value={v.kIndex} disabled={!editable} placeholder="Empty: no documents" onChange={set("kIndex")} />}</Field>
          <Field label="Passages given to the model">{(id) => <TextInput id={id} type="number" min={1} max={20} value={v.kTop} disabled={!editable || !v.kIndex.trim()} onChange={set("kTop")} />}</Field>
        </div>
        {v.kIndex.trim() && <Field label="Search the index for (empty: the prompt; ${name} allowed)">{(id) => <TextInput id={id} value={v.kQuery} disabled={!editable} onChange={set("kQuery")} />}</Field>}
        <label className="st-check-line"><input type="checkbox" checked={v.shared} disabled={!editable} onChange={(e) => setV({ ...v, shared: e.target.checked })} /> Shared: every agent may use it</label>
        {can("skills.all") && <label className="st-check-line"><input type="checkbox" checked={v.template} onChange={(e) => setV({ ...v, template: e.target.checked })} /> Template: offered as a starting point</label>}
        {editable && <Field label="What changed (optional)">{(id) => <TextInput id={id} value={v.comment} onChange={set("comment")} />}</Field>}
      </div>}
      {tab === "versions" && s && <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Versions">
          <thead><tr><th scope="col">Version</th><th scope="col">Saved by</th><th scope="col">When</th><th scope="col">What changed</th></tr></thead>
          <tbody>{(s.versions?.length ? s.versions : [{ version: s.version, updated_by: s.updated_by, updated: s.updated, comment: null }]).map((x) =>
            <tr key={x.version} className="is-static"><td>v{x.version}</td><td>{x.updated_by ?? ""}</td><td>{when(x.updated)}</td><td className="cc-clip" title={x.comment ?? undefined}>{x.comment ?? <span className="cc-none">–</span>}</td></tr>)}</tbody>
        </table>
      </div>}
    </div>
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
