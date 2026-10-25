package com.oneofficeautomation.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SdkTest {

    @OoaPackage(id = "acme.demo", name = "Demo", version = "1.2.0", description = "Demo package.", publisher = "acme")
    public static final class Demo {
        @Action(id = "hello", name = "Hello", description = "Says hello.")
        @Output(name = "greeting", type = OoaType.STRING, description = "The greeting.")
        public Map<String, Object> hello(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Name.") String name) {
            return Map.of("greeting", "Hello " + name);
        }
    }

    @OoaPackage(id = "acme.bad", name = "Bad", version = "1.0.0", description = "Missing descriptions.")
    public static final class Undocumented {
        @Action(id = "x", name = "X", description = "")
        public Map<String, Object> x(ActionContext ctx) { return Map.of(); }
    }

    @Test void manifestShape() {
        Map<String, Object> m = PackageIntrospector.manifest(Demo.class);
        assertEquals("acme.demo", m.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) ((Map<String, Object>) ((List<?>) m.get("actions")).get(0)).get("inputs");
        assertEquals(List.of("name"), inputs.get("required"));
        assertEquals(false, inputs.get("additionalProperties"));
    }

    @OoaPackage(id = "acme.panel", name = "Panel", version = "1.0.0", description = "Panel layouts.")
    public static final class Laid {
        @Action(id = "open", name = "Open", description = "Opens.", panel = "path, [Has a password: password], header | session")
        public Map<String, Object> open(ActionContext ctx, @Input(name = "path", description = "Path.") String path,
                @Input(name = "session", description = "Session.", required = false) String session,
                @Input(name = "header", description = "Header.", required = false) Boolean header,
                @Input(name = "password", description = "Password.", required = false) Object password) { return Map.of(); }
    }

    @OoaPackage(id = "acme.panelbad", name = "Panel bad", version = "1.0.0", description = "A panel that misses an input.")
    public static final class LaidBad {
        @Action(id = "open", name = "Open", description = "Opens.", panel = "path")
        public Map<String, Object> open(ActionContext ctx, @Input(name = "path", description = "Path.") String path,
                @Input(name = "session", description = "Session.", required = false) String session) { return Map.of(); }
    }

    @Test void panelLayoutReachesTheManifest() {
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) PackageIntrospector.manifest(Laid.class).get("actions")).get(0);
        assertEquals(Map.of("main", List.of("path", Map.of("tick", "Has a password", "fields", List.of("password")), "header"),
                "more", List.of("session")), a.get("x-ooa-panel"));
        ActionSpec chosen = new ActionSpec("action", "x", "X", "X.", "General", List.of(
                new ActionSpec.InputSpec("name", OoaType.STRING, "Name.", "", false, null, "", List.of(), ""),
                new ActionSpec.InputSpec("index", OoaType.NUMBER, "Index.", "", false, null, "", List.of(), "")),
                List.of(), 60, false, List.of(), null, "(Sheet by: By name = name / index)");
        assertEquals(Map.of("main", List.of(Map.of("choice", "Sheet by", "options", List.of(
                Map.of("label", "By name", "fields", List.of("name")), Map.of("fields", List.of("index"))))), "more", List.of()),
                chosen.panelLayout());
        OoaException e = assertThrows(OoaException.class, () -> PackageIntrospector.inspect(LaidBad.class));
        assertTrue(e.getMessage().contains("does not place input 'session'"), e.getMessage());
    }

    @Test void undocumentedActionsAreRejected() {
        OoaException e = assertThrows(OoaException.class, () -> PackageIntrospector.inspect(Undocumented.class));
        assertTrue(e.getMessage().contains("no description"));
    }

    @Test void signatureVerification() {
        PackageSigning.PublisherKey acme = PackageSigning.PublisherKey.generate("acme");
        PackageSigning.PublisherKey impostor = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry reg = new PackageRegistry();
        reg.trust(acme);
        assertEquals("PKG.UNSIGNED", assertThrows(OoaException.class, () -> reg.install(Demo.class, null)).code());
        assertEquals("PKG.BAD_SIGNATURE", assertThrows(OoaException.class,
                () -> reg.install(Demo.class, PackageSigning.sign(Demo.class, impostor))).code());
        PackageSigning.Signature good = PackageSigning.sign(Demo.class, acme);
        PackageSigning.Signature tampered = new PackageSigning.Signature("acme", "0".repeat(64), good.signature());
        assertEquals("PKG.TAMPERED", assertThrows(OoaException.class, () -> reg.install(Demo.class, tampered)).code());
        assertEquals("1.2.0", reg.install(Demo.class, good).version());
    }

    @Test void publisherAllowList() {
        PackageSigning.PublisherKey acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry reg = new PackageRegistry(Set.of("oneofficeautomation"));
        reg.trust(acme);
        assertEquals("PKG.PUBLISHER_NOT_ALLOWED", assertThrows(OoaException.class,
                () -> reg.install(Demo.class, PackageSigning.sign(Demo.class, acme))).code());
    }

    @Test void semanticVersions() {
        assertTrue(PackageRegistry.satisfies("1.4.2", "^1.2.0"));
        assertFalse(PackageRegistry.satisfies("2.0.0", "^1.2.0"));
        assertTrue(PackageRegistry.satisfies("0.3.1", "^0.3.0"));
        assertFalse(PackageRegistry.satisfies("0.4.0", "^0.3.0"));
        assertTrue(PackageRegistry.satisfies("1.2.9", "~1.2.0"));
        assertFalse(PackageRegistry.satisfies("1.3.0", "~1.2.0"));
        assertTrue(PackageRegistry.satisfies("3.0.0", ">=2.1.0"));
    }

    @Test void secureStringNeverPrints() {
        SecureString s = new SecureString("hunter2", "l/c/a");
        assertEquals("****", s.toString());
        assertEquals("\"****\"", JsonLite.write(s));
    }

    @Test void canonicalJsonIsStable() {
        assertEquals("{\"a\":1,\"b\":[true,null,\"x\"]}", JsonLite.canonical(JsonLite.parse("{\"b\":[true,null,\"x\"],\"a\":1.0}")));
    }
}
