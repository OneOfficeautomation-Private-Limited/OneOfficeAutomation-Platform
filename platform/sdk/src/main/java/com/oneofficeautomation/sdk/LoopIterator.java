package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Adds an iterator usable in Loop. Method returns an Iterable. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface LoopIterator {
    String id();
    String name();
    String description();
}
