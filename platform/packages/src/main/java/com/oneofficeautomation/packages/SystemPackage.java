package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.*;

/** System information and power actions. Power actions require confirm = true so they can never run by accident. */
@OoaPackage(id = "ooa.system", name = "System", version = "1.0.0", description = "Read environment variables and runner information; lock, sign out, restart or shut down the runner.")
public final class SystemPackage {

    @Action(id = "get_environment_variable", name = "Get environment variable", category = "System", idempotent = true,
            description = "Read an environment variable of the runner; variables that look like secrets are refused.",
            panel = "name, [Use a fallback value: default]")
    @Output(name = "value", type = OoaType.STRING, description = "Value, or the default when not set.")
    public Map<String, Object> getEnv(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Variable name, e.g. COMPUTERNAME.") String name,
            @Input(name = "default", type = OoaType.STRING, required = false, description = "Value when the variable is not set.") String def) {
        if (name.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|SECRET|TOKEN|API_KEY|PRIVATE).*"))
            throw new ActionException("SYSTEM.SECRET_VARIABLE", "Reading '" + name + "' is blocked because it looks like a secret",
                    "Store secrets in the credential vault and use a Credential variable.");
        // a value set earlier in this run (Set environment variable, process scope) wins; empty means removed
        if (ctx.sessions() != null && ctx.sessions().get(ProcessRunner.RUN_ENV_SESSION) instanceof Map<?, ?> runEnv && runEnv.containsKey(name)) {
            String set = String.valueOf(runEnv.get(name));
            return Out.of("value", set.isEmpty() ? def : set);
        }
        String v = System.getenv(name);
        return Out.of("value", v != null ? v : def);
    }

    static boolean secretName(String name) {
        return name.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|PASSWD|SECRET|TOKEN|API_KEY|APIKEY|PRIVATE|CREDENTIAL).*");
    }

    @Action(id = "get_environment_variables", name = "Get all environment variables", category = "System", idempotent = true,
            description = "Return every environment variable of the runner as a dictionary; variables that look like secrets are left out.")
    @Output(name = "variables", type = OoaType.DICTIONARY, description = "Variable name to value, sorted by name.")
    @Output(name = "hidden_count", type = OoaType.NUMBER, description = "How many secret-looking variables were left out.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEnvAll(ActionContext ctx) {
        Map<String, Object> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int hidden = 0;
        Map<String, String> all = new HashMap<>(System.getenv());
        if (ctx.sessions() != null && ctx.sessions().get(ProcessRunner.RUN_ENV_SESSION) instanceof Map<?, ?> m)
            ((Map<String, String>) m).forEach((k, v) -> { if (v == null || v.isEmpty()) all.remove(k); else all.put(k, v); });
        for (var e : all.entrySet()) {
            if (secretName(e.getKey())) { hidden++; continue; }
            out.put(e.getKey(), ctx.redact(e.getValue()));
        }
        return Out.of("variables", new LinkedHashMap<>(out), "hidden_count", BigDecimal.valueOf(hidden));
    }

    /** PowerShell that stores a user-level variable; name and value arrive through environment variables. */
    static final String SET_USER_ENV_SCRIPT = "[Environment]::SetEnvironmentVariable($env:OOA_ENV_NAME, $env:OOA_ENV_VALUE, 'User')";

    @Action(id = "set_environment_variable", name = "Set environment variable", category = "System",
            description = "Set an environment variable for programs this run starts (process scope) or permanently for the runner's user (user scope, Windows).",
            panel = "name, value, scope")
    @SuppressWarnings("unchecked")
    public Map<String, Object> setEnv(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, example = "REPORT_FOLDER", description = "Variable name: letters, digits and underscores.") String name,
            @Input(name = "value", type = OoaType.STRING, required = false, description = "Value; empty removes the variable.") String value,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"process\"", enumValues = {"process", "user"},
                    description = "process: programs and scripts started later in this run; user: saved for the runner's Windows user.") String scope) {
        if (name == null || !name.matches("^[A-Za-z_][A-Za-z0-9_.]{0,254}$"))
            throw new ActionException("SYSTEM.BAD_VARIABLE_NAME", "Invalid environment variable name '" + name + "'", "Use letters, digits and underscores, e.g. REPORT_FOLDER.");
        if (secretName(name))
            throw new ActionException("SYSTEM.SECRET_VARIABLE", "Setting '" + name + "' is blocked because it looks like a secret",
                    "Pass secrets to actions as Credential variables instead of environment variables.");
        String v = value == null ? "" : value;
        if ("user".equals(scope)) {
            if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "User-scope variables are supported on Windows only", "Use scope process, or set the variable in the runner's shell profile.");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("OOA_ENV_NAME", name);
            env.put("OOA_ENV_VALUE", v);
            ProcessRunner.Result r = ProcessRunner.run(ctx, ScriptPackage.powershellCommand(v.isEmpty()
                    ? "[Environment]::SetEnvironmentVariable($env:OOA_ENV_NAME, $null, 'User')" : SET_USER_ENV_SCRIPT), null, null, 60, env, java.nio.charset.StandardCharsets.UTF_8);
            if (r.exitCode() != 0) throw new ActionException("SYSTEM.SET_VARIABLE_FAILED", "Cannot set " + name + ": " + ApplicationPackage.tail(r.stderr() + r.stdout()), null);
        } else if (!"process".equals(scope)) {
            throw new ActionException("SYSTEM.BAD_SCOPE", "Unknown scope '" + scope + "'", "Use process or user.");
        }
        Map<String, String> runEnv = (Map<String, String>) ctx.sessions().computeIfAbsent(ProcessRunner.RUN_ENV_SESSION, k -> new java.util.concurrent.ConcurrentHashMap<String, String>());
        runEnv.put(name, v);  // empty means removed for programs started later
        return Map.of();
    }

    @Action(id = "get_system_info", name = "Get system info", category = "System", idempotent = true,
            description = "Return the runner name, operating system, user, processors and Java version.")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "hostname, os, os_version, user, processors, java_version.")
    public Map<String, Object> info(ActionContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "unknown"; }
        m.put("hostname", host);
        m.put("os", System.getProperty("os.name"));
        m.put("os_version", System.getProperty("os.version"));
        m.put("user", System.getProperty("user.name"));
        m.put("processors", BigDecimal.valueOf(Runtime.getRuntime().availableProcessors()));
        m.put("java_version", System.getProperty("java.version"));
        return Map.of("info", m);
    }

    /** shutdown command line for restart (/r, -r) or shut down (/s, -h); the message is one argument, never parsed by a shell. */
    static List<String> powerCommand(String flag, int delay, boolean force, String message, boolean windows) {
        int d = Math.max(0, Math.min(600, delay));
        List<String> cmd = new ArrayList<>();
        String m = message == null ? "" : message.replaceAll("[\\r\\n]+", " ").strip();
        if (m.length() > 512) m = m.substring(0, 512);
        if (windows) {
            cmd.addAll(List.of("shutdown.exe", flag, "/t", String.valueOf(d)));
            if (force) cmd.add("/f");
            if (!m.isEmpty()) { cmd.add("/c"); cmd.add(m); }
        } else {
            cmd.addAll(List.of("shutdown", flag, "+" + Math.max(1, d / 60)));
            if (!m.isEmpty()) cmd.add(m);
        }
        return cmd;
    }

    private static Map<String, Object> power(ActionContext ctx, boolean confirm, String what, List<String> windowsCmd, List<String> unixCmd) {
        if (!confirm) throw new ActionException("SYSTEM.CONFIRM_REQUIRED", what + " was not performed because confirm is false",
                "Set confirm to true to allow this agent to " + what.toLowerCase(Locale.ROOT) + " the runner.");
        List<String> cmd = ProcessRunner.WINDOWS ? windowsCmd : unixCmd;
        if (cmd == null) throw new ActionException("SYSTEM.WINDOWS_ONLY", what + " is supported on Windows only");
        ctx.warn(what + " requested by the agent");
        ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, null, null, 60);
        if (r.exitCode() != 0) throw new ActionException("SYSTEM.POWER_FAILED", what + " failed: " + ApplicationPackage.tail(r.stderr() + r.stdout()),
                "The agent may need administrator rights.");
        return Map.of();
    }

    @Action(id = "lock", name = "Lock computer", category = "Power", capabilities = "desktop", description = "Lock the screen of the current session.",
            panel = "confirm")
    public Map<String, Object> lock(ActionContext ctx, @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to lock.") boolean confirm) {
        return power(ctx, confirm, "Lock", List.of("rundll32.exe", "user32.dll,LockWorkStation"), null);
    }

    @Action(id = "log_off", name = "Sign out", category = "Power", capabilities = "desktop", description = "Sign out the current Windows user (open programs are closed).",
            panel = "confirm | force")
    public Map<String, Object> logOff(ActionContext ctx, @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to sign out.") boolean confirm,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Close programs without asking them to save (unsaved work is lost).") boolean force) {
        return power(ctx, confirm, "Sign out", force ? List.of("shutdown.exe", "/l", "/f") : List.of("shutdown.exe", "/l"), null);
    }

    @Action(id = "restart", name = "Restart computer", category = "Power", description = "Restart the runner after a delay.",
            panel = "confirm | delay_seconds, force, message")
    public Map<String, Object> restart(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to restart.") boolean confirm,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, max = 315360000, description = "Seconds before restarting.") int delay,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Close programs without asking them to save (unsaved work is lost).") boolean force,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Reason shown to signed-in users and written to the Windows event log.") String message) {
        return power(ctx, confirm, "Restart", powerCommand("/r", delay, force, message, true), powerCommand("-r", delay, force, message, false));
    }

    @Action(id = "shut_down", name = "Shut down computer", category = "Power", description = "Shut the runner down after a delay.",
            panel = "confirm | delay_seconds, force, message")
    public Map<String, Object> shutDown(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to shut down.") boolean confirm,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, max = 315360000, description = "Seconds before shutting down.") int delay,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Close programs without asking them to save (unsaved work is lost).") boolean force,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Reason shown to signed-in users and written to the Windows event log.") String message) {
        return power(ctx, confirm, "Shut down", powerCommand("/s", delay, force, message, true), powerCommand("-h", delay, force, message, false));
    }
}
