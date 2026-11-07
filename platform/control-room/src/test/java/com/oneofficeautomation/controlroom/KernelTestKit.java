package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;

import java.util.Map;

/**
 * Helpers the kernel's tests and the feature modules' tests share (the modules' build scripts put the kernel's test
 * classes on their test class path): small agent definitions and the like. Sign-in helpers are in RepositoryLifecycleTest.
 */
public final class KernelTestKit {
    private KernelTestKit() {}

    /** A one-step agent (a comment) with this id. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> flow(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/comment\",\"args\":{\"text\":\"x\"}}]}");
    }
}
