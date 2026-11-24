"""Hand-written cases for the AWS EC2 commands (stand-in EC2 at $standin_url$/cloud-ec2, made-up keys from the vault)."""
from helpers_cloud import adapt_samples, value, cred

T = "ooa.aws_ec2/"
BOT = "i-0a1b2c3d4e5f60001"      # running, Name "UT invoice bot", Team Finance
REPORT = "i-0a1b2c3d4e5f60002"   # stopped at the start, Team Finance
VOL = "vol-0a1b2c3d4e5f60001"


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("ec2_count", "Number"), ("ec2_ids", "List"), ("ec2_table", "Table"), ("ec2_states", "Dictionary"),
                 ("ec2_changes", "Table"), ("ec2_mon", "Dictionary"), ("ec2_new", "List"), ("ec2_snap", "String"),
                 ("ec2_state", "String"), ("ec2_vol", "String"), ("ec2_size", "Number")):
        ua.var(n, t)

    with ua.case(T + "connect", "keys from the vault, checked straight away",
                 "Connect opens an EC2 session with access keys from the vault; check_now asks EC2 at once, so wrong keys "
                 "or region fail here. Checked: the session then lists the instances.") as f:
        f.act(T + "connect", desc="Connect to the stand-in EC2", region="eu-west-1", access_key_id=cred("aws/id"),
              secret_access_key=cred("aws/secret"), endpoint="$standin_url$/cloud-ec2", check_now=True)
        f.act(T + "describe_instances", desc="List the instances", out={"count": "ec2_count"})
        ua.check("ge", "$ec2_count$", 3, what="instances seen")

    with ua.case(T + "describe_instances", "filter by tag",
                 "Describe instances lists instances with state, type and tags; here only those tagged Team=HR. "
                 "Checked: one instance, the HR bot, with its type in the table.") as f:
        f.act(T + "describe_instances", desc="Instances of the HR team", filters={"tag:Team": "HR"},
              out={"count": "ec2_count", "instance_ids": "ec2_ids", "instances": "ec2_table"})
        ua.check_eq("ec2_count", 1)
        value(ua, "$ec2_ids[0]", "eq", "i-0a1b2c3d4e5f60003", "the instance id")
        value(ua, "$ec2_table.rows[0][3]", "eq", "m6i.large", "the instance type column")

    with ua.case(T + "get_instance_status", "state and health checks",
                 "Get instance status reads each instance's state and AWS's two health checks. Checked: the invoice bot "
                 "is running with system status ok.") as f:
        f.act(T + "start_instances", desc="Make sure the invoice bot runs", instance_ids=[BOT])
        f.act(T + "get_instance_status", desc="Read its status", instance_ids=[BOT], include_stopped=True,
              out={"statuses": "ec2_table", "states": "ec2_states"})
        value(ua, "$ec2_states['" + BOT + "']", "eq", "running", "the state")
        value(ua, "$ec2_table.rows[0][2]", "eq", "ok", "the system status")

    with ua.case(T + "stop_instances", "stop and wait until stopped",
                 "Stop instances stops running instances (disks are kept) and can wait until they are stopped. "
                 "Checked: the change table and the final state.") as f:
        f.act(T + "start_instances", desc="Start the report bot first", instance_ids=[REPORT])
        f.act(T + "stop_instances", desc="Stop it and wait", instance_ids=[REPORT], wait=True, timeout_seconds=60,
              out={"changes": "ec2_changes", "states": "ec2_states"})
        value(ua, "$ec2_changes.rows[0][1]", "eq", "running", "the state before")
        value(ua, "$ec2_states['" + REPORT + "']", "eq", "stopped", "the state at the end")

    with ua.case(T + "start_instances", "start by Name tag",
                 "Start instances starts stopped instances; instances can be named by their Name tag instead of the id. "
                 "Checked: the report bot went from stopped to running.") as f:
        f.act(T + "stop_instances", desc="Make sure the report bot is stopped", instance_ids=[REPORT])
        f.act(T + "start_instances", desc="Start the report bot by its name", instance_ids=["UT report bot"],
              out={"changes": "ec2_changes"})
        value(ua, "$ec2_changes.rows[0][0]", "eq", REPORT, "the instance started")
        value(ua, "$ec2_changes.rows[0][2]", "eq", "running", "the new state")

    with ua.case(T + "reboot_instances", "restart a running instance",
                 "Reboot instances restarts running instances and gives nothing back. Checked: the instance is still running.") as f:
        f.act(T + "reboot_instances", desc="Reboot the invoice bot", instance_ids=[BOT])
        f.act(T + "get_instance_status", desc="Read its state", instance_ids=[BOT], out={"states": "ec2_states"})
        value(ua, "$ec2_states['" + BOT + "']", "eq", "running", "the state after the reboot")

    with ua.case(T + "wait_for_state", "wait until running",
                 "Wait for state checks the instances until all of them are in the state asked for. Checked: the "
                 "states it gives back.") as f:
        f.act(T + "wait_for_state", desc="Wait until the invoice bot runs", instance_ids=[BOT], state="running",
              timeout_seconds=30, check_every_seconds=1, out={"states": "ec2_states"})
        value(ua, "$ec2_states['" + BOT + "']", "eq", "running", "the state")

    with ua.case(T + "enable_monitoring", "detailed monitoring on",
                 "Enable monitoring switches on one-minute CloudWatch monitoring. Checked: EC2 reports it pending.") as f:
        f.act(T + "enable_monitoring", desc="Monitoring on for the invoice bot", instance_ids=[BOT], out={"monitoring": "ec2_mon"})
        value(ua, "$ec2_mon['" + BOT + "']", "eq", "pending", "the monitoring state")

    with ua.case(T + "disable_monitoring", "back to basic monitoring",
                 "Disable monitoring goes back to five-minute monitoring. Checked: EC2 reports it disabling.") as f:
        f.act(T + "disable_monitoring", desc="Monitoring off for the invoice bot", instance_ids=[BOT], out={"monitoring": "ec2_mon"})
        value(ua, "$ec2_mon['" + BOT + "']", "eq", "disabling", "the monitoring state")

    with ua.case(T + "set_tags", "add a tag",
                 "Set tags adds tags to instances or changes their values. Checked: Describe instances finds the "
                 "invoice bot by the new tag.") as f:
        f.act(T + "set_tags", desc="Tag the invoice bot", instance_ids=[BOT], tags={"CostCenter": "4711"})
        f.act(T + "describe_instances", desc="Find it by the tag", filters={"tag:CostCenter": "4711"}, out={"instance_ids": "ec2_ids"})
        ua.check("contains", "$ec2_ids$", BOT, what="the tagged instance")

    with ua.case(T + "launch_instances", "one new instance with a name",
                 "Launch instances creates instances from a machine image with a name, tags and a start-up script. "
                 "Checked: one new id and its Name in the table.") as f:
        f.act(T + "launch_instances", desc="Launch one instance", image_id="ami-0abc12345678def01", instance_type="t3.micro",
              count=1, name="UT launched bot", tags={"Team": "Test"}, user_data="echo hello",
              out={"instance_ids": "ec2_new", "instances": "ec2_table"})
        value(ua, "len($ec2_new)", "eq", 1, "new instances")
        value(ua, "$ec2_table.rows[0][1]", "eq", "UT launched bot", "the Name column")

    with ua.case(T + "terminate_instances", "end a launched instance",
                 "Terminate instances ends instances for good. A fresh instance is launched first, then ended and "
                 "waited for. Checked: its state is terminated.") as f:
        f.act(T + "launch_instances", desc="Launch an instance to end", image_id="ami-0abc12345678def01",
              instance_type="t3.micro", name="UT short-lived bot", out={"instance_ids": "ec2_new"})
        f.act(T + "terminate_instances", desc="End it and wait", instance_ids="$ec2_new$", wait=True, timeout_seconds=60,
              out={"changes": "ec2_changes", "states": "ec2_states"})
        value(ua, "$ec2_changes.rows[0][2]", "eq", "terminated", "the new state")

    with ua.case(T + "create_snapshot", "copy of a volume",
                 "Create snapshot starts a point-in-time copy of a volume. Checked: a snap- id and state pending; the "
                 "copy is deleted again at the end.") as f:
        f.act(T + "create_snapshot", desc="Snapshot the invoice bot's disk", volume_id=VOL, description="UT before patching",
              tags={"Team": "Finance"}, out={"snapshot_id": "ec2_snap", "state": "ec2_state"})
        ua.check("startsWith", "$ec2_snap$", "snap-", what="snapshot id")
        ua.check_eq("ec2_state", "pending")
        f.act(T + "delete_snapshot", desc="Tidy up", snapshot_id="$ec2_snap$")

    with ua.case(T + "list_snapshots", "snapshots of one volume",
                 "List snapshots lists this account's snapshots, here only those of one volume with a tag. Checked: the "
                 "nightly copy is found.") as f:
        f.act(T + "list_snapshots", desc="Snapshots of the volume", volume_id=VOL, tags={"Team": "Finance"}, owner="self",
              out={"snapshot_ids": "ec2_ids", "count": "ec2_count", "snapshots": "ec2_table"})
        ua.check("contains", "$ec2_ids$", "snap-0a1b2c3d4e5f60001", what="the nightly snapshot")
        ua.check("ge", "$ec2_count$", 1, what="snapshot count")

    with ua.case(T + "delete_snapshot", "delete a new snapshot",
                 "Delete snapshot removes a snapshot for good. A snapshot is made first. Checked: List snapshots no "
                 "longer has it.") as f:
        f.act(T + "create_snapshot", desc="Make a snapshot to delete", volume_id=VOL, out={"snapshot_id": "ec2_snap"})
        f.act(T + "delete_snapshot", desc="Delete it", snapshot_id="$ec2_snap$")
        f.act(T + "list_snapshots", desc="List again", volume_id=VOL, out={"snapshot_ids": "ec2_ids"})
        ua.check("notContains", "$ec2_ids$", "$ec2_snap$", what="the deleted snapshot")

    with ua.case(T + "create_volume", "empty 20 GiB volume",
                 "Create volume makes an EBS volume in a zone, empty or from a snapshot. Checked: a vol- id, state "
                 "creating and the size.") as f:
        f.act(T + "create_volume", desc="Create a volume", availability_zone="eu-west-1a", size_gib=20, volume_type="gp3",
              tags={"Name": "UT data"}, out={"volume_id": "ec2_vol", "state": "ec2_state", "size_gib": "ec2_size"})
        ua.check("startsWith", "$ec2_vol$", "vol-", what="volume id")
        ua.check_eq("ec2_state", "creating")
        ua.check_eq("ec2_size", 20)

    with ua.case(T + "attach_volume", "attach a new volume",
                 "Attach volume attaches an available volume to an instance in the same zone. Checked: state attaching.") as f:
        f.act(T + "create_volume", desc="A new volume", availability_zone="eu-west-1a", size_gib=5, out={"volume_id": "ec2_vol"})
        f.act(T + "attach_volume", desc="Attach it", volume_id="$ec2_vol$", instance_id=BOT, device="/dev/sdf",
              out={"state": "ec2_state"})
        ua.check_eq("ec2_state", "attaching")
        f.act(T + "detach_volume", desc="Tidy up", volume_id="$ec2_vol$")

    with ua.case(T + "detach_volume", "detach an attached volume",
                 "Detach volume detaches a volume from its instance. Checked: state detaching.") as f:
        f.act(T + "create_volume", desc="A new volume", availability_zone="eu-west-1a", size_gib=5, out={"volume_id": "ec2_vol"})
        f.act(T + "attach_volume", desc="Attach it", volume_id="$ec2_vol$", instance_id=BOT, device="/dev/sdg")
        f.act(T + "detach_volume", desc="Detach it", volume_id="$ec2_vol$", force=False, out={"state": "ec2_state"})
        ua.check_eq("ec2_state", "detaching")

    with ua.case(T + "disconnect", "close the session",
                 "Disconnect closes the EC2 session and forgets its keys. Checked: a step after it finds no session.") as f:
        f.act(T + "disconnect", desc="Close the session")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act(T + "describe_instances", desc="This needs the closed session"))
