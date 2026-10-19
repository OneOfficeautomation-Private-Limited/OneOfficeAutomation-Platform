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
  const until = async (fn, ms = 5000) => { const t = Date.now(); while (Date.now() - t < ms) { try { if (fn()) return true; } catch (_) {} await sleep(50); } return false; };
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
  const navItems = [...d.querySelectorAll("#nav button")].map((b) => b.textContent);
  check("an administrator sees all ten sections", navItems.length === 10 && navItems.includes("Users") && navItems.includes("Audit log"), navItems.join(","));
  check("home shows the dashboard tiles", d.querySelectorAll(".tile").length >= 6);

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

  nav("Devices");
  await until(() => btn("Register a device"));
  click(btn("Register a device"));
  check("device registration shows the agent command with a one-time key", await until(() => !d.getElementById("modal").hidden && d.getElementById("modal-body").textContent.includes("agent register --url " + BASE + " --key ooareg_")));
  click(btn("Close"));

  nav("Credentials");
  await until(() => btn("New locker"));
  click(btn("New locker"));
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

  nav("Global values");
  await until(() => btn("New global value"));
  click(btn("New global value"));
  await until(() => !d.getElementById("modal").hidden);
  d.querySelector("#modal-body input").value = "reports_folder"; d.querySelector("#modal-body textarea").value = '"D:/Reports"';
  click(btn("Save", d.getElementById("modal-actions")));
  check("global values can be added", await until(() => d.getElementById("main").textContent.includes("$global.reports_folder")));

  nav("Audit log");
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
  const carolNav = [...d.querySelectorAll("#nav button")].map((b) => b.textContent);
  check("a bot creator does not see Users or Audit log", !carolNav.includes("Users") && !carolNav.includes("Audit log") && carolNav.includes("Automation"), carolNav.join(","));
  nav("Credentials");
  check("a consumer sees the locker but cannot manage it", await until(() => d.getElementById("main").textContent.includes("finance")) && !btn("Add or update credential"));

  const csrf = await fetch(BASE + "/api/users", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  check("API calls without a session are refused", csrf.status === 401);
  console.log(failures ? failures + " console check(s) FAILED" : "All console checks passed");
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.log("Console test crashed: " + e.stack); process.exit(1); });
