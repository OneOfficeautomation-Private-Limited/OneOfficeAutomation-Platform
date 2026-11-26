import { readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { KERNEL_SECTIONS, MENU_SECTIONS } from "../shell/menu";
import { MODULE_PAGES } from "./pages";
import { UI_MODULES, withModules } from "./registry";

const here = join(process.cwd(), "src", "modules");

describe("the UI module registry", () => {
  it("finds every module folder and puts each page in its section of the five-section menu (26 Nov 2026)", () => {
    const folders = readdirSync(here, { withFileTypes: true }).filter((d) => d.isDirectory() && existsSync(join(here, d.name, "module.ts"))).map((d) => d.name).sort();
    expect(UI_MODULES.map((m) => m.id)).toEqual(folders);
    expect(MENU_SECTIONS.map((s) => s.id + ": " + s.pages.map((p) => p.id).join(" "))).toEqual([
      "setup: setup",
      "build: automation docautomation apps skills knowledge ideas",
      "run: activity workflows schedules triggers apitasks upcoming queues inbox devices locks chat",
      "manage: users roles teams credentials policies audit savedconn connections globals data toolkits settings licenses templates events",
      "monitor: status insights operations runner-status business alerts alert-rules governance",
    ]);
  });

  it("has a console page for every page a module puts in the menu, and no page twice", () => {
    const menu = UI_MODULES.flatMap((m) => m.menu.map((i) => i.page.id));
    expect(new Set(menu).size).toBe(menu.length);
    for (const id of menu) expect(MODULE_PAGES[id], id).toBeTypeOf("function");
    const kernel = KERNEL_SECTIONS.flatMap((s) => s.pages.map((p) => p.id));
    for (const id of menu) expect(kernel, id + " is a module's page, not the kernel's").not.toContain(id);
  });

  it("without a module its pages are simply not there, and a section with no pages is hidden", () => {
    const without = withModules(KERNEL_SECTIONS, UI_MODULES.filter((m) => m.id !== "insights" && m.id !== "orchestration"));
    expect(without.map((s) => s.id)).toEqual(["setup", "build", "run", "manage", "monitor"]);
    expect(without.find((s) => s.id === "build")!.pages.map((p) => p.id)).not.toContain("workflows");
    expect(without.find((s) => s.id === "monitor")!.pages.map((p) => p.id)).toEqual(["status", "governance"]);
  });

  it("puts a page whose page to follow is missing at the end of its section", () => {
    const out = withModules(KERNEL_SECTIONS, [{ id: "x", menu: [{ section: "build", after: "nowhere", page: { id: "x", label: "X" } }] }]);
    expect(out.find((s) => s.id === "build")!.pages.at(-1)!.id).toBe("x");
  });
});
