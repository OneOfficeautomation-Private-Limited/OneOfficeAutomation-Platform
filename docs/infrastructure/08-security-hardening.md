# 08. Security hardening

## What this covers / audience

How to protect an installation: the vault master key and where it can come from, TLS, service accounts and least
privilege, the audit chain with checkpoints and SIEM forwarding, secret rotation, retention, sign-in policy (single
sign-on, two-step sign-in), network restrictions, and a checklist of recommended settings. For security architects,
security operations and administrators preparing for production or an audit.

## Contents

1. Threat model in brief
2. The vault master key
3. Rotating the vault key
4. TLS
5. Service accounts and least privilege
6. File system permissions
7. Sign-in policy: passwords, SSO, two-step sign-in, sessions
8. Network restrictions
9. Audit trail, checkpoints and SIEM export
10. Secret rotation for runners, API keys and tokens
11. Retention
12. Agents, toolkits and uploads
13. Recommended settings checklist
14. Known gaps

## 1. Threat model in brief

| Asset | Main protections |
| --- | --- |
| Vault credentials | AES-256-GCM, each value bound to its location; master key outside the database; values handed to agents only during runs, every use audited |
| Agent definitions | SHA-256 taken at check-in and re-checked on every read (`CR.AGENT_TAMPERED`); optional four-eyes approval before production |
| Audit trail | HMAC-SHA-256 chain keyed by a vault-encrypted secret; hourly checkpoints outside the database |
| Sessions | Random tokens, only hashes stored; HttpOnly, SameSite=Strict cookie; CSRF header on every change |
| Runner channel | One-time registration keys; device secrets stored as hashes and rotated; TLS with certificate checks |
| Releases | Signed manifest (Ed25519) and SBOM |

Full control list: [Security review pack](../SECURITY_REVIEW.md).

## 2. The vault master key

The key (32 random bytes) encrypts vault values, database and TLS passwords stored in the data folder, two-step
secrets, the audit chain key and the install id. It is looked up in this order:

| Order | Source | Set with | Notes |
| --- | --- | --- | --- |
| 1 | `OOA_MASTER_KEY` | Environment variable, Base64 of 32 bytes (`openssl rand -base64 32`) | Machine-level variable on Windows (`setx ... /M`), `/etc/ooa/ooa.env` on Linux, a secret in containers |
| 2 | `OOA_MASTER_KEY_SOURCE=azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET` | Azure Key Vault secret | Signs in with `AZURE_TENANT_ID`/`AZURE_CLIENT_ID`/`AZURE_CLIENT_SECRET`, or the App Service/Container Apps identity, or the VM's managed identity (`AZURE_CLIENT_ID` picks a user-assigned one) |
| 2 | `OOA_MASTER_KEY_SOURCE=aws-secrets:REGION/SECRET-ID` | AWS Secrets Manager | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`(/`AWS_SESSION_TOKEN`), the ECS task role, or the EC2 instance role (IMDSv2) |
| 2 | `OOA_MASTER_KEY_SOURCE=gcp-secret:projects/P/secrets/S/versions/latest` | Google Secret Manager | The machine's service account (metadata server) |
| 2 | `OOA_MASTER_KEY_SOURCE=prompt` | An operator types the key at start | Needs a console: not for services or containers |
| 3 | `DATA\master.key` | Created at the first start when nothing else is set | Evaluation; on Linux created with mode 600 |

- The secret in a key store holds the key as Base64, like the variable. It is read once at start and kept only in
  memory. Give the machine's identity read access to that one secret only.
- If the key store cannot be reached at start, the Command Center does not start (`CR.VAULT_KEY`). Plan network
  access to the key store (and a proxy in `JAVA_OPTS` if needed).
- The start-up lines and Administration > Vault key show where the key came from and its fingerprint (first 12 hex
  digits of its SHA-256), never the key.
- Every `ooa` command that reads encrypted settings (`db`, `tls`, `backup`, `check`) needs the same key in its
  environment.
- Hardware security modules (PKCS#11) are not supported in this version.

## 3. Rotating the vault key

The console's **Rotate key** (Administration > One Agent Vault > Vault key) works when the key comes from the key
file. It writes the new key next to the old one, re-encrypts in one transaction every credential, people's own
values, external-vault sign-in secrets, the audit chain key, two-step secrets and the install id, re-encrypts the
database and TLS passwords in the configuration files (keeping `.before-rotate` copies), replaces `master.key` and
keeps the old one as `master.key.old-<time>`. It is audited as `vault.rotate`.

When the key comes from `OOA_MASTER_KEY` or a key store, the console refuses to rotate: replacing the key in the
store without re-encrypting would make every stored value unreadable. Procedure in that case (all nodes stopped
except one, during a maintenance window):

1. Take a backup (`ooa backup now`) and keep the current key.
2. On one node, write the current key into `DATA\master.key`, remove `OOA_MASTER_KEY`/`OOA_MASTER_KEY_SOURCE` from
   its environment and start it.
3. Use **Rotate key** in the console.
4. Stop the node, copy the new `master.key` contents into your key store or variable, delete `master.key`, restore
   the environment setting on every node, start the nodes.
5. Archive the old key with the backups made before the rotation.

## 4. TLS

| Item | Recommendation |
| --- | --- |
| Protocols | Built-in HTTPS offers TLS 1.2 and 1.3 only. On a load balancer, use a policy with TLS 1.2+ only. |
| Certificate | Company or public CA certificate covering every address used; renew before 30 days remain (`ooa check` warns) |
| Plain HTTP | Never across the network. A server listening beyond 127.0.0.1 without TLS prints a warning at start. |
| HSTS and cookies | Automatic with built-in HTTPS, with `X-Forwarded-Proto: https`, or when `public_url` starts with `https://` |
| `.pfx` password | Stored encrypted with the vault key (`ooa tls set-password`) |
| Database | `tls=required` with a trusted CA (`tls.ca`); never `trustAnyCertificate` in production |
| Runners | Trust only the company CA or the exported certificate (`--ca`) |
| SMTP | STARTTLS or SSL; `none` only on a trusted network; the server certificate must match the host name |

## 5. Service accounts and least privilege

| Component | Account | Rights it needs |
| --- | --- | --- |
| Command Center service | A dedicated domain account or gMSA (`--account`), rather than Local System | Read the program folder; read/write the data folder and backup folder; read/write the shared repository folder; network access to database and configured services; no local administrator |
| Command Center on Linux | `ooa` system user (systemd unit) | Write only `/var/lib/ooa` (`ProtectSystem=strict`) |
| Container | `ooa` user, non-root; program owned by root | Write only `/data` |
| Database runtime account | `ooa_app` or the gMSA | `db_datareader`, `db_datawriter` only |
| Database setup account | `ooa_setup` | `dbcreator`; keep its password out of the settings file between upgrades (remove `setup.password`) |
| Runner for screen agents | A dedicated Windows user per runner | Only the application rights the agents need; not an administrator |
| Runner service | Local System by default; prefer a dedicated account | Rights the headless agents need |
| Key store identity | VM or task identity | Read one secret |

Administrators in the console: give the Administrator role to few people; use custom roles (Administration > Roles)
for narrow duties; switch off unused built-in roles (`switched_off_roles`).

## 6. File system permissions

| Path | Who may read | Notes |
| --- | --- | --- |
| `DATA\master.key` (if used) | The service account only | Better: do not use a key file in production |
| `DATA\config` | Service account and administrators | Holds encrypted passwords and the `.pfx` |
| `DATA\backups`, `backup_folder` | Service account and backup operators | Backups hold encrypted vault values and all data |
| `DATA\audit-checkpoints.log` | Service account; copy to write-once storage | |
| Runner `%USERPROFILE%\.ooa-agent\agent.json` | That user only | Holds the device secret in clear text (profile ACLs protect it) |
| Program folder | Administrators write, everyone reads | Default for Program Files |

## 7. Sign-in policy

| Setting | Range | Default | Recommendation |
| --- | --- | --- | --- |
| `password_min_length` | 12 to 64 | 12 | 14 or more |
| `password_history` | 0 to 24 | 0 | 12 |
| `password_max_days` | 0 to 3650 | 0 (never) | Per company policy; or rely on SSO |
| `mfa_required` | 0 optional, 1 administrators, 2 everyone with a password | 0 | At least 1 |
| `session_hours` | 1 to 720 | 8 | 8 or less |
| `max_sessions_per_user` | 1 to 50 | 5 | 3 |
| `inactive_user_days` | 0 to 3650 | 0 | 90 |
| `sso_issuer`, `sso_client_id`, `sso_client_secret_ref` | OpenID Connect | - | Use the company identity provider (PKCE, signature, issuer, audience, expiry and nonce checked); client secret as a vault reference |
| SAML settings (`saml_idp_*`) | SAML 2.0 | - | Signed assertion or response, unencrypted; sign-ins start at the Command Center |
| `sso_default_role` | A role | Empty | Empty (existing accounts only) or the least role |

Built in, not configurable: lockout for 15 minutes after five failures; passwords stored with PBKDF2-HMAC-SHA256
(210,000 iterations); rate limits of 60 a minute per address for sign-in, tokens, SSO and runner registration, and
1,200 for webhooks, API tasks and MCP (429 `CR.RATE_LIMITED`).

Single sign-on users follow the identity provider's MFA policy; enforce it there.

## 8. Network restrictions

| Control | Setting |
| --- | --- |
| Restrict who may call the API | `ip_allow_list`: up to 200 addresses or CIDR ranges. This computer and health checks are always allowed; saving a list that excludes your own address is refused. Behind a remote load balancer the list sees the balancer's address. |
| Listen address | `--host 127.0.0.1` (default) behind a local reverse proxy, or `0.0.0.0` with HTTPS |
| Outgoing traffic | `outbound_proxy` plus egress firewall rules; serverless tasks can reach any address the server can, so make them only for trusted agents |
| Runner networks | No inbound ports; outbound to the Command Center only |
| SaaS | NetGuard refuses private, loopback, link-local, CGNAT and unique-local targets for tenants; add network egress rules too ([09](09-saas-and-multi-region-operations.md)) |
| Response headers | Built in: Content-Security-Policy (`default-src 'self'`, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` |

## 9. Audit trail, checkpoints and SIEM export

- Every sign-in, change, run, credential use, task and workflow event is recorded and chained with HMAC-SHA-256.
- Every hour (on the leader) a checkpoint line, `<time> <sequence> <hash>`, is appended to
  `DATA\audit-checkpoints.log`. **Copy it to write-once storage or your log collector**; the product does not do
  this itself. One Audit Trail > Verify integrity compares the database with it, so deleting the newest records is
  detected.
- **SIEM**: set `syslog_target` (`host:port`) and `syslog_protocol` (`udp` default, or `tcp` with octet counting).
  Every audit entry is sent as RFC 5424 syslog, facility 13 (log audit), severity notice for success and warning
  otherwise. "Send a test message" checks it.
- Export: Audit > Export CSV (up to 50,000 records), itself audited; API `GET /api/audit/export`.

## 10. Secret rotation

| Secret | Rotation |
| --- | --- |
| Runner device secrets | Automatic every `runner_secret_days` (0 to 365, default 30): the runner asks with its heartbeat, saves the new secret atomically; the old one works for a short overlap |
| Registration keys | One use, at most 24 hours |
| API keys (`ooak_...`) | Optional expiry; revoke any time; only a hash stored |
| Session tokens from API keys | 8 hours, extended while used; `POST /api/auth/refresh` replaces them |
| Database passwords | Change in the database, then `ooa db set-password [--setup]`, restart |
| TLS certificate | Replace the `.pfx`, `ooa tls set-password`, restart |
| SaaS operator token | `ooa region token`, update `operator.token_sha256`, restart the region |
| Vault master key | Section 3 |

## 11. Retention

| Setting | Range | Default |
| --- | --- | --- |
| `audit_retention_days` | 0, or 90 to 36500 | 0 (keep all). Older records are moved to compressed files in `DATA\audit-archive` with an `audit.purged` record that keeps the chain verifiable. |
| `run_retention_days`, `run_log_retention_days` | 0 to 36500 | 0 (keep) |
| `ai_call_retention_days`, `work_item_retention_days` | 0 to 36500 | 0 (keep) |
| `backup_keep_days` | 1 to 3650 | 7 |

Retention runs hourly on the leader. Queued and running runs are never removed.

## 12. Agents, toolkits and uploads

- `production_approval` = 1: a second person approves a version before it goes live.
- Code analysis policy can refuse check-ins with errors (for example passwords typed into steps).
- Custom toolkits must be signed by a trusted publisher; the runner re-checks the jar's SHA-256 and signature before
  loading. Trust only publishers whose code you would install yourself.
- `block_executable_uploads` = 1 refuses `.exe`, `.dll`, `.msi`, `.bat`, `.cmd`, `.com`, `.scr`, `.ps1`, `.vbs` and
  `.jar` uploads.
- `max_upload_mb` (1 to 1024, default 5) limits request size.
- Vault attributes: keep **Masked** on; use **Password fields only** for passwords.

## 13. Recommended settings checklist

- [ ] Vault key from `OOA_MASTER_KEY` or a cloud key store; no `master.key` on production servers; key backed up apart from backups.
- [ ] HTTPS with a company certificate; `public_url` set to the HTTPS address; `ooa check` shows no HTTPS warning.
- [ ] Database on SQL Server or PostgreSQL with `tls=required`, data-only runtime account, setup password removed between upgrades.
- [ ] Service runs as a dedicated account or gMSA; data folder ACLs limited.
- [ ] Single sign-on configured; `mfa_required` at least 1; `sso_default_role` empty or minimal.
- [ ] `password_min_length` 14+, `password_history` 12, `inactive_user_days` 90, `max_sessions_per_user` 3.
- [ ] `ip_allow_list` set, or equivalent filtering at the load balancer.
- [ ] `syslog_target` set to the SIEM; `audit-checkpoints.log` shipped to write-once storage.
- [ ] `audit_retention_days` set per policy (90 or more), archives retained.
- [ ] `backup_hours` set, `backup_folder` on separate storage; restore tested.
- [ ] `runner_secret_days` 30 (default) or less.
- [ ] `production_approval` = 1; code analysis refuses errors; `block_executable_uploads` = 1.
- [ ] Runners for screen agents use dedicated non-admin accounts; after-run action set to lock or sign out where acceptable.
- [ ] Downloads verified with `ooa release verify`; SBOM fed to the vulnerability scanner.

## 14. Known gaps

- Audit checkpoints are written to a local file only.
- Runner configuration files hold the device secret in clear text, protected by profile permissions.
- Runner updates are checked by SHA-256 over TLS, not separately signed.
- Local listeners (Studio, the internal runner listener, browser connector) trust any process on the same computer.
- SAML assertions cannot be encrypted, and sign-ins started at the identity provider are refused.
- No HSM support for the vault key.

## Related documents

- [README](README.md)
- [04. Network, ports, proxies and certificates](04-network-ports-proxies-and-certificates.md)
- [05. Databases](05-databases.md)
- [07. Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md)
- [Enterprise checks](../ENTERPRISE_CHECKS.md)
- [Security review pack](../SECURITY_REVIEW.md)
