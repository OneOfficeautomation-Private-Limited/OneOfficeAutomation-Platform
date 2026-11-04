package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Azure VMs toolkit against a local imitation of the Microsoft identity platform and Azure Resource Manager. */
class AzureVmPackageTest {
    static final String T = "11111111-2222-3333-4444-555555555555", C = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", S = "99999999-8888-7777-6666-555555555555";
    static final String SUB = "/subscriptions/" + S, RG = SUB + "/resourceGroups/rg-bots", VMS = "/providers/Microsoft\\.Compute/virtualMachines";
    final AzureVmPackage pkg = new AzureVmPackage();

    static Object[] ok(String json) { return new Object[]{200, json}; }
    static Object[] accepted(Map<String, String> headers) { return new Object[]{202, "", headers}; }

    static Map<String, String> form(Call c) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : c.text().split("&")) {
            int i = part.indexOf('=');
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String vm(String name, String group) {
        return "{\"id\":\"" + SUB + "/resourceGroups/" + group + "/providers/Microsoft.Compute/virtualMachines/" + name + "\",\"name\":\"" + name + "\",\"location\":\"westeurope\","
                + "\"tags\":{\"Team\":\"Finance\"},\"properties\":{\"vmId\":\"v-" + name + "\",\"provisioningState\":\"Succeeded\",\"hardwareProfile\":{\"vmSize\":\"Standard_B2s\"},"
                + "\"storageProfile\":{\"osDisk\":{\"osType\":\"Windows\",\"name\":\"" + name + "-os\",\"managedDisk\":{\"id\":\"" + SUB + "/resourceGroups/" + group
                + "/providers/Microsoft.Compute/disks/" + name + "-os\"}}}}}";
    }

    /** Token endpoint and subscription check, counting sign-ins. */
    static Fake signIn(Fake f, AtomicInteger tokens) {
        return f.on("POST /" + T + "/oauth2/v2\\.0/token", c -> ok("{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"arm-token-" + tokens.incrementAndGet() + "\"}"))
                .on("GET " + SUB, c -> ok("{\"subscriptionId\":\"" + S + "\",\"displayName\":\"Bots\",\"state\":\"Enabled\"}"));
    }

    ActionContext connected(Fake f) throws Exception {
        ActionContext ctx = ctx(PackagesTest.tmp());
        pkg.connect(ctx, T, C, new SecureString("s3cret~value", "azure/secret"), S, "public", f.url(), f.url(), true, "azure");
        return ctx;
    }

    @Test void connectSignsInAndListVmsReadsEveryPage() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger tokens = new AtomicInteger();
            f.on("GET " + SUB + VMS, c -> ok("{\"value\":[" + vm("invoice-bot", "rg-bots") + "],\"nextLink\":\"" + f.url() + "/page2?api-version=2024-07-01&$skiptoken=x\"}"))
                    .on("GET /page2", c -> ok("{\"value\":[" + vm("report-bot", "rg-reports") + "]}"))
                    .on("GET " + RG + VMS, c -> ok("{\"value\":[" + vm("invoice-bot", "rg-bots") + "]}"));
            signIn(f, tokens);
            ActionContext ctx = connected(f);
            Map<String, String> tk = form(f.calls.get(0));
            assertEquals("client_credentials", tk.get("grant_type"));
            assertEquals(C, tk.get("client_id"));
            assertEquals("s3cret~value", tk.get("client_secret"));
            assertEquals("https://management.azure.com/.default", tk.get("scope"));
            assertEquals("Bearer arm-token-1", f.calls.get(1).auth());
            assertEquals("api-version=2022-12-01", f.calls.get(1).query());

            Map<String, Object> all = pkg.listVms(ctx, "azure", null, 1000);
            assertEquals(List.of("invoice-bot", "report-bot"), all.get("names"));
            assertEquals(new BigDecimal("2"), all.get("count"));
            assertTrue(f.calls.get(2).query().contains("api-version=2024-07-01"), f.calls.get(2).query());
            assertTrue(f.calls.get(3).query().contains("skiptoken=x"), "the next page is read from nextLink");
            List<List<Object>> rows = rows(all.get("vms"));
            assertEquals(List.of("name", "resource_group", "location", "vm_size", "os_type", "provisioning_state", "vm_id", "id", "tags"), cols(all.get("vms")));
            assertEquals("rg-reports", rows.get(1).get(1));
            assertEquals("Standard_B2s", rows.get(0).get(3));
            assertEquals("Windows", rows.get(0).get(4));
            assertEquals(Map.of("Team", "Finance"), rows.get(0).get(8));
            assertEquals(1, tokens.get(), "the token is reused while it is valid");

            assertEquals(List.of("invoice-bot"), pkg.listVms(ctx, "azure", "rg-bots", 1000).get("names"));
            assertEquals(1, rows(pkg.listVms(ctx, "azure", null, 1).get("vms")).size(), "max_results stops early");
            fails("AZURE_VM.BAD_RESOURCE_GROUP", () -> pkg.listVms(ctx, "azure", "rg/../x", 10));
        }
    }

    @Test void nextLinkOnAnotherServerIsRefused() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + SUB + VMS, c -> ok("{\"value\":[],\"nextLink\":\"https://evil.example.com/page2\"}"));
            signIn(f, new AtomicInteger());
            ActionContext ctx = connected(f);
            fails("AZURE_VM.BAD_RESPONSE", () -> pkg.listVms(ctx, "azure", null, 10));
        }
    }

    @Test void statusFromInstanceView() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + RG + VMS + "/invoice-bot/instanceView", c -> ok("{\"computerName\":\"invoice-bot\",\"statuses\":["
                    + "{\"code\":\"ProvisioningState/succeeded\",\"level\":\"Info\",\"displayStatus\":\"Provisioning succeeded\",\"time\":\"2026-11-03T08:00:00Z\"},"
                    + "{\"code\":\"PowerState/deallocated\",\"level\":\"Info\",\"displayStatus\":\"VM deallocated\"}]}"));
            signIn(f, new AtomicInteger());
            ActionContext ctx = connected(f);
            Map<String, Object> st = pkg.getVmStatus(ctx, "azure", "rg-bots", "invoice-bot");
            assertEquals("deallocated", st.get("power_state"));
            assertEquals("succeeded", st.get("provisioning_state"));
            assertEquals("VM deallocated", st.get("display_status"));
            assertEquals(2, rows(st.get("statuses")).size());
            assertEquals("api-version=2024-07-01", f.last("GET", ".*/instanceView").query());
            fails("AZURE_VM.NOT_FOUND", () -> pkg.getVmStatus(ctx, "azure", "rg-bots", "missing-vm"));
            fails("AZURE_VM.BAD_VM_NAME", () -> pkg.getVmStatus(ctx, "azure", "rg-bots", "bad name!"));
        }
    }

    @Test void startStopRestartFollowLongRunningOperations() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger asyncPolls = new AtomicInteger(), locationPolls = new AtomicInteger();
            f.on("POST " + RG + VMS + "/invoice-bot/start", c -> accepted(Map.of("Azure-AsyncOperation", f.url() + "/ops/start?api-version=2024-07-01",
                            "Location", f.url() + "/ops/start-location", "Retry-After", "0")))
                    .on("GET /ops/start", c -> ok(asyncPolls.incrementAndGet() < 3 ? "{\"status\":\"InProgress\"}" : "{\"status\":\"Succeeded\"}"))
                    .on("POST " + RG + VMS + "/invoice-bot/deallocate", c -> accepted(Map.of("Location", f.url() + "/ops/dealloc", "Retry-After", "0")))
                    .on("GET /ops/dealloc", c -> locationPolls.incrementAndGet() < 2 ? new Object[]{202, "", Map.of("Retry-After", "0")} : new Object[]{200, ""})
                    .on("POST " + RG + VMS + "/invoice-bot/powerOff", c -> new Object[]{200, ""})
                    .on("POST " + RG + VMS + "/invoice-bot/restart", c -> accepted(Map.of("Azure-AsyncOperation", f.url() + "/ops/restart", "Retry-After", "0")))
                    .on("GET /ops/restart", c -> ok("{\"status\":\"Failed\",\"error\":{\"code\":\"OSProvisioningTimedOut\",\"message\":\"The VM did not start in time.\"}}"))
                    .on("POST " + RG + VMS + "/slow-bot/start", c -> accepted(Map.of("Azure-AsyncOperation", f.url() + "/ops/never", "Retry-After", "0.2")))
                    .on("GET /ops/never", c -> ok("{\"status\":\"InProgress\"}"));
            signIn(f, new AtomicInteger());
            ActionContext ctx = connected(f);

            assertEquals("Succeeded", pkg.startVm(ctx, "azure", "rg-bots", "invoice-bot", true, 30).get("status"));
            assertEquals(3, asyncPolls.get(), "Azure-AsyncOperation is checked until Succeeded");
            assertNull(f.last("GET", "/ops/start-location"), "Azure-AsyncOperation is preferred over Location");
            assertEquals("Bearer arm-token-1", f.last("GET", "/ops/start").auth());

            assertEquals("Succeeded", pkg.stopVm(ctx, "azure", "rg-bots", "invoice-bot", "deallocate", false, true, 30).get("status"));
            assertEquals(2, locationPolls.get(), "Location is checked until it stops answering 202");

            assertEquals("Succeeded", pkg.stopVm(ctx, "azure", "rg-bots", "invoice-bot", "power_off", true, true, 30).get("status"));
            assertEquals("api-version=2024-07-01&skipShutdown=true", f.last("POST", ".*/powerOff").query());

            assertEquals("Accepted", pkg.startVm(ctx, "azure", "rg-bots", "invoice-bot", false, 30).get("status"), "without waiting the step ends when Azure accepts");

            ActionException failed = fails("AZURE_VM.OPERATION_FAILED", () -> pkg.restartVm(ctx, "azure", "rg-bots", "invoice-bot", true, 30));
            assertTrue(failed.getMessage().contains("did not start in time"), failed.getMessage());
            assertEquals("OSProvisioningTimedOut", failed.details().get("azure_code"));

            long t0 = System.nanoTime();
            ActionException slow = fails("AZURE_VM.WAIT_TIMEOUT", () -> pkg.startVm(ctx, "azure", "rg-bots", "slow-bot", true, 1));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "the time limit is kept");
            assertTrue(String.valueOf(slow.details().get("operation_url")).endsWith("/ops/never"));
            fails("AZURE_VM.BAD_MODE", () -> pkg.stopVm(ctx, "azure", "rg-bots", "invoice-bot", "hibernate", false, true, 30));
        }
    }

    @Test void snapshotOfOsDiskOrNamedDisk() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger polls = new AtomicInteger();
            f.on("GET " + RG + VMS + "/invoice-bot", c -> ok(vm("invoice-bot", "rg-bots")))
                    .on("GET " + RG + "/providers/Microsoft\\.Compute/disks/.*", c -> ok("{\"name\":\"d\",\"location\":\"westeurope\",\"properties\":{\"diskSizeGB\":128}}"))
                    .on("PUT " + SUB + "/resourceGroups/.*/providers/Microsoft\\.Compute/snapshots/.*", c -> new Object[]{202,
                            "{\"id\":\"" + RG + "/providers/Microsoft.Compute/snapshots/" + c.path().replaceAll(".*/", "") + "\",\"properties\":{\"provisioningState\":\"Creating\"}}",
                            Map.of("Azure-AsyncOperation", f.url() + "/ops/snap", "Retry-After", "0")})
                    .on("GET /ops/snap", c -> ok(polls.incrementAndGet() < 2 ? "{\"status\":\"InProgress\"}" : "{\"status\":\"Succeeded\"}"));
            signIn(f, new AtomicInteger());
            ActionContext ctx = connected(f);

            Map<String, Object> r = pkg.createDiskSnapshot(ctx, "azure", "rg-bots", "invoice-bot", null, "invoice-os-1103", true, null, Map.of("Reason", "patching"), true, 60);
            assertEquals("Succeeded", r.get("status"));
            assertEquals(RG + "/providers/Microsoft.Compute/disks/invoice-bot-os", r.get("disk_id"));
            assertEquals(RG + "/providers/Microsoft.Compute/snapshots/invoice-os-1103", r.get("snapshot_id"));
            Call put = f.last("PUT", ".*/snapshots/.*");
            assertEquals("api-version=2024-03-02", put.query());
            Map<String, Object> body = put.json();
            assertEquals("westeurope", body.get("location"));
            assertEquals(Map.of("Reason", "patching"), body.get("tags"));
            assertEquals("Copy", HttpJson.path(body, "properties.creationData.createOption"));
            assertEquals(RG + "/providers/Microsoft.Compute/disks/invoice-bot-os", HttpJson.path(body, "properties.creationData.sourceResourceId"));
            assertEquals(true, HttpJson.path(body, "properties.incremental"));
            assertEquals(2, polls.get());

            Map<String, Object> named = pkg.createDiskSnapshot(ctx, "azure", "rg-bots", null, "data-disk-1", "data-1103", false, "rg-backups", null, false, 60);
            assertEquals("Accepted", named.get("status"));
            assertEquals(RG + "/providers/Microsoft.Compute/disks/data-disk-1", named.get("disk_id"));
            assertTrue(f.last("PUT", ".*/snapshots/.*").path().contains("/resourceGroups/rg-backups/"), "the snapshot goes to its own resource group");
            assertEquals(false, HttpJson.path(f.last("PUT", ".*/snapshots/.*").json(), "properties.incremental"));

            fails("AZURE_VM.NO_DISK", () -> pkg.createDiskSnapshot(ctx, "azure", "rg-bots", null, null, "x", false, null, null, true, 60));
            fails("AZURE_VM.NO_DISK", () -> pkg.createDiskSnapshot(ctx, "azure", "rg-bots", "invoice-bot", "d", "x", false, null, null, true, 60));
            fails("AZURE_VM.BAD_SNAPSHOT_NAME", () -> pkg.createDiskSnapshot(ctx, "azure", "rg-bots", "invoice-bot", null, "", false, null, null, true, 60));
        }
    }

    @Test void signInAndResourceManagerErrors() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger tokens = new AtomicInteger(), expired = new AtomicInteger(), busy = new AtomicInteger();
            f.on("POST /bad-tenant\\.onmicrosoft\\.com/oauth2/v2\\.0/token", c -> new Object[]{401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\\r\\nTrace ID: x\"}"})
                    .on("GET " + RG + VMS + "/expired-bot/instanceView", c -> expired.incrementAndGet() == 1
                            ? new Object[]{401, "{\"error\":{\"code\":\"ExpiredAuthenticationToken\",\"message\":\"The access token expiry has passed.\"}}"}
                            : ok("{\"statuses\":[{\"code\":\"PowerState/running\",\"displayStatus\":\"VM running\"}]}"))
                    .on("GET " + RG + VMS + "/denied-bot/instanceView", c -> new Object[]{401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"The token is invalid.\"}}"})
                    .on("POST " + RG + VMS + "/locked-bot/start", c -> new Object[]{403, "{\"error\":{\"code\":\"AuthorizationFailed\",\"message\":\"The client does not have authorization to perform action.\"}}"})
                    .on("POST " + RG + VMS + "/busy-bot/start", c -> new Object[]{409, "{\"error\":{\"code\":\"OperationNotAllowed\",\"message\":\"Another operation is in progress.\"}}"})
                    .on("GET " + RG + VMS + "/throttled-bot/instanceView", c -> busy.incrementAndGet() == 1
                            ? new Object[]{429, "{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"Slow down\"}}", Map.of("Retry-After", "0")}
                            : ok("{\"statuses\":[{\"code\":\"PowerState/stopped\"}]}"))
                    .on("GET " + RG + VMS + "/limit-bot/instanceView", c -> new Object[]{429, "{\"error\":{\"code\":\"SubscriptionRequestsThrottled\",\"message\":\"Too many requests\"}}"})
                    .on("GET " + RG + VMS + "/broken-bot/instanceView", c -> new Object[]{503, "{\"error\":{\"code\":\"ServiceUnavailable\",\"message\":\"Try later\"}}"});
            signIn(f, tokens);
            ActionContext ctx = ctx(PackagesTest.tmp());
            SecureString secret = new SecureString("s3cret", "azure/secret");
            ActionException auth = fails("AZURE_VM.AUTH_FAILED", () -> pkg.connect(ctx, "bad-tenant.onmicrosoft.com", C, secret, S, "public", f.url(), f.url(), true, "azure"));
            assertTrue(auth.getMessage().contains("AADSTS7000215") && !auth.getMessage().contains("Trace"), auth.getMessage());
            assertEquals("invalid_client", auth.details().get("azure_code"));
            assertFalse(ctx.sessions().containsKey("azure"), "a failed sign-in opens no session");

            pkg.connect(ctx, T, C, secret, S, null, f.url(), f.url(), true, "azure");
            assertEquals("running", pkg.getVmStatus(ctx, "azure", "rg-bots", "expired-bot").get("power_state"));
            assertEquals(2, tokens.get(), "an expired token is replaced once");
            assertEquals("Bearer arm-token-2", f.last("GET", ".*/expired-bot/instanceView").auth());
            fails("AZURE_VM.AUTH_FAILED", () -> pkg.getVmStatus(ctx, "azure", "rg-bots", "denied-bot"));
            ActionException forbidden = fails("AZURE_VM.FORBIDDEN", () -> pkg.startVm(ctx, "azure", "rg-bots", "locked-bot", true, 30));
            assertEquals("AuthorizationFailed", forbidden.details().get("azure_code"));
            fails("AZURE_VM.WRONG_STATE", () -> pkg.startVm(ctx, "azure", "rg-bots", "busy-bot", true, 30));
            assertEquals("stopped", pkg.getVmStatus(ctx, "azure", "rg-bots", "throttled-bot").get("power_state"));
            assertEquals(2, busy.get(), "an HTTP 429 is sent again after Retry-After");
            ActionException limited = fails("AZURE_VM.RATE_LIMITED", () -> pkg.getVmStatus(ctx, "azure", "rg-bots", "limit-bot"));
            assertTrue(limited.retryable());
            assertTrue(fails("AZURE_VM.SERVER_BUSY", () -> pkg.getVmStatus(ctx, "azure", "rg-bots", "broken-bot")).retryable());

            fails("AZURE_VM.BAD_TENANT", () -> pkg.connect(ctx, "not a tenant", C, secret, S, null, null, null, false, "x"));
            fails("AZURE_VM.BAD_CLIENT_ID", () -> pkg.connect(ctx, T, "my-app", secret, S, null, null, null, false, "x"));
            fails("AZURE_VM.NO_SECRET", () -> pkg.connect(ctx, T, C, "plain text", S, null, null, null, false, "x"));
            fails("AZURE_VM.BAD_SUBSCRIPTION", () -> pkg.connect(ctx, T, C, secret, "Pay-As-You-Go", null, null, null, false, "x"));
            fails("AZURE_VM.BAD_CLOUD", () -> pkg.connect(ctx, T, C, secret, S, "mars", null, null, false, "x"));
            pkg.disconnect(ctx, "azure");
            assertFalse(ctx.sessions().containsKey("azure"));
        }
    }

    @Test void sovereignCloudUsesItsOwnScope() throws Exception {
        try (Fake f = new Fake()) {
            signIn(f, new AtomicInteger());
            ActionContext ctx = ctx(PackagesTest.tmp());
            pkg.connect(ctx, T, C, new SecureString("s", "azure/secret"), S, "us_government", f.url(), f.url(), true, "gov");
            assertEquals("https://management.usgovcloudapi.net/.default", form(f.calls.get(0)).get("scope"));
        }
    }
}
