import "./consoleApi";
import { useCallback, useEffect, useState } from "react";
import type { Alert, AlertKind, CrApi, CrUser } from "../../api/cr";
import type { AlertRule } from "./consoleApi";
import { Button, Dialog, Field, HelpButton, PrincipalPicker, principalDirectory, Select, TextInput, type HelpTopic, PageError } from "../../components";
import { RefreshButton, RowMenu, rowClick, when } from "../../console/ListParts";
import { PageIntro } from "../../shell/PageIntro";

/**
 * Operational Insights > Alerts, as UiPath's real-time monitoring alerts on processes, queues and machines and AA's
 * notifications (checked 31 Oct 2026). Rules watch one condition each, checked once a minute; an alert goes to the
 * task inbox, e-mail and a webhook. Alerts on a state clear by themselves when it ends; alerts on a failed run stay
 * open until acknowledged. Seeing needs analytics.view; changing rules needs alerts.manage.
 * Review round 2 (31 Oct 2026, with UiPath's Alerts page and AA's Notifications): one line per alert, severity as pills,
 * search and filters by state, severity and rule, a row opening what the alert is about, a menu per alert and per rule,
 * and deleting a rule asked first.
 */
type Scope = "agent" | "runner" | "queue" | "connection";
const KINDS: Record<AlertKind, { label: string; scope: Scope; threshold?: [string, number]; window?: [string, number]; hint: string }> = {
  run_failed: { label: "A run fails", scope: "agent", hint: "One alert for each failed run. It stays until someone acknowledges it." },
  failure_rate: { label: "Too many runs fail", scope: "agent", threshold: ["Failed share of finished runs (%)", 50], window: ["Over the last (minutes)", 60],
    hint: "Counts once at least 3 runs have finished in the period. Clears by itself when the share drops." },
  run_long: { label: "A run takes too long", scope: "agent", window: ["Running longer than (minutes)", 60], hint: "One alert for each run still running after this time. Clears when it ends." },
  runner_offline: { label: "A runner stops reporting", scope: "runner", window: ["Silent for (minutes)", 5], hint: "Runners that are off or out of service are left out. Clears when it reports again." },
  queue_overdue: { label: "Work queue items are overdue", scope: "queue", threshold: ["Overdue items, at least", 1], hint: "Only queues with a target time count. Clears when no item is overdue." },
  ai_budget: { label: "An AI budget is nearly used", scope: "connection", threshold: ["Share of the monthly budget (%)", 80], hint: "Only connections with a monthly token budget count. Raised once a month." },
  agent_raised: { label: "An agent raises an alert", scope: "agent", hint: "Sent when an agent's Raise alert step runs, with the agent's message and severity. It stays until someone acknowledges it." },
};
const SCOPE_ANY: Record<Scope, string> = { agent: "Any agent", runner: "Any runner", queue: "Any work queue", connection: "Any model connection" };
const STATUS: Record<Alert["status"], string> = { open: "Open", acknowledged: "Acknowledged", cleared: "Cleared" };
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const sentTo = (d: Alert["delivery"]) => !d ? "" : [d.inbox?.length ? "Inbox: " + d.inbox.join(", ") : "", d.emailed?.length ? "E-mail: " + d.emailed.join(", ") : "",
  d.not_emailed?.length ? "Not e-mailed: " + d.not_emailed.join(", ") : "", d.webhook ? "Webhook" : ""].filter(Boolean).join(" · ");
type Choices = Record<Scope, { value: string; label: string }[]>;

const HELP: HelpTopic = { title: "Alerts", about: "Rules that watch runs, runners, work queues and AI budgets once a minute, "
  + "and tell people in the task inbox, by e-mail or through a webhook. Alerts about a state clear by themselves when it ends; "
  + "alerts about a failed run stay open until acknowledged.",
  items: [
    { name: "Alerts", text: "The alerts raised; open ones unless you choose another status. "
      + "Click an alert to open what it is about, such as the failed run or the runner." },
    { name: "Rules", text: "Every alert rule: what it watches, when it counts, who it tells and how many of its alerts are open. "
      + "Click the open count to see those alerts." },
    { name: "Status", text: "Show open, acknowledged or cleared alerts, or every state. The list refreshes every 30 seconds." },
    { name: "Severity, Rule", text: "Show only critical or warning alerts, or only the alerts of one rule. Clear filters goes back to the open alerts." },
    { name: "Acknowledge", text: "Select open alerts and acknowledge them to say someone is on it; your name is shown with them." },
    { name: "Sent to", text: "Whose task inbox the alert went to, which e-mail addresses got it, and whether the webhook was called." },
    { name: "New alert rule", text: "Choose what to watch for, in which agent, runner, work queue or model connection (or any), "
      + "the limit that counts and the severity. It needs the Manage alert rules permission." },
    { name: "Tell", text: "In a rule: people or roles get it in their task inbox (role:NAME or user:NAME), e-mail addresses by mail, "
      + "and a webhook address as JSON, for example for Teams or Slack." },
    { name: "Actions (⋯)", text: "On an alert: open what it is about, acknowledge it or open its rule. On a rule: "
      + "edit, turn on or off, send a test, show its alerts or delete it." },
  ] };

/**
 * Item 12 (docs/research/input-help-check.md): the alerts and their rules are two pages, Alerts and Alert rules, each
 * with its own address; "Show its alerts" on a rule opens Alerts for that rule (#/alerts?rule=<id>).
 */
export function Alerts({ api, user, onNotice, view = "alerts" }: { api: CrApi; user: CrUser; onNotice: (t: string) => void; view?: "alerts" | "rules" }) {
  const tab = view;
  const setTab = (t: "alerts" | "rules", query = "") => { location.hash = (t === "rules" ? "#/alert-rules" : "#/alerts") + query; };
  const asked = new URLSearchParams(location.hash.split("?")[1] ?? "");
  const [status, setStatus] = useState(asked.get("rule") ? "all" : "open");
  const [rule, setRule] = useState(asked.get("rule") ?? "");   // only this rule's alerts (a rule's "Show its alerts")
  const [severity, setSeverity] = useState("");
  const [q, setQ] = useState("");
  const [alerts, setAlerts] = useState<Alert[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [rules, setRules] = useState<AlertRule[] | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  // #/alerts?new=1 (Overview's New alert rule) opens the new rule straight away
  const [editing, setEditing] = useState<AlertRule | "new" | null>(() => user.permissions.includes("alerts.manage") && /[?&]new=1/.test(location.hash) ? "new" : null);
  const [deleting, setDeleting] = useState<AlertRule | null>(null);   // a rule to delete, asked first
  const [choices, setChoices] = useState<Choices>({ agent: [], runner: [], queue: [], connection: [] });
  const can = (p: string) => user.permissions.includes(p);
  const manage = can("alerts.manage");

  const load = useCallback(async () => {
    try {
      const [a, r] = await Promise.all([api.alerts({ status, limit: "200", ...(rule ? { rule } : {}) }), api.alertRules()]);
      setAlerts(a.alerts); setOpen(a.open); setRules(r); setError(null);
      setSelected((s) => s.filter((id) => a.alerts.some((x) => x.id === id && x.status === "open")));
    } catch (e) { setError(message(e)); }
  }, [api, status, rule]);
  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load]);
  useEffect(() => {   // the names behind each rule's scope, and the choices when making one
    const list = <T,>(ok: boolean, p: () => Promise<T[]>) => ok ? p().catch(() => [] as T[]) : Promise.resolve([] as T[]);
    Promise.all([list(can("repo.view"), () => api.agents()), list(can("devices.view"), () => api.runners()), list(can("queues.view"), () => api.queues()),
      list(can("connections.manage"), () => api.modelConnections())]).then(([a, d, q, c]) => setChoices({
      agent: a.map((x) => ({ value: x.path, label: name(x.path) })), runner: d.map((x) => ({ value: x.id, label: x.name })),
      queue: q.map((x) => ({ value: x.id, label: x.name })), connection: c.map((x) => ({ value: x.name, label: x.name })) }));
  }, [api]);   // eslint-disable-line react-hooks/exhaustive-deps

  const scopeLabel = (r: AlertRule) => { const k = KINDS[r.kind]; if (!r.scope) return SCOPE_ANY[k.scope]; return choices[k.scope].find((c) => c.value === r.scope)?.label ?? (k.scope === "agent" ? name(r.scope) : r.scope); };
  const condition = (r: AlertRule) => { const k = KINDS[r.kind]; return [k.threshold ? k.threshold[0].replace(/ \(.*\)$/, "").replace(", at least", "") + ": " + (r.threshold ?? k.threshold[1]) + (k.threshold[0].includes("%") ? "%" : "") : "",
    k.window ? k.window[0].replace(/ \(minutes\)$/, "") + " " + (r.window_minutes ?? k.window[1]) + " min" : ""].filter(Boolean).join(" · "); };
  const acknowledge = (ids: string[]) => api.acknowledgeAlerts(ids).then((r) => { onNotice("Acknowledged " + r.acknowledged + " alert" + (r.acknowledged === 1 ? "" : "s") + "."); setSelected([]); load(); }, (e) => setError(message(e)));
  const toggle = (r: AlertRule) => api.setAlertRuleEnabled(r.id, !r.enabled).then(() => { onNotice((r.enabled ? "Turned off " : "Turned on ") + r.name + "."); load(); }, (e) => setError(message(e)));
  const test = (r: AlertRule) => api.testAlertRule(r.id).then((d) => onNotice("Test sent. " + (sentTo(d) || "Nothing was sent.") + (d.note ? " " + d.note + "." : "")), (e) => setError(message(e)));
  const remove = (r: AlertRule) => api.deleteAlertRule(r.id).then(() => { onNotice("Deleted the alert rule " + r.name + "; its alerts are kept as cleared."); setDeleting(null); load(); }, (e) => setError(message(e)));
  /** A rule's alerts, in every state, on the Alerts tab. */
  const showAlerts = (r: AlertRule) => setTab("alerts", "?rule=" + encodeURIComponent(r.id));
  const goTo = (a: Alert) => { if (a.link?.startsWith("#")) location.hash = a.link; };
  const shown = (alerts ?? []).filter((a) => (!severity || a.severity === severity)
    && (!q.trim() || [a.message, a.rule_name, a.subject_label, a.subject].join(" ").toLowerCase().includes(q.trim().toLowerCase())));
  const openAlerts = shown.filter((a) => a.status === "open");
  const ruleOf = (a: Alert) => rules?.find((r) => r.id === a.rule_id);
  const alertMenu = (a: Alert) => [
    ...(a.link?.startsWith("#") ? [{ key: "open", label: "Open what it is about", onSelect: () => goTo(a) }] : []),
    ...(a.status === "open" ? [{ key: "ack", label: "Acknowledge", onSelect: () => { acknowledge([a.id]); } }] : []),
    ...(manage && ruleOf(a) ? [{ key: "rule", label: "Open its rule", onSelect: () => setEditing(ruleOf(a)!) }] : []),
  ];
  const ruleMenu = (r: AlertRule) => [
    ...(manage ? [{ key: "edit", label: "Edit", onSelect: () => setEditing(r) }, { key: "toggle", label: r.enabled ? "Turn off" : "Turn on", onSelect: () => { toggle(r); } },
      { key: "test", label: "Send a test", onSelect: () => { test(r); } }] : []),
    { key: "alerts", label: "Show its alerts", onSelect: () => showAlerts(r) },
    ...(manage ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(r) }] : []),
  ];
  const filtered = !!(rule || severity || q.trim() || status !== "open");

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-al-title">
      <header className="cc-list-head">
        <h1 id="cc-al-title">{tab === "rules" ? "Alert rules" : "Alerts"}</h1>
        <HelpButton topic={HELP} /><PageIntro k={tab === "rules" ? "page:alert-rules" : "page:alerts"} />
        <span className="ov-fill" />
        {manage && <Button kind="primary" onClick={() => setEditing("new")}>New alert rule</Button>}
      </header>
      <p className="cc-list-count cc-al-count">{tab === "alerts"
        ? <>{open != null && <>{open} open · </>}<a href="#/alert-rules">{rules ? rules.length + " rule" + (rules.length === 1 ? "" : "s") : "Alert rules"} ›</a></>
        : <>{rules && <>{rules.length} rule{rules.length === 1 ? "" : "s"} · </>}<a href="#/alerts">{open != null ? open + " open alert" + (open === 1 ? "" : "s") : "Alerts"} ›</a></>}</p>
      {error && <PageError text={error} />}
      {tab === "alerts" ? <>
        <div className="cc-list-tools">
          <div className="cc-list-filters" role="group" aria-label="Filters">
            <span className="cc-list-search"><TextInput type="search" aria-label="Search alerts" placeholder="Search alerts and rules" value={q} onChange={(e) => setQ(e.target.value)} /></span>
            <span className="cc-chip"><Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setAlerts(null); }}
              options={[{ value: "open", label: "Open" }, { value: "acknowledged", label: "Acknowledged" }, { value: "cleared", label: "Cleared" }, { value: "all", label: "Every state" }]} /></span>
            <span className="cc-chip"><Select aria-label="Severity" value={severity} onChange={(e) => setSeverity(e.target.value)}
              options={[{ value: "", label: "Every severity" }, { value: "critical", label: "Critical" }, { value: "warning", label: "Warning" }]} /></span>
            <span className="cc-chip"><Select aria-label="Rule" value={rule} onChange={(e) => { setRule(e.target.value); setAlerts(null); }}
              options={[{ value: "", label: "Every rule" }, ...(rules ?? []).map((r) => ({ value: r.id, label: r.name }))]} /></span>
            {filtered && <Button kind="plain" onClick={() => { setRule(""); setSeverity(""); setQ(""); setStatus("open"); }}>Clear filters</Button>}
          </div>
          <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
        </div>
        <div className="cc-list-panel">
          {selected.length > 0 && <div className="cc-list-bulk" role="toolbar" aria-label="Alert actions">
            <strong>{selected.length} selected</strong>
            <Button kind="secondary" onClick={() => acknowledge(selected)}>Acknowledge</Button>
            <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
          </div>}
          <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Alerts list">
            <thead><tr>
              <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={openAlerts.length > 0 && openAlerts.every((a) => selected.includes(a.id))}
                onChange={(e) => setSelected(e.target.checked ? openAlerts.map((a) => a.id) : [])} /></th>
              {["Severity", "Alert", "Rule", "Raised", "Status", "Sent to"].map((h) => <th key={h} scope="col">{h}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>
            </tr></thead>
            <tbody>
              {shown.map((a) => (
                <tr key={a.id} className={a.link?.startsWith("#") ? (selected.includes(a.id) ? "is-selected" : undefined) : "is-static"} onClick={rowClick(() => goTo(a))}>
                  <td className="cc-check">{a.status === "open" && <input type="checkbox" aria-label={"Select the alert " + a.message} checked={selected.includes(a.id)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, a.id] : selected.filter((x) => x !== a.id))} />}</td>
                  <td><span className={"cc-pill is-" + (a.severity === "critical" ? "failed" : "pending")}><span className="cc-pill-dot" />{a.severity === "critical" ? "Critical" : "Warning"}</span></td>
                  <td className="cc-clip cc-alert-msg" title={a.message}>{a.link ? <a href={a.link}>{a.message}</a> : a.message}</td>
                  <td>{a.rule_name}</td><td>{when(a.raised)}</td>
                  <td>{STATUS[a.status]}{a.status === "acknowledged" && a.acknowledged_by ? " by " + a.acknowledged_by : a.status === "cleared" && a.cleared ? " " + when(a.cleared) : ""}</td>
                  <td className="cc-clip" title={[sentTo(a.delivery), a.delivery?.note].filter(Boolean).join("\n") || undefined}>{sentTo(a.delivery)}</td>
                  <td className="cc-list-menu">{alertMenu(a).length > 0 && <RowMenu label={"Actions for the alert " + a.message} items={alertMenu(a)} />}</td>
                </tr>
              ))}
              {alerts && !shown.length && <tr className="is-static"><td colSpan={8} className="cc-none">{alerts.length ? "No alerts match." : status === "open" ? "No open alerts." : "No alerts here."}{rules && !rules.length ? " No alert rules yet: make one to be told when something goes wrong." : ""}</td></tr>}
              {!alerts && !error && <tr className="is-static"><td colSpan={8} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
          {alerts && <div className="cc-list-pager"><span>{shown.length} of {alerts.length}</span></div>}</div>
      </> : <>
        <div className="cc-list-panel cc-gap">
          {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the alert rule {deleting.name}? It stops watching; its alerts are kept as cleared.</span>
            <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
          <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Alert rules">
            <thead><tr>{["Status", "Rule", "Watches", "Condition", "Sends to", "Open alerts", "Last checked"].map((h) => <th key={h} scope="col">{h}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {(rules ?? []).map((r) => (
                <tr key={r.id} className={manage ? undefined : "is-static"} onClick={rowClick(() => { if (manage) setEditing(r); })}>
                  <td><span className={"cc-pill is-" + (r.enabled ? "active" : "disabled")}><span className="cc-pill-dot" />{r.enabled ? "On" : "Off"}</span></td>
                  <td>{r.name}{r.severity === "critical" && <span className="cc-tag">Critical</span>}</td>
                  <td className="cc-clip">{KINDS[r.kind]?.label ?? r.kind} · {scopeLabel(r)}</td>
                  <td className="cc-clip">{condition(r)}</td>
                  <td className="cc-clip" title={[...r.notify_to, ...(r.webhook_url ? ["webhook"] : [])].join(", ")}>{[...r.notify_to, ...(r.webhook_url ? ["webhook"] : [])].join(", ")}</td>
                  <td>{r.open ? <button type="button" className="cc-link cc-err" title="Show its alerts" onClick={() => showAlerts(r)}>{r.open}</button> : 0}</td>
                  <td>{when(r.last_checked)}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + r.name} items={ruleMenu(r)} /></td>
                </tr>
              ))}
              {rules && !rules.length && <tr className="is-static"><td colSpan={8} className="cc-none">No alert rules yet.{manage ? " Make one with New alert rule." : ""}</td></tr>}
            </tbody>
          </table>
        </div></div>
      </>}
      {editing && <RuleDialog api={api} rule={editing === "new" ? null : editing} choices={choices} onClose={() => setEditing(null)}
        onSaved={(t) => { onNotice(t); setEditing(null); if (tab !== "rules") setTab("rules"); else load(); }} />}
    </section>
  );
}

/** Making or changing a rule: what to watch, when it counts, and who hears of it. */
function RuleDialog({ api, rule, choices, onClose, onSaved }: { api: CrApi; rule: AlertRule | null; choices: Choices; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: rule?.name ?? "", kind: (rule?.kind ?? "run_failed") as AlertKind, scope: rule?.scope ?? "", threshold: rule?.threshold != null ? String(rule.threshold) : "",
    window: rule?.window_minutes != null ? String(rule.window_minutes) : "", people: (rule?.notify_to ?? ["role:admin"]).filter((t) => !t.includes("@")).join(", "),
    mail: (rule?.notify_to ?? []).filter((t) => t.includes("@")).join(", "), webhook: rule?.webhook_url ?? "", severity: rule?.severity ?? "warning" });
  const [error, setError] = useState<string | null>(null);
  const who = useCallback(() => principalDirectory(api), [api]);
  const k = KINDS[v.kind];
  const list = (s: string) => s.split(",").map((x) => x.trim()).filter(Boolean);
  const save = () => api.saveAlertRule(rule?.id ?? null, { name: v.name.trim(), kind: v.kind, scope: v.scope || null, severity: v.severity, enabled: rule?.enabled ?? true,
    threshold: k.threshold && v.threshold ? Number(v.threshold) : null, window_minutes: k.window && v.window ? Number(v.window) : null,
    notify_to: [...list(v.people), ...list(v.mail)], webhook_url: v.webhook.trim() || null })
    .then(() => onSaved((rule ? "Saved " : "Made ") + v.name.trim() + ": checked once a minute."), (e) => setError(message(e)));
  return (
    <Dialog open title={rule ? "Alert rule " + rule.name : "New alert rule"} width={640} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!v.name.trim()} onClick={save}>{rule ? "Save" : "Make the rule"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={120} placeholder="Invoice agents failing" onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
      <div className="cc-grid2">
        <Field label="Watch for" hint={k.hint}>{(id) => <Select id={id} value={v.kind} onChange={(e) => setV({ ...v, kind: e.target.value as AlertKind, scope: "" })}
          options={(Object.keys(KINDS) as AlertKind[]).map((x) => ({ value: x, label: KINDS[x].label }))} />}</Field>
        <Field label="In">{(id) => <Select id={id} value={v.scope} onChange={(e) => setV({ ...v, scope: e.target.value })}
          options={[{ value: "", label: SCOPE_ANY[k.scope] }, ...choices[k.scope]]} />}</Field>
        {k.threshold && <Field label={k.threshold[0]}>{(id) => <TextInput id={id} type="number" min={1} value={v.threshold} placeholder={String(k.threshold![1])} onChange={(e) => setV({ ...v, threshold: e.target.value })} />}</Field>}
        {k.window && <Field label={k.window[0]}>{(id) => <TextInput id={id} type="number" min={1} value={v.window} placeholder={String(k.window![1])} onChange={(e) => setV({ ...v, window: e.target.value })} />}</Field>}
        <Field label="Severity">{(id) => <Select id={id} value={v.severity} onChange={(e) => setV({ ...v, severity: e.target.value as "warning" | "critical" })}
          options={[{ value: "warning", label: "Warning" }, { value: "critical", label: "Critical" }]} />}</Field>
      </div>
      <h3 className="cc-log-title">Tell</h3>
      <Field label="People, in their task inbox">{(id) => <PrincipalPicker id={id} label="People" kinds={["user", "role"]} source={who} value={v.people} onChange={(x) => setV({ ...v, people: x })} />}</Field>
      <Field label="E-mail addresses" hint="Sent through Administration > Settings > E-mail">{(id) => <TextInput id={id} value={v.mail} placeholder="ops@example.com" onChange={(e) => setV({ ...v, mail: e.target.value })} />}</Field>
      <Field label="Webhook address" hint="Each alert is posted as JSON (Teams, Slack or your incident tool)">{(id) => <TextInput id={id} type="url" value={v.webhook} placeholder="https://" onChange={(e) => setV({ ...v, webhook: e.target.value })} />}</Field>
    </Dialog>
  );
}
