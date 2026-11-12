import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, EventSubscription, EventSubscriptionList } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextInput, type HelpTopic, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, when } from "./ListParts";

/**
 * Administration > Event subscriptions (gap G38; UiPath's webhooks, and run logs streamed to a log platform such as
 * Splunk): addresses One CommandCenter posts its events to as they happen (runs, work items, runners, alerts, audit
 * entries, run log lines), signed with a secret shown once, with a test button and how delivery goes. Below the list,
 * the metrics token for GET /metrics (Prometheus). Needs events.manage (administrators).
 */
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/** The event types by family, in our words, for the dialog's tick boxes. */
const GROUPS: { title: string; types: [string, string][] }[] = [
  { title: "Runs", types: [["run.queued", "Queued"], ["run.started", "Started"], ["run.finished", "Finished (any result)"], ["run.failed", "Failed"],
    ["run.paused", "Paused"], ["run.resumed", "Resumed"], ["run.log", "Run log lines"]] },
  { title: "Work items", types: [["queue.item_added", "Added"], ["queue.item_started", "Started"], ["queue.item_finished", "Finished"], ["queue.item_abandoned", "Abandoned"]] },
  { title: "Runners", types: [["runner.online", "Came online"], ["runner.offline", "Went offline"]] },
  { title: "Other", types: [["alert.raised", "Alert raised"], ["audit.entry", "Every audit entry"]] },
];

const HELP: HelpTopic = { title: "Event subscriptions", about: "One CommandCenter posts its events as they happen to the addresses listed here: "
  + "a webhook receiver, an integration platform, or Splunk's HTTP Event Collector. Each event is one JSON message.",
  items: [
    { name: "Events", text: "Runs (queued, started, finished, failed, paused, resumed, and their log lines), work items, runners coming online or going offline, "
      + "alerts raised, and every audit entry. A type ending in * takes a whole family, such as queue.item_*." },
    { name: "Format", text: "JSON: the event as it is, signed. Splunk: the shape Splunk's HTTP Event Collector takes, with the collector token as the secret; each run log line becomes one Splunk event." },
    { name: "Signature", text: "JSON events carry X-OOA-Signature: sha256= and the HMAC-SHA256 of the X-OOA-Timestamp value, a dot and the body, made with the secret. "
      + "The receiver makes the same and compares, and refuses old timestamps. X-OOA-Event-Id is the event's id: the same event may come twice, so keep the ids you have seen." },
    { name: "Secret", text: "Made for you and shown once when the subscription is made or when you ask for a new one. It is kept encrypted and never shown again." },
    { name: "Only for", text: "Folders or agent paths, such as Finance/. Events about other agents are not sent. Events that name no agent (runners, most audit entries) are always sent." },
    { name: "Delivery", text: "In the background, never holding up a run. A receiver that does not answer, or answers 408, 429 or 5xx, is tried again after 2 s, 10 s, 30 s and 2 minutes. "
      + "Failures counts deliveries that failed in a row; it goes back to 0 after one succeeds. Each event is sent once, by the One CommandCenter node where it happened." },
    { name: "Addresses", text: "https:// addresses on the public internet. Settings > E-mail, proxy and SIEM > Event subscriptions can allow http:// (test systems) and addresses in your own network." },
    { name: "Metrics", text: "GET /metrics gives One CommandCenter's figures in the Prometheus format (runs, durations, queues, runners, alerts, licence seats, memory). "
      + "Prometheus, or the OpenTelemetry Collector's Prometheus receiver, reads it with the metrics token as a bearer token. Each node answers for itself with its node label." },
  ] };

/** How the list names a type: "Run failed", "Work item added", "Run log lines". */
const typeLabel = (t: string) => {
  if (t === "run.log") return "Run log lines";
  if (t.endsWith("*")) return t;
  for (const g of GROUPS) for (const [k, l] of g.types) if (k === t) return g.title === "Other" ? l : g.title.replace(/s$/, "") + " " + l.toLowerCase();
  return t;
};
const eventsText = (events: string[]) => events.map(typeLabel).join(", ");

export function EventSubscriptions({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [data, setData] = useState<EventSubscriptionList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [editing, setEditing] = useState<EventSubscription | "new" | null>(null);
  const [deleting, setDeleting] = useState<EventSubscription | null>(null);
  const [shown, setShown] = useState<{ what: string; value: string } | null>(null);   // a secret or token, shown once
  const load = useCallback(async () => {
    try { setData(await api.eventSubscriptions()); setError(null); } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  const list = useMemo(() => (data?.subscriptions ?? []).filter((s) => !q.trim()
    || [s.name, s.url, s.events.join(" "), s.filters.join(" ")].join(" ").toLowerCase().includes(q.trim().toLowerCase())), [data, q]);
  const fail = (e: unknown) => setError(message(e));
  // after an action: the list again, keeping any message the action left
  const reload = () => { api.eventSubscriptions().then(setData, fail); };
  const toggle = (s: EventSubscription) => api.setEventSubscriptionEnabled(s.id, !s.enabled)
    .then(() => { onNotice((s.enabled ? "Turned off " : "Turned on ") + s.name + "."); load(); }, fail);
  const test = (s: EventSubscription) => api.testEventSubscription(s.id).then((r) => {
    if (r.ok) onNotice("The receiver of " + s.name + " accepted the test event (HTTP " + r.status + ").");
    else setError("The test event to " + s.name + " was not accepted: " + (r.error ?? "no answer"));
    reload();
  }, fail);
  const remove = (s: EventSubscription) => api.deleteEventSubscription(s.id)
    .then(() => { onNotice("Deleted " + s.name + "."); setDeleting(null); load(); }, (e) => { setDeleting(null); fail(e); });
  const menu = (s: EventSubscription) => [
    { key: "edit", label: "Edit", onSelect: () => setEditing(s) },
    { key: "test", label: "Send a test event", onSelect: () => { test(s); } },
    { key: "toggle", label: s.enabled ? "Turn off" : "Turn on", onSelect: () => { toggle(s); } },
    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setDeleting(s) },
  ];
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  const makeToken = () => api.makeMetricsToken().then((r) => { setShown({ what: "Metrics token", value: r.token }); load(); }, fail);
  const dropToken = () => api.deleteMetricsToken().then(() => { onNotice("Removed the metrics token; scrapes with it are refused."); load(); }, fail);

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-events-title">
      <header className="cc-list-head">
        <h1 id="cc-events-title">Event subscriptions</h1>
        <HelpButton topic={HELP} />
        {data && <span className="cc-list-count">{data.subscriptions.length}</span>}
        <span className="ov-fill" />
        <Button kind="primary" onClick={() => setEditing("new")}>New subscription</Button>
      </header>
      {error && <PageError text={error} />}
      {shown && <div className="cc-list-bulk" role="status">
        <span>{shown.what}, shown this once: copy it now.</span>
        <TextInput readOnly mono aria-label={shown.what} value={shown.value} onFocus={(e) => e.currentTarget.select()} />
        <Button kind="plain" onClick={() => setShown(null)}>Done</Button>
      </div>}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search subscriptions" placeholder="Search subscriptions" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          {data && (data.allow_http || data.allow_private) && <span className="cc-tag">Settings allow {[data.allow_http && "http://", data.allow_private && "private addresses"].filter(Boolean).join(" and ")}</span>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="List tools"><RefreshButton onClick={load} /></div>
      </div>
      <div className="cc-list-panel">
        {deleting && <div className="cc-list-bulk" role="alert"><span>Delete the subscription {deleting.name}? Its receiver gets no more events.</span>
          <Button kind="danger" onClick={() => remove(deleting)}>Delete</Button><Button kind="plain" onClick={() => setDeleting(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Event subscriptions list">
            <thead><tr>{["State", "Name", "Receiver", "Events", "Last delivery", "Failures"].map((h) => <th key={h} scope="col">{h}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {list.map((s) => (
                <tr key={s.id} onClick={rowClick(() => setEditing(s))}>
                  <td><span className={"cc-pill is-" + (s.enabled ? "active" : "disabled")}><span className="cc-pill-dot" />{s.enabled ? "On" : "Off"}</span></td>
                  <td>{s.name}{s.format === "splunk" && <span className="cc-tag">Splunk</span>}</td>
                  <td className="cc-clip" title={s.url}>{s.url}</td>
                  <td className="cc-clip" title={eventsText(s.events) + (s.filters.length ? "\nOnly for " + s.filters.join(", ") : "")}>
                    {eventsText(s.events)}{s.filters.length ? " · only " + s.filters.join(", ") : ""}</td>
                  <td className="cc-clip" title={s.last_error ?? undefined}>{s.last_delivery ? (s.failures ? "Failed" : "Delivered") + (s.last_status && s.last_status !== "error" ? " (" + s.last_status + ")" : "") + " " + when(s.last_delivery) : "Nothing sent yet"}</td>
                  <td>{s.failures ? <span className="cc-err" title={s.last_error ?? undefined}>{s.failures}</span> : 0}</td>
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + s.name} items={menu(s)} /></td>
                </tr>
              ))}
              {data && !list.length && <tr className="is-static"><td colSpan={7} className="cc-none">{data.subscriptions.length ? "No subscriptions match." : "No subscriptions yet. Make one with New subscription."}</td></tr>}
              {!data && !error && <tr className="is-static"><td colSpan={7} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      <div className="cc-list-panel cc-gap" aria-labelledby="cc-metrics-title" role="region">
        <h2 id="cc-metrics-title" className="cc-log-title">Metrics for Prometheus</h2>
        <p>Address <code>{origin}/metrics</code>, read with the metrics token as a bearer token ({data?.metrics_token ? "a token is set" : "no token yet"}).
          The OpenTelemetry Collector reads it with its Prometheus receiver.</p>
        <p>
          <Button kind="secondary" onClick={makeToken}>{data?.metrics_token ? "Make a new token" : "Make a metrics token"}</Button>{" "}
          {data?.metrics_token && <Button kind="plain" onClick={dropToken}>Remove the token</Button>}
        </p>
      </div>
      {editing && <SubscriptionDialog api={api} sub={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={(t, secret) => { onNotice(t); setEditing(null); if (secret) setShown({ what: "Signing secret", value: secret }); load(); }} />}
    </section>
  );
}

/** Making or changing a subscription: where, which events, which format, which agents, and its secret. */
function SubscriptionDialog({ api, sub, onClose, onSaved }: { api: CrApi; sub: EventSubscription | null; onClose: () => void; onSaved: (t: string, secret?: string) => void }) {
  const [v, setV] = useState({ name: sub?.name ?? "", url: sub?.url ?? "", format: sub?.format ?? "json", events: sub?.events ?? ["run.finished"],
    filters: (sub?.filters ?? []).join(", "), secret: "", rotate: false });
  const [error, setError] = useState<string | null>(null);
  const has = (t: string) => v.events.includes(t);
  const tick = (t: string, on: boolean) => setV({ ...v, events: on ? [...v.events, t] : v.events.filter((x) => x !== t) });
  const save = () => api.saveEventSubscription(sub?.id ?? null, { name: v.name.trim(), url: v.url.trim(), format: v.format, events: v.events,
    filters: v.filters.split(",").map((x) => x.trim()).filter(Boolean), enabled: sub?.enabled ?? true,
    ...(v.secret.trim() ? { secret: v.secret.trim() } : {}), ...(v.rotate ? { rotate_secret: true } : {}) })
    .then((r) => onSaved((sub ? "Saved " : "Made ") + v.name.trim() + ".", r.secret), (e) => setError(message(e)));
  const splunk = v.format === "splunk";
  return (
    <Dialog open title={sub ? "Event subscription " + sub.name : "New event subscription"} width={680} onClose={onClose}
      footer={<><Button kind="plain" onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!v.name.trim() || !v.url.trim() || !v.events.length} onClick={save}>{sub ? "Save" : "Make the subscription"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Name">{(id) => <TextInput id={id} value={v.name} maxLength={120} placeholder="Run results to ServiceNow" onChange={(e) => setV({ ...v, name: e.target.value })} />}</Field>
        <Field label="Format">{(id) => <Select id={id} value={v.format} onChange={(e) => setV({ ...v, format: e.target.value as "json" | "splunk", rotate: false })}
          options={[{ value: "json", label: "JSON, signed" }, { value: "splunk", label: "Splunk HTTP Event Collector" }]} />}</Field>
      </div>
      <Field label="Receiver address" hint={splunk ? "The collector's address, such as https://splunk.example.com:8088/services/collector/event" : "Each event is posted here as JSON"}>
        {(id) => <TextInput id={id} type="url" value={v.url} maxLength={1000} placeholder="https://" onChange={(e) => setV({ ...v, url: e.target.value })} />}</Field>
      <h3 className="cc-log-title" id="cc-events-pick">Events</h3>
      <div role="group" aria-labelledby="cc-events-pick">
        <div className="cc-grid2">
          {GROUPS.map((g) => <div key={g.title} role="group" aria-label={g.title} className="cc-ev-types">
            <strong>{g.title}</strong>
            {g.types.map(([t, l]) => <label key={t} className="st-check-line"><input type="checkbox" checked={has(t)} onChange={(e) => tick(t, e.target.checked)} /> {l}</label>)}
          </div>)}
        </div>
      </div>
      <Field label="Only for these folders or agents" hint="Paths separated by commas, such as Finance/. Empty: every agent.">
        {(id) => <TextInput id={id} value={v.filters} placeholder="Finance/, HR/Payroll.flow.json" onChange={(e) => setV({ ...v, filters: e.target.value })} />}</Field>
      <Field label={splunk ? "Collector token" : "Signing secret"}
        hint={splunk ? (sub?.has_secret ? "Kept. Paste a new token to change it." : "The HTTP Event Collector token from Splunk.")
          : sub ? "Kept. Paste your own to change it, or make a new one below." : "Leave empty and one is made for you, shown once."}>
        {(id) => <TextInput id={id} type="password" autoComplete="new-password" value={v.secret} onChange={(e) => setV({ ...v, secret: e.target.value })} />}</Field>
      {sub && !splunk && <label className="st-check-line"><input type="checkbox" checked={v.rotate} onChange={(e) => setV({ ...v, rotate: e.target.checked })} /> Make a new signing secret (the old one stops working)</label>}
    </Dialog>
  );
}
