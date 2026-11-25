import { useEffect, useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import { HINTS } from "./help";
import { FolderField } from "./folderPicker";
import { ProcessMailAccounts } from "./mailRules";
import { MicrosoftAccount } from "./msSignIn";
import { AdvancedToggle, ChoiceOrOwn, duration } from "./ui";
import { RULE_KIND, TAX_PRESETS, money, parseAmount, ruleTest, type Check, type Delivery, type DeliveryType, type DocFlowApi, type EInvoiceStandard, type EmailInbox, type InboxStatus, type ProcessDesign, type ProcessSettings, type RefTable, type RuleType } from "./api";

/** The process builder's stage forms and its check and delivery dialogs. */

export const IF_FAILS = { review: "Send to review", reject: "Reject", warn: "Warn only" } as const;

const EXTRACTION = [
  { value: "auto", label: "Default engine (Engines page)" }, { value: "builtin-rules", label: "Rules extractor (free, on this server)" },
  { value: "llm-local", label: "Self-hosted model" }, { value: "claude", label: "Claude (needs cloud opt-in)" },
  { value: "llm-cloud", label: "Cloud AI API (needs cloud opt-in)" }, { value: "azure-di", label: "Azure prebuilt invoice (needs cloud opt-in)" },
];

export const DELIVERY_KIND: Record<DeliveryType, { label: string; target: string; placeholder: string }> = {
  excel: { label: "Excel register", target: "Excel file", placeholder: "D:\\AP\\invoices.xlsx" },
  folder: { label: "Folder", target: "Folder", placeholder: "\\\\fileserver\\ap\\processed" },
  webhook: { label: "Webhook", target: "Address", placeholder: "https://erp.example.com/hooks/invoice" },
  start_bot: { label: "Start an agent (SFTP, ERP, SharePoint and more)", target: "Agent", placeholder: "Finance/Post invoice to ERP" },
  email: { label: "E-mail", target: "To", placeholder: "ap@example.com, role:finance" },
  cloud_storage: { label: "Cloud storage (S3, Azure Blob, a web address)", target: "Bucket", placeholder: "invoices" },
};
const FILES: [string, string][] = [["original", "The document"], ["json", "The results (JSON)"], ["markdown", "The text and tables (Markdown)"]];

/** The document states a delivery can be sent on, in plain words. */
const SEND_ON: [string, string][] = [
  ["success", "Done with no review"], ["validated", "Approved by a reviewer"], ["validation", "Waiting for review"], ["invalid", "Rejected"], ["failed", "Failed"],
];

type SetSettings = (patch: Partial<ProcessSettings>) => void;

/** Which e-invoice standards a process reads: every one (the default), or those ticked. */
function EInvoiceStandards({ api, value, onChange }: { api: DocFlowApi; value: string[]; onChange: (v: string[]) => void }) {
  const [list, setList] = useState<EInvoiceStandard[] | null>(null);
  useEffect(() => { api.einvoiceStandards().then(setList, () => setList([])); }, [api]);
  if (!list?.length) return null;
  const all = !value.length;
  const toggle = (id: string, on: boolean) => {
    const cur = all ? list.map((x) => x.id) : value;
    const next = on ? [...new Set([...cur, id])] : cur.filter((x) => x !== id);
    onChange(next.length === list.length ? [] : next);
  };
  return (
    <details style={{ marginLeft: 46 }}>
      <summary className="df-small">Standards read: {all ? "all that DocFlow knows" : `${value.length} of ${list.length}`}</summary>
      <div style={{ display: "flex", flexDirection: "column", gap: 4, marginTop: 6 }}>
        {(["xml", "qr"] as const).map((k) => <div key={k}>
          <div className="df-small"><b>{k === "xml" ? "Read instead of the page (XML)" : "Check what was read (QR codes)"}</b></div>
          {list.filter((x) => x.kind === k).map((x) => (
            <label key={x.id} style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input type="checkbox" checked={all || value.includes(x.id)} onChange={(e) => toggle(x.id, e.target.checked)} />{x.name}</label>))}
        </div>)}
      </div>
    </details>
  );
}

export function IntakeStage({ s, set, inbox, live, vaultRefs, api, processId, processName }:
  { s: ProcessSettings; set: SetSettings; inbox?: InboxStatus | null; live?: boolean; vaultRefs?: { ref: string; label: string }[] | null; api?: DocFlowApi; processId?: string; processName?: string }) {
  const email = s.email;
  const setEmail = (patch: Partial<EmailInbox>) => set({ email: { ...(email ?? { enabled: true, kind: "outlook" }), ...patch } as EmailInbox });
  const text = (key: keyof EmailInbox, label: string, placeholder = "", hint?: string) => (
    <Field label={label} hint={hint}>{(id) => <TextInput id={id} value={String(email?.[key] ?? "")} placeholder={placeholder}
      onChange={(e) => setEmail({ [key]: key === "port" ? Number(e.target.value) || undefined : e.target.value } as Partial<EmailInbox>)} />}</Field>
  );
  // DA-1: a saved account from the vault, picked from the ones the person may use; typing the reference is advanced
  const [typeRef, setTypeRef] = useState(false);
  // DA-1: Microsoft 365 signs in with Microsoft; your own app (tenant, app id, secret) is advanced, or shown when already set
  const ownApp = typeRef || (!email?.ms_signin && !!(email?.tenant_id || email?.client_id || email?.client_secret_ref));
  const secret = (key: "password_ref" | "client_secret_ref", label: string, placeholder: string) => {
    const value = String(email?.[key] ?? "");
    const refs = vaultRefs ?? [];
    const listed = !value || refs.some((r) => r.ref === value);
    // for Microsoft 365 the advanced switch shows your own app (with its secret picked); for IMAP it types the reference
    if ((typeRef && key === "password_ref") || !listed) return text(key, label + " (vault reference)", placeholder, "folder/credential/attribute; you must be allowed to use it");
    return <Field label={label} hint={refs.length ? "Saved in the vault; only the ones you may use are listed." : "Nothing saved that you may use yet: ask your administrator to add it to the vault."}>
      {(id) => <Select id={id} value={value} onChange={(e) => setEmail({ [key]: e.target.value } as Partial<EmailInbox>)}
        options={[{ value: "", label: refs.length ? "Choose a saved value" : "None saved yet" }, ...refs.map((r) => ({ value: r.ref, label: r.label }))]} />}</Field>;
  };
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
    <div className="df-grid df-g2">
      {/* DA-1 item 5: chosen from the folders processes may use (DocFlow 3.2.0); typed while there is no list */}
      <FolderField api={api} label="Watched folder" title="Choose the watched folder" hint="DocFlow reads every new file put here. Empty: upload only."
        value={s.inputFolder} placeholder="\\scan01\invoices" empty="No folder chosen: upload only" processId={processId} onChange={(v) => set({ inputFolder: v })} />
      <Field label="Check the folder every">
        {(id) => <ChoiceOrOwn id={id} value={s.pollSeconds} onChange={(v) => set({ pollSeconds: Math.round(v) })} options={POLL_SECONDS} label={duration} unit="seconds" min={5} />}
      </Field>
      <p className="df-small" style={{ gridColumn: "1 / -1", margin: 0 }}>Upload is always on. Agents send documents with the "Send document to process" action.</p>
    </div>
    <div style={{ display: "flex", gap: 10, alignItems: "flex-start" }}>
      <button type="button" role="switch" className="df-switch" aria-checked={s.einvoiceFirst} aria-labelledby="df-einvoice-label"
        onClick={() => set({ einvoiceFirst: !s.einvoiceFirst })} />
      <span><b id="df-einvoice-label">Read e-invoice data first</b>
        <span className="df-small" style={{ display: "block" }}>{HINTS.einvoiceFirst}</span></span>
    </div>
    {s.einvoiceFirst && api && <EInvoiceStandards api={api} value={s.einvoiceStandards ?? []} onChange={(einvoiceStandards) => set({ einvoiceStandards })} />}
    {api && processId && <ProcessMailAccounts api={api} processId={processId} processName={processName} />}
    <div className={`df-way${email?.enabled ? " is-on" : ""}`}>
      <label style={{ display: "flex", gap: 10 }}>
        <input type="checkbox" checked={!!email?.enabled} onChange={(e) => setEmail({ enabled: e.target.checked })} />
        <span><b>Email inbox</b><span className="df-small" style={{ display: "block" }}>The Command Center reads new emails and sends their PDF and image attachments here{live ? "" : " once the process is live"}.</span></span>
      </label>
      {email?.enabled && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, margin: "10px 0 0 24px" }}>
          <Field label="Mailbox kind">{(id) => <Select id={id} value={email.kind} onChange={(e) => setEmail({ kind: e.target.value as EmailInbox["kind"] })}
            options={[{ value: "outlook", label: "Microsoft 365 (Outlook)" }, { value: "email", label: "IMAP (any other mail server)" }]} />}</Field>
          {email.kind === "outlook" ? (<>
            <MicrosoftAccount api={api} account={email.ms_account} signedIn={!!email.ms_signin}
              onSignedIn={(id, account) => setEmail({ ms_signin: id, ms_account: account, tenant_id: "", client_id: "", client_secret_ref: "" })}
              onSignOut={() => setEmail({ ms_signin: "", ms_account: "" })} />
            <div className="df-grid df-g2">
              {text("mailbox", "Mailbox (optional)", "invoices@example.com", "Empty: the signed-in account's own mailbox. Or a shared mailbox it may read.")}
              {text("mail_folder", "Folder", "inbox")}
            </div>
            {ownApp && <div className="df-grid df-g2" role="group" aria-label="Your own Microsoft app">
              <p className="df-small" style={{ gridColumn: "1 / -1", margin: 0 }}>Your own Microsoft app, signed in as the app (it needs the Mail.Read application permission).{email.ms_signin ? " Not used while signed in with Microsoft." : ""}</p>
              {text("tenant_id", "Directory (tenant) id")}
              {text("client_id", "Application (client) id")}
              {secret("client_secret_ref", "Client secret", "m365/app/secret")}
            </div>}
          </>) : (
            <div className="df-grid df-g2">
              {text("host", "Mail server", "imap.example.com")}
              {text("port", "Port", "993")}
              <Field label="Connection">{(id) => <Select id={id} value={email?.ssl === false ? "plain" : "ssl"} onChange={(e) => setEmail({ ssl: e.target.value === "ssl" })}
                options={[{ value: "ssl", label: "Encrypted (SSL/TLS, usually port 993)" }, { value: "plain", label: "Not encrypted, or STARTTLS (usually port 143)" }]} />}</Field>
              {text("username", "User name", "invoices@example.com")}
              {secret("password_ref", "Password", "mail/invoices/password")}
              {text("mail_folder", "Folder", "INBOX")}
            </div>
          )}
          <AdvancedToggle open={typeRef} onToggle={() => setTypeRef(!typeRef)}
            what={email.kind === "outlook" ? "your own Microsoft app instead of Sign in with Microsoft" : "type a vault reference instead of choosing a saved value"} />
          <div className="df-grid df-g2">
            {text("from_contains", "Only from (optional)", "@supplier.com")}
            {text("subject_contains", "Subject contains (optional)", "invoice")}
          </div>
          {inbox && <p className={`df-small ${inbox.error ? "df-text-bad" : ""}`} role="status" style={{ margin: 0 }}>
            {inbox.lastCheck ? `Last checked ${new Date(inbox.lastCheck).toLocaleString()}` : "Not checked yet"}{inbox.error ? `: ${inbox.error}` : ` · ${inbox.documents} document${inbox.documents === 1 ? "" : "s"} read so far`}</p>}
        </div>
      )}
    </div>
    </div>
  );
}

export function ClassifyStage({ s, set }: { s: ProcessSettings; set: SetSettings }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {([["single", "Every file is this process's document type", "Fastest. Use when only one kind of document arrives."],
        ["auto", "Recognise each document", "For mixed files: DocFlow finds out the type of each one."]] as const).map(([v, title, text]) => (
        <label key={v} className={`df-radio-card${s.classifyMode === v ? " is-on" : ""}`}>
          <input type="radio" name="df-classify" checked={s.classifyMode === v} onChange={() => set({ classifyMode: v })} />
          <span><b>{title}</b><span className="df-muted" style={{ display: "block" }}>{text}</span></span>
        </label>
      ))}
      <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <input type="checkbox" checked={s.split} onChange={(e) => set({ split: e.target.checked })} />Split batches: one scan with several documents becomes several documents
      </label>
      {s.classifyMode === "single" && <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <input type="checkbox" checked={!!s.checkType} onChange={(e) => set({ checkType: e.target.checked })} />
        Check each file really is this type (its recognition words are on it); one that is not is handled as below
      </label>}
      <Field label="When a document fits none of the types" hint="Other document: its text, labelled values and tables are kept and delivered like any other.">
        {(id) => <Select id={id} value={s.whenNoType ?? "review"} onChange={(e) => set({ whenNoType: e.target.value as ProcessSettings["whenNoType"] })}
          options={[{ value: "review", label: "Send to review: a person chooses the type" }, { value: "other", label: "Keep it as an Other document" },
            { value: "reject", label: "Reject it, with the reason" }]} />}
      </Field>
    </div>
  );
}

export function ExtractStage({ s, set, api, processId, types = [] }: { s: ProcessSettings; set: SetSettings; api?: DocFlowApi; processId?: string; types?: { id: string; name: string }[] }) {
  const [advanced, setAdvanced] = useState(s.extraction !== "auto" || Object.keys(s.extractionByType ?? {}).length > 0);   // a chosen engine is shown, not hidden
  // the engines that read fields on this server's DocFlow (any option pack), else the usual list
  const [engines, setEngines] = useState(EXTRACTION);
  const [spend, setSpend] = useState<{ spend: number; currency: string; localOnly: boolean } | null>(null);
  useEffect(() => {
    if (!api) return;
    api.engines().then((e) => {
      const fields = e.options.flatMap((o) => o.engines).filter((x) => x.does !== "Reads pages");
      if (fields.length) setEngines([EXTRACTION[0], ...fields.map((x) => ({ value: x.id, label: x.name + (x.status === "ready" ? "" : x.status === "optin" ? " (needs cloud opt-in)" : " (needs setting up)") }))]);
    }, () => undefined);
    if (processId) api.processSpend(processId).then(setSpend, () => setSpend(null));
  }, [api, processId]);
  const byType = s.extractionByType ?? {};
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <p style={{ margin: 0 }}>The fields to read come from the document type. {s.extraction === "auto"
        ? "They are read the way the Engines page says (on this server unless cloud reading is switched on)."
        : "This process uses its own engine (below)."}</p>
      <AdvancedToggle open={advanced} onToggle={() => setAdvanced(!advanced)} what="choose a different engine for this process" />
      {advanced && <Field label="Engine that reads the fields" hint="Default engine: the one set on the Engines page.">
        {(id) => <Select id={id} value={s.extraction} onChange={(e) => set({ extraction: e.target.value })}
          options={[...engines, ...(engines.some((x) => x.value === s.extraction) ? [] : [{ value: s.extraction, label: s.extraction }])]} />}
      </Field>}
      {advanced && types.length > 1 && <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
        <legend className="df-small" style={{ padding: 0, marginBottom: 6 }}>An engine for each document type (optional)</legend>
        <div className="df-grid df-g2" style={{ gap: 8 }}>{types.map((t) => (
          <Field key={t.id} label={t.name}>{(id) => <Select id={id} value={byType[t.id] ?? ""}
            onChange={(e) => { const next = { ...byType }; if (e.target.value) next[t.id] = e.target.value; else delete next[t.id]; set({ extractionByType: next }); }}
            options={[{ value: "", label: "As above" }, ...engines.filter((x) => x.value !== "auto")]} />}</Field>))}
        </div>
      </fieldset>}
      <div className="df-grid df-g2">
        <Field label="Monthly budget for cloud and AI reading (optional)"
          hint={`Reached: the process reads on this server until the month ends. Empty: no limit.${spend ? ` This month so far: about ${money(spend.spend, spend.currency)}${spend.localOnly ? " (budget reached: reading on this server)" : ""}.` : ""}`}>
          {(id) => <TextInput id={id} inputMode="decimal" value={s.monthlyBudget == null ? "" : String(s.monthlyBudget)} placeholder="No limit"
            onChange={(e) => { const v = parseAmount(e.target.value); set({ monthlyBudget: e.target.value.trim() === "" || isNaN(v) ? null : v }); }} />}
        </Field>
      </div>
    </div>
  );
}

/** The thresholds a field can have of its own (70–99%), and the saved one when it is another. */
const FIELD_LEVELS = [0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.97, 0.99];
/** How often a watched folder is checked: common choices; any other number of seconds can be typed. */
export const POLL_SECONDS = [10, 30, 60, 300, 900, 3600];

export function ReviewStage({ s, set, fields }: { s: ProcessSettings; set: SetSettings; fields: { name: string; label: string }[] }) {
  const own = Object.keys(s.fieldThresholds ?? {}).length > 0;
  const [advanced, setAdvanced] = useState(own);   // thresholds already set are shown, not hidden
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <ReviewLevels s={s} set={set} />
      <AdvancedToggle open={advanced} onToggle={() => setAdvanced(!advanced)} what="stricter checks for important fields, such as the total" />
      {advanced && <FieldThresholds s={s} set={set} fields={fields} />}
    </div>
  );
}

/** Some fields matter more (a total, a VAT number): they can go to review under a higher confidence than the rest. */
function FieldThresholds({ s, set, fields }: { s: ProcessSettings; set: SetSettings; fields: { name: string; label: string }[] }) {
  const entries = Object.entries(s.fieldThresholds ?? {});
  const write = (list: [string, number][]) => set({ fieldThresholds: Object.fromEntries(list) });
  const free = fields.filter((f) => !(f.name in (s.fieldThresholds ?? {})));
  const labelOf = (name: string) => fields.find((f) => f.name === name)?.label ?? name;
  return (
    <section aria-label="Field thresholds" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div><b>Field thresholds</b><span className="df-small" style={{ display: "block" }}>{HINTS.fieldThresholds}</span></div>
      {entries.length > 0 && (
        <table className="df-table df-thresholds">
          <thead><tr><th>Field</th><th>Send to review under</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>
            {entries.map(([name, level], i) => (
              <tr key={name}>
                <td><select className="ui-input ui-select" aria-label={`Field ${i + 1}`} value={name}
                  onChange={(e) => write(entries.map(([n, v]) => (n === name ? [e.target.value, v] : [n, v])))}>
                  {[{ name, label: labelOf(name) }, ...free].map((f) => <option key={f.name} value={f.name}>{f.label}</option>)}
                </select></td>
                <td><ChoiceOrOwn id={`df-level-${i}`} ariaLabel={`Threshold for ${labelOf(name)}`} value={level} options={FIELD_LEVELS} label={(v) => Math.round(v * 1000) / 10 + "%"} unit="%" scale={100} min={1} max={100}
                  onChange={(nv) => write(entries.map(([n, v]) => [n, n === name ? nv : v]))} /></td>
                <td className="df-right"><Button kind="plain" aria-label={`Remove threshold for ${labelOf(name)}`} onClick={() => write(entries.filter(([n]) => n !== name))}>Remove</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div><Button icon="plus" disabled={!free.length} onClick={() => write([...entries, [free[0].name, 0.95]])}>Add field threshold</Button></div>
    </section>
  );
}

function ReviewLevels({ s, set }: { s: ProcessSettings; set: SetSettings }) {
  return (
    <div className="df-grid df-g2">
      <Field label="Send to review when a field is under" hint="Lower: fewer reviews, more risk. Higher: more reviews.">
        {(id) => <ChoiceOrOwn id={id} value={s.fieldConfidence} onChange={(v) => set({ fieldConfidence: v })} options={[0.7, 0.75, 0.8, 0.85, 0.9, 0.95]}
          label={(v) => Math.round(v * 1000) / 10 + "% confidence"} unit="%" scale={100} min={1} max={100} />}
      </Field>
      <Field label="Review within" hint="After this, the review shows as overdue.">
        {(id) => <ChoiceOrOwn id={id} value={s.slaHours} onChange={(v) => set({ slaHours: v })} options={[1, 4, 8, 24, 48, 72, 120]}
          label={(v) => duration(v * 3600)} unit="hours" min={0.25} />}
      </Field>
    </div>
  );
}

export function DeliverStage({ deliveries, onEdit, onRemove, onAdd }:
  { deliveries: Delivery[]; onEdit: (d: Delivery) => void; onRemove: (id: string) => void; onAdd: () => void }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {!deliveries.length && <div className="df-empty">Nothing is delivered yet. Results stay in DocFlow until you add a delivery.</div>}
      {deliveries.map((d) => (
        <div key={d.id} className="df-way" style={{ display: "flex", gap: 10, alignItems: "center" }}>
          <span style={{ minWidth: 0, flex: 1 }}><b>{d.name || DELIVERY_KIND[d.type].label}</b>
            <span className="df-small" style={{ display: "block", overflowWrap: "anywhere" }}>{DELIVERY_KIND[d.type].label} · {d.target || "not set"} · when {d.on.map((o) => SEND_ON.find(([k]) => k === o)?.[1].toLowerCase() ?? o).join(", ")}{d.enabled ? "" : " · off"}</span></span>
          <Button kind="plain" onClick={() => onEdit(d)}>Edit</Button>
          <Button kind="plain" onClick={() => onRemove(d.id)}>Remove</Button>
        </div>
      ))}
      <div><Button icon="plus" onClick={onAdd}>Add delivery</Button></div>
    </div>
  );
}

const newId = (p: string) => p + Date.now().toString(36) + Math.floor(Math.random() * 1e4).toString(36);

export function DeliveryDialog({ open, value, onClose, onSave, api, processId }:
  { open: boolean; value: Delivery | null; onClose: () => void; onSave: (d: Delivery) => void; api?: DocFlowApi; processId?: string }) {
  const [d, setD] = useState<Delivery>(() => value ?? { id: newId("d"), type: "excel", name: "", enabled: true, target: "", on: ["success", "validated"] });
  const kind = DELIVERY_KIND[d.type];
  return (
    <Dialog open={open} onClose={onClose} title={value ? "Edit delivery" : "Add delivery"} width={560}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!d.target.trim() || !d.on.length} onClick={() => onSave({ ...d, name: d.name.trim() || kind.label, target: d.target.trim() })}>Save</Button></>}>
      <div className="df-grid df-g2">
        <Field label="Send results to">{(id) => <Select id={id} value={d.type} onChange={(e) => setD({ ...d, type: e.target.value as DeliveryType, target: "" })}
          options={(Object.keys(DELIVERY_KIND) as DeliveryType[]).map((k) => ({ value: k, label: DELIVERY_KIND[k].label }))} />}</Field>
        <Field label="Name (optional)">{(id) => <TextInput id={id} value={d.name} placeholder={kind.label} onChange={(e) => setD({ ...d, name: e.target.value })} />}</Field>
      </div>
      {d.type === "folder" || d.type === "excel"
        ? <FolderField key={d.type} api={api} label={kind.target} title={d.type === "excel" ? "Choose the folder for the Excel file" : "Choose the folder for results"}
            value={d.target} placeholder={kind.placeholder} file={d.type === "excel" ? "register.xlsx" : undefined} processId={processId}
            hint={d.type === "folder" ? "Each document's values and file go in a folder named after its status (success, validated...)." : "One sheet per document type, a row for each document."}
            onChange={(v) => setD({ ...d, target: v })} />
        : <Field label={d.type === "cloud_storage" ? ({ s3: "Bucket", azure_blob: "Container address", http_put: "Address ({file} is the file name)" } as Record<string, string>)[String(d.extra?.provider ?? "azure_blob")] : kind.target} hint={d.type === "start_bot" ? "The agent's path in the agent repository. It runs as you, with the document's values as inputs (document, document_id, or any field by its name)." : undefined}>
            {(id) => <TextInput id={id} value={d.target} placeholder={kind.placeholder} onChange={(e) => setD({ ...d, target: e.target.value })} />}
          </Field>}
      {d.type === "start_bot" && <p className="df-small" style={{ margin: 0 }}>Use an agent for SFTP, an ERP, SharePoint or any other system: it gets the document and its values, and uses its own steps and vault credentials.</p>}
      {(d.type === "email" || d.type === "cloud_storage") && <OutSettings d={d} setD={setD} />}
      {d.type === "folder" && <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <input type="checkbox" checked={!!d.searchablePdf} onChange={(e) => setD({ ...d, searchablePdf: e.target.checked })} />
        Also save a searchable PDF (its text can be searched and copied)</label>}
      {d.type === "folder" && <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <input type="checkbox" checked={!!d.saveText} onChange={(e) => setD({ ...d, saveText: e.target.checked })} />
        Also save the text and tables as a Markdown file (a Knowledge base can index this folder)</label>}
      {d.type === "webhook" && <div className="df-grid" style={{ gridTemplateColumns: "1fr 3fr", gap: 12 }}>
        <Field label="Method">{(id) => <Select id={id} value={d.method ?? "POST"} onChange={(e) => setD({ ...d, method: e.target.value as Delivery["method"] })}
          options={["POST", "PUT", "PATCH"].map((m) => ({ value: m, label: m }))} />}</Field>
        <Field label="Headers (optional)" hint="One per line, Name: value, such as X-Api-Key: ... (sent with every delivery).">
          {(id) => <textarea id={id} className="ui-input" rows={2} value={Object.entries(d.headers ?? {}).map(([k, v]) => `${k}: ${v}`).join("\n")}
            onChange={(e) => setD({ ...d, headers: Object.fromEntries(e.target.value.split("\n").map((l) => l.split(/:(.*)/s)).filter(([k, v]) => k?.trim() && v !== undefined).map(([k, v]) => [k.trim(), v.trim()])) })} />}
        </Field>
      </div>}
      {d.type === "start_bot" && (
        <Field label="Runner pool (optional)" hint="Empty: your default runner.">
          {(id) => <TextInput id={id} value={d.pool ?? ""} placeholder="pool id" onChange={(e) => setD({ ...d, pool: e.target.value })} />}
        </Field>
      )}
      <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
        <legend style={{ fontWeight: 600, padding: 0, marginBottom: 6 }}>Send when a document is</legend>
        <div className="df-grid df-g2" style={{ gap: 6 }}>
          {SEND_ON.map(([k, label]) => (
            <label key={k} style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input type="checkbox" checked={d.on.includes(k)} onChange={(e) => setD({ ...d, on: e.target.checked ? [...d.on, k] : d.on.filter((x) => x !== k) })} />{label}
            </label>
          ))}
        </div>
      </fieldset>
      <label style={{ display: "flex", gap: 8, alignItems: "center" }}><input type="checkbox" checked={d.enabled} onChange={(e) => setD({ ...d, enabled: e.target.checked })} />On</label>
    </Dialog>
  );
}

/** The settings of an e-mail or a cloud storage delivery: subject, or where and with which key; and the files sent. */
function OutSettings({ d, setD }: { d: Delivery; setD: (d: Delivery) => void }) {
  const x = d.extra ?? {};
  const set = (k: string, v: unknown) => setD({ ...d, extra: { ...x, [k]: v } });
  const files = (x.files as string[] | undefined) ?? ["original", "json"];
  const text = (k: string, label: string, placeholder = "", hint?: string) => (
    <Field label={label} hint={hint}>{(id) => <TextInput id={id} value={String(x[k] ?? "")} placeholder={placeholder} onChange={(e) => set(k, e.target.value)} />}</Field>);
  const provider = String(x.provider ?? "azure_blob");
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      {d.type === "email" ? <div className="df-grid df-g2">
        {text("subject", "Subject", "{process}: {file} ({status})", "{process}, {file}, {status} and {type} are filled in.")}
        {text("message", "Message (optional)", "Please find the invoice attached.")}
      </div> : <>
        <Field label="Where">{(id) => <Select id={id} value={provider} onChange={(e) => set("provider", e.target.value)}
          options={[{ value: "azure_blob", label: "Azure Blob container (address and SAS)" }, { value: "s3", label: "Amazon S3 or S3-compatible (MinIO, Wasabi, R2...)" },
            { value: "http_put", label: "A web address that takes uploads" }]} />}</Field>
        <div className="df-grid df-g3">
          {provider === "s3" && <>{text("region", "Region", "eu-central-1")}{text("endpoint", "Endpoint (optional)", "https://minio.example.com", "Empty: Amazon S3.")}{text("accessKey", "Access key id", "AKIA...")}</>}
          {text("prefix", "Folder in it (optional)", "invoices/2026")}
          {text("secretRef", provider === "s3" ? "Secret key (vault)" : provider === "azure_blob" ? "SAS token (vault)" : "Authorization header (vault, optional)", "folder/credential/attribute",
            "A vault reference you may use; the value is read at delivery time and never stored with the process.")}
        </div>
      </>}
      <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
        <legend style={{ fontWeight: 600, padding: 0, marginBottom: 6 }}>Send these files</legend>
        <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>{FILES.map(([k, label]) => (
          <label key={k} style={{ display: "flex", gap: 8, alignItems: "center" }}><input type="checkbox" checked={files.includes(k)}
            onChange={(e) => set("files", e.target.checked ? [...files, k] : files.filter((f) => f !== k))} />{label}</label>))}</div>
      </fieldset>
    </div>
  );
}

const OPS = [[">", "more than"], [">=", "at least"], ["<", "less than"], ["<=", "at most"], ["==", "exactly"], ["!=", "not"], ["between", "between"], ["outside", "outside"]];

export function CheckDialog({ open, value, design, tables, onClose, onSave }:
  { open: boolean; value: Check | null; design: ProcessDesign; tables: RefTable[]; onClose: () => void; onSave: (c: Check) => void }) {
  const [c, setC] = useState<Check>(() => value ?? { id: newId("c"), on: true, name: "", kind: "", test: "", ifFails: "review", type: "threshold", params: { field: "total", op: ">", value: 50000 } });
  const p = c.params;
  const setP = (patch: Record<string, unknown>) => setC({ ...c, params: { ...p, ...patch } });
  const fieldOptions = design.fields.map((f) => ({ value: f.name, label: f.label }));
  const fieldSelect = (key: string, label: string) => (
    <Field label={label}>{(id) => <Select id={id} value={String(p[key] ?? "")} onChange={(e) => setP({ [key]: e.target.value })}
      options={[{ value: "", label: "Choose a field" }, ...fieldOptions]} />}</Field>
  );
  const fieldList = (key: string, label: string) => {
    const chosen = (p[key] as string[] | undefined) ?? [];
    return (
      <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
        <legend style={{ fontWeight: 600, padding: 0, marginBottom: 6 }}>{label}</legend>
        <div className="df-grid df-g2" style={{ gap: 6 }}>
          {design.fields.map((f) => (
            <label key={f.name} style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input type="checkbox" checked={chosen.includes(f.name)} onChange={(e) => setP({ [key]: e.target.checked ? [...chosen, f.name] : chosen.filter((x) => x !== f.name) })} />{f.label}
            </label>
          ))}
        </div>
      </fieldset>
    );
  };
  const table = tables.find((t) => t.id === p.table);
  const ready = (() => {
    switch (c.type) {
      case "expression": return !!String(p.expression ?? "").trim();
      case "lookup": return !!(p.field && p.table && p.column);
      case "threshold": return !!p.field && !isNaN(parseAmount(p.value)) && (p.op !== "between" && p.op !== "outside" || !isNaN(parseAmount(p.value2)));
      case "pattern": return !!p.field && !!String(p.pattern ?? "").trim() && (() => { try { new RegExp(String(p.pattern)); return true; } catch { return false; } })();
      case "date_range": return !!p.field && (!!p.notFuture || !!p.notOlderThanDays || !!p.notLaterThanDays);
      case "duplicate": case "required": return ((p.fields as string[] | undefined) ?? []).length > 0;
      default: return true;
    }
  })();
  const save = () => {
    const params = c.type === "threshold" ? { ...p, value: parseAmount(p.value), ...(p.value2 !== undefined && p.value2 !== "" ? { value2: parseAmount(p.value2) } : {}) } : p;
    onSave({ ...c, params, name: c.name.trim() || RULE_KIND[c.type], kind: RULE_KIND[c.type], test: ruleTest(c.type, params) });
  };
  return (
    <Dialog open={open} onClose={onClose} title={value ? "Edit check" : "Add check"} width={620}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!ready} onClick={save}>Save</Button></>}>
      <div className="df-grid df-g2">
        <Field label="Kind of check">{(id) => <Select id={id} value={c.type} onChange={(e) => setC({ ...c, type: e.target.value as RuleType, params: {} })}
          options={(Object.keys(RULE_KIND) as RuleType[]).filter((k) => k !== "ksa_vat" || c.type === "ksa_vat").map((k) => ({ value: k, label: RULE_KIND[k] }))} />}</Field>
        <Field label="Name">{(id) => <TextInput id={id} value={c.name} placeholder={RULE_KIND[c.type]} onChange={(e) => setC({ ...c, name: e.target.value })} />}</Field>
      </div>
      {c.type === "threshold" && (
        <div className="df-grid df-g3">
          {fieldSelect("field", "Field")}
          <Field label="Is">{(id) => <Select id={id} value={String(p.op ?? ">")} onChange={(e) => setP({ op: e.target.value })} options={OPS.map(([value, label]) => ({ value, label }))} />}</Field>
          <Field label={p.op === "between" || p.op === "outside" ? "From" : "Amount"}>{(id) => <TextInput id={id} inputMode="decimal" value={String(p.value ?? "")} onChange={(e) => setP({ value: e.target.value })} />}</Field>
          {(p.op === "between" || p.op === "outside") && <Field label="To">{(id) => <TextInput id={id} inputMode="decimal" value={String(p.value2 ?? "")} onChange={(e) => setP({ value2: e.target.value })} />}</Field>}
        </div>
      )}
      {(c.type === "tax" || c.type === "ksa_vat") && <>
        <div className="df-grid df-g2">
          <Field label="Country or rules" hint="A preset fills the usual rates and tax id format; you may change both.">
            {(id) => <Select id={id} value={String(p.preset ?? (c.type === "ksa_vat" ? "ksa" : "custom"))} onChange={(e) => setC({ ...c, type: "tax", params: { ...p, preset: e.target.value } })}
              options={TAX_PRESETS.map((x) => ({ value: x.id, label: x.name }))} />}
          </Field>
          <Field label="Tax rates (optional)" hint="Percentages, comma separated, e.g. 20, 5, 0. Empty: the preset's; none = any rate.">
            {(id) => <TextInput id={id} value={String(p.rates ?? "")} placeholder="preset rates" onChange={(e) => setP({ rates: e.target.value })} />}
          </Field>
        </div>
        <div className="df-grid df-g2">
          {fieldSelect("idField", "Tax id field")}
          <Field label="Tax id format (optional)" hint="A pattern; empty: the preset's.">
            {(id) => <TextInput id={id} mono value={String(p.idPattern ?? "")} placeholder="preset format" onChange={(e) => setP({ idPattern: e.target.value })} />}
          </Field>
        </div>
        <p className="df-small" style={{ margin: 0 }}>Also checks that the tax is one of the rates of the subtotal, and that subtotal + tax = total (to a cent or 0.1%).</p>
      </>}
      {c.type === "pattern" && (
        <div className="df-grid df-g2">
          {fieldSelect("field", "Field")}
          <Field label="Must match" hint="A pattern (regular expression) the whole value must match.">
            {(id) => <TextInput id={id} mono value={String(p.pattern ?? "")} onChange={(e) => setP({ pattern: e.target.value })} />}
          </Field>
        </div>
      )}
      {c.type === "date_range" && (
        <div className="df-grid df-g3">
          {fieldSelect("field", "Date field")}
          <Field label="At most this many days old">{(id) => <TextInput id={id} inputMode="numeric" value={String(p.notOlderThanDays ?? "")} placeholder="any age" onChange={(e) => setP({ notOlderThanDays: e.target.value ? Number(e.target.value) : undefined })} />}</Field>
          <label style={{ display: "flex", gap: 8, alignItems: "center", alignSelf: "end" }}><input type="checkbox" checked={!!p.notFuture} onChange={(e) => setP({ notFuture: e.target.checked })} />Not in the future</label>
        </div>
      )}
      {c.type === "lookup" && (
        <div className="df-grid df-g3">
          {fieldSelect("field", "Field on the document")}
          <Field label="Reference table">{(id) => <Select id={id} value={String(p.table ?? "")} onChange={(e) => setP({ table: e.target.value, column: "" })}
            options={[{ value: "", label: tables.length ? "Choose a table" : "No tables yet" }, ...tables.map((t) => ({ value: t.id, label: t.name }))]} />}</Field>
          <Field label="Column to match">{(id) => <Select id={id} value={String(p.column ?? "")} onChange={(e) => setP({ column: e.target.value })}
            options={[{ value: "", label: "Choose a column" }, ...(table?.columns ?? []).map((col) => ({ value: col, label: col }))]} />}</Field>
        </div>
      )}
      {c.type === "duplicate" && fieldList("fields", "Same values in these fields means a duplicate")}
      {c.type === "required" && fieldList("fields", "These fields must not be empty")}
      {c.type === "expression" && (
        <Field label="Must be true" hint="Use the field names, for example abs(subtotal + vat_amount - total) <= 0.05; value_or(discount, 0) counts a missing field as 0">
          {(id) => <TextInput id={id} mono value={String(p.expression ?? "")} onChange={(e) => setP({ expression: e.target.value })} />}
        </Field>
      )}
      <Field label="If it fails">{(id) => <Select id={id} value={c.ifFails} onChange={(e) => setC({ ...c, ifFails: e.target.value as Check["ifFails"] })}
        options={(Object.keys(IF_FAILS) as Check["ifFails"][]).map((k) => ({ value: k, label: IF_FAILS[k] }))} />}</Field>
    </Dialog>
  );
}
