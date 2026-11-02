package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Adds a condition usable in If and Wait. Method returns boolean. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Condition {
    String id();
    String name();
    String description();
    /** What the condition needs from the runner (desktop, network, filesystem, browser), as for actions; filesystem is added for File inputs. */
    String[] capabilities() default {};
}
