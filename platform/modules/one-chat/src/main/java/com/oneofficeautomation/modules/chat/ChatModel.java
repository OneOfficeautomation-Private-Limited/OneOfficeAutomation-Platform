package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

import com.oneofficeautomation.packages.AiBridge;
import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.SecureString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Model calls the Command Center makes itself (One Chat), through the same AI Gateway as agents. The person's roles
 * decide which One Model Connections may be used, as for their runs; the connection's masking, budget and call limits
 * apply; each call is kept in AI governance (model, tokens, masking, tools; a fingerprint of the prompt, never the
 * prompt). A connection's key is read from the vault for that call only.
 */
public final class ChatModel {
    private final ModuleContext ctx;
    private final Path dataDir;

    public ChatModel(ModuleContext ctx, Path dataDir) { this.ctx = ctx; this.dataDir = dataDir; }

    /**
     * One call with messages [{role, content}] and tools [{name, description, input_schema}] on the named connection
     * (blank: the first one the person may use). Returns {text, tool_calls: [{id, name, args}], input_tokens, output_tokens}.
     */
    Map<String, Object> call(User u, String connection, List<Map<String, Object>> messages, List<Map<String, Object>> tools, String purpose) {
        Map<String, Object> connections = ctx.modelConnectionsFor(ctx.rolesOf(u.username(), u.role()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) connections.get("connections");
        if (list.isEmpty()) throw new OoaException("AI.NO_CONNECTION", "No One Model Connection is available to you", "An administrator adds one in One Model Connections, or allows your role on one.");
        String name = connection == null || connection.isBlank() ? String.valueOf(connections.get("default")) : connection;
        Map<String, Object> chosen = list.stream().filter(c -> name.equals(c.get("name"))).findFirst()
                .orElseThrow(() -> new OoaException("AI.NO_CONNECTION", "The model connection " + name + " is not available to you", "Ask an administrator to allow your role on it."));
        // only this connection's key may be read, and only for this call
        Set<String> keyRefs = new HashSet<>();
        if (chosen.get("api_key") instanceof Map<?, ?> k && k.get("$credential") instanceof String ref) keyRefs.add(ref);
        CredentialVault vault = (ref, principal) -> {
            if (!keyRefs.contains(ref)) throw new OoaException("AI.KEY_REFUSED", "Only the model connection's own key can be read here");
            return new SecureString(ctx.systemCredential(ref), ref, false);
        };
        Path work;
        try { work = Files.createDirectories(dataDir.resolve("chat-ai")).resolve(id("call")); Files.createDirectories(work); }
        catch (IOException e) { throw new OoaException("AI.WORKDIR", "Cannot prepare the AI call: " + e.getMessage()); }
        Map<String, Object> services = new HashMap<>();
        services.put("model_connections", (Supplier<Object>) () -> Map.of("default", name, "connections", List.of(chosen)));
        services.put("vault", vault);
        ActionContext ctx = new ActionContext(null, "chat", null, work, new HashMap<>(), services, new AtomicBoolean(), List::of, null);
        try {
            return AiBridge.call(ctx, new AiBridge.Settings(name, null, null, null, null, 0.2, 2048, true, null, null, 90), messages, tools, purpose);
        } finally {
            recordGovernance(u, work.resolve("ai-governance.jsonl"));
            try (var files = Files.walk(work)) { files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (IOException ignored) { }
        }
    }

    private void recordGovernance(User u, Path log) {
        if (!Files.exists(log)) return;
        try {
            List<Object> calls = new ArrayList<>();
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) if (!line.isBlank()) calls.add(JsonLite.parse(line));
            Map<String, Object> run = new HashMap<>();
            run.put("id", null);
            run.put("bot_path", "One Chat");
            run.put("requested_by", u.username());
            ctx.recordAiCalls(run, calls);
        } catch (IOException | RuntimeException ignored) { /* governance logging never breaks the chat */ }
    }
}
