import {
  INBOX_ID, RULE_KIND, ruleTest, stagesOf,
  type Check, type Delivery, type DeliveryType, type DocFlowApi, type DocStatus, type DocType, type DocTypeField, type DocumentRow, type Engine,
  type EInvoice, type EmailInbox, type EngineField, type EngineOption, type InboxStatus, type Language, type NewProcess, type Overview, type Place, type ProcessDesign,
  type ProcessRow, type ProcessSummary, type RefTable,
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
interface DfFieldDef {
  name: string; type: string; label?: string | null; required: boolean; regex?: string | null; format?: string | null; aliases: string[]; columns: DfFieldDef[];
  fallback?: string | null;
}
interface DfDocType {
  id: string; name: string; description: string; category: string; languages: string[]; keywords: string[]; fields: DfFieldDef[];
  checks: string[]; learning: { enabled: boolean; aliases: Record<string, string[]> }; version: number; createdAt: string;
}
interface DfRule { id: string; type: RuleType; name: string; enabled: boolean; action: "review" | "reject" | "warn"; docTypes?: string[]; params: Record<string, unknown> }
interface DfDelivery { id: string; type: DeliveryType; name: string; enabled: boolean; on: string[]; params: Record<string, unknown> }
interface DfFlow {
  id: string; name: string; description: string; folder?: string; docTypes: string[]; status: "draft" | "live"; isInbox: boolean; version: number;
  classify: { mode: "single" | "auto"; split: boolean; minScore?: number }; engine: { option: string; ocrProvider: string; extractionProvider: string };
  review: { fieldConfidence: number; slaHours: number; fieldThresholds?: Record<string, number> }; rules: DfRule[];
  deliveries: DfDelivery[];
  intake: { inputFolder: string; pollSeconds: number; email?: Record<string, unknown>; einvoiceFirst?: boolean }; updatedAt: string;
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
interface DfEInvoice { source: string; values: Record<string, unknown>; checks?: { field: string; qr: unknown; read: unknown; result: "match" | "mismatch" | "differs" | "filled" }[] }
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
  allowedByPolicy: boolean; policyReason: string | null; languages: string[];
}
interface DfSetting { key: string; value: string }
interface DfCosts {
  prices: Record<string, { ocr: number | null; extract: number | null }>; claudeModel: string; claudeEscalationModel?: string | null; cloudOptIn: boolean;
}

/* ------------------------------------------------------------------ helpers */

const ENGINE_NAMES: Record<string, string> = {
  tesseract: "Tesseract", paddleocr: "PaddleOCR", "pdf-text": "PDF text layer", "builtin-rules": "Rules extractor", "azure-di": "Azure Document Intelligence",
  "google-docai": "Google Document AI", "aws-textract": "AWS Textract", abbyy: "ABBYY", claude: "Claude (Anthropic)", "llm-cloud": "Cloud AI API", "llm-local": "Self-hosted model",
};
const OPTIONS: Omit<EngineOption, "engines">[] = [
  { id: "onecore", name: "OneCore", description: "Open source, runs on this server, free", color: "#2f6fde" },
  { id: "oneconnect", name: "OneConnect", description: "Cloud OCR, for weak pages", color: "#8b6ae8" },
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
const FIELD_TYPES: Record<string, DocTypeField["type"]> = { string: "Text", date: "Date", currency: "Amount", number: "Number", table: "Table", enum: "Choice" };
const fieldType = (t: string): DocTypeField["type"] => FIELD_TYPES[t] ?? "Text";
const dfFieldType = (t: DocTypeField["type"]) => Object.keys(FIELD_TYPES).find((k) => FIELD_TYPES[k] === t) ?? "string";

/** A DocFlow document type as the screens show it. */
function docTypeOf(t: DfDocType, builtIn: Set<string | undefined>, flows: DfFlow[]): DocType {
  const usedBy = flows.filter((fl) => fl.docTypes.includes(t.id)).map((fl) => fl.name);
  const origin = builtIn.has(t.id) ? "built-in" as const : "made-here" as const;
  return {
    id: t.id, name: t.name, origin, usedBy, keywords: t.keywords, languages: t.languages,
    meta: `${origin === "built-in" ? "Built in" : "Made here"} · ${usedBy.length ? usedBy.length + " process" + (usedBy.length > 1 ? "es" : "") : "not used"}`,
    fields: t.fields.map((x) => ({
      key: x.name, label: (x.label ?? x.name).split(" / ")[0], aliases: [...((x.label ?? "").split(" / ").slice(1)), ...x.aliases],
      type: fieldType(x.type), required: x.required, regex: x.regex ?? "", learned: t.learning?.aliases?.[x.name] ?? [],
    })),
  };
}

/** Where a delivery points, by kind: the bot to start, the webhook address, or the folder / Excel path. */
const targetOf = (d: DfDelivery) => String((d.type === "start_bot" ? d.params.bot : d.type === "webhook" ? d.params.url : d.params.path) ?? "");
const paramsOf = (d: Delivery, old?: DfDelivery): Record<string, unknown> => {
  const keep = { ...(old?.params ?? {}) };
  if (d.type === "start_bot") return { ...keep, bot: d.target, pool: d.pool ?? "" };
  if (d.type === "webhook") return { ...keep, url: d.target };
  return { ...keep, path: d.target };
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

  async overview(): Promise<Overview> {
    const [o, costs, usage] = await Promise.all([
      this.call<{ totals: Stats; review: number; overdue: number; deliveryFailures: number; pagesThisMonth?: number; creditsThisMonth?: number }>("GET", "/df/overview"),
      this.call<DfCosts>("GET", "/costs"),
      this.call<{ k: string; pages: number | null; credits: number | null }[]>("GET", "/usage?groupBy=provider_id").catch(() => []),
    ]);
    const t = o.totals;
    const todayKey = new Date().toISOString().slice(0, 10);
    const today = t.daily.find((d) => d.day === todayKey);
    const ocr = usage.filter((u) => (u.pages ?? 0) > 0);
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
      days: t.daily.map((d) => ({ label: d.day.slice(8), straight: d.stp, reviewed: d.human })),
      alerts,
      readBy: ocr.map((u) => ({ label: ENGINE_NAMES[u.k] ?? u.k, share: pages ? Math.round(100 * (u.pages ?? 0) / pages) : 0 })),
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
      sources: ["upload", ...(f.intake.inputFolder ? ["folder" as const] : []), ...(f.intake.email?.enabled ? ["email" as const] : [])],
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

  async documents(processId: string): Promise<DocumentRow[]> {
    const rows = await this.call<DfDocRow[]>("GET", `/df/flows/${encodeURIComponent(processId)}/documents?limit=200&fields=true`);
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

  async folders(): Promise<string[]> {
    // the platform's own folders (the Agent repository's), with the person's access to each
    const r = await this.fetcher("/api/repo/folders", { credentials: "same-origin" });
    if (!r.ok) return [];
    const body = await r.json() as { folders: { path: string; access: string }[] };
    return body.folders.filter((f) => ["edit", "manage"].includes(f.access)).map((f) => f.path);
  }

  async createProcess(p: NewProcess): Promise<{ id: string }> {
    const f = await this.call<DfFlow>("POST", "/df/flows", {
      name: p.name, folder: p.folder, docTypes: [p.docType],
      intake: { inputFolder: p.watchedFolder ?? "", pollSeconds: p.pollSeconds ?? 30 },
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
      source: e.source,
      values: Object.fromEntries(Object.entries(e.values ?? {}).filter(([, v]) => v === null || typeof v !== "object").map(([k, v]) => [k, cell(v)])),
      checks: (e.checks ?? []).map((c) => ({ field: c.field, label: label(c.field), qr: text(c.qr), read: text(c.read), result: c.result })),
    };
  }

  async completeReview(documentId: string, fields: ReviewValues, decision: "approve" | "reject"): Promise<{ next?: string }> {
    const r = await this.call<{ nextDocumentId?: string | null }>("POST", `/df/documents/${encodeURIComponent(documentId)}/review`,
      { fields, action: decision === "approve" ? "submit" : "invalid" });
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
    return (s.engines[id]?.fields ?? []).map((f) => ({ key: f.key, label: f.label, type: f.type, value: String(f.value ?? ""), help: f.help, options: f.options, placeholder: f.placeholder }));
  }

  async saveEngineSettings(values: Record<string, string>): Promise<void> {
    await this.call("PUT", "/admin/settings", { values });
  }

  async engines(): Promise<{ routing: Routing; options: EngineOption[]; claude?: { model: string; escalationModel: string | null } }> {
    const [prov, costs, settings] = await Promise.all([
      this.call<{ providers: DfProvider[] }>("GET", "/providers"), this.call<DfCosts>("GET", "/costs"),
      // routing details are for people who may change engines; everyone else still sees the opt-in from /costs
      this.call<{ defaults: DfSetting[] }>("GET", "/admin/settings").catch(() => null),
    ]);
    const v = (k: string, d: string) => settings?.defaults.find((s) => s.key === k)?.value ?? d;
    const routing: Routing = {
      cloudOptIn: costs.cloudOptIn, retryBelow: Number(v("DOCAUTO_ESCALATE_THRESHOLD", "0.85")),
      localRetry: v("DOCAUTO_RETRY_LOCAL", "paddleocr"), cloudEngine: v("DOCAUTO_ESCALATE_TO", ""),
    };
    const price = (id: string, local: boolean) => {
      const p = costs.prices[id];
      const parts = [p?.ocr, p?.extract].filter((x): x is number => x !== null && x !== undefined && x > 0).map((x) => "$" + x);
      return local || !parts.length ? (local ? "Free" : "Contract") : parts.join(" · ");
    };
    const escalation = costs.claudeEscalationModel ?? null;
    const claudeDoes = `${modelName(costs.claudeModel)} reads the fields` + (escalation ? `; ${modelName(escalation)} re-reads documents that would go to review` : "");
    const engine = (p: DfProvider): Engine => ({
      id: p.id, name: ENGINE_NAMES[p.id] ?? p.displayName, does: p.id === "claude" && costs.claudeModel ? claudeDoes : p.kind === "ocr" ? "Reads pages" : "Reads fields",
      status: !p.available ? "setup" : !p.allowedByPolicy ? "optin" : "ready",
      runs: p.runsLocally ? "server" : "cloud", price: price(p.id, p.runsLocally), free: p.runsLocally,
      key: p.runsLocally ? "—" : p.available ? "Set" : "Not set",
    });
    return {
      routing,
      options: OPTIONS.map((o) => ({ ...o, engines: prov.providers.filter((p) => PACK_OPTION[p.optionPack] === o.id).map(engine) })),
      ...(costs.claudeModel ? { claude: { model: costs.claudeModel, escalationModel: escalation } } : {}),
    };
  }

  async saveRouting(r: Routing): Promise<void> {
    await this.call("PUT", "/admin/settings", { values: {
      DOCAUTO_CLOUD_OPT_IN: r.cloudOptIn, DOCAUTO_ESCALATE_THRESHOLD: String(r.retryBelow), DOCAUTO_RETRY_LOCAL: r.localRetry, DOCAUTO_ESCALATE_TO: r.cloudEngine,
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
        extraction: f.engine.extractionProvider, fieldConfidence: f.review.fieldConfidence, slaHours: f.review.slaHours,
        email: f.intake.email && Object.keys(f.intake.email).length ? (f.intake.email as unknown as EmailInbox) : null,
        einvoiceFirst: f.intake.einvoiceFirst ?? true, fieldThresholds: { ...(f.review.fieldThresholds ?? {}) },
      },
      deliveries: f.deliveries.map((d) => ({ id: d.id, type: d.type, name: d.name, enabled: d.enabled, on: d.on, target: targetOf(d),
        ...(d.type === "start_bot" ? { pool: String(d.params.pool ?? "") } : {}) })),
    };
    const seen = new Set<string>();
    const fields = (f.isInbox ? types : mine).flatMap((t) => t.fields).filter((x) => x.type !== "table" && !seen.has(x.name) && seen.add(x.name))
      .map((x) => ({ name: x.name, label: (x.label ?? x.name).split(" / ")[0] }));
    return { id: f.id, name: f.name, state: f.status, version: f.version, folder: f.folder ?? "", ...base, fields,
      stages: stagesOf(base, (e) => ENGINE_NAMES[e] ?? e) };
  }

  async saveProcess(d: ProcessDesign): Promise<ProcessDesign> {
    // DocFlow replaces whole sections, so each one starts from what it holds now (keeps settings these screens do not show)
    const cur = await this.call<DfFlow>("GET", `/df/flows/${encodeURIComponent(d.id)}`);
    const s = d.settings;
    const saved = await this.call<DfFlow>("PUT", `/df/flows/${encodeURIComponent(d.id)}`, {
      ...(cur.isInbox || !d.docTypeIds.length ? {} : { docTypes: d.docTypeIds }),
      intake: { ...cur.intake, inputFolder: s.inputFolder, pollSeconds: s.pollSeconds, email: s.email ?? {}, einvoiceFirst: s.einvoiceFirst },
      classify: { ...cur.classify, mode: s.classifyMode, split: s.split },
      engine: { ...cur.engine, extractionProvider: s.extraction },
      review: { ...cur.review, fieldConfidence: s.fieldConfidence, slaHours: s.slaHours, fieldThresholds: s.fieldThresholds },
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
      return { ...(old ?? {}), name: x.key, label: x.label, aliases: x.aliases, type: dfFieldType(x.type), required: x.required, regex: x.regex || null };
    });
    const learning = cur ? { ...cur.learning, aliases: Object.fromEntries(t.fields.map((x) => [x.key, x.learned])) } : undefined;
    const body = { name: t.name, keywords: t.keywords, languages: t.languages, fields, ...(learning ? { learning } : {}) };
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

  async uploadRefTable(file: File, name: string): Promise<{ id: string }> {
    const form = new FormData();
    form.append("file", file, file.name);
    form.append("name", name);
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
      rows: r.fields.map((f) => ({ field: f.label, a: value(a, f.name), aConf: conf(a, f.name), b: b ? value(b, f.name) : "", bConf: b ? conf(b, f.name) : "",
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
