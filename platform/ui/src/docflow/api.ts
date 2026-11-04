/**
 * Document Automation (DocFlow) data the screens use. `DocFlowApi` is the seam: today `SampleApi` answers from sample
 * data; the real client will call the DocFlow service through the Command Center (/api/docflow/…, tenant from the
 * signed-in user) with the same shapes, so the screens do not change when it is wired.
 */

export type DocStatus = "processing" | "done" | "review" | "failed" | "split";
export type Source = "upload" | "folder" | "email" | "bot" | "split";

export interface ProcessSummary {
  id: string; name: string; folder: string; docType: string; state: "live" | "draft"; version: number;
  today: number; review: number; processing: number; failed: number; done: number; straightThrough: number;
  lastDocument: string; lastTime: string; sources: Source[]; deliversTo: string[];
}

export interface DocumentRow {
  id: string; processId: string; name: string; pages: string; supplier: string; invoice: string; total: string;
  time: string; source: Source; sourceLabel: string; status: DocStatus; note: string; engine: string; confidence: number | null;
}

export interface ReviewField { id: string; label: string; value: string; confidence: number; flagged?: { reason: string; suggestion?: string } }
export interface ReviewCheck { text: string; passed: boolean; detail: string }
export interface ReviewTask {
  documentId: string; processId?: string; status: DocStatus; reviewedBy?: string; name: string; processName: string; docType: string; cameIn: string; dueIn: string; targetHours: number; position: number; of: number;
  engineTrail: string; pageLines: { text: string; mark?: "flag" | "ok"; bold?: boolean }[]; fields: ReviewField[]; checks: ReviewCheck[];
}

export type EngineStatus = "ready" | "optin" | "setup";
export interface Engine { id: string; name: string; does: string; status: EngineStatus; runs: "server" | "cloud"; price: string; free: boolean; key: string }
export interface EngineOption { id: "onecore" | "oneconnect" | "onemind"; name: string; description: string; color: string; engines: Engine[] }
/** One setting of an engine (its address, key, model…), as DocFlow's settings describe it. Secrets come back masked. */
export interface EngineField { key: string; label: string; type: string; value: string; help?: string; options?: string[]; placeholder?: string }
export interface Routing { cloudOptIn: boolean; retryBelow: number; localRetry: string; cloudEngine: string }

/** DocFlow's kinds of check (rules); `params` are DocFlow's own settings for that kind. */
export type RuleType = "duplicate" | "lookup" | "ksa_vat" | "threshold" | "required" | "expression";
export interface Check { id: string; on: boolean; name: string; kind: string; test: string; ifFails: "review" | "reject" | "warn"; type: RuleType; params: Record<string, unknown> }
export type DeliveryType = "excel" | "folder" | "webhook" | "start_bot";
/** Where results go. target: the folder or Excel path, the webhook address, or the bot to start. */
export interface Delivery { id: string; type: DeliveryType; name: string; enabled: boolean; target: string; on: string[]; pool?: string }
/**
 * A process's email inbox, read by the Command Center: IMAP (kind "email") or Microsoft 365 (kind "outlook"), as email
 * triggers. Secrets are vault references (folder/credential/attribute). Keys are DocFlow's, as stored.
 */
export interface EmailInbox {
  enabled: boolean; kind: "email" | "outlook"; host?: string; port?: number; ssl?: boolean; username?: string; password_ref?: string;
  mailbox?: string; tenant_id?: string; client_id?: string; client_secret_ref?: string; mail_folder?: string;
  from_contains?: string; subject_contains?: string; interval_seconds?: number;
}
export interface ProcessSettings {
  inputFolder: string; pollSeconds: number; classifyMode: "single" | "auto"; split: boolean; extraction: string;
  fieldConfidence: number; slaHours: number; email: EmailInbox | null;
}
/** How an email inbox's last check went (the Command Center reads the mail). */
export interface InboxStatus { lastCheck: string | null; error: string | null; documents: number }
export interface ProcessDesign {
  id: string; name: string; state: "live" | "draft"; version: number; folder: string; docType: string; checks: Check[];
  /** The document types it reads (ids); docType is their names. */
  docTypeIds: string[];
  stages: { name: string; summary: string; done: boolean }[];
  settings: ProcessSettings; deliveries: Delivery[];
  /** The fields its document types read, for checks to test. */
  fields: { name: string; label: string }[];
}

export const RULE_KIND: Record<RuleType, string> = {
  duplicate: "Duplicate", lookup: "Reference data lookup", ksa_vat: "KSA VAT (ZATCA)", threshold: "Amount threshold", required: "Required fields", expression: "Arithmetic",
};

/** What a check tests, in words, from its kind and settings. */
export function ruleTest(type: RuleType, p: Record<string, unknown>): string {
  const t = (v: unknown) => (v === undefined || v === null || v === "" ? "—" : String(v));
  const list = (v: unknown) => (Array.isArray(v) && v.length ? (v as string[]).join(", ") : "—");
  switch (type) {
    case "expression": return t(p.expression);
    case "lookup": return `${t(p.field)} is in "${t(p.table)}" (${t(p.column)})`;
    case "threshold": return `${t(p.field)} ${t(p.op ?? ">")} ${t(p.value)}`;
    case "duplicate": return `Same ${list(p.fields)} seen before`;
    case "ksa_vat": return "15-digit VAT number starting and ending with 3; VAT is 15%; totals add up";
    case "required": return list(p.fields);
  }
}

/**
 * The stage summaries of a process, from its settings (the builder and both clients share them), in the order a
 * developer thinks: where documents come from, what they are, how they are read, checked, reviewed and delivered.
 */
export function stagesOf(d: Pick<ProcessDesign, "settings" | "deliveries" | "checks" | "docType">, engineName: (id: string) => string) {
  const s = d.settings;
  const on = d.checks.filter((c) => c.on).length;
  const sources = ["upload", s.inputFolder ? "watched folder" : "", s.email?.enabled ? "email inbox" : ""].filter(Boolean).join(", ");
  return [
    { name: "Document ingestion", summary: `Sources: ${sources}${s.inputFolder ? `; ${s.inputFolder} every ${s.pollSeconds} s` : ""}`, done: true },
    { name: "Document classification", summary: s.classifyMode === "auto" ? `Recognised automatically${s.split ? "; batches are split" : ""}` : `Type: ${d.docType || "not chosen"}${s.split ? "; batches are split" : ""}`, done: !!d.docType },
    { name: "Data extraction", summary: `Fields of ${d.docType || "each type"}; ${s.extraction === "auto" ? "default engine" : engineName(s.extraction)}`, done: true },
    { name: "Validation", summary: d.checks.length ? `${on} of ${d.checks.length} checks on` : "No checks yet", done: d.checks.length > 0 },
    { name: "Human review", summary: `Under ${Math.round(s.fieldConfidence * 100)}% confidence; to the Task inbox, ${s.slaHours} h target`, done: true },
    { name: "Export & integration", summary: d.deliveries.length ? d.deliveries.map((x) => x.name || x.type).join(", ") : "Not set: Excel, folder, webhook or a bot", done: d.deliveries.length > 0 },
  ];
}

export type FieldType = "Text" | "Date" | "Amount" | "Number" | "Table" | "Choice";
/** A field a document type reads. key: its name in results and checks; aliases: other labels it has on pages; learned: labels picked up from reviews. */
export interface DocTypeField { key: string; label: string; aliases: string[]; type: FieldType; required: boolean; regex: string; learned: string[] }
/** id is "" for a type not saved yet (a new one, or one proposed from samples). */
export interface DocType {
  id: string; name: string; origin: "built-in" | "made-here"; meta: string; keywords: string[]; languages: string[]; usedBy: string[]; fields: DocTypeField[];
}

/** A reference table: any columns, as uploaded. Its rows are loaded on demand (refTableRows). */
export interface RefTable { id: string; name: string; rows: number; meta: string; columns: string[]; updated?: string; usedBy?: string[] }
export type RefRow = Record<string, string | number | boolean | null>;

export interface DayCount { label: string; straight: number; reviewed: number }
export interface Overview {
  today: number; straightThrough: number; waiting: number; overdue: number; failed: number; pagesThisMonth: number; cloudCost: number;
  days: DayCount[]; alerts: { level: "bad" | "warn" | "info"; title: string; text: string; to: string }[];
  readBy: { label: string; share: number }[];
}

export interface TestResult { field: string; a: string; aConf: string; b: string; bConf: string; check: string; differs: boolean }
/** A Test lab run: the field rows, and per engine its time, page confidence, whether it would go to review (or why it could not run). */
export interface TestRun { rows: TestResult[]; runs: { engine: string; seconds: number; confidence: number | null; review: boolean; readBy: string; error?: string }[] }

/** docType is a document type id (DocType.id). */
/** email: the person will set up the inbox next (in the builder's step 1, Document ingestion). */
export interface NewProcess { name: string; docType: string; folder: string; watchedFolder?: string; pollSeconds?: number; email?: boolean; fromBot: boolean }

export interface DocFlowApi {
  overview(): Promise<Overview>;
  processes(): Promise<ProcessSummary[]>;
  documents(processId: string): Promise<DocumentRow[]>;
  addDocuments(files: File[], processId: string | "auto", folder: string): Promise<{ processId: string }>;
  createProcess(p: NewProcess): Promise<{ id: string }>;
  /** The platform folders a new process can go in (where the person may edit). */
  folders(): Promise<string[]>;
  reviewTask(documentId: string): Promise<ReviewTask>;
  /** Saves the review; `next` is the next document waiting in the same process, if any. */
  completeReview(documentId: string, fields: Record<string, string>, decision: "approve" | "reject"): Promise<{ next?: string }>;
  /** Reads the document again as another type; it may then need review again. */
  changeDocType(documentId: string, docTypeId: string): Promise<{ documentId: string; needsReview: boolean }>;
  /** Where Export downloads a process's results (done and approved documents). */
  exportUrl(processId: string, format: "xlsx" | "csv"): string;
  engines(): Promise<{ routing: Routing; options: EngineOption[] }>;
  saveRouting(r: Routing): Promise<void>;
  /** Runs a tiny real request through one engine. */
  testEngine(id: string): Promise<{ ok: boolean; message: string }>;
  engineSettings(id: string): Promise<EngineField[]>;
  /** Server-wide engine settings (keys, addresses); only for the server's administrators. */
  saveEngineSettings(values: Record<string, string>): Promise<void>;
  processDesign(id: string): Promise<ProcessDesign>;
  /** Saves stages, checks and deliveries; DocFlow counts a new version when anything changed. */
  saveProcess(d: ProcessDesign): Promise<ProcessDesign>;
  setLive(id: string, live: boolean): Promise<void>;
  inboxStatus(processId: string): Promise<InboxStatus | null>;
  docTypes(): Promise<DocType[]>;
  refTables(): Promise<RefTable[]>;
  /** A table's rows (up to `limit`), for the preview and for downloading it. */
  refTableRows(id: string, limit?: number): Promise<RefRow[]>;
  /** Adds a table from a CSV or Excel file; a table with the same name is replaced. */
  uploadRefTable(file: File, name: string): Promise<{ id: string }>;
  deleteRefTable(id: string): Promise<void>;
  /** Reads the file as the document type with engine A (and B), side by side. Nothing is kept. */
  runTest(file: File, docTypeId: string, engineA: string, engineB: string | null): Promise<TestRun>;
  saveDocType(t: DocType): Promise<DocType>;
  deleteDocType(id: string): Promise<void>;
  /** Proposes a document type (not saved) from 1 to 3 sample files. */
  suggestDocType(files: File[]): Promise<DocType>;
}

/* ------------------------------------------------------------------ sample data (from the approved designs) */

const PROCESSES: ProcessSummary[] = [
  { id: "ap-invoices", name: "AP invoices", folder: "Finance / Accounts payable", docType: "Supplier invoice", state: "live", version: 4,
    today: 74, review: 5, processing: 2, failed: 0, done: 67, straightThrough: 91, lastDocument: "INV-2026-3944.pdf", lastTime: "10:42",
    sources: ["upload", "folder", "bot"], deliversTo: ["Excel register", 'bot "Post to ERP"'] },
  { id: "smart-inbox", name: "Smart inbox", folder: "Shared / Unsorted", docType: "Any", state: "live", version: 2,
    today: 21, review: 3, processing: 0, failed: 1, done: 17, straightThrough: 81, lastDocument: "batch_0930.pdf", lastTime: "10:31",
    sources: ["upload", "folder"], deliversTo: ["Matching process"] },
  { id: "expense-claims", name: "Expense claims", folder: "Finance / Expenses", docType: "Receipt", state: "live", version: 1,
    today: 18, review: 0, processing: 1, failed: 0, done: 17, straightThrough: 94, lastDocument: "IMG_2231.jpg", lastTime: "10:12",
    sources: ["upload", "bot"], deliversTo: ["Excel register"] },
  { id: "deliveries", name: "Deliveries", folder: "Operations / Goods received", docType: "Delivery note", state: "live", version: 3,
    today: 15, review: 1, processing: 0, failed: 0, done: 14, straightThrough: 93, lastDocument: "Part 2 of batch_0930.pdf", lastTime: "10:31",
    sources: ["folder", "email"], deliversTo: ["Folder"] },
];

const doc = (o: Partial<DocumentRow> & Pick<DocumentRow, "id" | "name" | "status">): DocumentRow => ({
  processId: "ap-invoices", pages: "1 page", supplier: "—", invoice: "—", total: "—", time: "", source: "upload", sourceLabel: "Upload",
  note: "", engine: "Tesseract", confidence: null, ...o });

const DOCUMENTS: DocumentRow[] = [
  doc({ id: "scan_0457", name: "scan_0457.jpg", status: "review", supplier: "Red Sea Print House", invoice: "INV-2026-3944", total: "47,971.34 SAR",
    time: "10:40", source: "folder", sourceLabel: "Folder \\\\scan01\\invoices", engine: "Tesseract, then PaddleOCR", confidence: 78, note: "VAT amount fails the total check" }),
  doc({ id: "acme_4011", name: "INV-ACME-4011.pdf", status: "processing", time: "10:44", sourceLabel: "Upload · Sara Khan", engine: "Reading page 1…" }),
  doc({ id: "inv_3944", name: "INV-2026-3944.pdf", status: "done", supplier: "ACME Trading Co. LLC", invoice: "INV-2026-3944", total: "11,500.00 SAR",
    time: "10:42", sourceLabel: "Upload · Sara Khan", engine: "PDF text layer", confidence: 100 }),
  doc({ id: "batch_0930_1", name: "Part 1 of batch_0930.pdf", pages: "pages 1–2", status: "done", supplier: "Delta Industrial Parts", invoice: "INV-2026-0187",
    total: "8,412.50 SAR", time: "10:31", source: "split", sourceLabel: "From Smart inbox", confidence: 96 }),
  doc({ id: "gos_0412", name: "GOS-INV-0412.pdf", pages: "2 pages", status: "done", supplier: "Gulf Office Supplies", invoice: "INV-2026-0412", total: "2,358.45 SAR",
    time: "09:58", source: "bot", sourceLabel: "Bot · Portal download", confidence: 97 }),
  doc({ id: "nl_7710", name: "NL-INV-7710.pdf", pages: "3 pages", status: "review", supplier: "Nile Logistics Ltd", invoice: "INV-2026-7710", total: "15,040.00 SAR",
    time: "09:44", source: "bot", sourceLabel: "Bot · Portal download", confidence: 88, note: "Supplier not in reference data" }),
  doc({ id: "fax_0931", name: "fax_0931.png", status: "done", supplier: "Falcon IT Services", invoice: "INV-2026-0931", total: "9,067.42 SAR",
    time: "09:31", source: "folder", sourceLabel: "Folder \\\\scan01\\invoices", engine: "Tesseract, then PaddleOCR", confidence: 98 }),
  doc({ id: "scan_0452", name: "scan_0452.jpg", status: "done", supplier: "Oasis Catering Co.", invoice: "INV-2026-0452", total: "1,725.00 SAR",
    time: "09:12", source: "folder", sourceLabel: "Folder \\\\scan01\\invoices", confidence: 95 }),
  doc({ id: "batch_0930", processId: "smart-inbox", name: "batch_0930.pdf", pages: "7 pages", status: "split", time: "10:31", source: "folder",
    sourceLabel: "Folder \\\\scan01\\invoices", note: "Split into 3 parts" }),
  doc({ id: "batch_0930_3", processId: "smart-inbox", name: "Part 3 of batch_0930.pdf", pages: "pages 5–7", status: "review", time: "10:31", source: "split",
    sourceLabel: "Split from batch", confidence: 61, note: "Choose a document type" }),
  doc({ id: "contract", processId: "smart-inbox", name: "contract_signed.pdf", pages: "12 pages", status: "failed", time: "09:15",
    sourceLabel: "Upload · Omar Haddad", engine: "—", note: "PDF is password-protected" }),
  doc({ id: "img_2231", processId: "expense-claims", name: "IMG_2231.jpg", status: "processing", time: "10:12", source: "bot",
    sourceLabel: "Bot · Portal download", engine: "Reading page 1…" }),
  doc({ id: "dn_118", processId: "deliveries", name: "Part 2 of batch_0930.pdf", pages: "pages 3–4", status: "done", time: "10:31", source: "split",
    sourceLabel: "Split from batch", confidence: 93 }),
];

const REVIEW: ReviewTask = {
  documentId: "scan_0457", processId: "ap-invoices", status: "review", name: "scan_0457.jpg", processName: "AP invoices", docType: "Supplier invoice", cameIn: "10:40 from \\\\scan01\\invoices",
  dueIn: "3 h", targetHours: 24, position: 1, of: 9, engineTrail: "read by Tesseract, re-read by PaddleOCR (78%)",
  pageLines: [
    { text: "Red Sea Print House", bold: true }, { text: "Tax Invoice", bold: true }, { text: "Invoice Number: INV-2026-3944" },
    { text: "Invoice Date: 05/02/2026" }, { text: "Supplier VAT No: 318609139099603" }, { text: "Customer: Al Noor Holdings" },
    { text: "Printer toner   9   2,382.93" }, { text: "Cloud backup (1 yr)   10   6,613.20" }, { text: "RPA licence   9   29,837.52" },
    { text: "On-site training   2   2,880.56" }, { text: "Subtotal: 41,714.21", mark: "ok" }, { text: "VAT 15%: 6,257.13", mark: "flag" },
    { text: "Total: 47,971.34 SAR", mark: "ok", bold: true }],
  fields: [
    { id: "vat_amount", label: "VAT amount", value: "6,287.13", confidence: 61,
      flagged: { reason: "Fails 2 checks: subtotal + VAT does not equal the total, and it is not 15% of the subtotal.", suggestion: "6,257.13" } },
    { id: "supplier", label: "Supplier", value: "Red Sea Print House", confidence: 96 },
    { id: "invoice_number", label: "Invoice number", value: "INV-2026-3944", confidence: 92 },
    { id: "invoice_date", label: "Invoice date", value: "05/02/2026", confidence: 95 },
    { id: "supplier_vat", label: "Supplier VAT no.", value: "318609139099603", confidence: 93 },
    { id: "subtotal", label: "Subtotal", value: "41,714.21", confidence: 97 },
    { id: "total", label: "Total", value: "47,971.34", confidence: 97 }],
  checks: [
    { text: "Subtotal + VAT = total", passed: false, detail: "41,714.21 + 6,287.13 ≠ 47,971.34" },
    { text: "VAT is 15% of subtotal", passed: false, detail: "expected 6,257.13" },
    { text: "Supplier is in reference data", passed: true, detail: "Suppliers list" },
    { text: "Not a duplicate", passed: true, detail: "no earlier INV-2026-3944" }],
};

const ENGINES: EngineOption[] = [
  { id: "onecore", name: "OneCore", description: "Open source, runs on this server, free", color: "#2f6fde", engines: [
    { id: "tesseract", name: "Tesseract 5", does: "Reads every page · 13 languages", status: "ready", runs: "server", price: "Free", free: true, key: "—" },
    { id: "paddleocr", name: "PaddleOCR", does: "Retries weak pages · faxes, photos", status: "ready", runs: "server", price: "Free", free: true, key: "—" },
    { id: "pdf-text", name: "PDF text layer", does: "Digital PDFs, no OCR", status: "ready", runs: "server", price: "Free", free: true, key: "—" },
    { id: "builtin-rules", name: "Rules extractor", does: "Fields by labels and patterns", status: "ready", runs: "server", price: "Free", free: true, key: "—" }] },
  { id: "oneconnect", name: "OneConnect", description: "Cloud OCR, for weak pages", color: "#8b6ae8", engines: [
    { id: "azure-di", name: "Azure Document Intelligence", does: "Read, layout, prebuilt invoice", status: "optin", runs: "cloud", price: "$1.50 · $10 prebuilt", free: false, key: "Vault: not set" },
    { id: "google-docai", name: "Google Document AI", does: "OCR, form parser", status: "optin", runs: "cloud", price: "$1.50", free: false, key: "Vault: not set" },
    { id: "aws-textract", name: "AWS Textract", does: "Text, tables, expenses", status: "optin", runs: "cloud", price: "$1.50 · $10 expenses", free: false, key: "Vault: not set" },
    { id: "abbyy", name: "ABBYY", does: "Depends on your ABBYY product", status: "setup", runs: "cloud", price: "Contract", free: false, key: "—" }] },
  { id: "onemind", name: "OneMind", description: "AI reads the fields of any document", color: "#b0307e", engines: [
    { id: "claude", name: "Claude (Anthropic)", does: "Model: Sonnet 5.5 · reads page images", status: "optin", runs: "cloud", price: "≈ $16", free: false, key: "Model connection: not set" },
    { id: "llm-cloud", name: "Cloud AI API", does: "Any OpenAI-compatible service", status: "optin", runs: "cloud", price: "Depends", free: false, key: "Model connection: not set" },
    { id: "llm-local", name: "Self-hosted model", does: "Your own server, nothing leaves", status: "setup", runs: "server", price: "Free", free: true, key: "—" }] },
];

const DESIGN_CHECKS: Check[] = [
  { id: "totals", on: true, name: "Totals add up", kind: "Arithmetic", test: "Subtotal + VAT = total, within 0.05", ifFails: "review", type: "expression", params: { expression: "abs(subtotal + vat_amount - total) <= 0.05" } },
  { id: "vat15", on: true, name: "VAT is 15%", kind: "Arithmetic", test: "VAT = 15% of subtotal, within 0.05", ifFails: "review", type: "expression", params: { expression: "abs(subtotal * 0.15 - vat_amount) <= 0.05" } },
  { id: "ksa-vat", on: true, name: "Valid VAT number", kind: "KSA VAT (ZATCA)", test: "15 digits, starts and ends with 3", ifFails: "review", type: "ksa_vat", params: {} },
  { id: "duplicate", on: true, name: "Not a duplicate", kind: "Duplicate", test: "Same supplier VAT no. and invoice number seen before", ifFails: "reject", type: "duplicate", params: { fields: ["supplier_vat", "invoice_number"] } },
  { id: "supplier", on: true, name: "Known supplier", kind: "Reference data lookup", test: 'Supplier VAT no. is in "Suppliers"; adds supplier code', ifFails: "review", type: "lookup", params: { field: "supplier_vat", table: "suppliers", column: "vat" } },
  { id: "large", on: false, name: "Large invoice", kind: "Amount threshold", test: "Total over 50,000 SAR needs a second person", ifFails: "review", type: "threshold", params: { field: "total", op: ">", value: 50000 } },
];

const DESIGN_BASE = {
  id: "supplier-invoices-ksa", name: "Supplier invoices KSA", state: "draft" as const, version: 1, folder: "Finance / Accounts payable", docType: "Supplier invoice",
  docTypeIds: ["supplier-invoice"], checks: DESIGN_CHECKS,
  settings: { inputFolder: "\\\\scan01\\invoices\\ksa", pollSeconds: 30, classifyMode: "single" as const, split: false, extraction: "auto", fieldConfidence: 0.85, slaHours: 24,
    email: null as EmailInbox | null },
  deliveries: [] as Delivery[],
  fields: [["invoice_number", "Invoice number"], ["invoice_date", "Invoice date"], ["supplier_name", "Supplier"], ["supplier_vat", "Supplier VAT no."],
    ["subtotal", "Subtotal"], ["vat_amount", "VAT amount"], ["total", "Total"]].map(([name, label]) => ({ name, label })),
};
const DESIGN: ProcessDesign = { ...DESIGN_BASE, stages: stagesOf(DESIGN_BASE, (id) => id) };

const f = (key: string, label: string, aliases: string[], type: FieldType, required: boolean, regex = "", learned: string[] = []): DocTypeField =>
  ({ key, label, aliases, type, required, regex, learned });
const DOC_TYPES: DocType[] = [
  { id: "supplier-invoice", name: "Supplier invoice", origin: "built-in", meta: "Built in · 2 processes", keywords: ["Tax invoice", "فاتورة ضريبية", "VAT No"],
    languages: ["en", "ar"], usedBy: ["AP invoices", "Supplier invoices KSA"], fields: [
      f("invoice_number", "Invoice number", ["Invoice No", "رقم الفاتورة"], "Text", true, "INV-\\d{4}-\\d+", ["Inv #"]),
      f("invoice_date", "Invoice date", ["Date", "تاريخ الفاتورة"], "Date", true),
      f("supplier_name", "Supplier", ["Vendor", "From"], "Text", true, "", ["Billed by"]),
      f("supplier_vat", "Supplier VAT no.", ["VAT No", "VAT Number", "الرقم الضريبي"], "Text", true, "3\\d{13}3"),
      f("subtotal", "Subtotal", ["Net amount"], "Amount", true),
      f("vat_amount", "VAT amount", ["VAT 15%", "VAT", "ضريبة القيمة المضافة"], "Amount", false),
      f("total", "Total", ["Grand Total", "الإجمالي"], "Amount", true, "", ["Amount due"]),
      f("line_items", "Line items", ["Description", "Qty", "Amount"], "Table", false)] },
  { id: "receipt", name: "Receipt", origin: "built-in", meta: "Built in · Expense claims", keywords: ["Receipt", "Total"], languages: ["en"], usedBy: ["Expense claims"],
    fields: [f("merchant_name", "Merchant", [], "Text", true), f("total", "Total", [], "Amount", true)] },
  { id: "delivery-note", name: "Delivery note", origin: "built-in", meta: "Built in · Deliveries", keywords: ["Delivery note", "GRN"], languages: ["en"], usedBy: ["Deliveries"],
    fields: [f("note_number", "Delivery note number", [], "Text", true)] },
  { id: "credit-note", name: "Credit note", origin: "built-in", meta: "Built in · not used", keywords: ["Credit note"], languages: ["en"], usedBy: [],
    fields: [f("credit_note_number", "Credit note number", [], "Text", true)] },
  { id: "customs", name: "Customs declaration", origin: "made-here", meta: "Made here · not used", keywords: ["Declaration", "HS code"], languages: ["en", "ar"], usedBy: [],
    fields: [f("declaration_number", "Declaration number", [], "Text", true), f("hs_code", "HS code", [], "Text", false)] },
  { id: "bank-statement", name: "Bank statement", origin: "made-here", meta: "Made here · not used", keywords: ["Statement", "IBAN"], languages: ["en"], usedBy: [],
    fields: [f("iban", "IBAN", [], "Text", true)] },
];

const SUPPLIER_ROWS: RefRow[] = [
  ["SUP-0012", "ACME Trading Co. LLC", "300123456700003", "Saudi Arabia", "Active"], ["SUP-0044", "Red Sea Print House", "318609139099603", "Saudi Arabia", "Active"],
  ["SUP-0057", "Gulf Office Supplies", "310482766100003", "Saudi Arabia", "Active"], ["SUP-0081", "Falcon IT Services", "302117745900003", "Saudi Arabia", "Active"],
  ["SUP-0093", "Delta Industrial Parts", "311290874400003", "Saudi Arabia", "Active"], ["SUP-0102", "Oasis Catering Co.", "305561239800003", "Saudi Arabia", "Active"],
  ["SUP-0120", "Emirates Facility Mgmt", "100245678900003", "UAE", "Active"], ["SUP-0133", "Summit Facilities LLC", "309912345600003", "Saudi Arabia", "Blocked"],
].map(([code, name, vat, country, status]) => ({ code, name, vat, country, status }));

const REF_TABLES: RefTable[] = [
  { id: "suppliers", name: "Suppliers", rows: 412, meta: "Used by 2 processes · 1 Nov", columns: ["code", "name", "vat", "country", "status"], updated: "1 Nov 2026",
    usedBy: ["AP invoices", "Supplier invoices KSA"] },
  { id: "purchase-orders", name: "Purchase orders", rows: 1208, meta: "Used by 1 process · today 06:00", columns: ["po_number", "supplier", "amount"], usedBy: ["AP invoices"] },
  { id: "cost-centres", name: "Cost centres", rows: 36, meta: "Not used by any check", columns: ["code", "name"] },
  { id: "customers", name: "Customers", rows: 95, meta: "Not used by any check", columns: ["code", "name", "country"] },
];

const OVERVIEW: Overview = {
  today: 128, straightThrough: 90, waiting: 9, overdue: 1, failed: 1, pagesThisMonth: 3412, cloudCost: 0,
  days: [[96, 9], [104, 12], [88, 8], [0, 0], [0, 0], [112, 11], [121, 14], [118, 10], [109, 9], [126, 13], [0, 0], [0, 0], [131, 12], [115, 13]]
    .map(([s, r], i) => ({ label: ["21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "1", "2", "3"][i], straight: s, reviewed: r })),
  alerts: [
    { level: "bad", title: "Review overdue", text: "NL-INV-7710.pdf in AP invoices passed its 24 h target", to: "#/review/nl_7710" },
    { level: "bad", title: "1 document failed", text: "contract_signed.pdf is password-protected", to: "#/process/smart-inbox" },
    { level: "warn", title: "Unrecognised pages", text: "3 pages in Smart inbox need a document type", to: "#/process/smart-inbox" },
    { level: "info", title: "Cloud opt-in is off", text: "Weak pages are retried locally only", to: "#/engines" }],
  readBy: [{ label: "Tesseract", share: 86 }, { label: "PaddleOCR retry (weak pages)", share: 9 }, { label: "PDF text layer, no OCR", share: 5 }, { label: "Cloud engines", share: 0 }],
};

const TEST: TestResult[] = [
  { field: "Invoice number", a: "INV-2026-0931", aConf: "93%", b: "INV-2026-0931", bConf: "98%", check: "Pattern ✓", differs: false },
  { field: "Invoice date", a: "—", aConf: "not found", b: "2026-02-13", bConf: "97%", check: "Required: missing in A", differs: true },
  { field: "Supplier VAT no.", a: "302117745900003", aConf: "88%", b: "302117745900003", bConf: "99%", check: "Pattern ✓", differs: false },
  { field: "Subtotal", a: "7,884.71", aConf: "91%", b: "7,884.71", bConf: "99%", check: "—", differs: false },
  { field: "VAT amount", a: "1,182.71", aConf: "90%", b: "1,182.71", bConf: "99%", check: "VAT is 15% ✓", differs: false },
  { field: "Total", a: "9,087.42", aConf: "83%", b: "9,067.42", bConf: "98%", check: "Totals add up: fails in A", differs: true },
  { field: "Supplier", a: "Falcon IT Services", aConf: "92%", b: "Falcon IT Services", bConf: "99%", check: "Known supplier ✓", differs: false },
];
const TEST_RUN = (engine: string, seconds: number, confidence: number, review: boolean): TestRun["runs"][number] =>
  ({ engine, seconds, confidence, review, readBy: engine });

const copy = <T,>(x: T): T => JSON.parse(JSON.stringify(x));

/** Sample data in memory. Writes change it, so the screens behave as they will against the service. */
export class SampleApi implements DocFlowApi {
  private processesData = copy(PROCESSES);
  private documentsData = copy(DOCUMENTS);
  private routing: Routing = { cloudOptIn: false, retryBelow: 0.85, localRetry: "paddleocr", cloudEngine: "" };
  readonly reviews: { documentId: string; fields: Record<string, string>; decision: string }[] = [];

  async overview() { return copy(OVERVIEW); }
  async processes() { return copy(this.processesData); }
  async documents(processId: string) { return copy(this.documentsData.filter((d) => d.processId === processId)); }
  async addDocuments(files: File[], processId: string | "auto") {
    const target = processId === "auto" ? "smart-inbox" : processId;
    files.forEach((f, i) => this.documentsData.unshift(doc({ id: "new_" + Date.now() + "_" + i, processId: target, name: f.name, status: "processing",
      time: "now", sourceLabel: "Upload · you", engine: "Waiting to start" })));
    const p = this.processesData.find((x) => x.id === target);
    if (p) { p.today += files.length; p.processing += files.length; }
    return { processId: target };
  }
  async folders() { return ["Finance/Accounts payable", "Finance/Expenses", "Operations/Goods received", "Shared/Unsorted"]; }
  async createProcess(n: NewProcess) {
    const id = n.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "process";
    this.processesData.push({ id, name: n.name, folder: n.folder, docType: DOC_TYPES.find((t) => t.id === n.docType)?.name ?? n.docType, state: "draft", version: 1, today: 0, review: 0, processing: 0,
      failed: 0, done: 0, straightThrough: 0, lastDocument: "—", lastTime: "", deliversTo: [],
      sources: ["upload", ...(n.watchedFolder ? ["folder" as const] : []), ...(n.email ? ["email" as const] : []), ...(n.fromBot ? ["bot" as const] : [])] });
    return { id };
  }
  async reviewTask(documentId: string) {
    const d = this.documentsData.find((x) => x.id === documentId);
    return { ...copy(REVIEW), documentId, name: d?.name ?? REVIEW.name };
  }
  async completeReview(documentId: string, fields: Record<string, string>, decision: "approve" | "reject") {
    this.reviews.push({ documentId, fields, decision });
    const d = this.documentsData.find((x) => x.id === documentId);
    if (d) { d.status = decision === "approve" ? "done" : "failed"; d.note = decision === "approve" ? "Approved by you" : "Rejected by you"; }
    return { next: this.documentsData.find((x) => x.status === "review" && x.processId === d?.processId && x.id !== documentId)?.id };
  }
  async changeDocType(documentId: string) { return { documentId, needsReview: true }; }
  exportUrl(processId: string) {
    const rows = this.documentsData.filter((d) => d.processId === processId && d.status === "done");
    return "data:text/csv;charset=utf-8," + encodeURIComponent(["Document,Supplier,Invoice,Total", ...rows.map((d) => [d.name, d.supplier, d.invoice, d.total].join(","))].join("\r\n"));
  }
  async testEngine(id: string) { return { ok: true, message: `${id}: the engine read the test page` }; }
  async engineSettings(id: string): Promise<EngineField[]> {
    return id === "azure-di" ? [{ key: "AZURE_DI_ENDPOINT", label: "Endpoint", type: "text", value: "" }, { key: "AZURE_DI_KEY", label: "Key", type: "secret", value: "" },
      { key: "AZURE_DI_REGION", label: "Region", type: "text", value: "westeurope", help: "Used by the residency policy, e.g. uaenorth." }] : [];
  }
  async saveEngineSettings() { /* sample: nothing to save */ }
  async engines() {
    const options = copy(ENGINES);
    if (this.routing.cloudOptIn) options.forEach((o) => o.engines.forEach((e) => { if (e.status === "optin") e.status = "setup"; }));
    return { routing: { ...this.routing }, options };
  }
  async saveRouting(r: Routing) { this.routing = { ...r }; }
  private designs: Record<string, ProcessDesign> = {};
  async processDesign(id: string) {
    const p = this.processesData.find((x) => x.id === id);
    return copy(this.designs[id] ?? (p ? { ...DESIGN, id: p.id, name: p.name, folder: p.folder, docType: p.docType, state: p.state, version: p.version,
      docTypeIds: this.types.filter((t) => t.name === p.docType).map((t) => t.id) } : DESIGN));
  }
  async saveProcess(d: ProcessDesign) {
    const before = this.designs[d.id] ?? await this.processDesign(d.id);
    const changed = JSON.stringify({ ...before, stages: [] }) !== JSON.stringify({ ...d, stages: [] });
    // another document type: its name and fields come from the types now chosen
    const types = JSON.stringify(before.docTypeIds) === JSON.stringify(d.docTypeIds) ? [] : this.types.filter((t) => d.docTypeIds.includes(t.id));
    const docType = types.map((t) => t.name).join(", ") || d.docType;
    const fields = types.length ? types.flatMap((t) => t.fields).filter((x) => x.type !== "Table").map((x) => ({ name: x.key, label: x.label })) : d.fields;
    this.designs[d.id] = { ...copy(d), docType, fields, version: changed ? d.version + 1 : d.version, stages: stagesOf({ ...d, docType }, (id) => id) };
    const p = this.processesData.find((x) => x.id === d.id);
    if (p) p.docType = docType;
    return copy(this.designs[d.id]);
  }
  async inboxStatus(): Promise<InboxStatus | null> { return null; }
  async setLive(id: string, live: boolean) {
    const d = await this.processDesign(id);
    this.designs[id] = { ...d, state: live ? "live" : "draft" };
    const p = this.processesData.find((x) => x.id === id);
    if (p) p.state = live ? "live" : "draft";
  }
  private types = copy(DOC_TYPES);
  async docTypes() { return copy(this.types); }
  async saveDocType(t: DocType) {
    const id = t.id || t.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "type";
    const saved = { ...copy(t), id, meta: t.id ? t.meta : "Made here · not used" };
    this.types = this.types.some((x) => x.id === id) ? this.types.map((x) => x.id === id ? saved : x) : [...this.types, saved];
    return copy(saved);
  }
  async deleteDocType(id: string) { this.types = this.types.filter((t) => t.id !== id); }
  async suggestDocType(files: File[]): Promise<DocType> {
    return { id: "", name: "New document type", origin: "made-here", meta: `From ${files.length} sample${files.length > 1 ? "s" : ""}`, keywords: ["Invoice"], languages: ["en"], usedBy: [],
      fields: [f("invoice_number", "Invoice number", [], "Text", true), f("invoice_date", "Invoice date", [], "Date", true), f("total", "Total", [], "Amount", true)] };
  }
  private tables = copy(REF_TABLES);
  async refTables() { return copy(this.tables); }
  async refTableRows(id: string) { return id === "suppliers" ? copy(SUPPLIER_ROWS) : []; }
  async uploadRefTable(file: File, name: string) {
    const title = name.trim() || file.name.replace(/\.[^.]+$/, "");
    const id = title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "table";
    this.tables = [...this.tables.filter((t) => t.id !== id), { id, name: title, rows: 0, meta: "Not used by any check", columns: [], updated: "now" }];
    return { id };
  }
  async deleteRefTable(id: string) { this.tables = this.tables.filter((t) => t.id !== id); }
  async runTest(_file: File, _type: string, a: string, b: string | null): Promise<TestRun> {
    return { rows: copy(TEST).map((r) => (b ? r : { ...r, b: "", bConf: "", differs: false })),
      runs: [TEST_RUN(a, 0.6, 78, true), ...(b ? [TEST_RUN(b, 7.3, 98, false)] : [])] };
  }
}

/* ------------------------------------------------------------------ cost estimate (DocFlow's docauto/costs.py) */

export const CLOUD_OCR_PER_1000: Record<string, number> = { "azure-di": 1.5, "google-docai": 1.5, "aws-textract": 1.5 };
export const EXTRACT_PER_1000: Record<string, number> = {
  "builtin-rules": 0, "llm-local": 0, "azure-di": 10, "aws-textract": 10,
  "claude-opus-5-5": 32, "claude-sonnet-5-5": 16, "claude-haiku-5-5": 0.8,
};

export function estimate(pages: number, cloudShare: number, fieldShare: number, cloudOcr: string, extraction: string) {
  const cloudPages = Math.round(pages * cloudShare);
  const fieldPages = Math.round(pages * fieldShare);
  const ocr = (CLOUD_OCR_PER_1000[cloudOcr] ?? 0) * cloudPages / 1000;
  const fields = (EXTRACT_PER_1000[extraction] ?? 0) * fieldPages / 1000;
  return { localPages: pages - cloudPages, cloudPages, fieldPages, ocr, fields, total: Math.round((ocr + fields) * 100) / 100,
    ocrRate: CLOUD_OCR_PER_1000[cloudOcr] ?? 0, fieldRate: EXTRACT_PER_1000[extraction] ?? 0 };
}
