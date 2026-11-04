package com.oneofficeautomation.runner;

import java.util.Map;

/**
 * Read-only view of the running bot's own variables, given to actions as the platform service {@code "flow_variables"}
 * (for example Log variables with "all variables of a type"). Built-in $sys and $global values are not included.
 */
public interface FlowVariables {
    /** Service name under which the runner offers the view. */
    String SERVICE = "flow_variables";

    /** Declared variables in declaration order: name to type (String, Number, Boolean, DateTime, List, Dictionary, Table, Record, File, Window, UIElement, Credential or Any). */
    Map<String, String> declared();

    /** Current value of a declared variable (null when unset). */
    Object value(String name);
}
