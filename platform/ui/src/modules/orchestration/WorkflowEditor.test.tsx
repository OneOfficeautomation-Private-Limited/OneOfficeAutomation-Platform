import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AgentSummary, WorkspaceNode } from "../../api/client";
import { WorkflowEditor } from "./WorkflowEditor";
import { problems } from "./flow";

/** OneAgentStudio's workflow editor, design A (review round, 26 Nov 2026): step list, diagram, settings, tabs, dry run. */
const AGENTS: AgentSummary[] = [{ path: "Finance/Refund.flow.json", id: "refund", name: "Refund", variables: [
  { name: "order_id", type: "String", direction: "input", required: true, description: "The order to refund" },
  { name: "amount", type: "Number", direction: "input" }, { name: "done", type: "Boolean", direction: "output" }] },
  { path: "Docs/Read invoice.flow.json", id: "read", name: "Read invoice", variables: [] }];
const TREE: WorkspaceNode = { name: "", path: "", type: "folder", children: [
  { name: "Refund.flow.json", path: "Finance/Refund.flow.json", type: "bot" }, { name: "Read invoice.flow.json", path: "Docs/Read invoice.flow.json", type: "bot" },
  { name: "New invoice.form.json", path: "Finance/New invoice.form.json", type: "form" }, { name: "Child.process.json", path: "Finance/Child.process.json", type: "process" }] };
type D = Record<string, unknown>;
const stages = (d: D) => d.stages as D[];

function Host({ start, onData, evaluate }: { start: D; onData: (d: D) => void; evaluate?: (e: string, v: D) => Promise<{ value?: unknown }> }) {
  const [d, setD] = useState(start);
  const agents = useState(() => async () => AGENTS)[0];
  const readItem = async (p: string) => p.startsWith("Docs/") ? '{"steps":[{"action":"ooa.idp/extract"}]}' : '{"steps":[]}';
  return <WorkflowEditor data={d} tree={TREE} agents={agents} readItem={readItem} evaluate={evaluate} onChange={(x) => { setD(x); onData(x); }} />;
}
const diagram = () => screen.getByRole("group", { name: "Workflow diagram" });
const step = (name: RegExp) => within(diagram()).getAllByRole("button", { name }).find((b) => b.getAttribute("aria-label")?.match(/^\d+ /))!;

describe("WorkflowEditor", () => {
  afterEach(cleanup);

  it("draws the steps numbered; a step selected shows its settings; an agent's inputs are fields, saved when focus leaves them", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ name: "Refunds", stages: [{ id: "bot", type: "bot", name: "Refund it", bot: "Finance/Refund.flow.json", inputs: { order_id: "$form.order$" } },
      { id: "end", type: "end", name: "Done" }] }} />);
    await act(async () => {});
    expect(step(/^1 Refund it, Agent task/)).toBeTruthy();
    expect(step(/^2 Done, End/)).toBeTruthy();
    fireEvent.click(step(/Refund it/));
    const settings = screen.getByRole("complementary", { name: "Settings" });
    expect((within(settings).getByLabelText("order_id *") as HTMLInputElement).value).toBe("$form.order$");
    expect(within(settings).queryByLabelText("done")).toBeNull();   // outputs are not inputs
    fireEvent.change(within(settings).getByLabelText("How amount is given"), { target: { value: "ref" } });
    fireEvent.change(within(settings).getByLabelText("amount"), { target: { value: "$form.amount$" } });
    expect(onData).not.toHaveBeenCalled();   // one undo step: kept until focus leaves the inputs
    fireEvent.blur(within(settings).getByRole("group", { name: "Inputs of the agent" }), { relatedTarget: document.body });
    expect(stages(onData.mock.calls[0][0])[0].inputs).toEqual({ order_id: "$form.order$", amount: "$form.amount$" });
  });

  it("adds steps from the step list (double-click after the selected one, or a + on the diagram), copies, pastes, moves and deletes them", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [{ id: "a", type: "approval", name: "Approve", assignee: "role:admin" }] }} />);
    fireEvent.click(step(/Approve/));
    fireEvent.doubleClick(within(screen.getByRole("complementary", { name: "Steps to add" })).getByRole("button", { name: "Notification" }));
    expect(stages(onData.mock.calls.at(-1)![0]).map((s) => s.type)).toEqual(["approval", "notify"]);
    expect(screen.getByRole("complementary", { name: "Settings" }).textContent).toContain("Notification");   // the new step is selected
    fireEvent.click(within(diagram()).getByRole("button", { name: /Add a step after 2/ }));
    expect(stages(onData.mock.calls.at(-1)![0]).length).toBe(3);
    fireEvent.click(step(/^1 Approve/));
    fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    fireEvent.click(screen.getByRole("button", { name: "Paste" }));
    const after = stages(onData.mock.calls.at(-1)![0]);
    expect(after.map((s) => s.id)).toEqual(["a", "a_copy", "notify", "bot"]);
    fireEvent.click(screen.getByRole("button", { name: "Move down" }));
    expect(stages(onData.mock.calls.at(-1)![0]).map((s) => s.id)).toEqual(["a", "notify", "a_copy", "bot"]);
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(stages(onData.mock.calls.at(-1)![0]).map((s) => s.id)).toEqual(["a", "notify", "bot"]);   // the pasted one was selected
  });

  it("gives a decision If, Else if and Else ways (an old yes/no decision becomes them), each to a step; the diagram shows where each goes", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [{ id: "d", type: "decision", name: "Amount?", condition: "$amount$ > 10", if_true: "big", if_false: "small" },
      { id: "big", type: "end", name: "Big" }, { id: "small", type: "end", name: "Small" }] }} />);
    expect(within(diagram()).getByRole("list", { name: "Where Amount? goes" }).textContent).toContain("Yes → 2 · Big");
    fireEvent.click(step(/Amount\?/));
    fireEvent.click(screen.getByRole("button", { name: "Add Else if" }));
    const d = stages(onData.mock.calls.at(-1)![0])[0];
    expect(d.condition).toBeUndefined();
    expect(d.branches).toEqual([{ name: "If", condition: "$amount$ > 10", goto: "big" }, { name: "Else if 2", condition: "" }]);
    expect(d.else).toBe("small");
    expect(screen.getByRole("tab", { name: /Problems/ }).textContent).toContain("1");   // the new way has no condition yet
  });

  it("sets an approval's approvers, how many must approve, its buttons, a reason to reject, and what happens when it is late", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [{ id: "a", type: "approval", name: "Approve", assignee: "user:asha, user:ben" }] }} />);
    fireEvent.click(step(/Approve/));
    const settings = screen.getByRole("complementary", { name: "Settings" });
    fireEvent.change(within(settings).getByLabelText("Approvals needed"), { target: { value: "all" } });
    expect(stages(onData.mock.calls.at(-1)![0])[0].approvals_needed).toBe("all");
    fireEvent.blur(Object.assign(within(settings).getByLabelText("Yes button"), { value: "Sign" }));
    expect(stages(onData.mock.calls.at(-1)![0])[0].approve_label).toBe("Sign");
    fireEvent.click(within(settings).getByLabelText("Ask for a reason when rejecting"));
    expect(stages(onData.mock.calls.at(-1)![0])[0].reason_required).toBe(true);
    fireEvent.blur(Object.assign(within(settings).getByLabelText("Due within (hours)"), { value: "24" }));
    fireEvent.change(within(screen.getByRole("complementary", { name: "Settings" })).getByLabelText("What happens"), { target: { value: "reassign" } });
    expect(stages(onData.mock.calls.at(-1)![0])[0].escalate).toEqual({ action: "reassign" });
    expect(screen.getByRole("tab", { name: /Problems/ }).textContent).toContain("1");   // to whom?
    // who it goes to: the starter, or one random member
    fireEvent.change(within(screen.getByRole("group", { name: "Approvers" })).getByLabelText("Approvers"), { target: { value: "starter" } });
    expect(stages(onData.mock.calls.at(-1)![0])[0].assignee).toBe("starter");
  });

  it("runs a Parallel step's branches side by side; a branch gets its steps from the + under its name", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [] }} />);
    fireEvent.doubleClick(screen.getByRole("button", { name: "Parallel" }));
    const branches = within(diagram()).getByRole("group", { name: /Branches of Parallel 1/ });
    expect(within(branches).getByText("Branch 1")).toBeTruthy();
    fireEvent.click(within(branches).getAllByRole("button", { name: "Add a step to this branch" })[1]);
    const par = stages(onData.mock.calls.at(-1)![0])[0] as { branches: { stages: D[] }[] };
    expect(par.branches[1].stages.map((s) => s.type)).toEqual(["bot"]);
    expect(par.branches[0].stages).toEqual([]);
  });

  it("lists problems (the server's checks) and takes you to the step; Start and data and Who does what", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [{ id: "f", type: "form", name: "Fill in" }, { id: "t", type: "notify", name: "Tell", to: "role:Finance, ops@example.com" }] }} />);
    fireEvent.click(screen.getByRole("tab", { name: /Problems/ }));
    const list = screen.getByRole("region", { name: "Problems" });
    expect(list.textContent).toContain("Human task Fill in needs a form");
    fireEvent.click(within(list).getAllByRole("button", { name: /Fill in/ })[0]);
    expect(step(/Fill in/).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(screen.getByRole("tab", { name: "Start and data" }));
    fireEvent.click(screen.getByLabelText("A form they fill in"));
    fireEvent.change(screen.getByLabelText("Start form"), { target: { value: "Finance/New invoice.form.json" } });
    expect(onData.mock.calls.at(-1)![0].start_form).toBe("Finance/New invoice.form.json");
    fireEvent.blur(Object.assign(screen.getByLabelText("Run title"), { value: "Invoice $number$" }));
    expect(onData.mock.calls.at(-1)![0].title).toBe("Invoice $number$");
    fireEvent.click(screen.getByRole("tab", { name: "Who does what" }));
    const who = screen.getByRole("table", { name: "Who does what" });
    expect(within(who).getAllByRole("row").map((r) => r.textContent)).toEqual(["WhoIsSteps", "FinanceRole2 Tell (told)", "ops@example.comE-mail2 Tell (told)"]);
  });

  it("offers only agents that read documents for Read a document", async () => {
    const onData = vi.fn();
    render(<Host onData={onData} start={{ stages: [] }} />);
    fireEvent.doubleClick(screen.getByRole("button", { name: "Read a document" }));
    await act(async () => {});
    const agent = within(screen.getByRole("complementary", { name: "Settings" })).getByLabelText("Agent") as HTMLSelectElement;
    expect([...agent.options].map((o) => o.value)).toEqual(["", "Docs/Read invoice.flow.json"]);
    expect(stages(onData.mock.calls.at(-1)![0])[0]).toMatchObject({ type: "bot", agent_kind: "document" });
  });

  it("test-runs the workflow without running anything: decisions from the data, answers given for each step, the path on the diagram", async () => {
    const evaluate = vi.fn(async (e: string, v: D) => ({ value: e === "$amount$ > 100" && Number(v.amount) > 100 }));
    render(<Host onData={() => {}} evaluate={evaluate} start={{ inputs: ["amount"], stages: [
      { id: "d", type: "decision", name: "Big?", branches: [{ name: "Big", condition: "$amount$ > 100", goto: "ok" }], else: "small" },
      { id: "ok", type: "approval", name: "Boss signs", assignee: "user:boss", on_reject: "end" },
      { id: "small", type: "end", name: "Small", status: "completed", message: "Small one: $amount$" }] }} />);
    fireEvent.click(screen.getByRole("button", { name: "Test run…" }));
    const panel = screen.getByRole("region", { name: "Test run" });
    fireEvent.change(within(panel).getByLabelText("amount"), { target: { value: "500" } });
    await act(async () => { fireEvent.click(within(panel).getByRole("button", { name: "Start the test run" })); });
    expect(evaluate).toHaveBeenCalledWith("$amount$ > 100", expect.objectContaining({ amount: "500" }));
    const ask = within(panel).getByRole("group", { name: "Step Boss signs" });
    expect(step(/Boss signs/).getAttribute("aria-label")).toContain("now");
    await act(async () => { fireEvent.click(within(ask).getByRole("button", { name: "Reject" })); });
    expect(within(panel).getByRole("status").textContent).toContain("ends completed: Ended after the rejection at Boss signs");
    expect(within(panel).getByRole("list", { name: "Path taken" }).textContent).toContain("Big?: Took Big");
    expect(step(/Small/).getAttribute("aria-label")).not.toContain("done");
  });
});

describe("the workflow checks", () => {
  it("find what the server refuses at check-in, and an agent's new input", () => {
    const p = problems({ stages: [{ id: "p", type: "parallel", name: "Both", branches: [{ name: "A", stages: [] }] },
      { id: "w", type: "wait", name: "Pause" }, { id: "b", type: "bot", name: "Refund", bot: "Finance/Refund.flow.json", inputs: {} }] }, AGENTS).map((x) => x.message);
    expect(p).toEqual(["Parallel Both needs at least two branches", "Parallel Both: A has no steps", "Wait Pause needs a date and time or a period", "Refund: the agent has a new input order_id"]);
  });
});
