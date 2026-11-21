package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared pieces of the AA-parity release (Studio defects, 21 Nov 2026): text fields take what is typed, files chosen
 * by name rules, Text inputs that may come from the vault, new-step defaults and tones in the manifest, a wait that
 * wraps any condition, the current loop item, and sessions lent to agents this one runs.
 */
class SharedFoundationsTest {
    @TempDir Path ws;

    @OoaPackage(id = "acme.kit", name = "Kit", version = "1.0.0", description = "Test commands.", publisher = "acme")
    public static final class Kit {
        @Action(id = "echo", name = "Echo", description = "Gives back its text.")
        @Output(name = "text", type = OoaType.STRING, description = "The text.")
        public Map<String, Object> echo(ActionContext ctx, @Input(name = "message", type = OoaType.STRING, description = "Text.") String message) {
            return Map.of("text", message);
        }

        @Action(id = "pick", name = "Pick", description = "Gives back the file it got and how many matched.")
        @Output(name = "file", type = OoaType.STRING, description = "The file.")
        @Output(name = "count", type = OoaType.NUMBER, description = "Matches.")
        public Map<String, Object> pick(ActionContext ctx, @Input(name = "source", type = OoaType.FILE, description = "File.") String source) {
            List<Path> all = ctx.selectedFiles("source");
            return Map.of("file", Path.of(source).getFileName().toString(), "count", all == null ? -1 : all.size());
        }

        @Action(id = "host", name = "Host", description = "Gives back a host that may come from the vault.")
        @Output(name = "text", type = OoaType.STRING, description = "The host.")
        public Map<String, Object> host(ActionContext ctx,
                @Input(name = "host", type = OoaType.STRING, acceptsCredential = true, description = "Host.") String host,
                @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", newStepDefault = "false", description = "Replace.") boolean overwrite,
                @Input(name = "keep", type = OoaType.BOOLEAN, required = false, defaultValue = "false", tone = "add", description = "Keep.") boolean keep) {
            return Map.of("text", host);
        }

        @Action(id = "item", name = "Item", description = "Gives back the current loop item.")
        @Output(name = "text", type = OoaType.STRING, description = "The item.")
        public Map<String, Object> item(ActionContext ctx) { return Map.of("text", String.valueOf(ctx.loopItem())); }

        @Action(id = "open", name = "Open", description = "Opens a session and shares it.")
        public Map<String, Object> open(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Name.") String name) {
            ctx.sessions().put(name, new StringBuilder("hello"));
            ctx.shareSession(name);
            return Map.of();
        }

        @Action(id = "read", name = "Read", description = "Reads a session.")
        @Output(name = "text", type = OoaType.STRING, description = "Its text.")
        public Map<String, Object> read(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Name.") String name) {
            return Map.of("text", ctx.session(name, StringBuilder.class).toString());
        }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation"), acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Kit.class, PackageSigning.sign(Kit.class, acme));
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String vars, String steps) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\": \"TaskBot\", \"id\": \"t\", \"name\": \"t\", \"variables\": [" + vars + "], \"steps\": [" + steps + "]}");
    }

    RunResult run(Map<String, Object> f) { return Runner.builder(registry()).workDir(ws).build().run(f, Map.of()); }

    @Test void aNumberTypedIntoATextFieldIsText() {
        Map<String, Object> f = flow("{\"name\": \"out\", \"type\": \"String\", \"direction\": \"output\"}",
                "{\"id\": \"s1\", \"action\": \"acme.kit/echo\", \"args\": {\"message\": 123}, \"out\": {\"text\": \"out\"}}");
        assertEquals(List.of(), new FlowValidator(registry(), FlowSchemaValidator.NONE).validate(f), "no 'expects String, got Number'");
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("123", r.outputs().get("out"));
    }

    @Test void nameRulesGiveTheFirstMatchAndEveryMatch() throws Exception {
        Path in = Files.createDirectories(ws.resolve("in"));
        for (String n : List.of("INV_000002.pdf", "INV_000001.pdf", "inv_x.txt", "notes.pdf")) Files.writeString(in.resolve(n), "x");
        String rules = "{\"$files\": {\"folder\": \"in\", \"rules\": [{\"what\": \"name\", \"op\": \"regex\", \"value\": \"^INV_\\\\d{6}\"}, {\"what\": \"type\", \"op\": \"in\", \"value\": \"pdf\"}], \"matchCase\": true}}";
        Map<String, Object> f = flow("{\"name\": \"file\", \"type\": \"String\", \"direction\": \"output\"}, {\"name\": \"count\", \"type\": \"Number\", \"direction\": \"output\"}",
                "{\"id\": \"s1\", \"action\": \"acme.kit/pick\", \"args\": {\"source\": " + rules + "}, \"out\": {\"file\": \"file\", \"count\": \"count\"}}");
        assertEquals(List.of(), new FlowValidator(registry(), FlowSchemaValidator.NONE).validate(f));
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("INV_000001.pdf", r.outputs().get("file"), "the first match by path");
        assertEquals(0, new java.math.BigDecimal(2).compareTo((java.math.BigDecimal) r.outputs().get("count")));
    }

    @Test void nameRulesThatMatchNothingSaySoInWords() throws Exception {
        Files.createDirectories(ws.resolve("empty"));
        RunResult r = run(flow("", "{\"id\": \"s1\", \"action\": \"acme.kit/pick\", \"args\": {\"source\": {\"$files\": {\"folder\": \"empty\", \"rules\": [{\"what\": \"name\", \"op\": \"starts\", \"value\": \"INV_\"}]}}}}"));
        assertFalse(r.ok());
        assertEquals("FILE.NO_MATCH", r.error().get("code"));
        assertTrue(String.valueOf(r.error().get("message")).contains("name starts with INV_"), String.valueOf(r.error()));
    }

    @Test void aBadRegularExpressionIsReportedBeforeAnythingRuns() throws Exception {
        Files.createDirectories(ws.resolve("in"));
        List<?> problems = new FlowValidator(registry(), FlowSchemaValidator.NONE).validate(flow("",
                "{\"id\": \"s1\", \"action\": \"acme.kit/pick\", \"args\": {\"source\": {\"$files\": {\"folder\": \"in\", \"rules\": [{\"what\": \"name\", \"op\": \"regex\", \"value\": \"(\"}]}}}}"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).toString().contains("not a valid regular expression"), problems.toString());
    }

    @Test void aTextInputMarkedSoTakesAVaultReferenceAndOthersStillRefuseIt() {
        String ref = "{\"$credential\": \"Servers/db/host\"}";
        assertEquals(List.of(), new FlowValidator(registry(), FlowSchemaValidator.NONE)
                .validate(flow("", "{\"id\": \"s1\", \"action\": \"acme.kit/host\", \"args\": {\"host\": " + ref + "}}")));
        assertFalse(new FlowValidator(registry(), FlowSchemaValidator.NONE)
                .validate(flow("", "{\"id\": \"s1\", \"action\": \"acme.kit/echo\", \"args\": {\"message\": " + ref + "}}")).isEmpty());
    }

    @Test void theManifestCarriesNewStepDefaultsTonesAndCredentialFields() {
        ActionSpec spec = registry().resolve("acme.kit", null).spec("action", "host");
        assertEquals(false, spec.input("overwrite").orElseThrow().schema().get("x-ooa-new-step-default"));
        assertEquals(true, spec.input("overwrite").orElseThrow().schema().get("default"), "saved steps keep the old default");
        assertEquals("add", spec.input("keep").orElseThrow().schema().get("x-ooa-tone"));
        assertEquals(true, spec.input("host").orElseThrow().schema().get("x-ooa-accepts-credential"));
    }

    @Test void keepCheckingWrapsANotAsWell() {
        Map<String, Object> f = flow("{\"name\": \"hit\", \"type\": \"Boolean\", \"direction\": \"output\", \"default\": false}",
                "{\"id\": \"s1\", \"action\": \"core/if\", \"args\": {\"condition\": {\"waitSeconds\": 2, \"not\": {\"op\": \"eq\", \"left\": \"a\", \"right\": \"b\"}}},"
                        + " \"children\": [{\"id\": \"s2\", \"action\": \"core/set\", \"args\": {\"variable\": \"hit\", \"value\": true}}]}");
        long t0 = System.nanoTime();
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(true, r.outputs().get("hit"));
        assertTrue(System.nanoTime() - t0 < 1_500_000_000L, "true at once, no waiting");
    }

    @Test void aStepInsideALoopSeesTheCurrentItem() {
        Map<String, Object> f = flow("{\"name\": \"last\", \"type\": \"String\", \"direction\": \"output\"}, {\"name\": \"x\", \"type\": \"String\"}",
                "{\"id\": \"l\", \"action\": \"core/loop\", \"args\": {\"iterator\": \"list\", \"over\": [\"a\", \"b\"], \"as\": \"x\"},"
                        + " \"children\": [{\"id\": \"s\", \"action\": \"acme.kit/item\", \"out\": {\"text\": \"last\"}}]}");
        RunResult r = run(f);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("b", r.outputs().get("last"));
    }

    @Test void aSharedSessionIsLentToTheChildWhichLeavesItOpen() {
        Map<String, Object> child = flow("{\"name\": \"t\", \"type\": \"String\", \"direction\": \"output\"}",
                "{\"id\": \"c\", \"action\": \"acme.kit/read\", \"args\": {\"name\": \"book\"}, \"out\": {\"text\": \"t\"}}");
        child.put("id", "child");
        AutomationRepository repo = (id, version) -> child;
        Map<String, Object> f = flow("{\"name\": \"got\", \"type\": \"String\", \"direction\": \"output\"}, {\"name\": \"after\", \"type\": \"String\", \"direction\": \"output\"}",
                "{\"id\": \"o\", \"action\": \"acme.kit/open\", \"args\": {\"name\": \"book\"}},"
                        + " {\"id\": \"r\", \"action\": \"core/run_bot\", \"args\": {\"automation\": \"child\", \"output_map\": {\"t\": \"got\"}}},"
                        + " {\"id\": \"a\", \"action\": \"acme.kit/read\", \"args\": {\"name\": \"book\"}, \"out\": {\"text\": \"after\"}}");
        RunResult r = Runner.builder(registry()).workDir(ws).service("repository", repo).build().run(f, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("hello", r.outputs().get("got"), "the child used the parent's shared session without same_session");
        assertEquals("hello", r.outputs().get("after"));
    }
}
