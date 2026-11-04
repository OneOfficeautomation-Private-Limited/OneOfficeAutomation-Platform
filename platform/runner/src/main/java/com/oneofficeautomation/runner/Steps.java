package com.oneofficeautomation.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Walks the step tree: children, else, elseIf branches, catch blocks, finally, switch cases and default (with walkFlow,
 * the subflows and the error handler too). */
public final class Steps {
    private Steps() {}

    /** The agent's error handler (flow "onError"), or null when it has none. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> onError(Map<String, Object> flow) {
        return flow != null && flow.get("onError") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** The steps of the agent's error handler; empty when it has none. */
    @SuppressWarnings("unchecked")
    public static List<Object> onErrorSteps(Map<String, Object> flow) {
        Map<String, Object> h = onError(flow);
        return h != null && h.get("steps") instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** Every step of the agent: the main steps, then each subflow's, then the error handler's (the order lines are numbered in). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> walkFlow(Map<String, Object> flow) {
        List<Map<String, Object>> out = walk(flow.get("steps") instanceof List<?> l ? (List<Object>) l : List.of());
        if (flow.get("subflows") instanceof List<?> subs)
            for (Object o : subs) if (o instanceof Map<?, ?> m && m.get("steps") instanceof List<?> st) out.addAll(walk((List<Object>) st));
        out.addAll(walk(onErrorSteps(flow)));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> walk(List<Object> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : steps == null ? List.of() : steps) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) o;
            out.add(s);
            // in the order One AgentStudio shows them, so a step's place in this list is its line number:
            // children, else-if branches, else, catch blocks, finally, switch cases, default
            if (s.get("children") instanceof List<?> l) out.addAll(walk((List<Object>) l));
            if (s.get("elseIf") instanceof List<?> l)
                for (Object b : l) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) out.addAll(walk((List<Object>) c));
            if (s.get("else") instanceof List<?> l) out.addAll(walk((List<Object>) l));
            if (s.get("catch") instanceof List<?> l)
                for (Object b : l) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) out.addAll(walk((List<Object>) c));
            if (s.get("finally") instanceof List<?> l) out.addAll(walk((List<Object>) l));
            if (s.get("cases") instanceof List<?> l)
                for (Object b : l) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) out.addAll(walk((List<Object>) c));
            if (s.get("default") instanceof List<?> l) out.addAll(walk((List<Object>) l));
        }
        return out;
    }


    /** The flow's subflows by name, in order: name to its step list (entries without a name, and repeated names, are left out). */
    @SuppressWarnings("unchecked")
    public static Map<String, List<Object>> subflows(Map<String, Object> flow) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        if (flow != null && flow.get("subflows") instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String n)
                    out.putIfAbsent(n, m.get("steps") instanceof List<?> st ? (List<Object>) st : List.of());
        return out;
    }
}
