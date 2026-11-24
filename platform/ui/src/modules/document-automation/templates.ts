/**
 * DA-1: ready-made starting points for a new process, for people without IT experience. A template picks the
 * built-in document type (by DocFlow's id, else by name), suggests a name, and has a made-up sample document that
 * "Try it with a sample" reads in the Test lab (nothing is saved). Samples are written in the viewer's own
 * currency and number style (from the browser's language and region), so they look like the documents they get.
 */
export interface ProcessTemplate {
  id: string; name: string; does: string; suggestedName: string;
  /** "Try it with a sample …" */
  sampleName: string;
  /** DocFlow's id of the built-in type, and its name as a fallback (a type renamed or made before ids were stable). */
  typeId: string;
  type: RegExp;
  /** The made-up sample's lines: a heading, then label and value lines. */
  sample: string[];
}

/** The viewer's currency, from the region of the browser's language (en-GB → GBP); US dollars when unknown. */
export function localCurrency(locale = typeof navigator !== "undefined" ? navigator.language : "en-US"): string {
  const region = (locale.split(/[-_]/)[1] ?? "").toUpperCase();
  const lang = locale.split(/[-_]/)[0].toLowerCase();
  const byRegion: Record<string, string> = {
    US: "USD", GB: "GBP", IN: "INR", SA: "SAR", AE: "AED", QA: "QAR", KW: "KWD", BH: "BHD", OM: "OMR", EG: "EGP", JP: "JPY", CN: "CNY",
    HK: "HKD", SG: "SGD", AU: "AUD", NZ: "NZD", CA: "CAD", CH: "CHF", ZA: "ZAR", NG: "NGN", KE: "KES", BR: "BRL", MX: "MXN", AR: "ARS",
    CL: "CLP", CO: "COP", PE: "PEN", SE: "SEK", NO: "NOK", DK: "DKK", PL: "PLN", CZ: "CZK", HU: "HUF", RO: "RON", TR: "TRY", IL: "ILS",
    KR: "KRW", TW: "TWD", TH: "THB", MY: "MYR", ID: "IDR", PH: "PHP", VN: "VND", PK: "PKR", BD: "BDT", LK: "LKR",
  };
  const euro = ["DE", "FR", "ES", "IT", "NL", "BE", "AT", "PT", "IE", "FI", "GR", "SK", "SI", "LT", "LV", "EE", "LU", "MT", "CY", "HR"];
  if (byRegion[region]) return byRegion[region];
  if (euro.includes(region) || ["de", "fr", "es", "it", "nl", "pt", "fi", "el"].includes(lang)) return "EUR";
  return ({ ja: "JPY", zh: "CNY", hi: "INR", ko: "KRW", ar: "USD" } as Record<string, string>)[lang] ?? "USD";
}

/** An amount as the viewer writes it (1,234.50 / 1.234,50 / 1 234,50...). */
const amount = (v: number, currency: string) => {
  try { return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(v); } catch { return `${v.toFixed(2)} ${currency}`; }
};

/** Today's date as the viewer writes it, and a date some days away. */
const day = (offset = 0) => new Date(Date.now() + offset * 86400000).toLocaleDateString();

function build(): ProcessTemplate[] {
  const c = localCurrency();
  const a = (v: number) => amount(v, c);
  return [
    { id: "invoices", typeId: "invoice", sampleName: "invoice", name: "Supplier invoices", suggestedName: "Supplier invoices", type: /invoice/i,
      does: "Reads supplier, invoice number, dates, totals and tax; checks the totals add up. Results download as Excel.",
      sample: ["ACME Trading Co.", "INVOICE", "Invoice Number: INV-2026-0099", `Invoice Date: ${day()}`, "Bill To: Example Customer",
        `Subtotal: ${a(10000)}`, `Tax 10%: ${a(1000)}`, `Total: ${a(11000)}`] },
    { id: "credit-notes", typeId: "credit_note", sampleName: "credit note", name: "Credit notes", suggestedName: "Supplier credit notes", type: /credit/i,
      does: "Reads the credit note number, the invoice it corrects, the supplier and the amount.",
      sample: ["ACME Trading Co.", "CREDIT NOTE", "Credit Note No: CN-2026-0012", `Date: ${day()}`, "Reference: INV-2026-0099", `Total Credit: ${a(550)}`] },
    { id: "receipts", typeId: "receipt", sampleName: "receipt", name: "Receipts for expense claims", suggestedName: "Expense receipts", type: /receipt/i,
      does: "Reads shop, date, total and currency; a person checks anything unclear.",
      sample: ["City Coffee House", "RECEIPT", `Date: ${day()}`, `Total: ${a(42.5)}`, "Paid by card"] },
    { id: "statements", typeId: "bank_statement", sampleName: "bank statement", name: "Bank statements", suggestedName: "Bank statements", type: /bank/i,
      does: "Reads bank, account number or IBAN, period and balances.",
      sample: ["Example National Bank", "ACCOUNT STATEMENT", "Account No: 0123456789", `From: ${day(-30)}`, `To: ${day()}`,
        `Opening Balance: ${a(25000)}`, `Closing Balance: ${a(31250)}`] },
    { id: "orders", typeId: "purchase_order", sampleName: "purchase order", name: "Purchase orders", suggestedName: "Purchase orders", type: /purchase/i,
      does: "Reads PO number, supplier, lines and totals.",
      sample: ["Example Buyer Co.", "PURCHASE ORDER", "PO Number: PO-5521", `Date: ${day()}`, "Vendor: ACME Trading Co.", `Total: ${a(8000)}`] },
    { id: "deliveries", typeId: "delivery_note", sampleName: "delivery note", name: "Delivery notes", suggestedName: "Goods received", type: /delivery/i,
      does: "Reads the delivery number, date, order number and the items delivered.",
      sample: ["ACME Trading Co.", "DELIVERY NOTE", "Delivery No: DN-7781", `Delivery Date: ${day()}`, "Order No: PO-5521"] },
    { id: "utilities", typeId: "utility_bill", sampleName: "utility bill", name: "Utility bills", suggestedName: "Utility bills", type: /utility/i,
      does: "Reads the provider, account number, bill date, due date and amount due.",
      sample: ["City Power & Water", "ELECTRICITY BILL", "Account No: 55012-889", `Bill Date: ${day()}`, `Due Date: ${day(14)}`, `Amount Due: ${a(184.2)}`] },
    { id: "ids", typeId: "id_document", sampleName: "ID document", name: "ID documents", suggestedName: "ID documents", type: /\bid\b|identity/i,
      does: "Reads name, number, nationality and expiry date.",
      sample: ["IDENTITY CARD", "Name: Example Person", "ID Number: X1234567", "Nationality: Example", `Expiry Date: ${day(1500)}`] },
    { id: "contracts", typeId: "contract", sampleName: "contract", name: "Contracts", suggestedName: "Contracts", type: /contract|agreement/i,
      does: "AI reading names the parties and dates and sums up the contract in a sentence.",
      sample: ["SERVICE AGREEMENT", "This agreement is between Example Buyer Co. and ACME Trading Co.", `Effective Date: ${day()}`, `End Date: ${day(365)}`] },
    { id: "other", typeId: "other", sampleName: "document", name: "Any other document", suggestedName: "Other documents", type: /other/i,
      does: "Keeps the text, the labelled values and the tables of any document, with no fixed fields; AI reading adds a title and summary.",
      sample: ["MEETING NOTE", `Date: ${day()}`, "Subject: Office move", "Owner: Facilities team"] },
  ];
}

export const TEMPLATES: ProcessTemplate[] = build();

/** The built-in document type a template uses, if DocFlow has it: by id, else by name. */
export const typeFor = (t: ProcessTemplate, types: { id: string; name: string }[]) =>
  types.find((x) => x.id === t.typeId)?.id ?? types.find((x) => t.type.test(x.name))?.id ?? "";

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
