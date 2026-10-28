# 04. Network, ports, proxies and certificates

## What this covers / audience

Every network connection the product makes or accepts, how runners reach the Command Center, how proxies are
handled on both sides, how to turn on HTTPS with a company certificate or a test certificate, how runners trust it,
and firewall rules. For network, firewall, PKI and security teams.

## Contents

1. Ports and directions
2. The runner connection: outbound only, long poll
3. Runner proxies: Windows proxy, PAC and WPAD, fixed proxy, proxy sign-in
4. The Command Center's outgoing proxy
5. HTTPS on the Command Center
6. Company certificates
7. Self-signed certificates for tests
8. Certificate trust on runners
9. Load balancers and reverse proxies
10. Firewall rules
11. Local-only listeners

## 1. Ports and directions

| From | To | Port / protocol | Purpose | Configurable |
| --- | --- | --- | --- | --- |
| Browsers, API clients, AI assistants (MCP) | Command Center | TCP 8720, HTTP or HTTPS | Console, REST API, `/api/mcp`, webhooks (`/api/hooks/...`), API tasks | `--port`, `-Port`, `PORT`, `OOA_PORT` |
| Runners | Command Center | TCP 8720 (or 443 through a load balancer), HTTPS recommended | Registration, heartbeats, work (long poll), events, results, credentials, update files | The runner's URL |
| Load balancer | Command Center nodes | TCP 8720 | Traffic and health probes (`/api/health`, `/api/health/ready`) | - |
| Command Center | SQL Server | TCP 1433 (or the instance's port) | Database | `ooa db configure --port`, `--instance` |
| Command Center | PostgreSQL | TCP 5432 | Database | `--port` |
| Command Center | Mail server | TCP 587 by default (STARTTLS), or SSL/TLS, or none | Licence reminders, workflow notifications | `mail_smtp_host`, `mail_smtp_port`, `mail_smtp_security` |
| Command Center | SIEM | UDP or TCP, the port you set (often 514) | Audit records as syslog RFC 5424 | `syslog_target`, `syslog_protocol` |
| Command Center | Identity provider | HTTPS 443 | OpenID Connect discovery, tokens and keys; SAML metadata is served by the Command Center | `sso_issuer` |
| Command Center | External vaults, Git remote, AI model providers, IMAP, Microsoft Graph, ServiceNow, polled URLs, callback URLs, promotion targets | Their own ports, usually HTTPS 443 (IMAP 993) | Features configured by administrators | Each feature's settings; outgoing proxy |
| Command Center | Key store at start | HTTPS 443 and metadata endpoints | Vault key from Azure Key Vault (`login.microsoftonline.com`, `*.vault.azure.net`, `169.254.169.254`), AWS Secrets Manager (`secretsmanager.REGION.amazonaws.com`, `169.254.169.254`, `169.254.170.2`), Google (`metadata.google.internal`, `secretmanager.googleapis.com`) | `OOA_MASTER_KEY_SOURCE` |
| Command Center | Template catalogue | HTTPS 443 | `https://virupakshaprabhu.github.io/OOA_Customer_Build/templates.json` by default | `template_catalog_url` (or `off`) |
| Runner (agents) | Applications they automate | Any | Whatever the agents do | Agent design |
| Studio, local runner | Command Center on the same computer | Loopback | See section 11 | - |

No connection is ever made from the Command Center to a runner.

## 2. The runner connection: outbound only, long poll

- A runner registers once with a one-time key (`ooa agent register --url ... --key ooareg_...`) and receives a device
  id and a secret, saved in `%USERPROFILE%\.ooa-agent\agent.json` (the Command Center keeps only a hash).
- Every request carries the headers `X-OOA-Device` and `X-OOA-Device-Secret` and goes to `/api/agent/...`.
- **Heartbeat** every 10 seconds: version, build, screen state, running run, toolkits. The answer carries stop,
  pause and resume requests, local triggers, the log level, the after-run action, secret rotation and update offers.
  A runner is shown **online** while its last heartbeat is under 60 seconds old.
- **Work**: an idle runner posts `jobs/next?wait=25`. The Command Center holds the request for up to 25 seconds and
  answers the moment a run is queued for it, so runs start without polling delay. Proxies and load balancers must
  allow idle requests of at least **30 seconds** (60 recommended).
- Requests time out after 60 seconds; update file downloads after 5 minutes. A result that cannot be delivered is
  retried 6 times over about a minute.
- If a runner stops reporting for 2 minutes while running an agent, the Command Center fails that run with
  `AGENT.LOST`.
- Runners can sit in any network that can reach the Command Center's address; no inbound port is opened on runners.

## 3. Runner proxies

Set at registration or later with `ooa agent network` (restart the runner afterwards):

```
ooa agent register --url https://ooa.example.com --key ooareg_... --proxy system
ooa agent network --proxy http://proxy.corp:8080
ooa agent network --proxy-user CORP\rpa-proxy --proxy-password-env PROXY_PW
ooa agent network --proxy none
ooa agent network                      (shows the current settings)
```

| Setting | Meaning |
| --- | --- |
| `--proxy system` (default) | The Windows proxy settings of the account the runner runs as, including automatic configuration scripts (PAC) and WPAD discovery through WinHTTP |
| `--proxy none` | Connect directly |
| `--proxy http://host:port` | A fixed proxy (an `https://` proxy address is accepted too) |
| `--proxy-user NAME` with `--proxy-password-env VAR` | Basic sign-in at the proxy. The password is read from the variable and stored encrypted with Windows DPAPI (machine scope, so a runner service and the installer can both read it). Basic sign-in for HTTPS tunnelling is enabled by the runner. |
| `--ca FILE` | A certificate to trust (section 8) |

Notes:

- A Command Center on `localhost`, `127.*` or `[::1]` is always reached directly.
- Proxies that accept only Windows integrated sign-in (NTLM, Kerberos) are not supported: allow the Command Center's
  address without sign-in, or use Basic.
- A runner service runs as Local System (or the `--account` given), whose Windows proxy settings can differ from the
  user's. Use a fixed proxy for services.
- Setup package: `-Proxy system|none|URL`; MSI: `PROXY=`.
- `ooa agent diagnose` shows which proxy is used for the Command Center's address.

## 4. The Command Center's outgoing proxy

Administration > Settings > Outgoing connections:

| Setting | Format | Effect |
| --- | --- | --- |
| `outbound_proxy` | `http://host:port` | The Command Center's own outgoing HTTP and HTTPS connections (single sign-on, Git sync, AI model connections, external vaults, polled triggers, callbacks) go through this proxy |
| `outbound_proxy_bypass` | Host names, wildcards allowed, for example `*.example.com, 10.*` | Reached directly. `localhost`, `127.*` and `[::1]` are always direct. |

- Every node checks the setting every minute, so a change reaches all nodes within a minute.
- The proxy cannot ask for a user name and password.
- Clearing the setting undoes only what it set: Java proxy properties given at start in `JAVA_OPTS` stay.
- The vault key is fetched from a key store at start, before settings are read; if that path needs a proxy, give
  the standard Java proxy properties in `JAVA_OPTS`.
- In a SaaS region the setting does nothing; the region's own network decides.

## 5. HTTPS on the Command Center

The Command Center serves HTTPS itself on its port once it has a certificate. Only TLS 1.2 and 1.3 are offered.
Stop the server, run the `ooa tls` commands from `bin`, then start it again.

| Command | Effect |
| --- | --- |
| `ooa tls configure --pfx FILE` | Use a PKCS#12 file (`.pfx`/`.p12`) with the private key and chain |
| `ooa tls set-password [--from-env VAR]` | Store the file's password, encrypted with the vault key (asked for when no variable is given) |
| `ooa tls self-signed [--host NAME] [--days N]` | Make a test certificate (default validity 825 days) |
| `ooa tls show` | Subject, issuer, names and validity of the certificate in use |
| `ooa tls export-cert --out FILE` | The certificate without its key (PEM), for runners and browsers |
| `ooa tls off` | Back to plain HTTP: `ooa-tls.properties` is renamed to `ooa-tls.properties.off` |

Every command takes `--data DIR`. Settings live in `DATA\config\ooa-tls.properties`.

With HTTPS on:

- The port answers HTTPS only; plain HTTP on it no longer works.
- The session cookie is `Secure`; responses carry `Strict-Transport-Security: max-age=31536000`.
- The start-up lines show `HTTPS: on (subject, valid until date[, self-signed])`.
- `ooa check` warns when fewer than 30 days of validity remain and fails when expired.
- Set `public_url` (Administration > Settings) to the HTTPS address people use; single sign-on redirects use it.

## 6. Company certificates

1. Request a server certificate whose names (Subject Alternative Names) include every address people and runners
   use: the DNS name (for example `ooa.company.com`), and each node's name if runners address nodes directly.
2. Export it with its private key and chain as a `.pfx`.
3. Copy it to a folder readable by the service account, then:
   ```
   ooa tls configure --pfx D:\OOA\certs\ooa.pfx --data D:\OOA\cc
   set OOA_PFX_PW=...
   ooa tls set-password --from-env OOA_PFX_PW --data D:\OOA\cc
   ooa tls show --data D:\OOA\cc
   ```
4. Restart the service. Renewal: replace the file (same path, or `configure` again), store the new password, restart.

A certificate from an authority that Windows and Java already trust (a public CA, or a company CA rolled out by Group
Policy) needs nothing more on runners.

## 7. Self-signed certificates for tests

```
ooa tls self-signed --host ooa-test.corp.local --data D:\OOA\cc
ooa tls export-cert --out C:\temp\ooa.cer --data D:\OOA\cc
```

The certificate is RSA 3072, valid 825 days unless `--days` says otherwise, named for the host plus `localhost` and
`127.0.0.1`, stored as `DATA\config\ooa-self-signed.pfx` with a random password kept encrypted. Browsers will warn;
runners must be given the exported certificate.

## 8. Certificate trust on runners

A runner trusts, together:

1. Java's built-in certificate authorities.
2. On Windows, the Windows certificate stores (current user and local machine roots): a company CA deployed by Group
   Policy is trusted automatically.
3. The file given with `--ca` (`ooa agent register --ca`, `ooa agent network --ca`, `ooa agent move --ca`,
   `-CaFile`, `CA_FILE`). It is copied next to the registration as `ca.cer` and used from then on.

Certificate errors show in `ooa agent diagnose` as `FAIL Certificate not trusted`. The runner checks the certificate
name against the URL, so register with an address that is in the certificate.

## 9. Load balancers and reverse proxies

- No sticky sessions are needed: sessions and sign-ins in progress (two-step codes, SAML and OpenID Connect) are kept
  in the database.
- Terminate TLS at the load balancer (leave `ooa tls` off) or pass it through to `ooa tls` on each node.
- Send `X-Forwarded-Proto: https` when TLS ends in front, so cookies are `Secure` and HSTS is sent. Set `public_url`.
- Idle timeout at least 30 seconds (long polls hold for 25 seconds; the console's change feed also waits up to 25).
- Request size: allow at least the `max_upload_mb` setting plus a third (files travel as Base64 in JSON).
- Health probes: `/api/health` for liveness, `/api/health/ready` for readiness.
- Client addresses: the IP allow list and audit records use the TCP peer address. Behind a remote load balancer
  they see the balancer's address. Rate limits use the first `X-Forwarded-For` address only when the proxy runs on
  the same computer (loopback); otherwise they count per balancer address. Allow-list the balancer and filter clients
  at the balancer, or run the reverse proxy on the node itself.

## 10. Firewall rules

| Rule | Where | Notes |
| --- | --- | --- |
| Allow inbound TCP 8720 (or 443 on the balancer) from user networks and runner networks | Command Center or load balancer | Restrict further with `ip_allow_list` (up to 200 addresses or CIDR ranges) |
| Allow inbound TCP 8720 only from the load balancer subnet | Command Center nodes | When a balancer is used |
| Allow outbound TCP 1433 / 5432 to the database | Command Center | |
| Allow outbound HTTPS to the services you configure (identity provider, vaults, model providers, Git) | Command Center, or through `outbound_proxy` | |
| Allow outbound to SMTP and syslog targets | Command Center | |
| Allow outbound TCP to the Command Center address | Runners | Directly or through the proxy |
| No inbound rules | Runners | |
| Block outbound to private and metadata ranges | SaaS region nodes | Required; see [09](09-saas-and-multi-region-operations.md) |

## 11. Local-only listeners

| Listener | Address | Who uses it |
| --- | --- | --- |
| Internal plain-HTTP listener | `127.0.0.1`, a random port, only when HTTPS is on | The runner and Studio built into `ooa server`, so they need no certificate trust |
| Agent Studio standalone (`ooa studio`) | `127.0.0.1:8710` | The builder's browser on that computer; other host names are refused |
| Studio inside `ooa server` | `/studio/` on the Command Center port, offered only to browsers on the same computer | The builder |
| Browser connector native host | `127.0.0.1`, random port, with a random token | Agents and Studio on the same computer |
| Java application bridge | `127.0.0.1`, with a token | The runner, for Swing/AWT automation |

These trust any process on the same computer; keep runner and builder machines single-purpose.

## Related documents

- [README](README.md)
- [02. Installation on Windows](02-installation-windows.md)
- [06. High availability and scaling](06-high-availability-and-scaling.md)
- [08. Security hardening](08-security-hardening.md)
- [11. Monitoring, logging and troubleshooting](11-monitoring-logging-and-troubleshooting.md)
- [Enterprise checks](../ENTERPRISE_CHECKS.md)
- [Browser connector](../BROWSER_CONNECTOR.md)
