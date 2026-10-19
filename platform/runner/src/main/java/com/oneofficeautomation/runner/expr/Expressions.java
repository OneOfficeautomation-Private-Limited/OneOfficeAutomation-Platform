package com.oneofficeautomation.runner.expr;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.SecureString;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OOA Flow expression language: a small sandboxed grammar (no reflection, no method calls,
 * allow-listed functions only). Hand-written Pratt parser with the same semantics as the Python
 * reference; the conformance suite keeps both identical.
 *
 * <pre>  $invoice.total > 1000 and contains(lower($vendor), 'acme')   dateAdd(now(), 30, 'days')  </pre>
 */
public final class Expressions {
    private Expressions() {}

    public static final MathContext MC = MathContext.DECIMAL128;

    // ---------- AST ----------
    sealed interface Node permits Lit, Var, ListLit, Get, Unary, Bin, Call {}
    record Lit(Object value) implements Node {}
    record Var(String name) implements Node {}
    record ListLit(List<Node> items) implements Node {}
    record Get(Node base, Node key) implements Node {}
    record Unary(String op, Node arg) implements Node {}
    record Bin(String op, Node left, Node right) implements Node {}
    record Call(String fn, List<Node> args) implements Node {}

    // ---------- lexer ----------
    record Tok(String kind, String text) {}

    private static final Pattern TOKEN = Pattern.compile(
            "(?<ws>\\s+)|(?<num>\\d+(?:\\.\\d+)?)|(?<str>'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")"
            + "|(?<var>\\$[A-Za-z_][A-Za-z0-9_]*)|(?<ident>[A-Za-z_][A-Za-z0-9_]*)"
            + "|(?<op>==|!=|<=|>=|&&|\\|\\||[-+*/%<>()\\[\\],.!])");
    private static final String[] GROUPS = {"ws", "num", "str", "var", "ident", "op"};

    public static List<Tok> tokenize(String src) {
        List<Tok> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(src);
        int pos = 0;
        while (pos < src.length()) {
            if (!m.find(pos) || m.start() != pos)
                throw new OoaException("EXPR.INVALID", "Unexpected character '" + src.charAt(pos) + "' at position " + pos + " in: " + src,
                        "Check quotes and operators in the expression.");
            String kind = null;
            for (String g : GROUPS) if (m.group(g) != null) { kind = g; break; }
            String text = m.group(kind);
            pos = m.end();
            if (kind.equals("ws")) continue;
            if (kind.equals("ident") && Set.of("and", "or", "not").contains(text)) kind = "op";
            out.add(new Tok(kind, text));
        }
        out.add(new Tok("eof", ""));
        return out;
    }

    // ---------- parser ----------
    private static final Map<String, Integer> PREC = Map.ofEntries(
            Map.entry("or", 1), Map.entry("||", 1), Map.entry("and", 2), Map.entry("&&", 2),
            Map.entry("==", 3), Map.entry("!=", 3), Map.entry("<", 4), Map.entry("<=", 4), Map.entry(">", 4), Map.entry(">=", 4),
            Map.entry("+", 5), Map.entry("-", 5), Map.entry("*", 6), Map.entry("/", 6), Map.entry("%", 6));

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    public static Object parse(String src) { return parseNode(src); }

    static Node parseNode(String src) {
        Node n = CACHE.get(src);
        if (n == null) {
            n = new Parser(src).parse();
            if (CACHE.size() < 5000) CACHE.put(src, n);
        }
        return n;
    }

    private static final class Parser {
        final String src; final List<Tok> toks; int i;
        Parser(String src) { this.src = src; this.toks = tokenize(src); }
        Tok peek() { return toks.get(i); }
        Tok next() { return toks.get(i++); }
        OoaException err(String m) { return new OoaException("EXPR.INVALID", m + " in: " + src); }
        void expect(String t) {
            Tok k = next();
            if (!k.text().equals(t)) throw err("Expected '" + t + "' but found '" + (k.text().isEmpty() ? "end" : k.text()) + "'");
        }
        Node parse() {
            Node n = expr(0);
            if (!peek().kind().equals("eof")) throw err("Unexpected '" + peek().text() + "'");
            return n;
        }
        Node expr(int minPrec) {
            Node left = unary();
            while (true) {
                Tok t = peek();
                Integer p = t.kind().equals("op") ? PREC.get(t.text()) : null;
                if (p == null || p < minPrec) return left;
                next();
                left = new Bin(t.text(), left, expr(p + 1));
            }
        }
        Node unary() {
            Tok t = peek();
            if (t.kind().equals("op") && Set.of("not", "!", "-").contains(t.text())) { next(); return new Unary(t.text(), unary()); }
            return postfix(primary());
        }
        Node primary() {
            Tok t = next();
            switch (t.kind()) {
                case "num": return new Lit(new BigDecimal(t.text()));
                case "str": return new Lit(unescape(t.text().substring(1, t.text().length() - 1)));
                case "var": return new Var(t.text().substring(1));
                case "ident": {
                    if (t.text().equals("true") || t.text().equals("false")) return new Lit(t.text().equals("true"));
                    if (t.text().equals("null")) return new Lit(null);
                    expect("(");
                    List<Node> args = new ArrayList<>();
                    if (!peek().text().equals(")")) {
                        args.add(expr(0));
                        while (peek().text().equals(",")) { next(); args.add(expr(0)); }
                    }
                    expect(")");
                    if (!Functions.ALL.containsKey(t.text()))
                        throw new OoaException("EXPR.INVALID", "Unknown function '" + t.text() + "'",
                                "Available: " + String.join(", ", new TreeSet<>(Functions.ALL.keySet())) + ".");
                    return new Call(t.text(), args);
                }
                default:
            }
            if (t.text().equals("(")) { Node n = expr(0); expect(")"); return n; }
            if (t.text().equals("[")) {
                List<Node> items = new ArrayList<>();
                if (!peek().text().equals("]")) {
                    items.add(expr(0));
                    while (peek().text().equals(",")) { next(); items.add(expr(0)); }
                }
                expect("]");
                return new ListLit(items);
            }
            throw err("Unexpected '" + (t.text().isEmpty() ? "end of expression" : t.text()) + "'");
        }
        Node postfix(Node n) {
            while (true) {
                String t = peek().text();
                if (t.equals(".")) {
                    next();
                    Tok k = next();
                    if (!k.kind().equals("ident") && !k.kind().equals("num")) throw err("Expected field name after '.'");
                    n = new Get(n, new Lit(k.text()));
                } else if (t.equals("[")) {
                    next();
                    Node idx = expr(0);
                    expect("]");
                    n = new Get(n, idx);
                } else return n;
            }
        }
        static String unescape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '\\' && k + 1 < s.length()) {
                    char e = s.charAt(++k);
                    sb.append(switch (e) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; default -> e; });
                } else sb.append(c);
            }
            return sb.toString();
        }
    }

    // ---------- evaluation ----------
    public static Object evaluate(String src, Function<String, Object> lookup) { return eval(parseNode(src), lookup); }

    static Object guard(Object v, String where) {
        if (v instanceof SecureString) throw Values.secureLeak(where);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Object eval(Node n, Function<String, Object> lookup) {
        if (n instanceof Lit l) return l.value();
        if (n instanceof Var v) return lookup.apply(v.name());
        if (n instanceof ListLit l) { List<Object> o = new ArrayList<>(); for (Node x : l.items()) o.add(eval(x, lookup)); return o; }
        if (n instanceof Get g) {
            Object base = guard(eval(g.base(), lookup), "field access");
            Object key = eval(g.key(), lookup);
            if (base == null) return null;
            if (base instanceof Map<?, ?> m) {
                String k = key instanceof BigDecimal d ? d.toBigInteger().toString() : String.valueOf(key);
                return ((Map<String, Object>) m).get(k);
            }
            if (base instanceof List<?> list) {
                try { int idx = Values.toNumber(key).intValueExact(); return idx >= 0 && idx < list.size() ? list.get(idx) : null; }
                catch (ArithmeticException | OoaException e) { return null; }
            }
            if (base instanceof String s) {
                try { int idx = Values.toNumber(key).intValueExact(); return idx >= 0 && idx < s.length() ? String.valueOf(s.charAt(idx)) : null; }
                catch (ArithmeticException | OoaException e) { return null; }
            }
            throw new OoaException("EXPR.INVALID", "Cannot read field '" + key + "' of a " + Values.typeName(base));
        }
        if (n instanceof Unary u) {
            Object v = eval(u.arg(), lookup);
            if (u.op().equals("-")) return num(v, "arithmetic").negate();
            return !truthy(v);
        }
        if (n instanceof Bin b) {
            switch (b.op()) {
                case "and", "&&": return truthy(eval(b.left(), lookup)) && truthy(eval(b.right(), lookup));
                case "or", "||": return truthy(eval(b.left(), lookup)) || truthy(eval(b.right(), lookup));
                default: return binary(b.op(), eval(b.left(), lookup), eval(b.right(), lookup));
            }
        }
        Call c = (Call) n;
        List<Object> args = new ArrayList<>();
        for (Node x : c.args()) args.add(eval(x, lookup));
        if (!Set.of("coalesce", "isEmpty", "if").contains(c.fn())) for (Object a : args) guard(a, "function " + c.fn() + "()");
        return Functions.call(c.fn(), args);
    }

    static BigDecimal num(Object v, String where) { return Values.toNumber(guard(v, where)); }

    public static Object binary(String op, Object a, Object b) {
        guard(a, "operator " + op);
        guard(b, "operator " + op);
        switch (op) {
            case "+":
                if (a instanceof String || b instanceof String) return Values.text(a) + Values.text(b);
                if (a instanceof List<?> la && b instanceof List<?> lb) { List<Object> o = new ArrayList<>(la); o.addAll(lb); return o; }
                return num(a, "arithmetic").add(num(b, "arithmetic"), MC);
            case "-": return num(a, "arithmetic").subtract(num(b, "arithmetic"), MC);
            case "*": return num(a, "arithmetic").multiply(num(b, "arithmetic"), MC);
            case "/": case "%": {
                BigDecimal x = num(a, "arithmetic"), y = num(b, "arithmetic");
                if (y.signum() == 0) throw new OoaException("EXPR.DIV_ZERO", "Division by zero", "Check the divisor before dividing.");
                return op.equals("/") ? x.divide(y, MC) : x.remainder(y, MC);
            }
            case "==": return eqPair(a, b);
            case "!=": return !eqPair(a, b);
            default: {
                Object[] p = cmpPair(a, b);
                int r;
                if (p[0] instanceof BigDecimal x && p[1] instanceof BigDecimal y) r = x.compareTo(y);
                else if (p[0] instanceof String x && p[1] instanceof String y) r = x.compareTo(y);
                else if (p[0] instanceof java.time.LocalDateTime x && p[1] instanceof java.time.LocalDateTime y) r = x.compareTo(y);
                else throw new OoaException("EXPR.INVALID", "Cannot compare " + Values.typeName(a) + " with " + Values.typeName(b) + " using " + op);
                return switch (op) { case "<" -> r < 0; case "<=" -> r <= 0; case ">" -> r > 0; default -> r >= 0; };
            }
        }
    }

    private static boolean eqPair(Object a, Object b) {
        Object[] p = cmpPair(a, b);
        return Values.looseEquals(p[0], p[1]);
    }

    private static Object[] cmpPair(Object a, Object b) {
        try {
            if (a instanceof java.time.LocalDateTime && b instanceof String s) return new Object[]{a, Values.toDateTime(s)};
            if (b instanceof java.time.LocalDateTime && a instanceof String s) return new Object[]{Values.toDateTime(s), b};
            if (a instanceof BigDecimal && b instanceof String s) return new Object[]{a, Values.toNumber(s)};
            if (b instanceof BigDecimal && a instanceof String s) return new Object[]{Values.toNumber(s), b};
        } catch (OoaException ignored) { }
        return new Object[]{a, b};
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof BigDecimal d) return d.signum() != 0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        if (v instanceof SecureString s) return !s.reveal().isEmpty();
        return true;
    }

    // ---------- argument resolution ----------
    private static final Pattern REF = Pattern.compile("^\\$[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+|\\[[^\\]]+\\])*$");
    private static final Pattern INTERP = Pattern.compile("\\$\\{([^}]*)\\}");
    private static final Pattern BARE = Pattern.compile("^\\s*[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+|\\[[^\\]]+\\])*\\s*$");

    /** Inside ${...} a bare path means a variable: ${name} == ${$name}. */
    static String interpSrc(String inner) {
        String t = inner.trim();
        if (BARE.matcher(inner).matches() && !Set.of("true", "false", "null").contains(t)) return "$" + t;
        return inner;
    }

    /**
     * Resolve a step argument: "$a.b[0]" keeps type; "Hi ${name}" interpolates; {"expr": "..."} evaluates;
     * {"literal": "$x"} escapes; maps and lists resolve recursively.
     */
    @SuppressWarnings("unchecked")
    public static Object resolve(Object value, Function<String, Object> lookup) {
        if (value instanceof String s) {
            if (REF.matcher(s).matches()) return evaluate(s, lookup);
            if (s.contains("${")) {
                Matcher m = INTERP.matcher(s);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    Object v = guard(evaluate(interpSrc(m.group(1)), lookup), "text interpolation");
                    m.appendReplacement(sb, Matcher.quoteReplacement(Values.text(v)));
                }
                m.appendTail(sb);
                return sb.toString();
            }
            return s;
        }
        if (value instanceof Map<?, ?> m) {
            if (m.size() == 1 && m.containsKey("expr")) return evaluate(String.valueOf(m.get("expr")), lookup);
            if (m.size() == 1 && m.containsKey("literal")) return m.get("literal");
            Map<String, Object> o = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> o.put(k, resolve(v, lookup)));
            return o;
        }
        if (value instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(resolve(x, lookup)); return o; }
        return value;
    }

    /** Variable names referenced by an argument; also surfaces syntax errors at validation time. */
    public static Set<String> references(Object value) {
        Set<String> found = new TreeSet<>();
        walk(value, found);
        return found;
    }

    private static void walk(Object v, Set<String> found) {
        if (v instanceof String s) {
            List<String> srcs = new ArrayList<>();
            if (REF.matcher(s).matches()) srcs.add(s);
            else { Matcher m = INTERP.matcher(s); while (m.find()) srcs.add(interpSrc(m.group(1))); }
            for (String src : srcs) scan(src, found);
        } else if (v instanceof Map<?, ?> m) {
            if (m.size() == 1 && m.containsKey("literal")) return;
            if (m.size() == 1 && m.containsKey("expr")) { scan(String.valueOf(m.get("expr")), found); return; }
            for (Object x : m.values()) walk(x, found);
        } else if (v instanceof List<?> l) for (Object x : l) walk(x, found);
    }

    private static void scan(String src, Set<String> found) {
        parseNode(src);
        for (Tok t : tokenize(src)) if (t.kind().equals("var")) found.add(t.text().substring(1));
    }

    static BigDecimal roundHalfUp(BigDecimal n, int digits) { return n.setScale(digits, RoundingMode.HALF_UP); }
}
