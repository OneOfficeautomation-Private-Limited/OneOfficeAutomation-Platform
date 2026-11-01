package com.oneofficeautomation.runner;

import java.util.regex.Pattern;

/**
 * Where an agent can run (AA's Platform column, 1 Nov 2026): Windows when one of its steps uses a desktop or Windows
 * toolkit (or VBScript), otherwise any runner. Read from the agent's JSON text, so a list of many agents stays quick.
 */
public final class Platforms {
    private static final Pattern WINDOWS = Pattern.compile(
            "\"action\"\\s*:\\s*\"(ooa\\.(desktop|desktop_ui|window|dll|java_apps|credential_manager|application|excel_desktop)/|ooa\\.script/run_vbscript\")");

    private Platforms() { }

    /** "Windows" or "Any". */
    public static String of(String flowJson) { return flowJson != null && WINDOWS.matcher(flowJson).find() ? "Windows" : "Any"; }
}
