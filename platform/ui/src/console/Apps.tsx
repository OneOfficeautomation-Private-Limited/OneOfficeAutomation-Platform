import { useCallback, useEffect, useMemo, useState } from "react";
import type { AppComponent, AppDefinition, AppEmbed, AppInfo, CrApi, CrUser } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick, when } from "./ListParts";

/**
 * Build > Apps (gap G67, after UiPath Apps): low-code apps that business people use in the console. An app is pages of
 * components (heading, text, fields, buttons, a table of data store records, the last result); a button runs an agent
 * with the fields as inputs, adds a data store record or a work item, or opens another page. Apps act with the rights
 * of the person who published them, so their users need no right to start runs. Making apps needs apps.manage.
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/** What an app's page needs from the server: the console's API, or an embed link's (EmbedApp). */
export type AppApi = Pick<CrApi, "app" | "appAct" | "appRun" | "appRecords">;

const HELP: HelpTopic = { title: "Apps", about: "Simple apps made of pages, fields and buttons, used here in the console by people who never open Studio.",
  items: [
    { name: "Components", text: "Heading, Text, Field (text, long text, number, date, yes/no, choice), Button, Records (a table of a data store entity) and Result (what the last button gave back)." },
    { name: "Buttons", text: "Run an agent (its inputs from the fields, written as {field}; the app waits and shows the agent's outputs), add a data store record, add a work item to a queue, or open another page." },
    { name: "Rights", text: "An app acts with the rights of the person who last published it, checked every time it is used; saving checks they may run its agents, change its entities and work its queues. "
      + "Its users need only to be in its audience." },
    { name: "Audience", text: "Everyone signed in, or only the roles you tick. App makers (Make apps) see every app." },
    { name: "Audit", text: "Every button press is in the audit trail as app:<name> for the person who pressed it; runs started from an app have the source app:<id>." },
  ] };

const KIND_LABEL: Record<string, string> = { heading: "Heading", text: "Text", input: "Field", button: "Button", records: "Records", result: "Result" };
const ROLES = [["viewer", "Viewers"], ["bot_runner", "Agent runners"], ["bot_creator", "Agent creators"]];

export function Apps({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<AppInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [editing, setEditing] = useState<AppInfo | "new" | null>(null);
  const [deleting, setDeleting] = useState<AppInfo | null>(null);
  const [embeds, setEmbeds] = useState<AppInfo | null>(null);
  const maker = user.permissions.includes("apps.manage");
  const load = useCallback(async () => { try { setList(await api.apps()); setError(null); } catch (e) { setError(message(e)); } }, [api]);
  useEffect(() => { load(); }, [load]);
  if (open) return <AppView api={api} appId={open} onBack={() => setOpen(null)} />;
  const remove = (a: AppInfo) => api.deleteApp(a.id).then(() => { onNotice("Deleted the app " + a.name + "."); setDeleting(null); load(); }, (e) => { setDeleting(null); setError(message(e)); });
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-apps-title">
      <header className="cc-list-head">
        <h1 id="cc-apps-title">Apps</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length}</span>}
        <span className="ov-fill" />
        {maker && <Button kind="primary" onClick={() => setEditing("new")}>New app</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools"><div className="cc-list-filters" /><div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div></div>
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the app {deleting.name}? Its users can no longer open it.</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Apps list">
            <thead><tr>{["App", "Description", "For", "Published by", "Changed"].map((h) => <th key={h} scope="col">{h}</th>)}
              {maker && <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>}</tr></thead>
            <tbody>
              {(list ?? []).map((a) => (
                <tr key={a.id} onClick={rowClick(() => setOpen(a.id))}>
                  <td>{a.name}</td>
                  <td className="cc-clip">{a.description ?? ""}</td>
                  <td>{a.audience.length ? a.audience.map((r) => ROLES.find(([k]) => k === r)?.[1] ?? r).join(", ") : "Everyone"}</td>
                  <td>{a.published_by}</td>
                  <td>{when(a.updated)}</td>
                  {maker && <td className="cc-list-menu"><RowMenu label={"Actions for " + a.name} items={[
                    { key: "open", label: "Open", onSelect: () => setOpen(a.id) },
                    { key: "edit", label: "Edit", onSelect: () => { api.app(a.id).then((full) => setEditing(full), (e) => setError(message(e))); } },
                    { key: "embed", label: "Embed links…", onSelect: () => setEmbeds(a) },
                    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(a) }]} /></td>}
                </tr>
              ))}
              {list && !list.length && <tr className="is-static"><td colSpan={6} className="cc-none">{maker ? "No apps yet. Make one with New app." : "No apps are shared with you yet."}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={6} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {embeds && <EmbedLinks api={api} app={embeds} onClose={() => setEmbeds(null)} onNotice={onNotice} />}
      {editing && <AppDesigner api={api} app={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={(t) => { onNotice(t); setEditing(null); load(); }} />}
    </section>
  );
}

type Fields = Record<string, string | boolean>;

/** Using an app: its pages, fields and buttons; runs started by a button are followed until they finish. */
export function AppView({ api, appId, onBack, initial }: { api: AppApi; appId: string; onBack?: () => void; initial?: Record<string, string> }) {
  const [app, setApp] = useState<AppInfo | null>(null);
  const [page, setPage] = useState<string>("");
  const [fields, setFields] = useState<Fields>(initial ?? {});
  const [result, setResult] = useState<{ text: string; outputs?: Record<string, unknown>; error?: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.app(appId).then((a) => { setApp(a); setPage(a.definition?.pages[0]?.id ?? ""); }, (e) => setError(message(e))); }, [api, appId]);
  const current = app?.definition?.pages.find((p) => p.id === page);
  const follow = async (runId: string, success: string) => {
    for (let i = 0; i < 900; i++) {
      const s = await api.appRun(appId, runId);
      if (!["queued", "running", "paused", "suspended"].includes(s.status)) {
        if (s.status === "success") setResult({ text: success, outputs: s.outputs });
        else setResult({ text: "The agent ended " + s.status + (s.error ? ": " + s.error : "."), error: true });
        return;
      }
      setResult({ text: s.status === "queued" ? "Waiting for a runner…" : "Working…" });
      await new Promise((r) => setTimeout(r, 2000));
    }
  };
  const press = async (c: AppComponent) => {
    setBusy(true); setError(null);
    try {
      const r = await api.appAct(appId, c.id, fields);
      if (r.page) { setPage(r.page); setResult(null); return; }
      if (r.run_id) await follow(r.run_id, r.message ?? "Done.");
      else setResult({ text: r.message ?? "Done.", outputs: r.outputs });
    } catch (e) { setResult({ text: message(e), error: true }); }
    finally { setBusy(false); }
  };
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-app-title">
      <header className="cc-list-head">
        {onBack && <Button kind="plain" onClick={onBack}>← Apps</Button>}
        <h1 id="cc-app-title">{app?.name ?? "App"}</h1>
        {app && app.definition && app.definition.pages.length > 1 && <span className="cc-inline" role="tablist" aria-label="Pages">
          {app.definition.pages.map((p) => <Button key={p.id} kind={p.id === page ? "secondary" : "plain"} role="tab" aria-selected={p.id === page} onClick={() => setPage(p.id)}>{p.title || p.id}</Button>)}</span>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {current && <div className="cc-list-panel cc-app-page" role="form" aria-label={current.title || app?.name}>
        {current.components.map((c) => <AppPart key={c.id} api={api} appId={appId} c={c} fields={fields} setFields={setFields} result={result} busy={busy} onPress={press} />)}
      </div>}
    </section>
  );
}

function AppPart({ api, appId, c, fields, setFields, result, busy, onPress }: { api: AppApi; appId: string; c: AppComponent; fields: Fields;
  setFields: (f: Fields) => void; result: { text: string; outputs?: Record<string, unknown>; error?: boolean } | null; busy: boolean; onPress: (c: AppComponent) => void }) {
  const set = (v: string | boolean) => setFields({ ...fields, [c.name ?? ""]: v });
  switch (c.kind) {
    case "heading": return <h2 className="cc-log-title">{c.text}</h2>;
    case "text": return <p>{c.text}</p>;
    case "input": {
      const v = fields[c.name ?? ""];
      return <Field label={(c.label || c.name || "") + (c.required ? " (required)" : "")} hint={c.hint}>{(id) =>
        c.type === "boolean" ? <input id={id} type="checkbox" checked={v === true} onChange={(e) => set(e.target.checked)} />
          : c.type === "textarea" ? <TextArea id={id} rows={4} value={String(v ?? "")} onChange={(e) => set(e.target.value)} />
          : c.type === "choice" ? <Select id={id} value={String(v ?? "")} onChange={(e) => set(e.target.value)} options={[{ value: "", label: "" }, ...(c.choices ?? []).map((x) => ({ value: x, label: x }))]} />
          : <TextInput id={id} type={c.type === "number" ? "number" : c.type === "date" ? "date" : "text"} value={String(v ?? "")} onChange={(e) => set(e.target.value)} />}</Field>;
    }
    case "button": {
      const missing = c.requires?.some((n) => fields[n] === undefined || fields[n] === "");
      return <p><Button kind={c.style === "secondary" ? "secondary" : "primary"} disabled={busy || missing} onClick={() => onPress(c)}>{c.label || "Go"}</Button></p>;
    }
    case "result": return result ? <div className={result.error ? "st-alert" : "cc-list-bulk"} role="status"><span>{result.text}</span>
      {result.outputs && Object.keys(result.outputs).length > 0 && <dl className="cc-app-out">{Object.entries(result.outputs).map(([k, v]) =>
        <div key={k}><dt>{k}</dt><dd>{typeof v === "object" ? JSON.stringify(v) : String(v)}</dd></div>)}</dl>}</div> : null;
    case "records": return <AppRecords api={api} appId={appId} c={c} />;
    default: return null;
  }
}

function AppRecords({ api, appId, c }: { api: AppApi; appId: string; c: AppComponent }) {
  const [data, setData] = useState<{ records: { id: string; data: Record<string, unknown> }[]; total: number; fields: { name: string }[] } | null>(null);
  const [q, setQ] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.appRecords(appId, c.id, q).then(setData, (e) => setError(message(e))); }, [api, appId, c.id, q]);
  const cols = c.columns?.length ? c.columns : (data?.fields ?? []).map((f) => f.name);
  return (
    <div>
      {c.title && <h3 className="cc-log-title">{c.title}</h3>}
      {error && <p className="st-alert" role="alert">{error}</p>}
      <span className="cc-list-search"><TextInput type="search" aria-label={"Search " + (c.title || c.entity)} placeholder="Search" value={q} onChange={(e) => setQ(e.target.value)} /></span>
      <table className="cc-list-table" aria-label={c.title || c.entity}>
        <thead><tr>{cols.map((k) => <th key={k} scope="col">{k}</th>)}</tr></thead>
        <tbody>{(data?.records ?? []).map((r) => <tr key={r.id} className="is-static">{cols.map((k) => <td key={k} className="cc-clip">{String(r.data[k] ?? "")}</td>)}</tr>)}
          {data && !data.records.length && <tr className="is-static"><td colSpan={Math.max(1, cols.length)} className="cc-none">No records.</td></tr>}</tbody>
      </table>
    </div>
  );
}

const newId = (kind: string, taken: Set<string>) => { let i = 1; while (taken.has(kind + i)) i++; return kind + i; };

/** Making or changing an app: name, audience, pages and their components in order. */
function AppDesigner({ api, app, onClose, onSaved }: { api: CrApi; app: AppInfo | null; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: app?.name ?? "", description: app?.description ?? "", audience: app?.audience ?? [] });
  const [def, setDef] = useState<AppDefinition>(app?.definition ?? { pages: [{ id: "main", title: "Main", components: [
    { id: "heading1", kind: "heading", text: "Request" }, { id: "input1", kind: "input", name: "customer", label: "Customer", type: "text", required: true },
    { id: "button1", kind: "button", label: "Submit", action: { kind: "create_record", entity: "", values: { customer: "{customer}" } } }, { id: "result1", kind: "result" }] }] });
  const [pageIdx, setPageIdx] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const page = def.pages[pageIdx];
  const ids = useMemo(() => new Set(def.pages.flatMap((p) => p.components.map((c) => c.id))), [def]);
  const setPageComps = (comps: AppComponent[]) => setDef({ pages: def.pages.map((p, i) => i === pageIdx ? { ...p, components: comps } : p) });
  const setComp = (i: number, c: AppComponent) => setPageComps(page.components.map((x, j) => j === i ? c : x));
  const move = (i: number, d: number) => { const l = [...page.components]; const [x] = l.splice(i, 1); l.splice(i + d, 0, x); setPageComps(l); };
  const add = (kind: string) => setPageComps([...page.components, { id: newId(kind, ids), kind, ...(kind === "input" ? { name: newId("field", new Set(page.components.map((c) => c.name ?? ""))), type: "text" } : {}),
    ...(kind === "button" ? { label: "Go", action: { kind: "run_agent", agent: "", inputs: {} } } : {}) } as AppComponent]);
  const save = () => api.saveApp(app?.id ?? null, { name: v.name.trim(), description: v.description, audience: v.audience, definition: def })
    .then(() => onSaved((app ? "Published " : "Made and published the app ") + v.name.trim() + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={app ? "App " + app.name : "New app"} width={980} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim()} onClick={save}>Publish</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={120} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Description">{(id) => <TextInput id={id} value={v.description} maxLength={500} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      </div>
      <div role="group" aria-label="Who may use it" className="cc-inline">
        <strong>For</strong>
        {ROLES.map(([k, l]) => <label key={k} className="st-check-line"><input type="checkbox" checked={v.audience.includes(k)}
          onChange={(e) => setV({ ...v, audience: e.target.checked ? [...v.audience, k] : v.audience.filter((x) => x !== k) })} /> {l}</label>)}
        <span className="cc-none">{v.audience.length ? "" : "(none ticked: everyone signed in)"}</span>
      </div>
      <div className="cc-inline" role="group" aria-label="Pages">
        {def.pages.map((p, i) => <Button key={p.id} kind={i === pageIdx ? "secondary" : "plain"} onClick={() => setPageIdx(i)}>{p.title || p.id}</Button>)}
        <Button kind="plain" onClick={() => { const pid = newId("page", new Set(def.pages.map((p) => p.id))); setDef({ pages: [...def.pages, { id: pid, title: "Page " + (def.pages.length + 1), components: [] }] }); setPageIdx(def.pages.length); }}>+ Page</Button>
        {def.pages.length > 1 && <Button kind="plain" onClick={() => { setDef({ pages: def.pages.filter((_, i) => i !== pageIdx) }); setPageIdx(0); }}>Remove this page</Button>}
      </div>
      <Field label="Page title">{(id) => <TextInput id={id} value={page.title} onChange={(e) => setDef({ pages: def.pages.map((p, i) => i === pageIdx ? { ...p, title: e.target.value } : p) })} />}</Field>
      <ol className="cc-app-design" aria-label="Components">
        {page.components.map((c, i) => (
          <li key={c.id}>
            <div className="cc-inline"><strong>{KIND_LABEL[c.kind]}</strong> <code>{c.id}</code><span className="ov-fill" />
              <Button kind="plain" aria-label={"Move " + c.id + " up"} disabled={i === 0} onClick={() => move(i, -1)}>↑</Button>
              <Button kind="plain" aria-label={"Move " + c.id + " down"} disabled={i === page.components.length - 1} onClick={() => move(i, 1)}>↓</Button>
              <Button kind="plain" aria-label={"Remove " + c.id} onClick={() => setPageComps(page.components.filter((_, j) => j !== i))}>Remove</Button></div>
            <ComponentEditor c={c} pages={def.pages.map((p) => p.id)} onChange={(x) => setComp(i, x)} />
          </li>
        ))}
      </ol>
      <div className="cc-inline" role="group" aria-label="Add a component"><span>Add</span>
        {Object.entries(KIND_LABEL).map(([k, l]) => <Button key={k} kind="secondary" onClick={() => add(k)}>{l}</Button>)}</div>
    </Dialog>
  );
}

const asJson = (o: unknown) => JSON.stringify(o ?? {}, null, 1);

function JsonField({ label, value, onChange, hint }: { label: string; value: unknown; onChange: (v: Record<string, unknown>) => void; hint?: string }) {
  const [text, setText] = useState(asJson(value));
  const [bad, setBad] = useState(false);
  return <Field label={label} hint={bad ? "Not valid JSON yet" : hint}>{(id) => <TextArea id={id} rows={3} className="ui-mono" value={text}
    onChange={(e) => { setText(e.target.value); try { onChange(JSON.parse(e.target.value)); setBad(false); } catch { setBad(true); } }} />}</Field>;
}

function ComponentEditor({ c, pages, onChange }: { c: AppComponent; pages: string[]; onChange: (c: AppComponent) => void }) {
  const t = (k: keyof AppComponent, label: string, hint?: string) => <Field label={label} hint={hint}>{(id) =>
    <TextInput id={id} value={String(c[k] ?? "")} onChange={(e) => onChange({ ...c, [k]: e.target.value })} />}</Field>;
  switch (c.kind) {
    case "heading": case "text": return t("text", "Text");
    case "input": return <div className="cc-grid2">
      {t("name", "Field name", "Used as {name} in buttons")}{t("label", "Label")}
      <Field label="Type">{(id) => <Select id={id} value={c.type ?? "text"} onChange={(e) => onChange({ ...c, type: e.target.value })}
        options={[["text", "Text"], ["textarea", "Long text"], ["number", "Number"], ["date", "Date"], ["boolean", "Yes/no"], ["choice", "Choice"]].map(([value, label]) => ({ value, label }))} />}</Field>
      {c.type === "choice" ? <Field label="Choices" hint="Separated by commas">{(id) => <TextInput id={id} value={(c.choices ?? []).join(", ")}
        onChange={(e) => onChange({ ...c, choices: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) })} />}</Field> : t("hint", "Hint")}
      <label className="st-check-line"><input type="checkbox" checked={!!c.required} onChange={(e) => onChange({ ...c, required: e.target.checked })} /> Required</label>
    </div>;
    case "records": return <div className="cc-grid2">{t("entity", "Entity")}{t("title", "Title")}
      <Field label="Columns" hint="Empty: every field">{(id) => <TextInput id={id} value={(c.columns ?? []).join(", ")}
        onChange={(e) => onChange({ ...c, columns: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) })} />}</Field>{t("sort", "Sort by")}</div>;
    case "result": return <p className="cc-none">Shows the message and outputs of the last button pressed.</p>;
    case "button": {
      const a = c.action ?? { kind: "run_agent" };
      const setA = (x: Partial<NonNullable<AppComponent["action"]>>) => onChange({ ...c, action: { ...a, ...x } });
      return <div>
        <div className="cc-grid2">{t("label", "Label")}
          <Field label="Does">{(id) => <Select id={id} value={a.kind} onChange={(e) => setA({ kind: e.target.value })}
            options={[["run_agent", "Run an agent"], ["create_record", "Add a data store record"], ["add_queue_item", "Add a work item"], ["go_page", "Open a page"]].map(([value, label]) => ({ value, label }))} />}</Field>
          <Field label="Needs fields" hint="Names, separated by commas; the button waits for them">{(id) => <TextInput id={id} value={(c.requires ?? []).join(", ")}
            onChange={(e) => onChange({ ...c, requires: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) })} />}</Field>
          {a.kind !== "go_page" && <Field label="Message when done" hint="May use {field}">{(id) => <TextInput id={id} value={a.success ?? ""} onChange={(e) => setA({ success: e.target.value })} />}</Field>}
        </div>
        {a.kind === "run_agent" && <div className="cc-grid2">
          <Field label="Agent">{(id) => <TextInput id={id} value={a.agent ?? ""} placeholder="Finance/Refund.flow.json" onChange={(e) => setA({ agent: e.target.value })} />}</Field>
          <Field label="Runner pool id" hint="Or a runner id below">{(id) => <TextInput id={id} value={a.pool ?? ""} onChange={(e) => setA({ pool: e.target.value })} />}</Field>
          <Field label="Runner id">{(id) => <TextInput id={id} value={a.runner ?? ""} onChange={(e) => setA({ runner: e.target.value })} />}</Field>
          <JsonField label="Inputs" hint='Agent input to value, such as {"customer": "{customer}"}' value={a.inputs} onChange={(x) => setA({ inputs: x })} /></div>}
        {a.kind === "create_record" && <div className="cc-grid2">
          <Field label="Entity">{(id) => <TextInput id={id} value={a.entity ?? ""} onChange={(e) => setA({ entity: e.target.value })} />}</Field>
          <JsonField label="Values" hint='Field to value, such as {"name": "{customer}"}' value={a.values} onChange={(x) => setA({ values: x })} /></div>}
        {a.kind === "add_queue_item" && <div className="cc-grid2">
          <Field label="Work queue">{(id) => <TextInput id={id} value={a.queue ?? ""} onChange={(e) => setA({ queue: e.target.value })} />}</Field>
          <JsonField label="Item data" value={a.data} onChange={(x) => setA({ data: x })} /></div>}
        {a.kind === "go_page" && <Field label="Page">{(id) => <Select id={id} value={a.page ?? ""} onChange={(e) => setA({ page: e.target.value })}
          options={[{ value: "", label: "" }, ...pages.map((p) => ({ value: p, label: p }))]} />}</Field>}
      </div>;
    }
    default: return null;
  }
}

/**
 * An app's embed links (G68): each names the sites that may show the app in a frame; its key is shown once. The
 * Teams package puts the link in a Teams tab.
 */
function EmbedLinks({ api, app, onClose, onNotice }: { api: CrApi; app: AppInfo; onClose: () => void; onNotice: (t: string) => void }) {
  const [list, setList] = useState<AppEmbed[] | null>(null);
  const [v, setV] = useState({ name: "", origins: "" });
  const [made, setMade] = useState<AppEmbed & { key: string; path: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => { api.appEmbeds(app.id).then(setList, (e) => setError(message(e))); }, [api, app.id]);
  useEffect(() => { load(); }, [load]);
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  const make = () => api.makeAppEmbed(app.id, { name: v.name.trim(), origins: v.origins.split(/[\s,]+/).filter(Boolean) })
    .then((r) => { setMade(r); setV({ name: "", origins: "" }); setError(null); load(); }, (e) => setError(message(e)));
  const remove = (e: AppEmbed) => api.deleteAppEmbed(app.id, e.id).then(() => { onNotice("Removed the embed link " + e.name + "; pages using it stop working."); load(); }, (x) => setError(message(x)));
  const teams = () => made && api.teamsPackage(app.id, made.id, made.key, origin).then((blob) => {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = app.name.replace(/[^A-Za-z0-9 _-]/g, "") + " Teams app.zip"; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  }, (e) => setError(message(e)));
  return (
    <Dialog open title={"Embed links for " + app.name} width={760} onClose={onClose} footer={<Button kind="plain" onClick={onClose}>Close</Button>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="cc-none">An embed link shows this app inside another site (Salesforce, ServiceNow, Teams, an intranet) without signing in.
        Whoever has the link can use the app, with the rights of its publisher, so make one only for what that site's users may do.
        Add fields to the link's query to fill them in, such as ?customer=ACME.</p>
      {made && <div className="cc-list-bulk" role="status">
        <span>The link for {made.name}, shown this once: copy it now.</span>
        <TextInput readOnly mono aria-label="Embed link" value={origin + made.path} onFocus={(e) => e.currentTarget.select()} />
        <Button kind="secondary" onClick={teams}>Teams app package</Button>
        <Button kind="plain" onClick={() => setMade(null)}>Done</Button>
      </div>}
      <table className="cc-list-table" aria-label="Embed links">
        <thead><tr><th scope="col">Link</th><th scope="col">Sites</th><th scope="col">Made</th><th scope="col">Last used</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
        <tbody>
          {(list ?? []).map((e) => <tr key={e.id} className="is-static"><td>{e.name}</td><td className="cc-clip" title={e.origins.join(" ")}>{e.origins.join(" ")}</td>
            <td>{when(e.created)}</td><td>{e.last_used ? when(e.last_used) : "Never"}</td>
            <td><Button kind="plain" aria-label={"Remove " + e.name} onClick={() => remove(e)}>Remove</Button></td></tr>)}
          {list && !list.length && <tr className="is-static"><td colSpan={5} className="cc-none">No embed links yet.</td></tr>}
        </tbody>
      </table>
      <h3 className="cc-log-title">New embed link</h3>
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={80} placeholder="Salesforce account page" onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Sites that may show it" hint="https:// addresses, separated by spaces; *. for every subdomain">{(id) =>
          <TextInput id={id} value={v.origins} placeholder="https://acme.lightning.force.com https://*.teams.microsoft.com" onChange={(e) => setV({ ...v, origins: e.target.value })} />}</Field>
      </div>
      <p><Button kind="primary" disabled={!v.name.trim() || !v.origins.trim()} onClick={make}>Make the link</Button></p>
    </Dialog>
  );
}
