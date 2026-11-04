import { describe, expect, it } from "vitest";
import { HttpDocFlowApi } from "./http";

/** The real client against a fake DocFlow (through the Command Center's /api/docflow/). */
type Call = { method: string; url: string; body?: BodyInit | null; headers: Record<string, string> };

function fake(routes: Record<string, unknown>) {
  const calls: Call[] = [];
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ method: init?.method ?? "GET", url, body: init?.body, headers: (init?.headers ?? {}) as Record<string, string> });
    const key = Object.keys(routes).find((k) => url.replace("/api/docflow", "").split("?")[0] === k.split(" ").pop() && (k.includes(" ") ? k.startsWith(init?.method ?? "GET") : true));
    if (!key) return new Response(JSON.stringify({ code: "NOT_FOUND", message: "no route " + url }), { status: 404 });
    const v = routes[key];
    return v instanceof Response ? v : new Response(JSON.stringify(v), { status: 200 });
  }) as typeof fetch;
  return { api: new HttpDocFlowApi("/api/docflow", fetcher), calls };
}

const today = new Date().toISOString().slice(0, 10);
const stats = { byStatus: { queued: 1, processing: 0, success: 6, validation: 2, validated: 1, invalid: 0, failed: 1, split: 0 }, total: 11, processed: 9,
  stpRate: 0.6667, pages: 14, credits: 0, daily: [{ day: today, total: 4, stp: 3, human: 1, failed: 0 }] };
const flow = { id: "ap", name: "AP invoices", description: "", folder: "Finance / Accounts payable", docTypes: ["invoice"], status: "live", isInbox: false, version: 2,
  classify: { mode: "single", split: false }, engine: { option: "tier1_opensource", ocrProvider: "auto", extractionProvider: "auto" },
  review: { fieldConfidence: 0.85, slaHours: 24 }, rules: [{ id: "r1", type: "expression", name: "Totals add up", enabled: true, action: "review", params: { expression: "subtotal + vat_amount == total" } }],
  deliveries: [{ type: "excel", name: "Excel register", enabled: true, params: {} }], intake: { inputFolder: "C:\\scan", pollSeconds: 30 }, updatedAt: "",
  stats, docTypeNames: ["Invoice"] };
const row = { id: "d1", file_name: "INV-1.pdf", created_at: new Date().toISOString(), status: "validation", instance_id: "ap", source: "folder", parent_id: null,
  doctype_id: "invoice", validated_by: null, flags: JSON.stringify([{ rule: "Totals add up", type: "expression", action: "review", message: "Totals do not add up", field: "total" }]),
  fields: { supplier_name: "ACME", invoice_number: "INV-1", total: 115, currency: "SAR" }, confidence: 0.91, pages: 2, engines: ["tesseract", "paddleocr"] };

describe("HttpDocFlowApi", () => {
  it("maps processes from flows, their counts and the latest document", async () => {
    const { api } = fake({ "/df/flows": [flow], "/df/flows/all/documents": [row] });
    const [p] = await api.processes();
    expect(p).toMatchObject({ id: "ap", name: "AP invoices", folder: "Finance / Accounts payable", docType: "Invoice", state: "live", version: 2,
      today: 4, review: 2, processing: 1, failed: 1, done: 7, straightThrough: 67, lastDocument: "INV-1.pdf", sources: ["upload", "folder"], deliversTo: ["Excel register"] });
  });

  it("maps a process's documents, with fields, engines and the first problem", async () => {
    const { api, calls } = fake({ "/df/flows/ap/documents": [row] });
    const [d] = await api.documents("ap");
    expect(calls[0].url).toContain("fields=true");
    expect(d).toMatchObject({ id: "d1", name: "INV-1.pdf", pages: "2 pages", supplier: "ACME", invoice: "INV-1", total: "115 SAR", status: "review",
      source: "folder", note: "Totals do not add up", engine: "Tesseract, then PaddleOCR", confidence: 91 });
  });

  it("uploads the files themselves to the chosen process, or to the inbox to recognise them", async () => {
    const { api, calls } = fake({ "POST /df/flows/smart-inbox/documents": { documents: [] } });
    const r = await api.addDocuments([new File(["%PDF"], "a.pdf")], "auto");
    expect(r.processId).toBe("smart-inbox");
    expect(calls[0].body).toBeInstanceOf(FormData);
    expect((calls[0].body as FormData).getAll("files")).toHaveLength(1);
    expect(calls[0].headers["X-OOA-Request"]).toBe("1");
    expect(calls[0].headers["Content-Type"]).toBeUndefined();   // the browser sets the multipart boundary
  });

  it("sends a review as DocFlow's submit or invalid", async () => {
    const { api, calls } = fake({ "POST /df/documents/d1/review": {} });
    await api.completeReview("d1", { total: "115.00" }, "reject");
    expect(JSON.parse(String(calls[0].body))).toEqual({ fields: { total: "115.00" }, action: "invalid" });
  });

  it("saves routing as the tenant's DocFlow settings", async () => {
    const { api, calls } = fake({ "PUT /admin/settings": {} });
    await api.saveRouting({ cloudOptIn: true, retryBelow: 0.8, localRetry: "paddleocr", cloudEngine: "azure-di" });
    expect(JSON.parse(String(calls[0].body)).values).toEqual({ DOCAUTO_CLOUD_OPT_IN: true, DOCAUTO_ESCALATE_THRESHOLD: "0.8", DOCAUTO_RETRY_LOCAL: "paddleocr", DOCAUTO_ESCALATE_TO: "azure-di" });
  });

  it("shows engines to people who may not change them, from the opt-in alone", async () => {
    const { api } = fake({
      "/providers": { providers: [{ id: "tesseract", displayName: "Tesseract", kind: "ocr", runsLocally: true, optionPack: "tier1_opensource", available: true, reason: null, allowedByPolicy: true, policyReason: null, languages: [] },
        { id: "azure-di", displayName: "Azure", kind: "ocr", runsLocally: false, optionPack: "tier2_cloud", available: true, reason: null, allowedByPolicy: false, policyReason: "opt-in", languages: [] }] },
      "/costs": { prices: { "azure-di": { ocr: 1.5, extract: 10 } }, claudeModel: "claude-sonnet-5-5", cloudOptIn: false },
      "/admin/settings": new Response(JSON.stringify({ error: { code: "CR.FORBIDDEN", message: "not allowed" } }), { status: 403 }),
    });
    const e = await api.engines();
    expect(e.routing).toEqual({ cloudOptIn: false, retryBelow: 0.85, localRetry: "paddleocr", cloudEngine: "" });
    expect(e.options[0].engines[0]).toMatchObject({ id: "tesseract", status: "ready", price: "Free" });
    expect(e.options[1].engines[0]).toMatchObject({ id: "azure-di", status: "optin", runs: "cloud", price: "$1.5 · $10" });
  });

  it("reports the Command Center's error message", async () => {
    const { api } = fake({ "/df/overview": new Response(JSON.stringify({ error: { code: "DOCS.UNAVAILABLE", message: "The Document Automation service is not reachable" } }), { status: 502 }) });
    await expect(api.overview()).rejects.toThrow("not reachable");
  });

  it("saves a process section by section, keeping what the screens do not show", async () => {
    const stored = { ...flow, review: { ...flow.review, documentConfidence: 0.7, slaWebhook: "https://x" }, deliveries: [{ id: "d1", type: "excel", name: "Excel register", enabled: true, on: ["success"], params: { path: "D:\\r.xlsx", sheet: "AP" } }] };
    const { api, calls } = fake({ "PUT /df/flows/ap": stored, "/df/flows/ap": stored, "/df/doctypes": [{ id: "invoice", name: "Invoice", fields: [], learning: { aliases: {} } }] });
    const d = await api.processDesign("ap");
    expect(d.deliveries[0]).toMatchObject({ type: "excel", target: "D:\\r.xlsx" });
    d.settings.fieldConfidence = 0.9;
    d.checks[0].on = false;
    d.deliveries[0].target = "E:\\new.xlsx";
    await api.saveProcess(d);
    const put = JSON.parse(String(calls.find((c) => c.method === "PUT")!.body));
    expect(put.review).toEqual({ fieldConfidence: 0.9, slaHours: 24, documentConfidence: 0.7, slaWebhook: "https://x" });
    expect(put.rules[0]).toMatchObject({ id: "r1", type: "expression", enabled: false, action: "review" });
    expect(put.deliveries[0].params).toEqual({ path: "E:\\new.xlsx", sheet: "AP" });
  });

  it("uploads a reference table and changes a document's type", async () => {
    const { api, calls } = fake({ "POST /df/reftables": { id: "suppliers" }, "POST /df/documents/d1/reclassify": { id: "d2", status: "validation" } });
    expect(await api.uploadRefTable(new File(["a,b"], "Suppliers.csv"), "Suppliers")).toEqual({ id: "suppliers" });
    expect((calls[0].body as FormData).get("name")).toBe("Suppliers");
    expect(await api.changeDocType("d1", "receipt")).toEqual({ documentId: "d2", needsReview: true });
    expect(JSON.parse(String(calls[1].body))).toEqual({ docType: "receipt" });
    expect(api.exportUrl("ap", "xlsx")).toBe("/api/docflow/df/flows/ap/export?format=xlsx");
  });

  it("saves a document type, keeping what DocFlow knows that the screens do not edit", async () => {
    const stored = { id: "invoice", name: "Invoice", description: "d", category: "invoice", languages: ["en", "ar"], keywords: ["Invoice"], checks: ["subtotal + vat == total"], version: 3, createdAt: "",
      learning: { enabled: true, aliases: { total: ["Amount due"] } },
      fields: [{ name: "total", type: "currency", label: "Total / الإجمالي", required: true, aliases: ["Grand Total"], columns: [], format: "0.00" },
        { name: "line_items", type: "table", label: "Line items", required: false, aliases: [], columns: [{ name: "qty", type: "number", required: false, aliases: [], columns: [] }] }] };
    const { api, calls } = fake({ "PUT /df/doctypes/invoice": stored, "/df/doctypes/invoice": stored, "/df/flows": [] });
    const shown = { id: "invoice", name: "Invoice", origin: "built-in" as const, meta: "", keywords: ["Invoice", "Tax invoice"], languages: ["en", "ar"], usedBy: [],
      fields: [{ key: "total", label: "Total", aliases: ["الإجمالي", "Grand Total"], type: "Amount" as const, required: true, regex: "", learned: [] },
        { key: "line_items", label: "Line items", aliases: [], type: "Table" as const, required: false, regex: "", learned: [] }] };
    await api.saveDocType(shown);
    const put = JSON.parse(String(calls.find((c) => c.method === "PUT")!.body));
    expect(put.keywords).toEqual(["Invoice", "Tax invoice"]);
    expect(put.fields[0]).toMatchObject({ name: "total", type: "currency", label: "Total", aliases: ["الإجمالي", "Grand Total"], format: "0.00" });
    expect(put.fields[1].columns).toHaveLength(1);
    expect(put.learning).toEqual({ enabled: true, aliases: { total: [], line_items: [] } });   // "Amount due" was forgotten on the screen
  });

  it("maps a Test lab run: values, confidence, what fails, and engines that could not run", async () => {
    const { api, calls } = fake({ "POST /df/test": {
      fields: [{ name: "invoice_number", label: "Invoice number", required: true }, { name: "total", label: "Total", required: true }],
      runs: [{ engine: "tesseract", ms: 600, confidence: 0.78, review: true, readBy: ["tesseract"], fields: {
        invoice_number: { value: "INV-1", confidence: 0.93, passed: true, errors: [] }, total: { value: null, confidence: null, passed: true, errors: [] } } },
        { engine: "azure-di", error: "policy: cloud opt-in is off" }] } });
    const r = await api.runTest(new File(["x"], "a.png"), "invoice", "tesseract", "azure-di");
    expect((calls[0].body as FormData).get("engines")).toBe("tesseract,azure-di");
    expect(r.rows[0]).toMatchObject({ field: "Invoice number", a: "INV-1", aConf: "93%", b: "—", differs: true });
    expect(r.rows[1].check).toBe("Required: missing in A");
    expect(r.runs[0]).toMatchObject({ seconds: 0.6, confidence: 78, review: true, readBy: "Tesseract" });
    expect(r.runs[1].error).toContain("opt-in");
  });
});
