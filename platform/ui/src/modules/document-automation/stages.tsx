import { useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../../components";
import { HINTS } from "./help";
import { MicrosoftAccount } from "./msSignIn";
import { AdvancedToggle } from "./ui";
import { RULE_KIND, ruleTest, type Check, type Delivery, type DeliveryType, type DocFlowApi, type EmailInbox, type InboxStatus, type ProcessDesign, type ProcessSettings, type RefTable, type RuleType } from "./api";

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
  start_bot: { label: "Start a bot", target: "Bot", placeholder: "Finance/Post invoice to ERP" },
};

/** The document states a delivery can be sent on, in plain words. */
const SEND_ON: [string, string][] = [
  ["success", "Done with no review"], ["validated", "Approved by a reviewer"], ["validation", "Waiting for review"], ["invalid", "Rejected"], ["failed", "Failed"],
];

type SetSettings = (patch: Partial<ProcessSettings>) => void;

export function IntakeStage({ s, set, inbox, live, vaultRefs, api }:
  { s: ProcessSettings; set: SetSettings; inbox?: InboxStatus | null; live?: boolean; vaultRefs?: { ref: string; label: string }[] | null; api?: DocFlowApi }) {
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
      <Field label="Watched folder" hint="DocFlow reads every new file put here. Empty: upload only.">
        {(id) => <TextInput id={id} value={s.inputFolder} placeholder="\\scan01\invoices" onChange={(e) => set({ inputFolder: e.target.value })} />}
      </Field>
      <Field label="Check the folder every">
        {(id) => <Select id={id} value={String(s.pollSeconds)} onChange={(e) => set({ pollSeconds: Number(e.target.value) })}
          options={[{ value: "30", label: "30 seconds" }, { value: "60", label: "1 minute" }, { value: "300", label: "5 minutes" }, { value: "900", label: "15 minutes" }]} />}
      </Field>
      <p className="df-small" style={{ gridColumn: "1 / -1", margin: 0 }}>Upload is always on. Agents send documents with the "Send document to process" action.</p>
    </div>
    <div style={{ display: "flex", gap: 10, alignItems: "flex-start" }}>
      <button type="button" role="switch" className="df-switch" aria-checked={s.einvoiceFirst} aria-labelledby="df-einvoice-label"
        onClick={() => set({ einvoiceFirst: !s.einvoiceFirst })} />
      <span><b id="df-einvoice-label">Read e-invoice data first (ZATCA XML, QR codes)</b>
        <span className="df-small" style={{ display: "block" }}>{HINTS.einvoiceFirst}</span></span>
    </div>
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
    </div>
  );
}

export function ExtractStage({ s, set }: { s: ProcessSettings; set: SetSettings }) {
  const [advanced, setAdvanced] = useState(s.extraction !== "auto");   // a chosen engine is shown, not hidden
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <p style={{ margin: 0 }}>The fields to read come from the document type. {s.extraction === "auto"
        ? "They are read the way the Engines page says (on this server unless cloud reading is switched on)."
        : "This process uses its own engine (below)."}</p>
      <AdvancedToggle open={advanced} onToggle={() => setAdvanced(!advanced)} what="choose a different engine for this process" />
      {advanced && <Field label="Engine that reads the fields" hint="Default engine: the one set on the Engines page.">
        {(id) => <Select id={id} value={s.extraction} onChange={(e) => set({ extraction: e.target.value })} options={EXTRACTION} />}
      </Field>}
    </div>
  );
}

/** The thresholds a field can have of its own (70–99%), and the saved one when it is another. */
const FIELD_LEVELS = [0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.97, 0.99];
const levelOptions = (saved: number) => [...new Set([...FIELD_LEVELS, saved])].sort((a, b) => a - b)
  .map((v) => ({ value: String(v), label: Math.round(v * 100) + "%" }));

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
                <td><select className="ui-input ui-select" aria-label={`Threshold for ${labelOf(name)}`} value={String(level)}
                  onChange={(e) => write(entries.map(([n, v]) => [n, n === name ? Number(e.target.value) : v]))}>
                  {levelOptions(level).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select></td>
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
        {(id) => <Select id={id} value={String(s.fieldConfidence)} onChange={(e) => set({ fieldConfidence: Number(e.target.value) })}
          options={["0.7", "0.75", "0.8", "0.85", "0.9", "0.95"].map((v) => ({ value: v, label: Math.round(Number(v) * 100) + "% confidence" }))} />}
      </Field>
      <Field label="Review within" hint="After this, the review shows as overdue.">
        {(id) => <Select id={id} value={String(s.slaHours)} onChange={(e) => set({ slaHours: Number(e.target.value) })}
          options={[["4", "4 hours"], ["8", "8 hours"], ["24", "24 hours"], ["48", "2 days"], ["72", "3 days"]].map(([value, label]) => ({ value, label }))} />}
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

export function DeliveryDialog({ open, value, onClose, onSave }: { open: boolean; value: Delivery | null; onClose: () => void; onSave: (d: Delivery) => void }) {
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
      <Field label={kind.target} hint={d.type === "start_bot" ? "The agent's path in the agent repository. It runs as you, with the document's values as inputs (document, document_id, or any field by its name)." : undefined}>
        {(id) => <TextInput id={id} value={d.target} placeholder={kind.placeholder} onChange={(e) => setD({ ...d, target: e.target.value })} />}
      </Field>
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

const OPS = [[">", "more than"], [">=", "at least"], ["<", "less than"], ["<=", "at most"], ["==", "exactly"]];

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
      case "threshold": return !!p.field && p.value !== "" && !isNaN(Number(p.value));
      case "duplicate": case "required": return ((p.fields as string[] | undefined) ?? []).length > 0;
      default: return true;
    }
  })();
  const save = () => {
    const params = c.type === "threshold" ? { ...p, value: Number(p.value) } : p;
    onSave({ ...c, params, name: c.name.trim() || RULE_KIND[c.type], kind: RULE_KIND[c.type], test: ruleTest(c.type, params) });
  };
  return (
    <Dialog open={open} onClose={onClose} title={value ? "Edit check" : "Add check"} width={620}
      footer={<><Button onClick={onClose}>Cancel</Button><Button kind="primary" disabled={!ready} onClick={save}>Save</Button></>}>
      <div className="df-grid df-g2">
        <Field label="Kind of check">{(id) => <Select id={id} value={c.type} onChange={(e) => setC({ ...c, type: e.target.value as RuleType, params: {} })}
          options={(Object.keys(RULE_KIND) as RuleType[]).map((k) => ({ value: k, label: RULE_KIND[k] }))} />}</Field>
        <Field label="Name">{(id) => <TextInput id={id} value={c.name} placeholder={RULE_KIND[c.type]} onChange={(e) => setC({ ...c, name: e.target.value })} />}</Field>
      </div>
      {c.type === "threshold" && (
        <div className="df-grid df-g3">
          {fieldSelect("field", "Field")}
          <Field label="Is">{(id) => <Select id={id} value={String(p.op ?? ">")} onChange={(e) => setP({ op: e.target.value })} options={OPS.map(([value, label]) => ({ value, label }))} />}</Field>
          <Field label="Amount">{(id) => <TextInput id={id} inputMode="decimal" value={String(p.value ?? "")} onChange={(e) => setP({ value: e.target.value })} />}</Field>
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
        <Field label="Must be true" hint="Use the field names, for example abs(subtotal + vat_amount - total) <= 0.05">
          {(id) => <TextInput id={id} mono value={String(p.expression ?? "")} onChange={(e) => setP({ expression: e.target.value })} />}
        </Field>
      )}
      {c.type === "ksa_vat" && <p className="df-muted" style={{ margin: 0 }}>Checks the supplier VAT number (15 digits, starts and ends with 3), that VAT is 15% of the subtotal, and that the totals add up.</p>}
      <Field label="If it fails">{(id) => <Select id={id} value={c.ifFails} onChange={(e) => setC({ ...c, ifFails: e.target.value as Check["ifFails"] })}
        options={(Object.keys(IF_FAILS) as Check["ifFails"][]).map((k) => ({ value: k, label: IF_FAILS[k] }))} />}</Field>
    </Dialog>
  );
}
