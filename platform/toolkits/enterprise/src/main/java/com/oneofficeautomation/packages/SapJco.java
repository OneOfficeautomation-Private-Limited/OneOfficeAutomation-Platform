package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAP Java Connector (JCo 3) through reflection. JCo is SAP's licensed library, so it is not shipped: the runner finds
 * sapjco3.jar (with its native library next to it) on its class path, in the folder named by OOA_SAPJCO_HOME, or in
 * lib/sapjco next to the runner. Destinations are served from memory by one DestinationDataProvider registered for
 * the whole runner, so passwords never touch a .jcoDestination file.
 */
final class SapJco {
    private SapJco() {}

    static final String PKG = "com.sap.conn.jco.";
    private static volatile ClassLoader loader;
    private static final Map<String, Properties> DESTINATIONS = new ConcurrentHashMap<>();
    private static volatile boolean providerRegistered;

    /** The class loader that sees JCo, or SAP_BAPI.JCO_MISSING with how to add it. */
    static synchronized ClassLoader loader() {
        if (loader != null) return loader;
        ClassLoader here = SapJco.class.getClassLoader();
        try { Class.forName(PKG + "JCoDestinationManager", false, here); return loader = here; }
        catch (ClassNotFoundException ignored) { }
        List<Path> places = new ArrayList<>();
        String home = System.getenv("OOA_SAPJCO_HOME");
        if (home != null && !home.isBlank()) places.add(Path.of(home.strip()));
        String app = System.getProperty("ooa.home", System.getenv().getOrDefault("APP_HOME", ""));
        if (!app.isBlank()) places.add(Path.of(app, "lib", "sapjco"));
        places.add(Path.of(System.getProperty("user.dir"), "lib", "sapjco"));
        places.add(Path.of(System.getProperty("user.home"), ".ooa", "sapjco"));
        for (Path dir : places) {
            Path jar = dir.resolve("sapjco3.jar");
            if (Files.isRegularFile(jar)) {
                try { return loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, here); }
                catch (java.net.MalformedURLException e) { throw new IllegalStateException(e); }
            }
        }
        throw new ActionException("SAP_BAPI.JCO_MISSING", "SAP Java Connector (JCo 3) is not installed on this runner",
                "Download SAP JCo 3.1 from the SAP Support Portal (your SAP licence) and put sapjco3.jar and its native library (sapjco3.dll on Windows) "
                        + "in a folder named by OOA_SAPJCO_HOME, or in lib\\sapjco next to the runner; then restart the runner.");
    }

    static Class<?> cls(String name) {
        try { return Class.forName(name.startsWith("com.") ? name : PKG + name, true, loader()); }
        catch (ClassNotFoundException e) { throw new ActionException("SAP_BAPI.JCO_MISSING", "SAP JCo class " + name + " is missing: " + e.getMessage(), "Install SAP JCo 3.1 (sapjco3.jar)."); }
        catch (LinkageError e) {
            throw new ActionException("SAP_BAPI.JCO_NATIVE_MISSING", "SAP JCo could not load its native library: " + e.getMessage(),
                    "Put sapjco3.dll (64-bit, matching the runner's Java) next to sapjco3.jar, then restart the runner.");
        }
    }

    // ---------------------------------------------------------------- reflection helpers (public interface methods only)

    static Object call(Object target, String name, Object... args) {
        Method m = method(target.getClass(), name, args);
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw sapError(e.getCause()); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    static Object callStatic(String cls, String name, Object... args) {
        Class<?> c = cls(cls);
        Method m = method(c, name, args);
        try { return m.invoke(null, args); }
        catch (InvocationTargetException e) { throw sapError(e.getCause()); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    /** setValue(String, Object) on a JCo record, chosen explicitly among its many overloads. */
    static void setValue(Object record, String field, Object value) {
        try {
            Method m = cls("JCoRecord").getMethod("setValue", String.class, Object.class);
            m.invoke(record, field, value);
        } catch (InvocationTargetException e) { throw sapError(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    static Method method(Class<?> c, String name, Object[] args) {
        String key = c.getName() + "#" + name + "/" + args.length + Arrays.toString(Arrays.stream(args).map(a -> a == null ? "null" : a.getClass().getName()).toArray());
        return METHODS.computeIfAbsent(key, k -> {
            for (Class<?> t : publicTypes(c))
                for (Method m : t.getMethods())
                    if (m.getName().equals(name) && m.getParameterCount() == args.length && fits(m.getParameterTypes(), args)) return m;
            throw new IllegalStateException("SAP JCo has no method " + c.getName() + "." + name + " for " + args.length + " argument(s)");
        });
    }

    private static List<Class<?>> publicTypes(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(c));
        while (!todo.isEmpty()) {
            Class<?> t = todo.poll();
            if (t == null || out.contains(t)) continue;
            if (Modifier.isPublic(t.getModifiers())) out.add(t);
            todo.addAll(Arrays.asList(t.getInterfaces()));
            if (t.getSuperclass() != null) todo.add(t.getSuperclass());
        }
        out.sort(Comparator.comparing((Class<?> t) -> !t.isInterface()));   // interfaces first: their methods are always callable
        return out;
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i].isPrimitive() ? box(types[i]) : types[i];
            if (!t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> p) {
        return p == int.class ? Integer.class : p == boolean.class ? Boolean.class : p == long.class ? Long.class : p == double.class ? Double.class : p;
    }

    /** A JCo exception as an OOA error: logon problems, missing functions, communication and ABAP exceptions. */
    static ActionException sapError(Throwable t) {
        if (t instanceof ActionException a) return a;
        String cls = t.getClass().getSimpleName(), msg = String.valueOf(t.getMessage());
        int group = -1;
        try { group = (Integer) t.getClass().getMethod("getGroup").invoke(t); } catch (ReflectiveOperationException | ClassCastException ignored) { }
        String key = "";
        try { key = String.valueOf(t.getClass().getMethod("getKey").invoke(t)); } catch (ReflectiveOperationException ignored) { }
        // JCoException groups: 103 logon failure, 102 communication failure, 104 system failure, 123 ABAP exception
        if (group == 103 || msg.matches("(?is).*(logon|password|user .*locked|name or password).*"))
            return new ActionException("SAP_BAPI.LOGON_FAILED", "SAP logon failed: " + msg, "Check client, user, password and language.", false);
        if (group == 102 || msg.matches("(?is).*(partner .* not reached|connection refused|timeout|hostname|service .* unknown).*"))
            return new ActionException("SAP_BAPI.UNREACHABLE", "Cannot reach the SAP system: " + msg, "Check the host, system number, router string and network access.", true);
        if (group == 123) return new ActionException("SAP_BAPI.ABAP_EXCEPTION", "The function raised ABAP exception " + key + ": " + msg, "Check the input values for this function.", false);
        return new ActionException("SAP_BAPI.ERROR", "SAP JCo " + cls + ": " + msg, "See the SAP message; check the function and its parameters.", group == 104);
    }

    // ---------------------------------------------------------------- destinations held in memory

    /** Registers OOA's destination provider once for the runner; JCo allows only one. */
    static synchronized void ensureProvider() {
        if (providerRegistered) return;
        Class<?> ddp = cls("com.sap.conn.jco.ext.DestinationDataProvider");
        Object provider = Proxy.newProxyInstance(loader(), new Class<?>[]{ddp}, (p, m, a) -> switch (m.getName()) {
            case "getDestinationProperties" -> { Properties pr = DESTINATIONS.get(String.valueOf(a[0])); yield pr == null ? null : (Properties) pr.clone(); }
            case "supportsEvents" -> false;
            case "setDestinationDataEventListener" -> null;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> "OOA destinations";
            default -> null;
        });
        try {
            Object registered = callStatic("com.sap.conn.jco.ext.Environment", "isDestinationDataProviderRegistered");
            if (Boolean.TRUE.equals(registered))
                throw new ActionException("SAP_BAPI.PROVIDER_IN_USE", "Another destination provider is already registered with SAP JCo in this runner",
                        "Run SAP BAPI agents on a runner without other JCo-based add-ons.");
        } catch (IllegalStateException noSuchMethod) { /* older JCo: register and let it complain */ }
        callStatic("com.sap.conn.jco.ext.Environment", "registerDestinationDataProvider", provider);
        providerRegistered = true;
    }

    static String addDestination(Properties p) {
        ensureProvider();
        String name = "ooa-" + UUID.randomUUID();
        DESTINATIONS.put(name, p);
        return name;
    }

    static void removeDestination(String name) { DESTINATIONS.remove(name); }

    // ---------------------------------------------------------------- values in and out

    /** A JCo value as an OOA value: dates as ISO text, numbers as BigDecimal, raw bytes as base64, records as dictionaries / tables. */
    static Object out(Object v) {
        if (v == null) return null;
        if (v instanceof Date d) {
            java.time.LocalDateTime t = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            return t.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? t.toLocalDate().toString() : t.toString();
        }
        if (v instanceof BigDecimal || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof byte[] b) return Base64.getEncoder().encodeToString(b);
        if (isA(v, "JCoTable")) return table(v);
        if (isA(v, "JCoRecord")) return record(v);
        return v.toString();
    }

    static boolean isA(Object v, String type) {
        try { return cls(type).isInstance(v); } catch (ActionException e) { return false; }
    }

    /** The fields of a record (structure or parameter list) as a dictionary, in metadata order. */
    static Map<String, Object> record(Object rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rec == null) return m;
        Object md = call(rec, "getMetaData");
        int n = (Integer) call(md, "getFieldCount");
        for (int i = 0; i < n; i++) {
            String name = (String) call(md, "getName", i);
            m.put(name, out(call(rec, "getValue", name)));
        }
        return m;
    }

    /** A JCo table as an OOA table (columns + rows); structure-typed cells become dictionaries. */
    static Map<String, Object> table(Object t) {
        Object md = call(t, "getMetaData");
        int n = (Integer) call(md, "getFieldCount");
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < n; i++) cols.add((String) call(md, "getName", i));
        List<Object> rows = new ArrayList<>();
        int count = (Integer) call(t, "getNumRows");
        for (int r = 0; r < count; r++) {
            call(t, "setRow", r);
            List<Object> row = new ArrayList<>();
            for (String c : cols) row.add(out(call(t, "getValue", c)));
            rows.add(row);
        }
        return com.oneofficeautomation.runner.Values.table(cols, rows);
    }

    /** Rows for a table parameter from an OOA table, a list of dictionaries, or one dictionary. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof Map<?, ?> m && m.get("columns") instanceof List<?> cols && m.get("rows") instanceof List<?> rows) {
            for (Object r : rows) {
                Map<String, Object> row = new LinkedHashMap<>();
                List<?> cells = r instanceof List<?> l ? l : r instanceof Map<?, ?> rm ? new ArrayList<>(rm.values()) : List.of();
                for (int i = 0; i < cols.size() && i < cells.size(); i++) row.put(String.valueOf(cols.get(i)), cells.get(i));
                out.add(row);
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) { out.add((Map<String, Object>) m); return out; }
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) throw new ActionException("SAP_BAPI.BAD_TABLE", "Each table row must be a dictionary of field values, got " + com.oneofficeautomation.runner.Values.typeName(o),
                        "Give a Table, or a list of dictionaries such as [{\"MATERIAL\": \"100-100\"}].");
                out.add((Map<String, Object>) m);
            }
            return out;
        }
        throw new ActionException("SAP_BAPI.BAD_TABLE", "A table parameter needs a Table or a list of dictionaries", "Give a Table, or a list of dictionaries.");
    }

    /** An OOA value for setValue: dictionaries fill structures field by field (done by the caller); numbers and text pass as they are. */
    static Object in(Object v) {
        if (v instanceof com.oneofficeautomation.sdk.SecureString s) return s.reveal();
        if (v instanceof BigDecimal b) return b.scale() <= 0 && b.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0 ? (Object) b.intValueExact() : b;
        if (v instanceof Boolean b) return b ? "X" : "";
        return v;
    }
}
