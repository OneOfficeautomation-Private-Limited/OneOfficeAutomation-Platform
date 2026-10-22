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
  shieldai: "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 12l2 2 4-4", tasks: "M9 6h11M9 12h11M9 18h11M4 6l1 1 2-2M4 12l1 1 2-2M4 18l1 1 2-2",
  form: "M5 3h14v18H5zM8 7h8M8 11h3M13 11h3M8 15h8", process: "M4 4h6v6H4zM14 14h6v6h-6zM7 10v5h7",
  gear: "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9L7 7M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1",
  key: "M7 14a4 4 0 1 1 3.5-6H21v3h-2v3h-3v-3h-5.5A4 4 0 0 1 7 14z", calendar: "M4 6h16v14H4zM4 10h16M8 3v4M16 3v4", inbox: "M3 13l3-8h12l3 8v6H3zM3 13h5l1 3h6l1-3h5",
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
const botName = (p) => String(p || "").replace(/\.(flow|process|form)\.json$/, "");
const isAgent = (p) => /\.flow\.json$/.test(String(p || ""));
const kindOf = (p) => /\.process\.json$/.test(p) ? ["Workflow", "process", "#7c3aed"] : /\.form\.json$/.test(p) ? ["Form", "form", "#0891b2"] : ["Agent", "bot", "#6d28d9"];
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
/** Who started a run, and the run-as user it ran as when that is someone else. */
function startedBy(r) { return (r.requested_by || "") + (r.run_as && r.run_as !== r.requested_by ? " as " + r.run_as : ""); }
function runStatus(r) { return r.status === "running" && r.pause_requested ? "paused" : r.status === "running" && r.cancel_requested ? "stopping" : r.status; }
/** Display names of the built-in roles (ids stay admin, bot_creator, bot_runner, viewer). */
const ROLE_LABELS = { admin: "Administrator", bot_creator: "Agent builder", bot_runner: "Agent operator", viewer: "Viewer" };
const roleLabel = (r) => ROLE_LABELS[r] || String(r || "").replace("_", " ");
const pill = (s) => h("span", { class: "pill " + s, text: s });
function sourceLabel(src) {
  const s = String(src || "");
  if (s.startsWith("schedule:")) return "Schedule";
  if (s.startsWith("trigger:") || s.startsWith("webhook") || s.startsWith("file")) return "Trigger";
  if (s === "mcp") return "AI assistant (MCP)";
  if (s.startsWith("queue:")) return "Work queue";
  if (s === "api") return "API";
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
function showLogin() {
  stopTimer(); closeDrawer(); $("app").hidden = true; $("password-view").hidden = true; $("login-view").hidden = false; $("login-user").focus();
  api("GET", "auth/sso").then((r) => {
    const box = $("sso-box");
    if (!box) return;
    box.replaceChildren(...(r.enabled ? [h("div", { class: "sso-or", text: "or" }), h("a", { class: "button-link sso-button", href: "/api/auth/sso/start" }, ic("key", "#2563eb", "sm"), r.label)] : []));
  }).catch(() => {});
}
function showPassword() { $("app").hidden = true; $("login-view").hidden = true; $("password-view").hidden = false; $("pw-current").focus(); }
function showApp() {
  $("login-view").hidden = true; $("password-view").hidden = true; $("app").hidden = false;
  $("who").textContent = S.user.display_name + " (" + roleLabel(S.user.role) + ")";
  $("avatar").textContent = (S.user.display_name || S.user.username || "?").trim().charAt(0).toUpperCase();
  api("GET", "health").then((r) => { $("btn-studio").hidden = !r.studio; S.studioAvailable = !!r.studio; renderNav(); }).catch(() => {});
  api("GET", "catalog").then((r) => { S.names = r.actions || {}; }).catch(() => {});
  api("GET", "auth/me").then((u) => showAnnouncement(u.announcement)).catch(() => {});
  renderNav(); go(S.page);
}

const PAGES = [
  ["home", "Home", null, "home", "#2563eb"], ["inbox", "Task inbox", null, "tasks", "#0d9488"], ["automation", "Agent repository", "repo.view", "bot", "#6d28d9"], ["activity", "Agent Runs", "runs.view", "pulse", "#ea580c"],
  ["devices", "Runners", "devices.view", "desktop", "#0891b2"], ["schedules", "Schedules", "schedules.view", "clock", "#059669"], ["triggers", "Triggers", "triggers.view", "bolt", "#d97706"],
  ["queues", "Work queues", "queues.view", "inbox", "#9333ea"], ["workflows", "One Workflow", "runs.view", "process", "#7c3aed"],
  ["credentials", "One Agent Vault", null, "lock", "#dc2626"], ["globals", "Shared values", "globals.view", "globe", "#0284c7"], ["users", "Users", "users.manage", "users", "#7c3aed"],
  ["audit", "One Audit Trail", "audit.view", "shield", "#475569"], ["governance", "AI governance", "audit.view", "shieldai", "#be185d"],
  ["roles", "Roles", "users.manage", "users", "#6d28d9"], ["toolkits", "Toolkits", "toolkits.manage", "bolt", "#0d9488"],
  ["connections", "One Model Connections", "connections.manage", "shieldai", "#7c3aed"], ["settings", "Settings", "settings.manage", "gear", "#334155"],
  ["admin", "Administration", "settings.manage", "gear", "#334155"]];

/** The menu: Home, then three groups. Pages not listed here (admin) are reachable from inside other pages only. */
const NAV_GROUPS = [
  ["build", "Build", ["automation", "studio", "workflows", "queues"]],
  ["run", "Run & Monitor", ["activity", "inbox", "schedules", "triggers", "devices"]],
  ["govern", "Govern", ["users", "roles", "credentials", "globals", "toolkits", "connections", "governance", "audit", "settings"]],
];
const NAV_COLLAPSED_KEY = "ooa.cc.navCollapsed";
function navCollapsed() { try { return JSON.parse(localStorage.getItem(NAV_COLLAPSED_KEY) || "[]"); } catch (_) { return []; } }

function navButton(id) {
  if (id === "studio") {
    if (!S.studioAvailable) return null;
    const a = h("a", { class: "nav-link", href: "/studio/", target: "ooa-studio", "aria-label": "One Agent Studio", title: "Build and test agents in One Agent Studio (opens in a new tab)" },
      ic("code", "#2563eb"), "One Agent Studio", h("span", { class: "nav-ext", "aria-hidden": "true", text: "\u2197" }));
    a.style.setProperty("--c", "#2563eb");
    return a;
  }
  const page = PAGES.find((x) => x[0] === id);
  if (!page || (page[2] && !can(page[2]))) return null;
  const [, label, , icon, color] = page;
  const b = h("button", { type: "button", class: S.page === id ? "active" : "", "aria-current": S.page === id ? "page" : null, "aria-label": label, onclick: () => go(id) }, ic(icon, color), label);
  b.style.setProperty("--c", color);
  return b;
}

function renderNav() {
  const collapsed = navCollapsed();
  const parts = [navButton("home")];
  for (const [gid, glabel, ids] of NAV_GROUPS) {
    const items = ids.map(navButton).filter(Boolean);
    if (!items.length) continue;
    const isCollapsed = collapsed.includes(gid) && !ids.includes(S.page);
    const head = h("button", { type: "button", class: "nav-group" + (isCollapsed ? " collapsed" : ""), "aria-expanded": String(!isCollapsed), "data-group": gid,
      onclick: () => {
        const c = navCollapsed();
        const next = c.includes(gid) ? c.filter((x) => x !== gid) : c.concat(gid);
        try { localStorage.setItem(NAV_COLLAPSED_KEY, JSON.stringify(next)); } catch (_) { /* storage unavailable */ }
        renderNav();
      } }, h("span", { class: "nav-caret", "aria-hidden": "true", text: isCollapsed ? "\u25B8" : "\u25BE" }), glabel);
    parts.push(h("div", { class: "nav-section", role: "group", "aria-label": glabel }, head, isCollapsed ? null : h("div", { class: "nav-items" }, items)));
  }
  $("nav").replaceChildren(...parts.filter(Boolean));
}

/** The administrator's announcement, shown to everyone at the top of the Command Center (dismissable per browser session). */
function showAnnouncement(text) {
  const bar = $("announcement");
  if (!bar) return;
  let dismissed = null;
  try { dismissed = sessionStorage.getItem("ooa.cc.announcement"); } catch (_) { /* storage unavailable */ }
  if (!text || dismissed === text) { bar.hidden = true; return; }
  bar.replaceChildren(ic("bolt", "#d97706", "sm"), h("span", { class: "ann-text", text }),
    h("button", { type: "button", class: "icon-close", "aria-label": "Dismiss announcement", onclick: () => { bar.hidden = true; try { sessionStorage.setItem("ooa.cc.announcement", text); } catch (_) { /* ignore */ } } }, "\u2715"));
  bar.hidden = false;
}

/** Tab strip; items are [id, label]. */
function tabStrip(items, current, onPick) {
  return h("div", { class: "tabs", role: "tablist" }, items.map(([id, label]) => h("button", { type: "button", role: "tab", class: "tab" + (current === id ? " active" : ""),
    "aria-selected": String(current === id), onclick: () => onPick(id) }, label)));
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
const botSelect = (val) => { const s = h("select", {}, S.bots.filter((b) => b.production_version && isAgent(b.path)).map((b) => h("option", { value: b.path, text: botName(b.path) }))); if (val) s.value = val; return s; };
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
    const parts = [...header("Home", "Welcome, " + S.user.display_name + ". Here is what your agents have been doing."),
      h("div", { class: "tiles" },
        tile(d.running, "Running now", "pulse", "#ea580c", can("runs.view") ? () => go("activity") : null),
        tile(d.queued, "Waiting to start", "queue", "#d97706", can("runs.view") ? () => go("activity") : null),
        tile(d.success_24h, "Succeeded (24 h)", "check", "#16a34a"), tile(d.failed_24h, "Failed (24 h)", "x", "#dc2626"),
        tile(d.success_rate_24h === null || d.success_rate_24h === undefined ? "\u2014" : d.success_rate_24h + "%", "Success rate (24 h)", "percent", "#059669"),
        tile(d.avg_duration_ms_24h ? dur(d.avg_duration_ms_24h) : "\u2014", "Average run time", "timer", "#0891b2"),
        tile(d.devices_online + " / " + d.devices_total, "Runners online", "desktop", "#0284c7", can("devices.view") ? () => go("devices") : null),
        tile(d.bots, "Agents in the agent repository", "bot", "#6d28d9", can("repo.view") ? () => go("automation") : null),
        tile(d.schedules, "Active schedules", "clock", "#7c3aed", can("schedules.view") ? () => go("schedules") : null),
        d.queue_items_waiting !== undefined ? tile(d.queue_items_waiting, "Work items waiting", "inbox", "#9333ea", () => go("queues")) : null,
        tile((d.hours_saved_30d || 0) + " h", "Hours saved (30 days)", "timer", "#16a34a"),
        tile(d.open_tasks || 0, "Tasks waiting for you", "tasks", "#0d9488", () => go("inbox")))];
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
          h("div", { class: "card" }, h("h2", { text: "Most used agents (7 days)" }), top.length ? top : h("p", { class: "muted", text: "No runs yet." })),
          can("devices.view") ? h("div", { class: "card", id: "home-devices" }, h("h2", { text: "Runners" }), h("p", { class: "muted", text: "Loading..." })) : null));
      if (can("devices.view")) fillDevices = () => api("GET", "devices").then((r) => {
        const box = $("home-devices");
        if (box) box.replaceChildren(h("h2", { text: "Runners" }), ...(r.devices.length ? r.devices.map((dv) => h("button", { type: "button", class: "list-row", onclick: () => openDevice(dv) },
          h("span", { class: "dot " + dv.status }), h("div", { class: "lr-main" }, h("strong", { text: dv.name }), h("div", { class: "muted small", text: dv.status + (dv.running ? ", running an agent" : "") + " \u00B7 last seen " + ago(dv.last_seen) })))) : [h("p", { class: "muted", text: "No runners yet." })]));
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
    S.repoFolder = S.repoFolder || "";
    const [r, f] = await Promise.all([api("GET", "repo" + (S.repoFolder ? "?folder=" + encodeURIComponent(S.repoFolder) : "")), api("GET", "repo/folders")]);
    S.folders = f.folders;
    const folderSel = h("select", { "aria-label": "Folder", onchange: (e) => { S.repoFolder = e.target.value; go("automation"); } },
      h("option", { value: "", text: "All folders" }), f.folders.map((x) => h("option", { value: x.path, text: "\u00A0".repeat(2 * (x.path.split("/").length - 1)) + x.path.split("/").pop()
        + " (" + x.agents + ")" + (x.has_permissions ? " \uD83D\uDD12" : "") })));
    folderSel.value = S.repoFolder;
    const cur = f.folders.find((x) => x.path === S.repoFolder);
    // agents in the folder and below: only an empty folder can be deleted
    const inside = cur ? f.folders.filter((x) => x.path === cur.path || x.path.startsWith(cur.path + "/")).reduce((n, x) => n + x.agents, 0) : 0;
    const tools = [folderSel,
      S.repoFolder && can("repo.permissions") ? h("button", { type: "button", onclick: () => folderPermissionsDialog(S.repoFolder) }, ic("lock", "#dc2626", "sm"), "Folder permissions") : null,
      S.repoFolder && can("repo.delete") && cur && inside === 0 ? h("button", { type: "button", class: "danger", onclick: async () => {
        if (!confirm("Delete the empty folder " + S.repoFolder + "?")) return;
        try { await api("DELETE", "repo/folders?path=" + encodeURIComponent(S.repoFolder)); S.repoFolder = ""; go("automation"); } catch (e) { toast(e.message, true); } } }, "Delete folder") : null].filter(Boolean);
    main.replaceChildren(...header("Agent repository", "The public agent repository: agents checked in from One Agent Studio, with every version. Run the live production version on any runner.",
      can("repo.checkin") ? h("button", { type: "button", onclick: () => newFolderDialog() }, "New folder") : null,
      can("lifecycle.export") ? h("button", { type: "button", onclick: () => exportDialog(r.bots) }, ic("download", "#475569", "sm"), "Export / promote") : null,
      can("lifecycle.import") ? h("button", { type: "button", onclick: () => importDialog() }, "Import") : null,
      can("repo.delete") ? h("button", { type: "button", onclick: () => deletedDialog() }, "Deleted agents") : null),
      dataTable({ rows: r.bots, search: "Search agents by name, id or description", exportName: "bots", onRow: openBot, empty: "No agents yet. In Agent Studio, open an agent and press Agent check-in.", tools,
        columns: [
          { label: "Agent", value: (b) => botName(b.path), render: (b) => { const k = kindOf(b.path); return h("div", { class: "name-cell" }, ic(k[1], k[2], "sm"), h("div", {}, h("strong", { text: botName(b.path) }),
            k[0] !== "Agent" ? h("span", { class: "tag", text: k[0] }) : null, h("div", { class: "muted small", text: b.description || "" }))); } },
          { label: "Folder", value: (b) => b.folder || "", render: (b) => b.folder ? h("span", { class: "muted", text: b.folder }) : h("span", { class: "muted", text: "(root)" }) },
          { label: "Latest", value: (b) => b.latest_version, render: (b) => h("span", {}, "v" + b.latest_version, ...(b.labels || []).map((l) => h("span", { class: "chip", title: "Label on v" + l.version, text: l.label }))) },
          { label: "Live production", value: (b) => b.production_version || 0, render: (b) => b.production_version ? h("span", { class: "pill success", text: "v" + b.production_version }) : "\u2014" },
          { label: "Status", value: (b) => b.locked_by ? "Checked out by " + b.locked_by : "Checked in", render: (b) => b.locked_by ? h("span", { class: "pill locked", text: "Checked out by " + b.locked_by }) : h("span", { class: "pill", text: "Checked in" }) },
          { label: "Updated", value: (b) => b.updated, render: (b) => h("span", { title: local(b.updated) }, ago(b.updated) + " by " + (b.updated_by || "")) },
          { label: "", sort: false, render: (b) => h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: () => versionsDialog(b) }, "Versions"),
            can("runs.start") && b.production_version && isAgent(b.path) ? h("button", { type: "button", class: "primary", onclick: () => runDialog(b) }, "Run") : null,
            can("runs.start") && b.production_version && /\.process\.json$/.test(b.path) ? h("button", { type: "button", class: "primary", onclick: () => startWorkflowDialog(b.path) }, "Start") : null,
            b.locked_by && (b.locked_by === S.user.username || can("repo.production")) ? h("button", { type: "button", onclick: async () => {
              try { await api("POST", "repo/undo-checkout", { path: b.path }); toast("Agent check-out undone"); go("automation"); } catch (err) { toast(err.message, true); } } }, "Undo check-out") : null,
            can("repo.delete") && ["edit", "manage"].includes(b.access) ? h("button", { type: "button", class: "danger", onclick: () => deleteBotAsk(b) }, "Delete") : null) }]}));
  },

  async activity(main) {
    await loadRefs().catch(() => {});
    const st = S.activity = Object.assign({ tab: "active", offset: 0, size: 25, f: {} }, S.activity || {});
    const tabs = h("div", { class: "tabs", role: "tablist" });
    const filters = h("div", { class: "filters" });
    const list = h("div");
    const renderTabs = (counts) => tabs.replaceChildren(...[["active", "Live"], ["history", "History"]].map(([t, l]) =>
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
      const q = h("input", { type: "search", placeholder: "Search agent, run id, user or error", value: st.f.q || "", "aria-label": "Search runs" });
      q.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(() => { st.f.q = q.value.trim(); st.offset = 0; refresh(); }, 300); });
      const date = (key, label) => { const i = h("input", { type: "date", value: st.f[key] || "", "aria-label": label, title: label, onchange: () => { st.f[key] = i.value; st.offset = 0; refresh(); } }); return h("label", { class: "inline" }, label, i); };
      filters.replaceChildren(q,
        sel("status", "Any status", [["success", "Succeeded"], ["failed", "Failed"], ["stopped", "Stopped"]]),
        sel("bot", "Any agent", S.bots.map((b) => [b.path, botName(b.path)])),
        sel("device", "Any runner", S.devices.map((d) => [d.id, d.name])),
        sel("source", "Any source", [["manual", "Manual"], ["schedule", "Schedule"], ["trigger", "Trigger"], ["rerun", "Rerun"]]),
        h("input", { type: "text", placeholder: "Started by (user name)", value: st.f.user || "", "aria-label": "Started by", onchange: (e) => { st.f.user = e.target.value.trim(); st.offset = 0; refresh(); } }),
        date("from", "From"), date("to", "To"),
        h("button", { type: "button", class: "link", onclick: () => { st.f = {}; st.offset = 0; renderFilters(); refresh(); } }, "Clear filters"),
        h("span", { class: "spacer" }),
        h("button", { type: "button", onclick: async () => {
          try {
            const r = await api("GET", query(500, 0));
            download("runs.csv", csv([["Run id", "Status", "Agent", "Version", "Runner", "Started by", "Source", "Queued", "Started", "Finished", "Duration ms", "Steps", "Error code", "Error"],
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
        h("div", { class: "lc-meta muted" }, [["Runner", run.device_name || run.device_id], ["Started by", startedBy(run) + " (" + sourceLabel(run.source) + ")"],
          ["Queued", ago(run.created)], ["Running for", elapsed !== null ? dur(elapsed) : "\u2014"]].map(([k, v]) => h("span", {}, k + ": ", h("b", { text: v })))));
      return card;
    }
    function historyTable(runs) {
      return h("div", { class: "table-wrap" }, h("table", {},
        h("thead", {}, h("tr", {}, ["Status", "Agent", "Runner", "Started by", "Source", "Started", "Duration", "Steps", "Error", ""].map((t) => h("th", { text: t })))),
        h("tbody", {}, runs.length ? runs.map((x) => {
          const e = parseMaybe(x.error);
          const tr = h("tr", { class: "clickable", tabindex: "0" },
            h("td", {}, pill(x.status)), h("td", {}, h("strong", { text: botName(x.bot_path) }), h("span", { class: "muted", text: " v" + x.version })),
            h("td", { text: x.device_name || x.device_id }), h("td", { text: startedBy(x) }), h("td", { text: sourceLabel(x.source) }),
            h("td", { title: local(x.started || x.created), text: ago(x.started || x.created) }), h("td", { text: dur(x.duration_ms) }), h("td", { text: x.steps ?? "" }),
            h("td", { class: "err-cell", title: e && e.message ? e.message : "", text: e ? (e.code || (typeof e === "string" ? e : "")) : "" }),
            h("td", {}, can("runs.start") ? h("button", { type: "button", onclick: () => rerun(x.id) }, "Rerun") : null));
          tr.addEventListener("click", (ev) => { if (!ev.target.closest("button")) openRun(x.id); });
          tr.addEventListener("keydown", (ev) => { if (ev.key === "Enter" && ev.target === tr) openRun(x.id); });
          return tr;
        }) : [h("tr", {}, h("td", { colspan: "10", class: "muted empty-cell", text: "No runs match these filters." }))])));
    }
    renderTabs(); renderFilters();
    main.replaceChildren(...header("Agent Runs", "Runs on all runners: what is running now, and the history of every run. Select a run for its live step log, inputs, outputs and errors."), tabs, filters, list);
    await refresh();
    if (main.isConnected) S.timer = setInterval(() => { if (st.tab === "active" || !st.offset) refresh().catch(() => {}); }, 2500);
  },

  async devices(main) {
    S.devTab = S.devTab || "runners";
    if (S.devTab === "pools") return VIEWS.pools(main);
    const r = await api("GET", "devices");
    S.devices = r.devices;
    const actions = can("devices.manage") ? [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const k = await api("POST", "devices/registration-key", { hours: 24 });
        modal("Register a runner", [
          h("p", { text: "On the Windows machine that will run agents, open PowerShell in the platform folder and run:" }),
          secretBox("Command to run", '.\\gradlew.bat :cli:run --args="agent register --url ' + location.origin + " --key " + k.key + '"', "The key works once and expires in 24 hours. It is shown only now."),
          h("p", { class: "muted", text: "Then start the agent with:  .\\gradlew.bat :cli:run --args=\"agent start\"" }),
          h("p", { class: "muted", text: "On this computer you do not need this: ooa server (or start-ooa.bat) registers and starts a One Runner for this computer automatically." })], []);
      } catch (e) { toast(e.message, true); }
    } }, "Register a runner")] : [];
    const online = r.devices.filter((d) => d.status === "online").length;
    main.replaceChildren(...header("Runners", "Machines running the One Runner. A runner is online when it checked in during the last minute. " + online + " of " + r.devices.length + " online.", ...actions),
      tabStrip([["runners", "Runners"], ["pools", "Runner pools"]], "runners", (t) => { S.devTab = t; go("devices"); }),
      dataTable({ rows: r.devices, search: "Search runners", exportName: "devices", onRow: openDevice, empty: "No runners yet. Register one, or start ooa server on this computer.",
        columns: [
          { label: "Runner", value: (d) => d.name, render: (d) => h("div", { class: "name-cell" }, h("span", { class: "dot " + d.status }), h("div", {}, h("strong", { text: d.name }), h("div", { class: "muted small", text: (d.hostname || "") + " " + (d.os || "") }))) },
          { label: "Status", value: (d) => d.status, render: (d) => pill(d.status) },
          { label: "Running", value: (d) => d.running, render: (d) => d.running ? h("span", { class: "pill running", text: d.running + " agent" }) : "\u2014" },
          { label: "Agent", value: (d) => d.agent_version || "" },
          { label: "Last seen", value: (d) => d.last_seen || "", render: (d) => h("span", { title: local(d.last_seen) }, ago(d.last_seen)) },
          { label: "Registered", value: (d) => d.registered, render: (d) => ago(d.registered) + " by " + (d.registered_by || "") },
          { label: "", sort: false, render: (d) => can("devices.manage") ? h("button", { type: "button", onclick: async () => { try { await api("PATCH", "devices/" + d.id, { enabled: !d.enabled }); go("devices"); } catch (e) { toast(e.message, true); } } }, d.enabled ? "Disable" : "Enable") : "" }]}));
    if (main.isConnected) S.timer = setInterval(() => { if (S.page === "devices" && S.devTab === "runners" && $("modal").hidden && $("drawer").hidden) go("devices"); }, 15000);
  },

  async pools(main) {
    const [r, d] = await Promise.all([api("GET", "pools"), api("GET", "devices")]);
    S.devices = d.devices; S.pools = r.pools;
    main.replaceChildren(...header("Runners", "Runner pools share work between several runners: a run sent to a pool goes to its least busy online runner.",
      can("pools.manage") ? h("button", { type: "button", class: "primary", onclick: () => poolDialog() }, "New runner pool") : null),
      tabStrip([["runners", "Runners"], ["pools", "Runner pools"]], "pools", (t) => { S.devTab = t; go("devices"); }),
      dataTable({ rows: r.pools, search: "Search runner pools", exportName: "runner-pools", empty: "No runner pools yet.",
        columns: [
          { label: "Pool", value: (p) => p.name, render: (p) => h("div", { class: "name-cell" }, ic("desktop", "#0891b2", "sm"), h("div", {}, h("strong", { text: p.name }), h("div", { class: "muted small", text: p.description || "" }))) },
          { label: "Runners", value: (p) => p.devices.map(deviceName).join(", "), render: (p) => h("div", { class: "chips" }, p.devices.map((id) => {
            const dv = S.devices.find((x) => x.id === id); return h("span", { class: "chip" }, h("span", { class: "dot " + (dv ? dv.status : "offline") }), dv ? dv.name : id); })) },
          { label: "Online", value: (p) => p.devices.filter((id) => (S.devices.find((x) => x.id === id) || {}).status === "online").length + " of " + p.devices.length },
          { label: "", sort: false, render: (p) => can("pools.manage") ? h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => poolDialog(p) }, "Edit"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete runner pool " + p.name + "?")) { try { await api("DELETE", "pools/" + p.id); go("devices"); } catch (e) { toast(e.message, true); } } } }, "Delete")) : "" }]}));
  },

  async queues(main) {
    await loadRefs();
    const [r, pools] = await Promise.all([api("GET", "queues"), can("devices.view") ? api("GET", "pools") : { pools: [] }]);
    S.pools = pools.pools;
    const waiting = (q) => Number(q.counts.new || 0) + Number(q.counts.retry || 0);
    const done = (q) => Number(q.counts.success || 0) + Number(q.counts.failed || 0);
    try { S.models = (await api("GET", "queue-models")).models; } catch (_) { S.models = []; }
    main.replaceChildren(...header("Work queues", "Lists of work items (invoices, tickets, orders) that agents process one by one on a runner pool, with retries and service levels.",
      h("button", { type: "button", onclick: () => modelsDialog() }, "Work item models"),
      can("queues.manage") ? h("button", { type: "button", class: "primary", onclick: () => queueDialog() }, "New work queue") : null),
      dataTable({ rows: r.queues, search: "Search work queues", exportName: "work-queues", onRow: openQueue, empty: "No work queues yet. Create one, then add items by hand, from a CSV file or from an agent.",
        columns: [
          { label: "Queue", value: (q) => q.name, render: (q) => h("div", { class: "name-cell" }, ic("inbox", "#9333ea", "sm"), h("div", {}, h("strong", { text: q.name }), h("div", { class: "muted small", text: q.description || "" }))) },
          { label: "Agent", value: (q) => q.bot_path ? botName(q.bot_path) : "\u2014" },
          { label: "Pool", value: (q) => (S.pools.find((p) => p.id === q.pool_id) || {}).name || "\u2014" },
          { label: "Waiting", value: waiting, render: (q) => h("strong", { text: String(waiting(q)) }) },
          { label: "In progress", value: (q) => Number(q.counts.in_progress || 0) },
          { label: "Done", value: done, render: (q) => h("div", { class: "q-done" }, h("span", { class: "ok-text", text: String(q.counts.success || 0) }), " / ", h("span", { class: q.counts.failed ? "error" : "muted", text: String(q.counts.failed || 0) + " failed" }),
            done(q) ? progressBar(100 * Number(q.counts.success || 0) / done(q), "ok") : null) },
          { label: "Service level", value: (q) => q.sla_minutes || 0, render: (q) => q.sla_minutes ? h("span", {}, q.sla_minutes + " min", q.overdue ? h("span", { class: "pill failed", text: q.overdue + " overdue" }) : null) : "\u2014" },
          { label: "Processing", value: (q) => q.processing ? "On" : "Off", render: (q) => q.processing ? pill("running") : pill("stopped") },
          { label: "", sort: false, render: (q) => can("queues.work") ? h("button", { type: "button", onclick: async () => {
            try { await api("POST", "queues/" + q.id + (q.processing ? "/stop" : "/start")); go("queues"); } catch (e) { toast(e.message, true); } } }, q.processing ? "Stop" : "Start") : "" }]}));
    if (main.isConnected) S.timer = setInterval(() => { if (S.page === "queues" && $("modal").hidden && $("drawer").hidden) go("queues"); }, 5000);
  },

  async inbox(main) {
    S.inboxTab = S.inboxTab || "open";
    const admin = can("users.manage");
    const allBox = admin ? h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!S.inboxAll, onchange: (e) => { S.inboxAll = e.target.checked; go("inbox"); } }), "Everyone's tasks") : null;
    const r = await api("GET", "tasks?status=" + (S.inboxTab === "open" ? "open" : "completed") + (S.inboxAll ? "&all=true" : ""));
    const kindLabel = { form: "Fill in", approval: "Approve", notification: "Notice" };
    main.replaceChildren(...header("Task inbox", "Work that workflows assign to you or your role: forms to fill in, approvals and notices."),
      h("div", { class: "toolbar" }, tabStrip([["open", "To do"], ["done", "Done"]], S.inboxTab, (t) => { S.inboxTab = t; go("inbox"); }), h("span", { class: "spacer" }), allBox),
      dataTable({ rows: r.tasks, search: "Search tasks", exportName: "tasks", onRow: openTask, empty: S.inboxTab === "open" ? "Nothing waiting for you. \u2728" : "No completed tasks yet.",
        columns: [
          { label: "Task", value: (t) => t.title, render: (t) => h("div", { class: "name-cell" }, ic(t.kind === "approval" ? "shield" : t.kind === "form" ? "form" : "bolt", t.kind === "approval" ? "#d97706" : "#0d9488", "sm"),
            h("div", {}, h("strong", { text: t.title }), h("div", { class: "muted small", text: t.description || "" }))) },
          { label: "Kind", value: (t) => kindLabel[t.kind] || t.kind },
          { label: "Assigned to", value: (t) => t.claimed_by ? t.claimed_by + " (working on it)" : t.assignee },
          { label: "Due", value: (t) => t.due || "", render: (t) => t.due ? h("span", { class: t.overdue ? "pill failed" : "", title: local(t.due), text: (t.overdue ? "Overdue " : "") + ago(t.due) }) : "\u2014" },
          { label: S.inboxTab === "open" ? "Created" : "Outcome", value: (t) => S.inboxTab === "open" ? t.created : t.outcome, render: (t) => S.inboxTab === "open" ? ago(t.created) : pill(t.outcome || t.status) }]}));
    if (main.isConnected) S.timer = setInterval(() => { if (S.page === "inbox" && $("modal").hidden && $("drawer").hidden) go("inbox"); }, 10000);
  },

  async workflows(main) {
    await loadRefs();
    const r = await api("GET", "workflows/runs");
    const defs = S.bots.filter((b) => /\.process\.json$/.test(b.path) && b.production_version);
    const stLabel = { running: "running", waiting: "waiting", completed: "success", failed: "failed", cancelled: "stopped" };
    main.replaceChildren(...header("One Workflow", "Workflows chain agents, people and decisions. Design them in One Agent Studio, check them in, then start them here; people get their steps in the Task inbox.",
      can("runs.start") && defs.length ? h("button", { type: "button", class: "primary", onclick: () => startWorkflowDialog() }, "Start a workflow") : null),
      defs.length ? null : h("p", { class: "muted", text: "No workflows are checked in yet. In One Agent Studio create a Workflow, then press Publish to Command Center." }),
      dataTable({ rows: r.runs, search: "Search workflow runs", exportName: "workflow-runs", onRow: openWorkflowRun, empty: "No workflow runs yet.",
        columns: [
          { label: "Workflow", value: (w) => botName(w.path), render: (w) => h("div", { class: "name-cell" }, ic("process", "#7c3aed", "sm"), h("div", {}, h("strong", { text: botName(w.path) + " v" + w.version }), h("div", { class: "muted small mono", text: w.id }))) },
          { label: "Status", value: (w) => w.status, render: (w) => h("span", { class: "pill " + (stLabel[w.status] || ""), text: w.status }) },
          { label: "Current stage", value: (w) => w.stage || "" },
          { label: "Started by", value: (w) => w.started_by || "" },
          { label: "Started", value: (w) => w.created, render: (w) => h("span", { title: local(w.created) }, ago(w.created)) },
          { label: "Updated", value: (w) => w.updated, render: (w) => ago(w.updated) }]}));
    if (main.isConnected) S.timer = setInterval(() => { if (S.page === "workflows" && $("modal").hidden && $("drawer").hidden) go("workflows"); }, 5000);
  },

  async governance(main) {
    S.govDays = S.govDays || 30;
    const g = await api("GET", "governance?days=" + S.govDays);
    const t = g.totals || {};
    const days = h("select", { "aria-label": "Period", onchange: (e) => { S.govDays = Number(e.target.value); go("governance"); } }, [[7, "Last 7 days"], [30, "Last 30 days"], [90, "Last 90 days"], [365, "Last year"]].map(([v, l]) => h("option", { value: String(v), text: l })));
    days.value = String(S.govDays);
    const tile = (n, l, icon, color) => { const x = h("div", { class: "card tile" }, ic(icon, color), h("div", {}, h("div", { class: "n", text: String(n) }), h("div", { class: "l", text: l }))); x.style.setProperty("--c", color); return x; };
    const n = (v) => Number(v || 0).toLocaleString();
    main.replaceChildren(...header("AI governance", "Every AI model call made by agents: which model, how many tokens, what was masked and which tools the AI used. Prompts are never stored, only a fingerprint."),
      h("div", { class: "toolbar" }, days),
      h("div", { class: "tiles" }, tile(n(t.calls), "AI calls", "shieldai", "#be185d"), tile(n(Number(t.input_tokens || 0) + Number(t.output_tokens || 0)), "Tokens", "pulse", "#7c3aed"),
        tile(n(t.masked_calls), "Calls with personal data masked", "lock", "#dc2626"), tile(n(t.tool_calls), "Calls where the AI used tools", "bolt", "#d97706"),
        tile(t.avg_latency_ms ? dur(Number(t.avg_latency_ms)) : "\u2014", "Average answer time", "timer", "#0891b2")),
      h("div", { class: "grid-2" },
        h("div", { class: "card" }, h("h2", { text: "By model" }), table(["Provider", "Model", "Calls", "Tokens in", "Tokens out"], (g.by_model || []).map((x) => ({ data: x, cells: [x.provider, x.model || "", n(x.calls), n(x.input_tokens), n(x.output_tokens)] })))),
        h("div", { class: "card" }, h("h2", { text: "By agent" }), table(["Agent", "Calls", "Tokens"], (g.by_agent || []).map((x) => ({ data: x, cells: [botName(x.bot_path), n(x.calls), n(x.tokens)] }))))),
      h("div", { class: "card" }, h("h2", { text: "Recent AI calls" }), dataTable({ rows: g.recent || [], search: "Search calls", exportName: "ai-calls", pageSize: 25, onRow: (c) => c.run_id && openRun(c.run_id),
        columns: [{ label: "Time", value: (c) => c.ts, render: (c) => h("span", { title: local(c.ts) }, ago(c.ts)) }, { label: "Agent", value: (c) => botName(c.bot_path) }, { label: "User", value: (c) => c.requested_by || "" },
          { label: "Purpose", value: (c) => c.purpose || "" }, { label: "Model", value: (c) => (c.provider || "") + "/" + (c.model || "") },
          { label: "Tokens", value: (c) => Number(c.input_tokens || 0) + Number(c.output_tokens || 0) },
          { label: "Masked", value: (c) => c.masked || "", render: (c) => c.masked && c.masked !== "[]" ? h("span", { class: "pill pending", text: String(c.masked).replace(/[\[\]"]/g, "") }) : "\u2014" },
          { label: "Tools used", value: (c) => c.tool_calls || "", render: (c) => c.tool_calls && c.tool_calls !== "[]" ? h("span", { class: "mono small", text: String(c.tool_calls).replace(/[\[\]"]/g, "") }) : "\u2014" },
          { label: "Fingerprint", value: (c) => c.prompt_sha256 || "", render: (c) => h("span", { class: "mono small", title: c.prompt_sha256 || "", text: String(c.prompt_sha256 || "").slice(0, 12) }) }] })));
  },

  async settings(main) { S.adminTab = "settings"; return VIEWS.admin(main); },
  async roles(main) { S.adminTab = "roles"; return VIEWS.admin(main); },
  async connections(main) { S.adminTab = "connections"; return VIEWS.admin(main); },
  async toolkits(main) { S.adminTab = "toolkits"; return VIEWS.admin(main); },

  async admin(main) {
    S.adminTab = S.adminTab || "settings";
    const tab = S.adminTab;
    const tabs = null;
    const ADMIN_TITLES = {
      settings: ["Settings", "Settings for everyone: announcement, sign-in, single sign-on, AI assistants (MCP), uploads and value tracking."],
      roles: ["Roles", "Built-in and custom roles, and the permissions each one grants."],
      connections: ["One Model Connections", "Named AI model connections agents use; keys stay in the One Agent Vault."],
      toolkits: ["Toolkits", "Every toolkit, how many agents use it, and switching it on or off."] };
    const head = header(ADMIN_TITLES[tab][0], ADMIN_TITLES[tab][1]);
    if (tab === "settings") {
      const st = await api("GET", "settings");
      const ann = h("textarea", { rows: 2, value: st.announcement || "", placeholder: "Maintenance on Saturday 10:00\u201312:00 UTC" });
      const num = (k, min, max) => h("input", { type: "number", min: String(min), max: String(max), value: String(st[k] === undefined ? "" : st[k]) });
      const txt = (k, ph) => h("input", { type: "text", value: String(st[k] || ""), placeholder: ph || "" });
      const pub = txt("public_url", location.origin), ssoIssuer = txt("sso_issuer", "https://login.microsoftonline.com/<tenant>/v2.0"), ssoClient = txt("sso_client_id"),
        ssoSecret = txt("sso_client_secret_ref", "sso/oidc/client_secret"), ssoRole = txt("sso_default_role", "viewer"), ssoLabel = txt("sso_label", "Sign in with your organisation account"),
        mcpAgents = txt("mcp_agents", "Finance/Invoice lookup.flow.json"), mcpRunner = txt("mcp_runner"), mcpTimeout = num("mcp_timeout_seconds", 10, 3600);
      const inactive = num("inactive_user_days", 0, 3650), session = num("session_hours", 1, 720), upload = num("max_upload_mb", 1, 1024), poll = num("queue_poll_seconds", 3, 3600), saved = num("default_minutes_saved", 0, 100000);
      main.replaceChildren(...head, tabs, h("div", { class: "card form-card" },
        h("label", {}, "Announcement for all users (empty for none)", ann),
        h("div", { class: "grid2" }, h("label", {}, "Disable users inactive for (days, 0 = never)", inactive), h("label", {}, "Sign-in session length (hours)", session),
          h("label", {}, "Largest upload (MB)", upload), h("label", {}, "Work queue check interval (seconds)", poll),
          h("label", {}, "Minutes saved per successful run (default for agents without their own value)", saved),
          h("label", {}, "Public address of the Command Center (for sign-in redirects)", pub)),
        h("h3", {}, ic("key", "#2563eb", "sm"), "Single sign-on (OpenID Connect)"),
        h("p", { class: "muted", text: "Works with Microsoft Entra ID, Okta, Google, Keycloak and other OpenID Connect providers. Register this redirect address with the provider: " + (st.public_url || location.origin) + "/api/auth/sso/callback" }),
        h("div", { class: "grid2" }, h("label", {}, "Issuer URL", ssoIssuer), h("label", {}, "Client id", ssoClient),
          h("label", {}, "Client secret (vault reference folder/credential/attribute)", ssoSecret), h("label", {}, "Role for new people (empty = existing accounts only)", ssoRole),
          h("label", {}, "Button text", ssoLabel)),
        h("h3", {}, ic("bolt", "#7c3aed", "sm"), "AI assistants (Model Context Protocol)"),
        h("p", { class: "muted", text: "Assistants connect to " + location.origin + "/api/mcp with a user's API key and can run the agents listed here as tools, with that user's permissions." }),
        h("div", { class: "grid2" }, h("label", {}, "Agents offered as tools (paths, comma separated; * for all)", mcpAgents), h("label", {}, "Runner or runner pool id (empty = any online runner)", mcpRunner),
          h("label", {}, "Wait for a result (seconds)", mcpTimeout)),
        h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: async () => {
          try {
            await api("PUT", "settings", { announcement: ann.value.trim(), inactive_user_days: Number(inactive.value), session_hours: Number(session.value), max_upload_mb: Number(upload.value),
              queue_poll_seconds: Number(poll.value), default_minutes_saved: Number(saved.value), public_url: pub.value.trim(), sso_issuer: ssoIssuer.value.trim(), sso_client_id: ssoClient.value.trim(),
              sso_client_secret_ref: ssoSecret.value.trim(), sso_default_role: ssoRole.value.trim(), sso_label: ssoLabel.value.trim(), mcp_agents: mcpAgents.value.trim(), mcp_runner: mcpRunner.value.trim(),
              mcp_timeout_seconds: Number(mcpTimeout.value) });
            toast("Settings saved"); showAnnouncement(ann.value.trim());
          } catch (e) { toast(e.message, true); } } }, "Save settings"))));
    } else if (tab === "roles") {
      const r = await api("GET", "roles");
      main.replaceChildren(...head, tabs, h("div", { class: "toolbar" }, h("span", { class: "spacer" }), h("button", { type: "button", class: "primary", onclick: () => roleDialog(null, r.permissions) }, "New role")),
        dataTable({ rows: r.roles, search: "Search roles", exportName: "roles",
          columns: [
            { label: "Role", value: (x) => roleLabel(x.name), render: (x) => h("div", {}, h("strong", { text: roleLabel(x.name) }), x.builtin ? h("span", { class: "tag", text: "built-in" }) : h("code", { class: "small", text: " " + x.name }), h("div", { class: "muted small", text: x.description || "" })) },
            { label: "Permissions", value: (x) => x.permissions.join(" "), render: (x) => h("div", { class: "chips" }, x.permissions.map((p) => h("span", { class: "chip", text: p }))) },
            { label: "Users", value: (x) => x.users === undefined ? "" : x.users },
            { label: "", sort: false, render: (x) => x.builtin ? "" : h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => roleDialog(x, r.permissions) }, "Edit"),
              h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete role " + x.name + "?")) { try { await api("DELETE", "roles/" + encodeURIComponent(x.name)); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } } }, "Delete")) }]}));
    } else if (tab === "connections") {
      const r = await api("GET", "connections");
      main.replaceChildren(...head, tabs, h("p", { class: "muted", text: "Named AI model connections that agents use through the connection input of the One Prompt Skill, AI agent and generative AI actions. Keys stay in the One Agent Vault; agents receive only the reference." }),
        h("div", { class: "toolbar" }, h("span", { class: "spacer" }), h("button", { type: "button", class: "primary", onclick: () => connectionDialog() }, "New connection")),
        dataTable({ rows: r.connections, search: "Search connections", exportName: "model-connections", empty: "No model connections yet.",
          columns: [
            { label: "Name", value: (c) => c.name, render: (c) => h("div", {}, h("strong", { text: c.name }), h("div", { class: "muted small", text: c.description || "" })) },
            { label: "Provider", value: (c) => c.provider }, { label: "Model", value: (c) => c.model || "" },
            { label: "Endpoint", value: (c) => c.base_url || "", render: (c) => h("span", { class: "mono small", text: c.base_url || "provider default" }) },
            { label: "Key", value: (c) => c.key_ref || "", render: (c) => c.key_ref ? h("span", {}, ic("lock", "#dc2626", "sm"), h("code", { text: c.key_ref })) : h("span", { class: "muted", text: "none" }) },
            { label: "Updated", value: (c) => c.updated || "", render: (c) => ago(c.updated) + " by " + (c.updated_by || "") },
            { label: "", sort: false, render: (c) => h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => connectionDialog(c) }, "Edit"),
              h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete connection " + c.name + "?")) { try { await api("DELETE", "connections/" + encodeURIComponent(c.name)); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } } }, "Delete")) }]}));
    } else {
      const r = await api("GET", "toolkits");
      main.replaceChildren(...head, tabs, h("p", { class: "muted", text: "Switch a toolkit off to stop agents that use it from being checked in or run. The core toolkit is always on." }),
        dataTable({ rows: r.toolkits, search: "Search toolkits", exportName: "toolkits", pageSize: 100,
          columns: [
            { label: "Toolkit", value: (t) => t.name, render: (t) => h("div", {}, h("strong", { text: t.name }), h("div", { class: "muted small mono", text: t.id + " " + (t.version || "") }), h("div", { class: "muted small", text: t.description || "" })) },
            { label: "Actions", value: (t) => t.actions + t.conditions, render: (t) => t.actions + (t.conditions ? " + " + t.conditions + " conditions" : "") },
            { label: "Used by", value: (t) => t.used_by, render: (t) => t.used_by ? h("button", { type: "button", class: "link", onclick: async () => {
              const u = await api("GET", "toolkits/" + encodeURIComponent(t.id) + "/used-by");
              modal("Agents using " + t.name, [table(["Agent"], u.agents.map((a) => ({ data: a, cells: [botName(a)] })))], []); } }, t.used_by + " agent" + (t.used_by === 1 ? "" : "s")) : h("span", { class: "muted", text: "none" }) },
            { label: "Status", value: (t) => t.enabled ? "On" : "Off", render: (t) => pill(t.enabled ? "enabled" : "disabled") },
            { label: "", sort: false, render: (t) => t.id === "core" ? "" : h("button", { type: "button", onclick: async () => {
              if (t.enabled && t.used_by && !confirm(t.used_by + " agents use " + t.name + ". They will stop running. Switch it off?")) return;
              try { await api("PATCH", "toolkits/" + encodeURIComponent(t.id), { enabled: !t.enabled }); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } }, t.enabled ? "Switch off" : "Switch on") }]}));
    }
  },

  async schedules(main) {
    await loadRefs();
    const r = await api("GET", "schedules");
    const when = (s) => s.kind === "every" ? "Every " + s.every_minutes + " min" : s.kind === "daily" ? "Daily at " + s.at_time : s.days + " at " + s.at_time;
    const [cals, pools] = await Promise.all([api("GET", "calendars"), can("devices.view") ? api("GET", "pools") : { pools: [] }]);
    S.calendars = cals.calendars; S.pools = pools.pools;
    main.replaceChildren(...header("Schedules", "Run agents automatically. Scheduled runs use the vault permissions of the schedule's owner; holiday calendars skip the dates you list.",
      h("button", { type: "button", onclick: calendarsDialog }, ic("calendar", "#059669", "sm"), "Holiday calendars"),
      can("schedules.manage") ? h("button", { type: "button", class: "primary", onclick: scheduleDialog }, "New schedule") : null),
      dataTable({ rows: r.schedules, search: "Search schedules", exportName: "schedules", empty: "No schedules yet.",
        onRow: can("runs.view") ? (s) => { S.activity = { tab: "history", offset: 0, size: 25, f: { source: "schedule", bot: s.bot_path } }; go("activity"); } : null,
        columns: [
          { label: "Schedule", value: (s) => s.name, render: (s) => h("div", { class: "name-cell" }, ic("clock", "#059669", "sm"), h("strong", { text: s.name })) },
          { label: "Agent", value: (s) => botName(s.bot_path) },
          { label: "Runner", value: (s) => s.pool_id ? "Pool: " + ((S.pools.find((p) => p.id === s.pool_id) || {}).name || s.pool_id) : deviceName(s.device_id) },
          { label: "When", value: (s) => when(s) + " (" + s.time_zone + ")" + (s.holiday_calendar ? ", not on " + s.holiday_calendar : "") },
          { label: "Owner", value: (s) => s.created_by || "" },
          { label: "Next run", value: (s) => s.enabled ? s.next_run || "" : "", render: (s) => s.enabled ? h("span", { title: local(s.next_run) }, ago(s.next_run)) : "\u2014" },
          { label: "Last run", value: (s) => s.last_run || "", render: (s) => s.last_run ? h("span", { title: local(s.last_run) }, ago(s.last_run)) : "never" },
          { label: "Enabled", value: (s) => s.enabled ? "Yes" : "No", render: (s) => s.enabled ? pill("enabled") : pill("disabled") },
          { label: "", sort: false, render: (s) => h("div", { class: "row-actions tight" },
            can("runs.start") ? h("button", { type: "button", onclick: async () => { try { const x = await api("POST", "schedules/" + s.id + "/run"); toast("Queued " + x.run_id); } catch (e) { toast(e.message, true); } } }, "Run now") : null,
            can("schedules.manage") ? h("button", { type: "button", onclick: () => scheduleEditDialog(s) }, "Edit") : null,
            can("schedules.manage") ? h("button", { type: "button", onclick: async () => { await api("PATCH", "schedules/" + s.id, { enabled: !s.enabled }); go("schedules"); } }, s.enabled ? "Disable" : "Enable") : null,
            can("schedules.manage") ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete schedule " + s.name + "?")) { await api("DELETE", "schedules/" + s.id); go("schedules"); } } }, "Delete") : null) }]}));
  },

  async triggers(main) {
    await loadRefs();
    const r = await api("GET", "triggers");
    const act = async (fn) => { try { await fn(); } catch (e) { toast(e.message, true); } go("triggers"); };
    main.replaceChildren(...header("Triggers", "Start agents on events: files and folders, windows, processes, services and hotkeys on a runner; new email, ServiceNow records and polled web data on the Command Center; or a webhook call. Agent Studio also has its own local triggers for development.",
      can("triggers.manage") ? h("button", { type: "button", class: "primary", onclick: triggerDialog }, "New trigger") : null),
      dataTable({ rows: r.triggers, search: "Search triggers", exportName: "triggers", empty: "No triggers yet.",
        columns: [
          { label: "Trigger", value: (t) => t.name, render: (t) => h("div", { class: "name-cell" }, ic("bolt", "#d97706", "sm"), h("strong", { text: t.name })) },
          { label: "Kind", value: (t) => triggerKindLabel(t.kind), render: (t) => h("div", {}, h("div", { text: triggerKindLabel(t.kind) }),
            h("div", { class: "muted small", text: t.evaluated_on === "server" ? "Checked by the Command Center" : t.evaluated_on === "runner" ? "Watched on the runner" + (t.windows_only ? " (Windows)" : "") : "" })) },
          { label: "Agent", value: (t) => botName(t.bot_path) }, { label: "Runner", value: (t) => deviceName(t.device_id) },
          { label: "Details", value: (t) => triggerDetails(t), render: (t) => h("span", { class: "mono small", text: triggerDetails(t) }) },
          { label: "Monitoring", value: (t) => (t.fire_count || 0) + " " + (t.last_fired || "") + " " + (t.last_error || ""), render: (t) => h("div", { class: "small" },
            h("div", {}, h("strong", { text: String(t.fire_count || 0) }), " fired", t.last_fired ? h("span", { class: "muted", title: local(t.last_fired) }, ", last " + ago(t.last_fired)) : h("span", { class: "muted" }, ", never")),
            t.last_error ? h("div", { class: "error trigger-error", title: t.last_error, text: t.last_error.length > 90 ? t.last_error.slice(0, 90) + "…" : t.last_error }) : null) },
          { label: "Status", value: (t) => t.status || (t.enabled ? "active" : "paused"), render: (t) => t.status === "error" ? h("span", { class: "pill failed", text: "error" }) : pill(t.enabled ? "active" : "paused") },
          { label: "", sort: false, render: (t) => can("triggers.manage") ? h("div", { class: "row-actions tight" },
            t.evaluated_on === "server" && t.enabled ? h("button", { type: "button", title: "Check now instead of waiting for the interval", onclick: () => act(async () => { const x = await api("POST", "triggers/" + t.id + "/poll"); toast(x.error ? x.error : "Checked: " + x.fired + " new"); }) }, "Check now") : null,
            h("button", { type: "button", onclick: () => act(() => api("POST", "triggers/" + t.id + (t.enabled ? "/pause" : "/resume"))) }, t.enabled ? "Pause" : "Resume"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete trigger " + t.name + "?")) act(() => api("DELETE", "triggers/" + t.id)); } }, "Delete")) : "" }]}));
    if (main.isConnected) S.timer = setInterval(() => { if (S.page === "triggers" && $("modal").hidden && $("drawer").hidden) go("triggers"); }, 30000);
  },

  async credentials(main) {
    const r = await api("GET", "lockers");
    const q = h("input", { type: "search", placeholder: "Search vault folders and credentials", "aria-label": "Search vault folders" });
    const box = h("div", { class: "locker-grid" });
    const render = () => {
      const f = q.value.trim().toLowerCase();
      const lockers = r.lockers.filter((l) => !f || (l.name + " " + (l.description || "") + " " + l.credentials.map((c) => c.name + " " + c.attribute).join(" ")).toLowerCase().includes(f)).map((l) => h("div", { class: "card locker" },
        h("div", { class: "locker-head" }, ic("lock", "#dc2626"), h("div", {}, h("h2", { text: l.name }), h("p", { class: "muted", text: (l.description || "") + "  Owners: " + l.owners + ".  Consumers: " + (l.consumers || "none") + "." }),
          l.external ? h("p", {}, h("span", { class: "pill running", text: "Linked: " + (EXT_VAULTS[l.external.provider] || l.external.provider) }), " ", h("span", { class: "muted small", text: (l.external.url || l.external.region || "") + " \u00B7 credentials are read from there as name/attribute" })) : null)),
        table(["Credential", "Attribute", "Updated", ""], l.credentials.map((c) => ({ data: c, cells: [c.name, c.attribute, ago(c.updated) + " by " + c.updated_by,
          l.can_manage ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + c.name + "/" + c.attribute + "?")) { await api("DELETE", "lockers/" + l.name + "/" + c.name + "/" + c.attribute); go("credentials"); } } }, "Delete") : ""] }))),
        l.can_manage ? h("div", { class: "row-actions" },
          h("button", { type: "button", onclick: () => credentialDialog(l.name) }, "Add or update credential"),
          h("button", { type: "button", onclick: () => consumersDialog(l) }, "Consumers"),
          h("button", { type: "button", onclick: () => externalVaultDialog(l) }, l.external ? "External vault" : "Link an external vault")) : null));
      box.replaceChildren(...(lockers.length ? lockers : [h("p", { class: "muted", text: r.lockers.length ? "No matches." : "No vault folders you own or consume yet." })]));
    };
    q.addEventListener("input", render);
    main.replaceChildren(...header("One Agent Vault", "Encrypted vault folders. Values are never shown again after saving; agents get them only while running, and every use is audited.",
      can("lockers.manage") || can("lockers.own") ? h("button", { type: "button", class: "primary", onclick: lockerDialog }, "New vault folder") : null), h("div", { class: "toolbar" }, q), box);
    render();
  },

  async globals(main) {
    const r = await api("GET", "globals");
    main.replaceChildren(...header("Shared values", "Shared values every agent can read as $global.name.", can("globals.manage") ? h("button", { type: "button", class: "primary", onclick: () => globalDialog() }, "New shared value") : null),
      dataTable({ rows: r.globals, search: "Search shared values", exportName: "global-values", empty: "No shared values yet.",
        columns: [
          { label: "Name", value: (g) => "$global." + g.name, render: (g) => h("code", { text: "$global." + g.name }) },
          { label: "Value", value: (g) => JSON.stringify(g.value), render: (g) => h("code", { class: "val", text: JSON.stringify(g.value) }) },
          { label: "Updated", value: (g) => g.updated, render: (g) => h("span", { title: local(g.updated) }, ago(g.updated) + " by " + g.updated_by) },
          { label: "", sort: false, render: (g) => can("globals.manage") ? h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => globalDialog(g) }, "Edit"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + g.name + "?")) { await api("DELETE", "globals/" + g.name); go("globals"); } } }, "Delete")) : "" }]}));
  },

  async users(main) {
    const [r, ra, dv] = await Promise.all([api("GET", "users"), api("GET", "devices/runasusers").catch(() => ({ users: [] })), api("GET", "devices").catch(() => ({ devices: [] }))]);
    S.users = r.users; S.devices = dv.devices;
    // run-as users: the runner each user's runs go to by default
    const runAs = Object.fromEntries(ra.users.map((x) => [x.username, x]));
    try { S.roles = (await api("GET", "roles")).roles.map((x) => x.name); } catch (_) { S.roles = Object.keys(ROLE_LABELS); }
    const roleSel = (u) => { const s = h("select", { "aria-label": "Role of " + u.username, onchange: async (e) => { try { await api("PATCH", "users/" + u.id, { role: e.target.value }); toast("Role changed"); } catch (err) { toast(err.message, true); go("users"); } } },
      (S.roles || Object.keys(ROLE_LABELS)).map((x) => h("option", { value: x, text: roleLabel(x) }))); s.value = u.role; return s; };
    const status = (u) => u.enabled ? (u.must_change ? "Must change password" : "Active") : "Disabled";
    main.replaceChildren(...header("Users", "People who can sign in, their roles, and the runner their runs go to as run-as users.", h("button", { type: "button", class: "primary", onclick: userDialog }, "New user")),
      dataTable({ rows: r.users, search: "Search users", exportName: "users", onRow: can("audit.view") ? openUser : null,
        columns: [
          { label: "User", value: (u) => (u.display_name || "") + " " + u.username, render: (u) => h("div", { class: "name-cell" }, h("span", { class: "avatar sm", text: (u.display_name || u.username).charAt(0).toUpperCase() }), h("div", {}, h("strong", { text: u.display_name || u.username }), h("div", { class: "muted small", text: u.username }))) },
          { label: "Role", value: (u) => u.role, render: roleSel },
          { label: "Status", value: status, render: (u) => pill(u.enabled ? (u.must_change ? "pending" : "active") : "disabled") },
          { label: "Default runner", value: (u) => (runAs[u.username] || {}).device_name || "", render: (u) => {
            const sel = h("select", { "aria-label": "Default runner of " + u.username, onchange: async (e) => {
              try { await api("PUT", "users/" + u.id + "/default-device", { device_id: e.target.value || null }); toast("Default runner of " + u.username + " saved"); } catch (err) { toast(err.message, true); go("users"); } } },
              h("option", { value: "", text: "(none)" }), (S.devices || []).map((d) => h("option", { value: d.id, text: d.name })));
            sel.value = (runAs[u.username] || {}).device_id || ""; return sel; } },
          { label: "Last sign-in", value: (u) => u.last_login || "", render: (u) => u.last_login ? h("span", { title: local(u.last_login) }, ago(u.last_login)) : "never" },
          { label: "", sort: false, render: (u) => h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: async () => { try { await api("PATCH", "users/" + u.id, { enabled: !u.enabled }); go("users"); } catch (e) { toast(e.message, true); } } }, u.enabled ? "Disable" : "Enable"),
            h("button", { type: "button", onclick: async () => { if (!confirm("Reset the password of " + u.username + "?")) return; const p = await api("POST", "users/" + u.id + "/reset-password");
              modal("Temporary password", [secretBox(u.username, p.temporary_password, "Give it to the user securely. They must change it at first sign-in. Shown only once.")], []); } }, "Reset password")) }]}));
  },

  async audit(main) {
    const q = h("input", { type: "search", placeholder: "Filter by user, action or object", "aria-label": "Filter audit trail" });
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
    main.replaceChildren(...header("One Audit Trail", "Every sign-in, change, run and credential use. Records are hash-chained, so editing history is detectable."), h("div", { class: "toolbar" }, q, result, verify, status), box);
    await render();
  },
};

function runTable(runs, onRow) {
  return table(["Status", "Agent", "Runner", "Started by", "Source", "Created", "Duration"], runs.map((r) => ({ data: r, cells: [pill(runStatus(r)),
    botName(r.bot_path) + " v" + r.version, r.device_name || r.device_id, startedBy(r), sourceLabel(r.source), h("span", { title: local(r.created) }, ago(r.created)), dur(r.duration_ms)] })), onRow);
}

async function control(id, action, after) {
  try { await api("POST", "runs/" + id + "/" + action); toast(action === "stop" ? "Stop requested" : action === "pause" ? "Pause requested: the agent pauses after the current step" : "Resume requested"); if (after) after(); }
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
        : h("p", { class: "muted", text: active ? "Waiting for the runner to start the agent..." : "No steps were recorded." });
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
      kv([["Run", h("code", { text: r.id })], ["Agent", botName(r.bot_path) + " v" + r.version], ["Runner", r.device_name || r.device_id], ["Started by", startedBy(r) + " (" + sourceLabel(r.source) + ")"],
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
    can("runs.start") && b.production_version && isAgent(b.path) ? h("button", { type: "button", class: "primary", onclick: () => runDialog(b) }, "Run") : null,
    can("runs.start") && b.production_version && /\.process\.json$/.test(b.path) ? h("button", { type: "button", class: "primary", onclick: () => startWorkflowDialog(b.path) }, "Start") : null,
    h("button", { type: "button", onclick: () => versionsDialog(b) }, "Versions"),
    can("repo.delete") && ["edit", "manage"].includes(b.access) ? h("button", { type: "button", class: "danger", onclick: () => deleteBotAsk(b) }, "Delete") : null]);
  try {
    const deps = await api("GET", "repo/dependencies?path=" + encodeURIComponent(b.path)).catch(() => null);
    const [prod, runs, vers] = await Promise.all([b.production_version ? api("GET", "repo/bot?path=" + encodeURIComponent(b.path) + "&version=production") : null,
      can("runs.view") ? api("GET", "runs?limit=10&bot=" + encodeURIComponent(b.path)) : { runs: [] }, api("GET", "repo/versions?path=" + encodeURIComponent(b.path))]);
    const flow = prod ? prod.flow : null;
    const inputs = flow ? (flow.variables || []).filter((v) => v.direction === "input" || v.direction === "in_out") : [];
    const outputs = flow ? (flow.variables || []).filter((v) => v.direction === "output" || v.direction === "in_out") : [];
    body.replaceChildren(
      b.description ? h("p", { text: b.description }) : null,
      kv([["Agent id", flow ? h("code", { text: flow.id }) : null], ["Kind", flow ? flow.kind : null], ["Live production", b.production_version ? "v" + b.production_version : "none"], ["Latest", "v" + b.latest_version],
        ["Status", b.locked_by ? "Checked out by " + b.locked_by : "Checked in"], ["Updated", local(b.updated) + " by " + (b.updated_by || "")], ["Steps", flow ? String((flow.steps || []).length) + " top-level" : null]]),
      h("h3", { text: "Inputs" }), inputs.length ? table(["Name", "Type", "Required", "Description"], inputs.map((v) => ({ data: v, cells: [h("code", { text: v.name }), v.type, v.required ? "Yes" : "", v.description || ""] }))) : h("p", { class: "muted", text: "No inputs." }),
      outputs.length ? [h("h3", { text: "Outputs" }), table(["Name", "Type", "Description"], outputs.map((v) => ({ data: v, cells: [h("code", { text: v.name }), v.type, v.description || ""] })))] : null,
      roiBox(b),
      deps ? [h("h3", { text: "Dependencies" }), kv([
        ["Calls these agents", deps.agents.length ? h("div", { class: "chips" }, deps.agents.map((a) => h("span", { class: "chip" + (a.found ? "" : " missing"), title: a.found ? "" : "Not in this Command Center", text: a.path ? botName(a.path) : a.bot_id }))) : "none"],
        ["Called by", deps.called_by.length ? h("div", { class: "chips" }, deps.called_by.map((p) => h("span", { class: "chip", text: botName(p) }))) : "no other agent"],
        ["Toolkits", deps.toolkits.length ? h("div", { class: "chips" }, deps.toolkits.map((t) => h("span", { class: "chip", text: t }))) : "built-in actions only"]])] : null,
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
    can("runs.start") ? h("button", { type: "button", class: "primary", onclick: async () => { await loadRefs(); runDialog(null, d.id); } }, "Run an agent here") : null, rename,
    can("devices.manage") ? h("button", { type: "button", onclick: async () => { try { await api("PATCH", "devices/" + d.id, { enabled: !d.enabled }); go("devices"); } catch (e) { toast(e.message, true); } } }, d.enabled ? "Disable" : "Enable") : null,
    can("devices.manage") ? h("button", { type: "button", class: "danger", onclick: async () => {
      if (!confirm("Remove runner " + d.name + "? It is taken off runner pools and default runners; register it again to use it.")) return;
      try { await api("DELETE", "devices/" + d.id); closeDrawer(); toast("Runner removed"); go("devices"); } catch (e) { toast(e.message, true); } } }, "Remove") : null]);
  try {
    const [active, hist] = can("runs.view") ? await Promise.all([api("GET", "runs?filter=active&device=" + encodeURIComponent(d.id)), api("GET", "runs?filter=history&limit=15&device=" + encodeURIComponent(d.id))]) : [{ runs: [] }, { runs: [] }];
    body.replaceChildren(
      kv([["Status", pill(d.status)], ["Host name", d.hostname], ["Operating system", d.os], ["Agent version", d.agent_version], ["Last seen", local(d.last_seen) + " (" + ago(d.last_seen) + ")"],
        ["Registered", local(d.registered) + " by " + (d.registered_by || "")], ["Runner id", h("code", { text: d.id })]]),
      h("h3", { text: "Now" }), active.runs.length ? runTable(active.runs, (r) => openRun(r.id)) : h("p", { class: "muted", text: "Idle." }),
      h("h3", { text: "History" }), runTable(hist.runs, (r) => openRun(r.id)));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

async function openUser(u) {
  const body = h("div", { class: "run-detail" });
  openDrawer([h("span", { class: "avatar sm", text: (u.display_name || u.username).charAt(0).toUpperCase() }), h("span", { text: u.display_name || u.username })], body, []);
  try {
    const a = await api("GET", "audit?limit=30&q=" + encodeURIComponent(u.username));
    body.replaceChildren(kv([["User name", u.username], ["Role", roleLabel(u.role)], ["Status", u.enabled ? (u.must_change ? "Must change password" : "Active") : "Disabled"], ["Last sign-in", local(u.last_login) || "never"]]),
      h("h3", { text: "Recent activity" }), table(["Time", "Action", "Object", "Result"], a.records.filter((x) => x.actor === u.username).map((x) => ({ data: x, cells: [local(x.ts), x.action, (x.object_type || "") + " " + (x.object_id || ""), x.result] }))));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

// ---------- dialogs ----------
async function versionsDialog(b) {
  const r = await api("GET", "repo/versions?path=" + encodeURIComponent(b.path));
  const picks = new Set();
  const compare = h("button", { type: "button", disabled: true, onclick: () => { const [x, y] = [...picks].sort((p, q) => p - q); diffDialog(b, x, y); } }, "Compare selected");
  modal("Versions of " + botName(b.path), [h("p", { class: "muted", text: "Tick two versions to compare them." }),
    table(["", "Version", "Labels", "Author", "Date", "Comment", ""], r.versions.map((v) => ({ data: v, cells: [
      h("input", { type: "checkbox", "aria-label": "Select v" + v.version, onchange: (e) => { if (e.target.checked) picks.add(v.version); else picks.delete(v.version); compare.disabled = picks.size !== 2; } }),
      "v" + v.version + (v.version === b.production_version ? " (production)" : ""),
      h("div", { class: "chips" }, (v.labels || []).map((l) => h("span", { class: "chip" }, l, can("repo.checkin") ? h("button", { type: "button", class: "chip-x", "aria-label": "Remove label " + l, onclick: async () => {
        try { await api("DELETE", "repo/labels?path=" + encodeURIComponent(b.path) + "&label=" + encodeURIComponent(l)); versionsDialog(b); } catch (e) { toast(e.message, true); } } }, "\u00D7") : null))),
      v.author, local(v.created), v.comment || "",
      h("div", { class: "row-actions tight" },
        can("repo.production") && v.version !== b.production_version ? h("button", { type: "button", onclick: async () => {
          try { await api("POST", "repo/production", { path: b.path, version: v.version }); toast("Live production is now v" + v.version); closeModal(); go("automation"); } catch (e) { toast(e.message, true); } } }, "Set as production") : null,
        can("repo.checkin") ? h("button", { type: "button", onclick: async () => {
          const l = prompt("Label for v" + v.version + " (for example UAT or Release-3). A label moves from the version that has it.", "UAT");
          if (!l) return;
          try { await api("PUT", "repo/labels", { path: b.path, label: l.trim(), version: v.version }); versionsDialog(b); } catch (e) { toast(e.message, true); } } }, "Label") : null)] })))],
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
      const ins = (flow.variables || []).filter((v) => v.direction === "input" || v.direction === "in_out");
      fields = ins.map((v) => {
        const el = v.type === "Boolean" ? h("select", {}, h("option", { value: "", text: "(not set)" }), h("option", { value: "true", text: "True" }), h("option", { value: "false", text: "False" }))
          : Array.isArray(v.enum) && v.enum.length ? h("select", {}, h("option", { value: "", text: "(not set)" }), v.enum.map((o) => h("option", { value: JSON.stringify(o), text: String(o) })))
            : ["List", "Dictionary", "Table", "Record", "Any"].includes(v.type) ? h("textarea", { rows: 2, placeholder: "JSON" + (v.default !== undefined ? ", default " + JSON.stringify(v.default) : "") })
              : h("input", { type: v.type === "Number" ? "number" : v.type === "DateTime" ? "datetime-local" : "text", placeholder: v.type === "Credential" ? "locker/credential/attribute" : v.default !== undefined ? "default: " + JSON.stringify(v.default) : "" });
        return { v, el };
      });
      inputsBox.replaceChildren(...(fields.length ? fields.map(({ v, el }) => h("label", {}, h("span", {}, v.name, v.required ? h("span", { class: "req", text: " *" }) : null, h("span", { class: "type", text: v.type })), el,
        v.description ? h("span", { class: "muted small", text: v.description }) : null)) : [h("p", { class: "muted", text: "This agent has no inputs." })]));
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
  if (!S.pools && can("devices.view")) { try { S.pools = (await api("GET", "pools")).pools; } catch (_) { S.pools = []; } }
  const target = h("select", { "aria-label": "Run on" }, h("option", { value: "device", text: "A runner" }), (S.pools || []).length ? h("option", { value: "pool", text: "A runner pool (least busy runner)" }) : null,
    h("option", { value: "users", text: "Run-as users (each on their default runner)" }));
  const pool = h("select", { "aria-label": "Runner pool" }, (S.pools || []).map((p) => h("option", { value: p.id, text: p.name })));
  const devBox = h("label", {}, "Runner", dev), poolBox = h("label", {}, "Runner pool", pool);
  // run-as users: each gets its own run, as that user (credentials are checked against them)
  const picked = new Set();
  const usersList = h("div", { class: "pick-list" }, h("p", { class: "muted", text: "Loading users\u2026" }));
  const usersBox = h("div", {}, h("span", { class: "label-text", text: "Run-as users" }), usersList);
  api("GET", "devices/runasusers").then((r) => usersList.replaceChildren(...(r.users.length ? r.users.map((u) => h("label", { class: "check" },
    h("input", { type: "checkbox", disabled: !u.device_id, onchange: (e) => { if (e.target.checked) picked.add(u.username); else picked.delete(u.username); } }),
    h("span", {}, h("strong", { text: u.username }), " ", h("span", { class: "muted", text: u.device_id ? "on " + (u.device_name || u.device_id) + " (" + (u.device_status || "") + ")" : "no default runner" })))) : [h("p", { class: "muted", text: "No users." })])))
    .catch(() => usersList.replaceChildren(h("p", { class: "muted", text: "Run-as users are not available to you." })));
  const mode = h("select", { "aria-label": "Run mode" }, [["unattended", "Unattended (nobody at the runner)"], ["attended", "Attended (someone is at the runner)"], ["headless", "Headless (no desktop)"]].map(([v, l]) => h("option", { value: v, text: l })));
  const syncTarget = () => { devBox.hidden = target.value !== "device"; poolBox.hidden = target.value !== "pool"; usersBox.hidden = target.value !== "users"; };
  target.addEventListener("change", syncTarget); syncTarget();
  const priority = h("select", { "aria-label": "Priority" }, [["0", "Normal"], ["5", "High"], ["9", "Urgent"], ["-5", "Low"]].map(([v, l]) => h("option", { value: v, text: l })));
  const callback = h("input", { type: "url", placeholder: "https://example.com/hooks/agent-finished" });
  modal("Run " + (b ? botName(b.path) : "an agent"), [bot ? h("label", {}, "Agent (live production version)", bot) : null, h("label", {}, "Run on", target), devBox, poolBox, usersBox, inputsBox,
    h("details", {}, h("summary", { text: "More inputs as JSON (optional)" }), raw),
    h("details", {}, h("summary", { text: "Run mode, priority and result callback (optional)" }), h("div", { class: "grid2" }, h("label", {}, "Run mode", mode), h("label", {}, "Priority on the runner", priority),
      h("label", {}, "Callback URL (gets the result as JSON)", callback))),
    h("p", { class: "muted", text: "Credential inputs are vault references, never the secret itself." })].filter(Boolean),
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      if (target.value === "users") {
        if (!picked.size) throw new Error("Tick at least one run-as user");
        const r = await api("POST", "runs", { bot_path: path(), run_as_users: [...picked], inputs: collect(), priority: Number(priority.value), mode: mode.value, callback_url: callback.value.trim() || null });
        closeModal();
        const bad = r.runs.filter((x) => x.error);
        toast("Started " + r.started + " of " + r.runs.length + " runs" + (bad.length ? ": " + bad.map((x) => x.run_as + " (" + x.error.message + ")").join("; ") : ""), bad.length > 0);
        S.activity = Object.assign({}, S.activity, { tab: "active" }); await go("activity"); return;
      }
      const r = await api("POST", "runs", { bot_path: path(), device_id: target.value === "device" ? dev.value : null, pool_id: target.value === "pool" ? pool.value : null,
        inputs: collect(), priority: Number(priority.value), mode: mode.value, callback_url: callback.value.trim() || null }); closeModal(); toast("Queued " + r.run_id); S.activity = Object.assign({}, S.activity, { tab: "active" }); await go("activity"); openRun(r.run_id); }
    catch (e) { toast(e.message, true); } } }, "Run now")]);
  if (path()) loadInputs(path());
}

function scheduleDialog() {
  const name = h("input", { placeholder: "Nightly invoice run" }), bot = botSelect(), dev = deviceSelect();
  const pool = h("select", { "aria-label": "Runner pool" }, h("option", { value: "", text: "(none: use the runner)" }), (S.pools || []).map((p) => h("option", { value: p.id, text: p.name })));
  const cal = h("select", { "aria-label": "Holiday calendar" }, h("option", { value: "", text: "(none)" }), (S.calendars || []).map((c) => h("option", { value: c.name, text: c.name })));
  const kind = h("select", {}, h("option", { value: "every", text: "Every N minutes" }), h("option", { value: "daily", text: "Daily" }), h("option", { value: "weekly", text: "Weekly" }));
  const every = h("input", { type: "number", min: "1", value: "60" }), at = h("input", { placeholder: "08:30", value: "08:30" });
  const days = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"].map((d) => h("label", { class: "check" }, h("input", { type: "checkbox", value: d, checked: !["SAT", "SUN"].includes(d) }), d));
  const tz = h("input", { value: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC" }), inputs = h("textarea", { placeholder: "{}" });
  const everyBox = h("label", {}, "Minutes", every), atBox = h("label", {}, "Time", at), daysBox = h("div", { class: "toolbar" }, days);
  const sync = () => { everyBox.hidden = kind.value !== "every"; atBox.hidden = kind.value === "every"; daysBox.hidden = kind.value !== "weekly"; };
  kind.addEventListener("change", sync); sync();
  modal("New schedule", [h("label", {}, "Name", name), h("label", {}, "Agent (live production version)", bot), h("div", { class: "grid2" }, h("label", {}, "Runner", dev), h("label", {}, "Or a runner pool", pool)),
    h("label", {}, "Repeat", kind), everyBox, atBox, daysBox,
    h("div", { class: "grid2" }, h("label", {}, "Time zone", tz), h("label", {}, "Skip the dates of a holiday calendar", cal)), h("label", {}, "Inputs (JSON, optional)", inputs)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const body = { name: name.value || "Schedule", bot_path: bot.value, device_id: pool.value ? null : dev.value, pool_id: pool.value || null, holiday_calendar: cal.value || null,
          kind: kind.value, time_zone: tz.value, inputs: parseJson(inputs.value, {}) };
        if (kind.value === "every") body.every_minutes = parseInt(every.value, 10); else body.at_time = at.value;
        if (kind.value === "weekly") body.days = days.map((l) => l.firstChild).filter((c) => c.checked).map((c) => c.value).join(",");
        const r = await api("POST", "schedules", body); closeModal(); toast("Next run " + local(r.next_run)); go("schedules");
      } catch (e) { toast(e.message, true); } } }, "Create")]);
}

function triggerDialog() {
  const name = h("input", { placeholder: "New invoices" }), bot = botSelect(), dev = deviceSelect();
  const kind = h("select", { "aria-label": "Event" }, TRIGGER_GROUPS.map(([group, kinds]) => h("optgroup", { label: group },
    kinds.map((k) => h("option", { value: k, text: TRIGGER_KINDS[k].label })))));
  // One field box per kind; inputs carry data-key, the name sent to the API.
  const boxes = {};
  for (const k of Object.keys(TRIGGER_KINDS)) boxes[k] = h("div", { class: "grid2 trigger-fields", "data-kind": k }, TRIGGER_KINDS[k].fields.map(triggerField));
  const help = h("p", { class: "muted" });
  const sync = () => { for (const k of Object.keys(boxes)) boxes[k].hidden = k !== kind.value; help.textContent = TRIGGER_KINDS[kind.value].help; };
  kind.addEventListener("change", sync); sync();
  modal("New trigger", [h("label", {}, "Name", name), h("label", {}, "Agent (live production version)", bot), h("label", {}, "Runner", dev), h("label", {}, "Event", kind),
    ...Object.values(boxes), help,
    h("p", { class: "muted", text: "Passwords and tokens are never typed here: give vault references such as mail/imap/password. The trigger's owner must be a consumer of that vault folder." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const body = Object.assign({ name: name.value || "Trigger", bot_path: bot.value, device_id: dev.value, kind: kind.value }, triggerValues(boxes[kind.value]));
        const r = await api("POST", "triggers", body);
        if (r.secret) modal("Webhook created", [secretBox("URL", location.origin + r.url), secretBox("Secret (send it in the X-OOA-Secret header)", r.secret, "Shown only once.")], []);
        else { closeModal(); toast("Trigger created"); }
        go("triggers");
      } catch (e) { toast(e.message, true); } } }, "Create")]);
}

// ---------- trigger kinds (helpers for triggerDialog and the Triggers view) ----------
const TRIGGER_FOLDER = [["folder", "Folder on the runner", "C:\\Invoices\\Inbox"], ["pattern", "File or folder name pattern", "*", "*"], ["recursive", "Include sub-folders", "", "", "check"]];
const TRIGGER_INTERVAL = ["interval_seconds", "Check every (seconds, 15 or more)", "60", "60", "number"];
const TRIGGER_MAIL_FILTERS = [["from_contains", "From contains (optional)", "billing@"], ["subject_contains", "Subject contains (optional)", "Invoice"]];
const TRIGGER_KINDS = {
  file: { label: "File arrives in a folder", fields: TRIGGER_FOLDER, help: "Watched on the runner. The agent gets the path in trigger_file and all details in trigger_event." },
  file_changed: { label: "File changed", fields: TRIGGER_FOLDER, help: "Fires when a matching file's size or time changes, once it stops changing. Input: trigger_file." },
  file_deleted: { label: "File deleted", fields: TRIGGER_FOLDER, help: "Fires when a matching file disappears. Input: trigger_file." },
  folder_created: { label: "Folder created", fields: TRIGGER_FOLDER, help: "Fires when a matching sub-folder appears. Input: trigger_file (the folder path)." },
  folder_deleted: { label: "Folder deleted", fields: TRIGGER_FOLDER, help: "Fires when a matching sub-folder disappears. Input: trigger_file." },
  window_opened: { label: "Window opened", fields: [["title", "Window title pattern", "*Invoice*"]], help: "Windows runners. Text the title contains, a pattern with * and ?, or re: and a regular expression. Input: trigger_window." },
  window_closed: { label: "Window closed", fields: [["title", "Window title pattern", "*Invoice*"]], help: "Windows runners. Fires when a matching window goes away. Input: trigger_window." },
  process_started: { label: "Process started", fields: [["process", "Process name", "excel.exe"]], help: "Fires when a new process with this name starts on the runner. Input: trigger_process." },
  process_stopped: { label: "Process stopped", fields: [["process", "Process name", "excel.exe"]], help: "Fires when a process with this name ends. Input: trigger_process." },
  service_started: { label: "Service started", fields: [["service", "Windows service name", "Spooler"]], help: "Windows runners. Use the service name (sc query), not the display name. Input: trigger_service." },
  service_stopped: { label: "Service stopped", fields: [["service", "Windows service name", "Spooler"]], help: "Windows runners. Input: trigger_service." },
  hotkey: { label: "Hotkey pressed", fields: [["hotkey", "Key combination", "CTRL+ALT+F9"]], help: "Windows runners with a signed-in desktop. The combination must not be used by another program. Input: trigger_hotkey." },
  email: { label: "New email (IMAP mailbox)", fields: [["host", "IMAP server", "imap.example.com"], ["port", "Port", "993", "993", "number"], ["ssl", "Use SSL/TLS", "", "", "check", true],
    ["username", "User name", "invoices@example.com"], ["password_ref", "Password (vault reference)", "mail/imap/password"], ["mail_folder", "Mailbox folder", "INBOX", "INBOX"], ...TRIGGER_MAIL_FILTERS, TRIGGER_INTERVAL],
    help: "Checked by the Command Center. Each new message starts the agent once, with from, to, subject, date and a text preview in trigger_email." },
  outlook: { label: "New email (Microsoft 365 Outlook)", fields: [["token_ref", "Graph access token (vault reference)", "m365/graph/token"], ["mailbox", "Mailbox (blank for the token's own)", "invoices@contoso.com"],
    ["tenant_id", "Or app sign-in: tenant id", ""], ["client_id", "Application (client) id", ""], ["client_secret_ref", "Client secret (vault reference)", "m365/app/secret"],
    ["mail_folder", "Mail folder", "inbox", "inbox"], ...TRIGGER_MAIL_FILTERS, TRIGGER_INTERVAL],
    help: "Checked by the Command Center through Microsoft Graph. Give a token reference, or tenant id, client id, client secret reference and mailbox. Input: trigger_email." },
  servicenow: { label: "ServiceNow record new or updated", fields: [["instance_url", "Instance URL", "https://yourcompany.service-now.com"], ["table", "Table", "incident", "incident"],
    ["query", "Encoded query (optional)", "active=true^priority=1"], ["username", "User name", "integration.user"], ["password_ref", "Password (vault reference)", "snow/api/password"],
    ["token_ref", "Or OAuth token (vault reference)", ""], TRIGGER_INTERVAL],
    help: "Checked by the Command Center through the Table API. Each new or changed record starts the agent once, with its fields and change (new or updated) in trigger_record." },
  http_poll: { label: "Universal poller (call a web address)", fields: [["url", "URL to call (GET)", "https://api.example.com/orders?status=new"], ["items_path", "Items JSON path", "$.items[*]", "$"],
    ["id_field", "Id field of each item", "id", "id"], ["auth_ref", "Bearer token (vault reference, optional)", ""], TRIGGER_INTERVAL],
    help: "Checked by the Command Center. Each item with an id not seen before starts the agent once, with the item in trigger_item. Items present at the first check are only remembered." },
  webhook: { label: "Webhook (HTTP call)", fields: [], help: "The agent receives the request body in an input named payload. You get the URL and secret after creating it." },
};
const TRIGGER_GROUPS = [["On the runner", ["file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "window_opened", "window_closed", "process_started", "process_stopped", "service_started", "service_stopped", "hotkey"]],
  ["On the Command Center", ["email", "outlook", "servicenow", "http_poll", "webhook"]]];

function triggerKindLabel(k) { return TRIGGER_KINDS[k] ? TRIGGER_KINDS[k].label : k; }

function triggerField([key, label, placeholder, value, type, checked]) {
  if (type === "check") return h("label", { class: "check" }, h("input", { type: "checkbox", "data-key": key, checked: !!checked }), label);
  return h("label", {}, label, h("input", { "data-key": key, placeholder: placeholder || "", value: value || "", type: type === "number" ? "number" : "text" }));
}

function triggerValues(box) {
  const out = {};
  for (const el of box.querySelectorAll("[data-key]")) {
    const k = el.dataset.key;
    if (el.type === "checkbox") out[k] = el.checked;
    else if (el.value.trim() === "") continue;
    else out[k] = el.type === "number" ? Number(el.value) : el.value.trim();
  }
  return out;
}

function triggerDetails(t) {
  const c = t.config || {};
  if (t.kind === "webhook") return "POST " + location.origin + "/api/hooks/" + t.id;
  if (t.folder) return t.folder + " / " + (t.pattern || "*") + (c.recursive ? " (with sub-folders)" : "");
  if (c.title) return "Title: " + c.title;
  if (c.process) return c.process;
  if (c.service) return c.service;
  if (c.hotkey) return c.hotkey;
  if (t.kind === "email") return c.username + " @ " + c.host + " / " + c.mail_folder + " every " + c.interval_seconds + " s";
  if (t.kind === "outlook") return (c.mailbox || "token's mailbox") + " / " + c.mail_folder + " every " + c.interval_seconds + " s";
  if (t.kind === "servicenow") return c.instance_url + " " + c.table + (c.query ? " where " + c.query : "") + " every " + c.interval_seconds + " s";
  if (t.kind === "http_poll") return "GET " + c.url + " " + c.items_path + " by " + c.id_field + " every " + c.interval_seconds + " s";
  return "";
}

function lockerDialog() {
  const name = h("input", { placeholder: "finance" }), desc = h("input", {}), cons = h("input", { placeholder: "role:bot_runner, user:jane" });
  modal("New vault folder", [h("label", {}, "Name", name), h("label", {}, "Description", desc), h("label", {}, "Consumers (who may use it in agents)", cons),
    h("p", { class: "muted", text: "You become the owner. Owners manage credentials; consumers' agents may use them." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("POST", "lockers", { name: name.value, description: desc.value, consumers: cons.value }); closeModal(); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Create")]);
}

function credentialDialog(locker) {
  const name = h("input", { placeholder: "vendor_api" }), attr = h("input", { value: "password" }), val = h("input", { type: "password", autocomplete: "new-password" });
  modal("Credential in " + locker, [h("label", {}, "Credential", name), h("label", {}, "Attribute", attr), h("label", {}, "Value", val),
    h("p", { class: "muted", text: "Agents refer to it as " + locker + "/credential/attribute." })],
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
  modal(g ? "Edit " + g.name : "New shared value", [h("label", {}, "Name", name), h("label", {}, "Value (JSON)", val)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PUT", "globals/" + name.value, { value: parseJson(val.value, "") }); closeModal(); go("globals"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function userDialog() {
  const user = h("input", { placeholder: "jane.doe" }), disp = h("input", { placeholder: "Jane Doe" });
  const role = h("select", {}, (S.roles || Object.keys(ROLE_LABELS)).filter((x) => x !== "admin").concat("admin").map((x) => h("option", { value: x, text: roleLabel(x) })));
  modal("New user", [h("label", {}, "User name", user), h("label", {}, "Display name", disp), h("label", {}, "Role", role)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { const r = await api("POST", "users", { username: user.value, display_name: disp.value, role: role.value });
        modal("User created", [secretBox(r.username, r.temporary_password, "Temporary password: give it to the user securely. They must change it at first sign-in. Shown only once.")], []); go("users"); }
      catch (e) { toast(e.message, true); } } }, "Create")]);
}

const EXT_VAULTS = { hashicorp: "HashiCorp Vault", azure_keyvault: "Azure Key Vault", aws_secrets_manager: "AWS Secrets Manager", cyberark_ccp: "CyberArk Central Credential Provider" };
function externalVaultDialog(l) {
  const e = l.external || {};
  const provider = h("select", {}, Object.entries(EXT_VAULTS).map(([v, t]) => h("option", { value: v, text: t })));
  provider.value = e.provider || "hashicorp";
  const f = {};
  const inp = (k, ph, type) => (f[k] = h("input", { type: type || "text", value: e[k] || "", placeholder: ph || "" }));
  const secret = h("input", { type: "password", autocomplete: "new-password", placeholder: l.external ? "(unchanged)" : "" });
  const PFIELDS = {
    hashicorp: [["url", "Vault address", "https://vault.example.com:8200"], ["mount", "KV v2 mount", "secret"], ["namespace", "Namespace (Enterprise, optional)", ""]],
    azure_keyvault: [["url", "Key Vault address", "https://my-vault.vault.azure.net"], ["tenant_id", "Tenant id", ""], ["client_id", "Application (client) id", ""]],
    aws_secrets_manager: [["region", "Region", "eu-west-1"], ["access_key_id", "Access key id", "AKIA..."], ["endpoint", "Endpoint (optional, e.g. VPC endpoint)", ""]],
    cyberark_ccp: [["url", "Central Credential Provider address", "https://ccp.example.com"], ["app_id", "Application id", ""], ["safe", "Safe", ""], ["folder", "Folder (optional)", ""]] };
  const SECRET = { hashicorp: "Token", azure_keyvault: "Client secret", aws_secrets_manager: "Secret access key", cyberark_ccp: "Password (optional; usually trusted by certificate or address)" };
  const box = h("div", { class: "grid2" }), secretLabel = h("span");
  const sync = () => { box.replaceChildren(...PFIELDS[provider.value].map(([k, l2, ph]) => h("label", {}, l2, f[k] || inp(k, ph)))); secretLabel.textContent = SECRET[provider.value]; };
  provider.addEventListener("change", sync); sync();
  const tName = h("input", { placeholder: "erp" }), tAttr = h("input", { value: "password" }), tOut = h("p", { class: "muted" });
  modal("External vault for " + l.name, [h("p", { class: "muted", text: "Keep secrets in your company vault. Agents still ask this vault folder, so owners, consumers and the One Audit Trail work as before; the value is read live from the external vault." }),
    h("label", {}, "Provider", provider), box, h("label", {}, secretLabel, secret),
    l.external ? h("div", { class: "card inset" }, h("h3", { text: "Test" }), h("div", { class: "grid2" }, h("label", {}, "Credential (secret name)", tName), h("label", {}, "Attribute (field)", tAttr)),
      h("button", { type: "button", onclick: async () => { try { const r = await api("POST", "lockers/" + l.name + "/test", { credential: tName.value, attribute: tAttr.value });
        tOut.textContent = r.ok ? "Found (" + r.length + " characters). The value is not shown." : r.code + ": " + r.message; tOut.className = r.ok ? "ok-text" : "error"; } catch (err) { toast(err.message, true); } } }, "Test reading"), tOut) : null],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    const body = { provider: provider.value };
    for (const [k] of PFIELDS[provider.value]) if (f[k] && f[k].value.trim()) body[k] = f[k].value.trim();
    if (secret.value) body.secret = secret.value;
    try { await api("PUT", "lockers/" + l.name + "/external", body); secret.value = ""; closeModal(); toast("Linked"); go("credentials"); } catch (err) { toast(err.message, true); } } }, "Save link"),
  l.external ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Unlink " + l.name + " from the external vault?")) return; try { await api("DELETE", "lockers/" + l.name + "/external"); closeModal(); go("credentials"); } catch (err) { toast(err.message, true); } } }, "Unlink") : null].filter(Boolean));
}

async function startWorkflowDialog(path) {
  if (!S.bots.length) await loadRefs();
  const defs = S.bots.filter((b) => /\.process\.json$/.test(b.path) && b.production_version);
  const sel = h("select", {}, defs.map((b) => h("option", { value: b.path, text: botName(b.path) })));
  if (path) sel.value = path;
  const data = h("textarea", { rows: 5, placeholder: '{"amount": 1200, "vendor": "Acme"}' });
  modal("Start a workflow", [h("label", {}, "Workflow (live production version)", sel), h("label", {}, "Starting data (JSON, optional)", data),
    h("p", { class: "muted", text: "Stages use this data as $name. Agent tasks run as you on any online runner; human tasks and approvals go to the Task inbox." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const r = await api("POST", "workflows/start", { path: sel.value, data: parseJson(data.value, {}) }); closeModal(); toast("Started " + r.id); S.page = "workflows"; await go("workflows"); openWorkflowRun({ id: r.id }); }
    catch (e) { toast(e.message, true); } } }, "Start")]);
}

async function openWorkflowRun(w) {
  const body = h("div", { class: "run-detail" });
  const load = async () => {
    const r = await api("GET", "workflows/runs");
    const cur = r.runs.find((x) => x.id === w.id);
    if (!cur) { body.replaceChildren(h("p", { class: "muted", text: "Not found." })); return; }
    const evLabel = { agent_started: "Agent started", agent_finished: "Agent finished", agent_failed: "Agent failed", agent_stopped: "Agent stopped", task_created: "Task created", task_completed: "Task completed",
      approved: "Approved", rejected: "Rejected", decided: "Decided", notified: "Notified", waiting: "Waiting", failed: "Failed", cancelled: "Cancelled" };
    body.replaceChildren(kv([["Workflow", botName(cur.path) + " v" + cur.version], ["Status", pill(cur.status === "completed" ? "success" : cur.status)], ["Current stage", cur.stage || "\u2014"],
      ["Started", local(cur.created) + " by " + (cur.started_by || "")], ["Finished", cur.finished ? local(cur.finished) : null], ["Error", cur.error ? (cur.error.code || "") + " " + (cur.error.message || "") : null]]),
      h("h3", { text: "Timeline" }),
      h("ol", { class: "timeline" }, (cur.history || []).map((e) => h("li", {}, h("strong", { text: evLabel[e.event] || e.event }), " \u00B7 ", h("span", { text: e.stage || "" }), " \u00B7 ", h("span", { class: "muted", text: local(e.ts) }),
        e.detail ? h("div", {}, e.detail.run_id ? h("button", { type: "button", class: "link", onclick: () => openRun(e.detail.run_id) }, "Open agent run " + e.detail.run_id) : null,
          h("div", { class: "mono small", text: JSON.stringify(e.detail) })) : null))),
      h("h3", { text: "Data" }), h("pre", { class: "json", text: JSON.stringify(cur.data || {}, null, 2) }));
    const actions = $("drawer-actions");
    if (actions) actions.replaceChildren(...(["running", "waiting"].includes(cur.status) ? [h("button", { type: "button", class: "danger", onclick: async () => {
      if (!confirm("Cancel this workflow run? Open tasks are withdrawn.")) return; try { await api("POST", "workflows/runs/" + cur.id + "/cancel"); load(); } catch (e) { toast(e.message, true); } } }, "Cancel run")] : []));
  };
  openDrawer([ic("process", "#7c3aed"), h("span", { text: "Workflow run" })], body, []);
  await load();
  S.drawerTimer = setInterval(() => { if (!$("drawer").hidden) load().catch(() => {}); }, 4000);
}

function taskField(f, value) {
  const id = "tf-" + f.id;
  if (f.type === "heading") return h("h3", { text: f.label || "" });
  if (f.type === "paragraph") return h("p", { class: "muted", text: f.text || "" });
  let el;
  if (f.type === "multiline") el = h("textarea", { id, rows: 3, placeholder: f.placeholder || "" });
  else if (f.type === "dropdown") el = h("select", { id }, h("option", { value: "", text: "(choose)" }), (f.options || []).map((o) => h("option", { value: o, text: o })));
  else if (f.type === "radio") el = h("div", { class: "radio-group", id, role: "radiogroup" }, (f.options || []).map((o) => h("label", { class: "check" }, h("input", { type: "radio", name: id, value: o }), o)));
  else if (f.type === "checkbox") el = h("input", { id, type: "checkbox" });
  else el = h("input", { id, type: f.type === "number" ? "number" : f.type === "date" ? "date" : f.type === "email" ? "email" : "text", placeholder: f.placeholder || "", min: f.min, max: f.max });
  const v = value !== undefined ? value : f.default;
  if (v !== undefined && v !== null) { if (f.type === "checkbox") el.checked = v === true || v === "true"; else if (f.type === "radio") { const r = el.querySelector('input[value="' + String(v).replace(/"/g, "") + '"]'); if (r) r.checked = true; } else el.value = String(v); }
  const read = () => f.type === "checkbox" ? el.checked : f.type === "radio" ? ((el.querySelector("input:checked") || {}).value || "") : el.value;
  const lab = h("label", { class: f.type === "checkbox" ? "check" : "" }, f.type === "checkbox" ? el : null, h("span", {}, f.label || f.id, f.required ? h("span", { class: "req", text: " *" }) : null), f.type === "checkbox" ? null : el,
    f.help ? h("span", { class: "muted small", text: f.help }) : null);
  lab.read = read;
  return lab;
}

async function openTask(t) {
  const fields = t.kind === "form" && t.form ? (t.form.fields || []).map((f) => [f, taskField(f, (t.result || {})[f.id])]) : [];
  const ctx = Object.keys(t.data || {}).length ? h("details", { open: t.kind === "approval" }, h("summary", { text: "Workflow data" }), kv(Object.entries(t.data).map(([k, v]) => [k, typeof v === "object" ? JSON.stringify(v) : String(v)]))) : null;
  const comment = h("textarea", { rows: 2, placeholder: "Comment (optional)" });
  const done = !t.can_act;
  const submit = async (outcome) => {
    try {
      const answers = {};
      for (const [f, el] of fields) if (el.read) answers[f.id] = el.read();
      await api("POST", "tasks/" + t.id + "/complete", { outcome, answers, comment: comment.value });
      closeModal(); toast(outcome === "rejected" ? "Rejected" : "Done"); go("inbox");
    } catch (e) { toast(e.message, true); }
  };
  const acts = [];
  if (!done) {
    if (t.status === "open" && t.kind !== "notification") acts.push(h("button", { type: "button", onclick: async () => { try { await api("POST", "tasks/" + t.id + "/claim"); toast("It's yours: others no longer see it as open"); closeModal(); go("inbox"); } catch (e) { toast(e.message, true); } } }, "Take it"));
    if (t.status === "claimed") acts.push(h("button", { type: "button", onclick: async () => { try { await api("POST", "tasks/" + t.id + "/release"); closeModal(); go("inbox"); } catch (e) { toast(e.message, true); } } }, "Give back"));
    if (t.kind === "approval") acts.unshift(h("button", { type: "button", class: "primary", onclick: () => submit("approved") }, "Approve"), h("button", { type: "button", class: "danger", onclick: () => submit("rejected") }, "Reject"));
    else if (t.kind === "form") acts.unshift(h("button", { type: "button", class: "primary", onclick: () => submit("submitted") }, (t.form && t.form.submitLabel) || "Submit"));
    else acts.unshift(h("button", { type: "button", class: "primary", onclick: () => submit("done") }, "Mark as read"));
  }
  modal(t.title, [t.description ? h("p", { text: t.description }) : null, t.due ? h("p", { class: t.overdue ? "error" : "muted", text: "Due " + local(t.due) }) : null,
    ...fields.map(([, el]) => el), t.kind === "approval" && !done ? comment : null, ctx,
    done && t.result ? h("details", {}, h("summary", { text: "Answer" }), h("pre", { class: "json", text: JSON.stringify(t.result, null, 2) })) : null].filter(Boolean), acts);
}

function scheduleEditDialog(s) {
  const name = h("input", { value: s.name });
  const cal = h("select", { "aria-label": "Holiday calendar" }, h("option", { value: "", text: "(none)" }), (S.calendars || []).map((c) => h("option", { value: c.name, text: c.name })));
  cal.value = s.holiday_calendar || "";
  const owner = h("input", { value: s.created_by || "", placeholder: "user name" });
  modal("Edit " + s.name, [h("label", {}, "Name", name), h("label", {}, "Skip the dates of a holiday calendar", cal), h("label", {}, "Owner (runs use the owner's permissions)", owner)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const body = { name: name.value, holiday_calendar: cal.value || null };
        if (owner.value.trim() && owner.value.trim() !== s.created_by) body.owner = owner.value.trim();
        await api("PUT", "schedules/" + s.id, body); closeModal(); go("schedules");
      } catch (e) { toast(e.message, true); } } }, "Save")]);
}

async function calendarsDialog() {
  const r = await api("GET", "calendars");
  const name = h("input", { placeholder: "India 2026" }), dates = h("textarea", { rows: 5, placeholder: "2026-01-26\n2026-08-15\n2026-12-25" });
  const pick = (c) => { name.value = c.name; dates.value = c.dates.join("\n"); };
  modal("Holiday calendars", [
    table(["Calendar", "Dates", "Updated", ""], r.calendars.map((c) => ({ data: c, cells: [h("strong", { text: c.name }), c.dates.length + " dates" + (c.dates.length ? " (next " + (c.dates.find((d) => d >= new Date().toISOString().slice(0, 10)) || "none") + ")" : ""),
      ago(c.updated) + " by " + (c.updated_by || ""),
      can("schedules.manage") ? h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => pick(c) }, "Edit"),
        h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete calendar " + c.name + "?")) { try { await api("DELETE", "calendars/" + encodeURIComponent(c.name)); calendarsDialog(); } catch (e) { toast(e.message, true); } } } }, "Delete")) : ""] }))),
    can("schedules.manage") ? h("div", { class: "card inset" }, h("h3", { text: "Add or update a calendar" }), h("label", {}, "Name", name), h("label", {}, "Dates (YYYY-MM-DD, one per line or comma separated)", dates)) : null],
  can("schedules.manage") ? [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PUT", "calendars/" + encodeURIComponent(name.value.trim()), { dates: dates.value.split(/[\s,;]+/).filter(Boolean) }); toast("Calendar saved"); calendarsDialog(); if (S.page === "schedules") go("schedules"); }
    catch (e) { toast(e.message, true); } } }, "Save calendar")] : []);
}

function poolDialog(p) {
  const name = h("input", { value: p ? p.name : "", placeholder: "Finance runners" }), desc = h("input", { value: p ? p.description || "" : "" });
  const boxes = S.devices.map((d) => h("label", { class: "check" }, h("input", { type: "checkbox", value: d.id, checked: !!(p && p.devices.includes(d.id)) }), h("span", { class: "dot " + d.status }), d.name));
  modal(p ? "Edit " + p.name : "New runner pool", [h("label", {}, "Name", name), h("label", {}, "Description", desc), h("h3", { text: "Runners in the pool" }),
    boxes.length ? h("div", { class: "check-list" }, boxes) : h("p", { class: "muted", text: "Register runners first." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    const devices = boxes.map((l) => l.firstChild).filter((c) => c.checked).map((c) => c.value);
    try { await api(p ? "PUT" : "POST", "pools" + (p ? "/" + p.id : ""), { name: name.value, description: desc.value, devices }); closeModal(); go("devices"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function queueDialog(q) {
  const name = h("input", { value: q ? q.name : "", placeholder: "Vendor invoices" }), desc = h("input", { value: q ? q.description || "" : "" });
  const bot = h("select", {}, h("option", { value: "", text: "(choose later)" }), S.bots.filter((b) => b.production_version && isAgent(b.path)).map((b) => h("option", { value: b.path, text: botName(b.path) })));
  const pool = h("select", {}, h("option", { value: "", text: "(choose later)" }), (S.pools || []).map((p) => h("option", { value: p.id, text: p.name })));
  const sla = h("input", { type: "number", min: "0", value: q && q.sla_minutes ? String(q.sla_minutes) : "", placeholder: "e.g. 240" });
  const attempts = h("input", { type: "number", min: "1", max: "10", value: String(q ? q.max_attempts : 2) });
  const model = h("select", {}, h("option", { value: "", text: "(none: items are free-form)" }), (S.models || []).map((m) => h("option", { value: m.id, text: m.name + " (" + m.fields.length + " fields)" })));
  if (q) { bot.value = q.bot_path || ""; pool.value = q.pool_id || ""; model.value = q.model_id || ""; }
  modal(q ? "Edit " + q.name : "New work queue", [h("label", {}, "Name", name), h("label", {}, "Description", desc),
    h("label", {}, "Work item model (the fields every item has; items are checked against it)", model),
    h("div", { class: "grid2" }, h("label", {}, "Agent that works the items", bot), h("label", {}, "Runner pool", pool),
      h("label", {}, "Service level (minutes from added to done)", sla), h("label", {}, "Attempts per item", attempts)),
    h("p", { class: "muted", text: "The agent receives each item in an input named work_item (a dictionary) and its id in work_item_id. Outputs are kept with the item; a failed run retries the item until the attempts are used." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api(q ? "PUT" : "POST", "queues" + (q ? "/" + q.id : ""), { name: name.value, description: desc.value, bot_path: bot.value || null, pool_id: pool.value || null,
      sla_minutes: sla.value ? Number(sla.value) : null, max_attempts: Number(attempts.value), model_id: model.value || null }); closeModal(); closeDrawer(); go("queues"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

/** Parses CSV text (first row = column names) into objects. */
function parseCsv(text) {
  const rows = []; let row = [], cell = "", quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) { if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') quoted = false; else cell += c; }
    else if (c === '"') quoted = true;
    else if (c === ",") { row.push(cell); cell = ""; }
    else if (c === "\n" || c === "\r") { if (c === "\r" && text[i + 1] === "\n") i++; row.push(cell); cell = ""; if (row.some((x) => x !== "")) rows.push(row); row = []; }
    else cell += c;
  }
  row.push(cell); if (row.some((x) => x !== "")) rows.push(row);
  if (rows.length < 2) return [];
  const head = rows[0].map((x) => x.trim());
  return rows.slice(1).map((r) => Object.fromEntries(head.map((k, i) => [k === "reference" ? "_reference" : k === "priority" ? "_priority" : k, k === "priority" ? Number(r[i] || 0) : (r[i] === undefined ? "" : r[i])])));
}

function addItemsDialog(q, after) {
  const text = h("textarea", { rows: 8, placeholder: '[{"_reference": "INV-1001", "vendor": "Acme", "amount": 120.5}]' });
  const file = h("input", { type: "file", accept: ".csv,.json,text/csv,application/json", "aria-label": "CSV or JSON file" });
  const preview = h("p", { class: "muted" });
  file.addEventListener("change", async () => { const f = file.files[0]; if (!f) return; text.value = await f.text(); preview.textContent = f.name + " loaded."; });
  modal("Add work items to " + q.name, [h("p", { class: "muted", text: "Paste a JSON list of objects, or CSV with a header row. A reference column (or _reference) names the item; priority (or _priority) puts it first." }),
    file, text, preview],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const t = text.value.trim();
      // CSV is read by the Command Center, which converts each cell to its field's type in the work item model
      const r = t.startsWith("[") || t.startsWith("{") ? await api("POST", "queues/" + q.id + "/items", { items: [].concat(parseJson(t, [])) })
        : await api("POST", "queues/" + q.id + "/items/csv", { csv: t });
      closeModal(); toast("Added " + r.added + " items"); if (after) after();
    } catch (e) { toast(e.message, true); } } }, "Add items")]);
}

async function openQueue(q) {
  const st = { status: "", q: "" }, sel = new Set();
  const status = h("select", { "aria-label": "Item status" }, [["", "All items"], ["new", "New"], ["in_progress", "In progress"], ["retry", "Waiting to retry"], ["success", "Succeeded"], ["failed", "Failed"], ["deferred", "Deferred"]]
    .map(([v, l]) => h("option", { value: v, text: l })));
  const search = h("input", { type: "search", placeholder: "Search reference or data", "aria-label": "Search items" });
  const list = h("div"), summary = h("div", { class: "q-summary" });
  const act = (action) => h("button", { type: "button", onclick: async () => {
    if (!sel.size) { toast("Tick items first", true); return; }
    if (action === "delete" && !confirm("Delete " + sel.size + " item(s)? Items being worked on are kept.")) return;
    try { await api("POST", "queues/" + q.id + "/items/" + action, { ids: [...sel] }); sel.clear(); load(); } catch (e) { toast(e.message, true); } } },
    action === "retry" ? "Retry selected" : action === "defer" ? "Defer selected" : "Delete selected");
  async function load() {
    const [r, all] = await Promise.all([api("GET", "queues/" + q.id + "/items?limit=200&status=" + st.status + "&q=" + encodeURIComponent(st.q)), api("GET", "queues")]);
    const cur = all.queues.find((x) => x.id === q.id) || q;
    summary.replaceChildren(...["new", "in_progress", "retry", "success", "failed", "deferred"].map((k) => h("span", { class: "pill " + ({ new: "queued", in_progress: "running", retry: "pending", success: "success", failed: "failed", deferred: "stopped" })[k],
      text: k.replace("_", " ") + ": " + (cur.counts[k] || 0) })), cur.overdue ? h("span", { class: "pill failed", text: cur.overdue + " past service level" }) : null);
    list.replaceChildren(table(["", "Reference", "Status", "Attempts", "Priority", "Added", "Result or error", ""], r.items.map((it) => {
      const box = h("input", { type: "checkbox", "aria-label": "Select " + (it.reference || it.id), checked: sel.has(it.id), onchange: (e) => { if (e.target.checked) sel.add(it.id); else sel.delete(it.id); } });
      const err = parseMaybe(it.error), res = parseMaybe(it.result);
      return { data: it, cells: [can("queues.work") ? box : "", h("div", {}, h("strong", { text: it.reference || it.id }), h("div", { class: "muted small mono", text: String(it.data).slice(0, 120) })),
        pill(it.status), String(it.attempts), String(it.priority), ago(it.created),
        it.run_id ? h("button", { type: "button", class: "link", onclick: () => openRun(it.run_id) }, err ? (err.code || "") + " " + (err.message || "") : res ? JSON.stringify(res).slice(0, 80) : "view run")
          : h("span", { class: "muted", text: err ? (err.code || JSON.stringify(err)) : "" }),
        h("button", { type: "button", onclick: () => itemDialog(q, it.id, load) }, it.status === "in_progress" ? "View" : "Open")] };
    })), r.total > r.items.length ? h("p", { class: "muted", text: "Showing " + r.items.length + " of " + r.total + ". Narrow the search to see more." }) : null);
  }
  status.addEventListener("change", () => { st.status = status.value; load(); });
  search.addEventListener("input", () => { clearTimeout(search.t); search.t = setTimeout(() => { st.q = search.value.trim(); load(); }, 250); });
  openDrawer([ic("inbox", "#9333ea"), h("span", { text: q.name })], [
    kv([["Agent", q.bot_path ? botName(q.bot_path) : "not chosen"], ["Runner pool", ((S.pools || []).find((p) => p.id === q.pool_id) || {}).name || "not chosen"],
      ["Attempts per item", String(q.max_attempts)], ["Service level", q.sla_minutes ? q.sla_minutes + " minutes" : "none"], ["Processing", q.processing ? "On (as " + q.run_as + ")" : "Off"]]),
    summary, h("div", { class: "toolbar" }, status, search, can("queues.work") ? act("retry") : null, can("queues.work") ? act("defer") : null, can("queues.work") ? act("delete") : null,
      h("span", { class: "spacer" }), h("button", { type: "button", onclick: () => exportItems(q, st.status) }, ic("download", "#475569", "sm"), "Export CSV")), list], [
    can("queues.work") ? h("button", { type: "button", class: "primary", onclick: () => addItemsDialog(q, load) }, "Add items") : null,
    can("queues.manage") ? h("button", { type: "button", onclick: () => membersDialog(q) }, "Members") : null,
    can("queues.work") ? h("button", { type: "button", onclick: async () => { try { await api("POST", "queues/" + q.id + (q.processing ? "/stop" : "/start")); q.processing = !q.processing; closeDrawer(); go("queues"); } catch (e) { toast(e.message, true); } } }, q.processing ? "Stop processing" : "Start processing") : null,
    can("queues.manage") ? h("button", { type: "button", onclick: () => queueDialog(q) }, "Edit") : null,
    can("queues.manage") ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete work queue " + q.name + " and its items?")) { try { await api("DELETE", "queues/" + q.id); closeDrawer(); go("queues"); } catch (e) { toast(e.message, true); } } } }, "Delete") : null]);
  await load();
  S.drawerTimer = setInterval(() => { if (!$("drawer").hidden && $("modal").hidden) load().catch(() => {}); }, 5000);
}

// ---------- repository: folders, permissions, deleted agents, lifecycle ----------
function newFolderDialog() {
  const path = h("input", { value: S.repoFolder ? S.repoFolder + "/" : "", placeholder: "Finance/Invoices" }), desc = h("input", {});
  modal("New folder", [h("label", {}, "Folder (sub-folders with /)", path), h("label", {}, "Description", desc)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { const r = await api("POST", "repo/folders", { path: path.value.trim(), description: desc.value }); closeModal(); S.repoFolder = r.path; go("automation"); } catch (e) { toast(e.message, true); } } }, "Create")]);
}

const ACCESS = [["view", "View (see, export)"], ["run", "Run (also run and schedule)"], ["edit", "Edit (also check out, check in, delete)"], ["manage", "Manage (also live version and labels)"]];
async function folderPermissionsDialog(folder) {
  let rows;
  try { rows = (await api("GET", "repo/permissions?folder=" + encodeURIComponent(folder))).permissions.map((p) => ({ principal: p.principal, access: p.access })); } catch (e) { toast(e.message, true); return; }
  let roles = [];
  try { roles = (await api("GET", "roles")).roles.map((x) => x.name); } catch (_) { roles = Object.keys(ROLE_LABELS); }
  const box = h("div");
  const draw = () => box.replaceChildren(rows.length ? table(["Who", "Access", ""], rows.map((r, i) => {
    const acc = h("select", { "aria-label": "Access of " + r.principal, onchange: (e) => { r.access = e.target.value; } }, ACCESS.map(([v, l]) => h("option", { value: v, text: l })));
    acc.value = r.access;
    return { data: r, cells: [r.principal.startsWith("role:") ? "Role " + roleLabel(r.principal.slice(5)) : "User " + r.principal.slice(5), acc,
      h("button", { type: "button", onclick: () => { rows.splice(i, 1); draw(); } }, "Remove")] };
  })) : h("p", { class: "muted", text: "No permissions of its own: the folder follows its parent folder (or, at the top, the roles alone)." }));
  const who = h("select", { "aria-label": "Role or user" }, h("optgroup", { label: "Roles" }, roles.map((r) => h("option", { value: "role:" + r, text: roleLabel(r) })), h("option", { value: "role:*", text: "Everyone" })),
    h("optgroup", { label: "Users" }, (S.users && S.users.length ? S.users : []).map((u) => h("option", { value: "user:" + u.username, text: u.username }))));
  const user = h("input", { placeholder: "or a user name" });
  const level = h("select", { "aria-label": "Access" }, ACCESS.map(([v, l]) => h("option", { value: v, text: l })));
  draw();
  modal("Permissions of " + folder, [h("p", { class: "muted", text: "Once a folder has permissions, only the roles and users listed here can see its agents (and its sub-folders' agents, unless a sub-folder has its own). Role permissions still apply: folder access never grants what a role does not allow." }),
    box, h("div", { class: "grid2" }, h("label", {}, "Add", who), h("label", {}, "User name (instead)", user), h("label", {}, "Access", level)),
    h("button", { type: "button", onclick: () => { const pr = user.value.trim() ? "user:" + user.value.trim() : who.value; if (!rows.some((x) => x.principal === pr)) rows.push({ principal: pr, access: level.value }); user.value = ""; draw(); } }, "+ Add")],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PUT", "repo/permissions", { folder, permissions: rows }); closeModal(); toast("Permissions of " + folder + " saved"); go("automation"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

async function deleteBotAsk(b) {
  if (!confirm("Delete " + botName(b.path) + "? It stops being listed and cannot run, but its versions are kept and it can be recovered from Deleted agents.")) return;
  try {
    const r = await api("POST", "repo/delete", { path: b.path });
    closeDrawer();
    toast("Deleted " + botName(b.path) + (r.called_by && r.called_by.length ? ". Note: " + r.called_by.map(botName).join(", ") + " still call it." : ""), !!(r.called_by && r.called_by.length));
    go("automation");
  } catch (e) { toast(e.message, true); }
}

async function deletedDialog() {
  let r;
  try { r = await api("GET", "repo?deleted=true"); } catch (e) { toast(e.message, true); return; }
  modal("Deleted agents", [r.bots.length ? table(["Agent", "Deleted", "By", ""], r.bots.map((b) => ({ data: b, cells: [botName(b.path), local(b.deleted_at), b.deleted_by || "",
    h("button", { type: "button", onclick: async () => { try { await api("POST", "repo/recover", { path: b.path }); toast("Recovered " + botName(b.path)); deletedDialog(); go("automation"); } catch (e) { toast(e.message, true); } } }, "Recover")] })))
    : h("p", { class: "muted", text: "No deleted agents." })], []);
}

function exportDialog(bots) {
  const picked = new Set();
  const list = h("div", { class: "pick-list" }, bots.map((b) => h("label", { class: "check" }, h("input", { type: "checkbox", onchange: (e) => { if (e.target.checked) picked.add(b.path); else picked.delete(b.path); } }),
    h("span", {}, botName(b.path), " ", h("span", { class: "muted", text: "v" + b.latest_version + (b.production_version ? ", live v" + b.production_version : "") })))));
  const version = h("input", { value: "production", "aria-label": "Version" });
  const deps = h("input", { type: "checkbox", checked: true });
  const target = h("input", { type: "url", placeholder: "https://cc-prod.example.com" }), key = h("input", { placeholder: "vault folder/credential/attribute" });
  const conflict = h("select", {}, h("option", { value: "skip", text: "Skip agents that are already there" }), h("option", { value: "overwrite", text: "Add a new version of agents that are already there" }));
  const live = h("input", { type: "checkbox" });
  const req = () => { if (!picked.size) throw new Error("Tick at least one agent"); return { paths: [...picked], version: version.value.trim() || "production", include_dependencies: deps.checked }; };
  modal("Export or promote agents", [h("p", { class: "muted", text: "A bundle holds each agent's exact version with a checksum, and (when ticked) every agent it calls. Import it in another Command Center, or promote straight to one." }),
    list, h("div", { class: "grid2" }, h("label", {}, "Version (production, latest, a number or a label)", version), h("label", { class: "check" }, deps, "Include the agents they call")),
    h("details", {}, h("summary", { text: "Promote to another Command Center" }),
      h("div", { class: "grid2" }, h("label", {}, "Its address", target), h("label", {}, "Its API key (vault reference)", key), h("label", {}, "If an agent is already there", conflict), h("label", { class: "check" }, live, "Make the versions live there")),
      h("p", { class: "muted", text: "Create an API key there (Your API keys) as someone allowed to import, and store it in a vault folder here. The key is read from the vault and sent only to that address." }),
      h("button", { type: "button", class: "primary", onclick: async () => {
        try {
          const r = await api("POST", "lifecycle/promote", Object.assign(req(), { target_url: target.value.trim(), api_key_ref: key.value.trim(), conflict: conflict.value, production: live.checked }));
          const res = r.result && r.result.results ? r.result.results : [];
          if (r.ok) { closeModal(); toast("Promoted: " + res.map((x) => botName(x.path) + " " + x.status).join(", ")); }
          else toast("Not promoted (" + r.status + "): " + (res.filter((x) => x.error).map((x) => botName(x.path) + ": " + x.error.message).join("; ") || JSON.stringify(r.result).slice(0, 200)), true);
        } catch (e) { toast(e.message, true); } } }, "Promote"))],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const b = await api("POST", "lifecycle/export", req()); download("agents-" + new Date().toISOString().slice(0, 10) + ".ooabundle.json", JSON.stringify(b, null, 2), "application/json"); toast("Exported " + b.agents.length + " agents"); }
    catch (e) { toast(e.message, true); } } }, "Download bundle")]);
}

function importDialog() {
  const file = h("input", { type: "file", accept: ".json,application/json", "aria-label": "Bundle file" });
  const folder = h("input", { placeholder: "(keep the folders of the bundle)" });
  const conflict = h("select", {}, h("option", { value: "skip", text: "Skip agents that are already here" }), h("option", { value: "overwrite", text: "Add a new version of agents that are already here" }));
  const live = h("input", { type: "checkbox" });
  const out = h("div");
  modal("Import agents", [h("p", { class: "muted", text: "Import a bundle exported from a Command Center. Every agent is checked first (checksum, validation, folder permissions); nothing is imported unless all of them pass. History is kept: an agent that exists gets a new version." }),
    file, h("div", { class: "grid2" }, h("label", {}, "Put them under folder", folder), h("label", {}, "If an agent is already here", conflict), h("label", { class: "check" }, live, "Make the imported versions live")), out],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const f = file.files[0]; if (!f) throw new Error("Choose a bundle file");
      const bundle = JSON.parse(await f.text());
      const r = await api("POST", "lifecycle/import", { bundle, conflict: conflict.value, production: live.checked, target_folder: folder.value.trim() || null });
      out.replaceChildren(table(["Agent", "Result", "Version", "Problem"], r.results.map((x) => ({ data: x, cells: [botName(x.path), pill(x.status === "created" || x.status === "updated" ? "success" : x.status === "skipped" ? "stopped" : "failed"),
        x.version ? "v" + x.version : "", x.error ? x.error.message : x.status] }))));
      toast(r.ok ? "Imported " + r.imported + " agents" : "Nothing imported: fix the problems listed", !r.ok);
      if (r.ok) go("automation");
    } catch (e) { toast(e.message, true); } } }, "Import")]);
}

// ---------- work queues: models, members, items ----------
async function modelsDialog() {
  try { S.models = (await api("GET", "queue-models")).models; } catch (e) { toast(e.message, true); return; }
  modal("Work item models", [h("p", { class: "muted", text: "A model names the fields of a queue's items and their types. Items added by hand, from CSV, by agents or over the API are checked against it." }),
    S.models.length ? table(["Model", "Fields", "Queues", ""], S.models.map((m) => ({ data: m, cells: [h("strong", { text: m.name }), m.fields.map((f) => f.name + (f.required ? "*" : "") + " (" + f.type + ")").join(", "), String(m.queues),
      can("queues.manage") ? h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => modelDialog(m) }, "Edit"),
        h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete model " + m.name + "?")) return; try { await api("DELETE", "queue-models/" + m.id); modelsDialog(); } catch (e) { toast(e.message, true); } } }, "Delete")) : ""] })))
      : h("p", { class: "muted", text: "No models yet." })],
  can("queues.manage") ? [h("button", { type: "button", class: "primary", onclick: () => modelDialog() }, "New model")] : []);
}

function modelDialog(m) {
  const name = h("input", { value: m ? m.name : "", placeholder: "Invoice" }), desc = h("input", { value: m ? m.description || "" : "" });
  const fields = m ? m.fields.map((f) => Object.assign({}, f)) : [{ name: "", type: "text", required: true, display: true }];
  const box = h("div");
  const draw = () => box.replaceChildren(table(["Field", "Type", "Required", "Shown in the list", ""], fields.map((f, i) => {
    const n = h("input", { value: f.name, placeholder: "invoice_no", "aria-label": "Field name", oninput: (e) => { f.name = e.target.value.trim(); } });
    const t = h("select", { "aria-label": "Field type", onchange: (e) => { f.type = e.target.value; } }, [["text", "Text"], ["number", "Number"], ["boolean", "True/false"], ["date", "Date"]].map(([v, l]) => h("option", { value: v, text: l })));
    t.value = f.type;
    return { data: f, cells: [n, t, h("input", { type: "checkbox", checked: !!f.required, "aria-label": "Required", onchange: (e) => { f.required = e.target.checked; } }),
      h("input", { type: "checkbox", checked: f.display !== false, "aria-label": "Shown in the list", onchange: (e) => { f.display = e.target.checked; } }),
      h("button", { type: "button", onclick: () => { fields.splice(i, 1); draw(); } }, "Remove")] };
  })));
  draw();
  modal(m ? "Edit model " + m.name : "New work item model", [h("div", { class: "grid2" }, h("label", {}, "Name", name), h("label", {}, "Description", desc)), box,
    h("button", { type: "button", onclick: () => { fields.push({ name: "", type: "text", required: false, display: true }); draw(); } }, "+ Field"),
    m ? h("p", { class: "muted", text: "While queues using this model have items, fields can be added (optional) but not removed, made required or given another type." }) : null].filter(Boolean),
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api(m ? "PUT" : "POST", "queue-models" + (m ? "/" + m.id : ""), { name: name.value, description: desc.value, fields }); modelsDialog(); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

async function membersDialog(q) {
  let full;
  try { full = await api("GET", "queues/" + q.id); } catch (e) { toast(e.message, true); return; }
  const rows = full.members.map((x) => ({ principal: x.principal, role: x.member_role }));
  const box = h("div");
  const ROLES = [["owner", "Owner (manages the queue)"], ["participant", "Participant (adds and changes items)"], ["consumer", "Consumer (processes the items)"]];
  const draw = () => box.replaceChildren(rows.length ? table(["Who", "Member as", ""], rows.map((r, i) => ({ data: r, cells: [r.principal, (ROLES.find((x) => x[0] === r.role) || [0, r.role])[1],
    h("button", { type: "button", onclick: () => { rows.splice(i, 1); draw(); } }, "Remove")] })))
    : h("p", { class: "muted", text: "No members: everyone whose role allows it can use this queue." }));
  const who = h("input", { placeholder: "user:jane or role:bot_runner", value: "user:" + S.user.username });
  const role = h("select", {}, ROLES.map(([v, l]) => h("option", { value: v, text: l })));
  draw();
  modal("Members of " + q.name, [h("p", { class: "muted", text: "With members, only they can use the queue: owners everything, participants add and change items, consumers start processing and retry items. Keep yourself as an owner." }),
    box, h("div", { class: "grid2" }, h("label", {}, "Who", who), h("label", {}, "As", role)),
    h("button", { type: "button", onclick: () => { const p = who.value.trim(); if (p && !rows.some((x) => x.principal === p && x.role === role.value)) rows.push({ principal: p, role: role.value }); draw(); } }, "+ Add")],
  [h("button", { type: "button", class: "primary", onclick: async () => { try { await api("PUT", "queues/" + q.id + "/members", { members: rows }); closeModal(); toast("Members saved"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

async function itemDialog(q, itemId, after) {
  let it, full;
  try { [it, full] = await Promise.all([api("GET", "queues/" + q.id + "/items/" + itemId), api("GET", "queues/" + q.id)]); } catch (e) { toast(e.message, true); return; }
  const fields = full.model ? full.model.fields : null;
  const locked = it.status === "in_progress";
  const inputs = {};
  const dataBox = fields ? h("div", { class: "grid2" }, fields.map((f) => {
    const v = it.data[f.name];
    const el = f.type === "boolean" ? h("select", { disabled: locked }, h("option", { value: "", text: "(empty)" }), h("option", { value: "true", text: "True" }), h("option", { value: "false", text: "False" }))
      : h("input", { type: f.type === "number" ? "number" : f.type === "date" ? "date" : "text", value: v === undefined || v === null ? "" : String(v), disabled: locked });
    if (f.type === "boolean") el.value = v === true ? "true" : v === false ? "false" : "";
    inputs[f.name] = el;
    return h("label", {}, h("span", {}, f.name, f.required ? h("span", { class: "req", text: " *" }) : null, h("span", { class: "type", text: f.type })), el);
  })) : h("textarea", { rows: 8, disabled: locked, value: JSON.stringify(it.data, null, 2) });
  const ref = h("input", { value: it.reference || "", disabled: locked }), prio = h("input", { type: "number", value: String(it.priority), disabled: locked });
  modal("Work item " + (it.reference || it.id), [kv([["Status", pill(it.status)], ["Attempts", String(it.attempts)], ["Added", local(it.created)], ["Finished", local(it.finished)]]),
    h("div", { class: "grid2" }, h("label", {}, "Reference", ref), h("label", {}, "Priority", prio)), h("h3", { text: "Data" }), dataBox,
    it.result ? [h("h3", { text: "Result" }), h("pre", { class: "mono", text: JSON.stringify(it.result, null, 2) })] : null,
    it.error ? [h("h3", { text: "Error" }), h("pre", { class: "mono error", text: JSON.stringify(it.error, null, 2) })] : null,
    locked ? h("p", { class: "muted", text: "The item is being worked on; it can be changed after its run ends." }) : null].flat().filter(Boolean),
  locked || !can("queues.work") ? [] : [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      let data;
      if (fields) { data = {}; for (const f of fields) { const t = inputs[f.name].value; if (t !== "") data[f.name] = f.type === "number" ? Number(t) : f.type === "boolean" ? t === "true" : t; } }
      else data = parseJson(dataBox.value, {});
      await api("PUT", "queues/" + q.id + "/items/" + itemId, { data, reference: ref.value.trim() || null, priority: Number(prio.value || 0) });
      closeModal(); toast("Item saved"); if (after) after();
    } catch (e) { toast(e.message, true); } } }, "Save"),
    h("button", { type: "button", class: "danger", onclick: async () => {
      if (!confirm("Delete this item?")) return;
      try { await api("POST", "queues/" + q.id + "/items/delete", { ids: [itemId] }); closeModal(); if (after) after(); } catch (e) { toast(e.message, true); } } }, "Delete")]);
}

async function exportItems(q, status) {
  try {
    const res = await fetch("/api/queues/" + q.id + "/items/export" + (status ? "?status=" + encodeURIComponent(status) : ""), { credentials: "same-origin" });
    if (!res.ok) { const e = (await res.json().catch(() => ({}))).error || {}; throw new Error(e.message || res.statusText); }
    download(q.name.replace(/[^A-Za-z0-9_.-]+/g, "_") + "-items.csv", await res.text(), "text/csv");
  } catch (e) { toast(e.message, true); }
}

function roleDialog(role, permissions) {
  const name = h("input", { value: role ? role.name : "", placeholder: "invoice_clerk", disabled: !!role }), desc = h("input", { value: role ? role.description || "" : "" });
  const groups = {};
  for (const p of permissions) (groups[p.split(".")[0]] = groups[p.split(".")[0]] || []).push(p);
  const boxes = [];
  const body = Object.entries(groups).map(([g, ps]) => h("fieldset", { class: "perm-group" }, h("legend", { text: g }), ps.map((p) => {
    const c = h("input", { type: "checkbox", value: p, checked: !!(role && role.permissions.includes(p)) }); boxes.push(c);
    return h("label", { class: "check" }, c, p.split(".")[1]);
  })));
  modal(role ? "Edit role " + role.name : "New role", [h("div", { class: "grid2" }, h("label", {}, "Name (letters, digits, _)", name), h("label", {}, "Description", desc)),
    h("div", { class: "perm-grid" }, body)],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PUT", "roles/" + encodeURIComponent(name.value.trim()), { description: desc.value, permissions: boxes.filter((c) => c.checked).map((c) => c.value) }); closeModal(); go(S.adminTab || "settings"); }
    catch (e) { toast(e.message, true); } } }, "Save role")]);
}

function connectionDialog(c) {
  const name = h("input", { value: c ? c.name : "", placeholder: "company-gpt", disabled: !!c });
  const provider = h("select", {}, [["openai", "OpenAI"], ["azure_openai", "Azure OpenAI"], ["anthropic", "Anthropic"], ["openai_compatible", "OpenAI-compatible (local or hosted)"]].map(([v, l]) => h("option", { value: v, text: l })));
  const model = h("input", { value: c ? c.model || "" : "", placeholder: "model or deployment name" }), base = h("input", { value: c ? c.base_url || "" : "", placeholder: "https://... (optional)" });
  const key = h("input", { value: c ? c.key_ref || "" : "", placeholder: "vault folder/credential/attribute" }), desc = h("input", { value: c ? c.description || "" : "" });
  if (c) provider.value = c.provider;
  modal(c ? "Edit " + c.name : "New One Model Connection", [h("div", { class: "grid2" }, h("label", {}, "Name", name), h("label", {}, "Provider", provider), h("label", {}, "Model", model), h("label", {}, "Endpoint", base)),
    h("label", {}, "API key from the One Agent Vault", key), h("label", {}, "Description", desc),
    h("p", { class: "muted", text: "Only the vault reference is stored here. Runners read the key from the vault while an agent runs, and only for agents whose owner may use that vault folder." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PUT", "connections/" + encodeURIComponent(name.value.trim()), { provider: provider.value, model: model.value.trim() || null, base_url: base.value.trim() || null, key_ref: key.value.trim() || null, description: desc.value });
      closeModal(); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } }, "Save connection")]);
}

async function apiKeysDialog() {
  const r = await api("GET", "apikeys");
  const name = h("input", { placeholder: "Nightly ERP sync" }), days = h("select", {}, [["30", "30 days"], ["90", "90 days"], ["365", "1 year"], ["0", "Never expires"]].map(([v, l]) => h("option", { value: v, text: l })));
  days.value = "90";
  modal("Your API keys", [h("p", { class: "muted", text: "API keys let other systems call the Command Center as you (Authorization: Bearer <key>), with your role's permissions. A key is shown only once." }),
    table(["Name", "Created", "Expires", "Last used", ""], r.keys.map((k) => ({ data: k, cells: [h("strong", { text: k.name }), ago(k.created), k.expires ? local(k.expires) : "never", k.last_used ? ago(k.last_used) : "never",
      h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Revoke key " + k.name + "? Systems using it stop working.")) { await api("DELETE", "apikeys/" + k.id); apiKeysDialog(); } } }, "Revoke")] }))),
    h("div", { class: "card inset" }, h("h3", { text: "New key" }), h("div", { class: "grid2" }, h("label", {}, "Name", name), h("label", {}, "Valid for", days)))],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const k = await api("POST", "apikeys", { name: name.value, days: Number(days.value) });
      modal("API key created", [secretBox(k.name || name.value, k.key, "Copy it now and store it in your system's secret store. It is not shown again.")], [h("button", { type: "button", onclick: apiKeysDialog }, "Back to keys")]); }
    catch (e) { toast(e.message, true); } } }, "Create key")]);
}

/** Value tracking on the agent drawer: minutes of manual work a successful run saves. */
function roiBox(b) {
  const input = h("input", { type: "number", min: "0", value: b.minutes_saved === null || b.minutes_saved === undefined ? "" : String(b.minutes_saved), placeholder: "default from Settings", "aria-label": "Minutes saved per run" });
  return h("div", { class: "card inset roi" }, h("h3", {}, ic("timer", "#16a34a", "sm"), "Value"),
    h("label", {}, "Minutes of manual work one successful run saves", input),
    can("repo.production") ? h("button", { type: "button", onclick: async () => {
      try { await api("POST", "repo/roi", { path: b.path, minutes_saved: input.value === "" ? null : Number(input.value) }); b.minutes_saved = input.value === "" ? null : Number(input.value); toast("Saved; Home shows hours saved"); }
      catch (e) { toast(e.message, true); } } }, "Save") : null);
}

// ---------- startup ----------
// Light or dark theme, remembered per browser (falls back to the operating system's preference).
const THEME_KEY = "ooa.cc.theme";
function applyTheme(t) { document.body.dataset.theme = t; try { localStorage.setItem(THEME_KEY, t); } catch (_) { /* storage unavailable */ } }
function initialTheme() {
  try { const t = localStorage.getItem(THEME_KEY); if (t === "light" || t === "dark") return t; } catch (_) { /* storage unavailable */ }
  return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

async function boot() {
  applyTheme(initialTheme());
  $("btn-theme").addEventListener("click", () => applyTheme(document.body.dataset.theme === "dark" ? "light" : "dark"));
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
  $("btn-apikeys").addEventListener("click", () => apiKeysDialog().catch((e) => toast(e.message, true)));
  $("modal").addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });
  $("drawer-close").addEventListener("click", closeDrawer);
  $("drawer").addEventListener("click", (e) => { if (e.target === $("drawer")) closeDrawer(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && $("modal").hidden && !$("drawer").hidden) closeDrawer(); });
  try { S.user = await api("GET", "auth/me"); if (S.user.must_change) showPassword(); else showApp(); } catch (_) { showLogin(); }
}

if (typeof window !== "undefined" && !window.__OOA_TEST__) boot();
