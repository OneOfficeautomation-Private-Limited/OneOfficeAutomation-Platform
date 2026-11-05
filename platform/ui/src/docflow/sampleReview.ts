import type { Box, Place, ReviewPage, ReviewTask, RowPlace } from "./api";

/**
 * The sample review task (SampleApi): a two-page Red Sea Print House invoice drawn as SVG, so the review screen shows
 * a real page without a server. Every value is placed from the same layout as its box, so the boxes sit on the text.
 */
const W = 1000, H = 1414;
type Item = { x: number; y: number; size: number; text: string; bold?: boolean; end?: boolean; key?: string };

/** Roughly how wide a line of text is (Arial): enough for a box around it. */
const width = (t: Item) => t.text.length * t.size * (/[؀-ۿ]/.test(t.text) ? 0.42 : 0.55);
const boxOf = (t: Item): Box => {
  const w = width(t), left = t.end ? t.x - w : t.x;
  const r = (v: number) => Math.round(v * 10000) / 10000;
  return { x: r((left - 6) / W), y: r((t.y - t.size - 4) / H), w: r((w + 12) / W), h: r((t.size * 1.3 + 8) / H) };
};
const union = (bs: Box[]): Box => {
  const x = Math.min(...bs.map((b) => b.x)), y = Math.min(...bs.map((b) => b.y));
  return { x, y, w: Math.max(...bs.map((b) => b.x + b.w)) - x, h: Math.max(...bs.map((b) => b.y + b.h)) - y };
};

const PAGE1: Item[] = [
  { x: 60, y: 110, size: 34, bold: true, text: "Red Sea Print House", key: "supplier" },
  { x: 60, y: 160, size: 22, bold: true, text: "Tax Invoice" },
  { x: 940, y: 160, size: 22, bold: true, end: true, text: "فاتورة ضريبية" },
  { x: 60, y: 240, size: 18, text: "Invoice Number:" }, { x: 300, y: 240, size: 18, text: "INV-2026-3944", key: "invoice_number" },
  { x: 60, y: 285, size: 18, text: "Invoice Date:" }, { x: 300, y: 285, size: 18, text: "05/02/2026", key: "invoice_date" },
  { x: 60, y: 330, size: 18, text: "Supplier VAT No:" }, { x: 300, y: 330, size: 18, text: "318609139099603", key: "supplier_vat" },
  { x: 60, y: 375, size: 18, text: "Customer:" }, { x: 300, y: 375, size: 18, text: "شركة النور القابضة", key: "customer" },
  { x: 60, y: 460, size: 16, bold: true, text: "Description" }, { x: 600, y: 460, size: 16, bold: true, text: "Qty" },
  { x: 940, y: 460, size: 16, bold: true, end: true, text: "Amount" },
];
const LINES = [["Printer toner", "9", "2,382.93"], ["Cloud backup (1 yr)", "10", "6,613.20"], ["RPA licence", "9", "29,837.52"], ["On-site training", "2", "2,880.56"]];
const CELLS: Item[][] = LINES.map(([d, q, a], i) => {
  const y = 510 + i * 50;
  return [{ x: 60, y, size: 18, text: d }, { x: 600, y, size: 18, text: q }, { x: 940, y, size: 18, end: true, text: a }];
});
const PAGE2: Item[] = [
  { x: 60, y: 110, size: 16, text: "Red Sea Print House · INV-2026-3944 · page 2" },
  { x: 560, y: 200, size: 20, text: "Subtotal:" }, { x: 940, y: 200, size: 20, end: true, text: "41,714.21", key: "subtotal" },
  { x: 560, y: 250, size: 20, text: "VAT 15%:" }, { x: 940, y: 250, size: 20, end: true, text: "6,257.13" },
  { x: 560, y: 310, size: 24, bold: true, text: "Total:" }, { x: 940, y: 310, size: 24, bold: true, end: true, text: "47,971.34 SAR", key: "total" },
  { x: 60, y: 600, size: 14, text: "ZATCA QR code" },
];
const QR = { x: 60, y: 380, size: 190 };

const esc = (t: string) => t.replace(/&/g, "&amp;").replace(/</g, "&lt;");
const text = (t: Item) => `<text x="${t.x}" y="${t.y}" font-size="${t.size}"${t.bold ? ' font-weight="700"' : ""}${t.end ? ' text-anchor="end"' : ""}>${esc(t.text)}</text>`;
/** A QR-like square of modules (a picture only; it encodes nothing). */
function qr(): string {
  const n = 25, m = QR.size / n;
  let s = `<rect x="${QR.x}" y="${QR.y}" width="${QR.size}" height="${QR.size}" fill="#fff"/>`;
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
    const finder = (r < 7 || r > n - 8) && (c < 7 || c > n - 8) && !((r > n - 8) && (c > n - 8));
    const on = finder ? (Math.max(Math.abs((r < 7 ? 3 : n - 4) - r), Math.abs((c < 7 ? 3 : n - 4) - c)) !== 2) : ((r * 7 + c * 13 + r * c) % 5 < 2);
    if (on) s += `<rect x="${QR.x + c * m}" y="${QR.y + r * m}" width="${m}" height="${m}" fill="#111"/>`;
  }
  return s;
}
const svg = (body: string) => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#fff"/>` +
  `<g font-family="Arial, sans-serif" fill="#1d1d1d">${body}</g></svg>`);

const PAGES: ReviewPage[] = [
  { number: 1, width: W, height: H, image: svg([...PAGE1, ...CELLS.flat()].map(text).join("") + `<line x1="60" y1="475" x2="940" y2="475" stroke="#999"/>`) },
  { number: 2, width: W, height: H, image: svg(PAGE2.map(text).join("") + qr()) },
];

const at = (page: number, items: Item[], key: string): Place => {
  const t = items.find((x) => x.key === key)!;
  return { page, bbox: boxOf(t), grounded: true, groundedBy: "ocr" };
};
const ROWS: RowPlace[] = CELLS.map((cells) => {
  const boxes = cells.map(boxOf);
  return { page: 1, bbox: union(boxes), grounded: true, groundedBy: "ocr",
    cells: Object.fromEntries(["description", "quantity", "amount"].map((c, i) => [c, { page: 1, bbox: boxes[i], grounded: true }])) };
});

export const SAMPLE_REVIEW: ReviewTask = {
  documentId: "scan_0457", processId: "ap-invoices", status: "review", name: "scan_0457.jpg", processName: "AP invoices", docType: "Supplier invoice", cameIn: "10:40 from \\\\scan01\\invoices",
  dueIn: "3 h", targetHours: 24, position: 1, of: 9, engineTrail: "read by Tesseract, re-read by PaddleOCR (78%)",
  pages: PAGES,
  pageLines: [
    { text: "Red Sea Print House", bold: true }, { text: "Tax Invoice", bold: true }, { text: "Invoice Number: INV-2026-3944" },
    { text: "Invoice Date: 05/02/2026" }, { text: "Supplier VAT No: 318609139099603" }, { text: "Customer: شركة النور القابضة" },
    { text: "Subtotal: 41,714.21", mark: "ok" }, { text: "VAT 15%: 6,257.13", mark: "flag" }, { text: "Total: 47,971.34 SAR", mark: "ok", bold: true }],
  fields: [
    { id: "vat_amount", label: "VAT amount", value: "6,287.13", confidence: 61, place: { page: null, bbox: null, grounded: false, groundedBy: null },
      flagged: { reason: "Fails 2 checks: subtotal + VAT does not equal the total, and it is not 15% of the subtotal.", suggestion: "6,257.13" } },
    { id: "supplier", label: "Supplier", value: "Red Sea Print House", confidence: 96, place: at(1, PAGE1, "supplier") },
    { id: "invoice_number", label: "Invoice number", value: "INV-2026-3944", confidence: 92, place: at(1, PAGE1, "invoice_number") },
    { id: "invoice_date", label: "Invoice date", value: "05/02/2026", confidence: 95, place: at(1, PAGE1, "invoice_date") },
    { id: "supplier_vat", label: "Supplier VAT no.", value: "318609139099603", confidence: 93, place: at(1, PAGE1, "supplier_vat") },
    { id: "customer", label: "Customer", value: "شركة النور القابضة", confidence: 90, place: at(1, PAGE1, "customer") },
    { id: "subtotal", label: "Subtotal", value: "41,714.21", confidence: 97, place: at(2, PAGE2, "subtotal") },
    { id: "total", label: "Total", value: "47,971.34", confidence: 97, place: at(2, PAGE2, "total") }],
  tables: [{
    id: "line_items", label: "Line items", confidence: 88,
    columns: [{ name: "description", label: "Description" }, { name: "quantity", label: "Qty" }, { name: "amount", label: "Amount" }],
    rows: LINES.map(([description, quantity, amount]) => ({ description, quantity, amount })),
    place: { page: 1, bbox: union(ROWS.map((r) => r.bbox!)), grounded: true, groundedBy: "ocr" }, rowPlaces: ROWS,
  }],
  checks: [
    { text: "Subtotal + VAT = total", passed: false, detail: "41,714.21 + 6,287.13 ≠ 47,971.34" },
    { text: "VAT is 15% of subtotal", passed: false, detail: "expected 6,257.13" },
    { text: "Supplier is in reference data", passed: true, detail: "Suppliers list" },
    { text: "Not a duplicate", passed: true, detail: "no earlier INV-2026-3944" }],
  einvoice: {
    source: "zatca-qr", values: { seller_name: "Red Sea Print House", vat_number: "318609139099603", timestamp: "2026-02-05T09:12:00", total: "47971.34", vat_amount: "6257.13" },
    checks: [
      { field: "supplier", label: "Supplier", qr: "Red Sea Print House", read: "Red Sea Print House", result: "match" },
      { field: "supplier_vat", label: "Supplier VAT no.", qr: "318609139099603", read: "318609139099603", result: "match" },
      { field: "total", label: "Total", qr: "47971.34", read: "47,971.34", result: "match" },
      { field: "vat_amount", label: "VAT amount", qr: "6257.13", read: "6,287.13", result: "mismatch" }],
  },
};
