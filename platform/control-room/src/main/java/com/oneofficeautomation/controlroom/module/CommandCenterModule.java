package com.oneofficeautomation.controlroom.module;

import java.util.List;

/**
 * A feature module of One CommandCenter (docs/architecture/13-modules.md). The kernel finds modules on the class path
 * with {@link java.util.ServiceLoader}: a module lists its class in
 * {@code META-INF/services/com.oneofficeautomation.controlroom.module.CommandCenterModule}.
 *
 * <p>A module never names the kernel's internals, and the kernel never names a module's classes: everything a module
 * adds (routes, clock jobs, settings, task kinds, trigger kinds, AI assistant tools, template parts, its own tables) it
 * registers in {@link #start} through the {@link ModuleContext}, and everything it needs from the kernel it asks the
 * context for. Without the module on the class path the kernel runs as before, without that feature.
 */
public interface CommandCenterModule {
    /** A short, stable id such as {@code document-automation}: the module map's id, and its key in module_schema. */
    String id();

    /** Its name, as Settings > About lists it. */
    String name();

    /** Its own version (it changes when the module changes, not with every platform release). */
    String version();

    /** The ids of other modules it needs started first (rare: modules talk through kernel events, not to each other). */
    default List<String> dependsOn() { return List.of(); }

    /**
     * The module's own tables: step n (from 1) is the module's schema version n, a list of statements in the portable
     * SQL the kernel's schema uses (it is translated for SQL Server). Steps are only ever added at the end. `ooa db
     * migrate` applies the steps a database does not have yet, after the kernel's own schema; module_schema records
     * where each module is.
     */
    default List<List<String>> schema() { return List.of(); }

    /** Called once when One CommandCenter starts, after the kernel and the module's schema are ready. */
    void start(ModuleContext ctx);
}
