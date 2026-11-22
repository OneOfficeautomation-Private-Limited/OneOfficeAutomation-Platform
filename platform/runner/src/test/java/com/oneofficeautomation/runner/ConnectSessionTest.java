package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sessions for the SaaS toolkits (AA parity, 21 Nov 2026): a Connect step names a saved connection, and the toolkit's
 * later steps, loops and conditions that choose no connection of their own use it; a step's own connection or value wins.
 */
class ConnectSessionTest {
    @TempDir Path ws;

    @OoaPackage(id = "acme.saas", name = "SaaS", version = "1.0.0", description = "Test commands.", publisher = "acme")
    public static final class Saas {
        @Action(id = "connect", name = "Connect", description = "Uses a saved connection for later steps.")
        public Map<String, Object> connect(ActionContext ctx, @Input(name = "connection", type = OoaType.STRING, description = "Connection.") String connection) {
            ctx.sessions().put("saas", new ConnectionSession("acme.saas", connection));
            return Map.of();
        }

        @Action(id = "disconnect", name = "Disconnect", description = "Forgets it.")
        public Map<String, Object> disconnect(ActionContext ctx) { ctx.sessions().remove("saas"); return Map.of(); }

        @Action(id = "where", name = "Where", description = "Gives back its endpoint.")
        @Output(name = "text", type = OoaType.STRING, description = "Endpoint.")
        public Map<String, Object> where(ActionContext ctx,
                @Input(name = "endpoint", type = OoaType.STRING, required = false, defaultValue = "\"none\"", description = "Endpoint.") String endpoint) {
            return Map.of("text", endpoint);
        }

        @LoopIterator(id = "items", name = "Items", description = "One item: the endpoint.")
        public Iterable<Object> items(ActionContext ctx,
                @Input(name = "endpoint", type = OoaType.STRING, required = false, defaultValue = "\"none\"", description = "Endpoint.") String endpoint) {
            return List.of(endpoint);
        }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation"), acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Saas.class, PackageSigning.sign(Saas.class, acme));
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String vars, String steps) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\": \"TaskBot\", \"id\": \"t\", \"name\": \"t\", \"variables\": [" + vars + "], \"steps\": [" + steps + "]}");
    }

    @Test void laterStepsLoopsAndOwnValuesAfterConnect() throws Exception {
        Files.writeString(ws.resolve("connections.json"), "{\"prod\": {\"package\": \"acme.saas\", \"values\": {\"endpoint\": \"https://prod\"}},"
                + " \"test\": {\"package\": \"acme.saas\", \"values\": {\"endpoint\": \"https://test\"}}}");
        String out = "{\"name\": \"%s\", \"type\": \"String\", \"direction\": \"output\"}";
        Map<String, Object> f = flow(String.join(", ", out.formatted("before"), out.formatted("after"), out.formatted("own"), out.formatted("step"),
                        out.formatted("looped"), out.formatted("gone"), "{\"name\": \"x\", \"type\": \"String\"}"),
                "{\"id\": \"a\", \"action\": \"acme.saas/where\", \"out\": {\"text\": \"before\"}},"
                        + " {\"id\": \"c\", \"action\": \"acme.saas/connect\", \"args\": {\"connection\": \"prod\"}},"
                        + " {\"id\": \"b\", \"action\": \"acme.saas/where\", \"out\": {\"text\": \"after\"}},"
                        + " {\"id\": \"o\", \"action\": \"acme.saas/where\", \"args\": {\"endpoint\": \"https://mine\"}, \"out\": {\"text\": \"own\"}},"
                        + " {\"id\": \"s\", \"action\": \"acme.saas/where\", \"connection\": \"test\", \"out\": {\"text\": \"step\"}},"
                        + " {\"id\": \"l\", \"action\": \"core/loop\", \"args\": {\"iterator\": \"acme.saas/items\", \"as\": \"x\"},"
                        + "  \"children\": [{\"id\": \"v\", \"action\": \"core/set\", \"args\": {\"variable\": \"looped\", \"value\": \"$x\"}}]},"
                        + " {\"id\": \"d\", \"action\": \"acme.saas/disconnect\"},"
                        + " {\"id\": \"g\", \"action\": \"acme.saas/where\", \"out\": {\"text\": \"gone\"}}");
        RunResult r = Runner.builder(registry()).workDir(ws).build().run(f, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("none", r.outputs().get("before"));
        assertEquals("https://prod", r.outputs().get("after"), "a later step without a connection uses the Connect step's");
        assertEquals("https://mine", r.outputs().get("own"), "what the step sets itself wins");
        assertEquals("https://test", r.outputs().get("step"), "the step's own connection wins");
        assertEquals("https://prod", r.outputs().get("looped"), "the toolkit's loops use it too");
        assertEquals("none", r.outputs().get("gone"), "Disconnect forgets it");
    }
}
