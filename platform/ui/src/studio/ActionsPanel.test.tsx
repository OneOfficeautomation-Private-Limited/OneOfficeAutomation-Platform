import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PackageManifest } from "../api/client";
import { ActionsPanel } from "./Editor";

const PACKAGES: PackageManifest[] = [
  { id: "ooa.application", name: "Application", version: "1.0.0", actions: [
    { id: "open_file", name: "Open file", description: "Open a document with its default application." },
    { id: "open_program_file", name: "Open program/file", description: "Open a program with its parameters, or a file in its application." },
    { id: "start_program", name: "Start program" }] },
  { id: "ooa.desktop", name: "Desktop", version: "1.0.0", actions: [{ id: "type_text", name: "Type text" }] },
];

describe("the action library (usage review item 26)", () => {
  afterEach(cleanup);

  it("no longer offers Open file, which Open program/file replaces", () => {
    render(<ActionsPanel packages={PACKAGES} onAdd={() => {}} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Search actions" }), { target: { value: "open" } });
    const names = screen.getAllByRole("button").filter((b) => b.classList.contains("st-action")).map((b) => b.textContent);
    expect(names).toContain("Open program/file");
    expect(names).not.toContain("Open file");
  });

  it("finds an action by its AA command name, on one line, with the matched name in the tooltip (item 5)", () => {
    render(<ActionsPanel packages={PACKAGES} onAdd={() => {}} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Search actions" }), { target: { value: "simulate keystrokes" } });
    const action = screen.getAllByRole("button").find((b) => b.classList.contains("st-action"))!;
    expect(action.textContent).toBe("Type text");
    expect(action.getAttribute("title")).toContain("Found by: Simulate keystrokes");
    expect(screen.queryByText(/Also called/)).toBeNull();
  });

  it("opens and closes every toolkit with Expand all and Collapse all (fix list 27 Nov round 2, 3e)", () => {
    render(<ActionsPanel packages={PACKAGES} onAdd={() => {}} />);
    const groups = () => screen.getAllByRole("button").filter((b) => b.classList.contains("st-group"));
    expect(groups().map((g) => g.getAttribute("aria-expanded"))).toEqual(["false", "false"]);
    fireEvent.click(screen.getByRole("button", { name: "Expand all toolkits" }));
    expect(groups().map((g) => g.getAttribute("aria-expanded"))).toEqual(["true", "true"]);
    expect(screen.getByRole("button", { name: "Type text" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Collapse all toolkits" }));
    expect(groups().map((g) => g.getAttribute("aria-expanded"))).toEqual(["false", "false"]);
    // a click still opens one toolkit and closes the others
    fireEvent.click(groups()[0]);
    fireEvent.click(groups()[1]);
    expect(groups().map((g) => g.getAttribute("aria-expanded"))).toEqual(["false", "true"]);
  });

  it("Down and Enter in the search add the highlighted result (AA's Quick add)", () => {
    const onAdd = vi.fn();
    render(<ActionsPanel packages={PACKAGES} onAdd={onAdd} />);
    const box = screen.getByRole("textbox", { name: "Search actions" });
    fireEvent.change(box, { target: { value: "t" } });
    const results = screen.getAllByRole("button").filter((b) => b.classList.contains("st-action"));
    expect(results.length).toBeGreaterThan(1);
    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(results[1].classList.contains("is-hi")).toBe(true);
    fireEvent.keyDown(box, { key: "Enter" });
    expect(onAdd).toHaveBeenCalledTimes(1);
  });

  it("a letter typed on the toolkit list jumps to the next toolkit starting with it, not to commands (item 6)", () => {
    render(<ActionsPanel packages={PACKAGES} onAdd={() => {}} />);
    const groups = screen.getAllByRole("button").filter((b) => b.classList.contains("st-group"));
    groups[0].focus();
    const letter = (groups[groups.length - 1].dataset.name ?? "x")[0];
    fireEvent.keyDown(groups[0], { key: letter });
    expect((document.activeElement as HTMLElement).dataset.name?.toLowerCase().startsWith(letter.toLowerCase())).toBe(true);
    expect(document.activeElement?.classList.contains("st-group")).toBe(true);
  });
});
