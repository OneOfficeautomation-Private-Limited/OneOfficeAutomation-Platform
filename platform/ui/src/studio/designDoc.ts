import type { Flow, FlowStep, PackageManifest } from "../api/client";
import { explainSteps } from "./AgentsTogether";
import { stateLists } from "./flowEdit";
import { describeAction, plainValue } from "./toolkits";

/**
 * An agent as a design document (G40): what it is for, its inputs and outputs, the toolkits it uses, its steps in
 * plain words (main steps, each subflow, the error handler) and a table of every step's settings. The same blocks are
 * written as a Word file (.docx) or shown for printing (Print > Save as PDF).
 */
export type DocBlock =
  | { kind: "title" | "h1" | "h2" | "p" | "meta"; text: string }
  | { kind: "item"; depth: number; mark: string; text: string }
  | { kind: "table"; head: string[]; rows: string[][] };

/** Every step of a list, inner ones included (blocks, branches, cases, states). */
function flatSteps(steps: FlowStep[] = []): FlowStep[] {
  return steps.flatMap((s) => [s, ...flatSteps(s.children), ...flatSteps(s.else), ...(s.elseIf ?? []).flatMap((b) => flatSteps(b.children)),
    ...(s.catch ?? []).flatMap((c) => flatSteps(c.children)), ...flatSteps(s.finally), ...(s.cases ?? []).flatMap((c) => flatSteps(c.children)),
    ...flatSteps(s.default), ...stateLists(s).flatMap((l) => flatSteps(l))]);
}

/** explainSteps lines ("1. x", "  - y", "  Otherwise:") as outline items with their depth. */
function outline(lines: string[]): DocBlock[] {
  return lines.map((l) => {
    const indent = l.length - l.trimStart().length, t = l.trimStart();
    const n = /^(\d+)\. /.exec(t), dash = t.startsWith("- ");
    return { kind: "item", depth: Math.floor(indent / 2), mark: n ? n[1] + "." : dash ? "•" : "", text: n ? t.slice(n[0].length) : dash ? t.slice(2) : t };
  });
}

const DIRECTIONS: Record<string, string> = { input: "Input", output: "Output", in_out: "Input and output", local: "Inside the agent" };

export function designDocument(flow: Flow, packages: PackageManifest[], opts: { path?: string; date?: Date } = {}): DocBlock[] {
  const b: DocBlock[] = [{ kind: "title", text: flow.name || "Agent" }];
  const when = (opts.date ?? new Date()).toISOString().slice(0, 16).replace("T", " ");
  b.push({ kind: "meta", text: [opts.path, flow.kind === "ApiTask" ? "Headless API agent" : flow.kind === "AiAgent" ? "AI agent" : "Agent",
    flow.tags?.length ? "Tags: " + flow.tags.join(", ") : "", "Design document made " + when].filter(Boolean).join(" · ") });
  b.push({ kind: "h1", text: "Purpose" }, { kind: "p", text: flow.description?.trim() || "No description yet. Add one in the agent's details." });

  const vars = flow.variables ?? [];
  b.push({ kind: "h1", text: "Inputs, outputs and variables" });
  if (vars.length) b.push({ kind: "table", head: ["Name", "Type", "Used as", "Default", "Description"],
    rows: vars.map((v) => [v.name, v.type, DIRECTIONS[v.direction ?? "local"] ?? v.direction ?? "", v.type === "Credential" || v.default === undefined ? "" : plainValue(v.default, 80), v.description ?? ""]) });
  else b.push({ kind: "p", text: "The agent has no variables." });

  const subflows = flow.subflows ?? [];
  const all = [...flatSteps(flow.steps), ...subflows.flatMap((s) => flatSteps(s.steps)), ...flatSteps(flow.onError?.steps)];
  const used = new Map<string, Set<string>>();
  for (const s of all) {
    const pid = s.action.split("/")[0];
    if (pid === "core") continue;
    if (!used.has(pid)) used.set(pid, new Set());
    used.get(pid)!.add(describeAction(s.action, packages).name);
  }
  b.push({ kind: "h1", text: "Toolkits used" });
  if (used.size) b.push({ kind: "table", head: ["Toolkit", "Actions"], rows: [...used].sort(([a], [c]) => a.localeCompare(c))
    .map(([pid, acts]) => [(packages.find((p) => p.id === pid)?.name ?? pid) + " (" + pid + ")", [...acts].sort().join(", ")]) });
  else b.push({ kind: "p", text: "Only built-in steps (no toolkits)." });

  b.push({ kind: "h1", text: "Steps" });
  const main = explainSteps(flow.steps, packages);
  if (main.length) b.push(...outline(main)); else b.push({ kind: "p", text: "No steps yet." });
  if (subflows.length) {
    b.push({ kind: "h1", text: "Subflows" });
    for (const sf of subflows) {
      b.push({ kind: "h2", text: sf.name });
      if (sf.description) b.push({ kind: "p", text: sf.description });
      const l = explainSteps(sf.steps, packages);
      if (l.length) b.push(...outline(l)); else b.push({ kind: "p", text: "No steps yet." });
    }
  }
  b.push({ kind: "h1", text: "Error handling" });
  if (flow.onError) {
    b.push({ kind: "p", text: "If an error ends the agent" + (flow.onError.as ? " (kept in $" + flow.onError.as + ")" : "") + ", these steps run and then the run "
      + (flow.onError.then === "succeed" ? "succeeds" : "fails") + (flow.onError.screenshot ? "; a screenshot is taken first" : "") + "." });
    b.push(...outline(explainSteps(flow.onError.steps, packages)));
  } else b.push({ kind: "p", text: "No agent-level error handler: an error that no Try step catches fails the run." });

  b.push({ kind: "h1", text: "Step settings" });
  const rows = all.filter((s) => !s.disabled).map((s) => [s.id, describeAction(s.action, packages).name, Object.entries(s.args ?? {})
    .map(([k, v]) => k.replace(/_/g, " ") + ": " + plainValue(v, 120)).join("; "), Object.entries(s.out ?? {}).map(([k, v]) => k + " → $" + v).join(", ")]);
  if (rows.length) b.push({ kind: "table", head: ["Step", "Action", "Settings", "Stores"], rows }); else b.push({ kind: "p", text: "No steps yet." });
  const off = all.filter((s) => s.disabled).length;
  if (off) b.push({ kind: "p", text: off + " disabled step" + (off > 1 ? "s are" : " is") + " left out." });
  return b;
}

// ---- Word (.docx): plain WordprocessingML in a stored zip, no library ----

const esc = (t: string) => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;")
  // characters XML 1.0 does not allow
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "");   // eslint-disable-line no-control-regex
const run = (t: string, bold = false) => `<w:r>${bold ? "<w:rPr><w:b/></w:rPr>" : ""}<w:t xml:space="preserve">${esc(t)}</w:t></w:r>`;
const para = (t: string, style?: string, extra = "") => `<w:p>${style || extra ? `<w:pPr>${style ? `<w:pStyle w:val="${style}"/>` : ""}${extra}</w:pPr>` : ""}${run(t)}</w:p>`;

function tableXml(head: string[], rows: string[][]): string {
  const cell = (t: string, h: boolean) => `<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/>${h ? '<w:shd w:val="clear" w:color="auto" w:fill="E7ECF3"/>' : ""}</w:tcPr><w:p>${h ? "<w:pPr><w:keepNext/></w:pPr>" : ""}${run(t, h)}</w:p></w:tc>`;   // the header row stays with the first row
  const border = (n: string) => `<w:${n} w:val="single" w:sz="4" w:space="0" w:color="A0A8B4"/>`;
  return `<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>${["top", "left", "bottom", "right", "insideH", "insideV"].map(border).join("")}</w:tblBorders>`
    + `<w:tblCellMar><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>`
    + `<w:tr><w:trPr><w:tblHeader/></w:trPr>${head.map((h) => cell(h, true)).join("")}</w:tr>`
    + rows.map((r) => `<w:tr><w:trPr><w:cantSplit/></w:trPr>${r.map((c) => cell(c, false)).join("")}</w:tr>`).join("") + `</w:tbl><w:p/>`;
}

export function documentXml(blocks: DocBlock[]): string {
  const body = blocks.map((x) => {
    switch (x.kind) {
      case "title": return para(x.text, "Title");
      case "h1": return para(x.text, "Heading1");
      case "h2": return para(x.text, "Heading2");
      case "meta": return para(x.text, "Subtitle");
      case "p": return para(x.text);
      case "item": return `<w:p><w:pPr><w:spacing w:after="40"/><w:ind w:left="${360 + x.depth * 360}" w:hanging="${x.mark ? 360 : 0}"/></w:pPr>`
        + (x.mark ? run(x.mark + "\t") : "") + run(x.text) + `</w:p>`;
      case "table": return tableXml(x.head, x.rows);
    }
  }).join("");
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
    + `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}`
    + `<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>`;
}

const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
  + `<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">`
  + `<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="20"/></w:rPr></w:rPrDefault>`
  + `<w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>`
  + `<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>`
  + `<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/><w:color w:val="1F2937"/></w:rPr></w:style>`
  + `<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:color w:val="4B5563"/><w:sz w:val="18"/></w:rPr></w:style>`
  + `<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="280" w:after="100"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="1D4ED8"/></w:rPr></w:style>`
  + `<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/><w:color w:val="1F2937"/></w:rPr></w:style>`
  + `</w:styles>`;

const CONTENT_TYPES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
  + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>`
  + `<Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>`
  + `<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>`;
const ROOT_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
  + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`;
const DOC_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
  + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`;

const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(b: Uint8Array): number { let c = 0xffffffff; for (const x of b) c = CRC[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }

/** A zip with stored (uncompressed) entries: enough for Word, which reads any valid zip. */
export function storedZip(files: [string, string][]): Uint8Array {
  const enc = new TextEncoder(), parts: Uint8Array[] = [], central: Uint8Array[] = [];
  let offset = 0;
  for (const [name, text] of files) {
    const n = enc.encode(name), data = enc.encode(text), crc = crc32(data);
    const local = new Uint8Array(30 + n.length), lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, 0x0800, true); lv.setUint16(10, 0, true); lv.setUint16(12, 0x21, true);
    lv.setUint32(14, crc, true); lv.setUint32(18, data.length, true); lv.setUint32(22, data.length, true); lv.setUint16(26, n.length, true); local.set(n, 30);
    const c = new Uint8Array(46 + n.length), cv = new DataView(c.buffer);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint16(8, 0x0800, true); cv.setUint16(14, 0x21, true);
    cv.setUint32(16, crc, true); cv.setUint32(20, data.length, true); cv.setUint32(24, data.length, true); cv.setUint16(28, n.length, true); cv.setUint32(42, offset, true); c.set(n, 46);
    parts.push(local, data); central.push(c); offset += local.length + data.length;
  }
  const size = central.reduce((s, c) => s + c.length, 0), end = new Uint8Array(22), ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true); ev.setUint32(12, size, true); ev.setUint32(16, offset, true);
  const out = new Uint8Array(offset + size + 22);
  let at = 0;
  for (const p of [...parts, ...central, end]) { out.set(p, at); at += p.length; }
  return out;
}

export function designDocx(blocks: DocBlock[]): Uint8Array {
  return storedZip([["[Content_Types].xml", CONTENT_TYPES], ["_rels/.rels", ROOT_RELS], ["word/_rels/document.xml.rels", DOC_RELS],
    ["word/document.xml", documentXml(blocks)], ["word/styles.xml", STYLES]]);
}

/** A file name from the agent's name: letters, digits, spaces, dots, dashes and underscores only. */
export function docFileName(flow: Flow, ext: string): string {
  return ((flow.name || "Agent").replace(/[^\p{L}\p{N} ._-]+/gu, " ").replace(/\s+/g, " ").trim() || "Agent") + " - design." + ext;
}
