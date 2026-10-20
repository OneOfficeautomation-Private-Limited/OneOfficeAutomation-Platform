package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Windows services through the Service Control Manager (sc.exe). Starting/stopping needs administrator rights. */
@OoaPackage(id = "ooa.services", name = "Windows services", version = "1.0.0", description = "Get the status of, start, stop and restart Windows services.")
public final class ServicesPackage {
    private static final Pattern STATE = Pattern.compile("STATE\\s*:\\s*\\d+\\s+(\\w+)");

    private static void windows() {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Windows services exist only on Windows", "Run this agent on a Windows runner.");
    }

    private static void checkName(String name) {
        if (!name.matches("^[A-Za-z0-9_.\\- $]{1,256}$")) throw new ActionException("SERVICES.BAD_NAME", "Invalid service name '" + name + "'", "Use the service name shown by sc query, e.g. Spooler.");
    }

    static String status(ActionContext ctx, String name) {
        windows(); checkName(name);
        ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("sc.exe", "query", name), null, null, 30);
        if (r.stdout().contains("1060")) throw new ActionException("SERVICES.NOT_FOUND", "Service '" + name + "' does not exist", "Check the service name (not the display name).");
        Matcher m = STATE.matcher(r.stdout());
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private static void control(ActionContext ctx, String verb, String name, String wanted, int timeoutSeconds) {
        windows(); checkName(name);
        ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("sc.exe", verb, name), null, null, 30);
        String out = r.stdout();
        if (out.contains(" 5:") || out.contains("FAILED 5")) throw new ActionException("SERVICES.ACCESS_DENIED", "Access denied to " + verb + " '" + name + "'", "Run the agent with administrator rights.");
        if (r.exitCode() != 0 && !out.contains("1056") && !out.contains("1062"))
            throw new ActionException("SERVICES.FAILED", "sc " + verb + " " + name + " failed: " + ApplicationPackage.tail(out), null);
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (!status(ctx, name).equals(wanted)) {
            if (System.currentTimeMillis() > end) throw new ActionException("SERVICES.TIMEOUT", "'" + name + "' did not reach " + wanted + " within " + timeoutSeconds + " s", null, true);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            ctx.checkCancelled();
        }
    }

    @Action(id = "get_status", name = "Get service status", category = "Services", idempotent = true, description = "Return RUNNING, STOPPED, PAUSED, START_PENDING or STOP_PENDING.")
    @Output(name = "status", type = OoaType.STRING, description = "Service state.")
    public Map<String, Object> getStatus(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, example = "Spooler", description = "Service name.") String name) {
        return Map.of("status", status(ctx, name));
    }

    @Action(id = "start", name = "Start service", category = "Services", timeoutSeconds = 180, description = "Start a service and wait until it is running.")
    public Map<String, Object> start(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "How long to wait.") int t) {
        control(ctx, "start", name, "RUNNING", t); return Map.of();
    }

    @Action(id = "stop", name = "Stop service", category = "Services", timeoutSeconds = 180, description = "Stop a service and wait until it has stopped.")
    public Map<String, Object> stop(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "How long to wait.") int t) {
        control(ctx, "stop", name, "STOPPED", t); return Map.of();
    }

    @Action(id = "restart", name = "Restart service", category = "Services", timeoutSeconds = 360, description = "Stop then start a service.")
    public Map<String, Object> restart(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "How long to wait for each phase.") int t) {
        if (!status(ctx, name).equals("STOPPED")) control(ctx, "stop", name, "STOPPED", t);
        control(ctx, "start", name, "RUNNING", t);
        return Map.of();
    }

    /** Command used to pause (sc pause) or resume (sc continue) a service. */
    static List<String> pauseCommand(String name, boolean pause) {
        checkName(name);
        return List.of("sc.exe", pause ? "pause" : "continue", name);
    }

    private static void pauseOrResume(ActionContext ctx, String name, boolean pause, int timeoutSeconds) {
        String state = status(ctx, name);  // also checks Windows, the name and that the service exists
        String wanted = pause ? "PAUSED" : "RUNNING";
        if (state.equals(wanted)) return;
        if (state.equals("STOPPED"))
            throw new ActionException("SERVICES.NOT_RUNNING", "'" + name + "' is stopped, so it cannot be " + (pause ? "paused" : "resumed"), "Use Start service instead.");
        ProcessRunner.Result r = ProcessRunner.run(ctx, pauseCommand(name, pause), null, null, 30);
        String out = r.stdout();
        if (out.contains(" 5:") || out.contains("FAILED 5")) throw new ActionException("SERVICES.ACCESS_DENIED", "Access denied to " + (pause ? "pause" : "resume") + " '" + name + "'", "Run the agent with administrator rights.");
        if (out.contains("1052")) throw new ActionException("SERVICES.CANNOT_PAUSE", "'" + name + "' does not support pause and resume", "Use Stop service and Start service instead.");
        if (r.exitCode() != 0) throw new ActionException("SERVICES.FAILED", "sc " + (pause ? "pause" : "continue") + " " + name + " failed: " + ApplicationPackage.tail(out), null);
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (!status(ctx, name).equals(wanted)) {
            if (System.currentTimeMillis() > end) throw new ActionException("SERVICES.TIMEOUT", "'" + name + "' did not reach " + wanted + " within " + timeoutSeconds + " s", null, true);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            ctx.checkCancelled();
        }
    }

    @Action(id = "pause", name = "Pause service", category = "Services", timeoutSeconds = 180,
            description = "Pause a running service that supports pausing and wait until it is paused.")
    public Map<String, Object> pause(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "How long to wait.") int t) {
        pauseOrResume(ctx, name, true, t); return Map.of();
    }

    @Action(id = "resume", name = "Resume service", category = "Services", timeoutSeconds = 180,
            description = "Continue a paused service and wait until it is running again.")
    public Map<String, Object> resume(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "How long to wait.") int t) {
        pauseOrResume(ctx, name, false, t); return Map.of();
    }

    @Condition(id = "is_running", name = "Service is running", description = "True when the service state is RUNNING.")
    public boolean isRunning(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name) {
        return status(ctx, name).equals("RUNNING");
    }
}
