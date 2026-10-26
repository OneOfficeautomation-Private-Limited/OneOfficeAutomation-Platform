# Enterprise checks and validations

What the Command Center checks, and where to set it. Licensing has its own page, [LICENSING.md](LICENSING.md); the
database has [DATABASE.md](DATABASE.md).

## Health

| Address | Use |
| --- | --- |
| `GET /api/health` | Liveness: the server answers |
| `GET /api/health/ready` | Readiness for load balancers and monitoring (200 ready, 503 not ready) |

Readiness reports `ok`, `warn` or `fail` for each of these checks, and never shows paths or secrets:

- the database answers;
- the schema is the version this release needs;
- the vault key encrypts and decrypts;
- free disk space in the data folder (fail under 200 MB, warn under 2 GB);
- the number of runners online.

At start, the server refuses a database that a newer release has upgraded (`CR.DB_NEWER`).

Before an install or upgrade, `ooa check` tests Java, memory, disk space, the port, the data folder, the vault key, the
database and HTTPS (PASS, WARN or FAIL; the exit code is the number of FAILs). On a runner, `ooa agent diagnose` does the same for
the connection to the Command Center.

## Settings checked by the server (Administration > Settings)

Every change is checked first and then saved in one transaction: either all of it is saved or none. Each change is
audited.

| Setting | Allowed | Effect |
| --- | --- | --- |
| `session_hours` | 1–720 | How long a sign-in lasts (session and cookie) |
| `max_upload_mb` | 1–1024 | Largest request (files travel as Base64 inside JSON) |
| `max_sessions_per_user` | 1–50 (default 5) | Sign-ins per person at once; the oldest end first |
| `password_min_length` | 12–64 | Shortest password allowed |
| `password_history` | 0–24 | Earlier passwords that may not be used again |
| `password_max_days` | 0–3650 (0 never) | Days after which a password must be changed at the next sign-in |
| `audit_retention_days` | 0, or 90 and more | Older audit records are archived to `audit-archive` and removed from the database |
| `ai_call_retention_days`, `work_item_retention_days` | 0–36500 (0 keeps) | Removal of old AI calls and finished work items |
| `backup_hours`, `backup_keep_days`, `backup_folder` | 0–168, 1–3650, a folder | Automatic backups and how long they are kept |
| `temporary_runner_hours` | 1–720 | Offline hours after which a temporary runner is removed |
| `runner_secret_days` | 0–365 (0 never) | Days after which a runner's secret is replaced |
| `repo_files_folder` | A folder | Shared storage for repository files when several nodes run |
| `block_executable_uploads` | 0/1 | Refuse .exe, .dll, .msi, .bat, .cmd, .com, .scr, .ps1, .vbs and .jar uploads |
| `ip_allow_list` | Addresses or CIDR ranges, up to 200 | Only these addresses may use the API (this computer and health checks always may); saving a list that excludes your own address is refused |
| `syslog_target`, `syslog_protocol` | `host:port`, `udp` or `tcp` | Forward every audit entry to a SIEM (RFC 5424; TCP uses octet counting). "Send a test message" checks the connection |
| `public_url`, `sso_issuer` | `http(s)://` addresses | |
| `sso_default_role` | An existing role | |
| Other numbers | Their own ranges (the same as the console's) | |

## Sign-in and requests

- **Passwords:** the minimum length from Settings and at most 128 characters. They need letters and digits, may not be
  the user name, may not repeat a character four times in a row, and may not be a common password with digits or
  symbols added.
- **Lockout:** five failed sign-ins lock the account for 15 minutes; five wrong two-step codes count as one failure.
- **Single sign-on:** OpenID Connect (with PKCE) and SAML 2.0. SAML responses are parsed without DTDs or external
  entities; exactly one assertion is accepted, signed (it or the response) by the configured certificate with the
  signature covering exactly that element; issuer, audience, recipient, time window and the sign-in it answers are
  checked, and each assertion is used once.
- **Two-step sign-in:** authenticator app codes (RFC 6238; the secret encrypted with the vault key, no code accepted
  twice) and one-time recovery codes; the `mfa_required` setting makes it optional, required for administrators, or
  required for everyone with a password.
- **Rate limits:** sign-in, tokens, single sign-on and runner registration allow 60 a minute per address. Webhooks,
  API tasks and MCP allow 1,200. Going over returns 429 `CR.RATE_LIMITED` with `Retry-After`. Behind a reverse proxy the
  limits count the first `X-Forwarded-For` address; calls made on this computer without a proxy are not limited.
- **HTTPS:** the Command Center serves HTTPS itself once `ooa tls` gives it a certificate (TLS 1.2 and 1.3 only; the
  .pfx password is stored encrypted). Served directly over HTTPS, with a `public_url` starting `https://`, or when a
  proxy sends `X-Forwarded-Proto: https`, the session cookie gets `Secure` and responses carry
  `Strict-Transport-Security`. Runners trust a self-signed certificate only when registered with `--ca`.
- **Other protections (unchanged):** CSRF header, SameSite=Strict cookie, Content-Security-Policy, X-Frame-Options.

## Agents and runners

- An agent version's SHA-256, taken at check-in, is checked again every time the version is read. A version changed in
  the database is never sent to a runner or editor (`CR.AGENT_TAMPERED`).
- A runner newer than the Command Center (`ControlRoom.VERSION`) gets no work. The Runners page marks it "update the
  Command Center".
- License seats apply to check-in and runs while a license is installed (see [LICENSING.md](LICENSING.md)). Deleting
  a runner, or removing a temporary runner, frees its seat.
- A run is only given to a runner that has the toolkits (and versions) the agent needs; otherwise it fails with
  `RUN.TOOLKIT_MISSING`.
- Runner secrets are replaced every `runner_secret_days`; only a hash is stored, and the previous secret is accepted
  for a short overlap.

## Audit

- Records are chained with HMAC-SHA-256 under a key kept encrypted by the vault key, so the chain cannot be rebuilt by
  someone who can only write to the database.
- An hourly checkpoint (position and hash) is appended to `audit-checkpoints.log` outside the database; verification
  compares against it, so removing the newest records is detected.
- Retention archives older records to compressed files before removing them, and an `audit.purged` record keeps the
  remaining chain verifiable.
- Rotating the vault key re-encrypts the chain key, two-step secrets, the install id and the database and HTTPS
  passwords along with the credentials.

## Not done yet

- Writing audit checkpoints to a write-once store directly (copy `audit-checkpoints.log` to one meanwhile).
