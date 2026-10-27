// Control Room console browser test (jsdom) against a real Control Room started with: ooa control-room
// Env: CONSOLE_DIR (control-room/src/main/resources/console), CR_URL, CR_ADMIN_PASSWORD (the one-time password printed at first start)
const { JSDOM } = require("jsdom");
const fs = require("fs");
const DIR = process.env.CONSOLE_DIR, BASE = process.env.CR_URL, FIRST = process.env.CR_ADMIN_PASSWORD;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (name, ok, extra) => { console.log((ok ? "  PASS " : "  FAIL ") + name + (ok || extra === undefined ? "" : "  -> " + extra)); if (!ok) failures++; };

async function page() {
  const dom = new JSDOM(fs.readFileSync(DIR + "/index.html", "utf8").replace('<script src="/cr.js"></script>', ""), { url: BASE + "/", runScripts: "dangerously", pretendToBeVisual: true });
  const w = dom.window, d = w.document;
  let cookie = "";
  w.fetch = async (u, o) => {
    const headers = Object.assign({}, (o && o.headers) || {});
    if (cookie) headers.Cookie = cookie;
    const res = await fetch(new URL(u, BASE), Object.assign({}, o, { headers }));
    const sc = res.headers.get("set-cookie");
    if (sc) cookie = sc.split(";")[0].endsWith("=") ? "" : sc.split(";")[0];
    return res;
  };
  w.confirm = () => true; w.alert = () => {};
  const sc = d.createElement("script"); sc.textContent = fs.readFileSync(DIR + "/cr.js", "utf8"); d.body.appendChild(sc);
  const until = async (fn, ms = 15000) => { const t = Date.now(); while (Date.now() - t < ms) { try { if (fn()) return true; } catch (_) {} await sleep(50); } return false; };
  const click = (el) => el.dispatchEvent(new w.MouseEvent("click", { bubbles: true }));
  const submit = (form) => form.dispatchEvent(new w.Event("submit", { bubbles: true, cancelable: true }));
  const btn = (text, root) => [...(root || d).querySelectorAll("button")].find((b) => b.textContent.trim() === text);
  const nav = (text) => click(btn(text, d.getElementById("nav")));
  return { w, d, until, click, submit, btn, nav };
}

(async () => {
  const head = await fetch(BASE + "/");
  check("console pages carry a strict content-security policy", (head.headers.get("content-security-policy") || "").includes("script-src 'self'") && head.headers.get("x-frame-options") === "DENY");
  let P = await page();
  let { d, until, click, submit, btn, nav } = P;
  check("sign-in screen appears first", await until(() => !d.getElementById("login-view").hidden));
  check("no single sign-on button until it is set up", await until(() => d.getElementById("sso-box") && d.getElementById("sso-box").children.length === 0));
  d.getElementById("login-user").value = "admin"; d.getElementById("login-pass").value = "wrong-password";
  submit(d.getElementById("login-form"));
  check("a wrong password is refused with a message", await until(() => !d.getElementById("login-error").hidden && d.getElementById("login-error").textContent.includes("Wrong")));
  d.getElementById("login-pass").value = FIRST;
  submit(d.getElementById("login-form"));
  check("first sign-in forces a new password", await until(() => !d.getElementById("password-view").hidden));
  d.getElementById("pw-current").value = FIRST; d.getElementById("pw-new").value = "short"; d.getElementById("pw-repeat").value = "short";
  submit(d.getElementById("password-form"));
  check("weak passwords are refused", await until(() => !d.getElementById("pw-error").hidden && d.getElementById("pw-error").textContent.includes("12 characters")));
  d.getElementById("pw-new").value = "Console-Passw0rd-1"; d.getElementById("pw-repeat").value = "Console-Passw0rd-1";
  submit(d.getElementById("password-form"));
  check("after the change the console opens", await until(() => !d.getElementById("app").hidden && d.getElementById("main").textContent.includes("Welcome")));
  // every page of the menu: Home, and each section's pages (listed in full in the section's pop-out)
  const menuPages = () => [...d.querySelectorAll("#nav > button:not(.nav-collapse), #nav .nav-fly button")].map((b) => b.textContent);
  const navItems = menuPages();
  const groups = [...d.querySelectorAll("#nav .nav-group")].map((b) => b.textContent.trim());
  check("the menu has short sections: Build, Manage, Operations, Administration, AI", groups.join("|") === "Build|Manage|Operations|Administration|AI", groups.join("|"));
  check("an administrator sees Home and all 26 pages, each once", navItems.length === 27 && new Set(navItems).size === 27 && navItems.includes("Templates") && navItems.includes("Agent tests") && navItems.includes("Dashboards") && navItems.includes("Task inbox") && navItems.includes("One Workflow") && navItems.includes("AI governance")
    && ["Settings", "Roles", "Toolkits", "One Model Connections", "Connections"].every((x) => navItems.includes(x)) && !navItems.includes("Administration"), navItems.join(","));
  const admin = [...d.querySelectorAll('#nav .nav-section[aria-label="Administration"] .nav-fly button')].map((b) => b.textContent);
  check("Administration holds users, roles, licenses, audit and settings", ["Users", "Roles", "Teams", "Licenses", "Settings", "One Audit Trail"].every((x) => admin.includes(x)), admin.join(","));
  nav("Licenses");
  check("Licenses says no license is installed and lists seats, people and runners", await until(() => { const t = d.getElementById("main").textContent; return t.includes("No license installed") && t.includes("Unattended Runner") && t.includes("Install license"); }),
    d.getElementById("main").textContent.slice(0, 300));
  const badLicense = await P.w.fetch("/api/license", { method: "POST", headers: { "Content-Type": "application/json", "X-OOA-Request": "1" }, body: JSON.stringify({ content: "{\"format\":\"ooa-license-1\",\"payload\":\"e30=\",\"signature\":\"AAAA\"}" }) });
  check("a license file with a wrong signature is refused", badLicense.status === 400 && (await badLicense.json()).error.code === "LICENSE.INVALID");
  nav("Home");
  await until(() => d.querySelectorAll("#main .tile").length >= 6);
  check("on Home only the sections show, no section is open", !d.querySelector("#nav .nav-items"));
  click(d.querySelector('#nav .nav-group[data-group="manage"]'));
  check("a section opens to show its pages", await until(() => [...d.querySelectorAll('#nav .nav-section[aria-label="Manage"] .nav-items button')].map((b) => b.textContent).includes("One Agent Vault")));
  click(d.querySelector('#nav .nav-group[data-group="manage"]'));
  check("and closes again", await until(() => !d.querySelector('#nav .nav-section[aria-label="Manage"] .nav-items')));
  const find = d.querySelector("#nav .nav-search input");
  find.value = "vault"; find.dispatchEvent(new P.w.Event("input", { bubbles: true }));
  check("Find a page lists the matching pages", await until(() => [...d.querySelectorAll("#nav .nav-items button")].map((b) => b.textContent).join() === "One Agent Vault"));
  const find2 = d.querySelector("#nav .nav-search input");
  find2.value = ""; find2.dispatchEvent(new P.w.Event("input", { bubbles: true }));
  click(d.querySelector("#nav .nav-collapse"));
  check("the menu can be made narrow and is remembered", d.body.classList.contains("nav-mini") && P.w.localStorage.getItem("ooa.cc.navMini") === "1");
  click(d.querySelector("#nav .nav-collapse"));
  check("the top bar has one user menu with API keys, password and sign out", d.getElementById("user-pop").hidden && d.getElementById("user-pop").contains(d.getElementById("btn-logout"))
    && d.getElementById("user-pop").contains(d.getElementById("btn-apikeys")));
  click(d.getElementById("btn-user"));
  check("the user menu opens and shows the role", !d.getElementById("user-pop").hidden && d.getElementById("user-pop-head").textContent.includes("Administrator"));
  click(d.getElementById("btn-user"));
  check("home shows hours saved", d.getElementById("main").textContent.includes("Hours saved (30 days)"));
  check("home shows the dashboard tiles", d.querySelectorAll(".tile").length >= 6);
  check("home shows the 7-day run chart", await until(() => d.querySelector("#main .chart") && d.getElementById("main").textContent.includes("Runs in the last 7 days")));
  // Home redraws itself: an agent checked in elsewhere shows up, and a deleted one no longer counts, without leaving the page
  const agentsTile = () => { const t = [...d.querySelectorAll("#main .tile")].find((x) => x.querySelector(".l") && x.querySelector(".l").textContent === "Agents in the agent repository"); return t ? t.querySelector(".n").textContent : null; };
  const post = (u, b) => P.w.fetch(u, { method: "POST", headers: { "Content-Type": "application/json", "X-OOA-Request": "1" }, body: JSON.stringify(b) });
  await post("/api/repo/checkin", { path: "Live/Refresh check.flow.json", comment: "live refresh", flow: { kind: "TaskBot", id: "live_refresh", name: "Refresh check", description: "t", variables: [], steps: [] } });
  check("Home updates by itself when an agent is checked in", await until(() => agentsTile() === "1", 12000), agentsTile());
  await post("/api/repo/delete", { path: "Live/Refresh check.flow.json" });
  check("and a deleted agent no longer counts", await until(() => agentsTile() === "0", 12000), agentsTile());
  nav("Agent Runs");
  check("Activity has In progress and History tabs with an empty state", await until(() => d.getElementById("main").textContent.includes("Live") && d.getElementById("main").textContent.includes("Nothing is running")));
  click([...d.querySelectorAll("#main .tabs .tab")].find((b) => b.textContent.startsWith("History")));
  check("History has filters and CSV export", await until(() => d.querySelector('#main select[aria-label="Any status"]') && d.querySelector('#main input[type=date]') && btn("Export CSV")));
  nav("Home");

  nav("Users");
  await until(() => btn("New user"));
  click(btn("New user"));
  await until(() => !d.getElementById("modal").hidden);
  const inputs = d.querySelectorAll("#modal-body input");
  inputs[0].value = "carol.dev"; inputs[1].value = "Carol Dev";
  d.querySelector("#modal-body select").value = "bot_creator";
  click(btn("Create", d.getElementById("modal-actions")));
  check("creating a user shows a one-time temporary password", await until(() => d.getElementById("modal-title").textContent === "User created" && d.querySelector(".secret-box .mono").textContent.length >= 12));
  const carolTemp = d.querySelector(".secret-box .mono").textContent;
  click(btn("Close"));
  check("the new user is listed", await until(() => d.getElementById("main").textContent.includes("carol.dev")));

  nav("Runners");
  await until(() => btn("Register a runner"));
  click(btn("Register a runner"));
  await until(() => btn("Create key", d.getElementById("modal-actions")));
  click(btn("Create key", d.getElementById("modal-actions")));
  check("device registration shows the agent command with a one-time key", await until(() => !d.getElementById("modal").hidden && d.getElementById("modal-body").textContent.includes("agent register --url " + BASE + " --key ooareg_")));
  click(btn("Close"));

  nav("One Agent Vault");
  await until(() => btn("New vault folder"));
  click(btn("New vault folder"));
  await until(() => !d.getElementById("modal").hidden);
  let li = d.querySelectorAll("#modal-body input");
  li[0].value = "finance"; li[1].value = '<img src=x onerror="window.__pwned=1">'; li[2].value = "role:bot_creator";
  click(btn("Create", d.getElementById("modal-actions")));
  check("lockers can be created", await until(() => d.getElementById("main").textContent.includes("finance")));
  check("HTML in a description is shown as text, never run", !d.querySelector("#main img[src=x]") && P.w.__pwned === undefined && d.getElementById("main").textContent.includes("<img src=x"));
  click(btn("Add or update credential"));
  await until(() => !d.getElementById("modal").hidden);
  li = d.querySelectorAll("#modal-body input");
  li[0].value = "erp"; li[1].value = "password"; li[2].value = "S3cret-ERP-pass";
  click(btn("Save", d.getElementById("modal-actions")));
  check("credentials are saved and listed by name only", await until(() => d.getElementById("main").textContent.includes("erp") && !d.body.innerHTML.includes("S3cret-ERP-pass")));

  nav("Shared values");
  await until(() => btn("New shared value"));
  click(btn("New shared value"));
  await until(() => !d.getElementById("modal").hidden);
  d.querySelector("#modal-body input").value = "reports_folder"; d.querySelector("#modal-body textarea").value = '"D:/Reports"';
  click(btn("Save", d.getElementById("modal-actions")));
  check("global values can be added", await until(() => d.getElementById("main").textContent.includes("$global.reports_folder")));

  // ---- Part 4: work queues, runner pools, calendars, administration, API keys ----
  nav("Connections");
  check("Manage has a Connections page for saved connector sign-ins", await until(() => d.getElementById("main").textContent.includes("chosen by name on agent steps") && btn("New connection") && d.querySelector(".crumbs") && d.querySelector(".crumbs").textContent.includes("Manage")));
  nav("Work queues");
  await until(() => btn("New work queue"));
  check("work queues start empty with guidance", d.getElementById("main").textContent.includes("No work queues yet"));
  click(btn("New work queue"));
  await until(() => !d.getElementById("modal").hidden);
  d.querySelector("#modal-body input").value = "Vendor invoices";
  click(btn("Save", d.getElementById("modal-actions")));
  check("a work queue can be created", await until(() => d.getElementById("main").textContent.includes("Vendor invoices")));
  click([...d.querySelectorAll("#main tr.clickable")].find((tr) => tr.textContent.includes("Vendor invoices")));
  await until(() => !d.getElementById("drawer").hidden && btn("Add items"));
  click(btn("Add items"));
  await until(() => !d.getElementById("modal").hidden);
  d.querySelector("#modal-body textarea").value = "reference,vendor,amount\nINV-1,Acme,10\nINV-2,\"Globex, Inc\",20\n";
  click(btn("Add items", d.getElementById("modal-actions")));
  check("work items are added from CSV and listed", await until(() => d.getElementById("drawer-body").textContent.includes("INV-2") && d.getElementById("drawer-body").textContent.includes("new: 2")));
  check("CSV quoting keeps commas inside values", await until(() => d.getElementById("drawer-body").textContent.includes("Globex, Inc")));
  click(btn("Start processing"));
  check("processing needs an agent and a pool first", await until(() => !d.getElementById("toast").hidden && d.getElementById("toast").textContent.includes("runner pool")));
  click(d.getElementById("drawer-close"));

  nav("Runners");
  await until(() => btn("Runner pools"));
  click(btn("Runner pools"));
  check("runners have a Runner pools tab", await until(() => btn("New runner pool") && d.getElementById("main").textContent.includes("No runner pools yet")));
  click(btn("New runner pool"));
  await until(() => !d.getElementById("modal").hidden);
  d.querySelector("#modal-body input").value = "Finance runners";
  click(btn("Save", d.getElementById("modal-actions")));
  check("a runner pool can be created", await until(() => d.getElementById("main").textContent.includes("Finance runners")));
  click(btn("Runners", d.querySelector("#main .tabs")));
  await until(() => btn("Register a runner"));

  nav("Schedules");
  await until(() => btn("Holiday calendars"));
  click(btn("Holiday calendars"));
  await until(() => !d.getElementById("modal").hidden && btn("Save calendar"));
  d.querySelector("#modal-body input").value = "India 2026";
  d.querySelector("#modal-body textarea").value = "2026-12-25\n2026-01-26";
  click(btn("Save calendar"));
  check("holiday calendars can be saved", await until(() => d.getElementById("modal-body").textContent.includes("India 2026") && d.getElementById("modal-body").textContent.includes("2 dates")));
  click(btn("Close"));

  nav("Settings");
  check("Settings is its own page under Govern", await until(() => btn("Save settings") && d.querySelector("h1.page").textContent === "Settings"));
  d.querySelector("#main textarea").value = "Maintenance on Saturday";
  click(btn("Save settings"));
  check("an announcement is shown to everyone", await until(() => !d.getElementById("announcement").hidden && d.getElementById("announcement").textContent.includes("Maintenance on Saturday")));
  nav("Roles");
  await until(() => btn("New role"));
  check("built-in roles are listed with their permissions", d.getElementById("main").textContent.includes("Agent builder") && d.getElementById("main").textContent.includes("repo.checkin"));
  click(btn("New role"));
  await until(() => !d.getElementById("modal").hidden);
  d.querySelector("#modal-body input").value = "queue_clerk";
  for (const c of d.querySelectorAll("#modal-body input[type=checkbox]")) if (c.value === "queues.view" || c.value === "queues.work") c.checked = true;
  click(btn("Save role"));
  check("a custom role can be created", await until(() => d.getElementById("main").textContent.includes("queue_clerk") && d.getElementById("main").textContent.includes("queues.work")));
  nav("One Model Connections");
  await until(() => btn("New connection"));
  click(btn("New connection"));
  await until(() => !d.getElementById("modal").hidden);
  const ci = d.querySelectorAll("#modal-body input");
  ci[0].value = "company-gpt"; ci[1].value = "gpt-test"; ci[3].value = "plain-secret-not-a-reference";
  click(btn("Save connection"));
  check("a connection key must be a vault reference", await until(() => !d.getElementById("toast").hidden && d.getElementById("toast").textContent.includes("vault")));
  ci[3].value = "ai/openai/key";
  click(btn("Save connection"));
  check("a model connection can be saved with a vault reference", await until(() => d.getElementById("main").textContent.includes("company-gpt") && d.getElementById("main").textContent.includes("ai/openai/key")));
  nav("Toolkits");
  check("toolkits are listed with usage and status", await until(() => d.getElementById("main").textContent.includes("ooa.string") && btn("Switch off")));

  check("settings include single sign-on and AI assistant (MCP) access", (nav("Settings"), await until(() => d.getElementById("main").textContent.includes("Single sign-on (OpenID Connect)") && d.getElementById("main").textContent.includes("/api/mcp"))));

  nav("Task inbox");
  check("the task inbox opens with an empty state", await until(() => d.getElementById("main").textContent.includes("Nothing waiting for you")));
  nav("One Workflow");
  check("One Workflow explains how to publish workflows", await until(() => d.getElementById("main").textContent.includes("No workflows are checked in yet") && d.getElementById("main").textContent.includes("No workflow runs yet")));
  nav("AI governance");
  check("AI governance shows totals by model and agent", await until(() => d.getElementById("main").textContent.includes("AI calls") && d.getElementById("main").textContent.includes("By model") && btn("Export CSV")));
  nav("One Agent Vault");
  await until(() => btn("Link an external vault"));
  click(btn("Link an external vault"));
  check("a vault folder can be linked to an external vault", await until(() => !d.getElementById("modal").hidden && d.getElementById("modal-body").textContent.includes("HashiCorp Vault") && d.getElementById("modal-body").textContent.includes("Token")));
  d.querySelector("#modal-body select").value = "aws_secrets_manager"; d.querySelector("#modal-body select").dispatchEvent(new P.w.Event("change"));
  check("each provider asks for its own settings", await until(() => d.getElementById("modal-body").textContent.includes("Secret access key") && d.getElementById("modal-body").textContent.includes("Region")));
  click(btn("Close"));

  click(d.getElementById("btn-apikeys"));
  await until(() => !d.getElementById("modal").hidden && btn("Create key"));
  d.querySelector("#modal-body input").value = "ERP sync";
  click(btn("Create key"));
  check("an API key is shown once after creation", await until(() => d.getElementById("modal-title").textContent === "API key created" && d.querySelector(".secret-box .mono").textContent.startsWith("ooak_")));
  const apiKey = d.querySelector(".secret-box .mono").textContent;
  const viaKey = await fetch(BASE + "/api/queues", { headers: { Authorization: "Bearer " + apiKey } });
  check("other systems can call the API with the key", viaKey.status === 200);
  click(btn("Back to keys"));
  check("the key is listed by name only", await until(() => d.getElementById("modal-body").textContent.includes("ERP sync") && !d.getElementById("modal-body").textContent.includes(apiKey)));
  click(btn("Close"));

  nav("Triggers");
  check("the Triggers page has a monitoring column", await until(() => btn("New trigger") && d.getElementById("main").textContent.includes("Start agents on events")) &&
    (d.querySelectorAll("#main th").length === 0 || [...d.querySelectorAll("#main th")].some((th) => th.textContent.includes("Monitoring"))));
  click(btn("New trigger"));
  await until(() => !d.getElementById("modal").hidden);
  const kindSel = d.querySelector('#modal-body select[aria-label="Event"]');
  const kinds = kindSel ? [...kindSel.options].map((o) => o.value) : [];
  check("the New trigger dialog offers every trigger kind", ["file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "window_opened", "window_closed",
    "process_started", "process_stopped", "service_started", "service_stopped", "hotkey", "email", "outlook", "servicenow", "http_poll", "webhook"].every((k) => kinds.includes(k)), kinds.join(","));
  const shown = () => [...d.querySelectorAll("#modal-body .trigger-fields")].filter((b) => !b.hidden).map((b) => [...b.querySelectorAll("[data-key]")].map((i) => i.dataset.key).join(","));
  kindSel.value = "email"; kindSel.dispatchEvent(new P.w.Event("change"));
  check("an email trigger asks for server, user and a vault reference, not a password", shown().length === 1 && shown()[0].includes("host") && shown()[0].includes("password_ref") && !shown()[0].split(",").includes("password"), shown().join("|"));
  kindSel.value = "http_poll"; kindSel.dispatchEvent(new P.w.Event("change"));
  check("the universal poller asks for URL, items path and id field", shown().length === 1 && ["url", "items_path", "id_field", "interval_seconds"].every((k) => shown()[0].includes(k)), shown().join("|"));
  kindSel.value = "hotkey"; kindSel.dispatchEvent(new P.w.Event("change"));
  check("help text follows the chosen kind", d.getElementById("modal-body").textContent.includes("Windows runners with a signed-in desktop"));
  P.w.closeModal ? P.w.closeModal() : click(btn("Cancel") || btn("Close"));

  nav("One Audit Trail");
  await until(() => btn("Verify integrity"));
  check("the audit log lists recent actions", await until(() => d.getElementById("main").textContent.includes("credential.set") && d.getElementById("main").textContent.includes("user.create")));
  click(btn("Verify integrity"));
  check("integrity verification reports an intact chain", await until(() => d.getElementById("main").textContent.includes("Chain intact")));

  click(d.getElementById("btn-logout"));
  check("sign out returns to the sign-in screen", await until(() => !d.getElementById("login-view").hidden));

  P = await page(); ({ d, until, click, submit, btn, nav } = P);
  await until(() => !d.getElementById("login-view").hidden);
  d.getElementById("login-user").value = "carol.dev"; d.getElementById("login-pass").value = carolTemp;
  submit(d.getElementById("login-form"));
  await until(() => !d.getElementById("password-view").hidden);
  d.getElementById("pw-current").value = carolTemp; d.getElementById("pw-new").value = "Carol-Passw0rd-22"; d.getElementById("pw-repeat").value = "Carol-Passw0rd-22";
  submit(d.getElementById("password-form"));
  await until(() => !d.getElementById("app").hidden);
  const carolNav = [...d.querySelectorAll("#nav > button:not(.nav-collapse), #nav .nav-fly button")].map((b) => b.textContent);
  check("a bot creator does not see Users or Audit log", !carolNav.includes("Users") && !carolNav.includes("One Audit Trail") && carolNav.includes("Agent repository"), carolNav.join(","));
  check("an agent builder sees work queues but not Administration", carolNav.includes("Work queues") && !carolNav.includes("Settings") && !carolNav.includes("Roles"), carolNav.join(","));
  check("everyone has a task inbox; AI governance is for auditors", carolNav.includes("Task inbox") && carolNav.includes("One Workflow") && !carolNav.includes("AI governance"), carolNav.join(","));
  check("other users see the announcement", await until(() => !d.getElementById("announcement").hidden && d.getElementById("announcement").textContent.includes("Maintenance")));
  nav("One Agent Vault");
  check("a consumer sees the locker but cannot manage it", await until(() => d.getElementById("main").textContent.includes("finance")) && !btn("Add or update credential"));

  const csrf = await fetch(BASE + "/api/users", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  check("API calls without a session are refused", csrf.status === 401);
  console.log(failures ? failures + " console check(s) FAILED" : "All console checks passed");
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.log("Console test crashed: " + e.stack); process.exit(1); });
