import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CrApi, CrUser, MailAccount, MailConditions, MailCondition, MailDetect, MailProvider, MailRule, MailStatus, MailTry } from "../api/cr";
import { Button, Dialog, Field, IconButton, MicrosoftSignInDialog, PrincipalPicker, principalDirectory, Select, TextInput, VaultPicker, vaultRefsFrom, type VaultRef } from "../components";
import { RefreshButton, when } from "./ListParts";

/**
 * Mail accounts (MA-3; the user's OK "Yes, build it", 15 Nov 2026), on Manage > Connections: each mailbox set up once
 * and chosen by name by processes, triggers and agents. Laid out as the design plan's MA-List (the list with one
 * account's details), MA-Vendor (type the address: the vendor is found), MA-Rules (an account's rules in order and
 * Not matched) and MA-RuleEdit (a rule's conditions and Try the rule) artboards.
 */

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
export const STATUS: Record<MailStatus, [string, string]> = {
  new: ["is-disabled", "Not checked yet"], working: ["is-success", "Working"], sign_in_expired: ["is-failed", "Sign-in expired"],
  wrong_password: ["is-failed", "Wrong password"], failed: ["is-failed", "Not reached"],
};
const SIGN_IN: Record<string, string> = { microsoft: "Sign in with Microsoft", microsoft_app: "Your own Microsoft app", app_password: "App password", password: "Password" };

export function MailStatusPill({ status }: { status: MailStatus }) {
  const [cls, text] = STATUS[status] ?? STATUS.failed;
  return <span className={"cc-pill " + cls}>{text}</span>;
}

/** The mail accounts list with the chosen account's details and what can be done with it. */
export function MailAccountsPanel({ api, user, onNotice }: { api: CrApi; user: CrUser; onNotice: (t: string) => void }) {
  const [data, setData] = useState<{ accounts: MailAccount[]; providers: MailProvider[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [edit, setEdit] = useState<MailAccount | "new" | null>(null);
  const [rules, setRules] = useState<MailAccount | null>(null);
  const [ask, setAsk] = useState<MailAccount | null>(null);
  const [busy, setBusy] = useState(false);
  const manage = user.permissions.includes("globals.manage");
  const load = useCallback(() => api.mailAccounts().then((d) => { setData(d); setError(null); }, (e) => setError(message(e))), [api]);
  useEffect(() => { void load(); }, [load]);
  const a = data?.accounts.find((x) => x.id === chosen) ?? data?.accounts[0] ?? null;
  const test = async (x: MailAccount) => {
    setBusy(true);
    try { const r = await api.testMailAccount(x.id); onNotice(r.ok ? r.message : "Not reached: " + r.message); await load(); } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  const del = (x: MailAccount) => api.deleteMailAccount(x.id).then(() => { onNotice("Deleted the mail account " + x.name + "."); setAsk(null); setChosen(null); void load(); }, (e) => setError(message(e)));
  return (
    <div className="mail-panel">
      <div className="cc-list-tools">
        <div className="cc-list-filters"><span className="st-muted">Mailboxes set up once: processes, triggers and agents choose them by name. Passwords stay in the vault.</span></div>
        <div className="cc-list-right" role="toolbar" aria-label="Mail account tools"><RefreshButton onClick={load} />
          {manage && <Button kind="primary" onClick={() => setEdit("new")}>New mail account</Button>}</div>
      </div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {ask && <div className="cc-list-bulk" role="alert"><span>Delete the mail account {ask.name}?</span>
        <Button kind="danger" onClick={() => del(ask)}>Delete</Button><Button kind="plain" onClick={() => setAsk(null)}>Cancel</Button></div>}
      <div className="mail-split">
        <div className="cc-list-panel"><div className="cc-list-scroll">
          <table className="cc-list-table" aria-label="Mail accounts">
            <thead><tr><th scope="col">Name</th><th scope="col">Vendor</th><th scope="col">Address</th><th scope="col">Status</th><th scope="col">Last check</th><th scope="col">Used by</th></tr></thead>
            <tbody>
              {data?.accounts.map((x) => (
                <tr key={x.id} className={a?.id === x.id ? "is-selected" : undefined} aria-selected={a?.id === x.id} onClick={() => setChosen(x.id)}>
                  <td><button type="button" className="st-link" onClick={() => setChosen(x.id)}>{x.name}</button></td>
                  <td>{x.provider_name}</td><td className="cc-clip">{x.address}</td><td><MailStatusPill status={x.status} /></td>
                  <td>{x.last_check ? when(x.last_check) : "Never"}</td><td>{x.used_by.length}</td>
                </tr>))}
              {data && !data.accounts.length && <tr className="is-static"><td colSpan={6} className="cc-none">No mail accounts yet. {manage ? "Add one so processes and triggers can choose it." : "Ask an administrator to add one."}</td></tr>}
              {!data && !error && <tr className="is-static"><td colSpan={6} className="cc-none" role="status">Loading…</td></tr>}
            </tbody>
          </table>
        </div></div>
        {a && <aside className="mail-detail" aria-label={"Mail account " + a.name}>
          <h2><span>{a.name}</span> <MailStatusPill status={a.status} /></h2>
          <dl className="cc-facts">
            <dt>Vendor</dt><dd>{a.provider_name} · {SIGN_IN[a.sign_in] ?? a.sign_in}</dd>
            <dt>Address</dt><dd>{a.address}{a.settings.mailbox && a.settings.mailbox !== a.address ? " (reads " + String(a.settings.mailbox) + ")" : ""}</dd>
            {a.signed_in_as && <><dt>Signed in as</dt><dd>{a.signed_in_as}</dd></>}
            {a.secret_ref && <><dt>From the vault</dt><dd className="ui-mono">{a.secret_ref}</dd></>}
            {a.settings.host ? <><dt>Server</dt><dd>{String(a.settings.host)}:{String(a.settings.port)}{a.settings.ssl === false ? " (no SSL)" : ""}</dd></> : null}
            <dt>Who may use it</dt><dd>{a.allowed.length ? a.allowed.join(", ") : "Only " + a.owner + " and administrators"}</dd>
            <dt>Owner</dt><dd>{a.owner}</dd>
            <dt>Used by</dt><dd>{a.used_by.length ? a.used_by.map((u) => (u.kind === "process" ? "Process " : u.kind === "trigger" ? "Trigger " : u.kind + " ") + u.target).join(", ") : "Nothing yet"}</dd>
          </dl>
          {a.last_error && <p className="st-alert" role="status">{a.last_error}</p>}
          <p className="st-muted">Signing in again or changing the password here fixes every process, trigger and agent that uses this account.</p>
          <div className="cc-inline mail-actions">
            {a.can_use && <Button kind="secondary" disabled={busy} onClick={() => void test(a)}>{busy ? "Checking…" : "Test now"}</Button>}
            {a.can_use && <Button kind="secondary" onClick={() => setRules(a)}>Mail rules</Button>}
            {a.can_change && <Button kind="secondary" onClick={() => setEdit(a)}>{a.sign_in === "microsoft" ? "Edit or sign in again" : "Edit"}</Button>}
            {a.can_change && <Button kind="plain" onClick={() => setAsk(a)}>Delete…</Button>}
          </div>
        </aside>}
      </div>
      {edit && data && <MailAccountDialog api={api} account={edit === "new" ? null : edit} providers={data.providers} onClose={() => setEdit(null)}
        onSaved={(x, t) => { onNotice(t); setEdit(null); setChosen(x.id); void load(); }} />}
      {rules && <MailRulesDialog api={api} account={rules} onClose={() => { setRules(null); void load(); }} onNotice={onNotice} />}
    </div>
  );
}

/** Add or change a mail account: the address finds the vendor; then how it signs in, its name and who may use it. */
export function MailAccountDialog({ api, account, providers, onClose, onSaved }: {
  api: CrApi; account: MailAccount | null; providers: MailProvider[]; onClose: () => void; onSaved: (a: MailAccount, t: string) => void;
}) {
  const s = account?.settings ?? {};
  const [address, setAddress] = useState(account?.address ?? "");
  const [provider, setProvider] = useState(account?.provider ?? "");
  const [found, setFound] = useState<MailDetect | null>(null);
  const [name, setName] = useState(account?.name ?? "");
  const p = providers.find((x) => x.id === provider) ?? null;
  const [signIn, setSignIn] = useState(account?.sign_in ?? "");
  const method = p ? (p.signIn.includes(signIn as never) ? signIn : p.signIn[0]) : "";
  const [region, setRegion] = useState(String(s.region ?? ""));
  const [host, setHost] = useState(String(s.host ?? ""));
  const [port, setPort] = useState(String(s.port ?? ""));
  const [ssl, setSsl] = useState(s.ssl !== false);
  const [user, setUser] = useState(String(s.user ?? ""));
  const [secret, setSecret] = useState<string | null>(account?.secret_ref ?? null);
  const [refs, setRefs] = useState<VaultRef[] | null | undefined>(undefined);
  const [ms, setMs] = useState<{ id: string; account: string } | null>(account?.ms_signin ? { id: account.ms_signin, account: account.signed_in_as ?? account.address } : null);
  const [signing, setSigning] = useState(false);
  const [shared, setShared] = useState(String(s.mailbox && s.mailbox !== account?.address ? s.mailbox : ""));
  const [tenant, setTenant] = useState(String(s.tenant_id ?? ""));
  const [client, setClient] = useState(String(s.client_id ?? ""));
  const [allowed, setAllowed] = useState((account?.allowed ?? []).join(","));
  const [advanced, setAdvanced] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const who = useCallback(() => principalDirectory(api), [api]);
  useEffect(() => { vaultRefsFrom(api)().then(setRefs, () => setRefs(null)); }, [api]);
  // the vendor from the address, a moment after typing stops (domain first, then where a company domain's mail goes)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => {
    if (account || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(address.trim())) { setFound(null); return; }
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      api.mailDetect(address.trim()).then((d) => {
        setFound(d);
        if (d.provider) { setProvider(d.provider); if (d.region) setRegion(d.region); }
        else setProvider((cur) => cur || "imap");
        setName((cur) => cur || address.trim());
      }, () => setFound(null));
    }, 400);
    return () => clearTimeout(timer.current);
  }, [address, api, account]);
  const imapHost = p?.regions?.find((r) => r.id === region)?.host ?? p?.imap?.host ?? "";
  const needsHost = p?.kind === "imap" && !imapHost;
  const valid = !!p && !!name.trim() && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(address.trim())
    && (method === "microsoft" ? !!ms : method === "microsoft_app" ? !!tenant.trim() && !!client.trim() && !!secret : !!secret && (!needsHost || !!host.trim()));
  const save = async () => {
    if (!p) return;
    const b: Record<string, unknown> = { name: name.trim(), provider: p.id, address: address.trim(), sign_in: method, allowed };
    if (method === "microsoft") { b.ms_signin = ms?.id; b.mailbox = shared.trim(); }
    else if (method === "microsoft_app") { b.tenant_id = tenant.trim(); b.client_id = client.trim(); b.secret_ref = secret; }
    else { b.secret_ref = secret; b.region = region || null; b.host = host.trim() || null; b.port = port.trim() ? Number(port) : null; b.ssl = ssl; b.user = user.trim() || null; }
    setSaving(true);
    try { const a = await api.saveMailAccount(account?.id ?? null, b); onSaved(a, (account ? "Saved the mail account " : "Added the mail account ") + a.name + "."); }
    catch (e) { setError(message(e)); } finally { setSaving(false); }
  };
  return (
    <Dialog open title={account ? "Mail account " + account.name : "New mail account"} width={900} onClose={onClose}
      footer={<><span className="st-muted mail-foot-note">{p?.help ?? "Type the address: we find who hosts the mail and how to sign in."}</span>
        <Button kind="plain" onClick={onClose}>Cancel</Button>
        <Button kind="primary" disabled={!valid || saving} onClick={() => void save()}>{saving ? "Saving…" : account ? "Save changes" : "Add mail account"}</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-grid2">
        <Field label="Email address" hint={found ? (found.provider ? (found.how === "mx" ? "Found: " + (providers.find((x) => x.id === found.provider)?.name ?? found.provider) + ", from where " + found.domain + "'s mail goes (" + found.mx + ")." : "Found: " + (providers.find((x) => x.id === found.provider)?.name ?? found.provider) + ".") : "Vendor not found: choose it below.") : undefined}>
          {(id) => <TextInput id={id} value={address} disabled={!!account} placeholder="invoices@example.com" onChange={(e) => setAddress(e.target.value)} />}</Field>
        <Field label="Name people choose">{(id) => <TextInput id={id} value={name} placeholder="AP invoices mailbox" onChange={(e) => setName(e.target.value)} />}</Field>
      </div>
      <fieldset className="mail-vendors"><legend>Vendor</legend>
        {providers.map((x) => <button key={x.id} type="button" className={"mail-vendor" + (provider === x.id ? " is-on" : "")} aria-pressed={provider === x.id} disabled={!!account && account.provider !== x.id}
          onClick={() => { setProvider(x.id); setSignIn(""); setRegion(""); }}><b>{x.name}</b><span>{x.signIn.map((m) => SIGN_IN[m]).join(" or ")}</span></button>)}
      </fieldset>
      {p && <div className="mail-signin">
        {p.signIn.length > 1 && <Field label="Sign in with">{(id) => <Select id={id} value={method} onChange={(e) => setSignIn(e.target.value)} options={p.signIn.map((m) => ({ value: m, label: SIGN_IN[m] }))} />}</Field>}
        {method === "microsoft" && <>
          <div className="cc-inline">{ms ? <><span className="cc-pill is-success">Signed in as {ms.account}</span><button type="button" className="st-link" onClick={() => setSigning(true)}>Sign in again</button></>
            : <Button kind="primary" onClick={() => setSigning(true)}>Sign in with Microsoft</Button>}</div>
          {p.audience !== "consumers" && <Field label="A shared mailbox (optional)" hint="Leave empty to read the signed-in account's own mailbox.">{(id) => <TextInput id={id} value={shared} placeholder="accounts@example.com" onChange={(e) => setShared(e.target.value)} />}</Field>}
        </>}
        {method === "microsoft_app" && <div className="cc-grid2">
          <Field label="Tenant id">{(id) => <TextInput id={id} value={tenant} onChange={(e) => setTenant(e.target.value)} />}</Field>
          <Field label="Application (client) id">{(id) => <TextInput id={id} value={client} onChange={(e) => setClient(e.target.value)} />}</Field>
          <Field label="Client secret (saved in the vault)">{(id) => <VaultPicker id={id} value={secret} list={refs} onChange={setSecret} />}</Field>
        </div>}
        {(method === "app_password" || method === "password") && <>
          {p.appPasswordUrl && <p className="st-muted">{p.help} <a href={p.appPasswordUrl} target="_blank" rel="noopener noreferrer">Make an app password at {p.name} ↗</a></p>}
          {p.regions && p.regions.length > 1 && <Field label="Region">{(id) => <Select id={id} value={region || p.regions![0].id} onChange={(e) => setRegion(e.target.value)} options={p.regions!.map((r) => ({ value: r.id, label: r.name }))} />}</Field>}
          <Field label={(method === "app_password" ? "App password" : "Password") + " (saved in the vault)"} hint="Choose a saved value you may use; the password itself never comes here.">
            {(id) => <VaultPicker id={id} value={secret} list={refs} onChange={setSecret} />}</Field>
          {(needsHost || advanced) ? <div className="cc-grid2">
            <Field label="Mail server (IMAP)">{(id) => <TextInput id={id} value={host} placeholder={imapHost || "mail.example.com"} onChange={(e) => setHost(e.target.value)} />}</Field>
            <Field label="Port">{(id) => <TextInput id={id} type="number" value={port} placeholder={String(p.imap?.port ?? 993)} onChange={(e) => setPort(e.target.value)} />}</Field>
            <Field label="User name">{(id) => <TextInput id={id} value={user} placeholder={address || "the address"} onChange={(e) => setUser(e.target.value)} />}</Field>
            <label className="st-check-line"><input type="checkbox" checked={ssl} onChange={(e) => setSsl(e.target.checked)} /> SSL/TLS (port 993); off only on a trusted network</label>
          </div> : <p className="st-muted">Server {imapHost}, port {p.imap?.port ?? 993}, SSL/TLS, user name {address || "the address"}. <button type="button" className="st-link" onClick={() => setAdvanced(true)}>Show advanced settings</button></p>}
        </>}
      </div>}
      <Field label="Who may use it" hint="They can choose it in processes, triggers and agents. Only you (the owner) and administrators change it.">
        {(id) => <PrincipalPicker id={id} value={allowed} onChange={setAllowed} source={who} />}</Field>
      {signing && p && <MicrosoftSignInDialog start={() => api.mailSignInStart(p.audience ?? "organizations")} poll={(id) => api.mailSignInPoll(id)} onClose={() => setSigning(false)}
        onDone={(id, who2) => { setSigning(false); setMs({ id, account: who2 }); }} />}
    </Dialog>
  );
}

// ------------------------------------------------------------------ rules

const FIELDS: Record<string, { label: string; ops: [string, string][]; hint?: string }> = {
  from: { label: "From", ops: [["domain_in", "is in the domain"], ["contains", "contains"], ["is", "is"], ["not_contains", "does not contain"]], hint: "@supplier.com, billing@" },
  to: { label: "To or Cc", ops: [["contains", "contains"], ["is", "is"]], hint: "invoices+ap@" },
  subject: { label: "Subject", ops: [["contains", "contains any of"], ["not_contains", "contains none of"], ["regex", "matches the pattern"]], hint: "invoice, bill" },
  body: { label: "Text", ops: [["contains", "contains any of"], ["not_contains", "contains none of"], ["regex", "matches the pattern"]] },
  attachment_type: { label: "Attachment type", ops: [["is_one_of", "is one of"]], hint: "pdf, image, xml, office, zip or any" },
  attachment_name: { label: "Attachment name", ops: [["matches", "is named like"]], hint: "INV-*.pdf" },
  has_attachments: { label: "Has attachments", ops: [["is", "is"]] },
  received_after: { label: "Received after", ops: [["after", "is after"]] },
};
export const EMPTY_CONDITIONS: MailConditions = { match: "all", when: [], except: [] };

/** A rule's conditions: All of these or Any of these, then Except when…, each a field, how, and the words. */
export function RuleBuilder({ value, onChange }: { value: MailConditions; onChange: (c: MailConditions) => void }) {
  const row = (list: "when" | "except", c: MailCondition, i: number) => {
    const f = FIELDS[c.field] ?? FIELDS.from;
    const set = (patch: Partial<MailCondition>) => onChange({ ...value, [list]: value[list].map((x, j) => (j === i ? { ...x, ...patch } : x)) });
    const text = Array.isArray(c.value) ? c.value.join(", ") : c.value;
    const label = (list === "except" ? "Except: " : "") + "condition " + (i + 1);
    return <div key={list + i} className="mail-cond" role="group" aria-label={label}>
      <Select aria-label={label + " field"} value={c.field} onChange={(e) => { const nf = FIELDS[e.target.value]; set({ field: e.target.value, op: nf.ops[0][0], value: e.target.value === "has_attachments" ? "true" : "" }); }}
        options={Object.entries(FIELDS).map(([k, v]) => ({ value: k, label: v.label }))} />
      <Select aria-label={label + " how"} value={c.op} onChange={(e) => set({ op: e.target.value })} options={f.ops.map(([k, v]) => ({ value: k, label: v }))} />
      {c.field === "has_attachments" ? <Select aria-label={label + " value"} value={text} onChange={(e) => set({ value: e.target.value })} options={[{ value: "true", label: "yes" }, { value: "false", label: "no" }]} />
        : <TextInput aria-label={label + " value"} type={c.field === "received_after" ? "date" : "text"} value={text} placeholder={f.hint} onChange={(e) => set({ value: e.target.value })} />}
      <IconButton icon="close" label={"Remove " + label} onClick={() => onChange({ ...value, [list]: value[list].filter((_, j) => j !== i) })} />
    </div>;
  };
  return (
    <div className="mail-rule-builder">
      <div className="cc-inline"><span className="st-muted">Take an email when it matches</span>
        <Select aria-label="Match" value={value.match} onChange={(e) => onChange({ ...value, match: e.target.value as "all" | "any" })} options={[{ value: "all", label: "all of these" }, { value: "any", label: "any of these" }]} /></div>
      {value.when.map((c, i) => row("when", c, i))}
      {!value.when.length && <p className="st-muted">No conditions: every email.</p>}
      {value.except.map((c, i) => row("except", c, i))}
      <div className="cc-inline">
        <Button kind="secondary" icon="plus" onClick={() => onChange({ ...value, when: [...value.when, { field: "from", op: "domain_in", value: "" }] })}>Condition</Button>
        <Button kind="secondary" icon="plus" onClick={() => onChange({ ...value, except: [...value.except, { field: "from", op: "contains", value: "noreply@" }] })}>Except when…</Button>
      </div>
    </div>
  );
}

/** Tries conditions on the latest emails: which match, and how many an earlier rule takes first. Reads nothing in. */
export function RuleTry({ api, account, conditions, rule }: { api: CrApi; account: string; conditions: MailConditions; rule?: string }) {
  const [r, setR] = useState<MailTry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true); setError(null);
    try { setR(await api.tryMailRule(account, { conditions, rule })); } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  return (
    <section className="mail-try" aria-label="Try the rule">
      <div className="cc-inline"><b>Try the rule</b><Button kind="secondary" disabled={busy || !account} onClick={() => void run()}>{busy ? "Reading the latest emails…" : "Try on the latest emails"}</Button></div>
      {error && <p className="st-alert" role="alert">{error}</p>}
      {r && <>
        <p><span className="cc-pill is-success">{r.matched.length} match</span> {r.taken_by_earlier > 0 && <span className="cc-pill is-pending">{r.taken_by_earlier} taken by an earlier rule</span>} <span className="st-muted">of the last {r.looked_at} emails · nothing was read in</span></p>
        <ul className="mail-try-list">{r.matched.slice(0, 8).map((m, i) => <li key={"m" + i}>✓ <b>{m.subject}</b> <span className="st-muted">· {m.from}</span></li>)}
          {r.not_matched.slice(0, 4).map((m, i) => <li key={"n" + i} className="st-muted">– {m.subject} · {m.from}</li>)}</ul>
      </>}
    </section>
  );
}

/** An account's rules in order, how they apply, Check now, and the emails no rule took (Not matched). */
export function MailRulesDialog({ api, account, onClose, onNotice }: { api: CrApi; account: MailAccount; onClose: () => void; onNotice: (t: string) => void }) {
  const [data, setData] = useState<{ rules: MailRule[]; rule_mode: "first" | "every"; can_order: boolean } | null>(null);
  const [waiting, setWaiting] = useState<{ id: string; received: string; sender: string; subject: string }[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sendTo, setSendTo] = useState<Record<string, string>>({});
  const load = useCallback(() => Promise.all([api.mailRules(account.id), api.mailUnmatched(account.id)]).then(([d, w]) => { setData(d); setWaiting(w); setError(null); }, (e) => setError(message(e))), [api, account.id]);
  useEffect(() => { void load(); }, [load]);
  const move = async (i: number, by: number) => {
    if (!data) return;
    const ids = data.rules.map((r) => r.id);
    const [x] = ids.splice(i, 1);
    ids.splice(i + by, 0, x);
    try { await api.orderMailRules(account.id, ids); await load(); } catch (e) { setError(message(e)); }
  };
  const mode = async (m: string) => { try { await api.saveMailAccount(account.id, { rule_mode: m }); await load(); } catch (e) { setError(message(e)); } };
  const check = async () => {
    try { const r = await api.readMailAccount(account.id); onNotice(r.error ? "Not reached: " + r.error : r.read + " new email" + (r.read === 1 ? "" : "s") + ", " + r.delivered + " sent on, " + r.unmatched + " not matched."); await load(); }
    catch (e) { setError(message(e)); }
  };
  const ruleOptions = useMemo(() => (data?.rules ?? []).map((r) => ({ value: r.id, label: r.name + " → " + r.target_name })), [data]);
  return (
    <Dialog open title={"Mail rules: " + account.name} width={1040} onClose={onClose} footer={<><Button kind="secondary" onClick={() => void check()}>Check now</Button><span className="st-bar-fill" /><Button kind="plain" onClick={onClose}>Done</Button></>}>
      {error && <p className="st-alert" role="alert">{error}</p>}
      <div className="cc-inline">
        <span className="st-muted">{account.address} is read once every {Math.round(account.interval_seconds / 60)} minute{account.interval_seconds >= 120 ? "s" : ""} for all rules. When an email matches several rules:</span>
        <Select aria-label="When an email matches several rules" value={data?.rule_mode ?? account.rule_mode} disabled={!data?.can_order} onChange={(e) => void mode(e.target.value)}
          options={[{ value: "first", label: "the first matching rule takes it" }, { value: "every", label: "every matching rule takes it" }]} />
      </div>
      <div className="mail-rules-grid">
        <table className="st-table" aria-label="Rules in order">
          <thead><tr><th scope="col">Order</th><th scope="col">Rule</th><th scope="col">Emails that match</th><th scope="col">Goes to</th><th scope="col">Matched</th></tr></thead>
          <tbody>{(data?.rules ?? []).map((r, i) => (
            <tr key={r.id} className={r.enabled ? undefined : "is-off"}>
              <td className="mail-order"><span>{i + 1}</span>{data?.can_order && <>
                <IconButton icon="up" label={"Move " + r.name + " up"} disabled={i === 0} onClick={() => void move(i, -1)} />
                <IconButton icon="down" label={"Move " + r.name + " down"} disabled={i === (data?.rules.length ?? 0) - 1} onClick={() => void move(i, 1)} /></>}</td>
              <td><b>{r.name}</b>{!r.enabled && <span className="st-muted"> (off)</span>}</td>
              <td>{r.says}</td>
              <td>{r.target_kind === "process" ? "Process " : r.target_kind === "trigger" ? "Trigger " : r.target_kind + " "}{r.target_name}</td>
              <td>{r.matched}</td>
            </tr>))}
            {data && !data.rules.length && <tr><td colSpan={5} className="st-muted">No rules yet: choose this account in a process's email step or in an email trigger.</td></tr>}
          </tbody>
        </table>
        <section className="mail-unmatched" aria-label="Not matched">
          <h3>Not matched <span className="cc-pill is-pending">{waiting.length}</span></h3>
          {!waiting.length && <p className="st-muted">Nothing waiting: every new email went to a rule.</p>}
          {waiting.slice(0, 20).map((w) => <div key={w.id} className="mail-waiting">
            <span className="cc-clip"><b>{w.subject || "(no subject)"}</b> <span className="st-muted">· {w.sender}</span></span>
            <span className="cc-inline">
              <Select aria-label={"Send " + w.subject + " to"} value={sendTo[w.id] ?? ""} onChange={(e) => setSendTo({ ...sendTo, [w.id]: e.target.value })} options={[{ value: "", label: "Send to…" }, ...ruleOptions]} />
              <Button kind="secondary" disabled={!sendTo[w.id]} onClick={() => api.sendUnmatched(w.id, sendTo[w.id]).then(() => { onNotice("Sent " + w.subject + " on."); void load(); }, (e) => setError(message(e)))}>Send</Button>
              <Button kind="plain" onClick={() => api.ignoreUnmatched(w.id).then(() => void load(), (e) => setError(message(e)))}>Ignore</Button>
            </span></div>)}
        </section>
      </div>
    </Dialog>
  );
}
