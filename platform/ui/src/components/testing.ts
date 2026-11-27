import { fireEvent } from "@testing-library/react";

/**
 * For tests: a drop-down's choice, whether it is the browser's own (a short list) or the searchable list (a long one,
 * item 3d of the fix list of 27 Nov 2026, round 2). `el` is the select, or the searchable list's field (its combobox).
 */
export function choose(el: HTMLElement, value: string) {
  if (el instanceof HTMLSelectElement) { fireEvent.change(el, { target: { value } }); return; }
  const option = listOf(el).find((o) => o.dataset.value === value);
  if (!option) throw new Error("No choice " + JSON.stringify(value) + " in " + (el.getAttribute("aria-label") ?? el.id));
  fireEvent.mouseDown(option);
  el.blur();
}

/** For tests: a drop-down's choices (value and label), from a select or the searchable list (opened to read them). */
export function choicesOf(el: HTMLElement): { value: string; label: string }[] {
  if (el instanceof HTMLSelectElement) return [...el.options].map((o) => ({ value: o.value, label: o.textContent ?? "" }));
  const all = listOf(el).map((o) => ({ value: o.dataset.value ?? "", label: o.querySelector(".ui-ss-label")?.textContent ?? "" }));
  fireEvent.keyDown(el, { key: "Escape" });
  return all;
}

/** For tests: a drop-down's value, from a select or the searchable list's field. */
export const valueOf = (el: HTMLElement) => (el instanceof HTMLSelectElement ? el.value : el.dataset.value ?? "");

function listOf(el: HTMLElement): HTMLElement[] {
  el.focus();   // as a person would: the dialog around it leaves Esc to the open list
  if (el.getAttribute("aria-expanded") !== "true") fireEvent.click(el);
  const list = document.getElementById(el.getAttribute("aria-controls") ?? "");
  if (!list) throw new Error("Not a drop-down: " + el.outerHTML.slice(0, 120));
  return [...list.querySelectorAll<HTMLElement>("[role=option]")];
}
