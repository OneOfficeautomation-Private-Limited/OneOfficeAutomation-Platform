package com.oneofficeautomation.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Receives run events. Implementations: in-memory, console, JSON lines, Agent gRPC stream. */
public interface EventSink {
    void emit(RunEvent e);

    final class ListSink implements EventSink {
        private final List<RunEvent> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void emit(RunEvent e) { events.add(e); }
        public List<RunEvent> events() { synchronized (events) { return new ArrayList<>(events); } }
        public List<RunEvent> ofType(String t) { return events().stream().filter(e -> e.type().equals(t)).toList(); }
    }

    final class ConsoleSink implements EventSink {
        private final boolean verbose;
        public ConsoleSink(boolean verbose) { this.verbose = verbose; }
        @Override public void emit(RunEvent e) {
            if (!verbose && (e.type().equals("variable.set") || e.type().equals("step.started"))) return;
            String detail = switch (e.type()) {
                case "step.finished" -> e.data().get("action") + " (" + e.data().get("durationMs") + " ms)";
                case "step.failed" -> e.data().get("code") + ": " + e.data().get("message");
                case "log" -> String.valueOf(e.data().get("message"));
                case "run.finished" -> e.data().get("status") + " in " + e.data().get("durationMs") + " ms";
                case "run.started" -> String.valueOf(e.data().get("automation"));
                default -> Values.safeRepr(e.data());
            };
            System.err.println("  " + (e.stepId() != null ? "[" + e.stepId() + "] " : "") + e.type() + "  " + detail);
        }
    }
}
