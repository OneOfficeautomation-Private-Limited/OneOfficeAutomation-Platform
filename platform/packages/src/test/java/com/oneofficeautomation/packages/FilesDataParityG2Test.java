package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.FlowVariables;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G2 (AA parity, 21 Nov 2026), batch A: an empty table of a size; AA's named date formats; a table saved with no path;
 * the CRITICAL and EMERGENCY levels and the agent's variables in a log line; RSA 2048 keys and a key owner by name or
 * e-mail only; a file from One CommandCenter by its path while the agent runs.
 */
class FilesDataParityG2Test {
    @SuppressWarnings("unchecked")
    @Test void anEmptyTableOfASize() throws Exception {
        DataTablePackage t = new DataTablePackage();
        var c = PackagesTestSupport.ctx(tmp(), Map.of());
        Map<String, Object> byName = (Map<String, Object>) t.createTable(c, List.of("Invoice", "Amount"), null, 3).get("table");
        assertEquals(List.of("Invoice", "Amount"), byName.get("columns"));
        assertEquals(3, ((List<Object>) byName.get("rows")).size());
        assertEquals(List.of("Column1", "Column2", "Column3"), ((Map<String, Object>) t.createTable(c, null, 3, 0).get("table")).get("columns"));
        assertEquals("TABLE.NO_COLUMNS", assertThrows(ActionException.class, () -> t.createTable(c, null, null, 1)).code());
        assertEquals("TABLE.BAD_COLUMN", assertThrows(ActionException.class, () -> t.createTable(c, List.of("A", "A"), null, 1)).code());
    }

    @SuppressWarnings("unchecked")
    @Test void namedDateFormatsAndATableSavedWithNoPath() throws Exception {
        DataTablePackage t = new DataTablePackage();
        Path w = tmp();
        var c = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> table = Values.table(List.of("when"), List.of(new ArrayList<>(List.of("Fri, 20 Nov 2026 10:15:00 GMT")), new ArrayList<>(List.of("2026-11-21T09:00:00+05:30"))));
        Map<String, Object> rfc = (Map<String, Object>) t.changeColumnType(c, Values.table(List.of("when"), List.of(new ArrayList<>(List.of("Fri, 20 Nov 2026 10:15:00 GMT")))),
                "when", "DateTime", null, "RFC_1123_DATE_TIME").get("table");
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 15), ((List<List<Object>>) rfc.get("rows")).get(0).get(0));
        Map<String, Object> iso = (Map<String, Object>) t.changeColumnType(c, Values.table(List.of("when"), List.of(new ArrayList<>(List.of("2026-11-21T09:00:00+05:30")))),
                "when", "DateTime", null, "iso_offset_date_time").get("table");
        assertEquals(LocalDateTime.of(2026, 11, 21, 3, 30), ((List<List<Object>>) iso.get("rows")).get(0).get(0), "turned into UTC");

        String path = String.valueOf(t.writeToFile(c, table, null, "csv", ",", true, "utf-8", false, "lf", null, true).get("path"));
        assertTrue(path.endsWith("table.csv") && path.contains(".ooa-temp"), path);
        assertTrue(Files.readString(Path.of(path)).startsWith("when\n"));
    }

    @Test void criticalLinesAndTheAgentsVariablesInTheLog() throws Exception {
        List<String> lines = new ArrayList<>();
        FlowVariables fv = new FlowVariables() {
            public Map<String, String> declared() { return new LinkedHashMap<>(Map.of("invoice", "String", "total", "Number", "pin", "Credential")); }
            public Object value(String n) { return Map.of("invoice", "INV-7", "total", new BigDecimal("12.5"), "pin", new SecureString("1234", "v")).get(n); }
        };
        ActionContext c = new ActionContext("r", "s", "default", tmp(), new HashMap<>(), new HashMap<>(Map.of(FlowVariables.SERVICE, fv)), new AtomicBoolean(), List::of,
                (l, m) -> lines.add(l + " " + m));
        LogPackage log = new LogPackage();
        log.logToCommandCenter(c, "Disk almost full", "CRITICAL", null, null, null);
        log.logToCommandCenter(c, "State", "INFO", Map.of("step", "post"), null, "all");
        assertEquals("error CRITICAL: Disk almost full", lines.get(0));
        assertTrue(lines.get(1).contains("\"invoice\":\"INV-7\"") && lines.get(1).contains("\"step\":\"post\""), lines.get(1));
        assertFalse(lines.get(1).contains("pin") || lines.get(1).contains("1234"), "credentials never: " + lines.get(1));
        assertEquals("LOG.BAD_LEVEL", assertThrows(ActionException.class, () -> log.logToCommandCenter(c, "x", "LOUD", null, null, null)).code());
    }

    @Test void keyOwnerByNameOrEmailAndRsa2048() {
        assertEquals("<agent@example.com>", PgpPackage.userId(null, "agent@example.com", null));
        assertEquals("Finance Agent (prod)", PgpPackage.userId("Finance Agent", "", "prod"));
        assertEquals("PGP.NO_USER_ID", assertThrows(ActionException.class, () -> PgpPackage.userId(" ", null, null)).code());
        assertEquals("PGP.BAD_EMAIL", assertThrows(ActionException.class, () -> PgpPackage.userId(null, "not-an-address", null)).code());
    }

    @Test void aFileFromOneCommandCenterByItsPath() throws Exception {
        Path w = tmp();
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> cc = req -> {
            asked.add(req);
            return Map.of("path", "Templates/rates.csv", "content", Base64.getEncoder().encodeToString("EUR,1\n".getBytes()), "content_type", "text/csv");
        };
        var c = PackagesTestSupport.ctx(w, Map.of("command_center", cc));
        FilePackage f = new FilePackage();
        Map<String, Object> r = f.getFromCommandCenter(c, "Templates/rates.csv", "in/", true);   // a folder: in/ (or one that exists)
        Path got = Path.of(String.valueOf(r.get("path")));
        assertEquals(w.resolve("in").resolve("rates.csv").toAbsolutePath().normalize(), got.toAbsolutePath().normalize());
        assertEquals("EUR,1\n", Files.readString(got));
        assertEquals(Map.of("call", "repo_file", "args", Map.of("path", "Templates/rates.csv")), asked.get(0));
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> f.getFromCommandCenter(c, "Templates/rates.csv", "in/", false)).code());
        assertEquals("FILE.NO_COMMAND_CENTER", assertThrows(ActionException.class,
                () -> f.getFromCommandCenter(PackagesTestSupport.ctx(w, Map.of()), "Templates/rates.csv", null, true)).code());
    }
}
