// ONEOFFICEAUTOMATION page script: reads element properties and matches captured objects.
// One source for Playwright runs (WebObjects) and the browser extension (content script). Plain declarations only:
// it is pasted inside a function or loaded as a content script.
const __norm = (s) => s == null ? null : String(s).replace(/\s+/g, ' ').trim();
const __generated = (v) => /\d{3,}|^\d/.test(v);
const __xq = (v) => v.indexOf("'") < 0 ? "'" + v + "'" : (v.indexOf('"') < 0 ? '"' + v + '"' : "'" + v.split("'").join('') + "'");
const __cssq = (v) => String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
const __absXpath = (el) => {
  const parts = []; let e = el;
  while (e && e.nodeType === 1) {
    const sib = e.parentNode ? Array.from(e.parentNode.children).filter(c => c.tagName === e.tagName) : [];
    parts.unshift(e.tagName.toLowerCase() + (sib.length > 1 ? '[' + (sib.indexOf(e) + 1) + ']' : ''));
    e = e.parentElement;
  }
  return '/' + parts.join('/');
};
const __domXpath = (el) => {
  const tag = el.tagName.toLowerCase();
  for (const a of ['id', 'name', 'data-testid', 'aria-label', 'placeholder', 'title']) {
    const v = el.getAttribute(a); if (v && !(a === 'id' && __generated(v))) return '//' + tag + '[@' + a + '=' + __xq(v) + ']';
  }
  const tx = (el.innerText || '').trim();
  if (tx && tx.length < 60 && tx.indexOf(String.fromCharCode(10)) < 0) return '//' + tag + '[normalize-space()=' + __xq(tx) + ']';
  let e = el.parentElement, depth = 0;
  while (e && depth < 6) {
    if (e.id && !__generated(e.id)) return '//*[@id=' + __xq(e.id) + ']' + __absXpath(el).slice(__absXpath(e).length);
    e = e.parentElement; depth++;
  }
  return __absXpath(el);
};
const __cssPath = (el) => {
  const tag = el.tagName.toLowerCase();
  const tid = el.getAttribute('data-testid'); if (tid) return tag + '[data-testid="' + __cssq(tid) + '"]';
  if (el.id && !__generated(el.id)) return tag + '#' + CSS.escape(el.id);
  const nm = el.getAttribute('name'); if (nm && /^(INPUT|SELECT|TEXTAREA|FORM|BUTTON)$/.test(el.tagName)) return tag + '[name="' + __cssq(nm) + '"]';
  const parts = []; let e = el;
  while (e && e.nodeType === 1 && parts.length < 6) {
    if (e !== el && e.id && !__generated(e.id)) { parts.unshift('#' + CSS.escape(e.id)); break; }
    let p = e.tagName.toLowerCase();
    const sib = e.parentElement ? Array.from(e.parentElement.children).filter(c => c.tagName === e.tagName) : [];
    if (sib.length > 1) p += ':nth-of-type(' + (sib.indexOf(e) + 1) + ')';
    parts.unshift(p); e = e.parentElement;
  }
  return parts.join(' > ');
};
const __path = (el) => {
  const parts = []; let e = el;
  while (e && e.parentElement) { parts.unshift(Array.from(e.parentElement.children).indexOf(e) + 1); e = e.parentElement; }
  return parts.join('/');
};
const __describe = (el) => {
  if (!el || el.nodeType !== 1) return null;
  let d = el.tagName.toLowerCase();
  if (el.id) d += '#' + el.id;
  else { const c = (el.getAttribute('class') || '').trim().split(/\s+/).filter(Boolean).slice(0, 2); if (c.length) d += '.' + c.join('.'); }
  return d;
};
const __labelOf = (el) => {
  if (el.labels && el.labels.length) return __norm(el.labels[0].innerText);
  const by = el.getAttribute && el.getAttribute('aria-labelledby');
  if (by) { const t = by.split(' ').map(i => document.getElementById(i)).filter(Boolean).map(n => n.innerText).join(' '); if (t) return __norm(t); }
  return null;
};
const __rect = (el) => {
  const r = el.getBoundingClientRect();
  return { left: Math.round(r.left + window.scrollX), top: Math.round(r.top + window.scrollY), width: Math.round(r.width), height: Math.round(r.height) };
};
const __one = (el, n) => {
  switch (n) {
    case 'tag': return el.tagName.toLowerCase();
    case 'text': return __norm(el.innerText !== undefined ? el.innerText : el.textContent);
    case 'class': return __norm(el.getAttribute('class'));
    case 'label': return __labelOf(el);
    case 'value': return el.value !== undefined ? String(el.value) : el.getAttribute('value');
    case 'visible': { const r = el.getBoundingClientRect(); return String(r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'); }
    case 'enabled': return String(!el.disabled);
    case 'checked': return el.checked === undefined ? null : String(el.checked);
    case 'selected': return el.tagName === 'SELECT' && el.selectedIndex >= 0 ? __norm(el.options[el.selectedIndex].text) : null;
    case 'dom_xpath': case 'relative_xpath': return __domXpath(el);
    case 'xpath': return __absXpath(el);
    case 'css': return __cssPath(el);
    case 'path': return __path(el);
    case 'parent': return __describe(el.parentElement);
    case 'inner_html': return el.innerHTML;
    case 'outer_html': return el.outerHTML;
    case 'tag_index': return String(Array.from(document.getElementsByTagName(el.tagName)).indexOf(el));
    case 'source_index': return String(Array.from(document.getElementsByTagName('*')).indexOf(el));
    case 'child_count': return String(el.children.length);
    case 'left': case 'top': case 'width': case 'height': return String(__rect(el)[n]);
    case 'in_frame': return String(window !== window.top);
    case 'frame_url': return location.href;
    default: { const a = el.getAttribute(n); return a !== null ? a : (n in el && typeof el[n] !== 'object' && typeof el[n] !== 'function' ? String(el[n]) : null); }
  }
};
const __props = (el) => {
  const p = {};
  const put = (k, v, max) => { if (v !== null && v !== undefined && String(v).trim() !== '') p[k] = String(v).trim().slice(0, max || 500); };
  put('tag', __one(el, 'tag'));
  for (const a of ['id', 'name', 'type', 'class', 'aria-label', 'placeholder', 'title', 'role', 'href', 'src', 'alt', 'for']) put(a, el.getAttribute(a));
  for (const a of el.attributes) if (!(a.name in p) && a.name !== 'style' && !a.name.startsWith('on')) put(a.name, a.value);
  put('text', __one(el, 'text'), 1000);
  if (el.value !== undefined && el.getAttribute('type') !== 'password') put('value', el.value);
  put('label', __labelOf(el));
  for (const k of ['dom_xpath', 'css', 'xpath', 'path', 'parent', 'tag_index', 'source_index', 'child_count', 'left', 'top', 'width', 'height',
                   'visible', 'enabled', 'checked', 'selected', 'in_frame', 'frame_url']) put(k, __one(el, k));
  put('inner_html', __one(el, 'inner_html'));
  put('outer_html', __one(el, 'outer_html'));
  return p;
};

/** Indexes of the elements that satisfy every rule ({name, value, match}); a ticked CSS or XPath rule means "the element it finds". */
const __matches = (els, rules, ignoreCase) => {
  const wild = (p) => new RegExp('^' + p.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$', ignoreCase ? 'is' : 's');
  const xpathHits = {};
  const inXpath = (el, x) => {
    if (!(x in xpathHits)) {
      const set = new Set();
      try { const r = document.evaluate(x, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null); for (let i = 0; i < r.snapshotLength; i++) set.add(r.snapshotItem(i)); } catch (e) { }
      xpathHits[x] = set;
    }
    return xpathHits[x].has(el);
  };
  const test = (el, r) => {
    if (r.match === 'equals' && r.name === 'css') { try { return el.matches(r.value); } catch (e) { } }
    if (r.match === 'equals' && (r.name === 'xpath' || r.name === 'dom_xpath' || r.name === 'relative_xpath')) return inXpath(el, r.value);
    const actual = __one(el, r.name);
    if (actual == null) return r.match === 'not_equals';
    let a = String(actual), v = String(r.value == null ? '' : r.value);
    if (ignoreCase && r.match !== 'regex' && r.match !== 'wildcard') { a = a.toLowerCase(); v = v.toLowerCase(); }
    switch (r.match) {
      case 'not_equals': return a !== v;
      case 'contains': return a.includes(v);
      case 'starts_with': return a.startsWith(v);
      case 'ends_with': return a.endsWith(v);
      case 'wildcard': return wild(String(r.value)).test(String(actual));
      case 'regex': try { return new RegExp(String(r.value), ignoreCase ? 'i' : '').test(String(actual)); } catch (e) { return false; }
      default: return a === v;
    }
  };
  const out = [];
  els.forEach((el, i) => { if (rules.every((r) => test(el, r))) out.push(i); });
  return out;
};
