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
    /**
     * Names this input had before it was renamed (K8, command review batch 10): a step that still uses one runs as
     * before, the validator accepts it, and OneAgentStudio moves its value to the new name when the step is opened.
     */
    String[] formerNames() default {};
    /**
     * K1 (command review, batch 10): this credential input takes the token of a saved connection's renewing sign-in
     * (the runner fills it when the step leaves it empty). access_token and bearer_token are such inputs anyway.
     */
    boolean signInToken() default false;
    /**
     * The value OneAgentStudio writes into a step when it is added (a JSON literal), when it differs from
     * {@link #defaultValue()}. Steps saved before keep running with defaultValue, so changing to a safer default (for
     * example overwrite off, as AA has it) never changes what an existing agent does. Empty = the same as defaultValue.
     */
    String newStepDefault() default "";
    /**
     * How a ticked Yes/No option is coloured in OneAgentStudio: "danger" (it replaces, removes or deletes something),
     * "add" (it creates or adds something) or "plain". Empty = worked out from the input's name.
     */
    String tone() default "";
    /**
     * A Text input that may also take a vault reference ({"$credential": "locker/credential/attribute"}): the runner
     * reads the secret just before the step runs and masks it in logs (a host name, a user name, a connection string).
     */
    boolean acceptsCredential() default false;
}
