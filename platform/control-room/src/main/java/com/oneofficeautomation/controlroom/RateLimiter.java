package com.oneofficeautomation.controlroom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-address request limits for the routes anyone can call without a session: sign-in and tokens (60 a minute),
 * runner registration (60), and webhooks, Agent APIs and MCP (1,200). A fixed one-minute window per address and group.
 */
final class RateLimiter {
    private record Window(long start, int count) { }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    RateLimiter() { this(System::currentTimeMillis); }
    RateLimiter(java.util.function.LongSupplier clock) { this.clock = clock; }

    /** The limit group of a route, or null when the route is not limited (signed-in calls). */
    static String group(String method, String route) {
        if (!method.equals("POST") && !route.startsWith("auth/sso") && !route.startsWith("auth/saml")) return null;
        if (route.equals("auth/login") || route.equals("auth/mfa") || route.equals("auth/mfa/enable") || route.equals("auth/mfa/disable") || route.equals("auth/token") || route.startsWith("auth/sso") || route.startsWith("auth/saml")) return "auth";
        if (route.equals("agent/register")) return "register";
        if (route.startsWith("hooks/") || route.startsWith("apitasks/") && route.endsWith("/run") || route.equals("mcp")) return "calls";
        return null;
    }

    static int limit(String group) { return group.equals("calls") ? 1200 : 60; }

    /** Counts one request; false when the address has used up this minute's allowance for the group. */
    boolean allow(String address, String group) {
        long now = clock.getAsLong(), minute = now - now % 60_000;
        if (windows.size() > 20_000) windows.entrySet().removeIf(e -> e.getValue().start() < minute);
        Window w = windows.compute(address + "|" + group, (k, old) -> old == null || old.start() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        return w.count() <= limit(group);
    }

    /** Seconds until the current window ends (for Retry-After). */
    long retryAfter() { long now = clock.getAsLong(); return 60 - (now % 60_000) / 1000; }
}
