# AWS EC2

Toolkit `ooa.aws_ec2`, version 1.0.0. Start, stop, reboot, launch and end AWS EC2 virtual machines, read their state and tags, switch detailed monitoring on or off, and snapshot, create, attach and detach their EBS volumes.

[All toolkits](README.md)

## Contents

- **Snapshots and volumes**: Attach volume, Create snapshot, Create volume, Delete snapshot, Detach volume, List snapshots
- **Connection**: Connect, Disconnect
- **Instances**: Describe instances, Get instance status, Launch instances, Reboot instances, Set tags, Start instances, Stop instances, Terminate instances, Wait for state
- **Monitoring**: Disable monitoring, Enable monitoring

## Snapshots and volumes

### Attach volume

`ooa.aws_ec2/attach_volume` (action)

Attach an available volume to an instance in the same availability zone, as a device name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Volume ID | `volume_id` | Text | Yes |  | Main | Id of the volume to attach. Example: `vol-0abc1234def567890` |
| Instance ID | `instance_id` | Text | Yes |  | Main | Id of the instance to attach it to. Example: `i-0abc1234def567890` |
| Device | `device` | Text | Yes |  | Main | Device name the instance sees, e.g. /dev/sdf (Linux) or xvdf (Windows). Example: `/dev/sdf` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `state` | Text | Attachment state, usually attaching. |

*Time limit 60 s; needs: network.*

### Create snapshot

`ooa.aws_ec2/create_snapshot` (action)

Start a point-in-time copy (snapshot) of an EBS volume; AWS finishes it in the background.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Volume ID | `volume_id` | Text | Yes |  | Main | Id of the volume to copy. Example: `vol-0abc1234def567890` |
| Description | `description` | Text | No |  | Main | Text shown with the snapshot in the AWS console. |
| Tags | `tags` | Dictionary | No |  | More options | Tags for the snapshot, e.g. {"Name": "Before patching", "Team": "Finance"}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `snapshot_id` | Text | Id of the new snapshot, e.g. snap-0abc1234def567890. |
| `state` | Text | Snapshot state: pending until AWS has finished the copy, then completed. |
| `start_time` | Text | When the snapshot was started (UTC, ISO 8601). |

*Time limit 60 s; needs: network.*

### Create volume

`ooa.aws_ec2/create_volume` (action)

Create an EBS volume in an availability zone, empty or restored from a snapshot.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Availability zone | `availability_zone` | Text | Yes |  | Main | Availability zone for the volume; it can only be attached to instances in the same zone. Example: `eu-west-1a` |
| Size gib | `size_gib` | Number | No | `0` | Main | Size in GiB (1-65536). 0 takes the snapshot's size when a snapshot is given. |
| Volume type | `volume_type` | Text | No | `gp3` | Main | Volume type: gp3 or gp2 general purpose SSD, io1 or io2 provisioned-IOPS SSD, st1 or sc1 hard disk, standard magnetic. One of: `gp3`, `gp2`, `io1`, `io2`, `st1`, `sc1`, `standard` |
| Snapshot ID | `snapshot_id` | Text | No |  | Main | Snapshot to restore the volume from. Leave empty for an empty volume. |
| Tags | `tags` | Dictionary | No |  | More options | Tags for the volume, e.g. {"Name": "Invoice data"}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `volume_id` | Text | Id of the new volume, e.g. vol-0abc1234def567890. |
| `state` | Text | Volume state: creating, then available. |
| `size_gib` | Number | Size of the volume in GiB. |

*Time limit 60 s; needs: network.*

### Delete snapshot

`ooa.aws_ec2/delete_snapshot` (action)

Delete a snapshot for good. AWS refuses while a machine image (AMI) still uses it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Snapshot ID | `snapshot_id` | Text | Yes |  | Main | Id of the snapshot to delete. Example: `snap-0abc1234def567890` |

*Time limit 60 s; needs: network.*

### Detach volume

`ooa.aws_ec2/detach_volume` (action)

Detach a volume from its instance. Unmount it on the instance first; force only for a volume that will not detach.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Volume ID | `volume_id` | Text | Yes |  | Main | Id of the volume to detach. Example: `vol-0abc1234def567890` |
| Force | `force` | Yes/No | No | No | More options | Force the detach; data not yet written can be lost, so use it only when a normal detach hangs. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `state` | Text | Attachment state, usually detaching. |

*Time limit 60 s; needs: network.*

### List snapshots

`ooa.aws_ec2/list_snapshots` (action)

List snapshots owned by this account (or another owner), optionally only those of one volume or with certain tags.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Volume ID | `volume_id` | Text | No |  | Main | Only snapshots of this volume. Leave empty for all. |
| Tags | `tags` | Dictionary | No |  | Main | Only snapshots with these tag values, e.g. {"Team": "Finance"}. |
| Owner | `owner` | Text | No | `self` | More options | Whose snapshots: self (this account), amazon, or an AWS account id. |
| Filters | `filters` | Dictionary | No |  | More options | More EC2 filters, name to value or list of values, e.g. {"status": "completed"}. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Stop after this many snapshots. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `snapshots` | Table | Columns: snapshot_id, volume_id, state, progress, start_time, volume_size_gib, description, encrypted, owner_id, tags. |
| `snapshot_ids` | List | The ids of the snapshots found. |
| `count` | Number | How many snapshots were found. |

*Time limit 60 s; safe to repeat; needs: network.*

## Connection

### Connect

`ooa.aws_ec2/connect` (action)

Open a session for one AWS region with an access key from the vault; later EC2 steps use it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Session name. |
| Region | `region` | Text | Yes |  | Main | AWS region of the instances, e.g. us-east-1, eu-west-1 or ap-south-1. Example: `eu-west-1` |
| Access key ID | `access_key_id` | Credential (vault) | Yes |  | Main | Access key id from the vault. |
| Secret access key | `secret_access_key` | Credential (vault) | Yes |  | Main | Secret access key from the vault. |
| Session token | `session_token` | Credential (vault) | No |  | More options | Session token from the vault, for temporary keys (assumed roles, single sign-on). |
| Endpoint | `endpoint` | Text | No |  | More options | Another EC2 address, e.g. a private VPC endpoint. Leave empty for AWS's address for the region. |
| Check now | `check_now` | Yes/No | No | Yes | More options | Check the keys and region with AWS straight away. |

*Time limit 60 s; needs: network.*

### Disconnect

`ooa.aws_ec2/disconnect` (action)

Close the EC2 session and forget its keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Session name. |

*Time limit 60 s.*

## Instances

### Describe instances

`ooa.aws_ec2/describe_instances` (action)

List instances with their state, type, addresses and tags: all of them, the ones you name, or those that match filters.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | No |  | Main; choice Instances = These ones | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. Leave empty for all. |
| Filters | `filters` | Dictionary | No |  | Main | EC2 filters, name to value or list of values, e.g. {"instance-state-name": "running", "tag:Team": ["Finance", "HR"]}. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Stop after this many instances. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `instances` | Table | Columns: instance_id, name, state, instance_type, image_id, private_ip, public_ip, availability_zone, launch_time, monitoring, tags. |
| `instance_ids` | List | The ids of the instances found. |
| `count` | Number | How many instances were found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get instance status

`ooa.aws_ec2/get_instance_status` (action)

Read each instance's state and the results of AWS's system and instance health checks.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | No |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. Leave empty for all. |
| Include stopped | `include_stopped` | Yes/No | No | Yes | Main | Also list instances that are not running (AWS leaves them out otherwise). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `statuses` | Table | Columns: instance_id, state, system_status, instance_status, availability_zone. |
| `states` | Dictionary | Instance id to state, e.g. {"i-0abc1234def567890": "running"}. |

*Time limit 60 s; safe to repeat; needs: network.*

### Launch instances

`ooa.aws_ec2/launch_instances` (action)

Create new instances from a machine image, with a name, tags and a start-up script.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Image ID | `image_id` | Text | Yes |  | Main | Machine image (AMI) id. Example: `ami-0abcdef1234567890` |
| Instance type | `instance_type` | Text | Yes |  | Main | Instance type, e.g. t3.micro or m6i.large. Example: `t3.micro` |
| Count | `count` | Number | No | `1` | Main | How many instances to create (1-100). |
| Name | `name` | Text | No |  | Main | Name shown in the AWS console (the Name tag). |
| Key pair | `key_pair` | Text | No |  | Main | Name of the key pair for signing in to the instance. |
| Subnet ID | `subnet_id` | Text | No |  | Main | Subnet to start the instances in. Leave empty for the default. |
| Security group IDs | `security_group_ids` | List | No |  | Main | Security group ids. Leave empty for the default group. |
| Tags | `tags` | Dictionary | No |  | More options | More tags, e.g. {"Team": "Finance", "CostCenter": "4711"}. |
| User data | `user_data` | Text | No |  | More options | Start-up script or cloud-init text, run when the instance first starts. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `instance_ids` | List | The ids of the new instances. |
| `instances` | Table | The new instances, with the same columns as Describe instances. |

*Time limit 60 s; needs: network.*

### Reboot instances

`ooa.aws_ec2/reboot_instances` (action)

Restart running instances.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |

*Time limit 60 s; needs: network.*

### Set tags

`ooa.aws_ec2/set_tags` (action)

Add tags to instances, or change the values of tags they already have.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |
| Tags | `tags` | Dictionary | Yes |  | Main | Tag name to value, e.g. {"Name": "Invoice bot", "Team": "Finance"}. |

*Time limit 60 s; needs: network.*

### Start instances

`ooa.aws_ec2/start_instances` (action)

Start stopped instances; optionally wait until they are running.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |
| Wait | `wait` | Yes/No | No | No | Main; tick Wait until running | Wait until every instance is running before going on. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main; tick Wait until running | Stop waiting after this many seconds and fail with WAIT_TIMEOUT. Range: 1 to 3600; Shown when: `{"wait": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changes` | Table | Columns: instance_id, previous_state, current_state. |
| `states` | Dictionary | Instance id to its state at the end, when waiting. |

*Time limit 3700 s; needs: network.*

### Stop instances

`ooa.aws_ec2/stop_instances` (action)

Stop running instances; their disks are kept. Optionally hibernate them or force the stop, and wait until they are stopped.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |
| Wait | `wait` | Yes/No | No | No | Main; tick Wait until stopped | Wait until every instance is stopped before going on. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main; tick Wait until stopped | Stop waiting after this many seconds and fail with WAIT_TIMEOUT. Range: 1 to 3600; Shown when: `{"wait": ["true"]}` |
| Hibernate | `hibernate` | Yes/No | No | No | More options | Hibernate instead of stopping (the instance must be set up for it). |
| Force | `force` | Yes/No | No | No | More options | Force the stop without a clean shutdown, for an instance that hangs. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changes` | Table | Columns: instance_id, previous_state, current_state. |
| `states` | Dictionary | Instance id to its state at the end, when waiting. |

*Time limit 3700 s; needs: network.*

### Terminate instances

`ooa.aws_ec2/terminate_instances` (action)

End instances for good; disks marked to be deleted on end are deleted too. This cannot be undone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |
| Wait | `wait` | Yes/No | No | No | Main; tick Wait until terminated | Wait until every instance is terminated before going on. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | Main; tick Wait until terminated | Stop waiting after this many seconds and fail with WAIT_TIMEOUT. Range: 1 to 3600; Shown when: `{"wait": ["true"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changes` | Table | Columns: instance_id, previous_state, current_state. |
| `states` | Dictionary | Instance id to its state at the end, when waiting. |

*Time limit 3700 s; needs: network.*

### Wait for state

`ooa.aws_ec2/wait_for_state` (action)

Wait until every instance named is in a state, e.g. running after a start or stopped after a stop.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |
| State | `state` | Text | No | `running` | Main | The state to wait for. One of: `running`, `stopped`, `terminated`, `pending`, `stopping`, `shutting-down` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | More options | Stop waiting after this many seconds and fail with WAIT_TIMEOUT. Range: 1 to 3600 |
| Check every (seconds) | `check_every_seconds` | Number | No | `10` | More options | Seconds between checks. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `states` | Dictionary | Instance id to the state it was in at the end. |

*Time limit 3700 s; safe to repeat; needs: network.*

## Monitoring

### Disable monitoring

`ooa.aws_ec2/disable_monitoring` (action)

Go back to basic (five-minute) monitoring for instances.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `monitoring` | Dictionary | Instance id to monitoring state, e.g. disabling or disabled. |

*Time limit 60 s; needs: network.*

### Enable monitoring

`ooa.aws_ec2/enable_monitoring` (action)

Switch on detailed (one-minute) CloudWatch monitoring for instances.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instances by id or by Name tag, e.g. ["i-0abc1234def567890"] or ["Invoice bot"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `monitoring` | Dictionary | Instance id to monitoring state, e.g. pending or enabled. |

*Time limit 60 s; needs: network.*
