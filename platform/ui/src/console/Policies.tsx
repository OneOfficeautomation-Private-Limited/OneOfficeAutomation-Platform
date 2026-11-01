import { useCallback, useEffect, useMemo, useState } from "react";
import type { CodePolicy, CrApi, CrUser, Policy, RepoFolder } from "../api/cr";
import { Button, HelpButton, Menu, Select, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { CodeRules, codeReportCsv, ruleText, rulesToSave } from "./RepoTools";

/**
 * Administration > Policies, as AA's (item 5, drawn on the design canvas and approved 1 Nov 2026): named policies given
 * to folders. A folder uses its own policy of a kind, or the nearest folder's above it; one of each kind per folder.
 * Code analysis policies hold the rules agents are checked against at check-in, and whether errors stop it; folders with
 * none use Default code analysis (the policy that was a dialog under Automation). Seeing needs repo.view; changing,
 * settings.manage. A policy opens as its own page (Rules, Folders); deleting asks first in a bar.
 */
const KINDS: Record<string, string> = { code: "Code analysis", ai: "AI guardrails" };

const HELP: HelpTopic = {
  title: "Policies",
  about: "Rules agents follow, set once and given to folders. A folder uses its own policy, or the nearest folder's above it; one policy of each kind per folder.",
  items: [
    { name: "Default code analysis", text: "The rules for every folder without its own code analysis policy. It cannot be deleted; change its rules to change the default." },
    { name: "Create policy", text: "Makes a code analysis policy: its rules, whether errors stop check-in, and the folders it applies to." },
    { name: "Folders it applies to", text: "Agents in these folders and in the folders below them follow the policy." },
    { name: "What it does", text: "How many rules are on, and whether errors stop check-in." },
    { name: "Kind", text: "Shows only one kind of policy." },
    { name: "Report (CSV)", text: "Every agent checked against its folder's policy, one line per finding." },
    { name: "Delete…", text: "In a row's ⋯ menu, after asking: the folders then follow the nearest policy above them, or the default." },
  ] };
const HELP_POLICY: HelpTopic = {
  title: "Policy",
  about: "One policy: what it checks and where it applies.",
  items: [
    { name: "Rules", text: "Each rule used or not, an error or a warning, and its setting (for example the most steps an agent may have)." },
    { name: "Refuse check-ins that have errors", text: "An agent with a finding marked Error cannot be checked in to these folders; warnings are only shown." },
    { name: "Folders", text: "Add the folders the policy applies to; sub-folders follow it too. A folder can have only one code analysis policy." },
    { name: "Save changes", text: "Saves the policy. It is used from the next check-in or analysis; every change is recorded in the Audit Trail." },
  ] };

const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const when = (iso?: string) => iso ? new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" }) : "";
const summary = (p: Policy) => {
  if (p.kind !== "code") return "";
  const on = Object.values(p.rules.rules ?? {}).filter((r) => r.enabled).length, all = Object.keys(p.rules.rules ?? {}).length;
  return on + " of " + all + " rules on · " + (p.rules.enforce_at_checkin ? "errors stop check-in" : "findings shown only");
};

export function Policies({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Policy[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [open, setOpen] = useState<Policy | "new" | null>(null);
  const [asking, setAsking] = useState<Policy | null>(null);
  const edit = user.permissions.includes("settings.manage");
  const load = useCallback(() => { api.policies().then((l) => { setList(l); setError(null); }, (e) => setError(message(e))); }, [api]);
  useEffect(() => { load(); }, [load]);
  const text = q.trim().toLowerCase();
  const shown = (list ?? []).filter((p) => (!kind || p.kind === kind) && (!text || (p.name + " " + p.folders.join(" ")).toLowerCase().includes(text)));
  const remove = async (p: Policy) => {
    setAsking(null);
    try { await api.deletePolicy(p.id); onNotice("Deleted the policy " + p.name + "."); load(); } catch (e) { setError(message(e)); }
  };
  const menu = (p: Policy) => [{ key: "open", label: "Open", onSelect: () => setOpen(p) },
    ...(edit && !p.built_in ? [{ key: "delete", label: "Delete…", separatorBefore: true, onSelect: () => setAsking(p) }] : [])];
  if (open) return <PolicyPage api={api} policy={open === "new" ? null : open} edit={edit} taken={(list ?? []).filter((p) => p.id !== (open === "new" ? "" : open.id))}
    onClose={() => { setOpen(null); load(); }} onSaved={(t) => { onNotice(t); setOpen(null); load(); }} onDelete={(p) => { setOpen(null); setAsking(p); }} />;
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-policies-title">
      <header className="cc-list-head">
        <h1 id="cc-policies-title">Policies</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} polic{list.length === 1 ? "y" : "ies"} · rules agents follow, given to folders</span>}
        <span className="ov-fill" />
        {edit && <Menu label="Create policy" title="Create policy" trigger={<Button kind="primary">Create policy ▾</Button>}
          items={[{ key: "code", label: "Code analysis policy", description: "Rules agents are checked against at check-in", onSelect: () => setOpen("new") }]} />}
      </header>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-list-tools">
        <div className="cc-list-filters" role="group" aria-label="Filters">
          <span className="cc-list-search"><TextInput type="search" aria-label="Search policies" placeholder="Search policies" value={q} onChange={(e) => setQ(e.target.value)} /></span>
          <span className={"cc-chip" + (kind ? " is-on" : "")}><Select aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value)}
            options={[{ value: "", label: "Kind: Any" }, ...Object.entries(KINDS).map(([value, l]) => ({ value, label: "Kind: " + l }))]} /></span>
        </div>
        <div className="cc-list-right" role="toolbar" aria-label="Policies list tools">
          <Button kind="plain" onClick={() => codeReportCsv(api).catch((e) => setError(message(e)))}>Report (CSV)</Button>
          <RefreshButton onClick={load} />
        </div>
      </div>
      <div className="cc-list-panel">
        {asking && <div className="cc-list-bulk" role="alert"><span>Delete the policy {asking.name}? {asking.folders.length ? "Its folders then follow the nearest policy above them, or the default." : ""}</span>
          <Button kind="danger" onClick={() => remove(asking)}>Delete</Button><Button kind="plain" onClick={() => setAsking(null)}>Cancel</Button></div>}
        <div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Policies list">
            <thead><tr><th scope="col">Name</th><th scope="col">Kind</th><th scope="col">Folders it applies to</th><th scope="col">What it does</th><th scope="col">Updated</th>
              <th scope="col" className="cc-list-menu"><span className="st-sr">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((p) => <tr key={p.id} onClick={rowClick(() => setOpen(p))}>
                <td><strong>{p.name}</strong></td>
                <td><span className={"cc-pill is-" + (p.kind === "code" ? "active" : "pending")}>{KINDS[p.kind]}</span></td>
                <td>{p.built_in ? <span className="cc-none">Every folder without its own</span> : p.folders.length ? p.folders.map((f) => <span key={f} className="cc-tag">{f}</span>) : <span className="cc-none">None yet</span>}</td>
                <td>{summary(p)}</td>
                <td>{when(p.updated)}{p.updated_by ? ", " + p.updated_by : ""}</td>
                <td className="cc-list-menu"><RowMenu label={"Actions for " + p.name} items={menu(p)} /></td>
              </tr>)}
              {list && !shown.length && <tr className="is-static"><td colSpan={6} className="cc-none">No policies match.</td></tr>}
              {!list && !error && <tr className="is-static"><td colSpan={6} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      <p className="cc-list-hint">A folder uses its own policy, or the nearest folder's above it. Folders with no code analysis policy use Default code analysis.</p>
    </section>
  );
}

/** A policy's own page: Rules (and its name), Folders; the default policy keeps its name and covers every other folder. */
function PolicyPage({ api, policy, edit, taken, onClose, onSaved, onDelete }:
  { api: CrApi; policy: Policy | null; edit: boolean; taken: Policy[]; onClose: () => void; onSaved: (t: string) => void; onDelete: (p: Policy) => void }) {
  const [tab, setTab] = useState<"rules" | "folders">("rules");
  const [name, setName] = useState(policy?.name ?? "");
  const [description, setDescription] = useState(policy?.description ?? "");
  const [folders, setFolders] = useState<string[]>(policy?.folders ?? []);
  const [pol, setPol] = useState<CodePolicy | null>(policy ? policy.rules : null);
  const [text, setText] = useState<Record<string, string>>(policy ? ruleText(policy.rules) : {});
  const [repoFolders, setRepoFolders] = useState<RepoFolder[]>([]);
  const [adding, setAdding] = useState("");
  const [error, setError] = useState<string | null>(null);
  const builtIn = !!policy?.built_in;
  // a new policy starts from the default rules
  useEffect(() => { if (!policy) api.codePolicy().then((p) => { setPol(p); setText(ruleText(p)); }, (e) => setError(message(e))); }, [api, policy]);
  useEffect(() => { api.repoFolders().then(setRepoFolders, () => setRepoFolders([])); }, [api]);
  // a folder that already has a policy of this kind is not offered
  const used = useMemo(() => new Set(taken.filter((p) => p.kind === (policy?.kind ?? "code")).flatMap((p) => p.folders)), [taken, policy]);
  const offer = repoFolders.map((f) => f.path).filter((f) => !folders.includes(f) && !used.has(f));
  const save = async () => {
    if (!pol) return;
    try {
      await api.savePolicy(policy?.id ?? null, { name: name.trim(), kind: "code", description, folders, rules: rulesToSave(pol, text) });
      onSaved((policy ? "Saved the policy " : "Created the policy ") + name.trim() + ".");
    } catch (e) { setError(message(e)); }
  };
  const title = policy ? policy.name : "Create code analysis policy";
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={title}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Policies</button> ›</span>
        <h1>{title}</h1>
        <HelpButton topic={HELP_POLICY} />
        <span className="cc-pill is-active">{KINDS[policy?.kind ?? "code"]}</span>
        <span className="ov-fill" />
        {edit && <Button kind="primary" disabled={!pol || (!builtIn && !name.trim())} onClick={save}>{policy ? "Save changes" : "Create policy"}</Button>}
        {edit && policy && !builtIn && <RowMenu label={"More for " + policy.name} items={[{ key: "delete", label: "Delete…", onSelect: () => onDelete(policy) }]} />}
        <button type="button" className="cc-list-icon" aria-label="Close" title="Back to the policies" onClick={onClose}>×</button>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="ov-tabs cc-list-tabs cc-agent-tabs" role="tablist" aria-label="About this policy">
        {([["rules", "Rules"], ["folders", builtIn ? "Folders" : "Folders (" + folders.length + ")"]] as const).map(([k, l]) =>
          <button key={k} type="button" role="tab" className="ov-tab" aria-selected={tab === k} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === "rules" && <div className="cc-agent-tab">
        {!builtIn && <div className="cc-role-fields">
          <label className="cc-role-field">Name<TextInput value={name} disabled={!edit} maxLength={120} onChange={(e) => setName(e.target.value)} /></label>
          <label className="cc-role-field">What it is for<TextInput value={description} disabled={!edit} maxLength={500} onChange={(e) => setDescription(e.target.value)} /></label>
        </div>}
        {builtIn && <p className="cc-list-hint">The rules for every folder without its own code analysis policy.</p>}
        {!edit && <p className="cc-list-hint">Only people who may change settings can change policies.</p>}
        {pol ? <CodeRules pol={pol} text={text} edit={edit} onPol={setPol} onText={setText} /> : <p className="cc-none" role="status">Loading…</p>}
      </div>}
      {tab === "folders" && <div className="cc-agent-tab">
        {builtIn ? <p>Every folder without its own code analysis policy.</p> : <>
          <p className="cc-list-hint">Agents in these folders, and in the folders below them, follow this policy.</p>
          <ul className="cc-policy-folders" aria-label="Folders it applies to">
            {folders.map((f) => <li key={f}><span className="cc-tag">{f}</span>{edit && <Button kind="plain" onClick={() => setFolders(folders.filter((x) => x !== f))}>Remove</Button>}</li>)}
            {!folders.length && <li className="cc-none">No folders yet: the policy applies nowhere until you add one.</li>}
          </ul>
          {edit && <div className="cc-inline">
            <Select aria-label="Folder to add" value={adding} onChange={(e) => setAdding(e.target.value)}
              options={[{ value: "", label: offer.length ? "Choose a folder" : "No other folders" }, ...offer.map((f) => ({ value: f, label: f }))]} />
            <Button kind="secondary" disabled={!adding} onClick={() => { setFolders([...folders, adding]); setAdding(""); }}>Add folder</Button>
          </div>}
        </>}
      </div>}
    </div>
  );
}
