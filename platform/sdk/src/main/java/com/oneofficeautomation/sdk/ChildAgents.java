package com.oneofficeautomation.sdk;

import java.util.Map;

/**
 * Lets a package action start a published child agent in the current run, for example an interrupt handler that
 * runs a recovery agent when an unexpected window appears. The Runner places one in the run's sessions under
 * {@link #SESSION}; it is absent when the run has no agent repository.
 */
@FunctionalInterface
public interface ChildAgents {
    String SESSION = "ooa.child_agents";

    /** Runs the production version of the agent with these inputs; returns its outputs or throws its error. */
    Map<String, Object> run(String automation, Map<String, Object> inputs);
}
