package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.EventSink;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionSpec;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.REG;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** G6 rows for the REST and SOAP web service commands. */
class WebServiceG6Test {
    static final String SOAP_ANSWER = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><r:Resp xmlns:r=\"urn:r\">"
            + "<r:Price>1</r:Price><r:Price>2</r:Price></r:Resp></soap:Body></soap:Envelope>";

    record Run(RunResult result, String events) {}

    /** A one-run agent with a vault holding a token, a URL and a body; variable tok is the token. */
    @SuppressWarnings("unchecked")
    static Run run(Path w, String outVars, String steps, String secretUrl) {
        String vars = "[" + TOKEN_VAR + (outVars.isEmpty() ? "" : "," + outVars) + "]";
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + vars + ",\"steps\":" + steps + "}");
        CredentialVault.InMemory vault = new CredentialVault.InMemory().put("test", "api", "token", "tok-123")
                .put("test", "api", "body", "{\"pin\":\"9911\"}");
        if (secretUrl != null) vault.put("test", "api", "url", secretUrl);
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(REG).workDir(w).sink(sink).service("vault", vault).build().run(flow, TOKEN);
        return new Run(r, sink.events().toString());
    }

    static String step(String action, String args, String out) {
        return "{\"id\":\"s\",\"action\":\"" + action + "\",\"args\":{" + args + "}" + (out == null ? "" : ",\"out\":{" + out + "}") + "}";
    }

    // ---------- row 1: the URL may come from the vault ----------

    @Test void restUrlFromTheVaultIsUsedAndLoggedAsSchemeAndHostOnly() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /secret/path-7731", c -> ok("{\"ok\":true}")).on("GET /files/secret-7731.txt", c -> ok("hello"));
            Run r = run(tmp(), "", "[" + step("ooa.rest/request", "\"url\":{\"$credential\":\"test/api/url\"}", null) + "]", f.url() + "/secret/path-7731");
            assertTrue(r.result().ok(), String.valueOf(r.result().error()));
            assertNotNull(f.last("GET", "/secret/path-7731"));
            assertTrue(r.events().contains("GET http://127.0.0.1/****"), r.events());
            assertFalse(r.events().contains("path-7731"), r.events());

            Path w = tmp();
            Run d = run(w, "", "[" + step("ooa.rest/download_file", "\"url\":{\"$credential\":\"test/api/url\"},\"path\":\"a.txt\"", null) + "]", f.url() + "/files/secret-7731.txt");
            assertTrue(d.result().ok(), String.valueOf(d.result().error()));
            assertEquals("hello", Files.readString(w.resolve("a.txt")));
            assertFalse(d.events().contains("secret-7731"), d.events());

            Run e = run(tmp(), "", "[" + step("ooa.rest/request", "\"url\":{\"$credential\":\"test/api/url\"}", null) + "]", f.url() + "/missing-7731");
            assertFalse(e.result().ok());
            assertFalse(String.valueOf(e.result().error().get("message")).contains("missing-7731"), String.valueOf(e.result().error()));
        }
    }

    @Test void urlAndBodyAcceptCredentials() {
        ActionSpec req = REG.resolve("ooa.rest", null).spec("action", "request");
        assertTrue(req.input("url").orElseThrow().acceptsCredential());
        assertTrue(req.input("body").orElseThrow().acceptsCredential());
        assertTrue(REG.resolve("ooa.rest", null).spec("action", "download_file").input("url").orElseThrow().acceptsCredential());
        assertTrue(REG.resolve("ooa.rest", null).spec("action", "upload_file").input("url").orElseThrow().acceptsCredential());
        assertTrue(REG.resolve("ooa.soap", null).spec("action", "send").input("body").orElseThrow().acceptsCredential());
    }

    // ---------- row 3: placeholders in the body, and the body from the vault ----------

    @Test void restReplaceInBodyAndBodyFromTheVault() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/login", c -> ok("{}"));
            String url = f.url() + "/v1/login";
            Run r = run(tmp(), "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "\",\"method\":\"POST\",\"content_type\":\"application/json\","
                    + "\"body\":\"{\\\"user\\\":\\\"bot\\\",\\\"key\\\":\\\"{{key}}\\\"}\",\"replace_in_body\":{\"key\":\"$tok\"}", null) + "]", null);
            assertTrue(r.result().ok(), String.valueOf(r.result().error()));
            assertEquals("{\"user\":\"bot\",\"key\":\"tok-123\"}", f.last("POST", "/v1/login").text());
            assertFalse(r.events().contains("tok-123"), r.events());

            Run b = run(tmp(), "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "\",\"method\":\"POST\",\"body\":{\"$credential\":\"test/api/body\"}", null) + "]", null);
            assertTrue(b.result().ok(), String.valueOf(b.result().error()));
            assertEquals("{\"pin\":\"9911\"}", f.last("POST", "/v1/login").text());

            Run none = run(tmp(), "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "\",\"method\":\"POST\",\"replace_in_body\":{\"key\":\"$tok\"}", null) + "]", null);
            assertEquals("REST.BAD_BODY", none.result().error().get("code"));
        }
    }

    // ---------- row 4: download overwrite is off for new steps ----------

    @Test void downloadOverwriteIsOffForNewStepsOnly() {
        ActionSpec.InputSpec in = REG.resolve("ooa.rest", null).spec("action", "download_file").input("overwrite").orElseThrow();
        assertEquals(true, in.defaultValue());
        assertEquals(false, in.newStepDefault());
    }

    // ---------- rows 5 and 9: a private certificate authority ----------

    @Test void trustCertificateLetsAPrivateCaThroughWithoutInsecureTls() throws Exception {
        Path w = tmp();
        SSLContext server = selfSignedServer(w);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(server));
        https.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = (ex.getRequestURI().getPath().startsWith("/soap") ? SOAP_ANSWER : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        https.start();
        try {
            String url = "https://127.0.0.1:" + https.getAddress().getPort();
            Run plain = run(w, "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "/a\"", null) + "]", null);
            assertEquals("REST.UNREACHABLE", plain.result().error().get("code"), "an unknown CA is refused");

            Run trusted = run(w, "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "/a\",\"trust_certificate\":\"ca.pem\"", null) + "]", null);
            assertTrue(trusted.result().ok(), String.valueOf(trusted.result().error()));

            Run dl = run(w, "", "[" + step("ooa.rest/download_file", "\"url\":\"" + url + "/f\",\"path\":\"f.json\",\"trust_certificate\":\"ca.pem\"", null) + "]", null);
            assertTrue(dl.result().ok(), String.valueOf(dl.result().error()));
            Files.writeString(w.resolve("up.txt"), "x");
            Run up = run(w, "", "[" + step("ooa.rest/upload_file", "\"url\":\"" + url + "/u\",\"path\":\"up.txt\",\"trust_certificate\":\"ca.pem\"", null) + "]", null);
            assertTrue(up.result().ok(), String.valueOf(up.result().error()));

            Run soap = run(w, "", "[" + step("ooa.soap/send", "\"url\":\"" + url + "/soap\",\"body\":\"<x/>\",\"trust_certificate\":\"ca.pem\"", null) + "]", null);
            assertTrue(soap.result().ok(), String.valueOf(soap.result().error()));
            Run soapPlain = run(w, "", "[" + step("ooa.soap/send", "\"url\":\"" + url + "/soap\",\"body\":\"<x/>\"", null) + "]", null);
            assertEquals("SOAP.UNREACHABLE", soapPlain.result().error().get("code"));

            Run both = run(w, "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "/a\",\"trust_certificate\":\"ca.pem\",\"insecure_tls\":true", null) + "]", null);
            assertEquals("REST.BAD_TLS", both.result().error().get("code"));
            Run missing = run(w, "", "[" + step("ooa.rest/request", "\"url\":\"" + url + "/a\",\"trust_certificate\":\"nope.pem\"", null) + "]", null);
            assertEquals("REST.CERTIFICATE_NOT_FOUND", missing.result().error().get("code"));
        } finally { https.stop(0); }
    }

    /** A self-signed certificate for 127.0.0.1 made with the JDK's keytool; its certificate is written to ca.pem. */
    static SSLContext selfSignedServer(Path w) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool").toString();
        Path ks = w.resolve("server.p12");
        exec(List.of(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit"));
        exec(List.of(keytool, "-exportcert", "-rfc", "-alias", "s", "-keystore", ks.toString(), "-storepass", "changeit", "-file", w.resolve("ca.pem").toString()));
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) { k.load(in, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, "changeit".toCharArray());
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    static void exec(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    // ---------- row 6: the body built from the WSDL ----------

    static String wsdl(String base) {
        return "<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\" xmlns:soap12=\"http://schemas.xmlsoap.org/wsdl/soap12/\""
                + " xmlns:tns=\"urn:shop\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:shop\">"
                + "<types><xs:schema targetNamespace=\"urn:shop\" elementFormDefault=\"qualified\">"
                + "<xs:element name=\"GetPrice\"><xs:complexType><xs:sequence>"
                + "<xs:element name=\"Item\" type=\"xs:string\"/><xs:element name=\"Qty\" type=\"xs:int\" minOccurs=\"0\"/><xs:element name=\"Note\" type=\"xs:string\" minOccurs=\"0\"/>"
                + "<xs:element name=\"Customer\" minOccurs=\"0\"><xs:complexType><xs:sequence><xs:element name=\"Name\" type=\"xs:string\"/></xs:sequence></xs:complexType></xs:element>"
                + "</xs:sequence></xs:complexType></xs:element></xs:schema></types>"
                + "<message name=\"GetPriceIn\"><part name=\"parameters\" element=\"tns:GetPrice\"/></message>"
                + "<portType name=\"ShopPort\"><operation name=\"GetPrice\"><input message=\"tns:GetPriceIn\"/></operation></portType>"
                + "<binding name=\"ShopSoap\" type=\"tns:ShopPort\"><soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>"
                + "<operation name=\"GetPrice\"><soap:operation soapAction=\"urn:shop/GetPrice\"/><input><soap:body use=\"literal\"/></input></operation></binding>"
                + "<binding name=\"ShopSoap12\" type=\"tns:ShopPort\"><soap12:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>"
                + "<operation name=\"GetPrice\"><soap12:operation soapAction=\"urn:shop/GetPrice12\"/><input><soap12:body use=\"literal\"/></input></operation></binding>"
                + "<service name=\"Shop\"><port name=\"ShopSoap\" binding=\"tns:ShopSoap\"><soap:address location=\"" + base + "/soap11\"/></port>"
                + "<port name=\"ShopSoap12\" binding=\"tns:ShopSoap12\"><soap12:address location=\"" + base + "/soap12\"/></port></service></definitions>";
    }

    @Test void soapBodyBuiltFromTheWsdl() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /soap11", c -> new Object[]{200, SOAP_ANSWER}).on("POST /soap12", c -> new Object[]{200, SOAP_ANSWER})
             .on("POST /other", c -> new Object[]{200, SOAP_ANSWER}).on("GET /shop", c -> ok(wsdl(f.url())));
            Path w = tmp();
            Files.writeString(w.resolve("shop.wsdl"), wsdl(f.url()));

            Run r = run(w, "", "[" + step("ooa.soap/send", "\"wsdl\":\"shop.wsdl\",\"operation\":\"GetPrice\",\"parameters\":{\"Item\":\"A1 & B\",\"Qty\":2}", null) + "]", null);
            assertTrue(r.result().ok(), String.valueOf(r.result().error()));
            Call c = f.last("POST", "/soap11");
            assertTrue(c.text().contains("<soap:Body><ns:GetPrice xmlns:ns=\"urn:shop\"><ns:Item>A1 &amp; B</ns:Item><ns:Qty>2</ns:Qty></ns:GetPrice></soap:Body>"), c.text());
            assertEquals("\"urn:shop/GetPrice\"", c.header("SOAPAction"));

            // the port picks the address and SOAP version; the WSDL may be read from its address
            Run r12 = run(w, "", "[" + step("ooa.soap/send", "\"wsdl\":\"" + f.url() + "/shop\",\"service\":\"Shop\",\"port\":\"ShopSoap12\",\"operation\":\"GetPrice\",\"parameters\":{\"Item\":\"A1\"}", null) + "]", null);
            assertTrue(r12.result().ok(), String.valueOf(r12.result().error()));
            Call c12 = f.last("POST", "/soap12");
            assertTrue(c12.contentType().contains("application/soap+xml") && c12.contentType().contains("urn:shop/GetPrice12"), c12.contentType());

            // namespace and prefix replaced, fields unqualified, a credential value and an address override
            Run o = run(w, "", "[" + step("ooa.soap/send", "\"url\":\"" + f.url() + "/other\",\"wsdl\":\"shop.wsdl\",\"operation\":\"GetPrice\",\"namespace\":\"urn:other\",\"prefix\":\"tem\","
                    + "\"qualify_elements\":false,\"soap_action\":\"urn:mine\",\"parameters\":{\"Item\":\"A1\",\"Customer/Name\":\"$tok\"}", null) + "]", null);
            assertTrue(o.result().ok(), String.valueOf(o.result().error()));
            Call co = f.last("POST", "/other");
            assertTrue(co.text().contains("<tem:GetPrice xmlns:tem=\"urn:other\"><Item>A1</Item><Customer><Name>tok-123</Name></Customer></tem:GetPrice>"), co.text());
            assertEquals("\"urn:mine\"", co.header("SOAPAction"));
            assertFalse(o.events().contains("tok-123"));

            Run bad = run(w, "", "[" + step("ooa.soap/send", "\"wsdl\":\"shop.wsdl\",\"operation\":\"GetPrice\",\"parameters\":{\"Colour\":\"red\"}", null) + "]", null);
            assertEquals("SOAP.UNKNOWN_PARAMETER", bad.result().error().get("code"));
            assertTrue(String.valueOf(bad.result().error().get("suggestedFix")).contains("GetPrice/Item"), String.valueOf(bad.result().error()));
            Run noOp = run(w, "", "[" + step("ooa.soap/send", "\"wsdl\":\"shop.wsdl\",\"operation\":\"Nope\"", null) + "]", null);
            assertEquals("SOAP.UNKNOWN_OPERATION", noOp.result().error().get("code"));
            Run noPort = run(w, "", "[" + step("ooa.soap/send", "\"wsdl\":\"shop.wsdl\",\"port\":\"Nope\",\"operation\":\"GetPrice\"", null) + "]", null);
            assertEquals("SOAP.UNKNOWN_PORT", noPort.result().error().get("code"));
            Run both = run(w, "", "[" + step("ooa.soap/send", "\"wsdl\":\"shop.wsdl\",\"operation\":\"GetPrice\",\"body\":\"<x/>\"", null) + "]", null);
            assertEquals("SOAP.BAD_BODY", both.result().error().get("code"));
        }
    }

    // ---------- row 7: placeholders and the body from the vault, for SOAP ----------

    @Test void soapReplaceInBodyAndBodyFromTheVault() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /soap", c -> new Object[]{200, SOAP_ANSWER});
            String url = f.url() + "/soap";
            Run r = run(tmp(), "", "[" + step("ooa.soap/send", "\"url\":\"" + url + "\",\"body\":\"<Login><Key>{{key}}</Key></Login>\",\"replace_in_body\":{\"key\":\"$tok\"}", null) + "]", null);
            assertTrue(r.result().ok(), String.valueOf(r.result().error()));
            assertTrue(f.last("POST", "/soap").text().contains("<Login><Key>tok-123</Key></Login>"));
            assertFalse(r.events().contains("tok-123"));

            CredentialVault.InMemory v = new CredentialVault.InMemory().put("test", "api", "token", "tok-123").put("test", "api", "xml", "<Pin>4242</Pin>");
            @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":[" + TOKEN_VAR + "],\"steps\":["
                    + step("ooa.soap/send", "\"url\":\"" + url + "\",\"body\":{\"$credential\":\"test/api/xml\"}", null) + "]}");
            RunResult b = Runner.builder(REG).workDir(tmp()).service("vault", v).build().run(flow, TOKEN);
            assertTrue(b.ok(), String.valueOf(b.error()));
            assertTrue(f.last("POST", "/soap").text().contains("<soap:Body><Pin>4242</Pin></soap:Body>"));
        }
    }

    // ---------- row 8: what xpath returns ----------

    @Test void soapXpathOutputChoices() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /soap", c -> new Object[]{200, SOAP_ANSWER});
            String base = "\"url\":\"" + f.url() + "/soap\",\"body\":\"<x/>\",";
            String vars = "{\"name\":\"t\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"v\",\"type\":\"List\",\"direction\":\"output\"}";
            String price = "\"xpath\":\"//*[local-name()='Price']\",";

            Map<String, Object> list = outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + price + "\"xpath_output\":\"values\"", "\"values\":\"v\",\"text\":\"t\"") + "]", null));
            assertEquals(List.of("1", "2"), list.get("v"));
            assertEquals("", list.get("t"));

            assertEquals("1|2", outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + price + "\"xpath_output\":\"joined\"", "\"text\":\"t\"") + "]", null)).get("t"));
            assertEquals("1;2", outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + price + "\"xpath_output\":\"joined\",\"delimiter\":\"semicolon\"", "\"text\":\"t\"") + "]", null)).get("t"));
            assertEquals("12", outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + price + "\"xpath_output\":\"joined\",\"delimiter\":\"none\"", "\"text\":\"t\"") + "]", null)).get("t"));
            assertEquals("1 + 2", outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + price + "\"xpath_output\":\"joined\",\"delimiter\":\"custom\",\"custom_delimiter\":\" + \"", "\"text\":\"t\"") + "]", null)).get("t"));

            String resp = "\"xpath\":\"//*[local-name()='Resp']\",";
            String inner = (String) outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + resp + "\"xpath_output\":\"inner_xml\"", "\"text\":\"t\"") + "]", null)).get("t");
            assertTrue(inner.startsWith("<r:Price") && inner.contains(">1</r:Price>") && inner.contains(">2</r:Price>") && !inner.contains("Resp"), inner);
            String outer = (String) outputs(run(tmp(), vars, "[" + step("ooa.soap/send", base + resp + "\"xpath_output\":\"outer_xml\"", "\"text\":\"t\"") + "]", null)).get("t");
            assertTrue(outer.startsWith("<r:Resp") && outer.contains("xmlns:r=\"urn:r\"") && outer.endsWith("</r:Resp>"), outer);
        }
    }

    static Map<String, Object> outputs(Run r) {
        assertTrue(r.result().ok(), String.valueOf(r.result().error()));
        return r.result().outputs();
    }
}
