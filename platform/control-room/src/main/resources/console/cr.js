/* ONEOFFICEAUTOMATION Control Room console. All data is rendered with textContent (never innerHTML). */
"use strict";
const $ = (id) => document.getElementById(id);
const S = { user: null, page: "home", timer: null, devices: [], bots: [] };

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
const pill = (s) => h("span", { class: "pill " + s, text: s });

// ---------- views ----------
function showLogin() { stopTimer(); $("app").hidden = true; $("password-view").hidden = true; $("login-view").hidden = false; $("login-user").focus(); }
function showPassword() { $("app").hidden = true; $("login-view").hidden = true; $("password-view").hidden = false; $("pw-current").focus(); }
function showApp() {
  $("login-view").hidden = true; $("password-view").hidden = true; $("app").hidden = false;
  $("who").textContent = S.user.display_name + " (" + S.user.role.replace("_", " ") + ")";
  renderNav(); go(S.page);
}

const PAGES = [
  ["home", "Home", null], ["automation", "Automation", "repo.view"], ["activity", "Activity", "runs.view"], ["devices", "Devices", "devices.view"],
  ["schedules", "Schedules", "schedules.view"], ["triggers", "Triggers", "triggers.view"], ["credentials", "Credentials", null],
  ["globals", "Global values", "globals.view"], ["users", "Users", "users.manage"], ["audit", "Audit log", "audit.view"]];

function renderNav() {
  $("nav").replaceChildren(...PAGES.filter(([, , p]) => !p || can(p)).map(([id, label]) =>
    h("button", { type: "button", class: S.page === id ? "active" : "", "aria-current": S.page === id ? "page" : null, onclick: () => go(id) }, label)));
}

function stopTimer() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }

async function go(page) {
  stopTimer();
  S.page = page; renderNav();
  const main = $("main");
  main.replaceChildren(h("p", { class: "muted", text: "Loading..." }));
  try { await VIEWS[page](main); } catch (e) { main.replaceChildren(h("p", { class: "error", text: e.message })); }
  main.focus();
}

function header(title, sub, ...actions) {
  return [h("h1", { class: "page", text: title }), h("p", { class: "page-sub", text: sub }), actions.length ? h("div", { class: "toolbar" }, actions) : null];
}

function table(cols, rows, onRow) {
  return h("div", { class: "table-wrap" }, h("table", {},
    h("thead", {}, h("tr", {}, cols.map((c) => h("th", { text: c })))),
    h("tbody", {}, rows.length ? rows.map((r) => {
      const tr = h("tr", { class: onRow ? "clickable" : "" }, r.cells.map((c) => h("td", {}, c)));
      if (onRow) { tr.tabIndex = 0; tr.addEventListener("click", () => onRow(r.data)); tr.addEventListener("keydown", (e) => { if (e.key === "Enter") onRow(r.data); }); }
      return tr;
    }) : [h("tr", {}, h("td", { colspan: String(cols.length), class: "muted", text: "Nothing here yet." }))])));
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

function secretBox(label, value, note) {
  return h("div", { class: "secret-box" }, h("strong", { text: label }), h("div", { class: "mono", text: value }), note ? h("div", { class: "muted", text: note }) : null);
}

async function loadRefs() {
  const [b, d] = await Promise.all([can("repo.view") ? api("GET", "repo") : { bots: [] }, can("devices.view") ? api("GET", "devices") : { devices: [] }]);
  S.bots = b.bots; S.devices = d.devices;
}
const botSelect = (val) => { const s = h("select", {}, S.bots.filter((b) => b.production_version).map((b) => h("option", { value: b.path, text: b.path.replace(/\.flow\.json$/, "") }))); if (val) s.value = val; return s; };
const deviceSelect = () => h("select", {}, S.devices.filter((d) => d.enabled).map((d) => h("option", { value: d.id, text: d.name + " (" + d.status + ")" })));
function parseJson(text, fallback) { if (!text.trim()) return fallback; try { return JSON.parse(text); } catch (e) { throw new Error("Invalid JSON: " + e.message); } }

const VIEWS = {
  async home(main) {
    const d = await api("GET", "dashboard");
    const tile = (n, l) => h("div", { class: "card tile" }, h("div", { class: "n", text: String(n) }), h("div", { class: "l", text: l }));
    const parts = [...header("Home", "Welcome, " + S.user.display_name + "."),
      h("div", { class: "tiles" }, tile(d.bots, "Bots in the repository"), tile(d.devices_online + " / " + d.devices_total, "Devices online"), tile(d.running, "Running now"),
        tile(d.queued, "Waiting"), tile(d.success_24h, "Succeeded (24 h)"), tile(d.failed_24h, "Failed (24 h)"), tile(d.schedules, "Active schedules"))];
    if (can("runs.view")) {
      const r = await api("GET", "runs?limit=8");
      parts.push(h("h2", { text: "Recent runs" }), runTable(r.runs));
    }
    main.replaceChildren(...parts);
  },

  async automation(main) {
    await loadRefs();
    const q = h("input", { type: "search", placeholder: "Search bots by name, id or description", "aria-label": "Search bots" });
    const box = h("div");
    const render = async () => {
      const r = await api("GET", "repo?q=" + encodeURIComponent(q.value));
      box.replaceChildren(table(["Bot", "Latest", "Production", "Status", "Updated", ""], r.bots.map((b) => ({ data: b, cells: [
        h("div", {}, h("strong", { text: b.path.replace(/\.flow\.json$/, "") }), h("div", { class: "muted", text: b.description || "" })),
        "v" + b.latest_version, b.production_version ? "v" + b.production_version : "-",
        b.locked_by ? h("span", { class: "pill locked", text: "Checked out by " + b.locked_by }) : h("span", { class: "pill", text: "Checked in" }),
        fmt(b.updated) + " by " + (b.updated_by || ""),
        h("div", { class: "row-actions" },
          h("button", { type: "button", onclick: (e) => { e.stopPropagation(); versionsDialog(b); } }, "Versions"),
          can("runs.start") && b.production_version ? h("button", { type: "button", class: "primary", onclick: (e) => { e.stopPropagation(); runDialog(b); } }, "Run") : null,
          b.locked_by && (b.locked_by === S.user.username || can("repo.production")) ? h("button", { type: "button", onclick: async (e) => {
            e.stopPropagation();
            try { await api("POST", "repo/undo-checkout", { path: b.path }); toast("Check-out undone"); render(); } catch (err) { toast(err.message, true); } } }, "Undo check-out") : null)] }))));
    };
    q.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(render, 250); });
    main.replaceChildren(...header("Automation", "The public repository: bots checked in from Studio, with versions. Studio's private workspace is each developer's own area."), h("div", { class: "toolbar" }, q), box);
    await render();
  },

  async activity(main) {
    let filter = "active", selected = null, after = 0;
    const tabs = h("div", { class: "toolbar" });
    const list = h("div"), details = h("div", { class: "details" });
    const renderTabs = () => tabs.replaceChildren(...[["active", "In progress"], ["history", "History"]].map(([f, l]) =>
      h("button", { type: "button", class: filter === f ? "primary" : "", onclick: () => { filter = f; renderTabs(); refresh(); } }, l)));
    const refresh = async () => {
      const r = await api("GET", "runs?filter=" + filter + "&limit=100");
      list.replaceChildren(runTable(r.runs, (run) => { selected = run.id; after = 0; details.replaceChildren(); refreshDetails(); }));
      if (selected) refreshDetails();
    };
    const refreshDetails = async () => {
      const r = await api("GET", "runs/" + selected);
      const evs = r.events.map((e) => {
        const d = e.data || {};
        const text = e.type === "step.finished" ? "done " + d.action + " (" + d.durationMs + " ms)" : e.type === "step.failed" ? "FAILED " + d.action + " " + d.code + ": " + d.message
          : e.type === "log" ? "log " + d.message : e.type === "variable.set" ? d.name + " = " + d.value : e.type;
        return h("div", { class: e.type === "step.failed" ? "failed" : "" }, (e.stepId ? "[" + e.stepId + "] " : "") + text);
      });
      const active = r.status === "running" || r.status === "queued";
      details.replaceChildren(h("div", { class: "card" },
        h("h2", { text: r.bot_path.replace(/\.flow\.json$/, "") + "  v" + r.version }),
        h("p", {}, pill(r.status), "  on " + (r.device_name || r.device_id) + ", started by " + r.requested_by + " (" + r.source + ")" + (r.duration_ms ? ", " + r.duration_ms + " ms" : "")),
        r.error ? h("p", { class: "error", text: typeof r.error === "string" ? r.error : JSON.stringify(r.error) }) : null,
        active && can("runs.stop") ? h("div", { class: "row-actions" },
          r.pause_requested ? h("button", { type: "button", onclick: () => control(r.id, "resume") }, "Resume") : h("button", { type: "button", onclick: () => control(r.id, "pause") }, "Pause"),
          h("button", { type: "button", class: "danger", onclick: () => control(r.id, "stop") }, "Stop")) : null,
        r.outputs && r.status !== "running" ? h("div", {}, h("strong", { text: "Outputs" }), h("pre", { class: "events", text: r.outputs })) : null,
        h("strong", { text: "Step log" }), h("div", { class: "events" }, evs.length ? evs : [h("div", { class: "muted", text: "Waiting for the device..." })])));
    };
    const control = async (id, action) => { try { await api("POST", "runs/" + id + "/" + action); toast(action === "stop" ? "Stop requested" : action === "pause" ? "Pause requested" : "Resume requested"); refreshDetails(); } catch (e) { toast(e.message, true); } };
    renderTabs();
    main.replaceChildren(...header("Activity", "Runs on all devices. Select a run to see its live step log."), tabs, list, details);
    await refresh();
    S.timer = setInterval(() => refresh().catch(() => {}), 2500);
  },

  async devices(main) {
    const r = await api("GET", "devices");
    const actions = can("devices.manage") ? [h("button", { type: "button", class: "primary", onclick: async () => {
      try {
        const k = await api("POST", "devices/registration-key", { hours: 24 });
        modal("Register a device", [
          h("p", { text: "On the Windows machine that will run bots, open PowerShell in the platform folder and run:" }),
          secretBox("Command", '.\\gradlew.bat :cli:run --args="agent register --url ' + location.origin + " --key " + k.key + '"', "The key works once and expires in 24 hours. It is shown only now."),
          h("p", { class: "muted", text: "Then start the agent with:  .\\gradlew.bat :cli:run --args=\"agent start\"" })], []);
      } catch (e) { toast(e.message, true); }
    } }, "Register a device")] : [];
    main.replaceChildren(...header("Devices", "Machines running the Bot Agent. A device is online when it checked in during the last minute.", ...actions),
      table(["Device", "Status", "Running", "Agent", "Last seen", "Registered", ""], r.devices.map((d) => ({ data: d, cells: [
        h("div", {}, h("strong", { text: d.name }), h("div", { class: "muted", text: (d.hostname || "") + " " + (d.os || "") })), pill(d.status), String(d.running),
        d.agent_version || "", fmt(d.last_seen), fmt(d.registered) + " by " + (d.registered_by || ""),
        can("devices.manage") ? h("button", { type: "button", onclick: async () => { try { await api("PATCH", "devices/" + d.id, { enabled: !d.enabled }); go("devices"); } catch (e) { toast(e.message, true); } } }, d.enabled ? "Disable" : "Enable") : ""] }))));
    S.timer = setInterval(() => { if (S.page === "devices" && $("modal").hidden) go("devices"); }, 15000);
  },

  async schedules(main) {
    await loadRefs();
    const r = await api("GET", "schedules");
    const when = (s) => s.kind === "every" ? "Every " + s.every_minutes + " min" : s.kind === "daily" ? "Daily at " + s.at_time : s.days + " at " + s.at_time;
    main.replaceChildren(...header("Schedules", "Run bots automatically. Scheduled runs use the vault permissions of the person who created the schedule.",
      can("schedules.manage") ? h("button", { type: "button", class: "primary", onclick: scheduleDialog }, "New schedule") : null),
      table(["Schedule", "Bot", "Device", "When", "Next run", "Enabled", ""], r.schedules.map((s) => ({ data: s, cells: [
        s.name, s.bot_path.replace(/\.flow\.json$/, ""), (S.devices.find((d) => d.id === s.device_id) || {}).name || s.device_id, when(s) + " (" + s.time_zone + ")", fmt(s.next_run),
        s.enabled ? "Yes" : "No",
        can("schedules.manage") ? h("div", { class: "row-actions" },
          h("button", { type: "button", onclick: async () => { await api("PATCH", "schedules/" + s.id, { enabled: !s.enabled }); go("schedules"); } }, s.enabled ? "Disable" : "Enable"),
          h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete schedule " + s.name + "?")) { await api("DELETE", "schedules/" + s.id); go("schedules"); } } }, "Delete")) : ""] }))));
  },

  async triggers(main) {
    await loadRefs();
    const r = await api("GET", "triggers");
    main.replaceChildren(...header("Triggers", "Start bots on events: a file arriving in a folder on a device, or a call to a webhook. Studio also has its own local triggers for development.",
      can("triggers.manage") ? h("button", { type: "button", class: "primary", onclick: triggerDialog }, "New trigger") : null),
      table(["Trigger", "Kind", "Bot", "Device", "Details", "Enabled", ""], r.triggers.map((t) => ({ data: t, cells: [
        t.name, t.kind, t.bot_path.replace(/\.flow\.json$/, ""), (S.devices.find((d) => d.id === t.device_id) || {}).name || t.device_id,
        t.kind === "file" ? t.folder + " / " + t.pattern : "POST " + location.origin + "/api/hooks/" + t.id, t.enabled ? "Yes" : "No",
        can("triggers.manage") ? h("div", { class: "row-actions" },
          h("button", { type: "button", onclick: async () => { await api("PATCH", "triggers/" + t.id, { enabled: !t.enabled }); go("triggers"); } }, t.enabled ? "Disable" : "Enable"),
          h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete trigger " + t.name + "?")) { await api("DELETE", "triggers/" + t.id); go("triggers"); } } }, "Delete")) : ""] }))));
  },

  async credentials(main) {
    const r = await api("GET", "lockers");
    const lockers = r.lockers.map((l) => h("div", { class: "card locker" },
      h("h2", { text: l.name }), h("p", { class: "muted", text: (l.description || "") + "  Owners: " + l.owners + ".  Consumers: " + (l.consumers || "none") + "." }),
      table(["Credential", "Attribute", "Updated", ""], l.credentials.map((c) => ({ data: c, cells: [c.name, c.attribute, fmt(c.updated) + " by " + c.updated_by,
        l.can_manage ? h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + c.name + "/" + c.attribute + "?")) { await api("DELETE", "lockers/" + l.name + "/" + c.name + "/" + c.attribute); go("credentials"); } } }, "Delete") : ""] }))),
      l.can_manage ? h("div", { class: "row-actions" },
        h("button", { type: "button", onclick: () => credentialDialog(l.name) }, "Add or update credential"),
        h("button", { type: "button", onclick: () => consumersDialog(l) }, "Consumers")) : null));
    main.replaceChildren(...header("Credentials", "Encrypted lockers. Values are never shown again after saving; bots get them only while running, and every use is audited.",
      can("lockers.manage") || can("lockers.own") ? h("button", { type: "button", class: "primary", onclick: lockerDialog }, "New locker") : null),
      ...(lockers.length ? lockers : [h("p", { class: "muted", text: "No lockers you own or consume yet." })]));
  },

  async globals(main) {
    const r = await api("GET", "globals");
    main.replaceChildren(...header("Global values", "Shared values every bot can read as $global.name.", can("globals.manage") ? h("button", { type: "button", class: "primary", onclick: () => globalDialog() }, "New global value") : null),
      table(["Name", "Value", "Updated", ""], r.globals.map((g) => ({ data: g, cells: [h("code", { text: "$global." + g.name }), h("code", { text: JSON.stringify(g.value) }), fmt(g.updated) + " by " + g.updated_by,
        can("globals.manage") ? h("div", { class: "row-actions" }, h("button", { type: "button", onclick: () => globalDialog(g) }, "Edit"),
          h("button", { type: "button", class: "danger", onclick: async () => { if (confirm("Delete " + g.name + "?")) { await api("DELETE", "globals/" + g.name); go("globals"); } } }, "Delete")) : ""] }))));
  },

  async users(main) {
    const r = await api("GET", "users");
    const roleSel = (u) => { const s = h("select", { "aria-label": "Role of " + u.username, onchange: async (e) => { try { await api("PATCH", "users/" + u.id, { role: e.target.value }); toast("Role changed"); } catch (err) { toast(err.message, true); go("users"); } } },
      ["admin", "bot_creator", "bot_runner", "viewer"].map((x) => h("option", { value: x, text: x.replace("_", " ") }))); s.value = u.role; return s; };
    main.replaceChildren(...header("Users", "People who can sign in, and their roles.", h("button", { type: "button", class: "primary", onclick: userDialog }, "New user")),
      table(["User", "Role", "Status", "Last sign-in", ""], r.users.map((u) => ({ data: u, cells: [
        h("div", {}, h("strong", { text: u.display_name || u.username }), h("div", { class: "muted", text: u.username })), roleSel(u),
        u.enabled ? (u.must_change ? "Must change password" : "Active") : "Disabled", fmt(u.last_login),
        h("div", { class: "row-actions" },
          h("button", { type: "button", onclick: async () => { try { await api("PATCH", "users/" + u.id, { enabled: !u.enabled }); go("users"); } catch (e) { toast(e.message, true); } } }, u.enabled ? "Disable" : "Enable"),
          h("button", { type: "button", onclick: async () => { if (!confirm("Reset the password of " + u.username + "?")) return; const p = await api("POST", "users/" + u.id + "/reset-password");
            modal("Temporary password", [secretBox(u.username, p.temporary_password, "Give it to the user securely. They must change it at first sign-in. Shown only once.")], []); } }, "Reset password"))] }))));
  },

  async audit(main) {
    const q = h("input", { type: "search", placeholder: "Filter by user, action or object", "aria-label": "Filter audit log" });
    const box = h("div"), status = h("span", { class: "muted" });
    const render = async () => {
      const r = await api("GET", "audit?limit=300&q=" + encodeURIComponent(q.value));
      box.replaceChildren(table(["#", "Time", "Who", "Action", "Object", "Result", "Details"], r.records.map((a) => ({ data: a, cells: [String(a.seq), fmt(a.ts), a.actor, a.action, (a.object_type || "") + " " + (a.object_id || ""),
        h("span", { class: "pill " + (a.result === "ok" ? "success" : "failed"), text: a.result }), h("span", { class: "mono", text: a.details || "" })] }))));
    };
    q.addEventListener("input", () => { clearTimeout(q.t); q.t = setTimeout(render, 250); });
    const verify = h("button", { type: "button", onclick: async () => {
      const v = await api("GET", "audit/verify");
      status.textContent = v.valid ? "Chain intact: " + v.records + " records verified." : "TAMPERING DETECTED at record " + v.broken_at + ".";
      status.className = v.valid ? "muted" : "error";
    } }, "Verify integrity");
    main.replaceChildren(...header("Audit log", "Every sign-in, change, run and credential use. Records are hash-chained, so editing history is detectable."), h("div", { class: "toolbar" }, q, verify, status), box);
    await render();
  },
};

function runTable(runs, onRow) {
  return table(["Status", "Bot", "Device", "Started by", "Source", "Created", "Duration"], runs.map((r) => ({ data: r, cells: [pill(r.status),
    r.bot_path.replace(/\.flow\.json$/, "") + " v" + r.version, r.device_name || r.device_id, r.requested_by, r.source, fmt(r.created), r.duration_ms ? r.duration_ms + " ms" : ""] })), onRow);
}

// ---------- dialogs ----------
async function versionsDialog(b) {
  const r = await api("GET", "repo/versions?path=" + encodeURIComponent(b.path));
  modal("Versions of " + b.path.replace(/\.flow\.json$/, ""), [table(["Version", "Author", "Date", "Comment", ""], r.versions.map((v) => ({ data: v, cells: [
    "v" + v.version + (v.version === b.production_version ? " (production)" : ""), v.author, fmt(v.created), v.comment || "",
    can("repo.production") && v.version !== b.production_version ? h("button", { type: "button", onclick: async () => {
      try { await api("POST", "repo/production", { path: b.path, version: v.version }); toast("Production is now v" + v.version); closeModal(); go("automation"); } catch (e) { toast(e.message, true); } } }, "Set as production") : ""] })))], []);
}

async function runDialog(b) {
  if (!S.devices.length) await loadRefs();
  const dev = deviceSelect();
  const inputs = h("textarea", { placeholder: '{"vendor": "Acme", "api_key": {"$credential": "finance/vendor_api/key"}}' });
  modal("Run " + b.path.replace(/\.flow\.json$/, ""), [h("label", {}, "Device", dev), h("label", {}, "Inputs (JSON, optional)", inputs),
    h("p", { class: "muted", text: "Credential inputs are vault references, never the secret itself." })],
    [h("button", { type: "button", class: "primary", onclick: async () => {
      try { const r = await api("POST", "runs", { bot_path: b.path, device_id: dev.value, inputs: parseJson(inputs.value, {}) }); closeModal(); toast("Queued " + r.run_id); go("activity"); }
      catch (e) { toast(e.message, true); } } }, "Run now")]);
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
        const r = await api("POST", "schedules", body); closeModal(); toast("Next run " + fmt(r.next_run)); go("schedules");
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
  try { S.user = await api("GET", "auth/me"); if (S.user.must_change) showPassword(); else showApp(); } catch (_) { showLogin(); }
}

if (typeof window !== "undefined" && !window.__OOA_TEST__) boot();
