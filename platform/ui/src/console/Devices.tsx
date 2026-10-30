import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, Runner, RunnerPool } from "../api/cr";
import { Button, Dialog, Field, Select, TextArea, TextInput, usePersistent } from "../components";

/**
 * Manage > Runners, built as AA's Devices page (checked against AA's live Control Room and docs, UiPath Machines and
 * Blue Prism Resources, 30 Oct 2026): two tabs, Runners and Runner pools. Runners: search in a chosen field, filters,
 * a column chooser, and for the selected runners: update settings, update the runner program, take out of service
 * (no new work, as AA's maintenance and Blue Prism's retire) or back, delete, export. A runner's details open from its
 * row. Pools: runners that share work, created and changed here. The list refreshes every 15 seconds.
 */
type Tab = "runners" | "pools";
type Col = { key: string; label: string; cell: (d: Runner, pools: RunnerPool[]) => string };

const STATUS: Record<string, string> = { online: "Online", offline: "Offline", disabled: "Out of service" };
const TYPE: Record<string, string> = { single_user: "Single-user", multi_user: "Multi-user", serverless: "Serverless (in the Command Center)" };
const SCREEN: Record<string, string> = { unlocked: "Unlocked", locked: "Locked", no_desktop: "No desktop (service)" };
const AFTER_RUN: Record<string, string> = { nothing: "Leave the screen as it is", lock: "Lock the screen", sign_out: "Sign out" };
const LOG_LEVELS = ["error", "warning", "info", "debug"];
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
function ago(t?: string | null) {
  if (!t) return "";
  const s = Math.max(0, Math.round((Date.now() - new Date(t).getTime()) / 1000));
  return s < 60 ? "just now" : s < 3600 ? Math.floor(s / 60) + " min ago" : s < 86_400 ? Math.floor(s / 3600) + " h ago" : Math.floor(s / 86_400) + " days ago";
}
const poolsOf = (d: Runner, pools: RunnerPool[]) => pools.filter((p) => p.devices.includes(d.id)).map((p) => p.name).join(", ");
const program = (d: Runner) => d.up_to_date === undefined ? "" : d.up_to_date ? "Up to date" : d.update_requested ? "Updating" : "Update available";

const COLS: Col[] = [
  { key: "status", label: "Status", cell: (d) => STATUS[d.status] ?? d.status },
  { key: "name", label: "Runner", cell: (d) => d.name },
  { key: "os", label: "Platform", cell: (d) => d.os ?? "" },
  { key: "users", label: "Default users", cell: (d) => (d.default_users ?? []).join(", ") },
  { key: "type", label: "Runner type", cell: (d) => TYPE[d.device_type ?? "multi_user"] ?? d.device_type ?? "" },
  { key: "lifespan", label: "Lifespan", cell: (d) => d.lifespan === "temporary" ? "Temporary" : "Persistent" },
  { key: "log", label: "Log level", cell: (d) => d.log_level ?? "info" },
  { key: "screen", label: "Screen", cell: (d) => SCREEN[d.session_state ?? ""] ?? "" },
  { key: "after", label: "After a run", cell: (d) => AFTER_RUN[d.after_run ?? "nothing"] },
  { key: "running", label: "Running", cell: (d) => String(d.running ?? 0) },
  { key: "version", label: "Runner version", cell: (d) => (d.agent_version ?? "") + (d.version_status && d.version_status !== "ok" ? " (newer than the Command Center)" : "") },
  { key: "program", label: "Program", cell: program },
  { key: "pools", label: "Runner pools", cell: poolsOf },
  { key: "seen", label: "Last seen", cell: (d) => ago(d.last_seen) },
  { key: "registered", label: "Registered", cell: (d) => when(d.registered) + (d.registered_by ? " by " + d.registered_by : "") },
];
/** The fields the search box can look in, as AA's search chooser. */
const SEARCH_IN: [string, string][] = [["name", "Runner name"], ["os", "Platform"], ["users", "Default users"], ["type", "Runner type"], ["lifespan", "Lifespan"], ["pools", "Runner pools"]];
const PAGE = 50;
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Devices({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = usePersistent<Tab>("devices.tab", "runners");
  const [hidden, setHidden] = usePersistent<string[]>("devices.hidden", ["screen", "after", "registered"]);
  const [runners, setRunners] = useState<Runner[] | null>(null);
  const [pools, setPools] = useState<RunnerPool[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searchIn, setSearchIn] = useState("name");
  const [f, setF] = useState<Record<string, string>>({});
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "columns" | "register" | "settings" | "delete" } | { kind: "details"; id: string } | { kind: "pool"; pool: RunnerPool | null; members?: string[] }>(null);
  const can = (p: string) => user.permissions.includes(p);
  const manage = can("devices.manage");

  const load = useCallback(async () => {
    try {
      const [d, p] = await Promise.all([api.devices(), api.pools()]);
      setRunners(d); setPools(p); setError(null);
      setSelected((s) => s.filter((id) => d.some((x) => x.id === id)));
    } catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(() => { if (!dialog) load(); }, 15_000); return () => clearInterval(t); }, [load, dialog]);

  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    const col = COLS.find((c) => c.key === searchIn)!;
    return (runners ?? []).filter((d) => (!text || col.cell(d, pools).toLowerCase().includes(text) || (searchIn === "name" && (d.hostname ?? "").toLowerCase().includes(text)))
      && (!f.status || d.status === f.status) && (!f.pool || pools.some((p) => p.id === f.pool && p.devices.includes(d.id))));
  }, [runners, pools, f, searchIn]);
  const page = shown.slice(offset, offset + PAGE);
  const chosen = (runners ?? []).filter((d) => selected.includes(d.id));
  const cols = COLS.filter((c) => !hidden.includes(c.key));
  const setFilter = (k: string, v: string) => { setF({ ...f, [k]: v }); setOffset(0); };

  /** One action on each selected runner; counts what worked and shows the first failure. */
  const each = async (what: string, fn: (d: Runner) => Promise<unknown>) => {
    let done = 0, failed: string | null = null;
    for (const d of chosen) { try { await fn(d); done++; } catch (e) { failed ??= d.name + ": " + message(e); } }
    onNotice(what + " " + done + " runner" + (done === 1 ? "" : "s") + ".");
    setError(failed); setSelected([]); load();
  };
  const exportCsv = () => {
    const rows = chosen.length ? chosen : shown;
    const text = [["Runner id", ...COLS.map((c) => c.label)], ...rows.map((d) => [d.id, ...COLS.map((c) => c.cell(d, pools))])].map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "runners.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + rows.length + " runner" + (rows.length === 1 ? "" : "s") + ".");
  };

  const online = (runners ?? []).filter((d) => d.status === "online").length;
  return (
    <section className="st-page cc-home" aria-labelledby="cc-dev-title">
      <header className="st-page-head">
        <div><h1 id="cc-dev-title">Runners</h1>
          <p>Machines running the One Runner. A runner is online when it checked in during the last minute.{runners ? " " + online + " of " + runners.length + " online." : ""}</p></div>
        {tab === "runners" && manage && <Button kind="primary" onClick={() => setDialog({ kind: "register" })}>Register a runner</Button>}
        {tab === "pools" && can("pools.manage") && <Button kind="primary" onClick={() => setDialog({ kind: "pool", pool: null })}>New runner pool</Button>}
      </header>
      <div className="st-page-tabs" role="tablist" aria-label="Runners">
        {(["runners", "pools"] as const).map((t) => <button key={t} type="button" role="tab" className="st-page-tab" aria-selected={tab === t} onClick={() => { setTab(t); setSelected([]); }}>
          {t === "runners" ? "Runners" : "Runner pools"}{runners && <span className="cc-count">{t === "runners" ? runners.length : pools.length}</span>}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {tab === "runners" ? <>
        <div className="cc-filters" role="group" aria-label="Filters">
          <Select aria-label="Search in" value={searchIn} onChange={(e) => { setSearchIn(e.target.value); setOffset(0); }} options={SEARCH_IN.map(([value, label]) => ({ value, label }))} />
          <TextInput type="search" aria-label="Search runners" placeholder="Search" value={f.q ?? ""} onChange={(e) => setFilter("q", e.target.value)} />
          <Select aria-label="Status" value={f.status ?? ""} onChange={(e) => setFilter("status", e.target.value)}
            options={[{ value: "", label: "Any status" }, ...Object.entries(STATUS).map(([value, label]) => ({ value, label }))]} />
          {pools.length > 0 && <Select aria-label="Runner pool" value={f.pool ?? ""} onChange={(e) => setFilter("pool", e.target.value)}
            options={[{ value: "", label: "Any pool" }, ...pools.map((p) => ({ value: p.id, label: p.name }))]} />}
          {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => { setF({}); setOffset(0); }}>Clear filters</Button>}
        </div>
        <div className="cc-bar" role="toolbar" aria-label="Runner actions">
          {chosen.length > 0 && <span className="cc-chosen">{chosen.length} selected</span>}
          {manage && <>
            <Button kind="secondary" disabled={!chosen.length} onClick={() => setDialog({ kind: "settings" })}>Update settings</Button>
            <Button kind="secondary" disabled={!chosen.length} onClick={() => each("Program update asked for on", (d) => api.updateRunnerProgram([d.id]))}>Update program</Button>
            <Button kind="secondary" disabled={!chosen.some((d) => d.enabled)} onClick={() => each("Took out of service", (d) => api.patchDevice(d.id, { enabled: false }))}>Take out of service</Button>
            <Button kind="secondary" disabled={!chosen.some((d) => !d.enabled)} onClick={() => each("Returned to service", (d) => api.patchDevice(d.id, { enabled: true }))}>Return to service</Button>
            <Button kind="danger" disabled={!chosen.length} onClick={() => setDialog({ kind: "delete" })}>Delete</Button>
          </>}
          {can("pools.manage") && <Button kind="secondary" disabled={!chosen.length} onClick={() => setDialog({ kind: "pool", pool: null, members: chosen.map((d) => d.id) })}>Make a pool</Button>}
          <span className="st-bar-fill" />
          <Button kind="plain" onClick={load}>Refresh</Button>
          <Button kind="plain" onClick={() => setDialog({ kind: "columns" })}>Columns</Button>
          <Button kind="secondary" onClick={exportCsv}>{chosen.length ? "Export selected" : "Export CSV"}</Button>
        </div>
        <div className="cc-table-wrap">
          <table className="st-table cc-table" aria-label="Runners list">
            <thead><tr>
              <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={page.length > 0 && page.every((d) => selected.includes(d.id))}
                onChange={(e) => setSelected(e.target.checked ? page.map((d) => d.id) : [])} /></th>
              {cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}
            </tr></thead>
            <tbody>
              {page.map((d) => (
                <tr key={d.id} className={selected.includes(d.id) ? "is-selected" : ""} onClick={(e) => { if (!(e.target as HTMLElement).closest("input")) setDialog({ kind: "details", id: d.id }); }}>
                  <td className="cc-check"><input type="checkbox" aria-label={"Select " + d.name} checked={selected.includes(d.id)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, d.id] : selected.filter((x) => x !== d.id))} /></td>
                  {cols.map((c) => <td key={c.key} className={c.key === "status" ? "cc-status is-" + d.status : undefined}>{c.cell(d, pools)}</td>)}
                </tr>
              ))}
              {runners && !shown.length && <tr><td colSpan={cols.length + 1} className="st-muted">{runners.length ? "No runners match these filters." : "No runners yet. Register one, or start ooa server on this computer."}</td></tr>}
              {!runners && !error && <tr><td colSpan={cols.length + 1} className="st-muted" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {shown.length > PAGE && (
          <div className="cc-pager">
            <Button kind="plain" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</Button>
            <span>{offset + 1}–{Math.min(offset + PAGE, shown.length)} of {shown.length}</span>
            <Button kind="plain" disabled={offset + PAGE >= shown.length} onClick={() => setOffset(offset + PAGE)}>Next</Button>
          </div>
        )}
      </> : <Pools pools={pools} runners={runners ?? []} canManage={can("pools.manage")} onOpen={(p) => setDialog({ kind: "pool", pool: p })} />}

      {dialog?.kind === "columns" && (
        <Dialog open title="Columns" width={360} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {COLS.map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)} disabled={c.key === "name"}
              onChange={(e) => setHidden(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
          </div>
        </Dialog>
      )}
      {dialog?.kind === "register" && <RegisterRunner api={api} onClose={() => setDialog(null)} />}
      {dialog?.kind === "settings" && <RunnerSettings runners={chosen} onClose={() => setDialog(null)}
        onSave={async (changes) => { const r = await api.updateDevices(chosen.map((d) => d.id), changes); onNotice("Updated " + r.updated + " runner" + (r.updated === 1 ? "" : "s") + "."); setDialog(null); setSelected([]); load(); }} />}
      {dialog?.kind === "delete" && (
        <Dialog open title="Delete runners" width={420} onClose={() => setDialog(null)} footer={<>
          <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button>
          <Button kind="danger" onClick={() => { setDialog(null); each("Deleted", (d) => api.deleteDevice(d.id)); }}>Delete</Button></>}>
          <p>Delete {chosen.map((d) => d.name).join(", ")}? A deleted runner must be registered again before it gets work. Runners with work waiting or running, or used by a schedule or trigger, are kept.</p>
        </Dialog>
      )}
      {dialog?.kind === "details" && (() => { const d = runners?.find((x) => x.id === dialog.id); return d &&
        <RunnerDetails api={api} runner={d} pools={pools} manage={manage} onClose={() => setDialog(null)} onChanged={(t) => { onNotice(t); load(); }} />; })()}
      {dialog?.kind === "pool" && <PoolDialog api={api} pool={dialog.pool} members={dialog.members} runners={runners ?? []} onClose={() => setDialog(null)} onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
    </section>
  );
}

function Pools({ pools, runners, canManage, onOpen }: { pools: RunnerPool[]; runners: Runner[]; canManage: boolean; onOpen: (p: RunnerPool) => void }) {
  const nameOf = (id: string) => runners.find((d) => d.id === id)?.name ?? id;
  return (
    <div className="cc-table-wrap">
      <table className="st-table cc-table" aria-label="Runner pools list">
        <thead><tr><th scope="col">Pool</th><th scope="col">Description</th><th scope="col">Runners</th><th scope="col">Online</th><th scope="col">Created by</th></tr></thead>
        <tbody>
          {pools.map((p) => (
            <tr key={p.id} onClick={() => canManage && onOpen(p)} className={canManage ? undefined : "is-static"}>
              <td>{p.name}</td><td>{p.description ?? ""}</td><td>{p.devices.map(nameOf).join(", ")}</td>
              <td>{p.devices.filter((id) => runners.find((d) => d.id === id)?.status === "online").length} of {p.devices.length}</td>
              <td>{p.created_by ?? ""}</td>
            </tr>
          ))}
          {!pools.length && <tr><td colSpan={5} className="st-muted">No runner pools yet. A pool shares work between runners: a run sent to it goes to its least busy online runner.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/** A runner's details by topic, as AA's device page (General, sessions, logs, program), with its own actions. */
function RunnerDetails({ api, runner: d, pools, manage, onClose, onChanged }:
  { api: CrApi; runner: Runner; pools: RunnerPool[]; manage: boolean; onClose: () => void; onChanged: (t: string) => void }) {
  const [name, setName] = useState(d.name);
  const [error, setError] = useState<string | null>(null);
  const run = async (fn: () => Promise<unknown>, done: string) => { try { await fn(); setError(null); onChanged(done); } catch (e) { setError(message(e)); } };
  return (
    <Dialog open title={"Runner " + d.name} width={640} onClose={onClose} footer={manage ? <>
      <Button kind="secondary" onClick={() => run(() => api.patchDevice(d.id, { enabled: !d.enabled }), (d.enabled ? "Took " : "Returned ") + d.name + (d.enabled ? " out of service." : " to service."))}>
        {d.enabled ? "Take out of service" : "Return to service"}</Button>
      {d.up_to_date === false && !d.update_requested && <Button kind="secondary" onClick={() => run(() => api.updateRunnerProgram([d.id]), d.name + " updates its program when it is idle.")}>Update program</Button>}
    </> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <h3 className="cc-log-title">General</h3>
      <dl className="cc-facts">
        <dt>Name</dt><dd>{manage ? <span className="cc-inline">
          <TextInput aria-label="Runner name" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
          <Button kind="plain" disabled={!name.trim() || name === d.name} onClick={() => run(() => api.patchDevice(d.id, { name: name.trim() }), "Renamed to " + name.trim() + ".")}>Rename</Button></span> : d.name}</dd>
        <dt>Status</dt><dd className={"cc-status is-" + d.status}>{STATUS[d.status]}{d.status !== "disabled" && d.last_seen ? " · last seen " + ago(d.last_seen) : ""}</dd>
        <dt>Computer</dt><dd>{d.hostname ?? ""}</dd>
        <dt>Platform</dt><dd>{d.os ?? ""}</dd>
        <dt>Runner pools</dt><dd>{poolsOf(d, pools) || "None"}</dd>
        <dt>Lifespan</dt><dd>{d.lifespan === "temporary" ? "Temporary: removed by itself after a time without contact (Settings)" : "Persistent"}</dd>
        <dt>Registered</dt><dd>{when(d.registered)}{d.registered_by ? " by " + d.registered_by : ""}</dd>
        <dt>Runner id</dt><dd className="ui-mono">{d.id}</dd>
      </dl>
      <h3 className="cc-log-title">Sessions</h3>
      <dl className="cc-facts">
        <dt>Runner type</dt><dd>{TYPE[d.device_type ?? "multi_user"]}</dd>
        <dt>Default users</dt><dd>{(d.default_users ?? []).join(", ") || "None"}</dd>
        <dt>Screen</dt><dd>{SCREEN[d.session_state ?? ""] ?? "Not reported yet"}</dd>
        <dt>After a run</dt><dd>{AFTER_RUN[d.after_run ?? "nothing"]}</dd>
        <dt>Running now</dt><dd>{d.running ?? 0}</dd>
      </dl>
      <h3 className="cc-log-title">Logs and program</h3>
      <dl className="cc-facts">
        <dt>Log level</dt><dd>{d.log_level ?? "info"}</dd>
        <dt>Version</dt><dd>{d.agent_version ?? ""}{d.version_status && d.version_status !== "ok" ? " (newer than the Command Center: it gets no work until the Command Center is updated)" : ""}</dd>
        <dt>Program</dt><dd>{program(d) || "No update information"}</dd>
        <dt>Toolkits</dt><dd>{d.toolkits ? d.toolkits.split(",").length + " installed" : "None reported"}</dd>
      </dl>
    </Dialog>
  );
}

/** Settings for the selected runners; only what is chosen changes. */
function RunnerSettings({ runners, onClose, onSave }: { runners: Runner[]; onClose: () => void; onSave: (c: { log_level?: string; device_type?: string; after_run?: string }) => Promise<void> }) {
  const [c, setC] = useState<{ log_level?: string; device_type?: string; after_run?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const keep = { value: "", label: "Keep as it is" };
  const set = (k: keyof typeof c, v: string) => setC({ ...c, [k]: v || undefined });
  return (
    <Dialog open title={"Update " + runners.length + " runner" + (runners.length === 1 ? "" : "s")} width={480} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!Object.values(c).some(Boolean)} onClick={() => onSave(c).catch((e) => setError(message(e)))}>Save</Button></>}>
      <p className="st-muted">{runners.map((d) => d.name).join(", ")}. Runners pick up a new log level with their next check-in.</p>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Log level">{(id) => <Select id={id} value={c.log_level ?? ""} onChange={(e) => set("log_level", e.target.value)} options={[keep, ...LOG_LEVELS.map((v) => ({ value: v, label: v }))]} />}</Field>
      <Field label="Runner type">{(id) => <Select id={id} value={c.device_type ?? ""} onChange={(e) => set("device_type", e.target.value)}
        options={[keep, { value: "single_user", label: "Single-user: one person's default runner" }, { value: "multi_user", label: "Multi-user: the default runner of several people" }]} />}</Field>
      <Field label="After an unattended run">{(id) => <Select id={id} value={c.after_run ?? ""} onChange={(e) => set("after_run", e.target.value)}
        options={[keep, ...Object.entries(AFTER_RUN).map(([value, label]) => ({ value, label }))]} />}</Field>
    </Dialog>
  );
}

/** A one-time key and the commands that register a runner with it (installed copy, MSI, setup package). */
function RegisterRunner({ api, onClose }: { api: CrApi; onClose: () => void }) {
  const [temporary, setTemporary] = useState(false);
  const [key, setKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const url = location.origin;
  return (
    <Dialog open title="Register a runner" width={640} onClose={onClose} footer={key ? <Button kind="primary" onClick={onClose}>Done</Button> : <>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" onClick={() => api.registrationKey(temporary).then((k) => setKey(k.key), (e) => setError(message(e)))}>Create key</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!key ? <>
        <p>Creates a one-time key, valid for 24 hours, and shows the command to run on the runner machine.</p>
        <label className="st-check-line"><input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} /> Temporary runner (VDI or pooled desktop): removed by itself after a time without contact, freeing its seat</label>
      </> : <div role="region" aria-label="Registration commands">
        <p>On the runner machine, run one of these. The key works once, expires in 24 hours and is shown only now.</p>
        <h3 className="cc-log-title">Already installed (in its folder)</h3>
        <code className="cc-key">{"bin\\ooa.bat agent register --url " + url + " --key " + key}</code>
        <h3 className="cc-log-title">With the MSI (runs as a Windows service)</h3>
        <code className="cc-key">{"msiexec /i ooa.msi /qn ROLE=Runner URL=" + url + " REGISTRATION_KEY=" + key}</code>
        <h3 className="cc-log-title">With the setup package</h3>
        <code className="cc-key">{"Install.cmd -Silent -Role Runner -Url " + url + " -RegistrationKey " + key}</code>
      </div>}
    </Dialog>
  );
}

function PoolDialog({ api, pool, members: initial, runners, onClose, onSaved }: { api: CrApi; pool: RunnerPool | null; members?: string[]; runners: Runner[]; onClose: () => void; onSaved: (t: string) => void }) {
  const [name, setName] = useState(pool?.name ?? "");
  const [description, setDescription] = useState(pool?.description ?? "");
  const [members, setMembers] = useState<string[]>(pool?.devices ?? initial ?? []);
  const [error, setError] = useState<string | null>(null);
  const save = () => api.savePool(pool?.id ?? null, { name: name.trim(), description, devices: members }).then(() => onSaved("Saved runner pool " + name.trim() + "."), (e) => setError(message(e)));
  const remove = () => api.deletePool(pool!.id).then(() => onSaved("Deleted runner pool " + pool!.name + "."), (e) => setError(message(e)));
  return (
    <Dialog open title={pool ? "Runner pool " + pool.name : "New runner pool"} width={520} onClose={onClose} footer={<>
      {pool && <Button kind="danger" onClick={remove}>Delete pool</Button>}
      <span className="st-bar-fill" />
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!name.trim()} onClick={save}>Save</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Name">{(id) => <TextInput id={id} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Description">{(id) => <TextArea id={id} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <div className="cc-cols" role="group" aria-label="Runners in the pool">
        {runners.map((d) => <label key={d.id} className="st-check-line"><input type="checkbox" checked={members.includes(d.id)}
          onChange={(e) => setMembers(e.target.checked ? [...members, d.id] : members.filter((x) => x !== d.id))} /> {d.name} <span className="st-muted">({STATUS[d.status]})</span></label>)}
        {!runners.length && <p className="st-muted">No runners registered yet.</p>}
      </div>
    </Dialog>
  );
}
