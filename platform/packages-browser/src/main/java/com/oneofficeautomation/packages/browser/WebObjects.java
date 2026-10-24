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

    /**
     * Property readers shared by Capture, the recorder and the run-time search, so a property means the same thing
     * everywhere. Plain function declarations: paste inside any script (the picker and recorder use the OOA_LIB marker).
     *
     * Property names: tag, id, name, type, class, text (inner text), value, label, placeholder, title, aria-label,
     * role, href, src, alt, for, data-*, dom_xpath (short XPath), xpath (full XPath), css, path (child positions from
     * the page root), parent, inner_html, outer_html, tag_index, source_index, left, top, width, height, visible,
     * enabled, checked, selected, child_count, in_frame, frame_url; any other name reads that attribute.
     */
    static final String LIB_JS = resource("ooa-page.js");

    /** The run-time search: which of the candidate elements satisfy every ticked property. */
    static final String MATCH_JS = "(els, q) => {\n" + LIB_JS + "\nreturn __matches(els, q.rules, q.ignoreCase);\n}";

    static String resource(String name) {
        try (var in = WebObjects.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing page script " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    /** Reads every property of a matched element (get_all_properties and Test): the capture names plus x, y and booleans. */
    static final String ALL_PROPS_JS = "(el) => {\n" + LIB_JS + """
          const o = __props(el);
          o.text = (el.innerText || '').trim().slice(0, 1000);
          if (el.value !== undefined) o.value = String(el.value);
          if (el.checked !== undefined) o.checked = el.checked;
          if (el.disabled !== undefined) o.disabled = el.disabled;
          const r = el.getBoundingClientRect();
          o.x = Math.round(r.left); o.y = Math.round(r.top); o.width = Math.round(r.width); o.height = Math.round(r.height);
          o.visible = r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
          return o;
        }
        """;

    /** Index of the element nearest to a point (page coordinates), or -1 when none is within max pixels. */
    static final String NEAREST_JS = """
        (els, q) => {
          let best = -1, bd = Infinity;
          els.forEach((e, i) => {
            const r = e.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) return;
            const d = Math.hypot(r.left + window.scrollX - q.x, r.top + window.scrollY - q.y);
            if (d < bd) { bd = d; best = i; }
          });
          return bd <= q.max ? best : -1;
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
            if (name.isBlank()) continue;
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
            // XPath: the short DOM XPath first, then the full one (older objects: the relative one only when preferred).
            List<String> queries = new ArrayList<>();
            if (by.equals("css")) queries.add(property(object, "css"));
            else {
                queries.add(property(object, "dom_xpath"));
                if (Boolean.TRUE.equals(object.get("prefer_relative_xpath"))) queries.add(property(object, "relative_xpath"));
                queries.add(property(object, "xpath"));
                if (queries.stream().allMatch(Objects::isNull)) queries.add(property(object, "relative_xpath"));
            }
            queries.removeIf(q -> q == null || q.isBlank());
            if (queries.isEmpty()) throw new ActionException("BROWSER.BAD_OBJECT", "The captured object has no " + by, "Capture the element again, or search by properties.");
            for (String q : queries) {
                Locator all = locator(root, by.equals("xpath") ? "xpath=" + q : q);
                int n = all.count();
                if (n == 0 && q != queries.get(queries.size() - 1)) continue;
                Locator[] out = new Locator[n];
                for (int i = 0; i < n; i++) out[i] = all.nth(i);
                return out;
            }
            return new Locator[0];
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
        // -1 is the last match (-2 the one before it), for lists whose length changes
        if (index < -1000) throw new ActionException("BROWSER.BAD_OBJECT", "The index must be 0 or more, or -1 for the last match");
        long end = System.currentTimeMillis() + (long) timeoutMs;
        String primary = String.valueOf(object.getOrDefault("search_by", "properties"));
        List<String> order = new ArrayList<>(List.of(primary));
        boolean heal = !Boolean.FALSE.equals(object.get("self_heal"));
        if (heal) {
            if (!order.contains("css") && property(object, "css") != null) order.add("css");
            if (!order.contains("xpath") && (property(object, "dom_xpath") != null || property(object, "xpath") != null)) order.add("xpath");
        }
        Map<String, Object> anchor = object.get("anchor") instanceof Map<?, ?> am ? object(am) : null;
        boolean explicitIndex = object.get("index") instanceof Number n && n.intValue() != 0;
        while (true) {
            UiGuard.before(ctx);
            ctx.checkCancelled();
            Page page = page(bs, object, useCapturedPage);
            for (String by : order) {
                Map<String, Object> o = new LinkedHashMap<>(object);
                o.put("search_by", by);
                Locator[] found;
                try { found = findAll(page, o); } catch (PlaywrightException e) { found = new Locator[0]; }
                if (found.length > 1 && anchor != null && !explicitIndex) {
                    // Several match: the anchor decides (the one nearest to where the element sat next to it).
                    Locator near = nearAnchor(page, object, anchor, found);
                    if (near != null) return near;
                }
                int at = index < 0 ? found.length + index : index;
                if (at >= 0 && found.length > at) {
                    if (!by.equals(primary)) ctx.warn("Captured object found by its " + by + " instead of its " + primary + " (self-healing)");
                    return found[at];
                }
                if (found.length > 0 && by.equals(primary) && index > 0 && System.currentTimeMillis() >= end)
                    throw new ActionException("BROWSER.INDEX_OUT_OF_RANGE", found.length + " element(s) match, so index " + index + " does not exist", "Lower the index or loosen the properties.");
            }
            if (anchor != null) {
                Locator near;
                try { near = nearAnchor(page, object, anchor, null); } catch (PlaywrightException e) { near = null; }
                if (near != null) { ctx.warn("Captured object found next to its anchor " + describe(anchor)); return near; }
            }
            if (System.currentTimeMillis() >= end)
                throw new ActionException("BROWSER.OBJECT_NOT_FOUND", "No element matches the captured object " + describe(object) + " within " + (long) timeoutMs + " ms",
                        "Open the step's captured object and loosen the properties (contains, wildcard), use Test to check it, or recapture.", true);
            try { page.waitForTimeout(250); } catch (PlaywrightException e) { throw BrowserPackage.pwPublic(e, "Waiting for the element"); }
        }
    }

    /**
     * The element next to the anchor: the anchor is found by its own properties, and the element is the candidate
     * nearest to where it sat relative to the anchor at capture (anchor.offset, in pixels). Without candidates, every
     * element with the object's tag within 250 pixels of that spot is considered.
     */
    @SuppressWarnings("unchecked")
    static Locator nearAnchor(Page page, Map<String, Object> object, Map<String, Object> anchor, Locator[] candidates) {
        Locator[] anchors = findAll(page, anchor);
        int ai = anchor.get("index") instanceof Number n ? n.intValue() : 0;
        if (anchors.length <= ai) return null;
        Map<String, Object> at = (Map<String, Object>) anchors[ai].evaluate("e => { const r = e.getBoundingClientRect(); return { x: r.left + window.scrollX, y: r.top + window.scrollY }; }");
        Map<String, Object> off = anchor.get("offset") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        double x = ((Number) at.get("x")).doubleValue() + (off.get("dx") instanceof Number d ? d.doubleValue() : 0);
        double y = ((Number) at.get("y")).doubleValue() + (off.get("dy") instanceof Number d ? d.doubleValue() : 0);
        if (candidates != null) {
            int best = -1; double bd = Double.MAX_VALUE;
            for (int i = 0; i < candidates.length && i < 200; i++) {
                Map<String, Object> r = (Map<String, Object>) candidates[i].evaluate("e => { const r = e.getBoundingClientRect(); return { x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width }; }");
                if (((Number) r.get("w")).doubleValue() == 0) continue;
                double d = Math.hypot(((Number) r.get("x")).doubleValue() - x, ((Number) r.get("y")).doubleValue() - y);
                if (d < bd) { bd = d; best = i; }
            }
            return best < 0 ? null : candidates[best];
        }
        String tag = null;
        for (Map<String, Object> r : rules(object)) if ("tag".equals(r.get("name")) && "equals".equals(r.get("match"))) tag = (String) r.get("value");
        if (tag == null) tag = property(object, "tag");
        if (tag == null || !tag.matches("^[a-zA-Z][a-zA-Z0-9-]*$")) return null;
        Locator all = locator(root(page, object), tag.toLowerCase(Locale.ROOT));
        int i = ((Number) all.evaluateAll(NEAREST_JS, Map.of("x", x, "y", y, "max", 250))).intValue();
        return i < 0 ? null : all.nth(i);
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
