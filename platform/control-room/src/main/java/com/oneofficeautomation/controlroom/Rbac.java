package com.oneofficeautomation.controlroom;

import java.util.*;

/** System roles and their permissions. Every API call checks one permission; the console hides what a role cannot use. */
public final class Rbac {
    private Rbac() {}

    public static final List<String> ROLES = List.of("admin", "bot_creator", "bot_runner", "viewer");

    private static final Map<String, Set<String>> PERMS = Map.of(
            "viewer", Set.of("repo.view", "runs.view", "devices.view", "schedules.view", "triggers.view", "globals.view"),
            "bot_runner", Set.of("repo.view", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "triggers.view", "globals.view"),
            "bot_creator", Set.of("repo.view", "repo.checkin", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "schedules.manage",
                    "triggers.view", "triggers.manage", "globals.view", "lockers.own"),
            "admin", Set.of("*"));

    public static boolean allowed(String role, String permission) {
        Set<String> p = PERMS.getOrDefault(role, Set.of());
        return p.contains("*") || p.contains(permission);
    }

    public static List<String> permissions(String role) {
        Set<String> all = new TreeSet<>();
        PERMS.values().forEach(all::addAll);
        all.remove("*");
        all.addAll(List.of("repo.production", "users.manage", "devices.manage", "lockers.manage", "globals.manage", "audit.view"));
        return all.stream().filter(p -> allowed(role, p)).toList();
    }
}
