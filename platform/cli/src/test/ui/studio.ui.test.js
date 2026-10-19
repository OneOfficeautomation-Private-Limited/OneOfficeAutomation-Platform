const { JSDOM } = require("jsdom");
const fs = require("fs");
const path = require("path");
const S = process.env.STUDIO_DIR, WS = process.env.OOA_WS, BASE = process.env.OOA_STUDIO_URL || "http://localhost:8710";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (name, ok, extra) => { console.log((ok ? "  PASS " : "  FAIL ") + name + (ok || extra === undefined ? "" : "  -> " + extra)); if (!ok) failures++; };

(async () => {
  const dom = new JSDOM(fs.readFileSync(S + "/index.html", "utf8").replace('<script src="studio.js"></script>', ""), { url: BASE + "/", runScripts: "dangerously", pretendToBeVisual: true });
  const w = dom.window, d = w.document;
  w.fetch = (u, o) => fetch(new URL(u, BASE), o);
  w.CSS = w.CSS || {}; w.CSS.escape = w.CSS.escape || ((s) => s);
  w.HTMLElement.prototype.scrollIntoView = () => {};
  const answers = [];
  w.prompt = () => answers.shift(); w.confirm = () => true;
  let lastToast = "";
  w.__OOA_NO_AUTOSTART__ = true;
  const sc = d.createElement("script"); sc.textContent = fs.readFileSync(S + "/studio.js", "utf8"); d.body.appendChild(sc);
  new w.MutationObserver(() => { lastToast = d.getElementById("toast").textContent; }).observe(d.getElementById("toast"), { childList: true, characterData: true, subtree: true });
  const until = async (fn, ms = 4000) => { const t = Date.now(); while (Date.now() - t < ms) { try { if (fn()) return true; } catch (_) {} await sleep(40); } return false; };
  const click = (el) => el.dispatchEvent(new w.MouseEvent("click", { bubbles: true }));
  const change = (el, v) => { el.value = v; el.dispatchEvent(new w.Event("change", { bubbles: true })); };
  const node = (p) => d.querySelector('.node[data-path="' + p + '"]');
  const fsx = (rel) => fs.existsSync(path.join(WS, rel));
  // HTML5 drag and drop with a shared DataTransfer, as a browser does
  function drag(src, dst) {
    const store = {};
    const dt = { setData: (k, v) => { store[k] = v; }, getData: (k) => store[k] || "", effectAllowed: "", dropEffect: "" };
    const fire = (el, type) => { const e = new w.Event(type, { bubbles: true, cancelable: true }); Object.defineProperty(e, "dataTransfer", { value: dt }); el.dispatchEvent(e); };
    fire(src, "dragstart"); fire(dst, "dragover"); fire(dst, "drop"); fire(src, "dragend");
  }
  const flow = () => w.eval("state.flow");
  const actions = (list) => list.map((s) => s.action);

  w.init();
  await until(() => d.querySelectorAll(".pkg-head").length > 10);
  click(d.getElementById("btn-fold-groups"));
  // ---- branding and explorer ----
  check("logo shown in header", d.querySelector(".topbar img.logo").getAttribute("src") === "logo-mark.png");
  const logoRes = await fetch(BASE + "/logo-mark.png");
  check("logo served as PNG", logoRes.status === 200 && logoRes.headers.get("content-type") === "image/png");
  check("explorer shows seeded workspace", await until(() => node("Examples") && node("My Agents") && node("data")));
  check("welcome screen until a bot is opened", !d.getElementById("welcome").hidden);
  click(node("Examples")); click(await (async () => { await until(() => node("Examples/Refunds")); return node("Examples/Refunds"); })());
  check("sub-folders expand", await until(() => node("Examples/Refunds/lookup_order.flow.json")));
  click(node("Examples/Refunds/lookup_order.flow.json"));
  check("clicking a bot opens it", await until(() => w.eval("state.file") === "Examples/Refunds/lookup_order.flow.json" && d.querySelectorAll(".step").length === 3));
  check("breadcrumb shows the path", d.getElementById("crumbs").textContent.includes("Examples / Refunds") && d.getElementById("crumbs").textContent.includes("lookup_order"));
  check("sidebar switches to Commands after opening", !d.getElementById("side-commands").hidden);
  await until(() => d.querySelector('[data-input="order_id"]'));
  d.querySelector('[data-input="order_id"]').value = "ORD-10042";
  click(d.getElementById("btn-run"));
  check("bot in a sub-folder runs (data paths still work)", await until(() => (d.querySelector("#run-result .status") || {}).textContent === "success"),
        d.querySelector("#run-result") && d.querySelector("#run-result").textContent.slice(0, 160));

  // ---- folders, sub-folders, new bot ----
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  click(node("My Agents"));
  answers.push("Invoices"); click(d.getElementById("btn-new-folder"));
  check("folder created", await until(() => node("My Agents/Invoices")) && fsx("My Agents/Invoices"));
  answers.push("2026"); click(d.getElementById("btn-new-folder"));
  check("sub-folder created inside the selected folder", await until(() => node("My Agents/Invoices/2026")) && fsx("My Agents/Invoices/2026"));
  answers.push("Monthly report"); click(d.getElementById("btn-new-bot"));
  check("bot created in the sub-folder and opened", await until(() => w.eval("state.file") === "My Agents/Invoices/2026/Monthly report.flow.json") && fsx("My Agents/Invoices/2026/Monthly report.flow.json"));
  check("new bot shows the empty-canvas drop zone", await until(() => d.querySelector(".canvas-empty")));

  // ---- drag and drop on the canvas ----
  const cmd = (a) => d.querySelector('.cmd[data-action="' + a + '"]');
  drag(cmd("ooa.string/change_case"), d.querySelector(".canvas-empty"));
  check("drag command onto empty canvas", await until(() => flow().steps.length === 1 && flow().steps[0].action === "ooa.string/change_case"));
  const lastSlot = () => [...d.querySelectorAll('.slot')].filter((s) => s.dataset.list === '["steps"]').pop();
  drag(cmd("core/if"), lastSlot());
  check("drag If into the slot after step 1", await until(() => JSON.stringify(actions(flow().steps)) === '["ooa.string/change_case","core/if"]'));
  const thenSection = () => [...d.querySelectorAll(".section")].find((s) => s.textContent === "Then");
  drag(cmd("core/log"), thenSection());
  check("drag command onto the If's Then section", await until(() => flow().steps[1].children.length === 1 && flow().steps[1].children[0].action === "core/log"));
  const firstSlot = () => d.querySelector('.slot[data-list=\'["steps"]\'][data-index="0"]');
  drag(cmd("core/comment"), firstSlot());
  check("drag command to the very top", await until(() => flow().steps[0].action === "core/comment" && flow().steps.length === 3));
  // move an existing step: change_case (now step 2) into the Else section
  const stepEl = (id) => d.querySelector('.step[data-step-id="' + id + '"]');
  const elseSection = () => [...d.querySelectorAll(".section")].find((s) => s.textContent === "Else");
  const ccId = flow().steps[1].id;
  drag(stepEl(ccId), elseSection());
  check("drag a step into the Else section (move, not copy)", await until(() => flow().steps.length === 2 && flow().steps[1].else.length === 1 && flow().steps[1].else[0].id === ccId));
  const ifId = flow().steps[1].id;
  drag(stepEl(ifId), thenSection());
  await sleep(150);
  check("a block cannot be dropped inside itself", flow().steps.length === 2 && flow().steps[1].id === ifId && lastToast.includes("inside itself"), lastToast);
  drag(stepEl(ifId), firstSlot());
  check("drag to reorder steps", await until(() => flow().steps[0].id === ifId && flow().steps[1].action === "core/comment"));
  drag(stepEl(ccId), lastSlot());
  check("drag a nested step back out to the top level", await until(() => flow().steps.length === 3 && flow().steps[2].id === ccId && flow().steps[0].else.length === 0));
  check("clicking still adds commands too", (click(cmd("ooa.number/round")), await until(() => flow().steps.some((s) => s.action === "ooa.number/round"))));

  click(d.getElementById("btn-save"));
  check("saved to the sub-folder file", await until(() => { try { return JSON.parse(fs.readFileSync(path.join(WS, "My Agents/Invoices/2026/Monthly report.flow.json"), "utf8")).steps.length === 4; } catch (_) { return false; } }));

  // ---- rename, move, delete ----
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  await until(() => node("My Agents/Invoices/2026/Monthly report.flow.json"));
  click(node("My Agents/Invoices/2026"));
  answers.push("Q4"); click(d.getElementById("btn-rename"));
  check("rename a folder; the open bot follows it", await until(() => fsx("My Agents/Invoices/Q4/Monthly report.flow.json") && w.eval("state.file") === "My Agents/Invoices/Q4/Monthly report.flow.json"));
  w.eval("state.treeSel = 'My Agents/Invoices/Q4/Monthly report.flow.json'");
  answers.push("Monthly summary"); click(d.getElementById("btn-rename"));
  check("rename a bot (extension kept)", await until(() => fsx("My Agents/Invoices/Q4/Monthly summary.flow.json") && w.eval("state.file").endsWith("Monthly summary.flow.json")));
  await until(() => node("My Agents/Invoices/Q4/Monthly summary.flow.json"));
  drag(node("My Agents/Invoices/Q4/Monthly summary.flow.json"), node("My Agents"));
  check("drag a bot onto a folder to move it", await until(() => fsx("My Agents/Monthly summary.flow.json") && w.eval("state.file") === "My Agents/Monthly summary.flow.json"));
  drag(node("My Agents"), node("My Agents/Invoices"));
  await sleep(200);
  check("a folder cannot be moved into its own sub-folder", fsx("My Agents/Invoices") && !fsx("My Agents/Invoices/My Agents"));
  click(node("My Agents/Invoices"));
  click(d.getElementById("btn-delete"));
  check("delete moves the folder to the workspace trash", await until(() => !fsx("My Agents/Invoices") && !node("My Agents/Invoices")) &&
        fs.readdirSync(path.join(WS, ".trash")).some((f) => f.endsWith("_Invoices")));
  check("trash is hidden from the explorer", !d.querySelector('.node[data-path^=".trash"]'));
  answers.push("../escape"); click(node("My Agents")); click(d.getElementById("btn-new-folder"));
  await sleep(250);
  check("names that try to leave the workspace are refused", !fsx("escape") && lastToast.includes("WORKSPACE.BAD_NAME"), lastToast);

  // ---- server-side path security ----
  const bad1 = await (await fetch(BASE + "/api/bot?path=" + encodeURIComponent("../../outside.flow.json"))).json();
  check("API refuses paths outside the workspace", bad1.error && bad1.error.code === "WORKSPACE.OUTSIDE", JSON.stringify(bad1));
  const bad2 = await (await fetch(BASE + "/api/bot?path=" + encodeURIComponent(".trash/x.flow.json"))).json();
  check("API refuses hidden paths", bad2.error && bad2.error.code === "WORKSPACE.HIDDEN", JSON.stringify(bad2));


  // ================= Option A design features =================
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  await until(() => node("Examples/Refunds/check_refund_policy.flow.json") || (click(node("Examples")), false));
  click(node("Examples/Refunds/check_refund_policy.flow.json"));
  check("open a bot with nested blocks", await until(() => w.eval("state.file") === "Examples/Refunds/check_refund_policy.flow.json"));
  const before = d.querySelectorAll(".step").length;
  check("blocks show fold buttons", d.querySelectorAll(".step .fold").length >= 2);
  click(d.getElementById("btn-collapse-all"));
  check("collapse all hides nested steps and shows a hidden-steps pill", await until(() => d.querySelectorAll(".step").length < before && d.querySelector(".hidden-pill")));
  check("fold state is kept in the bot's ui section (not in its logic)", JSON.stringify(w.eval("state.flow.ui.collapsed")).includes("load"));
  click(d.getElementById("btn-expand-all"));
  check("expand all restores every step", await until(() => d.querySelectorAll(".step").length === before && !w.eval("state.flow.ui")));
  const firstFold = d.querySelector(".step .fold");
  click(firstFold);
  check("a single fold button collapses only its block", await until(() => d.querySelectorAll(".hidden-pill").length === 1));
  click(d.querySelector(".step .fold"));
  click(d.getElementById("tg-left"));
  check("left panel toggle hides the side panel", await until(() => d.body.classList.contains("no-left")));
  click(d.getElementById("tg-left"));
  check("and shows it again", await until(() => !d.body.classList.contains("no-left")));
  click(d.querySelector('.rail-btn.active'));
  check("clicking the active rail icon collapses the panel", await until(() => d.body.classList.contains("no-left")));
  click(d.querySelector('.rail-btn[data-side="variables"]'));
  check("variables open in the side panel", await until(() => !d.body.classList.contains("no-left") && !d.getElementById("side-variables").hidden && d.querySelector("#side-variables .var-scroll")));
  click(d.getElementById("tg-right"));
  check("settings panel toggle", await until(() => d.body.classList.contains("no-right")));
  click(d.getElementById("tg-right"));
  const themeSel = d.getElementById("sel-theme");
  change(themeSel, "midnight");
  check("theme switch (midnight) is applied and remembered", d.body.dataset.theme === "midnight" && JSON.parse(w.localStorage.getItem("ooa.studio.prefs")).theme === "midnight");
  change(themeSel, "brand");
  change(d.getElementById("sel-density"), "compact");
  check("compact density", d.body.classList.contains("compact"));
  change(d.getElementById("sel-density"), "comfortable");
  const dockSel = d.getElementById("sel-dock");
  change(dockSel, "right");
  check("dock moves to the right", d.body.classList.contains("dock-right") && d.getElementById("dock").parentElement.id === "workbench");
  change(dockSel, "float");
  check("dock floats", d.body.classList.contains("dock-float"));
  change(dockSel, "mini");
  check("dock minimises to a mini bar with status", d.body.classList.contains("dock-mini") && d.getElementById("mini-status").textContent.length > 0);
  click([...d.querySelectorAll("#mini-status button")].find((b) => b.textContent === "Expand"));
  check("mini bar expands back to the bottom dock", await until(() => d.body.classList.contains("dock-bottom") && d.getElementById("dock").parentElement.id === "center"));
  await until(() => d.querySelector('[data-input="order_id"]'));
  d.querySelector('[data-input="order_id"]').value = "ORD-10042";
  d.querySelector('[data-input="reason"]').value = "damaged";
  click(d.getElementById("btn-run"));
  check("run from the new header button", await until(() => (d.querySelector("#run-result .status") || {}).textContent === "success"));
  click(d.querySelector('.ctab[data-ctab="vars"]'));
  check("Variables tab lists last values after a run", await until(() => d.querySelector("#ctab-vars table") && d.getElementById("ctab-vars").textContent.includes("eligible")));
  click(d.querySelector(".step"));
  check("settings use foldable sections", await until(() => d.querySelectorAll("#props details.acc").length >= 2));

  // ================= Recorder without the browser module =================
  // With the optional Browser module installed, Record and Capture open a real browser, so these checks only run without it.
  const hasBrowser = w.eval("state.packages.some((p) => p.id === 'ooa.browser')");
  if (hasBrowser) console.log("  SKIP record/capture without the Browser module (packages-browser is installed here)");
  else {
    answers.push("https://example.com");
    click(d.getElementById("btn-record"));
    await sleep(600);
    check("record explains when the Browser module is missing", lastToast.includes("STUDIO.NO_BROWSER_MODULE") || lastToast.includes("Inserted"), lastToast);
  }

  // ================= Triggers (server side, end to end) =================
  fs.mkdirSync(path.join(WS, "inbox"), { recursive: true });
  fs.writeFileSync(path.join(WS, "My Agents", "on_file.flow.json"), JSON.stringify({ kind: "TaskBot", id: "on_file", name: "on file", description: "",
    variables: [{ name: "trigger_file", type: "String", direction: "input" }],
    steps: [{ id: "w", action: "ooa.file/write_text", args: { path: "trigger-out.txt", text: "got ${trigger_file}", append: true } }] }));
  const put = async (list) => (await fetch(BASE + "/api/triggers", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(list) })).json();
  const bad = await put([{ id: "t1", name: "x", bot: "../../evil.flow.json", type: "file", folder: "inbox" }]);
  check("triggers reject bots outside the workspace", bad.error && /WORKSPACE|TRIGGER/.test(bad.error.code), JSON.stringify(bad));
  const weak = await put([{ id: "h1", bot: "My Agents/on_file.flow.json", type: "webhook", secret: "short" }]);
  check("webhooks need a strong secret", weak.error && weak.error.code === "TRIGGER.WEAK_SECRET");
  const secret = "s3cret-webhook-0123456789";
  const ok = await put([
    { id: "files", name: "New files", bot: "My Agents/on_file.flow.json", type: "file", folder: "inbox", pattern: "*.pdf" },
    { id: "sched", name: "Every minute", bot: "My Agents/on_file.flow.json", type: "schedule", every_minutes: 1 },
    { id: "hook", name: "Hook", bot: "My Agents/on_file.flow.json", type: "webhook", secret }]);
  check("triggers saved", ok.triggers && ok.triggers.length === 3, JSON.stringify(ok).slice(0, 200));
  await sleep(500);
  fs.writeFileSync(path.join(WS, "inbox", "INV-77.pdf"), "%PDF");
  fs.writeFileSync(path.join(WS, "inbox", "notes.txt"), "ignored");
  const outFile = path.join(WS, "trigger-out.txt");
  check("file trigger runs the bot with the arriving file", await until(() => fs.existsSync(outFile) && fs.readFileSync(outFile, "utf8").includes("INV-77.pdf"), 15000));
  check("files not matching the pattern are ignored", !fs.readFileSync(outFile, "utf8").includes("notes.txt"));
  const denied = await fetch(BASE + "/api/hooks/hook", { method: "POST", headers: { "X-OOA-Secret": "wrong" }, body: "{}" });
  check("webhook refuses a wrong secret (403 Forbidden)", denied.status === 403);
  const acc = await fetch(BASE + "/api/hooks/hook", { method: "POST", headers: { "X-OOA-Secret": secret, "Content-Type": "application/json" }, body: JSON.stringify({ invoice: "INV-9" }) });
  check("webhook accepts the right secret", acc.status === 202);
  check("schedule trigger fires on its own", await until(async () => false, 1) || await (async () => { for (let i = 0; i < 40; i++) { const l = (await (await fetch(BASE + "/api/triggers")).json()).log; if (l.some((e) => e.trigger === "sched" && e.status === "success")) return true; await sleep(500); } return false; })());
  click(d.querySelector('.rail-btn[data-side="triggers"]'));
  check("Triggers panel lists the triggers and recent activity", await until(() => d.querySelectorAll("#side-triggers .trig").length === 3 && d.querySelectorAll("#side-triggers .trig-log").length >= 2));
  await put([]);

  // ================= Part 1: canvas editing, conditions, variables, live run =================
  fs.writeFileSync(path.join(WS, "global-values.json"), JSON.stringify({ threshold: 1000, team: "Finance" }));
  const mk = (id, n, extraSteps) => ({ kind: "TaskBot", id, name: id, description: "Editing test bot " + id,
    variables: [{ name: "note", type: "String" }],
    steps: [...Array.from({ length: n }, (_, i) => ({ id: "s" + (i + 1), action: "core/log", args: { message: "step " + (i + 1) + " $note" } })), ...(extraSteps || [])] });
  fs.writeFileSync(path.join(WS, "My Agents", "edit_test.flow.json"), JSON.stringify(mk("edit_test", 4, [{ id: "c1", action: "core/if", args: { condition: { op: "eq", left: 1, right: 1 } }, children: [], else: [] }])));
  fs.writeFileSync(path.join(WS, "My Agents", "other_bot.flow.json"), JSON.stringify({ kind: "TaskBot", id: "other_bot", name: "other", description: "", variables: [], steps: [] }));
  fs.writeFileSync(path.join(WS, "My Agents", "slow_bot.flow.json"), JSON.stringify({ kind: "TaskBot", id: "slow_bot", name: "slow", description: "", variables: [],
    steps: [1, 2, 3, 4].map((i) => ({ id: "d" + i, action: "core/delay", args: { seconds: 0.8 } })) }));
  click(d.getElementById("btn-refresh"));
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="explorer"]'));
  await until(() => node("My Agents/edit_test.flow.json") || (node("My Agents") && click(node("My Agents")), false));
  if (w.eval("state.dirty")) w.eval("state.dirty = false");
  click(node("My Agents/edit_test.flow.json"));
  check("open the editing test bot", await until(() => w.eval("state.file") === "My Agents/edit_test.flow.json"));
  const stepById = (id) => d.querySelector('.step[data-step-id="' + id + '"]');
  const mclick = (el, opts) => el.dispatchEvent(new w.MouseEvent("click", Object.assign({ bubbles: true }, opts)));
  const key = (k, opts) => d.dispatchEvent(new w.KeyboardEvent("keydown", Object.assign({ key: k, bubbles: true }, opts)));
  const count = () => w.eval("state.flow.steps.length");
  mclick(stepById("s1")); mclick(stepById("s3"), { ctrlKey: true });
  check("Ctrl+click selects several steps", w.eval("state.multi.size") === 2 && d.querySelectorAll(".step.selected").length === 2);
  mclick(stepById("s1")); mclick(stepById("s3"), { shiftKey: true });
  check("Shift+click selects a range", w.eval("state.multi.size") === 3);
  click(d.getElementById("btn-copy"));
  click(d.getElementById("btn-paste"));
  check("paste inserts copies with new unique ids", count() === 8 && new Set(w.eval("allSteps(state.flow.steps).map(x => x.id)")).size === w.eval("allSteps(state.flow.steps).length"));
  click(d.getElementById("btn-undo"));
  check("undo removes the pasted steps", count() === 5);
  click(d.getElementById("btn-redo"));
  check("redo brings them back", count() === 8);
  key("z", { ctrlKey: true });
  check("Ctrl+Z undoes from the keyboard", count() === 5);
  mclick(stepById("s2"));
  click(d.getElementById("btn-del"));
  check("delete removes the selection", count() === 4 && !stepById("s2"));
  key("z", { ctrlKey: true });
  check("and undo restores it", count() === 5 && stepById("s2"));
  mclick(stepById("s1")); mclick(stepById("s2"), { ctrlKey: true });
  click(d.getElementById("btn-cut"));
  check("cut removes and keeps a copy", count() === 3 && w.eval("state.clipboard.steps.length") === 2);
  mclick(stepById("s4"));
  key("v", { ctrlKey: true });
  check("Ctrl+V pastes after the selected step", count() === 5 && w.eval("state.flow.steps[2].action") === "core/log");
  stepById("s3").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 200, clientY: 200 }));
  const menu = d.getElementById("ctx-menu");
  check("right-click menu offers cut, copy, paste, shared clipboard, duplicate, disable and delete",
    menu && ["Cut", "Copy", "Paste after", "Copy to shared clipboard", "Paste from shared clipboard", "Duplicate", "Disable", "Delete"].every((t) => menu.textContent.includes(t)));
  click([...menu.querySelectorAll("button")].find((b) => b.textContent.startsWith("Disable")));
  check("disable from the menu", w.eval("allSteps(state.flow.steps).find(x => x.id === 's3').disabled") === true);
  mclick(stepById("s3")); mclick(stepById("s4"), { ctrlKey: true });
  click(d.getElementById("btn-share-copy"));
  await until(() => lastToast.includes("shared clipboard"));
  click(d.getElementById("btn-save"));
  await until(() => !w.eval("state.dirty"));
  click(node("My Agents/other_bot.flow.json"));
  await until(() => w.eval("state.file") === "My Agents/other_bot.flow.json");
  click(d.getElementById("btn-share-paste"));
  check("shared clipboard pastes into another bot, with the variables it uses", await until(() => w.eval("state.flow.steps.length") === 2 && w.eval("state.flow.variables.some(v => v.name === 'note')")));
  w.eval("state.dirty = false");
  click(node("My Agents/edit_test.flow.json"));
  await until(() => w.eval("state.file") === "My Agents/edit_test.flow.json");
  mclick(stepById("c1"));
  check("If shows the condition builder", await until(() => d.querySelector("#props .cond .cond-row")));
  const kind = d.querySelector("#props .cond .cond-top select");
  check("condition types include values and package checks", [...kind.querySelectorAll("option")].some((o) => o.value === "ooa.file/exists") && [...kind.querySelectorAll("option")].some((o) => o.value === "ooa.datetime/is_weekend") && kind.querySelectorAll("option").length >= 20);
  change(kind, "ooa.file/exists");
  await until(() => d.querySelector("#props .cond .cond-arg input"));
  change(d.querySelector("#props .cond .cond-arg input"), "data/orders.json");
  check("package condition is stored as {condition, args}", JSON.stringify(w.eval("state.flow.steps.find(x => x.id === 'c1').args.condition")) === JSON.stringify({ condition: "ooa.file/exists", args: { path: "data/orders.json" } }));
  click([...d.querySelectorAll("#props .cond button")].find((b) => b.textContent === "+ Condition"));
  const rows = d.querySelectorAll("#props .cond .cond-row");
  const inputs = rows[1].querySelectorAll("input[type=text]");
  change(inputs[0], "$global.threshold"); change(rows[1].querySelector("select[aria-label=Operator]"), "gt"); change(d.querySelectorAll("#props .cond .cond-row")[1].querySelectorAll("input[type=text]")[1], "500");
  change(d.querySelector("#props .cond select[aria-label='Combine conditions']"), "any");
  const cond = w.eval("state.flow.steps.find(x => x.id === 'c1').args.condition");
  check("two conditions combine with OR into the Runner's format", cond.any && cond.any.length === 2 && cond.any[1].op === "gt" && cond.any[1].left === "$global.threshold" && cond.any[1].right === 500, JSON.stringify(cond));
  click([...d.querySelectorAll("#props button")].find((b) => b.textContent === "+ Else if"));
  check("Else if adds a branch on the canvas", await until(() => [...d.querySelectorAll(".section")].some((x) => x.textContent === "Else if #1")));
  click(d.getElementById("btn-validate"));
  check("the bot with built conditions validates cleanly", await until(() => w.eval("state.problems.length") === 0, 4000), JSON.stringify(w.eval("state.problems")));
  click(d.querySelector('.rail-btn[data-side="variables"]'));
  check("Variables panel shows system, global and bot variables", await until(() => {
    const t = d.getElementById("side-variables").textContent;
    return t.includes("System variables") && t.includes("$sys.date") && t.includes("$global.threshold") && t.includes("Agent variables");
  }));
  click(d.getElementById("btn-save")); await until(() => !w.eval("state.dirty"));
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  click(node("My Agents/slow_bot.flow.json"));
  await until(() => w.eval("state.file") === "My Agents/slow_bot.flow.json");
  click(d.getElementById("btn-run"));
  check("live run highlights the running step", await until(() => d.querySelector(".step.running"), 4000));
  check("run window shows progress and the current step", d.getElementById("run-result").textContent.includes("of 4 steps") && d.getElementById("run-result").textContent.includes("Now:"));
  click([...d.querySelectorAll("#run-result button")].find((b) => b.textContent === "Pause"));
  check("pause holds the run", await until(() => w.eval("state.live.state") === "paused", 4000));
  const doneWhilePaused = w.eval("state.live.finished");
  await sleep(1500);
  check("no steps run while paused", w.eval("state.live.finished") === doneWhilePaused);
  click([...d.querySelectorAll("#run-result button")].find((b) => b.textContent === "Resume"));
  check("resume finishes the run successfully", await until(() => w.eval("state.lastRun && state.lastRun.result.status") === "success", 10000));
  check("finished steps are ticked on the canvas", d.querySelectorAll(".step.done").length === 4);
  click(d.getElementById("btn-run"));
  await until(() => d.querySelector(".step.running"), 4000);
  click([...d.querySelectorAll("#run-result button")].find((b) => b.textContent === "Stop"));
  check("stop ends the run as stopped", await until(() => w.eval("state.lastRun && state.lastRun.result.status") === "stopped", 8000), w.eval("state.lastRun && state.lastRun.result.status"));
  const ts = d.getElementById("tree-search");
  ts.value = "editing test"; ts.dispatchEvent(new w.Event("input", { bubbles: true }));
  check("search finds bots by description", await until(() => !d.getElementById("search-results").hidden && d.getElementById("search-results").textContent.includes("edit_test")));
  ts.value = "core/delay"; ts.dispatchEvent(new w.Event("input", { bubbles: true }));
  check("search finds bots by the commands they use", await until(() => d.getElementById("search-results").textContent.includes("slow_bot") && d.getElementById("search-results").textContent.includes("uses core/delay")));
  ts.value = ""; ts.dispatchEvent(new w.Event("input", { bubbles: true }));
  check("clearing the search shows the tree again", await until(() => !d.getElementById("tree").hidden));
  check("panels have drag handles for resizing", d.querySelectorAll(".splitter").length === 3);
  w.eval("setSize('side', 360)");
  check("resizing is applied and remembered", d.documentElement.style.getPropertyValue("--side-w") === "360px" && JSON.parse(w.localStorage.getItem("ooa.studio.prefs")).sizes.side === 360);
  if (!hasBrowser) {
    const cap = await (await fetch(BASE + "/api/capture", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url: "https://example.com" }) })).json();
    check("Capture explains when the Browser module is missing", cap.error && cap.error.code === "STUDIO.NO_BROWSER_MODULE");
  }

  // ================= Part 3: colours, Automation home, typed items, variables =================
  check("commands are grouped by category with coloured icons", d.querySelectorAll("#library .pkg-head .tile").length >= 10 && [...d.querySelectorAll("#library .pkg-head .gname")].some((x) => x.textContent === "Logic and flow"));
  w.eval("state.dirty = false; goHome()");
  check("the Automation home lists the workspace with create cards and type filters", await until(() => !d.getElementById("welcome").hidden && d.querySelectorAll(".create-card").length >= 7 && d.querySelectorAll(".home-table tbody tr").length > 3 && d.querySelectorAll(".chips .chip").length >= 3));
  check("the explorer has a Create menu for every item type", ["btn-new-bot", "btn-new-template", "btn-new-api", "btn-new-form", "btn-new-process", "btn-new-doc", "btn-new-skill", "btn-new-py", "btn-new-ps1", "btn-new-vbs", "btn-new-js"].every((id) => d.getElementById(id)));
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="explorer"]'));
  click(node("My Agents"));

  answers.push("Leave request"); click(d.getElementById("btn-new-form"));
  check("a new form opens in the form designer", await until(() => w.eval("state.item && state.item.type") === "form" && fsx("My Agents/Leave request.form.json") && d.querySelectorAll("#item-editor .fcard").length === 2));
  click([...d.querySelectorAll("#item-editor .pal-item")].find((b) => b.textContent === "Drop-down"));
  check("adding a field selects it and shows its settings", await until(() => w.eval("state.item.data.fields.length") === 3 && d.querySelector("#item-editor .designer-props textarea")));
  click(d.querySelector('#item-editor [data-itab="preview"]'));
  check("the form preview renders real controls", await until(() => d.querySelector("#item-editor .form-card.preview select")));
  click(d.getElementById("btn-save"));
  check("the form is saved as JSON", await until(() => { try { return JSON.parse(fs.readFileSync(path.join(WS, "My Agents/Leave request.form.json"), "utf8")).fields.length === 3; } catch (_) { return false; } }));

  answers.push("Approvals"); click(d.getElementById("btn-new-process"));
  check("a new process opens in the process designer", await until(() => w.eval("state.item && state.item.type") === "process"));
  for (const label of ["Human task", "Approval", "Agent task"]) click([...d.querySelectorAll("#item-editor .pal-item")].find((b) => b.textContent === label));
  check("stages are added to the flow", await until(() => d.querySelectorAll("#item-editor .pcard").length === 3 && w.eval("state.item.data.stages.length") === 3));
  click(d.getElementById("btn-save"));
  await until(() => !w.eval("state.dirty"));

  answers.push("Invoices"); click(d.getElementById("btn-new-doc"));
  check("a document automation opens with its field table", await until(() => w.eval("state.item && state.item.type") === "document" && d.querySelectorAll("#item-editor .doc-fields tbody tr").length === 2));
  w.eval("generateExtractionBot(state.item)");
  check("document automation generates a valid extraction bot", await until(() => w.eval("state.file") === "My Agents/Invoices extraction.flow.json" && w.eval("state.flow.steps.some(s => s.action === 'ooa.genai/extract')")));
  click(d.getElementById("btn-validate"));
  check("the generated extraction bot validates", await until(() => w.eval("state.problems.length") === 0, 4000), JSON.stringify(w.eval("state.problems")));

  answers.push("triage"); click(d.getElementById("btn-new-skill"));
  check("AI Skills are created in the ai-skills folder", await until(() => w.eval("state.file") === "ai-skills/triage.json" && fsx("ai-skills/triage.json") && d.getElementById("item-editor").textContent.includes("Prompt template")));

  click(node("My Agents"));
  answers.push("hello"); click(d.getElementById("btn-new-py"));
  check("a Python script opens in the code editor", await until(() => w.eval("state.item && state.item.type") === "script" && d.querySelector("#item-editor textarea.code").value.includes("import sys")));
  click(d.getElementById("btn-run"));
  check("Run runs the script and shows its output", await until(() => { const r = w.eval("state.lastRun && state.lastRun.result"); return r && r.status === "success" && String(r.outputs.output).includes("received"); }, 20000), JSON.stringify(w.eval("state.lastRun && state.lastRun.result")));

  const tpls = w.eval("JSON.stringify(availableTemplates().map((t) => ({ id: t.id, flow: { $schema: SCHEMA, kind: 'TaskBot', id: 't_' + t.id, name: t.name, variables: t.variables, steps: t.steps } })))");
  const badTpl = [];
  for (const t of JSON.parse(tpls)) {
    const r = await (await fetch(BASE + "/api/validate", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(t.flow) })).json();
    if (r.problems.length) badTpl.push(t.id + ": " + JSON.stringify(r.problems));
  }
  check("every available bot template validates (" + JSON.parse(tpls).length + " templates)", JSON.parse(tpls).length >= 4 && badTpl.length === 0, badTpl.join("; "));
  answers.push("CSV loop");
  w.eval("createFromTemplate(TEMPLATES.find((t) => t.id === 'csv_rows'))");
  check("a bot is created from a template", await until(() => w.eval("state.file") === "My Agents/CSV loop.flow.json" && w.eval("state.flow.steps.length") === 3));
  check("container steps are drawn as coloured blocks", await until(() => d.querySelector("#steps .block > .section") && d.querySelector("#steps .step .tile.ico svg") && d.querySelector("#steps .step").style.getPropertyValue("--c")));
  click([...d.querySelectorAll(".tab")].find((b) => b.dataset.tab === "deps"));
  check("the Dependencies tab lists what the bot uses and who uses it", await until(() => d.querySelectorAll("#tab-deps .dep-group").length === 5));
  click([...d.querySelectorAll(".tab")].find((b) => b.dataset.tab === "steps"));

  // variables: create a List with the editor, pick it in a field, rename it everywhere
  click(d.querySelector('.rail-btn[data-side="variables"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="variables"]'));
  await until(() => d.getElementById("btn-new-var"));
  click(d.getElementById("btn-new-var"));
  check("+ New opens the variable editor", await until(() => !d.getElementById("dialog").hidden && d.querySelectorAll("#dialog .type-opt").length === 13));
  change(d.querySelector('#dialog input[aria-label="Variable name"]'), "amounts");
  click([...d.querySelectorAll("#dialog .type-opt")].find((b) => b.textContent === "List"));
  await until(() => [...d.querySelectorAll("#dialog button")].some((b) => b.textContent === "+ Item"));
  click([...d.querySelectorAll("#dialog button")].find((b) => b.textContent === "+ Item"));
  click([...d.querySelectorAll("#dialog button")].find((b) => b.textContent === "+ Item"));
  const items = d.querySelectorAll('#dialog .ve input[aria-label^="Item"]');
  change(items[0], "10"); change(items[1], "25.5");
  d.querySelector('#dialog input[aria-label="Variable name"]').value = "amounts";
  click([...d.querySelectorAll("#dialog-actions button")].find((b) => b.textContent === "Create variable"));
  check("the List variable is created with its typed default", await until(() => JSON.stringify(w.eval("state.flow.variables.find((v) => v.name === 'amounts')")) === JSON.stringify({ name: "amounts", type: "List", default: [10, 25.5] })), JSON.stringify(w.eval("state.flow.variables.find((v) => v.name === 'amounts')")));
  check("the variables pane groups variables and shows the new one", await until(() => d.querySelector('#side-variables .var-row[data-var="amounts"]') && d.getElementById("side-variables").textContent.includes("Inside the agent")));
  w.eval("selectById('s2')");
  await until(() => d.querySelector("#props .var-btn"));
  click(d.querySelector("#props .var-btn"));
  check("the {x} picker lists the bot's variables", await until(() => d.getElementById("var-pop") && [...d.querySelectorAll("#var-pop .var-pop-item")].some((b) => b.textContent.includes("$amounts"))));
  click([...d.querySelectorAll("#var-pop .var-pop-item")].find((b) => b.textContent.includes("$amounts")));
  check("picking a variable inserts it into the field", await until(() => String(w.eval("state.flow.steps[1].args.message")).includes("$amounts")), w.eval("state.flow.steps[1].args.message"));
  w.eval("editVariable(state.flow.variables.find((v) => v.name === 'row_count'))");
  await until(() => !d.getElementById("dialog").hidden);
  d.querySelector('#dialog input[aria-label="Variable name"]').value = "rows_read";
  click([...d.querySelectorAll("#dialog-actions button")].find((b) => b.textContent === "Save changes"));
  check("renaming a variable updates $name, ${name} and outputs everywhere", await until(() => w.eval("state.flow.steps[1].args.message").includes("Read ${rows_read} rows") && w.eval("state.flow.steps[0].out.row_count") === "rows_read"), w.eval("JSON.stringify(state.flow.steps.slice(0, 2))"));
  click(d.getElementById("btn-save")); await until(() => !w.eval("state.dirty"));

  // ================= Part 2: Control Room section in Studio =================
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="explorer"]'));
  check("Explorer shows a Control Room section with a sign-in form", await until(() => d.querySelector("#cr-body input[type=password]") && d.getElementById("cr-state").textContent.includes("not connected")));
  check("Explorer has a Check in button", !!d.getElementById("btn-checkin"));
  const crInputs = d.querySelectorAll("#cr-body input");
  crInputs[0].value = "http://127.0.0.1:1"; crInputs[1].value = "nobody"; crInputs[2].value = "x";
  click([...d.querySelectorAll("#cr-body button")].find((b) => b.textContent === "Connect"));
  check("connecting to an unreachable Control Room explains the problem", await until(() => lastToast.includes("Cannot reach Command Center"), 8000), lastToast);

  console.log(failures ? failures + " UI check(s) FAILED" : "All UI checks passed");
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error("UI test crashed:", e); process.exit(2); });
