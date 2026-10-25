package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/**
 * A command. The method signature is {@code Map<String,Object> m(ActionContext ctx, @Input ... args)};
 * return a map of output name to value (or null when there are no outputs).
 */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Action {
    String id();
    String name();
    String description();
    String category() default "General";
    double timeoutSeconds() default 60;
    boolean idempotent() default false;
    /** Device capabilities needed: desktop, browser, network, filesystem, ai. */
    String[] capabilities() default {};
    /**
     * How Studio lays out the command panel, naming every input once: {@code "a, b, [Has a password: p, q], c | d, e"}.
     * Inputs before {@code |} are shown; those after it go under More options. {@code [Label: x, y]} is a tick box
     * that reveals x and y. {@code (Label: By name = x / By index = y, z)} is a choice that shows only the chosen
     * option's fields (an option's label defaults to its first field's label). Empty = inputs in declaration order,
     * the first few shown.
     */
    String panel() default "";
}
