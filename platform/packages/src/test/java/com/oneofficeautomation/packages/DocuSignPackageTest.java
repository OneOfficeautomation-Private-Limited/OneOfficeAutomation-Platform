package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** DocuSign toolkit against a local imitation of the eSignature REST API v2.1. */
class DocuSignPackageTest {
    static final String ACC = "1a2b3c4d-0000-1111-2222-333344445555";
    static final String ENV = "4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59";
    static final String TPL = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String A = "/restapi/v2.1/accounts/" + ACC;
    final DocuSignPackage pkg = new DocuSignPackage();

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    static List<Object> l(Object o) { return (List<Object>) o; }

    @Test void envelopeFromDocumentsAndFromTemplate() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST " + A + "/envelopes", c -> status(201, "{\"envelopeId\":\"" + ENV + "\",\"status\":\"" + (c.text().contains("\"created\"") ? "created" : "sent") + "\"}"));
            Path w = PackagesTest.tmp();
            Files.writeString(w.resolve("nda.pdf"), "%PDF-1.4 nda");
            ActionContext ctx = ctx(w);
            String base = f.url() + "/restapi";
            Map<String, Object> r = pkg.createEnvelope(ctx, base, ACC, List.of("nda.pdf"),
                    List.of(Map.of("name", "Ann Lee", "email", "ann@acme.com"), Map.of("Name", "Bob", "Email", "bob@acme.com", "routing_order", 2, "client_user_id", "emp-7")),
                    "Please sign the NDA", "Thanks", List.of(Map.of("name", "Legal", "email", "legal@acme.com")), "/sig/", true, TOKEN);
            assertEquals(ENV, r.get("envelope_id"));
            assertEquals("sent", r.get("status"));
            Call c = f.last("POST", A + "/envelopes");
            assertEquals("Bearer tok-123", c.auth());
            Map<String, Object> b = c.json();
            assertEquals("Please sign the NDA", b.get("emailSubject"));
            assertEquals("Thanks", b.get("emailBlurb"));
            assertEquals("sent", b.get("status"));
            Map<String, Object> doc = m(l(b.get("documents")).get(0));
            assertEquals("1", doc.get("documentId"));
            assertEquals("pdf", doc.get("fileExtension"));
            assertEquals("%PDF-1.4 nda", new String(Base64.getDecoder().decode((String) doc.get("documentBase64"))));
            List<Object> signers = l(m(b.get("recipients")).get("signers"));
            assertEquals("1", m(signers.get(0)).get("recipientId"));
            assertEquals("2", m(signers.get(1)).get("routingOrder"));
            assertEquals("emp-7", m(signers.get(1)).get("clientUserId"));
            assertEquals("/sig/", m(l(m(m(signers.get(0)).get("tabs")).get("signHereTabs")).get(0)).get("anchorString"));
            assertEquals("3", m(l(m(b.get("recipients")).get("carbonCopies")).get(0)).get("recipientId"), "copy recipients numbered after signers");
            fails("DOCUSIGN.NO_SIGNERS", () -> pkg.createEnvelope(ctx, base, ACC, List.of("nda.pdf"), List.of(), "s", null, null, null, true, TOKEN));
            fails("DOCUSIGN.BAD_RECIPIENT", () -> pkg.createEnvelope(ctx, base, ACC, List.of("nda.pdf"), List.of(Map.of("name", "x")), "s", null, null, null, true, TOKEN));
            fails("FILE.NOT_FOUND", () -> pkg.createEnvelope(ctx, base, ACC, List.of("nope.pdf"), List.of(Map.of("name", "x", "email", "y")), "s", null, null, null, true, TOKEN));

            Map<String, Object> t = pkg.fromTemplate(ctx, base, ACC, TPL, List.of(Map.of("role", "Client", "name", "Ann", "email", "ann@acme.com")),
                    Map.of("ContractNumber", "C-1042"), null, false, TOKEN);
            assertEquals("created", t.get("status"));
            Map<String, Object> tb = f.last("POST", A + "/envelopes").json();
            assertEquals(TPL, tb.get("templateId"));
            assertEquals("created", tb.get("status"));
            assertFalse(tb.containsKey("emailSubject"));
            Map<String, Object> role = m(l(tb.get("templateRoles")).get(0));
            assertEquals("Client", role.get("roleName"));
            assertEquals(Map.of("tabLabel", "ContractNumber", "value", "C-1042"), m(l(m(role.get("tabs")).get("textTabs")).get(0)));
            fails("DOCUSIGN.BAD_ID", () -> pkg.fromTemplate(ctx, base, ACC, "not-a-guid", List.of(Map.of("role", "C", "name", "A", "email", "a@b")), null, null, true, TOKEN));
        }
    }

    @Test void sendStatusListVoidAndResend() throws Exception {
        try (Fake f = new Fake()) {
            f.on("PUT " + A + "/envelopes/" + ENV, c -> ok("{\"envelopeId\":\"" + ENV + "\"}"))
             .on("GET " + A + "/envelopes/" + ENV, c -> ok("{\"envelopeId\":\"" + ENV + "\",\"status\":\"completed\",\"emailSubject\":\"NDA\",\"completedDateTime\":\"2026-10-19T10:00:00Z\"}"))
             .on("GET " + A + "/envelopes", c -> q(c).contains("start_position=0")
                    ? ok("{\"totalSetSize\":\"3\",\"envelopes\":[{\"envelopeId\":\"e1\",\"status\":\"sent\"},{\"envelopeId\":\"e2\",\"status\":\"sent\"}]}")
                    : ok("{\"totalSetSize\":\"3\",\"envelopes\":[{\"envelopeId\":\"e3\",\"status\":\"sent\"}]}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            String base = f.url() + "/restapi/";
            pkg.sendEnvelope(ctx, base, ACC, ENV, TOKEN);
            assertEquals(Map.of("status", "sent"), f.last("PUT", A + "/envelopes/" + ENV).json());
            Map<String, Object> s = pkg.status(ctx, base, ACC, ENV, TOKEN);
            assertEquals("completed", s.get("status"));
            assertEquals("2026-10-19T10:00:00Z", m(s.get("envelope")).get("completed"));

            Map<String, Object> list = pkg.listEnvelopes(ctx, base, ACC, LocalDateTime.of(2026, 10, 1, 0, 0), null, "sent, delivered", "NDA", 100, TOKEN);
            assertEquals(new BigDecimal(3), list.get("count"));
            String lq = q(f.last("GET", A + "/envelopes"));
            assertTrue(lq.startsWith("from_date=2026-10-01T00:00&status=sent,delivered&search_text=NDA&count="), lq);
            assertTrue(lq.endsWith("start_position=2"), lq);
            assertEquals("e3", rows(list.get("table")).get(2).get(0));

            pkg.voidEnvelope(ctx, base, ACC, ENV, "Wrong amount", TOKEN);
            assertEquals(Map.of("status", "voided", "voidedReason", "Wrong amount"), f.last("PUT", A + "/envelopes/" + ENV).json());
            fails("DOCUSIGN.NO_REASON", () -> pkg.voidEnvelope(ctx, base, ACC, ENV, " ", TOKEN));
            pkg.resend(ctx, base, ACC, ENV, TOKEN);
            assertEquals("resend_envelope=true", f.last("PUT", A + "/envelopes/" + ENV).query());
            fails("DOCUSIGN.BAD_ACCOUNT", () -> pkg.status(ctx, base, "", ENV, TOKEN));
        }
    }

    @Test void recipientsDocumentsAndDownloads() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + A + "/envelopes/" + ENV + "/recipients", c -> ok("{\"signers\":[{\"recipientId\":\"1\",\"name\":\"Ann\",\"email\":\"ann@acme.com\",\"routingOrder\":\"1\",\"status\":\"completed\",\"signedDateTime\":\"2026-10-19\"}],"
                    + "\"carbonCopies\":[{\"recipientId\":\"2\",\"name\":\"Legal\",\"email\":\"legal@acme.com\",\"routingOrder\":\"2\",\"status\":\"created\"}]}"))
             .on("PUT " + A + "/envelopes/" + ENV + "/recipients", c -> ok("{\"recipientUpdateResults\":[]}"))
             .on("GET " + A + "/envelopes/" + ENV + "/documents", c -> ok("{\"envelopeDocuments\":[{\"documentId\":\"1\",\"name\":\"nda.pdf\",\"type\":\"content\",\"pages\":[{},{}],\"order\":\"1\"},"
                    + "{\"documentId\":\"certificate\",\"name\":\"Summary\",\"type\":\"summary\"}]}"))
             .on("GET " + A + "/envelopes/" + ENV + "/documents/.*", c -> ok("%PDF " + c.path().substring(c.path().lastIndexOf('/') + 1)));
            Path w = PackagesTest.tmp();
            ActionContext ctx = ctx(w);
            String base = f.url() + "/restapi";
            Map<String, Object> r = pkg.listRecipients(ctx, base, ACC, ENV, TOKEN);
            assertEquals(new BigDecimal(2), r.get("count"));
            assertEquals(Arrays.asList("1", "signers", "Ann", "ann@acme.com", "1", "completed", null, "2026-10-19", null), rows(r.get("table")).get(0));
            assertEquals("carbonCopies", rows(r.get("table")).get(1).get(1));

            pkg.updateRecipient(ctx, base, ACC, ENV, "1", null, "ann.lee@acme.com", "signer", true, TOKEN);
            Call up = f.last("PUT", A + "/envelopes/" + ENV + "/recipients");
            assertEquals("resend_envelope=true", up.query());
            assertEquals(Map.of("signers", List.of(Map.of("recipientId", "1", "email", "ann.lee@acme.com"))), up.json());
            pkg.updateRecipient(ctx, base, ACC, ENV, "2", "Legal Team", null, "copy", false, TOKEN);
            assertTrue(f.last("PUT", A + "/envelopes/" + ENV + "/recipients").json().containsKey("carbonCopies"));
            fails("DOCUSIGN.NOTHING_TO_UPDATE", () -> pkg.updateRecipient(ctx, base, ACC, ENV, "1", null, "", "signer", true, TOKEN));

            Map<String, Object> d = pkg.listDocuments(ctx, base, ACC, ENV, TOKEN);
            assertEquals(Arrays.asList("1", "nda.pdf", "content", new BigDecimal(2), "1"), rows(d.get("table")).get(0));

            Map<String, Object> one = pkg.downloadDocument(ctx, base, ACC, ENV, "1", "signed/nda.pdf", TOKEN);
            assertEquals("%PDF 1", Files.readString(Path.of((String) one.get("path"))));
            Map<String, Object> all = pkg.downloadCombined(ctx, base, ACC, ENV, "signed/all.pdf", true, TOKEN);
            assertEquals("%PDF combined", Files.readString(Path.of((String) all.get("path"))));
            assertEquals("certificate=true", f.last("GET", A + "/envelopes/" + ENV + "/documents/combined").query());
            fails("DOCUSIGN.BAD_DOCUMENT", () -> pkg.downloadDocument(ctx, base, ACC, ENV, "../x", "x.pdf", TOKEN));
        }
    }

    @Test void templatesSigningUrlCustomFieldsAndFormData() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + A + "/templates", c -> ok("{\"totalSetSize\":\"1\",\"envelopeTemplates\":[{\"templateId\":\"" + TPL + "\",\"name\":\"NDA\",\"folderName\":\"Legal\"}]}"))
             .on("GET " + A + "/templates/" + TPL, c -> ok("{\"templateId\":\"" + TPL + "\",\"name\":\"NDA\",\"emailSubject\":\"Sign\",\"documents\":[{\"name\":\"nda.pdf\"}],"
                    + "\"recipients\":{\"signers\":[{\"roleName\":\"Client\"},{\"roleName\":\"Manager\"}],\"carbonCopies\":[{\"roleName\":\"Legal\"}]}}"))
             .on("POST " + A + "/envelopes/" + ENV + "/views/recipient", c -> status(201, "{\"url\":\"https://demo.docusign.net/Signing/abc\"}"))
             .on("GET " + A + "/envelopes/" + ENV + "/custom_fields", c -> ok("{\"textCustomFields\":[{\"fieldId\":\"11\",\"name\":\"OrderNumber\",\"value\":\"SO-1\"}],\"listCustomFields\":[{\"fieldId\":\"12\",\"name\":\"Region\",\"value\":\"EMEA\"}]}"))
             .on("PUT " + A + "/envelopes/" + ENV + "/custom_fields", c -> ok("{}"))
             .on("POST " + A + "/envelopes/" + ENV + "/custom_fields", c -> status(201, "{}"))
             .on("GET " + A + "/envelopes/" + ENV + "/form_data", c -> ok("{\"formData\":[{\"name\":\"Company\",\"value\":\"Acme\"},{\"name\":\"Title\",\"value\":\"CFO\"}]}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            String base = f.url() + "/restapi";
            Map<String, Object> lt = pkg.listTemplates(ctx, base, ACC, "NDA", 500, TOKEN);
            assertEquals(new BigDecimal(1), lt.get("count"));
            assertEquals("search_text=NDA&count=100&start_position=0", q(f.last("GET", A + "/templates")));
            Map<String, Object> gt = pkg.getTemplate(ctx, base, ACC, TPL, TOKEN);
            assertEquals(List.of("Client", "Manager", "Legal"), gt.get("roles"));
            assertEquals(List.of("nda.pdf"), m(gt.get("template")).get("documents"));

            assertEquals("https://demo.docusign.net/Signing/abc",
                    pkg.signingUrl(ctx, base, ACC, ENV, "Ann", "ann@acme.com", "emp-7", "https://portal.acme.com/done", "none", TOKEN).get("url"));
            assertEquals(Map.of("userName", "Ann", "email", "ann@acme.com", "clientUserId", "emp-7", "returnUrl", "https://portal.acme.com/done", "authenticationMethod", "none"),
                    f.last("POST", ".*/views/recipient").json());

            assertEquals(Map.of("OrderNumber", "SO-1", "Region", "EMEA"), pkg.getCustomFields(ctx, base, ACC, ENV, TOKEN).get("fields"));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("OrderNumber", "SO-2");
            fields.put("CostCenter", 4410);
            pkg.setCustomFields(ctx, base, ACC, ENV, fields, false, TOKEN);
            assertEquals(Map.of("textCustomFields", List.of(Map.of("fieldId", "11", "name", "OrderNumber", "value", "SO-2", "show", "false"))),
                    f.last("PUT", ".*/custom_fields").json(), "existing field updated by id");
            assertEquals(Map.of("textCustomFields", List.of(Map.of("name", "CostCenter", "value", "4410", "show", "false"))),
                    f.last("POST", ".*/custom_fields").json(), "new field added");
            fails("DOCUSIGN.NOTHING_TO_UPDATE", () -> pkg.setCustomFields(ctx, base, ACC, ENV, Map.of(), false, TOKEN));

            assertEquals(Map.of("Company", "Acme", "Title", "CFO"), pkg.formData(ctx, base, ACC, ENV, TOKEN).get("values"));
        }
    }

    @Test void errorsIncludingMissingEnvelopeReportedAs400() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + A + "/envelopes/" + ENV, c -> status(400, "{\"errorCode\":\"ENVELOPE_DOES_NOT_EXIST\",\"message\":\"The envelope specified either does not exist or you have no rights to it.\"}"))
             .on("GET " + A + "/envelopes/" + ENV.replace('4', '5'), c -> status(429, "{\"errorCode\":\"HOURLY_APIINVOCATION_LIMIT_EXCEEDED\",\"message\":\"The maximum number of hourly API invocations has been exceeded.\"}"))
             .on("GET " + A + "/envelopes/" + ENV.replace('4', '6'), c -> status(401, "{\"errorCode\":\"USER_AUTHENTICATION_FAILED\",\"message\":\"One or both of Username and Password are invalid.\"}"));
            String step = "[{\"id\":\"s\",\"action\":\"ooa.docusign/get_envelope_status\",\"args\":{\"base_url\":\"" + f.url() + "/restapi\",\"account_id\":\"" + ACC + "\",\"envelope_id\":\"ID\",\"access_token\":\"$tok\"}}]";
            FlowRun nf = flow(PackagesTest.tmp(), "", step.replace("ID", ENV));
            assertEquals("DOCUSIGN.NOT_FOUND", nf.result().error().get("code"));
            assertTrue(String.valueOf(nf.result().error().get("message")).contains("ENVELOPE_DOES_NOT_EXIST"));
            assertFalse(nf.events().contains("tok-123"));
            FlowRun rl = flow(PackagesTest.tmp(), "", step.replace("ID", ENV.replace('4', '5')));
            assertEquals("DOCUSIGN.RATE_LIMITED", rl.result().error().get("code"));
            assertEquals(true, rl.result().error().get("retryable"));
            assertEquals("DOCUSIGN.AUTH_FAILED", flow(PackagesTest.tmp(), "", step.replace("ID", ENV.replace('4', '6'))).result().error().get("code"));
        }
    }
}
