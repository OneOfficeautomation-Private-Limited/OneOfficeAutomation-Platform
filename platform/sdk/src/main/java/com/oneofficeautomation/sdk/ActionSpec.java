package com.oneofficeautomation.sdk;

import java.lang.reflect.Method;
import java.util.*;

/** Reflected description of one action, condition or iterator. */
public record ActionSpec(String kind, String id, String name, String description, String category,
                         List<InputSpec> inputs, List<OutputSpec> outputs, double timeoutSeconds, boolean idempotent,
                         List<String> capabilities, Method method, String panel) {

    public ActionSpec(String kind, String id, String name, String description, String category,
                      List<InputSpec> inputs, List<OutputSpec> outputs, double timeoutSeconds, boolean idempotent,
                      List<String> capabilities, Method method) {
        this(kind, id, name, description, category, inputs, outputs, timeoutSeconds, idempotent, capabilities, method, "");
    }

    /**
     * The parsed panel layout: {"main": [name | {"tick": label, "fields": [names]}], "more": [...]}, or null when the
     * action has none. Every input must appear exactly once.
     */
    public Map<String, Object> panelLayout() {
        if (panel == null || panel.isBlank()) return null;
        String[] halves = panel.split("\\|", -1);
        if (halves.length > 2) throw bad("has more than one '|'");
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", panelPart(halves[0], seen));
        out.put("more", halves.length > 1 ? panelPart(halves[1], seen) : List.of());
        for (InputSpec i : inputs) if (!seen.contains(i.name())) throw bad("does not place input '" + i.name() + "'");
        return out;
    }

    private List<Object> panelPart(String text, Set<String> seen) {
        List<Object> items = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ',' || Character.isWhitespace(c)) { i++; continue; }
            if (c == '(') {   // (Label: Option A = a, b / Option B = c): a choice that shows only the chosen option's fields
                int end = text.indexOf(')', i);
                if (end < 0) throw bad("has an unclosed '('");
                String body = text.substring(i + 1, end);
                int colon = body.indexOf(':');
                if (colon < 1) throw bad("choice '" + body + "' needs 'Label: option / option'");
                List<Object> options = new ArrayList<>();
                for (String opt : body.substring(colon + 1).split("/")) {
                    int eq = opt.indexOf('=');
                    List<String> fields = new ArrayList<>();
                    for (String f : (eq < 0 ? opt : opt.substring(eq + 1)).split(",")) if (!f.isBlank()) fields.add(place(f.strip(), seen));
                    // an option with only a label ("Active cell =") stands for leaving the other options' fields empty
                    if (fields.isEmpty() && (eq < 1 || opt.substring(0, eq).isBlank())) throw bad("choice '" + body + "' has an option with no label and no fields");
                    Map<String, Object> o = new LinkedHashMap<>();
                    if (eq >= 0) o.put("label", opt.substring(0, eq).strip());
                    o.put("fields", fields);
                    options.add(o);
                }
                if (options.size() < 2) throw bad("choice '" + body + "' needs at least two options");
                items.add(Map.of("choice", body.substring(0, colon).strip(), "options", options));
                i = end + 1;
            } else if (c == '[') {
                int end = text.indexOf(']', i);
                if (end < 0) throw bad("has an unclosed '['");
                String body = text.substring(i + 1, end);
                int colon = body.indexOf(':');
                if (colon < 1) throw bad("group '" + body + "' needs 'Label: fields'");
                List<String> fields = new ArrayList<>();
                for (String f : body.substring(colon + 1).split(",")) if (!f.isBlank()) fields.add(place(f.strip(), seen));
                if (fields.isEmpty()) throw bad("group '" + body + "' has no fields");
                items.add(Map.of("tick", body.substring(0, colon).strip(), "fields", fields));
                i = end + 1;
            } else {
                int end = i;
                while (end < text.length() && text.charAt(end) != ',' && text.charAt(end) != '[' && text.charAt(end) != '(') end++;
                items.add(place(text.substring(i, end).strip(), seen));
                i = end;
            }
        }
        return items;
    }

    private String place(String n, Set<String> seen) {
        if (input(n).isEmpty()) throw bad("names '" + n + "', which is not an input");
        if (!seen.add(n)) throw bad("places '" + n + "' twice");
        return n;
    }

    private OoaException bad(String what) {
        return new OoaException("PKG.ERROR", "The panel of '" + id + "' " + what, "List every input once: \"a, [Label: b], c | d\".");
    }

    public record InputSpec(String name, OoaType type, String description, String label, boolean required,
                            Object defaultValue, String pattern, List<String> enumValues, String example,
                            String visibleWhen, double min, double max) {
        public InputSpec(String name, OoaType type, String description, String label, boolean required,
                         Object defaultValue, String pattern, List<String> enumValues, String example) {
            this(name, type, description, label, required, defaultValue, pattern, enumValues, example, "", Double.NaN, Double.NaN);
        }

        /** The parsed {@code visibleWhen} rule: field name to the allowed values, or to "!" followed by the refused values. */
        public Map<String, List<String>> visibleRule() {
            Map<String, List<String>> rule = new LinkedHashMap<>();
            if (visibleWhen == null || visibleWhen.isBlank()) return rule;
            for (String part : visibleWhen.split(";")) {
                String t = part.strip();
                if (t.isEmpty()) continue;
                boolean not = t.contains("!=");
                String[] kv = t.split(not ? "!=" : "=", 2);
                if (kv.length != 2) throw new OoaException("PKG.ERROR", "Input '" + name + "': visibleWhen part '" + t + "' is not field=value");
                List<String> vals = new ArrayList<>();
                if (not) vals.add("!");
                for (String v : kv[1].split("\\|")) vals.add(v.strip());
                rule.put(kv[0].strip(), vals);
            }
            return rule;
        }

        /** Whether the field applies for these literal argument values; a $reference or expression counts as "may apply". */
        public boolean visibleFor(Map<String, ?> args, java.util.function.Function<String, Object> defaults) {
            for (Map.Entry<String, List<String>> e : visibleRule().entrySet()) {
                Object v = args.containsKey(e.getKey()) ? args.get(e.getKey()) : defaults.apply(e.getKey());
                if (v instanceof String s && s.startsWith("$")) continue;
                if (v instanceof Map<?, ?>) continue;
                String sv = v == null ? "" : String.valueOf(v);
                List<String> vals = e.getValue();
                boolean neg = !vals.isEmpty() && vals.get(0).equals("!");
                boolean hit = (neg ? vals.subList(1, vals.size()) : vals).contains(sv);
                if (neg == hit) return false;
            }
            return true;
        }

        public Map<String, Object> schema() {
            Map<String, Object> s = new LinkedHashMap<>(SchemaTypes.forType(type));
            s.put("description", description);
            if (!pattern.isEmpty()) s.put("pattern", pattern);
            if (!enumValues.isEmpty()) s.put("enum", enumValues);
            if (defaultValue != null) s.put("default", defaultValue);
            if (!example.isEmpty()) s.put("examples", List.of(example));
            if (!Double.isNaN(min)) s.put("minimum", java.math.BigDecimal.valueOf(min).stripTrailingZeros());
            if (!Double.isNaN(max)) s.put("maximum", java.math.BigDecimal.valueOf(max).stripTrailingZeros());
            if (visibleWhen != null && !visibleWhen.isBlank()) s.put("x-ooa-visible-when", visibleRule());
            s.put("x-ooa-type", type.flowName());
            s.put("x-ooa-label", label.isEmpty() ? FieldLabels.of(name) : label);
            return s;
        }
    }

    public record OutputSpec(String name, OoaType type, String description) {}

    public Map<String, Object> manifest() {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (InputSpec i : inputs) {
            props.put(i.name(), i.schema());
            if (i.required() && i.defaultValue() == null) required.add(i.name());
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("type", "object");
        in.put("properties", props);
        in.put("required", required);
        in.put("additionalProperties", false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("description", description); m.put("category", category);
        m.put("inputs", in);
        if (kind.equals("action")) {
            Map<String, Object> outs = new LinkedHashMap<>();
            for (OutputSpec o : outputs) outs.put(o.name(), Map.of("x-ooa-type", o.type().flowName(), "description", o.description()));
            m.put("outputs", outs);
            m.put("timeoutSeconds", java.math.BigDecimal.valueOf(timeoutSeconds));
            m.put("idempotent", idempotent);
            m.put("capabilities", capabilities);
            Map<String, Object> layout = panelLayout();
            if (layout != null) m.put("x-ooa-panel", layout);
        }
        return m;
    }

    public Optional<InputSpec> input(String n) { return inputs.stream().filter(i -> i.name().equals(n)).findFirst(); }
}
