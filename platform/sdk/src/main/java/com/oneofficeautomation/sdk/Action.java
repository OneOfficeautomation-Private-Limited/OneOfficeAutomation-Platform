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
}
