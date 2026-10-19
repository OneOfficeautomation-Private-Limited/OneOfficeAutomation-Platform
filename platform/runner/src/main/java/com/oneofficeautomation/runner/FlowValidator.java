package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Flow validation (FR-STU-007, architecture s6 "Plan"): JSON Schema first (via the adapter), then
 * semantic checks against package manifests. Returns every problem at once, each linked to a step id
 * with a suggested fix, so Studio can list them and the AI assistant can repair them.
 */
public final class FlowValidator {
    private static final Set<String> KINDS = Set.of("TaskBot", "ApiTask", "Process", "Form", "Agent");
    private static final Pattern ACTION = Pattern.compile("^[a-z][a-z0-9_.]*/[a-z][a-z0-9_]*$");

    private final PackageRegistry registry;
    private final FlowSchemaValidator schema;

    public FlowValidator(PackageRegistry registry, FlowSchemaValidator schema) {
        this.registry = registry;
        this.schema = schema == null ? FlowSchemaValidator.NONE : schema;
    }

    public void validateOrThrow(Map<String, Object> flow) {
        List<Map<String, Object>> problems = validate(flow);
        if (!problems.isEmpty()) {
            Map<String, Object> first = problems.get(0);
            throw new OoaException("FLOW.INVALID", "Flow has " + problems.size() + " problem(s); first: " + first.get("message"),
                    (String) first.get("suggestedFix"), false, Map.of("problems", problems));
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> validate(Map<String, Object> flow) {
        List<Map<String, Object>> problems = new ArrayList<>(schema.validate(flow));
        if (!problems.isEmpty()) return problems;
        P p = new P(problems);

        // structural guard (also protects callers that run without the JSON Schema adapter)
        Object kind = flow.get("kind");
        if (!KINDS.contains(kind)) { p.add(null, "kind", "kind must be one of " + KINDS, null); return problems; }
        for (String f : List.of("id", "name"))
            if (!(flow.get(f) instanceof String s) || s.isBlank()) p.add(null, f, "Missing required field '" + f + "'", null);
        if ("Agent".equals(kind) && !(flow.get("agent") instanceof Map)) p.add(null, "agent", "Agent flows need an 'agent' section", null);
        if (!"Agent".equals(kind) && !(flow.get("steps") instanceof List)) p.add(null, "steps", "Missing required field 'steps'", null);
        if (!problems.isEmpty()) return problems;

        Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
        for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> v = (Map<String, Object>) o;
            String name = (String) v.get("name");
            if (declared.put(name, v) != null) p.add(null, "variables/" + name, "Duplicate variable name '" + name + "'", "Give every variable a unique name.");
            if (Runner.RESERVED.contains(name)) p.add(null, "variables/" + name, "'" + name + "' is a reserved built-in variable name", "Choose another name; $sys and $global are provided automatically.");
            if ("Credential".equals(v.get("type")) && v.containsKey("default"))
                p.add(null, "variables/" + name, "Credential variable '" + name + "' must not have a default value",
                        "Remove the default; supply a vault reference at run time.");
            try { OoaType.fromFlowName(String.valueOf(v.get("type"))); }
            catch (IllegalArgumentException e) { p.add(null, "variables/" + name, "Unknown type '" + v.get("type") + "'", null); }
        }

        Map<String, Object> requiresMap = (Map<String, Object>) flow.getOrDefault("requires", Map.of());
        Map<String, Object> requires = (Map<String, Object>) requiresMap.getOrDefault("packages", Map.of());
        List<Object> caps = (List<Object>) requiresMap.getOrDefault("capabilities", List.of());
        if ("ApiTask".equals(kind) && caps.contains("desktop"))
            p.add(null, "requires/capabilities", "ApiTask flows cannot require the desktop capability", "Use kind TaskBot for agents that drive a desktop.");

        Set<String> seen = new HashSet<>();
        for (Map<String, Object> step : Steps.walk((List<Object>) flow.getOrDefault("steps", List.of()))) {
            String sid = String.valueOf(step.get("id"));
            String act = String.valueOf(step.get("action"));
            if (!seen.add(sid)) p.add(sid, null, "Duplicate step id '" + sid + "'", "Step ids must be unique; Agent Studio regenerates them on paste.");
            if (!ACTION.matcher(act).matches()) { p.add(sid, "action", "Invalid action address '" + act + "'", "Use package/action, e.g. ooa.excel/get_cell."); continue; }
            String pid = act.substring(0, act.indexOf('/')), aid = act.substring(act.indexOf('/') + 1);
            Map<String, Object> args = step.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

            Set<String> refs = Set.of();
            try {
                Set<String> r = new TreeSet<>(Expressions.references(normConditionArgs(args)));
                for (Object b : (List<Object>) step.getOrDefault("elseIf", List.of()))
                    r.addAll(Expressions.references(normCondition(((Map<String, Object>) b).get("condition"))));
                refs = r;
            } catch (OoaException e) { p.add(sid, "args", e.getMessage(), e.suggestedFix()); }
            for (String n : refs) if (!declared.containsKey(n) && !Runner.RESERVED.contains(n)) p.add(sid, "args", "Variable '$" + n + "' is not declared", "Declare '" + n + "' in variables or fix the spelling.");
            Map<String, Object> out = step.get("out") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            out.forEach((o, var) -> {
                if (Runner.RESERVED.contains(String.valueOf(var))) p.add(sid, "out/" + o, "Cannot store an output in the built-in variable '" + var + "'", "Choose one of your own variables.");
                else if (!declared.containsKey(var)) p.add(sid, "out/" + o, "Output target '" + var + "' is not declared", "Declare variable '" + var + "'.");
            });

            boolean container = pid.equals("core") && CorePackage.CONTAINERS.contains(aid);
            if (step.get("children") instanceof List<?> l && !l.isEmpty() && !container)
                p.add(sid, null, "'" + act + "' cannot have children", "Only if, loop, try, step and parallel hold children.");
            if ((step.containsKey("catch") || step.containsKey("finally")) && !act.equals("core/try")) p.add(sid, null, "catch/finally are only allowed on core/try", null);
            if ((step.containsKey("else") || step.containsKey("elseIf")) && !act.equals("core/if")) p.add(sid, null, "else/elseIf are only allowed on core/if", null);
            if (act.equals("core/loop")) {
                Object it = args.get("iterator");
                if (it instanceof String s && !CorePackage.BUILTIN_ITERATORS.contains(s) && !s.contains("/"))
                    p.add(sid, "args/iterator", "Unknown iterator '" + s + "'", "Use one of " + new TreeSet<>(CorePackage.BUILTIN_ITERATORS) + " or package/iterator.");
                for (String k : List.of("as", "index"))
                    if (args.get(k) instanceof String v && !declared.containsKey(v)) p.add(sid, "args/" + k, "Loop variable '" + v + "' is not declared", null);
            }
            if (act.equals("core/set") && args.get("variable") instanceof String v && !declared.containsKey(v))
                p.add(sid, "args/variable", "Variable '" + v + "' is not declared", null);
            for (Object c : (List<Object>) step.getOrDefault("catch", List.of()))
                if (((Map<String, Object>) c).get("as") instanceof String v && !declared.containsKey(v)) p.add(sid, "catch/as", "Catch variable '" + v + "' is not declared", null);

            // manifest checks
            PackageRegistry.LoadedPackage pkg;
            try { pkg = registry.resolve(pid, (String) requires.get(pid)); }
            catch (OoaException e) { p.add(sid, "action", e.getMessage(), e.suggestedFix()); continue; }
            ActionSpec spec = pkg.spec("action", aid);
            if (spec == null) { p.add(sid, "action", "Toolkit " + pid + "@" + pkg.version() + " has no action '" + aid + "'", "Pick the action from the command library."); continue; }
            if ("ApiTask".equals(kind) && spec.capabilities().contains("desktop"))
                p.add(sid, "action", "'" + act + "' needs a desktop and cannot run in an ApiTask", null);
            for (String k : args.keySet())
                if (spec.input(k).isEmpty()) p.add(sid, "args/" + k, "Unknown input '" + k + "' for " + act,
                        "Valid inputs: " + (spec.inputs().isEmpty() ? "none" : String.join(", ", spec.inputs().stream().map(ActionSpec.InputSpec::name).toList())) + ".");
            for (ActionSpec.InputSpec in : spec.inputs()) {
                if (in.required() && in.defaultValue() == null && !args.containsKey(in.name()))
                    p.add(sid, "args/" + in.name(), "Missing required input '" + in.name() + "' for " + act, in.description());
                if (args.containsKey(in.name())) checkLiteral(p, sid, in, args.get(in.name()));
            }
            Set<String> outputs = new HashSet<>();
            for (ActionSpec.OutputSpec o : spec.outputs()) outputs.add(o.name());
            for (String o : out.keySet()) if (!outputs.contains(o)) p.add(sid, "out/" + o, act + " has no output '" + o + "'",
                    "Outputs: " + (outputs.isEmpty() ? "none" : String.join(", ", new TreeSet<>(outputs))) + ".");
        }
        return problems;
    }

    /** Literal (non-reference) argument values are checked against the manifest's type, enum and pattern. */
    private static void checkLiteral(P p, String sid, ActionSpec.InputSpec in, Object v) {
        boolean dynamic;
        try { dynamic = !Expressions.references(v).isEmpty() || (v instanceof Map<?, ?> m && m.size() == 1 && (m.containsKey("expr") || m.containsKey("literal"))); }
        catch (OoaException e) { return; }
        if (dynamic || v == null) return;
        String path = "args/" + in.name();
        boolean ok = switch (in.type()) {
            case STRING, FILE -> v instanceof String;
            case NUMBER -> v instanceof BigDecimal;
            case BOOLEAN -> v instanceof Boolean;
            case LIST -> v instanceof List;
            case DICTIONARY, RECORD -> v instanceof Map;
            default -> true;
        };
        if (!ok) { p.add(sid, path, "Input '" + in.name() + "' expects " + in.type().flowName() + ", got " + Values.typeName(v), in.description()); return; }
        if (!in.enumValues().isEmpty() && !in.enumValues().contains(String.valueOf(v)))
            p.add(sid, path, "Input '" + in.name() + "': " + Values.safeRepr(v) + " is not one of " + in.enumValues(), in.description());
        if (!in.pattern().isEmpty() && v instanceof String s && !Pattern.compile(in.pattern()).matcher(s).find())
            p.add(sid, path, "Input '" + in.name() + "': '" + s + "' does not match " + in.pattern(), in.description());
    }

    /** Condition strings are expressions, so they are scanned as {"expr": ...}. */
    @SuppressWarnings("unchecked")
    static Object normCondition(Object c) {
        if (c instanceof String s) return Map.of("expr", s);
        if (c instanceof Map<?, ?> m) {
            for (String k : List.of("all", "any")) if (m.get(k) instanceof List<?> l)
                return Map.of(k, l.stream().map(FlowValidator::normCondition).toList());
            if (m.containsKey("not")) return Map.of("not", normCondition(m.get("not")));
        }
        return c;
    }

    private static Object normConditionArgs(Map<String, Object> args) {
        if (!args.containsKey("condition")) return args;
        Map<String, Object> copy = new LinkedHashMap<>(args);
        copy.put("condition", normCondition(args.get("condition")));
        return copy;
    }

    private record P(List<Map<String, Object>> list) {
        void add(String stepId, String path, String message, String fix) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepId", stepId); m.put("path", path); m.put("message", message); m.put("suggestedFix", fix);
            list.add(m);
        }
    }
}
