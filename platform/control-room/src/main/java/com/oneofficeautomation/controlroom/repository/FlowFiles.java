package com.oneofficeautomation.controlroom.repository;

/**
 * The kinds of file in the agent repository, by name: agents (.flow.json), workflows (.process.json, run by the
 * orchestration module) and forms (.form.json, a workflow's human tasks). All are checked in and versioned alike.
 */
public final class FlowFiles {
    private FlowFiles() { }

    public static boolean isWorkflow(String path) { return path != null && path.endsWith(".process.json"); }
    public static boolean isForm(String path) { return path != null && path.endsWith(".form.json"); }
    /** A workflow or a form: no toolkits, its own rules at check-in. */
    public static boolean isDefinition(String path) { return isWorkflow(path) || isForm(path); }
}
