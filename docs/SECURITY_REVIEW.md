# Security review pack

For an independent security review and penetration test of ONEOFFICEAUTOMATION (open item S1). It describes what to
test, how to set up a test installation, the controls in place and the known gaps, so a testing firm can start without
a walkthrough. Report findings as described in [SECURITY.md](../SECURITY.md).

## 1. Scope

| Component | What it is | Network exposure |
| --- | --- | --- |
| One Command Center | Java server: web console, REST API, runner API, webhooks, API tasks, MCP endpoint | TCP 8720 (configurable), HTTP or HTTPS |
| Agent Studio | Web editor served by the Command Center at `/studio/` | Same port; this computer only (loopback) |
| One Runner | Java process (sign-in task or Windows service) that polls the Command Center and runs agents | Outbound only, to the Command Center |
| Run workers | One Java process per run, started by the runner | None (stdin/stdout pipe to the runner) |
| Browser connector | Chrome/Edge extension plus a native messaging host | None (native messaging) |
| Installers | `ooa-setup` zip with Install.cmd, and the MSI | Local |
| Database | Microsoft SQL Server (licensed installs) or embedded H2 (evaluation) | Customer's database server |
| License Studio | Vendor-only tool for issuing licence files; not in customer builds | 127.0.0.1 only, token in the address |

Out of scope unless agreed: the customer's identity provider, external vaults and database server themselves, and the
third-party systems agents automate.

## 2. Test installation

1. On a Windows Server 2022 VM: `msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720` (or
   Install.cmd). Move it to SQL Server: `bin\ooa.bat db configure ...` ([DATABASE.md](DATABASE.md)).
2. HTTPS: `ooa tls configure --pfx server.pfx`, `ooa tls set-password`, restart the service. For a quick test,
   `ooa tls self-signed --host <name>`.
3. Sign in as `admin` with the one-time password from `<data>\logs\service-server.log`, then create one account per
   role for the testers (Administration > People).
4. A second VM as a runner: `msiexec /i ... ROLE=Runner URL=https://<cc>:8720 REGISTRATION_KEY=ooareg_...` (runner
   service), or Install.cmd -Role Runner for a desktop runner.
5. Optional: single sign-on (OIDC) against a test tenant, an external vault link, syslog to a collector.

Roles to test with: Administrator, Bot Creator, Bot Runner and Viewer, plus custom roles from Administration > Roles.

## 3. Trust boundaries and data

- **Browser to Command Center:** session token in an `HttpOnly`, `SameSite=Strict` cookie (or `Authorization: Bearer`
  for API clients). Changes through the cookie need the `X-OOA-Request` header (CSRF).
- **Runner to Command Center:** device id and secret from one-time registration (registration keys expire after at
  most the hours chosen and work once). The runner stores them in its own config file in the service account's or
  user's profile; the server keeps only a hash.
- **Runner to run worker:** the job (agent, inputs, vault references) over a pipe; secrets are fetched per use through
  the runner and never written to the job file.
- **Command Center to external systems:** identity provider (OIDC), external vaults (HashiCorp Vault, Azure Key Vault,
  AWS Secrets Manager, CyberArk CCP), mail (IMAP, Microsoft Graph), syslog, model providers, webhooks out.
- **Sensitive data held:** credentials in the vault, agent definitions and repository files, run inputs and outputs,
  audit trail, licence file.

## 4. Controls to verify

| Area | Control | Where |
| --- | --- | --- |
| Passwords | PBKDF2-HMAC-SHA256, 210,000 iterations, random salt; length and composition rules; common-password check; history and expiry | `Security.java`, `ControlRoom.java` |
| Sign-in | Lockout after 5 failures for 15 minutes; rate limits per address; session limit per user; must change first password; two-step sign-in (TOTP, recovery codes) optional or required | `RateLimiter.java`, `ControlRoom.java`, `Mfa.java` |
| Sessions | Random tokens; only SHA-256 hashes stored; idle and absolute expiry from Settings | `ControlRoom.java` |
| Authorization | Role permissions plus folder permissions on every API call | `Rbac.java`, `Folders.java` |
| SSO | OIDC authorization code with PKCE; ID token signature, issuer, audience, expiry and nonce checked. SAML 2.0: no DTDs, one assertion, signature over exactly that element, issuer, audience, recipient, time window, InResponseTo, no replay | `Sso.java`, `Saml.java` |
| Vault | AES-256-GCM, each value bound to its location (additional data); master key from `OOA_MASTER_KEY` or a key file; key rotation | `Vault.java`, `VaultAdmin.java` |
| Audit | Every change audited; HMAC chain keyed by a vault-encrypted secret, hourly checkpoints in a file outside the database, archived retention; checked by "Verify integrity"; syslog forwarding | `Audit.java`, `SyslogForwarder.java` |
| Agents | SHA-256 of each checked-in version re-checked on every read; optional four-eyes approval before a version goes live | `ControlRoom.java`, `ProductionApproval.java` |
| Transport | Built-in HTTPS, TLS 1.2 and 1.3 only; HSTS and `Secure` cookie over HTTPS; runners verify the certificate (custom CA with `--ca`) | `ControlRoomServer.java`, `TlsConfig.java`, `AgentClient.java` |
| Headers | Content-Security-Policy (`default-src 'self'`, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` | `ControlRoomServer.java` |
| Network | IP allow list (CIDR); Studio and the local runner listener answer on loopback only | `IpAllowList.java`, `ControlRoomServer.java` |
| Uploads | Size limit; optional block on executable uploads | Settings |
| Runners | Secrets stored as hashes and replaced every `runner_secret_days`; temporary runners removed when offline; runs only on runners with the needed toolkits | `ControlRoom.java` |
| Backups | Scheduled zip of all tables and repository files; vault values stay encrypted, the vault key is never included; restore checks the key fingerprint | `Backups.java` |
| Licensing | Ed25519-signed licence files tied to an install id | `License.java`, `Licensing.java` |
| Unattended runs | Dialogs and forms never wait for a person; screen agents do not start on a locked runner | `DesktopSupport.java`, `DesktopSession.java` |

## 5. Suggested test cases

1. Authentication: brute force and lockout, password rules, two-step codes (replay, lockout), session fixation, logout
   and expiry, SSO token substitution (wrong audience, expired, unsigned, other issuer), SAML signature wrapping and
   XML entity attacks.
2. Authorization: every API route with each role; folder permissions; horizontal access to other people's runs,
   credentials and work-queue items; runner credentials used against user APIs and the other way round.
3. Injection: SQL (SQL Server and H2), JSON path expressions, agent expressions, file paths in repository files and
   workspace writes, headers and syslog fields.
4. Web: XSS in names, descriptions, run outputs and audit details shown in the console; CSRF; clickjacking; CSP bypass.
5. Secrets: vault values in API responses, logs, audit entries, run outputs and error messages; master key handling;
   the TLS certificate password; runner config files.
6. Runner channel: replayed or forged heartbeats and results; a runner reporting results for another runner's run;
   registration key reuse; TLS downgrade or a wrong certificate.
7. Denial of service: large uploads, many sessions, slow requests, deeply nested JSON.
8. Installers: file and folder permissions after install (Program Files, data folder, runner config), service
   account rights, the MSI log (the registration key must not appear), uninstall leftovers.
9. Supply chain: third-party libraries (`gradlew dependencies`), the bundled Java runtime version.

## 6. Known gaps (no need to report)

- SAML assertions cannot be encrypted (refused), and sign-ins started at the identity provider are refused.
- Audit checkpoints go to a local file; copying it to write-once storage is up to the operator.
- Runner config files hold the device secret in clear text, protected by the profile's file permissions.
- Runner updates are checked by SHA-256 over the TLS connection, not separately signed.
- The local Studio listener and the loopback runner listener trust any process on the same computer.
- Unattended runners cannot sign a user in by themselves yet.

## 7. Before the test

- Agree dates, test windows, source IPs and contacts; give the testers this file, [ENTERPRISE_CHECKS.md](ENTERPRISE_CHECKS.md)
  and [CONTROL_ROOM_AND_AGENT.md](CONTROL_ROOM_AND_AGENT.md).
- Decide whether they get the source (white box) or only the installers (black box).
- Take a database backup; tests may lock accounts and fill the audit trail.
