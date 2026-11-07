import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { PackageManifest } from "../api/client";
import { ActionsPanel } from "./Editor";
import { fitsSimple, simplePackages } from "./simpleMode";

const pk = (id: string, name: string, actions: string[]): PackageManifest => ({ id, name, version: "1.0.0", actions: actions.map((a) => ({ id: a, name: a.replace(/_/g, " ") })) as never });
const PACKAGES = [
  pk("core", "Core", ["if", "loop", "set", "comment", "parallel", "run_bot"]),
  pk("ooa.excel", "Excel (no Office needed)", ["open", "read_range"]),
  pk("ooa.script", "Scripts", ["run_powershell"]),
  pk("ooa.desktop", "Mouse, keyboard and screen", ["click", "message_box", "prompt_value"]),
  pk("ooa.browser", "Browser", ["open", "click"]),
];

describe("Simple mode (G47)", () => {
  it("keeps everyday toolkits under plain names, and only Core's building blocks", () => {
    const s = simplePackages(PACKAGES);
    expect(s.map((p) => p.name)).toEqual(["Decisions, repeats and notes", "Excel files", "Websites", "Ask a person"]);
    expect(s[0].actions.map((a) => a.id)).toEqual(["if", "loop", "set", "comment"]);
    expect(s.find((p) => p.id === "ooa.desktop")!.actions.map((a) => a.id)).toEqual(["message_box", "prompt_value"]);
    expect(fitsSimple(["core/if", "ooa.excel/read_range"], PACKAGES)).toBe(true);
    expect(fitsSimple(["ooa.script/run_powershell"], PACKAGES)).toBe(false);
  });

  it("switches in the Actions panel; the choice reaches the caller", () => {
    let last: boolean | null = null;
    function Host() {
      const [simple, setSimple] = useState(false);
      return <ActionsPanel packages={PACKAGES} onAdd={() => {}} simple={simple} onSimple={(v) => { last = v; setSimple(v); }} onTrigger={() => {}} />;
    }
    render(<Host />);
    expect(screen.getByRole("button", { name: /^Scripts/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /^Triggers/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Simple" }));
    expect(last).toBe(true);
    expect(screen.getByRole("button", { name: "Simple" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.queryByRole("button", { name: /^Scripts/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /^Triggers/ })).toBeNull();
    expect(screen.getByRole("button", { name: /^Excel files/ })).toBeTruthy();
    expect(screen.getByText(/Everyday actions/)).toBeTruthy();
  });
});
