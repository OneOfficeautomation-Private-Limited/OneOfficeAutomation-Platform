package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSON Schema validation (draft-07 to 2020-12): type, enum, const, properties, patternProperties, additionalProperties,
 * propertyNames, required, dependentRequired/dependentSchemas (and draft-07 dependencies), items, prefixItems,
 * additionalItems, contains/minContains/maxContains, min/maxItems, uniqueItems, min/maxLength, pattern, numeric bounds,
 * multipleOf, min/maxProperties, allOf, anyOf, oneOf, not, if/then/else, $id/$anchor and $ref, including references to
 * other schema files and URLs (through a {@link Loader}), and format checks (date-time, date, time, duration, email,
 * hostname, ipv4, ipv6, uri, uri-reference, uuid, regex, json-pointer) when asked for.
 */
public final class JsonSchemaLite {
    private JsonSchemaLite() {}

    /** Loads the schema document a $ref points to (without the fragment). */
    @FunctionalInterface
    public interface Loader { Object load(URI document); }

    /** Base URI of a schema given as text or a Dictionary without $id; relative file references then need a schema file. */
    static final URI INLINE = URI.create("ooa-schema:/inline.json");
    static final int MAX_DEPTH = 200;

    /** Problems of a value against a JSON Schema, one line each; empty when it is valid. Local references only, no format checks. */
    public static List<Object> validate(Object schema, Object value) {
        return validate(schema, value, null, null, false);
    }

    /**
     * Problems of a value against a JSON Schema.
     *
     * @param base    URI of the schema document (e.g. its file), against which relative references resolve; null for inline schemas
     * @param loader  loads other schema documents; null allows references inside the schema only
     * @param formats check the format keyword
     */
    public static List<Object> validate(Object schema, Object value, URI base, Loader loader, boolean formats) {
        List<Object> errors = new ArrayList<>();
        Validator v = new Validator(loader, formats);
        URI b = v.register(schema, base == null ? INLINE : base);
        v.check(b, schema, Values.toJsonable(value), "$", errors, 0);
        return errors;
    }

    /** Kept for callers of the draft-07 subset: local references only, no format checks. */
    static void check(Object root, Object schema, Object v, String at, List<Object> errors) {
        Validator val = new Validator(null, false);
        URI b = val.register(root, INLINE);
        val.check(b, schema, v, at, errors, 0);
    }

    // ------------------------------------------------------------------ validator

    static final class Validator {
        final Loader loader;
        final boolean formats;
        /** Schema documents by URI (no fragment), and schemas with $id or $anchor by their absolute URI. */
        final Map<String, Object> documents = new HashMap<>(), ids = new HashMap<>();

        Validator(Loader loader, boolean formats) { this.loader = loader; this.formats = formats; }

        /** Indexes a document's $id and $anchor values; returns the document's own base URI. */
        URI register(Object doc, URI base) {
            URI own = withId(base, doc);
            documents.putIfAbsent(noFragment(base).toString(), doc);
            documents.putIfAbsent(noFragment(own).toString(), doc);
            index(doc, own, 0);
            return own;
        }

        @SuppressWarnings("unchecked")
        private void index(Object node, URI base, int depth) {
            if (depth > 100) return;
            if (node instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                URI b = withId(base, m);
                if (m.get("$id") instanceof String) ids.putIfAbsent(noFragment(b).toString(), m);
                if (m.get("$anchor") instanceof String a) ids.putIfAbsent(noFragment(b) + "#" + a, m);
                if (m.get("$id") instanceof String id && id.startsWith("#") && id.length() > 1) ids.putIfAbsent(noFragment(b) + id, m);  // draft-06/07 plain-name ids
                for (var e : m.entrySet()) {
                    if (e.getKey().equals("enum") || e.getKey().equals("const") || e.getKey().equals("examples") || e.getKey().equals("default")) continue;
                    index(e.getValue(), b, depth + 1);
                }
            } else if (node instanceof List<?> l) {
                for (Object x : l) index(x, base, depth + 1);
            }
        }

        static URI withId(URI base, Object schema) {
            if (schema instanceof Map<?, ?> m && m.get("$id") instanceof String id && !id.startsWith("#")) {
                try { return base.resolve(new URI(id.trim())); }
                catch (URISyntaxException | IllegalArgumentException e) { throw new ActionException("JSON.BAD_SCHEMA", "Invalid $id '" + id + "'", "Use an absolute URI or a relative path as $id."); }
            }
            return base;
        }

        static URI noFragment(URI u) {
            try { return new URI(u.getScheme(), u.getSchemeSpecificPart(), null); }
            catch (URISyntaxException e) { return u; }
        }

        /** The schema a $ref points to, and the base URI to continue with. */
        @SuppressWarnings("unchecked")
        Object[] resolve(URI base, String ref) {
            URI target;
            try { target = base.resolve(new URI(ref.trim())); }
            catch (URISyntaxException | IllegalArgumentException e) { throw new ActionException("JSON.BAD_SCHEMA", "Invalid $ref '" + ref + "'", "Use #/$defs/name, a file such as address.json#/$defs/x or a URL."); }
            String docKey = noFragment(target).toString();
            String frag = target.getFragment();
            Object doc = ids.containsKey(docKey) ? ids.get(docKey) : documents.get(docKey);
            URI docBase = noFragment(target);
            if (doc == null) {
                if ("ooa-schema".equals(target.getScheme()))
                    throw new ActionException("JSON.BAD_SCHEMA", "Reference '" + ref + "' points to another file, but the schema has no location",
                            "Give the schema as schema_path (references are read from its folder), or give it an $id URL.");
                if (loader == null)
                    throw new ActionException("JSON.BAD_SCHEMA", "Only references inside the schema are supported here, got '" + ref + "'", "Use references such as #/$defs/address.");
                doc = loader.load(docBase);
                docBase = register(doc, docBase);
            } else docBase = withId(docBase, doc);
            if (frag == null || frag.isEmpty()) return new Object[]{doc, docBase};
            if (frag.startsWith("/")) {
                Object cur = doc;
                URI curBase = docBase;
                for (String part : frag.substring(1).split("/", -1)) {
                    String key = part.replace("~1", "/").replace("~0", "~");
                    if (cur instanceof Map<?, ?> m && m.containsKey(key)) cur = ((Map<String, Object>) m).get(key);
                    else if (cur instanceof List<?> l && key.matches("\\d+") && Integer.parseInt(key) < l.size()) cur = l.get(Integer.parseInt(key));
                    else throw new ActionException("JSON.BAD_SCHEMA", "Reference '" + ref + "' not found in the schema", "Check the $ref path.");
                    curBase = withId(curBase, cur);
                }
                return new Object[]{cur, curBase};
            }
            Object anchored = ids.get(noFragment(docBase) + "#" + frag);
            if (anchored == null) throw new ActionException("JSON.BAD_SCHEMA", "Anchor '#" + frag + "' not found for reference '" + ref + "'", "Check the $anchor name.");
            return new Object[]{anchored, docBase};
        }

        @SuppressWarnings("unchecked")
        void check(URI base, Object schema, Object v, String at, List<Object> errors, int depth) {
            if (depth > MAX_DEPTH) throw new ActionException("JSON.BAD_SCHEMA", "The schema's references loop without end", "Check $ref values that point back to themselves.");
            if (schema instanceof Boolean b) { if (!b) errors.add(at + ": no value is allowed here"); return; }
            if (!(schema instanceof Map<?, ?> raw)) return;
            Map<String, Object> s = (Map<String, Object>) raw;
            base = withId(base, s);
            if (s.get("$ref") instanceof String ref) {
                Object[] r = resolve(base, ref);
                check((URI) r[1], r[0], v, at, errors, depth + 1);
            }
            if (s.get("$dynamicRef") instanceof String ref) {  // treated as a plain $ref
                Object[] r = resolve(base, ref);
                check((URI) r[1], r[0], v, at, errors, depth + 1);
            }
            if (s.containsKey("type")) {
                List<Object> types = s.get("type") instanceof List<?> l ? (List<Object>) l : List.of(s.get("type"));
                if (types.stream().noneMatch(t -> isType(v, String.valueOf(t)))) { errors.add(at + ": expected " + types.stream().map(String::valueOf).reduce((a, b) -> a + " or " + b).orElse("?") + ", got " + kind(v)); return; }
            }
            if (s.get("enum") instanceof List<?> en && en.stream().noneMatch(x -> same(x, v))) errors.add(at + ": " + JsonLite.write(v) + " is not one of " + JsonLite.write(en));
            if (s.containsKey("const") && !same(s.get("const"), v)) errors.add(at + ": must be " + JsonLite.write(s.get("const")));
            if (v instanceof String str) {
                int len = str.codePointCount(0, str.length());
                if (s.get("minLength") instanceof BigDecimal n && len < n.intValue()) errors.add(at + ": shorter than " + n.intValue() + " characters");
                if (s.get("maxLength") instanceof BigDecimal n && len > n.intValue()) errors.add(at + ": longer than " + n.intValue() + " characters");
                if (s.get("pattern") instanceof String p && !pattern(p).matcher(str).find()) errors.add(at + ": does not match pattern " + p);
                if (formats && s.get("format") instanceof String f && !Formats.valid(f, str)) errors.add(at + ": " + JsonLite.write(str) + " is not a valid " + f);
            }
            if (v instanceof BigDecimal d) {
                if (s.get("minimum") instanceof BigDecimal n && d.compareTo(n) < 0) errors.add(at + ": less than " + n.toPlainString());
                if (s.get("maximum") instanceof BigDecimal n && d.compareTo(n) > 0) errors.add(at + ": more than " + n.toPlainString());
                if (s.get("exclusiveMinimum") instanceof BigDecimal n && d.compareTo(n) <= 0) errors.add(at + ": must be more than " + n.toPlainString());
                if (s.get("exclusiveMaximum") instanceof BigDecimal n && d.compareTo(n) >= 0) errors.add(at + ": must be less than " + n.toPlainString());
                if (Boolean.TRUE.equals(s.get("exclusiveMinimum")) && s.get("minimum") instanceof BigDecimal n && d.compareTo(n) == 0) errors.add(at + ": must be more than " + n.toPlainString());  // draft-04
                if (Boolean.TRUE.equals(s.get("exclusiveMaximum")) && s.get("maximum") instanceof BigDecimal n && d.compareTo(n) == 0) errors.add(at + ": must be less than " + n.toPlainString());
                if (s.get("multipleOf") instanceof BigDecimal n && n.signum() > 0 && d.remainder(n).signum() != 0) errors.add(at + ": not a multiple of " + n.toPlainString());
            }
            if (v instanceof Map<?, ?> obj) object(base, s, (Map<String, Object>) obj, at, errors, depth);
            if (v instanceof List<?> arr) array(base, s, (List<Object>) arr, at, errors, depth);
            if (s.get("allOf") instanceof List<?> all) for (Object sub : all) check(base, sub, v, at, errors, depth + 1);
            final URI b = base;
            if (s.get("anyOf") instanceof List<?> any && any.stream().noneMatch(sub -> passes(b, sub, v, depth))) errors.add(at + ": matches none of the anyOf schemas");
            if (s.get("oneOf") instanceof List<?> one) {
                long n = one.stream().filter(sub -> passes(b, sub, v, depth)).count();
                if (n != 1) errors.add(at + ": matches " + n + " of the oneOf schemas instead of exactly one");
            }
            if (s.containsKey("not") && passes(base, s.get("not"), v, depth)) errors.add(at + ": must not match the 'not' schema");
            if (s.containsKey("if")) {
                if (passes(base, s.get("if"), v, depth)) { if (s.containsKey("then")) check(base, s.get("then"), v, at, errors, depth + 1); }
                else if (s.containsKey("else")) check(base, s.get("else"), v, at, errors, depth + 1);
            }
        }

        @SuppressWarnings("unchecked")
        private void object(URI base, Map<String, Object> s, Map<String, Object> o, String at, List<Object> errors, int depth) {
            if (s.get("required") instanceof List<?> req) for (Object k : req) if (!o.containsKey(String.valueOf(k))) errors.add(at + ": missing required property '" + k + "'");
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            Map<String, Object> patterns = s.get("patternProperties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            for (var e : o.entrySet()) {
                String path = at + "." + e.getKey();
                boolean matched = false;
                if (props.containsKey(e.getKey())) { check(base, props.get(e.getKey()), e.getValue(), path, errors, depth + 1); matched = true; }
                for (var pp : patterns.entrySet())
                    if (pattern(pp.getKey()).matcher(e.getKey()).find()) { check(base, pp.getValue(), e.getValue(), path, errors, depth + 1); matched = true; }
                if (!matched && s.containsKey("additionalProperties")) {
                    Object ap = s.get("additionalProperties");
                    if (Boolean.FALSE.equals(ap)) errors.add(at + ": property '" + e.getKey() + "' is not allowed");
                    else check(base, ap, e.getValue(), path, errors, depth + 1);
                }
                if (s.containsKey("propertyNames")) {
                    List<Object> sub = new ArrayList<>();
                    check(base, s.get("propertyNames"), e.getKey(), path, sub, depth + 1);
                    if (!sub.isEmpty()) errors.add(at + ": property name '" + e.getKey() + "' is not allowed (" + sub.get(0).toString().replaceFirst("^[^:]*: ", "") + ")");
                }
            }
            if (s.get("minProperties") instanceof BigDecimal n && o.size() < n.intValue()) errors.add(at + ": fewer than " + n.intValue() + " properties");
            if (s.get("maxProperties") instanceof BigDecimal n && o.size() > n.intValue()) errors.add(at + ": more than " + n.intValue() + " properties");
            Map<String, Object> depReq = new LinkedHashMap<>(), depSchemas = new LinkedHashMap<>();
            if (s.get("dependentRequired") instanceof Map<?, ?> m) depReq.putAll((Map<String, Object>) m);
            if (s.get("dependentSchemas") instanceof Map<?, ?> m) depSchemas.putAll((Map<String, Object>) m);
            if (s.get("dependencies") instanceof Map<?, ?> m)  // draft-07: a list means required names, otherwise a schema
                for (var e : ((Map<String, Object>) m).entrySet()) (e.getValue() instanceof List ? depReq : depSchemas).put(e.getKey(), e.getValue());
            for (var e : depReq.entrySet())
                if (o.containsKey(e.getKey()) && e.getValue() instanceof List<?> need)
                    for (Object k : need) if (!o.containsKey(String.valueOf(k))) errors.add(at + ": property '" + k + "' is required when '" + e.getKey() + "' is present");
            for (var e : depSchemas.entrySet()) if (o.containsKey(e.getKey())) check(base, e.getValue(), o, at, errors, depth + 1);
        }

        @SuppressWarnings("unchecked")
        private void array(URI base, Map<String, Object> s, List<Object> arr, String at, List<Object> errors, int depth) {
            int prefix = 0;
            List<Object> tuple = s.get("prefixItems") instanceof List<?> l ? (List<Object>) l : s.get("items") instanceof List<?> l2 ? (List<Object>) l2 : null;
            if (tuple != null) {
                prefix = Math.min(tuple.size(), arr.size());
                for (int i = 0; i < prefix; i++) check(base, tuple.get(i), arr.get(i), at + "[" + i + "]", errors, depth + 1);
            }
            Object rest = s.get("prefixItems") instanceof List ? s.get("items") : s.get("items") instanceof List ? s.get("additionalItems") : s.get("items");
            if (rest != null) for (int i = prefix; i < arr.size(); i++) {
                if (Boolean.FALSE.equals(rest)) { errors.add(at + ": no more than " + prefix + " items are allowed"); break; }
                check(base, rest, arr.get(i), at + "[" + i + "]", errors, depth + 1);
            }
            if (s.containsKey("contains")) {
                long n = arr.stream().filter(x -> passes(base, s.get("contains"), x, depth)).count();
                int min = s.get("minContains") instanceof BigDecimal m ? m.intValue() : 1;
                if (n < min) errors.add(at + (min <= 1 ? ": contains no item matching the 'contains' schema" : ": fewer than " + min + " items match the 'contains' schema"));
                if (s.get("maxContains") instanceof BigDecimal m && n > m.intValue()) errors.add(at + ": more than " + m.intValue() + " items match the 'contains' schema");
            }
            if (s.get("minItems") instanceof BigDecimal n && arr.size() < n.intValue()) errors.add(at + ": fewer than " + n.intValue() + " items");
            if (s.get("maxItems") instanceof BigDecimal n && arr.size() > n.intValue()) errors.add(at + ": more than " + n.intValue() + " items");
            if (Boolean.TRUE.equals(s.get("uniqueItems"))) {
                Set<String> seen = new HashSet<>();
                for (Object x : arr) if (!seen.add(JsonLite.canonical(norm(x)))) { errors.add(at + ": items are not unique"); break; }
            }
        }

        private boolean passes(URI base, Object schema, Object v, int depth) {
            List<Object> e = new ArrayList<>();
            check(base, schema, v, "$", e, depth + 1);
            return e.isEmpty();
        }
    }

    private static final Map<String, Pattern> PATTERNS = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Pattern> e) { return size() > 256; }
    });

    static Pattern pattern(String p) {
        Pattern c = PATTERNS.get(p);
        if (c != null) return c;
        try { c = Pattern.compile(p); }
        catch (PatternSyntaxException e) { throw new ActionException("JSON.BAD_SCHEMA", "Invalid pattern in schema: " + p, "Fix the regular expression in the schema."); }
        PATTERNS.put(p, c);
        return c;
    }

    // ------------------------------------------------------------------ formats

    /** Checks for the format keyword. Unknown formats pass, as the specification asks. */
    static final class Formats {
        private Formats() {}
        static final Pattern DURATION = Pattern.compile("^P(?!$)(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?$");
        static final Pattern EMAIL_LOCAL = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
        static final Pattern IDN_LOCAL = Pattern.compile("^[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~-]+(\\.[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~-]+)*$");
        static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
        static final Pattern IDN_LABEL = Pattern.compile("^[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?$");
        static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
        static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
        static final Pattern POINTER = Pattern.compile("^(/([^~/]|~[01])*)*$");
        static final Pattern REL_POINTER = Pattern.compile("^(0|[1-9]\\d*)(#|(/([^~/]|~[01])*)*)$");
        static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d):([0-5]\\d|60)(\\.\\d+)?([Zz]|[+-]([01]\\d|2[0-3]):[0-5]\\d)$");

        static boolean valid(String format, String v) {
            try {
                return switch (format) {
                    case "date-time" -> {
                        int t = Math.max(v.indexOf('T'), v.indexOf('t'));
                        yield t == 10 && date(v.substring(0, 10)) && TIME.matcher(v.substring(11)).matches();
                    }
                    case "date" -> date(v);
                    case "time" -> TIME.matcher(v).matches();
                    case "duration" -> DURATION.matcher(v).matches();
                    case "email" -> email(v, false);
                    case "idn-email" -> email(v, true);
                    case "hostname" -> hostname(v, false);
                    case "idn-hostname" -> hostname(v, true);
                    case "ipv4" -> IPV4.matcher(v).matches();
                    case "ipv6" -> v.indexOf(':') >= 0 && v.matches("^[0-9A-Fa-f:.]+$") && InetAddress.getByName(v) instanceof java.net.Inet6Address
                            || v.matches("^[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:(\\d{1,3}\\.){3}\\d{1,3}$") && InetAddress.getByName(v) != null;
                    case "uri", "iri" -> { URI u = new URI(v); yield u.isAbsolute(); }
                    case "uri-reference", "iri-reference" -> { new URI(v); yield true; }
                    case "uuid" -> UUID_RE.matcher(v).matches();
                    case "regex" -> { Pattern.compile(v); yield true; }
                    case "json-pointer" -> POINTER.matcher(v).matches();
                    case "relative-json-pointer" -> REL_POINTER.matcher(v).matches();
                    default -> true;
                };
            } catch (Exception e) {
                return false;
            }
        }

        static boolean date(String v) {
            if (!v.matches("^\\d{4}-\\d{2}-\\d{2}$")) return false;
            try { java.time.LocalDate.parse(v); return true; } catch (java.time.DateTimeException e) { return false; }
        }

        static boolean email(String v, boolean idn) {
            int at = v.lastIndexOf('@');
            if (at <= 0 || at == v.length() - 1 || at > 64) return false;
            String local = v.substring(0, at), domain = v.substring(at + 1);
            if (!(idn ? IDN_LOCAL : EMAIL_LOCAL).matcher(local).matches()) return false;
            if (domain.startsWith("[") && domain.endsWith("]")) return IPV4.matcher(domain.substring(1, domain.length() - 1)).matches()
                    || domain.startsWith("[IPv6:") && valid("ipv6", domain.substring(6, domain.length() - 1));
            return hostname(domain, idn);
        }

        static boolean hostname(String v, boolean idn) {
            String h = v.endsWith(".") ? v.substring(0, v.length() - 1) : v;
            if (h.isEmpty() || h.length() > 253) return false;
            for (String label : h.split("\\.", -1)) if (!(idn ? IDN_LABEL : LABEL).matcher(label).matches()) return false;
            return true;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isType(Object v, String t) {
        return switch (t) {
            case "null" -> v == null;
            case "boolean" -> v instanceof Boolean;
            case "string" -> v instanceof String;
            case "number" -> v instanceof BigDecimal;
            case "integer" -> v instanceof BigDecimal d && d.stripTrailingZeros().scale() <= 0;
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            default -> false;
        };
    }

    private static String kind(Object v) {
        return v == null ? "null" : v instanceof Boolean ? "boolean" : v instanceof String ? "string" : v instanceof BigDecimal ? "number"
                : v instanceof Map ? "object" : v instanceof List ? "array" : Values.typeName(v);
    }

    private static Object norm(Object x) { return x instanceof BigDecimal d ? d.stripTrailingZeros() : x; }

    private static boolean same(Object a, Object b) { return Values.looseEquals(norm(a), norm(b)) || Values.looseEquals(a, b); }
}
