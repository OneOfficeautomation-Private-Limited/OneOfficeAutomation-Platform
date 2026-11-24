"""Hand-written cases for the Azure VMs commands (stand-in sign-in and Resource Manager at $standin_url$/cloud-azure)."""
import json

from helpers_cloud import adapt_samples, value, cred

T = "ooa.azure_vm/"
TENANT = "00000000-0000-0000-0000-0000000000a1"
CLIENT = "00000000-0000-0000-0000-000000000001"
SUB = "00000000-0000-0000-0000-000000000002"
RG = "rg-ut-bots"


def _stop_mode_sample(ua):
    """The sample with an unknown stop mode is refused when the whole flow is checked, which would stop this agent from
    running at all; here the mode comes from a variable, so the step itself refuses it when it runs."""
    ua.var("azure_bad_mode", "String", "hibernate")
    for step in ua.f.steps:
        if step.get("action") == "core/step" and "unknown stop mode" in step["args"].get("title", ""):
            text = json.dumps(step, ensure_ascii=False)
            text = text.replace('"mode": "hibernate"', '"mode": "$azure_bad_mode$"').replace("FLOW.INVALID", "AZURE_VM.BAD_MODE")
            text = text.replace("is refused when the flow is checked", "is refused when the step runs")
            step.clear()
            step.update(json.loads(text))


def add(ua):
    adapt_samples(ua, ua.f.name)
    _stop_mode_sample(ua)
    for n, t in (("az_count", "Number"), ("az_names", "List"), ("az_vms", "Table"), ("az_power", "String"),
                 ("az_display", "String"), ("az_status", "String"), ("az_snap", "String"), ("az_disk", "String")):
        ua.var(n, t)

    with ua.case(T + "connect", "service principal, checked straight away",
                 "Connect signs in with a service principal (client secret from the vault) and keeps the token in a "
                 "session; check_now reads the subscription at once. Checked: the session then lists the VMs.") as f:
        f.act(T + "connect", desc="Connect to the stand-in Azure", tenant_id=TENANT, client_id=CLIENT,
              client_secret=cred("azure/secret"), subscription_id=SUB, arm_endpoint="$standin_url$/cloud-azure/arm",
              login_endpoint="$standin_url$/cloud-azure/login", check_now=True)
        f.act(T + "list_vms", desc="List every VM", out={"count": "az_count"})
        ua.check_eq("az_count", 3)

    with ua.case(T + "list_vms", "one resource group",
                 "List VMs lists the virtual machines of the subscription or one resource group with size and tags. "
                 "Checked: the two bots of rg-ut-bots and the size column.") as f:
        f.act(T + "list_vms", desc="VMs of one group", resource_group=RG, max_results=10,
              out={"vms": "az_vms", "names": "az_names", "count": "az_count"})
        ua.check_eq("az_count", 2)
        ua.check("contains", "$az_names$", "ut-invoice-vm", what="the invoice VM")
        value(ua, "$az_vms.rows[0][3]", "eq", "Standard_B2s", "the size column")

    with ua.case(T + "get_vm_status", "power state of a running VM",
                 "Get VM status reads a VM's power and provisioning state from its instance view. Checked: running, "
                 "VM running and succeeded.") as f:
        f.act(T + "start_vm", desc="Make sure it runs", resource_group=RG, vm_name="ut-invoice-vm", wait=False)
        f.act(T + "get_vm_status", desc="Read the status", resource_group=RG, vm_name="ut-invoice-vm",
              out={"power_state": "az_power", "display_status": "az_display", "provisioning_state": "az_status"})
        ua.check_eq("az_power", "running")
        ua.check_eq("az_display", "VM running")
        ua.check_eq("az_status", "succeeded")

    with ua.case(T + "start_vm", "start and wait",
                 "Start VM starts a stopped or deallocated VM and waits for Azure to finish. Checked: Succeeded, and "
                 "the power state is running.") as f:
        f.act(T + "stop_vm", desc="Deallocate the report VM first", resource_group=RG, vm_name="ut-report-vm", wait=False)
        f.act(T + "start_vm", desc="Start it and wait", resource_group=RG, vm_name="ut-report-vm", wait=True,
              timeout_seconds=60, out={"status": "az_status"})
        ua.check_eq("az_status", "Succeeded")
        f.act(T + "get_vm_status", desc="Read the power state", resource_group=RG, vm_name="ut-report-vm", out={"power_state": "az_power"})
        ua.check_eq("az_power", "running")

    with ua.case(T + "stop_vm", "power off without waiting",
                 "Stop VM deallocates a VM or only powers it off; without waiting it gives Accepted. Checked: Accepted "
                 "and the power state stopped.") as f:
        f.act(T + "stop_vm", desc="Power off the report VM", resource_group=RG, vm_name="ut-report-vm", mode="power_off",
              skip_shutdown=True, wait=False, out={"status": "az_status"})
        ua.check_eq("az_status", "Accepted")
        f.act(T + "get_vm_status", desc="Read the power state", resource_group=RG, vm_name="ut-report-vm", out={"power_state": "az_power"})
        ua.check_eq("az_power", "stopped")

    with ua.case(T + "restart_vm", "restart and wait",
                 "Restart VM restarts a running VM and waits for Azure to finish. Checked: Succeeded.") as f:
        f.act(T + "restart_vm", desc="Restart the HR VM", resource_group="rg-ut-hr", vm_name="ut-hr-vm", wait=True,
              timeout_seconds=60, out={"status": "az_status"})
        ua.check_eq("az_status", "Succeeded")

    with ua.case(T + "create_disk_snapshot", "OS disk of a VM",
                 "Create disk snapshot copies a managed disk: here the OS disk of a VM, found from the VM. Checked: the "
                 "snapshot id, the disk id and Succeeded.") as f:
        f.act(T + "create_disk_snapshot", desc="Snapshot the invoice VM's OS disk", resource_group=RG, vm_name="ut-invoice-vm",
              snapshot_name="ut-invoice-os-before-patch", incremental=True, tags={"Reason": "Before patching"}, wait=True,
              out={"snapshot_id": "az_snap", "disk_id": "az_disk", "status": "az_status"})
        ua.check("endsWith", "$az_snap$", "/snapshots/ut-invoice-os-before-patch", what="snapshot id")
        ua.check("endsWith", "$az_disk$", "/disks/ut-invoice-vm_OsDisk", what="disk id")
        ua.check_eq("az_status", "Succeeded")

    with ua.case(T + "disconnect", "close the session",
                 "Disconnect closes the Azure session and forgets its token and secret. Checked: a step after it finds no session.") as f:
        f.act(T + "disconnect", desc="Close the session")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act(T + "list_vms", desc="This needs the closed session"))
