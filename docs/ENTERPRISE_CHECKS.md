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

## Settings checked by the server (Administration > Settings)

Every change is checked first and then saved in one transaction: either all of it is saved or none. Each change is
audited.

| Setting | Allowed | Effect |
| --- | --- | --- |
| `session_hours` | 1–720 | How long a sign-in lasts (session and cookie) |
| `max_upload_mb` | 1–1024 | Largest request (files travel as Base64 inside JSON) |
| `max_sessions_per_user` | 1–50 (default 5) | Sign-ins per person at once; the oldest end first |
| `password_min_length` | 12–64 | Shortest password allowed |
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
- **Lockout:** five failed sign-ins lock the account for 15 minutes.
- **Rate limits:** sign-in, tokens, single sign-on and runner registration allow 60 a minute per address. Webhooks,
  API tasks and MCP allow 1,200. Going over returns 429 `CR.RATE_LIMITED` with `Retry-After`. Behind a reverse proxy the
  limits count the first `X-Forwarded-For` address; calls made on this computer without a proxy are not limited.
- **HTTPS:** with a `public_url` starting `https://`, or when the proxy sends `X-Forwarded-Proto: https`, the session
  cookie gets `Secure` and responses carry `Strict-Transport-Security`.
- **Other protections (unchanged):** CSRF header, SameSite=Strict cookie, Content-Security-Policy, X-Frame-Options.

## Agents and runners

- An agent version's SHA-256, taken at check-in, is checked again every time the version is read. A version changed in
  the database is never sent to a runner or editor (`CR.AGENT_TAMPERED`).
- A runner newer than the Command Center (`ControlRoom.VERSION`) gets no work. The Runners page marks it "update the
  Command Center".
- License seats apply to check-in and runs while a license is installed (see [LICENSING.md](LICENSING.md)).

## Not done yet

- Multi-factor sign-in without single sign-on.
- Password history and expiry.
- Audit-log retention, and a keyed or externally anchored audit chain.
- Automatic runner updates.
- Toolkit inventory per runner.
- Approval before an agent goes to production.
