import { useCallback, useEffect, useState } from "react";
import type { MailConditions } from "../../api/cr";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import { AfterReading, EMPTY_CONDITIONS, RuleBuilder, RuleTry } from "../../console/MailAccounts";
import type { DocFlowApi, MailAccountBrief, ProcessMailRule } from "./api";
import { Chip, reportError } from "./ui";

/**
 * MA-4: a process's emails come from mail accounts (set up once in Manage > Connections), each by a rule: which emails
 * of that account become documents here. Laid out as the design plan's MA-Use and MA-RuleEdit artboards. The account
 * is read once for all its rules; its own page orders them and keeps the emails no rule took.
 */
export function ProcessMailAccounts({ api, processId, processName, bare = false, onCount }: { api: DocFlowApi; processId: string; processName?: string;
  /** Inside step 2's Email row (27 Nov 2026): no heading of its own. */ bare?: boolean;
  /** How many mail accounts send emails here, once loaded. */ onCount?: (n: number) => void }) {
  const [rules, setRules] = useState<ProcessMailRule[] | null>(null);
  const [accounts, setAccounts] = useState<MailAccountBrief[]>([]);
  const [editing, setEditing] = useState<ProcessMailRule | "new" | null>(null);
  const load = useCallback(() => Promise.all([api.processMailRules(processId), api.mailAccounts()]).then(([r, a]) => { setRules(r); setAccounts(a); }, reportError), [api, processId]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (rules) onCount?.(rules.length); }, [rules]);   // eslint-disable-line react-hooks/exhaustive-deps
  const remove = (r: ProcessMailRule) => api.deleteMailRule(r.id).then(load, reportError);
  return (
    <section className="df-mail-rules" aria-label="Mail accounts">
      {!bare && <div className="df-head-row"><b>Mail accounts</b>
        <span className="df-small">Emails from these accounts that match come in here. Accounts are set up once in Manage &gt; Connections; no server, password or sign-in here.</span></div>}
      {rules?.map((r) => (
        <div key={r.id} className="df-mail-rule">
          <span className="df-mail-rule-main"><b>{r.account_name}</b> <span className="df-small">{r.account_address}</span>
            <span className="df-small" style={{ display: "block" }}>{r.says}{r.matched ? " · " + r.matched + " so far" : ""}</span></span>
          <Chip tone={r.account_status === "working" ? "ok" : r.account_status === "new" ? "mute" : "bad"}>{r.account_status === "working" ? "Working" : r.account_status === "new" ? "Not checked yet" : "Needs attention"}</Chip>
          <Button kind="plain" onClick={() => setEditing(r)}>Edit</Button>
          <Button kind="plain" onClick={() => void remove(r)}>Remove</Button>
        </div>))}
      {rules && !rules.length && <p className="df-small" style={{ margin: 0 }}>No mail account sends emails here yet.</p>}
      <div><Button icon="plus" disabled={!accounts.length} onClick={() => setEditing("new")}>Add a mail account</Button>
        {!accounts.length && rules && <span className="df-small"> No mail account you may use yet: ask your administrator to add one (Manage &gt; Connections).</span>}</div>
      {editing && <MailRuleDialog api={api} processId={processId} processName={processName ?? processId} accounts={accounts} rule={editing === "new" ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void load(); }} />}
    </section>
  );
}

/** Which emails of an account come into the process: the account, a name, the conditions, and Try the rule. */
function MailRuleDialog({ api, processId, processName, accounts, rule, onClose, onSaved }: {
  api: DocFlowApi; processId: string; processName: string; accounts: MailAccountBrief[]; rule: ProcessMailRule | null; onClose: () => void; onSaved: () => void;
}) {
  const [account, setAccount] = useState(rule?.account_id ?? accounts[0]?.id ?? "");
  const [name, setName] = useState(rule?.name ?? processName);
  const [conditions, setConditions] = useState<MailConditions>(rule?.conditions ?? { ...EMPTY_CONDITIONS, when: [{ field: "attachment_type", op: "is_one_of", value: "pdf, image, xml" }] });
  const [afterRead, setAfterRead] = useState(rule?.after_read ?? "none");
  const [saving, setSaving] = useState(false);
  const save = async () => {
    setSaving(true);
    try { await api.saveProcessMailRule(account, rule?.id ?? null, { name: name.trim(), conditions, target: processId, after_read: afterRead }); onSaved(); }
    catch (e) { reportError(e); } finally { setSaving(false); }
  };
  return (
    <Dialog open onClose={onClose} title={rule ? "Emails from " + rule.account_name : "Add a mail account"} width={900}
      footer={<><span className="df-small" style={{ marginRight: "auto" }}>The account is read once for all its rules; its page (Manage &gt; Connections) sets their order.</span>
        <Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!account || !name.trim() || saving} onClick={() => void save()}>{saving ? "Saving…" : "Save"}</Button></>}>
      <div className="df-grid df-g2">
        <Field label="Mail account">{(id) => <Select id={id} value={account} disabled={!!rule} onChange={(e) => setAccount(e.target.value)}
          options={accounts.map((a) => ({ value: a.id, label: a.name + " · " + a.address }))} />}</Field>
        <Field label="Rule name">{(id) => <TextInput id={id} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
      </div>
      <RuleBuilder value={conditions} onChange={setConditions} />
      <p className="df-small" style={{ margin: 0 }}>PDF, image and XML attachments become documents. Reading the email itself comes later.</p>
      <AfterReading value={afterRead} onChange={setAfterRead} canWrite={!!accounts.find((a) => a.id === account)?.can_write} />
      <RuleTry disabled={!account} tryRule={() => api.tryMailRule(account, conditions, rule?.id)} />
    </Dialog>
  );
}
