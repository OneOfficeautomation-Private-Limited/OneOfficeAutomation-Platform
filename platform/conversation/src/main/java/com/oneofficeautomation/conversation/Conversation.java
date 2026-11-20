package com.oneofficeautomation.conversation;

import com.oneofficeautomation.conversation.ChatEngine.*;
import com.oneofficeautomation.conversation.ChatPlatform.ApprovalRequest;
import com.oneofficeautomation.conversation.ChatPlatform.Person;
import com.oneofficeautomation.conversation.ChatPlatform.Tool;
import com.oneofficeautomation.conversation.ChatSession.Pending;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * One Chat: people ask in plain words for an agent to run, and for the state of their runs. A {@link ChatEngine} turns
 * the words into a proposal; this class applies the rules, the same in every mode. Only the agents the host says the
 * person may run are offered; anything but an R0 agent waits for the person's yes on a summary this class writes, never
 * the engine; the run starts as that person. A confirmation is used once, so a repeated yes cannot start a second run.
 * R3 agents (money, bulk changes) go to the host's approvers after the yes: someone other than the person who asked
 * must approve, and only then does the run start, as the person who asked. Replies are in the person's console
 * language ({@link Messages}), kept with the chat.
 */
public final class Conversation {
    /** The built-in "my runs" action, offered like an agent. */
    public static final String MY_RUNS = "chat:my_runs";
    /** Administration tools' paths start with this (S3). */
    public static final String TOOL_PREFIX = "admin:";
    /** The chat_mode setting: deterministic (no model, the default), llm, or hybrid (deterministic first, the model when unsure). */
    public static final List<String> MODES = List.of("deterministic", "llm", "hybrid");

    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10), IDLE = Duration.ofHours(8);
    /** Yes and no in the console languages (and Hindi, which people often type in Latin letters). */
    static final Set<String> YES = Set.of("yes", "y", "yeah", "yep", "ok", "okay", "sure", "confirm", "go ahead", "do it", "proceed",
            "haan", "ha", "ji", "ja", "jawohl", "gerne", "oui", "d'accord", "si", "sí", "claro", "vale", "はい", "お願いします", "ええ");
    static final Set<String> NO = Set.of("no", "n", "nope", "cancel", "stop", "don't", "dont", "do not", "abort", "nahi", "na",
            "nein", "abbrechen", "non", "annuler", "cancelar", "いいえ", "キャンセル", "やめて");
    private static final ChatBot MY_RUNS_BOT = new ChatBot(MY_RUNS, "My runs", "The person's recent agent runs and their status.", "R0",
            List.of("what are my runs", "show my recent runs", "status of my runs", "did my run finish", "what did I run today", "my jobs"),
            List.of(), List.of(), List.of(), Map.of());

    private final ChatPlatform platform;
    private final Clock clock;
    private final ChatEngine deterministic, llm;
    private volatile Instant lastPurge = Instant.EPOCH;

    public Conversation(ChatPlatform platform, Clock clock) {
        this.platform = platform;
        this.clock = clock;
        this.deterministic = new DeterministicChat(clock);
        this.llm = new LlmChat(platform, clock);
    }

    /** The mode for this person: a per-role setting (chat_mode_by_role, role=mode lines) first, else chat_mode. */
    private ChatEngine engine(Person p) {
        return switch (modeFor(p)) {
            case "llm" -> llm;
            case "hybrid" -> new ChatEngine.Router(List.of(deterministic, llm), 0.6);
            default -> deterministic;
        };
    }

    /** chat_mode_by_role holds lines such as finance=deterministic and it=hybrid; the person's main role decides. */
    String modeFor(Person p) {
        for (String line : platform.setting("chat_mode_by_role").split("[\\n,;]")) {
            String[] kv = line.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(p.role()) && MODES.contains(kv[1].trim())) return kv[1].trim();
        }
        return platform.setting("chat_mode").trim();
    }

    /** As {@link #message(Person, String, String, String)}, keeping the chat's language (English for a new chat). */
    public Map<String, Object> message(Person p, String sessionId, String text) { return message(p, sessionId, text, null); }

    /**
     * Handles one message and returns the reply: session_id, kind (answer, question, confirm, started, cancelled,
     * not_understood, awaiting_approval, refused, no_runner), reply text, and the agent, inputs, run or task id where
     * they apply. A missing or someone else's session is CR.NOT_FOUND.
     *
     * @param lang the person's console language (en, de, fr, es, ja); null keeps the chat's
     */
    public Map<String, Object> message(Person p, String sessionId, String text, String lang) {
        if (text == null || text.isBlank()) throw new OoaException("CR.BAD_INPUT", "Write a message", null);
        purgeIdle();
        boolean fresh = sessionId == null || sessionId.isBlank();
        String id = fresh ? platform.newId("chat") : sessionId;
        synchronized (lock(id)) {
            ChatSession s = fresh ? new ChatSession(id, p.username(), clock.instant()) : load(id);
            if (s == null || !s.username.equals(p.username())) throw notFound(sessionId); // someone else's chat stays invisible
            if (lang != null && !lang.isBlank()) s.lang = Messages.language(lang);
            Instant at = clock.instant();
            s.lastUsed = at;
            s.person = p;
            s.history.add(Map.of("role", "user", "text", text, "at", at.toString()));
            Map<String, Object> out = respond(p, s, text.trim());
            out.put("session_id", s.id);
            Map<String, Object> said = new LinkedHashMap<>(Map.of("role", "assistant", "text", out.get("reply"), "kind", out.get("kind"), "at", clock.instant().toString()));
            if (out.get("run_id") != null) said.put("run_id", out.get("run_id"));
            if (out.get("task_id") != null) said.put("task_id", out.get("task_id"));
            // the card's details (S3): what a confirmation is about, and a tool's small table, so a reloaded chat shows them too
            for (String k : List.of("confirmation", "columns", "page")) if (out.get(k) != null) said.put(k, out.get(k));
            if (out.get("rows") instanceof List<?> rows) said.put("rows", new ArrayList<>(rows.subList(0, Math.min(20, rows.size()))));
            s.history.add(said);
            platform.saveSession(s.id, s.username, s.toMap());
            return out;
        }
    }

    /** The messages of one of the person's chats, oldest first. */
    public List<Map<String, Object>> history(String username, String sessionId) {
        ChatSession s = sessionId == null ? null : load(sessionId);
        if (s == null || !s.username.equals(username)) throw notFound(sessionId);
        return List.copyOf(s.history);
    }

    private static OoaException notFound(String sessionId) { return new OoaException("CR.NOT_FOUND", "Chat " + sessionId + " not found", "Start a new chat; this one has ended."); }

    /** One lock per chat on this node; the stored copy is read fresh under it, so the host's store is the truth. */
    private static Object lock(String id) { return ("ooa.chat:" + id).intern(); }

    private ChatSession load(String id) {
        Map<String, Object> stored = platform.loadSession(id);
        if (stored == null) return null;
        ChatSession s = ChatSession.fromMap(stored);
        return s.lastUsed.isBefore(clock.instant().minus(IDLE)) ? null : s;
    }

    /** Drops chats idle for longer than a working day, at most every ten minutes. */
    private void purgeIdle() {
        Instant now = clock.instant();
        if (now.isBefore(lastPurge.plus(Duration.ofMinutes(10)))) return;
        lastPurge = now;
        platform.deleteSessionsIdleSince(now.minus(IDLE));
    }

    /** A yes or no answer, whatever the language: lower case, letters and apostrophes only. */
    static String answerWord(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}' ]", "").trim().replaceAll("\\s+", " ");
    }

    private Map<String, Object> respond(Person p, ChatSession s, String text) {
        String lang = s.lang;
        Pending pending = s.pending;
        if (pending != null) {
            s.pending = null;
            String answer = answerWord(text);
            if (YES.contains(answer)) return confirm(p, s, pending);
            if (NO.contains(answer)) {
                platform.audit(p, "chat.decline", "bot", pending.bot().path(), Map.of("confirmation", pending.id()));
                return reply("cancelled", Messages.t(lang, "declined", pending.bot().name()));
            }
            // anything else is a new request; the confirmation lapses
        }
        Catalog catalog = catalog(p);
        List<ChatBot> bots = catalog.bots();
        Result r = engine(p).decide(s, bots, text);
        if (r == null) r = new Handoff("no engine answered", false);
        return switch (r) {
            case Reply x -> reply("answer", x.text());
            case Clarify c -> {
                Map<String, Object> m = reply("question", c.question());
                if (c.path() != null) { m.put("agent", c.path()); m.put("missing", c.missing()); }
                yield m;
            }
            case Handoff h when h.reason().startsWith("the model") -> reply("not_understood", Messages.t(lang, "model_failed", h.reason().substring(10)));
            case Handoff h -> reply("not_understood", bots.stream().filter(b -> !b.path().equals(MY_RUNS)).findFirst()
                    .map(b -> Messages.t(lang, "not_understood_example", b.name())).orElse(Messages.t(lang, "not_understood")));
            case Proposal pr -> propose(p, s, catalog, pr);
        };
    }

    /** What this person may reach in chat now, and the administration tools among it by path. */
    private record Catalog(List<ChatBot> bots, Map<String, Tool> tools) {}

    /**
     * The agents this person may run, the administration tools they may use and "my runs" when they may see runs, less
     * every tool group the settings set to Never (Settings > AI assistants > One Chat > Tools and permissions).
     */
    private Catalog catalog(Person p) {
        List<ChatBot> out = new ArrayList<>();
        Map<String, Tool> tools = new LinkedHashMap<>();
        if (!"never".equals(platform.policy(p, "agents"))) out.addAll(platform.agents(p));
        if (ADMIN_GROUPS.stream().anyMatch(g -> !"never".equals(platform.policy(p, g))))
            for (Tool tool : platform.adminTools(p))
                if (ADMIN_GROUPS.contains(tool.group()) && !"never".equals(platform.policy(p, tool.group()))) { out.add(tool.bot()); tools.put(tool.bot().path(), tool); }
        if (platform.canViewRuns(p) && !"never".equals(platform.policy(p, "read"))) out.add(MY_RUNS_BOT);
        return new Catalog(out, tools);
    }

    static final List<String> ADMIN_GROUPS = List.of("admin_read", "admin_change", "admin_high");

    /** The tool group a catalogue entry belongs to. */
    private static String groupOf(ChatBot bot, Catalog c) {
        if (bot.path().equals(MY_RUNS)) return "read";
        Tool tool = c.tools().get(bot.path());
        return tool != null ? tool.group() : "agents";
    }

    private Map<String, Object> propose(Person p, ChatSession s, Catalog catalog, Proposal pr) {
        String lang = s.lang;
        ChatBot bot = catalog.bots().stream().filter(b -> b.path().equals(pr.path())).findFirst().orElse(null);
        if (bot == null) return reply("refused", Messages.t(lang, "not_available"));
        String group = groupOf(bot, catalog), rule = platform.policy(p, group);
        if ("never".equals(rule)) return reply("refused", Messages.t(lang, "not_available"));
        Tool tool = catalog.tools().get(bot.path());
        // settings only make it stricter: Ask first waits for a yes even where the tool's risk would not
        boolean ask = bot.needsConfirmation() || rule.startsWith("ask");
        if (!ask) {
            if (bot.path().equals(MY_RUNS)) return myRuns(p, lang);
            if (tool != null) return useTool(p, lang, tool, pr.inputs(), null);
            return start(p, s.id, lang, bot, pr.inputs(), null);
        }

        Pending pending = new Pending(platform.newId("cnf"), bot, pr.inputs(), hash(pr.inputs()), clock.instant().plus(CONFIRMATION_TTL), pr.engine());
        s.pending = pending;
        platform.audit(p, "chat.propose", tool != null ? "tool" : "bot", bot.path(),
                Map.of("confirmation", pending.id(), "engine", pr.engine(), "inputs", new ArrayList<>(pr.inputs().keySet()), "input_hash", pending.inputHash()));
        String why = bot.needsConfirmation() ? null
                : rule.equals("ask_person") ? Messages.t(lang, "asked_person") : Messages.t(lang, "asked_policy", Messages.t(lang, "group_" + group));
        String question = Messages.t(lang, bot.needsApproval() ? (tool != null ? "confirm_r3_change" : "confirm_r3") : "confirm");
        Map<String, Object> m = reply("confirm", summary(lang, bot, pr.inputs()) + (why == null ? "" : "\n" + why) + "\n" + question);
        m.put("agent", bot.path());
        m.put("inputs", pr.inputs());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", pending.id());
        card.put("expires", pending.expires().toString());
        card.put("risk", bot.risk());
        card.put("name", bot.name());
        card.put("kind", tool != null ? "tool" : bot.path().equals(MY_RUNS) ? "read" : "agent");
        if (why != null) card.put("reason", why);
        if (tool != null && tool.undo() != null) card.put("undo", tool.undo());
        m.put("confirmation", card);
        return m;
    }

    private Map<String, Object> confirm(Person p, ChatSession s, Pending pending) {
        if (clock.instant().isAfter(pending.expires())) return reply("cancelled", Messages.t(s.lang, "expired"));
        // still allowed, by permissions and by the settings, at the moment of the yes
        Catalog c = catalog(p);
        boolean stillAllowed = c.bots().stream().anyMatch(b -> b.path().equals(pending.bot().path()));
        if (!stillAllowed) return reply("refused", Messages.t(s.lang, "no_longer", pending.bot().name()));
        if (pending.bot().needsApproval()) return requestApproval(p, s, pending);
        if (pending.bot().path().equals(MY_RUNS)) return myRuns(p, s.lang);
        Tool tool = c.tools().get(pending.bot().path());
        if (tool != null) return useTool(p, s.lang, tool, pending.inputs(), pending);
        return start(p, s.id, s.lang, pending.bot(), pending.inputs(), pending);
    }

    /**
     * Uses an administration tool as the person; the host does it with the console's own checks and records the change
     * in the Audit Trail. A look-up answers with its text and a small table; a change says what was done.
     */
    private Map<String, Object> useTool(Person p, String lang, Tool tool, Map<String, Object> inputs, Pending pending) {
        try {
            Map<String, Object> r = platform.runTool(p, tool.bot().path(), inputs);
            platform.audit(p, "chat.tool", "tool", tool.bot().path(), pending == null ? Map.of("inputs", new ArrayList<>(inputs.keySet()))
                    : Map.of("confirmation", pending.id(), "input_hash", pending.inputHash()));
            Map<String, Object> m = reply(tool.group().equals("admin_read") ? "answer" : "done", String.valueOf(r.getOrDefault("text", "")));
            m.put("agent", tool.bot().path());
            for (String k : List.of("rows", "columns")) if (r.get(k) != null) m.put(k, r.get(k));
            String page = r.get("page") instanceof String pg ? pg : tool.page();
            if (page != null) m.put("page", page);
            return m;
        } catch (ChatPlatform.Refused e) {
            return reply("refused", Messages.t(lang, "tool_refused", tool.bot().name(), e.getMessage()));
        } catch (OoaException e) {
            return reply("refused", Messages.t(lang, "tool_refused", tool.bot().name(), e.getMessage() + (e.suggestedFix() == null ? "" : " " + e.suggestedFix())));
        }
    }

    private Map<String, Object> requestApproval(Person p, ChatSession s, Pending pending) {
        ChatBot bot = pending.bot();
        // the approvers read the request in the Task inbox: in English, whatever language the person chats in
        String taskId = platform.requestApproval(p, new ApprovalRequest(s.id, bot.path(), bot.name(), pending.inputs(),
                summary("en", bot, pending.inputs()), pending.id(), pending.inputHash()));
        platform.audit(p, "chat.approval_requested", "task", taskId, Map.of("agent", bot.path(), "confirmation", pending.id(), "input_hash", pending.inputHash()));
        Map<String, Object> m = reply("awaiting_approval", Messages.t(s.lang, "awaiting", taskId, bot.name()));
        m.put("agent", bot.path());
        m.put("task_id", taskId);
        return m;
    }

    /**
     * The host calls this once an approver decides an approval request from {@link #requestApproval}. Approved: the run
     * starts as the person who asked, under their permissions at this moment. Either way they get a notice from the host
     * and, if their chat is still open, a message there, in their chat's language. Returns the run id when one started.
     *
     * @param request the stored request: requested_by, session_id, agent, inputs
     */
    @SuppressWarnings("unchecked")
    public String decided(String taskId, Map<String, Object> request, Person approver, boolean approved, String comment) {
        String requester = String.valueOf(request.get("requested_by")), path = String.valueOf(request.get("agent"));
        String sessionId = String.valueOf(request.get("session_id"));
        Map<String, Object> inputs = request.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of();
        ChatSession peek = load(sessionId);
        String lang = peek != null && peek.username.equals(requester) ? peek.lang : "en";
        String name = path.replaceAll("^.*/", "").replaceAll("\\.flow\\.json$", "");
        String message, runId = null;
        if (approved) {
            Person asker = platform.person(requester);
            if (asker == null) {
                message = Messages.t(lang, "approved_no_account", approver.username(), name, requester);
            } else if (path.startsWith(TOOL_PREFIX)) {
                // a high-impact administration change: done now, as the person who asked, if they still may
                Person as = new Person(asker.username(), asker.role(), approver.ip());
                Tool tool = "never".equals(platform.policy(as, "admin_high")) ? null
                        : platform.adminTools(as).stream().filter(x -> x.bot().path().equals(path)).findFirst().orElse(null);
                if (tool == null) message = Messages.t(lang, "requester_cannot", requester, name);
                else {
                    name = tool.bot().name();
                    Map<String, Object> done = useTool(as, lang, tool, inputs, null);
                    message = "refused".equals(done.get("kind")) ? Messages.t(lang, "approved_not_started", approver.username(), name, done.get("reply"))
                            : Messages.t(lang, "approved_done", approver.username(), name, done.get("reply"));
                }
            } else {
                ChatBot bot = platform.agents(asker).stream().filter(b -> b.path().equals(path)).findFirst().orElse(null);
                if (bot != null) name = bot.name();
                Map<String, Object> started = bot == null ? reply("refused", Messages.t(lang, "requester_cannot", requester, name))
                        : start(new Person(asker.username(), asker.role(), approver.ip()), sessionId, lang, bot, inputs, null);
                runId = (String) started.get("run_id");
                message = runId != null ? Messages.t(lang, "approved_started", approver.username(), name, runId)
                        : Messages.t(lang, "approved_not_started", approver.username(), name, started.get("reply"));
            }
        } else {
            message = comment == null || comment.isBlank() ? Messages.t(lang, "rejected", approver.username(), name)
                    : Messages.t(lang, "rejected_comment", approver.username(), name, comment);
        }
        platform.audit(approver, approved ? "chat.approved" : "chat.rejected", "task", taskId,
                runId == null ? Map.of("agent", path, "requested_by", requester) : Map.of("agent", path, "requested_by", requester, "run", runId));
        platform.notify(requester, Messages.t(lang, approved ? "notice_approved" : "notice_rejected", name), message,
                runId == null ? Map.of("agent", path) : Map.of("agent", path, "run_id", runId));
        synchronized (lock(sessionId)) {
            ChatSession s = load(sessionId);
            if (s != null && s.username.equals(requester)) {
                Map<String, Object> m = new LinkedHashMap<>(Map.of("role", "assistant", "text", message, "kind", approved ? "approval_approved" : "approval_rejected",
                        "at", clock.instant().toString()));
                if (runId != null) m.put("run_id", runId);
                s.history.add(m);
                platform.saveSession(s.id, s.username, s.toMap());
            }
        }
        return runId;
    }

    private Map<String, Object> start(Person p, String sessionId, String lang, ChatBot bot, Map<String, Object> inputs, Pending pending) {
        try {
            String runId = platform.startRun(p, bot.path(), inputs);
            if (pending != null) platform.audit(p, "chat.confirm", "run", runId, Map.of("confirmation", pending.id(), "input_hash", pending.inputHash()));
            platform.watchRun(runId, sessionId, p.username());
            Map<String, Object> m = reply("started", Messages.t(lang, "started", bot.name(), runId));
            m.put("agent", bot.path());
            m.put("run_id", runId);
            return m;
        } catch (ChatPlatform.Refused e) {
            return reply(e.kind(), e.kind().equals("no_runner") ? Messages.t(lang, "no_runner", bot.name())
                    : Messages.t(lang, "not_started", bot.name(), e.getMessage()));
        }
    }

    /**
     * The host calls this once a run this chat started has ended. Adds a message to the chat (if it is still kept), in
     * the chat's language: the results on success (the first few outputs), the reason and the suggested fix on failure.
     *
     * @param name   the agent's display name
     * @param status success, failed or stopped
     */
    public void runFinished(String sessionId, String username, String runId, String name, String status,
                            Map<String, Object> outputs, Map<String, Object> error) {
        synchronized (lock(sessionId)) {
            ChatSession s = load(sessionId);
            if (s == null || !s.username.equals(username)) return;
            String lang = s.lang;
            String text = switch (status) {
                case "success" -> outputs == null || outputs.isEmpty() ? Messages.t(lang, "run_success", name, runId)
                        : Messages.t(lang, "run_results", name, runId, outputs.entrySet().stream().limit(5)
                                .map(e -> e.getKey() + " = " + shorten(e.getValue())).collect(Collectors.joining(", ")) + (outputs.size() > 5 ? ", ..." : ""));
                case "stopped" -> Messages.t(lang, "run_stopped", name, runId);
                default -> error == null || error.get("message") == null ? Messages.t(lang, "run_failed", name, runId)
                        : Messages.t(lang, "run_failed_reason", name, runId, error.get("message")
                                + (error.get("suggestedFix") != null ? " " + error.get("suggestedFix") : ""));
            };
            s.history.add(new LinkedHashMap<>(Map.of("role", "assistant", "text", text, "kind", "run_" + status, "run_id", runId, "at", clock.instant().toString())));
            platform.saveSession(s.id, s.username, s.toMap());
        }
    }

    private static String shorten(Object v) {
        String s = String.valueOf(v);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private Map<String, Object> myRuns(Person p, String lang) {
        List<Map<String, Object>> runs = platform.recentRuns(p, 5);
        if (runs.isEmpty()) return reply("answer", Messages.t(lang, "no_runs"));
        Map<String, Object> m = reply("answer", Messages.t(lang, "recent_runs") + "\n" + runs.stream()
                .map(r -> "- " + r.get("run_id") + " | " + String.valueOf(r.get("agent")).replaceAll("\\.flow\\.json$", "") + " | " + r.get("status"))
                .collect(Collectors.joining("\n")));
        m.put("runs", runs);
        return m;
    }

    /** The component's own words for what will run, so the person confirms what actually runs. */
    static String summary(String lang, ChatBot bot, Map<String, Object> inputs) {
        boolean tool = bot.path().startsWith(TOOL_PREFIX);
        if (inputs.isEmpty()) return Messages.t(lang, tool ? "summary_tool" : "summary", bot.name());
        return Messages.t(lang, tool ? "summary_tool_with" : "summary_with", bot.name(), inputs.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining(", ")));
    }

    private static Map<String, Object> reply(String kind, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("reply", text);
        return m;
    }

    private static String hash(Map<String, Object> inputs) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(JsonLite.write(new TreeMap<>(inputs)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
