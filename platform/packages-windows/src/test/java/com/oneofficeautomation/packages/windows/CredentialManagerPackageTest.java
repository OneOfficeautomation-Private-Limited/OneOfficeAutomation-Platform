package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real Credential Manager round trip under the test user's account; every credential it creates is removed again. */
class CredentialManagerPackageTest {
    final List<String> logs = new ArrayList<>();
    final ActionContext ctx = new ActionContext("run_test", "s1", "default", Path.of("."), new HashMap<>(), new HashMap<>(),
            new AtomicBoolean(), List::of, (l, m) -> logs.add(m));
    final CredentialManagerPackage cm = new CredentialManagerPackage();

    @EnabledOnOs(OS.WINDOWS)
    @Test void writeReadListAndDeleteGenericCredential() {
        String prefix = "OOA-test-" + UUID.randomUUID().toString().substring(0, 8) + "/";
        String target = prefix + "service";
        String secret = "p@ss w0rd-é";
        try {
            assertFalse(cm.credentialExists(ctx, target));
            cm.writeCredential(ctx, target, new SecureString(secret, "t"), "svc-user", "made by a test", "local_machine", "utf16");
            assertTrue(cm.credentialExists(ctx, target));

            Map<String, Object> r = cm.readCredential(ctx, target, "auto");
            assertEquals("svc-user", r.get("username"));
            assertEquals("made by a test", r.get("comment"));
            assertInstanceOf(SecureString.class, r.get("password"));
            assertEquals(secret, ((SecureString) r.get("password")).reveal());
            assertEquals(SecureString.MASK, String.valueOf(r.get("password")), "the secret prints as ****");
            assertInstanceOf(LocalDateTime.class, r.get("last_written"));

            cm.writeCredential(ctx, prefix + "utf8", new SecureString("abc", "t"), null, null, "session", "utf8");
            assertEquals("abc", ((SecureString) cm.readCredential(ctx, prefix + "utf8", "utf8").get("password")).reveal());
            assertEquals("abc", ((SecureString) cm.readCredential(ctx, prefix + "utf8", "auto").get("password")).reveal(), "auto falls back to UTF-8");

            Map<String, Object> l = cm.listTargets(ctx, prefix + "*");
            assertEquals(List.of(target, prefix + "utf8"), l.get("targets"));
            List<?> rows = (List<?>) ((Map<?, ?>) l.get("credentials")).get("rows");
            assertEquals("svc-user", ((List<?>) rows.get(0)).get(1));
            assertEquals("session", ((List<?>) rows.get(1)).get(3));
            assertTrue(((List<?>) cm.listTargets(ctx, null).get("targets")).contains(target), "no filter lists everything");
            assertEquals(List.of(), cm.listTargets(ctx, "OOA-test-none-" + UUID.randomUUID() + "*").get("targets"));

            assertEquals(true, cm.deleteCredential(ctx, target, false).get("deleted"));
            assertEquals(false, cm.deleteCredential(ctx, target, true).get("deleted"));
            var gone = assertThrows(ActionException.class, () -> cm.deleteCredential(ctx, target, false));
            assertEquals("CREDMAN.NOT_FOUND", gone.code());
            var missing = assertThrows(ActionException.class, () -> cm.readCredential(ctx, target, "auto"));
            assertEquals("CREDMAN.NOT_FOUND", missing.code());
            assertTrue(logs.stream().noneMatch(m -> m.contains(secret)), "secret never logged: " + logs);
        } finally {
            cm.deleteCredential(ctx, target, true);
            cm.deleteCredential(ctx, prefix + "utf8", true);
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test void inputErrors() {
        assertEquals("CREDMAN.NO_TARGET", assertThrows(ActionException.class, () -> cm.readCredential(ctx, " ", "auto")).code());
        assertEquals("CREDMAN.NO_SECRET", assertThrows(ActionException.class,
                () -> cm.writeCredential(ctx, "OOA-test-x", "plain text", null, null, "local_machine", "utf16")).code());
        assertEquals("CREDMAN.BAD_PERSIST", assertThrows(ActionException.class,
                () -> cm.writeCredential(ctx, "OOA-test-x", new SecureString("x", "t"), null, null, "forever", "utf16")).code());
        assertEquals("CREDMAN.BAD_FILTER", assertThrows(ActionException.class, () -> cm.listTargets(ctx, "a*b")).code());
        assertEquals("CREDMAN.TOO_LONG", assertThrows(ActionException.class,
                () -> cm.writeCredential(ctx, "OOA-test-x", new SecureString("x".repeat(3000), "t"), null, null, "session", "utf8")).code());
    }

    @Test void blobDecoding() {
        assertEquals("héllo", CredentialManagerPackage.decode("héllo".getBytes(StandardCharsets.UTF_16LE), "auto"));
        assertEquals("héllo", CredentialManagerPackage.decode("héllo".getBytes(StandardCharsets.UTF_8), "auto"));
        assertEquals("abcd", CredentialManagerPackage.decode("abcd".getBytes(StandardCharsets.UTF_8), "auto"));
        assertEquals("", CredentialManagerPackage.decode(new byte[0], "auto"));
        assertEquals("CREDMAN.BAD_ENCODING", assertThrows(ActionException.class, () -> CredentialManagerPackage.decode(new byte[2], "latin1")).code());
    }

    @EnabledOnOs(value = {OS.LINUX, OS.MAC})
    @Test void otherSystemsGetAClearError() {
        assertEquals("SYSTEM.WINDOWS_ONLY", assertThrows(ActionException.class, () -> cm.readCredential(ctx, "x", "auto")).code());
    }
}
