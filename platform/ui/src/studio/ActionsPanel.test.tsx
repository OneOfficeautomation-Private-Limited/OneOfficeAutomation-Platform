import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
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

  it("finds an action by its AA command name and says which name matched", () => {
    render(<ActionsPanel packages={PACKAGES} onAdd={() => {}} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Search actions" }), { target: { value: "simulate keystrokes" } });
    const action = screen.getAllByRole("button").find((b) => b.classList.contains("st-action"))!;
    expect(action.textContent).toContain("Type text");
    expect(within(action).getByText("AA: Simulate keystrokes")).toBeTruthy();
  });
});
