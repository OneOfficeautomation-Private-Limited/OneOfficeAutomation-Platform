import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrRole } from "../api/cr";
import { Button, Dialog, HelpButton, Select, TextInput, usePersistent, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { roleName } from "./roleNames";

/**
 * Administration > Roles, built as AA's Roles page (checked against AA's docs, UiPath's roles and Blue Prism's user
 * roles, 31 Oct 2026): the roles with their kind, permissions and holders; create, copy (UiPath's clone), delete
 * selected, export. A role opens as a permission matrix grouped by area, as Blue Prism's tree and UiPath's View /
 * Run / Manage columns, with the people who hold it. Built-in roles are read-only; copy one to change it.
 * Laid out as the design plan's list pattern and permission matrix (step 4, approved on the canvas 31 Oct 2026):
 * a lock and a one-line summary on built-in roles, a permission bar, a ⋯ menu per row; the matrix with an icon per
 * area, a count strip on top (ticked, by kind, high risk), Find a permission, All / None per area, and who holds it.
 * Review round 2 (1 Nov 2026): a role opens as its own page (Permissions; Held by, each person opening in Users), and
 * deleting asks first in a bar, from the list and from the page.
 */
type Col = { key: string; label: string; cell: (r: CrRole, total: number) => string };

/** Each permission in plain words, by area and kind (see, run, change, administer). */
type Kind = "see" | "run" | "change" | "admin";
const PERMS: Record<string, { area: string; kind: Kind; label: string }> = {
  "repo.view": { area: "Agents", kind: "see", label: "See agents" },
  "repo.checkin": { area: "Agents", kind: "change", label: "Check in agents" },
  "repo.delete": { area: "Agents", kind: "change", label: "Delete agents" },
  "repo.production": { area: "Agents", kind: "admin", label: "Put versions in production" },
  "repo.approve": { area: "Agents", kind: "admin", label: "Approve versions for production" },
  "repo.permissions": { area: "Agents", kind: "admin", label: "Set folder permissions" },
  "lifecycle.export": { area: "Agents", kind: "admin", label: "Export bundles" },
  "lifecycle.import": { area: "Agents", kind: "admin", label: "Import bundles" },
  "runs.view": { area: "Runs", kind: "see", label: "See runs" },
  "runs.start": { area: "Runs", kind: "run", label: "Start runs" },
  "runs.stop": { area: "Runs", kind: "run", label: "Pause and stop runs" },
  "devices.view": { area: "Runner Machines", kind: "see", label: "See runners" },
  "devices.manage": { area: "Runner Machines", kind: "admin", label: "Manage runners" },
  "pools.manage": { area: "Runner Machines", kind: "admin", label: "Manage runner pools" },
  "locks.manage": { area: "Runner Machines", kind: "admin", label: "Release environment locks" },
  "knowledge.manage": { area: "AI", kind: "change", label: "Manage knowledge bases" },
  "schedules.view": { area: "Schedules and triggers", kind: "see", label: "See schedules" },
  "schedules.manage": { area: "Schedules and triggers", kind: "change", label: "Manage schedules" },
  "triggers.view": { area: "Schedules and triggers", kind: "see", label: "See triggers" },
  "triggers.manage": { area: "Schedules and triggers", kind: "change", label: "Manage triggers" },
  "queues.view": { area: "Work queues", kind: "see", label: "See work queues" },
  "queues.work": { area: "Work queues", kind: "run", label: "Work on items" },
  "queues.manage": { area: "Work queues", kind: "change", label: "Manage work queues" },
  "queues.all": { area: "Work queues", kind: "admin", label: "Every work queue" },
  "globals.view": { area: "Shared values and vault", kind: "see", label: "See shared global values" },
  "globals.manage": { area: "Shared values and vault", kind: "admin", label: "Manage shared global values" },
  "lockers.own": { area: "Shared values and vault", kind: "change", label: "Own vault folders" },
  "lockers.manage": { area: "Shared values and vault", kind: "admin", label: "Manage the credential vault" },
  "apitasks.manage": { area: "Agent APIs", kind: "change", label: "Manage Agent APIs" },
  "analytics.view": { area: "Insights", kind: "see", label: "See operational insights" },
  "alerts.manage": { area: "Insights", kind: "admin", label: "Manage alert rules" },
  "insights.design": { area: "Insights", kind: "admin", label: "Make and share dashboards" },
  "insights.admin": { area: "Insights", kind: "admin", label: "Set savings and the yearly goal" },
  "skills.view": { area: "AI", kind: "see", label: "See prompt skills" },
  "skills.manage": { area: "AI", kind: "change", label: "Manage own prompt skills" },
  "skills.all": { area: "AI", kind: "admin", label: "Every prompt skill" },
  "connections.manage": { area: "AI", kind: "admin", label: "Manage model connections" },
  "toolkits.manage": { area: "Administration", kind: "admin", label: "Manage agent toolkits" },
  "teams.manage": { area: "Administration", kind: "admin", label: "Manage teams" },
  "users.manage": { area: "Administration", kind: "admin", label: "Manage users and roles" },
  "audit.view": { area: "Administration", kind: "admin", label: "See the audit trail" },
  "settings.manage": { area: "Administration", kind: "admin", label: "Change settings" },
};
/** Permissions that reach people, secrets or the whole system: marked in the matrix (as AA marks its high-risk ones). */
const HIGH_RISK = new Set(["users.manage", "settings.manage", "lockers.manage", "repo.production", "repo.permissions", "lifecycle.import", "devices.manage"]);
const KINDS: [Kind, string][] = [["see", "See"], ["run", "Run"], ["change", "Change"], ["admin", "Administer"]];
/** The icon tile of each area in the matrix: a short mark on the area's colour (white text at least 4.5:1). */
const AREA_LOOK: Record<string, [string, string]> = {
  Agents: ["A", "#2f6fde"], Runs: ["R", "#0f766e"], "Runner Machines": ["M", "#0e7490"], "Schedules and triggers": ["S", "#b45309"],
  "Work queues": ["Q", "#7c3aed"], "Shared values and vault": ["V", "#be185d"], "Agent APIs": ["P", "#1d4ed8"], Insights: ["I", "#0369a1"],
  AI: ["AI", "#6d28d9"], Administration: ["Ad", "#475569"],
};
const infoOf = (p: string) => PERMS[p] ?? { area: "Other", kind: "admin" as Kind, label: p };
/** Permissions in a steady order (by area as in AREA_LOOK, then as listed in PERMS), whatever order the server sends. */
const AREA_ORDER = Object.keys(AREA_LOOK);
const PERM_ORDER = Object.keys(PERMS);
const rank = (list: string[], v: string) => { const i = list.indexOf(v); return i < 0 ? list.length : i; };
const byOrder = (a: string, b: string) => rank(AREA_ORDER, infoOf(a).area) - rank(AREA_ORDER, infoOf(b).area) || rank(PERM_ORDER, a) - rank(PERM_ORDER, b) || a.localeCompare(b);
/** What each built-in role is for, in one line. */
const BUILTIN_FOR: Record<string, string> = {
  admin: "Everything, including users, settings and the vault",
  bot_creator: "Builds, checks in and runs agents; schedules, triggers and queues",
  bot_runner: "Runs agents and works on queue items",
  viewer: "Sees agents, runs and insights; changes nothing",
};
const kindOf = (r: CrRole) => r.builtin ? (r.switched_off ? "Switched off" : "Built-in") : "Custom";
const when = (t?: string | null) => t ? new Date(t).toLocaleDateString() : "";

const COLS: Col[] = [
  { key: "name", label: "Role", cell: (r) => roleName(r.name) },
  { key: "kind", label: "Kind", cell: (r) => kindOf(r) },
  { key: "description", label: "What it is for", cell: (r) => r.builtin ? BUILTIN_FOR[r.name] ?? "" : r.description ?? "" },
  { key: "perms", label: "Permissions", cell: (r, total) => r.permissions.length + " of " + total },
  { key: "users", label: "Users", cell: (r) => String(r.users ?? 0) },
  { key: "created", label: "Created", cell: (r) => r.builtin ? "" : when(r.created) + (r.created_by ? " by " + r.created_by : "") },
];
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const LOCK = <svg aria-hidden="true" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>;

const HELP: HelpTopic = { title: "Roles",
  about: "What each role lets people see, run, change and administer. A person has every permission of each of their roles; click a role to open its own "
    + "page.",
  items: [
    { name: "Create role", text: "Opens a new role's page: give it a name, say what it is for, and tick its permissions, or start from an existing role's." },
    { name: "Kind", text: "Built-in roles come with One CommandCenter and cannot be changed or deleted; custom roles are your own. A built-in role switched "
      + "off in Settings grants nothing." },
    { name: "Grants", text: "Shows only the roles that give one chosen permission, for example to find every role that may change settings." },
    { name: "Permissions", text: "How many of all permissions the role gives, with a bar to compare roles at a glance." },
    { name: "Copy as a new role", text: "Makes a new role with the same permissions, which you can then change. This is the way to adjust a built-in role." },
    { name: "Delete…", text: "Deletes the selected custom roles after you confirm. A role someone still holds is kept; give those people another role "
      + "first." },
    { name: "Columns", text: "Chooses which columns the list shows." },
    { name: "Export CSV", text: "Saves the roles shown, or only the selected ones, with their permissions and holders as a CSV file." },
  ] };

const HELP_ROLE: HelpTopic = { title: "Role",
  about: "One role: its permissions as a table by area and kind, and the people who hold it. Built-in roles are read-only; copy one to make your own.",
  items: [
    { name: "Name and What it is for", text: "The name is lower-case letters, digits and _, 3 to 40 long, and cannot change once the role is made. The "
      + "description shows in the list." },
    { name: "Start from", text: "For a new role: ticks the same permissions as an existing role, so you only change what differs." },
    { name: "Permissions", text: "One row per area and one column per kind: See, Run, Change and Administer. Tick what the role may do; All or None ticks a "
      + "whole area." },
    { name: "high risk", text: "Marks permissions that reach people, secrets or the whole system, such as managing users, settings or the vault. Give them "
      + "only to people who need them." },
    { name: "Find a permission and Only ticked", text: "Narrow the table to permissions whose name matches, or to those the role already gives." },
    { name: "Held by", text: "The people who have this role. Click a person to open them in Users." },
    { name: "Save changes", text: "Saves the role; people who hold it get the new permissions." },
    { name: "Copy as a new role", text: "Makes a new role from this one's permissions." },
  ] };

export function Roles({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [hidden, setHidden] = usePersistent<string[]>("roles.hidden", ["created"]);
  const [data, setData] = useState<{ roles: CrRole[]; permissions: string[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "columns" }>(null);
  const [page, setPage] = useState<null | { role: CrRole | null; copyOf?: CrRole }>(null);   // a role's own page, in place of the list
  const [asking, setAsking] = useState(false);   // deleting the chosen roles, asked first

  const load = useCallback(async () => {
    try { const d = await api.rolesPage(); setData(d); setError(null); setSelected((s) => s.filter((n) => d.roles.some((r) => r.name === n && !r.builtin))); }
    catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);

  const total = data?.permissions.length ?? 0;
  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    return (data?.roles ?? []).filter((r) => (!text || (r.name + " " + roleName(r.name) + " " + (r.description ?? "")).toLowerCase().includes(text))
      && (!f.kind || (f.kind === "builtin") === r.builtin) && (!f.perm || r.permissions.includes(f.perm)));
  }, [data, f]);
  const chosen = (data?.roles ?? []).filter((r) => selected.includes(r.name));
  const cols = COLS.filter((c) => !hidden.includes(c.key));
  const filtered = Object.values(f).some(Boolean);
  const builtins = (data?.roles ?? []).filter((r) => r.builtin).length;
  const exportCsv = () => {
    const rows = chosen.length ? chosen : shown;
    const text = [[...COLS.map((c) => c.label), "Permission list", "Holders"], ...rows.map((r) => [...COLS.map((c) => c.cell(r, total)), r.permissions.join(" "), (r.members ?? []).join(" ")])]
      .map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "roles.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + rows.length + " role" + (rows.length === 1 ? "" : "s") + ".");
  };
  const removeChosen = async () => {
    let done = 0, failed: string | null = null;
    for (const r of chosen) { try { await api.deleteRole(r.name); done++; } catch (e) { failed ??= r.name + ": " + message(e); } }
    onNotice("Deleted " + done + " role" + (done === 1 ? "" : "s") + ".");
    setError(failed); setSelected([]); load();
  };
  const rowMenu = (r: CrRole) => [
    { key: "open", label: "Open", onSelect: () => setPage({ role: r }) },
    { key: "copy", label: "Copy as a new role", onSelect: () => setPage({ role: null, copyOf: r }) },
    { key: "delete", label: r.builtin ? "Delete (built-in roles stay)" : "Delete…", disabled: r.builtin, separatorBefore: true,
      onSelect: () => { setSelected([r.name]); setAsking(true); } },
  ];
  const cell = (c: Col, r: CrRole) => {
    if (c.key === "name") return <strong>{roleName(r.name)}</strong>;
    if (c.key === "kind") return <span className={"cc-kind is-" + (r.builtin ? (r.switched_off ? "off" : "builtin") : "custom")}>{kindOf(r)}</span>;
    if (c.key === "perms") return <span className="cc-perm"><span className="cc-perm-bar" aria-hidden="true"><span style={{ width: (total ? Math.round(100 * r.permissions.length / total) : 0) + "%" }} /></span>
      {r.permissions.length} of {total}</span>;
    const v = c.cell(r, total);
    return v || <span className="cc-none">–</span>;
  };

  const askBar = asking && chosen.length > 0 && <div className="cc-list-bulk" role="alert">
    <span>Delete {chosen.map((r) => roleName(r.name)).join(", ")}? Roles someone still holds are kept: give those people another role first.</span>
    <Button kind="danger" onClick={() => { setAsking(false); removeChosen().then(() => setPage(null)); }}>Delete</Button>
    <Button kind="plain" onClick={() => { setAsking(false); if (page) setSelected([]); }}>Cancel</Button></div>;
  if (page && data) return (
    <section className="st-page cc-home cc-explorer" aria-label="Role">
      {askBar}
      <RolePage key={page.role?.name ?? "new:" + (page.copyOf?.name ?? "")} api={api} role={page.role} copyOf={page.copyOf} all={data.permissions} roles={data.roles}
        onClose={() => { setPage(null); setAsking(false); }} onCopy={(r) => setPage({ role: null, copyOf: r })} onDelete={(r) => { setSelected([r.name]); setAsking(true); }}
        onSaved={(t) => { onNotice(t); setPage(null); load(); }} />
    </section>
  );
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-roles-title">
      <header className="cc-list-head">
        <h1 id="cc-roles-title">Roles</h1>
        <HelpButton topic={HELP} />
        {data && <span className="cc-list-count">{builtins} built-in · {data.roles.length - builtins} custom</span>}
        <span className="ov-fill" />
        <Button kind="primary" onClick={() => setPage({ role: null })}>Create role</Button>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search roles" placeholder="Search roles" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} /></span>
          <span className={"cc-chip" + (f.kind ? " is-on" : "")}><Select aria-label="Kind" value={f.kind ?? ""} onChange={(e) => setF({ ...f, kind: e.target.value })}
            options={[{ value: "", label: "Kind: Any" }, { value: "builtin", label: "Kind: Built-in" }, { value: "custom", label: "Kind: Custom" }]} /></span>
          {data && <span className={"cc-chip" + (f.perm ? " is-on" : "")}><Select aria-label="Grants" value={f.perm ?? ""} onChange={(e) => setF({ ...f, perm: e.target.value })}
            options={[{ value: "", label: "Grants: Any permission" }, ...[...data.permissions].sort(byOrder).map((p) => ({ value: p, label: "Grants: " + infoOf(p).area + ", " + infoOf(p).label }))]} /></span>}
          {filtered && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Roles list tools">
          <Button kind="plain" onClick={() => setDialog({ kind: "columns" })}>Columns</Button>
          <Button kind="plain" onClick={exportCsv}>{chosen.length ? "Export selected" : "Export CSV"}</Button>
          <RefreshButton onClick={load} />
        </div>
      </div>
      <div className="cc-list-panel">
        {askBar}
        {chosen.length > 0 && !asking && <div className="cc-list-bulk" role="toolbar" aria-label="Role actions">
          <strong>{chosen.length} selected</strong>
          {chosen.length === 1 && <Button kind="secondary" onClick={() => setPage({ role: null, copyOf: chosen[0] })}>Copy</Button>}
          <Button kind="danger" onClick={() => setAsking(true)}>Delete</Button>
          <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
        </div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Roles list">
            <thead><tr>
              <th scope="col" className="cc-check"><span className="st-sr">Select</span></th>
              {cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>
            </tr></thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.name} className={selected.includes(r.name) ? "is-selected" : ""} onClick={rowClick(() => setPage({ role: r }))}>
                  <td className="cc-check">{r.builtin ? <span className="cc-lock" role="img" aria-label="Built-in" title="Built-in roles cannot be changed or deleted">{LOCK}</span>
                    : <input type="checkbox" aria-label={"Select " + r.name} checked={selected.includes(r.name)}
                      onChange={(e) => setSelected(e.target.checked ? [...selected, r.name] : selected.filter((x) => x !== r.name))} />}</td>
                  {cols.map((c) => <td key={c.key}>{cell(c, r)}</td>)}
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + roleName(r.name)} items={rowMenu(r)} /></td>
                </tr>
              ))}
              {data && !shown.length && <tr className="is-static"><td colSpan={cols.length + 2} className="cc-none">No roles match these filters.</td></tr>}
              {!data && !error && <tr className="is-static"><td colSpan={cols.length + 2} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {data && <div className="cc-list-pager"><span>{shown.length} of {data.roles.length}{filtered ? " matching" : ""}</span></div>}
      </div>
      <p className="cc-list-hint">Built-in roles cannot be changed: copy one to make your own. They can be switched off in <a href="#/settings">Settings</a>, where single sign-on groups map to roles too.</p>

      {dialog?.kind === "columns" && (
        <Dialog open title="Columns" width={360} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {COLS.map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)} disabled={c.key === "name"}
              onChange={(e) => setHidden(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
          </div>
        </Dialog>
      )}
    </section>
  );
}

/**
 * A role's own page: its permissions as a matrix (one row per area with its icon and how many are ticked, one column per
 * kind: see, run, change, administer; All / None per area) and the people who hold it. Built-in roles are read-only,
 * with Copy as a new role.
 */
function RolePage({ api, role, copyOf, all, roles, onClose, onCopy, onDelete, onSaved }:
  { api: CrApi; role: CrRole | null; copyOf?: CrRole; all: string[]; roles: CrRole[]; onClose: () => void; onCopy: (r: CrRole) => void;
    onDelete: (r: CrRole) => void; onSaved: (t: string) => void }) {
  const readOnly = !!role?.builtin;
  const [tab, setTab] = useState<"perms" | "held">("perms");
  const [name, setName] = useState(role?.name ?? (copyOf ? (copyOf.name + "_copy").replace(/[^a-z0-9_]/g, "_").slice(0, 40) : ""));
  const [description, setDescription] = useState(role?.description ?? (copyOf ? "Copy of " + roleName(copyOf.name) : ""));
  const [perms, setPerms] = useState<string[]>(role?.permissions ?? copyOf?.permissions ?? []);
  const [from, setFrom] = useState(copyOf?.name ?? "");
  const [find, setFind] = useState("");
  const [onlyTicked, setOnlyTicked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const areas = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const p of [...all].sort(byOrder)) { const a = infoOf(p).area; m.set(a, [...(m.get(a) ?? []), p]); }
    return [...m.entries()];
  }, [all]);
  const text = find.trim().toLowerCase();
  const visible = (p: string) => (!text || (infoOf(p).label + " " + infoOf(p).area + " " + p).toLowerCase().includes(text)) && (!onlyTicked || perms.includes(p));
  const toggle = (p: string, on: boolean) => setPerms(on ? [...perms, p] : perms.filter((x) => x !== p));
  const risky = perms.filter((p) => HIGH_RISK.has(p)).length;
  const save = () => api.saveRole(name.trim(), description, perms).then(() => onSaved((role ? "Saved" : "Created") + " the role " + name.trim() + "."), (e) => setError(message(e)));
  const title = role ? (readOnly ? "Built-in role " + roleName(role.name) : "Role " + role.name) : "Create role";
  const members = role?.members ?? [];
  const close = <button type="button" className="cc-list-icon" aria-label="Close" title="Back to the roles" onClick={onClose}>×</button>;
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={title}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Roles</button> ›</span>
        <h1>{role ? roleName(role.name) : "Create role"}</h1>
        <HelpButton topic={HELP_ROLE} />
        {role && <span className={"cc-kind is-" + (role.builtin ? (role.switched_off ? "off" : "builtin") : "custom")}>{kindOf(role)}</span>}
        <span className="cc-none">{perms.length} of {all.length} permissions{role ? " · " + members.length + (members.length === 1 ? " person" : " people") : ""}</span>
        <span className="ov-fill" />
        {!readOnly && <Button kind="primary" disabled={!/^[a-z][a-z0-9_]{2,39}$/.test(name.trim())} onClick={save}>{role ? "Save changes" : "Create role"}</Button>}
        {role && <Button kind={readOnly ? "primary" : "secondary"} onClick={() => onCopy(role)}>Copy as a new role</Button>}
        {role && !readOnly && <RowMenu label={"More for " + role.name} items={[{ key: "delete", label: "Delete…", onSelect: () => onDelete(role) }]} />}
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {role && <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this role">
        {([["perms", "Permissions"], ["held", "Held by (" + members.length + ")"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>}
      {tab === "perms" && <div className="cc-agent-tab">
        {readOnly && <p className="cc-list-hint">Built-in roles cannot be changed.{role!.switched_off ? " This one is switched off in Settings: it grants nothing." : ""}</p>}
        {!readOnly && <div className="cc-role-fields">
          <label className="cc-role-field">Name<TextInput value={name} disabled={!!role} maxLength={40} placeholder="lower-case letters, digits, _ (3 to 40)" onChange={(e) => setName(e.target.value)} /></label>
          <label className="cc-role-field">What it is for<TextInput value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} /></label>
          {!role && <label className="cc-role-field">Start from<Select value={from} onChange={(e) => { setFrom(e.target.value); setPerms(roles.find((r) => r.name === e.target.value)?.permissions ?? []); }}
            options={[{ value: "", label: "Nothing ticked" }, ...roles.map((r) => ({ value: r.name, label: roleName(r.name) }))]} /></label>}
        </div>}
        <div className="cc-mx-strip" role="status" aria-label="Permissions ticked">
          <span><strong>{perms.length} of {all.length}</strong> permissions</span>
          <span>{KINDS.map(([k, l]) => l + " " + perms.filter((p) => infoOf(p).kind === k).length).join(" · ")}</span>
          <span className={risky ? "cc-mx-risky" : "cc-mx-safe"}>{risky ? risky + " high-risk permission" + (risky === 1 ? "" : "s") : "No high-risk permissions"}</span>
          <span className="ov-fill" />
          <span className="cc-mx-find"><TextInput type="search" aria-label="Find a permission" placeholder="Find a permission" value={find} onChange={(e) => setFind(e.target.value)} /></span>
          <label className="st-check-line"><input type="checkbox" checked={onlyTicked} onChange={(e) => setOnlyTicked(e.target.checked)} /> Only ticked</label>
        </div>
        <table className="cc-mx" aria-label="Permissions">
          <colgroup><col className="cc-mx-area" />{KINDS.map(([k]) => <col key={k} />)}{!readOnly && <col className="cc-mx-all" />}</colgroup>
          <thead><tr><th scope="col">Area</th>{KINDS.map(([k, l]) => <th key={k} scope="col">{l}</th>)}{!readOnly && <th scope="col"><span className="st-sr">All in the area</span></th>}</tr></thead>
          <tbody>
            {areas.filter(([, ps]) => ps.some(visible)).map(([area, ps]) => {
              const n = ps.filter((p) => perms.includes(p)).length;
              const [mark, colour] = AREA_LOOK[area] ?? [area.slice(0, 1), "#475569"];
              return (
                <tr key={area}>
                  <th scope="row"><span className="cc-mx-ic" style={{ background: colour }} aria-hidden="true">{mark}</span>{area}<span className="cc-mx-ct">{n} of {ps.length}</span></th>
                  {KINDS.map(([k]) => <td key={k}>{ps.filter((p) => infoOf(p).kind === k && visible(p)).map((p) =>
                    <label key={p} className="cc-mx-ln" title={p}><input type="checkbox" checked={perms.includes(p)} disabled={readOnly} onChange={(e) => toggle(p, e.target.checked)} />{infoOf(p).label}
                      {HIGH_RISK.has(p) && <span className="cc-risk" title="High risk: reaches people, secrets or the whole system">high risk</span>}</label>)}</td>)}
                  {!readOnly && <td><span className="cc-mx-seg" role="group" aria-label={"All or none in " + area}>
                    <button type="button" aria-pressed={n === ps.length} onClick={() => setPerms([...new Set([...perms, ...ps])])}>All</button>
                    <button type="button" aria-pressed={n === 0} onClick={() => setPerms(perms.filter((p) => !ps.includes(p)))}>None</button></span></td>}
                </tr>
              );
            })}
            {!areas.some(([, ps]) => ps.some(visible)) && <tr><td colSpan={KINDS.length + 2} className="cc-none">No permission matches.</td></tr>}
          </tbody>
        </table>
        <p className="cc-list-hint">Folder permissions (who may see or run what in each agent folder) are set on the folders in Agent Repository.</p>
      </div>}
      {tab === "held" && <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Held by">
          <thead><tr><th scope="col">Person</th></tr></thead>
          <tbody>{members.map((m) => <tr key={m} title="Open this person in Users" onClick={() => { location.hash = "#/users?user=" + encodeURIComponent(m); }}>
            <td><span className="cc-who"><span className="cc-who-av" aria-hidden="true">{m.slice(0, 2).toUpperCase()}</span>{m}</span></td></tr>)}
            {!members.length && <tr className="is-static"><td className="cc-none">Nobody yet. Give it to people in Users, or map a single sign-on group to it in Settings.</td></tr>}</tbody>
        </table>
      </div>}
    </div>
  );
}
