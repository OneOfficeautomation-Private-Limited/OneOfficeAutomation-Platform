package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Apache Kafka through a Confluent-compatible REST Proxy: v3 for topics and producing, v2 consumer instances for reading.
 * No Kafka client library is needed. Sign-in with an API key and secret, or a bearer token.
 */
@OoaPackage(id = "ooa.kafka", name = "Kafka", version = "1.0.0",
        description = "Send messages to Kafka topics, read messages, and create or inspect topics through a Kafka REST Proxy.")
public final class KafkaPackage {
    static final String P = "KAFKA";
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9._-]{1,249}$");
    private static final Pattern GROUP = Pattern.compile("^[A-Za-z0-9._-]{1,200}$");
    private static final String V2 = "application/vnd.kafka.v2+json";
    private static final String D_URL = "Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082.";
    private static final String D_CLUSTER = "Kafka cluster id. Leave empty to use the only (first) cluster the proxy serves.";
    private static final String D_KEY = "API key or user name, used with api_secret.";
    private static final String D_SECRET = "API secret or password from the vault.";
    private static final String D_BEARER = "Bearer token from the vault, used instead of an API key and secret.";

    private static String root(String base) { return base.replaceAll("/+$", ""); }

    private static HttpJson.Req req(String method, String url, String apiKey, Object secret, Object bearer) {
        HttpJson.Req r = HttpJson.req(P, method, url);
        if (bearer instanceof SecureString) return r.bearer(bearer);
        if (secret instanceof SecureString) return r.basic(apiKey == null ? "" : apiKey, secret);
        throw new ActionException(P + ".NO_TOKEN", "Sign-in details are required", "Map api_key and api_secret, or access_token, to vault values.");
    }

    private static String topic(String t) {
        if (t == null || !TOPIC.matcher(t.trim()).matches()) throw new ActionException(P + ".BAD_TOPIC", "Invalid topic name '" + t + "'", "Topic names use letters, digits, dot, underscore and dash only.");
        return t.trim();
    }

    private static String cluster(ActionContext ctx, String base, String clusterId, String apiKey, Object secret, Object bearer) {
        if (clusterId != null && !clusterId.isBlank()) return HttpJson.enc(clusterId.trim());
        List<?> data = (List<?>) req("GET", root(base) + "/v3/clusters", apiKey, secret, bearer).send(ctx).map().getOrDefault("data", List.of());
        if (data.isEmpty()) throw new ActionException(P + ".NO_CLUSTER", "The REST Proxy reports no Kafka cluster", "Check the address, or give cluster_id.");
        return HttpJson.enc(Values.text(((Map<?, ?>) data.get(0)).get("cluster_id")));
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    @Action(id = "produce_message", name = "Send message", category = "Messages", capabilities = "network",
            description = "Write one message to a topic, with an optional key and headers.")
    @Output(name = "partition", type = OoaType.NUMBER, description = "Partition the message was written to.")
    @Output(name = "offset", type = OoaType.NUMBER, description = "Position of the message in that partition.")
    @Output(name = "timestamp", type = OoaType.STRING, description = "Time Kafka recorded for the message.")
    public Map<String, Object> produce(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "topic", type = OoaType.STRING, example = "orders", description = "Topic to write to.") String t,
            @Input(name = "value", type = OoaType.ANY, description = "Message content: text, or a dictionary or list sent as JSON.") Object value,
            @Input(name = "key", type = OoaType.STRING, required = false, description = "Message key. Messages with the same key always go to the same partition, so they stay in order.") String key,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Extra message headers, e.g. {\"source\": \"oneofficeautomation\"}.") Map<String, Object> headers,
            @Input(name = "value_format", type = OoaType.STRING, required = false, defaultValue = "\"json\"", enumValues = {"json", "text"}, description = "Send the value as JSON or as plain text.") String format,
            @Input(name = "partition", type = OoaType.NUMBER, required = false, description = "Write to this partition. Leave empty to let Kafka choose.") Integer partition,
            @Input(name = "cluster_id", type = OoaType.STRING, required = false, description = D_CLUSTER) String clusterId,
            @Input(name = "api_key", type = OoaType.STRING, required = false, description = D_KEY) String apiKey,
            @Input(name = "api_secret", type = OoaType.CREDENTIAL, required = false, description = D_SECRET) Object secret,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_BEARER) Object bearer) {
        if (value instanceof SecureString) throw Values.secureLeak("a Kafka message");
        Map<String, Object> body = new LinkedHashMap<>();
        if (partition != null) body.put("partition_id", partition);
        if (headers != null && !headers.isEmpty()) {
            List<Map<String, Object>> hs = new ArrayList<>();
            headers.forEach((k, v) -> hs.add(Map.of("name", k, "value", b64(Values.text(v)))));
            body.put("headers", hs);
        }
        if (key != null && !key.isEmpty()) body.put("key", Map.of("type", "STRING", "data", key));
        Map<String, Object> val = new LinkedHashMap<>();
        if ("text".equals(format)) { val.put("type", "STRING"); val.put("data", Values.text(value)); }
        else { val.put("type", "JSON"); val.put("data", value); }
        body.put("value", val);
        String url = root(base) + "/v3/clusters/" + cluster(ctx, base, clusterId, apiKey, secret, bearer) + "/topics/" + topic(t) + "/records";
        Map<String, Object> r = req("POST", url, apiKey, secret, bearer).json(body).send(ctx).map();
        if (r.get("error_code") instanceof BigDecimal code && code.intValue() != 200)
            throw new ActionException(P + ".PRODUCE_FAILED", "Kafka did not accept the message: " + r.get("message"), "Check the topic exists and the account may write to it.",
                    code.intValue() == 429 || code.intValue() >= 500, Map.of("error_code", code));
        return Out.of("partition", r.get("partition_id"), "offset", r.get("offset"), "timestamp", r.get("timestamp") == null ? null : Values.text(r.get("timestamp")));
    }

    @Action(id = "list_topics", name = "List topics", category = "Topics", idempotent = true, capabilities = "network", description = "Return the topics in the cluster.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: topic, partitions, replication_factor, internal.")
    @Output(name = "names", type = OoaType.LIST, description = "Topic names only.")
    public Map<String, Object> listTopics(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "include_internal", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also list Kafka's own internal topics, whose names usually start with an underscore.") boolean internal,
            @Input(name = "cluster_id", type = OoaType.STRING, required = false, description = D_CLUSTER) String clusterId,
            @Input(name = "api_key", type = OoaType.STRING, required = false, description = D_KEY) String apiKey,
            @Input(name = "api_secret", type = OoaType.CREDENTIAL, required = false, description = D_SECRET) Object secret,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_BEARER) Object bearer) {
        List<Object> rows = new ArrayList<>();
        List<Object> names = new ArrayList<>();
        String url = root(base) + "/v3/clusters/" + cluster(ctx, base, clusterId, apiKey, secret, bearer) + "/topics";
        int pages = 0;
        while (url != null && pages++ < 1000) {
            Map<String, Object> r = req("GET", url, apiKey, secret, bearer).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("data", List.of())) {
                Map<?, ?> t = (Map<?, ?>) o;
                boolean isInternal = Boolean.TRUE.equals(t.get("is_internal"));
                if (isInternal && !internal) continue;
                rows.add(new ArrayList<>(Arrays.asList(t.get("topic_name"), t.get("partitions_count"), t.get("replication_factor"), isInternal)));
                names.add(t.get("topic_name"));
            }
            Object next = r.get("metadata") instanceof Map<?, ?> m ? m.get("next") : null;
            url = next != null && Values.text(next).startsWith(root(base) + "/") ? Values.text(next) : null;
        }
        return Out.of("table", Values.table(List.of("topic", "partitions", "replication_factor", "internal"), rows), "names", names);
    }

    @Action(id = "create_topic", name = "Create topic", category = "Topics", capabilities = "network",
            description = "Create a topic with a number of partitions and optional settings such as how long messages are kept.")
    @Output(name = "created", type = OoaType.BOOLEAN, description = "True if the topic was created, false if it was already there.")
    public Map<String, Object> createTopic(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "topic", type = OoaType.STRING, description = "Name of the new topic.") String t,
            @Input(name = "partitions", type = OoaType.NUMBER, required = false, defaultValue = "6", description = "Number of partitions; more partitions let more readers work in parallel.") int partitions,
            @Input(name = "replication_factor", type = OoaType.NUMBER, required = false, description = "Copies of each message kept on different servers. Leave empty for the cluster default.") Integer replicas,
            @Input(name = "settings", type = OoaType.DICTIONARY, required = false, description = "Topic settings, e.g. {\"retention.ms\": \"604800000\", \"cleanup.policy\": \"delete\"}.") Map<String, Object> settings,
            @Input(name = "fail_if_exists", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop with an error if the topic is already there, instead of carrying on.") boolean failIfExists,
            @Input(name = "cluster_id", type = OoaType.STRING, required = false, description = D_CLUSTER) String clusterId,
            @Input(name = "api_key", type = OoaType.STRING, required = false, description = D_KEY) String apiKey,
            @Input(name = "api_secret", type = OoaType.CREDENTIAL, required = false, description = D_SECRET) Object secret,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_BEARER) Object bearer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic_name", topic(t));
        body.put("partitions_count", Math.max(1, partitions));
        if (replicas != null && replicas > 0) body.put("replication_factor", replicas);
        if (settings != null && !settings.isEmpty()) {
            List<Map<String, Object>> cfg = new ArrayList<>();
            settings.forEach((k, v) -> cfg.add(Map.of("name", k, "value", Values.text(v))));
            body.put("configs", cfg);
        }
        try {
            req("POST", root(base) + "/v3/clusters/" + cluster(ctx, base, clusterId, apiKey, secret, bearer) + "/topics", apiKey, secret, bearer).json(body).send(ctx);
            return Out.of("created", true);
        } catch (ActionException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("already exists")) throw e;
            if (failIfExists) throw new ActionException(P + ".TOPIC_EXISTS", "Topic '" + t + "' already exists", "Use another name, or turn off fail_if_exists.");
            return Out.of("created", false);
        }
    }

    @Action(id = "get_topic", name = "Get topic info", category = "Topics", idempotent = true, capabilities = "network",
            description = "Read a topic's partition count, replication and settings.")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "topic, partitions, replication_factor, internal.")
    @Output(name = "settings", type = OoaType.DICTIONARY, description = "Topic settings, e.g. retention.ms and cleanup.policy.")
    public Map<String, Object> getTopic(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "topic", type = OoaType.STRING, description = "Topic to look up.") String t,
            @Input(name = "cluster_id", type = OoaType.STRING, required = false, description = D_CLUSTER) String clusterId,
            @Input(name = "api_key", type = OoaType.STRING, required = false, description = D_KEY) String apiKey,
            @Input(name = "api_secret", type = OoaType.CREDENTIAL, required = false, description = D_SECRET) Object secret,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_BEARER) Object bearer) {
        String url = root(base) + "/v3/clusters/" + cluster(ctx, base, clusterId, apiKey, secret, bearer) + "/topics/" + topic(t);
        Map<String, Object> r = req("GET", url, apiKey, secret, bearer).send(ctx).map();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("topic", r.get("topic_name"));
        info.put("partitions", r.get("partitions_count"));
        info.put("replication_factor", r.get("replication_factor"));
        info.put("internal", Boolean.TRUE.equals(r.get("is_internal")));
        Map<String, Object> cfg = new LinkedHashMap<>();
        for (Object o : (List<?>) req("GET", url + "/configs", apiKey, secret, bearer).send(ctx).map().getOrDefault("data", List.of()))
            if (o instanceof Map<?, ?> c) cfg.put(String.valueOf(c.get("name")), c.get("value"));
        return Out.of("info", info, "settings", cfg);
    }

    @Action(id = "read_messages", name = "Read messages", category = "Messages", timeoutSeconds = 900, capabilities = "network",
            description = "Read new messages from one or more topics as a consumer group, so the next read carries on where this one stopped. "
                    + "Needs a REST Proxy that supports consumer instances (self-managed Confluent REST Proxy).")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: topic, partition, offset, key, value. JSON values are shown as text.")
    @Output(name = "messages", type = OoaType.LIST, description = "Each message as a dictionary; JSON values stay structured.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of messages read.")
    public Map<String, Object> read(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "topics", type = OoaType.LIST, description = "Topics to read, e.g. [\"orders\"].") List<Object> topics,
            @Input(name = "consumer_group", type = OoaType.STRING, example = "ooa-order-agent", description = "Reader group name. Agents with the same group share the work and never get the same message twice.") String group,
            @Input(name = "max_messages", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Stop once this many messages have arrived. The last batch is kept whole, so a few more can come back.") int max,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Stop when no new message arrives within this time.") int wait,
            @Input(name = "value_format", type = OoaType.STRING, required = false, defaultValue = "\"json\"", enumValues = {"json", "text"}, description = "Read values as JSON or as plain text.") String format,
            @Input(name = "start_from", type = OoaType.STRING, required = false, defaultValue = "\"earliest\"", enumValues = {"earliest", "latest"},
                    description = "For a new group only: start at the oldest message kept (earliest) or only new messages (latest).") String start,
            @Input(name = "mark_as_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Remember what was read so the group does not read it again.") boolean commit,
            @Input(name = "api_key", type = OoaType.STRING, required = false, description = D_KEY) String apiKey,
            @Input(name = "api_secret", type = OoaType.CREDENTIAL, required = false, description = D_SECRET) Object secret,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_BEARER) Object bearer) {
        if (group == null || !GROUP.matcher(group.trim()).matches()) throw new ActionException(P + ".BAD_GROUP", "Invalid consumer group '" + group + "'", "Use letters, digits, dot, underscore and dash.");
        if (topics == null || topics.isEmpty()) throw new ActionException(P + ".BAD_TOPIC", "Give at least one topic", "Example: [\"orders\"].");
        List<String> ts = new ArrayList<>();
        for (Object o : topics) ts.add(topic(Values.text(o)));
        boolean text = "text".equals(format);
        String groupUrl = root(base) + "/consumers/" + HttpJson.enc(group.trim());
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("name", "ooa-" + UUID.randomUUID().toString().substring(0, 12));
        create.put("format", text ? "binary" : "json");
        create.put("auto.offset.reset", "latest".equals(start) ? "latest" : "earliest");
        create.put("auto.commit.enable", "false");
        Map<String, Object> inst = req("POST", groupUrl, apiKey, secret, bearer).json(create).header("Content-Type", V2).header("Accept", V2).send(ctx).map();
        String instUrl = groupUrl + "/instances/" + HttpJson.enc(Values.text(inst.get("instance_id")));
        List<Object> rows = new ArrayList<>();
        List<Object> messages = new ArrayList<>();
        try {
            req("POST", instUrl + "/subscription", apiKey, secret, bearer).json(Map.of("topics", ts)).header("Content-Type", V2).header("Accept", V2).send(ctx);
            String accept = text ? "application/vnd.kafka.binary.v2+json" : "application/vnd.kafka.json.v2+json";
            long idleUntil = System.currentTimeMillis() + Math.max(1, wait) * 1000L;
            while (messages.size() < max) {
                ctx.checkCancelled();
                HttpJson.Response r = req("GET", instUrl + "/records?timeout=1000", apiKey, secret, bearer).header("Accept", accept).timeout(60).send(ctx);
                List<?> batch = r.json() instanceof List<?> l ? l : List.of();
                for (Object o : batch) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    Object k = m.get("key"), v = m.get("value");
                    if (text) { k = k == null ? null : new String(Base64.getDecoder().decode(Values.text(k)), StandardCharsets.UTF_8); v = v == null ? null : new String(Base64.getDecoder().decode(Values.text(v)), StandardCharsets.UTF_8); }
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("topic", m.get("topic"));
                    msg.put("partition", m.get("partition"));
                    msg.put("offset", m.get("offset"));
                    msg.put("key", k);
                    msg.put("value", v);
                    messages.add(msg);
                    rows.add(new ArrayList<>(Arrays.asList(m.get("topic"), m.get("partition"), m.get("offset"), k == null ? null : Values.text(k), v == null ? null : Values.text(v))));
                }
                if (!batch.isEmpty()) idleUntil = System.currentTimeMillis() + Math.max(1, wait) * 1000L;
                else if (System.currentTimeMillis() >= idleUntil) break;
            }
            if (commit && !messages.isEmpty())
                req("POST", instUrl + "/offsets", apiKey, secret, bearer).bytes(new byte[0], V2).header("Accept", V2).send(ctx);
        } finally {
            try { req("DELETE", instUrl, apiKey, secret, bearer).header("Content-Type", V2).header("Accept", V2).send(ctx); }
            catch (RuntimeException e) { ctx.warn("Could not close the Kafka reader: " + e.getMessage()); }
        }
        return Out.of("table", Values.table(List.of("topic", "partition", "offset", "key", "value"), rows), "messages", messages, "count", BigDecimal.valueOf(messages.size()));
    }
}
