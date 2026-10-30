import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrRole } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput, usePersistent } from "../components";

/**
 * Administration > Roles, built as AA's Roles page (checked against AA's docs, UiPath's roles and Blue Prism's user
 * roles, 31 Oct 2026): the roles with their kind, permissions and holders; create, copy (UiPath's clone), delete
 * selected, export. A role opens as a permission matrix grouped by area, as Blue Prism's tree and UiPath's View /
 * Run / Manage columns, with the people who hold it. Built-in roles are read-only; copy one to change it.
 */
type Col = { key: string; label: string; cell: (r: CrRole) => string };

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
const infoOf = (p: string) => PERMS[p] ?? { area: "Other", kind: "admin" as Kind, label: p };
const ROLE_NAMES: Record<string, string> = { admin: "Administrator", bot_creator: "Agent builder", bot_runner: "Agent runner", viewer: "Viewer" };
const roleName = (r: string) => ROLE_NAMES[r] ?? r;
const when = (t?: string | null) => t ? new Date(t).toLocaleDateString() : "";

const COLS: Col[] = [
  { key: "name", label: "Role", cell: (r) => roleName(r.name) },
  { key: "kind", label: "Kind", cell: (r) => r.builtin ? (r.switched_off ? "Built-in, switched off" : "Built-in") : "Custom" },
  { key: "description", label: "Description", cell: (r) => r.builtin ? "" : r.description ?? "" },
  { key: "perms", label: "Permissions", cell: (r) => String(r.permissions.length) },
  { key: "users", label: "Users", cell: (r) => String(r.users ?? 0) },
  { key: "created", label: "Created", cell: (r) => r.builtin ? "" : when(r.created) + (r.created_by ? " by " + r.created_by : "") },
];
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Roles({ api, onNotice }: { api: CrApi; onNotice: (t: string) => void }) {
  const [hidden, setHidden] = usePersistent<string[]>("roles.hidden", ["created"]);
  const [data, setData] = useState<{ roles: CrRole[]; permissions: string[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "columns" | "delete" } | { kind: "role"; role: CrRole | null; copyOf?: CrRole }>(null);

  const load = useCallback(async () => {
    try { const d = await api.rolesPage(); setData(d); setError(null); setSelected((s) => s.filter((n) => d.roles.some((r) => r.name === n && !r.builtin))); }
    catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);

  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    return (data?.roles ?? []).filter((r) => (!text || (r.name + " " + roleName(r.name) + " " + (r.description ?? "")).toLowerCase().includes(text))
      && (!f.kind || (f.kind === "builtin") === r.builtin) && (!f.perm || r.permissions.includes(f.perm)));
  }, [data, f]);
  const chosen = (data?.roles ?? []).filter((r) => selected.includes(r.name));
  const cols = COLS.filter((c) => !hidden.includes(c.key));
  const exportCsv = () => {
    const rows = chosen.length ? chosen : shown;
    const text = [[...COLS.map((c) => c.label), "Permission list", "Holders"], ...rows.map((r) => [...COLS.map((c) => c.cell(r)), r.permissions.join(" "), (r.members ?? []).join(" ")])]
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

  return (
    <section className="st-page cc-home" aria-labelledby="cc-roles-title">
      <header className="st-page-head">
        <div><h1 id="cc-roles-title">Roles</h1><p>What each role lets people see, run, change and administer. A person has every permission of each of their roles.</p></div>
        <Button kind="primary" onClick={() => setDialog({ kind: "role", role: null })}>Create role</Button>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-filters" role="group" aria-label="Filters">
        <TextInput type="search" aria-label="Search roles" placeholder="Search roles" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} />
        <Select aria-label="Kind" value={f.kind ?? ""} onChange={(e) => setF({ ...f, kind: e.target.value })}
          options={[{ value: "", label: "Built-in and custom" }, { value: "builtin", label: "Built-in" }, { value: "custom", label: "Custom" }]} />
        {data && <Select aria-label="Grants" value={f.perm ?? ""} onChange={(e) => setF({ ...f, perm: e.target.value })}
          options={[{ value: "", label: "Any permission" }, ...data.permissions.map((p) => ({ value: p, label: infoOf(p).area + ": " + infoOf(p).label }))]} />}
        {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
      </div>
      <div className="cc-bar" role="toolbar" aria-label="Role actions">
        {chosen.length > 0 && <span className="cc-chosen">{chosen.length} selected</span>}
        <Button kind="secondary" disabled={chosen.length !== 1} onClick={() => setDialog({ kind: "role", role: null, copyOf: chosen[0] })}>Copy</Button>
        <Button kind="danger" disabled={!chosen.length} onClick={() => setDialog({ kind: "delete" })}>Delete</Button>
        <span className="st-bar-fill" />
        <Button kind="plain" onClick={load}>Refresh</Button>
        <Button kind="plain" onClick={() => setDialog({ kind: "columns" })}>Columns</Button>
        <Button kind="secondary" onClick={exportCsv}>{chosen.length ? "Export selected" : "Export CSV"}</Button>
      </div>
      <div className="cc-table-wrap">
        <table className="st-table cc-table" aria-label="Roles list">
          <thead><tr><th scope="col" className="cc-check"><span className="st-sr">Select</span></th>{cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}</tr></thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.name} className={selected.includes(r.name) ? "is-selected" : ""} onClick={(e) => { if (!(e.target as HTMLElement).closest("input")) setDialog({ kind: "role", role: r }); }}>
                <td className="cc-check">{r.builtin ? <span className="st-muted" title="Built-in roles cannot be changed or deleted">—</span>
                  : <input type="checkbox" aria-label={"Select " + r.name} checked={selected.includes(r.name)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, r.name] : selected.filter((x) => x !== r.name))} />}</td>
                {cols.map((c) => <td key={c.key}>{c.cell(r)}</td>)}
              </tr>
            ))}
            {data && !shown.length && <tr><td colSpan={cols.length + 1} className="st-muted">No roles match these filters.</td></tr>}
            {!data && !error && <tr><td colSpan={cols.length + 1} className="st-muted" role="status">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="st-muted">To copy a built-in role, open it and choose Copy as a new role. Built-in roles can be switched off in Settings; single sign-on groups map to roles there too.</p>

      {dialog?.kind === "columns" && (
        <Dialog open title="Columns" width={360} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {COLS.map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)} disabled={c.key === "name"}
              onChange={(e) => setHidden(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
          </div>
        </Dialog>
      )}
      {dialog?.kind === "delete" && (
        <Dialog open title="Delete roles" width={440} onClose={() => setDialog(null)} footer={<>
          <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button>
          <Button kind="danger" onClick={() => { setDialog(null); removeChosen(); }}>Delete</Button></>}>
          <p>Delete {chosen.map((r) => r.name).join(", ")}? Roles someone still holds are kept: give those people another role first.</p>
        </Dialog>
      )}
      {dialog?.kind === "role" && data && <RoleEditor key={dialog.role?.name ?? "new:" + (dialog.copyOf?.name ?? "")} api={api} role={dialog.role} copyOf={dialog.copyOf} all={data.permissions}
        onClose={() => setDialog(null)} onCopy={(r) => setDialog({ kind: "role", role: null, copyOf: r })}
        onSaved={(t) => { onNotice(t); setDialog(null); load(); }} />}
    </section>
  );
}

/**
 * A role as a matrix: one row per area, one column per kind (see, run, change, administer), a box per permission.
 * Built-in roles are shown read-only, with Copy as a new role.
 */
function RoleEditor({ api, role, copyOf, all, onClose, onCopy, onSaved }:
  { api: CrApi; role: CrRole | null; copyOf?: CrRole; all: string[]; onClose: () => void; onCopy: (r: CrRole) => void; onSaved: (t: string) => void }) {
  const readOnly = !!role?.builtin;
  const [name, setName] = useState(role?.name ?? (copyOf ? (copyOf.name + "_copy").replace(/[^a-z0-9_]/g, "_").slice(0, 40) : ""));
  const [description, setDescription] = useState(role?.description ?? (copyOf ? "Copy of " + roleName(copyOf.name) : ""));
  const [perms, setPerms] = useState<string[]>(role?.permissions ?? copyOf?.permissions ?? []);
  const [error, setError] = useState<string | null>(null);
  const areas = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const p of all) { const a = infoOf(p).area; m.set(a, [...(m.get(a) ?? []), p]); }
    return [...m.entries()];
  }, [all]);
  const toggle = (p: string, on: boolean) => setPerms(on ? [...perms, p] : perms.filter((x) => x !== p));
  const save = () => api.saveRole(name.trim(), description, perms).then(() => onSaved((role ? "Saved" : "Created") + " the role " + name.trim() + "."), (e) => setError(message(e)));
  const title = role ? (readOnly ? "Built-in role " + roleName(role.name) : "Role " + role.name) : "Create role";
  return (
    <Dialog open title={title} width={860} onClose={onClose} footer={readOnly ? <Button kind="primary" onClick={() => onCopy(role!)}>Copy as a new role</Button> : <>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!/^[a-z][a-z0-9_]{2,39}$/.test(name.trim())} onClick={save}>{role ? "Save changes" : "Create role"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {readOnly && <p className="st-muted">Built-in roles cannot be changed.{role!.switched_off ? " This one is switched off in Settings: it grants nothing." : ""}</p>}
      {!readOnly && <div className="cc-grid2">
        <Field label="Name (3-40 lower-case letters, digits, _)">{(id) => <TextInput id={id} value={name} disabled={!!role} maxLength={40} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description">{(id) => <TextInput id={id} value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />}</Field>
      </div>}
      <table className="st-table cc-matrix" aria-label="Permissions">
        <thead><tr><th scope="col">Area</th>{KINDS.map(([k, l]) => <th key={k} scope="col">{l}</th>)}{!readOnly && <th scope="col"><span className="st-sr">All in the area</span></th>}</tr></thead>
        <tbody>
          {areas.map(([area, ps]) => (
            <tr key={area}>
              <th scope="row">{area}</th>
              {KINDS.map(([k]) => <td key={k}>{ps.filter((p) => infoOf(p).kind === k).map((p) =>
                <label key={p} className="st-check-line" title={p}><input type="checkbox" checked={perms.includes(p)} disabled={readOnly} onChange={(e) => toggle(p, e.target.checked)} /> {infoOf(p).label}{HIGH_RISK.has(p) && <span className="cc-risk" title="High risk: reaches people, secrets or the whole system">high risk</span>}</label>)}</td>)}
              {!readOnly && <td><Button kind="plain" onClick={() => setPerms(ps.every((p) => perms.includes(p)) ? perms.filter((p) => !ps.includes(p)) : [...new Set([...perms, ...ps])])}>
                {ps.every((p) => perms.includes(p)) ? "None" : "All"}</Button></td>}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="st-muted">{perms.length} of {all.length} permissions. Folder permissions (who may see or run what in each agent folder) are set on the folders in Agent Repository.</p>
      {role && <>
        <h3 className="cc-log-title">Held by</h3>
        <p>{(role.members ?? []).length ? role.members!.join(", ") : "Nobody yet."}</p>
      </>}
    </Dialog>
  );
}
