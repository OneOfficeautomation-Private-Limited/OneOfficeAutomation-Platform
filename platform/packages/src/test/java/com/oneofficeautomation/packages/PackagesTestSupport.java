package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct ActionContext for calling package methods in unit tests. */
final class PackagesTestSupport {
    private PackagesTestSupport() {}
    static ActionContext ctx(Path work, Map<String, Object> services) {
        return new ActionContext("run_test", "s1", "default", work, new HashMap<>(), new HashMap<>(services), new AtomicBoolean(), List::of, (l, m) -> {});
    }
}
