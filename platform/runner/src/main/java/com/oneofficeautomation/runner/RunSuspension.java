package com.oneofficeautomation.runner;

import java.util.Map;
import java.util.Set;

/**
 * Suspend and resume (gap G36, long-running agents), offered to actions as the platform service {@code "run_suspension"}.
 * A run suspends by ending with status "suspended" (its action throws {@link #CODE}) after One CommandCenter saved its
 * variable values and the step it stopped at; when the wait is over, a new run of the same agent version starts AT that
 * step (Run from here) with the saved values, and the step's action sees {@link #resumed(String)} and returns its answer
 * instead of suspending again.
 * <p>Where a run can stop and come back correctly: in the agent's main steps (not a child agent, a subflow or the error
 * handler), inside If, Switch, Step group and State machine blocks, and in the Try part of a Try (its Catch and Finally
 * stay in force after the resume, and do not run at suspension). Not inside a Loop (Run from here runs one pass only),
 * a Parallel block, a Catch or a Finally.
 */
public interface RunSuspension {
    /** Service name under which the runner offers it. */
    String SERVICE = "run_suspension";
    /** The error code a suspending action throws; the run ends with status "suspended" (no Catch, retry or error handler takes it). */
    String CODE = "RUN.SUSPENDED";
    /** Variable types whose values cannot be carried to another run (window and element handles, pictures, files' bytes, secrets). */
    Set<String> NOT_CARRIED = Set.of("Credential", "Window", "UIElement", "Image", "Binary");
    /** Blocks a run may be suspended inside (the Try part of core/try is checked on its own). */
    Set<String> RESUMABLE_BLOCKS = Set.of("core/if", "core/switch", "core/step", "core/state_machine", "core/try");

    /**
     * Checks the run can be suspended at this step and gives what to save: {values: name -> plain JSON value,
     * left_out: [names not carried]}. Throws LONGRUN.CANNOT_SUSPEND (with the reason) where it cannot resume correctly,
     * and LONGRUN.SESSION_OPEN when a browser, workbook, file or other session is open.
     */
    Map<String, Object> prepare(String stepId);

    /** For the step a resumed run started at: the answer of the wait, once (later calls get null). Null otherwise. */
    Map<String, Object> resumed(String stepId);
}
