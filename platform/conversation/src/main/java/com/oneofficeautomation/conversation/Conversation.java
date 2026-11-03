package com.oneofficeautomation.conversation;

import com.oneofficeautomation.conversation.ChatEngine.*;
import com.oneofficeautomation.conversation.ChatPlatform.ApprovalRequest;
import com.oneofficeautomation.conversation.ChatPlatform.Person;
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
 * must approve, and only then does the run start, as the person who asked.
 */
public final class Conversation {
    /** The built-in "my runs" action, offered like an agent. */
    public static final String MY_RUNS = "chat:my_runs";
    /** The chat_mode setting: deterministic (no model, the default), llm, or hybrid (deterministic first, the model when unsure). */
    public static final List<String> MODES = List.of("deterministic", "llm", "hybrid");

    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10), IDLE = Duration.ofHours(8);
    private static final Set<String> YES = Set.of("yes", "y", "yeah", "yep", "ok", "okay", "sure", "confirm", "go ahead", "do it", "proceed", "haan", "ha", "ji");
    private static final Set<String> NO = Set.of("no", "n", "nope", "cancel", "stop", "don't", "dont", "do not", "abort", "nahi", "na");
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

    private ChatEngine engine() {
        return switch (platform.setting("chat_mode").trim()) {
            case "llm" -> llm;
            case "hybrid" -> new ChatEngine.Router(List.of(deterministic, llm), 0.6);
            default -> deterministic;
        };
    }

    /**
     * Handles one message and returns the reply: session_id, kind (answer, question, confirm, started, cancelled,
     * not_understood, awaiting_approval, refused, no_runner), reply text, and the agent, inputs, run or task id where
     * they apply. A missing or someone else's session is CR.NOT_FOUND.
     */
    public Map<String, Object> message(Person p, String sessionId, String text) {
        if (text == null || text.isBlank()) throw new OoaException("CR.BAD_INPUT", "Write a message", null);
        purgeIdle();
        boolean fresh = sessionId == null || sessionId.isBlank();
        String id = fresh ? platform.newId("chat") : sessionId;
        synchronized (lock(id)) {
            ChatSession s = fresh ? new ChatSession(id, p.username(), clock.instant()) : load(id);
            if (s == null || !s.username.equals(p.username())) throw notFound(sessionId); // someone else's chat stays invisible
            Instant at = clock.instant();
            s.lastUsed = at;
            s.person = p;
            s.history.add(Map.of("role", "user", "text", text, "at", at.toString()));
            Map<String, Object> out = respond(p, s, text.trim());
            out.put("session_id", s.id);
            Map<String, Object> said = new LinkedHashMap<>(Map.of("role", "assistant", "text", out.get("reply"), "kind", out.get("kind"), "at", clock.instant().toString()));
            if (out.get("run_id") != null) said.put("run_id", out.get("run_id"));
            if (out.get("task_id") != null) said.put("task_id", out.get("task_id"));
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

    private static OoaException notFound(String sessionId) { return new OoaException("CR.NOT_FOUND", "Chat " + sessionId + " not found"); }

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

    private Map<String, Object> respond(Person p, ChatSession s, String text) {
        Pending pending = s.pending;
        if (pending != null) {
            s.pending = null;
            String answer = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", "").trim();
            if (YES.contains(answer)) return confirm(p, s, pending);
            if (NO.contains(answer)) {
                platform.audit(p, "chat.decline", "bot", pending.bot().path(), Map.of("confirmation", pending.id()));
                return reply("cancelled", "OK, I won't run " + pending.bot().name() + ".");
            }
            // anything else is a new request; the confirmation lapses
        }
        List<ChatBot> bots = catalog(p);
        Result r = engine().decide(s, bots, text);
        if (r == null) r = new Handoff("no engine answered", false);
        return switch (r) {
            case Reply x -> reply("answer", x.text());
            case Clarify c -> {
                Map<String, Object> m = reply("question", c.question());
                if (c.path() != null) { m.put("agent", c.path()); m.put("missing", c.missing()); }
                yield m;
            }
            case Handoff h when h.reason().startsWith("the model") -> reply("not_understood", "I couldn't get an answer from the AI model ("
                    + h.reason().substring(10) + "). Try again, or ask for an agent by its name.");
            case Handoff h -> reply("not_understood", "Sorry, I didn't understand that. Ask for an agent by its name"
                    + bots.stream().filter(b -> !b.path().equals(MY_RUNS)).findFirst().map(b -> ", for example \"" + b.name() + "\"").orElse("")
                    + ", or ask \"what are my runs?\".");
            case Proposal pr -> propose(p, s, bots, pr);
        };
    }

    /** The agents this person may run, plus "my runs" when they may see runs. */
    private List<ChatBot> catalog(Person p) {
        List<ChatBot> out = new ArrayList<>(platform.agents(p));
        if (platform.canViewRuns(p)) out.add(MY_RUNS_BOT);
        return out;
    }

    private Map<String, Object> propose(Person p, ChatSession s, List<ChatBot> bots, Proposal pr) {
        ChatBot bot = bots.stream().filter(b -> b.path().equals(pr.path())).findFirst().orElse(null);
        if (bot == null) return reply("refused", "That agent is not available to you in chat.");
        if (bot.path().equals(MY_RUNS)) return myRuns(p);
        if (!bot.needsConfirmation()) return start(p, s.id, bot, pr.inputs(), null);

        Pending pending = new Pending(platform.newId("cnf"), bot, pr.inputs(), hash(pr.inputs()), clock.instant().plus(CONFIRMATION_TTL), pr.engine());
        s.pending = pending;
        platform.audit(p, "chat.propose", "bot", bot.path(),
                Map.of("confirmation", pending.id(), "engine", pr.engine(), "inputs", new ArrayList<>(pr.inputs().keySet()), "input_hash", pending.inputHash()));
        Map<String, Object> m = reply("confirm", summary(bot, pr.inputs()) + (bot.needsApproval()
                ? "\nThis is a high-impact (R3) agent: after your yes, someone other than you must approve it in the Task inbox before it runs.\nShall I ask for approval? (yes/no)"
                : "\nShall I go ahead? (yes/no)"));
        m.put("agent", bot.path());
        m.put("inputs", pr.inputs());
        m.put("confirmation", Map.of("id", pending.id(), "expires", pending.expires().toString(), "risk", bot.risk()));
        return m;
    }

    private Map<String, Object> confirm(Person p, ChatSession s, Pending pending) {
        if (clock.instant().isAfter(pending.expires())) return reply("cancelled", "That confirmation expired. Ask again and I'll prepare it afresh.");
        boolean stillAllowed = platform.agents(p).stream().anyMatch(b -> b.path().equals(pending.bot().path()));
        if (!stillAllowed) return reply("refused", "You can no longer run " + pending.bot().name() + ".");
        if (pending.bot().needsApproval()) return requestApproval(p, s, pending);
        return start(p, s.id, pending.bot(), pending.inputs(), pending);
    }

    private Map<String, Object> requestApproval(Person p, ChatSession s, Pending pending) {
        ChatBot bot = pending.bot();
        String taskId = platform.requestApproval(p, new ApprovalRequest(s.id, bot.path(), bot.name(), pending.inputs(),
                summary(bot, pending.inputs()), pending.id(), pending.inputHash()));
        platform.audit(p, "chat.approval_requested", "task", taskId, Map.of("agent", bot.path(), "confirmation", pending.id(), "input_hash", pending.inputHash()));
        Map<String, Object> m = reply("awaiting_approval", "I've asked for approval (task " + taskId + "). " + bot.name()
                + " runs once someone other than you approves it in the Task inbox; I'll tell you here.");
        m.put("agent", bot.path());
        m.put("task_id", taskId);
        return m;
    }

    /**
     * The host calls this once an approver decides an approval request from {@link #requestApproval}. Approved: the run
     * starts as the person who asked, under their permissions at this moment. Either way they get a notice from the host
     * and, if their chat is still open, a message there. Returns the run id when one started.
     *
     * @param request the stored request: requested_by, session_id, agent, inputs
     */
    @SuppressWarnings("unchecked")
    public String decided(String taskId, Map<String, Object> request, Person approver, boolean approved, String comment) {
        String requester = String.valueOf(request.get("requested_by")), path = String.valueOf(request.get("agent"));
        Map<String, Object> inputs = request.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of();
        String name = path.replaceAll("\\.flow\\.json$", "");
        String message, runId = null;
        if (approved) {
            Person asker = platform.person(requester);
            if (asker == null) {
                message = approver.username() + " approved " + name + ", but " + requester + " no longer has an account, so it did not start.";
            } else {
                ChatBot bot = platform.agents(asker).stream().filter(b -> b.path().equals(path)).findFirst().orElse(null);
                Map<String, Object> started = bot == null ? reply("refused", requester + " can no longer run " + name + ".")
                        : start(new Person(asker.username(), asker.role(), approver.ip()), String.valueOf(request.get("session_id")), bot, inputs, null);
                runId = (String) started.get("run_id");
                message = runId != null ? approver.username() + " approved " + name + "; it started as run " + runId + "."
                        : approver.username() + " approved " + name + ", but it did not start: " + started.get("reply");
            }
        } else {
            message = approver.username() + " rejected " + name + (comment == null || comment.isBlank() ? "." : ": " + comment);
        }
        platform.audit(approver, approved ? "chat.approved" : "chat.rejected", "task", taskId,
                runId == null ? Map.of("agent", path, "requested_by", requester) : Map.of("agent", path, "requested_by", requester, "run", runId));
        platform.notify(requester, (approved ? "Approved: " : "Rejected: ") + name, message,
                runId == null ? Map.of("agent", path) : Map.of("agent", path, "run_id", runId));
        String sessionId = String.valueOf(request.get("session_id"));
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

    private Map<String, Object> start(Person p, String sessionId, ChatBot bot, Map<String, Object> inputs, Pending pending) {
        try {
            String runId = platform.startRun(p, bot.path(), inputs);
            if (pending != null) platform.audit(p, "chat.confirm", "run", runId, Map.of("confirmation", pending.id(), "input_hash", pending.inputHash()));
            platform.watchRun(runId, sessionId, p.username());
            Map<String, Object> m = reply("started", "Started " + bot.name() + " (run " + runId + "). I'll tell you here when it finishes.");
            m.put("agent", bot.path());
            m.put("run_id", runId);
            return m;
        } catch (ChatPlatform.Refused e) {
            return reply(e.kind(), e.getMessage());
        }
    }

    /**
     * The host calls this once a run this chat started has ended. Adds a message to the chat (if it is still kept): the
     * results on success (the first few outputs), the reason and the suggested fix on failure.
     *
     * @param name   the agent's display name
     * @param status success, failed or stopped
     */
    public void runFinished(String sessionId, String username, String runId, String name, String status,
                            Map<String, Object> outputs, Map<String, Object> error) {
        String text = switch (status) {
            case "success" -> name + " (run " + runId + ") finished." + (outputs == null || outputs.isEmpty() ? "" : " Results: "
                    + outputs.entrySet().stream().limit(5).map(e -> e.getKey() + " = " + shorten(e.getValue())).collect(Collectors.joining(", "))
                    + (outputs.size() > 5 ? ", ..." : "") + ".");
            case "stopped" -> name + " (run " + runId + ") was stopped.";
            default -> name + " (run " + runId + ") failed" + (error == null || error.get("message") == null ? "." : ": " + error.get("message"))
                    + (error != null && error.get("suggestedFix") != null ? " " + error.get("suggestedFix") : "");
        };
        synchronized (lock(sessionId)) {
            ChatSession s = load(sessionId);
            if (s == null || !s.username.equals(username)) return;
            s.history.add(new LinkedHashMap<>(Map.of("role", "assistant", "text", text, "kind", "run_" + status, "run_id", runId, "at", clock.instant().toString())));
            platform.saveSession(s.id, s.username, s.toMap());
        }
    }

    private static String shorten(Object v) {
        String s = String.valueOf(v);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private Map<String, Object> myRuns(Person p) {
        List<Map<String, Object>> runs = platform.recentRuns(p, 5);
        if (runs.isEmpty()) return reply("answer", "You have no runs yet.");
        Map<String, Object> m = reply("answer", "Your recent runs:\n" + runs.stream()
                .map(r -> "- " + r.get("run_id") + " | " + String.valueOf(r.get("agent")).replaceAll("\\.flow\\.json$", "") + " | " + r.get("status"))
                .collect(Collectors.joining("\n")));
        m.put("runs", runs);
        return m;
    }

    /** The component's own words for what will run, so the person confirms what actually runs. */
    static String summary(ChatBot bot, Map<String, Object> inputs) {
        if (inputs.isEmpty()) return "I'll run " + bot.name() + ".";
        return "I'll run " + bot.name() + " with " + inputs.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining(", ")) + ".";
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
