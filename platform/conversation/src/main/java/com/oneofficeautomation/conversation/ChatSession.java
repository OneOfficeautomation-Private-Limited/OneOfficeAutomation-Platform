package com.oneofficeautomation.conversation;

import com.oneofficeautomation.conversation.ChatEngine.ChatBot;
import com.oneofficeautomation.conversation.ChatPlatform.Person;

import java.time.Instant;
import java.util.*;

/**
 * One person's conversation: its messages, the engines' working state and a confirmation waiting for yes or no. It is
 * stored by the host after every message ({@link #toMap()}, plain JSON values), so a chat survives a restart and can
 * continue on another node of a cluster.
 */
public final class ChatSession {
    /** Messages kept with a stored chat, and characters kept of each; older and longer parts are dropped. */
    static final int KEEP_MESSAGES = 50, KEEP_CHARS = 1500;

    final String id, username;
    final List<Map<String, Object>> history = new ArrayList<>();
    /** Engine working state, such as inputs filled in so far; keyed by the engine; plain JSON values only. */
    final Map<String, Object> state = new HashMap<>();
    /** The engine that asked the last question, which gets the answer first. */
    String activeEngine;
    Pending pending;
    /** The person speaking, as signed in for the current message (not stored). */
    Person person;
    Instant lastUsed;

    ChatSession(String id, String username, Instant now) { this.id = id; this.username = username; this.lastUsed = now; }

    public String id() { return id; }

    /** A run waiting for the person's yes; bound to the agent and the exact inputs shown. */
    record Pending(String id, ChatBot bot, Map<String, Object> inputs, String inputHash, Instant expires, String engine) {}

    /** The chat as plain JSON values, for the host to store. */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> h : history.subList(Math.max(0, history.size() - KEEP_MESSAGES), history.size())) {
            Map<String, Object> c = new LinkedHashMap<>(h);
            if (c.get("text") instanceof String t && t.length() > KEEP_CHARS) c.put("text", t.substring(0, KEEP_CHARS) + "...");
            kept.add(c);
        }
        m.put("history", kept);
        m.put("state", state);
        if (activeEngine != null) m.put("active_engine", activeEngine);
        if (pending != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", pending.id());
            p.put("agent", pending.bot().path());
            p.put("name", pending.bot().name());
            p.put("risk", pending.bot().risk());
            p.put("inputs", pending.inputs());
            p.put("input_hash", pending.inputHash());
            p.put("expires", pending.expires().toString());
            p.put("engine", pending.engine());
            m.put("pending", p);
        }
        m.put("last_used", lastUsed.toString());
        return m;
    }

    /** A chat back from what {@link #toMap()} stored. */
    @SuppressWarnings("unchecked")
    static ChatSession fromMap(Map<String, Object> m) {
        ChatSession s = new ChatSession(String.valueOf(m.get("id")), String.valueOf(m.get("username")), Instant.parse(String.valueOf(m.get("last_used"))));
        if (m.get("history") instanceof List<?> h) for (Object o : h) if (o instanceof Map<?, ?> msg) s.history.add((Map<String, Object>) msg);
        if (m.get("state") instanceof Map<?, ?> st) s.state.putAll((Map<String, Object>) st);
        s.activeEngine = m.get("active_engine") instanceof String a ? a : null;
        if (m.get("pending") instanceof Map<?, ?> p) {
            ChatBot bot = new ChatBot(String.valueOf(p.get("agent")), String.valueOf(p.get("name")), "", String.valueOf(p.get("risk")),
                    List.of(), List.of(), List.of(), List.of(), Map.of());
            s.pending = new Pending(String.valueOf(p.get("id")), bot, p.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of(),
                    String.valueOf(p.get("input_hash")), Instant.parse(String.valueOf(p.get("expires"))), String.valueOf(p.get("engine")));
        }
        return s;
    }
}
