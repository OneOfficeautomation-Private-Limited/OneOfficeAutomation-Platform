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

    public SecureString(String value, String ref) {
        this.value = Objects.requireNonNull(value).toCharArray();
        this.ref = ref;
    }

    public String reveal() { return new String(value); }

    public String ref() { return ref; }

    /** Overwrite the secret in memory at the end of a run. */
    public void wipe() { java.util.Arrays.fill(value, '\0'); }

    @Override public String toString() { return MASK; }

    @Override public boolean equals(Object o) {
        return o instanceof SecureString s && java.util.Arrays.equals(s.value, value);
    }

    @Override public int hashCode() { return Objects.hashCode(ref); }
}
