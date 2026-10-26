package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import org.w3c.dom.*;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;

/**
 * SAML 2.0 single sign-on (service provider; HTTP-Redirect to the identity provider, HTTP-POST back). Works with Entra ID,
 * Okta, ADFS, Ping, Google Workspace and other SAML identity providers.
 * <p>What is checked: the XML is parsed with DTDs and external entities off; the document holds exactly one assertion,
 * a direct child of the response, and it, or the response, carries a valid signature by the configured identity provider
 * certificate whose one reference points at that element (enveloped and exclusive canonicalisation transforms only,
 * the JDK's secure validation on); the status is Success; the issuer, the audience (this Command Center's entity id), the
 * recipient and destination (the assertion consumer address), the validity window (2 minutes of clock skew) and that it
 * answers a sign-in this Command Center started (InResponseTo; sign-ins started at the identity provider are refused).
 * Each assertion is accepted once. Encrypted assertions are not supported.
 * <p>Settings: saml_idp_sso_url, saml_idp_entity_id, saml_idp_certificate (PEM), saml_sp_entity_id (default: the
 * metadata address), saml_label; new people get sso_default_role, as with OpenID Connect.
 */
final class Saml {
    static final String P = "urn:oasis:names:tc:SAML:2.0:protocol", A = "urn:oasis:names:tc:SAML:2.0:assertion";
    static final long SKEW_SECONDS = 120;
    private final ControlRoom cr;
    // sign-ins started here (10 minutes to come back) and assertion ids already used live in the store all nodes share
    /** The time responses are checked against (tests move it). */
    java.time.Clock clock = java.time.Clock.systemUTC();

    Saml(ControlRoom cr) { this.cr = cr; }

    private String s(String k) { return String.valueOf(cr.platform.setting(k)).trim(); }

    boolean enabled() { return !s("saml_idp_sso_url").isEmpty() && !s("saml_idp_certificate").isEmpty(); }

    Map<String, Object> info() { return Map.of("enabled", enabled(), "label", s("saml_label").isEmpty() ? "Sign in with SAML" : s("saml_label")); }

    /** This Command Center's entity id: the setting, else the metadata address. */
    String spEntityId(String origin) { return s("saml_sp_entity_id").isEmpty() ? origin + "/api/auth/saml/metadata" : s("saml_sp_entity_id"); }

    /** The address to send the person to: the identity provider with a deflated, Base64 AuthnRequest. */
    String start(String origin) {
        if (!enabled()) throw new OoaException("SSO.DISABLED", "SAML sign-in is not set up", "An administrator sets it up in Administration > Settings.");
        String id = "_" + Security.token(18).replace('-', 'a').replace('_', 'b');
        cr.cluster.put("saml", id, "", java.time.Duration.ofMinutes(10));
        String req = "<samlp:AuthnRequest xmlns:samlp=\"" + P + "\" xmlns:saml=\"" + A + "\" ID=\"" + id + "\" Version=\"2.0\" IssueInstant=\"" + clock.instant() + "\""
            + " Destination=\"" + xml(s("saml_idp_sso_url")) + "\" AssertionConsumerServiceURL=\"" + xml(origin + "/api/auth/saml/acs") + "\""
            + " ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"><saml:Issuer>" + xml(spEntityId(origin)) + "</saml:Issuer>"
            + "<samlp:NameIDPolicy AllowCreate=\"true\" Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\"/></samlp:AuthnRequest>";
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(req.getBytes(StandardCharsets.UTF_8)); d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        String url = s("saml_idp_sso_url");
        return url + (url.contains("?") ? "&" : "?") + "SAMLRequest=" + URLEncoder.encode(Base64.getEncoder().encodeToString(out.toByteArray()), StandardCharsets.UTF_8);
    }

    /** The metadata an identity provider imports: entity id and assertion consumer address (HTTP-POST). */
    String metadata(String origin) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\"" + xml(spEntityId(origin)) + "\">"
            + "<md:SPSSODescriptor AuthnRequestsSigned=\"false\" WantAssertionsSigned=\"true\" protocolSupportEnumeration=\"" + P + "\">"
            + "<md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>"
            + "<md:AssertionConsumerService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\" Location=\"" + xml(origin + "/api/auth/saml/acs") + "\" index=\"0\" isDefault=\"true\"/>"
            + "</md:SPSSODescriptor></md:EntityDescriptor>\n";
    }

    /** Checks a SAMLResponse posted back by the identity provider and signs the person in. */
    Map<String, Object> acs(String samlResponse, String origin, String ip) {
        if (!enabled()) throw new OoaException("SSO.DISABLED", "SAML sign-in is not set up");
        Map<String, Object> claims = validate(samlResponse, origin);
        return cr.ssoLogin(claims, s("sso_default_role"), ip);
    }

    /** Everything but the sign-in: returns the person's claims (iss, sub, email, name) or refuses with SSO.INVALID. */
    Map<String, Object> validate(String samlResponse, String origin) {
        if (samlResponse == null || samlResponse.length() > 2_000_000) throw bad("The identity provider sent no response");
        byte[] xmlBytes;
        try { xmlBytes = Base64.getMimeDecoder().decode(samlResponse); } catch (IllegalArgumentException e) { throw bad("The SAML response is not Base64"); }
        Document doc = parse(xmlBytes);
        Element resp = doc.getDocumentElement();
        if (!P.equals(resp.getNamespaceURI()) || !"Response".equals(resp.getLocalName())) throw bad("Not a SAML response");
        markIds(resp);
        String acs = origin + "/api/auth/saml/acs";
        if (resp.hasAttribute("Destination") && !acs.equals(resp.getAttribute("Destination"))) throw bad("The response is for another address: " + resp.getAttribute("Destination"));
        // status
        Element status = child(resp, P, "Status"), code = status == null ? null : child(status, P, "StatusCode");
        if (code == null || !"urn:oasis:names:tc:SAML:2.0:status:Success".equals(code.getAttribute("Value")))
            throw new OoaException("SSO.DENIED", "The identity provider did not sign you in" + (code == null ? "" : " (" + code.getAttribute("Value").replaceAll(".*:", "") + ")"));
        if (doc.getElementsByTagNameNS(A, "EncryptedAssertion").getLength() > 0) throw bad("Encrypted assertions are not supported", "Turn off assertion encryption for this application at the identity provider; the connection is protected by HTTPS.");
        // exactly one assertion, directly in the response (no wrapping)
        NodeList all = doc.getElementsByTagNameNS(A, "Assertion");
        if (all.getLength() != 1) throw bad("The response must hold exactly one assertion");
        Element as = (Element) all.item(0);
        if (as.getParentNode() != resp) throw bad("The assertion is not where it belongs");
        // a valid signature by the identity provider over the response or the assertion
        X509Certificate cert = certificate();
        boolean signed = false;
        for (Element holder : List.of(resp, as)) {
            Element sig = child(holder, XMLSignature.XMLNS, "Signature");
            if (sig != null && verify(sig, holder, cert)) signed = true;
            else if (sig != null) throw bad("The signature is not valid");
        }
        if (!signed) throw bad("The response is not signed", "Have the identity provider sign the assertion or the response.");
        // issuer
        String idp = s("saml_idp_entity_id");
        Element issuer = child(as, A, "Issuer");
        if (!idp.isEmpty() && (issuer == null || !idp.equals(issuer.getTextContent().trim()))) throw bad("The assertion comes from another identity provider");
        Element respIssuer = child(resp, A, "Issuer");
        if (!idp.isEmpty() && respIssuer != null && !idp.equals(respIssuer.getTextContent().trim())) throw bad("The response comes from another identity provider");
        Instant now = clock.instant();
        // conditions: time window and audience
        Element cond = child(as, A, "Conditions");
        if (cond == null) throw bad("The assertion has no conditions");
        if (cond.hasAttribute("NotBefore") && now.plusSeconds(SKEW_SECONDS).isBefore(Instant.parse(cond.getAttribute("NotBefore")))) throw bad("The assertion is not valid yet (check the clocks)");
        if (cond.hasAttribute("NotOnOrAfter") && !now.minusSeconds(SKEW_SECONDS).isBefore(Instant.parse(cond.getAttribute("NotOnOrAfter")))) throw bad("The assertion has expired");
        String sp = spEntityId(origin);
        boolean audience = false;
        NodeList auds = cond.getElementsByTagNameNS(A, "Audience");
        for (int i = 0; i < auds.getLength(); i++) if (sp.equals(auds.item(i).getTextContent().trim())) audience = true;
        if (!audience) throw bad("The assertion is meant for another application (audience)", "Set the identity provider's audience / entity id to " + sp);
        // subject: bearer confirmation for this address, in time, answering our request
        Element subject = child(as, A, "Subject");
        Element nameId = subject == null ? null : child(subject, A, "NameID");
        if (nameId == null || nameId.getTextContent().isBlank()) throw bad("The assertion names nobody");
        Element scd = null;
        for (Element sc : children(subject, A, "SubjectConfirmation"))
            if ("urn:oasis:names:tc:SAML:2.0:cm:bearer".equals(sc.getAttribute("Method"))) scd = child(sc, A, "SubjectConfirmationData");
        if (scd == null) throw bad("The assertion has no bearer confirmation");
        if (!acs.equals(scd.getAttribute("Recipient"))) throw bad("The assertion is for another address (recipient)");
        if (!scd.hasAttribute("NotOnOrAfter") || !now.minusSeconds(SKEW_SECONDS).isBefore(Instant.parse(scd.getAttribute("NotOnOrAfter")))) throw bad("The sign-in has expired");
        String inResponseTo = scd.hasAttribute("InResponseTo") ? scd.getAttribute("InResponseTo") : resp.getAttribute("InResponseTo");
        if (inResponseTo.isEmpty() || cr.cluster.take("saml", inResponseTo) == null)
            throw bad("This sign-in was not started here, or was used already", "Start the sign-in from the Command Center's sign-in page.");
        // once only
        String aid = as.getAttribute("ID");
        java.time.Duration keep = java.time.Duration.between(now, Instant.parse(scd.getAttribute("NotOnOrAfter")).plusSeconds(SKEW_SECONDS));
        if (!cr.cluster.put("saml-used", aid, "", keep.isNegative() ? java.time.Duration.ofMinutes(5) : keep.plusMinutes(5))) throw bad("This assertion was used already");

        Map<String, Object> claims = new LinkedHashMap<>();
        String sub = nameId.getTextContent().trim();
        claims.put("iss", issuer != null ? issuer.getTextContent().trim() : idp);
        claims.put("sub", sub);
        Map<String, String> attrs = attributes(as);
        String email = first(attrs, "email", "mail", "emailaddress", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress", "urn:oid:0.9.2342.19200300.100.1.3");
        if (email == null && nameId.getAttribute("Format").endsWith("emailAddress")) email = sub;
        if (email != null) claims.put("email", email);
        String name = first(attrs, "displayname", "name", "http://schemas.microsoft.com/identity/claims/displayname", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name");
        String given = first(attrs, "givenname", "firstname", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname");
        String family = first(attrs, "surname", "lastname", "sn", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname");
        if (name == null && given != null) name = family == null ? given : given + " " + family;
        if (name != null) claims.put("name", name);
        claims.put("preferred_username", sub);
        return claims;
    }

    /** A signature that covers exactly its parent element, made with the identity provider's certificate. */
    private boolean verify(Element sig, Element holder, X509Certificate cert) {
        try {
            DOMValidateContext ctx = new DOMValidateContext(cert.getPublicKey(), sig);
            ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature xs = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
            List<?> refs = xs.getSignedInfo().getReferences();
            if (refs.size() != 1) return false;
            Reference ref = (Reference) refs.get(0);
            if (!("#" + holder.getAttribute("ID")).equals(ref.getURI()) || holder.getAttribute("ID").isEmpty()) return false;
            for (Object t : ref.getTransforms()) {
                String alg = ((javax.xml.crypto.dsig.Transform) t).getAlgorithm();
                if (!alg.equals(javax.xml.crypto.dsig.Transform.ENVELOPED) && !alg.equals(CanonicalizationMethod.EXCLUSIVE) && !alg.equals(CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS)) return false;
            }
            return xs.validate(ctx);
        } catch (Exception e) { return false; }
    }

    private X509Certificate certificate() {
        String pem = s("saml_idp_certificate").replaceAll("-----(BEGIN|END) CERTIFICATE-----", "").replaceAll("\\s", "");
        try { return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(pem))); }
        catch (Exception e) { throw new OoaException("SSO.CONFIG", "The identity provider certificate in Settings cannot be read", "Paste the identity provider's signing certificate (PEM)."); }
    }

    static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) { throw bad("The SAML response is not valid XML"); }
    }

    /** Lets signature references find elements by their ID attribute. */
    private static void markIds(Element e) {
        if (e.hasAttribute("ID")) e.setIdAttribute("ID", true);
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) markIds(c);
    }

    private static Map<String, String> attributes(Element as) {
        Map<String, String> m = new HashMap<>();
        Element st = child(as, A, "AttributeStatement");
        if (st == null) return m;
        for (Element a : children(st, A, "Attribute")) {
            Element v = child(a, A, "AttributeValue");
            if (v != null) { m.put(a.getAttribute("Name").toLowerCase(Locale.ROOT), v.getTextContent().trim()); if (a.hasAttribute("FriendlyName")) m.put(a.getAttribute("FriendlyName").toLowerCase(Locale.ROOT), v.getTextContent().trim()); }
        }
        return m;
    }

    private static String first(Map<String, String> m, String... names) {
        for (String n : names) { String v = m.get(n.toLowerCase(Locale.ROOT)); if (v != null && !v.isBlank()) return v; }
        return null;
    }

    static Element child(Element e, String ns, String name) { List<Element> l = children(e, ns, name); return l.isEmpty() ? null : l.get(0); }

    static List<Element> children(Element e, String ns, String name) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && ns.equals(c.getNamespaceURI()) && name.equals(c.getLocalName())) out.add(c);
        return out;
    }

    private static String xml(String s) { return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static OoaException bad(String msg) { return bad(msg, null); }
    private static OoaException bad(String msg, String fix) { return new OoaException("SSO.INVALID", msg, fix == null ? "Try signing in again; if it keeps failing, an administrator checks the SAML settings." : fix); }
}
