# Network

Toolkit `ooa.network`, version 1.0.0. Check whether hosts and ports are reachable, and read or change devices over SNMP v1, v2c and v3.

[All toolkits](README.md)

## Contents

- **Network**: Check port, Ping
- **SNMP**: SNMP get, SNMP get next, SNMP send trap, SNMP set, SNMP walk
- **Conditions**: Host is reachable, Port is open

## Network

### Check port

`ooa.network/check_port` (action)

Check whether a TCP port accepts connections, e.g. a database on port 1433.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Host name or IP address. |
| Port | `port` | Number | Yes |  | Main | TCP port. Range: 1 to 65535 |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | More options | Timeout in milliseconds. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `open` | Yes/No | True when the port accepted a connection. |

*Time limit 60 s; safe to repeat; needs: network.*

### Ping

`ooa.network/ping` (action)

Check whether a host answers, and how fast.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Host name or IP address. |
| Count | `count` | Number | No | `1` | Main | Number of attempts (1 to 100); reachable is true when any is answered. Range: 1 to 100 |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | More options | Timeout in milliseconds. Range: 1 to  |
| Interval (milliseconds) | `interval_ms` | Number | No | `1000` | More options | Pause between attempts. Range: 0 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `reachable` | Yes/No | True when the host answered. |
| `response_ms` | Number | Round-trip time in milliseconds (the average of the answered attempts). |
| `replies` | Number | How many attempts were answered. |
| `lost` | Number | How many attempts got no answer. |

*Time limit 60 s; safe to repeat; needs: network.*

## SNMP

### SNMP get

`ooa.network/snmp_get` (action)

Read one or more values from a device with SNMP v1, v2c or v3.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Device host name or IP address. |
| Version | `version` | Text | No | `v2c` | Main | SNMP version the device speaks. One of: `v1`, `v2c`, `v3` |
| Community | `community` | Any | No | `public` | Main | SNMP v1 or v2c community (text or a Credential); public when empty. |
| Security user | `security_user` | Text | No |  | Main | SNMP v3 security user name (v3 uses it instead of a community). Shown when: `{"version": ["v3"]}` |
| Sign-in protocol | `auth_protocol` | Text | No | `sha` | Main | SNMP v3 sign-in protocol: none, md5, sha (SHA-1), sha256 or sha512. One of: `none`, `md5`, `sha`, `sha256`, `sha512`; Shown when: `{"version": ["v3"]}` |
| Sign-in password | `auth_password` | Credential (vault) | No |  | Main | SNMP v3 sign-in password from the vault. Shown when: `{"version": ["v3"]}` |
| Privacy protocol | `privacy_protocol` | Text | No | `aes128` | Main | SNMP v3 privacy (encryption): none, des or aes128. One of: `none`, `des`, `aes128`; Shown when: `{"version": ["v3"]}` |
| Privacy password | `privacy_password` | Credential (vault) | No |  | Main | SNMP v3 privacy (encryption) password from the vault. Shown when: `{"version": ["v3"]}` |
| Oids | `oids` | Any | Yes |  | Main | One OID, several separated by commas, or a List of OIDs. Example: `1.3.6.1.2.1.1.5.0` |
| Context name | `context_name` | Text | No |  | More options | SNMP v3 context, when the device uses them; usually empty. Shown when: `{"version": ["v3"]}` |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | More options | How long to wait for each answer, in milliseconds. Range: 1 to  |
| Port | `port` | Number | No | `161` | More options | UDP port of the SNMP agent. Range: 1 to 65535 |
| Retries | `retries` | Number | No | `1` | More options | Extra attempts after a timeout. Range: 0 to 10 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | OID to value; empty for objects the device does not have. |
| `table` | Table | Columns: oid, type, value. |

*Time limit 120 s; safe to repeat; needs: network.*

### SNMP get next

`ooa.network/snmp_get_next` (action)

Read the object that follows an OID in the device's tree (SNMP GETNEXT).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Device host name or IP address. |
| Version | `version` | Text | No | `v2c` | Main | SNMP version the device speaks. One of: `v1`, `v2c`, `v3` |
| Community | `community` | Any | No | `public` | Main | SNMP v1 or v2c community (text or a Credential); public when empty. |
| Security user | `security_user` | Text | No |  | Main | SNMP v3 security user name (v3 uses it instead of a community). Shown when: `{"version": ["v3"]}` |
| Sign-in protocol | `auth_protocol` | Text | No | `sha` | Main | SNMP v3 sign-in protocol: none, md5, sha (SHA-1), sha256 or sha512. One of: `none`, `md5`, `sha`, `sha256`, `sha512`; Shown when: `{"version": ["v3"]}` |
| Sign-in password | `auth_password` | Credential (vault) | No |  | Main | SNMP v3 sign-in password from the vault. Shown when: `{"version": ["v3"]}` |
| Privacy protocol | `privacy_protocol` | Text | No | `aes128` | Main | SNMP v3 privacy (encryption): none, des or aes128. One of: `none`, `des`, `aes128`; Shown when: `{"version": ["v3"]}` |
| Privacy password | `privacy_password` | Credential (vault) | No |  | Main | SNMP v3 privacy (encryption) password from the vault. Shown when: `{"version": ["v3"]}` |
| Oid | `oid` | Text | Yes |  | Main | Starting OID. |
| Context name | `context_name` | Text | No |  | More options | SNMP v3 context, when the device uses them; usually empty. Shown when: `{"version": ["v3"]}` |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | More options | How long to wait for each answer, in milliseconds. Range: 1 to  |
| Port | `port` | Number | No | `161` | More options | UDP port of the SNMP agent. Range: 1 to 65535 |
| Retries | `retries` | Number | No | `1` | More options | Extra attempts after a timeout. Range: 0 to 10 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `oid` | Text | OID of the next object. |
| `type` | Text | Value type, e.g. integer, string, end_of_mib_view. |
| `value` | Any | Value of the next object. |

*Time limit 120 s; safe to repeat; needs: network.*

### SNMP send trap

`ooa.network/snmp_send_trap` (action)

Send an SNMP notification (trap) to a monitoring system: v1 (enterprise, generic and specific type), v2c, or v3 signed and encrypted from this runner's engine ID.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Monitoring system host name or IP address. |
| Version | `version` | Text | No | `v2c` | Main | SNMP version the receiver expects. One of: `v1`, `v2c`, `v3` |
| Community | `community` | Any | No | `public` | Main | SNMP v1 or v2c community (text or a Credential); public when empty. |
| Trap oid | `trap_oid` | Text | Yes |  | Main | OID that identifies the kind of notification; for v1 the enterprise OID. Example: `1.3.6.1.4.1.99999.0.1` |
| Variables | `variables` | Dictionary | No |  | Main | Extra values to include: OID to value (numbers are sent as integers, everything else as text). |
| Generic trap | `generic_trap` | Text | No | `enterprise_specific` | Main; tick v1 trap type | SNMP v1 generic trap type; enterprise_specific for your own notifications. One of: `cold_start`, `warm_start`, `link_down`, `link_up`, `authentication_failure`, `egp_neighbor_loss`, `enterprise_specific`; Shown when: `{"version": ["v1"]}` |
| Specific trap | `specific_trap` | Number | No | `0` | Main; tick v1 trap type | SNMP v1 specific trap number, used with enterprise_specific. Range: 0 to ; Shown when: `{"version": ["v1"]}` |
| Security user | `security_user` | Text | No |  | Main; tick SNMP v3 | SNMP v3 security user name (v3 uses it instead of a community). Shown when: `{"version": ["v3"]}` |
| Sign-in protocol | `auth_protocol` | Text | No | `sha` | Main; tick SNMP v3 | SNMP v3 sign-in protocol: none, md5, sha (SHA-1), sha256 or sha512. One of: `none`, `md5`, `sha`, `sha256`, `sha512`; Shown when: `{"version": ["v3"]}` |
| Sign-in password | `auth_password` | Credential (vault) | No |  | Main; tick SNMP v3 | SNMP v3 sign-in password from the vault. Shown when: `{"version": ["v3"]}` |
| Privacy protocol | `privacy_protocol` | Text | No | `aes128` | Main; tick SNMP v3 | SNMP v3 privacy (encryption): none, des or aes128. One of: `none`, `des`, `aes128`; Shown when: `{"version": ["v3"]}` |
| Privacy password | `privacy_password` | Credential (vault) | No |  | Main; tick SNMP v3 | SNMP v3 privacy (encryption) password from the vault. Shown when: `{"version": ["v3"]}` |
| Context name | `context_name` | Text | No |  | Main; tick SNMP v3 | SNMP v3 context, when the receiver uses them; usually empty. Shown when: `{"version": ["v3"]}` |
| Engine ID | `engine_id` | Text | No |  | Main; tick SNMP v3 | SNMP v3 engine ID (hex) to send from; this computer's own (made from its name) when empty. The receiver lists it with the user. Example: `800000000403414243`; Shown when: `{"version": ["v3"]}` |
| Port | `port` | Number | No | `162` | More options | UDP port of the trap receiver. Range: 1 to 65535 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `engine_id` | Text | For v3, the engine ID (hex) the trap was sent from, for setting up the user on the receiver; empty for v1 and v2c. |

*Time limit 60 s; needs: network.*

### SNMP set

`ooa.network/snmp_set` (action)

Change one value on a device with SNMP; the community (or v3 user) must have write access.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Device host name or IP address. |
| Version | `version` | Text | No | `v2c` | Main | SNMP version the device speaks. One of: `v1`, `v2c`, `v3` |
| Community | `community` | Any | No | `private` | Main | SNMP v2c community with write access (text or a Credential). |
| Security user | `security_user` | Text | No |  | Main | SNMP v3 security user name (v3 uses it instead of a community). Shown when: `{"version": ["v3"]}` |
| Sign-in protocol | `auth_protocol` | Text | No | `sha` | Main | SNMP v3 sign-in protocol: none, md5, sha (SHA-1), sha256 or sha512. One of: `none`, `md5`, `sha`, `sha256`, `sha512`; Shown when: `{"version": ["v3"]}` |
| Sign-in password | `auth_password` | Credential (vault) | No |  | Main | SNMP v3 sign-in password from the vault. Shown when: `{"version": ["v3"]}` |
| Privacy protocol | `privacy_protocol` | Text | No | `aes128` | Main | SNMP v3 privacy (encryption): none, des or aes128. One of: `none`, `des`, `aes128`; Shown when: `{"version": ["v3"]}` |
| Privacy password | `privacy_password` | Credential (vault) | No |  | Main | SNMP v3 privacy (encryption) password from the vault. Shown when: `{"version": ["v3"]}` |
| Oid | `oid` | Text | Yes |  | Main | OID of the object to change. Example: `1.3.6.1.2.1.1.6.0` |
| Value type | `value_type` | Text | No | `string` | Main | SNMP type of the new value. One of: `string`, `integer`, `oid`, `ip_address`, `counter32`, `gauge32`, `timeticks`, `counter64` |
| Value | `value` | Any | Yes |  | Main | New value. |
| Context name | `context_name` | Text | No |  | More options | SNMP v3 context, when the device uses them; usually empty. Shown when: `{"version": ["v3"]}` |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | More options | How long to wait for each answer, in milliseconds. Range: 1 to  |
| Port | `port` | Number | No | `161` | More options | UDP port of the SNMP agent. Range: 1 to 65535 |
| Retries | `retries` | Number | No | `1` | More options | Extra attempts after a timeout. Range: 0 to 10 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Value the device reports after the change. |

*Time limit 120 s; needs: network.*

### SNMP walk

`ooa.network/snmp_walk` (action)

Read every object under an OID, e.g. a whole interface table, with repeated GETNEXT requests.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Device host name or IP address. |
| Version | `version` | Text | No | `v2c` | Main | SNMP version the device speaks. One of: `v1`, `v2c`, `v3` |
| Community | `community` | Any | No | `public` | Main | SNMP v1 or v2c community (text or a Credential); public when empty. |
| Security user | `security_user` | Text | No |  | Main | SNMP v3 security user name (v3 uses it instead of a community). Shown when: `{"version": ["v3"]}` |
| Sign-in protocol | `auth_protocol` | Text | No | `sha` | Main | SNMP v3 sign-in protocol: none, md5, sha (SHA-1), sha256 or sha512. One of: `none`, `md5`, `sha`, `sha256`, `sha512`; Shown when: `{"version": ["v3"]}` |
| Sign-in password | `auth_password` | Credential (vault) | No |  | Main | SNMP v3 sign-in password from the vault. Shown when: `{"version": ["v3"]}` |
| Privacy protocol | `privacy_protocol` | Text | No | `aes128` | Main | SNMP v3 privacy (encryption): none, des or aes128. One of: `none`, `des`, `aes128`; Shown when: `{"version": ["v3"]}` |
| Privacy password | `privacy_password` | Credential (vault) | No |  | Main | SNMP v3 privacy (encryption) password from the vault. Shown when: `{"version": ["v3"]}` |
| Oid | `oid` | Text | Yes |  | Main | Root OID of the subtree to read. Example: `1.3.6.1.2.1.2.2` |
| Context name | `context_name` | Text | No |  | More options | SNMP v3 context, when the device uses them; usually empty. Shown when: `{"version": ["v3"]}` |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | More options | How long to wait for each answer, in milliseconds. Range: 1 to  |
| Port | `port` | Number | No | `161` | More options | UDP port of the SNMP agent. Range: 1 to 65535 |
| Retries | `retries` | Number | No | `1` | More options | Extra attempts after a timeout. Range: 0 to 10 |
| Maximum rows | `max_rows` | Number | No | `10000` | More options | Safety limit on objects read. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: oid, type, value. |
| `row_count` | Number | Number of objects read. |

*Time limit 600 s; safe to repeat; needs: network.*

## Conditions

Used in If, Loop while and Wait until steps.

### Host is reachable

`ooa.network/host_reachable` (condition)

True when the host answers a ping within the timeout.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Host name or IP address. |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | Main | Timeout in milliseconds. Range: 1 to  |

### Port is open

`ooa.network/port_open` (condition)

True when a TCP port accepts connections.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Host | `host` | Text | Yes |  | Main | Host name or IP address. |
| Port | `port` | Number | Yes |  | Main | TCP port. Range: 1 to 65535 |
| Wait up to (milliseconds) | `timeout_ms` | Number | No | `3000` | Main | Timeout in milliseconds. Range: 1 to  |
