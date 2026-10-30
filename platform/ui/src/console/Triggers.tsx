import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Runner, Trigger } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Manage > Triggers, built as AA's event triggers (checked against AA's docs, UiPath's queue and Integration Service
 * triggers and Blue Prism, 31 Oct 2026): each trigger with its event, where it listens (a runner, or the Command Center,
 * UiPath-style, so no runner waits), its health (listening, paused, error) with the last firing, and Check now for the
 * Command Center ones. Create in sections: event, its settings, agent, runner and run-as. Refreshes every 30 seconds.
 */
type F = [key: string, label: string, placeholder?: string, value?: string, type?: "number" | "check", checked?: boolean];
const FOLDER: F[] = [["folder", "Folder on the runner", "C:\\Invoices\\Inbox"], ["pattern", "File or folder name pattern", "*", "*"], ["recursive", "Include sub-folders", "", "", "check"]];
const INTERVAL: F = ["interval_seconds", "Check every (seconds, 15 or more)", "60", "60", "number"];
const MAIL_FILTERS: F[] = [["from_contains", "From contains (optional)", "billing@"], ["subject_contains", "Subject contains (optional)", "Invoice"]];
export const KINDS: Record<string, { label: string; fields: F[]; help: string }> = {
  file: { label: "File arrives in a folder", fields: FOLDER, help: "Watched on the runner. The agent gets the path in trigger_file and all details in trigger_event." },
  file_changed: { label: "File changed", fields: FOLDER, help: "Fires when a matching file's size or time changes, once it stops changing. Input: trigger_file." },
  file_deleted: { label: "File deleted", fields: FOLDER, help: "Fires when a matching file disappears. Input: trigger_file." },
  folder_created: { label: "Folder created", fields: FOLDER, help: "Fires when a matching sub-folder appears. Input: trigger_file (the folder path)." },
  folder_deleted: { label: "Folder deleted", fields: FOLDER, help: "Fires when a matching sub-folder disappears. Input: trigger_file." },
  window_opened: { label: "Window opened", fields: [["title", "Window title pattern", "*Invoice*"]], help: "Windows runners. Text the title contains, a pattern with * and ?, or re: and a regular expression. Input: trigger_window." },
  window_closed: { label: "Window closed", fields: [["title", "Window title pattern", "*Invoice*"]], help: "Windows runners. Fires when a matching window goes away. Input: trigger_window." },
  process_started: { label: "Process started", fields: [["process", "Process name", "excel.exe"]], help: "Fires when a new process with this name starts on the runner. Input: trigger_process." },
  process_stopped: { label: "Process stopped", fields: [["process", "Process name", "excel.exe"]], help: "Fires when a process with this name ends. Input: trigger_process." },
  service_started: { label: "Service started", fields: [["service", "Windows service name", "Spooler"]], help: "Windows runners. Use the service name (sc query), not the display name. Input: trigger_service." },
  service_stopped: { label: "Service stopped", fields: [["service", "Windows service name", "Spooler"]], help: "Windows runners. Input: trigger_service." },
  hotkey: { label: "Hotkey pressed", fields: [["hotkey", "Key combination", "CTRL+ALT+F9"]], help: "Windows runners with a signed-in desktop. The combination must not be used by another program. Input: trigger_hotkey." },
  email: { label: "New email (IMAP mailbox)", fields: [["host", "IMAP server", "imap.example.com"], ["port", "Port", "993", "993", "number"], ["ssl", "Use SSL/TLS", "", "", "check", true],
    ["username", "User name", "invoices@example.com"], ["password_ref", "Password (vault reference)", "mail/imap/password"], ["mail_folder", "Mailbox folder", "INBOX", "INBOX"], ...MAIL_FILTERS, INTERVAL],
    help: "Checked by the Command Center. Each new message starts the agent once, with from, to, subject, date and a text preview in trigger_email." },
  outlook: { label: "New email (Microsoft 365 Outlook)", fields: [["token_ref", "Graph access token (vault reference)", "m365/graph/token"], ["mailbox", "Mailbox (blank for the token's own)", "invoices@contoso.com"],
    ["tenant_id", "Or app sign-in: tenant id"], ["client_id", "Application (client) id"], ["client_secret_ref", "Client secret (vault reference)", "m365/app/secret"],
    ["mail_folder", "Mail folder", "inbox", "inbox"], ...MAIL_FILTERS, INTERVAL],
    help: "Checked by the Command Center through Microsoft Graph. Give a token reference, or tenant id, client id, client secret reference and mailbox. Input: trigger_email." },
  servicenow: { label: "ServiceNow record new or updated", fields: [["instance_url", "Instance URL", "https://yourcompany.service-now.com"], ["table", "Table", "incident", "incident"],
    ["query", "Encoded query (optional)", "active=true^priority=1"], ["username", "User name", "integration.user"], ["password_ref", "Password (vault reference)", "snow/api/password"],
    ["token_ref", "Or OAuth token (vault reference)"], INTERVAL],
    help: "Checked by the Command Center through the Table API. Each new or changed record starts the agent once, with its fields in trigger_record." },
  http_poll: { label: "Universal poller (call a web address)", fields: [["url", "URL to call (GET)", "https://api.example.com/orders?status=new"], ["items_path", "Items JSON path", "$.items[*]", "$"],
    ["id_field", "Id field of each item", "id", "id"], ["auth_ref", "Bearer token (vault reference, optional)"], INTERVAL],
    help: "Checked by the Command Center. Each item with an id not seen before starts the agent once, with the item in trigger_item. Items present at the first check are only remembered." },
  webhook: { label: "Webhook (HTTP call)", fields: [], help: "The agent receives the request body in an input named payload. You get the address and secret after creating it." },
};
const GROUPS: [string, string[]][] = [["On the runner", ["file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "window_opened", "window_closed", "process_started", "process_stopped", "service_started", "service_stopped", "hotkey"]],
  ["On the Command Center", ["email", "outlook", "servicenow", "http_poll", "webhook"]]];
const kindName = (k: string) => KINDS[k]?.label ?? k;
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const health = (t: Trigger) => t.status === "error" || t.last_error ? "error" : t.enabled ? "listening" : "paused";
const HEALTH: Record<string, string> = { listening: "Listening", paused: "Paused", error: "Error" };
function details(t: Trigger) {
  const c = (t.config ?? {}) as Record<string, string | number | boolean>;
  if (t.kind === "webhook") return "POST " + location.origin + "/api/hooks/" + t.id;
  if (t.folder) return t.folder + " / " + (t.pattern || "*") + (c.recursive ? " (with sub-folders)" : "");
  if (c.title) return "Title: " + c.title;
  if (c.process || c.service || c.hotkey) return String(c.process ?? c.service ?? c.hotkey);
  if (t.kind === "email") return c.username + " @ " + c.host + " / " + c.mail_folder + " every " + c.interval_seconds + " s";
  if (t.kind === "outlook") return (c.mailbox || "the token's mailbox") + " / " + c.mail_folder + " every " + c.interval_seconds + " s";
  if (t.kind === "servicenow") return c.instance_url + " " + c.table + (c.query ? " where " + c.query : "") + " every " + c.interval_seconds + " s";
  if (t.kind === "http_poll") return String(c.url ?? "") + " every " + c.interval_seconds + " s";
  return "";
}

export function Triggers({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Trigger[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [dialog, setDialog] = useState<null | { kind: "new" } | { kind: "edit"; t: Trigger } | { kind: "hook"; url: string; secret: string }>(null);
  const manage = user.permissions.includes("triggers.manage");

  const load = useCallback(async () => {
    try { const [t, d] = await Promise.all([api.triggers(), api.devices().catch(() => [])]); setList(t); setRunners(d); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!dialog) load(); }, 30_000); return () => clearInterval(t); }, [load, dialog]);

  const runnerName = (id?: string | null) => id ? runners.find((r) => r.id === id)?.name ?? id : "";
  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    return (list ?? []).filter((t) => (!text || (t.name + " " + t.bot_path + " " + kindName(t.kind) + " " + details(t)).toLowerCase().includes(text))
      && (!f.where || t.evaluated_on === f.where) && (!f.health || health(t) === f.health));
  }, [list, f]);
  const act = (fn: () => Promise<unknown>, done: string) => fn().then(() => { onNotice(done); load(); }, (e) => setError(message(e)));

  return (
    <section className="st-page cc-home" aria-labelledby="cc-trig-title">
      <header className="st-page-head">
        <div><h1 id="cc-trig-title">Triggers</h1><p>Start agents on events: files, windows, processes, services and hotkeys on a runner; new e-mail, ServiceNow records and polled web data checked by the Command Center; or a webhook call.</p></div>
        {manage && <Button kind="primary" onClick={() => setDialog({ kind: "new" })}>Create trigger</Button>}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search triggers" placeholder="Search triggers, agents, events" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} />
        <Select aria-label="Listens on" value={f.where ?? ""} onChange={(e) => setF({ ...f, where: e.target.value })} options={[{ value: "", label: "Anywhere" }, { value: "runner", label: "A runner" }, { value: "server", label: "The Command Center" }]} />
        <Select aria-label="Health" value={f.health ?? ""} onChange={(e) => setF({ ...f, health: e.target.value })} options={[{ value: "", label: "Any health" }, ...Object.entries(HEALTH).map(([value, label]) => ({ value, label }))]} />
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        <span className="st-bar-fill" /><Button kind="plain" onClick={load}>Refresh</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Triggers list">
          <thead><tr><th scope="col">Health</th><th scope="col">Trigger</th><th scope="col">Event</th><th scope="col">Listens on</th><th scope="col">Agent</th>
            <th scope="col">Details</th><th scope="col">Fired</th>{manage && <th scope="col"><span className="st-sr">Actions</span></th>}</tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} onClick={(e) => { if (!(e.target as HTMLElement).closest("button")) setDialog({ kind: "edit", t }); }}>
                <td className={"cc-status is-" + (health(t) === "listening" ? "success" : health(t) === "error" ? "failed" : "disabled")} title={t.last_error ?? undefined}>{HEALTH[health(t)]}</td>
                <td>{t.name}{t.run_as && <div className="st-muted">runs as {t.run_as}</div>}</td>
                <td>{kindName(t.kind)}</td>
                <td>{t.evaluated_on === "server" ? "The Command Center" : runnerName(t.device_id) + (t.windows_only ? " (Windows)" : "")}</td>
                <td>{agentName(t.bot_path)}</td>
                <td className="cc-clip ui-mono">{details(t)}</td>
                <td>{t.fire_count ?? 0}{t.last_fired ? ", last " + when(t.last_fired) : ", never"}{t.last_error && <div className="cc-err cc-clip">{t.last_error}</div>}</td>
                {manage && <td><span className="cc-inline">
                  {t.evaluated_on === "server" && t.enabled && <Button kind="plain" onClick={() => api.pollTrigger(t.id).then((x) => { onNotice(x.error ? x.error : "Checked " + t.name + ": " + (x.fired ?? 0) + " new."); load(); }, (e) => setError(message(e)))}>Check now</Button>}
                  <Button kind="plain" onClick={() => act(() => api.pauseTrigger(t.id, t.enabled), (t.enabled ? "Paused " : "Resumed ") + t.name + ".")}>{t.enabled ? "Pause" : "Resume"}</Button>
                </span></td>}
              </tr>
            ))}
            {list && !shown.length && <tr><td colSpan={8} className="st-muted">{list.length ? "No triggers match." : "No triggers yet."}</td></tr>}
            {!list && !error && <tr><td colSpan={8} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      {dialog?.kind === "new" && <NewTrigger api={api} runners={runners} onClose={() => setDialog(null)}
        onCreated={(t, hook) => { onNotice(t); load(); setDialog(hook ? { kind: "hook", ...hook } : null); }} />}
      {dialog?.kind === "edit" && <EditTrigger key={dialog.t.id} api={api} t={dialog.t} manage={manage} where={dialog.t.evaluated_on === "server" ? "The Command Center" : runnerName(dialog.t.device_id)}
        onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "hook" && (
        <Dialog open title="Webhook created" width={560} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <p>Call this address with POST. Send the secret in the X-OOA-Secret header. The secret is shown only now.</p>
          <code className="cc-key" aria-label="Webhook address">{location.origin + dialog.url}</code>
          <code className="cc-key" aria-label="Webhook secret">{dialog.secret}</code>
        </Dialog>
      )}
    </section>
  );
}

/** Create trigger in sections: the event and its settings, then the agent, the runner and run-as. */
function NewTrigger({ api, runners, onClose, onCreated }: { api: CrApi; runners: Runner[]; onClose: () => void; onCreated: (t: string, hook?: { url: string; secret: string }) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [kind, setKind] = useState("file");
  const [name, setName] = useState("");
  const [bot, setBot] = useState("");
  const [device, setDevice] = useState(runners[0]?.id ?? "");
  const [values, setValues] = useState<Record<string, string | boolean>>({});
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); setBot((o) => o || b[0]?.path || ""); }, () => {}); }, [api]);
  const fields = KINDS[kind].fields;
  const val = (fd: F) => values[fd[0]] ?? (fd[4] === "check" ? !!fd[5] : fd[3] ?? "");
  const create = async () => {
    const b: Record<string, unknown> = { name: name.trim() || "Trigger", bot_path: bot, device_id: device, kind };
    for (const fd of fields) {
      const v = val(fd);
      if (fd[4] === "check") b[fd[0]] = !!v;
      else if (String(v).trim() !== "") b[fd[0]] = fd[4] === "number" ? Number(v) : String(v).trim();
    }
    try { const r = await api.createTrigger(b); onCreated("Created the trigger " + b.name + ".", r.secret && r.url ? { url: r.url, secret: r.secret } : undefined); } catch (e) { setError(message(e)); }
  };
  return (
    <Dialog open title="Create trigger" width={680} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!bot || !device} onClick={create}>Create trigger</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <h3 className="cc-log-title">Event</h3>
      <Field label="Event">{(id) => <select id={id} className="ui-input ui-select" value={kind} onChange={(e) => { setKind(e.target.value); setValues({}); }}>
        {GROUPS.map(([g, ks]) => <optgroup key={g} label={g}>{ks.map((k) => <option key={k} value={k}>{kindName(k)}</option>)}</optgroup>)}</select>}</Field>
      <p className="st-muted">{KINDS[kind].help}</p>
      <div className="cc-grid2">{fields.map((fd) => fd[4] === "check"
        ? <label key={fd[0]} className="st-check-line"><input type="checkbox" checked={!!val(fd)} onChange={(e) => setValues({ ...values, [fd[0]]: e.target.checked })} /> {fd[1]}</label>
        : <Field key={fd[0]} label={fd[1]}>{(id) => <TextInput id={id} type={fd[4] === "number" ? "number" : "text"} placeholder={fd[2]} value={String(val(fd))} onChange={(e) => setValues({ ...values, [fd[0]]: e.target.value })} />}</Field>)}</div>
      <h3 className="cc-log-title">What runs</h3>
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="New invoices" onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Agent (its live version)">{(id) => <Select id={id} value={bot} onChange={(e) => setBot(e.target.value)} options={agents.length ? agents.map((a) => ({ value: a, label: agentName(a) })) : [{ value: "", label: "No agent is live yet" }]} />}</Field>
        <Field label="Runner (the agent runs here; runner events are watched here)">{(id) => <Select id={id} value={device} onChange={(e) => setDevice(e.target.value)}
          options={runners.length ? runners.map((r) => ({ value: r.id, label: r.name + " (" + r.status + ")" })) : [{ value: "", label: "No runners" }]} />}</Field>
      </div>
      <p className="st-muted">Passwords and tokens are never typed here: give vault references such as mail/imap/password. The trigger's owner must be a consumer of that vault folder.</p>
    </Dialog>
  );
}

/** A trigger: its event and settings shown, and what can change in place (name, owner, run-as), pause and delete. */
function EditTrigger({ api, t, manage, where, onClose, onSaved }: { api: CrApi; t: Trigger; manage: boolean; where: string; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: t.name, owner: t.created_by ?? "", runAs: t.run_as ?? "" });
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const save = () => {
    const b: Record<string, unknown> = { name: v.name.trim(), run_as: v.runAs.trim() || null };
    if (v.owner.trim() && v.owner.trim() !== t.created_by) b.owner = v.owner.trim();
    api.updateTrigger(t.id, b).then(() => onSaved("Saved " + v.name.trim() + "."), (e) => setError(message(e)));
  };
  const c = Object.entries(t.config ?? {}).filter(([k]) => !/secret|password|token/i.test(k) || k.endsWith("_ref"));
  return (
    <Dialog open title={"Trigger " + t.name} width={640} onClose={onClose} footer={manage ? <>
      <Button kind="danger" onClick={() => setConfirm(true)}>Delete</Button>
      <Button kind="secondary" onClick={() => api.pauseTrigger(t.id, t.enabled).then(() => onSaved((t.enabled ? "Paused " : "Resumed ") + t.name + "."), (e) => setError(message(e)))}>{t.enabled ? "Pause" : "Resume"}</Button>
      <span className="st-bar-fill" /><Button kind="primary" onClick={save}>Save changes</Button></> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirm && <div className="st-alert" role="alert"><span>Delete the trigger {t.name}?</span>
        <Button kind="danger" onClick={() => api.deleteTrigger(t.id).then(() => onSaved("Deleted " + t.name + "."), (e) => setError(message(e)))}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button></div>}
      <dl className="cc-facts">
        <dt>Event</dt><dd>{kindName(t.kind)}</dd>
        <dt>Listens on</dt><dd>{where}</dd>
        <dt>Agent</dt><dd>{t.bot_path}</dd>
        <dt>Health</dt><dd>{HEALTH[health(t)]}{t.last_error ? ": " + t.last_error : ""}</dd>
        <dt>Fired</dt><dd>{t.fire_count ?? 0} times{t.last_fired ? ", last " + when(t.last_fired) : ""}</dd>
        {t.folder && <><dt>Folder</dt><dd className="ui-mono">{t.folder} / {t.pattern || "*"}</dd></>}
        {c.map(([k, val]) => [<dt key={k + "t"}>{k}</dt>, <dd key={k + "d"} className="ui-mono">{String(val)}</dd>])}
      </dl>
      <p className="st-muted">To change the event or its settings, create a new trigger and delete this one.</p>
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} disabled={!manage} onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Owner (it fires with the owner's permissions)">{(id) => <TextInput id={id} value={v.owner} disabled={!manage} onChange={(e) => setV({ ...v, owner: e.target.value })} />}</Field>
        <Field label="Run as (empty: the owner)">{(id) => <TextInput id={id} value={v.runAs} disabled={!manage} onChange={(e) => setV({ ...v, runAs: e.target.value })} />}</Field>
      </div>
    </Dialog>
  );
}
