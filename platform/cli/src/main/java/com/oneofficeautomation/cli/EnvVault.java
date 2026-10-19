package com.oneofficeautomation.cli;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.SecureString;

import java.util.Locale;

/**
 * Development vault: reference locker/credential/attribute reads the environment variable
 * OOA_SECRET_LOCKER_CREDENTIAL_ATTRIBUTE. Production uses the Control Center vault (OpenBao or external).
 */
final class EnvVault implements CredentialVault {
    @Override public SecureString getSecret(String ref, String principal) {
        String key = "OOA_SECRET_" + ref.replace('/', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        String v = System.getenv(key);
        if (v == null) throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found",
                "For local testing set the environment variable " + key + " before starting Agent Studio.");
        return new SecureString(v, ref);
    }
}
