package com.oneofficeautomation.controlroom;

import java.util.*;

/**
 * One conversational mode of One Chat: deterministic (no model), a self-hosted model or a frontier model. Every mode
 * returns the same small result, so the policy in {@link Chat} (permissions, confirmation, starting the run) never
 * depends on the mode. An engine only decides; it never starts anything.
 */
interface ChatEngine {

    /** Stable id: deterministic, oss-llm, frontier, hybrid. */
    String id();

    /** Decides what to do with one message, given the agents this person may run. */
    Result decide(Chat.Session session, List<ChatBot> bots, String text);

    sealed interface Result permits Reply, Clarify, Proposal, Handoff {}

    /** A plain answer; nothing runs. */
    record Reply(String text) implements Result {}

    /** More information is needed; {@code path} is the agent being filled in, or null before one is chosen. */
    record Clarify(String question, List<String> missing, String path) implements Result {
        public Clarify { missing = List.copyOf(missing); }
        static Clarify question(String q) { return new Clarify(q, List.of(), null); }
    }

    /** A proposal to run an agent with these inputs. Becomes a run only through {@link Chat}'s policy. */
    record Proposal(String path, Map<String, Object> inputs, double confidence, String engine) implements Result {
        public Proposal { inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs)); }
    }

    /** The engine cannot handle the message; {@code escalate} lets the router try the next engine. */
    record Handoff(String reason, boolean escalate) implements Result {}

    /**
     * An agent as One Chat sees it: its production flow's name, description and inputs, plus the optional
     * {@code ui.chat} block (examples, aliases, not_for, risk, prompts) that helps people reach it in plain words.
     *
     * @param risk R0 runs at once; R1 and R2 need the person's yes; R3 also needs a second person's approval in the Task inbox
     */
    record ChatBot(String path, String name, String description, String risk, List<String> examples, List<String> aliases,
                   List<String> notFor, List<Map<String, Object>> inputs, Map<String, String> prompts) {
        static final List<String> RISKS = List.of("R0", "R1", "R2", "R3");

        boolean needsConfirmation() { return !risk.equals("R0"); }
        boolean needsApproval() { return risk.equals("R3"); }

        /** Builds the chat view of a production flow. Agents without a risk class are treated as R2: always confirm. */
        @SuppressWarnings("unchecked")
        static ChatBot of(String path, Map<String, Object> flow) {
            Map<String, Object> chat = flow.get("ui") instanceof Map<?, ?> ui && ui.get("chat") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
            String risk = chat.get("risk") instanceof String r && RISKS.contains(r) ? r : "R2";
            Map<String, String> prompts = new LinkedHashMap<>();
            if (chat.get("prompts") instanceof Map<?, ?> p) p.forEach((k, v) -> prompts.put(String.valueOf(k), String.valueOf(v)));
            String name = flow.get("name") instanceof String n && !n.isBlank() ? n : path.replaceAll("\\.flow\\.json$", "");
            return new ChatBot(path, name, flow.get("description") instanceof String d ? d : "", risk, strings(chat.get("examples")),
                    strings(chat.get("aliases")), strings(chat.get("not_for")), BotTools.inputs(flow), prompts);
        }

        private static List<String> strings(Object o) {
            return o instanceof List<?> l ? l.stream().filter(Objects::nonNull).map(String::valueOf).toList() : List.of();
        }
    }

    /**
     * Mode E: tries engines from cheapest to most capable. A handoff that escalates, or a proposal under the threshold,
     * goes to the next engine; the engine that asked a question gets the answer first.
     */
    final class Router implements ChatEngine {
        private final List<ChatEngine> engines;
        private final double threshold;

        Router(List<ChatEngine> engines, double threshold) {
            if (engines.isEmpty()) throw new IllegalArgumentException("at least one engine");
            this.engines = List.copyOf(engines);
            this.threshold = threshold;
        }

        @Override public String id() { return "hybrid"; }

        @Override public Result decide(Chat.Session session, List<ChatBot> bots, String text) {
            Result fallback = null;
            for (ChatEngine e : order(session.activeEngine)) {
                Result r = e.decide(session, bots, text);
                boolean last = e == engines.getLast();
                if (r instanceof Handoff h && h.escalate()) { if (fallback == null) fallback = h; continue; }
                if (r instanceof Proposal p && p.confidence() < threshold && !last) { fallback = p; continue; }
                session.activeEngine = r instanceof Clarify ? e.id() : null;
                return r;
            }
            session.activeEngine = null;
            return fallback;
        }

        private List<ChatEngine> order(String active) {
            if (active == null) return engines;
            List<ChatEngine> out = new ArrayList<>();
            engines.stream().filter(e -> e.id().equals(active)).forEach(out::add);
            engines.stream().filter(e -> !e.id().equals(active)).forEach(out::add);
            return out;
        }
    }
}
