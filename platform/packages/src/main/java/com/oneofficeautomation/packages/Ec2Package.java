package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AWS EC2 virtual machines through EC2's query API, signed with AWS Signature Version 4 (no AWS SDK). Connect keeps
 * the region and the access keys from the vault in a session; the other steps list, start, stop, reboot, end, launch,
 * tag and watch instances.
 */
@OoaPackage(id = "ooa.aws_ec2", name = "AWS EC2", version = "1.0.0",
        description = "Start, stop, reboot, launch and end AWS EC2 virtual machines, read their state and tags, and switch detailed monitoring on or off.")
public final class Ec2Package {
    static final String P = "EC2";
    static final String API_VERSION = "2016-11-15";
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d{1,2}$");
    private static final Pattern INSTANCE_ID = Pattern.compile("^i-[0-9a-f]{8,17}$");
    private static final String D_SESSION = "Name of the session opened by Connect.";
    private static final String D_IDS = "Instance ids, e.g. [\"i-0abc1234def567890\"].";
    private static final List<String> INSTANCE_COLS = List.of("instance_id", "name", "state", "instance_type", "image_id", "private_ip", "public_ip",
            "availability_zone", "launch_time", "monitoring", "tags");
    private static final List<String> CHANGE_COLS = List.of("instance_id", "previous_state", "current_state");
    private static final List<String> STATUS_COLS = List.of("instance_id", "state", "system_status", "instance_status", "availability_zone");
    private static final List<String> STATES = List.of("pending", "running", "stopping", "stopped", "shutting-down", "terminated");

    /** What Connect keeps: where to send requests and the keys to sign them with (secrets stay SecureStrings). */
    static final class Ec2Session {
        final String region, endpoint;
        final SecureString accessKeyId, secretKey, sessionToken;
        Ec2Session(String region, String endpoint, SecureString accessKeyId, SecureString secretKey, SecureString sessionToken) {
            this.region = region; this.endpoint = endpoint; this.accessKeyId = accessKeyId; this.secretKey = secretKey; this.sessionToken = sessionToken;
        }
    }

    // ----- connection -----

    @Action(id = "connect", name = "Connect", category = "Connection", capabilities = "network",
            description = "Open a session for one AWS region with an access key from the vault; later EC2 steps use it.",
            panel = "session, region, access_key_id, secret_access_key | session_token, endpoint, check_now")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "region", type = OoaType.STRING, example = "eu-west-1", description = "AWS region of the instances, e.g. us-east-1, eu-west-1 or ap-south-1.") String region,
            @Input(name = "access_key_id", type = OoaType.CREDENTIAL, description = "Access key id from the vault.") Object accessKeyId,
            @Input(name = "secret_access_key", type = OoaType.CREDENTIAL, description = "Secret access key from the vault.") Object secretKey,
            @Input(name = "session_token", type = OoaType.CREDENTIAL, required = false, description = "Session token from the vault, for temporary keys (assumed roles, single sign-on).") Object sessionToken,
            @Input(name = "endpoint", type = OoaType.STRING, required = false, pattern = HttpJson.HTTPS_OR_LOCAL,
                    description = "Another EC2 address, e.g. a private VPC endpoint. Leave empty for AWS's address for the region.") String endpoint,
            @Input(name = "check_now", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Check the keys and region with AWS straight away.") boolean checkNow,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = "Session name.") String session) {
        String r = region == null ? "" : region.strip();
        if (!REGION.matcher(r).matches()) throw new ActionException(P + ".BAD_REGION", "'" + r + "' is not an AWS region", "Use a region code such as us-east-1, eu-west-1 or ap-south-1.");
        if (!(accessKeyId instanceof SecureString id) || id.reveal().isBlank())
            throw new ActionException(P + ".NO_ACCESS_KEY", "The access key id from the vault is required", "Map access_key_id to a Credential.");
        if (!(secretKey instanceof SecureString secret) || secret.reveal().isBlank())
            throw new ActionException(P + ".NO_SECRET_KEY", "The secret access key from the vault is required", "Map secret_access_key to a Credential.");
        String ep = endpoint == null || endpoint.isBlank() ? "https://ec2." + r + ".amazonaws.com" : endpoint.strip().replaceAll("/+$", "");
        Ec2Session s = new Ec2Session(r, ep, id, secret, sessionToken instanceof SecureString t && !t.reveal().isBlank() ? t : null);
        if (checkNow) call(ctx, s, "DescribeRegions", params("RegionName.1", r));
        ctx.sessions().put(session == null || session.isBlank() ? "ec2" : session, s);
        return Map.of();
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the EC2 session and forget its keys.", panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = "Session name.") String session) {
        ctx.sessions().remove(session);
        return Map.of();
    }

    // ----- reading -----

    @Action(id = "describe_instances", name = "Describe instances", category = "Instances", idempotent = true, capabilities = "network",
            description = "List instances with their state, type, addresses and tags: all of them, the ones you name, or those that match filters.",
            panel = "session, (Instances: All = / These ones = instance_ids), filters | max_results")
    @Output(name = "instances", type = OoaType.TABLE, description = "Columns: instance_id, name, state, instance_type, image_id, private_ip, public_ip, availability_zone, launch_time, monitoring, tags.")
    @Output(name = "instance_ids", type = OoaType.LIST, description = "The ids of the instances found.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many instances were found.")
    public Map<String, Object> describeInstances(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, required = false, description = D_IDS + " Leave empty for all.") List<Object> ids,
            @Input(name = "filters", type = OoaType.DICTIONARY, required = false,
                    description = "EC2 filters, name to value or list of values, e.g. {\"instance-state-name\": \"running\", \"tag:Team\": [\"Finance\", \"HR\"]}.") Map<String, Object> filters,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Stop after this many instances.") int max) {
        Ec2Session s = ctx.session(session, Ec2Session.class);
        Map<String, String> p = new LinkedHashMap<>();
        List<String> idList = ids(ids, false);
        list(p, "InstanceId", idList);
        filters(p, filters);
        List<Object> rows = new ArrayList<>();
        List<Object> found = new ArrayList<>();
        String next = null;
        do {
            Map<String, String> page = new LinkedHashMap<>(p);
            if (idList.isEmpty()) page.put("MaxResults", "1000");
            if (next != null) page.put("NextToken", next);
            Element root = call(ctx, s, "DescribeInstances", page);
            for (Element res : items(child(root, "reservationSet")))
                for (Element in : items(child(res, "instancesSet"))) {
                    if (rows.size() >= max) break;
                    rows.add(instanceRow(in));
                    found.add(text(in, "instanceId"));
                }
            next = text(root, "nextToken");
        } while (next != null && !next.isBlank() && rows.size() < max);
        return Out.of("instances", Values.table(INSTANCE_COLS, rows), "instance_ids", found, "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_instance_status", name = "Get instance status", category = "Instances", idempotent = true, capabilities = "network",
            description = "Read each instance's state and the results of AWS's system and instance health checks.",
            panel = "session, instance_ids, include_stopped")
    @Output(name = "statuses", type = OoaType.TABLE, description = "Columns: instance_id, state, system_status, instance_status, availability_zone.")
    @Output(name = "states", type = OoaType.DICTIONARY, description = "Instance id to state, e.g. {\"i-0abc1234def567890\": \"running\"}.")
    public Map<String, Object> getInstanceStatus(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, required = false, description = D_IDS + " Leave empty for all.") List<Object> ids,
            @Input(name = "include_stopped", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also list instances that are not running (AWS leaves them out otherwise).") boolean all) {
        Ec2Session s = ctx.session(session, Ec2Session.class);
        Map<String, String> p = new LinkedHashMap<>();
        List<String> idList = ids(ids, false);
        list(p, "InstanceId", idList);
        if (all) p.put("IncludeAllInstances", "true");
        List<Object> rows = new ArrayList<>();
        Map<String, Object> states = new LinkedHashMap<>();
        String next = null;
        do {
            Map<String, String> page = new LinkedHashMap<>(p);
            if (next != null) page.put("NextToken", next);
            Element root = call(ctx, s, "DescribeInstanceStatus", page);
            for (Element it : items(child(root, "instanceStatusSet"))) {
                String id = text(it, "instanceId"), state = text(child(it, "instanceState"), "name");
                rows.add(new ArrayList<>(Arrays.asList(id, state, text(child(it, "systemStatus"), "status"), text(child(it, "instanceStatus"), "status"), text(it, "availabilityZone"))));
                states.put(id, state);
            }
            next = text(root, "nextToken");
        } while (next != null && !next.isBlank());
        return Out.of("statuses", Values.table(STATUS_COLS, rows), "states", states);
    }

    // ----- changing state -----

    @Action(id = "start_instances", name = "Start instances", category = "Instances", capabilities = "network", description = "Start stopped instances.",
            panel = "session, instance_ids")
    @Output(name = "changes", type = OoaType.TABLE, description = "Columns: instance_id, previous_state, current_state.")
    public Map<String, Object> startInstances(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids) {
        return changes(call(ctx, ctx.session(session, Ec2Session.class), "StartInstances", list(new LinkedHashMap<>(), "InstanceId", ids(ids, true))));
    }

    @Action(id = "stop_instances", name = "Stop instances", category = "Instances", capabilities = "network",
            description = "Stop running instances; their disks are kept. Optionally hibernate them or force the stop.",
            panel = "session, instance_ids | hibernate, force")
    @Output(name = "changes", type = OoaType.TABLE, description = "Columns: instance_id, previous_state, current_state.")
    public Map<String, Object> stopInstances(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids,
            @Input(name = "hibernate", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Hibernate instead of stopping (the instance must be set up for it).") boolean hibernate,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Force the stop without a clean shutdown, for an instance that hangs.") boolean force) {
        Map<String, String> p = list(new LinkedHashMap<>(), "InstanceId", ids(ids, true));
        if (hibernate) p.put("Hibernate", "true");
        if (force) p.put("Force", "true");
        return changes(call(ctx, ctx.session(session, Ec2Session.class), "StopInstances", p));
    }

    @Action(id = "reboot_instances", name = "Reboot instances", category = "Instances", capabilities = "network", description = "Restart running instances.",
            panel = "session, instance_ids")
    public Map<String, Object> rebootInstances(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids) {
        call(ctx, ctx.session(session, Ec2Session.class), "RebootInstances", list(new LinkedHashMap<>(), "InstanceId", ids(ids, true)));
        return Map.of();
    }

    @Action(id = "terminate_instances", name = "Terminate instances", category = "Instances", capabilities = "network",
            description = "End instances for good; disks marked to be deleted on end are deleted too. This cannot be undone.",
            panel = "session, instance_ids")
    @Output(name = "changes", type = OoaType.TABLE, description = "Columns: instance_id, previous_state, current_state.")
    public Map<String, Object> terminateInstances(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids) {
        return changes(call(ctx, ctx.session(session, Ec2Session.class), "TerminateInstances", list(new LinkedHashMap<>(), "InstanceId", ids(ids, true))));
    }

    @Action(id = "enable_monitoring", name = "Enable monitoring", category = "Monitoring", capabilities = "network",
            description = "Switch on detailed (one-minute) CloudWatch monitoring for instances.", panel = "session, instance_ids")
    @Output(name = "monitoring", type = OoaType.DICTIONARY, description = "Instance id to monitoring state, e.g. pending or enabled.")
    public Map<String, Object> enableMonitoring(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids) {
        return monitoring(call(ctx, ctx.session(session, Ec2Session.class), "MonitorInstances", list(new LinkedHashMap<>(), "InstanceId", ids(ids, true))));
    }

    @Action(id = "disable_monitoring", name = "Disable monitoring", category = "Monitoring", capabilities = "network",
            description = "Go back to basic (five-minute) monitoring for instances.", panel = "session, instance_ids")
    @Output(name = "monitoring", type = OoaType.DICTIONARY, description = "Instance id to monitoring state, e.g. disabling or disabled.")
    public Map<String, Object> disableMonitoring(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids) {
        return monitoring(call(ctx, ctx.session(session, Ec2Session.class), "UnmonitorInstances", list(new LinkedHashMap<>(), "InstanceId", ids(ids, true))));
    }

    // ----- launching, tags, waiting -----

    @Action(id = "launch_instances", name = "Launch instances", category = "Instances", capabilities = "network",
            description = "Create new instances from a machine image, with a name, tags and a start-up script.",
            panel = "session, image_id, instance_type, count, name, key_pair, subnet_id, security_group_ids | tags, user_data")
    @Output(name = "instance_ids", type = OoaType.LIST, description = "The ids of the new instances.")
    @Output(name = "instances", type = OoaType.TABLE, description = "The new instances, with the same columns as Describe instances.")
    public Map<String, Object> launchInstances(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "image_id", type = OoaType.STRING, pattern = "^ami-[0-9a-f]{8,17}$", example = "ami-0abcdef1234567890", description = "Machine image (AMI) id.") String image,
            @Input(name = "instance_type", type = OoaType.STRING, example = "t3.micro", description = "Instance type, e.g. t3.micro or m6i.large.") String type,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many instances to create (1-100).") int count,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name shown in the AWS console (the Name tag).") String name,
            @Input(name = "key_pair", type = OoaType.STRING, required = false, description = "Name of the key pair for signing in to the instance.") String keyPair,
            @Input(name = "subnet_id", type = OoaType.STRING, required = false, description = "Subnet to start the instances in. Leave empty for the default.") String subnet,
            @Input(name = "security_group_ids", type = OoaType.LIST, required = false, description = "Security group ids. Leave empty for the default group.") List<Object> groups,
            @Input(name = "tags", type = OoaType.DICTIONARY, required = false, description = "More tags, e.g. {\"Team\": \"Finance\", \"CostCenter\": \"4711\"}.") Map<String, Object> tags,
            @Input(name = "user_data", type = OoaType.STRING, required = false, description = "Start-up script or cloud-init text, run when the instance first starts.") String userData) {
        if (count < 1 || count > 100) throw new ActionException(P + ".BAD_COUNT", "Count must be 1-100, not " + count);
        if (type == null || type.isBlank()) throw new ActionException(P + ".BAD_TYPE", "An instance type is required", "For example t3.micro.");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ImageId", image.strip());
        p.put("InstanceType", type.strip());
        p.put("MinCount", String.valueOf(count));
        p.put("MaxCount", String.valueOf(count));
        if (keyPair != null && !keyPair.isBlank()) p.put("KeyName", keyPair.strip());
        if (subnet != null && !subnet.isBlank()) p.put("SubnetId", subnet.strip());
        List<String> sg = new ArrayList<>();
        if (groups != null) for (Object g : groups) if (!Values.text(g).isBlank()) sg.add(Values.text(g).strip());
        list(p, "SecurityGroupId", sg);
        Map<String, Object> all = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) all.put("Name", name.strip());
        if (tags != null) all.putAll(tags);
        if (!all.isEmpty()) { p.put("TagSpecification.1.ResourceType", "instance"); tagParams(p, "TagSpecification.1.Tag", all); }
        if (userData != null && !userData.isBlank()) p.put("UserData", Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8)));
        Element root = call(ctx, ctx.session(session, Ec2Session.class), "RunInstances", p);
        List<Object> rows = new ArrayList<>();
        List<Object> found = new ArrayList<>();
        for (Element in : items(child(root, "instancesSet"))) { rows.add(instanceRow(in)); found.add(text(in, "instanceId")); }
        return Out.of("instance_ids", found, "instances", Values.table(INSTANCE_COLS, rows));
    }

    @Action(id = "set_tags", name = "Set tags", category = "Instances", capabilities = "network",
            description = "Add tags to instances, or change the values of tags they already have.", panel = "session, instance_ids, tags")
    public Map<String, Object> setTags(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids,
            @Input(name = "tags", type = OoaType.DICTIONARY, description = "Tag name to value, e.g. {\"Name\": \"Invoice bot\", \"Team\": \"Finance\"}.") Map<String, Object> tags) {
        if (tags == null || tags.isEmpty()) throw new ActionException(P + ".NO_TAGS", "No tags given", "Give at least one tag name and value.");
        Map<String, String> p = list(new LinkedHashMap<>(), "ResourceId", ids(ids, true));
        tagParams(p, "Tag", tags);
        call(ctx, ctx.session(session, Ec2Session.class), "CreateTags", p);
        return Map.of();
    }

    @Action(id = "wait_for_state", name = "Wait for state", category = "Instances", idempotent = true, capabilities = "network",
            description = "Wait until every instance named is in a state, e.g. running after a start or stopped after a stop.",
            panel = "session, instance_ids, state | timeout_seconds, check_every_seconds")
    @Output(name = "states", type = OoaType.DICTIONARY, description = "Instance id to the state it was in at the end.")
    public Map<String, Object> waitForState(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ec2\"", description = D_SESSION) String session,
            @Input(name = "instance_ids", type = OoaType.LIST, description = D_IDS) List<Object> ids,
            @Input(name = "state", type = OoaType.STRING, required = false, enumValues = {"running", "stopped", "terminated", "pending", "stopping", "shutting-down"},
                    defaultValue = "\"running\"", description = "The state to wait for.") String state,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Stop waiting after this many seconds and fail.") int timeout,
            @Input(name = "check_every_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Seconds between checks.") double every) {
        if (!STATES.contains(state)) throw new ActionException(P + ".BAD_STATE", "Unknown state '" + state + "'", "Use one of " + String.join(", ", STATES) + ".");
        Ec2Session s = ctx.session(session, Ec2Session.class);
        List<String> idList = ids(ids, true);
        long deadline = System.nanoTime() + Math.max(1, timeout) * 1_000_000_000L;
        Map<String, Object> states;
        while (true) {
            states = new LinkedHashMap<>();
            Element root = call(ctx, s, "DescribeInstances", list(new LinkedHashMap<>(), "InstanceId", idList));
            for (Element res : items(child(root, "reservationSet")))
                for (Element in : items(child(res, "instancesSet"))) states.put(text(in, "instanceId"), text(child(in, "instanceState"), "name"));
            boolean done = true;
            for (String id : idList) if (!state.equals(states.get(id))) done = false;
            if (done) return Out.of("states", states);
            if (System.nanoTime() > deadline)
                throw new ActionException(P + ".WAIT_TIMEOUT", "Not all instances were " + state + " after " + timeout + " s: " + states,
                        "Raise timeout_seconds, or check the instances in the AWS console.", false, Map.of("states", states));
            try { Thread.sleep((long) (Math.max(0.05, every) * 1000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    // ----- query API -----

    static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static List<String> ids(List<Object> ids, boolean required) {
        List<String> out = new ArrayList<>();
        if (ids != null) for (Object o : ids) {
            String id = Values.text(o).strip();
            if (id.isEmpty()) continue;
            if (!INSTANCE_ID.matcher(id).matches()) throw new ActionException(P + ".BAD_INSTANCE_ID", "'" + id + "' is not an instance id", "Instance ids look like i-0abc1234def567890.");
            if (!out.contains(id)) out.add(id);
        }
        if (required && out.isEmpty()) throw new ActionException(P + ".NO_INSTANCES", "No instance ids given", "Give at least one instance id, e.g. from Describe instances.");
        return out;
    }

    static Map<String, String> list(Map<String, String> p, String name, List<String> values) {
        for (int i = 0; i < values.size(); i++) p.put(name + "." + (i + 1), values.get(i));
        return p;
    }

    static void filters(Map<String, String> p, Map<String, Object> filters) {
        if (filters == null) return;
        int i = 0;
        for (var e : filters.entrySet()) {
            i++;
            p.put("Filter." + i + ".Name", e.getKey());
            List<?> vals = e.getValue() instanceof List<?> l ? l : List.of(e.getValue() == null ? "" : e.getValue());
            int j = 0;
            for (Object v : vals) p.put("Filter." + i + ".Value." + (++j), Values.text(v));
        }
    }

    static void tagParams(Map<String, String> p, String prefix, Map<String, Object> tags) {
        int i = 0;
        for (var e : tags.entrySet()) {
            i++;
            p.put(prefix + "." + i + ".Key", e.getKey());
            p.put(prefix + "." + i + ".Value", e.getValue() == null ? "" : Values.text(e.getValue()));
        }
    }

    /** Sends one signed query-API request (form body) and returns the response's root element. */
    static Element call(ActionContext ctx, Ec2Session s, String action, Map<String, String> params) {
        StringBuilder body = new StringBuilder("Action=").append(enc(action)).append("&Version=").append(API_VERSION);
        params.forEach((k, v) -> body.append('&').append(enc(k)).append('=').append(enc(v)));
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(s.endpoint + "/");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-www-form-urlencoded; charset=utf-8");
        if (s.sessionToken != null) headers.put("x-amz-security-token", s.sessionToken.reveal());
        Map<String, String> signed = new LinkedHashMap<>(headers);
        signed.putAll(AwsSigV4.sign("POST", uri, headers, payload, s.accessKeyId.reveal().strip(), s.secretKey.reveal().strip(), s.region, "ec2", Instant.now()));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        signed.forEach(b::header);
        ctx.info("EC2 " + action + " in " + s.region);
        HttpResponse<byte[]> r;
        try { r = HttpJson.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (HttpTimeoutException e) { throw new ActionException(P + ".TIMEOUT", "No answer from EC2 within 60 s", "Try again later.", true); }
        catch (IOException e) { throw new ActionException(P + ".UNREACHABLE", "Cannot reach " + uri.getHost() + ": " + e.getMessage(), "Check the region, proxy and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        Element root = parse(r.body());
        if (r.statusCode() >= 400 || root == null || "Response".equals(root.getTagName()) && child(root, "Errors") != null) throw error(r.statusCode(), root, r.body());
        return root;
    }

    static ActionException error(int status, Element root, byte[] raw) {
        Element err = root == null ? null : child(child(root, "Errors"), "Error");
        String code = err == null ? null : text(err, "Code"), msg = err == null ? null : text(err, "Message");
        if (msg == null) { String t = new String(raw, StandardCharsets.UTF_8); msg = t.length() > 300 ? t.substring(0, 300) : t; }
        boolean retry = status >= 500 || "RequestLimitExceeded".equals(code) || "Unavailable".equals(code) || "InternalError".equals(code);
        String ours = code == null ? (status == 403 ? ".FORBIDDEN" : ".REQUEST_FAILED")
                : code.startsWith("AuthFailure") || code.equals("InvalidClientTokenId") || code.equals("SignatureDoesNotMatch") ? ".AUTH_FAILED"
                : code.equals("UnauthorizedOperation") ? ".FORBIDDEN"
                : code.startsWith("InvalidInstanceID") ? ".INSTANCE_NOT_FOUND"
                : code.equals("IncorrectInstanceState") ? ".WRONG_STATE"
                : retry ? ".SERVER_BUSY" : ".REQUEST_FAILED";
        String fix = ours.equals(".AUTH_FAILED") ? "Check the access keys in the vault and the region."
                : ours.equals(".FORBIDDEN") ? "The keys' IAM policy does not allow this EC2 action."
                : ours.equals(".INSTANCE_NOT_FOUND") ? "Check the instance ids and that they are in this region."
                : ours.equals(".WRONG_STATE") ? "The instance cannot do this in its current state; check it with Get instance status." : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", BigDecimal.valueOf(status));
        if (code != null) details.put("aws_code", code);
        return new ActionException(P + ours, "EC2 " + (code == null ? "HTTP " + status : code) + ": " + msg, fix, retry, details);
    }

    static Element parse(byte[] xml) {
        if (xml == null || xml.length == 0) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            return d.getDocumentElement();
        } catch (Exception e) { return null; }
    }

    static Element child(Element e, String name) {
        if (e == null) return null;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && c.getTagName().equals(name)) return c;
        return null;
    }

    static List<Element> items(Element set) {
        List<Element> out = new ArrayList<>();
        if (set == null) return out;
        for (Node n = set.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && c.getTagName().equals("item")) out.add(c);
        return out;
    }

    static String text(Element e, String name) {
        Element c = child(e, name);
        return c == null ? null : c.getTextContent().strip();
    }

    static List<Object> instanceRow(Element in) {
        Map<String, Object> tags = new LinkedHashMap<>();
        for (Element t : items(child(in, "tagSet"))) tags.put(text(t, "key"), text(t, "value"));
        return new ArrayList<>(Arrays.asList(text(in, "instanceId"), tags.get("Name"), text(child(in, "instanceState"), "name"), text(in, "instanceType"),
                text(in, "imageId"), text(in, "privateIpAddress"), text(in, "ipAddress"), text(child(in, "placement"), "availabilityZone"),
                text(in, "launchTime"), text(child(in, "monitoring"), "state"), tags));
    }

    static Map<String, Object> changes(Element root) {
        List<Object> rows = new ArrayList<>();
        for (Element it : items(child(root, "instancesSet")))
            rows.add(new ArrayList<>(Arrays.asList(text(it, "instanceId"), text(child(it, "previousState"), "name"), text(child(it, "currentState"), "name"))));
        return Out.of("changes", Values.table(CHANGE_COLS, rows));
    }

    static Map<String, Object> monitoring(Element root) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Element it : items(child(root, "instancesSet"))) m.put(text(it, "instanceId"), text(child(it, "monitoring"), "state"));
        return Out.of("monitoring", m);
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~"); }
}
