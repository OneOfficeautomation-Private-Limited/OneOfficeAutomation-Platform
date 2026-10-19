package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Adds a condition usable in If and Wait. Method returns boolean. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Condition {
    String id();
    String name();
    String description();
}
