// ONEOFFICEAUTOMATION Browser Connector: content script (every page and frame).
// Finds captured objects with the shared page script (ooa-page.js, loaded first), acts on them, and runs the
// picker (Capture) and the recorder. Answers only requests meant for its own frame.
(() => {
  if (window.__ooaContent) return;
  window.__ooaContent = true;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const frameSel = (fe) => {
    if (fe.id && !__generated(fe.id)) return fe.tagName.toLowerCase() + "#" + CSS.escape(fe.id);
    const nm = fe.getAttribute("name"); if (nm) return fe.tagName.toLowerCase() + '[name="' + __cssq(nm) + '"]';
    const src = fe.getAttribute("src"); if (src && src.length < 200) return fe.tagName.toLowerCase() + '[src="' + __cssq(src) + '"]';
    return fe.tagName.toLowerCase() + ":nth-of-type(" + (Array.from(fe.parentElement.children).filter((c) => c.tagName === fe.tagName).indexOf(fe) + 1) + ")";
  };
  /** The chain of iframe selectors from the top page down to this frame (by address when a parent is on another site). */
  const framePath = () => {
    const path = [];
    let w = window;
    try { while (w !== w.top) { const fe = w.frameElement; if (!fe) throw new Error("cross-site"); path.unshift(frameSel(fe)); w = w.parent; } }
    catch (e) { return ["iframe[src=\"" + __cssq(location.href) + "\"]"]; }
    return path;
  };
  const isMine = (frames) => JSON.stringify(frames || []) === JSON.stringify(framePath());

  // ---------- finding ----------
  const CSS_ATTRS = ["id", "name", "type", "aria-label", "placeholder", "title", "role", "alt", "for"];
  const prop = (o, n) => { const p = (o.properties || []).find((x) => x.name === n && x.value != null); return p ? String(p.value) : null; };
  const rulesOf = (o) => (o.properties || []).filter((p) => p.use && p.name).map((p) => ({ name: p.name, value: p.value == null ? "" : String(p.value), match: p.match || "equals" }));
  const baseCss = (rules) => {
    let tag = "*", attrs = "";
    for (const r of rules) {
      if (r.match !== "equals") continue;
      if (r.name === "tag" && /^[a-zA-Z][a-zA-Z0-9-]*$/.test(r.value)) tag = r.value.toLowerCase();
      else if (CSS_ATTRS.includes(r.name) || r.name.startsWith("data-")) attrs += "[" + r.name + '="' + __cssq(r.value) + '"]';
    }
    return tag + attrs;
  };
  const byXpath = (x) => { const out = []; try { const r = document.evaluate(x, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null); for (let i = 0; i < r.snapshotLength; i++) out.push(r.snapshotItem(i)); } catch (e) { } return out; };
  const findAll = (o, by) => {
    by = by || o.search_by || "properties";
    if (by === "css") { const q = prop(o, "css"); return q ? Array.from(document.querySelectorAll(q)) : []; }
    if (by === "xpath") {
      for (const q of [prop(o, "dom_xpath"), o.prefer_relative_xpath ? prop(o, "relative_xpath") : null, prop(o, "xpath"), prop(o, "relative_xpath")]) {
        if (!q) continue; const f = byXpath(q); if (f.length) return f;
      }
      return [];
    }
    const rules = rulesOf(o);
    if (!rules.length) throw Object.assign(new Error("No property is ticked for the search"), { code: "BROWSER.BAD_OBJECT" });
    const cands = Array.from(document.querySelectorAll(baseCss(rules)));
    return __matches(cands, rules, !!o.ignore_case).map((i) => cands[i]);
  };
  const bySelector = (sel) => {
    if (sel.startsWith("xpath=")) return byXpath(sel.slice(6));
    if (sel.startsWith("//") || sel.startsWith("(//")) return byXpath(sel);
    const t = sel.match(/^text=(["']?)(.*)\1$/);
    if (t) return Array.from(document.querySelectorAll("a,button,input[type=button],input[type=submit],label,span,div,li,td,th,h1,h2,h3,h4,p"))
      .filter((e) => (e.innerText || e.value || "").trim() === t[2]);
    return Array.from(document.querySelectorAll(sel));
  };
  const pageXY = (el) => { const r = el.getBoundingClientRect(); return { x: r.left + scrollX, y: r.top + scrollY, w: r.width }; };
  const nearest = (cands, x, y, max) => {
    let best = null, bd = Infinity;
    for (const c of cands) { const p = pageXY(c); if (!p.w) continue; const d = Math.hypot(p.x - x, p.y - y); if (d < bd) { bd = d; best = c; } }
    return bd <= max ? best : null;
  };
  /** The object's element right now, or null: index, self-healing (CSS, XPath) and the anchor, as at run time with Playwright. */
  const locateNow = (o, healed) => {
    const index = Number(o.index || 0), primary = o.search_by || "properties";
    const order = [primary];
    if (o.self_heal !== false) { if (prop(o, "css") && !order.includes("css")) order.push("css"); if ((prop(o, "dom_xpath") || prop(o, "xpath")) && !order.includes("xpath")) order.push("xpath"); }
    for (const by of order) {
      let found = [];
      try { found = findAll(o, by); } catch (e) { if (by === primary) throw e; }
      if (found.length > 1 && o.anchor && !index) {
        const a = locateNow(o.anchor, null);
        if (a) { const p = pageXY(a), off = o.anchor.offset || {}; const n = nearest(found, p.x + (off.dx || 0), p.y + (off.dy || 0), Infinity); if (n) return n; }
      }
      if (found.length > index) { if (by !== primary && healed) healed.by = by; return found[index]; }
    }
    if (o.anchor) {
      const a = locateNow(o.anchor, null), tag = prop(o, "tag");
      if (a && tag) { const p = pageXY(a), off = o.anchor.offset || {}; const n = nearest(Array.from(document.getElementsByTagName(tag)), p.x + (off.dx || 0), p.y + (off.dy || 0), 250); if (n) { if (healed) healed.by = "anchor"; return n; } }
    }
    return null;
  };
  const describe = (o) => rulesOf(o).map((r) => r.name + " " + r.match + " '" + r.value + "'").join(", ") || o.search_by || "";

  // ---------- acting ----------
  const fire = (el, type, init) => el.dispatchEvent(type.startsWith("key") ? new KeyboardEvent(type, Object.assign({ bubbles: true, cancelable: true }, init))
    : type.startsWith("pointer") ? new PointerEvent(type, Object.assign({ bubbles: true, cancelable: true, composed: true }, init))
    : new MouseEvent(type, Object.assign({ bubbles: true, cancelable: true, composed: true, view: window }, init)));
  const mouse = (el, button, count, mods, at) => {
    el.scrollIntoView({ block: "center", inline: "center" });
    const r = el.getBoundingClientRect();
    const x = r.left + (at && at.x != null ? at.x : r.width / 2), y = r.top + (at && at.y != null ? at.y : r.height / 2);
    const init = { clientX: x, clientY: y, button, buttons: button === 2 ? 2 : 1, ctrlKey: !!mods.ctrl, shiftKey: !!mods.shift, altKey: !!mods.alt, metaKey: !!mods.meta };
    for (let i = 0; i < count; i++) {
      fire(el, "pointerover", init); fire(el, "mouseover", init); fire(el, "pointerdown", init); fire(el, "mousedown", init);
      if (el.focus) el.focus();
      fire(el, "pointerup", init); fire(el, "mouseup", init);
      if (button === 2) fire(el, "contextmenu", init); else if (button === 1) fire(el, "auxclick", init); else fire(el, "click", Object.assign({ detail: i + 1 }, init));
    }
    if (count === 2 && button === 0) fire(el, "dblclick", init);
  };
  const nativeValue = (el, v) => {
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    const set = Object.getOwnPropertyDescriptor(proto, "value");
    if (set && set.set) set.set.call(el, v); else el.value = v;
  };
  const setText = async (el, text, opts) => {
    el.scrollIntoView({ block: "center" }); if (el.focus) el.focus();
    if (el.isContentEditable) { if (opts.clear) el.textContent = ""; el.textContent += text; fire(el, "input", {}); return; }
    const base = opts.clear ? "" : String(el.value || "");
    if (opts.delay > 0 || opts.keystrokes) {
      nativeValue(el, base); el.dispatchEvent(new Event("input", { bubbles: true }));
      let v = base;
      for (const ch of text) {
        fire(el, "keydown", { key: ch }); fire(el, "keypress", { key: ch });
        v += ch; nativeValue(el, v); el.dispatchEvent(new InputEvent("input", { bubbles: true, data: ch, inputType: "insertText" }));
        fire(el, "keyup", { key: ch });
        if (opts.delay > 0) await sleep(opts.delay);
      }
    } else { nativeValue(el, base + text); el.dispatchEvent(new Event("input", { bubbles: true })); }
    el.dispatchEvent(new Event("change", { bubbles: true }));
  };
  const KEYS = { enter: "Enter", tab: "Tab", escape: "Escape", esc: "Escape", backspace: "Backspace", delete: "Delete", space: " ", up: "ArrowUp", down: "ArrowDown", left: "ArrowLeft", right: "ArrowRight", home: "Home", end: "End", pageup: "PageUp", pagedown: "PageDown" };
  const pressKey = (el, spec) => {
    const parts = String(spec).split("+").map((s) => s.trim());
    const key = KEYS[parts[parts.length - 1].toLowerCase()] || parts[parts.length - 1];
    const mods = { ctrlKey: parts.some((p) => /^ctrl|control$/i.test(p)), shiftKey: parts.some((p) => /^shift$/i.test(p)), altKey: parts.some((p) => /^alt$/i.test(p)) };
    if (el.focus) el.focus();
    fire(el, "keydown", Object.assign({ key }, mods)); fire(el, "keypress", Object.assign({ key }, mods)); fire(el, "keyup", Object.assign({ key }, mods));
    if (key === "Enter" && el.form && el.tagName === "INPUT") { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }
  };
  const optionsOf = (el) => (el.tagName === "SELECT" ? Array.from(el.options) : []);
  const select = (el, how, v) => {
    const opts = optionsOf(el);
    if (!opts.length) throw Object.assign(new Error("The element is not a drop-down list"), { code: "BROWSER.NOT_A_LIST" });
    const i = how === "index" ? Number(v) : opts.findIndex((o) => (how === "text" ? o.text.trim() === String(v).trim() : o.value === String(v)));
    if (!(i >= 0 && i < opts.length)) throw Object.assign(new Error("The list has no item " + v), { code: "BROWSER.NO_ITEM" });
    nativeValue(el, opts[i].value); el.selectedIndex = i;
    el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true }));
  };
  const table = (el) => {
    const t = el.closest("table") || el; const rows = Array.from(t.querySelectorAll("tr"));
    if (!rows.length) throw Object.assign(new Error("The element is not a table"), { code: "BROWSER.NOT_A_TABLE" });
    const cells = (r) => Array.from(r.querySelectorAll("th,td")).map((c) => (c.innerText || "").trim());
    const head = rows[0].querySelector("th") ? cells(rows[0]) : cells(rows[0]).map((_, i) => "Column" + (i + 1));
    return { columns: head, rows: rows.slice(rows[0].querySelector("th") ? 1 : 0).map(cells) };
  };
  const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== "hidden"; };
  const setChecked = (el, want) => { if (!!el.checked !== want) mouse(el, 0, 1, {}, null); if (!!el.checked !== want) { el.checked = want; el.dispatchEvent(new Event("change", { bubbles: true })); } };

  async function act(req) {
    const o = req.object, act = req.action || "click", v = req.value;
    const deadline = Date.now() + Math.max(0, Number(req.timeout_ms == null ? 30000 : req.timeout_ms));
    const count = () => (o ? findAll(o) : bySelector(req.selector)).length;
    if (act === "count") return { result: count() };
    if (act === "is_present") return { result: o ? !!locateNow(o, null) : count() > 0 };
    if (act === "is_visible") { const el = o ? locateNow(o, null) : bySelector(req.selector)[0]; return { result: !!el && visible(el) }; }
    if (act === "wait_to_disappear" || act === "wait_detached") {
      while (true) {
        const el = o ? locateNow(o, null) : bySelector(req.selector)[0];
        if (!el || (act === "wait_to_disappear" && !visible(el))) return { result: true };
        if (Date.now() > deadline) throw Object.assign(new Error("Still on the page after " + (req.timeout_ms || 30000) + " ms"), { code: "BROWSER.STILL_VISIBLE" });
        await sleep(250);
      }
    }
    let el = null; const healed = {};
    while (true) {
      el = o ? locateNow(o, healed) : bySelector(req.selector)[Number(req.index || 0)];
      if (el && (act !== "wait_to_appear" || visible(el))) break;
      if (Date.now() > deadline) throw Object.assign(new Error("No element matches " + (o ? "the captured object (" + describe(o) + ")" : req.selector) + " within " + (req.timeout_ms || 30000) + " ms"), { code: "BROWSER.OBJECT_NOT_FOUND" });
      await sleep(250);
    }
    const mods = { ctrl: /ctrl|control/i.test(req.modifiers || ""), shift: /shift/i.test(req.modifiers || ""), alt: /alt/i.test(req.modifiers || ""), meta: /win|meta|cmd/i.test(req.modifiers || "") };
    const at = req.offset_x != null || req.offset_y != null ? { x: req.offset_x, y: req.offset_y } : null;
    const out = (r) => ({ result: r, healed_by: healed.by || "" });
    switch (act) {
      case "click": if (req.click_method === "javascript") el.click(); else mouse(el, 0, 1, mods, at); return out(true);
      case "double_click": mouse(el, 0, 2, mods, at); return out(true);
      case "right_click": mouse(el, 2, 1, mods, at); return out(true);
      case "middle_click": mouse(el, 1, 1, mods, at); return out(true);
      case "hover": { const r = el.getBoundingClientRect(); const i = { clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }; fire(el, "pointerover", i); fire(el, "mouseover", i); fire(el, "mouseenter", i); fire(el, "mousemove", i); return out(true); }
      case "focus": el.focus(); return out(true);
      case "set_text": await setText(el, String(v == null ? "" : v), { clear: req.clear_first !== false, delay: Number(req.key_delay_ms || 0), keystrokes: req.typing === "keystrokes" }); return out(true);
      case "append_text": await setText(el, String(v == null ? "" : v), { clear: false, delay: Number(req.key_delay_ms || 0), keystrokes: req.typing === "keystrokes" }); return out(true);
      case "clear": nativeValue(el, ""); el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true })); return out(true);
      case "press_key": pressKey(el, v); return out(true);
      case "get_text": return out((el.innerText !== undefined ? el.innerText : el.textContent || "").trim());
      case "get_value": return out(el.value !== undefined ? String(el.value) : el.getAttribute("value"));
      case "get_attribute": return out(el.getAttribute(String(v)));
      case "get_property": { const p = el[String(v)]; return out(p == null || typeof p === "function" ? (__one(el, String(v)) ?? null) : typeof p === "object" ? JSON.parse(JSON.stringify(p)) : p); }
      case "get_all_properties": { const p = __props(el); const r = el.getBoundingClientRect(); Object.assign(p, { x: Math.round(r.left), y: Math.round(r.top), visible: visible(el) }); if (el.checked !== undefined) p.checked = el.checked; return out(p); }
      case "get_html": return out(el.outerHTML);
      case "get_inner_html": return out(el.innerHTML);
      case "get_coordinates": { const r = el.getBoundingClientRect(); return out({ x: Math.round(r.left), y: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height), center_x: Math.round(r.left + r.width / 2), center_y: Math.round(r.top + r.height / 2) }); }
      case "get_children_text": return out(Array.from(el.children).map((c) => (c.innerText || c.textContent || "").trim()));
      case "get_table": return out(table(el));
      case "select_by_text": select(el, "text", v); return out(true);
      case "select_by_value": select(el, "value", v); return out(true);
      case "select_by_index": select(el, "index", v); return out(true);
      case "get_selected": return out(el.selectedIndex >= 0 && optionsOf(el).length ? el.options[el.selectedIndex].text : null);
      case "get_options": return out(optionsOf(el).map((x) => x.text));
      case "check": setChecked(el, true); return out(true);
      case "uncheck": setChecked(el, false); return out(true);
      case "toggle": setChecked(el, !el.checked); return out(!!el.checked);
      case "is_checked": return out(!!el.checked);
      case "is_enabled": return out(!el.disabled);
      case "scroll_into_view": el.scrollIntoView({ block: "center" }); return out(true);
      case "highlight": { const old = el.style.outline; el.style.outline = "3px solid #a371ed"; setTimeout(() => { el.style.outline = old; }, 2000); return out(true); }
      case "wait_to_appear": return out(true);
      case "screenshot": { el.scrollIntoView({ block: "center" }); await sleep(150); const r = el.getBoundingClientRect(); return { shot_rect: { x: r.left, y: r.top, width: r.width, height: r.height }, ratio: devicePixelRatio, healed_by: healed.by || "" }; }
      default: throw Object.assign(new Error("The action '" + act + "' is not available through the browser connector"), { code: "BROWSER.NOT_SUPPORTED" });
    }
  }

  // ---------- picker (Capture) ----------
  let pickState = null;
  const overlay = () => {
    const box = document.createElement("div");
    box.style.cssText = "position:fixed;pointer-events:none;z-index:2147483646;border:2px solid #a371ed;background:rgba(163,113,237,.12);border-radius:3px;display:none";
    const tip = document.createElement("div");
    tip.style.cssText = "position:fixed;z-index:2147483647;top:10px;right:10px;background:#2f3336;color:#fff;font:13px Segoe UI,sans-serif;padding:8px 12px;border-radius:10px;pointer-events:none";
    tip.textContent = "ONEOFFICEAUTOMATION Capture: click the element you want (Esc cancels)";
    return { box, tip };
  };
  const pickData = (t) => {
    const r = t.getBoundingClientRect();
    return { selector: __cssPath(t), tag: t.tagName.toLowerCase(), text: (t.innerText || t.value || "").trim().slice(0, 80), type: t.getAttribute("type") || "",
      name: t.getAttribute("name") || "", id: t.id || "", props: __props(t), css: __cssPath(t), xpath: __absXpath(t), dom_xpath: __domXpath(t),
      rect: { x: r.left, y: r.top, width: r.width, height: r.height }, page_rect: __rect(t), ratio: devicePixelRatio,
      page: { url: (window.top === window ? location.href : (document.referrer || location.href)), title: window.top === window ? document.title : "" },
      frames: window.top === window ? [] : framePath(), frame_url: location.href };
  };
  function pickOn(token) {
    pickOff();
    const { box, tip } = overlay();
    const move = (e) => { const t = e.target; if (!t || t === box || t === tip) return; const r = t.getBoundingClientRect(); Object.assign(box.style, { display: "block", left: r.left + "px", top: r.top + "px", width: r.width + "px", height: r.height + "px" }); };
    const click = (e) => {
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      const d = pickData(e.target);
      pickOff();
      chrome.runtime.sendMessage(Object.assign({ event: "picked", token }, d));
    };
    const block = (e) => { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); };
    const key = (e) => { if (e.key === "Escape") { pickOff(); chrome.runtime.sendMessage({ event: "pick_cancelled", token }); } };
    document.documentElement.append(box); if (window.top === window) document.documentElement.append(tip);
    document.addEventListener("mousemove", move, true); document.addEventListener("click", click, true);
    for (const t of ["mousedown", "mouseup", "pointerdown", "pointerup"]) document.addEventListener(t, block, true);
    document.addEventListener("keydown", key, true);
    pickState = { box, tip, move, click, block, key };
  }
  function pickOff() {
    if (!pickState) return;
    const s = pickState; pickState = null;
    s.box.remove(); s.tip.remove();
    document.removeEventListener("mousemove", s.move, true); document.removeEventListener("click", s.click, true);
    for (const t of ["mousedown", "mouseup", "pointerdown", "pointerup"]) document.removeEventListener(t, s.block, true);
    document.removeEventListener("keydown", s.key, true);
  }

  // ---------- recorder ----------
  let rec = null;
  const textInput = (t) => t.tagName === "TEXTAREA" || (t.tagName === "INPUT" && !/^(checkbox|radio|submit|button|file|image|reset)$/.test(t.type)) || t.isContentEditable;
  function recordOn(token) {
    if (rec) return;
    const send = (kind, t, extra) => chrome.runtime.sendMessage(Object.assign({ event: "recorded", token, kind }, pickData(t), extra || {}));
    const click = (e) => {
      if (e.target.closest && e.target.closest("#__ooa_rec_bar")) return;
      const t = (e.target.closest && e.target.closest("button,a,input,select,textarea,[role=button],[onclick],label")) || e.target;
      if (textInput(t) || t.tagName === "SELECT") return;
      send("click", t, { label: (t.innerText || t.value || "").trim().slice(0, 40), checked: t.checked });
    };
    const change = (e) => {
      const t = e.target;
      if (t.tagName === "SELECT") send("select", t, { value: t.value, option: t.selectedIndex >= 0 ? (t.options[t.selectedIndex].text || "").trim() : null });
      else if (textInput(t)) send("type", t, { value: t.type === "password" ? null : (t.isContentEditable ? t.innerText : t.value), password: t.type === "password" });
    };
    document.addEventListener("click", click, true); document.addEventListener("change", change, true);
    let bar = null;
    if (window.top === window) {
      bar = document.createElement("div"); bar.id = "__ooa_rec_bar";
      bar.style.cssText = "position:fixed;z-index:2147483647;top:10px;right:10px;background:#2f3336;color:#fff;font:13px Segoe UI,sans-serif;padding:8px 12px;border-radius:10px;box-shadow:0 6px 18px rgba(0,0,0,.3);display:flex;gap:10px;align-items:center";
      const dot = document.createElement("span"); dot.style.cssText = "width:10px;height:10px;border-radius:50%;background:#e5484d";
      const label = document.createElement("span"); label.textContent = "ONEOFFICEAUTOMATION is recording";
      const stop = document.createElement("button"); stop.type = "button"; stop.textContent = "Stop recording";
      stop.style.cssText = "background:#0a7bbb;color:#fff;border:0;border-radius:6px;padding:5px 10px;cursor:pointer;font:inherit";
      stop.onclick = () => chrome.runtime.sendMessage({ event: "record_stopped", token });
      bar.append(dot, label, stop); document.documentElement.append(bar);
    }
    rec = { click, change, bar };
  }
  function recordOff() {
    if (!rec) return;
    document.removeEventListener("click", rec.click, true); document.removeEventListener("change", rec.change, true);
    if (rec.bar) rec.bar.remove();
    rec = null;
  }

  // ---------- requests from the background worker ----------
  chrome.runtime.onMessage.addListener((msg, sender, reply) => {
    if (!msg || !msg.ooa) return;
    if (msg.ooa === "ping") { reply({ ok: true, frames: framePath() }); return; }
    if (msg.ooa === "pick") { if (msg.on) pickOn(msg.token); else pickOff(); if (window.top === window) reply(true); return; }
    if (msg.ooa === "record") { if (msg.on) recordOn(msg.token); else recordOff(); if (window.top === window) reply(true); return; }
    if (msg.ooa === "element") {
      const req = msg.req || {};
      if (!isMine(req.object ? req.object.frames : req.frames)) return;   // another frame answers
      act(req).then((r) => reply(r), (e) => reply({ error: String(e && e.message || e), code: e && e.code || "BROWSER.ERROR" }));
      return true;
    }
  });
})();
