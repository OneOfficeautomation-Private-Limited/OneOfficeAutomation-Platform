package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** AWS EC2 toolkit against a local imitation of EC2's query API (form requests, XML answers). */
class Ec2PackageTest {
    static final String NS = " xmlns=\"http://ec2.amazonaws.com/doc/2016-11-15/\"";
    static final String A = "i-0aaaaaaaaaaaaaaa1", B = "i-0bbbbbbbbbbbbbbb2";
    final Ec2Package pkg = new Ec2Package();

    static Object[] xml(String body) { return new Object[]{200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + body}; }

    /** The form fields of a call, decoded. */
    static Map<String, String> form(Call c) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : c.text().split("&")) {
            int i = part.indexOf('=');
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String instance(String id, String state, String name) {
        return "<item><instanceId>" + id + "</instanceId><imageId>ami-0123456789abcdef0</imageId><instanceState><code>16</code><name>" + state + "</name></instanceState>"
                + "<privateIpAddress>10.0.0.5</privateIpAddress><instanceType>t3.micro</instanceType><launchTime>2026-10-01T08:00:00.000Z</launchTime>"
                + "<placement><availabilityZone>eu-west-1a</availabilityZone></placement><monitoring><state>disabled</state></monitoring>"
                + "<tagSet><item><key>Name</key><value>" + name + "</value></item><item><key>Team</key><value>Finance</value></item></tagSet></item>";
    }

    ActionContext connected(Fake f, SecureString token) throws Exception {
        ActionContext ctx = ctx(PackagesTest.tmp());
        pkg.connect(ctx, "eu-west-1", new SecureString("AKIDEXAMPLE", "aws/id"), new SecureString("wJalrXUtnFEMI/K7MDENG", "aws/secret"), token, f.url(), true, "ec2");
        return ctx;
    }

    @Test void connectSignsAndDescribeReadsEveryPage() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger page = new AtomicInteger();
            f.on("POST /", c -> {
                Map<String, String> p = form(c);
                return switch (p.get("Action")) {
                    case "DescribeRegions" -> xml("<DescribeRegionsResponse" + NS + "><regionInfo><item><regionName>eu-west-1</regionName></item></regionInfo></DescribeRegionsResponse>");
                    case "DescribeInstances" -> page.incrementAndGet() == 1
                            ? xml("<DescribeInstancesResponse" + NS + "><reservationSet><item><instancesSet>" + instance(A, "running", "Invoice bot") + "</instancesSet></item></reservationSet><nextToken>p2</nextToken></DescribeInstancesResponse>")
                            : xml("<DescribeInstancesResponse" + NS + "><reservationSet><item><instancesSet>" + instance(B, "stopped", "Report bot") + "</instancesSet></item></reservationSet></DescribeInstancesResponse>");
                    default -> new Object[]{400, "<Response><Errors><Error><Code>InvalidAction</Code><Message>no</Message></Error></Errors></Response>"};
                };
            });
            ActionContext ctx = connected(f, new SecureString("FQoGZXIvYXdzE", "aws/token"));
            Call check = f.calls.get(0);
            assertEquals("DescribeRegions", form(check).get("Action"));
            assertEquals("2016-11-15", form(check).get("Version"));
            assertEquals("eu-west-1", form(check).get("RegionName.1"));
            assertTrue(check.auth().startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), check.auth());
            assertTrue(check.auth().contains("/eu-west-1/ec2/aws4_request"), check.auth());
            assertTrue(check.auth().contains("x-amz-security-token"), "the session token is signed");
            assertEquals("FQoGZXIvYXdzE", check.header("x-amz-security-token"));

            Map<String, Object> r = pkg.describeInstances(ctx, "ec2", null, new LinkedHashMap<>(Map.of("instance-state-name", List.of("running", "stopped"))) {{ put("tag:Team", "Finance"); }}, 1000);
            assertEquals(List.of(A, B), r.get("instance_ids"));
            assertEquals(new java.math.BigDecimal("2"), r.get("count"));
            Map<String, String> p1 = form(f.calls.get(1)), p2 = form(f.calls.get(2));
            assertEquals("instance-state-name", p1.get("Filter.1.Name"));
            assertEquals("stopped", p1.get("Filter.1.Value.2"));
            assertEquals("tag:Team", p1.get("Filter.2.Name"));
            assertEquals("1000", p1.get("MaxResults"));
            assertNull(p1.get("NextToken"));
            assertEquals("p2", p2.get("NextToken"), "the next page is asked for with the token");
            String table = String.valueOf(r.get("instances"));
            assertTrue(table.contains("Invoice bot") && table.contains("Report bot") && table.contains("eu-west-1a") && table.contains("10.0.0.5"), table);
        }
    }

    @Test void startStopTerminateMonitoringTagsAndLaunch() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /", c -> {
                Map<String, String> p = form(c);
                String change = "<instancesSet><item><instanceId>" + A + "</instanceId><currentState><name>%s</name></currentState><previousState><name>%s</name></previousState></item></instancesSet>";
                return switch (p.get("Action")) {
                    case "DescribeRegions" -> xml("<DescribeRegionsResponse" + NS + "/>");
                    case "StartInstances" -> xml("<StartInstancesResponse" + NS + ">" + change.formatted("pending", "stopped") + "</StartInstancesResponse>");
                    case "StopInstances" -> xml("<StopInstancesResponse" + NS + ">" + change.formatted("stopping", "running") + "</StopInstancesResponse>");
                    case "TerminateInstances" -> xml("<TerminateInstancesResponse" + NS + ">" + change.formatted("shutting-down", "stopped") + "</TerminateInstancesResponse>");
                    case "RebootInstances" -> xml("<RebootInstancesResponse" + NS + "><return>true</return></RebootInstancesResponse>");
                    case "MonitorInstances" -> xml("<MonitorInstancesResponse" + NS + "><instancesSet><item><instanceId>" + A + "</instanceId><monitoring><state>pending</state></monitoring></item></instancesSet></MonitorInstancesResponse>");
                    case "CreateTags" -> xml("<CreateTagsResponse" + NS + "><return>true</return></CreateTagsResponse>");
                    case "RunInstances" -> xml("<RunInstancesResponse" + NS + "><instancesSet>" + instance(A, "pending", "New bot") + instance(B, "pending", "New bot") + "</instancesSet></RunInstancesResponse>");
                    default -> new Object[]{400, "<Response><Errors><Error><Code>InvalidAction</Code><Message>no</Message></Error></Errors></Response>"};
                };
            });
            ActionContext ctx = connected(f, null);
            assertNull(f.calls.get(0).header("x-amz-security-token"));
            assertTrue(String.valueOf(pkg.startInstances(ctx, "ec2", List.of(A)).get("changes")).contains("stopped"));
            assertEquals(A, form(f.calls.get(1)).get("InstanceId.1"));
            pkg.stopInstances(ctx, "ec2", List.of(A, B, A), true, false);
            Map<String, String> stop = form(f.calls.get(2));
            assertEquals(B, stop.get("InstanceId.2"));
            assertNull(stop.get("InstanceId.3"), "a repeated id is sent once");
            assertEquals("true", stop.get("Hibernate"));
            assertNull(stop.get("Force"));
            pkg.rebootInstances(ctx, "ec2", List.of(A));
            assertTrue(String.valueOf(pkg.terminateInstances(ctx, "ec2", List.of(A)).get("changes")).contains("shutting-down"));
            assertEquals(Map.of(A, "pending"), pkg.enableMonitoring(ctx, "ec2", List.of(A)).get("monitoring"));

            pkg.setTags(ctx, "ec2", List.of(A, B), new LinkedHashMap<>(Map.of("Team", "HR")));
            Map<String, String> tags = form(f.last("POST", "/"));
            assertEquals("CreateTags", tags.get("Action"));
            assertEquals(B, tags.get("ResourceId.2"));
            assertEquals("Team", tags.get("Tag.1.Key"));
            assertEquals("HR", tags.get("Tag.1.Value"));

            Map<String, Object> launched = pkg.launchInstances(ctx, "ec2", "ami-0123456789abcdef0", "t3.micro", 2, "New bot", "ops-key", "subnet-0abc",
                    List.of("sg-01", "sg-02"), Map.of("Team", "Finance"), "#!/bin/bash\necho hi");
            assertEquals(List.of(A, B), launched.get("instance_ids"));
            Map<String, String> run = form(f.last("POST", "/"));
            assertEquals("2", run.get("MinCount"));
            assertEquals("2", run.get("MaxCount"));
            assertEquals("ops-key", run.get("KeyName"));
            assertEquals("sg-02", run.get("SecurityGroupId.2"));
            assertEquals("instance", run.get("TagSpecification.1.ResourceType"));
            assertEquals("Name", run.get("TagSpecification.1.Tag.1.Key"));
            assertEquals("New bot", run.get("TagSpecification.1.Tag.1.Value"));
            assertEquals("Finance", run.get("TagSpecification.1.Tag.2.Value"));
            assertEquals("#!/bin/bash\necho hi", new String(Base64.getDecoder().decode(run.get("UserData")), StandardCharsets.UTF_8));

            fails("EC2.BAD_INSTANCE_ID", () -> pkg.startInstances(ctx, "ec2", List.of("web-1")));
            fails("EC2.NO_INSTANCES", () -> pkg.stopInstances(ctx, "ec2", List.of(), false, false));
            fails("EC2.BAD_COUNT", () -> pkg.launchInstances(ctx, "ec2", "ami-0123456789abcdef0", "t3.micro", 0, null, null, null, null, null, null));
            fails("EC2.NO_TAGS", () -> pkg.setTags(ctx, "ec2", List.of(A), Map.of()));
        }
    }

    @Test void waitForStateAndStatus() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger looks = new AtomicInteger();
            f.on("POST /", c -> switch (form(c).get("Action")) {
                case "DescribeRegions" -> xml("<DescribeRegionsResponse" + NS + "/>");
                case "DescribeInstances" -> xml("<DescribeInstancesResponse" + NS + "><reservationSet><item><instancesSet>"
                        + instance(A, looks.incrementAndGet() < 3 ? "pending" : "running", "x") + "</instancesSet></item></reservationSet></DescribeInstancesResponse>");
                case "StartInstances" -> xml("<StartInstancesResponse" + NS + "><instancesSet><item><instanceId>" + A + "</instanceId><currentState><name>pending</name></currentState>"
                        + "<previousState><name>stopped</name></previousState></item></instancesSet></StartInstancesResponse>");
                case "DescribeInstanceStatus" -> xml("<DescribeInstanceStatusResponse" + NS + "><instanceStatusSet><item><instanceId>" + A + "</instanceId>"
                        + "<availabilityZone>eu-west-1a</availabilityZone><instanceState><name>running</name></instanceState><systemStatus><status>ok</status></systemStatus>"
                        + "<instanceStatus><status>initializing</status></instanceStatus></item></instanceStatusSet></DescribeInstanceStatusResponse>");
                default -> new Object[]{400, ""};
            });
            ActionContext ctx = connected(f, null);
            assertEquals(Map.of(A, "running"), pkg.waitForState(ctx, "ec2", List.of(A), "running", 30, 0.05).get("states"));
            assertEquals(3, looks.get(), "it looked until the instance was running");
            fails("EC2.WAIT_TIMEOUT", () -> pkg.waitForState(ctx, "ec2", List.of(A), "stopped", 1, 0.2));
            // K4 (batch 10): Start can wait until running, and gives the states with its changes
            looks.set(0);
            double every = Ec2Package.waitEverySeconds;
            Ec2Package.waitEverySeconds = 0.05;
            try {
                Map<String, Object> started = pkg.startInstances(ctx, "ec2", List.of(A), true, 30);
                assertEquals(Map.of(A, "running"), started.get("states"));
                assertTrue(String.valueOf(started.get("changes")).contains("stopped"));
                assertEquals(3, looks.get());
            } finally { Ec2Package.waitEverySeconds = every; }
            assertTrue(java.util.Arrays.stream(Ec2Package.class.getMethods()).map(m -> m.getAnnotation(com.oneofficeautomation.sdk.Action.class))
                    .filter(a -> a != null && a.id().equals("wait_for_state")).findFirst().orElseThrow().timeoutSeconds() >= 3600,
                    "the step's own time limit is longer than its longest wait");
            Map<String, Object> st = pkg.getInstanceStatus(ctx, "ec2", List.of(A), true);
            assertEquals(Map.of(A, "running"), st.get("states"));
            assertTrue(String.valueOf(st.get("statuses")).contains("initializing"));
            assertEquals("true", form(f.last("POST", "/")).get("IncludeAllInstances"));
        }
    }

    static final String VOL = "vol-0aaaaaaaaaaaaaaa1", SNAP = "snap-0aaaaaaaaaaaaaaa1", SNAP2 = "snap-0bbbbbbbbbbbbbbb2";

    static String snapshot(String id, String state) {
        return "<item><snapshotId>" + id + "</snapshotId><volumeId>" + VOL + "</volumeId><status>" + state + "</status><startTime>2026-11-01T08:00:00.000Z</startTime>"
                + "<progress>100%</progress><ownerId>123456789012</ownerId><volumeSize>30</volumeSize><description>Nightly</description><encrypted>true</encrypted>"
                + "<tagSet><item><key>Team</key><value>Finance</value></item></tagSet></item>";
    }

    @Test void snapshotsCreateListPageAndDelete() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger page = new AtomicInteger();
            f.on("POST /", c -> switch (form(c).get("Action")) {
                case "DescribeRegions" -> xml("<DescribeRegionsResponse" + NS + "/>");
                case "CreateSnapshot" -> xml("<CreateSnapshotResponse" + NS + "><snapshotId>" + SNAP + "</snapshotId><volumeId>" + VOL + "</volumeId>"
                        + "<status>pending</status><startTime>2026-11-03T09:00:00.000Z</startTime><progress/><volumeSize>30</volumeSize></CreateSnapshotResponse>");
                case "DescribeSnapshots" -> page.incrementAndGet() == 1
                        ? xml("<DescribeSnapshotsResponse" + NS + "><snapshotSet>" + snapshot(SNAP, "completed") + "</snapshotSet><nextToken>t2</nextToken></DescribeSnapshotsResponse>")
                        : xml("<DescribeSnapshotsResponse" + NS + "><snapshotSet>" + snapshot(SNAP2, "pending") + "</snapshotSet></DescribeSnapshotsResponse>");
                case "DeleteSnapshot" -> form(c).get("SnapshotId").equals(SNAP)
                        ? xml("<DeleteSnapshotResponse" + NS + "><return>true</return></DeleteSnapshotResponse>")
                        : new Object[]{400, "<Response><Errors><Error><Code>InvalidSnapshot.NotFound</Code><Message>The snapshot does not exist.</Message></Error></Errors></Response>"};
                default -> new Object[]{400, "<Response><Errors><Error><Code>InvalidAction</Code><Message>no</Message></Error></Errors></Response>"};
            });
            ActionContext ctx = connected(f, null);
            Map<String, Object> made = pkg.createSnapshot(ctx, "ec2", VOL, "Before patching", new LinkedHashMap<>(Map.of("Name", "pre-patch")));
            assertEquals(SNAP, made.get("snapshot_id"));
            assertEquals("pending", made.get("state"));
            Map<String, String> cs = form(f.last("POST", "/"));
            assertEquals(VOL, cs.get("VolumeId"));
            assertEquals("Before patching", cs.get("Description"));
            assertEquals("snapshot", cs.get("TagSpecification.1.ResourceType"));
            assertEquals("pre-patch", cs.get("TagSpecification.1.Tag.1.Value"));

            Map<String, Object> listed = pkg.listSnapshots(ctx, "ec2", VOL, Map.of("Team", "Finance"), "self", Map.of("status", List.of("completed", "pending")), 1000);
            assertEquals(List.of(SNAP, SNAP2), listed.get("snapshot_ids"));
            assertEquals(new java.math.BigDecimal("2"), listed.get("count"));
            int n = f.calls.size();
            Map<String, String> p1 = form(f.calls.get(n - 2)), p2 = form(f.calls.get(n - 1));
            assertEquals("self", p1.get("Owner.1"));
            assertEquals("volume-id", p1.get("Filter.1.Name"));
            assertEquals(VOL, p1.get("Filter.1.Value.1"));
            assertEquals("tag:Team", p1.get("Filter.2.Name"));
            assertEquals("Finance", p1.get("Filter.2.Value.1"));
            assertEquals("status", p1.get("Filter.3.Name"));
            assertEquals("pending", p1.get("Filter.3.Value.2"));
            assertNull(p1.get("NextToken"));
            assertEquals("t2", p2.get("NextToken"), "the second page is asked for with the token");
            List<List<Object>> rows = rows(listed.get("snapshots"));
            assertEquals(new java.math.BigDecimal("30"), rows.get(0).get(5));
            assertEquals(true, rows.get(0).get(7));
            assertEquals(Map.of("Team", "Finance"), rows.get(0).get(9));
            assertEquals(1, rows(pkg.listSnapshots(ctx, "ec2", null, null, null, null, 1).get("snapshots")).size(), "max_results stops early");

            pkg.deleteSnapshot(ctx, "ec2", SNAP);
            assertEquals(SNAP, form(f.last("POST", "/")).get("SnapshotId"));
            fails("EC2.SNAPSHOT_NOT_FOUND", () -> pkg.deleteSnapshot(ctx, "ec2", SNAP2));
            fails("EC2.BAD_SNAPSHOT_ID", () -> pkg.deleteSnapshot(ctx, "ec2", "ami-0123"));
            fails("EC2.BAD_VOLUME_ID", () -> pkg.createSnapshot(ctx, "ec2", "disk-1", null, null));
            fails("EC2.BAD_OWNER", () -> pkg.listSnapshots(ctx, "ec2", null, null, "everyone", null, 10));
        }
    }

    @Test void volumesCreateAttachDetach() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /", c -> switch (form(c).get("Action")) {
                case "DescribeRegions" -> xml("<DescribeRegionsResponse" + NS + "/>");
                case "CreateVolume" -> xml("<CreateVolumeResponse" + NS + "><volumeId>" + VOL + "</volumeId><size>" + form(c).getOrDefault("Size", "30") + "</size>"
                        + "<availabilityZone>eu-west-1a</availabilityZone><status>creating</status><volumeType>gp3</volumeType></CreateVolumeResponse>");
                case "AttachVolume" -> xml("<AttachVolumeResponse" + NS + "><volumeId>" + VOL + "</volumeId><instanceId>" + A + "</instanceId><device>/dev/sdf</device>"
                        + "<status>attaching</status></AttachVolumeResponse>");
                case "DetachVolume" -> "true".equals(form(c).get("Force"))
                        ? new Object[]{400, "<Response><Errors><Error><Code>IncorrectState</Code><Message>Volume is not attached</Message></Error></Errors></Response>"}
                        : xml("<DetachVolumeResponse" + NS + "><volumeId>" + VOL + "</volumeId><status>detaching</status></DetachVolumeResponse>");
                default -> new Object[]{400, "<Response><Errors><Error><Code>InvalidVolume.NotFound</Code><Message>The volume does not exist</Message></Error></Errors></Response>"};
            });
            ActionContext ctx = connected(f, null);
            Map<String, Object> v = pkg.createVolume(ctx, "ec2", "eu-west-1a", 50, "gp3", null, Map.of("Name", "Invoice data"));
            assertEquals(VOL, v.get("volume_id"));
            assertEquals("creating", v.get("state"));
            assertEquals(new java.math.BigDecimal("50"), v.get("size_gib"));
            Map<String, String> cv = form(f.last("POST", "/"));
            assertEquals("eu-west-1a", cv.get("AvailabilityZone"));
            assertEquals("50", cv.get("Size"));
            assertEquals("gp3", cv.get("VolumeType"));
            assertNull(cv.get("SnapshotId"));
            assertEquals("volume", cv.get("TagSpecification.1.ResourceType"));

            pkg.createVolume(ctx, "ec2", "eu-west-1a", 0, "io2", SNAP, null);
            Map<String, String> fromSnap = form(f.last("POST", "/"));
            assertEquals(SNAP, fromSnap.get("SnapshotId"));
            assertNull(fromSnap.get("Size"), "size 0 takes the snapshot's size");
            assertEquals("io2", fromSnap.get("VolumeType"));

            assertEquals("attaching", pkg.attachVolume(ctx, "ec2", VOL, A, "/dev/sdf").get("state"));
            Map<String, String> at = form(f.last("POST", "/"));
            assertEquals(VOL, at.get("VolumeId"));
            assertEquals(A, at.get("InstanceId"));
            assertEquals("/dev/sdf", at.get("Device"));

            assertEquals("detaching", pkg.detachVolume(ctx, "ec2", VOL, false).get("state"));
            assertNull(form(f.last("POST", "/")).get("Force"));
            fails("EC2.VOLUME_WRONG_STATE", () -> pkg.detachVolume(ctx, "ec2", VOL, true));
            assertEquals("true", form(f.last("POST", "/")).get("Force"));

            fails("EC2.BAD_SIZE", () -> pkg.createVolume(ctx, "ec2", "eu-west-1a", 0, "gp3", null, null));
            fails("EC2.BAD_SIZE", () -> pkg.createVolume(ctx, "ec2", "eu-west-1a", 70000, "gp3", null, null));
            fails("EC2.BAD_ZONE", () -> pkg.createVolume(ctx, "ec2", "Ireland A", 10, "gp3", null, null));
            fails("EC2.BAD_VOLUME_TYPE", () -> pkg.createVolume(ctx, "ec2", "eu-west-1a", 10, "ssd", null, null));
            fails("EC2.NO_DEVICE", () -> pkg.attachVolume(ctx, "ec2", VOL, A, " "));
            fails("EC2.BAD_INSTANCE_ID", () -> pkg.attachVolume(ctx, "ec2", VOL, "web-1", "/dev/sdf"));
        }
    }

    @Test void volumeErrorsNotFoundForbiddenAndThrottling() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger throttled = new AtomicInteger();
            f.on("POST /", c -> switch (form(c).get("Action")) {
                case "DescribeRegions" -> xml("<DescribeRegionsResponse" + NS + "/>");
                case "AttachVolume" -> new Object[]{400, "<Response><Errors><Error><Code>InvalidVolume.NotFound</Code><Message>The volume 'vol-0aaaaaaaaaaaaaaa1' does not exist.</Message></Error></Errors></Response>"};
                case "CreateVolume" -> new Object[]{403, "<Response><Errors><Error><Code>UnauthorizedOperation</Code><Message>You are not authorized to perform this operation.</Message></Error></Errors></Response>"};
                case "DescribeSnapshots" -> throttled.incrementAndGet() == 1
                        ? new Object[]{429, "", Map.of("Retry-After", "0")}
                        : xml("<DescribeSnapshotsResponse" + NS + "><snapshotSet/></DescribeSnapshotsResponse>");
                default -> new Object[]{503, "<Response><Errors><Error><Code>RequestLimitExceeded</Code><Message>Request limit exceeded.</Message></Error></Errors></Response>"};
            });
            ActionContext ctx = connected(f, null);
            ActionException nf = fails("EC2.VOLUME_NOT_FOUND", () -> pkg.attachVolume(ctx, "ec2", VOL, A, "/dev/sdf"));
            assertEquals("InvalidVolume.NotFound", nf.details().get("aws_code"));
            fails("EC2.FORBIDDEN", () -> pkg.createVolume(ctx, "ec2", "eu-west-1a", 10, "gp3", null, null));
            assertEquals(new java.math.BigDecimal("0"), pkg.listSnapshots(ctx, "ec2", null, null, "self", null, 10).get("count"));
            assertEquals(2, throttled.get(), "an HTTP 429 is sent again after Retry-After");
            ActionException busy = fails("EC2.SERVER_BUSY", () -> pkg.createSnapshot(ctx, "ec2", VOL, null, null));
            assertTrue(busy.retryable(), "RequestLimitExceeded can be retried");
        }
    }

    @Test void awsErrorsBecomeClearErrors() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /", c -> switch (form(c).get("Action")) {
                case "DescribeRegions" -> new Object[]{401, "<Response><Errors><Error><Code>AuthFailure</Code><Message>AWS was not able to validate the provided access credentials</Message></Error></Errors><RequestID>r1</RequestID></Response>"};
                case "StartInstances" -> new Object[]{400, "<Response><Errors><Error><Code>InvalidInstanceID.NotFound</Code><Message>The instance ID does not exist</Message></Error></Errors></Response>"};
                default -> new Object[]{503, "<Response><Errors><Error><Code>RequestLimitExceeded</Code><Message>Request limit exceeded.</Message></Error></Errors></Response>"};
            });
            ActionContext ctx = ctx(PackagesTest.tmp());
            SecureString id = new SecureString("AKIDEXAMPLE", "aws/id"), secret = new SecureString("wJalrXUtnFEMI/K7MDENG", "aws/secret");
            ActionException auth = fails("EC2.AUTH_FAILED", () -> pkg.connect(ctx, "eu-west-1", id, secret, null, f.url(), true, "ec2"));
            assertTrue(auth.getMessage().contains("validate"), auth.getMessage());
            assertFalse(ctx.sessions().containsKey("ec2"), "a failed check opens no session");
            pkg.connect(ctx, "eu-west-1", id, secret, null, f.url(), false, "ec2");
            fails("EC2.INSTANCE_NOT_FOUND", () -> pkg.startInstances(ctx, "ec2", List.of(A)));
            ActionException busy = fails("EC2.SERVER_BUSY", () -> pkg.rebootInstances(ctx, "ec2", List.of(A)));
            assertTrue(busy.retryable());
            fails("EC2.BAD_REGION", () -> pkg.connect(ctx, "Ireland", id, secret, null, null, false, "ec2"));
            fails("EC2.NO_SECRET_KEY", () -> pkg.connect(ctx, "eu-west-1", id, null, null, null, false, "ec2"));
            pkg.disconnect(ctx, "ec2");
            assertFalse(ctx.sessions().containsKey("ec2"));
        }
    }
}
