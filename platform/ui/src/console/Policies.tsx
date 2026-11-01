import { useCallback, useEffect, useMemo, useState } from "react";
import type { CodePolicy, CrApi, CrUser, ModelConnection, Policy, RepoFolder } from "../api/cr";
import { Button, HelpButton, Menu, Select, TextInput, type HelpTopic } from "../components";
import { RefreshButton, RowMenu, rowClick } from "./ListParts";
import { CodeRules, codeReportCsv, ruleText, rulesToSave } from "./RepoTools";

/**
 * Administration > Policies, as AA's (item 5, drawn on the design canvas and approved 1 Nov 2026): named policies given
 * to folders. A folder uses its own policy of a kind, or the nearest folder's above it; one of each kind per folder.
 * Code analysis policies hold the rules agents are checked against at check-in, and whether errors stop it; folders with
 * none use Default code analysis (the policy that was a dialog under Automation). Seeing needs repo.view; changing,
 * settings.manage. A policy opens as its own page (Rules, Folders); deleting asks first in a bar.
 * Step 2 (1 Nov 2026): AI guardrails policies, sent with every run of an agent in their folders: personal data always
 * masked, only the model connections ticked, and answers checked for harmful content (logged, or the step stopped).
 */
type Guardrails = { mask: boolean; models: string[]; content: "off" | "log" | "block" };
const CONTENT: Record<string, string> = { off: "Off", log: "Log only", block: "Block" };
const KINDS: Record<string, string> = { code: "Code analysis", ai: "AI guardrails" };

const HELP: HelpTopic = {
  title: "Policies",
  about: "Rules agents follow, set once and given to folders. A folder uses its own policy, or the nearest folder's above it; one policy of each kind per folder.",
  items: [
    { name: "Default code analysis", text: "The rules for every folder without its own code analysis policy. It cannot be deleted; change its rules to change the default." },
    { name: "Create policy", text: "Makes a code analysis policy (its rules, whether errors stop check-in) or AI guardrails (masking, models allowed, content check), and the folders it applies to." },
    { name: "AI guardrails", text: "Apply to every AI step of the agents in their folders when they run: personal data masked, only the models ticked, answers checked for harmful content." },
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
    { name: "Mask personal data", text: "AI guardrails: contact details, ID, account and card numbers are replaced before they reach a model, and put back in the answer. AI steps cannot switch it off." },
    { name: "Models agents may use", text: "AI guardrails: only the model connections ticked; a step asking for another, or with a model typed on it, stops with a clear message. None ticked: any model." },
    { name: "Content check", text: "AI guardrails: Log only notes a harmful answer in the run log; Block stops the step. It uses the model provider's moderation service (OpenAI and Azure OpenAI)." },
    { name: "Folders", text: "Add the folders the policy applies to; sub-folders follow it too. A folder can have one policy of each kind." },
    { name: "Save changes", text: "Saves the policy. It is used from the next check-in or analysis; every change is recorded in the Audit Trail." },
  ] };

const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const when = (iso?: string) => iso ? new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" }) : "";
const summary = (p: Policy) => {
  if (p.kind === "ai") {
    const g = p.rules as unknown as Guardrails;
    return [g.mask ? "masks personal data" : "no masking", g.models?.length ? g.models.length + " model" + (g.models.length === 1 ? "" : "s") + " allowed" : "any model",
      g.content === "block" ? "blocks harmful answers" : g.content === "log" ? "logs harmful answers" : "no content check"].join(" · ");
  }
  const on = Object.values(p.rules.rules ?? {}).filter((r) => r.enabled).length, all = Object.keys(p.rules.rules ?? {}).length;
  return on + " of " + all + " rules on · " + (p.rules.enforce_at_checkin ? "errors stop check-in" : "findings shown only");
};

export function Policies({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [list, setList] = useState<Policy[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [open, setOpen] = useState<Policy | "code" | "ai" | null>(null);
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
  if (open) return <PolicyPage api={api} policy={typeof open === "string" ? null : open} kind={typeof open === "string" ? open : open.kind} edit={edit}
    taken={(list ?? []).filter((p) => typeof open === "string" || p.id !== open.id)}
    onClose={() => { setOpen(null); load(); }} onSaved={(t) => { onNotice(t); setOpen(null); load(); }} onDelete={(p) => { setOpen(null); setAsking(p); }} />;
  return (
    <section className="st-page cc-home cc-list" aria-labelledby="cc-policies-title">
      <header className="cc-list-head">
        <h1 id="cc-policies-title">Policies</h1>
        <HelpButton topic={HELP} />
        {list && <span className="cc-list-count">{list.length} polic{list.length === 1 ? "y" : "ies"} · rules agents follow, given to folders</span>}
        <span className="ov-fill" />
        {edit && <Menu label="Create policy" title="Create policy" trigger={<Button kind="primary">Create policy ▾</Button>}
          items={[{ key: "code", label: "Code analysis policy", description: "Rules agents are checked against at check-in", onSelect: () => setOpen("code") },
            { key: "ai", label: "AI guardrails policy", description: "Masking, models allowed and content check for AI steps", onSelect: () => setOpen("ai") }]} />}
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
      <p className="cc-list-hint">A folder uses its own policy, or the nearest folder's above it. Folders with no code analysis policy use Default code analysis; with no AI guardrails, AI steps keep their own settings.</p>
    </section>
  );
}

/** A policy's own page: Rules (and its name), Folders; the default policy keeps its name and covers every other folder. */
function PolicyPage({ api, policy, kind, edit, taken, onClose, onSaved, onDelete }:
  { api: CrApi; policy: Policy | null; kind: "code" | "ai"; edit: boolean; taken: Policy[]; onClose: () => void; onSaved: (t: string) => void; onDelete: (p: Policy) => void }) {
  const [tab, setTab] = useState<"rules" | "folders">("rules");
  const [name, setName] = useState(policy?.name ?? "");
  const [description, setDescription] = useState(policy?.description ?? "");
  const [folders, setFolders] = useState<string[]>(policy?.folders ?? []);
  const [pol, setPol] = useState<CodePolicy | null>(policy && kind === "code" ? policy.rules : null);
  const [guard, setGuard] = useState<Guardrails>(policy && kind === "ai" ? policy.rules as unknown as Guardrails : { mask: true, models: [], content: "off" });
  const [models, setModels] = useState<ModelConnection[]>([]);
  const [text, setText] = useState<Record<string, string>>(policy ? ruleText(policy.rules) : {});
  const [repoFolders, setRepoFolders] = useState<RepoFolder[]>([]);
  const [adding, setAdding] = useState("");
  const [error, setError] = useState<string | null>(null);
  const builtIn = !!policy?.built_in;
  // a new code analysis policy starts from the default rules; AI guardrails list the model connections to choose from
  useEffect(() => { if (!policy && kind === "code") api.codePolicy().then((p) => { setPol(p); setText(ruleText(p)); }, (e) => setError(message(e))); }, [api, policy, kind]);
  useEffect(() => { if (kind === "ai") api.modelConnections().then(setModels, () => setModels([])); }, [api, kind]);
  useEffect(() => { api.repoFolders().then(setRepoFolders, () => setRepoFolders([])); }, [api]);
  // a folder that already has a policy of this kind is not offered
  const used = useMemo(() => new Set(taken.filter((p) => p.kind === kind).flatMap((p) => p.folders)), [taken, kind]);
  const offer = repoFolders.map((f) => f.path).filter((f) => !folders.includes(f) && !used.has(f));
  const save = async () => {
    if (kind === "code" && !pol) return;
    try {
      await api.savePolicy(policy?.id ?? null, { name: name.trim(), kind, description, folders, rules: kind === "code" ? rulesToSave(pol!, text) : guard });
      onSaved((policy ? "Saved the policy " : "Created the policy ") + name.trim() + ".");
    } catch (e) { setError(message(e)); }
  };
  const title = policy ? policy.name : kind === "code" ? "Create code analysis policy" : "Create AI guardrails policy";
  return (
    <div className="cc-agent" role="dialog" aria-modal="false" aria-label={title}>
      <div className="cc-agent-head">
        <span className="cc-crumbs-path"><button type="button" className="cc-link" onClick={onClose}>Policies</button> ›</span>
        <h1>{title}</h1>
        <HelpButton topic={HELP_POLICY} />
        <span className={"cc-pill is-" + (kind === "code" ? "active" : "pending")}>{KINDS[kind]}</span>
        <span className="ov-fill" />
        {edit && <Button kind="primary" disabled={(kind === "code" && !pol) || (!builtIn && !name.trim())} onClick={save}>{policy ? "Save changes" : "Create policy"}</Button>}
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
        {kind === "ai" ? <div className="cc-guard" role="group" aria-label="AI guardrails">
          <label className="st-check-line"><input type="checkbox" disabled={!edit} checked={guard.mask} onChange={(e) => setGuard({ ...guard, mask: e.target.checked })} /> Mask personal data before it reaches a model</label>
          <p className="cc-list-hint">Contact details, ID, account and card numbers are replaced with placeholders, and put back in the answer. AI steps cannot switch it off.</p>
          <fieldset className="cc-guard-models"><legend>Models agents may use</legend>
            {models.map((m) => <label key={m.name} className="st-check-line"><input type="checkbox" disabled={!edit} checked={guard.models.includes(m.name)}
              onChange={(e) => setGuard({ ...guard, models: e.target.checked ? [...guard.models, m.name] : guard.models.filter((x) => x !== m.name) })} /> {m.name} <span className="cc-none">({m.provider}{m.model ? ", " + m.model : ""})</span></label>)}
            {!models.length && <p className="cc-none">No model connections yet.</p>}
            <p className="cc-list-hint">{guard.models.length ? "Any other model stops the step with a clear message." : "None ticked: any model."}</p>
          </fieldset>
          <label className="cc-role-field">Content check<Select disabled={!edit} value={guard.content} onChange={(e) => setGuard({ ...guard, content: e.target.value as Guardrails["content"] })}
            options={Object.entries(CONTENT).map(([value, label]) => ({ value, label }))} /></label>
          <p className="cc-list-hint">Checks answers for harmful content with the provider's moderation service: Log only notes it in the run log, Block stops the step.</p>
        </div>
          : pol ? <CodeRules pol={pol} text={text} edit={edit} onPol={setPol} onText={setText} /> : <p className="cc-none" role="status">Loading…</p>}
      </div>}
      {tab === "folders" && <div className="cc-agent-tab">
        {builtIn ? <p>Every folder without its own code analysis policy.</p> : <>
          <p className="cc-list-hint">Agents in these folders, and in the folders below them, follow this policy{kind === "ai" ? " when they run" : ""}.</p>
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
