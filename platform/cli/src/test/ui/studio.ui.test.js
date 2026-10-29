const { JSDOM } = require("jsdom");
const fs = require("fs");
const path = require("path");
const S = process.env.STUDIO_DIR, WS = process.env.OOA_WS, BASE = process.env.OOA_STUDIO_URL || "http://localhost:8710";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (name, ok, extra) => { console.log((ok ? "  PASS " : "  FAIL ") + name + (ok || extra === undefined ? "" : "  -> " + extra)); if (!ok) failures++; };

// The checks change the workspace (new folders, renamed and moved bots, new forms ...). Keep a copy and put the
// workspace back at the end, so the test can run again against the same Studio; a copy left by a crashed run wins.
const SNAP = WS ? path.join(path.dirname(path.resolve(WS)), "." + path.basename(path.resolve(WS)) + ".uitest-copy") : null;
const emptyDir = (dir) => { for (const n of fs.readdirSync(dir)) fs.rmSync(path.join(dir, n), { recursive: true, force: true }); };
if (SNAP && fs.existsSync(WS)) {
  if (fs.existsSync(SNAP)) { emptyDir(WS); fs.cpSync(SNAP, WS, { recursive: true }); }
  else fs.cpSync(WS, SNAP, { recursive: true });
}
function restoreWorkspace() {
  if (!SNAP || !fs.existsSync(SNAP)) return;
  try { emptyDir(WS); fs.cpSync(SNAP, WS, { recursive: true }); fs.rmSync(SNAP, { recursive: true, force: true }); }
  catch (e) { console.error("Could not put the workspace back (the copy is in " + SNAP + "):", e.message); }
}

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
    const newPage = await until(() => d.getElementById("rec-new-page"));
    if (newPage) click(newPage);
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
  await until(() => d.querySelector("#props .cond [data-field=path] input"));
  change(d.querySelector("#props .cond [data-field=path] input"), "data/orders.json");
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

  // ================= Part 4: debugger, quick add, flow view, assistant =================
  click(d.querySelector('#steps .step[data-step-id="s2"] .num'));
  check("clicking a step number sets a breakpoint", await until(() => d.querySelector('#steps .step[data-step-id="s2"] .num.bp') && JSON.stringify(w.eval("state.flow.ui.breakpoints")) === '["s2"]'));
  w.eval("openQuickAdd()");
  check("quick add opens with a search box", await until(() => d.querySelector("#quick-add input")));
  const qa = d.querySelector("#quick-add input");
  qa.value = "change case"; qa.dispatchEvent(new w.Event("input", { bubbles: true }));
  await until(() => d.querySelector("#quick-add .qa-item .qa-name") && d.querySelector("#quick-add .qa-item .qa-name").textContent === "Change case");
  const beforeQa = w.eval("allSteps(state.flow.steps).length");
  qa.dispatchEvent(new w.KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  check("Enter in quick add inserts the best match", await until(() => w.eval("allSteps(state.flow.steps).length") === beforeQa + 1 && !d.getElementById("quick-add") && w.eval("allSteps(state.flow.steps).some((x) => x.action === 'ooa.string/change_case')")));
  w.eval("deleteSelection(true)");
  click(d.querySelector('[data-canvas-mode="flow"]'));
  check("the flow view draws the agent as a diagram", await until(() => d.querySelector("#steps .flow-view .fterm.start") && d.querySelectorAll("#steps .fnode").length === w.eval("allSteps(state.flow.steps).length") && d.querySelector("#steps .fbranches")));
  click(d.querySelector('#steps .fnode[data-step-id="s1"]'));
  check("selecting in the flow view opens the action settings", await until(() => d.getElementById("props").textContent.includes("Read to table") || d.getElementById("props").textContent.includes("CSV")));
  click(d.querySelector('[data-canvas-mode="list"]'));
  await until(() => d.querySelector("#steps .step"));
  w.eval("openAssistant('explain')");
  check("the assistant explains the agent in plain words", await until(() => !d.getElementById("dialog").hidden && d.querySelector("#dialog pre.explain") && d.querySelector("#dialog pre.explain").textContent.includes("Repeat for each item of $rows")));
  w.eval("openAssistant('build')");
  check("building with AI asks for a One Model Connection first", await until(() => d.getElementById("dialog").textContent.includes("Set up a One Model Connection")));
  w.eval("closeDialog()");
  fs.mkdirSync(path.join(WS, "data"), { recursive: true });
  fs.writeFileSync(path.join(WS, "data", "debug.csv"), ["name,amount", "A,1", "B,2", ""].join("\n"));
  await until(() => d.querySelector('[data-input="csv_file"]'));
  d.querySelector('[data-input="csv_file"]').value = "data/debug.csv";
  click(d.getElementById("btn-debug"));
  check("Debug stops at the breakpoint and shows the debugger", await until(() => w.eval("state.live && state.live.debug && state.live.debug.stepId") === "s2" && d.getElementById("run-result").textContent.includes("Call stack") && d.querySelector('#steps .step.paused[data-step-id="s2"]'), 8000),
    lastToast + " | " + (d.getElementById("run-result") && d.getElementById("run-result").textContent.slice(0, 200)) + " | " + JSON.stringify(w.eval("state.live && { id: state.live.id, st: state.live.state, dbg: state.live.debug }")));
  click([...d.querySelectorAll("#run-result button")].find((b) => b.textContent === "Stop"));
  check("Stop ends a debug run", await until(() => w.eval("state.lastRun && state.lastRun.result.status") === "stopped" || !w.eval("state.running"), 8000));
  w.eval("state.dirty = false");

  // ================= Captured object editor (web Capture) =================
  if (w.eval("!!state.flow")) {
    w.eval(`state.flow.steps.push({ id: "cap_ui", action: "ooa.browser/element_action", args: { action: "click", object: { technology: "web", search_by: "properties", index: 0,
      page: { url: "https://example.com/", title: "Example" }, properties: [{ name: "tag", value: "button", match: "equals", use: true },
      { name: "id", value: "save-123", match: "equals", use: false }, { name: "text", value: "Save", match: "equals", use: true }] } } });
      state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    // only the selected properties show at first, as in the reference product; Show all properties lists the rest
    check("the captured object editor shows only the selected properties at first", await until(() => d.querySelectorAll(".cap-table .cap-row:not(.cap-headrow)").length === 2
      && d.querySelector(".cap-more") && d.querySelector(".cap-more").textContent === "Show all properties (1 more)"));
    click(d.querySelector(".cap-more"));
    check("the captured object editor lists every property with Use and Match", await until(() => d.querySelectorAll(".cap-table .cap-row:not(.cap-headrow)").length === 3 && d.querySelectorAll(".cap-table select").length === 3),
      "selected=" + JSON.stringify(w.eval("state.selected")) + " spec=" + w.eval("!!state.specs.get('ooa.browser/element_action')") + " props=" + (d.getElementById("props") ? d.getElementById("props").textContent.slice(0, 300) : "none"));
    if (!d.querySelector(".cap-table")) throw new Error("capture editor not shown");
    check("properties show readable names (stored name on hover) and a ticked count", [...d.querySelectorAll(".cap-table .cap-name")].map((x) => x.textContent).join() === "HTML tag,HTML ID,Inner text"
      && d.querySelector(".cap-table .cap-name").title === "tag" && d.querySelector(".cap-count").textContent === "2 selected of 3");
    click(d.querySelector(".cap-more"));
    check("Show only selected hides the unticked properties", await until(() => d.querySelectorAll(".cap-table .cap-row:not(.cap-headrow)").length === 2 && d.querySelector(".cap-more").textContent.startsWith("Show all")));
    // unticking while only the selected ones show keeps the row until the list is switched, so a slip can be undone
    const textUse = d.querySelector('.cap-table input[aria-label="Search by Inner text"]'); textUse.checked = false; textUse.dispatchEvent(new w.Event("change"));
    check("an unticked property stays in view until the list is switched", await until(() => d.querySelectorAll(".cap-table .cap-row:not(.cap-headrow)").length === 2
      && !d.querySelector('.cap-table input[aria-label="Search by Inner text"]').checked));
    const textUse2 = d.querySelector('.cap-table input[aria-label="Search by Inner text"]'); textUse2.checked = true; textUse2.dispatchEvent(new w.Event("change"));
    click(d.querySelector(".cap-more"));
    await until(() => d.querySelectorAll(".cap-table .cap-row:not(.cap-headrow)").length === 3);
    const idUse = d.querySelector('.cap-table input[aria-label="Search by HTML ID"]'); idUse.checked = true; idUse.dispatchEvent(new w.Event("change"));
    const idMatch = d.querySelector('.cap-table select[aria-label="Match for HTML ID"]'); idMatch.value = "regex"; idMatch.dispatchEvent(new w.Event("change"));
    const capProp = () => JSON.stringify(w.eval("state.flow.steps[state.flow.steps.length - 1].args.object.properties[1]"));
    check("ticking a property and choosing its match rule updates the step", capProp().includes('"use":true') && capProp().includes('"match":"regex"'), capProp());
    check("search by offers properties, CSS and XPath; Recapture and Test are offered",
      [...d.querySelectorAll('.cap-opts select[aria-label="Search by"] option')].map((o) => o.value).join() === "properties,css,xpath"
      && [...d.querySelectorAll(".cap-head button")].map((b) => b.textContent).join() === "Recapture,Test");
    click([...d.querySelectorAll(".cap-editor .link-btn")].find((b) => b.textContent.includes("Add property")));
    check("a custom property can be added", await until(() => d.querySelectorAll(".cap-table .cap-row:not(.cap-headrow)").length === 4 && d.querySelector('.cap-table input[aria-label="Property name"]')));
    click([...d.querySelectorAll(".cap-tabs button")].find((b) => b.textContent.startsWith("Anchor")));
    check("the Anchor tab explains anchors and offers Capture anchor", await until(() => d.querySelector(".cap-anchor")
      && [...d.querySelectorAll(".cap-anchor button")].some((b) => b.textContent === "Capture anchor")));
    w.eval(`(() => { const o = state.flow.steps[state.flow.steps.length - 1].args.object; o.anchor = { technology: "web", search_by: "properties", index: 0, offset: { dx: 0, dy: 24 },
      properties: [{ name: "text", value: "Order", match: "equals", use: true }] }; renderProps(); })()`);
    click([...d.querySelectorAll(".cap-tabs button")].find((b) => b.textContent.startsWith("Anchor")));
    check("a captured anchor shows its offset and its own properties", await until(() => d.querySelector(".cap-anchor .cap-nested .cap-table")
      && d.querySelector(".cap-anchor").textContent.includes("24 px down") && [...d.querySelectorAll(".cap-anchor button")].some((b) => b.textContent === "Remove anchor")));
    w.eval("state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // Desktop objects: readable names, the window with program path and resize, and an anchor
    w.eval(`state.flow.steps.push({ id: "dcap_ui", action: "ooa.desktop_ui/object_action", args: { action: "click", object: { technology: "desktop", index: 0,
      window: { title: "Untitled - Notepad", title_match: "wildcard", process: "notepad", path: "C:\\\\Apps\\\\notepad.exe", size: { width: 1440, height: 740 } },
      properties: [{ name: "ControlType", value: "Document", match: "equals", use: true }, { name: "Path", value: "1/1", match: "equals", use: false },
        { name: "XPath", value: "/Pane[1]/Document[@Name='Text editor']", match: "equals", use: false }, { name: "BoundingRectangle", value: "10,100,600,400", match: "equals", use: false }] } } });
      state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    check("a desktop object shows readable names, Main and Anchor, and the program path", await until(() => [...d.querySelectorAll(".cap-table .cap-name")].map((x) => x.textContent).join() === "Control type,Path,XPath,Screen rectangle"
      && d.querySelector(".cap-tabs") && d.querySelector(".cap-page") && d.querySelector(".cap-page").textContent.includes("C:\\Apps\\notepad.exe")));
    const rz = d.querySelector('.cap-page input[aria-label="Resize window"]'); rz.checked = true; rz.dispatchEvent(new w.Event("change"));
    check("Resize window records the captured size", JSON.stringify(w.eval("state.flow.steps[state.flow.steps.length - 1].args.object.window.resize")) === '{"width":1440,"height":740}'
      && !!(await until(() => d.querySelector('.cap-page input[aria-label="Window width"]'))));
    click([...d.querySelectorAll(".cap-tabs button")].find((b) => b.textContent.startsWith("Anchor")));
    check("the desktop Anchor tab offers Capture anchor", await until(() => [...d.querySelectorAll(".cap-anchor button")].some((b) => b.textContent === "Capture anchor")));
    w.eval("state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // Web actions: the window comes before the captured object, and Capture fills it from the page (title, link, browser)
    w.eval(`state.flow.steps.push({ id: "win_ui", action: "ooa.browser/element_action", args: { action: "click" } }); state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    check("a web action shows the window before the captured object", await until(() => {
      const f = [...d.querySelectorAll("#props .field[data-field]")].map((x) => x.dataset.field);
      return f.includes("window") && f.indexOf("window") < f.indexOf("object"); }), [...d.querySelectorAll("#props .field[data-field]")].map((x) => x.dataset.field).join());
    w.eval(`fillWindowFromCapture(state.flow.steps[state.flow.steps.length - 1], { page: { title: "The Internet", url: "https://the-internet.herokuapp.com/login" } }); renderProps();`);
    // as in the reference product, Capture makes a window variable (browser title, link, browser) and the step uses it
    const capWin = JSON.parse(w.eval(`JSON.stringify({ win: state.flow.steps[state.flow.steps.length - 1].args.window,
      v: state.flow.variables.find((x) => x.name === "Window_1") })`));
    check("Capture makes a numbered window variable (Window_1) with the browser title, link and browser, and the step uses it", capWin.win === "$Window_1" && capWin.v && capWin.v.type === "Dictionary"
      && JSON.stringify(capWin.v.default) === JSON.stringify({ source: "browser", title: "The Internet", title_match: "equals", link: "https://the-internet.herokuapp.com/login", browser: "edge" })
      && !!(await until(() => d.querySelector(".win-now") && d.querySelector(".win-now").textContent.includes("\u201CThe Internet\u201D (window variable $Window_1)"))), JSON.stringify(capWin));
    w.eval(`fillWindowFromCapture((() => { state.flow.steps.push({ id: "win_ui2", action: "ooa.browser/click", args: {} }); return state.flow.steps[state.flow.steps.length - 1]; })(),
      { page: { title: "The Internet", url: "https://the-internet.herokuapp.com/login" } })`);
    check("a second capture in the same window uses the same window variable", w.eval("state.flow.steps[state.flow.steps.length - 1].args.window") === "$Window_1"
      && w.eval("state.flow.variables.filter((x) => /^Window_/.test(x.name)).length") === 1);
    w.eval(`fillWindowFromCapture((() => { state.flow.steps.push({ id: "win_ui3", action: "ooa.browser/click", args: {} }); return state.flow.steps[state.flow.steps.length - 1]; })(),
      { page: { title: "Secure Area | Example Portal with a long page title", url: "https://the-internet.herokuapp.com/secure" } })`);
    check("another window gets the next number, not its title or link", w.eval("state.flow.steps[state.flow.steps.length - 1].args.window") === "$Window_2");
    w.eval("state.flow.steps.pop(); state.flow.variables = state.flow.variables.filter((x) => x.name !== 'Window_2')");
    w.eval("state.flow.steps.pop(); state.flow.steps.pop(); state.flow.variables = state.flow.variables.filter((x) => x.name !== 'Window_1'); state.selected = null; renderProps(); renderVariables(); state.dirty = false");
    // Recorded typing and selections become input variables named after the field
    const recVars = JSON.parse(w.eval(`JSON.stringify((() => { const before = state.flow.variables.length;
      const steps = [{ action: "ooa.browser/type_text", args: { text: "tomsmith", object: { properties: [{ name: "label", value: "Username" }, { name: "name", value: "user" }] } } },
        { action: "ooa.browser/type_text", args: { text: "$password" } }, { action: "ooa.browser/select_option", args: { option: "Option 2", object: { properties: [{ name: "id", value: "dropdown" }] } } }];
      const made = recordedVariables(steps); const vars = state.flow.variables.slice(before); state.flow.variables.splice(before);
      return { made, vars, texts: [steps[0].args.text, steps[1].args.text, steps[2].args.option] }; })())`));
    check("recorded values become short numbered variables (Text_1, Choice_1) with the recorded value as default and the field named", recVars.made.join() === "Text_1,Choice_1"
      && recVars.vars[0].default === "tomsmith" && recVars.vars[0].description.includes("Username") && recVars.texts.join() === "$Text_1,$password,$Choice_1", JSON.stringify(recVars));
    // The recorder makes one window variable per window, and its steps use it
    const recWin = JSON.parse(w.eval(`JSON.stringify((() => { const before = state.flow.variables.length;
      const win = { source: "browser", title: "Web form", title_match: "equals", link: "https://x.test/form", browser: "edge" };
      const steps = [{ action: "ooa.browser/click", args: { window: Object.assign({}, win), object: { properties: [] } } }, { action: "ooa.browser/click", args: { window: Object.assign({}, win), object: { properties: [] } } }];
      const made = recordedVariables(steps); const vars = state.flow.variables.slice(before); state.flow.variables.splice(before);
      return { made, vars, windows: steps.map((s) => s.args.window) }; })())`));
    check("the recorder makes one numbered window variable and the steps use it", recWin.made.join() === "Window_1" && recWin.vars[0].type === "Dictionary"
      && recWin.vars[0].default.title === "Web form" && recWin.windows.every((x) => x === "$Window_1"), JSON.stringify(recWin));
    // Desktop recordings: typed text becomes a variable named after the control, password fields use $password
    const recDesk = JSON.parse(w.eval(`JSON.stringify((() => { const before = state.flow.variables.length;
      const steps = [{ action: "ooa.desktop_ui/object_action", args: { action: "set_text", text: "Ann", object: { technology: "desktop", properties: [{ name: "Name", value: "Customer name" }, { name: "AutomationId", value: "txtName" }] } } },
        { action: "ooa.desktop_ui/object_action", args: { action: "click", object: { technology: "desktop", properties: [{ name: "Name", value: "Save" }] } } }];
      const made = recordedVariables(steps); const vars = state.flow.variables.slice(before); state.flow.variables.splice(before);
      return { made, vars, text: steps[0].args.text, click: steps[1].args.text === undefined }; })())`));
    check("recorded desktop typing becomes Text_1, its description naming the control", recDesk.made.join() === "Text_1" && recDesk.vars[0].default === "Ann"
      && recDesk.vars[0].description.includes("Customer name") && recDesk.text === "$Text_1" && recDesk.click, JSON.stringify(recDesk));
    // Run in background: offered on both element actions; on desktop it hides the mouse and typing choices
    const bgFields = JSON.parse(w.eval(`JSON.stringify({
      web: [...elementActionFields({ action: "ooa.browser/element_action", args: { action: "click" } })],
      desk: [...elementActionFields({ action: "ooa.desktop_ui/object_action", args: { action: "click" } })],
      deskBg: [...elementActionFields({ action: "ooa.desktop_ui/object_action", args: { action: "click", run_in_background: true } })],
      textBg: [...elementActionFields({ action: "ooa.desktop_ui/object_action", args: { action: "set_text", run_in_background: true } })] })`));
    check("Run in background is offered for web and desktop element actions", bgFields.web.includes("run_in_background") && bgFields.desk.includes("run_in_background")
      && bgFields.desk.includes("click_method") && !bgFields.deskBg.includes("click_method") && !bgFields.deskBg.includes("offset_x")
      && bgFields.textBg.includes("text") && !bgFields.textBg.includes("text_method"), JSON.stringify(bgFields));
    // The recorder asks where to record, with a desktop application always on offer
    w.eval("recordSteps()");
    const recDlg = await until(() => !d.getElementById("dialog").hidden && d.getElementById("rec-desktop"));
    check("the recorder offers a desktop application and a new page", !!recDlg && !!d.getElementById("rec-new-page")
      && d.getElementById("dialog-title").textContent === "Record in which window?", d.getElementById("dialog-title").textContent);
    w.eval("closeDialog()");
    // Line numbers count every step from the top, nested steps included (as in the reference product)
    const lineNos = JSON.parse(w.eval(`JSON.stringify([...stepNumbers({ steps: [{ id: "a", action: "core/comment" }, { id: "b", action: "core/if", children: [{ id: "c", action: "core/comment" }],
      elseIf: [{ condition: {}, children: [{ id: "c2", action: "core/comment" }] }], else: [{ id: "d", action: "core/comment" }] },
      { id: "t", action: "core/try", children: [{ id: "t1", action: "core/comment" }], catch: [{ codes: [], children: [{ id: "k1", action: "core/comment" }] }, { codes: ["FILE.*"], children: [{ id: "k2", action: "core/comment" }] }], finally: [{ id: "f1", action: "core/comment" }] },
      { id: "e", action: "core/comment" }] }).entries()])`));
    check("line numbers run 1, 2, 3 ... through blocks, catch blocks and finally", lineNos.map(([id, n]) => id + n).join() === "a1,b2,c3,c24,d5,t6,t17,k18,k29,f110,e11", JSON.stringify(lineNos));

    // A variable can be an input, an output or both
    w.eval("editVariable(null, { name: 'both_ways' })");
    await until(() => d.querySelector('#dialog input[data-use="input"]'));
    const useIn = d.querySelector('#dialog input[data-use="input"]'); useIn.checked = true; useIn.dispatchEvent(new w.Event("change"));
    const useOut = d.querySelector('#dialog input[data-use="output"]'); useOut.checked = true; useOut.dispatchEvent(new w.Event("change"));
    click([...d.querySelectorAll("#dialog-actions button, #dialog button")].find((x) => x.textContent === "Create variable"));
    check("ticking Use as input and Use as output makes an input-and-output variable", await until(() => w.eval("(state.flow.variables.find((v) => v.name === 'both_ways') || {}).direction") === "in_out")
      && w.eval("isInputVar(state.flow.variables.find((v) => v.name === 'both_ways')) && isOutputVar(state.flow.variables.find((v) => v.name === 'both_ways'))"));
    check("the variables list has an Inputs and outputs group", await until(() => [...d.querySelectorAll(".var-group .gname")].some((g) => g.textContent === "Inputs and outputs")));
    w.eval("state.flow.variables = state.flow.variables.filter((v) => v.name !== 'both_ways'); renderVariables(); state.dirty = false");

    // Run Agent: choose the agent, give its inputs, save its outputs to variables of this agent
    fs.writeFileSync(path.join(WS, "child_for_map.flow.json"), JSON.stringify({ schemaVersion: "1.0", kind: "TaskBot", id: "child_for_map", name: "Child for map",
      variables: [{ name: "customer", type: "String", direction: "input", required: true }, { name: "count", type: "Number", direction: "in_out" }, { name: "total", type: "Number", direction: "output" }],
      steps: [{ id: "s1", action: "core/comment", args: { text: "x" } }] }));
    w.eval(`state.agents = null; loadAgents(true); state.flow.steps.push({ id: "rb_ui", action: "core/run_bot", args: { automation: "child_for_map" } }); state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    check("Run Agent lists the agent's inputs (input, and input-and-output) to fill in", await until(() => d.querySelector('#props [data-input="customer"]') && d.querySelector('#props [data-input="count"]') && !d.querySelector('#props [data-input="total"]'), 5000));
    const custIn = d.querySelector('#props [data-input="customer"]'); custIn.value = "$name_here"; custIn.dispatchEvent(new w.Event("change"));
    const cntIn = d.querySelector('#props [data-input="count"]'); cntIn.value = "3"; cntIn.dispatchEvent(new w.Event("change"));
    check("input values are kept on the step, numbers as numbers", JSON.stringify(w.eval("state.flow.steps[state.flow.steps.length - 1].args.inputs")) === JSON.stringify({ customer: "$name_here", count: 3 }));
    await until(() => d.querySelector('#props select[data-output="total"]'));
    const outSel = d.querySelector('#props select[data-output="total"]'); outSel.value = "__new"; outSel.dispatchEvent(new w.Event("change"));
    check("an output can go to a new variable of this agent", await until(() => JSON.stringify(w.eval("state.flow.steps[state.flow.steps.length - 1].args.output_map")) === JSON.stringify({ total: "total" }))
      && w.eval("state.flow.variables.some((v) => v.name === 'total' && v.type === 'Number')") && !!d.querySelector('#props select[data-output="count"]'));
    w.eval("state.flow.steps.pop(); state.flow.variables = state.flow.variables.filter((v) => v.name !== 'total'); state.selected = null; renderProps(); renderVariables(); state.dirty = false");
    fs.unlinkSync(path.join(WS, "child_for_map.flow.json"));

    // Try: several Catch blocks, each with its own errors, message and line number variables
    w.eval(`state.flow.steps.push({ id: "try_ui", action: "core/try", children: [], catch: [{ codes: ["FILE.*"], children: [] }, { codes: [], children: [] }], finally: [] });
      state.selected = ["steps", state.flow.steps.length - 1]; renderSteps(); renderProps();`);
    check("each Catch block shows on the canvas with the errors it catches", await until(() => { const secs = [...d.querySelectorAll('#steps .section')].map((x) => x.textContent);
      return secs.includes("Catch FILE.*") && secs.includes("Catch all errors") && secs.includes("Finally (always)"); }));
    check("the Try settings show one box per Catch block, with message and line number variables", d.querySelectorAll("#props .catch-block").length === 2
      && !!d.querySelector('#props select[aria-label="Error message variable"]') && !!d.querySelector('#props select[aria-label="Line number variable"]'));
    click([...d.querySelectorAll("#props button")].find((x) => x.textContent === "+ Catch"));
    check("+ Catch adds another Catch block", await until(() => d.querySelectorAll("#props .catch-block").length === 3 && w.eval("state.flow.steps[state.flow.steps.length - 1].catch.length") === 3));
    const lineSel = d.querySelector('#props select[aria-label="Line number variable"]'); lineSel.value = "__new"; lineSel.dispatchEvent(new w.Event("change"));
    check("the line number can go to a new Number variable", await until(() => w.eval("state.flow.steps[state.flow.steps.length - 1].catch[0].line_to") === "error_line"
      && w.eval("(state.flow.variables.find((v) => v.name === 'error_line') || {}).type") === "Number"));
    w.eval("state.flow.steps.pop(); state.flow.variables = state.flow.variables.filter((v) => v.name !== 'error_line'); state.selected = null; renderSteps(); renderProps(); renderVariables(); state.dirty = false");

    // Keyboard: Down selects the next step, Shift+Down extends the selection
    w.eval("state.canvasMode = 'list'; state.multi = new Set([state.flow.steps[0].id]); state.selected = ['steps', 0]; state.anchorId = state.flow.steps[0].id; renderSteps(); document.activeElement && document.activeElement.blur && document.activeElement.blur()");
    const key = (k, shift) => d.dispatchEvent(new w.KeyboardEvent("keydown", { key: k, shiftKey: !!shift, bubbles: true }));
    key("ArrowDown", true); key("ArrowDown", true);
    check("Shift+Down selects several steps", w.eval("state.multi.size") === 3, String(w.eval("state.multi.size")));
    key("ArrowUp", true);
    check("Shift+Up gives one back", w.eval("state.multi.size") === 2);
    key("ArrowDown");
    check("Down alone moves to a single step", w.eval("state.multi.size") === 1);
    w.eval("clearSelection()");

    // Flow view: actions can be dropped on the lines
    click(d.querySelector('[data-canvas-mode="flow"]'));
    await until(() => d.querySelector("#steps .flow-view .fslot"));
    const before = w.eval("state.flow.steps.length");
    const slot = d.querySelectorAll("#steps .flow-view > .fseq > .fslot")[1];
    const drop = new w.Event("drop", { bubbles: true }); drop.dataTransfer = { getData: (t) => (t === "text/x-ooa-command" ? "core/comment" : "") };
    slot.dispatchEvent(drop);
    check("dropping an action on a line in the flow view adds it there", await until(() => w.eval("state.flow.steps.length") === before + 1 && w.eval("state.flow.steps[1].action") === "core/comment"));
    w.eval("state.flow.steps.splice(1, 1); state.selected = null; state.multi = new Set(); renderSteps(); renderProps(); state.dirty = false");
    click(d.querySelector('[data-canvas-mode="list"]'));
    await until(() => d.querySelector("#steps .step"));

    // Element action: actions for the captured control type, only the fields the action uses, the outcome variable, the time-out box
    w.eval(`state.flow.steps.push({ id: "ea_ui", action: "ooa.browser/element_action", args: { action: "click", object: { technology: "web", search_by: "properties", index: 0,
      properties: [{ name: "tag", value: "select", match: "equals", use: true }, { name: "control_type", value: "Drop-down list", match: "equals", use: false }] } } });
      state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    await until(() => d.querySelector('#props select[aria-label="Action to take on the element"]'));
    const actSel = d.querySelector('#props select[aria-label="Action to take on the element"]');
    check("the action list starts with the drop-down list's actions", !!actSel && actSel.querySelector("optgroup").label === "For this drop-down list"
      && [...actSel.querySelector("optgroup").querySelectorAll("option")].map((o) => o.value).slice(0, 3).join() === "select_by_text,select_by_value,select_by_index"
      && d.querySelector("#props .ct-chip").textContent === "Drop-down list");
    check("a click shows click options but no value field", !d.querySelector('#props .field[data-field="value"]') && !!d.querySelector('#props .field[data-field="click_method"]'));
    check("the time-out sits in its own Basic / Advanced box", !!d.querySelector("#props .timeout-box") && !d.querySelector('#props .timeout-box [data-field="if_not_found"]'));
    actSel.value = "get_selected_index"; actSel.dispatchEvent(new w.Event("change"));
    check("an action that returns something gets an outcome variable", await until(() => w.eval("state.flow.steps[state.flow.steps.length - 1].out && state.flow.steps[state.flow.steps.length - 1].out.result") === "selected_index"
      && w.eval("state.flow.variables.some((v) => v.name === 'selected_index')")));
    const actSel2 = d.querySelector('#props select[aria-label="Action to take on the element"]'); actSel2.value = "set_text"; actSel2.dispatchEvent(new w.Event("change"));
    check("Set text shows the text to enter and the typing options", await until(() => d.querySelector('#props .field[data-field="value"] label') && d.querySelector('#props .field[data-field="value"] label').textContent.startsWith("Text to enter")
      && d.querySelector('#props .field[data-field="typing"]')));
    click([...d.querySelectorAll("#props .timeout-box button")].find((b) => b.textContent === "Advanced"));
    check("Advanced time-out adds the page wait and what to do when time runs out", await until(() => d.querySelector('#props .timeout-box [data-field="if_not_found"]') && d.querySelector('#props .timeout-box [data-field="wait_for_page_ms"]')));
    w.eval("state.flow.variables = state.flow.variables.filter((v) => v.name !== 'selected_index'); state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // Set text: the text comes from a credential, a variable or plain text
    w.eval(`state.flow.steps.push({ id: "ts_ui", action: "ooa.browser/element_action", args: { action: "set_text", value: "hello", object: { technology: "web", properties: [] } } });
      state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    await until(() => d.querySelector('#props .text-source'));
    check("Set text shows Credential / Variable / Text / Calculation, Text chosen for typed text",
      [...d.querySelectorAll('#props .text-source .seg button')].map((b) => b.textContent).join() === "Credential,Variable,Text,Calculation"
      && d.querySelector('#props .text-source .seg button[aria-checked="true"]').textContent === "Text");
    click([...d.querySelectorAll('#props .text-source .seg button')].find((b) => b.textContent === "Credential"));
    await until(() => d.querySelector('#props .text-source select[aria-label="Credential variable"]'));
    click([...d.querySelectorAll('#props .text-source button')].find((b) => b.textContent === "+ New"));
    check("a new credential variable is made and used", await until(() => /^\$password/.test(String(w.eval("state.flow.steps[state.flow.steps.length - 1].args.value")))
      && w.eval("state.flow.variables.some((v) => v.type === 'Credential' && v.direction === 'input')")));
    w.eval("state.flow.variables = state.flow.variables.filter((v) => !(v.type === 'Credential' && /^password/.test(v.name) && v.description && v.description.startsWith('From the vault'))); state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // Set into a Credential variable: only another Credential variable can be chosen (no typed text or calculation)
    w.eval(`state.flow.variables.push({ name: "api_secret", type: "Credential", direction: "input" }, { name: "copy_secret", type: "Credential" });
      state.flow.steps.push({ id: "setcred_ui", action: "core/set", args: { variable: "copy_secret" } });
      state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    await until(() => d.querySelector('#props .field[data-field="value"] select[aria-label="Credential variable"]'));
    const credSel = d.querySelector('#props .field[data-field="value"] select[aria-label="Credential variable"]');
    check("Set into a Credential variable offers only Credential variables",
      !!credSel && !d.querySelector('#props .field[data-field="value"] .seg') && !d.querySelector('#props .field[data-field="value"] textarea')
      && [...credSel.options].map((o) => o.value).filter(Boolean).every((n) => w.eval(`state.flow.variables.find((v) => v.name === "${n}").type`) === "Credential")
      && !d.querySelector('#props .field[data-field="value"]').textContent.includes("null"));
    credSel.value = "api_secret"; credSel.dispatchEvent(new w.Event("change"));
    check("choosing one sets the value to that variable", await until(() => w.eval("state.flow.steps[state.flow.steps.length - 1].args.value") === "$api_secret"));
    w.eval("state.flow.variables = state.flow.variables.filter((v) => v.name !== 'api_secret' && v.name !== 'copy_secret'); state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // Desktop objects: actions for the control type, fields per action, window presets
    w.eval(`state.flow.steps.push({ id: "do_ui", action: "ooa.desktop_ui/object_action", args: { action: "click", object: { technology: "desktop", index: 0,
      window: { title: "Untitled - Notepad", title_match: "wildcard", process: "notepad" }, properties: [{ name: "ControlType", value: "CheckBox", match: "equals", use: true }] } } });
      state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    await until(() => d.querySelector('#props select[aria-label="Action to take on the control"]'));
    const dsel = d.querySelector('#props select[aria-label="Action to take on the control"]');
    check("a desktop check box offers check, uncheck and toggle first", !!dsel && [...dsel.querySelector("optgroup").querySelectorAll("option")].map((o) => o.value).slice(0, 3).join() === "check,uncheck,toggle");
    check("a desktop click shows the click options only", !!d.querySelector('#props [data-field="click_type"]') && !d.querySelector('#props [data-field="text"]') && !d.querySelector('#props [data-field="property"]'));
    const presetSel = d.querySelector('#props select[aria-label="Window preset"]');
    check("the desktop window offers presets (in front, desktop, taskbar)", !!presetSel && [...presetSel.options].map((o) => o.value).join() === ",active,desktop,taskbar");
    w.eval("state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // The window title can be case sensitive
    w.eval(`state.flow.steps.push({ id: "cs_ui", action: "ooa.browser/click", args: { window: { source: "browser", title: "Orders", title_match: "equals" } } }); state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    await until(() => d.querySelector('#props .win-editor input[aria-label="Case sensitive"]'));
    const cs = d.querySelector('#props .win-editor input[aria-label="Case sensitive"]');
    if (cs) { cs.checked = true; cs.dispatchEvent(new w.Event("change")); }
    check("the window title has a Case sensitive switch", !!cs && w.eval("state.flow.steps[state.flow.steps.length - 1].args.window.case_sensitive") === true);
    w.eval("state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
    // Dragging one of several selected steps moves all of them
    const order = w.eval(`(() => { const base = state.flow.steps.length;
      ["mv_a", "mv_b", "mv_c", "mv_d"].forEach((id) => state.flow.steps.push({ id, action: "core/log", args: { message: id } }));
      state.multi = new Set(["mv_b", "mv_d"]);
      dropAt({ dataTransfer: { getData: (t) => t === "text/x-ooa-step" ? JSON.stringify(["steps", base + 1]) : "" } }, ["steps"], base);
      const ids = state.flow.steps.slice(base).map((x) => x.id).join();
      state.flow.steps.splice(base); state.multi = new Set(); state.selected = null; renderProps(); state.dirty = false;
      return ids; })()`);
    check("dragging one of several selected steps moves all of them, in order", order === "mv_b,mv_d,mv_a,mv_c", order);
    // Every web action (not only the element action) offers the captured object and a Capture button by its selector.
    w.eval(`state.flow.steps.push({ id: "click_ui", action: "ooa.browser/click", args: {} }); state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
    check("Click element offers a captured object with a Capture button, and a selector on request", await until(() => d.querySelector(".cap-editor .cap-empty")
      && [...d.querySelectorAll("#props button")].some((x) => x.textContent === "Capture element")
      && !d.querySelector('#props .field[data-field="selector"]')
      && [...d.querySelectorAll("#props button")].some((x) => x.textContent === "Use a selector instead")));
    click([...d.querySelectorAll("#props button")].find((x) => x.textContent === "Use a selector instead"));
    check("Use a selector instead shows the selector with its Pick button", await until(() => d.querySelector('#props .field[data-field="selector"]')
      && !d.querySelector(".cap-editor") && [...d.querySelectorAll("#props .field-row button")].some((x) => x.textContent === "Capture")));
    w.eval("state.flow.steps.pop(); state.selected = null; renderProps(); state.dirty = false");
  }

  // ================= Part 6: action groups, multi-select, step menu, close, settings, run window, explorer, window editor, layout =================
  // ---- one group per technology ----
  const libSearch = d.getElementById("cmd-search");
  libSearch.value = ""; libSearch.dispatchEvent(new w.Event("input"));
  const gnames = [...d.querySelectorAll("#library .pkg-head .gname")].map((x) => x.textContent);
  check("Terminal emulator has its own, clearly named group", w.eval("groupOf('ooa.terminal/connect')") === "terminal" && w.eval("groupMeta('terminal').name") === "Terminal emulator"
    && (!w.eval("state.specs.has('ooa.terminal/connect') || state.packages.some((p) => p.id === 'ooa.terminal')") || gnames.includes("Terminal emulator")), gnames.join(", "));
  check("XML and JSON are separate groups", gnames.includes("XML") && gnames.includes("JSON") && w.eval("groupOf('ooa.xml/read') !== groupOf('ooa.json/parse')"), gnames.join(", "));
  check("REST and SOAP web services are separate groups", gnames.includes("REST web service") && gnames.includes("SOAP web service"), gnames.join(", "));
  check("Excel, Word, PDF and CSV have their own groups", ["Excel", "Word", "PDF", "CSV / Text files"].every((g) => gnames.includes(g)) && w.eval("groupOf('ooa.excel_desktop/run_macro')") === "excel_desktop", gnames.join(", "));
  check("scripts are split by language", w.eval("[groupOf('ooa.script/run_python'), groupOf('ooa.script/run_javascript_function'), groupOf('ooa.script/run_vbscript'), groupOf('ooa.script/run_powershell')].join()") === "python,javascript,vbscript,powershell");
  const mixedGroups = w.eval(`(() => { const by = new Map(); for (const p of state.packages) for (const a of p.actions) { const g = groupOf(p.id + "/" + a.id); if (!by.has(g)) by.set(g, new Set()); by.get(g).add(p.id); } return [...by].filter(([, s]) => s.size > 1).map(([g, s]) => g + ": " + [...s].join("+")); })()`);
  check("no group mixes toolkits", mixedGroups.length === 0, JSON.stringify(mixedGroups));
  check("groups sit under section headings", d.querySelectorAll("#library .lib-section").length >= 6);
  libSearch.value = "soap"; libSearch.dispatchEvent(new w.Event("input"));
  check("search still works across groups", await until(() => d.querySelectorAll("#library .cmd").length > 0 && [...d.querySelectorAll("#library .cmd")].every((c) => /soap/i.test(c.textContent + c.title + c.dataset.action))));
  libSearch.value = ""; libSearch.dispatchEvent(new w.Event("input"));

  // ---- canvas multi-select and selection commands ----
  fs.writeFileSync(path.join(WS, "My Agents", "multi_test.flow.json"), JSON.stringify({ kind: "TaskBot", id: "multi_test", name: "Multi test", description: "",
    variables: [{ name: "out", type: "String", direction: "output", default: "-" }],
    steps: [1, 2, 3, 4, 5].map((i) => ({ id: "m" + i, action: "core/set", args: { variable: "out", value: "v" + i } })) }));
  w.eval("state.dirty = false");
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="explorer"]'));
  click(d.getElementById("btn-refresh"));
  await until(() => node("My Agents/multi_test.flow.json") || (node("My Agents") && !w.eval("state.expanded.has('My Agents')") && click(node("My Agents")), false));
  click(node("My Agents/multi_test.flow.json"));
  check("open the multi-select test agent", await until(() => w.eval("state.file") === "My Agents/multi_test.flow.json" && stepById("m5")));
  const ids = () => w.eval("state.flow.steps.map((s) => s.id).join()");
  mclick(stepById("m1")); mclick(stepById("m2"), { ctrlKey: true }); mclick(stepById("m4"), { ctrlKey: true });
  check("Ctrl+click adds steps to the selection", w.eval("state.multi.size") === 3 && d.querySelectorAll(".step.selected").length === 3);
  check("the selection count is shown", !d.getElementById("sel-count").hidden && d.getElementById("sel-count").textContent.includes("3 steps selected"), d.getElementById("sel-count").textContent);
  mclick(stepById("m2"), { ctrlKey: true });
  check("Ctrl+click removes a selected step", w.eval("state.multi.size") === 2 && !stepById("m2").classList.contains("selected"));
  mclick(stepById("m2"), { ctrlKey: true });
  click(d.getElementById("btn-copy"));
  check("copying 3 selected steps copies all 3", w.eval("state.clipboard.steps.length") === 3 && w.eval("state.clipboard.steps.map((s) => s.id).join()") === "m1,m2,m4");
  click(d.getElementById("btn-paste"));
  check("paste goes after the last selected step", w.eval("state.flow.steps.length") === 8 && JSON.stringify(w.eval("state.flow.steps.slice(4, 7).map((s) => s.args.value)")) === '["v1","v2","v4"]' && w.eval("state.flow.steps[7].id") === "m5", ids());
  key("z", { ctrlKey: true });
  mclick(stepById("m2")); mclick(stepById("m4"), { shiftKey: true });
  check("Shift+click selects the range from the last clicked step", w.eval("[...state.multi].sort().join()") === "m2,m3,m4");
  mclick(stepById("m5"), { shiftKey: true });
  check("Shift+click again extends from the same anchor", w.eval("[...state.multi].sort().join()") === "m2,m3,m4,m5");
  stepById("m1").focus();
  key("a", { ctrlKey: true });
  check("Ctrl+A selects all steps when the canvas has focus", w.eval("state.multi.size") === 5);
  key("Escape");
  check("Esc clears the selection", w.eval("state.multi.size") === 0 && d.getElementById("sel-count").hidden);
  mclick(stepById("m2")); mclick(stepById("m3"), { ctrlKey: true });
  click(d.getElementById("btn-down"));
  check("Move down moves the whole selection", ids() === "m1,m4,m2,m3,m5", ids());
  stepById("m2").focus();
  key("ArrowUp", { altKey: true });
  check("Alt+Up moves it back", ids() === "m1,m2,m3,m4,m5", ids());
  click(d.getElementById("btn-toggle"));
  check("Disable acts on the whole selection", w.eval("state.flow.steps.filter((s) => s.disabled).map((s) => s.id).join()") === "m2,m3" && d.getElementById("btn-toggle").textContent === "Enable");
  key("/", { ctrlKey: true });
  check("Ctrl+/ enables them again", w.eval("state.flow.steps.filter((s) => s.disabled).length") === 0);
  key("d", { ctrlKey: true });
  check("Ctrl+D duplicates the selection after it", w.eval("state.flow.steps.length") === 7 && w.eval("state.multi.size") === 2
    && JSON.stringify(w.eval("state.flow.steps.slice(3, 5).map((s) => s.args.value)")) === '["v2","v3"]' && w.eval("state.flow.steps[5].id") === "m4", ids());
  key("z", { ctrlKey: true });
  mclick(stepById("m1")); mclick(stepById("m2"), { ctrlKey: true });
  key("Delete");
  check("Delete removes the whole selection", ids() === "m3,m4,m5", ids());
  key("z", { ctrlKey: true });
  check("and undo brings them back", ids() === "m1,m2,m3,m4,m5", ids());

  // ---- step menu (⋮ and right-click) ----
  mclick(stepById("m1")); mclick(stepById("m2"), { ctrlKey: true });
  check("every step card has a ⋮ button", d.querySelectorAll("#steps .step .step-menu-btn").length === 5);
  click(stepById("m1").querySelector(".step-menu-btn"));
  const smenu = d.getElementById("ctx-menu");
  const want = ["Run from here", "Run this step only", "Copy", "Cut", "Paste after", "Duplicate", "Delete", "Disable", "Toggle breakpoint", "Move up", "Move down", "Add note"];
  check("the ⋮ button opens the step menu with every item", smenu && want.every((t) => [...smenu.querySelectorAll("button")].some((b) => b.dataset.item.startsWith(t))),
    smenu && [...smenu.querySelectorAll("button")].map((b) => b.dataset.item).join(" | "));
  check("the menu acts on the selection", smenu && [...smenu.querySelectorAll("button")].some((b) => b.dataset.item === "Copy 2 steps"));
  check("the menu takes the keyboard focus", smenu && d.activeElement === smenu.querySelector("button"));
  smenu.dispatchEvent(new w.KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
  check("arrow keys move through the menu", d.activeElement === smenu.querySelectorAll("button")[1]);
  smenu.dispatchEvent(new w.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  check("Esc closes the menu and keeps the selection", !d.getElementById("ctx-menu") && w.eval("state.multi.size") === 2);
  stepById("m4").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  check("right-click on an unselected step opens the menu for that step only", d.getElementById("ctx-menu") && w.eval("[...state.multi].join()") === "m4");
  click(d.body);
  check("an outside click closes the menu", !d.getElementById("ctx-menu"));
  answers.push("Sets the fourth value");
  stepById("m4").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  click([...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item.startsWith("Add note")));
  check("Add note sets the step's note", w.eval("state.flow.steps[3].description") === "Sets the fourth value" && stepById("m4").textContent.includes("Sets the fourth value"));
  stepById("m4").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  click([...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item === "Toggle breakpoint"));
  check("Toggle breakpoint from the menu", JSON.stringify(w.eval("state.flow.ui && state.flow.ui.breakpoints")) === '["m4"]');
  w.eval("toggleBreakpoint('m4')");
  const sent = [];
  const realFetch = w.fetch;
  w.fetch = (u, o) => { if (String(u).endsWith("api/runs") && o && o.method === "POST") sent.push(JSON.parse(o.body)); return realFetch(u, o); };
  stepById("m3").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  click([...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item === "Run from here"));
  check("Run from here sends start_at", await until(() => sent.length === 1 && sent[0].start_at === "m3" && !sent[0].only_step), JSON.stringify(sent.map((s) => ({ start_at: s.start_at, only_step: s.only_step }))));
  check("Run from here skips the earlier steps", await until(() => w.eval("state.lastRun && state.lastRun.result.status") === "success", 8000)
    && !w.eval("state.lastRun.events.some((e) => e.type === 'step.started' && (e.stepId === 'm1' || e.stepId === 'm2'))") && w.eval("state.lastRun.result.outputs.out") === "v5");
  stepById("m2").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  click([...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item === "Run this step only"));
  check("Run this step only sends only_step and runs one step", await until(() => sent.length === 2 && sent[1].only_step === "m2" && w.eval("state.lastRun && state.lastRun.result.outputs.out") === "v2", 8000));
  await until(() => !w.eval("state.running"), 8000);
  stepById("m3").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  click([...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item === "Run from here with default values"));
  check("Run from here with default values asks for no carried-over values", await until(() => sent.length === 3 && sent[2].start_at === "m3" && sent[2].carry_over === false),
    JSON.stringify(sent.map((s) => ({ start_at: s.start_at, carry_over: s.carry_over }))));
  await until(() => !w.eval("state.running"), 8000);
  w.fetch = realFetch;
  w.eval("state.live = null; renderRunWindow()");

  // ---- Action settings: compact, help behind ⓘ, More options ----
  const moreAddr = w.eval(`(() => { for (const [addr, sp] of state.specs) { if (/^(core|ooa\\.browser|ooa\\.desktop_ui)/.test(addr)) continue; const pr = sp.action.inputs.properties, req = sp.action.inputs.required || []; let main = 0, more = 0;
    for (const [n, p] of Object.entries(pr)) { if (!req.includes(n) && p.default !== undefined && main >= 3) more++; else main++; } if (more >= 2) return addr; } return null; })()`);
  w.eval(`state.flow.steps.push({ id: "mo", action: ${JSON.stringify(moreAddr)} }); state.selected = ["steps", state.flow.steps.length - 1]; state.multi = new Set(["mo"]); renderSteps(); renderProps();`);
  check("rarely used inputs sit under a closed More options section", await until(() => d.querySelector("#props details.more-opts") && !d.querySelector("#props details.more-opts").open
    && d.querySelectorAll("#props details.more-opts .field").length >= 2), moreAddr);
  check("help text sits behind an ⓘ instead of under every field", d.querySelectorAll("#props .field > .help").length === 0 && d.querySelectorAll("#props .info-tip").length > 0);
  click(d.querySelector("#props details.more-opts summary"));
  w.eval("state.flow.steps.pop(); state.selected = null; state.multi = new Set(); renderSteps(); renderProps()");

  // ---- window editor for web actions ----
  w.eval(`state.specs.set("test.win/click", { pkg: { id: "test.win", name: "Window test" }, action: { id: "click", name: "Click in a window", description: "Test action", timeoutSeconds: 30,
    inputs: { properties: { window: { "x-ooa-type": "Dictionary", description: "Which window" } }, required: [] }, outputs: {} } });
    state.flow.steps.push({ id: "wn", action: "test.win/click" }); state.selected = ["steps", state.flow.steps.length - 1]; renderProps();`);
  check("a window input gets the Window editor", await until(() => d.querySelector("#props .win-editor") && d.querySelectorAll("#props .win-editor .win-source button").length === 3
    && d.querySelector('#props .win-editor input[aria-label="Window title"]') && d.querySelector('#props .win-editor select[aria-label="Title match"]') && d.querySelector('#props .win-editor input[aria-label="Window address"]')));
  change(d.querySelector('#props .win-editor input[aria-label="Window title"]'), "*Invoices*");
  check("the title is stored with its match rule", JSON.stringify(w.eval("state.flow.steps.find((s) => s.id === 'wn').args.window")) === JSON.stringify({ source: "browser", title: "*Invoices*", title_match: "wildcard" }),
    JSON.stringify(w.eval("state.flow.steps.find((s) => s.id === 'wn').args")));
  check("the open-windows list fails gracefully or fills in", await until(() => { const s = d.querySelector("#props .win-pick"); return s && (s.options.length > 1 || s.options[0].text !== "Pick from open windows…"); }, 4000),
    d.querySelector("#props .win-pick") && d.querySelector("#props .win-pick").options[0].text);
  click(d.querySelector('#props .win-editor button[data-source="variable"]'));
  await until(() => d.querySelector('#props .win-editor input[aria-label="Window variable"]'));
  change(d.querySelector('#props .win-editor input[aria-label="Window variable"]'), "$myBrowser");
  check("Variable stores the window variable", JSON.stringify(w.eval("state.flow.steps.find((s) => s.id === 'wn').args.window")) === JSON.stringify({ source: "variable", variable: "$myBrowser" }));
  w.eval("state.flow.steps.pop(); state.specs.delete('test.win/click'); state.selected = null; renderSteps(); renderProps()");

  // ---- Close asks to save ----
  mclick(stepById("m1")); click(d.getElementById("btn-toggle"));
  check("Close is shown for the open agent", !d.getElementById("btn-close").hidden && w.eval("state.dirty") === true);
  click(d.getElementById("btn-close"));
  check("Close asks to save unsaved changes", !d.getElementById("dialog").hidden && d.getElementById("btn-close-save") && d.getElementById("btn-close-discard") && w.eval("state.view") === "bot");
  click(d.getElementById("btn-close-save"));
  check("Save and close saves, then returns to the Automation home", await until(() => w.eval("state.view") === "home" && !w.eval("state.file")
    && JSON.parse(fs.readFileSync(path.join(WS, "My Agents/multi_test.flow.json"), "utf8")).steps[0].disabled === true) && d.getElementById("btn-close").hidden);
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="explorer"]'));
  click(node("My Agents/multi_test.flow.json"));
  await until(() => w.eval("state.file") === "My Agents/multi_test.flow.json" && stepById("m1"));
  mclick(stepById("m2")); click(d.getElementById("btn-toggle"));
  click(d.getElementById("btn-close"));
  click(d.getElementById("btn-close-discard"));
  check("Don't save closes without saving", await until(() => w.eval("state.view") === "home") && !JSON.parse(fs.readFileSync(path.join(WS, "My Agents/multi_test.flow.json"), "utf8")).steps[1].disabled);

  // ---- run window ----
  click(d.querySelector('.rail-btn[data-side="explorer"]'));
  if (d.body.classList.contains("no-left")) click(d.querySelector('.rail-btn[data-side="explorer"]'));
  click(node("My Agents/slow_bot.flow.json"));
  await until(() => w.eval("state.file") === "My Agents/slow_bot.flow.json");
  click(d.getElementById("btn-run"));
  check("the Run window appears during a run", await until(() => { const rw = d.getElementById("run-window"); return rw && rw.textContent.includes("slow") && rw.textContent.includes("of 4 steps") && /Line \d/.test(rw.textContent); }, 5000),
    d.getElementById("run-window") && d.getElementById("run-window").textContent);
  check("it offers Pause and Stop, and the canvas highlights the running step", [...d.querySelectorAll("#run-window button")].some((b) => b.textContent === "Pause") && d.querySelector(".step.running"));
  click([...d.querySelectorAll("#run-window button")].find((b) => b.getAttribute("aria-label") === "Minimise the run window"));
  check("the Run window can be minimised", d.getElementById("run-window").classList.contains("min"));
  click([...d.querySelectorAll("#run-window button")].find((b) => b.getAttribute("aria-label") === "Restore the run window"));
  click([...d.querySelectorAll("#run-window button")].find((b) => b.textContent === "Stop"));
  check("Stop in the Run window stops the run", await until(() => w.eval("state.lastRun && state.lastRun.result.status") === "stopped", 8000) && d.getElementById("run-window").textContent.includes("Stopped"));
  click([...d.querySelectorAll("#run-window button")].find((b) => b.textContent === "Close"));
  check("the finished Run window closes", !d.getElementById("run-window"));

  // ---- Explorer: context menu, new folder, multi-select, copy, paste, duplicate, delete ----
  w.eval("state.dirty = false");
  const ctx = (el) => el.dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 120, clientY: 160 }));
  const menuItem = (t) => [...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item.startsWith(t));
  if (!node("My Agents")) { click(d.querySelector('.rail-btn[data-side="explorer"]')); }
  await until(() => node("My Agents"), 8000);
  ctx(node("My Agents"));
  const tmenu = d.getElementById("ctx-menu");
  const treeItems = ["New folder", "New agent here", "New form here", "New workflow here", "New Python script here", "Rename", "Copy", "Cut", "Paste", "Duplicate", "Delete", "Move to", "Copy path", "Open in File Explorer", "Upload file(s)", "Download"];
  check("right-click on an Explorer item opens its menu", tmenu && treeItems.every((t) => [...tmenu.querySelectorAll("button")].some((b) => b.dataset.item.startsWith(t))),
    tmenu && [...tmenu.querySelectorAll("button")].map((b) => b.dataset.item).join(" | "));
  answers.push("Batch");
  click(menuItem("New folder"));
  check("New folder from the menu creates it in that folder", await until(() => fsx("My Agents/Batch") && node("My Agents/Batch")));
  check("Explorer items have a ⋮ button", !!node("My Agents/other_bot.flow.json").querySelector(".node-menu-btn"));
  mclick(node("My Agents/other_bot.flow.json")); await until(() => w.eval("state.file") === "My Agents/other_bot.flow.json");
  mclick(node("My Agents/slow_bot.flow.json"), { ctrlKey: true });
  check("Ctrl+click selects several Explorer items", w.eval("state.treeMulti.size") === 2 && d.querySelectorAll("#tree .node.selected").length === 2);
  click(node("My Agents/slow_bot.flow.json").querySelector(".node-menu-btn"));
  click(menuItem("Copy 2 items"));
  check("Copy keeps both items", w.eval("state.treeClip && state.treeClip.paths.length") === 2);
  ctx(node("My Agents/Batch"));
  click(menuItem("Paste"));
  check("Paste copies them into the folder", await until(() => fsx("My Agents/Batch/other_bot.flow.json") && fsx("My Agents/Batch/slow_bot.flow.json") && fsx("My Agents/other_bot.flow.json")));
  await until(() => node("My Agents/Batch/slow_bot.flow.json") && node("My Agents/Batch/other_bot.flow.json"), 8000);   // the tree redraws after the paste
  ctx(node("My Agents/Batch/slow_bot.flow.json"));
  click(menuItem("Duplicate"));
  check("Duplicate makes a copy next to it", await until(() => fsx("My Agents/Batch/slow_bot - Copy.flow.json")));
  await until(() => node("My Agents/Batch/slow_bot - Copy.flow.json"), 8000);
  mclick(node("My Agents/Batch/other_bot.flow.json")); await until(() => w.eval("state.file") === "My Agents/Batch/other_bot.flow.json");
  mclick(node("My Agents/Batch/slow_bot.flow.json"), { shiftKey: true });
  check("Shift+click selects a range in the Explorer", w.eval("state.treeMulti.size") === 3, w.eval("[...state.treeMulti].join()"));
  ctx(node("My Agents/Batch"));
  click(menuItem("Delete"));
  check("Delete (after confirming) moves the folder to the trash", await until(() => !fsx("My Agents/Batch") && !node("My Agents/Batch")) && w.eval("state.view") === "home");
  const outside = await (await fetch(BASE + "/api/files/copy", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ path: "My Agents", to: "../.." }) })).json();
  check("file copy refuses paths outside the workspace", outside.error && outside.error.code === "WORKSPACE.OUTSIDE", JSON.stringify(outside));

  // ---- layout: panels never overlap (CSS rules read with jsdom, then the flex arithmetic at 1280, 1024 and 800 px) ----
  const cssText = fs.readFileSync(S + "/studio.css", "utf8");
  const st = d.createElement("style"); st.textContent = cssText; d.head.appendChild(st);
  const cs = (id) => w.getComputedStyle(d.getElementById(id));
  const px = (v) => parseFloat(v) || 0;
  const center = cs("center"), side = cs("side"), propsP = cs("props-panel");
  check("the canvas keeps a minimum width and clips instead of covering its neighbours", px(center.minWidth) >= 300 && center.overflow === "hidden" && center.flexGrow === "1", center.minWidth + " " + center.overflow + " " + center.flexGrow);
  check("the side panels can shrink to a minimum width", px(side.minWidth) >= 160 && side.flexShrink === "1" && px(propsP.minWidth) >= 200 && propsP.flexShrink === "1", side.minWidth + " " + side.flexShrink + " " + propsP.minWidth);
  check("the workbench scrolls sideways when even the minimums do not fit", cs("workbench").overflowX === "auto");
  const mins = (wpx) => {
    let m = { side: px(side.minWidth), props: px(propsP.minWidth), center: px(center.minWidth) };
    for (const [max, sel] of [[1100, /@media \(max-width: 1100px\) \{([^}]*\}[^}]*\}[^}]*\})/], [860, /@media \(max-width: 860px\) \{([^}]*\}[^}]*\}[^}]*\})/]]) {
      const mm = sel.exec(cssText);
      if (mm && wpx <= max) for (const k of ["side", "props", "center"]) { const r = new RegExp("\\." + k + " \\{ min-width: (\\d+)px").exec(mm[1]); if (r) m[k] = +r[1]; }
    }
    return m;
  };
  const layoutAt = (wpx) => {
    const m = mins(wpx), rail = 52, splits = 2;
    let sideW = 288, propsW = 340;
    let centerW = wpx - rail - splits - sideW - propsW;
    if (centerW < m.center) {   // flex-shrink: side and props give way in proportion to their size, never below their minimum
      let need = m.center - centerW;
      const room = (sideW - m.side) + (propsW - m.props);
      const take = Math.min(need, room);
      sideW -= take * (sideW - m.side) / room; propsW -= take * (propsW - m.props) / room;
      centerW = wpx - rail - splits - sideW - propsW;
    }
    return { m, sideW, propsW, centerW, total: rail + splits + sideW + propsW + Math.max(centerW, m.center) };
  };
  for (const wpx of [1280, 1024, 800]) {
    const L = layoutAt(wpx);
    check("at " + wpx + " px the three panels fit side by side without overlapping", L.centerW >= L.m.center && L.sideW >= L.m.side && L.propsW >= L.m.props && L.total <= wpx + 0.5, JSON.stringify(L));
  }
  st.remove();

  // ================= Command settings: options shown only when they apply (command comparison) =================
  {
    const fields = () => [...d.querySelectorAll("#props .field[data-field]")].map((x) => x.dataset.field);
    w.eval(`openBot("Examples/Refunds/lookup_order.flow.json")`);
    await until(() => w.eval("state.file") === "Examples/Refunds/lookup_order.flow.json" && w.eval("state.flow"));
    w.eval(`state.flow.steps.push({ id: "cc_loop", action: "core/loop", args: { iterator: "times", count: 3 }, children: [] },
      { id: "cc_date", action: "ooa.datetime/add", args: { value: "2026-10-23T00:00:00", amount: 2 } }); renderAll(); selectById("cc_loop")`);
    check("Loop N times shows the count and start, not the list or while fields", await until(() => fields().includes("count") && fields().includes("start")
      && !fields().includes("over") && !fields().includes("condition") && !fields().includes("from_index") && !fields().includes("check_at_end")), fields().join());
    check("the Loop starts with its kind: N times, For each or While", [...d.querySelectorAll("#props .loop-kind button")].map((b) => b.textContent).join() === "N times,For each,While");
    click([...d.querySelectorAll("#props .loop-kind button")].find((b) => b.textContent === "For each"));
    await until(() => d.querySelector('#props .field[data-field="iterator"] select'));
    const iter = d.querySelector('#props .field[data-field="iterator"] select');
    check("the Loop type list offers each key and each value of a dictionary", [...iter.options].some((o) => o.value === "dictionary_values") && [...iter.options].some((o) => o.value === "dictionary_keys"));
    change(d.querySelector('#props .field[data-field="iterator"] select'), "list");
    check("switching the Loop to a list shows its range options and hides the counter", await until(() => fields().includes("over") && fields().includes("from_index") && fields().includes("reverse")
      && !fields().includes("count") && !fields().includes("start")), fields().join());
    check("a number with limits says so in its help", (d.querySelector('#props .field[data-field="from_index"] .info-tip') || {}).title.includes("Smallest value: 0"));
    w.eval(`selectById("cc_date")`);
    check("Add to date hides the holiday list until business days are chosen", await until(() => fields().includes("unit") && !fields().includes("holidays")), fields().join());
    change(d.querySelector('#props .field[data-field="unit"] select'), "business_days");
    check("choosing business days shows the holiday list", await until(() => fields().includes("holidays")), fields().join());
    w.eval(`state.flow.steps = state.flow.steps.filter((s) => !s.id.startsWith("cc_")); state.selected = null; renderAll()`);
  }

  // ================= Canvas, Try, variables and connections (reported defects) =================
  {
    w.eval(`openBot("Examples/Refunds/lookup_order.flow.json")`);
    await until(() => w.eval("state.file") === "Examples/Refunds/lookup_order.flow.json" && w.eval("state.flow"));
    if (w.eval("state.canvasMode") === "flow") click([...d.querySelectorAll("button")].find((b) => b.textContent.trim() === "List"));
    check("the canvas shows fixed Start and End rows", await until(() => d.querySelector("#steps .terminal.start") && d.querySelector("#steps .terminal.end")));
    const firstId = w.eval("state.flow.steps[0].id");
    click(d.querySelector("#steps .terminal.start"));
    check("selecting Start says clicked actions go first", d.getElementById("insert-hint").textContent.includes("step 1"));
    w.eval(`addStep("core/log")`);
    check("with Start selected a clicked action becomes step 1", w.eval("state.flow.steps[0].action") === "core/log" && w.eval("state.flow.steps[1].id") === firstId);
    w.eval(`addStep("core/comment")`);
    check("the next action goes after it, as step 2", w.eval("state.flow.steps[1].action") === "core/comment");
    const rows = () => [...d.querySelectorAll("#steps > .step")];
    const lastId = rows()[rows().length - 1].dataset.stepId;
    drag(rows()[rows().length - 1], rows()[0]);
    check("dropping a step on a whole row moves it next to that row", w.eval("state.flow.steps[1].id") === lastId, w.eval("state.flow.steps.map((s) => s.id).join()"));
    const movedId = rows()[0].dataset.stepId;
    drag(rows()[0], d.querySelector("#steps .terminal.end"));
    check("dropping on End puts the step last", await until(() => w.eval("state.flow.steps[state.flow.steps.length - 1].id") === movedId), w.eval("state.flow.steps.map((s) => s.id).join()"));
    w.eval(`addStep("core/try")`);
    const t = w.eval("getAt(state.flow, state.selected)");
    check("a new Try has Try and Catch but no Finally", t.children && t.catch && !("finally" in t), JSON.stringify(Object.keys(t)));
    const addFinally = await until(() => [...d.querySelectorAll("#props button")].some((b) => b.textContent === "+ Finally"));
    click([...d.querySelectorAll("#props button")].find((b) => b.textContent === "+ Finally"));
    check("+ Finally adds the section when wanted", addFinally && Array.isArray(w.eval("getAt(state.flow, findPathById('" + t.id + "')).finally")));
    w.eval(`state.flow.variables.push({ name: "total_n", type: "Number" }, { name: "label_s", type: "String" })`);
    const outs = w.eval(`outputOptions("Number").map((o) => o.value)`);
    check("a Number result is offered only Number variables", outs.includes("total_n") && !outs.includes("label_s"), outs.join());
    w.eval(`state.flow.variables.push({ name: "window_contact_us_acme_corp", type: "Dictionary", default: { source: "browser", title: "Contact us | Acme" }, description: "Window the recording worked in (browser title “Contact us | Acme”)." },
      { name: "tell_us_more", type: "String", default: "x", description: "Recorded typing (change it here or pass it in)." });
      state.flow.steps.push({ id: "rn1", action: "ooa.browser/click", args: { window: "$window_contact_us_acme_corp", selector: "#a" } }); offerShortNames()`);
    check("an older recording offers to shorten its variable names", await until(() => d.getElementById("rename-offer")));
    click(d.querySelector("#rename-offer button.primary"));
    check("long recorded names become Window_1 and Text_1, steps included", w.eval(`state.flow.variables.some((v) => v.name === "Window_1") && state.flow.variables.some((v) => v.name === "Text_1")
      && state.flow.steps.find((s) => s.id === "rn1").args.window === "$Window_1"`));
    w.eval(`state.flow.steps.push({ id: "cn1", action: "ooa.servicenow/create_record", args: { table: "incident" } }); renderAll(); selectById("cn1")`);
    check("a connector step offers a saved connection", await until(() => d.querySelector("#props .conn-field select")));
    w.eval(`state.dirty = false`);
  }

  // ---- parent and child agents: open the agent a Run agent step runs, breadcrumb back, "Run by" ----
  fs.writeFileSync(path.join(WS, "My Agents", "nav_child.flow.json"), JSON.stringify({ kind: "TaskBot", id: "nav_child", name: "Nav child", description: "", variables: [],
    steps: [{ id: "k1", action: "core/comment", args: { text: "child" } }] }));
  fs.writeFileSync(path.join(WS, "My Agents", "nav_parent.flow.json"), JSON.stringify({ kind: "TaskBot", id: "nav_parent", name: "Nav parent", description: "", variables: [],
    steps: [{ id: "n1", action: "core/comment", args: { text: "first" } }, { id: "n2", action: "core/if", args: { condition: { op: "eq", left: 1, right: 1 } },
      children: [{ id: "n3", action: "core/run_bot", args: { automation: "nav_child" } }] }] }));
  w.eval("state.dirty = false; openBot('My Agents/nav_parent.flow.json')");
  await until(() => w.eval("state.file") === "My Agents/nav_parent.flow.json" && stepById("n3"));
  // the name comes from the workspace's agent list: read it again now that nav_child exists (a list read earlier can lack it)
  w.eval("loadAgents(true).then((l) => { state.agents = l; renderSteps(); })");
  check("a Run agent step shows the name of the agent it runs", await until(() => stepById("n3") && stepById("n3").querySelector(".summary").textContent.includes("Nav child"), 10000),
    (stepById("n3") ? stepById("n3").querySelector(".summary").textContent : "no step n3") + " | agents: " + JSON.stringify((w.eval("state.agents") || []).map((a) => a.id)));
  if (!stepById("n3")) throw new Error("the parent agent did not open");
  stepById("n3").dispatchEvent(new w.MouseEvent("contextmenu", { bubbles: true, clientX: 300, clientY: 200 }));
  click([...d.getElementById("ctx-menu").querySelectorAll("button")].find((b) => b.dataset.item === "Open the agent it runs"));
  check("Open the agent it runs opens the child with a breadcrumb back to the step", await until(() => w.eval("state.file") === "My Agents/nav_child.flow.json"
    && d.querySelector("#crumbs .crumb-parent") && d.querySelector("#crumbs .crumb-parent").textContent === "Nav parent › line 3"), d.getElementById("crumbs").textContent);
  check("the child says which agents run it", await until(() => d.querySelector("#crumbs .crumb-callers") && d.querySelector("#crumbs .crumb-callers").textContent.includes("Nav parent")));
  click(d.querySelector("#crumbs .crumb-parent"));
  check("the breadcrumb goes back to the parent, at the Run agent step", await until(() => w.eval("state.file") === "My Agents/nav_parent.flow.json" && w.eval("[...state.multi].join()") === "n3"
    && !d.querySelector("#crumbs .crumb-parent")));
  fs.unlinkSync(path.join(WS, "My Agents", "nav_child.flow.json")); fs.unlinkSync(path.join(WS, "My Agents", "nav_parent.flow.json"));

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
  restoreWorkspace();
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error("UI test crashed:", e); restoreWorkspace(); process.exit(2); });
