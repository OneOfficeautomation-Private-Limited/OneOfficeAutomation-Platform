package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.*;
import com.oneofficeautomation.packages.UiGuard;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.util.*;

/**
 * Captured web objects. Capture records an element's properties; at run time the element is found again by the
 * properties the developer chose ("use": true), each compared with its own match rule, optionally inside iframes and
 * with an index when several elements match. Instead of properties, an object can be searched by its CSS or XPath.
 *
 * Object format (shared with desktop capture):
 * {"technology": "web", "page": {"url", "title", "url_match", "title_match"}, "frames": ["css of iframe", ...],
 *  "search_by": "properties" | "css" | "xpath", "index": 0, "ignore_case": false,
 *  "properties": [{"name": "id", "value": "q", "match": "equals", "use": true}, ...], "screenshot": "base64 PNG"}
 */
public final class WebObjects {
    private WebObjects() {}

    public static final List<String> MATCHES = List.of("equals", "not_equals", "contains", "starts_with", "ends_with", "wildcard", "regex");

    /** Attributes CSS can pre-filter on with [name="value"] when the match is "equals". */
    private static final Set<String> CSS_ATTRS = Set.of("id", "name", "type", "aria-label", "placeholder", "title", "role", "alt", "for");

    /** The same property reader and match rules used by the picker and by the run-time search. */
    static final String MATCH_JS = """
        (els, q) => {
          const norm = (s) => s == null ? null : String(s).replace(/\\s+/g, ' ').trim();
          const labelOf = (el) => {
            if (el.labels && el.labels.length) return norm(el.labels[0].innerText);
            const by = el.getAttribute && el.getAttribute('aria-labelledby');
            if (by) { const t = by.split(' ').map(i => document.getElementById(i)).filter(Boolean).map(n => n.innerText).join(' '); if (t) return norm(t); }
            return null;
          };
          const prop = (el, n) => {
            switch (n) {
              case 'tag': return el.tagName.toLowerCase();
              case 'text': return norm(el.innerText !== undefined ? el.innerText : el.textContent);
              case 'class': return norm(el.getAttribute('class'));
              case 'label': return labelOf(el);
              case 'value': return el.value !== undefined ? String(el.value) : el.getAttribute('value');
              case 'visible': { const r = el.getBoundingClientRect(); return String(r.width > 0 && r.height > 0); }
              default: { const a = el.getAttribute(n); return a !== null ? a : (n in el && typeof el[n] !== 'object' && typeof el[n] !== 'function' ? String(el[n]) : null); }
            }
          };
          const wild = (p) => new RegExp('^' + p.replace(/[.+^${}()|[\\]\\\\]/g, '\\\\$&').replace(/\\*/g, '.*').replace(/\\?/g, '.') + '$', q.ignoreCase ? 'is' : 's');
          const test = (actual, r) => {
            if (actual == null) return r.match === 'not_equals';
            let a = String(actual), v = String(r.value == null ? '' : r.value);
            if (q.ignoreCase && r.match !== 'regex' && r.match !== 'wildcard') { a = a.toLowerCase(); v = v.toLowerCase(); }
            switch (r.match) {
              case 'not_equals': return a !== v;
              case 'contains': return a.includes(v);
              case 'starts_with': return a.startsWith(v);
              case 'ends_with': return a.endsWith(v);
              case 'wildcard': return wild(String(r.value)).test(String(actual));
              case 'regex': try { return new RegExp(String(r.value), q.ignoreCase ? 'i' : '').test(String(actual)); } catch (e) { return false; }
              default: return a === v;
            }
          };
          const out = [];
          els.forEach((el, i) => { if (q.rules.every(r => test(prop(el, r.name), r))) out.push(i); });
          return out;
        }
        """;

    /** Reads every property of a matched element (for the get_all_properties action). */
    static final String ALL_PROPS_JS = """
        (el) => {
          const o = { tag: el.tagName.toLowerCase(), text: (el.innerText || '').trim().slice(0, 1000) };
          for (const a of el.attributes) o[a.name] = a.value;
          if (el.value !== undefined) o.value = String(el.value);
          if (el.checked !== undefined) o.checked = el.checked;
          if (el.disabled !== undefined) o.disabled = el.disabled;
          const r = el.getBoundingClientRect();
          o.x = Math.round(r.left); o.y = Math.round(r.top); o.width = Math.round(r.width); o.height = Math.round(r.height);
          o.visible = r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
          return o;
        }
        """;

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rules(Map<String, Object> object) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object props = object.get("properties");
        if (!(props instanceof List<?> l)) return out;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("use"))) continue;
            Map<String, Object> p = (Map<String, Object>) m;
            String name = String.valueOf(p.get("name"));
            if (name.equals("css") || name.equals("xpath") || name.equals("relative_xpath")) continue; // used by search_by, not as a rule
            String match = String.valueOf(p.getOrDefault("match", "equals"));
            if (!MATCHES.contains(match)) throw new ActionException("BROWSER.BAD_MATCH", "Property " + name + " has an unknown match rule '" + match + "'", "Use one of " + MATCHES);
            out.add(Map.of("name", name, "value", p.get("value") == null ? "" : String.valueOf(p.get("value")), "match", match));
        }
        return out;
    }

    static String property(Map<String, Object> object, String name) {
        if (object.get("properties") instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> m && name.equals(m.get("name")) && m.get("value") != null) return String.valueOf(m.get("value"));
        return null;
    }

    /** CSS that narrows the candidates (tag and exact attributes); every rule is still checked in the page. */
    static String baseCss(List<Map<String, Object>> rules) {
        String tag = "*";
        StringBuilder attrs = new StringBuilder();
        for (Map<String, Object> r : rules) {
            String n = (String) r.get("name"), v = (String) r.get("value");
            if (!"equals".equals(r.get("match"))) continue;
            if (n.equals("tag") && v.matches("^[a-zA-Z][a-zA-Z0-9-]*$")) tag = v.toLowerCase(Locale.ROOT);
            else if (CSS_ATTRS.contains(n) || n.startsWith("data-")) attrs.append('[').append(n).append("=\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
        }
        return tag + attrs;
    }

    /** The root to search in: the page, or the iframe chain recorded at capture. */
    static Object root(Page page, Map<String, Object> object) {
        Object frames = object.get("frames");
        if (!(frames instanceof List<?> l) || l.isEmpty()) return page;
        FrameLocator f = null;
        for (Object sel : l) f = f == null ? page.frameLocator(String.valueOf(sel)) : f.frameLocator(String.valueOf(sel));
        return f;
    }

    private static Locator locator(Object root, String query) {
        return root instanceof Page p ? p.locator(query) : ((FrameLocator) root).locator(query);
    }

    /** A tab whose URL or title matches the captured page, when the object asks for it; otherwise the session's page. */
    @SuppressWarnings("unchecked")
    static Page page(BrowserPackage.BrowserSession bs, Map<String, Object> object, boolean useCapturedPage) {
        if (!useCapturedPage || !(object.get("page") instanceof Map<?, ?> pm)) return bs.page;
        Map<String, Object> p = (Map<String, Object>) pm;
        for (Page candidate : bs.context.pages()) {
            boolean ok = true;
            if (p.get("url") instanceof String u && !u.isBlank()) ok = matches(candidate.url(), u, String.valueOf(p.getOrDefault("url_match", "starts_with")));
            if (ok && p.get("title") instanceof String t && !t.isBlank() && p.get("title_match") != null) ok = matches(candidate.title(), t, String.valueOf(p.get("title_match")));
            if (ok) { bs.page = candidate; return candidate; }
        }
        return bs.page;
    }

    static boolean matches(String actual, String value, String match) {
        if (actual == null) return "not_equals".equals(match);
        return switch (match) {
            case "not_equals" -> !actual.equals(value);
            case "contains" -> actual.contains(value);
            case "starts_with" -> actual.startsWith(value);
            case "ends_with" -> actual.endsWith(value);
            case "regex" -> java.util.regex.Pattern.compile(value).matcher(actual).find();
            case "wildcard" -> actual.matches(("\\Q" + value + "\\E").replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
            default -> actual.equals(value);
        };
    }

    /** All elements matching the object right now (no waiting): the locator and the indexes that match. */
    @SuppressWarnings("unchecked")
    static Locator[] findAll(Page page, Map<String, Object> object) {
        Object root = root(page, object);
        String by = String.valueOf(object.getOrDefault("search_by", "properties"));
        if (by.equals("css") || by.equals("xpath")) {
            String q = by.equals("css") ? property(object, "css") : property(object, "relative_xpath") != null && Boolean.TRUE.equals(object.get("prefer_relative_xpath")) ? property(object, "relative_xpath") : property(object, "xpath");
            if (q == null || q.isBlank()) throw new ActionException("BROWSER.BAD_OBJECT", "The captured object has no " + by, "Capture the element again, or search by properties.");
            Locator all = locator(root, by.equals("xpath") ? "xpath=" + q : q);
            int n = all.count();
            Locator[] out = new Locator[n];
            for (int i = 0; i < n; i++) out[i] = all.nth(i);
            return out;
        }
        List<Map<String, Object>> rules = rules(object);
        if (rules.isEmpty()) throw new ActionException("BROWSER.BAD_OBJECT", "No property is selected for the search", "Tick at least one property in the captured object, or search by CSS or XPath.");
        Locator base = locator(root, baseCss(rules));
        List<Object> idx = (List<Object>) base.evaluateAll(MATCH_JS, Map.of("rules", rules, "ignoreCase", Boolean.TRUE.equals(object.get("ignore_case"))));
        Locator[] out = new Locator[idx.size()];
        for (int i = 0; i < idx.size(); i++) out[i] = base.nth(((Number) idx.get(i)).intValue());
        return out;
    }

    /**
     * Waits until the object is found and returns its locator (index from the object). When the chosen search finds
     * nothing, the captured CSS and then the XPath are tried as fallbacks, and a healed match is logged.
     */
    static Locator locate(ActionContext ctx, BrowserPackage.BrowserSession bs, Map<String, Object> object, double timeoutMs, boolean useCapturedPage) {
        int index = object.get("index") instanceof Number n ? n.intValue() : 0;
        if (index < 0) throw new ActionException("BROWSER.BAD_OBJECT", "The index must be 0 or more");
        long end = System.currentTimeMillis() + (long) timeoutMs;
        String primary = String.valueOf(object.getOrDefault("search_by", "properties"));
        List<String> order = new ArrayList<>(List.of(primary));
        for (String f : List.of("css", "xpath")) if (!order.contains(f) && property(object, f) != null && !Boolean.FALSE.equals(object.get("self_heal"))) order.add(f);
        while (true) {
            UiGuard.before(ctx);
            ctx.checkCancelled();
            Page page = page(bs, object, useCapturedPage);
            for (String by : order) {
                Map<String, Object> o = new LinkedHashMap<>(object);
                o.put("search_by", by);
                Locator[] found;
                try { found = findAll(page, o); } catch (PlaywrightException e) { found = new Locator[0]; }
                if (found.length > index) {
                    if (!by.equals(primary)) ctx.warn("Captured object found by its " + by + " instead of its " + primary + " (self-healing)");
                    return found[index];
                }
                if (found.length > 0 && by.equals(primary) && index > 0 && System.currentTimeMillis() >= end)
                    throw new ActionException("BROWSER.INDEX_OUT_OF_RANGE", found.length + " element(s) match, so index " + index + " does not exist", "Lower the index or loosen the properties.");
            }
            if (System.currentTimeMillis() >= end)
                throw new ActionException("BROWSER.OBJECT_NOT_FOUND", "No element matches the captured object " + describe(object) + " within " + (long) timeoutMs + " ms",
                        "Open the step's captured object and loosen the properties (contains, wildcard), use Test to check it, or recapture.", true);
            try { page.waitForTimeout(250); } catch (PlaywrightException e) { throw BrowserPackage.pwPublic(e, "Waiting for the element"); }
        }
    }

    static String describe(Map<String, Object> object) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> r : rules(object)) parts.add(r.get("name") + " " + r.get("match") + " '" + r.get("value") + "'");
        String s = String.join(", ", parts);
        return "(" + (s.isEmpty() ? String.valueOf(object.getOrDefault("search_by", "")) : s) + ")";
    }

    /** Validates the shape of an object input early, with a clear error. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object o) {
        Object v = o instanceof String s && s.trim().startsWith("{") ? JsonLite.parse(s) : o;
        if (!(v instanceof Map<?, ?> m)) throw new ActionException("BROWSER.BAD_OBJECT", "The captured object must be a dictionary", "Use Capture in Agent Studio to record it.");
        Map<String, Object> obj = (Map<String, Object>) m;
        if (obj.get("technology") != null && !"web".equals(obj.get("technology")))
            throw new ActionException("BROWSER.BAD_OBJECT", "This object was captured from a " + obj.get("technology") + " application, not a web page", "Use the Desktop objects toolkit for desktop applications.");
        return obj;
    }
}
