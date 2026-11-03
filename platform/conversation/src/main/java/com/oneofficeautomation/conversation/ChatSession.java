package com.oneofficeautomation.conversation;

import com.oneofficeautomation.conversation.ChatEngine.ChatBot;
import com.oneofficeautomation.conversation.ChatPlatform.Person;

import java.time.Instant;
import java.util.*;

/** One person's conversation: its messages, the engines' working state and a confirmation waiting for yes or no. */
public final class ChatSession {
    final String id, username;
    final List<Map<String, Object>> history = new ArrayList<>();
    /** Engine working state, such as inputs filled in so far; keyed by the engine. */
    final Map<String, Object> state = new HashMap<>();
    /** The engine that asked the last question, which gets the answer first. */
    String activeEngine;
    Pending pending;
    /** The person speaking, as signed in for the current message. */
    Person person;
    Instant lastUsed;

    ChatSession(String id, String username, Instant now) { this.id = id; this.username = username; this.lastUsed = now; }

    public String id() { return id; }

    /** A run waiting for the person's yes; bound to the agent and the exact inputs shown. */
    record Pending(String id, ChatBot bot, Map<String, Object> inputs, String inputHash, Instant expires, String engine) {}
}
