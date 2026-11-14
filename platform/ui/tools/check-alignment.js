/*
 * Alignment check for tool bar rows (14 Nov 2026, item A).
 *
 * Run it in the browser on any page of One CommandCenter, OneAgentStudio or Document Automation (paste it into the
 * console, or inject it), then call ooaCheckAlignment(). It returns one entry per row of controls that breaks the rule
 * every tool bar follows: the controls of a row sit on one centre line (within 1.5 px), and the controls 20 px or
 * higher in it all have one height (within 1 px; underline tabs are compared only with tabs). Small markers such as
 * the round help "i" only need to be centred.
 *
 * A row is a flex row with at least two controls as direct children (buttons, fields, tabs, switches and groups of
 * them). Rows that wrap are checked line by line. Hidden elements are skipped.
 */
(function () {
  const CONTROL = "button, select, input:not([type=checkbox]):not([type=radio]), textarea, a.ui-btn, [role=tab], [role=radiogroup], [role=group], [role=combobox], .ui-btn, .ui-icon-btn, .ui-input, .st-seg, .st-tool-group, .cc-chip";

  function visible(el) {
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== "hidden" && cs.display !== "none" && Number(cs.opacity) > 0;
  }

  /** The control a direct child stands for: itself, or the only control inside a thin wrapper (a span around a select). */
  function controlOf(child) {
    // a group holding several controls (a filter bar) is a row of its own, checked on its own, not one control
    if (child.matches("[role=group]:not(.st-tool-group):not(.ov-seg)") && child.querySelectorAll(CONTROL).length > 1 && getComputedStyle(child).display.includes("flex")) return null;
    if (child.matches(CONTROL)) return child;
    const inner = child.querySelectorAll(CONTROL);
    if (inner.length === 1 && child.children.length <= 2) return inner[0];
    return null;
  }

  function label(el) {
    const t = (el.getAttribute("aria-label") || el.innerText || el.getAttribute("title") || el.tagName).trim().split("\n")[0];
    return t.slice(0, 24);
  }

  function rowName(row) {
    const cls = typeof row.className === "string" ? row.className.trim().split(/\s+/).slice(0, 2).join(".") : "";
    return row.tagName.toLowerCase() + (cls ? "." + cls : "") + (row.getAttribute("aria-label") ? ` [${row.getAttribute("aria-label")}]` : "");
  }

  function ooaCheckAlignment(root) {
    const problems = [];
    for (const row of (root || document).querySelectorAll("*")) {
      if (!visible(row)) continue;
      const cs = getComputedStyle(row);
      if (!cs.display.includes("flex") || !cs.flexDirection.startsWith("row")) continue;
      const controls = [...row.children].filter(visible).map(controlOf).filter(Boolean).filter(visible);
      if (controls.length < 2) continue;
      // group by line: a control starts a new line when it begins below the bottom of the line so far
      const lines = [];
      for (const c of controls.map((el) => ({ el, r: el.getBoundingClientRect() })).sort((a, b) => a.r.top - b.r.top)) {
        const line = lines.find((l) => c.r.top < l.bottom - 2);
        if (line) { line.items.push(c); line.bottom = Math.max(line.bottom, c.r.bottom); } else lines.push({ items: [c], bottom: c.r.bottom });
      }
      for (const line of lines) {
        if (line.items.length < 2) continue;
        const mids = line.items.map((c) => c.r.top + c.r.height / 2);
        const spread = Math.max(...mids) - Math.min(...mids);
        // underline tabs are a strip of their own: they are compared with tabs, other controls with other controls
        const spreadOf = (hs) => hs.length ? Math.max(...hs) - Math.min(...hs) : 0;
        const tall = line.items.filter((c) => c.r.height >= 20);
        const isTab = (c) => c.el.getAttribute("role") === "tab";
        const hSpread = Math.max(spreadOf(tall.filter(isTab).map((c) => c.r.height)), spreadOf(tall.filter((c) => !isTab(c)).map((c) => c.r.height)));
        if (spread > 1.5 || hSpread > 1) {
          problems.push({
            row: rowName(row),
            offCentre: Math.round(spread * 10) / 10,
            heights: [...new Set(line.items.map((c) => Math.round(c.r.height)))].join("/"),
            controls: line.items.map((c) => `${label(c.el)} (${Math.round(c.r.height)})`).join(" | "),
          });
        }
      }
    }
    return problems;
  }

  window.ooaCheckAlignment = ooaCheckAlignment;
})();
