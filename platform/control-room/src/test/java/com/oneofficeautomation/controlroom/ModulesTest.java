package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The kernel's side of feature modules (docs/architecture/13-modules.md): their schema steps and what they may register. */
class ModulesTest {

    @Test void aModulesSchemaStepsApplyOnceInOrderAndADowngradeIsRefused() throws Exception {
        Path data = Files.createTempDirectory("ooa-modules");
        try (Db db = Db.embedded(data)) {
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertEquals(0, db.moduleVersion("sample"));
            List<String> v1 = List.of("CREATE TABLE sample_items (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL)");
            db.migrateModule("sample", List.of(v1));
            assertEquals(1, db.moduleVersion("sample"));
            db.update("INSERT INTO sample_items (id, name) VALUES (?, ?)", "a", "first");
            db.migrateModule("sample", List.of(v1));                     // nothing new: nothing runs twice
            assertEquals(1, db.moduleVersion("sample"));
            db.migrateModule("sample", List.of(v1, List.of("ALTER TABLE sample_items ADD COLUMN note VARCHAR(200)")));
            assertEquals(2, db.moduleVersion("sample"));
            assertEquals("first", db.one("SELECT name, note FROM sample_items WHERE id = 'a'").get("name"));   // the data stays
            OoaException older = assertThrows(OoaException.class, () -> db.migrateModule("sample", List.of(v1)));
            assertEquals("CR.DB_NEWER", older.code());
            assertEquals(0, db.moduleVersion("other"), "each module has its own version");
        }
    }

    @Test void theKernelRunsWithoutAnyFeatureModule() throws Exception {
        // control-room's own tests have no feature module on the class path: the kernel starts and lists none
        Path data = Files.createTempDirectory("ooa-modules-none");
        try (Db db = Db.embedded(data)) {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            assertEquals(List.of(), cr.modules.list());
            assertNull(cr.modules.taskKind("document_review"));
            assertTrue(cr.modules.userRoutes.isEmpty());
            assertEquals(List.of(), ConsolePlugin.installed(), "no module pages either: AI > Document Automation says it is not installed");
        }
    }

    @Test void aModuleCannotTakeOverTheKernelsSettingsOrTriggerKinds() {
        assertThrows(IllegalStateException.class, () -> Platform.defineModuleSetting("session_hours", 8, null), "a kernel setting stays the kernel's");
        assertThrows(IllegalArgumentException.class, () -> Platform.defineModuleSetting("Bad Key", "", null));
        assertThrows(IllegalStateException.class, () -> TriggerKinds.defineModuleKind("email", "Mail", r -> Map.of()), "a kernel trigger kind stays the kernel's");
        assertThrows(IllegalArgumentException.class, () -> TriggerKinds.defineModuleKind("a_kind_longer_than_twenty", "x", r -> Map.of()), "the triggers table's kind column");
        // a module's own may be defined again (one class path, several tenants in SaaS): the same entry
        TriggerKinds.defineModuleKind("sample_event", "Sample event", r -> Map.of("x", 1));
        TriggerKinds.defineModuleKind("sample_event", "Sample event", r -> Map.of("x", 1));
        assertTrue(TriggerKinds.pushed("sample_event"));
        assertEquals(Map.of("x", 1), TriggerKinds.validate("sample_event", Map.of()));
    }
}
