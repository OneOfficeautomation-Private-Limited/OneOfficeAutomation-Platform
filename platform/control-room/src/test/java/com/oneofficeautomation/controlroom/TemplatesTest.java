package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * K7: agent templates. A catalogue signed with the vendor key installs into a folder; one signed otherwise is refused.
 * In a SaaS region a tenant shares an agent, an operator approves it, and other tenants install it.
 */
class TemplatesTest {

    static String flow(String id) { return JsonLite.write(LicensingAndChecksTest.agent(id)); }

    @Test @SuppressWarnings("unchecked")
    void aSignedCatalogueInstallsIntoAFolder() throws Exception {
        Map<String, String> vendor = License.newKeyPair(), other = License.newKeyPair();
        List<Map<String, Object>> list = List.of(new LinkedHashMap<>(Map.of("id", "invoice-summary", "name", "Invoice summary", "category", "Finance",
            "description", "Totals invoices", "bundle", Templates.bundleOf(Map.of("Finance/Invoice summary.flow.json", flow("invoice_summary")), "test"))));
        String feed = Templates.makeFeed("Test", list, License.privateKey(vendor.get("private")));
        String forged = Templates.makeFeed("Test", list, License.privateKey(other.get("private")));
        assertEquals("TEMPLATES.BAD_SIGNATURE", assertThrows(OoaException.class, () -> Templates.readFeed(forged, License.publicKey(vendor.get("public")))).code());
        assertEquals("TEMPLATES.BAD_FEED", assertThrows(OoaException.class, () -> Templates.readFeed("{}", License.publicKey(vendor.get("public")))).code());

        Path data = Files.createTempDirectory("ooa-templates");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            Templates t = new Templates(cr, () -> License.publicKey(vendor.get("public")));
            assertEquals("TEMPLATES.BAD_SIGNATURE", assertThrows(OoaException.class, () -> t.upload(admin, forged, null)).code());
            assertEquals(Map.of("templates", 1), t.upload(admin, feed, null));
            Map<String, Object> l = t.list(admin);
            List<Map<String, Object>> shown = (List<Map<String, Object>>) l.get("templates");
            assertEquals("invoice-summary", shown.get(0).get("id"));
            assertEquals(List.of("Finance/Invoice summary.flow.json"), shown.get(0).get("agents"));
            assertFalse(String.valueOf(shown).contains("\"content\""), "the list carries no agent content");
            assertEquals(false, l.get("can_share"), "sharing is for SaaS regions");

            Map<String, Object> r = t.install(admin, "vendor", "invoice-summary", "Ready-made", null);
            assertEquals(true, r.get("ok"), String.valueOf(r));
            assertNotNull(db.one("SELECT path FROM bots WHERE path = 'Ready-made/Finance/Invoice summary.flow.json'"));
            assertEquals("skipped", ((Map<String, Object>) ((List<Object>) t.install(admin, "vendor", "invoice-summary", "Ready-made", null).get("results")).get(0)).get("status"),
                "installing again keeps what is there");
            assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> t.install(admin, "vendor", "nothing", null, null)).code());
            assertEquals("TEMPLATES.NO_REGION", assertThrows(OoaException.class, () -> t.share(admin, "Ready-made/Finance/Invoice summary.flow.json", "Mine", "", "Finance", null)).code());
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("template_catalog_url", "http://plain.example/templates.json"), null));
            cr.platform.updateSettings(admin, Map.of("template_catalog_url", "off"), null);
        } finally { db.close(); }
    }

    @Test @SuppressWarnings("unchecked")
    void aTenantSharesAnOperatorApprovesOthersInstall() throws Exception {
        Path data = Files.createTempDirectory("ooa-region-templates");
        Db base = Db.embedded(data);
        assumeFalse(base.sqlServer, "tenants need PostgreSQL or H2");
        try (Tenants region = new Tenants(base, data, Vault.ofKey(new byte[32], "test"), "in", "in.ooa.test", FirstPartyPackages::registry, null)) {
            Map<String, Object> ta = region.create("acme", "Acme Ltd", null, Map.of(), "op"), tg = region.create("globex", "Globex", null, Map.of(), "op");
            ControlRoom a = region.serve("acme").cr, g = region.serve("globex").cr;
            ControlRoom.User aa = TenantsTest.signIn(a, (String) ta.get("admin_password")), ga = TenantsTest.signIn(g, (String) tg.get("admin_password"));
            a.platform.updateSettings(aa, Map.of("template_catalog_url", "off"), null);
            g.platform.updateSettings(ga, Map.of("template_catalog_url", "off"), null);
            a.checkin(aa, "Fin/Pay.flow.json", LicensingAndChecksTest.agent("pay_template"), "v1", true, null);

            assertEquals(true, a.templates.list(aa).get("can_share"));
            Map<String, Object> shared = a.templates.share(aa, "Fin/Pay.flow.json", "Pay suppliers", "Pays approved invoices", "Finance", null);
            assertEquals("pending", shared.get("status"));
            assertTrue(((List<Object>) g.templates.list(ga).get("templates")).isEmpty(), "not shown before an operator approves it");

            List<Map<String, Object>> pending = region.templates("pending", false);
            assertEquals(1, pending.size());
            assertEquals("Acme Ltd", pending.get(0).get("publisher"));
            assertEquals(List.of("Fin/Pay.flow.json"), pending.get(0).get("agents"));
            region.reviewTemplate((String) shared.get("id"), true, "op");

            List<Map<String, Object>> seen = (List<Map<String, Object>>) g.templates.list(ga).get("templates");
            assertEquals("Pay suppliers", seen.get(0).get("name"));
            assertEquals("region", seen.get(0).get("source"));
            assertEquals(true, g.templates.install(ga, "region", (String) shared.get("id"), null, null).get("ok"));
            assertTrue(String.valueOf(g.db.one("SELECT content FROM bot_versions WHERE path = 'Templates/Fin/Pay.flow.json'").get("content")).contains("pay_template"));

            // a rejected template is no longer offered
            region.reviewTemplate((String) shared.get("id"), false, "op");
            assertTrue(((List<Object>) g.templates.list(ga).get("templates")).isEmpty());
            assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> region.reviewTemplate("tpl_nothing", true, "op")).code());
        } finally { base.close(); }
    }
}
