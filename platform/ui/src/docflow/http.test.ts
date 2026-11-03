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
});
