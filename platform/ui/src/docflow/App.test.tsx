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
  it("sits under AI in the Command Center menu, with seven tabs and Overview first", async () => {
    start();
    const menu = await screen.findByRole("navigation", { name: "Command Center" });
    expect(within(menu).getByRole("link", { name: "Document Automation" }).getAttribute("aria-current")).toBe("page");
    expect(within(menu).getByRole("link", { name: "Document Automation" }).getAttribute("href")).toBe("/documents/");
    const tabs = screen.getByRole("navigation", { name: "Document Automation" });
    expect(within(tabs).getAllByRole("link").map((a) => a.textContent))
      .toEqual(["Overview", "Inbox", "Processes", "Document types", "Reference data", "Engines", "Test lab"]);
    expect(within(tabs).getByRole("link", { name: "Overview" }).getAttribute("aria-current")).toBe("page");
    expect(await screen.findByText("Documents today")).toBeTruthy();
    expect(screen.getByText("Local-only: nothing leaves this server")).toBeTruthy();
  });

  it("reads routes from the hash", () => {
    expect(parseRoute("#/process/ap-invoices")).toEqual({ tab: "inbox", process: "ap-invoices" });
    expect(parseRoute("#/review/scan_0457")).toEqual({ tab: "review", document: "scan_0457" });
    expect(parseRoute("#/engines")).toEqual({ tab: "engines" });
    expect(parseRoute("#/nowhere")).toEqual({ tab: "overview" });
  });

  it("lists processes in the Inbox and filters them; Clear filters brings them back", async () => {
    start(); await go("#/inbox");
    const region = await table("Processes");
    expect(within(region).getAllByRole("row")).toHaveLength(5);   // header + 4 processes
    fireEvent.change(within(region).getByLabelText("Status"), { target: { value: "failed" } });
    expect(within(region).getAllByRole("row")).toHaveLength(2);
    expect(within(region).getByRole("link", { name: "Smart inbox" })).toBeTruthy();
    fireEvent.change(within(region).getByLabelText("Comes in from"), { target: { value: "email" } });
    expect(within(region).getByText("No process matches these filters.")).toBeTruthy();
    fireEvent.click(within(region).getAllByRole("button", { name: "Clear filters" })[0]);
    expect(within(region).getAllByRole("row")).toHaveLength(5);
  });

  it("adds documents to a chosen process and opens it", async () => {
    const api = start(); await go("#/inbox");
    fireEvent.click(await screen.findByRole("button", { name: "Add documents" }));
    const dialog = await screen.findByRole("dialog", { name: "Add documents" });
    const start_ = within(dialog).getByRole("button", { name: "Start processing" }) as HTMLButtonElement;
    expect(start_.disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText("Choose files"), { target: { files: [new File(["x"], "INV-NEW-1.pdf")] } });
    fireEvent.click(within(dialog).getByRole("radio", { name: /Use a process/ }));
    fireEvent.change(within(dialog).getByLabelText("Process"), { target: { value: "expense-claims" } });
    expect((within(dialog).getByLabelText("Folder") as HTMLSelectElement).value).toBe("Finance / Expenses");   // the process's own folder
    fireEvent.click(within(dialog).getByRole("button", { name: "Start processing" }));
    await waitFor(() => expect(location.hash).toBe("#/process/expense-claims"));
    expect((await api.documents("expense-claims")).some((d) => d.name === "INV-NEW-1.pdf")).toBe(true);
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
    const menu = screen.queryByRole("navigation", { name: "Command Center" });
    expect(menu).toBeTruthy();
  });

  it("routes local first: cloud opt-in asks first, and the options show one at a time", async () => {
    start(); await go("#/engines");
    const options = await screen.findByRole("tablist", { name: "Engine options" });
    expect(screen.getByRole("region", { name: "OneCore engines" })).toBeTruthy();
    fireEvent.click(within(options).getByRole("tab", { name: /OneMind/ }));
    const mind = screen.getByRole("region", { name: "OneMind engines" });
    expect(within(mind).getByText("Claude (Anthropic)")).toBeTruthy();
    fireEvent.change(within(mind).getByLabelText("Price"), { target: { value: "free" } });
    expect(within(mind).getByText("1 of 3")).toBeTruthy();

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
    start(); await go("#/engines");
    const card = await screen.findByRole("region", { name: "Monthly cost estimate" });
    expect(within(card).getByText("$33.00")).toBeTruthy();
    fireEvent.change(within(card).getByLabelText("Field extraction by"), { target: { value: "builtin-rules" } });
    expect(within(card).getByText("$3.00", { selector: "[aria-live]" })).toBeTruthy();
  });

  it("builds a process: checks switch on and off and say what happens when they fail", async () => {
    start(); await go("#/processes/supplier-invoices-ksa");
    const checks = await screen.findByRole("region", { name: "Checks and lookups" });
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
    const api = start(); await go("#/processes/supplier-invoices-ksa");
    const checks = await screen.findByRole("region", { name: "Checks and lookups" });
    fireEvent.click(within(checks).getByRole("button", { name: "Add check" }));
    const dialog = await screen.findByRole("dialog", { name: "Add check" });
    fireEvent.change(within(dialog).getByLabelText("Kind of check"), { target: { value: "required" } });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "Invoice date" }));
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Has a date" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(checks).getByText("Has a date")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /Deliver/ }));
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

  it("shows how to use each page on the first visit, and remembers when it is hidden", async () => {
    start();
    await go("#/inbox");
    const help = await screen.findByRole("region", { name: "How to use this page" });
    expect(within(help).getAllByRole("listitem")[0].textContent).toContain("Add documents");
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

  it("sets up a process's email inbox in the Intake stage", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Intake" });
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
});
