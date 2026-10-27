// Interface languages for One Command Center and One Agent Studio (K3). The pages are written in English; this script
// swaps each visible text, placeholder, tooltip and accessible name for the chosen language as the page draws it, from
// ooa-i18n-<language>.json. A text missing from the catalog stays in English. Texts built from parts use keys with {}
// ("Delete {}?", "{} runs, {} failed"). Names, ids and code (elements with class mono, code, pre, textarea) are never translated.
// The language is kept per browser (Language in the user menu or the Studio view menu); the browser's own language is
// the default when there is a catalog for it.
(function () {
  "use strict";
  var LANGUAGES = [["en", "English"], ["de", "Deutsch"], ["fr", "Français"], ["es", "Español"], ["ja", "日本語"]];
  var KEY = "ooa.lang";
  var SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, CODE: 1, PRE: 1, NOSCRIPT: 1 };
  var ATTRS = ["placeholder", "title", "aria-label"];
  var exact = null, templates = [];

  function stored() { try { return localStorage.getItem(KEY); } catch (_) { return null; } }
  function known(l) { return LANGUAGES.some(function (x) { return x[0] === l; }); }
  function pick() {
    var s = stored();
    if (s && known(s)) return s;
    var n = ((navigator.languages && navigator.languages[0]) || navigator.language || "en").slice(0, 2).toLowerCase();
    return known(n) ? n : "en";
  }
  var lang = pick();

  function lookup(t) {
    var v = exact[t];
    if (v !== undefined) return v;
    for (var i = 0; i < templates.length; i++) {
      var p = templates[i];
      if (t.lastIndexOf(p.first, 0) !== 0 || t.slice(t.length - p.last.length) !== p.last) continue;
      var m = p.re.exec(t);
      if (!m) continue;
      var k = 0;
      return p.v.replace(/\{\}/g, function () {
        var part = m[++k] || "", tr = exact[part.trim()];
        return tr !== undefined ? part.replace(part.trim(), tr) : part;
      });
    }
    return undefined;
  }

  function template(key, value) {
    var parts = key.split("{}");
    var re = new RegExp("^" + parts.map(function (x) { return x.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }).join("(.+?)") + "$");
    return { first: parts[0], last: parts[parts.length - 1], fixed: key.length - 2 * (parts.length - 1), re: re, v: value };
  }

  /** The text in the chosen language (unchanged when there is no translation). */
  function t(s) {
    if (!exact || typeof s !== "string") return s;
    var trimmed = s.trim();
    if (!trimmed || !/[A-Za-z]/.test(trimmed)) return s;
    var v = lookup(trimmed);
    return v === undefined || v === trimmed ? s : s.replace(trimmed, v);
  }

  function skipped(el) {
    for (var e = el; e && e.nodeType === 1; e = e.parentElement) {
      if (SKIP[e.tagName] || e.isContentEditable || (e.classList && (e.classList.contains("mono") || e.hasAttribute("data-no-i18n")))) return true;
    }
    return false;
  }

  function text(node) {
    if (!node.parentElement || skipped(node.parentElement)) return;
    var v = t(node.data);
    if (v !== node.data) node.data = v;
  }

  function attrs(el) {
    for (var i = 0; i < ATTRS.length; i++) {
      var a = el.getAttribute && el.getAttribute(ATTRS[i]);
      if (a) { var v = t(a); if (v !== a) el.setAttribute(ATTRS[i], v); }
    }
    if (el.tagName === "INPUT" && (el.type === "button" || el.type === "submit") && el.value) { var w = t(el.value); if (w !== el.value) el.value = w; }
  }

  function walk(root) {
    if (root.nodeType === 3) { text(root); return; }
    if (root.nodeType !== 1 || SKIP[root.tagName]) return;
    if (!skipped(root)) attrs(root);
    var w = document.createTreeWalker(root, 5 /* elements and texts */);
    for (var n = w.nextNode(); n; n = w.nextNode()) {
      if (n.nodeType === 3) text(n);
      else if (!skipped(n)) attrs(n);
    }
  }

  function observe() {
    walk(document.documentElement);
    document.title = t(document.title);
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r.type === "childList") for (var j = 0; j < r.addedNodes.length; j++) walk(r.addedNodes[j]);
        else if (r.type === "characterData") text(r.target);
        else if (r.type === "attributes" && !skipped(r.target)) attrs(r.target);
      }
    }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
    // the browser's own dialogs are not part of the page
    ["alert", "confirm", "prompt"].forEach(function (f) {
      var orig = window[f];
      if (typeof orig === "function") window[f] = function (msg) { var a = Array.prototype.slice.call(arguments); a[0] = t(msg); return orig.apply(window, a); };
    });
  }

  function ready() { document.documentElement.classList.remove("i18n-wait"); }

  function fillPickers() {
    var sels = document.querySelectorAll("select[data-ooa-lang]");
    for (var i = 0; i < sels.length; i++) {
      var s = sels[i];
      if (s.options.length) continue;
      LANGUAGES.forEach(function (l) { var o = document.createElement("option"); o.value = l[0]; o.textContent = l[1]; s.appendChild(o); });
      s.value = lang;
      s.setAttribute("data-no-i18n", "");
      s.addEventListener("change", function (e) { set(e.target.value); });
    }
  }

  function set(l) {
    try { localStorage.setItem(KEY, l); } catch (_) { /* storage unavailable: this page only */ }
    location.reload();
  }

  document.documentElement.lang = lang;
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fillPickers); else fillPickers();
  window.OOA_I18N = { lang: lang, languages: LANGUAGES, t: function (s) { return t(s); }, set: set };
  if (lang === "en") return;
  document.documentElement.classList.add("i18n-wait");
  setTimeout(ready, 1500);   // never leave the page hidden, catalog or not
  var x = new XMLHttpRequest();
  x.open("GET", "ooa-i18n-" + lang + ".json");
  x.onload = function () {
    try {
      var cat = JSON.parse(x.responseText);
      exact = {};
      Object.keys(cat).forEach(function (k) {
        if (!cat[k]) return;
        if (k.indexOf("{}") < 0) exact[k] = cat[k];
        else templates.push(template(k, cat[k]));
      });
      // the longest fixed text first, so "Delete the dashboard {}?" wins over "Delete {}"
      templates.sort(function (p, q) { return q.fixed - p.fixed; });
      observe();
    } catch (_) { /* a broken catalog leaves the page in English */ }
    ready();
  };
  x.onerror = ready;
  x.send();
})();
