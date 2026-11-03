# Azure VMs

Toolkit `ooa.azure_vm`, version 1.0.0. List Azure virtual machines, read their power state, start, stop (deallocate or power off) and restart them, and snapshot their disks.

[All toolkits](README.md)

## Contents

- **Connection**: Connect, Disconnect
- **Disks**: Create disk snapshot
- **Virtual machines**: Get VM status, List VMs, Restart VM, Start VM, Stop VM

## Connection

### Connect

`ooa.azure_vm/connect` (action)

Sign in to Azure with a service principal (app registration) from the vault for one subscription; later Azure VM steps use it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Session name. |
| Tenant ID | `tenant_id` | Text | Yes |  | Main | Directory (tenant) id, or the tenant's domain name. Example: `contoso.onmicrosoft.com` |
| Client ID | `client_id` | Text | Yes |  | Main | Application (client) id of the app registration. Example: `00000000-0000-0000-0000-000000000000` |
| Client secret | `client_secret` | Credential (vault) | Yes |  | Main | Client secret of the app registration, from the vault. |
| Subscription ID | `subscription_id` | Text | Yes |  | Main | Id of the subscription that holds the virtual machines. Example: `00000000-0000-0000-0000-000000000000` |
| Cloud | `cloud` | Text | No | `public` | More options | Azure cloud: public (global Azure), us_government or china. One of: `public`, `us_government`, `china` |
| Arm endpoint | `arm_endpoint` | Text | No |  | More options | Another Resource Manager address, e.g. a private link. Leave empty for the cloud's address. |
| Login endpoint | `login_endpoint` | Text | No |  | More options | Another sign-in address. Leave empty for the cloud's Microsoft identity platform address. |
| Check now | `check_now` | Yes/No | No | Yes | More options | Sign in and read the subscription straight away. |

*Time limit 60 s; needs: network.*

### Disconnect

`ooa.azure_vm/disconnect` (action)

Close the Azure session and forget its token and secret.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Session name. |

*Time limit 60 s.*

## Disks

### Create disk snapshot

`ooa.azure_vm/create_disk_snapshot` (action)

Snapshot a managed disk: a virtual machine's OS disk, or a disk named directly.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Name of the session opened by Connect. |
| Resource group | `resource_group` | Text | Yes |  | Main | Resource group of the virtual machine or disk. |
| VM name | `vm_name` | Text | No |  | Main; choice Disk = OS disk of a VM | Virtual machine whose OS disk to copy. |
| Disk name | `disk_name` | Text | No |  | Main; choice Disk = Named disk | Name of the managed disk to copy, e.g. a data disk. |
| Snapshot name | `snapshot_name` | Text | Yes |  | Main | Name for the new snapshot. Example: `invoice-vm-os-2026-11-03` |
| Incremental | `incremental` | Yes/No | No | No | More options | Store only the changes since the disk's last incremental snapshot (cheaper); otherwise a full copy. |
| Snapshot resource group | `snapshot_resource_group` | Text | No |  | More options | Resource group for the snapshot. Leave empty for the disk's group. |
| Tags | `tags` | Dictionary | No |  | More options | Tags for the snapshot, e.g. {"Reason": "Before patching"}. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until Azure has finished; otherwise the step ends as soon as Azure has accepted the request. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `1800` | More options | Stop waiting after this many seconds and fail. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `snapshot_id` | Text | Resource id of the new snapshot. |
| `disk_id` | Text | Resource id of the disk that was copied. |
| `status` | Text | Succeeded when waited for, otherwise Accepted. |

*Time limit 3600 s; needs: network.*

## Virtual machines

### Get VM status

`ooa.azure_vm/get_vm_status` (action)

Read a virtual machine's power state (running, stopped, deallocated and so on) and provisioning state from its instance view.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Name of the session opened by Connect. |
| Resource group | `resource_group` | Text | Yes |  | Main | Resource group of the virtual machine. |
| VM name | `vm_name` | Text | Yes |  | Main | Name of the virtual machine. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `power_state` | Text | Power state: starting, running, stopping, stopped, deallocating, deallocated or unknown. |
| `provisioning_state` | Text | Provisioning state, e.g. succeeded, updating or failed. |
| `display_status` | Text | Azure's text for the power state, e.g. VM running. |
| `statuses` | Table | Columns: code, level, display_status, time. |

*Time limit 60 s; safe to repeat; needs: network.*

### List VMs

`ooa.azure_vm/list_vms` (action)

List the virtual machines of the subscription, or of one resource group, with their size, location and tags.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Name of the session opened by Connect. |
| Resource group | `resource_group` | Text | No |  | Main | Only this resource group. Leave empty for the whole subscription. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Stop after this many virtual machines. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `vms` | Table | Columns: name, resource_group, location, vm_size, os_type, provisioning_state, vm_id, id, tags. |
| `names` | List | The names of the virtual machines found. |
| `count` | Number | How many virtual machines were found. |

*Time limit 300 s; safe to repeat; needs: network.*

### Restart VM

`ooa.azure_vm/restart_vm` (action)

Restart a running virtual machine.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Name of the session opened by Connect. |
| Resource group | `resource_group` | Text | Yes |  | Main | Resource group of the virtual machine. |
| VM name | `vm_name` | Text | Yes |  | Main | Name of the virtual machine. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until Azure has finished; otherwise the step ends as soon as Azure has accepted the request. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `900` | More options | Stop waiting after this many seconds and fail. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | Succeeded when waited for, otherwise Accepted. |

*Time limit 3600 s; needs: network.*

### Start VM

`ooa.azure_vm/start_vm` (action)

Start a stopped or deallocated virtual machine.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Name of the session opened by Connect. |
| Resource group | `resource_group` | Text | Yes |  | Main | Resource group of the virtual machine. |
| VM name | `vm_name` | Text | Yes |  | Main | Name of the virtual machine. |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until Azure has finished; otherwise the step ends as soon as Azure has accepted the request. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `900` | More options | Stop waiting after this many seconds and fail. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | Succeeded when waited for, otherwise Accepted. |

*Time limit 3600 s; needs: network.*

### Stop VM

`ooa.azure_vm/stop_vm` (action)

Stop a virtual machine: deallocate it (compute is no longer billed) or only power it off (the hardware stays reserved and billed).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure` | Main | Name of the session opened by Connect. |
| Resource group | `resource_group` | Text | Yes |  | Main | Resource group of the virtual machine. |
| VM name | `vm_name` | Text | Yes |  | Main | Name of the virtual machine. |
| Mode | `mode` | Text | No | `deallocate` | Main | deallocate: release the hardware so compute is not billed; power_off: stop the VM but keep (and pay for) its hardware. One of: `deallocate`, `power_off` |
| Skip shutdown | `skip_shutdown` | Yes/No | No | No | More options | Power off without a clean shutdown of the operating system (power_off only). |
| Wait | `wait` | Yes/No | No | Yes | More options | Wait until Azure has finished; otherwise the step ends as soon as Azure has accepted the request. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `900` | More options | Stop waiting after this many seconds and fail. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | Succeeded when waited for, otherwise Accepted. |

*Time limit 3600 s; needs: network.*
