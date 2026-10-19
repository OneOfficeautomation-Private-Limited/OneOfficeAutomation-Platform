package com.oneofficeautomation.packages;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds action results. Unlike Map.of, values may be null (an empty output is a valid result). */
final class Out {
    private Out() {}

    static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
