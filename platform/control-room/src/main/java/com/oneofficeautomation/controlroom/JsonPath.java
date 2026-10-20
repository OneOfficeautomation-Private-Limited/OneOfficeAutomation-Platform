package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

/**
 * A small JSON path subset for the universal poller: $ (root), .name, ['name'], [index], [*] and .* , e.g.
 * $.data.items[*], $.value, results[0].rows. The leading $ is optional.
 */
final class JsonPath {
    private final List<Object> steps;   // String name, Integer index, or WILDCARD

    private static final Object WILDCARD = new Object();

    private JsonPath(List<Object> steps) { this.steps = steps; }

    static JsonPath compile(String path) {
        String p = path == null ? "$" : path.trim();
        List<Object> steps = new ArrayList<>();
        int i = p.startsWith("$") ? 1 : 0;
        StringBuilder name = new StringBuilder();
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') { flush(name, steps); i++; if (i < p.length() && p.charAt(i) == '*') { steps.add(WILDCARD); i++; } continue; }
            if (c == '[') {
                flush(name, steps);
                int end = p.indexOf(']', i);
                if (end < 0) throw bad(path);
                String in = p.substring(i + 1, end).trim();
                if (in.equals("*")) steps.add(WILDCARD);
                else if ((in.startsWith("'") && in.endsWith("'")) || (in.startsWith("\"") && in.endsWith("\""))) steps.add(in.substring(1, in.length() - 1));
                else { try { steps.add(Integer.parseInt(in)); } catch (NumberFormatException e) { throw bad(path); } }
                i = end + 1;
                continue;
            }
            name.append(c);
            i++;
        }
        flush(name, steps);
        return new JsonPath(steps);
    }

    private static void flush(StringBuilder name, List<Object> steps) { if (!name.isEmpty()) { steps.add(name.toString()); name.setLength(0); } }

    private static OoaException bad(String path) { return new OoaException("CR.BAD_TRIGGER", "Not a supported JSON path: " + path, "Examples: $.items[*], $.data.results, value"); }

    /** Every value the path reaches. */
    List<Object> select(Object root) {
        List<Object> cur = new ArrayList<>();
        cur.add(root);
        for (Object step : steps) {
            List<Object> next = new ArrayList<>();
            for (Object node : cur) {
                if (step == WILDCARD) {
                    if (node instanceof List<?> l) next.addAll(l);
                    else if (node instanceof Map<?, ?> m) next.addAll(m.values());
                } else if (step instanceof Integer idx) {
                    if (node instanceof List<?> l) { int k = idx < 0 ? l.size() + idx : idx; if (k >= 0 && k < l.size()) next.add(l.get(k)); }
                } else if (node instanceof Map<?, ?> m && m.containsKey(step)) next.add(m.get(step));
            }
            cur = next;
        }
        return cur;
    }

    /** The items the path points at: a single list is opened up, so $.items and $.items[*] give the same items. */
    List<Object> items(Object root) {
        List<Object> r = select(root);
        if (r.size() == 1 && r.get(0) instanceof List<?> l) return new ArrayList<>(l);
        return r;
    }

    /** The first value, or null. */
    Object first(Object root) { List<Object> r = select(root); return r.isEmpty() ? null : r.get(0); }
}
