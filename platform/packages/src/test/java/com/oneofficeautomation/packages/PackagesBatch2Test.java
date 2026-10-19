package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Batch 2: XML, Log, Boolean, Application, Scripts, Network, SOAP, Active Directory, Image, Desktop, Services, Printer. */
class PackagesBatch2Test {

    static boolean onPath(String exe) {
        try { return new ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0; } catch (Exception e) { return false; }
    }

    @Test void xmlQueryChangeAndConvert() throws Exception {
        Path w = tmp();
        Files.writeString(w.resolve("inv.xml"), "<invoice id=\"INV-7\"><vendor>Acme</vendor><lines><line><sku>A1</sku><amount>10.50</amount></line>"
                + "<line><sku>B2</sku><amount>4</amount></line></lines></invoice>");
        Map<String, Object> o = run(w, "[{\"name\":\"v\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"skus\",\"type\":\"List\",\"direction\":\"output\"},"
                + "{\"name\":\"d\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"after\",\"type\":\"String\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.xml/get_value\",\"args\":{\"path\":\"inv.xml\",\"xpath\":\"/invoice/vendor\"},\"out\":{\"value\":\"v\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.xml/get_nodes\",\"args\":{\"path\":\"inv.xml\",\"xpath\":\"//sku\"},\"out\":{\"values\":\"skus\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.xml/to_dictionary\",\"args\":{\"path\":\"inv.xml\"},\"out\":{\"value\":\"d\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.xml/set_value\",\"args\":{\"path\":\"inv.xml\",\"xpath\":\"//amount\",\"value\":\"0\"},\"out\":{\"count\":\"n\"}},"
                + "{\"id\":\"5\",\"action\":\"ooa.xml/insert_node\",\"args\":{\"path\":\"inv.xml\",\"parent_xpath\":\"/invoice\",\"name\":\"status\",\"text\":\"paid\"}},"
                + "{\"id\":\"6\",\"action\":\"ooa.xml/delete_nodes\",\"args\":{\"path\":\"inv.xml\",\"xpath\":\"/invoice/@id\"}},"
                + "{\"id\":\"7\",\"action\":\"ooa.file/read_text\",\"args\":{\"path\":\"inv.xml\"},\"out\":{\"text\":\"after\"}}]", Map.of());
        assertEquals("Acme", o.get("v"));
        assertEquals(List.of("A1", "B2"), o.get("skus"));
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) o.get("d");
        assertEquals("INV-7", d.get("@id"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) d.get("lines")).get("line")).size());
        eqNum("2", o.get("n"));
        String after = (String) o.get("after");
        assertTrue(after.contains("<status>paid</status>") && !after.contains("INV-7") && !after.contains("10.50"), after);
    }

    @Test void xmlRefusesXxeAttack() throws Exception {
        Path w = tmp();
        Files.writeString(w.resolve("secret.txt"), "TOP-SECRET");
        Files.writeString(w.resolve("evil.xml"), "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY s SYSTEM \"file:" + w.resolve("secret.txt").toUri().getPath() + "\">]><x>&s;</x>");
        RunResult r = runRaw(w, "[]", "[{\"id\":\"x\",\"action\":\"ooa.xml/get_value\",\"args\":{\"path\":\"evil.xml\",\"xpath\":\"/x\"}}]");
        assertEquals("XML.DTD_NOT_ALLOWED", r.error().get("code"));
        assertFalse(String.valueOf(r.error()).contains("TOP-SECRET"));
    }

    @Test void xmlSchemaValidation() throws Exception {
        Path w = tmp();
        Files.writeString(w.resolve("s.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"qty\" type=\"xs:int\"/></xs:schema>");
        Map<String, Object> o = run(w, "[{\"name\":\"ok\",\"type\":\"Boolean\",\"direction\":\"output\"},{\"name\":\"bad\",\"type\":\"Boolean\",\"direction\":\"output\"},{\"name\":\"errs\",\"type\":\"List\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.xml/validate\",\"args\":{\"xml\":\"<qty>5</qty>\",\"xsd_path\":\"s.xsd\"},\"out\":{\"valid\":\"ok\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.xml/validate\",\"args\":{\"xml\":\"<qty>five</qty>\",\"xsd_path\":\"s.xsd\"},\"out\":{\"valid\":\"bad\",\"errors\":\"errs\"}}]", Map.of());
        assertEquals(true, o.get("ok"));
        assertEquals(false, o.get("bad"));
        assertFalse(((List<?>) o.get("errs")).isEmpty());
    }

    @Test void logMasksSecretsAndBooleanConversions() throws Exception {
        Path w = tmp();
        Map<String, Object> o = run(w, "[{\"name\":\"tok\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true},{\"name\":\"t\",\"type\":\"String\",\"direction\":\"output\"},"
                + "{\"name\":\"b\",\"type\":\"Boolean\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.log/write\",\"args\":{\"path\":\"logs/run.log\",\"message\":\"token was tok-123 here\",\"level\":\"WARN\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.boolean/from_text\",\"args\":{\"text\":\"Yes\"},\"out\":{\"result\":\"b\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.boolean/to_text\",\"args\":{\"value\":false,\"true_text\":\"Approved\",\"false_text\":\"Rejected\"},\"out\":{\"text\":\"t\"}}]",
                Map.of("tok", Map.of("$credential", "test/api/token")));
        String log = Files.readString(w.resolve("logs/run.log"));
        assertTrue(log.contains("WARN token was **** here") && !log.contains("tok-123"), log);
        assertEquals(true, o.get("b"));
        assertEquals("Rejected", o.get("t"));
    }

    @Test void scriptsPythonAndJavaScriptWithArgsAndJson() throws Exception {
        if (!onPath("python3") && !onPath("python")) { System.out.println("    (python not installed; skipped)"); return; }
        Map<String, Object> o = run(tmp(), "[{\"name\":\"j\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"code\",\"type\":\"Number\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.script/run_python\",\"args\":{\"script\":\"import sys, json\\nprint(json.dumps({'args': sys.argv[1:], 'in': sys.stdin.read()}))\","
                + "\"arguments\":[\"a b\",\"c\"],\"input\":\"hello\"},\"out\":{\"json\":\"j\",\"exit_code\":\"code\"}}]", Map.of());
        @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) o.get("j");
        assertEquals(List.of("a b", "c"), j.get("args"), "arguments are passed as-is, without shell splitting");
        assertEquals("hello", j.get("in"));
        RunResult r = runRaw(tmp(), "[]", "[{\"id\":\"f\",\"action\":\"ooa.script/run_python\",\"args\":{\"script\":\"import sys\\nsys.exit(3)\"}}]");
        assertEquals("SCRIPT.FAILED", r.error().get("code"));
        if (onPath("node")) {
            Map<String, Object> js = run(tmp(), "[{\"name\":\"out\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"1\",\"action\":\"ooa.script/run_javascript\",\"args\":{\"script\":\"console.log(process.argv.slice(2).join('+'))\",\"arguments\":[1,2]},\"out\":{\"output\":\"out\"}}]", Map.of());
            assertEquals("1+2", ((String) js.get("out")).strip());
        }
    }

    @Test void applicationRunProgramTimeoutAndMissingProgram() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace("\\", "\\\\");
        Map<String, Object> o = run(tmp(), "[{\"name\":\"code\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"err\",\"type\":\"String\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.application/run_program\",\"args\":{\"program\":\"" + java + "\",\"arguments\":[\"-version\"]},\"out\":{\"exit_code\":\"code\",\"error_output\":\"err\"}}]", Map.of());
        eqNum("0", o.get("code"));
        assertTrue(((String) o.get("err")).contains("version"));
        RunResult missing = runRaw(tmp(), "[]", "[{\"id\":\"m\",\"action\":\"ooa.application/run_program\",\"args\":{\"program\":\"no-such-program-ooa\"}}]");
        assertEquals("PROCESS.NOT_FOUND", missing.error().get("code"));
        if (onPath("python3")) {
            RunResult slow = runRaw(tmp(), "[]", "[{\"id\":\"s\",\"action\":\"ooa.application/run_program\",\"args\":{\"program\":\"python3\",\"arguments\":[\"-c\",\"import time; time.sleep(10)\"],\"timeout_seconds\":1}}]");
            assertEquals("PROCESS.TIMEOUT", slow.error().get("code"));
        }
    }

    @Test void networkPortCheck() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            Map<String, Object> o = run(tmp(), "[{\"name\":\"open\",\"type\":\"Boolean\",\"direction\":\"output\"},{\"name\":\"closed\",\"type\":\"Boolean\",\"direction\":\"output\"}]",
                    "[{\"id\":\"1\",\"action\":\"ooa.network/check_port\",\"args\":{\"host\":\"127.0.0.1\",\"port\":" + ss.getLocalPort() + "},\"out\":{\"open\":\"open\"}},"
                    + "{\"id\":\"2\",\"action\":\"ooa.network/check_port\",\"args\":{\"host\":\"127.0.0.1\",\"port\":1,\"timeout_ms\":500},\"out\":{\"open\":\"closed\"}}]", Map.of());
            assertEquals(true, o.get("open"));
            assertEquals(false, o.get("closed"));
        }
    }

    @Test void soapResponseAndFault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/svc", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String action = ex.getRequestHeaders().getFirst("SOAPAction");
            boolean fault = req.contains("BAD");
            String resp = fault
                    ? "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>Unknown customer</faultstring></s:Fault></s:Body></s:Envelope>"
                    : "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><GetRateResponse><rate>1.17</rate><action>" + action + "</action></GetRateResponse></s:Body></s:Envelope>";
            ex.sendResponseHeaders(fault ? 500 : 200, resp.length());
            try (OutputStream os = ex.getResponseBody()) { os.write(resp.getBytes()); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/svc";
            Map<String, Object> o = run(tmp(), "[{\"name\":\"b\",\"type\":\"Any\",\"direction\":\"output\"}]",
                    "[{\"id\":\"1\",\"action\":\"ooa.soap/send\",\"args\":{\"url\":\"" + url + "\",\"soap_action\":\"urn:GetRate\",\"body\":\"<GetRate><currency>EUR</currency></GetRate>\"},\"out\":{\"body\":\"b\"}}]", Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) o.get("b");
            assertEquals("1.17", b.get("rate"));
            assertEquals("\"urn:GetRate\"", b.get("action"));
            RunResult f = runRaw(tmp(), "[]", "[{\"id\":\"f\",\"action\":\"ooa.soap/send\",\"args\":{\"url\":\"" + url + "\",\"body\":\"<GetRate>BAD</GetRate>\"}}]");
            assertEquals("SOAP.FAULT", f.error().get("code"));
            assertTrue(String.valueOf(f.error().get("message")).contains("Unknown customer"));
        } finally { server.stop(0); }
    }

    @Test void ldapFilterValuesAreEscaped() {
        assertEquals("jdoe\\29\\28|\\28sAMAccountName=\\2a", ActiveDirectoryPackage.escape("jdoe)(|(sAMAccountName=*"));
    }

    @Test void imageRecognitionFindsTemplate() throws Exception {
        Path w = tmp();
        Random rnd = new Random(42);
        BufferedImage screen = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) for (int x = 0; x < 640; x++) screen.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        BufferedImage button = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 48; x++) button.setRGB(x, y, (x < 4 || y < 4 || x > 43 || y > 27) ? 0x0A7BBB : ((x / 6 + y / 6) % 2 == 0 ? 0xFFFFFF : 0xA371ED));
        for (int y = 0; y < 32; y++) for (int x = 0; x < 48; x++) screen.setRGB(311 + x, 177 + y, button.getRGB(x, y));
        ImageIO.write(screen, "png", w.resolve("screen.png").toFile());
        ImageIO.write(button, "png", w.resolve("button.png").toFile());
        BufferedImage other = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 48; x++) other.setRGB(x, y, 0x123456);
        ImageIO.write(other, "png", w.resolve("other.png").toFile());
        Map<String, Object> o = run(w, "[{\"name\":\"f\",\"type\":\"Boolean\",\"direction\":\"output\"},{\"name\":\"x\",\"type\":\"Number\",\"direction\":\"output\"},"
                + "{\"name\":\"y\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"none\",\"type\":\"Boolean\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.image/find_in_image\",\"args\":{\"image\":\"screen.png\",\"template\":\"button.png\"},\"out\":{\"found\":\"f\",\"x\":\"x\",\"y\":\"y\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.image/find_in_image\",\"args\":{\"image\":\"screen.png\",\"template\":\"other.png\",\"tolerance\":5},\"out\":{\"found\":\"none\"}}]", Map.of());
        assertEquals(true, o.get("f"));
        eqNum(String.valueOf(311 + 24), o.get("x"));
        eqNum(String.valueOf(177 + 16), o.get("y"));
        assertEquals(false, o.get("none"));
    }

    @Test void desktopAndServicesReportClearErrorsWhereUnavailable() throws Exception {
        System.setProperty("java.awt.headless", "true");
        RunResult d = runRaw(tmp(), "[]", "[{\"id\":\"c\",\"action\":\"ooa.desktop/mouse_click\",\"args\":{\"x\":1,\"y\":1}}]");
        if (java.awt.GraphicsEnvironment.isHeadless()) assertEquals("DESKTOP.NO_SESSION", d.error().get("code"));
        RunResult k = runRaw(tmp(), "[]", "[{\"id\":\"k\",\"action\":\"ooa.desktop/press_keys\",\"args\":{\"keys\":\"CTRL+NOPE\"}}]");
        assertEquals("DESKTOP.BAD_KEY", k.error().get("code"), "key names are checked before touching the desktop");
        if (!ProcessRunner.WINDOWS) {
            RunResult s = runRaw(tmp(), "[]", "[{\"id\":\"s\",\"action\":\"ooa.services/get_status\",\"args\":{\"name\":\"Spooler\"}}]");
            assertEquals("SYSTEM.WINDOWS_ONLY", s.error().get("code"));
        }
        Map<String, Object> p = run(tmp(), "[{\"name\":\"list\",\"type\":\"List\",\"direction\":\"output\"}]",
                "[{\"id\":\"p\",\"action\":\"ooa.printer/list_printers\",\"out\":{\"printers\":\"list\"}}]", Map.of());
        assertNotNull(p.get("list"));
    }
}
