package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 (docs/architecture/13-modules.md): feature modules register their own permissions. With every module there,
 * the built-in roles have exactly the permissions they had when the kernel listed them all, so no one gains or loses
 * anything; each permission says which module it belongs to; a module may not take another's or the kernel's.
 */
class ModulePermissionsTest {
    static final Set<String> VIEWER = Set.of("repo.view", "runs.view", "devices.view", "schedules.view", "triggers.view", "globals.view", "queues.view",
            "analytics.view", "skills.view", "docs.view", "data.view");
    static final Set<String> RUNNER = Set.of("repo.view", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view", "triggers.view", "globals.view",
            "queues.view", "queues.work", "analytics.view", "skills.view", "docs.view", "docs.review", "data.view",
            "devices.choose_own", "devices.register_own");   // My account stage B (13 Nov 2026): own default runner, connect own computer
    static final Set<String> CREATOR = Set.of("repo.view", "repo.checkin", "repo.delete", "runs.view", "runs.start", "runs.stop", "devices.view", "schedules.view",
            "schedules.manage", "triggers.view", "triggers.manage", "globals.view", "lockers.own", "queues.view", "queues.work", "queues.manage", "analytics.view",
            "apitasks.manage", "skills.view", "skills.manage", "docs.view", "docs.review", "docs.manage", "data.view", "data.edit", "apps.manage", "knowledge.manage",
            "devices.choose_own", "devices.register_own");
    static final Set<String> ADMIN_ONLY = Set.of("repo.production", "repo.approve", "repo.permissions", "lifecycle.export", "lifecycle.import", "queues.all",
            "teams.manage", "users.manage", "devices.manage", "lockers.manage", "globals.manage", "audit.view", "pools.manage", "locks.manage", "settings.manage",
            "events.manage", "metrics.view", "connections.manage", "toolkits.manage", "skills.all", "alerts.manage", "docs.engines", "insights.design",
            "insights.admin", "ideas.manage", "data.manage");

    @Test
    void theBuiltInRolesKeepTheirPermissionsAndModulesOwnTheirs() throws Exception {
        Path data = Files.createTempDirectory("ooa-perms");
        Db db = Db.embedded(data);
        try {
            new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            assertEquals(new TreeSet<>(VIEWER), new TreeSet<>(Rbac.permissions("viewer")));
            assertEquals(new TreeSet<>(RUNNER), new TreeSet<>(Rbac.permissions("bot_runner")));
            assertEquals(new TreeSet<>(CREATOR), new TreeSet<>(Rbac.permissions("bot_creator")));
            Set<String> all = new TreeSet<>(CREATOR);
            all.addAll(RUNNER); all.addAll(VIEWER); all.addAll(ADMIN_ONLY);
            assertEquals(all, new TreeSet<>(Rbac.all()));

            assertEquals("document-automation", Rbac.moduleOf("docs.review"));
            assertEquals("low-code", Rbac.moduleOf("data.edit"));
            assertEquals("governance", Rbac.moduleOf("repo.approve"));
            assertNull(Rbac.moduleOf("runs.start"), "shared permissions stay the kernel's");

            // the same again is fine (another One CommandCenter in this process); a different claim is refused
            Rbac.register("document-automation", "docs.review", List.of("bot_runner", "bot_creator"));
            assertThrows(IllegalStateException.class, () -> Rbac.register("insights", "docs.review", List.of("bot_runner", "bot_creator")));
            assertThrows(IllegalStateException.class, () -> Rbac.register("document-automation", "docs.review", List.of("viewer")));
            assertThrows(IllegalStateException.class, () -> Rbac.register("insights", "runs.start", List.of()));
            assertThrows(IllegalArgumentException.class, () -> Rbac.register("insights", "Bad Name", List.of()));
            assertThrows(IllegalArgumentException.class, () -> Rbac.register("insights", "x.y", List.of("admin")));
        } finally { db.close(); }
    }
}
