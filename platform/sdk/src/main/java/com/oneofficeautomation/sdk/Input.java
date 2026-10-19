package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

/** Describes one action input. Studio renders the command panel field from this. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
public @interface Input {
    String name();
    OoaType type() default OoaType.ANY;
    String description();
    String label() default "";
    boolean required() default true;
    /** Default as a JSON literal, e.g. "true", "60", "\"excel\"". Empty = no default. */
    String defaultValue() default "";
    String pattern() default "";
    String[] enumValues() default {};
    String example() default "";
}
