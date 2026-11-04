import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { Flow } from "../api/client";
import { VariableDialog } from "./VariablesPanel";

afterEach(() => cleanup());

const empty = (): Flow => ({ kind: "TaskBot", id: "a", name: "A", variables: [], steps: [] });

/** Opens the New variable dialog, picks a type and returns what Create variable saves. */
function create(type: string, fill: (input: HTMLElement) => void) {
  let saved: Flow | null = null;
  render(<VariableDialog flow={empty()} onClose={() => {}} onSave={(f) => { saved = f; }} />);
  fireEvent.change(screen.getByLabelText("Type"), { target: { value: type } });
  fill(screen.getByLabelText("Default value"));
  fireEvent.click(screen.getByRole("button", { name: "Create variable" }));
  return saved!.variables![0];
}

describe("variable types Time, TimeSpan, Image and Binary", () => {
  it("offers the four types in the type list", () => {
    render(<VariableDialog flow={empty()} onClose={() => {}} onSave={() => {}} />);
    const labels = [...(screen.getByLabelText("Type") as HTMLSelectElement).options].map((o) => o.value);
    expect(labels).toEqual(expect.arrayContaining(["Time", "TimeSpan", "Image", "Binary"]));
  });

  it("saves a Time default as hours, minutes and seconds", () => {
    const v = create("Time", (input) => {
      expect(input).toHaveProperty("type", "time");
      fireEvent.change(input, { target: { value: "14:30" } });
    });
    expect(v).toMatchObject({ type: "Time", default: "14:30:00" });
  });

  it("saves a TimeSpan typed as 2h 15m in ISO form, and says when the text is not a length of time", () => {
    const v = create("TimeSpan", (input) => {
      fireEvent.change(input, { target: { value: "soon" } });
      expect(screen.getByRole("alert").textContent).toMatch(/Not a length of time/);
      fireEvent.change(input, { target: { value: "2h 15m" } });
      expect(screen.getByText("Saved as PT2H15M")).toBeTruthy();
    });
    expect(v).toMatchObject({ type: "TimeSpan", default: "PT2H15M" });
  });

  it("takes a file path as the default of an Image", () => {
    const v = create("Image", (input) => fireEvent.change(input, { target: { value: "pictures/logo.png" } }));
    expect(v).toMatchObject({ type: "Image", default: "pictures/logo.png" });
  });
});
