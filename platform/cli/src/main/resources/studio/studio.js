/* ONEOFFICEAUTOMATION Studio (preview): explorer, drag-and-drop canvas, command panels, run console. Plain JavaScript, no external libraries, so it works offline and
   under a strict Content-Security-Policy. All user data is rendered with textContent (never innerHTML). */
"use strict";

// Optional parts are written as `cond ? node : null`; skip null/false (and flatten arrays) instead of printing "null".
for (const m of ["append", "replaceChildren"]) {
  const orig = Element.prototype[m];
  Element.prototype[m] = function (...nodes) { return orig.apply(this, nodes.flat(Infinity).filter((n) => n !== null && n !== undefined && n !== false)); };
}

const SCHEMA = "https://schemas.oneofficeautomation.com/flow/v1.json";
const TYPES = ["String", "Number", "Boolean", "DateTime", "List", "Dictionary", "Table", "Record", "File", "Window", "UIElement", "Credential", "Any"];
const SECTIONS = {
  "core/if": [["children", "Then"], ["else", "Else"]],
  "core/loop": [["children", "Repeat"]],
  "core/step": [["children", "Steps"]],
  "core/parallel": [["children", "Branches (run at the same time)"]],
  "core/try": [["children", "Try"], [["catch", 0, "children"], "Catch (on error)"], ["finally", "Finally (always)"]],
};

// ---------- colours and icons: command categories, item types and variable types ----------
const SVGNS = "http://www.w3.org/2000/svg";
const ICONS = {
  branch: "M12 3l8 9-8 9-8-9z", loop: "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4",
  shield: "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", varx: "M5 4c-2 4-2 12 0 16M19 4c2 4 2 12 0 16M9 9l6 6M15 9l-6 6",
  note: "M5 4h14v16H5zM8 9h8M8 13h8M8 17h5", comment: "M4 5h16v11H9l-5 4z", clock: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 3",
  bot: "M5 9h14v10H5zM12 5v4M9 13v1M15 13v1M9 16.5h6M12 3v1", group: "M3 5h18v14H3zM3 9h18", parallel: "M6 4v16M18 4v16M6 8h12M6 16h12",
  jump: "M5 12h12M13 6l6 6-6 6", stop: "M8 3h8l5 5v8l-5 5H8l-5-5V8zM12 8v5M12 16v.5",
  data: "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  text: "M4 6h16M12 6v13M8 19h8", hash: "M9 3L7 21M17 3l-2 18M4 9h17M3 15h17", toggle: "M8 7h8a5 5 0 0 1 0 10H8A5 5 0 0 1 8 7zM16 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
  calendar: "M4 6h16v14H4zM4 10h16M8 3v4M16 3v4", list: "M9 6h11M9 12h11M9 18h11M4 6v.5M4 12v.5M4 18v.5",
  braces: "M8 3c-2 0-3 1-3 3v3l-2 3 2 3v3c0 2 1 3 3 3M16 3c2 0 3 1 3 3v3l2 3-2 3v3c0 2-1 3-3 3", table: "M3 5h18v14H3zM3 10h18M3 15h18M9 5v14M15 5v14",
  record: "M6 3h12v18H6zM9 8h6M9 12h6M9 16h3", file: "M6 3h8l4 4v14H6zM14 3v4h4", folder: "M3 6h6l2 2h10v11H3z",
  window: "M3 5h18v14H3zM3 9h18M6 7v.5M9 7v.5", cursor: "M5 3l14 7-6 2-2 6z", lock: "M6 11h12v10H6zM8 11V7a4 4 0 0 1 8 0v4",
  star: "M12 3l2.6 5.6 6 .6-4.5 4 1.3 6L12 16l-5.4 3.2 1.3-6-4.5-4 6-.6z", sheet: "M4 3h16v18H4zM4 9h16M4 15h16M10 3v18",
  globe: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18", plug: "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4",
  mail: "M3 6h18v12H3zM3 6l9 7 9-7", cloud: "M7 18h10a4 4 0 0 0 .5-8A6 6 0 0 0 6 9a4.5 4.5 0 0 0 1 9z",
  spark: "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z",
  gear: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1",
  queue: "M4 5h16v4H4zM4 11h16v4H4zM4 17h10v3H4z", code: "M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16", desktop: "M3 4h18v12H3zM8 20h8M12 16v4",
  form: "M5 3h14v18H5zM8 7h8M8 11h3M13 11h3M8 15h8", process: "M4 4h6v6H4zM14 14h6v6h-6zM7 10v5h7", doc: "M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6M9 8h2",
  template: "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM16.5 13v7M13 16.5h7", home: "M3 11l9-8 9 8M5 10v10h14V10", plus: "M12 5v14M5 12h14",
  image: "M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6M15 9.5a1.5 1.5 0 1 0 0-.01", terminal: "M3 5h18v14H3zM7 10l3 2-3 2M12 15h5",
  api: "M4 12h4l2-6 4 12 2-6h4", key: "M15 7a4 4 0 1 1-3.9 5H3v3h3v3h3v-3h2.1A4 4 0 0 1 15 7z", play: "M7 4l13 8-13 8z",
};
function svg(name, cls) {
  const s = document.createElementNS(SVGNS, "svg");
  s.setAttribute("viewBox", "0 0 24 24"); s.setAttribute("aria-hidden", "true");
  if (cls) s.setAttribute("class", cls);
  const p = document.createElementNS(SVGNS, "path");
  p.setAttribute("d", ICONS[name] || ICONS.star);
  s.appendChild(p);
  return s;
}
/** A coloured icon tile, e.g. tile("loop", "#ea580c"). */
function tile(icon, color, cls) {
  const t = h("span", { class: "tile" + (cls ? " " + cls : "") }, svg(icon));
  t.style.setProperty("--c", color);
  return t;
}
const CATS = {
  logic: { name: "Logic and flow", color: "#7c3aed", icon: "branch" }, data: { name: "Data and variables", color: "#2563eb", icon: "data" },
  files: { name: "Files and documents", color: "#059669", icon: "file" }, office: { name: "Excel, Word and PDF", color: "#16a34a", icon: "sheet" },
  web: { name: "Browser and web", color: "#ea580c", icon: "globe" }, desktop: { name: "Desktop and applications", color: "#d97706", icon: "desktop" },
  integration: { name: "APIs and databases", color: "#4f46e5", icon: "plug" }, email: { name: "Email and messaging", color: "#0891b2", icon: "mail" },
  cloud: { name: "Cloud applications", color: "#0284c7", icon: "cloud" }, ai: { name: "AI", color: "#db2777", icon: "spark" },
  scripts: { name: "Scripts", color: "#0f766e", icon: "code" }, workload: { name: "Work queues and insights", color: "#b45309", icon: "queue" },
  system: { name: "System and security", color: "#475569", icon: "gear" }, other: { name: "Other", color: "#64748b", icon: "star" },
};
const PKG_CAT = {
  core: "logic", "ooa.boolean": "data", "ooa.number": "data", "ooa.string": "data", "ooa.datetime": "data", "ooa.list": "data", "ooa.dictionary": "data",
  "ooa.datatable": "data", "ooa.json": "data", "ooa.xml": "data", "ooa.csv": "data", "ooa.global_values": "data", "ooa.run_context": "data",
  "ooa.file": "files", "ooa.folder": "files", "ooa.log": "files", "ooa.excel": "office", "ooa.excel_desktop": "office", "ooa.word": "office", "ooa.pdf": "office",
  "ooa.browser": "web", "ooa.desktop": "desktop", "ooa.window": "desktop", "ooa.image": "desktop", "ooa.ocr": "desktop", "ooa.application": "desktop",
  "ooa.terminal": "desktop", "ooa.sap": "desktop", "ooa.dll": "desktop", "ooa.sound": "desktop", "ooa.printer": "desktop",
  "ooa.rest": "integration", "ooa.connector": "integration", "ooa.soap": "integration", "ooa.database": "integration", "ooa.network": "integration", "ooa.file_transfer": "integration",
  "ooa.email": "email", "ooa.slack": "email", "ooa.microsoft365": "cloud", "ooa.google": "cloud", "ooa.salesforce": "cloud", "ooa.servicenow": "cloud",
  "ooa.jira": "cloud", "ooa.workday": "cloud", "ooa.okta": "cloud", "ooa.box": "cloud", "ooa.docusign": "cloud",
  "ooa.snowflake": "integration", "ooa.databricks": "integration", "ooa.kafka": "integration", "ooa.genai": "ai", "ooa.ai_skills": "ai", "ooa.ai_agent": "ai", "ooa.script": "scripts",
  "ooa.workload": "workload", "ooa.insights": "workload", "ooa.system": "system", "ooa.services": "system", "ooa.crypto": "system", "ooa.pgp": "system",
  "ooa.active_directory": "system", "ooa.fuzzy": "data", "ooa.currency": "data", "ooa.triggerloop": "logic", "ooa.credential_manager": "system",
};
const PKG_ICON = {
  "ooa.string": "text", "ooa.number": "hash", "ooa.boolean": "toggle", "ooa.datetime": "calendar", "ooa.list": "list", "ooa.dictionary": "braces",
  "ooa.datatable": "table", "ooa.csv": "table", "ooa.json": "braces", "ooa.xml": "code", "ooa.folder": "folder", "ooa.log": "note", "ooa.word": "doc",
  "ooa.pdf": "doc", "ooa.window": "window", "ooa.image": "image", "ooa.ocr": "image", "ooa.terminal": "terminal", "ooa.database": "data", "ooa.rest": "api",
  "ooa.soap": "api", "ooa.ai_agent": "bot", "ooa.crypto": "lock", "ooa.pgp": "key", "ooa.active_directory": "key", "ooa.global_values": "globe",
};
const CORE_STYLE = {
  "core/if": { color: "#7c3aed", icon: "branch" }, "core/loop": { color: "#ea580c", icon: "loop" }, "core/try": { color: "#dc2626", icon: "shield" },
  "core/throw": { color: "#dc2626", icon: "stop" }, "core/set": { color: "#0d9488", icon: "varx" }, "core/log": { color: "#475569", icon: "note" },
  "core/comment": { color: "#94a3b8", icon: "comment" }, "core/delay": { color: "#0891b2", icon: "clock" }, "core/wait_until": { color: "#0891b2", icon: "clock" },
  "core/run_bot": { color: "#2563eb", icon: "bot" }, "core/step": { color: "#64748b", icon: "group" }, "core/parallel": { color: "#9333ea", icon: "parallel" },
  "core/break": { color: "#7c3aed", icon: "jump" }, "core/continue": { color: "#7c3aed", icon: "jump" },
};
/** Colour, icon and category of a command address such as "ooa.excel/open". */
function styleFor(addr) {
  const pkg = String(addr || "").split("/")[0];
  const cat = PKG_CAT[pkg] || "other";
  const core = CORE_STYLE[addr];
  return { color: core ? core.color : CATS[cat].color, icon: core ? core.icon : PKG_ICON[pkg] || CATS[cat].icon, cat };
}
const state = {
  packages: [], specs: new Map(), flow: null, file: null,
  tree: null, workspace: "", expanded: new Set([""]), treeSel: null, dragNode: null, dragStep: null,
  groupsOpen: new Set(), triggers: [], triggerLog: [], running: false, runStarted: 0, side: "explorer",
  multi: new Set(), history: [], future: [], lastSnapshot: null, clipboard: null,
  live: null, liveSteps: { running: null, done: new Set(), failed: null }, builtins: null, lastInput: null,
  selected: null,     // path of the selected step, e.g. ["steps", 0, "children", 1]
  target: null,       // path of a list chosen as insertion point (a container section)
  problems: [], dirty: false, lastRun: null, validateTimer: null,
};

// ---------- small DOM helper ----------
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "text") el.textContent = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "value") el.value = v;
    else if (k === "checked") el.checked = !!v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}
const $ = (id) => document.getElementById(id);

function toast(msg, isError) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast" + (isError ? " error" : "");
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, isError ? 6000 : 2500);
}

async function api(method, path, body) {
  const res = await fetch("api/" + path, {
    method, headers: body !== undefined ? { "Content-Type": "application/json" } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const e = data.error || {};
    const err = new Error((e.code ? "[" + e.code + "] " : "") + (e.message || res.statusText) + (e.suggestedFix ? " Fix: " + e.suggestedFix : ""));
    err.code = e.code; err.problems = data.problems;
    throw err;
  }
  return data;
}

// ---------- path helpers ----------
function getAt(obj, path) { return path.reduce((o, k) => (o == null ? undefined : o[k]), obj); }
function sameList(a, b) { return a && b && a.length === b.length && a.every((x, i) => x === b[i]); }
function allSteps(list, out = []) {
  for (const s of list || []) {
    out.push(s);
    for (const k of ["children", "else", "finally"]) allSteps(s[k], out);
    for (const c of s.catch || []) allSteps(c.children, out);
    for (const b of s.elseIf || []) allSteps(b.children, out);
  }
  return out;
}
function newStepId() {
  let n = 0;
  for (const s of allSteps(state.flow.steps)) { const m = /^s(\d+)$/.exec(s.id); if (m) n = Math.max(n, +m[1]); }
  return "s" + (n + 1);
}
function findPathById(id, list = state.flow.steps, base = ["steps"]) {
  for (let i = 0; i < (list || []).length; i++) {
    const s = list[i], p = base.concat(i);
    if (s.id === id) return p;
    for (const [key] of SECTIONS[s.action] || []) {
      const k = Array.isArray(key) ? key : [key];
      const r = findPathById(id, getAt(s, k), p.concat(k));
      if (r) return r;
    }
  }
  return null;
}

// ---------- value parsing for form fields ----------
/** Text from a form field to a Flow value: JSON when it parses, otherwise the text itself
    (so "$var", "${expr}" and plain words stay strings). Empty means "not set". */
function parseLoose(text) {
  if (text.trim() === "") return undefined;
  if (/^\s*[\[{"]|^\s*-?\d|^\s*(true|false|null)\s*$/.test(text)) {
    try { return JSON.parse(text); } catch (_) { /* keep as text */ }
  }
  return text;
}
function showValue(v) {
  if (v === undefined) return "";
  return typeof v === "string" ? v : JSON.stringify(v);
}

// ---------- loading ----------
async function init() {
  wire();
  try {
    const pk = await api("GET", "packages");
    state.packages = pk.packages;
    state.packages.forEach((p) => { p.color = styleFor(p.id + "/x").color; for (const a of p.actions) state.specs.set(p.id + "/" + a.id, { pkg: p, action: a }); });
    renderLibrary();
    await loadTree();
    showEditor(false);
    crRefresh();
    api("GET", "info").then((i) => { state.info = i; $("btn-open-cr").hidden = !i.controlRoom; }).catch(() => {});
  } catch (e) { toast("Cannot reach Agent Studio server: " + e.message, true); }
}

/** Centre view: "home" (Automation list), "bot" (canvas) or "item" (form, process, document, AI Skill, script or text editor). */
function showView(mode) {
  state.view = mode;
  $("welcome").hidden = mode !== "home";
  $("editor-body").hidden = mode !== "bot";
  $("item-editor").hidden = mode !== "item";
  document.body.dataset.view = mode;
  document.body.classList.toggle("script-item", mode === "item" && !!state.item && state.item.type === "script");
  $("btn-validate").hidden = mode !== "bot";
  $("btn-debug").hidden = mode !== "bot";
  $("btn-assistant").hidden = mode !== "bot";
  $("btn-save").hidden = mode === "home";
  $("btn-run").hidden = !(mode === "bot" || (mode === "item" && state.item && state.item.type === "script"));
  if (mode === "home") renderHome();
  renderCrumbs();
}
function showEditor(open) { showView(open ? "bot" : "home"); }

function goHome() {
  if (state.dirty && !confirm("Discard unsaved changes?")) return;
  state.flow = null; state.file = null; state.item = null; state.selected = null; state.multi = new Set(); state.problems = [];
  setDirty(false);
  showView("home");
  renderTree(); renderProps(); renderVariables(); renderProblems();
}

function renderCrumbs() {
  const c = $("crumbs");
  const home = h("a", { class: "crumb-home", href: "#", title: "All automation items", onclick: (e) => { e.preventDefault(); goHome(); } }, "Automation");
  if (!state.file) { c.replaceChildren(home); return; }
  const parts = state.file.split("/");
  const type = state.item ? state.item.type : "bot";
  c.replaceChildren(home, h("span", { text: " / " + parts.slice(0, -1).map((p) => p + " / ").join("") }), h("strong", { text: displayName({ name: parts[parts.length - 1], type }) }));
}

async function openBot(path) {
  if (state.dirty && path !== state.file && !confirm("Discard unsaved changes to the open agent?")) return;
  const data = await api("GET", "bot?path=" + encodeURIComponent(path));
  setFlow(data.flow, path);
  switchSide("commands");
}

function setFlow(flow, file) {
  state.item = null;
  state.flow = flow;
  state.flow.variables = state.flow.variables || [];
  state.flow.steps = state.flow.steps || [];
  state.file = file;
  state.selected = null; state.target = null; state.lastRun = null; state.multi = new Set();
  state.history = []; state.future = []; state.lastSnapshot = JSON.stringify(flow);
  state.liveSteps = { running: null, done: new Set(), failed: null };
  setDirty(false);
  showEditor(true);
  renderAll();
  renderTree();
  updateEditButtons();
  scheduleValidate(0);
}

// ---------- explorer ----------
async function loadTree() {
  const data = await api("GET", "tree");
  state.tree = data.tree;
  state.workspace = data.workspace;
  $("workspace-path").textContent = "Workspace folder: " + data.workspace;
  renderTree();
  if ($("ws-files")) fileOptions();
  if (state.view === "home") renderHome();
}

function nodeByPath(path, node = state.tree) {
  if (!node) return null;
  if (node.path === path) return node;
  for (const c of node.children || []) { const r = nodeByPath(path, c); if (r) return r; }
  return null;
}

function parentOf(path) { const i = path.lastIndexOf("/"); return i < 0 ? "" : path.slice(0, i); }

/** Folder that new items go into: the selected folder, or the folder of the selected file. */
function currentFolder() {
  const sel = state.treeSel && nodeByPath(state.treeSel);
  if (!sel) return "";
  return sel.type === "folder" ? sel.path : parentOf(sel.path);
}

function renderTree() {
  if (!state.tree) return;
  const rows = [];
  const walk = (node, depth) => {
    const isFolder = node.type === "folder";
    const open = state.expanded.has(node.path);
    const row = h("div", {
      class: "node" + (state.treeSel === node.path ? " selected" : "") + (node.path === state.file ? " open-bot" : ""),
      role: "treeitem", tabindex: "0", "data-path": node.path, draggable: node.path !== "" ? "true" : null, title: node.path || state.workspace,
      onclick: () => onNodeClick(node),
      onkeydown: (e) => { if (e.key === "Enter") onNodeClick(node); },
      ondragstart: (e) => { state.dragNode = node.path; e.dataTransfer.setData("text/x-ooa-node", node.path); e.dataTransfer.effectAllowed = "move"; },
      ondragend: () => { state.dragNode = null; },
    },
      h("span", { class: "tw", text: isFolder ? (open ? "\u25BE" : "\u25B8") : "" }),
      itemTile(node),
      h("span", { class: "nm", text: node.path === "" ? "Workspace" : displayName(node) }),
      node.type === "file" ? h("span", { class: "size", text: fmtSize(node.size) }) : null);
    row.style.paddingLeft = 6 + depth * 14 + "px";
    if (isFolder) {
      row.addEventListener("dragover", (e) => {
        if (!state.dragNode || state.dragNode === node.path || (node.path + "/").startsWith(state.dragNode + "/")) return;
        e.preventDefault(); row.classList.add("drop-ok");
      });
      row.addEventListener("dragleave", () => row.classList.remove("drop-ok"));
      row.addEventListener("drop", (e) => { e.preventDefault(); row.classList.remove("drop-ok"); moveNode(e.dataTransfer.getData("text/x-ooa-node") || state.dragNode, node.path); });
    }
    rows.push(row);
    if (isFolder && open) for (const c of node.children || []) walk(c, depth + 1);
  };
  walk(state.tree, 0);
  $("tree").replaceChildren(...rows);
}

function fmtSize(n) { return n == null ? "" : n < 1024 ? n + " B" : n < 1048576 ? Math.round(n / 1024) + " KB" : (n / 1048576).toFixed(1) + " MB"; }

function onNodeClick(node) {
  state.treeSel = node.path;
  if (node.type === "folder") { if (state.expanded.has(node.path) && node.path !== "") state.expanded.delete(node.path); else state.expanded.add(node.path); renderTree(); }
  else if (node.type === "bot") { renderTree(); openBot(node.path).catch((e) => toast(e.message, true)); }
  else if (node.type !== "file" || EDITABLE_FILE.test(node.name)) { renderTree(); openItem(node.path); }
  else { renderTree(); toast(node.name + " (" + fmtSize(node.size) + "). Agents can read this file using its path: " + node.path); }
}

function expandTo(path) { let p = parentOf(path); while (true) { state.expanded.add(p); if (!p) break; p = parentOf(p); } }

async function fileOp(op, body, okMsg) {
  try {
    const res = await api("POST", "files/" + op, body);
    await loadTree();
    if (okMsg) toast(okMsg);
    return res.path;
  } catch (e) { toast(e.message, true); return null; }
}

async function newBotHere(kind) {
  const name = prompt(kind === "ApiTask" ? "Name of the new headless API agent:" : "Name of the new agent:", kind === "ApiTask" ? "New headless API agent" : "New agent");
  if (!name) return;
  if (state.dirty && !confirm("Discard unsaved changes to the open agent?")) return;
  const folder = currentFolder();
  const path = await fileOp("bot", { parent: folder, name, kind: kind === "ApiTask" ? "ApiTask" : "TaskBot" }, kind === "ApiTask" ? "Headless API agent created" : "Agent created");
  if (path) { setDirty(false); expandTo(path); state.treeSel = path; await openBot(path); }
}

async function newFolderHere() {
  const name = prompt("Name of the new folder:", "New folder");
  if (!name) return;
  const folder = currentFolder();
  const path = await fileOp("folder", { parent: folder, name }, "Folder created");
  if (path) { expandTo(path); state.expanded.add(path); state.treeSel = path; renderTree(); }
}

async function renameSelected() {
  const node = state.treeSel && nodeByPath(state.treeSel);
  if (!node || node.path === "") { toast("Select a file or folder to rename.", true); return; }
  const current = node.type === "folder" || node.type === "file" ? node.name : displayName(node);
  const name = prompt("New name:", current);
  if (!name || name === current) return;
  const path = await fileOp("rename", { path: node.path, name }, "Renamed");
  if (path) afterPathChange(node.path, path);
}

async function deleteSelectedNode() {
  const node = state.treeSel && nodeByPath(state.treeSel);
  if (!node || node.path === "") { toast("Select a file or folder to delete.", true); return; }
  if (!confirm("Move '" + node.name + "'" + (node.type === "folder" ? " and everything in it" : "") + " to the workspace trash?")) return;
  const path = await fileOp("delete", { path: node.path }, "Moved to trash (workspace .trash folder)");
  if (path !== null) {
    if (state.file && (state.file === node.path || state.file.startsWith(node.path + "/"))) { state.file = null; state.flow = null; state.item = null; setDirty(false); showEditor(false); renderProps(); }
    state.treeSel = null; renderTree();
  }
}

async function moveNode(from, toFolder) {
  if (!from || parentOf(from) === toFolder) return;
  const path = await fileOp("move", { path: from, to: toFolder }, "Moved");
  if (path) { state.expanded.add(toFolder); afterPathChange(from, path); }
}

function afterPathChange(oldPath, newPath) {
  const remap = (p) => (p === oldPath ? newPath : p.startsWith(oldPath + "/") ? newPath + p.slice(oldPath.length) : p);
  if (state.file) state.file = remap(state.file);
  if (state.item) state.item.path = remap(state.item.path);
  state.expanded = new Set([...state.expanded].map(remap));  // renamed or moved folders stay open
  expandTo(newPath);
  if (state.file) expandTo(state.file);                       // keep the open bot visible
  state.treeSel = newPath;
  renderTree(); renderCrumbs();
  if (state.flow) renderSettings();
}

function setDirty(d) { state.dirty = d; $("dirty").hidden = !d; }

function changed(rerenderSteps = true) {
  const now = JSON.stringify(state.flow);
  if (state.lastSnapshot !== null && now !== state.lastSnapshot) {
    state.history.push(state.lastSnapshot);
    if (state.history.length > 100) state.history.shift();
    state.future = [];
  }
  state.lastSnapshot = now;
  setDirty(true);
  if (rerenderSteps) renderSteps();
  renderJson();
  updateEditButtons();
  scheduleValidate();
}

function restore(snapshot) {
  const file = state.file;
  const sel = state.multi;
  state.flow = JSON.parse(snapshot);
  state.lastSnapshot = snapshot;
  state.file = file;
  state.multi = new Set([...sel].filter((id) => allSteps(state.flow.steps).some((x) => x.id === id)));
  state.selected = state.multi.size ? findPathById([...state.multi][0]) : null;
  state.target = null;
  setDirty(true);
  renderAll(); updateEditButtons(); scheduleValidate();
}

function undo() {
  if (!state.flow || !state.history.length) return;
  state.future.push(JSON.stringify(state.flow));
  restore(state.history.pop());
  toast("Undone");
}

function redo() {
  if (!state.flow || !state.future.length) return;
  state.history.push(JSON.stringify(state.flow));
  restore(state.future.pop());
  toast("Redone");
}

function updateEditButtons() {
  const has = !!state.flow, sel = state.multi.size > 0;
  const set = (id, on) => { const b = $(id); if (b) b.disabled = !on; };
  set("btn-undo", has && state.history.length > 0);
  set("btn-redo", has && state.future.length > 0);
  set("btn-cut", has && sel); set("btn-copy", has && sel); set("btn-del", has && sel);
  set("btn-paste", has && !!state.clipboard);
  set("btn-share-copy", has && sel);
}

// ---------- library ----------
const RECENT_KEY = "ooa.studio.recent";
function recentCommands() { try { return JSON.parse(localStorage.getItem(RECENT_KEY)) || []; } catch (_) { return []; } }
function noteRecent(addr) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify([addr, ...recentCommands().filter((x) => x !== addr)].slice(0, 8))); } catch (_) { /* storage unavailable */ }
}

function renderLibrary() {
  const q = $("cmd-search").value.trim().toLowerCase();
  const groups = new Map(Object.keys(CATS).map((c) => [c, []]));
  for (const p of state.packages) for (const a of p.actions) {
    if (q && !(a.name + " " + a.id + " " + a.description + " " + p.name + " " + (a.category || "")).toLowerCase().includes(q)) continue;
    groups.get(styleFor(p.id + "/" + a.id).cat).push({ p, a, addr: p.id + "/" + a.id });
  }
  const out = [];
  let count = 0;
  const cmdRow = ({ p, a, addr }) => {
    const st = styleFor(addr);
    return h("div", {
      class: "cmd", title: a.description + "\nClick to add, or drag onto the canvas.", tabindex: "0", draggable: "true", "data-action": addr,
      onclick: () => addStep(addr),
      onkeydown: (e) => { if (e.key === "Enter") addStep(addr); },
      ondragstart: (e) => { e.dataTransfer.setData("text/x-ooa-command", addr); e.dataTransfer.effectAllowed = "copy"; document.body.classList.add("dnd"); },
      ondragend: () => document.body.classList.remove("dnd"),
    }, tile(st.icon, st.color, "sm"), h("span", { class: "cmd-name", text: a.name }), h("span", { class: "cmd-pkg", text: p.name }));
  };
  const group = (id, meta, items) => {
    const open = q !== "" || state.groupsOpen.has(id);
    out.push(h("button", { type: "button", class: "pkg-head", "aria-expanded": String(open),
      onclick: () => { if (state.groupsOpen.has(id)) state.groupsOpen.delete(id); else state.groupsOpen.add(id); renderLibrary(); } },
      h("span", { class: "chev", text: open ? "\u25BE" : "\u25B8" }), tile(meta.icon, meta.color, "sm"), h("span", { class: "gname", text: meta.name }), h("span", { class: "n", text: items.length })));
    if (open) out.push(h("div", { class: "cmd-group" }, items.map(cmdRow)));
  };
  const recent = q ? [] : recentCommands().filter((x) => state.specs.has(x)).map((addr) => ({ p: state.specs.get(addr).pkg, a: state.specs.get(addr).action, addr }));
  if (recent.length) group("recent", { name: "Recently used", color: "#64748b", icon: "clock" }, recent);
  for (const [cat, items] of groups) {
    if (!items.length) continue;
    count += items.length;
    group(cat, CATS[cat], items);
  }
  $("library").replaceChildren(...out);
  $("cmd-count").textContent = "(" + count + ")";
  $("btn-fold-groups").textContent = state.groupsOpen.size ? "Collapse all" : "Expand all";
}

// ---------- steps ----------
function insertionPoint() {
  if (state.target) { const list = getAt(state.flow, state.target); return { list, index: list.length, path: state.target }; }
  if (state.selected) {
    const listPath = state.selected.slice(0, -1);
    return { list: getAt(state.flow, listPath), index: state.selected[state.selected.length - 1] + 1, path: listPath };
  }
  return { list: state.flow.steps, index: state.flow.steps.length, path: ["steps"] };
}

function addStep(action, at) {
  if (!state.flow) { toast("Open or create an agent first (Explorer tab).", true); return; }
  const spec = state.specs.get(action);
  const step = { id: newStepId(), action };
  const args = {};
  for (const [name, prop] of Object.entries(spec.action.inputs.properties)) {
    if (action === "core/loop" && name === "iterator") args.iterator = "list";
    else if ((spec.action.inputs.required || []).includes(name) && prop.examples) args[name] = prop.examples[0];
  }
  if (Object.keys(args).length) step.args = args;
  if (action === "core/if") { step.children = []; step.else = []; }
  else if (action === "core/try") { step.children = []; step.catch = [{ codes: [], children: [] }]; step.finally = []; }
  else if (SECTIONS[action]) step.children = [];
  at = at || insertionPoint();
  at.list.splice(at.index, 0, step);
  state.selected = at.path.concat(at.index);
  state.multi = new Set([step.id]);
  state.target = SECTIONS[action] ? state.selected.concat("children") : null;
  noteRecent(action);
  changed();
  renderProps();
  toast("Added " + spec.action.name);
}

function summary(step) {
  const a = step.args || {};
  if (step.action === "core/set") return (a.variable || "?") + " = " + showValue(a.value);
  if (step.action === "core/if") return "if " + showValue(a.condition);
  if (step.action === "core/loop") return a.iterator + (a.over ? " over " + showValue(a.over) : a.count ? " x " + showValue(a.count) : "") + (a.as ? " as " + a.as : "");
  const parts = Object.entries(a).slice(0, 3).map(([k, v]) => k + ": " + showValue(v));
  const outs = Object.entries(step.out || {}).map(([k, v]) => "-> " + v);
  return parts.concat(outs).join("   ");
}

function makeSlot(listPath, index) {
  const slot = h("div", { class: "slot", "data-list": JSON.stringify(listPath), "data-index": index });
  slot.addEventListener("dragover", (e) => { e.preventDefault(); slot.classList.add("over"); });
  slot.addEventListener("dragleave", () => slot.classList.remove("over"));
  slot.addEventListener("drop", (e) => { e.preventDefault(); slot.classList.remove("over"); dropAt(e, listPath, index); });
  return slot;
}

/** Handles a drop of a library command (insert) or a canvas step (move) at listPath[index]. */
function dropAt(e, listPath, index) {
  document.body.classList.remove("dnd");
  const command = e.dataTransfer.getData("text/x-ooa-command");
  const stepData = e.dataTransfer.getData("text/x-ooa-step") || (state.dragStep ? JSON.stringify(state.dragStep) : "");
  const list = getAt(state.flow, listPath);
  if (!list) return;
  if (command) { addStep(command, { list, index, path: listPath }); return; }
  if (!stepData) return;
  const from = JSON.parse(stepData);
  const prefix = from.length <= listPath.length && from.every((k, i) => listPath[i] === k);
  if (prefix) { toast("A block cannot be moved inside itself.", true); return; }
  const srcList = getAt(state.flow, from.slice(0, -1));
  const fi = from[from.length - 1];
  const step = srcList[fi];
  srcList.splice(fi, 1);
  let ti = index;
  if (srcList === list && fi < ti) ti--;
  list.splice(ti, 0, step);
  state.dragStep = null;
  state.selected = findPathById(step.id);
  state.target = null;
  changed(); renderProps();
}

function collapsedSet() { return new Set(((state.flow && state.flow.ui) || {}).collapsed || []); }
function setCollapsed(set) {
  state.flow.ui = Object.assign({}, state.flow.ui || {}, { collapsed: [...set] });
  if (!state.flow.ui.collapsed.length) delete state.flow.ui.collapsed;
  if (!Object.keys(state.flow.ui).length) delete state.flow.ui;
  setDirty(true); renderSteps(); renderJson();
}
function toggleFold(id) { const c = collapsedSet(); if (c.has(id)) c.delete(id); else c.add(id); setCollapsed(c); }
function foldAll(collapse) {
  const ids = allSteps(state.flow.steps).filter((st) => SECTIONS[st.action]).map((st) => st.id);
  setCollapsed(collapse ? new Set(ids) : new Set());
}

// ---------- multi-select, clipboard, context menu ----------
function siblingsRange(p1, p2) {
  const a = p1.slice(0, -1), b = p2.slice(0, -1);
  if (!sameList(a, b)) return null;
  const list = getAt(state.flow, a);
  const i = p1[p1.length - 1], j = p2[p2.length - 1];
  return list.slice(Math.min(i, j), Math.max(i, j) + 1).map((x) => x.id);
}

function clickStep(e, p, step) {
  if (e.ctrlKey || e.metaKey) {
    if (state.multi.has(step.id)) state.multi.delete(step.id); else state.multi.add(step.id);
    state.selected = state.multi.size ? (state.multi.has(step.id) ? p : findPathById([...state.multi][0])) : null;
  } else if (e.shiftKey && state.selected) {
    const ids = siblingsRange(state.selected, p);
    if (ids) state.multi = new Set(ids); else state.multi = new Set([step.id]);
  } else {
    state.multi = new Set([step.id]);
    state.selected = p;
  }
  state.target = null;
  renderSteps(); renderProps(); updateEditButtons();
}

/** Selected steps in document order, leaving out steps whose block is also selected. */
function selectedSteps() {
  const order = allSteps(state.flow.steps);
  const chosen = order.filter((x) => state.multi.has(x.id));
  return chosen.filter((x) => !chosen.some((y) => y !== x && allSteps([y]).slice(1).includes(x)));
}

function referencedVariables(steps) {
  const text = JSON.stringify(steps);
  const names = new Set();
  for (const m of text.matchAll(/\$([A-Za-z_][A-Za-z0-9_]*)/g)) names.add(m[1]);
  for (const st of allSteps(steps)) for (const v of Object.values(st.out || {})) names.add(v);
  return state.flow.variables.filter((v) => names.has(v.name));
}

function copySelection(silent) {
  const steps = selectedSteps();
  if (!steps.length) return false;
  state.clipboard = { steps: JSON.parse(JSON.stringify(steps)), variables: JSON.parse(JSON.stringify(referencedVariables(steps))) };
  try { if (navigator.clipboard) navigator.clipboard.writeText(JSON.stringify({ ooaSteps: state.clipboard })); } catch (_) { /* clipboard permission not granted */ }
  updateEditButtons();
  if (!silent) toast("Copied " + steps.length + (steps.length === 1 ? " step" : " steps"));
  return true;
}

function deleteSelection(silent) {
  const steps = selectedSteps();
  if (!steps.length) return;
  for (const st of steps.slice().reverse()) {
    const p = findPathById(st.id);
    if (p) getAt(state.flow, p.slice(0, -1)).splice(p[p.length - 1], 1);
  }
  state.multi = new Set(); state.selected = null; state.target = null;
  changed(); renderProps();
  if (!silent) toast("Deleted " + steps.length + (steps.length === 1 ? " step" : " steps"));
}

function cutSelection() { if (copySelection(true)) { deleteSelection(true); toast("Cut to the clipboard"); } }

function freshIds(steps) {
  const used = new Set(allSteps(state.flow.steps).map((x) => x.id));
  let n = 0;
  for (const id of used) { const m = /^s(\d+)$/.exec(id); if (m) n = Math.max(n, +m[1]); }
  for (const st of allSteps(steps)) {
    let id = st.id;
    if (!id || used.has(id)) { do { n += 1; id = "s" + n; } while (used.has(id)); }
    st.id = id; used.add(id);
  }
  return steps;
}

function pasteSteps(clip, label) {
  if (!state.flow) { toast("Open an agent first.", true); return; }
  if (!clip || !clip.steps || !clip.steps.length) { toast("The clipboard is empty.", true); return; }
  const steps = freshIds(JSON.parse(JSON.stringify(clip.steps)));
  let added = 0;
  for (const v of clip.variables || []) if (!state.flow.variables.some((x) => x.name === v.name)) { state.flow.variables.push(JSON.parse(JSON.stringify(v))); added++; }
  const at = insertionPoint();
  at.list.splice(at.index, 0, ...steps);
  state.multi = new Set(steps.map((x) => x.id));
  state.selected = at.path.concat(at.index + steps.length - 1);
  state.target = null;
  changed(); renderProps(); renderVariables();
  toast("Pasted " + steps.length + (steps.length === 1 ? " step" : " steps") + (label ? " " + label : "") + (added ? " and " + added + " variable(s)" : ""));
}

async function copyToShared() {
  const steps = selectedSteps();
  if (!steps.length) { toast("Select steps first.", true); return; }
  try {
    await api("PUT", "clipboard", { steps, variables: referencedVariables(steps) });
    toast("Copied " + steps.length + " step(s) to the shared clipboard; paste them into any agent.");
  } catch (e) { toast(e.message, true); }
}

async function pasteFromShared() {
  try { pasteSteps(await api("GET", "clipboard"), "from the shared clipboard"); } catch (e) { toast(e.message, true); }
}

function toggleDisabled() {
  const steps = selectedSteps();
  if (!steps.length) return;
  const disable = steps.some((x) => !x.disabled);
  for (const st of steps) { if (disable) st.disabled = true; else delete st.disabled; }
  changed(); renderProps();
}

function selectAllSteps() {
  if (!state.flow) return;
  state.multi = new Set(state.flow.steps.map((x) => x.id));
  state.selected = state.flow.steps.length ? ["steps", 0] : null;
  renderSteps(); renderProps(); updateEditButtons();
}

function showContextMenu(e, p, step) {
  e.preventDefault();
  if (!state.multi.has(step.id)) { state.multi = new Set([step.id]); state.selected = p; renderSteps(); renderProps(); }
  closeContextMenu();
  const item = (label, keys, fn, enabled = true) => h("button", { type: "button", role: "menuitem", disabled: !enabled,
    onclick: () => { closeContextMenu(); fn(); } }, h("span", { text: label }), h("span", { class: "keys", text: keys }));
  const n = selectedSteps().length;
  const menu = h("div", { class: "ctx-menu", role: "menu", id: "ctx-menu" },
    item("Cut", "Ctrl+X", cutSelection), item("Copy", "Ctrl+C", () => copySelection()), item("Paste after", "Ctrl+V", () => pasteSteps(state.clipboard), !!state.clipboard),
    h("hr"),
    item("Copy to shared clipboard", "", copyToShared), item("Paste from shared clipboard", "", pasteFromShared),
    h("hr"),
    item(n > 1 ? "Duplicate" : "Duplicate", "Ctrl+D", () => { copySelection(true); pasteSteps(state.clipboard); }),
    item(selectedSteps().some((x) => !x.disabled) ? "Disable" : "Enable", "", toggleDisabled),
    item(breakpointSet().has(step.id) ? "Clear breakpoint" : "Set breakpoint", "F9", () => toggleBreakpoint(step.id)),
    item("Delete" + (n > 1 ? " " + n + " steps" : ""), "Del", () => deleteSelection()));
  menu.style.left = Math.min(e.clientX, window.innerWidth - 240) + "px";
  menu.style.top = Math.min(e.clientY, window.innerHeight - 320) + "px";
  document.body.appendChild(menu);
  menu.querySelector("button").focus();
}

function closeContextMenu() { const m = $("ctx-menu"); if (m) m.remove(); }

function renderSteps() {
  if (!state.flow) return;
  if (state.canvasMode === "flow") { renderFlowView(); return; }
  const container = $("steps");
  const problemsBy = new Map();
  for (const p of state.problems) if (p.stepId) problemsBy.set(p.stepId, (problemsBy.get(p.stepId) || 0) + 1);
  const collapsed = collapsedSet();

  function list(steps, path, prefix) {
    const out = [makeSlot(path, 0)];
    steps.forEach((s, i) => {
      const p = path.concat(i);
      const spec = state.specs.get(s.action);
      const st = styleFor(s.action);
      const num = prefix + (i + 1);
      const isSel = state.multi.has(s.id) || (state.multi.size === 0 && sameList(p, state.selected));
      const live = state.liveSteps;
      const liveClass = live.paused === s.id ? " paused" : live.running === s.id ? " running" : live.failed === s.id ? " failed-run" : live.done.has(s.id) ? " done" : "";
      const isContainer = !!SECTIONS[s.action];
      const folded = isContainer && collapsed.has(s.id);
      const hiddenCount = folded ? allSteps([s]).length - 1 : 0;
      const row = h("div", {
        class: "step" + (isContainer ? " container" : "") + (isSel ? " selected" : "") + (s.disabled ? " disabled" : "") + liveClass, tabindex: "0", draggable: "true", "data-step-id": s.id,
        "aria-selected": String(isSel),
        onclick: (e) => { e.stopPropagation(); clickStep(e, p, s); },
        oncontextmenu: (e) => { e.stopPropagation(); showContextMenu(e, p, s); },
        onkeydown: (e) => {
          if (e.key === "Enter") select(p);
          if (isContainer && (e.key === "ArrowLeft" || e.key === "ArrowRight")) { e.preventDefault(); if ((e.key === "ArrowLeft") !== folded) toggleFold(s.id); }
        },
        ondragstart: (e) => {
          e.stopPropagation(); state.dragStep = p;
          e.dataTransfer.setData("text/x-ooa-step", JSON.stringify(p)); e.dataTransfer.effectAllowed = "move";
          row.classList.add("dragging-source"); document.body.classList.add("dnd");
        },
        ondragend: () => { row.classList.remove("dragging-source"); document.body.classList.remove("dnd"); state.dragStep = null; },
      },
        h("span", { class: "grip", title: "Drag to move", text: "\u22EE\u22EE" }),
        h("span", { class: "num" + (breakpointSet().has(s.id) ? " bp" : ""), text: num, title: "Click to set or clear a breakpoint (F9)",
          onclick: (e) => { e.stopPropagation(); toggleBreakpoint(s.id); } }),
        isContainer ? h("button", { type: "button", class: "fold", "aria-expanded": String(!folded), "aria-label": folded ? "Expand block" : "Collapse block",
          onclick: (e) => { e.stopPropagation(); toggleFold(s.id); } }, folded ? "\u25B8" : "\u25BE") : null,
        tile(st.icon, st.color, "ico"),
        h("span", { class: "title", text: spec ? spec.action.name : s.action }),
        h("span", { class: "pkg", text: spec ? spec.pkg.name : s.action.split("/")[0] }),
        h("span", { class: "summary", text: s.description ? s.description : summary(s) }),
        folded && hiddenCount ? h("span", { class: "hidden-pill", text: hiddenCount + (hiddenCount === 1 ? " step hidden" : " steps hidden") }) : null,
        s.disabled ? h("span", { class: "off-pill", text: "Disabled" }) : null,
        problemsBy.get(s.id) ? h("span", { class: "flag", title: "Has problems; see the Problems tab", text: "!" + problemsBy.get(s.id) }) : null);
      row.style.setProperty("--c", st.color);
      out.push(row);
      let sections = SECTIONS[s.action] || [];
      if (s.action === "core/if") sections = [["children", "Then"], ...(s.elseIf || []).map((_, k) => [["elseIf", k, "children"], "Else if #" + (k + 1)]), ["else", "Else"]];
      if (!folded && sections.length) {
        const blk = h("div", { class: "block" });
        blk.style.setProperty("--c", st.color);
        for (const [key, label] of sections) {
          const k = Array.isArray(key) ? key : [key];
          if (getAt(s, k) === undefined) continue;
          const sp = p.concat(k);
          const kids = getAt(s, k);
          const sec = h("div", {
            class: "section" + (sameList(sp, state.target) ? " target" : ""), tabindex: "0",
            title: "Click to add new actions here, or drop an action on it",
            onclick: (e) => { e.stopPropagation(); state.target = sp; state.selected = p; renderSteps(); renderProps(); },
          }, label);
          sec.addEventListener("dragover", (e) => { e.preventDefault(); sec.classList.add("target"); });
          sec.addEventListener("dragleave", () => sec.classList.toggle("target", sameList(sp, state.target)));
          sec.addEventListener("drop", (e) => { e.preventDefault(); e.stopPropagation(); dropAt(e, sp, kids.length); });
          blk.append(sec);
          const body = h("div", { class: "block-list" });
          if (kids.length) body.append(...list(kids, sp, num + "."));
          else body.append(makeSlot(sp, 0), h("div", { class: "empty", text: "Empty: drop an action here" }));
          blk.append(body);
        }
        out.push(blk);
      }
      out.push(makeSlot(path, i + 1));
    });
    return out;
  }
  if (!state.flow.steps.length) {
    const zone = h("div", { class: "canvas-empty", text: "This agent has no steps yet. Drag an action here from Actions, click an action, or press Record." });
    zone.addEventListener("dragover", (e) => { e.preventDefault(); });
    zone.addEventListener("drop", (e) => { e.preventDefault(); dropAt(e, ["steps"], 0); });
    container.replaceChildren(zone);
  } else container.replaceChildren(...list(state.flow.steps, ["steps"], ""));
  const total = allSteps(state.flow.steps).length;
  $("canvas-stats").textContent = total + (total === 1 ? " step" : " steps") + " \u00B7 " + state.problems.length + (state.problems.length === 1 ? " problem" : " problems");
  $("insert-hint").textContent = state.target ? "Clicked actions go into the highlighted section."
    : state.selected ? "Clicked actions go after the selected step." : "Clicked actions go at the end.";
}

function select(path) {
  const st = getAt(state.flow, path);
  state.multi = new Set(st ? [st.id] : []);
  state.selected = path;
  state.target = null;
  renderSteps();
  renderProps();
}

function selectById(id) {
  const p = findPathById(id);
  if (!p) return;
  switchTab("steps");
  select(p);
  const row = document.querySelector('[data-step-id="' + CSS.escape(id) + '"]');
  if (row) row.scrollIntoView({ block: "center" });
}

function moveSelected(delta) {
  const listPath = state.selected.slice(0, -1), i = state.selected[state.selected.length - 1];
  const list = getAt(state.flow, listPath), j = i + delta;
  if (j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  state.selected = listPath.concat(j);
  changed(); renderProps();
}

function deleteSelected() {
  if (!state.selected) return;
  const listPath = state.selected.slice(0, -1), i = state.selected[state.selected.length - 1];
  const step = getAt(state.flow, state.selected);
  if (SECTIONS[step.action] && allSteps([step]).length > 1 && !confirm("Delete this step and everything inside it?")) return;
  getAt(state.flow, listPath).splice(i, 1);
  state.selected = null; state.target = null;
  changed(); renderProps();
}

function duplicateSelected() {
  const listPath = state.selected.slice(0, -1), i = state.selected[state.selected.length - 1];
  const copy = JSON.parse(JSON.stringify(getAt(state.flow, state.selected)));
  for (const s of allSteps([copy])) s.id = newStepId() + "_" + Math.random().toString(36).slice(2, 5);
  getAt(state.flow, listPath).splice(i + 1, 0, copy);
  state.selected = listPath.concat(i + 1);
  changed(); renderProps();
}

// ---------- properties panel ----------
function varNames() { return state.flow.variables.map((v) => v.name); }

function addVariable(name, type) {
  let n = name, k = 2;
  while (varNames().includes(n)) n = name + "_" + k++;
  state.flow.variables.push({ name: n, type: type || "Any" });
  return n;
}

function inputControl(step, name, prop, required) {
  const args = step.args || (step.args = {});
  const type = prop["x-ooa-type"] || "Any";
  const current = args[name];
  const set = (v) => {
    if (v === undefined) delete args[name]; else args[name] = v;
    if (!Object.keys(args).length) delete step.args;
    changed();
  };
  let control;
  if ((name === "condition" || name === "while") && step.action.startsWith("core/")) {
    return h("div", { class: "field" }, h("label", {}, name === "while" ? "Repeat while" : "Condition", required ? h("span", { class: "req", text: " *" }) : null),
      conditionEditor(current, (v) => set(v)), h("div", { class: "help", text: prop.description || "" }));
  }
  if (name === "object" && /^ooa\.(browser|desktop_ui)\//.test(step.action)) {
    return h("div", { class: "field" }, h("label", {}, "Captured object", required ? h("span", { class: "req", text: " *" }) : null, typeChip(type)),
      captureEditor(step, current, set), h("div", { class: "help", text: "Tick the properties to search by and choose how each one must match. Values can use $variables. Test opens the page and outlines what matches." }));
  }
  if (step.action === "core/loop" && name === "iterator") {
    const iters = [];
    for (const p of state.packages) for (const it of p.iterators || []) iters.push([p.id + "/" + it.id, it.name + " (" + p.name + ")"]);
    control = h("select", { onchange: (e) => set(e.target.value) },
      h("optgroup", { label: "Built in" }, [["times", "N times"], ["while", "While a condition is true"], ["list", "Each item in a list"], ["dictionary", "Each key in a dictionary"], ["table_rows", "Each row in a table"]].map(([v, t]) => h("option", { value: v, text: t }))),
      iters.length ? h("optgroup", { label: "From toolkits" }, iters.map(([v, t]) => h("option", { value: v, text: t }))) : null);
    control.value = current || "list";
  } else if (prop.enum) {
    control = h("select", { onchange: (e) => set(e.target.value === "" ? undefined : e.target.value) },
      h("option", { value: "", text: prop.default !== undefined ? "(default: " + prop.default + ")" : "(choose)" }),
      prop.enum.map((v) => h("option", { value: v, text: v })));
    control.value = current === undefined ? "" : String(current);
  } else if (type === "Boolean") {
    control = h("select", { onchange: (e) => set(e.target.value === "" ? undefined : e.target.value === "true" ? true : e.target.value === "false" ? false : e.target.value) },
      h("option", { value: "", text: prop.default !== undefined ? "(default: " + prop.default + ")" : "(not set)" }),
      h("option", { value: "true", text: "true" }), h("option", { value: "false", text: "false" }));
    control.value = current === true ? "true" : current === false ? "false" : "";
  } else if (["List", "Dictionary", "Table", "Record", "Any"].includes(type)) {
    control = h("textarea", { rows: name === "condition" || name === "value" ? 2 : 3, value: showValue(current),
      placeholder: name === "condition" ? "$amount > 1000" : type === "Any" ? "value, $variable or JSON" : "$variable or JSON",
      onchange: (e) => set(parseLoose(e.target.value)) });
  } else {
    control = h("input", { type: "text", value: showValue(current),
      placeholder: prop.default !== undefined ? "default: " + prop.default : type === "Credential" ? "$credential_variable" : type === "Number" ? "number or $variable" : "",
      onchange: (e) => set(type === "Number" ? parseLoose(e.target.value) : (e.target.value === "" ? undefined : e.target.value)) });
  }
  if (control.tagName === "INPUT" || control.tagName === "TEXTAREA") { control.dataset.argName = name; control = withPicker(control, type); }
  if (name === "selector" && step.action.startsWith("ooa.browser/")) {
    // Pick records the full captured object (all properties, editable match rules) when the action accepts one; otherwise just the selector.
    const takesObject = !!(state.specs.get(step.action) && state.specs.get(step.action).action.inputs.properties.object);
    const pick = h("button", { type: "button", title: takesObject ? "Open the page and click the element: all its properties are captured, and you choose which to match"
        : "Open the page and click the element to capture its selector",
      onclick: () => pickSelector((sel, el, obj) => {
        if (takesObject && obj) { args.object = obj; delete args[name]; changed(); }
        else set(sel);
        renderProps();
      }) }, takesObject ? "Capture" : "Pick");
    control = h("div", { class: "field-row" }, control, pick);
  }
  const help = [prop.description, prop.examples ? "Example: " + prop.examples[0] : null].filter(Boolean).join("  ");
  return h("div", { class: "field" },
    h("label", {}, (prop["x-ooa-label"] || name), required ? h("span", { class: "req", text: " *" }) : null, typeChip(type)),
    control, h("div", { class: "help", text: help }));
}

function acc(title, nodes, open) {
  const d = h("details", { class: "acc", "data-acc": title }, h("summary", { text: title }), h("div", { class: "acc-body" }, nodes));
  const remembered = state.accOpen && state.accOpen[title];
  d.open = remembered === undefined ? open : remembered;
  d.addEventListener("toggle", () => { state.accOpen = Object.assign({}, state.accOpen, { [title]: d.open }); });
  return d;
}

// ---------- condition builder (If, Else if, Wait until, Loop while) ----------
const OPS = [["eq", "equals"], ["ne", "does not equal"], ["gt", "is greater than"], ["ge", "is greater than or equal to"], ["lt", "is less than"],
  ["le", "is less than or equal to"], ["contains", "contains"], ["notContains", "does not contain"], ["startsWith", "starts with"],
  ["notStartsWith", "does not start with"], ["endsWith", "ends with"], ["notEndsWith", "does not end with"], ["matches", "matches pattern (regex)"],
  ["notMatches", "does not match pattern"], ["empty", "is empty"], ["notEmpty", "is not empty"], ["in", "is one of (list)"], ["notIn", "is not one of (list)"],
  ["between", "is between [low, high]"], ["notBetween", "is not between [low, high]"]];

function conditionCatalog() {
  const out = [];
  for (const p of state.packages) for (const c of p.conditions || []) out.push({ addr: p.id + "/" + c.id, name: c.name, pkg: p.name, spec: c });
  return out;
}

function parseCondRow(c) {
  let not = false;
  if (c && typeof c === "object" && "not" in c && Object.keys(c).length === 1) { not = true; c = c.not; }
  if (c && typeof c === "object" && c.op) {
    if (c.op === "eq" && (c.right === true || c.right === false)) return { kind: "bool", left: showValue(c.left), truth: c.right, not };
    return { kind: "compare", left: showValue(c.left), op: c.op, right: showValue(c.right), not };
  }
  if (c && typeof c === "object" && typeof c.condition === "string") return { kind: "pkg", addr: c.condition, args: Object.assign({}, c.args || {}), not };
  return null;
}

function parseCondition(c) {
  if (c === undefined || c === null || c === "") return { mode: "builder", match: "all", rows: [{ kind: "compare", left: "", op: "eq", right: "", not: false }] };
  if (typeof c === "object" && !Array.isArray(c)) {
    const key = Array.isArray(c.all) ? "all" : Array.isArray(c.any) ? "any" : null;
    const items = key ? c[key] : [c];
    const rows = items.map(parseCondRow);
    if (rows.every(Boolean)) return { mode: "builder", match: key || "all", rows };
  }
  return { mode: "expr", text: typeof c === "string" ? c : typeof c === "object" && c.expr ? c.expr : JSON.stringify(c) };
}

function serializeCondition(m) {
  if (m.mode === "expr") { const t = (m.text || "").trim(); if (!t) return undefined; try { const j = JSON.parse(t); if (typeof j === "object") return j; } catch (_) { /* expression text */ } return t; }
  const items = m.rows.map((r) => {
    let c;
    if (r.kind === "bool") c = { op: "eq", left: parseLoose(r.left), right: r.truth };
    else if (r.kind === "pkg") c = { condition: r.addr, args: r.args };
    else { c = { op: r.op, left: parseLoose(r.left) }; if (!["empty", "notEmpty"].includes(r.op)) c.right = parseLoose(r.right); }
    return r.not ? { not: c } : c;
  });
  return items.length === 1 ? items[0] : { [m.match]: items };
}

function conditionEditor(value, onChange) {
  const model = parseCondition(value);
  const box = h("div", { class: "cond" });
  const commit = () => { onChange(serializeCondition(model)); render(); };
  const catalog = conditionCatalog();
  function rowEl(r, i) {
    const kindSel = h("select", { "aria-label": "Condition type", onchange: (e) => {
      const v = e.target.value;
      if (v === "compare") Object.assign(r, { kind: "compare", op: r.op || "eq", left: r.left || "", right: r.right || "" });
      else if (v === "true" || v === "false") Object.assign(r, { kind: "bool", truth: v === "true", left: r.left || "" });
      else Object.assign(r, { kind: "pkg", addr: v, args: {} });
      commit();
    } },
      h("optgroup", { label: "Values" }, h("option", { value: "compare", text: "Compare two values" }), h("option", { value: "true", text: "Is true" }), h("option", { value: "false", text: "Is false" })),
      ...[...new Set(catalog.map((c) => c.pkg))].map((pkg) => h("optgroup", { label: pkg }, catalog.filter((c) => c.pkg === pkg).map((c) => h("option", { value: c.addr, text: c.name })))));
    kindSel.value = r.kind === "pkg" ? r.addr : r.kind === "bool" ? String(r.truth) : "compare";
    const fields = [];
    if (r.kind === "compare") {
      const op = h("select", { "aria-label": "Operator", onchange: (e) => { r.op = e.target.value; commit(); } }, OPS.map(([v, t]) => h("option", { value: v, text: t })));
      op.value = r.op;
      fields.push(withPicker(h("input", { type: "text", value: r.left, placeholder: "$variable or value", "aria-label": "Left value", onchange: (e) => { r.left = e.target.value; commit(); } }), "Any"), op);
      if (!["empty", "notEmpty"].includes(r.op)) fields.push(withPicker(h("input", { type: "text", value: r.right, placeholder: r.op === "in" ? '["a","b"]' : r.op === "matches" ? "^INV-\\d+$" : "value or $variable", "aria-label": "Right value", onchange: (e) => { r.right = e.target.value; commit(); } }), "Any"));
    } else if (r.kind === "bool") {
      fields.push(withPicker(h("input", { type: "text", value: r.left, placeholder: "$variable", "aria-label": "Value", onchange: (e) => { r.left = e.target.value; commit(); } }), "Boolean"));
    } else {
      const c = catalog.find((x) => x.addr === r.addr);
      if (!c) fields.push(h("div", { class: "help", text: "Unknown condition " + r.addr + " (is its toolkit installed?)" }));
      else for (const [name, prop] of Object.entries(c.spec.inputs.properties)) {
        const req = (c.spec.inputs.required || []).includes(name);
        fields.push(h("label", { class: "cond-arg" }, h("span", {}, name, req ? h("span", { class: "req", text: " *" }) : null),
          withPicker(h("input", { type: "text", value: showValue(r.args[name]), placeholder: prop.default !== undefined ? "default: " + prop.default : prop["x-ooa-type"] || "",
            title: prop.description || "", onchange: (e) => { const v = parseLoose(e.target.value); if (v === undefined || e.target.value === "") delete r.args[name]; else r.args[name] = v; commit(); } }), prop["x-ooa-type"] || "Any")));
      }
    }
    return h("div", { class: "cond-row" },
      h("div", { class: "cond-top" }, kindSel,
        h("label", { class: "check", title: "Reverse this condition" }, h("input", { type: "checkbox", checked: r.not, onchange: (e) => { r.not = e.target.checked; commit(); } }), "Not"),
        model.rows.length > 1 ? h("button", { type: "button", class: "link-btn", "aria-label": "Remove condition", onclick: () => { model.rows.splice(i, 1); commit(); } }, "Remove") : null),
      h("div", { class: "cond-fields" }, fields));
  }
  function render() {
    if (model.mode === "expr") {
      box.replaceChildren(
        h("textarea", { rows: 2, value: model.text || "", placeholder: "$amount > 1000 && $status == 'open'", "aria-label": "Condition expression", onchange: (e) => { model.text = e.target.value; onChange(serializeCondition(model)); } }),
        h("div", { class: "row-actions" }, h("button", { type: "button", class: "link-btn", onclick: () => { Object.assign(model, parseCondition(undefined)); commit(); } }, "Use the condition builder")));
      return;
    }
    const match = h("select", { "aria-label": "Combine conditions", onchange: (e) => { model.match = e.target.value; commit(); } },
      h("option", { value: "all", text: "All of these are true (AND)" }), h("option", { value: "any", text: "Any of these is true (OR)" }));
    match.value = model.match;
    box.replaceChildren(
      model.rows.length > 1 ? match : null,
      ...model.rows.map(rowEl),
      h("div", { class: "row-actions" },
        h("button", { type: "button", onclick: () => { model.rows.push({ kind: "compare", left: "", op: "eq", right: "", not: false }); commit(); } }, "+ Condition"),
        h("button", { type: "button", class: "link-btn", onclick: () => { model.mode = "expr"; model.text = ""; onChange(undefined); render(); } }, "Write an expression instead")));
  }
  render();
  return box;
}

function renderProps() {
  const box = $("props");
  if (!state.flow || !state.selected) {
    box.replaceChildren(h("p", { class: "desc", text: state.flow ? "Select a step on the canvas to edit its settings." : "Open an agent to start." }));
    return;
  }
  const step = getAt(state.flow, state.selected);
  if (!step) { state.selected = null; return renderProps(); }
  const spec = state.specs.get(step.action);
  const st = styleFor(step.action);
  const parts = [];
  parts.push(h("div", { class: "props-title" }, tile(st.icon, st.color, "lg"),
    h("div", {}, h("h2", { class: "props-head", text: spec ? spec.action.name : step.action }),
      h("div", { class: "desc", text: (spec ? spec.pkg.name + " \u00B7 " : "") + step.action }))));
  parts.push(h("p", { class: "desc", text: spec ? spec.action.description : "Unknown action" }));

  const general = [
    h("div", { class: "field" }, h("label", { text: "Step id" }),
      h("input", { type: "text", value: step.id, onchange: (e) => {
        const v = e.target.value.trim();
        if (!/^[A-Za-z0-9_.-]{1,64}$/.test(v)) { toast("Step ids use letters, digits, '.', '-' and '_'.", true); e.target.value = step.id; return; }
        if (v !== step.id && allSteps(state.flow.steps).some((x) => x.id === v)) { toast("Another step already has id " + v, true); e.target.value = step.id; return; }
        step.id = v; changed();
      } })),
    h("div", { class: "field" }, h("label", { text: "Note" }),
      h("input", { type: "text", value: step.description || "", placeholder: "What this step does (optional)",
        onchange: (e) => { if (e.target.value) step.description = e.target.value; else delete step.description; changed(); } })),
  ];

  if (spec) {
    const props = spec.action.inputs.properties, req = spec.action.inputs.required || [];
    const inputs = Object.entries(props).map(([name, prop]) => inputControl(step, name, prop, req.includes(name)));
    if (inputs.length) parts.push(acc("Inputs", inputs, true));
    const outs = Object.entries(spec.action.outputs || {});
    if (outs.length) {
      const nodes = outs.map(([oname, o]) => {
        const sel = h("select", { onchange: (e) => {
          step.out = step.out || {};
          if (e.target.value) step.out[oname] = e.target.value; else delete step.out[oname];
          if (!Object.keys(step.out).length) delete step.out;
          changed();
        } }, h("option", { value: "", text: "(do not store)" }), ...outputOptions(o["x-ooa-type"]));
        sel.value = (step.out || {})[oname] || "";
        const mk = h("button", { type: "button", title: "Create a new variable for this output", onclick: () => {
          const n = addVariable(oname, o["x-ooa-type"]);
          step.out = step.out || {}; step.out[oname] = n;
          changed(); renderProps(); renderVariables();
          toast("Created variable " + n);
        } }, "+ New");
        return h("div", { class: "field" }, h("label", {}, oname, typeChip(o["x-ooa-type"] || "Any")),
          h("div", { class: "field-row" }, sel, mk), h("div", { class: "help", text: o.description || "" }));
      });
      parts.push(acc("Outputs", nodes, true));
    }
  }

  if (step.action === "core/if") {
    const branches = (step.elseIf || []).map((br, k) => h("div", { class: "field" },
      h("label", {}, "Else if #" + (k + 1), " ", h("button", { type: "button", class: "link-btn", onclick: () => { step.elseIf.splice(k, 1); if (!step.elseIf.length) delete step.elseIf; changed(); renderProps(); } }, "Remove")),
      conditionEditor(br.condition, (v) => { br.condition = v; changed(); })));
    parts.push(acc("Else if branches", [...branches,
      h("button", { type: "button", onclick: () => { step.elseIf = step.elseIf || []; step.elseIf.push({ condition: { op: "eq", left: "", right: "" }, children: [] }); changed(); renderProps(); } }, "+ Else if")], (step.elseIf || []).length > 0));
  }
  if (step.action === "core/try" && step.catch && step.catch[0]) {
    const c = step.catch[0];
    const asSel = h("select", { onchange: (e) => { if (e.target.value) c.as = e.target.value; else delete c.as; changed(); } },
      h("option", { value: "", text: "(do not store)" }), varNames().map((n) => h("option", { value: n, text: n })));
    asSel.value = c.as || "";
    parts.push(acc("Catch", [
      h("div", { class: "field" }, h("label", { text: "Error codes to catch" }),
        h("input", { type: "text", value: (c.codes || []).join(", "), placeholder: "Empty = all errors, e.g. BUSINESS.*, FILE.NOT_FOUND",
          onchange: (e) => { c.codes = e.target.value.split(",").map((x) => x.trim()).filter(Boolean); changed(); } })),
      h("div", { class: "field" }, h("label", { text: "Store the error in" }), asSel,
        h("div", { class: "help", text: "A Dictionary variable receives code, message and suggestedFix." })),
    ], true));
  }

  const handling = [
    h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!step.disabled,
      onchange: (e) => { if (e.target.checked) step.disabled = true; else delete step.disabled; changed(); } }), "Disabled (skip this step)"),
    h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!step.continueOnError,
      onchange: (e) => { if (e.target.checked) step.continueOnError = true; else delete step.continueOnError; changed(); } }), "Continue the agent if this step fails"),
  ];
  if (spec && !step.action.startsWith("core/")) {
    handling.push(h("div", { class: "field" }, h("label", { text: "Retries on temporary errors" }),
      h("input", { type: "number", min: "0", max: "10", value: (step.retry || {}).max || "",
        onchange: (e) => { const n = parseInt(e.target.value, 10); if (n > 0) step.retry = { max: n, backoff: "exponential", delayMs: 500 }; else delete step.retry; changed(); } })));
    handling.push(h("div", { class: "field" }, h("label", { text: "Timeout (seconds)" }),
      h("input", { type: "number", min: "1", value: step.timeoutSeconds || "", placeholder: "default " + spec.action.timeoutSeconds,
        onchange: (e) => { const n = parseFloat(e.target.value); if (n > 0) step.timeoutSeconds = n; else delete step.timeoutSeconds; changed(); } })));
  }
  parts.push(acc("Error handling", handling, false));
  parts.push(acc("General", general, false));
  parts.push(h("div", { class: "row-actions" },
    h("button", { type: "button", onclick: () => moveSelected(-1) }, "Move up"),
    h("button", { type: "button", onclick: () => moveSelected(1) }, "Move down"),
    h("button", { type: "button", onclick: duplicateSelected }, "Duplicate"),
    h("button", { type: "button", class: "danger", onclick: deleteSelected }, "Delete")));
  box.replaceChildren(...parts);
}

// ---------- variables, settings, JSON ----------
async function loadBuiltins() {
  try { state.builtins = await api("GET", "builtins"); } catch (_) { state.builtins = { sys: {}, global: {} }; }
  renderVariables();
}

function insertReference(ref) {
  const el = state.lastInput;
  if (el && document.body.contains(el)) {
    const st = el.selectionStart ?? el.value.length, en = el.selectionEnd ?? el.value.length;
    el.value = el.value.slice(0, st) + ref + el.value.slice(en);
    el.dispatchEvent(new Event("change", { bubbles: true }));
    toast("Inserted " + ref);
  } else {
    try { navigator.clipboard.writeText(ref); toast("Copied " + ref + "; paste it into any field."); } catch (_) { toast(ref); }
  }
}

// ---------- variable types, value editors and the variables pane ----------
const TYPE_STYLE = {
  String: { icon: "text", color: "#2563eb", hint: "Text" }, Number: { icon: "hash", color: "#059669", hint: "Whole or decimal number" },
  Boolean: { icon: "toggle", color: "#7c3aed", hint: "True or false" }, DateTime: { icon: "calendar", color: "#d97706", hint: "Date and time" },
  List: { icon: "list", color: "#0891b2", hint: "Ordered items" }, Dictionary: { icon: "braces", color: "#db2777", hint: "Named keys and values" },
  Table: { icon: "table", color: "#16a34a", hint: "Rows and columns" }, Record: { icon: "record", color: "#9333ea", hint: "One row of named values" },
  File: { icon: "file", color: "#64748b", hint: "File or folder path" }, Window: { icon: "window", color: "#ea580c", hint: "An application window" },
  UIElement: { icon: "cursor", color: "#c2410c", hint: "An element on screen or in a web page" }, Credential: { icon: "lock", color: "#dc2626", hint: "Secret from the vault" },
  Any: { icon: "star", color: "#475569", hint: "Any kind of value" },
};
function typeBadge(type) { const st = TYPE_STYLE[type] || TYPE_STYLE.Any; const b = tile(st.icon, st.color, "sm type-badge"); b.title = type + ": " + st.hint; return b; }
function typeChip(type) { const c = h("span", { class: "type-chip", text: type }); c.style.setProperty("--c", (TYPE_STYLE[type] || TYPE_STYLE.Any).color); return c; }
function guessType(v) {
  if (typeof v === "number") return "Number";
  if (typeof v === "boolean") return "Boolean";
  if (Array.isArray(v)) return "List";
  if (v && typeof v === "object") return Array.isArray(v.columns) && Array.isArray(v.rows) ? "Table" : "Dictionary";
  return "String";
}
function preview(v) { const t = showValue(v); return t.length > 42 ? t.slice(0, 40) + "\u2026" : t; }
/** Can a variable of type `have` be used where a command expects `want`? Strings accept most values (they are formatted). */
function compatible(want, have) {
  if (!want || want === "Any" || !have || have === "Any" || want === have) return true;
  if (want === "String") return ["Number", "Boolean", "DateTime", "File"].includes(have);
  if (want === "File") return have === "String";
  return (want === "Record" && have === "Dictionary") || (want === "Dictionary" && have === "Record");
}
function outputOptions(want) {
  const vars = state.flow.variables;
  const fit = vars.filter((v) => compatible(want, v.type) || compatible(v.type, want)), rest = vars.filter((v) => !fit.includes(v));
  const opt = (v) => h("option", { value: v.name, text: v.name + "  (" + v.type + ")" });
  if (!rest.length) return fit.map(opt);
  return [fit.length ? h("optgroup", { label: "Matching " + (want || "Any") }, fit.map(opt)) : null, h("optgroup", { label: "Other variables" }, rest.map(opt))];
}
/** Matches $name and the start of an interpolation ${name...}; group 1 keeps the "{" so a rename can put it back. */
function varRegex(name) { return new RegExp("\\$(\\{\\s*)?" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "(?![A-Za-z0-9_])", "g"); }
function usageCount(name) {
  if (!state.flow) return 0;
  const re = varRegex(name);
  let n = 0;
  for (const st of allSteps(state.flow.steps)) {
    n += (JSON.stringify([st.args || {}, (st.elseIf || []).map((b) => b.condition)]).match(re) || []).length;
    for (const v of Object.values(st.out || {})) if (v === name) n++;
    for (const k of ["variable", "as", "index"]) if (st.args && st.args[k] === name) n++;
    for (const c of st.catch || []) if (c.as === name) n++;
  }
  return n;
}
/** After a rename, every $old reference, output and loop/catch name in the bot points to the new name. */
function renameVariableRefs(oldName, newName) {
  const re = varRegex(oldName);
  const map = (v) => typeof v === "string" ? v.replace(re, (m, brace) => "$" + (brace || "") + newName) : Array.isArray(v) ? v.map(map)
    : v && typeof v === "object" ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, map(x)])) : v;
  for (const st of allSteps(state.flow.steps)) {
    if (st.args) { st.args = map(st.args); for (const k of ["variable", "as", "index"]) if (st.args[k] === oldName) st.args[k] = newName; }
    for (const b of st.elseIf || []) b.condition = map(b.condition);
    for (const [k, v] of Object.entries(st.out || {})) if (v === oldName) st.out[k] = newName;
    for (const c of st.catch || []) if (c.as === oldName) c.as = newName;
  }
}
function uniqueVarName(base) {
  let b = String(base || "variable").replace(/[^A-Za-z0-9_]+/g, "_").replace(/^([0-9])/, "_$1").slice(0, 56) || "variable";
  let n = b, k = 2;
  while (state.flow && state.flow.variables.some((v) => v.name === n)) n = b + "_" + k++;
  return n;
}

function fileOptions() {
  let dl = $("ws-files");
  if (!dl) { dl = h("datalist", { id: "ws-files" }); document.body.appendChild(dl); }
  const out = [];
  const walk = (n) => { if (!n) return; if (n.type !== "folder") out.push(n.path); for (const c of n.children || []) walk(c); };
  walk(state.tree);
  dl.replaceChildren(...out.slice(0, 2000).map((p) => h("option", { value: p })));
}

/**
 * An editor for one value of a variable type: text, number, true/false, date and time, list, keys and values, table grid,
 * file path (with workspace suggestions) or JSON. The returned element has getValue(); undefined means "not set".
 */
function valueEditor(type, value, onChange, opts = {}) {
  const emit = () => { if (onChange) onChange(el.getValue()); };
  let el;
  if (Array.isArray(opts.enum) && opts.enum.length) {
    el = h("select", { "aria-label": opts.label || "Value", onchange: emit }, h("option", { value: "", text: "(not set)" }), opts.enum.map((x) => h("option", { value: String(x), text: String(x) })));
    el.value = value === undefined ? "" : String(value);
    el.getValue = () => (el.value === "" ? undefined : opts.enum.find((x) => String(x) === el.value));
    return el;
  }
  switch (type) {
    case "Boolean":
      el = h("select", { "aria-label": opts.label || "Value", onchange: emit }, h("option", { value: "", text: "(not set)" }), h("option", { value: "true", text: "True" }), h("option", { value: "false", text: "False" }));
      el.value = value === true ? "true" : value === false ? "false" : "";
      el.getValue = () => (el.value === "" ? undefined : el.value === "true");
      return el;
    case "Number":
      el = h("input", { type: "text", inputmode: "decimal", "aria-label": opts.label || "Value", value: value === undefined ? "" : showValue(value), placeholder: opts.placeholder || "e.g. 42 or 3.5" });
      el.getValue = () => { const t = el.value.trim(); if (!t) return undefined; const n = Number(t); return Number.isFinite(n) ? n : t; };
      el.addEventListener("change", () => { const v = el.getValue(); el.classList.toggle("invalid", typeof v === "string" && !v.startsWith("$")); emit(); });
      return el;
    case "DateTime": {
      const iso = typeof value === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value) ? value.slice(0, 19) : "";
      el = h("input", { type: "datetime-local", step: "1", "aria-label": opts.label || "Value", value: iso });
      el.getValue = () => (el.value ? (el.value.length === 16 ? el.value + ":00" : el.value) : undefined);
      el.addEventListener("change", emit);
      return el;
    }
    case "List": case "Dictionary": case "Record": case "Table":
      el = structuredEditor(type, value, () => emit());
      return el;
    case "Any":
      el = h("textarea", { rows: 2, "aria-label": opts.label || "Value", value: showValue(value), placeholder: opts.placeholder || "Text, number, true/false or JSON" });
      el.getValue = () => parseLoose(el.value);
      el.addEventListener("change", emit);
      return el;
    case "File":
      fileOptions();
      el = h("input", { type: "text", list: "ws-files", "aria-label": opts.label || "Value", value: showValue(value), placeholder: opts.placeholder || "Path in the workspace, e.g. data/orders.csv, or C:\\Folder\\file.xlsx" });
      el.getValue = () => (el.value.trim() === "" ? undefined : el.value.trim());
      el.addEventListener("change", emit);
      return el;
    case "Credential":
      el = h("input", { type: "text", "aria-label": opts.label || "Value", value: "", placeholder: opts.placeholder || "locker/credential/attribute" });
      el.getValue = () => (el.value.trim() === "" ? undefined : el.value.trim());
      el.addEventListener("change", emit);
      return el;
    default:
      el = h("input", { type: "text", "aria-label": opts.label || "Value", value: value === undefined ? "" : showValue(value), placeholder: opts.placeholder || (type === "Window" ? "Window title, e.g. *Notepad" : type === "UIElement" ? "Selector, e.g. #submit" : "") });
      el.getValue = () => (el.value === "" ? undefined : el.value);
      el.addEventListener("change", emit);
      return el;
  }
}

/** Row editors for List, Dictionary/Record and Table values, with an "Edit as JSON" switch. */
function structuredEditor(kind, value, emit) {
  const box = h("div", { class: "ve" });
  const isTable = kind === "Table", isList = kind === "List";
  const fits = (v) => (isList ? Array.isArray(v) : isTable ? !!v && Array.isArray(v.columns) && Array.isArray(v.rows) : !!v && typeof v === "object" && !Array.isArray(v));
  let cur = value === undefined ? (isList ? [] : isTable ? { columns: [], rows: [] } : {}) : JSON.parse(JSON.stringify(value));
  let asJson = value !== undefined && !fits(value);
  let pairs = !isList && !isTable && fits(cur) ? Object.entries(cur) : [];
  const ta = h("textarea", { rows: 4, "aria-label": kind + " as JSON" });
  const empty = () => (isList ? !cur.length : isTable ? !cur.columns.length : !pairs.length);
  box.getValue = () => {
    if (asJson) return parseLoose(ta.value);
    if (!isList && !isTable) { cur = {}; for (const [k, v] of pairs) if (k !== "") cur[k] = v; }
    return empty() ? undefined : JSON.parse(JSON.stringify(cur));
  };
  const changedNow = () => { emit(); };
  const cell = (v, onSet, label) => h("input", { type: "text", value: showValue(v), "aria-label": label, onchange: (e) => { const x = parseLoose(e.target.value); onSet(x === undefined ? "" : x); changedNow(); } });
  const x = (label, fn) => h("button", { type: "button", class: "icon-mini danger", "aria-label": label, title: label, onclick: () => { fn(); render(); changedNow(); } }, "\u2715");
  function render() {
    if (asJson) {
      ta.value = typeof cur === "string" ? cur : value === undefined && empty() ? "" : JSON.stringify(fits(cur) ? box.getValueRaw() : cur, null, 2);
      box.replaceChildren(ta, h("div", { class: "ve-actions" }, h("button", { type: "button", class: "link-btn", onclick: () => {
        const v = parseLoose(ta.value);
        if (v !== undefined && !fits(v)) { toast("That JSON is not a " + kind + "; keep editing it as JSON.", true); return; }
        cur = v === undefined ? (isList ? [] : isTable ? { columns: [], rows: [] } : {}) : v; pairs = !isList && !isTable ? Object.entries(cur) : pairs; asJson = false; render(); } }, "Use the " + kind.toLowerCase() + " editor")));
      return;
    }
    const kids = [];
    if (isList) {
      cur.forEach((item, i) => kids.push(h("div", { class: "ve-row" }, h("span", { class: "ve-idx", text: String(i + 1) }), cell(item, (v) => { cur[i] = v; }, "Item " + (i + 1)), x("Remove item " + (i + 1), () => cur.splice(i, 1)))));
      if (!cur.length) kids.push(h("div", { class: "empty", text: "No items yet." }));
    } else if (isTable) {
      const t = h("table", { class: "ve-table" },
        h("thead", {}, h("tr", {}, cur.columns.map((c, j) => h("th", {}, h("div", { class: "ve-row" },
          h("input", { type: "text", value: c, "aria-label": "Column " + (j + 1) + " name", onchange: (e) => { cur.columns[j] = e.target.value; changedNow(); } }),
          x("Remove column " + c, () => { cur.columns.splice(j, 1); for (const r of cur.rows) r.splice(j, 1); })))), h("th", {}))),
        h("tbody", {}, cur.rows.map((r, i) => h("tr", {}, cur.columns.map((_, j) => h("td", {}, cell(r[j], (v) => { r[j] = v; }, "Row " + (i + 1) + " " + cur.columns[j]))),
          h("td", {}, x("Remove row " + (i + 1), () => cur.rows.splice(i, 1)))))));
      kids.push(cur.columns.length ? h("div", { class: "ve-table-wrap" }, t) : h("div", { class: "empty", text: "Add a column to start the table." }));
    } else {
      pairs.forEach((pr, i) => kids.push(h("div", { class: "ve-row" },
        h("input", { type: "text", value: pr[0], placeholder: "key", "aria-label": "Key " + (i + 1), onchange: (e) => { pr[0] = e.target.value.trim(); changedNow(); } }),
        cell(pr[1], (v) => { pr[1] = v; }, "Value " + (i + 1)), x("Remove key " + (pr[0] || i + 1), () => pairs.splice(i, 1)))));
      if (!pairs.length) kids.push(h("div", { class: "empty", text: "No keys yet." }));
    }
    const add = isList ? h("button", { type: "button", class: "small", onclick: () => { cur.push(""); render(); } }, "+ Item")
      : isTable ? [h("button", { type: "button", class: "small", onclick: () => { cur.columns.push("column" + (cur.columns.length + 1)); for (const r of cur.rows) r.push(""); render(); changedNow(); } }, "+ Column"),
        cur.columns.length ? h("button", { type: "button", class: "small", onclick: () => { cur.rows.push(cur.columns.map(() => "")); render(); changedNow(); } }, "+ Row") : null]
      : h("button", { type: "button", class: "small", onclick: () => { pairs.push(["", ""]); render(); } }, "+ Key");
    kids.push(h("div", { class: "ve-actions" }, add, h("span", { class: "spacer" }), h("button", { type: "button", class: "link-btn", onclick: () => { asJson = true; render(); } }, "Edit as JSON")));
    box.replaceChildren(...kids);
  }
  box.getValueRaw = () => { if (!isList && !isTable) { const o = {}; for (const [k, v] of pairs) if (k !== "") o[k] = v; return o; } return cur; };
  render();
  return box;
}

// ---------- dialog ----------
function openDialog(title, body, actions, opts = {}) {
  $("dialog-title").textContent = title;
  $("dialog-body").replaceChildren(...[].concat(body).filter(Boolean));
  $("dialog-actions").replaceChildren(...actions, h("button", { type: "button", onclick: closeDialog }, opts.closeLabel || "Cancel"));
  $("dialog").classList.toggle("wide", !!opts.wide);
  $("dialog").hidden = false;
  const first = $("dialog-body").querySelector("input, select, textarea, button");
  if (first) first.focus();
}
function closeDialog() { $("dialog").hidden = true; }
function field(label, control, help) { return h("div", { class: "field" }, h("label", { text: label }), control, help ? h("div", { class: "help", text: help }) : null); }

/** Create or edit a bot variable: name, type, use (input/output/inside), default value editor for the type, description. */
function editVariable(existing, preset = {}) {
  if (!state.flow) { toast("Open an agent first.", true); return; }
  const isNew = !existing;
  const v = isNew ? { name: uniqueVarName(preset.name || "new_variable"), type: preset.type || "String" } : JSON.parse(JSON.stringify(existing));
  if (isNew && preset.direction && preset.direction !== "local") v.direction = preset.direction;
  const name = h("input", { type: "text", value: v.name, spellcheck: "false", "aria-label": "Variable name" });
  const typeGrid = h("div", { class: "type-grid", role: "radiogroup", "aria-label": "Type" });
  const dirSeg = h("div", { class: "seg", role: "radiogroup", "aria-label": "Use" });
  const reqBox = h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!v.required, onchange: (e) => { v.required = e.target.checked; } }), "Required: the agent cannot start without a value");
  const defBox = h("div"), enumBox = h("div");
  const desc = h("textarea", { rows: 2, value: v.description || "", placeholder: "What this variable holds. People running the agent and AI agents read this." });
  let defEditor = null;
  const keepDefault = () => { if (defEditor) { const d = defEditor.getValue(); if (d === undefined) delete v.default; else v.default = d; } };
  const renderType = () => typeGrid.replaceChildren(...TYPES.map((t) => h("button", { type: "button", class: "type-opt", role: "radio", "aria-checked": String(v.type === t), title: (TYPE_STYLE[t] || {}).hint,
    onclick: () => { if (v.type !== t) { v.type = t; delete v.default; delete v.enum; renderType(); renderDefault(); } } }, typeBadge(t), h("span", { text: t }))));
  const renderDir = () => {
    dirSeg.replaceChildren(...[["local", "Inside the agent"], ["input", "Input"], ["output", "Output"]].map(([d, l]) => h("button", { type: "button", role: "radio", "aria-checked": String((v.direction || "local") === d),
      onclick: () => { keepDefault(); if (d === "local") delete v.direction; else v.direction = d; renderDir(); renderDefault(); } }, l)));
    reqBox.hidden = v.direction !== "input";
  };
  const renderDefault = () => {
    if (v.type === "Credential") {
      defEditor = null; delete v.default;
      defBox.replaceChildren(h("div", { class: "help", text: "Credentials never have a default. Whoever runs the agent gives a vault reference (vault folder/credential/attribute)." }));
      enumBox.replaceChildren();
      return;
    }
    defEditor = valueEditor(v.type, v.default, null, { label: "Default value", enum: v.enum });
    defBox.replaceChildren(defEditor, h("div", { class: "help", text: v.direction === "input" ? "Used when the caller gives no value." : "The value the variable starts with. Leave empty for none." }));
    if (v.direction === "input" && ["String", "Number"].includes(v.type)) {
      const en = h("input", { type: "text", value: (v.enum || []).join(", "), placeholder: "Optional: the only values allowed, separated by commas" });
      en.addEventListener("change", () => {
        const vals = en.value.split(",").map((x) => x.trim()).filter(Boolean).map((x) => (v.type === "Number" && Number.isFinite(Number(x)) ? Number(x) : x));
        keepDefault();
        if (vals.length) v.enum = vals; else delete v.enum;
        if (v.enum && v.default !== undefined && !v.enum.includes(v.default)) delete v.default;
        renderDefault();
      });
      enumBox.replaceChildren(field("Allowed values", en));
    } else enumBox.replaceChildren();
  };
  renderType(); renderDir(); renderDefault();
  const save = h("button", { type: "button", class: "primary", onclick: () => {
    const n = name.value.trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(n)) { toast("Variable names start with a letter or _ and use letters, digits and _.", true); name.focus(); return; }
    if (state.flow.variables.some((x) => x.name === n && x !== existing)) { toast("Another variable is already called " + n + ".", true); name.focus(); return; }
    keepDefault();
    if (desc.value.trim()) v.description = desc.value.trim(); else delete v.description;
    if (v.direction !== "input") { delete v.required; delete v.enum; }
    if (!v.required) delete v.required;
    v.name = n;
    if (existing) {
      if (existing.name !== n) renameVariableRefs(existing.name, n);
      for (const k of Object.keys(existing)) delete existing[k];
      Object.assign(existing, v);
    } else state.flow.variables.push(v);
    closeDialog();
    changed(); renderVariables(); renderProps(); renderRunPanel();
    toast((existing ? "Saved" : "Created") + " variable $" + n);
    if (preset.onSave) preset.onSave(n);
  } }, isNew ? "Create variable" : "Save changes");
  openDialog(isNew ? "New variable" : "Edit variable $" + existing.name, [
    field("Name", name, "Use it in any field as $" + v.name + ". Renaming updates every use in this agent."),
    field("Type", typeGrid), h("div", { class: "field" }, h("label", { text: "Use" }), dirSeg, reqBox),
    h("div", { class: "field" }, h("label", { text: "Default value" }), defBox), enumBox, field("Description", desc)], [save], { wide: true });
}

function insertReference(ref) {
  const el = state.lastInput;
  if (el && document.body.contains(el)) insertAt(el, ref, false);
  else {
    try { navigator.clipboard.writeText(ref); toast("Copied " + ref + "; paste it into any field."); } catch (_) { toast(ref); }
  }
}
function insertAt(el, ref, replaceDollar) {
  const st = el.selectionStart ?? el.value.length, en = el.selectionEnd ?? el.value.length;
  const start = replaceDollar && el.value[st - 1] === "$" ? st - 1 : st;
  el.value = el.value.slice(0, start) + ref + el.value.slice(en);
  el.focus();
  try { el.setSelectionRange(start + ref.length, start + ref.length); } catch (_) { /* not a text field */ }
  el.dispatchEvent(new Event("change", { bubbles: true }));
  toast("Inserted " + ref);
}

/** Adds a {x} button to a text field: pick a variable (matching the field's type first), or press Ctrl+Space or type $. */
function withPicker(control, wantType) {
  if (!control || !/^(INPUT|TEXTAREA)$/.test(control.tagName)) return control;
  const btn = h("button", { type: "button", class: "var-btn", title: "Insert a variable (Ctrl+Space, or type $)", "aria-label": "Insert a variable",
    onclick: (e) => { e.preventDefault(); e.stopPropagation(); openVarPicker(control, wantType, false); } }, "{x}");
  control.addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key === " ") { e.preventDefault(); openVarPicker(control, wantType, false); } });
  control.addEventListener("input", (e) => { if (e.data === "$") openVarPicker(control, wantType, true); });
  return h("div", { class: "var-field" }, control, btn);
}

function openVarPicker(target, wantType, typedDollar) {
  closeVarPicker();
  if (!state.builtins) loadBuiltins();
  const want = wantType && wantType !== "Any" ? wantType : null;
  const q = h("input", { type: "search", placeholder: "Find a variable", "aria-label": "Find a variable" });
  const list = h("div", { class: "var-pop-list", role: "listbox" });
  const pick = (ref) => { closeVarPicker(); insertAt(target, ref, typedDollar); };
  const item = (ref, type, note) => h("button", { type: "button", class: "var-pop-item", role: "option", onclick: () => pick(ref),
    onkeydown: (e) => { if (e.key === "ArrowDown" && e.target.nextElementSibling) { e.preventDefault(); (e.target.parentElement.querySelectorAll(".var-pop-item")[[...e.target.parentElement.querySelectorAll(".var-pop-item")].indexOf(e.target) + 1] || e.target).focus(); }
      if (e.key === "ArrowUp") { e.preventDefault(); const all = [...e.target.parentElement.querySelectorAll(".var-pop-item")]; (all[all.indexOf(e.target) - 1] || q).focus(); }
      if (e.key === "Escape") { closeVarPicker(); target.focus(); } } },
    typeBadge(type), h("span", { class: "mono", text: ref }), note ? h("span", { class: "muted vnote", text: note }) : null);
  const render = () => {
    const f = q.value.trim().toLowerCase();
    const m = (n) => !f || n.toLowerCase().includes(f);
    const vars = (state.flow ? state.flow.variables : []).filter((v) => m(v.name));
    const good = vars.filter((v) => compatible(want, v.type)), other = vars.filter((v) => !compatible(want, v.type));
    const sys = Object.entries((state.builtins || {}).sys || {}).filter(([k]) => m("sys." + k));
    const glob = Object.entries((state.builtins || {}).global || {}).filter(([k]) => m("global." + k));
    const parts = [];
    if (good.length) parts.push(h("div", { class: "var-pop-head", text: want ? "Variables that fit (" + want + ")" : "Agent variables" }), ...good.map((v) => item("$" + v.name, v.type, v.direction && v.direction !== "local" ? v.direction : "")));
    if (other.length) parts.push(h("div", { class: "var-pop-head", text: "Other variables" }), ...other.map((v) => item("$" + v.name, v.type, v.type)));
    if (sys.length) parts.push(h("div", { class: "var-pop-head", text: "System" }), ...sys.map(([k, val]) => item("$sys." + k, guessType(val), preview(val))));
    if (glob.length) parts.push(h("div", { class: "var-pop-head", text: "Shared values" }), ...glob.map(([k, val]) => item("$global." + k, guessType(val), preview(val))));
    if (!parts.length) parts.push(h("div", { class: "empty", text: "No variables match." }));
    list.replaceChildren(...parts);
  };
  q.addEventListener("input", render);
  q.addEventListener("keydown", (e) => {
    if (e.key === "Escape") { closeVarPicker(); target.focus(); }
    if (e.key === "Enter") { const first = list.querySelector(".var-pop-item"); if (first) { e.preventDefault(); first.click(); } }
    if (e.key === "ArrowDown") { const first = list.querySelector(".var-pop-item"); if (first) { e.preventDefault(); first.focus(); } }
  });
  const add = h("button", { type: "button", class: "link-btn", onclick: () => {
    closeVarPicker();
    editVariable(null, { type: want || "String", name: target.dataset.argName || "new_variable", onSave: (n) => insertAt(target, "$" + n, typedDollar) });
  } }, "+ New variable" + (want ? " (" + want + ")" : ""));
  const pop = h("div", { class: "var-pop", id: "var-pop", role: "dialog", "aria-label": "Insert a variable" }, q, list, h("div", { class: "var-pop-foot" }, add));
  document.body.appendChild(pop);
  const r = target.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 330)) + "px";
  pop.style.top = (r.bottom + 330 > window.innerHeight ? Math.max(8, r.top - 330) : r.bottom + 4) + "px";
  render();
  q.focus();
}
function closeVarPicker() { const p = $("var-pop"); if (p) p.remove(); }

function renderVariables() {
  if (!state.builtins) { state.builtins = { sys: {}, global: {} }; loadBuiltins(); }
  const box = $("side-variables");
  const head = h("div", { class: "side-head" }, h("span", { text: "Variables" }),
    h("span", { class: "head-acts" }, h("button", { type: "button", class: "link-btn", onclick: loadBuiltins }, "Refresh"),
      state.flow ? h("button", { type: "button", class: "small primary", id: "btn-new-var", onclick: () => editVariable(null) }, "+ New") : null));
  if (!state.flow) { box.replaceChildren(head, h("p", { class: "desc", text: "Open an agent to see its variables." })); return; }
  const search = h("input", { type: "search", placeholder: "Search variables", value: state.varQuery || "", "aria-label": "Search variables" });
  const listBox = h("div", { class: "side-scroll var-scroll" });
  search.addEventListener("input", () => { state.varQuery = search.value; renderList(); });
  box.replaceChildren(head, search, listBox);
  function row(v) {
    const uses = usageCount(v.name);
    const meta = [v.type, v.required ? "required" : null, v.default !== undefined ? "= " + (v.default === "" ? '""' : preview(v.default)) : null].filter(Boolean).join("  \u00B7  ");
    const r = h("div", { class: "var-row", tabindex: "0", role: "button", title: (v.description ? v.description + "\n" : "") + "Click to edit", "data-var": v.name,
      onclick: () => editVariable(v), onkeydown: (e) => { if (e.key === "Enter") editVariable(v); } },
      typeBadge(v.type),
      h("div", { class: "var-main" }, h("div", { class: "var-name", text: "$" + v.name }), h("div", { class: "var-meta", text: meta })),
      h("span", { class: "uses" + (uses ? "" : " none"), title: uses + " use(s) in this agent", text: uses ? uses + (uses === 1 ? " use" : " uses") : "unused" }),
      h("span", { class: "var-acts" },
        h("button", { type: "button", class: "icon-mini", title: "Insert $" + v.name + " into the field you last edited", "aria-label": "Insert $" + v.name, onclick: (e) => { e.stopPropagation(); insertReference("$" + v.name); } }, "\u2192"),
        h("button", { type: "button", class: "icon-mini", title: "Duplicate", "aria-label": "Duplicate $" + v.name, onclick: (e) => {
          e.stopPropagation(); const c = JSON.parse(JSON.stringify(v)); c.name = uniqueVarName(v.name + "_copy"); state.flow.variables.push(c); changed(); renderVariables(); } }, "\u29C9"),
        h("button", { type: "button", class: "icon-mini danger", title: "Delete", "aria-label": "Delete $" + v.name, onclick: (e) => {
          e.stopPropagation();
          if (uses && !confirm("$" + v.name + " is used " + uses + " time(s) in this agent. Delete it anyway?")) return;
          state.flow.variables.splice(state.flow.variables.indexOf(v), 1); changed(); renderVariables(); renderProps(); renderRunPanel(); } }, "\u2715")));
    r.style.setProperty("--c", (TYPE_STYLE[v.type] || TYPE_STYLE.Any).color);
    return r;
  }
  function builtin(title, prefix, values, note) {
    const rows = Object.entries(values || {}).filter(([k]) => !state.varQuery || (prefix + k).toLowerCase().includes(state.varQuery.toLowerCase())).map(([k, val]) => h("div", {
      class: "var-row builtin", tabindex: "0", role: "button", title: "Insert " + prefix + k + " into the field you last edited",
      onclick: () => insertReference(prefix + k), onkeydown: (e) => { if (e.key === "Enter") insertReference(prefix + k); } },
      typeBadge(guessType(val)), h("div", { class: "var-main" }, h("div", { class: "var-name", text: prefix + k }), h("div", { class: "var-meta", text: preview(val) }))));
    const d = h("details", { class: "var-builtins" }, h("summary", {}, h("span", { text: title }), h("span", { class: "n", text: rows.length })),
      h("div", { class: "help", text: note }), rows.length ? h("div", { class: "var-list" }, rows) : h("div", { class: "empty", text: "None defined." }));
    d.open = !!(state.varBuiltinsOpen || {})[title];
    d.addEventListener("toggle", () => { state.varBuiltinsOpen = Object.assign({}, state.varBuiltinsOpen, { [title]: d.open }); });
    return d;
  }
  function renderList() {
    const f = (state.varQuery || "").toLowerCase();
    const vars = state.flow.variables.filter((v) => !f || v.name.toLowerCase().includes(f) || (v.description || "").toLowerCase().includes(f) || v.type.toLowerCase().includes(f));
    const parts = [h("div", { class: "var-section-title", text: "Agent variables" })];
    if (!state.flow.variables.length) {
      parts.push(h("div", { class: "var-empty" }, h("strong", { text: "No variables yet" }),
        h("p", { text: "Variables hold values while the agent runs. Inputs are given when it starts; outputs are returned at the end." }),
        h("button", { type: "button", class: "primary", onclick: () => editVariable(null) }, "+ Create a variable")));
    } else for (const [dir, label, hint] of [["input", "Inputs", "Given when the agent starts"], ["output", "Outputs", "Returned when the agent ends"], ["local", "Inside the agent", "Working values"]]) {
      const group = vars.filter((v) => (v.direction || "local") === dir);
      parts.push(h("div", { class: "var-group" },
        h("div", { class: "var-group-head" }, h("span", { class: "gname", text: label }), h("span", { class: "n", text: group.length }), h("span", { class: "muted gh", text: hint }),
          h("button", { type: "button", class: "link-btn", title: "New " + label.toLowerCase() + " variable", onclick: () => editVariable(null, { direction: dir }) }, "+ Add")),
        group.length ? h("div", { class: "var-list" }, group.map(row)) : h("div", { class: "empty", text: f ? "No matches." : "None." })));
    }
    parts.push(builtin("System variables", "$sys.", state.builtins.sys, "Read-only values from the platform at run time, such as today's date or the machine name."),
      builtin("Shared values", "$global.", state.builtins.global, "Shared by all agents. Managed in Command Center > Shared values (or global-values.json in the workspace)."));
    listBox.replaceChildren(...parts);
  }
  renderList();
}

function renderSettings() {
  const f = state.flow;
  const field = (label, control, help) => h("div", { class: "field" }, h("label", { text: label }), control, help ? h("div", { class: "help", text: help }) : null);
  const kind = h("select", { onchange: (e) => { f.kind = e.target.value; changed(); } }, [["TaskBot", "Agent"], ["ApiTask", "Headless API agent"]].map(([k, t]) => h("option", { value: k, text: t })));
  kind.value = f.kind;
  $("tab-settings").replaceChildren(
    field("Agent id", h("input", { type: "text", value: f.id, onchange: (e) => { f.id = e.target.value.trim(); changed(); } }), "Used by parent agents and AI agents to call this agent."),
    field("Name", h("input", { type: "text", value: f.name, onchange: (e) => { f.name = e.target.value; changed(); } })),
    field("Kind", kind, "TaskBot can drive a desktop; ApiTask runs headless in a container."),
    field("Description", h("textarea", { rows: 3, value: f.description || "", onchange: (e) => { f.description = e.target.value; changed(); } }),
      "Describe what the agent does. AI agents read this to decide when to use the agent."),
    field("File", h("input", { type: "text", value: state.file, disabled: true }), "Rename or move the agent in the Explorer."));
}

function renderJson() { if (state.flow) $("json-text").value = JSON.stringify(state.flow, null, 2); }

function renderAll() { renderSteps(); renderProps(); renderVariables(); renderSettings(); renderJson(); renderRunPanel(); renderProblems(); renderEvents(); renderVars(); updateMini(); }

// ---------- validation ----------
function scheduleValidate(delay = 500) {
  clearTimeout(state.validateTimer);
  state.validateTimer = setTimeout(validate, delay);
}

async function validate(showToast) {
  if (!state.flow) return;
  try {
    state.problems = (await api("POST", "validate", state.flow)).problems;
    renderProblems(); renderSteps();
    if (showToast) toast(state.problems.length ? state.problems.length + " problem(s) found" : "No problems found", state.problems.length > 0);
  } catch (e) { toast(e.message, true); }
}

function renderProblems() {
  $("problem-count").textContent = state.problems.length || "";
  const items = state.problems.map((p) => h("div", { class: "problem" },
    p.stepId ? h("a", { class: "link", onclick: () => selectById(p.stepId), text: "[" + p.stepId + "]" }) : h("span", { class: "muted", text: "[" + (p.path || "bot") + "]" }),
    " ", p.message, p.suggestedFix ? h("div", { class: "help fix", text: "Fix: " + p.suggestedFix }) : null));
  $("ctab-problems").replaceChildren(...(items.length ? items : [h("div", { class: "empty", text: "No problems." })]));
}

// ---------- run ----------
function renderRunPanel() {
  if (!state.flow) {
    if (state.item && state.item.type === "script") {
      $("ctab-run").replaceChildren(h("div", { class: "run-grid" },
        h("div", {}, h("div", { class: "pane-title", text: "Script" }), h("div", { class: "mono", text: state.item.path }),
          h("p", { class: "help", text: "Runs the saved script with the Scripts toolkit, exactly as an agent would. Its output, error output and exit code appear on the right." }),
          h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: runScript }, "Run script"))),
        h("div", { id: "run-result" })));
      renderRunResult();
    }
    return;
  }
  const inputs = state.flow.variables.filter((v) => v.direction === "input");
  const form = inputs.map((v) => {
    const ed = valueEditor(v.type, undefined, null, { enum: v.enum, label: v.name,
      placeholder: v.type === "Credential" ? "locker/credential/attribute" : v.default !== undefined ? "default: " + showValue(v.default) : "" });
    ed.setAttribute("data-input", v.name);
    return h("div", { class: "field" },
      h("label", {}, v.name, v.required ? h("span", { class: "req", text: " *" }) : null, typeChip(v.type)),
      ed, v.description ? h("div", { class: "help", text: v.description }) : null);
  });
  const left = h("div", {}, h("div", { class: "pane-title", text: "Inputs" }),
    ...(form.length ? form : [h("div", { class: "empty", text: "This agent has no input variables." })]),
    h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: runBot }, "Run agent")));
  const right = h("div", { id: "run-result" });
  $("ctab-run").replaceChildren(h("div", { class: "run-grid" }, left, right));
  renderRunResult();
}

function collectInputs() {
  const out = {};
  for (const el of document.querySelectorAll("#ctab-run [data-input]")) {
    const v = state.flow.variables.find((x) => x.name === el.dataset.input);
    if (!v) continue;
    const val = el.getValue ? el.getValue() : (el.value.trim() === "" ? undefined : el.value);
    if (val === undefined || val === "") continue;
    out[v.name] = v.type === "Credential" ? { $credential: String(val).trim() } : val;
  }
  return out;
}

async function runBot() {
  if (state.item && state.item.type === "script") return runScript();
  if (!state.flow) { toast("Open an agent first.", true); return; }
  return runFlow(state.flow, collectInputs());
}

/** Runs a Flow in Studio and follows it live in the Run dock (bots, and script test runs). */
async function runFlow(flow, inputs, opts = {}) {
  if (state.running) return;
  state.running = true; state.runStarted = Date.now();
  state.live = { id: null, state: "starting", events: [], seq: 0, total: allSteps(flow.steps).length, finished: 0, current: null };
  state.liveSteps = { running: null, done: new Set(), failed: null, paused: null };
  $("btn-run").disabled = true; $("btn-debug").disabled = true;
  $("run-progress").hidden = false;
  if (document.body.classList.contains("no-dock")) setPref("dock", true);
  if (!document.body.classList.contains("dock-mini")) switchConsole("run");
  renderLive();
  try {
    const start = await api("POST", "runs", Object.assign({ flow, inputs }, opts.debug ? { debug: true, breakpoints: [...breakpointSet()], stopAtStart: !!opts.stopAtStart } : {}));
    state.live.id = start.run_id;
    while (true) {
      const r = await api("GET", "runs/" + state.live.id + "?after=" + state.live.seq);
      for (const e of r.events) applyLiveEvent(e);
      state.live.state = r.state; state.live.elapsed = r.elapsed_ms;
      const wasStopped = !!state.live.debug;
      state.live.debug = r.debug || null;
      state.liveSteps.paused = r.debug ? r.debug.stepId : null;
      if (r.debug && !wasStopped) {
        const row = document.querySelector('[data-step-id="' + CSS.escape(r.debug.stepId) + '"]');
        if (row) row.scrollIntoView({ block: "center" });
        if (document.body.classList.contains("no-dock")) setPref("dock", true);
        switchConsole("run");
      }
      renderLive(); renderSteps(); updateMini();
      if (r.result) {
        state.lastRun = { result: r.result, events: state.live.events };
        if (r.result.status === "success") state.liveSteps.running = null;
        renderRunResult(); renderEvents(); renderVars(); renderSteps();
        toast("Run " + r.result.status + " in " + r.result.durationMs + " ms", r.result.status !== "success");
        break;
      }
      await new Promise((res) => setTimeout(res, state.live.debug ? 250 : 400));
    }
  } catch (e) {
    toast(e.message, true);
    if (e.problems) { state.problems = e.problems; renderProblems(); switchConsole("problems"); }
    // The run never started (for example validation problems): show why instead of a frozen "Running" panel.
    if (!state.lastRun) { state.live = null; const box = $("run-result"); if (box) box.replaceChildren(h("div", { class: "error-box" }, h("span", { class: "code", text: e.code || "RUN.NOT_STARTED" }), " ", e.message)); }
  } finally {
    state.running = false; $("btn-run").disabled = false; $("btn-debug").disabled = false; $("run-progress").hidden = true;
    state.liveSteps.paused = null; if (state.live) state.live.debug = null;
    state.live && (state.live.state = state.lastRun ? state.lastRun.result.status : "failed");
    renderLive(); updateMini();
  }
}

function applyLiveEvent(e) {
  const L = state.live;
  L.events.push(e); L.seq = Math.max(L.seq, e.seq);
  const d = e.data || {};
  if (e.type === "run.started" && d.depth === 0 && d.totalSteps) L.total = d.totalSteps;
  if (e.type === "step.started") { state.liveSteps.running = e.stepId; L.current = { id: e.stepId, action: d.action }; }
  if (e.type === "step.finished") { state.liveSteps.done.add(e.stepId); L.finished += 1; if (state.liveSteps.running === e.stepId) state.liveSteps.running = null; }
  if (e.type === "step.failed") { state.liveSteps.failed = e.stepId; }
}

async function liveControl(action) {
  if (!state.live || !state.live.id) return;
  try { await api("POST", "runs/" + state.live.id + "/" + action); toast(action === "stop" ? "Stopping the run" : action === "pause" ? "Pausing after the current step" : "Resuming"); }
  catch (e) { toast(e.message, true); }
}

function renderLive() {
  const box = $("run-result");
  if (!box || !state.live || (!state.running && state.lastRun)) return;
  const L = state.live;
  const spec = L.current ? state.specs.get(L.current.action) : null;
  const pct = L.total ? Math.min(100, Math.round(100 * L.finished / L.total)) : 0;
  const bar = h("div", { class: "live-bar" }, h("div", {}));
  bar.firstChild.style.width = pct + "%";
  if (L.debug) { renderDebugPanel(box, L); return; }
  const paused = L.state === "paused";
  const recent = L.events.filter((e) => e.type !== "step.started").slice(-8).map((e) => h("div", { class: "event" + (e.type === "step.failed" ? " failed" : "") },
    e.stepId ? "[" + e.stepId + "] " : "", e.type === "step.finished" ? "done " + (e.data || {}).action : e.type === "log" ? "log " + (e.data || {}).message : e.type === "variable.set" ? (e.data || {}).name + " = " + (e.data || {}).value : e.type));
  box.replaceChildren(
    h("div", { class: "live-head" }, h("span", { class: "status running", text: paused ? "Paused" : L.state === "starting" ? "Starting" : "Running" }),
      h("span", { text: Math.round((L.elapsed || (Date.now() - state.runStarted)) / 1000) + " s" }),
      h("span", { class: "muted", text: L.finished + " of " + L.total + " steps" })),
    bar,
    h("div", { class: "live-now" }, "Now: ", h("strong", { text: L.current ? (spec ? spec.action.name : L.current.action) : "preparing" }),
      L.current ? h("a", { class: "link", onclick: () => selectById(L.current.id), text: "  [" + L.current.id + "]" }) : null),
    h("div", { class: "row-actions" },
      paused ? h("button", { type: "button", class: "primary", onclick: () => liveControl("resume") }, "Resume") : h("button", { type: "button", onclick: () => liveControl("pause") }, "Pause"),
      h("button", { type: "button", class: "danger", onclick: () => liveControl("stop") }, "Stop")),
    h("div", { class: "pane-title", text: "Live log" }), h("div", { class: "live-log" }, recent));
}

function updateMini() {
  const m = $("mini-status");
  let parts;
  if (state.running && state.live) {
    const L = state.live, spec = L.current ? state.specs.get(L.current.action) : null;
    parts = [h("span", { class: "status running", text: L.state === "paused" ? "Paused" : "Running" }),
      h("span", { text: L.finished + "/" + L.total + " steps · " + Math.round((Date.now() - state.runStarted) / 1000) + " s" }),
      h("span", { class: "muted", text: L.current ? (spec ? spec.action.name : L.current.action) : "" }),
      L.state === "paused" ? h("button", { type: "button", class: "link-btn", onclick: () => liveControl("resume") }, "Resume") : h("button", { type: "button", class: "link-btn", onclick: () => liveControl("pause") }, "Pause"),
      h("button", { type: "button", class: "link-btn", onclick: () => liveControl("stop") }, "Stop")];
  } else if (state.running) parts = [h("span", { class: "status running", text: "Running" }), h("span", { text: Math.round((Date.now() - state.runStarted) / 1000) + " s" })];
  else if (state.lastRun) { const r = state.lastRun.result; parts = [h("span", { class: "status " + r.status, text: r.status }), h("span", { class: "muted", text: r.durationMs + " ms, " + r.stepsExecuted + " steps" })]; }
  else parts = [h("span", { class: "muted", text: "No run yet" })];
  parts.push(h("button", { type: "button", class: "link-btn", onclick: () => setDock("bottom") }, "Expand"));
  m.replaceChildren(...parts);
}

function renderVars() {
  const ev = (state.lastRun && state.lastRun.events) || [];
  const last = new Map();
  for (const e of ev) if (e.type === "variable.set" && e.data) last.set(e.data.name, e.data.value);
  const outputs = state.lastRun ? state.lastRun.result.outputs || {} : {};
  const rows = [...last.entries()].map(([k, v]) => h("tr", {}, h("td", { text: k }), h("td", {}, h("code", { text: String(v) }))));
  $("ctab-vars").replaceChildren(
    rows.length ? h("table", { class: "grid" }, h("thead", {}, h("tr", {}, h("th", { text: "Variable" }), h("th", { text: "Last value" }))), h("tbody", {}, rows))
      : h("div", { class: "empty", text: "Run the agent to see variable values. Credentials always show as ****." }),
    Object.keys(outputs).length ? h("div", {}, h("div", { class: "side-head", text: "Outputs" }), h("pre", { text: JSON.stringify(outputs, null, 2) })) : null);
}

function renderRunResult() {
  const box = $("run-result");
  if (!box) return;
  if (!state.lastRun) { box.replaceChildren(h("div", { class: "empty", text: "Run the agent to see its result here. Step-by-step details appear in the Run log tab." })); return; }
  const r = state.lastRun.result;
  const parts = [h("div", {}, h("span", { class: "status " + r.status, text: r.status }), "  ", h("span", { class: "muted", text: r.durationMs + " ms, " + r.stepsExecuted + " steps" }))];
  if (r.error) {
    const e = r.error;
    parts.push(h("div", { class: "error-box" }, h("span", { class: "code", text: e.code }), " ", e.message,
      e.stepId ? h("div", {}, "At step ", h("a", { class: "link", onclick: () => selectById(e.stepId), text: e.stepId })) : null,
      e.suggestedFix ? h("div", { class: "fix", text: "Fix: " + e.suggestedFix }) : null,
      e.details && e.details.problems ? h("div", { class: "help", text: "See the Problems tab for all validation problems." }) : null));
  }
  parts.push(h("div", { class: "pane-title", text: "Outputs" }), h("pre", { text: JSON.stringify(r.outputs || {}, null, 2) }));
  box.replaceChildren(...parts);
}

function renderEvents() {
  const ev = (state.lastRun && state.lastRun.events) || [];
  const t0 = ev.length ? ev[0].ts : 0;
  const rows = ev.filter((e) => e.type !== "step.started").map((e) => {
    const d = e.data || {};
    let text = e.type;
    if (e.type === "step.finished") text = "done  " + d.action + "  (" + d.durationMs + " ms)";
    else if (e.type === "step.failed") text = "FAILED  " + d.action + "  " + d.code + ": " + d.message;
    else if (e.type === "step.retry") text = "retry " + d.attempt + " after " + d.code;
    else if (e.type === "variable.set") text = d.name + " = " + d.value;
    else if (e.type === "log") text = "log  " + d.message;
    else if (e.type === "run.started") text = "run started  " + d.automation;
    else if (e.type === "run.finished") text = "run " + d.status + "  (" + d.durationMs + " ms)";
    return h("div", { class: "event" + (e.type === "step.failed" ? " failed" : "") },
      h("span", { class: "t", text: "+" + Math.round((e.ts - t0) * 1000) + "ms" }),
      e.stepId ? h("a", { class: "link", onclick: () => selectById(e.stepId), text: "[" + e.stepId + "] " }) : "", text);
  });
  $("ctab-events").replaceChildren(...(rows.length ? rows : [h("div", { class: "empty", text: "No run yet." })]));
}

// ---------- tabs, saving, wiring ----------
function switchTab(name) {
  for (const b of document.querySelectorAll(".tab")) b.classList.toggle("active", b.dataset.tab === name);
  for (const t of ["steps", "settings", "deps", "json"]) $("tab-" + t).hidden = t !== name;
  $("canvas-bar").hidden = name !== "steps";
  if (name === "deps") renderDeps();
  if (name === "json") renderJson();
  if (name === "settings") renderSettings();
}

function switchSide(name, fromRail) {
  if (fromRail && state.side === name && !document.body.classList.contains("no-left")) { setPref("left", false); return; }
  state.side = name;
  setPref("left", true);
  for (const b of document.querySelectorAll(".rail-btn")) b.classList.toggle("active", b.dataset.side === name);
  for (const t of ["explorer", "commands", "variables", "triggers"]) $("side-" + t).hidden = t !== name;
  if (name === "variables") { renderVariables(); loadBuiltins(); }
  if (name === "triggers") loadTriggers();
}

function switchConsole(name) {
  for (const b of document.querySelectorAll(".ctab")) b.classList.toggle("active", b.dataset.ctab === name);
  for (const t of ["run", "problems", "events", "vars"]) $("ctab-" + t).hidden = t !== name;
}

// ---------- layout preferences ----------
const PREFS_KEY = "ooa.studio.prefs";
function prefs() { try { return JSON.parse(localStorage.getItem(PREFS_KEY)) || {}; } catch (_) { return {}; } }
function savePrefs(p) { try { localStorage.setItem(PREFS_KEY, JSON.stringify(p)); } catch (_) { /* storage unavailable */ } }
function setPref(key, value) {
  const p = prefs(); p[key] = value; savePrefs(p);
  applyPrefs();
}
function applyPrefs() {
  const p = Object.assign({ left: true, right: true, dock: true, theme: "brand", density: "comfortable", dockPos: "bottom" }, prefs());
  document.body.classList.toggle("no-left", !p.left);
  document.body.classList.toggle("no-right", !p.right);
  document.body.classList.toggle("no-dock", !p.dock);
  document.body.classList.toggle("compact", p.density === "compact");
  document.body.dataset.theme = p.theme;
  for (const pos of ["bottom", "right", "float", "mini"]) document.body.classList.toggle("dock-" + pos, p.dockPos === pos);
  $("tg-left").setAttribute("aria-pressed", String(p.left));
  $("tg-right").setAttribute("aria-pressed", String(p.right));
  $("tg-dock").setAttribute("aria-pressed", String(p.dock));
  $("sel-theme").value = p.theme; $("sel-density").value = p.density; $("sel-dock").value = p.dockPos;
  state.canvasMode = p.canvasMode === "flow" ? "flow" : "list";
  for (const b of document.querySelectorAll("[data-canvas-mode]")) b.setAttribute("aria-pressed", String(b.dataset.canvasMode === state.canvasMode));
  if (state.flow) renderSteps();
  const dock = $("dock");
  const target = p.dockPos === "right" ? $("workbench") : $("center");
  if (dock.parentElement !== target) target.appendChild(dock);
  if (p.dockPos !== "float") { dock.style.left = ""; dock.style.top = ""; dock.style.right = ""; dock.style.bottom = ""; }
  updateMini();
}
function setDock(pos) { const p = prefs(); p.dockPos = pos; p.dock = true; savePrefs(p); applyPrefs(); }

function makeDockDraggable() {
  const head = $("dock-head"), dock = $("dock");
  let sx = 0, sy = 0, ox = 0, oy = 0, dragging = false;
  head.addEventListener("pointerdown", (e) => {
    if (!document.body.classList.contains("dock-float") || e.target.closest("button,select,label")) return;
    const r = dock.getBoundingClientRect();
    dragging = true; sx = e.clientX; sy = e.clientY; ox = r.left; oy = r.top;
    head.setPointerCapture(e.pointerId);
  });
  head.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    dock.style.right = "auto"; dock.style.bottom = "auto";
    dock.style.left = Math.max(0, ox + e.clientX - sx) + "px"; dock.style.top = Math.max(0, oy + e.clientY - sy) + "px";
  });
  head.addEventListener("pointerup", () => { dragging = false; });
}

// ---------- recorder ----------
async function recordSteps() {
  if (!state.flow) { toast("Open or create an agent first.", true); return; }
  const url = prompt("Start recording at which web address?", "https://");
  if (!url || url === "https://") return;
  const btn = $("btn-record");
  btn.classList.add("recording"); btn.disabled = true;
  toast("Recording in the browser window. Press 'Stop recording' there (or close the browser) when you are done.");
  try {
    const res = await api("POST", "record", { url, browser: "edge" });
    const at = insertionPoint();
    const steps = res.steps || [];
    steps.forEach((st, k) => { st.id = newStepId() + (k ? "" : ""); at.list.splice(at.index + k, 0, st); });
    if (JSON.stringify(steps).includes('"$password"') && !state.flow.variables.some((v) => v.name === "password"))
      state.flow.variables.push({ name: "password", type: "Credential", direction: "input", description: "Password used by the recorded steps (from the vault)." });
    changed(); renderVariables();
    toast("Inserted " + steps.length + " recorded steps.");
  } catch (e) { toast(e.message, true); }
  finally { btn.classList.remove("recording"); btn.disabled = false; }
}

// ---------- triggers ----------
async function loadTriggers() {
  try { const r = await api("GET", "triggers"); state.triggers = r.triggers; state.triggerLog = r.log; renderTriggers(); }
  catch (e) { toast(e.message, true); }
}

function botPaths(node = state.tree, out = []) {
  if (!node) return out;
  if (node.type === "bot") out.push(node.path);
  for (const c of node.children || []) botPaths(c, out);
  return out;
}

async function saveTriggers(list, msg) {
  try { const r = await api("PUT", "triggers", list); state.triggers = r.triggers; renderTriggers(); if (msg) toast(msg); return true; }
  catch (e) { toast(e.message, true); return false; }
}

function describeTrigger(t) {
  if (t.type === "file") return "When a file matching " + t.pattern + " arrives in " + t.folder;
  if (t.type === "schedule") return t.every_minutes ? "Every " + t.every_minutes + " minutes" : "Daily at " + t.daily_at;
  return "Local webhook: POST /api/hooks/" + t.id + " with header X-OOA-Secret";
}

function renderTriggers() {
  const box = $("side-triggers");
  const items = state.triggers.map((t, i) => h("div", { class: "trig" },
    h("div", { class: "trig-top" },
      h("strong", { text: t.name || t.id }), h("span", { class: "badge", text: t.type }),
      h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!t.enabled, "aria-label": "Enabled",
        onchange: (e) => { const l = state.triggers.map((x) => Object.assign({}, x)); l[i].enabled = e.target.checked; saveTriggers(l, e.target.checked ? "Trigger enabled" : "Trigger disabled"); } }), "On")),
    h("div", { class: "meta", text: describeTrigger(t) }),
    h("div", { class: "meta", text: "Runs " + t.bot }),
    h("div", { class: "row-actions" },
      h("button", { type: "button", onclick: async () => { try { const r = await api("POST", "triggers/" + t.id + "/run"); toast("Test run " + r.result.status, r.result.status !== "success"); loadTriggers(); } catch (e) { toast(e.message, true); } } }, "Run now"),
      h("button", { type: "button", class: "danger", onclick: () => { if (confirm("Delete trigger '" + (t.name || t.id) + "'?")) saveTriggers(state.triggers.filter((_, k) => k !== i), "Trigger deleted"); } }, "Delete"))));
  const bots = botPaths();
  const f = { name: h("input", { type: "text", placeholder: "e.g. New invoices" }),
    bot: h("select", {}, bots.map((b) => h("option", { value: b, text: b.replace(/\.flow\.json$/, "") }))),
    type: h("select", {}, h("option", { value: "file", text: "File arrives in a folder" }), h("option", { value: "schedule", text: "Schedule" }), h("option", { value: "webhook", text: "Local webhook" })),
    folder: h("input", { type: "text", placeholder: "Folder, e.g. inbox or C:\\Invoices" }), pattern: h("input", { type: "text", value: "*.pdf" }),
    every: h("input", { type: "number", min: "1", placeholder: "Every N minutes" }), daily: h("input", { type: "text", placeholder: "or daily at HH:mm" }) };
  if (state.file) f.bot.value = state.file;
  const fileBox = h("div", { class: "form-box" }, h("label", {}, "Folder", f.folder), h("label", {}, "File pattern", f.pattern));
  const schedBox = h("div", { class: "form-box", hidden: true }, h("label", {}, "Every (minutes)", f.every), h("label", {}, "Or daily at", f.daily));
  f.type.addEventListener("change", () => { fileBox.hidden = f.type.value !== "file"; schedBox.hidden = f.type.value !== "schedule"; });
  const add = h("button", { type: "button", class: "primary", onclick: async () => {
    const id = "t" + Date.now().toString(36);
    const t = { id, name: f.name.value || id, bot: f.bot.value, type: f.type.value, enabled: true };
    if (t.type === "file") { t.folder = f.folder.value; t.pattern = f.pattern.value || "*"; }
    if (t.type === "schedule") { if (f.every.value) t.every_minutes = parseInt(f.every.value, 10); else t.daily_at = f.daily.value; }
    if (t.type === "webhook") { const a = new Uint8Array(18); crypto.getRandomValues(a); t.secret = Array.from(a, (x) => x.toString(16).padStart(2, "0")).join(""); }
    if (await saveTriggers(state.triggers.concat([t]), "Trigger added") && t.type === "webhook") prompt("Webhook secret (copy it now):", t.secret);
  } }, "Add trigger");
  const logRows = state.triggerLog.slice(0, 20).map((l) => h("div", { class: "trig-log" },
    h("span", { class: "muted", text: (l.ts || "").replace("T", " ") + "  " }), (l.name || l.trigger || "") + ": " + l.status + (l.detail ? " (" + l.detail + ")" : "") + "  \u00B7 " + (l.reason || "")));
  box.replaceChildren(
    h("div", { class: "side-head" }, h("span", { text: "Triggers" }), h("button", { type: "button", class: "link-btn", onclick: loadTriggers }, "Refresh")),
    h("div", { class: "side-scroll" },
      items.length ? h("div", { class: "row-actions" }, items) : h("p", { class: "desc", text: "No triggers yet. Triggers start agents automatically while Agent Studio is running." }),
      h("div", { class: "side-head", text: "New trigger" }),
      h("div", { class: "form-box" }, h("label", {}, "Name", f.name), h("label", {}, "Agent", f.bot), h("label", {}, "When", f.type)),
      fileBox, schedBox, h("div", { class: "row-actions" }, add),
      h("div", { class: "side-head", text: "Recent activity" }),
      logRows.length ? h("div", {}, logRows) : h("p", { class: "desc", text: "Nothing has fired yet." })));
}

async function save() {
  if (state.item) return saveItem();
  if (!state.flow) return;
  try {
    const res = await api("PUT", "bot?path=" + encodeURIComponent(state.file), state.flow);
    setDirty(false);
    state.problems = res.problems;
    renderProblems(); renderSteps();
    await loadTree();
    toast("Saved " + res.saved + (res.problems.length ? " (" + res.problems.length + " problem(s) to fix)" : ""), res.problems.length > 0);
  } catch (e) { toast(e.message, true); }
}

// ---------- Control Room (public repository, check-in and check-out) ----------
async function crRefresh() {
  try { state.cr = await api("GET", "cr/status"); } catch (_) { state.cr = { connected: false }; }
  renderCr();
}

function renderCr() {
  const cr = state.cr || { connected: false };
  $("cr-state").textContent = cr.connected ? "(" + cr.user.username + ")" : "(not connected)";
  const body = $("cr-body");
  if (!cr.connected && cr.sameServer) {
    body.replaceChildren(h("div", { class: "cr-form" },
      h("div", { class: "hint", text: "Agent Studio and Command Center run together here. Sign in to Command Center in this browser and Agent Studio uses that sign-in for check-in and check-out." }),
      h("a", { class: "button-link", href: "../", target: "ooa-control-room" }, "Open Command Center to sign in"),
      h("button", { type: "button", onclick: crRefresh }, "I have signed in")));
    return;
  }
  if (!cr.connected) {
    const url = h("input", { type: "text", value: cr.url || "http://localhost:8720", "aria-label": "Command Center address" });
    const user = h("input", { type: "text", autocomplete: "username", "aria-label": "User name" });
    const pass = h("input", { type: "password", autocomplete: "current-password", "aria-label": "Password" });
    const go = h("button", { type: "button", class: "primary", onclick: async () => {
      try { state.cr = await api("POST", "cr/connect", { url: url.value, username: user.value, password: pass.value }); pass.value = ""; toast("Connected to Command Center"); renderCr(); crLoadRepo(); }
      catch (e) { toast(e.message, true); }
    } }, "Connect");
    pass.addEventListener("keydown", (e) => { if (e.key === "Enter") go.click(); });
    body.replaceChildren(h("div", { class: "cr-form" }, h("label", {}, "Address", url), h("label", {}, "User name", user), h("label", {}, "Password", pass), go,
      h("div", { class: "hint", text: "Your workspace above is private. Connect to share agents through the public agent repository with check-in and check-out." })));
    return;
  }
  const q = h("input", { type: "search", placeholder: "Search the agent repository", "aria-label": "Search the agent repository" });
  q.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(() => crLoadRepo(q.value), 250); });
  body.replaceChildren(h("div", { class: "hint", text: cr.url }), q, h("div", { id: "cr-list" }),
    h("div", { class: "row-actions" }, h("button", { type: "button", onclick: () => crLoadRepo(q.value) }, "Refresh"),
      cr.sameServer ? h("span", { class: "hint", text: "Signed in through Command Center" })
        : h("button", { type: "button", onclick: async () => { await api("POST", "cr/disconnect"); state.cr = { connected: false }; renderCr(); } }, "Disconnect")));
  crLoadRepo();
}

async function crLoadRepo(q) {
  const list = $("cr-list");
  if (!list) return;
  try {
    const r = await api("GET", "cr/repo?q=" + encodeURIComponent(q || ""));
    const me = state.cr.user.username;
    const localOf = (path) => Object.keys(r.mapping || {}).find((k) => r.mapping[k] === path);
    list.replaceChildren(...(r.bots.length ? r.bots.map((b) => {
      const mine = b.locked_by === me, local = localOf(b.path);
      return h("div", { class: "cr-bot" },
        h("div", { class: "top" }, h("span", { class: "icon bot" }), h("strong", { text: b.path.replace(/\.flow\.json$/, ""), title: b.path }), h("span", { class: "muted", text: "v" + b.latest_version })),
        b.locked_by ? h("span", { class: "lock", text: mine ? "Checked out by you" : "Checked out by " + b.locked_by }) : null,
        h("div", { class: "acts" },
          !b.locked_by || mine ? h("button", { type: "button", onclick: () => crCheckout(b.path) }, mine ? "Open my copy" : "Agent check-out") : null,
          mine ? h("button", { type: "button", onclick: async () => { if (!confirm("Undo check-out of " + b.path + "? Your private copy stays in the workspace.")) return;
            try { await api("POST", "cr/undo-checkout", { path: b.path }); toast("Agent check-out undone"); crLoadRepo(q); } catch (e) { toast(e.message, true); } } }, "Undo check-out") : null,
          h("button", { type: "button", onclick: () => crVersions(b) }, "Versions")),
        local ? h("div", { class: "hint", text: "Private copy: " + local }) : null);
    }) : [h("div", { class: "empty", text: "The agent repository is empty. Open an agent and press Agent check-in." })]));
  } catch (e) { list.replaceChildren(h("div", { class: "empty", text: e.message })); }
}

async function crCheckout(path) {
  if (state.dirty && !confirm("You have unsaved changes in the open agent. Continue?")) return;
  try {
    const r = await api("POST", "cr/checkout", { path });
    await loadTree();
    state.dirty = false;
    await openBot(r.local);
    toast("Checked out v" + r.version + " into My Workspace: " + r.local);
    crLoadRepo();
  } catch (e) { toast(e.message, true); }
}

async function crCheckin() {
  if (!state.flow || !state.file) { toast("Open the agent you want to check in.", true); return; }
  if (!state.cr || !state.cr.connected) { toast("Connect to Command Center first (bottom of the Explorer).", true); return; }
  if (state.dirty) await save();
  const comment = prompt("What changed in this version? (shown in the version history)", "");
  if (comment === null) return;
  try {
    const r = await api("POST", "cr/checkin", { local: state.file, comment });
    toast("Checked in " + r.path + " as version " + r.version);
    crLoadRepo();
  } catch (e) {
    toast(e.message, true);
    if (e.problems || (e.message || "").includes("validation problem")) { switchConsole("problems"); validate(false); }
  }
}

async function crVersions(b) {
  try {
    const r = await api("GET", "cr/versions?path=" + encodeURIComponent(b.path));
    alert("Versions of " + b.path + "\n\n" + r.versions.map((v) => "v" + v.version + (v.version === b.production_version ? " (production)" : "") + "  " + v.author + "  " + String(v.created).slice(0, 16).replace("T", " ") + (v.comment ? "  " + v.comment : "")).join("\n"));
  } catch (e) { toast(e.message, true); }
}

// ---------- automation items: types, home, create menu, editors ----------
const ITEM_TYPES = {
  folder: { label: "Folder", icon: "folder", color: "#e0a526" }, bot: { label: "Agent", icon: "bot", color: "#6d28d9" },
  api: { label: "Headless API agent", icon: "api", color: "#0d9488" }, form: { label: "Form", icon: "form", color: "#0891b2" },
  process: { label: "Workflow", icon: "process", color: "#7c3aed" }, document: { label: "Document intelligence", icon: "doc", color: "#db2777" },
  skill: { label: "Prompt Skill", icon: "spark", color: "#c026d3" }, script: { label: "Script", icon: "code", color: "#0f766e" },
  file: { label: "File", icon: "file", color: "#64748b" },
};
const EDITABLE_FILE = /\.(json|txt|csv|xml|md|sql|html|ini|ya?ml|py|ps1|vbs|js)$/i;
const SCRIPT_CMD = {
  py: { action: "ooa.script/run_python", name: "Run Python" }, ps1: { action: "ooa.script/run_powershell", name: "Run PowerShell" },
  vbs: { action: "ooa.script/run_vbscript", name: "Run VBScript" }, js: { action: "ooa.script/run_javascript", name: "Run JavaScript" },
};
function itemKind(n) { return n.type === "bot" && n.kind === "ApiTask" ? "api" : n.type; }
function displayName(n) {
  const name = String(n.name || "").split("/").pop();
  if (n.type === "folder" || n.type === "file" || n.type === "script") return name;
  const base = name.replace(/\.(flow|form|process|doc)\.json$/i, "");
  return n.type === "skill" ? base.replace(/\.json$/i, "") : base;
}
function itemTile(n) { const t = ITEM_TYPES[itemKind(n)] || ITEM_TYPES.file; const el = tile(t.icon, t.color, "xs"); el.title = t.label; return el; }
function allItems() { const out = []; const walk = (n) => { if (!n) return; if (n.type !== "folder") out.push(n); for (const c of n.children || []) walk(c); }; walk(state.tree); return out; }
function scriptLang(path) { return (/\.([a-z0-9]+)$/i.exec(path) || [])[1].toLowerCase(); }
function mini(text, label, fn, cls) { return h("button", { type: "button", class: "icon-mini" + (cls ? " " + cls : ""), title: label, "aria-label": label, onclick: (e) => { e.stopPropagation(); fn(); } }, text); }
function ago(iso) {
  if (!iso) return "";
  const s = (Date.now() - Date.parse(iso)) / 1000;
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + " min ago";
  if (s < 86400) return Math.floor(s / 3600) + " h ago";
  if (s < 86400 * 7) return Math.floor(s / 86400) + " days ago";
  return String(iso).slice(0, 10);
}

const CREATE_ITEMS = [
  { id: "btn-new-bot", kind: "bot", label: "Agent", desc: "Automate apps, files, the desktop and the web", run: () => newBotHere("TaskBot") },
  { id: "btn-new-template", kind: "template", label: "Agent from a blueprint", desc: "Start from a ready-made pattern", icon: "template", color: "#2563eb", run: () => openTemplates() },
  { id: "btn-new-api", kind: "api", label: "Headless API agent", desc: "Headless automation, no desktop needed", run: () => newBotHere("ApiTask") },
  { id: "btn-new-form", kind: "form", label: "Form", desc: "Collect information from people", run: () => newItemHere("form") },
  { id: "btn-new-process", kind: "process", label: "Workflow", desc: "Chain agents, forms and approvals", run: () => newItemHere("process") },
  { id: "btn-new-doc", kind: "document", label: "Document intelligence", desc: "Extract fields from documents with AI", run: () => newItemHere("document") },
  { id: "btn-new-skill", kind: "skill", label: "Prompt Skill", desc: "A reusable, reviewed AI prompt", run: () => newItemHere("skill") },
  { id: "btn-new-py", kind: "script", label: "Python script", desc: "Run with the Run Python action", run: () => newItemHere("script-python") },
  { id: "btn-new-ps1", kind: "script", label: "PowerShell script", desc: "Run with the Run PowerShell action", run: () => newItemHere("script-powershell") },
  { id: "btn-new-vbs", kind: "script", label: "VBScript", desc: "Run with the Run VBScript action", run: () => newItemHere("script-vbscript") },
  { id: "btn-new-js", kind: "script", label: "JavaScript", desc: "Run with the Run JavaScript action (Node.js)", run: () => newItemHere("script-javascript") },
];
function createEntry(c, cls) {
  const t = ITEM_TYPES[c.kind] || { icon: c.icon, color: c.color };
  return h("button", { type: "button", id: cls === "menu-item" ? c.id : null, class: cls, role: cls === "menu-item" ? "menuitem" : null,
    onclick: () => { closeCreateMenu(); c.run(); } }, tile(c.icon || t.icon, c.color || t.color, cls === "menu-item" ? "sm" : "lg"),
    h("span", { class: "ci-text" }, h("strong", { text: c.label }), h("span", { class: "ci-desc", text: c.desc })));
}
function buildCreateMenu() {
  $("create-pop").replaceChildren(...CREATE_ITEMS.map((c) => createEntry(c, "menu-item")), h("hr"),
    h("button", { type: "button", class: "menu-item", role: "menuitem", onclick: () => { closeCreateMenu(); newFolderHere(); } }, tile("folder", ITEM_TYPES.folder.color, "sm"),
      h("span", { class: "ci-text" }, h("strong", { text: "Folder" }), h("span", { class: "ci-desc", text: "Organise your items" }))));
}
function toggleCreateMenu() { const p = $("create-pop"); p.hidden = !p.hidden; $("btn-create").setAttribute("aria-expanded", String(!p.hidden)); }
function closeCreateMenu() { const p = $("create-pop"); if (p && !p.hidden) { p.hidden = true; $("btn-create").setAttribute("aria-expanded", "false"); } }

const NEW_LABELS = { form: "Form", process: "Workflow", document: "Document intelligence", skill: "Prompt Skill", "script-python": "Python script",
  "script-powershell": "PowerShell script", "script-vbscript": "VBScript", "script-javascript": "JavaScript file" };
const NEW_NAMES = { form: "New form", process: "New workflow", document: "New document type", skill: "new_skill", "script-python": "new_script",
  "script-powershell": "new_script", "script-vbscript": "new_script", "script-javascript": "new_script" };
async function newItemHere(type) {
  const label = NEW_LABELS[type] || "item";
  const name = prompt("Name of the new " + label.toLowerCase() + ":", NEW_NAMES[type] || "New item");
  if (!name) return;
  if (state.dirty && !confirm("Discard unsaved changes?")) return;
  const path = await fileOp("item", { parent: currentFolder(), name, type }, (type === "skill" ? "Prompt Skill created in the ai-skills folder" : label + " created"));
  if (path) { setDirty(false); expandTo(path); state.treeSel = path; renderTree(); await openItem(path); }
}

// ---- home: every item in the workspace ----
const HOME_FILTERS = [["all", "All"], ["bot", "Agents"], ["api", "Headless API agents"], ["form", "Forms"], ["process", "Workflows"], ["document", "Documents"], ["skill", "Prompt Skills"], ["script", "Scripts"], ["file", "Files"]];
function renderHome() {
  const box = $("welcome");
  const items = allItems();
  const counts = {};
  for (const it of items) { const k = itemKind(it); counts[k] = (counts[k] || 0) + 1; }
  const f = state.homeFilter || "all";
  const search = h("input", { type: "search", placeholder: "Search by name or folder", value: state.homeQuery || "", "aria-label": "Search items" });
  const sort = h("select", { "aria-label": "Sort", onchange: (e) => { state.homeSort = e.target.value; rows(); } },
    h("option", { value: "modified", text: "Recently changed" }), h("option", { value: "name", text: "Name" }), h("option", { value: "type", text: "Type" }));
  sort.value = state.homeSort || "modified";
  const tableBox = h("div", { class: "home-table" });
  search.addEventListener("input", () => { state.homeQuery = search.value; rows(); });
  const chips = h("div", { class: "chips", role: "tablist", "aria-label": "Filter by type" }, HOME_FILTERS.filter(([k]) => k === "all" || counts[k]).map(([k, label]) => {
    const t = ITEM_TYPES[k];
    const c = h("button", { type: "button", class: "chip", role: "tab", "aria-selected": String(f === k), onclick: () => { state.homeFilter = k; renderHome(); } },
      t ? tile(t.icon, t.color, "xs") : null, label, h("span", { class: "n", text: k === "all" ? items.length : counts[k] }));
    if (t) c.style.setProperty("--c", t.color);
    return c;
  }));
  function rows() {
    const q = (state.homeQuery || "").toLowerCase(), by = state.homeSort || "modified";
    const list = items.filter((it) => (f === "all" || itemKind(it) === f) && (!q || it.path.toLowerCase().includes(q)));
    list.sort(by === "name" ? (a, b) => displayName(a).localeCompare(displayName(b))
      : by === "type" ? (a, b) => (ITEM_TYPES[itemKind(a)].label + displayName(a)).localeCompare(ITEM_TYPES[itemKind(b)].label + displayName(b))
        : (a, b) => String(b.modified || "").localeCompare(String(a.modified || "")));
    if (!list.length) { tableBox.replaceChildren(h("div", { class: "canvas-empty", text: items.length ? "Nothing matches." : "Your workspace is empty. Create your first item above." })); return; }
    tableBox.replaceChildren(h("table", { class: "grid list" },
      h("thead", {}, h("tr", {}, ["Name", "Type", "Folder", "Changed", "Size"].map((t) => h("th", { text: t })))),
      h("tbody", {}, list.slice(0, 500).map((it) => {
        const open = () => { state.treeSel = it.path; expandTo(it.path); onNodeClick(it); };
        return h("tr", { class: "clickable", tabindex: "0", onclick: open, onkeydown: (e) => { if (e.key === "Enter") open(); } },
          h("td", {}, h("span", { class: "name-cell" }, itemTile(it), h("strong", { text: displayName(it) }))),
          h("td", { text: ITEM_TYPES[itemKind(it)].label }), h("td", { class: "muted", text: parentOf(it.path) || "\u2014" }),
          h("td", { class: "muted", text: ago(it.modified) }), h("td", { class: "muted", text: fmtSize(it.size) }));
      }))));
  }
  box.replaceChildren(h("div", { class: "home" },
    h("div", { class: "home-head" }, h("div", {}, h("h1", { text: "Automation" }),
      h("p", { class: "muted", text: "Everything in My Workspace: agents, forms, workflows, document intelligence items, Prompt Skills and scripts. Check agents in to Command Center to share and run them on other runners." }))),
    h("div", { class: "pane-title", text: "Create" }),
    h("div", { class: "create-grid" }, CREATE_ITEMS.filter((c) => !["btn-new-ps1", "btn-new-vbs", "btn-new-js"].includes(c.id)).map((c) => createEntry(c, "create-card"))),
    h("div", { class: "home-tools" }, chips, h("span", { class: "spacer" }), search, sort),
    tableBox));
  rows();
}

// ---- opening and saving items ----
async function openItem(path) {
  if (state.dirty && path !== state.file && !confirm("Discard unsaved changes?")) return;
  try {
    const r = await api("GET", "item?path=" + encodeURIComponent(path));
    let data = null;
    if (["form", "process", "document", "skill"].includes(r.type)) { try { const d = JSON.parse(r.text); if (d && typeof d === "object" && !Array.isArray(d)) data = d; } catch (_) { /* opens as text */ } }
    state.item = { path, type: r.type, text: r.text, data, tab: data ? "design" : "code", sel: null };
    state.flow = null; state.file = path; state.selected = null; state.multi = new Set(); state.problems = []; state.lastRun = null;
    setDirty(false);
    if (state.side !== "explorer" && state.side !== "triggers") switchSide("explorer");
    showView("item");
    renderItemEditor(); renderTree(); renderProps(); renderVariables(); renderProblems(); renderRunPanel();
  } catch (e) { toast(e.message, true); }
}

function touchItem() { const it = state.item; if (it && it.data) it.text = JSON.stringify(it.data, null, 2) + "\n"; setDirty(true); }

/** Checks a workflow or form in to the Command Center, where workflows run and forms become human tasks. */
async function publishItem(it) {
  if (!state.cr || !state.cr.connected) { toast("Connect to Command Center first (bottom of the Explorer).", true); return; }
  await saveItem();
  const comment = prompt("What changed in this version?", "");
  if (comment === null) return;
  try { const r = await api("POST", "cr/checkin", { local: it.path, comment }); toast("Published " + r.path + " as version " + r.version); if (typeof crLoadRepo === "function") crLoadRepo(); }
  catch (e) { toast(e.message, true); }
}

async function saveItem() {
  const it = state.item;
  if (!it) return;
  if (it.data && it.tab !== "code") it.text = JSON.stringify(it.data, null, 2) + "\n";
  try {
    await api("PUT", "item?path=" + encodeURIComponent(it.path), { text: it.text });
    setDirty(false);
    await loadTree();
    toast("Saved " + it.path);
  } catch (e) { toast(e.message, true); }
}

function renderItemEditor() {
  const it = state.item, box = $("item-editor");
  if (!it) return;
  const meta = ITEM_TYPES[it.type] || ITEM_TYPES.file;
  const designer = ["form", "process", "document", "skill"].includes(it.type);
  const tabs = designer ? [["design", "Design"], ...(it.type === "form" ? [["preview", "Preview"]] : []), ["code", "JSON"]] : [];
  const head = h("div", { class: "item-head" }, tile(meta.icon, meta.color, "lg"),
    h("div", { class: "item-title" }, h("h2", { text: displayName({ name: it.path, type: it.type }) }), h("div", { class: "desc", text: meta.label + " \u00B7 " + it.path })),
    h("span", { class: "spacer" }),
    ["form", "process"].includes(it.type) ? h("button", { type: "button", class: "btn", title: "Check in to the Command Center as the live production version", onclick: () => publishItem(it) }, "Publish to Command Center") : null,
    tabs.length ? h("div", { class: "seg item-tabs", role: "tablist" }, tabs.map(([k, l]) => h("button", { type: "button", role: "tab", "aria-selected": String(it.tab === k), "data-itab": k,
      onclick: () => {
        if (k !== "code" && it.tab === "code") { try { const d = JSON.parse(it.text); if (!d || typeof d !== "object" || Array.isArray(d)) throw new Error("not an object"); it.data = d; } catch (e) { toast("Fix the JSON first: " + e.message, true); return; } }
        if (k === "code" && it.data) it.text = JSON.stringify(it.data, null, 2) + "\n";
        it.tab = k; renderItemEditor();
      } }, l))) : null);
  head.style.setProperty("--c", meta.color);
  let body;
  if (!designer || it.tab === "code" || !it.data) body = codeEditor(it, it.type === "script");
  else if (it.type === "form") body = it.tab === "preview" ? formPreview(it.data) : formDesigner(it);
  else if (it.type === "process") body = processDesigner(it);
  else if (it.type === "document") body = documentDesigner(it);
  else body = skillDesigner(it);
  box.replaceChildren(head, body);
}

function codeEditor(it, runnable) {
  const ta = h("textarea", { class: "code", spellcheck: "false", wrap: "off", "aria-label": "Contents of " + it.path });
  ta.value = it.text;
  const gutter = h("pre", { class: "gutter", "aria-hidden": "true" });
  const lines = () => { const n = ta.value.split("\n").length; let t = ""; for (let i = 1; i <= n; i++) t += i + "\n"; gutter.textContent = t; };
  ta.addEventListener("input", () => {
    it.text = ta.value;
    if (it.path.toLowerCase().endsWith(".json")) { try { const d = JSON.parse(ta.value); if (d && typeof d === "object" && !Array.isArray(d)) it.data = d; } catch (_) { /* still typing */ } }
    setDirty(true); lines();
  });
  ta.addEventListener("scroll", () => { gutter.scrollTop = ta.scrollTop; });
  ta.addEventListener("keydown", (e) => {
    if (e.key === "Tab" && !e.shiftKey) {
      e.preventDefault();
      const a = ta.selectionStart, b = ta.selectionEnd;
      ta.value = ta.value.slice(0, a) + "    " + ta.value.slice(b);
      ta.selectionStart = ta.selectionEnd = a + 4;
      ta.dispatchEvent(new Event("input"));
    }
  });
  lines();
  const cmd = SCRIPT_CMD[scriptLang(it.path)];
  const bar = runnable && cmd ? h("div", { class: "item-bar" },
    h("button", { type: "button", class: "primary", onclick: runScript }, svg("play"), "Run script"),
    h("button", { type: "button", onclick: () => copyStep({ action: cmd.action, args: { file: it.path } }, cmd.name) }, "Copy as an agent step"),
    h("span", { class: "hint", text: "Agents run this file with the " + cmd.name + " action. Ctrl+S saves." })) : null;
  return h("div", { class: "code-pane" }, bar, h("div", { class: "code-wrap" }, gutter, ta));
}

function copyStep(step, label) {
  state.clipboard = { steps: [Object.assign({ id: "s1" }, step)], variables: [] };
  updateEditButtons();
  toast("Copied a '" + label + "' step. Open an agent and press Ctrl+V (or Paste) to add it.");
}

async function runScript() {
  const it = state.item;
  if (!it || it.type !== "script") return;
  const cmd = SCRIPT_CMD[scriptLang(it.path)];
  if (!cmd || !state.specs.has(cmd.action)) { toast("The Scripts toolkit cannot run this file type.", true); return; }
  if (state.dirty) await saveItem();
  const flow = { $schema: SCHEMA, kind: "TaskBot", id: "script_test", name: "Script test",
    variables: [{ name: "output", type: "String", direction: "output" }, { name: "error_output", type: "String", direction: "output" },
      { name: "exit_code", type: "Number", direction: "output" }, { name: "json", type: "Any", direction: "output" }],
    steps: [{ id: "run", action: cmd.action, args: { file: it.path, fail_on_error: false }, out: { output: "output", error_output: "error_output", exit_code: "exit_code", json: "json" } }] };
  renderRunPanel();
  await runFlow(flow, {});
}

// ---- form designer ----
const FORM_FIELDS = {
  text: { label: "Text", icon: "text" }, multiline: { label: "Multi-line text", icon: "note" }, number: { label: "Number", icon: "hash" },
  email: { label: "Email", icon: "mail" }, date: { label: "Date", icon: "calendar" }, dropdown: { label: "Drop-down", icon: "list" },
  radio: { label: "Choice", icon: "list" }, checkbox: { label: "Checkbox", icon: "toggle" }, file: { label: "File upload", icon: "file" },
  heading: { label: "Heading", icon: "text" }, paragraph: { label: "Text block", icon: "comment" },
};
function uniqueKey(list, base, key = "id") { let n = 1, k = base; while (list.some((x) => x[key] === k)) k = base + "_" + ++n; return k; }
function setOrDelete(obj, k, v) { if (v === undefined || v === "" || v === false || (Array.isArray(v) && !v.length)) delete obj[k]; else obj[k] = v; }

function formDesigner(it) {
  const d = it.data;
  if (!Array.isArray(d.fields)) d.fields = [];
  const wrap = h("div", { class: "designer" });
  const touch = () => { touchItem(); render(); };
  const move = (i, dl) => { const j = i + dl; if (j < 0 || j >= d.fields.length) return; [d.fields[i], d.fields[j]] = [d.fields[j], d.fields[i]]; it.sel = j; touch(); };
  function fieldProps(f) {
    const set = (k, v) => { setOrDelete(f, k, v); touch(); };
    const isInput = !["heading", "paragraph"].includes(f.type);
    const idIn = h("input", { type: "text", value: f.id || "", onchange: (e) => {
      const v = e.target.value.trim();
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(v) || d.fields.some((x) => x !== f && x.id === v)) { toast("Field ids are unique and use letters, digits and _.", true); e.target.value = f.id; return; }
      f.id = v; touch(); } });
    const typeSel = h("select", { onchange: (e) => set("type", e.target.value) }, Object.entries(FORM_FIELDS).map(([k, m]) => h("option", { value: k, text: m.label })));
    typeSel.value = f.type;
    const parts = [h("div", { class: "pane-title", text: "Field settings" }),
      field("Label", h("input", { type: "text", value: f.label || "", onchange: (e) => set("label", e.target.value) })),
      field("Type", typeSel)];
    if (isInput) {
      parts.push(field("Field id", idIn, "Agents and workflows read the answer as form." + (f.id || "id") + "."),
        h("label", { class: "check" }, h("input", { type: "checkbox", checked: !!f.required, onchange: (e) => set("required", e.target.checked) }), "Required"));
      if (["text", "multiline", "number", "email"].includes(f.type)) parts.push(field("Placeholder", h("input", { type: "text", value: f.placeholder || "", onchange: (e) => set("placeholder", e.target.value) })));
      if (["dropdown", "radio"].includes(f.type)) parts.push(field("Options (one per line)", h("textarea", { rows: 4, value: (f.options || []).join("\n"), onchange: (e) => set("options", e.target.value.split("\n").map((x) => x.trim()).filter(Boolean)) })));
      if (f.type === "number") parts.push(h("div", { class: "grid2" },
        field("Minimum", h("input", { type: "number", value: f.min ?? "", onchange: (e) => set("min", e.target.value === "" ? undefined : Number(e.target.value)) })),
        field("Maximum", h("input", { type: "number", value: f.max ?? "", onchange: (e) => set("max", e.target.value === "" ? undefined : Number(e.target.value)) }))));
      if (f.type !== "file") parts.push(field("Default value", h("input", { type: "text", value: f.default ?? "", onchange: (e) => set("default", e.target.value) })));
      parts.push(field("Help text", h("input", { type: "text", value: f.help || "", onchange: (e) => set("help", e.target.value) })));
    } else if (f.type === "paragraph") parts.push(field("Text", h("textarea", { rows: 4, value: f.text || "", onchange: (e) => set("text", e.target.value) })));
    parts.push(h("div", { class: "row-actions" }, h("button", { type: "button", class: "link-btn", onclick: () => { it.sel = null; render(); } }, "\u2190 Form settings")));
    return parts;
  }
  function formProps() {
    const set = (k, v) => { setOrDelete(d, k, v); touch(); };
    return [h("div", { class: "pane-title", text: "Form settings" }),
      field("Form name", h("input", { type: "text", value: d.name || "", onchange: (e) => set("name", e.target.value) })),
      field("Description", h("textarea", { rows: 3, value: d.description || "", onchange: (e) => set("description", e.target.value) })),
      field("Submit button", h("input", { type: "text", value: d.submitLabel || "Submit", onchange: (e) => set("submitLabel", e.target.value) })),
      h("p", { class: "help", text: "Answers are returned as a Dictionary keyed by field id. Select a field on the form to edit it." })];
  }
  function render() {
    const palette = h("div", { class: "palette" }, h("div", { class: "pane-title", text: "Add a field" }),
      Object.entries(FORM_FIELDS).map(([t, m]) => h("button", { type: "button", class: "pal-item", onclick: () => {
        const f = { id: uniqueKey(d.fields, t === "heading" || t === "paragraph" ? t : t + "_field"), type: t, label: m.label };
        if (t === "dropdown" || t === "radio") f.options = ["Option 1", "Option 2"];
        if (t === "paragraph") f.text = "Explain what to fill in.";
        d.fields.push(f); it.sel = d.fields.length - 1; touch();
      } }, tile(m.icon, "#0891b2", "sm"), m.label)));
    const cards = d.fields.map((f, i) => {
      const m = FORM_FIELDS[f.type] || FORM_FIELDS.text;
      return h("div", { class: "fcard" + (it.sel === i ? " selected" : ""), tabindex: "0", onclick: () => { it.sel = i; render(); }, onkeydown: (e) => { if (e.key === "Enter") { it.sel = i; render(); } } },
        tile(m.icon, "#0891b2", "sm"),
        h("div", { class: "fmain" }, h("div", { class: "flabel" }, f.label || f.id, f.required ? h("span", { class: "req", text: " *" }) : null),
          h("div", { class: "fmeta", text: m.label + (["heading", "paragraph"].includes(f.type) ? "" : " \u00B7 " + f.id) })),
        h("span", { class: "facts" }, mini("\u2191", "Move up", () => move(i, -1)), mini("\u2193", "Move down", () => move(i, 1)),
          mini("\u2715", "Remove field", () => { d.fields.splice(i, 1); it.sel = null; touch(); }, "danger")));
    });
    const canvas = h("div", { class: "form-canvas" }, h("div", { class: "form-card" }, h("h3", { text: d.name || "Form" }), d.description ? h("p", { class: "desc", text: d.description }) : null,
      cards.length ? cards : h("div", { class: "canvas-empty", text: "Add fields from the left." }),
      h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", disabled: true }, d.submitLabel || "Submit"))));
    const props = h("div", { class: "designer-props" }, ...(it.sel != null && d.fields[it.sel] ? fieldProps(d.fields[it.sel]) : formProps()));
    wrap.replaceChildren(palette, canvas, props);
  }
  render();
  return wrap;
}

function formPreview(d) {
  const ctl = (f) => {
    const common = { "aria-label": f.label || f.id, placeholder: f.placeholder || "" };
    switch (f.type) {
      case "multiline": return h("textarea", Object.assign({ rows: 3, value: f.default || "" }, common));
      case "number": return h("input", Object.assign({ type: "number", value: f.default ?? "", min: f.min, max: f.max }, common));
      case "email": return h("input", Object.assign({ type: "email", value: f.default || "" }, common));
      case "date": return h("input", Object.assign({ type: "date", value: f.default || "" }, common));
      case "dropdown": return h("select", common, h("option", { value: "", text: "(choose)" }), (f.options || []).map((o) => h("option", { value: o, text: o })));
      case "radio": return h("div", { class: "radios" }, (f.options || []).map((o) => h("label", { class: "check" }, h("input", { type: "radio", name: f.id }), o)));
      case "checkbox": return h("label", { class: "check" }, h("input", { type: "checkbox" }), f.label);
      case "file": return h("input", Object.assign({ type: "file" }, common));
      default: return h("input", Object.assign({ type: "text", value: f.default || "" }, common));
    }
  };
  return h("div", { class: "form-canvas" }, h("form", { class: "form-card preview", onsubmit: (e) => { e.preventDefault(); toast("Preview only: forms are filled in through workflows."); } },
    h("h3", { text: d.name || "Form" }), d.description ? h("p", { class: "desc", text: d.description }) : null,
    (d.fields || []).map((f) => f.type === "heading" ? h("h4", { text: f.label }) : f.type === "paragraph" ? h("p", { text: f.text || "" })
      : h("div", { class: "field" }, f.type === "checkbox" ? null : h("label", {}, f.label || f.id, f.required ? h("span", { class: "req", text: " *" }) : null), ctl(f), f.help ? h("div", { class: "help", text: f.help }) : null)),
    h("div", { class: "row-actions" }, h("button", { type: "submit", class: "primary" }, d.submitLabel || "Submit"))));
}

// ---- process designer ----
const STAGE_TYPES = {
  bot: { label: "Agent task", icon: "bot", color: "#6d28d9", desc: "Run an agent" }, form: { label: "Human task", icon: "form", color: "#0891b2", desc: "Someone fills in a form" },
  approval: { label: "Approval", icon: "shield", color: "#d97706", desc: "Someone approves or rejects" }, decision: { label: "Decision", icon: "branch", color: "#7c3aed", desc: "Choose the next stage" },
  notify: { label: "Notification", icon: "mail", color: "#0284c7", desc: "Send a message" },
};
function processDesigner(it) {
  const d = it.data;
  if (!Array.isArray(d.stages)) d.stages = [];
  const wrap = h("div", { class: "designer" });
  const touch = () => { touchItem(); render(); };
  const items = allItems();
  const bots = items.filter((x) => x.type === "bot"), forms = items.filter((x) => x.type === "form");
  const pathSelect = (list, value, onSet, none) => { const s = h("select", { onchange: (e) => onSet(e.target.value) }, h("option", { value: "", text: none }), list.map((x) => h("option", { value: x.path, text: x.path }))); s.value = value || ""; return s; };
  const targetSelect = (value, onSet) => { const s = h("select", { onchange: (e) => onSet(e.target.value) }, h("option", { value: "", text: "Next stage" }), h("option", { value: "end", text: "End the workflow" }), d.stages.map((x) => h("option", { value: x.id, text: x.name || x.id }))); s.value = value || ""; return s; };
  function stageProps(st) {
    const set = (k, v) => { setOrDelete(st, k, v); touch(); };
    const t = STAGE_TYPES[st.type] || STAGE_TYPES.bot;
    const parts = [h("div", { class: "pane-title", text: t.label }), field("Name", h("input", { type: "text", value: st.name || "", onchange: (e) => set("name", e.target.value) }))];
    if (st.type === "bot") {
      parts.push(field("Agent", pathSelect(bots, st.bot, (v) => set("bot", v), "(choose an agent)")),
        field("Inputs (JSON)", h("textarea", { rows: 3, value: st.inputs ? JSON.stringify(st.inputs, null, 2) : "", placeholder: '{"order_id": "$form.order_id"}',
          onchange: (e) => { const v = parseLoose(e.target.value); if (v !== undefined && (typeof v !== "object" || Array.isArray(v))) { toast("Inputs must be a JSON object.", true); return; } set("inputs", v); } })));
    } else if (st.type === "form") {
      parts.push(field("Form", pathSelect(forms, st.form, (v) => set("form", v), "(choose a form)")),
        field("Assign to", h("input", { type: "text", value: st.assignee || "", placeholder: "role:bot_runner or user:jane", onchange: (e) => set("assignee", e.target.value) })),
        field("Due within (hours)", h("input", { type: "number", min: "1", value: st.due_hours ?? "", onchange: (e) => set("due_hours", e.target.value === "" ? undefined : Number(e.target.value)) })));
    } else if (st.type === "approval") {
      const rej = h("select", { onchange: (e) => set("on_reject", e.target.value) }, h("option", { value: "end", text: "End the workflow" }), h("option", { value: "continue", text: "Continue anyway" }), d.stages.filter((x) => x !== st).map((x) => h("option", { value: x.id, text: "Go to " + (x.name || x.id) })));
      rej.value = st.on_reject || "end";
      parts.push(field("Approver", h("input", { type: "text", value: st.assignee || "", placeholder: "role:admin or user:fin.lead", onchange: (e) => set("assignee", e.target.value) })),
        field("Due within (hours)", h("input", { type: "number", min: "1", value: st.due_hours ?? "", onchange: (e) => set("due_hours", e.target.value === "" ? undefined : Number(e.target.value)) })),
        field("If rejected", rej));
    } else if (st.type === "decision") {
      parts.push(field("Condition", withPicker(h("input", { type: "text", value: st.condition || "", placeholder: "$amount > 1000", onchange: (e) => set("condition", e.target.value) }), "Any")),
        field("If true, go to", targetSelect(st.if_true, (v) => set("if_true", v))), field("If false, go to", targetSelect(st.if_false, (v) => set("if_false", v))));
    } else if (st.type === "notify") {
      parts.push(field("To", h("input", { type: "text", value: st.to || "", placeholder: "user:jane, role:admin or an email address", onchange: (e) => set("to", e.target.value) })),
        field("Message", h("textarea", { rows: 4, value: st.message || "", onchange: (e) => set("message", e.target.value) })));
    }
    parts.push(field("Notes", h("textarea", { rows: 2, value: st.description || "", onchange: (e) => set("description", e.target.value) })),
      h("div", { class: "row-actions" }, h("button", { type: "button", class: "link-btn", onclick: () => { it.sel = null; render(); } }, "\u2190 Workflow settings")));
    return parts;
  }
  function processProps() {
    const set = (k, v) => { setOrDelete(d, k, v); touch(); };
    return [h("div", { class: "pane-title", text: "Workflow settings" }),
      field("Name", h("input", { type: "text", value: d.name || "", onchange: (e) => set("name", e.target.value) })),
      field("Description", h("textarea", { rows: 3, value: d.description || "", onchange: (e) => set("description", e.target.value) })),
      h("p", { class: "help", text: "Select a stage to edit it. Stages run from top to bottom; decisions and approvals can jump to another stage." })];
  }
  function render() {
    const palette = h("div", { class: "palette" }, h("div", { class: "pane-title", text: "Add a stage" }),
      Object.entries(STAGE_TYPES).map(([t, m]) => h("button", { type: "button", class: "pal-item", title: m.desc, onclick: () => {
        d.stages.push({ id: uniqueKey(d.stages, t), type: t, name: m.label + " " + (d.stages.filter((x) => x.type === t).length + 1) });
        it.sel = d.stages.length - 1; touch();
      } }, tile(m.icon, m.color, "sm"), m.label)));
    const nameOf = (id) => id === "end" ? "End" : (d.stages.find((x) => x.id === id) || {}).name || "next stage";
    const flow = [h("div", { class: "pnode start", text: "Start" })];
    d.stages.forEach((st, i) => {
      const m = STAGE_TYPES[st.type] || STAGE_TYPES.bot;
      const detail = st.type === "bot" ? (st.bot || "No agent chosen") : st.type === "form" ? (st.form || "No form chosen") + (st.assignee ? " \u00B7 " + st.assignee : "")
        : st.type === "approval" ? (st.assignee || "No approver") : st.type === "decision" ? (st.condition || "No condition") + "  \u2192 yes: " + nameOf(st.if_true) + ", no: " + nameOf(st.if_false)
          : (st.to || "No recipient");
      flow.push(h("div", { class: "parrow", "aria-hidden": "true" }));
      const card = h("div", { class: "pcard" + (it.sel === i ? " selected" : ""), tabindex: "0", onclick: () => { it.sel = i; render(); }, onkeydown: (e) => { if (e.key === "Enter") { it.sel = i; render(); } } },
        tile(m.icon, m.color, "lg"), h("div", { class: "fmain" }, h("div", { class: "flabel", text: st.name || st.id }), h("div", { class: "fmeta", text: m.label + " \u00B7 " + detail })),
        h("span", { class: "facts" }, mini("\u2191", "Move up", () => { if (i > 0) { [d.stages[i - 1], d.stages[i]] = [d.stages[i], d.stages[i - 1]]; it.sel = i - 1; touch(); } }),
          mini("\u2193", "Move down", () => { if (i < d.stages.length - 1) { [d.stages[i + 1], d.stages[i]] = [d.stages[i], d.stages[i + 1]]; it.sel = i + 1; touch(); } }),
          mini("\u2715", "Remove stage", () => { d.stages.splice(i, 1); it.sel = null; touch(); }, "danger")));
      card.style.setProperty("--c", m.color);
      flow.push(card);
    });
    flow.push(h("div", { class: "parrow", "aria-hidden": "true" }), h("div", { class: "pnode end", text: "End" }));
    const canvas = h("div", { class: "form-canvas" },
      h("div", { class: "note", text: "Publish the workflow and the forms and agents it uses to the Command Center, then start it from One Workflow. People get their steps in the Task inbox." }),
      h("div", { class: "pflow" }, flow), d.stages.length ? null : h("div", { class: "canvas-empty", text: "Add stages from the left: agent tasks, human tasks, approvals, decisions and notifications." }));
    const props = h("div", { class: "designer-props" }, ...(it.sel != null && d.stages[it.sel] ? stageProps(d.stages[it.sel]) : processProps()));
    wrap.replaceChildren(palette, canvas, props);
  }
  render();
  return wrap;
}

// ---- document automation ----
const DOC_TYPES = ["pdf", "txt", "csv", "xml", "json", "md", "html"];
const AI_PROVIDERS = [["openai", "OpenAI"], ["azure_openai", "Azure OpenAI"], ["anthropic", "Anthropic"], ["openai_compatible", "OpenAI-compatible (local or other)"], ["huggingface", "Hugging Face"], ["aws_bedrock_sagemaker", "Amazon SageMaker endpoint"]];
function providerSelect(value, onSet) { const s = h("select", { onchange: (e) => onSet(e.target.value) }, AI_PROVIDERS.map(([v, t]) => h("option", { value: v, text: t }))); s.value = value || "openai"; return s; }
function documentDesigner(it) {
  const d = it.data;
  if (!Array.isArray(d.fields)) d.fields = [];
  if (!Array.isArray(d.accept)) d.accept = ["pdf", "txt"];
  const wrap = h("div", { class: "doc-designer" });
  const set = (k, v) => { setOrDelete(d, k, v); touchItem(); };
  function render() {
    const fieldRows = d.fields.map((f, i) => {
      const type = h("select", { "aria-label": "Type of " + f.name, onchange: (e) => { f.type = e.target.value; touchItem(); } }, ["String", "Number", "DateTime", "Boolean", "List"].map((t) => h("option", { value: t, text: t })));
      type.value = f.type || "String";
      return h("tr", {},
        h("td", {}, h("input", { type: "text", value: f.name || "", "aria-label": "Field name", onchange: (e) => {
          const v = e.target.value.trim();
          if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(v)) { toast("Field names use letters, digits and _.", true); e.target.value = f.name || ""; return; }
          f.name = v; touchItem(); } })),
        h("td", {}, h("input", { type: "text", value: f.description || "", "aria-label": "What to extract", placeholder: "Describe the value for the AI", onchange: (e) => { f.description = e.target.value; touchItem(); } })),
        h("td", {}, type),
        h("td", { class: "ta-center" }, h("input", { type: "checkbox", checked: !!f.required, "aria-label": "Required", onchange: (e) => { f.required = e.target.checked; touchItem(); } })),
        h("td", {}, mini("\u2715", "Remove field", () => { d.fields.splice(i, 1); touchItem(); render(); }, "danger")));
    });
    wrap.replaceChildren(
      h("div", { class: "card-box" }, h("div", { class: "pane-title", text: "Document type" }),
        h("div", { class: "grid2" }, field("Name", h("input", { type: "text", value: d.name || "", onchange: (e) => set("name", e.target.value) })),
          field("AI provider", providerSelect(d.provider, (v) => set("provider", v)))),
        h("div", { class: "grid2" }, field("Description", h("input", { type: "text", value: d.description || "", placeholder: "e.g. Supplier invoices in English", onchange: (e) => set("description", e.target.value) })),
          field("Model", h("input", { type: "text", value: d.model || "", placeholder: "gpt-4o-mini", onchange: (e) => set("model", e.target.value) }))),
        h("div", { class: "field" }, h("label", { text: "Accepted files" }), h("div", { class: "chips" }, DOC_TYPES.map((t) => h("label", { class: "chip check" },
          h("input", { type: "checkbox", checked: d.accept.includes(t), onchange: (e) => { d.accept = e.target.checked ? [...new Set([...d.accept, t])] : d.accept.filter((x) => x !== t); touchItem(); } }), t.toUpperCase()))))),
      h("div", { class: "card-box" }, h("div", { class: "pane-title", text: "Fields to extract" }),
        h("table", { class: "grid list doc-fields" }, h("thead", {}, h("tr", {}, ["Field", "What to extract", "Type", "Required", ""].map((t) => h("th", { text: t })))), h("tbody", {}, fieldRows)),
        d.fields.length ? null : h("div", { class: "empty", text: "No fields yet." }),
        h("div", { class: "row-actions" }, h("button", { type: "button", onclick: () => { d.fields.push({ name: uniqueKey(d.fields, "field", "name"), description: "", type: "String" }); touchItem(); render(); } }, "+ Field"))),
      h("div", { class: "card-box" }, h("div", { class: "pane-title", text: "Use it" }),
        h("p", { class: "help", text: "Create an agent that reads a document (PDF or text), sends the text to the AI provider with these field descriptions, and returns the fields as a Dictionary. Personal data is sent to the provider, so check your AI policy. Scanned images need OCR first." }),
        h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: () => generateExtractionBot(it) }, "Create extraction agent"))));
  }
  render();
  return wrap;
}

async function generateExtractionBot(it) {
  const d = it.data;
  if (!d.fields.length) { toast("Add at least one field first.", true); return; }
  if (state.dirty) await saveItem();
  const fields = {};
  for (const f of d.fields) fields[f.name] = (f.description || f.name) + (f.type && f.type !== "String" ? " (" + f.type + ")" : "") + (f.required ? "" : " (may be missing)");
  const usePdf = d.accept.includes("pdf") && state.specs.has("ooa.pdf/extract_text");
  const read = { id: "s3", action: "ooa.file/read_text", args: { path: "$document" }, out: { text: "text" } };
  const extract = { id: "s4", action: "ooa.genai/extract", args: { text: "$text", fields, model: d.model || "gpt-4o-mini", api_key: "$api_key" }, out: { fields: "fields" } };
  if (d.provider && d.provider !== "openai") extract.args.provider = d.provider;
  const steps = usePdf ? [{ id: "s1", action: "core/if", args: { condition: { op: "endsWith", left: "$document", right: ".pdf" } },
    children: [{ id: "s2", action: "ooa.pdf/extract_text", args: { path: "$document" }, out: { text: "text" } }], else: [read] }, extract] : [read, extract];
  const name = (d.name || "Document") + " extraction";
  const path = await fileOp("bot", { parent: parentOf(it.path), name }, null);
  if (!path) return;
  const created = (await api("GET", "bot?path=" + encodeURIComponent(path))).flow;
  Object.assign(created, { description: "Extracts " + Object.keys(fields).join(", ") + " from a document with AI. Generated from " + it.path + ".",
    variables: [{ name: "document", type: "File", direction: "input", required: true, description: "The document to read (" + d.accept.join(", ") + ")." },
      { name: "api_key", type: "Credential", direction: "input", description: "API key for the AI provider (a vault reference)." },
      { name: "text", type: "String" }, { name: "fields", type: "Dictionary", direction: "output", description: "The extracted fields." }], steps });
  await api("PUT", "bot?path=" + encodeURIComponent(path), created);
  await loadTree();
  setDirty(false);
  expandTo(path); state.treeSel = path;
  await openBot(path);
  toast("Created " + path);
}

// ---- AI Skill ----
function skillDesigner(it) {
  const d = it.data;
  const name = it.path.split("/").pop().replace(/\.json$/i, "");
  const set = (k, v) => { setOrDelete(d, k, v); touchItem(); };
  const holders = h("div", { class: "chips" });
  const prompt = h("textarea", { rows: 8, value: d.prompt || "", "aria-label": "Prompt template", onchange: (e) => { d.prompt = e.target.value; touchItem(); renderHolders(); } });
  const renderHolders = () => {
    const names = [...new Set([...(String(d.prompt || "") + " " + String(d.system || "")).matchAll(/\$\{([A-Za-z_][A-Za-z0-9_.]*)\}/g)].map((m) => m[1]))];
    holders.replaceChildren(...(names.length ? names.map((n) => h("span", { class: "chip mono", text: "${" + n + "}" })) : [h("span", { class: "muted", text: "No placeholders. Add ${name} where a value goes." })]));
    return names;
  };
  const out = h("div", { class: "seg", role: "radiogroup", "aria-label": "Answer format" });
  const renderOut = () => out.replaceChildren(...[["text", "Text"], ["json", "JSON (Dictionary)"]].map(([v, l]) => h("button", { type: "button", role: "radio", "aria-checked": String((d.output || "text") === v), onclick: () => { d.output = v; touchItem(); renderOut(); } }, l)));
  renderOut();
  const names = renderHolders();
  return h("div", { class: "doc-designer" },
    h("div", { class: "card-box" }, h("div", { class: "pane-title", text: "Prompt" }),
      field("Description", h("input", { type: "text", value: d.description || "", placeholder: "What the skill does; shown in the Prompt Skills list", onchange: (e) => set("description", e.target.value) })),
      field("Instructions (system)", h("textarea", { rows: 3, value: d.system || "", onchange: (e) => { set("system", e.target.value); renderHolders(); } })),
      field("Prompt template", prompt, "Values from the agent replace ${name} placeholders."), holders),
    h("div", { class: "card-box" }, h("div", { class: "pane-title", text: "Model" }),
      h("div", { class: "grid2" }, field("Provider", providerSelect(d.provider, (v) => set("provider", v))),
        field("Model", h("input", { type: "text", value: d.model || "", placeholder: "gpt-4o-mini", onchange: (e) => set("model", e.target.value) }))),
      h("div", { class: "grid2" }, field("Temperature", h("input", { type: "number", min: "0", max: "2", step: "0.1", value: d.temperature ?? 0, onchange: (e) => set("temperature", e.target.value === "" ? undefined : Number(e.target.value)) }), "0 is the most predictable."),
        field("Base URL (optional)", h("input", { type: "text", value: d.base_url || "", placeholder: "For Azure or an OpenAI-compatible server", onchange: (e) => set("base_url", e.target.value) }))),
      h("div", { class: "field" }, h("label", { text: "Answer format" }), out),
      h("label", { class: "check" }, h("input", { type: "checkbox", checked: d.mask_pii !== false, onchange: (e) => { d.mask_pii = e.target.checked; touchItem(); } }), "Mask personal data (emails, phone numbers, card numbers) before sending")),
    h("div", { class: "card-box" }, h("div", { class: "pane-title", text: "Use it" }),
      h("p", { class: "help", text: "Agents run this skill with the Run Prompt Skill action: skill = " + name + ". API keys never go in skill files; give the step an api_key credential." }),
      h("div", { class: "row-actions" }, h("button", { type: "button", onclick: () => copyStep({ action: "ooa.ai_skills/run", args: { skill: name, values: Object.fromEntries(names.map((n) => [n, ""])) } }, "Run Prompt Skill") }, "Copy as an agent step"))));
}

// ---- templates ----
const TEMPLATES = [
  { id: "csv_rows", name: "Loop through a CSV file", icon: "table", color: "#2563eb", description: "Read a CSV file into a table and handle each row.",
    variables: [{ name: "csv_file", type: "File", direction: "input", required: true, description: "The CSV file to read." }, { name: "rows", type: "Table" },
      { name: "row", type: "Record" }, { name: "row_count", type: "Number", direction: "output", description: "How many rows were read." }],
    steps: [{ id: "s1", action: "ooa.csv/read", args: { path: "$csv_file" }, out: { table: "rows", row_count: "row_count" } },
      { id: "s2", action: "core/log", args: { message: "Read ${row_count} rows" } },
      { id: "s3", action: "core/loop", args: { iterator: "table_rows", over: "$rows", as: "row" }, children: [
        { id: "s4", action: "core/comment", args: { text: "Handle one row here; read a column with $row.column_name" } },
        { id: "s5", action: "core/log", args: { message: "Row: ${row}" } }] }] },
  { id: "excel_rows", name: "Loop through Excel rows", icon: "sheet", color: "#16a34a", description: "Open a workbook, read the first sheet as a table and handle each row.",
    variables: [{ name: "workbook", type: "File", direction: "input", required: true, description: "The Excel workbook." }, { name: "rows", type: "Table" },
      { name: "row", type: "Record" }, { name: "row_count", type: "Number", direction: "output" }],
    steps: [{ id: "s1", action: "ooa.excel/open", args: { path: "$workbook" } },
      { id: "s2", action: "ooa.excel/get_all_cells", args: { header: true }, out: { table: "rows", row_count: "row_count" } },
      { id: "s3", action: "core/loop", args: { iterator: "table_rows", over: "$rows", as: "row" }, children: [
        { id: "s4", action: "core/comment", args: { text: "Handle one row here; read a column with $row.column_name" } }] },
      { id: "s5", action: "ooa.excel/close", args: {} }] },
  { id: "folder_files", name: "Process every file in a folder", icon: "folder", color: "#059669", description: "List the files in a folder and handle them one by one.",
    variables: [{ name: "folder", type: "File", direction: "input", required: true, description: "The folder to process." }, { name: "pattern", type: "String", direction: "input", default: "*.*", description: "Which files, e.g. *.pdf" },
      { name: "files", type: "List" }, { name: "file", type: "String" }],
    steps: [{ id: "s1", action: "ooa.file/list", args: { folder: "$folder", pattern: "$pattern" }, out: { files: "files" } },
      { id: "s2", action: "core/loop", args: { iterator: "list", over: "$files", as: "file" }, children: [
        { id: "s3", action: "core/log", args: { message: "Processing ${file}" } },
        { id: "s4", action: "core/comment", args: { text: "Handle $file here" } }] }] },
  { id: "rest_api", name: "Call a REST API safely", icon: "api", color: "#4f46e5", description: "Call a web API with error handling and return its answer.",
    variables: [{ name: "url", type: "String", direction: "input", required: true, default: "https://api.example.com/items", description: "The address to call." },
      { name: "status", type: "Number", direction: "output" }, { name: "body", type: "Any", direction: "output" }, { name: "error", type: "Dictionary" }],
    steps: [{ id: "s1", action: "core/try", children: [{ id: "s2", action: "ooa.rest/request", args: { url: "$url", method: "GET" }, out: { status: "status", json: "body" } }],
      catch: [{ codes: [], as: "error", children: [{ id: "s3", action: "core/log", args: { message: "The request failed: ${error.message}", level: "error" } },
        { id: "s4", action: "core/throw", args: { code: "BUSINESS.API_FAILED", message: "Could not call ${url}", suggested_fix: "Check the address and that the service is up." } }] }], finally: [] }] },
  { id: "ai_summary", name: "Summarise a document with AI", icon: "spark", color: "#db2777", description: "Read a text file and summarise it with a language model.",
    variables: [{ name: "text_file", type: "File", direction: "input", required: true, description: "The text file to summarise." },
      { name: "api_key", type: "Credential", direction: "input", description: "API key for the AI provider." }, { name: "text", type: "String" },
      { name: "summary", type: "String", direction: "output", description: "The summary." }],
    steps: [{ id: "s1", action: "ooa.file/read_text", args: { path: "$text_file" }, out: { text: "text" } },
      { id: "s2", action: "ooa.genai/summarize", args: { text: "$text", sentences: 3, model: "gpt-4o-mini", api_key: "$api_key" }, out: { summary: "summary" } }] },
  { id: "web_text", name: "Read text from a web page", icon: "globe", color: "#ea580c", description: "Open a page in a browser, read the main heading and close it.",
    variables: [{ name: "url", type: "String", direction: "input", default: "https://example.com", description: "The page to open." }, { name: "heading", type: "String", direction: "output" }],
    steps: [{ id: "s1", action: "ooa.browser/open", args: { url: "$url" } }, { id: "s2", action: "ooa.browser/get_text", args: { selector: "h1" }, out: { text: "heading" } },
      { id: "s3", action: "ooa.browser/close", args: {} }] },
  { id: "error_handling", name: "Agent with error handling", icon: "shield", color: "#dc2626", description: "A Try / Catch / Finally skeleton that logs failures.",
    variables: [{ name: "error", type: "Dictionary" }],
    steps: [{ id: "s1", action: "core/try", children: [{ id: "s2", action: "core/comment", args: { text: "Put the main steps of the agent here." } }],
      catch: [{ codes: [], as: "error", children: [{ id: "s3", action: "core/log", args: { message: "Failed: ${error.code} ${error.message}", level: "error" } }] }],
      finally: [{ id: "s4", action: "core/log", args: { message: "Finished" } }] }] },
];
function availableTemplates() { return TEMPLATES.filter((t) => allSteps(t.steps).every((st) => state.specs.has(st.action))); }
function openTemplates() {
  openDialog("Create an agent from a blueprint", h("div", { class: "tpl-grid" }, availableTemplates().map((t) => h("button", { type: "button", class: "tpl", onclick: () => { closeDialog(); createFromTemplate(t); } },
    tile(t.icon, t.color, "lg"), h("strong", { text: t.name }), h("span", { class: "desc", text: t.description })))), [], { wide: true, closeLabel: "Close" });
}
async function createFromTemplate(t) {
  const name = prompt("Name of the new agent:", t.name);
  if (!name) return;
  if (state.dirty && !confirm("Discard unsaved changes?")) return;
  const path = await fileOp("bot", { parent: currentFolder(), name }, null);
  if (!path) return;
  try {
    const created = (await api("GET", "bot?path=" + encodeURIComponent(path))).flow;
    Object.assign(created, { description: t.description, variables: JSON.parse(JSON.stringify(t.variables)), steps: JSON.parse(JSON.stringify(t.steps)) });
    await api("PUT", "bot?path=" + encodeURIComponent(path), created);
    setDirty(false); expandTo(path); state.treeSel = path;
    await loadTree(); await openBot(path);
    toast("Created " + name + " from the blueprint");
  } catch (e) { toast(e.message, true); }
}

// ---- dependencies of the open bot ----
async function renderDeps() {
  const box = $("tab-deps");
  if (!state.flow || !state.file) return;
  box.replaceChildren(h("p", { class: "muted", text: "Looking through the workspace\u2026" }));
  try {
    const r = await api("GET", "deps?path=" + encodeURIComponent(state.file));
    const group = (title, icon, color, items, empty) => h("div", { class: "dep-group" },
      h("div", { class: "dep-head" }, tile(icon, color, "sm"), h("strong", { text: title }), h("span", { class: "n", text: items.length })),
      items.length ? items.map((x) => h("div", { class: "dep-row" + (x.missing ? " missing" : ""), tabindex: x.path ? "0" : null, role: x.path ? "button" : null,
        onclick: () => { if (x.path) openAny(x.path); }, onkeydown: (e) => { if (e.key === "Enter" && x.path) openAny(x.path); } },
        h("span", { class: "mono", text: x.path || x.name }), x.missing ? h("span", { class: "pill-warn", text: "Not in the workspace" }) : null,
        h("span", { class: "spacer" }), x.step ? h("a", { class: "link", text: "step " + x.step, onclick: (e) => { if (x.path && title !== "Used by") { e.stopPropagation(); selectById(x.step); } } }) : null))
        : h("div", { class: "empty", text: empty }));
    box.replaceChildren(
      state.dirty ? h("div", { class: "note", text: "Save the agent to include your latest changes." }) : null,
      h("p", { class: "help", text: "What this agent needs to run, and which agents use it. Agent check-in child agents too before running this agent from Command Center." }),
      h("div", { class: "dep-grid" },
        group("Child agents and agent tools", "bot", "#6d28d9", r.bots, "This agent does not call other agents."),
        group("Scripts", "code", "#0f766e", r.scripts, "No script files."),
        group("Prompt Skills", "spark", "#c026d3", r.skills, "No Prompt Skills."),
        group("Files", "file", "#64748b", r.files, "No workspace files referenced."),
        group("Used by", "jump", "#2563eb", r.usedBy, "No other agent in the workspace calls this agent.")));
  } catch (e) { box.replaceChildren(h("p", { class: "error-box", text: e.message })); }
}
function openAny(path) {
  if (path.endsWith(".flow.json")) { state.treeSel = path; expandTo(path); openBot(path).catch((e) => toast(e.message, true)); }
  else { state.treeSel = path; expandTo(path); openItem(path); }
}

// ---------- debugger ----------
function breakpointSet() { return new Set(((state.flow && state.flow.ui) || {}).breakpoints || []); }
function toggleBreakpoint(id) {
  const b = breakpointSet();
  if (b.has(id)) b.delete(id); else b.add(id);
  state.flow.ui = Object.assign({}, state.flow.ui || {}, { breakpoints: [...b] });
  if (!state.flow.ui.breakpoints.length) delete state.flow.ui.breakpoints;
  if (!Object.keys(state.flow.ui).length) delete state.flow.ui;
  setDirty(true); renderSteps(); renderJson();
  if (state.live && state.live.id && state.running) api("POST", "runs/" + state.live.id + "/breakpoints", { ids: [...b] }).catch(() => {});
  toast(b.has(id) ? "Breakpoint set on " + id : "Breakpoint cleared");
}
async function debugBot() {
  if (!state.flow) { toast("Open an agent first.", true); return; }
  const bps = breakpointSet();
  toast(bps.size ? "Debugging: the run stops at " + bps.size + " breakpoint(s)." : "Debugging: the run stops before the first step.");
  return runFlow(state.flow, collectInputs(), { debug: true, stopAtStart: bps.size === 0 });
}
async function debugCommand(command) {
  if (!state.live || !state.live.id) return;
  try { await api("POST", "runs/" + state.live.id + "/debug", { command }); state.live.debug = null; renderLive(); }
  catch (e) { toast(e.message, true); }
}
function renderDebugPanel(box, L) {
  const d = L.debug;
  const spec = state.specs.get(d.action);
  const varsRows = Object.entries(d.variables || {}).map(([name, value]) => {
    const v = (state.flow.variables || []).find((x) => x.name === name) || { type: "Any" };
    const inp = h("input", { type: "text", value: typeof value === "string" ? value : JSON.stringify(value), "aria-label": "Value of " + name, disabled: v.type === "Credential" });
    const set = h("button", { type: "button", class: "small", disabled: v.type === "Credential", onclick: async () => {
      try { await api("POST", "runs/" + L.id + "/variable", { name, value: v.type === "String" ? inp.value : parseLoose(inp.value) }); toast("Changed $" + name); }
      catch (e) { toast(e.message, true); } } }, "Set");
    inp.addEventListener("keydown", (e) => { if (e.key === "Enter") set.click(); });
    return h("tr", {}, h("td", {}, typeBadge(v.type), " ", h("span", { class: "mono", text: "$" + name })), h("td", {}, h("div", { class: "var-field" }, inp, set)));
  });
  box.replaceChildren(
    h("div", { class: "dbg-head" }, h("span", { class: "status debugging", text: d.reason === "breakpoint" ? "At breakpoint" : "Paused" }),
      h("span", {}, "Before ", h("strong", { text: spec ? spec.action.name : d.action }), " ", h("a", { class: "link", text: "[" + d.stepId + "]", onclick: () => selectById(d.stepId) })),
      d.depth ? h("span", { class: "muted", text: "in child agent " + d.automation }) : null),
    h("div", { class: "row-actions dbg-bar" },
      h("button", { type: "button", class: "primary", title: "Continue (F5)", onclick: () => debugCommand("continue") }, "Continue"),
      h("button", { type: "button", title: "Step over (F10)", onclick: () => debugCommand("stepOver") }, "Step over"),
      h("button", { type: "button", title: "Step into (F11)", onclick: () => debugCommand("stepInto") }, "Step into"),
      h("button", { type: "button", title: "Step out (Shift+F11)", onclick: () => debugCommand("stepOut") }, "Step out"),
      h("button", { type: "button", class: "danger", onclick: () => liveControl("stop") }, "Stop")),
    h("div", { class: "dbg-grid" },
      h("div", {}, h("div", { class: "pane-title", text: "Call stack" }),
        h("ol", { class: "dbg-stack" }, (d.stack || []).slice().reverse().map((f, i) => h("li", { class: i === 0 ? "top" : "" }, h("span", { class: "mono", text: f.automation }), f.stepId ? " at [" + f.stepId + "]" : "")))),
      h("div", {}, h("div", { class: "pane-title", text: "Variables (edit and press Set)" }),
        varsRows.length ? h("table", { class: "grid dbg-vars" }, h("tbody", {}, varsRows)) : h("div", { class: "empty", text: "No variables." }))));
}

// ---------- quick add (Ctrl+K or /) ----------
function openQuickAdd() {
  if (!state.flow) { toast("Open an agent first.", true); return; }
  closeQuickAdd();
  const q = h("input", { type: "search", placeholder: "Type to find an action, then Enter", "aria-label": "Find an action" });
  const list = h("div", { class: "qa-list", role: "listbox" });
  let results = [], sel = 0;
  const all = [];
  for (const p of state.packages) for (const a of p.actions) all.push({ addr: p.id + "/" + a.id, a, p, text: (a.name + " " + p.name + " " + (a.category || "") + " " + a.description).toLowerCase() });
  const render = () => {
    const words = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    if (!words.length) {
      const rec = recentCommands().filter((x) => state.specs.has(x));
      results = [...rec.map((x) => all.find((y) => y.addr === x)).filter(Boolean), ...all.filter((x) => x.p.id === "core" && !rec.includes(x.addr))].slice(0, 12);
    } else {
      results = all.map((x) => {
        let score = 0;
        for (const w of words) { const i = x.text.indexOf(w); if (i < 0) return null; score += x.a.name.toLowerCase().startsWith(w) ? 0 : x.a.name.toLowerCase().includes(w) ? 1 : 3 + i / 100; }
        return { x, score };
      }).filter(Boolean).sort((m, n) => m.score - n.score).slice(0, 14).map((r) => r.x);
    }
    sel = Math.min(sel, Math.max(0, results.length - 1));
    list.replaceChildren(...(results.length ? results.map((r, i) => {
      const st = styleFor(r.addr);
      return h("button", { type: "button", class: "qa-item" + (i === sel ? " sel" : ""), role: "option", "aria-selected": String(i === sel), onclick: () => pick(r.addr) },
        tile(st.icon, st.color, "sm"), h("span", { class: "qa-name", text: r.a.name }), h("span", { class: "qa-pkg", text: r.p.name }));
    }) : [h("div", { class: "empty", text: "No action matches." })]));
  };
  const pick = (addr) => { closeQuickAdd(); addStep(addr); };
  q.addEventListener("input", () => { sel = 0; render(); });
  q.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); sel = Math.min(sel + 1, results.length - 1); render(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); sel = Math.max(sel - 1, 0); render(); }
    else if (e.key === "Enter" && results[sel]) { e.preventDefault(); pick(results[sel].addr); }
    else if (e.key === "Escape") { closeQuickAdd(); }
  });
  const box = h("div", { class: "qa-backdrop", id: "quick-add", onclick: (e) => { if (e.target.id === "quick-add") closeQuickAdd(); } },
    h("div", { class: "qa-panel", role: "dialog", "aria-label": "Quick add" }, h("div", { class: "qa-head" }, svg("plus"), h("strong", { text: "Quick add" }),
      h("span", { class: "muted", text: state.target ? "into the highlighted section" : state.selected ? "after the selected step" : "at the end" })), q, list));
  document.body.appendChild(box);
  render();
  q.focus();
}
function closeQuickAdd() { const b = $("quick-add"); if (b) b.remove(); }

// ---------- flow view ----------
function renderFlowView() {
  const container = $("steps");
  const problemsBy = new Map();
  for (const p of state.problems) if (p.stepId) problemsBy.set(p.stepId, (problemsBy.get(p.stepId) || 0) + 1);
  const node = (s, p) => {
    const spec = state.specs.get(s.action), st = styleFor(s.action);
    const live = state.liveSteps;
    const isSel = state.multi.has(s.id) || (state.multi.size === 0 && sameList(p, state.selected));
    const cls = "fnode" + (isSel ? " selected" : "") + (s.disabled ? " disabled" : "") + (live.paused === s.id ? " paused" : live.running === s.id ? " running" : live.failed === s.id ? " failed-run" : live.done.has(s.id) ? " done" : "")
      + (breakpointSet().has(s.id) ? " bp" : "") + (s.action === "core/if" ? " decision" : "");
    const el = h("div", { class: cls, tabindex: "0", "data-step-id": s.id, title: summary(s),
      onclick: (e) => { e.stopPropagation(); clickStep(e, p, s); }, oncontextmenu: (e) => { e.stopPropagation(); showContextMenu(e, p, s); } },
      tile(st.icon, st.color, "ico"), h("div", { class: "fn-text" }, h("div", { class: "fn-title", text: spec ? spec.action.name : s.action }),
        h("div", { class: "fn-sum", text: s.description || summary(s) })),
      problemsBy.get(s.id) ? h("span", { class: "flag", text: "!" + problemsBy.get(s.id) }) : null);
    el.style.setProperty("--c", st.color);
    return el;
  };
  const seq = (steps, path) => {
    const col = h("div", { class: "fseq" });
    if (!steps.length) { col.append(h("div", { class: "fempty", text: "(empty)" })); return col; }
    steps.forEach((s, i) => {
      const p = path.concat(i);
      col.append(h("div", { class: "fline" }), node(s, p));
      let branches = [];
      if (s.action === "core/if") branches = [["Then", ["children"]], ...(s.elseIf || []).map((_, k) => ["Else if #" + (k + 1), ["elseIf", k, "children"]]), ["Else", ["else"]]];
      else if (s.action === "core/try") branches = [["Try", ["children"]], ["Catch", ["catch", 0, "children"]], ["Finally", ["finally"]]];
      else if (s.action === "core/parallel") branches = [["At the same time", ["children"]]];
      else if (SECTIONS[s.action]) branches = [[s.action === "core/loop" ? "Repeat" : "Steps", ["children"]]];
      branches = branches.filter(([, k]) => getAt(s, k) !== undefined);
      if (!branches.length || collapsedSet().has(s.id)) return;
      const row = h("div", { class: "fbranches" + (s.action === "core/loop" ? " loop" : "") });
      row.style.setProperty("--c", styleFor(s.action).color);
      for (const [label, k] of branches) {
        const kids = getAt(s, k);
        const sub = seq(kids || [], p.concat(k));
        row.append(h("div", { class: "fbranch" }, h("div", { class: "flabel-pill", text: label }), sub));
      }
      col.append(row);
    });
    return col;
  };
  if (!state.flow.steps.length) { container.replaceChildren(h("div", { class: "canvas-empty", text: "This agent has no steps yet. Switch to List to drag actions in, or press Ctrl+K to quick add." })); return; }
  container.replaceChildren(h("div", { class: "flow-view" }, h("div", { class: "fterm start", text: "Start" }), seq(state.flow.steps, ["steps"]), h("div", { class: "fline" }), h("div", { class: "fterm end", text: "End" })));
  const total = allSteps(state.flow.steps).length;
  $("canvas-stats").textContent = total + (total === 1 ? " step" : " steps") + " \u00B7 " + state.problems.length + (state.problems.length === 1 ? " problem" : " problems");
  $("insert-hint").textContent = "Flow view: select steps here; drag and drop in List view.";
}

// ---------- assistant: build from a description, explain this agent ----------
function explainAgent(flow) {
  const lines = [];
  const name = (s) => { const sp = state.specs.get(s.action); return sp ? sp.action.name : s.action; };
  const walk = (steps, depth) => (steps || []).forEach((s, i) => {
    if (s.disabled) return;
    const pad = "  ".repeat(depth) + (depth ? "- " : (i + 1) + ". ");
    const a = s.args || {};
    let text;
    if (s.action === "core/if") text = "If " + showValue(a.condition) + ":";
    else if (s.action === "core/loop") text = "Repeat " + (a.iterator === "times" ? showValue(a.count) + " times" : a.iterator === "while" ? "while " + showValue(a.condition) : "for each item of " + showValue(a.over) + (a.as ? " (as $" + a.as + ")" : "")) + ":";
    else if (s.action === "core/try") text = "Try the following, and handle errors:";
    else if (s.action === "core/set") text = "Set $" + a.variable + " to " + showValue(a.value);
    else if (s.action === "core/comment") text = "Note: " + showValue(a.text);
    else text = name(s) + (s.description ? " (" + s.description + ")" : "") + (Object.keys(a).length ? " with " + Object.entries(a).slice(0, 3).map(([k, v]) => k + " " + preview(v)).join(", ") : "")
      + (s.out ? ", storing " + Object.values(s.out).map((v) => "$" + v).join(" and ") : "");
    lines.push(pad + text);
    walk(s.children, depth + 1);
    if (s.action === "core/if" && (s.else || []).length) { lines.push("  ".repeat(depth + 1) + "Otherwise:"); walk(s.else, depth + 2); }
    for (const c of s.catch || []) if ((c.children || []).length) { lines.push("  ".repeat(depth + 1) + "If an error happens" + (c.as ? " (kept in $" + c.as + ")" : "") + ":"); walk(c.children, depth + 2); }
    if ((s.finally || []).length) { lines.push("  ".repeat(depth + 1) + "Always, at the end:"); walk(s.finally, depth + 2); }
  });
  const ins = (flow.variables || []).filter((v) => v.direction === "input"), outs = (flow.variables || []).filter((v) => v.direction === "output");
  const head = [(flow.name || "This agent") + (flow.description ? ": " + flow.description : ""),
    ins.length ? "It needs: " + ins.map((v) => "$" + v.name + " (" + v.type + (v.required ? ", required" : "") + ")").join(", ") + "." : "It needs no inputs.",
    outs.length ? "It returns: " + outs.map((v) => "$" + v.name + " (" + v.type + ")").join(", ") + "." : "It returns no outputs.", "", "Steps:"];
  walk(flow.steps, 0);
  return head.concat(lines.length ? lines : ["(no steps yet)"]).join("\n");
}

async function openAssistant(tab) {
  if (!state.flow) { toast("Open an agent first.", true); return; }
  let conns = { connections: [] };
  try { conns = await api("GET", "connections"); } catch (_) { /* none yet */ }
  const mode = { tab: tab || "build" };
  const body = h("div", { class: "assistant" });
  const prompt = h("textarea", { rows: 4, placeholder: "Describe what the agent should do, e.g.: Read invoices.csv, and for every row with amount above 1000 send an email to approvals@company.com with the invoice number." });
  const connSel = h("select", { "aria-label": "Model connection" }, (conns.connections || []).map((c) => h("option", { value: c.name, text: c.name + " (" + c.provider + ", " + (c.model || "?") + ")" })));
  if (conns.default) connSel.value = conns.default;
  const result = h("div", { class: "assistant-result" });
  let proposal = null;
  const insert = () => {
    if (!proposal) return;
    const added = [];
    for (const v of proposal.variables || []) if (v && v.name && !state.flow.variables.some((x) => x.name === v.name)) { state.flow.variables.push(v); added.push(v.name); }
    const steps = freshIds(JSON.parse(JSON.stringify(proposal.steps || [])));
    const at = insertionPoint();
    at.list.splice(at.index, 0, ...steps);
    state.multi = new Set(steps.map((x) => x.id));
    changed(); renderVariables(); renderProps(); closeDialog();
    toast("Inserted " + steps.length + " step(s)" + (added.length ? " and " + added.length + " variable(s)" : "") + ". Review them before running.");
  };
  const render = () => {
    const tabs = h("div", { class: "seg", role: "tablist" }, [["build", "Build from a description"], ["explain", "Explain this agent"]].map(([k, l]) =>
      h("button", { type: "button", role: "tab", "aria-selected": String(mode.tab === k), onclick: () => { mode.tab = k; render(); } }, l)));
    if (mode.tab === "explain") {
      const text = explainAgent(state.flow);
      body.replaceChildren(tabs, h("pre", { class: "explain", text }),
        h("div", { class: "row-actions" }, h("button", { type: "button", onclick: () => { try { navigator.clipboard.writeText(text); toast("Copied"); } catch (_) { toast("Select the text to copy it."); } } }, "Copy")));
      return;
    }
    body.replaceChildren(tabs, field("What should the agent do?", prompt),
      (conns.connections || []).length ? field("One Model Connection", connSel)
        : h("div", { class: "note" }, "The assistant needs a language model. ", h("a", { class: "link", text: "Set up a One Model Connection", onclick: () => { closeDialog(); openConnections(); } }), " first."),
      h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", disabled: !(conns.connections || []).length, onclick: async (e) => {
        if (!prompt.value.trim()) { toast("Describe what the agent should do.", true); return; }
        e.target.disabled = true;
        result.replaceChildren(h("p", { class: "muted", text: "Thinking\u2026 this can take up to a minute." }));
        try {
          proposal = await api("POST", "assistant/build", { prompt: prompt.value, connection: connSel.value, flow: state.flow });
          result.replaceChildren(
            proposal.notes ? h("p", { text: proposal.notes }) : null,
            h("ol", { class: "assistant-steps" }, allSteps(proposal.steps || []).map((s) => { const sp = state.specs.get(s.action); const st = styleFor(s.action);
              return h("li", {}, tile(st.icon, st.color, "sm"), " ", h("strong", { text: sp ? sp.action.name : s.action }), h("span", { class: "muted", text: "  " + summary(s) })); })),
            (proposal.problems || []).length ? h("div", { class: "note warn", text: proposal.problems.length + " problem(s) to fix after inserting: " + proposal.problems.slice(0, 3).map((p) => p.message).join("; ") }) : null,
            h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: insert }, "Insert into the agent")));
        } catch (err) { result.replaceChildren(h("p", { class: "error-box", text: err.message })); }
        finally { e.target.disabled = false; }
      } }, "Generate steps")), result);
  };
  render();
  openDialog("Agent assistant", body, [], { wide: true, closeLabel: "Close" });
}

// ---------- One Model Connections ----------
async function openConnections() {
  let data;
  try { data = await api("GET", "connections"); } catch (e) { toast(e.message, true); return; }
  const rows = (data.connections || []).map((c) => Object.assign({}, c));
  let def = data.default || "";
  const box = h("div", { class: "conn-list" });
  const render = () => {
    box.replaceChildren(...(rows.length ? rows.map((c, i) => {
      const prov = h("select", { "aria-label": "Provider", onchange: (e) => { c.provider = e.target.value; } }, AI_PROVIDERS.map(([v, t]) => h("option", { value: v, text: t })));
      prov.value = c.provider || "openai";
      c.provider = prov.value;
      const keyKind = h("select", { "aria-label": "API key from", onchange: (e) => { if (e.target.value === "env") { delete c.api_key; c.api_key_env = c.api_key_env || "OPENAI_API_KEY"; } else if (e.target.value === "vault") { delete c.api_key_env; c.api_key = { $credential: "" }; } else { delete c.api_key; delete c.api_key_env; } render(); } },
        h("option", { value: "env", text: "Environment variable" }), h("option", { value: "vault", text: "One Agent Vault credential" }), h("option", { value: "none", text: "No key (local model)" }));
      keyKind.value = c.api_key ? "vault" : c.api_key_env ? "env" : "none";
      const keyVal = keyKind.value === "none" ? null : h("input", { type: "text", "aria-label": "Key reference", value: c.api_key ? c.api_key.$credential : c.api_key_env,
        placeholder: keyKind.value === "vault" ? "vault folder/credential/attribute" : "OPENAI_API_KEY",
        onchange: (e) => { if (c.api_key) c.api_key = { $credential: e.target.value.trim() }; else c.api_key_env = e.target.value.trim(); } });
      return h("div", { class: "conn-row card-box" },
        h("div", { class: "conn-top" }, h("input", { type: "text", "aria-label": "Connection name", value: c.name || "", placeholder: "name, e.g. gpt", onchange: (e) => { const old = c.name; c.name = e.target.value.trim(); if (def === old) def = c.name; } }),
          h("label", { class: "check" }, h("input", { type: "radio", name: "conn-default", checked: def === c.name, onchange: () => { def = c.name; } }), "Default"),
          mini("\u2715", "Remove connection", () => { rows.splice(i, 1); render(); }, "danger")),
        h("div", { class: "grid2" }, field("Provider", prov), field("Model", h("input", { type: "text", value: c.model || "", placeholder: "gpt-4o-mini", onchange: (e) => { c.model = e.target.value.trim(); } }))),
        h("div", { class: "grid2" }, field("API key from", h("div", { class: "var-field" }, keyKind, keyVal)),
          field("Endpoint (optional)", h("input", { type: "text", value: c.base_url || "", placeholder: "For Azure or a local server", onchange: (e) => { c.base_url = e.target.value.trim(); } }))));
    }) : [h("p", { class: "muted", text: "No connections yet. Add one to use AI actions, Prompt Skills and the assistant without repeating model settings." })]),
      h("button", { type: "button", onclick: () => { rows.push({ name: rows.length ? "model" + (rows.length + 1) : "default_model", provider: "openai", model: "gpt-4o-mini", api_key_env: "OPENAI_API_KEY" }); render(); } }, "+ Connection"));
  };
  render();
  openDialog("One Model Connections", [h("p", { class: "help", text: "Named model settings for this workspace (model-connections.json). Keys are never stored here: point to an environment variable or a One Agent Vault credential. In an action, set connection to the name; leave it empty to use the action's own settings." }), box],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { await api("PUT", "connections", { default: def, connections: rows }); closeDialog(); toast("Saved model connections"); }
      catch (e) { toast(e.message, true); } } }, "Save")], { wide: true });
}

// ---------- Custom connectors ----------
/** Connectors dialog: import an OpenAPI, Swagger or Postman file, browse operations, insert a call_operation step. */
async function openConnectors() {
  const box = h("div", { class: "conn-list" });
  const fileIn = h("input", { type: "file", accept: ".json,.yaml,.yml", "aria-label": "API description file" });
  const nameIn = h("input", { type: "text", placeholder: "from the API title" });
  const serverIn = h("input", { type: "text", placeholder: "first server in the file" });
  const doImport = async () => {
    const f = fileIn.files && fileIn.files[0];
    if (!f) { toast("Choose a file to import.", true); return; }
    try {
      const r = await api("POST", "connectors/import", { text: await f.text(), name: nameIn.value.trim() || undefined, server: serverIn.value.trim() || undefined });
      toast("Imported " + r.connector.name + " (" + r.connector.operations.length + " operations)");
      showOps(r.connector);
    } catch (e) { toast(e.message, true); }
  };
  const importCard = h("div", { class: "conn-row card-box" },
    h("div", { class: "help", text: "Import an OpenAPI 3.x or Swagger 2.0 file (JSON or YAML) or a Postman v2.1 collection. Each request becomes an operation an agent can call." }),
    field("API description", fileIn),
    h("div", { class: "grid2" }, field("Connector name (optional)", nameIn), field("Server (optional)", serverIn)),
    h("button", { type: "button", class: "primary", onclick: doImport }, "Import"));
  const showList = async () => {
    let data;
    try { data = await api("GET", "connectors"); } catch (e) { toast(e.message, true); return; }
    const rows = (data.connectors || []).map((c) => h("div", { class: "conn-row card-box" },
      h("div", { class: "conn-top" }, h("strong", { text: c.name }), h("span", { class: "muted", text: (c.title || "") + " · " + (c.operations || 0) + " operations" }),
        h("button", { type: "button", onclick: async () => { try { showOps((await api("GET", "connectors/" + encodeURIComponent(c.name))).connector); } catch (e) { toast(e.message, true); } } }, "Operations"),
        mini("✕", "Delete connector", async () => {
          if (!confirm("Delete connector " + c.name + "? Steps that use it will stop working.")) return;
          try { await api("DELETE", "connectors/" + encodeURIComponent(c.name)); toast("Deleted " + c.name); showList(); } catch (e) { toast(e.message, true); }
        }, "danger")),
      c.error ? h("div", { class: "pill-warn", text: c.error }) : h("div", { class: "muted", text: c.server || "No server set" })));
    box.replaceChildren(importCard, ...(rows.length ? rows : [h("p", { class: "muted", text: "No connectors yet." })]));
  };
  const showOps = (def) => {
    const search = h("input", { type: "search", placeholder: "Filter operations", "aria-label": "Filter operations" });
    const list = h("div");
    const render = () => {
      const q = search.value.trim().toLowerCase();
      list.replaceChildren(...(def.operations || []).filter((o) => !q || (o.id + " " + o.name + " " + o.path + " " + (o.tags || []).join(" ")).toLowerCase().includes(q)).map((o) =>
        h("div", { class: "search-hit" }, h("span", { class: "badge", text: o.method }),
          h("div", {}, h("div", {}, h("strong", { text: o.name }), " ", h("span", { class: "muted", text: o.path })),
            h("div", { class: "help", text: o.id + ((o.parameters || []).length ? " · " + o.parameters.map((p) => p.name + (p.required ? "*" : "")).join(", ") : "") })),
          h("button", { type: "button", onclick: () => insertConnectorStep(def, o) }, "Insert step"))));
    };
    search.addEventListener("input", render);
    render();
    box.replaceChildren(h("div", { class: "conn-top" }, h("button", { type: "button", onclick: showList }, "← All connectors"),
      h("strong", { text: def.title || def.name }), h("span", { class: "muted", text: def.server || "" })), search, list);
  };
  openDialog("Connectors", [h("p", { class: "help", text: "Custom connectors live in the workspace folder connectors/. Agents call them with Call operation (ooa.connector/call_operation); secrets come from a Credential variable." }), box], [], { wide: true, closeLabel: "Close" });
  showList();
}

/** Adds an ooa.connector/call_operation step for one operation, with its parameters filled in from defaults. */
function insertConnectorStep(def, op) {
  const action = "ooa.connector/call_operation";
  if (!state.flow) { toast("Open or create an agent first (Explorer tab).", true); return; }
  if (!state.specs.has(action)) { toast("The Custom Connectors package is not installed.", true); return; }
  addStep(action);
  const step = state.selected && getAt(state.flow, state.selected);
  if (!step || step.action !== action) return;
  const params = {};
  for (const p of op.parameters || []) if (p.required || (p.default !== undefined && p.default !== null)) params[p.name] = p.default !== undefined && p.default !== null ? p.default : "";
  step.args = { connector: def.name, operation: op.id };
  if (Object.keys(params).length) step.args.parameters = params;
  if (op.body) step.args.body = op.body.example !== undefined ? op.body.example : {};
  if (op.result && op.result.binary) step.args.output_file = op.id + ".bin";
  closeDialog();
  changed();
  renderProps();
}

// ---------- capture (pick an element in the browser) ----------
function defaultUrl() {
  const open = state.flow && allSteps(state.flow.steps).find((x) => x.action === "ooa.browser/open" && x.args && typeof x.args.url === "string" && !x.args.url.startsWith("$"));
  return open ? open.args.url : "https://";
}

async function pickSelector(done, askUrl) {
  const url = askUrl || prompt("Open which page to pick the element from?", defaultUrl());
  if (!url || url === "https://") return;
  toast("Click the element in the browser window that opens.");
  try { const r = await api("POST", "capture", { url, browser: "edge" }); done(r.selector, r.element, r.object); toast("Captured " + (r.element && r.element.tag ? r.element.tag : "") + " with " + ((r.object && r.object.properties) || []).length + " properties"); }
  catch (e) { toast(e.message, true); }
}

const CAPTURE_MATCHES = [["equals", "equals"], ["not_equals", "not equals"], ["contains", "contains"], ["starts_with", "starts with"], ["ends_with", "ends with"], ["wildcard", "wildcard (* ?)"], ["regex", "regular expression"]];

/** Editor for a captured object: page or window, iframes, search mode, index and the property table. */
function captureEditor(step, value, set) {
  const wrap = h("div", { class: "cap-editor" });
  let obj = value && typeof value === "object" && !Array.isArray(value) ? value : null;
  const desktop = step.action.startsWith("ooa.desktop_ui/") || (obj && obj.technology === "desktop");
  const save = () => set(obj);
  const sel = (opts, v, on, label) => { const e = h("select", { "aria-label": label, onchange: (ev) => on(ev.target.value) }, opts.map(([k, t]) => h("option", { value: k, text: t }))); e.value = v; return e; };
  async function recapture() {
    if (desktop) {
      toast("Point at the control in the application, then click it (or press Ctrl+Shift+C, as the capture window says).");
      try { const r = await api("POST", "capture-desktop", {}); obj = r.object || r; save(); render(); toast("Captured " + ((obj.properties || []).length) + " properties"); }
      catch (e) { toast(e.message, true); }
      return;
    }
    await pickSelector((sel2, el, o) => { if (o) { obj = o; save(); render(); } }, obj && obj.page && obj.page.url ? obj.page.url : null);
  }
  async function test() {
    if (!obj) return;
    const url = (obj.page && obj.page.url) || prompt("Open which page to test on?", defaultUrl());
    if (!url) return;
    toast("Opening the page: matches are outlined (green = the one the step uses).");
    try { const r = await api("POST", "capture/test", { url, object: obj, browser: "edge" }); const n = Number(r.count || 0);
      toast(n === 0 ? "Nothing matches: loosen a property (contains, wildcard) or recapture." : n + " element" + (n === 1 ? "" : "s") + " match" + (n === 1 ? "es" : "") + "." + (n > 1 ? " Use the index to choose one, or tick another property." : ""), n === 0); }
    catch (e) { toast(e.message, true); }
  }
  function render() {
    if (!obj) {
      wrap.replaceChildren(h("div", { class: "cap-empty" }, h("span", { text: "Nothing captured yet." }),
        h("button", { type: "button", class: "btn", onclick: recapture }, desktop ? "Capture desktop object" : "Capture element")));
      return;
    }
    const props = Array.isArray(obj.properties) ? obj.properties : (obj.properties = []);
    const head = h("div", { class: "cap-head" },
      h("span", { class: "chip", text: desktop ? "Desktop" : "Web" }),
      h("span", { class: "cap-where", text: desktop ? ((obj.window && obj.window.title) || "") + (obj.window && obj.window.process ? " (" + obj.window.process + ")" : "")
        : ((obj.page && (obj.page.title || obj.page.url)) || "") }),
      h("span", { class: "spacer" }),
      h("button", { type: "button", class: "btn", onclick: recapture }, "Recapture"),
      desktop ? null : h("button", { type: "button", class: "btn", onclick: test }, "Test"));
    const shot = obj.screenshot ? h("img", { class: "cap-shot", alt: "Captured element", src: "data:image/png;base64," + obj.screenshot }) : null;
    const by = sel(desktop ? [["properties", "Properties"]] : [["properties", "Properties (ticked below)"], ["css", "CSS selector"], ["xpath", "XPath"]], obj.search_by || "properties", (v) => { obj.search_by = v; save(); render(); }, "Search by");
    const idx = h("input", { type: "number", min: "0", value: String(obj.index || 0), "aria-label": "Index", onchange: (e) => { obj.index = Math.max(0, parseInt(e.target.value || "0", 10) || 0); save(); } });
    const ic = h("input", { type: "checkbox", checked: !!obj.ignore_case, onchange: (e) => { obj.ignore_case = e.target.checked; save(); } });
    const heal = h("input", { type: "checkbox", checked: obj.self_heal !== false, onchange: (e) => { obj.self_heal = e.target.checked; save(); } });
    const opts = h("div", { class: "cap-opts" },
      h("label", {}, "Search by ", by), h("label", { title: "When several elements match: 0 is the first" }, "Index ", idx),
      h("label", { class: "check" }, ic, "Ignore case"), desktop ? null : h("label", { class: "check", title: "Try the captured CSS and XPath when the search finds nothing" }, heal, "Self-heal"));
    const frames = !desktop && Array.isArray(obj.frames) && obj.frames.length ? h("div", { class: "cap-frames" }, "Inside iframe: ", h("code", { text: obj.frames.join("  \u203A  ") })) : null;
    const rows = props.map((p, i) => {
      const use = h("input", { type: "checkbox", checked: !!p.use, "aria-label": "Use " + p.name, onchange: (e) => { p.use = e.target.checked; save(); } });
      const nm = p.custom ? h("input", { type: "text", value: p.name || "", placeholder: "attribute", "aria-label": "Property name", onchange: (e) => { p.name = e.target.value.trim(); save(); } })
        : h("span", { class: "cap-name", text: p.name });
      const val = h("input", { type: "text", value: p.value == null ? "" : String(p.value), "aria-label": "Value of " + p.name, onchange: (e) => { p.value = e.target.value; save(); } });
      const m = sel(CAPTURE_MATCHES, p.match || "equals", (v) => { p.match = v; save(); }, "Match for " + p.name);
      const del = h("button", { type: "button", class: "mini danger", title: "Remove this property", onclick: () => { props.splice(i, 1); save(); render(); } }, "\u2715");
      // One block per property: Use, name, match and remove on the first line; the value full width below it.
      const row = h("div", { class: "cap-row" + (p.use ? " on" : "") }, h("span", { class: "cap-use" }, use), h("span", { class: "cap-prop" }, nm), m, del, h("span", { class: "cap-val" }, val));
      use.addEventListener("change", () => row.classList.toggle("on", use.checked));
      return row;
    });
    const table = h("div", { class: "cap-table", role: "group", "aria-label": "Captured properties" },
      h("div", { class: "cap-row cap-headrow" }, h("span", { text: "Use" }), h("span", { text: "Property and value" }), h("span", { text: "Match" }), h("span")), rows);
    const add = h("button", { type: "button", class: "link-btn", onclick: () => { props.push({ name: "", value: "", match: "equals", use: true, custom: true }); save(); render(); } }, "+ Add property");
    let place = null;
    if (!desktop) {
      const pg = obj.page || (obj.page = {});
      place = h("details", { class: "cap-page" }, h("summary", { text: "Page (used when the step switches to the captured page)" }),
        h("div", { class: "cap-opts" }, h("label", {}, "Address ", h("input", { type: "text", value: pg.url || "", onchange: (e) => { pg.url = e.target.value; save(); } })),
          sel(CAPTURE_MATCHES, pg.url_match || "starts_with", (v) => { pg.url_match = v; save(); }, "Address match")),
        h("div", { class: "cap-opts" }, h("label", {}, "Title ", h("input", { type: "text", value: pg.title || "", onchange: (e) => { pg.title = e.target.value; save(); } })),
          sel([["", "(ignore the title)"]].concat(CAPTURE_MATCHES), pg.title_match || "", (v) => { if (v) pg.title_match = v; else delete pg.title_match; save(); }, "Title match")));
    } else if (obj.window) {
      const w = obj.window;
      place = h("details", { class: "cap-page" }, h("summary", { text: "Window" }),
        h("div", { class: "cap-opts" }, h("label", {}, "Title ", h("input", { type: "text", value: w.title || "", onchange: (e) => { w.title = e.target.value; save(); } })),
          sel(CAPTURE_MATCHES, w.title_match || "wildcard", (v) => { w.title_match = v; save(); }, "Window title match")),
        h("div", { class: "cap-opts" }, h("label", {}, "Process ", h("input", { type: "text", value: w.process || "", onchange: (e) => { w.process = e.target.value; save(); } }))));
    }
    wrap.replaceChildren(head, shot, opts, frames, table, add, place);
  }
  render();
  return wrap;
}

/** Captures a control in a Windows application and adds a Desktop objects step for it. */
async function captureDesktopStep() {
  if (!state.flow) { toast("Open or create an agent first.", true); return; }
  toast("Hover over the control in the application (a red frame follows the pointer), then press Ctrl+Shift+C.");
  try {
    const r = await api("POST", "capture-desktop", { timeout_seconds: 60, screenshot: true, highlight: true });
    const object = r.object || r;
    const prop = (n) => ((object.properties || []).find((p) => p.name === n) || {}).value || "";
    const type = prop("ControlType");
    const action = /^(Edit|Document)$/.test(type) ? "set_text" : /^(ComboBox|List)$/.test(type) ? "select_item" : type === "CheckBox" ? "check" : "click";
    const step = { id: freshIds([{ id: "" }])[0].id, action: "ooa.desktop_ui/object_action", args: { object, action },
      description: "Captured " + (type || "control") + (prop("Name") ? ": " + prop("Name").slice(0, 30) : "") };
    const at = insertionPoint();
    at.list.splice(at.index, 0, step);
    state.multi = new Set([step.id]); state.selected = at.path.concat(at.index); state.target = null;
    changed(); renderProps();
    toast("Captured " + (type || "control") + " in " + ((object.window && object.window.title) || "the window"));
  } catch (e) { toast(e.message, true); }
}

function captureStep() {
  if (!state.flow) { toast("Open or create an agent first.", true); return; }
  pickSelector((selector, el, object) => {
    const tag = (el && el.tag) || "";
    const action = /^(input|textarea)$/.test(tag) && !/^(checkbox|radio|submit|button)$/.test((el && el.type) || "") ? "set_text"
      : tag === "select" ? "select_by_text" : el && el.type === "checkbox" ? "check" : "click";
    const args = object ? { object, action } : { selector, action };
    const step = { id: freshIds([{ id: "" }])[0].id, action: "ooa.browser/element_action", args, description: "Captured " + tag + (el && el.text ? ": " + el.text.slice(0, 30) : "") };
    const at = insertionPoint();
    at.list.splice(at.index, 0, step);
    state.multi = new Set([step.id]); state.selected = at.path.concat(at.index); state.target = null;
    changed(); renderProps();
  });
}

// ---------- search across bots ----------
let searchTimer = null;
function onTreeSearch() {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(async () => {
    const q = $("tree-search").value.trim();
    if (q.length < 2) { $("search-results").hidden = true; $("tree").hidden = false; return; }
    try {
      const r = await api("GET", "search?q=" + encodeURIComponent(q));
      $("tree").hidden = true;
      const box = $("search-results");
      box.hidden = false;
      box.replaceChildren(...(r.results.length ? r.results.map((x) => h("div", { class: "search-hit", tabindex: "0", role: "button",
        onclick: () => openBot(x.path), onkeydown: (e) => { if (e.key === "Enter") openBot(x.path); } },
        itemTile({ type: "bot", name: x.path }), h("div", {}, h("div", { text: x.path.replace(/\.flow\.json$/, "") }), h("div", { class: "help", text: "Matches " + x.matches.join(", ") }))))
        : [h("div", { class: "empty", text: "No agents match \u201C" + q + "\u201D." })]));
    } catch (e) { toast(e.message, true); }
  }, 250);
}

// ---------- resizable panels ----------
function makeSplitters() {
  const add = (el, where, cls, onDrag) => {
    const sp = h("div", { class: "splitter " + cls, role: "separator", tabindex: "0", "aria-label": "Drag to resize" });
    el.insertAdjacentElement(where, sp);
    sp.addEventListener("pointerdown", (e) => {
      e.preventDefault(); sp.setPointerCapture(e.pointerId); document.body.classList.add("resizing");
      const move = (ev) => onDrag(ev);
      const up = () => { sp.removeEventListener("pointermove", move); sp.removeEventListener("pointerup", up); document.body.classList.remove("resizing"); };
      sp.addEventListener("pointermove", move); sp.addEventListener("pointerup", up);
    });
    return sp;
  };
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  add($("side"), "afterend", "v side-split", (e) => setSize("side", clamp(e.clientX - $("side").getBoundingClientRect().left, 200, 520)));
  add($("props-panel"), "beforebegin", "v props-split", (e) => setSize("props", clamp($("props-panel").getBoundingClientRect().right - e.clientX, 260, 620)));
  add($("dock"), "beforebegin", "h dock-split", (e) => {
    if (document.body.classList.contains("dock-right")) setSize("rdock", clamp(window.innerWidth - e.clientX, 280, 760));
    else setSize("dock", clamp($("center").getBoundingClientRect().bottom - e.clientY, 120, window.innerHeight - 220));
  });
}

function setSize(key, px) {
  const p = prefs(); p.sizes = Object.assign({}, p.sizes, { [key]: Math.round(px) }); savePrefs(p);
  applySizes();
}

function applySizes() {
  const z = prefs().sizes || {};
  const r = document.documentElement.style;
  if (z.side) r.setProperty("--side-w", z.side + "px");
  if (z.props) r.setProperty("--props-w", z.props + "px");
  if (z.dock) r.setProperty("--dock-h", z.dock + "px");
  if (z.rdock) r.setProperty("--rdock-w", z.rdock + "px");
}

function wire() {
  applyPrefs();
  applySizes();
  makeSplitters();
  $("btn-undo").addEventListener("click", undo);
  $("btn-redo").addEventListener("click", redo);
  $("btn-cut").addEventListener("click", cutSelection);
  $("btn-copy").addEventListener("click", () => copySelection());
  $("btn-paste").addEventListener("click", () => pasteSteps(state.clipboard));
  $("btn-del").addEventListener("click", () => deleteSelection());
  $("btn-share-copy").addEventListener("click", copyToShared);
  $("btn-share-paste").addEventListener("click", pasteFromShared);
  $("btn-capture").addEventListener("click", captureStep);
  if ($("btn-capture-desktop")) $("btn-capture-desktop").addEventListener("click", captureDesktopStep);
  $("btn-checkin").addEventListener("click", crCheckin);
  $("tree-search").addEventListener("input", onTreeSearch);
  document.addEventListener("focusin", (e) => { if (e.target.closest && e.target.closest("#props, #item-editor") && /^(INPUT|TEXTAREA)$/.test(e.target.tagName) && e.target.type !== "search") state.lastInput = e.target; });
  document.addEventListener("click", (e) => { if (!e.target.closest("#ctx-menu")) closeContextMenu(); if (!e.target.closest("#var-pop, .var-btn")) closeVarPicker(); });
  $("dialog").addEventListener("click", (e) => { if (e.target === $("dialog")) closeDialog(); });
  makeDockDraggable();
  $("cmd-search").addEventListener("input", renderLibrary);
  $("btn-fold-groups").addEventListener("click", () => {
    if (state.groupsOpen.size) state.groupsOpen.clear(); else for (const id of ["recent", ...Object.keys(CATS)]) state.groupsOpen.add(id);
    renderLibrary();
  });
  buildCreateMenu();
  $("btn-create").addEventListener("click", (e) => { e.stopPropagation(); toggleCreateMenu(); });
  $("btn-home").addEventListener("click", goHome);
  $("btn-new-folder").addEventListener("click", newFolderHere);
  $("btn-rename").addEventListener("click", renameSelected);
  $("btn-delete").addEventListener("click", deleteSelectedNode);
  $("btn-refresh").addEventListener("click", () => loadTree().catch((e) => toast(e.message, true)));
  $("btn-save").addEventListener("click", save);
  $("btn-validate").addEventListener("click", () => { setPref("dock", true); switchConsole("problems"); validate(true); });
  $("btn-run").addEventListener("click", runBot);
  $("btn-debug").addEventListener("click", debugBot);
  $("btn-assistant").addEventListener("click", () => openAssistant());
  $("btn-quick-add").addEventListener("click", openQuickAdd);
  $("btn-connections").addEventListener("click", () => { $("view-pop").hidden = true; openConnections(); });
  $("btn-connectors").addEventListener("click", () => { $("view-pop").hidden = true; openConnectors(); });
  for (const b of document.querySelectorAll("[data-canvas-mode]")) b.addEventListener("click", () => setPref("canvasMode", b.dataset.canvasMode));
  $("btn-record").addEventListener("click", recordSteps);
  $("btn-expand-all").addEventListener("click", () => foldAll(false));
  $("btn-collapse-all").addEventListener("click", () => foldAll(true));
  $("tg-left").addEventListener("click", () => setPref("left", document.body.classList.contains("no-left")));
  $("tg-right").addEventListener("click", () => setPref("right", document.body.classList.contains("no-right")));
  $("tg-dock").addEventListener("click", () => setPref("dock", document.body.classList.contains("no-dock")));
  $("btn-props-collapse").addEventListener("click", () => setPref("right", false));
  $("btn-dock-min").addEventListener("click", () => setDock("mini"));
  $("sel-dock").addEventListener("change", (e) => setDock(e.target.value));
  $("sel-theme").addEventListener("change", (e) => setPref("theme", e.target.value));
  $("sel-density").addEventListener("change", (e) => setPref("density", e.target.value));
  $("btn-view").addEventListener("click", () => { const p = $("view-pop"); p.hidden = !p.hidden; $("btn-view").setAttribute("aria-expanded", String(!p.hidden)); });
  document.addEventListener("click", (e) => {
    if (!e.target.closest(".view-menu")) { $("view-pop").hidden = true; $("btn-view").setAttribute("aria-expanded", "false"); }
    if (!e.target.closest(".create-menu")) closeCreateMenu();
  });
  $("btn-json-apply").addEventListener("click", () => {
    try {
      const f = JSON.parse($("json-text").value);
      if (typeof f !== "object" || !f || Array.isArray(f)) throw new Error("The agent must be a JSON object.");
      setFlow(f, state.file); setDirty(true);
      toast("JSON applied");
    } catch (e) { toast("Invalid JSON: " + e.message, true); }
  });
  $("btn-json-format").addEventListener("click", () => {
    try { $("json-text").value = JSON.stringify(JSON.parse($("json-text").value), null, 2); } catch (e) { toast("Invalid JSON: " + e.message, true); }
  });
  for (const b of document.querySelectorAll(".rail-btn")) b.addEventListener("click", () => switchSide(b.dataset.side, true));
  for (const b of document.querySelectorAll(".tab")) b.addEventListener("click", () => switchTab(b.dataset.tab));
  for (const b of document.querySelectorAll(".ctab")) b.addEventListener("click", () => switchConsole(b.dataset.ctab));
  $("steps").addEventListener("click", () => { state.selected = null; state.target = null; state.multi = new Set(); renderSteps(); renderProps(); updateEditButtons(); });
  document.addEventListener("keydown", (e) => {
    const k = e.key.toLowerCase(), mod = e.ctrlKey || e.metaKey;
    if (mod && k === "s") { e.preventDefault(); save(); return; }
    if (e.key === "Escape" && $("var-pop")) { closeVarPicker(); return; }
    if (e.key === "Escape" && !$("dialog").hidden) { closeDialog(); return; }
    if (e.key === "Escape") { closeContextMenu(); if (state.multi.size) { state.multi = new Set(); state.selected = null; renderSteps(); renderProps(); updateEditButtons(); } return; }
    if (state.live && state.live.debug && ["F5", "F10", "F11"].includes(e.key)) {
      e.preventDefault(); debugCommand(e.key === "F5" ? "continue" : e.key === "F10" ? "stepOver" : e.shiftKey ? "stepOut" : "stepInto"); return;
    }
    if (mod && k === "k" && state.flow) { e.preventDefault(); openQuickAdd(); return; }
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test((document.activeElement || {}).tagName || "");
    if (typing || !state.flow) return;
    if (e.key === "F9" && state.selected) { e.preventDefault(); const st = getAt(state.flow, state.selected); if (st) toggleBreakpoint(st.id); return; }
    if (e.key === "/" && !mod) { e.preventDefault(); openQuickAdd(); return; }
    if (mod && k === "z" && !e.shiftKey) { e.preventDefault(); undo(); }
    else if ((mod && k === "y") || (mod && k === "z" && e.shiftKey)) { e.preventDefault(); redo(); }
    else if (mod && k === "c") { e.preventDefault(); copySelection(); }
    else if (mod && k === "x") { e.preventDefault(); cutSelection(); }
    else if (mod && k === "v") { e.preventDefault(); pasteSteps(state.clipboard); }
    else if (mod && k === "d") { e.preventDefault(); if (copySelection(true)) pasteSteps(state.clipboard); }
    else if (mod && k === "a") { e.preventDefault(); selectAllSteps(); }
    else if (e.key === "Delete" && state.multi.size) { e.preventDefault(); deleteSelection(); }
  });
  document.addEventListener("dragend", () => document.body.classList.remove("dnd"));
  window.addEventListener("beforeunload", (e) => { if (state.dirty) { e.preventDefault(); e.returnValue = ""; } });
}

if (typeof window !== "undefined" && !window.__OOA_NO_AUTOSTART__) document.addEventListener("DOMContentLoaded", init);
