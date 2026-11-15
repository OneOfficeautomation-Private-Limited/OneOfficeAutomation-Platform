import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Runner, Trigger } from "../api/cr";
import { Button, Dialog, Field, HelpButton, MicrosoftSignInDialog, Select, TextInput, type HelpTopic, reportMinor, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, when } from "./ListParts";
import { ADVANCED_FIELDS, DOCUMENT_KINDS, KINDS, TRIGGER_GROUPS as GROUPS, type F } from "../shell/triggerKinds";
import { PageIntro } from "../shell/PageIntro";
import { HelpLinks } from "../shell/HelpLinks";
import { EMPTY_CONDITIONS, RuleBuilder, RuleTry } from "./MailAccounts";
import type { MailAccount, MailConditions } from "../api/cr";

/**
 * Manage > Triggers, built as AA's event triggers (checked against AA's docs, UiPath's queue and Integration Service
 * triggers and Blue Prism, 31 Oct 2026): each trigger with its event, where it listens (a runner, or One CommandCenter,
 * UiPath-style, so no runner waits), its health (listening, paused, error) with the last firing, and Check now for the
 * One CommandCenter ones. Create in sections: event, its settings, agent, runner and run-as. Refreshes every 30 seconds.
 * Laid out as the design plan's list pattern (applied 31 Oct 2026): a count with errors, chips, a ⋯ menu per row.
 * Review round 2 (31 Oct 2026): one line per trigger, the last problem and the last firing in columns of their own
 * (Check now is in the row's menu), and the agent with its icon.
 */
const kindName = (k: string) => KINDS[k]?.label ?? k;
const agentName = (p: string) => p.split("/").pop()!.replace(/\.flow\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const health = (t: Trigger) => t.status === "error" || t.last_error ? "error" : t.enabled ? "listening" : "paused";
const HEALTH: Record<string, string> = { listening: "Listening", paused: "Paused", error: "Error" };
function details(t: Trigger) {
  const c = (t.config ?? {}) as Record<string, string | number | boolean>;
  if (t.kind === "webhook") return "POST " + location.origin + "/api/hooks/" + t.id;
  if (t.folder) return t.folder + " / " + (t.pattern || "*") + (c.recursive ? " (with sub-folders)" : "");
  if (c.title) return "Title: " + c.title;
  if (t.kind === "key_pressed") return String(c.key) + (c.window || c.process ? " in " + [c.window, c.process].filter(Boolean).join(" / ") : " in any window");
  if (t.kind === "mouse_clicked") return c.button + " click in " + [c.window, c.process].filter(Boolean).join(" / ")
    + (c.element_name || c.element_type || c.automation_id ? " on " + [c.element_type, c.element_name, c.automation_id].filter(Boolean).join(" ") : "");
  if (c.process || c.service || c.hotkey) return String(c.process ?? c.service ?? c.hotkey);
  if (t.kind === "email") return c.username + " @ " + c.host + " / " + c.mail_folder + " every " + c.interval_seconds + " s";
  if (t.kind === "mail") return String(c.mail_account_name ?? c.mail_account) + (c.folder ? " / " + c.folder : "") + ": its rule (Mail rules) decides which emails";
  if (t.kind === "outlook") return (c.mailbox || c.ms_account || "the token's mailbox") + " / " + c.mail_folder + " every " + c.interval_seconds + " s";
  if (t.kind === "servicenow") return c.instance_url + " " + c.table + (c.query ? " where " + c.query : "") + " every " + c.interval_seconds + " s";
  if (t.kind === "http_poll") return String(c.url ?? "") + " every " + c.interval_seconds + " s";
  if (DOCUMENT_KINDS.includes(t.kind)) return (c.process ? "Process " + c.process : "Any process") + (c.doc_type ? ", " + c.doc_type : "");
  return "";
}

const HELP: HelpTopic = { title: "Triggers",
  about: "Start an agent when something happens: files, windows, processes, services, hotkeys, key presses and clicks on a runner; new e-mail, ServiceNow records and web data "
    + "checked by One CommandCenter; a webhook call; or a document event in Document Automation. The list refreshes every 30 seconds.",
  items: [
    { name: "Create trigger", text: "Pick the event and fill in its settings, then the agent (its live version runs) and the runner it runs on. Runner "
      + "events are watched on that runner. Needs permission to manage triggers." },
    { name: "Key presses and clicks", text: "Windows runners only. A key-press trigger listens for one named key or combination (such as F9 or "
      + "CTRL+ENTER), optionally only in one window; typed text is never recorded. A click trigger listens in one window, or on one element captured "
      + "in OneAgentStudio. Repeats within the debounce time are ignored." },
    { name: "Vault references", text: "Passwords and tokens are never typed into a trigger: give a vault reference such as mail/imap/password. The "
      + "trigger's owner must be a consumer of that vault folder." },
    { name: "Webhook", text: "After creating a webhook trigger you see its address and secret once. Call the address with POST and send the secret in the "
      + "X-OOA-Secret header." },
    { name: "Listens on and Health", text: "Show only triggers watched on a runner or by One CommandCenter, and only those listening, paused or with an "
      + "error." },
    { name: "Last problem", text: "The latest error the trigger met, for example a mailbox it could not open. Hover to read it in full." },
    { name: "Open", text: "Shows the trigger's event and settings; you can change its name, owner and run-as person, pause it or delete it. "
      + "To change the event or its settings, create a new trigger and delete this one." },
    { name: "On Document Automation", text: "Document processed, needs review, review approved or rejected, delivery failed, review overdue: Document "
      + "Automation tells One CommandCenter at once. Leave Process empty for any process. The trigger's owner must be able to see the process's folder." },
    { name: "Check now", text: "For triggers One CommandCenter checks: looks for new mail, records or items straight away instead of waiting for the next "
      + "check." },
    { name: "Pause and Resume", text: "A paused trigger keeps its settings but does not start the agent until you resume it." },
  ] };

export function Triggers({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Trigger[] | null>(null);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  // OneAgentStudio's command list opens #/triggers?new=<event>&bot=<agent>: Create trigger, for that agent and event
  // a click trigger from OneAgentStudio also brings the captured element's settings (window, process, element_name, ...)
  const [dialog, setDialog] = useState<null | { kind: "new"; event?: string; bot?: string; preset?: Record<string, string> } | { kind: "edit"; t: Trigger } | { kind: "hook"; url: string; secret: string }>(() => {
    const q = new URLSearchParams(location.hash.split("?")[1] ?? "");
    const ev = q.get("new");
    if (!ev || !KINDS[ev]) return null;
    const preset: Record<string, string> = {};
    for (const fd of KINDS[ev].fields) { const v = q.get(fd[0]); if (v !== null && fd[4] !== "check") preset[fd[0]] = v; }
    return { kind: "new", event: ev, bot: q.get("bot") ?? undefined, preset };
  });
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

  const filtered = Object.values(f).some(Boolean);
  const errors = (list ?? []).filter((t) => health(t) === "error").length;
  const listening = (list ?? []).filter((t) => health(t) === "listening").length;
  const checkNow = (t: Trigger) => api.pollTrigger(t.id).then((x) => { onNotice(x.error ? x.error : "Checked " + t.name + ": " + (x.fired ?? 0) + " new."); load(); }, (e) => setError(message(e)));
  const rowMenu = (t: Trigger) => [
    { key: "open", label: "Open", onSelect: () => setDialog({ kind: "edit", t }) },
    ...(manage && t.evaluated_on === "server" && t.enabled && !DOCUMENT_KINDS.includes(t.kind) ? [{ key: "check", label: "Check now", onSelect: () => { checkNow(t); } }] : []),
    ...(manage ? [{ key: "pause", label: t.enabled ? "Pause" : "Resume", onSelect: () => { act(() => api.pauseTrigger(t.id, t.enabled), (t.enabled ? "Paused " : "Resumed ") + t.name + "."); } }] : []),
  ];

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-trig-title">
      <header className="cc-list-head">
        <h1 id="cc-trig-title">Triggers</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:triggers" />
        {list && <span className="cc-list-count">{list.length} trigger{list.length === 1 ? "" : "s"} · {listening} listening{errors ? " · " + errors + " with an error" : ""}</span>}
        <span className="ov-fill" />
        {manage && <Button kind="primary" onClick={() => setDialog({ kind: "new" })}>Create trigger</Button>}
      </header>
      {error && <PageError text={error} />}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search triggers" placeholder="Search triggers, agents, events" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} /></span>
          <span className={"cc-chip" + (f.where ? " is-on" : "")}><Select aria-label="Listens on" value={f.where ?? ""} onChange={(e) => setF({ ...f, where: e.target.value })}
            options={[{ value: "", label: "Listens on: Anywhere" }, { value: "runner", label: "Listens on: A runner" }, { value: "server", label: "Listens on: One CommandCenter" }]} /></span>
          <span className={"cc-chip" + (f.health ? " is-on" : "")}><Select aria-label="Health" value={f.health ?? ""} onChange={(e) => setF({ ...f, health: e.target.value })}
            options={[{ value: "", label: "Health: Any" }, ...Object.entries(HEALTH).map(([value, label]) => ({ value, label: "Health: " + label }))]} /></span>
          {filtered && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Triggers list tools">
          <RefreshButton onClick={load} />
        </div>
      </div>
      <div className="cc-list-panel">
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Triggers list">
            <thead><tr><th scope="col">Status</th><th scope="col">Trigger</th><th scope="col">Event</th><th scope="col">Listens on</th><th scope="col">Agent</th>
              <th scope="col">Details</th><th scope="col" className="cc-num">Times fired</th><th scope="col">Last fired</th><th scope="col">Last problem</th>
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((t) => (
                <tr key={t.id} onClick={rowClick(() => setDialog({ kind: "edit", t }))}>
                  <td title={t.last_error ?? undefined}><span className={"cc-pill is-" + (health(t) === "listening" ? "active" : health(t) === "error" ? "failed" : "disabled")}><span className="cc-pill-dot" />{HEALTH[health(t)]}</span></td>
                  <td><strong>{t.name}</strong>{t.run_as && <span className="cc-none"> · runs as {t.run_as}</span>}</td>
                  <td>{kindName(t.kind)}</td>
                  <td>{t.evaluated_on === "server" ? "One CommandCenter" : runnerName(t.device_id) + (t.windows_only ? " (Windows)" : "")}</td>
                  <td><span className="cc-aico" aria-hidden="true">A</span>{agentName(t.bot_path)}</td>
                  <td className="cc-clip cc-trig-details ui-mono" title={details(t)}>{details(t)}</td>
                  <td className="cc-num">{t.fire_count ?? 0}</td>
                  <td>{t.last_fired ? when(t.last_fired) : <span className="cc-none">Never</span>}</td>
                  <td>{t.last_error ? <span className="cc-err cc-clip cc-trig-problem" title={t.last_error}>{t.last_error}</span> : <span className="cc-none">–</span>}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + t.name} items={rowMenu(t)} /></td>
                </tr>
              ))}
              {list && !shown.length && <tr className="is-static"><td colSpan={10} className="cc-none">{list.length ? "No triggers match." : "No triggers yet. Press Create trigger to start an agent when a file arrives, an e-mail comes in or another system calls."}</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={10} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {list && <div className="cc-list-pager"><span>{shown.length} of {list.length}{filtered ? " matching" : ""} · refreshes every 30 seconds</span></div>}
      </div>
      {dialog?.kind === "new" && <NewTrigger api={api} runners={runners} event={dialog.event} agent={dialog.bot} preset={dialog.preset} onClose={() => setDialog(null)}
        onCreated={(t, hook) => { onNotice(t); load(); setDialog(hook ? { kind: "hook", ...hook } : null); }} />}
      {dialog?.kind === "edit" && <EditTrigger key={dialog.t.id} api={api} t={dialog.t} manage={manage} where={dialog.t.evaluated_on === "server" ? "One CommandCenter" : runnerName(dialog.t.device_id)}
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
function NewTrigger({ api, runners, event, agent, preset, onClose, onCreated }: { api: CrApi; runners: Runner[]; event?: string; agent?: string; preset?: Record<string, string>; onClose: () => void;
  onCreated: (t: string, hook?: { url: string; secret: string }) => void }) {
  const [agents, setAgents] = useState<string[]>([]);
  const [kind, setKind] = useState(event ?? "file");
  const [name, setName] = useState("");
  const [bot, setBot] = useState(agent ?? "");
  const [device, setDevice] = useState(runners[0]?.id ?? "");
  const [values, setValues] = useState<Record<string, string | boolean>>(preset ?? {});
  const [error, setError] = useState<string | null>(null);
  // DA-1: an Outlook trigger signs in with Microsoft; your own app or a token reference is advanced (or shown when the sign-in is not offered)
  const [ms, setMs] = useState<{ id: string; account: string } | null>(null);
  const [msReady, setMsReady] = useState<boolean | null>(null);
  const [signingIn, setSigningIn] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  // MA-4: New email (any mail account): an account set up once, a folder, and this trigger's rule
  const [mailAccounts, setMailAccounts] = useState<MailAccount[] | null>(null);
  const [mailAccount, setMailAccount] = useState("");
  const [mailFolder, setMailFolder] = useState("");
  const [conditions, setConditions] = useState<MailConditions>(EMPTY_CONDITIONS);
  useEffect(() => {
    if (kind !== "mail" || mailAccounts) return;
    api.mailAccounts().then((d) => { const usable = d.accounts.filter((a) => a.can_use); setMailAccounts(usable); setMailAccount((cur) => cur || usable[0]?.id || ""); }, () => setMailAccounts([]));
  }, [api, kind, mailAccounts]);
  useEffect(() => { api.liveAgents().then((b) => { setAgents(b.map((x) => x.path)); setBot((o) => o || b[0]?.path || ""); }, (e) => reportMinor("the list of agents", e)); }, [api]);
  useEffect(() => { if (kind === "outlook" && msReady === null) api.triggerMsSignInReady().then(setMsReady, () => setMsReady(false)); }, [api, kind, msReady]);
  const hidden = ms || (msReady !== false && !advanced) ? ADVANCED_FIELDS[kind] ?? [] : [];
  const fields = KINDS[kind].fields.filter((fd) => !hidden.includes(fd[0]));
  const val = (fd: F) => values[fd[0]] ?? (fd[4] === "check" ? !!fd[5] : fd[3] ?? "");
  const create = async () => {
    const b: Record<string, unknown> = { name: name.trim() || "Trigger", bot_path: bot, device_id: device, kind };
    if (kind === "outlook" && ms) b.ms_signin = ms.id;
    if (kind === "mail") { b.mail_account = mailAccount; b.conditions = conditions; if (mailFolder.trim()) b.folder = mailFolder.trim(); }
    for (const fd of fields) {
      const v = val(fd);
      if (fd[4] === "check") b[fd[0]] = !!v;
      else if (String(v).trim() !== "") b[fd[0]] = fd[4] === "number" ? Number(v) : String(v).trim();
    }
    try { const r = await api.createTrigger(b); onCreated("Created the trigger " + b.name + ".", r.secret && r.url ? { url: r.url, secret: r.secret } : undefined); } catch (e) { setError(message(e)); }
  };
  return (
    <Dialog open title="Create trigger" width={680} onClose={onClose} help={<HelpLinks k="dialog:trigger" label="Triggers" />} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!bot || !device || (kind === "mail" && !mailAccount)} onClick={create}>Create trigger</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <h3 className="cc-log-title">Event</h3>
      <Field label="Event">{(id) => <select id={id} className="ui-input ui-select" value={kind} onChange={(e) => { setKind(e.target.value); setValues({}); setMs(null); }}>
        {GROUPS.map(([g, ks]) => <optgroup key={g} label={g}>{ks.map((k) => <option key={k} value={k}>{kindName(k)}</option>)}</optgroup>)}</select>}</Field>
      <p className="st-muted">{KINDS[kind].help}</p>
      {kind === "outlook" && <div className="ui-ms-account" role="group" aria-label="Microsoft account">
        <b>Microsoft account</b>
        {ms ? <span className="ui-ms-account-row"><span className="cc-pill is-success">Signed in as {ms.account}</span>
            <button type="button" className="st-link" onClick={() => setMs(null)}>Sign out</button></span>
          : msReady === null ? <span className="st-muted">Checking…</span>
          : msReady ? <span className="ui-ms-account-row"><Button kind="primary" onClick={() => setSigningIn(true)}>Sign in with Microsoft</Button>
              <span className="st-muted">With the mailbox's account, or one that may read the shared mailbox. Your Microsoft 365 administrator approves One CommandCenter once.</span></span>
          : <span className="st-muted">Sign in with Microsoft is not set up on this server: an administrator adds the Microsoft app ID in Administration &gt; Settings &gt; E-mail, proxy and SIEM. Until then, give your own app or a token reference below.</span>}
        {!ms && msReady && <span><button type="button" className="st-link" aria-expanded={advanced} onClick={() => setAdvanced(!advanced)}>{advanced ? "Hide advanced settings" : "Show advanced settings"}</button>
          {!advanced && <span className="st-muted"> · your own Microsoft app, or a Graph token</span>}</span>}
      </div>}
      {kind === "mail" && <div className="mail-trigger" role="group" aria-label="Mail account and rule">
        <div className="cc-grid2">
          <Field label="Mail account">{(id) => <Select id={id} value={mailAccount} onChange={(e) => setMailAccount(e.target.value)}
            options={mailAccounts?.length ? mailAccounts.map((a) => ({ value: a.id, label: a.name + " · " + a.address })) : [{ value: "", label: mailAccounts ? "No mail account you may use yet" : "Loading…" }]} />}</Field>
          <Field label="Folder (optional)">{(id) => <TextInput id={id} value={mailFolder} placeholder="Inbox" onChange={(e) => setMailFolder(e.target.value)} />}</Field>
        </div>
        <RuleBuilder value={conditions} onChange={setConditions} />
        <RuleTry disabled={!mailAccount} tryRule={() => api.tryMailRule(mailAccount, { conditions, folder: mailFolder.trim() || undefined })} />
      </div>}
      {signingIn && <MicrosoftSignInDialog start={() => api.triggerMsSignInStart()} poll={(id) => api.triggerMsSignInPoll(id)} onClose={() => setSigningIn(false)}
        onDone={(id, account) => { setSigningIn(false); setMs({ id, account }); }} />}
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
    <Dialog open title={"Trigger " + t.name} width={640} onClose={onClose} help={<HelpLinks k="dialog:trigger" label="Triggers" />} footer={manage ? <>
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
