import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { SampleApi, type ReviewTask } from "./api";
import { App } from "./App";
import { plainCheck } from "./Review";

/** A document like the one waiting on a real server (28 Nov 2026): most required fields not found, a check that could
 * not run, values not found on the page, empty optional fields and empty table rows, eight days late. */
class LateApi extends SampleApi {
  async reviewTask(documentId: string): Promise<ReviewTask> {
    const t = await super.reviewTask(documentId);
    const req = (id: string, label: string) => ({ id, label, value: "", confidence: 0, flagged: { reason: "Required, but not found on the document" } });
    return {
      ...t, overdueHours: 170, targetHours: 24, docType: "Invoice",
      documentReasons: ["Check failed: abs(subtotal + vat_amount - total) < 0.05 (TypeError)", "Document confidence 52% is under the 80% needed"],
      checks: [{ text: "abs(subtotal + vat_amount - total) < 0.05 (TypeError)", passed: false, detail: "check of the document type" }],
      fields: [req("invoice_number", "Invoice number"), req("invoice_date", "Invoice date"), req("subtotal", "Subtotal"), req("total", "Total"),
        { id: "vat_amount", label: "VAT amount", value: "150", confidence: 90, place: { page: 1, grounded: false } },
        { id: "supplier_name", label: "Supplier", value: "ACME", confidence: 90, place: { page: 1, grounded: true } },
        { id: "due_date", label: "Due date", value: "", confidence: 0 }],
      tables: [{ id: "line_items", label: "Line items", columns: [{ name: "description", label: "Description" }, { name: "amount", label: "Amount" }],
        rows: [{ description: "", amount: "" }, { description: "", amount: "" }], confidence: null }],
    } as ReviewTask;
  }
}

describe("Review (walkthrough 28 Nov 2026)", () => {
  afterEach(cleanup);

  it("says a check that could not run in plain words", () => {
    const fields = [{ id: "subtotal", label: "Subtotal" }, { id: "vat_amount", label: "VAT amount" }, { id: "total", label: "Total" }];
    expect(plainCheck("Check failed: abs(subtotal + vat_amount - total) < 0.05 (TypeError)", fields, { subtotal: "", vat_amount: "150", total: "" }))
      .toEqual({ text: "Could not check Subtotal, VAT amount and Total: Subtotal and Total are empty", formula: "abs(subtotal + vat_amount - total) < 0.05" });
    expect(plainCheck("Total more than 50,000", fields, {})).toEqual({ text: "Total more than 50,000" });
  });

  it("groups what needs a look, what was read and what is not there; says it is late and may be another type", async () => {
    render(<App api={new LateApi()} />);
    window.location.hash = "#/review/scan_0457";
    window.dispatchEvent(new HashChangeEvent("hashchange"));
    const fields = await screen.findByRole("region", { name: "Fields" });
    expect(screen.getByText(/^Overdue by 7\.1 days$/)).toBeTruthy();
    expect(within(fields).getByText("5 fields need you")).toBeTruthy();                      // 4 required + 1 not found on the page
    expect(within(fields).getByText("Not found on the page: check it against the document")).toBeTruthy();
    const note = within(fields).getByRole("note", { name: "It may be another kind of document" });
    expect(note.textContent).toContain("This may not be an Invoice: 4 required fields were not found on it.");
    expect(within(note).getByRole("button", { name: "Change document type" })).toBeTruthy();
    const why = within(fields).getByRole("note", { name: "Why this document needs review" });
    expect(why.textContent).toContain("Could not check Subtotal, VAT amount and Total: Subtotal and Total are empty");
    expect(within(fields).getByText("NOT ON THE DOCUMENT")).toBeTruthy();
    expect(within(fields).getByLabelText("Due date")).toBeTruthy();
    expect(within(fields).getByText("No line items were read.")).toBeTruthy();
    fireEvent.click(within(fields).getByRole("button", { name: "Add a row" }));
    expect(within(fields).queryByText("No line items were read.")).toBeNull();
  });
});
