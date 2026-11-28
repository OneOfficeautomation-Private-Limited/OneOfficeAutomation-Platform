import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { SampleApi, estimate } from "./api";
import { App, parseRoute } from "./App";
import { FORMATS } from "./formats";
import { choicesOf, choose, valueOf } from "../../components/testing";

beforeEach(() => { location.hash = ""; });
afterEach(() => { cleanup(); localStorage.clear(); });

/** Step 2's "+ Add a way in" (27 Nov 2026): adds a way documents come in, unless its row is there already. */
/** DA-8: own mailbox settings show only for a process that already uses them; these tests start with one. */
async function mailboxApi(api = new SampleApi()) {
  const d = await api.processDesign("supplier-invoices-ksa");
  await api.saveProcess({ ...d, settings: { ...d.settings, email: { enabled: true, kind: "outlook" } } });
  return api;
}

async function addWay(intake: HTMLElement, name: string) {
  if (within(intake).queryByRole("group", { name })) return;
  fireEvent.pointerDown(within(intake).getByRole("button", { name: "+ Add a way in ▾" }), { button: 0, ctrlKey: false });
  fireEvent.click(await screen.findByRole("menuitem", { name: new RegExp("^" + name) }));
  await within(intake).findByRole("group", { name });
}

const go = async (hash: string) => { await act(async () => { location.hash = hash; window.dispatchEvent(new HashChangeEvent("hashchange")); }); };
const start = (api = new SampleApi()) => { render(<App api={api} />); return api; };
const table = (name: string) => screen.findByRole("region", { name });

describe("Document Automation", () => {
  it("opens every other page from its menu as a link, Build > Agents too (14 Nov 2026: Agents did nothing here)", async () => {
    start();
    const menu = await screen.findByRole("navigation", { name: "One CommandCenter" });
    const build = within(menu).getByRole("button", { name: /^(Unfold )?Build$/ });
    if (build.getAttribute("aria-expanded") !== "true") fireEvent.click(build);   // Document Automation is in Build since 26 Nov 2026, so Build is open
    const agents = await within(menu).findByRole("link", { name: "Agents" });
    expect(agents.getAttribute("href")).toBe("/next/#/automation");
    expect(within(menu).queryAllByRole("button").map((b) => b.textContent?.trim())).not.toContain("Agents");   // no button that waits for Studio
  });

  it("sits under Build in the Command Center menu: Overview, Inbox and Processes, then the Libraries", async () => {
    start();
    const menu = await screen.findByRole("navigation", { name: "One CommandCenter" });
    expect(within(menu).getByRole("link", { name: "Document Automation" }).getAttribute("aria-current")).toBe("page");
    expect(within(menu).getByRole("link", { name: "Document Automation" }).getAttribute("href")).toBe("/next/#/docautomation");   // in the console's frame (27 Nov 2026)
    const tabs = screen.getByRole("navigation", { name: "Document Automation" });
    expect(within(tabs).getAllByRole("group").map((g) => g.getAttribute("aria-label"))).toEqual(["Pages", "Libraries"]);
    expect(within(within(tabs).getByRole("group", { name: "Pages" })).getAllByRole("link").map((a) => a.textContent)).toEqual(["Overview", "Inbox", "Processes"]);
    expect(within(tabs).getByRole("link", { name: "Overview" }).getAttribute("aria-current")).toBe("page");
    expect(await screen.findByRole("region", { name: "Processes" })).toBeTruthy();   // Overview opens on its table
    expect(screen.getByText("Local-only: nothing leaves this server")).toBeTruthy();
  });

  it("shows the tenant; with several, the one chosen is used for the next requests and kept", async () => {
    const api = new SampleApi();
    const { unmount } = render(<App api={api} tenants={["Acme Trading Co.", "Beta Foods"]} />);
    const pick = screen.getByRole("combobox", { name: "Tenant" }) as HTMLSelectElement;
    expect(pick.value).toBe("Acme Trading Co.");
    expect(api.tenant).toBe("Acme Trading Co.");
    await act(async () => { fireEvent.change(pick, { target: { value: "Beta Foods" } }); });
    expect((screen.getByRole("combobox", { name: "Tenant" }) as HTMLSelectElement).value).toBe("Beta Foods");
    expect(api.tenant).toBe("Beta Foods");
    unmount();
    const again = new SampleApi();
    render(<App api={again} tenants={["Acme Trading Co.", "Beta Foods"]} />);     // the next visit opens on the tenant chosen
    expect((screen.getByRole("combobox", { name: "Tenant" }) as HTMLSelectElement).value).toBe("Beta Foods");
    expect(again.tenant).toBe("Beta Foods");
    cleanup();
    render(<App api={new SampleApi()} tenants={["Gamma Ltd"]} />);                 // one tenant: named, nothing to choose
    expect(screen.getByText("Tenant: Gamma Ltd")).toBeTruthy();
    expect(screen.queryByRole("combobox", { name: "Tenant" })).toBeNull();
  });

  it("opens the libraries from a small menu, which then names the one open", async () => {
    start();
    const libraries = within(screen.getByRole("group", { name: "Libraries" })).getByRole("button");
    expect(libraries.textContent).toBe("Libraries");
    await act(async () => { libraries.focus(); fireEvent.keyDown(libraries, { key: "ArrowDown" }); });
    const items = within(await screen.findByRole("menu")).getAllByRole("menuitem").map((i) => i.textContent);
    // one line per entry (3a): what each does shows on hover and is read out with its name
    expect(items).toEqual(["Document types", "Reference data", "Engines", "Test lab"]);
    expect(within(screen.getByRole("menu")).getByRole("menuitem", { name: "Engines, Which engines read the pages; cloud opt-in; cost" }).title).toBe("Which engines read the pages; cloud opt-in; cost");
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
    // step 4 (28 Nov 2026): what is read (the type's fields), who reads them by name, the budget in its currency
    const extract = screen.getByRole("region", { name: "Data extraction" });
    const read = await within(extract).findByRole("table", { name: /^Fields of / });
    expect(within(read).getAllByRole("row").length).toBeGreaterThan(1);
    expect(within(extract).getByRole("button", { name: "Edit this type" })).toBeTruthy();   // opens beside the step (Nav A)
    await waitFor(() => expect(within(extract).getByText(/^Read by/).textContent).not.toMatch(/the default engine/));
    expect(within(extract).getByLabelText("Monthly budget for cloud and AI reading (optional)")).toBeTruthy();
    expect(within(extract).getByText("USD")).toBeTruthy();                             // the currency once, by the box
    expect(read.classList.contains("cc-no-filter")).toBe(true);                       // a small read-only list: no funnels
    fireEvent.click(within(extract).getByRole("button", { name: "Show advanced settings" }));
    // the engine list is long enough to be the searchable list: its value shows the default by name
    const engine = within(extract).getByLabelText("Engine that reads the fields") as HTMLInputElement;
    expect(engine.value).toMatch(/^Default engine \(Engines page\): .+/);
    fireEvent.focus(engine);
    expect(screen.queryByRole("option", { name: /Bedrock Data Automation/ })).toBeNull();   // splitters do not read fields
    expect(screen.getAllByRole("option", { name: /Claude/ }).length).toBeGreaterThan(0);
    fireEvent.blur(engine);
    fireEvent.click(screen.getByRole("button", { name: "step 8, Testing & go-live" }));
    const test = screen.getByRole("region", { name: "Testing & go-live" });
    expect(await within(test).findByRole("button", { name: "Run test" })).toBeTruthy();   // the type-only test, folded below
    // step 8 (28 Nov 2026): try in this process, the checklist, and Go live says what it does first
    const tryIt = within(test).getByRole("region", { name: "Try in this process" });
    const list = within(test).getByRole("region", { name: "Before going live" });
    expect(await within(list).findByText("Not tried on a sample document yet")).toBeTruthy();
    expect(within(list).getByText("Nothing is delivered: results stay in DocFlow")).toBeTruthy();
    fireEvent.change(within(tryIt).getByLabelText("Sample document"), { target: { files: [new File(["%PDF"], "inv.pdf", { type: "application/pdf" })] } });
    fireEvent.click(within(tryIt).getByRole("button", { name: "Try it" }));
    const would = await within(tryIt).findByLabelText("What would happen");
    expect(would.textContent).toContain("Would go to a person for review");
    expect(within(would).getByText("Total is 62% sure; 85% is needed")).toBeTruthy();
    expect(await within(list).findByText("Tried on a sample document")).toBeTruthy();
    fireEvent.click(within(list).getAllByRole("button", { name: "Step 7" })[0]);
    expect(screen.getByRole("region", { name: "Export & integration" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "step 8, Testing & go-live" }));
    fireEvent.click(within(screen.getByRole("region", { name: "Testing & go-live" })).getByRole("button", { name: "Go live" }));
    const confirm = await screen.findByRole("dialog", { name: /^Go live with / });
    expect(confirm.textContent).toMatch(/New documents from uploads, agents, the watched folder/);
    fireEvent.click(within(confirm).getByRole("button", { name: "Go live" }));
    expect(await screen.findByText("Live · version 1")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "Testing & go-live" })).getByText("This process is live: new documents use this version.")).toBeTruthy();
  });

  it("shows this process's choices in step 3, and its document type read-only with Edit this type", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/classify");
    const step = await screen.findByRole("region", { name: "Document classification" });
    expect((within(step).getByLabelText("Document type") as HTMLSelectElement).value).toBe("supplier-invoice");
    // the type is shared: read-only here, edited in Libraries (28 Nov 2026, artboard "DA step 3")
    const read = within(step).getByRole("region", { name: "What is read" });
    expect(within(read).getByRole("table", { name: "Fields of Supplier invoice" })).toBeTruthy();
    expect(within(read).getByText(/A bill from a supplier asking for payment/)).toBeTruthy();
    // Nav A (28 Nov 2026): Edit this type opens the type beside the step; Save brings you back; a shared type can be copied
    fireEvent.click(within(read).getByRole("button", { name: "Edit this type" }));
    const slow = { timeout: 5000 };   // the panel loads the types; CI machines can be slow
    const panel = await screen.findByRole("dialog", { name: "Supplier invoice" }, slow);
    await within(panel).findByRole("note", {}, slow);
    expect(within(panel).getByLabelText("Where you are").textContent).toBe("Supplier invoices KSA › step 3 › document type");
    expect(within(panel).getByRole("link", { name: "Open in Libraries ↗" }).getAttribute("target")).toBe("_blank");
    expect(within(panel).getByRole("note").textContent).toMatch(/^Shared: Supplier invoices KSA and 1 more process read this type/);
    fireEvent.change(within(panel).getByLabelText("Type name"), { target: { value: "Supplier invoice" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Make a copy for Supplier invoices KSA only" }));
    await within(panel).findByText(/Copy made: Supplier invoices KSA now reads/, {}, slow);
    expect((within(panel).getByLabelText("Type name") as HTMLInputElement).value).toBe("Supplier invoice (Supplier invoices KSA)");
    // the step's list of types loads again with the copy in it
    await waitFor(() => expect((within(step).getByLabelText("Document type") as HTMLSelectElement).value).toBe("supplier-invoice-supplier-invoices-ksa"), slow);
    fireEvent.change(within(panel).getByLabelText(/Description/), { target: { value: "Invoices from KSA suppliers only." } });
    fireEvent.click(within(panel).getByRole("button", { name: "Save and back to step 3" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /Supplier invoice/ })).toBeNull(), slow);
    expect(await within(step).findByText(/Invoices from KSA suppliers only/, {}, slow)).toBeTruthy();   // after the step loads the types again
    fireEvent.change(within(step).getByLabelText("Document type"), { target: { value: "supplier-invoice" } });
    expect(screen.queryByRole("region", { name: "Fields to read" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Add field" })).toBeNull();
    fireEvent.change(within(step).getByLabelText("Document type"), { target: { value: "receipt" } });
    expect(screen.getByText("Unsaved changes")).toBeTruthy();
    expect(within(step).getByRole("region", { name: "What is read" }).textContent).toContain("Receipt");
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    expect(await api.processDesign("supplier-invoices-ksa")).toMatchObject({ docTypeIds: ["receipt"], docType: "Receipt" });
    // Edit this type opens that type in Libraries, Document types
    await go("#/types/receipt");
    expect((await screen.findByLabelText("Type name") as HTMLInputElement).value).toBe("Receipt");
  });

  it("makes a new document type in step 3, and the process reads it", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/classify");
    fireEvent.click(await screen.findByRole("button", { name: "New type…" }));
    const naming = await screen.findByRole("dialog", { name: "New document type" });
    fireEvent.change(within(naming).getByLabelText("Name"), { target: { value: "Customs declaration" } });
    fireEvent.click(within(naming).getByRole("button", { name: "Next: add fields" }));
    const field = await screen.findByRole("dialog", { name: "Add field" });
    fireEvent.change(within(field).getByLabelText("Name"), { target: { value: "Declaration number" } });
    fireEvent.click(within(field).getByRole("button", { name: "Save" }));
    const create = screen.getByRole("button", { name: "Create type" }) as HTMLButtonElement;
    expect(create.disabled).toBe(true);                                         // DA-10: a description first
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: "A form declaring goods that cross a border." } });
    fireEvent.click(create);
    await waitFor(() => expect((screen.getByLabelText("Document type") as HTMLSelectElement).value).toBe("customs-declaration"));
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
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

  it("creates a process from three fields, then sets where it is kept and how documents come in, in step 2", async () => {
    const api = start(); await go("#/inbox");
    fireEvent.click(await screen.findByRole("button", { name: "New process" }));
    const dialog = await screen.findByRole("dialog", { name: "New process" });
    // the dialog (27 Nov 2026, artboard "DA step 1"): Start from, the name and the document type; nothing else
    expect(within(dialog).queryByText("Folder")).toBeNull();
    expect(within(dialog).queryByText("How documents come in")).toBeNull();
    const create = within(dialog).getByRole("button", { name: "Create process" }) as HTMLButtonElement;
    const name = within(dialog).getByLabelText("Process name") as HTMLInputElement;
    expect(name.value).toBe("");                                                  // DA-3: typed, never filled in
    await waitFor(() => expect((within(dialog).getByLabelText("Document type") as HTMLSelectElement).value).toBe("supplier-invoice"));
    expect(create.disabled).toBe(true);
    fireEvent.change(name, { target: { value: "Supplier invoices Riyadh" } });
    fireEvent.click(create);
    await waitFor(() => expect(location.hash).toBe("#/processes/supplier-invoices-riyadh/intake"));   // step 2 next
    // step 2: where it is kept, in the folder tree
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    const kept = within(intake).getByRole("region", { name: "Where this process is kept" });
    fireEvent.click(within(kept).getByRole("button", { name: "Change…" }));
    const folders = await screen.findByRole("dialog", { name: "Choose the process's folder" });
    fireEvent.click(within(folders).getByRole("button", { name: "Expand all folders" }));
    fireEvent.click(within(within(folders).getByRole("group", { name: "Folders" })).getByText("Expenses"));
    expect(within(folders).getByText("Chosen:").parentElement!.textContent).toContain("Finance › Expenses");
    fireEvent.click(within(folders).getByRole("button", { name: "Choose" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Choose the process's folder" })).toBeNull());
    expect(within(kept).getByText("Finance › Expenses")).toBeTruthy();
    // DA-7: one save point, shown where people work: the changed row says Unsaved, and Save is at the foot of step 2 too
    expect(within(kept).getByText("Unsaved")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "Save draft" })).toHaveLength(2);
    // DA-8: Email is the mail accounts; own mailbox settings are not offered to a new process
    fireEvent.pointerDown(within(intake).getByRole("button", { name: "+ Add a way in ▾" }), { button: 0, ctrlKey: false });
    const offered = (await screen.findAllByRole("menuitem")).map((m) => m.textContent);
    expect(offered).toContain("Email");
    expect(offered).not.toContain("Own mailbox settings");
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    // how documents come in: Upload is there; a watched folder is added with its own inputs, and removed with ✕
    expect(within(intake).getByRole("group", { name: "Upload" })).toBeTruthy();
    await addWay(intake, "Watched folder");
    const watched = within(intake).getByRole("group", { name: "Watched folder" });
    expect(within(watched).getByLabelText("Folder path")).toBeTruthy();
    expect(within(watched).getByLabelText("Check every")).toBeTruthy();
    fireEvent.click(within(watched).getByRole("button", { name: "Remove Watched folder" }));
    expect(within(intake).queryByRole("group", { name: "Watched folder" })).toBeNull();
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-riyadh")).folder).toBe("Finance/Expenses");
    expect((await api.processDesign("supplier-invoices-riyadh")).state).toBe("draft");
  });

  it("starts a new process from a template and tries it with a sample, saving nothing (DA-2)", async () => {
    const api = start(); await go("#/inbox");
    fireEvent.click(await screen.findByRole("button", { name: "New process" }));
    const dialog = await screen.findByRole("dialog", { name: "New process" });
    fireEvent.change(within(dialog).getByLabelText("Start from"), { target: { value: "receipts" } });
    await waitFor(() => expect((within(dialog).getByLabelText("Document type") as HTMLSelectElement).value).toBe("receipt"));
    expect(within(dialog).getByText(/Reads/)).toBeTruthy();                          // what it does, one line under the list
    expect((within(dialog).getByLabelText("Process name") as HTMLInputElement).value).toBe("");   // DA-3: never filled in
    const before = (await api.processes()).length;
    fireEvent.click(within(dialog).getByRole("button", { name: "Try it with a sample receipt" }));
    expect(await within(dialog).findByRole("region", { name: "What the sample gave" })).toBeTruthy();
    expect((await api.processes()).length).toBe(before);                       // a try saves nothing
    fireEvent.change(within(dialog).getByLabelText("Start from"), { target: { value: "scratch" } });
    expect(within(dialog).queryByRole("button", { name: /Try it with a sample/ })).toBeNull();
    expect(within(dialog).getByText("An empty process: choose each step yourself.")).toBeTruthy();
  });

  it("shows every document of a process, with filters on how it came in, engine and confidence", async () => {
    start(); await go("#/process/ap-invoices");
    const region = await table("Documents in AP invoices");
    expect(within(region).getByText("8 of 8 documents")).toBeTruthy();
    fireEvent.change(within(region).getByLabelText("Read by"), { target: { value: "PaddleOCR" } });   // the engines that read them, from the documents
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

  it("reads e-invoices from their own data with nothing to set (DA-9), and gives fields their own review threshold in step 6", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    expect(within(intake).queryByRole("switch", { name: "Read e-invoice data first" })).toBeNull();   // DA-9: not shown
    fireEvent.click(screen.getByRole("tab", { name: /Human review/ }));
    fireEvent.click(screen.getByRole("button", { name: "Show advanced settings" }));   // DA-1: thresholds per field are advanced
    const levels = screen.getByRole("region", { name: "Stricter for some fields" });
    fireEvent.click(within(levels).getByRole("button", { name: "Add a stricter field" }));
    fireEvent.click(within(levels).getByRole("button", { name: "Add a stricter field" }));
    fireEvent.change(within(levels).getByLabelText("Field 2"), { target: { value: "total" } });
    fireEvent.change(within(levels).getByLabelText("Threshold for Total"), { target: { value: "0.99" } });
    fireEvent.click(within(levels).getByRole("button", { name: "Remove threshold for Invoice number" }));
    expect(within(levels).getAllByRole("row")).toHaveLength(2);
    expect(within(screen.getByRole("region", { name: "Process summary" })).getByText(/1 field with its own threshold/)).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-ksa")).settings).toMatchObject({ einvoiceFirst: true, fieldThresholds: { total: 0.99 } });
  });

  it("names Claude's models where Claude is set up: Haiku reads the fields, Sonnet re-reads for review", async () => {
    const api = start(); await go("#/engines/routing");
    const note = await screen.findByLabelText("Claude models");
    expect(note.textContent).toContain("Claude Haiku 5.5 reads them; Claude Sonnet 5.5 re-reads a document that would otherwise go to review");
    await go("#/engines/catalog/onemind");
    const claude = await screen.findByRole("article", { name: "Claude (Anthropic)" });
    expect(within(claude).getByText(/Claude Haiku 5.5 reads the fields/)).toBeTruthy();
    fireEvent.click(within(claude).getByRole("button", { name: "Configure Claude (Anthropic)" }));
    const dialog = await screen.findByRole("dialog", { name: "Configure Claude (Anthropic)" });
    expect((within(dialog).getByLabelText("Model") as HTMLSelectElement).value).toBe("claude-haiku-5-5");
    expect((within(dialog).getByLabelText("Escalation model") as HTMLSelectElement).value).toBe("claude-sonnet-5-5");
    // I-4: the key may come from a Model connection: the key and model fields give way, the escalation model stays
    const from = await within(dialog).findByLabelText("Where the key comes from") as HTMLSelectElement;
    expect([...from.options].map((o) => o.value)).toEqual(["", "claude-prod"]);       // only anthropic connections fit Claude
    expect(within(dialog).getByLabelText("Anthropic API key")).toBeTruthy();
    fireEvent.change(from, { target: { value: "claude-prod" } });
    expect(within(dialog).queryByLabelText("Anthropic API key")).toBeNull();
    expect(within(dialog).queryByLabelText("Model")).toBeNull();
    expect(within(dialog).getByLabelText("Escalation model")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Configure Claude (Anthropic)" })).toBeNull());
    expect((await api.engineConnections()).choices).toEqual({ claude: "claude-prod" });
    await go("#/engines/catalog/onemind");
    expect(within(await screen.findByRole("article", { name: "Claude (Anthropic)" })).getByText(/Model connection: claude-prod/)).toBeTruthy();
    await go("#/engines/cost");
    const card = await screen.findByRole("region", { name: "Monthly cost estimate" });
    fireEvent.click(within(card).getByRole("button", { name: "Claude Haiku 5.5 (Claude default)" }));
    expect(within(card).getByText(/read again by Claude Sonnet 5.5/)).toBeTruthy();
  });

  it("shows Engines in sections: the overview first, then routing and engines by option, each option across the page", async () => {
    const api = start(); await go("#/engines");
    // DA-14 (28 Nov 2026): who does each job, then the engines in three groups
    const jobs = await screen.findByRole("region", { name: "Who does each job" });
    expect(within(jobs).getByText("Recognise and split")).toBeTruthy();
    for (const g of ["On this server", "Cloud document services", "AI models"]) expect(screen.getByRole("region", { name: g })).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "AI models" })).getByText(/OpenAI, Azure OpenAI, Google Gemini/)).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "AI models" })).getByRole("link", { name: "Test or configure ↗" }).getAttribute("href")).toBe("#/engines/catalog/onemind");
    fireEvent.change(within(jobs).getByLabelText("Read the fields"), { target: { value: "claude" } });
    // I-1: recognising types is a job of its own: the words on the page, or an AI engine that recognises from descriptions
    const recognise = within(jobs).getByLabelText("Recognise and split") as HTMLSelectElement;
    expect([...recognise.options].map((o) => o.value)).toEqual(["", "azure-content-understanding", "google-docai-split", "aws-bda", "claude", "gemini", "bedrock", "azure-openai", "llm-cloud", "llm-local"]);
    // I-3: the cloud recognise-and-split services only recognise: they are not offered to read pages or fields
    for (const job of ["Read the page", "Read the fields"]) expect([...(within(jobs).getByLabelText(job) as HTMLSelectElement).options].map((o) => o.value)).not.toContain("aws-bda");
    fireEvent.change(recognise, { target: { value: "claude" } });
    fireEvent.click(within(jobs).getByRole("button", { name: "Save" }));
    await waitFor(async () => expect((await api.engines()).routing).toMatchObject({ extraction: "claude", classify: "claude" }));
    await go("#/engines/routing");
    expect(await screen.findByRole("region", { name: "How a page is routed" })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Monthly cost estimate" })).toBeNull();      // cost is a section of its own
    await go("#/engines/catalog");
    const options = await screen.findByRole("tablist", { name: "Engine options" });
    expect(screen.getByRole("region", { name: "OneCore engines" })).toBeTruthy();
    // DA-1: plain names first, the OneCore / OneConnect / OneMind labels beside them, and the state in words
    expect(within(options).getByRole("tab", { name: /On this server.*OneCore.*Ready · 4 of 4/ })).toBeTruthy();
    expect(within(options).getByRole("tab", { name: /Cloud reading.*OneConnect.*Off: cloud not allowed/ })).toBeTruthy();
    fireEvent.click(within(options).getByRole("tab", { name: /AI reading.*OneMind/ }));
    await waitFor(() => expect(location.hash).toBe("#/engines/catalog/onemind"));
    await go("#/engines/catalog/onemind");
    const mind = await screen.findByRole("region", { name: "OneMind engines" });
    expect(within(mind).getByRole("article", { name: "Claude (Anthropic)" })).toBeTruthy();
    fireEvent.change(within(mind).getByLabelText("Price"), { target: { value: "free" } });
    expect(within(mind).getByText("1 of 6")).toBeTruthy();
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
    fireEvent.click(await screen.findByRole("button", { name: "Show advanced settings" }));
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
    // DA-1: the Excel file's folder is chosen from the folders processes may use, then the file is named
    fireEvent.click(await within(d).findByRole("button", { name: "Browse for the Excel file's folder" }));
    const picker = await screen.findByRole("dialog", { name: "Choose the folder for the Excel file" });
    fireEvent.click(await within(picker).findByRole("button", { name: /Accounts payable/ }));
    await waitFor(() => expect(within(picker).getByText(/^Chosen:/).parentElement!.textContent).toContain("D:\\AP"));
    fireEvent.click(within(picker).getByRole("button", { name: "Use this folder" }));
    expect((within(d).getByLabelText("Excel file: folder") as HTMLInputElement).value).toBe("D:\\AP");
    expect((within(d).getByLabelText("File name") as HTMLInputElement).value).toBe("register.xlsx");
    fireEvent.click(within(d).getByRole("button", { name: "Save" }));
    expect(screen.getByText("Excel register · D:\\AP\\register.xlsx · when done with no review, approved by a reviewer")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    const saved = await api.processDesign("supplier-invoices-ksa");
    expect(saved.checks.at(-1)).toMatchObject({ name: "Has a date", type: "required", params: { fields: ["invoice_date"] }, test: "invoice_date" });
    expect(saved.deliveries).toHaveLength(1);
    expect(saved.version).toBe(2);
  });

  it("filters document type fields and reference data rows", async () => {
    start(); await go("#/types");
    const fields = await screen.findByRole("region", { name: "Fields to read" });
    expect(within(fields).getByText("8 fields")).toBeTruthy();
    expect(within(fields).queryByLabelText("Kind")).toBeNull();               // filters only past 10 fields (28 Nov 2026)
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
    expect(screen.queryByLabelText("Engine B")).toBeNull();                    // DA-1: one reading as live, engines are advanced
    fireEvent.click(screen.getByRole("button", { name: "Show advanced settings" }));
    fireEvent.click(screen.getByRole("button", { name: "Compare two" }));
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
    fireEvent.click(screen.getByRole("button", { name: /^How to use this page/ }));   // closed until asked for (DA-1)
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

  it("keeps how to use each page closed behind its link, and remembers it open per page (DA-1)", async () => {
    start();
    await go("#/inbox");
    const link = await screen.findByRole("button", { name: /^How to use this page/ });
    expect(screen.queryByRole("region", { name: "How to use this page" })).toBeNull();   // closed on the first visit too
    expect(link.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(link);
    const help = screen.getByRole("region", { name: "How to use this page" });
    expect(within(help).getAllByRole("listitem")[0].textContent).toContain("Status column");
    await go("#/engines");
    expect(screen.queryByRole("region", { name: "How to use this page" })).toBeNull();   // each page on its own
    await go("#/inbox");
    expect(screen.getByRole("region", { name: "How to use this page" })).toBeTruthy();   // left open on this page
    fireEvent.click(screen.getByRole("button", { name: "Close how to use this page" }));
    expect(screen.queryByRole("region", { name: "How to use this page" })).toBeNull();
    expect(screen.getByRole("button", { name: /^How to use this page/ })).toBeTruthy();   // the link stays
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
    fireEvent.click(screen.getByRole("button", { name: /^Advanced/ }));   // field names and learned labels, folded (DA-13)
    expect(within(fields).getByText("po_number")).toBeTruthy();
    fireEvent.click(within(fields).getByRole("button", { name: 'Forget "Inv #"' }));
    expect(screen.getByText("Unsaved changes")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save type" }));
    await waitFor(async () => {
      const t = (await api.docTypes()).find((x) => x.id === "supplier-invoice")!;
      expect(t.fields.at(-1)).toMatchObject({ key: "po_number", label: "PO number", aliases: ["Purchase order", "PO No"], required: true });
      expect(t.fields[0].learned).toEqual([]);
    });
  });

  it("offers common formats for a field's format check, says what each accepts, and keeps an own pattern", async () => {
    const api = start(); await go("#/types");
    const fields = await screen.findByRole("region", { name: "Fields to read" });
    fireEvent.click(screen.getByRole("button", { name: /^Advanced/ }));   // the pattern column and field names
    fireEvent.click(within(fields).getByRole("button", { name: "Add field" }));
    const dialog = await screen.findByRole("dialog", { name: "Add field" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Due date" } });
    const check = within(dialog).getByLabelText(/^Format check/);
    expect(choicesOf(check).map((o) => o.label)).toEqual(["No check", ...FORMATS.map((f) => f.label), "Own pattern…"]);
    expect(within(dialog).getByText("Any value is accepted.")).toBeTruthy();
    expect(within(dialog).queryByLabelText(/^Own pattern/)).toBeNull();     // no hand-typed pattern unless asked for
    choose(within(dialog).getByLabelText(/^Format check/), "date-dmy");
    expect(within(dialog).getByText(/^Accepts: Day, month and year with slashes/)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(fields).getByText("Date dd/mm/yyyy")).toBeTruthy();         // the table names the format
    fireEvent.click(screen.getByRole("button", { name: "Save type" }));
    await waitFor(async () => {
      const t = (await api.docTypes()).find((x) => x.id === "supplier-invoice")!;
      expect(t.fields.at(-1)).toMatchObject({ key: "due_date", regex: "^(?:0[1-9]|[12]\\d|3[01])/(?:0[1-9]|1[0-2])/\\d{4}$" });
    });
    // a pattern not in the list opens as an own pattern, in its text box
    const row = within(fields).getByText("invoice_number").closest("tr")!;
    fireEvent.pointerDown(within(row).getByRole("button", { name: /^More for / }), { button: 0, ctrlKey: false });   // the row's ⋮ menu
    fireEvent.click(await screen.findByRole("menuitem", { name: "Edit" }));
    const edit = await screen.findByRole("dialog", { name: "Edit field" });
    expect(valueOf(within(edit).getByLabelText(/^Format check/))).toBe("own");
    const own = within(edit).getByLabelText(/^Own pattern/) as HTMLInputElement;
    expect(own.value).toBe("INV-\\d{4}-\\d+");
    fireEvent.change(own, { target: { value: "(" } });
    expect(within(edit).getByText("This is not a valid pattern.")).toBeTruthy();
    expect((within(edit).getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(true);
    choose(within(edit).getByLabelText(/^Format check/), "");
    expect(within(edit).queryByLabelText(/^Own pattern/)).toBeNull();
    expect((within(edit).getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("has common format patterns that accept their examples and refuse near misses", () => {
    for (const f of FORMATS) {
      const re = new RegExp(f.pattern);
      for (const v of f.ok) expect(re.test(v), `${f.id} accepts ${v}`).toBe(true);
      for (const v of f.bad) expect(re.test(v), `${f.id} refuses ${v}`).toBe(false);
    }
  });

  it("teaches a new document type from samples, checked before it is saved", async () => {
    const api = start(); await go("#/types");
    fireEvent.click(await screen.findByRole("button", { name: "Teach from samples" }));
    const dialog = await screen.findByRole("dialog", { name: "Teach from samples" });
    fireEvent.change(within(dialog).getByLabelText("Choose samples"), { target: { files: [new File(["x"], "a.pdf"), new File(["y"], "b.pdf")] } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Read samples" }));
    expect(await screen.findByText("Not saved yet")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Type name"), { target: { value: "Pro forma invoice" } });
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: "An invoice sent before the goods, to ask for payment in advance." } });
    fireEvent.click(screen.getByRole("button", { name: "Create type" }));
    await waitFor(async () => expect((await api.docTypes()).some((t) => t.id === "pro-forma-invoice" && t.fields.length === 3)).toBe(true));
  });

  it("sets up a process's email inbox in step 2, Document ingestion", async () => {
    const api = start(await mailboxApi()); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    await addWay(intake, "Own mailbox settings");
    fireEvent.change(within(intake).getByLabelText("Mailbox kind"), { target: { value: "email" } });
    fireEvent.change(within(intake).getByLabelText("Mail server"), { target: { value: "imap.example.com" } });
    fireEvent.change(within(intake).getByLabelText("User name"), { target: { value: "invoices@example.com" } });
    await within(intake).findByRole("option", { name: "mail · invoices · password" });   // DA-1: a saved value, picked
    fireEvent.change(within(intake).getByLabelText("Password"), { target: { value: "mail/invoices/password" } });
    fireEvent.change(within(intake).getByLabelText("Subject contains (optional)"), { target: { value: "invoice" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-ksa")).settings.email).toEqual({ enabled: true, kind: "email", host: "imap.example.com",
      username: "invoices@example.com", password_ref: "mail/invoices/password", subject_contains: "invoice" });
  });

  it("signs a Microsoft 365 inbox in with Microsoft: a code to type, then who is signed in (DA-1)", async () => {
    const api = start(await mailboxApi()); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    await addWay(intake, "Own mailbox settings");
    expect((within(intake).getByLabelText("Mailbox kind") as HTMLSelectElement).value).toBe("outlook");
    expect(within(intake).queryByLabelText("Directory (tenant) id")).toBeNull();          // your own app is advanced
    fireEvent.click(await within(intake).findByRole("button", { name: "Sign in with Microsoft" }));
    const dialog = await screen.findByRole("dialog", { name: "Sign in with Microsoft" });
    expect(await within(dialog).findByText("SAMPLE-01")).toBeTruthy();
    expect(within(dialog).getByRole("link", { name: "microsoft.com/devicelogin" }).getAttribute("href")).toBe("https://microsoft.com/devicelogin");
    expect(await within(intake).findByText("Signed in as invoices@example.com", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.queryByRole("dialog", { name: "Sign in with Microsoft" })).toBeNull();
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-ksa")).settings.email).toMatchObject({ enabled: true, kind: "outlook", ms_signin: "ms" + "0".repeat(24), ms_account: "invoices@example.com" });
    // your own Microsoft app stays under Show advanced settings
    fireEvent.click(within(intake).getByRole("button", { name: "Show advanced settings" }));
    expect(within(intake).getByRole("group", { name: "Your own Microsoft app" })).toBeTruthy();
    expect(within(intake).getByText(/Not used while signed in with Microsoft/)).toBeTruthy();
    expect(within(intake).getByLabelText("Client secret").tagName).toBe("SELECT");          // a saved value, picked
    fireEvent.click(within(intake).getByRole("button", { name: "Sign out" }));
    expect(await within(intake).findByRole("button", { name: "Sign in with Microsoft" })).toBeTruthy();
  }, 10000);

  it("chooses the watched folder from the folders processes may use (DA-1)", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    await addWay(intake, "Watched folder");                                         // step 2's table of ways in (27 Nov 2026)
    const field = await within(intake).findByLabelText("Folder path") as HTMLInputElement;
    await waitFor(() => expect(field.readOnly).toBe(true));                       // a list exists: choose, do not type
    fireEvent.click(within(intake).getByRole("button", { name: "Browse for folder path" }));
    const picker = await screen.findByRole("dialog", { name: "Choose the watched folder" });
    const allowed = within(picker).getByRole("group", { name: "Allowed folders" });
    expect(within(allowed).getAllByRole("button").map((b) => b.textContent)).toEqual(["Scans\\\\scan01\\scans", "Accounts payableD:\\AP"]);
    fireEvent.click(await within(picker).findByRole("button", { name: "Open invoices" }));
    const riyadh = await within(picker).findByRole("button", { name: "riyadh" });
    expect(within(picker).getAllByRole("row").slice(1).map((r) => r.textContent)).toEqual(["20250None", "jeddah3Supplier credit notes", "riyadh12None"]);
    fireEvent.click(riyadh);
    const chosen = within(picker).getByText(/^Chosen:/).parentElement!;
    expect(chosen.textContent).toContain("\\\\scan01\\scans\\invoices\\riyadh");
    expect(await within(picker).findByText("DocFlow can read and move files here")).toBeTruthy();
    expect(within(picker).getByText(/12 files waiting/)).toBeTruthy();
    fireEvent.click(within(picker).getByRole("button", { name: "jeddah" }));
    expect(within(picker).getByText(/Already watched by Supplier credit notes/)).toBeTruthy();
    // a new folder inside, chosen at once
    fireEvent.click(within(picker).getByRole("button", { name: "New folder" }));
    fireEvent.change(within(picker).getByLabelText("New folder name"), { target: { value: "returns" } });
    fireEvent.click(within(picker).getByRole("button", { name: "Make folder" }));
    await waitFor(() => expect(within(picker).getByText(/^Chosen:/).parentElement!.textContent).toContain("invoices\\returns"));
    fireEvent.click(within(picker).getByRole("button", { name: "Use this folder" }));
    expect(field.value).toBe("\\\\scan01\\scans\\invoices\\returns");
    fireEvent.click(screen.getAllByRole("button", { name: "Save draft" })[0]);
    await screen.findByRole("button", { name: "Saved" });
    expect((await api.processDesign("supplier-invoices-ksa")).settings.inputFolder).toBe("\\\\scan01\\scans\\invoices\\returns");
    // a folder outside the list says so; typing stays one click away
    fireEvent.click(within(intake).getByRole("button", { name: "Type the path" }));
    fireEvent.change(within(intake).getByLabelText("Folder path"), { target: { value: "\\\\old\\scans" } });
    fireEvent.click(within(intake).getByRole("button", { name: "Choose from the folders processes may use" }));
    expect(within(intake).getByText(/Not one of the folders processes may use/)).toBeTruthy();
    fireEvent.click(within(intake).getByRole("button", { name: "Clear" }));
    expect((within(intake).getByLabelText("Folder path") as HTMLInputElement).value).toBe("");
  });

  it("takes a process's emails from mail accounts set up once, by rules (MA-4)", async () => {
    const api = start(); await go("#/processes/supplier-invoices-ksa/intake");
    const mail = await screen.findByRole("region", { name: "Mail accounts" });
    expect(await within(mail).findByText("AP invoices mailbox")).toBeTruthy();
    expect(within(mail).getByText(/from @acme.com · has a PDF file · 18 so far/)).toBeTruthy();
    fireEvent.click(within(mail).getByRole("button", { name: "Add a mail account" }));
    const d = await screen.findByRole("dialog", { name: "Add a mail account" });
    expect((within(d).getByLabelText("After reading the email") as HTMLSelectElement).disabled).toBe(true);   // AP invoices mailbox may not change its mailbox
    fireEvent.change(within(d).getByLabelText("Mail account"), { target: { value: "mail_2" } });
    fireEvent.change(within(d).getByLabelText("After reading the email"), { target: { value: "move" } });
    expect((within(d).getByLabelText("Folder") as HTMLInputElement).value).toBe("Processed");
    expect((within(d).getByLabelText("Rule name") as HTMLInputElement).value).toBe("Supplier invoices KSA");
    expect((within(d).getByLabelText("condition 1 value") as HTMLInputElement).value).toBe("pdf, image, xml");    // attachments DocFlow reads, by default
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Try on the latest emails" })); });
    expect(await within(d).findByText("3 taken by an earlier rule")).toBeTruthy();
    await act(async () => { fireEvent.click(within(d).getByRole("button", { name: "Save" })); });
    await waitFor(async () => expect((await api.processMailRules("supplier-invoices-ksa")).map((r) => r.account_name)).toEqual(["AP invoices mailbox", "Supplier Gmail"]));
    expect((await api.processMailRules("supplier-invoices-ksa"))[1].after_read).toBe("move:Processed");
    expect(await within(mail).findByText("Supplier Gmail")).toBeTruthy();
  });

  it("types the watched folder while no folders are listed", async () => {
    const api = new SampleApi();
    await api.saveAllowedFolders("");
    start(api); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    await act(async () => { await api.folderRoots(); });
    await addWay(intake, "Watched folder");
    const field = within(intake).getByLabelText("Folder path") as HTMLInputElement;
    expect(field.readOnly).toBe(false);
    expect(within(intake).queryByRole("button", { name: "Browse for folder path" })).toBeNull();
    fireEvent.change(field, { target: { value: "\\\\scan01\\ksa" } });
    expect(field.value).toBe("\\\\scan01\\ksa");
  });

  it("says when Sign in with Microsoft is not set up on the server", async () => {
    const api = new SampleApi();
    api.msSignInReady = async () => false;
    start(await mailboxApi(api)); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    await addWay(intake, "Own mailbox settings");
    expect(await within(intake).findByText(/Sign in with Microsoft is not set up on this server/)).toBeTruthy();
    expect(within(intake).queryByRole("button", { name: "Sign in with Microsoft" })).toBeNull();
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
    const upload = await screen.findByRole("region", { name: "Try it" });       // the upload box and the last documents (27 Nov 2026)
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
    fireEvent.click(screen.getByRole("button", { name: /^How to use this page/ }));   // closed until asked for (DA-1)
    expect(within(screen.getByRole("region", { name: "How to use this page" })).getByText(/in charts/)).toBeTruthy();
    cleanup();
    await api.saveRouting({ ...(await api.engines()).routing, cloudOptIn: true });
    start(api); await go("#/overview/dashboard");
    expect(await screen.findByText("Cloud cost this month")).toBeTruthy();
  });

  it("offers the administrator's languages for a document type, marking those not installed", async () => {
    const api = start(); await go("#/types");
    await screen.findByRole("region", { name: "Supplier invoice" });
    fireEvent.click(screen.getByRole("button", { name: /^Advanced/ }));       // DA-13: languages are advanced, a short pick
    const pick = (await screen.findByRole("button", { name: /^Languages: / }));
    expect(pick.textContent).toContain("English, Arabic");
    fireEvent.click(pick);
    let pop = screen.getByRole("dialog", { name: "Languages" });
    await waitFor(() => expect(within(pop).getAllByRole("checkbox").map((c) => c.closest("label")!.textContent!.trim())).toEqual(["English", "Arabic"]));
    fireEvent.click(within(pop).getByRole("button", { name: "Done" }));

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
    await screen.findByRole("region", { name: "Supplier invoice" });
    fireEvent.click(screen.getByRole("button", { name: /^Advanced/ }));
    fireEvent.click(await screen.findByRole("button", { name: /^Languages: / }));
    pop = screen.getByRole("dialog", { name: "Languages" });
    await waitFor(() => expect(within(pop).getAllByRole("checkbox")).toHaveLength(4));
    expect(within(pop).getByText(/Bengali/).textContent).toContain("not installed");
    fireEvent.click(within(pop).getByLabelText(/Urdu/));
    fireEvent.click(within(pop).getByRole("button", { name: "Done" }));
    fireEvent.click(screen.getByRole("button", { name: "Save type" }));
    await waitFor(async () => expect((await api.docTypes()).find((t) => t.id === "supplier-invoice")!.languages).toEqual(["en", "ar", "ur"]));
  });

  it("keeps a document type's language that is no longer offered", async () => {
    const api = new SampleApi();
    await api.saveOfferedLanguages(["en"]);
    start(api); await go("#/types");
    await screen.findByRole("region", { name: "Supplier invoice" });
    fireEvent.click(screen.getByRole("button", { name: /^Advanced/ }));
    fireEvent.click(await screen.findByRole("button", { name: /^Languages: / }));
    const pop = screen.getByRole("dialog", { name: "Languages" });
    await waitFor(() => expect(within(pop).getByText(/not offered/)).toBeTruthy());
    expect((within(pop).getByLabelText(/Arabic/) as HTMLInputElement).checked).toBe(true);
  });
});
