package com.oneofficeautomation.controlroom.module;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.MailSource;
import com.oneofficeautomation.controlroom.Vault;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the kernel gives a {@link CommandCenterModule}: its services (database, people and permissions, folders, vault,
 * audit, settings, runs) and the places a module plugs in (routes, clock jobs, settings, task kinds, trigger kinds, AI
 * assistant tools, template parts). This is the only way a module reaches the kernel, so the kernel can change inside
 * without touching modules, and a module can change without touching the kernel (docs/architecture/13-modules.md).
 */
public interface ModuleContext {

    // ------------------------------------------------------------------ where and who

    /** The tenant in the SaaS service, or null on premises. */
    String tenant();

    /** One CommandCenter's data folder. */
    Path dataDir();

    /** The database (the module's own tables and the kernel's shared ones, such as human_tasks and triggers). */
    Db db();

    /** The vault, to keep a module's own secrets encrypted with the data folder's master key. */
    Vault vault();

    /** An active user by name, or null when there is none or they are switched off. */
    User activeUser(String username);

    /** Whether the person has the permission (custom roles and switched-off roles included). */
    boolean can(User u, String permission);

    /** Refuses with CR.FORBIDDEN, saying what the person may not do, unless they have the permission. */
    void require(User u, String permission, String what);

    // ------------------------------------------------------------------ folders, vault, audit, settings

    /** Every folder's permission entries, read once for a batch of checks. */
    Map<String, Map<String, String>> folderEntries();

    /** What a person may do in a folder path ("Finance/Accounts payable"): none, view, run, edit or manage. */
    String folderAccess(String username, String role, String folder, Map<String, Map<String, String>> entries);

    /** True when an access level (none, view, run, edit, manage) is at least the level needed. */
    boolean atLeast(String access, String level);

    /** A folder path as the repository keeps it ("Finance/AP"); refuses one that is not valid (CR.BAD_PATH). */
    String normalizeFolder(String path);

    /** Refuses unless the person has the level on the folder of a repository path (an agent). */
    void requirePathAccess(String username, String role, String path, String level, String what);

    /** A secret One CommandCenter itself needs, from a vault reference folder/credential/attribute. */
    String systemCredential(String ref);

    /** Whether the person may use the vault folder (as its consumer). */
    boolean consumesVaultFolder(String username, String role, String folder);

    /** Records an Audit Trail entry. */
    void audit(String by, String action, String objectType, String objectId, Map<String, Object> details, String outcome, String ip);

    /** A Settings value (the module's own, from {@link #defineSetting}, or the kernel's). */
    Object setting(String key);

    /** A whole-number setting (0 when it is not a number). */
    default int intSetting(String key) { return setting(key) instanceof Number n ? n.intValue() : 0; }

    /** COUNT(*) AS n of a query, as a long. */
    default long count(String sql, Object... params) { return ((Number) db().one(sql, params).get("n")).longValue(); }

    /** Refuses an outbound address the network policy does not allow (Settings > Outbound connections). */
    void checkHost(String host, String what);

    // ------------------------------------------------------------------ runs and agents

    /** An agent's checked-in content at a label or version ("production"). */
    Map<String, Object> agentContent(String path, Object version);

    /** The inputs an agent declares. */
    Set<String> declaredInputs(Map<String, Object> flow);

    /**
     * Starts an agent run as a person (ControlRoom.startRun): on a runner (deviceId) or pool, or the person's default
     * runner when runAs names them; source says what started it ("docflow:process").
     */
    Map<String, Object> startRun(String requestedBy, String role, String path, String deviceId, String poolId, Map<String, Object> inputs,
                                 String source, String ip, int priority, String callbackUrl, String runAs, String mode);

    /** A run of the calling runner (a device), refused when it is not that runner's. */
    Map<String, Object> runOfDevice(Map<String, Object> device, String runId);

    /** Starts a trigger's agent with these inputs, as the trigger's owner. */
    Map<String, Object> fireTrigger(Map<String, Object> trigger, Map<String, Object> inputs, String source, String ip);

    /**
     * Adds work items to a work queue on behalf of a person (an app's publisher, with actor saying who used the app):
     * the queue's rules and the person's rights apply. Returns {ids}.
     */
    Map<String, Object> addWorkItems(User by, String actor, String queueId, List<Object> items, String ip);

    /** A trigger's settings (its config), as the trigger pollers read them. */
    Map<String, Object> triggerConfig(Map<String, Object> trigger);

    /** A mailbox over IMAP (as email triggers read it). */
    MailSource imapMailbox(String host, int port, boolean ssl, String user, String password, String folder, int timeoutMillis) throws IOException;

    /** A Microsoft 365 mailbox through Microsoft Graph, signed in as an app with a client secret. */
    MailSource graphMailbox(String tenantId, String clientId, String clientSecret, String mailbox, String folder) throws IOException;

    // ------------------------------------------------------------------ HTTP helpers for routes

    /** Sends a JSON answer. */
    void sendJson(HttpExchange ex, int status, Object body) throws IOException;

    /** Sends a file to download (Content-Disposition attachment), with the kernel's security headers. */
    void sendFile(HttpExchange ex, byte[] content, String contentType, String fileName) throws IOException;

    /** The request body, up to Settings > Largest upload (CR.TOO_LARGE beyond). */
    byte[] body(HttpExchange ex) throws IOException;

    /** The request body as a JSON object (CR.BAD_JSON otherwise). */
    Map<String, Object> jsonBody(HttpExchange ex) throws IOException;

    /** The request's query parameters (decoded). */
    Map<String, String> query(HttpExchange ex);

    /** Settings > Largest upload, in bytes. */
    int maxBody();

    // ------------------------------------------------------------------ what a module adds

    /** A signed-in person's request under /api/{prefix}/...: the route after the prefix ("" for /api/{prefix}). */
    @FunctionalInterface interface UserRoute { void handle(HttpExchange ex, User u, String method, String route, String ip) throws IOException; }

    /** A runner's request at /api/agent/{route}, authenticated as that runner (the device). Returns the JSON answer. */
    @FunctionalInterface interface AgentRoute { Object handle(HttpExchange ex, Map<String, Object> device, Map<String, String> query) throws IOException; }

    /** A call from another service at /api/{route}, before sign-in: the module authenticates it (a service token). Returns the JSON answer. */
    @FunctionalInterface interface CallbackRoute { Object handle(HttpExchange ex, String ip) throws IOException; }

    /** A request under /api/{prefix}/ before sign-in: the module authenticates it itself (an embed key). The route is after the prefix. */
    @FunctionalInterface interface PublicRoute { void handle(HttpExchange ex, String method, String route, String ip) throws IOException; }

    /** The sites that may show a page in a frame, as a frame-ancestors list ("https://a.example https://b.example"), or null when there is no such page. */
    @FunctionalInterface interface FrameOrigins { String forPage(String id); }

    /** Checks a value saved in Settings for a module's setting; throws OoaException (CR.BAD_INPUT) to refuse it. */
    @FunctionalInterface interface SettingCheck { void check(String value, boolean saas); }

    /** How the Task inbox treats a kind of task a module opens. */
    interface TaskKind {
        /** Whether the person sees the task (beyond being its assignee), from its data. */
        default boolean visible(User u, Map<String, Object> data, Map<String, Map<String, String>> folderEntries) { return true; }
        /** Why the task cannot be completed in the Task inbox (it closes elsewhere), or null when it can. */
        default String completeElsewhere() { return null; }
    }

    /** A trigger kind whose events a module pushes (nothing is polled). */
    @FunctionalInterface interface TriggerKind {
        /** A trigger's settings as stored, from what was sent when it was saved; throws OoaException (CR.BAD_INPUT) to refuse them. */
        Map<String, Object> config(Map<String, Object> request);
    }

    /** Tools a module offers AI assistants through One CommandCenter's MCP server, as the calling person. */
    interface McpTools {
        Set<String> names();
        List<Map<String, Object>> tools(User u);
        Map<String, Object> call(User u, String name, Map<String, Object> args, String ip);
        /** What the assistant is told about these tools (one or two sentences). */
        default String instructions() { return ""; }
    }

    /** What a module adds to a template's bundle when it is shared, and creates when it is installed. */
    interface TemplatePart {
        /** Adds the chosen items (by id) to the bundle being shared. */
        void addToBundle(User by, Map<String, Object> bundle, List<String> ids);
        /** Creates the bundle's items in the folder the template is installed in; returns each with its status. */
        List<Map<String, Object>> install(User by, Map<String, Object> bundle, String folder);
    }

    /**
     * Offers one of the module's objects to the rest of One CommandCenter by its type (its tests, and other modules
     * through an interface the kernel publishes); {@code Modules.service(type)} finds it.
     */
    <T> void provide(Class<T> type, T service);

    /** An object a module or the kernel offers by this type, or null when none does. */
    <T> T service(Class<T> type);

    // ------------------------------------------------------------------ the kernel's conventions, the same for every module

    /** A new id with a prefix ("idea_3f2a..."), as the kernel makes them. */
    static String id(String prefix) { return com.oneofficeautomation.controlroom.ControlRoom.newId(prefix); }

    /** Now, to the millisecond, as the kernel stores times. */
    static java.time.Instant now() { return com.oneofficeautomation.controlroom.ControlRoom.nowMillis(); }

    /** CR.FORBIDDEN: "You do not have permission to {what}". */
    static com.oneofficeautomation.sdk.OoaException denied(String what) { return com.oneofficeautomation.controlroom.ControlRoom.deniedTo(what); }

    /** A random token of this many bytes, URL-safe (embed keys, API keys), as the kernel makes them. */
    static String secureToken(int bytes) { return com.oneofficeautomation.controlroom.Security.token(bytes); }

    /** The SHA-256 of a text, in hex (how keys are kept: only their hash). */
    static String sha256(String text) { return com.oneofficeautomation.controlroom.Security.sha256(text); }

    /** Compares two secrets in constant time. */
    static boolean constantEquals(String a, String b) { return com.oneofficeautomation.controlroom.Security.constantEquals(a, b); }

    /** A folder path as the repository keeps it ("Finance/AP"); refuses one that is not valid (CR.BAD_PATH). */
    static String folderPath(String path) { return com.oneofficeautomation.controlroom.ControlRoom.normalizedFolder(path); }

    /** CR.NOT_FOUND: "{what} not found". */
    static com.oneofficeautomation.sdk.OoaException notFound(String what) { return com.oneofficeautomation.controlroom.ControlRoom.notFoundError(what); }

    /** A part a module adds to every run's job when a runner takes it (Prompt Skills: the skills the agent names). */
    @FunctionalInterface interface JobPart {
        /**
         * The part for this run: the agent's flow, its bundle (the agents it calls) and the person it runs as. Empty
         * when there is nothing to add; an OoaException fails the run before it starts, with the reason.
         */
        Map<String, Object> forJob(Map<String, Object> flow, Map<String, Object> bundle, String principal);
    }

    /** Adds a part, under this key, to the job of every run (the runner reads it there). */
    void jobPart(String key, JobPart part);

    /** Requests under /api/{prefix}/ from signed-in people. */
    void route(String prefix, UserRoute handler);

    /** Requests at /api/agent/{route} from runners. */
    void agentRoute(String route, AgentRoute handler);

    /** Requests at /api/{route} from another service, before sign-in (POST only). */
    void callbackRoute(String route, CallbackRoute handler);

    /** Requests under /api/{prefix}/ before sign-in, which the module authenticates itself. */
    void publicRoute(String prefix, PublicRoute handler);

    /**
     * The console page at /{path}/{id} (apps embedded in other sites: /embed/{link}), which the sites the module names
     * may frame; an unknown id gets 404.
     */
    void embeddablePage(String path, FrameOrigins origins);

    /** A job on One CommandCenter's clock, on the cluster's leader only, first after the delay. Failures are ignored until the next turn. */
    void every(Duration delay, Duration period, Runnable job);

    /** A setting with its default (its type sets how Settings saves it) and its check. */
    void defineSetting(String key, Object defaultValue, SettingCheck check);

    /** A kind of Task inbox task the module opens. */
    void taskKind(String kind, TaskKind rules);

    /** A trigger kind (at most 20 characters) with its label, whose events the module reports with {@link #fireTrigger}. */
    void triggerKind(String kind, String label, TriggerKind rules);

    /** Tools for AI assistants. */
    void mcpTools(McpTools tools);

    /** A part of template bundles. */
    void templatePart(TemplatePart part);
}
