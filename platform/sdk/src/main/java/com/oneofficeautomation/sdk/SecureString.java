package com.oneofficeautomation.sdk;

import java.util.Objects;

/**
 * A secret value (Credential type). toString() is always "****" so a secret can never reach a log,
 * event, screenshot caption or export by accident (NFR-007). Packages call {@link #reveal()} only at
 * the moment a secret is handed to the target system.
 */
public final class SecureString {
    public static final String MASK = "****";
    private final char[] value;
    private final String ref;
    /** The vault allows this value in password fields only; typing actions check the target before revealing it. */
    private final boolean passwordOnly;

    public SecureString(String value, String ref) { this(value, ref, false); }

    public SecureString(String value, String ref, boolean passwordOnly) {
        this.value = Objects.requireNonNull(value).toCharArray();
        this.ref = ref;
        this.passwordOnly = passwordOnly;
    }

    public String reveal() { return new String(value); }

    public String ref() { return ref; }

    public boolean passwordOnly() { return passwordOnly; }

    /** Refuses a password-only value for a target that is not a password field. */
    public static void checkPasswordField(Object value, boolean targetIsPassword, String target) {
        if (value instanceof SecureString s && s.passwordOnly && !targetIsPassword)
            throw new OoaException("VAULT.PASSWORD_ONLY", "The credential " + s.ref + " may only be typed into password fields, and " + target + " is not one",
                    "Point the action at the password field, or ask the credential owner to clear Password fields only.");
    }

    /** Overwrite the secret in memory at the end of a run. */
    public void wipe() { java.util.Arrays.fill(value, '\0'); }

    @Override public String toString() { return MASK; }

    @Override public boolean equals(Object o) {
        return o instanceof SecureString s && java.util.Arrays.equals(s.value, value);
    }

    @Override public int hashCode() { return Objects.hashCode(ref); }
}
