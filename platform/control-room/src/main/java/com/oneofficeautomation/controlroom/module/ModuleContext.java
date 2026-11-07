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

    /** Whether the person may see a repository path (an agent) by its folder's permissions. */
    boolean mayView(User u, String path);

    /** What a person may do with a repository path (an agent) by its folder: none, view, run, edit or manage. */
    String pathAccess(String username, String role, String path);

    /** An agent as the person may see it (content, versions, labels), at a version or "latest" / "production". */
    Map<String, Object> getAgent(User by, String path, Object version);

    /** The agents the person may see in the repository. */
    List<Map<String, Object>> listAgents(User by);

    /** What a group of principals (a person's user:, role: and team: entries) may do in a folder path. */
    String folderAccessFor(List<String> principals, String role, String folder, Map<String, Map<String, String>> entries);

    /** Refuses unless the person has the level on the folder of a repository path (an agent). */
    void requirePathAccess(String username, String role, String path, String level, String what);

    /** As above, for a person. */
    default void requirePathAccess(User u, String path, String level, String what) { requirePathAccess(u.username(), u.role(), path, level, what); }

    /** A secret One CommandCenter itself needs, from a vault reference folder/credential/attribute. */
    String systemCredential(String ref);

    /** Whether the person may use the vault folder (as its consumer). */
    boolean consumesVaultFolder(String username, String role, String folder);

    /** Records an Audit Trail entry; details is usually a map, or a short text. */
    void audit(String by, String action, String objectType, String objectId, Object details, String outcome, String ip);

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

    /** Every main and further role of a person. */
    List<String> rolesOf(String username, String role);

    /** Whether a role exists (built in or custom). */
    boolean roleExists(String role);

    /** An online runner that takes work, or null when none is online. */
    String onlineRunner();

    /** The model connections (AI gateway) a person's roles may use, as a run's job carries them. */
    Map<String, Object> modelConnectionsFor(List<String> roles);

    /** Records the AI calls a run (or a chat turn) made, for AI governance. */
    void recordAiCalls(Map<String, Object> run, List<?> calls);

    /** The teams and roles a person acts as (user:, role:, team: principals), for sharing checks. */
    List<String> principalsOf(String username, String role);

    /** One CommandCenter's default time zone (Settings), as an id such as Asia/Kolkata. */
    String defaultTimeZone();

    /** Whether the person may use a work queue at a level (view, work, manage). */
    boolean queueAllows(User u, String queueId, String level);

    /** A work queue's item counts by status. */
    Map<String, Object> queueCounts(String queueId);

    /** The AI tokens a model connection used this month. */
    long tokensThisMonth(String connection);

    /** Publishes an event to the event subscriptions (events out), as the kernel's own events go. */
    void emitEvent(String type, Map<String, Object> data);

    /** Whether outgoing mail is set up (Settings > Mail). */
    boolean mailConfigured();

    /** A file sent with a mail. */
    record MailAttachment(String name, String contentType, byte[] bytes) {}

    /** Sends a mail through Settings > Mail. */
    void sendMail(List<String> to, String subject, String body, List<MailAttachment> attachments);

    /** Sends a mail without attachments. */
    default void sendMail(List<String> to, String subject, String body) { sendMail(to, subject, body, List.of()); }

    /** The mail addresses of people (user names, role:, team:), as the kernel resolves them. */
    List<String> addressesOf(java.util.Collection<String> people);

    /** The server-side runner's device id (serverless Agent APIs run there). */
    String serverlessDevice();

    /** Refuses an agent the server-side runner cannot run (screen steps, toolkits not allowed there). */
    void checkServerless(Map<String, Object> flow);

    /** Stores a new version of an agent as the person (as an import: no check-out needed), with every check-in check. */
    Map<String, Object> storeAgentVersion(User by, String path, Map<String, Object> flow, String comment, boolean production, String ip);

    /** Every module's tools for AI assistants (the MCP server lists and calls them). */
    List<McpTools> mcpTools();

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

    /** The request body parsed as JSON (an object, a list or a value; CR.BAD_JSON when it is not JSON). */
    Object jsonValue(HttpExchange ex) throws IOException;

    /** The bearer token or session cookie of a request, or null (a module checks its own tokens with it). */
    String requestToken(HttpExchange ex);

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

    /** Told around every check-in (and import) of an agent, workflow or form. */
    interface CheckinListener {
        /** Before the version is stored: throws OoaException to refuse it (code analysis rules that block check-in). */
        default void before(String path, Map<String, Object> flow) { }
        /** After the version is stored, in the same transaction (code analysis stores its report). */
        default void checkedIn(User by, String path, int version, Map<String, Object> flow, String comment) { }
    }

    /** Decides whether making a version live needs someone's approval first (production approval). */
    interface ProductionGate {
        /** True when production versions need approval now. */
        boolean required();
        /** Asks for a version to go live; returns the request. */
        Map<String, Object> request(User by, String path, int version, String comment, String ip);
    }

    /** The AI guardrails for an agent's runs (by its folder), merged into the run's job; null when none. */
    @FunctionalInterface interface Guardrails { Map<String, Object> forAgent(String path); }

    /** Told when something in the repository changed (a check-in, a version made live), for Git sync. */
    @FunctionalInterface interface RepositoryChange { void changed(String by, String message); }

    /** A request a running agent makes of One CommandCenter (raise_alert): the run, its user, the step's arguments and the audit actor. */
    @FunctionalInterface interface RunRequest { Map<String, Object> handle(Map<String, Object> run, User as, Map<String, Object> args, String actor); }

    /**
     * A request under /api/{prefix}/ before sign-in: the module authenticates it itself (an embed key, a task token). The
     * route is after the prefix. Returns false when it is not one of the module's public requests, so the kernel goes on
     * to sign the person in (a prefix may have public and signed-in routes).
     */
    @FunctionalInterface interface PublicRoute { boolean handle(HttpExchange ex, String method, String route, String ip) throws IOException; }

    /** The sites that may show a page in a frame, as a frame-ancestors list ("https://a.example https://b.example"), or null when there is no such page. */
    @FunctionalInterface interface FrameOrigins { String forPage(String id); }

    /** Told when a run (or a chain of suspended and resumed runs) ends: success, failed or stopped. */
    @FunctionalInterface interface RunEnded { void ended(Map<String, Object> run, String status, Map<String, Object> result); }

    /** Task inbox tasks of a stage a module opens (One Chat's R3 approvals), whatever their kind. */
    interface TaskStage {
        /** Refuses (OoaException) a person completing the task, such as approving their own request. */
        default void checkComplete(User u, Map<String, Object> task) { }
        /** The task was decided; returns the id of a run it started, or null. */
        String decided(Map<String, Object> task, User u, String outcome, String comment, String ip);
    }

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

    /** The server-side runner's device id (to recognise its runs; serverlessDevice() also sets it up). */
    String SERVERLESS_DEVICE = com.oneofficeautomation.controlroom.ControlRoom.SERVERLESS_DEVICE_ID;

    /** How long after its last heartbeat a runner still counts as online. */
    java.time.Duration ONLINE = com.oneofficeautomation.controlroom.ControlRoom.ONLINE_WINDOW;

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

    /** The folder of a repository path ("Finance/AP/Post.flow.json" -> "Finance/AP"). */
    static String folderOf(String path) { return com.oneofficeautomation.controlroom.ControlRoom.folderOfPath(path); }

    /** One CSV line (with its line break), quoted and protected against spreadsheet formulas, as the kernel's exports write it. */
    static String csvRow(List<String> cells) { return com.oneofficeautomation.controlroom.ControlRoom.csvLine(cells); }

    /** Checks a mail address (CR.BAD_INPUT when it is not one); returns it trimmed. */
    static String mailAddress(String address) { return com.oneofficeautomation.controlroom.ControlRoom.mailAddressChecked(address); }

    /** A person as the kernel represents them, from a users row. */
    static User userOf(Map<String, Object> row) { return com.oneofficeautomation.controlroom.ControlRoom.userFromRow(row); }

    /** A whole number from a request parameter, or the default when it is missing or not a number. */
    static int intOr(String value, int dflt) { return com.oneofficeautomation.controlroom.ControlRoom.intOrDefault(value, dflt); }

    /** A time from a request parameter (an ISO instant or a date; a date's end with endOfDay), or null; anything else is CR.BAD_INPUT. */
    static java.time.Instant instant(String value, boolean endOfDay) { return com.oneofficeautomation.controlroom.ControlRoom.instantOf(value, endOfDay); }

    /** A simple PDF of a title and text lines (mailed reports), as the kernel writes them. */
    static byte[] textPdf(String title, List<String> lines) { return com.oneofficeautomation.controlroom.ControlRoom.textPdfOf(title, lines); }

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

    /** Tells the module when runs end (after the kernel's own follow-up: work items, KPIs, workflows). */
    void onRunEnded(RunEnded listener);

    /** Tasks whose stage the module owns (One Chat: chat_approval). */
    void taskStage(String stage, TaskStage rules);

    /** Told around every check-in. */
    void checkinListener(CheckinListener listener);

    /** The production gate (one module at most). */
    void productionGate(ProductionGate gate);

    /** AI guardrails for runs. */
    void guardrails(Guardrails source);

    /** Tells whoever follows repository changes (Git sync) that something changed. */
    void repositoryChanged(String by, String message);

    /** Follows repository changes (Git sync). */
    void onRepositoryChanged(RepositoryChange listener);

    /** Answers a running agent's request of this name (POST /api/agent/request with call). */
    void runRequest(String call, RunRequest handler);

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
