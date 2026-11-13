import { afterEach, describe, expect, it } from "vitest";
import { commandKey, DEFAULT_DOCS_BASE, DEFAULT_VIDEO_BASE, helpLinks, helpPath, setHelpConfig } from "./helpCatalogue";
import { MENU_SECTIONS } from "./menu";
import { PAGE_INTROS } from "./PageIntro";
import { errorLink } from "../components/errorLink";

afterEach(() => setHelpConfig({}));

describe("help links (13 Nov 2026 round 3, item 6)", () => {
  it("builds every key's Docs and Video address by one rule, with the version in the documentation's", () => {
    setHelpConfig({ version: "0.2.0" });
    expect(helpPath("page:activity")).toBe("pages/activity");
    expect(helpPath("command:excel.open-workbook")).toBe("commands/excel/open-workbook");
    expect(helpPath("error:RUN.INPUT_MISSING")).toBe("errors/run-input-missing");
    expect(helpLinks("page:activity")).toEqual({ doc: DEFAULT_DOCS_BASE + "0.2.0/pages/activity", video: DEFAULT_VIDEO_BASE + "pages/activity" });
    expect(commandKey("excel/openWorkbook")).toBe("command:excel.open-workbook");
    expect(errorLink("RUN.INPUT_MISSING")).toBe(DEFAULT_DOCS_BASE + "0.2.0/errors/run-input-missing");
  });

  it("uses the server's addresses (a company's own copy) and links set in its help-links.json, without a rebuild", () => {
    setHelpConfig({ version: "0.2.0", docs_base: "https://intranet.example.com/ooa-docs", video_base: "https://intranet.example.com/ooa-videos/",
      links: { "page:schedules": { video: "https://youtu.be/abc" } } });
    expect(helpLinks("page:activity").doc).toBe("https://intranet.example.com/ooa-docs/0.2.0/pages/activity");
    expect(helpLinks("page:schedules")).toEqual({ doc: "https://intranet.example.com/ooa-docs/0.2.0/pages/schedules", video: "https://youtu.be/abc" });
  });

  it("gives every page in the menu its one-line description (item 5), so no page is left without one", () => {
    const pages = MENU_SECTIONS.flatMap((s) => s.pages.map((p) => p.id));
    const missing = pages.filter((id) => !PAGE_INTROS["page:" + id]);
    expect(missing).toEqual([]);
  });
});
