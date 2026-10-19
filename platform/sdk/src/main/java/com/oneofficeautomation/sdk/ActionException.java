package com.oneofficeautomation.sdk;

import java.util.Map;

/** Thrown by package actions. Packages choose their own codes, e.g. EXCEL.CELL_INVALID. */
public class ActionException extends OoaException {
    public ActionException(String code, String message) { super(code, message); }
    public ActionException(String code, String message, String suggestedFix) { super(code, message, suggestedFix); }
    public ActionException(String code, String message, String suggestedFix, boolean retryable) {
        super(code, message, suggestedFix, retryable, null);
    }
    public ActionException(String code, String message, String suggestedFix, boolean retryable, Map<String, Object> details) {
        super(code, message, suggestedFix, retryable, details);
    }
}
