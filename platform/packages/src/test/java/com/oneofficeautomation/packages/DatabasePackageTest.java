package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
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
               {"id":"ct","action":"ooa.database/execute","args":{"sql":"CREATE TABLE invoices(id VARCHAR(20), amount DECIMAL(12,2))"}},
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
}
