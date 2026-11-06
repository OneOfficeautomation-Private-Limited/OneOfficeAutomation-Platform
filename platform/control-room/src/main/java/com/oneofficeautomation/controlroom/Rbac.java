package com.oneofficeautomation.controlroom;

import java.util.*;

/** System roles and their permissions. Every API call checks one permission; the console hides what a role cannot use. */
public final class Rbac {
    private Rbac() {}

    public static final List<String> ROLES = List.of("admin", "bot_creator", "bot_runner", "viewer");

    private static final Map<String, Set<String>> PERMS = Map.of(
            "viewer", Set.of("repo.view", "runs.view", "devices.view", "schedules.view", "triggers.view", "globals.view", "queues.view", "analytics.view", "skills.view", "docs.view"),
            "bot_runner", Set.of("repo.view", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "triggers.view", "globals.view",
                    "queues.view", "queues.work", "analytics.view", "skills.view", "docs.view", "docs.review"),
            "bot_creator", Set.of("repo.view", "repo.checkin", "repo.delete", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "schedules.manage",
                    "triggers.view", "triggers.manage", "globals.view", "lockers.own", "queues.view", "queues.work", "queues.manage", "analytics.view", "apitasks.manage", "skills.view", "skills.manage",
                    "docs.view", "docs.review", "docs.manage"),
            "admin", Set.of("*"));

    /** Permissions only administrators have unless a custom role grants them. */
    public static final List<String> ADMIN_ONLY = List.of("repo.production", "repo.approve", "repo.permissions", "lifecycle.export", "lifecycle.import", "queues.all", "teams.manage", "users.manage", "devices.manage",
            "lockers.manage", "globals.manage", "audit.view", "pools.manage", "locks.manage", "settings.manage", "events.manage", "metrics.view", "connections.manage", "toolkits.manage", "skills.all", "alerts.manage",
            "docs.engines",
            // Operational Insights (AA's Insight roles): make and share dashboards; set savings and the yearly goal
            "insights.design", "insights.admin",
            // Automation ideas (G65): assess ideas, move them through the pipeline, link the agent built
            "ideas.manage");

    /** Every permission, for building custom roles. */
    public static List<String> all() { return permissions("admin"); }

    public static boolean allowed(String role, String permission) {
        Set<String> p = PERMS.getOrDefault(role, Set.of());
        return p.contains("*") || p.contains(permission);
    }

    public static List<String> permissions(String role) {
        Set<String> all = new TreeSet<>();
        PERMS.values().forEach(all::addAll);
        all.remove("*");
        all.addAll(ADMIN_ONLY);
        return all.stream().filter(p -> allowed(role, p)).toList();
    }
}
