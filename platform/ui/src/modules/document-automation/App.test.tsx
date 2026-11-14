import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { SampleApi, estimate } from "./api";
import { App, parseRoute } from "./App";

beforeEach(() => { location.hash = ""; });
afterEach(() => { cleanup(); localStorage.clear(); });

const go = async (hash: string) => { await act(async () => { location.hash = hash; window.dispatchEvent(new HashChangeEvent("hashchange")); }); };
const start = (api = new SampleApi()) => { render(<App api={api} />); return api; };
const table = (name: string) => screen.findByRole("region", { name });

describe("Document Automation", () => {
  it("sits under AI in the Command Center menu: Overview, Inbox and Processes, then the Libraries", async () => {
    start();
    const menu = await screen.findByRole("navigation", { name: "One CommandCenter" });
    expect(within(menu).getByRole("link", { name: "Document Automation" }).getAttribute("aria-current")).toBe("page");
    expect(within(menu).getByRole("link", { name: "Document Automation" }).getAttribute("href")).toBe("/documents/");
    const tabs = screen.getByRole("navigation", { name: "Document Automation" });
    expect(within(tabs).getAllByRole("group").map((g) => g.getAttribute("aria-label"))).toEqual(["Pages", "Libraries"]);
    expect(within(within(tabs).getByRole("group", { name: "Pages" })).getAllByRole("link").map((a) => a.textContent)).toEqual(["Overview", "Inbox", "Processes"]);
    expect(within(tabs).getByRole("link", { name: "Overview" }).getAttribute("aria-current")).toBe("page");
    expect(await screen.findByRole("region", { name: "Processes" })).toBeTruthy();   // Overview opens on its table
    expect(screen.getByText("Local-only: nothing leaves this server")).toBeTruthy();
  });

  it("opens the libraries from a small menu, which then names the one open", async () => {
    start();
    const libraries = within(screen.getByRole("group", { name: "Libraries" })).getByRole("button");
    expect(libraries.textContent).toBe("Libraries");
    await act(async () => { libraries.focus(); fireEvent.keyDown(libraries, { key: "ArrowDown" }); });
    const items = within(await screen.findByRole("menu")).getAllByRole("menuitem").map((i) => i.textContent);
    expect(items).toEqual(["Document typesWhat to read from each kind of document", "Reference dataLists that checks look values up in",
      "EnginesWhich engines read the pages; cloud opt-in; cost", "Test labTry a document, or compare engines on test sets"]);
    await act(async () => { fireEvent.click(screen.getByRole("menuitem", { name: /^Engines/ })); });
    await waitFor(() => expect(location.hash).toBe("#/engines"));
    window.dispatchEvent(new HashChangeEvent("hashchange"));
    await waitFor(() => expect(within(screen.getByRole("group", { name: "Libraries" })).getByRole("button").textContent).toBe("Engines"));
  });

  it("builds a process in eight numbered steps, the process chosen first, and tests it before going live", async () => {
    start(); await go("#/processes/supplier-invoices-ksa");
    const steps = await screen.findByRole("tablist", { name: "Steps" });
    expect(within(steps).getAllByRole("tab").map((t) => t.querySelector("b")!.textContent))
      .toEqual(["Select a process", "Document ingestion", "Document classification", "Data extraction", "Validation", "Human review", "Export & integration", "Testing & go-live"]);
    expect(screen.getByRole("region", { name: "Document ingestion" })).toBeTruthy();  // a process in the address: it opens on step 2
    expect(screen.queryByRole("combobox", { name: "Process" })).toBeNull();          // no process list in the header: step 1 is the one place
    fireEvent.click(within(steps).getByRole("tab", { name: /Data extraction/ }));
    expect(within(screen.getByRole("region", { name: "Data extraction" })).getByRole("link", { name: "Engines" }).getAttribute("href")).toBe("#/engines/routing");
    fireEvent.click(screen.getByRole("button", { name: "step 8, Testing & go-live" }));
    const test = screen.getByRole("region", { name: "Testing & go-live" });
    expect(await within(test).findByRole("button", { name: "Run test" })).toBeTruthy();
    fireEvent.click(within(test).getByRole("button", { name: "Go live" }));
    expect(await screen.findByText("Live · version 1")).toBeTruthy();
    expect(within(test).getByText("This process is live: new documents use this version.")).toBeTruthy();
  });

  it("chooses and edits the document type in step 3, on the same screen", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/classify");
    const step = await screen.findByRole("region", { name: "Document classification" });
    expect((within(step).getByLabelText("Document type") as HTMLSelectElement).value).toBe("supplier-invoice");
    expect(await screen.findByRole("region", { name: "Fields to read" })).toBeTruthy();      // its fields, here: no other page
    expect(location.hash).toBe("#/processes/supplier-invoices-ksa/classify");
    fireEvent.change(within(step).getByLabelText("Document type"), { target: { value: "receipt" } });
    expect(screen.getByText("Unsaved changes")).toBeTruthy();
    expect(screen.getByRole("region", { name: "Receipt" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByRole("button", { name: "Saved" });
    expect(await api.processDesign("supplier-invoices-ksa")).toMatchObject({ docTypeIds: ["receipt"], docType: "Receipt" });
  });

  it("makes a new document type in step 3, and the process reads it", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/classify");
    fireEvent.click(await screen.findByRole("button", { name: "New document type" }));
    const naming = await screen.findByRole("dialog", { name: "New document type" });
    fireEvent.change(within(naming).getByLabelText("Name"), { target: { value: "Customs declaration" } });
    fireEvent.click(within(naming).getByRole("button", { name: "Next: add fields" }));
    const field = await screen.findByRole("dialog", { name: "Add field" });
    fireEvent.change(within(field).getByLabelText("Name"), { target: { value: "Declaration number" } });
    fireEvent.click(within(field).getByRole("button", { name: "Save" }));
    fireEvent.click(screen.getByRole("button", { name: "Create type" }));
    await waitFor(() => expect((screen.getByLabelText("Document type") as HTMLSelectElement).value).toBe("customs-declaration"));
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByRole("button", { name: "Saved" });
    expect(await api.processDesign("supplier-invoices-ksa")).toMatchObject({ docTypeIds: ["customs-declaration"], fields: [{ name: "declaration_number", label: "Declaration number" }] });
  });

  it("reads routes from the hash", () => {
    expect(parseRoute("#/process/ap-invoices")).toEqual({ tab: "inbox", process: "ap-invoices" });
    expect(parseRoute("#/review/scan_0457")).toEqual({ tab: "review", document: "scan_0457" });
    expect(parseRoute("#/engines")).toEqual({ tab: "engines" });
    expect(parseRoute("#/overview/dashboard")).toEqual({ tab: "overview", section: "dashboard" });
    expect(parseRoute("#/processes/ap-invoices/select")).toEqual({ tab: "processes", design: "ap-invoices", stage: "select" });
    expect(parseRoute("#/engines/catalog/onemind")).toEqual({ tab: "engines", section: "catalog", item: "onemind" });
    expect(parseRoute("#/testlab/sets/ap-reviewed")).toEqual({ tab: "testlab", section: "sets", item: "ap-reviewed" });
    expect(parseRoute("#/nowhere")).toEqual({ tab: "overview" });
  });

  it("lists processes in the Inbox and filters them; Clear filters brings them back", async () => {
    start(); await go("#/inbox");
    const region = await table("Processes");
    expect(within(region).getAllByRole("row")).toHaveLength(5);   // header + 4 processes
    fireEvent.change(within(region).getByLabelText("Status"), { target: { value: "failed" } });
    expect(within(region).getAllByRole("row")).toHaveLength(2);
    expect(within(region).getByRole("link", { name: "One Inbox" })).toBeTruthy();
    fireEvent.change(within(region).getByLabelText("Comes in from"), { target: { value: "email" } });
    expect(within(region).getByText("No process matches these filters.")).toBeTruthy();
    fireEvent.click(within(region).getAllByRole("button", { name: "Clear filters" })[0]);
    expect(within(region).getAllByRole("row")).toHaveLength(5);
  });

  it("adds documents on the process page, not on the Inbox's main screen", async () => {
    const api = start(); await go("#/inbox");
    await table("Processes");
    expect(screen.queryByRole("button", { name: "Add documents" })).toBeNull();
    expect(screen.queryByText(/Drop files here/)).toBeNull();
    expect(screen.getByRole("button", { name: "New process" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Add them in One Inbox" }).getAttribute("href")).toBe("#/process/smart-inbox");

    await go("#/process/expense-claims");
    expect(await screen.findByRole("button", { name: "Add documents" })).toBeTruthy();
    expect(screen.getByText(/They go to Expense claims/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Choose files for Expense claims"), { target: { files: [new File(["x"], "INV-NEW-1.pdf")] } });
    const files = screen.getByLabelText("Files to add");
    expect(within(files).getByText("Filed in Finance / Expenses")).toBeTruthy();       // the process's own folder
    fireEvent.click(within(files).getByRole("button", { name: "Start processing" }));
    expect(await within(await table("Documents in Expense claims")).findByRole("link", { name: "INV-NEW-1.pdf" })).toBeTruthy();
    expect((await api.documents("expense-claims")).find((d) => d.name === "INV-NEW-1.pdf")?.status).toBe("processing");
  });

  it("recognises documents of mixed types on the One Inbox page", async () => {
    const api = start(); await go("#/process/smart-inbox");
    expect(await screen.findByText(/Each document is recognised and sent to its process/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Choose files for One Inbox"), { target: { files: [new File(["x"], "mixed.pdf")] } });
    fireEvent.click(screen.getByRole("button", { name: "Start processing" }));
    await waitFor(async () => expect((await api.documents("smart-inbox")).some((d) => d.name === "mixed.pdf")).toBe(true));
  });

  it("creates a process with the ways documents come in, then opens the builder", async () => {
    const api = start(); await go("#/inbox");
    fireEvent.click(await screen.findByRole("button", { name: "New process" }));
    const dialog = await screen.findByRole("dialog", { name: "New process" });
    const create = within(dialog).getByRole("button", { name: "Create process" }) as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText("Process name"), { target: { value: "Supplier invoices KSA" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Watched folder/ }));
    expect(create.disabled).toBe(true);                                         // a watched folder needs its path
    fireEvent.change(within(dialog).getByLabelText("Folder path"), { target: { value: "\\\\scan01\\ksa" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Email inbox/ }));
    fireEvent.click(create);
    await waitFor(() => expect(location.hash).toBe("#/processes/supplier-invoices-ksa/intake"));   // email ticked: set the inbox up next
    const made = (await api.processes()).find((p) => p.name === "Supplier invoices KSA")!;
    expect(made.sources).toEqual(["upload", "folder", "email"]);
    expect(made.state).toBe("draft");
  });

  it("shows every document of a process, with filters on how it came in, engine and confidence", async () => {
    start(); await go("#/process/ap-invoices");
    const region = await table("Documents in AP invoices");
    expect(within(region).getByText("8 of 8 documents")).toBeTruthy();
    fireEvent.change(within(region).getByLabelText("Read by"), { target: { value: "paddle" } });
    expect(within(region).getByText("2 of 8 documents")).toBeTruthy();
    fireEvent.change(within(region).getByLabelText("Confidence"), { target: { value: "under85" } });
    expect(within(region).getByRole("link", { name: "scan_0457.jpg" }).getAttribute("href")).toBe("#/review/scan_0457");
    expect(within(region).getByText("1 of 8 documents")).toBeTruthy();
  });

  it("reviews a flagged document: the suggestion fixes both checks, Approve records it", async () => {
    const api = start(); await go("#/review/scan_0457");
    const fields = await screen.findByRole("region", { name: "Fields" });
    expect(within(fields).getByText("1 field needs you")).toBeTruthy();
    expect(within(fields).getAllByText("failed")).toHaveLength(2);
    fireEvent.click(within(fields).getByRole("button", { name: "Use 6,257.13" }));
    expect((within(fields).getByLabelText(/VAT amount/) as HTMLInputElement).value).toBe("6,257.13");
    expect(within(fields).queryByText("failed")).toBeNull();
    expect(within(fields).getByText("All flagged fields checked")).toBeTruthy();
    fireEvent.click(within(fields).getByRole("button", { name: /Approve and next/ }));
    await waitFor(() => expect(api.reviews).toHaveLength(1));
    expect(api.reviews[0]).toMatchObject({ documentId: "scan_0457", decision: "approve", fields: { vat_amount: "6,257.13" } });
    expect(api.reviews[0].fields.line_items).toBeUndefined();                   // an unchanged table is not sent back as a correction
    const menu = screen.queryByRole("navigation", { name: "One CommandCenter" });
    expect(menu).toBeTruthy();
  });

  it("moves to the previous and next review task; Previous is off on the first, Next on the last", async () => {
    start(); await go("#/review/scan_0457");
    await screen.findByRole("region", { name: "Fields" });
    expect(screen.getByText("Task 1 of 3")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Previous task" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Next task" }));
    expect(location.hash).toBe("#/review/nl_7710");
    await go("#/review/nl_7710");
    await waitFor(() => expect(screen.getByText("Task 2 of 3")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Next task" }));
    expect(location.hash).toBe("#/review/batch_0930_3");
    await go("#/review/batch_0930_3");
    await waitFor(() => expect(screen.getByText("Task 3 of 3")).toBeTruthy());
    expect((screen.getByRole("button", { name: "Next task" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Previous task" }));
    expect(location.hash).toBe("#/review/nl_7710");
  });

  it("says why the document needs review: the fields to check first with their reason, the rest after, the document's own reasons on top", async () => {
    const api = new SampleApi();
    const sample = await api.reviewTask("scan_0457");
    api.reviewTask = async () => ({ ...sample,
      fields: sample.fields.map((f) => (f.id === "subtotal" ? { ...f, confidence: 58, flagged: { reason: "Confidence 58% is under the 85% needed", kind: "low_confidence" } }
        : f.id === "supplier" ? { ...f, value: "", confidence: 0, flagged: { reason: "Required, but not found on the document", kind: "required_missing" } } : f)),
      documentReasons: ["Document confidence 74% is under the 80% needed"] });
    start(api); await go("#/review/scan_0457");
    const fields = await screen.findByRole("region", { name: "Fields" });
    expect(within(fields).getByText("3 fields need you")).toBeTruthy();
    const why = within(fields).getByRole("note", { name: "Why this document needs review" });
    expect(within(why).getByText("Document confidence 74% is under the 80% needed")).toBeTruthy();
    expect(within(fields).getByText("Confidence 58% is under the 85% needed")).toBeTruthy();
    expect(within(fields).getByText("Required, but not found on the document")).toBeTruthy();
    expect(within(fields).getByText("Not read")).toBeTruthy();
    expect(within(fields).getByText("Read at 58%")).toBeTruthy();
    // the fields to check come first, under their heading; Subtotal is no longer "checked, no action needed"
    const text = fields.textContent!;
    const needs = text.indexOf("NEEDS YOUR CHECK"), fine = text.indexOf("CHECKED, NO ACTION NEEDED");
    expect(needs).toBeGreaterThanOrEqual(0);
    for (const label of ["VAT amount", "Supplier", "Subtotal"]) {
      const at = text.indexOf(label, needs);
      expect(at > needs && at < fine).toBe(true);
    }
    expect(within(fields).getByLabelText(/^Subtotal/).closest(".df-flag")).toBeTruthy();
    expect(within(fields).getByLabelText(/^Invoice number/).closest(".df-field-row")).toBeTruthy();
  });

  it("shows the page itself, with pages, zoom and a box over each value; a field and its box find each other", async () => {
    start(); await go("#/review/scan_0457");
    const page = await screen.findByRole("region", { name: "Document page" });
    const fields = screen.getByRole("region", { name: "Fields" });
    expect(within(page).getByRole("img", { name: "Page 1 of scan_0457.jpg" }).getAttribute("loading")).toBe("lazy");
    expect(within(page).getByText("Page 1 of 2")).toBeTruthy();
    expect((within(page).getByRole("button", { name: "Previous page" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(page).getByRole("button", { name: "Zoom in" }));
    expect(within(page).getByText("110%")).toBeTruthy();
    expect(within(page).getByRole("button", { name: "Invoice number on the page" })).toBeTruthy();
    expect(within(page).queryByRole("button", { name: "Total on the page" })).toBeNull();     // on page 2

    fireEvent.focus(within(fields).getByLabelText("Total"));                       // a field on page 2: the page turns to it
    expect(within(page).getByText("Page 2 of 2")).toBeTruthy();
    expect(within(page).getByRole("button", { name: "Total on the page" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(within(page).getByRole("button", { name: "Previous page" }));
    expect(within(page).getByText("Page 1 of 2")).toBeTruthy();
    fireEvent.click(within(page).getByRole("button", { name: "Invoice number on the page" }));   // a box: its field is chosen
    expect(document.activeElement).toBe(within(fields).getByLabelText("Invoice number"));
    expect(within(page).getByRole("button", { name: "Invoice number on the page" }).getAttribute("aria-pressed")).toBe("true");

    expect(within(fields).getByText("Not found on the page")).toBeTruthy();        // VAT amount was read as a value the page does not have
    const customer = within(fields).getByLabelText("Customer") as HTMLInputElement;
    expect(customer.value).toBe("شركة النور القابضة");
    expect(customer.getAttribute("dir")).toBe("auto");                              // Arabic reads right to left
  });

  it("edits a table field row by row, shows the row on the page, and sends the changed table", async () => {
    const api = start(); await go("#/review/scan_0457");
    const items = await screen.findByRole("region", { name: "Line items" });
    expect(within(items).getAllByRole("row")).toHaveLength(5);                      // header + 4 rows read
    const qty = within(items).getByLabelText("Qty, row 2") as HTMLInputElement;
    fireEvent.focus(qty);
    const page = screen.getByRole("region", { name: "Document page" });
    expect(within(page).getByRole("button", { name: "Line items, row 2 on the page" }).getAttribute("aria-pressed")).toBe("true");
    expect(qty.closest("tr")!.getAttribute("aria-selected")).toBe("true");
    fireEvent.click(within(page).getByRole("button", { name: "Line items, row 4 on the page" }));
    expect(document.activeElement).toBe(within(items).getByLabelText("Description, row 4"));
    fireEvent.change(qty, { target: { value: "11" } });
    fireEvent.click(within(items).getByRole("button", { name: "Add row" }));
    fireEvent.change(within(items).getByLabelText("Description, row 5"), { target: { value: "Delivery" } });
    fireEvent.click(within(items).getByRole("button", { name: "Remove row 1" }));
    expect(within(items).getAllByRole("row")).toHaveLength(5);
    fireEvent.click(screen.getByRole("button", { name: /Approve and next/ }));
    await waitFor(() => expect(api.reviews).toHaveLength(1));
    const sent = api.reviews[0].fields.line_items as Record<string, string>[];
    expect(sent.map((r) => r.quantity)).toEqual(["11", "9", "2", ""]);
    expect(sent.at(-1)).toEqual({ description: "Delivery", quantity: "", amount: "" });
  });

  it("shows the e-invoice QR code's values against what was read", async () => {
    start(); await go("#/review/scan_0457");
    const e = await screen.findByRole("region", { name: "E-invoice" });
    expect(within(e).getByText("ZATCA QR code on the page")).toBeTruthy();
    expect(within(e).getByText("1 mismatch")).toBeTruthy();
    const vat = within(e).getByText("VAT amount").closest("tr")!;
    expect(within(vat).getByText("Mismatch")).toBeTruthy();
    expect(within(vat).getByText("6257.13")).toBeTruthy();
    expect(within(e).getAllByText("Match")).toHaveLength(3);
  });

  it("reads e-invoice data first in step 2 and gives fields their own review threshold in step 6", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    const einvoice = within(intake).getByRole("switch", { name: "Read e-invoice data first (ZATCA XML, QR codes)" });
    expect(einvoice.getAttribute("aria-checked")).toBe("true");
    fireEvent.click(einvoice);
    expect(einvoice.getAttribute("aria-checked")).toBe("false");
    fireEvent.click(screen.getByRole("tab", { name: /Human review/ }));
    const levels = screen.getByRole("region", { name: "Field thresholds" });
    fireEvent.click(within(levels).getByRole("button", { name: "Add field threshold" }));
    fireEvent.click(within(levels).getByRole("button", { name: "Add field threshold" }));
    fireEvent.change(within(levels).getByLabelText("Field 2"), { target: { value: "total" } });
    fireEvent.change(within(levels).getByLabelText("Threshold for Total"), { target: { value: "0.99" } });
    fireEvent.click(within(levels).getByRole("button", { name: "Remove threshold for Invoice number" }));
    expect(within(levels).getAllByRole("row")).toHaveLength(2);
    expect(within(screen.getByRole("region", { name: "Process summary" })).getByText(/1 field with its own threshold/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-ksa")).settings).toMatchObject({ einvoiceFirst: false, fieldThresholds: { total: 0.99 } });
  });

  it("names Claude's models where Claude is set up: Haiku reads the fields, Sonnet re-reads for review", async () => {
    start(); await go("#/engines/routing");
    const note = await screen.findByLabelText("Claude models");
    expect(note.textContent).toContain("Claude Haiku 5.5 reads them; Claude Sonnet 5.5 re-reads a document that would otherwise go to review");
    await go("#/engines/catalog/onemind");
    const claude = await screen.findByRole("article", { name: "Claude (Anthropic)" });
    expect(within(claude).getByText(/Claude Haiku 5.5 reads the fields/)).toBeTruthy();
    fireEvent.click(within(claude).getByRole("button", { name: "Configure Claude (Anthropic)" }));
    const dialog = await screen.findByRole("dialog", { name: "Configure Claude (Anthropic)" });
    expect((within(dialog).getByLabelText("Model") as HTMLSelectElement).value).toBe("claude-haiku-5-5");
    expect((within(dialog).getByLabelText("Escalation model") as HTMLSelectElement).value).toBe("claude-sonnet-5-5");
    await go("#/engines/cost");
    const card = await screen.findByRole("region", { name: "Monthly cost estimate" });
    fireEvent.click(within(card).getByRole("button", { name: "Claude Haiku 5.5 (Claude default)" }));
    expect(within(card).getByText(/read again by Claude Sonnet 5.5/)).toBeTruthy();
  });

  it("shows Engines in sections: routing first, then engines by option, each option across the page", async () => {
    start(); await go("#/engines");
    expect(await screen.findByRole("region", { name: "How a page is routed" })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Monthly cost estimate" })).toBeNull();      // cost is a section of its own
    await go("#/engines/catalog");
    const options = await screen.findByRole("tablist", { name: "Engine options" });
    expect(screen.getByRole("region", { name: "OneCore engines" })).toBeTruthy();
    fireEvent.click(within(options).getByRole("tab", { name: /OneMind/ }));
    await waitFor(() => expect(location.hash).toBe("#/engines/catalog/onemind"));
    await go("#/engines/catalog/onemind");
    const mind = await screen.findByRole("region", { name: "OneMind engines" });
    expect(within(mind).getByRole("article", { name: "Claude (Anthropic)" })).toBeTruthy();
    fireEvent.change(within(mind).getByLabelText("Price"), { target: { value: "free" } });
    expect(within(mind).getByText("1 of 3")).toBeTruthy();
    expect(within(mind).getAllByRole("article")).toHaveLength(1);

    const optIn = screen.getByRole("switch", { name: "Cloud opt-in" });
    expect(optIn.getAttribute("aria-checked")).toBe("false");
    fireEvent.click(optIn);
    const ask = await screen.findByRole("dialog", { name: "Turn on cloud opt-in?" });
    fireEvent.click(within(ask).getByRole("button", { name: "Turn on" }));
    await waitFor(() => expect(screen.getByRole("switch", { name: "Cloud opt-in" }).getAttribute("aria-checked")).toBe("true"));
    await waitFor(() => expect(screen.getByText(/Cloud opt-in on: weak pages may go to cloud engines/)).toBeTruthy());
  });

  it("estimates monthly cost like the strategy plan ($33 for 10,000 pages)", async () => {
    expect(estimate(10000, 0.2, 0.3, "azure-di", "azure-di").total).toBe(33);
    expect(estimate(10000, 0.2, 0.3, "azure-di", "claude-sonnet-5-5").total).toBe(51);
    start(); await go("#/engines/cost");
    const card = await screen.findByRole("region", { name: "Monthly cost estimate" });
    expect(within(card).getByText("$33.00", { selector: "[aria-live]" })).toBeTruthy();
    fireEvent.change(within(card).getByLabelText("Field extraction by"), { target: { value: "builtin-rules" } });
    expect(within(card).getByText("$3.00", { selector: "[aria-live]" })).toBeTruthy();
    fireEvent.click(within(card).getByRole("button", { name: "Claude Sonnet 5.5" }));                 // compare: pick another
    expect(within(card).getByText("$51.00", { selector: "[aria-live]" })).toBeTruthy();
  });

  it("shows the saved retry threshold, also one set in DocFlow that the list does not offer", async () => {
    const api = new SampleApi();
    await api.saveRouting({ ...(await api.engines()).routing, retryBelow: 0.65 });
    start(api); await go("#/engines/routing");
    const retry = await screen.findByRole("combobox", { name: "Retry pages below" }) as HTMLSelectElement;
    expect(retry.value).toBe("0.65");                                           // not the first choice, which Save would then store
    expect(screen.getByText("Retry pages under 65%")).toBeTruthy();
  });

  it("builds a process: checks switch on and off and say what happens when they fail", async () => {
    start(); await go("#/processes/supplier-invoices-ksa/checks");
    const checks = await screen.findByRole("region", { name: "Validation" });
    expect(within(checks).getByText("6 of 6 checks")).toBeTruthy();
    expect(screen.getByText("5 of 6 checks on")).toBeTruthy();
    fireEvent.click(within(checks).getByRole("checkbox", { name: "Check on: Large invoice" }));
    expect(screen.getByText("6 of 6 checks on")).toBeTruthy();
    fireEvent.change(within(checks).getByLabelText("If it fails"), { target: { value: "reject" } });
    expect(within(checks).getByText("1 of 6 checks")).toBeTruthy();
    expect(screen.getByText("Unsaved changes")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Go live" }));
    expect(await screen.findByText("Live · version 2")).toBeTruthy();       // saved first, so it counts as a new version
    expect(screen.queryByText("Unsaved changes")).toBeNull();
  });

  it("adds a check and a delivery, and saves the draft", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/checks");
    const checks = await screen.findByRole("region", { name: "Validation" });
    fireEvent.click(within(checks).getByRole("button", { name: "Add check" }));
    const dialog = await screen.findByRole("dialog", { name: "Add check" });
    fireEvent.change(within(dialog).getByLabelText("Kind of check"), { target: { value: "required" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "Invoice date" }));
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Has a date" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(checks).getByText("Has a date")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Export & integration/ }));
    fireEvent.click(screen.getByRole("button", { name: "Add delivery" }));
    const d = await screen.findByRole("dialog", { name: "Add delivery" });
    fireEvent.change(within(d).getByLabelText("Excel file"), { target: { value: "D:\\AP\\register.xlsx" } });
    fireEvent.click(within(d).getByRole("button", { name: "Save" }));
    expect(screen.getByText("Excel register · D:\\AP\\register.xlsx · when done with no review, approved by a reviewer")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByRole("button", { name: "Saved" });
    const saved = await api.processDesign("supplier-invoices-ksa");
    expect(saved.checks.at(-1)).toMatchObject({ name: "Has a date", type: "required", params: { fields: ["invoice_date"] }, test: "invoice_date" });
    expect(saved.deliveries).toHaveLength(1);
    expect(saved.version).toBe(2);
  });

  it("filters document type fields and reference data rows", async () => {
    start(); await go("#/types");
    const fields = await screen.findByRole("region", { name: "Fields to read" });
    fireEvent.change(within(fields).getByLabelText("Type"), { target: { value: "Amount" } });
    expect(within(fields).getByText("3 of 8 fields")).toBeTruthy();
    await go("#/refdata");
    const rows = await screen.findByRole("region", { name: "Rows" });
    await within(rows).findByText("Summit Facilities LLC");
    fireEvent.change(within(rows).getByLabelText("status"), { target: { value: "Blocked" } });
    expect(within(rows).getByText("Summit Facilities LLC")).toBeTruthy();
    expect(within(rows).getAllByRole("row")).toHaveLength(2);
  });

  it("compares two engines in the Test lab, or tests one", async () => {
    start(); await go("#/testlab");
    const run = await screen.findByRole("button", { name: "Run test" }) as HTMLButtonElement;
    expect(run.disabled).toBe(true);                                            // a document first
    fireEvent.change(screen.getByLabelText("Choose a document"), { target: { files: [new File(["x"], "fax_0931.png")] } });
    fireEvent.change(screen.getByLabelText("Engine B"), { target: { value: "paddleocr" } });
    fireEvent.click(run);
    const results = await screen.findByRole("region", { name: "Field results" });
    expect(within(results).getByText("Engine B · PaddleOCR")).toBeTruthy();
    expect(screen.getByText("A 0.6 s · B 7.3 s")).toBeTruthy();
    fireEvent.change(within(results).getByLabelText("Show"), { target: { value: "diff" } });
    expect(within(results).getByText("2 of 7 fields")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "One engine" }));
    expect(screen.queryByRole("region", { name: "Field results" })).toBeNull();
    expect(screen.queryByLabelText("Engine B")).toBeNull();
  });

  it("has two Test lab sections: try one document, as before, and test sets", async () => {
    start(); await go("#/testlab");
    const sections = await screen.findByRole("navigation", { name: "Test lab sections" });
    expect(within(sections).getAllByRole("link").map((a) => a.textContent)).toEqual(["Try one document", "Test sets"]);
    expect(within(sections).getByRole("link", { name: "Try one document" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("region", { name: "Set up the test" })).toBeTruthy();
    await go("#/testlab/sets");
    expect(await screen.findByRole("region", { name: "Test sets" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Test sets" }).getAttribute("aria-current")).toBe("page");
    expect(within(screen.getByRole("region", { name: "How to use this page" })).getByText(/Add our sample set/)).toBeTruthy();
  });

  it("lists test sets with the last run's best result, filtered by document type", async () => {
    start(); await go("#/testlab/sets");
    const sets = await table("Test sets");
    expect(within(sets).getAllByRole("row")).toHaveLength(3);                     // header + 2 sets
    const ap = within(sets).getByRole("link", { name: "AP invoices, reviewed" }).closest("tr")!;
    expect(within(ap).getByText("4")).toBeTruthy();
    expect(within(ap).getByText("Supplier invoice")).toBeTruthy();
    expect(within(ap).getAllByText(/%$/)).toHaveLength(2);                         // field accuracy, straight through
    const receipts = within(sets).getByRole("link", { name: "Receipts" }).closest("tr")!;
    expect(within(receipts).getByText("Never run")).toBeTruthy();
    fireEvent.change(within(sets).getByLabelText("Document type"), { target: { value: "receipt" } });
    expect(within(sets).getByText("1 of 2 sets")).toBeTruthy();
    expect(within(sets).queryByRole("link", { name: "AP invoices, reviewed" })).toBeNull();
  });

  it("creates a test set and adds a document with its right values", async () => {
    const api = start(); await go("#/testlab/sets");
    fireEvent.click(await screen.findByRole("button", { name: "New test set" }));
    const dialog = await screen.findByRole("dialog", { name: "New test set" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Hard receipts" } });
    fireEvent.change(within(dialog).getByLabelText("Document type"), { target: { value: "receipt" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create set" }));
    await waitFor(() => expect(location.hash).toBe("#/testlab/sets/hard-receipts"));
    await go("#/testlab/sets/hard-receipts");
    const items = await table("Documents in the set");
    expect(within(items).getByText(/No documents yet/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Run test" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(items).getByRole("button", { name: "Add a document" }));
    const add = await screen.findByRole("dialog", { name: "Add a document" });
    const save = within(add).getByRole("button", { name: "Add document" }) as HTMLButtonElement;
    fireEvent.change(within(add).getByLabelText("Choose a document"), { target: { files: [new File(["x"], "taxi.jpg")] } });
    expect(save.disabled).toBe(true);                                             // a right value first
    fireEvent.change(within(add).getByLabelText("Merchant"), { target: { value: "Careem" } });
    fireEvent.change(within(add).getByLabelText("Total"), { target: { value: "42.50" } });
    fireEvent.change(within(add).getByLabelText("Tags (optional)"), { target: { value: "photo, faded" } });
    fireEvent.click(save);
    expect(await within(items).findByRole("link", { name: "taxi.jpg" })).toBeTruthy();
    expect((await api.testSet("hard-receipts")).items).toMatchObject([{ fileName: "taxi.jpg", expected: { merchant_name: "Careem", total: "42.50" }, tags: ["photo", "faded"], source: "upload" }]);

    fireEvent.click(within(items).getByRole("button", { name: "Edit right values of taxi.jpg" }));
    const edit = await screen.findByRole("dialog", { name: "Right values of taxi.jpg" });
    fireEvent.change(within(edit).getByLabelText("Total"), { target: { value: "45.00" } });
    fireEvent.click(within(edit).getByRole("button", { name: "Save" }));
    await waitFor(async () => expect((await api.testSet("hard-receipts")).items![0].expected).toEqual({ merchant_name: "Careem", total: "45.00" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    fireEvent.click(within(items).getByRole("button", { name: "Remove taxi.jpg" }));
    await waitFor(async () => expect((await api.testSet("hard-receipts")).itemCount).toBe(0));
  });

  it("adds our sample set of generated invoices, with Arabic/English ones", async () => {
    const api = start(); await go("#/testlab/sets");
    fireEvent.click(await screen.findByRole("button", { name: "Add our sample set" }));
    await waitFor(() => expect(location.hash).toBe("#/testlab/sets/synthetic-invoices-seed"));
    const seed = await api.testSet("synthetic-invoices-seed");
    expect(seed.itemCount).toBe(15);
    expect(seed.items!.some((i) => i.tags.includes("bilingual"))).toBe(true);
    await api.seedTestSet();                                                      // again: topped up, not doubled
    expect((await api.testSet("synthetic-invoices-seed")).itemCount).toBe(15);
  });

  it("runs a test set on engine combinations, waits for it, and marks the best per column", async () => {
    const api = start(); await go("#/testlab/sets/ap-reviewed");
    expect(await table("Results by combination")).toBeTruthy();                  // the last run shows first
    fireEvent.click(screen.getByRole("button", { name: "Run test" }));
    const dialog = await screen.findByRole("dialog", { name: "Run test" });
    const ocr1 = within(dialog).getByLabelText("OCR engine, combination 1") as HTMLSelectElement;
    expect((within(ocr1).getByRole("option", { name: "Azure Document Intelligence (not ready)" }) as HTMLOptionElement).disabled).toBe(true);
    const fields1 = within(dialog).getByLabelText("Extraction engine, combination 1") as HTMLSelectElement;
    expect(fields1.value).toBe("builtin-rules");
    expect((within(fields1).getByRole("option", { name: "Claude (Anthropic) (not ready)" }) as HTMLOptionElement).disabled).toBe(true);
    fireEvent.click(within(dialog).getByRole("button", { name: "Add combination" }));
    fireEvent.change(within(dialog).getByLabelText("OCR engine, combination 2"), { target: { value: "tesseract" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add combination" }));
    fireEvent.change(within(dialog).getByLabelText("OCR engine, combination 3"), { target: { value: "paddleocr" } });
    expect(within(dialog).getByText("3 combinations")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Run test" }));

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Run test" })).toBeNull());
    const results = await table("Results by combination");
    await waitFor(() => expect(within(screen.getByRole("region", { name: "Results by combination" })).getAllByRole("row")).toHaveLength(4));   // header + 3: the run finished
    const done = screen.getByRole("region", { name: "Results by combination" });
    expect(results).toBeTruthy();
    expect(within(done).getByText("As live + Rules extractor")).toBeTruthy();
    expect(within(done).getByText("Tesseract 5 + Rules extractor")).toBeTruthy();
    expect(within(done).getAllByText("Best").length).toBeGreaterThanOrEqual(5);    // one or more per column
    const runs = await api.setRuns("ap-reviewed");
    expect(runs).toHaveLength(2);
    expect(runs[0]).toMatchObject({ status: "completed", combos: [{ ocr: "auto", extraction: "builtin-rules" }, { ocr: "tesseract", extraction: "builtin-rules" }, { ocr: "paddleocr", extraction: "builtin-rules" }] });
    const past = screen.getByRole("region", { name: "Past runs" });
    await waitFor(() => expect(within(past).getAllByRole("row")).toHaveLength(3));
    expect(within(past).getAllByText("Done")).toHaveLength(2);
    expect(screen.getByRole("region", { name: "Accuracy per field" })).toBeTruthy();
    const docs = screen.getByRole("region", { name: "Documents read" });
    expect(within(docs).getByText(/of 12 readings/)).toBeTruthy();               // 4 documents x 3 combinations
    fireEvent.change(within(docs).getByLabelText("Show"), { target: { value: "all" } });
    expect(within(docs).getByText("12 of 12 readings")).toBeTruthy();
  });

  it("adds an approved document to a test set of its type, from Review", async () => {
    const api = start(); await go("#/review/scan_0457");
    await screen.findByRole("region", { name: "Fields" });
    expect(screen.queryByRole("button", { name: "Add to test set" })).toBeNull();   // not approved yet
    fireEvent.click(screen.getByRole("button", { name: "Use 6,257.13" }));
    fireEvent.click(screen.getByRole("button", { name: /Approve and next/ }));
    await waitFor(() => expect(api.reviews).toHaveLength(1));
    await go("#/review/scan_0457");
    expect(await screen.findByText("Approved by you")).toBeTruthy();
    fireEvent.click(await screen.findByRole("button", { name: "Add to test set" }));
    const dialog = await screen.findByRole("dialog", { name: "Add to test set" });
    expect((within(dialog).getByLabelText("Test set") as HTMLSelectElement).value).toBe("ap-reviewed");   // only sets of a Supplier invoice
    expect(within(dialog).queryByRole("option", { name: /Receipts/ })).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(await screen.findByRole("link", { name: "AP invoices, reviewed" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "AP invoices, reviewed" }).getAttribute("href")).toBe("#/testlab/sets/ap-reviewed");
    const set = await api.testSet("ap-reviewed");
    expect(set.itemCount).toBe(5);
    expect(set.items!.at(-1)).toMatchObject({ fileName: "scan_0457.jpg", source: "document", sourceDocumentId: "scan_0457", tags: ["reviewed"],
      expected: { vat_amount: "6,257.13", invoice_number: "INV-2026-3944", total: "47,971.34" } });
  });

  it("shows how to use each page on the first visit, and remembers when it is hidden", async () => {
    start();
    await go("#/inbox");
    const help = await screen.findByRole("region", { name: "How to use this page" });
    expect(within(help).getAllByRole("listitem")[0].textContent).toContain("Status column");
    fireEvent.click(within(help).getByRole("button", { name: "Hide" }));
    expect(screen.queryByRole("region", { name: "How to use this page" })).toBeNull();
    await go("#/engines");
    expect(within(await screen.findByRole("region", { name: "How to use this page" })).getByText(/Cloud opt-in off/)).toBeTruthy();
    await go("#/inbox");
    expect(screen.queryByRole("region", { name: "How to use this page" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "How to use this page" }));
    expect(screen.getByRole("region", { name: "How to use this page" })).toBeTruthy();
  });

  it("explains an option in one line", async () => {
    start();
    await go("#/engines");
    fireEvent.click(await screen.findByRole("button", { name: "About Cloud opt-in" }));
    expect(screen.getByRole("note").textContent).toContain("never leave this server");
  });

  it("edits a document type's fields and saves it", async () => {
    const api = start(); await go("#/types");
    const fields = await screen.findByRole("region", { name: "Fields to read" });
    fireEvent.click(within(fields).getByRole("button", { name: "Add field" }));
    const dialog = await screen.findByRole("dialog", { name: "Add field" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "PO number" } });
    fireEvent.change(within(dialog).getByLabelText("Other labels on the page"), { target: { value: "Purchase order, PO No" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Required/ }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(fields).getByText("po_number")).toBeTruthy();
    fireEvent.click(within(fields).getByRole("button", { name: 'Forget "Inv #"' }));
    expect(screen.getByText("Unsaved changes")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(async () => {
      const t = (await api.docTypes()).find((x) => x.id === "supplier-invoice")!;
      expect(t.fields.at(-1)).toMatchObject({ key: "po_number", label: "PO number", aliases: ["Purchase order", "PO No"], required: true });
      expect(t.fields[0].learned).toEqual([]);
    });
  });

  it("teaches a new document type from samples, checked before it is saved", async () => {
    const api = start(); await go("#/types");
    fireEvent.click(await screen.findByRole("button", { name: "Teach from samples" }));
    const dialog = await screen.findByRole("dialog", { name: "Teach from samples" });
    fireEvent.change(within(dialog).getByLabelText("Choose samples"), { target: { files: [new File(["x"], "a.pdf"), new File(["y"], "b.pdf")] } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Read samples" }));
    expect(await screen.findByText("Not saved yet")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Type name"), { target: { value: "Pro forma invoice" } });
    fireEvent.click(screen.getByRole("button", { name: "Create type" }));
    await waitFor(async () => expect((await api.docTypes()).some((t) => t.id === "pro-forma-invoice" && t.fields.length === 3)).toBe(true));
  });

  it("sets up a process's email inbox in step 2, Document ingestion", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    fireEvent.click(within(intake).getByRole("checkbox", { name: /Email inbox/ }));
    fireEvent.change(within(intake).getByLabelText("Mailbox kind"), { target: { value: "email" } });
    fireEvent.change(within(intake).getByLabelText("Mail server"), { target: { value: "imap.example.com" } });
    fireEvent.change(within(intake).getByLabelText("User name"), { target: { value: "invoices@example.com" } });
    fireEvent.change(within(intake).getByLabelText("Password (vault reference)"), { target: { value: "mail/invoices/password" } });
    fireEvent.change(within(intake).getByLabelText("Subject contains (optional)"), { target: { value: "invoice" } });
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-ksa")).settings.email).toEqual({ enabled: true, kind: "email", host: "imap.example.com",
      username: "invoices@example.com", password_ref: "mail/invoices/password", subject_contains: "invoice" });
  });

  it("counts every document of a process in the Inbox, not only today's", async () => {
    const api = new SampleApi();
    const all = await api.processes();
    api.processes = async () => all.map((p) => (p.id === "deliveries" ? { ...p, today: 0, done: 2, review: 1, processing: 0, failed: 0 } : p));
    start(api); await go("#/inbox");
    const region = await screen.findByRole("region", { name: "Processes" });
    const row = within(region).getByRole("link", { name: "Deliveries" }).closest("tr")!;
    expect(within(row).getByText("3")).toBeTruthy();
    expect(within(row).queryByText("No documents yet")).toBeNull();
    expect(screen.queryByLabelText("Period")).toBeNull();
  });

  it("starts the builder on step 1: choose a process; the other steps open once one is chosen", async () => {
    start(); await go("#/processes");
    const select = await screen.findByRole("region", { name: "Select a process" });
    const tabs = within(screen.getByRole("tablist", { name: "Steps" })).getAllByRole("tab") as HTMLButtonElement[];
    expect(tabs).toHaveLength(8);
    expect(tabs[0].disabled).toBe(false);
    expect(tabs.slice(1).every((t) => t.disabled)).toBe(true);
    const list = within(select).getByLabelText("Process") as HTMLSelectElement;
    expect([...list.options].map((o) => o.textContent)).toEqual(["Choose a process", "AP invoices", "One Inbox", "Expense claims", "Deliveries"]);
    expect(within(select).getByRole("button", { name: "New process" })).toBeTruthy();
    fireEvent.change(list, { target: { value: "expense-claims" } });
    await waitFor(() => expect(location.hash).toBe("#/processes/expense-claims"));
    await go("#/processes/expense-claims/select");                                  // step 1 again: the process is shown chosen
    const chosen = await screen.findByRole("region", { name: "Select a process" });
    expect((within(chosen).getByLabelText("Process") as HTMLSelectElement).value).toBe("expense-claims");
    expect(within(chosen).getByText("Finance / Expenses")).toBeTruthy();
    expect(within(chosen).getByText("Live · version 1")).toBeTruthy();
  });

  it("uploads files into a draft process in step 2 and shows its last documents", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const upload = await screen.findByRole("region", { name: "Upload files" });
    expect(await within(upload).findByText("No documents yet.")).toBeTruthy();
    fireEvent.change(within(upload).getByLabelText("Choose files for Supplier invoices KSA"), { target: { files: [new File(["x"], "try-1.pdf")] } });
    fireEvent.click(within(upload).getByRole("button", { name: "Start processing" }));
    expect(await within(upload).findByRole("link", { name: "try-1.pdf" })).toBeTruthy();
    expect(within(within(upload).getByRole("region", { name: "Last uploads" })).getByText("Processing")).toBeTruthy();
    expect((await api.processDesign("supplier-invoices-ksa")).state).toBe("draft");
    expect((await api.documents("supplier-invoices-ksa")).map((d) => d.name)).toEqual(["try-1.pdf"]);
  });

  it("shows the Overview as a table of every process: filters, sorting, a row opens the process", async () => {
    start(); await go("#/overview");
    const sections = screen.getByRole("navigation", { name: "Overview sections" });
    expect(within(sections).getAllByRole("link").map((a) => a.textContent)).toEqual(["Table", "Dashboard"]);
    expect(within(sections).getByRole("link", { name: "Table" }).getAttribute("aria-current")).toBe("page");
    const region = await table("Processes");
    // 13 Nov 2026 (item 11): ten columns at a time so the table fits; Activity first, Set-up a click away
    const heads = () => within(region).getAllByRole("button").filter((b) => b.className === "df-sort").map((b) => b.textContent!.replace(/ .$/, ""));
    expect(heads()).toEqual(["Process", "Status", "This month", "Today", "Done", "Straight through", "Waiting for review", "Overdue", "Failed", "Last document"]);
    fireEvent.click(within(region).getByRole("button", { name: "Set-up" }));
    expect(heads()).toEqual(["Process", "Folder", "Status", "Document types", "Comes in from", "Classification", "Extraction engine", "Checks on", "Review", "Delivers to"]);
    const names = () => within(region).getAllByRole("row").slice(1).map((r) => r.querySelector("a")!.textContent);
    expect(names()).toEqual(["AP invoices", "Deliveries", "Expense claims", "One Inbox"]);
    const ap = within(region).getByRole("link", { name: "AP invoices" }).closest("tr")!;
    expect(within(ap).getByText("Live · v4")).toBeTruthy();
    expect(within(ap).getByText("Upload, Watched folder, Bot")).toBeTruthy();
    expect(within(ap).getByText("5 of 6")).toBeTruthy();
    expect(within(ap).getByText("Under 85% · 24 h")).toBeTruthy();
    fireEvent.click(within(region).getByRole("button", { name: "Activity" }));
    expect(within(within(region).getByRole("link", { name: "AP invoices" }).closest("tr")!).getByText("1,460")).toBeTruthy();
    fireEvent.click(within(region).getByRole("button", { name: "Set-up" }));
    const inbox = within(region).getByRole("link", { name: "One Inbox" }).closest("tr")!;
    expect(within(inbox).getByText("Any type")).toBeTruthy();
    expect(within(inbox).getByText("Recognised")).toBeTruthy();

    fireEvent.click(within(region).getByRole("button", { name: "Activity" }));
    fireEvent.click(within(region).getByRole("button", { name: "This month" }));        // numbers sort largest first
    expect(names()).toEqual(["AP invoices", "One Inbox", "Expense claims", "Deliveries"]);
    expect(within(region).getByRole("columnheader", { name: /This month/ }).getAttribute("aria-sort")).toBe("descending");
    fireEvent.click(within(region).getByRole("button", { name: "This month" }));
    expect(names()[0]).toBe("Deliveries");

    fireEvent.change(within(region).getByLabelText("Comes in from"), { target: { value: "email" } });
    expect(names()).toEqual(["Deliveries"]);
    expect(within(region).getByText("1 of 4 processes")).toBeTruthy();
    fireEvent.click(within(region).getByRole("button", { name: "Clear filters" }));
    fireEvent.change(within(region).getByLabelText("Folder"), { target: { value: "Finance" } });
    expect(names()).toEqual(["Expense claims", "AP invoices"]);
    fireEvent.click(within(region).getByRole("button", { name: "Set-up" }));            // the folder is a set-up column
    fireEvent.click(within(region).getByText("Finance / Expenses"));                    // anywhere on the row
    expect(location.hash).toBe("#/process/expense-claims");
  });

  it("shows the charts on the Overview's Dashboard", async () => {
    const api = start(); await go("#/overview/dashboard");
    expect(await screen.findByText("Documents today")).toBeTruthy();
    for (const name of ["Documents per day", "Needs attention", "Straight-through rate", "Review backlog", "Failures", "Documents by process", "Where pages were read"])
      expect(screen.getByRole("region", { name })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Processes" })).toBeNull();            // the table is a page of its own
    expect(within(screen.getByRole("region", { name: "Review backlog" })).getByText("5 · 1 overdue")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "Failures" })).getByRole("link", { name: "One Inbox" })).toBeTruthy();
    expect(screen.queryByText("Cloud cost this month")).toBeNull();                      // only with cloud opt-in
    expect(within(screen.getByRole("region", { name: "How to use this page" })).getByText(/in charts/)).toBeTruthy();
    cleanup();
    await api.saveRouting({ ...(await api.engines()).routing, cloudOptIn: true });
    start(api); await go("#/overview/dashboard");
    expect(await screen.findByText("Cloud cost this month")).toBeTruthy();
  });

  it("offers the administrator's languages for a document type, marking those not installed", async () => {
    const api = start(); await go("#/types");
    const type = await screen.findByRole("region", { name: "Supplier invoice" });
    const langs = within(type).getByRole("group", { name: "Languages" });
    await waitFor(() => expect(within(langs).getAllByRole("checkbox").map((c) => c.parentElement!.textContent)).toEqual(["English", "Arabic"]));

    await go("#/engines/languages");
    const panel = await screen.findByRole("region", { name: "Languages" });
    expect(within(panel).getByText("2 offered · 8 installed")).toBeTruthy();
    fireEvent.click(within(panel).getByRole("checkbox", { name: "Offer Urdu" }));
    fireEvent.click(within(panel).getByRole("checkbox", { name: "Offer Bengali" }));
    fireEvent.change(within(panel).getByLabelText("Show"), { target: { value: "missing" } });
    expect(within(panel).getByText("Bengali").closest("tr")!.textContent).toContain("Not installed");
    fireEvent.click(within(panel).getByRole("button", { name: "Save languages" }));
    await waitFor(async () => expect((await api.languages()).filter((l) => l.offered).map((l) => l.code)).toEqual(["en", "ar", "ur", "bn"]));

    await go("#/types");
    const again = within(await screen.findByRole("region", { name: "Supplier invoice" })).getByRole("group", { name: "Languages" });
    await waitFor(() => expect(within(again).getAllByRole("checkbox")).toHaveLength(4));
    expect(within(again).getByText("Bengali").parentElement!.textContent).toContain("not installed");
    fireEvent.click(within(again).getByRole("checkbox", { name: /Urdu/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(async () => expect((await api.docTypes()).find((t) => t.id === "supplier-invoice")!.languages).toEqual(["en", "ar", "ur"]));
  });

  it("keeps a document type's language that is no longer offered", async () => {
    const api = new SampleApi();
    await api.saveOfferedLanguages(["en"]);
    start(api); await go("#/types");
    const langs = within(await screen.findByRole("region", { name: "Supplier invoice" })).getByRole("group", { name: "Languages" });
    await waitFor(() => expect(within(langs).getByText("not offered")).toBeTruthy());
    expect((within(langs).getByRole("checkbox", { name: /Arabic/ }) as HTMLInputElement).checked).toBe(true);
  });
});
