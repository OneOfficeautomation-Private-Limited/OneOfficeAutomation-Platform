package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema version 33 (Document Automation's mail table) after this branch's 25-32. The Document Automation release (on
 * master) numbered that table version 25, which here is the runners' version, so a database it upgraded is put right.
 */
class SchemaUpgradeTest {

    static Db fresh() { return new Db("jdbc:h2:mem:schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); }

    /** Applies this release's versions 1..upTo by hand, as an older release would have left the database. */
    static void upTo(Db db, int upTo) throws Exception {
        Field f = Db.class.getDeclaredField("SCHEMA");
        f.setAccessible(true);
        String[][] schema = (String[][]) f.get(null);
        db.update("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
        for (int v = 1; v <= upTo; v++) {
            for (String ddl : schema[v - 1]) db.update(Db.ddl(ddl, false));
            db.update("INSERT INTO schema_version (version) VALUES (" + v + ")");
        }
    }

    static boolean has(Db db, String select) {
        try { db.query(select); return true; } catch (OoaException e) { return false; }
    }

    @Test void aNewDatabaseGetsEveryVersion() {
        try (Db db = fresh()) {
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertTrue(has(db, "SELECT process_id, mail_cursor FROM docflow_mail WHERE 1 = 0"));
            assertTrue(has(db, "SELECT nickname, cpu_max FROM devices WHERE 1 = 0"));
        }
    }

    @Test void aDatabaseOfThisBranchAt32GetsTheMailTable() throws Exception {
        try (Db db = fresh()) {
            upTo(db, 32);
            assertFalse(has(db, "SELECT 1 FROM docflow_mail WHERE 1 = 0"));
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertTrue(has(db, "SELECT process_id FROM docflow_mail WHERE 1 = 0"));
        }
    }

    @Test void aDatabaseTheDocumentAutomationReleaseUpgradedIsPutRight() throws Exception {
        try (Db db = fresh()) {
            // master's release: this release's 1..24, then its own version 25, the mail table, with a row in it
            upTo(db, 24);
            db.update("CREATE TABLE docflow_mail (process_id VARCHAR(120) PRIMARY KEY, mail_cursor VARCHAR(200), last_check TIMESTAMP, last_error VARCHAR(1000), documents INT)");
            db.update("INSERT INTO schema_version (version) VALUES (25)");
            db.update("INSERT INTO docflow_mail (process_id, mail_cursor, documents) VALUES ('invoices', 'uid-42', 3)");
            assertFalse(has(db, "SELECT nickname FROM devices WHERE 1 = 0"), "the runners' version 25 is missing");
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertTrue(has(db, "SELECT nickname, maintenance, cpu_max FROM devices WHERE 1 = 0"), "the runners' version 25 was applied");
            assertTrue(has(db, "SELECT owners FROM device_pools WHERE 1 = 0"));
            assertTrue(has(db, "SELECT kind FROM runner_files WHERE 1 = 0"));
            assertTrue(has(db, "SELECT id FROM alert_rules WHERE 1 = 0"), "26-32 ran after it");
            assertEquals("uid-42", db.one("SELECT mail_cursor FROM docflow_mail WHERE process_id = 'invoices'").get("mail_cursor"), "the mail table and its rows are kept");
            db.migrate();   // and nothing more happens the next time
            assertEquals(Db.LATEST, db.schemaVersion());
        }
    }

    @Test void aDatabaseAt33GetsTheCredentialExpiry() throws Exception {
        try (Db db = fresh()) {
            upTo(db, 33);
            db.update("INSERT INTO credential_meta (locker, name, owner, created) VALUES ('erp', 'sap', 'jane', CURRENT_TIMESTAMP)");
            assertFalse(has(db, "SELECT expires FROM credential_meta WHERE 1 = 0"));
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertNull(db.one("SELECT expires FROM credential_meta WHERE locker = 'erp'").get("expires"), "existing credentials do not expire");
            assertEquals("jane", db.one("SELECT owner FROM credential_meta WHERE locker = 'erp'").get("owner"));
        }
    }

    /** Version 34 (G31): items added before keep the references they share; references added as unique are refused twice. */
    @Test void workQueueUpgradeFrom33KeepsItemsAndGuardsUniqueReferences() throws Exception {
        try (Db db = fresh()) {
            upTo(db, 33);
            db.update("INSERT INTO queues (id, name, max_attempts, processing, created) VALUES ('q1', 'Invoices', 1, FALSE, CURRENT_TIMESTAMP)");
            for (String id : new String[] { "i1", "i2" })
                db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated) VALUES (?, 'q1', 'INV-1', '{}', 'new', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id);
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertEquals(2, db.query("SELECT id FROM queue_items WHERE reference = 'INV-1'").size(), "items with the same reference from before stay");
            assertNull(db.one("SELECT encrypt_data FROM queues WHERE id = 'q1'").get("encrypt_data"));
            assertTrue(has(db, "SELECT tags, error_kind, unique_ref FROM queue_items WHERE 1 = 0"));
            db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated, unique_ref) VALUES ('i3', 'q1', 'INV-2', '{}', 'new', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'INV-2')");
            db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated, unique_ref) VALUES ('i4', 'q2', 'INV-2', '{}', 'new', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'INV-2')");
            assertThrows(OoaException.class, () -> db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated, unique_ref) VALUES ('i5', 'q1', 'INV-2', '{}', 'new', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'INV-2')"),
                    "a unique reference once per queue");
        }
    }

    @Test void theUniqueIndexIsFilteredOnSqlServerOnly() {
        String ddl = "CREATE UNIQUE INDEX queue_items_unique_ref ON queue_items (queue_id, unique_ref) WHERE unique_ref IS NOT NULL";
        assertEquals("CREATE UNIQUE INDEX queue_items_unique_ref ON queue_items (queue_id, unique_ref)", Db.ddl(ddl, false));
        assertEquals(ddl, Db.ddl(ddl, true));
    }

    @Test void aDatabaseOfThisBranchAt25IsNotMistakenForOne() throws Exception {
        try (Db db = fresh()) {
            upTo(db, 25);   // this branch's version 25: the runners' columns, no mail table
            db.migrate();
            assertEquals(Db.LATEST, db.schemaVersion());
            assertTrue(has(db, "SELECT process_id FROM docflow_mail WHERE 1 = 0"));
        }
    }
}
