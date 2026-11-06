import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CommandMenu } from "./CommandMenu";
import { MENU_SECTIONS } from "./menu";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); });

function screenWidth(px: number) {
  vi.stubGlobal("matchMedia", (q: string) => ({ matches: /max-width: 640px/.test(q) && px <= 640, media: q, addEventListener: () => {}, removeEventListener: () => {} }));
}

const menu = (onLocal = () => {}) => <CommandMenu sections={MENU_SECTIONS} current="home" local={new Set(["inbox", "apps", "activity"])} onLocal={onLocal}
  hrefFor={() => undefined} home={{ onClick: () => {}, current: true }} viewer={{ permissions: ["runs.view"] }} console />;

describe("The menu on a phone (G69)", () => {
  it("starts folded, opens over the page, and folds again once a page is picked", () => {
    screenWidth(390);
    const picked: string[] = [];
    render(menu((id: string) => { picked.push(id); }));
    const nav = screen.getByRole("navigation", { name: "One CommandCenter" });
    expect(nav.className).toContain("is-mini");
    fireEvent.click(screen.getByRole("button", { name: "Expand menu" }));
    expect(nav.className).not.toContain("is-mini");
    fireEvent.click(screen.getByRole("button", { name: "MANAGE" }));
    fireEvent.click(screen.getByRole("button", { name: "Task inbox" }));
    expect(picked).toEqual(["inbox"]);
    expect(nav.className).toContain("is-mini");
  });

  it("stays open on a wide screen, as the person left it", () => {
    screenWidth(1440);
    render(menu());
    expect(screen.getByRole("navigation", { name: "One CommandCenter" }).className).not.toContain("is-mini");
  });
});
