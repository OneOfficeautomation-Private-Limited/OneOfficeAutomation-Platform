package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Each package is exercised through real bots run by the Runner, the way customers use them. */
class PackagesTest {
    static final PackageRegistry REG = FirstPartyPackages.registry();

    /** Runs a bot given its variables and steps as JSON text; returns outputs (fails the test if the run fails). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> run(Path work, String variables, String steps, Map<String, Object> inputs) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse(
                "{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + variables + ",\"steps\":" + steps + "}");
        CredentialVault vault = new CredentialVault.InMemory().put("test", "api", "token", "tok-123").put("test", "hmac", "key", "key");
        RunResult r = Runner.builder(REG).workDir(work).service("vault", vault).build().run(flow, inputs);
        assertTrue(r.ok(), "run failed: " + r.error());
        return r.outputs();
    }

    @SuppressWarnings("unchecked")
    static RunResult runRaw(Path work, String variables, String steps) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse(
                "{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + variables + ",\"steps\":" + steps + "}");
        return Runner.builder(REG).workDir(work).build().run(flow, Map.of());
    }

    static void eqNum(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), "expected " + expected + " but was " + actual);
    }

    static Path tmp() throws Exception { return Files.createTempDirectory("ooa-pkg"); }

    @Test void csvRoundTripWithQuotesAndTableOperations() throws Exception {
        Path w = tmp();
        Files.writeString(w.resolve("in.csv"), "Invoice,Vendor,Amount\r\nINV-1,\"Acme, Ltd\",1200.50\nINV-2,\"Glo\"\"bex\",99\nINV-3,Acme,5000\nINV-3,Acme,5000\n");
        Map<String, Object> o = run(w,
                "[{\"name\":\"t\",\"type\":\"Table\"},{\"name\":\"big\",\"type\":\"Table\"},{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"},"
                + "{\"name\":\"total\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"output\"},"
                + "{\"name\":\"top\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"back\",\"type\":\"Table\",\"direction\":\"output\"}]",
                "[{\"id\":\"r\",\"action\":\"ooa.csv/read\",\"args\":{\"path\":\"in.csv\"},\"out\":{\"table\":\"t\"}},"
                + "{\"id\":\"d\",\"action\":\"ooa.datatable/remove_duplicates\",\"args\":{\"table\":\"$t\"},\"out\":{\"table\":\"t\"}},"
                + "{\"id\":\"s\",\"action\":\"ooa.datatable/sum_column\",\"args\":{\"table\":\"$t\",\"column\":\"Amount\"},\"out\":{\"total\":\"total\"}},"
                + "{\"id\":\"v\",\"action\":\"ooa.datatable/get_value\",\"args\":{\"table\":\"$t\",\"row\":1,\"column\":\"Vendor\"},\"out\":{\"value\":\"vendor\"}},"
                + "{\"id\":\"f\",\"action\":\"ooa.datatable/filter\",\"args\":{\"table\":\"$t\",\"column\":\"Amount\",\"operator\":\"gt\",\"value\":1000},\"out\":{\"table\":\"big\",\"count\":\"n\"}},"
                + "{\"id\":\"o\",\"action\":\"ooa.datatable/sort\",\"args\":{\"table\":\"$big\",\"column\":\"Amount\",\"descending\":true},\"out\":{\"table\":\"big\"}},"
                + "{\"id\":\"g\",\"action\":\"ooa.datatable/get_value\",\"args\":{\"table\":\"$big\",\"row\":0,\"column\":\"Invoice\"},\"out\":{\"value\":\"top\"}},"
                + "{\"id\":\"wr\",\"action\":\"ooa.csv/write\",\"args\":{\"table\":\"$t\",\"path\":\"out/copy.csv\"}},"
                + "{\"id\":\"rb\",\"action\":\"ooa.csv/read\",\"args\":{\"path\":\"out/copy.csv\"},\"out\":{\"table\":\"back\"}}]", Map.of());
        eqNum("6299.50", o.get("total"));
        assertEquals("Glo\"bex", o.get("vendor"));
        eqNum("2", o.get("n"));
        assertEquals("INV-3", o.get("top"));
        @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) ((Map<String, Object>) o.get("back")).get("rows");
        assertEquals("Acme, Ltd", rows.get(0).get(1));
        assertEquals(3, rows.size());
    }

    @Test void numberFormattingAndRounding() throws Exception {
        Map<String, Object> o = run(tmp(), "[{\"name\":\"a\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"b\",\"type\":\"String\",\"direction\":\"output\"},"
                + "{\"name\":\"c\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"r\",\"type\":\"Number\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.number/format\",\"args\":{\"value\":1234567.5},\"out\":{\"text\":\"a\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.number/format\",\"args\":{\"value\":1234567.5,\"locale\":\"de-DE\"},\"out\":{\"text\":\"b\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.number/round\",\"args\":{\"value\":2.5,\"mode\":\"half_even\"},\"out\":{\"result\":\"c\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.number/random\",\"args\":{\"min\":5,\"max\":5},\"out\":{\"result\":\"r\"}}]", Map.of());
        assertEquals("1,234,567.50", o.get("a"));
        assertEquals("1.234.567,50", o.get("b"));
        eqNum("2", o.get("c"));
        eqNum("5", o.get("r"));
    }

    @Test void dateTimeZonesAndArithmetic() throws Exception {
        Map<String, Object> o = run(tmp(), "[{\"name\":\"d\",\"type\":\"DateTime\",\"default\":\"2026-10-18T14:30:00\"},"
                + "{\"name\":\"ist\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"plus\",\"type\":\"DateTime\",\"direction\":\"output\"},"
                + "{\"name\":\"diff\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"parsed\",\"type\":\"DateTime\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.datetime/format\",\"args\":{\"value\":\"$d\",\"pattern\":\"dd/MM/yyyy HH:mm\",\"time_zone\":\"Asia/Kolkata\"},\"out\":{\"text\":\"ist\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.datetime/add\",\"args\":{\"value\":\"$d\",\"amount\":-1,\"unit\":\"months\"},\"out\":{\"result\":\"plus\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.datetime/difference\",\"args\":{\"start\":\"2026-10-01\",\"end\":\"$d\",\"unit\":\"hours\"},\"out\":{\"result\":\"diff\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.datetime/parse\",\"args\":{\"text\":\"18/10/2026 20:00\",\"pattern\":\"dd/MM/yyyy HH:mm\",\"time_zone\":\"Asia/Kolkata\"},\"out\":{\"value\":\"parsed\"}}]", Map.of());
        assertEquals("18/10/2026 20:00", o.get("ist"));
        assertEquals("2026-09-18T14:30", o.get("plus").toString());
        eqNum("422.5", o.get("diff"));
        assertEquals("2026-10-18T14:30", o.get("parsed").toString());
    }

    @Test void listAndDictionary() throws Exception {
        Map<String, Object> o = run(tmp(), "[{\"name\":\"l\",\"type\":\"List\",\"default\":[\"b\",10,\"a\",2,\"b\"]},{\"name\":\"d\",\"type\":\"Dictionary\",\"default\":{}},"
                + "{\"name\":\"sorted\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"v\",\"type\":\"Any\",\"direction\":\"output\"},"
                + "{\"name\":\"found\",\"type\":\"Boolean\",\"direction\":\"output\"},{\"name\":\"keys\",\"type\":\"List\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.list/remove_duplicates\",\"args\":{\"list\":\"$l\"},\"out\":{\"list\":\"l\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.list/sort\",\"args\":{\"list\":\"$l\"},\"out\":{\"list\":\"l\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.list/join\",\"args\":{\"list\":\"$l\",\"separator\":\"|\"},\"out\":{\"text\":\"sorted\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.dictionary/put\",\"args\":{\"dictionary\":\"$d\",\"key\":\"vendor\",\"value\":\"Acme\"},\"out\":{\"dictionary\":\"d\"}},"
                + "{\"id\":\"5\",\"action\":\"ooa.dictionary/get\",\"args\":{\"dictionary\":\"$d\",\"key\":\"missing\",\"default\":\"n/a\"},\"out\":{\"value\":\"v\",\"found\":\"found\"}},"
                + "{\"id\":\"6\",\"action\":\"ooa.dictionary/keys\",\"args\":{\"dictionary\":\"$d\"},\"out\":{\"keys\":\"keys\"}}]", Map.of());
        assertEquals("2|10|a|b", o.get("sorted"));
        assertEquals("n/a", o.get("v"));
        assertEquals(false, o.get("found"));
        assertEquals(List.of("vendor"), o.get("keys"));
    }

    @Test void filesFoldersAndZip() throws Exception {
        Path w = tmp();
        Map<String, Object> o = run(w, "[{\"name\":\"files\",\"type\":\"List\",\"direction\":\"output\"},{\"name\":\"info\",\"type\":\"Dictionary\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.folder/create\",\"args\":{\"path\":\"src/sub\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.file/write_text\",\"args\":{\"path\":\"src/a.txt\",\"text\":\"hello\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.file/copy\",\"args\":{\"source\":\"src/a.txt\",\"target\":\"src/sub/b.txt\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.file/zip\",\"args\":{\"source\":\"src\",\"target\":\"out/src.zip\"}},"
                + "{\"id\":\"5\",\"action\":\"ooa.file/unzip\",\"args\":{\"archive\":\"out/src.zip\",\"folder\":\"restored\"},\"out\":{\"files\":\"files\"}},"
                + "{\"id\":\"6\",\"action\":\"ooa.file/get_info\",\"args\":{\"path\":\"restored/sub/b.txt\"},\"out\":{\"info\":\"info\"}},"
                + "{\"id\":\"7\",\"action\":\"ooa.folder/delete\",\"args\":{\"path\":\"src\",\"recursive\":true}}]", Map.of());
        assertEquals(2, ((List<?>) o.get("files")).size());
        @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) o.get("info");
        assertEquals("txt", info.get("extension"));
        eqNum("5", info.get("size_bytes"));
        assertFalse(Files.exists(w.resolve("src")));
    }

    @Test void zipSlipIsRejected() throws Exception {
        Path w = tmp();
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(w.resolve("evil.zip")))) {
            z.putNextEntry(new ZipEntry("../../escaped.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        RunResult r = runRaw(w, "[]", "[{\"id\":\"u\",\"action\":\"ooa.file/unzip\",\"args\":{\"archive\":\"evil.zip\",\"folder\":\"out\"}}]");
        assertEquals("ZIP.UNSAFE_ENTRY", r.error().get("code"));
        assertFalse(Files.exists(w.getParent().resolve("escaped.txt")));
    }

    @Test void folderDeleteProtectsWorkingFolder() throws Exception {
        RunResult r = runRaw(tmp(), "[]", "[{\"id\":\"d\",\"action\":\"ooa.folder/delete\",\"args\":{\"path\":\".\",\"recursive\":true}}]");
        assertEquals("FOLDER.PROTECTED", r.error().get("code"));
    }

    @Test void restAgainstLocalServerWithBearerTokenFromVault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp = "{\"auth\":\"" + auth + "\",\"query\":\"" + ex.getRequestURI().getRawQuery() + "\",\"echo\":" + (body.isEmpty() ? "null" : body) + "}";
            int code = ex.getRequestURI().getPath().endsWith("/missing") ? 404 : 200;
            ex.sendResponseHeaders(code, resp.length());
            try (OutputStream os = ex.getResponseBody()) { os.write(resp.getBytes()); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/orders";
            Map<String, Object> o = run(tmp(), "[{\"name\":\"tok\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true},"
                    + "{\"name\":\"status\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"json\",\"type\":\"Dictionary\",\"direction\":\"output\"}]",
                    "[{\"id\":\"r\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"" + base + "\",\"method\":\"POST\",\"query\":{\"q\":\"a b\"},"
                    + "\"json\":{\"amount\":129.99},\"bearer_token\":\"$tok\"},\"out\":{\"status\":\"status\",\"json\":\"json\"}}]",
                    Map.of("tok", Map.of("$credential", "test/api/token")));
            eqNum("200", o.get("status"));
            @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) o.get("json");
            assertEquals("Bearer tok-123", j.get("auth"));
            assertEquals("q=a+b", j.get("query"));
            eqNum("129.99", ((Map<?, ?>) j.get("echo")).get("amount"));
            RunResult r = runRaw(tmp(), "[]", "[{\"id\":\"m\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"" + base + "/missing\"}}]");
            assertEquals("REST.HTTP_ERROR", r.error().get("code"));
        } finally { server.stop(0); }
    }

    @Test void hashesAndEncoding() throws Exception {
        Map<String, Object> o = run(tmp(), "[{\"name\":\"k\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true},"
                + "{\"name\":\"h\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"b\",\"type\":\"String\",\"direction\":\"output\"},"
                + "{\"name\":\"back\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"mac\",\"type\":\"String\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.crypto/hash_text\",\"args\":{\"text\":\"abc\"},\"out\":{\"hash\":\"h\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.crypto/base64_encode\",\"args\":{\"text\":\"OOA\"},\"out\":{\"result\":\"b\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.crypto/base64_decode\",\"args\":{\"text\":\"$b\"},\"out\":{\"result\":\"back\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.crypto/hmac\",\"args\":{\"text\":\"The quick brown fox jumps over the lazy dog\",\"key\":\"$k\"},\"out\":{\"signature\":\"mac\"}}]",
                Map.of("k", Map.of("$credential", "test/hmac/key")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", o.get("h"));
        assertEquals("T09B", o.get("b"));
        assertEquals("OOA", o.get("back"));
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", o.get("mac"));
    }

    @Test void systemRefusesSecretLookingVariables() throws Exception {
        RunResult r = runRaw(tmp(), "[]", "[{\"id\":\"e\",\"action\":\"ooa.system/get_environment_variable\",\"args\":{\"name\":\"DB_PASSWORD\"}}]");
        assertEquals("SYSTEM.SECRET_VARIABLE", r.error().get("code"));
    }

    @Test void everyFirstPartyActionIsDocumented() {
        for (Map<String, Object> m : REG.allManifests())
            for (Object a : (List<?>) m.get("actions")) {
                Map<?, ?> act = (Map<?, ?>) a;
                assertFalse(String.valueOf(act.get("description")).isBlank(), m.get("id") + "/" + act.get("id"));
            }
    }
}
