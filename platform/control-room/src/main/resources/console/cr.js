/* ONEOFFICEAUTOMATION Control Room console. All data is rendered with textContent (never innerHTML). */
"use strict";
const $ = (id) => document.getElementById(id);
// Optional parts are written as `cond ? node : null`; skip null/false (and flatten arrays) instead of printing "null".
for (const m of ["append", "replaceChildren"]) {
  const orig = Element.prototype[m];
  Element.prototype[m] = function (...nodes) { return orig.apply(this, nodes.flat(Infinity).filter((n) => n !== null && n !== undefined && n !== false)); };
}

const S = { user: null, page: "home", timer: null, drawerTimer: null, devices: [], bots: [], users: [], names: {} };
// A link such as /?page=queues (from the new Studio menu) opens that page after sign-in.
const LINKED_PAGE = new URLSearchParams(location.search).get("page");

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
  chevron: "M9 6l6 6-6 6", "chevron-left": "M15 6l-6 6 6 6", search: "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4", link: "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1",
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
    if (e.code === "CR.MFA_SETUP_REQUIRED" && $("modal").hidden) { mfaDialog(true).catch(() => {}); }
    const err = new Error((e.message || res.statusText) + (e.suggestedFix ? " (" + e.suggestedFix + ")" : ""));
    err.code = e.code; throw err;
  }
  return data;
}

const can = (p) => S.user && (S.user.permissions || []).includes(p);
/** A page's permission: one of several (a|b); team_lead is anyone who leads a team. */
const canAny = (spec) => String(spec).split("|").some((p) => p === "team_lead" ? !!(S.user && S.user.leads_team) : can(p));
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
    const saml = r.saml && r.saml.enabled;
    box.replaceChildren(...(r.enabled || saml ? [h("div", { class: "sso-or", text: "or" })] : []),
      ...(r.enabled ? [h("a", { class: "button-link sso-button", href: "/api/auth/sso/start" }, ic("key", "#2563eb", "sm"), r.label)] : []),
      ...(saml ? [h("a", { class: "button-link sso-button", href: "/api/auth/saml/start" }, ic("key", "#2563eb", "sm"), r.saml.label)] : []));
  }).catch(() => {});
}
/** After the password: the code box replaces the user name and password (token = null goes back). */
function loginCodeStep(token) {
  S.mfaToken = token;
  for (const id of ["login-user", "login-pass"]) { $(id).closest("label").hidden = !!token; $(id).required = !token; }
  $("login-code-row").hidden = !token; $("login-code").required = !!token; $("login-code").value = "";
  if (token) $("login-code").focus();
}

function afterSignIn() {
  if (S.user.must_change) { showPassword(); return; }
  showApp();
  watchChanges();
  if (S.user.mfa_setup_required) mfaDialog(true).catch((e) => toast(e.message, true));
}

/** Two-step sign-in for the signed-in person: set it up, see the recovery codes once, or turn it off. forced: required by the administrator. */
async function mfaDialog(forced) {
  const st = await api("GET", "auth/mfa/status");
  if (st.sso) { modal("Two-step sign-in", [h("p", { text: "You sign in through your organisation's identity provider, which handles two-step sign-in." })], []); return; }
  if (st.enabled) {
    const pw = h("input", { type: "password", autocomplete: "current-password" }), code = h("input", { inputmode: "numeric", autocomplete: "one-time-code", maxlength: "12" });
    modal("Two-step sign-in", [h("p", { text: "On. " + st.recovery_codes_left + " recovery code(s) left." }),
      st.required ? h("p", { class: "muted", text: "Your administrator requires it for your account, so it cannot be turned off. If you lose your phone, an administrator can reset it." })
        : [h("p", { class: "muted", text: "To turn it off, enter your password and a current code." }), h("label", {}, "Password", pw), h("label", {}, "Code", code)]],
      st.required ? [] : [h("button", { type: "button", class: "danger", onclick: async () => {
        try { await api("POST", "auth/mfa/disable", { password: pw.value, code: code.value }); closeModal(); toast("Two-step sign-in is off"); } catch (e) { toast(e.message, true); } } }, "Turn off")]);
    return;
  }
  const s = await api("POST", "auth/mfa/setup");
  const code = h("input", { inputmode: "numeric", autocomplete: "one-time-code", maxlength: "6" });
  modal(forced ? "Set up two-step sign-in (required)" : "Set up two-step sign-in", [
    forced ? h("p", { class: "note warn", text: "Your administrator requires a code from an authenticator app as well as your password." }) : null,
    h("p", { text: "1. In an authenticator app (Microsoft Authenticator, Google Authenticator or another TOTP app), add an account with this key:" }),
    h("p", {}, h("code", { class: "secret", text: s.secret })),
    h("p", { class: "muted" }, "On a phone you can also ", h("a", { href: s.otpauth_uri }, "open it in the app"), ". Type: time-based, 6 digits."),
    h("p", { text: "2. Enter the 6-digit code the app shows:" }), h("label", {}, "Code", code)],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const r = await api("POST", "auth/mfa/enable", { code: code.value });
      S.user = await api("GET", "auth/me");
      modal("Recovery codes", [h("p", { text: "Two-step sign-in is on. Keep these recovery codes somewhere safe: each one signs you in once if you lose your phone. They are shown only now." }),
        h("pre", { class: "secret", text: r.recovery_codes.join("\n") })], [h("button", { type: "button", class: "primary", onclick: () => { closeModal(); if (forced) go(S.page); } }, "I have saved them")]);
    } catch (e) { toast(e.message, true); } } }, "Turn on")]);
}

function showPassword() { $("app").hidden = true; $("login-view").hidden = true; $("password-view").hidden = false; $("pw-current").focus(); }
function showApp() {
  $("login-view").hidden = true; $("password-view").hidden = true; $("app").hidden = false;
  $("who").textContent = S.user.display_name || S.user.username;
  $("user-pop-head").replaceChildren(h("strong", { text: S.user.display_name || S.user.username }), h("span", { class: "muted", text: S.user.username + " \u00B7 " + roleLabel(S.user.role) }));
  $("avatar").textContent = (S.user.display_name || S.user.username || "?").trim().charAt(0).toUpperCase();
  api("GET", "health").then((r) => { $("btn-studio").hidden = !r.studio; S.studioAvailable = !!r.studio; renderNav(); }).catch(() => {});
  api("GET", "catalog").then((r) => { S.names = r.actions || {}; }).catch(() => {});
  api("GET", "auth/me").then((u) => { showAnnouncement(u.announcement); showLicenseBanner(u.license_banner); }).catch(() => {});
  document.body.classList.toggle("nav-mini", stored(NAV_MINI_KEY) === "1");
  if (LINKED_PAGE && Object.hasOwn(VIEWS, LINKED_PAGE) && S.page === "home") S.page = LINKED_PAGE;
  renderNav(); go(S.page);
}

const PAGES = [
  ["home", "Home", null, "home", "#2563eb"], ["inbox", "Task inbox", null, "tasks", "#0d9488"], ["automation", "Agent Repository", "repo.view", "bot", "#6d28d9"], ["templates", "Agent Templates", "repo.view", "download", "#0d9488"], ["activity", "Agent Runs", "runs.view", "pulse", "#ea580c"],
  ["devices", "Runner Machines", "devices.view", "desktop", "#0891b2"], ["schedules", "Schedules", "schedules.view", "clock", "#059669"], ["triggers", "Triggers", "triggers.view", "bolt", "#d97706"],
  ["queues", "Work queues", "queues.view", "inbox", "#9333ea"], ["workflows", "Workflow", "runs.view", "process", "#7c3aed"],
  ["credentials", "Credential vault", null, "lock", "#dc2626"], ["globals", "Shared Global values", "globals.view", "globe", "#0284c7"], ["savedconn", "Connections", "globals.view", "link", "#0891b2"], ["users", "Users", "users.manage", "users", "#7c3aed"],
  ["audit", "Audit Trail", "audit.view", "shield", "#475569"], ["governance", "AI governance", "audit.view", "shieldai", "#be185d"],
  ["roles", "Roles", "users.manage", "users", "#6d28d9"], ["teams", "Teams", "teams.manage|team_lead", "users", "#0d9488"], ["toolkits", "Agent ToolKits", "toolkits.manage", "bolt", "#0d9488"],
  ["operations", "Operations", "analytics.view", "percent", "#0891b2"], ["apitasks", "Agent APIs", "apitasks.manage", "code", "#2563eb"],
  ["skills", "Prompt Skills", "skills.view", "shieldai", "#be185d"], ["docautomation", "Document Automation", "repo.view", "form", "#6d28d9"], ["tests", "Agent tests", "repo.view", "check", "#16a34a"], ["dashboards", "Dashboards", "analytics.view", "percent", "#0891b2"],
  ["connections", "Model Connections", "connections.manage", "shieldai", "#7c3aed"], ["settings", "Settings", "settings.manage", "gear", "#334155"],
  ["admin", "Administration", "settings.manage", "gear", "#334155"], ["licenses", "Licenses", "users.manage|settings.manage", "key", "#b45309"]];

/**
 * The menu (V6.1), grouped by task as Studio's is: Home, then Build (make agents), Manage (run them and what they
 * use), Insights (how they do), Administration (people, the vault, audit, licences, settings) and AI, shown in
 * capitals. The section of the current page is open, and the one opened last; the others show only their name.
 * Pages not listed here (admin) are reached from inside other pages.
 */
const NAV_GROUPS = [
  ["automation", "Build", "bot", ["automation", "templates", "studio", "apitasks", "tests"]],
  ["manage", "Manage", "desktop", ["activity", "inbox", "devices", "schedules", "triggers", "queues", "workflows", "globals", "savedconn", "toolkits"]],
  ["insights", "Operational Insights", "percent", ["operations", "dashboards"]],
  ["administration", "Administration", "gear", ["users", "roles", "teams", "credentials", "audit", "licenses", "settings"]],
  ["ai", "AI", "shieldai", ["connections", "skills", "docautomation", "governance"]],
];
const NAV_OPEN_KEY = "ooa.cc.navOpen", NAV_MINI_KEY = "ooa.cc.navMini";
function store(key, value) { try { localStorage.setItem(key, value); } catch (_) { /* storage unavailable */ } }
function stored(key) { try { return localStorage.getItem(key) || ""; } catch (_) { return ""; } }
function groupOf(page) { return NAV_GROUPS.find((g) => g[3].includes(page)) || null; }
function pageLabel(id) { if (id === "studio") return "One AgentStudio"; const p = PAGES.find((x) => x[0] === id); return p ? p[1] : id; }

function navButton(id) {
  if (id === "studio") {
    if (!S.studioAvailable) return null;
    const a = h("a", { class: "nav-link", href: "/studio/", target: "_blank", rel: "noopener", "aria-label": "One AgentStudio", title: "Build and test agents in One AgentStudio (opens in a new tab)" },
      ic("code", "#2563eb"), h("span", { class: "nav-label", text: "One AgentStudio" }), h("span", { class: "nav-ext", "aria-hidden": "true", text: "\u2197" }));
    return a;
  }
  const page = PAGES.find((x) => x[0] === id);
  if (!page || (page[2] && !canAny(page[2]))) return null;
  const [, label, , icon] = page;
  return h("button", { type: "button", class: S.page === id ? "active" : "", "aria-current": S.page === id ? "page" : null, "aria-label": label, title: label, onclick: () => go(id) },
    ic(icon, "#fff"), h("span", { class: "nav-label", text: label }));
}

function renderNav() {
  const q = (S.navQuery || "").trim().toLowerCase();
  const search = h("label", { class: "nav-search" }, svg("search"), h("input", { type: "search", placeholder: "Find a page", "aria-label": "Find a page", value: S.navQuery || "",
    oninput: (e) => { S.navQuery = e.target.value; renderNav(); const i = $("nav").querySelector(".nav-search input"); i.focus(); i.setSelectionRange(i.value.length, i.value.length); },
    onkeydown: (e) => {
      if (e.key === "Enter") { const first = $("nav").querySelector(".nav-items button, .nav-items a"); if (first) first.click(); }
      if (e.key === "Escape") { S.navQuery = ""; renderNav(); }
    } }));
  const parts = [search, navButton("home")];
  if (q) {
    // a search lists the matching pages of every section, open
    for (const [, glabel, , ids] of NAV_GROUPS) {
      const items = ids.filter((id) => (pageLabel(id) + " " + glabel).toLowerCase().includes(q)).map(navButton).filter(Boolean);
      if (items.length) parts.push(h("div", { class: "nav-section open", role: "group", "aria-label": glabel }, h("div", { class: "nav-found", text: glabel }), h("div", { class: "nav-items" }, items)));
    }
    if (parts.length === 2) parts.push(h("p", { class: "nav-empty", text: "No page matches \u201C" + S.navQuery.trim() + "\u201D." }));
  } else {
    const current = groupOf(S.page);
    const chosen = stored(NAV_OPEN_KEY);
    for (const [gid, glabel, icon, ids] of NAV_GROUPS) {
      if (!ids.map(navButton).some(Boolean)) continue;
      const isCurrent = !!current && current[0] === gid, isOpen = isCurrent || chosen === gid;
      const head = h("button", { type: "button", class: "nav-group" + (isOpen ? " open" : "") + (isCurrent ? " current" : ""), "aria-expanded": String(isOpen), "data-group": gid, title: glabel,
        onclick: () => { store(NAV_OPEN_KEY, chosen === gid && !isCurrent ? "" : gid); renderNav(); } },
        ic(icon, "#fff"), h("span", { class: "nav-label", text: glabel }), h("span", { class: "nav-caret", "aria-hidden": "true" }, svg("chevron")));
      // in the narrow menu, a section's pages pop out beside its icon
      const fly = h("div", { class: "nav-fly", role: "group", "aria-label": glabel }, h("div", { class: "nav-fly-title", text: glabel }), ids.map(navButton).filter(Boolean));
      parts.push(h("div", { class: "nav-section" + (isOpen ? " open" : "") + (isCurrent ? " current" : ""), role: "group", "aria-label": glabel }, head,
        isOpen ? h("div", { class: "nav-items" }, ids.map(navButton).filter(Boolean)) : null, fly));
    }
  }
  const mini = document.body.classList.contains("nav-mini");
  parts.push(h("div", { class: "nav-foot" }, h("button", { type: "button", class: "nav-collapse", title: mini ? "Show the full menu" : "Make the menu narrow (icons only)",
    "aria-label": mini ? "Show the full menu" : "Make the menu narrow",
    onclick: () => { const m = !document.body.classList.contains("nav-mini"); document.body.classList.toggle("nav-mini", m); store(NAV_MINI_KEY, m ? "1" : ""); renderNav(); } },
    svg(mini ? "chevron" : "chevron-left"), h("span", { class: "nav-label", text: mini ? "Full menu" : "Narrow menu" }))));
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

/** The license banner: a warning can be put away for this browser session; a critical one stays. */
function showLicenseBanner(b) {
  const bar = $("license-banner");
  if (!bar) return;
  let dismissed = null;
  try { dismissed = sessionStorage.getItem("ooa.cc.licenseBanner"); } catch (_) { /* storage unavailable */ }
  if (!b || (b.level !== "critical" && dismissed === b.text)) { bar.hidden = true; return; }
  bar.classList.toggle("critical", b.level === "critical");
  bar.replaceChildren(ic("key", b.level === "critical" ? "#dc2626" : "#d97706", "sm"), h("span", { class: "ann-text", text: b.text }),
    canAny("users.manage|settings.manage") ? h("button", { type: "button", class: "link", onclick: () => go("licenses") }, "Licenses") : null,
    b.level === "critical" ? null : h("button", { type: "button", class: "icon-close", "aria-label": "Dismiss", onclick: () => { bar.hidden = true; try { sessionStorage.setItem("ooa.cc.licenseBanner", b.text); } catch (_) { /* storage unavailable */ } } }, "\u2715"));
  bar.hidden = false;
}

/** Tab strip; items are [id, label]. */
function tabStrip(items, current, onPick) {
  return h("div", { class: "tabs", role: "tablist" }, items.map(([id, label]) => h("button", { type: "button", role: "tab", class: "tab" + (current === id ? " active" : ""),
    "aria-selected": String(current === id), onclick: () => onPick(id) }, label)));
}

function stopTimer() { if (S.timer) { clearInterval(S.timer); S.timer = null; } S.live = null; }

/**
 * Live updates: the open page's refresh (S.live) runs as soon as the Command Center reports a change (a long request
 * to /api/changes that it answers at once when something changes), at most every 1.5 seconds. Each page's own timer
 * stays as a slower fallback. Started once after sign-in; stops when the person is signed out.
 */
function every(cb, ms) { S.live = cb; return setInterval(cb, S.liveOk ? Math.max(ms * 4, 20000) : ms); }

async function watchChanges() {
  if (S.watching) return;
  S.watching = true;
  let token = "", last = 0, failures = 0;
  while (S.user) {
    try {
      const r = await api("GET", "changes?wait=25&since=" + encodeURIComponent(token));
      failures = 0; S.liveOk = true;
      token = r.token;
      if (r.changed && S.live && !document.hidden) {
        const wait = Math.max(0, 1500 - (Date.now() - last));
        if (wait) await new Promise((res) => setTimeout(res, wait));
        last = Date.now();
        try { await S.live(); } catch (_) { /* the fallback timer tries again */ }
      }
    } catch (e) {
      if (["CR.UNAUTHENTICATED", "CR.PASSWORD_CHANGE_REQUIRED", "CR.MFA_SETUP_REQUIRED", "CR.LICENSE_EXPIRED"].includes(e.code)) break;
      failures++; S.liveOk = false;
      await new Promise((res) => setTimeout(res, Math.min(30000, 2000 * failures)));
    }
  }
  S.watching = false;
}

/**
 * Pages that show live figures but have no live view of their own are drawn again quietly while they stay open
 * (milliseconds between redraws). Pages with their own timer (runs, runners, queues, inbox, workflows, triggers) and
 * pages with forms or local filters (settings, audit trail) are not listed.
 */
const AUTO_REFRESH = { home: 5000, automation: 15000, schedules: 15000, apitasks: 15000, pools: 15000, operations: 30000, governance: 30000, skills: 30000 };

async function go(page) {
  stopTimer();
  closeDrawer();
  S.page = page; renderNav();
  S.dtStates = {}; S.dtSeq = 0;
  // Each visit renders into its own container: a slow page that finishes after the user moved on writes into a detached element.
  const main = h("div", { class: "view" }, h("p", { class: "muted", text: "Loading..." }));
  $("main").replaceChildren(main);
  try { await VIEWS[page](main); } catch (e) { main.replaceChildren(h("p", { class: "error", text: e.message })); }
  if (main.isConnected) $("main").focus();
  if (main.isConnected && AUTO_REFRESH[page] && !S.timer) S.timer = every(() => quietRefresh(page).catch(() => {}), AUTO_REFRESH[page]);
}

/**
 * Draws the open page again off screen and swaps it in, keeping the scroll position and each table's search, sort and
 * page. Skipped while a dialog or drawer is open, while someone types in the page, or while text is selected.
 */
async function quietRefresh(page) {
  if (S.page !== page || S.refreshing || !$("modal").hidden || !$("drawer").hidden || document.hidden) return;
  const a = document.activeElement;
  if (a && $("main").contains(a) && a.matches("input, select, textarea")) return;
  if (String(window.getSelection() || "")) return;
  S.refreshing = true; S.dtSeq = 0;
  try {
    const next = h("div", { class: "view" });
    await VIEWS[page](next);
    if (S.page !== page || !$("modal").hidden || !$("drawer").hidden) return;
    const y = window.scrollY, inMain = a && $("main").contains(a);
    $("main").replaceChildren(next);
    window.scrollTo(0, y);
    if (inMain) $("main").focus({ preventScroll: true });
  } finally { S.refreshing = false; }
}

function header(title, sub, ...actions) {
  const meta = PAGES.find((p) => p[1] === title || p[0] === S.page) || PAGES[0];
  const group = groupOf(S.page);
  const crumbs = S.page === "home" ? null : h("nav", { class: "crumbs", "aria-label": "Breadcrumb" },
    h("a", { href: "#", onclick: (e) => { e.preventDefault(); go("home"); } }, "Home"), h("span", { "aria-hidden": "true", text: "\u203A" }),
    group ? h("span", { text: group[1] }) : null, group ? h("span", { "aria-hidden": "true", text: "\u203A" }) : null, h("span", { "aria-current": "page", text: title }));
  return [h("div", { class: "page-head" }, ic(meta[3], meta[4], "lg"), h("div", { class: "page-titles" }, crumbs, h("h1", { class: "page", text: title }), h("p", { class: "page-sub", text: sub })),
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
  // a quiet refresh of the page (quietRefresh) draws the tables again in the same order: each keeps its search, sort and page
  const key = S.page + ":" + (S.dtSeq = (S.dtSeq || 0) + 1);
  S.dtStates = S.dtStates || {};
  const st = S.refreshing && S.dtStates[key] ? S.dtStates[key] : { q: "", sort: null, dir: 1, page: 0 };
  S.dtStates[key] = st;
  const wrap = h("div", { class: "dt" });
  const box = h("div");
  const q = search ? h("input", { type: "search", placeholder: search, "aria-label": search, value: st.raw || "" }) : null;
  if (q) q.addEventListener("input", () => { st.raw = q.value; st.q = q.value.trim().toLowerCase(); st.page = 0; render(); });
  const value = (c, r) => { const v = c.value ? c.value(r) : ""; return v === null || v === undefined ? "" : v; };
  const list = () => {
    let l = rows.filter((r) => !st.q || columns.some((c) => String(value(c, r)).toLowerCase().includes(st.q)));
    if (st.sort !== null) { const c = columns[st.sort]; l = l.slice().sort((a, b) => { const x = value(c, a), y = value(c, b); return (typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true })) * st.dir; }); }
    return l;
  };
  // columns the viewer switched off, per table (only labelled columns; the actions column always shows)
  const colKey = exportName ? "ooa.columns." + exportName : null;
  let hidden = new Set();
  try { if (colKey) hidden = new Set(JSON.parse(localStorage.getItem(colKey) || "[]")); } catch (_) { /* storage off: all columns */ }
  const shown = () => columns.map((c, i) => i).filter((i) => !columns[i].label || !hidden.has(columns[i].label));
  const chooser = h("div", { class: "col-chooser", hidden: true });
  const colBtn = colKey && columns.filter((c) => c.label).length > 2 ? h("button", { type: "button", "aria-expanded": "false", onclick: (e) => {
    chooser.hidden = !chooser.hidden; e.currentTarget.setAttribute("aria-expanded", String(!chooser.hidden));
    chooser.replaceChildren(...columns.filter((c) => c.label).map((c) => h("label", { class: "check" }, h("input", { type: "checkbox", checked: !hidden.has(c.label), onchange: (ev) => {
      if (ev.target.checked) hidden.delete(c.label); else if (shown().filter((i) => columns[i].label).length > 1) hidden.add(c.label); else { ev.target.checked = true; return; }
      try { localStorage.setItem(colKey, JSON.stringify([...hidden])); } catch (_) { /* kept for this page only */ }
      render(); } }), c.label)));
  } }, "Columns") : null;
  const exp = exportName ? h("button", { type: "button", onclick: () => download(exportName + ".csv", csv([columns.filter((c) => c.value).map((c) => c.label), ...list().map((r) => columns.filter((c) => c.value).map((c) => value(c, r)))]), "text/csv") }, ic("download", "#475569", "sm"), "Export CSV") : null;
  function render() {
    const l = list(), pages = Math.max(1, Math.ceil(l.length / pageSize));
    if (st.page >= pages) st.page = pages - 1;
    const slice = l.slice(st.page * pageSize, st.page * pageSize + pageSize);
    const vis = shown();
    const ths = vis.map((i) => [columns[i], i]).map(([c, i]) => c.sort === false || !c.value ? h("th", { text: c.label })
      : h("th", { "aria-sort": st.sort === i ? (st.dir > 0 ? "ascending" : "descending") : "none" },
        h("button", { type: "button", class: "th-sort", onclick: () => { if (st.sort === i) st.dir = -st.dir; else { st.sort = i; st.dir = 1; } render(); } }, c.label, h("span", { class: "arrow", text: st.sort === i ? (st.dir > 0 ? " \u25B2" : " \u25BC") : "" }))));
    const body = slice.length ? slice.map((r) => {
      const tr = h("tr", { class: onRow ? "clickable" : "" }, vis.map((i) => columns[i]).map((c) => h("td", { class: c.cls || null }, c.render ? c.render(r) : String(value(c, r)))));
      if (onRow) { tr.tabIndex = 0; tr.addEventListener("click", (e) => { if (!e.target.closest("button, a, input, select")) onRow(r); }); tr.addEventListener("keydown", (e) => { if (e.key === "Enter" && e.target === tr) onRow(r); }); }
      return tr;
    }) : [h("tr", {}, h("td", { colspan: String(vis.length), class: "muted empty-cell", text: st.q ? "No matches." : empty }))];
    box.replaceChildren(h("div", { class: "table-wrap" }, h("table", {}, h("thead", {}, h("tr", {}, ths)), h("tbody", {}, body))),
      l.length > pageSize ? pager(st.page, pages, l.length, pageSize, (p) => { st.page = p; render(); }) : null);
  }
  wrap.append(h("div", { class: "toolbar" }, q, ...tools, h("span", { class: "spacer" }), colBtn, exp), chooser, box);
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
// the serverless virtual runner takes only serverless Agent APIs, so it is not offered as a runner
const deviceSelect = (val) => { const s = h("select", {}, S.devices.filter((d) => d.enabled && d.device_type !== "serverless").map((d) => h("option", { value: d.id, text: d.name + " (" + d.status + ")" }))); if (val) s.value = val; return s; };
function parseJson(text, fallback) { if (!text.trim()) return fallback; try { return JSON.parse(text); } catch (e) { throw new Error("Invalid JSON: " + e.message); } }
const deviceName = (id) => (S.devices.find((d) => d.id === id) || {}).name || id;

const VIEWS = {
  async home(main) {
    let tz = ""; try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch (_) { /* no time zone: the server counts days in UTC */ }
    const d = await api("GET", "dashboard" + (tz ? "?tz=" + encodeURIComponent(tz) : ""));
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
    const myAgents = d.my_recent_agents || [], myRuns = d.my_recent_runs || [];
    if (myAgents.length || myRuns.length) {
      parts.push(h("div", { class: "card" }, h("h2", { text: "Pick up where you left off" }), h("div", { class: "grid-2 flat" },
        h("div", {}, h("h3", { text: "Your agents" }), myAgents.length ? myAgents.map((b) => h("button", { type: "button", class: "list-row", onclick: () => openBot(b) }, ic("bot", "#6d28d9", "sm"),
          h("div", { class: "lr-main" }, h("strong", { text: botName(b.path) }), h("div", { class: "muted small", text: "v" + b.latest_version + (b.locked_by ? " \u00B7 checked out by " + b.locked_by : "") })),
          h("span", { class: "muted small", text: ago(b.updated) }))) : h("p", { class: "muted", text: "Agents you check in or check out show here." })),
        h("div", {}, h("h3", { text: "Your runs" }), myRuns.length ? myRuns.map((r) => h("button", { type: "button", class: "list-row", onclick: () => openRun(r.id) }, h("span", { class: "dot " + (r.status === "success" ? "online" : r.status === "failed" ? "offline" : "busy") }),
          h("div", { class: "lr-main" }, h("strong", { text: botName(r.bot_path) }), h("div", { class: "muted small", text: r.status })),
          h("span", { class: "muted small", text: ago(r.finished || r.created) }))) : h("p", { class: "muted", text: "Agents you run show here." })))));
    }
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

  /** Templates (K7): ready-made agents from ONEOFFICEAUTOMATION's catalogue and, in the SaaS service, the region's shared ones. */
  async templates(main) {
    const l = await api("GET", "templates");
    S.tplCategory = S.tplCategory || "";
    const q = h("input", { type: "search", placeholder: "Search templates", "aria-label": "Search templates", value: S.tplQuery || "" });
    const cat = h("select", { "aria-label": "Category" }, h("option", { value: "", text: "All categories" }), l.categories.map((c) => h("option", { value: c, text: c })));
    cat.value = S.tplCategory;
    const grid = h("div", { class: "tpl-grid" });
    const draw = () => {
      S.tplQuery = q.value; S.tplCategory = cat.value;
      const words = q.value.trim().toLowerCase();
      const shown = l.templates.filter((t) => (!cat.value || t.category === cat.value)
        && (!words || (t.name + " " + t.description + " " + t.publisher).toLowerCase().includes(words)));
      grid.replaceChildren(...(shown.length ? shown.map((t) => h("div", { class: "card" },
        h("div", { class: "muted small", text: t.category + " \u00B7 " + (t.source === "region" ? "shared by " + t.publisher : t.publisher) }),
        h("h3", { text: t.name }), h("p", { text: t.description || "" }),
        h("div", { class: "muted small", text: t.agents.length === 1 ? "1 agent: " + botName(t.agents[0]) : t.agents.length + " agents" }),
        can("lifecycle.import") ? h("button", { type: "button", class: "primary", onclick: () => installTemplate(t) }, "Install") : null))
        : [h("p", { class: "muted", text: l.templates.length ? "No template matches." : "No templates yet." })]));
    };
    q.addEventListener("input", draw); cat.addEventListener("change", draw);
    main.replaceChildren(...header("Agent Templates", "Ready-made agents to install into your agent repository and adapt: from ONEOFFICEAUTOMATION" + (l.can_share ? " and shared in your region" : "") + ".",
      l.can_share ? h("button", { type: "button", onclick: () => shareTemplateDialog() }, "Share an agent") : null,
      can("settings.manage") ? h("button", { type: "button", onclick: () => uploadCatalogueDialog() }, "Upload a catalogue file") : null),
      l.problem ? h("p", { class: "error", text: l.problem }) : null,
      h("div", { class: "toolbar" }, q, cat), grid);
    draw();
  },

  async automation(main) {
    await loadRefs();
    S.repoFolder = S.repoFolder || "";
    const [r, f, pr] = await Promise.all([api("GET", "repo" + (S.repoFolder ? "?folder=" + encodeURIComponent(S.repoFolder) : "")), api("GET", "repo/folders"),
      api("GET", "repo/production-requests?status=pending").catch(() => ({ requests: [], approval_on: false }))]);
    S.approvalOn = pr.approval_on;
    const toDecide = pr.requests.filter((x) => x.can_decide).length;
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
      S.repoFolder && can("repo.checkin") ? h("button", { type: "button", onclick: async () => {
        const to = prompt("Rename or move the folder " + S.repoFolder + " (with everything in it) to:", S.repoFolder);
        if (!to || to.trim() === S.repoFolder) return;
        try { const r = await api("POST", "repo/folders/rename", { from: S.repoFolder, to: to.trim() }); toast("Moved " + r.agents + " agent(s) to " + r.path); S.repoFolder = r.path; go("automation"); } catch (e) { toast(e.message, true); } } }, "Rename folder") : null,
      S.repoFolder && can("repo.delete") && cur && inside === 0 ? h("button", { type: "button", class: "danger", onclick: async () => {
        if (!confirm("Delete the empty folder " + S.repoFolder + "?")) return;
        try { await api("DELETE", "repo/folders?path=" + encodeURIComponent(S.repoFolder)); S.repoFolder = ""; go("automation"); } catch (e) { toast(e.message, true); } } }, "Delete folder") : null].filter(Boolean);
    main.replaceChildren(...header("Agent Repository", "The public agent repository: agents checked in from One AgentStudio, with every version. Run the live production version on any runner.",
      can("repo.checkin") ? h("button", { type: "button", onclick: () => newFolderDialog() }, "New folder") : null,
      can("lifecycle.export") ? h("button", { type: "button", onclick: () => exportDialog(r.bots) }, ic("download", "#475569", "sm"), "Export / promote") : null,
      can("lifecycle.import") ? h("button", { type: "button", onclick: () => importDialog() }, "Import") : null,
      can("repo.delete") ? h("button", { type: "button", onclick: () => deletedDialog() }, "Deleted agents") : null,
      S.approvalOn || pr.requests.length ? h("button", { type: "button", class: toDecide ? "primary" : "", onclick: () => approvalsDialog().catch((e) => toast(e.message, true)) },
        "Production approvals" + (pr.requests.length ? " (" + pr.requests.length + " waiting)" : "")) : null,
      h("button", { type: "button", onclick: () => policyDialog() }, "Code analysis")),
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
            can("repo.delete") && ["edit", "manage"].includes(b.access) ? h("button", { type: "button", class: "danger", onclick: () => deleteBotAsk(b) }, "Delete") : null) }]}),
      await repoFilesCard());
  },

  async skills(main) {
    const r = await api("GET", "skills");
    main.replaceChildren(...header("Prompt Skills", "Reviewed prompt templates agents run with the Prompt Skills toolkit. Private until shared; every save is a new version.",
      can("skills.manage") ? h("button", { type: "button", onclick: () => skillTemplatesDialog() }, "From a template") : null,
      can("skills.manage") ? h("button", { type: "button", class: "primary", onclick: () => skillDialog() }, "New skill") : null),
      dataTable({ rows: r.skills, search: "Search skills", exportName: "prompt-skills", onRow: (s) => skillDialog(s.name), empty: "No Prompt Skills yet. Start from a template.",
        columns: [
          { label: "Skill", value: (s) => s.name, render: (s) => h("div", {}, h("strong", { text: s.name }), s.template ? h("span", { class: "tag", text: "Template" }) : null, h("div", { class: "muted small", text: s.description || "" })) },
          { label: "Shared", value: (s) => s.shared ? "Shared" : "Private (" + s.owner + ")", render: (s) => s.shared ? h("span", { class: "pill success", text: "Shared" }) : h("span", { class: "pill", text: "Private to " + s.owner }) },
          { label: "Version", value: (s) => s.version, render: (s) => "v" + s.version },
          { label: "Updated", value: (s) => s.updated, render: (s) => h("span", { title: local(s.updated) }, ago(s.updated) + " by " + (s.updated_by || "")) }]}));
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
      if (st.tab === "active") list.replaceChildren(r.runs.length ? h("div", { class: "live-list" }, r.runs.map(liveCard)) : h("div", { class: "empty-state" }, ic("check", "#16a34a", "lg"), h("strong", { text: "Nothing is running" }), h("p", { class: "muted", text: "Runs appear here as soon as they are queued. Start one from Build." })));
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
    if (main.isConnected) S.timer = every(() => { if (st.tab === "active" || !st.offset) refresh().catch(() => {}); }, 2500);
  },

  async devices(main) {
    S.devTab = S.devTab || "runners";
    if (S.devTab === "pools") return VIEWS.pools(main);
    const r = await api("GET", "devices");
    S.devices = r.devices;
    const actions = can("devices.manage") ? [h("button", { type: "button", class: "primary", onclick: () => registerRunnerDialog() }, "Register a runner")] : [];
    const online = r.devices.filter((d) => d.status === "online").length;
    if (can("devices.manage") && r.devices.length) actions.unshift(h("button", { type: "button", onclick: () => runnerSettingsDialog(r.devices) }, "Update runners"));
    main.replaceChildren(...header("Runner Machines", "Machines running the OneRunner. A runner is online when it checked in during the last minute. " + online + " of " + r.devices.length + " online.", ...actions),
      tabStrip([["runners", "Runners"], ["pools", "Runner pools"]], "runners", (t) => { S.devTab = t; go("devices"); }),
      dataTable({ rows: r.devices, search: "Search runners", exportName: "devices", onRow: openDevice, empty: "No runners yet. Register one, or start ooa server on this computer.",
        columns: [
          { label: "Runner", value: (d) => d.name, render: (d) => h("div", { class: "name-cell" }, h("span", { class: "dot " + d.status }), h("div", {}, h("strong", { text: d.name }), h("div", { class: "muted small", text: (d.hostname || "") + " " + (d.os || "") }))) },
          { label: "Status", value: (d) => d.status, render: (d) => pill(d.status) },
          { label: "Running", value: (d) => d.running, render: (d) => d.running ? h("span", { class: "pill running", text: d.running + " agent" }) : "\u2014" },
          { label: "Agent", value: (d) => d.agent_version || "", render: (d) => d.version_status && d.version_status !== "ok"
            ? h("span", { class: "pill failed", title: "This runner is newer than the Command Center and gets no work until the Command Center is updated", text: (d.agent_version || "") + " – update the Command Center" })
            : h("span", { text: d.agent_version || "" }) },
          { label: "Type", value: (d) => (d.device_type === "serverless" ? "Serverless (in the Command Center)" : d.device_type === "single_user" ? "Single-user" : "Multi-user") + (d.lifespan === "temporary" ? ", temporary" : ""),
            render: (d) => h("span", {}, d.device_type === "serverless" ? "Serverless (in the Command Center)" : d.device_type === "single_user" ? "Single-user" : "Multi-user", d.lifespan === "temporary" ? [" ", h("span", { class: "pill pending", title: "Removed by itself after a time without contact (Settings)", text: "Temporary" })] : null) },
          { label: "Default users", value: (d) => (d.default_users || []).join(", "), render: (d) => (d.default_users || []).length ? h("div", { class: "chips" }, d.default_users.map((x) => h("span", { class: "chip", text: x }))) : "\u2014" },
          { label: "Log level", value: (d) => d.log_level || "info" },
          { label: "Screen", value: (d) => SCREEN[d.session_state] || "", render: (d) => d.session_state ? h("span", { class: "pill " + (d.session_state === "unlocked" ? "online" : d.session_state === "locked" ? "locked" : "offline"), title: SCREEN_HELP[d.session_state], text: SCREEN[d.session_state] }) : "—" },
          { label: "After a run", value: (d) => AFTER_RUN[d.after_run || "nothing"] },
          { label: "Program", value: (d) => d.up_to_date === undefined ? "" : d.up_to_date ? "Up to date" : "Update available",
            render: (d) => d.up_to_date === undefined ? "\u2014" : d.up_to_date ? h("span", { class: "pill online", text: "Up to date" })
              : h("span", {}, h("span", { class: "pill pending", text: d.update_requested ? "Updating" : "Update available" }), " ",
                can("devices.manage") && !d.update_requested ? h("button", { type: "button", title: "The runner updates itself when it is idle, then starts again", onclick: async () => {
                  try { const r = await api("POST", "devices/update", { ids: [d.id] }); toast("Runner " + d.name + " updates to " + r.version + " when it is idle"); go("devices"); } catch (e) { toast(e.message, true); } } }, "Update") : null) },
          { label: "Last seen", value: (d) => d.last_seen || "", render: (d) => h("span", { title: local(d.last_seen) }, ago(d.last_seen)) },
          { label: "Registered", value: (d) => d.registered, render: (d) => ago(d.registered) + " by " + (d.registered_by || "") },
          { label: "", sort: false, render: (d) => can("devices.manage") ? h("button", { type: "button", onclick: async () => { try { await api("PATCH", "devices/" + d.id, { enabled: !d.enabled }); go("devices"); } catch (e) { toast(e.message, true); } } }, d.enabled ? "Disable" : "Enable") : "" }]}));
    if (main.isConnected) S.timer = every(() => { if (S.page === "devices" && S.devTab === "runners" && $("modal").hidden && $("drawer").hidden) go("devices"); }, 15000);
  },

  async pools(main) {
    const [r, d] = await Promise.all([api("GET", "pools"), api("GET", "devices")]);
    S.devices = d.devices; S.pools = r.pools;
    main.replaceChildren(...header("Runner Machines", "Runner pools share work between several runners: a run sent to a pool goes to its least busy online runner.",
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
      can("queues.manage") ? h("button", { type: "button", onclick: () => importQueueDialog() }, "Import queue") : null,
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
    if (main.isConnected) S.timer = every(() => { if (S.page === "queues" && $("modal").hidden && $("drawer").hidden) go("queues"); }, 5000);
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
    if (main.isConnected) S.timer = every(() => { if (S.page === "inbox" && $("modal").hidden && $("drawer").hidden) go("inbox"); }, 10000);
  },

  async workflows(main) {
    await loadRefs();
    const r = await api("GET", "workflows/runs");
    const defs = S.bots.filter((b) => /\.process\.json$/.test(b.path) && b.production_version);
    const stLabel = { running: "running", waiting: "waiting", completed: "success", failed: "failed", cancelled: "stopped" };
    main.replaceChildren(...header("Workflow", "Workflows chain agents, people and decisions. Design them in One AgentStudio, check them in, then start them here; people get their steps in the Task inbox.",
      can("runs.start") && defs.length ? h("button", { type: "button", class: "primary", onclick: () => startWorkflowDialog() }, "Start a workflow") : null),
      defs.length ? null : h("p", { class: "muted", text: "No workflows are checked in yet. In One AgentStudio create a Workflow, then press Publish to Command Center." }),
      dataTable({ rows: r.runs, search: "Search workflow runs", exportName: "workflow-runs", onRow: openWorkflowRun, empty: "No workflow runs yet.",
        columns: [
          { label: "Workflow", value: (w) => botName(w.path), render: (w) => h("div", { class: "name-cell" }, ic("process", "#7c3aed", "sm"), h("div", {}, h("strong", { text: botName(w.path) + " v" + w.version }), h("div", { class: "muted small mono", text: w.id }))) },
          { label: "Status", value: (w) => w.status, render: (w) => h("span", { class: "pill " + (stLabel[w.status] || ""), text: w.status }) },
          { label: "Current stage", value: (w) => w.stage || "" },
          { label: "Started by", value: (w) => w.started_by || "" },
          { label: "Started", value: (w) => w.created, render: (w) => h("span", { title: local(w.created) }, ago(w.created)) },
          { label: "Updated", value: (w) => w.updated, render: (w) => ago(w.updated) }]}));
    if (main.isConnected) S.timer = every(() => { if (S.page === "workflows" && $("modal").hidden && $("drawer").hidden) go("workflows"); }, 5000);
  },

  /**
   * AI > Document Automation, as in AA's Control Room: a plug-in page. An optional module fills it (it lists itself
   * as a ConsolePlugin on the server); without it, the page says what the module does and that it is not installed.
   */
  async docautomation(main) {
    const r = await api("GET", "plugins");
    const p = (r.plugins || []).find((x) => x.id === "document-automation");
    const head = header("Document Automation", "Reads documents such as invoices, receipts and forms, and pulls their fields out with AI, for agents to use.");
    if (p) {
      main.replaceChildren(...head, h("div", { class: "card" }, h("p", { text: p.description }),
        h("a", { class: "btn primary", href: p.path, text: "Open Document Automation" })));
      return;
    }
    main.replaceChildren(...head, h("div", { class: "card" },
      h("h2", { text: "Not installed on this Command Center" }),
      h("p", { text: "Document Automation is an add-on module. Once it is installed, its pages open here, and agents can use it." }),
      h("ul", {},
        h("li", { text: "A learning instance for each kind of document: the fields to extract, how text is read, and the AI model that extracts it." }),
        h("li", { text: "Results checked by people in a validation queue before agents use them." }),
        h("li", { text: "Accuracy measured on sample documents before it goes live." })),
      h("p", { class: "muted", text: "Ask your administrator about the module. It works on premises and in the cloud." })));
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
      connections: ["Model Connections", "Named AI model connections agents use; keys stay in the Credential vault."],
      toolkits: ["Agent ToolKits", "Every toolkit, how many agents use it, and switching it on or off."] };
    const head = header(ADMIN_TITLES[tab][0], ADMIN_TITLES[tab][1]);
    if (tab === "settings") {
      const st = await api("GET", "settings");
      const ann = h("textarea", { rows: 2, value: st.announcement || "", placeholder: "Maintenance on Saturday 10:00\u201312:00 UTC" });
      const num = (k, min, max) => h("input", { type: "number", min: String(min), max: String(max), value: String(st[k] === undefined ? "" : st[k]) });
      const txt = (k, ph) => h("input", { type: "text", value: String(st[k] || ""), placeholder: ph || "" });
      const pub = txt("public_url", location.origin), ssoIssuer = txt("sso_issuer", "https://login.microsoftonline.com/<tenant>/v2.0"), ssoClient = txt("sso_client_id"),
        ssoSecret = txt("sso_client_secret_ref", "sso/oidc/client_secret"), ssoRole = txt("sso_default_role", "viewer"), ssoGroups = h("textarea", { rows: 3, value: st.sso_group_roles || "", placeholder: "OOA Admins = admin\nFinance Automation = bot_runner" }), ssoLabel = txt("sso_label", "Sign in with your organisation account"),
        mcpAgents = txt("mcp_agents", "Finance/Invoice lookup.flow.json"), mcpRunner = txt("mcp_runner"), mcpTimeout = num("mcp_timeout_seconds", 10, 3600),
        mcpAud = txt("mcp_oauth_audience", "api://ooa-mcp (empty = API keys only)"), mcpScope = txt("mcp_oauth_scope", "mcp.use (optional)");
      const inactive = num("inactive_user_days", 0, 3650), session = num("session_hours", 1, 720), upload = num("max_upload_mb", 1, 1024), poll = num("queue_poll_seconds", 3, 3600), saved = num("default_minutes_saved", 0, 100000);
      const keepRuns = num("run_retention_days", 0, 36500), keepLogs = num("run_log_retention_days", 0, 36500), keepAudit = num("audit_retention_days", 0, 36500);
      const maxSessions = num("max_sessions_per_user", 1, 50), pwMin = num("password_min_length", 12, 64), pwHist = num("password_history", 0, 24), pwDays = num("password_max_days", 0, 3650);
      const keepAi = num("ai_call_retention_days", 0, 36500), keepItems = num("work_item_retention_days", 0, 36500);
      const blockExe = h("input", { type: "checkbox", checked: Number(st.block_executable_uploads) === 1 });
      const prodApproval = h("input", { type: "checkbox", checked: Number(st.production_approval) === 1 });
      const autoUpdate = h("input", { type: "checkbox", checked: Number(st.runner_auto_update) === 1 });
      const tempHours = num("temporary_runner_hours", 1, 720), secretDays = num("runner_secret_days", 0, 365);
      const cpuMax = num("runner_cpu_max", 0, 100), memMax = num("runner_mem_max", 0, 100);
      const screenPics = h("input", { type: "checkbox", checked: Number(st.runner_screen_pictures) === 1 });
      const repoShared = txt("repo_files_folder", "\\\\fileserver\\ooa\\repo-files (for several nodes)");
      const tplUrl = txt("template_catalog_url", "(empty: ONEOFFICEAUTOMATION's catalogue; off: none)");
      const nodesBox = h("div", { class: "muted" });
      api("GET", "cluster").then((c) => nodesBox.replaceChildren(h("p", { text: c.nodes.length + " node(s): " + c.nodes.map((n) => n.node_id + (n.leader ? " (leads background work)" : "") + (n.this_node ? " (this one)" : "")).join(", ") }))).catch(() => {});
      const bkHours = num("backup_hours", 0, 168), bkKeep = num("backup_keep_days", 1, 3650), bkFolder = txt("backup_folder", "(the data folder's backups folder)");
      const bkState = h("div", { class: "muted" });
      const showBk = (b) => bkState.replaceChildren(h("p", { text: (b.backups.length ? b.backups.length + " backup(s) in " + b.folder + ", newest " + local(b.backups[0].created) + " (" + b.backups[0].size_mb + " MB)." : "No backups in " + b.folder + " yet.") + (b.state === "failed" ? " Last attempt failed: " + b.error : "") }), h("p", { class: "note warn", text: b.key_note }));
      api("GET", "backups").then(showBk).catch(() => {});
      const samlUrl = txt("saml_idp_sso_url", "https://login.microsoftonline.com/<tenant>/saml2"), samlIdp = txt("saml_idp_entity_id", "https://sts.windows.net/<tenant>/"),
        samlSp = txt("saml_sp_entity_id", (st.public_url || location.origin) + "/api/auth/saml/metadata"), samlLabel = txt("saml_label", "Sign in with SAML");
      const samlCert = h("textarea", { rows: 3, value: st.saml_idp_certificate || "", placeholder: "-----BEGIN CERTIFICATE-----" });
      const gitUrl = txt("git_remote_url", "https://github.com/acme/agents.git"), gitBranch = txt("git_branch", "main"), gitUser = txt("git_username", "(any, for a token)"),
        gitToken = txt("git_token_ref", "git/agents-repo/token");
      const gitAuto = h("input", { type: "checkbox", checked: Number(st.git_auto_push) === 1 });
      const gitState = h("p", { class: "muted" });
      const showGit = (g) => gitState.textContent = !g.configured ? "Not set up." : g.state === "never" ? "Not synced yet." : g.state === "ok" ? "Last sync " + local(g.at) + ": " + g.agents + " agent(s)" + (g.changed ? ", commit " + String(g.commit).slice(0, 8) : ", nothing changed") + "." : "Last sync failed " + local(g.at) + ": " + g.error;
      api("GET", "git/status").then(showGit).catch(() => {});
      const mfaReq = h("select", {}, h("option", { value: "0", text: "Optional (each person decides)" }), h("option", { value: "1", text: "Required for administrators" }),
        h("option", { value: "2", text: "Required for everyone with a password" })); mfaReq.value = String(st.mfa_required || 0);
      const allowList = h("textarea", { rows: 2, value: st.ip_allow_list || "", placeholder: "10.0.0.0/8, 203.0.113.7" });
      const trustedProxies = h("textarea", { rows: 2, value: st.trusted_proxies || "", placeholder: "10.0.5.10, 10.0.6.0/24" });
      const syslogTarget = txt("syslog_target", "siem.example.com:514");
      const syslogProto = h("select", {}, h("option", { value: "udp", text: "UDP" }), h("option", { value: "tcp", text: "TCP" })); syslogProto.value = st.syslog_protocol || "udp";
      const offNow = String(st.switched_off_roles || "").split(",").filter(Boolean);
      const roleOff = ["bot_creator", "bot_runner", "viewer"].map((r) => [r, h("input", { type: "checkbox", checked: offNow.includes(r) }), roleLabel(r)]);
      const proxy = txt("outbound_proxy", "http://proxy.example.com:8080"), proxyBypass = txt("outbound_proxy_bypass", "*.example.com, 10.*");
      const mailHost = txt("mail_smtp_host", "smtp.office365.com"), mailPort = num("mail_smtp_port", 1, 65535), mailUser = txt("mail_smtp_user", "ooa@example.com"),
        mailPw = txt("mail_smtp_password_ref", "mail/smtp/password"), mailFrom = txt("mail_from", "ooa@example.com"), licMailTo = txt("license_mail_to", "licences@example.com");
      const mailSec = h("select", {}, h("option", { value: "starttls", text: "STARTTLS (port 587)" }), h("option", { value: "ssl", text: "SSL/TLS (port 465)" }),
        h("option", { value: "none", text: "None (trusted network only)" })); mailSec.value = st.mail_smtp_security || "starttls";
      const mailTestTo = h("input", { type: "text", placeholder: "(your own address)" });
      const mailSettings = () => ({ mail_smtp_host: mailHost.value.trim(), mail_smtp_port: Number(mailPort.value), mail_smtp_security: mailSec.value, mail_smtp_user: mailUser.value.trim(),
        mail_smtp_password_ref: mailPw.value.trim(), mail_from: mailFrom.value.trim(), license_mail_to: licMailTo.value.trim() });
      const lic = await api("GET", "license").catch(() => null);
      const dbi = await api("GET", "database").catch(() => null);
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
        h("h3", {}, ic("key", "#2563eb", "sm"), "Single sign-on (SAML 2.0)"),
        h("p", { class: "muted", text: "For Entra ID, Okta, ADFS and other SAML identity providers. Give the provider this Command Center's metadata: " + (st.public_url || location.origin) + "/api/auth/saml/metadata (sign-in address " + (st.public_url || location.origin) + "/api/auth/saml/acs). Sign the assertion or the response; do not encrypt it. New people get the role for new people above." }),
        h("div", { class: "grid2" }, h("label", {}, "Identity provider sign-in address (SAML SSO URL)", samlUrl), h("label", {}, "Identity provider entity id (issuer)", samlIdp),
          h("label", {}, "This Command Center's entity id (empty = the metadata address)", samlSp), h("label", {}, "Button text", samlLabel)),
        h("label", {}, "Identity provider signing certificate (PEM)", samlCert),
        h("h4", { text: "Groups to roles (both kinds of single sign-on)" }),
        h("p", { class: "muted", text: "One line per group: group = role (for example: Finance Automation = bot_runner). At every sign-in the first group the person is in decides their role, so a change in the identity provider reaches here the next time they sign in. People in none of these groups keep their role; new ones get the role for new people. Local accounts, such as the administrator, always sign in with their password." }),
        h("label", {}, "Groups to roles", ssoGroups),
        h("h3", {}, ic("code", "#475569", "sm"), "Git sync of the agent repository"),
        h("p", { class: "muted", text: "Mirrors the agent repository to a Git branch (GitHub, GitLab, Azure DevOps): the latest version of every agent as JSON, and which version is live. The Command Center stays the source of truth; changes made in Git are overwritten. Keep the access token in the vault." }),
        h("div", { class: "grid2" }, h("label", {}, "Remote (https://...git)", gitUrl), h("label", {}, "Branch", gitBranch),
          h("label", {}, "User name", gitUser), h("label", {}, "Access token (vault reference folder/credential/attribute)", gitToken),
          h("label", { class: "check" }, gitAuto, "Push after every check-in, production change and deletion"),
          h("div", { class: "row-actions" }, h("button", { type: "button", onclick: async (e) => {
            e.target.disabled = true;
            try { await api("PUT", "settings", { git_remote_url: gitUrl.value.trim(), git_branch: gitBranch.value.trim(), git_username: gitUser.value.trim(), git_token_ref: gitToken.value.trim(), git_auto_push: gitAuto.checked ? 1 : 0 });
              const r = await api("POST", "git/sync"); showGit(Object.assign({ configured: true }, r)); toast(r.changed ? "Pushed to Git" : "Git is up to date"); }
            catch (err) { toast(err.message, true); api("GET", "git/status").then(showGit).catch(() => {}); } finally { e.target.disabled = false; } } }, "Save and sync now"))),
        gitState,
        h("h3", {}, ic("bolt", "#7c3aed", "sm"), "AI assistants (Model Context Protocol)"),
        h("p", { class: "muted", text: "Assistants connect to " + location.origin + "/api/mcp with a user's API key and can run the agents listed here as tools, with that user's permissions." }),
        h("div", { class: "grid2" }, h("label", {}, "Agents offered as tools (paths, comma separated; * for all)", mcpAgents), h("label", {}, "Runner or runner pool id (empty = any online runner)", mcpRunner),
          h("label", {}, "Wait for a result (seconds)", mcpTimeout)),
        h("p", { class: "muted", text: "Sign-in with OAuth: assistants can also send an access token from your identity provider (the single sign-on issuer above), meant for the audience below. The person must already have an account here. Assistants find this at " + location.origin + "/.well-known/oauth-protected-resource." }),
        h("div", { class: "grid2" }, h("label", {}, "Token audience (the application id URI of the MCP app in your identity provider)", mcpAud), h("label", {}, "Scope the token must carry", mcpScope)),
        h("h3", {}, ic("shield", "#475569", "sm"), "Security"),
        h("p", { class: "muted", text: "Sign-in sessions per person (the oldest end first), the shortest password allowed, and which addresses may use the Command Center. An empty allow list lets every address in; this computer is always allowed." }),
        h("div", { class: "grid2" }, h("label", {}, "Sign-ins per person at once", maxSessions), h("label", {}, "Shortest password (characters)", pwMin),
          h("label", {}, "Passwords that cannot be used again (the last N, 0 = no rule)", pwHist), h("label", {}, "Change passwords every (days, 0 = never)", pwDays),
          h("label", {}, "Two-step sign-in (authenticator app codes; single sign-on users are left to their identity provider)", mfaReq),
          h("label", { class: "check" }, blockExe, "Block programs and scripts (.exe, .dll, .ps1 and similar) in repository uploads"),
          h("label", { class: "check" }, prodApproval, "Approval before production: a second person approves every version before it goes live"),
          h("label", { class: "check" }, autoUpdate, "Update runners automatically to this Command Center's version when they are idle"),
          h("label", {}, "Remove temporary runners after this many hours without contact", tempHours),
          h("label", {}, "Give runners a new secret every (days, 0 = never)", secretDays),
          h("label", {}, "A runner above this CPU use takes no new work (%, 0 = no limit; a runner can have its own)", cpuMax),
          h("label", {}, "A runner above this memory use takes no new work (%, 0 = no limit)", memMax),
          h("label", { class: "check" }, screenPics, "Administrators may ask a runner for a picture of its screen (each picture is recorded in the audit log)")),
        h("label", {}, "IP allow list (addresses or CIDR ranges, separated by commas)", allowList),
        h("label", {}, "Trusted proxies: load balancers whose X-Forwarded-For names the client (addresses or ranges)", trustedProxies),
        h("p", { class: "muted", text: "Switch off built-in roles you do not use: they then give no permissions and cannot be given to anyone. People who still have one keep only their other roles." }),
        h("div", { class: "grid2" }, roleOff.map(([, box, l]) => h("label", { class: "check" }, box, "Switch off " + l))),
        h("h3", {}, ic("globe", "#475569", "sm"), "Outgoing connections"),
        h("p", { class: "muted", text: "The Command Center's own connections to the internet (single sign-on, Git, AI model connections, external vaults) go through this proxy when set. This computer is always reached directly. Runners have their own proxy setting (ooa agent network)." }),
        h("div", { class: "grid2" }, h("label", {}, "Proxy (http://host:port, empty = direct)", proxy), h("label", {}, "Reach these directly (host names, * allowed)", proxyBypass)),
        h("h3", {}, ic("pulse", "#475569", "sm"), "Audit forwarding (SIEM)"),
        h("p", { class: "muted", text: "Every audit entry is also sent to your SIEM as a syslog message (RFC 5424). Leave the target empty to switch this off." }),
        h("div", { class: "grid2" }, h("label", {}, "Syslog target (host:port)", syslogTarget), h("label", {}, "Protocol", syslogProto),
          h("div", { class: "row-actions" }, h("button", { type: "button", onclick: async () => {
            try { await api("PUT", "settings", { syslog_target: syslogTarget.value.trim(), syslog_protocol: syslogProto.value }); await api("POST", "settings/syslog-test"); toast("Test message sent to " + syslogTarget.value.trim()); }
            catch (e) { toast(e.message, true); } } }, "Send a test message"))),
        h("h3", {}, ic("inbox", "#475569", "sm"), "E-mail"),
        h("p", { class: "muted", text: "The Command Center sends its own e-mail (licence expiry reminders to administrators, workflow e-mail steps) through your mail server. The password is a vault reference, never typed here." }),
        h("div", { class: "grid2" }, h("label", {}, "Outgoing mail server", mailHost), h("label", {}, "Port", mailPort), h("label", {}, "Security", mailSec),
          h("label", {}, "Account (empty = no sign-in)", mailUser), h("label", {}, "Password (vault reference folder/credential/attribute)", mailPw), h("label", {}, "Sender address", mailFrom),
          h("label", {}, "Also send licence reminders to (addresses, separated by commas)", licMailTo),
          h("label", {}, "Send a test e-mail to", mailTestTo),
          h("div", { class: "row-actions" }, h("button", { type: "button", onclick: async () => {
            try { await api("PUT", "settings", mailSettings()); const r = await api("POST", "settings/mail-test", { to: mailTestTo.value.trim() }); toast("Test e-mail sent to " + r.to); }
            catch (e) { toast(e.message, true); } } }, "Send a test e-mail"))),
        h("h3", {}, ic("desktop", "#475569", "sm"), "Several nodes"),
        h("p", { class: "muted", text: "Start more Command Centers on the same database behind a load balancer for high availability. Each serves everything; one leads the background work and another takes over within about 30 seconds if it stops. Put repository files in a shared folder every node can reach." }),
        nodesBox, h("label", {}, "Shared folder for repository files (empty = the data folder)", repoShared),
        h("label", {}, "Template catalogue (an https address of templates.json)", tplUrl),
        h("h3", {}, ic("download", "#475569", "sm"), "Backups"),
        h("p", { class: "muted", text: "Every table, the repository files and the configuration, in one zip per backup. Restore with: bin\\ooa.bat backup restore FILE (the server stopped)." }),
        h("div", { class: "grid2" }, h("label", {}, "Back up every (hours, 0 = never)", bkHours), h("label", {}, "Keep backups for (days)", bkKeep),
          h("label", {}, "Backup folder", bkFolder),
          h("div", { class: "row-actions" }, h("button", { type: "button", onclick: async (e) => {
            e.target.disabled = true;
            try { const r = await api("POST", "backups"); toast("Backup made: " + r.file + " (" + r.size_mb + " MB)"); api("GET", "backups").then(showBk); }
            catch (err) { toast(err.message, true); } finally { e.target.disabled = false; } } }, "Back up now"))),
        bkState,
        h("h3", {}, ic("clock", "#475569", "sm"), "Run history"),
        h("p", { class: "muted", text: "Old run history is removed once an hour: step logs after the first period, whole runs after the second (0 keeps them). Queued and running runs are never removed." }),
        h("div", { class: "grid2" }, h("label", {}, "Keep step logs for (days, 0 = always)", keepLogs), h("label", {}, "Keep runs for (days, 0 = always)", keepRuns),
          h("label", {}, "Keep audit entries for (days, at least 90; 0 = always). Older ones go to audit-archive in the data folder first", keepAudit),
          h("label", {}, "Keep AI call records for (days, 0 = always)", keepAi), h("label", {}, "Keep finished work-queue items for (days, 0 = always)", keepItems),
          h("div", { class: "row-actions" }, h("button", { type: "button", onclick: async () => {
            const d = Number(keepRuns.value), l = Number(keepLogs.value);
            if (!d && !l) { toast("Set a number of days first", true); return; }
            if (!confirm("Remove run history older than these periods now?")) return;
            try { const r = await api("POST", "runs/purge", { run_days: d, log_days: l }); toast("Removed " + r.runs_removed + " run(s) and " + r.log_lines_removed + " log line(s)"); } catch (e) { toast(e.message, true); } } }, "Remove now"))),
        lic ? [h("h3", {}, ic("shield", "#475569", "sm"), "This Command Center"), kv([["Edition", lic.edition], ["Limits", lic.limits], ["Active users", String(lic.users)], ["Runners", String(lic.runners)],
          ["Agents", String(lic.agents)], ["Toolkits", lic.toolkits + (lic.toolkits_switched_off ? " (" + lic.toolkits_switched_off + " switched off)" : "")]])] : null,
        dbi ? [h("h3", {}, ic("desktop", "#475569", "sm"), "Database"),
          h("p", { class: "muted", text: dbi.note || "Read-only here. The connection is set in config\\ooa-db.properties in the data folder and changed with the ooa db command." }),
          kv([["Engine", dbi.engine + " " + dbi.version + (dbi.edition ? " (" + dbi.edition + ")" : "")], dbi.server ? ["Server", dbi.server] : null, ["Database", dbi.database || "\u2014"],
            dbi.account ? ["Signed in as", dbi.account] : null, ["Encrypted connection", dbi.encrypted ? "Yes" : "No"],
            ["Schema", "version " + dbi.schema_version + " of " + dbi.schema_latest + (dbi.schema_version < dbi.schema_latest ? " (run ooa db migrate)" : "")],
            dbi.collation ? ["Collation", dbi.collation] : null, dbi.size_mb !== undefined && dbi.size_mb !== null ? ["Size", dbi.size_mb + " MB"] : null,
            ["Connection pool", "up to " + dbi.pool_max + " idle connections"]].filter(Boolean))] : null,
        h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: async () => {
          try {
            await api("PUT", "settings", { announcement: ann.value.trim(), inactive_user_days: Number(inactive.value), session_hours: Number(session.value), max_upload_mb: Number(upload.value),
              queue_poll_seconds: Number(poll.value), default_minutes_saved: Number(saved.value), public_url: pub.value.trim(), sso_issuer: ssoIssuer.value.trim(), sso_client_id: ssoClient.value.trim(),
              sso_client_secret_ref: ssoSecret.value.trim(), sso_default_role: ssoRole.value.trim(), sso_group_roles: ssoGroups.value.trim(), sso_label: ssoLabel.value.trim(), mcp_agents: mcpAgents.value.trim(), mcp_runner: mcpRunner.value.trim(),
              mcp_timeout_seconds: Number(mcpTimeout.value), mcp_oauth_audience: mcpAud.value.trim(), mcp_oauth_scope: mcpScope.value.trim(), run_retention_days: Number(keepRuns.value), run_log_retention_days: Number(keepLogs.value), audit_retention_days: Number(keepAudit.value), backup_hours: Number(bkHours.value), backup_keep_days: Number(bkKeep.value), backup_folder: bkFolder.value.trim(), repo_files_folder: repoShared.value.trim(), template_catalog_url: tplUrl.value.trim(),
              max_sessions_per_user: Number(maxSessions.value), password_min_length: Number(pwMin.value), password_history: Number(pwHist.value), password_max_days: Number(pwDays.value),
              ai_call_retention_days: Number(keepAi.value), work_item_retention_days: Number(keepItems.value), block_executable_uploads: blockExe.checked ? 1 : 0, mfa_required: Number(mfaReq.value), production_approval: prodApproval.checked ? 1 : 0, runner_auto_update: autoUpdate.checked ? 1 : 0, temporary_runner_hours: Number(tempHours.value), runner_secret_days: Number(secretDays.value), runner_cpu_max: Number(cpuMax.value), runner_mem_max: Number(memMax.value), runner_screen_pictures: screenPics.checked ? 1 : 0,
              saml_idp_sso_url: samlUrl.value.trim(), saml_idp_entity_id: samlIdp.value.trim(), saml_sp_entity_id: samlSp.value.trim(), saml_label: samlLabel.value.trim(), saml_idp_certificate: samlCert.value.trim(),
              git_remote_url: gitUrl.value.trim(), git_branch: gitBranch.value.trim(), git_username: gitUser.value.trim(), git_token_ref: gitToken.value.trim(), git_auto_push: gitAuto.checked ? 1 : 0,
              ip_allow_list: allowList.value.trim(), trusted_proxies: trustedProxies.value.trim(), syslog_target: syslogTarget.value.trim(), syslog_protocol: syslogProto.value, ...mailSettings(),
              switched_off_roles: roleOff.filter(([, b]) => b.checked).map(([r]) => r).join(","), outbound_proxy: proxy.value.trim(), outbound_proxy_bypass: proxyBypass.value.trim() });
            toast("Settings saved"); showAnnouncement(ann.value.trim());
          } catch (e) { toast(e.message, true); } } }, "Save settings"))));
    } else if (tab === "roles") {
      const r = await api("GET", "roles");
      main.replaceChildren(...head, tabs, h("div", { class: "toolbar" }, h("span", { class: "spacer" }), h("button", { type: "button", class: "primary", onclick: () => roleDialog(null, r.permissions) }, "New role")),
        dataTable({ rows: r.roles, search: "Search roles", exportName: "roles",
          columns: [
            { label: "Role", value: (x) => roleLabel(x.name), render: (x) => h("div", {}, h("strong", { text: roleLabel(x.name) }), x.builtin ? h("span", { class: "tag", text: x.switched_off ? "built-in, switched off" : "built-in" }) : h("code", { class: "small", text: " " + x.name }), h("div", { class: "muted small", text: x.description || "" })) },
            { label: "Permissions", value: (x) => x.permissions.join(" "), render: (x) => h("div", { class: "chips" }, x.permissions.map((p) => h("span", { class: "chip", text: p }))) },
            { label: "Users", value: (x) => x.users === undefined ? "" : x.users },
            { label: "", sort: false, render: (x) => x.builtin ? "" : h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => roleDialog(x, r.permissions) }, "Edit"),
              h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete role " + x.name + "?")) { try { await api("DELETE", "roles/" + encodeURIComponent(x.name)); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } } }, "Delete")) }]}));
    } else if (tab === "connections") {
      const r = await api("GET", "connections");
      main.replaceChildren(...head, tabs, h("p", { class: "muted", text: "Named AI model connections that agents use through the connection input of the Prompt Skill, AI agent and generative AI actions. Keys stay in the Credential vault; agents receive only the reference." }),
        h("div", { class: "toolbar" }, h("span", { class: "spacer" }), h("button", { type: "button", class: "primary", onclick: () => connectionDialog() }, "New connection")),
        dataTable({ rows: r.connections, search: "Search connections", exportName: "model-connections", empty: "No model connections yet.",
          columns: [
            { label: "Name", value: (c) => c.name, render: (c) => h("div", {}, h("strong", { text: c.name }), h("div", { class: "muted small", text: c.description || "" })) },
            { label: "Provider", value: (c) => c.provider }, { label: "Model", value: (c) => c.model || "", render: (c) => h("span", {}, c.model || "", c.model_type === "fine_tuned" ? h("span", { class: "tag", text: "Fine-tuned" }) : null) },
            { label: "Roles", value: (c) => (c.allowed_roles || []).join(", "), render: (c) => (c.allowed_roles || []).length ? h("div", { class: "chips" }, c.allowed_roles.map((x) => h("span", { class: "chip", text: roleLabel(x) }))) : h("span", { class: "muted", text: "every role" }) },
            { label: "Endpoint", value: (c) => c.base_url || "", render: (c) => h("span", { class: "mono small", text: c.base_url || "provider default" }) },
            { label: "Tokens this month", value: (c) => c.tokens_this_month || 0, render: (c) => c.monthly_tokens ? h("div", {}, (c.tokens_this_month || 0) + " of " + c.monthly_tokens,
              progressBar(100 * (c.tokens_this_month || 0) / c.monthly_tokens, c.tokens_this_month >= c.monthly_tokens ? "bad" : "")) : String(c.tokens_this_month || 0) },
            { label: "Key", value: (c) => c.key_ref || "", render: (c) => c.key_ref ? h("span", {}, ic("lock", "#dc2626", "sm"), h("code", { text: c.key_ref })) : h("span", { class: "muted", text: "none" }) },
            { label: "Updated", value: (c) => c.updated || "", render: (c) => ago(c.updated) + " by " + (c.updated_by || "") },
            { label: "", sort: false, render: (c) => h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => connectionDialog(c) }, "Edit"),
              h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete connection " + c.name + "?")) { try { await api("DELETE", "connections/" + encodeURIComponent(c.name)); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } } }, "Delete")) }]}));
    } else {
      const r = await api("GET", "toolkits");
      const manage = can("toolkits.manage");
      main.replaceChildren(...head, tabs, h("p", { class: "muted", text: "Switch a toolkit off to stop agents that use it from being checked in or run. The core toolkit is always on. Your own toolkits are signed jars from trusted publishers; several versions stay installed side by side." }),
        manage ? h("div", { class: "toolbar" }, h("button", { type: "button", class: "primary", onclick: uploadToolkitDialog }, "Upload toolkit"),
          h("button", { type: "button", onclick: publishersDialog }, "Trusted publishers"), h("button", { type: "button", onclick: customVersionsDialog }, "Your toolkit versions")) : null,
        dataTable({ rows: r.toolkits, search: "Search toolkits", exportName: "toolkits", pageSize: 100,
          columns: [
            { label: "Toolkit", value: (t) => t.name, render: (t) => h("div", {}, h("strong", { text: t.name }), t.custom ? h("span", { class: "pill", text: "your own" }) : null,
              h("div", { class: "muted small mono", text: t.id + " " + (t.versions ? t.versions.join(", ") + " (default " + t.version + ")" : t.version || "") }), h("div", { class: "muted small", text: t.description || "" })) },
            { label: "Actions", value: (t) => t.actions + t.conditions, render: (t) => t.actions + (t.conditions ? " + " + t.conditions + " conditions" : "") },
            { label: "Used by", value: (t) => t.used_by, render: (t) => t.used_by ? h("button", { type: "button", class: "link", onclick: async () => {
              const u = await api("GET", "toolkits/" + encodeURIComponent(t.id) + "/used-by");
              modal("Agents using " + t.name, [table(["Agent"], u.agents.map((a) => ({ data: a, cells: [botName(a)] })))], []); } }, t.used_by + " agent" + (t.used_by === 1 ? "" : "s")) : h("span", { class: "muted", text: "none" }) },
            { label: "Status", value: (t) => t.enabled ? "On" : "Off", render: (t) => pill(t.enabled ? "enabled" : "disabled") },
            { label: "Versions", sort: false, render: (t) => t.used_by ? h("button", { type: "button", onclick: () => toolkitVersionsDialog(t) }, "Versions") : "" },
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
    // several schedules at once: enable, disable, hand to another owner
    const picked = new Set();
    const bulk = (action) => async () => {
      if (!picked.size) { toast("Tick schedules first", true); return; }
      const body = { ids: [...picked], action };
      if (action === "owner") { const o = prompt("New owner (user name) for " + picked.size + " schedule(s):"); if (!o) return; body.owner = o.trim(); }
      try { const r = await api("POST", "schedules/bulk", body); toast(r.changed + " schedule(s) changed"); go("schedules"); } catch (e) { toast(e.message, true); }
    };
    const tools = can("schedules.manage") ? [h("button", { type: "button", onclick: bulk("enable") }, "Enable selected"), h("button", { type: "button", onclick: bulk("disable") }, "Disable selected"),
      h("button", { type: "button", onclick: bulk("owner") }, "Change owner")] : [];
    main.replaceChildren(...header("Schedules", "Run agents automatically. Scheduled runs use the vault permissions of the schedule's owner, or of the run-as user; holiday calendars skip the dates you list.",
      h("button", { type: "button", onclick: calendarsDialog }, ic("calendar", "#059669", "sm"), "Holiday calendars"),
      can("schedules.manage") ? h("button", { type: "button", class: "primary", onclick: scheduleDialog }, "New schedule") : null),
      dataTable({ rows: r.schedules, search: "Search schedules", exportName: "schedules", empty: "No schedules yet.", tools,
        onRow: can("runs.view") ? (s) => { S.activity = { tab: "history", offset: 0, size: 25, f: { source: "schedule", bot: s.bot_path } }; go("activity"); } : null,
        columns: [
          { label: "Schedule", value: (s) => s.name, render: (s) => h("div", { class: "name-cell" }, ic("clock", "#059669", "sm"), h("strong", { text: s.name })) },
          { label: "Agent", value: (s) => botName(s.bot_path) },
          { label: "", sort: false, render: (s) => can("schedules.manage") ? h("input", { type: "checkbox", "aria-label": "Select " + s.name, checked: picked.has(s.id),
            onchange: (e) => { if (e.target.checked) picked.add(s.id); else picked.delete(s.id); } }) : "" },
          { label: "Runner", value: (s) => s.pool_id ? "Pool: " + ((S.pools.find((p) => p.id === s.pool_id) || {}).name || s.pool_id) : s.device_id ? deviceName(s.device_id) : "default runner of " + s.run_as },
          { label: "When", value: (s) => when(s) + " (" + s.time_zone + ")" + (s.holiday_calendar ? ", not on " + s.holiday_calendar : "")
            + (s.missed_runs === "skip" ? ", skips missed times" : "") + (s.overlap === "skip" ? ", skips while the previous run is going" : "") },
          { label: "Owner", value: (s) => s.created_by || "", render: (s) => h("span", {}, s.created_by || "", s.run_as ? h("div", { class: "muted small", text: "runs as " + s.run_as }) : null) },
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
          { label: "Agent", value: (t) => botName(t.bot_path) }, { label: "Runner", value: (t) => deviceName(t.device_id), render: (t) => h("span", {}, deviceName(t.device_id), t.run_as ? h("div", { class: "muted small", text: "runs as " + t.run_as }) : null) },
          { label: "Details", value: (t) => triggerDetails(t), render: (t) => h("span", { class: "mono small", text: triggerDetails(t) }) },
          { label: "Monitoring", value: (t) => (t.fire_count || 0) + " " + (t.last_fired || "") + " " + (t.last_error || ""), render: (t) => h("div", { class: "small" },
            h("div", {}, h("strong", { text: String(t.fire_count || 0) }), " fired", t.last_fired ? h("span", { class: "muted", title: local(t.last_fired) }, ", last " + ago(t.last_fired)) : h("span", { class: "muted" }, ", never")),
            t.last_error ? h("div", { class: "error trigger-error", title: t.last_error, text: t.last_error.length > 90 ? t.last_error.slice(0, 90) + "…" : t.last_error }) : null) },
          { label: "Status", value: (t) => t.status || (t.enabled ? "active" : "paused"), render: (t) => t.status === "error" ? h("span", { class: "pill failed", text: "error" }) : pill(t.enabled ? "active" : "paused") },
          { label: "", sort: false, render: (t) => can("triggers.manage") ? h("div", { class: "row-actions tight" },
            t.evaluated_on === "server" && t.enabled ? h("button", { type: "button", title: "Check now instead of waiting for the interval", onclick: () => act(async () => { const x = await api("POST", "triggers/" + t.id + "/poll"); toast(x.error ? x.error : "Checked: " + x.fired + " new"); }) }, "Check now") : null,
            h("button", { type: "button", onclick: () => act(() => api("POST", "triggers/" + t.id + (t.enabled ? "/pause" : "/resume"))) }, t.enabled ? "Pause" : "Resume"),
            h("button", { type: "button", onclick: () => triggerEditDialog(t) }, "Edit"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete trigger " + t.name + "?")) act(() => api("DELETE", "triggers/" + t.id)); } }, "Delete")) : "" }]}));
    if (main.isConnected) S.timer = every(() => { if (S.page === "triggers" && $("modal").hidden && $("drawer").hidden) go("triggers"); }, 30000);
  },

  async credentials(main) {
    const [r, req] = await Promise.all([api("GET", "lockers"), api("GET", "vault/requests").catch(() => ({ requests: [] }))]);
    const q = h("input", { type: "search", placeholder: "Search vault folders and credentials", "aria-label": "Search vault folders" });
    const box = h("div", { class: "locker-grid" });
    const render = () => {
      const f = q.value.trim().toLowerCase();
      const lockers = r.lockers.filter((l) => !f || (l.name + " " + (l.description || "") + " " + l.credentials.map((c) => c.name + " " + c.attribute).join(" ")).toLowerCase().includes(f)).map((l) => {
        // one row per credential: its attributes (user-provided ones marked), owner and last change
        const info = Object.fromEntries((l.credential_info || []).map((c) => [c.name, c]));
        const byName = {};
        for (const c of l.credentials) (byName[c.name] = byName[c.name] || []).push(c);
        for (const n of Object.keys(info)) byName[n] = byName[n] || [];
        const writer = ["owner", "manager"].includes(l.my_role);
        const canChange = (n) => writer || (l.my_role === "participant" && (!info[n] || info[n].owner === S.user.username));
        const rows = Object.keys(byName).sort().map((n) => {
          const ci = info[n] || { user_attributes: [], my_values: [] };
          const last = byName[n].slice().sort((a, b) => String(b.updated).localeCompare(String(a.updated)))[0];
          return { data: n, cells: [h("strong", { text: n }), h("div", { class: "chips" }, byName[n].map((c) => h("span", { class: "chip", text: c.attribute })),
            (ci.user_attributes || []).map((a) => h("span", { class: "chip user-attr", title: "Each person stores their own value" + ((ci.my_values || []).includes(a) ? " (yours is set)" : " (yours is not set)"), text: a + ((ci.my_values || []).includes(a) ? " \u2713" : " \u2026") }))),
            ci.owner || "", last ? ago(last.updated) + " by " + last.updated_by : "",
            h("div", { class: "row-actions tight" }, h("button", { type: "button", onclick: () => credentialDetails(l, n) }, "Details"),
              canChange(n) ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete the credential " + n + " with all its values (everyone's own values too)?")) return;
                try { await api("DELETE", "vault/credential?locker=" + encodeURIComponent(l.name) + "&name=" + encodeURIComponent(n)); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Delete") : null)] };
        });
        return h("div", { class: "card locker" },
          h("div", { class: "locker-head" }, ic("lock", "#dc2626"), h("div", {}, h("h2", {}, l.name, " ", h("span", { class: "pill", text: "You: " + l.my_role })),
            h("p", { class: "muted", text: (l.description || "") + "  Owners: " + l.owners + ".  Consumers: " + (l.consumers || "none") + "."
              + (l.managers && l.managers.length ? "  Managers: " + l.managers.join(", ") + "." : "") + (l.participants && l.participants.length ? "  Participants: " + l.participants.join(", ") + "." : "") }),
            l.external ? h("p", {}, h("span", { class: "pill running", text: "Linked: " + (EXT_VAULTS[l.external.provider] || l.external.provider) }), " ", h("span", { class: "muted small", text: (l.external.url || l.external.region || "") + " \u00B7 credentials are read from there as name/attribute" })) : null)),
          table(["Credential", "Attributes", "Owner", "Updated", ""], rows),
          h("div", { class: "row-actions" },
            writer || l.my_role === "participant" ? h("button", { type: "button", onclick: () => credentialDialog(l.name) }, "Add or update credential") : null,
            l.can_manage ? h("button", { type: "button", onclick: () => lockerMembersDialog(l) }, "Members") : null,
            l.can_manage ? h("button", { type: "button", onclick: () => externalVaultDialog(l) }, l.external ? "External vault" : "Link an external vault") : null,
            l.can_manage && !rows.length ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete the empty vault folder " + l.name + "?")) return;
              try { await api("DELETE", "lockers/" + encodeURIComponent(l.name)); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Delete folder") : null));
      });
      box.replaceChildren(...(lockers.length ? lockers : [h("p", { class: "muted", text: r.lockers.length ? "No matches." : "No vault folders you own or consume yet." })]));
    };
    q.addEventListener("input", render);
    main.replaceChildren(...header("Credential vault", "Encrypted vault folders. Values are never shown again after saving; agents get them only while running, and every use is audited.",
      h("button", { type: "button", onclick: credentialRequestsDialog }, "Credential requests", req.requests.length ? h("span", { class: "count", text: String(req.requests.length) }) : null),
      can("lockers.manage") ? h("button", { type: "button", onclick: vaultKeyDialog }, ic("key", "#475569", "sm"), "Vault key") : null,
      can("lockers.manage") || can("lockers.own") ? h("button", { type: "button", class: "primary", onclick: lockerDialog }, "New vault folder") : null), h("div", { class: "toolbar" }, q), box);
    render();
  },

  async globals(main) {
    const r = await api("GET", "globals");
    main.replaceChildren(...header("Shared Global values", "Shared Global values every agent can read as $global.name. Where people may change a value, their runs use their own value.", can("globals.manage") ? h("button", { type: "button", class: "primary", onclick: () => globalDialog() }, "New shared value") : null),
      dataTable({ rows: r.globals, search: "Search shared values", exportName: "global-values", empty: "No shared values yet.",
        columns: [
          { label: "Name", value: (g) => "$global." + g.name, render: (g) => h("code", { text: "$global." + g.name }) },
          { label: "Value", value: (g) => JSON.stringify(g.value), render: (g) => h("code", { class: "val", text: JSON.stringify(g.value) }) },
          { label: "People may change", value: (g) => g.changeable ? "Yes" : "No", render: (g) => g.changeable ? h("span", { class: "pill success", text: "Yes" }) : "No" },
          { label: "Your value", value: (g) => g.my_value === undefined ? "" : JSON.stringify(g.my_value), render: (g) => !g.changeable ? "\u2014" : g.my_value === undefined ? h("span", { class: "muted", text: "(uses the shared one)" }) : h("code", { class: "val", text: JSON.stringify(g.my_value) }) },
          { label: "Updated", value: (g) => g.updated, render: (g) => h("span", { title: local(g.updated) }, ago(g.updated) + " by " + g.updated_by) },
          { label: "", sort: false, render: (g) => h("div", { class: "row-actions tight" },
            g.changeable ? h("button", { type: "button", onclick: () => myGlobalDialog(g) }, "My value") : null,
            can("globals.manage") ? h("button", { type: "button", onclick: () => globalDialog(g) }, "Edit") : null,
            can("globals.manage") ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + g.name + "? Everyone's own values of it go too.")) { await api("DELETE", "globals/" + g.name); go("globals"); } } }, "Delete") : null) }]}));
  },

  async savedconn(main) {
    const [r, k] = await Promise.all([api("GET", "saved-connections"), api("GET", "connection-kinds")]);
    S.connKinds = k.kinds;
    const kindName = (pkg) => (S.connKinds.find((x) => x.package === pkg) || { name: pkg }).name;
    main.replaceChildren(...header("Connections", "A connector's address and sign-in, kept once and chosen by name on agent steps. Secrets stay in Credential vault; a connection keeps only their references.",
      can("globals.manage") ? h("button", { type: "button", class: "primary", onclick: () => savedConnDialog(null) }, "New connection") : null),
      dataTable({ rows: r.connections, search: "Search connections", exportName: "connections", empty: "No connections yet. Create one so agent steps do not repeat an address and sign-in.",
        columns: [
          { label: "Connection", value: (c) => c.name + " " + (c.description || ""), render: (c) => h("div", {}, h("strong", { text: c.name }), c.description ? h("div", { class: "muted small", text: c.description }) : null) },
          { label: "Toolkit", value: (c) => kindName(c.package) },
          { label: "Settings", value: (c) => Object.keys(c.values || {}).join(", "), render: (c) => h("div", { class: "chips" }, Object.entries(c.values || {}).map(([n, v]) => h("span", { class: "chip", title: String(v), text: n }))) },
          { label: "Secrets from the vault", value: (c) => Object.values(c.credentials || {}).join(", "), render: (c) => h("div", { class: "chips" }, Object.entries(c.credentials || {}).map(([n, v]) => h("span", { class: "chip", title: n, text: v }))) },
          { label: "Updated", value: (c) => c.updated, render: (c) => h("span", { title: local(c.updated) }, ago(c.updated) + " by " + c.updated_by) },
          { label: "", sort: false, render: (c) => can("globals.manage") ? h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: () => savedConnDialog(c) }, "Edit"),
            h("button", { type: "button", class: "danger", onclick: async () => {
              if (!confirm("Delete the connection " + c.name + "? Agent steps that use it fail until another is chosen.")) return;
              try { await api("DELETE", "saved-connections/" + encodeURIComponent(c.name)); toast("Deleted " + c.name); go("savedconn"); } catch (e) { toast(e.message, true); } } }, "Delete")) : null }]}));
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
          { label: "User", value: (u) => (u.display_name || "") + " " + u.username + " " + (u.first_name || "") + " " + (u.last_name || ""), render: (u) => h("div", { class: "name-cell" }, h("span", { class: "avatar sm", text: (u.display_name || u.username).charAt(0).toUpperCase() }), h("div", {}, h("strong", { text: u.display_name || u.username }),
            h("div", { class: "muted small", text: u.username + ([u.first_name, u.last_name].filter(Boolean).length ? " \u00B7 " + [u.first_name, u.last_name].filter(Boolean).join(" ") : "") + (u.description ? " \u00B7 " + u.description : "") }))) },
          { label: "Role", value: (u) => u.role, render: roleSel },
          { label: "Further roles", value: (u) => (u.roles || []).slice(1).join(", "), render: (u) => (u.roles || []).length > 1 ? h("div", { class: "chips" }, u.roles.slice(1).map((x) => h("span", { class: "chip", text: roleLabel(x) }))) : "\u2014" },
          { label: "Status", value: status, render: (u) => pill(u.enabled ? (u.must_change ? "pending" : "active") : "disabled") },
          { label: "Default runner", value: (u) => (runAs[u.username] || {}).device_name || "", render: (u) => {
            const sel = h("select", { "aria-label": "Default runner of " + u.username, onchange: async (e) => {
              try { await api("PUT", "users/" + u.id + "/default-device", { device_id: e.target.value || null }); toast("Default runner of " + u.username + " saved"); } catch (err) { toast(err.message, true); go("users"); } } },
              h("option", { value: "", text: "(none)" }), (S.devices || []).map((d) => h("option", { value: d.id, text: d.name })));
            sel.value = (runAs[u.username] || {}).device_id || ""; return sel; } },
          { label: "Last sign-in", value: (u) => u.last_login || "", render: (u) => u.last_login ? h("span", { title: local(u.last_login) }, ago(u.last_login)) : "never" },
          { label: "", sort: false, render: (u) => h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: () => userEditDialog(u) }, "Edit"),
            h("button", { type: "button", onclick: async () => { try { await api("PATCH", "users/" + u.id, { enabled: !u.enabled }); go("users"); } catch (e) { toast(e.message, true); } } }, u.enabled ? "Disable" : "Enable"),
            h("button", { type: "button", onclick: async () => { if (!confirm("Reset the password of " + u.username + "?")) return; const p = await api("POST", "users/" + u.id + "/reset-password");
              modal("Temporary password", [secretBox(u.username, p.temporary_password, "Give it to the user securely. They must change it at first sign-in. Shown only once.")], []); } }, "Reset password"),
            u.mfa_enabled ? h("button", { type: "button", title: "For a lost phone: they set two-step sign-in up again at their next sign-in", onclick: async () => {
              if (!confirm("Reset two-step sign-in for " + u.username + "? Their sign-ins end, and they set it up again.")) return;
              try { await api("POST", "users/" + u.id + "/reset-mfa"); toast("Two-step sign-in reset for " + u.username); go("users"); } catch (e) { toast(e.message, true); } } }, "Reset two-step") : null,
            u.username !== S.user.username ? h("button", { type: "button", class: "danger", onclick: async () => {
              if (!confirm("Delete " + u.username + "? Their sign-ins, API keys, team places, own vault values and every rule naming them go. The audit trail keeps their name.")) return;
              try { await api("DELETE", "users/" + u.id); toast("Deleted " + u.username); go("users"); } catch (e) { toast(e.message, true); } } }, "Delete") : null) }]}));
  },

  async dashboards(main) {
    const r = await api("GET", "dashboards");
    S.dashId = r.dashboards.some((d) => d.id === S.dashId) ? S.dashId : (r.dashboards[0] || {}).id;
    const cur = r.dashboards.find((d) => d.id === S.dashId);
    const pick = h("select", { "aria-label": "Dashboard", onchange: (e) => { S.dashId = e.target.value; go("dashboards"); } }, r.dashboards.map((d) => h("option", { value: d.id, text: d.name })));
    if (cur) pick.value = cur.id;
    const grid = h("div", { class: "dash-grid" });
    main.replaceChildren(...header("Dashboards", "Business figures your agents record (outputs marked KPI) and figures about the runs, on dashboards you keep to yourself or share with roles and teams.",
      h("button", { type: "button", class: "primary", onclick: () => dashboardDialog(null) }, "New dashboard")),
      r.dashboards.length ? h("div", { class: "toolbar" }, pick, cur && cur.can_edit ? h("button", { type: "button", onclick: () => dashboardDialog(cur) }, "Edit") : null,
        cur && cur.can_edit ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete the dashboard " + cur.name + "?")) return; try { await api("DELETE", "dashboards/" + cur.id); S.dashId = null; go("dashboards"); } catch (e) { toast(e.message, true); } } }, "Delete") : null,
        cur && cur.shared_with.length ? h("span", { class: "muted small", text: "Shared with " + cur.shared_with.join(", ") }) : null) : h("p", { class: "muted", text: "No dashboards yet. Mark an agent's outputs as KPI in Agent Studio, then make a dashboard." }),
      grid);
    if (!cur) return;
    let tz = ""; try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch (_) { /* UTC */ }
    const d = await api("GET", "dashboards/" + cur.id + "/data" + (tz ? "?tz=" + encodeURIComponent(tz) : ""));
    grid.replaceChildren(...d.widgets.map(widgetCard));
  },

  async tests(main) {
    await loadRefs();
    const [r, pools] = await Promise.all([api("GET", "test-sets"), can("devices.view") ? api("GET", "pools").catch(() => ({ pools: [] })) : { pools: [] }]);
    S.pools = pools.pools;
    const score = (x) => x ? (x.status === "running" ? "running…" : x.passed + " / " + x.total) : "not run";
    main.replaceChildren(...header("Agent tests", "Test sets run an agent with chosen inputs and check what it gives back, also for AI answers (contains, matches, close to a number, one of). Each run of a set is scored and kept.",
      can("repo.checkin") ? h("button", { type: "button", class: "primary", onclick: () => testSetDialog(null) }, "New test set") : null),
      dataTable({ rows: r.sets, search: "Search test sets", exportName: "test-sets", empty: "No test sets yet.",
        columns: [
          { label: "Test set", value: (t) => t.name, render: (t) => h("div", {}, h("strong", { text: t.name }), h("div", { class: "muted small", text: t.description || "" })) },
          { label: "Agent", value: (t) => botName(t.bot_path) },
          { label: "Cases", value: (t) => t.cases.length },
          { label: "Last score", value: (t) => t.last_run ? t.last_run.passed / Math.max(1, t.last_run.total) : -1,
            render: (t) => t.last_run ? h("button", { type: "button", class: "link", onclick: () => testRunDialog(t.last_run.id) }, h("span", { class: "pill " + (t.last_run.status === "passed" ? "success" : t.last_run.status === "running" ? "running" : "failed"), text: score(t.last_run) }), " ", ago(t.last_run.started)) : h("span", { class: "muted", text: "not run" }) },
          { label: "", sort: false, render: (t) => h("div", { class: "row-actions tight" },
            can("runs.start") ? h("button", { type: "button", class: "primary", onclick: () => runTestSetDialog(t) }, "Run") : null,
            h("button", { type: "button", onclick: () => testHistoryDialog(t) }, "History"),
            can("repo.checkin") ? h("button", { type: "button", onclick: () => testSetDialog(t) }, "Edit") : null,
            can("repo.checkin") ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete the test set " + t.name + " and its results?")) return;
              try { await api("DELETE", "test-sets/" + t.id); go("tests"); } catch (e) { toast(e.message, true); } } }, "Delete") : null) }] }));
  },

  async apitasks(main) {
    await loadRefs();
    const [r, pools] = await Promise.all([api("GET", "apitasks"), can("devices.view") ? api("GET", "pools") : { pools: [] }]);
    S.pools = pools.pools;
    main.replaceChildren(...header("Agent APIs", "An agent behind its own address and token, for other systems to call like a web service: the call runs the agent and answers with its outputs.",
      h("button", { type: "button", class: "primary", onclick: () => apiTaskDialog() }, "New API task")),
      dataTable({ rows: r.tasks, search: "Search Agent APIs", exportName: "api-tasks", empty: "No Agent APIs yet.",
        columns: [
          { label: "API task", value: (t) => t.name, render: (t) => h("div", {}, h("strong", { text: t.name }), h("div", { class: "muted small mono", text: "POST " + location.origin + t.url })) },
          { label: "Agent", value: (t) => botName(t.bot_path) },
          { label: "Runs on", value: (t) => t.serverless ? "Command Center (serverless)" : t.pool_id ? "Pool " + ((S.pools.find((p) => p.id === t.pool_id) || {}).name || t.pool_id) : deviceName(t.device_id) },
          { label: "Waits up to", value: (t) => t.timeout_seconds, render: (t) => t.timeout_seconds + " s" },
          { label: "Calls", value: (t) => t.calls, render: (t) => h("span", {}, String(t.calls), t.last_used ? h("span", { class: "muted small", text: " \u00B7 last " + ago(t.last_used) }) : null) },
          { label: "Status", value: (t) => t.enabled ? "On" : "Off", render: (t) => pill(t.enabled ? "active" : "paused") },
          { label: "", sort: false, render: (t) => h("div", { class: "row-actions tight" },
            h("button", { type: "button", onclick: async () => { try { await api("PATCH", "apitasks/" + t.id, { enabled: !t.enabled }); go("apitasks"); } catch (e) { toast(e.message, true); } } }, t.enabled ? "Switch off" : "Switch on"),
            h("button", { type: "button", onclick: async () => {
              if (!confirm("Make a new token for " + t.name + "? The current one stops working at once.")) return;
              try { const n = await api("POST", "apitasks/" + t.id + "/token"); apiTaskShown(t.name, t.url, n.token); } catch (e) { toast(e.message, true); } } }, "New token"),
            h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete API task " + t.name + "?")) return; try { await api("DELETE", "apitasks/" + t.id); go("apitasks"); } catch (e) { toast(e.message, true); } } }, "Delete")) }]}));
  },

  async teams(main) {
    const [r, u] = await Promise.all([api("GET", "teams"), api("GET", "users").catch(() => ({ users: [] }))]);
    S.users = u.users;
    main.replaceChildren(...header("Teams", "Groups of people. Name a team as team:finance in folder permissions, vault folders and work queue members; adding someone to the team gives them all of it.",
      can("teams.manage") ? h("button", { type: "button", class: "primary", onclick: () => teamDialog() }, "New team") : null),
      dataTable({ rows: r.teams, search: "Search teams", exportName: "teams", empty: "No teams yet.",
        columns: [
          { label: "Team", value: (t) => t.name, render: (t) => h("div", {}, h("strong", { text: t.name }), h("div", { class: "muted small", text: t.description || "" })) },
          { label: "Leads", value: (t) => t.members.filter((m) => m.team_role === "lead").map((m) => m.username).join(", ") },
          { label: "Members", value: (t) => t.members.length, render: (t) => h("div", { class: "chips" }, t.members.map((m) => h("span", { class: "chip", text: m.username + (m.team_role === "lead" ? " (lead)" : "") }))) },
          { label: "Use in rules as", value: (t) => "team:" + t.name, render: (t) => h("code", { text: "team:" + t.name }) },
          { label: "", sort: false, render: (t) => h("div", { class: "row-actions tight" },
            can("teams.manage") || t.members.some((m) => m.username === S.user.username && m.team_role === "lead") ? h("button", { type: "button", onclick: () => teamMembersDialog(t) }, "Members") : null,
            can("teams.manage") ? h("button", { type: "button", onclick: () => teamDialog(t) }, "Edit") : null,
            can("teams.manage") ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete team " + t.name + "?")) return; try { await api("DELETE", "teams/" + t.id); go("teams"); } catch (e) { toast(e.message, true); } } }, "Delete") : null) }]}));
  },

  async licenses(main) {
    const [st, asg, ur, dv, use] = await Promise.all([api("GET", "license"), api("GET", "license/assignments"), api("GET", "users"), api("GET", "devices").catch(() => ({ devices: [] })),
      api("GET", "license/usage?days=90").catch(() => null)]);
    const L = st.license || {};
    const TYPES = [["developer", "Developer", "user"], ["citizen_developer", "Citizen Developer", "user"], ["attended_runner", "Attended Runner", "user"], ["unattended_runner", "Unattended Runner", "device"]];
    const held = new Set(asg.assignments.map((a) => a.principal_type + "|" + a.principal + "|" + a.license_type));
    const STATE = { unlicensed: ["No license installed", "pending"], evaluation: ["Evaluation", "queued"], evaluation_ended: ["Evaluation ended", "failed"],
      active: ["Active", "success"], expiring: ["Expiring soon", "queued"], grace: ["Expired: grace period", "failed"], expired: ["Expired", "failed"],
      database_required: ["Needs SQL Server", "failed"] };
    const [stateText, stateCls] = STATE[L.state] || [L.state, ""];
    const toggle = (type, principal, on) => async () => {
      try { await api("PUT", "license/assignments", { license_type: type, principal, assigned: on }); go("licenses"); } catch (e) { toast(e.message, true); go("licenses"); }
    };
    const seatCell = (type, kind, principal) => {
      const on = held.has(kind + "|" + principal + "|" + type);
      const box = h("input", { type: "checkbox", checked: on, "aria-label": type, disabled: !can("users.manage"), onchange: (e) => toggle(type, principal, e.target.checked)() });
      return box;
    };
    const fileInput = h("input", { type: "file", accept: ".lic,.json,.txt", "aria-label": "License file" });
    const paste = h("textarea", { rows: 3, placeholder: "Or paste the license file text here" });
    const install = async () => {
      let text = paste.value.trim();
      if (!text && fileInput.files && fileInput.files[0]) text = (await fileInput.files[0].text()).trim();
      if (!text) { toast("Choose or paste a license file first", true); return; }
      try { await api("POST", "license", { content: text }); toast("License installed"); const me = await api("GET", "auth/me"); showLicenseBanner(me.license_banner); go("licenses"); }
      catch (e) { toast(e.message, true); }
    };
    const seats = L.seats || {};
    main.replaceChildren(...header("Licenses", "The license of this Command Center and who holds which seat. Developer and Citizen Developer seats let people check in agents; a runner needs an Unattended Runner seat to take scheduled, triggered, API and queue work; an Attended Runner seat lets a person run agents on their own runner."),
      h("div", { class: "card" },
        h("div", { class: "card-head" }, h("h2", { text: "License" }), h("span", { class: "pill " + stateCls, text: stateText })),
        L.state === "unlicensed" ? h("p", { class: "muted", text: "No license is installed, so nothing is limited. Install the license file you received from ONEOFFICEAUTOMATION to apply its seats and expiry date." })
          : L.state === "evaluation" || L.state === "evaluation_ended" ? h("p", { class: "muted", text: (L.state === "evaluation" ? "This is a 60-day evaluation: " + L.days_left + " days left (until " + L.evaluation_ends + "). "
            : "The 60-day evaluation ended on " + L.evaluation_ends + ". ") + "It allows 2 developers, 2 attended and 2 unattended runners; seats are given out automatically on first use. Install the license file you received from ONEOFFICEAUTOMATION." })
          : kv([["Licensed to", L.customer], ["Edition", L.edition], ["License id", L.id], ["Valid", (L.issued || "") + " to " + (L.expires || "") + (L.days_left !== undefined && L.days_left >= 0 ? " (" + L.days_left + " days left)" : "")],
            ["Grace period", L.grace_days !== undefined ? L.grace_days + " days, until " + L.grace_until : null], ["Issued for", L.bound_to]]),
        L.problem ? h("p", { class: "error", text: L.problem }) : null,
        L.install_id ? h("p", { class: "muted" }, "Install id of this Command Center: ", h("code", { text: L.install_id }), ". Send it with a license request; a license issued for it works only here.") : null,
        L.requires_server_database ? h("div", { class: "note warn" }, h("strong", { text: "Move to Microsoft SQL Server before licensing. " }),
          h("span", { text: "The evaluation runs on the built-in database; a licensed installation runs on SQL Server (PostgreSQL also works). " + L.database_steps })) : null,
        can("settings.manage") && !L.requires_server_database ? h("div", { class: "form-card" }, h("h3", { text: "Install a license" }), h("label", {}, "License file", fileInput), paste,
          h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: install }, "Install license"))) : null),
      h("div", { class: "card" }, h("h2", { text: "Seats" }),
        dataTable({ rows: TYPES.map(([t, label]) => ({ t, label, total: seats[t] ? seats[t].total : -1, used: seats[t] ? seats[t].used : 0 })), search: null, exportName: "license-seats",
          columns: [{ label: "License type", value: (x) => x.label }, { label: "In use", value: (x) => x.used },
            { label: "Seats", value: (x) => x.total < 0 ? "unlimited" : x.total },
            { label: "", sort: false, render: (x) => x.total < 0 ? h("span", { class: "muted", text: "no license" }) : h("div", { class: "seat-bar" }, progressBar(x.total ? 100 * x.used / x.total : 100, x.used >= x.total ? "bad" : "")) }] })),
      use ? licenseUsageCard(use) : null,
      h("div", { class: "card" }, h("h2", { text: "People" }),
        dataTable({ rows: ur.users.filter((u) => u.enabled !== false), search: "Search people", exportName: "license-people",
          columns: [{ label: "User", value: (u) => u.username, render: (u) => h("div", {}, h("strong", { text: u.username }), u.display_name ? h("div", { class: "small muted", text: u.display_name }) : null) },
            ...TYPES.filter(([, , k]) => k === "user").map(([t, label]) => ({ label, value: (u) => held.has("user|" + u.username + "|" + t) ? 1 : 0, render: (u) => seatCell(t, "user", u.username) }))] })),
      h("div", { class: "card" }, h("h2", { text: "Runners" }),
        dataTable({ rows: dv.devices, search: "Search runners", exportName: "license-runners",
          columns: [{ label: "Runner", value: (d) => d.name, render: (d) => h("div", {}, h("strong", { text: d.name }), h("div", { class: "small muted", text: d.hostname || "" })) },
            { label: "Status", value: (d) => d.status, render: (d) => pill(d.status) },
            { label: "Unattended Runner", value: (d) => held.has("device|" + d.id + "|unattended_runner") ? 1 : 0, render: (d) => seatCell("unattended_runner", "device", d.id) }] })));
  },
  async operations(main) {
    await loadRefs();
    S.ops = S.ops || { days: "30", bot: "" };
    const days = h("select", { "aria-label": "Period" }, [["1", "Last 24 hours"], ["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["365", "Last year"]].map(([v, l]) => h("option", { value: v, text: l })));
    days.value = S.ops.days;
    const bot = h("select", { "aria-label": "Agent" }, h("option", { value: "", text: "All agents" }), S.bots.filter((b) => isAgent(b.path)).map((b) => h("option", { value: b.path, text: botName(b.path) })));
    bot.value = S.ops.bot;
    const query = () => "days=" + S.ops.days + (S.ops.bot ? "&bot=" + encodeURIComponent(S.ops.bot) : "");
    days.addEventListener("change", () => { S.ops.days = days.value; go("operations"); });
    bot.addEventListener("change", () => { S.ops.bot = bot.value; go("operations"); });
    const o = await api("GET", "analytics?" + query());
    const t = o.totals;
    const csvBtn = (report, label) => h("button", { type: "button", class: "small", onclick: async () => {
      try { const res = await fetch("/api/analytics/export?report=" + report + "&" + query(), { credentials: "same-origin" }); if (!res.ok) throw new Error(res.statusText);
        download("operations-" + report + ".csv", await res.text(), "text/csv"); } catch (e) { toast(e.message, true); } } }, ic("download", "#475569", "sm"), label || "CSV");
    const tile = (label, value, sub) => h("div", { class: "card kpi" }, h("div", { class: "muted small", text: label }), h("div", { class: "kpi-value", text: value }), sub ? h("div", { class: "muted small", text: sub }) : null);
    const bars = (items, label, value, fmtv) => { const max = Math.max(1, ...items.map(value)); return h("div", { class: "hbars" }, items.map((it) => h("div", { class: "hbar" },
      h("span", { class: "hbar-label", text: label(it) }), h("span", { class: "hbar-track" }, (() => { const b = h("span", { class: "hbar-fill" }); b.style.width = (100 * value(it) / max) + "%"; return b; })()),
      h("span", { class: "hbar-value", text: fmtv ? fmtv(it) : String(value(it)) })))); };
    const rate = (x) => x === null || x === undefined ? "\u2014" : x + "%";
    const statTable = (rows, first, extra = []) => table([first, "Runs", "Success rate", "Average", "95% within", "Total time", ...extra.map((e) => e[0])], rows.map((r) => ({ data: r,
      cells: [first === "Agent" ? h("button", { type: "button", class: "link", onclick: () => agentStatsDialog(r.bot_path) }, botName(r.bot_path)) : String(r.runner || r.source || r.day || ""),
        String(r.runs), rate(r.success_rate), dur(r.avg_ms), dur(r.p95_ms), dur(r.total_ms), ...extra.map((e) => e[1](r))] })));
    main.replaceChildren(...header("Operations", "How agents ran in the period: volumes, success, run time, the agents that run most, take longest and fail most, and the use of each runner.", csvBtn("run_history", "Run history CSV")),
      h("div", { class: "toolbar" }, days, bot, o.period.capped ? h("span", { class: "pill failed", text: "Only the first " + o.period.runs_counted + " runs are counted" }) : null),
      h("div", { class: "kpis" }, tile("Runs", String(t.runs), t.active ? t.active + " still active" : null), tile("Success rate", rate(t.success_rate), t.success + " succeeded, " + t.failed + " failed, " + t.stopped + " stopped"),
        tile("Run time", dur(t.total_ms), "average " + dur(t.avg_ms)), tile("95% of runs finish within", dur(t.p95_ms), "longest " + dur(t.max_ms))),
      h("div", { class: "grid2 ops-grid" },
        h("div", { class: "card" }, h("h3", { text: "How long runs take" }), bars(o.duration_distribution, (x) => x.bucket, (x) => x.runs)),
        h("div", { class: "card" }, h("h3", { text: "Runs per day" }), o.by_day.length ? bars(o.by_day.slice(-31), (x) => x.day.slice(5), (x) => x.runs, (x) => x.runs + (x.failed ? " (" + x.failed + " failed)" : "")) : h("p", { class: "muted", text: "No runs in the period." }))),
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", { text: "Agents run most" }), csvBtn("most_run")), statTable(o.most_run, "Agent")),
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", { text: "Agents that take longest (average)" }), csvBtn("longest")), statTable(o.longest, "Agent", [["Longest", (r) => dur(r.max_ms)]])),
      h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", { text: "Agents that fail most" }), csvBtn("most_failed")),
        o.most_failed.length ? statTable(o.most_failed, "Agent", [["Failed", (r) => String(r.failed)], ["Failure rate", (r) => rate(r.failure_rate)], ["Errors", (r) => (r.top_errors || []).join(", ")]]) : h("p", { class: "muted", text: "No failures in the period." })),
      h("div", { class: "grid2 ops-grid" },
        h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", { text: "Runners" }), csvBtn("by_runner")), statTable(o.by_runner, "Runner")),
        h("div", { class: "card" }, h("div", { class: "card-head" }, h("h3", { text: "How runs were started" }), csvBtn("by_source")), statTable(o.by_source, "Source"))));
  },

  async audit(main) {
    const q = h("input", { type: "search", placeholder: "Search everything", "aria-label": "Search the audit trail" });
    const actor = h("input", { placeholder: "Who (user name)", "aria-label": "Who" }), action = h("input", { placeholder: "Action, e.g. repo. or run.start", "aria-label": "Action" });
    const otype = h("input", { placeholder: "Object type, e.g. bot", "aria-label": "Object type" });
    const result = h("select", { "aria-label": "Result" }, [["", "Any result"], ["ok", "Succeeded"], ["failed", "Failed"], ["denied", "Refused"], ["not_found", "Not found"]].map(([v, l]) => h("option", { value: v, text: l })));
    const from = h("input", { type: "date", "aria-label": "From" }), to = h("input", { type: "date", "aria-label": "To" });
    const sort = h("select", { "aria-label": "Order" }, h("option", { value: "newest", text: "Newest first" }), h("option", { value: "oldest", text: "Oldest first" }));
    const box = h("div"), status = h("span", { class: "muted" });
    let page = 0;
    const SIZE = 100;
    const params = () => [["q", q.value], ["actor", actor.value], ["action", action.value], ["object_type", otype.value], ["result", result.value], ["from", from.value], ["to", to.value], ["sort", sort.value]]
      .filter(([, v]) => v && v.trim()).map(([k, v]) => k + "=" + encodeURIComponent(v.trim())).join("&");
    const render = async () => {
      const r = await api("GET", "audit?limit=" + SIZE + "&offset=" + page * SIZE + "&" + params());
      const rows = r.records;
      const pages = Math.max(1, Math.ceil(r.total / SIZE));
      box.replaceChildren(h("p", { class: "muted", text: r.total + " record" + (r.total === 1 ? "" : "s") + " match." }), r.total > SIZE ? pager(page, pages, r.total, SIZE, (p) => { page = p; render(); }) : null, dataTable({ rows, search: false, pageSize: SIZE,
        columns: [{ label: "#", value: (a) => a.seq }, { label: "Time", value: (a) => a.ts, render: (a) => h("span", { title: a.ts }, local(a.ts)) }, { label: "Who", value: (a) => a.actor },
          { label: "Action", value: (a) => a.action, render: (a) => h("span", { class: "tag", text: a.action }) }, { label: "Object", value: (a) => (a.object_type || "") + " " + (a.object_id || "") },
          { label: "Result", value: (a) => a.result, render: (a) => h("span", { class: "pill " + (a.result === "ok" ? "success" : "failed"), text: a.result }) },
          { label: "Details", value: (a) => a.details || "", render: (a) => h("span", { class: "mono small", text: a.details || "" }) }] }));
    };
    for (const el of [q, actor, action, otype]) el.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(() => { page = 0; render(); }, 300); });
    for (const el of [result, from, to, sort]) el.addEventListener("change", () => { page = 0; render(); });
    const exportCsv = h("button", { type: "button", onclick: async () => {
      try {
        const res = await fetch("/api/audit/export?" + params(), { credentials: "same-origin" });
        if (!res.ok) { const e = (await res.json().catch(() => ({}))).error || {}; throw new Error(e.message || res.statusText); }
        download("audit-trail.csv", await res.text(), "text/csv");
      } catch (e) { toast(e.message, true); } } }, ic("download", "#475569", "sm"), "Export CSV");
    const verify = h("button", { type: "button", onclick: async () => {
      const v = await api("GET", "audit/verify");
      status.textContent = v.valid ? "Chain intact: " + v.records + " records verified." : "TAMPERING DETECTED at record " + v.broken_at + ".";
      status.className = v.valid ? "ok-text" : "error";
    } }, "Verify integrity");
    main.replaceChildren(...header("Audit Trail", "Every sign-in, change, run and credential use. Records are hash-chained, so editing history is detectable."),
      h("div", { class: "toolbar" }, q, actor, action, otype, result), h("div", { class: "toolbar" }, h("label", { class: "inline" }, "From ", from), h("label", { class: "inline" }, "To ", to), sort, exportCsv, verify, status), box);
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
    // AI calls are recorded when the run ends
    if (!["queued", "running"].includes(r.status) && st.ai === undefined) st.ai = (await api("GET", "runs/" + id + "/ai-calls").catch(() => ({ calls: [] }))).calls;
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
    if (st.ai && st.ai.length) tabsDef.push(["ai", "AI calls (" + st.ai.length + ")"]);
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
    } else if (st.tab === "ai") {
      // each model call in order: what it was for, the model and connection, tokens and time, what was masked and the tools it asked for (prompts are never kept)
      pane = table(["When", "Purpose", "Model", "Tokens", "Time", "Masked", "Tools"], (st.ai || []).map((c) => ({ cells: [
        "+" + ((Date.parse(c.ts) - Date.parse(r.started || c.ts)) / 1000).toFixed(1) + "s", c.purpose || "", (c.model || "") + (c.connection ? " (" + c.connection + ")" : ""),
        (c.input_tokens || 0) + " + " + (c.output_tokens || 0), c.latency_ms ? dur(c.latency_ms) : "",
        c.masked && Object.keys(c.masked).length ? Object.entries(c.masked).map(([k, n]) => k + " " + n).join(", ") : "",
        Array.isArray(c.tool_calls) ? c.tool_calls.join(", ") : ""] })));
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
    can("analytics.view") && isAgent(b.path) ? h("button", { type: "button", onclick: () => agentStatsDialog(b.path) }, "Statistics") : null,
    isAgent(b.path) ? h("button", { type: "button", onclick: () => analysisDialog(b.path) }, "Code analysis") : null,
    can("repo.checkin") && ["edit", "manage"].includes(b.access) ? h("button", { type: "button", onclick: () => moveBotDialog(b) }, "Move") : null,
    can("repo.checkin") ? h("button", { type: "button", onclick: () => copyBotDialog(b) }, "Copy") : null,
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
      kv([["Status", pill(d.status)], ["Host name", d.hostname], ["Operating system", d.os], ["Agent version", (d.agent_version || "") + (d.version_status && d.version_status !== "ok" ? " (newer than the Command Center: it gets no work until the Command Center is updated)" : "")], ["Last seen", local(d.last_seen) + " (" + ago(d.last_seen) + ")"],
        ["Type", d.device_type === "single_user" ? "Single-user" : "Multi-user"], ["Default runner of", (d.default_users || []).join(", ") || "nobody"], ["Log level", d.log_level || "info"],
        ["Screen", d.session_state ? SCREEN[d.session_state] + ": " + SCREEN_HELP[d.session_state] : "not reported yet"], ["After unattended runs", AFTER_RUN[d.after_run || "nothing"]], ["Toolkits", d.toolkits ? h("details", {}, h("summary", { text: d.toolkits.split(",").length + " toolkits" }), h("div", { class: "muted", text: d.toolkits.split(",").join(", ") })) : "not reported yet"],
        ["Registered", local(d.registered) + " by " + (d.registered_by || "")], ["Runner id", h("code", { text: d.id })]]),
      h("h3", { text: "Now" }), active.runs.length ? runTable(active.runs, (r) => openRun(r.id)) : h("p", { class: "muted", text: "Idle." }),
      h("h3", { text: "History" }), runTable(hist.runs, (r) => openRun(r.id)));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

async function openUser(u) {
  const body = h("div", { class: "run-detail" });
  openDrawer([h("span", { class: "avatar sm", text: (u.display_name || u.username).charAt(0).toUpperCase() }), h("span", { text: u.display_name || u.username })], body, []);
  try {
    const a = await api("GET", "audit?limit=30&actor=" + encodeURIComponent(u.username));
    body.replaceChildren(kv([["User name", u.username], ["Name", [u.first_name, u.last_name].filter(Boolean).join(" ")], ["Description", u.description], ["Role", roleLabel(u.role)],
      ["Further roles", (u.roles || []).slice(1).map(roleLabel).join(", ")], ["Status", u.enabled ? (u.must_change ? "Must change password" : "Active") : "Disabled"], ["Last sign-in", local(u.last_login) || "never"]]),
      h("h3", { text: "Recent activity" }), table(["Time", "Action", "Object", "Result"], a.records.filter((x) => x.actor === u.username).map((x) => ({ data: x, cells: [local(x.ts), x.action, (x.object_type || "") + " " + (x.object_id || ""), x.result] }))));
  } catch (e) { body.replaceChildren(h("p", { class: "error", text: e.message })); }
}

// ---------- dialogs ----------
/** Requests for versions to go live: approve or reject (someone else's), withdraw (your own); the latest decisions below. */
async function approvalsDialog() {
  const [pending, all] = await Promise.all([api("GET", "repo/production-requests?status=pending"), api("GET", "repo/production-requests")]);
  const decide = (q, how) => async () => {
    const note = how === "withdraw" ? "" : prompt((how === "approve" ? "Approve" : "Reject") + " v" + q.version + " of " + botName(q.path) + ". Note (optional):", "");
    if (note === null) return;
    try { await api("POST", "repo/production-requests/" + q.id + "/" + how, { note }); toast(how === "approve" ? "v" + q.version + " is live" : how === "reject" ? "Rejected" : "Withdrawn"); approvalsDialog(); }
    catch (e) { toast(e.message, true); }
  };
  const done = all.requests.filter((q) => q.status !== "pending").slice(0, 20);
  modal("Production approvals", [
    h("p", { class: "muted", text: pending.approval_on ? "A version goes live in production only when someone with the approval right, other than the person who asked, approves it." : "Approval before production is off (Administration > Settings); requests made before still wait here." }),
    pending.requests.length ? table(["Agent", "Version", "Asked by", "When", "What changed", ""], pending.requests.map((q) => ({ data: q, cells: [botName(q.path), "v" + q.version, q.requested_by, ago(q.requested), q.comment || "",
      h("div", { class: "row-actions tight" },
        q.can_decide ? h("button", { type: "button", class: "primary", onclick: decide(q, "approve") }, "Approve") : null,
        q.can_decide ? h("button", { type: "button", class: "danger", onclick: decide(q, "reject") }, "Reject") : null,
        q.requested_by === S.user.username ? h("button", { type: "button", onclick: decide(q, "withdraw") }, "Withdraw") : null)] })))
      : h("p", { text: "Nothing is waiting." }),
    done.length ? [h("h3", { text: "Decided" }), table(["Agent", "Version", "Asked by", "Result", "By", "When", "Note"], done.map((q) => ({ data: q, cells: [botName(q.path), "v" + q.version, q.requested_by, q.status, q.decided_by || "", ago(q.decided), q.note || ""] })))] : null], []);
}

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
        (S.approvalOn ? can("repo.checkin") : can("repo.production")) && v.version !== b.production_version ? h("button", { type: "button", onclick: async () => {
          let comment;
          if (S.approvalOn && (comment = prompt("Ask for v" + v.version + " to go live in production. What changed (for the approver)?", "")) === null) return;
          try {
            const res = await api("POST", "repo/production", { path: b.path, version: v.version, comment });
            toast(res.production_request ? "Sent for approval: v" + v.version : "Live production is now v" + v.version); closeModal(); go("automation");
          } catch (e) { toast(e.message, true); } } }, S.approvalOn ? "Ask to make live" : "Set as production") : null,
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

/** A select of run-as users (people with a default runner are marked), for schedules and triggers. */
async function runAsSelect(value) {
  const sel = h("select", { "aria-label": "Run as" }, h("option", { value: "", text: "(the owner, on the runner chosen here)" }));
  try {
    const r = await api("GET", "devices/runasusers");
    sel.append(...r.users.map((u) => h("option", { value: u.username, text: u.username + (u.device_id ? " (default runner " + (u.device_name || u.device_id) + ")" : " (no default runner)") })));
  } catch (_) { /* not allowed to list them */ }
  sel.value = value || "";
  return sel;
}

async function scheduleDialog() {
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
  const runAs = await runAsSelect();
  const noRunner = h("label", { class: "check" }, h("input", { type: "checkbox" }), "Use the run-as user's default runner");
  const opts = scheduleOptionSelects();
  modal("New schedule", [h("label", {}, "Name", name), h("label", {}, "Agent (live production version)", bot), h("div", { class: "grid2" }, h("label", {}, "Runner", dev), h("label", {}, "Or a runner pool", pool)),
    h("div", { class: "grid2" }, h("label", {}, "Run as (credentials are checked against this person)", runAs), noRunner),
    h("label", {}, "Repeat", kind), everyBox, atBox, daysBox,
    h("div", { class: "grid2" }, h("label", {}, "Time zone", tz), h("label", {}, "Skip the dates of a holiday calendar", cal)), opts.row, h("label", {}, "Inputs (JSON, optional)", inputs)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const useDefault = runAs.value && noRunner.firstChild.checked;
        const body = { name: name.value || "Schedule", bot_path: bot.value, device_id: pool.value || useDefault ? null : dev.value, pool_id: useDefault ? null : pool.value || null, holiday_calendar: cal.value || null,
          kind: kind.value, time_zone: tz.value, inputs: parseJson(inputs.value, {}), run_as: runAs.value || null,
          missed_runs: opts.missed.value, overlap: opts.overlap.value };
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

async function triggerEditDialog(t) {
  const name = h("input", { value: t.name }), owner = h("input", { value: t.created_by || "" });
  const runAs = await runAsSelect(t.run_as);
  modal("Edit " + t.name, [h("label", {}, "Name", name), h("label", {}, "Owner (the trigger fires with the owner's permissions)", owner), h("label", {}, "Run as (credentials are checked against this person)", runAs)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const body = { name: name.value, run_as: runAs.value || null };
        if (owner.value.trim() && owner.value.trim() !== t.created_by) body.owner = owner.value.trim();
        await api("PUT", "triggers/" + t.id, body); closeModal(); go("triggers");
      } catch (e) { toast(e.message, true); } } }, "Save")]);
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

/** Owners (people), managers, participants and consumers (people, roles or teams) of a vault folder. */
function lockerMembersDialog(l) {
  const list = (v) => (Array.isArray(v) ? v : String(v || "").split(",")).map((x) => x.trim()).filter(Boolean).join(", ");
  const owners = h("input", { value: list(l.owners) }), managers = h("input", { value: list(l.managers), placeholder: "user:carl, team:finance-admins" });
  const participants = h("input", { value: list(l.participants), placeholder: "team:finance" }), consumers = h("input", { value: list(l.consumers), placeholder: "role:bot_runner, team:finance" });
  modal("Members of " + l.name, [
    h("label", {}, "Owners (user names): everything, members included", owners),
    h("label", {}, "Managers: add, change and delete every credential", managers),
    h("label", {}, "Participants: see the folder, add credentials and change their own", participants),
    h("label", {}, "Consumers: their agents may use the credentials", consumers),
    h("p", { class: "muted", text: "Managers, participants and consumers can be users (user:jane), roles (role:bot_runner) or teams (team:finance)." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PUT", "lockers/" + encodeURIComponent(l.name) + "/members", { owners: owners.value, managers: managers.value, participants: participants.value, consumers: consumers.value }); closeModal(); go("credentials"); }
    catch (e) { toast(e.message, true); } } }, "Save")]);
}

/** A credential's attributes, owner, description, user-provided attributes and the caller's own values. */
async function credentialDetails(l, name) {
  let c;
  try { c = await api("GET", "vault/credential?locker=" + encodeURIComponent(l.name) + "&name=" + encodeURIComponent(name)); } catch (e) { toast(e.message, true); return; }
  const ref = l.name + "/" + name;
  const userAttrs = c.attributes.filter((a) => a.user_provided).map((a) => a.attribute);
  const desc = h("input", { value: c.description || "", disabled: !c.can_change });
  const ua = h("input", { value: userAttrs.join(", "), disabled: !c.can_change, placeholder: "password" });
  const owner = h("input", { value: c.owner || "", disabled: !["owner", "manager"].includes(l.my_role) });
  const mine = userAttrs.map((a) => {
    const v = h("input", { type: "password", autocomplete: "new-password", placeholder: c.my_values.includes(a) ? "(your value is set; type to replace)" : "your value" });
    return h("div", { class: "grid2" }, h("label", {}, "Your " + a, v), h("div", { class: "row-actions" },
      h("button", { type: "button", class: "primary", onclick: async () => { try { await api("PUT", "vault/my-value", { locker: l.name, name, attribute: a, value: v.value }); v.value = ""; toast("Your " + a + " is saved"); credentialDetails(l, name); } catch (e) { toast(e.message, true); } } }, "Save mine"),
      c.my_values.includes(a) ? h("button", { type: "button", onclick: async () => { try { await api("DELETE", "vault/my-value?locker=" + encodeURIComponent(l.name) + "&name=" + encodeURIComponent(name) + "&attribute=" + encodeURIComponent(a)); credentialDetails(l, name); } catch (e) { toast(e.message, true); } } }, "Clear mine") : null));
  });
  const flags = {};
  const attrRows = c.attributes.map((a) => {
    const f = flags[a.attribute] = { d: h("input", { value: a.description || "", disabled: !c.can_change, placeholder: "what it is for", "aria-label": "Description of " + a.attribute }),
      m: h("input", { type: "checkbox", checked: a.masked !== false, disabled: !c.can_change, "aria-label": "Masked" }),
      p: h("input", { type: "checkbox", checked: !!a.password_only, disabled: !c.can_change, "aria-label": "Password fields only" }) };
    const shown = h("span");
    return { data: a, cells: [h("code", { text: ref + "/" + a.attribute }), a.user_provided ? "each person's own" + (c.my_values.includes(a.attribute) ? " (yours is set)" : " (yours is not set)") : "shared, encrypted",
      f.d, h("label", { class: "check" }, f.m, "Masked"), h("label", { class: "check" }, f.p, "Password fields only"),
      h("div", {}, a.updated ? ago(a.updated) + " by " + a.updated_by : "", !a.user_provided && a.masked === false && c.can_change ? h("button", { type: "button", onclick: async () => {
        try { const v = await api("GET", "vault/value?locker=" + encodeURIComponent(l.name) + "&name=" + encodeURIComponent(name) + "&attribute=" + encodeURIComponent(a.attribute)); shown.replaceChildren(h("code", { text: v.value })); } catch (e) { toast(e.message, true); } } }, "Show") : null, shown)] };
  });
  modal("Credential " + ref, [
    table(["Attribute", "Value", "Description", "", "", "Updated"], attrRows),
    h("p", { class: "muted small", text: "Masked values are never shown (the default). Clear it for values that are not secret, such as a user name, so owners and managers can look them up. Password fields only: agents may type the value into password fields only." }),
    h("div", { class: "grid2" }, h("label", {}, "Description", desc), h("label", {}, "Owner", owner), h("label", {}, "User-provided attributes (each person stores their own)", ua)),
    userAttrs.length ? h("h3", { text: "Your values" }) : null, ...mine,
    h("p", { class: "muted", text: "A run reads a user-provided attribute from the person it runs as (the run-as user, or whoever started it)." })].filter(Boolean),
  c.can_change || ["owner", "manager"].includes(l.my_role) ? [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const body = { description: desc.value, user_attributes: ua.value.split(",").map((x) => x.trim()).filter(Boolean),
        attributes_info: Object.fromEntries(Object.entries(flags).map(([k, f]) => [k, { description: f.d.value, masked: f.m.checked, password_only: f.p.checked }])) };
      if (owner.value.trim() && owner.value.trim() !== c.owner) body.owner = owner.value.trim();
      await api("PUT", "vault/credential?locker=" + encodeURIComponent(l.name) + "&name=" + encodeURIComponent(name), body); closeModal(); go("credentials");
    } catch (e) { toast(e.message, true); } } }, "Save details")] : []);
}

/** The master key: where it comes from, its fingerprint, and rotating it (every stored value is encrypted again). */
async function vaultKeyDialog() {
  let st;
  try { st = await api("GET", "vault/status"); } catch (e) { toast(e.message, true); return; }
  modal("Vault key", [kv([["Key from", st.key_source === "file" ? "Key file in the data folder" : st.key_source === "environment" ? "OOA_MASTER_KEY environment variable" : st.key_source_detail], ["Key fingerprint", h("code", { text: st.key_fingerprint })],
    ["Vault folders", String(st.vault_folders)], ["Shared Global values", String(st.values)], ["People's own values", String(st.personal_values)], ["External vault links", String(st.external_links)]]),
    h("p", { class: "muted", text: st.can_rotate ? "Rotating makes a new master key and encrypts every stored value again with it, in one transaction. The old key file is kept beside the new one (master.key.old-…) until you remove it; back it up with the database." :
      "The key is kept outside the data folder, so it cannot be changed here. To change it: stop the Command Center, run ooa vault rekey --new-key-env VAR (every value is encrypted again with the new key), put the new key where the old one is kept, and start again." })],
  st.can_rotate ? [h("button", { type: "button", class: "danger", onclick: async () => {
    if (!confirm("Rotate the vault master key now? Agents keep working; every value is encrypted again.")) return;
    try { const r = await api("POST", "vault/rotate"); toast("Key rotated: " + r.re_encrypted + " values encrypted again. New fingerprint " + r.key_fingerprint); vaultKeyDialog(); } catch (e) { toast(e.message, true); } } }, "Rotate key")] : []);
}

function apiTaskDialog() {
  const name = h("input", { placeholder: "Invoice lookup" }), bot = botSelect(), dev = deviceSelect();
  const pool = h("select", { "aria-label": "Runner pool" }, h("option", { value: "", text: "(none: use the runner)" }), (S.pools || []).map((p) => h("option", { value: p.id, text: p.name })));
  const timeout = h("input", { type: "number", min: "1", max: "300", value: "60" });
  const runners = h("div", { class: "grid2" }, h("label", {}, "Runner", dev), h("label", {}, "Or a runner pool", pool));
  const serverless = h("input", { type: "checkbox", onchange: () => { runners.hidden = serverless.checked; } });
  modal("New API task", [h("label", {}, "Name", name), h("label", {}, "Agent (live production version)", bot),
    h("label", { class: "check" }, serverless, " Run on the Command Center, with no runner (serverless)"),
    h("p", { class: "muted small", text: "Serverless suits agents that only call web services and work on data: REST, SOAP, the cloud apps, Excel, PDF, JSON and the like. They run for at most 5 minutes, four at a time, with files only in their own workspace." }),
    runners, h("label", {}, "Wait for the result up to (seconds, 1-300)", timeout),
    h("p", { class: "muted", text: "Calls run as you, at high priority. When the agent takes longer than the wait, the caller gets 202 with the run id and asks again." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const r = await api("POST", "apitasks", serverless.checked ? { name: name.value, bot_path: bot.value, serverless: true, timeout_seconds: Number(timeout.value) }
        : { name: name.value, bot_path: bot.value, device_id: pool.value ? null : dev.value, pool_id: pool.value || null, timeout_seconds: Number(timeout.value) });
      apiTaskShown(name.value, r.url, r.token); go("apitasks");
    } catch (e) { toast(e.message, true); } } }, "Create")]);
}

function apiTaskShown(name, url, token) {
  const full = location.origin + url;
  modal("API task " + name, [secretBox("Address", full), secretBox("Token (send it as X-OOA-Task-Token or Authorization: Bearer)", token, "Shown only once. Make a new one if it is lost."),
    h("h3", { text: "Example" }), h("pre", { class: "mono", text: 'curl -X POST "' + full + '" -H "X-OOA-Task-Token: <token>" -H "Content-Type: application/json" -d \'{"inputs": {"text": "hello"}}\'' }),
    h("p", { class: "muted", text: "The answer is {status, run_id, outputs, error}: 200 when the run finished, 202 while it still runs (then GET " + url.replace(/\/run$/, "/runs/") + "<run_id> with the same token)." })], []);
}

/** An agent's code analysis report (latest version), with Analyse again. */
async function analysisDialog(path) {
  let r;
  try { r = await api("GET", "repo/analysis?path=" + encodeURIComponent(path)); } catch (e) { toast(e.message, true); return; }
  modal("Code analysis of " + botName(path) + " v" + r.version, [
    h("p", {}, r.passed ? h("span", { class: "pill success", text: "No errors" }) : h("span", { class: "pill failed", text: r.errors + " error" + (r.errors === 1 ? "" : "s") }), " ", h("span", { class: "muted", text: r.warnings + " warning(s)" + (r.analyzed ? " \u00B7 analysed " + ago(r.analyzed) : "") })),
    r.violations.length ? table(["Severity", "Rule", "Step", "Finding"], r.violations.map((v) => ({ data: v, cells: [pill(v.severity === "error" ? "failed" : "pending"), v.rule.replace(/_/g, " "), v.step || "", v.message] })))
      : h("p", { class: "muted", text: "Nothing to report." })],
  [h("button", { type: "button", onclick: async () => { try { await api("POST", "repo/analyze", { path }); analysisDialog(path); } catch (e) { toast(e.message, true); } } }, "Analyse again")]);
}

/** The code analysis policy: each rule on or off, error or warning, its settings, and blocking check-ins with errors. */
async function policyDialog() {
  let pol;
  try { pol = await api("GET", "repo/policy"); } catch (e) { toast(e.message, true); return; }
  const edit = can("settings.manage");
  const enforce = h("input", { type: "checkbox", checked: pol.enforce_at_checkin, disabled: !edit });
  const rows = Object.entries(pol.rules).map(([id, r]) => {
    const on = h("input", { type: "checkbox", checked: r.enabled, disabled: !edit, "aria-label": "Use " + id });
    const sev = h("select", { disabled: !edit, "aria-label": "Severity of " + id }, h("option", { value: "error", text: "Error" }), h("option", { value: "warning", text: "Warning" }));
    sev.value = r.severity;
    const params = Object.entries(r.params || {}).map(([k, v]) => ({ k, el: h("input", { value: Array.isArray(v) ? v.join(", ") : String(v), disabled: !edit, "aria-label": id + " " + k, title: k }), list: Array.isArray(v), num: typeof v === "number" }));
    return { id, r, on, sev, params };
  });
  modal("Code analysis", [h("p", { class: "muted", text: "Agents are checked against these rules at every check-in, and on demand from an agent's details. Reports are kept per version." }),
    h("label", { class: "check" }, enforce, "Refuse check-ins that have errors"),
    table(["Use", "Rule", "Severity", "Settings"], rows.map((x) => ({ data: x, cells: [x.on, h("div", {}, h("strong", { text: x.id.replace(/_/g, " ") }), h("div", { class: "muted small", text: x.r.description })), x.sev,
      h("div", {}, x.params.map((p) => h("label", { class: "inline" }, p.k.replace(/_/g, " ") + " ", p.el)))] })))],
  [edit ? h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const rules = {};
      for (const x of rows) {
        const params = {};
        for (const p of x.params) params[p.k] = p.list ? p.el.value.split(",").map((t) => t.trim()).filter(Boolean) : p.num ? Number(p.el.value) : p.el.value;
        rules[x.id] = { enabled: x.on.checked, severity: x.sev.value, params };
      }
      await api("PUT", "repo/policy", { enforce_at_checkin: enforce.checked, rules }); closeModal(); toast("Code analysis policy saved");
    } catch (e) { toast(e.message, true); } } }, "Save") : null,
    h("button", { type: "button", onclick: async () => {
      try { const res = await fetch("/api/repo/analysis/report", { credentials: "same-origin" }); if (!res.ok) throw new Error(res.statusText); download("code-analysis.csv", await res.text(), "text/csv"); } catch (e) { toast(e.message, true); } } },
      ic("download", "#475569", "sm"), "Report for all agents (CSV)")].filter(Boolean));
}

function teamDialog(t) {
  const name = h("input", { value: t ? t.name : "", placeholder: "finance", disabled: !!t }), desc = h("input", { value: t ? t.description || "" : "" });
  modal(t ? "Edit team " + t.name : "New team", [h("label", {}, "Name (used in rules as team:name)", name), h("label", {}, "Description", desc)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { const r = await api(t ? "PUT" : "POST", "teams" + (t ? "/" + t.id : ""), { name: name.value.trim(), description: desc.value }); closeModal(); if (!t) teamMembersDialog({ id: r.id, name: r.name, members: [] }); else go("teams"); }
      catch (e) { toast(e.message, true); } } }, "Save")]);
}

function teamMembersDialog(t) {
  const rows = t.members.map((m) => ({ username: m.username, role: m.team_role }));
  const box = h("div");
  const draw = () => box.replaceChildren(rows.length ? table(["Person", "Role", ""], rows.map((r, i) => {
    const role = h("select", { "aria-label": "Role of " + r.username, onchange: (e) => { r.role = e.target.value; } }, h("option", { value: "member", text: "Member" }), h("option", { value: "lead", text: "Lead (changes the members)" }));
    role.value = r.role;
    return { data: r, cells: [r.username, role, h("button", { type: "button", onclick: () => { rows.splice(i, 1); draw(); } }, "Remove")] };
  })) : h("p", { class: "muted", text: "No members yet." }));
  const who = h("select", { "aria-label": "Person" }, (S.users || []).map((u) => h("option", { value: u.username, text: (u.display_name || u.username) + " (" + u.username + ")" })));
  const typed = h("input", { placeholder: "or a user name" });
  draw();
  modal("Members of " + t.name, [box, h("div", { class: "grid2" }, h("label", {}, "Add", who), h("label", {}, "User name (instead)", typed)),
    h("button", { type: "button", onclick: () => { const u = typed.value.trim() || who.value; if (u && !rows.some((x) => x.username === u)) rows.push({ username: u, role: "member" }); typed.value = ""; draw(); } }, "+ Add")],
  [h("button", { type: "button", class: "primary", onclick: async () => { try { await api("PUT", "teams/" + t.id + "/members", { members: rows }); closeModal(); go("teams"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

/** One agent's statistics for the Operations period. */
async function agentStatsDialog(path) {
  let a, vp = null;
  try { a = await api("GET", "analytics/agent?path=" + encodeURIComponent(path) + "&days=" + ((S.ops && S.ops.days) || "30")); } catch (e) { toast(e.message, true); return; }
  try { vp = await api("GET", "analytics/variables?path=" + encodeURIComponent(path) + "&days=" + ((S.ops && S.ops.days) || "30")); } catch (_) { vp = null; }
  const t = a.totals;
  modal("Statistics of " + botName(path), [kv([["Runs", String(t.runs)], ["Success rate", t.success_rate === null ? "\u2014" : t.success_rate + "%"], ["Average", dur(t.avg_ms)], ["95% within", dur(t.p95_ms)], ["Longest", dur(t.max_ms)],
    ["Last run", a.last_run ? local(a.last_run.created) + " (" + a.last_run.status + ")" : "none"], ["Last failure", a.last_failure ? local(a.last_failure.created) + " (" + a.last_failure.code + ")" : "none"]]),
    a.errors.length ? [h("h3", { text: "Errors" }), table(["Error", "Runs"], a.errors.map((e) => ({ data: e, cells: [h("code", { text: e.code }), String(e.runs)] })))] : null,
    h("h3", { text: "Runners" }), table(["Runner", "Runs", "Success rate", "Average"], a.by_runner.map((r) => ({ data: r, cells: [String(r.runner), String(r.runs), r.success_rate === null ? "\u2014" : r.success_rate + "%", dur(r.avg_ms)] }))),
    vp && vp.variables.length ? [h("h3", { text: "Outputs (variable profile)" }), table(["Output", "Had a value", "Range", "Most common"], vp.variables.map((v) => ({ data: v, cells: [h("code", { text: v.variable }),
      v.runs_with_value + " of " + v.runs, v.min !== undefined ? v.min + " \u2013 " + v.max + " (avg " + v.avg + ")" : "", (v.common || []).map((c) => c.value + " (" + c.runs + ")").join(", ")] })))] : null].flat().filter(Boolean), []);
}

function consumersDialog(l) {
  const cons = h("input", { value: l.consumers || "" }), owners = h("input", { value: l.owners || "" });
  modal("Access to " + l.name, [h("label", {}, "Consumers", cons), h("label", {}, "Owners (user names)", owners)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PATCH", "lockers/" + l.name, { consumers: cons.value, owners: owners.value }); closeModal(); go("credentials"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function globalDialog(g) {
  const name = h("input", { value: g ? g.name : "", placeholder: "reports_folder" }), val = h("textarea", { value: g ? JSON.stringify(g.value) : "", placeholder: '"D:/Reports"  or  1000  or  {"a": 1}' });
  const changeable = h("input", { type: "checkbox", checked: !!(g && g.changeable) });
  if (g) name.disabled = true;
  modal(g ? "Edit " + g.name : "New shared value", [h("label", {}, "Name", name), h("label", {}, "Value (JSON)", val),
    h("label", { class: "check" }, changeable, "People may set their own value (their runs use it instead)"),
    h("p", { class: "muted", text: "Turning this off removes everyone's own values." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PUT", "globals/" + name.value, { value: parseJson(val.value, ""), changeable: changeable.checked }); closeModal(); go("globals"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

/** New or changed saved connection: the toolkit's address and settings, and vault references for its secrets. */
function savedConnDialog(c) {
  const kinds = S.connKinds || [];
  const name = h("input", { value: c ? c.name : "", placeholder: "ServiceNow production" });
  const desc = h("input", { value: c ? c.description || "" : "", placeholder: "What it is for (optional)" });
  const kind = h("select", { "aria-label": "Toolkit" }, kinds.map((k) => h("option", { value: k.package, text: k.name })));
  if (c) { name.disabled = true; kind.value = c.package; kind.disabled = true; }
  const box = h("div", { class: "conn-inputs" });
  const inputs = {};
  const draw = () => {
    const k = kinds.find((x) => x.package === kind.value) || kinds[0];
    if (!k) { box.replaceChildren(h("p", { class: "muted", text: "No toolkit takes a connection." })); return; }
    box.replaceChildren(...k.inputs.map((i) => {
      const cur = c ? (i.credential ? (c.credentials || {})[i.name] : (c.values || {})[i.name]) : "";
      const f = h("input", { value: cur === undefined ? "" : String(cur), placeholder: i.credential ? "vault folder/credential/attribute" : "" });
      inputs[i.name] = { el: f, credential: i.credential };
      return h("label", {}, i.name + (i.credential ? " (vault reference)" : ""), f, h("span", { class: "muted small", text: i.description }));
    }));
  };
  kind.addEventListener("change", () => { for (const k of Object.keys(inputs)) delete inputs[k]; draw(); });
  draw();
  modal(c ? "Edit " + c.name : "New connection", [h("label", {}, "Name", name), h("label", {}, "Toolkit", kind), h("label", {}, "Description", desc), box,
    h("p", { class: "muted", text: "Leave a setting empty to set it on each step instead. Secrets are references to Credential vault, read when the agent runs, as the person it runs as." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      const values = {}, credentials = {};
      for (const [n, x] of Object.entries(inputs)) { const v = x.el.value.trim(); if (v) (x.credential ? credentials : values)[n] = v; }
      try {
        await api("PUT", "saved-connections/" + encodeURIComponent(name.value.trim()), { package: kind.value, description: desc.value.trim(), values, credentials });
        closeModal(); toast("Saved connection " + name.value.trim()); go("savedconn");
      } catch (e) { toast(e.message, true); }
    } }, "Save")]);
}

function myGlobalDialog(g) {
  const val = h("textarea", { value: g.my_value === undefined ? JSON.stringify(g.value) : JSON.stringify(g.my_value) });
  modal("Your value of " + g.name, [h("p", { class: "muted", text: "Runs that run as you use this value instead of the shared one (" + JSON.stringify(g.value) + ")." }), h("label", {}, "Value (JSON)", val)],
    [g.my_value !== undefined ? h("button", { type: "button", onclick: async () => { try { await api("DELETE", "globals/" + g.name + "/mine"); closeModal(); go("globals"); } catch (e) { toast(e.message, true); } } }, "Use the shared value") : null,
    h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PUT", "globals/" + g.name + "/mine", { value: parseJson(val.value, "") }); closeModal(); toast("Your value of " + g.name + " is saved"); go("globals"); } catch (e) { toast(e.message, true); } } }, "Save mine")].filter(Boolean));
}

function userEditDialog(u) {
  const first = h("input", { value: u.first_name || "" }), last = h("input", { value: u.last_name || "" }), disp = h("input", { value: u.display_name || "" });
  const desc = h("input", { value: u.description || "", placeholder: "for example Accounts payable team lead" });
  const extra = new Set((u.roles || []).slice(1));
  const boxes = (S.roles || Object.keys(ROLE_LABELS)).filter((x) => x !== "admin" && x !== u.role).map((x) => {
    const c = h("input", { type: "checkbox", checked: extra.has(x), onchange: (e) => { if (e.target.checked) extra.add(x); else extra.delete(x); } });
    return h("label", { class: "check" }, c, roleLabel(x));
  });
  modal("Edit " + u.username, [h("div", { class: "grid2" }, h("label", {}, "First name", first), h("label", {}, "Last name", last), h("label", {}, "Display name", disp), h("label", {}, "Description", desc)),
    h("h3", { text: "Further roles" }), h("p", { class: "muted", text: "Main role: " + roleLabel(u.role) + ". A person has every permission of each of their roles." }), h("div", { class: "pick-list" }, ...boxes)],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PATCH", "users/" + u.id, { first_name: first.value, last_name: last.value, display_name: disp.value || u.username, description: desc.value, further_roles: Array.from(extra) });
      closeModal(); toast("Saved " + u.username); go("users"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

const SCREEN = { unlocked: "Unlocked", locked: "Locked", no_desktop: "No desktop" };
const SCREEN_HELP = { unlocked: "someone is signed in and the screen is usable", locked: "agents that use the screen cannot run until someone signs in", no_desktop: "the runner is a Windows service: only agents that need no screen can run" };
const AFTER_RUN = { nothing: "Leave the screen as it is", lock: "Lock the screen", sign_out: "Sign out" };

/** A one-time key and the commands that register a runner with it: an installed copy, the MSI, or the setup package. */
function registerRunnerDialog() {
  const temp = h("input", { type: "checkbox" });
  modal("Register a runner", [
    h("p", { text: "Creates a one-time key (valid 24 hours) and shows the command for the runner machine." }),
    h("label", { class: "check" }, temp, "Temporary runner (VDI or pooled desktop): removed by itself after a time without contact, freeing its seat")],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const k = await api("POST", "devices/registration-key", { hours: 24, temporary: temp.checked });
      const url = location.origin;
      modal("Register a runner", [
        h("p", { text: "On the runner machine, run one of these (the key works once, expires in 24 hours and is shown only now):" }),
        secretBox("ONEOFFICEAUTOMATION already installed (in its folder)", "bin\\ooa.bat agent register --url " + url + " --key " + k.key, "Then: bin\\ooa.bat agent install-task (starts at sign-in, for agents that use the screen)."),
        secretBox("With the MSI (runs as a Windows service)", "msiexec /i ooa.msi /qn ROLE=Runner URL=" + url + " REGISTRATION_KEY=" + k.key, ""),
        secretBox("With the setup package", "Install.cmd -Silent -Role Runner -Url " + url + " -RegistrationKey " + k.key, ""),
        k.temporary ? h("p", { class: "note warn", text: "Temporary: this runner is removed after the time set in Settings without contact." }) : null,
        h("p", { class: "muted", text: "On the Command Center computer itself you do not need this: ooa server (START-OOA.cmd) registers a runner for it." })], []);
    } catch (e) { toast(e.message, true); }
  } }, "Create key")]);
}

function runnerSettingsDialog(devices) {
  const picked = new Set();
  const level = h("select", {}, h("option", { value: "", text: "(keep)" }), ["error", "warning", "info", "debug"].map((x) => h("option", { value: x, text: x })));
  const type = h("select", {}, h("option", { value: "", text: "(keep)" }), h("option", { value: "single_user", text: "Single-user" }), h("option", { value: "multi_user", text: "Multi-user" }));
  const after = h("select", {}, h("option", { value: "", text: "(keep)" }), Object.entries(AFTER_RUN).map(([v, t]) => h("option", { value: v, text: t })));
  modal("Update runners", [h("p", { class: "muted", text: "Choose runners, then the settings to change. Runners pick up a new log level with their next heartbeat." }),
    h("div", { class: "pick-list" }, ...devices.map((d) => h("label", { class: "check" }, h("input", { type: "checkbox", onchange: (e) => { if (e.target.checked) picked.add(d.id); else picked.delete(d.id); } }),
      d.name + " (" + (d.log_level || "info") + ", " + (d.device_type === "single_user" ? "single-user" : "multi-user") + ")"))),
    h("div", { class: "grid2" }, h("label", {}, "Log level", level), h("label", {}, "Runner type", type), h("label", {}, "After unattended runs", after)),
    h("p", { class: "muted", text: "Single-user: the default runner of one person. Multi-user: the default runner of several people. After unattended runs, the runner can lock its screen or sign out, so nobody walks up to a signed-in machine." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    const b = { ids: Array.from(picked) };
    if (level.value) b.log_level = level.value;
    if (type.value) b.device_type = type.value;
    if (after.value) b.after_run = after.value;
    try { const r = await api("PATCH", "devices", b); closeModal(); toast("Updated " + r.updated + " runner(s)"); go("devices"); } catch (e) { toast(e.message, true); } } }, "Update checked runners")]);
}

/** User-provided vault values the signed-in person still has to fill in. */
async function credentialRequestsDialog() {
  const r = await api("GET", "vault/requests");
  modal("Credential requests", [h("p", { class: "muted", text: "Agents that run as you read these values from the vault. Fill them in once; you can change them later in the credential's details." }),
    r.requests.length ? table(["Credential", "Attribute", "", ""], r.requests.map((q) => {
      const v = h("input", { type: "password", autocomplete: "new-password", placeholder: "your value", "aria-label": "Your " + q.attribute });
      return { data: q, cells: [h("div", {}, h("code", { text: q.locker + "/" + q.name }), h("div", { class: "muted small", text: q.credential_description || "" })),
        h("div", {}, h("strong", { text: q.attribute }), q.password_only ? h("span", { class: "tag", text: "Password fields only" }) : null, h("div", { class: "muted small", text: q.description || "" })), v,
        h("button", { type: "button", class: "primary", onclick: async () => { try { await api("PUT", "vault/my-value", { locker: q.locker, name: q.name, attribute: q.attribute, value: v.value }); v.value = ""; toast("Saved"); credentialRequestsDialog(); } catch (e) { toast(e.message, true); } } }, "Save")] };
    })) : h("p", { text: "Nothing to fill in. \u2728" })], []);
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
  modal("External vault for " + l.name, [h("p", { class: "muted", text: "Keep secrets in your company vault. Agents still ask this vault folder, so owners, consumers and the Audit Trail work as before; the value is read live from the external vault." }),
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
  const reference = h("input", { placeholder: "e.g. PO-2026-0042 (optional)" });
  // a workflow with a start form asks for its fields; otherwise any starting data as JSON
  const formBox = h("div"), dataBox = h("label", {}, "Starting data (JSON, optional)", data);
  let fields = [];
  const loadForm = async () => {
    fields = [];
    try {
      const f = await api("GET", "workflows/form?path=" + encodeURIComponent(sel.value));
      fields = ((f.form && f.form.fields) || []).filter((x) => x.id && !["heading", "paragraph"].includes(x.type)).map((x) => {
        const el = x.type === "checkbox" ? h("input", { type: "checkbox" }) : (x.type === "dropdown" || x.type === "radio") ? h("select", {}, h("option", { value: "", text: "(choose)" }), (x.options || []).map((o) => h("option", { value: String(o), text: String(o) })))
          : x.type === "textarea" ? h("textarea", { rows: 3 }) : h("input", { type: x.type === "number" ? "number" : x.type === "date" ? "date" : "text" });
        return { x, el };
      });
      formBox.replaceChildren(...(fields.length ? [h("h3", { text: "Request" }), ...fields.map(({ x, el }) => x.type === "checkbox" ? h("label", { class: "check" }, el, x.label || x.id)
        : h("label", {}, h("span", {}, x.label || x.id, x.required ? h("span", { class: "req", text: " *" }) : null), el))] : []));
      dataBox.hidden = fields.length > 0;
    } catch (_) { formBox.replaceChildren(); dataBox.hidden = false; }
  };
  sel.addEventListener("change", loadForm);
  modal("Start a workflow", [h("label", {}, "Workflow (live production version)", sel), formBox, dataBox, h("label", {}, "Reference (to find this request again)", reference),
    h("p", { class: "muted", text: "Stages use this data as $name. Agent tasks run as you on any online runner; human tasks and approvals go to the Task inbox." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      let d = parseJson(data.value, {});
      if (fields.length) { d = {}; for (const { x, el } of fields) { if (x.type === "checkbox") d[x.id] = el.checked; else if (el.value !== "") d[x.id] = x.type === "number" ? Number(el.value) : el.value; } }
      const r = await api("POST", "workflows/start", { path: sel.value, data: d, reference: reference.value.trim() || null }); closeModal(); toast("Started " + r.id + (r.reference ? " (" + r.reference + ")" : "")); S.page = "workflows"; await go("workflows"); openWorkflowRun({ id: r.id }); }
    catch (e) { toast(e.message, true); } } }, "Start")]);
  if (sel.value) loadForm();
}

async function openWorkflowRun(w) {
  const body = h("div", { class: "run-detail" });
  const load = async () => {
    const r = await api("GET", "workflows/runs");
    const cur = r.runs.find((x) => x.id === w.id);
    if (!cur) { body.replaceChildren(h("p", { class: "muted", text: "Not found." })); return; }
    const evLabel = { agent_started: "Agent started", agent_finished: "Agent finished", agent_failed: "Agent failed", agent_stopped: "Agent stopped", task_created: "Task created", task_completed: "Task completed",
      approved: "Approved", rejected: "Rejected", decided: "Decided", notified: "Notified", waiting: "Waiting", failed: "Failed", cancelled: "Cancelled" };
    body.replaceChildren(kv([["Workflow", botName(cur.path) + " v" + cur.version], ["Reference", cur.reference || null], ["Status", pill(cur.status === "completed" ? "success" : cur.status)], ["Current stage", cur.stage || "\u2014"],
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

/** What a schedule does with times it missed, and when its previous run is still going (empty value = the default). */
function scheduleOptionSelects(s) {
  const missed = h("select", { "aria-label": "Missed times" }, h("option", { value: "once", text: "Run once for them" }), h("option", { value: "skip", text: "Skip them" }));
  const overlap = h("select", { "aria-label": "Previous run still going" }, h("option", { value: "allow", text: "Start another run" }), h("option", { value: "skip", text: "Skip this time" }));
  missed.value = (s && s.missed_runs) || "once"; overlap.value = (s && s.overlap) || "allow";
  return { missed, overlap, row: h("div", { class: "grid2" }, h("label", {}, "Times missed while the Command Center was down", missed), h("label", {}, "When the previous run is still going", overlap)) };
}

async function scheduleEditDialog(s) {
  const name = h("input", { value: s.name });
  const runAs = await runAsSelect(s.run_as);
  const cal = h("select", { "aria-label": "Holiday calendar" }, h("option", { value: "", text: "(none)" }), (S.calendars || []).map((c) => h("option", { value: c.name, text: c.name })));
  cal.value = s.holiday_calendar || "";
  const owner = h("input", { value: s.created_by || "", placeholder: "user name" });
  const opts = scheduleOptionSelects(s);
  modal("Edit " + s.name, [h("label", {}, "Name", name), h("label", {}, "Skip the dates of a holiday calendar", cal), h("label", {}, "Owner (runs use the owner's permissions)", owner),
    h("label", {}, "Run as", runAs), opts.row],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const body = { name: name.value, holiday_calendar: cal.value || null, run_as: runAs.value || null, missed_runs: opts.missed.value, overlap: opts.overlap.value };
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
  const file = h("input", { type: "file", accept: ".csv,.json,.xlsx,text/csv,application/json,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "aria-label": "CSV, JSON or Excel file" });
  const preview = h("p", { class: "muted" });
  const sheet = h("input", { placeholder: "first sheet", "aria-label": "Sheet" });
  const sheetBox = h("label", { hidden: true }, "Excel sheet (empty for the first)", sheet);
  let workbook = null;
  file.addEventListener("change", async () => {
    const f = file.files[0]; if (!f) return;
    workbook = null; sheetBox.hidden = true;
    if (/\.xlsx$/i.test(f.name)) {
      // the Command Center reads the workbook; it is sent as Base64
      const buf = new Uint8Array(await f.arrayBuffer()); let bin = "";
      for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
      workbook = btoa(bin); text.value = ""; sheetBox.hidden = false; preview.textContent = f.name + " loaded (Excel).";
    } else { text.value = await f.text(); preview.textContent = f.name + " loaded."; }
  });
  modal("Add work items to " + q.name, [h("p", { class: "muted", text: "Paste a JSON list of objects or CSV with a header row, or choose a CSV, JSON or Excel file. A reference column (or _reference) names the item; priority (or _priority) puts it first." }),
    file, sheetBox, text, preview],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const t = text.value.trim();
      // CSV and Excel are read by the Command Center, which converts each cell to its field's type in the work item model
      const r = workbook ? await api("POST", "queues/" + q.id + "/items/xlsx", { file: workbook, sheet: sheet.value.trim() || null })
        : t.startsWith("[") || t.startsWith("{") ? await api("POST", "queues/" + q.id + "/items", { items: [].concat(parseJson(t, [])) })
          : await api("POST", "queues/" + q.id + "/items/csv", { csv: t });
      closeModal(); toast("Added " + r.added + " items"); if (after) after();
    } catch (e) { toast(e.message, true); } } }, "Add items")]);
}

async function openQueue(q) {
  const st = { status: "", q: "" }, sel = new Set();
  const status = h("select", { "aria-label": "Item status" }, [["", "All items"], ["new", "New"], ["in_progress", "In progress"], ["review", "To validate"], ["retry", "Waiting to retry"], ["success", "Succeeded"], ["failed", "Failed"], ["deferred", "Deferred"]]
    .map(([v, l]) => h("option", { value: v, text: l })));
  const search = h("input", { type: "search", placeholder: "Search reference or data", "aria-label": "Search items" });
  const list = h("div"), summary = h("div", { class: "q-summary" });
  const act = (action) => h("button", { type: "button", onclick: async () => {
    if (!sel.size) { toast("Tick items first", true); return; }
    if (action === "delete" && !confirm("Delete " + sel.size + " item(s)? Items being worked on are kept.")) return;
    const body = { ids: [...sel] };
    if (action === "defer") {
      // a time: the item is picked up again by itself after it; empty: it waits until someone presses Retry
      const when = prompt("Defer until when? (YYYY-MM-DD HH:MM, your time; empty = until someone presses Retry)", "");
      if (when === null) return;
      if (when.trim()) {
        const d = new Date(when.trim().replace(" ", "T"));
        if (isNaN(d)) { toast("Write the time like 2026-11-02 09:00", true); return; }
        body.until = d.toISOString();
      }
    }
    try { await api("POST", "queues/" + q.id + "/items/" + action, body); sel.clear(); load(); } catch (e) { toast(e.message, true); } } },
    action === "retry" ? "Retry selected" : action === "defer" ? "Defer selected" : "Delete selected");
  async function load() {
    const [r, all] = await Promise.all([api("GET", "queues/" + q.id + "/items?limit=200&status=" + st.status + "&q=" + encodeURIComponent(st.q)), api("GET", "queues")]);
    const cur = all.queues.find((x) => x.id === q.id) || q;
    summary.replaceChildren(...["new", "in_progress", "review", "retry", "success", "failed", "deferred"].filter((k) => k !== "review" || cur.counts.review)
      .map((k) => h("span", { class: "pill " + ({ new: "queued", in_progress: "running", review: "pending", retry: "pending", success: "success", failed: "failed", deferred: "stopped" })[k],
      text: (k === "review" ? "to validate" : k.replace("_", " ")) + ": " + (cur.counts[k] || 0) })), cur.overdue ? h("span", { class: "pill failed", text: cur.overdue + " past service level" }) : null);
    list.replaceChildren(table(["", "Reference", "Status", "Attempts", "Priority", "Added", "Result or error", ""], r.items.map((it) => {
      const box = h("input", { type: "checkbox", "aria-label": "Select " + (it.reference || it.id), checked: sel.has(it.id), onchange: (e) => { if (e.target.checked) sel.add(it.id); else sel.delete(it.id); } });
      const err = parseMaybe(it.error), res = parseMaybe(it.result);
      return { data: it, cells: [can("queues.work") ? box : "", h("div", {}, h("strong", { text: it.reference || it.id }), h("div", { class: "muted small mono", text: String(it.data).slice(0, 120) })),
        it.status === "deferred" ? h("span", {}, pill(it.status), h("div", { class: "muted small", text: it.defer_until ? "until " + local(it.defer_until) : "until Retry" })) : pill(it.status), String(it.attempts), String(it.priority), ago(it.created),
        it.run_id ? h("button", { type: "button", class: "link", onclick: () => openRun(it.run_id) }, err ? (err.code || "") + " " + (err.message || "") : res ? JSON.stringify(res).slice(0, 80) : "view run")
          : h("span", { class: "muted", text: err ? (err.code || JSON.stringify(err)) : "" }),
        it.status === "review" && can("queues.work") ? h("button", { type: "button", class: "primary", onclick: () => validateDialog(q, it, load) }, "Validate")
          : h("button", { type: "button", onclick: () => itemDialog(q, it.id, load) }, it.status === "in_progress" ? "View" : "Open")] };
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
    h("button", { type: "button", onclick: async () => {
      const items = confirm("Include the waiting items (new, retry, deferred)? OK = with items, Cancel = settings only.");
      try { const d = await api("GET", "queues/" + q.id + "/export" + (items ? "?items=true" : "")); download(q.name.replace(/[^A-Za-z0-9_.-]+/g, "_") + ".ooaqueue.json", JSON.stringify(d, null, 2), "application/json"); }
      catch (e) { toast(e.message, true); } } }, "Export definition"),
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

function installTemplate(t) {
  const folder = h("input", { value: "Templates", "aria-label": "Folder" });
  const out = h("div");
  modal("Install " + t.name, [h("p", { class: "muted", text: "The template's agents go into the agent repository under this folder, as new agents you can change. Agents already there are kept as they are." }),
    h("label", {}, "Folder", folder), out],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const r = await api("POST", "templates/install", { source: t.source, id: t.id, folder: folder.value.trim() || "Templates" });
      out.replaceChildren(table(["Agent", "Result", "Problem"], r.results.map((x) => ({ data: x, cells: [botName(x.path), pill(x.status === "created" ? "success" : x.status === "skipped" ? "stopped" : "failed"), x.error ? x.error.message : x.status] }))));
      toast(r.ok ? "Installed in " + r.folder : "Nothing installed: fix the problems listed", !r.ok);
      if (r.ok) { S.repoFolder = r.folder; closeModal(); go("automation"); }
    } catch (e) { toast(e.message, true); } } }, "Install")]);
}

async function shareTemplateDialog() {
  await loadRefs();
  const agent = botSelect();
  const name = h("input", { maxlength: 120, "aria-label": "Template name" });
  const description = h("textarea", { rows: 3, maxlength: 2000, "aria-label": "Description" });
  const category = h("select", {}, ["Finance", "Human resources", "Sales", "Customer service", "IT", "Operations", "Documents", "Other"].map((c) => h("option", { value: c, text: c })));
  agent.addEventListener("change", () => { if (!name.value) name.value = botName(agent.value); });
  modal("Share an agent as a template", [h("p", { class: "muted", text: "Its live production version and the agents it calls go to your region's template catalogue. Once the service's operators approve it, every company in the region can install it. Only the agents are shared: never credentials, shared values or runs." }),
    h("label", {}, "Agent", agent), h("label", {}, "Template name", name), h("label", {}, "What it does", description), h("label", {}, "Category", category)],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      await api("POST", "templates/share", { path: agent.value, name: name.value.trim() || botName(agent.value), description: description.value.trim(), category: category.value });
      closeModal(); toast("Sent for approval: it shows in Templates once approved");
    } catch (e) { toast(e.message, true); } } }, "Share")]);
}

function uploadCatalogueDialog() {
  const file = h("input", { type: "file", accept: ".json,application/json", "aria-label": "Catalogue file" });
  modal("Upload a template catalogue", [h("p", { class: "muted", text: "For a Command Center without internet access: download templates.json from the releases page and upload it here. It must carry ONEOFFICEAUTOMATION's signature." }), file],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const f = file.files[0]; if (!f) throw new Error("Choose the catalogue file");
      const r = await api("POST", "templates/catalogue", { text: await f.text() });
      closeModal(); toast(r.templates + " templates available"); go("templates");
    } catch (e) { toast(e.message, true); } } }, "Upload")]);
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
/** Which agents use a toolkit at which required version range, and setting a new range on them (new versions, not made live). */
async function toolkitVersionsDialog(t) {
  let v;
  try { v = await api("GET", "toolkits/" + encodeURIComponent(t.id) + "/versions"); } catch (e) { toast(e.message, true); return; }
  const range = h("input", { value: "^" + String(v.installed).split(".")[0] + ".0.0", "aria-label": "Version range" });
  const out = h("div");
  modal(t.name + " " + v.installed + ": agents", [
    table(["Agent", "Latest", "Requires", "Installed version fits"], v.agents.map((a) => ({ data: a, cells: [botName(a.path), "v" + a.version, a.required || "(any version)", a.satisfied ? pill("success") : pill("failed")] }))),
    can("repo.checkin") ? h("div", { class: "grid2" }, h("label", {}, "Require this version range in every agent that uses it", range)) : null,
    h("p", { class: "muted", text: "Each agent gets a new version with the range (its live version stays until you choose the new one). Agents checked out by others are left as they are." }), out].filter(Boolean),
  can("repo.checkin") ? [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const r = await api("POST", "toolkits/" + encodeURIComponent(t.id) + "/update-agents", { range: range.value.trim() });
      out.replaceChildren(table(["Agent", "Result"], r.results.map((x) => ({ data: x, cells: [botName(x.path), x.status + (x.version ? " (v" + x.version + ")" : "") + (x.error ? ": " + x.error.message : "")] }))));
      toast("Updated " + r.results.filter((x) => x.status === "updated").length + " agent(s)");
    } catch (e) { toast(e.message, true); } } }, "Update agents")] : []);
}

function importQueueDialog() {
  const file = h("input", { type: "file", accept: ".json,application/json", "aria-label": "Queue definition file" });
  const name = h("input", { placeholder: "(the name in the file)" });
  const out = h("div");
  modal("Import a work queue", [h("p", { class: "muted", text: "A queue definition exported from a Command Center: settings, work item model, members and, when exported with them, waiting items. The agent and runner pool are linked when they exist here." }),
    file, h("label", {}, "Name here", name), out],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try {
      const f = file.files[0]; if (!f) throw new Error("Choose a definition file");
      const r = await api("POST", "queues/import", { definition: JSON.parse(await f.text()), name: name.value.trim() || null });
      out.replaceChildren(h("p", { class: "ok-text", text: "Made " + r.name + " with " + r.items + " item(s)." }), ...(r.notes || []).map((n) => h("p", { class: "muted", text: n })));
      toast("Imported " + r.name); go("queues");
    } catch (e) { toast(e.message, true); } } }, "Import")]);
}

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

/**
 * Validation (A1): the fields an agent read with too little confidence, the unsure ones marked. Approve sends the item
 * back to the agent with the checked values; Reject fails it with a reason.
 */
function validateDialog(q, it, after) {
  const res = parseMaybe(it.result) || {};
  const v = res.validation || {}, fields = v.fields || {}, conf = v.confidence || {}, min = Number(v.min_confidence || 0);
  const inputs = {};
  const rows = Object.keys(fields).map((k) => {
    const c = conf[k] === undefined ? null : Number(conf[k]);
    const unsure = c !== null && c < min;
    const el = h("input", { type: "text", value: fields[k] === null || fields[k] === undefined ? "" : String(fields[k]), "aria-label": k, class: unsure ? "unsure" : "" });
    inputs[k] = el;
    return h("label", {}, h("span", {}, k, c !== null ? h("span", { class: "pill " + (unsure ? "failed" : "success"), text: Math.round(c * 100) + "%" }) : null), el);
  });
  const reason = h("input", { type: "text", placeholder: "Why it is rejected (needed to reject)" });
  modal("Validate " + (it.reference || it.id), [
    h("p", { class: "muted", text: "The agent was not sure of the fields marked red (below " + Math.round(min * 100) + "%). Check them against the document, correct them and approve: the item goes back to the agent with these values." }),
    h("div", { class: "grid2" }, rows), h("label", {}, "Reject instead", reason)],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    const out = {}; for (const k of Object.keys(inputs)) out[k] = inputs[k].value === "" ? null : inputs[k].value;
    try { const r = await api("POST", "queues/" + q.id + "/items/" + it.id + "/validate", { fields: out }); closeModal(); toast(r.changed.length ? "Approved with " + r.changed.length + " correction(s)" : "Approved"); if (after) after(); }
    catch (e) { toast(e.message, true); } } }, "Approve"),
   h("button", { type: "button", class: "danger", onclick: async () => {
    try { await api("POST", "queues/" + q.id + "/items/" + it.id + "/validate", { reject: true, reason: reason.value.trim() }); closeModal(); toast("Rejected"); if (after) after(); }
    catch (e) { toast(e.message, true); } } }, "Reject")]);
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

const PROVIDER_HELP = {
  aws_bedrock: "Model id, for example anthropic.claude-sonnet-4-5 or amazon.nova-pro-v1:0. Endpoint: https://bedrock-runtime.REGION.amazonaws.com. Key: ACCESS_KEY_ID:SECRET in one credential.",
  aws_bedrock_sagemaker: "Model: the SageMaker endpoint name. Endpoint: https://runtime.sagemaker.REGION.amazonaws.com. Key: ACCESS_KEY_ID:SECRET in one credential.",
  google_vertex: "Model, for example gemini-2.5-flash. Endpoint: https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION. Key: a service-account JSON key or an access token.",
  azure_openai: "Model: the deployment name. Endpoint: your resource, for example https://myresource.openai.azure.com.",
  huggingface: "Model id on the Hugging Face router, or leave the endpoint set to your own TGI server.",
};

function connectionDialog(c) {
  const name = h("input", { value: c ? c.name : "", placeholder: "company-gpt", disabled: !!c });
  const provider = h("select", {}, [["openai", "OpenAI"], ["azure_openai", "Azure OpenAI"], ["anthropic", "Anthropic"], ["aws_bedrock", "Amazon Bedrock"], ["google_vertex", "Google Vertex AI"],
    ["aws_bedrock_sagemaker", "Amazon SageMaker endpoint"], ["huggingface", "Hugging Face"], ["openai_compatible", "OpenAI-compatible (local or hosted)"]].map(([v, l]) => h("option", { value: v, text: l })));
  const type = h("select", {}, h("option", { value: "standard", text: "Standard model" }), h("option", { value: "fine_tuned", text: "Fine-tuned model" }));
  const model = h("input", { value: c ? c.model || "" : "", placeholder: "model or deployment name" }), base = h("input", { value: c ? c.base_url || "" : "", placeholder: "https://... (optional)" });
  const key = h("input", { value: c ? c.key_ref || "" : "", placeholder: "vault folder/credential/attribute" }), desc = h("input", { value: c ? c.description || "" : "" });
  const roles = h("input", { value: c ? (c.allowed_roles || []).join(", ") : "", placeholder: "every role" });
  const lim = (k, max) => h("input", { type: "number", min: "0", max: String(max), value: c && c[k] ? String(c[k]) : "", placeholder: "no limit" });
  const monthly = lim("monthly_tokens", 1e12), perMinute = lim("calls_per_minute", 100000), perRun = lim("calls_per_run", 1000000);
  const analyzer = h("input", { value: c ? c.pii_analyzer_url || "" : "", placeholder: "https://presidio-analyzer.internal (optional)" });
  const contentCheck = h("select", {}, h("option", { value: "none", text: "No content check" }), h("option", { value: "moderation", text: "Check answers with the provider's moderation endpoint" }));
  contentCheck.value = c && c.content_check === "moderation" ? "moderation" : "none";
  const help = h("p", { class: "muted small" });
  const result = h("div");
  if (c) { provider.value = c.provider; type.value = c.model_type || "standard"; }
  const showHelp = () => { help.textContent = PROVIDER_HELP[provider.value] || ""; };
  provider.onchange = showHelp; showHelp();
  const values = () => ({ provider: provider.value, model_type: type.value, model: model.value.trim() || null, base_url: base.value.trim() || null, key_ref: key.value.trim() || null, description: desc.value,
    allowed_roles: roles.value.split(",").map((x) => x.trim()).filter(Boolean),
    monthly_tokens: Number(monthly.value || 0), calls_per_minute: Number(perMinute.value || 0), calls_per_run: Number(perRun.value || 0),
    pii_analyzer_url: analyzer.value.trim() || null, content_check: contentCheck.value });
  modal(c ? "Edit " + c.name : "New model connection", [h("div", { class: "grid2" }, h("label", {}, "Name", name), h("label", {}, "Provider", provider), h("label", {}, "Model", model), h("label", {}, "Type", type)),
    help, h("label", {}, "Endpoint", base),
    h("label", {}, "API key from the Credential vault", key), h("label", {}, "Roles that may use it (comma-separated)", roles), h("label", {}, "Description", desc),
    h("h3", { text: "Privacy and safety" }),
    h("div", { class: "grid2" }, h("label", {}, "Microsoft Presidio analyzer (masks names, places and IDs before the model sees them, with Hide personal data)", analyzer),
      h("label", {}, "Content check", contentCheck)),
    h("h3", { text: "Budget and limits" }),
    h("div", { class: "grid2" }, h("label", {}, "Tokens a month (in and out)" + (c && c.tokens_this_month ? "; " + c.tokens_this_month + " used this month" : ""), monthly),
      h("label", {}, "Calls a minute, per run (more calls wait)", perMinute), h("label", {}, "Calls a run (more calls fail)", perRun)),
    h("p", { class: "muted small", text: "When the month's tokens are used up, runs get the connection blocked and its AI steps fail with AI.BUDGET_REACHED until the next month or a higher budget. Empty or 0: no limit." }),
    h("p", { class: "muted", text: "Only the vault reference is stored here. Runners read the key from the vault while an agent runs, and only for agents whose owner may use that vault folder. Runs of other roles do not receive the connection." }),
    result],
  [h("button", { type: "button", onclick: async () => {
    result.replaceChildren(h("p", { class: "muted", text: "Testing..." }));
    try { const r = await api("POST", "connections/test", Object.assign({ name: name.value.trim() }, values()));
      result.replaceChildren(h("p", { class: r.ok ? "ok-text" : "error", text: r.ok ? "Connected in " + r.ms + " ms" + (r.answer ? ": " + r.answer : "") : r.message })); }
    catch (e) { result.replaceChildren(h("p", { class: "error", text: e.message })); } } }, "Test connection"),
  h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("PUT", "connections/" + encodeURIComponent(name.value.trim()), values());
      closeModal(); go(S.adminTab || "settings"); } catch (e) { toast(e.message, true); } } }, "Save connection")]);
}

/** One dashboard widget drawn from its figures: a number, bars, a line or a table. */
function widgetCard(w) {
  const title = w.title || (w.source === "runs" ? (w.metric || "runs").replace(/_/g, " ") : (w.agg || "sum") + " of " + w.kpi);
  const fmtNum = (v) => v === null || v === undefined ? "\u2014" : Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 }) + (w.metric === "success_rate" ? "%" : "");
  let body;
  if (w.error) body = h("p", { class: "error", text: w.error });
  else if (w.type === "number" || !w.type) body = h("div", { class: "kpi-value", text: fmtNum(w.value) });
  else if (w.type === "table") body = table([w.group === "agent" ? "Agent" : w.group === "value" ? "Value" : "Period", "Figure"], (w.points || []).map((p) => ({ cells: [p.label, fmtNum(p.value)] })));
  else if (w.type === "bars") {
    const pts = w.points || [], max = Math.max(1, ...pts.map((p) => Number(p.value) || 0));
    body = pts.length ? h("div", { class: "hbars" }, pts.map((p) => h("div", { class: "hbar" }, h("span", { class: "hbar-label", text: p.label }),
      h("span", { class: "hbar-track" }, (() => { const b = h("span", { class: "hbar-fill" }); b.style.width = (100 * (Number(p.value) || 0) / max) + "%"; return b; })()), h("span", { class: "hbar-value", text: fmtNum(p.value) })))) : h("p", { class: "muted", text: "No figures in this period." });
  } else {
    const pts = (w.points || []).filter((p) => p.value !== null), W = 320, H = 120;
    if (!pts.length) body = h("p", { class: "muted", text: "No figures in this period." });
    else {
      const max = Math.max(...pts.map((p) => Number(p.value))), min = Math.min(0, ...pts.map((p) => Number(p.value)));
      const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      svg.setAttribute("viewBox", "0 0 " + W + " " + (H + 18)); svg.setAttribute("class", "dash-line"); svg.setAttribute("role", "img"); svg.setAttribute("aria-label", title);
      const x = (i) => pts.length === 1 ? W / 2 : 6 + i * (W - 12) / (pts.length - 1), y = (v) => H - 4 - (H - 8) * (v - min) / Math.max(1e-9, max - min);
      const line = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
      line.setAttribute("points", pts.map((p, i) => x(i).toFixed(1) + "," + y(Number(p.value)).toFixed(1)).join(" ")); line.setAttribute("fill", "none"); line.setAttribute("stroke", "#2563eb"); line.setAttribute("stroke-width", "2");
      svg.append(line);
      if (pts.length <= 60) pts.forEach((p, i) => { const c = document.createElementNS("http://www.w3.org/2000/svg", "circle"); c.setAttribute("cx", x(i).toFixed(1)); c.setAttribute("cy", y(Number(p.value)).toFixed(1)); c.setAttribute("r", "3"); c.setAttribute("fill", "#2563eb"); svg.append(c); });
      for (const [i, p] of pts.length === 1 ? [[0, pts[0]]] : [[0, pts[0]], [pts.length - 1, pts[pts.length - 1]]]) {
        const t = document.createElementNS("http://www.w3.org/2000/svg", "text");
        t.setAttribute("x", String(Math.min(W - 40, Math.max(0, x(i) - 20)))); t.setAttribute("y", String(H + 14)); t.setAttribute("font-size", "10"); t.setAttribute("fill", "currentColor"); t.textContent = p.label;
        svg.append(t);
      }
      body = h("div", {}, svg, h("div", { class: "muted small", text: "Last: " + fmtNum(pts[pts.length - 1].value) + "  \u00B7  highest " + fmtNum(max) }));
    }
  }
  return h("div", { class: "card dash-card" + (w.type === "number" || !w.type ? " kpi" : "") }, h("div", { class: "muted small", text: /days?/.test(title) ? title : title + " (" + (w.days || 30) + " days)" }), body);
}

const DASH_EXAMPLE = [
  { title: "Amount paid", type: "number", kpi: "amount", agg: "sum", days: 30 },
  { title: "Amount by category", type: "bars", kpi: "amount", agg: "sum", group: "value", by: "category", days: 30 },
  { title: "Invoices per day", type: "line", kpi: "amount", agg: "count", group: "day", days: 30 },
  { title: "Success rate", type: "number", source: "runs", metric: "success_rate", days: 7 }];

/** Upload a signed toolkit jar (ooa toolkit sign); its publisher must be trusted first. */
function uploadToolkitDialog() {
  const file = h("input", { type: "file", accept: ".jar", "aria-label": "Toolkit jar" });
  modal("Upload toolkit", [h("label", {}, "Signed toolkit jar (10 MB at most)", file),
    h("p", { class: "muted small", text: "Developers build the toolkit with the OOA SDK and sign it: ooa toolkit sign --jar FILE --class NAME --key FILE. A new version is installed beside the ones already here; agents use the newest version their range allows." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    const f = file.files && file.files[0];
    if (!f) { toast("Choose the jar", true); return; }
    try {
      const res = await fetch("/api/toolkits/custom", { method: "POST", credentials: "same-origin", headers: { "X-OOA-Request": "1", "Content-Type": "application/java-archive" }, body: f });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { const e = data.error || {}; throw new Error((e.message || res.statusText) + (e.suggestedFix ? " (" + e.suggestedFix + ")" : "")); }
      closeModal(); toast(data.unchanged ? data.toolkit_id + " " + data.version + " was already installed" : "Installed " + data.toolkit_id + " " + data.version); go(S.adminTab || "toolkits");
    } catch (e) { toast(e.message, true); } } }, "Upload")]);
}

async function publishersDialog() {
  const r = await api("GET", "toolkits/publishers");
  const name = h("input", { placeholder: "acme" }), key = h("textarea", { rows: 2, class: "mono", placeholder: "The contents of the publisher's .pub file (ooa toolkit keygen)" });
  modal("Trusted publishers", [r.publishers.length ? table(["Publisher", "Key", "Added", ""], r.publishers.map((p) => ({ cells: [p.publisher, h("code", { text: p.public_key.slice(-16) }), ago(p.added) + " by " + (p.added_by || ""),
      h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Stop trusting " + p.publisher + "?")) return; try { await api("DELETE", "toolkits/publishers/" + encodeURIComponent(p.publisher)); closeModal(); publishersDialog(); } catch (e) { toast(e.message, true); } } }, "Remove")] })))
      : h("p", { class: "muted", text: "No publishers yet. Only jars signed by a publisher trusted here can be uploaded." }),
    h("div", { class: "grid2" }, h("label", {}, "Publisher name (as in the key file)", name), h("label", {}, "Public key", key))],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { await api("POST", "toolkits/publishers", { publisher: name.value.trim(), public_key: key.value.trim() }); closeModal(); publishersDialog(); } catch (e) { toast(e.message, true); } } }, "Trust publisher")]);
}

async function customVersionsDialog() {
  const r = await api("GET", "toolkits/custom");
  modal("Your toolkit versions", [r.versions.length ? table(["Toolkit", "Version", "Publisher", "Uploaded", "SHA-256", ""], r.versions.map((v) => ({ cells: [v.name || v.toolkit_id, v.version, v.publisher, ago(v.uploaded) + " by " + (v.uploaded_by || ""),
      h("code", { text: v.sha256.slice(0, 12) }), h("button", { type: "button", class: "danger", onclick: async () => {
        if (!confirm("Delete " + v.toolkit_id + " " + v.version + "?")) return;
        try { await api("DELETE", "toolkits/custom/" + v.id); closeModal(); customVersionsDialog(); } catch (e) { toast(e.message, true); } } }, "Delete")] })))
    : h("p", { class: "muted", text: "No toolkits uploaded yet." })], []);
}

async function dashboardDialog(d) {
  const k = await api("GET", "dashboards/kpis").catch(() => ({ kpis: [] }));
  const name = h("input", { value: d ? d.name : "" }), desc = h("input", { value: d ? d.description || "" : "" });
  const shared = h("input", { value: d ? d.shared_with.join(", ") : "", placeholder: "role:viewer, team:finance or * (empty = only you)" });
  const widgets = h("textarea", { rows: 12, class: "mono", value: JSON.stringify(d ? d.widgets : DASH_EXAMPLE, null, 2) });
  modal(d ? "Edit " + d.name : "New dashboard", [h("div", { class: "grid2" }, h("label", {}, "Name", name), h("label", {}, "Share with", shared)), h("label", {}, "Description", desc),
    h("label", {}, "Widgets (JSON)", widgets),
    h("p", { class: "muted small", text: "A widget: title; type number, bars, line or table; source kpi (with kpi, agg sum/avg/count/min/max/last, optionally bot) or runs (metric runs, success_rate, failed, avg_seconds, hours_saved); group none, day, week, month, agent or value (with by: another KPI of the same run); days 1 to 365." }),
    k.kpis.length ? h("p", { class: "muted small", text: "KPIs recorded so far: " + k.kpis.map((x) => x.name + " (" + botName(x.bot_path) + ")").join(", ") }) : h("p", { class: "muted small", text: "No KPI values yet: mark outputs as KPI in Agent Studio and run the agent." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const body = { name: name.value.trim(), description: desc.value.trim(), shared_with: shared.value.split(",").map((x) => x.trim()).filter(Boolean), widgets: parseJson(widgets.value, []) };
      const r = await api(d ? "PUT" : "POST", "dashboards" + (d ? "/" + d.id : ""), body); S.dashId = r.id; closeModal(); go("dashboards"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

const TEST_EXAMPLE = [
  { name: "urgent mail", inputs: { text: "The server is down!" }, expect: { outputs: { label: { op: "one_of", value: ["urgent", "high"] } } } },
  { name: "thank you", inputs: { text: "Thanks a lot" }, expect: { outputs: { label: "low" } } },
  { name: "empty text fails", inputs: { text: "" }, expect: { status: "failed", error_code: "TRIAGE.*" } }];

function testSetDialog(t) {
  const name = h("input", { value: t ? t.name : "" }), desc = h("input", { value: t ? t.description || "" : "" });
  const bot = botSelect(t ? t.bot_path : null);
  if (t) { const known = [...bot.options].some((o) => o.value === t.bot_path); if (!known) bot.append(h("option", { value: t.bot_path, text: botName(t.bot_path) })); bot.value = t.bot_path; }
  const cases = h("textarea", { rows: 14, class: "mono", value: JSON.stringify(t ? t.cases : TEST_EXAMPLE, null, 2) });
  modal(t ? "Edit " + t.name : "New test set", [h("div", { class: "grid2" }, h("label", {}, "Name", name), h("label", {}, "Agent", bot)), h("label", {}, "Description", desc),
    h("label", {}, "Cases (JSON)", cases),
    h("p", { class: "muted small", text: "Each case: a name, the agent's inputs, and expect: status (success or failed), error_code (EMAIL.* allowed) and outputs. An output is equal to a value, or {op, value}: contains, not_contains, matches (regular expression), approx (with tolerance), one_of (a list), not_empty." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const body = { name: name.value.trim(), description: desc.value.trim(), bot_path: bot.value, cases: parseJson(cases.value, []) };
      await api(t ? "PUT" : "POST", "test-sets" + (t ? "/" + t.id : ""), body); closeModal(); go("tests"); } catch (e) { toast(e.message, true); } } }, "Save")]);
}

function runTestSetDialog(t) {
  const where = h("select", {}, h("optgroup", { label: "Runners" }, S.devices.filter((d) => d.enabled).map((d) => h("option", { value: "d:" + d.id, text: d.name + " (" + d.status + ")" }))),
    h("optgroup", { label: "Runner pools" }, (S.pools || []).map((p) => h("option", { value: "p:" + p.id, text: p.name }))));
  modal("Run " + t.name, [h("p", { class: "muted", text: t.cases.length + " case(s) run the live production version of " + botName(t.bot_path) + ", one run each." }), h("label", {}, "Run on", where)],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      const v = where.value || "";
      try { const r = await api("POST", "test-sets/" + t.id + "/run", v.startsWith("p:") ? { pool_id: v.slice(2) } : { device_id: v.slice(2) });
        closeModal(); toast(r.started + " of " + r.cases + " case(s) started"); testRunDialog(r.test_run_id); } catch (e) { toast(e.message, true); } } }, "Run")]);
}

async function testRunDialog(id) {
  const body = h("div");
  const load = async () => {
    const r = await api("GET", "test-sets/runs/" + id);
    body.replaceChildren(kv([["Agent", botName(r.bot_path) + " v" + r.version], ["Started", local(r.started) + " by " + (r.started_by || "")], ["Score", r.status === "running" ? "running…" : r.passed + " of " + r.total + " passed"]]),
      table(["Case", "Result", "Why", ""], r.results.map((x) => ({ cells: [x.case_name, x.status === "running" ? pill("running") : h("span", { class: "pill " + (x.passed ? "success" : "failed"), text: x.passed ? "passed" : "failed" }),
        h("span", { class: "small", text: x.detail || "" }), x.run_id && !x.run_id.startsWith("norun") ? h("button", { type: "button", class: "link", onclick: () => openRun(x.run_id) }, "run") : ""] }))));
    return r.status === "running";
  };
  modal("Test run", [body], []);
  let more = await load().catch((e) => { toast(e.message, true); return false; });
  while (more && !$("modal").hidden) { await new Promise((res) => setTimeout(res, 2000)); more = await load().catch(() => false); }
}

async function testHistoryDialog(t) {
  try {
    const r = await api("GET", "test-sets/" + t.id + "/history");
    modal("History of " + t.name, [r.runs.length ? table(["Started", "Version", "Score", ""], r.runs.map((x) => ({ cells: [local(x.started), "v" + x.version,
      h("span", { class: "pill " + (x.status === "passed" ? "success" : x.status === "running" ? "running" : "failed"), text: x.status === "running" ? "running" : x.passed + " / " + x.total }),
      h("button", { type: "button", class: "link", onclick: () => testRunDialog(x.id) }, "details")] }))) : h("p", { class: "muted", text: "Not run yet." })], []);
  } catch (e) { toast(e.message, true); }
}

/** The files kept in the current folder of the repository (templates, workbooks, lists), with upload. */
async function repoFilesCard() {
  const r = await api("GET", "repo/files" + (S.repoFolder ? "?folder=" + encodeURIComponent(S.repoFolder) : "")).catch(() => ({ files: [] }));
  const picker = h("input", { type: "file", hidden: true, onchange: async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const path = (S.repoFolder ? S.repoFolder + "/" : "") + f.name;
    try {
      const res = await fetch("/api/repo/files?path=" + encodeURIComponent(path), { method: "PUT", credentials: "same-origin", headers: { "X-OOA-Request": "1", "Content-Type": f.type || "application/octet-stream" }, body: f });
      const d = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error((d.error && d.error.message) || res.statusText);
      toast("Uploaded " + path + " (version " + d.version + ")"); go("automation");
    } catch (err) { toast(err.message, true); }
  } });
  const size = (n) => n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB";
  return h("div", { class: "card" }, h("div", { class: "toolbar" }, h("h2", { text: "Files" + (S.repoFolder ? " in " + S.repoFolder : "") }), h("span", { class: "spacer" }),
      can("repo.checkin") ? h("button", { type: "button", onclick: () => picker.click() }, "Upload file") : null, picker),
    h("p", { class: "muted small", text: "Templates, workbooks and lists agents use. An agent lists the files it needs under requires.files; runners receive them at the same path before the run." }),
    r.files.length ? table(["File", "Size", "Version", "Updated", ""], r.files.map((f) => ({ data: f, cells: [h("code", { text: f.path }), size(Number(f.size)), "v" + f.version, ago(f.updated) + " by " + (f.updated_by || ""),
      h("div", { class: "row-actions tight" }, h("a", { class: "button-link", href: "/api/repo/files/content?path=" + encodeURIComponent(f.path) }, "Download"),
        can("repo.delete") && ["edit", "manage"].includes(f.access) ? h("button", { type: "button", class: "danger", onclick: async () => {
          if (!confirm("Delete " + f.path + "? Agents that need it will fail to start.")) return;
          try { await api("DELETE", "repo/files?path=" + encodeURIComponent(f.path)); go("automation"); } catch (e) { toast(e.message, true); } } }, "Delete") : null)] })))
      : h("p", { class: "muted", text: "No files here." }));
}

function moveBotDialog(b) {
  const sel = h("select", {}, h("option", { value: "", text: "(root)" }), (S.folders || []).map((x) => h("option", { value: x.path, text: x.path })));
  sel.value = b.folder || "";
  modal("Move " + botName(b.path), [h("label", {}, "To folder", sel),
    h("p", { class: "muted", text: "Versions, labels, schedules, triggers and run history move with it. A workflow that names it by path must be changed first." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const r = await api("POST", "repo/move", { path: b.path, folder: sel.value }); closeModal(); closeDrawer(); toast("Moved to " + r.path); go("automation"); } catch (e) { toast(e.message, true); } } }, "Move")]);
}

function copyBotDialog(b) {
  const ext = b.path.slice(b.path.search(/\.(flow|process|form)\.json$/));
  const to = h("input", { value: botName(b.path) + " (copy)" });
  modal("Copy " + botName(b.path), [h("label", {}, "New name (with folder)", to),
    h("p", { class: "muted", text: "The latest version is copied as version 1 of a new agent with its own id. Schedules and history are not copied." })],
  [h("button", { type: "button", class: "primary", onclick: async () => {
    try { const r = await api("POST", "repo/copy", { path: b.path, to: to.value.trim() + ext }); closeModal(); closeDrawer(); toast("Copied to " + botName(r.path || to.value)); go("automation"); } catch (e) { toast(e.message, true); } } }, "Copy")]);
}

async function skillDialog(name) {
  const s = name ? await api("GET", "skills/" + encodeURIComponent(name)).catch((e) => { toast(e.message, true); return null; }) : null;
  if (name && !s) return;
  const d = s ? s.definition : { prompt: "", output: "text", temperature: 0.2, inputs: [] };
  const mine = !s || s.owner === S.user.username || can("skills.all");
  const nm = h("input", { value: s ? s.name : "", placeholder: "summarise_complaint", disabled: !!s });
  const desc = h("input", { value: s ? s.description || "" : "" });
  const conn = h("input", { value: d.connection || "", placeholder: "Model Connection name (blank: the run's default)" });
  const system = h("textarea", { rows: 3, text: d.system || "" });
  const prompt = h("textarea", { rows: 6, text: d.prompt || "", placeholder: "Use ${name} for the values the agent passes in." });
  const output = h("select", {}, h("option", { value: "text", text: "Text" }), h("option", { value: "json", text: "JSON" }));
  output.value = d.output || "text";
  const temp = h("input", { type: "number", step: "0.1", min: "0", max: "2", value: d.temperature === undefined ? "0.2" : String(d.temperature) });
  const inputs = h("textarea", { rows: 3, text: (d.inputs || []).map((i) => i.name + (i.description ? " - " + i.description : "")).join("\n"), placeholder: "one per line: name - description" });
  const shared = h("input", { type: "checkbox", checked: !!(s && s.shared) }), template = h("input", { type: "checkbox", checked: !!(s && s.template) });
  const comment = h("input", { placeholder: "What changed (optional)" });
  const found = () => Array.from(new Set((prompt.value.match(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g) || []).map((x) => x.slice(2, -1))));
  modal(s ? s.name + " (v" + s.version + ")" : "New Prompt Skill", [h("div", { class: "grid2" }, h("label", {}, "Name", nm), h("label", {}, "Connection", conn)),
    h("label", {}, "Description", desc), h("label", {}, "Instructions for the model (system)", system), h("label", {}, "Prompt", prompt),
    h("div", { class: "grid2" }, h("label", {}, "Answer", output), h("label", {}, "Temperature", temp)),
    h("label", {}, "Inputs", inputs),
    h("label", { class: "check" }, shared, "Shared: every agent may use it"), can("skills.all") ? h("label", { class: "check" }, template, "Template: offered as a starting point") : null,
    mine ? h("label", {}, "Comment", comment) : h("p", { class: "muted", text: "Owned by " + s.owner + ": read only." }),
    s && s.versions && s.versions.length > 1 ? [h("h3", { text: "Versions" }), table(["Version", "By", "When", "Comment"], s.versions.map((v) => ({ data: v, cells: ["v" + v.version, v.updated_by || "", ago(v.updated), v.comment || ""] })))] : null],
  [s && mine ? h("button", { type: "button", class: "danger", onclick: async () => { if (!confirm("Delete the skill " + s.name + " and its versions? Agents that run it will fail.")) return;
    try { await api("DELETE", "skills/" + encodeURIComponent(s.name)); closeModal(); go("skills"); } catch (e) { toast(e.message, true); } } }, "Delete") : null,
  mine && can("skills.manage") ? h("button", { type: "button", class: "primary", onclick: async () => {
    const listed = inputs.value.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const i = l.indexOf(" - "); return i > 0 ? { name: l.slice(0, i).trim(), description: l.slice(i + 3).trim() } : { name: l }; });
    for (const n of found()) if (!listed.some((i) => i.name === n)) listed.push({ name: n });
    const def = { prompt: prompt.value, output: output.value, temperature: Number(temp.value || 0), inputs: listed };
    if (system.value.trim()) def.system = system.value;
    if (conn.value.trim()) def.connection = conn.value.trim();
    try { const r = await api("PUT", "skills/" + encodeURIComponent(nm.value.trim()), { description: desc.value, definition: def, shared: shared.checked, template: template.checked, comment: comment.value || null });
      closeModal(); toast("Saved " + r.name + " v" + r.version); go("skills"); } catch (e) { toast(e.message, true); } } }, "Save") : null].filter(Boolean));
}

async function skillTemplatesDialog() {
  const r = await api("GET", "skills/templates");
  modal("Start from a template", [table(["Template", "What it does", ""], r.templates.map((t) => ({ data: t, cells: [h("strong", { text: t.name }), t.description || "",
    h("button", { type: "button", class: "primary", onclick: async () => {
      const n = prompt("Name of your new skill:", t.name.replace(/^template_/, ""));
      if (!n) return;
      try { const s = await api("POST", "skills/from-template", { template: t.name, name: n.trim() }); closeModal(); skillDialog(s.name); } catch (e) { toast(e.message, true); } } }, "Use")] })))], []);
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
      // two steps for accounts with two-step sign-in: the password, then the code
      const r = S.mfaToken ? await api("POST", "auth/mfa", { mfa_token: S.mfaToken, code: $("login-code").value })
        : await api("POST", "auth/login", { username: $("login-user").value, password: $("login-pass").value });
      $("login-pass").value = "";
      if (r.mfa_required) { loginCodeStep(r.mfa_token); return; }
      loginCodeStep(null);
      S.user = r.user;
      afterSignIn();
    } catch (err) {
      if (err.code === "CR.MFA_EXPIRED") loginCodeStep(null);
      $("login-error").textContent = err.message; $("login-error").hidden = false;
    }
  });
  $("password-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("pw-error").hidden = true;
    if ($("pw-new").value !== $("pw-repeat").value) { $("pw-error").textContent = "The new passwords do not match."; $("pw-error").hidden = false; return; }
    try {
      await api("POST", "auth/password", { current: $("pw-current").value, new: $("pw-new").value });
      for (const id of ["pw-current", "pw-new", "pw-repeat"]) $(id).value = "";
      S.user = await api("GET", "auth/me"); toast("Password changed"); afterSignIn();
    } catch (err) { $("pw-error").textContent = err.message; $("pw-error").hidden = false; }
  });
  $("btn-logout").addEventListener("click", async () => { try { await api("POST", "auth/logout"); } catch (_) { /* already signed out */ } S.user = null; showLogin(); });
  $("btn-change-pw").addEventListener("click", showPassword);
  $("btn-mfa").addEventListener("click", () => mfaDialog(false).catch((e) => toast(e.message, true)));
  $("btn-apikeys").addEventListener("click", () => apiKeysDialog().catch((e) => toast(e.message, true)));
  // the user menu in the top bar: name and role, API keys, password, sign out
  const userPop = $("user-pop"), userBtn = $("btn-user");
  const setUserMenu = (open) => { userPop.hidden = !open; userBtn.setAttribute("aria-expanded", String(open)); if (open) userPop.querySelector("button").focus(); };
  userBtn.addEventListener("click", (e) => { e.stopPropagation(); setUserMenu(userPop.hidden); });
  userPop.addEventListener("click", (e) => { if (e.target.closest("button")) setUserMenu(false); });
  document.addEventListener("click", (e) => { if (!userPop.hidden && !e.target.closest(".user-menu")) setUserMenu(false); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !userPop.hidden) { setUserMenu(false); userBtn.focus(); } });
  $("modal").addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });
  $("drawer-close").addEventListener("click", closeDrawer);
  $("drawer").addEventListener("click", (e) => { if (e.target === $("drawer")) closeDrawer(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && $("modal").hidden && !$("drawer").hidden) closeDrawer(); });
  try { S.user = await api("GET", "auth/me"); afterSignIn(); } catch (_) { showLogin(); }
}

if (typeof window !== "undefined" && !window.__OOA_TEST__) boot();

/** Licence use: seats in use per type over time, who holds each seat and whether it was used in the last 30 days. */
function licenseUsageCard(use) {
  const NAMES = { developer: "Developer", citizen_developer: "Citizen Developer", attended_runner: "Attended Runner", unattended_runner: "Unattended Runner" };
  const spark = (type) => {
    const pts = use.trend.filter((r) => r.license_type === type);
    const max = Math.max(1, ...pts.map((r) => Math.max(r.used, r.total))), w = 160, ht = 28;
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 " + w + " " + ht); svg.setAttribute("width", w); svg.setAttribute("height", ht); svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", NAMES[type] + " seats in use over " + pts.length + " day(s)");
    if (pts.length) {
      const line = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
      line.setAttribute("points", pts.map((r, i) => (pts.length === 1 ? w : i * w / (pts.length - 1)).toFixed(1) + "," + (ht - 2 - (ht - 4) * r.used / max).toFixed(1)).join(" "));
      line.setAttribute("fill", "none"); line.setAttribute("stroke", "#2563eb"); line.setAttribute("stroke-width", "2");
      svg.appendChild(line);
    }
    return svg;
  };
  return h("div", { class: "card" },
    h("h2", { text: "Usage" }),
    h("p", { class: "muted", text: "Seats in use each day over the last 90 days, and who holds each seat. A seat whose holder has not signed in (or, for a runner, been online) and has started no runs for 30 days is marked idle: a candidate to take back." }),
    table(["License type", "In use", "Idle", "Last 90 days"], Object.entries(use.seats).map(([t, x]) => ({ cells: [x.label, x.used + (x.total >= 0 ? " of " + x.total : ""), String(x.idle), spark(t)] }))),
    dataTable({ rows: use.holders, search: "Search seat holders", exportName: "license-use",
      columns: [{ label: "Holder", value: (x) => x.name, render: (x) => h("div", {}, h("strong", { text: x.name }), x.name !== x.principal ? h("div", { class: "small muted", text: x.principal }) : null) },
        { label: "License", value: (x) => NAMES[x.license_type] || x.license_type },
        { label: "Since", value: (x) => x.assigned, render: (x) => local(x.assigned) },
        { label: "Last active", value: (x) => x.last_active || "", render: (x) => x.last_active ? local(x.last_active) : h("span", { class: "muted", text: "never" }) },
        { label: "Runs (30 days)", value: (x) => x.runs_30_days },
        { label: "", value: (x) => x.idle ? 1 : 0, render: (x) => x.idle ? h("span", { class: "pill queued", text: "idle" }) : null }] }));
}
