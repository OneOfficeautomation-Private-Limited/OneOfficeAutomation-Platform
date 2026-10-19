package com.oneofficeautomation.cli;

import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent Studio's debugger: stops before steps that have a breakpoint, or after a step command (step into, over, out),
 * and holds the run until the developer continues. While stopped, the call stack and variables can be read and
 * variables changed. Breakpoints are step ids and apply in child agents too.
 */
final class StudioDebugger implements Runner.Debugger {
    enum Mode { RUN, STEP_INTO, STEP_OVER, STEP_OUT }

    private final Object lock = new Object();
    private final AtomicBoolean cancel;
    private final Set<String> breakpoints = new HashSet<>();
    private Mode mode;
    private int targetLevel;
    private Runner.Frame stopped;
    private String reason;

    StudioDebugger(Collection<String> breakpoints, boolean stopAtStart, AtomicBoolean cancel) {
        this.breakpoints.addAll(breakpoints);
        this.mode = stopAtStart ? Mode.STEP_INTO : Mode.RUN;
        this.cancel = cancel;
    }

    @Override public void beforeStep(Runner.Frame frame) {
        synchronized (lock) {
            boolean bp = breakpoints.contains(frame.stepId());
            boolean stop = bp || switch (mode) {
                case STEP_INTO -> true;
                case STEP_OVER -> frame.level() <= targetLevel;
                case STEP_OUT -> frame.level() < targetLevel;
                case RUN -> false;
            };
            if (!stop) return;
            stopped = frame;
            reason = bp ? "breakpoint" : "step";
            lock.notifyAll();
            while (stopped == frame && !cancel.get()) {
                try { lock.wait(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted"); }
            }
        }
    }

    /** continue, stepInto, stepOver or stepOut. */
    void command(String cmd) {
        synchronized (lock) {
            if (stopped == null) throw new OoaException("DEBUG.NOT_STOPPED", "The run is not stopped at a step");
            int level = stopped.level();
            switch (cmd) {
                case "continue" -> mode = Mode.RUN;
                case "stepInto" -> mode = Mode.STEP_INTO;
                case "stepOver" -> { mode = Mode.STEP_OVER; targetLevel = level; }
                case "stepOut" -> { mode = Mode.STEP_OUT; targetLevel = level; }
                default -> throw new OoaException("DEBUG.BAD_COMMAND", "Unknown debug command '" + cmd + "'", "Use continue, stepInto, stepOver or stepOut.");
            }
            stopped = null;
            lock.notifyAll();
        }
    }

    void breakpoints(Collection<String> ids) { synchronized (lock) { breakpoints.clear(); breakpoints.addAll(ids); } }

    void setVariable(String name, Object value) {
        synchronized (lock) {
            if (stopped == null) throw new OoaException("DEBUG.NOT_STOPPED", "Variables can be changed only while the run is stopped at a step");
            stopped.setVariable(name, value);
        }
    }

    /** Null while running; otherwise where the run is stopped, why, the call stack and the variables. */
    Map<String, Object> state() {
        synchronized (lock) {
            if (stopped == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepId", stopped.stepId());
            m.put("action", stopped.action());
            m.put("automation", stopped.automation());
            m.put("depth", stopped.depth());
            m.put("reason", reason);
            m.put("stack", stopped.stack());
            m.put("variables", stopped.variables());
            return m;
        }
    }

    /** Releases a stopped run (used when the run is stopped from Agent Studio). */
    void release() { synchronized (lock) { stopped = null; mode = Mode.RUN; lock.notifyAll(); } }
}
