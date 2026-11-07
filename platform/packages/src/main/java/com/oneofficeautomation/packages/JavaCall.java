package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.JsonLite;

import java.io.PrintStream;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The separate Java process behind Call Java method (G41): reads {class, method, args, create_instance,
 * constructor_args} as JSON on standard input, calls the method whose parameters fit the arguments, and prints the
 * result as JSON after a marker. What the method prints goes to standard error, so the result line stays clean.
 */
public final class JavaCall {
    private JavaCall() { }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try { call(out); }
        finally { out.flush(); System.exit(0); }   // a library's threads must not keep the process alive
    }

    @SuppressWarnings("unchecked")
    private static void call(PrintStream out) {
        try {
            Map<String, Object> p = (Map<String, Object>) JsonLite.parse(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
            System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
            Class<?> c;
            try { c = Class.forName((String) p.get("class")); }
            catch (ClassNotFoundException e) { send(out, false, null, "Class not found: " + p.get("class"), "not_found"); return; }
            List<Object> args = (List<Object>) p.getOrDefault("args", List.of());
            Object target = null;
            if (Boolean.TRUE.equals(p.get("create_instance"))) {
                List<Object> ca = (List<Object>) p.getOrDefault("constructor_args", List.of());
                Constructor<?> fit = null; Object[] conv = null;
                for (Constructor<?> k : c.getConstructors()) { Object[] v = convertAll(k.getParameterTypes(), ca); if (v != null) { fit = k; conv = v; break; } }
                if (fit == null) { send(out, false, null, "No public constructor of " + c.getName() + " takes " + ca.size() + " argument(s) like these", "not_found"); return; }
                target = fit.newInstance(conv);
            }
            String name = (String) p.get("method");
            List<Method> candidates = new ArrayList<>();
            for (Method m : c.getMethods())
                if (m.getName().equals(name) && m.getParameterCount() == args.size() && (target != null || Modifier.isStatic(m.getModifiers()))) candidates.add(m);
            // exact types first (int before long before double), so max(3, 4) is the int one
            candidates.sort(Comparator.comparingInt(JavaCall::rank));
            for (Method m : candidates) {
                Object[] conv = convertAll(m.getParameterTypes(), args);
                if (conv == null) continue;
                Object r = m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, conv);
                send(out, true, plain(r, 0), null, null);
                return;
            }
            send(out, false, null, "No public " + (target == null ? "static " : "") + "method " + c.getName() + "." + name + " takes " + args.size() + " argument(s) like these", "not_found");
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            send(out, false, null, t.getClass().getName() + ": " + t.getMessage(), "run");
        } catch (Throwable t) {
            send(out, false, null, t.getClass().getName() + ": " + t.getMessage(), "run");
        }
    }

    private static int rank(Method m) {
        int r = 0;
        for (Class<?> t : m.getParameterTypes()) r += t == int.class || t == Integer.class ? 0 : t == long.class || t == Long.class ? 1 : t == double.class || t == Double.class ? 2 : t == String.class ? 3 : 4;
        return r;
    }

    private static void send(PrintStream out, boolean ok, Object result, String message, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok); m.put("result", result); m.put("message", message); m.put("kind", kind);
        out.println("\n" + DotNetPackage.MARK + JsonLite.write(m));
        out.flush();
    }

    private static Object[] convertAll(Class<?>[] types, List<Object> args) {
        if (types.length != args.size()) return null;
        Object[] v = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object[] one = convert(types[i], args.get(i));
            if (one == null) return null;
            v[i] = one[0];
        }
        return v;
    }

    /** The value as the parameter type, wrapped in an array; null when it does not fit. */
    private static Object[] convert(Class<?> t, Object v) {
        if (v == null) return t.isPrimitive() ? null : new Object[]{null};
        if (v instanceof Number n) {
            BigDecimal d = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
            try {
                if (t == int.class || t == Integer.class) return new Object[]{d.intValueExact()};
                if (t == long.class || t == Long.class) return new Object[]{d.longValueExact()};
                if (t == short.class || t == Short.class) return new Object[]{d.shortValueExact()};
                if (t == byte.class || t == Byte.class) return new Object[]{d.byteValueExact()};
            } catch (ArithmeticException e) { return null; }
            if (t == double.class || t == Double.class) return new Object[]{d.doubleValue()};
            if (t == float.class || t == Float.class) return new Object[]{d.floatValue()};
            if (t == BigDecimal.class || t == Number.class) return new Object[]{d};
            if (t == BigInteger.class) try { return new Object[]{d.toBigIntegerExact()}; } catch (ArithmeticException e) { return null; }
            if (t == Object.class) return new Object[]{d.scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0 ? (Object) d.intValue() : d};
            if (t == String.class) return new Object[]{d.toPlainString()};
            return null;
        }
        if (v instanceof Boolean b) return t == boolean.class || t == Boolean.class || t == Object.class ? new Object[]{b} : t == String.class ? new Object[]{b.toString()} : null;
        if (v instanceof String s) {
            if (t == String.class || t == Object.class || t == CharSequence.class) return new Object[]{s};
            if ((t == char.class || t == Character.class) && s.length() == 1) return new Object[]{s.charAt(0)};
            return null;
        }
        if (v instanceof List<?> l) {
            if (t.isAssignableFrom(ArrayList.class)) return new Object[]{new ArrayList<>(l)};
            if (t.isArray()) {
                Object arr = Array.newInstance(t.getComponentType(), l.size());
                for (int i = 0; i < l.size(); i++) { Object[] one = convert(t.getComponentType(), l.get(i)); if (one == null) return null; Array.set(arr, i, one[0]); }
                return new Object[]{arr};
            }
            return null;
        }
        if (v instanceof Map<?, ?> m) return t.isAssignableFrom(LinkedHashMap.class) ? new Object[]{new LinkedHashMap<>(m)} : null;
        return null;
    }

    /** A result as JSON values: text, numbers, true/false, lists and maps; anything else as its text. */
    static Object plain(Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Character ch) return ch.toString();
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) return d.toString();
        if (v instanceof Float f && (f.isNaN() || f.isInfinite())) return f.toString();
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (depth > 20) return String.valueOf(v);
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put(String.valueOf(k), plain(x, depth + 1))); return o; }
        if (v instanceof Iterable<?> it) { List<Object> o = new ArrayList<>(); for (Object x : it) { if (o.size() >= 10000) break; o.add(plain(x, depth + 1)); } return o; }
        if (v.getClass().isArray()) { List<Object> o = new ArrayList<>(); int n = Math.min(Array.getLength(v), 10000); for (int i = 0; i < n; i++) o.add(plain(Array.get(v, i), depth + 1)); return o; }
        return String.valueOf(v);
    }
}
