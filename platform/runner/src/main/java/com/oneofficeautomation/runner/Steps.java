package com.oneofficeautomation.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Walks the step tree: children, else, elseIf branches, catch blocks and finally. */
public final class Steps {
    private Steps() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> walk(List<Object> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : steps == null ? List.of() : steps) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) o;
            out.add(s);
            for (String k : List.of("children", "else", "finally"))
                if (s.get(k) instanceof List<?> l) out.addAll(walk((List<Object>) l));
            for (String k : List.of("elseIf", "catch"))
                if (s.get(k) instanceof List<?> l)
                    for (Object b : l) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) out.addAll(walk((List<Object>) c));
        }
        return out;
    }
}
