// License Studio: issue, list and check ONEOFFICEAUTOMATION license files (vendor only; served on 127.0.0.1).
"use strict";

const $ = (id) => document.getElementById(id);
const SEATS = [["developer", "Developer"], ["citizen_developer", "Citizen Developer"], ["attended_runner", "Attended Runner"], ["unattended_runner", "Unattended Runner"]];
const STATE = { active: "Active", expiring: "Expiring soon", grace: "In grace period", expired: "Expired", invalid: "Not valid" };

// the access token comes in the address the server printed (#t=...) and stays for this browser tab only
let token = "";
try { token = sessionStorage.getItem("ooa.licenseStudio.token") || ""; } catch (_) { /* storage unavailable */ }
const hashToken = new URLSearchParams(location.hash.slice(1)).get("t");
if (hashToken) { token = hashToken; try { sessionStorage.setItem("ooa.licenseStudio.token", token); } catch (_) { /* storage unavailable */ } history.replaceState(null, "", location.pathname); }
// a new address with a token (pasted into the same tab) connects without a reload
window.addEventListener("hashchange", () => { const t = new URLSearchParams(location.hash.slice(1)).get("t"); if (t) { try { sessionStorage.setItem("ooa.licenseStudio.token", t); } catch (_) { /* storage unavailable */ } location.reload(); } });

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "text") el.textContent = v; else if (k === "class") el.className = v; else if (k.startsWith("on")) el.addEventListener(k.slice(2), v); else el.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c.nodeType ? c : String(c));
  return el;
}

async function api(method, path, body) {
  const r = await fetch("/api/" + path, { method, headers: { "Content-Type": "application/json", "X-Studio-Token": token }, body: body ? JSON.stringify(body) : undefined });
  if (path.startsWith("download")) { if (!r.ok) throw new Error((await r.json()).error.message); return r.blob(); }
  const j = await r.json();
  if (!r.ok) throw new Error(j.error ? j.error.message + (j.error.suggestedFix ? " " + j.error.suggestedFix : "") : "Request failed");
  return j;
}

function toast(text, bad) {
  const t = $("toast"); t.textContent = text; t.className = "toast" + (bad ? " bad" : ""); t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => { t.hidden = true; }, 4000);
}

function saveFile(name, blob) {
  const a = h("a", { href: URL.createObjectURL(blob), download: name });
  document.body.append(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

// ---------- tabs ----------
function showTab(name) {
  for (const b of document.querySelectorAll(".tabs button")) b.setAttribute("aria-selected", String(b.dataset.tab === name));
  for (const s of document.querySelectorAll(".tab-body")) s.hidden = s.id !== "tab-" + name;
  if (name === "issued") loadIssued();
}
for (const b of document.querySelectorAll(".tabs button")) b.addEventListener("click", () => showTab(b.dataset.tab));

// ---------- key status ----------
async function loadInfo() {
  try {
    const i = await api("GET", "info");
    const pill = $("key-status");
    if (i.key_matches_product) { pill.className = "pill good"; pill.textContent = "Signing key ready"; }
    else if (!i.key_present) { pill.className = "pill bad"; pill.textContent = "No signing key"; $("key-note").textContent = "No private key at " + i.key_file + ". Start License Studio with --key pointing at your private.key."; $("key-note").hidden = false; }
    else { pill.className = "pill bad"; pill.textContent = "Wrong signing key"; $("key-note").textContent = "The key at " + i.key_file + " is not the pair of the key built into the product, so its licenses would be refused."; $("key-note").hidden = false; }
    pill.title = i.key_file;
    $("issued-folder").textContent = "Copies of issued licenses: " + i.issued_folder;
    $("issue-btn").disabled = !i.key_matches_product;
  } catch (e) {
    $("key-status").className = "pill bad"; $("key-status").textContent = "Not connected";
    $("key-note").textContent = e.message + " (open the address shown in the window where License Studio started)."; $("key-note").hidden = false;
    $("issue-btn").disabled = true;
  }
}

// ---------- issue ----------
const iso = (d) => d.toISOString().slice(0, 10);
function addMonths(n) { const d = new Date(); d.setMonth(d.getMonth() + n); return iso(d); }
$("f-expires").value = addMonths(12);
$("f-expires").min = iso(new Date(Date.now() + 864e5));
for (const b of document.querySelectorAll(".quick button")) b.addEventListener("click", () => { $("f-expires").value = addMonths(Number(b.dataset.months)); summary(); });

function form() {
  const seats = {};
  for (const [t] of SEATS) seats[t] = Number($("s-" + t).value || 0);
  return { customer: $("f-customer").value.trim(), edition: $("f-edition").value.trim() || "Enterprise", expires: $("f-expires").value, grace_days: Number($("f-grace").value || 0), seats, notes: $("f-notes").value.trim() };
}

function summary() {
  const f = form();
  const days = f.expires ? Math.round((new Date(f.expires) - new Date(iso(new Date()))) / 864e5) : null;
  const rows = [["Customer", f.customer || "—"], ["Edition", f.edition], ["Valid", "today to " + (f.expires || "—") + (days !== null ? " (" + days + " days)" : "")],
    ["Grace", f.grace_days + " days after expiry"], ...SEATS.map(([t, l]) => [l, String(f.seats[t])]), ["Total seats", String(Object.values(f.seats).reduce((a, b) => a + b, 0))]];
  $("summary").replaceChildren(...rows.flatMap(([k, v]) => [h("dt", { text: k }), h("dd", { text: v })]));
}
$("issue-form").addEventListener("input", summary);
$("issue-form").addEventListener("reset", () => setTimeout(() => { $("f-expires").value = addMonths(12); $("result").hidden = true; summary(); }, 0));

let lastIssued = null;
$("issue-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("issue-error").hidden = true;
  const f = form();
  if (!f.customer) { $("issue-error").textContent = "Give the customer's name."; $("issue-error").hidden = false; return; }
  if (Object.values(f.seats).every((n) => !n)) { $("issue-error").textContent = "Give at least one seat."; $("issue-error").hidden = false; return; }
  $("issue-btn").disabled = true;
  try {
    lastIssued = await api("POST", "issue", f);
    $("result-text").textContent = lastIssued.id + " for " + lastIssued.customer + ", valid until " + lastIssued.expires + ".";
    $("result").hidden = false;
    toast("License " + lastIssued.id + " issued");
    loadIssued();
  } catch (err) { $("issue-error").textContent = err.message; $("issue-error").hidden = false; }
  finally { $("issue-btn").disabled = false; }
});
$("result-download").addEventListener("click", async () => { if (lastIssued) saveFile(lastIssued.file, await api("GET", "download?file=" + encodeURIComponent(lastIssued.file))); });
$("result-copy").addEventListener("click", async () => {
  if (!lastIssued) return;
  try { await navigator.clipboard.writeText(lastIssued.text); toast("License text copied"); } catch (_) { toast("Copy is not allowed here; use Download", true); }
});

// ---------- issued ----------
let issued = [];
async function loadIssued() {
  try { issued = (await api("GET", "licenses")).licenses; } catch (e) { toast(e.message, true); return; }
  $("issued-count").textContent = String(issued.length);
  renderIssued();
}
function renderIssued() {
  const q = $("issued-search").value.trim().toLowerCase();
  const rows = issued.filter((l) => !q || (l.customer + " " + l.id + " " + l.file).toLowerCase().includes(q));
  $("issued-rows").replaceChildren(...(rows.length ? rows.map((l) => h("tr", {},
    h("td", {}, h("strong", { text: l.customer || l.file }), l.notes ? h("div", { class: "sub", text: l.notes }) : null),
    h("td", {}, h("div", { text: l.id || "" }), h("div", { class: "sub", text: l.issued ? "issued " + l.issued : l.problem || "" })),
    h("td", {}, h("div", { text: l.expires || "" }), l.days_left !== undefined ? h("div", { class: "sub", text: l.days_left >= 0 ? l.days_left + " days left" : -l.days_left + " days ago" }) : null),
    h("td", {}, h("span", { class: "pill " + l.state, text: STATE[l.state] || l.state })),
    h("td", { class: "sub", text: l.seats ? SEATS.filter(([t]) => l.seats[t]).map(([t, n]) => l.seats[t] + " " + n).join(", ") : "" }),
    h("td", {}, h("button", { type: "button", class: "link", onclick: async () => saveFile(l.file, await api("GET", "download?file=" + encodeURIComponent(l.file))) }, "Download"),
      l.seats ? h("button", { type: "button", class: "link", onclick: () => renew(l) }, "Renew") : null)))
    : [h("tr", {}, h("td", { class: "empty", colspan: "6", text: q ? "No matches." : "No licenses issued yet." }))]));
}
$("issued-search").addEventListener("input", renderIssued);

/** Renew: the same customer and seats, valid for another year from the old end date (or from today when already past). */
function renew(l) {
  $("f-customer").value = l.customer; $("f-edition").value = l.edition || "Enterprise"; $("f-grace").value = l.grace_days;
  for (const [t] of SEATS) $("s-" + t).value = l.seats[t] || 0;
  const from = new Date(Math.max(Date.now(), new Date(l.expires).getTime())); from.setFullYear(from.getFullYear() + 1);
  $("f-expires").value = iso(from);
  $("f-notes").value = "Renewal of " + l.id;
  $("result").hidden = true;
  showTab("issue"); summary(); $("f-customer").focus();
  toast("Form filled from " + l.id + "; check it and issue");
}

// ---------- check ----------
$("check-btn").addEventListener("click", async () => {
  let text = $("check-text").value.trim();
  const file = $("check-file").files[0];
  if (!text && file) text = (await file.text()).trim();
  if (!text) { toast("Choose or paste a license file", true); return; }
  const out = $("check-result");
  try {
    const l = await api("POST", "verify", { text });
    out.replaceChildren(h("p", { class: "ok", text: "The signature is valid: this product accepts the file." }),
      h("dl", {}, ...[["Customer", l.customer], ["License", l.id], ["Edition", l.edition], ["Valid", l.issued + " to " + l.expires], ["Grace", l.grace_days + " days"],
        ["State", STATE[l.state] || l.state], ...SEATS.map(([t, n]) => [n, String(l.seats[t] || 0)]), l.notes ? ["Notes", l.notes] : null].filter(Boolean).flatMap(([k, v]) => [h("dt", { text: k }), h("dd", { text: v })])));
  } catch (e) { out.replaceChildren(h("p", { class: "error", text: e.message })); }
});

summary();
loadInfo();
loadIssued();
