/**
 * Column tools for every table in the console (review round 2, 31 Oct 2026): drag a column's right edge to widen or
 * narrow it (double-click to fit it again), and filter a column by ticking the values to show, as in a spreadsheet.
 *
 * The tables are drawn by each page; this adds the tools to them from outside, so no page has to change. It only
 * appends its own small elements to header cells and sets widths and the hidden attribute, which React leaves alone,
 * and it runs again whenever a table's rows change. Widths are remembered per table in this browser (a convenience:
 * nothing breaks when storage is not there); filters last while the page is open.
 */
const TABLES = "table.cc-list-table, table.st-table, table.ov-t, table.cc-au-kv";
/** A header's words as shown (words only for screen readers, in .st-sr, do not count). */
const shownText = (th: HTMLTableCellElement) => [...th.childNodes].filter((n) => !(n instanceof HTMLElement && (n.classList.contains("st-sr") || n.classList.contains("cc-col-filter") || n.classList.contains("cc-col-grip"))))
  .map((n) => n.textContent ?? "").join("").trim();
const SKIP = (th: HTMLTableCellElement) => !shownText(th) || th.classList.contains("cc-check") || th.classList.contains("cc-list-menu") || th.scope === "row";
const WIDTHS = "ooa.columns.";
const MAX_VALUES = 200;

type Filters = Map<number, Set<string>>;
const keyOf = (t: HTMLTableElement) => t.getAttribute("aria-label") ?? t.closest("[aria-label]")?.getAttribute("aria-label") ?? "table";
const labelOf = (th: HTMLTableCellElement) => shownText(th);
const cellText = (td: HTMLTableCellElement | undefined) => { const t = (td?.innerText ?? td?.textContent ?? "").replace(/\s+/g, " ").trim(); return t.length > 80 ? t.slice(0, 79) + "…" : t || "(empty)"; };

function read(key: string): Record<string, number> {
  try { return JSON.parse(localStorage.getItem(WIDTHS + key) ?? "{}"); } catch { return {}; }
}
function write(key: string, widths: Record<string, number>) {
  try { localStorage.setItem(WIDTHS + key, JSON.stringify(widths)); } catch { /* storage off: widths last until the page closes */ }
}

/** The data rows: those with a cell per column (day headings, empty and loading rows span the table and stay). */
const dataRows = (t: HTMLTableElement, cols: number) => [...(t.tBodies[0]?.rows ?? [])].filter((r) => r.cells.length === cols);

export function enableTableTools(root: Document | HTMLElement = document): () => void {
  const filters = new WeakMap<HTMLTableElement, Filters>();
  const ready = new WeakSet<HTMLTableElement>();
  let pop: HTMLElement | null = null;

  const apply = (t: HTMLTableElement) => {
    const f = filters.get(t);
    const head = t.tHead?.rows[0];
    if (!head) return;
    if (!f) { t.querySelectorAll<HTMLTableRowElement>("tbody tr[hidden]").forEach((r) => { r.hidden = false; }); head.querySelectorAll(".cc-col-filter.is-on").forEach((b) => b.classList.remove("is-on")); return; }
    for (const r of dataRows(t, head.cells.length)) {
      const hide = !!f && [...f.entries()].some(([i, allowed]) => !allowed.has(cellText(r.cells[i])));
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
    for (const [i, th] of [...head.cells].entries()) th.querySelector(".cc-col-filter")?.classList.toggle("is-on", !!f?.has(i));
    note(t);
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
    const clear = document.createElement("button");
    clear.type = "button"; clear.className = "cc-col-filtered-clear"; clear.textContent = "Clear column filters";
    clear.addEventListener("click", () => { filters.delete(t); apply(t); });
    line.append(clear);
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
  const openFilter = (t: HTMLTableElement, i: number, button: HTMLButtonElement) => {
    closePop();
    const head = t.tHead!.rows[0];
    const counts = new Map<string, number>();
    for (const r of dataRows(t, head.cells.length)) { const v = cellText(r.cells[i]); counts.set(v, (counts.get(v) ?? 0) + 1); }
    const values = [...counts.keys()].sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).slice(0, MAX_VALUES);
    const f = filters.get(t) ?? new Map<number, Set<string>>();
    const allowed = new Set(f.get(i) ?? values);
    const label = labelOf(head.cells[i]);

    pop = document.createElement("div");
    pop.className = "cc-col-pop";
    pop.setAttribute("role", "dialog");
    pop.setAttribute("aria-label", "Filter " + label);
    const search = document.createElement("input");
    search.type = "search"; search.className = "ui-input"; search.placeholder = "Find a value"; search.setAttribute("aria-label", "Find a value of " + label);
    const all = document.createElement("label");
    all.className = "cc-col-pop-all";
    const allBox = document.createElement("input");
    allBox.type = "checkbox";
    all.append(allBox, " Select all");
    const list = document.createElement("div");
    list.className = "cc-col-pop-list";
    list.setAttribute("role", "group");
    list.setAttribute("aria-label", "Values of " + label);
    const boxes = values.map((v) => {
      const row = document.createElement("label");
      const box = document.createElement("input");
      box.type = "checkbox"; box.checked = allowed.has(v); box.value = v;
      const n = document.createElement("span");
      n.className = "cc-col-pop-n"; n.textContent = String(counts.get(v));
      row.append(box, " " + v, n);
      list.append(row);
      return { v, box, row };
    });
    const sync = () => {
      const on = boxes.filter((b) => b.box.checked).map((b) => b.v);
      allBox.checked = on.length === values.length; allBox.indeterminate = on.length > 0 && on.length < values.length;
      if (on.length === values.length) f.delete(i); else f.set(i, new Set(on));
      if (f.size) filters.set(t, f); else filters.delete(t);
      apply(t);
    };
    allBox.addEventListener("change", () => { for (const b of boxes) if (!b.row.hidden) b.box.checked = allBox.checked; sync(); });
    for (const b of boxes) b.box.addEventListener("change", sync);
    search.addEventListener("input", () => { const q = search.value.trim().toLowerCase(); for (const b of boxes) b.row.hidden = !!q && !b.v.toLowerCase().includes(q); });
    const foot = document.createElement("div");
    foot.className = "cc-col-pop-foot";
    const clear = document.createElement("button");
    clear.type = "button"; clear.className = "ui-btn ui-btn-plain"; clear.textContent = "Clear filter";
    clear.addEventListener("click", () => { for (const b of boxes) b.box.checked = true; sync(); closePop(); button.focus(); });
    const done = document.createElement("button");
    done.type = "button"; done.className = "ui-btn ui-btn-primary"; done.textContent = "Done";
    done.addEventListener("click", () => { closePop(); button.focus(); });
    foot.append(clear, done);
    pop.append(search, all, list, foot);
    pop.addEventListener("keydown", (e) => { if (e.key === "Escape") { e.stopPropagation(); closePop(); button.focus(); } });
    document.body.append(pop);
    const r = button.getBoundingClientRect();
    pop.style.top = Math.min(r.bottom + 4, window.innerHeight - 340) + "px";
    pop.style.left = Math.max(8, Math.min(r.left - 8, window.innerWidth - 268)) + "px";
    sync();
    search.focus();
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
          const b = document.createElement("button");
          b.type = "button"; b.className = "cc-col-filter"; b.setAttribute("aria-label", "Filter " + label); b.title = "Filter " + label;
          b.innerHTML = '<svg aria-hidden="true" width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M3 4h18l-7 9v6l-4 2v-8z"/></svg>';
          b.addEventListener("click", (e) => { e.stopPropagation(); if (pop?.getAttribute("aria-label") === "Filter " + label) closePop(); else openFilter(t, i, b); });
          th.append(b);
        }
        const grip = document.createElement("span");
        grip.className = "cc-col-grip"; grip.setAttribute("aria-hidden", "true"); grip.title = "Drag to widen or narrow; double-click to fit";
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
