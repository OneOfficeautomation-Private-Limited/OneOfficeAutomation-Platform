import type { MailConditions, MailTry } from "../../api/cr";
import {
  ANY_TYPE, INBOX_ID, NO_FOLDER_LIST, RULE_KIND, ruleTest, stagesOf, type FolderListing, type FolderRoots, type MailAccountBrief, type ProcessMailRule,
  type Check, type Delivery, type DeliveryType, type DocFlowApi, type MsSignInStart, type MsSignInState, type DocStatus, type DocType, type DocTypeField, type DocumentRow, type Engine,
  type EInvoice, type EmailInbox, type EngineConnections, type FieldKind, type ReviewMode, type DeliveryResult, type DeliveryLogRow, type EngineField, type EngineOption, type InboxStatus, type Language, type NewProcess, type Overview, type Place, type ProcessDesign,
  type ProcessRow, type ProcessSummary, type RefTable, type CheckStrictness, type PriceList, type EInvoiceStandard, type Accuracy, type Improvement,
  type ReviewFlag, type ReviewTable, type ReviewTask, type ReviewValues, type Routing, type RefRow, type RuleType, type Source, type TestRun, modelName,
  type Combo, type SetRun, type TestSet, type TestSetItem,
} from "./api";

/**
 * The real DocFlow client: the Command Center forwards /api/docflow/… to the DocFlow service (/api/docs/v1/…) with the
 * signed-in person's tenant, so this only maps DocFlow's shapes onto the screens'. Cookie sign-in, like the console.
 */

/* ------------------------------------------------------------------ DocFlow's shapes (docauto/api/app.py) */

interface Stats {
  byStatus: Record<string, number>; total: number; processed: number; stpRate: number | null; pages: number; credits: number;
  daily: { day: string; total: number; stp: number; human: number; failed: number }[];
  /** Documents that came in since the 1st of this month (newer DocFlow). */
  thisMonth?: number;
}
/** A DocFlow field type as what a check may compare it with (step 5). */
function fieldKind(type: string): FieldKind {
  return ["number", "currency", "percentage", "integer", "amount"].includes(type) ? "number" : type === "date" || type === "datetime" ? "date" : "text";
}
interface DfFieldDef {
  name: string; type: string; label?: string | null; required: boolean; regex?: string | null; format?: string | null; aliases: string[]; columns: DfFieldDef[];
  fallback?: string | null; description?: string | null; enumValues?: string[]; enumDescriptions?: Record<string, string>; allowOther?: boolean;
  method?: "extract" | "infer" | "classify"; patternSeverity?: CheckStrictness; output?: string | null;
}
interface DfDocType {
  id: string; name: string; description: string; category: string; languages: string[]; keywords: string[]; fields: DfFieldDef[];
  dateOrder?: string | null; decimalMark?: string | null; examples?: string[];
  checks: string[]; learning: { enabled: boolean; aliases: Record<string, string[]> }; version: number; createdAt: string;
}
interface DfRule { id: string; type: RuleType; name: string; enabled: boolean; action: "review" | "reject" | "warn"; docTypes?: string[]; params: Record<string, unknown> }
interface DfDelivery { id: string; type: DeliveryType; name: string; enabled: boolean; on: string[]; params: Record<string, unknown> }
interface DfFlow {
  id: string; name: string; description: string; folder?: string; docTypes: string[]; status: "draft" | "live"; isInbox: boolean; version: number;
  classify: { mode: "single" | "auto"; split: boolean; minScore?: number; whenNoType?: "review" | "other" | "reject"; checkType?: boolean }; engine: { option: string; ocrProvider: string; extractionProvider: string; monthlyBudget?: number | null; extractionByType?: Record<string, string> };
  review: { fieldConfidence: number; slaHours: number; fieldThresholds?: Record<string, number>; mode?: ReviewMode; documentConfidence?: number }; rules: DfRule[];
  deliveries: DfDelivery[];
  intake: { inputFolder: string; pollSeconds: number; email?: Record<string, unknown>; einvoiceFirst?: boolean; einvoiceStandards?: string[]; upload?: boolean }; updatedAt: string;
  stats?: Stats; docTypeNames?: string[];
}
interface DfFlag { rule: string; type: string; action: string; message: string; field?: string | null }
interface DfDocRow {
  id: string; file_name: string; created_at: string; status: string; instance_id: string; source: string; parent_id: string | null;
  doctype_id: string | null; flags: string | DfFlag[] | null; validated_by: string | null;
  fields?: Record<string, unknown>; confidence?: number | null; pages?: number | null; engines?: string[];
}
/** Where a value is on the pages (docauto/grounding.py locations()); rows only for table fields. */
interface DfPlace { page: number | null; bbox: { x: number; y: number; w: number; h: number } | null; grounded: boolean | null; groundedBy?: string | null }
interface DfLocation extends DfPlace { rows?: (DfPlace & { row: number; cells: Record<string, DfPlace> })[] }
interface DfEInvoice { source: string; standard?: string; values: Record<string, unknown>; checks?: { field: string; qr: unknown; read: unknown; result: "match" | "mismatch" | "differs" | "filled" }[] }
/**
 * Why a document went to review (docauto/scoring.py review_reasons, plus DocFlow's rules): field null = the whole document.
 * reason: low_confidence | required_missing | validation | not_grounded | check_failed | document_confidence | rule | unclassified.
 */
interface DfReason { field: string | null; reason: string; message: string; threshold?: number | null; confidence?: number | null; fields?: string[]; rule?: string | null }
interface DfDocument extends Omit<DfDocRow, "instance_id" | "flags" | "confidence"> {
  flowId: string; flags: DfFlag[]; fields: Record<string, unknown>; confidence: Record<string, number | null>; error: { message?: string } | null;
  locations?: Record<string, DfLocation>; einvoice?: DfEInvoice | null; reviewReasons?: DfReason[];
}
interface DfResult {
  pages: { pageNumber?: number; width?: number; height?: number; lines: { text: string }[] }[];
  provenance: { steps: { stage: string; providerId: string }[] }; confidence: number | null;
}
interface DfProvider {
  id: string; displayName: string; kind: string; runsLocally: boolean; optionPack: string; available: boolean; reason: string | null;
  allowedByPolicy: boolean; policyReason: string | null; languages: string[]; capabilities?: string[];
}
interface DfSetting { key: string; value: string }
interface DfCosts {
  prices: Record<string, { ocr: number | null; extract: number | null }>; claudeModel: string; claudeEscalationModel?: string | null; cloudOptIn: boolean;
  currency?: string; modelPrices?: Record<string, number | null>;
}
/** DocFlow's prices as the cost estimate uses them: engine ids, and Claude by model id. */
const priceList = (c: DfCosts): PriceList => {
  const pick = (use: "ocr" | "extract") => Object.fromEntries(Object.entries(c.prices).flatMap(([id, p]) => (p[use] ?? null) === null ? [] : [[id, p[use] as number]]));
  const models = Object.fromEntries(Object.entries(c.modelPrices ?? {}).flatMap(([m, v]) => (v === null ? [] : [[m, v]])));
  return { currency: c.currency ?? "USD", ocr: pick("ocr"), extract: { ...pick("extract"), ...models } };
};

/* ------------------------------------------------------------------ helpers */

/** Engines that read fields, not pages: their usage counts the pages they were given, not pages they read. */
const EXTRACTORS = new Set(["builtin-rules", "claude", "llm-cloud", "llm-local"]);

/** The usage rows of engines that read pages (OCR and the PDF text layer), by DocFlow's provider kinds when known. */
export function pageReaders<U extends { k: string; pages: number | null }>(usage: U[], providers: { id: string; kind: string }[]): U[] {
  const kind = new Map(providers.map((p) => [p.id, p.kind]));
  return usage.filter((u) => (u.pages ?? 0) > 0 && (kind.has(u.k) ? kind.get(u.k) === "ocr" : !EXTRACTORS.has(u.k)));
}

/** The last n days up to today (YYYY-MM-DD keys, oldest first); days without documents count zero. */
export function lastDays<D extends { day: string; total: number; stp: number; human: number; failed: number }>(daily: D[], todayKey: string, n = 14) {
  const by = new Map(daily.map((d) => [d.day, d]));
  const end = new Date(todayKey + "T00:00:00Z").getTime();
  return Array.from({ length: n }, (_, i) => {
    const day = new Date(end - (n - 1 - i) * 86_400_000).toISOString().slice(0, 10);
    return by.get(day) ?? { day, total: 0, stp: 0, human: 0, failed: 0 };
  });
}

const ENGINE_NAMES: Record<string, string> = {
  tesseract: "Tesseract", paddleocr: "PaddleOCR", "pdf-text": "PDF text layer", "builtin-rules": "Rules extractor", "azure-di": "Azure Document Intelligence",
  "google-docai": "Google Document AI", "aws-textract": "AWS Textract", abbyy: "ABBYY", claude: "Claude (Anthropic)", "llm-cloud": "Cloud AI API", "llm-local": "Self-hosted model",
  gemini: "Google Gemini", bedrock: "Amazon Bedrock", "azure-openai": "Azure OpenAI",
  "azure-content-understanding": "Azure Content Understanding", "google-docai-split": "Google Document AI splitter",
  "aws-bda": "Amazon Bedrock Data Automation",
};
/** What a cloud recognise-and-split service does (DocFlow 3.4.0, kind "classify"). */
const SPLITS = "Recognises and splits whole files";
const OPTIONS: Omit<EngineOption, "engines">[] = [
  { id: "onecore", name: "OneCore", description: "Free engines that read pages here; nothing leaves this server", color: "#2f6fde" },
  { id: "oneconnect", name: "OneConnect", description: "Paid cloud services read weak pages again", color: "#8b6ae8" },
  { id: "onemind", name: "OneMind", description: "AI reads the fields of any document", color: "#b0307e" },
];
const PACK_OPTION: Record<string, EngineOption["id"]> = { tier1_opensource: "onecore", tier2_cloud: "oneconnect", tier3_ai: "onemind" };

const status = (s: string): DocStatus =>
  s === "success" || s === "validated" ? "done" : s === "validation" ? "review" : s === "failed" || s === "invalid" ? "failed" : s === "split" ? "split" : "processing";
const flagsOf = (f: DfDocRow["flags"]): DfFlag[] => (typeof f === "string" ? JSON.parse(f) as DfFlag[] : f ?? []);
const text = (v: unknown) => (v === null || v === undefined || v === "" ? "—" : typeof v === "object" ? JSON.stringify(v) : String(v));
const pct = (v: number | null | undefined) => (v === null || v === undefined ? null : Math.round(v * 100));
const cell = (v: unknown) => (v === null || v === undefined ? "" : typeof v === "object" ? JSON.stringify(v) : String(v));
const placeOf = (l: DfPlace | undefined): Place | undefined =>
  l ? { page: l.page ?? null, bbox: l.bbox ?? null, grounded: l.grounded ?? null, groundedBy: (l.groundedBy ?? null) as Place["groundedBy"] } : undefined;
const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();

function clock(iso: string): string {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  return sameDay(d, new Date()) ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : d.toLocaleDateString([], { day: "numeric", month: "short" });
}

function sourceOf(r: DfDocRow): { source: Source; label: string } {
  if (r.parent_id) return { source: "split", label: "Split from batch" };
  switch (r.source) {
    case "folder": return { source: "folder", label: "Folder" };
    case "email": return { source: "email", label: "Email" };
    case "bot": case "api": return { source: "bot", label: "Bot" };
    default: return { source: "upload", label: "Upload" };
  }
}

/** DocFlow's field types and the screens' names for them. */
const FIELD_TYPES: Record<string, DocTypeField["type"]> = {
  string: "Text", date: "Date", datetime: "Date and time", currency: "Amount", number: "Number", percentage: "Percentage", boolean: "Yes/No",
  checkbox: "Ticked", signature: "Signed", enum: "Choice", multienum: "Several choices", email: "Email", phone: "Phone", address: "Address",
  table: "Table", object: "Group",
};
const fieldType = (t: string): DocTypeField["type"] => FIELD_TYPES[t] ?? "Text";
/** The screens' type back to DocFlow's: the type DocFlow had is kept when the screens show it unchanged (one it has that the screens do not know). */
const dfFieldType = (t: DocTypeField["type"], had?: string) =>
  had && fieldType(had) === t ? had : Object.keys(FIELD_TYPES).find((k) => FIELD_TYPES[k] === t) ?? "string";
const METHOD_TO_DF = { copy: "extract", "work-out": "infer", pick: "classify" } as const;
const METHOD_OF = { extract: "copy", infer: "work-out", classify: "pick" } as const;

/** A DocFlow document type as the screens show it. */
function docTypeOf(t: DfDocType, builtIn: Set<string | undefined>, flows: DfFlow[]): DocType {
  // a process with no types of its own (One Inbox) recognises every type, so it uses each of them
  const usedBy = flows.filter((fl) => fl.isInbox || !fl.docTypes.length || fl.docTypes.includes(t.id)).map((fl) => fl.name);
  const origin = builtIn.has(t.id) ? "built-in" as const : "made-here" as const;
  return {
    id: t.id, name: t.name, origin, usedBy, keywords: t.keywords, languages: t.languages, description: t.description ?? "",
    dateOrder: t.dateOrder ?? "", decimalMark: t.decimalMark ?? "", examples: t.examples ?? [],
    meta: `${origin === "built-in" ? "Built in" : "Made here"} · ${usedBy.length ? usedBy.length + " process" + (usedBy.length > 1 ? "es" : "") : "not used"}`,
    fields: t.fields.map((x) => ({
      key: x.name, label: (x.label ?? x.name).split(" / ")[0], aliases: [...((x.label ?? "").split(" / ").slice(1)), ...x.aliases],
      type: fieldType(x.type), required: x.required, regex: x.regex ?? "", learned: t.learning?.aliases?.[x.name] ?? [],
      description: x.description ?? "", method: METHOD_OF[x.method ?? "extract"] ?? "copy", choices: x.enumValues ?? [],
      choiceNotes: x.enumDescriptions ?? {}, allowOther: !!x.allowOther, strictness: x.patternSeverity ?? "error", writeAs: x.output ?? "", dfType: x.type,
    })),
  };
}

/** Where a delivery points, by kind: the bot to start, the webhook address, or the folder / Excel path. */
const targetOf = (d: DfDelivery) => d.type === "email" ? ((d.params.to as string[] | undefined) ?? []).join(", ")
  : d.type === "cloud_storage" ? String(d.params.bucket ?? d.params.container ?? d.params.url ?? "")
  : String((d.type === "start_bot" ? d.params.bot : d.type === "webhook" ? d.params.url : d.params.path) ?? "");
/** The settings of e-mail and cloud storage deliveries beyond their target, as the dialog edits them. */
const EXTRA_KEYS = ["subject", "message", "provider", "region", "endpoint", "prefix", "accessKey", "secretRef", "files"];
const paramsOf = (d: Delivery, old?: DfDelivery): Record<string, unknown> => {
  const keep = { ...(old?.params ?? {}) };
  if (d.type === "start_bot") return { ...keep, bot: d.target, pool: d.pool ?? "" };
  if (d.type === "webhook") return { ...keep, url: d.target, method: d.method ?? "POST", headers: d.headers ?? {} };
  if (d.type === "email") return { ...keep, ...(d.extra ?? {}), to: d.target.split(/[,;]/).map((x) => x.trim()).filter(Boolean) };
  if (d.type === "cloud_storage") {
    const provider = String(d.extra?.provider ?? "azure_blob");
    const target = provider === "s3" ? { bucket: d.target } : provider === "azure_blob" ? { container: d.target } : { url: d.target };
    return { ...Object.fromEntries(Object.entries(keep).filter(([k]) => !["bucket", "container", "url"].includes(k))), ...(d.extra ?? {}), provider, ...target };
  }
  return { ...keep, path: d.target, ...(d.type === "folder" ? { searchablePdf: !!d.searchablePdf, saveText: !!d.saveText } : {}) };
};
const checkOf = (r: DfRule): Check => ({
  id: r.id, on: r.enabled, name: r.name || RULE_KIND[r.type] || r.type, kind: RULE_KIND[r.type] ?? r.type, test: ruleTest(r.type, r.params ?? {}),
  ifFails: r.action, type: r.type, params: r.params ?? {},
});

export class HttpError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

/* ------------------------------------------------------------------ the client */

export class HttpDocFlowApi implements DocFlowApi {
  constructor(private readonly base = "/api/docflow", private readonly fetcher: typeof fetch = (...a) => fetch(...a)) {}

  private async call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const form = body instanceof FormData;
    const r = await this.fetcher(this.base + path, {
      method, credentials: "same-origin",
      headers: { "X-OOA-Request": "1", ...(body !== undefined && !form ? { "Content-Type": "application/json" } : {}) },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
    });
    if (r.status === 401) { location.assign("/"); throw new HttpError(401, "Please sign in"); }
    const t = await r.text();
    const data = t ? JSON.parse(t) : null;
    if (!r.ok) {
      const e = data?.error ?? data;
      const said = (typeof e?.message === "string" && e.message) || (typeof e?.detail === "string" && e.detail) || `DocFlow answered ${r.status}`;
      // the Command Center's errors carry what to do about them (improvement list item 10)
      const fix = typeof e?.suggestedFix === "string" && e.suggestedFix ? " " + e.suggestedFix : typeof e?.fix === "string" && e.fix ? " " + e.fix : "";
      throw new HttpError(r.status, said + (said.endsWith(".") ? "" : ".") + fix);
    }
    return data as T;
  }

  private flowsCache: Promise<DfFlow[]> | null = null;
  private flows(fresh = false) {
    if (fresh || !this.flowsCache) this.flowsCache = this.call<DfFlow[]>("GET", "/df/flows");
    return this.flowsCache;
  }

  async overview(days = 14): Promise<Overview> {
    const [o, costs, usage, prov] = await Promise.all([
      this.call<{ totals: Stats; review: number; overdue: number; deliveryFailures: number; pagesThisMonth?: number; creditsThisMonth?: number }>("GET", `/df/overview?days=${days}`),
      this.call<DfCosts>("GET", "/costs"),
      this.call<{ k: string; pages: number | null; credits: number | null }[]>("GET", "/usage?groupBy=provider_id").catch(() => []),
      this.call<{ providers: DfProvider[] }>("GET", "/providers").catch(() => ({ providers: [] as DfProvider[] })),
    ]);
    const t = o.totals;
    const todayKey = new Date().toISOString().slice(0, 10);
    const today = t.daily.find((d) => d.day === todayKey);
    const ocr = pageReaders(usage, prov.providers);
    const pages = ocr.reduce((n, u) => n + (u.pages ?? 0), 0);
    const alerts: Overview["alerts"] = [];
    if (o.overdue) alerts.push({ level: "bad", title: "Review overdue", text: `${o.overdue} document${o.overdue > 1 ? "s" : ""} passed the review target`, to: "#/inbox" });
    if (t.byStatus.failed) alerts.push({ level: "bad", title: `${t.byStatus.failed} document${t.byStatus.failed > 1 ? "s" : ""} failed`, text: "Open the process to see why", to: "#/inbox" });
    if (o.deliveryFailures) alerts.push({ level: "warn", title: "Deliveries failed", text: `${o.deliveryFailures} recent deliveries did not arrive`, to: "#/processes" });
    if (!costs.cloudOptIn) alerts.push({ level: "info", title: "Cloud opt-in is off", text: "Weak pages are retried locally only", to: "#/engines" });
    return {
      cloudOptIn: costs.cloudOptIn,
      today: today?.total ?? 0, straightThrough: pct(t.stpRate) ?? 0, waiting: o.review, overdue: o.overdue, failed: t.byStatus.failed ?? 0,
      pagesThisMonth: o.pagesThisMonth ?? t.pages, cloudCost: Math.round((o.creditsThisMonth ?? t.credits ?? 0) * 100) / 100,
      days: lastDays(t.daily, todayKey, days).map((d) => ({ label: new Date(d.day + "T00:00:00").toLocaleDateString(undefined, days > 31 ? { month: "short", day: "numeric" } : { day: "numeric" }), straight: d.stp, reviewed: d.human })),
      alerts,
      // exact shares (one decimal) so the bar adds up; the dashboard writes a share under 1% as "<1%"
      readBy: ocr.map((u) => ({ label: ENGINE_NAMES[u.k] ?? u.k, share: pages ? Math.round(1000 * (u.pages ?? 0) / pages) / 10 : 0 })),
    };
  }

  async processes(): Promise<ProcessSummary[]> {
    const [flows, recent] = await Promise.all([this.flows(true), this.call<DfDocRow[]>("GET", "/df/flows/all/documents?limit=200")]);
    return flows.map((f) => this.summary(f, recent));
  }

  /** A flow's counts and its latest document (recent: the newest documents of every process, newest first). */
  private summary(f: DfFlow, recent: DfDocRow[]): ProcessSummary {
    const todayKey = new Date().toISOString().slice(0, 10);
    const by = f.stats?.byStatus ?? {};
    const last = recent.find((d) => d.instance_id === f.id);
    return {
      id: f.id, name: f.name, folder: f.folder ?? "", docType: f.isInbox ? "Any" : (f.docTypeNames ?? []).join(", ") || "—",
      state: f.status, version: f.version,
      today: f.stats?.daily.find((d) => d.day === todayKey)?.total ?? 0,
      review: by.validation ?? 0, processing: (by.queued ?? 0) + (by.processing ?? 0), failed: (by.failed ?? 0) + (by.invalid ?? 0),
      done: (by.success ?? 0) + (by.validated ?? 0), straightThrough: pct(f.stats?.stpRate) ?? 0,
      lastDocument: last?.file_name ?? "—", lastTime: last ? clock(last.created_at) : "",
      sources: [...(f.intake.upload === false ? [] : ["upload" as const]), ...(f.intake.inputFolder ? ["folder" as const] : []), ...(f.intake.email?.enabled ? ["email" as const] : [])],
      deliversTo: f.deliveries.filter((d) => d.enabled).map((d) => d.name || d.type),
    };
  }

  async processTable(): Promise<ProcessRow[]> {
    // three calls for every process: the flows (with their counts), the newest documents and the review queue
    const [flows, recent, queue] = await Promise.all([
      this.flows(true), this.call<DfDocRow[]>("GET", "/df/flows/all/documents?limit=200"),
      this.call<{ flowId: string; overdue: boolean }[]>("GET", "/df/review").catch(() => []),
    ]);
    const month = new Date().toISOString().slice(0, 7);
    return flows.map((f) => {
      const last = recent.find((d) => d.instance_id === f.id);
      const e = f.engine.extractionProvider;
      return {
        ...this.summary(f, recent), isInbox: f.isInbox, docTypes: f.isInbox ? [] : f.docTypeNames ?? [], classifyMode: f.isInbox ? "auto" : f.classify.mode,
        extraction: !e || e === "auto" ? "Default engine" : ENGINE_NAMES[e] ?? e,
        checksOn: f.rules.filter((r) => r.enabled).length, checks: f.rules.length, fieldConfidence: f.review.fieldConfidence, slaHours: f.review.slaHours,
        month: f.stats?.thisMonth ?? (f.stats?.daily ?? []).filter((d) => d.day.startsWith(month)).reduce((n, d) => n + d.total, 0),
        overdue: queue.filter((q) => q.flowId === f.id && q.overdue).length, lastAt: last?.created_at ?? null,
      };
    });
  }

  async resplit(documentId: string, parts: { pages: number[]; docType: string | null }[]): Promise<void> {
    await this.call("POST", `/df/documents/${encodeURIComponent(documentId)}/split`, { parts });
  }

  async addTeachingExample(docTypeId: string, documentId: string): Promise<void> {
    await this.call("POST", `/df/doctypes/${encodeURIComponent(docTypeId)}/examples`, { documentId });
  }

  async removeTeachingExample(docTypeId: string, documentId: string): Promise<void> {
    await this.call("DELETE", `/df/doctypes/${encodeURIComponent(docTypeId)}/examples/${encodeURIComponent(documentId)}`);
  }

  async improveInstructions(setId: string, runId?: string): Promise<Improvement> {
    return this.call<Improvement>("POST", `/df/testsets/${encodeURIComponent(setId)}/improve`, { runId: runId ?? null });
  }

  async processSpend(processId: string) {
    return this.call<{ spend: number; budget: number | null; currency: string; localOnly: boolean }>("GET", `/df/flows/${encodeURIComponent(processId)}/spend`);
  }

  async accuracy(processId: string): Promise<Accuracy> {
    return this.call<Accuracy>("GET", `/df/flows/${encodeURIComponent(processId)}/accuracy`);
  }

  async documents(processId: string, limit = 200): Promise<DocumentRow[]> {
    const rows = await this.call<DfDocRow[]>("GET", `/df/flows/${encodeURIComponent(processId)}/documents?limit=${limit}&fields=true`);
    return rows.map((r) => {
      const f = r.fields ?? {};
      const flags = flagsOf(r.flags);
      const src = sourceOf(r);
      const st = status(r.status);
      return {
        id: r.id, processId: r.instance_id, name: r.file_name, pages: r.pages ? `${r.pages} page${r.pages > 1 ? "s" : ""}` : "—",
        supplier: text(f.supplier_name ?? f.merchant_name ?? f.vendor), invoice: text(f.invoice_number ?? f.document_number ?? f.po_number),
        total: f.total === undefined || f.total === null ? "—" : `${text(f.total)}${f.currency ? " " + text(f.currency) : ""}`,
        time: clock(r.created_at), source: src.source, sourceLabel: src.label, status: st,
        note: flags[0]?.message ?? (r.status === "invalid" ? "Rejected" + (r.validated_by ? " by " + r.validated_by : "") : r.status === "validated" && r.validated_by ? "Approved by " + r.validated_by : ""),
        engine: st === "processing" ? "Reading…" : (r.engines ?? []).map((e) => ENGINE_NAMES[e] ?? e).join(", then ") || "—",
        confidence: pct(r.confidence),
        values: Object.fromEntries(Object.entries(f).filter(([, v]) => v === null || typeof v !== "object").map(([k, v]) => [k, text(v)])),
      };
    });
  }

  async addDocuments(files: File[], processId: string | "auto"): Promise<{ processId: string }> {
    const target = processId === "auto" ? INBOX_ID : processId;
    const form = new FormData();
    files.forEach((f) => form.append("files", f, f.name));
    await this.call("POST", `/df/flows/${encodeURIComponent(target)}/documents`, form);
    return { processId: target };
  }

  async testDelivery(processId: string, deliveryId: string): Promise<DeliveryResult[]> {
    return this.call<DeliveryResult[]>("POST", `/df/flows/${encodeURIComponent(processId)}/deliveries/${encodeURIComponent(deliveryId)}/test`);
  }

  async deliveryLog(processId: string): Promise<DeliveryLogRow[]> {
    const rows = await this.call<{ delivery: string; type: string; ok: number | boolean; detail: string | null; ts: string; document_id: string }[]>(
      "GET", `/df/flows/${encodeURIComponent(processId)}/deliveries`);
    return rows.map((r) => ({ delivery: r.delivery, type: r.type, ok: !!r.ok, detail: r.detail ?? "", ts: r.ts, documentId: r.document_id }));
  }

  // step 7: agents and runner pools are One CommandCenter's own (/api/repo, /api/pools)
  async agentPaths(): Promise<string[]> {
    const r = await this.fetcher("/api/repo", { credentials: "same-origin" });
    if (!r.ok) throw new Error("The agent list could not be read");
    return ((await r.json() as { bots?: { path: string }[] }).bots ?? []).map((b) => b.path);
  }

  async runnerPools(): Promise<{ id: string; name: string }[]> {
    const r = await this.fetcher("/api/pools", { credentials: "same-origin" });
    if (!r.ok) throw new Error("The runner pools could not be read");
    return ((await r.json() as { pools?: { id: string; name: string }[] }).pools ?? []).map((p) => ({ id: p.id, name: p.name }));
  }

  async vaultRefs(): Promise<{ ref: string; label: string }[]> {
    // the platform's vault folders the person may use, with their credentials' attributes (never the values)
    const r = await this.fetcher("/api/lockers", { credentials: "same-origin" });
    if (!r.ok) return [];
    const body = await r.json() as { lockers?: { name: string; credentials: { name: string; attribute: string }[] }[] };
    return (body.lockers ?? []).flatMap((l) => l.credentials.map((c) => ({ ref: `${l.name}/${c.name}/${c.attribute}`, label: `${l.name} · ${c.name} · ${c.attribute}` })));
  }

  // DA-1: "Sign in with Microsoft" for an email inbox (the platform's /api/docflow/ms-signin)
  async msSignInReady(): Promise<boolean> {
    try { return (await this.call<{ ready: boolean }>("GET", "/ms-signin")).ready; } catch { return false; }
  }
  msSignInStart(): Promise<MsSignInStart> { return this.call<MsSignInStart>("POST", "/ms-signin"); }
  msSignInPoll(id: string): Promise<MsSignInState> { return this.call<MsSignInState>("GET", `/ms-signin/${encodeURIComponent(id)}`); }

  // DA-1 item 5: the folders processes may use (DocFlow 3.2.0); an older DocFlow, or no list, means any typed folder
  async folderRoots(): Promise<FolderRoots> {
    try { return await this.call<FolderRoots>("GET", "/df/folders"); } catch { return NO_FOLDER_LIST; }
  }
  folderList(path: string, q = ""): Promise<FolderListing> { return this.call<FolderListing>("GET", `/df/folders?path=${encodeURIComponent(path)}${q ? "&q=" + encodeURIComponent(q) : ""}`); }
  folderCreate(path: string, name: string): Promise<FolderListing> { return this.call<FolderListing>("POST", "/df/folders", { path, name }); }
  folderPreview(list: string): Promise<FolderRoots> { return this.call<FolderRoots>("POST", "/df/folders/preview", { folders: list }); }
  async allowedFolders(): Promise<string | null> {
    const s = await this.call<{ deployment?: DfSetting[] }>("GET", "/admin/settings");
    const f = s.deployment?.find((x) => x.key === "DOCAUTO_ALLOWED_FOLDERS");
    return f ? String(f.value ?? "") : null;
  }
  async saveAllowedFolders(list: string): Promise<void> { await this.call("PUT", "/admin/settings", { values: { DOCAUTO_ALLOWED_FOLDERS: list } }); }

  // MA-4: mail accounts and rules live in One CommandCenter itself (/api/mail-accounts, /api/mail-rules), not in DocFlow
  private async kernel<T>(method: string, path: string, body?: unknown): Promise<T> {
    const r = await this.fetcher("/api/" + path, { method, credentials: "same-origin",
      headers: { "X-OOA-Request": "1", ...(body !== undefined ? { "Content-Type": "application/json" } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
    const t = await r.text();
    const data = t ? JSON.parse(t) : null;
    if (!r.ok) throw new HttpError(r.status, (data?.error?.message as string) || `One CommandCenter answered ${r.status}`);
    return data as T;
  }
  async mailAccounts(): Promise<MailAccountBrief[]> {
    try { return (await this.kernel<{ accounts: MailAccountBrief[] }>("GET", "mail-accounts")).accounts.filter((a) => a.can_use); } catch { return []; }
  }
  async processMailRules(processId: string): Promise<ProcessMailRule[]> {
    try { return (await this.kernel<{ rules: ProcessMailRule[] }>("GET", `mail-rules?target_kind=process&target=${encodeURIComponent(processId)}`)).rules; } catch { return []; }
  }
  async saveProcessMailRule(account: string, id: string | null, rule: { name: string; conditions: MailConditions; target: string; after_read?: string }): Promise<void> {
    await this.kernel(id ? "PUT" : "POST", id ? `mail-rules/${encodeURIComponent(id)}` : `mail-accounts/${encodeURIComponent(account)}/rules`,
      { account_id: account, name: rule.name, conditions: rule.conditions, target_kind: "process", target: rule.target, after_read: rule.after_read ?? "none" });
  }
  async deleteMailRule(id: string): Promise<void> { await this.kernel("DELETE", `mail-rules/${encodeURIComponent(id)}`); }
  tryMailRule(account: string, conditions: MailConditions, rule?: string): Promise<MailTry> {
    return this.kernel<MailTry>("POST", `mail-accounts/${encodeURIComponent(account)}/rules/try`, { conditions, rule });
  }

  async folders(): Promise<string[]> {
    // the platform's own folders (the Agent repository's), with the person's access to each
    const r = await this.fetcher("/api/repo/folders", { credentials: "same-origin" });
    if (!r.ok) return [];
    const body = await r.json() as { folders: { path: string; access: string }[] };
    return body.folders.filter((f) => ["edit", "manage"].includes(f.access)).map((f) => f.path);
  }

  async createProcess(p: NewProcess): Promise<{ id: string }> {
    const f = await this.call<DfFlow>("POST", "/df/flows", {
      name: p.name, folder: p.folder, docTypes: p.docType === ANY_TYPE ? [] : [p.docType],
      ...(p.docType === ANY_TYPE ? { classify: { mode: "auto", split: true } } : {}),
      intake: { inputFolder: p.watchedFolder ?? "", pollSeconds: p.pollSeconds ?? 30, upload: p.upload !== false },
    });
    this.flowsCache = null;
    return { id: f.id };
  }

  async reviewTask(documentId: string): Promise<ReviewTask> {
    const id = encodeURIComponent(documentId);
    const [d, flows, types, queue, result] = await Promise.all([
      this.call<DfDocument>("GET", `/df/documents/${id}`), this.flows(), this.call<DfDocType[]>("GET", "/df/doctypes"),
      this.call<(DfDocRow & { ageHours: number; slaHours: number })[]>("GET", "/df/review"),
      this.call<DfResult>("GET", `/documents/${id}/result`).catch(() => null),
    ]);
    const flow = flows.find((f) => f.id === d.flowId);
    const type = types.find((t) => t.id === d.doctype_id);
    const at = queue.findIndex((q) => q.id === documentId);
    const mine = queue[at];
    const isTable = (name: string) => Array.isArray(d.fields[name]);
    const defs = type?.fields.filter((f) => f.type !== "table") ?? Object.keys(d.fields).filter((n) => !isTable(n)).map((name) => ({ name, label: name } as DfFieldDef));
    const tableDefs = type?.fields.filter((f) => f.type === "table")
      ?? Object.keys(d.fields).filter(isTable).map((name) => ({ name, label: name, columns: [] as DfFieldDef[] } as DfFieldDef));
    const loc = d.locations ?? {};
    const engines = [...new Set(result?.provenance.steps.filter((s) => s.stage === "digitize").map((s) => ENGINE_NAMES[s.providerId] ?? s.providerId) ?? [])];
    const label = (f: DfFieldDef) => (f.label ?? f.name).split(" / ")[0];
    // why it needs review: the process's rules (flags) and DocFlow's reasons, per field; only while it waits for review
    const reasons = status(d.status) === "review" ? d.reviewReasons ?? [] : [];
    const known = new Set([...defs, ...tableDefs].map((f) => f.name));
    const flagOf = (name: string): ReviewFlag | undefined => {
      const why = [...d.flags.filter((x) => x.field === name && x.action !== "warn").map((x) => ({ kind: "rule", text: x.message })),
        ...reasons.filter((r) => r.field === name).map((r) => ({ kind: r.reason, text: r.message }))];
      const texts = [...new Set(why.map((w) => w.text))];
      return texts.length ? { reason: texts.join(". "), kind: why[0].kind } : undefined;
    };
    return {
      documentId, processId: d.flowId, ...(d.doctype_id ? { docTypeId: d.doctype_id } : {}), status: status(d.status), reviewedBy: d.validated_by ?? undefined, name: d.file_name, processName: flow?.name ?? d.flowId, docType: type?.name ?? "Not recognised",
      cameIn: `${clock(d.created_at)} by ${sourceOf({ ...d, instance_id: d.flowId, flags: d.flags, confidence: null }).label.toLowerCase()}`,
      dueIn: mine ? `${Math.max(0, Math.round(mine.slaHours - mine.ageHours))} h` : "—", targetHours: mine?.slaHours ?? flow?.review.slaHours ?? 24,
      position: at >= 0 ? at + 1 : 1, of: Math.max(queue.length, 1),
      ...(at > 0 ? { previousId: queue[at - 1].id } : {}), ...(at >= 0 && at < queue.length - 1 ? { nextId: queue[at + 1].id } : {}),
      engineTrail: engines.length ? "read by " + engines.join(", re-read by ") + (result?.confidence ? ` (${pct(result.confidence)}%)` : "") : "—",
      pages: (result?.pages ?? []).map((p, i) => {
        const n = p.pageNumber ?? i + 1;
        return { number: n, image: this.pageUrl(documentId, n), width: p.width, height: p.height };
      }),
      pageLines: (result?.pages ?? []).flatMap((p) => p.lines.map((l) => ({ text: l.text }))).slice(0, 200),
      fields: defs.map((f) => {
        const flag = flagOf(f.name);
        const conf = d.confidence[f.name];
        const place = placeOf(loc[f.name]);
        return {
          id: f.name, label: label(f), value: d.fields[f.name] === null || d.fields[f.name] === undefined ? "" : String(d.fields[f.name]),
          confidence: pct(conf) ?? 0, ...(flag ? { flagged: flag } : {}), ...(place ? { place } : {}),
        };
      }),
      tables: tableDefs.map((f): ReviewTable => {
        const raw = Array.isArray(d.fields[f.name]) ? d.fields[f.name] as Record<string, unknown>[] : [];
        const names = f.columns?.length ? f.columns.map((c) => ({ name: c.name, label: label(c) }))
          : [...new Set(raw.flatMap((r) => (r && typeof r === "object" ? Object.keys(r) : [])))].map((n) => ({ name: n, label: n }));
        const flag = flagOf(f.name);
        const l = loc[f.name];
        return {
          id: f.name, label: label(f), columns: names, confidence: pct(d.confidence[f.name]),
          rows: raw.map((r) => Object.fromEntries(names.map((c) => [c.name, cell(r?.[c.name])]))),
          ...(flag ? { flagged: flag } : {}), ...(l ? { place: placeOf(l) } : {}),
          ...(l?.rows ? { rowPlaces: l.rows.map((r) => ({ ...placeOf(r)!, cells: Object.fromEntries(Object.entries(r.cells ?? {}).map(([k, c]) => [k, placeOf(c)!])) })) } : {}),
        };
      }),
      einvoice: d.einvoice ? this.einvoiceOf(d.einvoice, (n) => label(type?.fields.find((x) => x.name === n) ?? ({ name: n } as DfFieldDef))) : null,
      checks: d.flags.length || reasons.some((r) => r.reason === "check_failed")
        ? [...d.flags.map((x) => ({ text: x.rule, passed: x.action === "warn", detail: x.message })),
          ...reasons.filter((r) => r.reason === "check_failed").map((r) => ({ text: r.message.replace(/^Check failed: /, ""), passed: false, detail: "check of the document type" }))]
        : [{ text: "Every check", passed: true, detail: "nothing to fix" }],
      documentReasons: reasons.filter((r) => !r.field || !known.has(r.field)).map((r) => (r.field ? `${r.field}: ${r.message}` : r.message)),
    };
  }

  /** A page of the document as an image (DocFlow renders it with the same geometry as OCR, so boxes fit). */
  pageUrl(documentId: string, page: number): string {
    return `${this.base}/documents/${encodeURIComponent(documentId)}/page/${page}.png`;
  }

  private einvoiceOf(e: DfEInvoice, label: (field: string) => string): EInvoice {
    return {
      source: e.source, standard: e.standard,
      values: Object.fromEntries(Object.entries(e.values ?? {}).filter(([, v]) => v === null || typeof v !== "object").map(([k, v]) => [k, cell(v)])),
      checks: (e.checks ?? []).map((c) => ({ field: c.field, label: label(c.field), qr: text(c.qr), read: text(c.read), result: c.result })),
    };
  }

  async completeReview(documentId: string, fields: ReviewValues, decision: "approve" | "reject", note = ""): Promise<{ next?: string }> {
    const r = await this.call<{ nextDocumentId?: string | null }>("POST", `/df/documents/${encodeURIComponent(documentId)}/review`,
      { fields, action: decision === "approve" ? "submit" : "invalid", ...(note.trim() ? { note: note.trim() } : {}) });
    return { next: r?.nextDocumentId ?? undefined };
  }

  async changeDocType(documentId: string, docTypeId: string): Promise<{ documentId: string; needsReview: boolean }> {
    const d = await this.call<{ id: string; status: string }>("POST", `/df/documents/${encodeURIComponent(documentId)}/reclassify`, { docType: docTypeId });
    return { documentId: d.id, needsReview: d.status === "validation" };
  }

  exportUrl(processId: string, format: "xlsx" | "csv"): string {
    return `${this.base}/df/flows/${encodeURIComponent(processId)}/export?format=${format}`;
  }

  async testEngine(id: string): Promise<{ ok: boolean; message: string }> {
    const r = await this.call<{ ok: boolean; message: string; output?: string }>("POST", `/admin/providers/${encodeURIComponent(id)}/test`);
    return { ok: r.ok, message: r.message + (r.output ? `: "${r.output}"` : "") };
  }

  async engineSettings(id: string): Promise<EngineField[]> {
    const s = await this.call<{ engines: Record<string, { fields: (EngineField & { default?: string })[] }> }>("GET", "/admin/settings");
    return (s.engines[id]?.fields ?? []).map((f) => ({ key: f.key, label: f.label, type: f.type, value: String(f.value ?? ""), help: f.help, options: f.options, suggestions: f.suggestions, placeholder: f.placeholder }));
  }

  async saveEngineSettings(values: Record<string, string>): Promise<void> {
    await this.call("PUT", "/admin/settings", { values });
  }

  // I-4: kept by One CommandCenter itself (/api/docflow/engine-connections), not by DocFlow
  async engineConnections(): Promise<EngineConnections> {
    return this.call<EngineConnections>("GET", "/engine-connections");
  }

  async saveEngineConnection(engine: string, connection: string): Promise<void> {
    await this.call("PUT", "/engine-connections", { engine, connection });
  }

  /** Whether this DocFlow has the recognise engine setting (3.4.0); read with the engines. */
  private knowsClassify = false;

  async engines(): Promise<{ routing: Routing; options: EngineOption[]; claude?: { model: string; escalationModel: string | null }; prices?: PriceList }> {
    const [prov, costs, settings, conns] = await Promise.all([
      this.call<{ providers: DfProvider[] }>("GET", "/providers"), this.call<DfCosts>("GET", "/costs"),
      // routing details are for people who may change engines; everyone else still sees the opt-in from /costs
      this.call<{ defaults: DfSetting[] }>("GET", "/admin/settings").catch(() => null),
      this.engineConnections().catch(() => null),
    ]);
    const v = (k: string, d: string) => settings?.defaults.find((s) => s.key === k)?.value ?? d;
    this.knowsClassify = !!settings?.defaults.some((s) => s.key === "DOCAUTO_DEFAULT_CLASSIFY");
    const routing: Routing = {
      cloudOptIn: costs.cloudOptIn, retryBelow: Number(v("DOCAUTO_ESCALATE_THRESHOLD", "0.85")),
      localRetry: v("DOCAUTO_RETRY_LOCAL", "paddleocr"), cloudEngine: v("DOCAUTO_ESCALATE_TO", ""),
      ocr: v("DOCAUTO_DEFAULT_OCR", "tesseract"), extraction: v("DOCAUTO_DEFAULT_EXTRACTION", "builtin-rules"),
      classify: v("DOCAUTO_DEFAULT_CLASSIFY", ""),
    };
    const price = (id: string, local: boolean) => {
      const p = costs.prices[id];
      const parts = [p?.ocr, p?.extract].filter((x): x is number => x !== null && x !== undefined && x > 0).map((x) => "$" + x);
      return local || !parts.length ? (local ? "Free" : "Contract") : parts.join(" · ");
    };
    const escalation = costs.claudeEscalationModel ?? null;
    const claudeDoes = `${modelName(costs.claudeModel)} reads the fields` + (escalation ? `; ${modelName(escalation)} re-reads documents that would go to review` : "");
    const engine = (p: DfProvider): Engine => ({
      id: p.id, name: ENGINE_NAMES[p.id] ?? p.displayName, does: p.id === "claude" && costs.claudeModel ? claudeDoes : p.kind === "ocr" ? "Reads pages" : p.kind === "classify" ? SPLITS : "Reads fields",
      status: !p.available ? "setup" : !p.allowedByPolicy ? "optin" : "ready",
      runs: p.runsLocally ? "server" : "cloud", price: price(p.id, p.runsLocally), free: p.runsLocally,
      key: conns?.choices[p.id] ? "Model connection: " + conns.choices[p.id] : p.runsLocally ? "—" : p.available ? "Set" : "Not set", ...(p.capabilities ? { can: p.capabilities } : {}),
    });
    // engines from an option pack the screens do not name (a new or own pack) are listed as Other engines, not dropped
    const others = prov.providers.filter((p) => !PACK_OPTION[p.optionPack]).map(engine);
    return {
      routing,
      options: [...OPTIONS.map((o) => ({ ...o, engines: prov.providers.filter((p) => PACK_OPTION[p.optionPack] === o.id).map(engine) })),
        ...(others.length ? [{ id: "other" as const, name: "Other engines", description: "Engines from other option packs", color: "#5b6b7f", engines: others }] : [])],
      ...(costs.claudeModel ? { claude: { model: costs.claudeModel, escalationModel: escalation } } : {}),
      prices: priceList(costs),
    };
  }

  async saveRouting(r: Routing): Promise<void> {
    await this.call("PUT", "/admin/settings", { values: {
      DOCAUTO_CLOUD_OPT_IN: r.cloudOptIn, DOCAUTO_ESCALATE_THRESHOLD: String(r.retryBelow), DOCAUTO_RETRY_LOCAL: r.localRetry, DOCAUTO_ESCALATE_TO: r.cloudEngine,
      ...(r.ocr ? { DOCAUTO_DEFAULT_OCR: r.ocr } : {}), ...(r.extraction ? { DOCAUTO_DEFAULT_EXTRACTION: r.extraction } : {}),
      // DocFlow 3.4.0 knows this setting; an older DocFlow refuses unknown settings, so it is only sent when it was read
      ...(r.classify !== undefined && this.knowsClassify ? { DOCAUTO_DEFAULT_CLASSIFY: r.classify } : {}),
    } });
  }

  async processDesign(id: string): Promise<ProcessDesign> {
    const [f, types] = await Promise.all([this.call<DfFlow>("GET", `/df/flows/${encodeURIComponent(id)}`), this.call<DfDocType[]>("GET", "/df/doctypes")]);
    return this.design(f, types);
  }

  private design(f: DfFlow, types: DfDocType[]): ProcessDesign {
    const mine = f.docTypes.map((t) => types.find((x) => x.id === t)).filter((t): t is DfDocType => !!t);
    const base = {
      docType: f.isInbox ? "Any" : (mine.map((t) => t.name).join(", ") || f.docTypes.join(", ")),
      docTypeIds: f.docTypes,
      checks: f.rules.map(checkOf),
      settings: {
        inputFolder: f.intake.inputFolder, pollSeconds: f.intake.pollSeconds, classifyMode: f.classify.mode, split: f.classify.split,
        whenNoType: f.classify.whenNoType ?? "review", checkType: !!f.classify.checkType,
        extraction: f.engine.extractionProvider, monthlyBudget: f.engine.monthlyBudget ?? null, extractionByType: { ...(f.engine.extractionByType ?? {}) }, fieldConfidence: f.review.fieldConfidence, slaHours: f.review.slaHours, ...(f.review.mode ? { reviewMode: f.review.mode } : {}),
        email: f.intake.email && Object.keys(f.intake.email).length ? (f.intake.email as unknown as EmailInbox) : null,
        einvoiceFirst: f.intake.einvoiceFirst ?? true, einvoiceStandards: f.intake.einvoiceStandards ?? [], upload: f.intake.upload !== false, fieldThresholds: { ...(f.review.fieldThresholds ?? {}) },
      },
      deliveries: f.deliveries.map((d) => ({ id: d.id, type: d.type, name: d.name, enabled: d.enabled, on: d.on, target: targetOf(d),
        ...(d.type === "start_bot" ? { pool: String(d.params.pool ?? "") } : {}),
        ...(d.type === "folder" ? { searchablePdf: !!d.params.searchablePdf, saveText: !!d.params.saveText } : {}),
        ...(d.type === "email" || d.type === "cloud_storage" ? { extra: Object.fromEntries(EXTRA_KEYS.filter((k) => d.params[k] !== undefined).map((k) => [k, d.params[k]])) } : {}),
        ...(d.type === "webhook" ? { method: (String(d.params.method ?? "POST").toUpperCase() as Delivery["method"]), headers: (d.params.headers ?? {}) as Record<string, string> } : {}) })),
    };
    const seen = new Set<string>();
    const fields = (f.isInbox ? types : mine).flatMap((t) => t.fields).filter((x) => x.type !== "table" && !seen.has(x.name) && seen.add(x.name))
      .map((x) => ({ name: x.name, label: (x.label ?? x.name).split(" / ")[0], kind: fieldKind(x.type) }));
    return { id: f.id, name: f.name, state: f.status, version: f.version, folder: f.folder ?? "", ...base, fields,
      stages: stagesOf(base, (e) => ENGINE_NAMES[e] ?? e) };
  }

  async saveProcess(d: ProcessDesign): Promise<ProcessDesign> {
    // DocFlow replaces whole sections, so each one starts from what it holds now (keeps settings these screens do not show)
    const cur = await this.call<DfFlow>("GET", `/df/flows/${encodeURIComponent(d.id)}`);
    const s = d.settings;
    const saved = await this.call<DfFlow>("PUT", `/df/flows/${encodeURIComponent(d.id)}`, {
      ...(cur.isInbox || !d.docTypeIds.length ? {} : { docTypes: d.docTypeIds }),
      // step 2, Where this process is kept (27 Nov 2026): the server checks the person may edit the new folder
      ...((cur.folder ?? "") !== d.folder ? { folder: d.folder } : {}),
      intake: { ...cur.intake, inputFolder: s.inputFolder, pollSeconds: s.pollSeconds, email: s.email ?? {}, einvoiceFirst: s.einvoiceFirst, einvoiceStandards: s.einvoiceStandards ?? [], upload: s.upload !== false },
      classify: { ...cur.classify, mode: s.classifyMode, split: s.split, whenNoType: s.whenNoType ?? "review", checkType: !!s.checkType },
      engine: { ...cur.engine, extractionProvider: s.extraction, monthlyBudget: s.monthlyBudget ?? null, extractionByType: s.extractionByType ?? {} },
      review: { ...cur.review, fieldConfidence: s.fieldConfidence, slaHours: s.slaHours, fieldThresholds: s.fieldThresholds,
        ...(s.reviewMode && cur.review.mode ? { mode: s.reviewMode } : {}) },
      rules: d.checks.map((c) => {
        const old = cur.rules.find((r) => r.id === c.id);
        return { ...(old ?? {}), id: c.id, type: c.type, name: c.name, enabled: c.on, action: c.ifFails, params: c.params };
      }),
      deliveries: d.deliveries.map((x) => {
        const old = cur.deliveries.find((o) => o.id === x.id);
        return { ...(old ?? {}), id: x.id, type: x.type, name: x.name, enabled: x.enabled, on: x.on, params: paramsOf(x, old) };
      }),
    });
    this.flowsCache = null;
    return this.design(saved, await this.call<DfDocType[]>("GET", "/df/doctypes"));
  }

  async inboxStatus(processId: string): Promise<InboxStatus | null> {
    const r = await this.call<{ inboxes: { process_id: string; last_check: string | null; last_error: string | null; documents: number | null }[] }>("GET", "/mail-status");
    const x = r.inboxes.find((i) => i.process_id === processId);
    return x ? { lastCheck: x.last_check, error: x.last_error, documents: x.documents ?? 0 } : null;
  }

  async setLive(id: string, live: boolean): Promise<void> {
    await this.call("POST", `/df/flows/${encodeURIComponent(id)}/live?live=${live}`);
    this.flowsCache = null;
  }

  async docTypes(): Promise<DocType[]> {
    const [types, library, flows] = await Promise.all([
      this.call<DfDocType[]>("GET", "/df/doctypes"), this.call<{ id?: string; category?: string }[]>("GET", "/df/library").catch(() => []), this.flows(),
    ]);
    const builtIn = new Set(library.map((l) => l.id ?? l.category));
    return types.map((t) => docTypeOf(t, builtIn, flows));
  }

  async saveDocType(t: DocType): Promise<DocType> {
    // DocFlow keeps more per field (table columns, choices, formats) than the screens edit: start from what it holds
    const cur = t.id ? await this.call<DfDocType>("GET", `/df/doctypes/${encodeURIComponent(t.id)}`) : null;
    const fields = t.fields.map((x) => {
      const old = cur?.fields.find((o) => o.name === x.key);
      return {
        ...(old ?? {}), name: x.key, label: x.label, aliases: x.aliases, type: dfFieldType(x.type, old?.type ?? x.dfType), required: x.required, regex: x.regex || null,
        description: x.description || null, method: METHOD_TO_DF[x.method ?? "copy"], enumValues: x.choices ?? old?.enumValues ?? [],
        enumDescriptions: x.choiceNotes ?? old?.enumDescriptions ?? {}, allowOther: !!x.allowOther, patternSeverity: x.strictness ?? "error", output: x.writeAs || null,
      };
    });
    const learning = cur ? { ...cur.learning, aliases: Object.fromEntries(t.fields.map((x) => [x.key, x.learned])) } : undefined;
    const body = { name: t.name, description: t.description ?? cur?.description ?? "", keywords: t.keywords, languages: t.languages, fields,
      dateOrder: t.dateOrder || null, decimalMark: t.decimalMark || null, examples: t.examples ?? cur?.examples ?? [], ...(learning ? { learning } : {}) };
    const saved = t.id
      ? await this.call<DfDocType>("PUT", `/df/doctypes/${encodeURIComponent(t.id)}`, body)
      : await this.call<DfDocType>("POST", "/df/doctypes", { ...body, category: "custom" });
    this.flowsCache = null;
    return docTypeOf(saved, new Set(), await this.flows());
  }

  async deleteDocType(id: string): Promise<void> {
    await this.call("DELETE", `/df/doctypes/${encodeURIComponent(id)}`);
  }

  async suggestDocType(files: File[]): Promise<DocType> {
    const form = new FormData();
    files.forEach((x) => form.append("files", x, x.name));
    const p = await this.call<{ name: string; languages: string[]; keywords: string[]; fields: (DfFieldDef & { suggestOff?: boolean })[] }>("POST", "/df/doctypes/suggest", form);
    return docTypeOf({ id: "", name: p.name, description: "", category: "custom", languages: p.languages, keywords: p.keywords, checks: [], version: 0, createdAt: "",
      learning: { enabled: true, aliases: {} }, fields: p.fields.filter((x) => !x.suggestOff).map((x) => ({ ...x, aliases: x.aliases ?? [], columns: x.columns ?? [] })) },
    new Set(), []);
  }

  async einvoiceStandards(): Promise<EInvoiceStandard[]> {
    return this.call<EInvoiceStandard[]>("GET", "/df/einvoice-standards").catch(() => []);   // an older DocFlow: none listed
  }

  async languages(): Promise<Language[]> {
    return this.call<Language[]>("GET", "/df/languages");
  }

  async saveOfferedLanguages(codes: string[]): Promise<void> {
    await this.call("PUT", "/admin/settings", { values: { DOCAUTO_OFFERED_LANGUAGES: codes.join(",") } });
  }

  async refTables(): Promise<RefTable[]> {
    const [list, flows] = await Promise.all([
      this.call<{ id: string; name: string; columns: string[]; rowCount: number; updatedAt: string }[]>("GET", "/df/reftables"), this.flows(true),
    ]);
    return list.map((t) => {
      const usedBy = flows.filter((f) => f.rules.some((r) => r.type === "lookup" && r.params?.table === t.id)).map((f) => f.name);
      return {
        id: t.id, name: t.name, rows: t.rowCount, columns: t.columns, updated: new Date(t.updatedAt).toLocaleString(), usedBy,
        meta: usedBy.length ? `Used by ${usedBy.length} process${usedBy.length > 1 ? "es" : ""} · ${clock(t.updatedAt)}` : "Not used by any check",
      };
    });
  }

  async refTableRows(id: string, limit = 200): Promise<RefRow[]> {
    return (await this.call<{ rows: RefRow[] }>("GET", `/df/reftables/${encodeURIComponent(id)}?limit=${limit}`)).rows;
  }

  async uploadRefTable(file: File, name: string, replaceId?: string, headerRow = 1): Promise<{ id: string }> {
    const form = new FormData();
    form.append("file", file, file.name);
    form.append("name", name);
    form.append("headerRow", String(headerRow));
    if (replaceId) form.append("id", replaceId);
    return this.call<{ id: string }>("POST", "/df/reftables", form);
  }

  async deleteRefTable(id: string): Promise<void> {
    await this.call("DELETE", `/df/reftables/${encodeURIComponent(id)}`);
  }

  async runTest(file: File, docTypeId: string, engineA: string, engineB: string | null): Promise<TestRun> {
    const form = new FormData();
    form.append("file", file, file.name);
    form.append("docType", docTypeId);
    form.append("engines", [engineA, engineB].filter(Boolean).join(","));
    type Run = { engine: string; ms?: number; confidence?: number | null; review?: boolean; readBy?: string[]; error?: string;
      fields?: Record<string, { value: unknown; confidence: number | null; passed: boolean; errors: string[] }> };
    const r = await this.call<{ fields: { name: string; label: string; required: boolean }[]; runs: Run[] }>("POST", "/df/test", form);
    const [a, b] = r.runs;
    const value = (run: Run | undefined, k: string) => { const v = run?.fields?.[k]?.value; return v === null || v === undefined || v === "" ? "—" : String(v); };
    const conf = (run: Run | undefined, k: string) => { const c = run?.fields?.[k]?.confidence; return run?.fields?.[k] ? (c === null || c === undefined ? "" : Math.round(c * 100) + "%") : "not found"; };
    const check = (f: { name: string; required: boolean }) => {
      const out: string[] = [];
      for (const [label, run] of [["A", a], ["B", b]] as const) {
        if (!run || run.error) continue;
        const x = run.fields?.[f.name];
        if (f.required && (!x || x.value === null || x.value === "")) out.push(`Required: missing${b ? " in " + label : ""}`);
        else if (x && !x.passed) out.push(`${x.errors[0] ?? "fails a check"}${b ? " in " + label : ""}`);
      }
      return out.join("; ") || "—";
    };
    return {
      rows: r.fields.map((f) => ({ field: f.label, key: f.name, a: value(a, f.name), aConf: conf(a, f.name), b: b ? value(b, f.name) : "", bConf: b ? conf(b, f.name) : "",
        check: check(f), differs: !!b && value(a, f.name) !== value(b, f.name) })),
      runs: r.runs.map((x) => ({ engine: x.engine, seconds: (x.ms ?? 0) / 1000, confidence: pct(x.confidence), review: !!x.review,
        readBy: (x.readBy ?? []).map((e) => ENGINE_NAMES[e] ?? e).join(", then ") || "—", error: x.error })),
    };
  }

  /* test sets: DocFlow's shapes are the screens' (docauto/testsets.py) */
  private set(id: string) { return `/df/testsets/${encodeURIComponent(id)}`; }

  async testSets(docType?: string): Promise<TestSet[]> {
    return this.call<TestSet[]>("GET", "/df/testsets" + (docType ? "?docType=" + encodeURIComponent(docType) : ""));
  }

  async testSet(id: string): Promise<TestSet> { return this.call<TestSet>("GET", this.set(id)); }

  async createTestSet(name: string, docType: string, description = ""): Promise<TestSet> {
    return this.call<TestSet>("POST", "/df/testsets", { name, docType, description });
  }

  async updateTestSet(id: string, name: string, description: string): Promise<TestSet> {
    return this.call<TestSet>("PUT", this.set(id), { name, description });
  }

  async deleteTestSet(id: string): Promise<void> { await this.call("DELETE", this.set(id)); }

  async seedTestSet(): Promise<TestSet> { return this.call<TestSet>("POST", "/df/testsets/seed?count=3&bilingual=true"); }

  async addToTestSet(setId: string, documentId: string, tags: string[] = []): Promise<TestSetItem> {
    return this.call<TestSetItem>("POST", this.set(setId) + "/documents", { documentId, tags });
  }

  async addTestItem(setId: string, file: File, expected: Record<string, unknown>, tags: string[]): Promise<TestSetItem> {
    const form = new FormData();
    form.append("file", file, file.name);
    form.append("expected", JSON.stringify(expected));
    form.append("tags", tags.join(","));
    return this.call<TestSetItem>("POST", this.set(setId) + "/items", form);
  }

  async updateTestItem(setId: string, itemId: string, expected: Record<string, unknown>, tags: string[]): Promise<TestSetItem> {
    return this.call<TestSetItem>("PUT", `${this.set(setId)}/items/${encodeURIComponent(itemId)}`, { expected, tags });
  }

  async deleteTestItem(setId: string, itemId: string): Promise<void> {
    await this.call("DELETE", `${this.set(setId)}/items/${encodeURIComponent(itemId)}`);
  }

  testItemFileUrl(setId: string, itemId: string): string {
    return `${this.base}${this.set(setId)}/items/${encodeURIComponent(itemId)}/file`;
  }

  async startSetRun(setId: string, combos: Combo[]): Promise<SetRun> {
    return this.call<SetRun>("POST", this.set(setId) + "/runs?wait=false", { combos });
  }

  async setRuns(setId: string, limit = 20): Promise<SetRun[]> {
    return this.call<SetRun[]>("GET", `${this.set(setId)}/runs?limit=${limit}`);
  }

  async setRun(setId: string, runId: string): Promise<SetRun> {
    return this.call<SetRun>("GET", `${this.set(setId)}/runs/${encodeURIComponent(runId)}`);
  }
}
