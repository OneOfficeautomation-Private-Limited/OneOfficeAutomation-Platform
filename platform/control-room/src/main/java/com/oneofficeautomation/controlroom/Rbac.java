package com.oneofficeautomation.controlroom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * System roles and their permissions. Every API call checks one permission; the console hides what a role cannot use.
 * The kernel's permissions are listed here; feature modules register their own (ModuleContext.permission, phase 8 of
 * docs/architecture/13-modules.md), with the built-in roles that have them. Administrators have every permission.
 */
public final class Rbac {
    private Rbac() {}

    public static final List<String> ROLES = List.of("admin", "bot_creator", "bot_runner", "viewer");

    private static final Map<String, Set<String>> PERMS = Map.of(
            "viewer", Set.of("repo.view", "runs.view", "devices.view", "schedules.view", "triggers.view", "globals.view", "queues.view"),
            // devices.choose_own and devices.register_own (13 Nov 2026 round 3, item 9 B): as AA's Bot Runner and Bot Creator,
            // a person picks their own default runner and connects their own computer as a runner (My devices)
            "bot_runner", Set.of("repo.view", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "triggers.view", "globals.view",
                    "queues.view", "queues.work", "devices.choose_own", "devices.register_own"),
            "bot_creator", Set.of("repo.view", "repo.checkin", "repo.delete", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "schedules.manage",
                    "triggers.view", "triggers.manage", "globals.view", "lockers.own", "queues.view", "queues.work", "queues.manage", "devices.choose_own", "devices.register_own"),
            "admin", Set.of("*"));

    /** The kernel's permissions only administrators have unless a custom role grants them. */
    public static final List<String> ADMIN_ONLY = List.of("repo.production", "repo.permissions", "lifecycle.export", "lifecycle.import", "queues.all", "teams.manage", "users.manage", "devices.manage",
            "lockers.manage", "globals.manage", "audit.view", "pools.manage", "locks.manage", "settings.manage", "events.manage", "metrics.view", "connections.manage", "toolkits.manage");

    /** A feature module's permission: the module that registered it and the built-in roles (besides admin) that have it. */
    record Grant(String module, Set<String> roles) {}

    private static final Map<String, Grant> MODULES = new ConcurrentHashMap<>();
    private static final Pattern NAME = Pattern.compile("[a-z][a-z_]*\\.[a-z][a-z_]*");

    /**
     * A feature module's permission (ModuleContext.permission). Registering the same permission again the same way is
     * fine (each One CommandCenter in a process starts its modules); another module, other roles or a kernel permission
     * is refused.
     */
    static synchronized void register(String module, String permission, Collection<String> roles) {
        if (permission == null || !NAME.matcher(permission).matches()) throw new IllegalArgumentException("Module " + module + ": a permission is named area.action, not '" + permission + "'");
        Set<String> r = Set.copyOf(roles);
        for (String role : r)
            if (!ROLES.contains(role) || role.equals("admin")) throw new IllegalArgumentException("Module " + module + ": " + role + " is not a built-in role a permission is given to (administrators have them all)");
        if (kernelPermissions().contains(permission)) throw new IllegalStateException("Module " + module + " registers the permission " + permission + ", which is the kernel's");
        Grant g = new Grant(module, r), had = MODULES.putIfAbsent(permission, g);
        if (had != null && !had.equals(g)) throw new IllegalStateException("Module " + module + " registers the permission " + permission + ", which " + had.module() + " has");
    }

    /** The module a permission belongs to, or null for the kernel's (and unknown ones). */
    public static String moduleOf(String permission) { Grant g = MODULES.get(permission); return g == null ? null : g.module(); }

    private static Set<String> kernelPermissions() {
        Set<String> all = new TreeSet<>();
        PERMS.values().forEach(all::addAll);
        all.remove("*");
        all.addAll(ADMIN_ONLY);
        return all;
    }

    /** Every permission, for building custom roles. */
    public static List<String> all() { return permissions("admin"); }

    public static boolean allowed(String role, String permission) {
        Set<String> p = PERMS.getOrDefault(role, Set.of());
        if (p.contains("*") || p.contains(permission)) return true;
        Grant g = MODULES.get(permission);
        return g != null && g.roles().contains(role);
    }

    public static List<String> permissions(String role) {
        Set<String> all = kernelPermissions();
        all.addAll(MODULES.keySet());
        return all.stream().filter(p -> allowed(role, p)).toList();
    }
}
