package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Describes one action output. Repeat for several outputs. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @Repeatable(Outputs.class)
public @interface Output {
    String name();
    OoaType type() default OoaType.ANY;
    String description() default "";
}
