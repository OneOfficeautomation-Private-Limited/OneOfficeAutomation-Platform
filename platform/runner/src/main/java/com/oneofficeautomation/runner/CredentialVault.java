package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.SecureString;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Credential vault adapter. References look like "locker/credential/attribute" and resolve just-in-time
 * on the Agent. Adapters: built-in (OpenBao), CyberArk, HashiCorp Vault, Azure Key Vault, AWS Secrets Manager.
 */
public interface CredentialVault {
    SecureString getSecret(String ref, String principal);

    /** In-memory vault for tests and development, with consumer lists per locker. */
    final class InMemory implements CredentialVault {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> consumers = new ConcurrentHashMap<>();

        public InMemory put(String locker, String credential, String attribute, String value) {
            values.put(locker + "/" + credential + "/" + attribute, value);
            return this;
        }

        public InMemory consumers(String locker, Set<String> users) { consumers.put(locker, Set.copyOf(users)); return this; }

        @Override public SecureString getSecret(String ref, String principal) {
            String[] parts = ref.split("/");
            if (parts.length != 3) throw new OoaException("VAULT.BAD_REF", "Bad credential reference '" + ref + "'", "Use locker/credential/attribute.");
            Set<String> allowed = consumers.get(parts[0]);
            if (allowed != null && !allowed.contains(principal))
                throw new OoaException("VAULT.DENIED", "'" + principal + "' is not a consumer of vault folder '" + parts[0] + "'",
                        "Ask the vault folder owner to add you or your role as a consumer.");
            String v = values.get(ref);
            if (v == null) throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found");
            return new SecureString(v, ref);
        }
    }
}
