// Builds docs/research/command-comparison.md: one row per command of ours (from `ooa manifest`), with the AA equivalent,
// differences, fixes, modernisation ideas, the sample-task result and the tests. Run from the repository root:
//   platform/cli/build/install/ooa/bin/ooa.bat manifest > manifest.json
//   node docs/research/tools/command-table.js manifest.json platform/cli/build/command-samples-results.json platform/cli/build/connector-smoke-results.json
const fs = require("fs");
const path = require("path");

const [manifestFile, resultsFile, smokeFile] = process.argv.slice(2);
const root = path.join(__dirname, "..");
const data = JSON.parse(fs.readFileSync(path.join(root, "command-comparison.data.json"), "utf8"));
const aa = JSON.parse(fs.readFileSync(path.join(root, "aa-ce-actions-2026-10-19.json"), "utf8")).actions;
const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
const results = resultsFile && fs.existsSync(resultsFile) ? JSON.parse(fs.readFileSync(resultsFile, "utf8")) : [];
// connector smoke test: each connector command against a catch-all mock server (ConnectorSmokeTest)
const smoke = {};
for (const r of smokeFile && fs.existsSync(smokeFile) ? JSON.parse(fs.readFileSync(smokeFile, "utf8")) : []) smoke[r.command] = r;

const cell = (s) => String(s || "").replace(/\|/g, "\\|").replace(/\r?\n/g, " ");
const reverse = {};                     // our command -> AA actions mapped to it
for (const [k, v] of Object.entries(data.aaMap)) if (!v.startsWith("-")) (reverse[v] = reverse[v] || []).push(k);
const samples = {};
for (const r of results) { const s = samples[r.command] = samples[r.command] || { pass: 0, fail: 0, skipped: 0 }; s[r.check === "pass" ? "pass" : r.check === "skipped" ? "skipped" : "fail"]++; }
// commands used inside another command's sample task count as exercised by it
const usedIn = {};
for (const r of results) if (r.check === "pass") for (const u of r.uses || []) if (u !== r.command) (usedIn[u] = usedIn[u] || new Set()).add(r.command);
const groupOf = {};
data.groups.forEach((g) => g.packages.forEach((p) => { groupOf[p] = g.name; }));

const rows = [], byPkg = {};
for (const p of manifest) for (const kind of ["actions", "conditions", "iterators"]) for (const a of p[kind] || []) {
  const id = p.id + "/" + a.id + (kind === "actions" ? "" : " (" + kind.slice(0, -1) + ")");
  const key = p.id + "/" + a.id;
  const f = (kind === "actions" ? data.commands[key] : data.commands[id]) || {};
  const pk = data.packages[p.id] || {};
  const s = samples[key];
  const row = { pkg: p.id, pkgName: p.name, id, name: a.name, kind,
    aa: (reverse[key] || []).join("; ") || (f.aa || ""),
    diff: f.diff || (groupOf[p.id] ? "Same options as documented in property-gaps; nothing further found." : ""),
    fixed: f.fixed || "", ideas: f.ideas || pk.ideas || "",
    sample: s && (s.pass || s.fail) ? (s.fail ? "FAIL " + s.fail + " / pass " + s.pass : "pass (" + s.pass + ")") + (s.skipped ? ", " + s.skipped + " skipped here" : "") : s && s.skipped ? "written; skipped in this run (needs a browser or desktop)"
      : usedIn[id] || usedIn[key] ? "pass (in the " + [...(usedIn[id] || usedIn[key])][0].split("/")[1] + " sample)"
      : smoke[key] ? (smoke[key].verdict === "pass" ? "mock server: " : "FAIL on mock: ") + (smoke[key].request || "no request (checks inputs or session first)")
        + (smoke[key].error ? " -> " + smoke[key].error : " -> finished") : groupOf[p.id] ? (pk.sample || "unit test") : "",
    test: groupOf[p.id] ? (f.test || pk.test || "") : "",
    status: groupOf[p.id] ? "checked" : "listed" };
  rows.push(row); (byPkg[p.id] = byPkg[p.id] || []).push(row);
}

const actions = rows.filter((r) => r.kind === "actions");
const checked = actions.filter((r) => r.status === "checked");
const out = [];
out.push("# Command comparison: One Agent Studio and Automation 360", "",
  "One row per command (action) of ours, compared with the matching AA action. Built by `tools/command-table.js` from the",
  "action manifests, `command-comparison.data.json` (the findings) and the sample-task results of `CommandSamplesTest`",
  "(`platform/packages/src/test/resources/command-samples/`). AA behaviour is summarised in our own words.", "",
  "How each command was checked: its settings form in Studio (fields, defaults, required marks, drop-downs, which options",
  "appear for which choice, help text), a small sample agent run through the real runner with the variables checked after",
  "the run, and AA's documented settings for the same action (`property-gaps/*.md`; AA's editor needs a bot, see the",
  "note at the end). Differences found were fixed or listed as proposals. From group 5 on, AA's own settings panels were",
  "also read in AA's editor (an empty scratch bot, nothing saved).", "",
  "| | Count |", "| --- | --- |",
  "| Commands (actions) in Studio | " + actions.length + " |",
  "| Checked so far | " + checked.length + " |",
  "| With a passing sample task | " + checked.filter((r) => r.sample.startsWith("pass")).length + " |",
  "| Connector commands run against a mock server (ConnectorSmokeTest) | " + Object.keys(smoke).length + " (" + Object.values(smoke).filter((r) => r.verdict !== "pass").length + " failing) |",
  "| With a fix or new option | " + checked.filter((r) => r.fixed).length + " |",
  "| Conditions and loop iterators (also listed) | " + (rows.length - actions.length) + " |", "");

out.push("## Changes for every command", "",
  "- **Options that apply only to some choices are hidden until they apply** (AA disables or hides them too). Toolkits",
  "  declare it with `@Input(visibleWhen = \"field=value|value\")`; the manifest carries `x-ooa-visible-when` and Studio",
  "  redraws the form when the controlling field changes. A value already set stays visible so it can be cleared.",
  "- **Number limits** (`@Input(min, max)`, JSON Schema `minimum`/`maximum`): shown in the field's help, marked in red when",
  "  out of range, and reported by the validator before a run.", "");

const order = [...data.groups.map((g) => g.name), "Not yet compared"];
for (const gname of order) {
  const pkgs = gname === "Not yet compared" ? Object.keys(byPkg).filter((p) => !groupOf[p]) : data.groups.find((g) => g.name === gname).packages;
  out.push("## " + gname, "");
  if (gname === "Not yet compared") out.push("Listed for the next passes; the AA column is filled from the action names where known.", "");
  for (const p of pkgs) {
    const list = byPkg[p] || [];
    if (!list.length) continue;
    out.push("### " + list[0].pkgName + " (`" + p + "`)", "");
    out.push("| Command | AA equivalent | Differences | Fixes made | Modernisation | Sample task | Test |", "| --- | --- | --- | --- | --- | --- | --- |");
    for (const r of list) out.push("| `" + r.id.replace(p + "/", "") + "` " + cell(r.name) + " | " + cell(r.aa) + " | " + cell(r.diff) + " | " + cell(r.fixed) + " | " + cell(r.ideas) + " | " + cell(r.sample) + " | " + cell(r.test) + " |");
    out.push("");
  }
}

out.push("## AA actions and where they are in our command list", "");
const done = new Set(data.groups.flatMap((g) => g.packages));
const aaPkgs = Object.keys(aa).filter((k) => Object.keys(data.aaMap).some((m) => m.startsWith(k + "/")));
out.push("| AA action | Ours |", "| --- | --- |");
for (const k of aaPkgs) for (const act of aa[k]) {
  const v = data.aaMap[k + "/" + act];
  out.push("| " + cell(k + " > " + act) + " | " + (v ? (v.startsWith("-") ? cell(v.slice(1).trim()) : "`" + v + "`") : "**missing**") + " |");
}
out.push("", "AA packages not mapped yet: " + Object.keys(aa).filter((k) => !aaPkgs.includes(k)).join(", ") + ".", "");
fs.writeFileSync(path.join(root, "command-comparison.md"), out.join("\n"), "utf8");
console.log("rows", rows.length, "actions", actions.length, "checked", checked.length);
