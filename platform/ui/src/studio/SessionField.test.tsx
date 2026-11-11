import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { knownSessions, SessionField } from "./TableFields";

const flow: Flow = { kind: "TaskBot", id: "t", name: "t", steps: [
  { id: "s1", action: "ooa.excel/open", args: { path: "a.xlsx", session: "invoices" } },
  { id: "s2", action: "core/if", children: [{ id: "s3", action: "ooa.excel/set_cell", args: { cell: "A1", session: "invoices" } }] },
  { id: "s4", action: "ooa.excel/get_cell", args: { session: "typo" } },
  { id: "s5", action: "ooa.word/open", args: { session: "letters" } }],
  subflows: [{ name: "more", steps: [{ id: "s6", action: "ooa.excel/open", args: { path: "b.xlsx" } }] }] };

describe("session fields (command review X3)", () => {
  afterEach(cleanup);

  it("knows the sessions the agent's own steps open, per toolkit, the default one included", () => {
    expect(knownSessions(flow, "ooa.excel")).toEqual({ opened: ["\u0000default", "invoices"], used: ["typo"] });
    expect(knownSessions(flow, "ooa.word")).toEqual({ opened: ["letters"], used: [] });
  });

  it("offers them, and says when no step opens the session a step names", () => {
    render(<SessionField id="s" value="typo" fallback="excel" opens={false} sessions={knownSessions(flow, "ooa.excel")} onCommit={() => {}} />);
    expect([...document.querySelectorAll("datalist option")].map((o) => (o as HTMLOptionElement).value)).toEqual(["excel", "invoices", "typo"]);
    expect(screen.getByRole("note").textContent).toContain("No step of this agent opens a session named “typo”. Opened: excel, invoices.");
    cleanup();
    render(<SessionField id="s" value={undefined} fallback="excel" opens={false} sessions={knownSessions(flow, "ooa.excel")} onCommit={() => {}} />);
    expect(screen.queryByRole("note")).toBeNull();   // the default session is opened by the subflow's Open step
  });
});
