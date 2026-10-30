import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrPerson, CrUser, LicenseInfo, LicenseUsage, Runner } from "../api/cr";
import { Button, TextArea, TextInput } from "../components";

/**
 * Administration > Licenses, built as AA's Licenses page (checked against AA's docs, UiPath's licence allocation and
 * Blue Prism's licensing, 31 Oct 2026): tabs Overview (the licence, its state and expiry, seats used of purchased with
 * an alert from 80 %, installing a licence file offline), Allocation (seats to people and runners, as AA's device
 * licences and UiPath's user and robot licences) and Usage (90 days, and idle seats to take back, from UiPath's usage).
 */
type Tab = "overview" | "allocation" | "usage";
const TYPES: [string, string, "user" | "device", string][] = [
  ["developer", "Developer", "user", "Checks in agents"],
  ["citizen_developer", "Citizen Developer", "user", "Checks in simpler agents"],
  ["attended_runner", "Attended Runner", "user", "Runs agents on their own runner"],
  ["unattended_runner", "Unattended Runner", "device", "A runner that takes scheduled, triggered, API and queue work"],
];
const STATE: Record<string, [string, string]> = { unlicensed: ["No licence installed", "pending"], evaluation: ["Evaluation", "pending"], evaluation_ended: ["Evaluation ended", "failed"],
  active: ["Active", "success"], expiring: ["Expiring soon", "pending"], grace: ["Expired: grace period", "failed"], expired: ["Expired", "failed"], database_required: ["Needs SQL Server", "failed"] };
const typeName = (t: string) => TYPES.find(([k]) => k === t)?.[1] ?? t;
const when = (t?: string | null) => t ? new Date(t).toLocaleDateString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;

export function Licenses({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("overview");
  const [lic, setLic] = useState<LicenseInfo | null>(null);
  const [held, setHeld] = useState<Set<string>>(new Set());
  const [usage, setUsage] = useState<LicenseUsage | null>(null);
  const [people, setPeople] = useState<CrPerson[]>([]);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [error, setError] = useState<string | null>(null);
  const canAssign = user.permissions.includes("users.manage");
  const canInstall = user.permissions.includes("settings.manage");

  const load = useCallback(async () => {
    try {
      const [l, a, u, p, d] = await Promise.all([api.license(), api.licenseAssignments(), api.licenseUsage(90).catch(() => null),
        canAssign ? api.users().catch(() => []) : Promise.resolve([]), api.devices().catch(() => [])]);
      setLic(l); setHeld(new Set(a.map((x) => x.principal_type + "|" + x.principal + "|" + x.license_type))); setUsage(u); setPeople(p.filter((x) => x.enabled)); setRunners(d); setError(null);
    } catch (e) { setError(message(e)); }
  }, [api, canAssign]);
  useEffect(() => { load(); }, [load]);

  const assign = (type: string, principal: string, on: boolean) => api.assignSeat(type, principal, on)
    .then(() => { onNotice((on ? "Gave a " : "Took back the ") + typeName(type) + " seat " + (on ? "to " : "from ") + principal + "."); load(); }, (e) => { setError(message(e)); load(); });
  const seats = lic?.seats ?? {};
  const [stateText, stateCls] = STATE[lic?.state ?? ""] ?? [lic?.state ?? "", ""];
  const nearlyFull = TYPES.filter(([t]) => seats[t] && seats[t].total > 0 && seats[t].used / seats[t].total >= 0.8);

  return (
    <section className="st-page cc-home" aria-labelledby="cc-lic-title">
      <header className="st-page-head">
        <div><h1 id="cc-lic-title">Licenses</h1><p>The licence of this Command Center and who holds which seat.</p></div>
      </header>
      <div className="st-page-tabs" role="tablist" aria-label="Licenses">
        {([["overview", "Overview"], ["allocation", "Allocation"], ["usage", "Usage"]] as const).map(([t, l]) =>
          <button key={t} type="button" role="tab" className="st-page-tab" aria-selected={tab === t} onClick={() => setTab(t)}>{l}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!lic && !error && <p className="st-muted" role="status">Loading…</p>}

      {lic && tab === "overview" && <>
        <h2 className="cc-log-title">Licence <span className={"cc-status is-" + stateCls}>{stateText}</span></h2>
        {lic.state === "unlicensed" ? <p className="st-muted">No licence is installed, so nothing is limited. Install the licence file you received to apply its seats and expiry date.</p>
          : lic.state === "evaluation" || lic.state === "evaluation_ended"
            ? <p className="st-muted">{lic.state === "evaluation" ? "A 60-day evaluation: " + lic.days_left + " days left (until " + lic.evaluation_ends + "). " : "The 60-day evaluation ended on " + lic.evaluation_ends + ". "}
              It allows 2 developers, 2 attended and 2 unattended runners; seats are given out on first use.</p>
            : <dl className="cc-facts">
              <dt>Licensed to</dt><dd>{lic.customer}</dd>
              <dt>Edition</dt><dd>{lic.edition}</dd>
              <dt>Licence id</dt><dd className="ui-mono">{lic.id}</dd>
              <dt>Valid</dt><dd>{lic.issued} to {lic.expires}{lic.days_left !== undefined && lic.days_left >= 0 ? " (" + lic.days_left + " days left)" : ""}</dd>
              {lic.grace_days !== undefined && <><dt>Grace period</dt><dd>{lic.grace_days} days, until {lic.grace_until}</dd></>}
              {lic.bound_to && <><dt>Issued for</dt><dd>{lic.bound_to}</dd></>}
            </dl>}
        {lic.problem && <p className="st-alert" role="alert">{lic.problem}</p>}
        {lic.days_left !== undefined && lic.days_left >= 0 && lic.days_left <= 30 && (lic.state === "active" || lic.state === "expiring") &&
          <p className={lic.days_left <= 7 ? "st-alert" : "st-notice"} role="status">The licence ends in {lic.days_left} day{lic.days_left === 1 ? "" : "s"}, on {lic.expires}. Install the renewed licence file below.</p>}
        {lic.install_id && <p className="st-muted">Install id of this Command Center: <code>{lic.install_id}</code>. Send it with a licence request; a licence issued for it works only here.</p>}
        {lic.requires_server_database && <p className="st-alert">Move to Microsoft SQL Server (or PostgreSQL) before licensing. {lic.database_steps}</p>}
        <h2 className="cc-log-title">Seats</h2>
        {nearlyFull.length > 0 && <p className="st-alert" role="status">Nearly full: {nearlyFull.map(([t, l]) => l + " (" + seats[t].used + " of " + seats[t].total + ")").join(", ")}. Take back idle seats on the Usage tab, or extend the licence.</p>}
        <table className="st-table cc-table" aria-label="Seats">
          <thead><tr><th scope="col">Licence type</th><th scope="col">What it allows</th><th scope="col">In use</th><th scope="col">Purchased</th><th scope="col">Free</th><th scope="col">Idle</th><th scope="col"><span className="st-sr">Use</span></th></tr></thead>
          <tbody>{TYPES.map(([t, l, , what]) => {
            const s = seats[t], idle = usage?.seats[t]?.idle;
            const pct = s && s.total > 0 ? Math.min(100, Math.round(100 * s.used / s.total)) : 0;
            return <tr key={t}><td>{l}</td><td>{what}</td><td>{s ? s.used : 0}</td><td>{s && s.total >= 0 ? s.total : "Unlimited"}</td><td>{s && s.total >= 0 ? Math.max(0, s.total - s.used) : ""}</td><td>{idle ?? ""}</td>
              <td>{s && s.total > 0 && <span className="cc-meter" role="img" aria-label={pct + "% in use"}><span className={pct >= 100 ? "is-full" : pct >= 80 ? "is-high" : ""} style={{ width: pct + "%" }} /></span>}</td></tr>;
          })}</tbody>
        </table>
        {canInstall && !lic.requires_server_database && <InstallLicense api={api} onInstalled={() => { onNotice("Licence installed."); load(); }} />}
      </>}

      {lic && tab === "allocation" && <Allocation people={people} runners={runners} held={held} seats={seats} canAssign={canAssign} onAssign={assign} />}

      {lic && tab === "usage" && (usage ? <Usage usage={usage} canAssign={canAssign} onTakeBack={(type, principal) => assign(type, principal, false)} onNotice={onNotice} />
        : <p className="st-muted">Usage is not available.</p>)}
    </section>
  );
}

/** Install a licence file: chosen from disk or pasted (offline, as AA's on-premises licence upload). */
function InstallLicense({ api, onInstalled }: { api: CrApi; onInstalled: () => void }) {
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="cc-install">
      <h2 className="cc-log-title">Install a licence</h2>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <input type="file" accept=".lic,.json,.txt" aria-label="Licence file" onChange={async (e) => { const f = e.target.files?.[0]; if (f) setText((await f.text()).trim()); }} />
      <TextArea aria-label="Licence file text" rows={3} placeholder="Or paste the licence file text here" value={text} onChange={(e) => setText(e.target.value)} />
      <Button kind="primary" disabled={!text.trim()} onClick={() => api.installLicense(text.trim()).then(() => { setText(""); onInstalled(); }, (e) => setError(message(e)))}>Install licence</Button>
    </div>
  );
}

/** Seats to people (Developer, Citizen Developer, Attended Runner) and to runners (Unattended Runner). */
function Allocation({ people, runners, held, seats, canAssign, onAssign }: { people: CrPerson[]; runners: Runner[]; held: Set<string>;
  seats: Record<string, { total: number; used: number }>; canAssign: boolean; onAssign: (type: string, principal: string, on: boolean) => void }) {
  const [q, setQ] = useState("");
  const text = q.trim().toLowerCase();
  const ps = people.filter((p) => !text || (p.username + " " + (p.display_name ?? "")).toLowerCase().includes(text));
  const rs = runners.filter((r) => !text || (r.name + " " + (r.hostname ?? "")).toLowerCase().includes(text));
  const full = (t: string) => !!seats[t] && seats[t].total >= 0 && seats[t].used >= seats[t].total;
  const box = (type: string, kind: "user" | "device", principal: string, label: string) => {
    const on = held.has(kind + "|" + principal + "|" + type);
    return <input type="checkbox" aria-label={typeName(type) + " seat for " + label} checked={on} disabled={!canAssign || (!on && full(type))}
      title={!on && full(type) ? "No seats left" : undefined} onChange={(e) => onAssign(type, principal, e.target.checked)} />;
  };
  return (
    <div>
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search people and runners" placeholder="Search people and runners" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <h2 className="cc-log-title">People</h2>
      <table className="st-table cc-table" aria-label="People seats">
        <thead><tr><th scope="col">Person</th>{TYPES.filter(([, , k]) => k === "user").map(([t, l]) => <th key={t} scope="col">{l}{seats[t] && seats[t].total >= 0 ? " (" + seats[t].used + "/" + seats[t].total + ")" : ""}</th>)}</tr></thead>
        <tbody>
          {ps.map((p) => <tr key={p.username}><td>{p.display_name || p.username}{p.display_name && p.display_name !== p.username && <span className="st-muted"> ({p.username})</span>}</td>
            {TYPES.filter(([, , k]) => k === "user").map(([t]) => <td key={t}>{box(t, "user", p.username, p.username)}</td>)}</tr>)}
          {!ps.length && <tr><td colSpan={4} className="st-muted">{people.length ? "No people match." : "Only people who manage users see this list."}</td></tr>}
        </tbody>
      </table>
      <h2 className="cc-log-title">Runner Machines</h2>
      <table className="st-table cc-table" aria-label="Runner seats">
        <thead><tr><th scope="col">Runner</th><th scope="col">Status</th><th scope="col">Unattended Runner{seats.unattended_runner && seats.unattended_runner.total >= 0 ? " (" + seats.unattended_runner.used + "/" + seats.unattended_runner.total + ")" : ""}</th></tr></thead>
        <tbody>
          {rs.map((r) => <tr key={r.id}><td>{r.name}</td><td>{r.status}</td><td>{box("unattended_runner", "device", r.id, r.name)}</td></tr>)}
          {!rs.length && <tr><td colSpan={3} className="st-muted">No runners.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/** 90 days of seats in use, and each holder's last activity: idle seats (no sign-in or runs for 30 days) can be taken back. */
function Usage({ usage, canAssign, onTakeBack, onNotice }: { usage: LicenseUsage; canAssign: boolean; onTakeBack: (type: string, principal: string) => void; onNotice: (t: string) => void }) {
  const [idleOnly, setIdleOnly] = useState(false);
  const holders = useMemo(() => usage.holders.filter((h) => !idleOnly || h.idle), [usage, idleOnly]);
  const spark = (type: string) => {
    const pts = usage.trend.filter((r) => r.license_type === type);
    const max = Math.max(1, ...pts.map((r) => Math.max(r.used, r.total))), w = 160, ht = 28;
    const line = pts.map((r, i) => (pts.length === 1 ? w : i * w / (pts.length - 1)).toFixed(1) + "," + (ht - 2 - (ht - 4) * r.used / max).toFixed(1)).join(" ");
    return <svg viewBox={"0 0 " + w + " " + ht} width={w} height={ht} role="img" aria-label={typeName(type) + " seats in use over " + pts.length + " days"}>
      {pts.length > 0 && <polyline points={line} fill="none" stroke="var(--ui-accent)" strokeWidth="2" />}</svg>;
  };
  const exportCsv = () => {
    const text = [["Holder", "User name or runner", "Licence", "Since", "Last active", "Runs (30 days)", "Idle"],
      ...holders.map((h) => [h.name, h.principal, typeName(h.license_type), when(h.assigned), when(h.last_active), String(h.runs_30_days), h.idle ? "yes" : "no"])].map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "licence-use.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + holders.length + " seat holder" + (holders.length === 1 ? "" : "s") + ".");
  };
  return (
    <div>
      <p className="st-muted">Seats in use each day over the last 90 days, and who holds each one. A seat whose holder has not signed in (a runner: not been online) and started no runs for 30 days is idle: a candidate to take back.</p>
      <table className="st-table cc-table" aria-label="Seat use">
        <thead><tr><th scope="col">Licence type</th><th scope="col">In use</th><th scope="col">Idle</th><th scope="col">Last 90 days</th></tr></thead>
        <tbody>{Object.entries(usage.seats).map(([t, s]) => <tr key={t}><td>{s.label}</td><td>{s.used}{s.total >= 0 ? " of " + s.total : ""}</td><td>{s.idle}</td><td>{spark(t)}</td></tr>)}</tbody>
      </table>
      <div className="cc-bar" role="toolbar" aria-label="Seat holder actions">
        <label className="st-check-line"><input type="checkbox" checked={idleOnly} onChange={(e) => setIdleOnly(e.target.checked)} /> Idle seats only</label>
        <span className="st-bar-fill" />
        <Button kind="secondary" onClick={exportCsv}>Export CSV</Button>
      </div>
      <table className="st-table cc-table" aria-label="Seat holders">
        <thead><tr><th scope="col">Holder</th><th scope="col">Licence</th><th scope="col">Since</th><th scope="col">Last active</th><th scope="col">Runs (30 days)</th><th scope="col"><span className="st-sr">Take back</span></th></tr></thead>
        <tbody>
          {holders.map((h) => <tr key={h.principal + h.license_type}>
            <td>{h.name}{h.name !== h.principal && <span className="st-muted"> ({h.principal})</span>}{h.idle && <span className="cc-tag">idle</span>}</td>
            <td>{typeName(h.license_type)}</td><td>{when(h.assigned)}</td><td>{h.last_active ? when(h.last_active) : "Never"}</td><td>{h.runs_30_days}</td>
            <td>{canAssign && h.idle && <Button kind="plain" onClick={() => onTakeBack(h.license_type, h.principal)}>Take back</Button>}</td>
          </tr>)}
          {!holders.length && <tr><td colSpan={6} className="st-muted">{idleOnly ? "No idle seats." : "Nobody holds a seat yet."}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
