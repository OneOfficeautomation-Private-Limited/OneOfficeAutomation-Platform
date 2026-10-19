package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Marks a class as an OOA command package (architecture section 5). */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface OoaPackage {
    String id();
    String name();
    String version();
    String description();
    String publisher() default "oneofficeautomation";
}
