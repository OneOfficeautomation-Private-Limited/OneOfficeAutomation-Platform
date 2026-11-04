import { useState } from "react";
import { Button, Dialog, Field, Select, TextInput } from "../components";
import { RULE_KIND, ruleTest, type Check, type Delivery, type DeliveryType, type ProcessDesign, type ProcessSettings, type RefTable, type RuleType } from "./api";

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

export function IntakeStage({ s, set }: { s: ProcessSettings; set: SetSettings }) {
  return (
    <div className="df-grid df-g2">
      <Field label="Watched folder" hint="DocFlow reads every new file put here. Empty: upload only.">
        {(id) => <TextInput id={id} value={s.inputFolder} placeholder="\\scan01\invoices" onChange={(e) => set({ inputFolder: e.target.value })} />}
      </Field>
      <Field label="Check the folder every">
        {(id) => <Select id={id} value={String(s.pollSeconds)} onChange={(e) => set({ pollSeconds: Number(e.target.value) })}
          options={[{ value: "30", label: "30 seconds" }, { value: "60", label: "1 minute" }, { value: "300", label: "5 minutes" }, { value: "900", label: "15 minutes" }]} />}
      </Field>
      <p className="df-small" style={{ gridColumn: "1 / -1", margin: 0 }}>Upload is always on. Email and bots come in through their own settings.</p>
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
  return (
    <Field label="Engine that reads the fields" hint="The fields themselves come from the document type.">
      {(id) => <Select id={id} value={s.extraction} onChange={(e) => set({ extraction: e.target.value })} options={EXTRACTION} />}
    </Field>
  );
}

export function ReviewStage({ s, set }: { s: ProcessSettings; set: SetSettings }) {
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
      <Field label={kind.target} hint={d.type === "start_bot" ? "The bot's path in the agent repository. It gets the document's fields as input." : undefined}>
        {(id) => <TextInput id={id} value={d.target} placeholder={kind.placeholder} onChange={(e) => setD({ ...d, target: e.target.value })} />}
      </Field>
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
