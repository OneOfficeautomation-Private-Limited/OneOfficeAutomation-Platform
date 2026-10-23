package com.oneofficeautomation.sdk;

import java.lang.reflect.Method;
import java.util.*;

/** Reflected description of one action, condition or iterator. */
public record ActionSpec(String kind, String id, String name, String description, String category,
                         List<InputSpec> inputs, List<OutputSpec> outputs, double timeoutSeconds, boolean idempotent,
                         List<String> capabilities, Method method) {

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
            s.put("x-ooa-label", label.isEmpty() ? Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('_', ' ') : label);
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
        }
        return m;
    }

    public Optional<InputSpec> input(String n) { return inputs.stream().filter(i -> i.name().equals(n)).findFirst(); }
}
