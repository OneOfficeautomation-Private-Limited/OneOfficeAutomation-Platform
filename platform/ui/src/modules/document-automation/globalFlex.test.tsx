import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { SampleApi, estimate, money, parseAmount, type PriceList } from "./api";
import { App } from "./App";
import { AccuracyView } from "./accuracy";
import { freeKey, keyOf, list } from "./DocTypes";
import { FORMATS } from "./formats";
import { pageList } from "./splitDialog";
import { localCurrency } from "./templates";
import { ChoiceOrOwn, duration } from "./ui";

/** Global and flexible (25 Nov 2026): names in any script, own values beside every list, numbers and money in any style. */
beforeEach(() => { location.hash = ""; });
afterEach(() => { cleanup(); localStorage.clear(); });
const go = async (hash: string) => { await act(async () => { location.hash = hash; window.dispatchEvent(new HashChangeEvent("hashchange")); }); };
const start = (api = new SampleApi()) => { render(<App api={api} />); return api; };

describe("names, lists and numbers of every country", () => {
  it("makes field names from labels in any script, never the same twice", () => {
    expect(keyOf("Invoice no")).toBe("invoice_no");
    expect(keyOf("رقم الفاتورة")).toBe("رقم_الفاتورة");
    expect(keyOf("発行日")).toBe("発行日");
    expect(keyOf("2nd address")).toBe("f_2nd_address");
    expect(freeKey("total", ["total", "total_2"])).toBe("total_3");
    expect(list("a, b، c，d; e")).toEqual(["a", "b", "c", "d", "e"]);
  });

  it("reads amounts typed in any style", () => {
    expect(parseAmount("1,000")).toBe(1000);
    expect(parseAmount("1.234,50")).toBe(1234.5);
    expect(parseAmount("1 234,5")).toBe(1234.5);
    expect(parseAmount("1'250.00")).toBe(1250);
    expect(parseAmount("12.5")).toBe(12.5);
    expect(Number.isNaN(parseAmount("abc"))).toBe(true);
  });

  it("writes money in the price currency and estimates with DocFlow's own prices", () => {
    const prices: PriceList = { currency: "EUR", ocr: { "azure-di": 1.4 }, extract: { "claude-haiku-5-5": 0.7 } };
    const e = estimate(10000, 0.2, 0.3, "azure-di", "claude-haiku-5-5", prices);
    expect([e.ocrRate, e.fieldRate, e.total]).toEqual([1.4, 0.7, 4.9]);
    expect(money(4.9, "EUR")).toMatch(/4[.,]90/);
    expect(money(1, "NOT-A-CODE")).toBe("NOT-A-CODE 1.00");
  });

  it("chooses sample currencies from the viewer's region", () => {
    expect(localCurrency("de-DE")).toBe("EUR");
    expect(localCurrency("en-IN")).toBe("INR");
    expect(localCurrency("ja")).toBe("JPY");
    expect(localCurrency("xx")).toBe("USD");
  });

  it("groups format checks by kind and country, each with examples", () => {
    const groups = [...new Set(FORMATS.map((f) => f.group))];
    expect(groups.slice(0, 3)).toEqual(["General", "Amounts", "Dates"]);
    expect(groups).toEqual(expect.arrayContaining(["European Union", "India", "United States", "Saudi Arabia"]));
    for (const f of FORMATS) expect(f.ok.length, f.id).toBeGreaterThan(0);
  });

  it("reads page lists for splitting", () => {
    expect(pageList("1-3, 5")).toEqual([1, 2, 3, 5]);
    expect(pageList("3-1")).toBeNull();
    expect(pageList("a")).toBeNull();
  });

  it("says durations in words", () => {
    expect([duration(30), duration(60), duration(300), duration(12 * 3600), duration(2 * 86400)]).toEqual(["30 seconds", "1 minute", "5 minutes", "12 hours", "2 days"]);
  });
});

describe("own values beside every list", () => {
  function Harness({ start: first }: { start: number }) {
    const [v, setV] = useState(first);
    return <><ChoiceOrOwn id="x" ariaLabel="Interval" value={v} onChange={setV} options={[30, 60]} label={duration} unit="seconds" /><output>{v}</output></>;
  }

  it("shows a saved value that is not a choice, and takes a typed one", () => {
    render(<Harness start={120} />);
    const sel = screen.getByLabelText("Interval") as HTMLSelectElement;
    expect(sel.value).toBe("120");
    expect(Array.from(sel.options).map((o) => o.text)).toEqual(["30 seconds", "1 minute", "2 minutes (saved)", "Other…"]);
    fireEvent.change(sel, { target: { value: "own" } });
    fireEvent.change(screen.getByLabelText("Own value in seconds"), { target: { value: "45" } });
    expect(document.querySelector("output")?.textContent).toBe("45");
  });
});

describe("the screens", () => {
  it("adds a field read by AI from its plain-words question, with choices and their meanings", async () => {
    const api = start(); await go("#/types");
    const fields = await screen.findByRole("region", { name: "Fields to read" });
    fireEvent.click(within(fields).getByRole("button", { name: "Add field" }));
    const dialog = await screen.findByRole("dialog", { name: "Add field" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Kind of expense" } });
    fireEvent.change(within(dialog).getByLabelText(/^How it is read/), { target: { value: "pick" } });
    fireEvent.change(within(dialog).getByLabelText(/^Choices/), { target: { value: "Travel, Meals" } });
    fireEvent.click(within(dialog).getByLabelText(/Keep other values too/));
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    const editor = screen.getByRole("region", { name: "Supplier invoice" });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(async () => {
      const t = (await api.docTypes()).find((x) => x.id === "supplier-invoice");
      expect(t?.fields.find((f) => f.key === "kind_of_expense")).toMatchObject({ method: "pick", choices: ["Travel", "Meals"], allowOther: true });
    });
  });

  it("lists every kind of field, Yes/No, Ticked and Signed among them", async () => {
    start(); await go("#/types");
    fireEvent.click(within(await screen.findByRole("region", { name: "Fields to read" })).getByRole("button", { name: "Add field" }));
    const type = within(await screen.findByRole("dialog", { name: "Add field" })).getByLabelText("Type") as HTMLSelectElement;
    expect(Array.from(type.options).map((o) => o.value)).toEqual(expect.arrayContaining(["Yes/No", "Ticked", "Signed", "Percentage", "Several choices", "Email", "Phone", "Address"]));
  });

  it("asks why a document is rejected, with an own reason allowed", async () => {
    const api = start(); await go("#/review/scan_0457");
    fireEvent.click(await screen.findByRole("button", { name: "Reject" }));
    const dialog = await screen.findByRole("dialog", { name: /^Reject/ });
    fireEvent.change(within(dialog).getByLabelText("Why"), { target: { value: "Other reason…" } });
    expect((within(dialog).getByRole("button", { name: "Reject" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText("Reason"), { target: { value: "Supplier closed" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(api.reviews).toHaveLength(1));
    expect(api.reviews[0]).toMatchObject({ documentId: "scan_0457", decision: "reject" });
  });

  it("shows how well a process reads and what each review level would let through", () => {
    render(<AccuracyView a={{ documents: 10, finished: 9, reviewed: 4, measured: 40, straightThrough: 0.5, reviewLevel: 0.85,
      fields: [{ name: "total", label: "Total", seen: 4, corrected: 1, correctedShare: 0.25 }],
      levels: [{ level: 0.8, straightThrough: 0.9, wrongPassed: 0.05, fields: 36 }, { level: 0.85, straightThrough: 0.8, wrongPassed: 0.0125, fields: 32 }] }} />);
    const card = screen.getByRole("region", { name: "How well it reads" });
    expect(within(card).getByText("50%")).toBeTruthy();
    expect(within(card).getByText("85% (now)")).toBeTruthy();
    expect(within(card).getAllByText("about 1 in 80").length).toBeGreaterThan(0);
    expect(within(card).getByText("25%")).toBeTruthy();
  });

  it("chooses which e-invoice standards a process reads, under a neutral switch", async () => {
    start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    expect(within(intake).getByRole("switch", { name: "Read e-invoice data first" })).toBeTruthy();
    expect(within(intake).queryByText(/ZATCA XML/)).toBeNull();
    expect(await within(intake).findByText(/Standards read: all that DocFlow knows/)).toBeTruthy();
  });
});


describe("the leftovers (25 Nov 2026)", () => {
  it("adds an e-mail delivery and a cloud storage delivery with a vault key", async () => {
    start(); await go("#/processes/supplier-invoices-ksa/deliver");
    fireEvent.click(await screen.findByRole("button", { name: "Add delivery" }));
    let d = await screen.findByRole("dialog", { name: "Add delivery" });
    fireEvent.change(within(d).getByLabelText("Send results to"), { target: { value: "email" } });
    fireEvent.change(within(d).getByLabelText("To"), { target: { value: "ap@example.com, role:finance" } });
    fireEvent.change(within(d).getByLabelText(/^Subject/), { target: { value: "{process}: {file}" } });
    fireEvent.click(within(d).getByRole("button", { name: "Save" }));
    expect(screen.getByText(/E-mail · ap@example.com, role:finance/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Add delivery" }));
    d = await screen.findByRole("dialog", { name: "Add delivery" });
    fireEvent.change(within(d).getByLabelText("Send results to"), { target: { value: "cloud_storage" } });
    fireEvent.change(within(d).getByLabelText("Where"), { target: { value: "s3" } });
    fireEvent.change(within(d).getByLabelText("Bucket"), { target: { value: "invoices" } });
    fireEvent.change(within(d).getByLabelText(/^Region/), { target: { value: "eu-central-1" } });
    fireEvent.change(within(d).getByLabelText(/^Secret key \(vault\)/), { target: { value: "store/s3/secret" } });
    fireEvent.click(within(d).getByRole("checkbox", { name: "The text and tables (Markdown)" }));
    fireEvent.click(within(d).getByRole("button", { name: "Save" }));
    expect(screen.getByText(/Cloud storage \(S3, Azure Blob, a web address\) · invoices/)).toBeTruthy();
  });

  it("sends a document that fails a check to another process, or tells people", async () => {
    start(); await go("#/processes/supplier-invoices-ksa/checks");
    fireEvent.click(within(await screen.findByRole("region", { name: "Validation" })).getByRole("button", { name: "Add check" }));
    const dialog = await screen.findByRole("dialog", { name: "Add check" });
    fireEvent.change(within(dialog).getByLabelText("If it fails"), { target: { value: "route" } });
    const save = within(dialog).getByRole("button", { name: "Save" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    const to = within(dialog).getByLabelText(/^Send it to/) as HTMLSelectElement;
    fireEvent.change(to, { target: { value: to.options[1].value } });
    expect(save.disabled).toBe(false);
    fireEvent.change(within(dialog).getByLabelText("If it fails"), { target: { value: "notify" } });
    expect(save.disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText(/^Tell/), { target: { value: "cfo@example.com" } });
    expect(save.disabled).toBe(false);
  });

  it("reads Gmail with an app password and POP3 inboxes", async () => {
    start(); await go("#/processes/supplier-invoices-ksa/intake");
    const intake = await screen.findByRole("region", { name: "Document ingestion" });
    fireEvent.click(within(intake).getByRole("checkbox", { name: /Email inbox/ }));
    fireEvent.change(within(intake).getByLabelText("Mailbox kind"), { target: { value: "gmail" } });
    expect(within(intake).getByLabelText(/^Gmail address/)).toBeTruthy();
    expect(within(intake).queryByLabelText(/^Mail server/)).toBeNull();
    fireEvent.change(within(intake).getByLabelText("Mailbox kind"), { target: { value: "pop3" } });
    expect((within(intake).getByLabelText(/^Port/) as HTMLInputElement).placeholder).toBe("995");
    expect(within(intake).getByText(/POP3 reads the inbox only/)).toBeTruthy();
    fireEvent.click(within(intake).getByRole("checkbox", { name: /People may upload documents here/ }));
    expect((within(intake).getByRole("checkbox", { name: /People may upload documents here/ }) as HTMLInputElement).checked).toBe(false);
  });
});
