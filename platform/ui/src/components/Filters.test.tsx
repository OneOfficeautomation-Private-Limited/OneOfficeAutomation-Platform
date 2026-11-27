import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { DateRange } from "./DateRange";
import { ChoiceFilter, choiceHas, choiceParam, choiceText, choicesOf } from "./ChoiceFilter";
import { isoDay, type DateRangeValue } from "./rangeRules";

const NOW = new Date(2026, 10, 27, 15, 30);

function Dates({ start, ahead, onChange }: { start: DateRangeValue; ahead?: boolean; onChange?: (v: DateRangeValue) => void }) {
  const [v, setV] = useState(start);
  return <DateRange label="Period" value={v} now={NOW} ahead={ahead} onChange={(x) => { setV(x); onChange?.(x); }} />;
}
const open = () => { fireEvent.click(screen.getByRole("button", { name: /^Period: / })); return screen.getByRole("dialog", { name: "Period" }); };

describe("the date filter", () => {
  afterEach(cleanup);

  it("shows the choice and its days; a quick choice applies with Apply, and Cancel keeps the old one", () => {
    const changed = vi.fn();
    render(<Dates start={{ key: "7d" }} onChange={changed} />);
    expect(screen.getByRole("button", { name: /^Period: Last 7 days · / }).textContent).toMatch(/Last 7 days · .*21.* – .*27/);
    let pop = open();
    expect(within(pop).getAllByRole("radio").map((r) => r.textContent)).toEqual(["Today", "Yesterday", "Last 7 days", "Last 30 days", "Last 90 days", "This month", "Last month", "Any time", "Custom…"]);
    expect(within(pop).getByRole("radio", { name: "Last 7 days" }).getAttribute("aria-checked")).toBe("true");
    fireEvent.click(within(pop).getByRole("radio", { name: "Yesterday" }));
    expect((within(pop).getByLabelText("From date") as HTMLInputElement).value).toBe("2026-11-26");
    fireEvent.click(within(pop).getByRole("button", { name: "Cancel" }));
    expect(changed).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog", { name: "Period" })).toBeNull();
    pop = open();
    fireEvent.click(within(pop).getByRole("radio", { name: "Last month" }));
    fireEvent.click(within(pop).getByRole("button", { name: "Apply" }));
    expect(changed).toHaveBeenLastCalledWith({ key: "last-month" });
    expect(screen.getByRole("button", { name: /^Period: Last month/ })).toBeTruthy();
  });

  it("custom: two days clicked on the calendar, or typed From and To with times; From after To is refused", () => {
    const changed = vi.fn();
    render(<Dates start={{ key: "30d" }} onChange={changed} />);
    let pop = open();
    const day = (d: string) => pop.querySelector<HTMLButtonElement>('[data-day="' + d + '"]')!;
    fireEvent.click(day("2026-11-12"));
    fireEvent.click(day("2026-11-05"));   // an earlier day second: the range turns round
    expect(within(pop).getByRole("radio", { name: "Custom…" }).getAttribute("aria-checked")).toBe("true");
    expect(day("2026-11-08").className).toContain("is-in");
    expect(day("2026-11-30").getAttribute("aria-disabled")).toBe("true");   // still to come, when looking back
    fireEvent.click(within(pop).getByRole("button", { name: "Apply" }));
    expect(changed).toHaveBeenLastCalledWith({ key: "custom", from: "2026-11-05", to: "2026-11-12" });
    pop = open();
    fireEvent.change(within(pop).getByLabelText("From date"), { target: { value: "2026-11-20" } });
    fireEvent.change(within(pop).getByLabelText("From time (optional)"), { target: { value: "09:30" } });
    fireEvent.change(within(pop).getByLabelText("To date"), { target: { value: "2026-11-19" } });
    fireEvent.click(within(pop).getByRole("button", { name: "Apply" }));
    expect(within(pop).getByRole("alert").textContent).toBe("From must be before To.");
    fireEvent.change(within(pop).getByLabelText("To date"), { target: { value: "2026-11-21" } });
    fireEvent.click(within(pop).getByRole("button", { name: "Apply" }));
    expect(changed).toHaveBeenLastCalledWith({ key: "custom", from: "2026-11-20T09:30", to: "2026-11-21" });
  });

  it("keys: arrows move on the calendar, Enter picks the day, Esc closes", () => {
    render(<Dates start={{ key: "today" }} />);
    const pop = open();
    const today = pop.querySelector<HTMLButtonElement>('[data-day="2026-11-27"]')!;
    expect(today.tabIndex).toBe(0);
    fireEvent.keyDown(today, { key: "ArrowLeft" });
    expect(pop.querySelector<HTMLButtonElement>('[data-day="2026-11-26"]')!.tabIndex).toBe(0);
    fireEvent.keyDown(today, { key: "ArrowUp" });
    expect(pop.querySelector<HTMLButtonElement>('[data-day="' + isoDay(new Date(2026, 10, 19)) + '"]')!.tabIndex).toBe(0);
    fireEvent.keyDown(today, { key: "Enter" });
    expect((within(pop).getByLabelText("From date") as HTMLInputElement).value).toBe("2026-11-19");
    fireEvent.keyDown(pop, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Period" })).toBeNull();
  });

  it("looking ahead: today, tomorrow, the next 7 and 30 days, and days gone are dimmed", () => {
    const changed = vi.fn();
    render(<Dates start={{ key: "next7" }} ahead onChange={changed} />);
    expect(screen.getByRole("button", { name: /^Period: Next 7 days/ })).toBeTruthy();
    const pop = open();
    expect(within(pop).getAllByRole("radio").map((r) => r.textContent)).toEqual(["Today", "Tomorrow", "Next 7 days", "Next 30 days", "Custom…"]);
    expect(pop.querySelector('[data-day="2026-11-20"]')!.getAttribute("aria-disabled")).toBe("true");
    expect(pop.querySelector('[data-day="2026-11-30"]')!.getAttribute("aria-disabled")).toBeNull();
    fireEvent.click(within(pop).getByRole("radio", { name: "Tomorrow" }));
    fireEvent.click(within(pop).getByRole("button", { name: "Apply" }));
    expect(changed).toHaveBeenLastCalledWith({ key: "tomorrow" });
  });
});

function Choices({ onChange }: { onChange?: (v: string[]) => void }) {
  const [v, setV] = useState<string[]>([]);
  const options = Array.from({ length: 195 }, (_, i) => ({ value: "a" + i, label: "Agent " + i }));
  return <><ChoiceFilter label="Status" value={v} onChange={(x) => { setV(x); onChange?.(x); }} options={[{ value: "failed", label: "Failed" }, { value: "stopped", label: "Stopped" }, { value: "success", label: "Completed" }]} />
    <ChoiceFilter label="Agent" value={[]} onChange={() => undefined} options={options} /></>;
}

describe("the multi-choice filter", () => {
  afterEach(cleanup);

  it("ticks several, says how many are chosen, reads them on the button, and clears with ×", () => {
    const changed = vi.fn();
    render(<Choices onChange={changed} />);
    expect(screen.getByRole("button", { name: "Status: Any" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Status: Any" }));
    const pop = screen.getByRole("dialog", { name: "Status" });
    expect(within(pop).queryByRole("searchbox")).toBeNull();   // a short list needs no search
    fireEvent.click(within(pop).getByLabelText("Stopped"));
    fireEvent.click(within(pop).getByLabelText("Failed"));
    expect(changed).toHaveBeenLastCalledWith(["failed", "stopped"]);   // in the list's order
    expect(within(pop).getByRole("status").textContent).toBe("2 of 3 chosen");
    fireEvent.click(within(pop).getByRole("button", { name: "Done" }));
    expect(screen.getByRole("button", { name: "Status: Failed, Stopped" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Status: Failed, Stopped" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "Status" })).getByLabelText("Completed"));
    fireEvent.click(within(screen.getByRole("dialog", { name: "Status" })).getByRole("button", { name: "Done" }));
    expect(screen.getByRole("button", { name: "Status: 3 chosen" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear Status" }));
    expect(changed).toHaveBeenLastCalledWith([]);
    expect(screen.getByRole("button", { name: "Status: Any" })).toBeTruthy();
  });

  it("a long list has a search box", () => {
    render(<Choices />);
    fireEvent.click(screen.getByRole("button", { name: "Agent: Any" }));
    const pop = screen.getByRole("dialog", { name: "Agent" });
    expect(within(pop).getByRole("status").textContent).toBe("0 of 195 chosen");
    fireEvent.change(within(pop).getByRole("searchbox", { name: "Find agent" }), { target: { value: "agent 19" } });
    expect(within(pop).getAllByRole("checkbox")).toHaveLength(6);   // 19 and 190 to 194
  });

  it("keeps the chosen values as one text, and as a comma list for a server", () => {
    expect(choicesOf(choiceText(["Finance/Pay, monthly.flow.json", "b"]))).toEqual(["Finance/Pay, monthly.flow.json", "b"]);
    expect(choiceParam(choiceText(["failed", "stopped"]))).toBe("failed,stopped");
    expect(choiceHas("", "x")).toBe(true);
    expect(choiceHas(choiceText(["a", "b"]), "b")).toBe(true);
    expect(choiceHas(choiceText(["a", "b"]), "c")).toBe(false);
  });
});
