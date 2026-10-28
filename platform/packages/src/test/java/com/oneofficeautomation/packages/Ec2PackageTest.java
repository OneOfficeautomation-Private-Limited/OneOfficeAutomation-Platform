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

            Map<String, Object> r = pkg.describeInstances(ctx, "ec2", null, Map.of("instance-state-name", List.of("running", "stopped"), "tag:Team", "Finance"), 1000);
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
                case "DescribeInstanceStatus" -> xml("<DescribeInstanceStatusResponse" + NS + "><instanceStatusSet><item><instanceId>" + A + "</instanceId>"
                        + "<availabilityZone>eu-west-1a</availabilityZone><instanceState><name>running</name></instanceState><systemStatus><status>ok</status></systemStatus>"
                        + "<instanceStatus><status>initializing</status></instanceStatus></item></instanceStatusSet></DescribeInstanceStatusResponse>");
                default -> new Object[]{400, ""};
            });
            ActionContext ctx = connected(f, null);
            assertEquals(Map.of(A, "running"), pkg.waitForState(ctx, "ec2", List.of(A), "running", 30, 0.05).get("states"));
            assertEquals(3, looks.get(), "it looked until the instance was running");
            fails("EC2.WAIT_TIMEOUT", () -> pkg.waitForState(ctx, "ec2", List.of(A), "stopped", 1, 0.2));
            Map<String, Object> st = pkg.getInstanceStatus(ctx, "ec2", List.of(A), true);
            assertEquals(Map.of(A, "running"), st.get("states"));
            assertTrue(String.valueOf(st.get("statuses")).contains("initializing"));
            assertEquals("true", form(f.last("POST", "/")).get("IncludeAllInstances"));
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
