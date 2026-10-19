/* ONEOFFICEAUTOMATION Control Room console. All data is rendered with textContent (never innerHTML). */
"use strict";
const $ = (id) => document.getElementById(id);
// Optional parts are written as `cond ? node : null`; skip null/false (and flatten arrays) instead of printing "null".
for (const m of ["append", "replaceChildren"]) {
  const orig = Element.prototype[m];
  Element.prototype[m] = function (...nodes) { return orig.apply(this, nodes.flat(Infinity).filter((n) => n !== null && n !== undefined && n !== false)); };
}

const S = { user: null, page: "home", timer: null, drawerTimer: null, devices: [], bots: [], users: [], names: {} };

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "text") el.textContent = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "value") el.value = v;
    else if (k === "checked") el.checked = !!v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of kids.flat()) if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}

// ---------- icons ----------
const ICONS = {
  home: "M3 11l9-8 9 8M5 10v10h14V10", bot: "M5 9h14v10H5zM12 5v4M9 13v1M15 13v1M9 16.5h6M12 3v1", pulse: "M3 12h4l2-6 4 12 2-6h6",
  desktop: "M3 4h18v12H3zM8 20h8M12 16v4", clock: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 3", bolt: "M13 2L4 14h7l-1 8 9-12h-7z",
  lock: "M6 11h12v10H6zM8 11V7a4 4 0 0 1 8 0v4", globe: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18",
  users: "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21c0-4 3-6 7-6s7 2 7 6M17 3.5a4 4 0 0 1 0 7M22 21c0-3-1.5-5-4-5.7", shield: "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
  check: "M5 12l5 5L20 7", x: "M6 6l12 12M18 6L6 18", play: "M7 4l13 8-13 8z", queue: "M4 5h16v4H4zM4 11h16v4H4zM4 17h10v3H4z",
  percent: "M19 5L5 19M7 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM17 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", stop: "M6 6h12v12H6z", code: "M8 7l-5 5 5 5M16 7l5 5-5 5",
  timer: "M12 8v5l3 2M9 2h6M12 4a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", download: "M12 3v12M7 10l5 5 5-5M4 21h16", refresh: "M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6",
};
function svg(name) {
  const ns = "http://www.w3.org/2000/svg";
  const s = document.createElementNS(ns, "svg"); s.setAttribute("viewBox", "0 0 24 24"); s.setAttribute("aria-hidden", "true");
  const p = document.createElementNS(ns, "path"); p.setAttribute("d", ICONS[name] || ICONS.bot); s.appendChild(p);
  return s;
}
function ic(name, color, cls) { const t = h("span", { class: "ic" + (cls ? " " + cls : "") }, svg(name)); t.style.setProperty("--c", color); return t; }

function toast(msg, err) {
  const t = $("toast");
  t.textContent = msg; t.className = "toast" + (err ? " error" : ""); t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => { t.hidden = true; }, err ? 7000 : 3500);
}

async function api(method, path, body) {
  const res = await fetch("/api/" + path, { method, credentials: "same-origin",
    headers: Object.assign({ "X-OOA-Request": "1" }, body !== undefined ? { "Content-Type": "application/json" } : {}),
    body: body !== undefined ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const e = data.error || {};
    if (e.code === "CR.UNAUTHENTICATED") { showLogin(); }
    if (e.code === "CR.PASSWORD_CHANGE_REQUIRED") { showPassword(); }
    const err = new Error((e.message || res.statusText) + (e.suggestedFix ? " (" + e.suggestedFix + ")" : ""));
    err.code = e.code; throw err;
  }
  return data;
}

const can = (p) => S.user && (S.user.permissions || []).includes(p);
const fmt = (t) => t ? String(t).replace("T", " ").replace(/\.\d+Z$|Z$/, "") : "";
const local = (t) => { if (!t) return ""; const d = new Date(t); return isNaN(d) ? fmt(t) : d.toLocaleString(); };
const botName = (p) => String(p || "").replace(/\.flow\.json$/, "");
const actionName = (a) => S.names[a] || a || "";
function ago(t) {
  if (!t) return "";
  const s = (Date.now() - Date.parse(t)) / 1000;
  if (s < 0) return "in " + dur(-s * 1000);
  if (s < 45) return "just now";
  if (s < 3600) return Math.round(s / 60) + " min ago";
  if (s < 86400) return Math.round(s / 3600) + " h ago";
  return Math.round(s / 86400) + " days ago";
}
function dur(ms) {
  if (ms === null || ms === undefined || ms === "") return "";
  ms = Number(ms);
  if (ms < 1000) return Math.round(ms) + " ms";
  const s = Math.round(ms / 1000);
  if (s < 60) return s + " s";
  if (s < 3600) return Math.floor(s / 60) + " min " + (s % 60) + " s";
  return Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
}
function runStatus(r) { return r.status === "running" && r.pause_requested ? "paused" : r.status === "running" && r.cancel_requested ? "stopping" : r.status; }
const pill = (s) => h("span", { class: "pill " + s, text: s });
function sourceLabel(src) {
  const s = String(src || "");
  if (s.startsWith("schedule:")) return "Schedule";
  if (s.startsWith("trigger:") || s.startsWith("webhook") || s.startsWith("file")) return "Trigger";
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : "";
}
function parseMaybe(t) { if (t === null || t === undefined || t === "") return null; if (typeof t !== "string") return t; try { return JSON.parse(t); } catch (_) { return t; } }
function download(name, text, type) {
  const url = URL.createObjectURL(new Blob([text], { type: type || "text/plain" }));
  const a = h("a", { href: url, download: name });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function csv(rows) { return rows.map((r) => r.map((v) => { const t = v === null || v === undefined ? "" : String(v); return /[",\n\r]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t; }).join(",")).join("\r\n") + "\r\n"; }

// ---------- views ----------
function showLogin() { stopTimer(); closeDrawer(); $("app").hidden = true; $("password-view").hidden = true; $("login-view").hidden = false; $("login-user").focus(); }
function showPassword() { $("app").hidden = true; $("login-view").hidden = true; $("password-view").hidden = false; $("pw-current").focus(); }
function showApp() {
  $("login-view").hidden = true; $("password-view").hidden = true; $("app").hidden = false;
  $("who").textContent = S.user.display_name + " (" + S.user.role.replace("_", " ") + ")";
  $("avatar").textContent = (S.user.display_name || S.user.username || "?").trim().charAt(0).toUpperCase();
  api("GET", "health").then((r) => { $("btn-studio").hidden = !r.studio; }).catch(() => {});
  api("GET", "catalog").then((r) => { S.names = r.actions || {}; }).catch(() => {});
  renderNav(); go(S.page);
}

const PAGES = [
  ["home", "Home", null, "home", "#2563eb"], ["automation", "Automation", "repo.view", "bot", "#6d28d9"], ["activity", "Activity", "runs.view", "pulse", "#ea580c"],
  ["devices", "Devices", "devices.view", "desktop", "#0891b2"], ["schedules", "Schedules", "schedules.view", "clock", "#059669"], ["triggers", "Triggers", "triggers.view", "bolt", "#d97706"],
  ["credentials", "Credentials", null, "lock", "#dc2626"], ["globals", "Global values", "globals.view", "globe", "#0284c7"], ["users", "Users", "users.manage", "users", "#7c3aed"],
  ["audit", "Audit log", "audit.view", "shield", "#475569"]];

function renderNav() {
  $("nav").replaceChildren(...PAGES.filter(([, , p]) => !p || can(p)).map(([id, label, , icon, color]) => {
    const b = h("button", { type: "button", class: S.page === id ? "active" : "", "aria-current": S.page === id ? "page" : null, "aria-label": label, onclick: () => go(id) }, ic(icon, color), label);
    b.style.setProperty("--c", color);
    return b;
  }));
}

function stopTimer() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }

async function go(page) {
  stopTimer();
  closeDrawer();
  S.page = page; renderNav();
  // Each visit renders into its own container: a slow page that finishes after the user moved on writes into a detached element.
  const main = h("div", { class: "view" }, h("p", { class: "muted", text: "Loading..." }));
  $("main").replaceChildren(main);
  try { await VIEWS[page](main); } catch (e) { main.replaceChildren(h("p", { class: "error", text: e.message })); }
  if (main.isConnected) $("main").focus();
}

function header(title, sub, ...actions) {
  const meta = PAGES.find((p) => p[1] === title || p[0] === S.page) || PAGES[0];
  return [h("div", { class: "page-head" }, ic(meta[3], meta[4], "lg"), h("div", { class: "page-titles" }, h("h1", { class: "page", text: title }), h("p", { class: "page-sub", text: sub })),
    actions.filter(Boolean).length ? h("div", { class: "page-actions" }, actions) : null)];
}

/** Simple table (used inside dialogs and drawers). */
function table(cols, rows, onRow) {
  return h("div", { class: "table-wrap" }, h("table", {},
    h("thead", {}, h("tr", {}, cols.map((c) => h("th", { text: c })))),
    h("tbody", {}, rows.length ? rows.map((r) => {
      const tr = h("tr", { class: onRow ? "clickable" : "" }, r.cells.map((c) => h("td", {}, c)));
      if (onRow) { tr.tabIndex = 0; tr.addEventListener("click", () => onRow(r.data)); tr.addEventListener("keydown", (e) => { if (e.key === "Enter") onRow(r.data); }); }
      return tr;
    }) : [h("tr", {}, h("td", { colspan: String(cols.length), class: "muted", text: "Nothing here yet." }))])));
}

/**
 * Table with search, sortable columns, paging and CSV export, all in the browser.
 * columns: [{ label, value(row) for search/sort/CSV, render(row) for the cell, sort: false to disable }]
 */
function dataTable({ columns, rows, onRow, search = "Search", pageSize = 25, empty = "Nothing here yet.", exportName, tools = [] }) {
  const st = { q: "", sort: null, dir: 1, page: 0 };
  const wrap = h("div", { class: "dt" });
  const box = h("div");
  const q = search ? h("input", { type: "search", placeholder: search, "aria-label": search }) : null;
  if (q) q.addEventListener("input", () => { st.q = q.value.trim().toLowerCase(); st.page = 0; render(); });
  const value = (c, r) => { const v = c.value ? c.value(r) : ""; return v === null || v === undefined ? "" : v; };
  const list = () => {
    let l = rows.filter((r) => !st.q || columns.some((c) => String(value(c, r)).toLowerCase().includes(st.q)));
    if (st.sort !== null) { const c = columns[st.sort]; l = l.slice().sort((a, b) => { const x = value(c, a), y = value(c, b); return (typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true })) * st.dir; }); }
    return l;
  };
  const exp = exportName ? h("button", { type: "button", onclick: () => download(exportName + ".csv", csv([columns.filter((c) => c.value).map((c) => c.label), ...list().map((r) => columns.filter((c) => c.value).map((c) => value(c, r)))]), "text/csv") }, ic("download", "#475569", "sm"), "Export CSV") : null;
  function render() {
    const l = list(), pages = Math.max(1, Math.ceil(l.length / pageSize));
    if (st.page >= pages) st.page = pages - 1;
    const slice = l.slice(st.page * pageSize, st.page * pageSize + pageSize);
    const ths = columns.map((c, i) => c.sort === false || !c.value ? h("th", { text: c.label })
      : h("th", { "aria-sort": st.sort === i ? (st.dir > 0 ? "ascending" : "descending") : "none" },
        h("button", { type: "button", class: "th-sort", onclick: () => { if (st.sort === i) st.dir = -st.dir; else { st.sort = i; st.dir = 1; } render(); } }, c.label, h("span", { class: "arrow", text: st.sort === i ? (st.dir > 0 ? " \u25B2" : " \u25BC") : "" }))));
    const body = slice.length ? slice.map((r) => {
      const tr = h("tr", { class: onRow ? "clickable" : "" }, columns.map((c) => h("td", { class: c.cls || null }, c.render ? c.render(r) : String(value(c, r)))));
      if (onRow) { tr.tabIndex = 0; tr.addEventListener("click", (e) => { if (!e.target.closest("button, a, input, select")) onRow(r); }); tr.addEventListener("keydown", (e) => { if (e.key === "Enter" && e.target === tr) onRow(r); }); }
      return tr;
    }) : [h("tr", {}, h("td", { colspan: String(columns.length), class: "muted empty-cell", text: st.q ? "No matches." : empty }))];
    box.replaceChildren(h("div", { class: "table-wrap" }, h("table", {}, h("thead", {}, h("tr", {}, ths)), h("tbody", {}, body))),
      l.length > pageSize ? pager(st.page, pages, l.length, pageSize, (p) => { st.page = p; render(); }) : null);
  }
  wrap.append(h("div", { class: "toolbar" }, q, ...tools, h("span", { class: "spacer" }), exp), box);
  render();
  return wrap;
}

function pager(page, pages, total, size, onPage) {
  return h("div", { class: "pager" }, h("span", { class: "muted", text: (page * size + 1) + "\u2013" + Math.min(total, (page + 1) * size) + " of " + total }),
    h("button", { type: "button", disabled: page === 0, onclick: () => onPage(page - 1) }, "Previous"),
    h("span", { text: "Page " + (page + 1) + " of " + pages }),
    h("button", { type: "button", disabled: page >= pages - 1, onclick: () => onPage(page + 1) }, "Next"));
}

function modal(title, fields, actions) {
  $("modal-title").textContent = title;
  $("modal-body").replaceChildren(...fields);
  $("modal-actions").replaceChildren(...actions, h("button", { type: "button", onclick: closeModal }, "Close"));
  $("modal").hidden = false;
  const first = $("modal-body").querySelector("input, select, textarea, button");
  if (first) first.focus();
}
function closeModal() { $("modal").hidden = true; }

function openDrawer(title, body, actions) {
  $("drawer-title").replaceChildren(...[].concat(title));
  $("drawer-actions").replaceChildren(...[].concat(actions || []).filter(Boolean));
  $("drawer-body").replaceChildren(...[].concat(body).filter(Boolean));
  $("drawer").hidden = false;
}
function closeDrawer() { if (S.drawerTimer) { clearInterval(S.drawerTimer); S.drawerTimer = null; } const d = $("drawer"); if (d) d.hidden = true; }

function secretBox(label, value, note) {
  return h("div", { class: "secret-box" }, h("strong", { text: label }), h("div", { class: "mono", text: value }), note ? h("div", { class: "muted", text: note }) : null);
}
function kv(pairs) { return h("dl", { class: "kv" }, ...pairs.filter(([, v]) => v !== null && v !== undefined && v !== "").flatMap(([k, v]) => [h("dt", { text: k }), h("dd", {}, v)])); }
function progressBar(pct, cls) { const b = h("div", { class: "bar" + (cls ? " " + cls : "") }, h("div", {})); b.firstChild.style.width = Math.max(0, Math.min(100, pct)) + "%"; return b; }

async function loadRefs() {
  const [b, d] = await Promise.all([can("repo.view") ? api("GET", "repo") : { bots: [] }, can("devices.view") ? api("GET", "devices") : { devices: [] }]);
  S.bots = b.bots; S.devices = d.devices;
}
const botSelect = (val) => { const s = h("select", {}, S.bots.filter((b) => b.production_version).map((b) => h("option", { value: b.path, text: botName(b.path) }))); if (val) s.value = val; return s; };
const deviceSelect = (val) => { const s = h("select", {}, S.devices.filter((d) => d.enabled).map((d) => h("option", { value: d.id, text: d.name + " (" + d.status + ")" }))); if (val) s.value = val; return s; };
function parseJson(text, fallback) { if (!text.trim()) return fallback; try { return JSON.parse(text); } catch (e) { throw new Error("Invalid JSON: " + e.message); } }
const deviceName = (id) => (S.devices.find((d) => d.id === id) || {}).name || id;

const VIEWS = {
  async home(main) {
    const d = await api("GET", "dashboard");
    const tile = (n, l, icon, color, onClick) => {
      const t = h(onClick ? "button" : "div", { type: onClick ? "button" : null, class: "card tile" + (onClick ? " clickable" : ""), onclick: onClick || null },
        ic(icon, color), h("div", {}, h("div", { class: "n", text: String(n) }), h("div", { class: "l", text: l })));
      t.style.setProperty("--c", color);
      return t;
    };
    let fillDevices = null;
    const parts = [...header("Home", "Welcome, " + S.user.display_name + ". Here is what your bots have been doing."),
      h("div", { class: "tiles" },
        tile(d.running, "Running now", "pulse", "#ea580c", can("runs.view") ? () => go("activity") : null),
        tile(d.queued, "Waiting to start", "queue", "#d97706", can("runs.view") ? () => go("activity") : null),
        tile(d.success_24h, "Succeeded (24 h)", "check", "#16a34a"), tile(d.failed_24h, "Failed (24 h)", "x", "#dc2626"),
        tile(d.success_rate_24h === null || d.success_rate_24h === undefined ? "\u2014" : d.success_rate_24h + "%", "Success rate (24 h)", "percent", "#059669"),
        tile(d.avg_duration_ms_24h ? dur(d.avg_duration_ms_24h) : "\u2014", "Average run time", "timer", "#0891b2"),
        tile(d.devices_online + " / " + d.devices_total, "Devices online", "desktop", "#0284c7", can("devices.view") ? () => go("devices") : null),
        tile(d.bots, "Bots in the repository", "bot", "#6d28d9", can("repo.view") ? () => go("automation") : null),
        tile(d.schedules, "Active schedules", "clock", "#7c3aed", can("schedules.view") ? () => go("schedules") : null))];
    if (d.days) {
      const max = Math.max(1, ...d.days.map((x) => x.success + x.failed + x.stopped + x.active));
      const chart = h("div", { class: "chart", role: "img", "aria-label": "Runs per day for the last 7 days" }, d.days.map((x) => {
        const col = h("div", { class: "chart-col", title: x.date + ": " + x.success + " succeeded, " + x.failed + " failed, " + x.stopped + " stopped" + (x.active ? ", " + x.active + " active" : "") });
        const stack = h("div", { class: "stack" });
        for (const [k, cls] of [["active", "s-active"], ["stopped", "s-stopped"], ["failed", "s-failed"], ["success", "s-success"]]) {
          if (!x[k]) continue;
          const seg = h("div", { class: "seg " + cls }); seg.style.height = (100 * x[k] / max) + "%"; stack.append(seg);
        }
        col.append(h("div", { class: "chart-n", text: String(x.success + x.failed + x.stopped + x.active || "") }), stack,
          h("div", { class: "chart-l", text: new Date(x.date + "T00:00:00").toLocaleDateString(undefined, { weekday: "short", day: "numeric" }) }));
        return col;
      }));
      const legend = h("div", { class: "legend" }, [["s-success", "Succeeded"], ["s-failed", "Failed"], ["s-stopped", "Stopped"], ["s-active", "Running or waiting"]].map(([c, l]) => h("span", {}, h("i", { class: c }), l)));
      const fails = (d.recent_failures || []).map((f) => {
        const e = parseMaybe(f.error) || {};
        return h("button", { type: "button", class: "list-row", onclick: () => openRun(f.id) }, ic("x", "#dc2626", "sm"),
          h("div", { class: "lr-main" }, h("strong", { text: botName(f.bot_path) + " v" + f.version }), h("div", { class: "muted small", text: (e.code ? e.code + ": " : "") + (e.message || "") })),
          h("span", { class: "muted small", text: ago(f.finished) }));
      });
      const top = (d.top_bots || []).map((b) => h("div", { class: "list-row static" }, ic("bot", "#6d28d9", "sm"), h("div", { class: "lr-main" }, h("strong", { text: botName(b.bot_path) }),
        h("div", { class: "muted small", text: b.runs + " runs, " + (b.failed || 0) + " failed" + (b.avg_ms ? ", about " + dur(b.avg_ms) : "") })),
        progressBar(100 * (Number(b.runs) - Number(b.failed || 0)) / Math.max(1, Number(b.runs)), "ok")));
      parts.push(h("div", { class: "grid-2" },
        h("div", { class: "card" }, h("h2", { text: "Runs in the last 7 days" }), chart, legend),
        h("div", { class: "card" }, h("h2", { text: "Recent failures" }), fails.length ? fails : h("p", { class: "muted", text: "No failed runs. \u2728" }))),
        h("div", { class: "grid-2" },
          h("div", { class: "card" }, h("h2", { text: "Most used bots (7 days)" }), top.length ? top : h("p", { class: "muted", text: "No runs yet." })),
          can("devices.view") ? h("div", { class: "card", id: "home-devices" }, h("h2", { text: "Devices" }), h("p", { class: "muted", text: "Loading..." })) : null));
      if (can("devices.view")) fillDevices = () => api("GET", "devices").then((r) => {
        const box = $("home-devices");
        if (box) box.replaceChildren(h("h2", { text: "Devices" }), ...(r.devices.length ? r.devices.map((dv) => h("button", { type: "button", class: "list-row", onclick: () => openDevice(dv) },
          h("span", { class: "dot " + dv.status }), h("div", { class: "lr-main" }, h("strong", { text: dv.name }), h("div", { class: "muted small", text: dv.status + (dv.running ? ", running a bot" : "") + " \u00B7 last seen " + ago(dv.last_seen) })))) : [h("p", { class: "muted", text: "No devices yet." })]));
      }).catch(() => {});
    }
    if (can("runs.view")) {
      const r = await api("GET", "runs?limit=8");
      parts.push(h("div", { class: "card" }, h("h2", { text: "Recent runs" }), runTable(r.runs, (run) => openRun(run.id))));
    }
    main.replaceChildren(...parts);
    if (fillDevices) fillDevices();
  },

  async automation(main) {
    await loadRefs();
    const r = await api("GET", "repo");
    main.replaceChildren(...header("Automation", "The public repository: bots checked in from Studio, with every version. Run the production version on any device."),
      dataTable({ rows: r.bots, search: "Search bots by name, id or description", exportName: "bots", onRow: openBot, empty: "No bots yet. In Studio, open a bot and press Check in.",
        columns: [
          { label: "Bot", value: (b) => botName(b.path), render: (b) => h("div", { class: "name-cell" }, ic("bot", "#6d28d9", "sm"), h("div", {}, h("strong", { text: botName(b.path) }), h("div", { class: "muted small", text: b.description || "" }))) },
          { label: "Latest", value: (b) => b.latest_version, render: (b) => "v" + b.latest_version },
          { label: "Production", value: (b) => b.production_version || 0, render: (b) => b.production_version ? h("span", { class: "pill success", text: "v" + b.production_version }) : "\u2014" },
          { label: "Status", value: (b) => b.locked_by ? "Checked out by " + b.locked_by : "Checked in", render: (b) => b.locked_by ? h("span", { class: "pill locked", text: "Checked out by " + b.locked_by }) : h("span", { class: "pill", text: "Checked in" }) },
          { label: "Updated", value: (b) => b.updated, render: (b) => h("span", { title: local(b.updated) }, ago(b.updated) + " by " + (b.updated_by || "")) },
          { label: "", sort: false, render: (b) => h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: () => versionsDialog(b) }, "Versions"),
            can("runs.start") && b.production_version ? h("button", { type: "button", class: "primary", onclick: () => runDialog(b) }, "Run") : null,
            b.locked_by && (b.locked_by === S.user.username || can("repo.production")) ? h("button", { type: "button", onclick: async () => {
              try { await api("POST", "repo/undo-checkout", { path: b.path }); toast("Check-out undone"); go("automation"); } catch (err) { toast(err.message, true); } } }, "Undo check-out") : null) }]}));
  },

  async activity(main) {
    await loadRefs().catch(() => {});
    const st = S.activity = Object.assign({ tab: "active", offset: 0, size: 25, f: {} }, S.activity || {});
    const tabs = h("div", { class: "tabs", role: "tablist" });
    const filters = h("div", { class: "filters" });
    const list = h("div");
    const renderTabs = (counts) => tabs.replaceChildren(...[["active", "In progress"], ["history", "History"]].map(([t, l]) =>
      h("button", { type: "button", role: "tab", class: "tab" + (st.tab === t ? " active" : ""), "aria-selected": String(st.tab === t), onclick: () => { st.tab = t; st.offset = 0; renderTabs(counts); renderFilters(); refresh(); } },
        l, counts && counts[t] !== undefined ? h("span", { class: "count", text: String(counts[t]) }) : null)));
    const counts = {};
    function query(limit, offset) {
      const p = new URLSearchParams({ filter: st.tab, limit: String(limit), offset: String(offset) });
      // Dates are whole local days: "to" includes the chosen day.
      const day = (v, next) => { const d = new Date(v + "T00:00:00"); if (next) d.setDate(d.getDate() + 1); return d.toISOString(); };
      if (st.tab === "history") for (const [k, v] of Object.entries(st.f)) if (v) p.set(k, k === "from" ? day(v, false) : k === "to" ? day(v, true) : v);
      return "runs?" + p.toString();
    }
    function renderFilters() {
      if (st.tab !== "history") { filters.replaceChildren(); return; }
      const sel = (key, label, opts) => { const s = h("select", { "aria-label": label, onchange: () => { st.f[key] = s.value; st.offset = 0; refresh(); } }, h("option", { value: "", text: label }), opts.map(([v, t]) => h("option", { value: v, text: t }))); s.value = st.f[key] || ""; return s; };
      const q = h("input", { type: "search", placeholder: "Search bot, run id, user or error", value: st.f.q || "", "aria-label": "Search runs" });
      q.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(() => { st.f.q = q.value.trim(); st.offset = 0; refresh(); }, 300); });
      const date = (key, label) => { const i = h("input", { type: "date", value: st.f[key] || "", "aria-label": label, title: label, onchange: () => { st.f[key] = i.value; st.offset = 0; refresh(); } }); return h("label", { class: "inline" }, label, i); };
      filters.replaceChildren(q,
        sel("status", "Any status", [["success", "Succeeded"], ["failed", "Failed"], ["stopped", "Stopped"]]),
        sel("bot", "Any bot", S.bots.map((b) => [b.path, botName(b.path)])),
        sel("device", "Any device", S.devices.map((d) => [d.id, d.name])),
        sel("source", "Any source", [["manual", "Manual"], ["schedule", "Schedule"], ["trigger", "Trigger"], ["rerun", "Rerun"]]),
        h("input", { type: "text", placeholder: "Started by (user name)", value: st.f.user || "", "aria-label": "Started by", onchange: (e) => { st.f.user = e.target.value.trim(); st.offset = 0; refresh(); } }),
        date("from", "From"), date("to", "To"),
        h("button", { type: "button", class: "link", onclick: () => { st.f = {}; st.offset = 0; renderFilters(); refresh(); } }, "Clear filters"),
        h("span", { class: "spacer" }),
        h("button", { type: "button", onclick: async () => {
          try {
            const r = await api("GET", query(500, 0));
            download("runs.csv", csv([["Run id", "Status", "Bot", "Version", "Device", "Started by", "Source", "Queued", "Started", "Finished", "Duration ms", "Steps", "Error code", "Error"],
              ...r.runs.map((x) => { const e = parseMaybe(x.error) || {}; return [x.id, x.status, botName(x.bot_path), x.version, x.device_name || x.device_id, x.requested_by, x.source, x.created, x.started, x.finished, x.duration_ms, x.steps, e.code || "", e.message || (typeof e === "string" ? e : "")]; })]), "text/csv");
          } catch (e) { toast(e.message, true); } } }, ic("download", "#475569", "sm"), "Export CSV"));
    }
    async function refresh() {
      const [r, a, hcount] = await Promise.all([api("GET", query(st.tab === "active" ? 200 : st.size, st.tab === "active" ? 0 : st.offset)),
        st.tab === "active" ? null : api("GET", "runs?filter=active&limit=1"), st.tab === "history" ? null : api("GET", "runs?filter=history&limit=1")]);
      counts[st.tab] = r.total; if (a) counts.active = a.total; if (hcount) counts.history = hcount.total;
      renderTabs(counts);
      if (st.tab === "active") list.replaceChildren(r.runs.length ? h("div", { class: "live-list" }, r.runs.map(liveCard)) : h("div", { class: "empty-state" }, ic("check", "#16a34a", "lg"), h("strong", { text: "Nothing is running" }), h("p", { class: "muted", text: "Runs appear here as soon as they are queued. Start one from Automation." })));
      else list.replaceChildren(historyTable(r.runs), r.total > st.size ? pager(Math.floor(st.offset / st.size), Math.ceil(r.total / st.size), r.total, st.size, (p) => { st.offset = p * st.size; refresh(); }) : null);
    }
    function liveCard(run) {
      const p = run.progress || { total: 0, finished: 0 };
      const status = runStatus(run);
      const pct = p.total ? 100 * p.finished / p.total : status === "queued" ? 0 : 5;
      const elapsed = run.started ? Date.now() - Date.parse(run.started) : null;
      const card = h("div", { class: "live-card " + status, tabindex: "0", role: "button", "aria-label": "Run of " + botName(run.bot_path), onclick: (e) => { if (!e.target.closest("button")) openRun(run.id); }, onkeydown: (e) => { if (e.key === "Enter" && e.target === card) openRun(run.id); } },
        h("div", { class: "lc-top" }, pill(status), h("strong", { text: botName(run.bot_path) }), h("span", { class: "muted", text: "v" + run.version }), h("span", { class: "spacer" }),
          can("runs.stop") ? h("div", { class: "row-actions tight" },
            status === "queued" ? null : run.pause_requested ? h("button", { type: "button", onclick: () => control(run.id, "resume", refresh) }, "Resume") : h("button", { type: "button", onclick: () => control(run.id, "pause", refresh) }, "Pause"),
            h("button", { type: "button", class: "danger", onclick: () => control(run.id, "stop", refresh) }, "Stop")) : null),
        progressBar(pct, status === "queued" ? "waiting" : ""),
        h("div", { class: "lc-now" }, status === "queued" ? "Waiting for " + (run.device_name || run.device_id) + " to pick it up"
          : h("span", {}, "Step " + Math.min(p.finished + 1, p.total || p.finished + 1) + (p.total ? " of " + p.total : "") + ": ", h("strong", { text: p.action ? actionName(p.action) : "starting" }), p.step_id ? h("span", { class: "muted", text: "  [" + p.step_id + "]" }) : null)),
        h("div", { class: "lc-meta muted" }, [["Device", run.device_name || run.device_id], ["Started by", run.requested_by + " (" + sourceLabel(run.source) + ")"],
          ["Queued", ago(run.created)], ["Running for", elapsed !== null ? dur(elapsed) : "\u2014"]].map(([k, v]) => h("span", {}, k + ": ", h("b", { text: v })))));
      return card;
    }
    function historyTable(runs) {
      return h("div", { class: "table-wrap" }, h("table", {},
        h("thead", {}, h("tr", {}, ["Status", "Bot", "Device", "Started by", "Source", "Started", "Duration", "Steps", "Error", ""].map((t) => h("th", { text: t })))),
        h("tbody", {}, runs.length ? runs.map((x) => {
          const e = parseMaybe(x.error);
          const tr = h("tr", { class: "clickable", tabindex: "0" },
            h("td", {}, pill(x.status)), h("td", {}, h("strong", { text: botName(x.bot_path) }), h("span", { class: "muted", text: " v" + x.version })),
            h("td", { text: x.device_name || x.device_id }), h("td", { text: x.requested_by }), h("td", { text: sourceLabel(x.source) }),
            h("td", { title: local(x.started || x.created), text: ago(x.started || x.created) }), h("td", { text: dur(x.duration_ms) }), h("td", { text: x.steps ?? "" }),
            h("td", { class: "err-cell", title: e && e.message ? e.message : "", text: e ? (e.code || (typeof e === "string" ? e : "")) : "" }),
            h("td", {}, can("runs.start") ? h("button", { type: "button", onclick: () => rerun(x.id) }, "Rerun") : null));
          tr.addEventListener("click", (ev) => { if (!ev.target.closest("button")) openRun(x.id); });
          tr.addEventListener("keydown", (ev) => { if (ev.key === "Enter" && ev.target === tr) openRun(x.id); });
          return tr;
        }) : [h("tr", {}, h("td", { colspan: "10", class: "muted empty-cell", text: "No runs match these filters." }))])));
    }
    renderTabs(); renderFilters();
    main.replaceChildren(...header("Activity", "Runs on all devices: what is running now, and the history of every run. Select a run for its live step log, inputs, outputs and errors."), tabs, filters, list);
    await refresh();
    if (main.isConnected) S.timer = setInterval(() => { if (st.tab === "active" || !st.offset) refresh().catch(() => {}); }, 2500);
  },

  async devices(main) {
    const r = await api("GET", "devices");
    S.devices = r.devices;
    const actions = can("devices.manage") ? [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const k = await api("POST", "devices/registration-key", { hours: 24 });
        modal("Register a device", [
          h("p", { text: "On the Windows machine that will run bots, open PowerShell in the platform folder and run:" }),
          secretBox("Command", '.\\gradlew.bat :cli:run --args="agent register --url ' + location.origin + " --key " + k.key + '"', "The key works once and expires in 24 hours. It is shown only now."),
          h("p", { class: "muted", text: "Then start the agent with:  .\\gradlew.bat :cli:run --args=\"agent start\"" }),
          h("p", { class: "muted", text: "On this computer you do not need this: ooa server (or start-ooa.bat) registers and starts a Bot Agent for this computer automatically." })], []);
      } catch (e) { toast(e.message, true); }
    } }, "Register a device")] : [];
    const online = r.devices.filter((d) => d.status === "online").length;
    main.replaceChildren(...header("Devices", "Machines running the Bot Agent. A device is online when it checked in during the last minute. " + online + " of " + r.devices.length + " online.", ...actions),
      dataTable({ rows: r.devices, search: "Search devices", exportName: "devices", onRow: openDevice, empty: "No devices yet. Register one, or start ooa server on this computer.",
        columns: [
          { label: "Device", value: (d) => d.name, render: (d) => h("div", { class: "name-cell" }, h("span", { class: "dot " + d.status }), h("div", {}, h("strong", { text: d.name }), h("div", { class: "muted small", text: (d.hostname || "") + " " + (d.os || "") }))) },
          { label: "Status", value: (d) => d.status, render: (d) => pill(d.status) },
          { label: "Running", value: (d) => d.running, render: (d) => d.running ? h("span", { class: "pill running", text: d.running + " bot" }) : "\u2014" },
          { label: "Agent", value: (d) => d.agent_version || "" },
          { label: "Last seen", value: (d) => d.last_seen || "", render: (d) => h("span", { title: local(d.last_seen) }, ago(d.last_seen)) },
          { label: "Registered", value: (d) => d.registered, render: (d) => ago(d.registered) + " by " + (d.registered_by || "") },
          { label: "", sort: false, render: (d) => can("devices.manage") ? h("button", { type: "button", onclick: async () => { try { await api("PATCH", "devices/" + d.id, { enabled: !d.enabled }); go("devices"); } catch (e) { toast(e.message, true); } } }, d.enabled ? "Disable" : "Enable") : "" }]}));
    if (main.isConnected) S.timer = setInterval(() => { if (S.page === "devices" && $("modal").hidden && $("drawer").hidden) go("devices"); }, 15000);
  },

  async schedules(main) {
    await loadRefs();
    const r = await api("GET", "schedules");
    const when = (s) => s.kind === "every" ? "Every " + s.every_minutes + " min" : s.kind === "daily" ? "Daily at " + s.at_time : s.days + " at " + s.at_time;
    main.replaceChildren(...header("Schedules", "Run bots automatically. Scheduled runs use the vault permissions of the person who created the schedule.",
      can("schedules.manage") ? h("button", { type: "button", class: "primary", onclick: scheduleDialog }, "New schedule") : null),
      dataTable({ rows: r.schedules, search: "Search schedules", exportName: "schedules", empty: "No schedules yet.",
        onRow: can("runs.view") ? (s) => { S.activity = { tab: "history", offset: 0, size: 25, f: { source: "schedule", bot: s.bot_path } }; go("activity"); } : null,
        columns: [
          { label: "Schedule", value: (s) => s.name, render: (s) => h("div", { class: "name-cell" }, ic("clock", "#059669", "sm"), h("strong", { text: s.name })) },
          { label: "Bot", value: (s) => botName(s.bot_path) }, { label: "Device", value: (s) => deviceName(s.device_id) },
          { label: "When", value: (s) => when(s) + " (" + s.time_zone + ")" },
          { label: "Next run", value: (s) => s.enabled ? s.next_run || "" : "", render: (s) => s.enabled ? h("span", { title: local(s.next_run) }, ago(s.next_run)) : "\u2014" },
          { label: "Last run", value: (s) => s.last_run || "", render: (s) => s.last_run ? h("span", { title: local(s.last_run) }, ago(s.last_run)) : "never" },
          { label: "Enabled", value: (s) => s.enabled ? "Yes" : "No", render: (s) => s.enabled ? pill("enabled") : pill("disabled") },
          { label: "", sort: false, render: (s) => h("div", { class: "row-actions tight" },
            can("runs.start") ? h("button", { type: "button", onclick: async () => { try { const x = await api("POST", "schedules/" + s.id + "/run"); toast("Queued " + x.run_id); } catch (e) { toast(e.message, true); } } }, "Run now") : null,
            can("schedules.manage") ? h("button", { type: "button", onclick: async () => { await api("PATCH", "schedules/" + s.id, { enabled: !s.enabled }); go("schedules"); } }, s.enabled ? "Disable" : "Enable") : null,
            can("schedules.manage") ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete schedule " + s.name + "?")) { await api("DELETE", "schedules/" + s.id); go("schedules"); } } }, "Delete") : null) }]}));
  },

  async triggers(main) {
    await loadRefs();
    const r = await api("GET", "triggers");
    main.replaceChildren(...header("Triggers", "Start bots on events: a file arriving in a folder on a device, or a call to a webhook. Studio also has its own local triggers for development.",
      can("triggers.manage") ? h("button", { type: "button", class: "primary", onclick: triggerDialog }, "New trigger") : null),
      dataTable({ rows: r.triggers, search: "Search triggers", exportName: "triggers", empty: "No triggers yet.",
        columns: [
          { label: "Trigger", value: (t) => t.name, render: (t) => h("div", { class: "name-cell" }, ic("bolt", "#d97706", "sm"), h("strong", { text: t.name })) },
          { label: "Kind", value: (t) => t.kind === "file" ? "File arrives" : "Webhook" },
          { label: "Bot", value: (t) => botName(t.bot_path) }, { label: "Device", value: (t) => deviceName(t.device_id) },
          { label: "Details", value: (t) => t.kind === "file" ? t.folder + " / " + t.pattern : "POST " + location.origin + "/api/hooks/" + t.id, render: (t) => h("span", { class: "mono small", text: t.kind === "file" ? t.folder + " / " + t.pattern : "POST " + location.origin + "/api/hooks/" + t.id }) },
          { label: "Enabled", value: (t) => t.enabled ? "Yes" : "No", render: (t) => t.enabled ? pill("enabled") : pill("disabled") },
          { label: "", sort: false, render: (t) => can("triggers.manage") ? h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: async () => { await api("PATCH", "triggers/" + t.id, { enabled: !t.enabled }); go("triggers"); } }, t.enabled ? "Disable" : "Enable"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete trigger " + t.name + "?")) { await api("DELETE", "triggers/" + t.id); go("triggers"); } } }, "Delete")) : "" }]}));
  },

  async credentials(main) {
    const r = await api("GET", "lockers");
    const q = h("input", { type: "search", placeholder: "Search lockers and credentials", "aria-label": "Search lockers" });
    const box = h("div", { class: "locker-grid" });
    const render = () => {
      const f = q.value.trim().toLowerCase();
      const lockers = r.lockers.filter((l) => !f || (l.name + " " + (l.description || "") + " " + l.credentials.map((c) => c.name + " " + c.attribute).join(" ")).toLowerCase().includes(f)).map((l) => h("div", { class: "card locker" },
        h("div", { class: "locker-head" }, ic("lock", "#dc2626"), h("div", {}, h("h2", { text: l.name }), h("p", { class: "muted", text: (l.description || "") + "  Owners: " + l.owners + ".  Consumers: " + (l.consumers || "none") + "." }))),
        table(["Credential", "Attribute", "Updated", ""], l.credentials.map((c) => ({ data: c, cells: [c.name, c.attribute, ago(c.updated) + " by " + c.updated_by,
          l.can_manage ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + c.name + "/" + c.attribute + "?")) { await api("DELETE", "lockers/" + l.name + "/" + c.name + "/" + c.attribute); go("credentials"); } } }, "Delete") : ""] }))),
        l.can_manage ? h("div", { class: "row-actions" },
          h("button", { type: "button", onclick: () => credentialDialog(l.name) }, "Add or update credential"),
          h("button", { type: "button", onclick: () => consumersDialog(l) }, "Consumers")) : null));
      box.replaceChildren(...(lockers.length ? lockers : [h("p", { class: "muted", text: r.lockers.length ? "No matches." : "No lockers you own or consume yet." })]));
    };
    q.addEventListener("input", render);
    main.replaceChildren(...header("Credentials", "Encrypted lockers. Values are never shown again after saving; bots get them only while running, and every use is audited.",
      can("lockers.manage") || can("lockers.own") ? h("button", { type: "button", class: "primary", onclick: lockerDialog }, "New locker") : null), h("div", { class: "toolbar" }, q), box);
    render();
  },

  async globals(main) {
    const r = await api("GET", "globals");
    main.replaceChildren(...header("Global values", "Shared values every bot can read as $global.name.", can("globals.manage") ? h("button", { type: "button", class: "primary", onclick: () => globalDialog() }, "New global value") : null),
      dataTable({ rows: r.globals, search: "Search global values", exportName: "global-values", empty: "No global values yet.",
        columns: [
          { label: "Name", value: (g) => "$global." + g.name, render: (g) => h("code", { text: "$global." + g.name }) },
          { label: "Value", value: (g) => JSON.stringify(g.value), render: (g) => h("code", { class: "val", text: JSON.stringify(g.value) }) },
          { label: "Updated", value: (g) => g.updated, render: (g) => h("span", { title: local(g.updated) }, ago(g.updated) + " by " + g.updated_by) },
          { label: "", sort: false, render: (g) => can("globals.manage") ? h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => globalDialog(g) }, "Edit"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + g.name + "?")) { await api("DELETE", "globals/" + g.name); go("globals"); } } }, "Delete")) : "" }]}));
  },

  async users(main) {
    const r = await api("GET", "users");
    S.users = r.users;
    const roleSel = (u) => { const s = h("select", { "aria-label": "Role of " + u.username, onchange: async (e) => { try { await api("PATCH", "users/" + u.id, { role: e.target.value }); toast("Role changed"); } catch (err) { toast(err.message, true); go("users"); } } },
      ["admin", "bot_creator", "bot_runner", "viewer"].map((x) => h("option", { value: x, text: x.replace("_", " ") }))); s.value = u.role; return s; };
    const status = (u) => u.enabled ? (u.must_change ? "Must change password" : "Active") : "Disabled";
    main.replaceChildren(...header("Users", "People who can sign in, and their roles.", h("button", { type: "button", class: "primary", onclick: userDialog }, "New user")),
      dataTable({ rows: r.users, search: "Search users", exportName: "users", onRow: can("audit.view") ? openUser : null,
        columns: [
          { label: "User", value: (u) => (u.display_name || "") + " " + u.username, render: (u) => h("div", { class: "name-cell" }, h("span", { class: "avatar sm", text: (u.display_name || u.username).charAt(0).toUpperCase() }), h("div", {}, h("strong", { text: u.display_name || u.username }), h("div", { class: "muted small", text: u.username }))) },
          { label: "Role", value: (u) => u.role, render: roleSel },
          { label: "Status", value: status, render: (u) => pill(u.enabled ? (u.must_change ? "pending" : "active") : "disabled") },
          { label: "Last sign-in", value: (u) => u.last_login || "", render: (u) => u.last_login ? h("span", { title: local(u.last_login) }, ago(u.last_login)) : "never" },
          { label: "", sort: false, render: (u) => h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: async () => { try { await api("PATCH", "users/" + u.id, { enabled: !u.enabled }); go("users"); } catch (e) { toast(e.message, true); } } }, u.enabled ? "Disable" : "Enable"),
            h("button", { type: "button", onclick: async () => { if (!confirm("Reset the password of " + u.username + "?")) return; const p = await api("POST", "users/" + u.id + "/reset-password");
              modal("Temporary password", [secretBox(u.username, p.temporary_password, "Give it to the user securely. They must change it at first sign-in. Shown only once.")], []); } }, "Reset password")) }]}));
  },

  async audit(main) {
    const q = h("input", { type: "search", placeholder: "Filter by user, action or object", "aria-label": "Filter audit log" });
    const result = h("select", { "aria-label": "Result" }, h("option", { value: "", text: "Any result" }), h("option", { value: "ok", text: "Succeeded" }), h("option", { value: "failed", text: "Failed or refused" }));
    const box = h("div"), status = h("span", { class: "muted" });
    const render = async () => {
      const r = await api("GET", "audit?limit=1000&q=" + encodeURIComponent(q.value));
      const rows = r.records.filter((a) => !result.value || (result.value === "ok" ? a.result === "ok" : a.result !== "ok"));
      box.replaceChildren(dataTable({ rows, search: false, pageSize: 50, exportName: "audit-log",
        columns: [{ label: "#", value: (a) => a.seq }, { label: "Time", value: (a) => a.ts, render: (a) => h("span", { title: a.ts }, local(a.ts)) }, { label: "Who", value: (a) => a.actor },
          { label: "Action", value: (a) => a.action, render: (a) => h("span", { class: "tag", text: a.action }) }, { label: "Object", value: (a) => (a.object_type || "") + " " + (a.object_id || "") },
          { label: "Result", value: (a) => a.result, render: (a) => h("span", { class: "pill " + (a.result === "ok" ? "success" : "failed"), text: a.result }) },
          { label: "Details", value: (a) => a.details || "", render: (a) => h("span", { class: "mono small", text: a.details || "" }) }] }));
    };
    q.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(render, 250); });
    result.addEventListener("change", render);
    const verify = h("button", { type: "button", onclick: async () => {
      const v = await api("GET", "audit/verify");
      status.textContent = v.valid ? "Chain intact: " + v.records + " records verified." : "TAMPERING DETECTED at record " + v.broken_at + ".";
      status.className = v.valid ? "ok-text" : "error";
    } }, "Verify integrity");
    main.replaceChildren(...header("Audit log", "Every sign-in, change, run and credential use. Records are hash-chained, so editing history is detectable."), h("div", { class: "toolbar" }, q, result, verify, status), box);
    await render();
  },
};

function runTable(runs, onRow) {
  return table(["Status", "Bot", "Device", "Started by", "Source", "Created", "Duration"], runs.map((r) => ({ data: r, cells: [pill(runStatus(r)),
    botName(r.bot_path) + " v" + r.version, r.device_name || r.device_id, r.requested_by, sourceLabel(r.source), h("span", { title: local(r.created) }, ago(r.created)), dur(r.duration_ms)] })), onRow);
}

async function control(id, action, after) {
  try { await api("POST", "runs/" + id + "/" + action); toast(action === "stop" ? "Stop requested" : action === "pause" ? "Pause requested: the bot pauses after the current step" : "Resume requested"); if (after) after(); }
  catch (e) { toast(e.message, true); }
}
async function rerun(id) {
  try { const r = await api("POST", "runs/" + id + "/rerun"); toast("Queued again as " + r.run_id + " (v" + r.version + ")"); openRun(r.run_id); }
  catch (e) { toast(e.message, true); }
}

// ---------- run details drawer ----------
async function openRun(id) {
  const st = { after: 0, events: [], run: null, tab: S.runTab || "steps", filter: "all" };
  const body = h("div", { class: "run-detail" });
  async function refresh() {
    const r = await api("GET", "runs/" + id + "?after=" + st.after);
    st.run = r;
    for (const e of r.events) { st.events.push(e); st.after = Math.max(st.after, e.seq); }
    render();
    if (!["queued", "running"].includes(r.status) && S.drawerTimer) { clearInterval(S.drawerTimer); S.drawerTimer = null; }
  }
  function derive() {
    const steps = new Map(), vars = new Map();
    let total = 0, finished = 0, current = null, t0 = st.events.length ? st.events[0].ts : 0;
    for (const e of st.events) {
      const d = e.data || {};
      if (e.type === "run.started" && !total && d.totalSteps && !d.depth) total = d.totalSteps;
      if (e.stepId && e.type.startsWith("step.")) {
        const s = steps.get(e.stepId) || { id: e.stepId, action: d.action, status: "running", start: e.ts, retries: 0 };
        if (d.action) s.action = d.action;
        if (e.type === "step.started") { s.status = "running"; s.start = s.start || e.ts; current = s; }
        if (e.type === "step.finished") { s.status = "done"; s.ms = d.durationMs; finished++; }
        if (e.type === "step.failed") { s.status = "failed"; s.error = (d.code ? d.code + ": " : "") + (d.message || ""); }
        if (e.type === "step.retry") s.retries++;
        steps.set(e.stepId, s);
      }
      if (e.type === "variable.set") vars.set(d.name, d.value);
    }
    return { steps: [...steps.values()], vars, total, finished, current, t0 };
  }
  function line(e, t0) {
    const d = e.data || {};
    let text = e.type;
    if (e.type === "step.started") text = "start  " + actionName(d.action);
    else if (e.type === "step.finished") text = "done   " + actionName(d.action) + "  (" + dur(d.durationMs) + ")";
    else if (e.type === "step.failed") text = "FAILED " + actionName(d.action) + "  " + (d.code || "") + ": " + (d.message || "");
    else if (e.type === "step.retry") text = "retry " + d.attempt + " after " + d.code;
    else if (e.type === "variable.set") text = d.name + " = " + d.value;
    else if (e.type === "log") text = "log    " + d.message;
    else if (e.type === "run.started") text = "run started  " + (d.automation || "");
    else if (e.type === "run.finished") text = "run " + d.status + "  (" + dur(d.durationMs) + ")";
    return { t: "+" + ((e.ts - t0)).toFixed(2) + "s", step: e.stepId || "", text, failed: e.type === "step.failed", kind: e.type };
  }
  function render() {
    const r = st.run, x = derive();
    const status = runStatus(r), active = ["queued", "running"].includes(r.status);
    const err = parseMaybe(r.error);
    const elapsed = r.duration_ms || (r.started ? Date.now() - Date.parse(r.started) : null);
    const pct = x.total ? 100 * x.finished / x.total : r.status === "success" ? 100 : 0;
    const tabsDef = [["steps", "Steps (" + x.steps.length + ")"], ["log", "Log (" + st.events.length + ")"], ["vars", "Variables (" + x.vars.size + ")"], ["io", "Inputs and outputs"]];
    const tabs = h("div", { class: "tabs small", role: "tablist" }, tabsDef.map(([k, l]) => h("button", { type: "button", role: "tab", class: "tab" + (st.tab === k ? " active" : ""), "aria-selected": String(st.tab === k), onclick: () => { st.tab = k; S.runTab = k; render(); } }, l)));
    let pane;
    if (st.tab === "steps") {
      pane = x.steps.length ? h("div", { class: "timeline" }, x.steps.map((s) => h("div", { class: "tl-row " + s.status },
        ic(s.status === "failed" ? "x" : s.status === "done" ? "check" : "play", s.status === "failed" ? "#dc2626" : s.status === "done" ? "#16a34a" : "#ea580c", "sm"),
        h("div", { class: "tl-main" }, h("strong", { text: actionName(s.action) }), h("span", { class: "muted small", text: "  [" + s.id + "]" + (s.retries ? "  retried " + s.retries + "x" : "") }),
          s.error ? h("div", { class: "error small", text: s.error }) : null),
        h("span", { class: "muted small", text: s.status === "running" ? "running" : dur(s.ms) }))))
        : h("p", { class: "muted", text: active ? "Waiting for the device to start the bot..." : "No steps were recorded." });
    } else if (st.tab === "log") {
      const lines = st.events.map((e) => line(e, x.t0)).filter((l) => st.filter === "all" || (st.filter === "errors" ? l.failed || l.kind === "step.retry" : st.filter === "logs" ? l.kind === "log" : l.kind === "variable.set"));
      const fsel = h("div", { class: "seg" }, [["all", "All"], ["errors", "Errors"], ["logs", "Log messages"], ["vars", "Variables"]].map(([k, l]) => h("button", { type: "button", "aria-pressed": String(st.filter === k), onclick: () => { st.filter = k; render(); } }, l)));
      pane = h("div", {}, h("div", { class: "toolbar" }, fsel), h("div", { class: "events" }, lines.length ? lines.map((l) => h("div", { class: l.failed ? "failed" : "" }, h("span", { class: "t", text: l.t }), l.step ? h("span", { class: "sid", text: "[" + l.step + "] " }) : null, l.text)) : [h("div", { class: "muted", text: "Nothing to show." })]));
    } else if (st.tab === "vars") {
      pane = x.vars.size ? table(["Variable", "Last value"], [...x.vars.entries()].map(([k, v]) => ({ data: k, cells: [h("code", { text: k }), h("code", { class: "val", text: String(v) })] })))
        : h("p", { class: "muted", text: "No variables were set yet. Credentials always show as ****." });
    } else {
      pane = h("div", { class: "grid-2 tight" }, h("div", {}, h("strong", { text: "Inputs" }), h("pre", { class: "events", text: JSON.stringify(parseMaybe(r.inputs) || {}, null, 2) })),
        h("div", {}, h("strong", { text: "Outputs" }), h("pre", { class: "events", text: r.outputs ? JSON.stringify(parseMaybe(r.outputs), null, 2) : active ? "Available when the run ends." : "{}" })));
    }
    body.replaceChildren(
      h("div", { class: "run-status" }, pill(status), h("span", { class: "muted", text: active ? (r.status === "queued" ? "Waiting for " + (r.device_name || r.device_id) : "Running for " + dur(elapsed)) : (status === "success" ? "Finished in " : "Ended after ") + dur(elapsed) })),
      active || x.total ? h("div", { class: "run-progress" }, progressBar(pct, r.status === "failed" ? "bad" : r.status === "success" ? "ok" : ""),
        h("div", { class: "muted small", text: x.finished + (x.total ? " of " + x.total : "") + " steps" + (active && x.current ? "  \u00B7  now: " + actionName(x.current.action) + " [" + x.current.id + "]" : "") })) : null,
      err ? h("div", { class: "error-box" }, h("div", {}, h("span", { class: "code", text: err.code || "Error" }), " ", err.message || (typeof err === "string" ? err : "")),
        err.stepId ? h("div", { class: "small", text: "At step " + err.stepId }) : null, err.suggestedFix ? h("div", { class: "small", text: "Fix: " + err.suggestedFix }) : null) : null,
      kv([["Run", h("code", { text: r.id })], ["Bot", botName(r.bot_path) + " v" + r.version], ["Device", r.device_name || r.device_id], ["Started by", r.requested_by + " (" + sourceLabel(r.source) + ")"],
        ["Queued", local(r.created)], ["Started", local(r.started)], ["Finished", local(r.finished)], ["Steps run", r.steps]]),
      tabs, pane);
    $("drawer-actions").replaceChildren(...[
      active && can("runs.stop") && r.status === "running" ? (r.pause_requested ? h("button", { type: "button", onclick: () => control(id, "resume", refresh) }, "Resume") : h("button", { type: "button", onclick: () => control(id, "pause", refresh) }, "Pause")) : null,
      active && can("runs.stop") ? h("button", { type: "button", class: "danger", onclick: () => control(id, "stop", refresh) }, "Stop") : null,
      !active && can("runs.start") ? h("button", { type: "button", class: "primary", onclick: () => rerun(id) }, "Rerun") : null,
      h("button", { type: "button", onclick: () => download(id + ".log", st.events.map((e) => { const l = line(e, x.t0); return l.t + "  " + (l.step ? "[" + l.step + "] " : "") + l.text; }).join("\r\n") + "\r\n") }, ic("download", "#475569", "sm"), "Download log")].filter(Boolean));
    $("drawer-title").replaceChildren(ic("pulse", "#ea580c"), h("span", { text: botName(r.bot_path) }));
  }
  openDrawer("Run", body, []);
  try { await refresh(); } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); return; }
  if (["queued", "running"].includes(st.run.status)) S.drawerTimer = setInterval(() => refresh().catch(() => {}), 2000);
}

// ---------- bot, device and user drawers ----------
async function openBot(b) {
  const body = h("div", { class: "run-detail" });
  openDrawer([ic("bot", "#6d28d9"), h("span", { text: botName(b.path) })], body, [
    can("runs.start") && b.production_version ? h("button", { type: "button", class: "primary", onclick: () => runDialog(b) }, "Run") : null,
    h("button", { type: "button", onclick: () => versionsDialog(b) }, "Versions")]);
  try {
    const [prod, runs, vers] = await Promise.all([b.production_version ? api("GET", "repo/bot?path=" + encodeURIComponent(b.path) + "&version=production") : null,
      can("runs.view") ? api("GET", "runs?limit=10&bot=" + encodeURIComponent(b.path)) : { runs: [] }, api("GET", "repo/versions?path=" + encodeURIComponent(b.path))]);
    const flow = prod ? prod.flow : null;
    const inputs = flow ? (flow.variables || []).filter((v) => v.direction === "input") : [];
    const outputs = flow ? (flow.variables || []).filter((v) => v.direction === "output") : [];
    body.replaceChildren(
      b.description ? h("p", { text: b.description }) : null,
      kv([["Bot id", flow ? h("code", { text: flow.id }) : null], ["Kind", flow ? flow.kind : null], ["Production", b.production_version ? "v" + b.production_version : "none"], ["Latest", "v" + b.latest_version],
        ["Status", b.locked_by ? "Checked out by " + b.locked_by : "Checked in"], ["Updated", local(b.updated) + " by " + (b.updated_by || "")], ["Steps", flow ? String((flow.steps || []).length) + " top-level" : null]]),
      h("h3", { text: "Inputs" }), inputs.length ? table(["Name", "Type", "Required", "Description"], inputs.map((v) => ({ data: v, cells: [h("code", { text: v.name }), v.type, v.required ? "Yes" : "", v.description || ""] }))) : h("p", { class: "muted", text: "No inputs." }),
      outputs.length ? [h("h3", { text: "Outputs" }), table(["Name", "Type", "Description"], outputs.map((v) => ({ data: v, cells: [h("code", { text: v.name }), v.type, v.description || ""] })))] : null,
      h("h3", { text: "Recent runs" }), runTable(runs.runs, (r) => openRun(r.id)),
      h("h3", { text: "Versions" }), table(["Version", "Author", "Date", "Comment"], vers.versions.slice(0, 8).map((v) => ({ data: v, cells: ["v" + v.version + (v.version === b.production_version ? " (production)" : ""), v.author, ago(v.created), v.comment || ""] }))));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

async function openDevice(d) {
  const body = h("div", { class: "run-detail" });
  const rename = can("devices.manage") ? h("button", { type: "button", onclick: async () => {
    const n = prompt("New name for " + d.name + ":", d.name);
    if (!n || n === d.name) return;
    try { await api("PATCH", "devices/" + d.id, { name: n }); toast("Renamed"); if (S.page === "devices") go("devices"); else closeDrawer(); } catch (e) { toast(e.message, true); }
  } }, "Rename") : null;
  openDrawer([h("span", { class: "dot " + d.status }), h("span", { text: d.name })], body, [
    can("runs.start") ? h("button", { type: "button", class: "primary", onclick: async () => { await loadRefs(); runDialog(null, d.id); } }, "Run a bot here") : null, rename,
    can("devices.manage") ? h("button", { type: "button", onclick: async () => { try { await api("PATCH", "devices/" + d.id, { enabled: !d.enabled }); go("devices"); } catch (e) { toast(e.message, true); } } }, d.enabled ? "Disable" : "Enable") : null]);
  try {
    const [active, hist] = can("runs.view") ? await Promise.all([api("GET", "runs?filter=active&device=" + encodeURIComponent(d.id)), api("GET", "runs?filter=history&limit=15&device=" + encodeURIComponent(d.id))]) : [{ runs: [] }, { runs: [] }];
    body.replaceChildren(
      kv([["Status", pill(d.status)], ["Host name", d.hostname], ["Operating system", d.os], ["Agent version", d.agent_version], ["Last seen", local(d.last_seen) + " (" + ago(d.last_seen) + ")"],
        ["Registered", local(d.registered) + " by " + (d.registered_by || "")], ["Device id", h("code", { text: d.id })]]),
      h("h3", { text: "Now" }), active.runs.length ? runTable(active.runs, (r) => openRun(r.id)) : h("p", { class: "muted", text: "Idle." }),
      h("h3", { text: "History" }), runTable(hist.runs, (r) => openRun(r.id)));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

async function openUser(u) {
  const body = h("div", { class: "run-detail" });
  openDrawer([h("span", { class: "avatar sm", text: (u.display_name || u.username).charAt(0).toUpperCase() }), h("span", { text: u.display_name || u.username })], body, []);
  try {
    const a = await api("GET", "audit?limit=30&q=" + encodeURIComponent(u.username));
    body.replaceChildren(kv([["User name", u.username], ["Role", u.role.replace("_", " ")], ["Status", u.enabled ? (u.must_change ? "Must change password" : "Active") : "Disabled"], ["Last sign-in", local(u.last_login) || "never"]]),
      h("h3", { text: "Recent activity" }), table(["Time", "Action", "Object", "Result"], a.records.filter((x) => x.actor === u.username).map((x) => ({ data: x, cells: [local(x.ts), x.action, (x.object_type || "") + " " + (x.object_id || ""), x.result] }))));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

// ---------- dialogs ----------
async function versionsDialog(b) {
  const r = await api("GET", "repo/versions?path=" + encodeURIComponent(b.path));
  const picks = new Set();
  const compare = h("button", { type: "button", disabled: true, onclick: () => { const [x, y] = [...picks].sort((p, q) => p - q); diffDialog(b, x, y); } }, "Compare selected");
  modal("Versions of " + botName(b.path), [h("p", { class: "muted", text: "Tick two versions to compare them." }),
    table(["", "Version", "Author", "Date", "Comment", ""], r.versions.map((v) => ({ data: v, cells: [
      h("input", { type: "checkbox", "aria-label": "Select v" + v.version, onchange: (e) => { if (e.target.checked) picks.add(v.version); else picks.delete(v.version); compare.disabled = picks.size !== 2; } }),
      "v" + v.version + (v.version === b.production_version ? " (production)" : ""), v.author, local(v.created), v.comment || "",
      can("repo.production") && v.version !== b.production_version ? h("button", { type: "button", onclick: async () => {
        try { await api("POST", "repo/production", { path: b.path, version: v.version }); toast("Production is now v" + v.version); closeModal(); go("automation"); } catch (e) { toast(e.message, true); } } }, "Set as production") : ""] })))],
  [compare]);
}

/** Line-by-line comparison of two versions (longest common subsequence on the formatted JSON). */
async function diffDialog(b, v1, v2) {
  try {
    const [a, c] = await Promise.all([api("GET", "repo/bot?path=" + encodeURIComponent(b.path) + "&version=" + v1), api("GET", "repo/bot?path=" + encodeURIComponent(b.path) + "&version=" + v2)]);
    const x = JSON.stringify(a.flow, null, 2).split("\n"), y = JSON.stringify(c.flow, null, 2).split("\n");
    const n = x.length, m = y.length;
    if (n * m > 4000000) { toast("These versions are too large to compare here.", true); return; }
    const L = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = x[i] === y[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
    const out = [];
    let i = 0, j = 0, added = 0, removed = 0;
    while (i < n || j < m) {
      if (i < n && j < m && x[i] === y[j]) { out.push([" ", x[i]]); i++; j++; }
      else if (j < m && (i >= n || L[i][j + 1] >= L[i + 1][j])) { out.push(["+", y[j]]); j++; added++; }
      else { out.push(["-", x[i]]); i++; removed++; }
    }
    // Show changes with three lines of context.
    const keep = out.map((l, k) => l[0] !== " " || out.slice(Math.max(0, k - 3), k + 4).some((z) => z[0] !== " "));
    const lines = [];
    out.forEach((l, k) => { if (keep[k]) lines.push(h("div", { class: "d" + (l[0] === "+" ? " add" : l[0] === "-" ? " del" : "") }, l[0] + " " + l[1])); else if (keep[k - 1]) lines.push(h("div", { class: "d gap", text: "\u22EF" })); });
    modal("v" + v1 + " \u2192 v" + v2 + " of " + botName(b.path), [h("p", { class: "muted", text: added + " line(s) added, " + removed + " removed." }), h("div", { class: "diff" }, lines.length ? lines : [h("div", { text: "The versions are identical." })])], []);
  } catch (e) { toast(e.message, true); }
}

async function runDialog(b, deviceId) {
  if (!S.devices.length || !S.bots.length) await loadRefs();
  const dev = deviceSelect(deviceId);
  const bot = b ? null : botSelect();
  const inputsBox = h("div", { class: "inputs-box" });
  const raw = h("textarea", { placeholder: '{"vendor": "Acme", "api_key": {"$credential": "finance/vendor_api/key"}}' });
  let fields = [];
  async function loadInputs(path) {
    fields = [];
    inputsBox.replaceChildren(h("p", { class: "muted", text: "Loading inputs..." }));
    try {
      const flow = (await api("GET", "repo/bot?path=" + encodeURIComponent(path) + "&version=production")).flow;
      const ins = (flow.variables || []).filter((v) => v.direction === "input");
      fields = ins.map((v) => {
        const el = v.type === "Boolean" ? h("select", {}, h("option", { value: "", text: "(not set)" }), h("option", { value: "true", text: "True" }), h("option", { value: "false", text: "False" }))
          : Array.isArray(v.enum) && v.enum.length ? h("select", {}, h("option", { value: "", text: "(not set)" }), v.enum.map((o) => h("option", { value: JSON.stringify(o), text: String(o) })))
            : ["List", "Dictionary", "Table", "Record", "Any"].includes(v.type) ? h("textarea", { rows: 2, placeholder: "JSON" + (v.default !== undefined ? ", default " + JSON.stringify(v.default) : "") })
              : h("input", { type: v.type === "Number" ? "number" : v.type === "DateTime" ? "datetime-local" : "text", placeholder: v.type === "Credential" ? "locker/credential/attribute" : v.default !== undefined ? "default: " + JSON.stringify(v.default) : "" });
        return { v, el };
      });
      inputsBox.replaceChildren(...(fields.length ? fields.map(({ v, el }) => h("label", {}, h("span", {}, v.name, v.required ? h("span", { class: "req", text: " *" }) : null, h("span", { class: "type", text: v.type })), el,
        v.description ? h("span", { class: "muted small", text: v.description }) : null)) : [h("p", { class: "muted", text: "This bot has no inputs." })]));
    } catch (e) { inputsBox.replaceChildren(h("p", { class: "muted", text: "Inputs could not be loaded; give them as JSON below." })); }
  }
  const collect = () => {
    const out = parseJson(raw.value, {});
    for (const { v, el } of fields) {
      const t = el.value.trim();
      if (!t) continue;
      if (v.type === "Credential") out[v.name] = { $credential: t };
      else if (el.tagName === "SELECT") out[v.name] = v.type === "Boolean" ? t === "true" : JSON.parse(t);
      else if (v.type === "Number") out[v.name] = Number(t);
      else if (["List", "Dictionary", "Table", "Record", "Any"].includes(v.type)) out[v.name] = parseJson(t, undefined);
      else if (v.type === "DateTime") out[v.name] = t.length === 16 ? t + ":00" : t;
      else out[v.name] = t;
    }
    return out;
  };
  const path = () => (b ? b.path : bot.value);
  if (bot) bot.addEventListener("change", () => loadInputs(bot.value));
  modal("Run " + (b ? botName(b.path) : "a bot"), [bot ? h("label", {}, "Bot (production version)", bot) : null, h("label", {}, "Device", dev), inputsBox,
    h("details", {}, h("summary", { text: "More inputs as JSON (optional)" }), raw),
    h("p", { class: "muted", text: "Credential inputs are vault references, never the secret itself." })].filter(Boolean),
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const r = await api("POST", "runs", { bot_path: path(), device_id: dev.value, inputs: collect() }); closeModal(); toast("Queued " + r.run_id); S.activity = Object.assign({}, S.activity, { tab: "active" }); await go("activity"); openRun(r.run_id); }
    catch (e) { toast(e.message, true); } } }, "Run now")]);
  if (path()) loadInputs(path());
}

function scheduleDialog() {
  const name = h("input", { placeholder: "Nightly invoice run" }), bot = botSelect(), dev = deviceSelect();
  const kind = h("select", {}, h("option", { value: "every", text: "Every N minutes" }), h("option", { value: "daily", text: "Daily" }), h("option", { value: "weekly", text: "Weekly" }));
  const every = h("input", { type: "number", min: "1", value: "60" }), at = h("input", { placeholder: "08:30", value: "08:30" });
  const days = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"].map((d) => h("label", { class: "check" }, h("input", { type: "checkbox", value: d, checked: !["SAT", "SUN"].includes(d) }), d));
  const tz = h("input", { value: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC" }), inputs = h("textarea", { placeholder: "{}" });
  const everyBox = h("label", {}, "Minutes", every), atBox = h("label", {}, "Time", at), daysBox = h("div", { class: "toolbar" }, days);
  const sync = () => { everyBox.hidden = kind.value !== "every"; atBox.hidden = kind.value === "every"; daysBox.hidden = kind.value !== "weekly"; };
  kind.addEventListener("change", sync); sync();
  modal("New schedule", [h("label", {}, "Name", name), h("label", {}, "Bot (production version)", bot), h("label", {}, "Device", dev), h("label", {}, "Repeat", kind), everyBox, atBox, daysBox,
    h("label", {}, "Time zone", tz), h("label", {}, "Inputs (JSON, optional)", inputs)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const body = { name: name.value || "Schedule", bot_path: bot.value, device_id: dev.value, kind: kind.value, time_zone: tz.value, inputs: parseJson(inputs.value, {}) };
        if (kind.value === "every") body.every_minutes = parseInt(every.value, 10); else body.at_time = at.value;
        if (kind.value === "weekly") body.days = days.map((l) => l.firstChild).filter((c) => c.checked).map((c) => c.value).join(",");
        const r = await api("POST", "schedules", body); closeModal(); toast("Next run " + local(r.next_run)); go("schedules");
      } catch (e) { toast(e.message, true); } } }, "Create")]);
}

function triggerDialog() {
  const name = h("input", { placeholder: "New invoices" }), bot = botSelect(), dev = deviceSelect();
  const kind = h("select", {}, h("option", { value: "file", text: "File arrives in a folder (on the device)" }), h("option", { value: "webhook", text: "Webhook (HTTP call)" }));
  const folder = h("input", { placeholder: "C:\\Invoices\\Inbox" }), pattern = h("input", { value: "*.pdf" });
  const fileBox = h("div", { class: "grid2" }, h("label", {}, "Folder on the device", folder), h("label", {}, "File pattern", pattern));
  kind.addEventListener("change", () => { fileBox.hidden = kind.value !== "file"; });
  modal("New trigger", [h("label", {}, "Name", name), h("label", {}, "Bot (production version)", bot), h("label", {}, "Device", dev), h("label", {}, "Event", kind), fileBox,
    h("p", { class: "muted", text: "The bot receives the file path in an input named trigger_file, or the webhook body in an input named payload, when it declares them." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const r = await api("POST", "triggers", { name: name.value || "Trigger", bot_path: bot.value, device_id: dev.value, kind: kind.value, folder: folder.value, pattern: pattern.value });
        if (r.secret) modal("Webhook created", [secretBox("URL", location.origin + r.url), secretBox("Secret (send it in the X-OOA-Secret header)", r.secret, "Shown only once.")], []);
        else { closeModal(); toast("Trigger created"); }
        go("triggers");
      } catch (e) { toast(e.message, true); } } }, "Create")]);
}

function lockerDialog() {
  const name = h("input", { placeholder: "finance" }), desc = h("input", {}), cons = h("input", { placeholder: "role:bot_runner, user:jane" });
  modal("New locker", [h("label", {}, "Name", name), h("label", {}, "Description", desc), h("label", {}, "Consumers (who may use it in bots)", cons),
    h("p", { class: "muted", text: "You become the owner. Owners manage credentials; consumers' bots may use them." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("POST", "lockers", { name: name.value, description: desc.value, consumers: cons.value }); closeModal(); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Create")]);
}

function credentialDialog(locker) {
  const name = h("input", { placeholder: "vendor_api" }), attr = h("input", { value: "password" }), val = h("input", { type: "password", autocomplete: "new-password" });
  modal("Credential in " + locker, [h("label", {}, "Credential", name), h("label", {}, "Attribute", attr), h("label", {}, "Value", val),
    h("p", { class: "muted", text: "Bots refer to it as " + locker + "/credential/attribute." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PUT", "lockers/" + locker + "/" + name.value + "/" + attr.value, { value: val.value }); val.value = ""; closeModal(); toast("Saved and encrypted"); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function consumersDialog(l) {
  const cons = h("input", { value: l.consumers || "" }), owners = h("input", { value: l.owners || "" });
  modal("Access to " + l.name, [h("label", {}, "Consumers", cons), h("label", {}, "Owners (user names)", owners)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PATCH", "lockers/" + l.name, { consumers: cons.value, owners: owners.value }); closeModal(); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function globalDialog(g) {
  const name = h("input", { value: g ? g.name : "", placeholder: "reports_folder" }), val = h("textarea", { value: g ? JSON.stringify(g.value) : "", placeholder: '"D:/Reports"  or  1000  or  {"a": 1}' });
  if (g) name.disabled = true;
  modal(g ? "Edit " + g.name : "New global value", [h("label", {}, "Name", name), h("label", {}, "Value (JSON)", val)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PUT", "globals/" + name.value, { value: parseJson(val.value, "") }); closeModal(); go("globals"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function userDialog() {
  const user = h("input", { placeholder: "jane.doe" }), disp = h("input", { placeholder: "Jane Doe" });
  const role = h("select", {}, ["bot_creator", "bot_runner", "viewer", "admin"].map((x) => h("option", { value: x, text: x.replace("_", " ") })));
  modal("New user", [h("label", {}, "User name", user), h("label", {}, "Display name", disp), h("label", {}, "Role", role)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { const r = await api("POST", "users", { username: user.value, display_name: disp.value, role: role.value });
        modal("User created", [secretBox(r.username, r.temporary_password, "Temporary password: give it to the user securely. They must change it at first sign-in. Shown only once.")], []); go("users"); }
      catch (e) { toast(e.message, true); } } }, "Create")]);
}

// ---------- startup ----------
async function boot() {
  $("login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("login-error").hidden = true;
    try {
      const r = await api("POST", "auth/login", { username: $("login-user").value, password: $("login-pass").value });
      $("login-pass").value = "";
      S.user = r.user;
      if (S.user.must_change) showPassword(); else showApp();
    } catch (err) { $("login-error").textContent = err.message; $("login-error").hidden = false; }
  });
  $("password-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("pw-error").hidden = true;
    if ($("pw-new").value !== $("pw-repeat").value) { $("pw-error").textContent = "The new passwords do not match."; $("pw-error").hidden = false; return; }
    try {
      await api("POST", "auth/password", { current: $("pw-current").value, new: $("pw-new").value });
      for (const id of ["pw-current", "pw-new", "pw-repeat"]) $(id).value = "";
      S.user = await api("GET", "auth/me"); toast("Password changed"); showApp();
    } catch (err) { $("pw-error").textContent = err.message; $("pw-error").hidden = false; }
  });
  $("btn-logout").addEventListener("click", async () => { try { await api("POST", "auth/logout"); } catch (_) { /* already signed out */ } S.user = null; showLogin(); });
  $("btn-change-pw").addEventListener("click", showPassword);
  $("modal").addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });
  $("drawer-close").addEventListener("click", closeDrawer);
  $("drawer").addEventListener("click", (e) => { if (e.target === $("drawer")) closeDrawer(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && $("modal").hidden && !$("drawer").hidden) closeDrawer(); });
  try { S.user = await api("GET", "auth/me"); if (S.user.must_change) showPassword(); else showApp(); } catch (_) { showLogin(); }
}

if (typeof window !== "undefined" && !window.__OOA_TEST__) boot();
