import { describe, expect, it } from "vitest";
import type { Flow, PackageManifest } from "../api/client";
import { designDocument, designDocx, docFileName, type DocBlock } from "./designDoc";

const packages: PackageManifest[] = [
  { id: "core", name: "Core", version: "1.0.0", actions: [{ id: "log", name: "Write to log" }, { id: "if", name: "If" }, { id: "comment", name: "Comment" }] as never },
  { id: "ooa.workload", name: "Work queues", version: "1.0.0", actions: [{ id: "get_next", name: "Get next item" }, { id: "complete", name: "Mark completed" }] as never },
];

const flow: Flow = {
  kind: "TaskBot", id: "claims", name: "Claims <intake> & checks", description: "Takes claims from the queue and checks each one.", tags: ["finance"],
  variables: [
    { name: "queue", type: "String", direction: "input", default: "claims", description: "Queue to read." },
    { name: "secret", type: "Credential", direction: "input" },
    { name: "processed", type: "Number", direction: "output", default: 0 },
  ],
  steps: [
    { id: "s1", action: "ooa.workload/get_next", args: { queue: "$queue" }, out: { found: "found" } },
    { id: "s2", action: "core/if", args: { condition: { op: "eq", left: "$found", right: true } }, children: [
      { id: "s3", action: "ooa.workload/complete", args: { queue: "$queue", item_id: "$item_id" } }] },
    { id: "s4", action: "core/log", args: { message: "old" }, disabled: true },
  ],
  subflows: [{ name: "Process", description: "One claim.", steps: [{ id: "p1", action: "core/comment", args: { text: "Check the claim." } }] }],
  onError: { as: "err", then: "fail", steps: [{ id: "h1", action: "core/log", args: { message: "${err.code}" } }] },
};

/** The entries of a stored (uncompressed) zip, as text. */
function unzip(bytes: Uint8Array): Record<string, string> {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), out: Record<string, string> = {}, dec = new TextDecoder();
  let at = 0;
  while (v.getUint32(at, true) === 0x04034b50) {
    expect(v.getUint16(at + 8, true)).toBe(0);   // stored
    const size = v.getUint32(at + 18, true), n = v.getUint16(at + 26, true), extra = v.getUint16(at + 28, true);
    const name = dec.decode(bytes.subarray(at + 30, at + 30 + n));
    out[name] = dec.decode(bytes.subarray(at + 30 + n + extra, at + 30 + n + extra + size));
    at += 30 + n + extra + size;
  }
  expect(v.getUint32(bytes.length - 22, true)).toBe(0x06054b50);   // end of central directory
  return out;
}

describe("design document (G40)", () => {
  const blocks = designDocument(flow, packages, { path: "Finance/Claims.flow.json", date: new Date("2026-11-06T09:30:00Z") });
  const text = (b: DocBlock) => ("text" in b ? b.text : b.kind === "table" ? [...b.head, ...b.rows.flat()].join(" | ") : "");

  it("has the purpose, variables, toolkits, steps, subflows, error handling and step settings", () => {
    expect(blocks.filter((b) => b.kind === "h1").map(text)).toEqual(["Purpose", "Inputs, outputs and variables", "Toolkits used", "Steps", "Subflows", "Error handling", "Step settings"]);
    expect(text(blocks[1])).toBe("Finance/Claims.flow.json · Agent · Tags: finance · Design document made 2026-11-06 09:30");
    const vars = blocks.find((b) => b.kind === "table")!;
    expect(vars.kind === "table" && vars.rows).toEqual([["queue", "String", "Input", "claims", "Queue to read."], ["secret", "Credential", "Input", "", ""],
      ["processed", "Number", "Output", "0", ""]]);
    const tk = blocks.filter((b) => b.kind === "table")[1];
    expect(tk.kind === "table" && tk.rows).toEqual([["Work queues (ooa.workload)", "Get next item, Mark completed"]]);
    expect(blocks.some((b) => b.kind === "h2" && b.text === "Process")).toBe(true);
    expect(blocks.some((b) => b.kind === "item" && b.mark === "1." && b.text.startsWith("Get next item"))).toBe(true);
    expect(blocks.some((b) => b.kind === "item" && b.depth === 1 && b.mark === "•" && b.text.startsWith("Mark completed"))).toBe(true);
    const settings = blocks[blocks.length - 2];
    expect(settings.kind === "table" && settings.rows.map((r) => r[0])).toEqual(["s1", "s2", "s3", "p1", "h1"]);
    expect(text(blocks[blocks.length - 1])).toBe("1 disabled step is left out.");
  });

  it("writes a Word file Word can open: a valid zip with well-formed, escaped XML", () => {
    const files = unzip(designDocx(blocks));
    expect(Object.keys(files)).toEqual(["[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels", "word/document.xml", "word/styles.xml"]);
    for (const [name, xml] of Object.entries(files)) {
      const doc = new DOMParser().parseFromString(xml, "application/xml");
      expect(doc.getElementsByTagName("parsererror").length, name).toBe(0);
    }
    expect(files["word/document.xml"]).toContain("Claims &lt;intake&gt; &amp; checks");
    expect(files["word/document.xml"]).toContain('<w:pStyle w:val="Heading1"/>');
  });

  it("names the file after the agent, without characters Windows refuses", () => {
    expect(docFileName(flow, "docx")).toBe("Claims intake checks - design.docx");
    expect(docFileName({ ...flow, name: "" }, "docx")).toBe("Agent - design.docx");
  });
});
