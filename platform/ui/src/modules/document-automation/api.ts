/**
 * Document Automation (DocFlow) data the screens use. `DocFlowApi` is the seam: today `SampleApi` answers from sample
 * data; the real client will call the DocFlow service through the Command Center (/api/docflow/…, tenant from the
 * signed-in user) with the same shapes, so the screens do not change when it is wired.
 */

import type { MailConditions, MailTry } from "../../api/cr";
import { SAMPLE_REVIEW } from "./sampleReview";

export type DocStatus = "processing" | "done" | "review" | "failed" | "split";
export type Source = "upload" | "folder" | "email" | "bot" | "split";

export interface ProcessSummary {
  id: string; name: string; folder: string; docType: string; state: "live" | "draft"; version: number;
  today: number; review: number; processing: number; failed: number; done: number; straightThrough: number;
  lastDocument: string; lastTime: string; sources: Source[]; deliversTo: string[];
}

/** The process that recognises each document and sends it on (DocFlow's inbox flow); uploads of mixed types go here. */
export const INBOX_ID = "smart-inbox";

/**
 * A process as the Overview table shows it: its set-up (document types, classification, engine, checks, review,
 * deliveries) and what is happening (this month, overdue, the last document's time as ISO, or null).
 */
export interface ProcessRow extends ProcessSummary {
  isInbox: boolean; docTypes: string[]; classifyMode: "single" | "auto"; extraction: string; checksOn: number; checks: number;
  fieldConfidence: number; slaHours: number; month: number; overdue: number; lastAt: string | null;
}

/**
 * A language document types can use. engines: the OCR engines on the server that read it; installed: at least one
 * does; offered: on the administrator's list, so people can choose it for a document type.
 */
export interface Language { code: string; name: string; engines: string[]; installed: boolean; offered: boolean }

export interface DocumentRow {
  id: string; processId: string; name: string; pages: string; supplier: string; invoice: string; total: string;
  time: string; source: Source; sourceLabel: string; status: DocStatus; note: string; engine: string; confidence: number | null;
  /** Every value read, as text, by field name (the process page shows the columns a person chooses). */
  values?: Record<string, string>;
}

/** A box on a page: 0–1 of the page's width and height, from its top-left corner. */
export interface Box { x: number; y: number; w: number; h: number }
/**
 * Where a value is printed (DocFlow's grounding). grounded: false = the value was not found on the page; null = not
 * checked (empty). groundedBy: "ocr" (the page's words), "qr" (the e-invoice QR code), "einvoice-xml" (no place on a page).
 */
export interface Place { page: number | null; bbox: Box | null; grounded: boolean | null; groundedBy?: "ocr" | "qr" | "einvoice-xml" | null }
export interface RowPlace extends Place { cells: Record<string, Place> }
/**
 * Why a field needs the reviewer (DocFlow's review reasons and the process's rules): reason in plain words; kind: DocFlow's
 * reason (low_confidence, required_missing, validation, not_grounded, rule...); suggestion: a value that makes the checks pass.
 */
export interface ReviewFlag { reason: string; kind?: string; suggestion?: string }
export interface ReviewField { id: string; label: string; value: string; confidence: number; flagged?: ReviewFlag; place?: Place }
export type TableRow = Record<string, string>;
/** A table field (line items): its columns, the rows read, and where the table and each row are on the pages. */
export interface ReviewTable {
  id: string; label: string; columns: { name: string; label: string }[]; rows: TableRow[]; confidence: number | null;
  flagged?: ReviewFlag; place?: Place; rowPlaces?: RowPlace[];
}
export interface ReviewCheck { text: string; passed: boolean; detail: string }
/** One value of the e-invoice QR code compared with what was read from the page. */
export interface EInvoiceCheck { field: string; label: string; qr: string; read: string; result: "match" | "mismatch" | "differs" | "filled" }
/** E-invoice data DocFlow read: the invoice's own XML, or a QR code on the page. standard: which (UBL, CII, a QR code...), in words. */
export interface EInvoice { source: "einvoice-xml" | string; standard?: string; values: Record<string, string>; checks: EInvoiceCheck[] }
/** An e-invoice standard DocFlow reads: "xml" replaces reading the page, "qr" checks what was read. */
export interface EInvoiceStandard { id: string; name: string; kind: "xml" | "qr" }
/** A page of the document: its number (as places name it), its image and its size in pixels when known. */
export interface ReviewPage { number: number; image: string; width?: number; height?: number }
export interface ReviewTask {
  /** docTypeId: the document type's id (DocType.id), when the document has one. */
  documentId: string; processId?: string; docTypeId?: string; status: DocStatus; reviewedBy?: string; name: string; processName: string; docType: string; cameIn: string; dueIn: string; targetHours: number; position: number; of: number;
  /** The review tasks before and after this one in the waiting list (Previous task, Next task); absent at either end. */
  previousId?: string; nextId?: string;
  engineTrail: string; pages: ReviewPage[];
  /** The page's text, shown only when there is no page image (an e-invoice XML on its own). */
  pageLines: { text: string; mark?: "flag" | "ok"; bold?: boolean }[];
  fields: ReviewField[]; tables: ReviewTable[]; checks: ReviewCheck[]; einvoice: EInvoice | null;
  /** Why the whole document needs review, not one field (document confidence, a failed check, type not recognised). */
  documentReasons: string[];
}
/** What a reviewer sends back: text fields as typed, and table fields (only those changed) as rows. */
export type ReviewValues = Record<string, string | TableRow[]>;

export type EngineStatus = "ready" | "optin" | "setup";
export interface Engine { id: string; name: string; does: string; status: EngineStatus; runs: "server" | "cloud"; price: string; free: boolean; key: string }
export interface EngineOption { id: "onecore" | "oneconnect" | "onemind" | "other"; name: string; description: string; color: string; engines: Engine[] }
/** One setting of an engine (its address, key, model…), as DocFlow's settings describe it. Secrets come back masked. */
export interface EngineField { key: string; label: string; type: string; value: string; help?: string; options?: string[]; suggestions?: string[]; placeholder?: string }
/** ocr / extraction: the engines that read every page and the fields by default (DocFlow's defaults; a process may choose its own). */
export interface Routing { cloudOptIn: boolean; retryBelow: number; localRetry: string; cloudEngine: string; ocr?: string; extraction?: string }
/** Claude's models: the one that reads the fields, and the one that re-reads a document that would go to review (null: off). */
export interface ClaudeModels { model: string; escalationModel: string | null }
/** "claude-haiku-5-5" → "Claude Haiku 5.5". */
export function modelName(id: string): string {
  const m = /^claude-([a-z]+)-(\d+)-(\d+)$/.exec(id);
  return m ? `Claude ${m[1][0].toUpperCase()}${m[1].slice(1)} ${m[2]}.${m[3]}` : id;
}

/** DocFlow's kinds of check (rules); `params` are DocFlow's own settings for that kind. */
export type RuleType = "duplicate" | "lookup" | "ksa_vat" | "tax" | "threshold" | "required" | "expression" | "pattern" | "date_range";
export interface Check { id: string; on: boolean; name: string; kind: string; test: string; ifFails: "review" | "reject" | "warn" | "route" | "notify"; type: RuleType; params: Record<string, unknown> }
export type DeliveryType = "excel" | "folder" | "webhook" | "start_bot" | "email" | "cloud_storage";
/** Where results go. target: the folder or Excel path, the webhook address, or the bot to start. */
/** method and headers: a web address delivery's HTTP method (POST by default) and its own headers (an API key header...). */
export interface Delivery { id: string; type: DeliveryType; name: string; enabled: boolean; target: string; on: string[]; pool?: string;
  method?: "POST" | "PUT" | "PATCH"; headers?: Record<string, string>;
  /** Folder deliveries: also save a searchable PDF (the text read is under the page images, so it can be searched and copied). */
  searchablePdf?: boolean;
  /** Folder deliveries: also save the document's text and tables as Markdown (for search and Knowledge bases). */
  saveText?: boolean;
  /** E-mail and cloud storage settings beyond the target: subject, provider, region, endpoint, prefix, accessKey,
   * secretRef (a vault reference), files (original, json, markdown). */
  extra?: Record<string, unknown> }
/**
 * A process's email inbox, read by the Command Center: IMAP (kind "email") or Microsoft 365 (kind "outlook"), as email
 * triggers. Secrets are vault references (folder/credential/attribute). Keys are DocFlow's, as stored.
 */
export interface EmailInbox {
  enabled: boolean; kind: "email" | "outlook"; host?: string; port?: number; ssl?: boolean; username?: string; password_ref?: string;
  mailbox?: string; tenant_id?: string; client_id?: string; client_secret_ref?: string; mail_folder?: string;
  from_contains?: string; subject_contains?: string; interval_seconds?: number;
  /** DA-1: read with "Sign in with Microsoft": the sign-in's id, and the account Microsoft confirmed (set by the server). */
  ms_signin?: string; ms_account?: string;
}
/** DA-1: a started "Sign in with Microsoft": the code the person types at Microsoft, where, and how often to ask. */
export interface MsSignInStart { id: string; userCode: string; verificationUri: string; expiresIn: number; interval: number }
/** Where a "Sign in with Microsoft" is. */
export type MsSignInState = { status: "pending" } | { status: "signed_in"; account: string } | { status: "failed"; message: string };
export interface ProcessSettings {
  inputFolder: string; pollSeconds: number; classifyMode: "single" | "auto"; split: boolean; extraction: string;
  /** A document that fits no type: a person chooses (review), kept as an Other document, or rejected. */
  whenNoType?: "review" | "other" | "reject";
  /** Every-file-is-this-type processes: check each document looks like it (its recognition words). */
  checkType?: boolean;
  /** Most to spend a month on cloud and AI engines (price currency); reached, the process reads on this server. null: no limit. */
  monthlyBudget?: number | null;
  /** An engine per document type (type id -> engine id); the others use extraction. */
  extractionByType?: Record<string, string>;
  fieldConfidence: number; slaHours: number; email: EmailInbox | null;
  /** Read e-invoice data (XML, QR codes) before OCR; on by default. */
  einvoiceFirst: boolean;
  /** The e-invoice standards read (EInvoiceStandard ids); empty: every one DocFlow knows. */
  einvoiceStandards?: string[];
  /** Stricter (or looser) review thresholds for single fields: {field name: 0–1}, over fieldConfidence. */
  fieldThresholds: Record<string, number>;
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
  duplicate: "Duplicate", lookup: "Reference data lookup", tax: "Tax check", ksa_vat: "Tax check (Saudi Arabia)", threshold: "Amount threshold",
  required: "Required fields", expression: "Arithmetic", pattern: "Format of a field", date_range: "Date check",
};
/** Tax check presets as DocFlow has them (GET /df/tax-presets); used when DocFlow does not list them. */
export const TAX_PRESETS: { id: string; name: string }[] = [
  { id: "custom", name: "Own rates and format" }, { id: "eu", name: "EU VAT (any member state, standard and reduced rates)" },
  { id: "uk", name: "United Kingdom VAT" }, { id: "germany", name: "Germany VAT" }, { id: "france", name: "France VAT" },
  { id: "india-gst", name: "India GST" }, { id: "ksa", name: "Saudi Arabia VAT" }, { id: "uae", name: "UAE VAT" }, { id: "bahrain", name: "Bahrain VAT" },
  { id: "oman", name: "Oman VAT" }, { id: "australia", name: "Australia GST" }, { id: "new-zealand", name: "New Zealand GST" },
  { id: "singapore", name: "Singapore GST" }, { id: "canada", name: "Canada GST/HST" }, { id: "japan", name: "Japan consumption tax" },
];

/** A number as people type it, in any common style: 1,000 · 1.000,50 · 1 000,5 · 1'000.50 → 1000 / 1000.5. NaN when it is none. */
export function parseAmount(text: unknown): number {
  let s = String(text ?? "").trim().replace(/[\s'’\u00a0]/g, "");
  if (!s) return NaN;
  const last = Math.max(s.lastIndexOf(","), s.lastIndexOf("."));
  if (last >= 0) {
    const seps = s.match(/[.,]/g) ?? [];
    const tail = s.length - last - 1;
    // one kind of separator before exactly 3 digits (1,000 or 1.000.000) is grouping; otherwise the last one is the decimal mark
    const grouping = new Set(seps).size === 1 && tail === 3 && (seps.length > 1 || s[last] === ",");
    s = grouping ? s.replace(/[.,]/g, "") : s.slice(0, last).replace(/[.,]/g, "") + "." + s.slice(last + 1);
  }
  return /^[-+]?\d*\.?\d+$/.test(s) ? Number(s) : NaN;
}

/** What a check tests, in words, from its kind and settings. */
export function ruleTest(type: RuleType, p: Record<string, unknown>): string {
  const t = (v: unknown) => (v === undefined || v === null || v === "" ? "—" : String(v));
  const list = (v: unknown) => (Array.isArray(v) && v.length ? (v as string[]).join(", ") : "—");
  switch (type) {
    case "expression": return t(p.expression);
    case "lookup": return `${t(p.field)} is in "${t(p.table)}" (${t(p.column)})`;
    case "threshold": return p.op === "between" || p.op === "outside" ? `${t(p.field)} ${p.op} ${t(p.value)} and ${t(p.value2)}` : `${t(p.field)} ${t(p.op ?? ">")} ${t(p.value)}`;
    case "duplicate": return `Same ${list(p.fields)} seen before`;
    case "ksa_vat": return "Saudi VAT number format; VAT is 15%; totals add up";
    case "tax": return `${TAX_PRESETS.find((x) => x.id === (p.preset ?? "custom"))?.name ?? t(p.preset)}${p.rates ? "; rates " + t(p.rates) : ""}; tax id format; totals add up`;
    case "pattern": return `${t(p.field)} matches ${t(p.pattern)}`;
    case "date_range": return `${t(p.field)}${p.notFuture ? " not in the future" : ""}${p.notOlderThanDays ? `, at most ${t(p.notOlderThanDays)} days old` : ""}`;
    case "required": return list(p.fields);
  }
}

/**
 * The stage summaries of a process, from its settings (the builder and both clients share them), in the order a
 * developer thinks: where documents come from, what they are, how they are read, checked, reviewed and delivered.
 */
const thresholds = (s: ProcessSettings) => {
  const n = Object.keys(s.fieldThresholds ?? {}).length;
  return n ? `; ${n} field${n > 1 ? "s" : ""} with ${n > 1 ? "their" : "its"} own threshold` : "";
};

export function stagesOf(d: Pick<ProcessDesign, "settings" | "deliveries" | "checks" | "docType">, engineName: (id: string) => string) {
  const s = d.settings;
  const on = d.checks.filter((c) => c.on).length;
  const sources = ["upload", s.inputFolder ? "watched folder" : "", s.email?.enabled ? "email inbox" : ""].filter(Boolean).join(", ");
  return [
    { name: "Document ingestion", summary: `Sources: ${sources}${s.inputFolder ? `; ${s.inputFolder} every ${s.pollSeconds} s` : ""}`, done: true },
    { name: "Document classification", summary: s.classifyMode === "auto" ? `Recognised automatically${s.split ? "; batches are split" : ""}` : `Type: ${d.docType || "not chosen"}${s.split ? "; batches are split" : ""}`, done: !!d.docType },
    // a process that recognises every type (One Inbox: docType "Any") reads each document's own type's fields
    { name: "Data extraction", summary: `Fields of ${!d.docType || d.docType === "Any" ? "each document type" : d.docType}; ${s.extraction === "auto" ? "default engine" : engineName(s.extraction)}`, done: true },
    { name: "Validation", summary: d.checks.length ? `${on} of ${d.checks.length} checks on` : "No checks yet", done: d.checks.length > 0 },
    { name: "Human review", summary: `Under ${Math.round(s.fieldConfidence * 100)}% confidence; to the Task inbox, ${s.slaHours} h target${thresholds(s)}`, done: true },
    { name: "Export & integration", summary: d.deliveries.length ? d.deliveries.map((x) => x.name || x.type).join(", ") : "Not set: Excel, folder, webhook or a bot", done: d.deliveries.length > 0 },
  ];
}

export type FieldType = "Text" | "Date" | "Date and time" | "Amount" | "Number" | "Percentage" | "Yes/No" | "Ticked" | "Signed"
  | "Choice" | "Several choices" | "Email" | "Phone" | "Address" | "Table" | "Group";
/** How a field is read: copied as printed, worked out from the whole document (a summary, "is it overdue?"), or a choice picked. */
export type FieldMethod = "copy" | "work-out" | "pick";
/** What a failed format check does: send to review (error), only note it (warn), or nothing (off). */
export type CheckStrictness = "error" | "warn" | "off";
/**
 * A field a document type reads. key: its name in results and checks; aliases: other labels it has on pages; learned:
 * labels picked up from reviews. description: what the field is, in plain words (AI reading follows it); choices and
 * choiceNotes: a Choice field's values and what each means; allowOther: a value outside the choices is kept, not an
 * error; writeAs: how the value is written in results (a date pattern, "2 decimals", "UPPER"...). dfType: DocFlow's own
 * type name, kept so a type the screens do not know is never changed by saving.
 */
export interface DocTypeField {
  key: string; label: string; aliases: string[]; type: FieldType; required: boolean; regex: string; learned: string[];
  description?: string; method?: FieldMethod; choices?: string[]; choiceNotes?: Record<string, string>; allowOther?: boolean;
  strictness?: CheckStrictness; writeAs?: string; dfType?: string;
}
/** id is "" for a type not saved yet (a new one, or one proposed from samples). description: what the type is, for people and AI recognising it. */
export interface DocType {
  id: string; name: string; origin: "built-in" | "made-here"; meta: string; keywords: string[]; languages: string[]; usedBy: string[]; fields: DocTypeField[];
  description?: string;
  /** How numeric dates of this type are written (DMY, MDY, YMD) and its decimal mark ("." or ","); "" = the server's setting. */
  dateOrder?: string; decimalMark?: string;
  /** Teaching examples: approved documents whose right values AI reading sees (document ids). */
  examples?: string[];
}

/** A reference table: any columns, as uploaded. Its rows are loaded on demand (refTableRows). */
export interface RefTable { id: string; name: string; rows: number; meta: string; columns: string[]; updated?: string; usedBy?: string[] }
export type RefRow = Record<string, string | number | boolean | null>;

export interface DayCount { label: string; straight: number; reviewed: number }
export interface Overview {
  today: number; straightThrough: number; waiting: number; overdue: number; failed: number; pagesThisMonth: number; cloudCost: number;
  /** Whether pages may go to cloud engines (the dashboard shows the cloud cost only then). */
  cloudOptIn: boolean;
  days: DayCount[]; alerts: { level: "bad" | "warn" | "info"; title: string; text: string; to: string }[];
  readBy: { label: string; share: number }[];
}

/** How well a process reads, from its reviewed documents (DocFlow GET /df/flows/{id}/accuracy). Shares are 0-1. */
export interface Accuracy {
  documents: number; finished: number; reviewed: number; measured: number; straightThrough: number | null; reviewLevel: number;
  fields: { name: string; label: string; seen: number; corrected: number; correctedShare: number }[];
  levels: { level: number; straightThrough: number | null; wrongPassed: number | null; fields: number }[];
}
/** Improve with AI: clearer field instructions proposed from what a test run read wrong (nothing saved yet). */
export interface Improvement {
  runId: string; docType: string; engine?: string;
  proposals: { name: string; label: string; current: string; proposed: string; wrong: number; examples: { right: unknown; read: unknown }[] }[];
}
/** key: the field's name (field is its label). */
export interface TestResult { field: string; key?: string; a: string; aConf: string; b: string; bConf: string; check: string; differs: boolean }
/** A Test lab run: the field rows, and per engine its time, page confidence, whether it would go to review (or why it could not run). */
export interface TestRun { rows: TestResult[]; runs: { engine: string; seconds: number; confidence: number | null; review: boolean; readBy: string; error?: string }[] }

/* ------------------------------------------------------------------ test sets (DocFlow's docauto/testsets.py, same shapes) */

/** One document of a test set with its right values. source: "document" (a reviewed one), "upload" or "seed". */
export interface TestSetItem {
  id: string; setId: string; fileName: string; mimeType: string; expected: Record<string, unknown>; tags: string[];
  source: string; sourceDocumentId: string | null; createdAt: string;
}
/** An OCR engine and an extraction engine, tested together ("auto": as routed live). */
export interface Combo { ocr: string; extraction: string }
/** One combination's result over the set; rates are 0–1. skipped: why it did not run. */
export interface ComboResult extends Combo {
  label: string; skipped: string | null; documents?: number; pages?: number; errors?: number;
  fieldAccuracy?: number | null; byField?: Record<string, number>; straightThrough?: number | null; reviewRate?: number | null;
  wronglyAutoApproved?: number; wronglyAutoApprovedRate?: number | null; charAccuracy?: number | null; credits?: number;
  costUsd?: number; costPer1000PagesUsd?: number | null; costKnown?: boolean; secondsPerPage?: number | null; p95Seconds?: number | null;
}
/** One document read by one combination: each field's right value (want), what was read (got) and whether it matches. */
export interface SetRunRow extends Combo {
  itemId: string; fileName: string; label: string; tags: string[]; seconds: number; pages: number; error?: string | null;
  charAccuracy?: number | null; ocrConfidence?: number | null; fields: Record<string, { want: unknown; got: unknown; ok: boolean }>;
  reviewRequired?: boolean; reviewReasons?: string[]; straightThrough: boolean; wronglyAutoApproved: boolean;
}
/** A run of a test set: running until every combination is done (rows only on a single run). */
export interface SetRun {
  id: string; setId: string; status: "running" | "completed" | "failed" | string; combos: Combo[]; results: ComboResult[];
  error: string | null; createdAt: string; finishedAt: string | null; rows?: SetRunRow[];
}
/** docType: the document type's id. items only on a single set. */
export interface TestSet {
  id: string; name: string; docType: string; description: string; itemCount: number; createdAt: string; updatedAt: string;
  lastRun: SetRun | null; items?: TestSetItem[];
}

/**
 * DA-1 item 5 (DocFlow 3.2.0): folders on DocFlow's server that processes may watch and save results in. An
 * administrator lists them; people choose inside them instead of typing a network path.
 */
export type FolderAccess = "ok" | "read" | "missing" | "denied";
export interface FolderUse { id: string; name: string }
export interface FolderRoot { name: string; path: string; access: FolderAccess; usedBy: FolderUse[] }
/** limited false: no list (or a DocFlow before 3.2.0), so any typed folder works. */
export interface FolderRoots { limited: boolean; roots: FolderRoot[]; outside: { id: string; name: string; use: "watched" | "results"; path: string }[] }
export interface FolderEntry { name: string; path: string; waiting: number; watchedBy: FolderUse[] }
export interface FolderListing {
  path: string; name: string; root: { name: string; path: string }; parent: string | null;
  access: FolderAccess; waiting: number; watchedBy: FolderUse[]; folders: FolderEntry[]; truncated: boolean;
}
export const NO_FOLDER_LIST: FolderRoots = { limited: false, roots: [], outside: [] };

/** Mail accounts (MA-4): mailboxes set up once (Manage > Connections); a process takes emails from them by rules. */
export interface MailAccountBrief { id: string; name: string; address: string; provider_name: string; status: string; can_use: boolean; can_write?: boolean }
export interface ProcessMailRule { id: string; account_id: string; account_name: string; account_address: string; account_status: string; name: string; says: string;
  conditions: MailConditions; matched: number; enabled: boolean; after_read?: string }
/** A folder's own name: the last part of its path. */
export const lastPart = (p: string) => p.trim().replace(/[\\/]+$/, "").split(/[\\/]/).pop() || p.trim();
/** The list as DocFlow reads DOCAUTO_ALLOWED_FOLDERS: folders separated by ';', each optionally "Name=folder". */
export function parseFolderList(list: string): { name: string; path: string }[] {
  return list.split(";").map((x) => x.trim()).filter(Boolean).map((part) => {
    const at = part.indexOf("=");
    const name = at >= 0 ? part.slice(0, at).trim() : "";
    if (at < 0 || /[\\/:]/.test(name)) return { name: lastPart(part), path: part };
    const path = part.slice(at + 1).trim();
    return { name: name || lastPart(path), path };
  });
}
export function formatFolderList(rows: { name: string; path: string }[]): string {
  return rows.map((r) => (r.name.trim() && r.name.trim() !== lastPart(r.path) ? `${r.name.trim()}=${r.path.trim()}` : r.path.trim())).join("; ");
}
/** Is path this folder or inside it? For showing only: case and \ or / do not matter here; DocFlow decides on real paths. */
export function inFolder(path: string, folder: string): boolean {
  const n = (p: string) => p.trim().replace(/[\\/]+/g, "/").replace(/\/$/, "").toLowerCase();
  const p = n(path), f = n(folder);
  return !!f && (p === f || p.startsWith(f + "/"));
}

/** docType is a document type id (DocType.id). */
/** email: the person will set up the inbox next (in the builder's step 2, Document ingestion). */
/** docType ANY_TYPE: the process recognises each document's type itself (every type DocFlow has). */
export const ANY_TYPE = "*any";
export interface NewProcess { name: string; docType: string; folder: string; watchedFolder?: string; pollSeconds?: number; email?: boolean; fromBot: boolean }

export interface DocFlowApi {
  /**
   * The tenant the next requests are for, when one person may see several (an operator, or the sample data). The real
   * client has none: the Command Center takes the tenant from the address the person signed in at.
   */
  setTenant?(tenant: string): void;
  /** days: how many days the per-day chart covers (14 by default). */
  overview(days?: number): Promise<Overview>;
  processes(): Promise<ProcessSummary[]>;
  /** Every process with its set-up and activity, in one go (the Overview table). */
  processTable(): Promise<ProcessRow[]>;
  /** This month's estimated cloud and AI spend of a process, its budget and whether it reads on this server only now. */
  processSpend(processId: string): Promise<{ spend: number; budget: number | null; currency: string; localOnly: boolean }>;
  /** How well a process reads: corrections per field, straight-through share, what each review level would do. */
  accuracy(processId: string): Promise<Accuracy>;
  /** The newest documents of a process, at most limit (200 by default). */
  documents(processId: string, limit?: number): Promise<DocumentRow[]>;
  addDocuments(files: File[], processId: string | "auto", folder: string): Promise<{ processId: string }>;
  createProcess(p: NewProcess): Promise<{ id: string }>;
  /** The platform folders a new process can go in (where the person may edit). */
  folders(): Promise<string[]>;
  /** DA-1: the vault values the person may use (folder/credential/attribute), for pickers instead of typing references. */
  vaultRefs(): Promise<{ ref: string; label: string }[]>;
  /** DA-1: whether "Sign in with Microsoft" is offered for email inboxes (a Microsoft app is set up). */
  msSignInReady(): Promise<boolean>;
  /** Starts "Sign in with Microsoft": the person types the code at Microsoft; then ask msSignInPoll until it is done. */
  msSignInStart(): Promise<MsSignInStart>;
  msSignInPoll(id: string): Promise<MsSignInState>;
  /** DA-1: the folders processes may use; NO_FOLDER_LIST when none are listed or DocFlow is older than 3.2.0. */
  folderRoots(): Promise<FolderRoots>;
  /** The folders inside one of them (never files). */
  folderList(path: string): Promise<FolderListing>;
  /** A new folder inside one of them; returns it. */
  folderCreate(path: string, name: string): Promise<FolderListing>;
  /** What a list being edited would give, saving nothing (administrators). */
  folderPreview(list: string): Promise<FolderRoots>;
  /** The saved list as one line ("Name=folder; folder"); null when DocFlow is older than 3.2.0 (administrators). */
  allowedFolders(): Promise<string | null>;
  saveAllowedFolders(list: string): Promise<void>;
  /** MA-4: the mail accounts the person may use, the rules sending emails to a process, saving one, and trying conditions. */
  mailAccounts(): Promise<MailAccountBrief[]>;
  processMailRules(processId: string): Promise<ProcessMailRule[]>;
  saveProcessMailRule(account: string, id: string | null, rule: { name: string; conditions: MailConditions; target: string; after_read?: string }): Promise<void>;
  deleteMailRule(id: string): Promise<void>;
  tryMailRule(account: string, conditions: MailConditions, rule?: string): Promise<MailTry>;
  reviewTask(documentId: string): Promise<ReviewTask>;
  /** Saves the review; `next` is the next document waiting in the same process, if any. */
  /** note: the reviewer's reason (why it was rejected), kept with the document. */
  completeReview(documentId: string, fields: ReviewValues, decision: "approve" | "reject", note?: string): Promise<{ next?: string }>;
  /** Reads the document again as another type; it may then need review again. */
  changeDocType(documentId: string, docTypeId: string): Promise<{ documentId: string; needsReview: boolean }>;
  /** Where Export downloads a process's results (done and approved documents). */
  exportUrl(processId: string, format: "xlsx" | "csv"): string;
  /** claude: the models Claude uses, when DocFlow says (shown where Claude is set up). */
  engines(): Promise<{ routing: Routing; options: EngineOption[]; claude?: ClaudeModels; prices?: PriceList }>;
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
  /** replaceId: the table to replace (its name may change); without it a table with the same name is replaced. */
  uploadRefTable(file: File, name: string, replaceId?: string, headerRow?: number): Promise<{ id: string }>;
  deleteRefTable(id: string): Promise<void>;
  /** Reads the file as the document type with engine A (and B), side by side. Nothing is kept. */
  runTest(file: File, docTypeId: string, engineA: string, engineB: string | null): Promise<TestRun>;
  saveDocType(t: DocType): Promise<DocType>;
  deleteDocType(id: string): Promise<void>;
  /** Proposes a document type (not saved) from 1 to 3 sample files. */
  suggestDocType(files: File[]): Promise<DocType>;
  /** The languages document types can use: the offered ones first, then the others the engines know. */
  languages(): Promise<Language[]>;
  /** The e-invoice standards DocFlow reads. */
  einvoiceStandards(): Promise<EInvoiceStandard[]>;
  /** The languages offered for document types (administrators: Engines, Languages). */
  saveOfferedLanguages(codes: string[]): Promise<void>;

  /** Test sets, of one document type (id) or all. */
  testSets(docType?: string): Promise<TestSet[]>;
  /** A test set with its items. */
  testSet(id: string): Promise<TestSet>;
  /** Split a scanned batch again: the pages of each part and its type (null: recognise it). */
  resplit(documentId: string, parts: { pages: number[]; docType: string | null }[]): Promise<void>;
  /** Teaching examples of a document type: add an approved document, or remove one. */
  addTeachingExample(docTypeId: string, documentId: string): Promise<void>;
  removeTeachingExample(docTypeId: string, documentId: string): Promise<void>;
  /** Improve with AI: clearer instructions for the fields the test run (the last one by default) read wrong. */
  improveInstructions(setId: string, runId?: string): Promise<Improvement>;
  createTestSet(name: string, docType: string, description?: string): Promise<TestSet>;
  updateTestSet(id: string, name: string, description: string): Promise<TestSet>;
  deleteTestSet(id: string): Promise<void>;
  /** Our sample set: generated invoices (no real data), with Arabic/English ones; made or topped up. */
  seedTestSet(): Promise<TestSet>;
  /** Copies an approved document (its file and the values approved) into a set of the same document type. */
  addToTestSet(setId: string, documentId: string, tags?: string[]): Promise<TestSetItem>;
  /** Adds a file with its right values ({field: value}). */
  addTestItem(setId: string, file: File, expected: Record<string, unknown>, tags: string[]): Promise<TestSetItem>;
  updateTestItem(setId: string, itemId: string, expected: Record<string, unknown>, tags: string[]): Promise<TestSetItem>;
  deleteTestItem(setId: string, itemId: string): Promise<void>;
  /** Where the item's file opens. */
  testItemFileUrl(setId: string, itemId: string): string;
  /** Starts a run of engine combinations (at most 6); it runs in the background. */
  startSetRun(setId: string, combos: Combo[]): Promise<SetRun>;
  setRuns(setId: string, limit?: number): Promise<SetRun[]>;
  /** A run with its rows; poll it while it is running. */
  setRun(setId: string, runId: string): Promise<SetRun>;
}

/* ------------------------------------------------------------------ sample data (from the approved designs) */

const PROCESSES: ProcessSummary[] = [
  { id: "ap-invoices", name: "AP invoices", folder: "Finance / Accounts payable", docType: "Supplier invoice", state: "live", version: 4,
    today: 74, review: 5, processing: 2, failed: 0, done: 67, straightThrough: 91, lastDocument: "INV-2026-3944.pdf", lastTime: "10:42",
    sources: ["upload", "folder", "bot"], deliversTo: ["Excel register", 'bot "Post to ERP"'] },
  { id: "smart-inbox", name: "One Inbox", folder: "Shared / Unsorted", docType: "Any", state: "live", version: 2,
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

/** Sample activity the process summaries do not carry: this month, overdue reviews, the last document's time. */
const ACTIVITY: Record<string, { month: number; overdue: number; at: string }> = {
  "ap-invoices": { month: 1460, overdue: 1, at: "10:42" }, "smart-inbox": { month: 402, overdue: 0, at: "10:31" },
  "expense-claims": { month: 351, overdue: 0, at: "10:12" }, deliveries: { month: 288, overdue: 0, at: "10:31" },
};
const todayAt = (hhmm: string) => { const d = new Date(); const [h, m] = hhmm.split(":").map(Number); d.setHours(h, m, 0, 0); return d.toISOString(); };

const LANGUAGES: Omit<Language, "offered">[] = [
  ["en", "English", "tesseract,paddleocr"], ["ar", "Arabic", "tesseract,paddleocr"], ["ur", "Urdu", "tesseract,paddleocr"], ["hi", "Hindi", "tesseract,paddleocr"],
  ["fa", "Persian", "tesseract,paddleocr"], ["fr", "French", "tesseract,paddleocr"], ["zh", "Chinese (simplified)", "paddleocr"], ["bn", "Bengali", ""],
  ["ta", "Tamil", ""], ["ml", "Malayalam", ""], ["tl", "Filipino", ""], ["de", "German", "paddleocr"], ["ja", "Japanese", ""],
].map(([code, name, engines]) => ({ code, name, engines: engines ? engines.split(",") : [], installed: !!engines }));

const REVIEW = SAMPLE_REVIEW;

const ENGINES: EngineOption[] = [
  { id: "onecore", name: "OneCore", description: "Free engines that read pages here; nothing leaves this server", color: "#2f6fde", engines: [
    { id: "tesseract", name: "Tesseract 5", does: "Reads every page · 13 languages", status: "ready", runs: "server", price: "Free", free: true, key: "—" },
    { id: "paddleocr", name: "PaddleOCR", does: "Retries weak pages · faxes, photos", status: "ready", runs: "server", price: "Free", free: true, key: "—" },
    { id: "pdf-text", name: "PDF text layer", does: "Digital PDFs, no OCR", status: "ready", runs: "server", price: "Free", free: true, key: "—" },
    { id: "builtin-rules", name: "Rules extractor", does: "Fields by labels and patterns", status: "ready", runs: "server", price: "Free", free: true, key: "—" }] },
  { id: "oneconnect", name: "OneConnect", description: "Paid cloud services read weak pages again", color: "#8b6ae8", engines: [
    { id: "azure-di", name: "Azure Document Intelligence", does: "Read, layout, prebuilt invoice", status: "optin", runs: "cloud", price: "$1.50 · $10 prebuilt", free: false, key: "Vault: not set" },
    { id: "google-docai", name: "Google Document AI", does: "OCR, form parser", status: "optin", runs: "cloud", price: "$1.50", free: false, key: "Vault: not set" },
    { id: "aws-textract", name: "AWS Textract", does: "Text, tables, expenses", status: "optin", runs: "cloud", price: "$1.50 · $10 expenses", free: false, key: "Vault: not set" },
    { id: "abbyy", name: "ABBYY", does: "Depends on your ABBYY product", status: "setup", runs: "cloud", price: "Contract", free: false, key: "—" }] },
  { id: "onemind", name: "OneMind", description: "AI reads the fields of any document", color: "#b0307e", engines: [
    { id: "claude", name: "Claude (Anthropic)", does: "Claude Haiku 5.5 reads the fields; Claude Sonnet 5.5 re-reads documents that would go to review", status: "optin", runs: "cloud", price: "$0.8", free: false, key: "Model connection: not set" },
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
    email: null as EmailInbox | null, einvoiceFirst: true, fieldThresholds: {} as Record<string, number> },
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
  today: 128, straightThrough: 90, waiting: 9, overdue: 1, failed: 1, pagesThisMonth: 3412, cloudCost: 0, cloudOptIn: false,
  days: [[96, 9], [104, 12], [88, 8], [0, 0], [0, 0], [112, 11], [121, 14], [118, 10], [109, 9], [126, 13], [0, 0], [0, 0], [131, 12], [115, 13]]
    .map(([s, r], i) => ({ label: ["21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "1", "2", "3"][i], straight: s, reviewed: r })),
  alerts: [
    { level: "bad", title: "Review overdue", text: "NL-INV-7710.pdf in AP invoices passed its 24 h target", to: "#/review/nl_7710" },
    { level: "bad", title: "1 document failed", text: "contract_signed.pdf is password-protected", to: "#/process/smart-inbox" },
    { level: "warn", title: "Unrecognised pages", text: "3 pages in One Inbox need a document type", to: "#/process/smart-inbox" },
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

/* sample test sets: one of reviewed AP invoices with a past run, one empty; runs are worked out from the engines */
const AP_FIELDS = (n: string, date: string, supplier: string, vat: string, subtotal: number): Record<string, unknown> =>
  ({ invoice_number: n, invoice_date: date, supplier_name: supplier, supplier_vat: vat, subtotal: subtotal.toFixed(2),
    vat_amount: (subtotal * 0.15).toFixed(2), total: (subtotal * 1.15).toFixed(2) });
const item = (setId: string, id: string, fileName: string, expected: Record<string, unknown>, tags: string[], source = "document", sourceDocumentId: string | null = null): TestSetItem =>
  ({ id, setId, fileName, mimeType: fileName.endsWith(".pdf") ? "application/pdf" : "image/png", expected, tags, source, sourceDocumentId, createdAt: "2026-11-02T09:00:00Z" });
const SAMPLE_ITEMS: TestSetItem[] = [
  item("ap-reviewed", "i1", "INV-2026-0931.pdf", AP_FIELDS("INV-2026-0931", "2026-02-13", "Falcon IT Services", "302117745900003", 7884.71), ["reviewed"], "document", "fax_0931"),
  item("ap-reviewed", "i2", "scan_0452.jpg", AP_FIELDS("INV-2026-0452", "2026-03-02", "Oasis Catering Co.", "305561239800003", 1500), ["reviewed", "scan"], "document", "scan_0452"),
  item("ap-reviewed", "i3", "GOS-INV-0412.pdf", AP_FIELDS("INV-2026-0412", "2026-03-09", "Gulf Office Supplies", "310482766100003", 2050.83), ["reviewed"], "document", "gos_0412"),
  item("ap-reviewed", "i4", "delta_bilingual.png", AP_FIELDS("INV-2026-0187", "2026-03-11", "Delta Industrial Parts", "311290874400003", 7315.22), ["bilingual"], "upload"),
];
const QUALITY: Record<string, number> = { auto: 0.93, tesseract: 0.86, paddleocr: 0.9, "pdf-text": 0.95, "azure-di": 0.97, "google-docai": 0.96, "aws-textract": 0.96,
  "builtin-rules": 0, claude: 0.05, "llm-local": 0.02, "llm-cloud": 0.04 };
const SPEED: Record<string, number> = { auto: 1.1, tesseract: 0.9, paddleocr: 2.4, "azure-di": 1.6, "google-docai": 1.5, "aws-textract": 1.7,
  "builtin-rules": 0.05, claude: 2.2, "llm-local": 3.5, "llm-cloud": 1.8 };

/** A finished run worked out from the engines' sample quality: the same set and engines always give the same numbers. */
function sampleRun(id: string, setId: string, items: TestSetItem[], combos: Combo[], ready: (id: string) => boolean, at: string): SetRun {
  const rows: SetRunRow[] = [];
  const results: ComboResult[] = combos.map((c, ci) => {
    const label = `${c.ocr} × ${c.extraction}`;
    const notReady = [c.ocr, c.extraction].find((e) => e !== "auto" && !ready(e));
    if (notReady) return { ...c, label, skipped: `${notReady}: not ready (set it up on the Engines page)` };
    const acc = Math.min(0.995, (QUALITY[c.ocr] ?? 0.85) + (QUALITY[c.extraction] ?? 0));
    const mine: SetRunRow[] = items.map((it, i) => {
      const names = Object.keys(it.expected);
      const fields = Object.fromEntries(names.map((n, j) => {
        const ok = ((i * 7 + j * 3 + ci * 5) % 20) / 20 < acc;
        const want = it.expected[n];
        return [n, { want, got: ok ? want : String(want).replace(/\d(?=\D*$)/, (d) => String((Number(d) + 1) % 10)), ok }];
      }));
      const allOk = Object.values(fields).every((f) => f.ok);
      const review = !allOk && (i + ci) % 3 !== 0;
      const pages = it.fileName.endsWith(".pdf") ? 2 : 1;
      return { itemId: it.id, fileName: it.fileName, label, ocr: c.ocr, extraction: c.extraction, tags: it.tags, pages,
        seconds: Math.round(((SPEED[c.ocr] ?? 1) + (SPEED[c.extraction] ?? 0)) * pages * 100) / 100, error: null, fields,
        reviewRequired: review, reviewReasons: review ? ["a field under its confidence threshold"] : [], straightThrough: allOk && !review, wronglyAutoApproved: !allOk && !review };
    });
    rows.push(...mine);
    const all = mine.flatMap((r) => Object.values(r.fields));
    const pages = mine.reduce((n, r) => n + r.pages, 0);
    const names = [...new Set(mine.flatMap((r) => Object.keys(r.fields)))];
    const per1000 = (CLOUD_OCR_PER_1000[c.ocr] ?? 0) + (EXTRACT_PER_1000[c.extraction === "claude" ? "claude-haiku-5-5" : c.extraction] ?? (c.extraction === "llm-cloud" ? 2 : 0));
    const wrong = mine.filter((r) => r.wronglyAutoApproved).length;
    const n = Math.max(1, mine.length);
    return { ...c, label, skipped: null, documents: mine.length, pages, errors: 0,
      fieldAccuracy: all.length ? all.filter((f) => f.ok).length / all.length : null,
      byField: Object.fromEntries(names.map((k) => { const fs = mine.map((r) => r.fields[k]).filter(Boolean); return [k, fs.filter((f) => f.ok).length / Math.max(1, fs.length)]; })),
      straightThrough: mine.filter((r) => r.straightThrough).length / n, reviewRate: mine.filter((r) => r.reviewRequired).length / n,
      wronglyAutoApproved: wrong, wronglyAutoApprovedRate: wrong / n, charAccuracy: acc, credits: 0,
      costUsd: Math.round(per1000 * pages / 10) / 100, costPer1000PagesUsd: per1000, costKnown: true,
      secondsPerPage: Math.round(mine.reduce((x, r) => x + r.seconds, 0) / Math.max(1, pages) * 1000) / 1000, p95Seconds: Math.max(0, ...mine.map((r) => r.seconds)) };
  });
  return { id, setId, status: "completed", combos, results, error: null, createdAt: at, finishedAt: at, rows };
}

/** Sample data in memory. Writes change it, so the screens behave as they will against the service. */
export class SampleApi implements DocFlowApi {
  private processesData = copy(PROCESSES);
  private documentsData = copy(DOCUMENTS);
  private routing: Routing = { cloudOptIn: false, retryBelow: 0.85, localRetry: "paddleocr", cloudEngine: "" };
  readonly reviews: { documentId: string; fields: ReviewValues; decision: string }[] = [];
  /** The tenant chosen at the top; the sample data is the same for every tenant. */
  tenant = "";
  setTenant(tenant: string) { this.tenant = tenant; }
  private claude: ClaudeModels = { model: "claude-haiku-5-5", escalationModel: "claude-sonnet-5-5" };

  async overview(_days = 14) { return { ...copy(OVERVIEW), cloudOptIn: this.routing.cloudOptIn }; }
  async processes() { return copy(this.processesData); }
  async processTable(): Promise<ProcessRow[]> {
    const name = (id: string) => ENGINES.flatMap((o) => o.engines).find((e) => e.id === id)?.name ?? id;
    return Promise.all(this.processesData.map(async (p) => {
      const d = await this.processDesign(p.id);
      const inbox = p.id === INBOX_ID;
      const a = ACTIVITY[p.id] ?? { month: p.today, overdue: 0, at: p.lastTime };
      return { ...copy(p), isInbox: inbox, docTypes: inbox ? [] : d.docType.split(", ").filter(Boolean),
        classifyMode: inbox ? "auto" as const : d.settings.classifyMode, extraction: d.settings.extraction === "auto" ? "Default engine" : name(d.settings.extraction),
        checksOn: d.checks.filter((c) => c.on).length, checks: d.checks.length, fieldConfidence: d.settings.fieldConfidence, slaHours: d.settings.slaHours,
        month: a.month, overdue: a.overdue, lastAt: a.at ? todayAt(a.at) : null };
    }));
  }
  async resplit(_d: string, _p: { pages: number[]; docType: string | null }[]) { /* sample screens split nothing */ }
  async addTeachingExample(_t: string, _d: string) { /* sample screens keep no examples */ }
  async removeTeachingExample(_t: string, _d: string) { /* sample screens keep no examples */ }

  async improveInstructions(_setId: string): Promise<Improvement> {
    return { runId: "r1", docType: "supplier-invoice", engine: "Claude", proposals: [{ name: "vat_amount", label: "VAT amount", current: "",
      proposed: "The tax amount on the totals line labelled VAT, not the tax rate and not a line item's tax.", wrong: 2, examples: [{ right: "6,257.13", read: "15" }] }] };
  }

  async processSpend(_processId: string) { return { spend: 12.4, budget: null, currency: "USD", localOnly: false }; }

  async accuracy(_processId: string): Promise<Accuracy> {
    return { documents: 120, finished: 110, reviewed: 40, measured: 480, straightThrough: 0.64, reviewLevel: 0.85,
      fields: [{ name: "vat_amount", label: "VAT amount", seen: 40, corrected: 6, correctedShare: 0.15 }, { name: "supplier_name", label: "Supplier", seen: 40, corrected: 3, correctedShare: 0.075 },
        { name: "invoice_number", label: "Invoice number", seen: 40, corrected: 1, correctedShare: 0.025 }],
      levels: [0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.99].map((level, i) => ({ level, straightThrough: 0.95 - i * 0.08, wrongPassed: Math.max(0, 0.06 - i * 0.007), fields: 400 - i * 30 })) };
  }

  async documents(processId: string, limit = 200) { return copy(this.documentsData.filter((d) => d.processId === processId).slice(0, limit)); }
  async addDocuments(files: File[], processId: string | "auto") {
    const target = processId === "auto" ? "smart-inbox" : processId;
    files.forEach((f, i) => this.documentsData.unshift(doc({ id: "new_" + Date.now() + "_" + i, processId: target, name: f.name, status: "processing",
      time: "now", sourceLabel: "Upload · you", engine: "Waiting to start" })));
    const p = this.processesData.find((x) => x.id === target);
    if (p) { p.today += files.length; p.processing += files.length; }
    return { processId: target };
  }
  async folders() { return ["Finance/Accounts payable", "Finance/Expenses", "Operations/Goods received", "Shared/Unsorted"]; }
  async vaultRefs() { return [{ ref: "mail/invoices/password", label: "mail · invoices · password" }, { ref: "m365/app/secret", label: "m365 · app · secret" }]; }
  private msPolls = 0;
  async msSignInReady() { return true; }
  async msSignInStart(): Promise<MsSignInStart> { this.msPolls = 0; return { id: "ms" + "0".repeat(24), userCode: "SAMPLE-01", verificationUri: "https://microsoft.com/devicelogin", expiresIn: 900, interval: 1 }; }
  async msSignInPoll(): Promise<MsSignInState> { return ++this.msPolls < 2 ? { status: "pending" } : { status: "signed_in", account: "invoices@example.com" }; }
  // DA-1: folders on DocFlow's server, as a small tree (folder -> the folders in it)
  private allowed = "Scans=\\\\scan01\\scans; Accounts payable=D:\\AP";
  private tree: Record<string, string[]> = { "\\\\scan01\\scans": ["invoices", "receipts"], "\\\\scan01\\scans\\invoices": ["2025", "jeddah", "riyadh"],
    "\\\\scan01\\scans\\invoices\\2025": [], "\\\\scan01\\scans\\invoices\\jeddah": [], "\\\\scan01\\scans\\invoices\\riyadh": [], "\\\\scan01\\scans\\receipts": [], "D:\\AP": [] };
  private waiting: Record<string, number> = { "\\\\scan01\\scans\\invoices\\riyadh": 12, "\\\\scan01\\scans\\invoices\\jeddah": 3 };
  private watched: Record<string, FolderUse[]> = { "\\\\scan01\\scans\\invoices\\jeddah": [{ id: "credit-notes", name: "Supplier credit notes" }] };
  private sampleRoots(list: string): FolderRoots {
    const roots = parseFolderList(list).map((r) => ({ ...r, access: (r.path in this.tree ? "ok" : "missing") as FolderAccess,
      usedBy: Object.entries(this.watched).filter(([p]) => inFolder(p, r.path)).flatMap(([, u]) => u) }));
    const outside = Object.entries(this.watched).filter(([p]) => roots.length && !roots.some((r) => inFolder(p, r.path)))
      .flatMap(([p, u]) => u.map((x) => ({ ...x, use: "watched" as const, path: p })));
    return { limited: roots.length > 0, roots, outside };
  }
  async folderRoots() { return this.sampleRoots(this.allowed); }
  async folderList(path: string): Promise<FolderListing> {
    const root = parseFolderList(this.allowed).find((r) => inFolder(path, r.path));
    if (!root) throw new Error(`'${path}' is not one of the folders processes may use`);
    if (!(path in this.tree)) throw new Error(`Folder not found: ${path}`);
    const atRoot = path === root.path;
    return { path, name: atRoot ? root.name : path.slice(path.lastIndexOf("\\") + 1), root, parent: atRoot ? null : path.slice(0, path.lastIndexOf("\\")),
      access: "ok", waiting: this.waiting[path] ?? 0, watchedBy: this.watched[path] ?? [], truncated: false,
      folders: this.tree[path].map((n) => { const p = path + "\\" + n; return { name: n, path: p, waiting: this.waiting[p] ?? 0, watchedBy: this.watched[p] ?? [] }; }) };
  }
  async folderCreate(path: string, name: string) {
    await this.folderList(path);
    if (!name.trim() || /[\\/:*?"<>|]/.test(name)) throw new Error("Use a folder name without \\ / : * ? \" < > |");
    if (this.tree[path].includes(name.trim())) throw new Error(`There is already a folder named '${name.trim()}' in ${path}`);
    this.tree[path] = [...this.tree[path], name.trim()].sort((a, b) => a.localeCompare(b));
    this.tree[path + "\\" + name.trim()] = [];
    return this.folderList(path + "\\" + name.trim());
  }
  async folderPreview(list: string) { return this.sampleRoots(list); }
  async allowedFolders(): Promise<string | null> { return this.allowed; }
  async saveAllowedFolders(list: string) { this.allowed = list; }
  // MA-4: two sample mail accounts and the rules that send emails to processes
  private mailRulesData: ProcessMailRule[] = [{ id: "mrule_1", account_id: "mail_1", account_name: "AP invoices mailbox", account_address: "invoices@contoso.com", account_status: "working",
    name: "Supplier invoices KSA", says: "from @acme.com · has a PDF file", conditions: { match: "all", when: [{ field: "from", op: "domain_in", value: "@acme.com" }, { field: "attachment_type", op: "is_one_of", value: "pdf" }], except: [] },
    matched: 18, enabled: true }];
  private mailTargets: Record<string, string> = { mrule_1: "supplier-invoices-ksa" };
  async mailAccounts(): Promise<MailAccountBrief[]> {
    return [{ id: "mail_1", name: "AP invoices mailbox", address: "invoices@contoso.com", provider_name: "Microsoft 365", status: "working", can_use: true, can_write: false },
      { id: "mail_2", name: "Supplier Gmail", address: "suppliers.acme@gmail.com", provider_name: "Gmail, Google Workspace", status: "working", can_use: true, can_write: true }];
  }
  async processMailRules(processId: string) { return copy(this.mailRulesData.filter((r) => this.mailTargets[r.id] === processId)); }
  async saveProcessMailRule(account: string, id: string | null, rule: { name: string; conditions: MailConditions; target: string; after_read?: string }) {
    const a = (await this.mailAccounts()).find((x) => x.id === account)!;
    const says = rule.conditions.when.length ? rule.conditions.when.map((c) => c.field + " " + (Array.isArray(c.value) ? c.value.join(" or ") : c.value)).join(" · ") : "Every email";
    const row: ProcessMailRule = { id: id ?? "mrule_" + (this.mailRulesData.length + 1), account_id: a.id, account_name: a.name, account_address: a.address, account_status: a.status,
      name: rule.name, says, conditions: rule.conditions, matched: 0, enabled: true, after_read: rule.after_read ?? "none" };
    this.mailRulesData = id ? this.mailRulesData.map((r) => (r.id === id ? { ...row, matched: r.matched } : r)) : [...this.mailRulesData, row];
    this.mailTargets[row.id] = rule.target;
  }
  async deleteMailRule(id: string) { this.mailRulesData = this.mailRulesData.filter((r) => r.id !== id); }
  async tryMailRule(): Promise<MailTry> {
    return { looked_at: 200, says: "", taken_by_earlier: 3, matched: [{ subject: "INV-2026-0042.pdf", from: "billing@acme.com", date: "2026-11-15T09:00:00Z" }],
      not_matched: [{ subject: "Payment received", from: "noreply@globex.com", date: "2026-11-15T08:00:00Z" }] };
  }
  async createProcess(n: NewProcess) {
    const id = n.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "process";
    this.processesData.push({ id, name: n.name, folder: n.folder, docType: DOC_TYPES.find((t) => t.id === n.docType)?.name ?? n.docType, state: "draft", version: 1, today: 0, review: 0, processing: 0,
      failed: 0, done: 0, straightThrough: 0, lastDocument: "—", lastTime: "", deliversTo: [],
      sources: ["upload", ...(n.watchedFolder ? ["folder" as const] : []), ...(n.email ? ["email" as const] : []), ...(n.fromBot ? ["bot" as const] : [])] });
    return { id };
  }
  async reviewTask(documentId: string): Promise<ReviewTask> {
    const d = this.documentsData.find((x) => x.id === documentId);
    const approved = this.reviews.some((r) => r.documentId === documentId && r.decision === "approve") && d?.status === "done";
    // where it stands in the documents waiting for review, for Previous task and Next task
    const queue = this.documentsData.filter((x) => x.status === "review");
    const at = queue.findIndex((x) => x.id === documentId);
    const place = at < 0 ? {} : { position: at + 1, of: queue.length,
      ...(at > 0 ? { previousId: queue[at - 1].id } : {}), ...(at < queue.length - 1 ? { nextId: queue[at + 1].id } : {}) };
    return { ...copy(REVIEW), documentId, name: d?.name ?? REVIEW.name, docTypeId: "supplier-invoice", ...place,
      ...(approved ? { status: "done" as const, reviewedBy: "you", documentReasons: [] } : {}) };
  }
  async completeReview(documentId: string, fields: ReviewValues, decision: "approve" | "reject", _note?: string) {
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
    if (id === "claude") return [{ key: "CLAUDE_API_KEY", label: "Anthropic API key", type: "secret", value: "" },
      { key: "CLAUDE_MODEL", label: "Model", type: "select", value: this.claude.model, options: ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"],
        help: "Haiku 5.5: cheapest, the default. Sonnet 5.5: mid-tier. Opus 5.5: most accurate, most expensive." },
      { key: "CLAUDE_ESCALATION_MODEL", label: "Escalation model", type: "select", value: this.claude.escalationModel ?? "none",
        options: ["claude-sonnet-5-5", "claude-opus-5-5", "none"], help: "Re-reads a document whose fields would go to review, and keeps the better result. none = off." }];
    return id === "azure-di" ? [{ key: "AZURE_DI_ENDPOINT", label: "Endpoint", type: "text", value: "" }, { key: "AZURE_DI_KEY", label: "Key", type: "secret", value: "" },
      { key: "AZURE_DI_REGION", label: "Region", type: "text", value: "westeurope", help: "Used by the residency policy, e.g. uaenorth." }] : [];
  }
  async saveEngineSettings(values: Record<string, string>) {
    if (values.CLAUDE_MODEL) this.claude = { model: values.CLAUDE_MODEL, escalationModel: values.CLAUDE_ESCALATION_MODEL === "none" ? null : values.CLAUDE_ESCALATION_MODEL ?? this.claude.escalationModel };
  }
  async engines() {
    const options = copy(ENGINES);
    if (this.routing.cloudOptIn) options.forEach((o) => o.engines.forEach((e) => { if (e.status === "optin") e.status = "setup"; }));
    return { routing: { ...this.routing }, options, claude: { ...this.claude } };
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
  private offered = ["en", "ar"];
  async einvoiceStandards(): Promise<EInvoiceStandard[]> {
    return [{ id: "ubl", name: "UBL 2.1 XML (Peppol, EN 16931, XRechnung UBL, ZATCA, and others)", kind: "xml" },
      { id: "cii", name: "CII XML (Factur-X, ZUGFeRD, XRechnung CII)", kind: "xml" }, { id: "zatca-qr", name: "Saudi Arabia ZATCA QR code", kind: "qr" },
      { id: "gst-qr", name: "India GST e-invoice QR code", kind: "qr" }, { id: "swiss-qr", name: "Swiss QR-bill", kind: "qr" },
      { id: "epc-qr", name: "SEPA payment QR code (EPC, GiroCode)", kind: "qr" }];
  }

  async languages(): Promise<Language[]> {
    const all = LANGUAGES.map((l) => ({ ...l, engines: [...l.engines], offered: this.offered.includes(l.code) }));
    return [...this.offered.map((c) => all.find((l) => l.code === c)).filter((l): l is Language => !!l), ...all.filter((l) => !l.offered)];
  }
  async saveOfferedLanguages(codes: string[]) {
    if (!codes.length) throw new Error("offer at least one language");
    this.offered = [...new Set(codes)];
  }
  private tables = copy(REF_TABLES);
  async refTables() { return copy(this.tables); }
  async refTableRows(id: string) { return id === "suppliers" ? copy(SUPPLIER_ROWS) : []; }
  async uploadRefTable(file: File, name: string, replaceId?: string) {
    const title = name.trim() || file.name.replace(/\.[^.]+$/, "");
    const same = this.tables.find((t) => t.name.toLowerCase() === title.toLowerCase());
    const id = replaceId ?? same?.id ?? ((title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "table") + (this.tables.length ? "-" + this.tables.length : ""));
    this.tables = [...this.tables.filter((t) => t.id !== id), { id, name: title, rows: 0, meta: "Not used by any check", columns: [], updated: "now" }];
    return { id };
  }
  async deleteRefTable(id: string) { this.tables = this.tables.filter((t) => t.id !== id); }
  async runTest(_file: File, _type: string, a: string, b: string | null): Promise<TestRun> {
    return { rows: copy(TEST).map((r) => (b ? r : { ...r, b: "", bConf: "", differs: false })),
      runs: [TEST_RUN(a, 0.6, 78, true), ...(b ? [TEST_RUN(b, 7.3, 98, false)] : [])] };
  }

  /* test sets: in memory; a run is "running" when started and done when next looked at, as a quick DocFlow would be */
  private sets: SetHead[] = [
    { id: "ap-reviewed", name: "AP invoices, reviewed", docType: "supplier-invoice", description: "Approved invoices from AP invoices, incl. a fax and a scan.",
      createdAt: "2026-11-02T09:00:00Z", updatedAt: "2026-11-02T09:00:00Z" },
    { id: "receipts", name: "Receipts", docType: "receipt", description: "", createdAt: "2026-11-03T09:00:00Z", updatedAt: "2026-11-03T09:00:00Z" },
  ];
  private items: TestSetItem[] = copy(SAMPLE_ITEMS);
  private runsData: (SetRun & { pending?: boolean })[] = [
    sampleRun("r1", "ap-reviewed", SAMPLE_ITEMS, [{ ocr: "auto", extraction: "builtin-rules" }, { ocr: "paddleocr", extraction: "builtin-rules" }], () => true, "2026-11-03T15:20:00Z"),
  ];
  private nextId = 1;
  private async readyEngine() {
    const ready = new Set((await this.engines()).options.flatMap((o) => o.engines).filter((e) => e.status === "ready").map((e) => e.id));
    return (id: string) => ready.has(id);
  }
  private setOut(s: SetHead, withItems = false): TestSet {
    const items = this.items.filter((i) => i.setId === s.id);
    const last = this.runsData.filter((r) => r.setId === s.id).at(-1);
    const lastRun = last ? { ...last, rows: undefined, pending: undefined } : null;
    return copy({ ...s, itemCount: items.length, lastRun, ...(withItems ? { items } : {}) });
  }
  private findSet(id: string): SetHead {
    const s = this.sets.find((x) => x.id === id);
    if (!s) throw new Error(`unknown test set '${id}'`);
    return s;
  }
  async testSets(docType?: string) { return this.sets.filter((s) => !docType || s.docType === docType).map((s) => this.setOut(s)); }
  async testSet(id: string) { return this.setOut(this.findSet(id), true); }
  async createTestSet(name: string, docType: string, description = "") {
    if (!name.trim()) throw new Error("name is required");
    const base = name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "testset";
    let id = base;
    for (let n = 2; this.sets.some((s) => s.id === id); n++) id = `${base}-${n}`;
    const now = new Date().toISOString();
    this.sets.push({ id, name: name.trim(), docType, description, createdAt: now, updatedAt: now });
    return this.testSet(id);
  }
  async updateTestSet(id: string, name: string, description: string) {
    const s = this.findSet(id);
    Object.assign(s, { name: name.trim() || s.name, description, updatedAt: new Date().toISOString() });
    return this.testSet(id);
  }
  async deleteTestSet(id: string) {
    this.sets = this.sets.filter((s) => s.id !== id);
    this.items = this.items.filter((i) => i.setId !== id);
    this.runsData = this.runsData.filter((r) => r.setId !== id);
  }
  async seedTestSet() {
    const name = "Synthetic invoices (seed)";
    const s = this.sets.find((x) => x.name === name)
      ?? await this.createTestSet(name, "supplier-invoice", "Generated invoices (no real data): clean, scan, fax, PDF and Arabic/English bilingual pages.");
    const have = new Set(this.items.filter((i) => i.setId === s.id).map((i) => i.fileName));
    ["clean", "scan", "fax", "pdf", "bilingual"].forEach((v, k) => [1, 2, 3].forEach((n) => {
      const fileName = `invoice_${String(n).padStart(2, "0")}_${v}.${v === "pdf" ? "pdf" : "png"}`;
      if (!have.has(fileName)) this.items.push(item(s.id, `seed${k}${n}`, fileName,
        AP_FIELDS(`INV-2026-${7000 + k * 10 + n}`, `2026-0${n}-1${k}`, "Seeded Supplier Co.", "300000000000003", 1000 * n + 125 * k), [v], "seed"));
    }));
    return this.testSet(s.id);
  }
  async addToTestSet(setId: string, documentId: string, tags: string[] = []) {
    const s = this.findSet(setId);
    const approved = this.reviews.filter((r) => r.documentId === documentId && r.decision === "approve").at(-1);
    const d = this.documentsData.find((x) => x.id === documentId);
    if (!d || d.status !== "done" || !approved) throw new Error("only reviewed documents can be added");
    const t = await this.reviewTask(documentId);
    if (t.docTypeId && t.docTypeId !== s.docType) throw new Error(`the document is a '${t.docTypeId}', the set is for '${s.docType}'`);
    const fields = this.types.find((x) => x.id === s.docType)?.fields.filter((f) => f.type !== "Table").map((f) => f.key) ?? [];
    const expected = Object.fromEntries(fields.map((k) => [k, approved.fields[k] ?? t.fields.find((f) => f.id === k)?.value]).filter(([, v]) => v !== undefined && v !== ""));
    const it = item(setId, `d${this.nextId++}`, d.name, expected, tags.length ? tags : ["reviewed"], "document", documentId);
    this.items.push(it);
    return copy(it);
  }
  async addTestItem(setId: string, file: File, expected: Record<string, unknown>, tags: string[]) {
    this.findSet(setId);
    const values = Object.fromEntries(Object.entries(expected).filter(([, v]) => v !== "" && v !== null && v !== undefined));
    if (!Object.keys(values).length) throw new Error("give at least one expected value");
    const it = item(setId, `u${this.nextId++}`, file.name, values, tags, "upload");
    this.items.push(it);
    return copy(it);
  }
  async updateTestItem(setId: string, itemId: string, expected: Record<string, unknown>, tags: string[]) {
    const it = this.items.find((i) => i.setId === setId && i.id === itemId);
    if (!it) throw new Error(`unknown item '${itemId}'`);
    it.expected = Object.fromEntries(Object.entries(expected).filter(([, v]) => v !== "" && v !== null && v !== undefined));
    it.tags = tags;
    return copy(it);
  }
  async deleteTestItem(setId: string, itemId: string) { this.items = this.items.filter((i) => !(i.setId === setId && i.id === itemId)); }
  testItemFileUrl(_setId: string, itemId: string) {
    return "data:text/plain;charset=utf-8," + encodeURIComponent(`Sample data: the file of ${this.items.find((i) => i.id === itemId)?.fileName ?? itemId} is not kept here.`);
  }
  async startSetRun(setId: string, combos: Combo[]) {
    this.findSet(setId);
    if (!this.items.some((i) => i.setId === setId)) throw new Error("the test set has no documents yet");
    if (combos.length > 6) throw new Error("at most 6 engine combinations per run");
    const run = { id: `run${this.nextId++}`, setId, status: "running", combos: copy(combos), results: [], error: null,
      createdAt: new Date().toISOString(), finishedAt: null, rows: [], pending: true };
    this.runsData.push(run);
    return copy({ ...run, pending: undefined });
  }
  async setRuns(setId: string, limit = 50) {
    return this.runsData.filter((r) => r.setId === setId).reverse().slice(0, limit).map((r) => copy({ ...r, rows: undefined, pending: undefined }));
  }
  async setRun(setId: string, runId: string) {
    const at = this.runsData.findIndex((r) => r.setId === setId && r.id === runId);
    if (at < 0) throw new Error(`unknown run '${runId}'`);
    const r = this.runsData[at];
    if (r.pending) this.runsData[at] = sampleRun(r.id, setId, this.items.filter((i) => i.setId === setId), r.combos, await this.readyEngine(), r.createdAt);
    return copy(this.runsData[at]);
  }
}

/** A test set as SampleApi keeps it (counts and the last run are worked out). */
type SetHead = Omit<TestSet, "itemCount" | "lastRun" | "items">;

/* ------------------------------------------------------------------ cost estimate (DocFlow's docauto/costs.py) */

export const CLOUD_OCR_PER_1000: Record<string, number> = { "azure-di": 1.5, "google-docai": 1.5, "aws-textract": 1.5 };
export const EXTRACT_PER_1000: Record<string, number> = {
  "builtin-rules": 0, "llm-local": 0, "azure-di": 10, "aws-textract": 10,
  "claude-opus-5-5": 32, "claude-sonnet-5-5": 16, "claude-haiku-5-5": 0.8,
};

/** Prices per 1,000 pages as DocFlow has them (its list prices, or the administrator's own), in DocFlow's currency. */
export interface PriceList { currency: string; ocr: Record<string, number>; extract: Record<string, number> }
/** The list prices above, in US dollars: used when DocFlow gives none (the sample screens, an older DocFlow). */
export const LIST_PRICES: PriceList = { currency: "USD", ocr: CLOUD_OCR_PER_1000, extract: EXTRACT_PER_1000 };

export function estimate(pages: number, cloudShare: number, fieldShare: number, cloudOcr: string, extraction: string, prices: PriceList = LIST_PRICES) {
  const cloudPages = Math.round(pages * cloudShare);
  const fieldPages = Math.round(pages * fieldShare);
  const ocrRate = prices.ocr[cloudOcr] ?? 0, fieldRate = prices.extract[extraction] ?? 0;
  const ocr = ocrRate * cloudPages / 1000;
  const fields = fieldRate * fieldPages / 1000;
  return { localPages: pages - cloudPages, cloudPages, fieldPages, ocr, fields, total: Math.round((ocr + fields) * 100) / 100, ocrRate, fieldRate };
}

/** An amount in the price currency, written the way the person's browser writes money. */
export function money(v: number, currency = "USD") {
  try { return new Intl.NumberFormat(undefined, { style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v); }
  catch { return `${currency} ${v.toFixed(2)}`; }
}
