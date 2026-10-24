package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the H2 in-memory database (test dependency only). */
class DatabasePackageTest {

    @SuppressWarnings("unchecked")
    @Test void parameterisedQueriesAndTransactions() throws Exception {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"db","name":"db",
             "variables":[{"name":"t","type":"Table","direction":"output"},{"name":"n","type":"Number","direction":"output"},
                          {"name":"evil","type":"String","default":"x'); DROP TABLE invoices; --"}],
             "steps":[
               {"id":"c","action":"ooa.database/connect","args":{"jdbc_url":"jdbc:h2:mem:ooa;DB_CLOSE_DELAY=-1"}},
               {"id":"ct","action":"ooa.database/execute","args":{"sql":"CREATE TABLE invoices(id VARCHAR(64), amount DECIMAL(12,2))"}},
               {"id":"i1","action":"ooa.database/execute","args":{"sql":"INSERT INTO invoices VALUES (?, ?)","parameters":["INV-1",1200.50]}},
               {"id":"i2","action":"ooa.database/execute","args":{"sql":"INSERT INTO invoices VALUES (?, ?)","parameters":["$evil",1]}},
               {"id":"b","action":"ooa.database/begin_transaction","args":{}},
               {"id":"i3","action":"ooa.database/execute","args":{"sql":"INSERT INTO invoices VALUES (?, ?)","parameters":["INV-ROLLED-BACK",9]}},
               {"id":"rb","action":"ooa.database/rollback","args":{}},
               {"id":"q","action":"ooa.database/query","args":{"sql":"SELECT id, amount FROM invoices WHERE amount > ? ORDER BY id","parameters":[0]},
                "out":{"table":"t","row_count":"n"}},
               {"id":"d","action":"ooa.database/disconnect","args":{}}
             ]}""");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(Files.createTempDirectory("ooa-db")).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) r.outputs().get("n")), "injection text stored as data; rollback discarded row");
        List<List<Object>> rows = (List<List<Object>>) ((Map<String, Object>) r.outputs().get("t")).get("rows");
        assertEquals("INV-1", rows.get(0).get(0));
        assertEquals("x'); DROP TABLE invoices; --", rows.get(1).get(0));
    }

    @Test void connectBuildsTheUrlFromTheDatabaseType() {
        assertEquals("jdbc:sqlserver://db01:1433;databaseName=erp", DatabasePackage.jdbcUrl("sqlserver", "db01", 1433, "erp", null));
        assertEquals("jdbc:sqlserver://db01\\SQLEXPRESS", DatabasePackage.jdbcUrl("sqlserver", "db01\\SQLEXPRESS", null, null, null));
        assertEquals("jdbc:postgresql://pg:5433/sales", DatabasePackage.jdbcUrl("postgresql", "pg", 5433, "sales", null));
        assertEquals("jdbc:mysql://my/shop", DatabasePackage.jdbcUrl("MySQL", " my ", null, "shop", null));
        assertEquals("jdbc:oracle:thin:@//ora:1521/ORCLPDB1", DatabasePackage.jdbcUrl("oracle", "ora", null, "ORCLPDB1", null));
        assertEquals("jdbc:sqlite:C:\\data\\a.db", DatabasePackage.jdbcUrl("sqlite", null, null, null, "C:\\data\\a.db"));
        assertEquals("jdbc:h2:C:\\data\\erp", DatabasePackage.jdbcUrl("h2", null, null, null, "C:\\data\\erp.mv.db"));
        assertEquals("jdbc:ucanaccess://C:\\data\\a.accdb", DatabasePackage.jdbcUrl("access", null, null, null, "C:\\data\\a.accdb"));
        assertEquals("DB.BAD_INPUT", assertThrows(ActionException.class, () -> DatabasePackage.jdbcUrl(null, "x", null, null, null)).code());
        assertEquals("DB.BAD_INPUT", assertThrows(ActionException.class, () -> DatabasePackage.jdbcUrl("postgresql", "", null, "db", null)).code());
        assertEquals("DB.BAD_INPUT", assertThrows(ActionException.class, () -> DatabasePackage.jdbcUrl("sqlite", "host", null, null, null)).code());
        assertEquals("DB.BAD_INPUT", assertThrows(ActionException.class, () -> DatabasePackage.jdbcUrl("oracle", "ora", 1521, null, null)).code());
    }

    @SuppressWarnings("unchecked")
    @Test void eachRowOfAQueryOnAnH2FileDatabase() throws Exception {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"dbrows","name":"dbrows",
             "variables":[{"name":"sum","type":"Number","default":0,"direction":"output"},{"name":"ids","type":"String","default":"","direction":"output"},{"name":"row","type":"Dictionary"}],
             "steps":[
               {"id":"c","action":"ooa.database/connect","args":{"database_type":"h2","file":"data/erp"}},
               {"id":"ct","action":"ooa.database/execute","args":{"sql":"CREATE TABLE invoices(id VARCHAR(64), amount DECIMAL(12,2))"}},
               {"id":"i","action":"ooa.database/execute","args":{"sql":"INSERT INTO invoices VALUES ('A', 10), ('B', 20.5), ('C', 99)"}},
               {"id":"l","action":"core/loop","args":{"iterator":"ooa.database/query_rows","iterator_args":{"sql":"SELECT id, amount FROM invoices WHERE amount < ? ORDER BY id","parameters":[50]},"as":"row"},
                "children":[
                  {"id":"s","action":"core/set","args":{"variable":"sum","value":{"expr":"$sum + $row.AMOUNT"}}},
                  {"id":"u","action":"ooa.database/execute","args":{"sql":"UPDATE invoices SET amount = 0 WHERE id = ?","parameters":["$row.ID"]}},
                  {"id":"j","action":"core/set","args":{"variable":"ids","value":"${ids}${row.ID}"}}
                ]},
               {"id":"d","action":"ooa.database/disconnect","args":{}}
             ]}""");
        java.nio.file.Path dir = Files.createTempDirectory("ooa-db");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(dir).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, new BigDecimal("30.5").compareTo((BigDecimal) r.outputs().get("sum")));
        assertEquals("AB", r.outputs().get("ids"));
        assertTrue(Files.exists(dir.resolve("data/erp.mv.db")), "file resolved against the work folder");
    }
}
