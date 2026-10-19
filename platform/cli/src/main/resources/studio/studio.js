/* ONEOFFICEAUTOMATION Studio (preview): explorer, drag-and-drop canvas, command panels, run console. Plain JavaScript, no external libraries, so it works offline and
   under a strict Content-Security-Policy. All user data is rendered with textContent (never innerHTML). */
"use strict";

const SCHEMA = "https://schemas.oneofficeautomation.com/flow/v1.json";
const TYPES = ["String", "Number", "Boolean", "DateTime", "List", "Dictionary", "Table", "Record", "File", "Credential", "Any"];
const SECTIONS = {
  "core/if": [["children", "Then"], ["else", "Else"]],
  "core/loop": [["children", "Repeat"]],
  "core/step": [["children", "Steps"]],
  "core/parallel": [["children", "Branches (run at the same time)"]],
  "core/try": [["children", "Try"], [["catch", 0, "children"], "Catch (on error)"], ["finally", "Finally (always)"]],
};

const PKG_COLORS = ["#0a7bbb", "#a371ed", "#1f9d8b", "#e07a2f", "#d0457a", "#5867dd", "#3b9c3b", "#b8860b", "#7a5af8", "#0f8fa8", "#c2410c", "#64748b"];
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
  const res = await fetch("/api/" + path, {
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
    state.packages.forEach((p, i) => { p.color = PKG_COLORS[i % PKG_COLORS.length]; for (const a of p.actions) state.specs.set(p.id + "/" + a.id, { pkg: p, action: a }); });
    renderLibrary();
    await loadTree();
    showEditor(false);
  } catch (e) { toast("Cannot reach Studio server: " + e.message, true); }
}

function showEditor(open) {
  $("welcome").hidden = open;
  $("editor-body").hidden = !open;
  renderCrumbs();
}

function renderCrumbs() {
  const c = $("crumbs");
  if (!state.file) { c.replaceChildren(); return; }
  const parts = state.file.split("/");
  c.replaceChildren(h("span", { text: "Workspace / " + parts.slice(0, -1).map((p) => p + " / ").join("") }), h("strong", { text: parts[parts.length - 1].replace(/\.flow\.json$/, "") }));
}

async function openBot(path) {
  if (state.dirty && path !== state.file && !confirm("Discard unsaved changes to the open bot?")) return;
  const data = await api("GET", "bot?path=" + encodeURIComponent(path));
  setFlow(data.flow, path);
  switchSide("commands");
}

function setFlow(flow, file) {
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
      h("span", { class: "icon " + node.type }),
      h("span", { text: node.type === "bot" ? node.name.replace(/\.flow\.json$/, "") : node.name }),
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
  else { renderTree(); toast(node.name + " (" + fmtSize(node.size) + "). Bots can read this file using its path: " + node.path); }
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

async function newBotHere() {
  const name = prompt("Name of the new bot:", "New bot");
  if (!name) return;
  if (state.dirty && !confirm("Discard unsaved changes to the open bot?")) return;
  const folder = currentFolder();
  const path = await fileOp("bot", { parent: folder, name }, "Bot created");
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
  const current = node.type === "bot" ? node.name.replace(/\.flow\.json$/, "") : node.name;
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
    if (state.file && (state.file === node.path || state.file.startsWith(node.path + "/"))) { state.file = null; state.flow = null; setDirty(false); showEditor(false); renderProps(); }
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
function renderLibrary() {
  const q = $("cmd-search").value.trim().toLowerCase();
  const out = [];
  let count = 0;
  for (const p of state.packages) {
    const items = p.actions.filter((a) => !q || (a.name + " " + a.id + " " + a.description + " " + p.name).toLowerCase().includes(q));
    if (!items.length) continue;
    count += items.length;
    const open = q !== "" || state.groupsOpen.has(p.id);
    const sw = h("span", { class: "sw" });
    sw.style.background = p.color;
    out.push(h("button", { type: "button", class: "pkg-head", "aria-expanded": String(open),
      onclick: () => { if (state.groupsOpen.has(p.id)) state.groupsOpen.delete(p.id); else state.groupsOpen.add(p.id); renderLibrary(); } },
      sw, h("span", { text: (open ? "\u25BE " : "\u25B8 ") + p.name }), h("span", { class: "n", text: items.length })));
    if (!open) continue;
    for (const a of items) {
      out.push(h("div", {
        class: "cmd", title: a.description + "\nClick to add, or drag onto the canvas.", tabindex: "0", draggable: "true", "data-action": p.id + "/" + a.id,
        onclick: () => addStep(p.id + "/" + a.id),
        onkeydown: (e) => { if (e.key === "Enter") addStep(p.id + "/" + a.id); },
        ondragstart: (e) => { e.dataTransfer.setData("text/x-ooa-command", p.id + "/" + a.id); e.dataTransfer.effectAllowed = "copy"; document.body.classList.add("dnd"); },
        ondragend: () => document.body.classList.remove("dnd"),
      }, a.name));
    }
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
  if (!state.flow) { toast("Open or create a bot first (Explorer tab).", true); return; }
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

function makeSlot(listPath, index, depth) {
  const slot = h("div", { class: "slot", "data-list": JSON.stringify(listPath), "data-index": index });
  slot.style.marginLeft = depth * 24 + "px";
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
function iconFor(spec) {
  const name = spec ? spec.action.name : "?";
  return name.replace(/[^A-Za-z]/g, "").slice(0, 2).toUpperCase() || "?";
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
  if (!state.flow) { toast("Open a bot first.", true); return; }
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
    toast("Copied " + steps.length + " step(s) to the shared clipboard; paste them into any bot.");
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
    item("Delete" + (n > 1 ? " " + n + " steps" : ""), "Del", () => deleteSelection()));
  menu.style.left = Math.min(e.clientX, window.innerWidth - 240) + "px";
  menu.style.top = Math.min(e.clientY, window.innerHeight - 320) + "px";
  document.body.appendChild(menu);
  menu.querySelector("button").focus();
}

function closeContextMenu() { const m = $("ctx-menu"); if (m) m.remove(); }

function renderSteps() {
  if (!state.flow) return;
  const container = $("steps");
  const problemsBy = new Map();
  for (const p of state.problems) if (p.stepId) problemsBy.set(p.stepId, (problemsBy.get(p.stepId) || 0) + 1);
  const collapsed = collapsedSet();

  function list(steps, path, depth, prefix) {
    const out = [makeSlot(path, 0, depth)];
    steps.forEach((s, i) => {
      const p = path.concat(i);
      const spec = state.specs.get(s.action);
      const num = prefix + (i + 1);
      const isSel = state.multi.has(s.id) || (state.multi.size === 0 && sameList(p, state.selected));
      const live = state.liveSteps;
      const liveClass = live.running === s.id ? " running" : live.failed === s.id ? " failed-run" : live.done.has(s.id) ? " done" : "";
      const isContainer = !!SECTIONS[s.action];
      const folded = isContainer && collapsed.has(s.id);
      const bar = h("span", { class: "bar" });
      bar.style.background = spec ? spec.pkg.color : "#999";
      const ico = h("span", { class: "ico", text: iconFor(spec), "aria-hidden": "true" });
      ico.style.background = (spec ? spec.pkg.color : "#999") + "22";
      ico.style.color = spec ? spec.pkg.color : "#999";
      const hiddenCount = folded ? allSteps([s]).length - 1 : 0;
      const row = h("div", {
        class: "step" + (isSel ? " selected" : "") + (s.disabled ? " disabled" : "") + liveClass, tabindex: "0", draggable: "true", "data-step-id": s.id,
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
        bar,
        h("span", { class: "grip", title: "Drag to move", text: "\u22EE\u22EE" }),
        h("span", { class: "num", text: num }),
        isContainer ? h("button", { type: "button", class: "fold", "aria-expanded": String(!folded), "aria-label": folded ? "Expand block" : "Collapse block",
          onclick: (e) => { e.stopPropagation(); toggleFold(s.id); } }, folded ? "\u25B8" : "\u25BE") : null,
        ico,
        h("span", { class: "title", text: spec ? spec.action.name : s.action }),
        h("span", { class: "pkg", text: spec ? spec.pkg.name : s.action.split("/")[0] }),
        h("span", { class: "summary", text: summary(s) }),
        folded && hiddenCount ? h("span", { class: "hidden-pill", text: hiddenCount + (hiddenCount === 1 ? " step hidden" : " steps hidden") }) : null,
        problemsBy.get(s.id) ? h("span", { class: "flag", title: "Has problems; see the Problems tab", text: "!" + problemsBy.get(s.id) }) : null);
      row.style.marginLeft = depth * 26 + "px";
      out.push(row);
      let sections = SECTIONS[s.action] || [];
      if (s.action === "core/if") sections = [["children", "Then"], ...(s.elseIf || []).map((_, k) => [["elseIf", k, "children"], "Else if #" + (k + 1)]), ["else", "Else"]];
      if (!folded) for (const [key, label] of sections) {
        const k = Array.isArray(key) ? key : [key];
        if (getAt(s, k) === undefined) continue;
        const sp = p.concat(k);
        const kids = getAt(s, k);
        const sec = h("div", {
          class: "section" + (sameList(sp, state.target) ? " target" : ""), tabindex: "0",
          title: "Click to add new commands here, or drop a command on it",
          onclick: (e) => { e.stopPropagation(); state.target = sp; state.selected = p; renderSteps(); renderProps(); },
        }, label);
        sec.style.marginLeft = (depth + 1) * 26 + "px";
        sec.addEventListener("dragover", (e) => { e.preventDefault(); sec.classList.add("target"); });
        sec.addEventListener("dragleave", () => sec.classList.toggle("target", sameList(sp, state.target)));
        sec.addEventListener("drop", (e) => { e.preventDefault(); e.stopPropagation(); dropAt(e, sp, kids.length); });
        out.push(sec);
        if (kids.length) out.push(...list(kids, sp, depth + 1, num + "."));
        else { out.push(makeSlot(sp, 0, depth + 1)); const em = h("div", { class: "empty", text: "Empty: drop a command here" }); em.style.marginLeft = (depth + 1) * 26 + "px"; out.push(em); }
      }
      out.push(makeSlot(path, i + 1, depth));
    });
    return out;
  }
  if (!state.flow.steps.length) {
    const zone = h("div", { class: "canvas-empty", text: "This bot has no steps yet. Drag a command here from Commands, click a command, or press Record." });
    zone.addEventListener("dragover", (e) => { e.preventDefault(); });
    zone.addEventListener("drop", (e) => { e.preventDefault(); dropAt(e, ["steps"], 0); });
    container.replaceChildren(zone);
  } else container.replaceChildren(...list(state.flow.steps, ["steps"], 0, ""));
  const total = allSteps(state.flow.steps).length;
  $("canvas-stats").textContent = total + (total === 1 ? " step" : " steps") + " \u00B7 " + state.problems.length + (state.problems.length === 1 ? " problem" : " problems");
  $("insert-hint").textContent = state.target ? "Clicked commands go into the highlighted section."
    : state.selected ? "Clicked commands go after the selected step." : "Clicked commands go at the end.";
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
  if (step.action === "core/loop" && name === "iterator") {
    const iters = [];
    for (const p of state.packages) for (const it of p.iterators || []) iters.push([p.id + "/" + it.id, it.name + " (" + p.name + ")"]);
    control = h("select", { onchange: (e) => set(e.target.value) },
      h("optgroup", { label: "Built in" }, [["times", "N times"], ["while", "While a condition is true"], ["list", "Each item in a list"], ["dictionary", "Each key in a dictionary"], ["table_rows", "Each row in a table"]].map(([v, t]) => h("option", { value: v, text: t }))),
      iters.length ? h("optgroup", { label: "From packages" }, iters.map(([v, t]) => h("option", { value: v, text: t }))) : null);
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
  if (name === "selector" && step.action.startsWith("ooa.browser/")) {
    const pick = h("button", { type: "button", title: "Open the page and click the element to capture its selector", onclick: () => pickSelector((sel) => { set(sel); renderProps(); }) }, "Pick");
    control = h("div", { class: "field-row" }, control, pick);
  }
  const help = [prop.description, prop.examples ? "Example: " + prop.examples[0] : null].filter(Boolean).join("  ");
  return h("div", { class: "field" },
    h("label", {}, (prop["x-ooa-label"] || name), required ? h("span", { class: "req", text: " *" }) : null, h("span", { class: "muted", text: "  " + type })),
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
  ["endsWith", "ends with"], ["matches", "matches pattern (regex)"], ["empty", "is empty"], ["notEmpty", "is not empty"], ["in", "is one of (list)"]];

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
      fields.push(h("input", { type: "text", value: r.left, placeholder: "$variable or value", "aria-label": "Left value", onchange: (e) => { r.left = e.target.value; commit(); } }), op);
      if (!["empty", "notEmpty"].includes(r.op)) fields.push(h("input", { type: "text", value: r.right, placeholder: r.op === "in" ? '["a","b"]' : r.op === "matches" ? "^INV-\\d+$" : "value or $variable", "aria-label": "Right value", onchange: (e) => { r.right = e.target.value; commit(); } }));
    } else if (r.kind === "bool") {
      fields.push(h("input", { type: "text", value: r.left, placeholder: "$variable", "aria-label": "Value", onchange: (e) => { r.left = e.target.value; commit(); } }));
    } else {
      const c = catalog.find((x) => x.addr === r.addr);
      if (!c) fields.push(h("div", { class: "help", text: "Unknown condition " + r.addr + " (is its package installed?)" }));
      else for (const [name, prop] of Object.entries(c.spec.inputs.properties)) {
        const req = (c.spec.inputs.required || []).includes(name);
        fields.push(h("label", { class: "cond-arg" }, h("span", {}, name, req ? h("span", { class: "req", text: " *" }) : null),
          h("input", { type: "text", value: showValue(r.args[name]), placeholder: prop.default !== undefined ? "default: " + prop.default : prop["x-ooa-type"] || "",
            title: prop.description || "", onchange: (e) => { const v = parseLoose(e.target.value); if (v === undefined || e.target.value === "") delete r.args[name]; else r.args[name] = v; commit(); } })));
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
    box.replaceChildren(h("p", { class: "desc", text: state.flow ? "Select a step on the canvas to edit its settings." : "Open a bot to start." }));
    return;
  }
  const step = getAt(state.flow, state.selected);
  if (!step) { state.selected = null; return renderProps(); }
  const spec = state.specs.get(step.action);
  const parts = [];
  parts.push(h("h2", { class: "props-head", text: spec ? spec.action.name : step.action }));
  parts.push(h("div", { class: "desc", text: (spec ? spec.pkg.name + " \u00B7 " : "") + step.action }));
  parts.push(h("p", { class: "desc", text: spec ? spec.action.description : "Unknown command" }));

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
        } }, h("option", { value: "", text: "(do not store)" }), varNames().map((n) => h("option", { value: n, text: n })));
        sel.value = (step.out || {})[oname] || "";
        const mk = h("button", { type: "button", title: "Create a new variable for this output", onclick: () => {
          const n = addVariable(oname, o["x-ooa-type"]);
          step.out = step.out || {}; step.out[oname] = n;
          changed(); renderProps(); renderVariables();
          toast("Created variable " + n);
        } }, "+ New");
        return h("div", { class: "field" }, h("label", {}, oname, h("span", { class: "muted", text: "  " + o["x-ooa-type"] })),
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
      onchange: (e) => { if (e.target.checked) step.continueOnError = true; else delete step.continueOnError; changed(); } }), "Continue the bot if this step fails"),
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

function builtinSection(title, prefix, values, note) {
  const rows = Object.entries(values || {}).map(([k, v]) => h("tr", {},
    h("td", {}, h("code", { text: prefix + k })), h("td", { class: "muted", text: typeof v === "object" ? JSON.stringify(v) : String(v) }),
    h("td", {}, h("button", { type: "button", class: "link-btn", title: "Insert into the field you last edited", onclick: () => insertReference(prefix + k) }, "Insert"))));
  return h("details", { class: "acc", open: true }, h("summary", { text: title }),
    h("div", { class: "acc-body" }, note ? h("div", { class: "help", text: note }) : null,
      rows.length ? h("table", { class: "grid" }, h("tbody", {}, rows)) : h("div", { class: "empty", text: "None defined." })));
}

function renderVariables() {
  if (!state.builtins) { state.builtins = { sys: {}, global: {} }; loadBuiltins(); }
  if (!state.flow) { $("side-variables").replaceChildren(h("div", { class: "side-head" }, h("span", { text: "Variables" })), h("p", { class: "desc", text: "Open a bot to see its variables." })); return; }
  const vars = state.flow.variables;
  const rows = vars.map((v, i) => {
    const upd = (k, val) => { if (val === undefined || val === "" || val === false) delete v[k]; else v[k] = val; changed(); };
    const type = h("select", { onchange: (e) => upd("type", e.target.value) }, TYPES.map((t) => h("option", { value: t, text: t })));
    type.value = v.type;
    const dir = h("select", { onchange: (e) => { upd("direction", e.target.value === "local" ? undefined : e.target.value); renderVariables(); } },
      ["local", "input", "output"].map((d) => h("option", { value: d, text: d })));
    dir.value = v.direction || "local";
    return h("tr", {},
      h("td", {}, h("input", { type: "text", value: v.name, onchange: (e) => {
        const n = e.target.value.trim();
        if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(n)) { toast("Variable names start with a letter or _ and use letters, digits and _.", true); e.target.value = v.name; return; }
        v.name = n; changed();
      } })),
      h("td", {}, type), h("td", {}, dir),
      h("td", {}, v.direction === "input" ? h("input", { type: "checkbox", checked: !!v.required, title: "Required input", onchange: (e) => upd("required", e.target.checked) }) : ""),
      h("td", {}, v.type === "Credential" ? h("span", { class: "muted", text: "from vault" })
        : h("input", { type: "text", value: showValue(v.default), placeholder: "default", onchange: (e) => upd("default", parseLoose(e.target.value)) })),
      h("td", {}, h("input", { type: "text", value: v.description || "", placeholder: "description", onchange: (e) => upd("description", e.target.value) })),
      h("td", {}, h("button", { type: "button", class: "danger", title: "Delete variable", onclick: () => { vars.splice(i, 1); changed(); renderVariables(); } }, "x")));
  });
  $("side-variables").replaceChildren(h("div", { class: "side-head" }, h("span", { text: "Variables" }), h("button", { type: "button", class: "link-btn", onclick: loadBuiltins }, "Refresh")),
    builtinSection("System variables", "$sys.", state.builtins.sys, "Read-only values provided by the platform at run time, e.g. today's date or the machine name."),
    builtinSection("Global values", "$global.", state.builtins.global, "Shared by all bots. Edit global-values.json in the workspace (managed in Control Room in Part 2)."),
    h("div", { class: "side-head", text: "Bot variables" }),
    h("p", { class: "desc", text: "Input variables are supplied when the bot runs (and become its inputs as a child bot or agent tool). Output variables are returned at the end." }),
    h("div", { class: "side-scroll" }, h("table", { class: "grid" }, h("thead", {}, h("tr", {}, ["Name", "Type", "Direction", "Req.", "Default", "Description", ""].map((t) => h("th", { text: t })))), h("tbody", {}, rows))),
    h("div", { class: "row-actions" }, h("button", { type: "button", onclick: () => { addVariable("var" + (vars.length + 1), "String"); changed(); renderVariables(); } }, "+ Add variable")));
}

function renderSettings() {
  const f = state.flow;
  const field = (label, control, help) => h("div", { class: "field" }, h("label", { text: label }), control, help ? h("div", { class: "help", text: help }) : null);
  const kind = h("select", { onchange: (e) => { f.kind = e.target.value; changed(); } }, ["TaskBot", "ApiTask"].map((k) => h("option", { value: k, text: k })));
  kind.value = f.kind;
  $("tab-settings").replaceChildren(
    field("Bot id", h("input", { type: "text", value: f.id, onchange: (e) => { f.id = e.target.value.trim(); changed(); } }), "Used by child bots and agents to call this bot."),
    field("Name", h("input", { type: "text", value: f.name, onchange: (e) => { f.name = e.target.value; changed(); } })),
    field("Kind", kind, "TaskBot can drive a desktop; ApiTask runs headless in a container."),
    field("Description", h("textarea", { rows: 3, value: f.description || "", onchange: (e) => { f.description = e.target.value; changed(); } }),
      "Describe what the bot does. AI agents read this to decide when to use the bot."),
    field("File", h("input", { type: "text", value: state.file, disabled: true }), "Rename or move the bot in the Explorer."));
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
  if (!state.flow) return;
  const inputs = state.flow.variables.filter((v) => v.direction === "input");
  const form = inputs.map((v) => h("div", { class: "field" },
    h("label", {}, v.name, v.required ? h("span", { class: "req", text: " *" }) : null, h("span", { class: "muted", text: "  " + v.type })),
    h("input", { type: "text", "data-input": v.name, placeholder: v.type === "Credential" ? "locker/credential/attribute" : v.default !== undefined ? "default: " + showValue(v.default) : "" }),
    v.description ? h("div", { class: "help", text: v.description }) : null));
  const left = h("div", {}, h("div", { class: "pane-title", text: "Inputs" }),
    ...(form.length ? form : [h("div", { class: "empty", text: "This bot has no input variables." })]),
    h("div", { class: "row-actions" }, h("button", { type: "button", class: "primary", onclick: runBot }, "Run bot")));
  const right = h("div", { id: "run-result" });
  $("ctab-run").replaceChildren(h("div", { class: "run-grid" }, left, right));
  renderRunResult();
}

function collectInputs() {
  const out = {};
  for (const el of document.querySelectorAll("[data-input]")) {
    const v = state.flow.variables.find((x) => x.name === el.dataset.input);
    if (el.value.trim() === "") continue;
    out[v.name] = v.type === "Credential" ? { $credential: el.value.trim() } : v.type === "String" || v.type === "File" ? el.value : parseLoose(el.value);
  }
  return out;
}

async function runBot() {
  if (!state.flow) { toast("Open a bot first.", true); return; }
  if (state.running) return;
  state.running = true; state.runStarted = Date.now();
  state.live = { id: null, state: "starting", events: [], seq: 0, total: allSteps(state.flow.steps).length, finished: 0, current: null };
  state.liveSteps = { running: null, done: new Set(), failed: null };
  $("btn-run").disabled = true;
  $("run-progress").hidden = false;
  if (document.body.classList.contains("no-dock")) setPref("dock", true);
  if (!document.body.classList.contains("dock-mini")) switchConsole("run");
  renderLive();
  try {
    const start = await api("POST", "runs", { flow: state.flow, inputs: collectInputs() });
    state.live.id = start.run_id;
    while (true) {
      const r = await api("GET", "runs/" + state.live.id + "?after=" + state.live.seq);
      for (const e of r.events) applyLiveEvent(e);
      state.live.state = r.state; state.live.elapsed = r.elapsed_ms;
      renderLive(); renderSteps(); updateMini();
      if (r.result) {
        state.lastRun = { result: r.result, events: state.live.events };
        if (r.result.status === "success") state.liveSteps.running = null;
        renderRunResult(); renderEvents(); renderVars(); renderSteps();
        toast("Run " + r.result.status + " in " + r.result.durationMs + " ms", r.result.status !== "success");
        break;
      }
      await new Promise((res) => setTimeout(res, 400));
    }
  } catch (e) {
    toast(e.message, true);
    if (e.problems) { state.problems = e.problems; renderProblems(); switchConsole("problems"); }
  } finally {
    state.running = false; $("btn-run").disabled = false; $("run-progress").hidden = true;
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
      : h("div", { class: "empty", text: "Run the bot to see variable values. Credentials always show as ****." }),
    Object.keys(outputs).length ? h("div", {}, h("div", { class: "side-head", text: "Outputs" }), h("pre", { text: JSON.stringify(outputs, null, 2) })) : null);
}

function renderRunResult() {
  const box = $("run-result");
  if (!box) return;
  if (!state.lastRun) { box.replaceChildren(h("div", { class: "empty", text: "Run the bot to see its result here. Step-by-step details appear in the Run log tab." })); return; }
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
  for (const t of ["steps", "settings", "json"]) $("tab-" + t).hidden = t !== name;
  $("canvas-bar").hidden = name !== "steps";
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
  if (!state.flow) { toast("Open or create a bot first.", true); return; }
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
      items.length ? h("div", { class: "row-actions" }, items) : h("p", { class: "desc", text: "No triggers yet. Triggers start bots automatically while Studio is running." }),
      h("div", { class: "side-head", text: "New trigger" }),
      h("div", { class: "form-box" }, h("label", {}, "Name", f.name), h("label", {}, "Bot", f.bot), h("label", {}, "When", f.type)),
      fileBox, schedBox, h("div", { class: "row-actions" }, add),
      h("div", { class: "side-head", text: "Recent activity" }),
      logRows.length ? h("div", {}, logRows) : h("p", { class: "desc", text: "Nothing has fired yet." })));
}

async function save() {
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

// ---------- capture (pick an element in the browser) ----------
function defaultUrl() {
  const open = state.flow && allSteps(state.flow.steps).find((x) => x.action === "ooa.browser/open" && x.args && typeof x.args.url === "string" && !x.args.url.startsWith("$"));
  return open ? open.args.url : "https://";
}

async function pickSelector(done) {
  const url = prompt("Open which page to pick the element from?", defaultUrl());
  if (!url || url === "https://") return;
  toast("Click the element in the browser window that opens.");
  try { const r = await api("POST", "capture", { url, browser: "edge" }); done(r.selector, r.element); toast("Captured " + r.selector); }
  catch (e) { toast(e.message, true); }
}

function captureStep() {
  if (!state.flow) { toast("Open or create a bot first.", true); return; }
  pickSelector((selector, el) => {
    const tag = (el && el.tag) || "";
    const action = /^(input|textarea)$/.test(tag) && !/^(checkbox|radio|submit|button)$/.test((el && el.type) || "") ? "set_text"
      : tag === "select" ? "select_by_text" : el && el.type === "checkbox" ? "check" : "click";
    const step = { id: freshIds([{ id: "" }])[0].id, action: "ooa.browser/element_action", args: { selector, action }, description: "Captured " + tag + (el && el.text ? ": " + el.text.slice(0, 30) : "") };
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
        h("span", { class: "icon bot" }), h("div", {}, h("div", { text: x.path.replace(/\.flow\.json$/, "") }), h("div", { class: "help", text: "Matches " + x.matches.join(", ") }))))
        : [h("div", { class: "empty", text: "No bots match \u201C" + q + "\u201D." })]));
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
  $("tree-search").addEventListener("input", onTreeSearch);
  document.addEventListener("focusin", (e) => { if (e.target.closest && e.target.closest("#props") && /^(INPUT|TEXTAREA)$/.test(e.target.tagName)) state.lastInput = e.target; });
  document.addEventListener("click", (e) => { if (!e.target.closest("#ctx-menu")) closeContextMenu(); });
  makeDockDraggable();
  $("cmd-search").addEventListener("input", renderLibrary);
  $("btn-fold-groups").addEventListener("click", () => {
    if (state.groupsOpen.size) state.groupsOpen.clear(); else for (const p of state.packages) state.groupsOpen.add(p.id);
    renderLibrary();
  });
  $("btn-new-bot").addEventListener("click", newBotHere);
  $("btn-new-folder").addEventListener("click", newFolderHere);
  $("btn-rename").addEventListener("click", renameSelected);
  $("btn-delete").addEventListener("click", deleteSelectedNode);
  $("btn-refresh").addEventListener("click", () => loadTree().catch((e) => toast(e.message, true)));
  $("btn-save").addEventListener("click", save);
  $("btn-validate").addEventListener("click", () => { setPref("dock", true); switchConsole("problems"); validate(true); });
  $("btn-run").addEventListener("click", runBot);
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
  document.addEventListener("click", (e) => { if (!e.target.closest(".view-menu")) { $("view-pop").hidden = true; $("btn-view").setAttribute("aria-expanded", "false"); } });
  $("btn-json-apply").addEventListener("click", () => {
    try {
      const f = JSON.parse($("json-text").value);
      if (typeof f !== "object" || !f || Array.isArray(f)) throw new Error("The bot must be a JSON object.");
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
    if (e.key === "Escape") { closeContextMenu(); if (state.multi.size) { state.multi = new Set(); state.selected = null; renderSteps(); renderProps(); updateEditButtons(); } return; }
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test((document.activeElement || {}).tagName || "");
    if (typing || !state.flow) return;
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
