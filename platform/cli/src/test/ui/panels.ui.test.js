// Checks the command panel of every action that declares a layout (x-ooa-panel), one action at a time, in jsdom
// against a running Studio: shown fields in layout order, the rest under More options, tick boxes that reveal and
// clear their fields, and required markers. PANEL_FILTER limits the run to ids that start with it (e.g. "ooa.excel/").
// Run as studio.ui.test.js: STUDIO_DIR, OOA_STUDIO_URL (default http://localhost:8710).
const { JSDOM } = require("jsdom");
const fs = require("fs");
const S = process.env.STUDIO_DIR, BASE = process.env.OOA_STUDIO_URL || "http://localhost:8710", FILTER = process.env.PANEL_FILTER || "";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0, checked = 0;
const fail = (id, what) => { failures++; console.log("  FAIL " + id + ": " + what); };

(async () => {
  const dom = new JSDOM(fs.readFileSync(S + "/index.html", "utf8").replace('<script src="studio.js"></script>', ""), { url: BASE + "/", runScripts: "dangerously", pretendToBeVisual: true });
  const w = dom.window, d = w.document;
  w.fetch = (u, o) => fetch(new URL(u, BASE), o);
  w.CSS = w.CSS || {}; w.CSS.escape = w.CSS.escape || ((s) => s);
  w.HTMLElement.prototype.scrollIntoView = () => {};
  w.prompt = () => null; w.confirm = () => true;
  w.__OOA_NO_AUTOSTART__ = true;
  const sc = d.createElement("script"); sc.textContent = fs.readFileSync(S + "/studio.js", "utf8"); d.body.appendChild(sc);
  const until = async (fn, ms = 6000) => { const t = Date.now(); while (Date.now() - t < ms) { try { if (fn()) return true; } catch (_) {} await sleep(40); } return false; };
  const click = (el) => el.dispatchEvent(new w.MouseEvent("click", { bubbles: true }));
  w.init();
  await until(() => w.eval("state.specs.size") > 100, 20000);
  const ids = w.eval("[...state.specs.keys()]").filter((id) => id.startsWith(FILTER) && w.eval("state.specs").get(id).action["x-ooa-panel"]);
  console.log("Panels to check: " + ids.length);
  for (const id of ids) {
    checked++;
    const spec = w.eval("state.specs").get(id);
    const layout = spec.action["x-ooa-panel"], props = spec.action.inputs.properties, req = spec.action.inputs.required || [];
    w.eval("state.flow = { kind: 'TaskBot', id: 'panel_check', name: 'Panel check', variables: [], steps: [] }; state.file = null; state.selected = null; state.target = null; state.multi = new Set(); state.ticks = new Set(); state.choices = new Map();");
    w.addStep(id);
    w.renderProps();
    const step = () => w.eval("state.flow.steps[0]");
    const applies = (n) => w.fieldApplies(props[n], step().args || {}, props);
    const domFields = (inMore) => [...d.querySelectorAll("#props [data-field]")].map((el) => [el.dataset.field, !!el.closest(".more-opts")])
      .filter(([n, m]) => n in props && m === inMore).map(([n]) => n);
    // what the layout says should be visible now: plain fields that apply, and grouped fields only when their tick is on
    const expect = (part) => {
      const out = [];
      for (const it of layout[part] || []) {
        if (typeof it === "string") { if (applies(it)) out.push(it); continue; }
        if (it.choice) {
          let k = it.options.findIndex((o) => o.fields.some((f) => (step().args || {})[f] !== undefined));
          if (k < 0) k = w.eval("state.choices").get(step().id + "|" + it.choice) || 0;
          for (const f of it.options[k].fields) if (applies(f)) out.push(f);
          continue;
        }
        const on = it.fields.some((f) => (step().args || {})[f] !== undefined);
        if (on) for (const f of it.fields) if (applies(f)) out.push(f);
      }
      return out;
    };
    const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);
    // in order and nothing extra: Studio may leave fields out (see below), but never adds or reorders them
    const inOrder = (got, want) => { let k = 0; for (const x of got) { k = want.indexOf(x, k); if (k < 0) return false; k++; } return true; };
    const mainNow = domFields(false), moreNow = domFields(true);
    // Studio's own drawing: a web step shows one element field (the captured object, with a switch to a selector), and
    // element and desktop-object actions show only the fields of the chosen action, with waits in their own box
    const perAction = !!w.elementActionFields(step());
    const webElement = props.object && props.selector && id.startsWith("ooa.browser/") && (step().args || {}).selector === undefined;
    const want = (part) => expect(part).filter((n) => !(webElement && n === "selector"));
    if (perAction) {
      const all = [...want("main"), ...want("more")];
      if (!inOrder(mainNow, all) || !inOrder(moreNow, all)) fail(id, "fields out of layout order: " + JSON.stringify(mainNow) + " / " + JSON.stringify(moreNow));
      if (moreNow.some((n) => want("main").includes(n))) fail(id, "a main field is under More options: " + JSON.stringify(moreNow));
    } else {
      if (!same(mainNow, want("main"))) fail(id, "shown fields " + JSON.stringify(mainNow) + " but layout says " + JSON.stringify(want("main")));
      if (!same(moreNow, want("more"))) fail(id, "More options " + JSON.stringify(moreNow) + " but layout says " + JSON.stringify(want("more")));
    }
    if (webElement && !d.querySelector("#props .element-switch")) fail(id, "no switch between the captured object and a selector");
    for (const n of mainNow) if (req.includes(n) && !d.querySelector('#props [data-field="' + n + '"] .req')) fail(id, "required field " + n + " has no marker");
    // every tick box: ticking shows its fields, unticking hides them and clears their values
    // every choice: picking an option shows its fields and clears the other options' values
    for (const it of [...(layout.main || []), ...(layout.more || [])].filter((x) => x && x.choice)) {
      it.options.forEach((o, k) => {
        const more = d.querySelector("#props details.more-opts"); if (more) more.open = true;
        const group = [...d.querySelectorAll('#props [role="radiogroup"]')].find((g) => g.getAttribute("aria-label") === it.choice);
        if (!group) { fail(id, "choice '" + it.choice + "' not drawn"); return; }
        const others = it.options.filter((_, j) => j !== k).flatMap((x) => x.fields);
        w.eval("state.flow.steps[0]").args = Object.assign({}, step().args, Object.fromEntries(others.slice(0, 1).map((f) => [f, "x"])));
        w.renderProps();
        const g2 = [...d.querySelectorAll('#props [role="radiogroup"]')].find((g) => g.getAttribute("aria-label") === it.choice);
        click(g2.querySelectorAll('[role="radio"]')[k]);
        if (others.some((f) => (step().args || {})[f] !== undefined)) fail(id, "picking option " + (k + 1) + " of '" + it.choice + "' keeps other options' values");
        const vis = o.fields.filter(applies);
        const shownNow = vis.filter((f) => d.querySelector('#props .choice-fields [data-field="' + f + '"]'));
        if (shownNow.length !== vis.length) fail(id, "option " + (k + 1) + " of '" + it.choice + "' shows " + JSON.stringify(shownNow) + " of " + JSON.stringify(vis));
        if (others.some((f) => !o.fields.includes(f) && d.querySelector('#props [data-field="' + f + '"]'))) fail(id, "option " + (k + 1) + " of '" + it.choice + "' also shows other options' fields");
      });
    }
    for (const it of [...(layout.main || []), ...(layout.more || [])].filter((x) => x && x.tick)) {
      const more = d.querySelector("#props details.more-opts"); if (more) more.open = true;
      const box = [...d.querySelectorAll("#props .tick-group input")].find((b) => b.getAttribute("aria-label") === it.tick);
      if (!box) { fail(id, "tick box '" + it.tick + "' not drawn"); continue; }
      if (!box.checked) { box.checked = true; box.dispatchEvent(new w.Event("change", { bubbles: true })); }
      const shownAfter = it.fields.filter(applies).filter((f) => d.querySelector('#props .tick-fields [data-field="' + f + '"]'));
      if (!same(shownAfter, it.fields.filter(applies))) fail(id, "ticking '" + it.tick + "' shows " + JSON.stringify(shownAfter));
      const box2 = [...d.querySelectorAll("#props .tick-group input")].find((b) => b.getAttribute("aria-label") === it.tick);
      w.eval("state.flow.steps[0]").args = Object.assign({}, step().args, { [it.fields[0]]: "x" });
      box2.checked = false; box2.dispatchEvent(new w.Event("change", { bubbles: true }));
      if (it.fields.some((f) => (step().args || {})[f] !== undefined)) fail(id, "unticking '" + it.tick + "' keeps values");
      if (it.fields.some((f) => d.querySelector('#props [data-field="' + f + '"]'))) fail(id, "unticking '" + it.tick + "' leaves fields shown");
    }
  }
  console.log((failures ? failures + " failures" : "All passed") + " in " + checked + " panels");
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
