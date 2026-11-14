import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AgentSummary } from "../api/client";
import { WorkflowEditor } from "./ItemEditors";

/** Usability round (14 Nov 2026): a workflow stage's agent inputs as fields, and Assign to, Approver and To as a picker. */
const AGENTS: AgentSummary[] = [{ path: "Finance/Refund.flow.json", id: "refund", name: "Refund", variables: [
  { name: "order_id", type: "String", direction: "input", required: true, description: "The order to refund" },
  { name: "amount", type: "Number", direction: "input" }, { name: "done", type: "Boolean", direction: "output" }] }];

function Host({ start, onData }: { start: Record<string, unknown>; onData: (d: Record<string, unknown>) => void }) {
  const [d, setD] = useState(start);
  const agents = useState(() => async () => AGENTS)[0];
  return <WorkflowEditor data={d} tree={null} agents={agents} onChange={(x) => { setD(x); onData(x); }} />;
}
const stages = (d: Record<string, unknown>) => d.stages as Record<string, unknown>[];

describe("WorkflowEditor", () => {
  afterEach(cleanup);

  it("gives the chosen agent's inputs one field each, keeps $form.field$ values, and saves when focus leaves them", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ name: "Refunds", stages: [{ id: "bot", type: "bot", name: "Refund it", bot: "Finance/Refund.flow.json", inputs: { order_id: "$form.order$" } }] }} />);
    await act(async () => {});
    fireEvent.click(screen.getByRole("button", { name: /Refund it/ }));
    const group = screen.getByRole("group", { name: "Inputs of the agent" });
    expect((screen.getByLabelText("order_id *") as HTMLInputElement).value).toBe("$form.order$");
    expect(screen.queryByLabelText("done")).toBeNull();   // outputs are not inputs
    fireEvent.change(screen.getByLabelText("How amount is given"), { target: { value: "ref" } });
    fireEvent.change(screen.getByLabelText("amount"), { target: { value: "$form.amount$" } });
    expect(onData).not.toHaveBeenCalled();   // one undo step: kept until focus leaves the inputs
    fireEvent.blur(group, { relatedTarget: document.body });
    expect(stages(onData.mock.calls[0][0])[0].inputs).toEqual({ order_id: "$form.order$", amount: "$form.amount$" });
  });

  it("shows JSON that is not valid as an error instead of dropping it", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [{ id: "bot", type: "bot", name: "Refund it", bot: "Finance/Refund.flow.json" }] }} />);
    await act(async () => {});
    fireEvent.click(screen.getByRole("button", { name: /Refund it/ }));
    fireEvent.click(screen.getByRole("button", { name: "Edit as JSON" }));
    const box = screen.getByLabelText("Inputs of the agent as JSON");
    fireEvent.change(box, { target: { value: '{"order_id": ' } });
    expect(screen.getByRole("alert").textContent).toMatch(/Not valid JSON/);
    fireEvent.blur(screen.getByRole("group", { name: "Inputs of the agent" }), { relatedTarget: document.body });
    expect(onData).not.toHaveBeenCalled();
  });

  it("assigns tasks and approvals to users and roles typed by name, and sends notices to e-mail addresses too", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [{ id: "ok", type: "approval", name: "Approve", assignee: "role:admin" }, { id: "tell", type: "notify", name: "Tell" }] }} />);
    fireEvent.click(screen.getByRole("button", { name: /Approve/ }));
    expect(screen.getByText("Role: admin")).toBeTruthy();
    const who = screen.getByLabelText("Approver");
    fireEvent.focus(who);
    fireEvent.change(who, { target: { value: "fin.lead" } });
    fireEvent.mouseDown(screen.getByRole("option", { name: "Add user fin.lead" }));
    expect(stages(onData.mock.calls.at(-1)![0])[0].assignee).toBe("role:admin, user:fin.lead");
    fireEvent.click(screen.getByRole("button", { name: /Tell/ }));
    const to = screen.getByLabelText("To");
    fireEvent.focus(to);
    fireEvent.change(to, { target: { value: "ops@example.com" } });
    fireEvent.mouseDown(screen.getByRole("option", { name: "Add e-mail ops@example.com" }));
    expect(stages(onData.mock.calls.at(-1)![0])[1].to).toBe("ops@example.com");
  });
});
