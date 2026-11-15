/**
 * DA-1: ready-made starting points for a new process, for people without IT experience. A template picks the
 * built-in document type (matched by name, so it works with DocFlow's own ids), suggests a name, and has a made-up
 * sample document that "Try it with a sample" reads in the Test lab (nothing is saved).
 */
export interface ProcessTemplate {
  id: string; name: string; does: string; suggestedName: string;
  /** "Try it with a sample …" */
  sampleName: string;
  /** Matches the built-in document type's name. */
  type: RegExp;
  /** The made-up sample's lines: a heading, then label and value lines. */
  sample: string[];
}

export const TEMPLATES: ProcessTemplate[] = [
  { id: "invoices", sampleName: "invoice", name: "Supplier invoices", suggestedName: "Supplier invoices", type: /invoice/i,
    does: "Reads supplier, invoice number, dates, totals and VAT; checks the totals add up. Results download as Excel.",
    sample: ["ACME Trading Co. LLC", "TAX INVOICE", "Invoice Number: INV-2026-0099", "Invoice Date: 2026-11-14", "Bill To: Example Customer",
      "Subtotal: 10,000.00", "VAT 15%: 1,500.00", "Total: 11,500.00 SAR"] },
  { id: "receipts", sampleName: "receipt", name: "Receipts for expense claims", suggestedName: "Expense receipts", type: /receipt/i,
    does: "Reads shop, date, total and currency; a person checks anything unclear.",
    sample: ["City Coffee House", "RECEIPT", "Date: 2026-11-14", "Total: 42.50 SAR", "Paid by card"] },
  { id: "statements", sampleName: "bank statement", name: "Bank statements", suggestedName: "Bank statements", type: /bank/i,
    does: "Reads bank, IBAN, period and balances; checks the IBAN.",
    sample: ["Example National Bank", "ACCOUNT STATEMENT", "IBAN: SA0380000000608010167519", "From: 2026-10-01", "To: 2026-10-31",
      "Opening Balance: 25,000.00", "Closing Balance: 31,250.00"] },
  { id: "orders", sampleName: "purchase order", name: "Purchase orders", suggestedName: "Purchase orders", type: /purchase/i,
    does: "Reads PO number, supplier, lines and totals.",
    sample: ["Example Buyer Co.", "PURCHASE ORDER", "PO Number: PO-5521", "Date: 2026-11-10", "Supplier: ACME Trading Co. LLC", "Total: 8,000.00 SAR"] },
  { id: "ids", sampleName: "ID document", name: "ID documents", suggestedName: "ID documents", type: /\bid\b|identity/i,
    does: "Reads name, number and expiry date.",
    sample: ["KINGDOM OF SAUDI ARABIA", "NATIONAL ID", "Name: Example Person", "ID Number: 1012345678", "Expiry Date: 2030-05-01"] },
];

/** The built-in document type a template uses, if DocFlow has it. */
export const typeFor = (t: ProcessTemplate, types: { id: string; name: string }[]) => types.find((x) => t.type.test(x.name))?.id ?? "";

/** The template's made-up sample as a PNG drawn in the browser (a text file where there is no canvas, as in tests). */
export async function sampleFile(t: ProcessTemplate): Promise<File> {
  const name = `sample-${t.id}.png`;
  const canvas = typeof document !== "undefined" ? document.createElement("canvas") : null;
  const g = canvas?.getContext?.("2d") ?? null;
  if (!canvas || !g) return new File([t.sample.join("\n")], name, { type: "image/png" });
  canvas.width = 1400; canvas.height = 260 + t.sample.length * 66;
  g.fillStyle = "#ffffff"; g.fillRect(0, 0, canvas.width, canvas.height);
  g.fillStyle = "#000000";
  t.sample.forEach((line, i) => { g.font = i === 0 ? "bold 56px Arial" : "44px Arial"; g.fillText(line, 80, 120 + i * 66 + (i ? 30 : 0)); });
  const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, "image/png"));
  return new File([blob ?? new Blob([t.sample.join("\n")])], name, { type: "image/png" });
}
