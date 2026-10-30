package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;
import org.w3c.dom.*;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.*;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/** SAML sign-in against a test identity provider that signs real assertions; every check is tried with a bad response. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SamlTest {
    static final String ORIGIN = "https://ooa.example.com", IDP = "https://idp.example.com/saml";
    Db db; ControlRoom cr; ControlRoom.User admin;
    PrivateKey idpKey, otherKey; X509Certificate idpCert, otherCert;
    Instant now = Instant.parse("2026-11-03T09:00:00Z");

    @BeforeAll void start() throws Exception {
        Path tmp = Files.createTempDirectory("ooa-saml");
        Path data = Files.createDirectories(tmp.resolve("data"));
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        KeyStore ks = keyPair(tmp, "idp"); idpKey = (PrivateKey) ks.getKey("k", "pw-123456".toCharArray()); idpCert = (X509Certificate) ks.getCertificate("k");
        KeyStore other = keyPair(tmp, "other"); otherKey = (PrivateKey) other.getKey("k", "pw-123456".toCharArray()); otherCert = (X509Certificate) other.getCertificate("k");
        String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(idpCert.getEncoded()) + "\n-----END CERTIFICATE-----";
        cr.platform.updateSettings(admin, Map.of("saml_idp_sso_url", IDP + "/sso", "saml_idp_entity_id", IDP, "saml_idp_certificate", pem, "sso_default_role", "viewer"), null);
        cr.saml.clock = Clock.fixed(now, ZoneOffset.UTC);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    /** A key pair and self-signed certificate made with the JDK's keytool. */
    static KeyStore keyPair(Path dir, String name) throws Exception {
        Path p12 = dir.resolve(name + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool").toString();
        Process pr = new ProcessBuilder(keytool, "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12",
            "-keystore", p12.toString(), "-storepass", "pw-123456", "-keypass", "pw-123456", "-dname", "CN=" + name).redirectErrorStream(true).start();
        pr.getInputStream().readAllBytes();
        assertEquals(0, pr.waitFor());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(p12)) { ks.load(in, "pw-123456".toCharArray()); }
        return ks;
    }

    /** Starts a sign-in and returns the request id the identity provider must answer. */
    String startSignIn() throws Exception { return startSignIn("/"); }

    /** The same, coming back to a page of the Command Center after sign-in. */
    String startSignIn(String back) throws Exception {
        String url = cr.saml.start(ORIGIN, back);
        assertTrue(url.startsWith(IDP + "/sso?SAMLRequest="));
        byte[] deflated = Base64.getDecoder().decode(URLDecoder.decode(url.substring(url.indexOf('=') + 1), StandardCharsets.UTF_8));
        Inflater inf = new Inflater(true); inf.setInput(deflated);
        byte[] out = new byte[8192]; int n = inf.inflate(out);
        String req = new String(out, 0, n, StandardCharsets.UTF_8);
        assertTrue(req.contains("AssertionConsumerServiceURL=\"" + ORIGIN + "/api/auth/saml/acs\""), req);
        return req.replaceAll("(?s).* ID=\"([^\"]+)\".*", "$1");
    }

    /** A response as an identity provider makes it; the knobs break one rule each. */
    String response(String inResponseTo, String user, Map<String, Object> o) throws Exception {
        String aid = "_a" + UUID.randomUUID().toString().replace("-", "");
        String audience = (String) o.getOrDefault("audience", ORIGIN + "/api/auth/saml/metadata");
        Instant notAfter = now.plusSeconds(((Number) o.getOrDefault("valid", 300)).longValue());
        String assertion = "<saml:Assertion xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + aid + "\" Version=\"2.0\" IssueInstant=\"" + now + "\">"
            + "<saml:Issuer>" + o.getOrDefault("issuer", IDP) + "</saml:Issuer>"
            + "<saml:Subject><saml:NameID Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress\">" + user + "</saml:NameID>"
            + "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><saml:SubjectConfirmationData InResponseTo=\"" + inResponseTo + "\" NotOnOrAfter=\"" + notAfter
            + "\" Recipient=\"" + ORIGIN + "/api/auth/saml/acs\"/></saml:SubjectConfirmation></saml:Subject>"
            + "<saml:Conditions NotBefore=\"" + now.minusSeconds(60) + "\" NotOnOrAfter=\"" + notAfter + "\"><saml:AudienceRestriction><saml:Audience>" + audience + "</saml:Audience></saml:AudienceRestriction></saml:Conditions>"
            + "<saml:AttributeStatement><saml:Attribute Name=\"http://schemas.microsoft.com/identity/claims/displayname\"><saml:AttributeValue>Sam Lee</saml:AttributeValue></saml:Attribute>"
            + (o.get("groups") instanceof List<?> groups ? "<saml:Attribute Name=\"http://schemas.microsoft.com/ws/2008/06/identity/claims/groups\">"
                + groups.stream().map(g -> "<saml:AttributeValue>" + g + "</saml:AttributeValue>").collect(java.util.stream.Collectors.joining()) + "</saml:Attribute>" : "")
            + "</saml:AttributeStatement>"
            + "</saml:Assertion>";
        String xml = "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" ID=\"_r" + UUID.randomUUID().toString().replace("-", "") + "\" Version=\"2.0\" IssueInstant=\"" + now
            + "\" Destination=\"" + ORIGIN + "/api/auth/saml/acs\" InResponseTo=\"" + inResponseTo + "\"><samlp:Status><samlp:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/></samlp:Status>"
            + assertion + (o.containsKey("second") ? assertion.replace(aid, aid + "x") : "") + "</samlp:Response>";
        Document doc = Saml.parse(xml.getBytes(StandardCharsets.UTF_8));
        Element as = (Element) doc.getElementsByTagNameNS(Saml.A, "Assertion").item(0);
        as.setIdAttribute("ID", true);
        if (!Boolean.TRUE.equals(o.get("unsigned"))) {
            XMLSignatureFactory f = XMLSignatureFactory.getInstance("DOM");
            Reference ref = f.newReference("#" + aid, f.newDigestMethod(DigestMethod.SHA256, null),
                List.of(f.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null), f.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)), null, null);
            SignedInfo si = f.newSignedInfo(f.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                f.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null), List.of(ref));
            Node subject = as.getElementsByTagNameNS(Saml.A, "Subject").item(0);
            f.newXMLSignature(si, null).sign(new DOMSignContext(o.containsKey("otherKey") ? otherKey : idpKey, as, subject));
        }
        if (o.containsKey("tamper")) as.getElementsByTagNameNS(Saml.A, "NameID").item(0).setTextContent((String) o.get("tamper"));
        StringWriter w = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(w));
        return Base64.getEncoder().encodeToString(w.toString().getBytes(StandardCharsets.UTF_8));
    }

    String code(Runnable r) { return assertThrows(OoaException.class, r::run).code(); }
    String why(Runnable r) { return assertThrows(OoaException.class, r::run).getMessage(); }

    @Test void eachRefusalHasItsOwnReason() {
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("tamper", "admin"))).contains("signature is not valid"));
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("otherKey", true))).contains("signature is not valid"));
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("unsigned", true))).contains("not signed"));
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("second", true))).contains("exactly one assertion"));
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("audience", "https://other.app"))).contains("audience"));
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("valid", -600))).contains("expired"));
        assertTrue(why(() -> run(startSafe(), "x@example.com", Map.of("issuer", "https://evil.example"))).contains("another identity provider"));
        assertTrue(why(() -> run("_never_started", "x@example.com", Map.of())).contains("not started here"));
    }

    @Test void aSignedAssertionSignsThePersonIn() throws Exception {
        String req = startSignIn();
        Map<String, Object> r = cr.saml.acs(response(req, "sam@example.com", Map.of()), ORIGIN, null);
        assertNotNull(r.get("token"));
        @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>) r.get("user");
        assertEquals("sam@example.com", user.get("username"));
        assertEquals("Sam Lee", user.get("display_name"));
        assertEquals("viewer", user.get("role"), "new people get the default role");
        assertNotNull(db.one("SELECT sso_subject FROM users WHERE username = 'sam@example.com'").get("sso_subject"));
    }

    @SuppressWarnings("unchecked")
    @Test void groupsChooseTheRoleAndSignInComesBackWhereItStarted() throws Exception {
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("sso_group_roles", "just a group"), null)), "each line is group = role");
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("sso_group_roles", "OOA Builders = no_such_role"), null)), "only roles that exist");
        cr.platform.updateSettings(admin, Map.of("sso_group_roles", "# from the identity provider\nOOA Admins = admin\nOOA Builders = bot_creator"), null);
        try {
            // a new person: the role their groups give, not the default; back on the page the sign-in started from
            Map<String, Object> r = cr.saml.acs(response(startSignIn("/next/#/activity"), "gail@example.com", Map.of("groups", List.of("Everyone", "ooa builders"))), ORIGIN, null);
            assertEquals("bot_creator", ((Map<String, Object>) r.get("user")).get("role"));
            assertEquals("/next/#/activity", r.get("return"));
            // moved to another group in the identity provider: the role follows at the next sign-in, and it is audited
            r = cr.saml.acs(response(startSignIn("/next/"), "gail@example.com", Map.of("groups", List.of("OOA Admins", "OOA Builders"))), ORIGIN, null);
            assertEquals("admin", ((Map<String, Object>) r.get("user")).get("role"), "the first matching line wins");
            assertNotNull(db.one("SELECT seq FROM audit WHERE action = 'user.role' AND object_id = 'gail@example.com'"));
            // no matching group: the role stays; a link to another site is never followed
            r = cr.saml.acs(response(startSignIn("https://evil.example/steal"), "gail@example.com", Map.of("groups", List.of("Sales"))), ORIGIN, null);
            assertEquals("admin", ((Map<String, Object>) r.get("user")).get("role"));
            assertEquals("/", r.get("return"));
            for (String bad : List.of("//evil.example", "/\\evil","javascript:alert(1)", "/next/\"><script>", ""))
                assertEquals("/", ControlRoom.safeReturn(bad), bad);
            // local accounts still sign in with their password while single sign-on is on (no one-way switch)
            assertNotNull(cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        } finally { cr.platform.updateSettings(admin, Map.of("sso_group_roles", ""), null); }
    }

    @Test void everyRuleIsChecked() throws Exception {
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("tamper", "admin"))), "a changed assertion");
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("otherKey", true))), "signed by another key");
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("unsigned", true))), "not signed");
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("second", true))), "two assertions (signature wrapping)");
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("audience", "https://other.app"))), "another audience");
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("valid", -600))), "expired");
        assertEquals("SSO.INVALID", code(() -> run(startSafe(), "eve@example.com", Map.of("issuer", "https://evil.example"))), "another issuer");
        assertEquals("SSO.INVALID", code(() -> run("_never_started", "eve@example.com", Map.of())), "not started here (IdP-initiated or forged)");
        String req = startSafe(), good = responseSafe(req);
        cr.saml.acs(good, ORIGIN, null);
        assertEquals("SSO.INVALID", code(() -> cr.saml.acs(good, ORIGIN, null)), "the same response twice");
        String dtd = Base64.getEncoder().encodeToString("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]><r>&x;</r>".getBytes(StandardCharsets.UTF_8));
        assertEquals("SSO.INVALID", code(() -> cr.saml.acs(dtd, ORIGIN, null)), "DTDs and external entities are refused");
        assertNull(db.one("SELECT id FROM users WHERE username = 'eve@example.com'"), "nobody was created");
    }

    @Test void metadataAndSettings() {
        String md = cr.saml.metadata(ORIGIN);
        assertTrue(md.contains("entityID=\"" + ORIGIN + "/api/auth/saml/metadata\""));
        assertTrue(md.contains("Location=\"" + ORIGIN + "/api/auth/saml/acs\""));
        assertEquals(true, cr.saml.info().get("enabled"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, Map.of("saml_idp_certificate", "not a certificate"), null)));
    }

    void run(String req, String user, Map<String, Object> o) { cr.saml.acs(responseSafe(req, user, o), ORIGIN, null); }
    String startSafe() { try { return startSignIn(); } catch (Exception e) { throw new RuntimeException(e); } }
    String responseSafe(String req) { return responseSafe(req, "pat@example.com", Map.of()); }
    String responseSafe(String req, String user, Map<String, Object> o) { try { return response(req, user, o); } catch (Exception e) { throw new RuntimeException(e); } }
}
