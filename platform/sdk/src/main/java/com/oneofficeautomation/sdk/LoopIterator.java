package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Adds an iterator usable in Loop. Method returns an Iterable. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface LoopIterator {
    String id();
    String name();
    String description();
    /** What the loop type needs from the runner (desktop, network, filesystem, browser), as for actions; filesystem is added for File inputs. */
    String[] capabilities() default {};
}
