package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The Connect and Disconnect steps of the SaaS toolkits (Slack, Box, DocuSign, Zendesk, Asana, Jira, GitHub, Azure DevOps,
 * Google): Connect names a saved connection, checks it exists and is made for the toolkit, and keeps it in the run's
 * sessions as a {@link ConnectionSession}; the runner then gives that connection to the toolkit's later steps that choose
 * none of their own. No secret is read or kept here: each step gets a fresh token from the connection just in time.
 */
public final class SavedConnections {
    private SavedConnections() {}

    /** Help of the Connect step's connection field. */
    public static final String D_CONNECTION = "Saved connection (under Connections) that the later steps of this toolkit use when they choose none of their own.";
    /** Help of the Connect step's share tick. */
    public static final String D_SHARE = "Agents this one runs may use it too.";
    /** Help of the Connect step. */
    public static String connectDescription(String toolkit) {
        return "Sign in once with a saved connection: the later " + toolkit + " steps of this agent use it unless they choose another.";
    }

    /** Connect: keep the connection under the session name; returns the connection's name as the step's output. */
    public static Map<String, Object> connect(ActionContext ctx, String packageId, String connection, String session, boolean share) {
        String name = connection == null ? "" : connection.strip();
        if (name.isEmpty()) throw new ActionException("CONNECTION.NOT_FOUND", "Choose the saved connection to use", "Fill in connection with the name of a saved connection.");
        Map<String, Object> all = saved(ctx);
        if (all != null) {
            if (!(all.get(name) instanceof Map<?, ?> found))
                throw new ActionException("CONNECTION.NOT_FOUND", "There is no saved connection named '" + name + "'",
                        "Create it under Connections (One CommandCenter or OneAgentStudio), or choose another connection.");
            Object pkg = found.get("package");
            if (pkg instanceof String p && !p.isBlank() && !p.equals(packageId))
                throw new ActionException("CONNECTION.WRONG_TOOLKIT", "Connection '" + name + "' is for " + p + ", not " + packageId, "Choose a connection made for " + packageId + ".");
        }
        String key = session == null || session.isBlank() ? packageId.substring(packageId.indexOf('.') + 1) : session.strip();
        ctx.sessions().put(key, new ConnectionSession(packageId, name));
        if (share) ctx.shareSession(key);
        ctx.info("Later steps of " + packageId + " use connection '" + name + "'");
        return Map.of("connection", name);
    }

    /** Disconnect: forget the connection kept under the session name (or every one of the toolkit when the name is empty). */
    public static Map<String, Object> disconnect(ActionContext ctx, String packageId, String session) {
        if (session != null && !session.isBlank()) {
            if (ctx.sessions().get(session.strip()) instanceof ConnectionSession c && c.packageId().equals(packageId)) ctx.sessions().remove(session.strip());
        } else ctx.sessions().entrySet().removeIf(e -> e.getValue() instanceof ConnectionSession c && c.packageId().equals(packageId));
        return Map.of();
    }

    /** The connection a Connect step of this toolkit chose (the latest), or null. */
    public static String current(ActionContext ctx, String packageId) {
        ConnectionSession best = null;
        for (Object o : ctx.sessions().values())
            if (o instanceof ConnectionSession c && packageId.equals(c.packageId()) && (best == null || c.openedAt() >= best.openedAt())) best = c;
        return best == null ? null : best.connection();
    }

    /** Saved connections (as the runner reads them), or null when this run has none to check against. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> saved(ActionContext ctx) {
        try {
            Object svc = ctx.service("connections", Object.class);
            if (svc instanceof java.util.function.Supplier<?> sup && sup.get() instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (OoaException absent) { /* not in One CommandCenter: the workspace file */ }
        Path p = ctx.workDir() == null ? null : ctx.workDir().resolve("connections.json");
        if (p == null || !Files.isRegularFile(p)) return null;
        try { return JsonLite.parse(Files.readString(p)) instanceof Map<?, ?> m ? (Map<String, Object>) m : null; }
        catch (Exception e) { throw new ActionException("CONNECTION.INVALID", "connections.json is not valid JSON: " + e.getMessage(), "Fix the file in the workspace."); }
    }
}
