import { useState } from "react";
import type { FlowStep } from "../api/client";
import { Button, Dialog, TextArea } from "../components";

/**
 * Recording in SAP GUI (gap G60): SAP GUI's own Script Recording and Playback (Customize Local Layout > Script
 * Recording and Playback) saves what a person does as a VBScript. Importing it turns each line into the SAP GUI
 * toolkit's steps, so a recording becomes an agent without hand-writing element ids.
 */
const VB_STRING = /"((?:[^"]|"")*)"/;

function vbValue(raw: string): unknown {
  const t = raw.trim();
  const s = VB_STRING.exec(t);
  if (s && s.index === 0) return s[1].replace(/""/g, "\"");
  if (/^(true|false)$/i.test(t)) return t.toLowerCase() === "true";
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
  return t;
}

export type SapImport = { steps: FlowStep[]; skipped: number; kept: number };

/** The steps for a SAP GUI script recording; lines it does not know become comments holding the line. */
export function sapRecordingToSteps(vbs: string): SapImport {
  const steps: FlowStep[] = [];
  let skipped = 0, kept = 0, n = 0;
  const step = (action: string, args: Record<string, unknown>) => steps.push({ id: "sap" + ++n, action, args });
  for (const raw of vbs.split(/\r?\n/)) {
    const line = raw.trim();
    const m = /^session\.findById\("((?:[^"]|"")*)"\)\.(\w+)(.*)$/i.exec(line);
    if (!m) continue;   // the script's header (If Not IsObject(application)...) and blank lines
    const id = m[1].replace(/""/g, "\"");
    const member = m[2], rest = m[3].trim();
    const assign = /^=\s*(.+)$/.exec(rest);
    const lower = member.toLowerCase();
    if (assign) {
      const value = vbValue(assign[1]);
      if (lower === "text" && /okcd$/i.test(id) && typeof value === "string" && /^\/n/i.test(value)) step("ooa.sap/run_transaction", { tcode: value.slice(2) });
      else if (lower === "text") step("ooa.sap/set_field", { id, value });
      else if (lower === "selected") step("ooa.sap/check_box", { id, checked: value === true });
      else if (lower === "key") step("ooa.sap/select_item", { id, value, match_by: "key" });
      else if (["caretposition", "verticalscrollbar.position", "currentcellcolumn", "currentcellrow", "firstvisiblerow"].includes(lower)) skipped++;
      else { step("core/comment", { text: "From the SAP recording: " + line }); kept++; }
      continue;
    }
    if (lower === "press") step("ooa.sap/press", { id });
    else if (lower === "sendvkey") step("ooa.sap/send_key", { vkey: Number(vbValue(rest)) || 0, window: Number(/^wnd\[(\d+)\]/i.exec(id)?.[1] ?? 0) });
    else if (lower === "select") {
      if (/\/rad[^/]*$/i.test(id)) step("ooa.sap/select_radio", { id });
      else step("ooa.sap/click", { id });
    } else if (lower === "doubleclick" || lower === "doubleclicknode" || lower === "doubleclickitem" || lower === "doubleclickcurrentcell") step("ooa.sap/double_click", { id });
    else if (["setfocus", "maximize", "resizeworkingpane", "restore"].includes(lower)) skipped++;
    else { step("core/comment", { text: "From the SAP recording: " + line }); kept++; }
  }
  return { steps, skipped, kept };
}

/** Paste or open a SAP GUI recording (.vbs) and insert its steps after the selected step. */
export function SapImportDialog({ onInsert, onClose }: { onInsert: (steps: FlowStep[], note: string) => void; onClose: () => void }) {
  const [text, setText] = useState("");
  const r = sapRecordingToSteps(text);
  const open = (f: File | undefined) => { if (f) f.text().then(setText, () => {}); };
  return (
    <Dialog open title="Import SAP GUI recording" width={620} onClose={onClose} footer={<>
      <Button kind="secondary" onClick={onClose}>Cancel</Button><span className="st-bar-fill" />
      <Button kind="primary" disabled={!r.steps.length} onClick={() => onInsert(r.steps, "Inserted " + r.steps.length + " steps from the SAP recording"
        + (r.kept ? "; " + r.kept + " line(s) it did not know are comments" : "") + ". Add Open system and log on first, and check the values.")}>Insert {r.steps.length || ""} steps</Button></>}>
      <p className="st-muted">In SAP GUI, choose <strong>Customize Local Layout &gt; Script Recording and Playback</strong>, record, and save the .vbs file.
        Typed values come in as they were typed: change passwords to a Credential from the vault.</p>
      <label className="st-check-line">Open the file: <input type="file" accept=".vbs,.txt" aria-label="SAP GUI recording file" onChange={(e) => open(e.target.files?.[0])} /></label>
      <TextArea aria-label="SAP GUI recording" rows={10} value={text} placeholder={"session.findById(\"wnd[0]/tbar[0]/okcd\").text = \"/nVA01\"\nsession.findById(\"wnd[0]\").sendVKey 0"}
        onChange={(e) => setText(e.target.value)} />
      {text.trim() && <p role="status">{r.steps.length} step{r.steps.length === 1 ? "" : "s"}{r.skipped ? ", " + r.skipped + " cursor and window lines left out" : ""}{r.kept ? ", " + r.kept + " kept as comments" : ""}.</p>}
    </Dialog>
  );
}
