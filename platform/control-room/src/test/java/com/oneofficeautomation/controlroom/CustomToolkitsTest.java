package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** K6: signed custom toolkit jars, trusted publishers, versions side by side, and the jar handed to runs. */
class CustomToolkitsTest {

    /** Compiles a one-action toolkit at the given version into a jar (outside the test class path, as a customer's would be). */
    static Path buildToolkit(Path dir, String version, String reply) throws IOException {
        Path src = dir.resolve("src-" + version + reply.hashCode()).resolve("com/acme/billing");
        Files.createDirectories(src);
        Files.writeString(src.resolve("BillingToolkit.java"), """
            package com.acme.billing;
            import com.oneofficeautomation.sdk.*;
            import java.util.Map;
            @OoaPackage(id = "com.acme.billing", name = "Acme billing", version = "%s", description = "Test toolkit.")
            public final class BillingToolkit {
                @Action(id = "greet", name = "Greet", category = "Billing", idempotent = true, description = "Says hello.")
                @Output(name = "text", type = OoaType.STRING, description = "The greeting.")
                public Map<String, Object> greet(ActionContext ctx) { return Map.of("text", "%s"); }
            }
            """.formatted(version, reply));
        Path classes = src.getParent().getParent().getParent().resolve("classes");
        Files.createDirectories(classes);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", classes.toString(),
            src.resolve("BillingToolkit.java").toString());
        assertEquals(0, rc);
        Path jar = dir.resolve("billing-" + version + "-" + Math.abs(reply.hashCode()) + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/acme/billing/BillingToolkit.class"));
            out.write(Files.readAllBytes(classes.resolve("com/acme/billing/BillingToolkit.class")));
            out.closeEntry();
        }
        return jar;
    }

    @Test @SuppressWarnings("unchecked")
    void signedToolkitsUploadSideBySideAndGoWithRuns() throws Exception {
        Path data = Files.createTempDirectory("ooa-tk");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            Path keys = data.resolve("keys");
            PackageSigning.PublisherKey acme = ToolkitJar.keygen("acme", keys);
            Path v1 = buildToolkit(data, "1.0.0", "hello from 1"), v2 = buildToolkit(data, "2.0.0", "hello from 2");
            ToolkitJar.sign(v1, "com.acme.billing.BillingToolkit", ToolkitJar.readKey(keys.resolve("acme.key")), null);
            ToolkitJar.sign(v2, "com.acme.billing.BillingToolkit", acme, null);

            // unsigned and untrusted jars are refused
            Path unsigned = buildToolkit(data, "3.0.0", "x");
            assertEquals("PKG.UNSIGNED", assertThrows(OoaException.class, () -> cr.customToolkits.upload(admin, Files.readAllBytes(unsigned), null)).code());
            assertEquals("PKG.UNTRUSTED", assertThrows(OoaException.class, () -> cr.customToolkits.upload(admin, Files.readAllBytes(v1), null)).code());
            cr.customToolkits.addPublisher(admin, "acme", Files.readString(keys.resolve("acme.pub")), null);

            cr.customToolkits.upload(admin, Files.readAllBytes(v1), null);
            Map<String, Object> up2 = cr.customToolkits.upload(admin, Files.readAllBytes(v2), null);
            assertEquals(List.of("1.0.0", "2.0.0"), up2.get("versions"));
            assertEquals(Boolean.TRUE, cr.customToolkits.upload(admin, Files.readAllBytes(v1), null).get("unchanged"));

            // the same version with other code is refused, so agents keep what they were tested with
            Path v1b = buildToolkit(data, "1.0.0", "changed");
            ToolkitJar.sign(v1b, "com.acme.billing.BillingToolkit", acme, null);
            assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.customToolkits.upload(admin, Files.readAllBytes(v1b), null)).code());
            // signed with another key under the same publisher name: the signature does not check out
            Path forged = buildToolkit(data, "4.0.0", "forged");
            ToolkitJar.sign(forged, "com.acme.billing.BillingToolkit", PackageSigning.PublisherKey.generate("acme"), null);
            assertEquals("PKG.BAD_SIGNATURE", assertThrows(OoaException.class, () -> cr.customToolkits.upload(admin, Files.readAllBytes(forged), null)).code());
            assertEquals(2, cr.customToolkits.list(admin).size());

            // an agent pinned to ^1.0.0 checks in, and its runs get the 1.0.0 jar
            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"bill\",\"name\":\"Bill\",\"description\":\"t\","
                + "\"requires\":{\"packages\":{\"com.acme.billing\":\"^1.0.0\"}},\"variables\":[{\"name\":\"msg\",\"type\":\"String\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"s1\",\"action\":\"com.acme.billing/greet\",\"out\":{\"text\":\"msg\"}}]}");
            cr.checkin(admin, "Finance/Bill.flow.json", flow, "v1", true, null);
            List<Map<String, Object>> job = cr.customToolkits.forJob(flow, Map.of());
            assertEquals(1, job.size());
            assertEquals("1.0.0", job.get(0).get("version"));
            Map<String, Object> toolkitRow = cr.platform.toolkits(admin).stream().filter(t -> "com.acme.billing".equals(t.get("id"))).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, toolkitRow.get("custom"));

            // a runner: checks the jar it was sent and runs the agent with that version
            PackageRegistry runnerReg = FirstPartyPackages.registry();
            byte[] jar = Base64.getDecoder().decode((String) job.get(0).get("jar"));
            assertEquals(job.get(0).get("sha256"), ToolkitJar.sha256(jar));
            Path f = data.resolve("runner.jar");
            Files.write(f, jar);
            ToolkitJar.Loaded l = ToolkitJar.load(f, getClass().getClassLoader());
            runnerReg.trust(new PackageSigning.PublisherKey("acme", null, ToolkitJar.publicKey((String) job.get(0).get("public_key"))));
            runnerReg.install(l.cls(), l.signature());
            RunResult r = Runner.builder(runnerReg).workDir(data).build().run(flow, Map.of(), new Runner.RunOptions());
            assertEquals("success", r.status(), String.valueOf(r.error()));
            assertEquals("hello from 1", r.outputs().get("msg"));

            // the only version agents use cannot be deleted; a spare one can, and the toolkits load again on restart
            String v2id = (String) up2.get("id");
            cr.customToolkits.delete(admin, v2id, null);
            String v1id = (String) cr.customToolkits.list(admin).get(0).get("id");
            assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.customToolkits.delete(admin, v1id, null)).code());
            assertThrows(OoaException.class, () -> cr.customToolkits.removePublisher(admin, "acme", null));
            PackageRegistry fresh = FirstPartyPackages.registry();
            new ControlRoom(db, data, fresh, null);
            assertEquals(List.of("1.0.0"), fresh.versions("com.acme.billing"));
        } finally { db.close(); }
    }
}
