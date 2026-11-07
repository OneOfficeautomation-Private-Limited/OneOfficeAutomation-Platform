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

  it("shows Claude's models: the one that reads the fields and the one that re-reads documents for review", async () => {
    const { api } = fake({
      "/providers": { providers: [{ id: "claude", displayName: "Claude", kind: "extraction", runsLocally: false, optionPack: "tier3_ai", available: true, reason: null, allowedByPolicy: true, policyReason: null, languages: [] }] },
      "/costs": { prices: { claude: { ocr: null, extract: 0.8 } }, claudeModel: "claude-haiku-5-5", claudeEscalationModel: "claude-sonnet-5-5", cloudOptIn: true },
      "/admin/settings": { defaults: [] },
    });
    const e = await api.engines();
    expect(e.claude).toEqual({ model: "claude-haiku-5-5", escalationModel: "claude-sonnet-5-5" });
    expect(e.options[2].engines[0].does).toBe("Claude Haiku 5.5 reads the fields; Claude Sonnet 5.5 re-reads documents that would go to review");
  });

  it("maps a review: page images, where each value is, table rows and the e-invoice QR checks", async () => {
    const box = { x: 0.1, y: 0.2, w: 0.3, h: 0.04 };
    const doc = { ...row, flowId: "ap", flags: [], confidence: { total: 0.97, line_items: 0.9 }, error: null,
      fields: { total: 115, supplier_name: "شركة النور", line_items: [{ qty: 2, amount: 50 }, { qty: 1, amount: 15 }] },
      locations: {
        total: { page: 2, bbox: box, grounded: true, groundedBy: "qr" }, supplier_name: { page: null, bbox: null, grounded: false, groundedBy: null },
        line_items: { page: 1, bbox: box, grounded: true, groundedBy: "ocr", rows: [{ row: 0, page: 1, bbox: box, grounded: true, cells: { qty: { page: 1, bbox: box, grounded: true } } },
          { row: 1, page: null, bbox: null, grounded: false, cells: {} }] } },
      einvoice: { source: "zatca-qr", values: { total: 115, seller_name: "ACME" }, checks: [{ field: "total", qr: 115, read: 115, result: "match" }, { field: "supplier_name", qr: "ACME", read: null, result: "filled" }] } };
    const type = { id: "invoice", name: "Invoice", fields: [{ name: "supplier_name", type: "string", label: "Supplier", required: true, aliases: [], columns: [] },
      { name: "total", type: "currency", label: "Total / الإجمالي", required: true, aliases: [], columns: [] },
      { name: "line_items", type: "table", label: "Line items", required: false, aliases: [], columns: [{ name: "qty", type: "number", label: "Qty", required: false, aliases: [], columns: [] },
        { name: "amount", type: "currency", label: "Amount", required: false, aliases: [], columns: [] }] }], learning: { aliases: {} } };
    const { api } = fake({ "/df/documents/d1": doc, "/df/flows": [flow], "/df/doctypes": [type], "/df/review": [],
      "/documents/d1/result": { pages: [{ pageNumber: 1, width: 1240, height: 1754, lines: [] }, { pageNumber: 2, width: 1240, height: 1754, lines: [] }], provenance: { steps: [] }, confidence: 0.9 } });
    const t = await api.reviewTask("d1");
    expect(t.pages).toEqual([{ number: 1, image: "/api/docflow/documents/d1/page/1.png", width: 1240, height: 1754 },
      { number: 2, image: "/api/docflow/documents/d1/page/2.png", width: 1240, height: 1754 }]);
    expect(t.fields.find((f) => f.id === "total")).toMatchObject({ label: "Total", value: "115", place: { page: 2, bbox: box, grounded: true, groundedBy: "qr" } });
    expect(t.fields.find((f) => f.id === "supplier_name")!.place).toMatchObject({ grounded: false });
    expect(t.fields.some((f) => f.id === "line_items")).toBe(false);
    expect(t.tables[0]).toMatchObject({ id: "line_items", label: "Line items", confidence: 90, columns: [{ name: "qty", label: "Qty" }, { name: "amount", label: "Amount" }],
      rows: [{ qty: "2", amount: "50" }, { qty: "1", amount: "15" }] });
    expect(t.tables[0].rowPlaces![0]).toMatchObject({ page: 1, bbox: box, grounded: true, cells: { qty: { page: 1, grounded: true } } });
    expect(t.tables[0].rowPlaces![1].grounded).toBe(false);
    expect(t.einvoice).toEqual({ source: "zatca-qr", values: { total: "115", seller_name: "ACME" }, checks: [
      { field: "total", label: "Total", qr: "115", read: "115", result: "match" }, { field: "supplier_name", label: "Supplier", qr: "ACME", read: "—", result: "filled" }] });
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
    expect(d.settings).toMatchObject({ einvoiceFirst: true, fieldThresholds: {} });       // DocFlow's defaults when the flow has none
    d.settings.fieldConfidence = 0.9;
    d.settings.fieldThresholds = { total: 0.97 };
    d.settings.einvoiceFirst = false;
    d.checks[0].on = false;
    d.deliveries[0].target = "E:\\new.xlsx";
    await api.saveProcess(d);
    const put = JSON.parse(String(calls.find((c) => c.method === "PUT")!.body));
    expect(put.review).toEqual({ fieldConfidence: 0.9, slaHours: 24, documentConfidence: 0.7, slaWebhook: "https://x", fieldThresholds: { total: 0.97 } });
    expect(put.intake).toMatchObject({ inputFolder: flow.intake.inputFolder, einvoiceFirst: false });
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

  it("calls DocFlow's test-set routes: list by type, seed, add an approved document, upload with right values, run and poll", async () => {
    const set = { id: "ap", name: "AP", docType: "invoice", description: "", itemCount: 1, createdAt: "", updatedAt: "", lastRun: null };
    const run = { id: "r1", setId: "ap", status: "running", combos: [{ ocr: "auto", extraction: "claude" }], results: [], error: null, createdAt: "", finishedAt: null };
    const { api, calls } = fake({
      "GET /df/testsets": [set], "POST /df/testsets/seed": { ...set, items: [] }, "POST /df/testsets/ap/documents": { id: "i1" },
      "POST /df/testsets/ap/items": { id: "i2" }, "POST /df/testsets/ap/runs": run, "GET /df/testsets/ap/runs/r1": { ...run, status: "completed", rows: [] },
    });
    expect(await api.testSets("invoice")).toEqual([set]);
    expect(calls[0].url).toBe("/api/docflow/df/testsets?docType=invoice");
    await api.seedTestSet();
    expect(calls[1].url).toBe("/api/docflow/df/testsets/seed?count=3&bilingual=true");
    await api.addToTestSet("ap", "d1");
    expect(JSON.parse(String(calls[2].body))).toEqual({ documentId: "d1", tags: [] });
    await api.addTestItem("ap", new File(["%PDF"], "a.pdf"), { total: "115.00" }, ["scan"]);
    const form = calls[3].body as FormData;
    expect([form.get("expected"), form.get("tags"), (form.get("file") as File).name]).toEqual(['{"total":"115.00"}', "scan", "a.pdf"]);
    expect((await api.startSetRun("ap", run.combos)).status).toBe("running");
    expect(calls[4]).toMatchObject({ method: "POST", url: "/api/docflow/df/testsets/ap/runs?wait=false" });
    expect(JSON.parse(String(calls[4].body))).toEqual({ combos: [{ ocr: "auto", extraction: "claude" }] });
    expect((await api.setRun("ap", "r1")).status).toBe("completed");
    expect(api.testItemFileUrl("ap", "i 1")).toBe("/api/docflow/df/testsets/ap/items/i%201/file");
  });

  it("flags the fields DocFlow sends to review, with its reason, and says why the whole document needs a person", async () => {
    const doc = { ...row, flowId: "ap", confidence: { subtotal: 0.58, supplier_name: 0.72, total: 0.99, line_items: 0.8 }, error: null,
      flags: [{ rule: "Large invoice", type: "threshold", action: "review", message: "total 11,500.00 > 10,000.00", field: "total" }],
      fields: { subtotal: 10000, supplier_name: "ACME Trading Co. LLC", total: 11500, po_number: null, line_items: [{ amount: 8000 }] },
      reviewReasons: [
        { field: "total", reason: "rule", rule: "Large invoice", message: "total 11,500.00 > 10,000.00" },
        { field: null, reason: "check_failed", message: "Check failed: abs(subtotal + vat_amount - total) < 0.05", fields: ["subtotal", "total", "vat_amount"] },
        { field: "subtotal", reason: "low_confidence", message: "Confidence 58% is under the 85% needed", threshold: 0.85, confidence: 0.58 },
        { field: "supplier_name", reason: "low_confidence", message: "Confidence 72% is under the 85% needed", threshold: 0.85, confidence: 0.72 },
        { field: "po_number", reason: "required_missing", message: "Required, but not found on the document" },
        { field: "line_items", reason: "low_confidence", message: "Confidence 80% is under the 85% needed", threshold: 0.85, confidence: 0.8 },
        { field: "supplier_code", reason: "validation", message: "Not in the supplier list" },
        { field: null, reason: "document_confidence", message: "Document confidence 74% is under the 80% needed", threshold: 0.8, confidence: 0.74 }] };
    const f = (name: string, type = "string", columns: unknown[] = []) => ({ name, type, label: name, required: false, aliases: [], columns });
    const type = { id: "invoice", name: "Invoice", fields: [f("supplier_name"), f("subtotal", "currency"), f("total", "currency"), f("po_number"),
      f("line_items", "table", [f("amount", "currency")])], learning: { aliases: {} } };
    const { api } = fake({ "/df/documents/d1": doc, "/df/flows": [flow], "/df/doctypes": [type], "/df/review": [] });
    const t = await api.reviewTask("d1");
    const field = (id: string) => t.fields.find((x) => x.id === id)!;
    expect(field("subtotal").flagged).toEqual({ reason: "Confidence 58% is under the 85% needed", kind: "low_confidence" });
    expect(field("supplier_name").flagged).toEqual({ reason: "Confidence 72% is under the 85% needed", kind: "low_confidence" });
    expect(field("po_number").flagged).toEqual({ reason: "Required, but not found on the document", kind: "required_missing" });
    expect(field("total").flagged).toEqual({ reason: "total 11,500.00 > 10,000.00", kind: "rule" });     // the rule's flag, once
    expect(t.tables[0].flagged).toEqual({ reason: "Confidence 80% is under the 85% needed", kind: "low_confidence" });
    expect(t.documentReasons).toEqual(["Check failed: abs(subtotal + vat_amount - total) < 0.05", "supplier_code: Not in the supplier list",
      "Document confidence 74% is under the 80% needed"]);
    expect(t.checks).toEqual([{ text: "Large invoice", passed: false, detail: "total 11,500.00 > 10,000.00" },
      { text: "abs(subtotal + vat_amount - total) < 0.05", passed: false, detail: "check of the document type" }]);

    // once reviewed, the reasons are history: nothing is flagged by them
    const done = fake({ "/df/documents/d1": { ...doc, status: "validated", flags: [] }, "/df/flows": [flow], "/df/doctypes": [type], "/df/review": [] });
    const d = await done.api.reviewTask("d1");
    expect(d.fields.some((x) => x.flagged)).toBe(false);
    expect(d.documentReasons).toEqual([]);
    expect(d.checks).toEqual([{ text: "Every check", passed: true, detail: "nothing to fix" }]);
  });

  it("gives a review task its document type id, for Add to test set", async () => {
    const doc = { ...row, flowId: "ap", status: "validated", validated_by: "Sara", flags: [], fields: { total: 115 }, confidence: { total: 0.9 }, error: null };
    const { api } = fake({ "/df/documents/d1": doc, "/df/flows": [flow], "/df/doctypes": [], "/df/review": [] });
    expect(await api.reviewTask("d1")).toMatchObject({ docTypeId: "invoice", status: "done", reviewedBy: "Sara" });
  });

  it("maps every process's set-up and activity for the Overview table in three calls", async () => {
    const inbox = { ...flow, id: "smart-inbox", name: "One Inbox", isInbox: true, docTypes: [], docTypeNames: ["Invoice", "Receipt"], classify: { mode: "single", split: true },
      engine: { ...flow.engine, extractionProvider: "claude" }, rules: [], deliveries: [], intake: { inputFolder: "", pollSeconds: 30 }, stats: { ...stats, thisMonth: undefined } };
    const { api, calls } = fake({ "/df/flows": [{ ...flow, stats: { ...stats, thisMonth: 37 } }, inbox], "/df/flows/all/documents": [row],
      "/df/review": [{ flowId: "ap", overdue: true }, { flowId: "ap", overdue: false }] });
    const [ap, one] = await api.processTable();
    expect(calls.map((c) => c.url.replace("/api/docflow", "").split("?")[0])).toEqual(["/df/flows", "/df/flows/all/documents", "/df/review"]);
    expect(ap).toMatchObject({ id: "ap", isInbox: false, docTypes: ["Invoice"], classifyMode: "single", extraction: "Default engine", checksOn: 1, checks: 1,
      fieldConfidence: 0.85, slaHours: 24, month: 37, overdue: 1, review: 2, failed: 1, lastAt: row.created_at, deliversTo: ["Excel register"], sources: ["upload", "folder"] });
    expect(one).toMatchObject({ isInbox: true, docTypes: [], docType: "Any", classifyMode: "auto", extraction: "Claude (Anthropic)", month: 4, overdue: 0, lastAt: null });
  });

  it("lists the languages DocFlow's engines read, and saves the offered ones as a DocFlow setting", async () => {
    const langs = [{ code: "en", name: "English", engines: ["tesseract"], installed: true, offered: true }, { code: "bn", name: "Bengali", engines: [], installed: false, offered: false }];
    const { api, calls } = fake({ "/df/languages": langs, "PUT /admin/settings": {} });
    expect(await api.languages()).toEqual(langs);
    await api.saveOfferedLanguages(["en", "ar", "bn"]);
    expect(calls[1].method).toBe("PUT");
    expect(JSON.parse(String(calls[1].body))).toEqual({ values: { DOCAUTO_OFFERED_LANGUAGES: "en,ar,bn" } });
  });

  it("tells the dashboard whether cloud opt-in is on", async () => {
    const { api } = fake({ "/df/overview": { totals: stats, review: 2, overdue: 0, deliveryFailures: 0 }, "/costs": { prices: {}, claudeModel: "", cloudOptIn: true }, "/usage": [] });
    expect((await api.overview()).cloudOptIn).toBe(true);
  });
});
