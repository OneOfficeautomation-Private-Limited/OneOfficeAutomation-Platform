import { useCallback, useEffect, useState } from "react";
import type { Alert, AlertKind, AlertRule, CrApi, CrUser } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Operational Insights > Alerts, as UiPath's real-time monitoring alerts on processes, queues and machines and AA's
 * notifications (checked 31 Oct 2026). Rules watch one condition each, checked once a minute; an alert goes to the
 * task inbox, e-mail and a webhook. Alerts on a state clear by themselves when it ends; alerts on a failed run stay
 * open until acknowledged. Seeing needs analytics.view; changing rules needs alerts.manage.
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
};
const SCOPE_ANY: Record<Scope, string> = { agent: "Any agent", runner: "Any runner", queue: "Any work queue", connection: "Any model connection" };
const STATUS: Record<Alert["status"], string> = { open: "Open", acknowledged: "Acknowledged", cleared: "Cleared" };
const name = (path: string) => path.split("/").pop()!.replace(/\.(flow|process)\.json$/, "");
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const sentTo = (d: Alert["delivery"]) => !d ? "" : [d.inbox?.length ? "Inbox: " + d.inbox.join(", ") : "", d.emailed?.length ? "E-mail: " + d.emailed.join(", ") : "",
  d.not_emailed?.length ? "Not e-mailed: " + d.not_emailed.join(", ") : "", d.webhook ? "Webhook" : ""].filter(Boolean).join(" · ");
type Choices = Record<Scope, { value: string; label: string }[]>;

export function Alerts({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<"alerts" | "rules">("alerts");
  const [status, setStatus] = useState("open");
  const [alerts, setAlerts] = useState<Alert[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [rules, setRules] = useState<AlertRule[] | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  // #/alerts?new=1 (Overview's New alert rule) opens the new rule straight away
  const [editing, setEditing] = useState<AlertRule | "new" | null>(() => user.permissions.includes("alerts.manage") && /[?&]new=1/.test(location.hash) ? "new" : null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [choices, setChoices] = useState<Choices>({ agent: [], runner: [], queue: [], connection: [] });
  const can = (p: string) => user.permissions.includes(p);
  const manage = can("alerts.manage");

  const load = useCallback(async () => {
    try {
      const [a, r] = await Promise.all([api.alerts({ status, limit: "200" }), api.alertRules()]);
      setAlerts(a.alerts); setOpen(a.open); setRules(r); setError(null);
      setSelected((s) => s.filter((id) => a.alerts.some((x) => x.id === id && x.status === "open")));
    } catch (e) { setError(message(e)); }
  }, [api, status]);
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
  const remove = (id: string) => api.deleteAlertRule(id).then(() => { onNotice("Deleted the alert rule; its alerts are kept as cleared."); setDeleting(null); load(); }, (e) => setError(message(e)));
  const openAlerts = (alerts ?? []).filter((a) => a.status === "open");

  return (
    <section className="st-page cc-home" aria-labelledby="cc-al-title">
      <header className="st-page-head">
        <div><h1 id="cc-al-title">Alerts</h1><p>Rules that watch runs, runners, work queues and AI budgets once a minute, and tell people in the task inbox, by e-mail or through a webhook.</p></div>
        {manage && <Button kind="primary" onClick={() => setEditing("new")}>New alert rule</Button>}
      </header>
      <div className="st-page-tabs" role="tablist" aria-label="Alerts">
        <button type="button" role="tab" className="st-page-tab" aria-selected={tab === "alerts"} onClick={() => setTab("alerts")}>Alerts{open != null && <span className="cc-count">{open}</span>}</button>
        <button type="button" role="tab" className="st-page-tab" aria-selected={tab === "rules"} onClick={() => setTab("rules")}>Rules{rules && <span className="cc-count">{rules.length}</span>}</button>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {tab === "alerts" ? <>
        <div className="cc-bar" role="toolbar" aria-label="Alerts actions">
          <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setAlerts(null); }}
            options={[{ value: "open", label: "Open" }, { value: "acknowledged", label: "Acknowledged" }, { value: "cleared", label: "Cleared" }, { value: "all", label: "All" }]} />
          {selected.length > 0 && <span className="cc-chosen">{selected.length} selected</span>}
          <Button kind="secondary" disabled={!selected.length} onClick={() => acknowledge(selected)}>Acknowledge</Button>
          <span className="st-bar-fill" />
          <Button kind="plain" onClick={load}>Refresh</Button>
        </div>
        <div className="cc-table-wrap">
          <table className="st-table cc-table" aria-label="Alerts list">
            <thead><tr>
              <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={openAlerts.length > 0 && selected.length === openAlerts.length}
                onChange={(e) => setSelected(e.target.checked ? openAlerts.map((a) => a.id) : [])} /></th>
              {["Severity", "Alert", "Rule", "Raised", "State", "Sent to"].map((h) => <th key={h} scope="col">{h}</th>)}
            </tr></thead>
            <tbody>
              {(alerts ?? []).map((a) => (
                <tr key={a.id} className="is-static">
                  <td className="cc-check">{a.status === "open" && <input type="checkbox" aria-label={"Select the alert " + a.message} checked={selected.includes(a.id)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, a.id] : selected.filter((x) => x !== a.id))} />}</td>
                  <td className={"cc-status cc-sev is-" + a.severity}>{a.severity === "critical" ? "Critical" : "Warning"}</td>
                  <td className="cc-wrap">{a.link ? <a href={a.link}>{a.message}</a> : a.message}</td>
                  <td>{a.rule_name}</td><td>{when(a.raised)}</td>
                  <td>{STATUS[a.status]}{a.status === "acknowledged" && a.acknowledged_by ? " by " + a.acknowledged_by : a.status === "cleared" && a.cleared ? " " + when(a.cleared) : ""}</td>
                  <td className="cc-wrap" title={a.delivery?.note}>{sentTo(a.delivery)}</td>
                </tr>
              ))}
              {alerts && !alerts.length && <tr><td colSpan={7} className="st-muted">{status === "open" ? "No open alerts." : "No alerts here."}{rules && !rules.length ? " No alert rules yet: make one to be told when something goes wrong." : ""}</td></tr>}
              {!alerts && !error && <tr><td colSpan={7} className="st-muted" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </> : <>
        <div className="cc-table-wrap cc-gap">
          <table className="st-table cc-table" aria-label="Alert rules">
            <thead><tr>{["State", "Rule", "Watches", "Condition", "Sends to", "Open alerts", "Last checked", ""].map((h, i) => <th key={i} scope="col">{h}</th>)}</tr></thead>
            <tbody>
              {(rules ?? []).map((r) => (
                <tr key={r.id} className="is-static">
                  <td className={"cc-status is-" + (r.enabled ? "success" : "stopped")}>{r.enabled ? "On" : "Off"}</td>
                  <td>{manage ? <button type="button" className="cc-link" onClick={() => setEditing(r)}>{r.name}</button> : r.name}{r.severity === "critical" && <span className="cc-tag">Critical</span>}</td>
                  <td>{KINDS[r.kind]?.label ?? r.kind} · {scopeLabel(r)}</td>
                  <td>{condition(r)}</td>
                  <td className="cc-wrap">{[...r.notify_to, ...(r.webhook_url ? ["webhook"] : [])].join(", ")}</td>
                  <td className={r.open ? "cc-err" : undefined}>{r.open ?? 0}</td>
                  <td>{when(r.last_checked)}</td>
                  <td>{manage && (deleting === r.id
                    ? <span className="cc-inline">Delete {r.name}? <Button kind="danger" onClick={() => remove(r.id)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Keep</Button></span>
                    : <span className="cc-inline"><Button kind="plain" onClick={() => toggle(r)}>{r.enabled ? "Turn off" : "Turn on"}</Button>
                      <Button kind="plain" onClick={() => test(r)}>Send a test</Button><Button kind="plain" onClick={() => setDeleting(r.id)}>Delete</Button></span>)}</td>
                </tr>
              ))}
              {rules && !rules.length && <tr><td colSpan={8} className="st-muted">No alert rules yet.{manage ? " Make one with New alert rule." : ""}</td></tr>}
            </tbody>
          </table>
        </div>
      </>}
      {editing && <RuleDialog api={api} rule={editing === "new" ? null : editing} choices={choices} onClose={() => setEditing(null)}
        onSaved={(t) => { onNotice(t); setEditing(null); setTab("rules"); load(); }} />}
    </section>
  );
}

/** Making or changing a rule: what to watch, when it counts, and who hears of it. */
function RuleDialog({ api, rule, choices, onClose, onSaved }: { api: CrApi; rule: AlertRule | null; choices: Choices; onClose: () => void; onSaved: (t: string) => void }) {
  const [v, setV] = useState({ name: rule?.name ?? "", kind: (rule?.kind ?? "run_failed") as AlertKind, scope: rule?.scope ?? "", threshold: rule?.threshold != null ? String(rule.threshold) : "",
    window: rule?.window_minutes != null ? String(rule.window_minutes) : "", people: (rule?.notify_to ?? ["role:admin"]).filter((t) => !t.includes("@")).join(", "),
    mail: (rule?.notify_to ?? []).filter((t) => t.includes("@")).join(", "), webhook: rule?.webhook_url ?? "", severity: rule?.severity ?? "warning" });
  const [error, setError] = useState<string | null>(null);
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
      <Field label="People, in their task inbox" hint="role:NAME or user:NAME, separated by commas">{(id) => <TextInput id={id} value={v.people} placeholder="role:admin, user:asha" onChange={(e) => setV({ ...v, people: e.target.value })} />}</Field>
      <Field label="E-mail addresses" hint="Sent through Administration > Settings > E-mail">{(id) => <TextInput id={id} value={v.mail} placeholder="ops@example.com" onChange={(e) => setV({ ...v, mail: e.target.value })} />}</Field>
      <Field label="Webhook address" hint="Each alert is posted as JSON (Teams, Slack or your incident tool)">{(id) => <TextInput id={id} type="url" value={v.webhook} placeholder="https://" onChange={(e) => setV({ ...v, webhook: e.target.value })} />}</Field>
    </Dialog>
  );
}
