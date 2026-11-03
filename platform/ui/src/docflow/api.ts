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
  documentId: string; name: string; processName: string; docType: string; cameIn: string; dueIn: string; position: number; of: number;
  engineTrail: string; pageLines: { text: string; mark?: "flag" | "ok"; bold?: boolean }[]; fields: ReviewField[]; checks: ReviewCheck[];
}

export type EngineStatus = "ready" | "optin" | "setup";
export interface Engine { id: string; name: string; does: string; status: EngineStatus; runs: "server" | "cloud"; price: string; free: boolean; key: string }
export interface EngineOption { id: "onecore" | "oneconnect" | "onemind"; name: string; description: string; color: string; engines: Engine[] }
export interface Routing { cloudOptIn: boolean; retryBelow: number; localRetry: string; cloudEngine: string }

export interface Check { id: string; on: boolean; name: string; kind: string; test: string; ifFails: "review" | "reject" | "warn" }
export interface ProcessDesign {
  id: string; name: string; state: "live" | "draft"; version: number; folder: string; docType: string; checks: Check[];
  stages: { name: string; summary: string; done: boolean }[];
}

export interface DocTypeField { name: string; labels: string; type: "Text" | "Date" | "Amount" | "Table"; required: boolean; check: string; learned: string }
export interface DocType { id: string; name: string; origin: "built-in" | "made-here"; meta: string; keywords: string; languages: string; usedBy: string[]; fields: DocTypeField[] }

export interface RefRow { code: string; name: string; vat: string; country: string; active: boolean; matched: number }
export interface RefTable { id: string; name: string; rows: number; meta: string; file?: string; updated?: string; matchOn?: string; usedBy?: string[]; sample?: RefRow[] }

export interface DayCount { label: string; straight: number; reviewed: number }
export interface Overview {
  today: number; straightThrough: number; waiting: number; overdue: number; failed: number; pagesThisMonth: number; cloudCost: number;
  days: DayCount[]; alerts: { level: "bad" | "warn" | "info"; title: string; text: string; to: string }[];
  readBy: { label: string; share: number }[];
}

export interface TestResult { field: string; a: string; aConf: string; b: string; bConf: string; check: string; differs: boolean }

/** docType is a document type id (DocType.id). */
export interface NewProcess { name: string; docType: string; folder: string; watchedFolder?: string; pollSeconds?: number; mailbox?: string; fromBot: boolean }

export interface DocFlowApi {
  overview(): Promise<Overview>;
  processes(): Promise<ProcessSummary[]>;
  documents(processId: string): Promise<DocumentRow[]>;
  addDocuments(files: File[], processId: string | "auto", folder: string): Promise<{ processId: string }>;
  createProcess(p: NewProcess): Promise<{ id: string }>;
  reviewTask(documentId: string): Promise<ReviewTask>;
  completeReview(documentId: string, fields: Record<string, string>, decision: "approve" | "reject"): Promise<void>;
  engines(): Promise<{ routing: Routing; options: EngineOption[] }>;
  saveRouting(r: Routing): Promise<void>;
  processDesign(id: string): Promise<ProcessDesign>;
  docTypes(): Promise<DocType[]>;
  refTables(): Promise<RefTable[]>;
  runTest(sample: string, engineA: string, engineB: string | null): Promise<TestResult[]>;
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
  documentId: "scan_0457", name: "scan_0457.jpg", processName: "AP invoices", docType: "Supplier invoice", cameIn: "10:40 from \\\\scan01\\invoices",
  dueIn: "3 h", position: 1, of: 9, engineTrail: "read by Tesseract, re-read by PaddleOCR (78%)",
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

const DESIGN: ProcessDesign = {
  id: "supplier-invoices-ksa", name: "Supplier invoices KSA", state: "draft", version: 1, folder: "Finance / Accounts payable", docType: "Supplier invoice",
  stages: [
    { name: "Intake", summary: "Upload, \\\\scan01\\invoices\\ksa every 30 s, invoices@acme-trading.com", done: true },
    { name: "Classify & split", summary: "Every file is a supplier invoice; batches are not split", done: true },
    { name: "Extract", summary: "Invoice no., date, supplier, VAT no., subtotal, VAT, total; local rules", done: true },
    { name: "Checks & lookups", summary: "5 of 6 checks on", done: false },
    { name: "Review", summary: "Fields under 85% go to the Task inbox; 24 h target", done: false },
    { name: "Deliver", summary: "Choose: Excel register, folder, webhook or start a bot", done: false }],
  checks: [
    { id: "totals", on: true, name: "Totals add up", kind: "Arithmetic", test: "Subtotal + VAT = total, within 0.05", ifFails: "review" },
    { id: "vat15", on: true, name: "VAT is 15%", kind: "Arithmetic", test: "VAT = 15% of subtotal, within 0.05", ifFails: "review" },
    { id: "ksa-vat", on: true, name: "Valid VAT number", kind: "KSA VAT (ZATCA)", test: "15 digits, starts and ends with 3", ifFails: "review" },
    { id: "duplicate", on: true, name: "Not a duplicate", kind: "Duplicate", test: "Same supplier VAT no. and invoice number seen before", ifFails: "reject" },
    { id: "supplier", on: true, name: "Known supplier", kind: "Reference data lookup", test: 'Supplier VAT no. is in "Suppliers"; adds supplier code', ifFails: "review" },
    { id: "large", on: false, name: "Large invoice", kind: "Amount threshold", test: "Total over 50,000 SAR needs a second person", ifFails: "review" }],
};

const DOC_TYPES: DocType[] = [
  { id: "supplier-invoice", name: "Supplier invoice", origin: "built-in", meta: "Built in · 2 processes", keywords: '"Tax invoice", "فاتورة ضريبية", "VAT No"',
    languages: "English, Arabic", usedBy: ["AP invoices", "Supplier invoices KSA"], fields: [
      { name: "Invoice number", labels: "Invoice No, Invoice Number, رقم الفاتورة", type: "Text", required: true, check: "INV-\\d{4}-\\d+", learned: '"Inv #" (3 reviews)' },
      { name: "Invoice date", labels: "Invoice Date, Date, تاريخ الفاتورة", type: "Date", required: true, check: "DD/MM/YYYY", learned: "—" },
      { name: "Supplier", labels: "Supplier, Vendor, From", type: "Text", required: true, check: "—", learned: '"Billed by" (1 review)' },
      { name: "Supplier VAT no.", labels: "VAT No, VAT Number, الرقم الضريبي", type: "Text", required: true, check: "3\\d{13}3", learned: "—" },
      { name: "Subtotal", labels: "Subtotal, Net amount", type: "Amount", required: true, check: "—", learned: "—" },
      { name: "VAT amount", labels: "VAT 15%, VAT, ضريبة القيمة المضافة", type: "Amount", required: false, check: "—", learned: "—" },
      { name: "Total", labels: "Total, Grand Total, الإجمالي", type: "Amount", required: true, check: "—", learned: '"Amount due" (5 reviews)' },
      { name: "Line items", labels: "Description, Qty, Amount", type: "Table", required: false, check: "3 columns", learned: "—" }] },
  { id: "receipt", name: "Receipt", origin: "built-in", meta: "Built in · Expense claims", keywords: '"Receipt", "Total"', languages: "English", usedBy: ["Expense claims"], fields: [] },
  { id: "delivery-note", name: "Delivery note", origin: "built-in", meta: "Built in · Deliveries", keywords: '"Delivery note", "GRN"', languages: "English", usedBy: ["Deliveries"], fields: [] },
  { id: "credit-note", name: "Credit note", origin: "built-in", meta: "Built in · not used", keywords: '"Credit note"', languages: "English", usedBy: [], fields: [] },
  { id: "customs", name: "Customs declaration", origin: "made-here", meta: "Made here · taught from 3 samples", keywords: '"Declaration", "HS code"', languages: "English, Arabic", usedBy: [], fields: [] },
  { id: "bank-statement", name: "Bank statement", origin: "made-here", meta: "Made here · draft", keywords: '"Statement", "IBAN"', languages: "English", usedBy: [], fields: [] },
];

const REF_TABLES: RefTable[] = [
  { id: "suppliers", name: "Suppliers", rows: 412, meta: "Used by 2 checks · 1 Nov", file: "suppliers_2026-11.xlsx", updated: "1 Nov 2026 by Omar Haddad",
    matchOn: "VAT number", usedBy: ["AP invoices", "Supplier invoices KSA"], sample: [
      { code: "SUP-0012", name: "ACME Trading Co. LLC", vat: "300123456700003", country: "Saudi Arabia", active: true, matched: 31 },
      { code: "SUP-0044", name: "Red Sea Print House", vat: "318609139099603", country: "Saudi Arabia", active: true, matched: 12 },
      { code: "SUP-0057", name: "Gulf Office Supplies", vat: "310482766100003", country: "Saudi Arabia", active: true, matched: 9 },
      { code: "SUP-0081", name: "Falcon IT Services", vat: "302117745900003", country: "Saudi Arabia", active: true, matched: 6 },
      { code: "SUP-0093", name: "Delta Industrial Parts", vat: "311290874400003", country: "Saudi Arabia", active: true, matched: 4 },
      { code: "SUP-0102", name: "Oasis Catering Co.", vat: "305561239800003", country: "Saudi Arabia", active: true, matched: 3 },
      { code: "SUP-0120", name: "Emirates Facility Mgmt", vat: "100245678900003", country: "UAE", active: true, matched: 0 },
      { code: "SUP-0133", name: "Summit Facilities LLC", vat: "309912345600003", country: "Saudi Arabia", active: false, matched: 0 }] },
  { id: "purchase-orders", name: "Purchase orders", rows: 1208, meta: "Used by 1 check · today 06:00" },
  { id: "cost-centres", name: "Cost centres", rows: 36, meta: "Adds cost centre to fields" },
  { id: "customers", name: "Customers", rows: 95, meta: "Not used by any check" },
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
  async createProcess(n: NewProcess) {
    const id = n.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "process";
    this.processesData.push({ id, name: n.name, folder: n.folder, docType: DOC_TYPES.find((t) => t.id === n.docType)?.name ?? n.docType, state: "draft", version: 1, today: 0, review: 0, processing: 0,
      failed: 0, done: 0, straightThrough: 0, lastDocument: "—", lastTime: "", deliversTo: [],
      sources: ["upload", ...(n.watchedFolder ? ["folder" as const] : []), ...(n.mailbox ? ["email" as const] : []), ...(n.fromBot ? ["bot" as const] : [])] });
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
  }
  async engines() {
    const options = copy(ENGINES);
    if (this.routing.cloudOptIn) options.forEach((o) => o.engines.forEach((e) => { if (e.status === "optin") e.status = "setup"; }));
    return { routing: { ...this.routing }, options };
  }
  async saveRouting(r: Routing) { this.routing = { ...r }; }
  async processDesign() { return copy(DESIGN); }
  async docTypes() { return copy(DOC_TYPES); }
  async refTables() { return copy(REF_TABLES); }
  async runTest(_sample: string, _a: string, b: string | null) {
    return copy(TEST).map((r) => (b ? r : { ...r, b: "", bConf: "", differs: false }));
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
