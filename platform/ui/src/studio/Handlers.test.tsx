import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Flow, FlowStep, PackageManifest } from "../api/client";
import { ConditionEditor } from "./ConditionEditor";
import { addBranch, innerLists, moveHandler, newStep, partList, removeBranch, replaceInSteps, setHandler } from "./flowEdit";
import { handlerText, stepRows } from "./toolkits";
import { VarsContext, type VarsCtx } from "./VariablesPanel";

/**
 * G1 (AA parity, 21 Nov 2026) in OneAgentStudio: Watch for interruptions and a trigger loop hold handlers, each a part
 * of the block with its own steps; lines are numbered as the runner numbers them (a block's own steps, then its
 * handlers); value checks have Match case, Ignore line breaks and Compare the date only; any condition can keep checking.
 */
const PACKAGES: PackageManifest[] = [
  { id: "ooa.triggerloop", name: "Trigger loop", version: "1.0.0", actions: [], iterators: [{ id: "file_arrives", name: "Each new or changed file in folder" }] },
  { id: "ooa.window", name: "Window", version: "1.0.0", actions: [], conditions: [{ id: "exists", name: "Window exists", inputs: { properties: { title: { type: "string" } } } }] },
] as PackageManifest[];

const flowWith = (...steps: FlowStep[]): Flow => ({ kind: "TaskBot", id: "t", name: "t", steps });

describe("handlers of Watch for interruptions and of a trigger loop", () => {
  afterEach(cleanup);

  it("a new Watch for interruptions has the watched steps and a first handler", () => {
    const s = newStep("core/interrupt_scope", flowWith(), PACKAGES);
    expect(s.children).toEqual([]);
    expect(s.handlers).toEqual([{ name: "Pop-up", children: [] }]);
  });

  it("handlers are parts: added, filled, renamed, moved, switched off and removed", () => {
    let f = flowWith({ id: "w", action: "core/interrupt_scope", children: [{ id: "a", action: "core/comment" }], handlers: [{ name: "Pop-up", children: [] }] });
    const added = addBranch(f, "w", "handlers");
    expect(added.part).toBe("handlers:1");
    f = added.flow;
    expect(f.steps[0].handlers?.[1].name).toBe("Pop-up 2");
    f = setHandler(f, "w", 0, { when: { condition: "ooa.window/exists", args: { title: "*Session expired*" } }, max_times: 2 });
    partList(f.steps[0], "handlers:0")!.push({ id: "h", action: "core/comment" });
    expect(innerLists(f.steps[0]).map((l) => l.map((s) => s.id))).toEqual([["a"], ["h"], []]);
    expect(moveHandler(f, "w", 0, 1).part).toBe("handlers:1");
    expect(moveHandler(f, "w", 0, -1).part).toBeNull();
    f = setHandler(f, "w", 1, { disabled: true });
    expect(f.steps[0].handlers?.[1].disabled).toBe(true);
    expect(setHandler(f, "w", 1, { disabled: false }).steps[0].handlers?.[1].disabled).toBeUndefined();
    f = removeBranch(f, "w", "handlers:1");
    expect(f.steps[0].handlers).toHaveLength(1);
    expect(removeBranch(f, "w", "handlers:0")).toBe(f);   // the last handler stays
  });

  it("the list numbers a block's steps first, then each handler's, as the runner does", () => {
    const f = flowWith({ id: "w", action: "core/interrupt_scope", children: [{ id: "a", action: "core/comment" }],
      handlers: [{ name: "Expired", when: { condition: "ooa.window/exists", args: { title: "Session expired" } }, children: [{ id: "h", action: "core/comment" }] }] },
      { id: "b", action: "core/comment" });
    const rows = stepRows(f.steps, new Set(), 0, PACKAGES);
    expect(rows.map((r) => r.kind === "step" ? r.n + " " + r.step.id : "— " + r.label)).toEqual(["1 w", "2 a", "— Expired: when Window exists (Session expired)", "3 h", "4 b"]);
  });

  it("a trigger loop's heading names the trigger", () => {
    const loop: FlowStep = { id: "l", action: "core/loop", args: { iterator: "triggers" }, handlers: [{ name: "Invoices", trigger: "ooa.triggerloop/file_arrives", children: [] }, { children: [] }] };
    expect(handlerText(loop, 0, PACKAGES)).toBe("Invoices: each event of Each new or changed file in folder");
    expect(handlerText(loop, 1, PACKAGES)).toBe("choose a trigger");
    expect(addBranch(flowWith(loop), "l", "handlers").flow.steps[0].handlers?.[2].name).toBe("Trigger 3");
  });

  it("find and replace changes a handler's condition, never its trigger", () => {
    const f = flowWith({ id: "l", action: "core/loop", args: { iterator: "triggers" }, handlers: [{ name: "Inbox", trigger: "ooa.triggerloop/file_arrives", args: { folder: "Inbox" }, children: [] }] });
    const r = replaceInSteps(f, "Inbox", "Incoming", true);
    expect(r.flow.steps[0].handlers?.[0]).toMatchObject({ name: "Incoming", trigger: "ooa.triggerloop/file_arrives", args: { folder: "Incoming" } });
  });
});

describe("the condition builder's ticks and keep checking", () => {
  afterEach(cleanup);
  const show = (value: unknown, onCommit = vi.fn()) => {
    const ctx = { api: {} as VarsCtx["api"], flow: flowWith(), builtins: { variables: [] } as unknown as VarsCtx["builtins"], agents: [], edit: () => {}, newVariable: () => {}, packages: PACKAGES } as VarsCtx;
    render(<VarsContext.Provider value={ctx}><ConditionEditor value={value} onCommit={onCommit} /></VarsContext.Provider>);
    return onCommit;
  };

  it("Match case is on, as AA; unticked it compares without case", () => {
    const onCommit = show({ op: "eq", left: "$status$", right: "Approved" });
    const match = screen.getByRole("checkbox", { name: "Match case" }) as HTMLInputElement;
    expect(match.checked).toBe(true);
    fireEvent.click(match);
    expect(onCommit).toHaveBeenLastCalledWith({ op: "eq", left: "$status$", right: "Approved", ignoreCase: true });
    fireEvent.click(screen.getByRole("checkbox", { name: "Ignore line breaks" }));
    expect(onCommit).toHaveBeenLastCalledWith({ op: "eq", left: "$status$", right: "Approved", ignoreCase: true, ignoreLineBreaks: true });
  });

  it("reads the ticks back, also in their other spelling, and Compare the date only", () => {
    const onCommit = show({ op: "eq", left: "$due$", right: "$today$", matchCase: false, dateOnly: true });
    expect((screen.getByRole("checkbox", { name: "Match case" }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole("checkbox", { name: "Compare the date only" }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole("checkbox", { name: "Compare the date only" }));
    expect(onCommit).toHaveBeenLastCalledWith({ op: "eq", left: "$due$", right: "$today$", ignoreCase: true });
  });

  it("keeps checking for up to N seconds around the whole condition", () => {
    const onCommit = show({ condition: "ooa.window/exists", args: { title: "Done" }, waitSeconds: 30 });
    const wait = screen.getByRole("textbox", { name: "Keep checking for up to (seconds)" }) as HTMLInputElement;
    expect(wait.value).toBe("30");
    fireEvent.change(wait, { target: { value: "45" } });
    fireEvent.blur(wait);
    expect(onCommit).toHaveBeenLastCalledWith({ condition: "ooa.window/exists", args: { title: "Done" }, waitSeconds: 45 });
    const again = screen.getByRole("textbox", { name: "Keep checking for up to (seconds)" });   // drawn anew with the new value
    fireEvent.change(again, { target: { value: "" } });
    fireEvent.blur(again);
    expect(onCommit).toHaveBeenLastCalledWith({ condition: "ooa.window/exists", args: { title: "Done" } });
  });
});
