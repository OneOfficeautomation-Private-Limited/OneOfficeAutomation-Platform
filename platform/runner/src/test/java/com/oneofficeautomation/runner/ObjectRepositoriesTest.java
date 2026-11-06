package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G42: a step's object can be an element of a shared object repository, read from its file just before the step runs. */
class ObjectRepositoriesTest {
    @TempDir Path ws;

    @OoaPackage(id = "acme.screen", name = "Screen", version = "1.0.0", description = "Echoes the object a step gets.", publisher = "acme")
    public static final class Screen {
        @Action(id = "use", name = "Use object", description = "Returns the object it was given.")
        @Output(name = "seen", type = OoaType.DICTIONARY, description = "The object.")
        public Map<String, Object> use(ActionContext ctx, @Input(name = "object", type = OoaType.DICTIONARY, description = "Captured object.") Map<String, Object> object) {
            return Map.of("seen", object);
        }
        @Action(id = "keep", name = "Keep", description = "Returns a dictionary as it is.")
        @Output(name = "value", type = OoaType.DICTIONARY, description = "The value.")
        public Map<String, Object> keep(ActionContext ctx, @Input(name = "value", type = OoaType.DICTIONARY, description = "A value.") Map<String, Object> value) {
            return Map.of("value", value);
        }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation"), acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Screen.class, PackageSigning.sign(Screen.class, acme));
        return r;
    }

    static final String REPO = """
            {"kind": "ObjectRepository", "name": "SAP", "applications": [{"name": "SAP GUI", "screens": [{"name": "Login", "elements": [
              {"name": "User name", "description": "The user field", "object": {"technology": "desktop", "properties": [{"name": "name", "value": "User", "match": "equals", "use": true}], "screenshot": "iVBOR"}}]}]}]}""";

    @SuppressWarnings("unchecked")
    Map<String, Object> flow(String args) {
        return (Map<String, Object>) JsonLite.parse("""
                {"kind": "TaskBot", "id": "t", "name": "t", "variables": [{"name": "seen", "type": "Dictionary", "direction": "output"}, {"name": "value", "type": "Dictionary", "direction": "output"}],
                 "steps": [{"id": "s1", "action": "acme.screen/use", "args": %s, "out": {"seen": "seen"}}]}""".formatted(args));
    }

    @SuppressWarnings("unchecked")
    @Test void aReferenceIsReplacedByTheElementsObjectAndAChangeIsPickedUp() throws Exception {
        Files.createDirectories(ws.resolve("Shared"));
        Files.writeString(ws.resolve("Shared/SAP.objects.json"), REPO);
        Runner runner = Runner.builder(registry()).workDir(ws).build();
        RunResult r = runner.run(flow("{\"object\": {\"repository\": \"Shared/SAP.objects.json\", \"element\": \"SAP GUI/Login/User name\"}}"), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        Map<String, Object> seen = (Map<String, Object>) r.outputs().get("seen");
        assertEquals("desktop", seen.get("technology"));
        assertFalse(seen.containsKey("screenshot"), "pictures stay in the repository");
        // the repository changes: the next run uses the new object
        Files.writeString(ws.resolve("Shared/SAP.objects.json"), REPO.replace("\"User\"", "\"Benutzer\""));
        Files.setLastModifiedTime(ws.resolve("Shared/SAP.objects.json"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        RunResult again = runner.run(flow("{\"object\": {\"repository\": \"Shared/SAP.objects.json\", \"element\": \"SAP GUI/Login/User name\"}}"), Map.of());
        assertTrue(JsonLite.write(again.outputs().get("seen")).contains("Benutzer"));
    }

    @Test void missingFilesAndElementsAndPathsOutsideTheWorkspaceFail() throws Exception {
        Files.writeString(ws.resolve("SAP.objects.json"), REPO);
        Runner runner = Runner.builder(registry()).workDir(ws).build();
        assertEquals("OBJECT.NOT_IN_REPOSITORY", runner.run(flow("{\"object\": {\"repository\": \"SAP.objects.json\", \"element\": \"SAP GUI/Login/Password\"}}"), Map.of()).error().get("code"));
        assertEquals("OBJECT.REPOSITORY_NOT_FOUND", runner.run(flow("{\"object\": {\"repository\": \"Other.objects.json\", \"element\": \"a/b/c\"}}"), Map.of()).error().get("code"));
        assertEquals("OBJECT.BAD_REPOSITORY", runner.run(flow("{\"object\": {\"repository\": \"../x.objects.json\", \"element\": \"a/b/c\"}}"), Map.of()).error().get("code"));
        assertEquals("OBJECT.BAD_REPOSITORY", runner.run(flow("{\"object\": {\"repository\": \"notes.json\", \"element\": \"a/b/c\"}}"), Map.of()).error().get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test void onlyObjectInputsAreLookedUpAndReferencesAreListed() {
        Map<String, Object> f = (Map<String, Object>) JsonLite.parse("""
                {"kind": "TaskBot", "id": "t", "name": "t", "variables": [{"name": "value", "type": "Dictionary", "direction": "output"}],
                 "steps": [{"id": "k", "action": "acme.screen/keep", "args": {"value": {"repository": "SAP.objects.json", "element": "a/b/c"}}, "out": {"value": "value"}}],
                 "subflows": [{"name": "Sub", "steps": [{"id": "u", "action": "acme.screen/use", "args": {"object": {"repository": "B.objects.json", "element": "x/y/z"}}}]}]}""");
        RunResult r = Runner.builder(registry()).workDir(ws).build().run(f, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(Map.of("repository", "SAP.objects.json", "element", "a/b/c"), r.outputs().get("value"), "a dictionary that only looks like a reference is left alone");
        assertEquals(List.of("SAP.objects.json", "B.objects.json"), ObjectRepositories.referenced(f));
        assertEquals(List.of(Map.of("step", "k", "repository", "SAP.objects.json", "element", "a/b/c"), Map.of("step", "u", "repository", "B.objects.json", "element", "x/y/z")),
                ObjectRepositories.references(f));
        assertEquals(List.of("SAP GUI/Login/User name"), ObjectRepositories.elements((Map<String, Object>) JsonLite.parse(REPO)));
    }
}
