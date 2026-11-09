import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RetryFields, StepErrorFields } from "./ErrorPolicy";
import { setStepPolicy } from "./flowEdit";
import type { Flow } from "../api/client";

describe("If this step fails (command review C3)", () => {
  afterEach(cleanup);

  it("sets continue on error, and trying again for any error with a wait", () => {
    const onChange = vi.fn();
    render(<StepErrorFields stepId="s1" canRetry onChange={onChange} />);
    fireEvent.change(screen.getByLabelText("If it fails"), { target: { value: "continue" } });
    expect(onChange).toHaveBeenLastCalledWith({ continueOnError: true });
    fireEvent.change(screen.getByLabelText("Times to try again"), { target: { value: "3" } });
    expect(onChange).toHaveBeenLastCalledWith({ retry: { max: 3 } });
  });

  it("offers temporary errors, any error or the errors named", () => {
    const onChange = vi.fn();
    render(<RetryFields idPrefix="a" value={{ max: 2, delayMs: 2000 }} onChange={onChange} />);
    expect((screen.getByLabelText("Wait between tries (seconds)") as HTMLInputElement).value).toBe("2");
    fireEvent.change(screen.getByLabelText("For which errors"), { target: { value: "any" } });
    expect(onChange).toHaveBeenLastCalledWith({ max: 2, delayMs: 2000, codes: ["*"] });
    fireEvent.click(screen.getByLabelText(/Wait twice as long/));
    expect(onChange).toHaveBeenLastCalledWith({ max: 2, delayMs: 2000, backoff: "exponential" });
  });

  it("does not offer trying a block again, and 0 removes the setting", () => {
    const onChange = vi.fn();
    render(<RetryFields idPrefix="a" value={{ max: 1 }} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText("Times to try again"), { target: { value: "0" } });
    expect(onChange).toHaveBeenLastCalledWith(undefined);
    cleanup();
    render(<StepErrorFields stepId="s1" canRetry={false} onChange={() => {}} />);
    expect(screen.queryByLabelText("Times to try again")).toBeNull();
  });

  it("keeps the settings on the step, where the runner reads them", () => {
    const f: Flow = { kind: "TaskBot", id: "t", name: "t", steps: [{ id: "s1", action: "ooa.file/copy" }] };
    const g = setStepPolicy(setStepPolicy(f, "s1", { continueOnError: true }), "s1", { retry: { max: 2, codes: ["TIMEOUT"] } });
    expect(g.steps[0]).toMatchObject({ continueOnError: true, retry: { max: 2, codes: ["TIMEOUT"] } });
    expect(setStepPolicy(g, "s1", { continueOnError: false, retry: undefined }).steps[0]).toEqual({ id: "s1", action: "ooa.file/copy" });
  });
});
