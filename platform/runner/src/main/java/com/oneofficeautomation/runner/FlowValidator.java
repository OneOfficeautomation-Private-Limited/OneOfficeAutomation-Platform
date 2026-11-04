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
            try { checkDefault(p, name, OoaType.fromFlowName(String.valueOf(v.get("type"))), v); }
            catch (IllegalArgumentException e) { p.add(null, "variables/" + name, "Unknown type '" + v.get("type") + "'", null); }
        }

        Map<String, Object> requiresMap = (Map<String, Object>) flow.getOrDefault("requires", Map.of());
        Map<String, Object> requires = (Map<String, Object>) requiresMap.getOrDefault("packages", Map.of());
        List<Object> caps = (List<Object>) requiresMap.getOrDefault("capabilities", List.of());
        if ("ApiTask".equals(kind) && caps.contains("desktop"))
            p.add(null, "requires/capabilities", "ApiTask flows cannot require the desktop capability", "Use kind TaskBot for agents that drive a desktop.");

        // the agent's error handler: its variable, what happens after it, and (below) its steps like any others
        if (flow.containsKey("onError")) {
            Map<String, Object> h = Steps.onError(flow);
            if (h == null || (h.containsKey("steps") && !(h.get("steps") instanceof List)))
                p.add(null, "onError", "onError must be {\"as\": variable, \"steps\": [...], \"then\": \"fail\" or \"succeed\"}", null);
            else {
                if (h.get("as") instanceof String as && !as.isBlank()) {
                    if (Runner.RESERVED.contains(as)) p.add(null, "onError/as", "Cannot store the error in the built-in variable '" + as + "'", "Choose one of your own variables.");
                    else if (!declared.containsKey(as)) p.add(null, "onError/as", "Error handler variable '" + as + "' is not declared", "Declare a Dictionary variable '" + as + "'.");
                    else if (!List.of("Dictionary", "Any").contains(String.valueOf(declared.get(as).get("type"))))
                        p.add(null, "onError/as", "Error handler variable '" + as + "' must be a Dictionary, not " + declared.get(as).get("type"), "Make '" + as + "' a Dictionary variable.");
                }
                if (h.get("then") != null && !List.of("fail", "succeed").contains(String.valueOf(h.get("then"))))
                    p.add(null, "onError/then", "onError.then must be 'fail' or 'succeed', got '" + h.get("then") + "'", "Use fail (the default) to end the run failed with the original error.");
            }
        }
        // subflows: named step lists run by core/run_subflow; Go to and loop exits are checked per step tree
        Set<String> subNames = new LinkedHashSet<>();
        if (flow.get("subflows") instanceof List<?> subs)
            for (int i = 0; i < subs.size(); i++) {
                Object n = subs.get(i) instanceof Map<?, ?> m ? m.get("name") : null;
                if (!(n instanceof String s) || s.isBlank()) p.add(null, "subflows/" + i + "/name", "Subflow " + (i + 1) + " has no name", "Give every subflow a name.");
                else if (!subNames.add(s)) p.add(null, "subflows/" + i + "/name", "Duplicate subflow name '" + s + "'", "Give every subflow a unique name.");
            }
        checkTree(p, (List<Object>) flow.getOrDefault("steps", List.of()), null);
        for (var sub : Steps.subflows(flow).entrySet()) checkTree(p, sub.getValue(), sub.getKey());
        checkTree(p, Steps.onErrorSteps(flow), null);

        Set<String> seen = new HashSet<>();
        for (Map<String, Object> step : Steps.walkFlow(flow)) {
            String sid = String.valueOf(step.get("id"));
            String act = String.valueOf(step.get("action"));
            if (!seen.add(sid)) p.add(sid, null, "Duplicate step id '" + sid + "'", "Step ids must be unique; One AgentStudio regenerates them on paste.");
            if (!ACTION.matcher(act).matches()) { p.add(sid, "action", "Invalid action address '" + act + "'", "Use package/action, e.g. ooa.excel/get_cell."); continue; }
            String pid = act.substring(0, act.indexOf('/')), aid = act.substring(act.indexOf('/') + 1);
            Map<String, Object> args = step.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

            Set<String> refs = Set.of();
            try {
                Set<String> r = new TreeSet<>(Expressions.references(normConditionArgs(args)));
                for (Object b : (List<Object>) step.getOrDefault("elseIf", List.of()))
                    r.addAll(Expressions.references(normCondition(((Map<String, Object>) b).get("condition"))));
                boolean byCondition = "expression".equals(args.get("match"));
                for (Object c : step.get("cases") instanceof List<?> cl ? cl : List.of())
                    if (c instanceof Map<?, ?> cm && cm.get("values") instanceof List<?> vs)
                        for (Object v : vs) r.addAll(Expressions.references(byCondition ? normCondition(v) : v));
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
            if ((step.containsKey("cases") || step.containsKey("default")) && !act.equals("core/switch")) p.add(sid, null, "cases/default are only allowed on core/switch", null);
            if (act.equals("core/switch")) checkSwitch(p, sid, step, args, requires, kind);
            if (act.equals("core/loop")) {
                Object it = args.get("iterator");
                if (it instanceof String s && !CorePackage.BUILTIN_ITERATORS.contains(s) && !s.contains("/"))
                    p.add(sid, "args/iterator", "Unknown iterator '" + s + "'", "Use one of " + new TreeSet<>(CorePackage.BUILTIN_ITERATORS) + " or package/iterator.");
                for (String k : List.of("as", "index"))
                    if (args.get(k) instanceof String v && !declared.containsKey(v)) p.add(sid, "args/" + k, "Loop variable '" + v + "' is not declared", null);
                if (args.get("columns_to") instanceof Map<?, ?> cols)
                    cols.forEach((col, var) -> {
                        String v = String.valueOf(var).trim(); if (v.startsWith("$")) v = v.substring(1);
                        if (!v.isEmpty() && !declared.containsKey(v)) p.add(sid, "args/columns_to/" + col, "Loop variable '" + v + "' is not declared", "Declare variable '" + v + "'.");
                    });
            }
            if (act.equals("core/run_bot") && args.get("output_map") instanceof Map<?, ?> om)
                om.forEach((o, var) -> {
                    if (var instanceof String v && !declared.containsKey(v) && !v.contains("$"))
                        p.add(sid, "args/output_map/" + o, "Output target '" + v + "' is not declared", "Declare variable '" + v + "'.");
                });
            if (act.equals("core/run_subflow") && args.get("subflow") instanceof String sf && !sf.contains("$") && !sf.isBlank() && !subNames.contains(sf.strip()))
                p.add(sid, "args/subflow", "There is no subflow named '" + sf + "'",
                        subNames.isEmpty() ? "Add the subflow to the agent, or remove the step." : "Subflows: " + String.join(", ", subNames) + ".");
            if (act.equals("core/set")) checkAssign(p, sid, "args/", args.get("variable"), args.get("value"), declared);
            if (act.equals("core/set_many") && args.get("assignments") instanceof List<?> rows) {
                if (rows.isEmpty()) p.add(sid, "args/assignments", "No assignments", "Add at least one variable and value.");
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i) instanceof Map<?, ?> row && row.get("variable") instanceof String v && !v.isBlank())
                        checkAssign(p, sid, "args/assignments/" + i + "/", v, row.get("value"), declared);
                    else p.add(sid, "args/assignments/" + i, "Assignment " + (i + 1) + " has no variable", "Give each row a declared variable and a value.");
                }
            }
            for (Object c : (List<Object>) step.getOrDefault("catch", List.of()))
                for (String k : List.of("as", "message_to", "line_to"))
                    if (((Map<String, Object>) c).get(k) instanceof String v && !declared.containsKey(v)) p.add(sid, "catch/" + k, "Catch variable '" + v + "' is not declared", null);

            // toolkit conditions (If, Else if, While, Wait until) and toolkit loop types are checked like actions
            if (pid.equals("core") && args.containsKey("condition")) checkCondition(p, sid, "args/condition", args.get("condition"), requires, kind, 0);
            for (int i = 0; step.get("elseIf") instanceof List<?> ei && i < ei.size(); i++)
                if (ei.get(i) instanceof Map<?, ?> br) checkCondition(p, sid, "elseIf/" + i + "/condition", br.get("condition"), requires, kind, 0);
            if (act.equals("core/loop") && args.get("iterator") instanceof String it && it.contains("/")) {
                ActionSpec ispec = toolkitSpec(p, sid, "args/iterator", it, "iterator", "loop type", requires);
                Object ia = args.get("iterator_args");
                if (ispec != null) {
                    if (ia != null && !(ia instanceof Map<?, ?>) && !isDynamic(ia))
                        p.add(sid, "args/iterator_args", "iterator_args must be a Dictionary of the loop type's fields", null);
                    else checkArgs(p, sid, "args/iterator_args/", it + " (loop type)", ispec, ia instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), false, isDynamic(ia));
                    if ("ApiTask".equals(kind) && ispec.capabilities().contains("desktop"))
                        p.add(sid, "args/iterator", "'" + it + "' needs a desktop and cannot run in an ApiTask", null);
                }
            } else if (act.equals("core/loop") && args.containsKey("iterator_args") && args.get("iterator") instanceof String it && !it.contains("/"))
                p.add(sid, "args/iterator_args", "iterator_args only applies to toolkit loop types, not '" + it + "'", "Remove iterator_args or choose a toolkit loop type.");

            // manifest checks
            PackageRegistry.LoadedPackage pkg;
            try { pkg = registry.resolve(pid, (String) requires.get(pid)); }
            catch (OoaException e) { p.add(sid, "action", e.getMessage(), e.suggestedFix()); continue; }
            ActionSpec spec = pkg.spec("action", aid);
            if (spec == null) { p.add(sid, "action", "Toolkit " + pid + "@" + pkg.version() + " has no action '" + aid + "'", "Pick the action from the command library."); continue; }
            if ("ApiTask".equals(kind) && spec.capabilities().contains("desktop"))
                p.add(sid, "action", "'" + act + "' needs a desktop and cannot run in an ApiTask", null);
            // a step with a saved connection gets its address and sign-in inputs from it at run time
            checkArgs(p, sid, "args/", act, spec, args, step.get("connection") instanceof String, false);
            Set<String> outputs = new HashSet<>();
            for (ActionSpec.OutputSpec o : spec.outputs()) outputs.add(o.name());
            for (String o : out.keySet()) if (!outputs.contains(o)) p.add(sid, "out/" + o, act + " has no output '" + o + "'",
                    "Outputs: " + (outputs.isEmpty() ? "none" : String.join(", ", new TreeSet<>(outputs))) + ".");
        }
        return problems;
    }

    /**
     * One step tree (the main steps, or a subflow's when `subflow` is not null): Go to targets a Label in its own list or a
     * list around it, never into a block, out of a Parallel branch or out of the tree; a label name is used once among the
     * labels a Go to can see. In a subflow, Exit loop and Continue loop need a loop of the subflow around them. Exit
     * subflow is only for subflows. (Loop exits in the main steps keep failing at run time, as before.)
     */
    private static void checkTree(P p, List<Object> steps, String subflow) {
        Set<String> all = new HashSet<>();
        for (Map<String, Object> s : Steps.walk(steps)) if ("core/label".equals(s.get("action")) && labelName(s) != null) all.add(labelName(s));
        checkList(p, steps, Set.of(), List.of(), subflow, all, true);
    }

    private static String labelName(Map<String, Object> step) {
        Object v = step.get("args") instanceof Map<?, ?> a ? a.get("core/goto".equals(step.get("action")) ? "label" : "name") : null;
        return v instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    @SuppressWarnings("unchecked")
    private static void checkList(P p, List<Object> list, Set<String> outer, List<String> loops, String subflow, Set<String> all, boolean ownsLabels) {
        Set<String> scope = new HashSet<>(outer);
        if (ownsLabels) {
            Set<String> own = new HashSet<>();
            for (Object o : list)
                if (o instanceof Map<?, ?> m && "core/label".equals(m.get("action"))) {
                    String n = labelName((Map<String, Object>) m), sid = String.valueOf(m.get("id"));
                    if (n == null) p.add(sid, "args/name", "The Label has no name", "Give the label a name for Go to steps.");
                    else if (!own.add(n) || outer.contains(n)) p.add(sid, "args/name", "Duplicate label '" + n + "'" + (outer.contains(n) ? " (a list around it has one too)" : ""),
                            "Give each label a name of its own, so Go to knows where to carry on.");
                }
            scope.addAll(own);
        }
        String where = subflow == null ? "" : " of subflow '" + subflow + "'";
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> s = (Map<String, Object>) m;
            String act = String.valueOf(s.get("action")), sid = String.valueOf(s.get("id"));
            Map<String, Object> args = s.get("args") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
            switch (act) {
                case "core/goto" -> {
                    String n = labelName(s);
                    if (n == null) p.add(sid, "args/label", "Go to has no label", "Choose the label to carry on from.");
                    else if (!scope.contains(n)) p.add(sid, "args/label", all.contains(n)
                            ? "Go to label '" + n + "' cannot be reached from here: it would jump into a block or out of a Parallel branch"
                            : "There is no label '" + n + "' in the steps" + where,
                            "Go to can jump to a label in its own step list or a list around it" + (subflow == null ? "" : ", in the same subflow") + ".");
                }
                case "core/exit_subflow" -> { if (subflow == null) p.add(sid, null, "Exit subflow is only for subflows", "Use Stop agent to end the main steps."); }
                case "core/break", "core/continue" -> {
                    if (subflow == null) break;
                    String lb = args.get("label") instanceof String l && !l.isBlank() && !l.contains("$") ? l.strip() : null;
                    if (loops.isEmpty() || (lb != null && !loops.contains(lb)))
                        p.add(sid, lb == null ? null : "args/label", (act.equals("core/break") ? "Exit loop" : "Continue loop") + " is not inside a loop" + (lb == null ? "" : " labelled '" + lb + "'") + where,
                                "A subflow can only leave or continue its own loops.");
                }
                default -> { }
            }
            if (act.equals("core/parallel")) {
                // every branch runs apart: no Go to or loop exit leaves it
                for (Object b : s.get("children") instanceof List<?> l ? (List<Object>) l : List.of()) checkList(p, List.of(b), Set.of(), List.of(), subflow, all, false);
                continue;
            }
            List<String> inner = loops;
            if (act.equals("core/loop")) { inner = new ArrayList<>(loops); inner.add(args.get("label") instanceof String l ? l.strip() : ""); }
            for (String k : List.of("children", "else", "finally", "default"))
                if (s.get(k) instanceof List<?> l) checkList(p, (List<Object>) l, scope, inner, subflow, all, true);
            for (String k : List.of("elseIf", "catch", "cases"))
                if (s.get(k) instanceof List<?> l) for (Object b : l)
                    if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) checkList(p, (List<Object>) c, scope, inner, subflow, all, true);
        }
    }

    /** Unknown, missing and literal values of an action, condition or loop type, against its manifest. */
    private static void checkArgs(P p, String sid, String prefix, String what, ActionSpec spec, Map<String, Object> args, boolean connection, boolean dynamicArgs) {
        for (String k : args.keySet())
            if (spec.input(k).isEmpty()) p.add(sid, prefix + k, "Unknown input '" + k + "' for " + what,
                    "Valid inputs: " + (spec.inputs().isEmpty() ? "none" : String.join(", ", spec.inputs().stream().map(ActionSpec.InputSpec::name).toList())) + ".");
        for (ActionSpec.InputSpec in : spec.inputs()) {
            if (in.required() && in.defaultValue() == null && !args.containsKey(in.name()) && !connection && !dynamicArgs)
                p.add(sid, prefix + in.name(), "Missing required input '" + in.name() + "' for " + what, in.description());
            if (args.containsKey(in.name())) checkLiteral(p, sid, prefix + in.name(), in, args.get(in.name()), !what.startsWith("core/"));
        }
    }

    /**
     * core/switch: at least one case, each with at least one value; a value to compare unless the cases are conditions;
     * literal regex patterns that compile; toolkit conditions in expression cases checked like If's.
     */
    @SuppressWarnings("unchecked")
    private void checkSwitch(P p, String sid, Map<String, Object> step, Map<String, Object> args, Map<String, Object> requires, Object kind) {
        Object match = args.getOrDefault("match", "equals");
        if (!"expression".equals(match) && !args.containsKey("value"))
            p.add(sid, "args/value", "Switch has no value to compare", "Give the value (for example $status), or set match to expression.");
        if (!(step.get("cases") instanceof List<?> cases) || cases.isEmpty()) {
            p.add(sid, "cases", "Switch needs at least one case", "Add a case with the values that select it.");
            return;
        }
        for (int i = 0; i < cases.size(); i++) {
            List<?> values = cases.get(i) instanceof Map<?, ?> c && c.get("values") instanceof List<?> vs ? vs : List.of();
            if (values.isEmpty()) p.add(sid, "cases/" + i + "/values", "Case " + (i + 1) + " has no values", "Give at least one value that selects this case.");
            for (int j = 0; j < values.size(); j++) {
                Object v = values.get(j);
                if ("expression".equals(match)) checkCondition(p, sid, "cases/" + i + "/values/" + j, v, requires, kind, 0);
                else if ("regex".equals(match) && v instanceof String s && isLiteralText(s)) {
                    try { Pattern.compile(s); }
                    catch (java.util.regex.PatternSyntaxException e) {
                        p.add(sid, "cases/" + i + "/values/" + j, "Case " + (i + 1) + ": '" + s + "' is not a valid pattern: " + e.getDescription(), "Fix the regular expression.");
                    }
                }
            }
        }
    }

    /** Text with no $reference or ${...} in it, so its value is known before the run. */
    private static boolean isLiteralText(String s) {
        try { return !s.contains("${") && Expressions.references(s).isEmpty(); } catch (OoaException e) { return false; }
    }

    /** The manifest of a toolkit condition or loop type named "package/id", or null after reporting why there is none. */
    private ActionSpec toolkitSpec(P p, String sid, String path, String addr, String kindOf, String label, Map<String, Object> requires) {
        if (!ACTION.matcher(addr).matches()) { p.add(sid, path, "Invalid " + label + " address '" + addr + "'", "Use package/" + kindOf + ", e.g. ooa.file/file_exists."); return null; }
        String pid = addr.substring(0, addr.indexOf('/')), id = addr.substring(addr.indexOf('/') + 1);
        PackageRegistry.LoadedPackage pkg;
        try { pkg = registry.resolve(pid, (String) requires.get(pid)); }
        catch (OoaException e) { p.add(sid, path, e.getMessage(), e.suggestedFix()); return null; }
        ActionSpec spec = pkg.spec(kindOf, id);
        if (spec == null) p.add(sid, path, "Toolkit " + pid + "@" + pkg.version() + " has no " + label + " '" + id + "'", "Pick the " + label + " from the command library.");
        return spec;
    }

    /** Walks all/any/not and wait wrappers down to toolkit conditions {"condition": "pkg/id", "args": {...}}. */
    @SuppressWarnings("unchecked")
    private void checkCondition(P p, String sid, String path, Object c, Map<String, Object> requires, Object kind, int depth) {
        if (!(c instanceof Map<?, ?> raw) || depth > 20) return;
        Map<String, Object> m = (Map<String, Object>) raw;
        for (String k : List.of("all", "any"))
            if (m.get(k) instanceof List<?> l) { for (int i = 0; i < l.size(); i++) checkCondition(p, sid, path + "/" + k + "/" + i, l.get(i), requires, kind, depth + 1); return; }
        if (m.containsKey("not")) { checkCondition(p, sid, path + "/not", m.get("not"), requires, kind, depth + 1); return; }
        if (!(m.get("condition") instanceof String addr)) return;
        ActionSpec spec = toolkitSpec(p, sid, path, addr, "condition", "condition", requires);
        if (spec == null) return;
        Object a = m.get("args");
        if (a != null && !(a instanceof Map<?, ?>) && !isDynamic(a)) { p.add(sid, path + "/args", "The fields of condition " + addr + " must be a Dictionary", null); return; }
        checkArgs(p, sid, path + "/args/", addr + " (condition)", spec, a instanceof Map<?, ?> am ? (Map<String, Object>) am : Map.of(), false, isDynamic(a));
        if ("ApiTask".equals(kind) && spec.capabilities().contains("desktop"))
            p.add(sid, path, "Condition '" + addr + "' needs a desktop and cannot run in an ApiTask", null);
    }

    /** A whole field list given as a variable or expression ("$opts", {"expr": ...}) can only be checked when it runs. */
    private static boolean isDynamic(Object v) {
        return v instanceof String || (v instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("expr"));
    }

    /**
     * The default of a Time or TimeSpan variable must read as one (14:30, 2h 15m); an Image or Binary default is a file
     * path (read when the run starts) or {"$binary": Base64}. Defaults of the other types are not checked here.
     */
    private static void checkDefault(P p, String name, OoaType t, Map<String, Object> v) {
        Object d = v.get("default");
        if (d == null) return;
        String path = "variables/" + name;
        try {
            switch (t) {
                case TIME -> Values.toTime(d);
                case TIMESPAN -> Values.toTimeSpan(d);
                case IMAGE, BINARY -> {
                    if (d instanceof Map<?, ?> m) {
                        if (!(m.get("$binary") instanceof String b64) || Values.base64(b64) == null)
                            p.add(null, path, "The default of " + t.flowName() + " variable '" + name + "' must be a file path or {\"$binary\": \"<Base64>\"}", null);
                    } else if (!(d instanceof String) && !(t == OoaType.BINARY && d instanceof List))
                        p.add(null, path, "The default of " + t.flowName() + " variable '" + name + "' must be a file path or {\"$binary\": \"<Base64>\"}, got " + Values.typeName(d), null);
                }
                default -> { }
            }
        } catch (OoaException e) {
            p.add(null, path, "The default of variable '" + name + "' is not a " + t.flowName() + ": " + e.getMessage(), e.suggestedFix());
        }
    }

    /** Literal (non-reference) argument values are checked against the manifest's type, enum and pattern. */
    private static void checkLiteral(P p, String sid, String path, ActionSpec.InputSpec in, Object v, boolean toolkit) {
        if (in.type() == OoaType.CREDENTIAL) {
            if (v instanceof Map<?, ?> m && m.containsKey("$credential")) {
                if (m.size() != 1 || !(m.get("$credential") instanceof String ref) || !ref.strip().matches("[^/\\s][^/]*/[^/]+/[^/]*[^/\\s]"))
                    p.add(sid, path, "Input '" + in.name() + "': the vault reference must be {\"$credential\": \"locker/credential/attribute\"}", in.description());
                return;
            }
            if ((v instanceof Map<?, ?> m2 && !(m2.size() == 1 && (m2.containsKey("expr") || m2.containsKey("literal")))) || v instanceof List<?>)
                p.add(sid, path, "Input '" + in.name() + "' needs a Credential (a vault reference or a Credential variable), got " + Values.typeName(v), in.description());
            return;
        }
        if (toolkit && containsVaultReference(v, 0))
            p.add(sid, path, "Input '" + in.name() + "' is not a Credential field: a vault reference here would be sent as plain text",
                    "Put the secret in the command's Credential field (for example password, bearer_token or access_token).");
        boolean dynamic;
        try { dynamic = !Expressions.references(v).isEmpty() || (v instanceof Map<?, ?> m && m.size() == 1 && (m.containsKey("expr") || m.containsKey("literal"))); }
        catch (OoaException e) { return; }
        if (dynamic || v == null) return;
        boolean ok = switch (in.type()) {
            case STRING, FILE -> v instanceof String;
            case NUMBER -> v instanceof BigDecimal;
            case BOOLEAN -> v instanceof Boolean;
            case LIST -> v instanceof List;
            case DICTIONARY, RECORD -> v instanceof Map;
            case TIME -> readsAs(() -> Values.toTime(v));
            case TIMESPAN -> readsAs(() -> Values.toTimeSpan(v));
            default -> true;
        };
        if (!ok) { p.add(sid, path, "Input '" + in.name() + "' expects " + in.type().flowName() + ", got " + Values.typeName(v), in.description()); return; }
        if (!in.enumValues().isEmpty() && !in.enumValues().contains(String.valueOf(v)))
            p.add(sid, path, "Input '" + in.name() + "': " + Values.safeRepr(v) + " is not one of " + in.enumValues(), in.description());
        if (!in.pattern().isEmpty() && v instanceof String s && !Pattern.compile(in.pattern()).matcher(s).find())
            p.add(sid, path, "Input '" + in.name() + "': '" + s + "' does not match " + in.pattern(), in.description());
        if (v instanceof BigDecimal n && (n.doubleValue() < in.min() || n.doubleValue() > in.max()))
            p.add(sid, path, "Input '" + in.name() + "': " + n.toPlainString() + " is outside the allowed range "
                    + (Double.isNaN(in.min()) ? "" : "from " + BigDecimal.valueOf(in.min()).stripTrailingZeros().toPlainString() + " ")
                    + (Double.isNaN(in.max()) ? "" : "to " + BigDecimal.valueOf(in.max()).stripTrailingZeros().toPlainString()), in.description());
    }

    private static boolean readsAs(Runnable read) {
        try { read.run(); return true; } catch (OoaException e) { return false; }
    }

    /** Assign variable, and each row of Assign several variables: a declared variable; a vault reference only into a Credential. */
    private static void checkAssign(P p, String sid, String prefix, Object variable, Object value, Map<String, Map<String, Object>> declared) {
        if (!(variable instanceof String v)) return;
        if (!declared.containsKey(v)) { p.add(sid, prefix + "variable", "Variable '" + v + "' is not declared", null); return; }
        if (containsVaultReference(value, 0)) {
            boolean cred = "Credential".equals(declared.get(v).get("type"));
            if (!cred || !(value instanceof Map<?, ?> vm && vm.size() == 1 && vm.get("$credential") instanceof String))
                p.add(sid, prefix + "value", cred ? "A Credential variable is set from one vault reference {\"$credential\": \"locker/credential/attribute\"}"
                        : "Only a Credential variable can hold a vault reference; '" + v + "' is " + declared.get(v).get("type"), "Make '" + v + "' a Credential variable.");
        }
    }

    private static boolean containsVaultReference(Object v, int depth) {
        if (depth > 6) return false;
        if (v instanceof Map<?, ?> m) { if (m.containsKey("$credential")) return true; for (Object x : m.values()) if (containsVaultReference(x, depth + 1)) return true; }
        if (v instanceof List<?> l) for (Object x : l) if (containsVaultReference(x, depth + 1)) return true;
        return false;
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
