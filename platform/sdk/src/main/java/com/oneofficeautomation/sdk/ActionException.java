package com.oneofficeautomation.sdk;

import java.util.Map;

/**
 * Thrown by package actions. Packages choose their own codes, e.g. EXCEL.CELL_INVALID. Every one says what to do: an
 * action that gives no fix gets the one for its code (CommandFixes, error batch E3).
 */
public class ActionException extends OoaException {
    public ActionException(String code, String message) { super(code, message, CommandFixes.forCode(code)); }
    public ActionException(String code, String message, String suggestedFix) { super(code, message, CommandFixes.orDefault(code, suggestedFix)); }
    public ActionException(String code, String message, String suggestedFix, boolean retryable) {
        super(code, message, CommandFixes.orDefault(code, suggestedFix), retryable, null);
    }
    public ActionException(String code, String message, String suggestedFix, boolean retryable, Map<String, Object> details) {
        super(code, message, CommandFixes.orDefault(code, suggestedFix), retryable, details);
    }
}
