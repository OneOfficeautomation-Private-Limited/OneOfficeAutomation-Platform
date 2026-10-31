// Checks the colour tokens in src/theme.css by calculation (WCAG 2 contrast), for the light and the dark theme, and the
// console's own light colours (src/console/console.css, .cc-app):
// text at least 4.5:1 on every surface it is used on, control borders at least 3:1. Fails the build when one is short.
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../src/theme.css", import.meta.url), "utf8");
const consoleCss = readFileSync(new URL("../src/console/console.css", import.meta.url), "utf8");
const block = (selector, text = css, file = "theme.css") => {
  const at = text.indexOf(selector + " {");
  if (at < 0) throw new Error(file + " has no " + selector + " block");
  const body = text.slice(at, text.indexOf("}", at));
  return Object.fromEntries([...body.matchAll(/(--ui-[\w-]+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => [m[1], m[2]]));
};
const light = block(":root");
const themes = { light, dark: { ...light, ...block(':root[data-theme="dark"]') },
  "console light": { ...light, ...block(':root:not([data-theme="dark"]) .cc-app', consoleCss, "console.css") } };

const lum = (hex) => {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
};
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

const SURFACES = ["--ui-bg", "--ui-soft", "--ui-row-hover", "--ui-selected"];
const TEXT = ["--ui-text", "--ui-text-2", "--ui-text-3", "--ui-accent-text", "--ui-variable", "--ui-error-text", "--ui-warning-text"];
const PAIRS = [
  ...TEXT.flatMap((t) => SURFACES.map((s) => [t, s, 4.5])),
  ["--ui-pill-text", "--ui-selected", 4.5], ["--ui-error-text", "--ui-error-bg", 4.5], ["--ui-ok-text", "--ui-ok-bg", 4.5],
  ["--ui-ai-text", "--ui-ai-bg", 4.5], ["--ui-text", "--ui-offer-bg", 4.5], ["--ui-text", "--ui-running-bg", 4.5], ["--ui-text", "--ui-tint", 4.5],
  ["--ui-field", "--ui-bg", 3], ["--ui-accent", "--ui-bg", 3],
];
let failed = 0;
for (const [name, t] of Object.entries(themes)) {
  for (const [fg, bg, need] of PAIRS) {
    if (!t[fg] || !t[bg]) { console.error(`${name}: ${fg} or ${bg} is missing`); failed++; continue; }
    const r = ratio(t[fg], t[bg]);
    if (r < need) { console.error(`${name}: ${fg} ${t[fg]} on ${bg} ${t[bg]} is ${r.toFixed(2)}:1, needs ${need}:1`); failed++; }
  }
}
// The console's borders and lines (option B, "strong"): panel edges 3:1 on white, title and header lines 2:1, row lines
// 1.5:1; the dark menu's guide line 2.5:1 and section lines 2:1 on the menu.
const frame = { ...light, ...block(".st-app.cc-app", consoleCss, "console.css") };
const LINES = [
  [themes["console light"], "--ui-border", "--ui-bg", 3], [themes["console light"], "--ui-border", "--ui-soft", 2.5],
  [themes["console light"], "--ui-line", "--ui-bg", 2], [themes["console light"], "--ui-divider", "--ui-bg", 1.5],
  [frame, "--ui-frame-guide", "--ui-frame-2", 2.5], [frame, "--ui-frame-line", "--ui-frame-2", 2],
];
for (const [t, fg, bg, need] of LINES) {
  if (!t[fg] || !t[bg]) { console.error(`console lines: ${fg} or ${bg} is missing`); failed++; continue; }
  const r = ratio(t[fg], t[bg]);
  if (r < need) { console.error(`console lines: ${fg} ${t[fg]} on ${bg} ${t[bg]} is ${r.toFixed(2)}:1, needs ${need}:1`); failed++; }
}
if (failed) { console.error(failed + " colour pair(s) below the contrast rule"); process.exit(1); }
console.log(`Contrast: ${PAIRS.length} pairs pass in the light and the dark theme and the console's light colours; the console's ${LINES.length} border and line steps pass.`);
