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
    /**
     * When the field applies, as {@code other=value1|value2}; several conditions joined with {@code ;} must all hold
     * ({@code !=} negates one). Studio hides the field otherwise, as other tools disable options that do not apply.
     * Example: {@code "iterator=times"} or {@code "mode=regex;ignore_case=true"}.
     */
    String visibleWhen() default "";
    /** Smallest allowed number (inclusive); NaN = no limit. Checked by the validator and shown by Studio. */
    double min() default Double.NaN;
    /** Largest allowed number (inclusive); NaN = no limit. */
    double max() default Double.NaN;
}
