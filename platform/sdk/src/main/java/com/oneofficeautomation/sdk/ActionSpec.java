package com.oneofficeautomation.sdk;

import java.lang.reflect.Method;
import java.util.*;

/** Reflected description of one action, condition or iterator. */
public record ActionSpec(String kind, String id, String name, String description, String category,
                         List<InputSpec> inputs, List<OutputSpec> outputs, double timeoutSeconds, boolean idempotent,
                         List<String> capabilities, Method method) {

    public record InputSpec(String name, OoaType type, String description, String label, boolean required,
                            Object defaultValue, String pattern, List<String> enumValues, String example) {
        public Map<String, Object> schema() {
            Map<String, Object> s = new LinkedHashMap<>(SchemaTypes.forType(type));
            s.put("description", description);
            if (!pattern.isEmpty()) s.put("pattern", pattern);
            if (!enumValues.isEmpty()) s.put("enum", enumValues);
            if (defaultValue != null) s.put("default", defaultValue);
            if (!example.isEmpty()) s.put("examples", List.of(example));
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
