import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrPerson, CrUser, Runner } from "../api/cr";
import { Button, Dialog, Field, Menu, Select, TextArea, TextInput, usePersistent } from "../components";

/**
 * Administration > Users, built as AA's Users page (checked against AA's docs and live Control Room, UiPath's accounts
 * and Blue Prism's user management, 31 Oct 2026): search in a chosen field, status and role filters, a column chooser;
 * for the selected people: enable, disable, delete, export. A person's details open from their row, in AA's order
 * (general details, roles, default runner), with Blue Prism's Unlock and the password and two-step resets. Create
 * user shows the temporary password once; they choose their own at the first sign-in.
 * Laid out as the design plan's list pattern (step 3, approved on the canvas): a title row with a count, one toolbar
 * (search, filter chips, Columns, Export, refresh), Enable / Disable / Delete in a blue bar only while rows are ticked,
 * a compact table with one status pill style and a ⋯ menu per row, and a pager under it.
 */
type Col = { key: string; label: string; cell: (u: CrPerson, runner: string) => string };

/** The status shown: disabled, locked (after failed sign-ins), new password needed, or active. */
const stateOf = (u: CrPerson) => !u.enabled ? "disabled" : u.locked_until && new Date(u.locked_until).getTime() > Date.now() ? "locked" : u.must_change ? "pending" : "active";
const STATUS: Record<string, string> = { active: "Active", pending: "New password needed", locked: "Locked", disabled: "Disabled" };
const ROLE_NAMES: Record<string, string> = { admin: "Administrator", bot_creator: "Agent builder", bot_runner: "Agent runner", viewer: "Viewer" };
const roleName = (r: string) => ROLE_NAMES[r] ?? r;
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
function ago(t?: string | null) {
  if (!t) return "";
  const s = Math.max(0, Math.round((Date.now() - new Date(t).getTime()) / 1000));
  return s < 60 ? "just now" : s < 3600 ? Math.floor(s / 60) + " min ago" : s < 86_400 ? Math.floor(s / 3600) + " h ago" : Math.floor(s / 86_400) + " days ago";
}
const fullName = (u: CrPerson) => [u.first_name, u.last_name].filter(Boolean).join(" ");

const COLS: Col[] = [
  { key: "status", label: "Status", cell: (u) => STATUS[stateOf(u)] },
  { key: "username", label: "User name", cell: (u) => u.username },
  { key: "display", label: "Display name", cell: (u) => u.display_name ?? "" },
  { key: "name", label: "Name", cell: fullName },
  { key: "email", label: "E-mail", cell: (u) => u.email ?? "" },
  { key: "roles", label: "Roles", cell: (u) => (u.roles ?? [u.role]).map(roleName).join(", ") },
  { key: "runner", label: "Default runner", cell: (_u, runner) => runner },
  { key: "mfa", label: "Two-step sign-in", cell: (u) => u.mfa_enabled ? "On" : "Off" },
  { key: "login", label: "Last sign-in", cell: (u) => u.last_login ? ago(u.last_login) : "Never" },
  { key: "created", label: "Created", cell: (u) => when(u.created) },
  { key: "description", label: "Description", cell: (u) => u.description ?? "" },
];
/** The fields the search box can look in, as AA's search chooser. */
const SEARCH_IN: [string, string][] = [["any", "Any field"], ["username", "User name"], ["name", "Name"], ["email", "E-mail"], ["description", "Description"]];
const PAGE_SIZES = [25, 50, 100];
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export function Users({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [hidden, setHidden] = usePersistent<string[]>("users.hidden", ["display", "created", "description"]);
  const [people, setPeople] = useState<CrPerson[] | null>(null);
  const [runnerOf, setRunnerOf] = useState<Record<string, string>>({});
  const [roles, setRoles] = useState<string[]>([]);
  const [runners, setRunners] = useState<Runner[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searchIn, setSearchIn] = useState("any");
  const [f, setF] = useState<Record<string, string>>({});
  const [offset, setOffset] = useState(0);
  const [pageSize, setPageSize] = usePersistent("users.pageSize", 25);
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "columns" | "create" | "delete" } | { kind: "details"; id: string } | { kind: "password"; username: string; password: string }>(null);
  const canRunners = user.permissions.includes("devices.view");

  const load = useCallback(async () => {
    try {
      const [u, r] = await Promise.all([api.users(), api.roles().catch(() => [])]);
      setPeople(u); setRoles(r.filter((x) => !x.switched_off).map((x) => x.name)); setError(null);
      setSelected((s) => s.filter((id) => u.some((x) => x.id === id)));
      if (canRunners) {
        const [d, dr] = await Promise.all([api.devices().catch(() => []), api.defaultRunners().catch(() => [])]);
        setRunners(d); setRunnerOf(Object.fromEntries(dr.filter((x) => x.device_name).map((x) => [x.username, x.device_name!])));
      }
    } catch (e) { setError(message(e)); }
  }, [api, canRunners]);
  useEffect(() => { load(); }, [load]);

  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    const fields = (u: CrPerson) => searchIn === "any" ? [u.username, u.display_name, fullName(u), u.email, u.description].join(" ")
      : searchIn === "name" ? fullName(u) + " " + (u.display_name ?? "") : String((u as Record<string, unknown>)[searchIn] ?? "");
    return (people ?? []).filter((u) => (!text || fields(u).toLowerCase().includes(text))
      && (!f.status || stateOf(u) === f.status) && (!f.role || (u.roles ?? [u.role]).includes(f.role)));
  }, [people, f, searchIn]);
  const page = shown.slice(offset, offset + pageSize);
  const chosen = (people ?? []).filter((u) => selected.includes(u.id));
  const cols = COLS.filter((c) => !hidden.includes(c.key) && (c.key !== "runner" || canRunners));
  const setFilter = (k: string, v: string) => { setF({ ...f, [k]: v }); setOffset(0); };

  /** One action on each selected person; counts what worked and shows the first failure. */
  const each = async (what: string, fn: (u: CrPerson) => Promise<unknown>) => {
    let done = 0, failed: string | null = null;
    for (const u of chosen) { try { await fn(u); done++; } catch (e) { failed ??= u.username + ": " + message(e); } }
    onNotice(what + " " + done + " user" + (done === 1 ? "" : "s") + ".");
    setError(failed); setSelected([]); load();
  };
  const exportCsv = () => {
    const rows = chosen.length ? chosen : shown;
    const text = [COLS.map((c) => c.label), ...rows.map((u) => COLS.map((c) => c.cell(u, runnerOf[u.username] ?? "")))].map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "users.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + rows.length + " user" + (rows.length === 1 ? "" : "s") + ".");
  };

  const active = (people ?? []).filter((u) => u.enabled).length;
  const locked = (people ?? []).filter((u) => stateOf(u) === "locked").length;
  const filtered = Object.values(f).some(Boolean);
  const resetPassword = (u: CrPerson) => api.resetPassword(u.id).then((p) => { onNotice("Reset the password of " + u.username + "."); setDialog({ kind: "password", username: u.username, password: p.temporary_password }); },
    (e) => setError(message(e)));
  const one = (what: string, fn: () => Promise<unknown>) => fn().then(() => { onNotice(what); load(); }, (e) => setError(message(e)));
  const rowMenu = (u: CrPerson) => [
    { key: "open", label: "Open", onSelect: () => setDialog({ kind: "details", id: u.id }) },
    ...(stateOf(u) === "locked" ? [{ key: "unlock", label: "Unlock", onSelect: () => one("Unlocked " + u.username + ".", () => api.unlockUser(u.id)) }] : []),
    { key: "password", label: "Reset password", onSelect: () => resetPassword(u) },
    { key: "twostep", label: "Reset two-step sign-in", onSelect: () => one("Two-step sign-in reset for " + u.username + "; they set it up again.", () => api.resetTwoStep(u.id)) },
    { key: "enable", label: u.enabled ? "Disable" : "Enable", onSelect: () => one((u.enabled ? "Disabled " : "Enabled ") + u.username + ".", () => api.updateUser(u.id, { enabled: !u.enabled })) },
    { key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => { setSelected([u.id]); setDialog({ kind: "delete" }); } },
  ];
  const cell = (c: Col, u: CrPerson) => {
    if (c.key === "status") return <span className={"cc-pill is-" + stateOf(u)}><span className="cc-pill-dot" />{STATUS[stateOf(u)]}</span>;
    if (c.key === "username") return <strong>{u.username}</strong>;
    if (c.key === "roles") return (u.roles ?? [u.role]).map((r) => <span key={r} className="cc-role">{roleName(r)}</span>);
    const v = c.cell(u, runnerOf[u.username] ?? "");
    return v || <span className="cc-none">–</span>;
  };

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-users-title">
      <header className="cc-list-head">
        <h1 id="cc-users-title">Users</h1>
        <span className="ov-info" role="img" aria-label="About this page" title="People who can sign in, their roles, and the runner their runs go to">i</span>
        {people && <span className="cc-list-count">{people.length} {people.length === 1 ? "person" : "people"} · {active} active{locked ? " · " + locked + " locked" : ""}</span>}
        <span className="ov-fill" />
        <Button kind="primary" onClick={() => setDialog({ kind: "create" })}>Create user</Button>
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <Select aria-label="Search in" value={searchIn} onChange={(e) => { setSearchIn(e.target.value); setOffset(0); }} options={SEARCH_IN.map(([value, label]) => ({ value, label }))} />
          <span className="cc-list-search"><TextInput type="search" aria-label="Search users" placeholder="Search users" value={f.q ?? ""} onChange={(e) => setFilter("q", e.target.value)} /></span>
          <span className={"cc-chip" + (f.status ? " is-on" : "")}><Select aria-label="Status" value={f.status ?? ""} onChange={(e) => setFilter("status", e.target.value)}
            options={[{ value: "", label: "Status: Any" }, ...Object.entries(STATUS).map(([value, label]) => ({ value, label: "Status: " + label }))]} /></span>
          {roles.length > 0 && <span className={"cc-chip" + (f.role ? " is-on" : "")}><Select aria-label="Role" value={f.role ?? ""} onChange={(e) => setFilter("role", e.target.value)}
            options={[{ value: "", label: "Role: Any" }, ...roles.map((r) => ({ value: r, label: "Role: " + roleName(r) }))]} /></span>}
          {filtered && <Button kind="plain" onClick={() => { setF({}); setOffset(0); }}>Clear filters</Button>}
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Users list tools">
          <Button kind="plain" onClick={() => setDialog({ kind: "columns" })}>Columns</Button>
          <Button kind="plain" onClick={exportCsv}>{chosen.length ? "Export selected" : "Export CSV"}</Button>
          <button type="button" className="cc-list-icon" aria-label="Refresh" title="Refresh" onClick={load}>
            <svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="M21 12a9 9 0 1 1-3-6.7L21 8" /><path d="M21 3v5h-5" /></svg></button>
        </div>
      </div>
      <div className="cc-list-panel">
        {chosen.length > 0 && <div className="cc-list-bulk" role="toolbar" aria-label="User actions">
          <strong>{chosen.length} selected</strong>
          {chosen.some((u) => !u.enabled) && <Button kind="secondary" onClick={() => each("Enabled", (u) => api.updateUser(u.id, { enabled: true }))}>Enable</Button>}
          {chosen.some((u) => u.enabled) && <Button kind="secondary" onClick={() => each("Disabled", (u) => api.updateUser(u.id, { enabled: false }))}>Disable</Button>}
          <Button kind="danger" onClick={() => setDialog({ kind: "delete" })}>Delete</Button>
          <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
        </div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Users list">
            <thead><tr>
              <th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={page.length > 0 && page.every((u) => selected.includes(u.id))}
                onChange={(e) => setSelected(e.target.checked ? page.map((u) => u.id) : [])} /></th>
              {cols.map((c) => <th key={c.key} scope="col">{c.label}</th>)}
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th>
            </tr></thead>
            <tbody>
              {page.map((u) => (
                <tr key={u.id} className={selected.includes(u.id) ? "is-selected" : ""} onClick={(e) => { if (!(e.target as HTMLElement).closest("input, button")) setDialog({ kind: "details", id: u.id }); }}>
                  <td className="cc-check"><input type="checkbox" aria-label={"Select " + u.username} checked={selected.includes(u.id)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, u.id] : selected.filter((x) => x !== u.id))} /></td>
                  {cols.map((c) => <td key={c.key}>{cell(c, u)}</td>)}
                  <td className="cc-list-menu"><Menu label={"Actions for " + u.username} align="end" items={rowMenu(u)}
                    trigger={<button type="button" className="cc-list-icon is-row" aria-label={"Actions for " + u.username} title="Actions">
                      <svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg></button>} /></td>
                </tr>
              ))}
              {people && !shown.length && <tr className="is-static"><td colSpan={cols.length + 2} className="cc-none">No users match these filters.</td></tr>}
              {!people && !error && <tr className="is-static"><td colSpan={cols.length + 2} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {people && <div className="cc-list-pager">
          <span>{shown.length ? (offset + 1) + "–" + Math.min(offset + pageSize, shown.length) + " of " + shown.length : "0 of 0"}{filtered ? " matching · " + people.length + " in all" : ""}</span>
          <span className="ov-fill" />
          <label className="cc-list-rows">Rows per page <select aria-label="Rows per page" value={pageSize} onChange={(e) => { setPageSize(Number(e.target.value)); setOffset(0); }}>
            {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}</select></label>
          <button type="button" className="cc-list-icon" aria-label="Previous page" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - pageSize))}>‹</button>
          <button type="button" className="cc-list-icon" aria-label="Next page" disabled={offset + pageSize >= shown.length} onClick={() => setOffset(offset + pageSize)}>›</button>
        </div>}
      </div>
      {people && people.length === 1 && <p className="cc-list-hint">You are the only person here. <button type="button" className="cc-link" onClick={() => setDialog({ kind: "create" })}>Create a user</button> for each colleague,
        or turn on single sign-on in <a href="#/settings">Settings</a>.</p>}

      {dialog?.kind === "columns" && (
        <Dialog open title="Columns" width={360} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <div className="cc-cols" role="group" aria-label="Columns shown">
            {COLS.filter((c) => c.key !== "runner" || canRunners).map((c) => <label key={c.key} className="st-check-line"><input type="checkbox" checked={!hidden.includes(c.key)} disabled={c.key === "username"}
              onChange={(e) => setHidden(e.target.checked ? hidden.filter((k) => k !== c.key) : [...hidden, c.key])} /> {c.label}</label>)}
          </div>
        </Dialog>
      )}
      {dialog?.kind === "delete" && (
        <Dialog open title="Delete users" width={460} onClose={() => setDialog(null)} footer={<>
          <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button>
          <Button kind="danger" onClick={() => { setDialog(null); each("Deleted", (u) => api.deleteUser(u.id)); }}>Delete</Button></>}>
          <p>Delete {chosen.map((u) => u.username).join(", ")}? Their sign-ins, API keys, team places and own vault values go; the audit trail keeps their name.
            People who still own schedules, triggers, Agent APIs or vault folders are kept: disable them instead, or hand those over first.</p>
        </Dialog>
      )}
      {dialog?.kind === "create" && <CreateUser api={api} roles={roles} onClose={() => setDialog(null)}
        onCreated={(username, password) => { setDialog({ kind: "password", username, password }); load(); }} />}
      {dialog?.kind === "password" && (
        <Dialog open title={"Temporary password for " + dialog.username} width={480} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
          <p>Give it to {dialog.username} securely. They choose their own password at their first sign-in. It is shown only now.</p>
          <code className="cc-key" aria-label="Temporary password">{dialog.password}</code>
        </Dialog>
      )}
      {dialog?.kind === "details" && <UserDetails api={api} id={dialog.id} self={user.username} roles={roles} runners={canRunners ? runners : null}
        onClose={() => setDialog(null)} onChanged={(t) => { onNotice(t); load(); }}
        onPassword={(username, password) => setDialog({ kind: "password", username, password })} />}
    </section>
  );
}

/** Create user: the user name, display name and main role; the rest is set in their details afterwards. */
function CreateUser({ api, roles, onClose, onCreated }: { api: CrApi; roles: string[]; onClose: () => void; onCreated: (username: string, password: string) => void }) {
  const [username, setUsername] = useState("");
  const [display, setDisplay] = useState("");
  const [role, setRole] = useState("viewer");
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = async () => {
    try {
      const r = await api.createUser(username.trim(), display.trim(), role);
      if (email.trim()) {
        const all = await api.users();
        const made = all.find((u) => u.username === r.username);
        if (made) await api.updateUser(made.id, { email: email.trim() });
      }
      onCreated(r.username, r.temporary_password);
    } catch (e) { setError(message(e)); }
  };
  return (
    <Dialog open title="Create user" width={520} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={username.trim().length < 3} onClick={create}>Create user</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <h3 className="cc-log-title">General details</h3>
      <Field label="User name (3-80 letters, digits, . _ @ -)">{(id) => <TextInput id={id} value={username} maxLength={80} autoComplete="off" onChange={(e) => setUsername(e.target.value)} />}</Field>
      <Field label="Display name">{(id) => <TextInput id={id} value={display} maxLength={100} onChange={(e) => setDisplay(e.target.value)} />}</Field>
      <Field label="E-mail (links single sign-on)">{(id) => <TextInput id={id} type="email" value={email} maxLength={200} onChange={(e) => setEmail(e.target.value)} />}</Field>
      <h3 className="cc-log-title">Role</h3>
      <Field label="Main role">{(id) => <Select id={id} value={role} onChange={(e) => setRole(e.target.value)} options={(roles.length ? roles : Object.keys(ROLE_NAMES)).map((r) => ({ value: r, label: roleName(r) }))} />}</Field>
      <p className="st-muted">A temporary password is made and shown once; they choose their own at the first sign-in. Further roles, names and the default runner are in their details.</p>
    </Dialog>
  );
}

/** A person's details in AA's order: general details, roles, default runner; then security (Blue Prism's Unlock, resets). */
function UserDetails({ api, id, self, roles, runners, onClose, onChanged, onPassword }:
  { api: CrApi; id: string; self: string; roles: string[]; runners: Runner[] | null; onClose: () => void; onChanged: (t: string) => void;
    onPassword: (username: string, password: string) => void }) {
  type Detail = Awaited<ReturnType<CrApi["userDetail"]>>;
  const [u, setU] = useState<Detail | null>(null);
  const [form, setForm] = useState({ display_name: "", first_name: "", last_name: "", email: "", description: "", role: "", further: [] as string[], runner: "" });
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const read = useCallback(() => api.userDetail(id).then((d) => {
    setU(d);
    setForm({ display_name: d.display_name ?? "", first_name: d.first_name ?? "", last_name: d.last_name ?? "", email: d.email ?? "", description: d.description ?? "",
      role: d.role, further: d.further_roles ?? [], runner: d.default_device ?? "" });
  }, (e) => setError(message(e))), [api, id]);
  useEffect(() => { read(); }, [read]);
  const run = async (fn: () => Promise<unknown>, done: string) => { try { await fn(); setError(null); onChanged(done); await read(); } catch (e) { setError(message(e)); } };
  if (!u) return <Dialog open title="User" width={680} onClose={onClose} footer={null}>{error ? <p className="st-alert" role="alert">{error}</p> : <p className="st-muted" role="status">Loading…</p>}</Dialog>;

  const changes: Record<string, unknown> = {};
  for (const k of ["display_name", "first_name", "last_name", "email", "description"] as const) if (form[k] !== (u[k] ?? "")) changes[k] = form[k];
  if (form.role !== u.role) changes.role = form.role;
  if ([...form.further].sort().join() !== [...(u.further_roles ?? [])].sort().join()) changes.further_roles = form.further;
  const runnerChanged = runners !== null && form.runner !== (u.default_device ?? "");
  const dirty = Object.keys(changes).length > 0 || runnerChanged;
  const save = () => run(async () => {
    if (Object.keys(changes).length) await api.updateUser(u.id, changes as Parameters<CrApi["updateUser"]>[1]);
    if (runnerChanged) await api.setDefaultRunner(u.id, form.runner || null);
  }, "Saved " + u.username + ".");
  const state = stateOf(u);
  const isSelf = u.username === self;
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  return (
    <Dialog open title={"User " + u.username} width={680} onClose={onClose} footer={<>
      {!isSelf && <Button kind="danger" onClick={() => setConfirmDelete(true)}>Delete</Button>}
      {!isSelf && <Button kind="secondary" onClick={() => run(() => api.updateUser(u.id, { enabled: !u.enabled }), (u.enabled ? "Disabled " : "Enabled ") + u.username + ".")}>{u.enabled ? "Disable" : "Enable"}</Button>}
      <span className="st-bar-fill" />
      <Button kind="primary" disabled={!dirty} onClick={save}>Save changes</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {confirmDelete && <div className="st-alert" role="alert"><span>Delete {u.username}? The audit trail keeps their name.</span>
        <Button kind="danger" onClick={() => run(() => api.deleteUser(u.id), "Deleted " + u.username + ".").then(onClose)}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirmDelete(false)}>Cancel</Button></div>}
      <dl className="cc-facts">
        <dt>Status</dt><dd className={"cc-status is-" + state}>{STATUS[state]}{state === "locked" ? " until " + when(u.locked_until) : ""}</dd>
        <dt>Last sign-in</dt><dd>{u.last_login ? when(u.last_login) : "Never"}</dd>
        <dt>Created</dt><dd>{when(u.created)}</dd>
        {u.teams && u.teams.length > 0 && <><dt>Teams</dt><dd>{u.teams.map((t) => t.name + (t.team_role === "lead" ? " (lead)" : "")).join(", ")}</dd></>}
      </dl>
      <h3 className="cc-log-title">General details</h3>
      <div className="cc-grid2">
        <Field label="Display name">{(fid) => <TextInput id={fid} value={form.display_name} maxLength={100} onChange={set("display_name")} />}</Field>
        <Field label="E-mail">{(fid) => <TextInput id={fid} type="email" value={form.email} maxLength={200} onChange={set("email")} />}</Field>
        <Field label="First name">{(fid) => <TextInput id={fid} value={form.first_name} maxLength={100} onChange={set("first_name")} />}</Field>
        <Field label="Last name">{(fid) => <TextInput id={fid} value={form.last_name} maxLength={100} onChange={set("last_name")} />}</Field>
      </div>
      <Field label="Description">{(fid) => <TextArea id={fid} rows={2} value={form.description} maxLength={500} onChange={set("description")} />}</Field>
      <h3 className="cc-log-title">Roles</h3>
      <Field label="Main role">{(fid) => <Select id={fid} value={form.role} disabled={isSelf} onChange={set("role")} options={roles.map((r) => ({ value: r, label: roleName(r) }))} />}</Field>
      <div className="cc-cols" role="group" aria-label="Further roles">
        {roles.filter((r) => r !== "admin" && r !== form.role).map((r) => <label key={r} className="st-check-line"><input type="checkbox" checked={form.further.includes(r)}
          onChange={(e) => setForm({ ...form, further: e.target.checked ? [...form.further, r] : form.further.filter((x) => x !== r) })} /> {roleName(r)}</label>)}
      </div>
      <p className="st-muted">A person has every permission of each of their roles. Administrator can only be a main role.</p>
      {runners && <>
        <h3 className="cc-log-title">Default runner</h3>
        <Field label="Runs started as this person go to">{(fid) => <Select id={fid} value={form.runner} onChange={set("runner")}
          options={[{ value: "", label: "No default runner" }, ...runners.map((d) => ({ value: d.id, label: d.name + (d.status === "online" ? "" : " (" + d.status + ")") }))]} />}</Field>
      </>}
      <h3 className="cc-log-title">Sign-in and security</h3>
      <dl className="cc-facts">
        <dt>Two-step sign-in</dt><dd>{u.mfa_enabled ? "On" : "Off"}</dd>
        <dt>Password</dt><dd>{u.must_change ? "A temporary one: they choose their own at the next sign-in" : "Their own"}</dd>
      </dl>
      <span className="cc-inline">
        <Button kind="secondary" onClick={() => api.resetPassword(u.id).then((p) => { onChanged("Reset the password of " + u.username + "."); onPassword(u.username, p.temporary_password); }, (e) => setError(message(e)))}>Reset password</Button>
        {u.mfa_enabled && <Button kind="secondary" onClick={() => run(() => api.resetTwoStep(u.id), "Two-step sign-in reset for " + u.username + "; they set it up again.")}>Reset two-step sign-in</Button>}
        {state === "locked" && <Button kind="secondary" onClick={() => run(() => api.unlockUser(u.id), "Unlocked " + u.username + ".")}>Unlock</Button>}
      </span>
    </Dialog>
  );
}
