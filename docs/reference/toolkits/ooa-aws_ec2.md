# AWS EC2

Toolkit `ooa.aws_ec2`, version 1.0.0. Start, stop, reboot, launch and end AWS EC2 virtual machines, read their state and tags, and switch detailed monitoring on or off.

[All toolkits](README.md)

## Contents

- **Connection**: Connect, Disconnect
- **Instances**: Describe instances, Get instance status, Launch instances, Reboot instances, Set tags, Start instances, Stop instances, Terminate instances, Wait for state
- **Monitoring**: Disable monitoring, Enable monitoring

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
| Instance IDs | `instance_ids` | List | No |  | Main; choice Instances = These ones | Instance ids, e.g. ["i-0abc1234def567890"]. Leave empty for all. |
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
| Instance IDs | `instance_ids` | List | No |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. Leave empty for all. |
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
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |

*Time limit 60 s; needs: network.*

### Set tags

`ooa.aws_ec2/set_tags` (action)

Add tags to instances, or change the values of tags they already have.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |
| Tags | `tags` | Dictionary | Yes |  | Main | Tag name to value, e.g. {"Name": "Invoice bot", "Team": "Finance"}. |

*Time limit 60 s; needs: network.*

### Start instances

`ooa.aws_ec2/start_instances` (action)

Start stopped instances.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changes` | Table | Columns: instance_id, previous_state, current_state. |

*Time limit 60 s; needs: network.*

### Stop instances

`ooa.aws_ec2/stop_instances` (action)

Stop running instances; their disks are kept. Optionally hibernate them or force the stop.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |
| Hibernate | `hibernate` | Yes/No | No | No | More options | Hibernate instead of stopping (the instance must be set up for it). |
| Force | `force` | Yes/No | No | No | More options | Force the stop without a clean shutdown, for an instance that hangs. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changes` | Table | Columns: instance_id, previous_state, current_state. |

*Time limit 60 s; needs: network.*

### Terminate instances

`ooa.aws_ec2/terminate_instances` (action)

End instances for good; disks marked to be deleted on end are deleted too. This cannot be undone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changes` | Table | Columns: instance_id, previous_state, current_state. |

*Time limit 60 s; needs: network.*

### Wait for state

`ooa.aws_ec2/wait_for_state` (action)

Wait until every instance named is in a state, e.g. running after a start or stopped after a stop.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |
| State | `state` | Text | No | `running` | Main | The state to wait for. One of: `running`, `stopped`, `terminated`, `pending`, `stopping`, `shutting-down` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `600` | More options | Stop waiting after this many seconds and fail. |
| Check every (seconds) | `check_every_seconds` | Number | No | `10` | More options | Seconds between checks. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `states` | Dictionary | Instance id to the state it was in at the end. |

*Time limit 60 s; safe to repeat; needs: network.*

## Monitoring

### Disable monitoring

`ooa.aws_ec2/disable_monitoring` (action)

Go back to basic (five-minute) monitoring for instances.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ec2` | Main | Name of the session opened by Connect. |
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |

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
| Instance IDs | `instance_ids` | List | Yes |  | Main | Instance ids, e.g. ["i-0abc1234def567890"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `monitoring` | Dictionary | Instance id to monitoring state, e.g. pending or enabled. |

*Time limit 60 s; needs: network.*
