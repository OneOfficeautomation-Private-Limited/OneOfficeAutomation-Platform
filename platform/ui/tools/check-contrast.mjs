// Checks the colour tokens in src/theme.css by calculation (WCAG 2 contrast), for the light and the dark theme:
// text at least 4.5:1 on every surface it is used on, control borders at least 3:1. Fails the build when one is short.
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../src/theme.css", import.meta.url), "utf8");
const block = (selector) => {
  const at = css.indexOf(selector + " {");
  if (at < 0) throw new Error("theme.css has no " + selector + " block");
  const body = css.slice(at, css.indexOf("}", at));
  return Object.fromEntries([...body.matchAll(/(--ui-[\w-]+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => [m[1], m[2]]));
};
const light = block(":root");
const themes = { light, dark: { ...light, ...block(':root[data-theme="dark"]') } };

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
if (failed) { console.error(failed + " colour pair(s) below the contrast rule"); process.exit(1); }
console.log(`Contrast: ${PAIRS.length} pairs pass in the light and the dark theme.`);
