import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Phase 6 (docs/architecture/13-modules.md): each module's styles are its own. A module stylesheet styles only classes
 * that module's code uses, and the shared stylesheets (console, Studio) style no class that only one module uses, so
 * changing a module's look never touches another module or the shell.
 */
const src = join(process.cwd(), "src");
const files = (dir: string): string[] => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? (f === "node_modules" ? [] : files(p)) : [p];
});
const all = files(src);
const code = all.filter((p) => /\.tsx?$/.test(p) && !/\.test\.tsx?$/.test(p)).map((p) => ({ owner: ownerOf(p), text: readFileSync(p, "utf8") }));

function ownerOf(p: string) {
  const parts = relative(src, p).split(sep);
  return parts[0] === "modules" && parts.length > 2 ? parts[1] : "shell";
}
function classesOf(css: string) {
  const out = new Set<string>();
  for (const [, sel] of css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{[^{}]*\}/g))
    for (const [, c] of sel.matchAll(/\.([a-zA-Z][\w-]*)/g)) out.add(c);
  return out;
}
/** Class prefixes a module keeps to itself (it may build class names from them, as Document Automation's df-ok, df-bad). */
const PREFIXES: Record<string, string> = { "document-automation": "df-" };
/** Shared state modifiers (is-off, is-busy, has-error) mean the same everywhere, so any stylesheet may use them. */
const modifier = (c: string) => c.startsWith("is-") || c.startsWith("has-");
function usersOf(cls: string) {
  const rx = new RegExp("(?<![\\w-])" + cls.replace(/[-]/g, "\\-") + "(?![\\w-])");
  return new Set(code.filter((c) => rx.test(c.text)).map((c) => c.owner));
}

/** The module a class belongs to: by its prefix, or the one module whose code alone uses it. */
function ownerOfClass(c: string): string | undefined {
  const u = usersOf(c);
  return u.size === 1 && !u.has("shell") ? [...u][0] : undefined;
}

describe("module styles", () => {
  it("a module's class prefix is used by that module only", () => {
    for (const [mod, prefix] of Object.entries(PREFIXES))
      for (const c of code.filter((c) => c.owner !== mod))
        expect(c.text.match(new RegExp("[\"'` ]" + prefix + "[a-z]")), "a " + prefix + " class outside " + mod).toBeNull();
  });

  it("a module's stylesheet styles only its own markup (shared classes may give the context)", () => {
    for (const p of all.filter((p) => p.endsWith(".css") && ownerOf(p) !== "shell")) {
      const mod = ownerOf(p);
      const css = readFileSync(p, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
      for (const [, list] of css.matchAll(/([^{}]+)\{[^{}]*\}/g)) {
        if (list.trim().startsWith("@")) continue;
        for (const sel of list.split(",").map((s) => s.trim())) {
          const owners = [...sel.matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1]).filter((c) => !modifier(c))
            .map((c) => Object.keys(PREFIXES).find((m) => c.startsWith(PREFIXES[m])) ?? ownerOfClass(c)).filter((o): o is string => !!o);
          expect(owners.filter((o) => o !== mod), relative(src, p) + ": " + sel + " styles another module's markup").toEqual([]);
          expect(owners.length, relative(src, p) + ": " + sel + " names none of " + mod + "'s own classes").toBeGreaterThan(0);
        }
      }
    }
  });

  it("the shared stylesheets style no class only one module uses", () => {
    for (const sheet of ["console/console.css", "studio/studio.css", "components/components.css", "theme.css"]) {
      const own: string[] = [];
      for (const c of classesOf(readFileSync(join(src, sheet), "utf8"))) {
        const users = usersOf(c);
        if (!modifier(c) && users.size === 1 && !users.has("shell")) own.push("." + c + " (" + [...users][0] + ")");
      }
      expect(own, sheet + " styles classes that belong in a module's stylesheet").toEqual([]);
    }
  });
});
