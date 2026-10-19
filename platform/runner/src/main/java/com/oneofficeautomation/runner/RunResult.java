package com.oneofficeautomation.runner;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of one run. status: success | failed | stopped. */
public record RunResult(String runId, String automationId, String status, Map<String, Object> outputs,
                        Map<String, Object> error, long durationMs, String planHash, int stepsExecuted) {
    public boolean ok() { return "success".equals(status); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId); m.put("automationId", automationId); m.put("status", status);
        m.put("outputs", Values.toJsonable(outputs)); m.put("error", Values.toJsonable(error));
        m.put("durationMs", durationMs); m.put("planHash", planHash); m.put("stepsExecuted", stepsExecuted);
        return m;
    }
}
