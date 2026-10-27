package com.oneofficeautomation.agent;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** K6: a runner checks the hash and the signature of a custom toolkit sent with the run before loading it. */
class CustomToolkitLoadTest {

    @Test
    void checksAndLoadsTheJarSentWithTheRun() throws Exception {
        Path dir = Files.createTempDirectory("ooa-tkr");
        Path src = dir.resolve("src/com/acme/tk");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Tk.java"), """
            package com.acme.tk;
            import com.oneofficeautomation.sdk.*;
            import java.util.Map;
            @OoaPackage(id = "com.acme.tk", name = "Acme", version = "1.2.0", description = "Test.")
            public final class Tk {
                @Action(id = "ping", name = "Ping", category = "Acme", idempotent = true, description = "Pong.")
                @Output(name = "text", type = OoaType.STRING, description = "pong")
                public Map<String, Object> ping(ActionContext ctx) { return Map.of("text", "pong"); }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", dir.resolve("classes").toString(), src.resolve("Tk.java").toString()));
        Path jar = dir.resolve("tk.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/acme/tk/Tk.class"));
            out.write(Files.readAllBytes(dir.resolve("classes/com/acme/tk/Tk.class")));
            out.closeEntry();
        }
        PackageSigning.PublisherKey key = PackageSigning.PublisherKey.generate("acme");
        ToolkitJar.sign(jar, "com.acme.tk.Tk", key, null);
        byte[] b = Files.readAllBytes(jar);
        Map<String, Object> t = new HashMap<>(Map.of("id", "com.acme.tk", "version", "1.2.0", "publisher", "acme", "public_key", ToolkitJar.encode(key.publicKey()),
            "sha256", ToolkitJar.sha256(b), "jar", Base64.getEncoder().encodeToString(b)));

        PackageRegistry reg = FirstPartyPackages.registry();
        assertNull(RunWorker.loadCustomToolkits(reg, dir.resolve("ws1"), List.of(t)));
        assertEquals(List.of("1.2.0"), reg.versions("com.acme.tk"));

        Map<String, Object> changed = new HashMap<>(t);
        changed.put("sha256", "0".repeat(64));
        assertTrue(RunWorker.loadCustomToolkits(FirstPartyPackages.registry(), dir.resolve("ws2"), List.of(changed)).contains("changed on the way"));

        Map<String, Object> otherKey = new HashMap<>(t);
        otherKey.put("public_key", ToolkitJar.encode(PackageSigning.PublisherKey.generate("acme").publicKey()));
        PackageRegistry reg3 = FirstPartyPackages.registry();
        assertNotNull(RunWorker.loadCustomToolkits(reg3, dir.resolve("ws3"), List.of(otherKey)));
        assertTrue(reg3.versions("com.acme.tk").isEmpty());
    }
}
