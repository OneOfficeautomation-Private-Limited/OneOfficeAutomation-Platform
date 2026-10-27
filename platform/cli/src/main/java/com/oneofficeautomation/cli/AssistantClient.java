package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * What the attended desktop app (R13, {@code ooa assistant}) asks the Command Center, as the person signed in with
 * their API key: who they are, the agents they may run, an agent's inputs, starting a run on their own default runner,
 * their open tasks and their finished runs. {@link Watch} turns successive answers into the notices the tray shows.
 */
final class AssistantClient {
    private final String base;
    private final String apiKey;
    private final HttpClient http;

    AssistantClient(String base, String apiKey, HttpClient http) {
        this.base = base.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() : http;
    }

    String base() { return base; }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/" + path)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + apiKey).header("X-OOA-Request", "1");
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JsonLite.write(body)));
        HttpResponse<String> r;
        try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new OoaException("ASSISTANT.UNREACHABLE", "Cannot reach the Command Center at " + base + ": " + e.getMessage(), "Check the address and the network.", true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("ASSISTANT.INTERRUPTED", "Interrupted"); }
        Object parsed = r.body() == null || r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        Map<String, Object> m = parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of("value", parsed);
        if (r.statusCode() >= 400) {
            Map<String, Object> e = m.get("error") instanceof Map<?, ?> em ? (Map<String, Object>) em : Map.of();
            throw new OoaException(String.valueOf(e.getOrDefault("code", "ASSISTANT.HTTP_" + r.statusCode())), String.valueOf(e.getOrDefault("message", "The Command Center answered " + r.statusCode())));
        }
        return m;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    Map<String, Object> me() { return call("GET", "auth/me", null); }

    String username() { Object u = me().get("username"); return u == null ? "" : String.valueOf(u); }

    /** Agents with a live production version that this person can see, by path. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> agents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) call("GET", "repo", null).getOrDefault("bots", List.of())) {
            Map<String, Object> b = (Map<String, Object>) o;
            if (b.get("production_version") != null && String.valueOf(b.get("path")).endsWith(".flow.json")) out.add(b);
        }
        out.sort(Comparator.comparing(b -> String.valueOf(b.get("path"))));
        return out;
    }

    /** The input variables of an agent's live version: name, type, description, required, default. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> inputs(String path) {
        Map<String, Object> bot = call("GET", "repo/bot?path=" + enc(path) + "&version=production", null);
        Map<String, Object> flow = bot.get("flow") instanceof Map<?, ?> f ? (Map<String, Object>) f : bot;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (List.of("input", "in_out").contains(String.valueOf(m.get("direction")))) out.add(m);
        }
        return out;
    }

    /** Starts the agent's live version as this person, attended, on their default runner; answers the run id. */
    String run(String path, Map<String, Object> inputs, String me) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bot_path", path);
        body.put("inputs", inputs == null ? Map.of() : inputs);
        body.put("run_as", me);
        body.put("mode", "attended");
        return String.valueOf(call("POST", "runs", body).get("run_id"));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> openTasks() {
        return (List<Map<String, Object>>) call("GET", "tasks?status=open", null).getOrDefault("tasks", List.of());
    }

    /** This person's latest runs (newest first). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> myRuns(String me) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) call("GET", "runs?limit=30&q=" + enc(me), null).getOrDefault("runs", List.of())) {
            Map<String, Object> r = (Map<String, Object>) o;
            if (me.equals(r.get("requested_by")) || me.equals(r.get("run_as"))) out.add(r);
        }
        return out;
    }

    /** A notice for the tray: a title, a line of text and the console page it opens. */
    record Notice(String title, String text, String page) {}

    /**
     * Remembers what was already seen, so each new task and each run that finished since the last look gives one
     * notice. The first look only remembers (no flood of old items at start).
     */
    static final class Watch {
        private final Set<String> tasks = new HashSet<>(), finished = new HashSet<>();
        private boolean primed;

        List<Notice> update(List<Map<String, Object>> openTasks, List<Map<String, Object>> runs) {
            List<Notice> out = new ArrayList<>();
            for (Map<String, Object> t : openTasks) {
                String id = String.valueOf(t.get("id"));
                if (tasks.add(id) && primed)
                    out.add(new Notice("New task", String.valueOf(t.getOrDefault("title", t.getOrDefault("name", "A task is waiting for you"))), "inbox"));
            }
            for (Map<String, Object> r : runs) {
                String status = String.valueOf(r.get("status"));
                if (!Set.of("success", "failed", "stopped").contains(status)) continue;
                String id = String.valueOf(r.get("id"));
                if (finished.add(id) && primed) {
                    String agent = String.valueOf(r.get("bot_path")).replaceAll("\\.flow\\.json$", "");
                    out.add(new Notice(status.equals("success") ? "Agent finished" : status.equals("failed") ? "Agent failed" : "Agent stopped", agent, "activity"));
                }
            }
            primed = true;
            return out;
        }
    }
}
