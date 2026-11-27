import { AHEAD, BACK, RANGE_NAMES, resolveRange, type RangeKey } from "../components/rangeRules";
import { DURATION_CHIPS, EMPTY, columnKind, columnUnit, parseCellDate, passes, type ColumnFilter, type ColumnKind } from "./columnFilters";
import "./tableTools.css";

/**
 * Column tools for every table in the console (review round 2, 31 Oct 2026): drag a column's right edge to widen or
 * narrow it (double-click to fit it again), and filter a column with its funnel, as in a spreadsheet.
 *
 * The funnel fits the column (tables and filters, item 2c, 27 Nov 2026): a date column gets the date choices and
 * From / To; a number or duration column From / To in its unit (durations with quick choices such as Under 1 min); a
 * column of a few values a tick list with counts; a column of many values Contains / Starts with / Is and a text box
 * with a live count. Each has Apply and Clear.
 *
 * The tables are drawn by each page; this adds the tools to them from outside, so no page has to change. It only
 * appends its own small elements to header cells and sets widths and the hidden attribute, which React leaves alone,
 * and it runs again whenever a table's rows change. Widths are remembered per table in this browser (a convenience:
 * nothing breaks when storage is not there); filters last while the page is open.
 */
const TABLES = "table.cc-list-table, table.st-table, table.ov-t, table.cc-au-kv, table.df-table";
/** A header's words as shown (words only for screen readers, in .st-sr, do not count). */
const shownText = (th: HTMLTableCellElement) => [...th.childNodes].filter((n) => !(n instanceof HTMLElement && (n.classList.contains("st-sr") || n.classList.contains("cc-col-filter") || n.classList.contains("cc-col-grip"))))
  .map((n) => n.textContent ?? "").join("").trim();
const SKIP = (th: HTMLTableCellElement) => !shownText(th) || th.classList.contains("cc-check") || th.classList.contains("cc-list-menu") || th.scope === "row";
const WIDTHS = "ooa.columns.";
const MAX_VALUES = 200;

type Filters = Map<number, ColumnFilter>;
const keyOf = (t: HTMLTableElement) => t.getAttribute("aria-label") ?? t.closest("[aria-label]")?.getAttribute("aria-label") ?? "table";
const labelOf = (th: HTMLTableCellElement) => shownText(th);
const fullText = (td: HTMLTableCellElement | undefined) => (td?.innerText ?? td?.textContent ?? "").replace(/\s+/g, " ").trim();
const cellText = (td: HTMLTableCellElement | undefined) => { const t = fullText(td); return t.length > 80 ? t.slice(0, 79) + "…" : t || EMPTY; };
/** The text a filter tests: the full text for ranges and words, the shortened one for ticked values. */
const testText = (f: ColumnFilter, td: HTMLTableCellElement | undefined) => f.kind === "values" ? cellText(td) : fullText(td);

function read(key: string): Record<string, number> {
  try { return JSON.parse(localStorage.getItem(WIDTHS + key) ?? "{}"); } catch { return {}; }
}
function write(key: string, widths: Record<string, number>) {
  try { localStorage.setItem(WIDTHS + key, JSON.stringify(widths)); } catch { /* storage off: widths last until the page closes */ }
}

/** The data rows: those with a cell per column (day headings, empty and loading rows span the table and stay). */
const dataRows = (t: HTMLTableElement, cols: number) => [...(t.tBodies[0]?.rows ?? [])].filter((r) => r.cells.length === cols);

/** Small DOM helpers for the panels. */
function el<K extends keyof HTMLElementTagNameMap>(tag: K, props: Partial<HTMLElementTagNameMap[K]> & { className?: string } = {}, ...kids: (Node | string)[]): HTMLElementTagNameMap[K] {
  const e = Object.assign(document.createElement(tag), props);
  e.append(...kids);
  return e;
}
const btn = (text: string, cls: string, on: () => void) => { const b = el("button", { type: "button", className: cls, textContent: text }); b.addEventListener("click", on); return b; };

export function enableTableTools(root: Document | HTMLElement = document): () => void {
  const filters = new WeakMap<HTMLTableElement, Filters>();
  const ready = new WeakSet<HTMLTableElement>();
  let pop: HTMLElement | null = null;

  const apply = (t: HTMLTableElement) => {
    const f = filters.get(t);
    const head = t.tHead?.rows[0];
    if (!head) return;
    if (!f) { t.querySelectorAll<HTMLTableRowElement>("tbody tr[hidden]").forEach((r) => { r.hidden = false; }); head.querySelectorAll(".cc-col-filter.is-on").forEach((b) => b.classList.remove("is-on")); note(t); return; }
    const now = new Date();
    for (const r of dataRows(t, head.cells.length)) {
      const hide = [...f.entries()].some(([i, c]) => !passes(c, testText(c, r.cells[i]), now));
      if (r.hidden !== hide) r.hidden = hide;
    }
    // a day heading (a row across the table) shows only while a row under it does
    let heading: HTMLTableRowElement | null = null, any = false;
    const close = () => { if (heading && heading.hidden !== !any) heading.hidden = !any; };
    for (const r of [...(t.tBodies[0]?.rows ?? [])]) {
      if (r.classList.contains("cc-au-day")) { close(); heading = r; any = false; }
      else if (r.cells.length === head.cells.length && !r.hidden) any = true;
    }
    close();
    for (const [i, th] of [...head.cells].entries()) th.querySelector(".cc-col-filter")?.classList.toggle("is-on", f.has(i));
    note(t);
  };
  const setFilter = (t: HTMLTableElement, i: number, c: ColumnFilter | null) => {
    const f = filters.get(t) ?? new Map<number, ColumnFilter>();
    if (c) f.set(i, c); else f.delete(i);
    if (f.size) filters.set(t, f); else filters.delete(t);
    apply(t);
  };

  // 14 Nov usability round, item 19: a line above a filtered table says which columns filter it, with Clear, so a
  // filter set with a column's small funnel is never forgotten
  const note = (t: HTMLTableElement) => {
    const f = filters.get(t);
    const head = t.tHead?.rows[0];
    let line = t.previousElementSibling as HTMLElement | null;
    if (!line?.classList.contains("cc-col-filtered")) line = null;
    if (!f || !f.size || !head) { line?.remove(); return; }
    if (!line) {
      line = document.createElement("div");
      line.className = "cc-col-filtered"; line.setAttribute("role", "status");
      t.parentElement?.insertBefore(line, t);
    }
    const names = [...f.keys()].map((i) => labelOf(head.cells[i]));
    const shown = dataRows(t, head.cells.length).filter((r) => !r.hidden).length;
    line.textContent = "Filtered by " + names.join(", ") + ": " + shown + (shown === 1 ? " row" : " rows") + " shown. ";
    line.append(btn("Clear column filters", "cc-col-filtered-clear", () => { filters.delete(t); apply(t); }));
  };

  const freeze = (t: HTMLTableElement) => {
    if (t.classList.contains("cc-cols-fixed")) return;
    const head = t.tHead!.rows[0];
    for (const th of head.cells) th.style.width = th.getBoundingClientRect().width + "px";
    t.classList.add("cc-cols-fixed");
  };
  const fitTable = (t: HTMLTableElement) => {
    const head = t.tHead!.rows[0];
    const sum = [...head.cells].reduce((n, th) => n + (parseFloat(th.style.width) || th.getBoundingClientRect().width), 0);
    const room = t.parentElement?.clientWidth ?? sum;
    t.style.width = Math.max(sum, room) + "px";
  };
  const save = (t: HTMLTableElement) => {
    const out: Record<string, number> = {};
    for (const th of t.tHead!.rows[0].cells) if (th.style.width && labelOf(th)) out[labelOf(th)] = Math.round(parseFloat(th.style.width));
    write(keyOf(t), out);
  };

  const closePop = () => { pop?.remove(); pop = null; };

  /** A tick list of the column's values with counts, Select all, and a search box when the list is long. */
  const valuesPanel = (label: string, values: string[], counts: Map<string, number>, current: ColumnFilter | undefined) => {
    const allowed = new Set(current?.kind === "values" ? current.allowed : values);
    const parts: HTMLElement[] = [];
    const search = el("input", { type: "search", className: "ui-input", placeholder: "Find a value" });
    search.setAttribute("aria-label", "Find a value of " + label);
    if (values.length > 8) parts.push(search);
    const allBox = el("input", { type: "checkbox" });
    const all = el("label", { className: "cc-col-pop-all" }, allBox, " Select all");
    const list = el("div", { className: "cc-col-pop-list" });
    list.setAttribute("role", "group"); list.setAttribute("aria-label", "Values of " + label);
    const boxes = values.map((v) => {
      const box = el("input", { type: "checkbox", checked: allowed.has(v), value: v });
      const row = el("label", {}, box, " " + v, el("span", { className: "cc-col-pop-n", textContent: String(counts.get(v)) }));
      list.append(row);
      return { v, box, row };
    });
    const sync = () => { const on = boxes.filter((b) => b.box.checked).length; allBox.checked = on === values.length; allBox.indeterminate = on > 0 && on < values.length; };
    allBox.addEventListener("change", () => { for (const b of boxes) if (!b.row.hidden) b.box.checked = allBox.checked; sync(); });
    for (const b of boxes) b.box.addEventListener("change", sync);
    search.addEventListener("input", () => { const q = search.value.trim().toLowerCase(); for (const b of boxes) b.row.hidden = !!q && !b.v.toLowerCase().includes(q); });
    sync();
    parts.push(all, list);
    return { parts, focus: values.length > 8 ? search : allBox, read: (): ColumnFilter | null => {
      const on = boxes.filter((b) => b.box.checked).map((b) => b.v);
      return on.length === values.length ? null : { kind: "values", allowed: new Set(on) };
    } };
  };

  /** The date choices (looking back, or ahead when the column's dates are mostly to come) and From / To. */
  const datePanel = (label: string, cells: string[], current: ColumnFilter | undefined) => {
    const now = new Date();
    const times = cells.map((c) => parseCellDate(c, now)).filter((x): x is number => x != null);
    const ahead = times.filter((x) => x > now.getTime()).length > times.length / 2;
    const keys = (ahead ? AHEAD : BACK).filter((k) => k !== "any" && k !== "custom");
    let chosen: RangeKey | null = current?.kind === "range" && current.label ? current.label as RangeKey : null;
    const day = (ms: number | null) => { if (ms == null) return ""; const d = new Date(ms); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
    const from = el("input", { type: "date", className: "ui-input" }), to = el("input", { type: "date", className: "ui-input" });
    from.setAttribute("aria-label", "From"); to.setAttribute("aria-label", "To");
    if (current?.kind === "range") { from.value = day(current.from); to.value = day(current.to == null ? null : current.to - 1); }
    const list = el("div", { className: "cc-col-dates" });
    list.setAttribute("role", "radiogroup"); list.setAttribute("aria-label", "Quick choices for " + label);
    const choices = keys.map((k) => {
      const b = btn(RANGE_NAMES[k], "cc-col-choice", () => {
        chosen = k;
        const r = resolveRange({ key: k }, new Date());
        from.value = day(r.from?.getTime() ?? null); to.value = day(r.to ? r.to.getTime() - 1 : null);
        mark();
      });
      b.setAttribute("role", "radio");
      list.append(b);
      return { k, b };
    });
    const mark = () => { for (const c of choices) { c.b.setAttribute("aria-checked", String(c.k === chosen)); c.b.classList.toggle("is-on", c.k === chosen); } };
    for (const i of [from, to]) i.addEventListener("input", () => { chosen = null; mark(); });
    mark();
    const fields = el("div", { className: "cc-col-range" }, el("label", {}, "From ", from), el("label", {}, "To ", to));
    return { parts: [list, fields], focus: choices[0]?.b ?? from, read: (): ColumnFilter | null => {
      if (chosen) { const r = resolveRange({ key: chosen }, new Date()); return { kind: "range", type: "date", from: r.from?.getTime() ?? null, to: r.to?.getTime() ?? null, label: chosen }; }
      const a = from.value ? new Date(from.value + "T00:00:00").getTime() : null;
      const b = to.value ? new Date(to.value + "T00:00:00").getTime() + 86_400_000 : null;
      return a == null && b == null ? null : { kind: "range", type: "date", from: a, to: b };
    } };
  };

  /** From / To in the column's unit; durations get quick choices and are typed in minutes. */
  const numberPanel = (kind: "duration" | "number", unit: string, current: ColumnFilter | undefined) => {
    const scale = kind === "duration" ? 60 : 1;
    const from = el("input", { type: "number", className: "ui-input", step: "any" }), to = el("input", { type: "number", className: "ui-input", step: "any" });
    from.setAttribute("aria-label", "From" + (unit ? " (" + unit + ")" : "")); to.setAttribute("aria-label", "To" + (unit ? " (" + unit + ")" : ""));
    if (current?.kind === "range") { from.value = current.from == null ? "" : String(current.from / scale); to.value = current.to == null ? "" : String(current.to / scale); }
    const parts: HTMLElement[] = [];
    if (kind === "duration") {
      const chips = el("div", { className: "cc-col-chips" });
      chips.setAttribute("role", "group"); chips.setAttribute("aria-label", "Quick choices");
      for (const [l, a, b] of DURATION_CHIPS) chips.append(btn(l, "cc-col-chip", () => { from.value = a == null ? "" : String(a / 60); to.value = b == null ? "" : String(b / 60); }));
      parts.push(chips);
    }
    const u = (s: string) => unit ? el("span", { className: "cc-col-unit", textContent: s }) : "";
    parts.push(el("div", { className: "cc-col-range" }, el("label", {}, "From ", from, u(unit)), el("label", {}, "To ", to, u(unit))));
    return { parts, focus: from, read: (): ColumnFilter | null => {
      const a = from.value === "" ? null : Number(from.value) * scale, b = to.value === "" ? null : Number(to.value) * scale;
      return a == null && b == null ? null : { kind: "range", type: kind, from: a, to: b };
    } };
  };

  /** Contains / Starts with / Is, a text box, and how many rows match as it is typed. */
  const textPanel = (label: string, cells: string[], current: ColumnFilter | undefined) => {
    const op = el("select", { className: "ui-input ui-select" });
    op.setAttribute("aria-label", "How to match " + label);
    for (const [v, l] of [["contains", "Contains"], ["starts", "Starts with"], ["is", "Is"]]) op.append(el("option", { value: v, textContent: l }));
    const box = el("input", { type: "search", className: "ui-input", placeholder: "Text to match" });
    box.setAttribute("aria-label", "Text to match in " + label);
    if (current?.kind === "text") { op.value = current.op; box.value = current.text; }
    const count = el("div", { className: "cc-col-count" });
    count.setAttribute("role", "status");
    const f = (): ColumnFilter => ({ kind: "text", op: op.value as "contains" | "starts" | "is", text: box.value });
    const live = () => { const n = cells.filter((c) => passes(f(), c)).length; count.textContent = n + " of " + cells.length + " match"; };
    op.addEventListener("change", live); box.addEventListener("input", live);
    live();
    return { parts: [el("div", { className: "cc-col-text" }, op, box), count], focus: box, read: (): ColumnFilter | null => box.value.trim() ? f() : null };
  };

  const openFilter = (t: HTMLTableElement, i: number, button: HTMLButtonElement) => {
    closePop();
    const head = t.tHead!.rows[0];
    const rows = dataRows(t, head.cells.length);
    const counts = new Map<string, number>();
    for (const r of rows) { const v = cellText(r.cells[i]); counts.set(v, (counts.get(v) ?? 0) + 1); }
    const values = [...counts.keys()].sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).slice(0, MAX_VALUES);
    const cells = rows.map((r) => fullText(r.cells[i]));
    const current = filters.get(t)?.get(i);
    const kind: ColumnKind = current?.kind === "values" ? "values" : current?.kind === "text" ? "text" : current?.kind === "range" ? current.type : columnKind(cells);
    const label = labelOf(head.cells[i]);

    pop = el("div", { className: "cc-col-pop is-" + kind });
    pop.setAttribute("role", "dialog");
    pop.setAttribute("aria-label", "Filter " + label);
    pop.dataset.kind = kind;
    const finish = () => { closePop(); button.focus(); };
    const panel = kind === "date" ? datePanel(label, cells, current)
      : kind === "duration" || kind === "number" ? numberPanel(kind, columnUnit(kind, cells), current)
      : kind === "text" ? textPanel(label, cells, current)
      : valuesPanel(label, values, counts, current);
    const foot = el("div", { className: "cc-col-pop-foot" },
      btn("Clear", "ui-btn ui-btn-plain", () => { setFilter(t, i, null); finish(); }),
      btn("Apply", "ui-btn ui-btn-primary", () => { setFilter(t, i, panel.read()); finish(); }));
    pop.append(...panel.parts, foot);
    pop.addEventListener("keydown", (e) => {
      if (e.key === "Escape") { e.stopPropagation(); finish(); }
      else if (e.key === "Enter" && (e.target as HTMLElement).tagName === "INPUT" && (e.target as HTMLInputElement).type !== "checkbox") { e.preventDefault(); setFilter(t, i, panel.read()); finish(); }
    });
    document.body.append(pop);
    const r = button.getBoundingClientRect(), w = pop.offsetWidth || 280, h = pop.offsetHeight || 340;
    pop.style.top = Math.max(8, Math.min(r.bottom + 4, window.innerHeight - h - 8)) + "px";
    pop.style.left = Math.max(8, Math.min(r.left - 8, window.innerWidth - w - 8)) + "px";
    panel.focus.focus();
  };

  const setup = (t: HTMLTableElement) => {
    const head = t.tHead?.rows[0];
    if (!head) return;
    if (!ready.has(t)) {
      ready.add(t);
      const saved = read(keyOf(t));
      for (const [i, th] of [...head.cells].entries()) {
        if (SKIP(th)) continue;
        const label = labelOf(th);
        th.classList.add("cc-col-th");
        if (dataRows(t, head.cells.length).length > 1 || t.classList.contains("cc-list-table")) {
          const b = el("button", { type: "button", className: "cc-col-filter", title: "Filter " + label });
          b.setAttribute("aria-label", "Filter " + label);
          b.innerHTML = '<svg aria-hidden="true" width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M3 4h18l-7 9v6l-4 2v-8z"/></svg>';
          b.addEventListener("click", (e) => { e.stopPropagation(); if (pop?.getAttribute("aria-label") === "Filter " + label) closePop(); else openFilter(t, i, b); });
          th.append(b);
        }
        const grip = el("span", { className: "cc-col-grip", title: "Drag to widen or narrow; double-click to fit" });
        grip.setAttribute("aria-hidden", "true");
        grip.addEventListener("pointerdown", (e) => {
          e.preventDefault(); e.stopPropagation();
          freeze(t);
          const x0 = e.clientX, w0 = th.getBoundingClientRect().width;
          const move = (m: PointerEvent) => { th.style.width = Math.max(48, w0 + m.clientX - x0) + "px"; fitTable(t); };
          const up = () => { removeEventListener("pointermove", move); removeEventListener("pointerup", up); save(t); };
          addEventListener("pointermove", move); addEventListener("pointerup", up);
        });
        grip.addEventListener("dblclick", (e) => {
          e.stopPropagation();
          for (const c of head.cells) c.style.width = "";
          t.classList.remove("cc-cols-fixed"); t.style.width = "";
          write(keyOf(t), {});
        });
        grip.addEventListener("click", (e) => e.stopPropagation());
        th.append(grip);
        if (saved[label]) { th.style.width = saved[label] + "px"; t.classList.add("cc-cols-fixed"); }
      }
      if (t.classList.contains("cc-cols-fixed")) { for (const th of head.cells) if (!th.style.width) th.style.width = Math.max(48, th.getBoundingClientRect().width) + "px"; fitTable(t); }
    }
    apply(t);
  };

  let queued = false;
  const scan = () => { queued = false; root.querySelectorAll<HTMLTableElement>(TABLES).forEach(setup); };
  const later = () => { if (!queued) { queued = true; requestAnimationFrame(scan); } };
  const watch = new MutationObserver(later);
  watch.observe(root instanceof Document ? root.body : root, { childList: true, subtree: true, characterData: true });
  const outside = (e: MouseEvent) => { if (pop && !pop.contains(e.target as Node) && !(e.target as HTMLElement).closest?.(".cc-col-filter")) closePop(); };
  document.addEventListener("mousedown", outside);
  scan();
  return () => { watch.disconnect(); document.removeEventListener("mousedown", outside); closePop(); };
}
