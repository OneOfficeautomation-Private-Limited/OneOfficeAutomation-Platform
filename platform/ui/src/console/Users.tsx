import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrPerson, CrUser, Runner } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextArea, TextInput, usePersistent, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick, when } from "./ListParts";
import { ROLE_NAMES, roleName } from "./roleNames";

/**
 * Administration > Users, built as AA's Users page (checked against AA's docs and live Control Room, UiPath's accounts
 * and Blue Prism's user management, 31 Oct 2026): search in a chosen field, status and role filters, a column chooser;
 * for the selected people: enable, disable, delete, export. A person's details open from their row, in AA's order
 * (general details, roles, default runner), with Blue Prism's Unlock and the password and two-step resets. Create
 * user shows the temporary password once; they choose their own at the first sign-in.
 * Laid out as the design plan's list pattern (step 3, approved on the canvas): a title row with a count, one toolbar
 * (search, filter chips, Columns, Export, refresh), Enable / Disable / Delete in a blue bar only while rows are ticked,
 * a compact table with one status pill style and a ⋯ menu per row, and a pager under it.
 * Review round 2 (1 Nov 2026): a person opens as their own page (Details; Sign-in and security), as AA's user page, and
 * resetting a password or two-step sign-in asks first, from the list and from the page.
 */
type Col = { key: string; label: string; cell: (u: CrPerson, runner: string) => string };

/** The status shown: disabled, locked (after failed sign-ins), new password needed, or active. */
const stateOf = (u: CrPerson) => !u.enabled ? "disabled" : u.locked_until && new Date(u.locked_until).getTime() > Date.now() ? "locked" : u.must_change ? "pending" : "active";
const STATUS: Record<string, string> = { active: "Active", pending: "New password needed", locked: "Locked", disabled: "Disabled" };
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

const HELP: HelpTopic = { title: "Users",
  about: "The people who can sign in to One CommandCenter, their roles, and the runner their runs go to. Click a person to open their own page.",
  items: [
    { name: "Create user", text: "Give a user name, display name, e-mail and main role. A temporary password is shown once; give it to them securely, and "
      + "they choose their own at the first sign-in." },
    { name: "Search in", text: "Choose the field the search box looks in: any field, user name, name, e-mail or description." },
    { name: "Status", text: "Active; New password needed when they still have a temporary password; Locked after too many failed sign-ins; or Disabled, "
      + "when they cannot sign in." },
    { name: "Role", text: "Shows only the people who have the chosen role, as main role or further role." },
    { name: "Enable, Disable and Delete", text: "Tick people to act on several at once. Delete removes their sign-ins, API keys, team places and own vault "
      + "values; people who still own schedules, triggers, Agent APIs or vault folders are kept, so disable them or hand those over first." },
    { name: "Unlock", text: "In the row's ⋯ menu for a locked person: lets them try to sign in again straight away." },
    { name: "Reset password… and Reset two-step sign-in…", text: "Gives the person a new temporary password, or makes them set up two-step sign-in again at "
      + "their next sign-in. You are asked first." },
    { name: "Columns", text: "Chooses which columns the list shows, for example Default runner, Two-step sign-in or Last sign-in." },
    { name: "Export CSV", text: "Saves the people shown, or only the selected ones, as a CSV file." },
  ] };

const HELP_USER: HelpTopic = { title: "User",
  about: "One person: their details, roles and default runner, then how they sign in. Change what you need and press Save changes.",
  items: [
    { name: "General details", text: "Display name, e-mail, first and last name and a description. The e-mail links the person to single sign-on." },
    { name: "Main role and further roles", text: "A person has every permission of each of their roles. Administrator can only be a main role, and you "
      + "cannot change your own main role." },
    { name: "Default runner", text: "The runner that runs started as this person go to, for example from schedules that run as them. Shown when you may see "
      + "runners." },
    { name: "Sign-in and security", text: "Their status, last sign-in, teams, whether two-step sign-in is on, and whether they still have a temporary "
      + "password." },
    { name: "Reset password… and Reset two-step sign-in…", text: "Gives them a new temporary password, shown once, or makes them set up two-step sign-in "
      + "again. You are asked first." },
    { name: "Unlock", text: "Shown while the person is locked after failed sign-ins; lets them sign in again straight away." },
    { name: "Disable and Enable", text: "A disabled person cannot sign in, but keeps everything they own. You cannot disable yourself." },
    { name: "Delete…", text: "In the ⋯ menu: deletes the person after you confirm; the audit trail keeps their name. You cannot delete yourself." },
  ] };

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
  const [dialog, setDialog] = useState<null | { kind: "columns" | "create" | "delete" } | { kind: "password"; username: string; password: string }>(null);
  const [openId, setOpenId] = useState<string | null>(null);   // a person's own page, in place of the list
  // #/users?user=<name> (a role's Held by) opens that person once the list is read
  const [linked, setLinked] = useState(() => new URLSearchParams(location.hash.split("?")[1] ?? "").get("user"));
  const [ask, setAsk] = useState<Ask | null>(null);   // a reset, asked first
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
  useEffect(() => {
    if (!linked || !people) return;
    const p = people.find((x) => x.username === linked);
    if (p) setOpenId(p.id);
    setLinked(null);
    if (location.hash.includes("?")) history.replaceState(null, "", "#/users");
  }, [linked, people]);

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
  const reset = (a: Ask) => { setAsk(null); if (a.kind === "password") api.resetPassword(a.id).then((p) => { onNotice("Reset the password of " + a.username + "."); setDialog({ kind: "password", username: a.username, password: p.temporary_password }); load(); },
    (e) => setError(message(e))); else one("Two-step sign-in reset for " + a.username + "; they set it up again.", () => api.resetTwoStep(a.id)); };
  const one = (what: string, fn: () => Promise<unknown>) => fn().then(() => { onNotice(what); load(); }, (e) => setError(message(e)));
  const rowMenu = (u: CrPerson) => [
    { key: "open", label: "Open", onSelect: () => setOpenId(u.id) },
    ...(stateOf(u) === "locked" ? [{ key: "unlock", label: "Unlock", onSelect: () => one("Unlocked " + u.username + ".", () => api.unlockUser(u.id)) }] : []),
    { key: "password", label: "Reset password…", onSelect: () => setAsk({ kind: "password", id: u.id, username: u.username }) },
    ...(u.mfa_enabled ? [{ key: "twostep", label: "Reset two-step sign-in…", onSelect: () => setAsk({ kind: "twostep", id: u.id, username: u.username }) }] : []),
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

  const passwordDialog = dialog?.kind === "password" && (
    <Dialog open title={"Temporary password for " + dialog.username} width={480} onClose={() => setDialog(null)} footer={<Button kind="primary" onClick={() => setDialog(null)}>Done</Button>}>
      <p>Give it to {dialog.username} securely. They choose their own password at their first sign-in. It is shown only now.</p>
      <code className="cc-key" aria-label="Temporary password">{dialog.password}</code>
    </Dialog>
  );
  if (openId) return (
    <section className="st-page cc-home cc-explorer" aria-label="User">
      <UserPage key={openId} api={api} id={openId} self={user.username} roles={roles} runners={canRunners ? runners : null}
        onClose={() => { setOpenId(null); load(); }} onChanged={(t) => onNotice(t)} onPassword={(username, password) => setDialog({ kind: "password", username, password })} />
      {passwordDialog}
    </section>
  );
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-users-title">
      <header className="cc-list-head">
        <h1 id="cc-users-title">Users</h1>
        <HelpButton topic={HELP} />
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
          <RefreshButton onClick={load} />
        </div>
      </div>
      <div className="cc-list-panel">
        {ask && <AskBar ask={ask} onYes={() => reset(ask)} onNo={() => setAsk(null)} />}
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
                <tr key={u.id} className={selected.includes(u.id) ? "is-selected" : ""} onClick={rowClick(() => setOpenId(u.id))}>
                  <td className="cc-check"><input type="checkbox" aria-label={"Select " + u.username} checked={selected.includes(u.id)}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, u.id] : selected.filter((x) => x !== u.id))} /></td>
                  {cols.map((c) => <td key={c.key}>{cell(c, u)}</td>)}
                  <td className="cc-list-menu"><RowMenu label={"Actions for " + u.username} items={rowMenu(u)} /></td>
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
      {passwordDialog}
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

/** What a reset does, asked before it happens. */
type Ask = { kind: "password" | "twostep"; id: string; username: string };
const askText = (a: Ask) => a.kind === "password" ? "Reset the password of " + a.username + "? Their current password stops working; you get a temporary one to give them."
  : "Reset two-step sign-in for " + a.username + "? They set it up again at their next sign-in.";

/** The bar that asks before a reset, with what to do on yes. */
function AskBar({ ask, onYes, onNo }: { ask: Ask; onYes: () => void; onNo: () => void }) {
  return <div className="cc-list-bulk" role="alert"><span>{askText(ask)}</span>
    <Button kind="danger" onClick={onYes}>Reset</Button><Button kind="plain" onClick={onNo}>Cancel</Button></div>;
}

/** A person's own page, as AA's user page: their details, roles and default runner to change; then sign-in and security. */
function UserPage({ api, id, self, roles, runners, onClose, onChanged, onPassword }:
  { api: CrApi; id: string; self: string; roles: string[]; runners: Runner[] | null; onClose: () => void; onChanged: (t: string) => void;
    onPassword: (username: string, password: string) => void }) {
  type Detail = Awaited<ReturnType<CrApi["userDetail"]>>;
  const [u, setU] = useState<Detail | null>(null);
  const [tab, setTab] = useState<"details" | "security">("details");
  const [form, setForm] = useState({ display_name: "", first_name: "", last_name: "", email: "", description: "", role: "", further: [] as string[], runner: "" });
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [ask, setAsk] = useState<Ask | null>(null);
  const read = useCallback(() => api.userDetail(id).then((d) => {
    setU(d);
    setForm({ display_name: d.display_name ?? "", first_name: d.first_name ?? "", last_name: d.last_name ?? "", email: d.email ?? "", description: d.description ?? "",
      role: d.role, further: d.further_roles ?? [], runner: d.default_device ?? "" });
  }, (e) => setError(message(e))), [api, id]);
  useEffect(() => { read(); }, [read]);
  const run = async (fn: () => Promise<unknown>, done: string) => { try { await fn(); setError(null); onChanged(done); await read(); } catch (e) { setError(message(e)); } };
  const close = <button type="button" className="cc-list-icon" aria-label="Close" title="Back to the users" onClick={onClose}>×</button>;
  if (!u) return <div className="cc-agent" role="dialog" aria-modal="false" aria-label="User"><div className="cc-agent-head"><h1>User</h1><span className="ov-fill" />{close}</div>
    {error ? <p className="st-alert" role="alert">{error}</p> : <p className="cc-none cc-agent-pad" role="status">Loading…</p>}</div>;

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
  const reset = (a: Ask) => { setAsk(null); if (a.kind === "password") api.resetPassword(u.id).then((p) => { onChanged("Reset the password of " + u.username + "."); onPassword(u.username, p.temporary_password); read(); }, (e) => setError(message(e)));
    else run(() => api.resetTwoStep(u.id), "Two-step sign-in reset for " + u.username + "; they set it up again."); };
  const more = [{ key: "password", label: "Reset password…", onSelect: () => setAsk({ kind: "password", id: u.id, username: u.username }) },
    ...(u.mfa_enabled ? [{ key: "twostep", label: "Reset two-step sign-in…", onSelect: () => setAsk({ kind: "twostep", id: u.id, username: u.username }) }] : []),
    ...(state === "locked" ? [{ key: "unlock", label: "Unlock", onSelect: () => { run(() => api.unlockUser(u.id), "Unlocked " + u.username + "."); } }] : []),
    ...(!isSelf ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setConfirmDelete(true) }] : [])];

  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"User " + u.username}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Users</button> ›</span>
        <h1>{u.username}</h1>
        <HelpButton topic={HELP_USER} />
        <span className={"cc-pill is-" + state}><span className="cc-pill-dot" />{STATUS[state]}</span>
        <span className="cc-none">{[u.display_name || fullName(u), [u.role, ...(u.further_roles ?? [])].map(roleName).join(", ")].filter(Boolean).join(" · ")}</span>
        <span className="ov-fill" />
        {!isSelf && <Button kind="secondary" onClick={() => run(() => api.updateUser(u.id, { enabled: !u.enabled }), (u.enabled ? "Disabled " : "Enabled ") + u.username + ".")}>{u.enabled ? "Disable" : "Enable"}</Button>}
        <RowMenu label={"More for " + u.username} items={more} />
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ask && <AskBar ask={ask} onYes={() => reset(ask)} onNo={() => setAsk(null)} />}
      {confirmDelete && <div className="cc-list-bulk" role="alert"><span>Delete {u.username}? The audit trail keeps their name.</span>
        <Button kind="danger" onClick={() => run(() => api.deleteUser(u.id), "Deleted " + u.username + ".").then(onClose)}>Delete</Button>
        <Button kind="plain" onClick={() => setConfirmDelete(false)}>Cancel</Button></div>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this person">
        {([["details", "Details"], ["security", "Sign-in and security"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "details" && <div className="cc-agent-tab cc-user-form">
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
        <span><Button kind="primary" disabled={!dirty} onClick={save}>Save changes</Button></span>
      </div>}
      {tab === "security" && <div className="cc-agent-tab">
        <dl className="cc-facts cc-facts-wide">
          <dt>Status</dt><dd>{STATUS[state]}{state === "locked" ? " until " + when(u.locked_until) : ""}</dd>
          <dt>Last sign-in</dt><dd>{u.last_login ? when(u.last_login) : "Never"}</dd>
          <dt>Created</dt><dd>{when(u.created)}</dd>
          <dt>Teams</dt><dd>{u.teams?.length ? u.teams.map((t) => t.name + (t.team_role === "lead" ? " (lead)" : "")).join(", ") : "None"}</dd>
          <dt>Two-step sign-in</dt><dd>{u.mfa_enabled ? "On" : "Off"}</dd>
          <dt>Password</dt><dd>{u.must_change ? "A temporary one: they choose their own at the next sign-in" : "Their own"}</dd>
        </dl>
        <span className="cc-inline">
          <Button kind="secondary" onClick={() => setAsk({ kind: "password", id: u.id, username: u.username })}>Reset password…</Button>
          {u.mfa_enabled && <Button kind="secondary" onClick={() => setAsk({ kind: "twostep", id: u.id, username: u.username })}>Reset two-step sign-in…</Button>}
          {state === "locked" && <Button kind="secondary" onClick={() => run(() => api.unlockUser(u.id), "Unlocked " + u.username + ".")}>Unlock</Button>}
        </span>
      </div>}
    </div>
  );
}
