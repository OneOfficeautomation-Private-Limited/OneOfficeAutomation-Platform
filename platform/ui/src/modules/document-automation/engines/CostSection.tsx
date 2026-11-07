import { useState } from "react";
import { Field, Select, TextInput } from "../../../components";
import { estimate, modelName, type ClaudeModels } from "../api";
import { Chip } from "../ui";

const money = (v: number) => "$" + v.toLocaleString("en", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const CLOUD_OCR = [{ value: "azure-di", label: "Azure Document Intelligence" }, { value: "google-docai", label: "Google Document AI" }, { value: "aws-textract", label: "AWS Textract" }];
const EXTRACTION = [
  { value: "azure-di", label: "Azure prebuilt invoice" }, { value: "aws-textract", label: "AWS AnalyzeExpense" }, { value: "claude-sonnet-5-5", label: "Claude Sonnet 5.5" },
  { value: "claude-opus-5-5", label: "Claude Opus 5.5" }, { value: "claude-haiku-5-5", label: "Claude Haiku 5.5 (Claude default)" }, { value: "builtin-rules", label: "Local rules (free)" },
];

/**
 * The monthly cost, on its own: the client's volumes on the left, the bill on the right, and what the same volumes
 * would cost with each way of reading the fields. The plan's example: 10,000 pages, 20% weak pages to cloud OCR, 30%
 * with fields read by Azure gives $33 a month.
 */
export function CostSection({ optIn, claude }: { optIn: boolean; claude?: ClaudeModels }) {
  const [pages, setPages] = useState("10000");
  const [cloud, setCloud] = useState("20");
  const [fields, setFields] = useState("30");
  const [ocr, setOcr] = useState("azure-di");
  const [extract, setExtract] = useState("azure-di");
  const at = (x: string) => estimate(Number(pages) || 0, (Number(cloud) || 0) / 100, (Number(fields) || 0) / 100, ocr, x);
  const e = at(extract);
  return (
    <section aria-label="Monthly cost estimate" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div className="df-head-row"><h3 className="df-section-title">Monthly cost estimate</h3><span className="df-grow" /><Chip tone="mute" dot={false}>list prices</Chip></div>
      <div className="df-cost">
        <div className="df-card df-pad" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <b>The client's volumes</b>
          <Field label="Pages per month">{(id) => <TextInput id={id} inputMode="numeric" value={pages} onChange={(x) => setPages(x.target.value)} />}</Field>
          <div className="df-grid df-g2" style={{ gap: 10 }}>
            <Field label="Weak pages to cloud (%)">{(id) => <TextInput id={id} inputMode="numeric" value={cloud} onChange={(x) => setCloud(x.target.value)} />}</Field>
            <Field label="Pages with fields (%)">{(id) => <TextInput id={id} inputMode="numeric" value={fields} onChange={(x) => setFields(x.target.value)} />}</Field>
          </div>
          <Field label="Cloud OCR by">{(id) => <Select id={id} value={ocr} onChange={(x) => setOcr(x.target.value)} options={CLOUD_OCR} />}</Field>
          <Field label="Field extraction by">{(id) => <Select id={id} value={extract} onChange={(x) => setExtract(x.target.value)} options={EXTRACTION} />}</Field>
        </div>

        <div className="df-card df-pad" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <b>The bill</b>
          <table className="df-table">
            <tbody>
              <tr><td style={{ paddingLeft: 0 }}>Local OCR · {e.localPages.toLocaleString("en")} pages</td><td className="df-right df-num" style={{ paddingRight: 0 }}>{money(0)}</td></tr>
              <tr><td style={{ paddingLeft: 0 }}>Cloud OCR · {e.cloudPages.toLocaleString("en")} × {money(e.ocrRate)}/1,000</td><td className="df-right df-num" style={{ paddingRight: 0 }}>{money(e.ocr)}</td></tr>
              <tr><td style={{ paddingLeft: 0 }}>Fields · {e.fieldPages.toLocaleString("en")} × {money(e.fieldRate)}/1,000</td><td className="df-right df-num" style={{ paddingRight: 0 }}>{money(e.fields)}</td></tr>
            </tbody>
          </table>
          <div className="df-head-row" style={{ fontWeight: 700 }}><span>Total per month</span><span className="df-grow" /><span className="df-num" style={{ fontSize: 22 }} aria-live="polite">{money(e.total)}</span></div>
          {optIn
            ? <div className="df-note df-info">Cloud opt-in is on, so this is what the current routing would cost.</div>
            : <div className="df-note df-ok">Today this costs <b>$0</b>: cloud opt-in is off, so every page stays on this server.</div>}
          {extract.startsWith("claude-") && claude?.escalationModel && <div className="df-small">
            Documents that would go to review are read again by {modelName(claude.escalationModel)}, at its price, on top of this.</div>}
          <div className="df-small">Plus the server for local engines. Approximate list prices; check vendor pricing before quoting a client.</div>
        </div>

        <div className="df-card df-pad" style={{ display: "flex", flexDirection: "column", gap: 8 }} aria-label="Compare field extraction">
          <b>Same volumes, other ways to read fields</b>
          <table className="df-table">
            <tbody>
              {EXTRACTION.map((x) => (
                <tr key={x.value} aria-current={x.value === extract ? "true" : undefined} className={x.value === extract ? "df-row-current" : undefined}>
                  <td style={{ paddingLeft: 0 }}><button type="button" className="df-link" onClick={() => setExtract(x.value)}>{x.label}</button></td>
                  <td className="df-right df-num" style={{ paddingRight: 0 }}>{money(at(x.value).total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}
