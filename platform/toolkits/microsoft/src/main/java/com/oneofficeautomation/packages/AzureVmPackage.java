package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Azure virtual machines through the Azure Resource Manager REST API (no Azure SDK). Connect signs in with a service
 * principal (client credentials against the Microsoft identity platform) and keeps the token in a session; the other
 * steps list VMs, read their power state, start, stop, restart them and snapshot their disks. Long-running operations
 * are followed through the Azure-AsyncOperation or Location address Azure returns, with a time limit.
 */
@OoaPackage(id = "ooa.azure_vm", name = "Azure VMs", version = "1.0.0",
        description = "List Azure virtual machines, read their power state, start, stop (deallocate or power off) and restart them, and snapshot their disks.")
public final class AzureVmPackage {
    static final String P = "AZURE_VM";
    static final String VM_API = "2024-07-01";
    static final String DISK_API = "2024-03-02";
    static final String SUBSCRIPTION_API = "2022-12-01";
    private static final Pattern GUID = Pattern.compile("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");
    private static final Pattern TENANT = Pattern.compile("^([0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}|[A-Za-z0-9][A-Za-z0-9.-]{1,252}\\.[A-Za-z]{2,})$");
    private static final Pattern RESOURCE_GROUP = Pattern.compile("^[-\\w.()]{1,90}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][\\w.-]{0,79}$");
    private static final String D_SESSION = "Name of the session opened by Connect.";
    private static final String D_GROUP = "Resource group of the virtual machine.";
    private static final String D_VM = "Name of the virtual machine.";
    private static final String D_WAIT = "Wait until Azure has finished; otherwise the step ends as soon as Azure has accepted the request.";
    private static final String D_TIMEOUT = "Stop waiting after this many seconds and fail.";
    private static final List<String> VM_COLS = List.of("name", "resource_group", "location", "vm_size", "os_type", "provisioning_state", "vm_id", "id", "tags");
    private static final List<String> STATUS_COLS = List.of("code", "level", "display_status", "time");

    /** Where an Azure cloud signs in and where its Resource Manager is. */
    private static final Map<String, String[]> CLOUDS = Map.of(
            "public", new String[]{"https://login.microsoftonline.com", "https://management.azure.com"},
            "us_government", new String[]{"https://login.microsoftonline.us", "https://management.usgovcloudapi.net"},
            "china", new String[]{"https://login.chinacloudapi.cn", "https://management.chinacloudapi.cn"});

    /** What Connect keeps: the service principal (secret stays a SecureString), the subscription and the current token. */
    static final class AzureSession {
        final String tenant, clientId, subscription, login, arm, scope;
        final SecureString secret;
        String token;
        long expiresAtMillis;
        AzureSession(String tenant, String clientId, SecureString secret, String subscription, String login, String arm, String scope) {
            this.tenant = tenant; this.clientId = clientId; this.secret = secret; this.subscription = subscription; this.login = login; this.arm = arm; this.scope = scope;
        }
        String sub() { return arm + "/subscriptions/" + subscription; }
    }

    /** One answer from Resource Manager. */
    record Resp(int status, Map<String, Object> json, HttpHeaders headers) {
        String header(String n) { return headers.firstValue(n).orElse(null); }
    }

    // ----- connection -----

    @Action(id = "connect", name = "Connect", category = "Connection", capabilities = "network",
            description = "Sign in to Azure with a service principal (app registration) from the vault for one subscription; later Azure VM steps use it.",
            panel = "session, tenant_id, client_id, client_secret, subscription_id | cloud, arm_endpoint, login_endpoint, check_now")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "tenant_id", type = OoaType.STRING, example = "contoso.onmicrosoft.com", description = "Directory (tenant) id, or the tenant's domain name.") String tenant,
            @Input(name = "client_id", type = OoaType.STRING, example = "00000000-0000-0000-0000-000000000000", description = "Application (client) id of the app registration.") String clientId,
            @Input(name = "client_secret", type = OoaType.CREDENTIAL, description = "Client secret of the app registration, from the vault.") Object secret,
            @Input(name = "subscription_id", type = OoaType.STRING, example = "00000000-0000-0000-0000-000000000000", description = "Id of the subscription that holds the virtual machines.") String subscription,
            @Input(name = "cloud", type = OoaType.STRING, required = false, defaultValue = "\"public\"", enumValues = {"public", "us_government", "china"},
                    description = "Azure cloud: public (global Azure), us_government or china.") String cloud,
            @Input(name = "arm_endpoint", type = OoaType.STRING, required = false, pattern = HttpJson.HTTPS_OR_LOCAL,
                    description = "Another Resource Manager address, e.g. a private link. Leave empty for the cloud's address.") String armEndpoint,
            @Input(name = "login_endpoint", type = OoaType.STRING, required = false, pattern = HttpJson.HTTPS_OR_LOCAL,
                    description = "Another sign-in address. Leave empty for the cloud's Microsoft identity platform address.") String loginEndpoint,
            @Input(name = "check_now", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Sign in and read the subscription straight away.") boolean checkNow,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = "Session name.") String session) {
        String t = tenant == null ? "" : tenant.strip();
        if (!TENANT.matcher(t).matches()) throw new ActionException(P + ".BAD_TENANT", "'" + t + "' is not a tenant id", "Use the directory (tenant) id, a GUID, or the tenant's domain such as contoso.onmicrosoft.com.");
        String c = clientId == null ? "" : clientId.strip();
        if (!GUID.matcher(c).matches()) throw new ActionException(P + ".BAD_CLIENT_ID", "'" + c + "' is not an application (client) id", "Copy the Application (client) id, a GUID, from the app registration.");
        if (!(secret instanceof SecureString sec) || sec.reveal().isBlank())
            throw new ActionException(P + ".NO_SECRET", "The client secret from the vault is required", "Map client_secret to a Credential.");
        String s = subscription == null ? "" : subscription.strip();
        if (!GUID.matcher(s).matches()) throw new ActionException(P + ".BAD_SUBSCRIPTION", "'" + s + "' is not a subscription id", "Subscription ids are GUIDs, e.g. from the Subscriptions page of the Azure portal.");
        String cl = cloud == null || cloud.isBlank() ? "public" : cloud.strip();
        String[] hosts = CLOUDS.get(cl);
        if (hosts == null) throw new ActionException(P + ".BAD_CLOUD", "Unknown Azure cloud '" + cl + "'", "Use public, us_government or china.");
        String login = loginEndpoint == null || loginEndpoint.isBlank() ? hosts[0] : loginEndpoint.strip().replaceAll("/+$", "");
        String arm = armEndpoint == null || armEndpoint.isBlank() ? hosts[1] : armEndpoint.strip().replaceAll("/+$", "");
        AzureSession a = new AzureSession(t, c, sec, s, login, arm, hosts[1] + "/.default");
        if (checkNow) { token(ctx, a, true); send(ctx, a, "GET", a.sub() + "?api-version=" + SUBSCRIPTION_API, null); }
        ctx.sessions().put(session == null || session.isBlank() ? "azure" : session, a);
        return Map.of();
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the Azure session and forget its token and secret.", panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = "Session name.") String session) {
        ctx.sessions().remove(session);
        return Map.of();
    }

    // ----- reading -----

    @Action(id = "list_vms", name = "List VMs", category = "Virtual machines", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the virtual machines of the subscription, or of one resource group, with their size, location and tags.",
            panel = "session, resource_group | max_results")
    @Output(name = "vms", type = OoaType.TABLE, description = "Columns: name, resource_group, location, vm_size, os_type, provisioning_state, vm_id, id, tags.")
    @Output(name = "names", type = OoaType.LIST, description = "The names of the virtual machines found.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many virtual machines were found.")
    public Map<String, Object> listVms(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = D_SESSION) String session,
            @Input(name = "resource_group", type = OoaType.STRING, required = false, description = "Only this resource group. Leave empty for the whole subscription.") String group,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Stop after this many virtual machines.") int max) {
        AzureSession s = ctx.session(session, AzureSession.class);
        String url = (group == null || group.isBlank() ? s.sub() : s.sub() + "/resourceGroups/" + group(group))
                + "/providers/Microsoft.Compute/virtualMachines?api-version=" + VM_API;
        List<Object> rows = new ArrayList<>();
        List<Object> names = new ArrayList<>();
        while (url != null && rows.size() < max) {
            Map<String, Object> page = send(ctx, s, "GET", url, null).json();
            for (Map<String, Object> vm : HttpJson.maps(page.get("value"))) {
                if (rows.size() >= max) break;
                String id = str(vm.get("id"));
                rows.add(new ArrayList<>(Arrays.asList(vm.get("name"), groupOf(id), vm.get("location"),
                        HttpJson.path(vm, "properties.hardwareProfile.vmSize"), HttpJson.path(vm, "properties.storageProfile.osDisk.osType"),
                        HttpJson.path(vm, "properties.provisioningState"), HttpJson.path(vm, "properties.vmId"), id,
                        vm.get("tags") instanceof Map<?, ?> tg ? tg : new LinkedHashMap<>())));
                names.add(vm.get("name"));
            }
            String next = page.get("nextLink") instanceof String n && !n.isBlank() ? n : null;
            if (next != null && !HttpJson.sameOrigin(s.arm, next))
                throw new ActionException(P + ".BAD_RESPONSE", "The next page is on another server (" + HttpJson.host(next) + ")", "Check the Resource Manager address.");
            url = next;
        }
        return Out.of("vms", Values.table(VM_COLS, rows), "names", names, "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_vm_status", name = "Get VM status", category = "Virtual machines", idempotent = true, capabilities = "network",
            description = "Read a virtual machine's power state (running, stopped, deallocated and so on) and provisioning state from its instance view.",
            panel = "session, resource_group, vm_name")
    @Output(name = "power_state", type = OoaType.STRING, description = "Power state: starting, running, stopping, stopped, deallocating, deallocated or unknown.")
    @Output(name = "provisioning_state", type = OoaType.STRING, description = "Provisioning state, e.g. succeeded, updating or failed.")
    @Output(name = "display_status", type = OoaType.STRING, description = "Azure's text for the power state, e.g. VM running.")
    @Output(name = "statuses", type = OoaType.TABLE, description = "Columns: code, level, display_status, time.")
    public Map<String, Object> getVmStatus(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = D_SESSION) String session,
            @Input(name = "resource_group", type = OoaType.STRING, description = D_GROUP) String group,
            @Input(name = "vm_name", type = OoaType.STRING, description = D_VM) String vm) {
        AzureSession s = ctx.session(session, AzureSession.class);
        Map<String, Object> view = send(ctx, s, "GET", vmUrl(s, group, vm) + "/instanceView?api-version=" + VM_API, null).json();
        String power = "unknown", prov = null, display = null;
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> st : HttpJson.maps(view.get("statuses"))) {
            String code = str(st.get("code"));
            rows.add(new ArrayList<>(Arrays.asList(code, st.get("level"), st.get("displayStatus"), st.get("time"))));
            if (code != null && code.startsWith("PowerState/")) { power = code.substring(11); display = str(st.get("displayStatus")); }
            if (code != null && code.startsWith("ProvisioningState/")) prov = code.substring(18);
        }
        return Out.of("power_state", power, "provisioning_state", prov, "display_status", display, "statuses", Values.table(STATUS_COLS, rows));
    }

    // ----- changing state -----

    @Action(id = "start_vm", name = "Start VM", category = "Virtual machines", timeoutSeconds = 3600, capabilities = "network",
            description = "Start a stopped or deallocated virtual machine.", panel = "session, resource_group, vm_name | wait, timeout_seconds")
    @Output(name = "status", type = OoaType.STRING, description = "Succeeded when waited for, otherwise Accepted.")
    public Map<String, Object> startVm(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = D_SESSION) String session,
            @Input(name = "resource_group", type = OoaType.STRING, description = D_GROUP) String group,
            @Input(name = "vm_name", type = OoaType.STRING, description = D_VM) String vm,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_WAIT) boolean wait,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", description = D_TIMEOUT) int timeout) {
        AzureSession s = ctx.session(session, AzureSession.class);
        return operation(ctx, s, "POST", vmUrl(s, group, vm) + "/start?api-version=" + VM_API, wait, timeout);
    }

    @Action(id = "stop_vm", name = "Stop VM", category = "Virtual machines", timeoutSeconds = 3600, capabilities = "network",
            description = "Stop a virtual machine: deallocate it (compute is no longer billed) or only power it off (the hardware stays reserved and billed).",
            panel = "session, resource_group, vm_name, mode | skip_shutdown, wait, timeout_seconds")
    @Output(name = "status", type = OoaType.STRING, description = "Succeeded when waited for, otherwise Accepted.")
    public Map<String, Object> stopVm(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = D_SESSION) String session,
            @Input(name = "resource_group", type = OoaType.STRING, description = D_GROUP) String group,
            @Input(name = "vm_name", type = OoaType.STRING, description = D_VM) String vm,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"deallocate\"", enumValues = {"deallocate", "power_off"},
                    description = "deallocate: release the hardware so compute is not billed; power_off: stop the VM but keep (and pay for) its hardware.") String mode,
            @Input(name = "skip_shutdown", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Power off without a clean shutdown of the operating system (power_off only).") boolean skipShutdown,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_WAIT) boolean wait,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", description = D_TIMEOUT) int timeout) {
        String m = mode == null || mode.isBlank() ? "deallocate" : mode.strip();
        if (!m.equals("deallocate") && !m.equals("power_off")) throw new ActionException(P + ".BAD_MODE", "Unknown stop mode '" + m + "'", "Use deallocate or power_off.");
        AzureSession s = ctx.session(session, AzureSession.class);
        String url = vmUrl(s, group, vm) + (m.equals("deallocate") ? "/deallocate?api-version=" + VM_API
                : "/powerOff?api-version=" + VM_API + (skipShutdown ? "&skipShutdown=true" : ""));
        return operation(ctx, s, "POST", url, wait, timeout);
    }

    @Action(id = "restart_vm", name = "Restart VM", category = "Virtual machines", timeoutSeconds = 3600, capabilities = "network",
            description = "Restart a running virtual machine.", panel = "session, resource_group, vm_name | wait, timeout_seconds")
    @Output(name = "status", type = OoaType.STRING, description = "Succeeded when waited for, otherwise Accepted.")
    public Map<String, Object> restartVm(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = D_SESSION) String session,
            @Input(name = "resource_group", type = OoaType.STRING, description = D_GROUP) String group,
            @Input(name = "vm_name", type = OoaType.STRING, description = D_VM) String vm,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_WAIT) boolean wait,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", description = D_TIMEOUT) int timeout) {
        AzureSession s = ctx.session(session, AzureSession.class);
        return operation(ctx, s, "POST", vmUrl(s, group, vm) + "/restart?api-version=" + VM_API, wait, timeout);
    }

    // ----- disks -----

    @Action(id = "create_disk_snapshot", name = "Create disk snapshot", category = "Disks", timeoutSeconds = 3600, capabilities = "network",
            description = "Snapshot a managed disk: a virtual machine's OS disk, or a disk named directly.",
            panel = "session, resource_group, (Disk: OS disk of a VM = vm_name / Named disk = disk_name), snapshot_name | incremental, snapshot_resource_group, tags, wait, timeout_seconds")
    @Output(name = "snapshot_id", type = OoaType.STRING, description = "Resource id of the new snapshot.")
    @Output(name = "disk_id", type = OoaType.STRING, description = "Resource id of the disk that was copied.")
    @Output(name = "status", type = OoaType.STRING, description = "Succeeded when waited for, otherwise Accepted.")
    public Map<String, Object> createDiskSnapshot(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure\"", description = D_SESSION) String session,
            @Input(name = "resource_group", type = OoaType.STRING, description = "Resource group of the virtual machine or disk.") String group,
            @Input(name = "vm_name", type = OoaType.STRING, required = false, description = "Virtual machine whose OS disk to copy.") String vm,
            @Input(name = "disk_name", type = OoaType.STRING, required = false, description = "Name of the managed disk to copy, e.g. a data disk.") String disk,
            @Input(name = "snapshot_name", type = OoaType.STRING, example = "invoice-vm-os-2026-11-03", description = "Name for the new snapshot.") String name,
            @Input(name = "incremental", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Store only the changes since the disk's last incremental snapshot (cheaper); otherwise a full copy.") boolean incremental,
            @Input(name = "snapshot_resource_group", type = OoaType.STRING, required = false, description = "Resource group for the snapshot. Leave empty for the disk's group.") String snapGroup,
            @Input(name = "tags", type = OoaType.DICTIONARY, required = false, description = "Tags for the snapshot, e.g. {\"Reason\": \"Before patching\"}.") Map<String, Object> tags,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_WAIT) boolean wait,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1800", description = D_TIMEOUT) int timeout) {
        AzureSession s = ctx.session(session, AzureSession.class);
        String g = group(group);
        String snap = name(name, "snapshot");
        boolean byVm = vm != null && !vm.isBlank(), byDisk = disk != null && !disk.isBlank();
        if (byVm == byDisk) throw new ActionException(P + ".NO_DISK", byVm ? "Give a VM name or a disk name, not both" : "No VM or disk named",
                "Give vm_name to copy the VM's OS disk, or disk_name for a named disk.");
        String diskId;
        if (byVm) {
            Map<String, Object> v = send(ctx, s, "GET", vmUrl(s, g, vm) + "?api-version=" + VM_API, null).json();
            Object id = HttpJson.path(v, "properties.storageProfile.osDisk.managedDisk.id");
            if (!(id instanceof String d) || d.isBlank())
                throw new ActionException(P + ".NOT_MANAGED_DISK", "The OS disk of '" + vm.strip() + "' is not a managed disk", "Only managed disks can be snapshotted this way.");
            diskId = d;
        } else diskId = "/subscriptions/" + s.subscription + "/resourceGroups/" + g + "/providers/Microsoft.Compute/disks/" + name(disk, "disk");
        if (!diskId.startsWith("/subscriptions/")) throw new ActionException(P + ".BAD_RESPONSE", "Unexpected disk id '" + diskId + "'", "Check the disk id: copy it from the disk's Properties in the Azure portal (it starts with /subscriptions/).");
        Map<String, Object> d = send(ctx, s, "GET", s.arm + diskId + "?api-version=" + DISK_API, null).json();
        Map<String, Object> creation = new LinkedHashMap<>();
        creation.put("createOption", "Copy");
        creation.put("sourceResourceId", diskId);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("creationData", creation);
        props.put("incremental", incremental);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("location", d.get("location"));
        if (tags != null && !tags.isEmpty()) body.put("tags", tags);
        body.put("properties", props);
        String url = s.sub() + "/resourceGroups/" + (snapGroup == null || snapGroup.isBlank() ? g : group(snapGroup))
                + "/providers/Microsoft.Compute/snapshots/" + snap + "?api-version=" + DISK_API;
        Map<String, Object> r = operation(ctx, s, "PUT", url, body, wait, timeout);
        Object id = r.get("_id");
        r.remove("_id");
        r.put("snapshot_id", id);
        r.put("disk_id", diskId);
        return r;
    }

    // ----- Resource Manager -----

    static String group(String g) {
        String v = g == null ? "" : g.strip();
        if (!RESOURCE_GROUP.matcher(v).matches() || v.endsWith("."))
            throw new ActionException(P + ".BAD_RESOURCE_GROUP", "'" + v + "' is not a resource group name", "Resource group names have 1-90 letters, digits, - _ . ( ) and do not end in a dot.");
        return v;
    }

    static String name(String n, String what) {
        String v = n == null ? "" : n.strip();
        if (!NAME.matcher(v).matches())
            throw new ActionException(P + ".BAD_" + what.toUpperCase(Locale.ROOT) + "_NAME", "'" + v + "' is not a " + what + " name", "Use letters, digits, - _ and . (up to 80).");
        return v;
    }

    static String vmUrl(AzureSession s, String group, String vm) {
        return s.sub() + "/resourceGroups/" + group(group) + "/providers/Microsoft.Compute/virtualMachines/" + name(vm, "vm");
    }

    static String groupOf(String id) {
        if (id == null) return null;
        java.util.regex.Matcher m = Pattern.compile("(?i)/resourceGroups/([^/]+)").matcher(id);
        return m.find() ? m.group(1) : null;
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** A bearer token for Resource Manager, fetched again when it is about to run out (or when forced). */
    static String token(ActionContext ctx, AzureSession s, boolean force) {
        if (!force && s.token != null && System.currentTimeMillis() < s.expiresAtMillis - 120_000) return s.token;
        String form = "grant_type=client_credentials&client_id=" + HttpJson.enc(s.clientId) + "&client_secret=" + HttpJson.enc(s.secret.reveal())
                + "&scope=" + HttpJson.enc(s.scope);
        String url = s.login + "/" + HttpJson.enc(s.tenant) + "/oauth2/v2.0/token";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        ctx.info("Azure sign-in for tenant " + s.tenant);
        Resp r = exchange(ctx, b.build(), url);
        if (r.status() >= 400) {
            String err = str(r.json().get("error")), desc = str(r.json().get("error_description"));
            String msg = "Azure sign-in failed: " + (err == null ? "HTTP " + r.status() : err) + (desc == null ? "" : ": " + firstLine(desc));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", BigDecimal.valueOf(r.status()));
            if (err != null) details.put("azure_code", err);
            if (r.status() == 429 || r.status() >= 500)
                throw new ActionException(P + (r.status() == 429 ? ".RATE_LIMITED" : ".SERVER_BUSY"), msg, "Try again shortly.", true, details);
            throw new ActionException(P + ".AUTH_FAILED", msg, "Check the tenant id, client id and the client secret in the vault (it may have expired).", false, details);
        }
        if (!(r.json().get("access_token") instanceof String t) || t.isBlank())
            throw new ActionException(P + ".AUTH_FAILED", "The sign-in answer has no access token", "Check the sign-in address and the app registration.");
        long life = r.json().get("expires_in") instanceof Number n ? n.longValue() : r.json().get("expires_in") instanceof String sv && sv.matches("\\d+") ? Long.parseLong(sv) : 3600;
        s.token = t;
        s.expiresAtMillis = System.currentTimeMillis() + life * 1000;
        return t;
    }

    static String firstLine(String s) { int i = s.indexOf('\n'); String t = (i < 0 ? s : s.substring(0, i)).strip(); return t.length() > 300 ? t.substring(0, 300) : t; }

    /** Sends one Resource Manager request with the session's token: one fresh sign-in on 401, errors typed by status and Azure's code. */
    static Resp send(ActionContext ctx, AzureSession s, String method, String url, Object body) {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder b;
            try { b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)); }
            catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_URL", "Invalid URL: " + url, "Check the Resource Manager address."); }
            b.header("Authorization", "Bearer " + token(ctx, s, attempt > 0)).header("Accept", "application/json");
            if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JsonLite.write(Values.toJsonable(body)), StandardCharsets.UTF_8));
            else b.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("") : HttpRequest.BodyPublishers.noBody());
            ctx.info("Azure " + method + " " + url.replace(s.arm, ""));
            Resp r = exchange(ctx, b.build(), url);
            if (r.status() == 401 && attempt == 0) { ctx.warn("Azure refused the token; signing in again"); continue; }
            if (r.status() >= 400) throw error(r);
            return r;
        }
    }

    /** One HTTP exchange; HTTP 429 is sent again after the wait Azure asks for (up to a minute, three times). */
    static Resp exchange(ActionContext ctx, HttpRequest req, String url) {
        HttpResponse<byte[]> r;
        for (int attempt = 0; ; attempt++) {
            try { r = HttpJson.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray()); }
            catch (HttpTimeoutException e) { throw new ActionException(P + ".TIMEOUT", "No answer from " + HttpJson.host(url) + " within 60 s", "Try again later.", true); }
            catch (IOException e) { throw new ActionException(P + ".UNREACHABLE", "Cannot reach " + HttpJson.host(url) + ": " + NetErrors.reason(e, null), "Check the network, proxy and the Azure addresses.", true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted", "The run was stopped before this step finished. Run the agent again when it should go on."); }
            long wait = r.statusCode() == 429 && attempt < HttpJson.MAX_RATE_RETRIES ? HttpJson.retryAfterMillis(r.headers().firstValue("Retry-After").orElse(null), attempt) : -1;
            if (wait < 0) break;
            ctx.warn("Azure is limiting requests (HTTP 429); trying again in " + Math.max(1, Math.round(wait / 1000.0)) + " s");
            HttpJson.pause(ctx, wait);
        }
        String text = new String(r.body(), StandardCharsets.UTF_8);
        Object parsed = null;
        if (!text.isBlank()) try { parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
        if (parsed == null && HttpJson.looksLikePage(text, r.headers().firstValue("Content-Type").orElse(null)))
            throw new ActionException(P + ".BAD_RESPONSE", HttpJson.host(url) + " answered with a web page instead of JSON (often a proxy or sign-in page): " + HttpJson.snippet(text),
                    "Check the Azure addresses, proxy and network.", false, Map.of("status", BigDecimal.valueOf(r.statusCode())));
        @SuppressWarnings("unchecked") Map<String, Object> json = parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        return new Resp(r.statusCode(), json, r.headers());
    }

    static ActionException error(Resp r) {
        Object e = r.json().get("error");
        String code = e instanceof Map<?, ?> em ? str(em.get("code")) : null, msg = e instanceof Map<?, ?> em ? str(em.get("message")) : null;
        int st = r.status();
        boolean retry = st == 429 || st >= 500;
        String ours = st == 401 ? ".AUTH_FAILED" : st == 403 ? ".FORBIDDEN" : st == 404 ? ".NOT_FOUND" : st == 429 ? ".RATE_LIMITED"
                : st == 409 || "OperationNotAllowed".equals(code) || "Conflict".equals(code) ? ".WRONG_STATE" : retry ? ".SERVER_BUSY" : ".REQUEST_FAILED";
        String fix = switch (ours) {
            case ".AUTH_FAILED" -> "Check the service principal's tenant, client id and secret.";
            case ".FORBIDDEN" -> "Give the service principal a role on the subscription or resource group, e.g. Virtual Machine Contributor.";
            case ".NOT_FOUND" -> "Check the subscription, resource group and VM or disk name.";
            case ".RATE_LIMITED" -> "Azure is limiting requests; try again shortly.";
            case ".WRONG_STATE" -> "The virtual machine or disk cannot do this now; check it with Get VM status.";
            case ".SERVER_BUSY" -> "Azure is busy; try again shortly.";
            default -> null;
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", BigDecimal.valueOf(st));
        if (code != null) details.put("azure_code", code);
        return new ActionException(P + ours, "Azure " + (code == null ? "HTTP " + st : code) + ": " + (msg == null ? "no details" : msg), fix, retry, details);
    }

    static Map<String, Object> operation(ActionContext ctx, AzureSession s, String method, String url, boolean wait, int timeout) {
        Map<String, Object> r = operation(ctx, s, method, url, null, wait, timeout);
        r.remove("_id");
        return r;
    }

    /**
     * Starts a long-running operation and, when asked, follows it: Azure-AsyncOperation (a status document) first,
     * else Location (202 until done), else the resource's own provisioningState. Waits as long as Retry-After says.
     */
    static Map<String, Object> operation(ActionContext ctx, AzureSession s, String method, String url, Object body, boolean wait, int timeout) {
        Resp first = send(ctx, s, method, url, body);
        String id = str(first.json().get("id"));
        String async = first.header("Azure-AsyncOperation"), location = first.header("Location");
        String state = str(HttpJson.path(first.json(), "properties.provisioningState"));
        boolean pending = async != null || location != null || first.status() == 202 || state != null && !terminal(state);
        if (!wait || !pending) {
            if (state != null && failed(state)) throw new ActionException(P + ".OPERATION_FAILED", "Azure reports the operation " + state, "Check the activity log in the Azure portal.");
            return Out.of("status", pending ? "Accepted" : "Succeeded", "_id", id);
        }
        for (String poll : new String[]{async, location})
            if (poll != null && !HttpJson.sameOrigin(s.arm, poll))
                throw new ActionException(P + ".BAD_RESPONSE", "Azure asked to follow the operation on another server (" + HttpJson.host(poll) + ")", "Check the Resource Manager address.");
        long deadline = System.nanoTime() + Math.max(1, timeout) * 1_000_000_000L;
        Resp last = first;
        while (true) {
            if (System.nanoTime() > deadline)
                throw new ActionException(P + ".WAIT_TIMEOUT", "Azure had not finished after " + timeout + " s", "Raise timeout_seconds, or check the operation in the Azure portal.", false,
                        Map.of("operation_url", async != null ? async : location != null ? location : url));
            HttpJson.pause(ctx, pollMillis(last.header("Retry-After"), deadline));
            if (async != null) {
                last = send(ctx, s, "GET", async, null);
                String st = str(last.json().get("status"));
                if (st != null && terminal(st)) {
                    if (failed(st)) throw opFailed(st, last.json().get("error"));
                    break;
                }
            } else if (location != null) {
                last = send(ctx, s, "GET", location, null);
                if (last.status() != 202) break;
            } else {
                last = send(ctx, s, "GET", url, null);
                String st = str(HttpJson.path(last.json(), "properties.provisioningState"));
                if (st == null || terminal(st)) {
                    if (st != null && failed(st)) throw opFailed(st, null);
                    break;
                }
            }
        }
        return Out.of("status", "Succeeded", "_id", id);
    }

    static boolean terminal(String state) { return state.equalsIgnoreCase("Succeeded") || failed(state); }
    static boolean failed(String state) { return state.equalsIgnoreCase("Failed") || state.equalsIgnoreCase("Canceled") || state.equalsIgnoreCase("Cancelled"); }

    static ActionException opFailed(String state, Object error) {
        String code = error instanceof Map<?, ?> em ? str(em.get("code")) : null, msg = error instanceof Map<?, ?> em ? str(em.get("message")) : null;
        Map<String, Object> details = new LinkedHashMap<>();
        if (code != null) details.put("azure_code", code);
        return new ActionException(P + ".OPERATION_FAILED", "Azure reports the operation " + state + (msg == null ? "" : ": " + msg),
                "Check the activity log in the Azure portal.", false, details);
    }

    /** The wait Azure asks for between checks (Retry-After seconds), 10 s when it does not say, never past the deadline. */
    static long pollMillis(String retryAfter, long deadlineNanos) {
        long ms = 10_000;
        if (retryAfter != null && !retryAfter.isBlank()) try { ms = (long) (Double.parseDouble(retryAfter.strip()) * 1000); } catch (NumberFormatException ignored) { }
        ms = Math.min(Math.max(ms, 50), 60_000);
        long left = Math.max(50, (deadlineNanos - System.nanoTime()) / 1_000_000);
        return Math.min(ms, left);
    }
}
