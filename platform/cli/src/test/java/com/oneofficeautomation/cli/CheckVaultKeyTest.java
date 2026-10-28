package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ooa check: a vault key from a key store (OOA_MASTER_KEY_SOURCE) counts like one from OOA_MASTER_KEY. */
class CheckVaultKeyTest {
    @Test void keySourcesAreRecognised() {
        assertEquals("PASS", CheckCommand.vaultKey("a2V5", null).level());
        CheckCommand.Result store = CheckCommand.vaultKey(null, "azure-keyvault:https://ooa.vault.azure.net/secrets/master");
        assertEquals("PASS", store.level());
        assertTrue(store.detail().contains("Azure Key Vault"), store.detail());
        assertEquals("PASS", CheckCommand.vaultKey(null, "prompt").level());
        CheckCommand.Result file = CheckCommand.vaultKey(null, null);
        assertEquals("WARN", file.level());
        assertTrue(file.detail().contains("OOA_MASTER_KEY_SOURCE"));
    }
}
