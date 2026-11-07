import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { StageBar } from "./Inbox";

describe("stage bar (G48)", () => {
  afterEach(cleanup);
  it("marks the stages before the task done, this one current, the rest to come", () => {
    render(<StageBar stages={[{ id: "req", name: "Request" }, { id: "rev", name: "Review" }, { id: "pay", name: "Pay" }]} current="rev" done={false} />);
    const items = screen.getAllByRole("listitem");
    expect(items.map((i) => i.className)).toEqual(["is-done", "is-current", "is-next"]);
    expect(items[1].getAttribute("aria-current")).toBe("step");
    expect(items[0].textContent).toContain("(done)");
    expect(screen.getByRole("list", { name: "Stages of this request" })).toBeTruthy();
  });
  it("a completed task's own stage is done", () => {
    const { container } = render(<StageBar stages={[{ id: "a", name: "A" }, { id: "b", name: "B" }]} current="b" done />);
    expect(within(container).getAllByRole("listitem").map((i) => i.className)).toEqual(["is-done", "is-done"]);
  });
});
