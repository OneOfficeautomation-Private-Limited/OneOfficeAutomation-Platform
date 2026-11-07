package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.CommandCenterModule;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The feature modules of this One CommandCenter (docs/architecture/13-modules.md): found with ServiceLoader, their
 * schema brought up to date, started in dependency order, and what they register kept here for the kernel to use:
 * routes (ControlRoomServer), clock jobs, task kinds (Workflows), trigger kinds (TriggerKinds), AI assistant tools
 * (McpServer) and template parts (Templates). The kernel only ever asks this registry; it never names a module's class.
 */
public final class Modules {
    /** A clock job a module added. */
    record Job(String module, Duration delay, Duration period, Runnable run) {}

    private final ControlRoom cr;
    private final List<CommandCenterModule> started = new ArrayList<>();
    final Map<String, ModuleContext.UserRoute> userRoutes = new LinkedHashMap<>();
    final Map<String, ModuleContext.AgentRoute> agentRoutes = new LinkedHashMap<>();
    final Map<String, ModuleContext.CallbackRoute> callbacks = new LinkedHashMap<>();
    final Map<String, ModuleContext.PublicRoute> publicRoutes = new LinkedHashMap<>();
    final Map<String, ModuleContext.FrameOrigins> embeddablePages = new LinkedHashMap<>();
    final List<Job> jobs = new CopyOnWriteArrayList<>();
    final Map<String, ModuleContext.TaskKind> taskKinds = new LinkedHashMap<>();
    final List<ModuleContext.McpTools> mcpTools = new CopyOnWriteArrayList<>();
    final List<ModuleContext.TemplatePart> templateParts = new CopyOnWriteArrayList<>();
    final Map<String, ModuleContext.JobPart> jobParts = new LinkedHashMap<>();
    final List<ModuleContext.RunEnded> runEnded = new CopyOnWriteArrayList<>();
    final Map<String, ModuleContext.TaskStage> taskStages = new LinkedHashMap<>();
    final List<ModuleContext.CheckinListener> checkins = new CopyOnWriteArrayList<>();
    volatile ModuleContext.ProductionGate productionGate;
    final List<ModuleContext.Guardrails> guardrails = new CopyOnWriteArrayList<>();
    final List<ModuleContext.RepositoryChange> repositoryChanges = new CopyOnWriteArrayList<>();
    final Map<String, ModuleContext.RunRequest> runRequests = new LinkedHashMap<>();
    final List<ModuleContext.NoRunner> noRunner = new CopyOnWriteArrayList<>();
    volatile ModuleContext.WorkflowTasks workflowTasks;
    final Map<String, ModuleContext.DefinitionCheck> definitionChecks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Class<?>, Object> services = new java.util.concurrent.ConcurrentHashMap<>();

    /** An object a module offers by this type (ModuleContext.provide), or null when no module running here does. */
    public <T> T service(Class<T> type) { return type.cast(services.get(type)); }

    Modules(ControlRoom cr) { this.cr = cr; }

    /** The modules on the class path, each after the modules it depends on (a missing or circular dependency is refused). */
    static List<CommandCenterModule> discover() {
        Map<String, CommandCenterModule> byId = new LinkedHashMap<>();
        for (CommandCenterModule m : ServiceLoader.load(CommandCenterModule.class)) {
            if (!m.id().matches("[a-z][a-z0-9-]{1,79}")) throw new IllegalStateException("A module id is lower case letters, digits and -: " + m.id());
            if (byId.putIfAbsent(m.id(), m) != null) throw new IllegalStateException("Two modules have the id " + m.id());
        }
        List<CommandCenterModule> out = new ArrayList<>();
        Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (CommandCenterModule m : byId.values()) order(m, byId, done, visiting, out);
        return out;
    }

    private static void order(CommandCenterModule m, Map<String, CommandCenterModule> byId, Set<String> done, Set<String> visiting, List<CommandCenterModule> out) {
        if (done.contains(m.id())) return;
        if (!visiting.add(m.id())) throw new IllegalStateException("Modules depend on each other in a circle: " + m.id());
        for (String d : m.dependsOn()) {
            CommandCenterModule dm = byId.get(d);
            if (dm == null) throw new IllegalStateException("Module " + m.id() + " needs module " + d + ", which is not installed");
            order(dm, byId, done, visiting, out);
        }
        visiting.remove(m.id());
        done.add(m.id());
        out.add(m);
    }

    /**
     * Brings every installed module's own tables up to date (ooa db migrate, with the setup account, after the kernel's
     * schema). Returns each module with the version it was at and is at now.
     */
    public static List<Map<String, Object>> migrateAll(Db db) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommandCenterModule m : discover()) {
            int from = db.moduleVersion(m.id());
            db.migrateModule(m.id(), m.schema());
            out.add(Map.of("module", m.id(), "from", from, "to", db.moduleVersion(m.id())));
        }
        return out;
    }

    /** Starts the modules: their schema first, then each one's start, which registers what it adds. */
    void start() {
        for (CommandCenterModule m : discover()) {
            cr.db.migrateModule(m.id(), m.schema());
            m.start(new Context(m.id()));
            started.add(m);
        }
    }

    /** The modules running here, for Settings > About and GET /api/modules. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommandCenterModule m : started)
            out.add(Map.of("id", m.id(), "name", m.name(), "version", m.version(), "schema", m.schema().size()));
        return out;
    }

    /** A fresh context of a module running here, for tests that build the module's parts by hand. */
    ModuleContext contextFor(String module) {
        if (started.stream().noneMatch(m -> m.id().equals(module))) throw new IllegalStateException("Module " + module + " is not running here");
        return new Context(module);
    }

    /** The rules of a task stage a module owns, or null. */
    ModuleContext.TaskStage taskStage(Object stage) { return stage == null ? null : taskStages.get(String.valueOf(stage)); }

    /** Tells repository followers (Git sync) that something changed; their failures never stop the change. */
    void repositoryChanged(String by, String message) {
        for (ModuleContext.RepositoryChange l : repositoryChanges) try { l.changed(by, message); } catch (RuntimeException ignored) { /* the next change catches up */ }
    }

    /** The AI guardrails of an agent's runs from every source, merged (a later source adds to an earlier one); null when none. */
    Map<String, Object> guardrailsFor(String path) {
        Map<String, Object> out = null;
        for (ModuleContext.Guardrails g : guardrails) {
            Map<String, Object> one = g.forAgent(path);
            if (one != null) { if (out == null) out = new LinkedHashMap<>(); out.putAll(one); }
        }
        return out;
    }

    /** The module route (by the longest prefix of whole path segments) for a request path, or null. */
    static <T> Map.Entry<String, T> match(Map<String, T> routes, String route) {
        Map.Entry<String, T> best = null;
        for (Map.Entry<String, T> e : routes.entrySet())
            if ((route.equals(e.getKey()) || route.startsWith(e.getKey() + "/")) && (best == null || e.getKey().length() > best.getKey().length())) best = e;
        return best;
    }

    /** Tells the modules a pool has work but no online runner. */
    void noRunner(String poolId) { for (ModuleContext.NoRunner l : noRunner) try { l.forPool(poolId); } catch (RuntimeException ignored) { } }

    /** Tells the modules a run ended; one module failing does not stop the others. */
    void runEnded(Map<String, Object> run, String status, Map<String, Object> result) {
        for (ModuleContext.RunEnded l : runEnded) try { l.ended(run, status, result); } catch (RuntimeException ignored) { /* the module's own clock catches up */ }
    }

    /** The task kind rules a module registered, or null for the kernel's own kinds. */
    ModuleContext.TaskKind taskKind(String kind) { return taskKinds.get(kind); }

    /** One module's view of the kernel. */
    private final class Context implements ModuleContext {
        private final String module;

        Context(String module) { this.module = module; }

        public String tenant() { return cr.tenant; }
        public Path dataDir() { return cr.dataDir; }
        public Db db() { return cr.db; }
        public com.oneofficeautomation.controlroom.Vault vault() { return cr.vault; }

        public User activeUser(String username) {
            Map<String, Object> u = cr.db.one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", username);
            return u == null ? null : new User((String) u.get("id"), (String) u.get("username"), (String) u.get("role"), (String) u.get("display_name"), false, false, false);
        }

        public boolean can(User u, String permission) { return cr.can(u, permission); }
        public void require(User u, String permission, String what) { cr.require(u, permission, what); }

        public Map<String, Map<String, String>> folderEntries() { return cr.folders.entries(); }
        public String folderAccess(String username, String role, String folder, Map<String, Map<String, String>> entries) {
            return cr.folders.accessToFolder(username, role, folder, entries);
        }
        public boolean atLeast(String access, String level) { return Folders.atLeast(access, level); }
        public String normalizeFolder(String path) { return Folders.normalize(path); }
        public boolean mayView(User u, String path) { return cr.folders.mayView(u, path); }
        public String folderAccessFor(List<String> principals, String role, String folder, Map<String, Map<String, String>> entries) { return cr.folders.accessToFolder(principals, role, folder, entries); }
        public String pathAccess(String username, String role, String path) { return cr.folders.access(username, role, path); }
        public Map<String, Object> getAgent(User by, String path, Object version) { return cr.getBot(by, path, version); }
        public List<Map<String, Object>> listAgents(User by) { return cr.repoList(by, null); }
        public void requirePathAccess(String username, String role, String path, String level, String what) { cr.folders.require(username, role, path, level, what); }

        public String systemCredential(String ref) { return cr.systemCredential(ref); }
        public boolean consumesVaultFolder(String username, String role, String folder) {
            Map<String, Object> l = cr.db.one("SELECT * FROM lockers WHERE name = ?", folder);
            return l != null && cr.consumes(username, role, l);
        }

        public void audit(String by, String action, String objectType, String objectId, Object details, String outcome, String ip) {
            cr.audit.record(by, action, objectType, objectId, details, outcome, ip);
        }

        public Object setting(String key) { return cr.platform.setting(key); }
        public void checkHost(String host, String what) { NetGuard.check(cr, host, what); }

        public Map<String, Object> agentContent(String path, Object version) { return cr.content(path, version); }
        public Set<String> declaredInputs(Map<String, Object> flow) { return ControlRoom.declaredInputs(flow); }
        public Map<String, Object> startRun(String requestedBy, String role, String path, String deviceId, String poolId, Map<String, Object> inputs,
                                            String source, String ip, int priority, String callbackUrl, String runAs, String mode) {
            return cr.startRun(requestedBy, role, path, deviceId, poolId, inputs, source, ip, priority, callbackUrl, runAs, mode);
        }
        public Map<String, Object> runOfDevice(Map<String, Object> device, String runId) { return cr.runOnDevice(device, runId); }
        public Map<String, Object> fireTrigger(Map<String, Object> trigger, Map<String, Object> inputs, String source, String ip) {
            return cr.fireTrigger(trigger, inputs, source, ip);
        }
        public Map<String, Object> addWorkItems(User by, String actor, String queueId, List<Object> items, String ip) { return cr.platform.addItemsAs(by, actor, queueId, items, ip); }
        public List<String> rolesOf(String username, String role) { return cr.rolesOf(username, role); }
        public boolean roleExists(String role) { return cr.roleExists(role); }
        public String onlineRunner() { return cr.platform.onlineRunner(); }
        public Map<String, Object> modelConnectionsFor(List<String> roles) { return cr.platform.connectionsForJob(roles); }
        public void recordAiCalls(Map<String, Object> run, List<?> calls) { cr.platform.recordAiCalls(run, calls); }
        public List<String> principalsOf(String username, String role) { return cr.teams.principalsOf(username, role); }
        public String defaultTimeZone() { return cr.defaultTimeZone(); }
        public boolean queueAllows(User u, String queueId, String level) { return cr.platform.queueAllows(u, queueId, level); }
        public Map<String, Object> queueCounts(String queueId) { return cr.platform.counts(queueId); }
        public long tokensThisMonth(String connection) { return cr.platform.tokensThisMonth(connection); }
        public void emitEvent(String type, Map<String, Object> data) { cr.events.emit(type, data); }
        public boolean mailConfigured() { return cr.mailer.configured(); }
        public void sendMail(List<String> to, String subject, String body, List<MailAttachment> attachments) {
            List<Mailer.Attachment> files = new ArrayList<>();
            for (MailAttachment a : attachments == null ? List.<MailAttachment>of() : attachments) files.add(new Mailer.Attachment(a.name(), a.contentType(), a.bytes()));
            cr.mailer.send(to, subject, body, files);
        }
        public List<String> addressesOf(java.util.Collection<String> people) { return cr.addressesOf(people); }
        public String serverlessDevice() { return (String) cr.serverless.device().get("id"); }
        public void checkServerless(Map<String, Object> flow) { cr.serverless.check(flow); }
        public Map<String, Object> storeAgentVersion(User by, String path, Map<String, Object> flow, String comment, boolean production, String ip) {
            return cr.storeVersion(by, path, flow, comment, production, false, ip);
        }
        public List<McpTools> mcpTools() { return java.util.Collections.unmodifiableList(mcpTools); }
        public Map<String, Object> triggerConfig(Map<String, Object> trigger) { return TriggerPollers.config(trigger); }

        public MailSource imapMailbox(String host, int port, boolean ssl, String user, String password, String folder, int timeoutMillis) {
            return new ImapMailSource(host, port, ssl, user, password, folder, timeoutMillis);
        }
        public MailSource graphMailbox(String tenantId, String clientId, String clientSecret, String mailbox, String folder) throws IOException {
            return new GraphMailSource(null, mailbox, folder, GraphMailSource.clientCredentialsToken(null, tenantId, clientId, clientSecret));
        }

        public void sendJson(HttpExchange ex, int status, Object body) throws IOException { ControlRoomServer.sendJson(ex, status, body); }
        public byte[] body(HttpExchange ex) throws IOException {
            int limit = ControlRoomServer.maxBody.getAsInt();
            byte[] b = ex.getRequestBody().readNBytes(limit + 1);
            if (b.length > limit) throw new OoaException("CR.TOO_LARGE", "The upload is larger than the Largest upload setting allows", "Raise Settings > Largest upload, or send fewer files at once");
            return b;
        }
        public Map<String, Object> jsonBody(HttpExchange ex) throws IOException { return ControlRoomServer.map(ControlRoomServer.body(ex)); }
        public int maxBody() { return ControlRoomServer.maxBody.getAsInt(); }
        public Map<String, String> query(HttpExchange ex) { return ControlRoomServer.query(ex); }
        public Object jsonValue(HttpExchange ex) throws IOException { return ControlRoomServer.body(ex); }
        public String requestToken(HttpExchange ex) { return ControlRoomServer.token(ex); }
        public void sendFile(HttpExchange ex, byte[] content, String contentType, String fileName) throws IOException { ControlRoomServer.sendBytes(ex, content, contentType, fileName); }

        public void route(String prefix, UserRoute handler) { claim(userRoutes, prefix, handler, "the route /api/" + prefix); }
        public void agentRoute(String route, AgentRoute handler) { claim(agentRoutes, route, handler, "the runner route /api/agent/" + route); }
        public void callbackRoute(String route, CallbackRoute handler) { claim(callbacks, route, handler, "the callback /api/" + route); }
        public void publicRoute(String prefix, PublicRoute handler) { claim(publicRoutes, prefix, handler, "the public route /api/" + prefix); }
        public void embeddablePage(String path, FrameOrigins origins) { claim(embeddablePages, path, origins, "the page /" + path + "/"); }
        public void onRunEnded(RunEnded listener) { runEnded.add(listener); }
        public void taskStage(String stage, TaskStage rules) { claim(taskStages, stage, rules, "the task stage " + stage); }
        public void checkinListener(CheckinListener listener) { checkins.add(listener); }
        public void productionGate(ProductionGate gate) {
            if (productionGate != null) throw new IllegalStateException("Module " + module + " registers a production gate, which another module has");
            productionGate = gate;
        }
        public void guardrails(Guardrails source) { guardrails.add(source); }
        public void repositoryChanged(String by, String message) { Modules.this.repositoryChanged(by, message); }
        public void onRepositoryChanged(RepositoryChange listener) { repositoryChanges.add(listener); }
        public void runRequest(String call, RunRequest handler) { claim(runRequests, call, handler, "the run request " + call); }
        public void onNoRunner(NoRunner listener) { noRunner.add(listener); }
        public void workflowTasks(WorkflowTasks source) {
            synchronized (Modules.this) {
                if (workflowTasks != null) throw new IllegalStateException("Module " + module + " registers the workflow engine, which another module has");
                workflowTasks = source;
            }
        }
        public void definitionCheck(String suffix, DefinitionCheck check) { claim(definitionChecks, suffix, check, "the definition check for " + suffix); }
        public void requireFeature(String feature, String what) { cr.licensing.requireFeature(feature, what); }
        public void notifyTaskCreated(String taskId, String kind, String title, String assignee, java.time.Instant due, Map<String, Object> data) {
            cr.taskChannels.taskCreated(taskId, kind, title, assignee, due, data);
        }
        public void every(Duration delay, Duration period, Runnable job) { jobs.add(new Job(module, delay, period, job)); }
        public void defineSetting(String key, Object defaultValue, SettingCheck check) { Platform.defineModuleSetting(key, defaultValue, check); }
        public void taskKind(String kind, TaskKind rules) { claim(taskKinds, kind, rules, "the task kind " + kind); }
        public void triggerKind(String kind, String label, TriggerKind rules) { TriggerKinds.defineModuleKind(kind, label, rules); }
        public void mcpTools(McpTools tools) { mcpTools.add(tools); }
        public void templatePart(TemplatePart part) { templateParts.add(part); }
        public void jobPart(String key, JobPart part) { claim(jobParts, key, part, "the job part " + key); }
        public <T> void provide(Class<T> type, T service) { claim(services, type, service, "the service " + type.getName()); }
        public <T> T service(Class<T> type) { return Modules.this.service(type); }

        private <K, T> void claim(Map<K, T> map, K key, T value, String what) {
            if (map.putIfAbsent(key, value) != null) throw new IllegalStateException("Module " + module + " registers " + what + ", which another module has");
        }
    }
}
