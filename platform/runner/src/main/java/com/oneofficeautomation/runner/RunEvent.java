package com.oneofficeautomation.runner;

import java.util.Map;

/** Structured run event streamed to the Agent and on to Control Center (live runs, logs, Insights). */
public record RunEvent(String runId, long seq, String type, double ts, String stepId, Map<String, Object> data) {
    public Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("runId", runId); m.put("seq", seq); m.put("type", type); m.put("ts", ts); m.put("stepId", stepId);
        m.put("data", Values.toJsonable(data));
        return m;
    }
}
