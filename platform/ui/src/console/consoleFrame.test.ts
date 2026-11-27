import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * .cc-app is the class of the whole console (ConsoleApp's outer frame). A rule for .cc-app on its own styles every page,
 * so it may not limit the width: once the Apps page (G67) used the same name for one app's form, with max-width 760px,
 * and squeezed the whole console into 760 pixels on every screen.
 */
describe("the console frame", () => {
  const css = readFileSync(join(__dirname, "console.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

  it("has no rule for .cc-app on its own that limits its width", () => {
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
    const bad = rules.filter(([, sel, body]) =>
      sel.split(",").some((s) => s.trim() === ".cc-app") && /(^|;)\s*(max-width|width)\s*:/.test(body));
    expect(bad.map(([r]) => r.trim())).toEqual([]);
  });

  it("is still the class of the console's outer frame, as the other rules expect", () => {
    expect(readFileSync(join(__dirname, "ConsoleApp.tsx"), "utf8")).toContain('className="st-app cc-app"');
  });
});

/**
 * Layout fixes 27 Nov 2026, items 1 and 2. The console's main area is the one thing that scrolls: once a rule meant for
 * Studio on its own (".st-app > .st-body > .st-main.cc-main { overflow: visible }") matched the console too, the whole
 * window scrolled and the top bar and menu scrolled away. And a tab may grow to fit its text: a fixed height cut the
 * three-line process steps of Document Automation.
 */
describe("the console's scrolling and tabs", () => {
  const strip = (f: string) => readFileSync(join(__dirname, f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const rules = (css: string) => [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, sel, body]) => ({ sel: sel.trim(), body }));
  const all = [...rules(strip("console.css")), ...rules(strip("../studio/studio.css"))];

  it("never lets the main area stop scrolling, except in Studio on its own", () => {
    const bad = all.filter(({ sel, body }) => /\.st-main\.cc-main|\.cc-main\s*$/.test(sel.split(",").pop() ?? "")
      && /overflow(-y)?\s*:\s*visible/.test(body) && !sel.split(",").every((s) => s.includes(".st-studio")));
    expect(bad.map((r) => r.sel)).toEqual([]);
  });

  it("gives tabs in the main area a least height, not a fixed one", () => {
    const tabRules = all.filter(({ sel }) => sel.includes(".cc-main [role=\"tab\"]"));
    expect(tabRules.length).toBeGreaterThan(0);
    for (const r of tabRules) expect(r.body).not.toMatch(/(^|;|\s)height\s*:/);
  });

  it("Studio on its own carries the class that rule is scoped to", () => {
    expect(readFileSync(join(__dirname, "../studio/App.tsx"), "utf8")).toContain('"st-app cc-app st-studio"');
  });
});
