// ONEOFFICEAUTOMATION Browser Connector: background service worker (Edge, Chrome), or background script (Firefox:
// manifest.firefox.json, which has no debugger interface; see FIREFOX below).
// Talks to the native-messaging host (com.oneofficeautomation.bridge), which relays requests from agents and Agent
// Studio. Tab and window work happens here; element work is passed to the content script of the right frame.

const HOST = "com.oneofficeautomation.bridge";
const FIREFOX = /Firefox\//.test(navigator.userAgent);
const BROWSER = FIREFOX ? "firefox" : (navigator.userAgentData && navigator.userAgentData.brands || []).some((b) => /Edge/i.test(b.brand)) ? "edge"
  : /Edg\//.test(navigator.userAgent) ? "edge" : "chrome";
let port = null;
let status = { connected: false, error: null, since: null };

function connect() {
  if (port) return;
  try {
    port = chrome.runtime.connectNative(HOST);
  } catch (e) { status = { connected: false, error: String(e), since: Date.now() }; return; }
  // connected once the host is still there a moment later: a host that cannot start disconnects at once
  const mine = port;
  status = { connected: false, error: "Starting the connector on this computer...", since: Date.now() };
  setTimeout(() => { if (port === mine) status = { connected: true, error: null, since: Date.now() }; }, 2000);
  port.onMessage.addListener(onRequest);
  port.onDisconnect.addListener((p) => {
    // Chrome and Edge give the reason in runtime.lastError, Firefox in the port's error
    const why = (p && p.error && p.error.message) || (chrome.runtime.lastError && chrome.runtime.lastError.message) || "Disconnected";
    status = { connected: false, error: why, since: Date.now() };
    port = null;
  });
  send({ event: "hello", browser: BROWSER, version: chrome.runtime.getManifest().version });
}

/**
 * scripting.executeScript that fails when the script fails. Chrome and Edge reject the call; Firefox resolves it and
 * puts the error in the result's error, which would otherwise read as an empty result.
 */
async function executeScript(details) {
  const results = await chrome.scripting.executeScript(details);
  for (const r of results || []) if (r && r.error) throw (r.error instanceof Error ? r.error : new Error((r.error && r.error.message) || String(r.error)));
  return results;
}

function send(msg) { if (port) { try { port.postMessage(msg); } catch (e) { /* reconnect on the next alarm */ } } }

// Reconnect when the host went away (for example the agent install was updated).
chrome.alarms.create("ooa-keepalive", { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(() => { if (!port) connect(); });
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(() => { connect(); injectIntoOpenTabs(); });
connect();

/** Tabs that were open before the extension was installed or reloaded get the content scripts now. */
async function injectIntoOpenTabs() {
  for (const t of await chrome.tabs.query({})) {
    if (!/^(https?|file):/.test(t.url || "")) continue;
    try {
      const [{ result }] = await executeScript({ target: { tabId: t.id }, func: () => !!window.__ooaContent });
      if (!result) await executeScript({ target: { tabId: t.id, allFrames: true }, files: ["ooa-page.js", "content.js"] });
    } catch (e) { /* pages the browser protects */ }
  }
}

async function tabRow(t) {
  const w = await chrome.windows.get(t.windowId).catch(() => null);
  return { tab_id: t.id, window_id: t.windowId, title: t.title || "", url: t.url || "", active: !!t.active,
    focused: !!(w && w.focused), index: t.index, browser: BROWSER, incognito: !!t.incognito };
}

async function findTab(a) {
  if (a.tab_id != null) return chrome.tabs.get(Number(a.tab_id));
  const [t] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (!t) throw new Error("There is no open tab");
  return t;
}

/** Waits until the tab has finished loading, up to ms. */
function settled(tabId, ms) {
  return new Promise((resolve) => {
    const end = Date.now() + ms;
    const check = async () => {
      const t = await chrome.tabs.get(tabId).catch(() => null);
      if (!t || t.status === "complete" || Date.now() > end) return resolve(t);
      setTimeout(check, 150);
    };
    check();
  });
}

/** Sends an element request to the content script of the frame the object lives in (all frames answer only for their own path). */
async function toFrames(tabId, msg) {
  try { return await chrome.tabs.sendMessage(tabId, msg); }
  catch (e) {
    // the page was loaded before the extension: add the scripts and try once more
    await executeScript({ target: { tabId, allFrames: true }, files: ["ooa-page.js", "content.js"] }).catch(() => {});
    return chrome.tabs.sendMessage(tabId, msg);
  }
}

/**
 * A picture of the window's visible tab. The window is brought to the front first: the browser stops drawing windows
 * that are hidden behind others, and a capture of such a window never finishes (hence the time limit).
 */
async function visibleTab(windowId) {
  const w = await chrome.windows.get(windowId).catch(() => null);
  if (w && (w.state === "minimized" || !w.focused)) { await chrome.windows.update(windowId, { focused: true, state: w.state === "minimized" ? "normal" : undefined }).catch(() => {}); await new Promise((r) => setTimeout(r, 250)); }
  return Promise.race([chrome.tabs.captureVisibleTab(windowId, { format: "png" }),
    new Promise((_, no) => setTimeout(() => no(Object.assign(new Error("The browser did not draw the window (is it hidden or on a locked screen?)"), { code: "BROWSER.SCREENSHOT_FAILED" })), 8000))]);
}

/**
 * The tab drawn by the browser itself, also when its window is hidden behind other windows (the browser shows its
 * "being debugged" bar for a moment). Returns a data URL like captureVisibleTab.
 */
async function renderedTab(tabId) {
  // Firefox: tabs.captureTab pictures any tab, shown or not
  if (FIREFOX) return browser.tabs.captureTab(tabId, { format: "png" });
  const target = { tabId };
  await chrome.debugger.attach(target, "1.3");
  try {
    const r = await chrome.debugger.sendCommand(target, "Page.captureScreenshot", { format: "png" });
    return "data:image/png;base64," + r.data;
  } finally { await chrome.debugger.detach(target).catch(() => {}); }
}

async function tabPicture(tab) {
  try { return await visibleTab(tab.windowId); } catch (e) { return renderedTab(tab.id); }
}

/** Element screenshot: the visible tab, cut to the element's rectangle. */
async function crop(tab, rect, ratio) {
  const url = await tabPicture(tab);
  const blob = await (await fetch(url)).blob();
  const bmp = await createImageBitmap(blob);
  const r = ratio || 1, pad = 4;
  const x = Math.max(0, Math.floor((rect.x - pad) * r)), y = Math.max(0, Math.floor((rect.y - pad) * r));
  const w = Math.min(bmp.width - x, Math.ceil((rect.width + 2 * pad) * r)), h = Math.min(bmp.height - y, Math.ceil((rect.height + 2 * pad) * r));
  if (w < 2 || h < 2) return null;
  const canvas = new OffscreenCanvas(Math.min(w, 1200), Math.min(h, 600));
  canvas.getContext("2d").drawImage(bmp, x, y, canvas.width, canvas.height, 0, 0, canvas.width, canvas.height);
  const out = await canvas.convertToBlob({ type: "image/png" });
  const bytes = new Uint8Array(await out.arrayBuffer());
  let bin = ""; for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

/** run_js for pages with a strict content security policy: Runtime.evaluate through the debugger protocol. */
async function runJsByDebugger(tabId, code, args) {
  if (FIREFOX) throw new Error("This page's security policy blocks scripts, and Firefox has no other way in: run this step in Edge or Chrome");
  const target = { tabId };
  await chrome.debugger.attach(target, "1.3");
  try {
    // the code is placed in the expression itself (eval would still be refused): first as an expression, then as
    // statements with return
    const wrap = (body) => "(async (args) => { let v = " + body + "; if (typeof v === 'function') v = v(...args); v = await v; return v === undefined ? null : JSON.parse(JSON.stringify(v)); })("
      + JSON.stringify(args) + ")";
    let r = await chrome.debugger.sendCommand(target, "Runtime.evaluate", { expression: wrap("(" + code + "\n)"), awaitPromise: true, returnByValue: true, userGesture: true });
    if (r.exceptionDetails && /SyntaxError/.test(JSON.stringify(r.exceptionDetails)))
      r = await chrome.debugger.sendCommand(target, "Runtime.evaluate", { expression: wrap("await (async () => {\n" + code + "\n})()"), awaitPromise: true, returnByValue: true, userGesture: true });
    if (r.exceptionDetails) throw new Error((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text || "The script failed");
    return r.result ? (r.result.value === undefined ? null : r.result.value) : null;
  } finally { await chrome.debugger.detach(target).catch(() => {}); }
}

const OPS = {
  async ping() { return { browser: BROWSER, version: chrome.runtime.getManifest().version }; },
  async list_tabs() { return Promise.all((await chrome.tabs.query({})).map(tabRow)); },
  async open(a) {
    const url = a.url || "about:blank";
    let tab;
    if (a.mode === "existing_tab") { tab = await findTab(a); await chrome.tabs.update(tab.id, { url, active: true }); }
    else if (a.mode === "new_tab") { const base = a.tab_id != null ? await chrome.tabs.get(Number(a.tab_id)) : null; tab = await chrome.tabs.create({ url, windowId: base ? base.windowId : undefined, active: true }); }
    else { const w = await chrome.windows.create({ url, focused: true, state: a.maximized ? "maximized" : "normal" }); tab = w.tabs[0]; }
    await settled(tab.id, a.timeout_ms || 60000);
    return tabRow(await chrome.tabs.get(tab.id));
  },
  async activate(a) { const t = await findTab(a); await chrome.tabs.update(t.id, { active: true }); await chrome.windows.update(t.windowId, { focused: true }); return tabRow(await chrome.tabs.get(t.id)); },
  async navigate(a) { const t = await findTab(a); await chrome.tabs.update(t.id, { url: a.url }); await settled(t.id, a.timeout_ms || 60000); return tabRow(await chrome.tabs.get(t.id)); },
  async back(a) {
    const t = await findTab(a);
    const [{ result: len }] = await executeScript({ target: { tabId: t.id }, func: () => history.length });
    const steps = Math.max(1, Number(a.steps || 1));
    if (a.strict && steps >= len) throw new Error("The tab's history has only " + (len - 1) + " page(s) to go back to");
    for (let i = 0; i < steps; i++) { await chrome.tabs.goBack(t.id).catch(() => {}); await settled(t.id, 15000); }
    return tabRow(await chrome.tabs.get(t.id));
  },
  async forward(a) { const t = await findTab(a); await chrome.tabs.goForward(t.id); await settled(t.id, 15000); return tabRow(await chrome.tabs.get(t.id)); },
  async reload(a) { const t = await findTab(a); await chrome.tabs.reload(t.id); await settled(t.id, a.timeout_ms || 60000); return tabRow(await chrome.tabs.get(t.id)); },
  async close(a) {
    if (a.what === "all") { for (const w of await chrome.windows.getAll()) await chrome.windows.remove(w.id).catch(() => {}); return true; }
    const t = await findTab(a);
    if (a.what === "window") await chrome.windows.remove(t.windowId); else await chrome.tabs.remove(t.id);
    return true;
  },
  /**
   * Runs the agent's JavaScript in the page itself (main world), returning its value. A page whose content security
   * policy forbids evaluating code (no 'unsafe-eval') gets it through the browser's debugger protocol instead, which
   * the policy does not cover (the browser shows its "is debugging this browser" bar meanwhile).
   */
  async run_js(a) {
    const t = await findTab(a);
    try {
      const [{ result }] = await executeScript({ target: { tabId: t.id, frameIds: a.frame_id != null ? [a.frame_id] : undefined }, world: "MAIN",
        func: (code, args) => {
          // An expression ("document.title"), a function ("() => ...") or statements with return.
          let fn;
          try { fn = new Function("args", "return (" + code + ");"); } catch (e) {
            if (e instanceof EvalError || /unsafe-eval|Content Security Policy/i.test(String(e && e.message))) throw new Error("OOA_CSP");
            fn = new Function("args", code);
          }
          let v = fn(args);
          if (typeof v === "function") v = v(...(args || []));
          return Promise.resolve(v).then((x) => (x === undefined ? null : JSON.parse(JSON.stringify(x))));
        }, args: [a.code, a.args || []] });
      return result;
    } catch (e) {
      if (!/OOA_CSP|unsafe-eval|Content Security Policy|EvalError/i.test(String(e && e.message))) throw e;
      return runJsByDebugger(t.id, a.code, a.args || []);
    }
  },
  async call_function(a) {
    const t = await findTab(a);
    const [{ result }] = await executeScript({ target: { tabId: t.id }, world: "MAIN",
      func: (name, args) => {
        const f = name.split(".").reduce((o, k) => (o == null ? o : o[k]), window);
        if (typeof f !== "function") throw new Error("The page has no function " + name);
        return Promise.resolve(f(...args)).then((v) => (v === undefined ? null : JSON.parse(JSON.stringify(v))));
      }, args: [a.name, a.args || []] });
    return result;
  },
  async page_source(a) {
    const t = await findTab(a);
    const [{ result }] = await executeScript({ target: { tabId: t.id }, func: () => "<!DOCTYPE html>\n" + document.documentElement.outerHTML });
    return result;
  },
  async info(a) { return tabRow(await findTab(a)); },
  /** Links on the page (no page script needed, so strict pages work too). */
  async links(a) {
    const t = await findTab(a);
    const [{ result }] = await executeScript({ target: { tabId: t.id }, func: () => ({ url: location.href, links: Array.from(document.querySelectorAll("a[href], area[href]")).map((x) => x.href) }) });
    return result;
  },
  /** Clicks the element that starts a download and waits for the file; returns where the browser saved it. */
  async download_click(a) {
    const t = await findTab(a);
    const started = new Promise((resolve) => { const on = (item) => { chrome.downloads.onCreated.removeListener(on); resolve(item); }; chrome.downloads.onCreated.addListener(on); });
    const res = await toFrames(t.id, { ooa: "element", req: Object.assign({}, a.element, { action: "click" }) });
    if (res && res.error) throw Object.assign(new Error(res.error), { code: res.code });
    const item = await Promise.race([started, new Promise((_, no) => setTimeout(() => no(new Error("No download started")), a.timeout_ms || 120000))]);
    const end = Date.now() + (a.timeout_ms || 120000);
    while (Date.now() < end) {
      const [d] = await chrome.downloads.search({ id: item.id });
      if (d && d.state === "complete") return { path: d.filename, url: d.url };
      if (d && d.state === "interrupted") throw new Error("The download stopped: " + (d.error || "interrupted"));
      await new Promise((r) => setTimeout(r, 300));
    }
    throw new Error("The download did not finish in time");
  },
  async screenshot(a) {
    const t = await findTab(a);
    await chrome.tabs.update(t.id, { active: true });
    const url = await tabPicture(t);
    return url.slice(url.indexOf(",") + 1);
  },
  /** Element work: find the captured object (or selector) and act on it, in the frame it belongs to. */
  async element(a) {
    const t = await findTab(a);
    const res = await toFrames(t.id, { ooa: "element", req: a });
    if (!res) throw new Error("No frame of the tab answered; the object's iframe may not be loaded");
    if (res.error) throw Object.assign(new Error(res.error), { code: res.code });
    if (res.shot_rect) res.result = await crop(t, res.shot_rect, res.ratio);
    return { result: res.result === undefined ? null : res.result, healed_by: res.healed_by || "" };
  },
  async pick(a) {
    const t = await findTab(a);
    await chrome.tabs.update(t.id, { active: true }); await chrome.windows.update(t.windowId, { focused: true });
    await toFrames(t.id, { ooa: "pick", on: true, token: a.token });
    return { tab_id: t.id };
  },
  async pick_cancel(a) { const t = await findTab(a); await toFrames(t.id, { ooa: "pick", on: false }); return true; },
  async record(a) {
    const t = await findTab(a);
    await chrome.storage.session.set({ recording: { tab_id: t.id, token: a.token } });
    await chrome.tabs.update(t.id, { active: true }); await chrome.windows.update(t.windowId, { focused: true });
    await toFrames(t.id, { ooa: "record", on: true, token: a.token });
    return { tab_id: t.id };
  },
  async record_stop(a) {
    const { recording } = await chrome.storage.session.get("recording");
    await chrome.storage.session.remove("recording");
    if (recording) await toFrames(recording.tab_id, { ooa: "record", on: false }).catch(() => {});
    return true;
  },
};

async function onRequest(msg) {
  if (!msg || msg.id == null) return;
  const op = OPS[msg.op];
  if (!op) { send({ id: msg.id, ok: false, code: "EXT.BAD_OP", error: "Unknown request " + msg.op }); return; }
  try { send({ id: msg.id, ok: true, result: await op(msg.args || {}) }); }
  catch (e) { send({ id: msg.id, ok: false, code: e.code || "EXT.FAILED", error: String(e && e.message || e) }); }
}

// Events from content scripts: a picked element, recorded steps, the recording being stopped on the page. A picked
// element and each recorded step (in the top frame) get a picture of the element; events go out one after another, in
// the order they happened, while pictures are taken.
let events = Promise.resolve();
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg && msg.ooa === "status") { reply({ ...status, browser: BROWSER }); return; }
  if (!msg || !msg.event || !sender.tab) return;
  const ev = Object.assign({}, msg, { tab_id: sender.tab.id, window_id: sender.tab.windowId, frame_id: sender.frameId, browser: BROWSER });
  const shot = (msg.event === "picked" || msg.event === "recorded") && msg.rect && sender.frameId === 0 ? crop(sender.tab, msg.rect, msg.ratio)
    // the browser allows two pictures a second: typing ends as the next click starts, so the second one may wait
    .catch(() => new Promise((r) => setTimeout(r, 600)).then(() => crop(sender.tab, msg.rect, msg.ratio))).catch(() => null) : null;
  events = events.then(async () => { if (shot) ev.screenshot = await shot; send(ev); }).catch(() => {});
});

// A recording follows the tab across page loads: new pages in it start recording again.
chrome.webNavigation.onCompleted.addListener(async (d) => {
  const { recording } = await chrome.storage.session.get("recording");
  if (recording && recording.tab_id === d.tabId) chrome.tabs.sendMessage(d.tabId, { ooa: "record", on: true, token: recording.token }, { frameId: d.frameId }).catch(() => {});
});
