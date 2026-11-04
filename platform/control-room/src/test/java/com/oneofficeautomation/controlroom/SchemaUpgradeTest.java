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
            assertEquals(33, db.schemaVersion());
            assertTrue(has(db, "SELECT process_id, mail_cursor FROM docflow_mail WHERE 1 = 0"));
            assertTrue(has(db, "SELECT nickname, cpu_max FROM devices WHERE 1 = 0"));
        }
    }

    @Test void aDatabaseOfThisBranchAt32GetsTheMailTable() throws Exception {
        try (Db db = fresh()) {
            upTo(db, 32);
            assertFalse(has(db, "SELECT 1 FROM docflow_mail WHERE 1 = 0"));
            db.migrate();
            assertEquals(33, db.schemaVersion());
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
            assertEquals(33, db.schemaVersion());
            assertTrue(has(db, "SELECT nickname, maintenance, cpu_max FROM devices WHERE 1 = 0"), "the runners' version 25 was applied");
            assertTrue(has(db, "SELECT owners FROM device_pools WHERE 1 = 0"));
            assertTrue(has(db, "SELECT kind FROM runner_files WHERE 1 = 0"));
            assertTrue(has(db, "SELECT id FROM alert_rules WHERE 1 = 0"), "26-32 ran after it");
            assertEquals("uid-42", db.one("SELECT mail_cursor FROM docflow_mail WHERE process_id = 'invoices'").get("mail_cursor"), "the mail table and its rows are kept");
            db.migrate();   // and nothing more happens the next time
            assertEquals(33, db.schemaVersion());
        }
    }

    @Test void aDatabaseOfThisBranchAt25IsNotMistakenForOne() throws Exception {
        try (Db db = fresh()) {
            upTo(db, 25);   // this branch's version 25: the runners' columns, no mail table
            db.migrate();
            assertEquals(33, db.schemaVersion());
            assertTrue(has(db, "SELECT process_id FROM docflow_mail WHERE 1 = 0"));
        }
    }
}
