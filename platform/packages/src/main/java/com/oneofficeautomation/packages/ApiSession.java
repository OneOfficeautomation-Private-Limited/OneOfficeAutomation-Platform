package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.util.*;

/**
 * A sign-in kept for the later steps of a run, for toolkits that call a web API with an address and a token
 * (Salesforce, ServiceNow, Okta, Workday, Databricks, Snowflake, Kafka REST). The sign-in step (Authenticate or
 * Connect) keeps the values it worked out under a session name; a later step that names the session, or leaves its
 * address and token empty while a session with the toolkit's default name is open, takes the values it leaves empty
 * from the session. A step that fills its own address and token works exactly as before, so one-step use still works.
 *
 * <p>Values are kept as they came: text as text, secrets as {@link SecureString} (never revealed in toString or logs).
 * An object kept under {@link #resource()} (a native client, say) is closed with the session.
 */
public final class ApiSession implements AutoCloseable {
    /** Toolkit short name, such as "salesforce": a step of another toolkit cannot use the session by mistake. */
    final String toolkit;
    private final Map<String, Object> values;
    private final AutoCloseable resource;

    ApiSession(String toolkit, Map<String, Object> values) { this(toolkit, values, null); }

    ApiSession(String toolkit, Map<String, Object> values, AutoCloseable resource) {
        this.toolkit = toolkit;
        Map<String, Object> v = new LinkedHashMap<>();
        if (values != null) values.forEach((k, x) -> { if (x != null && !(x instanceof String s && s.isBlank())) v.put(k, x); });
        this.values = Collections.unmodifiableMap(v);
        this.resource = resource;
    }

    /** The value kept for this input name, or null. */
    Object get(String name) { return values.get(name); }

    /** A kept value as text (null when not kept). */
    String text(String name) { Object v = values.get(name); return v == null ? null : v instanceof SecureString s ? s.reveal() : String.valueOf(v); }

    /** The native client or connection kept with the session, or null. */
    AutoCloseable resource() { return resource; }

    Map<String, Object> values() { return values; }

    /**
     * Keeps the sign-in under the session name (the toolkit's default name when empty) and, when asked, lets agents
     * this one runs use it. An earlier session under the same name is closed first.
     */
    static ApiSession open(ActionContext ctx, String name, String defaultName, String toolkit, Map<String, Object> values, boolean share) {
        return open(ctx, name, defaultName, new ApiSession(toolkit, values), share);
    }

    static ApiSession open(ActionContext ctx, String name, String defaultName, ApiSession s, boolean share) {
        String key = name == null || name.isBlank() ? defaultName : name.strip();
        Object old = ctx.sessions().put(key, s);
        if (old instanceof AutoCloseable c && old != s) { try { c.close(); } catch (Exception ignored) { } }
        if (share) ctx.shareSession(key);
        return s;
    }

    /**
     * The session a step uses: the one it names (which must be open and of this toolkit); or, when it names none,
     * the one open under the toolkit's default name, if any; else null (the step works with its own fields).
     */
    static ApiSession find(ActionContext ctx, String name, String defaultName, String toolkit) {
        boolean named = name != null && !name.isBlank();
        String key = named ? name.strip() : defaultName;
        Object o = ctx.sessions().get(key);
        if (o == null) {
            if (!named) return null;
            throw new ActionException(toolkit.toUpperCase(Locale.ROOT) + ".NO_SESSION", "No session named '" + key + "' is open",
                    "Add a sign-in step (Authenticate or Connect) with this session name before this step, or leave the session empty and fill in the address and token.");
        }
        if (!(o instanceof ApiSession s) || !s.toolkit.equals(toolkit)) {
            if (!named) return null;
            throw new ActionException(toolkit.toUpperCase(Locale.ROOT) + ".WRONG_SESSION", "Session '" + key + "' is not a " + toolkit + " session",
                    "Name the session the " + toolkit + " sign-in step opened.");
        }
        return s;
    }

    /** The step's own text when it gave one, else the session's, else null. */
    static String text(ApiSession s, String name, String given) {
        if (given != null && !given.isBlank()) return given;
        return s == null ? given : s.text(name);
    }

    /** The step's own value (a Credential, a number) when it gave one, else the session's, else the given value. */
    static Object value(ApiSession s, String name, Object given) {
        if (given != null && !(given instanceof String g && g.isBlank())) return given;
        if (s == null) return given;
        Object v = s.get(name);
        return v == null ? given : v;
    }

    /** Ends the session: removes it from the run and closes what it holds. Nothing happens when none is open. */
    static boolean close(ActionContext ctx, String name, String defaultName) {
        String key = name == null || name.isBlank() ? defaultName : name.strip();
        Object o = ctx.sessions().get(key);
        if (!(o instanceof ApiSession s)) return false;
        ctx.sessions().remove(key);
        s.close();
        return true;
    }

    /** Fails with a clear message when the step has neither its own value nor one from a session. */
    static String require(String prefix, String value, String what) {
        if (value == null || value.isBlank())
            throw new ActionException(prefix + ".NO_SIGN_IN", "The " + what + " is missing",
                    "Fill in the " + what + ", or add a sign-in step (Authenticate or Connect) before this step and name its session.");
        return value;
    }

    static Object requireSecret(String prefix, Object value, String what) {
        if (value == null || value instanceof String s && s.isBlank())
            throw new ActionException(prefix + ".NO_SIGN_IN", "The " + what + " is missing",
                    "Map the " + what + " from the vault, or add a sign-in step (Authenticate or Connect) before this step and name its session.");
        return value;
    }

    @Override public void close() {
        if (resource != null) { try { resource.close(); } catch (Exception ignored) { } }
    }

    @Override public String toString() {
        StringJoiner j = new StringJoiner(", ", toolkit + " session {", "}");
        values.forEach((k, v) -> j.add(k + "=" + (v instanceof SecureString ? SecureString.MASK : v)));
        return j.toString();
    }
}
