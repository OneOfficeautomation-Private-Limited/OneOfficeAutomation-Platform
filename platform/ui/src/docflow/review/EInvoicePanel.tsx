import type { EInvoice, EInvoiceCheck } from "../api";
import { Chip, type Tone } from "../ui";

const SOURCE: Record<string, string> = {
  "einvoice-xml": "ZATCA e-invoice XML", "zatca-qr": "ZATCA QR code on the page", "gst-qr": "India GST e-invoice QR code",
};
const RESULT: Record<EInvoiceCheck["result"], { tone: Tone; label: string }> = {
  match: { tone: "ok", label: "Match" }, mismatch: { tone: "bad", label: "Mismatch" },
  differs: { tone: "warn", label: "Written differently" }, filled: { tone: "info", label: "Filled from QR" },
};

/** The e-invoice data DocFlow read (XML or QR code), and how each QR value compares with what was read from the page. */
export function EInvoicePanel({ einvoice: e }: { einvoice: EInvoice }) {
  const bad = e.checks.filter((c) => c.result === "mismatch").length;
  return (
    <section className="df-card df-pad" aria-label="E-invoice" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div className="df-head-row" style={{ gap: 8 }}>
        <b>E-invoice</b><span className="df-muted">{SOURCE[e.source] ?? e.source}</span><span className="df-grow" />
        {e.checks.length > 0 && (bad ? <Chip tone="bad">{bad} mismatch{bad > 1 ? "es" : ""}</Chip> : <Chip tone="ok">QR code agrees</Chip>)}
      </div>
      {e.source === "einvoice-xml" && <div className="df-small">The fields come from the invoice's own XML, not from reading the page.</div>}
      {e.checks.length > 0 && (
        <table className="df-table">
          <thead><tr><th>Field</th><th>QR code</th><th>Read</th><th>Result</th></tr></thead>
          <tbody>
            {e.checks.map((c) => (
              <tr key={c.field} className={c.result === "mismatch" ? "df-row-flag" : undefined}>
                <td>{c.label}</td><td className="df-num" dir="auto">{c.qr}</td><td className="df-num" dir="auto">{c.read}</td>
                <td><Chip tone={RESULT[c.result].tone}>{RESULT[c.result].label}</Chip></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
