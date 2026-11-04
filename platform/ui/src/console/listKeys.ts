import type { KeyboardEvent } from "react";
import { inTextField, is } from "../components";

/**
 * The keys of every console list (item 9, 1 Nov 2026), on the page's main area: / goes to the list's search; ↓ and ↑
 * move between rows (from the search, ↓ goes to the first row); Enter opens the row with focus, as a click does; Shift+F10
 * (or the menu key) opens its ⋯ menu.
 */
export function listKeys(ev: KeyboardEvent<HTMLElement>) {
  const e = ev.nativeEvent, main = ev.currentTarget, target = e.target as HTMLElement;
  if (!inTextField(e) && is(e, "list.search")) {
    const box = main.querySelector<HTMLInputElement>(".cc-list-search input, input[aria-label^='Search']");
    if (box) { ev.preventDefault(); box.focus(); box.select(); }
    return;
  }
  const row = target.closest?.("tbody tr") as HTMLTableRowElement | null;
  const fromSearch = !row && !!target.closest?.(".cc-list-search, .cc-list-filters") && is(e, "list.next");
  if ((row || fromSearch) && (is(e, "list.next") || is(e, "list.previous"))) {
    if (row && target !== row && inTextField(e)) return;   // a field in a row keeps its arrows
    const table = row ? row.closest("table") : main.querySelector(".cc-list-table");
    const rows = [...(table?.querySelectorAll<HTMLTableRowElement>("tbody tr") ?? [])].filter((r) => !r.querySelector(".cc-none"));
    const at = row ? rows.indexOf(row) : -1;
    const next = rows[is(e, "list.next") ? at + 1 : at - 1];
    if (next) { ev.preventDefault(); next.tabIndex = -1; next.focus(); }
    return;
  }
  if (!row || target !== row) return;   // Enter and the menu key act on a row that has focus itself
  if (is(e, "list.open")) { ev.preventDefault(); row.click(); }
  else if (is(e, "list.menu")) {
    const menu = row.querySelector<HTMLElement>(".cc-list-menu button");
    if (menu) { ev.preventDefault(); menu.focus(); menu.dispatchEvent(new globalThis.KeyboardEvent("keydown", { key: "Enter", bubbles: true })); }
  }
}
