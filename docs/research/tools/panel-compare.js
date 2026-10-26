// Compares how each command's panel is laid out in Studio with the mapped action's panel in the reference product.
// Usage: node panel-compare.js <reference-fields.json> <ooa-manifest.json> <out.json>
// The reference fields file is read from the reference product's editor on the user's machine and is never committed;
// this script and its output keep only field positions, kinds and match results, not the reference product's wording.
const fs = require("fs"), path = require("path");
const [refFile, manFile, outFile] = process.argv.slice(2);
const root = path.resolve(__dirname, "../../..");
const ref = JSON.parse(fs.readFileSync(refFile, "utf8"));
const manifest = JSON.parse(fs.readFileSync(manFile, "utf8").replace(/^﻿/, ""));
const data = JSON.parse(fs.readFileSync(path.join(root, "docs/research/command-comparison.data.json"), "utf8"));
const studio = fs.readFileSync(path.join(root, "platform/cli/src/main/resources/studio/studio.js"), "utf8");
const objectAt = (name) => { const i = studio.indexOf("const " + name + " = {"); let d = 0, j = studio.indexOf("{", i);
  for (let k = j; k < studio.length; k++) { if (studio[k] === "{") d++; else if (studio[k] === "}" && --d === 0) return eval("(" + studio.slice(j, k + 1) + ")"); } };
const FIELD_LABELS = objectAt("FIELD_LABELS"), KEY_FIELDS = objectAt("KEY_FIELDS");

// ---- our panel, in display order (as renderProps shows it) ----
const actions = {};
for (const p of manifest) for (const a of p.actions || []) actions[p.id + "/" + a.id] = a;
const labelFor = (name, given) => { const gen = name.charAt(0).toUpperCase() + name.slice(1).replace(/_/g, " ");
  if (given && given.toLowerCase() !== gen.toLowerCase()) return given; return FIELD_LABELS[name] || given || gen; };
function ourPanel(id) {
  const a = actions[id]; if (!a) return null;
  const props = (a.inputs || {}).properties || {}, req = (a.inputs || {}).required || [];
  let entries = Object.entries(props);
  if (props.window && props.object && id.startsWith("ooa.browser/")) entries.sort((x, y) => (y[0] === "window") - (x[0] === "window"));
  const main = [], more = [];
  for (const [name, p] of entries) {
    const advanced = (["alternatives", "session"].includes(name) || p["x-ooa-advanced"]) && !req.includes(name);
    const key = (KEY_FIELDS[id] || []).includes(name);
    const rare = advanced || (!key && name !== "window" && !req.includes(name) && main.length >= 3);
    (rare ? more : main).push({ name, label: labelFor(name, p["x-ooa-label"]), type: p["x-ooa-type"] || p.type, req: req.includes(name),
      when: p["x-ooa-visible-when"] ? Object.keys(p["x-ooa-visible-when"]) : null, more: rare });
  }
  return [...main, ...more];
}

// ---- the reference panel, flattened in display order ----
const SKIP = new Set(["HELP", "LABEL", "DESKTOPOPERATIONBUTTON", "BUTTON", "INFO"]);
function refPanel(fields, parent, depth, out) {
  for (const f of fields || []) {
    if (f.h || SKIP.has(f.t)) continue;
    const label = f.l || (parent && parent.label) || f.n || "";
    const item = { name: f.n || "", label, type: f.t, req: !!f.r, depth, parent: parent ? parent.label : null };
    const subs = (f.o || []).filter((o) => typeof o === "object" && o.f && o.f.length);
    if (subs.length) item.choice = (f.o || []).length;
    out.push(item);
    for (const o of subs) refPanel(o.f, { label: label + " > " + o.l }, depth + 1, out);
    if (f.f) refPanel(f.f, { label }, depth + 1, out);
  }
  return out;
}

// ---- field matching by words in label and name ----
const SYN = { filepath: "path", file: "path", folder: "path", directory: "path", location: "path", sessionname: "session",
  variable: "", assign: "", output: "", the: "", a: "", an: "", to: "", of: "", in: "", for: "", enter: "", select: "", optional: "",
  milliseconds: "ms", seconds: "s", timeout: "wait", time: "wait", delay: "wait", wait: "wait", password: "credential",
  pwd: "credential", username: "user", user: "user", worksheet: "sheet", sheetname: "sheet", text: "value", string: "value" };
const words = (s) => String(s || "").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/[_\-./>():]/g, " ").toLowerCase().split(/\s+/)
  .map((w) => (w in SYN ? SYN[w] : w)).filter((w) => w && w.length > 1);
const score = (x, y) => { const a = new Set([...words(x.label), ...words(x.name)]), b = new Set([...words(y.label), ...words(y.name)]);
  if (!a.size || !b.size) return 0; let n = 0; for (const w of a) if (b.has(w)) n++; return n / Math.min(a.size, b.size); };
function match(refF, ours) {
  const pairs = [];
  refF.forEach((r, i) => ours.forEach((o, j) => { const s = score(r, o); if (s >= 0.5) pairs.push([s, i, j]); }));
  pairs.sort((p, q) => q[0] - p[0]);
  const ri = new Map(), oj = new Map();
  for (const [s, i, j] of pairs) if (!ri.has(i) && !oj.has(j)) { ri.set(i, j); oj.set(j, i); }
  return { ri, oj };
}

// ---- compare every mapped pair ----
const results = [];
const refByKey = new Map(ref.map((a) => [a.p + "/" + a.l, a]));
for (const [key, target] of Object.entries(data.aaMap)) {
  if (typeof target !== "string" || target.startsWith("-")) continue;
  const r = refByKey.get(key); const ourId = target.split(/[ ,;]/)[0]; const ours = ourPanel(ourId);
  if (!r || !ours) { results.push({ key, ours: ourId, status: !r ? "no-reference-data" : "no-ooa-command" }); continue; }
  const refF = refPanel(r.f, null, 0, []);
  const { ri, oj } = match(refF, ours);
  // order: matched top-level reference fields should appear in the same order in our panel
  const seq = refF.map((f, i) => (ri.has(i) && f.depth === 0 ? ri.get(i) : null)).filter((x) => x !== null);
  let inversions = 0; for (let x = 0; x < seq.length; x++) for (let y = x + 1; y < seq.length; y++) if (seq[x] > seq[y]) inversions++;
  const buriedRequired = refF.filter((f, i) => f.depth === 0 && f.req && ri.has(i) && ours[ri.get(i)].more).map((f, i) => ours[ri.get(refF.indexOf(f))].name);
  const choiceFlat = refF.filter((f, i) => f.choice && ri.has(i) && !ours.some((o) => o.when)).length;
  results.push({ key, ours: ourId, status: "compared", refCount: refF.filter((f) => f.depth === 0).length, refAll: refF.length, ourCount: ours.length,
    ourMain: ours.filter((o) => !o.more).length, matched: ri.size, refOnly: refF.filter((f, i) => !ri.has(i)).length,
    oursOnly: ours.filter((o, j) => !oj.has(j)).map((o) => o.name), inversions, buriedRequired, choiceFlat,
    order: seq, ourOrder: ours.map((o) => o.name), ourFields: ours });
}
fs.writeFileSync(outFile, JSON.stringify(results, null, 1));
const c = results.filter((x) => x.status === "compared");
console.log(JSON.stringify({ pairs: results.length, compared: c.length, noRef: results.filter((x) => x.status === "no-reference-data").length,
  noOurs: results.filter((x) => x.status === "no-ooa-command").length, sameOrder: c.filter((x) => !x.inversions).length,
  orderDiff: c.filter((x) => x.inversions).length, withRefOnly: c.filter((x) => x.refOnly).length, withOursOnly: c.filter((x) => x.oursOnly.length).length,
  buried: c.filter((x) => x.buriedRequired.length).length, choiceFlat: c.filter((x) => x.choiceFlat).length }));
