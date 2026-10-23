package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks a model connection with one very small request ("Answer with OK"), for the Command Center's Test connection
 * button and Studio. The key is used for this request only and never written anywhere.
 */
public final class ModelProbe {
    private ModelProbe() {}

    /** {ok, ms, answer} or {ok: false, code, message}. */
    public static Map<String, Object> test(String provider, String model, String baseUrl, String key) {
        Path dir = null;
        long t0 = System.currentTimeMillis();
        try {
            dir = Files.createTempDirectory("ooa-probe");
            SecureString secret = key == null || key.isBlank() ? null : new SecureString(key, "probe");
            ActionContext ctx = new ActionContext("probe", "probe", "default", dir, new HashMap<>(), new HashMap<>(), new AtomicBoolean(),
                    () -> secret == null ? List.of() : List.of(key), (level, message) -> { });
            LlmClient.Config cfg = GenAiPackage.cfg(provider, model, secret, baseUrl, 0, 16, false, null, null)
                    .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, 30));
            LlmClient.Reply r = LlmClient.chat(ctx, cfg, List.of(Map.of("role", "user", "content", "Answer with the single word OK.")), null, false, "connection_test");
            String a = r.text() == null ? "" : r.text().strip();
            return Map.of("ok", true, "ms", System.currentTimeMillis() - t0, "answer", a.length() > 40 ? a.substring(0, 40) : a);
        } catch (OoaException e) {
            String msg = e.getMessage();
            if (key != null && !key.isBlank() && msg != null) msg = msg.replace(key, SecureString.MASK);
            return Map.of("ok", false, "code", e.code(), "message", msg == null ? e.code() : msg);
        } catch (Exception e) {
            return Map.of("ok", false, "code", "AI.TEST_FAILED", "message", String.valueOf(e.getMessage()));
        } finally {
            if (dir != null) try (var s = Files.walk(dir)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignored) { }
        }
    }
}
