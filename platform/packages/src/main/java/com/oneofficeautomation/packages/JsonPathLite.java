package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.ActionException;

import java.math.BigDecimal;
import java.util.*;

/**
 * Paths into JSON values, in the two forms people use: OOA's dotted paths (order.items[0].sku, items.0.sku) and JSONPath
 * ($.order.items[0].qty, $..sku, $.items[*].sku, $.items[?(@.qty > 1)].sku, $['odd key'], $.items[-1], $.items[0:2]).
 * A path with a wildcard, recursive descent, filter, slice or list of keys can match several values ("indefinite").
 */
final class JsonPathLite {
    private JsonPathLite() {}

    record Result(List<Object> matches, boolean definite) {}

    private sealed interface Step permits Name, Index, Wild, Slice, Filter, Multi {}
    private record Name(String name, boolean deep) implements Step {}
    private record Index(int i) implements Step {}
    private record Wild(boolean deep) implements Step {}
    private record Slice(Integer from, Integer to) implements Step {}
    private record Filter(String expr) implements Step {}
    private record Multi(List<Object> keys) implements Step {}

    static Result evaluate(Object root, String path) {
        List<Step> steps = parse(path);
        boolean definite = steps.stream().allMatch(s -> s instanceof Index || (s instanceof Name n && !n.deep()));
        List<Object> cur = new ArrayList<>(List.of(new Holder(root)));
        for (Step s : steps) {
            List<Object> next = new ArrayList<>();
            for (Object h : cur) apply(((Holder) h).v, s, next);
            cur = next;
            if (cur.isEmpty()) break;
        }
        List<Object> out = new ArrayList<>();
        for (Object h : cur) out.add(((Holder) h).v);
        return new Result(out, definite);
    }

    /** Wraps values so a JSON null still counts as a match. */
    private record Holder(Object v) {}

    private static void apply(Object v, Step s, List<Object> out) {
        switch (s) {
            case Name n -> {
                if (n.deep()) { descend(v, x -> { if (x instanceof Map<?, ?> m && m.containsKey(n.name())) out.add(new Holder(m.get(n.name()))); }); return; }
                if (v instanceof Map<?, ?> m && m.containsKey(n.name())) out.add(new Holder(m.get(n.name())));
                else if (v instanceof List<?> l && n.name().matches("-?\\d+")) index(l, Integer.parseInt(n.name()), out);
            }
            case Index i -> { if (v instanceof List<?> l) index(l, i.i(), out); }
            case Wild w -> {
                if (w.deep()) { descend(v, x -> children(x, out)); return; }
                children(v, out);
            }
            case Slice sl -> {
                if (!(v instanceof List<?> l)) return;
                int n = l.size();
                int a = sl.from() == null ? 0 : sl.from() < 0 ? Math.max(0, n + sl.from()) : Math.min(n, sl.from());
                int b = sl.to() == null ? n : sl.to() < 0 ? Math.max(0, n + sl.to()) : Math.min(n, sl.to());
                for (int i = a; i < b; i++) out.add(new Holder(l.get(i)));
            }
            case Multi m -> {
                for (Object k : m.keys()) {
                    if (k instanceof Integer i && v instanceof List<?> l) index(l, i, out);
                    else if (k instanceof String key && v instanceof Map<?, ?> map && map.containsKey(key)) out.add(new Holder(map.get(key)));
                }
            }
            case Filter f -> {
                Collection<?> items = v instanceof List<?> l ? l : v instanceof Map<?, ?> m ? m.values() : List.of();
                for (Object item : items) if (test(item, f.expr())) out.add(new Holder(item));
            }
        }
    }

    private static void index(List<?> l, int i, List<Object> out) {
        int k = i < 0 ? l.size() + i : i;
        if (k >= 0 && k < l.size()) out.add(new Holder(l.get(k)));
    }

    private static void children(Object v, List<Object> out) {
        if (v instanceof Map<?, ?> m) for (Object x : m.values()) out.add(new Holder(x));
        else if (v instanceof List<?> l) for (Object x : l) out.add(new Holder(x));
    }

    /** Visits v and everything below it, parents first ($..x). */
    private static void descend(Object v, java.util.function.Consumer<Object> visit) {
        visit.accept(v);
        if (v instanceof Map<?, ?> m) for (Object x : m.values()) descend(x, visit);
        else if (v instanceof List<?> l) for (Object x : l) descend(x, visit);
    }

    // ---- parsing ----

    static List<Step> parse(String path) {
        String p = path == null ? "" : path.strip();
        List<Step> steps = new ArrayList<>();
        int i = 0, n = p.length();
        if (p.equals("$") || p.startsWith("$.") || p.startsWith("$[")) i = 1;  // "$schema" stays a key name
        boolean first = true;
        while (i < n) {
            char c = p.charAt(i);
            if (c == '.') {
                boolean deep = i + 1 < n && p.charAt(i + 1) == '.';
                i += deep ? 2 : 1;
                if (i < n && p.charAt(i) == '*') { steps.add(new Wild(deep)); i++; }
                else if (i < n && p.charAt(i) == '[') { if (deep) steps.add(new Wild(true)); }
                else { int e = nameEnd(p, i); if (e == i) throw bad(path, "a name is missing after '.'"); steps.add(new Name(p.substring(i, e), deep)); i = e; }
            } else if (c == '[') {
                int close = closing(p, i, path);
                steps.add(bracket(p.substring(i + 1, close).strip(), path));
                i = close + 1;
            } else if (first) {
                if (c == '*') { steps.add(new Wild(false)); i++; }
                else { int e = nameEnd(p, i); if (e == i) throw bad(path, "unexpected '" + c + "'"); steps.add(new Name(p.substring(i, e), false)); i = e; }
            } else throw bad(path, "unexpected '" + c + "' at position " + (i + 1));
            first = false;
        }
        return steps;
    }

    private static int nameEnd(String p, int i) {
        int e = i;
        while (e < p.length() && ".[]".indexOf(p.charAt(e)) < 0) e++;
        return e;
    }

    private static int closing(String p, int open, String path) {
        int depth = 0; char quote = 0;
        for (int i = open; i < p.length(); i++) {
            char c = p.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '[' || c == '(') depth++;
            else if ((c == ']' || c == ')') && --depth == 0 && c == ']') return i;
        }
        throw bad(path, "a '[' is not closed");
    }

    private static Step bracket(String in, String path) {
        if (in.equals("*")) return new Wild(false);
        if (in.startsWith("?")) {
            String e = in.substring(1).strip();
            if (e.startsWith("(") && e.endsWith(")")) e = e.substring(1, e.length() - 1);
            return new Filter(e.strip());
        }
        if (in.matches("-?\\d*\\s*:\\s*-?\\d*(\\s*:\\s*\\d+)?")) {
            String[] ab = in.split(":", -1);
            return new Slice(ab[0].isBlank() ? null : Integer.parseInt(ab[0].strip()), ab[1].isBlank() ? null : Integer.parseInt(ab[1].strip()));
        }
        List<Object> keys = new ArrayList<>();
        for (String part : splitTopLevel(in, ',')) {
            String t = part.strip();
            if (t.matches("-?\\d+")) keys.add(Integer.parseInt(t));
            else if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) keys.add(t.substring(1, t.length() - 1));
            else if (!t.isEmpty()) keys.add(t);
            else throw bad(path, "empty [ ]");
        }
        if (keys.size() == 1) return keys.get(0) instanceof Integer i ? new Index(i) : new Name((String) keys.get(0), false);
        return new Multi(keys);
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        char quote = 0; int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == sep && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private static ActionException bad(String path, String why) {
        return new ActionException("JSON.BAD_PATH", "Path '" + path + "' cannot be read: " + why,
                "Use a dotted path such as order.items[0].sku or JSONPath such as $.order.items[*].sku or $.items[?(@.qty > 1)].");
    }

    // ---- filters: @.a.b op value, joined with && and ||, ! and brackets ----

    static boolean test(Object item, String expr) {
        List<String> ors = splitOp(expr, "||");
        if (ors.size() > 1) { for (String o : ors) if (test(item, o)) return true; return false; }
        List<String> ands = splitOp(expr, "&&");
        if (ands.size() > 1) { for (String a : ands) if (!test(item, a)) return false; return true; }
        String e = expr.strip();
        if (e.startsWith("(") && e.endsWith(")") && closingParen(e) == e.length() - 1) return test(item, e.substring(1, e.length() - 1));
        if (e.startsWith("!")) return !test(item, e.substring(1));
        for (String op : List.of("==", "!=", ">=", "<=", ">", "<", "=~")) {
            int at = indexOutsideQuotes(e, op);
            if (at < 0) continue;
            Object left = operand(item, e.substring(0, at).strip()), right = operand(item, e.substring(at + op.length()).strip());
            return compare(left, op, right);
        }
        Object v = operand(item, e);
        return v != null && !Boolean.FALSE.equals(v) && v != MISSING;
    }

    private static final Object MISSING = new Object();

    private static Object operand(Object item, String t) {
        if (t.startsWith("@")) {
            if (t.equals("@")) return item;
            Result r = evaluate(item, "$" + t.substring(1));
            return r.matches().isEmpty() ? MISSING : r.matches().get(0);
        }
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) return t.substring(1, t.length() - 1);
        if (t.equals("true")) return true;
        if (t.equals("false")) return false;
        if (t.equals("null")) return null;
        if (t.startsWith("/") && t.lastIndexOf('/') > 0) return java.util.regex.Pattern.compile(t.substring(1, t.lastIndexOf('/')), t.endsWith("/i") ? java.util.regex.Pattern.CASE_INSENSITIVE : 0);
        try { return new BigDecimal(t); } catch (NumberFormatException ex) { return t; }
    }

    private static boolean compare(Object a, String op, Object b) {
        if (a == MISSING || b == MISSING) return op.equals("!=");
        if (op.equals("=~")) return a != null && b instanceof java.util.regex.Pattern pt && pt.matcher(Values.text(a)).find();
        if (a instanceof Number || b instanceof Number) {
            try {
                int c = Values.toNumber(a).compareTo(Values.toNumber(b));
                return switch (op) { case "==" -> c == 0; case "!=" -> c != 0; case ">" -> c > 0; case ">=" -> c >= 0; case "<" -> c < 0; default -> c <= 0; };
            } catch (RuntimeException ex) { return op.equals("!="); }
        }
        boolean eq = Objects.equals(a, b) || (a != null && b != null && Values.text(a).equals(Values.text(b)));
        if (op.equals("==")) return eq;
        if (op.equals("!=")) return !eq;
        if (a == null || b == null) return false;
        int c = Values.text(a).compareTo(Values.text(b));
        return switch (op) { case ">" -> c > 0; case ">=" -> c >= 0; case "<" -> c < 0; default -> c <= 0; };
    }

    private static List<String> splitOp(String s, String op) {
        List<String> out = new ArrayList<>();
        char quote = 0; int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (depth == 0 && s.startsWith(op, i)) { out.add(s.substring(start, i)); start = i + op.length(); i += op.length() - 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int indexOutsideQuotes(String s, String op) {
        char quote = 0;
        for (int i = 0; i + op.length() <= s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"' || c == '/') { if (c != '/' || i == 0 || s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '~') { quote = c; continue; } }
            if (s.startsWith(op, i)) {
                // ">" must not be the first half of ">=" etc.
                if ((op.equals(">") || op.equals("<")) && i + 1 < s.length() && s.charAt(i + 1) == '=') continue;
                return i;
            }
        }
        return -1;
    }

    private static int closingParen(String s) {
        int depth = 0; char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }
}
