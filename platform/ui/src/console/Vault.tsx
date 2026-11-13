import { useCallback, useEffect, useMemo, useState } from "react";
import type { CredentialExpiry, CrApi, CrUser, VaultCredential, VaultFolder, VaultRequest, VaultStatus } from "../api/cr";
import { Button, Dialog, Field, HelpButton, Select, TextInput, type HelpTopic, PageError } from "../components";
import { RefreshButton, RowMenu, rowClick, when, CloseView } from "./ListParts";
import { PageIntro } from "../shell/PageIntro";
import { HelpLinks } from "../shell/HelpLinks";

/**
 * Administration > Credential vault, built as AA's Credentials page (checked against AA's docs, UiPath's credential
 * assets and stores, Blue Prism's Credential Manager, 31 Oct 2026): tabs Credentials, Vault folders (AA's lockers)
 * and My requests (values each person fills in for their own runs). Values are encrypted and never shown again unless
 * marked not secret; agents get them only while running, and every use is audited. A folder can read its values live
 * from a company vault (HashiCorp, Azure Key Vault, AWS Secrets Manager, CyberArk), as UiPath's credential stores.
 * Laid out as the design plan's list pattern (applied 31 Oct 2026): a count, tabs, one toolbar, Delete in a bar only
 * while rows are ticked, a ⋯ menu per row.
 * Review round 2 (1 Nov 2026): a credential and a vault folder open as their own pages (Attributes, Details, Your values;
 * Credentials, Members, Company vault), and deleting a credential or a folder and unlinking the company vault ask first.
 * Gap G30 (4 Nov 2026): a credential's expiry date, set in its Details, shown in the list with expired and expiring
 * soon (14 days or fewer) marked; agents check it with ooa.vault and may save new values when Settings allows.
 */
type Tab = "credentials" | "folders" | "requests";
const EXT: Record<string, string> = { hashicorp: "HashiCorp Vault", azure_keyvault: "Azure Key Vault", aws_secrets_manager: "AWS Secrets Manager", cyberark_ccp: "CyberArk Central Credential Provider" };
const EXT_FIELDS: Record<string, [string, string, string][]> = {
  hashicorp: [["url", "Vault address", "https://vault.example.com:8200"], ["mount", "KV v2 mount", "secret"], ["namespace", "Namespace (Enterprise, optional)", ""]],
  azure_keyvault: [["url", "Key Vault address", "https://my-vault.vault.azure.net"], ["tenant_id", "Tenant id", ""], ["client_id", "Application (client) id", ""]],
  aws_secrets_manager: [["region", "Region", "eu-west-1"], ["access_key_id", "Access key id", "AKIA..."], ["endpoint", "Endpoint (optional, e.g. VPC endpoint)", ""]],
  cyberark_ccp: [["url", "Central Credential Provider address", "https://ccp.example.com"], ["app_id", "Application id", ""], ["safe", "Safe", ""], ["folder", "Folder (optional)", ""]] };
const EXT_SECRET: Record<string, string> = { hashicorp: "Token", azure_keyvault: "Client secret", aws_secrets_manager: "Secret access key", cyberark_ccp: "Password (optional)" };
const PLACE: Record<string, string> = { owner: "Owner", manager: "Manager", participant: "Participant", consumer: "Consumer" };
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const list = (v?: string | string[] | null) => (Array.isArray(v) ? v : String(v ?? "").split(",")).map((x) => x.trim()).filter(Boolean);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;

/** One row of the Credentials tab: a credential in a folder, its attributes and whether the caller's own values are set. */
type CredRow = { folder: VaultFolder; name: string; attributes: string[]; userAttrs: string[]; mine: string[]; owner: string; updated?: string; updatedBy?: string; exp: CredentialExpiry };
function rowsOf(folders: VaultFolder[]): CredRow[] {
  const out: CredRow[] = [];
  for (const f of folders) {
    const info = Object.fromEntries((f.credential_info ?? []).map((c) => [c.name, c]));
    const names = new Set([...f.credentials.map((c) => c.name), ...Object.keys(info)]);
    for (const n of names) {
      const cs = f.credentials.filter((c) => c.name === n);
      const last = [...cs].sort((a, b) => String(b.updated).localeCompare(String(a.updated)))[0];
      out.push({ folder: f, name: n, attributes: cs.map((c) => c.attribute), userAttrs: info[n]?.user_attributes ?? [], mine: info[n]?.my_values ?? [],
        owner: info[n]?.owner ?? "", updated: last?.updated, updatedBy: last?.updated_by, exp: info[n] ?? {} });
    }
  }
  return out.sort((a, b) => (a.folder.name + "/" + a.name).localeCompare(b.folder.name + "/" + b.name));
}
const writer = (f: VaultFolder) => f.my_role === "owner" || f.my_role === "manager";
const needsCare = (e: CredentialExpiry) => !!e.expires && (!!e.expired || !!e.expiring_soon);
/** A credential's expiry date, marked when it has expired or expires within 14 days. */
function Expiry({ e }: { e: CredentialExpiry }) {
  if (!e.expires) return <span className="cc-none">Never</span>;
  const days = e.days_left ?? 0;
  if (e.expired) return <span className="cc-own is-expired" title={"Expired on " + e.expires}>Expired {e.expires}</span>;
  if (e.expiring_soon) return <span className="cc-own is-missing" title={"Expires on " + e.expires}>{days === 0 ? "Expires today" : "In " + days + " day" + (days === 1 ? "" : "s")} ({e.expires})</span>;
  return <span>{e.expires}</span>;
}

const HELP: HelpTopic = { title: "Credential vault",
  about: "Encrypted vault folders that keep the passwords, keys and other values agents need. Values are never shown again after saving; "
    + "agents get them only while running, and every use is recorded in the Audit Trail.",
  items: [
    { name: "Credentials", text: "Every credential you can see, with its vault folder, attributes and owner. Agents name a value as "
      + "folder/credential/attribute." },
    { name: "Each person's own", text: "Attributes each person fills in for their own runs, such as their own password. Needs my own value shows the ones "
      + "you have not filled in yet." },
    { name: "Create credential", text: "Choose a vault folder you manage or take part in, name the credential and add its attributes, each with a value or "
      + "left for each person to fill in." },
    { name: "Vault folders", text: "The folders you own or use, with your place in each, its owners and consumers, and whether its values come from a "
      + "company vault." },
    { name: "Create vault folder", text: "Makes a folder that you own; say which roles, teams or people are its consumers. Needs permission to own vault "
      + "folders or manage the vault." },
    { name: "My requests", text: "Values credentials ask you to fill in for runs that run as you. Type each one and press Save; you can change it later on "
      + "the credential's page." },
    { name: "Vault key", text: "Where the master key comes from, its fingerprint, and rotating it, which encrypts every stored value again. For those who "
      + "manage the vault." },
    { name: "Delete…", text: "Deletes credentials with all their values, everyone's own values too, after you confirm. Allowed in folders you manage, or "
      + "for credentials you own." },
    { name: "Expires", text: "When the credential stops working, for example a password that must be changed every 90 days. Expired ones and those "
      + "expiring within 14 days are marked; Expired or expiring shows only those. Set it in the credential's Details." },
    { name: "Export CSV", text: "Saves the credentials shown as a CSV file: names and attributes only, never values." },
  ] };

const HELP_CREDENTIAL: HelpTopic = { title: "Credential",
  about: "One credential: its attributes and values, its details and, when it has any, the values you keep for yourself. Agents name a value as "
    + "folder/credential/attribute.",
  items: [
    { name: "Attributes", text: "Each attribute with its value. Type a new value and press Change to replace it; the old value is never shown." },
    { name: "Masked", text: "On by default: the value is never shown. Clear it for values that are not secret, such as a user name, so owners and managers "
      + "can look them up with Show; each look is recorded." },
    { name: "Password fields only", text: "Agents may type this value into password fields only, so it cannot end up in ordinary text." },
    { name: "Details", text: "The description, the owner, the expiry date, and which attributes each person fills in for themselves, separated by commas. "
      + "Only folder owners and managers change the owner." },
    { name: "Expires on", text: "The date the credential stops working; empty when it does not expire. Agents read it with Check credential expiry, "
      + "and an agent that saves a new value can set the next date." },
    { name: "Your values", text: "Your own value of each such attribute. A run reads it from whoever the run runs as, or whoever started it. Clear mine "
      + "removes yours." },
    { name: "Save details", text: "Keeps what you changed in descriptions, Masked, Password fields only and Details." },
    { name: "Delete…", text: "In the ⋯ menu: deletes the credential with all its values, everyone's own values too. Agents that use it fail until it is "
      + "made again." },
  ] };

const HELP_FOLDER: HelpTopic = { title: "Vault folder",
  about: "One vault folder: its credentials, who may do what with them, and an optional link to your company vault.",
  items: [
    { name: "Credentials", text: "The credentials in the folder. Click one to open it." },
    { name: "Members", text: "Owners can do everything; managers add, change and delete every credential; participants add credentials and change their own; "
      + "consumers' agents may use the credentials. Write people, roles or teams as user:jane, role:bot_runner or team:finance." },
    { name: "Save members", text: "Keeps the member lists you changed. Only those who manage the folder can change them." },
    { name: "Company vault", text: "Reads the folder's values live from HashiCorp Vault, Azure Key Vault, AWS Secrets Manager or CyberArk. "
      + "Agents still ask this folder, so members and the Audit Trail work as before." },
    { name: "Link, Test reading and Unlink…", text: "Fill in the provider's address and secret, then Link. Test reading checks one secret can be read "
      + "without showing it. Unlink makes agents use values kept here again." },
    { name: "Delete folder…", text: "In the ⋯ menu once the folder is empty: deletes it with its members and company vault link." },
  ] };

export function Vault({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("credentials");
  const [folders, setFolders] = useState<VaultFolder[] | null>(null);
  const [requests, setRequests] = useState<VaultRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "create-credential" | "create-folder" | "key" }>(null);
  // a credential's or a vault folder's own page, in place of the list
  const [page, setPage] = useState<null | { kind: "credential"; folder: string; name: string } | { kind: "folder"; folder: string }>(null);
  const [asking, setAsking] = useState(false);   // deleting the ticked credentials, asked first
  const can = (p: string) => user.permissions.includes(p);

  const load = useCallback(async () => {
    try { const [fs, rq] = await Promise.all([api.vaultFolders(), api.vaultRequests().catch(() => [])]); setFolders(fs); setRequests(rq); setError(null); }
    catch (e) { setError(message(e)); }
  }, [api]);
  useEffect(() => { load(); }, [load]);

  const rows = useMemo(() => rowsOf(folders ?? []), [folders]);
  const key = (r: CredRow) => r.folder.name + "/" + r.name;
  const shown = useMemo(() => {
    const text = (f.q ?? "").trim().toLowerCase();
    return rows.filter((r) => (!text || (key(r) + " " + r.attributes.join(" ") + " " + r.userAttrs.join(" ") + " " + r.owner).toLowerCase().includes(text))
      && (!f.folder || r.folder.name === f.folder) && (!f.mine || r.userAttrs.some((a) => !r.mine.includes(a))) && (!f.expiry || needsCare(r.exp)));
  }, [rows, f]);
  const chosen = rows.filter((r) => selected.includes(key(r)));
  const canDelete = (r: CredRow) => writer(r.folder) || (r.folder.my_role === "participant" && r.owner === user.username);
  const removeChosen = async () => {
    let done = 0, failed: string | null = null;
    for (const r of chosen) { try { await api.deleteCredential(r.folder.name, r.name); done++; } catch (e) { failed ??= key(r) + ": " + message(e); } }
    onNotice("Deleted " + done + " credential" + (done === 1 ? "" : "s") + "."); setError(failed); setSelected([]); load();
  };
  const exportCsv = () => {
    const text = [["Credential", "Vault folder", "Attributes", "Each person's own", "Owner", "Expires", "Updated"],
      ...shown.map((r) => [r.name, r.folder.name, r.attributes.join(" "), r.userAttrs.join(" "), r.owner, r.exp.expires ?? "", when(r.updated)])].map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "credentials.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + shown.length + " credential" + (shown.length === 1 ? "" : "s") + " (names only, never values).");
  };
  const canCreateCredential = (folders ?? []).some((x) => writer(x) || x.my_role === "participant");
  const folderOf = (n: string) => (folders ?? []).find((x) => x.name === n);
  const pageFolder = page ? folderOf(page.folder) : undefined;
  if (page && pageFolder) return (
    <section className="st-page cc-home cc-explorer" aria-label="Credential vault">
      {page.kind === "credential"
        ? <CredentialPage key={page.folder + "/" + page.name} api={api} folder={pageFolder} name={page.name} onClose={() => { setPage(null); load(); }} onChanged={(t) => { onNotice(t); load(); }} />
        : <FolderPage key={page.folder} api={api} folder={pageFolder} onClose={() => { setPage(null); load(); }} onOpen={(n) => setPage({ kind: "credential", folder: page.folder, name: n })}
          onChanged={(t, close) => { onNotice(t); if (close) setPage(null); load(); }} />}
    </section>
  );
  const filtered = Object.values(f).some(Boolean);
  const missing = rows.filter((r) => r.userAttrs.some((a) => !r.mine.includes(a))).length;
  const expired = rows.filter((r) => r.exp.expired).length, soon = rows.filter((r) => !r.exp.expired && r.exp.expiring_soon).length;

  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-vault-title">
      <header className="cc-list-head">
        <h1 id="cc-vault-title">Credential vault</h1>
        <HelpButton topic={HELP} /><PageIntro k="page:credentials" />
        {folders && <span className="cc-list-count">{rows.length} credential{rows.length === 1 ? "" : "s"} · {folders.length} vault folder{folders.length === 1 ? "" : "s"}{missing ? " · " + missing + " need your own value" : ""}{expired ? " · " + expired + " expired" : ""}{soon ? " · " + soon + " expiring soon" : ""}</span>}
        <span className="ov-fill" />
        {can("lockers.manage") && <Button kind="secondary" onClick={() => setDialog({ kind: "key" })}>Vault key</Button>}
        {tab === "folders" && (can("lockers.manage") || can("lockers.own")) && <Button kind="primary" onClick={() => setDialog({ kind: "create-folder" })}>Create vault folder</Button>}
        {tab === "credentials" && canCreateCredential && <Button kind="primary" onClick={() => setDialog({ kind: "create-credential" })}>Create credential</Button>}
      </header>
      <div className="ov-tabs cc-list-tabs" role="tablist" aria-label="Credential vault">
        {([["credentials", "Credentials", rows.length], ["folders", "Vault folders", folders?.length ?? 0], ["requests", "My requests", requests.length]] as const).map(([t, l, n]) =>
          <button key={t} type="button" role="tab" className="ov-tab" aria-selected={tab === t} onClick={() => { setTab(t); setSelected([]); }}>{l}{folders && <span className="ov-cnt">{n}</span>}</button>)}
      </div>
      {error && <PageError text={error} />}

      {tab === "credentials" && <>
        <div className="cc-list-tools">
          <div className="cc-list-filters" role="group" aria-label="Filters">
            <span className="cc-list-search"><TextInput type="search" aria-label="Search credentials" placeholder="Search credentials, attributes, owners" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} /></span>
            <span className={"cc-chip" + (f.folder ? " is-on" : "")}><Select aria-label="Vault folder" value={f.folder ?? ""} onChange={(e) => setF({ ...f, folder: e.target.value })}
              options={[{ value: "", label: "Folder: Any" }, ...(folders ?? []).map((x) => ({ value: x.name, label: "Folder: " + x.name }))]} /></span>
            <label className={"cc-chip-check" + (f.mine ? " is-on" : "")}><input type="checkbox" checked={!!f.mine} onChange={(e) => setF({ ...f, mine: e.target.checked ? "1" : "" })} /> Needs my own value</label>
            <label className={"cc-chip-check" + (f.expiry ? " is-on" : "")}><input type="checkbox" checked={!!f.expiry} onChange={(e) => setF({ ...f, expiry: e.target.checked ? "1" : "" })} /> Expired or expiring</label>
            {filtered && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
          </div>
          <div className="cc-list-right" role="toolbar" aria-label="Credentials list tools">
            <Button kind="plain" onClick={exportCsv}>Export CSV</Button>
            <RefreshButton onClick={load} />
          </div>
        </div>
        <div className="cc-list-panel">
          {asking && chosen.length > 0 && <AskBar text={"Delete " + chosen.map(key).join(", ") + " with all their values, everyone's own values too? Agents that use them fail until they are made again."}
            yes="Delete" onYes={() => { setAsking(false); removeChosen(); }} onNo={() => setAsking(false)} />}
          {chosen.length > 0 && !asking && <div className="cc-list-bulk" role="toolbar" aria-label="Credential actions">
            <strong>{chosen.length} selected</strong>
            <Button kind="danger" disabled={!chosen.every(canDelete)} onClick={() => setAsking(true)}>Delete</Button>
            {!chosen.every(canDelete) && <span>You may delete only credentials in folders you manage, or your own.</span>}
            <Button kind="plain" onClick={() => setSelected([])}>Clear selection</Button>
          </div>}
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Credentials list">
              <thead><tr><th scope="col" className="cc-check"><input type="checkbox" aria-label="Select all" checked={shown.length > 0 && shown.every((r) => selected.includes(key(r)))}
                onChange={(e) => setSelected(e.target.checked ? shown.map(key) : [])} /></th><th scope="col">Credential</th><th scope="col">Vault folder</th>
                <th scope="col">Attributes</th><th scope="col">Each person's own</th><th scope="col">Owner</th><th scope="col">Expires</th><th scope="col">Last modified</th>
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {shown.map((r) => (
                  <tr key={key(r)} className={selected.includes(key(r)) ? "is-selected" : ""} onClick={rowClick(() => setPage({ kind: "credential", folder: r.folder.name, name: r.name }))}>
                    <td className="cc-check"><input type="checkbox" aria-label={"Select " + key(r)} checked={selected.includes(key(r))}
                      onChange={(e) => setSelected(e.target.checked ? [...selected, key(r)] : selected.filter((x) => x !== key(r)))} /></td>
                    <td><strong>{r.name}</strong></td>
                    <td>{r.folder.name}{r.folder.external && <span className="cc-tag" title="Values are read live from the company vault">{EXT[r.folder.external.provider] ?? r.folder.external.provider}</span>}</td>
                    <td>{r.attributes.length ? r.attributes.map((a) => <span key={a} className="cc-role">{a}</span>) : <span className="cc-none">–</span>}</td>
                    <td>{r.userAttrs.length ? r.userAttrs.map((a) => <span key={a} className={"cc-own " + (r.mine.includes(a) ? "is-set" : "is-missing")}>{a + (r.mine.includes(a) ? " (yours set)" : " (yours missing)")}</span>)
                      : <span className="cc-none">–</span>}</td>
                    <td>{r.owner || <span className="cc-none">–</span>}</td>
                    <td><Expiry e={r.exp} /></td>
                    <td>{r.updated ? when(r.updated) + (r.updatedBy ? " by " + r.updatedBy : "") : <span className="cc-none">–</span>}</td>
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + key(r)} items={[
                      { key: "open", label: "Open", onSelect: () => setPage({ kind: "credential", folder: r.folder.name, name: r.name }) },
                      { key: "delete", label: "Delete…", disabled: !canDelete(r), separatorBefore: true, onSelect: () => { setSelected([key(r)]); setAsking(true); } }]} /></td>
                  </tr>
                ))}
                {folders && !shown.length && <tr className="is-static"><td colSpan={9} className="cc-none">{rows.length ? "No credentials match." : "No credentials you can see yet."}</td></tr>}
                {!folders && !error && <tr className="is-static"><td colSpan={9} className="cc-none" role="status">Loading…</td></tr>}
              </tbody>
            </table>
          </div>
          {folders && <div className="cc-list-pager"><span>{shown.length} of {rows.length}{filtered ? " matching" : ""}</span></div>}
        </div>
      </>}

      {tab === "folders" && (
        <div className="cc-list-panel">
          <div className="cc-list-scroll">
            <table className="cc-list-table" aria-label="Vault folders list">
              <thead><tr><th scope="col">Vault folder</th><th scope="col">Description</th><th scope="col">Your place</th><th scope="col">Owners</th>
                <th scope="col">Consumers</th><th scope="col">Credentials</th><th scope="col">Company vault</th>
                <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
              <tbody>
                {(folders ?? []).map((x) => (
                  <tr key={x.name} onClick={rowClick(() => setPage({ kind: "folder", folder: x.name }))}>
                    <td><strong>{x.name}</strong></td><td>{x.description || <span className="cc-none">–</span>}</td>
                    <td><span className="cc-role">{PLACE[x.my_role]}</span></td><td>{list(x.owners).join(", ")}</td>
                    <td>{list(x.consumers).length ? list(x.consumers).map((c) => <code key={c} className="cc-code">{c}</code>) : <span className="cc-none">None</span>}</td>
                    <td>{new Set(x.credentials.map((c) => c.name)).size}</td>
                    <td>{x.external ? EXT[x.external.provider] ?? x.external.provider : <span className="cc-none">Not linked</span>}</td>
                    <td className="cc-list-menu"><RowMenu label={"Actions for " + x.name} items={[
                      { key: "open", label: "Open", onSelect: () => setPage({ kind: "folder", folder: x.name }) },
                      { key: "show", label: "Show its credentials", onSelect: () => { setF({ folder: x.name }); setTab("credentials"); } }]} /></td>
                  </tr>
                ))}
                {folders && !folders.length && <tr className="is-static"><td colSpan={8} className="cc-none">No vault folders you own or use yet.</td></tr>}
              </tbody>
            </table>
          </div>
          {folders && <div className="cc-list-pager"><span>{folders.length} vault folder{folders.length === 1 ? "" : "s"}</span></div>}
        </div>
      )}

      {tab === "requests" && <Requests api={api} requests={requests} onSaved={(t) => { onNotice(t); load(); }} />}

      {dialog?.kind === "create-credential" && <CreateCredential api={api} folders={(folders ?? []).filter((x) => writer(x) || x.my_role === "participant")}
        onClose={() => setDialog(null)} onCreated={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "create-folder" && <CreateFolder api={api} onClose={() => setDialog(null)} onCreated={(t) => { onNotice(t); setDialog(null); setTab("folders"); load(); }} />}
      {dialog?.kind === "key" && <VaultKey api={api} onClose={() => setDialog(null)} onNotice={onNotice} />}
    </section>
  );
}

/** Values each person stores for themselves, asked for by the credentials their runs use (AA's user-provided attributes). */
function Requests({ api, requests, onSaved }: { api: CrApi; requests: VaultRequest[]; onSaved: (t: string) => void }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const k = (q: VaultRequest) => q.locker + "/" + q.name + "/" + q.attribute;
  return (
    <>
      <p className="cc-list-hint">Agents that run as you read these values from the vault. Fill them in once; you can change them later in the credential's details.</p>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-panel">
        <div className="cc-list-scroll">
          <table className="cc-list-table cc-list-form" aria-label="My requests list">
            <thead><tr><th scope="col">Credential</th><th scope="col">Attribute</th><th scope="col">Your value</th><th scope="col"><span className="st-sr">Save</span></th></tr></thead>
            <tbody>
              {requests.map((q) => (
                <tr key={k(q)} className="is-static">
                  <td><code className="cc-code">{q.locker}/{q.name}</code>{q.credential_description && <div className="cc-list-sub">{q.credential_description}</div>}</td>
                  <td>{q.attribute}{q.password_only && <span className="cc-tag">password fields only</span>}{q.description && <div className="cc-list-sub">{q.description}</div>}</td>
                  <td><TextInput type="password" autoComplete="new-password" aria-label={"Your " + q.attribute + " for " + q.locker + "/" + q.name} value={values[k(q)] ?? ""}
                    onChange={(e) => setValues({ ...values, [k(q)]: e.target.value })} /></td>
                  <td><Button kind="primary" disabled={!values[k(q)]} onClick={() => api.setMyValue(q.locker, q.name, q.attribute, values[k(q)])
                    .then(() => { setValues({ ...values, [k(q)]: "" }); onSaved("Saved your " + q.attribute + " for " + q.locker + "/" + q.name + "."); }, (e) => setError(message(e)))}>Save</Button></td>
                </tr>
              ))}
              {!requests.length && <tr className="is-static"><td colSpan={4} className="cc-none">Nothing to fill in.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

/** Create credential: a folder, a name and its attributes, each with a value or left for each person to fill in. */
function CreateCredential({ api, folders, onClose, onCreated }: { api: CrApi; folders: VaultFolder[]; onClose: () => void; onCreated: (t: string) => void }) {
  const [folder, setFolder] = useState(folders[0]?.name ?? "");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [expires, setExpires] = useState("");
  const [attrs, setAttrs] = useState<{ attribute: string; value: string; own: boolean }[]>([{ attribute: "username", value: "", own: false }, { attribute: "password", value: "", own: false }]);
  const [error, setError] = useState<string | null>(null);
  const set = (i: number, patch: Partial<(typeof attrs)[number]>) => setAttrs(attrs.map((a, j) => j === i ? { ...a, ...patch } : a));
  const ok = /^[A-Za-z0-9_.-]{1,120}$/.test(name.trim()) && attrs.length > 0 && attrs.every((a) => /^[A-Za-z0-9_.-]{1,80}$/.test(a.attribute.trim()) && (a.own || a.value));
  const create = async () => {
    try {
      const shared = attrs.filter((a) => !a.own);
      for (const a of shared) await api.setCredentialValue(folder, name.trim(), a.attribute.trim(), a.value);
      const own = attrs.filter((a) => a.own).map((a) => a.attribute.trim());
      if (own.length || description || expires) await api.saveCredentialDetails(folder, name.trim(), { description, user_attributes: own, ...(expires ? { expires } : {}) });
      onCreated("Created the credential " + folder + "/" + name.trim() + ". Its values are encrypted.");
    } catch (e) { setError(message(e)); }
  };
  return (
    <Dialog open title="Create credential" width={640} onClose={onClose} help={<HelpLinks k="dialog:credential" label="Credentials" />} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!ok || !folder} onClick={create}>Create credential</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Vault folder">{(id) => <Select id={id} value={folder} onChange={(e) => setFolder(e.target.value)} options={folders.map((x) => ({ value: x.name, label: x.name }))} />}</Field>
        <Field label="Credential name">{(id) => <TextInput id={id} value={name} placeholder="vendor_api" maxLength={120} onChange={(e) => setName(e.target.value)} />}</Field>
      </div>
      <div className="cc-grid2">
        <Field label="Description">{(id) => <TextInput id={id} value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />}</Field>
        <Field label="Expires on (optional)">{(id) => <TextInput id={id} type="date" value={expires} onChange={(e) => setExpires(e.target.value)} />}</Field>
      </div>
      <h3 className="cc-log-title">Attributes</h3>
      <table className="st-table" aria-label="Attributes">
        <thead><tr><th scope="col">Attribute</th><th scope="col">Value</th><th scope="col">Each person's own</th><th scope="col"><span className="st-sr">Remove</span></th></tr></thead>
        <tbody>
          {attrs.map((a, i) => (
            <tr key={i}>
              <td><TextInput aria-label={"Attribute " + (i + 1)} value={a.attribute} maxLength={80} onChange={(e) => set(i, { attribute: e.target.value })} /></td>
              <td><TextInput type="password" autoComplete="new-password" aria-label={"Value of attribute " + (i + 1)} value={a.value} disabled={a.own}
                placeholder={a.own ? "each person fills in their own" : ""} onChange={(e) => set(i, { value: e.target.value })} /></td>
              <td><input type="checkbox" aria-label={"Attribute " + (i + 1) + " is each person's own"} checked={a.own} onChange={(e) => set(i, { own: e.target.checked, value: "" })} /></td>
              <td><Button kind="plain" disabled={attrs.length === 1} onClick={() => setAttrs(attrs.filter((_, j) => j !== i))}>Remove</Button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <Button kind="plain" onClick={() => setAttrs([...attrs, { attribute: "", value: "", own: false }])}>Add attribute</Button>
      <p className="st-muted">Agents refer to an attribute as {folder || "folder"}/{name || "credential"}/attribute. Values are masked and never shown again; mark a value as not secret in the credential's details afterwards. Each person's own values appear in their My requests.</p>
    </Dialog>
  );
}

/** The bar that asks before something that cannot be undone. */
function AskBar({ text, yes, onYes, onNo }: { text: string; yes: string; onYes: () => void; onNo: () => void }) {
  return <div className="cc-list-bulk" role="alert"><span>{text}</span>
    <Button kind="danger" onClick={onYes}>{yes}</Button><Button kind="plain" onClick={onNo}>Cancel</Button></div>;
}

/** A credential's own page: its attributes (values, masked, password fields only), its details, and the caller's own values. */
function CredentialPage({ api, folder, name, onClose, onChanged }: { api: CrApi; folder: VaultFolder; name: string; onClose: () => void; onChanged: (t: string) => void }) {
  const [c, setC] = useState<VaultCredential | null>(null);
  const [tab, setTab] = useState<"attributes" | "details" | "mine">("attributes");
  const [info, setInfo] = useState<Record<string, { description: string; masked: boolean; password_only: boolean }>>({});
  const [meta, setMeta] = useState({ description: "", owner: "", own: "", expires: "" });
  const [values, setValues] = useState<Record<string, string>>({});
  const [shown, setShown] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  const ref = folder.name + "/" + name;
  const read = useCallback(() => api.credential(folder.name, name).then((x) => {
    setC(x);
    setInfo(Object.fromEntries(x.attributes.filter((a) => !a.user_provided).map((a) => [a.attribute, { description: a.description ?? "", masked: a.masked !== false, password_only: !!a.password_only }])));
    setMeta({ description: x.description ?? "", owner: x.owner ?? "", own: x.attributes.filter((a) => a.user_provided).map((a) => a.attribute).join(", "), expires: x.expires ?? "" });
  }, (e) => setError(message(e))), [api, folder.name, name]);
  useEffect(() => { read(); }, [read]);
  const run = async (fn: () => Promise<unknown>, done: string) => { try { await fn(); setError(null); onChanged(done); await read(); } catch (e) { setError(message(e)); } };
  const close = <CloseView onClick={onClose} to="the vault" />;
  if (!c) return <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Credential " + ref}><div className="cc-agent-head"><h1>{ref}</h1><span className="ov-fill" />{close}</div>
    {error ? <p className="st-alert" role="alert">{error}</p> : <p className="cc-none cc-agent-pad" role="status">Loading…</p>}</div>;
  const ownerEditable = writer(folder);
  const own = c.attributes.filter((a) => a.user_provided);
  const save = () => run(() => api.saveCredentialDetails(folder.name, name, { description: meta.description, user_attributes: list(meta.own), attributes_info: info,
    ...(c.can_change && meta.expires !== (c.expires ?? "") ? { expires: meta.expires } : {}),
    ...(ownerEditable && meta.owner.trim() && meta.owner.trim() !== c.owner ? { owner: meta.owner.trim() } : {}) }), "Saved the details of " + ref + ".");
  const remove = async () => { setAsking(false); try { await api.deleteCredential(folder.name, name); onChanged("Deleted " + ref + "."); onClose(); } catch (e) { setError(message(e)); } };
  const tabs: ["attributes" | "details" | "mine", string][] = [["attributes", "Attributes (" + c.attributes.length + ")"], ["details", "Details"], ...(own.length ? [["mine", "Your values"] as ["mine", string]] : [])];
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Credential " + ref}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Credential vault</button> ›</span>
        <h1>{ref}</h1>
        <HelpButton topic={HELP_CREDENTIAL} />
        {folder.external && <span className="cc-tag" title="Values are read live from the company vault">{EXT[folder.external.provider] ?? folder.external.provider}</span>}
        <span className="cc-none">{c.owner ? "Owner " + c.owner : "No owner"}{own.length ? " · " + own.length + " each person's own" : ""}</span>
        {c.expires && <span>Expires: <Expiry e={c} /></span>}
        <span className="ov-fill" />
        {(c.can_change || ownerEditable) && <Button kind="primary" onClick={save}>Save details</Button>}
        {c.can_change && <RowMenu label={"More for " + ref} items={[{ key: "delete", label: "Delete…", onSelect: () => setAsking(true) }]} />}
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {asking && <AskBar text={"Delete " + ref + " with all its values, everyone's own values too? Agents that use it fail until it is made again."} yes="Delete" onYes={remove} onNo={() => setAsking(false)} />}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this credential">
        {tabs.map(([k, l]) => <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "attributes" && <>
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Attributes">
            <thead><tr><th scope="col">Attribute</th><th scope="col">Value</th><th scope="col">Description</th><th scope="col">Masked</th><th scope="col">Password fields only</th><th scope="col">Last modified</th></tr></thead>
            <tbody>
              {c.attributes.map((a) => {
                const i = info[a.attribute];
                return (
                  <tr key={a.attribute} className="is-static">
                    <td className="ui-mono">{a.attribute}</td>
                    <td>{a.user_provided ? "Each person's own" + (c.my_values.includes(a.attribute) ? " (yours is set)" : " (yours is not set)")
                      : <span className="cc-inline">
                        {c.can_change && <TextInput type="password" autoComplete="new-password" aria-label={"New value of " + a.attribute} placeholder="new value" value={values[a.attribute] ?? ""}
                          onChange={(e) => setValues({ ...values, [a.attribute]: e.target.value })} />}
                        {c.can_change && values[a.attribute] && <Button kind="secondary" onClick={() => run(() => api.setCredentialValue(folder.name, name, a.attribute, values[a.attribute]).then(() => setValues({ ...values, [a.attribute]: "" })), "Changed " + ref + "/" + a.attribute + ".")}>Change</Button>}
                        {!c.can_change && "Encrypted"}
                        {i && !i.masked && ownerEditable && (shown[a.attribute] !== undefined ? <code>{shown[a.attribute]}</code>
                          : <Button kind="plain" onClick={() => api.credentialValue(folder.name, name, a.attribute).then((v) => setShown({ ...shown, [a.attribute]: v.value }), (e) => setError(message(e)))}>Show</Button>)}
                      </span>}</td>
                    <td>{i ? <TextInput aria-label={"Description of " + a.attribute} value={i.description} disabled={!c.can_change} onChange={(e) => setInfo({ ...info, [a.attribute]: { ...i, description: e.target.value } })} /> : ""}</td>
                    <td>{i && <input type="checkbox" aria-label={"Masked: " + a.attribute} checked={i.masked} disabled={!c.can_change} onChange={(e) => setInfo({ ...info, [a.attribute]: { ...i, masked: e.target.checked } })} />}</td>
                    <td>{i && <input type="checkbox" aria-label={"Password fields only: " + a.attribute} checked={i.password_only} disabled={!c.can_change} onChange={(e) => setInfo({ ...info, [a.attribute]: { ...i, password_only: e.target.checked } })} />}</td>
                    <td>{a.updated ? when(a.updated) + (a.updated_by ? " by " + a.updated_by : "") : ""}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <p className="cc-list-hint cc-qi-foot">Masked values are never shown (the default). Clear Masked for values that are not secret, such as a user name, so owners and managers can look them up; each look is audited. Password fields only: agents may type the value into password fields only.</p>
      </>}
      {tab === "details" && <div className="cc-agent-tab">
        <div className="cc-grid2">
          <Field label="Description">{(id) => <TextInput id={id} value={meta.description} disabled={!c.can_change} onChange={(e) => setMeta({ ...meta, description: e.target.value })} />}</Field>
          <Field label="Owner">{(id) => <TextInput id={id} value={meta.owner} disabled={!ownerEditable} onChange={(e) => setMeta({ ...meta, owner: e.target.value })} />}</Field>
          <Field label="Expires on (empty: does not expire)">{(id) => <TextInput id={id} type="date" value={meta.expires} disabled={!c.can_change} onChange={(e) => setMeta({ ...meta, expires: e.target.value })} />}</Field>
        </div>
        <Field label="Each person's own attributes (separated by commas)">{(id) => <TextInput id={id} value={meta.own} disabled={!c.can_change} placeholder="password" onChange={(e) => setMeta({ ...meta, own: e.target.value })} />}</Field>
        <p className="cc-list-hint">Agents name it as {ref}/attribute. Changes here are kept when you choose Save details.</p>
      </div>}
      {tab === "mine" && <div className="cc-agent-tab">
        {own.map((a) => (
          <span key={a.attribute} className="cc-inline cc-myvalue">
            <TextInput type="password" autoComplete="new-password" aria-label={"Your " + a.attribute} value={values["mine:" + a.attribute] ?? ""}
              placeholder={c.my_values.includes(a.attribute) ? "your value is set; type to replace" : "your " + a.attribute} onChange={(e) => setValues({ ...values, ["mine:" + a.attribute]: e.target.value })} />
            <Button kind="secondary" disabled={!values["mine:" + a.attribute]} onClick={() => run(() => api.setMyValue(folder.name, name, a.attribute, values["mine:" + a.attribute])
              .then(() => setValues({ ...values, ["mine:" + a.attribute]: "" })), "Saved your " + a.attribute + ".")}>Save mine</Button>
            {c.my_values.includes(a.attribute) && <Button kind="plain" onClick={() => run(() => api.clearMyValue(folder.name, name, a.attribute), "Cleared your " + a.attribute + ".")}>Clear mine</Button>}
          </span>
        ))}
        <p className="st-muted">A run reads a person's own attribute from whoever it runs as (the run-as user, or whoever started it).</p>
      </div>}
    </div>
  );
}

/** A vault folder's own page: its credentials, its members by place (AA's owners, managers, participants, consumers), and its company vault link. */
function FolderPage({ api, folder: l, onClose, onOpen, onChanged }: { api: CrApi; folder: VaultFolder; onClose: () => void; onOpen: (name: string) => void; onChanged: (t: string, close: boolean) => void }) {
  const [tab, setTab] = useState<"credentials" | "members" | "external">("credentials");
  const [m, setM] = useState({ owners: list(l.owners).join(", "), managers: l.managers.join(", "), participants: l.participants.join(", "), consumers: list(l.consumers).join(", ") });
  const e0 = l.external ?? { provider: "hashicorp" };
  const [provider, setProvider] = useState(e0.provider);
  const [ext, setExt] = useState<Record<string, string>>(Object.fromEntries(Object.entries(e0).filter(([k, v]) => k !== "provider" && typeof v === "string")) as Record<string, string>);
  const [secret, setSecret] = useState("");
  const [test, setTest] = useState({ credential: "", attribute: "password", result: "" });
  const [error, setError] = useState<string | null>(null);
  const [asking, setAsking] = useState<null | "delete" | "unlink">(null);
  const run = async (fn: () => Promise<unknown>, done: string, close = false) => { try { await fn(); setError(null); onChanged(done, close); } catch (e) { setError(message(e)); } };
  const names = [...new Set(l.credentials.map((c) => c.name))].sort();
  const close = <CloseView onClick={onClose} to="the vault" />;
  const more = [...(l.can_manage && !names.length ? [{ key: "delete", label: "Delete folder…", onSelect: () => setAsking("delete" as const) }] : [])];
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={"Vault folder " + l.name}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Credential vault</button> ›</span>
        <h1>{l.name}</h1>
        <HelpButton topic={HELP_FOLDER} />
        <span className="cc-role">{PLACE[l.my_role]}</span>
        <span className="cc-none">{names.length} credential{names.length === 1 ? "" : "s"}{l.external ? " · read live from " + (EXT[l.external.provider] ?? l.external.provider) : ""}{l.description ? " · " + l.description : ""}</span>
        <span className="ov-fill" />
        {l.can_manage && tab === "members" && <Button kind="primary" onClick={() => run(() => api.setVaultMembers(l.name, m), "Saved the members of " + l.name + ".")}>Save members</Button>}
        {more.length > 0 && <RowMenu label={"More for " + l.name} items={more} />}
        {close}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {asking === "delete" && <AskBar text={"Delete the vault folder " + l.name + "? It is empty; its members and company vault link go with it."} yes="Delete folder"
        onYes={() => { setAsking(null); run(() => api.deleteVaultFolder(l.name), "Deleted the vault folder " + l.name + ".", true); }} onNo={() => setAsking(null)} />}
      {asking === "unlink" && <AskBar text={"Unlink " + l.name + " from " + (EXT[l.external?.provider ?? ""] ?? "the company vault") + "? Agents stop reading its values from there; values kept here are used again, if any."} yes="Unlink"
        onYes={() => { setAsking(null); run(() => api.unlinkExternalVault(l.name), "Unlinked " + l.name + " from the company vault."); }} onNo={() => setAsking(null)} />}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this vault folder">
        {([["credentials", "Credentials (" + names.length + ")"], ["members", "Members"], ...(l.can_manage ? [["external", "Company vault"]] : [])] as ["credentials" | "members" | "external", string][]).map(([k, lb]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{lb}</button>)}
      </div>
      {tab === "credentials" && <div className="cc-list-scroll">
        <table className="cc-list-table" aria-label="Credentials in the folder">
          <thead><tr><th scope="col">Credential</th><th scope="col">Attributes</th></tr></thead>
          <tbody>{names.map((n) => <tr key={n} title="Open the credential" onClick={rowClick(() => onOpen(n))}>
            <td><strong>{n}</strong></td><td className="cc-clip">{l.credentials.filter((c) => c.name === n).map((c) => c.attribute).join(", ")}</td></tr>)}
            {!names.length && <tr className="is-static"><td colSpan={2} className="cc-none">No credentials in this folder yet.</td></tr>}</tbody>
        </table>
      </div>}
      {tab === "members" && <div className="cc-agent-tab">
        <Field label="Owners (user names): everything, members included">{(id) => <TextInput id={id} value={m.owners} disabled={!l.can_manage} onChange={(e) => setM({ ...m, owners: e.target.value })} />}</Field>
        <Field label="Managers: add, change and delete every credential">{(id) => <TextInput id={id} value={m.managers} disabled={!l.can_manage} placeholder="user:carl, team:finance-admins" onChange={(e) => setM({ ...m, managers: e.target.value })} />}</Field>
        <Field label="Participants: see the folder, add credentials and change their own">{(id) => <TextInput id={id} value={m.participants} disabled={!l.can_manage} placeholder="team:finance" onChange={(e) => setM({ ...m, participants: e.target.value })} />}</Field>
        <Field label="Consumers: their agents may use the credentials">{(id) => <TextInput id={id} value={m.consumers} disabled={!l.can_manage} placeholder="role:bot_runner, team:finance" onChange={(e) => setM({ ...m, consumers: e.target.value })} />}</Field>
        <p className="st-muted">Managers, participants and consumers can be people (user:jane), roles (role:bot_runner) or teams (team:finance).</p>
      </div>}
      {tab === "external" && l.can_manage && <div className="cc-agent-tab">
        <p className="st-muted">Keep the secrets in your company vault: agents still ask this folder, so members and the Audit Trail work as before, and the value is read live from there as credential/attribute.</p>
        <Field label="Provider">{(id) => <Select id={id} value={provider} onChange={(e) => setProvider(e.target.value)} options={Object.entries(EXT).map(([value, label]) => ({ value, label }))} />}</Field>
        <div className="cc-grid2">
          {EXT_FIELDS[provider].map(([k, label, ph]) => <Field key={k} label={label}>{(id) => <TextInput id={id} value={ext[k] ?? ""} placeholder={ph} onChange={(e) => setExt({ ...ext, [k]: e.target.value })} />}</Field>)}
          <Field label={EXT_SECRET[provider]}>{(id) => <TextInput id={id} type="password" autoComplete="new-password" value={secret} placeholder={l.external ? "(unchanged)" : ""} onChange={(e) => setSecret(e.target.value)} />}</Field>
        </div>
        <span className="cc-inline">
          <Button kind="secondary" onClick={() => run(() => api.linkExternalVault(l.name, { provider, ...Object.fromEntries(EXT_FIELDS[provider].filter(([k]) => ext[k]?.trim()).map(([k]) => [k, ext[k].trim()])), ...(secret ? { secret } : {}) })
            .then(() => setSecret("")), "Linked " + l.name + " to " + EXT[provider] + ".")}>{l.external ? "Save link" : "Link"}</Button>
          {l.external && <Button kind="plain" onClick={() => setAsking("unlink")}>Unlink…</Button>}
        </span>
        {l.external && <div className="cc-inline cc-test">
          <TextInput aria-label="Secret name to test" placeholder="credential (secret name)" value={test.credential} onChange={(e) => setTest({ ...test, credential: e.target.value })} />
          <TextInput aria-label="Field to test" value={test.attribute} onChange={(e) => setTest({ ...test, attribute: e.target.value })} />
          <Button kind="secondary" disabled={!test.credential} onClick={() => api.testExternalVault(l.name, test.credential, test.attribute).then((r) =>
            setTest({ ...test, result: r.ok ? "Found (" + r.length + " characters). The value is not shown." : r.code + ": " + r.message }), (e) => setError(message(e)))}>Test reading</Button>
          {test.result && <span role="status">{test.result}</span>}
        </div>}
      </div>}
    </div>
  );
}

/** Create vault folder: you become its owner; consumers' agents may use its credentials. */
function CreateFolder({ api, onClose, onCreated }: { api: CrApi; onClose: () => void; onCreated: (t: string) => void }) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [consumers, setConsumers] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open title="Create vault folder" width={520} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!name.trim()} onClick={() => api.createVaultFolder(name.trim(), description, consumers).then(() => onCreated("Created the vault folder " + name.trim() + "; you are its owner."), (e) => setError(message(e)))}>Create vault folder</Button></>}>
      {error && <PageError text={error} />}
      <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="finance" onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Description">{(id) => <TextInput id={id} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <Field label="Consumers: their agents may use it (role:NAME, team:NAME, user:NAME)">{(id) => <TextInput id={id} value={consumers} placeholder="role:bot_runner, team:finance" onChange={(e) => setConsumers(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** The master key: where it comes from, its fingerprint, and rotating it (every stored value encrypted again). */
function VaultKey({ api, onClose, onNotice }: { api: CrApi; onClose: () => void; onNotice: (t: string) => void }) {
  const [st, setSt] = useState<VaultStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const read = useCallback(() => api.vaultStatus().then(setSt, (e) => setError(message(e))), [api]);
  useEffect(() => { read(); }, [read]);
  const rotate = () => api.rotateVaultKey().then((r) => { setConfirm(false); onNotice("Key rotated: " + r.re_encrypted + " values encrypted again. New fingerprint " + r.key_fingerprint + "."); read(); }, (e) => setError(message(e)));
  return (
    <Dialog open title="Vault key" width={560} onClose={onClose} footer={st?.can_rotate ? (confirm
      ? <><span>Rotate now? Agents keep working; every value is encrypted again.</span><Button kind="plain" onClick={() => setConfirm(false)}>Cancel</Button><Button kind="danger" onClick={rotate}>Rotate key</Button></>
      : <Button kind="danger" onClick={() => setConfirm(true)}>Rotate key</Button>) : null}>
      {error && <PageError text={error} />}
      {!st ? !error && <p className="st-muted" role="status">Loading…</p> : <>
        <dl className="cc-facts">
          <dt>Key from</dt><dd>{st.key_source === "file" ? "Key file in the data folder" : st.key_source === "environment" ? "OOA_MASTER_KEY environment variable" : st.key_source_detail}</dd>
          <dt>Fingerprint</dt><dd className="ui-mono">{st.key_fingerprint}</dd>
          <dt>Vault folders</dt><dd>{st.vault_folders}</dd>
          <dt>Credentials</dt><dd>{st.values}</dd>
          <dt>People's own values</dt><dd>{st.personal_values}</dd>
          <dt>Company vault links</dt><dd>{st.external_links}</dd>
        </dl>
        <p className="st-muted">{st.can_rotate ? "Rotating makes a new master key and encrypts every stored value again with it, in one transaction. The old key file is kept beside the new one until you remove it; back it up with the database."
          : "The key is kept outside the data folder, so it cannot be changed here. Stop One CommandCenter, run ooa vault rekey --new-key-env VAR, put the new key where the old one is kept, and start again."}</p>
      </>}
    </Dialog>
  );
}
