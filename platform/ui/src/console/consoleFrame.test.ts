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
