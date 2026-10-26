package com.oneofficeautomation.sdk;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds the package manifest from annotations. In the Gradle build an annotation processor writes the
 * same manifest.json at compile time; this runtime path is used for first-party packages and tests.
 * Missing descriptions are rejected: the AI assistant and agents rely on them (architecture section 19).
 */
public final class PackageIntrospector {
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern PKG_ID = Pattern.compile("^[a-z][a-z0-9_.]*$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private PackageIntrospector() {}

    public record PackageInfo(OoaPackage meta, Map<String, ActionSpec> actions, Map<String, ActionSpec> conditions,
                              Map<String, ActionSpec> iterators) {}

    public static PackageInfo inspect(Class<?> cls) {
        OoaPackage meta = cls.getAnnotation(OoaPackage.class);
        if (meta == null) throw new OoaException("PKG.ERROR", cls.getName() + " is not annotated with @OoaPackage");
        if (!PKG_ID.matcher(meta.id()).matches()) throw new OoaException("PKG.ERROR", "Invalid package id '" + meta.id() + "'");
        if (!SEMVER.matcher(meta.version()).matches())
            throw new OoaException("PKG.ERROR", "Package version '" + meta.version() + "' is not semantic (x.y.z)");
        if (meta.description().isBlank()) throw new OoaException("PKG.ERROR", "Package " + meta.id() + " has no description");
        Map<String, ActionSpec> actions = new TreeMap<>(), conditions = new TreeMap<>(), iterators = new TreeMap<>();
        for (Method m : cls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Action a = m.getAnnotation(Action.class);
            Condition c = m.getAnnotation(Condition.class);
            LoopIterator it = m.getAnnotation(LoopIterator.class);
            if (a != null) put(actions, spec("action", a.id(), a.name(), a.description(), a.category(), a.timeoutSeconds(),
                    a.idempotent(), List.of(a.capabilities()), m, a.panel()), meta);
            if (c != null) put(conditions, spec("condition", c.id(), c.name(), c.description(), "Conditions", 60, true, List.of(), m, ""), meta);
            if (it != null) put(iterators, spec("iterator", it.id(), it.name(), it.description(), "Iterators", 60, true, List.of(), m, ""), meta);
        }
        return new PackageInfo(meta, actions, conditions, iterators);
    }

    private static void put(Map<String, ActionSpec> map, ActionSpec s, OoaPackage meta) {
        if (map.put(s.id(), s) != null) throw new OoaException("PKG.ERROR", "Duplicate " + s.kind() + " '" + s.id() + "' in " + meta.id());
    }

    private static ActionSpec spec(String kind, String id, String name, String desc, String category, double timeout,
                                   boolean idempotent, List<String> caps, Method m, String panel) {
        if (!ID.matcher(id).matches()) throw new OoaException("PKG.ERROR", "Invalid " + kind + " id '" + id + "'", "Use lower_snake_case.");
        if (desc.isBlank()) throw new OoaException("PKG.ERROR", kind + " '" + id + "' has no description",
                "Add a one-sentence description; the AI assistant and agents rely on it.");
        Parameter[] ps = m.getParameters();
        if (ps.length == 0 || ps[0].getType() != ActionContext.class)
            throw new OoaException("PKG.ERROR", kind + " '" + id + "' must take ActionContext as its first parameter");
        List<ActionSpec.InputSpec> inputs = new ArrayList<>();
        for (int i = 1; i < ps.length; i++) {
            Input in = ps[i].getAnnotation(Input.class);
            if (in == null) throw new OoaException("PKG.ERROR", "Parameter " + i + " of '" + id + "' has no @Input");
            if (in.description().isBlank()) throw new OoaException("PKG.ERROR", "Input '" + in.name() + "' of '" + id + "' has no description");
            Object def = in.defaultValue().isEmpty() ? null : JsonLite.parse(in.defaultValue());
            inputs.add(new ActionSpec.InputSpec(in.name(), in.type(), in.description(), in.label(), in.required(), def,
                    in.pattern(), List.of(in.enumValues()), in.example(), in.visibleWhen(), in.min(), in.max()));
        }
        for (ActionSpec.InputSpec in : inputs)
            for (String other : in.visibleRule().keySet())
                if (inputs.stream().noneMatch(x -> x.name().equals(other)))
                    throw new OoaException("PKG.ERROR", "Input '" + in.name() + "' of '" + id + "' is shown when '" + other + "' has a value, but there is no such input");
        List<ActionSpec.OutputSpec> outputs = new ArrayList<>();
        for (Output o : m.getAnnotationsByType(Output.class)) outputs.add(new ActionSpec.OutputSpec(o.name(), o.type(), o.description()));
        ActionSpec spec = new ActionSpec(kind, id, name, desc, category, List.copyOf(inputs), List.copyOf(outputs), timeout, idempotent,
                caps, m, panel);
        spec.panelLayout();   // a panel that misses or repeats an input fails when the toolkit loads, not in Studio
        return spec;
    }

    /** Manifest in the shared format (identical to the Python reference SDK). */
    public static Map<String, Object> manifest(Class<?> cls) {
        PackageInfo p = inspect(cls);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manifestVersion", java.math.BigDecimal.ONE);
        m.put("id", p.meta().id()); m.put("name", p.meta().name()); m.put("version", p.meta().version());
        m.put("description", p.meta().description()); m.put("publisher", p.meta().publisher());
        m.put("actions", p.actions().values().stream().map(ActionSpec::manifest).toList());
        m.put("conditions", p.conditions().values().stream().map(ActionSpec::manifest).toList());
        m.put("iterators", p.iterators().values().stream().map(ActionSpec::manifest).toList());
        return m;
    }
}
