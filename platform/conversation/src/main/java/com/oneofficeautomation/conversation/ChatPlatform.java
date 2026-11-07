package com.oneofficeautomation.conversation;

import com.oneofficeautomation.conversation.ChatEngine.ChatBot;

import java.util.List;
import java.util.Map;

/**
 * What One Chat needs from the product it runs in. The ONEOFFICEAUTOMATION Command Center implements it (Chat.java in
 * control-room); the conversation component never reaches into the host directly. Every method acts for one person
 * and the host applies its own permissions: the component only decides what to propose and when to ask.
 */
public interface ChatPlatform {

    /** The person speaking: user name, main role, and the address the message came from (for the audit trail). */
    record Person(String username, String role, String ip) {}

    /** The agents this person may run now, in a stable order. */
    List<ChatBot> agents(Person p);

    /** Whether this person may see their own runs ("what are my runs?"). */
    boolean canViewRuns(Person p);

    /** Starts the agent as this person (source chat) and returns the run id; throws {@link Refused} when the host will not. */
    String startRun(Person p, String path, Map<String, Object> inputs);

    /** The person's latest runs, newest first: [{run_id, agent, status}]. */
    List<Map<String, Object>> recentRuns(Person p, int limit);

    /** Puts an approval request for a high-impact agent in front of the approvers; returns its id. */
    String requestApproval(Person p, ApprovalRequest request);

    /** A notice for one person, outside the chat (the host's inbox). */
    void notify(String username, String title, String message, Map<String, Object> data);

    /** The person with this user name as they are now, or null when they no longer have an account. */
    Person person(String username);

    /** A host setting (chat_mode, chat_connection, ...); empty when unset. */
    String setting(String name);

    /** One model call through the host's AI gateway: {text, tool_calls: [{id, name, args}]}. */
    Map<String, Object> callModel(Person p, String connection, List<Map<String, Object>> messages, List<Map<String, Object>> tools, String purpose);

    /** One entry in the host's audit trail. */
    void audit(Person p, String action, String objectType, String objectId, Map<String, Object> details);

    /** A new unique id with this prefix (chat, cnf, ...). */
    String newId(String prefix);

    /** A stored chat (what {@link #saveSession} was given), or null when there is none with this id. */
    Map<String, Object> loadSession(String id);

    /** Stores a chat after each message, replacing the earlier copy; {@code data} holds plain JSON values only. */
    void saveSession(String id, String username, Map<String, Object> data);

    /** Drops stored chats not used since this time. */
    void deleteSessionsIdleSince(java.time.Instant cutoff);

    /**
     * Remembers that this chat started the run. When the run ends (success, failed or stopped) the host calls
     * {@link Conversation#runFinished} once for it.
     */
    void watchRun(String runId, String sessionId, String username);

    /** An R3 request waiting for someone other than the person who asked. */
    record ApprovalRequest(String sessionId, String agent, String agentName, Map<String, Object> inputs, String summary,
                           String confirmationId, String inputHash) {}

    /** The host would not start a run; {@code kind} is no_runner or refused, {@code message} is shown to the person. */
    final class Refused extends RuntimeException {
        private final String kind;

        public Refused(String kind, String message) { super(message); this.kind = kind; }

        public String kind() { return kind; }
    }
}
