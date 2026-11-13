package com.oneofficeautomation.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to do about a command's error when the command gave no fix of its own (error batch E3, 13 Nov 2026): every
 * error a step raises says what happened and what to do. The fix is chosen by the last part of the code
 * (EXCEL.BAD_OPTION -> BAD_OPTION), then by its start (BAD_..., NO_...), so a toolkit's own codes are covered too.
 * A command's own fix always wins; these are the fallback.
 */
public final class CommandFixes {
    private CommandFixes() {}

    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    static {
        EXACT.put("CANCELLED", "The run was stopped before this step finished. Run the agent again when it should go on.");
        EXACT.put("INTERRUPTED", "The run was stopped before this step finished. Run the agent again when it should go on.");
        EXACT.put("IO_ERROR", "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.");
        EXACT.put("IO", "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.");
        EXACT.put("FILE_ERROR", "Check that the file exists, is not open in another program, and that the runner's account may read it.");
        EXACT.put("READ_FAILED", "Check that the file exists, is not open in another program, and that the runner's account may read it.");
        EXACT.put("UNREADABLE", "Check that the file exists, is not damaged and is of the kind the step expects.");
        EXACT.put("NOT_FOUND", "Check the name, path or id in the step; it may have been moved, renamed or deleted.");
        EXACT.put("WINDOWS_ONLY", "Run this agent on a Windows runner (choose a Windows runner or pool in the schedule or the Run dialog).");
        EXACT.put("NOT_INSTALLED", "Install the program on the runner's computer, or run the agent on a runner that has it.");
        EXACT.put("TOO_LARGE", "Split the work into smaller parts (fewer rows, files or items per step).");
        EXACT.put("EMPTY", "Check that the step gets data: the file, table or list it reads is empty.");
        EXACT.put("TYPE_MISMATCH", "Check the value's type: convert it first (for example with a String or Number step) or use a variable of the right type.");
        EXACT.put("VALUE_REQUIRED", "Fill in the field the message names in the step's settings.");
        EXACT.put("TIMEOUT", "Raise the step's time limit, or check that the system it waits for is answering.");
        EXACT.put("SCREEN", "Make sure the runner has an unlocked desktop session (not a locked screen or a closed remote session).");
        EXACT.put("DIALOG_FAILED", "Make sure the runner has an unlocked desktop session where the dialog can open.");
        EXACT.put("RENDER_FAILED", "Check that the document or template is valid and not damaged.");
        EXACT.put("MERGE_FAILED", "Check that every file to merge exists and is a valid file of the same kind.");
        EXACT.put("TEXT_FAILED", "Check that the document has text that can be read (a scanned image needs OCR first).");
        EXACT.put("FETCH", "Check the web address and that the runner can reach it (network, proxy).");
        EXACT.put("ERROR", "See the message for the cause; the run log has the details of the step.");
    }

    /** The fix for a code that came without one; never null. */
    public static String forCode(String code) {
        String last = code == null ? "" : code.substring(code.lastIndexOf('.') + 1);
        String exact = EXACT.get(last);
        if (exact != null) return exact;
        if (last.startsWith("BAD_") || last.equals("INVALID")) return "Check the value the message names in the step's settings and correct it.";
        if (last.startsWith("NO_")) return "Fill in what the message names in the step's settings.";
        if (last.endsWith("_FAILED")) return "See the message for the cause; check the step's settings and that the system it uses is available, then run it again.";
        return "Check the step's settings against the message; the run log has the details of the step.";
    }

    /** The command's own fix when it has one, else the fallback for its code. */
    static String orDefault(String code, String fix) {
        return fix == null || fix.isBlank() ? forCode(code) : fix;
    }
}
