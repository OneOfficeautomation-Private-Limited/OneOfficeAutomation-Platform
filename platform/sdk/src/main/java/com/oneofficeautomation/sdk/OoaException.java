package com.oneofficeautomation.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every OOA error: a stable code (matched by Try/Catch), a message, a suggested fix usable by humans
 * and the AI assistant (FR-AI-012), a retryable flag and the step where it happened.
 */
public class OoaException extends RuntimeException {
    private final String code;
    private final String suggestedFix;
    private final boolean retryable;
    private final Map<String, Object> details;
    private String stepId;

    public OoaException(String code, String message) { this(code, message, null, false, null); }

    public OoaException(String code, String message, String suggestedFix) { this(code, message, suggestedFix, false, null); }

    public OoaException(String code, String message, String suggestedFix, boolean retryable, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.suggestedFix = suggestedFix;
        this.retryable = retryable;
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    }

    public String code() { return code; }
    public String suggestedFix() { return suggestedFix; }
    public boolean retryable() { return retryable; }
    public Map<String, Object> details() { return details; }
    public String stepId() { return stepId; }

    public OoaException atStep(String stepId) {
        if (this.stepId == null) this.stepId = stepId;
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", getMessage());
        m.put("suggestedFix", suggestedFix);
        m.put("retryable", retryable);
        m.put("stepId", stepId);
        m.put("details", details);
        return m;
    }

    @Override public String toString() {
        return "[" + code + "] " + getMessage() + (suggestedFix != null ? " Fix: " + suggestedFix : "");
    }
}
