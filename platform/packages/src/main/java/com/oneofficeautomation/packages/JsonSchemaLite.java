package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The commonly used part of JSON Schema (draft 2020-12 and earlier): type, enum, const, properties, required,
 * additionalProperties, items, min/maxItems, uniqueItems, min/maxLength, pattern, minimum, maximum, exclusive bounds,
 * multipleOf, min/maxProperties, allOf, anyOf, oneOf, not and local $ref (#/$defs/..., #/definitions/...).
 * Unknown keywords, such as format, are ignored, as the specification allows.
 */
public final class JsonSchemaLite {
    private JsonSchemaLite() {}

    /** Problems of a value against a JSON Schema (draft-07 subset), one line each; empty when it is valid. */
    public static List<Object> validate(Object schema, Object value) {
        List<Object> errors = new ArrayList<>();
        check(schema, schema, com.oneofficeautomation.runner.Values.toJsonable(value), "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    static void check(Object root, Object schema, Object v, String at, List<Object> errors) {
        if (schema instanceof Boolean b) { if (!b) errors.add(at + ": no value is allowed here"); return; }
        if (!(schema instanceof Map<?, ?> raw)) return;
        Map<String, Object> s = (Map<String, Object>) raw;
        if (s.get("$ref") instanceof String ref) check(root, resolve(root, ref), v, at, errors);
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
            if (s.get("pattern") instanceof String p) {
                try { if (!Pattern.compile(p).matcher(str).find()) errors.add(at + ": does not match pattern " + p); }
                catch (PatternSyntaxException e) { throw new ActionException("JSON.BAD_SCHEMA", "Invalid pattern in schema: " + p, "Fix the regular expression in the schema."); }
            }
        }
        if (v instanceof BigDecimal d) {
            if (s.get("minimum") instanceof BigDecimal n && d.compareTo(n) < 0) errors.add(at + ": less than " + n.toPlainString());
            if (s.get("maximum") instanceof BigDecimal n && d.compareTo(n) > 0) errors.add(at + ": more than " + n.toPlainString());
            if (s.get("exclusiveMinimum") instanceof BigDecimal n && d.compareTo(n) <= 0) errors.add(at + ": must be more than " + n.toPlainString());
            if (s.get("exclusiveMaximum") instanceof BigDecimal n && d.compareTo(n) >= 0) errors.add(at + ": must be less than " + n.toPlainString());
            if (s.get("multipleOf") instanceof BigDecimal n && n.signum() > 0 && d.remainder(n).signum() != 0) errors.add(at + ": not a multiple of " + n.toPlainString());
        }
        if (v instanceof Map<?, ?> obj) {
            Map<String, Object> o = (Map<String, Object>) obj;
            if (s.get("required") instanceof List<?> req) for (Object k : req) if (!o.containsKey(String.valueOf(k))) errors.add(at + ": missing required property '" + k + "'");
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            for (var e : o.entrySet()) {
                String path = at + "." + e.getKey();
                if (props.containsKey(e.getKey())) check(root, props.get(e.getKey()), e.getValue(), path, errors);
                else if (s.containsKey("additionalProperties")) {
                    Object ap = s.get("additionalProperties");
                    if (Boolean.FALSE.equals(ap)) errors.add(at + ": property '" + e.getKey() + "' is not allowed");
                    else check(root, ap, e.getValue(), path, errors);
                }
            }
            if (s.get("minProperties") instanceof BigDecimal n && o.size() < n.intValue()) errors.add(at + ": fewer than " + n.intValue() + " properties");
            if (s.get("maxProperties") instanceof BigDecimal n && o.size() > n.intValue()) errors.add(at + ": more than " + n.intValue() + " properties");
        }
        if (v instanceof List<?> arr) {
            if (s.containsKey("items")) for (int i = 0; i < arr.size(); i++) check(root, s.get("items"), arr.get(i), at + "[" + i + "]", errors);
            if (s.get("minItems") instanceof BigDecimal n && arr.size() < n.intValue()) errors.add(at + ": fewer than " + n.intValue() + " items");
            if (s.get("maxItems") instanceof BigDecimal n && arr.size() > n.intValue()) errors.add(at + ": more than " + n.intValue() + " items");
            if (Boolean.TRUE.equals(s.get("uniqueItems"))) {
                Set<String> seen = new HashSet<>();
                for (Object x : arr) if (!seen.add(JsonLite.canonical(norm(x)))) { errors.add(at + ": items are not unique"); break; }
            }
        }
        if (s.get("allOf") instanceof List<?> all) for (Object sub : all) check(root, sub, v, at, errors);
        if (s.get("anyOf") instanceof List<?> any && any.stream().noneMatch(sub -> passes(root, sub, v))) errors.add(at + ": matches none of the anyOf schemas");
        if (s.get("oneOf") instanceof List<?> one) {
            long n = one.stream().filter(sub -> passes(root, sub, v)).count();
            if (n != 1) errors.add(at + ": matches " + n + " of the oneOf schemas instead of exactly one");
        }
        if (s.containsKey("not") && passes(root, s.get("not"), v)) errors.add(at + ": must not match the 'not' schema");
    }

    private static boolean passes(Object root, Object schema, Object v) {
        List<Object> e = new ArrayList<>();
        check(root, schema, v, "$", e);
        return e.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Object root, String ref) {
        if (!ref.startsWith("#")) throw new ActionException("JSON.BAD_SCHEMA", "Only references inside the schema are supported, got '" + ref + "'", "Use references such as #/$defs/address.");
        Object cur = root;
        for (String part : ref.substring(1).split("/")) {
            if (part.isEmpty()) continue;
            String key = part.replace("~1", "/").replace("~0", "~");
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(key)) throw new ActionException("JSON.BAD_SCHEMA", "Reference '" + ref + "' not found in the schema", "Check the $ref path.");
            cur = ((Map<String, Object>) m).get(key);
        }
        return cur;
    }

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
