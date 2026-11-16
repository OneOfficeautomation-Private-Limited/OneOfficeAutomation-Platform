import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { toolkitMark } from "./toolkits";

/**
 * Each toolkit in the Actions panel shows its own colour (command observations, 16 Nov 2026, icons option A). The
 * tile's colour is set on the tile itself, so no stylesheet may paint over it: a 13 Nov rule that gave every toolkit
 * one grey tile hid the new colours until 17 Nov. The same goes for the line beside an open toolkit's commands.
 */
const src = join(process.cwd(), "src");
const files = (dir: string): string[] => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? (f === "node_modules" ? [] : files(p)) : [p];
});
const rules = files(src).filter((p) => p.endsWith(".css")).flatMap((p) =>
  [...readFileSync(p, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map(([, sel, body]) => ({ file: p.slice(src.length + 1), sel: sel.trim().replace(/\s+/g, " "), body })));

describe("toolkit colours in the Actions panel", () => {
  it("no stylesheet paints over a toolkit's tile", () => {
    const tile = rules.filter((r) => /\.st-(group|actions)\b[^,]*\.ui-tile/.test(r.sel) && /(^|;)\s*(background(-color)?|color)\s*:/.test(r.body));
    expect(tile.map((r) => `${r.file}: ${r.sel}`)).toEqual([]);
  });

  it("an open toolkit's line keeps the toolkit's colour", () => {
    const line = rules.filter((r) => /\.st-group-actions\b/.test(r.sel) && /border-left(-color)?\s*:/.test(r.body) && !/var\(--kit/.test(r.body));
    expect(line.map((r) => `${r.file}: ${r.sel}`)).toEqual([]);
  });

  it("the data toolkits each have a colour of their own", () => {
    const ids = ["ooa.string", "ooa.number", "ooa.boolean", "ooa.convert", "ooa.datetime", "ooa.list", "ooa.dictionary", "ooa.datatable"];
    expect(new Set(ids.map((id) => toolkitMark(id).color)).size).toBe(ids.length);
  });
});
