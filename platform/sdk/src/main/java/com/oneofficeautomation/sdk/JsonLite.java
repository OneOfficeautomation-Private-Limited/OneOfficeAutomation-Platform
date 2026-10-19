package com.oneofficeautomation.sdk;

import java.math.BigDecimal;
import java.util.*;

/**
 * Small JDK-only JSON reader/writer used for canonical serialisation (signing, hashing, audit chains)
 * and annotation default literals. Numbers are BigDecimal, objects keep insertion order. Flow files are
 * read with Jackson in the runner; this class keeps the SDK free of third-party dependencies.
 */
public final class JsonLite {
    private JsonLite() {}

    // ---------- writing ----------
    /** Canonical form: object keys sorted, no whitespace, numbers in plain notation. */
    public static String canonical(Object v) { StringBuilder sb = new StringBuilder(); write(v, sb, true); return sb.toString(); }

    public static String write(Object v) { StringBuilder sb = new StringBuilder(); write(v, sb, false); return sb.toString(); }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb, boolean sorted) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) quote(s, sb);
        else if (v instanceof Boolean b) sb.append(b);
        else if (v instanceof BigDecimal d) sb.append(plain(d));
        else if (v instanceof Number n) sb.append(plain(new BigDecimal(n.toString())));
        else if (v instanceof SecureString) quote(SecureString.MASK, sb);
        else if (v instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) keys.add(String.valueOf(k));
            if (sorted) Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                quote(k, sb);
                sb.append(':');
                write(((Map<String, Object>) m).get(k), sb, sorted);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) { if (!first) sb.append(','); first = false; write(x, sb, sorted); }
            sb.append(']');
        } else quote(v.toString(), sb);
    }

    public static String plain(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() <= 0 ? s.toBigInteger().toString() : s.toPlainString();
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    // ---------- reading ----------
    public static Object parse(String text) {
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value();
        r.ws();
        if (r.i != text.length()) throw r.err("trailing characters");
        return v;
    }

    private static final class Reader {
        final String s; int i;
        Reader(String s) { this.s = s; }
        IllegalArgumentException err(String m) { return new IllegalArgumentException("Invalid JSON at " + i + ": " + m); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws();
                if (s.charAt(i++) != ':') throw err("expected ':'");
                ws(); m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }
        String string() {
            if (s.charAt(i) != '"') throw err("expected string");
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }
        BigDecimal number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw err("unexpected character '" + s.charAt(i) + "'");
            return new BigDecimal(s.substring(start, i));
        }
    }
}
