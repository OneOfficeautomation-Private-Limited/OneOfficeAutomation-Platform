# Active Directory

Toolkit `ooa.active_directory`, version 1.0.0. Create, change, move and delete users, groups, computers and organizational units, reset passwords and manage group membership in Active Directory or LDAP.

[All toolkits](README.md)

## Contents

- **Groups**: Add users to group, Create group, Delete group, Get group members, Get group properties, Move group, Remove users from group, Rename group, Set group property
- **Directory**: Connect, Disconnect, Run LDAP query, Search
- **Computers**: Create computer, Delete computer, Get computer properties, Move computer, Rename computer, Set computer property
- **Organizational units**: Create organizational unit, Delete organizational unit, Get organizational unit properties, Move organizational unit, Rename organizational unit, Set organizational unit property
- **Users**: Create user, Delete user, Disable user, Enable user, Get user, Move user, Rename user, Reset password, Set user property, Set password, Unlock user, Update account options, Update user details

## Groups

### Add users to group

`ooa.active_directory/add_to_group` (action)

Add one or more users to a group (no error for users already in it).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| Group (distinguished name) | `group_dn` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. Example: `CN=Finance,OU=Groups,DC=corp,DC=example,DC=com` |
| More usernames | `more_usernames` | List | No |  | Main | More users (sAMAccountName or UPN) to add in the same step. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changed` | Number | How many users were added (users already in the group are not counted). |

*Time limit 60 s; needs: network.*

### Create group

`ooa.active_directory/create_group` (action)

Create a security or distribution group.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Name | `name` | Text | Yes |  | Main | Group name (also its sAMAccountName unless given). |
| Group description | `group_description` | Text | No |  | Main | Description. |
| Scope | `scope` | Text | No | `global` | Main | Group scope. One of: `global`, `domain_local`, `universal` |
| Group type | `group_type` | Text | No | `security` | Main | Security groups can be used in permissions; distribution groups only for mail. One of: `security`, `distribution` |
| Container (distinguished name) | `container_dn` | Text | Yes |  | Main | Distinguished name of the organizational unit or container to create it in. Example: `OU=Groups,DC=corp,DC=example,DC=com` |
| Pre-Windows 2000 name (sAMAccountName) | `sam_account_name` | Text | No |  | More options | Pre-Windows 2000 name; the group name when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | Distinguished name of the new group. |

*Time limit 60 s; needs: network.*

### Delete group

`ooa.active_directory/delete_group` (action)

Delete a group (its members are not deleted).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Group | `group` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |

*Time limit 60 s; needs: network.*

### Get group members

`ooa.active_directory/get_group_members` (action)

List the members of a group, optionally including members of nested groups.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Group | `group` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |
| Member type | `member_type` | Text | No | `all` | Main | Only return members of this kind. One of: `all`, `user`, `group`, `computer` |
| Recursive | `recursive` | Yes/No | No | No | Main | Include members of nested groups (Active Directory only). |
| Maximum results | `max_results` | Number | No | `5000` | More options | Safety limit. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: dn, name, sAMAccountName, type, mail. |
| `count` | Number | Number of members. |

*Time limit 300 s; safe to repeat; needs: network.*

### Get group properties

`ooa.active_directory/get_group_property` (action)

Read attributes of a group.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Group | `group` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |
| Attributes | `attributes` | List | No |  | Main | Attributes to read, e.g. ["description","managedBy"]; all when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `properties` | Dictionary | dn plus the requested attributes (all readable ones when none are listed). |

*Time limit 60 s; safe to repeat; needs: network.*

### Move group

`ooa.active_directory/move_group` (action)

Move a group to another organizational unit; its members stay.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Group | `group` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |
| Target organizational unit (distinguished name) | `target_ou_dn` | Text | Yes |  | Main | Distinguished name of the destination organizational unit. |
| Session | `session` | Text | No | `ad` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Remove users from group

`ooa.active_directory/remove_from_group` (action)

Remove one or more users from a group (no error for users not in it).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| Group (distinguished name) | `group_dn` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |
| More usernames | `more_usernames` | List | No |  | Main | More users (sAMAccountName or UPN) to remove in the same step. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changed` | Number | How many users were removed. |

*Time limit 60 s; needs: network.*

### Rename group

`ooa.active_directory/rename_group` (action)

Change a group's name, optionally also its pre-Windows 2000 name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Group | `group` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |
| New name | `new_name` | Text | Yes |  | Main | New group name. |
| Also change the pre-Windows 2000 name | `update_sam_account_name` | Yes/No | No | Yes | More options | Also change the sAMAccountName to the new name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Set group property

`ooa.active_directory/set_group_property` (action)

Change one attribute of a group, e.g. description or managedBy.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Group | `group` | Text | Yes |  | Main | Group distinguished name, or its sAMAccountName or common name. |
| Attribute | `attribute` | Text | Yes |  | Main | LDAP attribute name. |
| Value | `value` | Any | No |  | Main | New value, or a list for several values; empty clears it. |

*Time limit 60 s; needs: network.*

## Directory

### Connect

`ooa.active_directory/connect` (action)

Sign in to Active Directory or an LDAP server.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Address (URL) | `url` | Text | Yes |  | Main | Server URL; use ldaps:// for password changes. Example: `ldaps://dc01.corp.example.com:636` |
| Search base (distinguished name) | `base_dn` | Text | Yes |  | Main | Where searches start. Example: `DC=corp,DC=example,DC=com` |
| Bind user | `bind_user` | Text | Yes |  | Main | User to sign in as (UPN or distinguished name). Example: `svc-bot@corp.example.com` |
| Password | `password` | Credential (vault) | Yes |  | Main | Password from the vault. |
| Connect timeout (seconds) | `connect_timeout_seconds` | Number | No | `10` | More options | Seconds to wait for the server to accept the connection. |
| Read timeout (seconds) | `read_timeout_seconds` | Number | No | `30` | More options | Seconds to wait for each answer from the server. |
| Follow referrals | `follow_referrals` | Yes/No | No | Yes | More options | Follow referrals to other domain controllers; turn off to ignore them. |

*Time limit 60 s; needs: network.*

### Disconnect

`ooa.active_directory/disconnect` (action)

Close the directory session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |

*Time limit 60 s.*

### Run LDAP query

`ooa.active_directory/run_query` (action)

Run a raw LDAP filter from any starting point and scope and return the entries as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Filter | `filter` | Text | Yes |  | Main | LDAP filter. Example: `(&(objectClass=computer)(operatingSystem=*Server*))` |
| Attributes | `attributes` | List | No |  | Main | Attributes to return; cn, sAMAccountName and mail when empty. |
| Search base (distinguished name) | `base_dn` | Text | No |  | More options | Where to start; the session's base when empty. |
| Scope | `scope` | Text | No | `subtree` | More options | How deep to search below the starting point. One of: `subtree`, `one_level`, `base` |
| Maximum results | `max_results` | Number | No | `1000` | More options | Safety limit. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per entry; columns are dn plus the requested attributes. |
| `count` | Number | Number of entries. |

*Time limit 300 s; safe to repeat; needs: network.*

### Search

`ooa.active_directory/search` (action)

Run an LDAP filter and return matching entries as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Filter | `filter` | Text | Yes |  | Main | LDAP filter. Example: `(&(objectClass=user)(department=Finance))` |
| Attributes | `attributes` | List | No |  | Main | Attributes to return, e.g. ["cn","mail"]. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Safety limit. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | One row per entry; columns are dn plus the requested attributes. |
| `count` | Number | Number of entries. |

*Time limit 60 s; safe to repeat; needs: network.*

## Computers

### Create computer

`ooa.active_directory/create_computer` (action)

Create a computer account so a machine can join the domain.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Name | `name` | Text | Yes |  | Main | Computer name (up to 15 characters). Example: `PC-0042` |
| Computer description | `computer_description` | Text | No |  | Main | Description. |
| Container (distinguished name) | `container_dn` | Text | Yes |  | Main | Distinguished name of the organizational unit or container to create it in. Example: `OU=Workstations,DC=corp,DC=example,DC=com` |
| Enabled | `enabled` | Yes/No | No | Yes | More options | Create the account enabled. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | Distinguished name of the new computer. |

*Time limit 60 s; needs: network.*

### Delete computer

`ooa.active_directory/delete_computer` (action)

Delete a computer account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Computer | `computer` | Text | Yes |  | Main | Computer distinguished name, or its name (with or without the trailing $). |

*Time limit 60 s; needs: network.*

### Get computer properties

`ooa.active_directory/get_computer_property` (action)

Read attributes of a computer account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Computer | `computer` | Text | Yes |  | Main | Computer distinguished name, or its name (with or without the trailing $). |
| Attributes | `attributes` | List | No |  | Main | Attributes to read, e.g. ["operatingSystem","dNSHostName"]; all when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `properties` | Dictionary | dn plus the requested attributes (all readable ones when none are listed). |

*Time limit 60 s; safe to repeat; needs: network.*

### Move computer

`ooa.active_directory/move_computer` (action)

Move a computer account to another organizational unit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Computer | `computer` | Text | Yes |  | Main | Computer distinguished name, or its name (with or without the trailing $). |
| Target organizational unit (distinguished name) | `target_ou_dn` | Text | Yes |  | Main | Distinguished name of the destination organizational unit. |
| Session | `session` | Text | No | `ad` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Rename computer

`ooa.active_directory/rename_computer` (action)

Rename a computer account (object name and sAMAccountName); the machine itself is not renamed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Computer | `computer` | Text | Yes |  | Main | Computer distinguished name, or its name (with or without the trailing $). |
| New name | `new_name` | Text | Yes |  | Main | New computer name (up to 15 characters). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Set computer property

`ooa.active_directory/set_computer_property` (action)

Change one attribute of a computer account, e.g. description or location.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Computer | `computer` | Text | Yes |  | Main | Computer distinguished name, or its name (with or without the trailing $). |
| Attribute | `attribute` | Text | Yes |  | Main | LDAP attribute name. |
| Value | `value` | Any | No |  | Main | New value, or a list for several values; empty clears it. |

*Time limit 60 s; needs: network.*

## Organizational units

### Create organizational unit

`ooa.active_directory/create_ou` (action)

Create an organizational unit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Name | `name` | Text | Yes |  | Main | Name of the organizational unit. |
| Organizational unit description | `ou_description` | Text | No |  | Main | Description. |
| Parent (distinguished name) | `parent_dn` | Text | Yes |  | Main | Distinguished name of the parent domain or organizational unit. Example: `DC=corp,DC=example,DC=com` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | Distinguished name of the new organizational unit. |

*Time limit 60 s; needs: network.*

### Delete organizational unit

`ooa.active_directory/delete_ou` (action)

Delete an organizational unit; with recursive, everything inside it is deleted too.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Organizational unit (OU) | `ou` | Text | Yes |  | Main | Organizational unit distinguished name, or its name when unique. |
| Recursive | `recursive` | Yes/No | No | No | More options | Also delete every object inside it (cannot be undone). |

*Time limit 60 s; needs: network.*

### Get organizational unit properties

`ooa.active_directory/get_ou_property` (action)

Read attributes of an organizational unit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Organizational unit (OU) | `ou` | Text | Yes |  | Main | Organizational unit distinguished name, or its name when unique. |
| Attributes | `attributes` | List | No |  | Main | Attributes to read, e.g. ["description","managedBy"]; all when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `properties` | Dictionary | dn plus the requested attributes (all readable ones when none are listed). |

*Time limit 60 s; safe to repeat; needs: network.*

### Move organizational unit

`ooa.active_directory/move_ou` (action)

Move an organizational unit, with everything in it, under another parent.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organizational unit (OU) | `ou` | Text | Yes |  | Main | Organizational unit distinguished name, or its name when unique. |
| Target parent (distinguished name) | `target_parent_dn` | Text | Yes |  | Main | Distinguished name of the new parent. |
| Session | `session` | Text | No | `ad` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Rename organizational unit

`ooa.active_directory/rename_ou` (action)

Change the name of an organizational unit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Organizational unit (OU) | `ou` | Text | Yes |  | Main | Organizational unit distinguished name, or its name when unique. |
| New name | `new_name` | Text | Yes |  | Main | New name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Set organizational unit property

`ooa.active_directory/set_ou_property` (action)

Change one attribute of an organizational unit, e.g. description or managedBy.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Organizational unit (OU) | `ou` | Text | Yes |  | Main | Organizational unit distinguished name, or its name when unique. |
| Attribute | `attribute` | Text | Yes |  | Main | LDAP attribute name. |
| Value | `value` | Any | No |  | Main | New value, or a list for several values; empty clears it. |

*Time limit 60 s; needs: network.*

## Users

### Create user

`ooa.active_directory/create_user` (action)

Create a user account in an organizational unit, optionally with a first password, and enable it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | Sign-in name (sAMAccountName, up to 20 characters). Example: `jdoe` |
| User principal name | `user_principal_name` | Text | No |  | Main | User principal name (UPN). Example: `jdoe@corp.example.com` |
| First name | `first_name` | Text | No |  | Main | Given name. |
| Last name | `last_name` | Text | No |  | Main | Surname. |
| Display name | `display_name` | Text | No |  | Main | Display name; also the object name. First and last name when empty. |
| Initials | `initials` | Text | No |  | Main | Initials (up to 6 characters). |
| User description | `user_description` | Text | No |  | Main | Description shown in the directory. |
| Email | `email` | Text | No |  | Main | Email address (stored in mail). Example: `jdoe@corp.example.com` |
| Department | `department` | Text | No |  | Main | Department. |
| Title | `title` | Text | No |  | Main | Job title. |
| Enabled | `enabled` | Yes/No | No | Yes | Main | Enable the account once the password is set. |
| Password | `password` | Credential (vault) | No |  | Main; tick Set a first password | First password from the vault (needs an ldaps:// connection); the account stays disabled without one. |
| Must change password | `must_change_password` | Yes/No | No | Yes | Main | Require a password change at first sign-in. |
| Password never expires | `password_never_expires` | Yes/No | No | No | Main | Create the account with a password that never expires (must_change_password is then ignored by the directory). |
| Container (distinguished name) | `container_dn` | Text | Yes |  | Main | Distinguished name of the organizational unit or container to create it in. Example: `OU=Staff,DC=corp,DC=example,DC=com` |
| Attributes | `attributes` | Dictionary | No |  | More options | More attributes, e.g. {"mail": "jdoe@example.com", "department": "Finance"}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | Distinguished name of the new user. |

*Time limit 60 s; needs: network.*

### Delete user

`ooa.active_directory/delete_user` (action)

Delete a user account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |

*Time limit 60 s; needs: network.*

### Disable user

`ooa.active_directory/disable_user` (action)

Disable a user account (for example when someone leaves).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |

*Time limit 60 s; needs: network.*

### Enable user

`ooa.active_directory/enable_user` (action)

Enable a disabled user account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |

*Time limit 60 s; needs: network.*

### Get user

`ooa.active_directory/get_user` (action)

Read a user's properties by sAMAccountName or UPN.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| Attributes | `attributes` | List | No |  | Main | More attributes to read, e.g. ["employeeID","physicalDeliveryOfficeName"]; the usual set is always returned. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | dn, sAMAccountName, displayName, mail, enabled, locked and more. |

*Time limit 60 s; safe to repeat; needs: network.*

### Move user

`ooa.active_directory/move_user` (action)

Move a user to another organizational unit.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| Target organizational unit (distinguished name) | `target_ou_dn` | Text | Yes |  | Main | Distinguished name of the destination organizational unit. |
| Session | `session` | Text | No | `ad` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Rename user

`ooa.active_directory/rename_user` (action)

Change the object name of a user (optionally also its display name), its sign-in name (sAMAccountName and the part of the UPN before @), or both.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| New name | `new_name` | Text | Yes |  | Main | New object name (common name), e.g. Jane Doe; also the new sign-in name when only the sign-in name is renamed and new_sign_in_name is empty. |
| Rename target | `rename_target` | Text | No | `object` | Main | object: the object name only (the sign-in name is kept); sign_in: the sign-in name only; both: object name and sign-in name. One of: `object`, `sign_in`, `both` |
| Update display name | `update_display_name` | Yes/No | No | Yes | More options | Also set the display name to the new name (when the object name changes). |
| New sign in name | `new_sign_in_name` | Text | No |  | More options | New sign-in name (sAMAccountName, up to 20 characters); new_name is used when empty. Example: `jdoe2` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dn` | Text | New distinguished name. |

*Time limit 60 s; needs: network.*

### Reset password

`ooa.active_directory/reset_password` (action)

Set a new password (needs an ldaps:// connection), optionally forcing a change at next sign-in.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| New password | `new_password` | Credential (vault) | Yes |  | Main | New password from the vault or a Credential variable. |
| Must change | `must_change` | Yes/No | No | Yes | Main | Require a change at next sign-in. |
| Unlock | `unlock` | Yes/No | No | No | More options | Also unlock the account. |

*Time limit 60 s; needs: network.*

### Set user property

`ooa.active_directory/set_attribute` (action)

Change one attribute of a user, e.g. title or telephoneNumber; values can be replaced, added or removed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| Attribute | `attribute` | Text | Yes |  | Main | LDAP attribute name. |
| Value | `value` | Text | No |  | Main | New value; empty clears it. |
| Mode | `mode` | Text | No | `replace` | More options | replace: set the value; add: add one more value to a multi-valued attribute; remove: remove this one value. One of: `replace`, `add`, `remove` |

*Time limit 60 s; needs: network.*

### Set password

`ooa.active_directory/set_password` (action)

Set a user's password as an administrator (needs an ldaps:// connection), optionally unlocking the account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| New password | `new_password` | Credential (vault) | Yes |  | Main | New password from the vault or a Credential variable. |
| Must change | `must_change` | Yes/No | No | No | Main | Require a change at next sign-in. |
| Unlock | `unlock` | Yes/No | No | No | More options | Also unlock the account. |

*Time limit 60 s; needs: network.*

### Unlock user

`ooa.active_directory/unlock_user` (action)

Unlock an account locked by failed sign-ins.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |

*Time limit 60 s; needs: network.*

### Update account options

`ooa.active_directory/update_account_options` (action)

Turn account options on or off: enabled, password never expires, and must change password at next sign-in; empty options stay as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| Enabled | `enabled` | Yes/No | No |  | Main | Enable (true) or disable (false) the account. |
| Must change password | `must_change_password` | Yes/No | No |  | Main | Whether a password change is required at next sign-in. |
| Password never expires | `password_never_expires` | Yes/No | No |  | Main | Whether the password never expires. |
| Cannot change password | `cannot_change_password` | Yes/No | No |  | Main | Whether the user is stopped from changing their own password (Active Directory only; changes the object's permissions). |
| Smart card required | `smart_card_required` | Yes/No | No |  | More options | Whether a smart card is required for interactive sign-in. |
| Not delegated | `not_delegated` | Yes/No | No |  | More options | Whether the account is sensitive and cannot be delegated. |
| Password not required | `password_not_required` | Yes/No | No |  | More options | Whether the account may have an empty password (normally false). |
| No kerberos preauth | `no_kerberos_preauth` | Yes/No | No |  | More options | Whether Kerberos pre-authentication is not required (normally false). |
| Account expires | `account_expires` | Text | No |  | More options | Last day the account can be used (yyyy-MM-dd, the account ends at the start of the next day UTC), or never. Example: `2026-12-31` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user_account_control` | Number | The account control flags after the change. |

*Time limit 60 s; needs: network.*

### Update user details

`ooa.active_directory/update_user_details` (action)

Change several details of a user in one step (names, description, office, phone, email, title, department, manager and any other attributes); empty inputs stay as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `ad` | Main | Session name. |
| Username | `username` | Text | Yes |  | Main | sAMAccountName or UPN. |
| First name | `first_name` | Text | No |  | Main | Given name. |
| Last name | `last_name` | Text | No |  | Main | Surname. |
| Display name | `display_name` | Text | No |  | Main | Display name. |
| Initials | `initials` | Text | No |  | Main | Initials. |
| Email | `email` | Text | No |  | Main | Email address (mail). |
| User description | `user_description` | Text | No |  | Main | Description. |
| Department | `department` | Text | No |  | Main | Department. |
| Title | `title` | Text | No |  | Main | Job title. |
| Office | `office` | Text | No |  | More options | Office (physicalDeliveryOfficeName). |
| Telephone | `telephone` | Text | No |  | More options | Telephone number. |
| Company | `company` | Text | No |  | More options | Company. |
| Manager | `manager` | Text | No |  | More options | Manager: a distinguished name, or a sAMAccountName or UPN that is looked up. |
| Attributes | `attributes` | Dictionary | No |  | More options | Any other attributes, e.g. {"employeeID": "E123"}; an empty value clears the attribute. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `changed` | List | Names of the attributes that were changed. |

*Time limit 60 s; needs: network.*
