package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ChatEngine.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * One Chat: people ask in plain words for an agent to run, and for the state of their runs. A {@link ChatEngine} turns
 * the words into a proposal; this class applies the same rules as every other way of starting a run. Only agents with a
 * production version that the person may run (role and folder access) are offered; anything but an R0 agent waits for
 * the person's yes on a summary the Command Center writes, never the engine; the run starts as that person with source
 * "chat", so it shows in Agent Runs and the One Audit Trail. A confirmation is used once, so a repeated yes cannot start
 * a second run. R3 agents (money, bulk changes) are not started from chat until second-person approval is wired in.
 */
public final class Chat {
    /** The built-in "my runs" action, offered like an agent. */
    static final String MY_RUNS = "chat:my_runs";
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10), IDLE = Duration.ofHours(8);
    private static final Set<String> YES = Set.of("yes", "y", "yeah", "yep", "ok", "okay", "sure", "confirm", "go ahead", "do it", "proceed", "haan", "ha", "ji");
    private static final Set<String> NO = Set.of("no", "n", "nope", "cancel", "stop", "don't", "dont", "do not", "abort", "nahi", "na");
    private static final ChatBot MY_RUNS_BOT = new ChatBot(MY_RUNS, "My runs", "The person's recent agent runs and their status.", "R0",
            List.of("what are my runs", "show my recent runs", "status of my runs", "did my run finish", "what did I run today", "my jobs"),
            List.of(), List.of(), List.of(), Map.of());

    static final List<String> MODES = List.of("deterministic", "llm", "hybrid");

    private final ControlRoom cr;
    private final ChatEngine fixedEngine, deterministic, llm;
    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    Chat(ControlRoom cr, java.nio.file.Path dataDir) { this(cr, dataDir, Clock.systemDefaultZone()); }

    /** The mode follows the chat_mode setting: deterministic (no model, the default), llm, or hybrid (deterministic first, the model when unsure). */
    Chat(ControlRoom cr, java.nio.file.Path dataDir, Clock clock) {
        this.cr = cr; this.clock = clock; this.fixedEngine = null;
        this.deterministic = new DeterministicChat(clock);
        this.llm = new LlmChat(new ChatModel(cr, dataDir), clock, () -> String.valueOf(cr.platform.setting("chat_connection")).trim());
    }

    /** Always this engine, whatever the settings (tests). */
    Chat(ControlRoom cr, ChatEngine engine, Clock clock) { this.cr = cr; this.fixedEngine = engine; this.clock = clock; this.deterministic = null; this.llm = null; }

    private ChatEngine engine() {
        if (fixedEngine != null) return fixedEngine;
        return switch (String.valueOf(cr.platform.setting("chat_mode")).trim()) {
            case "llm" -> llm;
            case "hybrid" -> new ChatEngine.Router(List.of(deterministic, llm), 0.6);
            default -> deterministic;
        };
    }

    /** One person's conversation: its messages, the engines' working state and a confirmation waiting for yes or no. */
    static final class Session {
        final String id, username;
        final List<Map<String, Object>> history = new ArrayList<>();
        /** Engine working state, such as inputs filled in so far; keyed by the engine. */
        final Map<String, Object> state = new HashMap<>();
        /** The engine that asked the last question, which gets the answer first. */
        String activeEngine;
        Pending pending;
        /** The person speaking, as signed in for the current message. */
        User user;
        Instant lastUsed;

        Session(String id, String username, Instant now) { this.id = id; this.username = username; this.lastUsed = now; }
    }

    /** A run waiting for the person's yes; bound to the agent and the exact inputs shown. */
    record Pending(String id, ChatBot bot, Map<String, Object> inputs, String inputHash, Instant expires, String engine) {}

    /**
     * Handles one message and returns the reply: session_id, kind (answer, question, confirm, started, cancelled,
     * not_understood, needs_approval, refused, no_runner), reply text, and the agent, inputs or run id where they apply.
     */
    public Map<String, Object> message(User u, String sessionId, String text, String ip) {
        if (text == null || text.isBlank()) throw new OoaException("CR.BAD_INPUT", "Write a message", null);
        purgeIdle();
        Session s = sessionId == null || sessionId.isBlank() ? open(u) : sessions.get(sessionId);
        if (s == null || !s.username.equals(u.username())) throw notFound("Chat " + sessionId); // someone else's chat stays invisible
        synchronized (s) {
            Instant at = clock.instant();
            s.lastUsed = at;
            s.user = u;
            s.history.add(Map.of("role", "user", "text", text, "at", at.toString()));
            Map<String, Object> out = respond(u, s, text.trim(), ip);
            out.put("session_id", s.id);
            s.history.add(Map.of("role", "assistant", "text", out.get("reply"), "kind", out.get("kind"), "at", clock.instant().toString()));
            return out;
        }
    }

    /** The messages of one of the person's chats, oldest first. */
    public List<Map<String, Object>> history(User u, String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.username.equals(u.username())) throw notFound("Chat " + sessionId);
        synchronized (s) { return List.copyOf(s.history); }
    }

    private Session open(User u) {
        Session s = new Session(id("chat"), u.username(), clock.instant());
        sessions.put(s.id, s);
        return s;
    }

    private void purgeIdle() {
        Instant cutoff = clock.instant().minus(IDLE);
        sessions.values().removeIf(s -> s.lastUsed.isBefore(cutoff));
    }

    private Map<String, Object> respond(User u, Session s, String text, String ip) {
        Pending p = s.pending;
        if (p != null) {
            s.pending = null;
            String answer = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", "").trim();
            if (YES.contains(answer)) return confirm(u, p, ip);
            if (NO.contains(answer)) {
                cr.audit.record(u.username(), "chat.decline", "bot", p.bot().path(), Map.of("confirmation", p.id()), "ok", ip);
                return reply("cancelled", "OK, I won't run " + p.bot().name() + ".");
            }
            // anything else is a new request; the confirmation lapses
        }
        List<ChatBot> bots = catalog(u);
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
            case Proposal pr -> propose(u, s, bots, pr, ip);
        };
    }

    /** The agents this person may run from chat: production versions they have run access to, plus "my runs". */
    @SuppressWarnings("unchecked")
    List<ChatBot> catalog(User u) {
        List<ChatBot> out = new ArrayList<>();
        if (cr.can(u, "runs.start")) {
            for (Map<String, Object> b : cr.db.query("SELECT path FROM bots WHERE production_version IS NOT NULL AND deleted_at IS NULL ORDER BY path")) {
                String path = (String) b.get("path");
                if (!path.endsWith(".flow.json")) continue;
                try {
                    cr.folders.require(u.username(), u.role(), path, "run", "run agents");
                    out.add(ChatBot.of(path, (Map<String, Object>) cr.content(path, "production").get("flow")));
                } catch (OoaException e) { /* no access, or an unreadable version: not offered */ }
            }
        }
        if (cr.can(u, "runs.view")) out.add(MY_RUNS_BOT);
        return out;
    }

    private Map<String, Object> propose(User u, Session s, List<ChatBot> bots, Proposal pr, String ip) {
        ChatBot bot = bots.stream().filter(b -> b.path().equals(pr.path())).findFirst().orElse(null);
        if (bot == null) return reply("refused", "That agent is not available to you in chat.");
        if (bot.path().equals(MY_RUNS)) return myRuns(u);
        if (bot.needsApproval())
            return reply("needs_approval", bot.name() + " is a high-impact (R3) agent. Starting it from chat needs a second person's approval, "
                    + "which One Chat does not offer yet; start it from One Command Center.");
        if (!bot.needsConfirmation()) return start(u, bot, pr.inputs(), null, ip);

        Pending p = new Pending(id("cnf"), bot, pr.inputs(), hash(pr.inputs()), clock.instant().plus(CONFIRMATION_TTL), pr.engine());
        s.pending = p;
        cr.audit.record(u.username(), "chat.propose", "bot", bot.path(),
                Map.of("confirmation", p.id(), "engine", pr.engine(), "inputs", new ArrayList<>(pr.inputs().keySet()), "input_hash", p.inputHash()), "ok", ip);
        Map<String, Object> m = reply("confirm", summary(bot, pr.inputs()) + "\nShall I go ahead? (yes/no)");
        m.put("agent", bot.path());
        m.put("inputs", pr.inputs());
        m.put("confirmation", Map.of("id", p.id(), "expires", p.expires().toString(), "risk", bot.risk()));
        return m;
    }

    private Map<String, Object> confirm(User u, Pending p, String ip) {
        if (clock.instant().isAfter(p.expires())) return reply("cancelled", "That confirmation expired. Ask again and I'll prepare it afresh.");
        ChatBot now = catalog(u).stream().filter(b -> b.path().equals(p.bot().path())).findFirst().orElse(null);
        if (now == null) return reply("refused", "You can no longer run " + p.bot().name() + ".");
        return start(u, p.bot(), p.inputs(), p, ip);
    }

    private Map<String, Object> start(User u, ChatBot bot, Map<String, Object> inputs, Pending p, String ip) {
        String target = String.valueOf(cr.platform.setting("chat_runner")).trim();
        String device = null, pool = null;
        if (!target.isEmpty() && (target.startsWith("pool_") || cr.db.one("SELECT id FROM device_pools WHERE id = ?", target) != null)) pool = target;
        else if (!target.isEmpty()) device = target;
        else device = cr.platform.onlineRunner();
        if (device == null && pool == null) return reply("no_runner", "No runner is online to run " + bot.name() + " right now. Try again later.");
        try {
            String runId = (String) cr.startRun(u.username(), u.role(), bot.path(), device, pool, inputs, "chat", ip, 5, null).get("run_id");
            if (p != null) cr.audit.record(u.username(), "chat.confirm", "run", runId, Map.of("confirmation", p.id(), "input_hash", p.inputHash()), "ok", ip);
            Map<String, Object> m = reply("started", "Started " + bot.name() + " (run " + runId + "). Ask \"what are my runs?\" to see how it is going.");
            m.put("agent", bot.path());
            m.put("run_id", runId);
            return m;
        } catch (OoaException e) {
            return reply("refused", "The Command Center did not start " + bot.name() + ": " + e.getMessage()
                    + (e.suggestedFix() == null ? "" : " " + e.suggestedFix()));
        }
    }

    private Map<String, Object> myRuns(User u) {
        List<Map<String, Object>> runs = cr.db.query("SELECT id, bot_path, status, created FROM runs WHERE requested_by = ? ORDER BY created DESC LIMIT ?", u.username(), 5);
        if (runs.isEmpty()) return reply("answer", "You have no runs yet.");
        Map<String, Object> m = reply("answer", "Your recent runs:\n" + runs.stream()
                .map(r -> "- " + r.get("id") + " | " + String.valueOf(r.get("bot_path")).replaceAll("\\.flow\\.json$", "") + " | " + r.get("status"))
                .collect(Collectors.joining("\n")));
        m.put("runs", runs.stream().map(r -> Map.of("run_id", r.get("id"), "agent", r.get("bot_path"), "status", r.get("status"))).toList());
        return m;
    }

    /** The Command Center's own words for what will run, so the person confirms what actually runs. */
    private static String summary(ChatBot bot, Map<String, Object> inputs) {
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
