package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.*;

/** System information and power actions. Power actions require confirm = true so they can never run by accident. */
@OoaPackage(id = "ooa.system", name = "System", version = "1.0.0", description = "Read environment variables and device information; lock, sign out, restart or shut down the device.")
public final class SystemPackage {

    @Action(id = "get_environment_variable", name = "Get environment variable", category = "System", idempotent = true,
            description = "Read an environment variable of the device; variables that look like secrets are refused.")
    @Output(name = "value", type = OoaType.STRING, description = "Value, or the default when not set.")
    public Map<String, Object> getEnv(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Variable name, e.g. COMPUTERNAME.") String name,
            @Input(name = "default", type = OoaType.STRING, required = false, description = "Value when the variable is not set.") String def) {
        if (name.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|SECRET|TOKEN|API_KEY|PRIVATE).*"))
            throw new ActionException("SYSTEM.SECRET_VARIABLE", "Reading '" + name + "' is blocked because it looks like a secret",
                    "Store secrets in the credential vault and use a Credential variable.");
        String v = System.getenv(name);
        return Out.of("value", v != null ? v : def);
    }

    @Action(id = "get_system_info", name = "Get system info", category = "System", idempotent = true,
            description = "Return the device name, operating system, user, processors and Java version.")
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

    private static Map<String, Object> power(ActionContext ctx, boolean confirm, String what, List<String> windowsCmd, List<String> unixCmd) {
        if (!confirm) throw new ActionException("SYSTEM.CONFIRM_REQUIRED", what + " was not performed because confirm is false",
                "Set confirm to true to allow this bot to " + what.toLowerCase(Locale.ROOT) + " the device.");
        List<String> cmd = ProcessRunner.WINDOWS ? windowsCmd : unixCmd;
        if (cmd == null) throw new ActionException("SYSTEM.WINDOWS_ONLY", what + " is supported on Windows only");
        ctx.warn(what + " requested by the bot");
        ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, null, null, 60);
        if (r.exitCode() != 0) throw new ActionException("SYSTEM.POWER_FAILED", what + " failed: " + ApplicationPackage.tail(r.stderr() + r.stdout()),
                "The bot agent may need administrator rights.");
        return Map.of();
    }

    @Action(id = "lock", name = "Lock computer", category = "Power", capabilities = "desktop", description = "Lock the screen of the current session.")
    public Map<String, Object> lock(ActionContext ctx, @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to lock.") boolean confirm) {
        return power(ctx, confirm, "Lock", List.of("rundll32.exe", "user32.dll,LockWorkStation"), null);
    }

    @Action(id = "log_off", name = "Sign out", category = "Power", capabilities = "desktop", description = "Sign out the current Windows user (open programs are closed).")
    public Map<String, Object> logOff(ActionContext ctx, @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to sign out.") boolean confirm) {
        return power(ctx, confirm, "Sign out", List.of("shutdown.exe", "/l"), null);
    }

    @Action(id = "restart", name = "Restart computer", category = "Power", description = "Restart the device after a delay.")
    public Map<String, Object> restart(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to restart.") boolean confirm,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Seconds before restarting.") int delay) {
        int d = Math.max(0, Math.min(600, delay));
        return power(ctx, confirm, "Restart", List.of("shutdown.exe", "/r", "/t", String.valueOf(d)), List.of("shutdown", "-r", "+" + Math.max(1, d / 60)));
    }

    @Action(id = "shut_down", name = "Shut down computer", category = "Power", description = "Shut the device down after a delay.")
    public Map<String, Object> shutDown(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to shut down.") boolean confirm,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Seconds before shutting down.") int delay) {
        int d = Math.max(0, Math.min(600, delay));
        return power(ctx, confirm, "Shut down", List.of("shutdown.exe", "/s", "/t", String.valueOf(d)), List.of("shutdown", "-h", "+" + Math.max(1, d / 60)));
    }
}
