import { fireEvent, screen, within } from "@testing-library/react";

/**
 * For tests of pages with the shared filters (item 2): tick choices in a multi-choice filter, pick a period in the
 * date filter, and read what a filter's button says.
 */
const named = (label: string) => new RegExp("^" + label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + ": ");
export const filterButton = (label: string, root: HTMLElement = document.body) => within(root).getByRole("button", { name: named(label) });

/** Ticks (or unticks) the named choices of a multi-choice filter, then closes its panel. */
export function chooseIn(label: string, ...choices: string[]) {
  fireEvent.click(filterButton(label));
  const pop = screen.getByRole("dialog", { name: label });
  for (const c of choices) fireEvent.click(within(pop).getByLabelText(c));
  fireEvent.click(within(pop).getByRole("button", { name: "Done" }));
}

/** Picks a quick choice ("Last 7 days") in a date filter and applies it. */
export function pickPeriod(label: string, choice: string) {
  fireEvent.click(filterButton(label));
  const pop = screen.getByRole("dialog", { name: label });
  fireEvent.click(within(pop).getByRole("radio", { name: choice }));
  fireEvent.click(within(pop).getByRole("button", { name: "Apply" }));
}

/** The start of the day n days ago, as a query writes it (for "Last 7 days": n = 6). */
export const dayStartParam = (daysAgo: number) => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - daysAgo); return "from=" + encodeURIComponent(d.toISOString()); };
