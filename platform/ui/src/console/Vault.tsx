import { useCallback, useEffect, useMemo, useState } from "react";
import type { CrApi, CrUser, VaultCredential, VaultFolder, VaultRequest, VaultStatus } from "../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../components";

/**
 * Administration > Credential vault, built as AA's Credentials page (checked against AA's docs, UiPath's credential
 * assets and stores, Blue Prism's Credential Manager, 31 Oct 2026): tabs Credentials, Vault folders (AA's lockers)
 * and My requests (values each person fills in for their own runs). Values are encrypted and never shown again unless
 * marked not secret; agents get them only while running, and every use is audited. A folder can read its values live
 * from a company vault (HashiCorp, Azure Key Vault, AWS Secrets Manager, CyberArk), as UiPath's credential stores.
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
const when = (t?: string | null) => t ? new Date(t).toLocaleString() : "";
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const list = (v?: string | string[] | null) => (Array.isArray(v) ? v : String(v ?? "").split(",")).map((x) => x.trim()).filter(Boolean);
const csvCell = (v: string) => /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;

/** One row of the Credentials tab: a credential in a folder, its attributes and whether the caller's own values are set. */
type CredRow = { folder: VaultFolder; name: string; attributes: string[]; userAttrs: string[]; mine: string[]; owner: string; updated?: string; updatedBy?: string };
function rowsOf(folders: VaultFolder[]): CredRow[] {
  const out: CredRow[] = [];
  for (const f of folders) {
    const info = Object.fromEntries((f.credential_info ?? []).map((c) => [c.name, c]));
    const names = new Set([...f.credentials.map((c) => c.name), ...Object.keys(info)]);
    for (const n of names) {
      const cs = f.credentials.filter((c) => c.name === n);
      const last = [...cs].sort((a, b) => String(b.updated).localeCompare(String(a.updated)))[0];
      out.push({ folder: f, name: n, attributes: cs.map((c) => c.attribute), userAttrs: info[n]?.user_attributes ?? [], mine: info[n]?.my_values ?? [],
        owner: info[n]?.owner ?? "", updated: last?.updated, updatedBy: last?.updated_by });
    }
  }
  return out.sort((a, b) => (a.folder.name + "/" + a.name).localeCompare(b.folder.name + "/" + b.name));
}
const writer = (f: VaultFolder) => f.my_role === "owner" || f.my_role === "manager";

export function Vault({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [tab, setTab] = useState<Tab>("credentials");
  const [folders, setFolders] = useState<VaultFolder[] | null>(null);
  const [requests, setRequests] = useState<VaultRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [f, setF] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string[]>([]);
  const [dialog, setDialog] = useState<null | { kind: "create-credential" | "create-folder" | "key" | "delete" } | { kind: "credential"; folder: VaultFolder; name: string } | { kind: "folder"; folder: VaultFolder }>(null);
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
      && (!f.folder || r.folder.name === f.folder) && (!f.mine || r.userAttrs.some((a) => !r.mine.includes(a))));
  }, [rows, f]);
  const chosen = rows.filter((r) => selected.includes(key(r)));
  const canDelete = (r: CredRow) => writer(r.folder) || (r.folder.my_role === "participant" && r.owner === user.username);
  const removeChosen = async () => {
    let done = 0, failed: string | null = null;
    for (const r of chosen) { try { await api.deleteCredential(r.folder.name, r.name); done++; } catch (e) { failed ??= key(r) + ": " + message(e); } }
    onNotice("Deleted " + done + " credential" + (done === 1 ? "" : "s") + "."); setError(failed); setSelected([]); load();
  };
  const exportCsv = () => {
    const text = [["Credential", "Vault folder", "Attributes", "Each person's own", "Owner", "Updated"],
      ...shown.map((r) => [r.name, r.folder.name, r.attributes.join(" "), r.userAttrs.join(" "), r.owner, when(r.updated)])].map((l) => l.map(csvCell).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
    a.download = "credentials.csv";
    document.body.appendChild(a); a.click(); a.remove();
    onNotice("Exported " + shown.length + " credential" + (shown.length === 1 ? "" : "s") + " (names only, never values).");
  };
  const canCreateCredential = (folders ?? []).some((x) => writer(x) || x.my_role === "participant");

  return (
    <section className="st-page cc-home" aria-labelledby="cc-vault-title">
      <header className="st-page-head">
        <div><h1 id="cc-vault-title">Credential vault</h1><p>Encrypted vault folders. Values are never shown again after saving; agents get them only while running, and every use is audited.</p></div>
        <span className="cc-inline">
          {can("lockers.manage") && <Button kind="secondary" onClick={() => setDialog({ kind: "key" })}>Vault key</Button>}
          {tab === "folders" && (can("lockers.manage") || can("lockers.own")) && <Button kind="primary" onClick={() => setDialog({ kind: "create-folder" })}>Create vault folder</Button>}
          {tab === "credentials" && canCreateCredential && <Button kind="primary" onClick={() => setDialog({ kind: "create-credential" })}>Create credential</Button>}
        </span>
      </header>
      <div className="st-page-tabs" role="tablist" aria-label="Credential vault">
        {([["credentials", "Credentials", rows.length], ["folders", "Vault folders", folders?.length ?? 0], ["requests", "My requests", requests.length]] as const).map(([t, l, n]) =>
          <button key={t} type="button" role="tab" className="st-page-tab" aria-selected={tab === t} onClick={() => { setTab(t); setSelected([]); }}>{l}{folders && <span className="cc-count">{n}</span>}</button>)}
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}

      {tab === "credentials" && <>
        <div className="cc-filters" role="group" aria-label="Filters">
          <TextInput type="search" aria-label="Search credentials" placeholder="Search credentials, attributes, owners" value={f.q ?? ""} onChange={(e) => setF({ ...f, q: e.target.value })} />
          <Select aria-label="Vault folder" value={f.folder ?? ""} onChange={(e) => setF({ ...f, folder: e.target.value })}
            options={[{ value: "", label: "Every vault folder" }, ...(folders ?? []).map((x) => ({ value: x.name, label: x.name }))]} />
          <label className="st-check-line"><input type="checkbox" checked={!!f.mine} onChange={(e) => setF({ ...f, mine: e.target.checked ? "1" : "" })} /> Needs my own value</label>
          {Object.values(f).some(Boolean) && <Button kind="plain" onClick={() => setF({})}>Clear filters</Button>}
        </div>
        <div className="cc-bar" role="toolbar" aria-label="Credential actions">
          {chosen.length > 0 && <span className="cc-chosen">{chosen.length} selected</span>}
          <Button kind="danger" disabled={!chosen.length || !chosen.every(canDelete)} onClick={() => setDialog({ kind: "delete" })}>Delete</Button>
          <span className="st-bar-fill" />
          <Button kind="plain" onClick={load}>Refresh</Button>
          <Button kind="secondary" onClick={exportCsv}>Export CSV</Button>
        </div>
        <div className="cc-table-wrap">
          <table className="st-table cc-table" aria-label="Credentials list">
            <thead><tr><th scope="col" className="cc-check"><span className="st-sr">Select</span></th><th scope="col">Credential</th><th scope="col">Vault folder</th>
              <th scope="col">Attributes</th><th scope="col">Each person's own</th><th scope="col">Owner</th><th scope="col">Updated</th></tr></thead>
            <tbody>
              {shown.map((r) => (
                <tr key={key(r)} className={selected.includes(key(r)) ? "is-selected" : ""} onClick={(e) => { if (!(e.target as HTMLElement).closest("input")) setDialog({ kind: "credential", folder: r.folder, name: r.name }); }}>
                  <td className="cc-check"><input type="checkbox" aria-label={"Select " + key(r)} checked={selected.includes(key(r))}
                    onChange={(e) => setSelected(e.target.checked ? [...selected, key(r)] : selected.filter((x) => x !== key(r)))} /></td>
                  <td>{r.name}</td>
                  <td>{r.folder.name}{r.folder.external && <span className="cc-tag" title="Values are read live from the company vault">{EXT[r.folder.external.provider] ?? r.folder.external.provider}</span>}</td>
                  <td>{r.attributes.join(", ")}</td>
                  <td>{r.userAttrs.map((a) => a + (r.mine.includes(a) ? " (yours set)" : " (yours missing)")).join(", ")}</td>
                  <td>{r.owner}</td>
                  <td>{r.updated ? when(r.updated) + (r.updatedBy ? " by " + r.updatedBy : "") : ""}</td>
                </tr>
              ))}
              {folders && !shown.length && <tr><td colSpan={7} className="st-muted">{rows.length ? "No credentials match." : "No credentials you can see yet."}</td></tr>}
              {!folders && !error && <tr><td colSpan={7} className="st-muted" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </>}

      {tab === "folders" && (
        <div className="cc-table-wrap">
          <table className="st-table cc-table" aria-label="Vault folders list">
            <thead><tr><th scope="col">Vault folder</th><th scope="col">Description</th><th scope="col">Your place</th><th scope="col">Owners</th>
              <th scope="col">Consumers</th><th scope="col">Credentials</th><th scope="col">Company vault</th></tr></thead>
            <tbody>
              {(folders ?? []).map((x) => (
                <tr key={x.name} onClick={() => setDialog({ kind: "folder", folder: x })}>
                  <td>{x.name}</td><td>{x.description ?? ""}</td><td>{PLACE[x.my_role]}</td><td>{list(x.owners).join(", ")}</td>
                  <td>{list(x.consumers).join(", ") || "None"}</td><td>{new Set(x.credentials.map((c) => c.name)).size}</td>
                  <td>{x.external ? EXT[x.external.provider] ?? x.external.provider : "Not linked"}</td>
                </tr>
              ))}
              {folders && !folders.length && <tr><td colSpan={7} className="st-muted">No vault folders you own or use yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === "requests" && <Requests api={api} requests={requests} onSaved={(t) => { onNotice(t); load(); }} />}

      {dialog?.kind === "delete" && (
        <Dialog open title="Delete credentials" width={460} onClose={() => setDialog(null)} footer={<>
          <Button kind="plain" onClick={() => setDialog(null)}>Cancel</Button>
          <Button kind="danger" onClick={() => { setDialog(null); removeChosen(); }}>Delete</Button></>}>
          <p>Delete {chosen.map(key).join(", ")} with all their values, everyone's own values too? Agents that use them fail until they are made again.</p>
        </Dialog>
      )}
      {dialog?.kind === "credential" && <CredentialDialog key={dialog.folder.name + "/" + dialog.name} api={api} folder={dialog.folder} name={dialog.name}
        onClose={() => setDialog(null)} onChanged={(t) => { onNotice(t); load(); }} />}
      {dialog?.kind === "create-credential" && <CreateCredential api={api} folders={(folders ?? []).filter((x) => writer(x) || x.my_role === "participant")}
        onClose={() => setDialog(null)} onCreated={(t) => { onNotice(t); setDialog(null); load(); }} />}
      {dialog?.kind === "create-folder" && <CreateFolder api={api} onClose={() => setDialog(null)} onCreated={(t) => { onNotice(t); setDialog(null); setTab("folders"); load(); }} />}
      {dialog?.kind === "folder" && <FolderDialog key={dialog.folder.name} api={api} folder={dialog.folder} onClose={() => setDialog(null)}
        onChanged={(t, close) => { onNotice(t); if (close) setDialog(null); load(); }} />}
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
    <div>
      <p className="st-muted">Agents that run as you read these values from the vault. Fill them in once; you can change them later in the credential's details.</p>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table cc-table" aria-label="My requests list">
        <thead><tr><th scope="col">Credential</th><th scope="col">Attribute</th><th scope="col">Your value</th><th scope="col"><span className="st-sr">Save</span></th></tr></thead>
        <tbody>
          {requests.map((q) => (
            <tr key={k(q)}>
              <td><span className="ui-mono">{q.locker}/{q.name}</span>{q.credential_description && <div className="st-muted">{q.credential_description}</div>}</td>
              <td>{q.attribute}{q.password_only && <span className="cc-tag">password fields only</span>}{q.description && <div className="st-muted">{q.description}</div>}</td>
              <td><TextInput type="password" autoComplete="new-password" aria-label={"Your " + q.attribute + " for " + q.locker + "/" + q.name} value={values[k(q)] ?? ""}
                onChange={(e) => setValues({ ...values, [k(q)]: e.target.value })} /></td>
              <td><Button kind="primary" disabled={!values[k(q)]} onClick={() => api.setMyValue(q.locker, q.name, q.attribute, values[k(q)])
                .then(() => { setValues({ ...values, [k(q)]: "" }); onSaved("Saved your " + q.attribute + " for " + q.locker + "/" + q.name + "."); }, (e) => setError(message(e)))}>Save</Button></td>
            </tr>
          ))}
          {!requests.length && <tr><td colSpan={4} className="st-muted">Nothing to fill in.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/** Create credential: a folder, a name and its attributes, each with a value or left for each person to fill in. */
function CreateCredential({ api, folders, onClose, onCreated }: { api: CrApi; folders: VaultFolder[]; onClose: () => void; onCreated: (t: string) => void }) {
  const [folder, setFolder] = useState(folders[0]?.name ?? "");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [attrs, setAttrs] = useState<{ attribute: string; value: string; own: boolean }[]>([{ attribute: "username", value: "", own: false }, { attribute: "password", value: "", own: false }]);
  const [error, setError] = useState<string | null>(null);
  const set = (i: number, patch: Partial<(typeof attrs)[number]>) => setAttrs(attrs.map((a, j) => j === i ? { ...a, ...patch } : a));
  const ok = /^[A-Za-z0-9_.-]{1,120}$/.test(name.trim()) && attrs.length > 0 && attrs.every((a) => /^[A-Za-z0-9_.-]{1,80}$/.test(a.attribute.trim()) && (a.own || a.value));
  const create = async () => {
    try {
      const shared = attrs.filter((a) => !a.own);
      for (const a of shared) await api.setCredentialValue(folder, name.trim(), a.attribute.trim(), a.value);
      const own = attrs.filter((a) => a.own).map((a) => a.attribute.trim());
      if (own.length || description) await api.saveCredentialDetails(folder, name.trim(), { description, user_attributes: own });
      onCreated("Created the credential " + folder + "/" + name.trim() + ". Its values are encrypted.");
    } catch (e) { setError(message(e)); }
  };
  return (
    <Dialog open title="Create credential" width={640} onClose={onClose} footer={<>
      <Button kind="plain" onClick={onClose}>Cancel</Button>
      <Button kind="primary" disabled={!ok || !folder} onClick={create}>Create credential</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Vault folder">{(id) => <Select id={id} value={folder} onChange={(e) => setFolder(e.target.value)} options={folders.map((x) => ({ value: x.name, label: x.name }))} />}</Field>
        <Field label="Credential name">{(id) => <TextInput id={id} value={name} placeholder="vendor_api" maxLength={120} onChange={(e) => setName(e.target.value)} />}</Field>
      </div>
      <Field label="Description">{(id) => <TextInput id={id} value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />}</Field>
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

/** A credential's attributes with their flags, an owner and description; the caller's own values; changing a value. */
function CredentialDialog({ api, folder, name, onClose, onChanged }: { api: CrApi; folder: VaultFolder; name: string; onClose: () => void; onChanged: (t: string) => void }) {
  const [c, setC] = useState<VaultCredential | null>(null);
  const [info, setInfo] = useState<Record<string, { description: string; masked: boolean; password_only: boolean }>>({});
  const [meta, setMeta] = useState({ description: "", owner: "", own: "" });
  const [values, setValues] = useState<Record<string, string>>({});
  const [shown, setShown] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const read = useCallback(() => api.credential(folder.name, name).then((x) => {
    setC(x);
    setInfo(Object.fromEntries(x.attributes.filter((a) => !a.user_provided).map((a) => [a.attribute, { description: a.description ?? "", masked: a.masked !== false, password_only: !!a.password_only }])));
    setMeta({ description: x.description ?? "", owner: x.owner ?? "", own: x.attributes.filter((a) => a.user_provided).map((a) => a.attribute).join(", ") });
  }, (e) => setError(message(e))), [api, folder.name, name]);
  useEffect(() => { read(); }, [read]);
  const run = async (fn: () => Promise<unknown>, done: string) => { try { await fn(); setError(null); onChanged(done); await read(); } catch (e) { setError(message(e)); } };
  if (!c) return <Dialog open title={"Credential " + folder.name + "/" + name} width={760} onClose={onClose} footer={null}>{error ? <p className="st-alert" role="alert">{error}</p> : <p className="st-muted" role="status">Loading…</p>}</Dialog>;
  const ownerEditable = writer(folder);
  const ref = folder.name + "/" + name;
  const save = () => run(() => api.saveCredentialDetails(folder.name, name, { description: meta.description, user_attributes: list(meta.own), attributes_info: info,
    ...(ownerEditable && meta.owner.trim() && meta.owner.trim() !== c.owner ? { owner: meta.owner.trim() } : {}) }), "Saved the details of " + ref + ".");
  return (
    <Dialog open title={"Credential " + ref} width={820} onClose={onClose} footer={c.can_change || ownerEditable ? <>
      {c.can_change && <Button kind="danger" onClick={() => run(() => api.deleteCredential(folder.name, name), "Deleted " + ref + ".").then(onClose)}>Delete</Button>}
      <span className="st-bar-fill" /><Button kind="primary" onClick={save}>Save details</Button></> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <table className="st-table" aria-label="Attributes">
        <thead><tr><th scope="col">Attribute</th><th scope="col">Value</th><th scope="col">Description</th><th scope="col">Masked</th><th scope="col">Password fields only</th><th scope="col">Updated</th></tr></thead>
        <tbody>
          {c.attributes.map((a) => {
            const i = info[a.attribute];
            return (
              <tr key={a.attribute}>
                <td className="ui-mono">{ref}/{a.attribute}</td>
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
      <p className="st-muted">Masked values are never shown (the default). Clear Masked for values that are not secret, such as a user name, so owners and managers can look them up; each look is audited. Password fields only: agents may type the value into password fields only.</p>
      <div className="cc-grid2">
        <Field label="Description">{(id) => <TextInput id={id} value={meta.description} disabled={!c.can_change} onChange={(e) => setMeta({ ...meta, description: e.target.value })} />}</Field>
        <Field label="Owner">{(id) => <TextInput id={id} value={meta.owner} disabled={!ownerEditable} onChange={(e) => setMeta({ ...meta, owner: e.target.value })} />}</Field>
      </div>
      <Field label="Each person's own attributes (separated by commas)">{(id) => <TextInput id={id} value={meta.own} disabled={!c.can_change} placeholder="password" onChange={(e) => setMeta({ ...meta, own: e.target.value })} />}</Field>
      {c.attributes.some((a) => a.user_provided) && <>
        <h3 className="cc-log-title">Your values</h3>
        {c.attributes.filter((a) => a.user_provided).map((a) => (
          <span key={a.attribute} className="cc-inline cc-myvalue">
            <TextInput type="password" autoComplete="new-password" aria-label={"Your " + a.attribute} value={values["mine:" + a.attribute] ?? ""}
              placeholder={c.my_values.includes(a.attribute) ? "your value is set; type to replace" : "your " + a.attribute} onChange={(e) => setValues({ ...values, ["mine:" + a.attribute]: e.target.value })} />
            <Button kind="secondary" disabled={!values["mine:" + a.attribute]} onClick={() => run(() => api.setMyValue(folder.name, name, a.attribute, values["mine:" + a.attribute])
              .then(() => setValues({ ...values, ["mine:" + a.attribute]: "" })), "Saved your " + a.attribute + ".")}>Save mine</Button>
            {c.my_values.includes(a.attribute) && <Button kind="plain" onClick={() => run(() => api.clearMyValue(folder.name, name, a.attribute), "Cleared your " + a.attribute + ".")}>Clear mine</Button>}
          </span>
        ))}
        <p className="st-muted">A run reads a person's own attribute from whoever it runs as (the run-as user, or whoever started it).</p>
      </>}
    </Dialog>
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
      {error && <p className="st-alert" role="alert">{error}</p>}
      <Field label="Name">{(id) => <TextInput id={id} value={name} placeholder="finance" onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Description">{(id) => <TextInput id={id} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <Field label="Consumers: their agents may use it (role:NAME, team:NAME, user:NAME)">{(id) => <TextInput id={id} value={consumers} placeholder="role:bot_runner, team:finance" onChange={(e) => setConsumers(e.target.value)} />}</Field>
    </Dialog>
  );
}

/** A vault folder: members by place (AA's owners, managers, participants, consumers), and its company vault link. */
function FolderDialog({ api, folder: l, onClose, onChanged }: { api: CrApi; folder: VaultFolder; onClose: () => void; onChanged: (t: string, close: boolean) => void }) {
  const [m, setM] = useState({ owners: list(l.owners).join(", "), managers: l.managers.join(", "), participants: l.participants.join(", "), consumers: list(l.consumers).join(", ") });
  const e0 = l.external ?? { provider: "hashicorp" };
  const [provider, setProvider] = useState(e0.provider);
  const [ext, setExt] = useState<Record<string, string>>(Object.fromEntries(Object.entries(e0).filter(([k, v]) => k !== "provider" && typeof v === "string")) as Record<string, string>);
  const [secret, setSecret] = useState("");
  const [test, setTest] = useState({ credential: "", attribute: "password", result: "" });
  const [error, setError] = useState<string | null>(null);
  const run = async (fn: () => Promise<unknown>, done: string, close = false) => { try { await fn(); setError(null); onChanged(done, close); } catch (e) { setError(message(e)); } };
  const empty = l.credentials.length === 0;
  return (
    <Dialog open title={"Vault folder " + l.name} width={720} onClose={onClose} footer={l.can_manage ? <>
      {empty && <Button kind="danger" onClick={() => run(() => api.deleteVaultFolder(l.name), "Deleted the vault folder " + l.name + ".", true)}>Delete folder</Button>}
      <span className="st-bar-fill" />
      <Button kind="primary" onClick={() => run(() => api.setVaultMembers(l.name, m), "Saved the members of " + l.name + ".")}>Save members</Button></> : null}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <p className="st-muted">{l.description ?? ""} Your place: {PLACE[l.my_role]}.</p>
      <h3 className="cc-log-title">Members</h3>
      <Field label="Owners (user names): everything, members included">{(id) => <TextInput id={id} value={m.owners} disabled={!l.can_manage} onChange={(e) => setM({ ...m, owners: e.target.value })} />}</Field>
      <Field label="Managers: add, change and delete every credential">{(id) => <TextInput id={id} value={m.managers} disabled={!l.can_manage} placeholder="user:carl, team:finance-admins" onChange={(e) => setM({ ...m, managers: e.target.value })} />}</Field>
      <Field label="Participants: see the folder, add credentials and change their own">{(id) => <TextInput id={id} value={m.participants} disabled={!l.can_manage} placeholder="team:finance" onChange={(e) => setM({ ...m, participants: e.target.value })} />}</Field>
      <Field label="Consumers: their agents may use the credentials">{(id) => <TextInput id={id} value={m.consumers} disabled={!l.can_manage} placeholder="role:bot_runner, team:finance" onChange={(e) => setM({ ...m, consumers: e.target.value })} />}</Field>
      <p className="st-muted">Managers, participants and consumers can be people (user:jane), roles (role:bot_runner) or teams (team:finance).</p>
      {l.can_manage && <>
        <h3 className="cc-log-title">Company vault</h3>
        <p className="st-muted">Keep the secrets in your company vault: agents still ask this folder, so members and the Audit Trail work as before, and the value is read live from there as credential/attribute.</p>
        <Field label="Provider">{(id) => <Select id={id} value={provider} onChange={(e) => setProvider(e.target.value)} options={Object.entries(EXT).map(([value, label]) => ({ value, label }))} />}</Field>
        <div className="cc-grid2">
          {EXT_FIELDS[provider].map(([k, label, ph]) => <Field key={k} label={label}>{(id) => <TextInput id={id} value={ext[k] ?? ""} placeholder={ph} onChange={(e) => setExt({ ...ext, [k]: e.target.value })} />}</Field>)}
          <Field label={EXT_SECRET[provider]}>{(id) => <TextInput id={id} type="password" autoComplete="new-password" value={secret} placeholder={l.external ? "(unchanged)" : ""} onChange={(e) => setSecret(e.target.value)} />}</Field>
        </div>
        <span className="cc-inline">
          <Button kind="secondary" onClick={() => run(() => api.linkExternalVault(l.name, { provider, ...Object.fromEntries(EXT_FIELDS[provider].filter(([k]) => ext[k]?.trim()).map(([k]) => [k, ext[k].trim()])), ...(secret ? { secret } : {}) })
            .then(() => setSecret("")), "Linked " + l.name + " to " + EXT[provider] + ".")}>{l.external ? "Save link" : "Link"}</Button>
          {l.external && <Button kind="plain" onClick={() => run(() => api.unlinkExternalVault(l.name), "Unlinked " + l.name + " from the company vault.")}>Unlink</Button>}
        </span>
        {l.external && <div className="cc-inline cc-test">
          <TextInput aria-label="Secret name to test" placeholder="credential (secret name)" value={test.credential} onChange={(e) => setTest({ ...test, credential: e.target.value })} />
          <TextInput aria-label="Field to test" value={test.attribute} onChange={(e) => setTest({ ...test, attribute: e.target.value })} />
          <Button kind="secondary" disabled={!test.credential} onClick={() => api.testExternalVault(l.name, test.credential, test.attribute).then((r) =>
            setTest({ ...test, result: r.ok ? "Found (" + r.length + " characters). The value is not shown." : r.code + ": " + r.message }), (e) => setError(message(e)))}>Test reading</Button>
          {test.result && <span role="status">{test.result}</span>}
        </div>}
      </>}
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
      {error && <p className="st-alert" role="alert">{error}</p>}
      {!st ? !error && <p className="st-muted" role="status">Loading…</p> : <>
        <dl className="cc-facts">
          <dt>Key from</dt><dd>{st.key_source === "file" ? "Key file in the data folder" : st.key_source === "environment" ? "OOA_MASTER_KEY environment variable" : st.key_source_detail}</dd>
          <dt>Fingerprint</dt><dd className="ui-mono">{st.key_fingerprint}</dd>
          <dt>Vault folders</dt><dd>{st.vault_folders}</dd>
          <dt>Shared Global values</dt><dd>{st.values}</dd>
          <dt>People's own values</dt><dd>{st.personal_values}</dd>
          <dt>Company vault links</dt><dd>{st.external_links}</dd>
        </dl>
        <p className="st-muted">{st.can_rotate ? "Rotating makes a new master key and encrypts every stored value again with it, in one transaction. The old key file is kept beside the new one until you remove it; back it up with the database."
          : "The key is kept outside the data folder, so it cannot be changed here. Stop the Command Center, run ooa vault rekey --new-key-env VAR, put the new key where the old one is kept, and start again."}</p>
      </>}
    </Dialog>
  );
}
